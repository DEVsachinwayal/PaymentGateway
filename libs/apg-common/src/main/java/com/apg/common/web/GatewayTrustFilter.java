package com.apg.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Service-to-service trust: every non-actuator request must carry X-Internal-Token (known only to the
 * gateway and services). Identity headers (X-User-Id, X-Merchant-Id, X-User-Roles, X-Auth-Type) are then
 * turned into a {@link CallerContext}. Protects services from direct calls that bypass the gateway.
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class GatewayTrustFilter extends OncePerRequestFilter {
    public static final String TOKEN_HEADER = "X-Internal-Token";
    private final byte[] expected;

    public GatewayTrustFilter(String expectedToken) {
        if (expectedToken == null || expectedToken.length() < 16) {
            throw new IllegalStateException("apg.internal.token (INTERNAL_SERVICE_TOKEN) must be at least 16 chars");
        }
        this.expected = expectedToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String given = req.getHeader(TOKEN_HEADER);
        if (given == null || !MessageDigest.isEqual(expected, given.getBytes(StandardCharsets.UTF_8))) {
            res.setStatus(401);
            res.setContentType("application/json");
            res.getWriter().write("{\"success\":false,\"data\":null,\"error\":{\"code\":\"UNTRUSTED_CALLER\","
                    + "\"message\":\"Request must come through the API gateway\",\"details\":[]},\"requestId\":\"req_unknown\","
                    + "\"timestamp\":\"" + Instant.now() + "\"}");
            return;
        }
        try {
            req.setAttribute(CallerContext.ATTRIBUTE, new CallerContext(
                    uuid(req.getHeader("X-User-Id")), uuid(req.getHeader("X-Merchant-Id")),
                    roles(req.getHeader("X-User-Roles")),
                    req.getHeader("X-Auth-Type") == null ? "NONE" : req.getHeader("X-Auth-Type")));
        } catch (IllegalArgumentException e) {
            res.sendError(400, "Malformed identity header");
            return;
        }
        chain.doFilter(req, res);
    }

    private static UUID uuid(String s) { return s == null || s.isBlank() ? null : UUID.fromString(s); }

    private static Set<String> roles(String s) {
        return s == null || s.isBlank() ? Set.of()
                : Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).collect(Collectors.toSet());
    }
}
