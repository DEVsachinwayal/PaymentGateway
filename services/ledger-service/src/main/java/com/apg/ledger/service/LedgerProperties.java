package com.apg.ledger.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "apg.ledger")
public record LedgerProperties(Fee fee) {
    public record Fee(int percentBps, long fixedMinor) {
        public Fee {
            if (percentBps < 0 || percentBps > 10_000 || fixedMinor < 0) {
                throw new IllegalStateException("apg.ledger.fee: percent-bps must be 0..10000 and fixed-minor >= 0");
            }
        }

        /** Never more than the gross amount, so the merchant payable can never go negative from a fee. */
        public long on(long gross) {
            long fee = (gross * percentBps + 5_000) / 10_000 + fixedMinor;
            return Math.min(fee, gross);
        }
    }
}
