package me.lokmvne.idempotency.payment.infrastructure.web.dto

import me.lokmvne.idempotency.payment.domain.enum.PaymentStatus
import java.time.Instant
import java.util.UUID

data class CreatePaymentResponse(
    val payment: PaymentResponse,
    val clientSecret: String?
) {
    data class PaymentResponse(
        val id: UUID,
        val orderId: UUID,
        val userId: UUID,
        val amountCents: Long,
        val currency: String,
        val status: PaymentStatus,
        val provider: String,
        val providerPaymentId: String?,
        val createdAt: Instant,
        val updatedAt: Instant
    )
}
