package com.apg.refund.web;

import com.apg.common.api.ApiResponse;
import com.apg.refund.service.RefundService;
import com.apg.refund.service.RefundService.RefundView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** Operators only (internal token, not routed by the gateway). */
@RestController
@RequestMapping("/internal/v1/refunds")
public class InternalRefundController {
    public record ResolveRequest(@NotBlank String status, @Size(max = 200) String note) {}

    private final RefundService refunds;
    private final com.apg.common.audit.AuditLog audit;

    public InternalRefundController(RefundService refunds, com.apg.common.audit.AuditLog audit) {
        this.refunds = refunds;
        this.audit = audit;
    }

    /** Reconciliation lookup by the provider-side token (refund_reference). */
    @GetMapping("/by-reference/{reference}")
    ApiResponse<RefundService.Lookup> byReference(@PathVariable String reference) {
        return ApiResponse.ok(refunds.lookupByReference(reference));
    }

    /** Settles a VERIFY_REQUIRED refund after a human checked the provider's own records. */
    @PostMapping("/{id}/resolve")
    ApiResponse<RefundView> resolve(@PathVariable UUID id, @Valid @RequestBody ResolveRequest r,
                                    @RequestHeader(value = "X-Operator", required = false) String operator) {
        String actor = "ops:" + (operator == null || operator.isBlank() ? "unspecified" : operator);
        java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("resolution", r.status());
        details.put("note", r.note());
        audit.record(actor, null, "REFUND_RESOLVE", id.toString(), "ATTEMPT", details);       // recorded before acting
        RefundView v = refunds.resolve(id, r.status(), r.note());
        audit.record(actor, null, "REFUND_RESOLVE", id.toString(), "SUCCESS", details);
        return ApiResponse.ok(v);
    }
}
