package com.apg.payment.service;

import com.apg.common.api.ApiException;
import com.apg.payment.domain.*;
import com.apg.payment.domain.Repositories.*;
import com.apg.payment.web.InternalDtos.*;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Provider attempts. Starting an attempt and CREATED -> PENDING happen atomically, so two starters cannot both win. */
@Service
@Transactional
public class AttemptService {
    private final PaymentRepository payments;
    private final PaymentIntentRepository intents;
    private final AttemptRepository attempts;
    private final PaymentStateService states;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public AttemptService(PaymentRepository payments, PaymentIntentRepository intents, AttemptRepository attempts,
                          PaymentStateService states, Clock clock) {
        this.payments = payments;
        this.intents = intents;
        this.attempts = attempts;
        this.states = states;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public InternalPayment payment(UUID id) {
        Payment p = payments.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "Payment not found"));
        return view(p);
    }

    public AttemptView begin(UUID paymentId, String provider) {
        Payment p = payments.findById(paymentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "Payment not found"));
        if (p.getStatus() != PaymentStatus.CREATED) {
            throw new ApiException(HttpStatus.CONFLICT, "PAYMENT_NOT_STARTABLE",
                    "Payment is " + p.getStatus() + "; checkout can only start from CREATED");
        }
        int no = attempts.findFirstByPaymentIdOrderByAttemptNoDesc(paymentId).map(a -> a.getAttemptNo() + 1).orElse(1);
        String reference = "A" + HexFormat.of().formatHex(randomBytes(10));
        PaymentAttempt a = new PaymentAttempt(paymentId, no, provider, reference, clock.instant());
        try {
            attempts.saveAndFlush(a);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "ATTEMPT_CONFLICT", "Another attempt was started concurrently");
        }
        p.assignProvider(provider, null, clock.instant());
        states.transition(paymentId, PaymentStatus.PENDING, "attempt " + no + " started with " + provider, "ORCHESTRATOR");
        return attemptView(a, p);
    }

    @Transactional(readOnly = true)
    public AttemptView byReference(String reference) {
        PaymentAttempt a = attempts.findByProviderReference(reference)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ATTEMPT_NOT_FOUND", "Attempt not found"));
        return attemptView(a, payments.findById(a.getPaymentId()).orElseThrow());
    }

    public AttemptView update(String reference, PaymentAttempt.Status status, String providerPaymentId, String failureCode) {
        PaymentAttempt a = attempts.findByProviderReference(reference)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ATTEMPT_NOT_FOUND", "Attempt not found"));
        Payment p = payments.findById(a.getPaymentId()).orElseThrow();
        a.update(status, providerPaymentId, failureCode, clock.instant());
        if (providerPaymentId != null) p.assignProvider(a.getProvider(), providerPaymentId, clock.instant());
        return attemptView(a, p);
    }

    /** Work list for the verification worker: payments stuck in a non-final state. */
    @Transactional(readOnly = true)
    public List<PendingItem> pending(Set<PaymentStatus> statuses, long olderThanSeconds, int limit) {
        var before = clock.instant().minus(Duration.ofSeconds(olderThanSeconds));
        return payments.findByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(statuses, before,
                        PageRequest.of(0, Math.min(Math.max(limit, 1), 200))).stream()
                .map(p -> {
                    var a = attempts.findFirstByPaymentIdOrderByAttemptNoDesc(p.getId());
                    return new PendingItem(p.getId(), p.getStatus().name(), p.getUpdatedAt(), p.getProvider(),
                            a.map(PaymentAttempt::getProviderReference).orElse(null));
                }).toList();
    }

    private InternalPayment view(Payment p) {
        PaymentIntent i = intents.findById(p.getIntentId()).orElseThrow();
        return new InternalPayment(p.getId(), p.getMerchantId(), p.getOrderId(), p.getAmountMinor(), p.getCurrency(),
                p.getStatus().name(), p.getProvider(), p.getProviderPaymentId(), i.getPaymentMethod(), i.getReturnUrl(),
                i.getDescription(), i.getCustomerName(), i.getCustomerEmail(), i.getCustomerPhone(), p.getUpdatedAt());
    }

    private static AttemptView attemptView(PaymentAttempt a, Payment p) {
        return new AttemptView(a.getId(), p.getId(), p.getMerchantId(), a.getAttemptNo(), a.getProvider(),
                a.getProviderReference(), a.getProviderPaymentId(), a.getStatus().name(), p.getAmountMinor(),
                p.getCurrency(), p.getStatus().name());
    }

    private byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return b;
    }
}
