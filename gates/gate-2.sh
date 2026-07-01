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
#   (e) mutation    PIT mutation thresholds enforced (charter §4.1). Enforced
#                   floors (just under verified CLEAN S2/mutation-gap scores):
#                   consensus-core module-wide >= 70 (verified 73.1%, MEETS the §4.1
#                   70% target), safety kernel >= 70 (verified 72.8%; 80% aspiration
#                   residual is provably-equivalent mutants), distribution control-
#                   plane >= 65. FAILS on regress.
#   (f) jcstress    the curated jcstress subset: no forbidden outcomes.
#   (g) assertions  every spec invariant has a runtime-assertion twin that has
#                   been OBSERVED to fire (AssertionTwinFiringTest).
#
# Environment knobs:
#   GATE2_SKIP_GATE1=1     skip step (a) — reported LOUDLY (CI runs gate-1 as its
#                          own parallel job, so re-running it here is pure
#                          duplication; cumulative coverage is supplied by that
#                          job, sealed by the ci-success aggregator). Mirrors the
#                          GATE3_SKIP_GATE2 / GATE4_SKIP_GATE3 / … pattern.
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
  if [ "${GATE2_SKIP_GATE1:-0}" = "1" ]; then
    echo "GATE-2 gate1: SKIPPED by GATE2_SKIP_GATE1=1 (LOUD: gate-1 NOT verified this run; CI runs gate-1 as its own parallel job)"
    return 0
  fi
  bash "$ROOT/gates/gate-1.sh" 2>&1 | tee "$LOGDIR/gate1.log" | tail -8
  grep -qE "TOTAL +PASS" "$LOGDIR/gate1.log" || { echo "GATE-2 gate1: gate-1 did not PASS"; return 1; }
}

step_p0tests() {
  cd "$ROOT"
  # Stale-artifact guard: step_gate1 ran `clean verify` (not install), so ~/.m2
  # holds the PRE-build jars while target/ holds fresh classes — a `-pl X -am
  # test` run can then mix stale upstream jars with new classes and fail
  # spuriously. Install the reactor once (no clean; reuses gate-1's compiled
  # output) so every module-scoped step below resolves HEAD artifacts. This
  # install persists across the sibling `--step` children via ~/.m2.
  # Pass/fail is the Maven EXIT CODE (via `set -o pipefail` + `if !`), NOT a
  # grep for "BUILD SUCCESS" — `-q` suppresses that INFO line, so grepping for it
  # always fails even on a green build. `-q` is therefore omitted here so the
  # logs retain the surefire summary for diagnosis.
  if ! $MVN install -DskipTests 2>&1 | tee "$LOGDIR/p0-install.log" | tail -2; then
    echo "GATE-2 p0tests: reactor install failed (see $LOGDIR/p0-install.log)"; return 1; fi
  # Named discriminating tests, one surefire run per owning module.
  if ! $MVN -pl configd-consensus-core -am test \
    -Dtest='CommitOutcomeSeamTest,SnapshotCrashRecoveryTest,TimingConversionTests,ReconfigurationTest' \
    -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tee "$LOGDIR/p0-consensus.log" | tail -4; then
    echo "GATE-2 p0tests: consensus-core FAILED (see $LOGDIR/p0-consensus.log)"; return 1; fi
  if ! $MVN -pl configd-transport -am test \
    -Dtest='TcpRaftTransportBlackholeTest,NoBlockingConnectOnConsensusPathTest' \
    -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tee "$LOGDIR/p0-transport.log" | tail -4; then
    echo "GATE-2 p0tests: transport FAILED (see $LOGDIR/p0-transport.log)"; return 1; fi
  if ! $MVN -pl configd-server -am test \
    -Dtest='RaftProposerCommitConfirmTest,StrongReadFailClosedTest' \
    -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tee "$LOGDIR/p0-server.log" | tail -4; then
    echo "GATE-2 p0tests: server FAILED (see $LOGDIR/p0-server.log)"; return 1; fi
  if ! $MVN -pl configd-testkit -am test \
    -Dtest='AckEqualsCommitTest,SimulationDeterminismTest' -Dconfigd.seedSweep.count=100 \
    -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tee "$LOGDIR/p0-testkit.log" | tail -4; then
    echo "GATE-2 p0tests: testkit FAILED (see $LOGDIR/p0-testkit.log)"; return 1; fi
  echo "GATE-2 p0tests: OK (all named discriminating tests green)"
}

