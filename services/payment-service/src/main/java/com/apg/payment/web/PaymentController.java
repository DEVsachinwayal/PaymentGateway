package com.apg.payment.web;

import com.apg.common.api.ApiException;
import com.apg.common.api.ApiResponse;
import com.apg.common.idempotency.IdempotencyService;
import com.apg.common.idempotency.IdempotencyService.Outcome;
import com.apg.common.web.CallerContext;
import com.apg.payment.service.PaymentService;
import com.apg.payment.web.PaymentDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {
    private final PaymentService payments;
    private final IdempotencyService idempotency;
    private final ObjectMapper mapper;

    public PaymentController(PaymentService payments, IdempotencyService idempotency, ObjectMapper mapper) {
        this.payments = payments;
        this.idempotency = idempotency;
        this.mapper = mapper;
    }

    private UUID merchantOf(CallerContext c) {
        if (c.isUser()) {
            c.requireAnyRole("MERCHANT_ADMIN", "MERCHANT_DEVELOPER");
        } else if (!"API_KEY".equals(c.authType())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Authentication required");
        }
        return c.requireMerchant();
    }

    @PostMapping
    ResponseEntity<ApiResponse<JsonNode>> create(CallerContext c,
                                                 @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                 @Valid @RequestBody CreatePaymentRequest body) throws Exception {
        UUID merchantId = merchantOf(c);
        Outcome out = idempotency.execute(merchantId, key, body, 201, () -> payments.create(merchantId, body));
        ResponseEntity.BodyBuilder b = ResponseEntity.status(out.status());
        if (out.replay()) b.header("Idempotent-Replayed", "true");
        return b.body(ApiResponse.ok(mapper.readTree(out.body())));
    }

    @GetMapping("/{id}")
    ApiResponse<PaymentView> get(CallerContext c, @PathVariable UUID id) {
        return ApiResponse.ok(payments.get(merchantOf(c), id));
    }

    /** Status as recorded by the state machine (provider-verified updates arrive from M6/M7/M8). */
    @GetMapping("/{id}/status")
    ApiResponse<StatusView> status(CallerContext c, @PathVariable UUID id) {
        return ApiResponse.ok(payments.status(merchantOf(c), id));
    }

    @GetMapping
    ApiResponse<PageView<PaymentView>> list(CallerContext c, @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(payments.list(merchantOf(c), page, size));
    }
}
