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

    public InternalRefundController(RefundService refunds) {
        this.refunds = refunds;
    }

    /** Reconciliation lookup by the provider-side token (refund_reference). */
    @GetMapping("/by-reference/{reference}")
    ApiResponse<RefundService.Lookup> byReference(@PathVariable String reference) {
        return ApiResponse.ok(refunds.lookupByReference(reference));
    }

    /** Settles a VERIFY_REQUIRED refund after a human checked the provider's own records. */
    @PostMapping("/{id}/resolve")
    ApiResponse<RefundView> resolve(@PathVariable UUID id, @Valid @RequestBody ResolveRequest r) {
        return ApiResponse.ok(refunds.resolve(id, r.status(), r.note()));
    }
}
