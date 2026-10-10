# DATABASE

One PostgreSQL 16 database per service (created by `infrastructure/postgres/init/01-create-databases.sh`); a service never reads another service's tables (it asks through the internal API or consumes events). Migrations are Flyway files in `services/<svc>/src/main/resources/db/migration`, applied automatically at start-up, **forward-only** (see docs/DEPLOYMENT.md for the compatibility rule). Money columns are `BIGINT` minor units with a 3-letter currency. All timestamps are `TIMESTAMPTZ` (UTC).

| Database | Service | Tables |
|---|---|---|
| apg_auth | auth-service | users, roles, user_roles, refresh_sessions (hashed tokens, rotation family), login_audit |
| apg_merchant | merchant-service | merchants, merchant_users, merchant_settings, api_keys (HMAC hash only), webhook_endpoints (AES-GCM encrypted secret), audit_events |
| apg_order | order-service | customers, orders, order_items, idempotency_keys |
| apg_payment | payment-service | payment_intents, payments, payment_attempts, payment_state_history, outbox_events, idempotency_keys |
| apg_provider | provider-integration | provider_configs, provider_attempts (sanitised call log), provider_audit |
| apg_orchestrator | payment-orchestrator | routing_decisions, verification_log, idempotency_keys |
| apg_webhook | webhook-service | webhook_events (sanitised, de-duplicated), webhook_delivery_attempts, outbox_events |
| apg_refund | refund-service | refunds, refund_attempts, outbox_events, idempotency_keys, audit_events |
| apg_ledger | ledger-service | ledger_accounts, ledger_transactions, ledger_entries, audit_events |
| apg_settlement | settlement-service | settlements, settlement_items, reconciliation_runs, reconciliation_exceptions, merchant_settlements, outbox_events, audit_events |
| apg_paymentlink | payment-link-service | payment_links, link_attempts, idempotency_keys, audit_events |
| apg_notification | notification-service | notification_jobs, notification_attempts, audit_events |

## Integrity enforced by the database itself
| Rule | Mechanism |
|---|---|
| Ledger rows never change | triggers raise on UPDATE / DELETE / TRUNCATE of `ledger_transactions`, `ledger_entries` |
| Ledger transactions balance | deferred constraint trigger at COMMIT: >= 2 entries, debits = credits, one currency |
| One posting per payment/refund event | unique `(tx_type, reference_id)` and unique `event_id`; one reversal per transaction |
| Settlement evidence immutable | triggers forbid editing/deleting settlement header/items (workflow columns stay updatable) |
| Reconciliation never duplicates | unique `(settlement_id, item, type)` on exceptions; unique batch `(provider, batch_ref)` |
| Payout needs evidence | CHECK: `status = 'PAID'` requires `payout_reference` |
| Audit trail append-only | triggers on `audit_events` |
| Idempotency | primary key `(merchant_id, idem_key)` inserted in the same transaction as the change |
| Duplicate provider callbacks | unique `(provider, fingerprint)` for authentic events |
| One webhook job per event+endpoint | unique `(event_id, endpoint_id)` |
| Optimistic locking | `version` column on `payments` (JPA) |

## Indexes and queries
Indexed for the access patterns used by the APIs: `(merchant_id, created_at DESC)` on orders, payments, refunds, ledger transactions, audit events, merchant settlements; partial indexes on work queues (`webhook_events` retry, `notification_jobs` due, `refunds` in-flight, `outbox_events WHERE published_at IS NULL`). List endpoints use batch fetches (no N+1; guarded by statement-count tests) and `OFFSET` paging (keyset paging is a candidate for very large tables, see docs/PERFORMANCE.md).

## Operations
- Create/reset local databases: `docker compose down -v && docker compose up -d` (destroys local data).
- Backups: `scripts/backup-postgres.sh` (all 11 databases, optional GPG), restore with `pg_restore`.
- Least-privilege runtime roles and separate migration roles are not set up yet (docs/DEPLOYMENT.md).
