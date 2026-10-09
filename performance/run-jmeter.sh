#!/usr/bin/env bash
# Runs the APG load plan with the JMeter Docker image (no local install). Usage: performance/run-jmeter.sh [threads] [duration-seconds]
# Results: performance/results/<timestamp>/{results.jtl,report/index.html}. LOCAL ONLY (see performance/README.md safety notes).
set -eu
cd "$(dirname "$0")/.."
THREADS="${1:-20}"; DURATION="${2:-60}"; RAMP="${RAMPUP:-10}"
[ -f performance/data/credentials.csv ] || { echo "run performance/setup-data.sh first"; exit 1; }
STAMP="$(date +%Y%m%d-%H%M%S)"; OUT="performance/results/$STAMP"; mkdir -p "$OUT"
python performance/jmeter/generate_plan.py >/dev/null
# host.docker.internal reaches the gateway running on the host
MSYS_NO_PATHCONV=1 docker run --rm --add-host host.docker.internal:host-gateway \
  -v "$(pwd -W 2>/dev/null || pwd)/performance/jmeter:/plan:ro" -v "$(pwd -W 2>/dev/null || pwd)/performance/data:/data:ro" -v "$(pwd -W 2>/dev/null || pwd)/$OUT:/out" \
  "${JMETER_IMAGE:-justb4/jmeter:5.5}" -n -t /plan/apg-load.jmx -l /out/results.jtl -e -o /out/report \
  -Jhost=host.docker.internal -Jport=8080 -Jthreads="$THREADS" -Jduration="$DURATION" -Jrampup="$RAMP" -Jcreds=/data/credentials.csv
echo "report: $OUT/report/index.html"
