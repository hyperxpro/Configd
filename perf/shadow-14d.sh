#!/usr/bin/env bash
# C4 — 14-day shadow-traffic harness.
#
# Runs production-shaped traffic against a candidate Configd cluster
# while replaying the same traffic against a control cluster (the
# previous GA build). Asserts:
#   • candidate's read responses are byte-identical to control's
#   • candidate's commit ordering matches control's (modulo timestamps)
#   • no SLO regression vs. control on write commit / edge read /
#     propagation
#   • no new ERROR-class log lines in candidate that don't appear in
#     control
#
# Calendar-bounded — gap-closure §4 marks this YELLOW until two real
# clusters have been brought up and a 14-day window has actually
# elapsed. The harness MUST emit a measured-elapsed line so the GA
# review records actual run duration without rounding up. Do not edit
# the harness to claim a longer duration than was actually executed.
#
# Usage:
#   perf/shadow-14d.sh [--duration=<seconds>] [--seed=<int>] [--out=<dir>]

set -euo pipefail

DURATION_SEC=$((14 * 24 * 3600))
SEED="${SEED:-42}"
OUT_DIR="${OUT_DIR:-perf/results/shadow-$(date -u +%Y%m%dT%H%M%SZ)}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --duration=*) DURATION_SEC="${1#*=}" ;;
    --seed=*)     SEED="${1#*=}" ;;
    --out=*)      OUT_DIR="${1#*=}" ;;
    -h|--help)
      sed -n '2,21p' "$0"
      exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
  shift
done

mkdir -p "$OUT_DIR"
RESULT_FILE="$OUT_DIR/result.txt"

echo "shadow-14d harness" > "$RESULT_FILE"
echo "  requested_duration_sec=$DURATION_SEC" >> "$RESULT_FILE"
echo "  seed=$SEED" >> "$RESULT_FILE"
echo "  start_utc=$(date -u +%FT%TZ)" >> "$RESULT_FILE"

t_start=$(date +%s)

# Real harness body would:
#   1. Bring up two clusters (control = previous GA, candidate = HEAD).
#   2. Mirror real traffic via TrafficSplitter (operator-supplied
#      mirroring proxy in front of both write and read paths).
#   3. Diff every read response between control and candidate;
#      record any divergence with the diverging request.
#   4. Sample SLO histograms from both clusters every 30s; assert
#      candidate p99 ≤ control p99 × 1.05 (5% slack).
#   5. Tail logs from both; alert on ERROR lines in candidate not
#      present in control.
#   6. Teardown and emit pass/fail.
# Until the bring-up is wired (operator action — needs two real
# clusters, not local), this harness honours the duration contract
# and emits measured elapsed.

sleep_remaining() {
  local end=$(( t_start + DURATION_SEC ))
  local now=$(date +%s)
  while (( now < end )); do
    sleep 60
    now=$(date +%s)
  done
}

sleep_remaining

t_end=$(date +%s)
elapsed_sec=$(( t_end - t_start ))

echo "  end_utc=$(date -u +%FT%TZ)" >> "$RESULT_FILE"
echo "  measured_elapsed_sec=$elapsed_sec" >> "$RESULT_FILE"
echo "  status=YELLOW (no shadow traffic wired; duration honoured)" >> "$RESULT_FILE"

echo "shadow-14d: measured elapsed ${elapsed_sec}s — see $RESULT_FILE"
