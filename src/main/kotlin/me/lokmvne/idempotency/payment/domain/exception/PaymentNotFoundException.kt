package me.lokmvne.idempotency.payment.domain.exception

import me.lokmvne.idempotency.shared.exception.DomainException
import java.util.UUID

class PaymentNotFoundException(paymentId: UUID) : DomainException("Payment not found: $paymentId")
