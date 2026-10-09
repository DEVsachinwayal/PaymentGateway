# DEPLOYMENT

## Artifacts
| Artifact | Built by | Notes |
|---|---|---|
| 12 service images `apg/<service>:<tag>` | `Dockerfile` (one parametrised multi-stage file; `--build-arg SERVICE=... PORT=... HEALTH_PORT=...`) | JRE 21 alpine, non-root user, `MaxRAMPercentage=70`, container health check on the actuator, **no secrets baked in** (`.dockerignore` excludes `.env`) |
| dashboard image `apg/dashboard:<tag>` | `frontend/Dockerfile` | nginx serves the static bundle and proxies `/api/`, `/checkout/`, `/payment-result` to the gateway; security headers on every location (tested) |
| Compose files | `docker-compose.yml` (infra) + `docker-compose.apps.yml` (apps) + `docker-compose.prod.yml` (OCIR images) | each container gets only the secrets it needs; only the dashboard port is published |

## Environments
| Profile | How | Logging |
|---|---|---|
| local dev | `docker compose up -d` + `bash scripts/dev-start.sh` (JVMs on the host, heap capped by `APG_JAVA_OPTS`) | plain text with trace/correlation ids |
| local containers | `docker compose -f docker-compose.yml -f docker-compose.apps.yml up -d --build` | JSON (`prod` profile) |
| production | `docker-compose.prod.yml` on the OCI VM, deployed by `scripts/deploy.sh <tag>` | JSON, masked |
Tests use their own containers (Testcontainers) and never need `.env`.

## Configuration (names only: `.env.example`, `docs/CONFIGURATION.md`)
Secrets (`JWT_PRIVATE_KEY`, `JWT_PUBLIC_KEY`, `INTERNAL_SERVICE_TOKEN`, `API_KEY_HASH_PEPPER`, `WEBHOOK_SECRET_KEY`, `CHECKOUT_TOKEN_SECRET`, `PAYU_MERCHANT_KEY/SALT`, DB and Redis passwords) come from the environment (OCI Vault -> environment file on the host, mode 600). Production must NOT set the dev switches `NOTIFICATION_ALLOW_INSECURE_URLS`, `MERCHANT_ALLOW_INSECURE_WEBHOOK_URLS`, `ORCHESTRATOR_VERIFICATION_ENABLED=false`, `GATEWAY_RATE_LIMIT_*` (relaxed values), and must use `PAYU_BASE_URL=https://secure.payu.in` only after live onboarding (external).

## Release procedure
1. Merge to `main` (CI green: tests, secret scan, dependency scan, image scan).
2. `git tag vX.Y.Z && git push --tags` -> `release.yml` builds and pushes images to OCIR.
3. A reviewer approves the `production` environment -> `scripts/deploy.sh vX.Y.Z` on the VM: pull, `up -d`, wait for every container to be healthy, **automatic rollback** to the previous tag if not.
4. Post-deploy checks: dashboard login, `GET /actuator/health` on 8180 (private), Grafana "APG overview" (error ratio, outbox lag, DLQ = 0), one test order.

## Rollback
`scripts/rollback.sh` (previous tag) or `scripts/rollback.sh vX.Y.Z`. Flyway migrations are forward-only, so every migration must be **backward compatible with the previous release** (expand/contract: add nullable columns / new tables first, switch code, drop old things in a later release). A release that breaks this rule needs a database restore instead of a rollback.

## Database
One database per service (`apg_*`), created by `infrastructure/postgres/init`. Flyway runs at service start and needs DDL rights: in production use a migration role per database for deployments and a **least-privilege runtime role** (DML only) for the running service (not implemented yet: services currently use one role). Ledger, settlement-evidence and audit tables are protected by triggers even against that role's UPDATE/DELETE.
Backups: managed-database automated backups + PITR in production; `scripts/backup-postgres.sh` for ad-hoc encrypted dumps. **Test a restore before go-live.**

## Capacity (from docs/PERFORMANCE.md, indicative only)
Laptop numbers (165 order creates/s with every service on one machine) say nothing about OCI sizing: repeat the JMeter plan on the target shape. JVM memory: ~400-500 MB per service container; plan for 12 services + Postgres + Kafka + Redis (>= 8 GB RAM for a single-VM pilot).
