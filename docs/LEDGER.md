# LEDGER

Rules (spec 14): double entry, immutable, balanced, currency + minor units on every entry, compensating transactions for corrections, reconcilable against provider data.

| Business event | Debit | Credit |
|---|---|---|
| Payment captured (gross G, fee F) | PROVIDER_RECEIVABLE_PAYU: G | MERCHANT_PAYABLE: G-F, APG_FEE_REVENUE: F |
| Refund of amount R | MERCHANT_PAYABLE: R | PROVIDER_RECEIVABLE_PAYU: R |
| Reversal of any transaction | every credit of the original | every debit of the original |
| Provider settles to APG / APG pays merchant | M11 | M11 |

Account sides: PROVIDER_RECEIVABLE_* and APG_BANK are assets (debit-normal); MERCHANT_PAYABLE and APG_FEE_REVENUE are liability/revenue (credit-normal). Balances are always derived from entries.
Merchant `payable` = what APG owes the merchant; `adjustments` = payable - (captured - fees - refunded) and is non-zero only after reversals/manual corrections.
Delivery: events arrive over HTTP now; M12 replaces this with a Kafka consumer calling the same handler (idempotent, so at-least-once delivery is safe).
Not decided yet (ask the business): fee on refunds (currently not returned), GST on fees, per-merchant pricing.
