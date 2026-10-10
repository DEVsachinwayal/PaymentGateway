package com.apg.gateway;

import java.util.List;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Mono;

@Configuration
public class GatewayConfig {

    /** Rate-limit key = client address (behind the OCI load balancer this must become X-Forwarded-For; see M15). */
    @Bean
    KeyResolver clientAddressKeyResolver() {
        return ex -> Mono.just(ex.getRequest().getRemoteAddress() == null
                ? "unknown" : ex.getRequest().getRemoteAddress().getAddress().getHostAddress());
    }

    @Bean
    @Primary
    RedisRateLimiter apgRateLimiter(GatewayProperties props) {
        return new RedisRateLimiter(props.rateLimit().replenishPerSecond(), props.rateLimit().burst(), 1);
    }

    /** Add one route per downstream service as each milestone lands. */
    @Bean
    RouteLocator apgRoutes(RouteLocatorBuilder b, GatewayProperties props, RedisRateLimiter limiter,
                           KeyResolver keyResolver) {
        return b.routes()
                .route("auth-service", r -> r.path("/api/v1/auth/**")
                        .filters(f -> f
                                .requestRateLimiter(c -> c.setRateLimiter(limiter).setKeyResolver(keyResolver))
                                .setRequestSize(props.maxRequestBytes())
                                .secureHeaders())
                        .uri(props.authUri()))
                .route("merchant-service", r -> r
                        .path("/api/v1/merchants/**", "/api/v1/api-keys/**", "/api/v1/webhook-endpoints/**", "/api/v1/audit-events")
                        .filters(f -> f
                                .requestRateLimiter(c -> c.setRateLimiter(limiter).setKeyResolver(keyResolver))
                                .setRequestSize(props.maxRequestBytes())
                                .secureHeaders())
                        .uri(props.merchantUri()))
                .route("order-service", r -> r.path("/api/v1/orders/**", "/api/v1/orders")
                        .filters(f -> f
                                .requestRateLimiter(c -> c.setRateLimiter(limiter).setKeyResolver(keyResolver))
                                .setRequestSize(props.maxRequestBytes())
                                .secureHeaders())
                        .uri(props.orderUri()))
                // must come BEFORE the generic payments route: checkout belongs to the orchestrator
                .route("orchestrator-checkout", r -> r.path("/api/v1/payments/{id}/checkout")
                        .filters(f -> f
                                .requestRateLimiter(c -> c.setRateLimiter(limiter).setKeyResolver(keyResolver))
                                .setRequestSize(props.maxRequestBytes())
                                .secureHeaders())
                        .uri(props.orchestratorUri()))
                // hosted redirect page opened by the customer's browser (public; protected by a signed token)
                .route("checkout-page", r -> r.path("/checkout/**")
                        .filters(f -> f
                                .requestRateLimiter(c -> c.setRateLimiter(limiter).setKeyResolver(keyResolver)))
                        .uri(props.orchestratorUri()))
                // provider callbacks + neutral customer result page (public; callbacks are authenticated by provider signature)
                .route("webhook-service", r -> r.path("/api/v1/webhooks/**", "/payment-result")
                        .filters(f -> f
                                .requestRateLimiter(c -> c.setRateLimiter(limiter).setKeyResolver(keyResolver))
                                .setRequestSize(props.maxRequestBytes()))
                        .uri(props.webhookUri()))
                .route("refund-service", r -> r.path("/api/v1/refunds/**", "/api/v1/refunds")
                        .filters(f -> f
                                .requestRateLimiter(c -> c.setRateLimiter(limiter).setKeyResolver(keyResolver))
                                .setRequestSize(props.maxRequestBytes())
                                .secureHeaders())
                        .uri(props.refundUri()))
                // read-only merchant statement; dashboard users only (API keys are rejected by the gateway filter)
                .route("ledger-service", r -> r.path("/api/v1/ledger/**")
                        .filters(f -> f
                                .requestRateLimiter(c -> c.setRateLimiter(limiter).setKeyResolver(keyResolver))
                                .setRequestSize(props.maxRequestBytes())
                                .secureHeaders())
                        .uri(props.ledgerUri()))
                // merchant payout statements; dashboard users only (API keys are rejected by the gateway filter)
                .route("settlement-service", r -> r.path("/api/v1/settlements/**", "/api/v1/settlements")
                        .filters(f -> f
                                .requestRateLimiter(c -> c.setRateLimiter(limiter).setKeyResolver(keyResolver))
                                .setRequestSize(props.maxRequestBytes())
                                .secureHeaders())
                        .uri(props.settlementUri()))
                // merchant webhook delivery history + resend; dashboard users only
                .route("notification-service", r -> r.path("/api/v1/webhook-deliveries/**", "/api/v1/webhook-deliveries")
                        .filters(f -> f
                                .requestRateLimiter(c -> c.setRateLimiter(limiter).setKeyResolver(keyResolver))
                                .setRequestSize(props.maxRequestBytes())
                                .secureHeaders())
                        .uri(props.notificationUri()))
                // payment links: merchant API (authenticated) ...
                .route("payment-link-api", r -> r.path("/api/v1/payment-links/**", "/api/v1/payment-links")
                        .filters(f -> f
                                .requestRateLimiter(c -> c.setRateLimiter(limiter).setKeyResolver(keyResolver))
                                .setRequestSize(props.maxRequestBytes())
                                .secureHeaders())
                        .uri(props.paymentLinkUri()))
                // ... and the public customer page (the service sets its own CSP with a nonce, so no secureHeaders here)
                .route("payment-link-page", r -> r.path("/pay/**")
                        .filters(f -> f
                                .requestRateLimiter(c -> c.setRateLimiter(limiter).setKeyResolver(keyResolver))
                                .setRequestSize(4096L))
                        .uri(props.paymentLinkUri()))
                .route("payment-service", r -> r.path("/api/v1/payments/**", "/api/v1/payments")
                        .filters(f -> f
                                .requestRateLimiter(c -> c.setRateLimiter(limiter).setKeyResolver(keyResolver))
                                .setRequestSize(props.maxRequestBytes())
                                .secureHeaders())
                        .uri(props.paymentUri()))
                .build();
    }

    /**
     * CORS protects the dashboard API. It must NOT apply to the pages a customer's browser is sent back to by the payment provider
     * (the provider posts a form from its own origin to /api/v1/webhooks/**, and customers open /checkout and /payment-result):
     * those requests carry a foreign Origin header and would otherwise be rejected with 403 "Invalid CORS request".
     * Those endpoints are protected by their own means (provider signature, signed checkout token, no state change).
     */
    static boolean providerFacing(String path) {
        // /pay/: the payment link page posts its own form with "Referrer-Policy: no-referrer", which makes browsers send "Origin: null"
        return path.startsWith("/api/v1/webhooks/") || path.startsWith("/checkout/") || path.equals("/payment-result") || path.startsWith("/pay/");
    }

    @Bean
    org.springframework.web.server.WebFilter corsWebFilter(GatewayProperties props) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(props.corsAllowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Correlation-Id"));
        cors.setExposedHeaders(List.of("X-Correlation-Id"));
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", cors);
        CorsWebFilter delegate = new CorsWebFilter(src);
        return (exchange, chain) -> providerFacing(exchange.getRequest().getURI().getPath()) ? chain.filter(exchange) : delegate.filter(exchange, chain);
    }
}
