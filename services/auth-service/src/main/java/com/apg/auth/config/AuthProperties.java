package com.apg.auth.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "apg.auth")
public record AuthProperties(
        String jwtSecret,
        String jwtPrivateKey,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        int maxFailedAttempts,
        Duration lockDuration) {

    public AuthProperties {
        boolean asymmetric = jwtPrivateKey != null && !jwtPrivateKey.isBlank();
        boolean symmetric = jwtSecret != null && jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length >= 32;
        if (!asymmetric && !symmetric) {
            throw new IllegalStateException("set apg.auth.jwt-private-key (JWT_PRIVATE_KEY, RS256, recommended) or "
                    + "apg.auth.jwt-secret (JWT_SECRET, HS256, at least 32 bytes)");
        }
    }
}
