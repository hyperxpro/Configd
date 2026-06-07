#!/usr/bin/env bash
# GATE (iii)+(iv): run the unmodified binary under N seeded fault+workload
# schedules on BOTH 3- and 5-node clusters (must be LINEARIZABLE, faults active
# throughout), and prove reproducibility (same seed -> byte-identical
# schedule-<seed>.json, run twice).
#
# Usage: run-gate.sh "<seed1 seed2 ...>"   (default: a fixed set)
set -u
cd "$(dirname "${BASH_SOURCE[0]}")/../.." || exit 2
ROOT="$(pwd)"
JAR="$ROOT/configd-server/target/configd-server-0.1.0-SNAPSHOT.jar"
CP="$ROOT/configd-linz/target/classes"
export PORCUPINE_BIN="${PORCUPINE_BIN:-$ROOT/configd-linz/bin/porcupine-check}"
SEEDS="${1:-1001 1002 1003 1004 1005}"

br=11000; ba=10000
green_run() { # nodes seed
  local nodes="$1" seed="$2"
  br=$((br+14)); ba=$((ba+14))
  local log="/tmp/gate-n$nodes-$seed.log"
  timeout 180 java --enable-preview -cp "$CP" io.configd.linz.runner.HarnessMain \
    --seed "$seed" --nodes "$nodes" --clients 4 --keys 8 --duration 15000 \
    --base-raft $br --base-api $ba --jar "$JAR" > "$log" 2>&1
  local code=$?
  pkill -9 -f 'configd-server-0.1.0-SNAPSHOT.jar' 2>/dev/null; sleep 1
  echo "  n=$nodes seed=$seed exit=$code  $(grep VERDICT "$log"|tail -1)  faults=$(grep -c '^\[fault\]' "$log")  $(grep -oE 'recorded [0-9]+ ops' "$log"|head -1)"
  return $code
}

fail=0
echo "================ GATE (iii): unmodified GREEN on 3- and 5-node ================"
for n in 3 5; do
  for s in $SEEDS; do
    green_run "$n" "$s" || fail=1
  done
done

echo "================ GATE (iv): reproducibility (same seed -> identical schedule) ================"
rs=777
# Generate the FULL schedule (real duration -> real faults + workload) twice, no cluster run.
rm -rf /tmp/repro-a /tmp/repro-b
java --enable-preview -cp "$CP" io.configd.linz.runner.HarnessMain --seed $rs --nodes 3 \
  --clients 4 --keys 8 --duration 15000 --schedule-only true --jar "$JAR" --out /tmp/repro-a >/dev/null 2>&1 || true
java --enable-preview -cp "$CP" io.configd.linz.runner.HarnessMain --seed $rs --nodes 3 \
  --clients 4 --keys 8 --duration 15000 --schedule-only true --jar "$JAR" --out /tmp/repro-b >/dev/null 2>&1 || true
if diff -q /tmp/repro-a/schedule-$rs-n3.json /tmp/repro-b/schedule-$rs-n3.json >/dev/null 2>&1; then
  faults=$(grep -c offsetMs /tmp/repro-a/schedule-$rs-n3.json)
  echo "  seed $rs: schedule-$rs-n3.json byte-identical across two runs ✓ ($(wc -l </tmp/repro-a/schedule-$rs-n3.json) lines, $faults scheduled events)"
  echo "  sha256: $(sha256sum /tmp/repro-a/schedule-$rs-n3.json | cut -d' ' -f1) == $(sha256sum /tmp/repro-b/schedule-$rs-n3.json | cut -d' ' -f1)"
else
  echo "  seed $rs: schedules DIFFER — reproducibility FAILED"; fail=1
fi

echo "==============================================================================="
[ "$fail" = 0 ] && echo "GATE (iii)+(iv) PASS" || echo "GATE (iii)+(iv) FAIL"
exit $fail
