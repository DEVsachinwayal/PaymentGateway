package com.apg.notification.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "apg.notification")
public record NotificationProperties(String merchantServiceUri, boolean allowInsecureUrls, int maxAttempts, Duration baseDelay,
                                     Duration maxDelay, Duration requestTimeout, Worker worker) {
    public record Worker(boolean enabled, Duration interval, int batchSize) {}
}
