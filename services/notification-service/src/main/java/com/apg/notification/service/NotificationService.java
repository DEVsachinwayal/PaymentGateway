package com.apg.notification.service;

import com.apg.common.api.ApiException;
import com.apg.notification.client.MerchantDirectory;
import com.apg.notification.client.MerchantDirectory.Subscriber;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns domain events into signed merchant webhooks. One job per (event, endpoint) so a redelivered event never notifies twice.
 * Delivery is at-least-once: merchants must de-duplicate on the event id. Failures retry with exponential backoff and end in DEAD
 * (the merchant can resend from the dashboard); nothing is silently dropped.
 */
@Service
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    /** Events a merchant may subscribe to and that carry merchant data (settlement.created is platform level). */
    public static final Set<String> MERCHANT_EVENTS = Set.of("payment.success", "payment.failed", "payment.unknown", "refund.success", "refund.failed");
    private static final UUID PLATFORM = new UUID(0, 0);
    private static final java.time.Duration LEASE = java.time.Duration.ofSeconds(60);

    public record JobView(UUID id, UUID eventId, String eventType, String endpointUrl, String status, int attempts, Instant nextAttemptAt,
                          Integer lastHttpStatus, String lastError, Instant createdAt, Instant deliveredAt, List<AttemptView> history) {}

    public record AttemptView(int attemptNo, String outcome, Integer httpStatus, String detail, long durationMs, Instant at) {}

    public record PageView<T>(List<T> content, int page, int size, long totalElements) {}

    private record Job(UUID id, UUID eventId, String type, UUID merchantId, UUID endpointId, String url, String payload, int attempts) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final MerchantDirectory directory;
    private final WebhookSender sender;
    private final NotificationProperties props;
    private final ObjectMapper mapper;
    private final Clock clock;

    private io.micrometer.core.instrument.MeterRegistry meters;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setMeters(io.micrometer.core.instrument.MeterRegistry meters) { this.meters = meters; }

    private void count(String name, String... tags) {
        if (meters != null) meters.counter(name, tags).increment();
    }

    public NotificationService(JdbcTemplate jdbc, TransactionTemplate tx, MerchantDirectory directory, WebhookSender sender,
                               NotificationProperties props, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.directory = directory;
        this.sender = sender;
        this.props = props;
        this.mapper = mapper;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ consuming events

    /** @return number of jobs created (0 for events nobody subscribed to, or already handled) */
    public int onEvent(String json) throws JsonProcessingException {
        JsonNode e = mapper.readTree(json);
        String type = text(e, "eventType");
        if (type == null || !MERCHANT_EVENTS.contains(type)) return 0;
        UUID eventId = uuid(e, "eventId");
        UUID merchantId = uuid(e, "merchantId");
        if (merchantId.equals(PLATFORM)) return 0;

        // if merchant-service is down this throws: the Kafka error handler retries, we never assume "no subscribers"
        List<Subscriber> subs = directory.subscribers(merchantId, type);
        if (subs.isEmpty()) return 0;
        String body = merchantPayload(e, eventId, type);
        Instant now = clock.instant();
        int created = 0;
        for (Subscriber s : subs) {
            created += jdbc.update("""
                    INSERT INTO notification_jobs (id, event_id, event_type, merchant_id, endpoint_id, endpoint_url, payload, status, next_attempt_at, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?) ON CONFLICT (event_id, endpoint_id) DO NOTHING""",
                    UUID.randomUUID(), eventId, type, merchantId, s.id(), s.url(), body, Timestamp.from(now), Timestamp.from(now));
        }
        return created;
    }

    /** What the merchant receives: stable, minimal, no internal correlation or merchant ids. */
    private String merchantPayload(JsonNode e, UUID eventId, String type) throws JsonProcessingException {
        ObjectNode out = mapper.createObjectNode();
        out.put("id", eventId.toString());
        out.put("type", type);
        out.put("createdAt", text(e, "occurredAt"));
        out.set("data", e.path("payload"));
        return mapper.writeValueAsString(out);
    }

    // ------------------------------------------------------------------ delivery

    /** @return number of jobs attempted */
    public int deliverDue() {
        Instant now = clock.instant();
        List<UUID> due = jdbc.query("""
                SELECT id FROM notification_jobs WHERE status IN ('PENDING', 'RETRYING') AND next_attempt_at <= ?
                AND (lock_until IS NULL OR lock_until < ?) ORDER BY next_attempt_at LIMIT ?""",
                (rs, i) -> rs.getObject(1, UUID.class), Timestamp.from(now), Timestamp.from(now), props.worker().batchSize());
        int n = 0;
        for (UUID id : due) {
            if (jdbc.update("""
                    UPDATE notification_jobs SET lock_until = ? WHERE id = ? AND status IN ('PENDING', 'RETRYING') AND (lock_until IS NULL OR lock_until < ?)""",
                    Timestamp.from(now.plus(LEASE)), id, Timestamp.from(now)) == 0) continue;
            n++;
            try {
                deliver(load(id));
            } catch (RuntimeException ex) {
                log.warn("delivery of job {} failed unexpectedly: {}", id, ex.toString());
                finishAttempt(load(id), new WebhookSender.Result("NETWORK", null, ex.getClass().getSimpleName(), 0));
            }
        }
        return n;
    }

    private void deliver(Job job) {
        Optional<Subscriber> sub;
        try {
            sub = directory.subscribers(job.merchantId(), job.type()).stream().filter(s -> s.id().equals(job.endpointId())).findFirst();
        } catch (RuntimeException e) {
            finishAttempt(job, new WebhookSender.Result("NETWORK", null, "merchant directory unavailable", 0));   // transient: retry later
            return;
        }
        if (sub.isEmpty()) {
            jdbc.update("UPDATE notification_jobs SET status = 'CANCELLED', last_error = 'endpoint deleted or unsubscribed', lock_until = NULL WHERE id = ?", job.id());
            return;
        }
        finishAttempt(job, sender.send(job.url(), job.eventId().toString(), job.type(), job.payload(), sub.get().signingSecret()));
    }

    private void finishAttempt(Job job, WebhookSender.Result r) {
        Instant now = clock.instant();
        int attemptNo = job.attempts() + 1;
        count("apg_webhook_delivery_total", "outcome", r.outcome());
        tx.executeWithoutResult(t -> {
            jdbc.update("INSERT INTO notification_attempts (job_id, attempt_no, outcome, http_status, detail, duration_ms, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    job.id(), attemptNo, r.outcome(), r.httpStatus(), truncate(r.detail(), 300), r.durationMs(), Timestamp.from(now));
            if (r.ok()) {
                jdbc.update("UPDATE notification_jobs SET status = 'DELIVERED', attempts = ?, last_http_status = ?, last_error = NULL, delivered_at = ?, next_attempt_at = NULL, lock_until = NULL WHERE id = ?",
                        attemptNo, r.httpStatus(), Timestamp.from(now), job.id());
            } else if (attemptNo >= props.maxAttempts() || "BLOCKED".equals(r.outcome())) {
                // a blocked target (private address, plain http) will not become acceptable by waiting
                jdbc.update("UPDATE notification_jobs SET status = 'DEAD', attempts = ?, last_http_status = ?, last_error = ?, next_attempt_at = NULL, lock_until = NULL WHERE id = ?",
                        attemptNo, r.httpStatus(), truncate(r.outcome() + (r.detail() == null ? "" : ": " + r.detail()), 300), job.id());
            } else {
                long secs = Math.min(props.maxDelay().toSeconds(), props.baseDelay().toSeconds() * (1L << Math.min(attemptNo - 1, 20)));
                jdbc.update("UPDATE notification_jobs SET status = 'RETRYING', attempts = ?, last_http_status = ?, last_error = ?, next_attempt_at = ?, lock_until = NULL WHERE id = ?",
                        attemptNo, r.httpStatus(), truncate(r.outcome() + (r.detail() == null ? "" : ": " + r.detail()), 300),
                        Timestamp.from(now.plusSeconds(secs)), job.id());
            }
        });
    }

    // ------------------------------------------------------------------ merchant API

    /** Manual resend from the dashboard: starts a fresh delivery cycle for the same event (same event id). */
    public JobView resend(UUID merchantId, UUID jobId) {
        int n = jdbc.update("""
                UPDATE notification_jobs SET status = 'RETRYING', attempts = 0, next_attempt_at = ?, lock_until = NULL, last_error = NULL
                WHERE id = ? AND merchant_id = ? AND status IN ('DEAD', 'DELIVERED', 'CANCELLED')""", Timestamp.from(clock.instant()), jobId, merchantId);
        if (n == 0) {
            get(merchantId, jobId);          // 404 if it is not theirs
            throw new ApiException(HttpStatus.CONFLICT, "DELIVERY_IN_PROGRESS", "This delivery is still being attempted");
        }
        return get(merchantId, jobId);
    }

    public JobView get(UUID merchantId, UUID id) {
        List<JobView> rows = jdbc.query("SELECT * FROM notification_jobs WHERE id = ? AND merchant_id = ?", (rs, i) -> view(rs, true), id, merchantId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "DELIVERY_NOT_FOUND", "Webhook delivery not found");
        return rows.get(0);
    }

    public PageView<JobView> list(UUID merchantId, String status, int page, int size) {
        int s = Math.min(Math.max(size, 1), 100), pg = Math.max(page, 0);
        String where = status == null ? "merchant_id = ?" : "merchant_id = ? AND status = ?";
        Object[] args = status == null ? new Object[]{merchantId} : new Object[]{merchantId, status};
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM notification_jobs WHERE " + where, Long.class, args);
        Object[] pageArgs = java.util.Arrays.copyOf(args, args.length + 2);
        pageArgs[args.length] = s;
        pageArgs[args.length + 1] = pg * s;
        List<JobView> rows = jdbc.query("SELECT * FROM notification_jobs WHERE " + where + " ORDER BY created_at DESC LIMIT ? OFFSET ?",
                (rs, i) -> view(rs, false), pageArgs);
        return new PageView<>(rows, pg, s, total == null ? 0 : total);
    }

    private JobView view(java.sql.ResultSet rs, boolean withHistory) throws java.sql.SQLException {
        UUID id = rs.getObject("id", UUID.class);
        List<AttemptView> history = new ArrayList<>();
        if (withHistory) {
            history = jdbc.query("SELECT * FROM notification_attempts WHERE job_id = ? ORDER BY id", (a, i) -> new AttemptView(a.getInt("attempt_no"),
                    a.getString("outcome"), (Integer) a.getObject("http_status"), a.getString("detail"), a.getLong("duration_ms"),
                    a.getTimestamp("created_at").toInstant()), id);
        }
        return new JobView(id, rs.getObject("event_id", UUID.class), rs.getString("event_type"), rs.getString("endpoint_url"), rs.getString("status"),
                rs.getInt("attempts"), ts(rs.getTimestamp("next_attempt_at")), (Integer) rs.getObject("last_http_status"), rs.getString("last_error"),
                rs.getTimestamp("created_at").toInstant(), ts(rs.getTimestamp("delivered_at")), history);
    }

    private Job load(UUID id) {
        return jdbc.queryForObject("SELECT * FROM notification_jobs WHERE id = ?", (rs, i) -> new Job(rs.getObject("id", UUID.class),
                rs.getObject("event_id", UUID.class), rs.getString("event_type"), rs.getObject("merchant_id", UUID.class), rs.getObject("endpoint_id", UUID.class),
                rs.getString("endpoint_url"), rs.getString("payload"), rs.getInt("attempts")), id);
    }

    private static Instant ts(Timestamp t) { return t == null ? null : t.toInstant(); }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.path(f);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static UUID uuid(JsonNode n, String f) {
        try {
            return UUID.fromString(text(n, f));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(f + " must be a UUID");
        }
    }

    private static String truncate(String s, int max) { return s == null ? null : s.length() <= max ? s : s.substring(0, max); }
}
