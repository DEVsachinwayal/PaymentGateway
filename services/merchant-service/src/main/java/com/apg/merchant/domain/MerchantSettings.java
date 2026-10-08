package com.apg.merchant.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "merchant_settings")
public class MerchantSettings {
    @Id private UUID merchantId;
    private String defaultCurrency = "INR";
    private String allowedReturnUrls = "";
    private Instant updatedAt;

    protected MerchantSettings() {}

    public MerchantSettings(UUID merchantId, Instant now) {
        this.merchantId = merchantId;
        this.updatedAt = now;
    }

    public void update(String currency, String returnUrls, Instant now) {
        this.defaultCurrency = currency;
        this.allowedReturnUrls = returnUrls;
        this.updatedAt = now;
    }

    public String getDefaultCurrency() { return defaultCurrency; }
    public String getAllowedReturnUrls() { return allowedReturnUrls; }
}
