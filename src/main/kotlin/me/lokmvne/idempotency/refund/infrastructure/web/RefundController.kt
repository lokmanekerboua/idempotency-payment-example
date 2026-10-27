package me.lokmvne.idempotency.refund.infrastructure.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import jakarta.validation.Valid
import me.lokmvne.idempotency.idempotency.domain.exception.IdempotencyKeyConflictException
import me.lokmvne.idempotency.refund.application.service.RefundService
import me.lokmvne.idempotency.refund.application.service.RefundAmountExceededException
import me.lokmvne.idempotency.refund.domain.model.Refund
import me.lokmvne.idempotency.refund.infrastructure.web.dto.CreateRefundRequest
import me.lokmvne.idempotency.refund.infrastructure.web.dto.RefundResponse
import me.lokmvne.idempotency.shared.exception.DomainException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/payments/{paymentId}/refunds")
@SecurityRequirement(name = "bearerAuth")
class RefundController(
    private val refundService: RefundService
) {

    @PostMapping
    @Operation(
        summary = "Create a refund",
        description = "Initiates a refund for a payment. Requires Idempotency-Key header to prevent duplicate refunds."
    )
    fun createRefund(
        @PathVariable paymentId: UUID,
        @Valid @RequestBody request: CreateRefundRequest,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @AuthenticationPrincipal userDetails: UserDetails
    ): ResponseEntity<RefundResponse> {
        val userId = UUID.fromString(userDetails.username)
        val idempotencyKeyUuid = UUID.fromString(idempotencyKey)

        val command = RefundService.CreateRefundCommand(
            paymentId = paymentId,
            amountCents = request.amountCents,
            userId = userId
        )

        val result = refundService.createRefund(command, idempotencyKeyUuid)

        val status = if (result.isCached) HttpStatus.OK else HttpStatus.CREATED

        val response = RefundResponse(
            id = result.refund.id,
            paymentId = result.refund.paymentId,
            userId = result.refund.userId,
            amountCents = result.refund.amountCents,
            currency = result.refund.currency,
            status = result.refund.status,
            providerRefundId = result.refund.providerRefundId,
            createdAt = result.refund.createdAt,
            updatedAt = result.refund.updatedAt
        )

        return ResponseEntity.status(status).body(response)
    }

    @GetMapping
    @Operation(summary = "Get refunds for a payment", description = "Retrieves all refunds for a specific payment")
    fun getRefundsByPayment(
        @PathVariable paymentId: UUID,
        @AuthenticationPrincipal userDetails: UserDetails
    ): ResponseEntity<List<RefundResponse>> {
        val userId = UUID.fromString(userDetails.username)
        val refunds = refundService.getRefundsByPayment(paymentId)

        val filteredRefunds = refunds.filter { it.userId == userId }

        val response = filteredRefunds.map { refund ->
            RefundResponse(
                id = refund.id,
                paymentId = refund.paymentId,
                userId = refund.userId,
                amountCents = refund.amountCents,
                currency = refund.currency,
                status = refund.status,
                providerRefundId = refund.providerRefundId,
                createdAt = refund.createdAt,
                updatedAt = refund.updatedAt
            )
        }

        return ResponseEntity.ok(response)
    }
}

@RestController
@RequestMapping("/api/refunds")
@SecurityRequirement(name = "bearerAuth")
class RefundDetailController(
    private val refundService: RefundService
) {

    @GetMapping("/{refundId}")
    @Operation(summary = "Get refund by ID", description = "Retrieves refund details")
    fun getRefund(
        @PathVariable refundId: UUID,
        @AuthenticationPrincipal userDetails: UserDetails
    ): ResponseEntity<RefundResponse> {
        val userId = UUID.fromString(userDetails.username)
        val refund = refundService.getRefund(refundId)

        if (refund.userId != userId) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }

        val response = RefundResponse(
            id = refund.id,
            paymentId = refund.paymentId,
            userId = refund.userId,
            amountCents = refund.amountCents,
            currency = refund.currency,
            status = refund.status,
            providerRefundId = refund.providerRefundId,
            createdAt = refund.createdAt,
            updatedAt = refund.updatedAt
        )

        return ResponseEntity.ok(response)
    }

    @ExceptionHandler(RefundAmountExceededException::class)
    fun handleRefundAmountExceeded(ex: RefundAmountExceededException): ResponseEntity<out Map<String, Any?>?> {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
            mapOf(
                "type" to "refund-amount-exceeded",
                "title" to "Refund amount exceeds refundable amount",
                "detail" to ex.message,
                "paymentId" to ex.paymentId,
                "requestedAmount" to ex.requestedAmount,
                "refundableAmount" to ex.refundableAmount
            )
        )
    }

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
