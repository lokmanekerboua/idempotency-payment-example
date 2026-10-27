package me.lokmvne.idempotency.outbox.infrastructure

import me.lokmvne.idempotency.outbox.domain.OutboxStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID
import jakarta.persistence.LockModeType

interface OutboxEventRepository : JpaRepository<OutboxEventEntity, UUID> {
    @Query(value = 
        """
        SELECT * FROM outbox_events
        WHERE status = 'PENDING'
        ORDER BY created_at ASC
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true
    )
    fun findPendingEventsForProcessing(@Param("limit") limit: Int): List<OutboxEventEntity>

    @Modifying
    @Query(
        """
        UPDATE OutboxEventEntity o
        SET o.status = :status,
            o.processedAt = :processedAt,
            o.attemptCount = o.attemptCount + 1
        WHERE o.id = :id
        """
    )
    fun updateProcessingStatus(
        @Param("id") id: UUID,
        @Param("status") status: OutboxStatus,
        @Param("processedAt") processedAt: java.time.Instant?
    ): Int
}
