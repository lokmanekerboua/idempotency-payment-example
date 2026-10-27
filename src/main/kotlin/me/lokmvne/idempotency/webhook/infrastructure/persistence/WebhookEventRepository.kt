package me.lokmvne.idempotency.webhook.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID
import jakarta.persistence.LockModeType

interface WebhookEventRepository : JpaRepository<WebhookEventEntity, UUID> {
    fun findByProviderAndProviderEventId(
        provider: String,
        providerEventId: String
    ): WebhookEventEntity?

    fun findByProcessingStatus(status: WebhookProcessingStatus): List<WebhookEventEntity>

    @Modifying
    @Query(value = """
        INSERT INTO webhook_events (id, provider, provider_event_id, event_type, provider_object_id, payload, processing_status, received_at, attempt_count)
        VALUES (:id, 'stripe', :eventId, :eventType, :objectId, :payload, 'PENDING', now(), 0)
        ON CONFLICT (provider, provider_event_id) DO NOTHING
    """, nativeQuery = true)
    fun insertIfAbsent(@Param("id") id: UUID, @Param("eventId") eventId: String,
                       @Param("eventType") eventType: String, @Param("objectId") objectId: String?,
                       @Param("payload") payload: String): Int

    @Modifying
    @Query(
        """
        UPDATE WebhookEventEntity w
        SET w.processingStatus = :status,
            w.processedAt = :processedAt,
            w.attemptCount = w.attemptCount + 1,
            w.lastError = :lastError
        WHERE w.id = :id
        """
    )
    fun updateProcessingStatus(
        @Param("id") id: UUID,
        @Param("status") status: WebhookProcessingStatus,
        @Param("processedAt") processedAt: java.time.Instant?,
        @Param("lastError") lastError: String?
    ): Int
}
