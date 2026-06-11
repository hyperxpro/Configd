#!/usr/bin/env bash
# =============================================================================
# jmh-gc-check.sh — CT-34 mechanical hot-path allocation check (gate-3 step)
# -----------------------------------------------------------------------------
# INVOKED BY: gate-3 (assembled at Session-3 close; until then runnable
# standalone). Authored at C2 sign-off per the C2 contract-qa audit's REQUIRED
# follow-up: the CT-34 GATE row cannot pass on Javadoc-recorded numbers — it
# needs a mechanical `-prof gc` run with a saved artifact.
#
# WHAT A GREEN RUN PROVES (CT-34 / charter §6 rule 3 — the inherited hot-path
# law on the edge read path):
#   ZERO steady-state allocation on the in-process edge read path
#   (LocalConfigStore), measured by JMH's GC profiler on:
#     - getMiss     the miss path (pre-allocated ReadResult.NOT_FOUND singleton)
#     - getIntoHit  the VDR-0001 strict-zero-alloc hit path (caller buffer)
#   Asserted mechanically: gc.alloc.rate.norm < 1 B/op for BOTH legs (JMH
#   reports true zero as "≈ 10⁻⁴" infrastructure noise; >= 1 B/op means a real
#   per-op allocation crept in). The plain get() hit legs allocate exactly one
#   ReadResult record by design (the documented, accepted nursery allocation —
#   see ReadResult's javadoc and the benchmark's) and are captured in the
#   artifact for trend visibility but NOT gated.
#
# SCOPE (the signed law boundary — c2-signoff-review Finding 4): the law binds
# the IN-PROCESS read path only. The HTTP serving shell above it
# (EdgeHttpServer) allocates per request and is out of scope by design; do NOT
# point this gate at the HTTP surface.
#
# ARTIFACT: the full raw JMH output is saved to
#   docs/session-3/captures/ct34-jmh-gc-check.txt   (stable path; overwritten
# per run, header carries the timestamp + git SHA so the contract map row can
# cite a concrete, reproducible run).
#
# NON-VACUITY (RR-012 lesson — a gate that can pass while checking nothing is
# worse than no gate): the script FAILS if either gated leg's summary line is
# absent from the JMH output (a renamed benchmark or a bad regex must go RED,
# not silently green).
#
# Environment knobs:
#   JMHGC_SKIP_BUILD=1   reuse an existing benchmarks.jar (local convenience;
#                        reported loudly — CI must not set it)
#   JMHGC_SIZE           store size param (default 10000)
# Runtime: ~2-3 minutes on the 2-vCPU gate box (1 fork, 3+3 x 1s iterations
# per leg; deliberately short — this is a zero-vs-nonzero gate, not a
# latency measurement).
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MVN="$ROOT/mvnw -B"
JAR="$ROOT/configd-testkit/target/benchmarks.jar"
CAPTURE="$ROOT/docs/session-3/captures/ct34-jmh-gc-check.txt"
SIZE="${JMHGC_SIZE:-10000}"
# Gated legs: the two paths the law requires to be strictly zero-alloc.
GATED_LEGS=(getMiss getIntoHit)
# Captured-not-gated legs (the documented single-ReadResult allocation).
ALL_LEGS_REGEX='LocalConfigStoreReadBenchmark\.(getMiss|getIntoHit|getHit|getHitWithCursor)$'

# --- 2-vCPU box discipline: never overlap another Maven/JMH workload ---------
if pgrep -f "org.apache.maven" >/dev/null 2>&1; then
  echo "JMH-GC-CHECK: another Maven workload is running — refusing to start" >&2
  exit 1
fi

# --- build the benchmarks uber-jar (unless explicitly reusing) ---------------
if [ "${JMHGC_SKIP_BUILD:-0}" = "1" ] && [ -f "$JAR" ]; then
  echo "JMH-GC-CHECK: JMHGC_SKIP_BUILD=1 — REUSING existing $JAR (CI must not do this)"
else
  echo "JMH-GC-CHECK: packaging benchmarks.jar (testkit + upstream, tests skipped)"
  if ! $MVN -q -pl configd-testkit -am package -Dmaven.test.skip=true >/dev/null; then
    echo "JMH-GC-CHECK: benchmarks.jar build FAILED" >&2
    exit 1
  fi
