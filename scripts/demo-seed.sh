#!/usr/bin/env bash
# Fills a fresh stack with DEMO data so the dashboard is not empty on stage. LOCAL DEMO ONLY.
#   bash scripts/demo-seed.sh
# Creates (or re-uses) the demo account, a TEST API key, a webhook endpoint for the sample shop, several payments in different
# states and two payment links. Payment results that you will not pay for live are SIMULATED with the internal token (the same
# dev tool as scripts/smoke-events.sh); they are labelled "SIMULATED (demo seed)" in the payment history. Real sandbox payments
# are what you do live during the demo. No refunds are seeded (they would call PayU with fake ids).
set -u
cd "$(dirname "$0")/.."
set -a; . ./.env; set +a
GW="${GATEWAY_URL:-http://localhost:8080}"
EMAIL="${DEMO_EMAIL:-demo@apg.test}"
PASSWORD="${DEMO_PASSWORD:-Demo-Passw0rd-2026}"
jget() { grep -o "\"$1\":\"[^\"]*\"" | head -1 | cut -d'"' -f4; }
H="X-Internal-Token: $INTERNAL_SERVICE_TOKEN"; PS=http://localhost:8084/internal/v1

curl -s -o /dev/null -X POST "$GW/api/v1/auth/register" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\",\"fullName\":\"Demo Merchant\"}"
TOK=$(curl -s -X POST "$GW/api/v1/auth/login" -H 'Content-Type: application/json' -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" | jget accessToken)
[ -n "$TOK" ] || { echo "login failed - is the stack up? (bash scripts/dev-start.sh)"; exit 1; }
AUTH="Authorization: Bearer $TOK"
curl -s -o /dev/null -X POST "$GW/api/v1/merchants" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"name":"Demo Books","legalName":"Demo Books Pvt Ltd","supportEmail":"help@demobooks.example","defaultCurrency":"INR"}'

KEY=$(curl -s -X POST "$GW/api/v1/api-keys" -H "$AUTH" -H 'Content-Type: application/json' -d '{"label":"demo shop","mode":"TEST"}' | jget secret)
[ -n "$KEY" ] || { echo "could not create an API key (account not an admin?)"; exit 1; }
WH=$(curl -s -X POST "$GW/api/v1/webhook-endpoints" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"url":"http://localhost:4000/apg-webhook","events":["payment.success","payment.failed","refund.success","refund.failed"]}')
printf 'APG_API_KEY=%s\nAPG_WEBHOOK_SECRET=%s\n' "$KEY" "$(echo "$WH" | jget signingSecret)" > .run/sample-shop.env

mkpay() {   # amount_minor description -> payment id (payment starts at CREATED)
  local o p
  o=$(curl -s -X POST "$GW/api/v1/orders" -H "Authorization: Bearer $KEY" -H "Idempotency-Key: seed-o-$RANDOM$RANDOM$RANDOM" -H 'Content-Type: application/json' \
      -d "{\"amount\":$1,\"currency\":\"INR\",\"description\":\"$2\",\"customer\":{\"name\":\"Demo Customer\",\"email\":\"customer@example.com\"}}" | jget id)
  p=$(curl -s -X POST "$GW/api/v1/payments" -H "Authorization: Bearer $KEY" -H "Idempotency-Key: seed-p-$RANDOM$RANDOM$RANDOM" -H 'Content-Type: application/json' \
      -d "{\"orderId\":\"$o\"}" | jget id)
  echo "$p"
}
settle() {  # payment id, SUCCESS|FAILED  (SIMULATED provider result, dev tool)
  local ref
  ref=$(curl -s -X POST "$PS/payments/$1/attempts" -H "$H" -H 'Content-Type: application/json' -d '{"provider":"payu"}' | jget providerReference)
  curl -s -o /dev/null -X PATCH "$PS/attempts/$ref" -H "$H" -H 'Content-Type: application/json' -d "{\"status\":\"$2\",\"providerPaymentId\":\"DEMO$RANDOM$RANDOM\"}"
  curl -s -o /dev/null -X POST "$PS/payments/$1/transition" -H "$H" -H 'Content-Type: application/json' -d '{"to":"PROCESSING","reason":"customer redirected (demo seed)","source":"ORCHESTRATOR"}'
  curl -s -o /dev/null -X POST "$PS/payments/$1/transition" -H "$H" -H 'Content-Type: application/json' \
    -d "{\"to\":\"$2\",\"reason\":\"SIMULATED (demo seed)\",\"source\":\"OPS\"}"
}

echo "creating demo payments..."
for spec in "129900|Laptop sleeve|SUCCESS" "49900|Programming book|SUCCESS" "250000|Headphones|SUCCESS" "9900|Phone cover|SUCCESS" "74900|Desk lamp|FAILED" "19900|Notebook pack|PENDING"; do
  IFS='|' read -r amt desc st <<< "$spec"
  pid=$(mkpay "$amt" "$desc")
  [ "$st" = "PENDING" ] || settle "$pid" "$st"
done

echo "creating payment links..."
curl -s -o /dev/null -X POST "$GW/api/v1/payment-links" -H "$AUTH" -H "Idempotency-Key: seed-l-1-$RANDOM$RANDOM" -H 'Content-Type: application/json' \
  -d '{"amount":50000,"currency":"INR","description":"Workshop fee","oneTime":false}'
LINK=$(curl -s -X POST "$GW/api/v1/payment-links" -H "$AUTH" -H "Idempotency-Key: seed-l-2-$RANDOM$RANDOM" -H 'Content-Type: application/json' \
  -d '{"amount":120000,"currency":"INR","description":"Invoice #1042","oneTime":true}' | jget url)

cat <<EOF

DEMO READY
  Dashboard : http://localhost:5173      login  $EMAIL  /  $PASSWORD
  Sample shop: node examples/sample-shop/server.js   (needs .run/sample-shop.env: set -a; . .run/sample-shop.env; set +a)  -> http://localhost:4000
  Pay by link: ${LINK:-<create one in the dashboard>}
  PayU test card 5123456789012346, CVV 123, any future expiry, OTP 123456   |   UPI anything@payu   |   Net banking payu / payu, OTP 123456
EOF
