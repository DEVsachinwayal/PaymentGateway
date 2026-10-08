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

## 2026-10-09 – M3 Merchant  => backend DONE
- apg-common: GatewayTrustFilter (X-Internal-Token), CallerContext + resolver, extra exception mappings (400/404/405).
- merchant-service (8082, DB apg_merchant): merchants/merchant_users/merchant_settings/api_keys/webhook_endpoints, API key create/list/rotate(grace)/revoke, verify endpoint, webhook URL SSRF validator, AES-GCM secret encryption. Tests MerchantFlowTest 8/8.
- api-gateway: API-key auth, merchant lookup for JWT users, anti-spoofing of all identity headers, X-Internal-Token to downstream, merchant routes. Tests 9/9.
- E2E smoke (3 services + DB + Redis): create merchant, TEST key issued once, LIVE key blocked, API key blocked on mgmt endpoints, direct call without token -> 401, /internal not exposed -> 404.
- Fix during work: CHAR(3) vs Hibernate varchar validation -> VARCHAR(3).
- NOT done (explicit): React dashboard pages for login/API keys/webhooks (frontend/ is still empty). Planned as a dedicated frontend step after M4 so the UI can show orders as well.
- .env now also holds INTERNAL_SERVICE_TOKEN, API_KEY_HASH_PEPPER, WEBHOOK_SECRET_KEY (local, git-ignored). Names are listed in .env.example (update it!).
- Next step: M4 Order – `services/order-service` (orders, customers, order_items; amount in minor units; merchant ownership via X-Merchant-Id; Idempotency-Key on create), gateway route /api/v1/orders/**.

## 2026-10-09 – apg-common: IdempotencyService (branch feature/apg-common)
- Added IdempotencyService + auto-config (active when a JdbcTemplate exists). Tests incl. 8-thread concurrent duplicate => action runs once. common 10/10; auth 8/8 and merchant 8/8 still green.

## 2026-10-09 – M4 Order (branch feature/order-service)
- order-service (8083, DB apg_order): orders/customers/order_items/idempotency_keys; create is idempotent via apg-common; strict money validation; tenant isolation. Tests OrderFlowTest 6/6 (incl. 6 concurrent duplicates => 1 order, failed requests leave no idempotency marker, 49.99 rejected).
- Next: gateway route for /api/v1/orders on branch feature/api-gateway, then frontend step, then M5 Payment (branch feature/payment-service).

## 2026-10-09 – gateway route for orders (branch feature/api-gateway) => M4 DONE
- Added route /api/v1/orders -> order-service (ORDER_SERVICE_URI). Gateway tests 9/9.
- E2E smoke (auth+merchant+order+gateway): API-key order create 201, replay with same key => `Idempotent-Replayed: true`, missing key => IDEMPOTENCY_KEY_REQUIRED, dashboard user can list, direct call to order-service => 401; DB has 1 order with amount_minor 49900.
- Next step: M5 Payment on branch `feature/payment-service` (payment_intents, payments, payment_attempts, payment_state_history; central state machine; idempotent create; never trust client-supplied status; calls order-service via internal API to validate order ownership/amount). Frontend (React dashboard) still pending — schedule after M5.

## 2026-10-09 – M5 Payment (branch feature/payment-service)
- payment-service (8084, DB apg_payment): intents/payments/attempts/history/outbox/idempotency tables; central state machine; idempotent create validated against order-service; one open payment per order; internal transition endpoint.
- Tests: PaymentStatusTest 3 (full 11x11 matrix vs spec) + PaymentFlowTest 11 = 14/14 (incl. concurrent create => 1 payment, conflicting concurrent transitions => 1 winner, duplicate webhook no-op, timeout->VERIFY_REQUIRED).
- order-service got `/internal/v1/orders/{id}` (7/7 tests).
- Next: gateway route /api/v1/payments (branch feature/api-gateway), then M6 Orchestrator + M7 Provider/PayU (needs user's PayU sandbox key/salt at M7).
