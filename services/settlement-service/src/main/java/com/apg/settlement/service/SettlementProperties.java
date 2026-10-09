package com.apg.settlement.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "apg.settlement")
public record SettlementProperties(String paymentServiceUri, String refundServiceUri, int maxItemsPerBatch, Rerun rerun) {
    public record Rerun(boolean enabled, Duration interval) {}
}
