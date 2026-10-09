package com.apg.settlement.service;

import com.apg.common.metrics.DbGauges;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Backlog / health gauges for alerting (docs/OBSERVABILITY.md). Computed from this service's own tables, cached ~15 s. */
@Component
public class SettlementMetrics {
    private final ObjectProvider<DbGauges> gauges;

    public SettlementMetrics(ObjectProvider<DbGauges> gauges) {
        this.gauges = gauges;
    }

    @PostConstruct
    void register() {
        DbGauges g = gauges.getIfAvailable();
        if (g == null) return;
        g.register("apg_reconciliation_open_exceptions", "Reconciliation exceptions that are OPEN or INVESTIGATING",
                "SELECT COUNT(*) FROM reconciliation_exceptions WHERE status IN ('OPEN','INVESTIGATING')");
        g.register("apg_settlements_unreconciled", "Settlement batches that are not RECONCILED",
                "SELECT COUNT(*) FROM settlements WHERE status <> 'RECONCILED'");
    }
}
