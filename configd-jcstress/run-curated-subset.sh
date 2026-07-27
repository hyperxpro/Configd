#!/usr/bin/env bash
# Fast curated jcstress smoke: @JCStressTest subset in sanity mode (1 normal + 1 stress fork, 1 iter).
# Harness self-test (HarnessSelfTest.KnownRacyCounter) intentionally forbidden-hitting; excluded from gate.
# 3-actor FanOutBuffer test (TwoReadersOneWriter) needs 3 hw-threads; 2-vCPU gate host cannot schedule.
# 2-actor single-reader variants cover same torn-read invariant.
# Subset: 6 transport interleavings + decisive read-path races + rehoming no-double-ownership.
# Companions intentionally forbidden-hitting (like KnownRacyCounter) are excluded.
#
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

# Rehoming tests run at -m quick (not sanity): double-ownership race is rare (~0.05-1%);
# sanity (~56 samples) false-passes broken control ~20% of runs; quick reliably FAILs it.
read -r -d '' REHOMING_TESTS <<'EOF' || true
io.configd.jcstress.RehomingDoubleOwnershipTest.CleanHandoffNoDoubleOwnership
io.configd.jcstress.RehomingDoubleOwnershipTest.PostAdoptGuardNoFalseNegative
EOF

REGEX="$(echo "$TESTS" | sed 's/\./\\./g' | paste -sd'|' -)"
REHOMING_REGEX="$(echo "$REHOMING_TESTS" | sed 's/\./\\./g' | paste -sd'|' -)"

check_run() {
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

LOG="$RESULTS/run-sanity.log"
set +e
java -jar "$JAR" -t "($REGEX)" -m sanity -c "$CPUS" -r "$RESULTS/sanity" > "$LOG" 2>&1
rc=$?
set -e
check_run sanity "$LOG" "$rc"

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
