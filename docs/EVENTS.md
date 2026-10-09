# EVENTS (spec 17)

## Pipeline
```
service state change + outbox row (same DB transaction)
        -> OutboxPublisher (apg-common, every 1 s)  -> Kafka topic = event type, key = aggregate id
        -> consumers: ledger-service (money events), notification-service (merchant webhooks)
        -> poison / permanently failing events -> <topic>.DLT
```
Delivery is **at-least-once**; every consumer is idempotent (ledger: unique eventId and unique (type, reference); notification: unique (event, endpoint)).
Publishing is switched on with `APG_EVENTS_ENABLED=true` (default off, `scripts/dev-start.sh` turns it on); Kafka address `KAFKA_BOOTSTRAP_SERVERS` (default localhost:9092).

## Envelope
`{eventId, eventType, version, occurredAt, correlationId, aggregateId, merchantId, payload}`; `merchantId` is `00000000-0000-0000-0000-000000000000` for platform-level events. Amounts are integer minor units.

| Topic | Producer | Consumers | Payload |
|---|---|---|---|
| payment.created / processing / success / failed / unknown | payment-service | ledger (success), notification (success, failed, unknown) | paymentId, orderId, amount, currency, status, provider |
| payment.refund_pending / refunded / partially_refunded | payment-service | (analytics later) | same |
| refund.created / success / failed | refund-service | ledger (success), notification (success, failed) | refundId, paymentId, amount, currency, status, provider |
| webhook.received | webhook-service | (ops/audit) | provider reference, status |
| settlement.created | settlement-service | ledger | settlementId, provider, currency, gross, refunds, providerFees, amount (net) |
| reconciliation.exception | settlement-service | operations (alerting in M13) | settlementId, itemId, type |

## Failure handling
- Producer: a failed send stops the batch (order kept) and is retried next tick; nothing is marked published before the broker acknowledged.
- Consumer: transient errors (database or merchant-service down) retry with exponential backoff 1 s .. 30 s for up to 2 min, then the record is parked on `<topic>.DLT` (any partition) with the exception headers. Malformed or invalid events skip retries and go to the DLT immediately, so one bad record never blocks a partition.
- Inspect a DLT: `docker exec <kafka> /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic payment.success.DLT --from-beginning`. Re-drive after fixing the cause by re-publishing the value to the original topic (consumers are idempotent).

## Merchant webhooks (notification-service)
Merchants subscribe endpoints (dashboard / `POST /api/v1/webhook-endpoints`) to: payment.success, payment.failed, payment.unknown, refund.success, refund.failed. Body: `{"id": <eventId>, "type": ..., "createdAt": ..., "data": {...}}` (no internal ids).
Headers: `X-APG-Signature: t=<unix>,v1=<hex HMAC-SHA256(secret, t + "." + rawBody)>`, `X-APG-Event-Id`, `X-APG-Event-Type`. Receivers must verify the signature over the raw body, reject timestamps older than ~5 minutes, and de-duplicate by event id.
Retries: 10 s * 2^n (max 1 h), 8 attempts, then DEAD; the merchant can **resend** from the dashboard/API (`POST /api/v1/webhook-deliveries/{id}/resend`). A removed endpoint cancels its pending jobs. Only 2xx counts as delivered; redirects are never followed; targets resolving to private/loopback/link-local addresses or plain http are blocked (set `NOTIFICATION_ALLOW_INSECURE_URLS=true` in dev only).
