package com.apg.merchant.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "api_keys")
public class ApiKey {
    @Id private UUID id;
    private UUID merchantId;
    private String keyId;
    private String secretHash;
    private String label;
    private String mode;
    private String status = "ACTIVE";
    private Instant createdAt;
    private Instant expiresAt;
    private Instant revokedAt;
    private Instant lastUsedAt;
    private UUID rotatedFromId;

    protected ApiKey() {}

    public ApiKey(UUID merchantId, String keyId, String secretHash, String label, String mode, UUID rotatedFromId, Instant now) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.keyId = keyId;
        this.secretHash = secretHash;
        this.label = label;
        this.mode = mode;
        this.rotatedFromId = rotatedFromId;
        this.createdAt = now;
    }

    public boolean isUsable(Instant now) {
        return "ACTIVE".equals(status) && (expiresAt == null || expiresAt.isAfter(now));
    }

    public void revoke(Instant now) {
        if ("ACTIVE".equals(status)) {
            status = "REVOKED";
            revokedAt = now;
        }
    }

    public void expireAt(Instant when) { this.expiresAt = when; }

    public void touch(Instant now) { this.lastUsedAt = now; }

    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public String getKeyId() { return keyId; }
    public String getSecretHash() { return secretHash; }
    public String getLabel() { return label; }
    public String getMode() { return mode; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
}
