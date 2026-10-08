package com.apg.common.money;

import java.util.Currency;

/** Money as integer minor units (paise/cents). Never floating point (spec 4.2). */
public record Money(long minorUnits, String currency) {
    public Money {
        if (minorUnits < 0) throw new IllegalArgumentException("amount must be >= 0");
        Currency.getInstance(currency); // validates ISO-4217 code
    }

    public Money plus(Money o) { requireSame(o); return new Money(Math.addExact(minorUnits, o.minorUnits), currency); }

    public Money minus(Money o) { requireSame(o); return new Money(Math.subtractExact(minorUnits, o.minorUnits), currency); }

    public boolean isGreaterThan(Money o) { requireSame(o); return minorUnits > o.minorUnits; }

    private void requireSame(Money o) {
        if (!currency.equals(o.currency)) throw new IllegalArgumentException("currency mismatch");
    }
}
