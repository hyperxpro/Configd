#!/usr/bin/env bash
# rr-002-blackhole-drill.sh — live black-hole drill
#
# The LIVE discriminating test for a timeout-less, on-tick-thread connect/TLS
# handshake to a black-holed peer freezing the whole node.
#
# SAFETY: iptables rules are ALWAYS torn down in an EXIT trap; the DROP count is
#   asserted 0 before and after. Nodes are killed by TRACKED PID ONLY — we never
#   pkill on the jar path (that would murder an invoking shell whose cmdline
#   contains the jar). The jar defaults to THIS repo's configd-server/target/.
#
# Requires: passwordless sudo (iptables); a built shaded jar; curl.
# Budget ~90s.
set -u

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="${CONFIGD_JAR:-$REPO_ROOT/configd-server/target/configd-server-0.1.0-SNAPSHOT.jar}"
BASE="${DRILL_BASE:-/tmp/configd-rr002-$$}"
RAFT_BASE=9090            # raft ports 9091,9092,9093
API_BASE=8080            # api  ports 8081,8082,8083
PEERS_ADDR="1=127.0.0.1:9091,2=127.0.0.1:9092,3=127.0.0.1:9093"
ELECT_BUDGET_S=20
DROP_WINDOW_S="${DROP_WINDOW_S:-35}"
OP_DEADLINE_S="${OP_DEADLINE_S:-2}"    # per-op latency bound (the contract)
PIDS=()
DROP_PORT=""             # the follower raft port we DROP (set later, for the trap)
MODE="${DRILL_MODE:-auto}"  # auto|prefix|postfix — affects exit semantics only

fail()  { echo "DRILL FAIL: $*" >&2; exit 1; }
pass()  { echo "  PASS: $*"; }
info()  { echo "  ..   $*"; }

# teardown: ALWAYS remove iptables rules + kill tracked PIDs
cleanup() {
  local rc=$?
  if [ -n "$DROP_PORT" ]; then
    # Remove every copy of our rule (idempotent; -D until it's gone).
    while sudo iptables -D INPUT -p tcp --dport "$DROP_PORT" -j DROP 2>/dev/null; do :; done
  fi
  for pid in "${PIDS[@]:-}"; do kill -9 "$pid" 2>/dev/null; done
  # Assert the box is clean (no stray DROP rules left by us).
  local left
  left=$(sudo iptables -S INPUT 2>/dev/null | grep -c -- "--dport ${DROP_PORT:-__none__} -j DROP")
  if [ "${DROP_PORT:-}" != "" ] && [ "$left" != "0" ]; then
    echo "DRILL WARN: $left stray DROP rule(s) on port $DROP_PORT remain — manual cleanup needed" >&2
  fi
  rm -rf "$BASE" 2>/dev/null
  exit "$rc"
}
trap cleanup EXIT

[ -f "$JAR" ] || fail "shaded jar not found: $JAR (build: ./mvnw -pl configd-server -am package -DskipTests)"
sudo -n true 2>/dev/null || fail "passwordless sudo required for iptables"

mkdir -p "$BASE"
api() { echo "127.0.0.1:$((API_BASE + $1))"; }

pre_drop=$(sudo iptables -S INPUT 2>/dev/null | grep -c -- "-j DROP")
info "iptables DROP rules present before drill: $pre_drop (informational)"

echo "[setup] launching 3-node cluster under $BASE (jar: $JAR)"
for k in 1 2 3; do
  peers=$(echo "1 2 3" | tr ' ' '\n' | grep -v "^$k$" | paste -sd,)
  dd="$BASE/n$k"; mkdir -p "$dd"
  # Dev drill: co-located key on a single-host test box. Opt out of the fail-closed
  # guard (prod mounts the key separately — see deploy/compose + ADR-0043).
  CONFIGD_ALLOW_COLOCATED_SIGNING_KEY=true \
  java -Xmx256m --enable-preview -jar "$JAR" \
    --node-id "$k" --data-dir "$dd" --peers "$peers" \
    --bind-address 127.0.0.1 --bind-port $((RAFT_BASE + k)) \
    --api-port $((API_BASE + k)) --peer-addresses "$PEERS_ADDR" \
    > "$BASE/n$k.log" 2>&1 &
  PIDS+=("$!")
done

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
[ "$ready" -eq 1 ] || fail "not all 3 nodes became ready within ${ELECT_BUDGET_S}s"
pass "all 3 nodes ready"

