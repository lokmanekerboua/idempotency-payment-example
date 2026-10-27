package me.lokmvne.idempotency.payment.infrastructure.stripe

import com.stripe.Stripe
import com.stripe.exception.StripeException
import com.stripe.model.PaymentIntent
import com.stripe.model.Refund
import com.stripe.net.RequestOptions
import com.stripe.param.PaymentIntentCancelParams
import com.stripe.param.PaymentIntentCreateParams
import com.stripe.param.RefundCreateParams
import me.lokmvne.idempotency.payment.application.port.output.PaymentProviderPort
import me.lokmvne.idempotency.shared.config.PaymentProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class StripePaymentAdapter(
    private val paymentProperties: PaymentProperties
) : PaymentProviderPort {

    private val logger = LoggerFactory.getLogger(StripePaymentAdapter::class.java)

    init {
        Stripe.apiKey = paymentProperties.provider.apiKey
    }

    override fun createPaymentIntent(request: PaymentProviderPort.CreatePaymentIntentRequest): PaymentProviderPort.CreatePaymentIntentResponse {
        val params = PaymentIntentCreateParams.builder()
            .setAmount(request.amountCents)
            .setCurrency(request.currency.lowercase())
            .putAllMetadata(request.metadata)
            .build()

        val paymentIntent = try {
            PaymentIntent.create(params, RequestOptions.builder().setIdempotencyKey(request.providerIdempotencyKey).build())
        } catch (e: StripeException) {
            logger.error("Failed to create Stripe PaymentIntent", e)
            throw PaymentProviderException("Failed to create payment intent: ${e.message}", e)
        }

        return PaymentProviderPort.CreatePaymentIntentResponse(
            providerPaymentId = paymentIntent.id,
            clientSecret = paymentIntent.clientSecret,
            status = paymentIntent.status,
            amount = paymentIntent.amount,
            currency = paymentIntent.currency.uppercase()
        )
    }

    override fun retrievePaymentIntent(providerPaymentId: String): PaymentProviderPort.RetrievePaymentIntentResponse {
        val paymentIntent = try {
            PaymentIntent.retrieve(providerPaymentId)
        } catch (e: StripeException) {
            logger.error("Failed to retrieve Stripe PaymentIntent: $providerPaymentId", e)
            throw PaymentProviderException("Failed to retrieve payment intent: ${e.message}", e)
        }

        return PaymentProviderPort.RetrievePaymentIntentResponse(
            providerPaymentId = paymentIntent.id,
            status = paymentIntent.status,
            amount = paymentIntent.amount,
            currency = paymentIntent.currency.uppercase(),
            metadata = paymentIntent.metadata
        )
    }

    override fun createRefund(request: PaymentProviderPort.CreateRefundRequest): PaymentProviderPort.CreateRefundResponse {
        val params = RefundCreateParams.builder()
            .setPaymentIntent(request.providerPaymentId)
            .setAmount(request.amountCents)
            .putAllMetadata(request.metadata)
            .build()

        val refund = try {
            Refund.create(params, RequestOptions.builder().setIdempotencyKey(request.providerIdempotencyKey).build())
        } catch (e: StripeException) {
            logger.error("Failed to create Stripe Refund", e)
            throw PaymentProviderException("Failed to create refund: ${e.message}", e)
        }

        return PaymentProviderPort.CreateRefundResponse(
            providerRefundId = refund.id,
            status = refund.status,
            amount = refund.amount,
            currency = refund.currency.uppercase()
        )
    }

    override fun retrieveRefundStatus(providerRefundId: String): String = Refund.retrieve(providerRefundId).status

    override fun cancelPaymentIntent(providerPaymentId: String): Boolean {
        return try {
            val params = PaymentIntentCancelParams.builder().build()
            val paymentIntent = PaymentIntent.retrieve(providerPaymentId)
            paymentIntent.cancel(params)
            true
        } catch (e: StripeException) {
            logger.error("Failed to cancel Stripe PaymentIntent: $providerPaymentId", e)
            false
        }
    }
}

class PaymentProviderException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
