package com.apg.gateway;

import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * API responses (new API-key and webhook secrets, payment data, ledger statements) must never end up in a browser or proxy cache:
 * (a WebFilter, so it also covers errors the gateway writes itself) unless a downstream service already chose a Cache-Control policy, every /api response is marked no-store.
 */
@Component
public class NoStoreGlobalFilter implements WebFilter, Ordered {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (exchange.getRequest().getURI().getPath().startsWith("/api/")) {
            exchange.getResponse().beforeCommit(() -> {
                var h = exchange.getResponse().getHeaders();
                if (!h.containsKey("Cache-Control")) {
                    h.set("Cache-Control", "no-store");
                    h.set("Pragma", "no-cache");
                }
                return Mono.empty();
            });
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 20; }
}
