#!/usr/bin/env bash
# =============================================================================
# run-curated-subset.sh — B6 / RR-011 curated jcstress smoke for gate-2.
# -----------------------------------------------------------------------------
# Runs the CURATED, deterministic subset of @JCStressTest classes in jcstress
# "sanity" mode (1 normal + 1 stress fork, 1 iteration each) — a fast smoke that
# proves the race detector runs end-to-end and surfaces NO forbidden outcome on
# the real structures. The full, longer-confidence run is the operator's
# `-m default/stress` pass recorded in docs/session-2/jcstress-results.md.
#
# WHY A CURATED SUBSET (not all tests):
#   * The harness self-test (HarnessSelfTest.KnownRacyCounter) is INTENTIONALLY
#     forbidden-hitting — it must NOT be in a gate batch.
#   * The 3-actor FanOutBuffer test (TwoReadersOneWriter) needs 3 hardware
#     threads; on the 2-vCPU gate host jcstress cannot schedule 3 actors and the
#     test does not converge in bounded time. The 2-actor single-reader variants
#     cover the same RR-066 torn-read invariant deterministically.
#
# The subset is the 6 RR-002 transport interleavings (the explicit must-cover
# list) plus the decisive read-path races: FanOutBuffer wrap-around + lapped
# eviction (RR-066), VersionedConfigStore torn-version + CF-31 aliased array
# (RR-029), HamtMap consistent-version structural sharing (RR-029), the
# Phase 0 R-01' owner-thread-guard publication (no false negative once a node is
# in service), and the Phase 0-B H-3 monitor-view publication (an immutable
# snapshot published via a single volatile ref is never observed torn). The
# UnboundGuardIsInertAndRaces and PerFieldPublishCanTear companions are
# INTENTIONALLY forbidden/tear-hitting (like HarnessSelfTest.KnownRacyCounter)
# and excluded here.
#
# Exit 0 iff every selected test ran and reported zero FAILED/forbidden results.
# Usage: run-curated-subset.sh [results-dir]
# =============================================================================
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$HERE/target/jcstress.jar"
RESULTS="${1:-$(mktemp -d /tmp/jcstress-curated-XXXXXX)}"
mkdir -p "$RESULTS"
CPUS="${JCSTRESS_CPUS:-2}"

if [ ! -f "$JAR" ]; then
  echo "jcstress: uber-jar missing ($JAR) — build it first:" >&2
  echo "  ./mvnw -o -pl configd-config-store,configd-distribution-service,configd-transport -am install -Dmaven.test.skip=true" >&2
  echo "  ./mvnw -o -pl configd-jcstress clean package -Dmaven.test.skip=true" >&2
  exit 1
fi

# Explicit, fully-qualified test names so the subset is deterministic and never
# accidentally pulls in the harness self-test.
read -r -d '' TESTS <<'EOF' || true
io.configd.jcstress.transport.TcpRaftTransportRaceTest.EnqueueVsTeardownVsPublish
io.configd.jcstress.transport.TcpRaftTransportRaceTest.CasVsFinallyReset
io.configd.jcstress.transport.TcpRaftTransportRaceTest.DoubleTeardownIdempotent
io.configd.jcstress.transport.TcpRaftTransportRaceTest.PublishVsWriterStart
io.configd.jcstress.transport.TcpRaftTransportRaceTest.CloseVsInFlightConnect
io.configd.jcstress.transport.TcpRaftTransportRaceTest.DropOldestVsPoll
io.configd.jcstress.FanOutBufferReadSinceTest.ExactlyFullWrap
io.configd.jcstress.FanOutBufferReadSinceTest.LappedCursorBelowWindow
io.configd.jcstress.VersionedConfigStoreReadTest.ConsistentVersionRead
io.configd.jcstress.VersionedConfigStoreReadTest.AliasedArrayNoTear
io.configd.jcstress.HamtMapStructuralSharingTest.ConsistentMapVersion
io.configd.jcstress.RaftOwnerThreadGuardTest.OwnerGuardNoFalseNegativeInService
io.configd.jcstress.RaftMonitorViewPublicationTest.PublishedSnapshotNeverTears
EOF

# jcstress -t takes a single regex; OR the exact names together (escape dots).
REGEX="$(echo "$TESTS" | sed 's/\./\\./g' | paste -sd'|' -)"

echo "jcstress curated subset: $(echo "$TESTS" | grep -c .) tests, sanity mode, ${CPUS} CPUs"
echo "results: $RESULTS"

LOG="$RESULTS/run.log"
set +e
java -jar "$JAR" -t "($REGEX)" -m sanity -c "$CPUS" -r "$RESULTS" > "$LOG" 2>&1
rc=$?
set -e

# A clean jcstress run exits 0 AND reports zero failed results. Be defensive:
# fail the gate on a non-zero exit, on any [FAILED] marker, or on a non-zero
# "failed" count in the planned/passed/failed summary.
if [ "$rc" -ne 0 ]; then
  echo "jcstress curated subset: jcstress exited $rc"; tail -20 "$LOG"; exit 1
fi
if grep -qE '\[FAILED\]' "$LOG"; then
  echo "jcstress curated subset: a test reported [FAILED] (forbidden outcome observed)"
  grep -E '\[FAILED\]' "$LOG"; exit 1
fi
if grep -qE 'failed, [1-9][0-9]* (soft|hard) err' "$LOG" || grep -qE '[1-9][0-9]* failed,' "$LOG"; then
  echo "jcstress curated subset: non-zero failed/error count"
  grep -E 'passed,.*failed' "$LOG" | tail -1; exit 1
fi

passed="$(grep -E 'passed,.*failed' "$LOG" | tail -1 || true)"
echo "jcstress curated subset: OK — $passed"
exit 0
