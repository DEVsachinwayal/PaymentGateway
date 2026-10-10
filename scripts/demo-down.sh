#!/usr/bin/env bash
# Stops the demo: services, dashboard dev server, sample shop. Docker infrastructure stays up (docker compose down to stop it too).
cd "$(dirname "$0")/.."
bash scripts/dev-stop.sh >/dev/null 2>&1
taskkill //F //IM node.exe >/dev/null 2>&1 || pkill -f "vite|sample-shop" 2>/dev/null
echo "demo stopped (docker compose down stops Postgres/Redis/Kafka)"
