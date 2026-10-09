# REQUIREMENTS TRACEABILITY

Source of truth: the master implementation documentation (extract in `docs/MASTER_SPEC_EXTRACT.txt`). This table maps each spec section to where it is implemented and tested. "Gap" lists what is deliberately not done.

| Spec | Requirement | Implemented in | Tests / evidence | Gap |
|---|---|---|---|---|
| 4 Principles | provider-agnostic port, unknown outcomes verified, no fake success | `PaymentProvider` port, orchestrator verification worker, PayU mapping (SUCCESS only for success+captured) | PayUProviderTest, OrchestratorFlowTest, real sandbox payment | only one provider (no failover, D24) |
| 5 Services & DBs | 12 services, DB per service, Flyway | `services/*`, `infrastructure/postgres` | all `*FlowTest` | - |
| 6 Idempotency | merchant + key, replay, conflict | `IdempotencyService` (common) | IdempotencyServiceTest, order/payment/refund tests | - |
| 7 Payment lifecycle | state machine, history, optimistic locking, outbox | payment-service `PaymentStatus`, `PaymentStateService` | PaymentStatusTest, PaymentFlowTest | AUTHORIZED/CAPTURED unused by PayU |
| 8 Auth / roles | JWT + rotating refresh, roles, lockout | auth-service, gateway | AuthFlowTest, TokenServiceRsaTest, JwtVerifierTest, GatewayFlowTest | operator roles exist but no API to grant them |
| 9 Provider port | adapter, health, audit | provider-integration | ProviderApiTest, PayUProviderTest | live settlement fetch |
| 10 Orchestration | routing record, timeouts, unknown handling | payment-orchestrator | OrchestratorFlowTest | no automatic failover |
| 11 UPI/Card/NB/Wallet | via provider hosted page, no PIN/CVV | hosted checkout redirect | real sandbox payment | UPI-specific flows untested live |
| 12 Webhooks | signature, replay, dedup, retry | webhook-service; merchant webhooks in notification-service | WebhookFlowTest, NotificationFlowTest | - |
| 13 Refunds | ownership, limits, idempotent, record first, verify, ledger, notify | refund-service | RefundFlowTest (14), real sandbox refund accepted | final success confirmation from PayU pending (docs/RELEASE_READINESS.md) |
| 14 Ledger | double entry, immutable, compensating | ledger-service + DB triggers | LedgerFlowTest, KafkaConsumerTest | fee/GST policy undefined |
| 15 Settlement | batches, mapping, totals, evidence-based status | settlement-service | SettlementFlowTest | PayU auto-fetch |
| 16 Reconciliation | rerunnable, exceptions with owner/reason/evidence | settlement-service | SettlementFlowTest | missing-in-settlement detection |
| 17 Kafka contract | topics, envelope, idempotent consumers, retry + DLQ | OutboxPublisher, ledger/notification consumers | OutboxPublisherTest, KafkaConsumerTest (x2), EventPublishingTest | retry topics are in-process backoff then DLT (no separate retry topics) |
| 18 Redis | rate limiting; idempotency/provider health cache | gateway rate limiter | GatewayFlowTest | no Redis cache for idempotency/provider health (DB is the source of truth) |
| 19 Security | TLS, JWT, API keys, webhooks, input, abuse, logging, DB, dependencies | docs/SECURITY.md | see that file | TLS/pen test/DB roles/scans on CI |
| 20 Testing | unit, repo, API, adapter contract, webhook, idempotency, state, refund, ledger, recon, Kafka retry/DLQ, e2e sandbox, security, JMeter | docs/TESTING.md | mandatory failure scenarios mostly covered | JMeter plan not executed; contract tests vs real PayU only for payment + refund |
| 21 Observability | JSON logs, ids, metrics, traces, Prometheus/Grafana, alerts | libs/apg-observability, infrastructure/observability | LoggingTest, MetricsTest, promtool, live scrape | Alertmanager receivers, trace backend |
| 22 Docker/local | compose infra, health checks, profiles, .env.example | docker-compose*.yml, scripts | validated + dashboard image built | Java service images not built locally (network) |
| 23 OCI | architecture and deployment | docs/ORACLE_CLOUD.md | design only | needs OCI account |
| 24 CI/CD | build, test, scan, push, deploy, rollback | `.github/workflows`, scripts/deploy.sh | YAML/compose validated | never executed |
| 25 Dashboard | login, summary, API keys, orders/payments + timeline, refunds, webhooks + history, settlements, roles | `frontend/` | `npm run build`, API flows | refunds / settlements / ledger / deliveries / activity screens are being added (see WORK.md) |
| 26 Gates | production readiness | docs/RELEASE_READINESS.md | - | external items |
| 28 Definition of done | docs, tests, observability per feature | per service README + docs | - | - |
| 29 External deps | listed | docs/TODO.md | - | all external |
| 30 Troubleshooting | runbooks | docs/TROUBLESHOOTING.md | - | - |
