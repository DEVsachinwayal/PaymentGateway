#!/usr/bin/env bash
# Creates a TEST order + payment + hosted-checkout link for a dashboard account, so you can pay in the PayU sandbox.
#   bash scripts/new-test-payment.sh <email> <password> [amount-in-rupees=100]
# Open the printed link in a browser and pay with a PayU TEST card/UPI (docs/PAYU_INTEGRATION.md). Sandbox only: no real money.
# Creates the business and a TEST API key if the account has none. Prints nothing secret except the checkout link.
set -eu
EMAIL="${1:?usage: new-test-payment.sh <email> <password> [amount-in-rupees]}"
PASSWORD="${2:?usage: new-test-payment.sh <email> <password> [amount-in-rupees]}"
RUPEES="${3:-100}"
GW="${GATEWAY_URL:-http://localhost:8080}"
case "$RUPEES" in ''|*[!0-9]*) echo "amount must be whole rupees, e.g. 100"; exit 2;; esac
MINOR=$((RUPEES * 100))
jget() { grep -o "\"$1\":\"[^\"]*\"" | head -1 | cut -d'"' -f4; }

TOK=$(curl -s -X POST "$GW/api/v1/auth/login" -H 'Content-Type: application/json' -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" | jget accessToken)
[ -n "$TOK" ] || { echo "login failed (check email/password, and that the stack is running)"; exit 1; }
if [ "$(curl -s -o /dev/null -w '%{http_code}' "$GW/api/v1/merchants/me" -H "Authorization: Bearer $TOK")" = "404" ]; then
  curl -s -X POST "$GW/api/v1/merchants" -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' \
    -d '{"name":"My Test Shop","supportEmail":"'"$EMAIL"'","defaultCurrency":"INR"}' >/dev/null
fi
KEY=$(curl -s -X POST "$GW/api/v1/api-keys" -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' -d '{"label":"test payment script","mode":"TEST"}' | jget secret)
[ -n "$KEY" ] || { echo "could not create an API key (is the account an admin?)"; exit 1; }
AUTH="Authorization: Bearer $KEY"
ORDER=$(curl -s -X POST "$GW/api/v1/orders" -H "$AUTH" -H "Idempotency-Key: o-$RANDOM$RANDOM$RANDOM" -H 'Content-Type: application/json' \
  -d '{"amount":'"$MINOR"',"currency":"INR","description":"Test payment","customer":{"name":"Test Customer","email":"customer@example.com","phone":"9999999999"}}' | jget id)
PAY=$(curl -s -X POST "$GW/api/v1/payments" -H "$AUTH" -H "Idempotency-Key: p-$RANDOM$RANDOM$RANDOM" -H 'Content-Type: application/json' -d '{"orderId":"'"$ORDER"'"}' | jget id)
URL=$(curl -s -X POST "$GW/api/v1/payments/$PAY/checkout" -H "$AUTH" -H "Idempotency-Key: c-$RANDOM$RANDOM$RANDOM" -H 'Content-Type: application/json' -d '{}' | jget checkoutUrl)
[ -n "$URL" ] || { echo "could not create the checkout link"; exit 1; }
echo "Payment id : $PAY"
echo "Amount     : INR $RUPEES.00 (sandbox)"
echo "Pay here   : $URL"
echo "(the link is valid for 30 minutes; after paying, open Payments in the dashboard)"
