# Configd-Specific Multi-Raft Analysis — Decisions Against Our Constraints

> **Session M1 deliverable (Phase 2). Read-only research. No production code.** Date: 2026-06-21.
> Synthesized by the research lead from the four parallel research strands (partitioning, topology,
> consistency, prior-art) and verified against the repo. Companion to `prior-art.md`; the input to
> the four ADRs in `docs/decisions/adr-multiraft-*.md` + `adr-throughput-target.md`.
>
> This document does the Configd-specific reasoning. Its centerpiece (§3) is the
> **heartbeat-amplification model** — the hard-rule risk that a naive multi-Raft could be *slower*
> than the single group we have today. Everything else is downstream of getting §3 right.

---

## 1. The premise, and why it is coherent with ADR-0030

The move to multi-Raft is settled (horizontal write throughput + blast-radius/recovery containment).
The job of this session is to decide *how*, against Configd's actual constraints, without breaking
what already works.

**Framing that keeps the arc coherent (load-bearing).** ADR-0030 ("Quicksilver-shaped topology")
chose **one centralized strongly-consistent Raft root** and **rejected global multi-region /
hierarchical-Raft write consensus**. This is *not* in tension with multi-Raft, and the distinction
must be stated precisely:

- ADR-0030 rejected **WAN-stretched / geo-distributed** write quorums (a 5-voter group spanning
  APAC↔US↔EU, or per-region write groups with cross-group ordering) — for latency (<150 ms p99 is
  physics-bound across regions) and availability reasons.
- ADR-0030 did **not** reject **co-located sharding for throughput**. It explicitly *"Reconciles with —
  and does not contradict — `adr-0023-multi-raft-sharding-deferred.md`,"* which defers sharding to v2,
  and its Reasoning #2 says *"the only correct use of one group is a small, region-local, centralized
  root."*

So the multi-Raft arc is correctly framed as **intra-region throughput sharding of the centralized
root**: N co-located Raft groups inside one low-RTT failure cluster (the TiKV model — many Regions in
one cluster), each preserving ADR-0030's single-region latency/availability properties, collectively
providing parallel write lanes. **We are not reviving the rejected geo-topology.** Each shard is still
a small, region-local group spanning AZs per ADR-0030 Amendment A2.

One real reconciliation cost lands on ADR-0030 Reasoning #4, which counted *"a PlacementDriver,
scope-aware shard routing"* as operational complexity it avoided. The resolution is in §4/§D-B below:
**static-N sharding needs only a deterministic shard-map (hash→group), not a dynamic PlacementDriver.**
The complexity ADR-0030 rejected was *dynamic* placement + the geo-scope REGIONAL tier of the rejected
adr-0015 — not a static co-located shard-map. Dynamic resharding (v2) *would* re-import that
complexity, which is one more reason to defer it.

## 2. The design is already ~80% sharding-ready — by deliberate prior choice

Multi-Raft is the activation of latent seams, not a greenfield bolt-on. Verified in the repo:

| Seam | Where | State today |
|---|---|---|
| **Group map** | `MultiRaftDriver` (`addGroup`/`removeGroup`/`routeMessage(int groupId,…)`/`propose(int groupId,…)`/`tick`) | Exists; one group registered (`ConfigdServer.java:348` `addGroup(DEFAULT_RAFT_GROUP=0,…)`). Explicitly the "CockroachDB store pattern — one I/O thread per node, not per Raft group." |
| **Write routing seam** | `ConfigWriteService.RaftProposer.propose(ConfigScope scope, byte[] cmd)` (`ConfigWriteService.java:105`) — "Determine the Raft group by key scope" | Exists; all scopes collapse to group 0. |
| **Per-group sequence** | ADR-0004 / ADR-0019 / `consistency-contract.md §4` | Already **per-group** monotonic 64-bit counter — chosen *specifically* "to enable parallel writes across groups" (ADR-0019: a global counter "would reintroduce the single-writer bottleneck"). |
| **Per-group edge cursor** | ADR-0035 / `consistency-contract.md §3/§4` | Edge tracks `last_applied_seq` **per subscribed Raft group**; gap detection is per-group. |
| **Cross-key atomicity disclaimer** | §0.2 non-goal; `consistency-contract.md §5` | Already "not a multi-key transactional store"; "route both keys to the same Raft group." |
| **Atomic single-group BATCH primitive** | `CommandCodec` (`TYPE_BATCH=0x03`, `MAX_BATCH_COUNT=10_000`) | Codec + wire + contract guarantee exist; endpoint unwired (CM-033). |
| **Scope enum** | `ConfigScope{GLOBAL,REGIONAL,LOCAL}` | Exists; the tier selector for hash-within-scope (D-A). |

