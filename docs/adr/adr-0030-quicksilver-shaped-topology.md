# ADR-0030: Centralized Strongly-Consistent Writes with Asynchronous Bounded-Staleness Edge Fan-out (Quicksilver-Shaped Topology); Reject Global Multi-Region / Hierarchical Raft Write Consensus

## Status

**Accepted** (2026-06-27 — ratified in the pre-EC2 cleanup). Was **Proposed** (under review) at authoring
(2026-06-06).

> **Ratification & reality-update note (2026-06-27).** The **core decision stands**: a single,
> region-local, strongly-consistent Raft root for writes + asynchronous bounded-staleness edge fan-out,
> and **reject global multi-region / hierarchical Raft *write* consensus** (the §Reasoning latency
> arithmetic is unchanged). The `## Context`, `## Reasoning`, and `## Consequences` below describe the
> repository **as it was at authoring (2026-06-06)**; the following has changed since and is reconciled
> here (the inline `file:line` references in those sections are therefore historical):
>
> - **"Registers exactly one Raft group" (Context, ~L27–31) is now historical.** The multi-Raft sharding
>   that `adr-0023-multi-raft-sharding-deferred.md` deferred to v0.2 has since been **built** (sim-verified
>   foundation merged; production server-wiring landed; the N>1 production-boot switch lands via the
>   multi-Raft workstream). Sharding partitions the **region-local root** by hash-within-scope; it does
>   **not** introduce WAN / multi-region write consensus, so it is exactly the "partition the root, not the
>   regions" path this ADR endorsed in Rejected-Alternative #6, and is consistent with the core decision.
>   **Default is N=1 (byte-identical to single-group)** and N=1 remains the validated v1 topology; N>1
>   aggregate throughput is **unmeasured pending the EC2 N×knee measurement**.
> - **The async fan-out is now WIRED (Session-3 edge data plane).** The point-7 / Consequences residual
>   "async fan-out … UNWIRED" is **closed for wiring** (`FanOutServer`, the commit-notification stream,
>   and the edge plane are all built and gated). End-to-end propagation/staleness **at scale** remains a
>   **target, not yet measured** (the deferred EC2 soak) — that portion of the residual stands.
> - **The thread-unsafe `RaftNode`/`ConfigStateMachine` integration race (point 6 / Risks) is FIXED** by
>   the Phase-0 owner-thread re-threading (one owner thread per group; the `apply_owner_thread` tripwire,
>   `consistency-contract.md §8`). The "no runtime invariant enforcement in production" risk is likewise
>   closed — `InvariantMonitor` runs in production (`testMode=false` ⇒ metric + log; `consistency-contract.md §8`).
> - **The §0.1 write-availability renegotiation is resolved:** `adr-0031` is now **Accepted**, so the
>   "HUMAN RATIFICATION PENDING / stops for review before merge" caveat under §"SLO impact" is discharged.
>   The single-region-root **full-region-loss** shortfall remains an accepted, documented residual (manual
>   cutover; sub-second region failover deferred to the `adr-0024` v0.2 cross-DC bridge).
> - **Naming honesty (cross-ref RR-098).** Amendment A1 / INV-1's `GLOBAL`/security key class (default
>   prefix `secure/`) is a **read-freshness** guarantee (always-linearizable, fail-closed, never served
>   stale) for security-*critical* decisions — it is **NOT** at-rest confidentiality/encryption. Configd
>   does not encrypt data at rest in v1; see `docs/known-limitations.md` and `docs/consistency-contract.md §9`.
>
> **Open residuals (unchanged, honest):** full-region write availability is not five-nines (A2 covers
> single-AZ loss automatically; full-region failover deferred to `adr-0024` v0.2); data residency deferred
> with residual stated (A3); end-to-end propagation/staleness numbers are MODELED until the EC2
> measurement; INV-1 (GLOBAL fail-closed) and INV-2 (residency deploy-time guardrail) remain Phase-B
> obligations.

Supersedes the *write-topology* portion of `adr-0015-multi-region-topology.md`
(the global 5-voter cross-region Raft group + regional groups + closed-timestamp
follower-read design). Reconciles with — and does not contradict —
`adr-0023-multi-raft-sharding-deferred.md` and `adr-0024-cross-dc-bridge-deferred.md`,
which already concede v0.1 is single-Raft / single-DC and that "WAN-stretched Raft
violates the SLO" (`adr-0024-cross-dc-bridge-deferred.md:23-25`). Aligned with the
split consistency model of `adr-0019-consistency-model.md` (which this ADR does not
change). See **Consequences → Relationships** for the precise reconciliation.

## Context

§0.1 (`PROMPT.md:16-27`) fixes the contract for any topology:

- Write commit latency **p99 < 150 ms cross-region**
- Propagation / edge visibility **p99 < 500 ms global**
- Edge read **p99 < 1 ms (in-process), p999 < 5 ms**
- Availability **99.999% control-plane writes, 99.9999% edge reads**
- 10k writes/s baseline, 100k/s burst; 10^6 keys baseline, 10^9 ceiling; 10k edges
  baseline, 1M ceiling.

The repository currently registers exactly **one** Raft group:
`ConfigdServer.java:82` declares `DEFAULT_RAFT_GROUP = 0`, `:252` is the only
`driver.addGroup(...)` call, and every proposal routes to it (`:369`). There is no
`region`, `non-voting`, or `closedTimestamp` code in `src/main`
(`docs/STATE-OF-REALITY.md:93-97`). The async fan-out half is unwired:
`fanOutBuffer.append(delta)` at `ConfigdServer.java:301` has **no draining reader** in
`src/main` (the `FanOutBuffer.deltasSince`/`latest` readers exist but are called by
nothing outside the class — verified by grep; corroborated by
`docs/STATE-OF-REALITY.md:62, :98-103`), and `PlumtreeNode.broadcast()` is invoked only
in a benchmark (`docs/STATE-OF-REALITY.md:62`).

So the decision is genuinely open: do we (a) **build outward** into the global
multi-region / hierarchical-Raft *write* consensus that `adr-0015` describes (and that
the devils-advocate steelmans), or (b) **adopt the Quicksilver shape** — one centralized
strongly-consistent write group, asynchronous fan-out to eventually-consistent edges,
under a bounded-staleness contract — and reject WAN write consensus. This ADR chooses (b),
with two scoped amendments forced by honest engagement with the adversary (a GLOBAL/security
strong-read key class, and a root group spanning low-RTT AZs/DCs for automatic survival),
and explicitly defers data residency with the residual stated (§0.2 does not enumerate residency
as a non-goal; A3 defers it rather than claiming §0.2 scoped it out).

The §0.2 non-goals (`PROMPT.md:31-41`) fence this: Configd is not a general KV store, not a
multi-key transactional store, not a secrets manager, not a schema registry, not a pub/sub
bus. That fence is load-bearing below — several of the strongest "keep multi-region write
consensus" arguments assume transactional or residency semantics that §0.2 places out of
scope.

## Decision

Adopt a **Quicksilver-shaped topology**:

