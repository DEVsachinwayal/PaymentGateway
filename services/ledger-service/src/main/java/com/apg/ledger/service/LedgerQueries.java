package com.apg.ledger.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Read side: balances are always derived from entries, never stored. */
@Service
public class LedgerQueries {
    public record Balance(String currency, long payable, long captured, long fees, long refunded, long adjustments) {}

    public record EntryView(String account, String side, long amount) {}

    public record TransactionView(UUID id, String type, UUID referenceId, String currency, String description, UUID reversesId,
                                  Instant occurredAt, List<EntryView> entries) {}

    public record PageView<T>(List<T> content, int page, int size, long totalElements) {}

    public record TrialRow(String account, String ownerType, UUID ownerId, String currency, long debit, long credit) {}

    public record TrialBalance(List<TrialRow> accounts, Map<String, long[]> totalsByCurrency, boolean balanced) {}

    private final JdbcTemplate jdbc;

    public LedgerQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** What APG owes the merchant (payable) and how it came about. All amounts minor units. */
    public List<Balance> balances(UUID merchantId) {
        return jdbc.query("""
                SELECT a.currency,
                  COALESCE(SUM(CASE WHEN a.code = 'MERCHANT_PAYABLE' THEN CASE e.side WHEN 'CREDIT' THEN e.amount ELSE -e.amount END END), 0) AS payable,
                  COALESCE(SUM(CASE WHEN t.tx_type = 'CAPTURE' AND a.code LIKE 'PROVIDER_RECEIVABLE%' AND e.side = 'DEBIT' THEN e.amount END), 0) AS captured,
                  COALESCE(SUM(CASE WHEN t.tx_type = 'CAPTURE' AND a.code = 'APG_FEE_REVENUE' AND e.side = 'CREDIT' THEN e.amount END), 0) AS fees,
                  COALESCE(SUM(CASE WHEN t.tx_type = 'REFUND' AND a.code = 'MERCHANT_PAYABLE' AND e.side = 'DEBIT' THEN e.amount END), 0) AS refunded
                FROM ledger_entries e
                JOIN ledger_transactions t ON t.id = e.transaction_id
                JOIN ledger_accounts a ON a.id = e.account_id
                WHERE t.merchant_id = ?
                GROUP BY a.currency ORDER BY a.currency""",
                (rs, i) -> {
                    long payable = rs.getLong("payable"), captured = rs.getLong("captured"), fees = rs.getLong("fees"), refunded = rs.getLong("refunded");
                    return new Balance(rs.getString("currency"), payable, captured, fees, refunded, payable - (captured - fees - refunded));
                }, merchantId);
    }

    public PageView<TransactionView> transactions(UUID merchantId, int page, int size) {
        int s = Math.min(Math.max(size, 1), 100);
        int pg = Math.max(page, 0);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM ledger_transactions WHERE merchant_id = ?", Long.class, merchantId);
        record Head(UUID id, String type, UUID ref, String currency, String desc, UUID reverses, Instant at) {}
        List<Head> heads = jdbc.query("""
                SELECT id, tx_type, reference_id, currency, description, reverses_tx_id, occurred_at FROM ledger_transactions
                WHERE merchant_id = ? ORDER BY occurred_at DESC, created_at DESC, id LIMIT ? OFFSET ?""",
                (rs, i) -> new Head(rs.getObject("id", UUID.class), rs.getString("tx_type"), rs.getObject("reference_id", UUID.class),
                        rs.getString("currency"), rs.getString("description"), rs.getObject("reverses_tx_id", UUID.class),
                        rs.getTimestamp("occurred_at").toInstant()), merchantId, s, pg * s);
        List<TransactionView> out = new ArrayList<>();
        for (Head h : heads) {
            List<EntryView> entries = jdbc.query("""
                    SELECT a.code, e.side, e.amount FROM ledger_entries e JOIN ledger_accounts a ON a.id = e.account_id
                    WHERE e.transaction_id = ? ORDER BY e.id""", (rs, i) -> new EntryView(rs.getString(1), rs.getString(2), rs.getLong(3)), h.id());
            out.add(new TransactionView(h.id(), h.type(), h.ref(), h.currency(), h.desc(), h.reverses(), h.at(), entries));
        }
        return new PageView<>(out, pg, s, total == null ? 0 : total);
    }

    /** Operations view: every account's debits and credits; the books balance when total debits equal total credits per currency. */
    public TrialBalance trialBalance() {
        List<TrialRow> rows = jdbc.query("""
                SELECT a.code, a.owner_type, a.owner_id, a.currency,
                       COALESCE(SUM(e.amount) FILTER (WHERE e.side = 'DEBIT'), 0) AS debit,
                       COALESCE(SUM(e.amount) FILTER (WHERE e.side = 'CREDIT'), 0) AS credit
                FROM ledger_accounts a LEFT JOIN ledger_entries e ON e.account_id = a.id
                GROUP BY a.id ORDER BY a.currency, a.code, a.owner_id""",
                (rs, i) -> new TrialRow(rs.getString("code"), rs.getString("owner_type"), rs.getObject("owner_id", UUID.class),
                        rs.getString("currency"), rs.getLong("debit"), rs.getLong("credit")));
        Map<String, long[]> totals = new LinkedHashMap<>();
        for (TrialRow r : rows) {
            long[] t = totals.computeIfAbsent(r.currency(), k -> new long[2]);
            t[0] += r.debit();
            t[1] += r.credit();
        }
        boolean balanced = totals.values().stream().allMatch(t -> t[0] == t[1]);
        return new TrialBalance(rows, totals, balanced);
    }
}
