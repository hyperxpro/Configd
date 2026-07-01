#!/usr/bin/env bash
# =============================================================================
# gate-3.sh — Configd Session-3 cumulative machine-verifiable gate
# -----------------------------------------------------------------------------
# Authored by the Session-3 lead. Cumulative with gates 1+2: a green gate-3
# REQUIRES a green gate-2 (step a; gate-2 itself runs gate-1). Exits non-zero
# on ANY failure; NO silent placeholders (the RR-012/RR-085 lesson, inherited
# from gate-2's charter).
#
# WHAT A GREEN GATE-3 PROVES (charter §5):
#   (a) gate2       gates 1+2 still green (cumulative; no regression of the
#                   control-plane guarantees while the data plane was built).
#   (b) map         the contract→test map's end state: ZERO failing-captured,
#                   ZERO unimplemented rows, and the CONTRACT-MAP-SUMMARY line
#                   exactly matches the committed expectation
#                   (gates/gate3-map-expectation.txt, owned by contract-qa —
#                   any map drift is LOUD, and PARTIAL rows are tolerated only
#                   because each carries an explicit future-session owner,
#                   audited in the contract-qa audit).
#   (c) edgeseeds   the committed 507-seed gate set with the FULL V1 edge
#                   invariant set (monotonicity, no-stale-overwrite, eventual
#                   delivery, snapshot–delta equivalence): zero safety
#                   violations (EdgeAdversarialGateSeedSweepTest) AND the
#                   determinism-digest byte-identity pin (EdgeSeedCompatTest).
#   (d) probe       the propagation probe runs in BOTH modes and emits
#                   histograms (mechanism check, not perf targets):
#                   sim mode (ProbeMechanismTest + the staleness-distribution
#                   sim test) and live mode (LivePropagationProbeMain
#                   --mode boundary AND --mode edge, each emitting
#                   PROBE-HISTOGRAM: lines; edge mode drives a REAL in-process
#                   EdgeNodeMain through the wire path).
#   (e) walk        the slow-consumer state machine walk (charter §4 C4):
#                   SlowConsumerStateMachineWalkTest — the full documented
#                   machine in recorded order + byte-equal replay determinism.
#   (f) e2e         the Compose E2E scenario (gates/e2e-compose-scenario.sh):
#                   3 CP + 3 edges + bootstrap joiner, four adversarial phases,
#                   throttle-robust, no sleeps-as-sync.
#   (g) gc          the CT-34 hot-path law, mechanically: jmh-gc-check.sh
#                   asserts gc.alloc.rate.norm == 0 B/op on the structurally-
#                   zero edge read-path legs and saves the artifact.
#   (h) mutation3   PIT floors for the modules this session built or reworked
#                   (>= 65 from day one, charter §5): configd-edge-cache and
#                   configd-edge-node (-Pmutation per-module poms;
#                   configd-distribution-service's floor is enforced by
#                   gate-2 step (e) and is not duplicated here).
#
# Environment knobs (CI must not set the skips on the nightly full run):
#   GATE3_SKIP_MUTATION=1   skip step (h) — reported LOUDLY (~10-15 min)
#   GATE3_SKIP_E2E=1        skip step (f) — reported LOUDLY (needs docker;
#                           ~5-8 min incl. image builds)
#   GATE3_SKIP_GATE2=1      skip step (a) — reported LOUDLY (local iteration
#                           only; CI runs gate-2 as its own job, see ci.yml)
#   GATE2_* / GATE1_*       forwarded to the underlying gates
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOGDIR="${GATE3_LOG_DIR:-$(mktemp -d /tmp/gate3-XXXXXX)}"
mkdir -p "$LOGDIR"
export GATE3_LOG_DIR="$LOGDIR"

MVN="$ROOT/mvnw -B"
MAP="$ROOT/gates/contract-test-map.md"
EXPECT="$ROOT/gates/gate3-map-expectation.txt"

step_gate2() {
  if [ "${GATE3_SKIP_GATE2:-0}" = "1" ]; then
    echo "GATE-3 gate2: SKIPPED by GATE3_SKIP_GATE2=1 (LOUD: gates 1+2 NOT verified this run)"
    return 0
  fi
  bash "$ROOT/gates/gate-2.sh" 2>&1 | tee "$LOGDIR/gate2.log" | tail -5
  echo "GATE-3 gate2: OK (cumulative — gates 1+2 green)"
}

