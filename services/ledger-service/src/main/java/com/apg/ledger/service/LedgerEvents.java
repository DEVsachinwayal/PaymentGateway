package com.apg.ledger.service;

import com.apg.common.api.ApiException;
import com.apg.ledger.service.LedgerService.Line;
import com.apg.ledger.service.LedgerService.Posting;
import com.apg.ledger.service.LedgerService.Result;
import com.apg.ledger.service.LedgerService.Side;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Turns domain events (spec 17 envelope) into postings. The same entry point serves the interim HTTP delivery and the
 * Kafka consumer of M12. Handling is idempotent: a redelivered event produces no second posting.
 *
 * <pre>
 * payment.success : DR PROVIDER_RECEIVABLE_{provider}  gross
 *                   CR MERCHANT_PAYABLE                gross - fee
 *                   CR APG_FEE_REVENUE                 fee           (only when fee > 0)
 * refund.success  : DR MERCHANT_PAYABLE                amount
 *                   CR PROVIDER_RECEIVABLE_{provider}  amount       (the fee on the original payment is not returned)
 * </pre>
 */
@Service
public class LedgerEvents {
    public enum Outcome { POSTED, REPLAYED, IGNORED }

    public record Handled(Outcome outcome, UUID transactionId) {}

    private final LedgerService ledger;
    private final LedgerProperties props;

    public LedgerEvents(LedgerService ledger, LedgerProperties props) {
        this.ledger = ledger;
        this.props = props;
    }

    public Handled handle(JsonNode event) {
        String type = text(event, "eventType");
        if (type == null) throw bad("eventType missing");
        if (type.equals("settlement.created")) return settlement(event);
        if (!type.equals("payment.success") && !type.equals("refund.success")) {
            return new Handled(Outcome.IGNORED, null);          // other events carry no money movement
        }
        UUID eventId = uuid(event, "eventId");
        UUID merchantId = uuid(event, "merchantId");
        UUID reference = uuid(event, "aggregateId");
        JsonNode payload = event.path("payload");
        long amount = payload.path("amount").isIntegralNumber() ? payload.path("amount").asLong() : -1;
        String currency = payload.path("currency").asText("");
        if (amount <= 0) throw bad("payload.amount must be a positive integer (minor units)");
        if (!currency.matches("^[A-Z]{3}$")) throw bad("payload.currency invalid");
        String provider = payload.path("provider").asText("UNKNOWN").toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (provider.isEmpty()) provider = "UNKNOWN";
        String receivable = "PROVIDER_RECEIVABLE_" + provider;
        Instant occurred = parseInstant(text(event, "occurredAt"));

        Posting posting;
        List<Line> lines = new ArrayList<>();
        if (type.equals("payment.success")) {
            long fee = props.fee().on(amount);
            lines.add(new Line(receivable, null, Side.DEBIT, amount));
            lines.add(new Line("MERCHANT_PAYABLE", merchantId, Side.CREDIT, amount - fee));
            if (fee > 0) lines.add(new Line("APG_FEE_REVENUE", null, Side.CREDIT, fee));
            if (amount - fee == 0) lines.remove(1);            // a fee equal to the whole amount leaves nothing for the merchant
            posting = new Posting(eventId, "CAPTURE", reference, merchantId, currency, "Payment captured " + reference, occurred, lines);
        } else {
            lines.add(new Line("MERCHANT_PAYABLE", merchantId, Side.DEBIT, amount));
            lines.add(new Line(receivable, null, Side.CREDIT, amount));
            posting = new Posting(eventId, "REFUND", reference, merchantId, currency, "Refund " + reference + " of payment "
                    + payload.path("paymentId").asText(""), occurred, lines);
        }
        Result r = ledger.post(posting);
        return new Handled(r.replayed() ? Outcome.REPLAYED : Outcome.POSTED, r.transactionId());
    }

    /**
     * The provider paid APG for a fully reconciled batch (platform level, no merchant):
     * DR APG_BANK net received, DR PROVIDER_FEE_EXPENSE fees / CR PROVIDER_RECEIVABLE_{provider} net + fees.
     * The receivable shrinks by (payments - refunds), which is exactly what the provider settled.
     */
    private Handled settlement(JsonNode event) {
        UUID eventId = uuid(event, "eventId");
        UUID reference = uuid(event, "aggregateId");
        JsonNode p = event.path("payload");
        long net = p.path("amount").isIntegralNumber() ? p.path("amount").asLong() : -1;
        long fees = p.path("providerFees").isIntegralNumber() ? p.path("providerFees").asLong() : -1;
        String currency = p.path("currency").asText("");
        if (net < 0 || fees < 0) throw bad("payload.amount and payload.providerFees must be non-negative integers (minor units)");
        if (!currency.matches("^[A-Z]{3}$")) throw bad("payload.currency invalid");
        if (net + fees == 0) return new Handled(Outcome.IGNORED, null);
        String provider = p.path("provider").asText("UNKNOWN").toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (provider.isEmpty()) provider = "UNKNOWN";
        List<Line> lines = new ArrayList<>();
        if (net > 0) lines.add(new Line("APG_BANK", null, Side.DEBIT, net));
        if (fees > 0) lines.add(new Line("PROVIDER_FEE_EXPENSE", null, Side.DEBIT, fees));
        lines.add(new Line("PROVIDER_RECEIVABLE_" + provider, null, Side.CREDIT, net + fees));
        Result r = ledger.post(new Posting(eventId, "SETTLEMENT", reference, null, currency, "Provider settlement " + reference,
                parseInstant(text(event, "occurredAt")), lines));
        return new Handled(r.replayed() ? Outcome.REPLAYED : Outcome.POSTED, r.transactionId());
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.path(f);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static UUID uuid(JsonNode n, String f) {
        try {
            return UUID.fromString(text(n, f));
        } catch (RuntimeException e) {
            throw bad(f + " must be a UUID");
        }
    }

    private static Instant parseInstant(String s) {
        try {
            return s == null ? null : Instant.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static ApiException bad(String m) { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EVENT", m); }
}
