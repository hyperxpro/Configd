#!/usr/bin/env bash
# GATE (ii) -- DISCRIMINATION. For each seeded bug: run the scenario against the
# UNMUTATED jar (must be GREEN/linearizable, the control), then apply the bug
# patch, rebuild a scratch jar, run again (must go RED/non-linearizable), then
# revert. Production source is left clean -- the bug only ever exists in a scratch
# build. If a seeded bug does NOT go RED, the harness is blind to that class:
# STOP (this script exits non-zero) and fix the harness before trusting any run.
#
# Usage: run-discrimination.sh [lost-acked-write|stale-read|both]
set -u
cd "$(dirname "${BASH_SOURCE[0]}")/../.." || exit 2
ROOT="$(pwd)"
JAR="$ROOT/configd-server/target/configd-server-0.1.0-SNAPSHOT.jar"
DISC="$ROOT/configd-linz/discrimination"
export PORCUPINE_BIN="${PORCUPINE_BIN:-$ROOT/configd-linz/bin/porcupine-check}"
CP="$ROOT/configd-linz/target/classes"
WHICH="${1:-both}"

# clean is REQUIRED: `-pl configd-server -am package` without it can reuse a stale shaded jar
# whose embedded upstream class (e.g. the patched FileStorage) is the OLD code, so the "mutated"
# build silently contains no bug and the gate falsely reports the harness blind.
build_jar() { ./mvnw -q -pl configd-server -am clean package -DskipTests >/tmp/disc-build.log 2>&1; }

# run_scenario <MainClass> <baseRaft> <baseApi> <label> ; echoes exit code
# (each scenario uses its own sensible node-count default: lost-write=3, stale-read=5)
run_scenario() {
  local main="$1" br="$2" ba="$3" label="$4" log="/tmp/disc-$4.log"
  timeout 200 java --enable-preview -cp "$CP" "$main" \
    --jar "$JAR" --base-raft "$br" --base-api "$ba" --label "$label" >"$log" 2>&1
  local code=$?
  pkill -9 -f 'configd-server-0.1.0-SNAPSHOT.jar' 2>/dev/null; sleep 1
  echo "$code"
}

# expect <wantCode> <main> <br> <ba> <label> : retries up to 3x for the wanted code
expect() {
  local want="$1" main="$2" br="$3" ba="$4" label="$5"
  local got=99 i=0
  while [ "$i" -lt 3 ]; do
    i=$((i+1))
    got=$(run_scenario "$main" "$br" "$ba" "$label-try$i")
    echo "    [$label try$i] exit=$got  $(grep VERDICT /tmp/disc-$label-try$i.log | tail -1)" >&2
    [ "$got" = "$want" ] && { echo "$got"; return 0; }
  done
  echo "$got"; return 1
}

discriminate() { # <name> <Main> <br> <ba> <patch>
  local name="$1" main="$2" br="$3" ba="$4" patch="$5" rc=0
  echo "================ DISCRIMINATION: $name ================"
  echo "--- control (unmutated jar) must be GREEN (exit 0) ---"
  if [ "$(expect 0 "$main" "$br" "$ba" "$name-control")" != 0 ]; then
    echo "!!! CONTROL did not go GREEN — investigate before trusting discrimination"; rc=1
  else echo "    control GREEN ✓"; fi

  echo "--- apply seed patch + rebuild scratch jar ---"
  git apply "$patch" || { echo "!!! patch failed to apply"; return 2; }
  if ! build_jar; then echo "!!! scratch build failed"; git apply -R "$patch"; return 2; fi

  echo "--- mutated build must go RED (exit 1 / NON-LINEARIZABLE) ---"
  if [ "$(expect 1 "$main" "$br" "$ba" "$name-mutated")" != 1 ]; then
    echo "!!! MUTATED build did NOT go RED — harness is BLIND to $name. STOP."; rc=1
  else echo "    mutated RED ✓"; fi

  echo "--- revert patch + rebuild clean jar ---"
  git apply -R "$patch"
  build_jar
  return $rc
}

overall=0
if [ "$WHICH" = "lost-acked-write" ] || [ "$WHICH" = "both" ]; then
  discriminate "lost-acked-write" io.configd.linz.runner.LostWriteScenario 9400 8400 "$DISC/lost-acked-write.patch" || overall=1
fi
if [ "$WHICH" = "stale-read" ] || [ "$WHICH" = "both" ]; then
  discriminate "stale-read" io.configd.linz.runner.StaleReadScenario 9500 8500 "$DISC/stale-read.patch" || overall=1
fi

echo "======================================================="
if [ "$overall" = 0 ]; then echo "DISCRIMINATION PASS: both seeds turn the checker RED; controls GREEN."
else echo "DISCRIMINATION FAIL: see above — do not proceed to the green gate."; fi
# safety: ensure source is clean
git checkout -- configd-common configd-consensus-core 2>/dev/null
exit $overall
