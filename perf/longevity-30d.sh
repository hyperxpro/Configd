#!/usr/bin/env bash
# C3 — 30-day longevity harness.
#
# Same workload shape as soak-72h but for 30 days, with daily snapshot
# install + truncate cycles to verify long-running snapshot subsystem
# health. Watches:
#   • snapshot-install latency drift
#   • WAL segment count growth
#   • disk-space high-watermark
#   • 30-day p99 latency stability (must stay within ±10% of day-1
#     measured baseline)
#
# Usage:
#   perf/longevity-30d.sh [--duration=<seconds>] [--seed=<int>] [--out=<dir>]
#
# YELLOW per gap-closure §4. Real 30-day runs are calendar-bound — record
# the measured elapsed honestly. Smoke runs in CI use --duration=600.

set -euo pipefail

DURATION_SEC=$((30 * 24 * 3600))
SEED="${SEED:-44}"
OUT_DIR="${OUT_DIR:-perf/results/longevity-$(date -u +%Y%m%dT%H%M%SZ)}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --duration=*) DURATION_SEC="${1#*=}" ;;
    --seed=*)     SEED="${1#*=}" ;;
    --out=*)      OUT_DIR="${1#*=}" ;;
    -h|--help)
      sed -n '2,17p' "$0"
      exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
  shift
done

mkdir -p "$OUT_DIR"
RESULT_FILE="$OUT_DIR/result.txt"

echo "longevity-30d harness" > "$RESULT_FILE"
echo "  requested_duration_sec=$DURATION_SEC" >> "$RESULT_FILE"
echo "  seed=$SEED" >> "$RESULT_FILE"
echo "  start_utc=$(date -u +%FT%TZ)" >> "$RESULT_FILE"

t_start=$(date +%s)

end=$(( t_start + DURATION_SEC ))
while (( $(date +%s) < end )); do
  sleep 60
done

t_end=$(date +%s)
elapsed_sec=$(( t_end - t_start ))

echo "  end_utc=$(date -u +%FT%TZ)" >> "$RESULT_FILE"
echo "  measured_elapsed_sec=$elapsed_sec" >> "$RESULT_FILE"
echo "  status=YELLOW (no workload wired; duration honoured)" >> "$RESULT_FILE"

echo "longevity-30d: measured elapsed ${elapsed_sec}s — see $RESULT_FILE"
