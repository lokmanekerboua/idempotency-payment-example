package me.lokmvne.idempotency.payment.domain.exception

import me.lokmvne.idempotency.payment.domain.enum.PaymentStatus
import me.lokmvne.idempotency.shared.exception.DomainException

class InvalidPaymentStateException(
    currentStatus: PaymentStatus,
    targetStatus: PaymentStatus
) : DomainException("Invalid payment state transition from $currentStatus to $targetStatus")
