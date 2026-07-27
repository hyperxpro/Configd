#!/usr/bin/env bash
# run-matrix.sh — faulted-linearizability matrix driver.
# Unmodified jar through matrix of seeded fault+workload schedules; every history checked by Porcupine.
# Adversarial schedule (overlapping quorum-breaking nemeses) across N=3/5, encrypt/auth/skew postures.
# Cell=(mode,N,posture,seed); every cell must LINEARIZABLE. Single NON_LINEARIZABLE fails (exit 1).
# INDETERMINATE retried once, then flagged (exit 2), never silent pass.
#
# Usage:
#   run-matrix.sh --out DIR [options]
# Options (defaults below):
#   --profile smoke|full  --nodes "3 5"  --postures "base encrypt auth"
#   --adv-seeds N  --seq-seeds N  --adv-dur MS  --keys K
#   --shard i/n  --faketime-lib PATH  --jar PATH

set -u
cd "$(dirname "${BASH_SOURCE[0]}")/../.." || exit 2
ROOT="$(pwd)"

JAR="$ROOT/configd-server/target/configd-server-0.1.0-SNAPSHOT.jar"
CP="$ROOT/configd-linz/target/classes"
export PORCUPINE_BIN="${PORCUPINE_BIN:-$ROOT/configd-linz/bin/porcupine-check}"

OUT=""
PROFILE="full"
NODES="3 5"
POSTURES="base encrypt auth"
ADV_SEEDS=""; SEQ_SEEDS=""; ADV_DUR=""; KEYS=""
SHARD="1/1"
FAKETIME_LIB=""

while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT="$2"; shift 2;;
    --profile) PROFILE="$2"; shift 2;;
    --nodes) NODES="$2"; shift 2;;
    --postures) POSTURES="$2"; shift 2;;
    --adv-seeds) ADV_SEEDS="$2"; shift 2;;
    --seq-seeds) SEQ_SEEDS="$2"; shift 2;;
    --adv-dur) ADV_DUR="$2"; shift 2;;
    --keys) KEYS="$2"; shift 2;;
    --shard) SHARD="$2"; shift 2;;
    --faketime-lib) FAKETIME_LIB="$2"; shift 2;;
    --jar) JAR="$2"; shift 2;;
    *) echo "unknown option: $1"; exit 2;;
  esac
done
[ -n "$OUT" ] || { echo "run-matrix.sh: --out DIR is required"; exit 2; }
[ -x "$PORCUPINE_BIN" ] || { echo "run-matrix.sh: PORCUPINE_BIN not executable: $PORCUPINE_BIN"; exit 2; }
[ -f "$JAR" ] || { echo "run-matrix.sh: server jar not found: $JAR"; exit 2; }

if [ "$PROFILE" = "smoke" ]; then
  : "${ADV_SEEDS:=2}"; : "${SEQ_SEEDS:=2}"; : "${ADV_DUR:=30000}"; : "${KEYS:=8}"
else
  : "${ADV_SEEDS:=40}"; : "${SEQ_SEEDS:=10}"; : "${ADV_DUR:=90000}"; : "${KEYS:=16}"
fi

SHARD_I="${SHARD%/*}"; SHARD_N="${SHARD#*/}"
mkdir -p "$OUT"
SUMMARY="$OUT/summary-shard${SHARD_I}of${SHARD_N}.tsv"
SHA="$(git rev-parse HEAD 2>/dev/null || echo unknown)"
: > "$SUMMARY"
printf 'sha\tmode\tnodes\tposture\tseed\tfaults\tops\tverdict\texit\n' >> "$SUMMARY"

# Ports from GLOBAL cell index: unique non-overlapping per shard; parallel shards never collide.
# Never pkill between runs (kills sibling shards); HarnessMain self-cleans via finally+shutdown-hook.
CELL=0
RED=0; INDET=0; RUN=0

posture_args() {
  case "$1" in
    base)    echo "";;
    encrypt) echo "--encrypt-at-rest true";;
    auth)    echo "--auth-token e1-matrix-secret-token";;
    skew)    echo "--clock-skew 3 --faketime-lib $FAKETIME_LIB";;
    shards)  echo "--shards 4";;   # multi-Raft: per-key check == per-shard linearizability (2.2-5)
  esac
}

