#!/usr/bin/env bash
# =============================================================================
# gate-phase1.sh — Multi-Raft Phase 1 (the static-N sharding layer) cumulative gate.
# -----------------------------------------------------------------------------
# A green gate-phase1 SEALS the Phase 1 sharding foundation: the ShardMap seam +
# StaticShardMap (hash-within-scope), the cross-shard DISCLAIM write guard, and
# the multi-shard deterministic simulator with its six invariants (routing
# correctness, disjoint ownership, per-shard linearizability, cross-shard
# isolation, stale-map redirect, N=1 equivalence) — each proven NON-VACUOUS
# (an injected mis-route / wrong-shard / dropped-redirect / N=1-divergence goes
# RED). It is CUMULATIVE with gates 1..7 + phase0 + B: a green gate-phase1
# REQUIRES a green gate-B (which chains gate-phase0 -> gate-7 -> ... -> 1).
#
# In CI, gate-B (and its chain) run as their OWN jobs and this gate's job DEPENDS
# on gate-B, so GATE_PHASE1_SKIP_CHAIN=1 relies on that coverage (LOUD) instead of
# re-running the multi-hour chain. Locally / in a full manual run it runs the chain.
#
# WHAT A GREEN gate-phase1 PROVES (beyond the cumulative chain):
#   - the sharding ownership model is correct + the invariants are non-vacuous;
#   - the DISCLAIM cross-shard guard rejects a multi-key write spanning > 1 shard;
#   - the Phase 1 milestone artifacts EXIST (non-vacuity: a deleted class / sim /
#     design note FAILS this gate, never a silent pass — the RR-012 lesson).
#
# Environment knobs:
#   GATE_PHASE1_SKIP_CHAIN=1   skip the cumulative gate-B chain (CI supplies it via
#                              the gate jobs) — LOUD.
#   GATE_PHASE1_SKIP_BUILD=1   reuse already-installed module jars (local convenience).
#   GATE_PHASE1_SWEEP_COUNT=N  multi-shard full-surface sweep seed count (default 200
#                              for fast PR; CI nightly sets 10000 for the integrated
#                              >=10k-seed sweep — charter C5).
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MVN="$ROOT/mvnw -B"
LOGDIR="${GATE_PHASE1_LOG_DIR:-$(mktemp -d /tmp/gate-phase1-XXXXXX)}"
mkdir -p "$LOGDIR"

# Modules whose sharding tests this gate exercises. (Seam F added configd-transport + configd-netty:
# the wire bump's codec/byte-identity tests live there; both are already built as server deps via -am.)
MODULES="configd-common,configd-consensus-core,configd-transport,configd-netty,configd-replication-engine,configd-server,configd-testkit"

echo "=== gate-phase1 (Multi-Raft Phase 1: static-N sharding layer) — logs in $LOGDIR ==="

fail() { echo "gate-phase1 FAIL [$1]: $2" >&2; exit 1; }

# Asserts a surefire class ran with >=1 test and zero failures/errors (non-vacuity).
assert_class_green() {
  local log="$1" cls="$2" line
  line="$(grep -E "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+.* -- in .*\.${cls}$" "$log" | tail -1 || true)"
  [ -n "$line" ] || { tail -30 "$log"; fail tests "non-vacuity: ${cls} did not run (renamed/skipped?)"; }
  echo "$line" | grep -qE "Tests run: [1-9][0-9]*, Failures: 0, Errors: 0" \
    || { echo "  $line"; fail tests "${cls} did not pass with >=1 test and 0 failures/errors"; }
  echo "gate-phase1   ✓ ${cls}: ${line#*-- in }"
}

# Runs a targeted, offline test set across MODULES (with optional extra -D props) and asserts BUILD SUCCESS.
run_tests() {
  local label="$1" tests="$2" log="$3"; shift 3
  $MVN -o -pl "$MODULES" test -Dtest="$tests" -Dsurefire.failIfNoSpecifiedTests=false "$@" >"$log" 2>&1 \
    || { tail -80 "$log"; fail "$label" "test run failed"; }
  grep -q "BUILD SUCCESS" "$log" || { tail -80 "$log"; fail "$label" "no BUILD SUCCESS"; }
}

