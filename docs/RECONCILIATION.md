# RECONCILIATION

Matching (spec 16): provider item -> APG record by provider reference (payment: the attempt's txnid; refund: our refund token), then compare provider ids, status and amount/currency.

| Finding (exception type) | Meaning |
|---|---|
| UNKNOWN_PAYMENT / UNKNOWN_REFUND | provider settled something APG has no record of |
| STATUS_MISMATCH | provider settled money for a payment APG does not consider captured, or a refund APG does not show as SUCCESS |
| AMOUNT_MISMATCH | amount or currency differs |
| REFERENCE_MISMATCH | provider/payment ids disagree |
| DUPLICATE_ITEM | the same provider reference was settled twice (same batch or another batch) |
| ADJUSTMENT_REVIEW | provider adjustment, needs a human |
| TOTAL_MISMATCH | provider's declared totals differ from the sum of its own items |

Rules: no silent mismatch (every difference is an exception with reason + evidence JSON); rerunnable (`POST /internal/v1/settlements/{id}/reconcile`, unique per batch+item+type, never duplicates); a finding that disappears (APG caught up) is auto-RESOLVED with a note and kept as history; an operator decision (RESOLVED / WRITTEN_OFF with notes, owner for INVESTIGATING) is never reopened by a rerun; lookups that fail leave the item unevaluated (not passed, not flagged).
Exception lifecycle: OPEN -> INVESTIGATING -> RESOLVED | WRITTEN_OFF. Closed exceptions keep their evidence.
Not built yet: "missing in settlement" (APG captured payments older than N days that appear in no batch) - needs a payment listing by provider/date from payment-service; also provider-side fee verification against a rate card.
