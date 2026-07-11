# ADR (multi-Raft): Express the Write-Throughput Target as a Derived, Falsifiable Aggregate (Per-Shard Rate x Shards x Efficiency)

## Status

Accepted. This is the design as built: the write-throughput target is expressed as a derived aggregate
(per-shard rate x shard count x efficiency factor), not a single flat number.

The per-shard knee has been measured on hardware (m6id.4xlarge). Result: the re-threaded single group
(owner-executor pool + coalesced heartbeats + Netty-Epoll consensus wire) caps at ~800/s, unchanged from
the pre-rethreading baseline. The rework did not raise the single-group knee and structurally could not:
at N=1 a group binds to one owner thread; the rework's parallelism is across groups, and its heartbeat
coalescing is a flat-in-N aggregate property. The only lever that lifts sustained throughput above ~800/s
is sharding across multiple Raft groups. So `per_shard_knee` is a measured ~800/s (co-location-confounded;
a dedicated-host re-measure is still owed before treating any particular N as final), and
`N ~ 10000/(800x0.75) ~ 17` to reach 10k/s sustained. Admission control was re-validated on the
re-threaded code (churn-collapse turns into a graceful `429`-shed, ~2x effective throughput under flood) -
it stabilizes the ~800/s knee under burst, it does not raise it.

Renegotiates the write-throughput target, following the precedent set by ADR-0031, which renegotiated
the write-*availability* target the same way. Depends on
[adr-multiraft-topology](adr-multiraft-topology.md) for the shard-count derivation.

## Context

The write-throughput target was originally stated as a flat number: 10k/s sustained baseline, 100k/s
burst. That number was mis-specified against a single-group design and never validated:

- ADR-0023 asserted a "measured ~10k commits/s" single-Raft ceiling, but that figure was modeled, never
  measured - there was no artifact behind it. An earlier hypothesis that the in-memory commit mechanism
  could do 815k/s and only host capacity limited it to 10k/s was likewise unvalidated.
- The first real-hardware measurement (m6id.4xlarge, 16 vCPU, instance-store NVMe, 3 co-located nodes)
  found the as-built single group sustains ~800 writes/s stably and collapses into leadership churn at
  ~1000-1200/s. The ceiling is the single-threaded consensus path (heartbeat starvation), not fsync
  (`iostat` f/s = 0), not CPU (86% idle), not disk. 10k/s on a single group is unreachable with that
  model.

So the 10k/s figure was a number nobody had validated, attached to a topology that cannot reach it. With
multi-Raft adopted, the target is re-expressed as what it actually is: an aggregate across shards,
derived from a measured per-shard rate, with an honest efficiency factor - and proven on real hardware,
not loopback.

## The per-shard envelope (the evidence that bounds a single tuned group)

| Bound | Value | Source |
|---|---|---|
| As-built single group, co-located NVMe | ~800/s stable (collapse ~1000/s) | measured on hardware, co-location-confounded |
| Single group after the owner-executor-pool and coalesced-heartbeat rework, co-located NVMe | ~800/s stable (collapse ~1000/s), unchanged | measured; the rework lifts the aggregate via sharding, not the single-group knee |
| Single group + group commit | flat, no change | fsync is free on instance-store NVMe (`f/s` = 0); group commit is load-bearing on EBS/SAN/HDD |
| Single group + admission control (mitigation) | ~864/s under a 2000/s flood (was 432/s), leader stable | measured; a failure-mode fix, not a ceiling raise |
| etcd, single group + batching | ~10k/s | public etcd evidence; the realistic tuned single-group envelope ceiling on dedicated hardware |

Reading: the measured Configd as-built knee (~800/s) is the conservative floor of the per-shard envelope;
etcd's ~10k/s batched single-group is the tuned ceiling of the envelope. The root cause (single-thread
heartbeat starvation) is an implementation ceiling, not an architectural one, so the consensus-thread fix
(coalesced heartbeats + sharded tick-executor pool + per-tick broadcast-coalescing) plus group
commit/batching was expected to raise the per-shard knee materially above ~800/s on a dedicated host.
Where in [800/s, ~10k/s] it lands was, at the time, the single most important number still owed to
measurement (dedicated, one-node-per-host, multi-box).

