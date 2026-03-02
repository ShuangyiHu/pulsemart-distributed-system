CREATE TABLE products (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(255)   NOT NULL,
    stock_quantity  INT            NOT NULL CHECK (stock_quantity >= 0)
);

CREATE TABLE reservations (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id    UUID        NOT NULL,
    product_id  UUID        NOT NULL REFERENCES products(id),
    quantity    INT         NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'RESERVED',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_reservations_order_id ON reservations(order_id);

CREATE TABLE outbox (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id    UUID           NOT NULL UNIQUE,
    event_type  VARCHAR(60)    NOT NULL,
    aggregate_id UUID          NOT NULL,
    payload     JSONB          NOT NULL,
    status      VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    retry_count INT            NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    sent_at     TIMESTAMPTZ
);
CREATE INDEX idx_outbox_pending ON outbox(created_at) WHERE status = 'PENDING';

CREATE TABLE processed_events (
    event_id      UUID PRIMARY KEY,
    processed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Seed products for demo
INSERT INTO products (id, name, stock_quantity) VALUES
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'Widget Pro',    100),
    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'Gadget Plus',   50),
    ('cccccccc-cccc-cccc-cccc-cccccccccccc', 'Doohickey Max', 25);
