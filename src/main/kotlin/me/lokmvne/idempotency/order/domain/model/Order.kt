package me.lokmvne.idempotency.order.domain.model

import me.lokmvne.idempotency.order.domain.enum.OrderStatus
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class Order(
    val id: UUID,
    val userId: UUID,
    val orderReference: String,
    val amountCents: Long,
    val currency: String,
    val status: OrderStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long
) {
    init {
        require(amountCents > 0) { "Amount must be positive" }
        require(currency.isNotBlank()) { "Currency cannot be blank" }
    }

    fun canInitiatePayment(): Boolean {
        return status == OrderStatus.CREATED
    }

    fun markAsPaid(): Order {
        require(canInitiatePayment()) { "Order cannot be marked as paid in current status: $status" }
        return copy(
            status = OrderStatus.PAID,
            updatedAt = Instant.now(),
            version = version + 1
        )
    }

    fun markAsCanceled(): Order {
        require(status == OrderStatus.CREATED) { "Order cannot be canceled in current status: $status" }
        return copy(
            status = OrderStatus.CANCELED,
            updatedAt = Instant.now(),
            version = version + 1
        )
    }

    fun markAsRefunded(): Order {
        require(status == OrderStatus.PAID) { "Order cannot be marked as refunded in current status: $status" }
        return copy(
            status = OrderStatus.REFUNDED,
            updatedAt = Instant.now(),
            version = version + 1
        )
    }
}
