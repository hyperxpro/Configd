#!/usr/bin/env bash
# Profile WHERE the idiomatic-Netty consensus-send allocation comes from (jdk.ObjectAllocationSample
# count-by-type ~= bytes-by-type). Runs the idiomatic variant ONLY so the manual path's 196 B/msg
# doesn't drown the samples. Output: alloc-profile-<payload>.txt + the .jfr for stack inspection.
cd "$(dirname "$0")/../.." || exit 1
JAR=configd-testkit/target/benchmarks.jar
PORT=19091
PAYLOAD="${1:-0}"
OUT="alloc-profile-${PAYLOAD}.txt"
JFR="alloc-idiom-${PAYLOAD}.jfr"
: > "$OUT"
pkill -9 -f ConsensusDrainServerMain 2>/dev/null
java --enable-preview -cp "$JAR" io.configd.jdkvsnetty.ConsensusDrainServerMain "$PORT" > drain-prof.txt 2>&1 &
DRAIN=$!
for _ in $(seq 1 50); do grep -q DRAIN_READY drain-prof.txt 2>/dev/null && break; sleep 0.2; done

java --enable-preview --enable-native-access=ALL-UNNAMED \
  -Dio.netty.leakDetection.level=DISABLED -Dio.netty.allocator.numDirectArenas=2 \
  -XX:StartFlightRecording=filename="$JFR",settings=profile,dumponexit=true \
  -cp "$JAR" io.configd.jdkvsnetty.ConsensusSendE2EMain 127.0.0.1 "$PORT" "$PAYLOAD" 100000 1000000 idiomatic \
  2>&1 | grep -E '^E2E' >> "$OUT"
kill -9 "$DRAIN" 2>/dev/null

echo "=== idiomatic payload=$PAYLOAD : allocation sample count by type (~ proportional to bytes) ===" >> "$OUT"
jfr print --events jdk.ObjectAllocationSample "$JFR" 2>/dev/null \
  | grep 'objectClass =' | sed 's/.*objectClass = //; s/ (classLoader.*//' \
  | sort | uniq -c | sort -rn | head -20 >> "$OUT"
echo "== done ==" >> "$OUT"
exit 0
