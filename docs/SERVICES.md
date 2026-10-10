# SERVICES

| Service | Port | DB | Role | Tests |
|---|---|---|---|---|
| api-gateway | 8080 | - (Redis rate limit) | JWT / API-key auth, routing, rate limit, strips identity headers, adds internal token | 15 |
| auth-service | 8081 | apg_auth | register, login, refresh rotation, roles | 8 |
| merchant-service | 8082 | apg_merchant | merchants, API keys, webhook endpoints (+ internal subscriber lookup) | 8 |
| order-service | 8083 | apg_order | orders, customers, idempotent create | 7 |
| payment-service | 8084 | apg_payment | payment state machine (sole status writer), attempts, outbox | 17 |
| provider-integration | 8085 | apg_provider | provider port + PayU adapter (checkout, status, refund, refund status, callback verify) | 24 |
| payment-orchestrator | 8086 | apg_orchestrator | routing, hosted checkout page, verification worker | 15 |
| webhook-service | 8087 | apg_webhook | provider callbacks (hash verified, de-duplicated, replay-safe) | 9 |
| refund-service | 8088 | apg_refund | full/partial refunds, over-refund protection, payment state sync | 14 |
| ledger-service | 8089 | apg_ledger | immutable double-entry ledger, Kafka consumer | 13 |
| settlement-service | 8090 | apg_settlement | provider settlement import, reconciliation, exceptions, merchant shares | 10 |
| notification-service | 8091 | apg_notification | signed merchant webhooks, retries, delivery history | 12 |
| payment-link-service | 8092 | apg_paymentlink | shareable payment links, public customer page | 15 |
| frontend (Vite) | 5173 | - | merchant dashboard | build only |
| libs/apg-common | - | - | envelope, errors, correlation id, idempotency, trust filter, outbox publisher | 14 |

Each service owns its database; services talk through the gateway-hidden `/internal/v1/**` APIs (internal token) and Kafka events (docs/EVENTS.md). Per-service details: `services/<name>/README.md`.
Start everything: `bash scripts/dev-start.sh` (needs `docker compose up -d`); stop: `bash scripts/dev-stop.sh`.
