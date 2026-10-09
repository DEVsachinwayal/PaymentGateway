package com.apg.refund.client;

import com.apg.common.api.ApiException;
import com.apg.refund.service.RefundProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** Calls to payment-service (read payment, move its state) and provider-integration (refund + refund status). */
@Component
public class Peers {
    public record PaymentInfo(UUID paymentId, UUID merchantId, long amount, String currency, String status,
                              String provider, String providerPaymentId) {}

    /** status: ACCEPTED | REJECTED | UNKNOWN (UNKNOWN is also what we report when the provider could not be reached). */
    public record ProviderRefund(String status, String providerRequestId, String message) {}

    /** outcome: SUCCESS | FAILED | PROCESSING */
    public record ProviderRefundStatus(String outcome, String rawStatus) {}

    private final RestClient payments;
    private final RestClient providers;
    private final ObjectMapper mapper;

    public Peers(RefundProperties props, @Value("${apg.internal.token}") String token, ObjectMapper mapper) {
        this.mapper = mapper;
        this.payments = build(props.paymentServiceUri(), token, 5);
        this.providers = build(props.providerServiceUri(), token, 30);
    }

    private static RestClient build(String base, String token, int readSeconds) {
        JdkClientHttpRequestFactory f = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        f.setReadTimeout(Duration.ofSeconds(readSeconds));
        return RestClient.builder().requestInterceptor(com.apg.common.web.OutboundHeaders.INSTANCE).baseUrl(base).requestFactory(f).defaultHeader("X-Internal-Token", token).build();
    }

    public PaymentInfo payment(UUID id) {
        return call(() -> {
            JsonNode d = payments.get().uri("/internal/v1/payments/{id}", id).retrieve().body(JsonNode.class).path("data");
            return new PaymentInfo(UUID.fromString(d.path("paymentId").asText()), UUID.fromString(d.path("merchantId").asText()),
                    d.path("amount").asLong(), d.path("currency").asText(), d.path("status").asText(),
                    text(d, "provider"), text(d, "providerPaymentId"));
        });
    }

    public void transitionPayment(UUID id, String to, String reason) {
        call(() -> {
            payments.post().uri("/internal/v1/payments/{id}/transition", id).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("to", to, "reason", reason, "source", "REFUND")).retrieve().toBodilessEntity();
            return null;
        });
    }

    /** Never throws for provider trouble: an unclear outcome is UNKNOWN and must be resolved by verification, not a retry. */
    public ProviderRefund refund(String provider, String providerPaymentId, String reference, long amountMinor) {
        try {
            JsonNode d = providers.post().uri("/internal/v1/providers/{p}/refunds", provider).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("providerPaymentId", providerPaymentId, "refundReference", reference, "amountMinor", amountMinor))
                    .retrieve().body(JsonNode.class).path("data");
            return new ProviderRefund(d.path("status").asText("UNKNOWN"), text(d, "providerRequestId"), text(d, "message"));
        } catch (Exception e) {
            return new ProviderRefund("UNKNOWN", null, "provider unreachable or unclear: " + e.getClass().getSimpleName());
        }
    }

    /** Provider trouble throws, so the caller keeps the refund PROCESSING and asks again later. */
    public ProviderRefundStatus refundStatus(String provider, String providerRequestId, String reference) {
        return call(() -> {
            JsonNode d = providers.post().uri("/internal/v1/providers/{p}/refunds/status", provider).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("providerRequestId", providerRequestId, "refundReference", reference))
                    .retrieve().body(JsonNode.class).path("data");
            return new ProviderRefundStatus(d.path("outcome").asText("PROCESSING"), text(d, "rawStatus"));
        });
    }

    private <T> T call(Supplier<T> r) {
        try {
            return r.get();
        } catch (HttpStatusCodeException e) {
            String code = "UPSTREAM_ERROR";
            try {
                JsonNode err = mapper.readTree(e.getResponseBodyAsString()).path("error");
                if (err.hasNonNull("code")) code = err.get("code").asText();
            } catch (Exception ignored) { }
            throw new ApiException(HttpStatus.valueOf(e.getStatusCode().value()), code, "Upstream error");
        } catch (ResourceAccessException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", "Dependency unavailable");
        }
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.path(f);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }
}
