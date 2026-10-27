package me.lokmvne.idempotency.order.domain.exception

import me.lokmvne.idempotency.shared.exception.DomainException
import java.util.UUID

class OrderNotFoundException(orderId: UUID) : DomainException("Order not found: $orderId")
