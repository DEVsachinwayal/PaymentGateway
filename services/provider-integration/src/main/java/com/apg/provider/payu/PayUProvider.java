package com.apg.provider.payu;

import com.apg.provider.api.PaymentProvider;
import com.apg.provider.api.ProviderException;
import com.apg.provider.api.ProviderModels.*;
import com.apg.provider.service.CallLog;
import com.apg.provider.service.SimpleCircuitBreaker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * PayU India adapter (hosted checkout). All PayU specifics - field names, hash formulas, status vocabulary -
 * stay in this package.
 */
@Component
@ConditionalOnProperty(prefix = "apg.provider.payu", name = "enabled", havingValue = "true")
public class PayUProvider implements PaymentProvider {
    static final String ID = "payu";

    private final PayUProperties props;
    private final ObjectMapper mapper;
    private final CallLog callLog;
    private final RestClient client;
    private final SimpleCircuitBreaker breaker;

    public PayUProvider(PayUProperties props, ObjectMapper mapper, CallLog callLog, Clock clock) {
        this.props = props;
        this.mapper = mapper;
        this.callLog = callLog;
        HttpClient http = HttpClient.newBuilder().connectTimeout(props.connectTimeout() == null ? Duration.ofSeconds(3) : props.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(props.readTimeout() == null ? Duration.ofSeconds(10) : props.readTimeout());
        this.client = RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory).build();
        this.breaker = new SimpleCircuitBreaker(5, Duration.ofSeconds(30), clock);
    }

    @Override
    public String id() { return ID; }

    // ---------------------------------------------------------------- checkout
    @Override
    public CheckoutInstructions createPayment(PaymentRequest r) {
        long t0 = System.currentTimeMillis();
        if (!"INR".equals(r.currency())) {
            throw new ProviderException("UNSUPPORTED_CURRENCY", "PayU adapter supports INR only");
        }
        String amount = toMajor(r.amountMinor());
        String productInfo = clean(r.description(), "Order", 100);
        String firstName = clean(r.customerName(), "Customer", 60);
        // Placeholder only for sandbox; production onboarding must make customer email mandatory (TODO).
        String email = r.customerEmail() == null || r.customerEmail().isBlank() ? "customer@example.com" : clean(r.customerEmail(), "", 100);
        String phone = r.customerPhone() == null ? "" : r.customerPhone().replaceAll("[^0-9]", "");
        String udf1 = r.paymentId() == null ? "" : clean(r.paymentId(), "", 64);

        String hash = PayUHasher.paymentRequestHash(props.merchantKey(), r.providerReference(), amount, productInfo,
                firstName, email, new String[]{udf1, "", "", "", ""}, props.merchantSalt());

        Map<String, String> f = new LinkedHashMap<>();
        f.put("key", props.merchantKey());
        f.put("txnid", r.providerReference());
        f.put("amount", amount);
        f.put("productinfo", productInfo);
        f.put("firstname", firstName);
        f.put("email", email);
        f.put("phone", phone);
        f.put("surl", r.successUrl());
        f.put("furl", r.failureUrl());
        f.put("curl", r.failureUrl());
        f.put("udf1", udf1);
        f.put("hash", hash);
        callLog.record(ID, "CREATE_PAYMENT", r.providerReference(), "OK", null, System.currentTimeMillis() - t0,
                "amountMinor=" + r.amountMinor());
        return new CheckoutInstructions("POST", props.baseUrl() + "/_payment", f);
    }

    // ---------------------------------------------------------------- status (verify_payment)
    @Override
    public PaymentStatusResponse getPaymentStatus(String providerReference) {
        JsonNode root = postService("verify_payment", providerReference, null, "STATUS", providerReference);
        JsonNode details = root.path("transaction_details");
        JsonNode tx = details.path(providerReference);
        if (tx.isMissingNode() || tx.isNull() || !tx.isObject()) {
            throw new ProviderException("PROVIDER_BAD_RESPONSE", "verify_payment returned no transaction block");
        }
        String rawStatus = text(tx, "status");
        if ("Not Found".equalsIgnoreCase(rawStatus) || "Not Found".equalsIgnoreCase(text(tx, "mihpayid"))) {
            return new PaymentStatusResponse(Status.NOT_FOUND, null, null, rawStatus, null, null);
        }
        if (root.path("status").asInt(0) != 1) {
            throw new ProviderException("PROVIDER_BAD_RESPONSE", "verify_payment status != 1: " + text(root, "msg"));
        }
        Long minor;
        try {
            minor = toMinor(firstNonNull(text(tx, "amt"), text(tx, "transaction_amount")));
        } catch (RuntimeException e) {
            throw new ProviderException("PROVIDER_BAD_RESPONSE", "unparseable amount in verify_payment");
        }
        return new PaymentStatusResponse(normalize(rawStatus, text(tx, "unmappedstatus")), text(tx, "mihpayid"), minor,
                rawStatus + "/" + text(tx, "unmappedstatus"), text(tx, "error_code"), text(tx, "error_Message"));
    }

