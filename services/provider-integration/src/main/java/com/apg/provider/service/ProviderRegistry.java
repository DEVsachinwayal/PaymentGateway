package com.apg.provider.service;

import com.apg.common.api.ApiException;
import com.apg.provider.api.PaymentProvider;
import com.apg.provider.payu.PayUProperties;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Holds the enabled adapters and publishes their (non-secret) configuration to provider_configs + provider_audit. */
@Component
public class ProviderRegistry implements ApplicationRunner {
    private final Map<String, PaymentProvider> providers;
    private final JdbcTemplate jdbc;
    private final PayUProperties payu;
    private final Clock clock;

    public ProviderRegistry(List<PaymentProvider> providers, JdbcTemplate jdbc, PayUProperties payu, Clock clock) {
        this.providers = providers.stream().collect(Collectors.toUnmodifiableMap(PaymentProvider::id, Function.identity()));
        this.jdbc = jdbc;
        this.payu = payu;
        this.clock = clock;
    }

    public PaymentProvider get(String id) {
        PaymentProvider p = providers.get(id);
        if (p == null) throw new ApiException(HttpStatus.NOT_FOUND, "PROVIDER_NOT_FOUND", "Unknown or disabled provider: " + id);
        return p;
    }

    public Map<String, PaymentProvider> all() { return providers; }

    @Override
    public void run(ApplicationArguments args) {
        Timestamp now = Timestamp.from(clock.instant());
        if (payu.enabled()) {
            jdbc.update("""
                    INSERT INTO provider_configs (provider, enabled, mode, priority, base_url, updated_at) VALUES ('payu', true, ?, ?, ?, ?)
                    ON CONFLICT (provider) DO UPDATE SET enabled = true, mode = EXCLUDED.mode, priority = EXCLUDED.priority,
                    base_url = EXCLUDED.base_url, updated_at = EXCLUDED.updated_at""",
                    payu.isTest() ? "TEST" : "LIVE", payu.priority(), payu.baseUrl(), now);
            jdbc.update("INSERT INTO provider_audit (provider, actor, action, detail, created_at) VALUES ('payu','SYSTEM','CONFIG_LOADED',?,?)",
                    "mode=" + (payu.isTest() ? "TEST" : "LIVE") + " priority=" + payu.priority(), now);
        } else {
            jdbc.update("UPDATE provider_configs SET enabled = false, updated_at = ? WHERE provider = 'payu'", now);
        }
    }
}
