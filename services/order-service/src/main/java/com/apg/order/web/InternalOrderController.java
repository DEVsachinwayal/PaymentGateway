package com.apg.order.web;

import com.apg.common.api.ApiException;
import com.apg.common.api.ApiResponse;
import com.apg.order.domain.Repositories.OrderRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Service-to-service lookup (payment-service). Not routed by the gateway; needs the internal token. */
@RestController
@RequestMapping("/internal/v1/orders")
public class InternalOrderController {
    public record InternalOrderView(UUID id, UUID merchantId, long amount, String currency, String status) {}

    private final OrderRepository orders;

    public InternalOrderController(OrderRepository orders) { this.orders = orders; }

    @GetMapping("/{id}")
    ApiResponse<InternalOrderView> get(@PathVariable UUID id, @RequestParam UUID merchantId) {
        return orders.findByIdAndMerchantId(id, merchantId)
                .map(o -> ApiResponse.ok(new InternalOrderView(o.getId(), o.getMerchantId(), o.getAmountMinor(),
                        o.getCurrency(), o.getStatus().name())))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order not found"));
    }
}
