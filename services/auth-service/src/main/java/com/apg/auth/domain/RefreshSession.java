package com.apg.auth.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_sessions")
public class RefreshSession {
    @Id private UUID id;
    private UUID userId;
    private UUID familyId;
    private String tokenHash;
    private Instant expiresAt;
    private Instant revokedAt;
    private UUID replacedBy;
    private Instant createdAt;
    private String ip;
    private String userAgent;

    protected RefreshSession() {}

    public RefreshSession(UUID userId, UUID familyId, String tokenHash, Instant expiresAt, Instant now,
                          String ip, String userAgent) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.createdAt = now;
        this.ip = ip;
        this.userAgent = userAgent == null ? null : userAgent.substring(0, Math.min(300, userAgent.length()));
    }

    public boolean isRevoked() { return revokedAt != null; }
    public boolean isExpired(Instant now) { return !expiresAt.isAfter(now); }

    public void revoke(Instant now, UUID replacedBy) {
        if (revokedAt == null) {
            revokedAt = now;
            this.replacedBy = replacedBy;
        }
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getFamilyId() { return familyId; }
}