run_cell() {
  local mode="$1" n="$2" posture="$3" seed="$4" dur="$5" readpct="$6"
  CELL=$((CELL+1))
  if [ "$SHARD_N" -gt 1 ] && [ $(( (CELL - 1) % SHARD_N + 1 )) -ne "$SHARD_I" ]; then
    return 0
  fi
  local pargs; pargs="$(posture_args "$posture")"
  if [ "$posture" = "skew" ] && [ -z "$FAKETIME_LIB" ]; then
    printf '%s\t%s\t%s\t%s\t%s\t-\t-\tSKIPPED(no-faketime)\t-\n' "$SHA" "$mode" "$n" "$posture" "$seed" >> "$SUMMARY"
    return 0
  fi
  local br=$((16000 + CELL * 24)); local ba=$((26000 + CELL * 24))
  local tag="$mode-n$n-$posture-$seed"
  local log="$OUT/run-$tag.log"
  local code v faults ops
  timeout 400 java --enable-preview -cp "$CP" io.configd.linz.runner.HarnessMain \
    --seed "$seed" --nodes "$n" --clients 6 --keys "$KEYS" --duration "$dur" \
    --mode "$mode" --max-concurrent 3 --read-pct "$readpct" $pargs \
    --base-raft "$br" --base-api "$ba" --jar "$JAR" --out "$OUT" > "$log" 2>&1
  code=$?
  v=$(grep -oE 'VERDICT: [A-Z_]+' "$log" | tail -1 | awk '{print $2}')
  faults=$(grep -c '^\[fault\]' "$log")
  ops=$(grep -oE 'recorded [0-9]+ ops' "$log" | grep -oE '[0-9]+' | head -1)
  if [ "$code" = "2" ]; then
    br=$((br + 12)); ba=$((ba + 12))
    timeout 400 java --enable-preview -cp "$CP" io.configd.linz.runner.HarnessMain \
      --seed "$seed" --nodes "$n" --clients 6 --keys "$KEYS" --duration "$dur" \
      --mode "$mode" --max-concurrent 3 --read-pct "$readpct" $pargs \
      --base-raft "$br" --base-api "$ba" --jar "$JAR" --out "$OUT" > "$log.retry" 2>&1
    code=$?
    v=$(grep -oE 'VERDICT: [A-Z_]+' "$log.retry" | tail -1 | awk '{print $2}')
    faults=$(grep -c '^\[fault\]' "$log.retry")
    ops=$(grep -oE 'recorded [0-9]+ ops' "$log.retry" | grep -oE '[0-9]+' | head -1)
  fi
  RUN=$((RUN+1))
  [ "$code" = "1" ] && RED=$((RED+1))
  [ "$code" = "2" ] && INDET=$((INDET+1))
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$SHA" "$mode" "$n" "$posture" "$seed" "${faults:-0}" "${ops:-0}" "${v:-NONE}" "$code" >> "$SUMMARY"
  echo "[matrix] $tag -> ${v:-NONE} (exit $code, $faults faults, ${ops:-?} ops)"
}

echo "[matrix] shard $SHARD_I/$SHARD_N  sha=$SHA  profile=$PROFILE  nodes='$NODES'  postures='$POSTURES'"
echo "[matrix] adv-seeds=$ADV_SEEDS seq-seeds=$SEQ_SEEDS adv-dur=${ADV_DUR}ms keys=$KEYS  out=$OUT"

seed_base() { case "$1" in base) echo 8000;; encrypt) echo 12000;; auth) echo 16000;; skew) echo 20000;; *) echo 24000;; esac; }

for n in $NODES; do
  # ADVERSARIAL cells across the requested postures
  for posture in $POSTURES; do
    base=$(seed_base "$posture")
    s=0
    while [ "$s" -lt "$ADV_SEEDS" ]; do
      run_cell adversarial "$n" "$posture" $((base + s)) "$ADV_DUR" 60
      s=$((s+1))
    done
  done
  # SEQUENTIAL continuity cells (base posture only) -- same schedule the CI gate uses, at depth
  s=0
  while [ "$s" -lt "$SEQ_SEEDS" ]; do
    run_cell sequential "$n" base $((30000 + s)) 20000 72
    s=$((s+1))
  done
done

echo "==============================================================================="
echo "[matrix] shard $SHARD_I/$SHARD_N DONE: $RUN runs, $RED non-linearizable, $INDET indeterminate"
echo "[matrix] summary -> $SUMMARY"
if [ "$RED" -gt 0 ]; then echo "[matrix] RESULT: FAIL (non-linearizable history found)"; exit 1; fi
if [ "$INDET" -gt 0 ]; then echo "[matrix] RESULT: INDETERMINATE ($INDET cells)"; exit 2; fi
echo "[matrix] RESULT: PASS (every history LINEARIZABLE)"
exit 0
