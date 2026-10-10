#!/usr/bin/env bash
# The whole demo in Docker: only Docker Desktop (and the .env file) are needed. No Java, Maven or Node on this computer.
#   bash scripts/docker-demo-up.sh          stop with: bash scripts/docker-demo-down.sh
# First run builds 13 service images + the dashboard (20-40 minutes); later runs start in a minute or two.
set -u
cd "$(dirname "$0")/.."
FILES="-f docker-compose.yml -f docker-compose.apps.yml -f docker-compose.demo.yml"
[ -f .env ] || { echo ".env is missing in $(pwd) - copy it from the other computer (README section 13)"; exit 1; }
docker info >/dev/null 2>&1 || { echo "Docker is not running - start Docker Desktop and wait until it is ready"; exit 1; }
export DOCKER_BUILDKIT=1 COMPOSE_DOCKER_CLI_BUILD=1
echo "[1/3] building images and starting the containers (first time: 20-40 minutes)"
docker compose $FILES up -d --build || { echo "docker compose failed: see the messages above"; exit 1; }
echo "[2/3] waiting for the dashboard"
for i in $(seq 1 120); do curl -s -m 2 http://localhost:8000/healthz | grep -q ok && break; sleep 3; done
echo "[3/3] waiting for the demo data (seed)"
for i in $(seq 1 120); do
  st=$(docker compose $FILES ps -a --format '{{.State}}' demo-seed 2>/dev/null | head -1)
  [ "$st" = "exited" ] && break; sleep 3
done
docker compose $FILES logs --no-log-prefix demo-seed 2>/dev/null | tail -8
echo
echo "Everything is up.  Dashboard: http://localhost:8000   Sample shop: http://localhost:4000"
echo "Login: demo@apg.test / Demo-Passw0rd-2026      (stop: bash scripts/docker-demo-down.sh)"
