package com.apg.paymentlink.service;

import com.apg.common.api.ApiException;
import com.apg.common.audit.AuditLog;
import com.apg.common.web.CallerContext;
import com.apg.paymentlink.client.Peers;
import com.apg.paymentlink.client.Peers.Profile;
import jakarta.validation.constraints.*;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Payment links: a stable public address that starts a normal order + payment + hosted checkout whenever a customer opens it and
 * presses Pay. The amount, currency and merchant always come from the stored link, never from the request. One-time links stop being
 * payable after the first confirmed payment and never allow two payments to be in flight at once; the paid state is derived from
 * payment-service (events + a lazy check), never from the customer's browser.
 */
@Service
public class LinkService {
    private static final Logger log = LoggerFactory.getLogger(LinkService.class);
    private static final String ALPHABET = "23456789abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ";   // no look-alikes (0/O, 1/l/I)
    private static final Set<String> PAID_STATUSES = Set.of("SUCCESS", "REFUND_PENDING", "PARTIALLY_REFUNDED", "REFUNDED");
    private static final Set<String> AT_PROVIDER = Set.of("PROCESSING", "VERIFY_REQUIRED", "AUTHORIZED", "CAPTURED");

    public record CustomerInput(@Size(max = 120) String name, @Email @Size(max = 254) String email, @Size(max = 20) String phone) {}

    public record CreateRequest(@NotNull @Positive Long amount, @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
                                @Size(max = 300) String description, @Min(1) @Max(2160) Integer expiresInHours, Boolean oneTime,
                                @Pattern(regexp = "^https://\\S+$", message = "must be an https URL") @Size(max = 500) String returnUrl,
                                @jakarta.validation.Valid CustomerInput customer) {}

    public record LinkView(UUID id, String code, String url, long amount, String currency, String description, String status, boolean oneTime,
                           Instant expiresAt, Instant createdAt, Instant paidAt, int successfulPayments, String returnUrl) {}

    public record PageView<T>(List<T> content, int page, int size, long totalElements) {}

    /** What the public page needs. Deliberately has no merchant id, payment ids or customer data. */
    public record PublicLink(String code, String merchantName, String merchantSupport, long amount, String currency, String description,
                             String status) {}

    /** Thrown for the public page; {@code kind} selects the message. */
    public static class NotPayable extends RuntimeException {
        public final String kind;
        public NotPayable(String kind) { super(kind); this.kind = kind; }
    }

