package me.lokmvne.idempotency.order.infrastructure.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import jakarta.validation.Valid
import me.lokmvne.idempotency.order.application.service.OrderService
import me.lokmvne.idempotency.order.domain.exception.OrderNotFoundException
import me.lokmvne.idempotency.order.domain.model.Order
import me.lokmvne.idempotency.order.infrastructure.web.dto.CreateOrderRequest
import me.lokmvne.idempotency.order.infrastructure.web.dto.OrderResponse
import me.lokmvne.idempotency.shared.exception.DomainException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/orders")
@SecurityRequirement(name = "bearerAuth")
class OrderController(
    private val orderService: OrderService
) {

    @PostMapping
    @Operation(summary = "Create an order", description = "Creates a new order for the authenticated user")
    fun createOrder(
        @Valid @RequestBody request: CreateOrderRequest,
        @AuthenticationPrincipal userDetails: UserDetails
    ): ResponseEntity<OrderResponse> {
        val userId = UUID.fromString(userDetails.username)

        val command = OrderService.CreateOrderCommand(
            userId = userId,
            amountCents = request.amountCents,
            currency = request.currency
        )

        val order = orderService.createOrder(command)

        val response = OrderResponse(
            id = order.id,
            userId = order.userId,
            orderReference = order.orderReference,
            amountCents = order.amountCents,
            currency = order.currency,
            status = order.status,
            createdAt = order.createdAt,
            updatedAt = order.updatedAt
        )

        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "Get order by ID", description = "Retrieves order details")
    fun getOrder(
        @PathVariable orderId: UUID,
        @AuthenticationPrincipal userDetails: UserDetails
    ): ResponseEntity<OrderResponse> {
        val userId = UUID.fromString(userDetails.username)
        val order = orderService.getOrder(orderId)

        if (order.userId != userId) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }

        val response = OrderResponse(
            id = order.id,
            userId = order.userId,
            orderReference = order.orderReference,
            amountCents = order.amountCents,
            currency = order.currency,
            status = order.status,
            createdAt = order.createdAt,
            updatedAt = order.updatedAt
        )

        return ResponseEntity.ok(response)
    }

    @GetMapping
    @Operation(summary = "Get user orders", description = "Retrieves all orders for the authenticated user")
    fun getUserOrders(
        @AuthenticationPrincipal userDetails: UserDetails
    ): ResponseEntity<List<OrderResponse>> {
        val userId = UUID.fromString(userDetails.username)
        val orders = orderService.getOrdersByUser(userId)

        val response = orders.map { order ->
            OrderResponse(
                id = order.id,
                userId = order.userId,
                orderReference = order.orderReference,
                amountCents = order.amountCents,
                currency = order.currency,
                status = order.status,
                createdAt = order.createdAt,
                updatedAt = order.updatedAt
            )
        }

        return ResponseEntity.ok(response)
    }

    @ExceptionHandler(OrderNotFoundException::class)
    fun handleOrderNotFound(ex: OrderNotFoundException): ResponseEntity<Map<String, String?>?> {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            mapOf(
                "type" to "order-not-found",
                "title" to "Order not found",
                "detail" to ex.message
            )
        )
    }

    @ExceptionHandler(DomainException::class)
    fun handleDomainException(ex: DomainException): ResponseEntity<Map<String, String?>?> {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
            mapOf(
                "type" to "domain-error",
                "title" to "Domain error",
                "detail" to ex.message
            )
        )
    }
}
