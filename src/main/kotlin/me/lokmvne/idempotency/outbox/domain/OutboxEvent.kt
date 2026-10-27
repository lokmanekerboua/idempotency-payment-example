package me.lokmvne.idempotency.outbox.domain

import java.time.Instant
import java.util.UUID

data class OutboxEvent(
    val id: UUID,
    val aggregateId: UUID,
    val aggregateType: String,
    val eventType: String,
    val payload: String,
    val status: OutboxStatus,
    val createdAt: Instant,
    val processedAt: Instant?,
    val attemptCount: Int
)

enum class OutboxStatus {
    PENDING,
    PROCESSED,
    FAILED
}