    private record Row(UUID id, UUID merchantId, String code, long amount, String currency, String description, String customerName,
                       String customerEmail, String customerPhone, boolean oneTime, String status, Instant expiresAt, String returnUrl) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Peers peers;
    private final LinkProperties props;
    private final AuditLog audit;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public LinkService(JdbcTemplate jdbc, TransactionTemplate tx, Peers peers, LinkProperties props, AuditLog audit, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.peers = peers;
        this.props = props;
        this.audit = audit;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ merchant side

    public LinkView create(UUID merchantId, CreateRequest r, CallerContext caller) {
        if (r.amount() > props.maxAmount()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AMOUNT_TOO_LARGE", "Amount above the allowed maximum");
        }
        int hours = r.expiresInHours() == null ? props.defaultExpiryHours() : Math.min(r.expiresInHours(), props.maxExpiryHours());
        Profile profile = peers.profile(merchantId);
        if ("SUSPENDED".equals(profile.status())) throw new ApiException(HttpStatus.FORBIDDEN, "MERCHANT_SUSPENDED", "Merchant is suspended");
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        String code = newCode();
        CustomerInput c = r.customer();
        jdbc.update("""
                INSERT INTO payment_links (id, merchant_id, code, merchant_name, merchant_support, amount, currency, description, customer_name,
                    customer_email, customer_phone, one_time, status, expires_at, return_url, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?, 'ACTIVE', ?,?,?,?)""",
                id, merchantId, code, truncate(profile.name(), 150), profile.supportEmail(), r.amount(), r.currency(), blankToNull(r.description()),
                c == null ? null : blankToNull(c.name()), c == null ? null : blankToNull(c.email()), c == null ? null : blankToNull(c.phone()),
                r.oneTime() == null || r.oneTime(), Timestamp.from(now.plus(hours, ChronoUnit.HOURS)), blankToNull(r.returnUrl()),
                Timestamp.from(now), Timestamp.from(now));
        audit.record(caller, "PAYMENT_LINK_CREATED", id.toString(), "SUCCESS", Map.of("amount", r.amount(), "currency", r.currency(), "oneTime", r.oneTime() == null || r.oneTime()));
        return get(merchantId, id);
    }

    public LinkView get(UUID merchantId, UUID id) {
        expireDue();
        return jdbc.query("SELECT * FROM payment_links WHERE id = ? AND merchant_id = ?", (rs, i) -> view(rs), id, merchantId).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PAYMENT_LINK_NOT_FOUND", "Payment link not found"));
    }

    public PageView<LinkView> list(UUID merchantId, String status, int page, int size) {
        expireDue();
        int s = Math.min(Math.max(size, 1), 100), pg = Math.max(page, 0);
        String where = status == null ? "merchant_id = ?" : "merchant_id = ? AND status = ?";
        Object[] args = status == null ? new Object[]{merchantId} : new Object[]{merchantId, status};
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM payment_links WHERE " + where, Long.class, args);
        Object[] pageArgs = Arrays.copyOf(args, args.length + 2);
        pageArgs[args.length] = s;
        pageArgs[args.length + 1] = pg * s;
        return new PageView<>(jdbc.query("SELECT * FROM payment_links WHERE " + where + " ORDER BY created_at DESC LIMIT ? OFFSET ?", (rs, i) -> view(rs), pageArgs),
                pg, s, total == null ? 0 : total);
    }

    public LinkView cancel(UUID merchantId, UUID id, CallerContext caller) {
        int n = jdbc.update("UPDATE payment_links SET status = 'CANCELLED', updated_at = ? WHERE id = ? AND merchant_id = ? AND status = 'ACTIVE'",
                Timestamp.from(clock.instant()), id, merchantId);
        LinkView v = get(merchantId, id);
        if (n == 0) throw new ApiException(HttpStatus.CONFLICT, "NOT_CANCELLABLE", "Only an active link can be cancelled (it is " + v.status() + ")");
        audit.record(caller, "PAYMENT_LINK_CANCELLED", id.toString(), "SUCCESS", null);
        return v;
    }

    // ------------------------------------------------------------------ customer side (public)

    public Optional<PublicLink> publicLink(String code) {
        Optional<Row> found = byCode(code);
        if (found.isEmpty()) return Optional.empty();
        refresh(found.get().id());
        expireDue();
        return jdbc.query("SELECT * FROM payment_links WHERE code = ?", (rs, i) -> new PublicLink(rs.getString("code"), rs.getString("merchant_name"),
                rs.getString("merchant_support"), rs.getLong("amount"), rs.getString("currency"), rs.getString("description"), rs.getString("status")), code)
                .stream().findFirst();
    }

    /** Presses "Pay": creates the order, the payment and the hosted checkout, and returns the URL to send the customer to. */
    public String start(String code) {
        Row link = byCode(code).orElseThrow(() -> new NotPayable("NOT_FOUND"));
        refresh(link.id());                                              // learn about finished attempts first (best effort)
        UUID attemptId = UUID.randomUUID();
        tx.executeWithoutResult(t -> {
            jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(?, 0))) l", Integer.class, link.id().toString());
            expireDue();
            String status = jdbc.queryForObject("SELECT status FROM payment_links WHERE id = ?", String.class, link.id());
            if (!"ACTIVE".equals(status)) throw new NotPayable(status);
            if (link.oneTime() && inFlight(link.id())) throw new NotPayable("IN_PROGRESS");
            Timestamp now = Timestamp.from(clock.instant());
            jdbc.update("INSERT INTO link_attempts (id, link_id, state, created_at, updated_at) VALUES (?, ?, 'STARTING', ?, ?)", attemptId, link.id(), now, now);
        });
        try {
            Map<String, String> customer = new LinkedHashMap<>();
            if (link.customerName() != null) customer.put("name", link.customerName());
            if (link.customerEmail() != null) customer.put("email", link.customerEmail());
            if (link.customerPhone() != null) customer.put("phone", link.customerPhone());
            String key = "plink-" + attemptId;
            UUID orderId = peers.createOrder(link.merchantId(), link.amount(), link.currency(), "LINK-" + link.code() + "-" + attemptId.toString().substring(0, 8),
                    link.description(), customer, key + "-order");
            UUID paymentId = peers.createPayment(link.merchantId(), orderId, link.returnUrl(), key + "-payment");
            jdbc.update("UPDATE link_attempts SET order_id = ?, payment_id = ?, state = 'OPEN', payment_status = 'CREATED', updated_at = ? WHERE id = ?",
                    orderId, paymentId, Timestamp.from(clock.instant()), attemptId);
            return peers.checkout(link.merchantId(), paymentId, key + "-checkout");
        } catch (RuntimeException e) {
            jdbc.update("UPDATE link_attempts SET state = 'START_FAILED', updated_at = ? WHERE id = ? AND state IN ('STARTING', 'OPEN') AND payment_id IS NULL",
                    Timestamp.from(clock.instant()), attemptId);
            log.warn("could not start payment for link {}: {}", link.code(), e.toString());
            throw e;
        }
    }

    // ------------------------------------------------------------------ keeping the link state truthful

    /** True when another payment of this link may already be at the provider (or is just being created). */
    private boolean inFlight(UUID linkId) {
        Timestamp startingSince = Timestamp.from(clock.instant().minus(props.startingWindow()));
        Timestamp openSince = Timestamp.from(clock.instant().minus(props.inFlightWindow()));
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM link_attempts WHERE link_id = ? AND (
                    (state = 'STARTING' AND created_at > ?) OR
                    (state = 'OPEN' AND created_at > ? AND payment_status IN ('PROCESSING', 'VERIFY_REQUIRED', 'AUTHORIZED', 'CAPTURED')))""",
                Integer.class, linkId, startingSince, openSince);
        return n != null && n > 0;
    }

    /** Asks payment-service about attempts that are not final yet. Failures are ignored: the next call or the event will do it. */
    public void refresh(UUID linkId) {
        Instant now = clock.instant();
        jdbc.update("UPDATE link_attempts SET state = 'START_FAILED', updated_at = ? WHERE link_id = ? AND state = 'STARTING' AND created_at < ?",
                Timestamp.from(now), linkId, Timestamp.from(now.minus(props.startingWindow())));
        record Open(UUID id, UUID paymentId, Instant createdAt) {}
        List<Open> open = jdbc.query("SELECT id, payment_id, created_at FROM link_attempts WHERE link_id = ? AND state = 'OPEN' AND payment_id IS NOT NULL",
                (rs, i) -> new Open(rs.getObject("id", UUID.class), rs.getObject("payment_id", UUID.class), rs.getTimestamp("created_at").toInstant()), linkId);
        for (Open a : open) {
            try {
                String st = peers.paymentStatus(a.paymentId());
                if (st == null) continue;
                if (PAID_STATUSES.contains(st)) {
                    succeeded(a.id());
                } else if ("FAILED".equals(st)) {
                    jdbc.update("UPDATE link_attempts SET state = 'FAILED', payment_status = ?, updated_at = ? WHERE id = ? AND state = 'OPEN'", st, Timestamp.from(now), a.id());
                } else if (!AT_PROVIDER.contains(st) && a.createdAt().isBefore(now.minus(props.inFlightWindow()))) {
                    jdbc.update("UPDATE link_attempts SET state = 'ABANDONED', payment_status = ?, updated_at = ? WHERE id = ? AND state = 'OPEN'", st, Timestamp.from(now), a.id());
                } else {
                    jdbc.update("UPDATE link_attempts SET payment_status = ?, updated_at = ? WHERE id = ? AND state = 'OPEN'", st, Timestamp.from(now), a.id());
                }
            } catch (RuntimeException e) {
                log.debug("status refresh of payment {} postponed: {}", a.paymentId(), e.toString());
            }
        }
    }

    /** Called by the payment.success event consumer and by refresh(). Idempotent. */
    public void paymentSucceeded(UUID paymentId) {
        jdbc.query("SELECT id FROM link_attempts WHERE payment_id = ?", (rs, i) -> rs.getObject(1, UUID.class), paymentId).forEach(this::succeeded);
    }

    private void succeeded(UUID attemptId) {
        tx.executeWithoutResult(t -> {
            int changed = jdbc.update("UPDATE link_attempts SET state = 'SUCCEEDED', payment_status = 'SUCCESS', updated_at = ? WHERE id = ? AND state <> 'SUCCEEDED'",
                    Timestamp.from(clock.instant()), attemptId);
            if (changed == 0) return;                                                    // already counted
            UUID linkId = jdbc.queryForObject("SELECT link_id FROM link_attempts WHERE id = ?", UUID.class, attemptId);
            Timestamp now = Timestamp.from(clock.instant());
            jdbc.update("""
                    UPDATE payment_links SET successful_payments = successful_payments + 1, updated_at = ?,
                        status = CASE WHEN one_time AND status IN ('ACTIVE', 'EXPIRED') THEN 'PAID' ELSE status END,
                        paid_at = COALESCE(paid_at, ?) WHERE id = ?""", now, now, linkId);
        });
    }

    /** Persists expiry (reads also compute it lazily so a link never shows ACTIVE after its time). */
    private void expireDue() {
        jdbc.update("UPDATE payment_links SET status = 'EXPIRED', updated_at = ? WHERE status = 'ACTIVE' AND expires_at < ?",
                Timestamp.from(clock.instant()), Timestamp.from(clock.instant()));
    }

    // ------------------------------------------------------------------ helpers

    private Optional<Row> byCode(String code) {
        if (code == null || !code.matches("^[A-Za-z0-9]{6,16}$")) return Optional.empty();
        return jdbc.query("SELECT * FROM payment_links WHERE code = ?", (rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getObject("merchant_id", UUID.class),
                rs.getString("code"), rs.getLong("amount"), rs.getString("currency"), rs.getString("description"), rs.getString("customer_name"),
                rs.getString("customer_email"), rs.getString("customer_phone"), rs.getBoolean("one_time"), rs.getString("status"),
                rs.getTimestamp("expires_at").toInstant(), rs.getString("return_url")), code).stream().findFirst();
    }

    private LinkView view(java.sql.ResultSet rs) throws java.sql.SQLException {
        String code = rs.getString("code");
        return new LinkView(rs.getObject("id", UUID.class), code, props.publicBaseUrl().replaceAll("/+$", "") + "/pay/" + code, rs.getLong("amount"),
                rs.getString("currency"), rs.getString("description"), rs.getString("status"), rs.getBoolean("one_time"),
                rs.getTimestamp("expires_at").toInstant(), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("paid_at") == null ? null : rs.getTimestamp("paid_at").toInstant(), rs.getInt("successful_payments"), rs.getString("return_url"));
    }

    private String newCode() {
        StringBuilder sb = new StringBuilder(10);
        for (int i = 0; i < 10; i++) sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    private static String truncate(String s, int max) { return s == null ? null : s.length() <= max ? s : s.substring(0, max); }
}
