package com.apg.merchant.service;

import com.apg.common.api.ApiException;
import com.apg.merchant.domain.Repositories.WebhookEndpointRepository;
import com.apg.merchant.domain.WebhookEndpoint;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class WebhookEndpointService {
    public static final Set<String> ALLOWED_EVENTS = Set.of(
            "payment.success", "payment.failed", "payment.unknown", "refund.success", "refund.failed", "settlement.created");
    private static final int MAX_ENDPOINTS = 10;

    public record Created(WebhookEndpoint endpoint, String signingSecret) {}

    private final WebhookEndpointRepository repo;
    private final WebhookUrlValidator validator;
    private final SecretCrypto crypto;
    private final Clock clock;

    public WebhookEndpointService(WebhookEndpointRepository repo, WebhookUrlValidator validator, SecretCrypto crypto, Clock clock) {
        this.repo = repo;
        this.validator = validator;
        this.crypto = crypto;
        this.clock = clock;
    }

    public Created create(UUID merchantId, String url, List<String> events) {
        validator.validate(url);
        if (events.isEmpty() || !ALLOWED_EVENTS.containsAll(events)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EVENTS", "Unknown event type; allowed: " + ALLOWED_EVENTS);
        }
        if (repo.findByMerchantIdAndStatusOrderByCreatedAtDesc(merchantId, "ACTIVE").size() >= MAX_ENDPOINTS) {
            throw new ApiException(HttpStatus.CONFLICT, "TOO_MANY_ENDPOINTS", "Maximum " + MAX_ENDPOINTS + " webhook endpoints");
        }
        String secret = "whsec_" + crypto.randomUrlSafe(32);
        WebhookEndpoint e = repo.save(new WebhookEndpoint(merchantId, url, String.join(",", events.stream().distinct().sorted().toList()),
                crypto.encrypt(secret), clock.instant()));
        return new Created(e, secret);
    }

    @Transactional(readOnly = true)
    public List<WebhookEndpoint> list(UUID merchantId) {
        return repo.findByMerchantIdAndStatusOrderByCreatedAtDesc(merchantId, "ACTIVE");
    }

    public record Subscriber(UUID id, String url, String signingSecret) {}

    /** Internal use (notification service): active endpoints of a merchant subscribed to the event, with the signing secret. */
    @Transactional(readOnly = true)
    public List<Subscriber> subscribers(UUID merchantId, String event) {
        return repo.findByMerchantIdAndStatusOrderByCreatedAtDesc(merchantId, "ACTIVE").stream()
                .filter(e -> List.of(e.getEvents().split(",")).contains(event))
                .map(e -> new Subscriber(e.getId(), e.getUrl(), crypto.decrypt(e.getSecretEnc())))
                .toList();
    }

    public void delete(UUID merchantId, UUID id) {
        repo.findByIdAndMerchantId(id, merchantId)
                .filter(e -> "ACTIVE".equals(e.getStatus()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "WEBHOOK_ENDPOINT_NOT_FOUND", "Webhook endpoint not found"))
                .delete();
    }
}
