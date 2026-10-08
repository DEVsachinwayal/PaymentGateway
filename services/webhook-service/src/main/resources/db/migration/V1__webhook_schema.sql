-- Provider callbacks (browser returns and server webhooks). Only a sanitized allow-list of fields is kept.
CREATE TABLE webhook_events (
    id                 UUID PRIMARY KEY,
    provider           VARCHAR(30)  NOT NULL,
    fingerprint        VARCHAR(64)  NOT NULL,       -- sha256(provider|reference|status|unmapped|amount|providerPaymentId)
    provider_reference VARCHAR(60),
    authentic          BOOLEAN      NOT NULL,
    status             VARCHAR(20)  NOT NULL,       -- RECEIVED | PROCESSED | RETRYING | DEAD | UNMATCHED | REJECTED
    payload            TEXT,                        -- sanitized JSON; NULL for rejected (unauthenticated) calls
    attempts           INT          NOT NULL DEFAULT 0,
    next_retry_at      TIMESTAMPTZ,
    last_result        VARCHAR(300),
    created_at         TIMESTAMPTZ  NOT NULL,
    processed_at       TIMESTAMPTZ
);
-- replay / duplicate protection: one authentic event per fingerprint
CREATE UNIQUE INDEX ux_webhook_fingerprint ON webhook_events (provider, fingerprint) WHERE authentic;
CREATE INDEX ix_webhook_retry ON webhook_events (next_retry_at) WHERE status IN ('RECEIVED', 'RETRYING');
CREATE INDEX ix_webhook_reference ON webhook_events (provider, provider_reference);

CREATE TABLE webhook_delivery_attempts (        -- each try to hand an event to the orchestrator
    id         BIGSERIAL PRIMARY KEY,
    event_id   UUID         NOT NULL REFERENCES webhook_events (id),
    attempt_no INT          NOT NULL,
    outcome    VARCHAR(20)  NOT NULL,
    detail     VARCHAR(300),
    created_at TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_delivery_event ON webhook_delivery_attempts (event_id);

-- webhook.received events for Kafka (publisher arrives in M12)
CREATE TABLE outbox_events (
    id             UUID PRIMARY KEY,
    event_type     VARCHAR(60)  NOT NULL,
    aggregate_id   UUID         NOT NULL,
    payload        TEXT         NOT NULL,
    occurred_at    TIMESTAMPTZ  NOT NULL,
    published_at   TIMESTAMPTZ
);
