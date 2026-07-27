#!/usr/bin/env bash
# soak-faults.sh — companion fault injector for perf/soak.sh.
# Faults rotated: (1) leader kill + rejoin (election + WAL-replay), (2) follower restart (WAL-replay),
# (3) clock-skew via libfaketime (tick-count-driven Raft, so skew is timestamp/staleness fault not election).
# Faults spaced (one per FAULT_INTERVAL_SEC) so system settles, drift observable between events.
# GC-log caveat: relaunched nodes NOT relaunched with -Xlog:gc (would overwrite pre-kill log),
# so GC-cumulative stops after restart; leak signals (jstat heap-used, RSS, FD, threads) unaffected.

set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="${CONFIGD_JAR:-$(ls "$ROOT"/configd-server/target/configd-server-*.jar 2>/dev/null | grep -v original- | head -1)}"
BASE="${SOAK_BASE:?SOAK_BASE must be set to the running soak data root}"
SIGNKEY="${SOAK_SIGNKEY:-$ROOT/deploy/compose/secrets/signing-key.bin}"
HEAP="${SOAK_HEAP:--Xmx1g -Xms1g}"
GCFLAGS="${SOAK_GC:--XX:+UseZGC}"
# Same OOM/leak capture on a relaunched node as soak.sh gives the initial ones,
# so a leak that only manifests on a restarted node still produces a heap dump.
JVM_EXTRA="${SOAK_JVM_EXTRA:--XX:+HeapDumpOnOutOfMemoryError}"
RAFT_BASE=9290; API_BASE=8280
PEERS_ADDR="1=127.0.0.1:9291,2=127.0.0.1:9292,3=127.0.0.1:9293"

DUR="${FAULT_DURATION_SEC:-258600}"        # stop a little before the soak ends
INTERVAL="${FAULT_INTERVAL_SEC:-21600}"    # one fault every 6h by default
SETTLE="${FAULT_SETTLE_SEC:-45}"           # gap between kill and relaunch
SKEW_SEC="${FAULT_SKEW_SEC:-8}"
# Default the schedule log to the PARENT of SOAK_BASE, not inside it: soak.sh's
# cleanup trap `rm -rf`s SOAK_BASE on exit, which would take the log with it.
LOG="${FAULT_LOG:-${BASE%/*}/fault-schedule.log}"

api()      { echo "127.0.0.1:$((API_BASE + $1))"; }
# Prefer comm==java over clock-skew wrapper (whose cmdline also has data-dir).
node_pid() {
  local k="$1" p
  for p in $(pgrep -f -- "--data-dir $BASE/n$k " 2>/dev/null); do
    [ "$(cat /proc/$p/comm 2>/dev/null)" = java ] && { echo "$p"; return; }
  done
}
FAKETIME_LIB=/usr/lib/x86_64-linux-gnu/faketime/libfaketimeMT.so.1
log()      { echo "$(date -u +%FT%TZ) $*" | tee -a "$LOG"; }
ready()    { local c; c=$(curl -s -o /dev/null -w '%{http_code}' --max-time 1 "http://$(api $1)/health/ready" 2>/dev/null); [ "$c" = 200 ]; }
wait_ready() { local k=$1 to=${2:-120} i; for i in $(seq 1 $((to * 2))); do ready "$k" && return 0; sleep 0.5; done; return 1; }
find_leader() {
  local k code _
  for _ in $(seq 1 60); do
    for k in 1 2 3; do
      code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 -X PUT -d p \
        "http://$(api $k)/v1/config/soak/__leader_probe__" 2>/dev/null)
      [ "$code" = 200 ] && { echo "$k"; return 0; }
    done
    sleep 0.5
  done
  return 1
}
launch_node() { # launch_node <k> [faketime_offset e.g. +8s]
  local k="$1" ft="${2:-}" peers dd pre=""
  peers=$(echo "1 2 3" | tr ' ' '\n' | grep -v "^$k$" | paste -sd,)
  dd="$BASE/n$k"; mkdir -p "$dd"
  # LD_PRELOAD on java process itself (not wrapper, so trend sampler sees exactly one process per node).
  [ -n "$ft" ] && pre="env FAKETIME=$ft FAKETIME_NO_CACHE=1 LD_PRELOAD=$FAKETIME_LIB"
  $pre java $GCFLAGS $HEAP $JVM_EXTRA --enable-preview \
    -jar "$JAR" --node-id "$k" --data-dir "$dd" --peers "$peers" \
    --signing-key-file "$SIGNKEY" --bind-address 127.0.0.1 \
    --bind-port $((RAFT_BASE + k)) --api-port $((API_BASE + k)) \
    --peer-addresses "$PEERS_ADDR" >> "$BASE/n$k.log" 2>&1 &
}

