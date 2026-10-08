package com.apg.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/** Calls merchant-service internal endpoints: API-key verification and user -> merchant lookup. */
@Component
public class MerchantDirectory {
    public record ApiKeyIdentity(String merchantId, String apiKeyId, String mode) {}

    private final WebClient client;

    public MerchantDirectory(GatewayProperties props, WebClient.Builder builder) {
        this.client = builder.baseUrl(props.merchantUri())
                .defaultHeader("X-Internal-Token", props.internalToken()).build();
    }

    /** Empty = key invalid. Errors other than 401 propagate (caller maps to 503). */
    public Mono<Optional<ApiKeyIdentity>> verifyApiKey(String apiKey) {
        return client.post().uri("/internal/v1/api-keys/verify")
                .bodyValue(Map.of("apiKey", apiKey))
                .retrieve().bodyToMono(JsonNode.class)
                .map(n -> Optional.of(new ApiKeyIdentity(n.at("/data/merchantId").asText(),
                        n.at("/data/apiKeyId").asText(), n.at("/data/mode").asText())))
                .onErrorResume(WebClientResponseException.class, e ->
                        e.getStatusCode().equals(HttpStatus.UNAUTHORIZED) ? Mono.just(Optional.empty()) : Mono.error(e))
                .timeout(Duration.ofSeconds(3));
    }

    /** Empty = the user has no merchant yet. */
    public Mono<Optional<String>> merchantForUser(String userId) {
        return client.get().uri("/internal/v1/users/{id}/merchant", userId)
                .retrieve().bodyToMono(JsonNode.class)
                .map(n -> Optional.of(n.at("/data/merchantId").asText()))
                .onErrorResume(WebClientResponseException.class, e ->
                        e.getStatusCode().equals(HttpStatus.NOT_FOUND) ? Mono.just(Optional.empty()) : Mono.error(e))
                .timeout(Duration.ofSeconds(3));
    }
}
