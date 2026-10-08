package com.apg.order.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "orders")
public class Order {
    public enum Status { CREATED, PAID, CANCELLED, EXPIRED }

    @Id private UUID id;
    private UUID merchantId;
    private String merchantOrderRef;
    private UUID customerId;
    private long amountMinor;
    private String currency;
    @Enumerated(EnumType.STRING) private Status status = Status.CREATED;
    private String description;
    private String metadata = "{}";
    private Instant createdAt;
    private Instant updatedAt;
    @Version private long version;

    protected Order() {}

    public Order(UUID merchantId, String merchantOrderRef, UUID customerId, long amountMinor, String currency,
                 String description, String metadataJson, Instant now) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.merchantOrderRef = merchantOrderRef;
        this.customerId = customerId;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.description = description;
        this.metadata = metadataJson;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public String getMerchantOrderRef() { return merchantOrderRef; }
    public UUID getCustomerId() { return customerId; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public Status getStatus() { return status; }
    public String getDescription() { return description; }
    public String getMetadata() { return metadata; }
    public Instant getCreatedAt() { return createdAt; }
}