step_map() {
  # (b) The map's end state. Two independent checks so neither can rot alone:
  #  1. the structural rule: zero FAILING-CAPTURED, zero UNIMPLEMENTED;
  #  2. the exact-summary pin vs the contract-qa-owned expectation file
  #     (drift in EITHER direction is loud — a silently-improved map is a map
  #      whose gate expectation nobody audited).
  local summary expected
  summary="$(grep -E '^CONTRACT-MAP-SUMMARY:' "$MAP" | tail -1)"
  [ -n "$summary" ] || { echo "GATE-3 map: CONTRACT-MAP-SUMMARY line MISSING"; return 1; }
  echo "$summary" | grep -q 'failing-captured=0' \
    || { echo "GATE-3 map: FAILING-CAPTURED rows present: $summary"; return 1; }
  echo "$summary" | grep -q 'unimplemented=0' \
    || { echo "GATE-3 map: UNIMPLEMENTED rows present: $summary"; return 1; }
  [ -f "$EXPECT" ] || { echo "GATE-3 map: expectation file $EXPECT MISSING"; return 1; }
  expected="$(grep -E '^CONTRACT-MAP-SUMMARY:' "$EXPECT" | tail -1)"
  if [ "$summary" != "$expected" ]; then
    echo "GATE-3 map: summary drift —"
    echo "  map:      $summary"
    echo "  expected: $expected"
    echo "  (contract-qa owns gates/gate3-map-expectation.txt; update it WITH the map change)"
    return 1
  fi
  echo "GATE-3 map: OK ($summary)"
}

step_edgeseeds() {
  cd "$ROOT"
  $MVN -q -pl configd-testkit -am install -DskipTests \
    2>&1 | tee "$LOGDIR/edgeseeds-install.log" | tail -3
  $MVN -pl configd-testkit test \
    -Dtest=EdgeAdversarialGateSeedSweepTest,EdgeSeedCompatTest \
    -Dsurefire.failIfNoSpecifiedTests=false \
    2>&1 | tee "$LOGDIR/edgeseeds.log" | grep -E "EDGE-GATE-SUMMARY|Tests run|BUILD" | tail -6
  grep -q "BUILD SUCCESS" "$LOGDIR/edgeseeds.log" \
    || { echo "GATE-3 edgeseeds: FAILED (see $LOGDIR/edgeseeds.log)"; return 1; }
  grep -q "safetyViolations=0" "$LOGDIR/edgeseeds.log" \
    || { echo "GATE-3 edgeseeds: EDGE-GATE-SUMMARY missing or non-zero safety violations"; return 1; }
  echo "GATE-3 edgeseeds: OK (507 seeds, 0 safety violations, digest byte-identical)"
}

step_probe() {
  cd "$ROOT"
  # Sim mode (logical time — the mechanism's correctness).
  $MVN -pl configd-testkit test \
    -Dtest=ProbeMechanismTest,EdgeStalenessDistributionSimTest \
    -Dsurefire.failIfNoSpecifiedTests=false \
    2>&1 | tee "$LOGDIR/probe-sim.log" | grep -E "Tests run|BUILD" | tail -3
  grep -q "BUILD SUCCESS" "$LOGDIR/probe-sim.log" \
    || { echo "GATE-3 probe: sim mode FAILED"; return 1; }

  # Live modes (wall time — both halves; honest-caveat output, NOT a perf gate).
  # benchmarks.jar carries the probe main; package it fresh (shaded-jar trap).
  $MVN -q -pl configd-testkit -am package -DskipTests \
    2>&1 | tee "$LOGDIR/probe-package.log" | tail -3
  local jar="$ROOT/configd-testkit/target/benchmarks.jar"
  [ -f "$jar" ] || { echo "GATE-3 probe: $jar missing after package"; return 1; }

  java --enable-preview -cp "$jar" io.configd.probe.LivePropagationProbeMain \
    --mode boundary --writes 100 \
    2>&1 | tee "$LOGDIR/probe-live-boundary.log" | grep -E "PROBE-HISTOGRAM|drove" | tail -4
  grep -q "PROBE-HISTOGRAM: scope=global" "$LOGDIR/probe-live-boundary.log" \
    || { echo "GATE-3 probe: live BOUNDARY mode emitted no histogram"; return 1; }

  java --enable-preview -cp "$jar" io.configd.probe.LivePropagationProbeMain \
    --mode edge --writes 100 \
    2>&1 | tee "$LOGDIR/probe-live-edge.log" | grep -E "PROBE-HISTOGRAM|drove|edge applied" | tail -5
  grep -q "PROBE-HISTOGRAM: scope=global" "$LOGDIR/probe-live-edge.log" \
    || { echo "GATE-3 probe: live EDGE mode emitted no histogram"; return 1; }

  echo "GATE-3 probe: OK (sim mode green; live boundary + live edge histograms emitted)"
}

