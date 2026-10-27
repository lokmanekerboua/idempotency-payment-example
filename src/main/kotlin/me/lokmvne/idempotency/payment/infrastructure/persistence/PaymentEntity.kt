package me.lokmvne.idempotency.payment.infrastructure.persistence

import jakarta.persistence.*
import me.lokmvne.idempotency.payment.domain.enum.PaymentStatus
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "payments",
    indexes = [
        Index(name = "idx_payments_order_id", columnList = "order_id"),
        Index(name = "idx_payments_user_id", columnList = "user_id"),
        Index(name = "idx_payments_status", columnList = "payment_status"),
        Index(name = "idx_payments_provider_payment_id", columnList = "provider_payment_id"),
        Index(name = "idx_payments_created_at", columnList = "created_at"),
        Index(
            name = "idx_payments_order_status",
            columnList = "order_id, payment_status"
        )
    ],
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_provider_payment_id",
            columnNames = ["provider_payment_id"]
        )
    ]
)
@EntityListeners(AuditingEntityListener::class)
data class PaymentEntity(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "order_id", nullable = false)
    val orderId: UUID,

    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    @Column(name = "amount_cents", nullable = false)
    val amountCents: Long,

    @Column(nullable = false, length = 3)
    val currency: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 30)
    var status: PaymentStatus = PaymentStatus.CREATED,

    @Column(nullable = false, length = 20)
    val provider: String = "stripe",

    @Column(name = "provider_payment_id", length = 255)
    var providerPaymentId: String? = null,

    @Column(name = "provider_idempotency_key", length = 255)
    var providerIdempotencyKey: String? = null,

    @Column(name = "failure_code", length = 100)
    var failureCode: String? = null,

    @Column(name = "failure_message", length = 500)
    var failureMessage: String? = null,

    @CreatedDate
    @Column(nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @LastModifiedDate
    @Column(nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Version
    var version: Long = 0
) {
    fun isTerminal(): Boolean = status in setOf(PaymentStatus.SUCCEEDED, PaymentStatus.FAILED, PaymentStatus.CANCELED)

    @PrePersist
    @PreUpdate
    fun validateAmount() {
        require(amountCents > 0) { "Amount must be positive" }
    }
}
