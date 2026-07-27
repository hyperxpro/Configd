#!/usr/bin/env bash
# 7-day burn-in harness: 80% capacity + periodic chaos (kill leader, partition, fsync stall, TLS reload).
# Detect accumulating drift (fd leaks, cache fragmentation, log-segment fragmentation, metric staleness).
# Calendar-bounded; emits measured_elapsed_sec honestly.
#
# Usage: perf/burn-7d.sh [--duration=<seconds>] [--seed=<int>] [--out=<dir>]

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
