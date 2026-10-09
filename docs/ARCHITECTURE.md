# ARCHITECTURE

## Service boundaries
```
                    Merchant server (API key)            Merchant staff (browser)
                                |                                |
                                |                         React dashboard (nginx)
                                v                                v
   Customer browser --> [ api-gateway :8080 ]  JWT RS256 / API-key auth, rate limit, headers, correlation id, routing
                          |   (adds X-Internal-Token + trusted identity headers; strips anything the client sent)
      +---------+---------+--------+---------+----------+-----------+-----------+------------+
      v         v         v        v         v          v           v           v            v
   auth      merchant   order   payment   payment-   webhook     refund     ledger     settlement   notification
   :8081     :8082     :8083    :8084    orchestrator :8087      :8088      :8089       :8090         :8091
                                   ^        :8086        |          |          ^           |            |
                                   |          |          v          |          |           |            |
                                   |     provider-integration :8085 (PayU adapter behind the PaymentProvider port)
   each service owns its own PostgreSQL database (apg_*); no service reads another service's tables
   Kafka carries domain events (outbox -> topic = event type); Redis is used by the gateway for rate limiting
```
| Service | Owns | Calls (HTTP, internal token) |
|---|---|---|
| api-gateway | nothing (stateless) | merchant (API-key / user lookups, cached), every routed service |
| auth | users, refresh sessions, login audit | - |
| merchant | merchants, API keys (hashed), webhook endpoints (secrets encrypted), audit | - |
| order | orders, customers, items | - |
| payment | payments, attempts, state history, outbox - **the only writer of payment status** | order (read order) |
| payment-orchestrator | routing decisions, hosted checkout page, verification worker | payment, provider-integration |
| provider-integration | provider config, provider call log; PayU adapter (checkout, status, refund, refund status, callback verification) | PayU |
| webhook | provider callbacks (verified, de-duplicated, retried) | provider-integration, orchestrator, payment |
| refund | refunds, attempts, outbox, audit | payment, provider-integration |
| ledger | accounts, immutable transactions/entries, audit | consumes Kafka |
| settlement | settlement batches/items, reconciliation, exceptions, merchant shares | payment (attempt lookup), refund |
| notification | merchant webhook jobs and attempts | merchant (subscribers), consumes Kafka |

## Payment flow (happy path)
```mermaid
sequenceDiagram
  participant M as Merchant server
  participant G as Gateway
  participant P as payment-service
  participant O as orchestrator
  participant PI as provider-integration
  participant C as Customer browser
  participant PU as PayU
  participant W as webhook-service
  M->>G: POST /orders, POST /payments (API key, Idempotency-Key)
  G->>P: create payment (CREATED)
  M->>G: POST /payments/{id}/checkout
  G->>O: start attempt
  O->>P: begin attempt (CREATED -> PENDING)
  O->>PI: checkout instructions (signed form fields, no network call)
  O-->>M: checkoutUrl (HMAC-signed, 30 min)
  C->>O: GET /checkout/{token}  (self-posting form page)  -> payment PROCESSING
  C->>PU: form POST (hosted page: card/UPI/netbanking)
  PU-->>C: redirect POST to /api/v1/webhooks/payu (surl/furl) ; PU server webhook optional
  C->>W: callback
  W->>PI: verify hash (callback alone proves nothing)
  W->>O: verify attempt
  O->>PI: provider status API (verify_payment) + amount check
  O->>P: transition SUCCESS / FAILED (state machine)
  P-->>W: ok ; W-->>C: 303 to merchant returnUrl / neutral result page
  P->>P: outbox event payment.success
```
If the browser never returns, the verification worker asks the provider after 60 s and settles the payment (or VERIFY_REQUIRED when the provider answer is unclear; never guessed).

## Money after SUCCESS
```
payment.success --Kafka--> ledger: DR provider receivable / CR merchant payable (+fee)      --> notification: signed webhook to merchant
refund request --> refund-service (row first, then PayU, then verify) --> payment REFUND_PENDING/PARTIALLY_REFUNDED/REFUNDED
refund.success --Kafka--> ledger: DR merchant payable / CR provider receivable
provider settlement batch (imported) --> reconciliation vs payments/refunds --> exceptions OR settlement.created --> ledger: bank / provider fees / receivable
```

## Cross-cutting rules
| Concern | Rule | Where |
|---|---|---|
| Trust | gateway authenticates; services accept only `X-Internal-Token`; client identity headers are stripped | GatewayTrustFilter, JwtAuthGlobalFilter |
| Idempotency | money-changing POSTs: merchant + `Idempotency-Key`; replays return the first response | IdempotencyService (same transaction as the change) |
| Consistency | state change + outbox row in one transaction; Kafka delivery at-least-once; consumers idempotent | OutboxPublisher, ledger/notification |
| Unknown outcomes | never FAILED or SUCCESS by guess: VERIFY_REQUIRED / PROCESSING until the provider or an operator decides | orchestrator, refund |
| Evidence | ledger, settlement evidence, audit trail are append-only (DB triggers) | migrations |
| Observability | correlation id + trace id in every log line, Prometheus metrics, alerts | libs/apg-observability, docs/OBSERVABILITY.md |

## Decisions
See `docs/DECISIONS.md` (D1-D39) for the why behind each choice; `docs/PAYMENT_LIFECYCLE.md` for the state machine; `docs/EVENTS.md` for topics.