    // ---------------------------------------------------------------- refund
    @Override
    public RefundResponse refund(RefundRequest r) {
        JsonNode root;
        try {
            root = postService("cancel_refund_transaction", r.providerPaymentId(),
                    Map.of("var2", r.refundReference(), "var3", toMajor(r.amountMinor())), "REFUND", r.refundReference());
        } catch (ProviderException e) {
            // We cannot know whether PayU queued the refund: the caller must verify, never blindly retry (spec 4.2).
            return new RefundResponse(RefundStatus.UNKNOWN, null, e.code());
        }
        if (root.path("status").asInt(0) == 1) {
            return new RefundResponse(RefundStatus.ACCEPTED, firstNonNull(text(root, "request_id"), text(root, "mihpayid")), text(root, "msg"));
        }
        return new RefundResponse(RefundStatus.REJECTED, null, text(root, "msg"));
    }

    @Override
    public RefundStatusResponse getRefundStatus(RefundStatusRequest r) {
        JsonNode root = postService("check_action_status", r.providerRequestId(), null, "REFUND_STATUS", r.refundReference());
        if (root.path("status").asInt(0) != 1) {
            throw new ProviderException("PROVIDER_BAD_RESPONSE", "check_action_status status != 1: " + text(root, "msg"));
        }
        // PayU nests the refund record under transaction_details; find the object for our request/token without
        // depending on the exact nesting. Anything not explicitly final stays PROCESSING (never guess success).
        String raw = findRefundStatus(root.path("transaction_details"), r.providerRequestId(), r.refundReference());
        return new RefundStatusResponse(normalizeRefund(raw), raw);
    }

    private static String findRefundStatus(JsonNode n, String requestId, String token) {
        if (n == null || !n.isObject()) return null;
        boolean mine = requestId.equals(text(n, "request_id")) || token.equals(text(n, "token"));
        if (mine && n.hasNonNull("status")) return n.get("status").asText();
        var it = n.elements();
        while (it.hasNext()) {
            String found = findRefundStatus(it.next(), requestId, token);
            if (found != null) return found;
        }
        return null;
    }

    static RefundOutcome normalizeRefund(String raw) {
        String s = raw == null ? "" : raw.toLowerCase(Locale.ROOT).trim();
        if (s.equals("success") || s.equals("refunded")) return RefundOutcome.SUCCESS;
        if (s.equals("failure") || s.equals("failed") || s.equals("rejected")) return RefundOutcome.FAILED;
        return RefundOutcome.PROCESSING;
    }

    // ---------------------------------------------------------------- callback (surl/furl/webhook)
    @Override
    public ProviderWebhookResult verifyWebhook(ProviderWebhookRequest request) {
        long t0 = System.currentTimeMillis();
        Map<String, String> f = request.fields();
        String txnid = f.get("txnid");
        try {
            for (String required : new String[]{"txnid", "status", "amount", "hash", "key"}) {
                if (f.get(required) == null || f.get(required).isBlank()) {
                    return invalid(txnid, "missing field " + required, t0);
                }
            }
            if (!props.merchantKey().equals(f.get("key"))) return invalid(txnid, "merchant key mismatch", t0);

            String additional = f.get("additionalCharges") != null ? f.get("additionalCharges") : f.get("additional_charges");
            String expected = PayUHasher.responseHash(additional, props.merchantSalt(), f.get("status"),
                    new String[]{v(f, "udf1"), v(f, "udf2"), v(f, "udf3"), v(f, "udf4"), v(f, "udf5")},
                    v(f, "email"), v(f, "firstname"), v(f, "productinfo"), f.get("amount"), txnid, props.merchantKey());
            if (!PayUHasher.constantTimeEqualsIgnoreCase(expected, f.get("hash"))) return invalid(txnid, "hash mismatch", t0);

            Long minor;
            try {
                minor = toMinor(f.get("amount"));
            } catch (RuntimeException e) {
                return invalid(txnid, "unparseable amount", t0);
            }
            Status status = normalize(f.get("status"), f.get("unmappedstatus"));
            callLog.record(ID, "VERIFY_CALLBACK", txnid, "OK", null, System.currentTimeMillis() - t0, "status=" + status);
            return new ProviderWebhookResult(true, null, txnid, f.get("mihpayid"), status, minor);
        } catch (RuntimeException e) {
            return invalid(txnid, "unexpected error", t0);
        }
    }

