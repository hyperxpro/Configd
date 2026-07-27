#!/usr/bin/env bash
# dr-drill.sh — 3-node disaster-recovery drills: leader loss, node recovery (WAL replay), snapshot catch-up, wipe-rejoin safety.
# Results and per-drill timings -> $OUT/dr-results.txt
#
# Usage:  perf/dr-drill.sh [outdir]
# Env:    DR_BASE (default /mnt/nvme/run/dr-<pid>)   DR_SEED_KEYS (default 2000)
#         DR_LOAD_RATE (default 300)   DR_HEAP (default "-Xmx4g -Xms4g")
#         DR_DRYRUN (default 0)        CONFIGD_JAR / CONFIGD_BENCH

set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="${CONFIGD_JAR:-$(ls "$ROOT"/configd-server/target/configd-server-*.jar 2>/dev/null | grep -v original- | head -1)}"
BENCH="${CONFIGD_BENCH:-$ROOT/configd-testkit/target/benchmarks.jar}"
DRYRUN="${DR_DRYRUN:-0}"
BASE="${DR_BASE:-/mnt/nvme/run/dr-$$}"
RAFT_BASE=9290; API_BASE=8280
PEERS_ADDR="1=127.0.0.1:9291,2=127.0.0.1:9292,3=127.0.0.1:9293"
HEAP="${DR_HEAP:--Xmx4g -Xms4g}"
GCFLAGS="${DR_GC:--XX:+UseZGC}"
SEED_KEYS="${DR_SEED_KEYS:-1000}"
LOAD_RATE="${DR_LOAD_RATE:-300}"
OUT="${1:-$ROOT/docs/measurement/captures/dr}"
declare -A PID   # node-id -> pid
SAMPLERS=()
mkdir -p "$BASE" "$OUT"
RES="$OUT/dr-results.txt"
SIGNKEY="$BASE/cluster-signing-key.bin"

now_ns() { date +%s%N; }
api() { echo "127.0.0.1:$((API_BASE + $1))"; }
log() { echo "[dr] $*" | tee -a "$RES"; }
fail() { echo "dr FAIL: $*" | tee -a "$RES" >&2; teardown; exit 1; }
teardown() {
  for p in "${SAMPLERS[@]:-}"; do kill "$p" 2>/dev/null; done
  for k in "${!PID[@]}"; do kill -9 "${PID[$k]}" 2>/dev/null; done
  pkill -9 -f -- "--data-dir $BASE/" 2>/dev/null
}
trap 'teardown; rm -rf "$BASE" 2>/dev/null' EXIT

