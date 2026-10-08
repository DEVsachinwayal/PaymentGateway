package com.apg.gateway;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Writes the standard APG error envelope (same shape as apg-common's ApiResponse). */
final class ErrorResponses {
    private ErrorResponses() {}

    static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        String reqId = exchange.getResponse().getHeaders().getFirst(CorrelationIdWebFilter.HEADER);
        String body = "{\"success\":false,\"data\":null,\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message
                + "\",\"details\":[]},\"requestId\":\"" + (reqId == null ? "req_unknown" : reqId)
                + "\",\"timestamp\":\"" + Instant.now() + "\"}";
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }
}