pick_follower() {
  local L="$1" k
  for k in 1 2 3; do
    if [ "$k" != "$L" ] && [ -n "$(node_pid $k)" ]; then echo "$k"; return 0; fi
  done
  return 1
}

fault_leader_kill() {
  local L p NL
  if ! L=$(find_leader); then log "FAULT #$n leader-kill SKIP (no leader)"; return; fi
  p=$(node_pid "$L"); log "FAULT #$n leader-kill node=$L pid=$p"
  kill -9 "$p" 2>/dev/null; sleep "$SETTLE"
  NL=$(find_leader); log "  post-kill new-leader=$NL; relaunch node $L (WAL-replay rejoin)"
  launch_node "$L"
  if wait_ready "$L" 120; then log "  node $L rejoined ready"; else log "  WARN node $L not ready in 120s"; fi
}

fault_follower_restart() {
  local L F p
  if ! L=$(find_leader); then log "FAULT #$n follower-restart SKIP (no leader)"; return; fi
  F=$(pick_follower "$L") || { log "FAULT #$n follower-restart SKIP (no follower)"; return; }
  p=$(node_pid "$F"); log "FAULT #$n follower-restart node=$F pid=$p (leader=$L)"
  kill -9 "$p" 2>/dev/null; sleep "$SETTLE"
  launch_node "$F"
  if wait_ready "$F" 120; then log "  node $F rejoined ready"; else log "  WARN node $F not ready in 120s"; fi
}

fault_clock_skew() {
  local L F p
  if ! command -v faketime >/dev/null 2>&1; then log "FAULT #$n clock-skew SKIP (libfaketime not installed)"; return; fi
  if ! L=$(find_leader); then log "FAULT #$n clock-skew SKIP (no leader)"; return; fi
  F=$(pick_follower "$L") || { log "FAULT #$n clock-skew SKIP (no follower)"; return; }
  p=$(node_pid "$F"); log "FAULT #$n clock-skew node=$F pid=$p offset=+${SKEW_SEC}s (tick-driven raft; timestamp/staleness fault)"
  kill -9 "$p" 2>/dev/null; sleep "$SETTLE"
  launch_node "$F" "+${SKEW_SEC}s"
  if wait_ready "$F" 120; then log "  node $F rejoined ready (skewed +${SKEW_SEC}s; cleared on its next restart)"; else log "  WARN node $F not ready in 120s"; fi
}

log "FAULT-INJECTOR start dur=${DUR}s interval=${INTERVAL}s settle=${SETTLE}s base=$BASE"
t0=$(date +%s); deadline=$((t0 + DUR)); n=0
sleep "$INTERVAL"   # clean baseline window before the first fault
while [ "$(date +%s)" -lt "$deadline" ]; do
  n=$((n + 1)); mode=$(( n % 3 ))
  if   [ "$mode" = 1 ]; then fault_leader_kill
  elif [ "$mode" = 2 ]; then fault_follower_restart
  else                       fault_clock_skew
  fi
  sleep 5
  h=0; for k in 1 2 3; do ready "$k" && h=$((h + 1)); done
  log "  cluster health after fault #$n: $h/3 ready"
  sleep "$INTERVAL"
done
log "FAULT-INJECTOR done after $n faults"