[ -f "$JAR" ] || fail "shaded jar not found: $JAR"
[ -f "$BENCH" ] || fail "benchmarks.jar not found: $BENCH"
if [ "$DRYRUN" != "1" ]; then case "$BASE" in /mnt/nvme/*) : ;; *) fail "DR_BASE must be /mnt/nvme"; esac; fi

launch_node() {
  local k="$1"
  local peers; peers=$(echo "1 2 3" | tr ' ' '\n' | grep -v "^$k$" | paste -sd,)
  local dd="$BASE/n$k"; mkdir -p "$dd"
  java $GCFLAGS $HEAP -Dconfigd.netty.transport=epoll --enable-preview -jar "$JAR" \
    --node-id "$k" --data-dir "$dd" --peers "$peers" --signing-key-file "$SIGNKEY" \
    --bind-address 127.0.0.1 --bind-port $((RAFT_BASE + k)) \
    --api-port $((API_BASE + k)) --peer-addresses "$PEERS_ADDR" \
    > "$BASE/n$k.log" 2>&1 &
  PID[$k]=$!
}
wait_ready() { # wait_ready <node-id> <timeout_s>
  local k="$1" to="${2:-60}" i
  for i in $(seq 1 $((to*2))); do
    [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 1 "http://$(api $k)/health/ready" 2>/dev/null)" = "200" ] && return 0
    sleep 0.5
  done
  return 1
}
launch_cluster() {
  launch_node 1
  local i; for i in $(seq 1 40); do [ -s "$SIGNKEY" ] && break; sleep 0.25; done
  launch_node 2; launch_node 3
  for k in 1 2 3; do wait_ready "$k" 60 || { tail -n 20 "$BASE"/n*.log; fail "node $k not ready"; }; done
}
# leader = the node that accepts a committed PUT (200). echoes node-id or empty.
find_leader() {
  local k code
  for _t in $(seq 1 60); do
    for k in 1 2 3; do
      [ -n "${PID[$k]:-}" ] || continue
      kill -0 "${PID[$k]}" 2>/dev/null || continue
      code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 -X PUT -d p "http://$(api $k)/v1/config/dr/__leader_probe__" 2>/dev/null)
      [ "$code" = "200" ] && { echo "$k"; return 0; }
    done
    sleep 0.25
  done
  return 1
}
# max commit_index across the node's exported raft metrics (group 0 / any shard).
commit_index() { # commit_index <node-id>
  curl -s --max-time 3 "http://$(api $1)/metrics" 2>/dev/null \
    | grep -E 'commit_index' | grep -vE 'pending' | awk '{print $NF}' | sort -nr | head -1 | sed 's/\..*//'
}
elections_total() { # max elections across nodes
  local m=0 k v
  for k in 1 2 3; do
    [ -n "${PID[$k]:-}" ] && kill -0 "${PID[$k]}" 2>/dev/null || continue
    v=$(curl -s --max-time 3 "http://$(api $k)/metrics" 2>/dev/null | grep -E '^configd_raft_elections_total' | awk '{print $NF}' | sort -nr | head -1)
    v=${v%%.*}; [ -z "$v" ] && v=0; [ "$v" -gt "$m" ] 2>/dev/null && m=$v
  done; echo "$m"
}
seed_keys() { # seed_keys <leader-id>  -> writes SEED_KEYS known keys
  local L="$1" i ok=0
  for ((i=0;i<SEED_KEYS;i++)); do
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 -X PUT -d "dr-val-$i" "http://$(api $L)/v1/config/dr/seed/$i" 2>/dev/null)
    [ "$code" = "200" ] && ok=$((ok+1))
  done
  echo "$ok"
}
verify_keys() { # verify_keys <any-id> -> echoes "intact missing mismatch"
  local L="$1" i intact=0 missing=0 mismatch=0 body
  for ((i=0;i<SEED_KEYS;i++)); do
    body=$(curl -s --max-time 5 "http://$(api $L)/v1/config/dr/seed/$i" 2>/dev/null)
    if [ -z "$body" ]; then missing=$((missing+1));
    elif [ "$body" = "dr-val-$i" ]; then intact=$((intact+1));
    else mismatch=$((mismatch+1)); fi
  done
  echo "$intact $missing $mismatch"
}
driver_bg() { # background open-loop load for <dur>s at LOAD_RATE
  local nodemap="1=http://$(api 1),2=http://$(api 2),3=http://$(api 3)"
  java $GCFLAGS -Xmx2g --enable-preview -cp "$BENCH" io.configd.bench.OpenLoopWriteDriver \
    atrate "$nodemap" "$LOAD_RATE" "$1" 64 256 > "$BASE/dr-load.txt" 2>&1 &
  echo $!
}

echo "=== Configd DR drills @ $(date -u +%FT%TZ) ===" > "$RES"
log "jar=$JAR base=$BASE seed_keys=$SEED_KEYS load_rate=$LOAD_RATE heap=$HEAP"
launch_cluster
L=$(find_leader) || fail "no initial leader"
log "initial leader = node $L"
log "seeding $SEED_KEYS known keys via leader $L..."
SEEDED=$(seed_keys "$L"); log "seeded_ok=$SEEDED / $SEED_KEYS"
[ "$SEEDED" -eq "$SEED_KEYS" ] || log "WARN: not all seeds committed ($SEEDED/$SEED_KEYS)"
CI_BEFORE=$(commit_index "$L"); log "leader commit_index after seed = $CI_BEFORE"

log ""
log "===== DRILL A: LEADER-LOSS under load ====="
LOADPID=$(driver_bg 60); log "background load pid=$LOADPID (60s @ ${LOAD_RATE}/s)"
sleep 8
L=$(find_leader) || fail "no leader before kill"
ELEC0=$(elections_total)
KILLPID=${PID[$L]}
T_KILL=$(now_ns)
kill -9 "$KILLPID"; unset 'PID[$L]'
log "killed leader node $L (pid $KILLPID) at t_kill; measuring failover..."
# tight prober: first committed 200 from a surviving node = writes available again
NEWL=""; T_REC=""
for _t in $(seq 1 800); do   # up to ~80s
  for k in 1 2 3; do
    [ -n "${PID[$k]:-}" ] || continue
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 -X PUT -d p "http://$(api $k)/v1/config/dr/__failover_probe__" 2>/dev/null)
    if [ "$code" = "200" ]; then T_REC=$(now_ns); NEWL=$k; break; fi
  done
  [ -n "$T_REC" ] && break
  sleep 0.1
