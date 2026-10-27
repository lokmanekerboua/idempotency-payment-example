package me.lokmvne.idempotency.idempotency.infrastructure.persistence

import jakarta.persistence.*
import me.lokmvne.idempotency.idempotency.domain.enum.OperationType
import me.lokmvne.idempotency.idempotency.domain.enum.ProcessingStatus
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "idempotency_records",
    indexes = [
        Index(name = "idx_idempotency_user_operation_key", columnList = "user_id, operation_type, idempotency_key"),
        Index(name = "idx_idempotency_status", columnList = "processing_status"),
        Index(name = "idx_idempotency_expires_at", columnList = "expires_at"),
        Index(name = "idx_idempotency_lease", columnList = "lease_owner, lease_expires_at")
    ],
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_idempotency_scope",
            columnNames = ["user_id", "operation_type", "idempotency_key"]
        )
    ]
)
@EntityListeners(AuditingEntityListener::class)
data class IdempotencyRecordEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val operationType: OperationType,

    @Column(name = "idempotency_key", nullable = false)
    val idempotencyKey: UUID,

    @Column(nullable = false, length = 64)
    val requestHash: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false, length = 20)
    var processingStatus: ProcessingStatus = ProcessingStatus.IN_PROGRESS,

    @Column(name = "response_http_status")
    var responseHttpStatus: Int? = null,

    @Column(columnDefinition = "TEXT")
    var responseBody: String? = null,

    @Column(name = "resource_id")
    var resourceId: UUID? = null,

    @Column(name = "lease_owner", length = 100)
    var leaseOwner: String? = null,

    @Column(name = "lease_expires_at")
    var leaseExpiresAt: Instant? = null,

    @CreatedDate
    @Column(nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @LastModifiedDate
    @Column(nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant
)
