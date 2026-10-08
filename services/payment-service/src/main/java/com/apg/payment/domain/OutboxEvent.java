package com.apg.payment.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {
    @Id private UUID id;
    private String eventType;
    private UUID aggregateId;
    private UUID merchantId;
    private String correlationId;
    private String payload;
    private Instant occurredAt;
    private Instant publishedAt;

    protected OutboxEvent() {}

    public OutboxEvent(UUID id, String eventType, UUID aggregateId, UUID merchantId, String correlationId,
                       String payload, Instant occurredAt) {
        this.id = id;
        this.eventType = eventType;
        this.aggregateId = aggregateId;
        this.merchantId = merchantId;
        this.correlationId = correlationId;
        this.payload = payload;
        this.occurredAt = occurredAt;
    }

    public UUID getId() { return id; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
}
