package me.lokmvne.idempotency.order.domain.enum

enum class OrderStatus {
    CREATED,
    PAID,
    CANCELED,
    REFUNDED
}
