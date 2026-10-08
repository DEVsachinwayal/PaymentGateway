# Work Log (chronological, newest at bottom)

## 2026-10-08 – M0 Repository + documentation contract
- Inspected folder: only the master .docx existed. Not a git repo -> `git init`.
- Extracted spec to `docs/MASTER_SPEC_EXTRACT.txt`.
- Created folder layout, .gitignore, .env.example, CLAUDE.md (resume instructions), docs skeleton.
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
