#!/usr/bin/env bash
# =============================================================================
# gate-1.sh — Configd Session-1 cumulative machine-verifiable gate
# -----------------------------------------------------------------------------
# Authored by the build-integrity-engineer (audit-session-1, Phase E).
# Self-contained: run `bash gates/gate-1.sh` from anywhere; it resolves the
# repo root itself. Exits non-zero on ANY failure. Prints a final PASS/FAIL
# summary table with per-step timings.
#
# WHAT A GREEN GATE-1 PROVES:
#   (a) build      clean reactor build + full suite: `./mvnw -B -fae clean verify`
#                  BUILD SUCCESS, 0 failures, 0 errors, >= 21,000 tests run
#                  (count tripwire; 8 known accounted skips are OK).
#   (b) linz       the R-04 linearizability harness self-tests: the Porcupine
#                  checker builds from the repo's Go sources and all 6
#                  PORCUPINE_BIN-gated CheckerSelfTest tests run green, 0 skips.
#   (c) jmh        all 9 JMH benchmark classes EXECUTE (exit 0, "Run complete")
#                  at minimal params — executability only, numbers are NOT
#                  performance evidence.
#   (d) tlc        the 3 TLA+ specs pass TLC ("No error") at REDUCED smoke
#                  bounds (gates/spec-smoke/*.cfg — same invariants as the full
#                  configs, smaller constants).
#   (e) multinode  a real 3-node cluster elects a leader, accepts writes,
#                  survives a leader kill -9, and loses no committed data
#                  (gates/smoke-multinode.sh — control-plane only).
#
# WHAT A GREEN GATE-1 DOES *NOT* PROVE — see docs/readiness-register.md,
# section "Gate-1 blockers". The four P0 findings are INVISIBLE to a green
# gate-1:
#   - RR-001: nothing exercises the headline edge-propagation pipeline
#             end-to-end (it does not exist; the suite cannot fail on it).
#   - RR-002: no test runs a black-holed peer against the real transport;
#             gate-1 is green while one routine network fault freezes a node.
#   - RR-003/RR-005: the restart-after-compaction data-loss path is
#             unreachable by the suite (compaction is unreachable).
#   - RR-004: RESOLVED in Session 2 (ADR-0033) — ack is now commit-confirmed
#             (HTTP 200 "Committed: seq=S" only after quorum commit + apply).
#             The discriminating proof lives in the unit/sim suites, NOT here:
#             configd-testkit AckEqualsCommitTest (randomized leader-kill in the
#             append->commit window, 3 fault shapes), configd-consensus-core
#             CommitOutcomeSeamTest, configd-server RaftProposerCommitConfirmTest.
#             gate-1's smoke-multinode now writes really-committed entries.
# Additional limits: the TLC step checks SMOKE bounds only (Session 2 owns
# bound adequacy); JMH executability != a performance baseline; the multinode
# smoke is control-plane only (edge fan-out is not demonstrable, RR-001);
# 93.4% of the test count is one seed sweep (RR-012) — quote 1,408, not 21,408.
#
# Environment knobs:
#   PORCUPINE_BIN   path to a prebuilt Porcupine checker (skips the Go build)
#   GATE1_SKIP_LINZ=1  skip step (b) entirely — reported LOUDLY as SKIPPED
#   GATE1_LOG_DIR   directory for per-step logs (default: mktemp under /tmp)
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOGDIR="${GATE1_LOG_DIR:-$(mktemp -d /tmp/gate1-XXXXXX)}"
mkdir -p "$LOGDIR"
export GATE1_LOG_DIR="$LOGDIR"   # children (--step) must write into the same log dir

# The 9 benchmark classes with one pinned @Param value each (bounds runtime;
# same pinning as docs/audit-session-1/harness-runs.md §1). Format: Class[:k=v]
JMH_CLASSES=(
  "HamtReadBenchmark:size=10000"
  "HamtWriteBenchmark:size=10000"
  "HistogramBenchmark"
  "HybridClockBenchmark:clockType=system"
  "PlumtreeFanOutBenchmark:fanOut=50"
  "RaftCommitBenchmark:clusterSize=3"
  "SubscriptionMatchBenchmark:prefixes=1000"
  "VersionedStoreReadBenchmark:size=10000"
  "WatchFanOutBenchmark:watcherCount=100"
)

