#!/usr/bin/env bash
# Starts the stack for load testing: relaxed gateway rate limit and the orchestrator verification worker OFF
# (so synthetic PROCESSING payments can never cause real PayU verify_payment calls). LOCAL ONLY.
set -eu
cd "$(dirname "$0")/.."
export GATEWAY_RATE_LIMIT_RPS="${GATEWAY_RATE_LIMIT_RPS:-5000}"
export GATEWAY_RATE_LIMIT_BURST="${GATEWAY_RATE_LIMIT_BURST:-10000}"
export ORCHESTRATOR_VERIFICATION_ENABLED=false
echo "perf mode: rate limit ${GATEWAY_RATE_LIMIT_RPS}/s burst ${GATEWAY_RATE_LIMIT_BURST}, PayU verification worker disabled"
exec bash scripts/dev-start.sh "$@"