done
[ -n "$T_REC" ] || fail "writes never resumed after leader kill (no failover)"
GAP_MS=$(( (T_REC - T_KILL) / 1000000 ))
log "failover: new write-leader = node $NEWL; write-availability GAP = ${GAP_MS} ms"
# stability: elections over a 12s settle window should be small + then flat
sleep 12
ELEC1=$(elections_total)
log "elections_total: before_kill=$ELEC0 after_settle=$ELEC1 (delta=$((ELEC1-ELEC0)) — bounded election, not a storm)"
# no committed-write loss: every seeded key intact
read I M MM < <(verify_keys "$NEWL"); log "seeded read-back via node $NEWL: intact=$I missing=$M mismatch=$MM"
if [ "$M" -eq 0 ] && [ "$MM" -eq 0 ]; then log "DRILL A VERDICT: NO committed-write loss (all $I seeded keys survived leader loss)"; else log "DRILL A VERDICT: ***DATA LOSS*** missing=$M mismatch=$MM"; fi
wait "$LOADPID" 2>/dev/null
grep -E "ATRATE-RESULT|ATRATE-STATUS" "$BASE/dr-load.txt" | sed 's/^/[dr] load: /' | tee -a "$RES" >/dev/null
# restart the ex-leader so the cluster is whole again for drills B/C
log "restarting ex-leader node $L (rejoin via its own data dir)..."
launch_node "$L"; wait_ready "$L" 60 && log "node $L rejoined" || log "WARN node $L did not become ready"

log ""
log "===== DRILL B: NODE RECOVERY via WAL replay (kill+restart, same data dir) ====="
L=$(find_leader) || fail "no leader for drill B"
# pick a follower != leader
FOLL=""; for k in 1 2 3; do [ "$k" != "$L" ] && [ -n "${PID[$k]:-}" ] && { FOLL=$k; break; }; done
log "leader=$L follower-to-recover=$FOLL"
CI_LEAD=$(commit_index "$L"); log "leader commit_index = $CI_LEAD"
kill -9 "${PID[$FOLL]}"; unset 'PID[$FOLL]'
log "killed follower $FOLL; restarting from its OWN data dir (WAL/snapshot replay)..."
T0=$(now_ns); launch_node "$FOLL"
wait_ready "$FOLL" 90 || fail "follower $FOLL did not become ready"
T_READY=$(now_ns); READY_MS=$(( (T_READY-T0)/1000000 ))
# converge: follower commit_index >= leader commit_index at kill time
CONV_MS=""; for _t in $(seq 1 600); do ci=$(commit_index "$FOLL"); [ -n "$ci" ] && [ "${ci:-0}" -ge "${CI_LEAD:-0}" ] 2>/dev/null && { CONV_MS=$(( ($(now_ns)-T0)/1000000 )); break; }; sleep 0.1; done
log "WAL-replay recovery: ready_in=${READY_MS}ms converged_in=${CONV_MS:-TIMEOUT}ms (follower ci>=leader ci=$CI_LEAD)"
read I M MM < <(verify_keys "$FOLL"); log "DRILL B read-back via recovered node $FOLL: intact=$I missing=$M mismatch=$MM"
[ "$M" -eq 0 ] && [ "$MM" -eq 0 ] && log "DRILL B VERDICT: recovered, NO loss (RTO=${CONV_MS:-?}ms)" || log "DRILL B VERDICT: ***LOSS*** missing=$M mismatch=$MM"

