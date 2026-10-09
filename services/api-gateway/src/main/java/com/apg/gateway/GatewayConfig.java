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
                        .path("/api/v1/merchants/**", "/api/v1/api-keys/**", "/api/v1/webhook-endpoints/**")
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
                .route("payment-service", r -> r.path("/api/v1/payments/**", "/api/v1/payments")
                        .filters(f -> f
                                .requestRateLimiter(c -> c.setRateLimiter(limiter).setKeyResolver(keyResolver))
                                .setRequestSize(props.maxRequestBytes())
                                .secureHeaders())
                        .uri(props.paymentUri()))
                .build();
    }

    @Bean
    CorsWebFilter corsWebFilter(GatewayProperties props) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(props.corsAllowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Correlation-Id"));
        cors.setExposedHeaders(List.of("X-Correlation-Id"));
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", cors);
        return new CorsWebFilter(src);
    }
}
