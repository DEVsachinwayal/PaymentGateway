# TROUBLESHOOTING (spec 30)

Rule for every runbook: **read before you write**, never edit history (ledger, audit, settlement evidence are append-only), and do not hammer PayU (it rate-limits bad callers; the provider circuit breaker opens after repeated failures).
Follow one request through the system with its correlation id (`X-Correlation-Id` header / `requestId` in the response): `grep <id> .run/*.log` locally, or the JSON field `correlationId` in production logs. `docker exec autonomouspaymentgateway-postgres-1 psql -U $POSTGRES_USER -d apg_<db>` opens a SQL shell.

## Payment stuck (PENDING / PROCESSING / VERIFY_REQUIRED)
1. `GET /api/v1/payments/{id}/status` (or `payment_state_history` in apg_payment): which transition was last, and who made it (ORCHESTRATOR / VERIFICATION / WEBHOOK / OPS).
2. Provider attempt: `provider_attempts` in apg_provider (operation, outcome, latency; STATUS/UNKNOWN = PayU unreachable or unclear; REJECTED = PayU said "not found").
3. Verification: `verification_log` in apg_orchestrator. The worker asks PayU every 30 s for payments older than 60 s; PROCESSING longer than 15 min with a still-pending provider -> VERIFY_REQUIRED; "provider never heard of it" after 30 min -> FAILED (customer never arrived).
4. Webhook: `webhook_events` in apg_webhook for the txn reference (status RECEIVED / RETRYING / DEAD / UNMATCHED / REJECTED).
5. If still unclear: compare with PayU's merchant dashboard transaction; only then, if PayU confirms, an operator may move the payment via the internal transition API (`POST /internal/v1/payments/{id}/transition`, source OPS, with a reason) - it still goes through the state machine.
Never mark SUCCESS from a browser redirect or an unsigned callback.

## Customer is sent back from PayU and sees "Access denied / 403"
PayU posts the customer's browser from its own origin to `/api/v1/webhooks/payu`. The gateway's CORS filter must not apply to `/api/v1/webhooks/**`, `/checkout/**`, `/payment-result` (fixed 2026-10-09; regression test `providerReturnsFromAnotherOriginAreNotBlockedByCorsButTheDashboardApiStillIs`). If it reappears, check the gateway logs for "Invalid CORS request". Meanwhile the verification worker still settles the payment by asking PayU.

## Duplicate payment / double charge suspicion
Idempotency record (`idempotency_keys`) -> one payment per order (`PAYMENT_ALREADY_EXISTS`) -> provider reference uniqueness (`payment_attempts.provider_reference`) -> webhook fingerprint (`webhook_events` unique per provider+fingerprint) -> ledger (`ledger_transactions` unique per `(CAPTURE, payment)`). A second capture posting is impossible by constraint; a second provider charge would show as two attempts with different txnids - refund the extra one.

## Duplicate webhook (provider -> APG)
Acknowledged but not reprocessed (`webhook_events` unique fingerprint; `apg_webhook_callbacks_total{result="duplicate"}`). Nothing to do.
## Merchant says they did not receive our webhook
`GET /api/v1/webhook-deliveries?status=DEAD` (or `notification_jobs`): last HTTP status/error per attempt, then fix their endpoint and use **Resend** (`POST /api/v1/webhook-deliveries/{id}/resend`). BLOCKED = their URL resolves to a private address / is not https. They must verify `X-APG-Signature` over the raw body and de-duplicate by event id.

## Refund problems
- `REFUND_EXCEEDS_PAYMENT`: remaining = payment amount - refunds not FAILED (in-flight ones count).
- Stuck PROCESSING: PayU queued it; the worker polls `check_action_status` with backoff (60 s doubling to 30 min). Check `refund_attempts` and `provider_attempts` (REFUND_STATUS).
- `VERIFY_REQUIRED`: the provider answer was unclear, the amount stays reserved. Look at PayU's dashboard, then `POST /internal/v1/refunds/{id}/resolve {"status":"SUCCESS|FAILED","note":"..."}` with header `X-Operator`.
- Payment status wrong after a refund: the sync is idempotent and repaired by the worker (`payment_synced=false` rows).

