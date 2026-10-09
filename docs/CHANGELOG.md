# Changelog
## Unreleased
- M0: repository initialised, documentation skeleton.
- M1: parent build, apg-common conventions library, local docker-compose infra.
- Renamed CLAUDE.md -> WORK.md (user request); added milestone status table. New chat: tell the AI to read WORK.md first.
- M2.1: auth-service (register/login/refresh/logout).
- M2.2: api-gateway (JWT validation, rate limit, correlation ID, CORS).
- M3: merchant-service, API-key auth at gateway, internal trust token.
- Adopted per-service branch workflow (see WORK.md).
- M4: order-service, orders route.
- M5: payment-service (state machine, history, outbox), payments route.
- M6 orchestrator, M7 provider-integration + PayU sandbox, M8 webhook-service (see WORK_LOG.md).
- Dashboard v1 (React): login, onboarding, overview, payments + timeline, orders, API keys, webhooks, settings.
- M9 refund-service; M10 ledger-service; M11 settlement-service (import, reconciliation, exceptions, merchant shares).
- M12 events: outbox -> Kafka publisher, ledger + notification consumers with retry and dead-letter topics, signed merchant webhooks (retry, resend, history).
- M13 security + observability: RS256 tokens, masked/JSON logs, Prometheus + Grafana + alerts, OTel tracing, audit trail, gateway hardening.
- M14 performance: JMeter plan, load findings fixed (N+1, API-key cache).
- M15 delivery: Dockerfiles, compose overlays, GitHub Actions CI/release, deploy/rollback/backup scripts, OCI design.
- M16 documentation and release-readiness assessment.
