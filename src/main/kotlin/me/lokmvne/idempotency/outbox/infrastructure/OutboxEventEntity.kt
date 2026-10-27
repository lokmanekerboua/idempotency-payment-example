package me.lokmvne.idempotency.outbox.infrastructure

import jakarta.persistence.*
import me.lokmvne.idempotency.outbox.domain.OutboxStatus
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "outbox_events",
    indexes = [
        Index(name = "idx_outbox_status", columnList = "status"),
        Index(name = "idx_outbox_aggregate", columnList = "aggregate_id, aggregate_type"),
        Index(name = "idx_outbox_created_at", columnList = "created_at")
    ]
)
@EntityListeners(AuditingEntityListener::class)
data class OutboxEventEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "aggregate_id", nullable = false)
    val aggregateId: UUID,

    @Column(name = "aggregate_type", nullable = false, length = 50)
    val aggregateType: String,

    @Column(name = "event_type", nullable = false, length = 100)
    val eventType: String,

    @Column(columnDefinition = "TEXT", nullable = false)
    val payload: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: OutboxStatus = OutboxStatus.PENDING,

    @CreatedDate
    @Column(nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "processed_at")
    var processedAt: Instant? = null,

    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = 0
)
