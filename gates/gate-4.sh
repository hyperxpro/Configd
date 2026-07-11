#!/usr/bin/env bash
# gate-4.sh — cumulative machine-verifiable gate (durability, recovery & chaos)
#
# Cumulative with gates 1+2+3: a green gate-4 REQUIRES a green gate-3 (step a;
# gate-3 runs gate-2 which runs gate-1). Exits non-zero on ANY failure; no
# silent placeholders. A gate that is wired but never executed is the
# phantom-CI failure mode — every step asserts a real BUILD SUCCESS.
#
# WHAT A GREEN GATE-4 PROVES:
#   (a) gate3       gates 1+2+3 still green (cumulative; no regression of the
#                   control plane or the edge data plane while chaos hardening
#                   landed). In CI gate-3 runs as its own job, so the gate-4 job
#                   sets GATE4_SKIP_GATE3=1 (coverage via the job dependency).
#   (b) liveness    the formerly-stalling seed regression suite + first-class
#                   liveness: Rr103InflightWindowRecoveryTest (the
#                   inflight-window leak fix, recovery = 1 heartbeat),
#                   LivenessBoundedProgressSweepTest (200 seeds, bounded
#                   post-heal progress, 0 violations), Rr095StallSeedDiagnosisTest
#                   (all 7 stall seeds diagnosed as never-healed artifacts).
#   (c) reconfig    reconfiguration-under-fault: ReconfigurationTest (incl.
#                   JointConsensusEndToEnd — split-brain prevention + mid-joint
#                   crash recovery).
#   (d) durability  consensus durability cells: RaftLogCompactionTriggerTest
#                   (compaction reachable), SnapshotCrashRecoveryTest
#                   (durable-prefix + fsync-lie), FileStorageTest (long-safe
#                   read), MultiRaftDriverTest (compaction fan-out),
#                   StorageEnospcConsensusReactionTest (ENOSPC), ConfigdServerTest
#                   (tick-loop wiring source-guard + clean start).
#   (e) edgechaos   the owed edge-chaos legs: FanOutSessionCoreTest
#                   (prod-threshold ack-lag + wedged-transport),
#                   GovernorBoundedIdentityMapChurnTest (governor churn),
#                   EdgeTransportMtlsTest (accept-then-blackhole
#                   handshake-timeout, real socket).
#   (g) partition   PartitionMatrixTest (single-region/leader/asymmetric/
#                   partial/gray partitions + clock-skew; continuous safety
#                   oracles + recovery). Runs UNCONDITIONALLY in the CI subset
#                   (main() → step_partition, no skip guard).
#   (h) overload    OverloadChaosTest (control-plane write-flood backpressure +
#                   post-partition reconnect storm). Runs UNCONDITIONALLY in
#                   the CI subset (main() → step_overload).
#   (f) nightly     HEAVY/long integrated sweeps — NOT in the CI subset (run only
#                   on the nightly path): EdgeIntegratedNightlySweepTest
#                   (-Dconfigd.edge.nightly=true, 10k ticks), Rr095StallSeedsIntegratedRerunTest
#                   (-Dconfigd.rr095.rerun=true), and MiniJepsenSweepTest
#                   (sustained mixed-fault mini-Jepsen). The control-plane 10k
#                   SeedSweepTest already runs in the build-and-test job; not
#                   duplicated here.
#
# Environment knobs (CI must not set the skips on the nightly full run):
#   GATE4_SKIP_GATE3=1    skip step (a) — reported LOUDLY (CI runs gate-3 as its
#                         own job; local iteration only)
#   GATE4_SKIP_NIGHTLY=1  skip step (f) — reported LOUDLY (the heavy integrated
#                         sweeps; ~3-5 min). Default-skipped on push/PR; CI runs
#                         it on the nightly schedule.
#   GATE3_* / GATE2_* / GATE1_*   forwarded to the underlying gates.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOGDIR="${GATE4_LOG_DIR:-$(mktemp -d /tmp/gate4-XXXXXX)}"
mkdir -p "$LOGDIR"
export GATE4_LOG_DIR="$LOGDIR"

MVN="$ROOT/mvnw -B"
NOSPEC="-Dsurefire.failIfNoSpecifiedTests=false"

# Run a scoped test invocation, tee to a log, and assert a real BUILD SUCCESS.
# Usage: run_tests <label> <module> <comma-test-list> [extra mvn args...]
run_tests() {
  local label="$1" module="$2" tests="$3"; shift 3
  cd "$ROOT"
  $MVN -pl "$module" test -Dtest="$tests" $NOSPEC "$@" \
    2>&1 | tee "$LOGDIR/$label.log" | grep -E "Tests run|BUILD" | tail -4
  grep -q "BUILD SUCCESS" "$LOGDIR/$label.log" \
    || { echo "GATE-4 $label: FAILED (see $LOGDIR/$label.log)"; return 1; }
}

step_gate3() {
  if [ "${GATE4_SKIP_GATE3:-0}" = "1" ]; then
    echo "GATE-4 gate3: SKIPPED by GATE4_SKIP_GATE3=1 (LOUD: gates 1+2+3 NOT verified this run)"
    return 0
  fi
  bash "$ROOT/gates/gate-3.sh" 2>&1 | tee "$LOGDIR/gate3.log" | tail -5
  echo "GATE-4 gate3: OK (cumulative — gates 1+2+3 green)"
}

