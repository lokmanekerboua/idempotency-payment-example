package me.lokmvne.idempotency.order.infrastructure.web.dto

import me.lokmvne.idempotency.order.domain.enum.OrderStatus
import java.time.Instant
import java.util.UUID

data class OrderResponse(
    val id: UUID,
    val userId: UUID,
    val orderReference: String,
    val amountCents: Long,
    val currency: String,
    val status: OrderStatus,
    val createdAt: Instant,
    val updatedAt: Instant
)
