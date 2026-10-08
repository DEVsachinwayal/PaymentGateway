package com.apg.gateway;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.crypto.SecretKey;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Authenticates end-user JWTs for every non-public route and forwards the identity as trusted
 * X-User-* headers. Any client-supplied X-User-* header is stripped first (anti-spoofing).
 * Merchant API-key authentication is added in M3.
 */
@Component
public class JwtAuthGlobalFilter implements GlobalFilter, Ordered {
    static final String USER_ID = "X-User-Id";
    static final String USER_EMAIL = "X-User-Email";
    static final String USER_ROLES = "X-User-Roles";

    private static final List<String> PUBLIC_POST = List.of(
            "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout");

    private final SecretKey key;

    public JwtAuthGlobalFilter(GatewayProperties props) {
        this.key = Keys.hmacShaKeyFor(props.jwtSecret().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerWebExchange stripped = exchange.mutate().request(r -> r.headers(h -> {
            h.remove(USER_ID);
            h.remove(USER_EMAIL);
            h.remove(USER_ROLES);
        })).build();

        if (isPublic(stripped)) {
            return chain.filter(stripped);
        }
        String auth = stripped.getRequest().getHeaders().getFirst("Authorization");
        if (auth == null || !auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return unauthorized(stripped, "MISSING_TOKEN", "Bearer token required");
        }
        Claims claims;
        try {
            claims = Jwts.parser().verifyWith(key).requireIssuer("apg-auth").build()
                    .parseSignedClaims(auth.substring(7).trim()).getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            return unauthorized(stripped, "INVALID_TOKEN", "Invalid or expired token");
        }
        List<?> roles = claims.get("roles", List.class);
        ServerWebExchange authed = stripped.mutate().request(r -> r.headers(h -> {
            h.set(USER_ID, claims.getSubject());
            h.set(USER_EMAIL, String.valueOf(claims.get("email")));
            h.set(USER_ROLES, roles == null ? "" : String.join(",", roles.stream().map(Object::toString).toList()));
        })).build();
        return chain.filter(authed);
    }

    private static boolean isPublic(ServerWebExchange ex) {
        String path = ex.getRequest().getURI().getPath();
        return (HttpMethod.POST.equals(ex.getRequest().getMethod()) && PUBLIC_POST.contains(path))
                || path.startsWith("/actuator/health");
    }

    private static Mono<Void> unauthorized(ServerWebExchange ex, String code, String msg) {
        return ErrorResponses.write(ex, HttpStatus.UNAUTHORIZED, code, msg);
    }

    @Override
    public int getOrder() { return -100; }
}
