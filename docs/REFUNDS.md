# REFUNDS

See `services/refund-service/README.md` for the service. Summary of the rules:

1. A refund is allowed only for the caller's own payment in SUCCESS / PARTIALLY_REFUNDED / REFUND_PENDING with a known provider payment id.
2. refundable = payment amount - sum(refunds not FAILED). Requests above it => 422 REFUND_EXCEEDS_PAYMENT. Serialised per payment (advisory lock).
3. The refund row exists before PayU is called (`cancel_refund_transaction`, token = refund_reference, amount in major units).
4. PayU "accepted" only means queued: the refund is PROCESSING until `check_action_status` says success/failed.
5. Unknown answer => VERIFY_REQUIRED, amount stays reserved, operator resolves after checking PayU's dashboard.
6. Payment status follows the refunds: any open refund => REFUND_PENDING; all refunded => REFUNDED; some => PARTIALLY_REFUNDED; none => SUCCESS.
7. Ledger entries (M10) and merchant webhooks (M12) consume the outbox events `refund.success` / `refund.failed`.

Open point: the exact JSON shape of PayU's `check_action_status` answer could not be verified against a live refund yet (no captured sandbox payment). The parser searches the whole `transaction_details` tree for our request id / token and treats anything not explicitly success/failed as still processing, so a different shape can only delay a refund, never fake it. Verify with the first real sandbox refund.
