#!/usr/bin/env bash
# Show records of a topic, e.g. a dead-letter topic:   scripts/kafka-peek.sh payment.success.DLT [max]
# Needs Java and the Maven repository (kafka-clients jar); reads only, commits nothing.
set -euo pipefail
cd "$(dirname "$0")/.."
TOPIC="${1:?usage: kafka-peek.sh <topic> [max-records]}"
M2="${M2_REPO:-$HOME/.m2/repository}"
KC="$(ls "$M2"/org/apache/kafka/kafka-clients/3.9.*/kafka-clients-3.9.*[0-9].jar 2>/dev/null | tail -1)"
SL="$(ls "$M2"/org/slf4j/slf4j-api/2.0.*/slf4j-api-2.0.*[0-9].jar 2>/dev/null | tail -1)"
[ -n "$KC" ] && [ -n "$SL" ] || { echo "kafka-clients / slf4j-api jars not found in $M2 (run a Maven build once)"; exit 1; }
SEP=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";"; KC="$(cygpath -m "$KC")"; SL="$(cygpath -m "$SL")";; esac
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
exec "$JAVA" -cp "$KC$SEP$SL" scripts/KafkaPeek.java "${KAFKA_BOOTSTRAP_SERVERS:-localhost:9092}" "$TOPIC" "${2:-20}" 2>&1 | grep -v "^SLF4J"
