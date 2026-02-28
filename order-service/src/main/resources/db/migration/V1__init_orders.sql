CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE orders (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    status        VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    total_amount  NUMERIC(12, 2) NOT NULL,
    customer_id   UUID        NOT NULL,
    reservation_id UUID,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE order_items (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id    UUID        NOT NULL REFERENCES orders(id),
    product_id  UUID        NOT NULL,
    quantity    INT         NOT NULL CHECK (quantity > 0),
    unit_price  NUMERIC(12, 2) NOT NULL
);
CREATE INDEX idx_order_items_order_id ON order_items(order_id);

CREATE TABLE processed_events (
    event_id     UUID        PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
