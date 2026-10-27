package me.lokmvne.idempotency.payment.infrastructure.persistence

import me.lokmvne.idempotency.payment.domain.enum.PaymentStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID
import jakarta.persistence.LockModeType

interface PaymentRepository : JpaRepository<PaymentEntity, UUID> {
    fun findByOrderId(orderId: UUID): List<PaymentEntity>
    fun findByUserId(userId: UUID): List<PaymentEntity>
    fun findByProviderPaymentId(providerPaymentId: String): PaymentEntity?
    fun findByOrderIdAndStatus(orderId: UUID, status: PaymentStatus): List<PaymentEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PaymentEntity p WHERE p.id = :id")
    fun findByIdWithLock(@Param("id") id: UUID): PaymentEntity?

    @Query("SELECT p FROM PaymentEntity p WHERE p.orderId = :orderId AND p.status IN :statuses")
    fun findByOrderIdAndStatusIn(
        @Param("orderId") orderId: UUID,
        @Param("statuses") statuses: List<PaymentStatus>
    ): List<PaymentEntity>

    @Query("SELECT p FROM PaymentEntity p WHERE p.orderId = :orderId AND p.status = 'SUCCEEDED'")
    fun findSuccessfulPaymentByOrderId(@Param("orderId") orderId: UUID): PaymentEntity?
}
