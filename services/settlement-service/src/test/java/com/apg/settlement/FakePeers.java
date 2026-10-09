package com.apg.settlement;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.Map;

/** Stand-in for payment-service (attempt lookup) and refund-service (refund lookup by token). */
public class FakePeers implements AutoCloseable {
    public record Payment(UUID paymentId, UUID merchantId, String provider, String providerPaymentId, long amount, String currency, String status) {}

    public record Refund(UUID refundId, UUID merchantId, UUID paymentId, long amount, String currency, String status, String provider) {}

    private final HttpServer server;
    public final Map<String, Payment> payments = new ConcurrentHashMap<>();   // txnid -> payment
    public final Map<String, Refund> refunds = new ConcurrentHashMap<>();     // token -> refund
    public volatile boolean down = false;

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

    public Payment payment(UUID merchant, String txn, long amount, String status) {
        Payment p = new Payment(UUID.randomUUID(), merchant, "payu", "MIH" + txn, amount, "INR", status);
        payments.put(txn, p);
        return p;
    }

    public Refund refund(UUID merchant, String token, long amount, String status) {
        Refund r = new Refund(UUID.randomUUID(), merchant, UUID.randomUUID(), amount, "INR", status, "payu");
        refunds.put(token, r);
        return r;
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (down) { reply(ex, 503, "{\"error\":{\"code\":\"X\"}}"); return; }
        if (path.startsWith("/internal/v1/attempts/")) {
            Payment p = payments.get(path.substring("/internal/v1/attempts/".length()));
            if (p == null) { reply(ex, 404, "{\"error\":{\"code\":\"ATTEMPT_NOT_FOUND\"}}"); return; }
            reply(ex, 200, "{\"data\":{\"paymentId\":\"" + p.paymentId() + "\",\"merchantId\":\"" + p.merchantId() + "\",\"provider\":\"" + p.provider()
                    + "\",\"providerPaymentId\":\"" + p.providerPaymentId() + "\",\"amount\":" + p.amount() + ",\"currency\":\"" + p.currency()
                    + "\",\"paymentStatus\":\"" + p.status() + "\"}}");
        } else if (path.startsWith("/internal/v1/refunds/by-reference/")) {
            Refund r = refunds.get(path.substring("/internal/v1/refunds/by-reference/".length()));
            if (r == null) { reply(ex, 404, "{\"error\":{\"code\":\"REFUND_NOT_FOUND\"}}"); return; }
            reply(ex, 200, "{\"data\":{\"refundId\":\"" + r.refundId() + "\",\"merchantId\":\"" + r.merchantId() + "\",\"paymentId\":\"" + r.paymentId()
                    + "\",\"amount\":" + r.amount() + ",\"currency\":\"" + r.currency() + "\",\"status\":\"" + r.status() + "\",\"provider\":\"" + r.provider() + "\"}}");
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
