package com.apg.merchant.web;

import com.apg.common.api.ApiException;
import com.apg.common.api.ApiResponse;
import com.apg.common.web.CallerContext;
import com.apg.merchant.domain.Merchant;
import com.apg.merchant.service.*;
import com.apg.merchant.service.ApiKeyService.Created;
import com.apg.merchant.web.MerchantDtos.*;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
public class MerchantController {
    private static final String ADMIN = "MERCHANT_ADMIN";
    private static final String DEV = "MERCHANT_DEVELOPER";

    private final MerchantService merchants;
    private final ApiKeyService apiKeys;
    private final WebhookEndpointService webhooks;
    private final com.apg.common.audit.AuditLog audit;

    public MerchantController(MerchantService merchants, ApiKeyService apiKeys, WebhookEndpointService webhooks, com.apg.common.audit.AuditLog audit) {
        this.audit = audit;
        this.merchants = merchants;
        this.apiKeys = apiKeys;
        this.webhooks = webhooks;
    }

    /** Merchant is always derived from the authenticated user, never from client-provided ids. */
    private UUID merchantOf(CallerContext c) {
        return merchants.merchantIdForUser(c.userId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NO_MERCHANT", "Create a merchant profile first"));
    }

    // ---- merchant ----
    @PostMapping("/api/v1/merchants")
    @ResponseStatus(HttpStatus.CREATED)
    @org.springframework.transaction.annotation.Transactional
    ApiResponse<MerchantView> create(CallerContext c, @Valid @RequestBody CreateMerchantRequest r) {
        c.requireAnyRole(ADMIN);
        Merchant m = merchants.create(c.userId(), r.name(), r.legalName(), r.supportEmail(),
                r.defaultCurrency() == null ? "INR" : r.defaultCurrency());
        audit.record(auditCtx(c, m.getId()), "MERCHANT_CREATED", m.getId().toString(), "SUCCESS", java.util.Map.of("name", r.name()));
        return ApiResponse.ok(MerchantView.of(m, merchants.getSettings(m.getId())));
    }

    @GetMapping("/api/v1/merchants/me")
    ApiResponse<MerchantView> me(CallerContext c) {
        c.requireAnyRole(ADMIN, DEV);
        UUID id = merchantOf(c);
        return ApiResponse.ok(MerchantView.of(merchants.get(id), merchants.getSettings(id)));
    }

    @GetMapping("/api/v1/merchants/me/settings")
    ApiResponse<SettingsView> settings(CallerContext c) {
        c.requireAnyRole(ADMIN, DEV);
        return ApiResponse.ok(SettingsView.of(merchants.getSettings(merchantOf(c))));
    }

    @PutMapping("/api/v1/merchants/me/settings")
    @org.springframework.transaction.annotation.Transactional
    ApiResponse<SettingsView> updateSettings(CallerContext c, @Valid @RequestBody SettingsRequest r) {
        c.requireAnyRole(ADMIN);
        List<String> urls = r.allowedReturnUrls() == null ? List.of() : r.allowedReturnUrls();
        UUID mid = merchantOf(c);
        var view = SettingsView.of(merchants.updateSettings(mid, r.defaultCurrency(), String.join("\n", urls)));
        audit.record(auditCtx(c, mid), "MERCHANT_SETTINGS_UPDATED", mid.toString(), "SUCCESS", java.util.Map.of("returnUrls", urls.size(), "currency", r.defaultCurrency()));
        return ApiResponse.ok(view);
    }

    // ---- API keys ----
    @PostMapping("/api/v1/api-keys")
    @ResponseStatus(HttpStatus.CREATED)
    @org.springframework.transaction.annotation.Transactional
    ApiResponse<ApiKeyView> createKey(CallerContext c, @Valid @RequestBody CreateApiKeyRequest r) {
        c.requireAnyRole(ADMIN);
        UUID mid = merchantOf(c);
        Created k = apiKeys.create(mid, r.label(), r.mode());
        audit.record(auditCtx(c, mid), "API_KEY_CREATED", k.key().getKeyId(), "SUCCESS", java.util.Map.of("mode", r.mode(), "label", r.label()));
        return ApiResponse.ok(ApiKeyView.of(k.key(), k.fullKey()));
    }

    @GetMapping("/api/v1/api-keys")
    ApiResponse<List<ApiKeyView>> listKeys(CallerContext c) {
        c.requireAnyRole(ADMIN, DEV);
        return ApiResponse.ok(apiKeys.list(merchantOf(c)).stream().map(k -> ApiKeyView.of(k, null)).toList());
    }

    @PostMapping("/api/v1/api-keys/{id}/rotate")
    @ResponseStatus(HttpStatus.CREATED)
    @org.springframework.transaction.annotation.Transactional
    ApiResponse<ApiKeyView> rotate(CallerContext c, @PathVariable UUID id, @Valid @RequestBody(required = false) RotateRequest r) {
        c.requireAnyRole(ADMIN);
        UUID mid = merchantOf(c);
        Created k = apiKeys.rotate(mid, id, r == null ? null : r.graceHours());
        audit.record(auditCtx(c, mid), "API_KEY_ROTATED", id.toString(), "SUCCESS", java.util.Map.of("newKey", k.key().getKeyId(), "graceHours", r == null || r.graceHours() == null ? 24 : r.graceHours()));
        return ApiResponse.ok(ApiKeyView.of(k.key(), k.fullKey()));
    }

    @DeleteMapping("/api/v1/api-keys/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @org.springframework.transaction.annotation.Transactional
    void revoke(CallerContext c, @PathVariable UUID id) {
        c.requireAnyRole(ADMIN);
        UUID mid = merchantOf(c);
        apiKeys.revoke(mid, id);
        audit.record(auditCtx(c, mid), "API_KEY_REVOKED", id.toString(), "SUCCESS", null);
    }

    // ---- webhook endpoints ----
    @PostMapping("/api/v1/webhook-endpoints")
    @ResponseStatus(HttpStatus.CREATED)
    @org.springframework.transaction.annotation.Transactional
    ApiResponse<WebhookView> createWebhook(CallerContext c, @Valid @RequestBody CreateWebhookRequest r) {
        c.requireAnyRole(ADMIN);
        UUID mid = merchantOf(c);
        var created = webhooks.create(mid, r.url(), r.events());
        audit.record(auditCtx(c, mid), "WEBHOOK_ENDPOINT_CREATED", created.endpoint().getId().toString(), "SUCCESS", java.util.Map.of("host", java.net.URI.create(r.url()).getHost(), "events", r.events()));
        return ApiResponse.ok(WebhookView.of(created.endpoint(), created.signingSecret()));
    }

    @GetMapping("/api/v1/webhook-endpoints")
    ApiResponse<List<WebhookView>> listWebhooks(CallerContext c) {
        c.requireAnyRole(ADMIN, DEV);
        return ApiResponse.ok(webhooks.list(merchantOf(c)).stream().map(e -> WebhookView.of(e, null)).toList());
    }

    @DeleteMapping("/api/v1/webhook-endpoints/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @org.springframework.transaction.annotation.Transactional
    void deleteWebhook(CallerContext c, @PathVariable UUID id) {
        c.requireAnyRole(ADMIN);
        UUID mid = merchantOf(c);
        webhooks.delete(mid, id);
        audit.record(auditCtx(c, mid), "WEBHOOK_ENDPOINT_DELETED", id.toString(), "SUCCESS", null);
    }

    /** For a dashboard user the merchant is derived server-side, so the audit record gets it explicitly. */
    private static CallerContext auditCtx(CallerContext c, UUID merchantId) {
        return new CallerContext(c.userId(), merchantId, c.roles(), c.authType());
    }
}
