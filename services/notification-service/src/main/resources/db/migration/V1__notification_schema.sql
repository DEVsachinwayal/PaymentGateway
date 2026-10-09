-- Merchant webhook deliveries (spec 12/17). One job per (event, endpoint): consuming an event twice never notifies twice.
CREATE TABLE notification_jobs (
    id               UUID PRIMARY KEY,
    event_id         UUID         NOT NULL,
    event_type       VARCHAR(60)  NOT NULL,
    merchant_id      UUID         NOT NULL,
    endpoint_id      UUID         NOT NULL,
    endpoint_url     VARCHAR(500) NOT NULL,          -- snapshot at the time the event happened
    payload          TEXT         NOT NULL,          -- exactly what is sent (no secrets, no PAN: only ids, amounts, statuses)
    status           VARCHAR(12)  NOT NULL,          -- PENDING | RETRYING | DELIVERED | DEAD | CANCELLED
    attempts         INT          NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ,
    lock_until       TIMESTAMPTZ,                    -- short lease so two instances never deliver the same job at once
    last_http_status INT,
    last_error       VARCHAR(300),
    created_at       TIMESTAMPTZ  NOT NULL,
    delivered_at     TIMESTAMPTZ,
    CONSTRAINT ux_job_event_endpoint UNIQUE (event_id, endpoint_id)
);
CREATE INDEX ix_job_due ON notification_jobs (next_attempt_at) WHERE status IN ('PENDING', 'RETRYING');
CREATE INDEX ix_job_merchant ON notification_jobs (merchant_id, created_at DESC);

CREATE TABLE notification_attempts (
    id          BIGSERIAL PRIMARY KEY,
    job_id      UUID         NOT NULL REFERENCES notification_jobs (id),
    attempt_no  INT          NOT NULL,
    outcome     VARCHAR(20)  NOT NULL,               -- OK | HTTP_ERROR | TIMEOUT | NETWORK | BLOCKED
    http_status INT,
    detail      VARCHAR(300),
    duration_ms BIGINT,
    created_at  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_attempt_job ON notification_attempts (job_id, id);
