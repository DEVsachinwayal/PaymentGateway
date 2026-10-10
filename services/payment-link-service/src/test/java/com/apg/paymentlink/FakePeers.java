package com.apg.paymentlink;

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

/** Stand-in for merchant-service (profile), order-service, payment-service and the orchestrator (checkout). */
public class FakePeers implements AutoCloseable {
    public record OrderCall(String merchantHeader, String authType, String idemKey, JsonNode body) {}

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpServer server;
    public final List<OrderCall> orderCalls = new CopyOnWriteArrayList<>();
    public final AtomicInteger orders = new AtomicInteger();
    public final AtomicInteger payments = new AtomicInteger();
    public final AtomicInteger checkouts = new AtomicInteger();
    public final Map<UUID, String> paymentStatus = new ConcurrentHashMap<>();
    public volatile String merchantName = "Asha's Books";
    public volatile String merchantStatus = "SANDBOX";
    public volatile boolean orderServiceDown = false;
    public volatile int orderDelayMillis = 0;

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

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (path.matches("/internal/v1/merchants/[^/]+/public-profile")) {
            reply(ex, 200, "{\"data\":{\"merchantId\":\"" + path.split("/")[4] + "\",\"name\":" + mapper.writeValueAsString(merchantName)
                    + ",\"supportEmail\":\"help@books.example\",\"status\":\"" + merchantStatus + "\"}}");
        } else if (path.equals("/api/v1/orders")) {
            if (orderServiceDown) { reply(ex, 500, "{\"error\":{\"code\":\"X\"}}"); return; }
            if (orderDelayMillis > 0) { try { Thread.sleep(orderDelayMillis); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } }
            orders.incrementAndGet();
            orderCalls.add(new OrderCall(ex.getRequestHeaders().getFirst("X-Merchant-Id"), ex.getRequestHeaders().getFirst("X-Auth-Type"),
                    ex.getRequestHeaders().getFirst("Idempotency-Key"), mapper.readTree(raw)));
            reply(ex, 201, "{\"data\":{\"id\":\"" + UUID.randomUUID() + "\"}}");
        } else if (path.equals("/api/v1/payments")) {
            payments.incrementAndGet();
            UUID id = UUID.randomUUID();
            paymentStatus.put(id, "CREATED");
            reply(ex, 201, "{\"data\":{\"id\":\"" + id + "\"}}");
        } else if (path.matches("/api/v1/payments/[^/]+/checkout")) {
            checkouts.incrementAndGet();
            reply(ex, 201, "{\"data\":{\"checkoutUrl\":\"https://checkout.test/c/" + path.split("/")[4] + "\"}}");
        } else if (path.matches("/internal/v1/payments/[^/]+")) {
            String st = paymentStatus.get(UUID.fromString(path.split("/")[4]));
            if (st == null) reply(ex, 404, "{\"error\":{\"code\":\"PAYMENT_NOT_FOUND\"}}");
            else reply(ex, 200, "{\"data\":{\"status\":\"" + st + "\"}}");
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
