#!/usr/bin/env bash
# A fast curated jcstress smoke.
#
# Runs the curated, deterministic subset of @JCStressTest classes in jcstress
# "sanity" mode (1 normal + 1 stress fork, 1 iteration each) -- a fast smoke that
# proves the race detector runs end-to-end and surfaces no forbidden outcome on
# the real structures. The full, longer-confidence run is the operator's
# `-m default/stress` pass.
#
# Why a curated subset (not all tests):
#   * The harness self-test (HarnessSelfTest.KnownRacyCounter) is intentionally
#     forbidden-hitting -- it must NOT run in a gate batch.
#   * The 3-actor FanOutBuffer test (TwoReadersOneWriter) needs 3 hardware
#     threads; on the 2-vCPU gate host jcstress cannot schedule 3 actors and the
#     test does not converge in bounded time. The 2-actor single-reader variants
#     cover the same torn-read invariant deterministically.
#
# The subset is the 6 transport interleavings (the explicit must-cover list) plus
# the decisive read-path races: FanOutBuffer wrap-around + lapped eviction,
# VersionedConfigStore torn-version + aliased array, HamtMap consistent-version
# structural sharing, the owner-thread-guard publication (no false negative once a
# node is in service), the monitor-view publication (an immutable snapshot
# published via a single volatile ref is never observed torn), and the rehoming
# no-double-ownership proof (RehomingDoubleOwnershipTest: the volatile owner field
# plus the detach->adopt barrier never let two owners both own the group, and a
# re-bind opens no false negative). The UnboundGuardIsInertAndRaces,
# PerFieldPublishCanTear, and RehomingDoubleOwnershipTest.BrokenHandoffDoubleOwnership
# companions are intentionally forbidden/tear-hitting (like
# HarnessSelfTest.KnownRacyCounter) and excluded here.
#
# Exit 0 iff every selected test ran and reported zero FAILED/forbidden results.
# Usage: run-curated-subset.sh [results-dir]
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

# The rehoming no-double-ownership proofs run at -m quick, NOT sanity. The double-ownership race is
# rare (~0.05-1% even with millions of samples); sanity mode (~56 samples) false-passes even the
# broken control ~20% of runs, so a sanity clean-pass cannot catch a double-ownership regression.
# -m quick reliably FAILS the broken control, so the clean pass here carries real weight.
read -r -d '' REHOMING_TESTS <<'EOF' || true
io.configd.jcstress.RehomingDoubleOwnershipTest.CleanHandoffNoDoubleOwnership
io.configd.jcstress.RehomingDoubleOwnershipTest.PostAdoptGuardNoFalseNegative
EOF

# jcstress -t takes a single regex; OR the exact names together (escape dots).
REGEX="$(echo "$TESTS" | sed 's/\./\\./g' | paste -sd'|' -)"
REHOMING_REGEX="$(echo "$REHOMING_TESTS" | sed 's/\./\\./g' | paste -sd'|' -)"

# A clean jcstress run exits 0 AND reports zero failed results. Be defensive: fail the gate on a
# non-zero exit, any [FAILED] marker, or a non-zero failed/error count. Called DIRECTLY (not in $())
# so its exit propagates to the gate.
check_run() { # $1=label $2=logfile $3=rc
  local label="$1" log="$2" rc="$3"
  if [ "$rc" -ne 0 ]; then
    echo "jcstress curated subset ($label): jcstress exited $rc"; tail -20 "$log"; exit 1
  fi
  if grep -qE '\[FAILED\]' "$log"; then
    echo "jcstress curated subset ($label): a test reported [FAILED] (forbidden outcome observed)"
    grep -E '\[FAILED\]' "$log"; exit 1
  fi
  if grep -qE 'failed, [1-9][0-9]* (soft|hard) err' "$log" || grep -qE '[1-9][0-9]* failed,' "$log"; then
    echo "jcstress curated subset ($label): non-zero failed/error count"
    grep -E 'passed,.*failed' "$log" | tail -1; exit 1
  fi
}

echo "jcstress curated subset: $(echo "$TESTS" | grep -c .) tests sanity + $(echo "$REHOMING_TESTS" | grep -c .) rehoming tests quick, ${CPUS} CPUs"
echo "results: $RESULTS"

# (1) the bulk at -m sanity — a fast smoke for the frequent races.
LOG="$RESULTS/run-sanity.log"
set +e
java -jar "$JAR" -t "($REGEX)" -m sanity -c "$CPUS" -r "$RESULTS/sanity" > "$LOG" 2>&1
rc=$?
set -e
check_run sanity "$LOG" "$rc"

# (2) the rehoming no-double-ownership proofs at -m quick — so a double-ownership regression is
# RELIABLY caught (sanity false-passes even the broken control ~20% of runs; see the comment above).
QLOG="$RESULTS/run-rehoming-quick.log"
set +e
java -jar "$JAR" -t "($REHOMING_REGEX)" -m quick -c "$CPUS" -r "$RESULTS/rehoming" > "$QLOG" 2>&1
qrc=$?
set -e
check_run rehoming-quick "$QLOG" "$qrc"

sanity_passed="$(grep -E 'passed,.*failed' "$LOG" | tail -1 || true)"
quick_passed="$(grep -E 'passed,.*failed' "$QLOG" | tail -1 || true)"
echo "jcstress curated subset: OK — sanity[$sanity_passed] rehoming-quick[$quick_passed]"
exit 0
