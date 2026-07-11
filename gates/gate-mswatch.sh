#!/usr/bin/env bash
# gate-mswatch.sh — the multi-shard client-facing WATCH non-vacuity seal.
#
# A green gate-mswatch SEALS the multi-shard watch plane (the server-side
# aggregating coordinator + the shard-complete _acl/ policy plane + the guard
# flip). The coordinator/authz tests run today only inside the umbrella
# `mvn install`, so a rename/delete would pass silently. This gate makes the
# plane NON-VACUOUS: each proof ran >=1 test with zero failures, and the
# coordinator seam is present in source (a refactor that deletes the
# multi-shard path FAILS this gate rather than silently passing).
#
# WHAT A GREEN gate-mswatch PROVES:
#   (a) coordinator  the fan-out/fan-in coordinator + per-shard completeness are green
#                    non-vacuously: MultiShardCoordinatorTest, RealHashCompletenessTest,
#                    ShardMapResolverTest, WatchMultiplexSinkTest.
#   (b) authz        the shard-complete _acl/ policy plane rejects a DENY that hashes to
#                    a NON-primary shard (the property that makes lifting the boot guard
#                    authz-safe): AclConfigPolicyLoaderMultiShardTest, incl. the explicit
#                    regression method tB6_multiShard_appliesNonPrimaryShardDeny_watchRejected.
#   (c) guard-flip   the split: N>1 + edge BOOTS serving multi-shard WATCH, the legacy
#                    whole-store SUBSCRIBE is refused per-connection unless the opt-in
#                    (LegacySubscribePartialShardViewTest), and the real server boots at
#                    N>1+edge (NGreaterThanOneBootSmokeTest).
#   (d) wire-stable  the v1/v2/v3 edge golden-fixture tests ran (the whole gate rests on
#                    "zero wire change" — the fixtures stay byte-stable).
#   (e) seam-present the coordinator seam EXISTS in source (non-vacuity: a deletion FAILS
#                    this gate, never a silent pass).
#
# Environment knobs:
#   GATE_MSWATCH_SKIP_BUILD=1  reuse already-installed module jars (local convenience;
#                              CI must not set it).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MVN="$ROOT/mvnw -B"
LOGDIR="${GATE_MSWATCH_LOG_DIR:-$(mktemp -d /tmp/gate-mswatch-XXXXXX)}"
mkdir -p "$LOGDIR"

# The two modules whose multi-shard watch tests this gate exercises.
MODULES="configd-wire,configd-distribution-service,configd-server"

echo "=== gate-mswatch (multi-shard client-facing WATCH seal) — logs in $LOGDIR ==="

fail() { echo "gate-mswatch FAIL [$1]: $2" >&2; exit 1; }

# Asserts a surefire class ran with >=1 test and zero failures/errors (non-vacuity).
assert_class_green() {
  local log="$1" cls="$2" line
  line="$(grep -E "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+.* -- in .*\.${cls}$" "$log" | tail -1 || true)"
  [ -n "$line" ] || { tail -40 "$log"; fail tests "non-vacuity: ${cls} did not run (renamed/skipped?)"; }
  echo "$line" | grep -qE "Tests run: [1-9][0-9]*, Failures: 0, Errors: 0" \
    || { echo "  $line"; fail tests "${cls} did not pass with >=1 test and 0 failures/errors"; }
  echo "gate-mswatch   ✓ ${cls}: ${line#*-- in }"
}

# Runs a targeted, offline test set across MODULES and asserts BUILD SUCCESS.
run_tests() {
  local label="$1" tests="$2" log="$3"
  $MVN -o -pl "$MODULES" test -Dtest="$tests" -Dsurefire.failIfNoSpecifiedTests=false >"$log" 2>&1 \
    || { tail -80 "$log"; fail "$label" "test run failed"; }
  grep -q "BUILD SUCCESS" "$log" || { tail -80 "$log"; fail "$label" "no BUILD SUCCESS"; }
}

assert_file() { [ -e "$ROOT/$1" ] || fail seam "missing multi-shard-watch seam file: $1"; echo "gate-mswatch   ✓ exists: $1"; }
assert_grep() { grep -qE "$2" "$ROOT/$1" 2>/dev/null || fail seam "expected /$2/ in $1 (seam regressed?)"; echo "gate-mswatch   ✓ $1 :: $2"; }

# 2-vCPU box discipline: never overlap another Maven workload.
if pgrep -f "[s]urefirebooter" >/dev/null 2>&1; then
  echo "gate-mswatch: another Maven test workload is running — refusing to start (2-vCPU box)" >&2
  exit 1
fi

# build/install the modules' deps once (so the targeted test runs are offline)
if [ "${GATE_MSWATCH_SKIP_BUILD:-0}" = "1" ]; then
  echo "gate-mswatch build: SKIPPED by GATE_MSWATCH_SKIP_BUILD=1 (reusing installed jars; CI must not do this)"
else
  echo "gate-mswatch build: installing module jars (skip tests) so the gate run is hermetic..."
  $MVN -q -pl "$MODULES" -am install -DskipTests >"$LOGDIR/build.txt" 2>&1 \
    || { tail -30 "$LOGDIR/build.txt"; fail build "module build/install failed"; }