## Ledger imbalance
Cannot be committed (database trigger). If the books look wrong: `GET /internal/v1/ledger/trial-balance`, find the transaction(s) via `ledger_transactions.reference_id`, post a **compensating reversal** (`POST /internal/v1/ledger/transactions/{id}/reverse`); never update or delete. A missing posting means the event was not consumed: check Kafka below.

## Settlement / reconciliation mismatch
`GET /internal/v1/exceptions?status=OPEN`: each has type, reason and the evidence compared. Fix the cause (e.g. let a payment finish verification), `POST /internal/v1/settlements/{id}/reconcile`, or decide with `PATCH /internal/v1/exceptions/{id}` (INVESTIGATING needs an owner; RESOLVED / WRITTEN_OFF need notes). Batch stuck `IMPORTED`: a lookup service was down; the worker retries every 10 min.

## Kafka / event problems
1. Producer side: `apg_outbox_unpublished` and `apg_outbox_oldest_unpublished_seconds` per service; log `publishing event ... failed` (Kafka unreachable). Events wait in the outbox and go out in order when Kafka is back.
2. Consumer side: `apg_dlq_records_total`, consumer lag. Dead letters: `bash scripts/kafka-peek.sh <topic>.DLT` shows the record and the failure reason; fix the cause; re-publish the value to the original topic (consumers are idempotent). Invalid events go straight to the DLT by design.
3. Duplicates are harmless (ledger unique event id; notification unique event+endpoint).

## Provider outage
Metrics `apg_provider_calls_total{outcome=~"ERROR|UNKNOWN"}`, `GET /internal/v1/providers` (health, circuit state in logs "circuit open"). Payments in flight stay PROCESSING / VERIFY_REQUIRED and are verified when PayU is back; new checkouts keep working until the browser reaches PayU. There is one provider today, so no automatic failover (D24).

## Service will not start
| Symptom | Cause / fix |
|---|---|
| "must be at least ... bytes/chars" or missing property | a required secret is not set (see docs/CONFIGURATION.md) |
| `NoClassDefFound CorrelationIdFilter` / bean `IdempotencyService` missing | fat jar built while the IDE rewrote `target/`: `rm -rf services/<svc>/target && mvn -q package -DskipTests` |
| "too many clients already" | 12 services x connection pool > Postgres `max_connections`; dev-start sets pool 5 |
| "insufficient memory" / `hs_err_pid*.log` | too many JVMs for the RAM; dev-start caps heap (`APG_JAVA_OPTS`); close other apps |
| Flyway checksum / validation error | a migration file was edited after it ran: never edit applied migrations, add a new `V<n>__` |
| 404 on `/actuator/*` of the gateway | by design: actuator is on port 8180 |
| 401 `INVALID_TOKEN` after switching to RS256 | gateway has no `JWT_PUBLIC_KEY`, or tokens issued before the switch (log in again) |
| login 401 / 423 | wrong password / account locked (5 failures, 15 min) |

## Deployment failure (OCI)
Image (OCIR pull/tag) -> configuration and secrets (`docker compose config`, `.env`) -> network (security lists, load balancer health check `/healthz`) -> container health (`docker compose ps`, `docker compose logs <svc>`) -> `scripts/deploy.sh` rolled back automatically? -> `scripts/rollback.sh`. See docs/DEPLOYMENT.md and docs/ORACLE_CLOUD.md.

## Local test environment quirks (Windows)
Stop the dev stack before `mvn`: running JVMs lock jars. Use `mvn clean test` for the module you changed (stale classes once hid a test). Set `TESTCONTAINERS_RYUK_DISABLED=true`. Docker image pulls on this machine's network can fail mid-download: retry, or use a smaller/cached image. If a test about logging fails only in a full multi-module run, rerun the module: it forks a child JVM.
