package com.apg.gateway;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Authenticates every non-public request and forwards a trusted identity downstream:
 * <ul>
 *   <li>dashboard users: Bearer JWT -> X-User-Id/Email/Roles, X-Merchant-Id (looked up), X-Auth-Type=USER</li>
 *   <li>merchant servers: Bearer sk_... (or X-Api-Key) verified by merchant-service -> X-Merchant-Id, X-Auth-Type=API_KEY;
 *       restricted to merchant-API paths (orders/payments/refunds)</li>
 * </ul>
 * All client-supplied identity headers are stripped first; X-Internal-Token is added for every downstream call.
 */
@Component
public class JwtAuthGlobalFilter implements GlobalFilter, Ordered {
    private static final Logger log = LoggerFactory.getLogger(JwtAuthGlobalFilter.class);
    private static final List<String> IDENTITY_HEADERS =
            List.of("X-User-Id", "X-User-Email", "X-User-Roles", "X-Merchant-Id", "X-Auth-Type", "X-Api-Key-Id", "X-Internal-Token");
    private static final List<String> PUBLIC_POST = List.of(
            "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout");
    private static final List<String> API_KEY_PATHS = List.of("/api/v1/orders", "/api/v1/payments", "/api/v1/refunds");

    private final SecretKey key;
    private final String internalToken;
    private final MerchantDirectory directory;

    public JwtAuthGlobalFilter(GatewayProperties props, MerchantDirectory directory) {
        this.key = Keys.hmacShaKeyFor(props.jwtSecret().getBytes(StandardCharsets.UTF_8));
        this.internalToken = props.internalToken();
        this.directory = directory;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerWebExchange clean = exchange.mutate().request(r -> r.headers(h -> {
            IDENTITY_HEADERS.forEach(h::remove);
            h.set("X-Internal-Token", internalToken);
        })).build();

        if (isPublic(clean)) {
            return chain.filter(clean);
        }
        String auth = clean.getRequest().getHeaders().getFirst("Authorization");
        String apiKeyHeader = clean.getRequest().getHeaders().getFirst("X-Api-Key");
        String bearer = auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7) ? auth.substring(7).trim() : null;

        String apiKey = apiKeyHeader != null ? apiKeyHeader : (bearer != null && bearer.startsWith("sk_") ? bearer : null);
        if (apiKey != null) {
            return apiKeyFlow(clean, chain, apiKey);
        }
        if (bearer == null) {
            return ErrorResponses.write(clean, HttpStatus.UNAUTHORIZED, "MISSING_TOKEN", "Bearer token or API key required");
        }
        return jwtFlow(clean, chain, bearer);
    }

    private Mono<Void> apiKeyFlow(ServerWebExchange ex, GatewayFilterChain chain, String apiKey) {
        String path = ex.getRequest().getURI().getPath();
        if (API_KEY_PATHS.stream().noneMatch(p -> path.equals(p) || path.startsWith(p + "/"))) {
            return ErrorResponses.write(ex, HttpStatus.FORBIDDEN, "FORBIDDEN_FOR_API_KEY", "API keys cannot access this endpoint");
        }
        return directory.verifyApiKey(apiKey).flatMap(id -> {
            if (id.isEmpty()) {
                return ErrorResponses.write(ex, HttpStatus.UNAUTHORIZED, "INVALID_API_KEY", "Invalid API key");
            }
            ServerWebExchange authed = ex.mutate().request(r -> r.headers(h -> {
                h.set("X-Merchant-Id", id.get().merchantId());
                h.set("X-Api-Key-Id", id.get().apiKeyId());
                h.set("X-Auth-Type", "API_KEY");
            })).build();
            return chain.filter(authed);
        }).onErrorResume(e -> unavailable(ex, e));
    }

    private Mono<Void> jwtFlow(ServerWebExchange ex, GatewayFilterChain chain, String token) {
        Claims claims;
        try {
            claims = Jwts.parser().verifyWith(key).requireIssuer("apg-auth").build().parseSignedClaims(token).getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            return ErrorResponses.write(ex, HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "Invalid or expired token");
        }
        List<?> roles = claims.get("roles", List.class);
        String rolesCsv = roles == null ? "" : String.join(",", roles.stream().map(Object::toString).toList());
        return directory.merchantForUser(claims.getSubject()).flatMap(merchant -> {
            ServerWebExchange authed = ex.mutate().request(r -> r.headers(h -> {
                h.set("X-User-Id", claims.getSubject());
                h.set("X-User-Email", String.valueOf(claims.get("email")));
                h.set("X-User-Roles", rolesCsv);
                h.set("X-Auth-Type", "USER");
                merchant.ifPresent(m -> h.set("X-Merchant-Id", m));
            })).build();
            return chain.filter(authed);
        }).onErrorResume(e -> unavailable(ex, e));
    }

    private static Mono<Void> unavailable(ServerWebExchange ex, Throwable e) {
        log.warn("merchant-service lookup failed: {}", e.toString());
        return ErrorResponses.write(ex, HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "Authentication backend unavailable");
    }

    private static boolean isPublic(ServerWebExchange ex) {
        String path = ex.getRequest().getURI().getPath();
        HttpMethod m = ex.getRequest().getMethod();
        return (HttpMethod.POST.equals(m) && PUBLIC_POST.contains(path))
                || path.startsWith("/actuator/health")
                // customer-facing: hosted checkout redirect page (signed token) ...
                || (HttpMethod.GET.equals(m) && (path.startsWith("/checkout/") || path.equals("/payment-result")))
                // ... and provider callbacks, which are authenticated by the provider's signature, not by us (M8)
                || (HttpMethod.POST.equals(m) && path.startsWith("/api/v1/webhooks/"));
    }

    @Override
    public int getOrder() { return -100; }
}
