package com.apg.orchestrator.client;

import com.apg.orchestrator.service.OrchestratorProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/** provider-integration internal API. 502 from it means "provider trouble => outcome unknown". */
@Component
public class ProviderClient extends InternalHttp {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Health(boolean up, long latencyMs, String detail) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProviderInfo(String provider, boolean enabled, String mode, int priority, Health health) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Checkout(String method, String url, Map<String, String> fields) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProviderStatus(String status, String providerPaymentId, Long amountMinor, String rawStatus,
                                 String errorCode, String errorMessage) {}

    private final ObjectMapper mapper;

    public ProviderClient(OrchestratorProperties props, @Value("${apg.internal.token}") String token, ObjectMapper mapper) {
        super(props.providerServiceUri(), token, mapper, Duration.ofSeconds(20));
        this.mapper = mapper;
    }

    public List<ProviderInfo> providers() {
        return call(() -> mapper.convertValue(client.get().uri("/internal/v1/providers").retrieve().body(JsonNode.class).path("data"),
                mapper.getTypeFactory().constructCollectionType(List.class, ProviderInfo.class)));
    }

    public Checkout checkout(String provider, Map<String, Object> paymentRequest) {
        return call(() -> mapper.convertValue(client.post().uri("/internal/v1/providers/{p}/checkout", provider)
                .contentType(MediaType.APPLICATION_JSON).body(paymentRequest).retrieve().body(JsonNode.class).path("data"), Checkout.class));
    }

    public ProviderStatus status(String provider, String reference) {
        return call(() -> mapper.convertValue(client.get().uri("/internal/v1/providers/{p}/payments/{r}/status", provider, reference)
                .retrieve().body(JsonNode.class).path("data"), ProviderStatus.class));
    }
}
