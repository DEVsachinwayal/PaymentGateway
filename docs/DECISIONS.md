# Decisions
## D1 – Spring Boot 3.5.16, Java release 21
Alternatives: Boot 4.x (new modularisation, less mature ecosystem), Java 25 target. Reason: spec says "Java 21/25+ compatible"; 3.5.x is stable and runs on JDK 25. Consequence: revisit Boot 4 upgrade later.
## D2 – Multi-module Maven monorepo with `libs/apg-common`
Shared lib holds only conventions (response envelope, error codes, correlation ID, Money helpers) – no business logic, so service boundaries stay intact.
## D3 – Spec stored in repo as text
`docs/MASTER_SPEC_EXTRACT.txt` lets any new chat resume without the .docx.
## D4 – Kafka image: apache/kafka-native:4.1.2
Alternative: apache/kafka:3.9.0 (could not be pulled due to network issues). Native image is cached locally, KRaft mode, ~fast start. Consequence: no CLI scripts in container; healthcheck is a TCP check.
## D5 – Docker Postgres on host port 5433
Host already runs a native Postgres on 5432 (services connected to it by mistake). Compose maps 5433; service default URLs use 5433.
## D6 – JWT signed with shared HMAC secret (JWT_SECRET)
Alternative: RS256 + JWKS. HMAC is simpler for gateway local validation; revisit in M13 (hardening) to move to asymmetric keys.
## D7 – Integration tests named *Test using Testcontainers
Run by plain `mvn test`; Testcontainers Ryuk disabled via env on this machine (image not pullable).
## D8 – Gateway does not depend on apg-common
apg-common is servlet-based, gateway is WebFlux. Error envelope is duplicated minimally in `ErrorResponses`.
## D9 – Rate limiting fails open if Redis is down
Spring Cloud Gateway RedisRateLimiter default. Redis is not source of truth (spec 18); revisit in M13.
## D10 – Internal trust via X-Internal-Token + gateway-injected identity headers
Services behind the gateway reject requests without the shared INTERNAL_SERVICE_TOKEN (GatewayTrustFilter in apg-common) and read identity from X-User-*/X-Merchant-Id/X-Auth-Type. Alternative: every service re-validates JWT (more defense in depth). Consequence: network isolation + token are the trust boundary; revisit with mTLS in M13/M15.
## D11 – API key secret hashed with HMAC-SHA256 + pepper, not bcrypt
Secrets are 256-bit random, so a fast keyed hash is safe and keeps per-request verification cheap. Pepper in env.
## D12 – Gateway verifies API keys by calling merchant-service on every request (no cache yet)
Correct revocation semantics first; add short Redis cache in M12/M14 if latency demands (TODO).
## D13 – Webhook signing secret is encrypted (AES-GCM), not hashed
It must be reusable to sign outbound webhooks (M12).
## D14 – IdempotencyService in apg-common, table per service
Marker row and business change share one DB transaction (failed attempt leaves no marker; concurrent duplicate blocks on PK then replays). Each service creates `idempotency_keys` in its own migration (DDL in `IdempotencyService.TABLE_DDL`). Redis is not used as the source of truth (spec 18).
## D15 – Reject float amounts (accept-float-as-int=false)
Jackson would turn 49.99 into 49 silently. Every money-handling service must set `spring.jackson.deserialization.accept-float-as-int=false`.
## D16 – Order currencies allow-listed (INR only for now)
PayU sandbox is INR; widen via config when a provider supports more.
## D17 – PROCESSING -> SUCCESS allowed in addition to AUTHORIZED -> CAPTURED -> SUCCESS
PayU returns a final captured result in one step; forcing fake AUTHORIZED/CAPTURED hops would record states the provider never reported. Providers with auth/capture use the long path.
## D18 – Transactional outbox for events
Events are inserted in the same DB transaction as the state change (no lost/phantom events); a publisher to Kafka is built in M12 (poll-the-outbox is the documented, deliberate exception to "no DB polling").
## D19 – Unknown JSON properties rejected in money services
`fail-on-unknown-properties=true` in payment-service so clients cannot send status/amount/currency.
## D20 – Payment pulls order data via order-service internal API (no shared DB)
Synchronous read at creation; order status is updated from payment events in M12 (until then orders stay CREATED; the one-open-payment unique index prevents double payment).
## D21 – Provider calls return UNKNOWN on trouble; only explicit provider answers can fail a payment
Timeouts, 429, malformed bodies => ProviderException => HTTP 502 => orchestrator marks VERIFY_REQUIRED. FAILED needs `failure`/`failed`/`usercancelled` from PayU.
## D22 – PayU "success" requires unmappedstatus=captured
`success` with `auth` (authorised only) is PENDING until captured.
## D23 – Test PayU is a fake server that validates hashes + one opt-in live test
Unit tests never contact PayU (it rate-limits bad callers); the live test is opt-in.
## D24 – No automatic provider failover yet
Failover is only safe before the customer is sent to a provider, or after the provider proved it never saw the txn. With one provider the routing record and rules are in place; failover is a TODO for when a second provider exists.
## D25 – Hosted redirect page with signed stateless token
The browser never sees API keys; the page only auto-submits provider-signed fields. Token = HMAC(attemptReference, expiry); expired => 410.
## D26 – Unknown amounts or provider doubts never settle as SUCCESS
Amount mismatch between provider and our payment => VERIFY_REQUIRED for ops, not SUCCESS.
## D27 – Refund row first, provider second, payment state derived
The refund is persisted (CREATED) before PayU is called; payment status is recomputed from all refunds (REFUND_PENDING / PARTIALLY_REFUNDED / REFUNDED / SUCCESS) by an idempotent sync, so a crash or a payment-service outage can always be repaired.
## D28 – In-flight and unknown refunds reserve the amount
Over-refund is prevented by a per-payment advisory lock and sum(refunds <> FAILED). VERIFY_REQUIRED keeps the money reserved; only an operator resolution (or provider answer) releases it.
## D29 – A queued refund is not a successful refund
PayU's "request queued" => PROCESSING. SUCCESS needs an explicit success from `check_action_status`; unknown response shapes stay PROCESSING (backoff 60s..30m).
