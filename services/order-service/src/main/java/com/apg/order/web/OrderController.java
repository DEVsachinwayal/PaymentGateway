package com.apg.order.web;

import com.apg.common.api.ApiException;
import com.apg.common.api.ApiResponse;
import com.apg.common.idempotency.IdempotencyService;
import com.apg.common.idempotency.IdempotencyService.Outcome;
import com.apg.common.web.CallerContext;
import com.apg.order.service.OrderService;
import com.apg.order.web.OrderDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {
    private final OrderService orders;
    private final IdempotencyService idempotency;
    private final ObjectMapper mapper;

    public OrderController(OrderService orders, IdempotencyService idempotency, ObjectMapper mapper) {
        this.orders = orders;
        this.idempotency = idempotency;
        this.mapper = mapper;
    }

    /** API-key callers (merchant servers) or dashboard users with a merchant role. */
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
                                                 @Valid @RequestBody CreateOrderRequest body) throws Exception {
        UUID merchantId = merchantOf(c);
        Outcome out = idempotency.execute(merchantId, key, body, 201, () -> orders.create(merchantId, body));
        ResponseEntity.BodyBuilder b = ResponseEntity.status(out.status());
        if (out.replay()) b.header("Idempotent-Replayed", "true");
        return b.body(ApiResponse.ok(mapper.readTree(out.body())));
    }

    @GetMapping("/{id}")
    ApiResponse<OrderView> get(CallerContext c, @PathVariable UUID id) {
        return ApiResponse.ok(orders.get(merchantOf(c), id));
    }

    @GetMapping
    ApiResponse<PageView<OrderView>> list(CallerContext c, @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(orders.list(merchantOf(c), page, size));
    }
}
