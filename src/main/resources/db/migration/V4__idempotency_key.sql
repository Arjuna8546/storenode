-- The terminal sends one key per sale (the Idempotency-Key header). The key is stored with the order,
-- and UNIQUE means the database itself refuses a second order for the same key.
ALTER TABLE orders ADD COLUMN idempotency_key VARCHAR(100);

-- Orders that already exist get their own id as a key, so nothing is left empty.
UPDATE orders SET idempotency_key = id::text;

ALTER TABLE orders ALTER COLUMN idempotency_key SET NOT NULL;
ALTER TABLE orders ADD CONSTRAINT uq_orders_idempotency_key UNIQUE (idempotency_key);
