package com.apg.ledger.service;

import com.apg.common.api.ApiException;
import com.apg.common.web.CorrelationIdFilter;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only writer of the ledger. Every posting is one database transaction holding the header and all entries; the
 * database refuses it at commit if debits and credits differ (see V1 trigger), so balance does not depend on this code alone.
 */
@Service
public class LedgerService {
    public enum Side { DEBIT, CREDIT }

    /** One line of a posting. {@code merchantId == null} means a platform account. */
    public record Line(String accountCode, UUID merchantId, Side side, long amount) {}

    public record Posting(UUID eventId, String txType, UUID referenceId, UUID merchantId, String currency, String description,
                          Instant occurredAt, List<Line> lines) {}

    /** @param replayed true when an identical posting already existed and nothing was written */
    public record Result(UUID transactionId, boolean replayed) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public LedgerService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public Result post(Posting p) {
        validate(p);
        UUID txId = UUID.randomUUID();
        Instant now = clock.instant();
        int inserted = jdbc.update("""
                INSERT INTO ledger_transactions (id, event_id, tx_type, reference_id, merchant_id, currency, description, correlation_id, occurred_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING""",
                txId, p.eventId(), p.txType(), p.referenceId(), p.merchantId(), p.currency(), truncate(p.description(), 300),
                MDC.get(CorrelationIdFilter.MDC_KEY), Timestamp.from(p.occurredAt() == null ? now : p.occurredAt()), Timestamp.from(now));
        if (inserted == 0) {
            // same event or same (type, reference) was posted before: idempotent replay
            UUID existing = jdbc.query("""
                    SELECT id FROM ledger_transactions WHERE (event_id = ? AND ? IS NOT NULL) OR (tx_type = ? AND reference_id = ? AND reverses_tx_id IS NULL)
                    LIMIT 1""", (rs, i) -> rs.getObject(1, UUID.class), p.eventId(), p.eventId(), p.txType(), p.referenceId())
                    .stream().findFirst().orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "LEDGER_CONFLICT", "Conflicting ledger posting"));
            return new Result(existing, true);
        }
        for (Line l : p.lines()) {
            UUID account = account(l.accountCode(), l.merchantId(), p.currency(), now);
            jdbc.update("INSERT INTO ledger_entries (transaction_id, account_id, side, amount, currency, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    txId, account, l.side().name(), l.amount(), p.currency(), Timestamp.from(now));
        }
        return new Result(txId, false);
    }

    /** Corrections never edit history: the reversal swaps every side of the original, exactly once. */
    @Transactional
    public Result reverse(UUID originalId, String reason) {
        var header = jdbc.queryForList("SELECT * FROM ledger_transactions WHERE id = ?", originalId);
        if (header.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND", "Ledger transaction not found");
        Map<String, Object> h = header.get(0);
        if (h.get("reverses_tx_id") != null) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_A_REVERSAL", "A reversal cannot be reversed; post a new transaction");
        }
        Integer already = jdbc.queryForObject("SELECT COUNT(*) FROM ledger_transactions WHERE reverses_tx_id = ?", Integer.class, originalId);
        if (already != null && already > 0) throw new ApiException(HttpStatus.CONFLICT, "ALREADY_REVERSED", "Transaction was already reversed");

        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        jdbc.update("""
                INSERT INTO ledger_transactions (id, tx_type, reference_id, merchant_id, currency, description, reverses_tx_id, correlation_id, occurred_at, created_at)
                VALUES (?, 'REVERSAL', ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, h.get("reference_id"), h.get("merchant_id"), h.get("currency"), truncate("Reversal: " + reason, 300), originalId,
                MDC.get(CorrelationIdFilter.MDC_KEY), Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                INSERT INTO ledger_entries (transaction_id, account_id, side, amount, currency, created_at)
                SELECT ?, account_id, CASE side WHEN 'DEBIT' THEN 'CREDIT' ELSE 'DEBIT' END, amount, currency, ?
                FROM ledger_entries WHERE transaction_id = ? ORDER BY id""", id, Timestamp.from(now), originalId);
        return new Result(id, false);
    }

    private void validate(Posting p) {
        if (p.lines() == null || p.lines().size() < 2) throw bad("a posting needs at least two lines");
        if (p.currency() == null || !p.currency().matches("^[A-Z]{3}$")) throw bad("invalid currency");
        long debit = 0, credit = 0;
        for (Line l : p.lines()) {
            if (l.amount() <= 0) throw bad("line amounts must be positive");
            if (l.side() == Side.DEBIT) debit = Math.addExact(debit, l.amount()); else credit = Math.addExact(credit, l.amount());
        }
        if (debit != credit) throw bad("debits (" + debit + ") must equal credits (" + credit + ")");
    }

    private static ApiException bad(String m) { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_POSTING", m); }

    private UUID account(String code, UUID merchantId, String currency, Instant now) {
        String ownerType = merchantId == null ? "PLATFORM" : "MERCHANT";
        List<UUID> found = jdbc.query("""
                SELECT id FROM ledger_accounts WHERE code = ? AND currency = ?
                AND COALESCE(owner_id, '00000000-0000-0000-0000-000000000000'::uuid) = COALESCE(?, '00000000-0000-0000-0000-000000000000'::uuid)""",
                (rs, i) -> rs.getObject(1, UUID.class), code, currency, merchantId);
        if (!found.isEmpty()) return found.get(0);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ledger_accounts (id, code, owner_type, owner_id, currency, normal_side, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING""", id, code, ownerType, merchantId, currency, normalSideOf(code), Timestamp.from(now));
        return jdbc.query("""
                SELECT id FROM ledger_accounts WHERE code = ? AND currency = ?
                AND COALESCE(owner_id, '00000000-0000-0000-0000-000000000000'::uuid) = COALESCE(?, '00000000-0000-0000-0000-000000000000'::uuid)""",
                (rs, i) -> rs.getObject(1, UUID.class), code, currency, merchantId).get(0);
    }

    /** Asset accounts grow with debits; liability and revenue accounts grow with credits. */
    static String normalSideOf(String code) {
        return code.startsWith("PROVIDER_RECEIVABLE") || code.startsWith("APG_BANK") || code.startsWith("PROVIDER_FEE_EXPENSE") ? "DEBIT" : "CREDIT";
    }

    private static String truncate(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max);
    }
}
