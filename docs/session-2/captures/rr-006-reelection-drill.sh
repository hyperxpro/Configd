#!/usr/bin/env bash
# =============================================================================
# rr-006-reelection-drill.sh — RR-006 live re-election timing drill (A5/S2)
# -----------------------------------------------------------------------------
# Measures kill-leader -> new-leader-accepts-write latency on a 3-node localhost
# cluster, to prove the RR-006 fix drops re-election from the pre-fix ~2.3s
# (ms-as-ticks: 1.5-3.0s election window at 10ms/tick) to well under 1s
# (15-30 ticks x 10ms = 150-300ms election window).
#
# Self-contained: launches its own cluster, kills its own PIDs, no pkill-by-jar
# (that footgun can kill the wrapper itself), no iptables. Budget ~60s.
# Requires CONFIGD_JAR to point at a freshly `clean package`d shaded jar of THIS
# repo (one that actually contains the fix — verify with javap first).
# =============================================================================
set -u

JAR="${CONFIGD_JAR:?set CONFIGD_JAR to a freshly clean-packaged shaded jar of this repo}"
BASE="${DRILL_BASE:-/tmp/rr006-drill-$$}"
RAFT_BASE=9290           # raft ports 9291,9292,9293 (offset to avoid smoke clash)
API_BASE=8280            # api  ports 8281,8282,8283
PEERS_ADDR="1=127.0.0.1:9291,2=127.0.0.1:9292,3=127.0.0.1:9293"
ELECT_BUDGET_S=15
PIDS=()

fail() { echo "DRILL FAIL: $*" >&2; cleanup; exit 1; }
cleanup() { for pid in "${PIDS[@]:-}"; do kill -9 "$pid" 2>/dev/null; done; rm -rf "$BASE" 2>/dev/null; }
trap cleanup EXIT

[ -f "$JAR" ] || fail "shaded jar not found: $JAR"
rm -rf "$BASE"; mkdir -p "$BASE"
api() { echo "127.0.0.1:$((API_BASE + $1))"; }

echo "[launch] 3-node cluster under $BASE (jar=$JAR)"
for k in 1 2 3; do
  peers=$(echo "1 2 3" | tr ' ' '\n' | grep -v "^$k$" | paste -sd,)
  dd="$BASE/n$k"; mkdir -p "$dd"
  java -Xmx256m --enable-preview -jar "$JAR" \
    --node-id "$k" --data-dir "$dd" --peers "$peers" \
    --bind-address 127.0.0.1 --bind-port $((RAFT_BASE + k)) \
    --api-port $((API_BASE + k)) --peer-addresses "$PEERS_ADDR" \
    > "$BASE/n$k.log" 2>&1 &
  PIDS+=("$!")
done

# wait until all ready
ready=0
for i in $(seq 1 $((ELECT_BUDGET_S * 2))); do
  ok=0
  for k in 1 2 3; do
    code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 1 "http://$(api $k)/health/ready" 2>/dev/null)
    [ "$code" = "200" ] && ok=$((ok + 1))
  done
  [ "$ok" -eq 3 ] && { ready=1; break; }
  sleep 0.5
done
[ "$ready" -eq 1 ] || fail "cluster not ready in ${ELECT_BUDGET_S}s"

# identify leader via a commit-confirmed probe PUT
find_leader() {
  for k in 1 2 3; do
    [ -n "${DEAD:-}" ] && [ "$k" = "$DEAD" ] && continue
    code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 8 -X PUT -d probe \
           "http://$(api $k)/v1/config/__probe__" 2>/dev/null)
    [ "$code" = "200" ] && { echo "$k"; return 0; }
  done
  return 1
}
LEADER=$(find_leader) || fail "no leader"
echo "[leader] node $LEADER"

# ---- the measurement ----
# Kill the leader, then poll survivors with a SHORT probe timeout at high
# frequency. The first survivor that commit-confirms a write is the new leader;
# the elapsed wall-clock from kill to that 200 is the re-election time. A short
# --max-time on the probe keeps the poll granularity fine (a follower 503s fast).
echo "[kill] kill -9 leader node $LEADER"
T_KILL=$(date +%s.%N)
kill -9 "${PIDS[$((LEADER - 1))]}" 2>/dev/null
DEAD="$LEADER"

NEWLEADER=""
T_NEW=""
for i in $(seq 1 600); do          # up to ~ELECT_BUDGET via tight loop
  for k in 1 2 3; do
    [ "$k" = "$DEAD" ] && continue
    code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 1 -X PUT -d probe2 \
           "http://$(api $k)/v1/config/__probe2__" 2>/dev/null)
    if [ "$code" = "200" ]; then
      T_NEW=$(date +%s.%N); NEWLEADER="$k"; break
    fi
  done
  [ -n "$NEWLEADER" ] && break
done
[ -n "$NEWLEADER" ] || fail "no new leader within budget"

REELECT=$(awk "BEGIN{printf \"%.3f\", $T_NEW - $T_KILL}")
echo "[result] new leader = node $NEWLEADER"
echo "[result] re-election (kill -> first commit-confirmed write) = ${REELECT}s"
echo "[result] PRE-FIX baseline (smoke-test.md:265) ~= 2.3s; documented election window 150-300ms"
exit 0