1. **Writes: one centralized, strongly-consistent Raft group ("the root").** All linearizable
   writes commit in a single Raft group whose voters are co-located in **one low-RTT failure
   cluster** (one region, voters spread across 3+ AZs / nearby DCs — see amendment A2). The
   root is the only consensus participant. No write quorum is stretched across APAC↔US↔EU.
   This is exactly Cloudflare's "centralized root cluster… built on the etcd Raft package"
   (`research-quicksilver.md:101-109`).

2. **Distribution: asynchronous hierarchical fan-out to eventually-consistent edges.** Committed
   deltas are pushed (Plumtree-over-HyParView per `adr-0011-fan-out-topology.md`) down a
   distribution tree to edges that hold a **local copy** and serve reads with **no consensus
   participation**. Edge reads are sequentially-consistent and bounded-stale, never linearizable
   (matching `adr-0019-consistency-model.md` and `consistency-contract.md`). Gap detection via
   the gap-free monotonic sequence number (`consistency-contract.md:126-133`).

3. **Bounded-staleness contract.** The edge promise is the contract already written:
   p99 < 500 ms, p999 < 1 s, p9999 < 2 s, with STALE/DEGRADED/DISCONNECTED degradation
   (`consistency-contract.md:38-57`). This is a *target*, not yet a measured property — see
   Consequences.

4. **REJECT global multi-region / hierarchical Raft *write* consensus.** No 5-voter group
   spanning core regions, no per-region write groups with cross-group ordering, no
   closed-timestamp side-transport on the write path. The cross-region *read*-locality benefit
   is obtained via async edge copies, not via in-Raft non-voting replicas.

### Amendment A1 — GLOBAL / security strong-read key class (forced by adversary #2)

A declared **`GLOBAL` key class** (security kill-switches, ACL/auth revocations, legal gates)
is exempt from the bounded-staleness read contract. Reads of a `GLOBAL` key MUST use the
linearizable path — control-plane **ReadIndex** against the root
(`consistency-contract.md:16-20`, `adr-0019-consistency-model.md:59-65`) — and enforcement
points (auth services, API gateways) MUST issue a strong read on the security decision rather
than trusting an edge copy. Bounded-stale edge serving of `GLOBAL` keys is a contract
violation, not a tuning choice. This is a *read*-path amendment only; it does **not**
reintroduce a cross-region write quorum. Residual cost is stated in Consequences.

### Amendment A2 — root group spans multiple low-RTT AZs/DCs for automatic survival (forced by adversary #1/#5)

The root is **not** a single-AZ, single-failure-domain group. Its voters are placed across
**≥ 3 availability zones (or ≥ 3 sub-100 ms-RTT DCs) within one region**, so the loss of any
single AZ/DC leaves a majority and triggers **automatic Raft leader election** (PreVote +
CheckQuorum, `docs/architecture.md:211-219`), with no human and no split-brain. This keeps the
commit floor low (intra-region AZ RTTs are single-digit ms, far under the §0.1 budget) while
buying automatic survival of a single-AZ event. It does **not** survive a full-region loss
without manual standby cutover — that residual is stated explicitly in Consequences and in
the rebuttal to adversary #1/#5.

### Amendment A3 — data residency explicitly DEFERRED with residual stated (forced by adversary #4)

In-jurisdiction authoritative commit (GDPR/Schrems II, DPDP/RBI, PIPL/CSL, 152-FZ) is **not
solved** by this topology and is **explicitly deferred** — per the solve-or-defer-with-residual
directive given for this ADR (residency is not enumerated in §0.2's non-goals; it is deferred
here, not declared out of scope by §0.2), with the residual stated. The
residual: a single-root deployment commits all writes in one jurisdiction, which is
non-compliant for data classes under hard localization mandates. The available v0.1 path is
the same one `adr-0023-multi-raft-sharding-deferred.md:48-50` and
`adr-0024-cross-dc-bridge-deferred.md:37-38` already document — N independent in-jurisdiction
clusters with application-layer routing — at the cost of cross-cluster shared ordering. This
is labeled **RESIDUAL RISK** below, not rebutted.

## Influenced by

- **Cloudflare Quicksilver (primary baseline; `PROMPT.md:117`).** Borrowed mechanism:
  *consensus for the source of truth, coordination for distribution.* A single centralized
  Raft root built on "the etcd Raft package" for durable ordered writes, then asynchronous
  hierarchical fan-out to every data center where edges serve sequentially-consistent local
  reads with **zero edge consensus** (`research-quicksilver.md:89-124`). Cloudflare distributes
  a config change "to 200 cities in 90 countries… within seconds" (2020) and at v2 serves "over
  three billion keys per second" at "90% of requests in less than 1 ms" (2025)
  (`research-quicksilver.md:126-135, :231-235`). Also borrowed: **monotonic sequence numbers**
  for gap detection ("exactly one higher than the last message we have seen",
  `research-quicksilver.md:150-152`), **MVCC + sliding window** for sequential consistency under
  tiered caching (`research-quicksilver.md:153-165`), and a **30-second disconnect / catch-up
  threshold** for slow consumers (`research-quicksilver.md:135-136`, already in
  `consistency-contract.md:57`). Sources: *Introducing Quicksilver* (2020), *Moving Quicksilver
  into production* (2020), *Quicksilver v2 Parts 1 & 2* (2025) — full URLs in
  `research-quicksilver.md:580-589`.