assert_file() { [ -e "$ROOT/$1" ] || fail artifacts "missing Phase 1 artifact: $1"; echo "gate-phase1   ✓ exists: $1"; }
assert_grep() { grep -qE "$2" "$ROOT/$1" 2>/dev/null || fail artifacts "expected /$2/ in $1 (artifact regressed?)"; echo "gate-phase1   ✓ $1 :: $2"; }

# --- 2-vCPU box discipline: never overlap another Maven workload --------------
if pgrep -f "[s]urefirebooter" >/dev/null 2>&1; then
  echo "gate-phase1: another Maven test workload is running — refusing to start (2-vCPU box)" >&2
  exit 1
fi

# --- cumulative: gate-B (chains gate-phase0 -> gate-7 -> ... -> 1) -------------
if [ "${GATE_PHASE1_SKIP_CHAIN:-0}" = "1" ]; then
  echo "gate-phase1 chain: SKIPPED by GATE_PHASE1_SKIP_CHAIN=1 (LOUD: gates 1..7 + phase0 + B NOT verified this run; CI supplies them via the gate jobs)"
else
  echo "gate-phase1 chain: running cumulative gate-B (chains gate-phase0 -> gate-7 -> ... -> 1)..."
  bash "$ROOT/gates/gate-B.sh" || fail chain "cumulative gate-B (1..7 + phase0 + B) is RED — Phase 1 sits on an unverified base"
  echo "gate-phase1 chain: OK (gates 1..7 + phase0 + B green)"
fi

# --- build/install the modules' deps once (so the targeted test runs are offline)
if [ "${GATE_PHASE1_SKIP_BUILD:-0}" = "1" ]; then
  echo "gate-phase1 build: SKIPPED by GATE_PHASE1_SKIP_BUILD=1 (reusing installed jars; CI must not do this)"
else
  echo "gate-phase1 build: installing module jars (skip tests) so the gate run is hermetic..."
  $MVN -q -pl "$MODULES" -am install -DskipTests >"$LOGDIR/build.txt" 2>&1 \
    || { tail -30 "$LOGDIR/build.txt"; fail build "module build/install failed"; }
fi

# --- (a) C1: ShardMap + StaticShardMap (hash-within-scope) + the DISCLAIM guard ----
echo "gate-phase1 c1: StaticShardMap (stable/in-range/opaque-id/N=1-equiv/spread) + cross-shard write guard..."
C1="$LOGDIR/c1.txt"
run_tests c1 "StaticShardMapTest,CrossShardWriteGuardTest" "$C1"
assert_class_green "$C1" "StaticShardMapTest"        # the hash-within-scope ownership model (C1)
assert_class_green "$C1" "CrossShardWriteGuardTest"  # cross-shard multi-key BATCH rejected (DISCLAIM, C2)
echo "gate-phase1 c1: OK"

# --- (b) V/C5: the multi-shard simulator + the six invariants, proven NON-VACUOUS ---
# The green tests route through the PRODUCTION StaticShardMap; the non-vacuity tests inject a mis-route /
# cross-shard-redirect / dropped-redirect / N=1-divergence and assert the matching invariant goes RED.
# GATE_PHASE1_SWEEP_COUNT sizes the full-surface sweep (fast PR = 200; nightly = 10000 — charter C5).
SWEEP_COUNT="${GATE_PHASE1_SWEEP_COUNT:-200}"
echo "gate-phase1 sim: multi-shard 6-invariant surface, full-surface sweep = ${SWEEP_COUNT} seeds..."
SIM="$LOGDIR/sim.txt"
run_tests sim "MultiShardSimTest" "$SIM" "-Dconfigd.multiShard.seedSweep.count=${SWEEP_COUNT}"
assert_class_green "$SIM" "MultiShardSimTest"
echo "gate-phase1 sim: OK"