**The one thing genuinely *not* ready — and the gating risk — is the single-tick-thread consensus
path.** It both caps single-group throughput today (~800/s) and would *amplify* under naive
multi-Raft. §3 is about exactly that.

---

## 3. The heartbeat-amplification model (the centerpiece — Hard Rule 3)

> **Claim to be proved:** naive N-group multi-Raft on today's single-threaded `MultiRaftDriver` is not
> merely "no faster" — it is **strictly worse than the single group we have now**. The recommended
> design avoids this only by making heartbeat cost flat in N *and* parallelizing per-proposal work off
> the one thread. A multi-Raft design that does not do both is a non-starter.

### 3.1 The measured bottleneck, restated as a budget (constants verified in-repo)

S7.5 proved (real hardware, `throughput-part2.md` §C/§D) the single-group ceiling is **single-tick-thread
heartbeat starvation**, not fsync (`iostat f/s`=0, `w_await`=0.03 ms) and not CPU (86% idle): above
~800/s, per-proposal work on the one `tickExecutor` thread delays the scheduled heartbeat past the
election timeout → a follower elects → in-flight gets `503 NotLeader` → retry storm → self-reinforcing
churn.

Verified constants (`ConfigdServer.java`):
- `TICK_PERIOD_MS = 10` (`:88`) — tick loop fires every 10 ms.
- heartbeat interval default **50 ms** (`:279`, `configd.raft.heartbeatIntervalMs`).
- election timeout **150–300 ms** (`:277–278`).
- `tickExecutor = Executors.newSingleThreadScheduledExecutor(...)` (`:367`) — **one** thread carries
  consensus tick + per-proposal `broadcastAppendEntries` + inbound handling + `applyCommitted`.
- `MultiRaftDriver.tick()` iterates **all** groups on that one thread, O(groups) (`MultiRaftDriver.java:100`).
- 3 co-located nodes ⇒ each leader has **2 peers**.

**The deadline that matters:** the leader must emit a heartbeat to each peer at least once per ~150 ms
(election-timeout floor) or lose leadership. With a 50 ms interval that is a 3× margin. The single
thread effectively has a **150 ms work window that must always contain the heartbeat emit.**

### 3.2 Heartbeat-message arithmetic with N groups (uncoalesced — today's model)

Per node, each group independently heartbeats each peer:

```
heartbeats/s/node (uncoalesced) = N groups × 2 peers × (1000ms / 50ms) = 40·N   (idle; zero writes)
```

| N (shards) | HB msgs/s/node | HB msgs/s cluster (×3) | HB emits per 10 ms tick/node |
|---|---|---|---|
| 1 (today) | 40 | 120 | 0.4 |
| 12 | 480 | 1,440 | 4.8 |
| 16 | 640 | 1,920 | 6.4 |
| 64 | 2,560 | 7,680 | 25.6 |

That is **idle** traffic, before a single write — each an encode + bounded-enqueue *on the one tick
thread*, serialized behind whatever proposals are in flight. Idle heartbeat load grows **linearly in
N.**

### 3.3 Proof: naive N-group multi-Raft is strictly worse than the status quo

