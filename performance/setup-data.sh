#!/usr/bin/env bash
# Creates one merchant, a TEST API key and a dashboard user, and writes performance/data/credentials.csv for JMeter (git-ignored).
# The CSV repeats the same credentials on several lines so every JMeter thread has a row. LOCAL ONLY.
set -eu
cd "$(dirname "$0")/.."
GW="${GATEWAY_URL:-http://localhost:8080}"
jget() { grep -o "\"$1\":\"[^\"]*\"" | head -1 | cut -d'"' -f4; }
E="perf$RANDOM$RANDOM@example.com"; P="Str0ng-Passw0rd-xyz"
curl -sf -X POST "$GW/api/v1/auth/register" -H 'Content-Type: application/json' -d "{\"email\":\"$E\",\"password\":\"$P\",\"fullName\":\"Perf Tester\"}" >/dev/null
TOK=$(curl -sf -X POST "$GW/api/v1/auth/login" -H 'Content-Type: application/json' -d "{\"email\":\"$E\",\"password\":\"$P\"}" | jget accessToken)
curl -sf -X POST "$GW/api/v1/merchants" -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' -d '{"name":"Perf Shop","supportEmail":"perf@example.com","defaultCurrency":"INR"}' >/dev/null
KEY=$(curl -sf -X POST "$GW/api/v1/api-keys" -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' -d '{"label":"perf","mode":"TEST"}' | jget secret)
mkdir -p performance/data
{ echo "apiKey,email,password"; for i in $(seq 1 10); do echo "$KEY,$E,$P"; done; } > performance/data/credentials.csv
echo "wrote performance/data/credentials.csv (merchant user $E)"
