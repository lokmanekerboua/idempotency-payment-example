package me.lokmvne.idempotency.payment.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import me.lokmvne.idempotency.idempotency.application.service.IdempotencyService
import me.lokmvne.idempotency.idempotency.domain.enum.OperationType
import me.lokmvne.idempotency.idempotency.domain.exception.IdempotencyKeyConflictException
import me.lokmvne.idempotency.order.domain.exception.OrderNotFoundException
import me.lokmvne.idempotency.order.domain.model.Order
import me.lokmvne.idempotency.order.infrastructure.persistence.OrderEntity
import me.lokmvne.idempotency.order.infrastructure.persistence.OrderRepository
import me.lokmvne.idempotency.payment.application.port.input.CreatePaymentCommand
import me.lokmvne.idempotency.payment.application.port.output.PaymentProviderPort
import me.lokmvne.idempotency.payment.domain.enum.PaymentStatus
import me.lokmvne.idempotency.payment.domain.exception.InvalidPaymentStateException
import me.lokmvne.idempotency.payment.domain.exception.PaymentNotFoundException
import me.lokmvne.idempotency.payment.domain.model.Payment
import me.lokmvne.idempotency.payment.infrastructure.persistence.PaymentEntity
import me.lokmvne.idempotency.payment.infrastructure.persistence.PaymentRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class PaymentService(
    private val paymentRepository: PaymentRepository,
    private val orderRepository: OrderRepository,
    private val idempotencyService: IdempotencyService,
    private val paymentProviderPort: PaymentProviderPort,
    private val objectMapper: ObjectMapper,
    private val outboxService: me.lokmvne.idempotency.outbox.application.service.OutboxService,
    private val metricsService: me.lokmvne.idempotency.shared.logging.MetricsService
) {
    private val logger = LoggerFactory.getLogger(PaymentService::class.java)

    data class CreatePaymentResult(
        val payment: Payment,
        val clientSecret: String?,
        val isCached: Boolean
    )

    @Transactional
    fun createPayment(
        command: CreatePaymentCommand,
        idempotencyKey: UUID
    ): CreatePaymentResult {
        val startTime = System.currentTimeMillis()

        val userId = command.userId

        val cachedResponse = idempotencyService.getCachedResponse(
            userId = userId,
            operationType = OperationType.CREATE_PAYMENT,
            idempotencyKey = idempotencyKey
        )

        if (cachedResponse != null) {
            metricsService.incrementPaymentDuplicateRequests()
            val payment = paymentRepository.findById(cachedResponse.resourceId)
                .orElseThrow { PaymentNotFoundException(cachedResponse.resourceId) }
            return CreatePaymentResult(
                payment = toDomain(payment),
                clientSecret = extractClientSecret(cachedResponse.body),
                isCached = true
            )
        }

        val reservation = idempotencyService.reserveIdempotencyKey(
            userId = userId,
            operationType = OperationType.CREATE_PAYMENT,
            idempotencyKey = idempotencyKey,
            requestParameters = mapOf(
                "orderId" to command.orderId.toString()
            )
        )

        if (!reservation.isNew) {
            metricsService.incrementIdempotencyConflicts()
            throw IdempotencyKeyConflictException(userId, OperationType.CREATE_PAYMENT.name, idempotencyKey)
        }

        metricsService.incrementPaymentCreated()

        val order = loadOrderWithLock(command.orderId)
        validateOrderForPayment(order)

        val providerIdempotencyKey = "${reservation.record.id}_${command.orderId}"

        val paymentEntity = PaymentEntity(
            orderId = command.orderId,
            userId = userId,
            amountCents = order.amountCents,
            currency = order.currency,
            status = PaymentStatus.PROCESSING,
            providerIdempotencyKey = providerIdempotencyKey
        )

        val savedPayment = paymentRepository.save(paymentEntity)

        try {
            val providerRequest = PaymentProviderPort.CreatePaymentIntentRequest(
                amountCents = order.amountCents,
                currency = order.currency,
                orderId = command.orderId,
                providerIdempotencyKey = providerIdempotencyKey,
                metadata = mapOf(
                    "order_id" to command.orderId.toString(),
                    "payment_id" to savedPayment.id.toString()
                )
            )

            val providerResponse = paymentProviderPort.createPaymentIntent(providerRequest)

            savedPayment.providerPaymentId = providerResponse.providerPaymentId
            savedPayment.status = mapStripeStatusToPaymentStatus(providerResponse.status)
            paymentRepository.save(savedPayment)

            val responseJson = objectMapper.writeValueAsString(
                mapOf(
                    "paymentId" to savedPayment.id,
                    "clientSecret" to providerResponse.clientSecret,
                    "status" to savedPayment.status.name
                )
            )

            idempotencyService.markAsCompleted(
                recordId = reservation.record.id,
                httpStatus = 201,
                responseBody = responseJson,
                resourceId = savedPayment.id
            )

            // Publish payment created event
            outboxService.publishEvent(
                aggregateId = savedPayment.id,
                aggregateType = "Payment",
                eventType = "PaymentCreated",
                payload = mapOf(
                    "paymentId" to savedPayment.id,
                    "orderId" to savedPayment.orderId,
                    "userId" to savedPayment.userId,
                    "amountCents" to savedPayment.amountCents,
                    "currency" to savedPayment.currency,
                    "status" to savedPayment.status.name
                )
            )

            return CreatePaymentResult(
                payment = toDomain(savedPayment),
                clientSecret = providerResponse.clientSecret,
                isCached = false
            )

        } catch (e: Exception) {
            metricsService.incrementPaymentFailed()
            savedPayment.status = PaymentStatus.FAILED
            savedPayment.failureCode = "PROVIDER_ERROR"
            savedPayment.failureMessage = e.message
            paymentRepository.save(savedPayment)

            idempotencyService.markAsFailed(reservation.record.id)

            logger.error("Payment creation failed for order ${command.orderId}", e)
            throw e
        } finally {
            val duration = System.currentTimeMillis() - startTime
            metricsService.recordPaymentProcessingDuration(duration)
        }
    }

    @Transactional(readOnly = true)
    fun getPayment(paymentId: UUID): Payment {
        val paymentEntity = paymentRepository.findById(paymentId)
            .orElseThrow { PaymentNotFoundException(paymentId) }
        return toDomain(paymentEntity)
    }

    @Transactional(readOnly = true)
    fun getPaymentsByOrder(orderId: UUID): List<Payment> {
        return paymentRepository.findByOrderId(orderId).map { toDomain(it) }
    }

    @Transactional(readOnly = true)
    fun getPaymentsByUser(userId: UUID): List<Payment> {
        return paymentRepository.findByUserId(userId).map { toDomain(it) }
    }

    private fun loadOrderWithLock(orderId: UUID): OrderEntity {
        return orderRepository.findByIdWithLock(orderId)
            ?: throw OrderNotFoundException(orderId)
    }

    private fun validateOrderForPayment(order: OrderEntity) {
        if (order.status != me.lokmvne.idempotency.order.domain.enum.OrderStatus.CREATED) {
            throw InvalidPaymentStateException(
                me.lokmvne.idempotency.payment.domain.enum.PaymentStatus.CREATED,
                me.lokmvne.idempotency.payment.domain.enum.PaymentStatus.PROCESSING
            )
        }

        val existingPayment = paymentRepository.findSuccessfulPaymentByOrderId(order.id)
        if (existingPayment != null) {
            throw IllegalStateException("Order ${order.id} already has a successful payment")
        }
    }

    private fun mapStripeStatusToPaymentStatus(stripeStatus: String): PaymentStatus {
        return when (stripeStatus) {
            "requires_payment_method", "requires_confirmation", "processing" -> PaymentStatus.PROCESSING
            "requires_action" -> PaymentStatus.REQUIRES_ACTION
            "succeeded" -> PaymentStatus.SUCCEEDED
            "canceled" -> PaymentStatus.CANCELED
            else -> PaymentStatus.RECONCILIATION_REQUIRED
        }
    }

    private fun toDomain(entity: PaymentEntity): Payment {
        return Payment(
            id = entity.id,
            orderId = entity.orderId,
            userId = entity.userId,
            amountCents = entity.amountCents,
            currency = entity.currency,
            status = entity.status,
            provider = entity.provider,
            providerPaymentId = entity.providerPaymentId,
            providerIdempotencyKey = entity.providerIdempotencyKey,
            failureCode = entity.failureCode,
            failureMessage = entity.failureMessage,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
            version = entity.version
        )
    }

    private fun extractClientSecret(responseBody: String): String? {
        return try {
            val jsonNode = objectMapper.readTree(responseBody)
            jsonNode.get("clientSecret")?.asText()
        } catch (e: Exception) {
            null
        }
    }
}
