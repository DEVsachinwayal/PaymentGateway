# payment-orchestrator (port 8086, DB `apg_orchestrator`)
Coordinates a payment attempt between payment-service and provider-integration. No provider code, no payment table.
- `POST /api/v1/payments/{id}/checkout` (API key / dashboard user, `Idempotency-Key` required): picks a provider (lowest priority, enabled, healthy; stored in `routing_decisions`), starts attempt N (payment CREATED -> PENDING), returns `checkoutUrl` (signed, 30 min). Provider trouble before redirect => attempt + payment FAILED (nothing reached the provider).
- `GET /checkout/{token}` (public, token only): hosted redirect page -> auto-submitting form to the provider; moves PENDING -> PROCESSING; HTML-escaped, CSP with nonce, no-store.
- `POST /internal/v1/orchestrator/attempts/{ref}/verify {trigger}`: asks the provider for the truth and settles via the state machine. SUCCESS only when provider says captured AND amount equals ours (else VERIFY_REQUIRED); explicit failure => FAILED; provider outage/timeouts => VERIFY_REQUIRED (never FAILED); NOT_FOUND => FAILED only after `abandon-after` (30 min). Idempotent.
- Verification worker (every 30 s) re-verifies PENDING/PROCESSING/VERIFY_REQUIRED payments older than 60 s.
- No automatic provider failover yet (D24).
Env: POSTGRES_USER/PASSWORD, INTERNAL_SERVICE_TOKEN, CHECKOUT_TOKEN_SECRET, PUBLIC_BASE_URL, PAYMENT_SERVICE_URI, PROVIDER_SERVICE_URI.
