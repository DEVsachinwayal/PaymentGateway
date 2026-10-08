package com.apg.payment.web;

import com.apg.common.api.ApiResponse;
import com.apg.payment.domain.Payment;
import com.apg.payment.service.PaymentStateService;
import com.apg.payment.web.PaymentDtos.TransitionRequest;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/**
 * Used by the orchestrator, webhook and verification workers (never exposed through the gateway).
 * Merchants/clients can never set a status: this is the only entry, and it still goes through the state machine.
 */
@RestController
@RequestMapping("/internal/v1/payments")
public class InternalPaymentController {
    private final PaymentStateService states;

    public InternalPaymentController(PaymentStateService states) { this.states = states; }

    @PostMapping("/{id}/transition")
    ApiResponse<Map<String, Object>> transition(@PathVariable UUID id, @Valid @RequestBody TransitionRequest r) {
        Payment p = states.transition(id, r.to(), r.reason(), r.source());
        return ApiResponse.ok(Map.of("paymentId", p.getId(), "status", p.getStatus().name()));
    }
}