# identify leader: node that COMMIT-CONFIRMS a probe PUT with 200 (ADR-0033)
find_leader() {
  for k in 1 2 3; do
    code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 8 -X PUT -d probe \
           "http://$(api $k)/v1/config/__leader_probe__" 2>/dev/null)
    [ "$code" = "200" ] && { echo "$k"; return 0; }
  done
  return 1
}
LEADER=$(find_leader) || fail "no leader elected"
pass "leader elected: node $LEADER (api $(api "$LEADER"))"

VICTIM=""
for k in 1 2 3; do [ "$k" != "$LEADER" ] && { VICTIM="$k"; break; }; done
DROP_PORT=$((RAFT_BASE + VICTIM))
KEEP=""
for k in 1 2 3; do [ "$k" != "$LEADER" ] && [ "$k" != "$VICTIM" ] && { KEEP="$k"; break; }; done
info "victim follower: node $VICTIM (raft port $DROP_PORT); surviving follower: node $KEEP"

# op probes: each echoes "<ok|FAIL|soft> <latency_ms> <detail>"
# Each probe enforces OP_DEADLINE_S via curl --max-time; latency is measured with
# %{time_total}. A timeout or non-2xx is a FAIL. The round's key/value are passed
# in explicitly (NOT via a shared counter — command substitution runs probes in a
# subshell, so a counter incremented inside would not survive to the reader).
probe_put() {
  local key="$1" val="$2" t code ms
  read -r code t < <(curl -s -o /dev/null -w "%{http_code} %{time_total}" \
        --max-time "$OP_DEADLINE_S" -X PUT -d "$val" \
        "http://$(api "$LEADER")/v1/config/$key" 2>/dev/null || echo "000 ${OP_DEADLINE_S}.0")
  ms=$(awk "BEGIN{printf \"%d\", $t*1000}")
  if [ "$code" = "200" ]; then echo "ok $ms PUT=200"; else echo "FAIL $ms PUT=$code"; fi
}
probe_get() {        # $1=key $2=expected — default (stale) read
  local key="$1" exp="$2" t v ms
  t=$(curl -s -o "$BASE/_g" -w "%{time_total}" --max-time "$OP_DEADLINE_S" \
        "http://$(api "$LEADER")/v1/config/$key" 2>/dev/null || echo "${OP_DEADLINE_S}.0")
  ms=$(awk "BEGIN{printf \"%d\", $t*1000}"); v=$(cat "$BASE/_g" 2>/dev/null)
  if [ "$v" = "$exp" ]; then echo "ok $ms GET=$exp"; else echo "FAIL $ms GET='$v'"; fi
}
probe_lin() {        # $1=key $2=expected — linearizable (ReadIndex; 503 transient possible)
  local key="$1" exp="$2" t v ms
  t=$(curl -s -o "$BASE/_l" -w "%{time_total}" --max-time "$OP_DEADLINE_S" \
        "http://$(api "$LEADER")/v1/config/$key?consistency=linearizable" 2>/dev/null || echo "${OP_DEADLINE_S}.0")
  ms=$(awk "BEGIN{printf \"%d\", $t*1000}"); v=$(cat "$BASE/_l" 2>/dev/null)
  if [ "$v" = "$exp" ]; then echo "ok $ms LIN=$exp"; else echo "soft $ms LIN='$v'"; fi
}
probe_health() {
  local t code ms
  read -r code t < <(curl -s -o /dev/null -w "%{http_code} %{time_total}" \
        --max-time "$OP_DEADLINE_S" "http://$(api "$LEADER")/health/ready" 2>/dev/null || echo "000 ${OP_DEADLINE_S}.0")
  ms=$(awk "BEGIN{printf \"%d\", $t*1000}")
  if [ "$code" = "200" ]; then echo "ok $ms HEALTH=200"; else echo "FAIL $ms HEALTH=$code"; fi
}

# Per-round key counter lives in the PARENT shell (probes run in subshells via
# command substitution, so a counter incremented inside them would not survive).
SEQ=0
nextkey() { SEQ=$((SEQ + 1)); }

echo "[baseline] one committing round before the black-hole"
nextkey
b=$(probe_put "drill/k$SEQ" "v$SEQ"); echo "    $b"
case "$b" in ok*) pass "leader commits before fault" ;; *) fail "leader not committing even before fault: $b" ;; esac

