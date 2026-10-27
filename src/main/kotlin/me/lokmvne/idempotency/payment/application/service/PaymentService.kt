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
    private val attemptGuard: PaymentAttemptGuard,
    private val idempotencyRecordRepository: me.lokmvne.idempotency.idempotency.infrastructure.persistence.IdempotencyRecordRepository,
    private val tx: org.springframework.transaction.support.TransactionTemplate,
    private val metricsService: me.lokmvne.idempotency.shared.logging.MetricsService
) {
    private val logger = LoggerFactory.getLogger(PaymentService::class.java)

    data class CreatePaymentResult(
        val payment: Payment,
        val clientSecret: String?,
        val isCached: Boolean
    )

    fun createPayment(
        command: CreatePaymentCommand,
        idempotencyKey: UUID
    ): CreatePaymentResult {
        val startTime = System.currentTimeMillis()

        val userId = command.userId

        val reservation = idempotencyService.reserveIdempotencyKey(
            userId = userId,
            operationType = OperationType.CREATE_PAYMENT,
            idempotencyKey = idempotencyKey,
            requestParameters = mapOf(
                "orderId" to command.orderId.toString()
            )
        )

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

        if (!reservation.isNew) {
            metricsService.incrementIdempotencyConflicts()
            throw IdempotencyKeyConflictException(userId, OperationType.CREATE_PAYMENT.name, idempotencyKey)
        }

        val payment = tx.execute {
            val order = loadOrderWithLock(command.orderId)
            if (order.userId != userId) throw OrderNotFoundException(command.orderId)
            validateOrderForPayment(order)
            attemptGuard.reserve(order.id, reservation.record.id)
            val saved = paymentRepository.saveAndFlush(PaymentEntity(
                orderId = order.id, userId = userId, amountCents = order.amountCents,
                currency = order.currency, status = PaymentStatus.PROCESSING,
                providerIdempotencyKey = "${reservation.record.id}_${order.id}"
            ))
            val record = idempotencyRecordRepository.findByIdWithLock(reservation.record.id)
                ?: error("Idempotency record not found")
            record.resourceId = saved.id
            idempotencyRecordRepository.save(record)
            saved
        } ?: error("Payment preparation failed")
        // The resource, request parameters and provider key have committed before Stripe is called.
        try {
            val response = paymentProviderPort.createPaymentIntent(PaymentProviderPort.CreatePaymentIntentRequest(
                amountCents = payment.amountCents, currency = payment.currency, orderId = payment.orderId,
                providerIdempotencyKey = requireNotNull(payment.providerIdempotencyKey),
                metadata = mapOf("order_id" to payment.orderId.toString(), "payment_id" to payment.id.toString())
            ))
            return tx.execute {
                val saved = paymentRepository.findByIdWithLock(payment.id) ?: error("Payment not found")
                saved.providerPaymentId = response.providerPaymentId
                saved.status = mapStripeStatusToPaymentStatus(response.status)
                paymentRepository.save(saved)
                val responseJson = objectMapper.writeValueAsString(mapOf(
                    "paymentId" to saved.id, "clientSecret" to response.clientSecret, "status" to saved.status.name
                ))
                idempotencyService.markAsCompleted(reservation.record.id, 201, responseJson, saved.id)
                outboxService.publishEvent(saved.id, "Payment", "PaymentCreated", mapOf(
                    "paymentId" to saved.id, "orderId" to saved.orderId, "userId" to saved.userId,
                    "amountCents" to saved.amountCents, "currency" to saved.currency, "status" to saved.status.name
                ))
                if (saved.status == PaymentStatus.CANCELED) attemptGuard.releaseForCanceled(saved)
                CreatePaymentResult(toDomain(saved), response.clientSecret, false)
            } ?: error("Payment completion failed")
        } catch (e: Exception) {
            // Do not declare a timeout or lost response a failure; reconciliation reuses the same key.
            metricsService.incrementPaymentFailed()
            logger.error("Payment outcome unknown for order ${command.orderId}; reconciliation required", e)
            throw e
        } finally {
            metricsService.recordPaymentProcessingDuration(System.currentTimeMillis() - startTime)
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