    private ProviderWebhookResult invalid(String txnid, String reason, long t0) {
        callLog.record(ID, "VERIFY_CALLBACK", txnid, "INVALID", null, System.currentTimeMillis() - t0, reason);
        return new ProviderWebhookResult(false, reason, txnid, null, Status.UNKNOWN, null);
    }

    // ---------------------------------------------------------------- health
    @Override
    public ProviderHealth health() {
        if (breaker.isOpen()) return new ProviderHealth(false, 0, "circuit open after repeated failures");
        long t0 = System.currentTimeMillis();
        try {
            HttpStatusCode code = client.get().uri("/").exchange((req, res) -> res.getStatusCode());
            long ms = System.currentTimeMillis() - t0;
            return new ProviderHealth(!code.is5xxServerError(), ms, "HTTP " + code.value());
        } catch (Exception e) {
            return new ProviderHealth(false, System.currentTimeMillis() - t0, "unreachable");
        }
    }

    // ---------------------------------------------------------------- helpers
    private JsonNode postService(String command, String var1, Map<String, String> extra, String operation, String reference) {
        if (!breaker.allowRequest()) {
            callLog.record(ID, operation, reference, "ERROR", null, 0, "circuit open");
            throw new ProviderException("PROVIDER_UNAVAILABLE", "PayU temporarily unavailable (circuit open)");
        }
        long t0 = System.currentTimeMillis();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("key", props.merchantKey());
        form.add("command", command);
        form.add("var1", var1);
        if (extra != null) extra.forEach(form::add);
        form.add("hash", PayUHasher.commandHash(props.merchantKey(), command, var1, props.merchantSalt()));
        try {
            var res = client.post().uri("/merchant/postservice?form=2").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().onStatus(s -> true, (rq, rs) -> { }).toEntity(String.class);   // read bodies of 4xx/5xx too
            int http = res.getStatusCode().value();
            JsonNode root = mapper.readTree(res.getBody() == null ? "" : res.getBody());
            if (!root.isObject()) throw new ProviderException("PROVIDER_BAD_RESPONSE", "non-JSON answer from PayU");
            if (root.has("message") && !root.has("status")) {       // e.g. "Invalid requests limit reached"
                breaker.recordFailure();
                callLog.record(ID, operation, reference, "ERROR", http, System.currentTimeMillis() - t0, "rejected: " + text(root, "message"));
                throw new ProviderException("PROVIDER_RATE_LIMITED", "PayU refused the request");
            }
            breaker.recordSuccess();
            callLog.record(ID, operation, reference, root.path("status").asInt(0) == 1 ? "OK" : "REJECTED", http,
                    System.currentTimeMillis() - t0, "msg=" + text(root, "msg"));
            return root;
        } catch (ProviderException e) {
            throw e;
        } catch (Exception e) {
            breaker.recordFailure();
            callLog.record(ID, operation, reference, "UNKNOWN", null, System.currentTimeMillis() - t0, e.getClass().getSimpleName());
            throw new ProviderException("PROVIDER_UNAVAILABLE", "PayU call failed: " + e.getClass().getSimpleName(), e);
        }
    }

    /**
     * PayU vocabulary -> APG. SUCCESS only when the money is really captured; "failure"/"failed"/"usercancelled" are final;
     * everything else (pending, dropped, bounced, auth...) stays PENDING so it gets verified again instead of being failed blindly.
     */
    static Status normalize(String status, String unmapped) {
        String s = status == null ? "" : status.toLowerCase(Locale.ROOT);
        String u = unmapped == null ? "" : unmapped.toLowerCase(Locale.ROOT);
        if (s.equals("success") && u.equals("captured")) return Status.SUCCESS;
        if (s.equals("failure") || u.equals("failed") || u.equals("usercancelled")) return Status.FAILED;
        return Status.PENDING;
    }

    static String toMajor(long minor) {
        return BigDecimal.valueOf(minor, 2).toPlainString();
    }

    static Long toMinor(String major) {
        return new BigDecimal(major.trim()).setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
    }

    /** PayU splits fields on '|' internally and rejects control characters: strip them and cap the length. */
    static String clean(String s, String fallback, int max) {
        if (s == null || s.isBlank()) return fallback;
        String c = s.replaceAll("[|\\p{Cntrl}]", " ").trim();
        if (c.isEmpty()) return fallback;
        return c.length() > max ? c.substring(0, max) : c;
    }

    private static String v(Map<String, String> m, String k) { return m.get(k) == null ? "" : m.get(k); }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.path(f);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static String firstNonNull(String a, String b) { return a != null ? a : b; }
}