step_install() {
  cd "$ROOT"
  echo "GATE-4 install: building the reactor (-DskipTests) so the per-module test steps resolve..."
  # NB: do NOT use -q here — Maven's quiet mode suppresses the BUILD SUCCESS line,
  # so check the exit code directly (full output captured to the log for debugging).
  if ! $MVN -DskipTests install > "$LOGDIR/install.log" 2>&1; then
    echo "GATE-4 install: reactor build FAILED (see $LOGDIR/install.log)"
    grep -iE "ERROR|BUILD FAILURE|cannot find symbol" "$LOGDIR/install.log" | grep -v WARNING | tail -15
    return 1
  fi
  echo "GATE-4 install: OK"
}

step_liveness() {
  run_tests liveness configd-consensus-core \
    "Rr103InflightWindowRecoveryTest,LivenessBoundedProgressSweepTest,Rr095StallSeedDiagnosisTest"
  echo "GATE-4 liveness: OK (RR-103 fix + bounded-progress sweep + RR-095 7-seed diagnosis)"
}

step_reconfig() {
  run_tests reconfig configd-consensus-core "ReconfigurationTest"
  echo "GATE-4 reconfig: OK (D§2 joint-consensus incl. split-brain prevention + mid-joint crash recovery)"
}

step_durability() {
  run_tests durability-consensus configd-consensus-core \
    "RaftLogCompactionTriggerTest,SnapshotCrashRecoveryTest"
  run_tests durability-common configd-common "FileStorageTest"
  run_tests durability-replication configd-replication-engine "MultiRaftDriverTest"
  run_tests durability-testkit configd-testkit "StorageEnospcConsensusReactionTest"
  run_tests durability-server configd-server \
    "ConfigdServerTest#rr005_raftLogCompactionTriggerIsWiredInTickLoop+serverStartsAndStopsCleanly+compactorReceivesSnapshotOnApply"
  echo "GATE-4 durability: OK (RR-005 compaction reachable+long-safe read; RR-003 durable-prefix+fsync-lie; ENOSPC)"
}

step_edgechaos() {
  run_tests edgechaos-fanout configd-distribution-service \
    "FanOutSessionCoreTest,GovernorBoundedIdentityMapChurnTest"
  run_tests edgechaos-edgenode configd-edge-node \
    "EdgeTransportMtlsTest#blackholedEndpointHandshakeTimesOutAndEdgeKeepsRetrying"
  echo "GATE-4 edgechaos: OK (A3 — ack-lag/wedged-transport/governor-churn/accept-then-blackhole)"
}

step_partition() {
  # Partition & WAN matrix (control plane), in-sim with continuous safety oracles +
  # recovery measurement: single-region isolation, leader isolation, asymmetric, partial,
  # gray-failure, clock-skew. The Porcupine full-history linearizability check over a fault
  # history is gate-2's linzgate (CI, Go); the edge fan-out partition is gate-3's E2E phase 3 +
  # EdgeReBootstrapOnDisconnectTest; the live iptables partition is gate-1's rr-002-blackhole-drill.sh.
  run_tests partition configd-testkit "PartitionMatrixTest"
  echo "GATE-4 partition: OK (C — §12 isolation/leader/asymmetric/partial/gray/clock-skew; safety + recovery)"
}

step_overload() {
  # Overload under chaos: control-plane write flood (OVERLOADED shed + bounded-plateau
  # queue + recovery) and the post-partition reconnect storm (a fleet of edges all DISCONNECTED
  # then healed at once — all recover to CURRENT, none terminal). Fan-out admission/queue bounds
  # are pinned by FanOutServerAdmissionBoundTest / DemotionNoticeBackpressureTest / the edge-chaos
  # legs above.
  run_tests overload configd-testkit "OverloadChaosTest"
  echo "GATE-4 overload: OK (D — write-flood backpressure + post-partition reconnect storm)"
}

step_nightly() {
  if [ "${GATE4_SKIP_NIGHTLY:-0}" = "1" ]; then
    echo "GATE-4 nightly: SKIPPED by GATE4_SKIP_NIGHTLY=1 (LOUD: heavy integrated sweeps NOT run this run)"
    return 0
  fi
  run_tests nightly-edge configd-testkit "EdgeIntegratedNightlySweepTest" -Dconfigd.edge.nightly=true
  run_tests nightly-rr095 configd-testkit "Rr095StallSeedsIntegratedRerunTest" -Dconfigd.rr095.rerun=true
  # Sustained mini-Jepsen (mixed-fault, long horizon) against the fully-fixed system.
  run_tests nightly-jepsen configd-testkit "MiniJepsenSweepTest" \
    -Dconfigd.minijepsen.seeds=16 -Dconfigd.minijepsen.horizon=20000
  echo "GATE-4 nightly: OK (integrated edge 10k sweep + RR-095 integrated rerun + mini-Jepsen, 0 safety violations)"
}

main() {
  echo "=== GATE-4 (Session 4: durability, recovery & chaos) — logs in $LOGDIR ==="
  step_gate3
  step_install
  step_liveness
  step_reconfig
  step_durability
  step_edgechaos
  step_partition
  step_overload
  step_nightly
  echo "=== GATE-4: ALL STEPS GREEN ==="
}

main "$@"
