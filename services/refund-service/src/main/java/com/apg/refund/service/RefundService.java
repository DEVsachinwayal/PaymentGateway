package com.apg.refund.service;

import com.apg.common.api.ApiException;
import com.apg.refund.client.Peers;
import com.apg.refund.client.Peers.PaymentInfo;
import com.apg.refund.client.Peers.ProviderRefund;
import com.apg.refund.client.Peers.ProviderRefundStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.security.SecureRandom;
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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Refund lifecycle (spec 13): validate -> persist BEFORE the provider call -> call provider -> verify -> update payment.
 * The refundable amount is protected by a per-payment advisory lock plus the sum of every refund that is not FAILED,
 * so concurrent or repeated requests can never refund more than was captured.
 */
@Service
public class RefundService {
    private static final Logger log = LoggerFactory.getLogger(RefundService.class);
    private static final Set<String> REFUNDABLE_PAYMENT = Set.of("SUCCESS", "PARTIALLY_REFUNDED", "REFUND_PENDING");
    private static final Duration DISPATCH_LEASE = Duration.ofSeconds(60);

    public record CreateRefundRequest(@NotNull UUID paymentId, @Positive Long amount, @Size(max = 300) String reason) {}

    public record RefundView(UUID id, UUID paymentId, long amount, String currency, String status, String reason,
                             String failureMessage, Instant createdAt, Instant updatedAt) {}

    public record PageView<T>(List<T> content, int page, int size, long totalElements) {}

