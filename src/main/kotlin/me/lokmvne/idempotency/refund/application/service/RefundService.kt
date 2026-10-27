package me.lokmvne.idempotency.refund.application.service

import me.lokmvne.idempotency.idempotency.application.service.IdempotencyService
import me.lokmvne.idempotency.idempotency.domain.enum.OperationType
import me.lokmvne.idempotency.payment.domain.enum.PaymentStatus
import me.lokmvne.idempotency.payment.domain.exception.PaymentNotFoundException
import me.lokmvne.idempotency.payment.domain.model.Payment
import me.lokmvne.idempotency.payment.infrastructure.persistence.PaymentEntity
import me.lokmvne.idempotency.payment.infrastructure.persistence.PaymentRepository
import me.lokmvne.idempotency.refund.domain.enum.RefundStatus
import me.lokmvne.idempotency.refund.domain.exception.RefundAmountExceededException
import me.lokmvne.idempotency.refund.domain.model.Refund
import me.lokmvne.idempotency.refund.infrastructure.persistence.RefundEntity
import me.lokmvne.idempotency.refund.infrastructure.persistence.RefundRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class RefundService(
    private val refundRepository: RefundRepository,
    private val paymentRepository: PaymentRepository,
    private val idempotencyService: IdempotencyService,
    private val paymentProviderPort: me.lokmvne.idempotency.payment.application.port.output.PaymentProviderPort
) {
    private val logger = LoggerFactory.getLogger(RefundService::class.java)

    data class CreateRefundCommand(
        val paymentId: UUID,
        val amountCents: Long,
        val userId: UUID
    )

    data class CreateRefundResult(
        val refund: Refund,
        val isCached: Boolean
    )

    @Transactional
    fun createRefund(
        command: CreateRefundCommand,
        idempotencyKey: UUID
    ): CreateRefundResult {
        val userId = command.userId

        val cachedResponse = idempotencyService.getCachedResponse(
            userId = userId,
            operationType = OperationType.CREATE_REFUND,
            idempotencyKey = idempotencyKey
        )

        if (cachedResponse != null) {
            val refund = refundRepository.findById(cachedResponse.resourceId)
                .orElseThrow { IllegalArgumentException("Refund not found: ${cachedResponse.resourceId}") }
            return CreateRefundResult(
                refund = toDomain(refund),
                isCached = true
            )
        }

        val reservation = idempotencyService.reserveIdempotencyKey(
            userId = userId,
            operationType = OperationType.CREATE_REFUND,
            idempotencyKey = idempotencyKey,
            requestParameters = mapOf(
                "paymentId" to command.paymentId.toString(),
                "amountCents" to command.amountCents
            )
        )

        if (!reservation.isNew) {
            throw me.lokmvne.idempotency.idempotency.domain.exception.IdempotencyKeyConflictException(
                userId,
                OperationType.CREATE_REFUND.name,
                idempotencyKey
            )
        }

        val payment = loadPaymentWithLock(command.paymentId)
        validatePaymentForRefund(payment, command.amountCents)

        val existingRefunds = refundRepository.findByPaymentId(command.paymentId)
        val totalRefunded = existingRefunds
            .filter { it.status == RefundStatus.SUCCEEDED }
            .sumOf { it.amountCents }

        val pendingRefunds = existingRefunds
            .filter { it.status == RefundStatus.PENDING }
            .sumOf { it.amountCents }

        val maxRefundable = payment.amountCents - totalRefunded - pendingRefunds

        if (command.amountCents > maxRefundable) {
            throw RefundAmountExceededException(
                paymentId = command.paymentId,
                requestedAmount = command.amountCents,
                refundableAmount = maxRefundable
            )
        }

        val providerIdempotencyKey = "${reservation.record.id}_${command.paymentId}"

        val refundEntity = RefundEntity(
            paymentId = command.paymentId,
            userId = userId,
            amountCents = command.amountCents,
            currency = payment.currency,
            status = RefundStatus.PENDING,
            providerIdempotencyKey = providerIdempotencyKey
        )

        val savedRefund = refundRepository.save(refundEntity)

        try {
            val providerRequest = me.lokmvne.idempotency.payment.application.port.output.PaymentProviderPort.CreateRefundRequest(
                providerPaymentId = payment.providerPaymentId!!,
                amountCents = command.amountCents,
                providerIdempotencyKey = providerIdempotencyKey,
                metadata = mapOf(
                    "payment_id" to command.paymentId.toString(),
                    "refund_id" to savedRefund.id.toString()
                )
            )

            val providerResponse = paymentProviderPort.createRefund(providerRequest)

            savedRefund.providerRefundId = providerResponse.providerRefundId
            savedRefund.status = mapStripeStatusToRefundStatus(providerResponse.status)
            refundRepository.save(savedRefund)

            val responseJson = com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                mapOf(
                    "refundId" to savedRefund.id,
                    "status" to savedRefund.status.name
                )
            )

            idempotencyService.markAsCompleted(
                recordId = reservation.record.id,
                httpStatus = 201,
                responseBody = responseJson,
                resourceId = savedRefund.id
            )

            return CreateRefundResult(
                refund = toDomain(savedRefund),
                isCached = false
            )

        } catch (e: Exception) {
            savedRefund.status = RefundStatus.FAILED
            refundRepository.save(savedRefund)

            idempotencyService.markAsFailed(reservation.record.id)

            logger.error("Refund creation failed for payment ${command.paymentId}", e)
            throw e
        }
    }

    @Transactional(readOnly = true)
    fun getRefund(refundId: UUID): Refund {
        val refundEntity = refundRepository.findById(refundId)
            .orElseThrow { IllegalArgumentException("Refund not found: $refundId") }
        return toDomain(refundEntity)
    }

    @Transactional(readOnly = true)
    fun getRefundsByPayment(paymentId: UUID): List<Refund> {
        return refundRepository.findByPaymentId(paymentId).map { toDomain(it) }
    }

    private fun loadPaymentWithLock(paymentId: UUID): PaymentEntity {
        return paymentRepository.findByIdWithLock(paymentId)
            ?: throw PaymentNotFoundException(paymentId)
    }

    private fun validatePaymentForRefund(payment: PaymentEntity, amountCents: Long) {
        if (payment.status != PaymentStatus.SUCCEEDED) {
            throw IllegalStateException("Payment must be succeeded to refund. Current status: ${payment.status}")
        }

        if (amountCents > payment.amountCents) {
            throw RefundAmountExceededException(
                paymentId = payment.id,
                requestedAmount = amountCents,
                refundableAmount = payment.amountCents
            )
        }
    }

    private fun mapStripeStatusToRefundStatus(stripeStatus: String): RefundStatus {
        return when (stripeStatus) {
            "succeeded" -> RefundStatus.SUCCEEDED
            "pending" -> RefundStatus.PENDING
            "failed", "canceled" -> RefundStatus.FAILED
            else -> RefundStatus.PENDING
        }
    }

    private fun toDomain(entity: RefundEntity): Refund {
        return Refund(
            id = entity.id,
            paymentId = entity.paymentId,
            userId = entity.userId,
            amountCents = entity.amountCents,
            currency = entity.currency,
            status = entity.status,
            providerRefundId = entity.providerRefundId,
            providerIdempotencyKey = entity.providerIdempotencyKey,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
            version = entity.version
        )
    }
}

class RefundAmountExceededException(
    val paymentId: UUID,
    val requestedAmount: Long,
    val refundableAmount: Long
) : RuntimeException("Refund amount $requestedAmount exceeds refundable amount $refundableAmount for payment $paymentId")
