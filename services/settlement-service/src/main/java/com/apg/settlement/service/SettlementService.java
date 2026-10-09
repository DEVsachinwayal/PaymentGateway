package com.apg.settlement.service;

import com.apg.common.api.ApiException;
import com.apg.common.web.CorrelationIdFilter;
import com.apg.settlement.client.Peers;
import com.apg.settlement.client.Peers.LookupUnavailable;
import com.apg.settlement.client.Peers.PaymentInfo;
import com.apg.settlement.client.Peers.RefundInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Settlement batches and their reconciliation (spec 15, 16). Rules that matter:
 * - evidence (the imported batch) is stored with its hash and never rewritten; another document for the same batch is refused;
 * - every difference becomes an exception with reason and evidence, nothing is silently ignored;
 * - reconciliation is rerunnable and idempotent; a lookup that could not be answered leaves the item unevaluated;
 * - a batch is RECONCILED (and announced to the ledger) only when every item matches or has been acknowledged by an operator.
 */
@Service
public class SettlementService {
    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);
    private static final Set<String> COVERED_PAYMENT_STATUS = Set.of("SUCCESS", "REFUND_PENDING", "PARTIALLY_REFUNDED", "REFUNDED");
    private static final UUID PLATFORM = new UUID(0, 0);

    public record ItemInput(@NotNull @Pattern(regexp = "^(PAYMENT|REFUND|ADJUSTMENT)$") String type,
                            @Size(max = 100) String providerReference, @Size(max = 100) String providerPaymentId,
                            @PositiveOrZero Long amount, @PositiveOrZero Long fee, @PositiveOrZero Long tax, Long net) {}

    public record ImportRequest(@NotBlank @Pattern(regexp = "^[a-z0-9_]{2,30}$") String provider,
                                @NotBlank @Size(max = 100) String batchRef, @NotNull LocalDate settlementDate,
                                @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
                                @NotNull @PositiveOrZero Long declaredGross, @NotNull @PositiveOrZero Long declaredRefunds,
                                @NotNull @PositiveOrZero Long declaredFees, @NotNull Long declaredNet,
                                @Size(max = 100) String importedBy,
                                @NotEmpty List<@Valid ItemInput> items) {}

    public record SettlementView(UUID id, String provider, String batchRef, LocalDate settlementDate, String currency, long declaredGross,
                                 long declaredRefunds, long declaredFees, long declaredNet, String status, int items, int matched,
                                 int openExceptions, Instant createdAt, Instant reconciledAt, boolean replayed) {}

    public record ItemView(UUID id, int lineNo, String type, String providerReference, long amount, long fee, long tax, long net,
                           String matchStatus, UUID paymentId, UUID refundId, UUID merchantId) {}

    public record ExceptionView(UUID id, UUID settlementId, UUID itemId, String type, String status, String owner, String reason,
                                String evidence, String resolutionNotes, Instant createdAt, Instant resolvedAt) {}

    public record MerchantSettlementView(UUID id, UUID settlementId, UUID merchantId, String provider, LocalDate settlementDate,
                                         String currency, long gross, long refunds, long providerFees, long net, String status,
                                         String payoutReference, Instant paidAt) {}

    public record PageView<T>(List<T> content, int page, int size, long totalElements) {}

    /** What reconciling one item concluded: either a finding (exception) or a clean match with the APG ids found. */
    private record Verdict(String exceptionType, String reason, Map<String, Object> evidence, UUID paymentId, UUID refundId, UUID merchantId) {
        static Verdict match(UUID paymentId, UUID refundId, UUID merchantId) { return new Verdict(null, null, null, paymentId, refundId, merchantId); }
        static Verdict finding(String type, String reason, Map<String, Object> evidence, UUID paymentId, UUID refundId, UUID merchantId) {
            return new Verdict(type, reason, evidence, paymentId, refundId, merchantId);
        }
        boolean matched() { return exceptionType == null; }
    }

    private record ItemRow(UUID id, int lineNo, String type, String reference, String providerPaymentId, long amount, long fee, long tax,
                           long net, String matchStatus) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Peers peers;
    private final SettlementProperties props;
    private final ObjectMapper mapper;
    private final Clock clock;

    private io.micrometer.core.instrument.MeterRegistry meters;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setMeters(io.micrometer.core.instrument.MeterRegistry meters) { this.meters = meters; }

    private void count(String name, String... tags) {
        if (meters != null) meters.counter(name, tags).increment();
    }

    public SettlementService(JdbcTemplate jdbc, TransactionTemplate tx, Peers peers, SettlementProperties props, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.peers = peers;
        this.props = props;
        this.mapper = mapper;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ import

    public SettlementView importBatch(ImportRequest r) {
        if (r.items().size() > props.maxItemsPerBatch()) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "BATCH_TOO_LARGE", "At most " + props.maxItemsPerBatch() + " items per batch");
        }
        // the hash covers the provider's document only: who imported it must not make an identical re-import look different
        String evidence = write(new ImportRequest(r.provider(), r.batchRef(), r.settlementDate(), r.currency(), r.declaredGross(),
                r.declaredRefunds(), r.declaredFees(), r.declaredNet(), null, r.items()));
        String hash = sha256(evidence);

        List<ItemRow> items = new ArrayList<>();
        long gross = 0, refunds = 0, fees = 0, net = 0;
        int line = 0;
        for (ItemInput i : r.items()) {
            line++;
            ItemRow row = normalize(i, line);
            items.add(row);
            net = Math.addExact(net, row.net());
            fees = Math.addExact(fees, row.fee() + row.tax());
            if (row.type().equals("PAYMENT")) gross = Math.addExact(gross, row.amount());
            if (row.type().equals("REFUND")) refunds = Math.addExact(refunds, row.amount());
        }

        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        Boolean created = tx.execute(s -> {
            int inserted = jdbc.update("""
                    INSERT INTO settlements (id, provider, batch_ref, settlement_date, currency, declared_gross, declared_refunds, declared_fees,
                                             declared_net, status, evidence_sha256, evidence, imported_by, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'IMPORTED', ?, ?, ?, ?) ON CONFLICT (provider, batch_ref) DO NOTHING""",
                    id, r.provider(), r.batchRef(), java.sql.Date.valueOf(r.settlementDate()), r.currency(), r.declaredGross(),
                    r.declaredRefunds(), r.declaredFees(), r.declaredNet(), hash, evidence, r.importedBy(), Timestamp.from(now));
            if (inserted == 0) return false;
            for (ItemRow i : items) {
                jdbc.update("""
                        INSERT INTO settlement_items (id, settlement_id, line_no, item_type, provider_reference, provider_payment_id, amount, fee, tax, net, match_status)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')""",
                        UUID.randomUUID(), id, i.lineNo(), i.type(), i.reference(), i.providerPaymentId(), i.amount(), i.fee(), i.tax(), i.net());
            }
            return true;
        });

        if (Boolean.FALSE.equals(created)) {
            var existing = jdbc.queryForMap("SELECT id, evidence_sha256 FROM settlements WHERE provider = ? AND batch_ref = ?", r.provider(), r.batchRef());
            if (!hash.equals(existing.get("evidence_sha256"))) {
                throw new ApiException(HttpStatus.CONFLICT, "BATCH_CONFLICT",
                        "This batch was already imported with different content; evidence is never overwritten");
            }
            return withReplay(view((UUID) existing.get("id")), true);
        }
        reconcile(id);
        return view(id);
    }

    private ItemRow normalize(ItemInput i, int line) {
        long amount = i.amount() == null ? 0 : i.amount();
        long fee = i.fee() == null ? 0 : i.fee();
        long tax = i.tax() == null ? 0 : i.tax();
        switch (i.type()) {
            case "PAYMENT" -> {
                if (isBlank(i.providerReference()) || amount <= 0) throw badItem(line, "a PAYMENT needs providerReference and amount > 0");
                if (fee + tax > amount) throw badItem(line, "fee + tax exceed the payment amount");
                long net = amount - fee - tax;
                if (i.net() != null && i.net() != net) throw badItem(line, "net " + i.net() + " != amount - fee - tax (" + net + ")");
                return new ItemRow(null, line, "PAYMENT", i.providerReference(), i.providerPaymentId(), amount, fee, tax, net, "PENDING");
            }
            case "REFUND" -> {
                if (isBlank(i.providerReference()) || amount <= 0) throw badItem(line, "a REFUND needs providerReference (refund token) and amount > 0");
                if (i.net() != null && i.net() != -amount) throw badItem(line, "net of a REFUND must be -amount");
                return new ItemRow(null, line, "REFUND", i.providerReference(), i.providerPaymentId(), amount, 0, 0, -amount, "PENDING");
            }
            default -> {
                if (i.net() == null || i.net() == 0) throw badItem(line, "an ADJUSTMENT needs a non-zero signed net");
                return new ItemRow(null, line, "ADJUSTMENT", i.providerReference(), i.providerPaymentId(), Math.abs(i.net()), 0, 0, i.net(), "PENDING");
            }
        }
    }

    // ------------------------------------------------------------------ reconciliation (rerunnable)

    /** Evaluates every item that is not yet matched/acknowledged and settles the batch status. */
    public SettlementView reconcile(UUID settlementId) {
        Map<String, Object> s = settlementRow(settlementId);
        String provider = (String) s.get("provider");
        String currency = (String) s.get("currency");
        Instant started = clock.instant();

        List<ItemRow> todo = jdbc.query("""
                SELECT * FROM settlement_items WHERE settlement_id = ? AND match_status IN ('PENDING', 'EXCEPTION') ORDER BY line_no""",
                (rs, i) -> new ItemRow(rs.getObject("id", UUID.class), rs.getInt("line_no"), rs.getString("item_type"), rs.getString("provider_reference"),
                        rs.getString("provider_payment_id"), rs.getLong("amount"), rs.getLong("fee"), rs.getLong("tax"), rs.getLong("net"),
                        rs.getString("match_status")), settlementId);

        // network lookups happen outside any database transaction
        Map<UUID, Verdict> verdicts = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();           // type|reference of items matched earlier in THIS run (they are not stored yet)
        int lookupErrors = 0;
        for (ItemRow it : todo) {
            try {
                Verdict v = evaluate(it, settlementId, provider, currency, seen);
                if (v.matched()) seen.add(it.type() + "|" + it.reference());
                verdicts.put(it.id(), v);
            } catch (LookupUnavailable e) {
                lookupErrors++;
                log.warn("settlement {} line {} could not be evaluated: {}", settlementId, it.lineNo(), e.getMessage());
            }
        }
        final int errors = lookupErrors;
        tx.executeWithoutResult(t -> apply(settlementId, s, verdicts, errors, started));
        return view(settlementId);
    }

    private Verdict evaluate(ItemRow it, UUID settlementId, String provider, String currency, Set<String> seen) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("line", it.lineNo());
        ev.put("providerReference", it.reference());
        ev.put("providerAmount", it.amount());

        if (it.type().equals("ADJUSTMENT")) {
            ev.put("net", it.net());
            return Verdict.finding("ADJUSTMENT_REVIEW", "Provider adjustment of " + it.net() + " needs a human decision", ev, null, null, null);
        }
        // the same provider reference already settled elsewhere (or earlier in this batch) must not be paid twice
        Integer dup = jdbc.queryForObject("""
                SELECT COUNT(*) FROM settlement_items i JOIN settlements st ON st.id = i.settlement_id
                WHERE st.provider = ? AND i.item_type = ? AND i.provider_reference = ? AND i.id <> ?
                  AND i.match_status IN ('MATCHED', 'ACKNOWLEDGED') AND (i.settlement_id <> ? OR i.line_no < ?)""",
                Integer.class, provider, it.type(), it.reference(), it.id(), settlementId, it.lineNo());
        if ((dup != null && dup > 0) || seen.contains(it.type() + "|" + it.reference())) {
            return Verdict.finding("DUPLICATE_ITEM", "Provider reference was already settled in another line or batch", ev, null, null, null);
        }

        if (it.type().equals("PAYMENT")) {
            Optional<PaymentInfo> found = peers.paymentByReference(it.reference());
            if (found.isEmpty()) {
                return Verdict.finding("UNKNOWN_PAYMENT", "Provider settled a transaction APG has no record of", ev, null, null, null);
            }
            PaymentInfo p = found.get();
            ev.put("apgAmount", p.amount());
            ev.put("apgStatus", p.paymentStatus());
            ev.put("paymentId", p.paymentId());
            if (!provider.equals(p.provider()) || (it.providerPaymentId() != null && !it.providerPaymentId().equals(p.providerPaymentId()))) {
                ev.put("apgProvider", p.provider());
                ev.put("apgProviderPaymentId", p.providerPaymentId());
                return Verdict.finding("REFERENCE_MISMATCH", "Provider ids do not agree with APG's record", ev, p.paymentId(), null, p.merchantId());
            }
            if (!COVERED_PAYMENT_STATUS.contains(p.paymentStatus())) {
                return Verdict.finding("STATUS_MISMATCH", "Provider settled money for a payment APG does not consider captured (" + p.paymentStatus() + ")",
                        ev, p.paymentId(), null, p.merchantId());
            }
            if (p.amount() != it.amount() || !p.currency().equals(currency)) {
                ev.put("apgCurrency", p.currency());
                return Verdict.finding("AMOUNT_MISMATCH", "Provider amount " + it.amount() + " differs from APG amount " + p.amount(),
                        ev, p.paymentId(), null, p.merchantId());
            }
            return Verdict.match(p.paymentId(), null, p.merchantId());
        }

        Optional<RefundInfo> found = peers.refundByReference(it.reference());
        if (found.isEmpty()) {
            return Verdict.finding("UNKNOWN_REFUND", "Provider settled a refund APG has no record of", ev, null, null, null);
        }
        RefundInfo f = found.get();
        ev.put("apgAmount", f.amount());
        ev.put("apgStatus", f.status());
        ev.put("refundId", f.refundId());
        if (!f.status().equals("SUCCESS")) {
            return Verdict.finding("STATUS_MISMATCH", "Provider settled a refund APG records as " + f.status(), ev, f.paymentId(), f.refundId(), f.merchantId());
        }
        if (f.amount() != it.amount() || !f.currency().equals(currency)) {
            return Verdict.finding("AMOUNT_MISMATCH", "Provider refund amount " + it.amount() + " differs from APG amount " + f.amount(),
                    ev, f.paymentId(), f.refundId(), f.merchantId());
        }
        return Verdict.match(f.paymentId(), f.refundId(), f.merchantId());
    }

    private void apply(UUID settlementId, Map<String, Object> s, Map<UUID, Verdict> verdicts, int lookupErrors, Instant started) {
        // serialise concurrent reruns of the same batch while we write
        jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(?, 0))) l", Integer.class, settlementId.toString());
        Instant now = clock.instant();

        for (var e : verdicts.entrySet()) {
            UUID itemId = e.getKey();
            Verdict v = e.getValue();
            if (v.matched()) {
                jdbc.update("UPDATE settlement_items SET match_status = 'MATCHED', payment_id = ?, refund_id = ?, merchant_id = ? WHERE id = ?",
                        v.paymentId(), v.refundId(), v.merchantId(), itemId);
                // the finding went away (e.g. APG caught up): close it, keep it as history
                jdbc.update("""
                        UPDATE reconciliation_exceptions SET status = 'RESOLVED', resolution_notes = 'Auto-resolved: item matches on re-run',
                               resolved_at = ?, updated_at = ? WHERE item_id = ? AND status IN ('OPEN', 'INVESTIGATING')""",
                        Timestamp.from(now), Timestamp.from(now), itemId);
                continue;
            }
            // an operator may already have decided about exactly this finding: respect it, do not reopen
            var prior = jdbc.queryForList("SELECT status FROM reconciliation_exceptions WHERE settlement_id = ? AND item_id = ? AND exc_type = ?",
                    String.class, settlementId, itemId, v.exceptionType());
            boolean acknowledged = !prior.isEmpty() && Set.of("RESOLVED", "WRITTEN_OFF").contains(prior.get(0));
            if (prior.isEmpty()) {
                jdbc.update("""
                        INSERT INTO reconciliation_exceptions (id, settlement_id, item_id, exc_type, status, reason, evidence, created_at, updated_at)
                        VALUES (?, ?, ?, ?, 'OPEN', ?, ?, ?, ?) ON CONFLICT DO NOTHING""",
                        UUID.randomUUID(), settlementId, itemId, v.exceptionType(), truncate(v.reason(), 300), write(v.evidence()),
                        Timestamp.from(now), Timestamp.from(now));
                count("apg_reconciliation_exceptions_total", "type", v.exceptionType());
                outbox("reconciliation.exception", settlementId, Map.of("settlementId", settlementId, "itemId", itemId, "type", v.exceptionType()), now);
            }
            boolean hasApgRecord = v.paymentId() != null || v.refundId() != null;
            jdbc.update("UPDATE settlement_items SET match_status = ?, payment_id = ?, refund_id = ?, merchant_id = ? WHERE id = ?",
                    acknowledged ? "ACKNOWLEDGED" : "EXCEPTION", v.paymentId(), v.refundId(), v.merchantId(), itemId);
            if (acknowledged && !hasApgRecord) {
                // acknowledged without an APG record (unknown/adjustment): it is simply not attributable to any merchant
                jdbc.update("UPDATE settlement_items SET merchant_id = NULL WHERE id = ?", itemId);
            }
        }

        // batch-level check: provider's own totals must add up to its own items
        Map<String, Long> sums = jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN item_type = 'PAYMENT' THEN amount END), 0) AS gross,
                       COALESCE(SUM(CASE WHEN item_type = 'REFUND' THEN amount END), 0) AS refunds,
                       COALESCE(SUM(fee + tax), 0) AS fees, COALESCE(SUM(net), 0) AS net
                FROM settlement_items WHERE settlement_id = ?""", (rs, i) -> Map.of("gross", rs.getLong("gross"), "refunds", rs.getLong("refunds"),
                "fees", rs.getLong("fees"), "net", rs.getLong("net")), settlementId);
        boolean totalsOk = sums.get("gross") == (long) (Long) s.get("declared_gross") && sums.get("refunds") == (long) (Long) s.get("declared_refunds")
                && sums.get("fees") == (long) (Long) s.get("declared_fees") && sums.get("net") == (long) (Long) s.get("declared_net");
        if (!totalsOk) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("declared", Map.of("gross", s.get("declared_gross"), "refunds", s.get("declared_refunds"), "fees", s.get("declared_fees"), "net", s.get("declared_net")));
            ev.put("itemsSum", sums);
            int ins = jdbc.update("""
                    INSERT INTO reconciliation_exceptions (id, settlement_id, item_id, exc_type, status, reason, evidence, created_at, updated_at)
                    VALUES (?, ?, NULL, 'TOTAL_MISMATCH', 'OPEN', 'Provider totals do not equal the sum of its own items', ?, ?, ?) ON CONFLICT DO NOTHING""",
                    UUID.randomUUID(), settlementId, write(ev), Timestamp.from(now), Timestamp.from(now));
            if (ins == 1) count("apg_reconciliation_exceptions_total", "type", "TOTAL_MISMATCH");
            if (ins == 1) outbox("reconciliation.exception", settlementId, Map.of("settlementId", settlementId, "type", "TOTAL_MISMATCH"), now);
        } else {
            jdbc.update("""
                    UPDATE reconciliation_exceptions SET status = 'RESOLVED', resolution_notes = 'Auto-resolved: totals agree on re-run', resolved_at = ?, updated_at = ?
                    WHERE settlement_id = ? AND item_id IS NULL AND exc_type = 'TOTAL_MISMATCH' AND status IN ('OPEN', 'INVESTIGATING')""",
                    Timestamp.from(now), Timestamp.from(now), settlementId);
        }

        Integer unresolvedItems = jdbc.queryForObject("SELECT COUNT(*) FROM settlement_items WHERE settlement_id = ? AND match_status IN ('PENDING', 'EXCEPTION')",
                Integer.class, settlementId);
        Integer open = jdbc.queryForObject("SELECT COUNT(*) FROM reconciliation_exceptions WHERE settlement_id = ? AND status IN ('OPEN', 'INVESTIGATING')",
                Integer.class, settlementId);
        Integer matched = jdbc.queryForObject("SELECT COUNT(*) FROM settlement_items WHERE settlement_id = ? AND match_status = 'MATCHED'", Integer.class, settlementId);
        boolean clean = unresolvedItems == 0 && open == 0 && lookupErrors == 0;
        String status = clean ? "RECONCILED" : (open > 0 || unresolvedItems > 0 && lookupErrors == 0 ? "HAS_EXCEPTIONS" : "IMPORTED");
        jdbc.update("UPDATE settlements SET status = ?, reconciled_at = CASE WHEN ? THEN COALESCE(reconciled_at, ?) ELSE reconciled_at END WHERE id = ?",
                status, clean, Timestamp.from(now), settlementId);
        jdbc.update("""
                INSERT INTO reconciliation_runs (id, settlement_id, started_at, finished_at, matched, open_exceptions, lookup_errors)
                VALUES (?, ?, ?, ?, ?, ?, ?)""", UUID.randomUUID(), settlementId, Timestamp.from(started), Timestamp.from(now), matched, open, lookupErrors);

        if (clean) {
            computeMerchantSettlements(settlementId, s, now);
            if (jdbc.update("UPDATE settlements SET event_emitted = TRUE WHERE id = ? AND NOT event_emitted", settlementId) == 1) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("settlementId", settlementId);
                p.put("provider", s.get("provider"));
                p.put("currency", s.get("currency"));
                p.put("settlementDate", s.get("settlement_date").toString());
                p.put("gross", sums.get("gross"));
                p.put("refunds", sums.get("refunds"));
                p.put("providerFees", sums.get("fees"));
                p.put("amount", sums.get("net"));              // what the provider actually pays out to APG
                outbox("settlement.created", settlementId, p, now);
            }
        }
    }

    private void computeMerchantSettlements(UUID settlementId, Map<String, Object> s, Instant now) {
        jdbc.update("""
                INSERT INTO merchant_settlements (id, settlement_id, merchant_id, currency, gross, refunds, provider_fees, net, status, created_at)
                SELECT gen_random_uuid(), settlement_id, merchant_id, ?,
                       COALESCE(SUM(CASE WHEN item_type = 'PAYMENT' THEN amount END), 0),
                       COALESCE(SUM(CASE WHEN item_type = 'REFUND' THEN amount END), 0),
                       COALESCE(SUM(fee + tax), 0), COALESCE(SUM(net), 0), 'CALCULATED', ?
                FROM settlement_items
                WHERE settlement_id = ? AND merchant_id IS NOT NULL AND item_type IN ('PAYMENT', 'REFUND') AND match_status IN ('MATCHED', 'ACKNOWLEDGED')
                GROUP BY settlement_id, merchant_id
                ON CONFLICT (settlement_id, merchant_id) DO NOTHING""", s.get("currency"), Timestamp.from(now), settlementId);
    }

    /** Batches that were left with unevaluated items (a dependency was down) or open work are re-evaluated by the worker. */
    public int rerunUnfinished() {
        List<UUID> ids = jdbc.query("SELECT id FROM settlements WHERE status <> 'RECONCILED' ORDER BY created_at LIMIT 20", (rs, i) -> rs.getObject(1, UUID.class));
        ids.forEach(id -> {
            try {
                reconcile(id);
            } catch (RuntimeException e) {
                log.warn("rerun of settlement {} failed: {}", id, e.toString());
            }
        });
        return ids.size();
    }

    // ------------------------------------------------------------------ exceptions workflow

    public ExceptionView updateException(UUID id, String status, String owner, String notes) {
        if (!Set.of("INVESTIGATING", "RESOLVED", "WRITTEN_OFF").contains(status)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_STATUS", "status must be INVESTIGATING, RESOLVED or WRITTEN_OFF");
        }
        if (!status.equals("INVESTIGATING") && isBlank(notes)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NOTES_REQUIRED", "Resolution notes are required to close an exception");
        }
        if (status.equals("INVESTIGATING") && isBlank(owner)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "OWNER_REQUIRED", "An owner is required to investigate an exception");
        }
        UUID settlementId = tx.execute(t -> {
            var rows = jdbc.queryForList("SELECT settlement_id, item_id, status FROM reconciliation_exceptions WHERE id = ? FOR UPDATE", id);
            if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "EXCEPTION_NOT_FOUND", "Exception not found");
            String current = (String) rows.get(0).get("status");
            if (Set.of("RESOLVED", "WRITTEN_OFF").contains(current)) {
                throw new ApiException(HttpStatus.CONFLICT, "ALREADY_CLOSED", "Exception is already " + current);
            }
            Instant now = clock.instant();
            boolean closing = !status.equals("INVESTIGATING");
            jdbc.update("""
                    UPDATE reconciliation_exceptions SET status = ?, owner = COALESCE(?, owner), resolution_notes = COALESCE(?, resolution_notes),
                           resolved_at = CASE WHEN ? THEN ? ELSE resolved_at END, updated_at = ? WHERE id = ?""",
                    status, owner, truncate(notes, 500), closing, Timestamp.from(now), Timestamp.from(now), id);
            return (UUID) rows.get(0).get("settlement_id");
        });
        reconcile(settlementId);       // an acknowledged finding may now complete the batch
        return exception(id);
    }

    // ------------------------------------------------------------------ payouts

    /** Marks the merchant's share as paid; requires the bank/payout reference as evidence (never marked paid blindly). */
    public MerchantSettlementView markPaid(UUID merchantSettlementId, String payoutReference) {
        if (isBlank(payoutReference)) throw new ApiException(HttpStatus.BAD_REQUEST, "PAYOUT_REFERENCE_REQUIRED", "A payout reference is required as evidence");
        int n = jdbc.update("UPDATE merchant_settlements SET status = 'PAID', payout_reference = ?, paid_at = ? WHERE id = ? AND status = 'CALCULATED'",
                truncate(payoutReference, 100), Timestamp.from(clock.instant()), merchantSettlementId);
        if (n == 0) {
            if (merchantSettlement(merchantSettlementId, null).isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Merchant settlement not found");
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_PAID", "Merchant settlement is already paid");
        }
        return merchantSettlement(merchantSettlementId, null).get();
    }

    // ------------------------------------------------------------------ reads

    public SettlementView view(UUID id) {
        Map<String, Object> s = settlementRow(id);
        Integer items = jdbc.queryForObject("SELECT COUNT(*) FROM settlement_items WHERE settlement_id = ?", Integer.class, id);
        Integer matched = jdbc.queryForObject("SELECT COUNT(*) FROM settlement_items WHERE settlement_id = ? AND match_status IN ('MATCHED', 'ACKNOWLEDGED')", Integer.class, id);
        Integer open = jdbc.queryForObject("SELECT COUNT(*) FROM reconciliation_exceptions WHERE settlement_id = ? AND status IN ('OPEN', 'INVESTIGATING')", Integer.class, id);
        return new SettlementView(id, (String) s.get("provider"), (String) s.get("batch_ref"), ((java.sql.Date) s.get("settlement_date")).toLocalDate(),
                (String) s.get("currency"), (Long) s.get("declared_gross"), (Long) s.get("declared_refunds"), (Long) s.get("declared_fees"),
                (Long) s.get("declared_net"), (String) s.get("status"), items == null ? 0 : items, matched == null ? 0 : matched,
                open == null ? 0 : open, ((Timestamp) s.get("created_at")).toInstant(),
                s.get("reconciled_at") == null ? null : ((Timestamp) s.get("reconciled_at")).toInstant(), false);
    }

    private static SettlementView withReplay(SettlementView v, boolean replayed) {
        return new SettlementView(v.id(), v.provider(), v.batchRef(), v.settlementDate(), v.currency(), v.declaredGross(), v.declaredRefunds(),
                v.declaredFees(), v.declaredNet(), v.status(), v.items(), v.matched(), v.openExceptions(), v.createdAt(), v.reconciledAt(), replayed);
    }

    public PageView<SettlementView> list(int page, int size) {
        int s = Math.min(Math.max(size, 1), 100), pg = Math.max(page, 0);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM settlements", Long.class);
        List<UUID> ids = jdbc.query("SELECT id FROM settlements ORDER BY settlement_date DESC, created_at DESC LIMIT ? OFFSET ?",
                (rs, i) -> rs.getObject(1, UUID.class), s, pg * s);
        return new PageView<>(ids.stream().map(this::view).toList(), pg, s, total == null ? 0 : total);
    }

    public List<ItemView> items(UUID settlementId) {
        settlementRow(settlementId);
        return jdbc.query("SELECT * FROM settlement_items WHERE settlement_id = ? ORDER BY line_no", (rs, i) -> new ItemView(rs.getObject("id", UUID.class),
                rs.getInt("line_no"), rs.getString("item_type"), rs.getString("provider_reference"), rs.getLong("amount"), rs.getLong("fee"),
                rs.getLong("tax"), rs.getLong("net"), rs.getString("match_status"), rs.getObject("payment_id", UUID.class),
                rs.getObject("refund_id", UUID.class), rs.getObject("merchant_id", UUID.class)), settlementId);
    }

    public List<ExceptionView> exceptions(String status, UUID settlementId) {
        StringBuilder sql = new StringBuilder("SELECT * FROM reconciliation_exceptions WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (status != null) { sql.append(" AND status = ?"); args.add(status); }
        if (settlementId != null) { sql.append(" AND settlement_id = ?"); args.add(settlementId); }
        sql.append(" ORDER BY created_at DESC LIMIT 200");
        return jdbc.query(sql.toString(), (rs, i) -> exceptionOf(rs), args.toArray());
    }

    private ExceptionView exception(UUID id) {
        return jdbc.query("SELECT * FROM reconciliation_exceptions WHERE id = ?", (rs, i) -> exceptionOf(rs), id).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EXCEPTION_NOT_FOUND", "Exception not found"));
    }

    private static ExceptionView exceptionOf(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ExceptionView(rs.getObject("id", UUID.class), rs.getObject("settlement_id", UUID.class), rs.getObject("item_id", UUID.class),
                rs.getString("exc_type"), rs.getString("status"), rs.getString("owner"), rs.getString("reason"), rs.getString("evidence"),
                rs.getString("resolution_notes"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("resolved_at") == null ? null : rs.getTimestamp("resolved_at").toInstant());
    }

    public PageView<MerchantSettlementView> merchantSettlements(UUID merchantId, int page, int size) {
        int s = Math.min(Math.max(size, 1), 100), pg = Math.max(page, 0);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM merchant_settlements WHERE merchant_id = ?", Long.class, merchantId);
        List<MerchantSettlementView> rows = jdbc.query(MS_SQL + " WHERE m.merchant_id = ? ORDER BY st.settlement_date DESC, m.created_at DESC LIMIT ? OFFSET ?",
                (rs, i) -> msOf(rs), merchantId, s, pg * s);
        return new PageView<>(rows, pg, s, total == null ? 0 : total);
    }

    public Optional<MerchantSettlementView> merchantSettlement(UUID id, UUID merchantId) {
        String sql = MS_SQL + " WHERE m.id = ?" + (merchantId == null ? "" : " AND m.merchant_id = ?");
        Object[] args = merchantId == null ? new Object[]{id} : new Object[]{id, merchantId};
        return jdbc.query(sql, (rs, i) -> msOf(rs), args).stream().findFirst();
    }

    private static final String MS_SQL = """
            SELECT m.*, st.provider, st.settlement_date FROM merchant_settlements m JOIN settlements st ON st.id = m.settlement_id""";

    private static MerchantSettlementView msOf(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new MerchantSettlementView(rs.getObject("id", UUID.class), rs.getObject("settlement_id", UUID.class), rs.getObject("merchant_id", UUID.class),
                rs.getString("provider"), rs.getDate("settlement_date").toLocalDate(), rs.getString("currency"), rs.getLong("gross"), rs.getLong("refunds"),
                rs.getLong("provider_fees"), rs.getLong("net"), rs.getString("status"), rs.getString("payout_reference"),
                rs.getTimestamp("paid_at") == null ? null : rs.getTimestamp("paid_at").toInstant());
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> settlementRow(UUID id) {
        var rows = jdbc.queryForList("SELECT * FROM settlements WHERE id = ?", id);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "SETTLEMENT_NOT_FOUND", "Settlement not found");
        return rows.get(0);
    }

    private void outbox(String type, UUID aggregateId, Map<String, ?> payload, Instant now) {
        UUID eventId = UUID.randomUUID();
        String correlation = MDC.get(CorrelationIdFilter.MDC_KEY);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", type);
        envelope.put("version", 1);
        envelope.put("occurredAt", now.toString());
        envelope.put("correlationId", correlation);
        envelope.put("aggregateId", aggregateId);
        envelope.put("merchantId", PLATFORM);          // settlement events are platform level
        envelope.put("payload", payload);
        jdbc.update("INSERT INTO outbox_events (id, event_type, aggregate_id, merchant_id, correlation_id, payload, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                eventId, type, aggregateId, PLATFORM, correlation, write(envelope), Timestamp.from(now));
    }

    private String write(Object o) {
        try {
            return mapper.writeValueAsString(o);
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

    private static ApiException badItem(int line, String m) { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ITEM", "Line " + line + ": " + m); }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static String truncate(String s, int max) { return s == null ? null : s.length() <= max ? s : s.substring(0, max); }
}
