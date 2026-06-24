#!/usr/bin/env bash
# End-to-end consensus-send head-to-head: JDK Socket (heap buffer, production-style) vs idiomatic
# Netty (pooled direct ByteBuf -> Epoll channel). Each variant builds its connection ONCE and runs
# warmup AND measurement on that same WARM connection. PLAINTEXT (best case for Netty's zero-copy
# argument; TLS only shrinks it). Drain receiver is a separate process so the sender's
# getTotalThreadAllocatedBytes() delta is the sender-side per-message allocation.
#
# Usage: docs/jdk-vs-netty/run-consensus-e2e.sh [warmupN] [measureN]
# NOTE: no `set -e`/`pipefail` — they interacted badly with the agent shell; we guard explicitly.
cd "$(dirname "$0")/../.." || exit 1
JAR=configd-testkit/target/benchmarks.jar
PORT=19077
WARMUP="${1:-100000}"
MEASURE="${2:-500000}"
OUT=docs/jdk-vs-netty/raw/consensus-e2e.txt
mkdir -p docs/jdk-vs-netty/raw
: > "$OUT"

pkill -9 -f ConsensusDrainServerMain 2>/dev/null
java --enable-preview -cp "$JAR" io.configd.jdkvsnetty.ConsensusDrainServerMain "$PORT" \
  > docs/jdk-vs-netty/raw/consensus-e2e-drain.txt 2>&1 &
DRAIN_PID=$!
for _ in $(seq 1 50); do
  grep -q DRAIN_READY docs/jdk-vs-netty/raw/consensus-e2e-drain.txt 2>/dev/null && break
  sleep 0.2
done
echo "drain ready on :$PORT" | tee -a "$OUT"

for payload in 0 4096; do
  echo "== payload=$payload (warmup=$WARMUP measure=$MEASURE) ==" | tee -a "$OUT"
  java --enable-preview --enable-native-access=ALL-UNNAMED \
    -Dio.netty.leakDetection.level=DISABLED -Dio.netty.allocator.numDirectArenas=2 \
    -cp "$JAR" io.configd.jdkvsnetty.ConsensusSendE2EMain \
    127.0.0.1 "$PORT" "$payload" "$WARMUP" "$MEASURE" 2>&1 | grep -E '^E2E' | tee -a "$OUT"
done
kill -9 "$DRAIN_PID" 2>/dev/null
echo "== done ==" | tee -a "$OUT"
exit 0
