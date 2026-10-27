package me.lokmvne.idempotency.refund.infrastructure.persistence

import me.lokmvne.idempotency.refund.domain.enum.RefundStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID
import jakarta.persistence.LockModeType

interface RefundRepository : JpaRepository<RefundEntity, UUID> {
    fun findByPaymentId(paymentId: UUID): List<RefundEntity>
    fun findByUserId(userId: UUID): List<RefundEntity>
    fun findByProviderRefundId(providerRefundId: String): RefundEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RefundEntity r WHERE r.id = :id")
    fun findByIdWithLock(@Param("id") id: UUID): RefundEntity?

    @Query("SELECT COALESCE(SUM(r.amountCents), 0) FROM RefundEntity r WHERE r.paymentId = :paymentId AND r.status IN :statuses")
    fun sumRefundedAmount(
        @Param("paymentId") paymentId: UUID,
        @Param("statuses") statuses: List<RefundStatus>
    ): Long
}
