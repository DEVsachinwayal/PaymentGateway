#!/usr/bin/env bash
# One command for the project demo: infrastructure, all services, demo data, dashboard and the sample shop.
#   bash scripts/demo-up.sh          (stop everything with: bash scripts/demo-down.sh)
# Takes 3-5 minutes the first time. Needs Docker Desktop running and the jars built (dev-start builds them when missing).
set -u
cd "$(dirname "$0")/.."
mkdir -p .run
echo "[1/5] infrastructure (Postgres, Redis, Kafka)"
docker compose up -d || { echo "Docker is not running?"; exit 1; }
echo "[2/5] services"
bash scripts/dev-start.sh || { echo "some services are not healthy: see .run/*.log"; exit 1; }
echo "[3/5] demo data"
bash scripts/demo-seed.sh
echo "[4/5] dashboard (http://localhost:5173)"
if ! curl -s -m 2 -o /dev/null localhost:5173/; then
  (cd frontend && nohup npm run dev > ../.run/frontend.log 2>&1 &)
  for i in $(seq 1 30); do curl -s -m 2 -o /dev/null localhost:5173/ && break; sleep 1; done
fi
echo "[5/5] sample shop (http://localhost:4000)"
if ! curl -s -m 2 -o /dev/null localhost:4000/; then
  (set -a; . .run/sample-shop.env; set +a; cd examples/sample-shop && nohup node server.js > ../../.run/sample-shop.log 2>&1 &)
  sleep 2
fi
echo
echo "Everything is up.  Dashboard: http://localhost:5173   Shop: http://localhost:4000   (login printed above)"
