# Payment lifecycle
Single source: `PaymentStatus` in payment-service (tested against the spec matrix in `PaymentStatusTest`).

| From | Allowed next |
|---|---|
| CREATED | PENDING, FAILED |
| PENDING | PROCESSING, FAILED, VERIFY_REQUIRED |
| PROCESSING | AUTHORIZED, SUCCESS*, FAILED, VERIFY_REQUIRED |
| AUTHORIZED | CAPTURED, FAILED, VERIFY_REQUIRED |
| CAPTURED | SUCCESS |
| VERIFY_REQUIRED | SUCCESS, FAILED, VERIFY_REQUIRED (still unknown) |
| SUCCESS | REFUND_PENDING |
| REFUND_PENDING | REFUNDED, PARTIALLY_REFUNDED, SUCCESS (refund failed) |
| PARTIALLY_REFUNDED | REFUND_PENDING |
| FAILED, REFUNDED | (terminal) |
*immediate-capture providers (PayU), see DECISIONS D17.

Rules: timeouts go to VERIFY_REQUIRED (never straight to FAILED, never blind retry); same-state requests are no-ops (duplicate webhooks); every change writes `payment_state_history` and an outbox event; concurrent writers get 409 CONCURRENT_MODIFICATION.
Events: payment.created, payment.processing, payment.success, payment.failed, payment.unknown (envelope: eventId,eventType,version,occurredAt,correlationId,aggregateId,merchantId,payload).
