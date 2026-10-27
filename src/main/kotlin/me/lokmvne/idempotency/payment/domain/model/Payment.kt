package me.lokmvne.idempotency.payment.domain.model

import me.lokmvne.idempotency.payment.domain.enum.PaymentStatus
import java.time.Instant
import java.util.UUID

data class Payment(
    val id: UUID,
    val orderId: UUID,
    val userId: UUID,
    val amountCents: Long,
    val currency: String,
    val status: PaymentStatus,
    val provider: String,
    val providerPaymentId: String?,
    val providerIdempotencyKey: String?,
    val failureCode: String?,
    val failureMessage: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long
) {
    init {
        require(amountCents > 0) { "Amount must be positive" }
        require(currency.isNotBlank()) { "Currency cannot be blank" }
    }

    fun transitionTo(newStatus: PaymentStatus): Payment {
        require(isValidTransition(status, newStatus)) {
            "Invalid payment status transition from $status to $newStatus"
        }
        return copy(
            status = newStatus,
            updatedAt = Instant.now(),
            version = version + 1
        )
    }

    fun markAsProcessing(): Payment = transitionTo(PaymentStatus.PROCESSING)

    fun markAsRequiresAction(): Payment = transitionTo(PaymentStatus.REQUIRES_ACTION)

    fun markAsAuthorized(): Payment = transitionTo(PaymentStatus.AUTHORIZED)

    fun markAsSucceeded(): Payment = transitionTo(PaymentStatus.SUCCEEDED)

    fun markAsFailed(failureCode: String?, failureMessage: String?): Payment {
        require(status != PaymentStatus.SUCCEEDED) { "Payment already succeeded" }
        return copy(
            status = PaymentStatus.FAILED,
            failureCode = failureCode,
            failureMessage = failureMessage,
            updatedAt = Instant.now(),
            version = version + 1
        )
    }

    fun markAsCanceled(): Payment = transitionTo(PaymentStatus.CANCELED)

    fun markAsReconciliationRequired(): Payment = transitionTo(PaymentStatus.RECONCILIATION_REQUIRED)

    fun isTerminal(): Boolean {
        return status in listOf(
            PaymentStatus.SUCCEEDED,
            PaymentStatus.FAILED,
            PaymentStatus.CANCELED
        )
    }

    fun canBeRetried(): Boolean {
        return status in listOf(
            PaymentStatus.FAILED,
            PaymentStatus.RECONCILIATION_REQUIRED
        )
    }

    companion object {
        private val VALID_TRANSITIONS = mapOf(
            PaymentStatus.CREATED to setOf(
                PaymentStatus.PROCESSING,
                PaymentStatus.CANCELED
            ),
            PaymentStatus.PROCESSING to setOf(
                PaymentStatus.REQUIRES_ACTION,
                PaymentStatus.AUTHORIZED,
                PaymentStatus.SUCCEEDED,
                PaymentStatus.FAILED,
                PaymentStatus.RECONCILIATION_REQUIRED
            ),
            PaymentStatus.REQUIRES_ACTION to setOf(
                PaymentStatus.PROCESSING,
                PaymentStatus.SUCCEEDED,
                PaymentStatus.FAILED,
                PaymentStatus.CANCELED
            ),
            PaymentStatus.AUTHORIZED to setOf(
                PaymentStatus.SUCCEEDED,
                PaymentStatus.FAILED,
                PaymentStatus.CANCELED
            ),
            PaymentStatus.RECONCILIATION_REQUIRED to setOf(
                PaymentStatus.SUCCEEDED,
                PaymentStatus.FAILED,
                PaymentStatus.CANCELED
            )
        )

        private fun isValidTransition(from: PaymentStatus, to: PaymentStatus): Boolean {
            return from == to || VALID_TRANSITIONS[from]?.contains(to) == true
        }
    }
}
