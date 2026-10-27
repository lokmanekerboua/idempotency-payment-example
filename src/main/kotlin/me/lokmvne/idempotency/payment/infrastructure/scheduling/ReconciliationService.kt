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
    private val paymentProperties: PaymentProperties,
    private val attemptGuard: me.lokmvne.idempotency.payment.application.service.PaymentAttemptGuard,
    private val objectMapper: com.fasterxml.jackson.databind.ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(ReconciliationService::class.java)
    private val workerId = "reconciliation-worker-${UUID.randomUUID()}"

    @Transactional
    @Scheduled(
        fixedDelayString = "\${app.payments.reconciliation.interval}",
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
            // A missing local reference cannot prove that Stripe did not act.
            idempotencyService.releaseLease(record.id)
            return
        }

        val payment = paymentRepository.findByIdWithLock(paymentId)
        if (payment == null) {
            logger.warn("Payment not found for operation ${record.id}")
            // A missing local reference cannot prove that Stripe did not act.
            idempotencyService.releaseLease(record.id)
            return
        }

        if (payment.isTerminal()) {
            logger.info("Payment ${payment.id} already in terminal state: ${payment.status}")
            markIdempotencyAsCompleted(record, payment)
            return
        }

        if (payment.providerPaymentId == null) {
            // Stripe may remove keys after 24 hours. Do not make a fresh create call near or after that boundary.
            if (record.createdAt.isBefore(Instant.now().minusSeconds(23 * 60 * 60))) {
                logger.error("Payment {} needs manual reconciliation: provider key is too old", payment.id)
                idempotencyService.releaseLease(record.id)
                return
            }
            try {
                val result = paymentProviderPort.createPaymentIntent(
                    PaymentProviderPort.CreatePaymentIntentRequest(
                        amountCents = payment.amountCents, currency = payment.currency, orderId = payment.orderId,
                        providerIdempotencyKey = requireNotNull(payment.providerIdempotencyKey),
                        metadata = mapOf("order_id" to payment.orderId.toString(), "payment_id" to payment.id.toString())
                    )
                )
                payment.providerPaymentId = result.providerPaymentId
                payment.status = mapStripeStatusToPaymentStatus(result.status)
                paymentRepository.save(payment)
                if (payment.status == PaymentStatus.CANCELED) attemptGuard.releaseForCanceled(payment)
                idempotencyService.markAsCompleted(record.id, 201, objectMapper.writeValueAsString(mapOf(
                    "paymentId" to payment.id, "clientSecret" to result.clientSecret, "status" to payment.status.name
                )), payment.id)
            } catch (e: Exception) {
                logger.error("Payment {} remains uncertain; same-key recovery failed", payment.id, e)
                idempotencyService.releaseLease(record.id)
            }
            return
        }

        try {
            val providerStatus = paymentProviderPort.retrievePaymentIntent(requireNotNull(payment.providerPaymentId))
            val mappedStatus = mapStripeStatusToPaymentStatus(providerStatus.status)

            if (payment.status != mappedStatus) {
                payment.status = mappedStatus
                paymentRepository.save(payment)
                logger.info("Updated payment ${payment.id} status to $mappedStatus from provider")
            }

            if (payment.status == PaymentStatus.CANCELED) attemptGuard.releaseForCanceled(payment)
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
            // A missing local reference cannot prove that Stripe did not act.
            idempotencyService.releaseLease(record.id)
            return
        }

        val refund = refundRepository.findByIdWithLock(refundId)
        if (refund == null) {
            logger.warn("Refund not found for operation ${record.id}")
            // A missing local reference cannot prove that Stripe did not act.
            idempotencyService.releaseLease(record.id)
            return
        }

        if (refund.isTerminal()) {
            logger.info("Refund ${refund.id} already in terminal state: ${refund.status}")
            markRefundIdempotencyAsCompleted(record, refund)
            return
        }

        if (refund.providerRefundId == null) {
            if (record.createdAt.isBefore(Instant.now().minusSeconds(23 * 60 * 60))) {
                logger.error("Refund {} needs manual reconciliation: provider key is too old", refund.id)
                idempotencyService.releaseLease(record.id)
                return
            }
            try {
                val payment = paymentRepository.findById(refund.paymentId).orElseThrow()
                val result = paymentProviderPort.createRefund(PaymentProviderPort.CreateRefundRequest(
                    providerPaymentId = requireNotNull(payment.providerPaymentId), amountCents = refund.amountCents,
                    providerIdempotencyKey = requireNotNull(refund.providerIdempotencyKey),
                    metadata = mapOf("payment_id" to refund.paymentId.toString(), "refund_id" to refund.id.toString())
                ))
                refund.providerRefundId = result.providerRefundId
                refund.status = when (result.status) {
                    "succeeded" -> me.lokmvne.idempotency.refund.domain.enum.RefundStatus.SUCCEEDED
                    "failed" -> me.lokmvne.idempotency.refund.domain.enum.RefundStatus.FAILED
                    "canceled" -> me.lokmvne.idempotency.refund.domain.enum.RefundStatus.CANCELED
                    else -> me.lokmvne.idempotency.refund.domain.enum.RefundStatus.PENDING
                }
                refundRepository.save(refund)
                markRefundIdempotencyAsCompleted(record, refund)
            } catch (e: Exception) {
                logger.error("Refund {} remains uncertain; same-key recovery failed", refund.id, e)
                idempotencyService.releaseLease(record.id)
            }
            return
        }

        val providerStatus = paymentProviderPort.retrieveRefundStatus(refund.providerRefundId!!)
        refund.status = when (providerStatus) {
            "succeeded" -> me.lokmvne.idempotency.refund.domain.enum.RefundStatus.SUCCEEDED
            "failed" -> me.lokmvne.idempotency.refund.domain.enum.RefundStatus.FAILED
            "canceled" -> me.lokmvne.idempotency.refund.domain.enum.RefundStatus.CANCELED
            else -> me.lokmvne.idempotency.refund.domain.enum.RefundStatus.PENDING
        }
        refundRepository.save(refund)
        if (refund.isTerminal()) markRefundIdempotencyAsCompleted(record, refund)
        else idempotencyService.releaseLease(record.id)
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
