-- A payment_intent is what the merchant asked for; a payment is the state-machine aggregate created with it.
CREATE TABLE payment_intents (
    id              UUID PRIMARY KEY,
    merchant_id     UUID         NOT NULL,
    order_id        UUID         NOT NULL,
    amount_minor    BIGINT       NOT NULL CHECK (amount_minor > 0),   -- always taken from the order, never the client
    currency        VARCHAR(3)   NOT NULL,
    payment_method  VARCHAR(20),                                     -- UPI | CARD | NETBANKING | WALLET (optional hint)
    return_url      VARCHAR(500),
    created_at      TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_intents_merchant ON payment_intents (merchant_id, created_at DESC);

CREATE TABLE payments (
    id                   UUID PRIMARY KEY,
    intent_id            UUID         NOT NULL REFERENCES payment_intents (id),
    merchant_id          UUID         NOT NULL,
    order_id             UUID         NOT NULL,
    amount_minor         BIGINT       NOT NULL,
    currency             VARCHAR(3)   NOT NULL,
    status               VARCHAR(20)  NOT NULL,
    refunded_amount_minor BIGINT      NOT NULL DEFAULT 0,
    provider             VARCHAR(30),
    provider_payment_id  VARCHAR(100),
    created_at           TIMESTAMPTZ  NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL,
    version              BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX ix_payments_merchant_created ON payments (merchant_id, created_at DESC);
CREATE INDEX ix_payments_status ON payments (status);
CREATE INDEX ix_payments_provider_ref ON payments (provider, provider_payment_id);
-- only one live payment per order; a FAILED one frees the order for a new attempt
CREATE UNIQUE INDEX ux_payments_open_order ON payments (order_id) WHERE status <> 'FAILED';

CREATE TABLE payment_attempts (          -- used by the orchestrator (M6/M7)
    id                  UUID PRIMARY KEY,
    payment_id          UUID         NOT NULL REFERENCES payments (id),
    attempt_no          INT          NOT NULL,
    provider            VARCHAR(30)  NOT NULL,
    provider_payment_id VARCHAR(100),
    status              VARCHAR(20)  NOT NULL,
    failure_code        VARCHAR(60),
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,
    UNIQUE (payment_id, attempt_no)
);

CREATE TABLE payment_state_history (
    id             BIGSERIAL PRIMARY KEY,
    payment_id     UUID         NOT NULL REFERENCES payments (id),
    from_status    VARCHAR(20),
    to_status      VARCHAR(20)  NOT NULL,
    reason         VARCHAR(300),
    source         VARCHAR(40)  NOT NULL,       -- API | ORCHESTRATOR | WEBHOOK | VERIFICATION | OPS
    correlation_id VARCHAR(64),
    created_at     TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_state_history_payment ON payment_state_history (payment_id, id);

-- Transactional outbox: events are written in the same transaction as the state change, published to Kafka in M12.
CREATE TABLE outbox_events (
    id             UUID PRIMARY KEY,
    event_type     VARCHAR(60)  NOT NULL,
    aggregate_id   UUID         NOT NULL,
    merchant_id    UUID         NOT NULL,
    correlation_id VARCHAR(64),
    payload        TEXT         NOT NULL,
    occurred_at    TIMESTAMPTZ  NOT NULL,
    published_at   TIMESTAMPTZ
);
CREATE INDEX ix_outbox_unpublished ON outbox_events (occurred_at) WHERE published_at IS NULL;

CREATE TABLE idempotency_keys (
    merchant_id     UUID         NOT NULL,
    idem_key        VARCHAR(100) NOT NULL,
    request_hash    VARCHAR(64)  NOT NULL,
    status          VARCHAR(12)  NOT NULL,
    response_status INT,
    response_body   TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (merchant_id, idem_key)
);
