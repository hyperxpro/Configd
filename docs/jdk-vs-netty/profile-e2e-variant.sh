#!/usr/bin/env bash
# Profile WHERE a given e2e consensus-send variant's allocation comes from, via JFR
# (jdk.ObjectAllocationSample count-by-type ~ bytes-by-type). Runs that variant ONLY.
# Usage: profile-e2e-variant.sh <payload> <jdk|jdk-batched|manual|idiomatic>
cd "$(dirname "$0")/../.." || exit 1
JAR=configd-testkit/target/benchmarks.jar
PORT=19092
PAYLOAD="${1:-0}"
VARIANT="${2:-jdk}"
OUT="e2e-attr-${VARIANT}-${PAYLOAD}.txt"
JFR="e2e-attr-${VARIANT}-${PAYLOAD}.jfr"
: > "$OUT"
pkill -9 -f ConsensusDrainServerMain 2>/dev/null
java --enable-preview -cp "$JAR" io.configd.jdkvsnetty.ConsensusDrainServerMain "$PORT" > drain-prof.txt 2>&1 &
DRAIN=$!
for _ in $(seq 1 50); do grep -q DRAIN_READY drain-prof.txt 2>/dev/null && break; sleep 0.2; done

java --enable-preview --enable-native-access=ALL-UNNAMED \
  -Dio.netty.leakDetection.level=DISABLED -Dio.netty.allocator.numDirectArenas=2 \
  -XX:StartFlightRecording=filename="$JFR",settings=profile,dumponexit=true \
  -cp "$JAR" io.configd.jdkvsnetty.ConsensusSendE2EMain 127.0.0.1 "$PORT" "$PAYLOAD" 200000 2000000 "$VARIANT" \
  2>&1 | grep -E '^E2E' >> "$OUT"
kill -9 "$DRAIN" 2>/dev/null

echo "=== variant=$VARIANT payload=$PAYLOAD : allocation sample count by type (~ bytes) ===" >> "$OUT"
jfr print --events jdk.ObjectAllocationSample "$JFR" 2>/dev/null \
  | grep 'objectClass =' | sed 's/.*objectClass = //; s/ (classLoader.*//' \
  | sort | uniq -c | sort -rn | head -15 >> "$OUT"
echo "=== total sampled bytes (weight) ===" >> "$OUT"
jfr print --events jdk.ObjectAllocationSample "$JFR" 2>/dev/null \
  | grep -c 'objectClass =' | sed 's/^/sample_count=/' >> "$OUT"
echo "== done ==" >> "$OUT"
rm -f "$JFR"
exit 0
