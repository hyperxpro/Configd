#!/usr/bin/env bash
# =============================================================================
# run-matrix.sh — the E1 faulted-linearizability MATRIX driver.
# -----------------------------------------------------------------------------
# Drives the unmodified shaded configd-server jar through a matrix of seeded
# fault+workload schedules and checks every recorded history with the trusted
# Porcupine checker. Unlike the 15-second single-fault smoke (run-gate.sh), this
# runs the ADVERSARIAL schedule (overlapping combination nemeses that break
# quorum in bursts) across N=3 and N=5, encryption-off/on and auth-off/on, plus
# clock skew when libfaketime is present.
#
# A cell = (mode, N, posture, seed). Every cell must be LINEARIZABLE. A single
# NON_LINEARIZABLE fails the whole matrix (exit 1) — that is a real correctness
# bug, never a documented-and-shipped result. An INDETERMINATE (no leader, or a
# checker timeout) is retried once, then flagged (exit 2) so it is never a silent
# pass.
#
# Usage:
#   run-matrix.sh --out DIR [options]
# Options (all have defaults; see below):
#   --profile smoke|full        preset seed counts/durations (default: full)
#   --nodes "3 5"               cluster sizes to run
#   --postures "base ..."       any of: base encrypt auth skew  (default: base encrypt auth)
#   --adv-seeds N               adversarial seeds per (N,posture) cell
#   --seq-seeds N               legacy sequential seeds per N (continuity)
#   --adv-dur MS                adversarial run duration
#   --keys K                    keyspace (more keys => smaller per-key histories => tractable)
#   --shard i/n                 run only cell index ≡ i (mod n): parallel sharding on distinct ports
#   --faketime-lib PATH         libfaketime.so.1 (enables the skew posture)
#   --jar PATH                  server jar (default: configd-server/target/...)
# =============================================================================
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

# profile presets
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

# Ports are derived from the GLOBAL cell index (below), so every run - across all parallel
# shards - gets a unique, non-overlapping port range. Two consequences: (1) parallel shards
# never collide, and an orphaned server from a crashed run can never clash with a later run;
# (2) we must NOT pkill between runs (that kills sibling shards' servers by jar path). Each
# HarnessMain tears down its OWN nodes and iptables rules in its finally + shutdown-hook, and
# `timeout` sends SIGTERM (which runs the hook), so a normal or timed-out run self-cleans. A
# single global sweep after ALL shards finish is the orchestrator's job, never a shard's.
CELL=0
RED=0; INDET=0; RUN=0

posture_args() {   # echoes the extra HarnessMain args for a posture
  case "$1" in
    base)    echo "";;
    encrypt) echo "--encrypt-at-rest true";;
    auth)    echo "--auth-token e1-matrix-secret-token";;
    skew)    echo "--clock-skew 3 --faketime-lib $FAKETIME_LIB";;
  esac
}

run_cell() { # mode nodes posture seed dur readpct
  local mode="$1" n="$2" posture="$3" seed="$4" dur="$5" readpct="$6"
  CELL=$((CELL+1))
  # shard selection: 1-based cell index mod shard count
  if [ "$SHARD_N" -gt 1 ] && [ $(( (CELL - 1) % SHARD_N + 1 )) -ne "$SHARD_I" ]; then
    return 0
  fi
  local pargs; pargs="$(posture_args "$posture")"
  if [ "$posture" = "skew" ] && [ -z "$FAKETIME_LIB" ]; then
    printf '%s\t%s\t%s\t%s\t%s\t-\t-\tSKIPPED(no-faketime)\t-\n' "$SHA" "$mode" "$n" "$posture" "$seed" >> "$SUMMARY"
    return 0
  fi
  # Globally-unique port range per cell (24 ports/cell covers N<=5 raft + api): no reuse ever.
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
  # one retry on INDETERMINATE (transient no-leader / checker timeout), never on RED
  if [ "$code" = "2" ]; then
    br=$((br + 12)); ba=$((ba + 12))  # fresh ports for the retry (avoid any orphan on the first range)
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

# Distinct seed range per posture so no two cells share a (seed,nodes) - HarnessMain names
# history/schedule files by (seed,nodes), so a clash would overwrite a captured history.
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
  # SEQUENTIAL continuity cells (base posture only) — same schedule the CI gate uses, at depth
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
