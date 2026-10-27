# Idempotent payment API — Kotlin / Spring Boot

> **Experimental and unverified.** This project has not been built or tested in the editing environment. Do not use it for real payments until the checks below pass and the recovery design has been reviewed.

## Configuration

Install Java 21 and Docker with Compose. Copy `.env.example` to `.env`, then set a strong database password and JWT secret, and provide Stripe **test-mode** API and webhook secrets. Never commit `.env`. Run `docker compose up --build`. Swagger UI is at `http://localhost:8080/swagger-ui.html` and the OpenAPI document is at `http://localhost:8080/api-docs`. The Unix Gradle wrapper can run tests with `./gradlew test`; Windows users can use `gradlew.bat test`. External Gradle and Maven downloads are required.

## Payment and refund recovery

The application reserves the caller's idempotency key, then commits a payment or refund row together with its provider key and a link from the idempotency record **before** the Stripe call. For refunds the payment row is locked while calculating the amount already succeeded or pending. The provider request uses the stored amount, currency, metadata identifiers, and key.

If a provider call fails or its response is lost, the local operation is left in progress; it is not assumed to have failed at Stripe. The scheduled reconciliation process attempts the same provider request with the same persisted parameters and key. Stripe may eventually forget idempotency keys, so this implementation refuses automatic creation retries once the reservation is 23 hours old and logs a manual-reconciliation requirement. A provider error, including a cached error, can still require manual investigation. Never generate a fresh key merely because a response was lost.

A per-order guard prevents creating a second payment attempt while the previous one might be active or successful. When Stripe confirms cancellation, the guard for that specific attempt can be released so a new key can be used. A guard migrated from an older database or an unresolved uncertain attempt may require manual review. Never delete one without first reconciling the order with Stripe.

Webhooks verify Stripe's signature, insert events with a unique provider event ID, and retrieve current provider status for recognized payment intent and refund events. Missing local resources cause a retriable error. Test events arriving concurrently and out of order.

## Required verification before use

- Compile the project and run the tests on Java 21; fix any remaining build, mapping, and schema-validation errors.
- Run PostgreSQL and Stripe test-mode integration tests, including process termination before and after each transaction and provider call. Test simultaneous keys, duplicate requests, partial refunds, provider timeouts and cached errors, delayed webhooks, multiple application instances, and the 23-hour cutoff.
- Review operations and manual reconciliation for records created by versions before this change, missing resource links and keys older than the cutoff. Test guard release only after a definitive cancellation.
- Review security, event delivery, observability, data retention, and Stripe-specific behavior. The outbox currently only logs events; it is not connected to a message broker.

## HTTP flow

Register or log in via `/api/auth`, then authorize Swagger using the returned JWT. Create an order at `/api/orders`. Create a payment at `/api/payments` with a UUID `Idempotency-Key` and the order ID. Repeat the same request with the same key to retrieve the result once completed; reusing a key with different parameters is rejected. Refunds use `/api/payments/{paymentId}/refunds` with a separate UUID key. Configure Stripe to send signed events to `/api/webhooks/stripe`. Consult Swagger for the request schemas.
