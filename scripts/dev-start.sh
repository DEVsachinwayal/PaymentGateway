#!/usr/bin/env bash
# Starts the whole APG stack locally (Docker infra must be up: `docker compose up -d`).
# Usage: scripts/dev-start.sh [--build]   |   stop everything: scripts/dev-stop.sh
set -e
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME_OVERRIDE:-C:/Program Files/Java/jdk-25.0.4.1}"
export PATH="/d/Tools/apache-maven-3.9.16/bin:$PATH"
[ "$1" = "--build" ] || [ ! -f services/api-gateway/target/api-gateway-0.1.0-SNAPSHOT.jar ] && mvn -q -B package -DskipTests
set -a; . ./.env; set +a
export PUBLIC_BASE_URL="${PUBLIC_BASE_URL:-http://localhost:8080}"
mkdir -p .run
for s in auth-service merchant-service order-service payment-service provider-integration payment-orchestrator webhook-service api-gateway; do
  nohup "$JAVA_HOME/bin/java" -jar services/$s/target/$s-0.1.0-SNAPSHOT.jar > .run/$s.log 2>&1 &
  echo "started $s"
done
for i in $(seq 1 80); do
  ok=1; for p in 8080 8081 8082 8083 8084 8085 8086 8087; do curl -s localhost:$p/actuator/health | grep -q UP || ok=0; done
  [ $ok = 1 ] && { echo "all services UP (gateway http://localhost:8080)"; exit 0; }; sleep 3
done
echo "some services did not become healthy; see .run/*.log"; exit 1
