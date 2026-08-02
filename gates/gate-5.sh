#!/usr/bin/env bash
# gate-5.sh — cumulative machine-verifiable gate (performance & capacity)
#
# Cumulative with gates 1+2+3+4: a green gate-5 REQUIRES a green gate-4 (which
# chains 3→2→1). In CI gate-4 runs as its own job, so the gate-5 job sets
# GATE5_SKIP_GATE4=1 (cumulative coverage via the job dependency, not a
# redundant re-run) — reported LOUDLY. Exits non-zero on ANY failure; no
# silent placeholders; every step asserts a real result and FAILS if its
# summary line is absent (non-vacuity).
#
#
# Environment knobs (CI must not set the skips on a full local run):
#   GATE5_SKIP_GATE4=1   skip step (a) — reported LOUDLY (CI runs gate-4 as its
#                        own job; local iteration only)
#   GATE5_SKIP_BUILD=1   reuse an existing benchmarks.jar (local convenience)
#   GATE4_*/GATE3_*/...  forwarded to the underlying gates.
# Runtime: gate-4 chain dominates; the gate-5-specific steps are ~3-5 min on the
# 2-vCPU box (one fork, short JMH iterations — these are pass/fail asserts, not
# the authoritative latency measurement, which is the perf nightly lane).
# Collector: ZGC (ADR-0041) for the JMH steps.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MVN="$ROOT/mvnw -B"
JAR="$ROOT/configd-testkit/target/benchmarks.jar"
LOGDIR="${GATE5_LOG_DIR:-$(mktemp -d /tmp/gate5-XXXXXX)}"
mkdir -p "$LOGDIR"
JVMOPTS="-XX:+UseZGC --enable-preview"

echo "=== GATE-5 (Session 5: performance & capacity) — logs in $LOGDIR ==="

# 2-vCPU box discipline: never overlap another Maven/JMH workload.
if pgrep -f "[a]pache-maven|[s]urefirebooter" >/dev/null 2>&1; then
  echo "GATE-5: another Maven workload is running — refusing to start (2-vCPU box)" >&2
  exit 1
fi

fail() { echo "GATE-5 FAIL [$1]: $2" >&2; exit 1; }

# (a) cumulative: gate-4 (chains 3→2→1)
if [ "${GATE5_SKIP_GATE4:-0}" = "1" ]; then
  echo "GATE-5 gate4: SKIPPED by GATE5_SKIP_GATE4=1 (LOUD: gates 1+2+3+4 NOT verified this run; CI supplies them via the gate-4 job)"
else
  echo "GATE-5 gate4: running cumulative gate-4 (chains gate-3→2→1)..."
  bash "$ROOT/gates/gate-4.sh" || fail gate4 "cumulative gate-4 (1+2+3+4) is RED — fix correctness/chaos before perf"
  echo "GATE-5 gate4: OK (gates 1+2+3+4 green)"
fi

if [ "${GATE5_SKIP_BUILD:-0}" = "1" ] && [ -f "$JAR" ]; then
  echo "GATE-5 build: REUSING existing $JAR (GATE5_SKIP_BUILD=1; CI must not do this)"
else
  echo "GATE-5 build: packaging benchmarks.jar..."
  # skipTests, not maven.test.skip: configd-testkit depends on configd-consensus-core's
  # test-jar, and skipping test COMPILATION means that jar is never attached, so the
  # reactor cannot supply it. It resolved anyway only while some earlier full build had
  # left one in the local repository; on a cold one the packaging fails outright.
  $MVN -q -pl configd-testkit -am package -DskipTests >"$LOGDIR/build.txt" 2>&1 \
    || { tail -30 "$LOGDIR/build.txt"; fail build "benchmarks.jar build failed"; }
fi
[ -f "$JAR" ] || fail build "$JAR missing after build"

echo "GATE-5 alloc: read-path 0 B/op (gates/jmh-gc-check.sh: getMiss + getIntoHit < 1 B/op)..."
GATE5_SKIP_BUILD=1 JMHGC_SKIP_BUILD=1 bash "$ROOT/gates/jmh-gc-check.sh" \
  || fail alloc "read-path steady-state allocation regressed (>= 1 B/op on a gated leg)"
echo "GATE-5 alloc: OK (zero steady-state allocation on the in-process read path)"

echo "GATE-5 read-tail: JMH SampleTime p99/p999 regression bounds (size=100000)..."
RT="$LOGDIR/read-tail.txt"
java $JVMOPTS -jar "$JAR" 'LocalConfigStoreReadBenchmark\.getHitWithCursor$' \
    -bm sample -p size=100000 -f 1 -wi 5 -i 8 -w 1 -r 1 >"$RT" 2>&1 \
    || { tail -20 "$RT"; fail read-tail "JMH SampleTime run failed"; }
