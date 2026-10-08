package com.apg.order.service;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "apg.order")
public record OrderProperties(Set<String> allowedCurrencies, long maxAmountMinor) {}
