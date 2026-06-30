# N×knee — aggregate write-throughput scaling vs shard count N

**Topology:** 3 co-located server JVMs on one m6id.4xlarge (16 vCPU, NVMe, ZGC 4g/node), plaintext
loopback — the established wsC/s75 methodology, comparable to the RR-113 ~800/s baseline. `StaticShardMap`
routes `(scope,key)→shard`; the **shard-aware** load driver replicates `shardFor` and keeps a per-shard
leader pointer (learned from `X-Leader-Hint`), so every PUT lands on the node that leads that key's shard.

## Methodology note — why closed-loop calibration

Three iterations were needed for an honest number:
1. **Leader co-location via election-timeout asymmetry** (short leader / long follower) — REJECTED: it makes
   the leader fragile at the knee and blocks failover, so the group goes leaderless and the "knee" is an
   artefact, not the real one.
2. **Open-loop shard-aware ladder** — REJECTED as the primary: above the knee the single open-loop driver's
   worker pool saturates (`rejected_backpressure` in the tens of thousands, CO-latency → 1.5s) while the
   **servers and NVMe sit idle** — it measures the driver, not the cluster.
3. **Closed-loop shard-aware calibration** (N workers send-as-fast-as-possible, routed per shard) — the
   sustained 200/s is the cluster's real ceiling at that concurrency. This is the reported method.

## Results — sustained committed writes/s (closed-loop calibrate, 20s, best stable point per N)

Stable = low elections (no leadership churn) and low non-200.

| N (shards) | sustained writes/s | vs N=1 | at concurrency | elections (churn) | CPU busy | NVMe util |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | ~450 | 1.0× | 512 | 26 (churning) | ~18% | ~21% |
| 2 | ~660 | 1.5× | 128 | 19 (churning) | ~21% | ~19% |
| 4 | ~1100 | 2.4× | 128 | 2 (clean) | ~30% | ~13% |
| 8 | ~1110 | 2.5× | 128–256 | 0–1 (clean) | ~30% | ~13–15% |

Default 150/300/50 ms timeouts. (Full per-concurrency ladder + the churn-mitigated 2–3 s election-timeout
arm are in `captures/` — the churn-mitigated arm was WORSE under closed-loop overload at low N: a 2–3 s
timeout means a starved leader recovers slowly, so the cluster thrashes leaderless. Neither timeout helps
once the offered load exceeds capacity; the as-built default is the right operating point below the knee.)

## Findings

1. **The single-group write ceiling (~450/s closed-loop, ~800/s open-loop borderline) is leadership churn
   — NOT CPU or disk.** At the N=1 knee the cluster shows 26–43 leader elections in 20 s while CPU is ~20%
   and NVMe ~16% utilised. This is RR-113 / the S7.5 heartbeat-starvation finding, now confirmed on metal:
   the bottleneck is the single per-group tick/heartbeat thread losing leadership under load, not fsync.

2. **Sharding DOES lift aggregate throughput — ~2.5× by N=4 (450→1100/s)** — and crucially it REMOVES the
   churn: N=4/N=8 run with 0–2 elections (vs N=1's 26–43). Spreading writes across more groups/tick threads
   keeps each group below its churn point. This validates the Phase 0/1 sharding direction directionally.

3. **But aggregate PLATEAUS at ~1100/s by N=4 — N=8 adds nothing — at only ~30% CPU, ~15% disk, zero
   churn.** No obvious resource is saturated.

4. **The plateau is the CLUSTER, not the load generator.** Multi-driver test on one N=8 cluster: a single
   calibrate driver sustained **1124/s**; **three concurrent drivers summed to ~593/s** (196+191+206) with
   high non-200 + retargets. Pushing more load does not increase throughput — it overloads the cluster into
   churn/shedding and *reduces* it. So ~1100/s is a hard ceiling of this single 3-co-located-node box (3
   JVMs sharing 16 cores + one NVMe + loopback replication), not the single-JVM driver.

## Go/no-go interpretation

- **Confirmed on metal:** the single-group write ceiling is leadership churn (RR-113), and sharding both
  **lifts aggregate ~2.5× (→~1100/s)** and **removes the churn**. The Phase 0/1 sharding direction is
  directionally validated.
- **NOT proven:** the "scales ~N× horizontally" claim. That claim is about N groups across **separate
  machines** (each its own CPU/disk/NIC); a single box with 3 co-located JVMs sharing one NVMe + loopback
  cannot represent it, and the data shows a ~1100/s single-box ceiling reached by N=4. **A true
  multi-machine N×knee (3+ separate instances × N groups) is the remaining empirical item** — a v1 ship
  caveat or a v2 measurement, not closed by this session.
- This is a conservative floor: real multi-machine deployment with leaders balanced across separate
  hardware should exceed ~1100/s aggregate; by how much is unmeasured.
