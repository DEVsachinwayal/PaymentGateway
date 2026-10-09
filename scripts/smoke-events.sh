#!/usr/bin/env bash
# DEV-ONLY smoke test of the event pipeline (payment.success -> Kafka -> ledger + merchant webhook).
# The provider result is SIMULATED with the internal token (no real PayU call, no real money): never use outside local dev.
# Needs: docker infra + scripts/dev-start.sh running, python3. Prints PASS/FAIL per check, exits 1 on any FAIL.
set -u
cd "$(dirname "$0")/.."
set -a; . ./.env; set +a
GW=http://localhost:8080
fail=0
check() { if [ "$2" = "$3" ]; then echo "PASS  $1"; else echo "FAIL  $1 (expected '$3', got '$2')"; fail=1; fi; }
jget() { grep -o "\"$1\":\"[^\"]*\"" | head -1 | cut -d'"' -f4; }
jnum() { grep -o "\"$1\":-\?[0-9]*" | head -1 | cut -d: -f2; }

E="smoke$RANDOM@example.com"; P="Str0ng-Passw0rd-xyz"
curl -s -X POST $GW/api/v1/auth/register -H 'Content-Type: application/json' -d "{\"email\":\"$E\",\"password\":\"$P\",\"fullName\":\"Smoke Test\"}" >/dev/null
TOK=$(curl -s -X POST $GW/api/v1/auth/login -H 'Content-Type: application/json' -d "{\"email\":\"$E\",\"password\":\"$P\"}" | jget accessToken)
curl -s -X POST $GW/api/v1/merchants -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' -d '{"name":"Smoke Shop","supportEmail":"s@example.com","defaultCurrency":"INR"}' >/dev/null
KEY=$(curl -s -X POST $GW/api/v1/api-keys -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' -d '{"label":"smoke","mode":"TEST"}' | jget secret)
WH=$(curl -s -X POST $GW/api/v1/webhook-endpoints -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' -d '{"url":"http://localhost:9099/hook","events":["payment.success"]}')
SECRET=$(echo "$WH" | jget signingSecret)
check "api key and webhook endpoint created" "$([ -n "$KEY" ] && [ -n "$SECRET" ] && echo yes)" yes

OUT=.run/webhook-received.log; : > $OUT
python scripts/webhook_receiver.py "$SECRET" 9099 > $OUT 2>&1 &
RCV=$!
trap 'kill $RCV 2>/dev/null' EXIT
sleep 1

ORDER=$(curl -s -X POST $GW/api/v1/orders -H "Authorization: Bearer $KEY" -H "Idempotency-Key: o-$RANDOM$RANDOM" -H 'Content-Type: application/json' -d '{"amount":5000,"currency":"INR","description":"smoke"}' | jget id)
PAY=$(curl -s -X POST $GW/api/v1/payments -H "Authorization: Bearer $KEY" -H "Idempotency-Key: p-$RANDOM$RANDOM" -H 'Content-Type: application/json' -d "{\"orderId\":\"$ORDER\"}" | jget id)
check "order and payment created" "$([ -n "$ORDER" ] && [ -n "$PAY" ] && echo yes)" yes

PS=http://localhost:8084/internal/v1
H="X-Internal-Token: $INTERNAL_SERVICE_TOKEN"
REF=$(curl -s -X POST $PS/payments/$PAY/attempts -H "$H" -H 'Content-Type: application/json' -d '{"provider":"payu"}' | jget providerReference)
curl -s -X PATCH $PS/attempts/$REF -H "$H" -H 'Content-Type: application/json' -d '{"status":"SUCCESS","providerPaymentId":"SMOKE'$RANDOM'"}' >/dev/null
curl -s -X POST $PS/payments/$PAY/transition -H "$H" -H 'Content-Type: application/json' -d '{"to":"PROCESSING","reason":"smoke","source":"ORCHESTRATOR"}' >/dev/null
curl -s -X POST $PS/payments/$PAY/transition -H "$H" -H 'Content-Type: application/json' -d '{"to":"SUCCESS","reason":"SIMULATED provider success (dev smoke test)","source":"OPS"}' >/dev/null
check "payment is SUCCESS" "$(curl -s $GW/api/v1/payments/$PAY -H "Authorization: Bearer $KEY" | jget status)" SUCCESS

for i in $(seq 1 30); do grep -q "payment.success" $OUT && break; sleep 1; done
check "merchant webhook received with a VALID signature" "$(grep -c 'signature=VALID' $OUT)" 1
check "webhook body has no internal ids" "$(grep -c "$E\|merchantId\|correlationId" $OUT)" 0

for i in $(seq 1 30); do P=$(curl -s $GW/api/v1/ledger/balance -H "Authorization: Bearer $TOK" | jnum payable); [ "${P:-0}" != "0" ] && break; sleep 1; done
check "ledger payable = 5000 (captured, no fee configured)" "${P:-0}" 5000
check "ledger captured = 5000" "$(curl -s $GW/api/v1/ledger/balance -H "Authorization: Bearer $TOK" | jnum captured)" 5000

D=$(curl -s "$GW/api/v1/webhook-deliveries?status=DELIVERED" -H "Authorization: Bearer $TOK")
check "delivery history shows 1 DELIVERED" "$(echo "$D" | jnum totalElements)" 1
check "trial balance balanced" "$(curl -s http://localhost:8089/internal/v1/ledger/trial-balance -H "$H" | grep -c '"balanced":true')" 1

[ $fail = 0 ] && echo "ALL CHECKS PASSED" || { echo "SOME CHECKS FAILED"; exit 1; }
