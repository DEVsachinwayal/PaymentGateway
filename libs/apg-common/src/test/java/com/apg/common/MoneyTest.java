package com.apg.common;

import static org.junit.jupiter.api.Assertions.*;

import com.apg.common.money.Money;
import org.junit.jupiter.api.Test;

class MoneyTest {
    @Test void addsAndSubtractsExactly() {
        Money a = new Money(1050, "INR");
        assertEquals(new Money(1550, "INR"), a.plus(new Money(500, "INR")));
        assertEquals(new Money(50, "INR"), a.minus(new Money(1000, "INR")));
    }
    @Test void rejectsNegativeAndBadCurrency() {
        assertThrows(IllegalArgumentException.class, () -> new Money(-1, "INR"));
        assertThrows(IllegalArgumentException.class, () -> new Money(1, "XXZ9"));
    }
    @Test void rejectsCurrencyMismatch() {
        assertThrows(IllegalArgumentException.class, () -> new Money(1, "INR").plus(new Money(1, "USD")));
    }
    @Test void rejectsNegativeResult() {
        assertThrows(IllegalArgumentException.class, () -> new Money(1, "INR").minus(new Money(2, "INR")));
    }
}
