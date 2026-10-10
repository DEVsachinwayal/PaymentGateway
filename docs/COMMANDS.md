# COMMANDS: every command used in this project

Run everything in **Git Bash** from the project root unless stated otherwise (`cd /d/Project/AutonomousPaymentGateway`). Docker Desktop must be running for anything that needs the infrastructure.

## 1. Start and stop

| Command | What it does |
|---|---|
| `bash scripts/demo-up.sh` | **Everything for the demo:** Docker infra + 13 services + demo data + dashboard + sample shop |
| `bash scripts/demo-down.sh` | Stops the services, dashboard and sample shop (Docker infra stays up) |
| `docker compose up -d` | Starts PostgreSQL (5433), Redis, Kafka |
| `docker compose down` | Stops PostgreSQL, Redis, Kafka (data volumes are kept) |
| `docker compose down -v` | Same, **and deletes all database data** (clean start) |
| `bash scripts/dev-start.sh` | Starts the 13 services (builds first if jars are missing) |
| `bash scripts/dev-start.sh --build` | Forces a rebuild, then starts the services |
| `bash scripts/dev-stop.sh` | Stops the 13 services (needed before `mvn clean`) |
| `cd frontend && npm run dev` | Dashboard on http://localhost:5173 |
| `cd frontend && npm install` | Installs dashboard packages (first time on a computer) |
| `set -a; . .run/sample-shop.env; set +a; node examples/sample-shop/server.js` | Sample shop on http://localhost:4000 (started by `demo-up.sh` already) |

### Everything in Docker (only Docker Desktop needed)
| Command | What it does |
|---|---|
| `bash scripts/docker-demo-up.sh` | Builds the images, starts 13 services + dashboard + sample shop, seeds demo data; dashboard http://localhost:8000 |
| `bash scripts/docker-demo-down.sh` | Stops it (add `--wipe` to delete the database too) |
| `docker compose -f docker-compose.yml -f docker-compose.apps.yml -f docker-compose.demo.yml up -d --build` | The same without the script |
| `docker compose -f docker-compose.yml -f docker-compose.apps.yml -f docker-compose.demo.yml ps` | Container status |
| `docker compose -f docker-compose.yml -f docker-compose.apps.yml -f docker-compose.demo.yml logs -f payment-service` | Follow one service's log |
| `docker compose -f docker-compose.yml -f docker-compose.apps.yml -f docker-compose.demo.yml -f docker-compose.observability.yml --profile observability up -d prometheus grafana` | Adds Prometheus (:9090) and Grafana (:3000, admin/admin, dashboard "APG overview") to the all-in-Docker mode. If the Grafana image cannot be downloaded, set `APG_GRAFANA_IMAGE=grafana/grafana:latest` first |

## 2. Setup (one time)

| Command | What it does |
|---|---|
| `cp .env.example .env` | Creates your `.env`; fill in the values (never commit it) |
| `bash scripts/gen-jwt-keys.sh` | Generates the RS256 key pair into `.env` (does not overwrite existing keys) |
| `java -version`, `mvn -v`, `node -v`, `docker ps` | Check the prerequisites |

## 3. Demo data and payments

| Command | What it does |
|---|---|
| `bash scripts/demo-seed.sh` | Creates demo account, TEST key, webhook, 6 payments, 2 payment links |
| `bash scripts/new-test-payment.sh <email> <password> [rupees]` | Creates a sandbox checkout link for an account |
| `bash scripts/smoke-events.sh` | Dev-only simulated provider success: checks webhook + ledger (PASS/FAIL) |
| `python3 scripts/webhook_receiver.py` | Tiny local receiver to watch merchant webhooks |

Demo login: `demo@apg.test` / `Demo-Passw0rd-2026`.

## 4. Build and test

| Command | What it does |
|---|---|
| `mvn -DskipTests package` | Builds all jars (`target/`) without tests |
| `mvn -q -pl services/auth-service -am package -DskipTests` | Builds one service (replace the module) |
| `bash scripts/dev-stop.sh` then `mvn clean test` | Full test suite (about 10 minutes; needs Docker) |
| `export TESTCONTAINERS_RYUK_DISABLED=true` | Needed on this Windows setup before running tests |
| `mvn -pl services/payment-service -am test` | Tests of one service |
| `cd frontend && npm run build` | Production build of the dashboard |

