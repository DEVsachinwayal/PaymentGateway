-- Provider settlement batches, their items and the reconciliation of both against APG's own records (spec 15, 16).
-- Evidence (the imported document) is never rewritten: a different document for the same batch is rejected.
CREATE TABLE settlements (
    id               UUID PRIMARY KEY,
    provider         VARCHAR(30)  NOT NULL,
    batch_ref        VARCHAR(100) NOT NULL,          -- the provider's own settlement/batch identifier
    settlement_date  DATE         NOT NULL,
    currency         VARCHAR(3)   NOT NULL,
    declared_gross   BIGINT       NOT NULL,          -- totals as stated by the provider
    declared_refunds BIGINT       NOT NULL,
    declared_fees    BIGINT       NOT NULL,          -- provider fees + tax
    declared_net     BIGINT       NOT NULL,
    status           VARCHAR(20)  NOT NULL,          -- IMPORTED | RECONCILED | HAS_EXCEPTIONS
    evidence_sha256  VARCHAR(64)  NOT NULL,
    evidence         TEXT         NOT NULL,
    imported_by      VARCHAR(100),
    event_emitted    BOOLEAN      NOT NULL DEFAULT FALSE,   -- settlement.created published (once)
    created_at       TIMESTAMPTZ  NOT NULL,
    reconciled_at    TIMESTAMPTZ,
    CONSTRAINT ux_settlement_batch UNIQUE (provider, batch_ref)
);

CREATE TABLE settlement_items (
    id                  UUID PRIMARY KEY,
    settlement_id       UUID         NOT NULL REFERENCES settlements (id),
    line_no             INT          NOT NULL,
    item_type           VARCHAR(12)  NOT NULL CHECK (item_type IN ('PAYMENT', 'REFUND', 'ADJUSTMENT')),
    provider_reference  VARCHAR(100),                -- txnid for payments, refund token for refunds
    provider_payment_id VARCHAR(100),
    amount              BIGINT       NOT NULL CHECK (amount >= 0),
    fee                 BIGINT       NOT NULL DEFAULT 0 CHECK (fee >= 0),
    tax                 BIGINT       NOT NULL DEFAULT 0 CHECK (tax >= 0),
    net                 BIGINT       NOT NULL,       -- PAYMENT: amount-fee-tax; REFUND: -amount; ADJUSTMENT: signed as given
    match_status        VARCHAR(14)  NOT NULL,       -- PENDING | MATCHED | EXCEPTION | ACKNOWLEDGED
    payment_id          UUID,
    refund_id           UUID,
    merchant_id         UUID,
    CONSTRAINT ux_settlement_line UNIQUE (settlement_id, line_no)
);
CREATE INDEX ix_item_reference ON settlement_items (item_type, provider_reference);
CREATE INDEX ix_item_settlement ON settlement_items (settlement_id, match_status);

CREATE TABLE reconciliation_runs (
    id            UUID PRIMARY KEY,
    settlement_id UUID        NOT NULL REFERENCES settlements (id),
    started_at    TIMESTAMPTZ NOT NULL,
    finished_at   TIMESTAMPTZ,
    matched       INT         NOT NULL DEFAULT 0,
    open_exceptions INT       NOT NULL DEFAULT 0,
    lookup_errors INT         NOT NULL DEFAULT 0     -- items we could not evaluate (dependency down): not reconciled, not silently passed
);
CREATE INDEX ix_run_settlement ON reconciliation_runs (settlement_id, started_at DESC);

