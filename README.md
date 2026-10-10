# Autonomous Payment Gateway (APG)

A payment gateway platform for merchants (think a small Razorpay). A merchant creates orders and payments through an API or the dashboard; the customer pays on a secure page (UPI, card, net banking or wallet, through PayU); APG **verifies** the result, keeps a double-entry ledger, handles refunds, settlement and reconciliation, and tells the merchant's website with signed webhooks.

> **Status: final-year project / demo, PayU sandbox only. No real money moves.** APG is not a bank or card network; real payments would need a licensed provider account (PayU live KYC), legal/licence work and a PCI assessment (see `docs/TODO.md`).
> New here (or a new AI chat)? Read **WORK.md** first, then `docs/README.md` for the documentation map.

---

## 1. What it does

| Feature | Where to see it |
|---|---|
| Merchant sign-up, login, business profile, API keys (test/live) | Dashboard -> Settings, API keys |
| Create orders and payments (API or **Collect payment** page) | Dashboard -> Collect payment, Orders, Payments |
| Customer chooses **UPI / Card / Net banking / Wallet**, pays on PayU's hosted page | payment page opened from checkout / payment link |
| **Payment links** (shareable link, one-time or reusable) | Dashboard -> Payment links |
| Verified payment status with full timeline | Payments -> open a payment |
| Refunds (full/partial, limits, idempotent) | Payment detail -> Refund; Refunds page |
| Immutable double-entry ledger | Ledger |
| Settlement batches and reconciliation exceptions | Settlements |
| Signed merchant webhooks, delivery attempts, resend | Webhooks, Deliveries |
| Audit trail | Activity |
| Sample merchant website using the API | http://localhost:4000 |
| Metrics, alerts (optional) | Prometheus / Grafana |

## 2. Architecture in one minute

```
 Customer browser ──► payment page (APG) ──► PayU hosted page (UPI/card/netbanking/wallet)
        ▲                                         │ callback (hash-verified)
        │                                         ▼
 Merchant website ──► API gateway :8080 ──► order · payment · provider-integration · orchestrator
 Dashboard :5173          │                      │            │ (verifies with PayU status API)
                          ▼                      ▼ events (outbox → Kafka)
                      auth · merchant     ledger · refund · settlement · webhook · notification
```
13 Spring Boot services (Java 21), PostgreSQL (one database per service), Redis (rate limit), Kafka (events), React + TypeScript dashboard. Details: `docs/ARCHITECTURE.md`, `docs/SERVICES.md`.

| Component | Port |
|---|---|
| api-gateway (the only public entry) | 8080 (actuator 8180) |
| auth / merchant / order / payment | 8081 / 8082 / 8083 / 8084 |
| provider-integration / payment-orchestrator | 8085 / 8086 |
| webhook / refund / ledger / settlement / notification | 8087 / 8088 / 8089 / 8090 / 8091 |
| payment-link | 8092 |
| Dashboard (Vite) | 5173 |
| Sample shop | 4000 |
| PostgreSQL / Redis / Kafka (Docker) | 5433 / 6379 / 9092 |

## 3. Requirements

Windows with **Git Bash**, **Docker Desktop** (running), **Java 25** (builds Java 21 bytecode; set `JAVA_HOME`), **Maven 3.9**, **Node 22**, about **8 GB free RAM**, internet (PayU sandbox is online).

## 4. Run it (demo mode: one command)

First time only:
1. `cp .env.example .env` and fill in the values. **Never commit `.env`.**
   - `bash scripts/gen-jwt-keys.sh` generates the token signing keys.
   - `PAYU_MERCHANT_KEY` / `PAYU_MERCHANT_SALT`: from your PayU **test** account.
2. `cd frontend && npm install` (once).

Every time:
```
bash scripts/demo-up.sh      # infrastructure + 13 services + demo data + dashboard + sample shop (3-5 minutes)
bash scripts/demo-down.sh    # stop everything   (docker compose down also stops Postgres/Redis/Kafka)
```
It prints the login and a payment link:

