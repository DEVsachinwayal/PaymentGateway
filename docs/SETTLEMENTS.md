# SETTLEMENTS

Service: `services/settlement-service` (port 8090, DB `apg_settlement`). A **settlement batch** is the provider's statement of what it paid out to APG.

## Evidence in
`POST /internal/v1/settlements/import` (operators, internal token). Body: provider, batchRef, settlementDate, currency, the provider's declared totals (gross, refunds, fees incl. tax, net) and the items:
- `PAYMENT` {providerReference = our txnid, [providerPaymentId], amount, fee, tax} -> net = amount - fee - tax
- `REFUND` {providerReference = our refund token, amount} -> net = -amount
- `ADJUSTMENT` {providerReference?, net signed} (chargebacks, corrections) - always needs a human decision
Idempotent per (provider, batchRef): same document => replay; a different document for the same batch => 409 BATCH_CONFLICT. The document and its SHA-256 are stored and the database refuses to edit/delete evidence (triggers).
Automatic fetching from PayU is NOT built: it needs PayU's settlement API/access (external). When it exists, an adapter simply calls this import API.

## Outcome
- All items matched (or acknowledged by an operator) and totals agree => batch `RECONCILED`, merchant shares are calculated (`merchant_settlements`: gross, refunds, provider fees, net, status CALCULATED) and `settlement.created` is published once (ledger posts bank / provider fees / receivable).
- Otherwise `HAS_EXCEPTIONS`; if lookups could not be answered (a service was down) the batch stays `IMPORTED` and the worker retries every 10 min.
- A merchant share becomes `PAID` only with a payout reference (bank UTR) as evidence.
Merchant read API (dashboard users): `GET /api/v1/settlements`, `GET /api/v1/settlements/{id}`.
Operator API: `/internal/v1/settlements[...]`, `/internal/v1/exceptions`, `/internal/v1/merchant-settlements/{id}/paid`.
