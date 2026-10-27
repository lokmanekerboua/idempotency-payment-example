package me.lokmvne.idempotency.webhook.application.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.stripe.Stripe
import com.stripe.exception.SignatureVerificationException
import com.stripe.model.Event
import com.stripe.net.Webhook
import me.lokmvne.idempotency.payment.domain.enum.PaymentStatus
import me.lokmvne.idempotency.payment.infrastructure.persistence.PaymentEntity
import me.lokmvne.idempotency.payment.infrastructure.persistence.PaymentRepository
import me.lokmvne.idempotency.shared.config.PaymentProperties
import me.lokmvne.idempotency.webhook.infrastructure.persistence.WebhookEventEntity
import me.lokmvne.idempotency.webhook.infrastructure.persistence.WebhookEventRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class WebhookService(
    private val webhookEventRepository: WebhookEventRepository,
    private val paymentRepository: PaymentRepository,
    private val paymentProperties: PaymentProperties,
    private val objectMapper: ObjectMapper,
    private val outboxService: me.lokmvne.idempotency.outbox.application.service.OutboxService
) {
    private val logger = LoggerFactory.getLogger(WebhookService::class.java)

    @Transactional
    fun handleStripeWebhook(
        payload: String,
        signature: String
    ): WebhookProcessingResult {
        try {
            val event = verifyStripeSignature(payload, signature)

            val existingEvent = webhookEventRepository.findByProviderAndProviderEventId(
                provider = "stripe",
                providerEventId = event.id
            )

            if (existingEvent != null) {
                logger.info("Duplicate webhook event received: ${event.id}")
                // metricsService.incrementWebhookDuplicates() - would need to inject
                return WebhookProcessingResult(
                    success = true,
                    message = "Duplicate event - already processed",
                    eventId = existingEvent.id
                )
            }

            val webhookEvent = WebhookEventEntity(
                provider = "stripe",
                providerEventId = event.id,
                eventType = event.type,
                providerObjectId = event.data?.`object`?.id,
                payload = payload,
                processingStatus = WebhookEventEntity.WebhookProcessingStatus.PENDING,
                receivedAt = Instant.now()
            )

            val savedEvent = webhookEventRepository.save(webhookEvent)

            processStripeEvent(event, savedEvent)

            return WebhookProcessingResult(
                success = true,
                message = "Webhook processed successfully",
                eventId = savedEvent.id
            )

        } catch (e: SignatureVerificationException) {
            logger.error("Invalid Stripe webhook signature", e)
            throw WebhookSignatureException("Invalid webhook signature", e)
        } catch (e: Exception) {
            logger.error("Error processing webhook", e)
            throw WebhookProcessingException("Failed to process webhook: ${e.message}", e)
        }
    }

    private fun verifyStripeSignature(payload: String, signature: String): Event {
        val webhookSecret = paymentProperties.provider.webhookSecret
        if (webhookSecret.isBlank()) {
            throw IllegalStateException("Stripe webhook secret not configured")
        }

        return Webhook.constructEvent(
            payload,
            signature,
            webhookSecret
        )
    }

    @Transactional
    fun processStripeEvent(event: Event, webhookEvent: WebhookEventEntity) {
        try {
            when (event.type) {
                "payment_intent.succeeded" -> handlePaymentIntentSucceeded(event, webhookEvent)
                "payment_intent.payment_failed" -> handlePaymentIntentFailed(event, webhookEvent)
                "payment_intent.canceled" -> handlePaymentIntentCanceled(event, webhookEvent)
                "payment_intent.requires_action" -> handlePaymentIntentRequiresAction(event, webhookEvent)
                "charge.refunded" -> handleChargeRefunded(event, webhookEvent)
                else -> {
                    logger.info("Unhandled Stripe event type: ${event.type}")
                    markEventAsProcessed(webhookEvent, null)
                }
            }
        } catch (e: Exception) {
            logger.error("Error processing Stripe event ${event.id}", e)
            markEventAsFailed(webhookEvent, e.message)
            throw e
        }
    }

    private fun handlePaymentIntentSucceeded(event: Event, webhookEvent: WebhookEventEntity) {
        val paymentIntent = event.data?.`object` ?: throw IllegalStateException("PaymentIntent object missing")
        val providerPaymentId = paymentIntent.get("id").asText()

        val payment = paymentRepository.findByProviderPaymentId(providerPaymentId)
        if (payment == null) {
            logger.warn("Payment not found for provider payment ID: $providerPaymentId")
            markEventAsProcessed(webhookEvent, null)
            return
        }

        if (payment.status == PaymentStatus.SUCCEEDED) {
            logger.info("Payment already succeeded: ${payment.id}")
            markEventAsProcessed(webhookEvent, null)
            return
        }

        payment.status = PaymentStatus.SUCCEEDED
        paymentRepository.save(payment)

        // Publish payment succeeded event
        outboxService.publishEvent(
            aggregateId = payment.id,
            aggregateType = "Payment",
            eventType = "PaymentSucceeded",
            payload = mapOf(
                "paymentId" to payment.id,
                "orderId" to payment.orderId,
                "status" to PaymentStatus.SUCCEEDED.name
            )
        )

        markEventAsProcessed(webhookEvent, null)
        logger.info("Payment marked as succeeded: ${payment.id}")
    }

    private fun handlePaymentIntentFailed(event: Event, webhookEvent: WebhookEventEntity) {
        val paymentIntent = event.data?.`object` ?: throw IllegalStateException("PaymentIntent object missing")
        val providerPaymentId = paymentIntent.get("id").asText()
        val lastPaymentError = paymentIntent.get("last_payment_error")

        val payment = paymentRepository.findByProviderPaymentId(providerPaymentId)
        if (payment == null) {
            logger.warn("Payment not found for provider payment ID: $providerPaymentId")
            markEventAsProcessed(webhookEvent, null)
            return
        }

        payment.status = PaymentStatus.FAILED
        payment.failureCode = lastPaymentError?.get("code")?.asText()
        payment.failureMessage = lastPaymentError?.get("message")?.asText()
        paymentRepository.save(payment)

        markEventAsProcessed(webhookEvent, null)
        logger.info("Payment marked as failed: ${payment.id}")
    }

    private fun handlePaymentIntentCanceled(event: Event, webhookEvent: WebhookEventEntity) {
        val paymentIntent = event.data?.`object` ?: throw IllegalStateException("PaymentIntent object missing")
        val providerPaymentId = paymentIntent.get("id").asText()

        val payment = paymentRepository.findByProviderPaymentId(providerPaymentId)
        if (payment == null) {
            logger.warn("Payment not found for provider payment ID: $providerPaymentId")
            markEventAsProcessed(webhookEvent, null)
            return
        }

        payment.status = PaymentStatus.CANCELED
        paymentRepository.save(payment)

        markEventAsProcessed(webhookEvent, null)
        logger.info("Payment marked as canceled: ${payment.id}")
    }

    private fun handlePaymentIntentRequiresAction(event: Event, webhookEvent: WebhookEventEntity) {
        val paymentIntent = event.data?.`object` ?: throw IllegalStateException("PaymentIntent object missing")
        val providerPaymentId = paymentIntent.get("id").asText()

        val payment = paymentRepository.findByProviderPaymentId(providerPaymentId)
        if (payment == null) {
            logger.warn("Payment not found for provider payment ID: $providerPaymentId")
            markEventAsProcessed(webhookEvent, null)
            return
        }

        payment.status = PaymentStatus.REQUIRES_ACTION
        paymentRepository.save(payment)

        markEventAsProcessed(webhookEvent, null)
        logger.info("Payment marked as requires action: ${payment.id}")
    }

    private fun handleChargeRefunded(event: Event, webhookEvent: WebhookEventEntity) {
        val charge = event.data?.`object` ?: throw IllegalStateException("Charge object missing")
        val paymentIntentId = charge.get("payment_intent").asText()

        val payment = paymentRepository.findByProviderPaymentId(paymentIntentId)
        if (payment == null) {
            logger.warn("Payment not found for payment intent ID: $paymentIntentId")
            markEventAsProcessed(webhookEvent, null)
            return
        }

        markEventAsProcessed(webhookEvent, null)
        logger.info("Charge refunded for payment: ${payment.id}")
    }

    private fun markEventAsProcessed(event: WebhookEventEntity, error: String?) {
        event.processingStatus = WebhookEventEntity.WebhookProcessingStatus.PROCESSED
        event.processedAt = Instant.now()
        event.lastError = error
        webhookEventRepository.save(event)
    }

    private fun markEventAsFailed(event: WebhookEventEntity, error: String?) {
        event.processingStatus = WebhookEventEntity.WebhookProcessingStatus.FAILED
        event.processedAt = Instant.now()
        event.lastError = error
        webhookEventRepository.save(event)
    }

    data class WebhookProcessingResult(
        val success: Boolean,
        val message: String,
        val eventId: java.util.UUID
    )
}

class WebhookSignatureException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class WebhookProcessingException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
