#!/usr/bin/env bash
# =============================================================================
# gate-2.sh — Configd Session-2 cumulative machine-verifiable gate
# -----------------------------------------------------------------------------
# Authored by the Session-2 lead. Cumulative with gate-1: a green gate-2
# REQUIRES a green gate-1 (step a). Exits non-zero on ANY failure, including
# any step that is declared but not yet wired (NO silent placeholders — the
# RR-012/RR-085 lesson: a gate that can pass while checking nothing is worse
# than no gate).
#
# WHAT A GREEN GATE-2 PROVES (charter §5):
#   (a) gate1       gate-1 green (build+suite, linz self-tests, JMH exec,
#                   TLC smoke, multinode smoke) — the RR-094 fix holds.
#   (b) p0tests     the discriminating tests for the Session-2 P0/P1 fixes
#                   pass, named explicitly (survives suite re-organisation):
#                   RR-004 AckEqualsCommitTest + CommitOutcomeSeamTest +
#                          RaftProposerCommitConfirmTest
#                   RR-003 SnapshotCrashRecoveryTest
#                   RR-002 TcpRaftTransportBlackholeTest +
#                          NoBlockingConnectOnConsensusPathTest
#                   RR-006 TimingConversionTests   RR-020 StrongReadFailClosedTest
#                   RR-018 ReconfigurationTest     RR-010 SimulationDeterminismTest
#   (c) seeds       the committed adversarial gate seed set (>= 500 seeds,
#                   gates/../configd-testkit/src/test/resources/gate/
#                   adversarial-gate-seeds.txt) with FULL invariant checking,
#                   zero safety violations (AdversarialGateSeedSweepTest).
#   (d) linzgate    linearizability over the gate seed set: sim-emitted
#                   operation histories checked by the Porcupine checker.
#                   (The live faulted multi-node linz run needs sudo/iptables
#                   and is the NIGHTLY variant — documented, not gated here.)
#   (e) mutation    PIT mutation thresholds enforced (charter §4.1):
#                   consensus-core safety kernel >= 80%, consensus-core >= 70%,
#                   distribution-service control-plane >= 65%. FAILS on regress.
#   (f) jcstress    the curated jcstress subset: no forbidden outcomes.
#   (g) assertions  the runtime-assertion-twin manifest is complete and
#                   machine-checked: every spec invariant has a twin that has
#                   been OBSERVED to fire (AssertionTwinFiringTest +
#                   docs/session-2/assertion-verification.md contains no
#                   UNVERIFIED row).
#
# Environment knobs:
#   GATE2_SKIP_MUTATION=1  skip step (e) — reported LOUDLY (local convenience;
#                          CI must not set it; ~25 min/module on 2 vCPU)
#   GATE2_SKIP_JCSTRESS=1  skip step (f) — reported LOUDLY
#   GATE1_LOG_DIR / PORCUPINE_BIN — forwarded to gate-1
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOGDIR="${GATE2_LOG_DIR:-$(mktemp -d /tmp/gate2-XXXXXX)}"
mkdir -p "$LOGDIR"
export GATE2_LOG_DIR="$LOGDIR"

MVN="$ROOT/mvnw -B"

step_gate1() {
  bash "$ROOT/gates/gate-1.sh" 2>&1 | tee "$LOGDIR/gate1.log" | tail -8
  grep -qE "TOTAL +PASS" "$LOGDIR/gate1.log" || { echo "GATE-2 gate1: gate-1 did not PASS"; return 1; }
}

step_p0tests() {
  cd "$ROOT"
  # Named discriminating tests, one surefire run per owning module.
  $MVN -q -pl configd-consensus-core -am test \
    -Dtest='CommitOutcomeSeamTest,SnapshotCrashRecoveryTest,TimingConversionTests,ReconfigurationTest' \
    -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tee "$LOGDIR/p0-consensus.log" | tail -3
  $MVN -q -pl configd-transport -am test \
    -Dtest='TcpRaftTransportBlackholeTest,NoBlockingConnectOnConsensusPathTest' \
    -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tee "$LOGDIR/p0-transport.log" | tail -3
  $MVN -q -pl configd-server -am test \
    -Dtest='RaftProposerCommitConfirmTest,StrongReadFailClosedTest' \
    -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tee "$LOGDIR/p0-server.log" | tail -3
  $MVN -q -pl configd-testkit -am test \
    -Dtest='AckEqualsCommitTest,SimulationDeterminismTest' -Dconfigd.seedSweep.count=100 \
    -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tee "$LOGDIR/p0-testkit.log" | tail -3
  for f in p0-consensus p0-transport p0-server p0-testkit; do
    grep -qE "BUILD SUCCESS" "$LOGDIR/$f.log" || { echo "GATE-2 p0tests: $f FAILED"; return 1; }
  done
  echo "GATE-2 p0tests: OK (all named discriminating tests green)"
}

step_seeds() {
  cd "$ROOT"
  $MVN -q -pl configd-testkit -am test -Dtest='AdversarialGateSeedSweepTest' \
    -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tee "$LOGDIR/seeds.log" | tail -3
  grep -qE "BUILD SUCCESS" "$LOGDIR/seeds.log" || { echo "GATE-2 seeds: gate seed set FAILED"; return 1; }
  echo "GATE-2 seeds: OK (committed gate seed set, full invariants, zero violations)"
}

step_linzgate() {
  echo "GATE-2 linzgate: NOT WIRED — failing loudly (B5 gate-2 step spec pending)"; return 1
}

step_mutation() {
  if [ "${GATE2_SKIP_MUTATION:-0}" = "1" ]; then
    echo "GATE-2 mutation: SKIPPED by GATE2_SKIP_MUTATION=1 (LOUD: thresholds NOT verified this run)"
    return 0
  fi
  echo "GATE-2 mutation: NOT WIRED — failing loudly (B3 PIT profile pending)"; return 1
}

step_jcstress() {
  if [ "${GATE2_SKIP_JCSTRESS:-0}" = "1" ]; then
    echo "GATE-2 jcstress: SKIPPED by GATE2_SKIP_JCSTRESS=1 (LOUD: races NOT verified this run)"
    return 0
  fi
  echo "GATE-2 jcstress: NOT WIRED — failing loudly (B6 curated subset pending)"; return 1
}

step_assertions() {
  echo "GATE-2 assertions: NOT WIRED — failing loudly (B7 twin manifest pending)"; return 1
}

# ---- child-process dispatch -------------------------------------------------
if [ "${1:-}" = "--step" ]; then
  "step_$2"
  exit $?
fi

STEPS=(gate1 p0tests seeds linzgate mutation jcstress assertions)
declare -A RESULT TIMES
overall=PASS
for s in "${STEPS[@]}"; do
  t0=$(date +%s)
  if bash "${BASH_SOURCE[0]}" --step "$s"; then RESULT[$s]=PASS; else RESULT[$s]=FAIL; overall=FAIL; fi
  TIMES[$s]=$(( $(date +%s) - t0 ))
done
echo
echo "==================== GATE-2 SUMMARY ===================="
for s in "${STEPS[@]}"; do printf "%-12s %-6s %ss\n" "$s" "${RESULT[$s]}" "${TIMES[$s]}"; done
printf "%-12s %-6s\n" "TOTAL" "$overall"
echo "logs: $LOGDIR"
[ "$overall" = "PASS" ]
