#!/usr/bin/env bash
# e2e-compose-scenario.sh — end-to-end Docker-Compose scenario (a gate-3 step).
# 3 control-plane nodes + 3 edge nodes, full mTLS + signed chain,
# then four scripted fault phases with EXPLICIT exit-code assertions:
#
#   Phase 1  PROPAGATION   sustained writes; every edge serves the written value
#                          at >= the committed seq (bounded read via the
#                          X-Configd-Cursor request header); secure/ fail-closed.
#   Phase 2  KILL LEADER   SIGKILL the leader mid-stream; a per-edge monotonic
#                          version watch spans the whole failover window (NO edge
#                          may EVER see X-Configd-Cursor decrease); writes resume
#                          via a new leader; staleness returns to CURRENT.
#   Phase 3  PARTITION     docker-network-disconnect one edge; it walks the
#                          staleness ladder (DEGRADED -> DISCONNECTED, ready 503,
#                          re-bootstrap trigger fires); after heal it catches up
#                          and converges; staleness returns to CURRENT.
#   Phase 4  BOOTSTRAP     a FRESH edge container joins mid-load (cursor 0 =>
#                          SNAPSHOT_FIRST), converges, then the topology
#                          quiesces and every edge is BYTE-EQUAL to a
#                          linearizable read of every written key.
#
# Discipline (the retry-across-churn patterns from smoke-multinode.sh):
#   - NO sleeps as synchronization — every wait is a deadline-bounded poll
#     (sleep appears only as a poll interval);
#   - throttle-robust budgets (the 2-vCPU box CPU-credit reality);
#   - kill by container name via docker (never pkill by jar path);
#   - cleanup trap: compose down -v, helper PIDs killed by tracked PID.
#
# Prereqs (the gate runner does these IN ORDER — never concurrently):
#   1. ./mvnw -o clean verify   (or at least `package` of server + edge-node)
#   2. this script (it stages jars, verifies classes-in-jar, builds images,
#      generates secrets, then runs the scenario).
# Env knobs: E2E_KEEP_UP=1 leaves the topology running on success (debugging).
set -u

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_DIR="$REPO_ROOT/deploy/compose"
COMPOSE=(docker compose --project-directory "$COMPOSE_DIR" -f "$COMPOSE_DIR/compose.yaml")
NET="configd-e2e_cpnet"

CP_PORTS=(18081 18082 18083)        # cp1..cp3 API on host loopback
EDGE_PORTS=(18181 18182 18183)      # edge1..edge3 HTTP on host loopback
EDGE4_PORT=18184
EDGE_SVCS=(edge1 edge2 edge3)

# Budgets (seconds) — generous on purpose: CPU-credit throttling is real.
READY_BUDGET=180
LEADER_BUDGET=120
PROPAGATION_BUDGET=120
FAILOVER_BUDGET=180
LADDER_DEGRADED_BUDGET=60
LADDER_DISCONNECTED_BUDGET=90
CONVERGE_BUDGET=180
QUIESCE_BUDGET=240

KEYSPACE=40                          # e2e/k0..k39, cycled by the writer
PASS_COUNT=0
WRITER_PID=""
WATCHER_PIDS=()
SCRATCH="$(mktemp -d /tmp/configd-e2e.XXXXXX)"

log()  { echo "[e2e $(date +%H:%M:%S)] $*"; }
pass() { PASS_COUNT=$((PASS_COUNT + 1)); echo "  PASS[$PASS_COUNT]: $*"; }
fail() { echo "E2E FAIL: $*" >&2; cleanup; exit 1; }

cleanup() {
    # Targeted waits ONLY: a bare `wait` blocks on EVERY background job — with the
    # sustained writer still running that is a deadlock (found the hard way).
    if [ -n "$WRITER_PID" ]; then kill "$WRITER_PID" 2>/dev/null; wait "$WRITER_PID" 2>/dev/null; fi
    for p in "${WATCHER_PIDS[@]:-}"; do kill "$p" 2>/dev/null; wait "$p" 2>/dev/null; done
    if [ "${E2E_KEEP_UP:-0}" != "1" ]; then
        "${COMPOSE[@]}" --profile bootstrap down -v --remove-orphans -t 5 >/dev/null 2>&1
    fi
    rm -rf "$SCRATCH"
}
trap cleanup EXIT