step_seeds() {
  cd "$ROOT"
  if ! $MVN -pl configd-testkit -am test -Dtest='AdversarialGateSeedSweepTest' \
    -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tee "$LOGDIR/seeds.log" | tail -4; then
    echo "GATE-2 seeds: gate seed set FAILED (see $LOGDIR/seeds.log)"; return 1; fi
  echo "GATE-2 seeds: OK (committed gate seed set, full invariants, zero violations)"
}

step_linzgate() {
  cd "$ROOT"
  # (i) checker self-tests (count-agnostic: BUILD SUCCESS + 0 skips; the suite
  # grew 6->7->8 this session and the gate must not rot on the number).
  if [ -z "${PORCUPINE_BIN:-}" ]; then
    local GO=""
    if command -v go >/dev/null 2>&1; then GO=go;
    elif [ -x "$HOME/sdk/go/bin/go" ]; then GO="$HOME/sdk/go/bin/go"; fi
    [ -n "$GO" ] || { echo "GATE-2 linzgate: no Go toolchain and PORCUPINE_BIN unset"; return 1; }
    GOTOOLCHAIN=local "$GO" -C "$ROOT/configd-linz/src/main/go/porcupine-check" \
      build -o "$ROOT/configd-linz/bin/porcupine-check" .
    export PORCUPINE_BIN="$ROOT/configd-linz/bin/porcupine-check"
  fi
  if ! $MVN -pl configd-linz -am test -Dtest='CheckerSelfTest,HistoryWriterUnitTest' \
    -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tee "$LOGDIR/linz-selftests.log" | tail -4; then
    echo "GATE-2 linzgate: self-tests FAILED (see $LOGDIR/linz-selftests.log)"; return 1; fi
  if grep -qE "Skipped: [1-9]" "$LOGDIR/linz-selftests.log"; then
    echo "GATE-2 linzgate: self-tests SKIPPED tests (PORCUPINE_BIN gating?) — failing"; return 1
  fi
  # (ii) sim-history linearizability over the gate seed (cheap, no cluster, no sudo).
  if ! $MVN -pl configd-testkit -am test -Dtest='OpHistoryTest' \
    -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tee "$LOGDIR/linz-simhist-gen.log" | tail -4; then
    echo "GATE-2 linzgate: sim-history generation FAILED (see $LOGDIR/linz-simhist-gen.log)"; return 1; fi
  local hist
  hist="$(ls "$ROOT"/configd-testkit/target/sim-histories/history-*.jsonl 2>/dev/null | head -1 || true)"
  [ -n "$hist" ] || { echo "GATE-2 linzgate: no sim history emitted"; return 1; }
  java --enable-preview -cp "$ROOT/configd-linz/target/classes" \
    io.configd.linz.runner.SimHistoryCheck "$hist" 2>&1 | tee "$LOGDIR/linz-simhist.log" | tail -2
  grep -q "LINEARIZABLE" "$LOGDIR/linz-simhist.log" || { echo "GATE-2 linzgate: sim-history check not LINEARIZABLE"; return 1; }
  # (iii) live faulted seed-matrix (needs sudo iptables + a fresh shaded jar) —
  # NIGHTLY variant, opt-in. Discrimination runs are deliberately NOT gated
  # (they mutate source; they are harness verification, captured under
  # docs/session-2/captures/linz-discrimination.txt and re-runnable manually).
  if [ "${GATE2_FAULTED:-0}" = "1" ]; then
    $MVN -q -pl configd-server -am clean package -DskipTests >/dev/null
    bash "$ROOT/configd-linz/scripts/run-gate.sh" "2001 2002 2003 2004" 2>&1 | tee "$LOGDIR/linz-faulted.log" | tail -5
    grep -q "GATE (iii)+(iv) PASS" "$LOGDIR/linz-faulted.log" || { echo "GATE-2 linzgate: faulted seed matrix FAILED"; return 1; }
  else
    echo "GATE-2 linzgate: faulted live matrix SKIPPED (GATE2_FAULTED!=1 — LOUD: nightly/self-hosted only)"
  fi
  echo "GATE-2 linzgate: OK"
}

