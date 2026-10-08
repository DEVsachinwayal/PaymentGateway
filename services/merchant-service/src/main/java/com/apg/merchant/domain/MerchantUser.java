package com.apg.merchant.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "merchant_users")
public class MerchantUser {
    @Id private UUID id;
    private UUID merchantId;
    private UUID userId;
    private String role;
    private Instant createdAt;

    protected MerchantUser() {}

    public MerchantUser(UUID merchantId, UUID userId, String role, Instant now) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.userId = userId;
        this.role = role;
        this.createdAt = now;
    }

    public UUID getMerchantId() { return merchantId; }
    public UUID getUserId() { return userId; }
}
