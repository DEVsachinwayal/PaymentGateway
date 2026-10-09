package com.apg.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Delivers due webhook jobs. Jobs are leased, so several instances can run it without double delivery. */
@Component
public class DeliveryWorker {
    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);
    private final NotificationService service;
    private final NotificationProperties props;

    public DeliveryWorker(NotificationService service, NotificationProperties props) {
        this.service = service;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${apg.notification.worker.interval:2s}", initialDelayString = "${apg.notification.worker.interval:2s}")
    public void run() {
        if (!props.worker().enabled()) return;
        try {
            int n = service.deliverDue();
            if (n > 0) log.debug("attempted {} webhook delivery(ies)", n);
        } catch (RuntimeException e) {
            log.warn("delivery worker run failed: {}", e.toString());
        }
    }
}
