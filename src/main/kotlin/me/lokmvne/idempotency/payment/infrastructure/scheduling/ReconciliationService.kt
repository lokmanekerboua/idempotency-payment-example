package me.lokmvne.idempotency.payment.infrastructure.scheduling

import me.lokmvne.idempotency.idempotency.application.service.IdempotencyService
import me.lokmvne.idempotency.idempotency.domain.enum.ProcessingStatus
import me.lokmvne.idempotency.idempotency.infrastructure.persistence.IdempotencyRecordEntity
import me.lokmvne.idempotency.idempotency.infrastructure.persistence.IdempotencyRecordRepository
import me.lokmvne.idempotency.payment.application.port.output.PaymentProviderPort
import me.lokmvne.idempotency.payment.domain.enum.PaymentStatus
import me.lokmvne.idempotency.payment.infrastructure.persistence.PaymentEntity
import me.lokmvne.idempotency.payment.infrastructure.persistence.PaymentRepository
import me.lokmvne.idempotency.refund.infrastructure.persistence.RefundEntity
import me.lokmvne.idempotency.refund.infrastructure.persistence.RefundRepository
import me.lokmvne.idempotency.shared.config.PaymentProperties
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class ReconciliationService(
    private val idempotencyRecordRepository: IdempotencyRecordRepository,
    private val paymentRepository: PaymentRepository,
    private val refundRepository: RefundRepository,
    private val paymentProviderPort: PaymentProviderPort,
    private val idempotencyService: IdempotencyService,
    private val paymentProperties: PaymentProperties
) {
    private val logger = LoggerFactory.getLogger(ReconciliationService::class.java)
    private val workerId = "reconciliation-worker-${UUID.randomUUID()}"

    @Scheduled(
        fixedDelayString = "\${app.payments.reconciliation.interval.toMillis()}",
        initialDelay = 60000
    )
    fun reconcileStaleOperations() {
        if (!paymentProperties.reconciliation.enabled) {
            return
        }

        try {
            val now = Instant.now()
            val threshold = now.minus(paymentProperties.idempotency.processingTimeout)

            val staleRecords = idempotencyRecordRepository.findStaleInProgressRecords(
                now = now,
                threshold = threshold,
                limit = paymentProperties.reconciliation.batchSize
            )

            if (staleRecords.isEmpty()) {
                logger.debug("No stale operations to reconcile")
                return
            }

            logger.info("Found ${staleRecords.size} stale operations to reconcile")

            staleRecords.forEach { record ->
                reconcileOperation(record)
            }

        } catch (e: Exception) {
            logger.error("Error during reconciliation", e)
        }
    }

    @Transactional
    fun reconcileOperation(record: IdempotencyRecordEntity) {
        try {
            val claimed = idempotencyService.claimLease(record.id, workerId)

            if (!claimed) {
                logger.debug("Could not claim lease for operation ${record.id}")
                return
            }

            logger.info("Reconciling operation ${record.id} for ${record.operationType}")

            when (record.operationType) {
                me.lokmvne.idempotency.idempotency.domain.enum.OperationType.CREATE_PAYMENT -> {
                    reconcilePayment(record)
                }
                me.lokmvne.idempotency.idempotency.domain.enum.OperationType.CREATE_REFUND -> {
                    reconcileRefund(record)
                }
            }

        } catch (e: Exception) {
            logger.error("Error reconciling operation ${record.id}", e)
            idempotencyService.releaseLease(record.id)
        }
    }

    private fun reconcilePayment(record: IdempotencyRecordEntity) {
        val paymentId = record.resourceId
        if (paymentId == null) {
            logger.warn("Payment operation ${record.id} has no resource ID")
            idempotencyService.markAsFailed(record.id)
            return
        }

        val payment = paymentRepository.findById(paymentId).orElse(null)
        if (payment == null) {
            logger.warn("Payment not found for operation ${record.id}")
            idempotencyService.markAsFailed(record.id)
            return
        }

        if (payment.isTerminal()) {
            logger.info("Payment ${payment.id} already in terminal state: ${payment.status}")
            markIdempotencyAsCompleted(record, payment)
            return
        }

        if (payment.providerPaymentId == null) {
            logger.warn("Payment ${payment.id} has no provider payment ID")
            payment.status = PaymentStatus.FAILED
            payment.failureCode = "NO_PROVIDER_ID"
            payment.failureMessage = "Payment created but no provider ID assigned"
            paymentRepository.save(payment)
            idempotencyService.markAsFailed(record.id)
            return
        }

        try {
            val providerStatus = paymentProviderPort.retrievePaymentIntent(payment.providerPaymentId)
            val mappedStatus = mapStripeStatusToPaymentStatus(providerStatus.status)

            if (payment.status != mappedStatus) {
                payment.status = mappedStatus
                paymentRepository.save(payment)
                logger.info("Updated payment ${payment.id} status to $mappedStatus from provider")
            }

            if (payment.isTerminal()) {
                markIdempotencyAsCompleted(record, payment)
            } else {
                idempotencyService.releaseLease(record.id)
            }

        } catch (e: Exception) {
            logger.error("Failed to retrieve payment status from provider for ${payment.providerPaymentId}", e)
            payment.status = PaymentStatus.RECONCILIATION_REQUIRED
            paymentRepository.save(payment)
            idempotencyService.releaseLease(record.id)
        }
    }

    private fun reconcileRefund(record: IdempotencyRecordEntity) {
        val refundId = record.resourceId
        if (refundId == null) {
            logger.warn("Refund operation ${record.id} has no resource ID")
            idempotencyService.markAsFailed(record.id)
            return
        }

        val refund = refundRepository.findById(refundId).orElse(null)
        if (refund == null) {
            logger.warn("Refund not found for operation ${record.id}")
            idempotencyService.markAsFailed(record.id)
            return
        }

        if (refund.isTerminal()) {
            logger.info("Refund ${refund.id} already in terminal state: ${refund.status}")
            markRefundIdempotencyAsCompleted(record, refund)
            return
        }

        if (refund.providerRefundId == null) {
            logger.warn("Refund ${refund.id} has no provider refund ID")
            refund.status = me.lokmvne.idempotency.refund.domain.enum.RefundStatus.FAILED
            refundRepository.save(refund)
            idempotencyService.markAsFailed(record.id)
            return
        }

        logger.info("Refund reconciliation completed for ${refund.id}")
        idempotencyService.releaseLease(record.id)
    }

    private fun markRefundIdempotencyAsCompleted(record: IdempotencyRecordEntity, refund: RefundEntity) {
        val responseJson = com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
            mapOf(
                "refundId" to refund.id,
                "status" to refund.status.name
            )
        )

        idempotencyService.markAsCompleted(
            recordId = record.id,
            httpStatus = 201,
            responseBody = responseJson,
            resourceId = refund.id
        )

        logger.info("Marked refund operation ${record.id} as completed")
    }

    private fun markIdempotencyAsCompleted(record: IdempotencyRecordEntity, payment: PaymentEntity) {
        val responseJson = com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
            mapOf(
                "paymentId" to payment.id,
                "status" to payment.status.name
            )
        )

        idempotencyService.markAsCompleted(
            recordId = record.id,
            httpStatus = 201,
            responseBody = responseJson,
            resourceId = payment.id
        )

        logger.info("Marked operation ${record.id} as completed")
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
}
