package me.lokmvne.idempotency.webhook.infrastructure.persistence

import jakarta.persistence.*
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "webhook_events",
    indexes = [
        Index(name = "idx_webhook_provider_event_id", columnList = "provider_event_id"),
        Index(name = "idx_webhook_event_type", columnList = "event_type"),
        Index(name = "idx_webhook_status", columnList = "processing_status"),
        Index(name = "idx_webhook_received_at", columnList = "received_at")
    ],
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_provider_event_id",
            columnNames = ["provider", "provider_event_id"]
        )
    ]
)
@EntityListeners(AuditingEntityListener::class)
data class WebhookEventEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID = UUID.randomUUID(),

    @Column(nullable = false, length = 20)
    val provider: String,

    @Column(name = "provider_event_id", nullable = false, length = 255)
    val providerEventId: String,

    @Column(name = "event_type", nullable = false, length = 100)
    val eventType: String,

    @Column(name = "provider_object_id", length = 255)
    val providerObjectId: String? = null,

    @Column(columnDefinition = "TEXT", nullable = false)
    val payload: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false, length = 20)
    var processingStatus: WebhookProcessingStatus = WebhookProcessingStatus.PENDING,

    @Column(name = "received_at", nullable = false)
    val receivedAt: Instant = Instant.now(),

    @Column(name = "processed_at")
    var processedAt: Instant? = null,

    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = 0,

    @Column(name = "last_error", columnDefinition = "TEXT")
    var lastError: String? = null
)

enum class WebhookProcessingStatus {
    PENDING,
    PROCESSED,
    FAILED
}
