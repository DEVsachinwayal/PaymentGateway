package com.apg.provider.web;

import com.apg.common.api.ApiException;
import com.apg.common.api.ApiResponse;
import com.apg.provider.api.PaymentProvider;
import com.apg.provider.api.ProviderException;
import com.apg.provider.api.ProviderModels.*;
import com.apg.provider.service.ProviderRegistry;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/** Internal API (token protected, not exposed by the gateway). The orchestrator and webhook service call this. */
@RestController
@RequestMapping("/internal/v1/providers")
public class ProviderController {
    public record ProviderInfo(String provider, boolean enabled, String mode, int priority, ProviderHealth health) {}

    private final ProviderRegistry registry;
    private final JdbcTemplate jdbc;

    public ProviderController(ProviderRegistry registry, JdbcTemplate jdbc) {
        this.registry = registry;
        this.jdbc = jdbc;
    }

    @GetMapping
    ApiResponse<List<ProviderInfo>> list() {
        List<ProviderInfo> out = jdbc.query("SELECT provider, enabled, mode, priority FROM provider_configs ORDER BY priority", (rs, i) -> {
            String id = rs.getString("provider");
            PaymentProvider p = registry.all().get(id);
            return new ProviderInfo(id, rs.getBoolean("enabled") && p != null, rs.getString("mode"), rs.getInt("priority"),
                    p == null ? new ProviderHealth(false, 0, "not loaded") : p.health());
        });
        return ApiResponse.ok(out);
    }

    @GetMapping("/{provider}/health")
    ApiResponse<ProviderHealth> health(@PathVariable String provider) {
        return ApiResponse.ok(registry.get(provider).health());
    }

    @PostMapping("/{provider}/checkout")
    ApiResponse<CheckoutInstructions> checkout(@PathVariable String provider, @Valid @RequestBody PaymentRequest r) {
        return ApiResponse.ok(guard(() -> registry.get(provider).createPayment(r)));
    }

    @GetMapping("/{provider}/payments/{reference}/status")
    ApiResponse<PaymentStatusResponse> status(@PathVariable String provider, @PathVariable String reference) {
        return ApiResponse.ok(guard(() -> registry.get(provider).getPaymentStatus(reference)));
    }

    @PostMapping("/{provider}/callbacks/verify")
    ApiResponse<ProviderWebhookResult> verify(@PathVariable String provider, @Valid @RequestBody ProviderWebhookRequest r) {
        return ApiResponse.ok(registry.get(provider).verifyWebhook(r));
    }

    @PostMapping("/{provider}/refunds")
    ApiResponse<RefundResponse> refund(@PathVariable String provider, @Valid @RequestBody RefundRequest r) {
        return ApiResponse.ok(registry.get(provider).refund(r));
    }

    @PostMapping("/{provider}/refunds/status")
    ApiResponse<RefundStatusResponse> refundStatus(@PathVariable String provider, @Valid @RequestBody RefundStatusRequest r) {
        return ApiResponse.ok(guard(() -> registry.get(provider).getRefundStatus(r)));
    }

    /** Provider trouble is reported as 502 so callers treat the money outcome as UNKNOWN, not as a failure. */
    private static <T> T guard(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (ProviderException e) {
            HttpStatus s = "UNSUPPORTED_CURRENCY".equals(e.code()) ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.BAD_GATEWAY;
            throw new ApiException(s, e.code(), e.getMessage());
        }
    }
}
