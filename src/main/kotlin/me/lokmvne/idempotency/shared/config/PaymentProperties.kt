package me.lokmvne.idempotency.shared.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.ConstructorBinding
import java.time.Duration

@ConfigurationProperties(prefix = "app.payments")
data class PaymentProperties @ConstructorBinding constructor(
    val idempotency: IdempotencyProperties,
    val reconciliation: ReconciliationProperties,
    val provider: ProviderProperties,
    val jwt: JwtProperties,
    val outbox: OutboxProperties
) {
    data class IdempotencyProperties(
        val retention: Duration,
        val processingTimeout: Duration,
        val keyTtl: Duration
    )

    data class ReconciliationProperties(
        val enabled: Boolean,
        val batchSize: Int,
        val interval: Duration,
        val maxRetryAttempts: Int,
        val backoffBaseMs: Long
    )

    data class ProviderProperties(
        val mode: String,
        val apiKey: String,
        val webhookSecret: String,
        val timeoutSeconds: Int,
        val retryConfig: RetryConfig
    ) {
        data class RetryConfig(
            val maxRetries: Int,
            val initialBackoffMs: Long,
            val maxBackoffMs: Long
        )
    }

    data class JwtProperties(
        val secret: String,
        val expiration: Duration,
        val refreshExpiration: Duration
    )

    data class OutboxProperties(
        val enabled: Boolean,
        val batchSize: Int,
        val interval: Duration,
        val maxRetryAttempts: Int
    )
}
