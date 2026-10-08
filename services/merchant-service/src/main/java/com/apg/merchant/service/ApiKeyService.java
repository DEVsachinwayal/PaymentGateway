package com.apg.merchant.service;

import com.apg.common.api.ApiException;
import com.apg.merchant.config.MerchantProperties;
import com.apg.merchant.domain.ApiKey;
import com.apg.merchant.domain.Merchant;
import com.apg.merchant.domain.Repositories.ApiKeyRepository;
import com.apg.merchant.domain.Repositories.MerchantRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Full key format shown once to the merchant: {@code sk_test_<16hex>.<43-char secret>}.
 * Only the public part (keyId) and HMAC(secret) are stored.
 */
@Service
@Transactional
public class ApiKeyService {
    public record Created(ApiKey key, String fullKey) {}
    public record Verified(UUID merchantId, UUID apiKeyId, String mode) {}

    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    private final ApiKeyRepository keys;
    private final MerchantRepository merchants;
    private final SecretCrypto crypto;
    private final MerchantProperties props;
    private final Clock clock;

    public ApiKeyService(ApiKeyRepository keys, MerchantRepository merchants, SecretCrypto crypto,
                         MerchantProperties props, Clock clock) {
        this.keys = keys;
        this.merchants = merchants;
        this.crypto = crypto;
        this.props = props;
        this.clock = clock;
    }

    public Created create(UUID merchantId, String label, String mode) {
        return create(merchantId, label, mode, null);
    }

    private Created create(UUID merchantId, String label, String mode, UUID rotatedFrom) {
        Merchant m = merchants.findById(merchantId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MERCHANT_NOT_FOUND", "Merchant not found"));
        if (m.isSuspended()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "MERCHANT_SUSPENDED", "Merchant is suspended");
        }
        if ("LIVE".equals(mode) && !m.isActive()) {
            // External dependency: provider onboarding/KYC must complete and ops must activate the merchant.
            throw new ApiException(HttpStatus.FORBIDDEN, "LIVE_MODE_NOT_ENABLED",
                    "Live keys require an activated merchant (onboarding/KYC pending)");
        }
        String keyId = "sk_" + mode.toLowerCase() + "_" + crypto.randomHex(8);
        String secret = crypto.randomUrlSafe(32);
        ApiKey k = keys.save(new ApiKey(merchantId, keyId, crypto.hmac(secret), label, mode, rotatedFrom, clock.instant()));
        return new Created(k, keyId + "." + secret);
    }

    @Transactional(readOnly = true)
    public List<ApiKey> list(UUID merchantId) {
        return keys.findByMerchantIdOrderByCreatedAtDesc(merchantId);
    }

    /** Issues a replacement key; the old key keeps working for {@code graceHours} (0 = revoke immediately). */
    public Created rotate(UUID merchantId, UUID id, Integer graceHours) {
        ApiKey old = owned(merchantId, id);
        if (!old.isUsable(clock.instant())) {
            throw new ApiException(HttpStatus.CONFLICT, "KEY_NOT_ACTIVE", "Only active keys can be rotated");
        }
        int grace = graceHours == null ? props.rotationGraceHoursDefault() : graceHours;
        Created fresh = create(merchantId, old.getLabel(), old.getMode(), old.getId());
        Instant now = clock.instant();
        if (grace == 0) old.revoke(now); else old.expireAt(now.plus(Duration.ofHours(grace)));
        return fresh;
    }

    public void revoke(UUID merchantId, UUID id) {
        owned(merchantId, id).revoke(clock.instant());
    }

    /** Called by the gateway for every API-key authenticated request. */
    public Optional<Verified> verify(String fullKey) {
        int dot = fullKey.lastIndexOf('.');
        if (dot <= 0 || dot == fullKey.length() - 1) return Optional.empty();
        String keyId = fullKey.substring(0, dot);
        String secret = fullKey.substring(dot + 1);
        Optional<ApiKey> found = keys.findByKeyId(keyId);
        // Compute the hash even when the key is unknown so timing does not reveal key existence.
        String presented = crypto.hmac(secret);
        if (found.isEmpty()) return Optional.empty();
        ApiKey k = found.get();
        Instant now = clock.instant();
        if (!MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), k.getSecretHash().getBytes(StandardCharsets.UTF_8))
                || !k.isUsable(now)) {
            return Optional.empty();
        }
        Merchant m = merchants.findById(k.getMerchantId()).orElse(null);
        if (m == null || m.isSuspended() || ("LIVE".equals(k.getMode()) && !m.isActive())) return Optional.empty();
        if (k.getLastUsedAt() == null || k.getLastUsedAt().isBefore(now.minus(TOUCH_INTERVAL))) k.touch(now);
        return Optional.of(new Verified(k.getMerchantId(), k.getId(), k.getMode()));
    }

    private ApiKey owned(UUID merchantId, UUID id) {
        // Same 404 for "does not exist" and "belongs to another merchant" (tenant isolation, no enumeration).
        return keys.findByIdAndMerchantId(id, merchantId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "API_KEY_NOT_FOUND", "API key not found"));
    }
}
