-- Append-only audit trail (apg-common AuditLog). Never edited, never deleted.
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
