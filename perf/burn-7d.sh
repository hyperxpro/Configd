#!/usr/bin/env bash
# 7-day burn-in harness.
#
# Drives a sustained 80%-of-capacity workload against a 5-node cluster
# for 7 days while injecting periodic chaos:
#   • 1 leader kill per 12h
#   • 1 partition-then-heal per 24h (≤ 30s)
#   • 1 disk-fsync stall per 48h (≤ 5s)
#   • 1 TLS hot-reload per 36h
#
# The intent is not to find steady-state perf bugs (the soak harness covers
# that) but to surface accumulating drift: fd leaks, cache fragmentation,
# log-segment fragmentation, follower-side metric staleness.
#
# Usage:
#   perf/burn-7d.sh [--duration=<seconds>] [--seed=<int>] [--out=<dir>]
#
# This is a calendar-bounded run (status=YELLOW until it actually runs for
# 7 days). The harness always emits a measured-elapsed line recording the
# actual run duration — never edit it to claim a longer duration than was
# actually executed.

set -euo pipefail

DURATION_SEC=$((7 * 24 * 3600))
SEED="${SEED:-43}"
OUT_DIR="${OUT_DIR:-perf/results/burn-$(date -u +%Y%m%dT%H%M%SZ)}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --duration=*) DURATION_SEC="${1#*=}" ;;
    --seed=*)     SEED="${1#*=}" ;;
    --out=*)      OUT_DIR="${1#*=}" ;;
    -h|--help)
      sed -n '2,16p' "$0"
      exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
  shift
done

mkdir -p "$OUT_DIR"
RESULT_FILE="$OUT_DIR/result.txt"

echo "burn-7d harness" > "$RESULT_FILE"
echo "  requested_duration_sec=$DURATION_SEC" >> "$RESULT_FILE"
echo "  seed=$SEED" >> "$RESULT_FILE"
echo "  start_utc=$(date -u +%FT%TZ)" >> "$RESULT_FILE"

t_start=$(date +%s)

# A real run would interleave the workload with the chaos schedule above;
# this harness only honours the requested duration today, so it sleeps.
end=$(( t_start + DURATION_SEC ))
while (( $(date +%s) < end )); do
  sleep 60
done

t_end=$(date +%s)
elapsed_sec=$(( t_end - t_start ))

echo "  end_utc=$(date -u +%FT%TZ)" >> "$RESULT_FILE"
echo "  measured_elapsed_sec=$elapsed_sec" >> "$RESULT_FILE"
echo "  status=YELLOW (no workload wired; duration honoured)" >> "$RESULT_FILE"

echo "burn-7d: measured elapsed ${elapsed_sec}s — see $RESULT_FILE"
