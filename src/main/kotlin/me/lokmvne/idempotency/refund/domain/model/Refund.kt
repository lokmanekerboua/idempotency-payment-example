package me.lokmvne.idempotency.refund.domain.model

import me.lokmvne.idempotency.refund.domain.enum.RefundStatus
import java.time.Instant
import java.util.UUID

data class Refund(
    val id: UUID,
    val paymentId: UUID,
    val userId: UUID,
    val amountCents: Long,
    val currency: String,
    val status: RefundStatus,
    val providerRefundId: String?,
    val providerIdempotencyKey: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long
) {
    init {
        require(amountCents > 0) { "Amount must be positive" }
        require(currency.isNotBlank()) { "Currency cannot be blank" }
    }

    fun transitionTo(newStatus: RefundStatus): Refund {
        require(isValidTransition(status, newStatus)) {
            "Invalid refund status transition from $status to $newStatus"
        }
        return copy(
            status = newStatus,
            updatedAt = Instant.now(),
            version = version + 1
        )
    }

    fun markAsSucceeded(providerRefundId: String): Refund {
        return copy(
            status = RefundStatus.SUCCEEDED,
            providerRefundId = providerRefundId,
            updatedAt = Instant.now(),
            version = version + 1
        )
    }

    fun markAsFailed(): Refund {
        return copy(
            status = RefundStatus.FAILED,
            updatedAt = Instant.now(),
            version = version + 1
        )
    }

    fun markAsCanceled(): Refund {
        return copy(
            status = RefundStatus.CANCELED,
            updatedAt = Instant.now(),
            version = version + 1
        )
    }

    fun isTerminal(): Boolean {
        return status in listOf(
            RefundStatus.SUCCEEDED,
            RefundStatus.FAILED,
            RefundStatus.CANCELED
        )
    }

    companion object {
        private val VALID_TRANSITIONS = mapOf(
            RefundStatus.PENDING to setOf(
                RefundStatus.SUCCEEDED,
                RefundStatus.FAILED,
                RefundStatus.CANCELED
            )
        )

        private fun isValidTransition(from: RefundStatus, to: RefundStatus): Boolean {
            return from == to || VALID_TRANSITIONS[from]?.contains(to) == true
        }
    }
}
