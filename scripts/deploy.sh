#!/usr/bin/env bash
# Deploys a release tag on the production host (run on the VM, normally by the release workflow):  ./scripts/deploy.sh v1.4.0
# Steps: remember the running tag -> pull the new images -> start them -> wait until every container is healthy ->
#        on any failure roll back to the remembered tag automatically. Database migrations are forward-only (Flyway), so every
#        release must stay compatible with the previous version's code (expand/contract) - see docs/DEPLOYMENT.md.
set -euo pipefail
cd "$(dirname "$0")/.."
TAG="${1:?usage: deploy.sh <release-tag>}"
[[ "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+([-.][A-Za-z0-9.]+)?$ ]] || { echo "refusing tag '$TAG' (expected vX.Y.Z)"; exit 2; }
STATE_DIR="${APG_STATE_DIR:-.deploy}"; mkdir -p "$STATE_DIR"
COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.apps.yml -f docker-compose.prod.yml)
: "${OCIR_PREFIX:?OCIR_PREFIX must be set (e.g. in /opt/apg/.env)}"

PREVIOUS="$(cat "$STATE_DIR/current-tag" 2>/dev/null || true)"
echo "deploying $TAG (previous: ${PREVIOUS:-none})"

wait_healthy() {
  local deadline=$((SECONDS + ${HEALTH_TIMEOUT:-300}))
  while (( SECONDS < deadline )); do
    local bad
    bad="$("${COMPOSE[@]}" ps --format '{{.Name}} {{.Health}}' | grep -v ' healthy$' || true)"
    [[ -z "$bad" ]] && return 0
    sleep 5
  done
  echo "not healthy after ${HEALTH_TIMEOUT:-300}s:"; echo "$bad"
  return 1
}

rollout() {
  export APG_TAG="$1"
  "${COMPOSE[@]}" pull
  "${COMPOSE[@]}" up -d --no-build --remove-orphans
  wait_healthy
}

if rollout "$TAG"; then
  [[ -n "$PREVIOUS" && "$PREVIOUS" != "$TAG" ]] && echo "$PREVIOUS" > "$STATE_DIR/previous-tag"
  echo "$TAG" > "$STATE_DIR/current-tag"
  echo "deployed $TAG"
else
  echo "DEPLOY FAILED for $TAG" >&2
  if [[ -n "$PREVIOUS" ]]; then
    echo "rolling back to $PREVIOUS" >&2
    rollout "$PREVIOUS" && echo "rolled back to $PREVIOUS" >&2 || echo "ROLLBACK ALSO FAILED - manual intervention needed" >&2
  fi
  exit 1
fi
