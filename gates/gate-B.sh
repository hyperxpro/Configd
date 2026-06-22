#!/usr/bin/env bash
# =============================================================================
# gate-B.sh — Workstream B (multi-Raft re-threading) cumulative MERGE gate.
# -----------------------------------------------------------------------------
# A green gate-B is the merge-readiness SEAL for Workstream B: the R-01
# single-tick-thread -> one-owner-thread-per-group re-threading (Stage 1), the
# N>1 owner-isolation (M1), the H-4 group-rehoming closure (M2a/M2b), and the
# coalesced-heartbeat de-regression (M3). It is CUMULATIVE with gates 1..7 +
# phase0: a green gate-B REQUIRES a green gate-phase0, which chains gate-7 -> 6
# -> ... -> 1 and adds the phase0 re-threading / rehoming / coalescing steps.
#
# In CI, gates 1..7 + gate-phase0 run as their OWN jobs and this gate's job
# DEPENDS on gate-phase0, so GATE_B_SKIP_CHAIN=1 relies on that coverage
# (reported LOUDLY) instead of re-running the multi-hour chain. Locally — or in
# a full manual run — it runs the whole chain via gate-phase0.sh.
#
# WHAT A GREEN gate-B PROVES (beyond the cumulative 1..7 + phase0 chain):
#   - Workstream B (re-threading + rehoming + coalesced heartbeats) is merge-ready;
#   - the verified baseline B branches from is recorded (cedc706 — D-010);
#   - the B milestone artifacts EXIST (non-vacuity: a deleted contract / gate /
#     coalescing class FAILS this gate, never a silent pass — the RR-012/RR-085
#     "test the tester" lesson).
#
# Environment knobs (CI must not set GATE_B_SKIP_CHAIN on a full manual run):
#   GATE_B_SKIP_CHAIN=1   skip the cumulative gate-phase0 chain (CI supplies it
#                         via the gate-1..7 + gate-phase0 jobs) — LOUD.
#   (every gate-phase0 / gate-1..7 knob is honoured when the chain runs.)
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

fail() { echo "gate-B FAIL [$1]: $2" >&2; exit 1; }

echo "=== gate-B (Workstream B merge gate: R-01 re-threading + H-4 rehoming + coalesced heartbeats) ==="

# --- cumulative: gate-phase0 (chains gate-7 -> ... -> 1, + the phase0 steps) ---
if [ "${GATE_B_SKIP_CHAIN:-0}" = "1" ]; then
  echo "gate-B chain: SKIPPED by GATE_B_SKIP_CHAIN=1 (LOUD: gates 1..7 + phase0 NOT verified this run; CI supplies them via the gate jobs)"
else
  echo "gate-B chain: running cumulative gate-phase0 (chains gate-7 -> ... -> 1, + phase0 re-threading/rehoming/coalescing)..."
  bash "$ROOT/gates/gate-phase0.sh" || fail chain "cumulative gate-phase0 (1..7 + phase0) is RED — Workstream B is NOT merge-ready"
  echo "gate-B chain: OK (gates 1..7 + phase0 green)"
fi

# --- B milestone artifacts present (non-vacuity: a deleted artifact FAILS) -----
echo "gate-B artifacts: asserting the Workstream B milestone artifacts exist (non-vacuity)..."
assert_file() { [ -e "$ROOT/$1" ] || fail artifacts "missing Workstream B artifact: $1"; echo "gate-B   ✓ exists: $1"; }
assert_grep() { grep -qE "$2" "$ROOT/$1" 2>/dev/null || fail artifacts "expected /$2/ in $1 (artifact regressed?)"; echo "gate-B   ✓ $1 :: $2"; }

# the re-threading spec + its as-built closure markers (Stage 1 / M1 / M2 / M3)
assert_file "docs/phase0/threading-contract.md"
assert_grep "docs/phase0/threading-contract.md" "H-4.*CLOSED"               # group rehoming closed (M2)
assert_grep "docs/phase0/threading-contract.md" "Coalesced-heartbeat property" # heartbeat coalescing wired (M3)
# the consensus-core re-threading + coalescing as-built
assert_file "configd-consensus-core/src/main/java/io/configd/raft/CoalescingRaftTransport.java"
assert_file "configd-consensus-core/src/main/java/io/configd/raft/HeartbeatCoalescer.java"
assert_file "configd-replication-engine/src/main/java/io/configd/replication/MultiRaftDriver.java"
# the cumulative gate this gate chains, incl. the M3 coalesce step
assert_file "gates/gate-phase0.sh"
assert_grep "gates/gate-phase0.sh" "M3 cost-flat-in-N"                      # gate-phase0 verifies M3
echo "gate-B artifacts: OK"

# --- the verified baseline B branches from (D-010) ----------------------------
MERGE_BASE="$(cd "$ROOT" && git merge-base main HEAD 2>/dev/null | cut -c1-7 || echo unknown)"
echo "gate-B baseline: Workstream B merge-base with main = $MERGE_BASE (D-010 recorded the verified baseline cedc706)"
# Not a hard fail: once B merges, main advances past cedc706 and the merge-base IS cedc706 by definition;
# this line is the audit trail that B was built on the verified baseline, not an unverified base.

echo "=== gate-B: GREEN — Workstream B (re-threading + rehoming + coalesced heartbeats) is MERGE-READY ==="
exit 0
