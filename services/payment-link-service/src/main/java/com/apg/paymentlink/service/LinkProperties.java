package com.apg.paymentlink.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "apg.paymentlink")
public record LinkProperties(String merchantServiceUri, String orderServiceUri, String paymentServiceUri, String orchestratorUri,
                             String publicBaseUrl, int defaultExpiryHours, int maxExpiryHours, long maxAmount,
                             Duration inFlightWindow, Duration startingWindow) {}
