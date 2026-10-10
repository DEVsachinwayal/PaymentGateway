# Autonomous Payment Gateway (APG)

A payment gateway platform for merchants (think a small Razorpay). A merchant creates orders and payments through an API or the dashboard; the customer pays on a secure page (UPI, card, net banking or wallet, through PayU); APG **verifies** the result, keeps a double-entry ledger, handles refunds, settlement and reconciliation, and tells the merchant's website with signed webhooks.

> **Status: final-year project / demo, PayU sandbox only. No real money moves.** APG is not a bank or card network; real payments would need a licensed provider account (PayU live KYC), legal/licence work and a PCI assessment (see `docs/TODO.md`).
> New here (or a new AI chat)? Read **WORK.md** first, then `docs/README.md` for the documentation map.

---

## Quick start on another laptop (only Docker Desktop needed)

Nothing else has to be installed: no Java, Maven, Node, PostgreSQL, Redis or Kafka. Everything is built and run inside Docker.

**1. Install** [Docker Desktop](https://www.docker.com/products/docker-desktop/) (keep "Use WSL 2" ticked), restart the computer, open Docker Desktop and wait until it says **Engine running**. Give it 6-8 GB RAM (Settings -> Resources). Needs about 15 GB free disk, 8 GB RAM, internet, and virtualization enabled in the BIOS.
(Optional: Git for Windows if you want to use the `bash scripts/...` commands; PowerShell works without it.)

**2. Extract** the project zip, for example to `D:\Project\AutonomousPaymentGateway`. The hidden `.env` file must be in that folder next to `README.md`.

**3. Run** (use ONE of these):

PowerShell:
```
cd D:\Project\AutonomousPaymentGateway
docker compose -f docker-compose.yml -f docker-compose.apps.yml -f docker-compose.demo.yml up -d --build
```
Git Bash:
```
cd /d/Project/AutonomousPaymentGateway
bash scripts/docker-demo-up.sh
```
The first run takes 20-40 minutes (images are built); later runs take 1-2 minutes. Check with `docker ps` (all containers should become "healthy").

**4. Open**

| What | Address |
|---|---|
| Dashboard | http://localhost:8000 |
| Login | `demo@apg.test` / `Demo-Passw0rd-2026` |
| Sample shop | http://localhost:4000 |

**5. Pay** (PayU sandbox, no real money): card `5123456789012346`, CVV `123`, any future expiry, OTP `123456`; UPI `anything@payu`; net banking user `payu` / password `payu`, OTP `123456`; wallet `7777777777`, OTP `888888`. More in section 6.

**6. Stop** (the same way you started):

PowerShell:
```
docker compose -f docker-compose.yml -f docker-compose.apps.yml -f docker-compose.demo.yml down
```
Git Bash:
```
bash scripts/docker-demo-down.sh            # add --wipe to delete the database too
```

| Problem | Fix |
|---|---|
| `docker: command not found` / "cannot connect to the Docker daemon" | open Docker Desktop and wait for "Engine running" |
| error about a value "set in .env" | `.env` is missing from the project folder |
| `port is already allocated` | close the program using port 8000, 4000 or 5433 |
| `No space left on device` | free disk space, or move Docker's disk to another drive (Settings -> Resources -> Advanced -> Disk image location), then run the command again |
| image download fails with EOF / network error | run the same command again; finished work is reused |
| a container keeps restarting | `docker logs <container-name>`; see section 13.6 |

Everything else (running without Docker, tests, load test, all commands): the sections below and `docs/COMMANDS.md`.

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
Full reference: `docs/API_REFERENCE.md`; **every script/command of the project: `docs/COMMANDS.md`**; merchant integration (webhook signature checking): `docs/INTEGRATION_GUIDE.md`.

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

---

## 13. Running the project on another computer

Two ways to move the project: a **zip of the whole folder** (includes `.env`) or **git clone** (then copy `.env` separately). `.env` holds all secrets, is git-ignored, and must never be pushed to GitHub, e-mailed or shared; delete it from the pen drive after copying.

### 13.1 Install on the new computer (one time)

| # | Software | Used for | Get it from |
|---|---|---|---|
| 1 | **Docker Desktop** (open it and wait until the engine is running) | PostgreSQL, Redis, Kafka | docker.com |
| 2 | **Java JDK 21 or 25** | the 13 services | adoptium.net (Temurin) |
| 3 | **Maven 3.9** | building the services | maven.apache.org (binary zip) |
| 4 | **Node.js 22 LTS** | the dashboard | nodejs.org |
| 5 | **Git for Windows** (includes Git Bash) | running the `.sh` scripts | git-scm.com |

After installing:
- Set `JAVA_HOME` to the JDK folder (Windows: Edit the system environment variables -> Environment Variables -> New) and add Maven's `bin` folder to `Path`.
- Open a **new** Git Bash and check: `java -version`, `mvn -v`, `node -v`, `docker ps`. All four must work.
- Needs about 8 GB free RAM and internet (Docker images, PayU sandbox).

### 13.2 Get the project

**Option 1: zip.** Zip the whole project folder on the old computer, copy it, extract it somewhere with a short path and no spaces or non-English characters (for example `D:\Project\AutonomousPaymentGateway`). Check that the hidden `.env` file is in the folder next to `README.md` (VS Code shows it). If `target/`, `node_modules/` or `.run/` came along they can stay, or be deleted to start clean.

**Option 2: git clone.**
```
git clone https://github.com/rohanrk2810/AutonomousPaymentGateway.git
cd AutonomousPaymentGateway
cp /e/.env .          # .env from the pen drive (E: is an example drive letter)
ls -a | grep .env     # must list .env
```
Without a copy of the old `.env`: `cp .env.example .env`, run `bash scripts/gen-jwt-keys.sh`, and fill the other values (random strong secrets, plus the PayU sandbox key and salt).

### 13.3 Run

In Git Bash, from the project folder:
```
cd frontend && npm install && cd ..    # dashboard packages, 2-3 minutes
bash scripts/demo-up.sh                # first run 5-10 minutes (image downloads + build)
```
Then open http://localhost:5173 and log in with `demo@apg.test` / `Demo-Passw0rd-2026`. The sample shop is at http://localhost:4000. Test cards and UPI/net banking details are in section 6.

Stop: `bash scripts/demo-down.sh` and `docker compose down`.

### 13.4 Notes

- The database does not travel with the code; the new computer starts empty and `demo-up.sh` seeds fresh demo data. (To move real data use `scripts/backup-postgres.sh`.)
- Ports 8080-8092, 5173, 4000, 5433, 6379 and 9092 must be free.
- Without PayU key/salt a real sandbox payment cannot start; `bash scripts/smoke-events.sh` still shows a simulated provider success locally (dev tool).

### 13.5 If something goes wrong

| Symptom | Fix |
|---|---|
| "Docker is not running" / `docker: command not found` | open Docker Desktop and wait until it is ready |
| Build fails / `JAVA_HOME is not set` | set `JAVA_HOME`, open a new Git Bash |
| `port is already allocated` | close the program using that port (8080, 5173, 4000, ...) |
| `.env: No such file` or services will not start | `.env` is not in the project root (13.2) |
| Services not healthy / out of memory | close other apps, `bash scripts/dev-stop.sh`, then `demo-up.sh` again |
| Login says "not found" | restart the dashboard: `cd frontend && npm run dev`, then Ctrl+F5 |
| Stale or corrupt build | `bash scripts/dev-stop.sh`, then `mvn clean` and `npm install`, start again |

### 13.6 Docker-only mode (no Java, Maven or Node on the computer)

If the other computer has **only Docker Desktop** (plus Git Bash for the script, or just PowerShell for the raw command), the whole project runs from containers with one command. Java, Maven and Node are not needed: the images are built inside Docker.

```
bash scripts/docker-demo-up.sh          # builds 13 service images + dashboard, starts everything, seeds demo data
bash scripts/docker-demo-down.sh        # stop (keeps data);  --wipe also deletes the database
```
Same thing without the script (PowerShell or Git Bash, from the project folder; `.env` must be present):
```
docker compose -f docker-compose.yml -f docker-compose.apps.yml -f docker-compose.demo.yml up -d --build
```

| What | Where |
|---|---|
| Dashboard | http://localhost:8000 (login `demo@apg.test` / `Demo-Passw0rd-2026`) |
| Sample shop | http://localhost:4000 |
| Payment link | printed by the script (`http://localhost:8000/pay/...`) |

Notes:
- The first run takes 20-40 minutes (images are built); later runs start in 1-2 minutes. Give Docker Desktop at least 6-8 GB RAM and keep about 15 GB of disk free (images and build cache); move Docker's disk to another drive in Settings -> Resources -> Advanced -> Disk image location if C: is small.
- Only the dashboard (8000) and the sample shop (4000) are published; the services talk to each other inside the Docker network. The database is also on host port 5433.
- `docker-compose.demo.yml` switches on development-only options (webhooks to an http address, a seed job, the sample shop). Never use it on a public server.
- `MERCHANT_ALLOW_INSECURE_WEBHOOK_URLS` and `NOTIFICATION_ALLOW_INSECURE_URLS` are the two switches it sets; the production compose files do not set them.
- Database connections are capped at 5 per service and each JVM heap at 384 MB (`APG_DB_POOL_SIZE`, `APG_JAVA_OPTS` in `.env` override this).
- If a docker build stops with "No space left on device", free disk space first, then run the script again: finished layers are reused. An image built while the disk was full can contain a broken jar ("Invalid or corrupt jarfile"); rebuild it with `docker compose -f docker-compose.yml -f docker-compose.apps.yml -f docker-compose.demo.yml build --no-cache <service>`.
- PayU still needs `PAYU_MERCHANT_KEY` / `PAYU_MERCHANT_SALT` in `.env` (sandbox) for real sandbox payments.
