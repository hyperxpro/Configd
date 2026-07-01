#!/usr/bin/env bash
# =============================================================================
# game-day-drill.sh — Session-6 GAME-DAY DRILL, ops/nightly lane (charter §8).
# -----------------------------------------------------------------------------
# The full multi-node drill: a healthy 3-CP + 3-edge cluster under load, then a
# sequence of injected faults, each mapped to the alert that should fire and the
# runbook whose VERBATIM steps resolve it — recovery verified within bound.
#
# The fault injection + recovery verification is the existing, CI-validated
# `gates/e2e-compose-scenario.sh` (a gate-3 step): it kills the leader, partitions
# an edge, and joins a fresh edge, asserting failover with NO monotonic-read
# violation, the staleness ladder + re-bootstrap, and post-heal byte-equality.
# This wrapper layers the S6 OPERABILITY OVERLAY on top: the drill→alert→runbook
# mapping, so an operator running the drill sees exactly which alert each fault
# trips and which runbook resolves it.
#
# The CI-gated subset of this loop (alert→runbook→recovery closes for one scenario)
# is `GameDayDrillTest` in gate-6 (fast, in-process). THIS script is the fuller
# multi-node lane — Docker-heavy, run on the nightly/ops schedule and CAPTURED.
#
# Requires: Docker. Run: bash gates/game-day-drill.sh   (logs the overlay + scenario)
# =============================================================================
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cat <<'OVERLAY'
=== GAME-DAY DRILL — fault → alert → runbook overlay (charter §8) ===

 Drill phase (e2e-compose-scenario.sh)   Alert that fires                     Runbook (verbatim steps)
 --------------------------------------   ----------------------------------   ----------------------------------
 1 PROPAGATION (healthy under load)       (none — dashboards live, quiet)      ops/runbooks/propagation-delay.md
 2 KILL LEADER (failover)                 ConfigdControlPlaneAvailability      ops/runbooks/control-plane-down.md
                                          (+ term churn on the CP board)
 3 PARTITION an edge (staleness ladder)   ConfigdEdgeStalenessWarn ->          ops/runbooks/edge-catchup-storm.md
                                          ConfigdEdgeStalenessDegraded         (+ propagation-delay.md)
 4 BOOTSTRAP a fresh edge mid-load        (recovery: staleness -> CURRENT)     deployment.md §1 (cold start)

 The pass criterion: every phase's recovery assertion in e2e-compose-scenario.sh
 holds (failover with no monotonic-read regression; staleness returns to CURRENT;
 fresh edge converges byte-equal) AND, scraped at /metrics during each fault, the
 mapped alert's series crosses its PROPOSED threshold then clears on recovery.
 A drill that needs builder improvisation to pass is a FAILING drill — file the gap.
OVERLAY

echo ""
echo "=== running the multi-node fault/recovery drill (e2e-compose-scenario.sh) ==="
bash "$ROOT/gates/e2e-compose-scenario.sh" "$@"
echo ""
echo "=== GAME-DAY DRILL: multi-node fault/recovery phases PASSED (overlay above) ==="
echo "Note: rolling upgrade + rollback within wire-version 0x01 is proven by the gate-6"
echo "wire-compat + durable-restart tests (the deployment runbook §3); a live"
echo "cross-binary N<->N+1 fleet measurement is the S7.5 item (needs a v0.2 artifact)."
