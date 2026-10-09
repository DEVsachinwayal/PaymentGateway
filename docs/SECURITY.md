# SECURITY (spec 19)

What is built, where it is enforced, and what is still external. Everything marked "tested" has an automated test.

## Identity and access
| Area | Implementation |
|---|---|
| Dashboard auth | Short-lived access JWT (15 min) + rotating, single-use refresh tokens (only SHA-256 stored, reuse revokes the whole session family), lockout after 5 failures (15 min). |
| JWT signing | **RS256** when `JWT_PRIVATE_KEY` is set: only auth-service can mint tokens, the gateway verifies with `JWT_PUBLIC_KEY` (kid = key thumbprint; several public keys allowed during rotation; HS secret ignored once a public key is set; algorithm-confusion and `alg=none` tokens rejected - tested). HS256 + `JWT_SECRET` remains as a dev fallback. `scripts/gen-jwt-keys.sh` creates a pair. |
| Merchant API | Secret API keys (`sk_test_`/`sk_live_`): 256-bit random, only an HMAC is stored, shown once, rotation with grace period, revocation; keys only reach `/orders`, `/payments`, `/refunds`. LIVE keys need an ACTIVE merchant (activation after KYC is external). |
| Roles | `MERCHANT_ADMIN` (write) / `MERCHANT_DEVELOPER` (read); the merchant is always derived server-side (never from a client id). Other merchants' data answers 404. |
| Service trust | Services only accept calls carrying `X-Internal-Token` (set by the gateway). The gateway strips every client-supplied identity header, and routes no `/internal/**` path (tested). |
| Operators | Internal APIs (resolve refund, reverse ledger entry, settlement import, exceptions, payouts) are not reachable from the internet; every use is audited with `X-Operator`. A real operator login/RBAC is future work. |

## Money and data integrity
- Money is integer minor units; floats are rejected (`accept-float-as-int=false`), unknown JSON fields are rejected on money endpoints.
- Idempotency keys on order/payment/refund creation; payment status changes only through one state machine; provider answers that are unclear never become SUCCESS or FAILED.
- Ledger: database triggers make entries immutable and refuse unbalanced / one-sided / mixed-currency transactions; corrections are compensating reversals.
- Settlement evidence is hashed and immutable (triggers); payouts are `PAID` only with a payout reference (CHECK constraint).
- Webhooks from the provider: hash verified, replay-safe (fingerprint), the callback alone never changes a payment (status is re-asked from the provider, amount compared).

## Cardholder data
No PAN, CVV or PIN is ever received, stored or logged: the customer pays on the provider's hosted page. Logs mask Luhn-valid card numbers anyway (tested). Webhook storage keeps an allow-list of fields only. Taking card data on APG's own page would need PCI-DSS certification (external).

## Transport and edge
- The gateway sets security headers (HSTS, nosniff, frame deny, referrer policy, CSP) and `Cache-Control: no-store` on every `/api` response including its own errors (tested). Request size limit (256 KB), per-client rate limit (Redis), CORS allow-list.
- Actuator is **not** served on the public port: the gateway uses internal port 8180; other services are only reachable behind it. In production put TLS termination (OCI load balancer / reverse proxy) in front and keep 8081-8091 and 8180 private.
- TLS/mTLS between services is not configured (internal network trust + token). Revisit on OCI with a service mesh or mTLS.

## Outbound calls
- Merchant webhooks: HMAC-SHA256 signature `t=<unix>,v1=<hex>` over `t.body` with a per-endpoint secret (AES-GCM at rest, shown once); https only, targets resolving to private/loopback/link-local/CGNAT/ULA addresses are blocked, redirects never followed, response bodies never read (tested). Residual risk: DNS rebinding between the check and the connect; mitigate at network level (egress proxy) in production.
- Internal client calls forward correlation and trace ids; third parties (PayU, merchants) never receive them.

## Secrets
Only in git-ignored `.env` (names in `.env.example`), injected as environment variables; production: OCI Vault (external). Never printed, never in logs (masked), never in audit rows or metrics labels (tested). Webhook signing secrets are encrypted with `WEBHOOK_SECRET_KEY` (AES-256-GCM); API-key hashes use `API_KEY_HASH_PEPPER`.

## Audit and monitoring
Append-only `audit_events` (merchant, refund, settlement, ledger, notification) written with the action; operator actions are written as ATTEMPT before and SUCCESS after; the merchant admin sees their own log at `GET /api/v1/audit-events`. Login attempts are recorded by auth-service (`login_audit`). Alerts: docs/OBSERVABILITY.md.

## Not done / external
- Dependency and secret scanning in CI (GitHub Actions, M15): OWASP dependency-check, `gitleaks`, Dependabot. Container image scanning.
- Penetration test, TLS certificates, WAF, DDoS protection, OCI IAM/network policy, encrypted backups and database least-privilege roles (one superuser is used locally).
- Operator RBAC/SSO, admin MFA, merchant IP allow-lists, per-API-key rate limits, 2-person approval for manual ledger reversals.
- PCI-DSS, RBI payment aggregator licence, KYC (see docs/TODO.md external dependencies).
