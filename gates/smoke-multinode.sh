#!/usr/bin/env bash
# =============================================================================
# smoke-multinode.sh — Configd CONTROL-PLANE-ONLY multi-node smoke gate
# -----------------------------------------------------------------------------
# Authored by the sre-auditor (audit-session-1) as harness-enablement.
#
# SCOPE: control-plane only (steps 2,3,4,6 of the smoke deliverable).
#   The EDGE propagation step (step 5) is DELIBERATELY OMITTED because it is
#   NOT DEMONSTRABLE: the server exposes NO fan-out/watch/subscribe endpoint
#   (only /health/*, /metrics, /v1/config/{key}), the FanOutBuffer is
#   appended-to but never drained (ConfigdServer.java:360 is its only ref),
#   and EdgeConfigClient has no network transport (applyDelta takes an
#   in-process object). See docs/audit-session-1/smoke-test.md step 5.
#
# WHAT IT PROVES (exits non-zero on ANY failure):
#   - 3-node localhost cluster comes up, all /health/ready == 200
#   - a leader is elected (one node commit-confirms a PUT with 200)
#   - PUT a config -> 200 (RR-004/ADR-0033: 200 == "Committed: seq=S", returned
#     ONLY after quorum commit + apply; the write now BLOCKS until commit or the
#     5s write deadline, so probe/write curls allow for commit-wait latency)
#   - read it back from ALL 3 nodes (default GET) and linearizably from leader
#   - kill -9 the leader, a NEW leader is elected within budget
#   - write again to a survivor, read it back
#
# Budget ~60s. No sudo. Idempotent (cleans up its own ports/dirs on entry+exit).
# Requires: a built shaded jar at the path below (mvn -pl configd-server package).
# =============================================================================
set -u

JAR="${CONFIGD_JAR:-/home/ubuntu/ws-smoke/configd-server/target/configd-server-0.1.0-SNAPSHOT.jar}"
BASE="${SMOKE_BASE:-/tmp/configd-smoke-$$}"
RAFT_BASE=9090           # raft ports 9091,9092,9093
API_BASE=8080            # api  ports 8081,8082,8083
PEERS_ADDR="1=127.0.0.1:9091,2=127.0.0.1:9092,3=127.0.0.1:9093"
ELECT_BUDGET_S=15        # max wait for an election
PIDS=()

fail() { echo "SMOKE FAIL: $*" >&2; cleanup; exit 1; }
pass() { echo "  PASS: $*"; }

cleanup() {
  for pid in "${PIDS[@]:-}"; do kill -9 "$pid" 2>/dev/null; done
  # belt-and-suspenders: any JVM bound to our jar
  pkill -9 -f "$JAR" 2>/dev/null
  rm -rf "$BASE" 2>/dev/null
}
trap cleanup EXIT

[ -f "$JAR" ] || fail "shaded jar not found: $JAR (build with: mvn -pl configd-server -am package)"

# Idempotency: free our ports if a prior run leaked.
pkill -9 -f "$JAR" 2>/dev/null; sleep 1
rm -rf "$BASE"; mkdir -p "$BASE"

api() { echo "127.0.0.1:$((API_BASE + $1))"; }

# ---- step 2: launch 3 nodes ------------------------------------------------
echo "[step 2] launching 3-node cluster under $BASE"
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

# wait for all 3 readiness endpoints to be 200 (leader elected => ready true)
ready=0
for i in $(seq 1 $((ELECT_BUDGET_S * 2))); do
  ok=0
  for k in 1 2 3; do
    code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 1 "http://$(api $k)/health/ready" 2>/dev/null)
    [ "$code" = "200" ] && ok=$((ok + 1))
  done
  if [ "$ok" -eq 3 ]; then ready=1; break; fi
  sleep 0.5
done
[ "$ready" -eq 1 ] || fail "not all 3 nodes became ready within ${ELECT_BUDGET_S}s"
pass "all 3 nodes /health/ready == 200 (leader elected)"

# identify leader: the node that COMMIT-CONFIRMS a probe PUT with 200.
# RR-004/ADR-0033: 200 now means the probe entry actually quorum-committed (a real
# side-effecting __leader_probe__ write), and the PUT BLOCKS until commit or the 5s
# write deadline. --max-time must exceed that deadline so a probe to the leader is
# not cut off mid-commit and misread as "not leader". A follower returns 503
# (NotLeader) promptly, so the loop still moves on quickly.
find_leader() {
  for k in 1 2 3; do
    [ -n "${DEAD:-}" ] && [ "$k" = "$DEAD" ] && continue
    code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 8 -X PUT -d probe \
           "http://$(api $k)/v1/config/__leader_probe__" 2>/dev/null)
    [ "$code" = "200" ] && { echo "$k"; return 0; }
  done
  return 1
}
LEADER=$(find_leader) || fail "no node accepted a write (no leader)"
pass "leader elected: node $LEADER (api $(api "$LEADER"))"

