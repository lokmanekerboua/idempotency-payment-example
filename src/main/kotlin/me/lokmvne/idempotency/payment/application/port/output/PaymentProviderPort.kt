package me.lokmvne.idempotency.payment.application.port.output

import java.math.BigDecimal
import java.util.UUID

interface PaymentProviderPort {
    data class CreatePaymentIntentRequest(
        val amountCents: Long,
        val currency: String,
        val orderId: UUID,
        val providerIdempotencyKey: String,
        val metadata: Map<String, String>
    )

    data class CreatePaymentIntentResponse(
        val providerPaymentId: String,
        val clientSecret: String?,
        val status: String,
        val amount: Long,
        val currency: String
    )

    data class RetrievePaymentIntentResponse(
        val providerPaymentId: String,
        val status: String,
        val amount: Long,
        val currency: String,
        val metadata: Map<String, String>
    )

    data class CreateRefundRequest(
        val providerPaymentId: String,
        val amountCents: Long,
        val providerIdempotencyKey: String,
        val metadata: Map<String, String>
    )

    data class CreateRefundResponse(
        val providerRefundId: String,
        val status: String,
        val amount: Long,
        val currency: String
    )

    fun createPaymentIntent(request: CreatePaymentIntentRequest): CreatePaymentIntentResponse

    fun retrievePaymentIntent(providerPaymentId: String): RetrievePaymentIntentResponse

    fun createRefund(request: CreateRefundRequest): CreateRefundResponse

    fun retrieveRefundStatus(providerRefundId: String): String

    fun cancelPaymentIntent(providerPaymentId: String): Boolean
}