CREATE TABLE reconciliation_exceptions (
    id               UUID PRIMARY KEY,
    settlement_id    UUID         NOT NULL REFERENCES settlements (id),
    item_id          UUID REFERENCES settlement_items (id),
    exc_type         VARCHAR(30)  NOT NULL,
    status           VARCHAR(14)  NOT NULL,           -- OPEN | INVESTIGATING | RESOLVED | WRITTEN_OFF
    owner            VARCHAR(100),
    reason           VARCHAR(300) NOT NULL,
    evidence         TEXT         NOT NULL,           -- what we compared (JSON); kept even after resolution
    resolution_notes VARCHAR(500),
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    resolved_at      TIMESTAMPTZ
);
-- a rerun can never create the same exception twice
CREATE UNIQUE INDEX ux_exception ON reconciliation_exceptions
    (settlement_id, COALESCE(item_id, '00000000-0000-0000-0000-000000000000'::uuid), exc_type);
CREATE INDEX ix_exception_status ON reconciliation_exceptions (status, created_at);

CREATE TABLE merchant_settlements (
    id               UUID PRIMARY KEY,
    settlement_id    UUID        NOT NULL REFERENCES settlements (id),
    merchant_id      UUID        NOT NULL,
    currency         VARCHAR(3)  NOT NULL,
    gross            BIGINT      NOT NULL,
    refunds          BIGINT      NOT NULL,
    provider_fees    BIGINT      NOT NULL,
    net              BIGINT      NOT NULL,
    status           VARCHAR(12) NOT NULL,            -- CALCULATED | PAID (PAID needs a payout reference as evidence)
    payout_reference VARCHAR(100),
    paid_at          TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL,
    CONSTRAINT ux_merchant_settlement UNIQUE (settlement_id, merchant_id),
    CONSTRAINT ck_paid_needs_evidence CHECK (status <> 'PAID' OR payout_reference IS NOT NULL)
);
CREATE INDEX ix_merchant_settlement_merchant ON merchant_settlements (merchant_id, created_at DESC);

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
CREATE INDEX ix_settlement_outbox_unpublished ON outbox_events (occurred_at) WHERE published_at IS NULL;

-- ---- evidence is immutable: a mismatch can be explained, never hidden by editing what the provider sent
CREATE FUNCTION settlement_no_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'settlement evidence cannot be deleted';
END;
$$;
CREATE TRIGGER trg_settlement_no_delete BEFORE DELETE ON settlements FOR EACH ROW EXECUTE FUNCTION settlement_no_delete();
CREATE TRIGGER trg_item_no_delete BEFORE DELETE ON settlement_items FOR EACH ROW EXECUTE FUNCTION settlement_no_delete();
CREATE TRIGGER trg_exception_no_delete BEFORE DELETE ON reconciliation_exceptions FOR EACH ROW EXECUTE FUNCTION settlement_no_delete();

CREATE FUNCTION settlement_evidence_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.provider <> OLD.provider OR NEW.batch_ref <> OLD.batch_ref OR NEW.settlement_date <> OLD.settlement_date
       OR NEW.currency <> OLD.currency OR NEW.declared_gross <> OLD.declared_gross OR NEW.declared_refunds <> OLD.declared_refunds
       OR NEW.declared_fees <> OLD.declared_fees OR NEW.declared_net <> OLD.declared_net
       OR NEW.evidence <> OLD.evidence OR NEW.evidence_sha256 <> OLD.evidence_sha256 THEN
        RAISE EXCEPTION 'settlement evidence cannot be modified';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_settlement_evidence BEFORE UPDATE ON settlements FOR EACH ROW EXECUTE FUNCTION settlement_evidence_guard();

CREATE FUNCTION settlement_item_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.settlement_id <> OLD.settlement_id OR NEW.line_no <> OLD.line_no OR NEW.item_type <> OLD.item_type
       OR NEW.provider_reference IS DISTINCT FROM OLD.provider_reference OR NEW.amount <> OLD.amount OR NEW.fee <> OLD.fee
       OR NEW.tax <> OLD.tax OR NEW.net <> OLD.net THEN
        RAISE EXCEPTION 'settlement item evidence cannot be modified';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_settlement_item_evidence BEFORE UPDATE ON settlement_items FOR EACH ROW EXECUTE FUNCTION settlement_item_guard();