fi

# (a) the coordinator + per-shard completeness, NON-VACUOUS
echo "gate-mswatch coordinator: aggregating fan-out/fan-in + per-shard completeness..."
COORD="$LOGDIR/coordinator.txt"
run_tests coordinator "MultiShardCoordinatorTest,RealHashCompletenessTest,ShardMapResolverTest,WatchMultiplexSinkTest" "$COORD"
assert_class_green "$COORD" "MultiShardCoordinatorTest"   # N drains, one connection; N=1 byte-identical oracle
assert_class_green "$COORD" "RealHashCompletenessTest"    # a real shardFor non-zero shard is covered + delivered
assert_class_green "$COORD" "ShardMapResolverTest"        # KEY->shardFor; PREFIX/FULL->shardIds()
assert_class_green "$COORD" "WatchMultiplexSinkTest"      # per-shard (gid,S) tagging + coalesced vectors
echo "gate-mswatch coordinator: OK"

# (b) the shard-complete _acl/ authz plane, incl. the explicit regression test
# The property that makes lifting the boot guard authz-safe: a DENY on a key hashing to a
# NON-primary shard rejects/revokes the covered watch (fails with a primary-only loader).
echo "gate-mswatch authz-b6: shard-complete _acl/ policy plane (DENY-on-non-primary-shard)..."
AUTHZ="$LOGDIR/authz.txt"
run_tests authz-b6 "AclConfigPolicyLoaderMultiShardTest" "$AUTHZ"
assert_class_green "$AUTHZ" "AclConfigPolicyLoaderMultiShardTest"
# Name the regression test explicitly (non-vacuity: its deletion FAILS the gate).
assert_grep "configd-server/src/test/java/io/configd/server/AclConfigPolicyLoaderMultiShardTest.java" \
  "tB6_multiShard_appliesNonPrimaryShardDeny_watchRejected"
echo "gate-mswatch authz-b6: OK"

# (c) the guard flip: the split + the real N>1+edge boot
echo "gate-mswatch guard-flip: legacy-SUBSCRIBE split + real N>1+edge boot..."
FLIP="$LOGDIR/guard-flip.txt"
run_tests guard-flip "LegacySubscribePartialShardViewTest,NGreaterThanOneBootSmokeTest" "$FLIP"
assert_class_green "$FLIP" "LegacySubscribePartialShardViewTest"  # split: refuse-at-N>1 / opt-in / WATCH / N=1 byte-identical
assert_class_green "$FLIP" "NGreaterThanOneBootSmokeTest"         # the real ConfigdServer boots at N>1+edge
echo "gate-mswatch guard-flip: OK"

# (d) wire byte-stability: the v1/v2/v3 golden fixtures ran (non-vacuity)
echo "gate-mswatch wire: v1/v2/v3 edge golden-fixture tests ran (zero wire change)..."
WIRE="$LOGDIR/wire.txt"
run_tests wire "EdgeFrameCodecGoldenFixtureTest,EdgeFrameCodecV2GoldenFixtureTest,EdgeFrameCodecV3GoldenFixtureTest" "$WIRE"
assert_class_green "$WIRE" "EdgeFrameCodecGoldenFixtureTest"    # v1 (0x01) frozen
assert_class_green "$WIRE" "EdgeFrameCodecV2GoldenFixtureTest"  # v2 (0x02) watch frames
assert_class_green "$WIRE" "EdgeFrameCodecV3GoldenFixtureTest"  # v3 filtered wire
echo "gate-mswatch wire: OK"

# (e) the coordinator seam EXISTS in source (a deletion FAILS the gate)
echo "gate-mswatch seam: the multi-shard coordinator seam is present in source..."
assert_grep "configd-distribution-service/src/main/java/io/configd/distribution/fanout/FanOutConnectionDriver.java" \
  "class FanOutConnectionDriver implements WatchMultiplexSink.Coordinator"
assert_grep "configd-distribution-service/src/main/java/io/configd/distribution/fanout/FanOutConnectionDriver.java" \
  "Map<Integer, FanOutSessionCore> cores"
assert_grep "configd-distribution-service/src/main/java/io/configd/distribution/fanout/FanOutConnectionDriver.java" \
  "Map<Integer, WatchMultiplexSink> sinks"
assert_file "configd-distribution-service/src/main/java/io/configd/distribution/fanout/ShardResolver.java"
assert_file "configd-server/src/main/java/io/configd/server/fanout/ShardMapResolver.java"
# The guard flip is the split, not a blanket boot refusal: the driver refuses a legacy
# SUBSCRIBE per-connection at N>1 (BAD_SUBSCRIBE) unless allowPartialShardView.
assert_grep "configd-distribution-service/src/main/java/io/configd/distribution/fanout/FanOutConnectionDriver.java" \
  "allGids.length > 1 && !config.allowPartialShardView"
echo "gate-mswatch seam: OK"

echo "=== gate-mswatch: GREEN — multi-shard client-facing WATCH plane sealed (coordinator + B6 authz + guard-flip split + wire-stable + seam present) ==="
exit 0
