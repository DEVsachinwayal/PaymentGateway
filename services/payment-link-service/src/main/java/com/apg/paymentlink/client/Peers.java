package com.apg.paymentlink.client;

import com.apg.common.api.ApiException;
import com.apg.paymentlink.service.LinkProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Calls the other services on behalf of the link's merchant (internal token + merchant headers, exactly like the gateway does for an
 * API-key caller). The customer never talks to these services; the merchant id always comes from the stored link, never from the request.
 */
@Component
public class Peers {
    public record Profile(String name, String supportEmail, String status) {}

    private final RestClient merchants, orders, payments, orchestrator;
    private final String token;

    public Peers(LinkProperties props, @Value("${apg.internal.token}") String token) {
        this.token = token;
        this.merchants = build(props.merchantServiceUri(), token, 5);
        this.orders = build(props.orderServiceUri(), token, 10);
        this.payments = build(props.paymentServiceUri(), token, 10);
        this.orchestrator = build(props.orchestratorUri(), token, 20);
    }

    private static RestClient build(String base, String token, int readSeconds) {
        JdkClientHttpRequestFactory f = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        f.setReadTimeout(Duration.ofSeconds(readSeconds));
        return RestClient.builder().requestInterceptor(com.apg.common.web.OutboundHeaders.INSTANCE).baseUrl(base).requestFactory(f)
                .defaultHeader("X-Internal-Token", token).build();
    }

    public Profile profile(UUID merchantId) {
        return call(() -> {
            JsonNode d = merchants.get().uri("/internal/v1/merchants/{id}/public-profile", merchantId).retrieve().body(JsonNode.class).path("data");
            return new Profile(d.path("name").asText(), text(d, "supportEmail"), d.path("status").asText());
        });
    }

    public UUID createOrder(UUID merchantId, long amount, String currency, String ref, String description, Map<String, String> customer, String idemKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amount);
        body.put("currency", currency);
        body.put("merchantOrderRef", ref);
        if (description != null) body.put("description", description);
        if (!customer.isEmpty()) body.put("customer", customer);
        return call(() -> UUID.fromString(orders.post().uri("/api/v1/orders").headers(h -> merchantHeaders(h, merchantId, idemKey))
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class).path("data").path("id").asText()));
    }

    public UUID createPayment(UUID merchantId, UUID orderId, String returnUrl, String idemKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", orderId.toString());
        if (returnUrl != null) body.put("returnUrl", returnUrl);
        return call(() -> UUID.fromString(payments.post().uri("/api/v1/payments").headers(h -> merchantHeaders(h, merchantId, idemKey))
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class).path("data").path("id").asText()));
    }

    public String checkout(UUID merchantId, UUID paymentId, String idemKey) {
        return call(() -> orchestrator.post().uri("/api/v1/payments/{id}/checkout", paymentId).headers(h -> merchantHeaders(h, merchantId, idemKey))
                .contentType(MediaType.APPLICATION_JSON).body(Map.of()).retrieve().body(JsonNode.class).path("data").path("checkoutUrl").asText());
    }

    /** @return the payment status, or null when payment-service does not know the payment */
    public String paymentStatus(UUID paymentId) {
        try {
            return payments.get().uri("/internal/v1/payments/{id}", paymentId).retrieve().body(JsonNode.class).path("data").path("status").asText();
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().value() == 404) return null;
            throw upstream(e);
        } catch (ResourceAccessException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", "Dependency unavailable");
        }
    }

    private static void merchantHeaders(org.springframework.http.HttpHeaders h, UUID merchantId, String idemKey) {
        h.set("X-Merchant-Id", merchantId.toString());
        h.set("X-Auth-Type", "API_KEY");
        h.set("Idempotency-Key", idemKey);
    }

    private <T> T call(java.util.function.Supplier<T> r) {
        try {
            return r.get();
        } catch (HttpStatusCodeException e) {
            throw upstream(e);
        } catch (ResourceAccessException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", "Dependency unavailable");
        }
    }

    private static ApiException upstream(HttpStatusCodeException e) {
        return new ApiException(e.getStatusCode().is4xxClientError() ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.BAD_GATEWAY,
                "UPSTREAM_REJECTED", "A dependency rejected the request (" + e.getStatusCode().value() + ")");
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.path(f);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }
}
