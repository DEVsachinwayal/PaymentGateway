package com.apg.webhook.service;

import com.apg.common.api.ApiException;
import com.apg.webhook.client.Peers;
import com.apg.webhook.client.Peers.PaymentView;
import com.apg.webhook.client.Peers.Verification;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Spec section 12: authenticate -> fingerprint -> de-duplicate -> persist (sanitized) -> hand to the orchestrator,
 * which asks the provider for the truth. An unsigned/forged callback never reaches the orchestrator and can never
 * change a payment. Duplicates are acknowledged without reprocessing.
 */
@Service
public class WebhookService {
    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    /** Only these fields are stored: no names, emails, phones, card data, hashes. */
    private static final Set<String> STORED_FIELDS = Set.of("txnid", "mihpayid", "status", "unmappedstatus", "amount", "mode",
            "bankcode", "error", "error_code", "error_Message", "bank_ref_num", "PG_TYPE", "udf1", "additionalCharges");

    public record Outcome(boolean authentic, boolean duplicate, String reference, PaymentView payment) {}

    private final Peers peers;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final WebhookProperties props;
    private final Clock clock;

    public WebhookService(Peers peers, JdbcTemplate jdbc, ObjectMapper mapper, WebhookProperties props, Clock clock) {
        this.peers = peers;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.props = props;
        this.clock = clock;
    }

    public Outcome handle(String provider, Map<String, String> fields) {
        Verification v = peers.verifyCallback(provider, fields);      // 404 unknown provider, 503 if verifier is down
        Instant now = clock.instant();
        if (!v.authentic()) {
            jdbc.update("""
                    INSERT INTO webhook_events (id, provider, fingerprint, provider_reference, authentic, status, last_result, created_at)
                    VALUES (?,?,?,?,false,'REJECTED',?,?)""", UUID.randomUUID(), provider, UUID.randomUUID().toString().replace("-", ""),
                    truncate(fields.get("txnid"), 60), truncate(v.reason(), 300), Timestamp.from(now));
            return new Outcome(false, false, null, null);
        }

        String reference = v.providerReference();
        String fingerprint = sha256(String.join("|", provider, reference, nz(v.status()), nz(fields.get("unmappedstatus")),
                String.valueOf(v.amountMinor()), nz(v.providerPaymentId())));
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO webhook_events (id, provider, fingerprint, provider_reference, authentic, status, payload, next_retry_at, created_at)
                VALUES (?,?,?,?,true,'RECEIVED',?,?,?)
                ON CONFLICT (provider, fingerprint) WHERE authentic DO NOTHING""",
                id, provider, fingerprint, reference, sanitizedJson(fields),
                Timestamp.from(now.plus(Duration.ofMinutes(1))),     // crash safety net: the retry worker picks it up if we die mid-processing
                Timestamp.from(now));

        boolean duplicate = inserted == 0;
        if (!duplicate) {
            jdbc.update("INSERT INTO outbox_events (id, event_type, aggregate_id, payload, occurred_at) VALUES (?,?,?,?,?)",
                    UUID.randomUUID(), "webhook.received", id, envelope(id, provider, reference, v.status()), Timestamp.from(now));
            process(id, reference);
        } else {
            log.info("duplicate {} callback for {} acknowledged without reprocessing", provider, reference);
        }
        return new Outcome(true, duplicate, reference, peers.paymentForAttempt(reference));
    }

    /** Hands one event to the orchestrator; on trouble schedules a retry with exponential backoff, finally DEAD. */
    public void process(UUID eventId, String reference) {
        Instant now = clock.instant();
        int attemptNo = jdbc.queryForObject("SELECT attempts FROM webhook_events WHERE id = ?", Integer.class, eventId) + 1;
        try {
            String result = peers.orchestratorVerify(reference);
            attempt(eventId, attemptNo, "OK", result, now);
            jdbc.update("UPDATE webhook_events SET status='PROCESSED', attempts=?, last_result=?, next_retry_at=NULL, processed_at=? WHERE id=?",
                    attemptNo, truncate(result, 300), Timestamp.from(now), eventId);
        } catch (ApiException e) {
            if (e.status().value() == 404) {      // authentic callback for a transaction we never created (other environment?)
                attempt(eventId, attemptNo, "UNMATCHED", e.code(), now);
                jdbc.update("UPDATE webhook_events SET status='UNMATCHED', attempts=?, last_result=?, next_retry_at=NULL WHERE id=?",
                        attemptNo, truncate(e.code(), 300), eventId);
                return;
            }
            attempt(eventId, attemptNo, "FAILED", e.code(), now);
            if (attemptNo >= props.retry().maxAttempts()) {
                jdbc.update("UPDATE webhook_events SET status='DEAD', attempts=?, last_result=?, next_retry_at=NULL WHERE id=?",
                        attemptNo, truncate("gave up: " + e.code(), 300), eventId);
                log.error("webhook event {} is DEAD after {} attempts ({})", eventId, attemptNo, e.code());
            } else {
                long delaySeconds = Math.min(10L << attemptNo, 900L);
                jdbc.update("UPDATE webhook_events SET status='RETRYING', attempts=?, last_result=?, next_retry_at=? WHERE id=?",
                        attemptNo, truncate(e.code(), 300), Timestamp.from(now.plusSeconds(delaySeconds)), eventId);
            }
        }
    }

    /** @return number of events retried */
    public int retryDue() {
        List<Map<String, Object>> due = jdbc.queryForList("""
                SELECT id, provider_reference FROM webhook_events
                WHERE status IN ('RECEIVED','RETRYING') AND next_retry_at <= ? ORDER BY created_at LIMIT 50""", Timestamp.from(clock.instant()));
        for (Map<String, Object> row : due) {
            try {
                process((UUID) row.get("id"), (String) row.get("provider_reference"));
            } catch (RuntimeException e) {
                log.warn("retry of webhook event {} failed unexpectedly: {}", row.get("id"), e.toString());
            }
        }
        return due.size();
    }

    private void attempt(UUID eventId, int no, String outcome, String detail, Instant now) {
        jdbc.update("INSERT INTO webhook_delivery_attempts (event_id, attempt_no, outcome, detail, created_at) VALUES (?,?,?,?,?)",
                eventId, no, outcome, truncate(detail, 300), Timestamp.from(now));
    }

    private String sanitizedJson(Map<String, String> fields) {
        Map<String, String> kept = new LinkedHashMap<>();
        for (String k : STORED_FIELDS) if (fields.get(k) != null) kept.put(k, truncate(fields.get(k), 200));
        try {
            return mapper.writeValueAsString(kept);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String envelope(UUID id, String provider, String reference, String status) {
        try {
            return mapper.writeValueAsString(Map.of("eventId", UUID.randomUUID(), "eventType", "webhook.received", "version", 1,
                    "aggregateId", id, "payload", Map.of("provider", provider, "reference", reference, "status", nz(status))));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String truncate(String s, int max) { return s == null ? null : s.substring(0, Math.min(max, s.length())); }
}