# --- (c) Phase 1 milestone artifacts present (non-vacuity: a deleted artifact FAILS) ----
echo "gate-phase1 artifacts: asserting the Phase 1 milestone artifacts exist (non-vacuity)..."
assert_file "configd-replication-engine/src/main/java/io/configd/replication/ShardMap.java"
assert_file "configd-replication-engine/src/main/java/io/configd/replication/StaticShardMap.java"
assert_file "configd-replication-engine/src/main/java/io/configd/replication/CrossShardWriteGuard.java"
assert_file "configd-testkit/src/test/java/io/configd/testkit/MultiShardSim.java"
assert_file "configd-testkit/src/test/java/io/configd/testkit/MultiShardSimTest.java"
# the D-B seam invariants + the operator-flagged wire-epoch deferral are recorded
assert_grep "configd-replication-engine/src/main/java/io/configd/replication/ShardMap.java" "Opaque, stable shard IDs"
echo "gate-phase1 artifacts: OK"

# --- (d) Server-wiring foundation: Seam A (C4a config N) + Seam B (DL-P1-06 inbound demux) ----
# The production-wiring session (server-wiring-decision-log.md) lands the dormant sharding into the live
# ConfigdServer in dependency-ordered seams. A+B are the verified foundation: deploy-time shard-count
# selection (range + N>1 guard + fixed-at-deploy reshard guard) and the inbound groupId demux (a frame
# stamped gid=k reaches group k, not the captured constant 0). Both are N=1 byte-identical.
echo "gate-phase1 wiring: server N-config (C4a) + inbound groupId demux (DL-P1-06)..."
WIRING="$LOGDIR/wiring.txt"
run_tests wiring "ShardCountConfigTest,RaftInboundDemuxTest" "$WIRING"
assert_class_green "$WIRING" "ShardCountConfigTest"   # C4a: range + N>1 BOOTS (Seam G4) + fixed-at-deploy reshard reject
assert_class_green "$WIRING" "RaftInboundDemuxTest"   # DL-P1-06: gid=k -> group k (not 0); hostile gid dropped
assert_file "configd-server/src/test/java/io/configd/server/ShardCountConfigTest.java"
assert_file "configd-server/src/test/java/io/configd/server/RaftInboundDemuxTest.java"
echo "gate-phase1 wiring: OK"

# --- (e) Server-wiring Seam C: N-group consensus bring-up (buildRaftGroup loop) ----
# The single bring-up path used for EVERY shard. Non-vacuity: N=1 reuses the node-level Storage INSTANCE
# (assertSame — byte-identity), N>1 groups bring up independently with per-shard storage/store isolation
# + per-shard linearizability (the present/absent matrix goes RED on a shared-store leak), and each
# group's outbound adapter stamps ITS gid (would go RED on a captured-constant-0). The hostile-gid demux
# drop (MIN/MAX/negative/unregistered) is covered by RaftInboundDemuxTest in the wiring block above.
echo "gate-phase1 wiring-c: N-group consensus bring-up (Seam C buildRaftGroup loop)..."
WIRINGC="$LOGDIR/wiring-c.txt"
run_tests wiring-c "MultiGroupBringupTest" "$WIRINGC"
assert_class_green "$WIRINGC" "MultiGroupBringupTest"
assert_file "configd-server/src/test/java/io/configd/server/MultiGroupBringupTest.java"
echo "gate-phase1 wiring-c: OK"

# --- (f) Server-wiring Seam D: live write/read routing + cross-shard guard ----
# Drives the PRODUCTION proposer (shard-routing) + the sharded reader over N real groups. Non-vacuity:
# a write for key k applies to shardFor(GLOBAL,k)'s store and NO other (isolation matrix goes RED on a
# mis-route); the sharded reader resolves the same shard (read/write consistency); a multi-key write
# spanning shards is CrossShardRejected (DISCLAIM); the leader hint resolves the owning shard's leader.
echo "gate-phase1 wiring-d: live write/read routing + cross-shard guard (Seam D)..."
WIRINGD="$LOGDIR/wiring-d.txt"
run_tests wiring-d "ShardedRoutingTest" "$WIRINGD"
assert_class_green "$WIRINGD" "ShardedRoutingTest"
assert_file "configd-server/src/test/java/io/configd/server/ShardedRoutingTest.java"
echo "gate-phase1 wiring-d: OK"

