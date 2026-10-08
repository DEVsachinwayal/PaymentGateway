package com.apg.provider.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Opens after N consecutive failures and lets one probe through after the cool-down. */
public class SimpleCircuitBreaker {
    private final int threshold;
    private final Duration coolDown;
    private final Clock clock;
    private int failures;
    private Instant openUntil;

    public SimpleCircuitBreaker(int threshold, Duration coolDown, Clock clock) {
        this.threshold = threshold;
        this.coolDown = coolDown;
        this.clock = clock;
    }

    public synchronized boolean allowRequest() {
        if (openUntil == null) return true;
        if (clock.instant().isBefore(openUntil)) return false;
        openUntil = clock.instant().plus(coolDown); // half-open: one probe, re-close on success
        return true;
    }

    public synchronized void recordSuccess() {
        failures = 0;
        openUntil = null;
    }

    public synchronized void recordFailure() {
        if (++failures >= threshold) openUntil = clock.instant().plus(coolDown);
    }

    public synchronized boolean isOpen() {
        return openUntil != null && clock.instant().isBefore(openUntil);
    }
}
