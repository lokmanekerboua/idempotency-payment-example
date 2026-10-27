-- Keep the guard committed even if a provider call or the main transaction fails.
-- Existing orders with any payment are protected as a conservative migration.
CREATE TABLE payment_attempt_guards (
    order_id UUID PRIMARY KEY,
    attempt_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO payment_attempt_guards (order_id, attempt_id)
SELECT DISTINCT order_id, order_id FROM payments
ON CONFLICT (order_id) DO NOTHING;
