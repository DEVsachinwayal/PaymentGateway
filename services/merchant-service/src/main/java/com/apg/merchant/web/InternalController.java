package com.apg.merchant.web;

import com.apg.common.api.ApiException;
import com.apg.common.api.ApiResponse;
import com.apg.merchant.service.ApiKeyService;
import com.apg.merchant.service.MerchantService;
import com.apg.merchant.web.MerchantDtos.*;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Gateway-only endpoints (the gateway exposes no route to /internal/**; callers need the internal token). */
@RestController
@RequestMapping("/internal/v1")
public class InternalController {
    private final ApiKeyService apiKeys;
    private final MerchantService merchants;

    public InternalController(ApiKeyService apiKeys, MerchantService merchants) {
        this.apiKeys = apiKeys;
        this.merchants = merchants;
    }

    @PostMapping("/api-keys/verify")
    ApiResponse<VerifyKeyResponse> verify(@Valid @RequestBody VerifyKeyRequest r) {
        return apiKeys.verify(r.apiKey())
                .map(v -> ApiResponse.ok(new VerifyKeyResponse(v.merchantId(), v.apiKeyId(), v.mode())))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_API_KEY", "Invalid API key"));
    }

    @GetMapping("/users/{userId}/merchant")
    ApiResponse<MerchantRef> merchantForUser(@PathVariable UUID userId) {
        return merchants.merchantIdForUser(userId)
                .map(id -> ApiResponse.ok(new MerchantRef(id)))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NO_MERCHANT", "No merchant for user"));
    }
}