fi
[ -f "$JAR" ] || { echo "JMH-GC-CHECK: $JAR missing after build" >&2; exit 1; }

# --- run the benchmark with the GC profiler ----------------------------------
RAW="$(mktemp /tmp/ct34-jmh-XXXXXX.txt)"
echo "JMH-GC-CHECK: running LocalConfigStoreReadBenchmark (-prof gc, size=$SIZE)"
java --enable-preview -jar "$JAR" "$ALL_LEGS_REGEX" \
    -p "size=$SIZE" -prof gc -f 1 -wi 3 -i 3 -w 1 -r 1 \
    >"$RAW" 2>&1 || { echo "JMH-GC-CHECK: JMH run FAILED"; tail -20 "$RAW"; exit 1; }

# --- persist the artifact (stable path, provenance header) -------------------
mkdir -p "$(dirname "$CAPTURE")"
{
  echo "# CT-34 mechanical gc-profile run (gates/jmh-gc-check.sh)"
  echo "# date: $(date -u +%Y-%m-%dT%H:%M:%SZ)  git: $(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)  size=$SIZE"
  echo "# gated legs (must be < 1 B/op): ${GATED_LEGS[*]}; getHit/getHitWithCursor captured for trend (one ReadResult by design, not gated)"
  echo
  cat "$RAW"
} >"$CAPTURE"
rm -f "$RAW"

# --- mechanical assertion: gc.alloc.rate.norm < 1 B/op on the gated legs -----
# JMH summary lines look like either of:
#   LocalConfigStoreReadBenchmark.getMiss:gc.alloc.rate.norm  10000  avgt  3  ≈ 10⁻⁴  B/op
#   LocalConfigStoreReadBenchmark.getMiss:gc.alloc.rate.norm  10000  avgt  3  0.001   B/op
FAIL=0
for leg in "${GATED_LEGS[@]}"; do
  line="$(grep -E "LocalConfigStoreReadBenchmark\.${leg}:gc\.alloc\.rate\.norm\s" "$CAPTURE" | tail -1 || true)"
  if [ -z "$line" ]; then
    echo "JMH-GC-CHECK FAIL [$leg]: no gc.alloc.rate.norm summary line found —"
    echo "  the benchmark did not run or was renamed (non-vacuity guard). See $CAPTURE"
    FAIL=1
    continue
  fi
  if echo "$line" | grep -q "≈ 10"; then
    echo "JMH-GC-CHECK OK   [$leg]: ≈ 0 B/op (JMH infrastructure noise only)"
    continue
  fi
  # Numeric SCORE column. JMH summary layout is fixed:
  #   <name> <(size)> <mode> <cnt> <score> [± <error>] <units>
  # so the score is field 5 — NOT the last numeric field, which would read the
  # ± error column (a bug the red-path drill caught: 32.001 ± 0.001 parsed as
  # 0.001). An unparseable score is a RED, never a silent pass.
  score="$(echo "$line" | awk '$5 ~ /^[0-9.]+$/ {print $5}')"
  if [ -z "$score" ]; then
    echo "JMH-GC-CHECK FAIL [$leg]: could not parse the score column (field 5) from:"
    echo "  $line"
    FAIL=1
    continue
  fi
  if awk -v s="$score" 'BEGIN { exit !(s < 1.0) }'; then
    echo "JMH-GC-CHECK OK   [$leg]: $score B/op (< 1 B/op)"
  else
    echo "JMH-GC-CHECK FAIL [$leg]: gc.alloc.rate.norm = $score B/op — the CT-34"
    echo "  hot-path law requires ZERO steady-state allocation on this leg."
    echo "  A per-op allocation has crept into the in-process read path:"
    echo "  $line"
    FAIL=1
  fi
done

echo "JMH-GC-CHECK: artifact saved to ${CAPTURE#"$ROOT"/}"
if [ "$FAIL" -ne 0 ]; then
  echo "JMH-GC-CHECK: RED — CT-34 hot-path law violated (see above)"
  exit 1
fi
echo "JMH-GC-CHECK: GREEN — zero steady-state allocation on the in-process edge read path (CT-34)"
