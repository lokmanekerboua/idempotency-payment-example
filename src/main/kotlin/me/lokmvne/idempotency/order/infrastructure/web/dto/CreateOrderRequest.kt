package me.lokmvne.idempotency.order.infrastructure.web.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import java.util.UUID

data class CreateOrderRequest(
    val amountCents: Long,
    @field:NotBlank
    val currency: String
) {
    init {
        require(amountCents > 0) { "Amount must be positive" }
    }
}
