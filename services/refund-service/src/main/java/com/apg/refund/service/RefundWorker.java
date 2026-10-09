package com.apg.refund.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Safety net and finisher: dispatches refunds that were never sent, asks the provider about accepted ones, syncs payments. */
@Component
public class RefundWorker {
    private static final Logger log = LoggerFactory.getLogger(RefundWorker.class);
    private final RefundService service;
    private final RefundProperties props;

    public RefundWorker(RefundService service, RefundProperties props) {
        this.service = service;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${apg.refund.worker.interval:10s}", initialDelayString = "${apg.refund.worker.interval:10s}")
    public void run() {
        if (!props.worker().enabled()) return;
        try {
            int sent = service.dispatchPending();
            int checked = service.checkDue();
            int synced = service.syncPending();
            if (sent + checked + synced > 0) log.info("refund worker: dispatched={} checked={} payments-synced={}", sent, checked, synced);
        } catch (RuntimeException e) {
            log.warn("refund worker run failed: {}", e.toString());
        }
    }
}
