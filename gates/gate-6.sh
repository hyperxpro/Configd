#!/usr/bin/env bash
# gate-6.sh — cumulative machine-verifiable gate (operability & deployment readiness)
#
# Cumulative with gates 1..5: a green gate-6 REQUIRES a green gate-5 (which chains
# 4→3→2→1). In CI gate-5 runs as its own job, so the gate-6 job sets
# GATE6_SKIP_GATE5=1 (cumulative coverage via the job dependency, not a redundant
# re-run) — reported LOUDLY. Exits non-zero on ANY failure; no silent placeholders;
# every step asserts a REAL result and FAILS if its summary line is absent
# (non-vacuity).
#
#
# Environment knobs (CI must not set the skips on a full local run):
#   GATE6_SKIP_GATE5=1   skip step (a) — LOUD (CI runs gate-5 as its own job).
#   GATE6_SKIP_BUILD=1   reuse already-installed module jars (local convenience).
#   PROMTOOL             path to a promtool binary (else a pinned one is downloaded).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MVN="$ROOT/mvnw -B"
LOGDIR="${GATE6_LOG_DIR:-$(mktemp -d /tmp/gate6-XXXXXX)}"
mkdir -p "$LOGDIR"
PROM_VER="2.53.2"

MODULES="configd-wire,configd-observability,configd-config-store,configd-transport,configd-distribution-service,configd-server,configd-edge-node"

echo "=== GATE-6 (Session 6: operability & deployment readiness) — logs in $LOGDIR ==="

fail() { echo "GATE-6 FAIL [$1]: $2" >&2; exit 1; }

# Asserts a surefire class ran with >=1 test and zero failures/errors (non-vacuity).
assert_class_green() {
  local log="$1" cls="$2"
  local line
  line="$(grep -E "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+.* -- in .*${cls}$" "$log" | tail -1 || true)"
  [ -n "$line" ] || { tail -30 "$log"; fail tests "non-vacuity: ${cls} did not run (renamed/skipped?)"; }
  echo "$line" | grep -qE "Tests run: [1-9][0-9]*, Failures: 0, Errors: 0" \
    || { echo "  $line"; fail tests "${cls} did not pass with >=1 test and 0 failures/errors"; }
  echo "GATE-6   ✓ ${cls}: ${line#*-- in }"
}

# 2-vCPU box discipline: never overlap another Maven workload.
if pgrep -f "[s]urefirebooter" >/dev/null 2>&1; then
  echo "GATE-6: another Maven test workload is running — refusing to start (2-vCPU box)" >&2
  exit 1
fi

if [ "${GATE6_SKIP_GATE5:-0}" = "1" ]; then
  echo "GATE-6 gate5: SKIPPED by GATE6_SKIP_GATE5=1 (LOUD: gates 1..5 NOT verified this run; CI supplies them via the gate-5 job)"
else
  echo "GATE-6 gate5: running cumulative gate-5 (chains 4→3→2→1)..."
  bash "$ROOT/gates/gate-5.sh" || fail gate5 "cumulative gate-5 (1..5) is RED — fix it before operability"
  echo "GATE-6 gate5: OK (gates 1..5 green)"
fi

# build/install the modules' deps once (so the targeted test run is offline)
if [ "${GATE6_SKIP_BUILD:-0}" = "1" ]; then
  echo "GATE-6 build: SKIPPED by GATE6_SKIP_BUILD=1 (reusing installed jars; CI must not do this)"
else
  echo "GATE-6 build: installing module jars (skip tests) so the gate run is hermetic..."
  $MVN -q -pl "$MODULES" -am install -DskipTests >"$LOGDIR/build.txt" 2>&1 \
    || { tail -30 "$LOGDIR/build.txt"; fail build "module build/install failed"; }
fi

