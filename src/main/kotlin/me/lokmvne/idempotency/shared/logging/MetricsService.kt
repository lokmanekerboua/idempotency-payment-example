package me.lokmvne.idempotency.shared.logging

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

@Component
class MetricsService(private val meterRegistry: MeterRegistry) {

    fun incrementPaymentCreated() {
        Counter.builder("payments.created")
            .description("Number of payments created")
            .register(meterRegistry)
            .increment()
    }

    fun incrementPaymentSucceeded() {
        Counter.builder("payments.succeeded")
            .description("Number of payments succeeded")
            .register(meterRegistry)
            .increment()
    }

    fun incrementPaymentFailed() {
        Counter.builder("payments.failed")
            .description("Number of payments failed")
            .register(meterRegistry)
            .increment()
    }

    fun incrementPaymentDuplicateRequests() {
        Counter.builder("payments.duplicate_requests")
            .description("Number of duplicate payment requests")
            .register(meterRegistry)
            .increment()
    }

    fun incrementIdempotencyConflicts() {
        Counter.builder("payments.idempotency_conflicts")
            .description("Number of idempotency key conflicts")
            .register(meterRegistry)
            .increment()
    }

    fun recordPaymentProcessingDuration(durationMs: Long) {
        Timer.builder("payments.processing_duration")
            .description("Payment processing duration")
            .register(meterRegistry)
            .record(durationMs, TimeUnit.MILLISECONDS)
    }

    fun incrementProviderTimeouts() {
        Counter.builder("payments.provider_timeouts")
            .description("Number of provider timeouts")
            .register(meterRegistry)
            .increment()
    }

    fun incrementReconciliationAttempts() {
        Counter.builder("payments.reconciliation_attempts")
            .description("Number of reconciliation attempts")
            .register(meterRegistry)
            .increment()
    }

    fun incrementReconciliationFailures() {
        Counter.builder("payments.reconciliation_failures")
            .description("Number of reconciliation failures")
            .register(meterRegistry)
            .increment()
    }

    fun incrementWebhookDuplicates() {
        Counter.builder("payments.webhook_duplicates")
            .description("Number of duplicate webhooks")
            .register(meterRegistry)
            .increment()
    }

    fun incrementRefunds() {
        Counter.builder("payments.refunds")
            .description("Number of refunds created")
            .register(meterRegistry)
            .increment()
    }
}
