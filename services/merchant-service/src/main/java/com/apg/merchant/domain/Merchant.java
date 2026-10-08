package com.apg.merchant.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "merchants")
public class Merchant {
    @Id private UUID id;
    private String name;
    private String legalName;
    private String supportEmail;
    private String status = "SANDBOX";
    private Instant createdAt;
    private Instant updatedAt;
    @Version private long version;

    protected Merchant() {}

    public Merchant(String name, String legalName, String supportEmail, Instant now) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.legalName = legalName;
        this.supportEmail = supportEmail;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getLegalName() { return legalName; }
    public String getSupportEmail() { return supportEmail; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public boolean isActive() { return "ACTIVE".equals(status); }
    public boolean isSuspended() { return "SUSPENDED".equals(status); }
}