# (b)(d)(e)(f)(g): the operability + deployment test classes (one run)
echo "GATE-6 tests: metric-contract + wire-compat + bootstrap + backup-restore + drill..."
TESTS="EdgeMetricsContractTest,MetricsWiringContractTest,WireCompatGoldenBytesTest,EdgeFrameCodecGoldenFixtureTest,BootstrapColdStartTest,BackupRestoreRoundTripTest,GameDayDrillTest"
TL="$LOGDIR/tests.txt"
$MVN -o -pl "$MODULES" test -Dtest="$TESTS" -Dsurefire.failIfNoSpecifiedTests=false >"$TL" 2>&1 \
  || { tail -40 "$TL"; fail tests "operability/deployment test run failed"; }
grep -q "BUILD SUCCESS" "$TL" || { tail -40 "$TL"; fail tests "no BUILD SUCCESS"; }
assert_class_green "$TL" "EdgeMetricsContractTest"          # (b) every dashboard/alert series emitted
assert_class_green "$TL" "MetricsWiringContractTest"        # (b) SLO series recorded with real data
assert_class_green "$TL" "WireCompatGoldenBytesTest"        # (d) raft wire byte-stability (N↔N+1)
assert_class_green "$TL" "EdgeFrameCodecGoldenFixtureTest"  # (d) edge wire byte-stability (N↔N+1)
assert_class_green "$TL" "BootstrapColdStartTest"           # (e) zero-state cold start → serving
assert_class_green "$TL" "BackupRestoreRoundTripTest"       # (f) backup/restore state-equality
assert_class_green "$TL" "GameDayDrillTest"                 # (g) alert→runbook→recovery loop closes
echo "GATE-6 tests: OK"

# (c) alert rules: lint + fires/quiet (promtool)
echo "GATE-6 alerts: promtool check rules + fires/quiet test rules..."
PT="${PROMTOOL:-}"
if [ -z "$PT" ]; then
  PT="$ROOT/.gate-tools/promtool"
  if [ ! -x "$PT" ]; then
    echo "GATE-6 alerts: fetching pinned promtool ${PROM_VER}..."
    mkdir -p "$ROOT/.gate-tools" "$LOGDIR/pt"
    curl -fsSL -o "$LOGDIR/pt/p.tgz" \
      "https://github.com/prometheus/prometheus/releases/download/v${PROM_VER}/prometheus-${PROM_VER}.linux-amd64.tar.gz" \
      || fail alerts "promtool download failed (set PROMTOOL=/path to skip the download)"
    tar -xzf "$LOGDIR/pt/p.tgz" -C "$LOGDIR/pt" "prometheus-${PROM_VER}.linux-amd64/promtool"
    cp "$LOGDIR/pt/prometheus-${PROM_VER}.linux-amd64/promtool" "$PT"
    chmod +x "$PT"
  fi
fi
"$PT" check rules "$ROOT/ops/alerts/configd-slo-alerts.yaml" >"$LOGDIR/alerts-check.txt" 2>&1 \
  || { cat "$LOGDIR/alerts-check.txt"; fail alerts "promtool check rules failed (rules do not lint)"; }
grep -qE "SUCCESS: [0-9]+ rules found" "$LOGDIR/alerts-check.txt" \
  || { cat "$LOGDIR/alerts-check.txt"; fail alerts "non-vacuity: no rules found by promtool check"; }
( cd "$ROOT/ops/alerts" && "$PT" test rules configd-slo-alerts.test.yaml ) >"$LOGDIR/alerts-test.txt" 2>&1 \
  || { cat "$LOGDIR/alerts-test.txt"; fail alerts "promtool test rules FAILED (an alert does not fire/quiet as specified)"; }
grep -q "SUCCESS" "$LOGDIR/alerts-test.txt" \
  || { cat "$LOGDIR/alerts-test.txt"; fail alerts "non-vacuity: no SUCCESS from promtool test rules"; }
echo "GATE-6 alerts: OK ($(grep -oE 'SUCCESS: [0-9]+ rules found' "$LOGDIR/alerts-check.txt") + fires/quiet green)"

echo ""
echo "=== GATE-6 GREEN: operability bar locked (metric-contract, alert fires/quiet, wire-compat, bootstrap, backup-restore, drill loop) ==="
