package com.apg.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/** In-memory stand-in for payment-service + provider-integration (their internal APIs), with the same state rules. */
public class FakeBackends implements AutoCloseable {
    static final Map<String, Set<String>> ALLOWED = Map.of(
            "CREATED", Set.of("PENDING", "FAILED"),
            "PENDING", Set.of("PROCESSING", "FAILED", "VERIFY_REQUIRED"),
            "PROCESSING", Set.of("AUTHORIZED", "SUCCESS", "FAILED", "VERIFY_REQUIRED"),
            "VERIFY_REQUIRED", Set.of("SUCCESS", "FAILED", "VERIFY_REQUIRED"),
            "SUCCESS", Set.of("REFUND_PENDING"));

    public static class P {
        public String id = UUID.randomUUID().toString();
        public String merchantId;
        public String status = "CREATED";
        public long amount;
        public String reference;
        public String attemptStatus;
        public String providerPaymentId;
        public Instant updatedAt;
        public final List<String> history = new CopyOnWriteArrayList<>();
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpServer server;
    private final Clock clock;
    public final Map<String, P> payments = new ConcurrentHashMap<>();
    public final Map<String, P> byRef = new ConcurrentHashMap<>();
    /** provider status per attempt reference: JSON body of "data", or "502" to simulate provider trouble. */
    public final Map<String, String> providerStatus = new ConcurrentHashMap<>();
    public volatile String providersJson =
            "[{\"provider\":\"payu\",\"enabled\":true,\"mode\":\"TEST\",\"priority\":1,\"health\":{\"up\":true,\"latencyMs\":5,\"detail\":\"ok\"}}]";
    public volatile boolean failCheckout = false;

    public FakeBackends(Clock clock) {
        this.clock = clock;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
    }

