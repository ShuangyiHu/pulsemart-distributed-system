CREATE TABLE outbox (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id    UUID        NOT NULL UNIQUE,
    event_type  VARCHAR(60) NOT NULL,
    aggregate_id UUID       NOT NULL,
    payload     JSONB       NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INT         NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at     TIMESTAMPTZ
);

-- Partial index: only index PENDING rows — the publisher query filter
CREATE INDEX idx_outbox_pending ON outbox(created_at) WHERE status = 'PENDING';
