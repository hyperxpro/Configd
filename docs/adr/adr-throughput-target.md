# ADR (multi-Raft): Correct the section 0.1 Write-Throughput Target to a Derived, Falsifiable Aggregate (Per-Shard Rate x Shards x Efficiency)

## Status

**Proposed** (2026-06-21). Not yet Accepted - awaits operator sign-off.

> **Workstream C update (2026-06-26, MEASURED).** The per-shard knee - flagged below as "the single most
> important number still owed to measurement" and as a "forward reference" - has now been **measured on
> hardware** (m6id.4xlarge).
> Result: the **re-threaded** single group (Phase 0 owner-executor pool + coalesced heartbeats + Netty-Epoll
> consensus wire) caps at **~800/s - unchanged from the pre-Phase-0 baseline.** Phase 0 did not raise the
> *single-group* knee and structurally could not: at N=1 a group binds to one owner thread; Phase 0's
> parallelism is *across* groups and its heartbeat coalescing is a flat-in-N *aggregate* property. **The only
> lever that lifts sustained throughput above ~800/s is sharding (multi-Raft Phase 1).** So `per_shard_knee` is
> now a measured **~800/s** (co-location-confounded; dedicated-host re-measure still owed before freezing N),
> and `N ~ 10000/(800x0.75) ~ 17` to reach 10k/s sustained. Admission control (`=16`) was re-validated on the
> re-threaded code (churn-collapse -> graceful `429`-shed, ~2x effective throughput under flood) - it stabilizes
> the ~800/s knee under burst, it does not raise it. The v1-vs-v2 recommendation (ship the fast single group +
> admission for v1, defer sharding to v2 unless 10k/s *sustained* is a hard contract) is in the verdict doc.
Renegotiates a section 0.1 target, which section 0.1 requires be done via an ADR (precedent: ADR-0031, which
renegotiated the write-*availability* target). Part of the multi-Raft arc; depends on
`adr-multiraft-topology.md` (N derivation).

## Context

section 0.1 (`PROMPT.md:18`) sets **Global write QPS: 10k/s baseline, 100k/s burst.** This number was
**mis-specified against a single-group design and never validated**:

