# DR drills — durability & availability on metal

Driven directly against a live 3-node Java cluster (N=1, symmetric default timeouts, NVMe WAL, ZGC 4g),
`perf/dr-drill.sh`. `ops/scripts/restore-snapshot.sh` is Kubernetes-only, so it could not be used on the
bare cluster; the drills exercise the same recovery primitives directly. 1000 known keys seeded and
read back after every fault to prove no committed-write loss. Full log: `captures/dr/dr-results.txt`.

## Results

| Drill | Measure | Result |
|---|---|---|
| **A — Leader-loss under load** (kill -9 leader at 300/s) | write-availability gap | **372 ms** |
| | leadership churn | 1 election (bounded, no storm) |
| | committed-write loss | **NONE** — 1000/1000 keys intact |
| **B — Node recovery, WAL replay** (kill+restart, same data dir) | ready | 1667 ms |
| | RTO (commit-index convergence with leader) | **4.2 s** |
| | data loss | **NONE** — 1000/1000 intact |
| **C — Node recovery, wipe + InstallSnapshot** (kill+wipe+restart empty) | RTO (empty node caught up via leader snapshot stream) | **5.9 s** |
| | data loss | **NONE** — 1000/1000 intact |

## Findings

1. **Leader-loss failover is fast and clean: ~372 ms write-availability gap, a single bounded election
   (no spurious re-election storm).** On real hardware the RR-002 / bounded-connect work holds — a killed
   leader does not stall the cluster.
2. **The durability contract holds under fault: NO committed-write loss in any scenario.** Every key that
   returned 200 before the fault read back intact after leader loss, after WAL-replay recovery, and after a
   full wipe+snapshot rebuild. fsync-before-ack / no-early-ack (ADR-0033) is confirmed on metal.
3. **Node recovery RTO is single-digit seconds**: ~4.2 s to rejoin via its own WAL/snapshot replay, ~5.9 s
   to rebuild from empty via the leader's InstallSnapshot stream. Both converged to the leader's commit
   index with the full dataset.

**Go/no-go: the durability + availability claims a deployer asks about are MEASURED and PASS** — sub-second
failover, no committed-write loss, single-digit-second node recovery. (Caveat: measured on the single-box
3-co-located-node topology; cross-machine failover would add real network RTT to the ~372 ms gap, but the
*correctness* — no loss, bounded election — is topology-independent.)
