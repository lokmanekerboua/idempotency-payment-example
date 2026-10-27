package me.lokmvne.idempotency.payment.infrastructure.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import me.lokmvne.idempotency.idempotency.domain.exception.IdempotencyKeyConflictException
import me.lokmvne.idempotency.payment.application.service.PaymentService
import me.lokmvne.idempotency.payment.domain.exception.PaymentNotFoundException
import me.lokmvne.idempotency.payment.domain.model.Payment
import me.lokmvne.idempotency.payment.infrastructure.web.dto.CreatePaymentRequest
import me.lokmvne.idempotency.payment.infrastructure.web.dto.CreatePaymentResponse
import me.lokmvne.idempotency.payment.infrastructure.web.dto.PaymentResponse
import me.lokmvne.idempotency.shared.exception.DomainException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/payments")
@SecurityRequirement(name = "bearerAuth")
class PaymentController(
    private val paymentService: PaymentService
) {

    @PostMapping
    @Operation(
        summary = "Create a payment",
        description = "Initiates a payment for an order using Stripe. Requires Idempotency-Key header to prevent duplicate charges."
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "201",
            description = "Payment created successfully",
            headers = [Header(name = "Idempotency-Key", description = "The idempotency key used for this request")]
        ),
        ApiResponse(
            responseCode = "200",
            description = "Returning cached result from previous request with same idempotency key"
        ),
        ApiResponse(
            responseCode = "409",
            description = "Idempotency key conflict - same key used with different parameters"
        ),
        ApiResponse(
            responseCode = "400",
            description = "Invalid request"
        ),
        ApiResponse(
            responseCode = "401",
            description = "Unauthorized"
        ),
        ApiResponse(
            responseCode = "404",
            description = "Order not found"
        )
    )
    fun createPayment(
        @Valid @RequestBody request: CreatePaymentRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @AuthenticationPrincipal userDetails: UserDetails,
        httpRequest: HttpServletRequest
    ): ResponseEntity<CreatePaymentResponse> {
        val userId = UUID.fromString(userDetails.username)
        val idempotencyKeyUuid = UUID.fromString(idempotencyKey)

        val command = me.lokmvne.idempotency.payment.application.port.input.CreatePaymentCommand(
            orderId = request.orderId,
            userId = userId
        )

        val result = paymentService.createPayment(command, idempotencyKeyUuid)

        val status = if (result.isCached) HttpStatus.OK else HttpStatus.CREATED

        val response = CreatePaymentResponse(
            payment = CreatePaymentResponse.PaymentResponse(
                id = result.payment.id,
                orderId = result.payment.orderId,
                userId = result.payment.userId,
                amountCents = result.payment.amountCents,
                currency = result.payment.currency,
                status = result.payment.status,
                provider = result.payment.provider,
                providerPaymentId = result.payment.providerPaymentId,
                createdAt = result.payment.createdAt,
                updatedAt = result.payment.updatedAt
            ),
            clientSecret = result.clientSecret
        )

        return ResponseEntity.status(status).body(response)
    }

    @GetMapping("/{paymentId}")
    @Operation(summary = "Get payment by ID", description = "Retrieves payment details")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Payment found"),
        ApiResponse(responseCode = "404", description = "Payment not found"),
        ApiResponse(responseCode = "401", description = "Unauthorized")
    )
    fun getPayment(
        @PathVariable paymentId: UUID,
        @AuthenticationPrincipal userDetails: UserDetails
    ): ResponseEntity<PaymentResponse> {
        val userId = UUID.fromString(userDetails.username)
        val payment = paymentService.getPayment(paymentId)

        if (payment.userId != userId) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }

        val response = PaymentResponse(
            id = payment.id,
            orderId = payment.orderId,
            userId = payment.userId,
            amountCents = payment.amountCents,
            currency = payment.currency,
            status = payment.status,
            provider = payment.provider,
            providerPaymentId = payment.providerPaymentId,
            failureCode = payment.failureCode,
            failureMessage = payment.failureMessage,
            createdAt = payment.createdAt,
            updatedAt = payment.updatedAt
        )

        return ResponseEntity.ok(response)
    }

    @GetMapping("/order/{orderId}")
    @Operation(summary = "Get payments for an order", description = "Retrieves all payments for a specific order")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Payments retrieved"),
        ApiResponse(responseCode = "401", description = "Unauthorized")
    )
    fun getPaymentsByOrder(
        @PathVariable orderId: UUID,
        @AuthenticationPrincipal userDetails: UserDetails
    ): ResponseEntity<List<PaymentResponse>> {
        val userId = UUID.fromString(userDetails.username)
        val payments = paymentService.getPaymentsByOrder(orderId)

        val filteredPayments = payments.filter { it.userId == userId }

        val response = filteredPayments.map { payment ->
            PaymentResponse(
                id = payment.id,
                orderId = payment.orderId,
                userId = payment.userId,
                amountCents = payment.amountCents,
                currency = payment.currency,
                status = payment.status,
                provider = payment.provider,
                providerPaymentId = payment.providerPaymentId,
                failureCode = payment.failureCode,
                failureMessage = payment.failureMessage,
                createdAt = payment.createdAt,
                updatedAt = payment.updatedAt
            )
        }

        return ResponseEntity.ok(response)
    }

    @GetMapping
    @Operation(summary = "Get user payments", description = "Retrieves all payments for the authenticated user")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Payments retrieved"),
        ApiResponse(responseCode = "401", description = "Unauthorized")
    )
    fun getUserPayments(
        @AuthenticationPrincipal userDetails: UserDetails
    ): ResponseEntity<List<PaymentResponse>> {
        val userId = UUID.fromString(userDetails.username)
        val payments = paymentService.getPaymentsByUser(userId)

        val response = payments.map { payment ->
            PaymentResponse(
                id = payment.id,
                orderId = payment.orderId,
                userId = payment.userId,
                amountCents = payment.amountCents,
                currency = payment.currency,
                status = payment.status,
                provider = payment.provider,
                providerPaymentId = payment.providerPaymentId,
                failureCode = payment.failureCode,
                failureMessage = payment.failureMessage,
                createdAt = payment.createdAt,
                updatedAt = payment.updatedAt
            )
        }

        return ResponseEntity.ok(response)
    }

    @ExceptionHandler(me.lokmvne.idempotency.payment.application.service.PaymentAttemptAlreadyExistsException::class)
    fun handleExistingAttempt(ex: RuntimeException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("type" to "payment-attempt-exists", "detail" to ex.message))

    @ExceptionHandler(IdempotencyKeyConflictException::class)
    fun handleIdempotencyConflict(ex: IdempotencyKeyConflictException): ResponseEntity<out Map<String, Any?>?> {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            mapOf(
                "type" to "idempotency-conflict",
                "title" to "Idempotency key conflict",
                "detail" to ex.message,
                "userId" to ex.userId,
                "operationType" to ex.operationType,
                "idempotencyKey" to ex.idempotencyKey
            )
        )
    }

    @ExceptionHandler(PaymentNotFoundException::class)
    fun handlePaymentNotFound(ex: PaymentNotFoundException): ResponseEntity<Map<String, String?>?> {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            mapOf(
                "type" to "payment-not-found",
                "title" to "Payment not found",
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