## 5. Load test (JMeter)

| Command | What it does |
|---|---|
| `bash scripts/perf-start.sh` | Stack in load-test mode (relaxed rate limit, verification worker off) |
| `bash performance/setup-data.sh` | Creates `performance/data/credentials.csv` |
| `"/d/apache-jmeter-5.6.3/bin/jmeter.bat" -n -t performance/jmeter/apg-load.jmx -l performance/results/run1.jtl -e -o performance/results/run1-report -Jhost=localhost -Jport=8080 -Jthreads=20 -Jduration=60 -Jrampup=10 -Jcreds="$(pwd -W)/performance/data/credentials.csv"` | Headless run; open `performance/results/run1-report/index.html` |
| `python3 performance/jmeter/generate_plan.py` | Regenerates `apg-load.jmx` after editing the generator |

Use a new report folder name for every run (JMeter refuses an existing one). After a load test go back with `bash scripts/dev-stop.sh` then `bash scripts/demo-up.sh`.

## 6. Looking inside

| Command | What it does |
|---|---|
| `curl http://localhost:8080/actuator/health` | Gateway health (actuator also on 8180) |
| `tail -f .run/payment-service.log` | Follow a service log (`.run/<service>.log`) |
| `docker exec -it autonomouspaymentgateway-postgres-1 psql -U apg -d apg_payment` | Open a database (`apg_auth`, `apg_merchant`, `apg_order`, `apg_payment`, `apg_provider`, `apg_orchestrator`, `apg_webhook`, `apg_refund`, `apg_ledger`, `apg_settlement`, `apg_notification`, `apg_paymentlink`) |
| `\dt` , `\q` | Inside psql: list tables, quit |
| `select id, status, amount from payments order by created_at desc limit 10;` | Latest payments |
| `bash scripts/kafka-peek.sh <topic> [max]` | Show records of a Kafka topic (for example `payment.success.DLT`) |
| `docker compose logs -f postgres` | Infrastructure logs |
| `docker compose --profile observability up -d` | Prometheus + Grafana (http://localhost:3000) |

## 7. Backup, deploy, release (not needed for the demo)

| Command | What it does |
|---|---|
| `bash scripts/backup-postgres.sh [target-dir]` | Dumps every APG database |
| `pg_restore -d <db> --clean --if-exists <file>` | Restores one dump |
| `./scripts/deploy.sh v1.0.0` | Deploys a release tag on a server (auto rollback on failure) |
| `./scripts/rollback.sh [vX.Y.Z]` | Manual rollback |
| `docker compose -f docker-compose.yml -f docker-compose.apps.yml up -d` | Runs the services as containers instead of jars |

## 8. Git

| Command | What it does |
|---|---|
| `git status`, `git log --oneline | head` | State and recent commits |
| `git checkout -b feature/<name>` | New branch for a piece of work |
| `git add -A && git commit -m "message"` | Commit |
| `git checkout main && git merge --no-ff feature/<name>` | Merge into main (branches are kept) |
| `git push origin main` | Upload to GitHub |

## 9. Move the project to another computer

1. Zip the folder without `node_modules`, `target`, `.run` (keep `.git` and `.env`) or `git clone` and copy `.env` separately (README section 13).
2. Install Docker Desktop, JDK 21/25, Maven 3.9, Node 22, Git.
3. `cd frontend && npm install && cd ..`
4. `bash scripts/demo-up.sh` (rebuilds `target/` and `.run/` by itself).

## 10. When something goes wrong

| Symptom | Command / fix |
|---|---|
| Services stuck or out of memory | `bash scripts/dev-stop.sh`; `export APG_JAVA_OPTS="-Xmx300m"`; `bash scripts/dev-start.sh` |
| `mvn clean` fails on a locked jar | `bash scripts/dev-stop.sh` first |
| Login "not found" | `cd frontend && npm run dev` again, Ctrl+F5 |
| Start completely fresh | `bash scripts/demo-down.sh`, `docker compose down -v`, then `bash scripts/demo-up.sh` |
