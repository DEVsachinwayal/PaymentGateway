package com.apg.orchestrator.web;

import com.apg.common.api.ApiException;
import com.apg.common.api.ApiResponse;
import com.apg.common.idempotency.IdempotencyService;
import com.apg.common.idempotency.IdempotencyService.Outcome;
import com.apg.common.web.CallerContext;
import com.apg.orchestrator.service.CheckoutService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class CheckoutController {
    private final CheckoutService checkout;
    private final IdempotencyService idempotency;
    private final ObjectMapper mapper;

    public CheckoutController(CheckoutService checkout, IdempotencyService idempotency, ObjectMapper mapper) {
        this.checkout = checkout;
        this.idempotency = idempotency;
        this.mapper = mapper;
    }

    /**
     * Starts the provider attempt and returns the hosted checkout URL the merchant sends the customer to.
     * Idempotent: the same Idempotency-Key returns the same URL instead of starting a second attempt.
     */
    @PostMapping("/api/v1/payments/{id}/checkout")
    ResponseEntity<ApiResponse<JsonNode>> start(CallerContext c, @PathVariable UUID id,
                                                @RequestHeader(value = "Idempotency-Key", required = false) String key) throws Exception {
        UUID merchantId = merchantOf(c);
        Outcome out = idempotency.execute(merchantId, key, Map.of("paymentId", id.toString()), 201,
                () -> checkout.start(merchantId, id));
        ResponseEntity.BodyBuilder b = ResponseEntity.status(out.status());
        if (out.replay()) b.header("Idempotent-Replayed", "true");
        return b.body(ApiResponse.ok(mapper.readTree(out.body())));
    }

    private static UUID merchantOf(CallerContext c) {
        if (c.isUser()) {
            c.requireAnyRole("MERCHANT_ADMIN", "MERCHANT_DEVELOPER");
        } else if (!"API_KEY".equals(c.authType())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Authentication required");
        }
        return c.requireMerchant();
    }
}
