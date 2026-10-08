package com.apg.provider.payu;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "apg.provider.payu")
public record PayUProperties(boolean enabled, int priority, String merchantKey, String merchantSalt, String baseUrl,
                             Duration connectTimeout, Duration readTimeout) {
    public PayUProperties {
        if (enabled && (merchantKey == null || merchantKey.isBlank() || merchantSalt == null || merchantSalt.isBlank())) {
            throw new IllegalStateException("PayU is enabled but PAYU_MERCHANT_KEY / PAYU_MERCHANT_SALT are not set");
        }
        if (baseUrl != null && baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
    }

    public boolean isTest() { return baseUrl != null && baseUrl.contains("test.payu.in"); }

    /** Never print secrets. */
    @Override
    public String toString() { return "PayUProperties[enabled=" + enabled + ", baseUrl=" + baseUrl + "]"; }
}
