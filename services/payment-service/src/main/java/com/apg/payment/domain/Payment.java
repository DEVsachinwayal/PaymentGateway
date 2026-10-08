package com.apg.payment.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class Payment {
    @Id private UUID id;
    private UUID intentId;
    private UUID merchantId;
    private UUID orderId;
    private long amountMinor;
    private String currency;
    @Enumerated(EnumType.STRING) private PaymentStatus status = PaymentStatus.CREATED;
    private long refundedAmountMinor;
    private String provider;
    private String providerPaymentId;
    private Instant createdAt;
    private Instant updatedAt;
    @Version private long version;

    protected Payment() {}

    public Payment(UUID intentId, UUID merchantId, UUID orderId, long amountMinor, String currency, Instant now) {
        this.id = UUID.randomUUID();
        this.intentId = intentId;
        this.merchantId = merchantId;
        this.orderId = orderId;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Only PaymentStateService may call this (it validates the transition); never call from controllers. */
    public void applyStatus(PaymentStatus next, Instant now) {
        this.status = next;
        this.updatedAt = now;
    }

    public void assignProvider(String provider, String providerPaymentId, Instant now) {
        this.provider = provider;
        this.providerPaymentId = providerPaymentId;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getIntentId() { return intentId; }
    public UUID getMerchantId() { return merchantId; }
    public UUID getOrderId() { return orderId; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public PaymentStatus getStatus() { return status; }
    public long getRefundedAmountMinor() { return refundedAmountMinor; }
    public String getProvider() { return provider; }
    public String getProviderPaymentId() { return providerPaymentId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
