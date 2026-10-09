-- Immutable double-entry ledger (spec 14). Amounts are minor units. Corrections are compensating transactions, never edits.
CREATE TABLE ledger_accounts (
    id          UUID PRIMARY KEY,
    code        VARCHAR(40)  NOT NULL,       -- PROVIDER_RECEIVABLE_PAYU | MERCHANT_PAYABLE | APG_FEE_REVENUE | ...
    owner_type  VARCHAR(10)  NOT NULL,       -- PLATFORM | MERCHANT
    owner_id    UUID,
    currency    VARCHAR(3)   NOT NULL,
    normal_side VARCHAR(6)   NOT NULL CHECK (normal_side IN ('DEBIT', 'CREDIT')),
    created_at  TIMESTAMPTZ  NOT NULL,
    CHECK ((owner_type = 'PLATFORM' AND owner_id IS NULL) OR (owner_type = 'MERCHANT' AND owner_id IS NOT NULL))
);
CREATE UNIQUE INDEX ux_ledger_account ON ledger_accounts (code, COALESCE(owner_id, '00000000-0000-0000-0000-000000000000'::uuid), currency);

CREATE TABLE ledger_transactions (
    id             UUID PRIMARY KEY,
    event_id       UUID,                     -- the event that caused it: replays of the same event are no-ops
    tx_type        VARCHAR(30)  NOT NULL,    -- CAPTURE | REFUND | REVERSAL | ...
    reference_id   UUID         NOT NULL,    -- payment id / refund id / settlement id the posting is about
    merchant_id    UUID,
    currency       VARCHAR(3)   NOT NULL,
    description    VARCHAR(300),
    reverses_tx_id UUID REFERENCES ledger_transactions (id),
    correlation_id VARCHAR(64),
    occurred_at    TIMESTAMPTZ  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL
);
CREATE UNIQUE INDEX ux_ledger_tx_event    ON ledger_transactions (event_id) WHERE event_id IS NOT NULL;
CREATE UNIQUE INDEX ux_ledger_tx_ref      ON ledger_transactions (tx_type, reference_id) WHERE reverses_tx_id IS NULL;
CREATE UNIQUE INDEX ux_ledger_tx_reverses ON ledger_transactions (reverses_tx_id) WHERE reverses_tx_id IS NOT NULL;
CREATE INDEX ix_ledger_tx_merchant ON ledger_transactions (merchant_id, occurred_at DESC);

CREATE TABLE ledger_entries (
    id             BIGSERIAL PRIMARY KEY,
    transaction_id UUID        NOT NULL REFERENCES ledger_transactions (id),
    account_id     UUID        NOT NULL REFERENCES ledger_accounts (id),
    side           VARCHAR(6)  NOT NULL CHECK (side IN ('DEBIT', 'CREDIT')),
    amount         BIGINT      NOT NULL CHECK (amount > 0),
    currency       VARCHAR(3)  NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_ledger_entry_tx      ON ledger_entries (transaction_id);
CREATE INDEX ix_ledger_entry_account ON ledger_entries (account_id);

-- ---- immutability: no UPDATE / DELETE / TRUNCATE on transactions and entries
CREATE FUNCTION ledger_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'ledger rows are immutable: post a compensating transaction instead';
END;
$$;
CREATE TRIGGER trg_ledger_tx_immutable BEFORE UPDATE OR DELETE ON ledger_transactions FOR EACH ROW EXECUTE FUNCTION ledger_immutable();
CREATE TRIGGER trg_ledger_entry_immutable BEFORE UPDATE OR DELETE ON ledger_entries FOR EACH ROW EXECUTE FUNCTION ledger_immutable();
CREATE TRIGGER trg_ledger_tx_truncate BEFORE TRUNCATE ON ledger_transactions FOR EACH STATEMENT EXECUTE FUNCTION ledger_immutable();
CREATE TRIGGER trg_ledger_entry_truncate BEFORE TRUNCATE ON ledger_entries FOR EACH STATEMENT EXECUTE FUNCTION ledger_immutable();

-- ---- balance: checked at COMMIT, so the database itself refuses an unbalanced or empty transaction
CREATE FUNCTION ledger_check_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    d BIGINT; c BIGINT; n INT; cur INT;
BEGIN
    SELECT COALESCE(SUM(amount) FILTER (WHERE side = 'DEBIT'), 0), COALESCE(SUM(amount) FILTER (WHERE side = 'CREDIT'), 0), COUNT(*),
           COUNT(DISTINCT currency)
      INTO d, c, n, cur FROM ledger_entries WHERE transaction_id = NEW.id;
    IF n < 2 THEN
        RAISE EXCEPTION 'ledger transaction % needs at least two entries', NEW.id;
    END IF;
    IF d <> c THEN
        RAISE EXCEPTION 'unbalanced ledger transaction % (debit %, credit %)', NEW.id, d, c;
    END IF;
    IF cur <> 1 THEN
        RAISE EXCEPTION 'ledger transaction % mixes currencies', NEW.id;
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER trg_ledger_tx_balanced AFTER INSERT ON ledger_transactions
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ledger_check_balanced();
