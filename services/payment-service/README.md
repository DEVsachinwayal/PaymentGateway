# payment-service (port 8084, DB `apg_payment`)
Owns payment_intents, payments, payment_attempts, payment_state_history, outbox_events.
- `POST /api/v1/payments` {orderId, [paymentMethod, returnUrl]} + `Idempotency-Key`. Amount/currency are read from order-service; the body cannot carry status/amount/currency (unknown fields => 400).
- `GET /api/v1/payments/{id}`, `/{id}/status` (state + history), `GET /api/v1/payments`.
- One open payment per order (DB unique index); a FAILED payment frees the order.
- `PaymentStatus` is the single transition table; `PaymentStateService.transition` is the only code that changes status (validated, recorded in history, event in outbox, idempotent for same-state, optimistic locking => 409 CONCURRENT_MODIFICATION).
- `POST /internal/v1/payments/{id}/transition` for orchestrator/webhook/verification (not routed by gateway).
- Outbox events (payment.created/processing/success/failed/unknown) are written transactionally; Kafka publishing arrives in M12.
Env: POSTGRES_USER, POSTGRES_PASSWORD, INTERNAL_SERVICE_TOKEN, ORDER_SERVICE_URI.
