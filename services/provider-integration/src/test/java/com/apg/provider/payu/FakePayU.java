package com.apg.provider.payu;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** A tiny stand-in for PayU's merchant/postservice API. It really checks the request hash, like PayU does. */
public class FakePayU implements AutoCloseable {
    public static final String KEY = "testkey";
    public static final String SALT = "testsalt";

    public final HttpServer server;
    public final Map<String, String> scenarios = new ConcurrentHashMap<>();   // verify var1 / "refund:"+var2 -> response JSON
    public final AtomicInteger hits = new AtomicInteger();
    public final List<Map<String, String>> requests = new CopyOnWriteArrayList<>();

    public FakePayU() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/merchant/postservice", ex -> {
            hits.incrementAndGet();
            Map<String, String> form = parse(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requests.add(form);
            String expected = PayUHasher.commandHash(KEY, form.get("command"), form.get("var1"), SALT);
            int code = 200;
            String body;
            if (!KEY.equals(form.get("key")) || !expected.equals(form.get("hash"))) {
                body = "{\"status\":0,\"msg\":\"Invalid Hash.\"}";
            } else if ("verify_payment".equals(form.get("command"))) {
                String s = scenarios.getOrDefault(form.get("var1"), notFound(form.get("var1")));
                if (s.startsWith("SLOW")) {
                    try { Thread.sleep(2500); } catch (InterruptedException ignored) { }
                    s = "{}";
                }
                if (s.equals("RATE")) {
                    code = 429;
                    s = "{\"message\":\"Invalid requests limit reached. Kindly connect with your KAM\"}";
                }
                body = s;
            } else if ("check_action_status".equals(form.get("command"))) {
                body = scenarios.getOrDefault("refundstatus:" + form.get("var1"),
                        "{\"status\":1,\"msg\":\"fetched\",\"transaction_details\":{\"403993715\":{\"" + form.get("var1")
                                + "\":{\"request_id\":\"" + form.get("var1") + "\",\"token\":\"RF0001abc\",\"status\":\"queued\"}}}}");
            } else {
                body = scenarios.getOrDefault("refund:" + form.get("var2"),
                        "{\"status\":1,\"msg\":\"Refund Request Queued\",\"request_id\":\"77001\"}");
            }
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");   // PayU really sends JSON as text/html
            ex.sendResponseHeaders(code, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.createContext("/", ex -> {
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());   // a slow answer must not block other requests
        server.start();
    }

    public String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    public static String tx(String txn, String status, String unmapped, String amount) {
        return "{\"status\":1,\"msg\":\"1 out of 1 Transactions Fetched Successfully\",\"transaction_details\":{\"" + txn
                + "\":{\"mihpayid\":\"403993715\",\"txnid\":\"" + txn + "\",\"status\":\"" + status + "\",\"unmappedstatus\":\""
                + unmapped + "\",\"amt\":\"" + amount + "\",\"mode\":\"UPI\",\"error_code\":\"E000\",\"error_Message\":\"NO ERROR\"}}}";
    }

    public static String notFound(String txn) {
        return "{\"status\":0,\"msg\":\"0 out of 1 Transactions Fetched Successfully\",\"transaction_details\":{\"" + txn
                + "\":{\"mihpayid\":\"Not Found\",\"status\":\"Not Found\"}}}";
    }

    private static Map<String, String> parse(String body) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0) m.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    @Override
    public void close() { server.stop(0); }
}
