package com.apg.common.audit;

import com.apg.common.web.CallerContext;
import com.apg.common.web.CorrelationIdFilter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Audit trail for security- and money-relevant actions (spec 19: "audit logs"). Each event is written to the service's own
 * {@code audit_events} table (append-only, enforced by triggers in the migration, see {@link #TABLE_DDL}) and to the log
 * channel {@code apg.audit}. Details must never contain secrets, tokens or card data; they are also masked on output.
 * Writing joins the caller's transaction, so an action and its audit row succeed or fail together.
 */
public class AuditLog {
    public static final String TABLE_DDL = """
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
            CREATE TRIGGER trg_audit_truncate BEFORE TRUNCATE ON audit_events FOR EACH STATEMENT EXECUTE FUNCTION audit_events_immutable();""";

    private static final Logger audit = LoggerFactory.getLogger("apg.audit");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AuditLog(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Actor for a gateway-authenticated caller: the dashboard user, or the merchant behind an API key. */
    public static String actorOf(CallerContext c) {
        if (c == null) return "unknown";
        if (c.isUser()) return "user:" + c.userId();
        return "api_key:" + c.merchantId();
    }

    public void record(CallerContext c, String action, String target, String outcome, Map<String, ?> details) {
        record(actorOf(c), c == null ? null : c.merchantId(), action, target, outcome, details);
    }

    public void record(String actor, UUID merchantId, String action, String target, String outcome, Map<String, ?> details) {
        String json = null;
        if (details != null && !details.isEmpty()) {
            try {
                json = mapper.writeValueAsString(new LinkedHashMap<>(details));
            } catch (JsonProcessingException e) {
                json = "{\"error\":\"details not serialisable\"}";
            }
        }
        jdbc.update("INSERT INTO audit_events (id, occurred_at, action, actor, merchant_id, target, outcome, details, correlation_id) VALUES (?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), Timestamp.from(Instant.now()), action, actor, merchantId, target, outcome, json, MDC.get(CorrelationIdFilter.MDC_KEY));
        audit.info("AUDIT action={} actor={} merchant={} target={} outcome={}", action, actor, merchantId, target, outcome);
    }
}
