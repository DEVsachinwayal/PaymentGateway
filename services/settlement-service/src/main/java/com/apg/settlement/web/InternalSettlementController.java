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
    private final com.apg.common.audit.AuditLog audit;

    public InternalSettlementController(SettlementService service, com.apg.common.audit.AuditLog audit) {
        this.service = service;
        this.audit = audit;
    }

    /** Idempotent per (provider, batchRef): same document => 200 with replayed=true; different document => 409. */
    @PostMapping("/settlements/import")
    ApiResponse<SettlementView> importBatch(@Valid @RequestBody ImportRequest r, @RequestHeader(value = "X-Operator", required = false) String op) {
        String actor = actor(op, r.importedBy());
        String target = r.provider() + "/" + r.batchRef();
        audit.record(actor, null, "SETTLEMENT_IMPORT", target, "ATTEMPT", java.util.Map.of("items", r.items().size(), "declaredNet", r.declaredNet()));
        SettlementView v = service.importBatch(r);
        audit.record(actor, null, "SETTLEMENT_IMPORT", target, "SUCCESS", java.util.Map.of("settlementId", v.id().toString(), "status", v.status(), "replayed", v.replayed()));
        return ApiResponse.ok(v);
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
    ApiResponse<ExceptionView> updateException(@PathVariable UUID id, @Valid @RequestBody ExceptionUpdate u,
                                               @RequestHeader(value = "X-Operator", required = false) String op) {
        String actor = actor(op, u.owner());
        java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("status", u.status());
        details.put("notes", u.notes());
        audit.record(actor, null, "RECON_EXCEPTION_UPDATE", id.toString(), "ATTEMPT", details);
        ExceptionView v = service.updateException(id, u.status(), u.owner(), u.notes());
        audit.record(actor, null, "RECON_EXCEPTION_UPDATE", id.toString(), "SUCCESS", details);
        return ApiResponse.ok(v);
    }

    @PostMapping("/merchant-settlements/{id}/paid")
    ApiResponse<MerchantSettlementView> paid(@PathVariable UUID id, @Valid @RequestBody PaidRequest r,
                                             @RequestHeader(value = "X-Operator", required = false) String op) {
        String actor = actor(op, null);
        audit.record(actor, null, "PAYOUT_MARK_PAID", id.toString(), "ATTEMPT", java.util.Map.of("payoutReference", String.valueOf(r.payoutReference())));
        MerchantSettlementView v = service.markPaid(id, r.payoutReference());
        audit.record(actor, v.merchantId(), "PAYOUT_MARK_PAID", id.toString(), "SUCCESS", java.util.Map.of("net", v.net(), "payoutReference", v.payoutReference()));
        return ApiResponse.ok(v);
    }

    private static String actor(String header, String fallback) {
        String who = header != null && !header.isBlank() ? header : (fallback != null && !fallback.isBlank() ? fallback : "unspecified");
        return "ops:" + who;
    }
}
