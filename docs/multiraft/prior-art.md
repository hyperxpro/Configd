# Multi-Raft Prior Art — Mechanism Teardown for Configd

> **Session M1 deliverable (Phase 1). Read-only research. No production code.** Date: 2026-06-21.
> Produced by the `prior-art-analyst` research agent, synthesized and fact-checked by the
> research lead against the Configd repo. This is the **evidentiary base** the three multi-Raft
> ADRs (`adr-multiraft-partitioning.md`, `adr-multiraft-topology.md`, `adr-multiraft-cross-shard.md`)
> and `adr-throughput-target.md` cite. Every recommendation in those ADRs names a specific
> mechanism below.
>
> External citations are public primary sources gathered for this session (URLs at the end of each
> section / in the citation list). In-repo claims carry `file:line` and were independently
> re-verified by the lead.

---

## Intro — the five systems and the axis each illuminates

| System | Sharding | Axis it illuminates for Configd |
|---|---|---|
| **TiKV** (Regions) | Range | The canonical embedded multi-Raft engine on the *same etcd Raft package Configd's engine descends from*; **hibernated regions + store-level raftstore** are the direct answer to Configd's single-thread heartbeat ceiling — **and** the cautionary tale (hibernation's failover-delay bug, #34906). |
| **CockroachDB** (Ranges) | Range | **Coalesced heartbeats + quiescence + the "3 goroutines, not one-per-range" MultiRaft scheduler + Leader leases** — the precise decoupling of *ticking* from *per-group work* that Configd's single-thread `MultiRaftDriver` needs. Also the richest cross-shard-txn and follower-read mechanism set. |
| **Google Spanner** | Range (directory fragments) | Cross-shard correctness via **2PC-over-Paxos + TrueTime commit-wait**, and `movedir` as a non-transactional background rebalance — the "what cross-shard consistency actually costs" reference. Configd rejects the cost. |
| **etcd / raft** | Single group | Configd's actual engine lineage and baseline. etcd **deliberately ships only single-group Raft** and pushes multi-raft to the application layer — validating that Configd must *build* the multi-raft layer (as TiKV/CRDB did), not expect it from the library. |
| **Cloudflare Quicksilver** | (read/fan-out tier only) | The read-tier baseline Configd is explicitly modeled on (ADR-0030): async fan-out, eventually-consistent local edge reads, **zero edge consensus** — confirms the data plane is *out of scope* for the multi-Raft decision; sharding is a control-plane-only concern. |

The single most important extraction is the **heartbeat-amplification mitigation** (item 5 per system),
because Configd's measured ceiling *is* heartbeat starvation on one thread (S7.5: above ~800/s the
per-proposal work delays the 50 ms heartbeat past the 150–300 ms election timeout → churn;
`docs/session-7.5/throughput-part2.md` §C/§D), and `MultiRaftDriver.tick()` ticks **all** groups on
**one** thread (`MultiRaftDriver.java:100`; `ConfigdServer.java:367` `newSingleThreadScheduledExecutor`).
Naive N-group multi-Raft amplifies exactly this. TiKV and CockroachDB each solved it; their
mechanisms — and their bugs — are below.

---

## 1. TiKV — multi-Raft "Regions"

**1. Sharding scheme & why — Range.** Verbatim rationale: *"Hash and Range are commonly used for
data sharding. TiKV uses Range and the main reason is that Range can better aggregate keys with the
same prefix, which is convenient for operations like scan."* Range also *"outperforms in split/merge
than Hash. Usually, it only involves metadata modification and there is no need to move data
around."* Acknowledged tradeoff: *"a Region may probably become a performance hotspot due to frequent
operations."* Each Region = an independent Raft group over a contiguous key range.

