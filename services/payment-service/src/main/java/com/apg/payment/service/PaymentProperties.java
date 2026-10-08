package com.apg.payment.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "apg.payment")
public record PaymentProperties(String orderServiceUri) {}
