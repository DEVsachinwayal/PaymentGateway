package com.apg.refund.service;

import com.apg.common.metrics.DbGauges;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Backlog / health gauges for alerting (docs/OBSERVABILITY.md). Computed from this service's own tables, cached ~15 s. */
@Component
public class RefundMetrics {
    private final ObjectProvider<DbGauges> gauges;

    public RefundMetrics(ObjectProvider<DbGauges> gauges) {
        this.gauges = gauges;
    }

    @PostConstruct
    void register() {
        DbGauges g = gauges.getIfAvailable();
        if (g == null) return;
        g.register("apg_refund_in_flight", "Refunds not yet final (CREATED, PROCESSING)",
                "SELECT COUNT(*) FROM refunds WHERE status IN ('CREATED','PROCESSING')");
        g.register("apg_refund_verify_required", "Refunds with an unclear provider outcome waiting for an operator",
                "SELECT COUNT(*) FROM refunds WHERE status = 'VERIFY_REQUIRED'");
        g.register("apg_refund_oldest_in_flight_seconds", "Age of the oldest refund still in flight",
                "SELECT COALESCE(EXTRACT(EPOCH FROM (now() - MIN(created_at))), 0) FROM refunds WHERE status IN ('CREATED','PROCESSING')");
    }
}
