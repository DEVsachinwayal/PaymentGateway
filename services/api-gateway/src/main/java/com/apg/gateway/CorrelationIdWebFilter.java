package com.apg.gateway;

import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Accepts a well-formed inbound X-Correlation-Id or generates one; forwards it downstream and echoes it back. */
@Component
public class CorrelationIdWebFilter implements WebFilter, Ordered {
    static final String HEADER = "X-Correlation-Id";
    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String id = exchange.getRequest().getHeaders().getFirst(HEADER);
        if (id == null || !SAFE.matcher(id).matches()) {
            id = "req_" + UUID.randomUUID().toString().replace("-", "");
        }
        final String correlationId = id;
        exchange.getResponse().getHeaders().set(HEADER, correlationId);
        ServerWebExchange mutated = exchange.mutate()
                .request(r -> r.headers(h -> h.set(HEADER, correlationId))).build();
        return chain.filter(mutated);
    }

    @Override
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
}
