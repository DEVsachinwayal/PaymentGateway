-- Refund lifecycle (spec 13). Amounts are minor units. A refund row is written BEFORE the provider is called.
CREATE TABLE refunds (
    id                  UUID PRIMARY KEY,
    merchant_id         UUID         NOT NULL,
    payment_id          UUID         NOT NULL,
    amount              BIGINT       NOT NULL CHECK (amount > 0),
    currency            VARCHAR(3)   NOT NULL,
    status              VARCHAR(20)  NOT NULL,      -- CREATED | PROCESSING | SUCCESS | FAILED | VERIFY_REQUIRED
    reason              VARCHAR(300),
    provider            VARCHAR(30)  NOT NULL,
    provider_payment_id VARCHAR(100) NOT NULL,
    refund_reference    VARCHAR(23)  NOT NULL,      -- our token sent to the provider; unique, alphanumeric
    provider_request_id VARCHAR(100),
    failure_message     VARCHAR(300),
    dispatch_lock_until TIMESTAMPTZ,                -- short lease so two workers never call the provider for one refund
    check_count         INT          NOT NULL DEFAULT 0,
    next_check_at       TIMESTAMPTZ,
    payment_synced      BOOLEAN      NOT NULL DEFAULT FALSE,   -- payment-service already reflects this refund's state
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ux_refund_reference UNIQUE (refund_reference)
);
CREATE INDEX ix_refund_merchant ON refunds (merchant_id, created_at DESC);
CREATE INDEX ix_refund_payment ON refunds (payment_id);
CREATE INDEX ix_refund_work ON refunds (status, next_check_at) WHERE status IN ('CREATED', 'PROCESSING');
CREATE INDEX ix_refund_unsynced ON refunds (payment_id) WHERE NOT payment_synced;

CREATE TABLE refund_attempts (                      -- every provider interaction, for audit
    id         BIGSERIAL PRIMARY KEY,
    refund_id  UUID         NOT NULL REFERENCES refunds (id),
    kind       VARCHAR(10)  NOT NULL,               -- REQUEST | STATUS
    outcome    VARCHAR(20)  NOT NULL,
    detail     VARCHAR(300),
    created_at TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_refund_attempt_refund ON refund_attempts (refund_id);

CREATE TABLE outbox_events (                        -- transactional outbox (published to Kafka in M12); payload = full event envelope (spec 17.1)
    id             UUID PRIMARY KEY,
    event_type     VARCHAR(60)  NOT NULL,
    aggregate_id   UUID         NOT NULL,
    merchant_id    UUID         NOT NULL,
    correlation_id VARCHAR(64),
    payload        TEXT         NOT NULL,
    occurred_at    TIMESTAMPTZ  NOT NULL,
    published_at   TIMESTAMPTZ
);
CREATE INDEX ix_refund_outbox_unpublished ON outbox_events (occurred_at) WHERE published_at IS NULL;

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
