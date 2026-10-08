package com.apg.payment.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** One try with one provider. {@code providerReference} is what the provider knows as our transaction id. */
@Entity
@Table(name = "payment_attempts")
public class PaymentAttempt {
    public enum Status { CREATED, REDIRECTED, SUCCESS, FAILED, UNKNOWN }

    @Id private UUID id;
    private UUID paymentId;
    private int attemptNo;
    private String provider;
    private String providerReference;
    private String providerPaymentId;
    @Enumerated(EnumType.STRING) private Status status = Status.CREATED;
    private String failureCode;
    private Instant createdAt;
    private Instant updatedAt;

    protected PaymentAttempt() {}

    public PaymentAttempt(UUID paymentId, int attemptNo, String provider, String providerReference, Instant now) {
        this.id = UUID.randomUUID();
        this.paymentId = paymentId;
        this.attemptNo = attemptNo;
        this.provider = provider;
        this.providerReference = providerReference;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(Status status, String providerPaymentId, String failureCode, Instant now) {
        this.status = status;
        if (providerPaymentId != null) this.providerPaymentId = providerPaymentId;
        if (failureCode != null) this.failureCode = failureCode;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getPaymentId() { return paymentId; }
    public int getAttemptNo() { return attemptNo; }
    public String getProvider() { return provider; }
    public String getProviderReference() { return providerReference; }
    public String getProviderPaymentId() { return providerPaymentId; }
    public Status getStatus() { return status; }
    public String getFailureCode() { return failureCode; }
}