SPECS=(ConsensusSpec ReadIndexSpec SnapshotInstallSpec)

# ----------------------------------------------------------------------------
# Step bodies. Each runs as a child `bash gate-1.sh --step <name>` so that
# `set -euo pipefail` genuinely aborts the step on the first unchecked failure
# (errexit is suppressed inside `if !` contexts in a single shell).
# ----------------------------------------------------------------------------

step_build() {
  cd "$ROOT"
  # Console output filtered for readability; the full transcript goes to the log.
  # Success is asserted from the log ("BUILD SUCCESS"), not the pipeline status.
  ./mvnw -B -fae clean verify 2>&1 | tee "$LOGDIR/build.log" | grep -E "^\[INFO\] (Tests run|BUILD|Reactor)|^\[(ERROR|WARNING)\] Tests run|FAILURE" || true
  grep -q "BUILD SUCCESS" "$LOGDIR/build.log" || { echo "GATE-1 build: no BUILD SUCCESS in mvn output (log: $LOGDIR/build.log)"; return 1; }
  # Count tripwire: sum the per-module surefire/failsafe summary lines
  # ("Tests run: N, Failures: ..., Errors: ..., Skipped: N" without "-- in").
  read -r runs fails errs skips < <(awk '
    /Tests run:.*Failures:.*Errors:.*Skipped:/ && !/-- in / {
      gsub(/,/, "");
      for (i = 1; i <= NF; i++) {
        if ($i == "run:")      r += $(i+1);
        if ($i == "Failures:") f += $(i+1);
        if ($i == "Errors:")   e += $(i+1);
        if ($i == "Skipped:")  s += $(i+1);
      }
    } END { print r+0, f+0, e+0, s+0 }' "$LOGDIR/build.log")
  echo "GATE-1 build: tests run=$runs failures=$fails errors=$errs skipped=$skips"
  [ "$fails" -eq 0 ] || { echo "GATE-1 build: $fails test failure(s)"; return 1; }
  [ "$errs"  -eq 0 ] || { echo "GATE-1 build: $errs test error(s)"; return 1; }
  [ "$runs" -ge 21000 ] || { echo "GATE-1 build: tests-run tripwire — $runs < 21000 (suite shrank?)"; return 1; }
  echo "GATE-1 build: OK (skips=$skips; 8 are known/accounted — see build-report.md)"
}

step_linz() {
  cd "$ROOT"
  if [ -z "${PORCUPINE_BIN:-}" ]; then
    # Build the trusted checker from the repo's Go sources (mirrors
    # configd-linz/scripts/build-porcupine.sh, minus its toolchain download —
    # a strict gate must not silently install toolchains).
    local GO=""
    if command -v go >/dev/null 2>&1; then GO=go;
    elif [ -x "$HOME/sdk/go/bin/go" ]; then GO="$HOME/sdk/go/bin/go"; fi
    if [ -z "$GO" ]; then
      echo "GATE-1 linz: FAIL — PORCUPINE_BIN is unset and no Go toolchain found (PATH, ~/sdk/go)." >&2
      echo "  Fix: install Go (or run configd-linz/scripts/build-porcupine.sh), or set PORCUPINE_BIN," >&2
      echo "  or set GATE1_SKIP_LINZ=1 to skip this step (reported loudly)." >&2
      return 1
    fi
    echo "GATE-1 linz: building Porcupine checker with $($GO version)"
    GOTOOLCHAIN=local "$GO" -C "$ROOT/configd-linz/src/main/go/porcupine-check" \
      build -o "$ROOT/configd-linz/bin/porcupine-check" .
    export PORCUPINE_BIN="$ROOT/configd-linz/bin/porcupine-check"
  fi
  [ -x "$PORCUPINE_BIN" ] || { echo "GATE-1 linz: PORCUPINE_BIN=$PORCUPINE_BIN is not executable"; return 1; }
  echo "GATE-1 linz: PORCUPINE_BIN=$PORCUPINE_BIN"
  # -am: step (a) ran `verify`, not `install`, so reactor siblings are not in
  # the local repo on a fresh runner. -Dtest=CheckerSelfTest keeps -am from
  # re-running the upstream suite (it already ran in step a).
  ./mvnw -B -pl configd-linz -am test \
    -Dtest=CheckerSelfTest -Dsurefire.failIfNoSpecifiedTests=false \
    2>&1 | tee "$LOGDIR/linz.log" | grep -E "Tests run|BUILD" || true
  grep -q "BUILD SUCCESS" "$LOGDIR/linz.log" || { echo "GATE-1 linz: mvn run failed"; return 1; }
  local line
  line="$(grep -E "Tests run:.*-- in io.configd.linz.CheckerSelfTest" "$LOGDIR/linz.log" | tail -1)" \
    || { echo "GATE-1 linz: CheckerSelfTest did not run at all"; return 1; }
  echo "GATE-1 linz: $line"
  echo "$line" | grep -q "Tests run: 6, Failures: 0, Errors: 0, Skipped: 0" \
    || { echo "GATE-1 linz: expected 6 run / 0 fail / 0 err / 0 skipped — got: $line"; return 1; }
  echo "GATE-1 linz: OK (6/6 gated self-tests ran, 0 skips)"
}

step_jmh() {
  cd "$ROOT"
  local jar="$ROOT/configd-testkit/target/benchmarks.jar"
  [ -f "$jar" ] || { echo "GATE-1 jmh: $jar missing (step a builds it)"; return 1; }
  local entry cls param t0 t1
  for entry in "${JMH_CLASSES[@]}"; do
    cls="${entry%%:*}"; param="${entry#"$cls"}"; param="${param#:}"
    t0=$(date +%s)
    if [ -n "$param" ]; then
      java -jar "$jar" "io.configd.bench.$cls" -f 1 -wi 1 -i 1 -w 1s -r 1s -foe true \
        -p "$param" > "$LOGDIR/jmh-$cls.log" 2>&1 \
        || { echo "GATE-1 jmh: $cls exited non-zero (log: $LOGDIR/jmh-$cls.log)"; return 1; }
    else
      java -jar "$jar" "io.configd.bench.$cls" -f 1 -wi 1 -i 1 -w 1s -r 1s -foe true \
        > "$LOGDIR/jmh-$cls.log" 2>&1 \
        || { echo "GATE-1 jmh: $cls exited non-zero (log: $LOGDIR/jmh-$cls.log)"; return 1; }
    fi
    grep -q "Run complete." "$LOGDIR/jmh-$cls.log" \
      || { echo "GATE-1 jmh: $cls produced no 'Run complete' (log: $LOGDIR/jmh-$cls.log)"; return 1; }
    t1=$(date +%s)
    echo "GATE-1 jmh: $cls OK (${param:-no param}, $((t1 - t0))s)"
  done
  echo "GATE-1 jmh: OK (9/9 classes executable — NOT performance evidence)"
}

step_tlc() {
  cd "$ROOT"
  [ -f "$ROOT/spec/tla2tools.jar" ] || { echo "GATE-1 tlc: spec/tla2tools.jar missing"; return 1; }
  # Scratch dir: TLC writes states/ and trace files into cwd; never run in spec/.
  local scratch spec t0 t1
  scratch="$(mktemp -d /tmp/gate1-tlc-XXXXXX)"
  for spec in "${SPECS[@]}"; do
    cp "$ROOT/spec/$spec.tla" "$ROOT/gates/spec-smoke/$spec-smoke.cfg" "$scratch/"
    t0=$(date +%s)
    (cd "$scratch" && java -XX:+UseParallelGC -Xmx3g -cp "$ROOT/spec/tla2tools.jar" tlc2.TLC \
        -workers 2 -config "$spec-smoke.cfg" "$spec.tla" > "$LOGDIR/tlc-$spec-smoke.log" 2>&1) \
      || { echo "GATE-1 tlc: $spec-smoke TLC exited non-zero (log: $LOGDIR/tlc-$spec-smoke.log)"; return 1; }
    grep -q "No error has been found" "$LOGDIR/tlc-$spec-smoke.log" \
      || { echo "GATE-1 tlc: $spec-smoke did not end 'No error' (log: $LOGDIR/tlc-$spec-smoke.log)"; return 1; }
    t1=$(date +%s)
    echo "GATE-1 tlc: $spec-smoke OK ($(grep -oE '[0-9,]+ states generated' "$LOGDIR/tlc-$spec-smoke.log" | tail -1), $((t1 - t0))s)"
  done
  rm -rf "$scratch"
  echo "GATE-1 tlc: OK (3/3 smoke configs 'No error' — smoke bounds, NOT full assurance)"
}

step_multinode() {
  cd "$ROOT"
  local jar
  jar="$(ls "$ROOT"/configd-server/target/configd-server-*.jar 2>/dev/null | grep -v original- | head -1 || true)"
  [ -n "$jar" ] || { echo "GATE-1 multinode: no shaded configd-server jar (step a builds it)"; return 1; }
  CONFIGD_JAR="$jar" bash "$ROOT/gates/smoke-multinode.sh" 2>&1 | tee "$LOGDIR/multinode.log"
  grep -q "SMOKE PASS" "$LOGDIR/multinode.log" || { echo "GATE-1 multinode: smoke did not PASS"; return 1; }
}

# ---- child-process dispatch -------------------------------------------------
if [ "${1:-}" = "--step" ]; then
  "step_$2"
  exit $?
fi

# ---- orchestrator -----------------------------------------------------------
STEPS=(build linz jmh tlc multinode)
declare -a S_NAME S_STATUS S_SECS
overall=0
echo "gate-1: logs in $LOGDIR"
GATE_T0=$(date +%s)

for s in "${STEPS[@]}"; do
  if [ "$overall" -ne 0 ]; then
    S_NAME+=("$s"); S_STATUS+=("NOT-RUN"); S_SECS+=("-")
    continue
  fi
  if [ "$s" = "linz" ] && [ "${GATE1_SKIP_LINZ:-0}" = "1" ]; then
    echo ""
    echo "############################################################################"
    echo "## WARNING: step (b) linz SKIPPED via GATE1_SKIP_LINZ=1.                  ##"
    echo "## The 6 linearizability checker self-tests were NOT run. A gate-1 that  ##"
    echo "## skips linz does NOT cover the R-04 harness (cf. RR-016).              ##"
    echo "############################################################################"
    S_NAME+=("$s"); S_STATUS+=("SKIPPED"); S_SECS+=("-")
    continue
  fi
  echo ""
  echo "=== gate-1 step: $s ==="
  t0=$(date +%s)
  rc=0
  bash "${BASH_SOURCE[0]}" --step "$s" || rc=$?
  t1=$(date +%s)
  S_NAME+=("$s"); S_SECS+=("$((t1 - t0))s")
  if [ "$rc" -eq 0 ]; then
    S_STATUS+=("PASS")
  else
    S_STATUS+=("FAIL")
    overall=1
  fi
done

GATE_T1=$(date +%s)

echo ""
echo "==================== gate-1 summary ===================="
printf "%-12s %-10s %s\n" "STEP" "STATUS" "TIME"
for i in "${!S_NAME[@]}"; do
  printf "%-12s %-10s %s\n" "${S_NAME[$i]}" "${S_STATUS[$i]}" "${S_SECS[$i]}"
done
echo "---------------------------------------------------------"
printf "%-12s %-10s %ss\n" "TOTAL" "$([ "$overall" -eq 0 ] && echo PASS || echo FAIL)" "$((GATE_T1 - GATE_T0))"
if printf '%s\n' "${S_STATUS[@]}" | grep -q SKIPPED; then
  echo "NOTE: one or more steps SKIPPED — this gate-1 result is PARTIAL."
fi
echo "Logs: $LOGDIR"
echo "Reminder: a green gate-1 does NOT mean system-ready —"
echo "see docs/readiness-register.md 'Gate-1 blockers' (4 P0s invisible to this gate)."
exit "$overall"