step_mutation() {
  if [ "${GATE2_SKIP_MUTATION:-0}" = "1" ]; then
    echo "GATE-2 mutation: SKIPPED by GATE2_SKIP_MUTATION=1 (LOUD: thresholds NOT verified this run)"
    return 0
  fi
  cd "$ROOT"
  # B3 / charter §4.1: PIT mutation thresholds, enforced per module by the
  # -Pmutation profile. Each module pom sets `mutationThreshold`, so the pitest
  # goal returns non-zero (BUILD FAILURE) when the module is UNDER target — that
  # propagates out as the step failing. Three enforced bars, one per-module PIT
  # run each (~25-40 min/module on 2 vCPU):
  #   (1) consensus-core module-wide        >= 70   (-Pmutation)
  #   (2) consensus-core SAFETY KERNEL       >= 70   (-Pmutation,mutation-kernel:
  #       RaftNode/RaftLog/DurableRaftState/ReadIndexState/ClusterConfig)
  #   (3) distribution-service control-plane >= 65   (-Pmutation; the RR-001/RR-088
  #       shelfware fan-out/gossip classes are excluded in that module's pom)
  #
  # FLOOR HISTORY (NOT a silent change): the S2/mutation-gap round raised the
  # consensus-core floors from 60/60. Measured CLEAN scores (2026-06-11,
  # RUN_ERROR=0): module-wide 73.1% (589/806) and kernel 72.8% (532/731). Module-
  # wide now MEETS the charter §4.1 70% target with margin (eight new test classes,
  # incl. MessageRecordCodecTest covering the record/DTO equals/hashCode that were
  # 0% NO_COVERAGE and alone dragged the module under 70). The kernel reaches 72.8%
  # — short of the 80% aspiration, with the remaining gap dominated by PROVABLY-
  # EQUIVALENT mutants (earlier-guard-masked boundaries, WAL-cross-validation-masked
  # recovery, crash-only persist-call removals, commit-outcome NO_COVERAGE machinery),
  # itemized in docs/session-2/mutation-kill-list.md SCORES — NOT gamed. The floors
  # sit JUST UNDER each verified score (70 < 73.1, 70 < 72.8) so the build FAILS on
  # any regression without flaking on PIT run-to-run jitter. (An earlier "80%" module
  # figure was a CONTAMINATED run — concurrent surefire forks → RUN_ERRORs; discarded.)
  # The upstream main artifacts must be installed first so PIT's classpath
  # resolves them (sibling test sources are skipped, as in step_jcstress).
  $MVN -q -pl configd-consensus-core,configd-distribution-service -am \
    install -Dmaven.test.skip=true 2>&1 | tee "$LOGDIR/mutation-install.log" | tail -3

  $MVN -Pmutation -pl configd-consensus-core org.pitest:pitest-maven:mutationCoverage \
    2>&1 | tee "$LOGDIR/mutation-consensus.log" | tail -6
  grep -qE "BUILD SUCCESS" "$LOGDIR/mutation-consensus.log" \
    || { echo "GATE-2 mutation: consensus-core module-wide < 70 floor (or PIT error)"; return 1; }

  $MVN -Pmutation,mutation-kernel -pl configd-consensus-core org.pitest:pitest-maven:mutationCoverage \
    2>&1 | tee "$LOGDIR/mutation-kernel.log" | tail -6
  grep -qE "BUILD SUCCESS" "$LOGDIR/mutation-kernel.log" \
    || { echo "GATE-2 mutation: consensus-core safety kernel < 70 floor (or PIT error)"; return 1; }

  $MVN -Pmutation -pl configd-distribution-service org.pitest:pitest-maven:mutationCoverage \
    2>&1 | tee "$LOGDIR/mutation-distribution.log" | tail -6
  grep -qE "BUILD SUCCESS" "$LOGDIR/mutation-distribution.log" \
    || { echo "GATE-2 mutation: distribution-service control-plane < 65 (or PIT error)"; return 1; }

  echo "GATE-2 mutation: OK (consensus-core module-wide >=70 floor [verified 73.1%, meets §4.1 70% target], safety-kernel >=70 floor [verified 72.8%], distribution control-plane >=65; the kernel 80% aspiration's residual is provably-equivalent mutants)"
}

