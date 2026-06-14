#!/usr/bin/env bash
# =============================================================================
# wsB-live-write.sh — Session 5 / Workstream B live-cluster write-path driver
# -----------------------------------------------------------------------------
# Launches a real 3-node localhost control-plane cluster (the same launch shape as
# gates/smoke-multinode.sh — which we do NOT modify), resolves the leader, then runs
# the OpenLoopWriteDriver phases against the leader's HTTP API:
#
#   PHASE 2  (low-rate)  : unloaded local write-commit latency (the local_commit_component)
#   PHASE 3  (10k/s)     : sustained 10,000 writes/s for >=60s, CO-corrected latency-at-rate
#   PHASE 4  (100k/s)    : burst/saturation characterization (where + how it sheds)
#   CALIBRATE (F4)       : generator/server max sustainable commit rate (run FIRST)
#
# Each PUT blocks until quorum commit (ADR-0033), so a 200 == a real committed write.
# This exercises the real HTTP -> propose -> quorum -> commit -> 200 path (methodology §1:
# the §0.1 throughput target is a fully-local single-host mechanism).
#
# Usage:  perf/wsB-live-write.sh <calibrate|phase2|phase3|phase4|all> [outdir]
# Requires a freshly-built shaded server jar + benchmarks.jar. Idempotent cleanup.
# =============================================================================
set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="${CONFIGD_JAR:-$(ls "$ROOT"/configd-server/target/configd-server-*.jar 2>/dev/null | grep -v original- | head -1)}"
BENCH="$ROOT/configd-testkit/target/benchmarks.jar"
BASE="/tmp/configd-wsB-$$"
RAFT_BASE=9190
API_BASE=8180
PEERS_ADDR="1=127.0.0.1:9191,2=127.0.0.1:9192,3=127.0.0.1:9193"
HEAP="${WSB_HEAP:--Xmx1g -Xms1g}"
GCFLAGS="${WSB_GC:--XX:+UseZGC}"   # chosen collector (ADR-0041): ZGC
MODE="${1:-all}"
OUT="${2:-$ROOT/docs/session-5/captures}"
PIDS=()

fail() { echo "WSB FAIL: $*" >&2; cleanup; exit 1; }
cleanup() {
  for pid in "${PIDS[@]:-}"; do kill -9 "$pid" 2>/dev/null; done
  pkill -9 -f -- "--data-dir $BASE/" 2>/dev/null
  rm -rf "$BASE" 2>/dev/null
}
trap cleanup EXIT
[ -f "$JAR" ] || fail "shaded jar not found: $JAR (build: ./mvnw -pl configd-server -am package)"
[ -f "$BENCH" ] || fail "benchmarks.jar not found (build: ./mvnw -pl configd-testkit -am package)"
mkdir -p "$BASE" "$OUT"

api() { echo "127.0.0.1:$((API_BASE + $1))"; }

launch_cluster() {
  echo "[wsB] launching 3-node cluster ($GCFLAGS $HEAP) under $BASE"
  for k in 1 2 3; do
    peers=$(echo "1 2 3" | tr ' ' '\n' | grep -v "^$k$" | paste -sd,)
    dd="$BASE/n$k"; mkdir -p "$dd"
    java $GCFLAGS $HEAP --enable-preview -jar "$JAR" \
      --node-id "$k" --data-dir "$dd" --peers "$peers" \
      --bind-address 127.0.0.1 --bind-port $((RAFT_BASE + k)) \
      --api-port $((API_BASE + k)) --peer-addresses "$PEERS_ADDR" \
      > "$BASE/n$k.log" 2>&1 &
    PIDS+=("$!")
  done
  local ready=0
  for i in $(seq 1 40); do
    local ok=0
    for k in 1 2 3; do
      code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 1 "http://$(api $k)/health/ready" 2>/dev/null)
      [ "$code" = "200" ] && ok=$((ok + 1))
    done
    [ "$ok" -eq 3 ] && { ready=1; break; }
    sleep 0.5
  done
  [ "$ready" -eq 1 ] || fail "cluster not ready"
  echo "[wsB] all 3 nodes ready"
}

resolve_leader() {
  for _try in $(seq 1 40); do
    for k in 1 2 3; do
      code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 8 -X PUT -d probe \
             "http://$(api $k)/v1/config/__wsB_probe__" 2>/dev/null)
      [ "$code" = "200" ] && { echo "$k"; return 0; }
    done
    sleep 0.5
  done
  return 1
}

run_phases() {
  local L; L=$(resolve_leader) || fail "no leader"
  # Full node-id -> API-URL map so the driver can FOLLOW the X-Leader-Hint under churn
  # (a real client behaviour; the driver counts + reports retargets).
  local NODEMAP="1=http://$(api 1),2=http://$(api 2),3=http://$(api 3)"
  echo "[wsB] initial leader = node $L; nodeMap=$NODEMAP"

  if [ "$MODE" = "calibrate" ] || [ "$MODE" = "all" ]; then
    echo "[wsB] F4 calibration (max sustainable commit rate, closed-loop)"
    java $GCFLAGS $HEAP --enable-preview -cp "$BENCH" io.configd.bench.OpenLoopWriteDriver \
      calibrate "$NODEMAP" 20 64 2>&1 | grep -v "WARNING\|Unsafe" | tee "$OUT/wsB-calibrate.txt"
  fi
  if [ "$MODE" = "phase2" ] || [ "$MODE" = "all" ]; then
    echo "[wsB] Phase 2: low-rate unloaded commit latency (200/s, 30s)"
    java $GCFLAGS $HEAP --enable-preview -cp "$BENCH" io.configd.bench.OpenLoopWriteDriver \
      atrate "$NODEMAP" 200 30 64 256 2>&1 | grep -v "WARNING\|Unsafe" | tee "$OUT/wsB-phase2-lowrate.txt"
  fi
  if [ "$MODE" = "phase3" ] || [ "$MODE" = "all" ]; then
    echo "[wsB] Phase 3: 10k/s sustained, 70s"
    java $GCFLAGS $HEAP --enable-preview -cp "$BENCH" io.configd.bench.OpenLoopWriteDriver \
      atrate "$NODEMAP" 10000 70 256 512 2>&1 | grep -v "WARNING\|Unsafe" | tee "$OUT/wsB-phase3-10k.txt"
  fi
  if [ "$MODE" = "phase4" ] || [ "$MODE" = "all" ]; then
    echo "[wsB] Phase 4: 100k/s burst, 30s (saturation/shed characterization)"
    java $GCFLAGS $HEAP --enable-preview -cp "$BENCH" io.configd.bench.OpenLoopWriteDriver \
      atrate "$NODEMAP" 100000 30 512 512 2>&1 | grep -v "WARNING\|Unsafe" | tee "$OUT/wsB-phase4-100k.txt"
    echo "[wsB] post-burst leader metrics snapshot:"
    curl -s --max-time 3 "http://$(api "$L")/metrics" 2>/dev/null | grep -iE "pending|overload|queue|raft|apply|write" | head -25 | tee "$OUT/wsB-phase4-metrics.txt"
  fi
}

launch_cluster
run_phases
echo "WSB DONE (mode=$MODE)"
