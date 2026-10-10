# DEMO GUIDE (final-year project presentation)

Goal: show a working payment gateway end to end in about 12 minutes, in test mode (PayU sandbox: no real money). Everything below was exercised on the real local stack.

## 0. Before you present (30 minutes earlier)
1. Close heavy apps; plug in the charger; make sure internet works (PayU sandbox is online). The stack needs ~6 GB RAM and 3-5 minutes to start.
2. `docker compose up -d` (Docker Desktop running).
3. `bash scripts/dev-start.sh` -> wait for "all services UP".
4. `bash scripts/demo-seed.sh` -> prints the dashboard login, a payment link and test card details. (Re-run any time: it adds data, it does not break anything.)
5. In another terminal: `cd frontend && npm run dev`  (dashboard http://localhost:5173).
6. Sample shop (merchant's own website): `set -a; . .run/sample-shop.env; set +a; node examples/sample-shop/server.js` (http://localhost:4000).
7. Open these tabs in advance: dashboard (logged in), sample shop, and keep `docs/ARCHITECTURE.md` diagram handy. Do one throw-away payment now so PayU is "warm".
Login: `demo@apg.test` / `Demo-Passw0rd-2026` (printed by the seed script).

## 1. The story (what to say)
"Businesses cannot all integrate with banks and cards themselves. A payment gateway gives them one API: create an order, send the customer to a secure page, and get a **verified** result, a ledger, refunds and settlement. I built that platform: 13 microservices, a React merchant dashboard, and PayU as the payment provider in sandbox."

## 2. Demo flow (12 minutes)
| # | Show | Say |
|---|---|---|
| 1 | Dashboard **Overview** (seeded data) | merchant sees volume, success rate, recent payments |
| 2 | **Payments -> open one** | timeline CREATED -> PENDING -> PROCESSING -> SUCCESS, who changed it (orchestrator / verification / webhook) |
| 3 | **Sample shop -> Buy with APG** | this is a *different* website using our API with a secret key on its server; it never sees card data |
| 4 | Our **payment page** (method choice) -> choose UPI or Card | customer picks the method on our page; PayU's page opens directly on that method |
| 5 | Pay on PayU (test card / UPI) | the card form is PayU's, so card data never touches our system |
| 6 | Back: shop shows **PAID** | it became paid only from our **signed webhook** (HMAC), not from the browser redirect |
| 7 | Dashboard: payment SUCCESS, **Ledger** | double-entry: provider receivable / merchant payable; cannot be edited or unbalanced (DB enforces it) |
| 8 | Payment page -> **Refund** (partial) | refund limits, idempotency; PayU is asked; state follows |
| 9 | **Webhook deliveries** | every notification, attempts, **Resend** |
| 10 | **Payment links -> create -> open on phone/new tab** | Razorpay-style shareable link; one-time vs reusable |
| 11 | **Activity log** + **Settlements** | audit trail; reconciliation exceptions (explain, import a batch if asked) |
| 12 | (optional) Grafana http://localhost:3000 after `docker compose --profile observability up -d` | metrics and alerts |

Backup if PayU/internet fails: `bash scripts/smoke-events.sh` simulates a provider success locally (clearly a dev tool) so you can still show webhook -> ledger -> dashboard. Say so honestly.

## 3. Test details to type on stage
- Card: `5123456789012346`, CVV `123`, expiry any future date, OTP `123456`
- UPI: `anything@payu`
- Net banking: user `payu`, password `payu`, OTP `123456`
- Wallet (Paytm): `7777777777`, OTP `888888`

## 4. What makes this project strong (talking points)
1. **Never trusts the browser**: SUCCESS only after the provider confirms (hash + status API + amount check); unknown outcomes become VERIFY_REQUIRED, never guessed.
2. **Idempotency** everywhere money moves: double click or retry cannot double charge/refund.
3. **Payment state machine** as the single writer of status, with full history.
4. **Transactional outbox + Kafka**: events are published reliably, consumers are idempotent, poison events go to a dead-letter topic.
5. **Immutable double-entry ledger** enforced by database triggers; corrections are compensating entries.
6. **Reconciliation** with exceptions instead of silent mismatches.
7. **Security**: RS256 tokens, hashed API keys, signed webhooks, SSRF guard, secrets masked in logs, audit trail, CORS/headers.
8. **Observability + tests**: 200+ automated tests, Prometheus/Grafana alerts, load test that found and fixed real bottlenecks (N+1, auth cache).

## 5. Likely viva questions (short answers)
| Question | Answer |
|---|---|
| Why microservices? | separate responsibilities and data ownership (payments, ledger, refunds...), independent failure and scaling; cost: more operations (shown by compose, health, metrics) |
| How do you avoid double payments? | Idempotency-Key per request (stored with the change), unique constraints (one payment per order, one capture posting per payment), webhook fingerprint de-dup |
| What if PayU's callback never arrives? | verification worker asks PayU's status API after 60 s; payment settles, or VERIFY_REQUIRED for an operator |
| What if the webhook is forged? | hash is checked with the merchant salt; unsigned/invalid -> 400 and stored as REJECTED; even a valid one is re-verified with PayU |
| Where is card data stored? | nowhere: the customer pays on PayU's hosted page; we store only references and status (PCI scope reduced) |
| Is it production ready? | for real money it would need PayU live onboarding/KYC, a legal/licence position, PCI assessment, TLS and cloud deployment; the software side (ledger, refunds, reconciliation, security controls) is built and tested; it runs in sandbox |
| What happens if Kafka is down? | events wait in the outbox table and are published in order when it is back; payments themselves do not depend on Kafka |
| How is money represented? | integer paise (never floats); strict JSON parsing rejects 10.5 for an amount |
| How do merchants verify your webhooks? | HMAC-SHA256 over timestamp + raw body, 5-minute window, event id for de-dup (docs/INTEGRATION_GUIDE.md) |
| What would you do next? | operator console, live onboarding flow, QR codes/subscriptions, second provider failover, mTLS and least-privilege DB roles |

## 6. Be honest about
- Sandbox only: no real money, test cards only.
- PayU is the only provider (no failover yet).
- Some pieces are written and unit-tested but not run in a real cloud (Docker images for every service, GitHub Actions, OCI deployment).
- Seeded demo data is created by a dev script (marked "SIMULATED (demo seed)" in payment history); the payments you make live are real sandbox payments.
- Settlement files are imported by an operator API (PayU auto-fetch needs a live account).

## 7. If something breaks on stage
| Symptom | Fix |
|---|---|
| Login says "not found" | Vite proxy lost: stop and `cd frontend && npm run dev` again, Ctrl+F5 |
| PayU page does not open | internet/PayU sandbox down: use `bash scripts/smoke-events.sh` backup, say what it is |
| "403" after paying | should not happen (fixed); if it does, the payment still settles via the verification worker within ~1 minute: refresh Payments |
| A service not up | `bash scripts/dev-start.sh` again (it starts only what is missing is NOT guaranteed: stop with `scripts/dev-stop.sh` first) |
| Out of memory | close other apps; `APG_JAVA_OPTS="-Xmx300m"` before dev-start |
