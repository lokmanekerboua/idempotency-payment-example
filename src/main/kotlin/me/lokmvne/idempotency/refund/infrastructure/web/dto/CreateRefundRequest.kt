package me.lokmvne.idempotency.refund.infrastructure.web.dto

import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import java.util.UUID

data class CreateRefundRequest(
    @field:NotNull
    val amountCents: Long
) {
    init {
        require(amountCents > 0) { "Amount must be positive" }
    }
}
