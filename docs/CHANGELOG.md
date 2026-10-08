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
