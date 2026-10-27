package me.lokmvne.idempotency.refund.infrastructure.web.dto

import me.lokmvne.idempotency.refund.domain.enum.RefundStatus
import java.time.Instant
import java.util.UUID

data class RefundResponse(
    val id: UUID,
    val paymentId: UUID,
    val userId: UUID,
    val amountCents: Long,
    val currency: String,
    val status: RefundStatus,
    val providerRefundId: String?,
    val createdAt: Instant,
    val updatedAt: Instant
)
