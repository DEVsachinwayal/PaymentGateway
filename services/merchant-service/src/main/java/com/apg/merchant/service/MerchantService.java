package com.apg.merchant.service;

import com.apg.common.api.ApiException;
import com.apg.merchant.domain.*;
import com.apg.merchant.domain.Repositories.*;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class MerchantService {
    private final MerchantRepository merchants;
    private final MerchantUserRepository merchantUsers;
    private final MerchantSettingsRepository settings;
    private final Clock clock;

    public MerchantService(MerchantRepository merchants, MerchantUserRepository merchantUsers,
                           MerchantSettingsRepository settings, Clock clock) {
        this.merchants = merchants;
        this.merchantUsers = merchantUsers;
        this.settings = settings;
        this.clock = clock;
    }

    public Merchant create(UUID userId, String name, String legalName, String supportEmail, String currency) {
        if (merchantUsers.findByUserId(userId).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "MERCHANT_ALREADY_EXISTS", "User already belongs to a merchant");
        }
        Instant now = clock.instant();
        Merchant m = merchants.save(new Merchant(name, legalName, supportEmail, now));
        try {
            merchantUsers.saveAndFlush(new MerchantUser(m.getId(), userId, "OWNER", now));
        } catch (DataIntegrityViolationException e) { // concurrent double-create
            throw new ApiException(HttpStatus.CONFLICT, "MERCHANT_ALREADY_EXISTS", "User already belongs to a merchant");
        }
        MerchantSettings s = new MerchantSettings(m.getId(), now);
        s.update(currency, "", now);
        settings.save(s);
        return m;
    }

    @Transactional(readOnly = true)
    public Optional<UUID> merchantIdForUser(UUID userId) {
        return merchantUsers.findByUserId(userId).map(MerchantUser::getMerchantId);
    }

    @Transactional(readOnly = true)
    public Merchant get(UUID merchantId) {
        return merchants.findById(merchantId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MERCHANT_NOT_FOUND", "Merchant not found"));
    }

    @Transactional(readOnly = true)
    public MerchantSettings getSettings(UUID merchantId) {
        return settings.findById(merchantId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MERCHANT_NOT_FOUND", "Merchant not found"));
    }

    public MerchantSettings updateSettings(UUID merchantId, String currency, String returnUrls) {
        MerchantSettings s = getSettings(merchantId);
        s.update(currency, returnUrls, clock.instant());
        return s;
    }
}