- **ADR-0023** asserted a *"measured ~10 k commits/s"* single-Raft ceiling - but **RR-047** records
  that this was **modeled, never measured** (`perf/results/` was a placeholder; the figure has no
  artifact). The S5 a-priori hypothesis ("mechanism does 815k/s in-memory; only host capacity limits
  10k/s") was likewise unvalidated.
- **The first real-hardware measurement** (m6id.4xlarge, 16 vCPU, instance-store NVMe, 3
  co-located nodes; RR-113) - found the as-built single group sustains
  **~800 writes/s stably** and collapses into leadership churn at ~1000-1200/s. The ceiling is the
  single-threaded consensus path (heartbeat starvation), **not** fsync (`iostat f/s`=0), **not** CPU
  (86% idle), **not** disk. **10k/s on a single group is unreachable with the current model** - tracked
  **P0 RR-113**.

So the 10k/s figure is a number nobody validated, attached to a topology that cannot reach it. With
multi-Raft adopted, the target must be re-expressed as what it actually is: an **aggregate across
shards**, derived from a measured per-shard rate, with an honest efficiency factor - and proven on
real hardware, not loopback.

## The per-shard envelope (the evidence that bounds a single tuned group)

| Bound | Value | Source |
|---|---|---|
| As-built single group, co-located NVMe | **~800/s stable** (collapse ~1000/s) | measured on hardware, co-location-confounded |
| **Re-threaded single group (Phase 0), co-located NVMe** | **~800/s stable** (collapse ~1000/s) - **UNCHANGED** | **Measured 2026-06-26**; Phase 0 lifts the *aggregate* via sharding, not the single-group knee |
| Single group + group commit | flat (no change here) | fsync is free on instance-store NVMe (`f/s`=0); group commit is load-bearing on EBS/SAN/HDD |
| Single group + admission control (mitigation) | ~864/s under a 2000/s flood (was 432/s), leader stable | measured - *failure-mode* fix, not a ceiling raise |
| **etcd, single group + batching** | **~10k/s** | public etcd evidence - the realistic *tuned single-group envelope ceiling* on dedicated hardware |

**Reading:** the measured Configd as-built knee (~800/s) is the conservative **floor** of the
per-shard envelope; etcd's ~10k/s batched single-group is the **tuned ceiling** of the envelope. The
The root cause (single-thread heartbeat starvation) is an *implementation* ceiling, not an
architectural one - so with the consensus-thread fix (coalesced heartbeats + sharded tick-executor
pool + per-tick broadcast-coalescing) plus group commit/batching, the
per-shard knee is expected to rise materially above ~800/s on a dedicated host. **Where in
[800/s, ~10k/s] it lands is the single most important number still owed to measurement** (dedicated,
one-node-per-host, multi-box).

> **MEASURED (2026-06-26):** the expectation above was partly wrong, and the correction is the
> key finding. The consensus-thread fix (coalesced heartbeats + owner-executor pool) did **NOT** raise the
> *single-group* knee - it is still ~800/s, byte-for-byte the baseline curve - because at N=1 a single group is a
> single owner thread; the fix's parallelism and flat-in-N heartbeat coalescing are *aggregate* (multi-group)
> properties. So on *this co-located box* the per-shard knee landed at **~800/s, not higher**. The remaining
> upside toward etcd's ~10k/s is (a) sharding (N groups, the multi-Raft thesis) and (b) a dedicated-host
> re-measure of the single-group knee (still owed; co-location plausibly suppresses it). Group commit is a
> no-op here (fsync free on instance-store NVMe, re-confirmed: 16,986 fdatasync IOPS).

## Decision

**Re-express the section 0.1 write-throughput target as a derived, falsifiable aggregate:**

```
sustained_aggregate_commit_rate  =  per_shard_knee  x  N_shards  x  efficiency_factor
```

- **`per_shard_knee`** - the measured stable single-group commit rate on the *deployment* hardware,
  after the consensus-thread fix + group commit. Floor today: **~800/s** (co-located). To be
  re-measured on a dedicated host before N is frozen.
- **`N_shards`** - the static shard count (`adr-multiraft-topology.md`): **N = 16** recommended.
- **`efficiency_factor`** - **~0.7-0.8**, accounting for coalesced-heartbeat/tick overhead, imperfect
  key balance, leader skew across only 3 nodes, and cross-shard fan-out. Not 1.0; stated honestly.

**The corrected section 0.1 sustained target stays 10k/s, but as a DERIVED aggregate, not a single-group
promise - but every term is currently a FORWARD REFERENCE (Red-Team):** the knee (~800/s) is
co-location-confounded, and the threading fix meant to *raise* it and the coalesced-HB overhead meant to
*lower* efficiency are **both unbuilt** (`RaftNode.propose():460` broadcasts per-proposal; no coalesced
HB). So `800 x 16 x 0.75 ~ 9.6k/s` is **not "~ 10k/s validated"** - it is the *balanced, all-shards-live
lab ceiling* of a measurement plan. Carry the realistic discounts into the headline as a **range**:
balanced/all-live ~ `Nxkneexeff` (~9.6k/s at the as-built knee); under a node outage ~ `(2/3)Nxkneexeff`
(~6.4k/s - D-B's 1/3 blast radius); under genuine single-hot-key skew, one shard caps at the knee. The
single number is the lab ceiling, not the delivered floor. The target is **falsifiable**: it is
met iff the measured `per_shard_knee x N x efficiency` on real hardware meets it, and the path to move
it is "raise the per-shard knee (consensus-thread work) or raise N," both explicit.

**The 100k/s "burst" is re-expressed as a graceful-shed target, not a sustained-commit target.** No
config control plane sustains 100k/s of durable consensus commits; "burst" means the system *absorbs*
a 100k/s offered spike without collapse, committing at its aggregate capacity and shedding the excess
as `429 + Retry-After`. The admission-control measurement validated this at the single-group scale (100k/s offered -> 565/s
committed, 680kx `429`, only 1,297x `503`, 5 elections - no churn-collapse; admission control =16). The
multi-Raft burst target is: **offered 100k/s is shed gracefully (bounded queues, documented shed
order, no leadership churn-collapse) while committing at the sustained aggregate** - to be re-measured
at N shards.

## Rationale

- **Honesty over aspiration.** The old 10k/s was modeled, attached to a topology that measurement
  showed cannot reach it. A derived aggregate with an explicit per-shard knee and efficiency factor
  *can* be validated or falsified on hardware - which is what stops the build arc from chasing a number
  nobody validated (the section 6 charter directive).
- **Keeps the section 0.1 contract intact in spirit** (ADR-0031 precedent: grow the design to meet the
  promise, don't silently lower the promise). 10k/s sustained remains the system target; it is now
  honestly derived and the mechanism to reach it (N parallel tuned shards) is named.
- **Separates the two regimes the old number conflated:** *sustained durable commit* (bounded by
  per-shard consensus x N) vs *burst absorption* (bounded by admission-control shed behavior). They
  have different mechanisms and different success criteria.

## Prior-Art Mechanism Borrowed

- **etcd single-group-with-batching ~10k/s** as the per-shard envelope ceiling (and etcd's stance that
  multi-raft is an application-layer concern).
- **CockroachDB / TiKV** "throughput scales with the number of (well-scheduled) groups" - the aggregate
  = N x per-group, with liveness cost held flat by coalesced heartbeats.

## Rejected Alternatives

- **Keep a flat 10k/s single-number target with no derivation.** Rejected: unfalsifiable against the
  measured single-group reality; it is the exact "number nobody validated" the session exists to retire.
- **Lower the target to the measured ~800/s.** Rejected: defeats the purpose of adopting multi-Raft and
  silently downgrades the section 0.1 contract (the failure mode ADR-0031 exists to prevent).
- **Claim 100k/s sustained.** Rejected: no evidence any single-region consensus control plane sustains
  100k/s durable commits; conflates burst-absorption with sustained-commit.

## Consequences

- The build arc's success metric becomes **measurable**: prove `per_shard_knee x N x efficiency >=
  10k/s` on dedicated multi-box hardware, with HdrHistogram p50/p99/p999 and the disk/CPU evidence
  rails established.
- **A dedicated-host single-group re-measurement is owed** before N is frozen (co-location confound,
  RR-113). N stays a deploy-time constant so a higher knee can lower N
  without a reshard.
- RR-113 (P0) is the tracking row; this ADR defines what "resolved" means for it (the aggregate target
  proven on hardware), and that admission control is the graceful-degradation backstop, not the
  resolution.
- section 0.3 "surpass-Quicksilver" scorecards that cite write throughput must use the derived aggregate with
  its hardware proof, not the modeled 10k/s.

## Red-Team Critique (surviving)

- **"`efficiency_factor` is a fudge factor - you can make any N hit any target by picking 0.75."**
  *Surviving; bounded by measurement.* The factor is a *pre-measurement estimate*; the target is only
  *met* when the measured aggregate on hardware meets it. The factor's honesty is in stating it is <1
  and naming its causes (heartbeat overhead, key imbalance, 3-node leader skew, fan-out) so the
  measurement can attribute any shortfall.
- **"~800/s is co-location-confounded - the whole derivation rests on a soft number."** *Surviving and
  acknowledged:* that is exactly why the per-shard knee is flagged for dedicated-host re-measurement
  before N is frozen, and why N is a deploy-time constant, not a hardcode.
- **"N x per-shard assumes uniform load; a hot shard means the aggregate is a fiction."** *Cross-links
  to D-B's surviving critique* - mitigated by D-A hash spreading skew + per-principal limits +
  N-headroom; the genuine single-hot-key case is unsplittable by any sharding and is a per-key
  remediation.
- **Every term in the formula is a forward reference (verified).** *Partly resolved (Workstream C,
  2026-06-26).* The threading fix is now **built and measured**, and the embedded assumption "threading fix
  raises [the single-group] knee" is **falsified**: it does not - the re-threaded single-group knee is still
  ~800/s, because Phase 0 parallelizes *across* groups, not *within* one. So `per_shard_knee` is now a
  **measured ~800/s** (no longer a forward reference, though still co-location-confounded pending a
  dedicated-host re-measure), and the path to 10k/s is **N parallel shards**, not a higher single-group rate.
  The honest statement stands for the *aggregate*: **"10k/s is still unvalidated against any built multi-shard
  system; the per-shard term is now measured at ~800/s and N~17 follows."**
- **The aggregate assumes uniform load AND all-shards-live** - now carried into the headline as a range
  (node loss -> `(2/3)N`; single-hot-key skew caps one shard at the knee).
- **Is 10k/s even the right target?** *Surviving - flagged for the operator.* The renegotiation preserves
  the legacy figure as an aggregate (ADR-0031 "grow the design" precedent) but does not assert the
  *workload* needs sustained 10k/s - real config load is rollout bursts (already re-expressed as
  graceful-shed). The operator should confirm 10k/s sustained is a genuine requirement vs a continuity
  anchor (carried to `recommendation-summary.md`).

## Verification Extension

Reuse the established rails (CO-corrected open-loop driver, HdrHistogram, per-phase iostat/mpstat/pidstat,
`elections_total` per rate) at N shards on dedicated hosts: a rate ladder per shard count proving the
per-shard knee and the aggregate; a 100k/s burst proving graceful shed at N; an amplification guard
proving heartbeat-emit latency stays under the election floor as N scales.

## Related

ADR-0031 (precedent: renegotiate a section 0.1 target via ADR), ADR-0023 / RR-047 (the modeled, unmeasured
10k/s), RR-113 (P0 write-throughput SLO unmet), `adr-multiraft-topology.md` (N).
