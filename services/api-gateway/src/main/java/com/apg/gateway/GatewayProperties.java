package com.apg.gateway;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "apg.gateway")
public record GatewayProperties(
        String jwtSecret,
        String jwtPublicKey,
        String internalToken,
        String authUri,
        String merchantUri,
        String orderUri,
        String paymentUri,
        String orchestratorUri,
        String webhookUri,
        String refundUri,
        String ledgerUri,
        String settlementUri,
        String notificationUri,
        String paymentLinkUri,
        List<String> corsAllowedOrigins,
        long maxRequestBytes,
        RateLimit rateLimit) {

    public record RateLimit(int replenishPerSecond, int burst) {}

    public GatewayProperties {
        boolean asymmetric = jwtPublicKey != null && !jwtPublicKey.isBlank();
        boolean symmetric = jwtSecret != null && jwtSecret.getBytes(StandardCharsets.UTF_8).length >= 32;
        if (!asymmetric && !symmetric) {
            throw new IllegalStateException("set apg.gateway.jwt-public-key (JWT_PUBLIC_KEY, RS256, recommended) or "
                    + "apg.gateway.jwt-secret (JWT_SECRET, HS256, at least 32 bytes)");
        }
        if (internalToken == null || internalToken.length() < 16) {
            throw new IllegalStateException("apg.gateway.internal-token (INTERNAL_SERVICE_TOKEN) must be at least 16 chars");
        }
    }
}
