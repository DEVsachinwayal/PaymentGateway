package com.apg.payment.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The single source of truth for payment state transitions (spec 7). All changes go through
 * {@code PaymentStateService}; nothing else may set a status.
 */
public enum PaymentStatus {
    CREATED, PENDING, PROCESSING, AUTHORIZED, CAPTURED, SUCCESS, FAILED,
    VERIFY_REQUIRED, REFUND_PENDING, REFUNDED, PARTIALLY_REFUNDED;

    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED = Map.of(
            CREATED, EnumSet.of(PENDING, FAILED),
            PENDING, EnumSet.of(PROCESSING, FAILED, VERIFY_REQUIRED),
            // PROCESSING -> SUCCESS: providers that capture immediately (PayU); see DECISIONS D17
            PROCESSING, EnumSet.of(AUTHORIZED, SUCCESS, FAILED, VERIFY_REQUIRED),
            AUTHORIZED, EnumSet.of(CAPTURED, FAILED, VERIFY_REQUIRED),
            CAPTURED, EnumSet.of(SUCCESS),
            // unknown provider outcome is resolved only by verification (never blindly failed or retried)
            VERIFY_REQUIRED, EnumSet.of(SUCCESS, FAILED, VERIFY_REQUIRED),
            SUCCESS, EnumSet.of(REFUND_PENDING),
            REFUND_PENDING, EnumSet.of(REFUNDED, PARTIALLY_REFUNDED, SUCCESS),
            PARTIALLY_REFUNDED, EnumSet.of(REFUND_PENDING),
            FAILED, EnumSet.noneOf(PaymentStatus.class));

    public boolean canTransitionTo(PaymentStatus next) {
        return ALLOWED.getOrDefault(this, EnumSet.noneOf(PaymentStatus.class)).contains(next);
    }

    public boolean isTerminal() {
        return this == FAILED || this == REFUNDED;
    }

    /** Statuses a payment may be moved into by an internal caller. */
    public static Set<PaymentStatus> allowedTargets(PaymentStatus from) {
        return ALLOWED.getOrDefault(from, EnumSet.noneOf(PaymentStatus.class));
    }
}
