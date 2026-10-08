package com.apg.gateway;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "apg.gateway")
public record GatewayProperties(
        String jwtSecret,
        String internalToken,
        String authUri,
        String merchantUri,
        String orderUri,
        String paymentUri,
        String orchestratorUri,
        List<String> corsAllowedOrigins,
        long maxRequestBytes,
        RateLimit rateLimit) {

    public record RateLimit(int replenishPerSecond, int burst) {}

    public GatewayProperties {
        if (jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("apg.gateway.jwt-secret (JWT_SECRET) must be at least 32 bytes");
        }
        if (internalToken == null || internalToken.length() < 16) {
            throw new IllegalStateException("apg.gateway.internal-token (INTERNAL_SERVICE_TOKEN) must be at least 16 chars");
        }
    }
}