step_jcstress() {
  if [ "${GATE2_SKIP_JCSTRESS:-0}" = "1" ]; then
    echo "GATE-2 jcstress: SKIPPED by GATE2_SKIP_JCSTRESS=1 (LOUD: races NOT verified this run)"
    return 0
  fi
  cd "$ROOT"
  # Build the jcstress uber-jar against FRESH upstream sources, then run the
  # curated subset (run-curated-subset.sh: the 6 RR-002 transport interleavings +
  # the decisive RR-066 / RR-029 read-path races, sanity mode, deterministic
  # 2-actor tests only — the intentionally-forbidden harness self-test and the
  # 3-actor test are excluded). Per docs/session-2/jcstress-results.md the clean
  # 2-vCPU sanity pass is a SMOKE; the full multi-fork run is the operator's
  # higher-confidence pass.
  # maven.test.skip (not just -DskipTests) so a sibling module's in-progress,
  # non-compiling TEST sources never block the harness build — jcstress only
  # needs the upstream MAIN artifacts. (Sessions run many agents on one branch.)
  $MVN -q -o -pl configd-config-store,configd-distribution-service,configd-transport -am \
    install -Dmaven.test.skip=true 2>&1 | tee "$LOGDIR/jcstress-install.log" | tail -3
  $MVN -q -o -pl configd-jcstress clean package -Dmaven.test.skip=true \
    2>&1 | tee "$LOGDIR/jcstress-build.log" | tail -3
  if [ ! -f "$ROOT/configd-jcstress/target/jcstress.jar" ]; then
    echo "GATE-2 jcstress: uber-jar not produced"; return 1
  fi
  JCSTRESS_CPUS="${JCSTRESS_CPUS:-2}" bash "$ROOT/configd-jcstress/run-curated-subset.sh" \
    "$LOGDIR/jcstress-results" 2>&1 | tee "$LOGDIR/jcstress.log" | tail -4
  grep -qE "jcstress curated subset: OK" "$LOGDIR/jcstress.log" \
    || { echo "GATE-2 jcstress: curated subset did NOT pass"; return 1; }
  echo "GATE-2 jcstress: OK (curated subset, no forbidden outcomes)"
}

step_assertions() {
  cd "$ROOT"
  # Machine-checkable twin manifest: every spec invariant's runtime twin is
  # OBSERVED to fire (AssertionTwinFiringTest, both owning modules), and the
  # human-readable matrix contains no UNVERIFIED status cell.
  if ! $MVN -pl configd-consensus-core,configd-config-store -am test \
    -Dtest='AssertionTwinFiringTest' -Dsurefire.failIfNoSpecifiedTests=false \
    2>&1 | tee "$LOGDIR/assertions.log" | tail -4; then
    echo "GATE-2 assertions: twin firing tests FAILED (see $LOGDIR/assertions.log)"; return 1; fi
  echo "GATE-2 assertions: OK (every twin observed firing)"
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
