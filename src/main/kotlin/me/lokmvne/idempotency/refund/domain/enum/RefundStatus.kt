package me.lokmvne.idempotency.refund.domain.enum

enum class RefundStatus {
    PENDING,
    SUCCEEDED,
    FAILED,
    CANCELED
}
