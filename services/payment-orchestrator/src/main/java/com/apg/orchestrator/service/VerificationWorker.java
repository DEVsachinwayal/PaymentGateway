package com.apg.orchestrator.service;

import com.apg.orchestrator.client.PaymentClient;
import com.apg.orchestrator.client.PaymentClient.Pending;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Safety net for lost callbacks, crashes and unknown outcomes: periodically re-verifies payments that have been
 * sitting in PENDING / PROCESSING / VERIFY_REQUIRED. Safe to run on several instances because every transition
 * is idempotent and guarded by optimistic locking.
 */
@Component
public class VerificationWorker {
    private static final Logger log = LoggerFactory.getLogger(VerificationWorker.class);
    private final PaymentClient payments;
    private final VerificationService verification;
    private final OrchestratorProperties props;

    public VerificationWorker(PaymentClient payments, VerificationService verification, OrchestratorProperties props) {
        this.payments = payments;
        this.verification = verification;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${apg.orchestrator.verification.interval:30s}", initialDelayString = "${apg.orchestrator.verification.interval:30s}")
    public void scheduled() {
        if (!props.verification().enabled()) return;
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.warn("verification run failed: {}", e.toString());
        }
    }

    /** @return number of payments examined */
    public int runOnce() {
        int n = 0;
        for (Pending p : payments.pending("PENDING,PROCESSING,VERIFY_REQUIRED", props.verification().minAge().toSeconds(), 50)) {
            if (p.providerReference() == null) continue;
            try {
                verification.verify(p.providerReference(), "VERIFICATION");
                n++;
            } catch (RuntimeException e) {
                log.warn("verification of {} failed: {}", p.providerReference(), e.toString());
            }
        }
        return n;
    }
}
