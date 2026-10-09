# Autonomous Payment Gateway (APG)

A payment orchestration platform for merchants (think a small Razorpay): merchants create orders and payments through an API, customers pay on the provider's hosted page (PayU first), and APG verifies the result, keeps a double-entry ledger, handles refunds, settlement and reconciliation, and notifies merchants with signed webhooks. APG is **not** a bank or card network: it needs a licensed provider/acquirer (see `docs/TODO.md` for the external, non-software items).

> New here (or a new AI chat)? Read **WORK.md** first, then `docs/README.md` for the documentation map.

## What you need
Java 25 (builds Java 21 bytecode), Maven 3.9, Docker Desktop, Node 22 (dashboard), Git Bash/WSL on Windows. About 8 GB of free RAM for the full stack.

## Run it locally (5 steps)
1. `cp .env.example .env` and fill the values (names only are in `.env.example`; **never commit `.env`**). Generate token keys with `bash scripts/gen-jwt-keys.sh`. PayU sandbox key/salt come from your PayU test account.
2. `docker compose up -d` - Postgres (host port 5433), Redis, Kafka.
3. `bash scripts/dev-start.sh --build` - builds and starts the 12 services (gateway on http://localhost:8080; health of every service is checked; logs in `.run/`). Stop with `bash scripts/dev-stop.sh`.
4. `cd frontend && npm install && npm run dev` - merchant dashboard on http://localhost:5173 (register, create the business, create an API key, ...).
5. Try the money flow without PayU: `bash scripts/smoke-events.sh` (simulates a provider success locally and checks webhook + ledger). For a real sandbox payment follow `docs/PAYU_INTEGRATION.md`.

## First API calls
```
POST /api/v1/auth/register, /login          -> dashboard JWT
POST /api/v1/merchants                      -> create your business (test mode)
POST /api/v1/api-keys {"label":"x","mode":"TEST"}   -> secret shown once
POST /api/v1/orders            (Authorization: Bearer sk_test_..., Idempotency-Key: <uuid>)
POST /api/v1/payments {"orderId": "..."}    (Idempotency-Key)
POST /api/v1/payments/{id}/checkout         -> checkoutUrl: send the customer there
GET  /api/v1/payments/{id}/status           -> verified state + history
POST /api/v1/refunds {"paymentId":"..."}    (Idempotency-Key)
```
Full reference: `docs/API_REFERENCE.md`.

## Repository map
| Path | What |
|---|---|
| `libs/apg-observability`, `libs/apg-common` | shared logging/metrics/tracing defaults; envelope, errors, idempotency, trust filter, outbox publisher, audit |
| `services/*` | api-gateway, auth, merchant, order, payment, provider-integration, payment-orchestrator, webhook, refund, ledger, settlement, notification (each has a README) |
| `frontend/` | React merchant dashboard (Vite + TypeScript) |
| `infrastructure/` | Postgres init, Prometheus/Grafana/alerts |
| `scripts/` | dev start/stop, smoke test, key generation, deploy/rollback, backups |
| `performance/` | JMeter plan + quick load tool |
| `docs/` | architecture, API, events, security, runbooks, release readiness |

## Test it
`bash scripts/dev-stop.sh` (running services lock jars on Windows), then `mvn clean test` (about 10 minutes; Testcontainers needs Docker; on this Windows setup set `TESTCONTAINERS_RYUK_DISABLED=true`). Details: `docs/TESTING.md`.

## Ground rules this project follows
Money is integer minor units. A payment becomes SUCCESS only after the provider confirms it (never from the browser). No card data is ever received or stored. Secrets live only in the environment. Every state change is recorded; corrections are compensating entries, never edits.
