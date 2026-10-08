package com.apg.webhook.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Re-delivers stored events whose first hand-over failed (and rescues events orphaned by a crash). */
@Component
public class RetryWorker {
    private static final Logger log = LoggerFactory.getLogger(RetryWorker.class);
    private final WebhookService service;
    private final WebhookProperties props;

    public RetryWorker(WebhookService service, WebhookProperties props) {
        this.service = service;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${apg.webhook.retry.interval:30s}", initialDelayString = "${apg.webhook.retry.interval:30s}")
    public void run() {
        if (!props.retry().enabled()) return;
        try {
            int n = service.retryDue();
            if (n > 0) log.info("retried {} webhook event(s)", n);
        } catch (RuntimeException e) {
            log.warn("webhook retry run failed: {}", e.toString());
        }
    }
}
