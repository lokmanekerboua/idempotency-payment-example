package me.lokmvne.idempotency.order.infrastructure.persistence

import jakarta.persistence.*
import me.lokmvne.idempotency.order.domain.enum.OrderStatus
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "orders",
    indexes = [
        Index(name = "idx_orders_user_id", columnList = "user_id"),
        Index(name = "idx_orders_status", columnList = "status"),
        Index(name = "idx_orders_created_at", columnList = "created_at")
    ]
)
@EntityListeners(AuditingEntityListener::class)
data class OrderEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    @Column(name = "order_reference", nullable = false, unique = true, length = 50)
    val orderReference: String,

    @Column(name = "amount_cents", nullable = false)
    val amountCents: Long,

    @Column(nullable = false, length = 3)
    val currency: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: OrderStatus = OrderStatus.CREATED,

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
}
