package me.lokmvne.idempotency.payment.application.port.input

import java.util.UUID

data class CreatePaymentCommand(
    val orderId: UUID,
    val userId: UUID
)
