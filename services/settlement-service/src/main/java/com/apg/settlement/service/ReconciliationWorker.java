package com.apg.settlement.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Re-evaluates batches that are not reconciled yet (e.g. a lookup was unavailable, or APG's record caught up). */
@Component
public class ReconciliationWorker {
    private static final Logger log = LoggerFactory.getLogger(ReconciliationWorker.class);
    private final SettlementService service;
    private final SettlementProperties props;

    public ReconciliationWorker(SettlementService service, SettlementProperties props) {
        this.service = service;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${apg.settlement.rerun.interval:10m}", initialDelayString = "${apg.settlement.rerun.interval:10m}")
    public void run() {
        if (!props.rerun().enabled()) return;
        try {
            int n = service.rerunUnfinished();
            if (n > 0) log.info("re-ran reconciliation for {} unfinished settlement(s)", n);
        } catch (RuntimeException e) {
            log.warn("reconciliation re-run failed: {}", e.toString());
        }
    }
}
