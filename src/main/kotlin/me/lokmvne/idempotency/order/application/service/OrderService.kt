package me.lokmvne.idempotency.order.application.service

import me.lokmvne.idempotency.order.domain.model.Order
import me.lokmvne.idempotency.order.infrastructure.persistence.OrderEntity
import me.lokmvne.idempotency.order.infrastructure.persistence.OrderRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.util.UUID

@Service
class OrderService(
    private val orderRepository: OrderRepository
) {
    data class CreateOrderCommand(
        val userId: UUID,
        val amountCents: Long,
        val currency: String
    )

    @Transactional
    fun createOrder(command: CreateOrderCommand): Order {
        val orderReference = generateOrderReference()

        val orderEntity = OrderEntity(
            userId = command.userId,
            orderReference = orderReference,
            amountCents = command.amountCents,
            currency = command.currency,
            status = me.lokmvne.idempotency.order.domain.enum.OrderStatus.CREATED
        )

        val savedOrder = orderRepository.save(orderEntity)
        return toDomain(savedOrder)
    }

    @Transactional(readOnly = true)
    fun getOrder(orderId: UUID): Order {
        val orderEntity = orderRepository.findById(orderId)
            .orElseThrow { me.lokmvne.idempotency.order.domain.exception.OrderNotFoundException(orderId) }
        return toDomain(orderEntity)
    }

    @Transactional(readOnly = true)
    fun getOrdersByUser(userId: UUID): List<Order> {
        return orderRepository.findByUserId(userId).map { toDomain(it) }
    }

    @Transactional
    fun markOrderAsPaid(orderId: UUID): Order {
        val orderEntity = orderRepository.findByIdWithLock(orderId)
            ?: throw me.lokmvne.idempotency.order.domain.exception.OrderNotFoundException(orderId)

        val order = toDomain(orderEntity)
        val updatedOrder = order.markAsPaid()

        orderEntity.status = updatedOrder.status
        orderEntity.version = updatedOrder.version

        val savedOrder = orderRepository.save(orderEntity)
        return toDomain(savedOrder)
    }

    private fun generateOrderReference(): String {
        return "ORD-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8).uppercase()}"
    }

    private fun toDomain(entity: OrderEntity): Order {
        return Order(
            id = entity.id,
            userId = entity.userId,
            orderReference = entity.orderReference,
            amountCents = entity.amountCents,
            currency = entity.currency,
            status = entity.status,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
            version = entity.version
        )
    }
}
