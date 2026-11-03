-- The outbox: written in the same transaction as the order, delivered to the kitchen afterwards.
CREATE TABLE outbox_event (
    id               UUID          PRIMARY KEY,
    aggregate_id     UUID          NOT NULL,
    event_type       VARCHAR(100)  NOT NULL,
    payload          JSONB         NOT NULL,
    status           VARCHAR(20)   NOT NULL,
    attempts         INTEGER       NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ   NOT NULL,
    last_error       TEXT,
    created_at       TIMESTAMPTZ   NOT NULL,
    published_at     TIMESTAMPTZ
);

-- Only waiting events are ever looked for, so the index stays small however many are already delivered.
CREATE INDEX idx_outbox_event_due ON outbox_event (next_attempt_at, created_at) WHERE status = 'PENDING';

-- order_id is UNIQUE: if the same event is delivered twice, the second ticket is refused.
CREATE TABLE kitchen_ticket (
    id            UUID         PRIMARY KEY,
    branch_id     UUID         NOT NULL,
    order_id      UUID         NOT NULL UNIQUE REFERENCES orders (id),
    order_number  VARCHAR(40)  NOT NULL,
    items         JSONB        NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_kitchen_ticket_new ON kitchen_ticket (created_at) WHERE status = 'NEW';
