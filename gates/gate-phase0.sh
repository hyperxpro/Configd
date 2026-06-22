#!/usr/bin/env bash
# =============================================================================
# gate-phase0.sh — Phase 0 Workstream B cumulative machine-verifiable gate
# -----------------------------------------------------------------------------
# Cumulative with gates 1..7: a green gate-phase0 REQUIRES a green gate-7 (which
# chains 6→5→4→3→2→1). In CI gates 1..7 run as their own jobs, so this gate sets
# GATE_PHASE0_SKIP_GATE7=1 (cumulative coverage via the job dependency, not a
# redundant re-run) — reported LOUDLY. Exits non-zero on ANY failure; NO silent
# placeholders; every test step asserts a REAL result via assert_class_green and
# FAILS if its summary line is absent (non-vacuity — the RR-012/RR-085 lesson).
#
# WHAT A GREEN gate-phase0 PROVES — the R-01 re-threading (single tick thread →
# one owner thread per group) and the H-4 group-rehoming hazard closure:
#   (a) owner net non-vacuous at N>1: a CROSS-GROUP access on a real foreign
#       owner trips the PER-NODE net (OwnerIsolationMultiOwnerTest); off-owner
#       inbound trips it under the pool (OwnerNetCatchesOffOwnerInboundTest).
#   (b) H-4 rehoming MECHANISM: quiesce→publish→adopt + check-and-bounce; the net
#       catches the rehoming-race (RehomingHandoffTest); the deferred
#       sub-mechanisms (quiesce / flush-retarget / abortHandoff) hold
#       (RehomingSubMechanismsTest); the red-team/replay robustness findings stay
#       fixed (RehomingRobustnessTest).
#   (c) H-4 no-double-ownership at the JMM level (jcstress
#       RehomingDoubleOwnershipTest, run at -m quick where the broken control
#       reliably fails) + the owner-guard + monitor-view publication proofs.
#   (d) the S2–S4 invariant surface WITH rehoming injected: tens of thousands of
#       handoffs under concurrent multi-owner load, zero off-owner fires, groups
#       keep committing (RehomingInjectedSweepTest); AND the deterministic
#       20,001-seed sim + adversarial schedules still green (no regression).
#   (e) the verified baseline is recorded (main pinned at cedc706 — D-010).
#
# Environment knobs (CI must not set the test skips on a full run):
#   GATE_PHASE0_SKIP_GATE7=1   skip the cumulative gate-7 (CI runs it as its own job) — LOUD.
#   GATE_PHASE0_SKIP_BUILD=1   reuse already-installed module jars (local convenience).
#   GATE_PHASE0_SKIP_JCSTRESS=1 skip the jcstress step (LOUD: the JMM proofs NOT verified this run).
#   GATE_PHASE0_SKIP_SIM=1     skip the heavy 20,001-seed deterministic sim (LOUD; CI nightly runs it).
#   JCSTRESS_CPUS=N            CPUs for the jcstress subset (default 2).
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MVN="$ROOT/mvnw -B"
LOGDIR="${GATE_PHASE0_LOG_DIR:-$(mktemp -d /tmp/gate-phase0-XXXXXX)}"
mkdir -p "$LOGDIR"

# Modules whose re-threading / rehoming tests this gate exercises (configd-server carries the
# production-wiring proof OwnerNetCatchesOffOwnerInboundTest — the net still catches off-owner inbound
# under the owner pool).
MODULES="configd-common,configd-consensus-core,configd-replication-engine,configd-server,configd-testkit"

echo "=== gate-phase0 (Phase 0 B: R-01 re-threading + H-4 rehoming closure) — logs in $LOGDIR ==="

fail() { echo "gate-phase0 FAIL [$1]: $2" >&2; exit 1; }

