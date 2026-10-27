-- Users table
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'CUSTOMER',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Orders table
CREATE TABLE orders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    order_reference VARCHAR(50) NOT NULL UNIQUE,
    amount_cents BIGINT NOT NULL CHECK (amount_cents > 0),
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'CREATED',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_orders_user FOREIGN KEY (user_id) REFERENCES users(id)
);

-- Indexes for orders
CREATE INDEX idx_orders_user_id ON orders(user_id);
CREATE INDEX idx_orders_status ON orders(status);
CREATE INDEX idx_orders_created_at ON orders(created_at);

-- Payments table
CREATE TABLE payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL,
    user_id UUID NOT NULL,
    amount_cents BIGINT NOT NULL CHECK (amount_cents > 0),
    currency VARCHAR(3) NOT NULL,
    payment_status VARCHAR(30) NOT NULL DEFAULT 'CREATED',
    provider VARCHAR(20) NOT NULL DEFAULT 'stripe',
    provider_payment_id VARCHAR(255),
    provider_idempotency_key VARCHAR(255),
    failure_code VARCHAR(100),
    failure_message VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_payments_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT fk_payments_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT uk_provider_payment_id UNIQUE (provider_payment_id)
);

-- Indexes for payments
CREATE INDEX idx_payments_order_id ON payments(order_id);
CREATE INDEX idx_payments_user_id ON payments(user_id);
CREATE INDEX idx_payments_status ON payments(payment_status);
CREATE INDEX idx_payments_provider_payment_id ON payments(provider_payment_id);
CREATE INDEX idx_payments_created_at ON payments(created_at);
CREATE INDEX idx_payments_order_status ON payments(order_id, payment_status);

-- Partial unique index to prevent duplicate successful payments for the same order
-- This ensures that an order can only have one succeeded payment
CREATE UNIQUE INDEX uk_order_successful_payment
ON payments(order_id)
WHERE payment_status = 'SUCCEEDED';

-- Idempotency records table
CREATE TABLE idempotency_records (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    operation_type VARCHAR(20) NOT NULL,
    idempotency_key UUID NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    processing_status VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS',
    response_http_status INTEGER,
    response_body TEXT,
    resource_id UUID,
    lease_owner VARCHAR(100),
    lease_expires_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_idempotency_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT uk_idempotency_scope UNIQUE (user_id, operation_type, idempotency_key)
);

-- Indexes for idempotency records
CREATE INDEX idx_idempotency_user_operation_key ON idempotency_records(user_id, operation_type, idempotency_key);
CREATE INDEX idx_idempotency_status ON idempotency_records(processing_status);
CREATE INDEX idx_idempotency_expires_at ON idempotency_records(expires_at);
CREATE INDEX idx_idempotency_lease ON idempotency_records(lease_owner, lease_expires_at);

-- Refunds table
CREATE TABLE refunds (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id UUID NOT NULL,
    user_id UUID NOT NULL,
    amount_cents BIGINT NOT NULL CHECK (amount_cents > 0),
    currency VARCHAR(3) NOT NULL,
    refund_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    provider_refund_id VARCHAR(255),
    provider_idempotency_key VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_refunds_payment FOREIGN KEY (payment_id) REFERENCES payments(id),
    CONSTRAINT fk_refunds_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT uk_provider_refund_id UNIQUE (provider_refund_id)
);

-- Indexes for refunds
CREATE INDEX idx_refunds_payment_id ON refunds(payment_id);
CREATE INDEX idx_refunds_user_id ON refunds(user_id);
CREATE INDEX idx_refunds_status ON refunds(refund_status);
CREATE INDEX idx_refunds_created_at ON refunds(created_at);

-- Webhook events table
CREATE TABLE webhook_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider VARCHAR(20) NOT NULL,
    provider_event_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    provider_object_id VARCHAR(255),
    payload TEXT NOT NULL,
    processing_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP WITH TIME ZONE,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    CONSTRAINT uk_provider_event_id UNIQUE (provider, provider_event_id)
);

-- Indexes for webhook events
CREATE INDEX idx_webhook_provider_event_id ON webhook_events(provider_event_id);
CREATE INDEX idx_webhook_event_type ON webhook_events(event_type);
CREATE INDEX idx_webhook_status ON webhook_events(processing_status);
CREATE INDEX idx_webhook_received_at ON webhook_events(received_at);

-- Outbox events table
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id UUID NOT NULL,
    aggregate_type VARCHAR(50) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP WITH TIME ZONE,
    attempt_count INTEGER NOT NULL DEFAULT 0
);

-- Indexes for outbox events
CREATE INDEX idx_outbox_status ON outbox_events(status);
CREATE INDEX idx_outbox_aggregate ON outbox_events(aggregate_id, aggregate_type);
CREATE INDEX idx_outbox_created_at ON outbox_events(created_at);
