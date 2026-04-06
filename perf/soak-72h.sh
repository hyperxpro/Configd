#!/usr/bin/env bash
# C1 — 72-h soak harness.
#
# Runs a steady-state workload against a 5-node Configd cluster and
# asserts that:
#   • write commit p99 < 150 ms
#   • edge read p99 < 1 ms
#   • propagation p99 < 500 ms
#   • RSS does not grow more than 10% from t+15min to t+end
#   • no leader churn after the warmup window
#
# Usage:
#   perf/soak-72h.sh [--duration=<seconds>] [--seed=<int>] [--out=<dir>]
#
# Calendar-bounded — gap-closure §4 marks this YELLOW. The harness MUST
# emit a measured-elapsed line so the GA review can record actual run
# duration without rounding up. Do not edit the harness to claim a longer
# duration than was actually executed.

set -euo pipefail

DURATION_SEC=$((72 * 3600))
SEED="${SEED:-42}"
OUT_DIR="${OUT_DIR:-perf/results/soak-$(date -u +%Y%m%dT%H%M%SZ)}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --duration=*) DURATION_SEC="${1#*=}" ;;
    --seed=*)     SEED="${1#*=}" ;;
    --out=*)      OUT_DIR="${1#*=}" ;;
    -h|--help)
      sed -n '2,18p' "$0"
      exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
  shift
done

mkdir -p "$OUT_DIR"
RESULT_FILE="$OUT_DIR/result.txt"

echo "soak-72h harness" > "$RESULT_FILE"
echo "  requested_duration_sec=$DURATION_SEC" >> "$RESULT_FILE"
echo "  seed=$SEED" >> "$RESULT_FILE"
echo "  start_utc=$(date -u +%FT%TZ)" >> "$RESULT_FILE"

t_start=$(date +%s)

# Real harness body would:
#   1. Bring up a 5-node cluster via configd-testkit harness
#   2. Spin a write loop at the SLO rate (1k commits/sec typical)
#   3. Run a read loop from edge nodes targeting p99 < 1 ms
#   4. Sample RSS, GC pause, leader-elections every 30s
#   5. Tear down and assert SLOs at end
# Until the cluster bringup is wired to this script (Phase 10 / DR
# integration), this harness only enforces the duration contract and
# emits the measured elapsed.

sleep_remaining() {
  local end=$(( t_start + DURATION_SEC ))
  local now=$(date +%s)
  while (( now < end )); do
    sleep 60
    now=$(date +%s)
  done
}

# Honour the duration even when no workload is wired yet, so the
# harness still produces a measured elapsed time. Operators running
# this in CI should pass --duration=300 for smoke; production soak runs
# default to 72h.
sleep_remaining

t_end=$(date +%s)
elapsed_sec=$(( t_end - t_start ))

echo "  end_utc=$(date -u +%FT%TZ)" >> "$RESULT_FILE"
echo "  measured_elapsed_sec=$elapsed_sec" >> "$RESULT_FILE"
echo "  status=YELLOW (no workload wired; duration honoured)" >> "$RESULT_FILE"

echo "soak-72h: measured elapsed ${elapsed_sec}s — see $RESULT_FILE"
