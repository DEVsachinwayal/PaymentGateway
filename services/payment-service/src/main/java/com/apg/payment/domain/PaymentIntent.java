package com.apg.payment.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_intents")
public class PaymentIntent {
    @Id private UUID id;
    private UUID merchantId;
    private UUID orderId;
    private long amountMinor;
    private String currency;
    private String paymentMethod;
    private String returnUrl;
    private Instant createdAt;

    protected PaymentIntent() {}

    public PaymentIntent(UUID merchantId, UUID orderId, long amountMinor, String currency, String paymentMethod,
                         String returnUrl, Instant now) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.orderId = orderId;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.paymentMethod = paymentMethod;
        this.returnUrl = returnUrl;
        this.createdAt = now;
    }

    public UUID getId() { return id; }
    public String getPaymentMethod() { return paymentMethod; }
    public String getReturnUrl() { return returnUrl; }
}
