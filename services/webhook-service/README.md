# webhook-service (port 8087, DB `apg_webhook`)
Public provider callback endpoint `POST /api/v1/webhooks/{provider}` (form-urlencoded or JSON). Serves two callers: the customer's browser (PayU posts to surl/furl; we answer **303** to the merchant `returnUrl` or `/payment-result`, with only `status` and `paymentId`) and the provider's server webhook (200 JSON).
Flow (spec 12): size/shape limits -> authenticate via provider-integration (hash) -> fingerprint -> store sanitized allow-listed fields (no names, emails, cards, hashes) -> de-duplicate (unique per provider+fingerprint) -> orchestrator `verify` (asks the provider; the callback itself never changes a payment) -> record result.
- Forged callback: 400 INVALID_SIGNATURE, stored as REJECTED without payload, never forwarded.
- Verifier down: 503 (provider retries). Orchestrator down: 200 ack, event RETRYING with exponential backoff (10s·2^n, max 15 min), DEAD after 8 attempts (ops attention). Unknown transaction: UNMATCHED, not retried.
- Duplicates/replays: acknowledged, not reprocessed.
- Tables: webhook_events, webhook_delivery_attempts, outbox_events (`webhook.received` for Kafka in M12).
- `GET /payment-result` neutral landing page: fixed messages only, never reflects request text.
Env: POSTGRES_USER/PASSWORD, INTERNAL_SERVICE_TOKEN, PROVIDER_SERVICE_URI, ORCHESTRATOR_SERVICE_URI, PAYMENT_SERVICE_URI.
