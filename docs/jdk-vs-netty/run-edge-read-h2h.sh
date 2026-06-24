#!/usr/bin/env bash
# Edge-read HTTP head-to-head (surface 2) orchestration.
#
# Resolves Phase R's open gating number — the SERVER-SIDE per-request allocation — by running
# the load client in a SEPARATE JVM from the server (so client allocation is excluded by
# construction) and having the server self-measure getTotalThreadAllocatedBytes() across a
# control-socket-delimited window. The SAME harness drives both the production JDK EdgeHttpServer
# (best-JDK form) and the strongest-Netty NettyEdgeReadServer; only the transport shell differs.
#
# Allocation (B/request) is CPU-count-independent → trustworthy on this 2-vCPU box. Throughput /
# latency are RELATIVE-ONLY (client + server share the same 2 vCPU here) — the JDK-vs-Netty delta
# on the identical co-located workload is the valid signal, not the absolute numbers.
#
# Usage: docs/jdk-vs-netty/run-edge-read-h2h.sh [concurrency] [warmupReqs] [measureReqs]
set -euo pipefail
cd "$(dirname "$0")/../.."

JAR=configd-testkit/target/benchmarks.jar
KEY_COUNT=256
VALUE_BYTES=64
CONCURRENCY="${1:-8}"
WARMUP="${2:-50000}"
MEASURE="${3:-200000}"
OUT=docs/jdk-vs-netty/raw
mkdir -p "$OUT"

SERVER_JVM_ARGS=(--enable-preview --enable-native-access=ALL-UNNAMED
  -Dio.netty.leakDetection.level=DISABLED -Dio.netty.allocator.numDirectArenas=2)
CLIENT_JVM_ARGS=(--enable-preview)

run_one() {
  local which="$1" controlPort="$2"
  local serverLog="$OUT/edge-read-${which}-server.txt"
  local clientLog="$OUT/edge-read-${which}-client.txt"
  echo "=== ${which} server ==="
  # Start server (ephemeral http port = 0; it prints READY ... httpPort=N).
  java "${SERVER_JVM_ARGS[@]}" -cp "$JAR" \
    io.configd.edge.node.EdgeReadAllocServerMain "$which" 0 "$controlPort" "$KEY_COUNT" "$VALUE_BYTES" \
    > "$serverLog" 2>&1 &
  local serverPid=$!
  # Wait for READY, parse the bound http port.
  local httpPort=""
  for _ in $(seq 1 100); do
    if grep -q '^READY' "$serverLog" 2>/dev/null; then
      httpPort=$(grep -m1 '^READY' "$serverLog" | sed -E 's/.*httpPort=([0-9]+).*/\1/')
      break
    fi
    if ! kill -0 "$serverPid" 2>/dev/null; then echo "server died:"; cat "$serverLog"; exit 1; fi
    sleep 0.2
  done
  [ -n "$httpPort" ] || { echo "no READY from server"; cat "$serverLog"; kill "$serverPid" 2>/dev/null || true; exit 1; }
  echo "  server ready httpPort=$httpPort ($(grep -m1 '^READY' "$serverLog"))"

  # Drive the out-of-JVM client.
  java "${CLIENT_JVM_ARGS[@]}" -cp "$JAR" \
    io.configd.edge.node.EdgeReadLoadClientMain \
    127.0.0.1 "$httpPort" "$controlPort" "$KEY_COUNT" "$VALUE_BYTES" \
    "$CONCURRENCY" "$WARMUP" "$MEASURE" | tee "$clientLog"
  wait "$serverPid" 2>/dev/null || true
  echo "  server-side result: $(grep -E '^RESULT|^IDLE' "$serverLog" | tr '\n' ' ')"
  echo
}

# Serial (2-vCPU): one server+client pair at a time. Distinct control ports for cleanliness.
run_one jdk   19099
run_one netty 19098
echo "edge-read H2H complete. Raw logs in $OUT/edge-read-*.txt"
