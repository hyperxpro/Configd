#!/usr/bin/env bash
# =============================================================================
# gate-4.sh — Configd Session-4 cumulative machine-verifiable gate
# -----------------------------------------------------------------------------
# Authored by the Session-4 lead. Cumulative with gates 1+2+3: a green gate-4
# REQUIRES a green gate-3 (step a; gate-3 runs gate-2 which runs gate-1). Exits
# non-zero on ANY failure; NO silent placeholders (the RR-012/RR-085 lesson,
# inherited from the prior gates). A gate that is wired but never executed is
# the phantom-CI failure mode — every step asserts a real BUILD SUCCESS.
#
# WHAT A GREEN GATE-4 PROVES (charter §7):
#   (a) gate3       gates 1+2+3 still green (cumulative; no regression of the
#                   control plane or the edge data plane while chaos hardening
#                   landed). In CI gate-3 runs as its own job, so the gate-4 job
#                   sets GATE4_SKIP_GATE3=1 (coverage via the job dependency).
#   (b) liveness    RR-103/RR-095 closure — the formerly-stalling seed
#                   regression suite + first-class liveness:
#                   Rr103InflightWindowRecoveryTest (the inflight-window leak
#                   fix, recovery = 1 heartbeat), LivenessBoundedProgressSweepTest
#                   (200 seeds, bounded post-heal progress, 0 violations),
#                   Rr095StallSeedDiagnosisTest (all 7 stall seeds diagnosed as
#                   never-healed artifacts). [EXP-001/EXP-002]
#   (c) reconfig    D§2 reconfiguration-under-fault: ReconfigurationTest
#                   (incl. JointConsensusEndToEnd — split-brain prevention
#                   (M1) + mid-joint crash recovery (M2)). [EXP-004]
#   (d) durability  B-rest consensus durability cells: RaftLogCompactionTriggerTest
#                   (RR-005 compaction reachable, M-compact), SnapshotCrashRecoveryTest
#                   (RR-003 durable-prefix + fsync-lie EXP-007), FileStorageTest
#                   (RR-005 long-safe read), MultiRaftDriverTest (compaction fan-out),
#                   StorageEnospcConsensusReactionTest (ENOSPC EXP-008),
#                   ConfigdServerTest (RR-005 tick-loop wiring source-guard +
#                   clean start). [EXP-005..008]
#   (e) edgechaos   A3 owed edge-chaos legs: FanOutSessionCoreTest (prod-threshold
#                   ack-lag A3-2 + wedged-transport A3-3), GovernorBoundedIdentityMapChurnTest
#                   (governor churn A3-4), EdgeTransportMtlsTest (accept-then-blackhole
#                   handshake-timeout A3-1, real socket). [EXP-005]
#   (f) nightly     HEAVY/long integrated sweeps — NOT in the CI subset (run in the
#                   nightly chaos job): EdgeIntegratedNightlySweepTest
#                   (-Dconfigd.edge.nightly=true, 10k ticks) + Rr095StallSeedsIntegratedRerunTest
#                   (-Dconfigd.rr095.rerun=true). The control-plane 10k SeedSweepTest
#                   already runs in the build-and-test job; not duplicated here.
#
# NOT YET IN GATE-4 (PENDING workstreams — added when they land; see
# docs/session-4/PROGRESS.md): Workstream C (partition/WAN matrix + linearizability
# over failover histories), D overload (post-partition reconnect storm), E
# (sustained mini-Jepsen). gate-4 covers the DONE workstreams (A, D§2, A3, B-rest).
#
# Environment knobs (CI must not set the skips on the nightly full run):
#   GATE4_SKIP_GATE3=1    skip step (a) — reported LOUDLY (CI runs gate-3 as its
#                         own job; local iteration only)
#   GATE4_SKIP_NIGHTLY=1  skip step (f) — reported LOUDLY (the heavy integrated
#                         sweeps; ~3-5 min). Default-skipped on push/PR; CI runs
#                         it on the nightly schedule.
#   GATE3_* / GATE2_* / GATE1_*   forwarded to the underlying gates.
# =============================================================================
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
  # C — partition & WAN matrix (control plane), in-sim with continuous safety oracles +
  # recovery measurement: single-region isolation, leader isolation, asymmetric, partial,
  # gray-failure, clock-skew. The Porcupine full-history linearizability check over a fault
  # history is gate-2's linzgate (CI, Go); the edge fan-out partition is gate-3's E2E phase 3 +
  # EdgeReBootstrapOnDisconnectTest; the live iptables partition is gate-1's rr-002 drill.
  run_tests partition configd-testkit "PartitionMatrixTest"
  echo "GATE-4 partition: OK (C — §12 isolation/leader/asymmetric/partial/gray/clock-skew; safety + recovery)"
}

step_nightly() {
  if [ "${GATE4_SKIP_NIGHTLY:-0}" = "1" ]; then
    echo "GATE-4 nightly: SKIPPED by GATE4_SKIP_NIGHTLY=1 (LOUD: heavy integrated sweeps NOT run this run)"
    return 0
  fi
  run_tests nightly-edge configd-testkit "EdgeIntegratedNightlySweepTest" -Dconfigd.edge.nightly=true
  run_tests nightly-rr095 configd-testkit "Rr095StallSeedsIntegratedRerunTest" -Dconfigd.rr095.rerun=true
  echo "GATE-4 nightly: OK (integrated edge 10k sweep + RR-095 integrated rerun, 0 safety violations)"
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
  step_nightly
  echo "=== GATE-4: ALL STEPS GREEN ==="
}

main "$@"