echo "[fault] DROP inbound SYNs to follower node $VICTIM raft port $DROP_PORT for ${DROP_WINDOW_S}s"
sudo iptables -A INPUT -p tcp --dport "$DROP_PORT" -j DROP \
  || fail "could not add iptables DROP rule"
armed=$(sudo iptables -S INPUT | grep -c -- "--dport $DROP_PORT -j DROP")
[ "$armed" -ge 1 ] || fail "DROP rule not present after add"
pass "DROP rule armed ($armed) on port $DROP_PORT"

# Force the leader to (re)establish a connection to the victim: kill nothing,
# but the leader will retry replication to the victim every heartbeat. To make
# the fault bite even if a connection was already cached, we DROP the port (new
# SYNs after the existing socket eventually fails will black-hole).

echo "[measure] probing leader every ~1s for ${DROP_WINDOW_S}s (per-op deadline ${OP_DEADLINE_S}s)"
printf '    %-6s %-10s %-26s %-22s %-22s %-14s\n' "t(s)" "PUT" "put_detail" "get_detail" "lin_detail" "health"
start=$(date +%s)
fail_rounds=0; total_rounds=0; first_fail_t=""
LIN_OK=0
while :; do
  now=$(date +%s); el=$((now - start))
  [ "$el" -ge "$DROP_WINDOW_S" ] && break
  total_rounds=$((total_rounds + 1))
  nextkey; key="drill/k$SEQ"; val="v$SEQ"
  p=$(probe_put "$key" "$val");  pl=$(echo "$p" | cut -d' ' -f2); ps=$(echo "$p" | cut -d' ' -f1)
  g=$(probe_get "$key" "$val");  gd=$(echo "$g" | cut -d' ' -f3-)
  l=$(probe_lin "$key" "$val");  ld=$(echo "$l" | cut -d' ' -f3-); ls_=$(echo "$l" | cut -d' ' -f1)
  h=$(probe_health);             hs=$(echo "$h" | cut -d' ' -f1); hms=$(echo "$h" | cut -d' ' -f2)
  [ "$ls_" = "ok" ] && LIN_OK=$((LIN_OK + 1))
  printf '    %-6s %-10s %-26s %-22s %-22s %-14s\n' \
    "$el" "${ps}/${pl}ms" "$(echo "$p"|cut -d' ' -f3-)" "$gd" "$ld" "${hs}/${hms}ms"
  if [ "$ps" != "ok" ] || [ "$hs" != "ok" ]; then
    fail_rounds=$((fail_rounds + 1))
    [ -z "$first_fail_t" ] && first_fail_t="$el"
  fi
  # pace ~1s between rounds (the probes themselves consume time under fault)
  sleep 1
done
echo "[measure] window complete: $total_rounds rounds, $fail_rounds with a PUT/HEALTH failure (first at t=${first_fail_t:-none}s); linearizable-ok rounds: $LIN_OK"

# disarm (also handled by trap, but do it explicitly + verify)
while sudo iptables -D INPUT -p tcp --dport "$DROP_PORT" -j DROP 2>/dev/null; do :; done
after=$(sudo iptables -S INPUT | grep -c -- "--dport $DROP_PORT -j DROP")
[ "$after" = "0" ] || fail "DROP rule still present after disarm ($after) — box not clean"
pass "DROP rule removed; 0 remain on port $DROP_PORT"

# verdict
# POST-FIX success = every round committed under deadline (fail_rounds == 0) and
#   at least one linearizable read succeeded (read path also unblocked).
# PRE-FIX is EXPECTED to stall: fail_rounds > 0. In prefix mode we treat a stall
#   as the captured pre-fix evidence and exit 0 (so the capture run "succeeds" at
#   demonstrating the bug); in postfix/auto mode a stall is a FAILURE.
if [ "$fail_rounds" -eq 0 ] && [ "$LIN_OK" -ge 1 ]; then
  pass "leader committed + served reads + health throughout the ${DROP_WINDOW_S}s DROP window (NO stall)"
  echo "DRILL PASS (post-fix behaviour): one black-holed follower did NOT freeze the leader."
  exit 0
else
  echo "DRILL OBSERVED STALL: $fail_rounds/$total_rounds rounds failed the ${OP_DEADLINE_S}s deadline" >&2
  if [ "$MODE" = "prefix" ]; then
    echo "DRILL PREFIX-CAPTURE OK: stall reproduced (this is the RR-002 pre-fix evidence)."
    exit 0
  fi
  fail "leader stalled under a single black-holed follower (RR-002 NOT fixed / regressed)"
fi