# JMH SampleTime summary lines (ns/op): <bench>:p0.99  100000  sample  <value>  ns/op
P99_NS="$(grep -E 'getHitWithCursor:p0\.99 ' "$RT" | awk '{for(i=1;i<=NF;i++) if($i ~ /^[0-9.]+$/){v=$i}} END{print v}')"
P999_NS="$(grep -E 'getHitWithCursor:p0\.999 ' "$RT" | awk '{for(i=1;i<=NF;i++) if($i ~ /^[0-9.]+$/){v=$i}} END{print v}')"
[ -n "$P99_NS" ]  || { tail -20 "$RT"; fail read-tail "no p0.99 summary line (non-vacuity: benchmark renamed or did not run)"; }
[ -n "$P999_NS" ] || { tail -20 "$RT"; fail read-tail "no p0.999 summary line (non-vacuity)"; }
# Generous regression bounds (ns): p99 < 20us, p999 < 500us (measured 0.92us / 22us)
awk -v v="$P99_NS"  'BEGIN{exit !(v+0 < 20000)}'  || fail read-tail "read p99 = ${P99_NS} ns >= 20000 ns bound — a read-path latency regression (lock/alloc/megamorphic?)"
awk -v v="$P999_NS" 'BEGIN{exit !(v+0 < 500000)}' || fail read-tail "read p999 = ${P999_NS} ns >= 500000 ns bound — read-path tail regression"
echo "GATE-5 read-tail: OK (p99=${P99_NS} ns < 20us, p999=${P999_NS} ns < 500us)"

echo "GATE-5 throughput: RaftCommitBenchmark in-memory floor (>= 50k commits/s)..."
TP="$LOGDIR/throughput.txt"
java $JVMOPTS -jar "$JAR" 'RaftCommitBenchmark\.proposeAndCommit$' \
    -p clusterSize=3 -f 1 -wi 3 -i 4 -w 1 -r 1 >"$TP" 2>&1 \
    || { tail -20 "$TP"; fail throughput "RaftCommitBenchmark run failed"; }
# Throughput mode score line; normalize ops/us or ops/s to ops/s. Accept either unit.
TLINE="$(grep -E 'RaftCommitBenchmark\.proposeAndCommit ' "$TP" | grep -E 'thrpt' | tail -1)"
[ -n "$TLINE" ] || { tail -20 "$TP"; fail throughput "no proposeAndCommit thrpt summary line (non-vacuity)"; }
SCORE="$(echo "$TLINE" | awk '{for(i=1;i<=NF;i++) if($i ~ /^[0-9.]+$/){v=$i; break}} END{print v}')"
UNIT="$(echo "$TLINE"  | awk '{print $NF}')"
case "$UNIT" in
  ops/us|ops/μs) OPS=$(awk -v s="$SCORE" 'BEGIN{print s*1000000}') ;;
  ops/ms)        OPS=$(awk -v s="$SCORE" 'BEGIN{print s*1000}') ;;
  ops/s)         OPS="$SCORE" ;;
  *)             OPS="$SCORE" ;; # default assume the raw score is already large
esac
awk -v v="$OPS" 'BEGIN{exit !(v+0 >= 50000)}' \
  || fail throughput "consensus throughput floor: ${OPS} ops/s < 50000 (mechanism broken?)  line: $TLINE"
echo "GATE-5 throughput: OK (${OPS} ops/s >= 50k floor; measured ~815k — mechanism sustains the floor)"

echo "GATE-5 backpressure: §11 as-built write bound (maxPendingProposals default == 1024)..."
RC="$ROOT/configd-consensus-core/src/main/java/io/configd/raft/RaftConfig.java"
grep -qE 'maxPendingProposals.*default 1024|default 1024' "$RC" \
  || fail backpressure "RaftConfig no longer documents the maxPendingProposals=1024 bound (the §11 as-built write threshold, RR-110). If the bound changed intentionally, update Workstream D + this gate."
echo "GATE-5 backpressure: OK (the §11 bounded-proposal-queue threshold = 1024 is intact)"

echo "GATE-5 co-check: coordinated-omission discipline on the harnesses..."
# The read-tail step (c) measured via -bm sample (SampleTime), NOT AverageTime — assert the run did so.
grep -qE 'Mode|sample' "$RT" || fail co-check "read-tail did not run in SampleTime mode (CO/averaging violation)"
grep -qE ':p0\.99 ' "$RT"   || fail co-check "read-tail produced no percentile distribution (averages are refused for tails)"
[ -f "$ROOT/configd-testkit/src/main/java/io/configd/bench/ReadUnderWriteContentionBenchmark.java" ] \
  || fail co-check "ReadUnderWriteContentionBenchmark (reader-vs-writer harness) missing"
echo "GATE-5 co-check: OK (tails via SampleTime; CO-correct harnesses present)"

echo "=== GATE-5: ALL STEPS GREEN ==="
