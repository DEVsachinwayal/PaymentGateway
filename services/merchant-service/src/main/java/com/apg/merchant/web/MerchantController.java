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

    public MerchantController(MerchantService merchants, ApiKeyService apiKeys, WebhookEndpointService webhooks) {
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
    ApiResponse<MerchantView> create(CallerContext c, @Valid @RequestBody CreateMerchantRequest r) {
        c.requireAnyRole(ADMIN);
        Merchant m = merchants.create(c.userId(), r.name(), r.legalName(), r.supportEmail(),
                r.defaultCurrency() == null ? "INR" : r.defaultCurrency());
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
    ApiResponse<SettingsView> updateSettings(CallerContext c, @Valid @RequestBody SettingsRequest r) {
        c.requireAnyRole(ADMIN);
        List<String> urls = r.allowedReturnUrls() == null ? List.of() : r.allowedReturnUrls();
        return ApiResponse.ok(SettingsView.of(merchants.updateSettings(merchantOf(c), r.defaultCurrency(), String.join("\n", urls))));
    }

    // ---- API keys ----
    @PostMapping("/api/v1/api-keys")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<ApiKeyView> createKey(CallerContext c, @Valid @RequestBody CreateApiKeyRequest r) {
        c.requireAnyRole(ADMIN);
        Created k = apiKeys.create(merchantOf(c), r.label(), r.mode());
        return ApiResponse.ok(ApiKeyView.of(k.key(), k.fullKey()));
    }

    @GetMapping("/api/v1/api-keys")
    ApiResponse<List<ApiKeyView>> listKeys(CallerContext c) {
        c.requireAnyRole(ADMIN, DEV);
        return ApiResponse.ok(apiKeys.list(merchantOf(c)).stream().map(k -> ApiKeyView.of(k, null)).toList());
    }

    @PostMapping("/api/v1/api-keys/{id}/rotate")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<ApiKeyView> rotate(CallerContext c, @PathVariable UUID id, @Valid @RequestBody(required = false) RotateRequest r) {
        c.requireAnyRole(ADMIN);
        Created k = apiKeys.rotate(merchantOf(c), id, r == null ? null : r.graceHours());
        return ApiResponse.ok(ApiKeyView.of(k.key(), k.fullKey()));
    }

    @DeleteMapping("/api/v1/api-keys/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(CallerContext c, @PathVariable UUID id) {
        c.requireAnyRole(ADMIN);
        apiKeys.revoke(merchantOf(c), id);
    }

    // ---- webhook endpoints ----
    @PostMapping("/api/v1/webhook-endpoints")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<WebhookView> createWebhook(CallerContext c, @Valid @RequestBody CreateWebhookRequest r) {
        c.requireAnyRole(ADMIN);
        var created = webhooks.create(merchantOf(c), r.url(), r.events());
        return ApiResponse.ok(WebhookView.of(created.endpoint(), created.signingSecret()));
    }

    @GetMapping("/api/v1/webhook-endpoints")
    ApiResponse<List<WebhookView>> listWebhooks(CallerContext c) {
        c.requireAnyRole(ADMIN, DEV);
        return ApiResponse.ok(webhooks.list(merchantOf(c)).stream().map(e -> WebhookView.of(e, null)).toList());
    }

    @DeleteMapping("/api/v1/webhook-endpoints/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteWebhook(CallerContext c, @PathVariable UUID id) {
        c.requireAnyRole(ADMIN);
        webhooks.delete(merchantOf(c), id);
    }
}