One group saturates the single tick thread at ~800 writes/s; the collapse is the moment per-tick work
pushes the heartbeat emit past the election timeout. Let `C` be the thread's effective per-second
work capacity at that knee (empirically `C ≈ 800` proposals-worth of tick-work/s, *including* one
group's heartbeats).

Put N groups on the **same one thread**. The thread must still tick all N groups every 10 ms (O(N)),
emit `40·N` heartbeats/s, *and* service the aggregate proposal load. Aggregate sustainable writes are
bounded by the single thread, with the heartbeat overhead **growing in N**:

```
naive N-group on ONE thread:
   sustainable aggregate writes/s  ≈  C − (overhead growing with N)  =  800 − Θ(N)
   per-shard share                 ≈  (≤800)/N   →  ~50/s/shard at N=16
```

**You pay for N groups' heartbeat+tick+apply overhead and buy *less than* the original 800/s
aggregate** — because the heartbeat traffic you added is the very thing that starves the heartbeat
deadline. Worse, **any one of N groups slipping its heartbeat triggers an election**, so the cluster
is ≈N× more likely to be churning at any instant. Naive multi-Raft on the existing single-threaded
driver is **strictly dominated by the status quo.** This is the non-starter the charter warns of,
shown concretely — and it is precisely CockroachDB #66686 ("a raft tick may not be scheduled for over
200 ms… can delay heartbeats and cause leadership instability") and the CRDB "1M ranges → 50% CPU on
ticking" finding, reproduced in Configd's numbers.

### 3.4 Why it bites before CPU does (scheduling, not compute)

At N=16, *per 10 ms tick* the one thread must iterate 16 groups, emit ~6.4 heartbeats, drain inbound
for 16 groups, and run broadcast-per-propose for arriving proposals. S7.5 established this is
**scheduling/queue latency, not CPU** (86% idle): the lone runnable cannot be in two places, so the
heartbeat waits in the queue behind a proposal-broadcast burst. CPU headroom is irrelevant when the
work is serialized on one thread. Adding groups adds both queue depth and mandatory per-tick heartbeat
emits.

### 3.5 The three mitigations, mapped to Configd, with prerequisite verdict

| # | Mitigation | Prior art (mechanism) | Configd mapping | Verdict |
|---|---|---|---|---|
| **(i)** | **Coalesced heartbeats** — one HB message *per peer-node per tick* carrying all co-located groups' beats | **CockroachDB** ("each pair of nodes only needs to exchange heartbeats once per tick, no matter how many ranges they have in common"; HB scales with **node count, not range count**); **TiKV** merged store heartbeats (#5620) | Batch all N groups' heartbeats for the same peer into one framed message per tick at the transport. Collapses §3.2's `40·N`/s to **`40`/s/peer-pair, constant in N** — back to today's 120 cluster msgs/s regardless of N. | **PREREQUISITE.** Highest-leverage single item. Without it, idle HB traffic is O(N) and defeats sharding. |
| **(ii)** | **Hibernated / quiescent groups** — idle groups stop ticking/heartbeating | **TiKV** Hibernate Region (default-on); **CockroachDB** range quiescence (#357) | A shard with no in-flight proposals + stable leader stops ticking; first write / follower timeout wakes it. | **OPTIONAL for v1 at N≈16; DEFER (see §3.6).** Its benefit is largely *already captured* by (i) at small N, and it carries a serious failover foot-gun. |
| **(iii)** | **Decouple the consensus thread** — a small fixed pool of sharded tick-executors (each owning a disjoint subset of groups) + a dedicated replication/flush thread + **broadcast-coalescing per tick** | **CockroachDB** MultiRaft ("a small, constant number of goroutines (currently 3) instead of one goroutine per range"); **TiKV** raftstore sized worker pool; the **RR-113 lever** (broadcast per tick, not per propose) | Replace the single `tickExecutor` with a small fixed pool (e.g. 2–4 threads), each owning `shardId % poolSize`; move per-proposal `broadcastAppendEntries` to per-tick coalesced broadcast. | **PREREQUISITE to raise the aggregate ceiling.** Coalescing (i) caps idle HB cost, but proposal+apply+broadcast is still one thread until you shard the executor. Per-tick broadcast-coalescing is *independently* the RR-113 lever (helps even at N=1) — **but see §3.8: (i) and (iii) are UNBUILT today, so `aggregate ≈ pool × knee` is a projection, not a measured property.** |

### 3.6 Resolving the hibernation question (a real cross-examination)

The topology research rated hibernation "borderline-prerequisite"; the prior-art research showed it is
a known foot-gun (**TiKV #34906**: a leader on a dead store is *not re-elected until a request touches
the region* → **20+ minute leaderless windows**; CRDB #9446 "quiescing with dead nodes"). These are
not averaged — they are reconciled:

- At **N≈16 with coalesced heartbeats (i)**, idle heartbeat cost is *already flat in N* (≈120 cluster
  msgs/s). Hibernation's marginal benefit at this scale is small.
- Its risk is large and directly antagonistic to Configd's 99.999% write SLO and demonstrated churn
  sensitivity: a hibernated shard whose leader is on a failed node stays write-unavailable until
  touched.
- **Verdict: hibernation is OUT for v1.** It becomes relevant only if N grows large (hundreds+) or the
  keyspace is sparse, and *if* adopted it MUST be paired with proactive, health-driven wake (the
  liveness mechanism TiKV had to bolt on after #34906). This is recorded as a disagreement-resolved,
  not a split-the-difference.

### 3.7 Verdict — the recommended design does NOT reproduce the bottleneck

With **(i) coalesced heartbeats** (HB cost constant in N) **+ (iii) a small sharded tick-executor pool
with per-tick broadcast-coalescing** (proposal/apply/broadcast parallelized across cores, per-proposal
cost cut), the aggregate ceiling becomes ≈ `(pool threads) × (~800/s, knee raised by coalescing)`
instead of a single thread shared N ways. Heartbeat liveness cost is flat in group count; throughput
scales with cores, not with one runnable. **(i)+(iii) are the prerequisite foundational work; they are
simultaneously the single-group throughput fix and the thing that stops multi-Raft from amplifying the
bottleneck.**

### 3.8 Build-state and the R-01 constraint (red-team-confirmed; honesty correction)

Two corrections, verified in the live code — the recommended design is sound, but the work is larger
and less built than §3.5–§3.7 phrasing implied:

- **Mitigations (i) and (iii) are UNBUILT today.** `RaftNode.propose()` calls `broadcastAppendEntries()`
  inline on *every* proposal (`RaftNode.java:460`); heartbeats are sent per-peer in `tickHeartbeat`.
  There is no coalesced heartbeat and no per-tick broadcast-coalescing. S7.5 *recommended* the
  broadcast-per-tick lever (§E.2) but **validated only admission control** (§G). So
  `aggregate ≈ pool × knee` (§3.7) is a **projection over unbuilt mechanisms, to be proven on hardware**,
  not a property of any built system — and the throughput target inherits that
  (`adr-throughput-target.md`).
- **The sharded pool DELETES the current synchronization mechanism and must rebuild it per shard.**
  Post-R-01, the live server makes the deliberately-non-synchronized `RaftNode` safe by funnelling
  **all** access onto the **single** `tickExecutor`: `tick()` (`ConfigdServer.java:779`), inbound
  `routeMessage` (`:1098`), `propose` (`:1212`), commit-callback, group-commit `flush` (`:403`),
  `maybeCompact`, ReadIndex `completeRead` (`:663`), and the non-volatile `commitIndex/lastApplied`
  metric reads (`:779`) — class comment `:362-365`: *"ALL RaftNode access … happens ONLY on the tick
  thread."* **The single thread IS the synchronization.** A sharded pool replaces it, so the
  prerequisite is not "add 2–4 threads" but: define **`ownerExecutor(shardId) = pool[shardId %
  poolSize]`** and route **every** one of those paths for a shard onto its owner thread, so each
  `RaftNode` is still touched by exactly one thread. This **re-opens the S2–S4 consensus-integration
  verification surface** (a concurrent tick+inbound+propose+flush stress test that `STATE-OF-REALITY`
  §6.1 flags as not existing) and must precede any shard-routing. It is partly-greenfield consensus-core
  work, not a drop-in pool.
- **Coalesced heartbeats add a cross-thread read and a correlated-failure frame.** One beat-per-peer
  carrying N co-located groups' beats must read every group's leader/term/commit state each tick —
  groups owned by *different* pool threads — so assembly either needs its own synchronization or
  serializes back onto one thread; and one lost coalesced frame de-livens **all N co-located groups at
  once** (this belongs in the §7 correlated-election-storm cell — it is *caused by* coalescing, not
  only by node loss).

---

## 4. Per-node load model + safe N

Uniform placement across 3 nodes ⇒ each node **leads ~N/3 shards, follows ~2N/3.**

| Resource | Per-shard cost | At N=16 (per node) | Constraint |
|---|---|---|---|
| Heartbeat msgs/s | uncoalesced 40/s; **coalesced ≈ 0 marginal** | coalesced ~120/s cluster, **constant in N** | (i) makes this O(1) in N |
| Tick iteration | O(1)/group/tick | 16 group-ticks/10 ms (trivial) | sharded pool (iii) parallelizes |
| Memory | one `RaftNode` + log/WAL + snapshot per shard; WAL bounded by `RAFT_LOG_COMPACTION_THRESHOLD=10_000` applied-span | N× per-group footprint — modest at N=16 | linear in N; watch at N≥64 (N WAL fsync working set) |
| Threads | **0 new per shard** (driver is per-node) | tick pool 2–4 threads total, **independent of N** | the whole point of the store pattern |
| Write CPU | propose+broadcast+apply on owning executor thread | aggregate ≤ pool × knee | (iii) sets the ceiling |

**Safe N:**
- **On the current single-thread driver (unmodified): safe N = 1.** Any N>1 with uncoalesced
  heartbeats is the §3.3 amplification — strictly worse than today.
- **On a small sharded pool (2–4 threads) + coalescing + per-tick broadcast: safe N ≈ 16–32.** N=16
  is comfortable (~5–6 led shards/node). Beyond ~64, the N-WAL fsync working set and snapshot
  bookkeeping start to matter (and you'd want hibernation, with the §3.6 caveat).

---

## 5. The single-group fix is FOUNDATIONAL — confirmed (charter §4)

The charter states the single-group throughput fix "precedes or accompanies sharding, not skipped."
This analysis **confirms and sharpens that**: the fix (coalesced heartbeats + sharded tick-executor
pool + per-tick broadcast-coalescing) is not merely beneficial — it is the *mechanism that makes
sharding safe at all* (§3.3/§3.7). Each shard *is* a single group, so the fix benefits every shard;
and without it, adding shards is actively harmful. **Therefore: the consensus-thread decoupling is the
first build-phase work of the multi-Raft arc, before any shard-routing lands.** Per §3.8 this is a
**partly-greenfield consensus-core build** (coalesced heartbeats + per-tick broadcast + the
`ownerExecutor`-per-shard marshalling are all unbuilt today) that **re-opens the S2–S4
integration-verification surface** — it is materially larger than a "co-deliver the fix" phrasing
implies, and the operator should scope it as such. (Admission control — S7.5, validated — remains the
graceful-degradation backstop on top, not a substitute.)

---

## 6. Per-decision analysis against Configd's constraints (summary; full reasoning in the ADRs)

- **D-A — Partitioning (→ `adr-multiraft-partitioning.md`): HASH-within-scope.** Configd's read path
  is **point lookups** (HAMT get ~483 ns), not range scans, so Range's only benefit (scan locality) is
  inapplicable while its cost (hot-shard skew) is fully retained. Deployment-correlated bursts
  (`/team-x/.../feature.*`) land on one contiguous Range shard = the measured ~1000/s churn mode; hash
  disperses by construction. Prefix *subscriptions* are unaffected — the distribution node already
  unions all shard streams and localizes prefixes via its radix trie, not via shard adjacency.
  Prior art: Slicer (hash → range-partition the *hashed* space) + Dynamo consistent hashing.
- **D-B — Topology (→ `adr-multiraft-topology.md`): STATIC-N (N≈16) behind a `ShardMap` indirection,
  dynamic deferred to v2 as a drop-in `DynamicShardMap` swap** — gated on the §3 prerequisite fix.
  Static delivers both wins (throughput ≈ pool×knee — *projected*, levers unbuilt per §3.8; blast radius
  1/N for a single-shard partition, **1/3 under node loss on 3 nodes**) at minimal surface; dynamic
  split/merge is the richest production-bug vein in the survey (prior-art L3) and adds *zero* throughput
  over static. The v1/v2 seam holds three invariants: opaque stable shard IDs, routing always via
  `ShardMap.shardFor(...)`, and an epoch/membership-version field on the wire (the TiKV
  `RegionEpoch{ConfVer,Version}` pattern — prior-art L4).
- **D-C — Cross-shard (→ `adr-multiraft-cross-shard.md`): DISCLAIM.** §0.2 names cross-key atomicity a
  non-goal; the contract already says "route both keys to the same group"; `research.md:343/385/401`
  already recorded the rejection of Percolator / parallel-commits / Spanner-2PC ("no cross-key
  transactions needed"). The escape hatch — co-locate related keys in one shard → existing single-group
  atomic `BATCH` — covers the realistic 2–3-key case with zero new failure surface. Building 2PC breaks
  the <150 ms budget *and* forces a per-key→strict-serializable rewrite of the configd-linz Porcupine
  harness. **This decision supersedes ADR-0023's migration-plan bullet that named a "cross-shard
  transaction coordinator (2PC over Raft)."**

## 7. Verification machinery must EXTEND, not replace (Hard Rule 7)

The existing machinery is the foundation the build extends:

- **Deterministic simulation** (`RaftSimulation`/`SimulatedNetwork`, seeded): instantiate N groups under
  one driver; **the amplification guard** — assert per-tick heartbeat-emit latency stays under the
  election floor as N scales; replay a bursty single-prefix deployment workload and assert per-group
  rate ≤ the ~800/s knee under hash, with a Range router as a *negative control* that reproduces the
  hotspot.
- **TLA+** (`ConsensusSpec`/`ReadIndexSpec`/`SnapshotInstallSpec`): add a routing-correctness invariant
  (a key resolves to exactly one live shard under a given epoch); each shard is an independent instance
  of the existing specs. *No new commit-protocol spec* (that is the cost DISCLAIM avoids).
- **configd-linz (Porcupine, per-key registers):** unchanged in model — each key stays an independent
  linearizable register; add a hash router and assert single-key linearizability holds (same key →
  same group) and per-group gap-detection under multi-group interleaving. **DISCLAIM keeps this
  additive; BUILD would force a multi-register strict-serializable checker (a rewrite).**
- **Chaos / fault matrix:** NEW cells that static-N introduces — (a) **correlated leadership loss**
  (kill a node leading many shards → assert no aggregate election storm; coalesced HB + per-shard
  staggered election timeouts mitigate); (b) **per-shard partition** (assert blast radius is exactly
  1/N — the containment claim); (c) **routing under reconfiguration** (stale `ShardMap` epoch must
  shed/redirect, not mis-commit); (d) hash-rebalance N→N+1 bounds key movement to ~1/N. For any future
  v2: split/merge under partition / fsync-lie / ENOSPC (the hardest cell — prior-art L3).

## 8. Open coupling between the decisions (for the operator)

- **D-A hash de-risks D-B:** hash removes the *primary* forcing function for dynamic resharding
  (hot-shard skew), so static-N is viable far longer. Range would *force* dynamic from day one.
- **D-C co-location vs D-A/D-B spread:** the escape hatch forces a small minority of related keys onto
  one shard — negligible load vs the independent majority that sharding spreads. D-A/D-B must honor a
  co-location/scope-affinity hint as a routing input (not a blocker).
- **The throughput target (→ `adr-throughput-target.md`)** is `N × per-group-knee × efficiency`; with
  knee ~800/s (co-location-confounded; dedicated-host re-measure owed) and efficiency ~0.75,
  N≈16 → ~9.6k/s order-of-magnitude, to be proven on hardware. N stays a deploy-time constant precisely
  so the knee can be re-measured without a reshard.