That expectation was partly wrong, and the correction is the key finding here: the consensus-thread fix
(coalesced heartbeats + owner-executor pool) did not raise the single-group knee - it is still ~800/s, the
same as the baseline curve - because at N=1 a group is still a single owner thread; the fix's parallelism
and its flat-in-N heartbeat coalescing are aggregate, multi-group properties. So on this co-located box
the per-shard knee landed at ~800/s, not higher. The remaining upside toward etcd's ~10k/s is (a) sharding
(N groups, the multi-Raft thesis) and (b) a dedicated-host re-measure of the single-group knee, which is
still owed - co-location plausibly suppresses it, but that has not been isolated and confirmed. Group
commit is a no-op here (fsync is free on instance-store NVMe, confirmed at 16,986 fdatasync IOPS).

## Decision

**The write-throughput target is expressed as a derived, falsifiable aggregate:**

```
sustained_aggregate_commit_rate  =  per_shard_knee  x  N_shards  x  efficiency_factor
```

- **`per_shard_knee`** - the measured stable single-group commit rate on the deployment hardware, after
  the consensus-thread fix and group commit. Floor today: ~800/s (co-located). A dedicated-host
  re-measure is still owed before treating any number here as final.
- **`N_shards`** - the static shard count (see [adr-multiraft-topology](adr-multiraft-topology.md));
  `configd.raft.shardCount` is an operator setting, capped at 16.
- **`efficiency_factor`** - ~0.7-0.8, accounting for coalesced-heartbeat/tick overhead, imperfect key
  balance, leader skew across a small node count, and cross-shard fan-out. Not 1.0; stated honestly.

**The write-throughput target stays 10k/s sustained, expressed as this derived aggregate rather than a
single-group promise.** The consensus-thread fix the formula assumes (owner-executor pool and coalesced
heartbeats) has since been built; the per-shard knee itself is still pending a clean dedicated-host
re-measure, so `800 x 16 x 0.75 ~ 9.6k/s` is the balanced, all-shards-live lab ceiling of the formula, not
a validated 10k/s result. The realistic range: balanced/all-live ~ `N x knee x efficiency` (~9.6k/s at the
as-built knee); under a node outage ~ `(2/3) x N x knee x efficiency` (~6.4k/s, reflecting a 1/3 blast
radius on a 3-node deployment); under genuine single-hot-key skew, one shard caps at the knee regardless
of N. The single number is the lab ceiling, not a delivered floor. The target is falsifiable: it is met
if and only if the measured `per_shard_knee x N x efficiency` on real hardware meets it, and the way to
move it is to raise the per-shard knee (consensus-thread work) or raise N - both explicit.

**The 100k/s "burst" is a graceful-shed target, not a sustained-commit target.** No config control plane
sustains 100k/s of durable consensus commits; "burst" means the system absorbs a 100k/s offered spike
without collapse, committing at its aggregate capacity and shedding the excess as `429` plus
`Retry-After`. The admission-control measurement validated this at single-group scale (100k/s offered,
565/s committed, 680k `429`s, only 1,297 `503`s, 5 elections, no churn-collapse; admission-control
threshold 16). The multi-Raft burst target: offered 100k/s is shed gracefully (bounded queues, documented
shed order, no leadership churn-collapse) while committing at the sustained aggregate - to be re-measured
at N shards.

## Rationale

- **Honesty over aspiration.** The old 10k/s figure was modeled, attached to a topology that measurement
  showed cannot reach it. A derived aggregate with an explicit per-shard knee and efficiency factor can
  be validated or falsified on hardware, which is what stops the design from chasing a number nobody
  validated.