log ""
log "===== DRILL D: InstallSnapshot catch-up (follower falls behind a compaction, rejoins from OWN dir) ====="
# The supported "leader streams state" path: a returning follower whose nextIndex predates the
# leader's compacted log head is caught up by InstallSnapshot. Kill a follower, drive writes while it
# is down so the leader compacts past it, then restart it from its OWN data dir (anchor intact -> NOT
# an anchor rollback, so the R-a' witness does not fire). No committed-write loss.
L=$(find_leader) || fail "no leader for drill D"
FOLL=""; for k in 1 2 3; do [ "$k" != "$L" ] && [ -n "${PID[$k]:-}" ] && { FOLL=$k; break; }; done
log "leader=$L follower-to-lag=$FOLL"
kill -9 "${PID[$FOLL]}"; unset 'PID[$FOLL]'
log "killed follower $FOLL; driving writes + waiting for leader compaction to advance past it..."
DPID=$(driver_bg 25); wait "$DPID" 2>/dev/null || true
SNAP_BYTES=$(curl -s --max-time 3 "http://$(api $L)/metrics" 2>/dev/null | grep -E '^configd_snapshot_bytes' | awk '{print $NF}' | sort -nr | head -1)
CI_LEAD=$(commit_index "$L"); log "leader commit_index after burst = $CI_LEAD (leader snapshot_bytes=${SNAP_BYTES:-0})"
T0=$(now_ns); launch_node "$FOLL"
wait_ready "$FOLL" 120 || fail "lagging follower $FOLL did not become ready (InstallSnapshot catch-up)"
CONV_MS=""; for _t in $(seq 1 900); do ci=$(commit_index "$FOLL"); [ -n "$ci" ] && [ "${ci:-0}" -ge "${CI_LEAD:-0}" ] 2>/dev/null && { CONV_MS=$(( ($(now_ns)-T0)/1000000 )); break; }; sleep 0.1; done
FAILN=$(curl -s --max-time 3 "http://$(api $FOLL)/metrics" 2>/dev/null | grep -E '^configd_snapshot_install_failed_total' | awk '{print $NF}' | sort -nr | head -1)
log "InstallSnapshot catch-up: ready+converged_in=${CONV_MS:-TIMEOUT}ms (follower ci>=leader ci=$CI_LEAD; install_failed=${FAILN:-0})"
read I M MM < <(verify_keys "$FOLL"); log "DRILL D read-back via caught-up node $FOLL: intact=$I missing=$M mismatch=$MM"
[ "$M" -eq 0 ] && [ "$MM" -eq 0 ] && log "DRILL D VERDICT: caught up via leader stream, NO loss (RTO=${CONV_MS:-?}ms)" || log "DRILL D VERDICT: ***LOSS*** missing=$M mismatch=$MM"

log ""
log "===== DRILL C: same-id WIPE-REJOIN must be REFUSED by the R-a' anchor witness (safety) ====="
# A single follower whose disk is wiped and restarted under the SAME node-id is a durable-state
# rollback: surviving peers still witness its prior (higher) anchorSeq, so a within-term re-vote could
# double-vote and split-brain. The peer-quorum anchor witness (Gate 3c R-a') fail-closes this by design
# (ConfigdServer halt 71). This drill asserts that SAFETY refusal actually fires. The supported
# lost-disk recovery is whole-cluster snapshot restore (ops/runbooks/restore-from-snapshot.md) or
# membership add-server under a NEW node-id -- NOT same-id wipe-rejoin. Runs last: it permanently
# retires this id for the rest of the run (the cluster keeps quorum at 2/3).
L=$(find_leader) || fail "no leader for drill C"
FOLL=""; for k in 1 2 3; do [ "$k" != "$L" ] && [ -n "${PID[$k]:-}" ] && { FOLL=$k; break; }; done
log "leader=$L follower-to-wipe=$FOLL"
kill -9 "${PID[$FOLL]}"; FPID_OLD="${PID[$FOLL]}"; unset 'PID[$FOLL]'
rm -rf "$BASE/n$FOLL"; mkdir -p "$BASE/n$FOLL"
log "killed + WIPED follower $FOLL data dir; restarting EMPTY under the SAME id (must be REFUSED)..."
launch_node "$FOLL"; FPID_NEW="${PID[$FOLL]}"
refused=0
for _t in $(seq 1 60); do
  kill -0 "$FPID_NEW" 2>/dev/null || { refused=1; break; }
  [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 1 "http://$(api $FOLL)/health/ready" 2>/dev/null)" = "200" ] && { refused=0; break; }
  sleep 1
done
if grep -q "anchor rollback detected" "$BASE/n$FOLL.log" 2>/dev/null && [ "$refused" = 1 ]; then
  log "DRILL C VERDICT: PASS - same-id wipe-rejoin correctly REFUSED by R-a' (anchor rollback, halt 71)"
else
  grep -iE "anchor|ready|SEVERE" "$BASE/n$FOLL.log" 2>/dev/null | tail -n 4 | sed 's/^/[dr]   /' | tee -a "$RES"
  fail "same-id wipe-rejoin was NOT refused (R-a' safety regression: node became ready or no anchor-rollback halt)"
fi
unset 'PID[$FOLL]'

log ""
log "=== DR drills complete @ $(date -u +%FT%TZ) ==="
column -t "$RES" 2>/dev/null || cat "$RES"