# Deadline-bounded poll: poll_until <budget_s> <interval_s> <desc> <cmd...>
poll_until() {
    local budget=$1 interval=$2 desc=$3; shift 3
    local deadline=$((SECONDS + budget))
    while [ $SECONDS -lt $deadline ]; do
        if "$@" >/dev/null 2>&1; then return 0; fi
        sleep "$interval"
    done
    echo "  poll_until TIMED OUT after ${budget}s: $desc" >&2
    return 1
}

# The CP API is HTTPS when the TLS triple is on (HttpApiServer wraps the same
# SSLContext; server-auth only, no client cert needed) — every CP curl pins the
# self-signed server cert as its CA. Edge HTTP surfaces stay plain HTTP.
CACERT="$COMPOSE_DIR/secrets/server.pem"
api()      { echo "https://127.0.0.1:${CP_PORTS[$(($1 - 1))]}"; }
edge_api() { echo "127.0.0.1:$1"; }

# A scrape that survives partitions: docker exec curls INSIDE the container.
edge_metric() { # <service> <metric-name> -> prints last value field
    docker exec "configd-e2e-$1-1" curl -fsS --max-time 3 http://localhost:8080/metrics 2>/dev/null \
        | awk -v m="$2" '$1 == m { v=$2 } END { if (v != "") print v; else exit 1 }'
}

edge_state_is()  { [ "$(edge_metric "$1" edge_staleness_state)" = "$2" ]; }
edge_state_gte() { local v; v=$(edge_metric "$1" edge_staleness_state) && [ -n "$v" ] && [ "${v%.*}" -ge "$2" ]; }

# Leader = the node that COMMIT-CONFIRMS a probe PUT (ADR-0033: 200 == quorum
# commit). The probe is a real write; --max-time must exceed the 5s write deadline.
find_leader() {
    local k
    for k in 1 2 3; do
        [ -n "${DEAD_CP:-}" ] && [ "$k" = "$DEAD_CP" ] && continue
        local code
        code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 8 --cacert "$CACERT" -X PUT -d probe \
               "$(api "$k")/v1/config/__leader_probe__" 2>/dev/null)
        [ "$code" = "200" ] && { echo "$k"; return 0; }
    done
    return 1
}

resolve_leader() { # retry-across-churn wrapper, prints the node id
    local i
    for i in $(seq 1 60); do
        local l
        if l=$(find_leader); then echo "$l"; return 0; fi
        sleep 1
    done
    return 1
}

# Committed write with retry across leader churn; prints the committed seq.
put_committed() { # <key> <value>
    local key=$1 val=$2 i
    for i in $(seq 1 60); do
        local l
        l=$(find_leader) || { sleep 1; continue; }
        local body code
        body=$(curl -s --max-time 8 --cacert "$CACERT" -w $'\n%{http_code}' -X PUT -d "$val" \
               "$(api "$l")/v1/config/$key" 2>/dev/null)
        code=$(echo "$body" | tail -1)
        if [ "$code" = "200" ]; then
            echo "$body" | head -1 | sed -n 's/^Committed: seq=\([0-9]*\).*/\1/p'
            return 0
        fi
        sleep 1
    done
    return 1
}

# Edge serves <key>=<value> at a version >= <seq>: the bounded read (the request
# X-Configd-Cursor header makes "fresh enough" explicit — 404 cursor-behind until
# the edge catches up, 200 + the value once it has).
edge_serves_at() { # <edge-host:port> <key> <value> <seq>
    local out
    out=$(curl -s --max-time 3 -H "X-Configd-Cursor: $4" "http://$1/v1/config/$2" 2>/dev/null)
    [ "$out" = "$3" ]
}

# sustained writer (background): cycles e2e/k<i>, retries across churn
writer_loop() {
    local i=0
    while :; do
        local l
        l=$(find_leader 2>/dev/null) || { sleep 1; continue; }
        local body code seq
        body=$(curl -s --max-time 8 --cacert "$CACERT" -w $'\n%{http_code}' -X PUT -d "val-$i" \
               "$(api "$l")/v1/config/e2e/k$((i % KEYSPACE))" 2>/dev/null)
        code=$(echo "$body" | tail -1)
        if [ "$code" = "200" ]; then
            seq=$(echo "$body" | head -1 | sed -n 's/^Committed: seq=\([0-9]*\).*/\1/p')
            [ -n "$seq" ] && echo "$seq" > "$SCRATCH/writer.lastseq"
            i=$((i + 1))
        fi
        sleep 0.1
    done
}

