# N x knee -- aggregate write-throughput scaling vs. shard count N

**Topology:** 3 co-located server JVMs on one m6id.4xlarge (16 vCPU, NVMe, ZGC 4g/node), plaintext
loopback -- the same methodology as the `wsC-ladder`/`s75` scripts, comparable to the established
~800/s single-group baseline. `StaticShardMap` routes `(scope,key) -> shard`; the shard-aware load
driver replicates `shardFor` and keeps a per-shard leader pointer (learned from `X-Leader-Hint`), so
every PUT lands on the node that leads that key's shard.

## Methodology note -- why closed-loop calibration

Three approaches were tried before landing on an honest number:

1. **Leader co-location via election-timeout asymmetry** (short leader / long follower) -- rejected: it
   makes the leader fragile at the knee and blocks failover, so the group goes leaderless and the "knee"
   becomes an artifact of the timeout choice, not the real one.
2. **Open-loop shard-aware ladder** -- rejected as the primary method: above the knee the single
   open-loop driver's worker pool saturates (`rejected_backpressure` in the tens of thousands, CO-latency
   to 1.5s) while the servers and NVMe sit idle -- it ends up measuring the driver, not the cluster.
3. **Closed-loop shard-aware calibration** (N workers send-as-fast-as-possible, routed per shard) -- the
   sustained rate is the cluster's real ceiling at that concurrency. This is the method reported below.

## Two single-group numbers (open-loop vs. closed-loop)

There are two legitimate "single writer" figures and they should not be conflated:

- **Open-loop at-rate knee, about 800/s** (fed at a target rate). Reproduced here: N=1 sustained 758/s
  at 800 offered (a minor 5% shed, 2 elections) before collapsing at 1200. This is the representative
  single-writer number.
- **Closed-loop calibrate, about 450/s** (workers send as fast as possible). This over-drives the single
  leader into heartbeat-starvation churn (26-43 elections/20s), so it reports a lower, worst-case-under-
  overload figure -- not the representative single-group throughput.

The sharded ceiling below is a closed-loop number (~1100/s). Comparing it to the ~800/s open-loop knee
gives the honest single-box lift of ~1.4x; comparing it to the ~450 closed-loop floor (apples-to-oranges)
would overstate it to ~2.5x -- that framing should not be used.

## Results -- sustained committed writes/s (closed-loop calibrate, 20s, best stable point per N)

Stable = low elections (no leadership churn) and low non-200.

| N (shards) | closed-loop sustained writes/s | at concurrency | elections (churn) | CPU busy | NVMe util |
|---:|---:|---:|---:|---:|---:|
| 1 | ~450 (churn-floor; open-loop knee ~800) | 512 | 26 (churning) | ~18% | ~21% |
| 2 | ~660 | 128 | 19 (churning) | ~21% | ~19% |
| 4 | ~1100 | 128 | 2 (clean) | ~30% | ~13% |
| 8 | ~1110 | 128-256 | 0-1 (clean) | ~30% | ~13-15% |

Single-box sharding lift = ~1100/s sharded / ~800/s single-group open-loop knee, about 1.4x, plateauing
by N=4.  (The ~2.5x over the closed-loop ~450 floor reflects churn-relief, not raw-throughput scaling.)

Default timeouts are 150/300/50 ms. The full per-concurrency ladder and a churn-mitigated 2-3 s
election-timeout arm are in `captures/` -- the churn-mitigated arm was worse under closed-loop overload
at low N: a 2-3 s timeout means a starved leader recovers slowly, so the cluster thrashes leaderless.
Neither timeout helps once offered load exceeds capacity; the default is the right operating point below
the knee.

## Findings

1. **The single-group write ceiling (~450/s closed-loop, ~800/s open-loop borderline) is leadership
   churn, not CPU or disk.** At the N=1 knee the cluster shows 26-43 leader elections in 20 s while CPU
   is ~20% and NVMe ~16% utilized. This confirms the earlier heartbeat-starvation finding on real
   hardware: the bottleneck is the single per-group tick/heartbeat thread losing leadership under load,
   not fsync.

2. **Sharding lifts the single-box aggregate only ~1.4x (about 800 to 1100/s), but it removes the
   churn**, which is the more valuable property: N=4/N=8 run with 0-2 elections vs. N=1's 26-43 under
   closed-loop load. Spreading writes across more groups/tick threads keeps each group below its
   heartbeat-starvation point, so the cluster stays stable under aggressive load where a single group
   degrades to ~450/s. The raw throughput gain on one box is modest (~1.4x); the stability gain is real.

3. **Aggregate plateaus at ~1100/s by N=4 -- N=8 adds nothing -- at only ~30% CPU, ~15% disk, zero
   churn.** No obvious resource is saturated.

4. **The plateau is the cluster, not the load generator.** A multi-driver test on one N=8 cluster: a
   single calibrate driver sustained 1124/s; three concurrent drivers summed to only ~593/s (196+191+206)
   with high non-200 and retargets. Pushing more load does not increase throughput -- it overloads the
   cluster into churn/shedding and reduces it. So ~1100/s is a hard ceiling of this single 3-co-located-
   node box (3 JVMs sharing 16 cores, one NVMe, loopback replication), not a limit of the single-JVM
   driver.

## Interpretation

Confirmed on metal: the single-group write knee is ~800/s and is leadership-churn-bound. Sharding lifts
the single-box aggregate only ~1.4x (~800 to ~1100/s) but removes the churn -- validated for stability;
for raw throughput scaling on a single box, it is a modest win, not an N-x win (see below).

Not proven here: the "scales ~N x horizontally" claim. That claim is about N groups across separate
machines (each with its own CPU/disk/NIC); a single box with 3 co-located JVMs sharing one NVMe and
loopback cannot represent it, and the data shows a ~1100/s single-box ceiling reached by N=4. A true
multi-machine N x knee (3+ separate instances x N groups) is the remaining empirical question, closed by
the follow-up horizontal-scale run (see `docs/archive/measurement/ec2-horizontal-2026-07-01/`).

This is a conservative floor: real multi-machine deployment with leaders balanced across separate
hardware exceeds ~1100/s aggregate; the horizontal-scale run below quantifies by how much.