    public String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    public P newPayment(String merchantId, long amount) {
        P p = new P();
        p.merchantId = merchantId;
        p.amount = amount;
        p.updatedAt = clock.instant();
        payments.put(p.id, p);
        return p;
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            String q = ex.getRequestURI().getQuery() == null ? "" : ex.getRequestURI().getQuery();
            byte[] raw = ex.getRequestBody().readAllBytes();
            String[] seg = path.split("/");

            if (path.equals("/internal/v1/providers") && method.equals("GET")) {
                reply(ex, 200, "{\"data\":" + providersJson + "}");
            } else if (path.equals("/internal/v1/providers/payu/checkout")) {
                if (failCheckout) { reply(ex, 502, err("PROVIDER_UNAVAILABLE")); return; }
                reply(ex, 200, "{\"data\":{\"method\":\"POST\",\"url\":\"https://test.payu.in/_payment\",\"fields\":{\"key\":\"k\",\"txnid\":\"T1\",\"productinfo\":\"A \\\"quoted\\\" <b>item</b>\",\"hash\":\"abc\"}}}");
            } else if (path.matches("/internal/v1/providers/payu/payments/[^/]+/status")) {
                String ref = seg[6];
                String s = providerStatus.getOrDefault(ref, "{\"status\":\"NOT_FOUND\"}");
                if (s.equals("502")) reply(ex, 502, err("PROVIDER_UNAVAILABLE")); else reply(ex, 200, "{\"data\":" + s + "}");
            } else if (path.equals("/internal/v1/payments/pending")) {
                List<String> items = new ArrayList<>();
                for (P p : payments.values()) {
                    if (p.reference != null && q.contains(p.status)) {
                        items.add("{\"paymentId\":\"" + p.id + "\",\"paymentStatus\":\"" + p.status + "\",\"provider\":\"payu\",\"providerReference\":\"" + p.reference + "\",\"updatedAt\":\"" + p.updatedAt + "\"}");
                    }
                }
                reply(ex, 200, "{\"data\":[" + String.join(",", items) + "]}");
            } else if (seg.length == 6 && seg[3].equals("payments") && seg[5].equals("attempts") && method.equals("POST")) {
                P p = payments.get(seg[4]);
                if (p == null) { reply(ex, 404, err("PAYMENT_NOT_FOUND")); return; }
                synchronized (p) {
                    if (!p.status.equals("CREATED")) { reply(ex, 409, err("PAYMENT_NOT_STARTABLE")); return; }
                    p.reference = "A" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
                    p.attemptStatus = "CREATED";
                    move(p, "PENDING");
                    byRef.put(p.reference, p);
                }
                reply(ex, 200, "{\"data\":" + attemptJson(p) + "}");
            } else if (seg.length == 6 && seg[3].equals("payments") && seg[5].equals("transition") && method.equals("POST")) {
                P p = payments.get(seg[4]);
                if (p == null) { reply(ex, 404, err("PAYMENT_NOT_FOUND")); return; }
                String to = mapper.readTree(new String(raw, StandardCharsets.UTF_8)).path("to").asText();
                synchronized (p) {
                    if (p.status.equals(to)) { reply(ex, 200, "{\"data\":{\"status\":\"" + to + "\"}}"); return; }
                    if (!ALLOWED.getOrDefault(p.status, Set.of()).contains(to)) { reply(ex, 409, err("INVALID_STATE_TRANSITION")); return; }
                    move(p, to);
                }
                reply(ex, 200, "{\"data\":{\"status\":\"" + to + "\"}}");
            } else if (seg.length == 5 && seg[3].equals("payments") && method.equals("GET")) {
                P p = payments.get(seg[4]);
                if (p == null) { reply(ex, 404, err("PAYMENT_NOT_FOUND")); return; }
                reply(ex, 200, "{\"data\":{\"paymentId\":\"" + p.id + "\",\"merchantId\":\"" + p.merchantId + "\",\"orderId\":\"" + UUID.randomUUID()
                        + "\",\"amount\":" + p.amount + ",\"currency\":\"INR\",\"status\":\"" + p.status + "\",\"description\":\"Test order\","
                        + "\"customerName\":\"Asha\",\"customerEmail\":\"asha@example.com\",\"updatedAt\":\"" + p.updatedAt + "\"}}");
            } else if (seg.length == 5 && seg[3].equals("attempts")) {
                P p = byRef.get(seg[4]);
                if (p == null) { reply(ex, 404, err("ATTEMPT_NOT_FOUND")); return; }
                if (method.equals("PATCH")) {
                    JsonNode b = mapper.readTree(new String(raw, StandardCharsets.UTF_8));
                    p.attemptStatus = b.path("status").asText();
                    if (b.hasNonNull("providerPaymentId")) p.providerPaymentId = b.get("providerPaymentId").asText();
                }
                reply(ex, 200, "{\"data\":" + attemptJson(p) + "}");
            } else {
                reply(ex, 404, err("NOT_FOUND"));
            }
        } catch (Exception e) {
            reply(ex, 500, err("FAKE_BACKEND_ERROR " + e));
        }
    }

    private String attemptJson(P p) {
        return "{\"attemptId\":\"" + UUID.randomUUID() + "\",\"paymentId\":\"" + p.id + "\",\"merchantId\":\"" + p.merchantId + "\",\"attemptNo\":1,\"provider\":\"payu\","
                + "\"providerReference\":\"" + p.reference + "\",\"status\":\"" + p.attemptStatus + "\",\"amount\":" + p.amount + ",\"currency\":\"INR\",\"paymentStatus\":\"" + p.status + "\"}";
    }

    private void move(P p, String to) {
        p.history.add(p.status + "->" + to);
        p.status = to;
        p.updatedAt = clock.instant();
    }

    private static String err(String code) { return "{\"success\":false,\"error\":{\"code\":\"" + code + "\",\"message\":\"" + code + "\"}}"; }

    private void reply(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    @Override
    public void close() { server.stop(0); }
}
