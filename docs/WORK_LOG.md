# Work Log (chronological, newest at bottom)

## 2026-10-08 – M0 Repository + documentation contract
- Inspected folder: only the master .docx existed. Not a git repo -> `git init`.
- Extracted spec to `docs/MASTER_SPEC_EXTRACT.txt`.
- Created folder layout, .gitignore, .env.example, WORK.md (resume instructions), docs skeleton.
- Tools: Java 25.0.4.1, Maven 3.9.16 (JAVA_HOME env var broken -> set per shell), Git 2.51, Node 22, Docker CLI (daemon not running at check time).
- Next step: M1 Foundation (parent pom, apg-common lib, docker-compose, health/logging).

## 2026-10-08 – M1 Foundation
- Added root `pom.xml` (Spring Boot 3.5.16 parent, Java release 21) and module `libs/apg-common`:
  ApiResponse/ApiError envelope, ApiException, GlobalExceptionHandler (hides internals), CorrelationIdFilter (X-Correlation-Id -> MDC), Money (minor units), auto-configuration.
- Tests: MoneyTest (4) + CorrelationWebTest (2) -> 6/6 pass (`mvn test` with JAVA_HOME=C:\Program Files\Java\jdk-25.0.4.1).
- Added `docker-compose.yml` (postgres16, redis7, kafka 3.9 KRaft) and postgres init script creating one DB per service. Local `.env` created (git-ignored, dummy local passwords).
- `docker compose config` valid. **NOT runtime-verified: Docker Desktop daemon was not running.** User must start Docker Desktop, then `docker compose up -d` to verify.
- Bash heredocs with certain content failed on this machine; use the Write tool for creating files.
- Next step: M2 – start Docker, then create `services/auth-service` (Flyway + JPA + JWT + refresh rotation) and `services/api-gateway` (Spring Cloud Gateway 2025.0.x).

## 2026-10-08 – M1 runtime verification
- Started Docker Desktop; `docker compose up -d`: postgres16, redis7, kafka all **healthy**; 10 per-service databases (apg_auth ... apg_notification) created by init script.
- Docker Hub pulls of `apache/kafka:3.9.0` failed (network EOF/DNS). Switched to locally cached `apache/kafka-native:4.1.2`; healthcheck changed to `nc -z localhost 9092` (native image has no kafka bin scripts). Decision D4.
- Local .env (git-ignored): POSTGRES_USER=apg, dummy local passwords.
- Next step: M2 – services/auth-service then services/api-gateway.

## 2026-10-09 – M2 part 1: auth-service
- Built `services/auth-service`: Flyway V1 (users, roles, user_roles, refresh_sessions, login_audit), BCrypt(12), JWT, refresh rotation + reuse detection, lockout, audit; security config denies everything except auth endpoints/health.
- Tests: AuthFlowTest 8/8 pass on real Postgres (Testcontainers). Smoke test on compose DB: register+login OK, rows present in apg_auth.
- Problem found: native Postgres on host :5432 hijacked connections -> compose Postgres moved to :5433 (D5).
- Run command used: `set -a; . ./.env; set +a; java -jar services/auth-service/target/auth-service-0.1.0-SNAPSHOT.jar` (.env has JWT_SECRET appended locally).
- Next step: M2 part 2 – `services/api-gateway` (Spring Cloud Gateway: route /api/v1/auth/** to :8081, JWT validation for protected routes, correlation ID, rate limit, CORS, body size limit).

## 2026-10-09 – M2 part 2: api-gateway  => M2 DONE
- Built `services/api-gateway` (Spring Cloud 2025.0.3): JWT global filter, header anti-spoofing, correlation ID, Redis rate limit, request-size limit, CORS, secure headers, route `/api/v1/auth/**` -> auth-service.
- Tests: GatewayFlowTest 7/7 (stub backend + real Redis container). E2E smoke with both services + compose DB/Redis: register/login/refresh/reuse-detection all correct through :8080.
- Run: `set -a; . ./.env; set +a` then `java -jar` auth-service (8081) and api-gateway (8080). Kill with `taskkill //F //IM java.exe`.
- Known gaps (tracked in TODO): admin API for granting privileged roles; X-Forwarded-For key for rate limiter behind OCI LB; asymmetric JWT keys.
- Next step: M3 Merchant – `services/merchant-service` (merchants, merchant_users, api_keys hashed + shown once, rotate/revoke, webhook_endpoints), gateway route + API-key authentication filter, then start `frontend/` login + API keys page.