step_walk() {
  cd "$ROOT"
  $MVN -pl configd-testkit test \
    -Dtest=SlowConsumerStateMachineWalkTest \
    -Dsurefire.failIfNoSpecifiedTests=false \
    2>&1 | tee "$LOGDIR/walk.log" | grep -E "Tests run|BUILD" | tail -3
  grep -q "BUILD SUCCESS" "$LOGDIR/walk.log" \
    || { echo "GATE-3 walk: slow-consumer state machine walk FAILED"; return 1; }
  echo "GATE-3 walk: OK (full machine in recorded order + replay determinism + flap scenario)"
}

step_e2e() {
  if [ "${GATE3_SKIP_E2E:-0}" = "1" ]; then
    echo "GATE-3 e2e: SKIPPED by GATE3_SKIP_E2E=1 (LOUD: the runtime data plane NOT verified this run)"
    return 0
  fi
  bash "$ROOT/gates/e2e-compose-scenario.sh" 2>&1 | tee "$LOGDIR/e2e.log" | tail -8
  echo "GATE-3 e2e: OK (four-phase Compose scenario green)"
}

step_gc() {
  bash "$ROOT/gates/jmh-gc-check.sh" 2>&1 | tee "$LOGDIR/gc.log" | tail -4
  echo "GATE-3 gc: OK (CT-34: zero steady-state allocation on the edge read path, artifact saved)"
}

step_mutation3() {
  if [ "${GATE3_SKIP_MUTATION:-0}" = "1" ]; then
    echo "GATE-3 mutation3: SKIPPED by GATE3_SKIP_MUTATION=1 (LOUD: new-module floors NOT verified this run)"
    return 0
  fi
  cd "$ROOT"
  # Floors live in each module's -Pmutation pom config (>= 65, charter §5).
  # distribution-service's floor is gate-2 step (e)'s — not duplicated here.
  $MVN -q -pl configd-edge-cache,configd-edge-node -am install -Dmaven.test.skip=true \
    2>&1 | tee "$LOGDIR/mutation3-install.log" | tail -3
  $MVN -Pmutation -pl configd-edge-cache org.pitest:pitest-maven:mutationCoverage \
    2>&1 | tee "$LOGDIR/mutation3-edge-cache.log" | tail -4
  grep -q "BUILD SUCCESS" "$LOGDIR/mutation3-edge-cache.log" \
    || { echo "GATE-3 mutation3: configd-edge-cache under floor (or PIT error)"; return 1; }
  $MVN -Pmutation -pl configd-edge-node org.pitest:pitest-maven:mutationCoverage \
    2>&1 | tee "$LOGDIR/mutation3-edge-node.log" | tail -4
  grep -q "BUILD SUCCESS" "$LOGDIR/mutation3-edge-node.log" \
    || { echo "GATE-3 mutation3: configd-edge-node under floor (or PIT error)"; return 1; }
  echo "GATE-3 mutation3: OK (edge-cache + edge-node >= 65 floors hold)"
}

main() {
  echo "=== GATE-3 (Session 3: edge data plane) — logs in $LOGDIR ==="
  step_gate2
  step_map
  step_edgeseeds
  step_probe
  step_walk
  step_e2e
  step_gc
  step_mutation3
  echo "=== GATE-3: ALL STEPS GREEN ==="
}

main "$@"
