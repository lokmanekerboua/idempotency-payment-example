package me.lokmvne.idempotency.payment.domain.enum

enum class PaymentStatus {
    CREATED,
    PROCESSING,
    REQUIRES_ACTION,
    AUTHORIZED,
    SUCCEEDED,
    FAILED,
    CANCELED,
    RECONCILIATION_REQUIRED
}