# per-edge monotonic version watcher (background)
# Samples X-Configd-Cursor from every response (hits, misses and refusals all
# carry it). The kill-leader assertion replays the log: NEVER decreasing.
watcher_loop() { # <edge-port> <outfile>
    while :; do
        local c
        c=$(curl -s --max-time 1 -D - -o /dev/null \
            "http://127.0.0.1:$1/v1/config/e2e/k0" 2>/dev/null \
            | tr -d '\r' | awk -F': ' 'tolower($1)=="x-configd-cursor"{print $2}')
        [ -n "$c" ] && echo "$c" >> "$2"
        sleep 0.2
    done
}

assert_monotonic() { # <file> <min-samples> <desc>
    local n
    n=$(wc -l < "$1" 2>/dev/null || echo 0)
    [ "$n" -ge "$2" ] || fail "$3: watcher captured only $n samples (< $2) — watch was not live"
    awk 'NR>1 && $1 < prev { exit 1 } { prev=$1 }' "$1" \
        || fail "$3: X-Configd-Cursor DECREASED (monotonic-read violation)"
}

# Phase 0 — build images, secrets, bring the steady-state topology up
log "phase 0: stage jars, verify shaded-jar contents, build images, secrets, up"

command -v docker >/dev/null || fail "docker not available"
# NOTE: the Maven 3.9.9 wrapper launches via plexus-classworlds, so the historical
# "[o]rg.apache.maven" pattern misses it — match the wrapper dist path AND the
# surefire fork (the bracket keeps the pattern from matching this script itself).
pgrep -f "[a]pache-maven|[s]urefirebooter" >/dev/null && fail "a Maven/surefire build is running — Compose runs AFTER builds complete (2-vCPU rule)"

SERVER_JAR=$(ls "$REPO_ROOT"/configd-server/target/configd-server-*.jar 2>/dev/null | grep -v original- | head -1)
EDGE_JAR=$(ls "$REPO_ROOT"/configd-edge-node/target/configd-edge-node-*.jar 2>/dev/null | grep -v original- | head -1)
[ -n "$SERVER_JAR" ] && [ -f "$SERVER_JAR" ] || fail "server shaded jar missing — ./mvnw -pl configd-server -am clean package -DskipTests"
[ -n "$EDGE_JAR" ] && [ -f "$EDGE_JAR" ] || fail "edge-node shaded jar missing — ./mvnw -pl configd-edge-node -am clean package -DskipTests"

# Shaded-jar trap: prove the classes this scenario depends on are IN the
# jars we are about to containerize (a stale jar fails loudly here, not 4 phases in).
unzip -p "$SERVER_JAR" io/configd/distribution/fanout/FanOutSessionCore.class > /dev/null 2>&1 \
    || fail "FanOutSessionCore not inside $SERVER_JAR (stale/unshaded jar?)"
unzip -p "$SERVER_JAR" io/configd/server/fanout/FanOutServer.class > /dev/null 2>&1 \
    || fail "FanOutServer not inside $SERVER_JAR"
unzip -p "$EDGE_JAR" io/configd/edge/EdgeClientCore.class > /dev/null 2>&1 \
    || fail "EdgeClientCore not inside $EDGE_JAR (stale/unshaded jar?)"
# Freshness probe: the fix's field must be present in the shipped class
# (javap needs a real file — extract into the scratch dir).
unzip -p "$SERVER_JAR" io/configd/distribution/fanout/FanOutSessionCore.class \
    > "$SCRATCH/FanOutSessionCore.class" 2>/dev/null
javap -p "$SCRATCH/FanOutSessionCore.class" 2>/dev/null | grep -q pendingDemotionNotice \
    || fail "RR-104 fix (pendingDemotionNotice) not in the shaded FanOutSessionCore — rebuild (the shaded-jar trap)"
pass "shaded jars present and carry the expected classes (incl. the RR-104 fix)"