**2. Write routing / placement (PD).** Metadata lives in **PD (Placement Driver)**, not TiKV. Clients
cache a **region cache** (`start_key/end_key → Region + leader`) and consult PD only on a miss or a
region error. Leader routing is **error-driven**: a request to a non-leader returns `NotLeader`
carrying the new leader; the client updates its cache. Staleness is detected via
**`RegionEpoch{ConfVer, Version}`** — `ConfVer` bumps on peer add/remove, `Version` bumps on
**split/merge**; the epoch *"can uniquely identify the Region of the current request."*

**3. Static vs dynamic — split/merge.** Fully dynamic. Split: a Region over a size/load threshold
sends `ask_split` to PD → PD allocates new IDs → the split is **proposed through the Region's own Raft
log** so all replicas split consistently, bumping `Version` → `report_split`. TiKV warns *"the Split
process is much more complicated and the situation of sending snapshot may occur."* **Merge** is the
harder direction (acknowledged as substantially more complex than split).

**4. Cross-shard consistency — Percolator.** 2PC over single-row-atomic storage, with a **timestamp
oracle** (strictly monotonic, contacted **twice per txn** — `start_ts`/`commit_ts` — a recognized
scalability/availability chokepoint) and a **primary lock** as the per-txn synchronizing cell.
Phases: **Prewrite** (lock + write at `start_ts`) then **Commit**. Cost: two oracle round-trips +
2PC + lock cleanup per cross-region txn.

**5. Per-shard consensus overhead — THE mitigation.** TiKV runs **one raftstore**, not one thread per
Region: *"Raftstore does not send tick messages to the Raft state machines of idle Regions if not
necessary, so these Raft state machines will not be triggered to generate heartbeat messages, which
can greatly reduce the workload of Raftstore."* This is **Hibernate Region** (default-on): an idle
Region is hibernated and wakes every ~5 min (`raftstore.peer-stale-state-check-interval`) or on
activity. *"Hibernate region is crucial to reduce raftstore heartbeat CPU usage, especially for
clusters with a high number of regions."* **Heartbeat/tick cost is bounded by *active* Region count,
not total Region count.**

**6. Known PRODUCTION bugs (under fault).**
- **Hibernation defeats failover — TiKV #34906 / tikv#11230 (the Configd-relevant one):** *"regions
  whose leaders are on an unhealthy store will not be re-elected until a request is sent to the
  follower… every time a new region is accessed, it will send a request to the original leader on the
  unhealthy TiKV… users will experience very long request duration until all regions are touched and
  elect new leaders"* — failover observed at **20+ minutes**.
- **Hibernation + health — TiKV #10017:** down-peer collection ignored hibernated regions; a leader
  could *"wrongly report normal peers as down peers,"* corrupting PD scheduling.
