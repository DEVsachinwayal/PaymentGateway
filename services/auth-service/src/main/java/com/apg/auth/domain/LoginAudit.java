package com.apg.auth.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "login_audit")
public class LoginAudit {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private UUID userId;
    private String email;
    private boolean success;
    private String reason;
    private String ip;
    private Instant createdAt;

    protected LoginAudit() {}

    public LoginAudit(UUID userId, String email, boolean success, String reason, String ip, Instant now) {
        this.userId = userId;
        this.email = email == null ? null : email.substring(0, Math.min(254, email.length()));
        this.success = success;
        this.reason = reason;
        this.ip = ip;
        this.createdAt = now;
    }

    public boolean isSuccess() { return success; }
    public String getReason() { return reason; }
}
