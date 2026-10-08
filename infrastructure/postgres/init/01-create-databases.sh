#!/bin/sh
# One database per service (spec 5.2: each service owns its persistence).
set -e
for db in apg_auth apg_merchant apg_order apg_payment apg_provider apg_orchestrator apg_webhook apg_refund apg_ledger apg_settlement apg_notification; do
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres -c "CREATE DATABASE $db"
done
