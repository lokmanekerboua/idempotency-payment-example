package me.lokmvne.idempotency.webhook.infrastructure.persistence

import me.lokmvne.idempotency.webhook.infrastructure.persistence.WebhookEventEntity.WebhookProcessingStatus
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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        SELECT w FROM WebhookEventEntity w
        WHERE w.processingStatus = 'PENDING'
        ORDER BY w.receivedAt ASC
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """
    )
    fun findPendingEventsForProcessing(@Param("limit") limit: Int): List<WebhookEventEntity>

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
