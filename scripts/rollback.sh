#!/usr/bin/env bash
# Manual rollback to the previously deployed tag (or an explicit one):  ./scripts/rollback.sh [vX.Y.Z]
# Safe only when the newer release's database migrations are backward compatible with the older code (docs/DEPLOYMENT.md).
set -euo pipefail
cd "$(dirname "$0")/.."
STATE_DIR="${APG_STATE_DIR:-.deploy}"
TARGET="${1:-$(cat "$STATE_DIR/previous-tag" 2>/dev/null || true)}"
[[ -n "$TARGET" ]] || { echo "no previous tag recorded; pass one explicitly"; exit 2; }
echo "rolling back to $TARGET"
exec ./scripts/deploy.sh "$TARGET"
