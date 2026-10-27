package me.lokmvne.idempotency.refund.infrastructure.persistence

import jakarta.persistence.*
import me.lokmvne.idempotency.refund.domain.enum.RefundStatus
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "refunds",
    indexes = [
        Index(name = "idx_refunds_payment_id", columnList = "payment_id"),
        Index(name = "idx_refunds_user_id", columnList = "user_id"),
        Index(name = "idx_refunds_status", columnList = "refund_status"),
        Index(name = "idx_refunds_created_at", columnList = "created_at")
    ],
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_provider_refund_id",
            columnNames = ["provider_refund_id"]
        )
    ]
)
@EntityListeners(AuditingEntityListener::class)
data class RefundEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "payment_id", nullable = false)
    val paymentId: UUID,

    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    @Column(name = "amount_cents", nullable = false)
    val amountCents: Long,

    @Column(nullable = false, length = 3)
    val currency: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "refund_status", nullable = false, length = 20)
    var status: RefundStatus = RefundStatus.PENDING,

    @Column(name = "provider_refund_id", length = 255)
    var providerRefundId: String? = null,

    @Column(name = "provider_idempotency_key", length = 255)
    var providerIdempotencyKey: String? = null,

    @CreatedDate
    @Column(nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @LastModifiedDate
    @Column(nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Version
    var version: Long = 0
) {
    @PrePersist
    @PreUpdate
    fun validateAmount() {
        require(amountCents > 0) { "Amount must be positive" }
    }

    fun isTerminal(): Boolean {
        return status in listOf(
            RefundStatus.SUCCEEDED,
            RefundStatus.FAILED,
            RefundStatus.CANCELED
        )
    }
}
