package com.apg.notification.service;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Performs one signed delivery. Safety rules: https only (unless the dev switch is on), the target must not resolve to a
 * private/loopback/link-local address (SSRF), redirects are never followed, the response body is never read, and the
 * signing secret is used only to compute the HMAC (never logged, never sent).
 * Signature: header {@code X-APG-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, t + "." + body)>}; receivers must
 * recompute it over the raw body and reject old timestamps.
 */
@Component
public class WebhookSender {
    public record Result(String outcome, Integer httpStatus, String detail, long durationMs) {
        public boolean ok() { return "OK".equals(outcome); }
    }

    private final NotificationProperties props;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5)).version(HttpClient.Version.HTTP_1_1).build();

    public WebhookSender(NotificationProperties props) {
        this.props = props;
    }

    public Result send(String url, String eventId, String eventType, String body, String secret) {
        long t0 = System.currentTimeMillis();
        try {
            URI uri = URI.create(url);
            String blocked = guard(uri);
            if (blocked != null) return new Result("BLOCKED", null, blocked, 0);
            long ts = System.currentTimeMillis() / 1000;
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(props.requestTimeout())
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "APG-Webhooks/1")
                    .header("X-APG-Event-Id", eventId)
                    .header("X-APG-Event-Type", eventType)
                    .header("X-APG-Signature", "t=" + ts + ",v1=" + sign(secret, ts + "." + body))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            HttpResponse<Void> res = http.send(req, HttpResponse.BodyHandlers.discarding());
            long ms = System.currentTimeMillis() - t0;
            int code = res.statusCode();
            if (code >= 200 && code < 300) return new Result("OK", code, null, ms);
            return new Result("HTTP_ERROR", code, code >= 300 && code < 400 ? "redirects are not followed" : "HTTP " + code, ms);
        } catch (HttpTimeoutException e) {
            return new Result("TIMEOUT", null, "no answer within " + props.requestTimeout().toSeconds() + "s", System.currentTimeMillis() - t0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result("NETWORK", null, "interrupted", System.currentTimeMillis() - t0);
        } catch (Exception e) {
            return new Result("NETWORK", null, e.getClass().getSimpleName(), System.currentTimeMillis() - t0);
        }
    }

    /** @return null when the target is acceptable, otherwise the reason it is blocked */
    public String guard(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (uri.getHost() == null || uri.getUserInfo() != null) return "URL must have a host and no credentials";
        if (props.allowInsecureUrls()) return scheme.equals("http") || scheme.equals("https") ? null : "only http/https";
        if (!scheme.equals("https")) return "only https is allowed";
        try {
            for (InetAddress a : InetAddress.getAllByName(uri.getHost())) {
                byte[] b = a.getAddress();
                boolean cgnat = b.length == 4 && (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 0x40;           // 100.64.0.0/10
                boolean ula = b.length == 16 && (b[0] & 0xfe) == 0xfc;                                      // fc00::/7
                if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                        || a.isMulticastAddress() || cgnat || ula) {
                    return "target resolves to a private or loopback address";
                }
            }
        } catch (Exception e) {
            return "host cannot be resolved";
        }
        return null;
    }

    public static String sign(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
