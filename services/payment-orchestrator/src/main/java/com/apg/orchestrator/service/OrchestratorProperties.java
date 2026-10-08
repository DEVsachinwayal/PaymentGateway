package com.apg.orchestrator.service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "apg.orchestrator")
public record OrchestratorProperties(String publicBaseUrl, String checkoutTokenSecret, Duration checkoutTokenTtl,
                                     String paymentServiceUri, String providerServiceUri, Verification verification) {

    public record Verification(boolean enabled, Duration interval, Duration minAge, Duration processingTimeout,
                               Duration abandonAfter) {}

    public OrchestratorProperties {
        if (checkoutTokenSecret == null || checkoutTokenSecret.getBytes(StandardCharsets.UTF_8).length < 16) {
            throw new IllegalStateException("CHECKOUT_TOKEN_SECRET must be at least 16 bytes");
        }
        if (publicBaseUrl != null && publicBaseUrl.endsWith("/")) publicBaseUrl = publicBaseUrl.substring(0, publicBaseUrl.length() - 1);
    }
}
