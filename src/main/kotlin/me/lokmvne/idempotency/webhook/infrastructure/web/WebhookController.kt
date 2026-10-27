package me.lokmvne.idempotency.webhook.infrastructure.web

import io.swagger.v3.oas.annotations.Operation
import me.lokmvne.idempotency.webhook.application.service.WebhookService
import me.lokmvne.idempotency.webhook.application.service.WebhookSignatureException
import me.lokmvne.idempotency.webhook.application.service.WebhookProcessingException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/webhooks")
class WebhookController(
    private val webhookService: WebhookService
) {
    private val logger = LoggerFactory.getLogger(WebhookController::class.java)

    @PostMapping("/stripe")
    @Operation(
        summary = "Stripe webhook endpoint",
        description = "Receives and processes Stripe webhook events. Signature verification is performed."
    )
    fun handleStripeWebhook(
        @RequestHeader("Stripe-Signature") signature: String,
        @RequestBody payload: String
    ): ResponseEntity<out Map<String, Any?>?> {
        logger.info("Received Stripe webhook")

        try {
            val result = webhookService.handleStripeWebhook(payload, signature)

            return ResponseEntity.ok(
                mapOf(
                    "success" to result.success,
                    "message" to result.message,
                    "eventId" to result.eventId
                )
            )
        } catch (e: WebhookSignatureException) {
            logger.error("Invalid webhook signature", e)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                mapOf(
                    "error" to "Invalid signature",
                    "message" to e.message
                )
            )
        } catch (e: WebhookProcessingException) {
            logger.error("Webhook processing failed", e)
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                mapOf(
                    "error" to "Processing failed",
                    "message" to e.message
                )
            )
        } catch (e: Exception) {
            logger.error("Unexpected error processing webhook", e)
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                mapOf(
                    "error" to "Unexpected error",
                    "message" to e.message
                )
            )
        }
    }
}