| What | Value |
|---|---|
| Dashboard | http://localhost:5173 |
| Login | `demo@apg.test` / `Demo-Passw0rd-2026` |
| Sample shop (merchant's own website) | http://localhost:4000 |

The seed data contains 6 payments (marked "SIMULATED (demo seed)" in history) and 2 payment links. Payments you make live are real PayU sandbox payments.

### Manual start (if you prefer step by step)
```
docker compose up -d                     # Postgres (5433), Redis, Kafka
bash scripts/dev-start.sh --build        # build + start the services, waits until all are healthy (logs in .run/)
bash scripts/demo-seed.sh                # optional demo data
cd frontend && npm run dev               # dashboard
bash scripts/dev-stop.sh                 # stop services (needed before mvn clean: running jars are locked on Windows)
```

## 5. How to make a payment (customer side)

### Option A: from the dashboard
1. Log in -> **Collect payment**.
2. Enter amount (rupees), description, customer name/email -> create. You get a checkout link.
3. Open the link: the APG payment page shows the four methods.
4. Pick **UPI**, **Card**, **Net banking** or **Wallet** (or "Show all payment options"). PayU's page opens directly on that method.
5. Pay with the **test details below**. You are returned to the result page; the payment becomes SUCCESS only after APG verifies it with PayU.

### Option B: from the sample shop (merchant's website using our API)
http://localhost:4000 -> **Buy with APG** -> choose a method -> pay on PayU -> the shop shows **PAID** when it receives our signed webhook.

### Option C: payment link
Dashboard -> **Payment links** -> create (amount, description, one-time or reusable) -> open the link in a new tab or on a phone on the same network.

### Option D: pure API
```
POST /api/v1/auth/register, /login                    -> dashboard JWT
POST /api/v1/merchants                                -> create your business
POST /api/v1/api-keys {"label":"x","mode":"TEST"}     -> secret shown once
POST /api/v1/orders          (Authorization: Bearer sk_test_..., Idempotency-Key: <uuid>)
POST /api/v1/payments {"orderId":"..."}               (Idempotency-Key)
POST /api/v1/payments/{id}/checkout                   -> checkoutUrl: send the customer there
GET  /api/v1/payments/{id}/status                     -> verified state + history
POST /api/v1/refunds {"paymentId":"..."}              (Idempotency-Key)
```
Full reference: `docs/API_REFERENCE.md`; merchant integration (webhook signature checking): `docs/INTEGRATION_GUIDE.md`.

## 6. PayU sandbox test details (type these on PayU's page)

| Method | What to enter |
|---|---|
| **Card** | number `5123456789012346`, CVV `123`, expiry any future date, name anything, OTP `123456` |
| **UPI** | VPA `anything@payu` (for example `test@payu`), then approve in the sandbox flow |
| **Net banking** | choose the test bank, user `payu`, password `payu`, OTP `123456` |
| **Wallet** (Paytm test) | mobile `7777777777`, OTP `888888` |

How each works in APG: the customer's choice on our page is sent to PayU as `enforce_paymethod` (`upi`, `creditcard|debitcard`, `netbanking`, `cashcard`), so PayU opens directly on that method. Card details are typed on **PayU's** page; APG never sees or stores card numbers or CVV. Details: `docs/PAYU_INTEGRATION.md`.

To test failure: use the wrong OTP or press cancel on PayU's page; the payment becomes FAILED. If the browser never returns, the verification worker asks PayU after about 60 seconds and settles it.

## 7. What to look at after a payment

1. **Payments -> the payment**: timeline CREATED -> PENDING -> PROCESSING -> SUCCESS, with who changed it.
2. **Ledger**: two balanced entries (provider receivable / merchant payable).
3. **Webhook deliveries**: the signed notification sent to the merchant, with **Resend**.
4. **Refund**: payment detail -> Refund (partial allowed; limited to what was captured).
5. **Activity**: who did what.
6. **Databases** (PostgreSQL, host `localhost`, port `5433`, user `apg`, password from `.env`): `apg_payment`, `apg_ledger`, `apg_order`, `apg_merchant`, ... one database per service.
   ```
   docker exec -it autonomouspaymentgateway-postgres-1 psql -U apg -d apg_payment
   select id, status, amount, currency from payments order by created_at desc limit 10;
   ```

## 8. Tests and load test

- Unit/integration tests: `bash scripts/dev-stop.sh`, then `mvn clean test` (about 10 minutes; Testcontainers needs Docker; set `TESTCONTAINERS_RYUK_DISABLED=true`). See `docs/TESTING.md`.
- Load test (JMeter, plan in `performance/jmeter/`): `bash scripts/perf-start.sh`, `bash performance/setup-data.sh`, then run `jmeter.bat -n -t performance/jmeter/apg-load.jmx -l performance/results/run1.jtl -e -o performance/results/run1-report -Jhost=localhost -Jport=8080 -Jthreads=20 -Jduration=60 -Jrampup=10 -Jcreds=<absolute path to performance/data/credentials.csv>` and open `run1-report/index.html`. Results: `docs/PERFORMANCE.md` (about 130 requests/s on a laptop, 0 errors). Restart with `scripts/demo-up.sh` afterwards: perf mode relaxes the rate limit and disables the verification worker.

## 9. Troubleshooting

| Symptom | Fix |
|---|---|
| Login says "not found" | Dashboard proxy lost: stop and `cd frontend && npm run dev` again, Ctrl+F5 |
| PayU page does not open | Internet / PayU sandbox down; backup: `bash scripts/smoke-events.sh` (simulated provider success, dev tool only) |
| Service not up / "too many clients" / out of memory | `bash scripts/dev-stop.sh`, close other apps, `APG_JAVA_OPTS="-Xmx300m"`, start again |
| `mvn clean` fails on a jar | A service is still running: `bash scripts/dev-stop.sh` first |
| Paste shows `^[[200~` in Git Bash | Terminal paste marker: type the command or paste again without the leading characters |

More: `docs/TROUBLESHOOTING.md`, `docs/DEMO_GUIDE.md` (12-minute demo flow, talking points, viva Q&A).

## 10. Repository map

| Path | What |
|---|---|
| `libs/apg-common`, `libs/apg-observability` | envelope, errors, idempotency, trust filter, outbox publisher, audit; logging/metrics/tracing defaults |
| `services/*` | the 13 services (each has a README) |
| `frontend/` | React merchant dashboard (Vite + TypeScript) |
| `examples/sample-shop/` | dependency-free Node merchant website using the API |
| `infrastructure/` | Postgres init, Prometheus/Grafana/alerts |
| `scripts/` | demo up/down/seed, dev start/stop, smoke test, key generation, deploy/rollback, backups |
| `performance/` | JMeter plan + quick load tools |
| `docs/` | architecture, API, events, security, runbooks, demo guide, release readiness |

## 11. Ground rules this project follows

- Money is integer minor units (paise), never floats.
- A payment becomes SUCCESS only after the provider confirms it (hash + status API + amount check), never from the browser.
- No card data is received or stored; the customer pays on the provider's hosted page.
- Secrets live only in the environment (`.env`, git-ignored); never in code, logs or screenshots.
- Every change of state is recorded; corrections are compensating entries, never edits (the ledger is immutable, enforced by database triggers).
- Every money-moving request is idempotent.

## 12. Honest limits

Sandbox only (no real money); PayU is the only provider (no failover); cloud deployment files (Docker images, GitHub Actions, OCI) are written but not run in a real cloud; settlement files are imported through an operator API (PayU auto-fetch needs a live account).