- **Keeps the original target intact in spirit** (the ADR-0031 precedent: grow the design to meet the
  promise, don't silently lower the promise). 10k/s sustained remains the system target; it is now
  honestly derived and the mechanism to reach it (N parallel tuned shards) is named.
- **Separates the two regimes the old number conflated:** sustained durable commit (bounded by
  per-shard consensus x N) vs. burst absorption (bounded by admission-control shed behavior). They have
  different mechanisms and different success criteria.

## Prior-Art Mechanism Borrowed

- **etcd single-group-with-batching ~10k/s** as the per-shard envelope ceiling (and etcd's stance that
  multi-raft is an application-layer concern).
- **CockroachDB / TiKV** "throughput scales with the number of (well-scheduled) groups" - the aggregate
  is N x per-group, with liveness cost held flat by coalesced heartbeats.

## Rejected Alternatives

- **Keep a flat 10k/s single-number target with no derivation.** Rejected: unfalsifiable against the
  measured single-group reality; it is the exact "number nobody validated" this decision exists to
  retire.
- **Lower the target to the measured ~800/s.** Rejected: defeats the purpose of adopting multi-Raft and
  silently downgrades the original target (the failure mode ADR-0031 exists to prevent).
- **Claim 100k/s sustained.** Rejected: no evidence any single-region consensus control plane sustains
  100k/s durable commits; conflates burst-absorption with sustained-commit.

## Consequences

- Success is measurable: `per_shard_knee x N x efficiency >= 10k/s` proven on dedicated multi-box
  hardware, with HdrHistogram p50/p99/p999 and the disk/CPU evidence rails established.
- A dedicated-host single-group re-measurement is owed before any particular N is treated as final (the
  co-location confound above). N stays a deploy-time constant so a higher knee can lower N without a
  reshard.
- Admission control is the graceful-degradation backstop for burst load; it is not a resolution of the
  sustained-throughput question, which is evaluated separately.
- Any comparison that cites write throughput must use the derived aggregate with its hardware proof, not
  the old modeled 10k/s figure.

## Known limitations

- **`efficiency_factor` looks like a fudge factor - you could make any N hit any target by picking
  0.75.** Bounded by measurement: the factor is a pre-measurement estimate, and the target is only met
  when the measured aggregate on hardware meets it. Its honesty is in stating it is less than 1 and
  naming its causes (heartbeat overhead, key imbalance, leader skew across a small node count, fan-out)
  so a measurement can attribute any shortfall to a specific cause.
- **~800/s is co-location-confounded - the whole derivation rests on a soft number.** Acknowledged: that
  is exactly why the per-shard knee is flagged for a dedicated-host re-measurement before N is treated as
  final, and why N is a deploy-time constant rather than a hardcoded value.
- **N x per-shard assumes uniform load; a hot shard makes the aggregate a fiction.** Mitigated by hash
  partitioning spreading skew (see [adr-multiraft-partitioning](adr-multiraft-partitioning.md)),
  per-principal rate limits, and N-headroom; the genuine single-hot-key case is unsplittable by any
  sharding scheme and is a per-key remediation, not a partitioning defect.
- **Every term in the formula was originally a forward reference.** Partly resolved since: the
  consensus-thread fix (owner-executor pool plus coalesced heartbeats) is now built. The embedded
  assumption that the fix would raise the single-group knee turned out to be false - it does not, because
  the fix parallelizes across groups, not within one, so a lone group is still one owner thread. So
  `per_shard_knee` is a measured ~800/s (still co-location-confounded, pending a dedicated-host
  re-measure), and the path to 10k/s is N parallel shards, not a higher single-group rate. The honest
  statement for the aggregate: 10k/s sustained has not been validated end to end against any built
  multi-shard system; the per-shard term is measured at ~800/s and N~17 follows from the formula.
- **The aggregate assumes uniform load and all shards live.** Carried into the headline as a range: node
  loss drops it to `(2/3) x N x knee x efficiency`; single-hot-key skew caps one shard at the knee
  regardless of the others.
- **Is 10k/s even the right target?** Still open. The renegotiation preserves the legacy figure as an
  aggregate (following the ADR-0031 precedent of growing the design to meet a promise) but does not
  assert that the workload actually needs sustained 10k/s - real config load looks like rollout bursts,
  which the graceful-shed target already covers. Whether 10k/s sustained is a genuine requirement or a
  continuity anchor from the original target is a question for whoever owns the SLA.

## Verification Extension

Reuse the established rails (CO-corrected open-loop driver, HdrHistogram, per-phase
iostat/mpstat/pidstat, `elections_total` per rate) at N shards on dedicated hosts: a rate ladder per
shard count proving the per-shard knee and the aggregate; a 100k/s burst proving graceful shed at N; an
amplification guard proving heartbeat-emit latency stays under the election floor as N scales.

## Related

ADR-0031 (precedent for renegotiating a throughput/availability target via ADR), ADR-0023 (the original
modeled, unmeasured 10k/s claim), [adr-multiraft-topology](adr-multiraft-topology.md) (shard-count
derivation).
