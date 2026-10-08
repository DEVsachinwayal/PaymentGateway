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
