-- Minimal orchestration state (spec 8): an auditable record of WHY a provider was chosen.
CREATE TABLE routing_decisions (
    id                BIGSERIAL PRIMARY KEY,
    payment_id        UUID         NOT NULL,
    attempt_reference VARCHAR(40),
    provider          VARCHAR(30)  NOT NULL,
    candidates        VARCHAR(500) NOT NULL,       -- e.g. "payu(priority=1,up)"
    reason            VARCHAR(200) NOT NULL,
    correlation_id    VARCHAR(64),
    created_at        TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_routing_payment ON routing_decisions (payment_id);

-- Record of verification runs (what the provider said and what we did about it).
CREATE TABLE verification_log (
    id                BIGSERIAL PRIMARY KEY,
    attempt_reference VARCHAR(40)  NOT NULL,
    payment_id        UUID         NOT NULL,
    trigger_source    VARCHAR(20)  NOT NULL,       -- WEBHOOK | VERIFICATION
    provider_status   VARCHAR(20),
    result            VARCHAR(30)  NOT NULL,
    detail            VARCHAR(300),
    correlation_id    VARCHAR(64),
    created_at        TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_verification_attempt ON verification_log (attempt_reference);

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
