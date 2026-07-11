#!/usr/bin/env bash
# fsync-probe.sh - verify the WAL device's fsync method + latency on target hardware.
#
# Configd acknowledges a write only after a durable majority fsync, so the WAL device MUST
# actually flush to durable media on fsync/fdatasync (no lying write cache). Run this on the
# real production storage BEFORE going live. This is an OPERATOR step; it runs no Configd code.
#
# Preferred: pg_test_fsync (from postgresql-contrib) - the canonical fsync-method benchmark.
# Fallback:  a dd + fdatasync micro-probe when pg_test_fsync is unavailable.
#
# Usage:  ops/scripts/fsync-probe.sh [DATA_DIR]
#   DATA_DIR defaults to the current directory; point it at the volume backing --data-dir.
#
# See also: ops/runbooks/disk-full-fsync.md (the durability/fsync runbook).
set -euo pipefail

DATA_DIR="${1:-.}"
TEST_FILE="${DATA_DIR%/}/.configd-fsync-probe.$$"
cleanup() { rm -f "$TEST_FILE"; }
trap cleanup EXIT

echo "== Configd fsync probe on: $DATA_DIR =="
echo "-- device / mount --"
df -h "$DATA_DIR" || true
findmnt -T "$DATA_DIR" 2>/dev/null || mount | grep -F "$(df -P "$DATA_DIR" | awk 'NR==2{print $6}')" || true

if command -v pg_test_fsync >/dev/null 2>&1; then
  echo "-- pg_test_fsync (5s/method) on $DATA_DIR --"
  # pg_test_fsync writes its own temp file into --filename's directory.
  pg_test_fsync --filename="$TEST_FILE" --secs-per-test=5
  echo
  echo "Interpretation: the WAL uses fdatasync/O_DSYNC. Confirm the reported open_datasync /"
  echo "fdatasync op/s is consistent with a device that truly flushes (an implausibly high number"
  echo "usually means a volatile write cache that is NOT crash-safe)."
else
  echo "-- pg_test_fsync not found; running dd + fdatasync fallback (1000 x 4KiB synced writes) --"
  # oflag=dsync forces an fdatasync-equivalent per block; time 1000 x 4KiB writes.
  START=$(date +%s.%N)
  dd if=/dev/zero of="$TEST_FILE" bs=4096 count=1000 oflag=dsync conv=fsync 2>/dev/null
  END=$(date +%s.%N)
  ELAPSED=$(awk "BEGIN{print $END-$START}")
  OPS=$(awk "BEGIN{printf \"%.0f\", 1000/$ELAPSED}")
  echo "1000 synced 4KiB writes in ${ELAPSED}s => ~${OPS} synced-writes/s"
  echo "Interpretation: a healthy enterprise SSD with a power-loss-protected cache typically shows"
  echo "hundreds to low-thousands of synced writes/s here; tens-of-thousands usually means fsync is"
  echo "NOT reaching durable media (a lying/volatile write cache) - a durability hazard for Configd."
  echo "Install postgresql-contrib for the authoritative pg_test_fsync method comparison."
fi

echo "== fsync probe complete =="
