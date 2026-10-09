package com.apg.notification.web;

import com.apg.common.api.ApiException;
import com.apg.common.api.ApiResponse;
import com.apg.common.web.CallerContext;
import com.apg.notification.service.NotificationService;
import com.apg.notification.service.NotificationService.JobView;
import com.apg.notification.service.NotificationService.PageView;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Merchant-facing delivery history (dashboard users only: the gateway rejects API keys for this path). */
@RestController
@RequestMapping("/api/v1/webhook-deliveries")
public class WebhookDeliveryController {
    private static final Set<String> STATUSES = Set.of("PENDING", "RETRYING", "DELIVERED", "DEAD", "CANCELLED");
    private final NotificationService service;
    private final com.apg.common.audit.AuditLog audit;

    public WebhookDeliveryController(NotificationService service, com.apg.common.audit.AuditLog audit) {
        this.service = service;
        this.audit = audit;
    }

    private static UUID merchantOf(CallerContext c, boolean write) {
        if (write) c.requireAnyRole("MERCHANT_ADMIN");
        else c.requireAnyRole("MERCHANT_ADMIN", "MERCHANT_DEVELOPER");
        return c.requireMerchant();
    }

    @GetMapping
    ApiResponse<PageView<JobView>> list(CallerContext c, @RequestParam(required = false) String status,
                                        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        UUID m = merchantOf(c, false);
        if (status != null && !STATUSES.contains(status)) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_STATUS", "Unknown status");
        return ApiResponse.ok(service.list(m, status, page, size));
    }

    @GetMapping("/{id}")
    ApiResponse<JobView> get(CallerContext c, @PathVariable UUID id) {
        return ApiResponse.ok(service.get(merchantOf(c, false), id));
    }

    /** Starts a new delivery cycle for the same event (same event id, so receivers can de-duplicate). */
    @PostMapping("/{id}/resend")
    @org.springframework.transaction.annotation.Transactional
    ApiResponse<JobView> resend(CallerContext c, @PathVariable UUID id) {
        JobView v = service.resend(merchantOf(c, true), id);
        audit.record(c, "WEBHOOK_DELIVERY_RESEND", id.toString(), "SUCCESS", java.util.Map.of("eventType", v.eventType()));
        return ApiResponse.ok(v);
    }
}
