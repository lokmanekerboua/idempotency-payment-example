package me.lokmvne.idempotency.webhook.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.stripe.exception.SignatureVerificationException
import com.stripe.net.Webhook
import me.lokmvne.idempotency.payment.application.port.output.PaymentProviderPort
import me.lokmvne.idempotency.payment.domain.enum.PaymentStatus
import me.lokmvne.idempotency.payment.infrastructure.persistence.PaymentRepository
import me.lokmvne.idempotency.refund.domain.enum.RefundStatus
import me.lokmvne.idempotency.refund.infrastructure.persistence.RefundRepository
import me.lokmvne.idempotency.shared.config.PaymentProperties
import me.lokmvne.idempotency.webhook.infrastructure.persistence.WebhookEventRepository
import me.lokmvne.idempotency.webhook.infrastructure.persistence.WebhookProcessingStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class WebhookService(
    private val webhookEventRepository: WebhookEventRepository,
    private val paymentRepository: PaymentRepository,
    private val refundRepository: RefundRepository,
    private val paymentProvider: PaymentProviderPort,
    private val paymentProperties: PaymentProperties,
    private val attemptGuard: me.lokmvne.idempotency.payment.application.service.PaymentAttemptGuard,
    private val objectMapper: ObjectMapper,
    private val outboxService: me.lokmvne.idempotency.outbox.application.service.OutboxService
) {
    data class WebhookProcessingResult(val success: Boolean, val message: String, val eventId: UUID)

    @Transactional
    fun handleStripeWebhook(payload: String, signature: String): WebhookProcessingResult {
        val secret = paymentProperties.provider.webhookSecret
        check(secret.isNotBlank()) { "Stripe webhook secret is not configured" }
        val event = try { Webhook.constructEvent(payload, signature, secret) }
        catch (e: SignatureVerificationException) { throw WebhookSignatureException("Invalid webhook signature", e) }
        val obj = objectMapper.readTree(payload).path("data").path("object")
        val objectId = obj.path("id").asText().takeIf { it.isNotBlank() }
        val id = UUID.randomUUID()
        val inserted = webhookEventRepository.insertIfAbsent(id, event.id, event.type, objectId, payload)
        val stored = webhookEventRepository.findByProviderAndProviderEventId("stripe", event.id)
            ?: error("Webhook event unavailable")
        if (inserted == 0) return WebhookProcessingResult(true, "Duplicate event", stored.id)
        when {
            event.type.startsWith("payment_intent.") -> syncPayment(requireNotNull(objectId))
            event.type.startsWith("refund.") -> syncRefund(requireNotNull(objectId))
            // Other event types are acknowledged without changing payment state.
        }
        stored.processingStatus = WebhookProcessingStatus.PROCESSED
        stored.processedAt = Instant.now()
        webhookEventRepository.save(stored)
        return WebhookProcessingResult(true, "Webhook processed", stored.id)
    }

    private fun syncPayment(providerId: String) {
        // Stripe events are not ordered: ask Stripe for the current state rather than applying a stale snapshot.
        val payment = paymentRepository.findByProviderPaymentIdWithLock(providerId)
            ?: throw WebhookProcessingException("Payment is not yet available for webhook")
        val current = paymentProvider.retrievePaymentIntent(providerId)
        val next = when (current.status) {
            "succeeded" -> PaymentStatus.SUCCEEDED
            "canceled" -> PaymentStatus.CANCELED
            "requires_action" -> PaymentStatus.REQUIRES_ACTION
            "requires_payment_method", "requires_confirmation", "processing", "requires_capture" -> PaymentStatus.PROCESSING
            else -> PaymentStatus.RECONCILIATION_REQUIRED
        }
        val wasSucceeded = payment.status == PaymentStatus.SUCCEEDED
        payment.status = next
        paymentRepository.save(payment)
        if (next == PaymentStatus.CANCELED) attemptGuard.releaseForCanceled(payment)
        if (!wasSucceeded && next == PaymentStatus.SUCCEEDED) {
            outboxService.publishEvent(payment.id, "Payment", "PaymentSucceeded",
                mapOf("paymentId" to payment.id, "orderId" to payment.orderId, "status" to next.name))
        }
    }

    private fun syncRefund(providerId: String) {
        val found = refundRepository.findByProviderRefundId(providerId)
            ?: throw WebhookProcessingException("Refund is not yet available for webhook")
        val refund = refundRepository.findByIdWithLock(found.id) ?: error("Refund disappeared")
        val current = paymentProvider.retrieveRefundStatus(providerId)
        refund.status = when (current) {
            "succeeded" -> RefundStatus.SUCCEEDED
            "failed" -> RefundStatus.FAILED
            "canceled" -> RefundStatus.CANCELED
            else -> RefundStatus.PENDING
        }
        refundRepository.save(refund)
    }
}
class WebhookSignatureException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class WebhookProcessingException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
