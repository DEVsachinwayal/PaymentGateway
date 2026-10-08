package com.apg.merchant.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "webhook_endpoints")
public class WebhookEndpoint {
    @Id private UUID id;
    private UUID merchantId;
    private String url;
    private String events;
    private String secretEnc;
    private String status = "ACTIVE";
    private Instant createdAt;

    protected WebhookEndpoint() {}

    public WebhookEndpoint(UUID merchantId, String url, String events, String secretEnc, Instant now) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.url = url;
        this.events = events;
        this.secretEnc = secretEnc;
        this.createdAt = now;
    }

    public void delete() { this.status = "DELETED"; }

    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public String getUrl() { return url; }
    public String getEvents() { return events; }
    public String getSecretEnc() { return secretEnc; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
