package com.apg.payment.web;

import com.apg.common.api.ApiResponse;
import com.apg.payment.domain.Payment;
import com.apg.payment.domain.PaymentStatus;
import com.apg.payment.service.AttemptService;
import com.apg.payment.service.PaymentStateService;
import com.apg.payment.web.InternalDtos.*;
import com.apg.payment.web.PaymentDtos.TransitionRequest;
import jakarta.validation.Valid;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/**
 * Used by the orchestrator, webhook and verification workers (never exposed through the gateway).
 * Merchants/clients can never set a status: this is the only entry, and it still goes through the state machine.
 */
@RestController
@RequestMapping("/internal/v1")
public class InternalPaymentController {
    private final PaymentStateService states;
    private final AttemptService attempts;

    public InternalPaymentController(PaymentStateService states, AttemptService attempts) {
        this.states = states;
        this.attempts = attempts;
    }

    @GetMapping("/payments/{id}")
    ApiResponse<InternalPayment> payment(@PathVariable UUID id) {
        return ApiResponse.ok(attempts.payment(id));
    }

    @PostMapping("/payments/{id}/transition")
    ApiResponse<Map<String, Object>> transition(@PathVariable UUID id, @Valid @RequestBody TransitionRequest r) {
        Payment p = states.transition(id, r.to(), r.reason(), r.source());
        return ApiResponse.ok(Map.of("paymentId", p.getId(), "status", p.getStatus().name()));
    }

    /** Starts a provider attempt and moves the payment CREATED -> PENDING atomically. */
    @PostMapping("/payments/{id}/attempts")
    ApiResponse<AttemptView> begin(@PathVariable UUID id, @Valid @RequestBody BeginAttemptRequest r) {
        return ApiResponse.ok(attempts.begin(id, r.provider()));
    }

    @GetMapping("/attempts/{reference}")
    ApiResponse<AttemptView> attempt(@PathVariable String reference) {
        return ApiResponse.ok(attempts.byReference(reference));
    }

    @PatchMapping("/attempts/{reference}")
    ApiResponse<AttemptView> updateAttempt(@PathVariable String reference, @Valid @RequestBody UpdateAttemptRequest r) {
        return ApiResponse.ok(attempts.update(reference, r.status(), r.providerPaymentId(), r.failureCode()));
    }

    /** Verification worker work-list (e.g. statuses=PROCESSING,VERIFY_REQUIRED&olderThanSeconds=120). */
    @GetMapping("/payments/pending")
    ApiResponse<List<PendingItem>> pending(@RequestParam(defaultValue = "PROCESSING,VERIFY_REQUIRED") Set<PaymentStatus> statuses,
                                           @RequestParam(defaultValue = "120") long olderThanSeconds,
                                           @RequestParam(defaultValue = "50") int limit) {
        Set<PaymentStatus> s = statuses.isEmpty() ? EnumSet.of(PaymentStatus.VERIFY_REQUIRED) : statuses;
        return ApiResponse.ok(attempts.pending(s, olderThanSeconds, limit));
    }
}
