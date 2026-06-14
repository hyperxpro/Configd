# EXP-009 — Workstream C: partition & WAN chaos matrix

- **Workstream:** C (charter §5). **Register rows:** none new — exercises arch §12 against the contract.
- **Status:** in-sim control-plane matrix GREEN (6 scenarios, continuous safety + recovery); edge/linz/live cited or ENVIRONMENT-BLOCKED with exact infra.

## Approach

Two layers, per `fault-matrix.md §C`:
1. **Deterministic in-sim matrix (primary, CI):** `PartitionMatrixTest` (configd-testkit) drives a
   5-node Raft cluster over `AdversarialNetwork` through every §12 scenario, asserting the
   linearizability-relevant SAFETY oracles **every step** — single-leader-per-term, no divergent
   commit (a committed index carries the same term on all nodes), no committed-entry loss across
   partition+heal, minority-makes-no-progress / majority-continues — and measuring bounded recovery.
2. **Full Porcupine history-linearizability (env-gated):** the `configd-linz` harness (real iptables
   partitions + kill-9 via `FaultInjector`, history → Porcupine) — CI gate-2 linzgate.

## Cells + results (`-Dconfigd.partition.seeds=12`)

| Scenario | Result |
|---|---|
| Single-region isolation (minority/majority) | majority re-elects (worst 703 ticks), minority frozen, heal converges (worst 59 ticks), baseline preserved |
| Leader isolation (1 vs 4) | old leader shed by CheckQuorum, majority re-elects (worst 543 ticks), isolated leader stops committing, no split-brain |
| Asymmetric partition (A→B cut) | safety held throughout an 800-tick soak ×12 seeds; heal converges |
| Partial partition (subset of links) | connected majority progresses; no divergent commit; heal converges |
| Gray failure (+40 ms latency, no drops) | safety held; no leadership-flap storm (termBumps ≤ 25); recovers when latency clears |
| Clock skew (per-node ±hours) | consensus safety + liveness independent of synchronized clocks — **charter §6 proven** |

All 6 GREEN (`PartitionMatrixTest` 6/6). Continuous `assertSafety` per step means a single
split-brain or divergent commit at ANY tick fails the run — the oracle is not end-state-only.

## What is cited vs ENVIRONMENT-BLOCKED

- **Edge fan-out partition** (ADR-0039 ladder → DISCONNECTED → re-bootstrap → CURRENT):
  `EdgeReBootstrapOnDisconnectTest` (S3, sim) + `e2e-compose-scenario.sh` phase 3 (live
  docker-network disconnect, gate-3). Cited — no duplication.
- **Live iptables partition:** `rr-002-blackhole-drill.sh` (gate-1; `sudo -n iptables` works on this
  box). Cited.
- **Full Porcupine linearizability over a fault history:** **ENVIRONMENT-BLOCKED on this dev box** —
  **Go is absent** so the porcupine-check binary cannot be built and `PORCUPINE_BIN` is unset
  (`PorcupineChecker` resolves it from that env / `configd-linz/bin`). It runs in **CI gate-2
  linzgate** (which sets up Go and builds it). The deterministic in-sim safety invariants (C-1..C-6)
  are the always-on CI substitute. → S5 handoff.
- **Multi-host asymmetric/partial partitions across real hosts:** ENVIRONMENT-BLOCKED (single-box
  Compose can do intra-host netem/docker-network; true cross-host WAN partitions need real
  multi-host) → S5.

## Reproduction

```
./mvnw -o -pl configd-testkit test -Dtest='PartitionMatrixTest' -Dconfigd.partition.seeds=12 -Dsurefire.failIfNoSpecifiedTests=false
# Porcupine path (needs Go): bash gates/gate-2.sh  (builds porcupine, runs the linzgate step)
```
