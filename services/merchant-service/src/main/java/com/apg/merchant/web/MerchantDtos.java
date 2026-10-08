package com.apg.merchant.web;

import com.apg.merchant.domain.ApiKey;
import com.apg.merchant.domain.Merchant;
import com.apg.merchant.domain.MerchantSettings;
import com.apg.merchant.domain.WebhookEndpoint;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public final class MerchantDtos {
    private MerchantDtos() {}

    public record CreateMerchantRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 250) String legalName,
            @NotBlank @Email @Size(max = 254) String supportEmail,
            @Pattern(regexp = "^[A-Z]{3}$", message = "ISO-4217 currency code") String defaultCurrency) {}

    public record MerchantView(UUID id, String name, String legalName, String supportEmail, String status,
                               Instant createdAt, String defaultCurrency) {
        static MerchantView of(Merchant m, MerchantSettings s) {
            return new MerchantView(m.getId(), m.getName(), m.getLegalName(), m.getSupportEmail(), m.getStatus(),
                    m.getCreatedAt(), s.getDefaultCurrency());
        }
    }

    public record SettingsRequest(
            @NotNull @Pattern(regexp = "^[A-Z]{3}$") String defaultCurrency,
            @Size(max = 10) List<@Pattern(regexp = "^https://[^\\s]{1,300}$", message = "must be an https URL") String> allowedReturnUrls) {}

    public record SettingsView(String defaultCurrency, List<String> allowedReturnUrls) {
        static SettingsView of(MerchantSettings s) {
            String raw = s.getAllowedReturnUrls();
            return new SettingsView(s.getDefaultCurrency(),
                    raw.isBlank() ? List.of() : Arrays.asList(raw.split("\n")));
        }
    }

    public record CreateApiKeyRequest(@NotBlank @Size(max = 100) String label,
                                      @NotNull @Pattern(regexp = "^(TEST|LIVE)$") String mode) {}

    public record RotateRequest(@Min(0) @Max(72) Integer graceHours) {}

    /** {@code secret} is present only in the create/rotate response. */
    public record ApiKeyView(UUID id, String keyId, String label, String mode, String status, Instant createdAt,
                             Instant expiresAt, Instant lastUsedAt, String secret) {
        static ApiKeyView of(ApiKey k, String secret) {
            return new ApiKeyView(k.getId(), k.getKeyId(), k.getLabel(), k.getMode(), k.getStatus(), k.getCreatedAt(),
                    k.getExpiresAt(), k.getLastUsedAt(), secret);
        }
    }

    public record CreateWebhookRequest(@NotBlank @Size(max = 500) String url, @NotEmpty @Size(max = 10) List<String> events) {}

    public record WebhookView(UUID id, String url, List<String> events, String status, Instant createdAt, String signingSecret) {
        static WebhookView of(WebhookEndpoint e, String secret) {
            return new WebhookView(e.getId(), e.getUrl(), Arrays.asList(e.getEvents().split(",")), e.getStatus(),
                    e.getCreatedAt(), secret);
        }
    }

    public record VerifyKeyRequest(@NotBlank @Size(max = 200) String apiKey) {}

    public record VerifyKeyResponse(UUID merchantId, UUID apiKeyId, String mode) {}

    public record MerchantRef(UUID merchantId) {}
}
