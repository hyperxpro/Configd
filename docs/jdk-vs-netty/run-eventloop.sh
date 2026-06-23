#!/usr/bin/env bash
# Non-JFR clean measurement of the event-loop-driven Netty send (writes inline → no WriteTask).
cd "$(dirname "$0")/../.." || exit 1
JAR=configd-testkit/target/benchmarks.jar
PORT=19095
OUT=eventloop-check.txt
: > "$OUT"
pkill -9 -f ConsensusDrainServerMain 2>/dev/null
java --enable-preview -cp "$JAR" io.configd.jdkvsnetty.ConsensusDrainServerMain "$PORT" > drain-prof.txt 2>&1 &
D=$!
for _ in $(seq 1 50); do grep -q DRAIN_READY drain-prof.txt 2>/dev/null && break; sleep 0.2; done
for payload in 0 4096; do
  java --enable-preview --enable-native-access=ALL-UNNAMED \
    -Dio.netty.leakDetection.level=DISABLED -Dio.netty.allocator.numDirectArenas=2 \
    -cp "$JAR" io.configd.jdkvsnetty.ConsensusSendE2EMain 127.0.0.1 "$PORT" "$payload" 200000 2000000 eventloop \
    2>&1 | grep '^E2E' >> "$OUT"
done
kill -9 "$D" 2>/dev/null
echo "== done ==" >> "$OUT"
exit 0
