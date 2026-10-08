package com.apg.merchant.config;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "apg.merchant")
public record MerchantProperties(
        String apiKeyPepper,
        String webhookSecretKey,
        boolean allowInsecureWebhookUrls,
        int rotationGraceHoursDefault) {

    public MerchantProperties {
        if (apiKeyPepper == null || apiKeyPepper.getBytes(StandardCharsets.UTF_8).length < 16) {
            throw new IllegalStateException("API_KEY_HASH_PEPPER must be at least 16 bytes");
        }
        if (webhookSecretKey == null || Base64.getDecoder().decode(webhookSecretKey).length != 32) {
            throw new IllegalStateException("WEBHOOK_SECRET_KEY must be base64 of exactly 32 bytes");
        }
    }
}
