package me.lokmvne.idempotency.refund.application.service

import me.lokmvne.idempotency.idempotency.application.service.IdempotencyService
import me.lokmvne.idempotency.idempotency.domain.enum.OperationType
import me.lokmvne.idempotency.payment.domain.enum.PaymentStatus
import me.lokmvne.idempotency.payment.domain.exception.PaymentNotFoundException
import me.lokmvne.idempotency.payment.domain.model.Payment
import me.lokmvne.idempotency.payment.infrastructure.persistence.PaymentEntity
import me.lokmvne.idempotency.payment.infrastructure.persistence.PaymentRepository
import me.lokmvne.idempotency.refund.domain.enum.RefundStatus
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
    private val idempotencyRecordRepository: me.lokmvne.idempotency.idempotency.infrastructure.persistence.IdempotencyRecordRepository,
    private val tx: org.springframework.transaction.support.TransactionTemplate,
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

    fun createRefund(
        command: CreateRefundCommand,
        idempotencyKey: UUID
    ): CreateRefundResult {
        val userId = command.userId

        val reservation = idempotencyService.reserveIdempotencyKey(
            userId = userId,
            operationType = OperationType.CREATE_REFUND,
            idempotencyKey = idempotencyKey,
            requestParameters = mapOf(
                "paymentId" to command.paymentId.toString(),
                "amountCents" to command.amountCents
            )
        )

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

        if (!reservation.isNew) {
            throw me.lokmvne.idempotency.idempotency.domain.exception.IdempotencyKeyConflictException(
                userId,
                OperationType.CREATE_REFUND.name,
                idempotencyKey
            )
        }

        val prepared = tx.execute {
            val payment = loadPaymentWithLock(command.paymentId)
            if (payment.userId != userId) throw PaymentNotFoundException(command.paymentId)
            require(command.amountCents > 0) { "Refund amount must be positive" }
            validatePaymentForRefund(payment, command.amountCents)
            val reserved = refundRepository.findByPaymentId(command.paymentId)
                .filter { it.status == RefundStatus.SUCCEEDED || it.status == RefundStatus.PENDING }
                .sumOf { it.amountCents }
            val maxRefundable = payment.amountCents - reserved
            if (command.amountCents > maxRefundable) throw RefundAmountExceededException(
                command.paymentId, command.amountCents, maxRefundable
            )
            val providerPaymentId = requireNotNull(payment.providerPaymentId)
            val saved = refundRepository.saveAndFlush(RefundEntity(
                paymentId = payment.id, userId = userId, amountCents = command.amountCents,
                currency = payment.currency, status = RefundStatus.PENDING,
                providerIdempotencyKey = "${reservation.record.id}_${payment.id}"
            ))
            val record = idempotencyRecordRepository.findByIdWithLock(reservation.record.id)
                ?: error("Idempotency record not found")
            record.resourceId = saved.id
            idempotencyRecordRepository.save(record)
            saved to providerPaymentId
        } ?: error("Refund preparation failed")
        val (refund, providerPaymentId) = prepared
        try {
            val response = paymentProviderPort.createRefund(
                me.lokmvne.idempotency.payment.application.port.output.PaymentProviderPort.CreateRefundRequest(
                    providerPaymentId = providerPaymentId, amountCents = refund.amountCents,
                    providerIdempotencyKey = requireNotNull(refund.providerIdempotencyKey),
                    metadata = mapOf("payment_id" to refund.paymentId.toString(), "refund_id" to refund.id.toString())
                )
            )
            return tx.execute {
                val saved = refundRepository.findByIdWithLock(refund.id) ?: error("Refund not found")
                saved.providerRefundId = response.providerRefundId
                saved.status = mapStripeStatusToRefundStatus(response.status)
                refundRepository.save(saved)
                val body = com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                    mapOf("refundId" to saved.id, "status" to saved.status.name)
                )
                idempotencyService.markAsCompleted(reservation.record.id, 201, body, saved.id)
                CreateRefundResult(toDomain(saved), false)
            } ?: error("Refund completion failed")
        } catch (e: Exception) {
            logger.error("Refund outcome unknown for payment ${command.paymentId}; reconciliation required", e)
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
