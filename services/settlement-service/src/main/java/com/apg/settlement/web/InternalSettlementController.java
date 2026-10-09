package com.apg.settlement.web;

import com.apg.common.api.ApiResponse;
import com.apg.settlement.service.SettlementService;
import com.apg.settlement.service.SettlementService.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** Operations API (internal token, not routed by the gateway): import provider evidence, reconcile, work the exception queue. */
@RestController
@RequestMapping("/internal/v1")
public class InternalSettlementController {
    public record ExceptionUpdate(String status, @Size(max = 100) String owner, @Size(max = 500) String notes) {}

    public record PaidRequest(@Size(max = 100) String payoutReference) {}

    private final SettlementService service;

    public InternalSettlementController(SettlementService service) {
        this.service = service;
    }

    /** Idempotent per (provider, batchRef): same document => 200 with replayed=true; different document => 409. */
    @PostMapping("/settlements/import")
    ApiResponse<SettlementView> importBatch(@Valid @RequestBody ImportRequest r) {
        return ApiResponse.ok(service.importBatch(r));
    }

    @PostMapping("/settlements/{id}/reconcile")
    ApiResponse<SettlementView> reconcile(@PathVariable UUID id) {
        return ApiResponse.ok(service.reconcile(id));
    }

    @GetMapping("/settlements")
    ApiResponse<PageView<SettlementView>> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(page, size));
    }

    @GetMapping("/settlements/{id}")
    ApiResponse<SettlementView> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.view(id));
    }

    @GetMapping("/settlements/{id}/items")
    ApiResponse<List<ItemView>> items(@PathVariable UUID id) {
        return ApiResponse.ok(service.items(id));
    }

    @GetMapping("/exceptions")
    ApiResponse<List<ExceptionView>> exceptions(@RequestParam(required = false) String status, @RequestParam(required = false) UUID settlementId) {
        return ApiResponse.ok(service.exceptions(status, settlementId));
    }

    /** INVESTIGATING needs an owner; RESOLVED / WRITTEN_OFF need notes. Closed exceptions keep their evidence. */
    @PatchMapping("/exceptions/{id}")
    ApiResponse<ExceptionView> updateException(@PathVariable UUID id, @Valid @RequestBody ExceptionUpdate u) {
        return ApiResponse.ok(service.updateException(id, u.status(), u.owner(), u.notes()));
    }

    @PostMapping("/merchant-settlements/{id}/paid")
    ApiResponse<MerchantSettlementView> paid(@PathVariable UUID id, @Valid @RequestBody PaidRequest r) {
        return ApiResponse.ok(service.markPaid(id, r.payoutReference()));
    }
}
