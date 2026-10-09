# refund-service (port 8088, DB `apg_refund`)
Full and partial refunds (spec 13). Merchant API through the gateway: `POST /api/v1/refunds` (Idempotency-Key required, API key or MERCHANT_ADMIN), `GET /api/v1/refunds/{id}`, `GET /api/v1/refunds?paymentId&page&size`.
Operator API (internal token, not routed): `POST /internal/v1/refunds/{id}/resolve` for refunds stuck in VERIFY_REQUIRED.

Lifecycle: `CREATED` (row written BEFORE the provider call) -> `PROCESSING` (provider accepted/queued) -> `SUCCESS` | `FAILED`; `VERIFY_REQUIRED` when the provider answer was unclear.
- Over-refund protection: per-payment advisory lock + sum of all refunds that are not FAILED (in-flight refunds count). Amount omitted = everything still refundable.
- Idempotent (merchant + Idempotency-Key). Provider token = our `refund_reference` (RF + 18 hex), unique.
- An unclear provider answer is never treated as failed and never retried blindly; it keeps the amount reserved until an operator resolves it.
- `SUCCESS` only when the provider explicitly says so (PayU `check_action_status`); queued/unknown stays PROCESSING with exponential backoff (60s, 2m ... max 30m).
- Payment state (`REFUND_PENDING` / `PARTIALLY_REFUNDED` / `REFUNDED` / back to `SUCCESS`) is derived from the refunds and pushed to payment-service by an idempotent sync; if payment-service is down the worker repairs it later (`payment_synced` flag).
- Tables: refunds, refund_attempts (audit of every provider call), outbox_events (`refund.created|success|failed`, Kafka in M12), idempotency_keys.
Env: POSTGRES_USER/PASSWORD, INTERNAL_SERVICE_TOKEN, PROVIDER_SERVICE_URI, PAYMENT_SERVICE_URI.
