package com.apg.merchant.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public final class Repositories {
    private Repositories() {}

    public interface MerchantRepository extends JpaRepository<Merchant, UUID> {}

    public interface MerchantUserRepository extends JpaRepository<MerchantUser, UUID> {
        Optional<MerchantUser> findByUserId(UUID userId);
    }

    public interface MerchantSettingsRepository extends JpaRepository<MerchantSettings, UUID> {}

    public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {
        Optional<ApiKey> findByKeyId(String keyId);
        List<ApiKey> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId);
        Optional<ApiKey> findByIdAndMerchantId(UUID id, UUID merchantId);
    }

    public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, UUID> {
        List<WebhookEndpoint> findByMerchantIdAndStatusOrderByCreatedAtDesc(UUID merchantId, String status);
        Optional<WebhookEndpoint> findByIdAndMerchantId(UUID id, UUID merchantId);
    }
}
