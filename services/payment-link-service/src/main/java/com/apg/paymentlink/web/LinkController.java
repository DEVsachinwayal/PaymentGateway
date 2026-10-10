package com.apg.paymentlink.web;

import com.apg.common.api.ApiException;
import com.apg.common.api.ApiResponse;
import com.apg.common.idempotency.IdempotencyService;
import com.apg.common.idempotency.IdempotencyService.Outcome;
import com.apg.common.web.CallerContext;
import com.apg.paymentlink.service.LinkService;
import com.apg.paymentlink.service.LinkService.CreateRequest;
import com.apg.paymentlink.service.LinkService.LinkView;
import com.apg.paymentlink.service.LinkService.PageView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Merchant API. API keys (merchant servers) and dashboard users; creating/cancelling needs MERCHANT_ADMIN for users. */
@RestController
@RequestMapping("/api/v1/payment-links")
public class LinkController {
    private static final Set<String> STATUSES = Set.of("ACTIVE", "PAID", "EXPIRED", "CANCELLED");
    private final LinkService links;
    private final IdempotencyService idempotency;
    private final ObjectMapper mapper;

    public LinkController(LinkService links, IdempotencyService idempotency, ObjectMapper mapper) {
        this.links = links;
        this.idempotency = idempotency;
        this.mapper = mapper;
    }

    private static UUID merchantOf(CallerContext c, boolean write) {
        if (c.isUser()) {
            if (write) c.requireAnyRole("MERCHANT_ADMIN");
            else c.requireAnyRole("MERCHANT_ADMIN", "MERCHANT_DEVELOPER");
        } else if (!"API_KEY".equals(c.authType())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Authentication required");
        }
        return c.requireMerchant();
    }

    @PostMapping
    @org.springframework.transaction.annotation.Transactional
    ResponseEntity<ApiResponse<JsonNode>> create(CallerContext c, @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                 @Valid @RequestBody CreateRequest body) throws Exception {
        UUID merchantId = merchantOf(c, true);
        Outcome out = idempotency.execute(merchantId, key, body, 201, () -> links.create(merchantId, body, c));
        ResponseEntity.BodyBuilder b = ResponseEntity.status(out.status());
        if (out.replay()) b.header("Idempotent-Replayed", "true");
        return b.body(ApiResponse.ok(mapper.readTree(out.body())));
    }

    @GetMapping
    ApiResponse<PageView<LinkView>> list(CallerContext c, @RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        UUID m = merchantOf(c, false);
        if (status != null && !STATUSES.contains(status)) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_STATUS", "Unknown status");
        return ApiResponse.ok(links.list(m, status, page, size));
    }

    @GetMapping("/{id}")
    ApiResponse<LinkView> get(CallerContext c, @PathVariable UUID id) {
        return ApiResponse.ok(links.get(merchantOf(c, false), id));
    }

    @PostMapping("/{id}/cancel")
    @org.springframework.transaction.annotation.Transactional
    ApiResponse<LinkView> cancel(CallerContext c, @PathVariable UUID id) {
        return ApiResponse.ok(links.cancel(merchantOf(c, true), id, c));
    }
}
