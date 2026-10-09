# OBSERVABILITY (spec 21)

Shared by every service through `libs/apg-observability` (also used by the reactive gateway).

## Logs
- **Plain text** by default: `time LEVEL [service,traceId,correlationId] logger : message`.
- **Structured JSON** (logstash format) with `SPRING_PROFILES_ACTIVE=prod`; the correlation id and trace id are separate fields.
- **Secrets are masked in both formats** (`LogMasker`): API keys (`sk_...`), webhook secrets (`whsec_...`), JWTs, `Bearer` tokens, private keys, `password/secret/salt/token/hash/cvv/...=value` pairs and Luhn-valid card numbers, including inside stack traces. This is defence in depth: code still must not log secrets.
- `X-Correlation-Id` is created at the gateway (or accepted when well formed), echoed to the caller, put in MDC and forwarded to every internal service call and Kafka event envelope. Never forwarded to third parties (PayU, merchant endpoints).

## Metrics (Prometheus, `/actuator/prometheus`)
- Services expose actuator on their own port (8081-8091); the **gateway serves actuator only on the internal port 8180** (`GATEWAY_MANAGEMENT_PORT`), never on the public 8080. In production keep actuator ports off the load balancer.
- Every metric carries `application=<service>`; HTTP server latency has histograms (p95/p99).
- Business metrics: `apg_payment_transitions_total{from,to,source}`, `apg_payment_completion_seconds{status}`, `apg_provider_calls_total{provider,operation,outcome}`, `apg_provider_call_seconds`, `apg_webhook_callbacks_total`, `apg_webhook_processing_total`, `apg_webhook_backlog`, `apg_webhook_oldest_pending_seconds`, `apg_webhook_dead`, `apg_refund_total{status}`, `apg_refund_in_flight`, `apg_refund_verify_required`, `apg_refund_oldest_in_flight_seconds`, `apg_ledger_postings_total{type}`, `apg_reconciliation_exceptions_total{type}`, `apg_reconciliation_open_exceptions`, `apg_settlements_unreconciled`, `apg_webhook_delivery_total{outcome}`, `apg_webhook_delivery_backlog`, `apg_webhook_delivery_dead`, `apg_outbox_unpublished`, `apg_outbox_oldest_unpublished_seconds`, `apg_outbox_published_total`, `apg_dlq_records_total{service,topic}`. Kafka lag comes from the Kafka client metrics (`kafka_consumer_fetch_manager_metrics_records_lag_max`).
- Labels never contain ids, emails or other customer data (tested). Backlog gauges are DB queries cached for 15 s.

## Traces (OpenTelemetry)
Spans and `traceId`/`spanId` exist in every service (visible in logs); W3C `traceparent` is forwarded between services. **Export is off** until you set `APG_TRACING_ENDPOINT` to an OTLP/HTTP traces URL (e.g. `http://collector:4318/v1/traces`); tune `management.tracing.sampling.probability` (default 0.1). The gateway and Spring-instrumented clients propagate automatically; internal `RestClient` calls forward the current trace through `OutboundHeaders`.

## Local dashboards and alerts
`docker compose --profile observability up -d` starts Prometheus (:9090) and Grafana (:3000, admin/admin - change it) scraping all twelve processes on the host; dashboard "APG overview" is provisioned from `infrastructure/observability/grafana/dashboards`. Alert rules (`infrastructure/observability/alerts.yml`, checked with `promtool`: 16 rules): service down, payment failure rate, payments in VERIFY_REQUIRED, provider errors and latency, 5xx, outbox lag, dead-letter growth, Kafka lag, provider callback backlog/dead, merchant webhook backlog, refund needing an operator / stuck, reconciliation mismatch, unreconciled settlement. No Alertmanager receiver is configured: add Slack/email/PagerDuty routing in the real environment (external dependency).

## Runbook hints
| Alert | First look |
|---|---|
| ProviderErrorsHigh | `provider_attempts` table, PayU status page, circuit breaker in provider-integration logs; do not hammer PayU (it rate-limits) |
| ProviderCallbackBacklog / Dead | `webhook_events` (status RETRYING/DEAD) and orchestrator health |
| OutboxLagging | Kafka reachable? `apg_outbox_publish_failures_total` |
| DeadLetterGrowth | read `<topic>.DLT` (see docs/EVENTS.md), fix cause, re-publish |
| RefundNeedsOperator | `POST /internal/v1/refunds/{id}/resolve` after checking PayU's own records |
| ReconciliationMismatch | `GET /internal/v1/exceptions?status=OPEN` |