- **Split/merge under fault:** load-based split collected split keys **raw, not memcomparable-encoded**
  (#10542); panics applying a **tombstone region that already existed** (#12368); *"meta corrupted: no
  region for …"* panic under Jepsen nemesis (#13311); write-hotspot split where *"Region size growing
  speed may exceed splitting speed"* (#9785) → oversized un-splittable Region.
- *TiDB-layer* Jepsen (2.1.7/3.0-beta) found no snapshot isolation by default — a transaction-layer,
  not Raft-layer, finding, but relevant to the cross-shard cost story.

**7. Read tier / follower reads.** Follower read via ReadIndex against the leader — bounded by one
intra-group RTT, **not free**. (Configd edges are *not* Raft followers — see verdict.)

> **Configd STEALS / REJECTS:** **STEAL** the *store-level raftstore model* — one I/O thread (or small
> pool) servicing all groups, ticking only **active** groups — which is exactly what `MultiRaftDriver`
> is shaped for ("one I/O thread per node, not per Raft group"). This is the direct antidote to the
> single-thread ceiling. **REJECT Hibernate Region as a v1 default**: #34906 shows hibernation trades
> CPU for *catastrophic failover latency* (20-min leaderless windows) — Configd's 99.999% write target
> and existing churn sensitivity cannot absorb a "leader on a dead node isn't re-elected until touched"
> failure mode. If Configd ever hibernates idle groups it MUST pair it with proactive health-driven
> wake (which TiKV bolted on after the fact). **REJECT Percolator** — §0.2 fences out multi-key
> transactions. **STEAL `RegionEpoch{ConfVer,Version}` + error-driven client-cache update** as the
> cheap routing-correctness pattern (no transaction machinery). **Range sharding itself: REJECT as the
> default** — Configd's reads are point lookups, which removes the scan-locality argument that justifies
> Range for TiKV (see Cross-cutting L2).

---

## 2. CockroachDB — Ranges + distribution layer

**1. Sharding scheme & why — Range**, because CockroachDB serves **ordered SQL scans**, so contiguous
ranges keep a scan on few ranges. Default range ~512 MiB; split/merge dynamic.

**2. Write routing.** No central PD — **range descriptors** live in a two-level **meta index range**
(`meta1`→`meta2`→descriptors), gossiped and cached per node. A write resolves key → descriptor →
**leaseholder**; a stale cache yields `NotLeaseHolderError` carrying the new leaseholder (same
error-driven correction as TiKV, but decentralized via gossip).

**3. Static vs dynamic — split/merge/rebalance.** Fully dynamic: size- and **load-based** splits
(`kv.range_split.by_load`), merges of small adjacent ranges, allocator-driven replica rebalancing,
all proposed through the range's own Raft. **Merge is the dangerous direction** (a replica acquiring a
lease must check for a deletion intent indicating an in-progress subsumption).

**4. Cross-shard consistency — full serializable cross-range ACID** via **write intents** (provisional
MVCC values), a **transaction record**, **parallel commits** (committed once all intents + the record
are durable, no separate serial commit round), a **timestamp cache**, and HLC + closed timestamps for
cross-range order. Far heavier than Configd needs.

**5. Per-shard consensus overhead — THE mitigation (richest source for Configd).** Three layered
mechanisms:
- **MultiRaft scheduler — the core fix:** *"instead of allowing each range to run Raft independently,
  we manage an entire node's worth of ranges as a group… MultiRaft only needs a small, constant number
  of goroutines (currently 3) instead of one goroutine per range."* **The answer to "N groups on one
  thread starves heartbeats" is not "one thread per group" — it is a small fixed worker pool that
  schedules ready-to-work groups**, bounding consensus + ticking by node resources, not group count.
- **Coalesced heartbeats:** *"each pair of nodes only needs to exchange heartbeats once per tick, no
  matter how many ranges they have in common"* — heartbeat volume scales with **node count, not range
  count.**
