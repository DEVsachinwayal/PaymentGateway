-- Shareable payment links: a stable public code that, when opened, starts a normal order + payment + hosted checkout.
CREATE TABLE payment_links (
    id                   UUID PRIMARY KEY,
    merchant_id          UUID         NOT NULL,
    code                 VARCHAR(16)  NOT NULL,          -- random, unguessable part of the public URL
    merchant_name        VARCHAR(150) NOT NULL,          -- snapshot shown to the customer
    merchant_support     VARCHAR(254),
    amount               BIGINT       NOT NULL CHECK (amount > 0),
    currency             VARCHAR(3)   NOT NULL,
    description          VARCHAR(300),
    customer_name        VARCHAR(120),
    customer_email       VARCHAR(254),
    customer_phone       VARCHAR(20),
    one_time             BOOLEAN      NOT NULL,           -- true: stops being payable after the first successful payment
    status               VARCHAR(12)  NOT NULL,           -- ACTIVE | PAID | EXPIRED | CANCELLED
    expires_at           TIMESTAMPTZ  NOT NULL,
    return_url           VARCHAR(500),
    successful_payments  INT          NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ  NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL,
    paid_at              TIMESTAMPTZ,
    CONSTRAINT ux_link_code UNIQUE (code)
);
CREATE INDEX ix_link_merchant ON payment_links (merchant_id, created_at DESC);

-- every time a customer presses "Pay" one attempt (one order + one payment) is created
CREATE TABLE link_attempts (
    id             UUID PRIMARY KEY,
    link_id        UUID         NOT NULL REFERENCES payment_links (id),
    order_id       UUID,
    payment_id     UUID,
    state          VARCHAR(16)  NOT NULL,                 -- STARTING | OPEN | SUCCEEDED | FAILED | ABANDONED | START_FAILED
    payment_status VARCHAR(30),                           -- last payment status seen in payment-service (informational)
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_attempt_link ON link_attempts (link_id, created_at DESC);
CREATE UNIQUE INDEX ux_attempt_payment ON link_attempts (payment_id) WHERE payment_id IS NOT NULL;

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

-- append-only audit trail (apg-common AuditLog)
CREATE TABLE audit_events (
    id             UUID PRIMARY KEY,
    occurred_at    TIMESTAMPTZ  NOT NULL,
    action         VARCHAR(60)  NOT NULL,
    actor          VARCHAR(120) NOT NULL,
    merchant_id    UUID,
    target         VARCHAR(120),
    outcome        VARCHAR(20)  NOT NULL,
    details        TEXT,
    correlation_id VARCHAR(64)
);
CREATE INDEX ix_audit_merchant ON audit_events (merchant_id, occurred_at DESC);
CREATE INDEX ix_audit_action ON audit_events (action, occurred_at DESC);
CREATE FUNCTION audit_events_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'audit events are append-only'; END; $$;
CREATE TRIGGER trg_audit_immutable BEFORE UPDATE OR DELETE ON audit_events FOR EACH ROW EXECUTE FUNCTION audit_events_immutable();
CREATE TRIGGER trg_audit_truncate BEFORE TRUNCATE ON audit_events FOR EACH STATEMENT EXECUTE FUNCTION audit_events_immutable();