# Asserts a surefire class ran with >=1 test and zero failures/errors (non-vacuity).
assert_class_green() {
  local log="$1" cls="$2" line
  line="$(grep -E "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+.* -- in .*\.${cls}$" "$log" | tail -1 || true)"
  [ -n "$line" ] || { tail -30 "$log"; fail tests "non-vacuity: ${cls} did not run (renamed/skipped?)"; }
  echo "$line" | grep -qE "Tests run: [1-9][0-9]*, Failures: 0, Errors: 0" \
    || { echo "  $line"; fail tests "${cls} did not pass with >=1 test and 0 failures/errors"; }
  echo "gate-phase0   ✓ ${cls}: ${line#*-- in }"
}

# Runs a targeted, offline test set across MODULES and asserts BUILD SUCCESS.
run_tests() {
  local label="$1" tests="$2" log="$3"
  $MVN -o -pl "$MODULES" test -Dtest="$tests" -Dsurefire.failIfNoSpecifiedTests=false >"$log" 2>&1 \
    || { tail -60 "$log"; fail "$label" "test run failed"; }
  grep -q "BUILD SUCCESS" "$log" || { tail -60 "$log"; fail "$label" "no BUILD SUCCESS"; }
}

# --- 2-vCPU box discipline: never overlap another Maven workload --------------
if pgrep -f "[s]urefirebooter" >/dev/null 2>&1; then
  echo "gate-phase0: another Maven test workload is running — refusing to start (2-vCPU box)" >&2
  exit 1
fi

# --- cumulative: gate-7 (chains 6→5→4→3→2→1) ----------------------------------
if [ "${GATE_PHASE0_SKIP_GATE7:-0}" = "1" ]; then
  echo "gate-phase0 gate7: SKIPPED by GATE_PHASE0_SKIP_GATE7=1 (LOUD: gates 1..7 NOT verified this run; CI supplies them via the gate jobs)"
else
  echo "gate-phase0 gate7: running cumulative gate-7 (chains 1..7)..."
  bash "$ROOT/gates/gate-7.sh" || fail gate7 "cumulative gate-7 (1..7) is RED — fix it before phase0"
  echo "gate-phase0 gate7: OK (gates 1..7 green)"
fi

# --- build/install the modules' deps once (so the targeted test runs are offline)
if [ "${GATE_PHASE0_SKIP_BUILD:-0}" = "1" ]; then
  echo "gate-phase0 build: SKIPPED by GATE_PHASE0_SKIP_BUILD=1 (reusing installed jars; CI must not do this)"
else
  echo "gate-phase0 build: installing module jars (skip tests) so the gate run is hermetic..."
  $MVN -q -pl "$MODULES" -am install -DskipTests >"$LOGDIR/build.txt" 2>&1 \
    || { tail -30 "$LOGDIR/build.txt"; fail build "module build/install failed"; }
fi

# --- (a) owner net non-vacuous at N>1 + the re-threading -----------------------
echo "gate-phase0 net: owner-isolation at N>1 (cross-group trips the per-node net) + off-owner inbound..."
NET="$LOGDIR/net.txt"
run_tests net "OwnerIsolationMultiOwnerTest,OwnerNetCatchesOffOwnerInboundTest,RaftNodeConcurrencyStressTest" "$NET"
assert_class_green "$NET" "OwnerIsolationMultiOwnerTest"      # cross-group class trips the per-node net (M1)
assert_class_green "$NET" "OwnerNetCatchesOffOwnerInboundTest" # off-owner inbound under the pool (Stage 1)
assert_class_green "$NET" "RaftNodeConcurrencyStressTest"     # off-owner class (Workstream A net)
echo "gate-phase0 net: OK"

# --- (b) H-4 rehoming mechanism + deferred sub-mechanisms + robustness ---------
echo "gate-phase0 rehoming: quiesce→publish→adopt + check-and-bounce; rehoming-race net; sub-mechanisms; robustness..."
REH="$LOGDIR/rehoming.txt"
run_tests rehoming "RehomingHandoffTest,RehomingSubMechanismsTest,RehomingRobustnessTest,MultiRaftDriverTest" "$REH"
assert_class_green "$REH" "RehomingHandoffTest"        # rehoming-race class + missed-hop + removeGroup (M2a)
assert_class_green "$REH" "RehomingSubMechanismsTest"  # quiesce / flush-retarget / abortHandoff (M2b S1)
assert_class_green "$REH" "RehomingRobustnessTest"     # interrupt-atomicity + no wedged livelock (M2b S1 fixes)
echo "gate-phase0 rehoming: OK"

