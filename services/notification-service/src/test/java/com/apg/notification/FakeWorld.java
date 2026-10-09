package com.apg.notification;

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

/** A fake merchant-service (subscriber directory) and a fake merchant website (webhook receiver) in one object. */
public class FakeWorld implements AutoCloseable {
    public record Received(String path, Map<String, String> headers, String body) {}

    public record Sub(UUID id, String path, String secret) {}

    private final HttpServer directory;
    private final HttpServer merchant;
    /** merchant id -> event type -> subscribers */
    public final Map<UUID, Map<String, List<Sub>>> subscribers = new ConcurrentHashMap<>();
    public volatile boolean directoryDown = false;
    public final List<Received> received = new CopyOnWriteArrayList<>();
    public final AtomicInteger hits = new AtomicInteger();
    /** path -> status to answer (default 200) */
    public final Map<String, Integer> answers = new ConcurrentHashMap<>();
    public final Map<String, Integer> delayMillis = new ConcurrentHashMap<>();

    public FakeWorld() {
        try {
            directory = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            merchant = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        directory.setExecutor(Executors.newCachedThreadPool());
        merchant.setExecutor(Executors.newCachedThreadPool());
        directory.createContext("/", this::directoryHandler);
        merchant.createContext("/", this::merchantHandler);
        directory.start();
        merchant.start();
    }

    public String directoryUrl() { return "http://127.0.0.1:" + directory.getAddress().getPort(); }

    public String merchantUrl(String path) { return "http://127.0.0.1:" + merchant.getAddress().getPort() + path; }

    public Sub subscribe(UUID merchantId, String event, String path, String secret) {
        Sub s = new Sub(UUID.randomUUID(), path, secret);
        subscribers.computeIfAbsent(merchantId, k -> new ConcurrentHashMap<>()).computeIfAbsent(event, k -> new CopyOnWriteArrayList<>()).add(s);
        return s;
    }

    public int hitsFor(String path) { return (int) received.stream().filter(r -> r.path().equals(path)).count(); }

    private void directoryHandler(HttpExchange ex) throws IOException {
        if (directoryDown) { reply(ex, 503, "{}"); return; }
        String[] p = ex.getRequestURI().getPath().split("/");          // /internal/v1/merchants/{id}/webhook-subscribers
        UUID merchantId = UUID.fromString(p[4]);
        String event = ex.getRequestURI().getQuery().substring("event=".length());
        StringBuilder sb = new StringBuilder("{\"data\":[");
        boolean first = true;
        for (Sub s : subscribers.getOrDefault(merchantId, Map.of()).getOrDefault(event, List.of())) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":\"").append(s.id()).append("\",\"url\":\"").append(merchantUrl(s.path())).append("\",\"signingSecret\":\"").append(s.secret()).append("\"}");
        }
        reply(ex, 200, sb.append("]}").toString());
    }

    private void merchantHandler(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> h = new ConcurrentHashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(), v.get(0)));
        received.add(new Received(path, h, body));
        hits.incrementAndGet();
        Integer d = delayMillis.get(path);
        if (d != null) {
            try { Thread.sleep(d); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        int code = answers.getOrDefault(path, 200);
        if (code >= 300 && code < 400) ex.getResponseHeaders().add("Location", "http://127.0.0.1:1/elsewhere");
        byte[] b = "ok".getBytes(StandardCharsets.UTF_8);
        try {
            ex.sendResponseHeaders(code, b.length);
            ex.getResponseBody().write(b);
        } catch (IOException ignored) {
            // the sender timed out and hung up
        }
        ex.close();
    }

    private void reply(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    @Override
    public void close() {
        directory.stop(0);
        merchant.stop(0);
    }
}
