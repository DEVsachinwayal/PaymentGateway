#!/usr/bin/env bash
# Logical backup of every APG database (custom format, one file per database) from the compose Postgres container.
#   ./scripts/backup-postgres.sh [target-dir]      restore one:  pg_restore -d <db> --clean --if-exists <file>
# Production: prefer the managed database's automated backups + point-in-time recovery; use this for ad-hoc dumps and to test restores.
# Dumps contain customer and financial data: store them encrypted (set BACKUP_GPG_RECIPIENT) and never on a shared disk.
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; [ -f .env ] && . ./.env; set +a
OUT="${1:-backups/$(date +%Y%m%d-%H%M%S)}"; mkdir -p "$OUT"
CONTAINER="${POSTGRES_CONTAINER:-$(docker compose ps -q postgres)}"
for db in apg_auth apg_merchant apg_order apg_payment apg_provider apg_orchestrator apg_webhook apg_refund apg_ledger apg_settlement apg_notification; do
  docker exec "$CONTAINER" pg_dump -U "$POSTGRES_USER" -Fc "$db" > "$OUT/$db.dump"
  if [ -n "${BACKUP_GPG_RECIPIENT:-}" ]; then gpg --yes --encrypt -r "$BACKUP_GPG_RECIPIENT" "$OUT/$db.dump" && rm "$OUT/$db.dump"; fi
  echo "dumped $db"
done
echo "backup written to $OUT"
