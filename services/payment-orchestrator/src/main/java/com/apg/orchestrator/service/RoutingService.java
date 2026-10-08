package com.apg.orchestrator.service;

import com.apg.common.api.ApiException;
import com.apg.common.web.CorrelationIdFilter;
import com.apg.orchestrator.client.ProviderClient;
import com.apg.orchestrator.client.ProviderClient.ProviderInfo;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Provider selection: the enabled, healthy provider with the lowest priority number. Every decision is stored
 * (routing_decisions) so "why did this payment go to provider X" is always answerable.
 * Failover to another provider is deliberately NOT automatic yet: it is only safe before the customer has been
 * sent to a provider (or after the provider proved it never saw the transaction) - see docs/DECISIONS.md D24.
 */
@Service
public class RoutingService {
    private final ProviderClient providers;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public RoutingService(ProviderClient providers, JdbcTemplate jdbc, Clock clock) {
        this.providers = providers;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record Choice(String provider, String candidates, String reason) {}

    public Choice choose() {
        List<ProviderInfo> all = providers.providers();
        String candidates = all.stream().map(p -> p.provider() + "(priority=" + p.priority() + "," + (p.enabled() ? "enabled" : "disabled")
                + "," + (p.health() != null && p.health().up() ? "up" : "down") + ")").collect(Collectors.joining(", "));
        ProviderInfo best = all.stream().filter(ProviderInfo::enabled).filter(p -> p.health() != null && p.health().up())
                .min(Comparator.comparingInt(ProviderInfo::priority))
                .orElseThrow(() -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NO_PROVIDER_AVAILABLE",
                        "No payment provider is currently available"));
        return new Choice(best.provider(), candidates, "lowest priority among enabled and healthy providers");
    }

    public void record(UUID paymentId, String attemptReference, Choice c) {
        jdbc.update("INSERT INTO routing_decisions (payment_id, attempt_reference, provider, candidates, reason, correlation_id, created_at) VALUES (?,?,?,?,?,?,?)",
                paymentId, attemptReference, c.provider(), c.candidates().substring(0, Math.min(500, c.candidates().length())), c.reason(),
                MDC.get(CorrelationIdFilter.MDC_KEY), Timestamp.from(clock.instant()));
    }
}
