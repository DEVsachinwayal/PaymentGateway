package com.apg.common.events;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transactional-outbox relay: events written next to a state change (table {@code outbox_events}) are published to Kafka,
 * topic = event type (spec 17), key = aggregate id (ordering per payment/refund/...). Delivery is at-least-once: a crash between
 * the broker ack and the {@code published_at} update re-sends the event, so every consumer must be idempotent (spec 17.1).
 * Order is kept: the first failure stops the batch and the next tick retries from the same event.
 * Required columns: id, event_type, aggregate_id, payload, occurred_at, published_at.
 */
public class OutboxPublisher implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private record Row(UUID id, String type, UUID aggregate, String payload) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final KafkaTemplate<String, String> kafka;
    private final Clock clock;
    private final int batchSize;
    private final long intervalMillis;
    private ScheduledExecutorService scheduler;

    public OutboxPublisher(JdbcTemplate jdbc, TransactionTemplate tx, KafkaTemplate<String, String> kafka, Clock clock,
                           int batchSize, long intervalMillis) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.kafka = kafka;
        this.clock = clock;
        this.batchSize = batchSize;
        this.intervalMillis = intervalMillis;
    }

    /** @return number of events published in this round */
    public int publishBatch() {
        Integer n = tx.execute(status -> {
            List<Row> rows = jdbc.query("""
                    SELECT id, event_type, aggregate_id, payload FROM outbox_events WHERE published_at IS NULL
                    ORDER BY occurred_at, id LIMIT ? FOR UPDATE SKIP LOCKED""",
                    (rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getString("event_type"), rs.getObject("aggregate_id", UUID.class),
                            rs.getString("payload")), batchSize);
            int sent = 0;
            for (Row r : rows) {
                try {
                    ProducerRecord<String, String> rec = new ProducerRecord<>(r.type(), r.aggregate().toString(), r.payload());
                    rec.headers().add("eventId", r.id().toString().getBytes(StandardCharsets.UTF_8));
                    rec.headers().add("eventType", r.type().getBytes(StandardCharsets.UTF_8));
                    kafka.send(rec).get(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.warn("publishing event {} ({}) failed, will retry: {}", r.id(), r.type(), e.toString());
                    break;                                    // keep order: nothing after this event is sent before it
                }
                jdbc.update("UPDATE outbox_events SET published_at = ? WHERE id = ?", Timestamp.from(clock.instant()), r.id());
                sent++;
            }
            return sent;
        });
        return n == null ? 0 : n;
    }

    @Override
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "outbox-publisher");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                int n = publishBatch();
                if (n > 0) log.debug("published {} outbox event(s)", n);
            } catch (RuntimeException e) {
                log.warn("outbox publish round failed: {}", e.toString());
            }
        }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        if (scheduler != null) scheduler.shutdownNow();
        scheduler = null;
    }

    @Override
    public boolean isRunning() { return scheduler != null; }
}
