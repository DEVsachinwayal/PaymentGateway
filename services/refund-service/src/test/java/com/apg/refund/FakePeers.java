package com.apg.refund;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Stand-in for payment-service (payment read + state transition) and provider-integration (refund + refund status). */
public class FakePeers implements AutoCloseable {
    public record Payment(UUID id, UUID merchantId, long amount, String status, String providerPaymentId) {}

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpServer server;
    public final Map<UUID, Payment> payments = new ConcurrentHashMap<>();
    public final Map<UUID, String> statusOverride = new ConcurrentHashMap<>();       // payment id -> current status after transitions
    public final List<String> transitions = new CopyOnWriteArrayList<>();            // "paymentId:TO"
    public final AtomicInteger refundCalls = new AtomicInteger();
    public final AtomicInteger statusCalls = new AtomicInteger();
    /** refund reference -> ACCEPTED | REJECTED | UNKNOWN | DOWN */
    public final Map<String, String> refundAnswer = new ConcurrentHashMap<>();
    public volatile String defaultRefundAnswer = "ACCEPTED";
    /** provider request id -> SUCCESS | FAILED | PROCESSING | DOWN */
    public final Map<String, String> statusAnswer = new ConcurrentHashMap<>();
    public volatile boolean paymentServiceDown = false;
    public final List<String> refundReferences = new CopyOnWriteArrayList<>();

    public FakePeers() {
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

    public Payment addPayment(UUID merchant, long amount, String status) {
        Payment p = new Payment(UUID.randomUUID(), merchant, amount, status, "PAYU" + System.nanoTime());
        payments.put(p.id(), p);
        statusOverride.put(p.id(), status);
        return p;
    }

    public String statusOf(UUID paymentId) { return statusOverride.get(paymentId); }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (path.matches("/internal/v1/payments/[^/]+/transition")) {
            if (paymentServiceDown) { reply(ex, 503, "{\"error\":{\"code\":\"X\"}}"); return; }
            UUID id = UUID.fromString(path.split("/")[4]);
            String to = mapper.readTree(raw).path("to").asText();
            String from = statusOverride.get(id);
            boolean ok = from.equals(to) || switch (from) {
                case "SUCCESS" -> to.equals("REFUND_PENDING");
                case "REFUND_PENDING" -> List.of("REFUNDED", "PARTIALLY_REFUNDED", "SUCCESS").contains(to);
                case "PARTIALLY_REFUNDED" -> to.equals("REFUND_PENDING");
                default -> false;
            };
            if (!ok) { reply(ex, 409, "{\"error\":{\"code\":\"INVALID_STATE_TRANSITION\"}}"); return; }
            statusOverride.put(id, to);
            transitions.add(id + ":" + to);
            reply(ex, 200, "{\"data\":{}}");
        } else if (path.matches("/internal/v1/payments/[^/]+")) {
            if (paymentServiceDown) { reply(ex, 503, "{\"error\":{\"code\":\"X\"}}"); return; }
            Payment p = payments.get(UUID.fromString(path.split("/")[4]));
            if (p == null) { reply(ex, 404, "{\"error\":{\"code\":\"PAYMENT_NOT_FOUND\"}}"); return; }
            reply(ex, 200, "{\"data\":{\"paymentId\":\"" + p.id() + "\",\"merchantId\":\"" + p.merchantId() + "\",\"amount\":" + p.amount()
                    + ",\"currency\":\"INR\",\"status\":\"" + statusOverride.get(p.id()) + "\",\"provider\":\"payu\",\"providerPaymentId\":\""
                    + p.providerPaymentId() + "\"}}");
        } else if (path.matches("/internal/v1/providers/[^/]+/refunds/status")) {
            statusCalls.incrementAndGet();
            JsonNode b = mapper.readTree(raw);
            String ans = statusAnswer.getOrDefault(b.path("providerRequestId").asText(), "PROCESSING");
            if (ans.equals("DOWN")) { reply(ex, 502, "{\"error\":{\"code\":\"PROVIDER_UNAVAILABLE\"}}"); return; }
            reply(ex, 200, "{\"data\":{\"outcome\":\"" + ans + "\",\"rawStatus\":\"" + ans.toLowerCase() + "\"}}");
        } else if (path.matches("/internal/v1/providers/[^/]+/refunds")) {
            refundCalls.incrementAndGet();
            JsonNode b = mapper.readTree(raw);
            String ref = b.path("refundReference").asText();
            refundReferences.add(ref);
            String ans = refundAnswer.getOrDefault(ref, defaultRefundAnswer);
            switch (ans) {
                case "DOWN" -> reply(ex, 502, "{\"error\":{\"code\":\"PROVIDER_UNAVAILABLE\"}}");
                case "REJECTED" -> reply(ex, 200, "{\"data\":{\"status\":\"REJECTED\",\"providerRequestId\":null,\"message\":\"Token already used\"}}");
                case "UNKNOWN" -> reply(ex, 200, "{\"data\":{\"status\":\"UNKNOWN\",\"providerRequestId\":null,\"message\":\"PROVIDER_UNAVAILABLE\"}}");
                default -> reply(ex, 200, "{\"data\":{\"status\":\"ACCEPTED\",\"providerRequestId\":\"REQ" + ref.substring(2, 10) + "\",\"message\":\"queued\"}}");
            }
        } else {
            reply(ex, 404, "{}");
        }
    }

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
