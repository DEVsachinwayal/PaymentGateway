package com.apg.settlement.web;

import com.apg.common.api.ApiException;
import com.apg.common.api.ApiResponse;
import com.apg.common.web.CallerContext;
import com.apg.settlement.service.SettlementService;
import com.apg.settlement.service.SettlementService.MerchantSettlementView;
import com.apg.settlement.service.SettlementService.PageView;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Merchant-facing, read-only; dashboard users only (the gateway rejects API keys for this path). */
@RestController
@RequestMapping("/api/v1/settlements")
public class SettlementController {
    private final SettlementService service;

    public SettlementController(SettlementService service) {
        this.service = service;
    }

    private static UUID merchantOf(CallerContext c) {
        c.requireAnyRole("MERCHANT_ADMIN", "MERCHANT_DEVELOPER");
        return c.requireMerchant();
    }

    @GetMapping
    ApiResponse<PageView<MerchantSettlementView>> list(CallerContext c, @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.merchantSettlements(merchantOf(c), page, size));
    }

    @GetMapping("/{id}")
    ApiResponse<MerchantSettlementView> get(CallerContext c, @PathVariable UUID id) {
        return ApiResponse.ok(service.merchantSettlement(id, merchantOf(c))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SETTLEMENT_NOT_FOUND", "Settlement not found")));
    }
}
