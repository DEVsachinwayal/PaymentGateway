package com.apg.refund.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "apg.refund")
public record RefundProperties(String providerServiceUri, String paymentServiceUri, Worker worker) {
    public record Worker(boolean enabled, Duration interval, Duration firstCheckDelay, Duration maxBackoff, int batchSize) {}
}
