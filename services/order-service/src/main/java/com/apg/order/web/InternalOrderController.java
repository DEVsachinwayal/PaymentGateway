package com.apg.order.web;

import com.apg.common.api.ApiException;
import com.apg.common.api.ApiResponse;
import com.apg.order.domain.Customer;
import com.apg.order.domain.Repositories.CustomerRepository;
import com.apg.order.domain.Repositories.OrderRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Service-to-service lookup (payment-service). Not routed by the gateway; needs the internal token. */
@RestController
@RequestMapping("/internal/v1/orders")
public class InternalOrderController {
    public record InternalOrderView(UUID id, UUID merchantId, long amount, String currency, String status,
                                    String description, String customerName, String customerEmail, String customerPhone) {}

    private final OrderRepository orders;
    private final CustomerRepository customers;

    public InternalOrderController(OrderRepository orders, CustomerRepository customers) {
        this.orders = orders;
        this.customers = customers;
    }

    @GetMapping("/{id}")
    ApiResponse<InternalOrderView> get(@PathVariable UUID id, @RequestParam UUID merchantId) {
        return orders.findByIdAndMerchantId(id, merchantId).map(o -> {
            Customer c = o.getCustomerId() == null ? null : customers.findById(o.getCustomerId()).orElse(null);
            return ApiResponse.ok(new InternalOrderView(o.getId(), o.getMerchantId(), o.getAmountMinor(), o.getCurrency(),
                    o.getStatus().name(), o.getDescription(),
                    c == null ? null : c.getName(), c == null ? null : c.getEmail(), c == null ? null : c.getPhone()));
        }).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order not found"));
    }
}
