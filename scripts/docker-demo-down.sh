#!/usr/bin/env bash
# Stops the Docker demo.   bash scripts/docker-demo-down.sh        (keeps the database)
#                          bash scripts/docker-demo-down.sh --wipe  (also deletes all data: fresh start next time)
cd "$(dirname "$0")/.."
FILES="-f docker-compose.yml -f docker-compose.apps.yml -f docker-compose.demo.yml"
if [ "${1:-}" = "--wipe" ]; then docker compose $FILES down -v; else docker compose $FILES down; fi