- **etcd / single-group Raft (`PROMPT.md:111`, `research-quicksilver.md:255-295`).** Borrowed
  the centralized single-group Raft engine (the same engine Quicksilver's root uses) — and
  borrowed it as a **constraint**: etcd's own guidance that a cluster "probably should have no
  more than seven nodes" because "the write performance suffers because data must be replicated
  across more machines," and that cross-DC consensus latency is "pronounced," is precisely why
  the voting set is kept small and region-local rather than WAN-stretched (etcd FAQ,
  `research-quicksilver.md:261-267`).

- **CockroachDB / Spanner closed-timestamp & safe-timestamp follower reads
  (`research-quicksilver.md:298-346`).** Borrowed the *concept* (a safe timestamp lets a
  follower serve bounded-staleness reads locally) but **rejected the in-Raft delivery**
  (cross-region voting/non-voting replicas). The read-locality benefit is obtained via
  Quicksilver-style async edge copies instead, avoiding the cross-region write quorum entirely.
  Sources: Cockroach Labs *optimize write latency for global tables* (2022); Google Cloud
  *Spanner: TrueTime and external consistency* (`research-quicksilver.md:596-599`).

## Reasoning

All external numbers below are cited to `research-quicksilver.md` (which cites public primary
sources). All repo numbers carry `file:line`. Numbers I could not measure in this repo are
labeled **modeled** or **unmeasured**.

### 1. Cross-region write consensus cannot meet §0.1's `write p99 < 150 ms` for a non-co-located client.

A Raft write commits when a majority including the leader has durably appended it; the
network-only commit floor is the RTT to the `floor(N/2)`-th nearest voter (Ongaro & Ousterhout;
arXiv 1902.02537, `research-quicksilver.md:360-376`). Using **measured** AWS inter-region RTTs
(`research-quicksilver.md:385-393`):

- A 5-voter global group with leader in us-east-1 has a **network-only commit floor of
  ~68.5 ms** (RTT to the 2nd-nearest voter, eu-west-1) — this matches the "~68 ms" `adr-0015`
  assumes (`adr-0015-multi-region-topology.md:32`).
- But §0.1's budget is **cross-region**, i.e. it must hold for a writer not next to the leader.
  End-to-end = `RTT(client↔leader) + commit_floor`. For an **ap-southeast-1 client to a us-east
  group: 219.64 + 68.55 ≈ 288 ms at the floor** — **~1.9× over the 150 ms budget**, before
  fsync (etcd targets each node's fdatasync **under 10 ms**), batching, and queueing
  (`research-quicksilver.md:426-457`). ap-northeast-1 ≈ 217.7 ms (~1.45× over).

This is physics, not an implementation defect: CockroachDB independently reports `GLOBAL`-table
writes "as high as 800 ms… [optimizable to] 250 ms or less"
(`research-quicksilver.md:326-329`); Spanner pays ~7 ms commit-wait for clock uncertainty
*alone* on top of cross-region Paxos (secondary; ~7 ms per Spanner OSDI 2012,
`research-quicksilver.md:311-317`). Re-placing the leader
does not fix it — it moves the penalty to a different region's clients
(`research-quicksilver.md:438-441`).

### 2. A single Raft group does not scale write throughput and is *harmed* by WAN spread.

Official etcd guidance: "the write performance suffers because data must be replicated across
more machines" (`research-quicksilver.md:261-263`); adding voters raises the quorum size and
fsync count without adding write capacity (`research-quicksilver.md:280-295`). Therefore the
only correct use of one group is a **small, region-local, centralized root** — exactly the
Quicksilver shape — not a global write fabric. Spreading voters "for survivability" actively
pushes the commit floor up: a 7-voter group whose 3rd-nearest voter sits in ap-northeast-1 has
a **~149 ms commit floor** (`research-quicksilver.md:443-449`).

### 3. The Quicksilver shape hits all three latency budgets.

- **Write p99 < 150 ms:** commits occur inside one low-RTT cluster (intra-region AZ RTTs are
  single-digit ms per `docs/architecture.md:435-436` showing 20 ms for EU-regional and 69 ms
  AP-regional intra-pairs; AZ-internal is lower still). The client routes to the root region.
  Quicksilver's root adds a 500 ms *batch* window by design
  (`research-quicksilver.md:110-113`), but the *commit* itself is intra-cluster; Configd can
  choose a far smaller micro-batch. **Modeled**, consistent with §C.4 of the research
  (`research-quicksilver.md:461-466`).
- **Propagation p99 < 500 ms global:** asynchronous fan-out. Quicksilver publishes the
  qualitative "within seconds" plus a 30-second disconnect threshold and did **not** publish a
  hard sub-second propagation p99 — Configd's `< 500 ms p99` is a **target**, marked
  `[UNVERIFIED for Quicksilver]` in the research (`research-quicksilver.md:137-140, :616-619`).
  The fan-out path is **currently unwired** in this repo (`ConfigdServer.java:301` append with
  no drain) — this number is therefore **unmeasured end-to-end**; see Consequences and the
  rebuttal to adversary #7.
- **Edge read p99 < 1 ms / p999 < 5 ms:** edges serve from a local copy with no consensus,
  matching Quicksilver's "90% of requests in less than 1 ms," "99.9% … less than 7 ms"
  (`research-quicksilver.md:221, :231-235`). The repo's lock-free volatile-snapshot MVCC read
  path (`VersionedConfigStore.java:42, :189`, verified real in
  `docs/STATE-OF-REALITY.md:60, :200-204`) is the mechanism. A Raft follower read cannot match
  this: it needs ReadIndex/lease confirmation (≥ intra-region RTT) or a closed-timestamp
  staleness window (`research-quicksilver.md:465`).

### 4. It also wins §0.3's operational-complexity axis.

The rejected `adr-0015` design requires a PlacementDriver, scope-aware shard routing,
cross-region non-voting replicas, and a closed-timestamp side-transport at 200 ms cadence
(`adr-0015-multi-region-topology.md:41, :87`; `docs/architecture.md:190-197`) —
CockroachDB-class machinery. The Quicksilver shape needs one root group plus a fan-out tree
with monotonic sequence numbers for gap detection. §0.3 grades operational complexity as "zero
external coordination" (`PROMPT.md:54`).

## Rejected Alternatives

This section reproduces the devils-advocate steelman as a numbered list of its distinct
arguments **in their strongest form** (`devils-advocate-case.md`), then rebuts each. Where a
point cannot be fully defeated it is labeled **RESIDUAL RISK** or **ACCEPTED TRADE-OFF** with
the mitigation/scope.

### Adversary framing (the "streetlight fallacy") — addressed first because it conditions every point.

**Their argument (`devils-advocate-case.md:15-22, :221-243`):** the Quicksilver shape is being
"sold as matching reality" because the code today registers only one Raft group
(`ConfigdServer.java:82, :252`); that is an *implementation gap*, not an *architecture
validation*. Adopting the half-built topology is the streetlight fallacy — the single-group
reality is a reason to *finish the multi-region build*, not to *ratify its absence*.

**Rebuttal — conceded as a framing rule, rejected as a verdict driver.** The adversary is
correct that "the code already does this" is **not** a valid justification, and this ADR does
not use it as one. The verdict in §Reasoning rests entirely on (a) measured cross-region RTT
arithmetic vs §0.1 (`research-quicksilver.md:426-457`), (b) etcd's own write-scaling constraint
(`research-quicksilver.md:261-295`), and (c) Cloudflare's published existence proof that the
shape meets the budgets at 330-city scale (`research-quicksilver.md:243-249`). If the code
registered five cross-region groups today, the §C arithmetic would still reject them. The
"matches reality" property is a *consequence* of the decision being cheap to reach from here,
not its *cause*. I accept the streetlight rule and have removed "it's already half-built" from
the justification.

---

**1. Region-loss write availability: single-group collapses globally on root-region loss; cross-region Raft survives by design — and the SLO is 99.999%.**

*Their strongest form (`devils-advocate-case.md:26-65`):* one group, one leader, one root
region. A correlated root-region event (AZ power/network, bad deploy, BGP/DNS withdrawal,
fat-fingered `kubectl` against the leader namespace — `adr-0024:56` pins the cluster to one
namespace) stops **100% of writes worldwide** until a human rebuilds/fails over. §0.1 demands
99.999% = **5.26 min/year**. `adr-0024:30-33` admits the DR story is a **manual** standby-cluster
cutover; a realistic manual cutover is minutes-to-tens-of-minutes (`adr-0015:50` even lists
"Minutes (manual)") — **one such event per year blows the entire annual budget.** A 5-voter
2+2+1 cross-region group survives any single region loss with **automatic** election in one
timeout (~150–300 ms, `RaftNode.java:1645`; documented RTO < 10 s, `adr-0015:49`).

*Rebuttal — partially rebutted via Amendment A2; full-region survival is an **ACCEPTED
TRADE-OFF**.*

- The adversary's premise that the root is necessarily a **single failure domain** is the part
  I reject. **Amendment A2** places the root's voters across **≥ 3 AZs / ≥ 3 sub-100 ms DCs in
  one region**, so a single-AZ event (the most common correlated event) leaves a majority and
  triggers **automatic** Raft election with the adversary's own ~150–300 ms timeout
  (`docs/architecture.md:211-219`; the same PreVote/CheckQuorum machinery they cite). This
  recovers the "automatic, fenced, loss-free" failover for AZ loss **without** any cross-region
  write quorum and **without** the ~288 ms latency penalty of §Reasoning-1. Commit floor stays
  single-digit ms (intra-region AZ RTT), well under §0.1.
- **What A2 does NOT buy — full-region loss — is an ACCEPTED TRADE-OFF.** A whole-region outage
  (submarine-cable cut to the region, region-wide control-plane failure) still requires manual
  standby cutover. The adversary's five-nines arithmetic is correct *for that event class*: a
  single multi-minute manual cutover breaches the annual budget. **Mitigation/scope:**
  (i) full-region loss is rarer than AZ loss, which A2 now covers automatically; (ii) the
  five-nines target is for *writes* — edge **reads** continue from local copies during a root
  outage (the 99.9999% edge-read budget is unaffected, by construction); (iii) Configd MUST
  treat sub-second region-failover as a deferred capability (the v0.2 cross-DC bridge of
  `adr-0024`), and until then **the write-availability SLO under full-region loss is not met by
  this topology** — stated, not hidden. Note the adversary's own cross-region alternative also
  bottoms out at "Minutes (manual)" for the *majority*-loss case (`adr-0015:50`), so neither
  design gives automatic survival of a 2-of-3-region loss; A2 closes the gap for the single-AZ
  case at zero latency cost.

**RESIDUAL: write availability under full-region loss is not five-nines with this topology
(ACCEPTED TRADE-OFF; mitigated for AZ loss by A2; full-region automatic failover deferred to the
v0.2 cross-DC bridge, `adr-0024`).**

---

**2. Security/auth/legal keys have no safe staleness window; bounded staleness is the wrong contract for negative authorization.**

*Their strongest form (`devils-advocate-case.md:69-107`):* a revocation (`auth.acl.revoke`,
`security.killswitch.tenantX`, legal cut-off) commits linearizably at the root, then fan-out is
async and edges serve bounded-stale (500 ms p99 → up to 2 s p9999, and 5 s/30 s under
STALE/DEGRADED during partition — `consistency-contract.md:38-57`). At 50k req/s an edge can
serve **~100k requests on a revoked credential** in the 2 s window, **~1.5M** in a 30 s DEGRADED
window — **by contract, not by bug**. The `X-Configd-Stale` header is not enforcement; an attacker
ignores it. The cost of staleness here is *unbounded damage*, while the contract bounds only
*time*. Cross-region read-your-writes is opt-in (`consistency-contract.md:170-173`), so the
operator who pushed the kill-switch is not even guaranteed to observe their own revocation at an
APAC edge.

*Rebuttal — the design defect is real; fixed by Amendment A1 (a strong-read key class), not by
defending bounded staleness.* This is the adversary's strongest point and I do **not** try to
rebut "bounded staleness is fine for kill-switches" — it is not. The fix:

- **Amendment A1** introduces a `GLOBAL`/security key class that is **exempt from bounded-stale
  edge serving**. Reads of these keys go through linearizable **ReadIndex** against the root
  (`adr-0019-consistency-model.md:59-65`, `consistency-contract.md:16-20`), and enforcement
  points (auth services, gateways) MUST do a strong read on the security decision rather than
  trust an edge copy. This is exactly the "choose strong-read for keys where staleness is a
  breach" capability the adversary demands (`devils-advocate-case.md:97-107`) — obtained **on
  the read path only**, so it does not reintroduce a cross-region *write* quorum.
- **Where A1 differs from the adversary's remedy, and the residual cost.** The adversary gets
  the strong read from an **in-region voter** at ~one regional RTT. Configd's strong read for a
  `GLOBAL` key hits the **single root** via ReadIndex, so a distant (e.g. APAC) enforcement
  point pays one client↔root RTT (~220 ms) for that read. This is acceptable because
  (i) security decisions are far rarer than ordinary config reads (the 99.9999% edge-read budget
  applies to the bounded-stale data plane, not to deliberately-strong security reads),
  (ii) enforcement points can cache a *negative* decision with a short, security-chosen TTL
  rather than the default staleness window, and (iii) a ~220 ms strong read is correct, whereas a
  sub-ms stale read is a breach. **ACCEPTED TRADE-OFF:** `GLOBAL`-key strong reads are
  high-latency (single-root RTT) and, during a root-region outage (point 1), unavailable —
  enforcement points MUST fail **closed** (deny) for security keys when the strong-read path is
  unavailable, never fall back to a stale copy.

**RESIDUAL: `GLOBAL`-key strong reads cost a single-root RTT (modeled ~220 ms worst-case APAC)
and are unavailable during root-region loss; enforcement MUST fail-closed (ACCEPTED TRADE-OFF,
mitigated by A1 + fail-closed rule + short negative-cache TTL).**

---

**3. Scope-aware placement: regional writes never hit a global quorum, so the "288 ms APAC floor" only applies to the ~10% genuinely-global keys.**

*Their strongest form (`devils-advocate-case.md:111-143`):* §Reasoning-1's 288 ms figure is a
strawman against the hierarchical design, because that design **never** routes a regional write
to the global group. `adr-0015` splits writes: ~60% `REGIONAL` commit in a 3-voter in-region
group at **2–5 ms** (`adr-0015:33, :68`), ~30% `LOCAL` at **< 1 ms** (`adr-0015:69`), and only
~10% genuinely-`GLOBAL` pay the 68 ms cross-region cost (`adr-0015:67`). Weighted average
**~9 ms** (`adr-0015:71`) — comfortably under 150 ms. Conversely, the adversary argues, it is
the **Quicksilver-shaped single-root** design that regresses: it forces an apac→apac regional
write that *should* be 2–5 ms to round-trip to us-east and back (~440 ms), a ~100× regression on
60% of traffic, and is "literally re-adopting the alternative ADR-0015 already rejected with
numbers" (`adr-0015:80`).

*Rebuttal — engaged head-on; the adversary's arithmetic is right for their model, but the model
assumes a high-rate per-region application-write load this config plane does not carry, and their
own latency win re-imports the centralized root (in-region, per their #3(c)).*

- **The ~9 ms weighted average is real arithmetic and I do not dispute it** for a world where
  config keys partition cleanly into GLOBAL/REGIONAL/LOCAL scopes with the cited write mix. So
  the 288 ms figure is **not** the rebuttal to scope-aware placement; it is the rebuttal only to
  *uniform* global consensus. The real disagreement is narrower and below.
- **Scope-aware placement's REGIONAL tier is not free — it buys low write latency by giving up
  the very thing this system exists for: a single global order.** A `REGIONAL` write committed by
  an in-region group has **its own independent sequence space**; cross-group order is "NOT
  GUARANTEED … approximate via HLC" (`consistency-contract.md:145-149`,
  `adr-0019-consistency-model.md:67-72`). So the 2–5 ms regional commit is cheap precisely
  because it is **not** globally ordered. For a *configuration control plane* whose stated value
  is "a definitive version and ordering that all observers will eventually see"
  (`adr-0019-consistency-model.md:83`), buying write latency with a fragmented version space is a
  poor trade: the Quicksilver-shaped single root gives **one** total order for the whole keyspace
  at one low-RTT commit, which is the property a config plane actually needs.
- **The "100× regression on regional writes" assumes the writer is far from the root; that is a
  routing question, not a topology one.** Under the Quicksilver shape the *writer* (the
  control-plane operator / CI system pushing config) routes to the root region — the same place
  `adr-0015`'s GLOBAL writes already go. Configd's write population is operator/CI-driven config
  changes (Quicksilver's 2020 write rate was ~350/s, baseline §0.1 is 10k/s —
  `research-quicksilver.md:218-219, :237-241`), **not** latency-sensitive per-request writes from
  apac end-users. There is no "apac→apac REGIONAL write" hot path in a config control plane the
  way the adversary's framing implies; the data-plane apac traffic is **reads**, which are local
  and sub-ms in *both* designs. So the 440 ms figure applies to a write pattern this system is not
  built to serve — an operator/CI-driven config plane has a low-rate write population
  (Quicksilver's 2020 rate ~350/s; baseline 10k/s — `research-quicksilver.md:218-219, :237-241`),
  not a high-rate per-region application-write load. (§0.2 does not enumerate a "regional write
  store" non-goal; the point rests on the write-population profile, not a §0.2 fence.)
- **Decisive point: the adversary's own ~9 ms average is dominated by the in-region commit — i.e.
  by a *centralized* group.** Their REGIONAL tier *is* a Quicksilver-shaped centralized root, just
  one-per-region without a shared order — and, per their own #3(c) (`devils-advocate-case.md:136-138`),
  it is a **3-voter group in the writer's region**, not a single global root. So the real difference
  is **in-region authoritative order vs one global order**, not centralized-vs-distributed commit.
  That makes #3 the *mirror* of the residuals I concede elsewhere: the read/commit locality the
  adversary buys for regional writes is the same locality I give up — and book as a residual — for
  `GLOBAL` strong reads (#2: single-root RTT) and for data residency (#4). I am not silently
  contradicting #2/#4; I am making the same locality trade explicit and choosing one global order
  for the v0.1 config workload. The disagreement therefore reduces to "one global root with one
  order" (this ADR) vs "N regional roots with no shared order + a global root for 10%" (`adr-0015`),
  and the latter adds the PlacementDriver + closed-timestamp side-transport machinery
  (§Reasoning-4) to manage the fragmentation. For a 10k/s operator-driven write load, one ordered
  root is sufficient and far simpler.

**ACCEPTED TRADE-OFF (scoped): if a future workload genuinely has a high-rate, latency-sensitive,
*regionally-partitionable* write population that needs 2–5 ms commits AND tolerates a fragmented
version space, scope-aware multi-Raft is the right tool — and that is exactly the multi-Raft
sharding `adr-0023-multi-raft-sharding-deferred.md` already defers to v0.2. This ADR does not
foreclose it; it declines to adopt it for the v0.1 config-control-plane workload, where one
ordered root meets §0.1 and avoids the fragmentation.**

---

**4. Data residency: a single global write root is non-compliant by construction in jurisdictions that require in-region authoritative commit.**

*Their strongest form (`devils-advocate-case.md:147-177`):* GDPR/Schrems II, India DPDP/RBI,
PIPL/CSL, 152-FZ require certain data be authoritatively committed within the jurisdiction.
Config values can carry tenant IDs, routing keyed to personal data, regional pricing/eligibility.
A single root commits every write — including a `REGIONAL` EU key — in us-east-1, durably writing
EU data **outside the EU on the hot path**: a per-write compliance violation. The fallback ("N
independent clusters with app-layer routing", `adr-0023:48-50`, `adr-0024:37-38`) re-creates the
ZK-shaped external coordination `PROMPT.md §5 rule 5` forbids and destroys shared ordering.
Regional Raft groups give authoritative in-region commit while still participating globally via
non-voting replication.

*Rebuttal — NOT rebutted. Explicitly DEFERRED with residual stated (Amendment A3).*

- This is a genuine constraint that the Quicksilver shape does **not** solve, and I do not claim
  otherwise. The directive given for this ADR is to "solve residency or explicitly defer it with
  the residual stated"; §0.2 (`PROMPT.md:31-41`) does **not** enumerate residency as a non-goal,
  so I am **deferring** it (Amendment A3), not claiming §0.2 scoped it out.
- **The adversary's strongest sub-claim — that regional Raft groups cleanly solve residency — is
  itself only partly true and worth naming:** an EU regional group keeps EU `REGIONAL` keys in the
  EU, but the **`GLOBAL`** subset (the 10% they rely on elsewhere) still commits in the core
  region, *and* the EU group's participation "in the global plane via non-voting replication"
  (`devils-advocate-case.md:172-174`) means GLOBAL data still crosses jurisdictions. So
  multi-region Raft solves residency for the *regional* subset only — it does not give a clean
  global answer either; it relocates the residual to the GLOBAL keys.
- **Residual for this ADR:** a single-root deployment is non-compliant for hard-localization data
  classes. The available path is N in-jurisdiction clusters + app-layer routing (the same
  documented v0.1 fallback, `adr-0023:48-50`, `adr-0024:37-38`), which loses cross-cluster shared
  ordering. The clean answer — per-jurisdiction roots bridged by an async, separately-specified
  consistency model — is exactly the v0.2 cross-DC bridge `adr-0024` defers. Until that ships,
  **Configd MUST NOT be deployed as a single global root for data under hard residency mandates.**

**RESIDUAL RISK: data residency unsolved by this topology; DEFERRED with residual stated (A3) —
not declared out of scope by §0.2, which does not enumerate residency. Mitigation: N
in-jurisdiction clusters + app-layer routing (loses shared ordering) until the v0.2 cross-DC
bridge (`adr-0024`) provides per-jurisdiction roots with a defined bridge consistency model.**

---

**5. Single-group failover is manual, split-brain-prone, and data-loss-windowed; Raft cross-region election is automatic and provably safe.**

*Their strongest form (`devils-advocate-case.md:181-217`):* with all voters in one failure domain,
losing the domain loses the quorum — you cannot Raft-elect out; recovery is manual standby-cluster
promotion (`adr-0024:30-33`). That is the textbook split-brain setup: if the "failed" root is
merely partitioned (gray failure) and a human promotes the standby, two clusters each believe they
are authoritative with divergent committed histories and **no Raft term to fence the stale
leader**; reconciliation = manual conflict resolution / data loss, and the bridge that would even
*define* merge semantics is deferred and unbuilt (`adr-0024:18, :28-29, :56`). Async standby has
lag δ, so a hard loss loses the last δ of *acknowledged* writes — a durability lie for a plane
that told the client "200 OK, version=N committed." Cross-region Raft makes failover automatic
(one timeout), split-brain-free (majority + higher term + CheckQuorum/PreVote — the project's own
TLA+-green Leader Completeness, `STATE-OF-REALITY §3`), and zero acknowledged-write-loss.

*Rebuttal — split-brain and acked-loss are addressed by A2 + a fencing rule for the AZ case;
the full-region manual-cutover residual stands (and overlaps point 1).*

- **A2 removes the "single failure domain" premise for the common case.** With voters across ≥ 3
  AZs/DCs, an AZ loss is survived by **automatic Raft election** — the same automatic, fenced,
  loss-free failover the adversary credits to cross-region Raft, because it *is* Raft, just
  intra-region. Leader Completeness and CheckQuorum/PreVote (`docs/architecture.md:211-219`; the
  TLA+-green `ConsensusSpec`, `STATE-OF-REALITY §3`) apply unchanged. There is **no split-brain
  and no acked-write loss** for AZ loss, because no manual standby is involved.
- **The split-brain risk is real only on the manual full-region cutover path, and is mitigated by
  fencing discipline, not eliminated.** When a full-region cutover *is* required, the runbook MUST
  fence the old root before promoting the standby (e.g. revoke its cluster identity / refuse its
  writes at the distribution tier), and the standby MUST refuse to accept writes until fencing is
  confirmed — i.e. **fail-closed** rather than dual-write. This converts "split-brain" into
  "unavailable until fenced," trading the adversary's catastrophe (divergent histories) for
  bounded downtime (which point 1 already counts against the SLO).
- **Acknowledged-write durability is preserved by *acking only on root-quorum durability*, not on
  standby replication.** The δ-loss the adversary describes exists only if the system acks before
  the standby has the write. Configd acks after the **root group's** majority fsync (Raft's normal
  rule, `RaftNode.java` quorum commit at `:1319` per `STATE-OF-REALITY:58`); the async standby is
  a *recovery* mechanism, and on a full-region loss the **unreplicated tail is lost** — this is the
  honest residual, identical in kind to any async-DR system and the same one `adr-0024` already
  carries.

**RESIDUAL: on a full-region loss requiring manual cutover, (a) downtime counts against the SLO
(point 1) and (b) the async-unreplicated write tail δ is lost (ACCEPTED TRADE-OFF). Mitigations:
A2 makes AZ loss automatic/fenced/loss-free; the full-region cutover runbook MUST fence-before-
promote and fail-closed to prevent split-brain; sub-second region failover + a defined bridge
merge semantics are DEFERRED to `adr-0024` v0.2.**

---

**6. Operational blast radius & the "looks done" trap: centralizing on the single group the skeleton already has ratifies the system's biggest known weakness; capacity ceiling is a wall.**

*Their strongest form (`devils-advocate-case.md:221-258`):* one write group = one
correlated-failure surface for 100% of writes, no bulkhead — a slow/poisoned tenant stalls
everyone (`adr-0023:14-17` admits this and uses it to justify *future* sharding, so the team
already agrees single-group is a reliability risk). The known thread-unsafe
`RaftNode`/`ConfigStateMachine` race lives *specifically in multi-node mode* (`STATE-OF-REALITY
§5.1 🔴`). Adopting the shape because the skeleton matches is the institutional "looks done" trap
(`STATE-OF-REALITY §5` preamble: a verified component wired unsafely is more dangerous than an
absent one). And the capacity ceiling is a **wall**: `adr-0023:24, :41` pins single-Raft at
**~10k commits/s** — exactly the §0.1 *baseline* with **zero** headroom for the **100k/s burst**;
the design is "sized to fail the burst SLO on day one" with sharding deferred.

*Rebuttal — the framing-trap is conceded (handled above); the burst-ceiling is an ACCEPTED
TRADE-OFF that reconciles with `adr-0023`; the race is an integration bug, not a topology
argument.*

- **The "looks done" trap is real and accepted** — addressed in the framing rebuttal: this
  decision is justified by §0.1 arithmetic, not by code shape. Adopting this ADR does **not** mean
  marking the topology "done"; the fan-out half is explicitly unwired (Consequences) and the
  consistency contract is explicitly unproven (point 7).
- **The 100k/s burst ceiling is an ACCEPTED TRADE-OFF, and the resolution is multi-Raft sharding —
  which is already deferred, not denied.** `adr-0023` defers multi-Raft to v0.2 and documents the
  ≤ 10k commits/s ceiling as a hard cap (`adr-0023:24, :41, :62`). This ADR is **consistent**
  with that: it adopts a *single ordered root* for v0.1's 10k/s baseline and **does not claim** the
  100k/s burst is met by one group. **Crucially, multi-Raft sharding (independent throughput lanes
  + bulkheads, the adversary's point) is fully compatible with the Quicksilver shape** — each shard
  is a centralized root that fans out; sharding partitions the *root*, it does not require WAN
  write consensus. So the burst headroom and the per-tenant bulkhead the adversary wants are
  obtained by `adr-0023`'s deferred multi-Raft, **not** by the cross-region/hierarchical write
  consensus this ADR rejects. The adversary conflates "more Raft groups for throughput/isolation"
  (compatible, deferred) with "Raft groups stretched across regions for write availability"
  (rejected on §C latency).
- **The thread-unsafe `RaftNode`/`ConfigStateMachine` race (`STATE-OF-REALITY §5.1`) is an
  integration defect, not evidence for either topology.** It is the inbound-message path
  (`ConfigdServer.java:257` routeMessage on per-connection virtual threads) running unsynchronized
  against the single tick thread (`:394` `configd-tick`). It must be fixed (serialize inbound onto
  the tick executor, per `STATE-OF-REALITY §6.1`) **regardless** of which topology wins — and it is
  strictly *worse* under the adversary's multi-node cross-region design, which exercises exactly
  the multi-node path where the race is live. It is therefore not an argument *for* multi-region
  Raft.

**ACCEPTED TRADE-OFF: one root meets the 10k/s baseline but NOT the 100k/s burst; burst headroom +
per-tenant bulkheads are obtained via multi-Raft sharding DEFERRED to v0.2 (`adr-0023`), which is
Quicksilver-shape-compatible (partition the root, not the regions). Single-group HOL-blocking is a
RESIDUAL until sharding ships.**

---

**7. The bounded-staleness contract is unfalsified vapor today, so "Quicksilver-shaped is simpler/proven" is unearned; the verified-real part is the write consensus, which multi-region Raft would extend.**

*Their strongest form (`devils-advocate-case.md:262-286`):* the async-fan-out half does not run —
`FanOutBuffer.append` (`ConfigdServer.java:301`) has no draining reader in `src/main`, and
`PlumtreeNode.broadcast()` is benchmark-only (`STATE-OF-REALITY §4.2, §5.3`). So **every** edge
guarantee the contract leans on (500 ms staleness bound, monotonic-read-on-failover, RYW) rides on
a pipeline that has never moved a real delta end-to-end; the property tests that would prove the
bound don't exist / lack a history checker (`STATE-OF-REALITY §4.4, §5.4`); the perf scorecard is
self-labeled "MODELED, NOT MEASURED" (`STATE-OF-REALITY §4.6`). Meanwhile the *write-consensus*
half **is** genuinely real and TLA+-verified (`ConsensusSpec` green, 13.7M states,
`STATE-OF-REALITY §3`). So the honest comparison is: extend the verified-real consensus core
across regions (building on solid ground) vs. bet the whole consistency story on an unrun fan-out.

*Rebuttal — the unwired/unmeasured state is fully conceded as a RESIDUAL; the inference "therefore
extend write consensus across regions" does not follow.*

- **Conceded, on the record (`file:line` verified in this repo).** `fanOutBuffer.append(delta)` at
  `ConfigdServer.java:301` has no `src/main` caller of `deltasSince`/`latest`
  (`FanOutBuffer.java:43, :56`; grep confirms only ConfigdServer references the buffer, and only to
  append). The fan-out path is unwired; the 500 ms propagation p99, monotonic-read-on-failover, and
  RYW guarantees in `consistency-contract.md` are **modeled, not measured end-to-end**. The
  `< 500 ms` propagation number in §Reasoning-3 is marked `[UNVERIFIED for Quicksilver]` and
  **unmeasured for Configd**. This ADR does **not** claim the contract is proven.
- **But "the fan-out is unbuilt" is an argument to *build and measure the fan-out*, not to *adopt
  cross-region write consensus*.** The two halves are orthogonal: wiring `FanOutBuffer` →
  `PlumtreeNode.broadcast` → transport → edge with a real end-to-end propagation test and a real
  linearizability/staleness history checker (`STATE-OF-REALITY §6.5, §6.3`) is required under
  *this* topology, and is **independent of** where the write voters sit. Choosing multi-region Raft
  would not make the fan-out exist — Quicksilver-class and `adr-0015`-class designs *both* fan out
  to edges; the adversary's own design "still participates in the global plane via … replication"
  to remote regions and to edges, so it inherits the *same* unmeasured-propagation risk.
- **The adversary's "extend the verified-real core across regions" is the part the §C arithmetic
  refutes (point 3 / §Reasoning-1):** the core is verified as a *single group* (`STATE-OF-REALITY
  §3` is `ConsensusSpec` for one group; multi-region/hierarchical Raft is `[DOC-ONLY]/[ABSENT]`,
  `STATE-OF-REALITY §4.1`). "Extending it across regions" is **new, unbuilt, unverified work** that
  also pays the ~288 ms cross-region floor — so it is neither "building on solid ground" nor free.
  Keeping the verified core as a centralized root and *additionally* building+measuring the fan-out
  is the lower-risk path. The "simpler/proven" claim for *fan-out* is unearned and I withdraw it;
  the "simpler/cheaper" claim for the *write topology* (one ordered root vs PlacementDriver +
  closed-timestamp side-transport) stands on §Reasoning-4.

**RESIDUAL RISK: the async fan-out and the entire bounded-staleness contract are UNWIRED and
UNMEASURED end-to-end in this repo today (verified: `ConfigdServer.java:301` append with no drain).
Adopting this ADR is conditional on building+measuring that path (wire fan-out drain → broadcast →
edge; add a real staleness/propagation probe and a linearizability history checker) before any
"meets §0.1 propagation" or "contract verified" claim is made. Until measured, all propagation and
edge-consistency numbers in this ADR are MODELED.**

---

### Other rejected alternatives (from the research, for completeness)

- **Global single Raft group spanning regions:** ~288 ms end-to-end write floor for an APAC client
  vs the 150 ms target (`research-quicksilver.md:434`); adding voters worsens it (etcd FAQ,
  `research-quicksilver.md:261-263`). Rejected on latency.
- **Hierarchical Raft (global group + regional groups) — `adr-0015`'s design:** the *global* group
  still pays the cross-region floor for any GLOBAL-scope write from a distant client; adds
  PlacementDriver + closed-timestamp side-transport complexity (§Reasoning-4) that loses §0.3's
  operational-complexity axis; cross-group ordering is disclaimed (`consistency-contract.md:145`).
  Never implemented (`STATE-OF-REALITY:93-97`). Its read-locality concept is borrowed (see
  Influenced by); its write topology is rejected on latency + complexity. *Its per-region
  throughput/isolation benefit is re-obtained via `adr-0023` multi-Raft sharding without WAN write
  consensus (point 6).*
- **Leaderless / EPaxos-style global writes:** fast path still needs a fast cross-region quorum;
  degrades to 2 RTT under conflicts; dependency-graph commit is unjustified for a low-frequency
  config write load (Quicksilver's 2020 rate ~350/s; baseline 10k/s —
  `research-quicksilver.md:218-219`). Rejected: complexity for no latency gain on this workload.
- **CockroachDB/Spanner in-Raft non-voting replicas for cross-region reads:** solves the read
  problem but keeps the cross-region *write* quorum; read-locality is obtained more cheaply via
  async edge copies. Rejected for the write path; its closed-timestamp read *concept* is borrowed.

## Consequences

### Positive

- **Meets §0.1 write p99 (modeled):** commits intra-cluster in one low-RTT region; no APAC writer
  pays the ~288 ms cross-region floor (`research-quicksilver.md:434`).
- **Meets §0.1 edge read p99 < 1 ms / p999 < 5 ms (read path verified real):** local
  lock-free MVCC serving with no consensus (`VersionedConfigStore.java:42, :189`;
  `STATE-OF-REALITY:60`), matching Quicksilver's published "90% < 1 ms" (`research-quicksilver.md:221`).
- **Wins §0.3 operational complexity:** one root + fan-out tree vs PlacementDriver +
  closed-timestamp side-transport (§Reasoning-4); "zero external coordination" (`PROMPT.md:54`).
- **One global total order** for the whole keyspace (the property a config control plane needs,
  `adr-0019-consistency-model.md:83`), without the fragmented per-region sequence spaces of
  scope-aware placement (`consistency-contract.md:145-149`).
- **AZ-loss survival is automatic and loss-free** via A2 (Raft election across AZs).
- **Existence proof at scale:** Cloudflare runs this shape at 330 cities / 3 B reads/s
  (`research-quicksilver.md:243-249`).

### Negative

- **Write availability under FULL-region loss is not five-nines** (point 1/5 RESIDUAL): requires
  manual standby cutover; sub-second region failover deferred to `adr-0024` v0.2.
- **`GLOBAL`/security-key strong reads are high-latency** (single-root RTT, modeled ~220 ms
  worst-case APAC) and unavailable during root-region loss; enforcement must fail-closed (point 2).
- **Data residency unsolved** (point 4 RESIDUAL): single-root deployment non-compliant for
  hard-localization data classes; deferred with residual stated (A3) — not scoped out by §0.2.
- **100k/s burst not met by one group** (point 6): obtained via deferred multi-Raft sharding
  (`adr-0023`); single-group head-of-line blocking is a residual until then.
- **The async fan-out and the entire bounded-staleness contract are UNWIRED/UNMEASURED today**
  (point 7 RESIDUAL): `ConfigdServer.java:301` appends with no drain. All propagation/edge
  numbers here are MODELED until that path is built and probed.

### Risks and mitigations

- **Unwired fan-out (highest):** mitigation — wire `FanOutBuffer` drain → `PlumtreeNode.broadcast`
  → transport → edge; add an end-to-end propagation probe and a real linearizability/staleness
  history checker (`STATE-OF-REALITY §6.5, §6.3`) **before** claiming §0.1 propagation is met.
- **Thread-unsafe `RaftNode`/`ConfigStateMachine` integration race** (`STATE-OF-REALITY §5.1`,
  `ConfigdServer.java:257` vs `:394`): mitigation — serialize inbound `routeMessage` onto the tick
  executor (`STATE-OF-REALITY §6.1`); required under any topology.
- **No runtime invariant enforcement in production** (`ConfigdServer.java:248` NOOP;
  `STATE-OF-REALITY §5.2`): mitigation — wire a real `InvariantChecker` before GA.
- **Root-region full loss / split-brain on manual cutover:** mitigation — A2 (AZ spread) +
  fence-before-promote + fail-closed standby (point 5); defer sub-second failover to `adr-0024`.
- **Security-key staleness:** mitigation — A1 strong-read class + fail-closed enforcement (point 2).

### SLO impact — §0.1 write-availability (KNOWN VIOLATION under full-region loss)

- **The single-region root, even with A2's multi-AZ spread, does NOT meet §0.1's 99.999%
  write-availability target under FULL-region loss.** Recovery is **manual standby cutover**
  (sub-second automatic region failover is deferred to the `adr-0024` v0.2 cross-DC bridge), and a
  single multi-minute-RTO event breaches the **5.26 min/yr** five-nines budget. A2 **DOES** meet
  the 99.999% target for **single-AZ loss** — that failover is automatic, fenced, and loss-free
  (Raft election across AZs).
- **Edge READS (§0.1's 99.9999% target) are UNAFFECTED by root-region loss** — they are served
  from local edge copies that do not depend on root liveness.
- **§0.1 (`PROMPT.md:27`) requires an ADR to change a target.** This is therefore recorded as a
  **KNOWN ACCEPTED VIOLATION**: the underlying residual is signed off by the three teammates (see
  Reviewers — #1/#5 full-region availability), and **formal target renegotiation is raised as
  `adr-0031-renegotiate-write-availability-target.md`** (stub, Proposed). **HUMAN RATIFICATION
  PENDING** — this branch stops for review before merge; the violation is not ratified by this ADR
  alone.

### Normative invariants (MUST) — Phase B obligations

These promote two concessions from prose (Rejected Alternatives #2 and #4) into explicit MUST
requirements with named Phase B verification obligations.

- **INV-1 (security/`GLOBAL` keys, from Rejected Alternative #2):** Enforcement points **MUST fail
  CLOSED (deny)** for a `GLOBAL`/security key when the linearizable root read path is unavailable;
  they **MUST NOT** fall back to a bounded-stale edge copy. **Phase B test obligation:** this MUST
  become a **testable entry in `consistency-contract.md`** (fail-closed on root-unreachable for
  `GLOBAL` keys) with a corresponding property test.
- **INV-2 (data residency, from Rejected Alternative #4):** Configd **MUST NOT** be deployed as a
  single global root for data classes under hard in-jurisdiction residency mandates until
  per-jurisdiction roots + a defined bridge (`adr-0024` v0.2) exist. **Phase B obligation:** this
  MUST be enforced by a **deploy-time guardrail** (deployment-manifest / admission check), not by
  documentation alone.

Both INV-1 and INV-2 are recorded as **Phase B test/enforcement obligations** and are
preconditions for any GA claim against the residuals they formalize.

### Relationships to other ADRs

- **Supersedes** the write-topology of `adr-0015-multi-region-topology.md` (global 5-voter
  cross-region group, regional write groups, closed-timestamp side-transport). `adr-0015`'s
  *read-locality concept* (follower reads via a safe timestamp) is retained in spirit but delivered
  via async edge copies, not in-Raft non-voting replicas. `adr-0015` should be marked
  `Superseded by ADR-0030` for its write topology on acceptance.
- **Reconciles with `adr-0023-multi-raft-sharding-deferred.md`:** consistent — v0.1 is one ordered
  root for the 10k/s baseline; the 100k/s burst + per-tenant bulkheads come from `adr-0023`'s
  deferred multi-Raft sharding, which partitions the *root* and is fully Quicksilver-shape
  compatible (no WAN write consensus). No conflict.
- **Reconciles with `adr-0024-cross-dc-bridge-deferred.md`:** consistent — `adr-0024` already
  rejects WAN-stretched Raft on the SLO (`adr-0024:23-25`) and defers per-DC roots + async bridge
  to v0.2. This ADR adopts the single-DC/single-region root `adr-0024` fixes for v0.1, and points
  the residency (A3) and full-region-failover (point 1/5) residuals at `adr-0024`'s v0.2 bridge.
- **Consistent with `adr-0019-consistency-model.md` and `consistency-contract.md`:** unchanged —
  linearizable writes at the root, bounded-stale edge reads, with A1 adding a strong-read GLOBAL
  key class (a read-path refinement, not a contract change to write linearizability).
- **Triggers `adr-0031-renegotiate-write-availability-target.md`** (stub, Proposed): §0.1 requires
  an ADR to change a target, and this ADR's single-region root is a KNOWN VIOLATION of the 99.999%
  write-availability target under full-region loss (see SLO impact). ADR-0031 enumerates the
  renegotiation options (keep 99.999% + sub-second failover via `adr-0024` v0.2; tiered SLO; or
  explicit risk-acceptance) without deciding.

## Reviewers

- prior-art-researcher: SIGN-OFF — latency math + citations faithful; era attributions correct; nothing [UNVERIFIED] promoted to fact. (Non-blocking: Spanner ~7 ms is secondary-sourced.)
- devils-advocate: SIGN-OFF (after change #1) — rejected-alternative analysis engages each argument at its strongest; residuals (#1/#5 full-region availability, #2 GLOBAL strong-read cost, #4 residency, #6 burst/HOL, #7 unwired fan-out) are honest, not decorative; A1/A2/A3 do not reintroduce a cross-region write quorum.
- topology-architect: SIGN-OFF (author).
