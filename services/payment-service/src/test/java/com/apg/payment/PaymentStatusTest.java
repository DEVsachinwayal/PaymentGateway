package com.apg.payment;

import static com.apg.payment.domain.PaymentStatus.*;
import static org.junit.jupiter.api.Assertions.*;

import com.apg.payment.domain.PaymentStatus;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PaymentStatusTest {
    /** The spec (section 7) written out independently of the production table. */
    private static final Map<PaymentStatus, Set<PaymentStatus>> EXPECTED = Map.ofEntries(
            Map.entry(CREATED, EnumSet.of(PENDING, FAILED)),
            Map.entry(PENDING, EnumSet.of(PROCESSING, FAILED, VERIFY_REQUIRED)),
            Map.entry(PROCESSING, EnumSet.of(AUTHORIZED, SUCCESS, FAILED, VERIFY_REQUIRED)),
            Map.entry(AUTHORIZED, EnumSet.of(CAPTURED, FAILED, VERIFY_REQUIRED)),
            Map.entry(CAPTURED, EnumSet.of(SUCCESS)),
            Map.entry(VERIFY_REQUIRED, EnumSet.of(SUCCESS, FAILED, VERIFY_REQUIRED)),
            Map.entry(SUCCESS, EnumSet.of(REFUND_PENDING)),
            Map.entry(REFUND_PENDING, EnumSet.of(REFUNDED, PARTIALLY_REFUNDED, SUCCESS)),
            Map.entry(PARTIALLY_REFUNDED, EnumSet.of(REFUND_PENDING)),
            Map.entry(FAILED, EnumSet.noneOf(PaymentStatus.class)),
            Map.entry(REFUNDED, EnumSet.noneOf(PaymentStatus.class)));

    @Test void everyPairMatchesTheSpecMatrix() {
        for (PaymentStatus from : values()) {
            for (PaymentStatus to : values()) {
                assertEquals(EXPECTED.get(from).contains(to), from.canTransitionTo(to), from + " -> " + to);
            }
        }
    }

    @Test void dangerousShortcutsAreImpossible() {
        assertFalse(CREATED.canTransitionTo(SUCCESS));      // never straight to success
        assertFalse(PENDING.canTransitionTo(SUCCESS));
        assertFalse(FAILED.canTransitionTo(SUCCESS));       // failed is final
        assertFalse(SUCCESS.canTransitionTo(FAILED));       // a captured payment cannot "become failed"
        assertFalse(SUCCESS.canTransitionTo(REFUNDED));     // must pass through REFUND_PENDING
        assertTrue(PROCESSING.canTransitionTo(VERIFY_REQUIRED)); // timeout => verify, not fail
        assertFalse(PROCESSING.canTransitionTo(CREATED));
    }

    @Test void terminalStates() {
        assertTrue(FAILED.isTerminal());
        assertTrue(REFUNDED.isTerminal());
        assertFalse(SUCCESS.isTerminal());
    }
}
