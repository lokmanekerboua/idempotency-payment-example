package me.lokmvne.idempotency.payment.infrastructure.web.dto

import jakarta.validation.constraints.NotNull
import java.util.UUID

data class CreatePaymentRequest(
    @field:NotNull
    val orderId: UUID
)
