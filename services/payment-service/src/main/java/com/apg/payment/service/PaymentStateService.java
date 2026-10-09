package com.apg.payment.service;

import com.apg.common.api.ApiException;
import com.apg.common.web.CorrelationIdFilter;
import com.apg.payment.domain.*;
import com.apg.payment.domain.Repositories.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * THE only place where a payment's status changes. Every transition is validated centrally, recorded in
 * payment_state_history and announced through the transactional outbox (spec 7).
 */
@Service
@Transactional
public class PaymentStateService {
    private final PaymentRepository payments;
    private final StateHistoryRepository history;
    private final OutboxRepository outbox;
    private final ObjectMapper mapper;
    private final Clock clock;

    public PaymentStateService(PaymentRepository payments, StateHistoryRepository history, OutboxRepository outbox,
                               ObjectMapper mapper, Clock clock) {
        this.payments = payments;
        this.history = history;
        this.outbox = outbox;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Records the very first state (CREATED) of a new payment. */
    public void recordCreated(Payment p, String source) {
        Instant now = clock.instant();
        history.save(new StateHistory(p.getId(), null, PaymentStatus.CREATED, "payment created", source, correlationId(), now));
        publish("payment.created", p, now);
    }

    /**
     * Idempotent: asking for the status the payment already has is a no-op (webhook/verification replays are safe).
     * Invalid moves fail with 409 INVALID_STATE_TRANSITION. Concurrent writers: one wins, the other gets 409
     * CONCURRENT_MODIFICATION and must re-read before deciding again.
     */
    public Payment transition(UUID paymentId, PaymentStatus to, String reason, String source) {
        Payment p = payments.findById(paymentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "Payment not found"));
        PaymentStatus from = p.getStatus();
        if (from == to) return p;
        if (!from.canTransitionTo(to)) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION",
                    "Cannot move payment from " + from + " to " + to);
        }
        Instant now = clock.instant();
        p.applyStatus(to, now);
        try {
            payments.saveAndFlush(p);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new ApiException(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION", "Payment was modified concurrently; retry");
        }
        history.save(new StateHistory(p.getId(), from, to, reason, source, correlationId(), now));
        String event = switch (to) {
            case PROCESSING -> "payment.processing";
            case SUCCESS -> "payment.success";
            case FAILED -> "payment.failed";
            case VERIFY_REQUIRED -> "payment.unknown";
            case REFUND_PENDING -> "payment.refund_pending";
            case REFUNDED -> "payment.refunded";
            case PARTIALLY_REFUNDED -> "payment.partially_refunded";
            default -> null;
        };
        if (event != null) publish(event, p, now);
        return p;
    }

    private void publish(String type, Payment p, Instant now) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paymentId", p.getId());
        payload.put("orderId", p.getOrderId());
        payload.put("amount", p.getAmountMinor());
        payload.put("currency", p.getCurrency());
        payload.put("status", p.getStatus().name());
        if (p.getProvider() != null) payload.put("provider", p.getProvider());
        UUID eventId = UUID.randomUUID();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", type);
        envelope.put("version", 1);
        envelope.put("occurredAt", now.toString());
        envelope.put("correlationId", correlationId());
        envelope.put("aggregateId", p.getId());
        envelope.put("merchantId", p.getMerchantId());
        envelope.put("payload", payload);
        try {
            outbox.save(new OutboxEvent(eventId, type, p.getId(), p.getMerchantId(), correlationId(),
                    mapper.writeValueAsString(envelope), now));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String correlationId() {
        return MDC.get(CorrelationIdFilter.MDC_KEY);
    }
}
