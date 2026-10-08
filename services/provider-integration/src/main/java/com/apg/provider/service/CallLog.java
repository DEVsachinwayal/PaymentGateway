package com.apg.provider.service;

import com.apg.common.web.CorrelationIdFilter;
import java.sql.Timestamp;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Sanitized record of every provider interaction (provider_attempts). Never pass secrets, hashes or card data here. */
@Component
public class CallLog {
    private static final Logger log = LoggerFactory.getLogger(CallLog.class);
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public CallLog(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void record(String provider, String operation, String reference, String outcome, Integer httpStatus,
                       long latencyMs, String detail) {
        try {
            jdbc.update("""
                    INSERT INTO provider_attempts (provider, operation, reference, outcome, http_status, latency_ms, detail, correlation_id, created_at)
                    VALUES (?,?,?,?,?,?,?,?,?)""", provider, operation, reference, outcome, httpStatus, (int) latencyMs,
                    detail == null ? null : detail.substring(0, Math.min(500, detail.length())),
                    MDC.get(CorrelationIdFilter.MDC_KEY), Timestamp.from(clock.instant()));
        } catch (Exception e) {
            // logging must never break a payment flow
            log.warn("could not write provider_attempts row: {}", e.toString());
        }
    }
}
