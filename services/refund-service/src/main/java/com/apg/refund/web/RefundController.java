package com.apg.refund.web;

import com.apg.common.api.ApiException;
import com.apg.common.api.ApiResponse;
import com.apg.common.idempotency.IdempotencyService;
import com.apg.common.idempotency.IdempotencyService.Outcome;
import com.apg.common.web.CallerContext;
import com.apg.refund.service.RefundService;
import com.apg.refund.service.RefundService.CreateRefundRequest;
import com.apg.refund.service.RefundService.PageView;
import com.apg.refund.service.RefundService.RefundView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Merchant API. API keys may create and read refunds; dashboard users need MERCHANT_ADMIN to create. */
@RestController
@RequestMapping("/api/v1/refunds")
public class RefundController {
    private final RefundService refunds;
    private final IdempotencyService idempotency;
    private final ObjectMapper mapper;

    public RefundController(RefundService refunds, IdempotencyService idempotency, ObjectMapper mapper) {
        this.refunds = refunds;
        this.idempotency = idempotency;
        this.mapper = mapper;
    }

    private UUID merchantOf(CallerContext c, boolean write) {
        if (c.isUser()) {
            if (write) c.requireAnyRole("MERCHANT_ADMIN");
            else c.requireAnyRole("MERCHANT_ADMIN", "MERCHANT_DEVELOPER");
        } else if (!"API_KEY".equals(c.authType())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Authentication required");
        }
        return c.requireMerchant();
    }

    /** 202: the refund is recorded; the provider outcome arrives asynchronously (poll GET or use webhooks). */
    @PostMapping
    ResponseEntity<ApiResponse<JsonNode>> create(CallerContext c,
                                                 @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                 @Valid @RequestBody CreateRefundRequest body) throws Exception {
        UUID merchantId = merchantOf(c, true);
        Outcome out = idempotency.execute(merchantId, key, body, 202, () -> refunds.create(merchantId, body));
        JsonNode view = mapper.readTree(out.body());
        if (!out.replay()) refunds.afterCreate(UUID.fromString(view.path("id").asText()));
        ResponseEntity.BodyBuilder b = ResponseEntity.status(out.status());
        if (out.replay()) b.header("Idempotent-Replayed", "true");
        return b.body(ApiResponse.ok(view));
    }

    @GetMapping("/{id}")
    ApiResponse<RefundView> get(CallerContext c, @PathVariable UUID id) {
        return ApiResponse.ok(refunds.get(merchantOf(c, false), id));
    }

    @GetMapping
    ApiResponse<PageView<RefundView>> list(CallerContext c, @RequestParam(required = false) UUID paymentId,
                                           @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(refunds.list(merchantOf(c, false), paymentId, page, size));
    }
}
