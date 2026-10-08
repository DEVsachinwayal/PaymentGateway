# APG – WORK.md: instructions for any AI session (read first; renamed from CLAUDE.md)

Project: Autonomous Payment Gateway. Spec = `docs/MASTER_SPEC_EXTRACT.txt` (extracted from the .docx; authoritative).
User communicates in Marathi/English. User asked: build the whole project per spec, keep notes so a new chat can continue, commit after each task.

## Resume protocol (new chat)
1. Read `docs/WORK_LOG.md` (last entries), `docs/TODO.md`, `docs/DECISIONS.md`.
2. Run `git log --oneline | head` and `git status`.
3. Continue from the "Next step" in the last WORK_LOG entry. One milestone (M0..M16) at a time.
4. After each task: update WORK_LOG, CHANGELOG, TODO (+DECISIONS), then `git commit`.

## Environment facts
- Windows 11. Real JDK: `C:\Program Files\Java\jdk-25.0.4.1` (system JAVA_HOME is wrong -> set `JAVA_HOME` per shell before mvn).
- Maven: `D:\Tools\apache-maven-3.9.16`. Node 22. Docker CLI present; daemon may need to be started (Docker Desktop).
- Stack decision: Spring Boot 3.5.16, Java release 21 (runs on 25), multi-module Maven. See DECISIONS.md.
- Never commit secrets; `.env` is git-ignored.

## Milestones (17 total: M0..M16) – update status after each commit
| # | Milestone | Status |
|---|---|---|
| M0 | Repo + docs skeleton | DONE |
| M1 | Foundation (parent pom, apg-common, docker infra) | DONE |
| M2 | API Gateway + Auth (JWT, refresh, roles) | DONE |
| M3 | Merchant (API keys, webhook config) - backend done, dashboard UI pending | DONE (backend) |
| M4 | Order | DONE |
| M5 | Payment (state machine, idempotency) | DONE |
| M6 | Orchestrator | DONE (tests 15/15) |
| M7 | Provider adapter + PayU sandbox | DONE offline (22 tests + 1 live signature check); REAL browser payment still to be completed |
| M8 | Webhook | DONE (9 tests); needs real PayU callback proof |
| M9 | Refund | todo |
| M10 | Ledger | todo |
| M11 | Settlement + Reconciliation | todo |
| M12 | Kafka/Redis/Notifications/DLQ | todo |
| M13 | Security + Observability | todo |
| M14 | Performance (JMeter) | todo |
| M15 | OCI + CI/CD (needs user's OCI account) | todo |
| M16 | Release readiness | todo |
Frontend (React merchant dashboard) is built incrementally alongside M3..M11.

## Git branching rules (from user request, 2026-10-09)
- `main` = integrated, always-green code. Never develop directly on main (docs-only notes excepted).
- ONE BRANCH PER SERVICE: `feature/<service-name>` (e.g. `feature/order-service`, `feature/payment-service`). Work and commit there; before merge run that service's tests (+ gateway tests if the gateway changed).
- Merge into main with `git merge --no-ff feature/<service>` and KEEP the branch (do not delete) so later changes to that service start from it.
- Later changes to an existing service: `git checkout feature/<service>` (merge main into it first), commit, merge back with --no-ff.
- Changes that touch shared code (`libs/apg-common`, gateway, root pom) go on `feature/common-<topic>` or `feature/api-gateway` branches.
- Existing services (auth, gateway, merchant, common) were built on main before this rule; their branches are created at the current main so future work follows the rule: `feature/auth-service`, `feature/api-gateway`, `feature/merchant-service`, `feature/apg-common`.
- Commit message prefix: `<service>: ...` e.g. `order-service: add idempotent create`.

## CURRENT STATE (updated 2026-10-09, end of chat 1) – READ THIS FIRST
- Branch workflow in force (see rules above). All work is merged to `main`; feature branches kept.
- Services built + tested: auth(8081) merchant(8082) order(8083) payment(8084) provider-integration(8085) payment-orchestrator(8086) webhook-service(8087) api-gateway(8080). Docker infra (postgres on **5433**, redis, kafka) via `docker compose up -d`.
- Run everything: `bash scripts/dev-start.sh` (stop: `bash scripts/dev-stop.sh`). Logs in `.run/`. If a service fails with NoClassDefFound / "bean IdempotencyService not found", its fat jar lacks apg-common's `AutoConfiguration.imports` (IDE interference during `mvn clean package`): `rm -rf services/<svc>/target && mvn -q package -DskipTests`.
- After editing tests use `mvn clean test` for that module (stale compiled classes once hid a new test).
- PayU TEST key/salt are in `.env` (verified: PayU accepted our signature). Never print them.
- **PENDING (user action):** one real sandbox payment through the browser. A payment (id in `.run/last-payment-id`, link in `.run/last-checkout-url`, expires 30 min) was created; if expired create a new one: register/login -> POST /api/v1/merchants -> POST /api/v1/api-keys -> POST /api/v1/orders -> POST /api/v1/payments -> POST /api/v1/payments/{id}/checkout (all with Idempotency-Key where required), open `checkoutUrl`, pay with PayU test instruments (docs/PAYU_INTEGRATION.md), land on /payment-result, then check `GET /api/v1/payments/{id}/status` (expect SUCCESS, history PENDING->PROCESSING->SUCCESS) and tables `webhook_events`, `verification_log`, `provider_attempts`.
- Tip when scripting JSON: use `grep -o '"id":"[^"]*"' | head -1` (first match); a greedy sed picked the nested customer id once.

## NEXT STEPS (in order)
1. Finish/verify the real PayU sandbox payment (above); fix whatever it reveals (callback field names, hash with real data).
2. M9 Refund service (branch feature/refund-service): refunds + refund_attempts, over-refund protection, idempotency, uses provider-integration `/refunds` (PayU cancel_refund_transaction), payment REFUND_PENDING -> REFUNDED/PARTIALLY_REFUNDED, ledger later.
3. M10 Ledger (double-entry), M11 Settlement/Reconciliation, M12 Kafka/Redis/Notifications (outbox publishers exist in payment + webhook DBs), M13 security/observability, M14 JMeter, M15 OCI/CI-CD, M16 release docs.
4. React dashboard (frontend/ still empty), ops APIs (activate merchant, grant roles), fill the stub docs (ARCHITECTURE, SERVICES, DATABASE, SECURITY, TESTING...).