# ---- step 3: write a config (RR-004/ADR-0033: 200 == COMMITTED) -------------
echo "[step 3] PUT smoke/k1 to leader"
body=$(curl -s --max-time 8 -w "\n%{http_code}" -X PUT -d "smoke-value-1" \
       "http://$(api "$LEADER")/v1/config/smoke/k1")
code=$(echo "$body" | tail -1)
[ "$code" = "200" ] || fail "PUT returned $code (expected 200)"
echo "  PUT response body: $(echo "$body" | head -1)  (RR-004: 200 == quorum-committed; body 'Committed: seq=S')"
pass "write committed (200)"

# ---- step 4: read back from all 3 + linearizable from leader ---------------
# Followers serve their LOCAL applied state, which lags the leader's commit by
# a replication round-trip; poll each node up to ~4s rather than a fixed sleep.
echo "[step 4] read back smoke/k1"
for k in 1 2 3; do
  v=""
  for i in $(seq 1 20); do
    v=$(curl -s --max-time 3 "http://$(api $k)/v1/config/smoke/k1")
    [ "$v" = "smoke-value-1" ] && break
    sleep 0.2
  done
  [ "$v" = "smoke-value-1" ] || fail "node $k default GET returned '$v' (expected smoke-value-1)"
done
pass "default GET == smoke-value-1 on all 3 nodes (committed + replicated)"
# Linearizable reads use ReadIndex with a 150ms leadership-confirmation budget
# and can return 503 transiently (the linz harness retries for the same reason).
# Re-resolve the leader each attempt in case leadership moved.
lin=""
for i in $(seq 1 15); do
  L=$(find_leader) || { sleep 0.3; continue; }
  lin=$(curl -s --max-time 3 "http://$(api "$L")/v1/config/smoke/k1?consistency=linearizable")
  [ "$lin" = "smoke-value-1" ] && break
  sleep 0.3
done
[ "$lin" = "smoke-value-1" ] || fail "linearizable GET on leader returned '$lin' after retries"
pass "linearizable GET on leader == smoke-value-1"

# ---- step 6: kill leader, observe re-election, write again -----------------
echo "[step 6] kill -9 leader (node $LEADER) and re-elect"
kill -9 "${PIDS[$((LEADER - 1))]}" 2>/dev/null
DEAD="$LEADER"
NEWLEADER=""
for i in $(seq 1 $((ELECT_BUDGET_S * 2))); do
  if NL=$(find_leader); then NEWLEADER="$NL"; break; fi
  sleep 0.5
done
[ -n "$NEWLEADER" ] || fail "no new leader elected within ${ELECT_BUDGET_S}s after kill"
pass "new leader elected: node $NEWLEADER"

echo "[step 6] write smoke/k2 to new leader, read back"
# RR-004/ADR-0033: the PUT blocks until quorum commit or the 5s deadline, so allow
# for commit-wait latency (--max-time 8) — a 200 here is a real committed write.
code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 8 -X PUT -d "smoke-value-2" \
       "http://$(api "$NEWLEADER")/v1/config/smoke/k2")
[ "$code" = "200" ] || fail "post-failover PUT returned $code"
# poll the new leader for the value (commit + apply)
got=""
for i in $(seq 1 20); do
  got=$(curl -s --max-time 3 "http://$(api "$NEWLEADER")/v1/config/smoke/k2")
  [ "$got" = "smoke-value-2" ] && break
  sleep 0.2
done
[ "$got" = "smoke-value-2" ] || fail "post-failover read-back returned '$got'"
pass "post-failover write read back == smoke-value-2"
# pre-failover write must still be present (no committed data lost)
old=$(curl -s --max-time 3 "http://$(api "$NEWLEADER")/v1/config/smoke/k1")
[ "$old" = "smoke-value-1" ] || fail "pre-failover write lost after failover (got '$old')"
pass "pre-failover write survived failover"

echo "SMOKE PASS: control-plane multi-node smoke succeeded (edge step intentionally excluded — not demonstrable)"
exit 0
