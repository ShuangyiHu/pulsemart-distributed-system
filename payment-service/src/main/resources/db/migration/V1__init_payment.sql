CREATE TABLE payments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id        UUID           NOT NULL,
    reservation_id  UUID,
    customer_id     UUID           NOT NULL,
    amount          NUMERIC(12, 2) NOT NULL,
    status          VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    failure_reason  VARCHAR(255),
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_payments_order_id ON payments(order_id);

CREATE TABLE outbox (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id     UUID           NOT NULL UNIQUE,
    event_type   VARCHAR(60)    NOT NULL,
    aggregate_id UUID           NOT NULL,
    payload      JSONB          NOT NULL,
    status       VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    retry_count  INT            NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ    NOT NULL DEFAULT now(),
    sent_at      TIMESTAMPTZ
);
CREATE INDEX idx_outbox_pending ON outbox(created_at) WHERE status = 'PENDING';

CREATE TABLE processed_events (
    event_id      UUID PRIMARY KEY,
    processed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
