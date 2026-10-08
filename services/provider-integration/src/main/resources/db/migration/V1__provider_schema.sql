-- Non-secret provider configuration. Secrets (keys, salts) live ONLY in environment/secret storage.
CREATE TABLE provider_configs (
    provider    VARCHAR(30) PRIMARY KEY,
    enabled     BOOLEAN      NOT NULL,
    mode        VARCHAR(10)  NOT NULL,          -- TEST | LIVE
    priority    INT          NOT NULL,          -- lower = preferred
    base_url    VARCHAR(200) NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL
);

-- One row per call we make to / verify from a provider (sanitized: no secrets, hashes, cards).
CREATE TABLE provider_attempts (
    id             BIGSERIAL PRIMARY KEY,
    provider       VARCHAR(30)  NOT NULL,
    operation      VARCHAR(30)  NOT NULL,       -- CREATE_PAYMENT | STATUS | VERIFY_CALLBACK | REFUND
    reference      VARCHAR(60),                 -- our provider reference (txnid) or refund token
    outcome        VARCHAR(20)  NOT NULL,       -- OK | REJECTED | UNKNOWN | ERROR | INVALID
    http_status    INT,
    latency_ms     INT,
    detail         VARCHAR(500),
    correlation_id VARCHAR(64),
    created_at     TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_provider_attempts_ref ON provider_attempts (provider, reference);
CREATE INDEX ix_provider_attempts_created ON provider_attempts (created_at);

-- Who/what changed provider configuration.
CREATE TABLE provider_audit (
    id         BIGSERIAL PRIMARY KEY,
    provider   VARCHAR(30)  NOT NULL,
    actor      VARCHAR(60)  NOT NULL,
    action     VARCHAR(40)  NOT NULL,
    detail     VARCHAR(500),
    created_at TIMESTAMPTZ  NOT NULL
);
