package com.apg.common.web;

import com.apg.common.api.ApiException;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Identity of the caller as asserted by the API gateway (after it authenticated a JWT or an API key).
 * Only trustworthy because {@link GatewayTrustFilter} rejects requests lacking the internal token.
 *
 * @param userId     dashboard user (JWT) or null
 * @param merchantId merchant resolved by the gateway (API key owner / user's merchant) or null
 * @param roles      JWT roles; empty for API-key callers
 * @param authType   USER or API_KEY
 */
public record CallerContext(UUID userId, UUID merchantId, Set<String> roles, String authType) {
    public static final String ATTRIBUTE = CallerContext.class.getName();

    public boolean isUser() { return "USER".equals(authType); }

    public CallerContext requireUser() {
        if (!isUser() || userId == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Dashboard user authentication required");
        }
        return this;
    }

    public CallerContext requireAnyRole(String... allowed) {
        requireUser();
        if (Arrays.stream(allowed).noneMatch(roles::contains)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Insufficient role");
        }
        return this;
    }

    public UUID requireMerchant() {
        if (merchantId == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NO_MERCHANT", "No merchant is associated with this caller");
        }
        return merchantId;
    }
}
