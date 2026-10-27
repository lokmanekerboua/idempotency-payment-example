package me.lokmvne.idempotency.payment.application.service

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Fail closed if an earlier attempt may have reached the provider. */
@Service
class PaymentAttemptGuard(private val jdbc: JdbcTemplate) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun reserve(orderId: UUID, attemptId: UUID) {
        val inserted = jdbc.update(
            "INSERT INTO payment_attempt_guards(order_id, attempt_id) VALUES (?, ?) ON CONFLICT (order_id) DO NOTHING",
            orderId, attemptId
        )
        if (inserted == 0) throw PaymentAttemptAlreadyExistsException()
    }
    @Transactional(propagation = Propagation.MANDATORY)
    fun releaseForCanceled(payment: me.lokmvne.idempotency.payment.infrastructure.persistence.PaymentEntity) {
        if (payment.status != me.lokmvne.idempotency.payment.domain.enum.PaymentStatus.CANCELED) return
        val attemptId = runCatching { UUID.fromString(payment.providerIdempotencyKey?.substringBefore('_')) }.getOrNull() ?: return
        jdbc.update("DELETE FROM payment_attempt_guards WHERE order_id = ? AND attempt_id = ?", payment.orderId, attemptId)
    }
}
class PaymentAttemptAlreadyExistsException : RuntimeException("A payment attempt already exists for this order and must be reconciled before another can be started")
