package com.apg.notification.service;

import com.apg.common.metrics.DbGauges;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Backlog / health gauges for alerting (docs/OBSERVABILITY.md). Computed from this service's own tables, cached ~15 s. */
@Component
public class NotificationMetrics {
    private final ObjectProvider<DbGauges> gauges;

    public NotificationMetrics(ObjectProvider<DbGauges> gauges) {
        this.gauges = gauges;
    }

    @PostConstruct
    void register() {
        DbGauges g = gauges.getIfAvailable();
        if (g == null) return;
        g.register("apg_webhook_delivery_backlog", "Merchant webhook deliveries waiting or retrying",
                "SELECT COUNT(*) FROM notification_jobs WHERE status IN ('PENDING','RETRYING')");
        g.register("apg_webhook_delivery_dead", "Merchant webhook deliveries that gave up (merchant can resend)",
                "SELECT COUNT(*) FROM notification_jobs WHERE status = 'DEAD'");
        g.register("apg_webhook_delivery_oldest_pending_seconds", "Age of the oldest merchant webhook still waiting",
                "SELECT COALESCE(EXTRACT(EPOCH FROM (now() - MIN(created_at))), 0) FROM notification_jobs WHERE status IN ('PENDING','RETRYING')");
    }
}
