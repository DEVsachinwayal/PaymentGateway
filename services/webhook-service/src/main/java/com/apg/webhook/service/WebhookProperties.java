package com.apg.webhook.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "apg.webhook")
public record WebhookProperties(String providerServiceUri, String orchestratorUri, String paymentServiceUri,
                                String defaultResultPath, int maxFields, int maxValueLength, Retry retry) {
    public record Retry(boolean enabled, Duration interval, int maxAttempts) {}
}