bash "$COMPOSE_DIR/setup-secrets.sh" || fail "secrets generation failed"

mkdir -p "$SCRATCH/ctx-server" "$SCRATCH/ctx-edge"
cp "$SERVER_JAR" "$SCRATCH/ctx-server/configd-server.jar"
cp "$EDGE_JAR" "$SCRATCH/ctx-edge/configd-edge-node.jar"
docker build -q -f "$COMPOSE_DIR/Dockerfile.server" -t configd-e2e-server:latest "$SCRATCH/ctx-server" >/dev/null \
    || fail "server image build failed"
docker build -q -f "$COMPOSE_DIR/Dockerfile.edge" -t configd-e2e-edge:latest "$SCRATCH/ctx-edge" >/dev/null \
    || fail "edge image build failed"
pass "images built (slim JRE layers over the staged shaded jars)"

"${COMPOSE[@]}" --profile bootstrap down -v --remove-orphans -t 5 >/dev/null 2>&1  # idempotency
"${COMPOSE[@]}" up -d || fail "compose up failed"

cp_ready() { # all 3 CP /health/ready == 200 (leader elected => ready)
    local k
    for k in 1 2 3; do
        [ "$(curl -s -o /dev/null -w "%{http_code}" --max-time 2 --cacert "$CACERT" "$(api $k)/health/ready" 2>/dev/null)" = "200" ] || return 1
    done
}
poll_until $READY_BUDGET 1 "all 3 CP nodes ready" cp_ready || fail "CP cluster never became ready"
pass "3-node control plane ready (leader elected, mTLS raft + mTLS fan-out up)"

LEADER=$(resolve_leader) || fail "no leader accepted a committed write"
pass "leader resolved: cp$LEADER"

