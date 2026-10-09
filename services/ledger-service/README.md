# ledger-service (port 8089, DB `apg_ledger`)
Immutable double-entry ledger (spec 14). Amounts are minor units; one currency per transaction.
- Events (spec 17 envelope) -> postings via `POST /internal/v1/ledger/events` (internal token). Same handler will be used by the Kafka consumer in M12. Idempotent per `eventId` and per (type, reference).
  - `payment.success`: DR PROVIDER_RECEIVABLE_{PROVIDER} gross / CR MERCHANT_PAYABLE gross-fee / CR APG_FEE_REVENUE fee.
  - `refund.success`: DR MERCHANT_PAYABLE amount / CR PROVIDER_RECEIVABLE_{PROVIDER} amount (fee on the original payment is not returned).
  - everything else is ignored. Settlement postings arrive with M11.
- Fee is placeholder pricing: `LEDGER_FEE_BPS` (default 0) + `LEDGER_FEE_FIXED_MINOR` (default 0), capped at the payment amount.
- Integrity is enforced by the database: UPDATE/DELETE/TRUNCATE on ledger rows raise; a deferred trigger refuses (at COMMIT) any transaction that is unbalanced, has < 2 entries or mixes currencies.
- Corrections: `POST /internal/v1/ledger/transactions/{id}/reverse` writes a compensating REVERSAL (once per transaction; a reversal cannot be reversed).
- Merchant read API (dashboard users MERCHANT_ADMIN/DEVELOPER, API keys rejected by the gateway): `GET /api/v1/ledger/balance` (payable, captured, fees, refunded, adjustments per currency), `GET /api/v1/ledger/transactions?page&size`.
- Ops: `GET /internal/v1/ledger/trial-balance` (total debits == total credits per currency).
Env: POSTGRES_USER/PASSWORD, INTERNAL_SERVICE_TOKEN, LEDGER_FEE_BPS, LEDGER_FEE_FIXED_MINOR.
