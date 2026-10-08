package com.apg.payment.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_state_history")
public class StateHistory {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private UUID paymentId;
    @Enumerated(EnumType.STRING) private PaymentStatus fromStatus;
    @Enumerated(EnumType.STRING) private PaymentStatus toStatus;
    private String reason;
    private String source;
    private String correlationId;
    private Instant createdAt;

    protected StateHistory() {}

    public StateHistory(UUID paymentId, PaymentStatus from, PaymentStatus to, String reason, String source,
                        String correlationId, Instant now) {
        this.paymentId = paymentId;
        this.fromStatus = from;
        this.toStatus = to;
        this.reason = reason == null ? null : reason.substring(0, Math.min(300, reason.length()));
        this.source = source;
        this.correlationId = correlationId;
        this.createdAt = now;
    }

    public PaymentStatus getFromStatus() { return fromStatus; }
    public PaymentStatus getToStatus() { return toStatus; }
    public String getReason() { return reason; }
    public String getSource() { return source; }
    public Instant getCreatedAt() { return createdAt; }
}