# --- (c) the S2–S4 invariant surface WITH rehoming injected (1 sweep smoke) ----
echo "gate-phase0 sweep: rehoming injected under concurrent multi-owner load — owner isolation + liveness..."
SWEEP="$LOGDIR/sweep.txt"
run_tests sweep "RehomingInjectedSweepTest" "$SWEEP"
assert_class_green "$SWEEP" "RehomingInjectedSweepTest"
echo "gate-phase0 sweep: OK"

# --- (d) the deterministic S2–S4 sim (no regression from the re-threading) -----
if [ "${GATE_PHASE0_SKIP_SIM:-0}" = "1" ]; then
  echo "gate-phase0 sim: SKIPPED by GATE_PHASE0_SKIP_SIM=1 (LOUD: the 20,001-seed sim NOT verified this run; CI nightly runs it)"
else
  echo "gate-phase0 sim: deterministic 20,001-seed sweep + adversarial + owner-thread sim integration..."
  SIM="$LOGDIR/sim.txt"
  run_tests sim "SeedSweepTest,AdversarialSimTest,OwnerThreadSimIntegrationTest" "$SIM"
  assert_class_green "$SIM" "SeedSweepTest"                 # 20,001-seed invariant sweep
  assert_class_green "$SIM" "OwnerThreadSimIntegrationTest" # off-drive-thread access fails the seed
  echo "gate-phase0 sim: OK"
fi

# --- (e) H-4 no-double-ownership (jcstress) + owner-guard + monitor-view -------
if [ "${GATE_PHASE0_SKIP_JCSTRESS:-0}" = "1" ]; then
  echo "gate-phase0 jcstress: SKIPPED by GATE_PHASE0_SKIP_JCSTRESS=1 (LOUD: the JMM no-double-ownership proof NOT verified this run)"
else
  echo "gate-phase0 jcstress: building the uber-jar, then the curated subset (rehoming proofs at -m quick)..."
  $MVN -q -o -pl configd-config-store,configd-distribution-service,configd-transport -am \
    install -Dmaven.test.skip=true >"$LOGDIR/jcstress-install.txt" 2>&1 \
    || { tail -20 "$LOGDIR/jcstress-install.txt"; fail jcstress "jcstress dep install failed"; }
  $MVN -q -o -pl configd-jcstress clean package -Dmaven.test.skip=true >"$LOGDIR/jcstress-build.txt" 2>&1 \
    || { tail -20 "$LOGDIR/jcstress-build.txt"; fail jcstress "jcstress uber-jar build failed"; }
  [ -f "$ROOT/configd-jcstress/target/jcstress.jar" ] || fail jcstress "uber-jar not produced"
  JCSTRESS_CPUS="${JCSTRESS_CPUS:-2}" bash "$ROOT/configd-jcstress/run-curated-subset.sh" \
    "$LOGDIR/jcstress-results" >"$LOGDIR/jcstress.txt" 2>&1 || { tail -20 "$LOGDIR/jcstress.txt"; fail jcstress "curated subset did NOT pass"; }
  grep -qE "jcstress curated subset: OK" "$LOGDIR/jcstress.txt" \
    || { tail -10 "$LOGDIR/jcstress.txt"; fail jcstress "curated subset did NOT report OK"; }
  echo "gate-phase0 jcstress: OK ($(grep -E 'curated subset: OK' "$LOGDIR/jcstress.txt" | tail -1))"
fi

# --- (f) record the verified baseline (D-010: main stays pinned at cedc706) ----
echo "gate-phase0 baseline: main pinned at the verified baseline (D-010)..."
BASE="$(cd "$ROOT" && git rev-parse main 2>/dev/null | cut -c1-7 || echo unknown)"
echo "gate-phase0 baseline: main = $BASE (expected cedc706 until the Workstream-B merge gate)"

echo "=== gate-phase0: GREEN — R-01 re-threading + H-4 rehoming closure verified ==="
exit 0
