package com.apg.common.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Gauges backed by a cheap COUNT-style query (backlogs, open exceptions, oldest pending age). The value is cached for a short
 * time so Prometheus scraping can never hammer the database, and a failing query keeps the last known value.
 */
public class DbGauges {
    private static final Logger log = LoggerFactory.getLogger(DbGauges.class);
    private static final long TTL_MILLIS = 15_000;

    private final JdbcTemplate jdbc;
    private final MeterRegistry registry;

    public DbGauges(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.registry = registry;
    }

    /** @param sql a query returning one numeric column and one row */
    public void register(String name, String description, String sql, String... tags) {
        AtomicLong readAt = new AtomicLong(0);
        AtomicReference<Double> last = new AtomicReference<>(Double.NaN);
        Gauge.builder(name, () -> {
            long now = System.currentTimeMillis();
            if (now - readAt.get() > TTL_MILLIS) {
                try {
                    Number n = jdbc.queryForObject(sql, Number.class);
                    last.set(n == null ? 0.0 : n.doubleValue());
                    readAt.set(now);
                } catch (RuntimeException e) {
                    log.warn("gauge {} could not be refreshed: {}", name, e.toString());
                    readAt.set(now);                       // do not retry on every scrape
                }
            }
            return last.get();
        }).description(description).tags(tags).register(registry);
    }
}
