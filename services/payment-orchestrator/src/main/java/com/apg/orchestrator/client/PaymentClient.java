package com.apg.orchestrator.client;

import com.apg.orchestrator.service.OrchestratorProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** payment-service internal API (payments, attempts, state transitions). */
@Component
public class PaymentClient extends InternalHttp {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Payment(UUID paymentId, UUID merchantId, UUID orderId, long amount, String currency, String status,
                          String provider, String providerPaymentId, String paymentMethod, String returnUrl,
                          String description, String customerName, String customerEmail, String customerPhone,
                          Instant updatedAt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Attempt(UUID attemptId, UUID paymentId, UUID merchantId, int attemptNo, String provider,
                          String providerReference, String providerPaymentId, String status, long amount, String currency,
                          String paymentStatus) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Pending(UUID paymentId, String paymentStatus, Instant updatedAt, String provider, String providerReference) {}

    private final ObjectMapper mapper;

    public PaymentClient(OrchestratorProperties props, @Value("${apg.internal.token}") String token, ObjectMapper mapper) {
        super(props.paymentServiceUri(), token, mapper, Duration.ofSeconds(5));
        this.mapper = mapper;
    }

    public Payment payment(UUID id) {
        return data("/internal/v1/payments/" + id, Payment.class);
    }

    public Attempt begin(UUID paymentId, String provider) {
        return call(() -> mapper.convertValue(client.post().uri("/internal/v1/payments/{id}/attempts", paymentId)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(Map.of("provider", provider))
                .retrieve().body(JsonNode.class).path("data"), Attempt.class));
    }

    public Attempt attempt(String reference) {
        return data("/internal/v1/attempts/" + reference, Attempt.class);
    }

    public void updateAttempt(String reference, String status, String providerPaymentId, String failureCode) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("status", status);
        if (providerPaymentId != null) body.put("providerPaymentId", providerPaymentId);
        if (failureCode != null) body.put("failureCode", failureCode);
        call(() -> client.patch().uri("/internal/v1/attempts/{ref}", reference)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity());
    }

    /** Idempotent on the payment-service side (same target status = no-op); invalid moves => 409. */
    public void transition(UUID paymentId, String to, String reason, String source) {
        call(() -> client.post().uri("/internal/v1/payments/{id}/transition", paymentId)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(Map.of("to", to, "reason", reason == null ? "" : reason, "source", source))
                .retrieve().toBodilessEntity());
    }

    public List<Pending> pending(String statuses, long olderThanSeconds, int limit) {
        return call(() -> {
            JsonNode n = client.get().uri("/internal/v1/payments/pending?statuses={s}&olderThanSeconds={o}&limit={l}",
                    statuses, olderThanSeconds, limit).retrieve().body(JsonNode.class);
            return mapper.convertValue(n.path("data"), mapper.getTypeFactory().constructCollectionType(List.class, Pending.class));
        });
    }

    private <T> T data(String path, Class<T> type) {
        return call(() -> mapper.convertValue(client.get().uri(path).retrieve().body(JsonNode.class).path("data"), type));
    }
}
