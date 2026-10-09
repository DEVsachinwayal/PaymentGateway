package com.apg.webhook.client;

import com.apg.common.api.ApiException;
import com.apg.webhook.service.WebhookProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** Calls to provider-integration (callback verification), orchestrator (verify) and payment-service (read-only). */
@Component
public class Peers {
    public record Verification(boolean authentic, String reason, String providerReference, String providerPaymentId,
                               String status, Long amountMinor) {}

    public record PaymentView(String paymentId, String status, String returnUrl) {}

    private final RestClient providers;
    private final RestClient orchestrator;
    private final RestClient payments;
    private final ObjectMapper mapper;

    public Peers(WebhookProperties props, @Value("${apg.internal.token}") String token, ObjectMapper mapper) {
        this.mapper = mapper;
        this.providers = build(props.providerServiceUri(), token, 10);
        this.orchestrator = build(props.orchestratorUri(), token, 30);
        this.payments = build(props.paymentServiceUri(), token, 5);
    }

    private static RestClient build(String base, String token, int readSeconds) {
        JdkClientHttpRequestFactory f = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        f.setReadTimeout(Duration.ofSeconds(readSeconds));
        return RestClient.builder().requestInterceptor(com.apg.common.web.OutboundHeaders.INSTANCE).baseUrl(base).requestFactory(f).defaultHeader("X-Internal-Token", token).build();
    }

    public Verification verifyCallback(String provider, Map<String, String> fields) {
        return call(() -> {
            JsonNode d = providers.post().uri("/internal/v1/providers/{p}/callbacks/verify", provider)
                    .contentType(MediaType.APPLICATION_JSON).body(Map.of("fields", fields)).retrieve().body(JsonNode.class).path("data");
            return new Verification(d.path("authentic").asBoolean(false), text(d, "reason"), text(d, "providerReference"),
                    text(d, "providerPaymentId"), text(d, "status"), d.hasNonNull("amountMinor") ? d.get("amountMinor").asLong() : null);
        });
    }

    /** @return the orchestrator's verification result name, e.g. SETTLED_SUCCESS */
    public String orchestratorVerify(String reference) {
        return call(() -> orchestrator.post().uri("/internal/v1/orchestrator/attempts/{r}/verify", reference)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("trigger", "WEBHOOK"))
                .retrieve().body(JsonNode.class).path("data").path("result").asText());
    }

    /** Best effort lookup used only to tell the customer where to go next. */
    public PaymentView paymentForAttempt(String reference) {
        try {
            JsonNode a = payments.get().uri("/internal/v1/attempts/{r}", reference).retrieve().body(JsonNode.class).path("data");
            String paymentId = a.path("paymentId").asText();
            JsonNode p = payments.get().uri("/internal/v1/payments/{id}", paymentId).retrieve().body(JsonNode.class).path("data");
            return new PaymentView(paymentId, p.path("status").asText(), text(p, "returnUrl"));
        } catch (Exception e) {
            return null;
        }
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