    private static final RowMapper<RefundView> VIEW = (rs, i) -> new RefundView(
            rs.getObject("id", UUID.class), rs.getObject("payment_id", UUID.class), rs.getLong("amount"), rs.getString("currency"),
            rs.getString("status"), rs.getString("reason"), rs.getString("failure_message"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());

    private record Row(UUID id, UUID merchantId, UUID paymentId, long amount, String provider, String providerPaymentId,
                       String reference, String providerRequestId, int checkCount, Instant updatedAt) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Peers peers;
    private final RefundProperties props;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public RefundService(JdbcTemplate jdbc, TransactionTemplate tx, Peers peers, RefundProperties props, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.peers = peers;
        this.props = props;
        this.mapper = mapper;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ create (runs inside the idempotency transaction)

    public RefundView create(UUID merchantId, CreateRefundRequest req) {
        jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(?, 0))) l", Integer.class, req.paymentId().toString());

        PaymentInfo p = peers.payment(req.paymentId());
        if (!p.merchantId().equals(merchantId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "Payment not found");   // never reveal other merchants' payments
        }
        if (!REFUNDABLE_PAYMENT.contains(p.status()) || p.providerPaymentId() == null || p.provider() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "PAYMENT_NOT_REFUNDABLE", "Payment is " + p.status() + " and cannot be refunded");
        }
        Long reserved = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM refunds WHERE payment_id = ? AND status <> 'FAILED'", Long.class, p.paymentId());
        long remaining = p.amount() - (reserved == null ? 0 : reserved);
        long amount = req.amount() == null ? remaining : req.amount();
        if (amount <= 0 || amount > remaining) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REFUND_EXCEEDS_PAYMENT",
                    "Refundable amount left on this payment is " + Math.max(remaining, 0) + " (minor units)");
        }

        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        String reference = "RF" + HexFormat.of().formatHex(randomBytes(9));          // 20 alphanumeric chars, the provider-side token
        jdbc.update("""
                INSERT INTO refunds (id, merchant_id, payment_id, amount, currency, status, reason, provider, provider_payment_id,
                                     refund_reference, payment_synced, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'CREATED', ?, ?, ?, ?, FALSE, ?, ?)""",
                id, merchantId, p.paymentId(), amount, p.currency(), truncate(req.reason(), 300), p.provider(), p.providerPaymentId(),
                reference, Timestamp.from(now), Timestamp.from(now));
        outbox("refund.created", id, merchantId, p.paymentId(), amount, p.currency(), "CREATED", now);
        return get(merchantId, id);
    }

    /** After the creating transaction committed: call the provider right away; the worker covers any failure here. */
    public void afterCreate(UUID refundId) {
        try {
            dispatch(refundId);
        } catch (RuntimeException e) {
            log.warn("immediate dispatch of refund {} failed, worker will retry: {}", refundId, e.toString());
        }
    }

    // ------------------------------------------------------------------ provider call

    public void dispatch(UUID id) {
        Instant now = clock.instant();
        int claimed = jdbc.update("""
                UPDATE refunds SET dispatch_lock_until = ? WHERE id = ? AND status = 'CREATED'
                AND (dispatch_lock_until IS NULL OR dispatch_lock_until < ?)""",
                Timestamp.from(now.plus(DISPATCH_LEASE)), id, Timestamp.from(now));
        if (claimed == 0) return;

        Row r = row(id);
        ProviderRefund res = peers.refund(r.provider(), r.providerPaymentId(), r.reference(), r.amount());
        Instant t = clock.instant();
        switch (res.status()) {
            case "ACCEPTED" -> {
                tx.executeWithoutResult(s -> {
                    if (jdbc.update("""
                            UPDATE refunds SET status = 'PROCESSING', provider_request_id = ?, next_check_at = ?, updated_at = ?
                            WHERE id = ? AND status = 'CREATED'""", truncate(res.providerRequestId(), 100),
                            Timestamp.from(t.plus(props.worker().firstCheckDelay())), Timestamp.from(t), id) == 1) {
                        attempt(id, "REQUEST", "ACCEPTED", res.message(), t);
                    }
                });
            }
            case "REJECTED" -> finish(id, "FAILED", "Provider rejected the refund: " + nz(res.message()), "REQUEST", "REJECTED", res.message());
            default -> tx.executeWithoutResult(s -> {
                // We cannot tell whether the provider queued it: never retry blindly, never call it failed. Needs resolution.
                if (jdbc.update("UPDATE refunds SET status = 'VERIFY_REQUIRED', updated_at = ? WHERE id = ? AND status = 'CREATED'",
                        Timestamp.from(t), id) == 1) {
                    attempt(id, "REQUEST", "UNKNOWN", res.message(), t);
                }
            });
        }
        syncPaymentQuietly(r.paymentId());
    }

    // ------------------------------------------------------------------ verification of accepted refunds

    /** @return number of refunds examined */
    public int checkDue() {
        Instant now = clock.instant();
        List<UUID> due = jdbc.query("""
                SELECT id FROM refunds WHERE status = 'PROCESSING' AND next_check_at <= ? ORDER BY next_check_at LIMIT ?""",
                (rs, i) -> rs.getObject("id", UUID.class), Timestamp.from(now), props.worker().batchSize());
        int n = 0;
        for (UUID id : due) {
            // lease so a second instance does not ask the provider about the same refund at the same moment
            if (jdbc.update("UPDATE refunds SET next_check_at = ? WHERE id = ? AND status = 'PROCESSING' AND next_check_at <= ?",
                    Timestamp.from(now.plus(DISPATCH_LEASE)), id, Timestamp.from(now)) == 0) continue;
            n++;
            checkOne(id);
        }
        return n;
    }

    void checkOne(UUID id) {
        Row r = row(id);
        Instant now = clock.instant();
        try {
            ProviderRefundStatus s = peers.refundStatus(r.provider(), r.providerRequestId(), r.reference());
            switch (s.outcome()) {
                case "SUCCESS" -> finish(id, "SUCCESS", null, "STATUS", "SUCCESS", s.rawStatus());
                case "FAILED" -> finish(id, "FAILED", "Provider reported the refund as " + nz(s.rawStatus()), "STATUS", "FAILED", s.rawStatus());
                default -> backoff(r, "STATUS", "PROCESSING", s.rawStatus(), now);
            }
        } catch (RuntimeException e) {
            backoff(r, "STATUS", "ERROR", e.getClass().getSimpleName(), now);
        }
        syncPaymentQuietly(r.paymentId());
    }

    private void backoff(Row r, String kind, String outcome, String detail, Instant now) {
        long secs = Math.min(props.worker().maxBackoff().toSeconds(),
                props.worker().firstCheckDelay().toSeconds() * (1L << Math.min(r.checkCount() + 1, 20)));
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE refunds SET check_count = check_count + 1, next_check_at = ?, updated_at = updated_at WHERE id = ? AND status = 'PROCESSING'",
                    Timestamp.from(now.plusSeconds(secs)), r.id());
            attempt(r.id(), kind, outcome, detail, now);
        });
    }

    /** Settles a refund exactly once (guarded by the current status) and records the event in the outbox. */
    private void finish(UUID id, String status, String message, String kind, String outcome, String detail) {
        tx.executeWithoutResult(s -> {
            Instant now = clock.instant();
            int changed = jdbc.update("""
                    UPDATE refunds SET status = ?, failure_message = ?, payment_synced = FALSE, updated_at = ?
                    WHERE id = ? AND status IN ('CREATED', 'PROCESSING', 'VERIFY_REQUIRED')""",
                    status, truncate(message, 300), Timestamp.from(now), id);
            if (changed == 1) {
                attempt(id, kind, outcome, detail, now);
                Row r = row(id);
                outbox("SUCCESS".equals(status) ? "refund.success" : "refund.failed", id, r.merchantId(), r.paymentId(), r.amount(), null, status, now);
            }
        });
    }

    /** Operator decision for a refund whose provider outcome stayed unknown (VERIFY_REQUIRED). */
    public RefundView resolve(UUID id, String status, String note) {
        if (!Set.of("SUCCESS", "FAILED").contains(status)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RESOLUTION", "status must be SUCCESS or FAILED");
        }
        String current = jdbc.query("SELECT status FROM refunds WHERE id = ?", (rs, i) -> rs.getString(1), id).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REFUND_NOT_FOUND", "Refund not found"));
        if (!"VERIFY_REQUIRED".equals(current)) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_RESOLVABLE", "Only refunds in VERIFY_REQUIRED can be resolved manually");
        }
        finish(id, status, "FAILED".equals(status) ? "Resolved by operator: " + nz(note) : null, "REQUEST", "RESOLVED_" + status, note);
        syncPaymentQuietly(row(id).paymentId());
        UUID merchant = row(id).merchantId();
        return get(merchant, id);
    }

    // ------------------------------------------------------------------ payment state sync

    /** Brings the payment's status in line with its refunds. Idempotent; safe to call any time. */
    public void syncPayment(UUID paymentId) {
        record Snap(UUID id, Instant updatedAt) {}
        List<Snap> unsynced = jdbc.query("SELECT id, updated_at FROM refunds WHERE payment_id = ? AND NOT payment_synced",
                (rs, i) -> new Snap(rs.getObject("id", UUID.class), rs.getTimestamp("updated_at").toInstant()), paymentId);
        if (unsynced.isEmpty()) return;

        PaymentInfo p = peers.payment(paymentId);
        Long success = jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM refunds WHERE payment_id = ? AND status = 'SUCCESS'", Long.class, paymentId);
        Integer open = jdbc.queryForObject("SELECT COUNT(*) FROM refunds WHERE payment_id = ? AND status IN ('CREATED','PROCESSING','VERIFY_REQUIRED')",
                Integer.class, paymentId);
        long ok = success == null ? 0 : success;
        String desired = open != null && open > 0 ? "REFUND_PENDING" : ok >= p.amount() ? "REFUNDED" : ok > 0 ? "PARTIALLY_REFUNDED" : "SUCCESS";

        if (!desired.equals(p.status())) {
            // the payment state machine has no SUCCESS -> REFUNDED shortcut: go through REFUND_PENDING first
            if (!"REFUND_PENDING".equals(p.status()) && !"REFUND_PENDING".equals(desired)) {
                peers.transitionPayment(paymentId, "REFUND_PENDING", "refund settled");
            }
            peers.transitionPayment(paymentId, desired, "refunded " + ok + " of " + p.amount());
        }
        // mark only what we actually looked at: a refund that changed meanwhile keeps its flag and is handled next round
        for (Snap s : unsynced) {
            jdbc.update("UPDATE refunds SET payment_synced = TRUE WHERE id = ? AND updated_at = ?", s.id(), Timestamp.from(s.updatedAt()));
        }
    }

    private void syncPaymentQuietly(UUID paymentId) {
        try {
            syncPayment(paymentId);
        } catch (RuntimeException e) {
            log.warn("payment {} state sync postponed: {}", paymentId, e.toString());
        }
    }

    public int syncPending() {
        List<UUID> payments = jdbc.query("SELECT DISTINCT payment_id FROM refunds WHERE NOT payment_synced LIMIT ?",
                (rs, i) -> rs.getObject(1, UUID.class), props.worker().batchSize());
        payments.forEach(this::syncPaymentQuietly);
        return payments.size();
    }

    public int dispatchPending() {
        List<UUID> ids = jdbc.query("SELECT id FROM refunds WHERE status = 'CREATED' ORDER BY created_at LIMIT ?",
                (rs, i) -> rs.getObject("id", UUID.class), props.worker().batchSize());
        ids.forEach(this::afterCreate);
        return ids.size();
    }

    // ------------------------------------------------------------------ reads

    public RefundView get(UUID merchantId, UUID id) {
        return jdbc.query("SELECT * FROM refunds WHERE id = ? AND merchant_id = ?", VIEW, id, merchantId).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REFUND_NOT_FOUND", "Refund not found"));
    }

    public PageView<RefundView> list(UUID merchantId, UUID paymentId, int page, int size) {
        int s = Math.min(Math.max(size, 1), 100);
        int pg = Math.max(page, 0);
        String where = paymentId == null ? "merchant_id = ?" : "merchant_id = ? AND payment_id = ?";
        Object[] args = paymentId == null ? new Object[]{merchantId} : new Object[]{merchantId, paymentId};
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM refunds WHERE " + where, Long.class, args);
        Object[] pageArgs = java.util.Arrays.copyOf(args, args.length + 2);
        pageArgs[args.length] = s;
        pageArgs[args.length + 1] = pg * s;
        List<RefundView> rows = jdbc.query("SELECT * FROM refunds WHERE " + where + " ORDER BY created_at DESC LIMIT ? OFFSET ?", VIEW, pageArgs);
        return new PageView<>(rows, pg, s, total == null ? 0 : total);
    }

    // ------------------------------------------------------------------ helpers

    private Row row(UUID id) {
        return jdbc.queryForObject("SELECT * FROM refunds WHERE id = ?", (rs, i) -> new Row(
                rs.getObject("id", UUID.class), rs.getObject("merchant_id", UUID.class), rs.getObject("payment_id", UUID.class),
                rs.getLong("amount"), rs.getString("provider"), rs.getString("provider_payment_id"), rs.getString("refund_reference"),
                rs.getString("provider_request_id"), rs.getInt("check_count"), rs.getTimestamp("updated_at").toInstant()), id);
    }

    private void attempt(UUID refundId, String kind, String outcome, String detail, Instant now) {
        jdbc.update("INSERT INTO refund_attempts (refund_id, kind, outcome, detail, created_at) VALUES (?, ?, ?, ?, ?)",
                refundId, kind, outcome, truncate(detail, 300), Timestamp.from(now));
    }

    private void outbox(String type, UUID refundId, UUID merchantId, UUID paymentId, long amount, String currency, String status, Instant now) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("refundId", refundId);
        payload.put("merchantId", merchantId);
        payload.put("paymentId", paymentId);
        payload.put("amount", amount);
        if (currency != null) payload.put("currency", currency);
        payload.put("status", status);
        try {
            jdbc.update("INSERT INTO outbox_events (id, event_type, aggregate_id, payload, created_at) VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID(), type, refundId, mapper.writeValueAsString(payload), Timestamp.from(now));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return b;
    }

    private static String truncate(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max);
    }

    private static String nz(String s) { return s == null ? "no detail" : s; }
}
