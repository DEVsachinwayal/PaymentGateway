package com.apg.webhook.service;

import com.apg.common.metrics.DbGauges;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Backlog / health gauges for alerting (docs/OBSERVABILITY.md). Computed from this service's own tables, cached ~15 s. */
@Component
public class WebhookMetrics {
    private final ObjectProvider<DbGauges> gauges;

    public WebhookMetrics(ObjectProvider<DbGauges> gauges) {
        this.gauges = gauges;
    }

    @PostConstruct
    void register() {
        DbGauges g = gauges.getIfAvailable();
        if (g == null) return;
        g.register("apg_webhook_backlog", "Provider callbacks stored but not yet handed over (RECEIVED, RETRYING)",
                "SELECT COUNT(*) FROM webhook_events WHERE status IN ('RECEIVED','RETRYING')");
        g.register("apg_webhook_oldest_pending_seconds", "Age of the oldest callback still waiting (webhook lag)",
                "SELECT COALESCE(EXTRACT(EPOCH FROM (now() - MIN(created_at))), 0) FROM webhook_events WHERE status IN ('RECEIVED','RETRYING')");
        g.register("apg_webhook_dead", "Callbacks that exhausted their retries (needs operator attention)",
                "SELECT COUNT(*) FROM webhook_events WHERE status = 'DEAD'");
    }
}