# --- (g) Server-wiring Seam E: per-shard observability (no longer group-0-only) ----
# registerPerShardMetrics publishes per-group leader/term/commit-index/apply-lag + the per-node leader
# count, read from monitorView(). Non-vacuity: at N>1 every shard's series is present + leader=1 +
# leader_count=N; at N=1 ONLY the group-0 series exist (shard-1 absent).
echo "gate-phase1 wiring-e: per-shard observability (Seam E)..."
WIRINGE="$LOGDIR/wiring-e.txt"
run_tests wiring-e "PerShardMetricsTest" "$WIRINGE"
assert_class_green "$WIRINGE" "PerShardMetricsTest"
assert_file "configd-server/src/test/java/io/configd/server/PerShardMetricsTest.java"
echo "gate-phase1 wiring-e: OK"

# --- (h) Server-wiring Seam F: the WIRE_VERSION 0x01->0x02 bump (D1 epoch + D2 coalesced frame) ----
# ONE intentional wire bump, both fields DORMANT at N=1. Non-vacuity: the epoch is reserved (encode 0 /
# decode-ignore) and a v2 frame minus version+epoch is byte-for-byte the v1 frame (N=1 byte-identity);
# the golden fixtures match v2 (the wire-compat intentional bump — a drift goes RED); the Netty
# in-pipeline encoder is byte-identical to RaftWireProtocol.encodeWire at v2; the coalesced payload
# codec round-trips and rejects every adversarial malformation; the inbound demux splits a coalesced
# frame per-group; the send drain emits a coalesced frame only at >1 group (N=1 stays plain AppendEntries).
echo "gate-phase1 wiring-f: WIRE_VERSION v2 bump — epoch reservation + coalesced-heartbeat frame (Seam F)..."
WIRINGF="$LOGDIR/wiring-f.txt"
run_tests wiring-f "FrameCodecEpochReservationTest,WireCompatGoldenBytesTest,MessageTypeTest,NettyConsensusFrameEncoderByteIdentityTest,CoalescedHeartbeatCodecTest,RaftTransportAdapterCoalescedInboundTest,HeartbeatDrainFramingTest,RedTeamCoalescedWirePoCTest" "$WIRINGF"
assert_class_green "$WIRINGF" "FrameCodecEpochReservationTest"            # D1: epoch MBZ + N=1 byte-identity
assert_class_green "$WIRINGF" "WireCompatGoldenBytesTest"                 # v2 golden bytes match (intentional bump)
assert_class_green "$WIRINGF" "NettyConsensusFrameEncoderByteIdentityTest" # Netty encoder == encodeWire at v2
assert_class_green "$WIRINGF" "CoalescedHeartbeatCodecTest"               # D2: coalesced codec round-trip + bounds
assert_class_green "$WIRINGF" "RaftTransportAdapterCoalescedInboundTest"  # D2: inbound per-group demux
assert_class_green "$WIRINGF" "HeartbeatDrainFramingTest"                 # D2: send drain dormant at N=1
assert_class_green "$WIRINGF" "RedTeamCoalescedWirePoCTest"               # D2: adversarial parser battery (red-team)
# The bump is INTENTIONAL: WIRE_VERSION is 0x02 and the header reserves the 8-byte epoch (HEADER_SIZE 26).
assert_grep "configd-transport/src/main/java/io/configd/transport/FrameCodec.java" "WIRE_VERSION = \(byte\) 0x02"
assert_grep "configd-transport/src/main/java/io/configd/transport/FrameCodec.java" "HEADER_SIZE = 26"
assert_grep "configd-transport/src/main/java/io/configd/transport/MessageType.java" "RAFT_COALESCED_HEARTBEAT\(0x11\)"
assert_file "configd-server/src/test/java/io/configd/server/CoalescedHeartbeatCodecTest.java"
echo "gate-phase1 wiring-f: OK"

