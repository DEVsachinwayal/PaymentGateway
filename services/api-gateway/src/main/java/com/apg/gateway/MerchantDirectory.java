package com.apg.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
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
    /**
     * Short-lived cache of POSITIVE answers only (found in the load test: verifying the key cost two database reads and an extra hop
     * on every API call). Keyed by SHA-256 of the key so raw secrets are never held. Trade-off: a revoked or suspended key keeps
     * working for at most the TTL (default 5 s, 0 disables). Invalid keys are never cached.
     */
    private final Cache<String, ApiKeyIdentity> keyCache;
    private final Cache<String, String> userMerchantCache;

    public MerchantDirectory(GatewayProperties props, WebClient.Builder builder,
                             @Value("${apg.gateway.api-key-cache-ttl:5s}") java.time.Duration keyTtl,
                             @Value("${apg.gateway.user-merchant-cache-ttl:30s}") java.time.Duration userTtl) {
        this.client = builder.baseUrl(props.merchantUri())
                .defaultHeader("X-Internal-Token", props.internalToken()).build();
        this.keyCache = keyTtl.isZero() ? null : Caffeine.newBuilder().expireAfterWrite(keyTtl).maximumSize(10_000).build();
        this.userMerchantCache = userTtl.isZero() ? null : Caffeine.newBuilder().expireAfterWrite(userTtl).maximumSize(10_000).build();
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Empty = key invalid. Errors other than 401 propagate (caller maps to 503). */
    public Mono<Optional<ApiKeyIdentity>> verifyApiKey(String apiKey) {
        String cacheKey = keyCache == null ? null : sha256(apiKey);
        if (cacheKey != null) {
            ApiKeyIdentity hit = keyCache.getIfPresent(cacheKey);
            if (hit != null) return Mono.just(Optional.of(hit));
        }
        return verifyRemote(apiKey).doOnNext(o -> {
            if (cacheKey != null) o.ifPresent(v -> keyCache.put(cacheKey, v));
        });
    }

    private Mono<Optional<ApiKeyIdentity>> verifyRemote(String apiKey) {
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
        if (userMerchantCache != null) {
            String hit = userMerchantCache.getIfPresent(userId);
            if (hit != null) return Mono.just(Optional.of(hit));
        }
        return merchantForUserRemote(userId).doOnNext(o -> {
            if (userMerchantCache != null) o.ifPresent(m -> userMerchantCache.put(userId, m));       // "no merchant yet" is never cached
        });
    }

    private Mono<Optional<String>> merchantForUserRemote(String userId) {
        return client.get().uri("/internal/v1/users/{id}/merchant", userId)
                .retrieve().bodyToMono(JsonNode.class)
                .map(n -> Optional.of(n.at("/data/merchantId").asText()))
                .onErrorResume(WebClientResponseException.class, e ->
                        e.getStatusCode().equals(HttpStatus.NOT_FOUND) ? Mono.just(Optional.empty()) : Mono.error(e))
                .timeout(Duration.ofSeconds(3));
    }
}