edges_ready() {
    local p
    for p in "${EDGE_PORTS[@]}"; do
        [ "$(curl -s -o /dev/null -w "%{http_code}" --max-time 2 "http://127.0.0.1:$p/health/ready" 2>/dev/null)" = "200" ] || return 1
    done
}
# Edge readiness needs first sync (a never-synced edge is 503 by design, CT-05) —
# the leader probe writes above give them something to sync to.
poll_until $READY_BUDGET 1 "all 3 edges ready (first sync)" edges_ready \
    || fail "edges never reached ready (first verified apply)"
pass "3 edge nodes subscribed over mTLS, verified-applied, ready"

# Phase 1 — sustained writes -> propagation to every edge
log "phase 1: sustained writes -> bounded-read propagation on every edge"

writer_loop & WRITER_PID=$!
poll_until 60 0.5 "writer commits flowing" test -s "$SCRATCH/writer.lastseq" \
    || fail "sustained writer never committed a write"

MARKER1_SEQ=$(put_committed "e2e/marker-p1" "marker-p1") || fail "marker-p1 write failed"
[ -n "$MARKER1_SEQ" ] || fail "marker-p1 returned no seq"
log "marker-p1 committed at seq=$MARKER1_SEQ; requiring every edge to serve it at >= that seq"

for i in 0 1 2; do
    p=${EDGE_PORTS[$i]}
    poll_until $PROPAGATION_BUDGET 0.5 "edge$((i+1)) serves marker-p1@>=$MARKER1_SEQ" \
        edge_serves_at "$(edge_api "$p")" "e2e/marker-p1" "marker-p1" "$MARKER1_SEQ" \
        || fail "edge$((i+1)) never served e2e/marker-p1 at cursor >= $MARKER1_SEQ"
done
pass "propagation: every edge serves the committed value at >= seq=$MARKER1_SEQ (bounded read)"

# secure/ strong-read class: stored at edges but NEVER served (CT-37 fail-closed) —
# checked at EVERY edge (the note claims every edge; the check must too).
put_committed "secure/e2e-secret" "must-never-leave" >/dev/null || fail "secure/ write failed"
for i in "${!EDGE_PORTS[@]}"; do
    SC=$(curl -s -o /dev/null -w "%{http_code}" --max-time 3 "http://$(edge_api "${EDGE_PORTS[$i]}")/v1/config/secure/e2e-secret")
    [ "$SC" = "503" ] || fail "secure/ read at edge$((i+1)) returned $SC (expected 503 fail-closed)"
done
pass "secure/ key fail-closed at EVERY edge (503, never served from edge state)"

# Phase 2 — kill the leader mid-stream
log "phase 2: kill leader cp$LEADER mid-stream (SIGKILL), watch every edge for monotonicity"

for i in 0 1 2; do
    : > "$SCRATCH/watch-edge$((i+1)).log"
    watcher_loop "${EDGE_PORTS[$i]}" "$SCRATCH/watch-edge$((i+1)).log" & WATCHER_PIDS+=("$!")
done
# Let the watchers observe the healthy stream first (deadline-poll, not a sleep-sync:
# we require evidence the watch is live BEFORE the kill).
poll_until 30 0.5 "watchers sampling" \
    bash -c "[ \$(wc -l < '$SCRATCH/watch-edge1.log') -ge 5 ]" \
    || fail "version watchers never started sampling"

docker kill "configd-e2e-cp$LEADER-1" >/dev/null 2>&1 || fail "docker kill cp$LEADER failed"
DEAD_CP="$LEADER"
log "cp$LEADER killed; resolving the new leader (writes keep retrying across churn)"

NEW_LEADER=""
deadline=$((SECONDS + FAILOVER_BUDGET))
while [ $SECONDS -lt $deadline ]; do
    if NEW_LEADER=$(find_leader); then break; fi
    sleep 1
done
[ -n "$NEW_LEADER" ] || fail "no new leader within ${FAILOVER_BUDGET}s of the kill"
pass "new leader elected: cp$NEW_LEADER (old: cp$DEAD_CP)"

MARKER2_SEQ=$(put_committed "e2e/marker-p2" "marker-p2") || fail "post-failover write failed"
for i in 0 1 2; do
    p=${EDGE_PORTS[$i]}
    poll_until $FAILOVER_BUDGET 0.5 "edge$((i+1)) serves marker-p2@>=$MARKER2_SEQ" \
        edge_serves_at "$(edge_api "$p")" "e2e/marker-p2" "marker-p2" "$MARKER2_SEQ" \
        || fail "edge$((i+1)) never caught up past the failover (marker-p2@$MARKER2_SEQ)"
done
pass "all edges progressed THROUGH the failover to seq>=$MARKER2_SEQ"

# Staleness must recover to CURRENT (state 0) on every edge.
for s in "${EDGE_SVCS[@]}"; do
    poll_until $FAILOVER_BUDGET 1 "$s staleness CURRENT" edge_state_is "$s" 0 \
        || fail "$s staleness never returned to CURRENT after the failover"
done
pass "staleness recovered to CURRENT on every edge after the failover"

# Stop the watchers and replay their logs: the central kill-leader assertion.
# (Targeted wait — a bare `wait` would block on the still-running writer.)
for p in "${WATCHER_PIDS[@]}"; do kill "$p" 2>/dev/null; wait "$p" 2>/dev/null; done
WATCHER_PIDS=()
# Min-samples is a NON-VACUITY guard (the watch was genuinely live), not a
# correctness bar: on the 2-vCPU box with 7 JVMs a curl round-trip can take
# seconds, and a fast failover (a healthy outcome) shortens the window — a few
# samples across kill->re-elect->converge is live evidence without punishing
# either the loaded box or a quick election. The real correctness check is the
# monotonicity awk in assert_monotonic, independent of this floor; the floor of 5
# is provably safe (loosening a non-vacuity floor can only make the gate more
# tolerant, never hide a monotonic-read violation).
for i in 1 2 3; do
    assert_monotonic "$SCRATCH/watch-edge$i.log" 5 "edge$i failover window"
done
pass "monotonic version watch: NO edge ever saw X-Configd-Cursor decrease across the failover"

# Bring the killed node back (it must rejoin for phases 3-4's full-strength cluster).
"${COMPOSE[@]}" start "cp$DEAD_CP" >/dev/null 2>&1 || "${COMPOSE[@]}" up -d "cp$DEAD_CP" >/dev/null 2>&1
poll_until $READY_BUDGET 1 "cp$DEAD_CP rejoined" \
    bash -c "[ \"\$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 --cacert "$CACERT" $(api "$DEAD_CP")/health/ready)\" = 200 ]" \
    || fail "killed node cp$DEAD_CP never rejoined ready state"
DEAD_CP=""
pass "killed node restarted and rejoined the cluster"

# Phase 3 — partition one edge: ladder, demotion, catch-up, convergence
VICTIM=edge1
VICTIM_PORT=${EDGE_PORTS[0]}
VICTIM_IP=172.28.0.21   # the compose static IP — reconnect MUST reuse it or the
                        # host-published-port NAT path dies with the old address
log "phase 3: partition $VICTIM from cpnet (writes continue); expect the staleness ladder"

REBOOT_BEFORE=$(edge_metric $VICTIM edge_rebootstrap_triggered_total || echo 0)
docker network disconnect "$NET" "configd-e2e-$VICTIM-1" \
    || fail "network disconnect failed"

# The ladder is wall-clock governed (contract §2): STALE>500ms, DEGRADED>5s,
# DISCONNECTED>30s. Observation is via docker exec (published ports survive, but
# exec is partition-proof by construction).
poll_until $LADDER_DEGRADED_BUDGET 1 "$VICTIM reaches DEGRADED(2)+" edge_state_gte $VICTIM 2 \
    || fail "$VICTIM never reached DEGRADED while partitioned"
pass "victim walked the ladder to DEGRADED (>5s behind the frontier)"

RC=$(docker exec "configd-e2e-$VICTIM-1" curl -s -o /dev/null -w "%{http_code}" --max-time 3 http://localhost:8080/health/ready)
[ "$RC" = "503" ] || fail "partitioned $VICTIM /health/ready returned $RC (expected 503 at DEGRADED+)"
pass "victim reports unready (503) at DEGRADED+ — the CT-05 load-balancer signal"

poll_until $LADDER_DISCONNECTED_BUDGET 2 "$VICTIM reaches DISCONNECTED(3)" edge_state_gte $VICTIM 3 \
    || fail "$VICTIM never reached DISCONNECTED while partitioned"
poll_until 30 2 "$VICTIM re-bootstrap trigger fired" \
    bash -c "[ \"\$(docker exec configd-e2e-$VICTIM-1 curl -fsS --max-time 3 http://localhost:8080/metrics | awk '\$1==\"edge_rebootstrap_triggered_total\"{print \$2}' | head -1 | cut -d. -f1)\" -gt \"${REBOOT_BEFORE%.*}\" ]" \
    || fail "$VICTIM DISCONNECTED but edge_rebootstrap_triggered_total never moved"
pass "victim walked to DISCONNECTED and the re-bootstrap trigger fired (demotion ladder complete)"

docker network connect --ip "$VICTIM_IP" "$NET" "configd-e2e-$VICTIM-1" || fail "network re-connect failed"
log "$VICTIM healed; expecting catch-up + convergence"

MARKER3_SEQ=$(put_committed "e2e/marker-p3" "marker-p3") || fail "marker-p3 write failed"
poll_until $CONVERGE_BUDGET 1 "$VICTIM converges to marker-p3@>=$MARKER3_SEQ" \
    edge_serves_at "$(edge_api "$VICTIM_PORT")" "e2e/marker-p3" "marker-p3" "$MARKER3_SEQ" \
    || fail "$VICTIM never converged after the heal"
poll_until $CONVERGE_BUDGET 1 "$VICTIM staleness CURRENT" edge_state_is $VICTIM 0 \
    || fail "$VICTIM staleness never returned to CURRENT after the heal"
RC=$(curl -s -o /dev/null -w "%{http_code}" --max-time 3 "http://$(edge_api "$VICTIM_PORT")/health/ready")
[ "$RC" = "200" ] || fail "healed $VICTIM /health/ready returned $RC"
pass "victim caught up (serves marker-p3@>=$MARKER3_SEQ), staleness CURRENT, ready 200"

# Phase 4 — bootstrap a FRESH edge mid-load, then quiesce + byte-equal audit
log "phase 4: bootstrap edge4 mid-load (zero state => C3 SNAPSHOT_FIRST)"

"${COMPOSE[@]}" --profile bootstrap up -d edge4 || fail "edge4 start failed"
poll_until $READY_BUDGET 1 "edge4 ready" \
    bash -c "[ \"\$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 http://127.0.0.1:$EDGE4_PORT/health/ready)\" = 200 ]" \
    || fail "edge4 never became ready"

SNAPS=$(edge_metric edge4 edge_snapshots_applied_total || echo 0)
[ "${SNAPS%.*}" -ge 1 ] || fail "edge4 bootstrapped without a snapshot transfer (edge_snapshots_applied_total=$SNAPS) — not the C3 cursor-0 path"
pass "edge4 bootstrapped mid-load via snapshot transfer (edge_snapshots_applied_total=$SNAPS)"

MARKER4_SEQ=$(put_committed "e2e/marker-p4" "marker-p4") || fail "marker-p4 write failed"
poll_until $CONVERGE_BUDGET 1 "edge4 serves marker-p4@>=$MARKER4_SEQ" \
    edge_serves_at "$(edge_api "$EDGE4_PORT")" "e2e/marker-p4" "marker-p4" "$MARKER4_SEQ" \
    || fail "edge4 never converged to the live stream after bootstrap"
pass "edge4 cut over to the live stream (serves marker-p4@>=$MARKER4_SEQ)"

# quiesce + byte-equal audit
log "quiesce: stop the writer, fence-write, then byte-compare every key on every edge"
kill "$WRITER_PID" 2>/dev/null; wait "$WRITER_PID" 2>/dev/null; WRITER_PID=""

FENCE_SEQ=$(put_committed "e2e/fence" "fence-final") || fail "fence write failed"
ALL_EDGE_PORTS=("${EDGE_PORTS[@]}" "$EDGE4_PORT")
for p in "${ALL_EDGE_PORTS[@]}"; do
    poll_until $QUIESCE_BUDGET 0.5 "edge@$p reaches fence@>=$FENCE_SEQ" \
        edge_serves_at "$(edge_api "$p")" "e2e/fence" "fence-final" "$FENCE_SEQ" \
        || fail "edge@$p never reached the fence seq $FENCE_SEQ"
done

L=$(resolve_leader) || fail "no leader for the linearizable audit"
AUDIT_KEYS=(e2e/marker-p1 e2e/marker-p2 e2e/marker-p3 e2e/marker-p4 e2e/fence)
for i in $(seq 0 $((KEYSPACE - 1))); do AUDIT_KEYS+=("e2e/k$i"); done
MISMATCH=0
AUDITED=0
for key in "${AUDIT_KEYS[@]}"; do
    # ONE request for body + code: two separate curls could silently
    # key-skip on a transient non-200 between them; combined fetch closes the race.
    resp=$(curl -s --max-time 5 --cacert "$CACERT" -w $'\n%{http_code}' \
        "$(api "$L")/v1/config/$key?consistency=linearizable") || continue
    code=${resp##*$'\n'}
    truth=${resp%$'\n'*}
    # a key the cycling writer never reached is absent everywhere — skip 404 bodies
    [ "$code" = "200" ] || continue
    AUDITED=$((AUDITED + 1))
    for p in "${ALL_EDGE_PORTS[@]}"; do
        got=$(curl -s --max-time 3 -H "X-Configd-Cursor: $FENCE_SEQ" "http://$(edge_api "$p")/v1/config/$key")
        if [ "$got" != "$truth" ]; then
            echo "  BYTE-DIFF key=$key edge@$p got='$got' leader='$truth'" >&2
            MISMATCH=$((MISMATCH + 1))
        fi
    done
done
[ "$MISMATCH" -eq 0 ] || fail "$MISMATCH byte mismatches between edges and the linearizable leader state"
# Non-vacuity floor: the markers + fence alone are 5 keys; an audit
# that compared fewer keys than that silently audited nothing.
[ "$AUDITED" -ge 5 ] || fail "byte-equal audit compared only $AUDITED keys (>= 5 required — vacuous audit)"
pass "byte-equal audit: $AUDITED keys identical on all 4 edges vs the leader (linearizable)"

echo
echo "E2E PASS: $PASS_COUNT/$PASS_COUNT assertions — propagation, leader-kill monotonicity +"
echo "          staleness recovery, partition ladder + re-bootstrap + convergence, and"
echo "          mid-load bootstrap with byte-equal convergence, all over mTLS + signed chain."
exit 0