# --- (i) Server-wiring Seam G: the integrated N>1 sweep (GATES the boot-guard removal) ----
# The cumulative proof that N>1 is correct END-TO-END (charter §3.4/§6) — the gate on lifting the boot
# guard. G1 (per-shard fan-out merge: monotone, isolated, concurrent-safe, no fabricated global order),
# G2 (live shared-node isolation: a STUCK apply starves a co-owned sibling — coupling-leak RED — while
# the other owner stays live, with per-shard safety preserved), G3 (the REAL production bring-up
# buildRaftGroup COMPOSED with the sharded fan-out at N>1 on shared owners — per-shard isolation in BOTH
# the store and the fan-out), the thread-safety net proven NON-VACUOUS at N>1 (missed-hop via
# OwnerIsolationMultiOwnerTest), and the coalesced-heartbeat flat-in-N property (HeartbeatCoalescingTest,
# G up to 256 ⊇ the Phase-1 N<=16 ceiling).
echo "gate-phase1 wiring-g: integrated N>1 sweep (fan-out + live isolation + thread-safety net + coalesced-HB)..."
WIRINGG="$LOGDIR/wiring-g.txt"
run_tests wiring-g "ShardedFanOutTest,MultiShardIntegratedSweepTest,SharedNodeFaultIsolationLiveTest,OwnerIsolationMultiOwnerTest,HeartbeatCoalescingTest" "$WIRINGG"
assert_class_green "$WIRINGG" "ShardedFanOutTest"                # G1: per-shard fan-out (monotone/isolated/concurrent-safe)
assert_class_green "$WIRINGG" "MultiShardIntegratedSweepTest"    # G3: real bring-up + sharded fan-out compose at N>1
assert_class_green "$WIRINGG" "SharedNodeFaultIsolationLiveTest" # G2: coupling-leak RED + cross-owner isolation GREEN
assert_class_green "$WIRINGG" "OwnerIsolationMultiOwnerTest"     # thread-safety net non-vacuous (missed-hop)
assert_class_green "$WIRINGG" "HeartbeatCoalescingTest"          # coalesced-heartbeat flat-in-N (covers N<=16)
assert_file "configd-server/src/test/java/io/configd/server/ShardedFanOutTest.java"
assert_file "configd-server/src/test/java/io/configd/server/MultiShardIntegratedSweepTest.java"
assert_file "configd-replication-engine/src/test/java/io/configd/replication/SharedNodeFaultIsolationLiveTest.java"
echo "gate-phase1 wiring-g: OK"

# --- (j) Server-wiring Seam G4: the SWITCH-FLIP — the N>1 boot guard is REMOVED ----
# Ordered AFTER wiring-g (the integrated sweep) so the structure encodes the charter §3.4 gate: the guard
# is removed ONLY with the sweep green. The smoke proves the REAL ConfigdServer.start() now BOOTS at N=2
# (before G4 it threw), both shards self-elect, a propose to shard k commits+applies on shard k ONLY (live
# cross-shard isolation), and per-shard metrics are live. ShardCountConfigTest (in wiring above) flipped
# from "N>1 refused" to "N>1 boots + fixed-at-deploy". N=1 stays byte-identical (its own assertions).
echo "gate-phase1 wiring-g4: the switch-flip — N>1 boots on the real server (boot guard removed)..."
WIRINGG4="$LOGDIR/wiring-g4.txt"
run_tests wiring-g4 "NGreaterThanOneBootSmokeTest" "$WIRINGG4"
assert_class_green "$WIRINGG4" "NGreaterThanOneBootSmokeTest"   # G4: ConfigdServer.start() boots at N=2 + per-shard commit
assert_file "configd-server/src/test/java/io/configd/server/NGreaterThanOneBootSmokeTest.java"
# Non-vacuity: the temporary N>1 boot refusal is GONE from resolveShardCount (a regression that re-added
# the throw would FAIL the smoke; this grep also fails loudly if the scaffold message creeps back). The
# old guard's unique fragment was "is not enabled in this build".
if grep -q "is not enabled in this build" "$ROOT/configd-server/src/main/java/io/configd/server/ConfigdServer.java"; then
  fail wiring-g4 "the N>1 boot-refusal message is back in ConfigdServer — the switch-flip regressed"
fi
echo "gate-phase1 wiring-g4: OK"

echo "=== gate-phase1: GREEN — Multi-Raft Phase 1 sharding foundation + server-wiring A/B/C/D/E/F/G (incl. G4 switch-flip) verified ==="
exit 0
