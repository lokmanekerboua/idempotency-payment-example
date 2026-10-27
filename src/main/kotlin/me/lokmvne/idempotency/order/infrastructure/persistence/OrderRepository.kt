package me.lokmvne.idempotency.order.infrastructure.persistence

import me.lokmvne.idempotency.order.domain.enum.OrderStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID
import jakarta.persistence.LockModeType

interface OrderRepository : JpaRepository<OrderEntity, UUID> {
    fun findByUserId(userId: UUID): List<OrderEntity>
    fun findByOrderReference(orderReference: String): OrderEntity?
    fun findByUserIdAndStatus(userId: UUID, status: OrderStatus): List<OrderEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM OrderEntity o WHERE o.id = :id")
    fun findByIdWithLock(@Param("id") id: UUID): OrderEntity?
}
