package com.apg.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Stand-in for provider-integration (callback verification), orchestrator (verify) and payment-service (lookup). */
public class FakePeers implements AutoCloseable {
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpServer server;
    public final Map<String, AtomicInteger> verifyCalls = new ConcurrentHashMap<>();
    /** per attempt reference: HTTP status the orchestrator should answer (default 200 SETTLED_SUCCESS) */
    public final Map<String, Integer> orchestratorStatus = new ConcurrentHashMap<>();
    public volatile boolean providerVerifierDown = false;
    public volatile String returnUrl = "https://merchant.example/return";

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
        byte[] raw = ex.getRequestBody().readAllBytes();
        if (path.matches("/internal/v1/providers/[^/]+/callbacks/verify")) {
            if (providerVerifierDown) { reply(ex, 503, "{\"error\":{\"code\":\"X\"}}"); return; }
            if (!path.contains("/payu/")) { reply(ex, 404, "{\"error\":{\"code\":\"PROVIDER_NOT_FOUND\"}}"); return; }
            JsonNode f = mapper.readTree(new String(raw, StandardCharsets.UTF_8)).path("fields");
            boolean ok = "valid-hash".equals(f.path("hash").asText());
            reply(ex, 200, "{\"data\":{\"authentic\":" + ok + ",\"reason\":" + (ok ? "null" : "\"hash mismatch\"") + ",\"providerReference\":\""
                    + f.path("txnid").asText() + "\",\"providerPaymentId\":\"" + f.path("mihpayid").asText() + "\",\"status\":\""
                    + ("success".equals(f.path("status").asText()) ? "SUCCESS" : "FAILED") + "\",\"amountMinor\":" + (long) (f.path("amount").asDouble() * 100) + "}}");
        } else if (path.matches("/internal/v1/orchestrator/attempts/[^/]+/verify")) {
            String ref = path.split("/")[5];
            verifyCalls.computeIfAbsent(ref, k -> new AtomicInteger()).incrementAndGet();
            int code = orchestratorStatus.getOrDefault(ref, 200);
            if (code == 200) reply(ex, 200, "{\"data\":{\"reference\":\"" + ref + "\",\"result\":\"SETTLED_SUCCESS\"}}");
            else reply(ex, code, "{\"error\":{\"code\":\"" + (code == 404 ? "ATTEMPT_NOT_FOUND" : "DEPENDENCY_UNAVAILABLE") + "\"}}");
        } else if (path.matches("/internal/v1/attempts/[^/]+")) {
            reply(ex, 200, "{\"data\":{\"paymentId\":\"11111111-1111-1111-1111-111111111111\"}}");
        } else if (path.matches("/internal/v1/payments/[^/]+")) {
            reply(ex, 200, "{\"data\":{\"status\":\"SUCCESS\",\"returnUrl\":" + (returnUrl == null ? "null" : "\"" + returnUrl + "\"") + "}}");
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