- **Quiescence (#357):** a leader quiesces (stops ticking) when X ticks have elapsed with no proposal,
  no read leases needed, and all followers have acked the latest entry; wakes on a new command. The
  stakes are measured: **1M mostly-empty ranges → ~50% CPU on Raft ticking alone** (#17609), and
  **#66686: *"a raft tick may not be scheduled for over 200ms, which can delay heartbeats and cause
  leadership instability."* — this is Configd's exact bug at scale.**
- **Leader leases (modern CRDB):** liveness detected **once per store**, not per range — same
  amortization principle as coalesced heartbeats.

**6. Known PRODUCTION bugs (under fault).**
- **Range-split consistency (Jepsen 2.0–2.1, fixed 2.1.1):** a read returned a value all *failed*
  writes had tried to set, **with the split nemesis active** — *"when a transaction failed to commit on
  its first proposal, it would still signal waiting transactions as if it had succeeded."*
- **Timestamp-cache bug (Jepsen, fixed beta-20160915):** inconsistencies *"whenever two transactions
  are assigned the same timestamp"* — HLC can hand out identical timestamps under clock jumps.
- **Closed-timestamp-during-merge follower-read bug (20.2, #43541/#65823):** *"the closed timestamp of
  a replica could be advanced past the subsumption time of a range, allowing… follower reads past its
  subsumption time."* The textbook "split/merge during a lease/election event corrupts a read
  invariant."
- **Over-aggressive quiescing (#9432) / quiescing with dead nodes (#9446):** quiescing a group with a
  dead peer delays recovery — the **same hazard class as TiKV #34906.**

**7. Read tier / follower reads.** Closed timestamps + follower reads: a closed timestamp is *"a
promise from the leaseholder… that it will not accept further writes below the respective timestamp,"*
propagated piggybacked on Raft commands **and** via a **side-transport every 200 ms** for idle ranges;
default target staleness **~3 s** (`kv.closed_timestamp.target_duration`). Non-voting replicas serve
follower reads for locality without joining the write quorum.

> **Configd STEALS / REJECTS:** **STEAL, as the centerpiece of the design: the MultiRaft scheduler
> model** — a small fixed worker pool that ticks/services only *ready* groups, replacing "tick()
> iterates ALL groups on ONE thread." **STEAL coalesced/store-level heartbeats** — emit liveness *per
> peer-node per tick*, not per group, so adding groups does not add heartbeat traffic. Together these
> are what make N-group multi-Raft safe on Configd's thread budget. **STEAL the closed-timestamp
> *concept* only as already-decided in ADR-0030** (safe-timestamp follower serving) but **REJECT in-Raft
> delivery** — Configd's edge reads are sub-1ms HAMT point lookups, which a ~3 s-stale follower read or
> a ReadIndex RTT cannot match. **REJECT quiescence's aggressive form** unless paired with
> dead-peer-aware wake. **REJECT cross-range serializable txns / parallel commits** — §0.2 fences
> multi-key transactions.

---

## 3. Google Spanner — splits + TrueTime + Paxos groups

**1. Sharding — Range, at directory granularity.** *"A directory is the unit of data placement…
Spanner will shard a directory into multiple fragments if it grows too large. Fragments may be served
from different Paxos groups."*

**2. Write routing.** Each spanserver runs a Paxos state machine per tablet; a placement/directory
layer maps directory→group; clients route to the group leader owning the fragment. Cross-group data
movement = `movedir`.

**3. Static vs dynamic — `movedir`.** *"Movedir is not implemented as a single transaction, so as to
avoid blocking ongoing reads and writes on a bulky data move. Instead, movedir registers a fact that
it is starting to move data and moves the data in the background. When it has moved all but a nominal
amount of the data, it uses a transaction to atomically move that nominal amount and update the
metadata for the two Paxos groups."* The cleanest published **background-move-then-tiny-atomic-cutover**
rebalance pattern.

**4. Cross-shard consistency.** Externally-consistent (linearizable) cross-group transactions via
**2PC over Paxos** + **TrueTime commit-wait** (*"the coordinator leader waits until TT.after gets
true"* before committing). Cost: 2PC across groups **plus** a commit-wait stall of ~2× clock
uncertainty (~7 ms per OSDI 2012) on top of cross-region Paxos — the highest-correctness,
highest-latency point in this survey. (ADR-0030 already cites this ~7 ms in pricing out WAN write
consensus.)

**5. Per-shard overhead.** One Paxos group per tablet/fragment with **long-lived leader leases**
(~10 s); pipelined/batched Paxos. No TiKV-style hibernation (a different regime: fewer, larger, busier
groups) — so **not** the model for Configd's "many idle groups" problem; TiKV/CRDB are.

**6. Known bugs.** Internal to Google; no public tracker or Jepsen report. Treat Spanner as a **design
reference**, not a bug corpus.

**7. Read tier.** Read-only transactions / snapshot reads at a chosen timestamp; any sufficiently
up-to-date replica serves lock-free — the ancestor of CockroachDB closed-timestamp follower reads.

> **Configd STEALS / REJECTS:** **STEAL the `movedir` pattern** *if and when* Configd ever needs
> dynamic rebalancing/splitting (background bulk move + tiny atomic Raft-committed cutover) — the
> lowest-disruption repartition, avoiding stop-the-world; this is the recommended v2 dynamic seam.
> **REJECT TrueTime + commit-wait + 2PC-over-Paxos wholesale** (external cross-group consistency that
> §0.2 and ADR-0030 already decline; commit-wait is pure latency for single-key-linearizable config).
> **REJECT TrueTime as infra** (GPS/atomic-clock hardware Configd will not have).

---

## 4. etcd / raft — single-group baseline + multi-raft stance

**1. Sharding — none, single Raft group, by design.** *"Several open source Raft implementations,
including etcd, are just implementations of a single Raft group, which cannot be used to store a large
amount of data, so the major use case for these implementations is configuration management."* —
**exactly Configd's use case.**

**2. Write routing.** One group, one leader; trivial. No shard map.

**3. Static vs dynamic.** N/A — no split/merge. Scaling is replica-count only, and etcd's FAQ warns
adding voters **hurts** write throughput (the constraint ADR-0030 cites).

**4. Cross-shard consistency.** N/A — single group ⇒ one total order, trivially linearizable over the
whole keyspace (which is *why* config systems like it). etcd's `Txn` (atomic If/Then/Else) is
**intra-group** multi-key — there is no cross-shard atomicity because there is no shard.

**5. The architectural lesson.** etcd **refuses to put multi-raft in the library:** *"The etcd team
decided to expose the raw raft node instead of putting multi-raft inside the minimum raft package. The
reason is that multi-raft is usually (proven by CockroachDB and other implementations) tightly coupled
with the application's storage and networking for better control and optimization."* *"Organizations
like CockroachDB and TiDB implement their own multi-raft layer on top of etcd's raw node API."*

**6. Known bugs.** Single-group correctness is mature; historical Jepsen issues centered on
linearizable-read/lease edge cases, not a multi-raft layer (it has none).

**7. Read tier.** Linearizable reads via ReadIndex; serializable (stale) local reads from any member.
No closed-timestamp machinery.

> **Configd STEALS / REJECTS:** **The load-bearing validation for the whole session.** etcd's stance
> means **Configd's `MultiRaftDriver` is the correct architectural location** — multi-raft *must* be
> built in Configd's own layer (coupled to its transport + fan-out), exactly as
> `MultiRaftDriver` / `ConfigWriteService.propose(scope, …)` already anticipate; expecting the embedded
> engine to provide it is a category error CockroachDB and TiKV both avoided. **STEAL the constraint**:
> keep each group's voter set small and region-local (ADR-0030 already does). **REJECT** treating
> "we run one etcd-style group today" as a ceiling (it can be partitioned into many) or as proof
> multi-raft is library-provided (it is not). **Configd's single-shard `BATCH` is the analog of etcd's
> intra-group `Txn`** — and like etcd, Configd offers no *cross*-shard atomicity (parity, not a gap).

---

## 5. Cloudflare Quicksilver — the READ / fan-out tier baseline

**1. Sharding.** Write/source-of-truth tier: a **single centralized Raft root** (built on the etcd
Raft package), *not* sharded — the model ADR-0030 adopts. Read tier (v2, 2025): a **multi-level cache
hierarchy** (per-server caches, DC-wide sharded caches, full-dataset replicas, reactive prefetching) —
sharding *cache slots* for capacity, not consensus.

**2–3. Routing / dynamics.** Read tier is replication-and-cache, not consensus-routed: committed
writes fan out asynchronously; every edge holds a local copy and serves locally. No shard map, no
split/merge on the read path.

**4. Cross-shard consistency.** Read tier is **eventually / sequentially consistent** (MVCC + sliding
window), not transactional. No cross-shard read transactions (none needed).

**5. Per-shard overhead — zero on the read tier; edges run no consensus.** *Consensus for the source
of truth, coordination (not consensus) for distribution.*

**6. Known bugs.** Engineering blogs, no public tracker; the v1→v2 writeups document *scaling pain*
(single-writer/replication bottleneck driving the v2 caching redesign), not correctness bugs.

**7. Read-tier numbers (the baseline).** **>3 billion keys/sec** globally; **90% of reads < 1 ms,
99.9% < 7 ms**; **~1.6 TB / ~5 billion KV pairs**; async replication (eventual consistency) with a
30-second disconnect/catch-up threshold.

> **Configd STEALS / REJECTS:** **STEAL (already ratified in ADR-0030):** the read tier is **out of
> scope for the multi-Raft decision** — edges never join consensus, so sharding the control plane
> changes nothing about edge reads (still HAMT point lookups, sub-1ms). This clean separation lets
> Configd shard the *root* freely. **STEAL** the v2 lesson that a single-writer source-of-truth
> eventually needs partitioning for write throughput — Quicksilver's own evolution validates
> partitioning the write tier (the premise of this session). **REJECT** importing the cache hierarchy
> as a consensus mechanism (a read-scaling device, orthogonal to control-plane sharding).

---

## Cross-cutting lessons for Configd

**The 3 mechanisms that most shape the decisions:**

**L1 — (shapes D-B + the throughput target; the decisive one) Solve heartbeat amplification with a
store-level scheduler + coalesced heartbeats — NOT one-thread-per-group, NOT naive N-on-one-thread.**
Configd's measured ceiling is heartbeat starvation on one tick thread; CockroachDB's *"small, constant
number of goroutines (currently 3) instead of one goroutine per range"* + *"each pair of nodes only
needs to exchange heartbeats once per tick, no matter how many ranges they have in common"* is the
proven fix, and CRDB #66686 (*"a raft tick may not be scheduled for over 200ms… can delay heartbeats
and cause leadership instability"*) plus the 1M-ranges/50%-CPU finding are *literally Configd's failure
mode at scale.* The N-group design is only safe if heartbeats coalesce per peer-node and a fixed
worker pool services ready groups — `MultiRaftDriver`'s "one I/O thread per node, not per group"
framing is already correct in spirit, but its `tick()`-iterates-all-on-one-thread *implementation*
reproduces the bottleneck and must be replaced with a ready-queue scheduler + coalesced peer
heartbeats.

**L2 — (shapes D-A: hash vs range) Configd's read model breaks TiKV's/CRDB's reason for Range.**
TiKV/CRDB chose Range *because their reads are ordered scans* (Range *"can better aggregate keys with
the same prefix, which is convenient for operations like scan"*). Configd's edge reads are **point
lookups (HAMT get)**; the only prefix-shaped access is **subscription filtering, a fan-out concern, not
a consensus-routing concern.** So the scan-locality argument for Range is *much weaker* here, while the
standard Range hazard — hotspots (*"a Region may probably become a performance hotspot"*) and
split/merge bugs — is mostly downside. **Hash partitioning by key-scope is the simpler,
hotspot-resistant default**, consistent with the OSS-simplicity bias and the existing
`propose(scope, cmd)` seam.

**L3 — (shapes D-B: static vs dynamic) Make partitioning STATIC first; dynamic split/merge is where
every system bleeds.** The richest production-bug vein in this entire survey is **split/merge under
fault**: CRDB's split-nemesis Jepsen inconsistency, the 20.2 closed-timestamp-past-subsumption
follower-read corruption, over-aggressive quiescing, TiKV's tombstone-region panics, raw-key split
keys, hotspot-outruns-split, and meta corruption under nemesis. A generic OSS config plane gains little
from online auto-split and inherits this entire bug class. **Recommend static scope→group mapping for
v1**; if dynamic rebalancing is ever needed, steal **Spanner's `movedir`** (background bulk move + tiny
atomic Raft-committed cutover), *not* TiKV/CRDB online split.

**The 2 mechanisms that bound the design:**

**L4 — (shapes D-C: cross-shard) Do NOT build cross-shard transactions.** Percolator (TiKV),
parallel-commits/write-intents (CRDB), and 2PC-over-Paxos+commit-wait (Spanner) all exist to order
*multiple keys across groups* — squarely inside §0.2's non-goals, each carrying real cost (oracle
round-trips, intent resolution, ~7 ms commit-wait) and real bugs (timestamp-cache anomaly,
closed-ts-during-merge). Configd's contract already says *"applications needing two keys ordered must
route both to the same Raft group."* **Keep that.** The only cross-group correctness work worth doing
is making the **scope→group mapping stable and epoch-versioned** — TiKV's `RegionEpoch{ConfVer,Version}`
+ error-driven client-cache update is the right, cheap routing-correctness pattern to borrow, *without*
any transaction machinery.

**L5 — (shapes the throughput target) The target is reachable by *parallelism across groups*, and the
engine choice is already validated.** etcd deliberately ships single-group only and pushes multi-raft
to the application layer (*"tightly coupled with the application's storage and networking"*);
CockroachDB and TiKV both built their own. Configd's `MultiRaftDriver` is the correct, expected place
to build it — this is not a fight against the engine. Combined with L1, the path past ~800/s is
*N groups × per-group throughput*, with liveness cost held flat by coalesced heartbeats and a
store-level scheduler — i.e. partition the write path so no single tick thread carries every group's
per-proposal work (the exact thing that collapsed at ~1000–1200/s in S7.5).

---

## Citations (public primary sources gathered this session)
- TiKV multi-raft & range rationale: pingcap.com/blog/design-and-implementation-of-multi-raft; tikv.org/deep-dive/scalability/multi-raft, /data-sharding
- TiKV hibernation & massive regions: docs.pingcap.com/tidb/stable/massive-regions-best-practices; tikv.org/blog/tune-with-massive-regions-in-tikv; tikv.org/docs/4.0/tasks/configure/raftstore
- TiKV PD / epoch / routing: github.com/tikv/pd/wiki/Metadata-Management
- TiKV bugs: github.com/pingcap/tidb/issues/34906; tikv/tikv #10017, #10542, #12368, #13311, #9785, #11230; jepsen.io/analyses/tidb-2.1.7
- TiKV Percolator / timestamp oracle: tikv.org/deep-dive/distributed-transaction/percolator, /timestamp-oracle
- CockroachDB scaling Raft / MultiRaft / coalesced heartbeats: cockroachlabs.com/blog/scaling-raft
- CockroachDB quiescence & tick scheduling: github.com/cockroachdb/cockroach issues #357, #17609, #66686, #9432, #9446; docs/RFCS/20160824_quiesce_ranges.md
- CockroachDB replication layer / Leader leases: cockroachlabs.com/docs/stable/architecture/replication-layer
- CockroachDB parallel commits: cockroachlabs.com/blog/parallel-commits; docs/RFCS/20180324_parallel_commit.md
- CockroachDB closed timestamps / follower reads: cockroachlabs.com/blog/follower-reads-stale-data
- CockroachDB Jepsen/split & timestamp-cache & merge bugs: cockroachlabs.com/blog/jepsen-tests-lessons; jepsen.io/analyses/cockroachdb-beta-20160829; cockroach issues #43541, #65823; docs/tech-notes/range-merges.md
- Spanner: usenix.org/system/files/conference/osdi12/osdi12-final-16.pdf; cloud.google.com/spanner/docs/whitepapers/life-of-reads-and-writes
- etcd multi-raft stance: github.com/etcd-io/etcd/issues/8562; pkg.go.dev/go.etcd.io/etcd/raft/v3; etcd.io/docs/v3.4/learning/why
- Quicksilver: blog.cloudflare.com/quicksilver-v2-evolution-of-a-globally-distributed-key-value-store-part-1; infoq.com/news/2025/08/cloudflare-key-value-store
- Slicer (D-A hash prior art): csaws.cs.technion.ac.il/~shralex/osdi16-final89.pdf (OSDI '16)
