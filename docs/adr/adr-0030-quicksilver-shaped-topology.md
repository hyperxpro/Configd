# ADR-0030: Centralized Strongly-Consistent Writes with Asynchronous Bounded-Staleness Edge Fan-out (Quicksilver-Shaped Topology); Reject Global Multi-Region / Hierarchical Raft Write Consensus

## Status

Accepted (2026-06-27). Was proposed (under review) at authoring (2026-06-06).

> **Reality-update note (2026-06-27).** The core decision stands: a single, region-local,
> strongly-consistent Raft root for writes plus asynchronous bounded-staleness edge fan-out, rejecting
> global multi-region / hierarchical Raft write consensus (the Reasoning section's latency arithmetic is
> unchanged). The Context, Reasoning, and Consequences sections below describe the repository as it was
> at authoring (2026-06-06); the following has changed since and is reconciled here (the inline
> `file:line` references in those sections are therefore historical):
>
> - **"Registers exactly one Raft group" (Context) is now historical.** The multi-Raft sharding that
>   `adr-0023-multi-raft-sharding-deferred.md` once deferred has since been built and is wired into the
>   production server. Sharding partitions the region-local root by hash-within-scope; it does not
>   introduce WAN / multi-region write consensus, so it is exactly the "partition the root, not the
>   regions" path this ADR endorsed in Rejected Alternative 6, and is consistent with the core decision.
>   The default is a single Raft group (N=1, byte-identical to a non-sharded build); an operator turns on
>   multiple shards for horizontal scale. Aggregate throughput across shards has since been measured and
>   scales near-linearly across machines (about 2.45x on 3 machines) -
>   see `docs/archive/measurement/ec2-horizontal-2026-07-01/02-scaling-curve.md`.
> - **The async fan-out is now wired (the edge data plane).** The Rejected Alternative 7 / Consequences
>   residual describing the fan-out as unwired is closed for wiring (`FanOutServer`, the
>   commit-notification stream, and the edge plane are all built and gated). End-to-end
>   propagation/staleness has since been measured at the scale run to date (see
>   `docs/measurement/ec2-drive-to-green-2026-07-02/gate7-final/`); the higher scales this ADR modeled
>   remain untested - that portion of the residual stands.
> - **The thread-unsafe `RaftNode`/`ConfigStateMachine` integration race (Rejected Alternative 6 / Risks)
>   is fixed** by re-threading onto one owner thread per group (the `apply_owner_thread` tripwire,
>   `consistency-contract.md` section 8). The "no runtime invariant enforcement in production" risk is
>   likewise closed - `InvariantMonitor` runs in production (`testMode=false` emits a metric and a log
>   line; `consistency-contract.md` section 8).
> - **The write-availability renegotiation is resolved:** `adr-0031` is now Accepted, keeping the 99.999%
>   write-availability target unchanged rather than weakening it. The single-region-root
>   full-region-loss shortfall remains an accepted, documented limitation: recovery is a manual standby
>   cutover, and closing it would require the cross-DC bridge described in `adr-0024`, which is not
>   built.
> - **Naming honesty.** Amendment A1 / INV-1's `GLOBAL`/security key class (default prefix `secure/`) is
>   a read-freshness guarantee (always-linearizable, fail-closed, never served stale) for
>   security-critical decisions - it is not at-rest confidentiality/encryption, and the two are
>   orthogonal. At-rest encryption is now available (opt-in AES-256-GCM, off by default); with it off the
>   default posture is plaintext-with-integrity, so `secure/` still does not imply confidentiality. See
>   `docs/operations/known-limitations.md` and `docs/operations/consistency-contract.md` section 9.
>
> **Open residuals (unchanged, honest):** full-region write availability is not five-nines (Amendment A2
> covers single-AZ loss automatically; full-region failover would require the cross-DC bridge in
> `adr-0024`, which is not built); data residency remains deferred with the residual stated (Amendment
> A3); end-to-end propagation/staleness numbers beyond the scale measured to date are modeled, not
> measured; INV-1 (GLOBAL fail-closed) and INV-2 (residency deploy-time guardrail) remain outstanding
> obligations.

Supersedes the *write-topology* portion of `adr-0015-multi-region-topology.md`
(the global 5-voter cross-region Raft group + regional groups + closed-timestamp
follower-read design). Reconciles with - and does not contradict -
`adr-0023-multi-raft-sharding-deferred.md` and `adr-0024-cross-dc-bridge-deferred.md`,
which already concede the system is single-Raft / single-DC and that "WAN-stretched Raft
violates the SLO" (`adr-0024-cross-dc-bridge-deferred.md:23-25`). Aligned with the
split consistency model of `adr-0019-consistency-model.md` (which this ADR does not
change). See **Consequences -> Relationships** for the precise reconciliation.

## Context

The target contract for any topology (as authored) was:

- Write commit latency p99 under 150 ms cross-region
- Propagation / edge visibility p99 under 500 ms global
- Edge read p99 under 1 ms (in-process), p999 under 5 ms
- Availability 99.999% control-plane writes, 99.9999% edge reads
- 10k writes/s baseline, 100k/s burst; 10^6 keys baseline, 10^9 ceiling; 10k edges
  baseline, 1M ceiling.

At authoring, the repository registered exactly **one** Raft group:
`ConfigdServer.java:82` declared `DEFAULT_RAFT_GROUP = 0`, `:252` was the only
`driver.addGroup(...)` call, and every proposal routed to it (`:369`). There was no
`region`, `non-voting`, or `closedTimestamp` code in `src/main`
(confirmed by code inspection). The async fan-out half was unwired:
`fanOutBuffer.append(delta)` at `ConfigdServer.java:301` had no draining reader in
`src/main` (the `FanOutBuffer.deltasSince`/`latest` readers existed but were called by
nothing outside the class - verified by grep), and `PlumtreeNode.broadcast()` was invoked only
in a benchmark.

So the decision was genuinely open: do we (a) **build outward** into the global
multi-region / hierarchical-Raft *write* consensus that `adr-0015` describes, or (b) **adopt
the Quicksilver shape** - one centralized strongly-consistent write group, asynchronous
fan-out to eventually-consistent edges, under a bounded-staleness contract - and reject WAN
write consensus. This ADR chooses (b), with two scoped amendments forced by honest engagement
with the strongest counterarguments (a GLOBAL/security strong-read key class, and a root group
spanning low-RTT AZs/DCs for automatic survival), and explicitly defers data residency with the
residual stated.

This fences the design: Configd is not a general KV store, not a multi-key transactional
store, not a secrets manager, not a schema registry, not a pub/sub bus. That fence is
load-bearing below - several of the strongest "keep multi-region write consensus" arguments
assume transactional or residency semantics that are out of scope here.

## Decision

Adopt a **Quicksilver-shaped topology**:

1. **Writes: one centralized, strongly-consistent Raft group ("the root").** All linearizable
   writes commit in a single Raft group whose voters are co-located in **one low-RTT failure
   cluster** (one region, voters spread across 3+ AZs / nearby DCs - see amendment A2). The
   root is the only consensus participant. No write quorum is stretched across APAC<->US<->EU.
   This is exactly Cloudflare's "centralized root cluster... built on the etcd Raft package".

2. **Distribution: asynchronous hierarchical fan-out to eventually-consistent edges.** Committed
   deltas are pushed (Plumtree-over-HyParView per `adr-0011-fan-out-topology.md`) down a
   distribution tree to edges that hold a **local copy** and serve reads with **no consensus
   participation**. Edge reads are sequentially-consistent and bounded-stale, never linearizable
   (matching `adr-0019-consistency-model.md` and `consistency-contract.md`). Gap detection via
   the gap-free monotonic sequence number (`consistency-contract.md:126-133`).

3. **Bounded-staleness contract.** The edge promise is the contract already written:
   p99 < 500 ms, p999 < 1 s, p9999 < 2 s, with stale/degraded/disconnected degradation
   (`consistency-contract.md:38-57`). This was a target, not yet a measured property, at
   authoring - see Consequences.

4. **Reject global multi-region / hierarchical Raft write consensus.** No 5-voter group
   spanning core regions, no per-region write groups with cross-group ordering, no
   closed-timestamp side-transport on the write path. The cross-region read-locality benefit
   is obtained via async edge copies, not via in-Raft non-voting replicas.

### Amendment A1 - GLOBAL / security strong-read key class (responds to Rejected Alternative 2)

A declared `GLOBAL` key class (security kill-switches, ACL/auth revocations, legal gates)
is exempt from the bounded-staleness read contract. Reads of a `GLOBAL` key must use the
linearizable path - control-plane ReadIndex against the root
(`consistency-contract.md:16-20`, `adr-0019-consistency-model.md:59-65`) - and enforcement
points (auth services, API gateways) must issue a strong read on the security decision rather
than trusting an edge copy. Bounded-stale edge serving of `GLOBAL` keys is a contract
violation, not a tuning choice. This is a read-path amendment only; it does not
reintroduce a cross-region write quorum. Residual cost is stated in Consequences.

### Amendment A2 - root group spans multiple low-RTT AZs/DCs for automatic survival (responds to Rejected Alternatives 1 and 5)

The root is not a single-AZ, single-failure-domain group. Its voters are placed across
at least 3 availability zones (or at least 3 sub-100 ms-RTT DCs) within one region, so the loss of any
single AZ/DC leaves a majority and triggers automatic Raft leader election (PreVote +
CheckQuorum, `docs/architecture.md:211-219`), with no human and no split-brain. This keeps the
commit floor low (intra-region AZ RTTs are single-digit ms, far under the write-latency budget) while
buying automatic survival of a single-AZ event. It does not survive a full-region loss
without manual standby cutover - that residual is stated explicitly in Consequences and in
the rebuttal to Rejected Alternatives 1 and 5.

### Amendment A3 - data residency explicitly deferred with residual stated (responds to Rejected Alternative 4)

In-jurisdiction authoritative commit (GDPR/Schrems II, DPDP/RBI, PIPL/CSL, 152-FZ) is not
solved by this topology and is explicitly deferred, with the residual stated. The
residual: a single-root deployment commits all writes in one jurisdiction, which is
non-compliant for data classes under hard localization mandates. The available path is
the same one `adr-0023-multi-raft-sharding-deferred.md:48-50` and
`adr-0024-cross-dc-bridge-deferred.md:37-38` already document - N independent in-jurisdiction
clusters with application-layer routing - at the cost of cross-cluster shared ordering. This
is a residual risk, not rebutted, discussed further below.

## Influenced by

- **Cloudflare Quicksilver (primary baseline).** Borrowed mechanism:
  *consensus for the source of truth, coordination for distribution.* A single centralized
  Raft root built on "the etcd Raft package" for durable ordered writes, then asynchronous
  hierarchical fan-out to every data center where edges serve sequentially-consistent local
  reads with **zero edge consensus**. Cloudflare distributes
  a config change "to 200 cities in 90 countries... within seconds" (2020) and at v2 serves "over
  three billion keys per second" at "90% of requests in less than 1 ms" (2025).
  Also borrowed: **monotonic sequence numbers**
  for gap detection ("exactly one higher than the last message we have seen"),
  **MVCC + sliding window** for sequential consistency under
  tiered caching, and a **30-second disconnect / catch-up
  threshold** for slow consumers (already in
  `consistency-contract.md:57`). Sources: *Introducing Quicksilver* (2020), *Moving Quicksilver
  into production* (2020), *Quicksilver v2 Parts 1 & 2* (2025).

- **etcd / single-group Raft.** Borrowed
  the centralized single-group Raft engine (the same engine Quicksilver's root uses) - and
  borrowed it as a **constraint**: etcd's own guidance that a cluster "probably should have no
  more than seven nodes" because "the write performance suffers because data must be replicated
  across more machines," and that cross-DC consensus latency is "pronounced," is precisely why
  the voting set is kept small and region-local rather than WAN-stretched (etcd FAQ).

- **CockroachDB / Spanner closed-timestamp & safe-timestamp follower reads.** Borrowed the *concept* (a safe timestamp lets a
  follower serve bounded-staleness reads locally) but **rejected the in-Raft delivery**
  (cross-region voting/non-voting replicas). The read-locality benefit is obtained via
  Quicksilver-style async edge copies instead, avoiding the cross-region write quorum entirely.
  Sources: Cockroach Labs *optimize write latency for global tables* (2022); Google Cloud
  *Spanner: TrueTime and external consistency*.

## Reasoning

All external numbers below are drawn from published primary sources (Cloudflare engineering posts, etcd documentation, CockroachDB/Spanner papers). All repo numbers carry `file:line`. Numbers that could not be measured in this repo at authoring are labeled modeled or unmeasured.

### 1. Cross-region write consensus cannot meet the 150 ms cross-region write p99 budget for a non-co-located client.

A Raft write commits when a majority including the leader has durably appended it; the
network-only commit floor is the RTT to the `floor(N/2)`-th nearest voter (Ongaro & Ousterhout;
arXiv 1902.02537). Using measured AWS inter-region RTTs:

- A 5-voter global group with leader in us-east-1 has a network-only commit floor of
  ~68.5 ms (RTT to the 2nd-nearest voter, eu-west-1) - this matches the "~68 ms" `adr-0015`
  assumes (`adr-0015-multi-region-topology.md:32`).
- But the budget is cross-region, i.e. it must hold for a writer not next to the leader.
  End-to-end = `RTT(client<->leader) + commit_floor`. For an ap-southeast-1 client to a us-east
  group: 219.64 + 68.55 ~ 288 ms at the floor - about 1.9x over the 150 ms budget, before
  fsync (etcd targets each node's fdatasync under 10 ms), batching, and queueing.
  ap-northeast-1 ~ 217.7 ms (about 1.45x over).

This is physics, not an implementation defect: CockroachDB independently reports `GLOBAL`-table
writes "as high as 800 ms... [optimizable to] 250 ms or less";
Spanner pays ~7 ms commit-wait for clock uncertainty
*alone* on top of cross-region Paxos (secondary; ~7 ms per Spanner OSDI 2012).
Re-placing the leader does not fix it - it moves the penalty to a different region's clients.

### 2. A single Raft group does not scale write throughput and is *harmed* by WAN spread.

Official etcd guidance: "the write performance suffers because data must be replicated across
more machines"; adding voters raises the quorum size and
fsync count without adding write capacity. Therefore the
only correct use of one group is a **small, region-local, centralized root** - exactly the
Quicksilver shape - not a global write fabric. Spreading voters "for survivability" actively
pushes the commit floor up: a 7-voter group whose 3rd-nearest voter sits in ap-northeast-1 has
a **~149 ms commit floor**.

### 3. The Quicksilver shape hits all three latency budgets.

- **Write p99 under 150 ms:** commits occur inside one low-RTT cluster (intra-region AZ RTTs are
  single-digit ms per `docs/architecture.md:435-436` showing 20 ms for EU-regional and 69 ms
  AP-regional intra-pairs; AZ-internal is lower still). The client routes to the root region.
  Quicksilver's root adds a 500 ms batch window by design,
  but the commit itself is intra-cluster; Configd can
  choose a far smaller micro-batch. Modeled at authoring.
- **Propagation p99 under 500 ms global:** asynchronous fan-out. Quicksilver publishes the
  qualitative "within seconds" plus a 30-second disconnect threshold and did not publish a
  hard sub-second propagation p99 - Configd's under-500-ms p99 was, at authoring, a target
  marked unverified for Quicksilver in the research.
  The fan-out path was at that point unwired in this repo (`ConfigdServer.java:301` append with
  no drain), so the number was unmeasured end-to-end; see Consequences and the
  rebuttal to Rejected Alternative 7 (and the reality-update note above for the current, wired
  status).
- **Edge read p99 under 1 ms / p999 under 5 ms:** edges serve from a local copy with no consensus,
  matching Quicksilver's "90% of requests in less than 1 ms," "99.9% ... less than 7 ms".
  The repo's lock-free volatile-snapshot MVCC read
  path (`VersionedConfigStore.java:42, :189`) is the mechanism. A Raft follower read cannot match
  this: it needs ReadIndex/lease confirmation (at least intra-region RTT) or a closed-timestamp
  staleness window.

### 4. It also wins on operational complexity.

The rejected `adr-0015` design requires a PlacementDriver, scope-aware shard routing,
cross-region non-voting replicas, and a closed-timestamp side-transport at 200 ms cadence
(`adr-0015-multi-region-topology.md:41, :87`; `docs/architecture.md:190-197`) -
CockroachDB-class machinery. The Quicksilver shape needs one root group plus a fan-out tree
with monotonic sequence numbers for gap detection - zero external coordination, against a
goal of minimizing operational complexity.

## Rejected Alternatives

This section reproduces the strongest counterargument for each rejected alternative as a
numbered list, then rebuts each. Where a point cannot be fully defeated it is labeled a
residual risk or an accepted trade-off, with the mitigation and scope stated.

### Framing note (the "streetlight fallacy") - addressed first because it conditions every point.

**The argument:** the Quicksilver shape is being
"sold as matching reality" because the code today registers only one Raft group
(`ConfigdServer.java:82, :252`); that is an implementation gap, not an architecture
validation. Adopting the half-built topology is the streetlight fallacy - the single-group
reality is a reason to finish the multi-region build, not to ratify its absence.

**Response - conceded as a framing rule, rejected as a verdict driver.** This is
correct that "the code already does this" is not a valid justification, and this ADR does
not use it as one. The verdict in the Reasoning section rests entirely on (a) measured cross-region RTT
arithmetic against the write-latency budget, (b) etcd's own write-scaling constraint,
and (c) Cloudflare's published existence proof that the
shape meets the budgets at 330-city scale. If the code
registered five cross-region groups today, that arithmetic would still reject them. The
"matches reality" property is a consequence of the decision being cheap to reach from here,
not its cause. The streetlight rule is accepted here, and "it's already half-built" plays no
part in the justification.

---

**1. Region-loss write availability: single-group collapses globally on root-region loss; cross-region Raft survives by design - and the target is 99.999%.**

*Strongest form:* one group, one leader, one root
region. A correlated root-region event (AZ power/network, bad deploy, BGP/DNS withdrawal,
fat-fingered `kubectl` against the leader namespace - `adr-0024:56` pins the cluster to one
namespace) stops 100% of writes worldwide until a human rebuilds/fails over. The target demands
99.999%, or 5.26 min/year. `adr-0024:30-33` admits the DR story is a manual standby-cluster
cutover; a realistic manual cutover is minutes-to-tens-of-minutes (`adr-0015:50` even lists
"Minutes (manual)") - one such event per year blows the entire annual budget. A 5-voter
2+2+1 cross-region group survives any single region loss with automatic election in one
timeout (~150-300 ms, `RaftNode.java:1645`; documented RTO < 10 s, `adr-0015:49`).

*Response - partially rebutted via Amendment A2; full-region survival is an accepted
trade-off.*

- The premise that the root is necessarily a single failure domain is the part
  rejected here. Amendment A2 places the root's voters across at least 3 AZs (or at least 3
  sub-100 ms DCs) in one region, so a single-AZ event (the most common correlated event) leaves
  a majority and triggers automatic Raft election in the same ~150-300 ms timeout
  (`docs/architecture.md:211-219`; the same PreVote/CheckQuorum machinery). This
  recovers the automatic, fenced, loss-free failover for AZ loss without any cross-region
  write quorum and without the ~288 ms latency penalty from Reasoning point 1. Commit floor stays
  single-digit ms (intra-region AZ RTT), well under the write-latency budget.
- **What A2 does not buy - full-region loss - is an accepted trade-off.** A whole-region outage
  (submarine-cable cut to the region, region-wide control-plane failure) still requires manual
  standby cutover. The five-nines arithmetic above is correct for that event class: a
  single multi-minute manual cutover breaches the annual budget. Mitigation and scope:
  (i) full-region loss is rarer than AZ loss, which A2 now covers automatically; (ii) the
  five-nines target is for writes - edge reads continue from local copies during a root
  outage (the 99.9999% edge-read budget is unaffected, by construction); (iii) sub-second
  region-failover would require a cross-DC bridge (the one `adr-0024` describes and that is not
  built), and until then the write-availability target under full-region loss is not met by
  this topology - stated, not hidden. Note that the cross-region alternative above also
  bottoms out at "Minutes (manual)" for the majority-loss case (`adr-0015:50`), so neither
  design gives automatic survival of a 2-of-3-region loss; A2 closes the gap for the single-AZ
  case at zero latency cost.

**Residual: write availability under full-region loss is not five-nines with this topology
(accepted trade-off; mitigated for AZ loss by A2; automatic full-region failover would require
the cross-DC bridge described in `adr-0024`, which is not built - see `adr-0031` for how this
gap is resolved).**

---

**2. Security/auth/legal keys have no safe staleness window; bounded staleness is the wrong contract for negative authorization.**

*Strongest form:* a revocation (`auth.acl.revoke`,
`security.killswitch.tenantX`, legal cut-off) commits linearizably at the root, then fan-out is
async and edges serve bounded-stale (500 ms p99 -> up to 2 s p9999, and 5 s/30 s under
stale/degraded during partition - `consistency-contract.md:38-57`). At 50k req/s an edge can
serve ~100k requests on a revoked credential in the 2 s window, ~1.5M in a 30 s degraded
window - by contract, not by bug. The `X-Configd-Stale` header is not enforcement; an attacker
ignores it. The cost of staleness here is unbounded damage, while the contract bounds only
time. Cross-region read-your-writes is opt-in (`consistency-contract.md:170-173`), so the
operator who pushed the kill-switch is not even guaranteed to observe their own revocation at an
APAC edge.

*Response - the design defect is real; fixed by Amendment A1 (a strong-read key class), not by
defending bounded staleness.* This is the strongest point in this section, and there is no
attempt here to rebut "bounded staleness is fine for kill-switches" - it is not. The fix:

- **Amendment A1** introduces a `GLOBAL`/security key class that is exempt from bounded-stale
  edge serving. Reads of these keys go through linearizable ReadIndex against the root
  (`adr-0019-consistency-model.md:59-65`, `consistency-contract.md:16-20`), and enforcement
  points (auth services, gateways) must do a strong read on the security decision rather than
  trust an edge copy. This is exactly the "choose strong-read for keys where staleness is a
  breach" capability this argument demands - obtained on
  the read path only, so it does not reintroduce a cross-region write quorum.
- **Where A1 differs from an in-region-voter remedy, and the residual cost.** A regional design
  gets the strong read from an in-region voter at about one regional RTT. Configd's strong read for a
  `GLOBAL` key hits the single root via ReadIndex, so a distant (e.g. APAC) enforcement
  point pays one client-to-root RTT (~220 ms) for that read. This is acceptable because
  (i) security decisions are far rarer than ordinary config reads (the 99.9999% edge-read budget
  applies to the bounded-stale data plane, not to deliberately-strong security reads),
  (ii) enforcement points can cache a negative decision with a short, security-chosen TTL
  rather than the default staleness window, and (iii) a ~220 ms strong read is correct, whereas a
  sub-ms stale read is a breach. Accepted trade-off: `GLOBAL`-key strong reads are
  high-latency (single-root RTT) and, during a root-region outage (point 1), unavailable -
  enforcement points must fail closed (deny) for security keys when the strong-read path is
  unavailable, never fall back to a stale copy.

**Residual: `GLOBAL`-key strong reads cost a single-root RTT (modeled ~220 ms worst-case APAC)
and are unavailable during root-region loss; enforcement must fail closed (accepted trade-off,
mitigated by A1 plus the fail-closed rule plus a short negative-cache TTL).**

---

**3. Scope-aware placement: regional writes never hit a global quorum, so the "288 ms APAC floor" only applies to the ~10% genuinely-global keys.**

*Strongest form:* Reasoning point 1's 288 ms figure is a
strawman against the hierarchical design, because that design never routes a regional write
to the global group. `adr-0015` splits writes: ~60% `REGIONAL` commit in a 3-voter in-region
group at 2-5 ms (`adr-0015:33, :68`), ~30% `LOCAL` at under 1 ms (`adr-0015:69`), and only
~10% genuinely-`GLOBAL` pay the 68 ms cross-region cost (`adr-0015:67`). Weighted average
~9 ms (`adr-0015:71`) - comfortably under 150 ms. By this logic it is
the Quicksilver-shaped single-root design that regresses: it forces an apac-to-apac regional
write that should be 2-5 ms to round-trip to us-east and back (~440 ms), a ~100x regression on
60% of traffic, and is "literally re-adopting the alternative ADR-0015 already rejected with
numbers" (`adr-0015:80`).

*Response - engaged head-on; the arithmetic above is right for its model, but the model
assumes a high-rate per-region application-write load this config plane does not carry, and its
own latency win re-imports the centralized root (in-region, per point 3(c) below).*

- **The ~9 ms weighted average is real arithmetic, not disputed here**, for a world where
  config keys partition cleanly into GLOBAL/REGIONAL/LOCAL scopes with the write mix modeled. So
  the 288 ms figure is not the rebuttal to scope-aware placement; it is the rebuttal only to
  uniform global consensus. The real disagreement is narrower and below.
- **Scope-aware placement's REGIONAL tier is not free - it buys low write latency by giving up
  the very thing this system exists for: a single global order.** A `REGIONAL` write committed by
  an in-region group has its own independent sequence space; cross-group order is "not
  guaranteed ... approximate via HLC" (`consistency-contract.md:145-149`,
  `adr-0019-consistency-model.md:67-72`). So the 2-5 ms regional commit is cheap precisely
  because it is not globally ordered. For a configuration control plane whose stated value
  is "a definitive version and ordering that all observers will eventually see"
  (`adr-0019-consistency-model.md:83`), buying write latency with a fragmented version space is a
  poor trade: the Quicksilver-shaped single root gives one total order for the whole keyspace
  at one low-RTT commit, which is the property a config plane actually needs.
- **The "100x regression on regional writes" assumes the writer is far from the root; that is a
  routing question, not a topology one.** Under the Quicksilver shape the writer (the
  control-plane operator or CI system pushing config) routes to the root region - the same place
  `adr-0015`'s GLOBAL writes already go. Configd's write population is operator/CI-driven config
  changes (Quicksilver's 2020 write rate was ~350/s, against a 10k/s baseline),
  not latency-sensitive per-request writes from
  apac end-users. There is no "apac-to-apac REGIONAL write" hot path in a config control plane the
  way this framing implies; the data-plane apac traffic is reads, which are local
  and sub-ms in both designs. So the 440 ms figure applies to a write pattern this system is not
  built to serve - an operator/CI-driven config plane has a low-rate write population
  (Quicksilver's 2020 rate ~350/s; baseline 10k/s),
  not a high-rate per-region application-write load. (This rests on the write-population profile,
  not on a scoped-out non-goal.)
- **Decisive point: the ~9 ms average above is dominated by the in-region commit - i.e.
  by a centralized group.** Its REGIONAL tier is a Quicksilver-shaped centralized root, just
  one-per-region without a shared order - per its own point 3(c),
  it is a 3-voter group in the writer's region, not a single global root. So the real difference
  is in-region authoritative order vs one global order, not centralized-vs-distributed commit.
  That makes point 3 the mirror of the residuals conceded elsewhere: the read/commit locality
  bought for regional writes here is the same locality given up, and booked as a residual, for
  `GLOBAL` strong reads (point 2: single-root RTT) and for data residency (point 4). This is not a
  silent contradiction of points 2 and 4; it is the same locality trade made explicit, choosing
  one global order for this config workload. The disagreement therefore reduces to "one global
  root with one order" (this ADR) vs "N regional roots with no shared order plus a global root
  for 10%" (`adr-0015`), and the latter adds the PlacementDriver plus closed-timestamp
  side-transport machinery (Reasoning point 4) to manage the fragmentation. For a 10k/s
  operator-driven write load, one ordered root is sufficient and far simpler.

**Accepted trade-off (scoped): if a future workload genuinely has a high-rate, latency-sensitive,
regionally-partitionable write population that needs 2-5 ms commits and tolerates a fragmented
version space, scope-aware multi-Raft is the right tool - and that is exactly what the multi-Raft
sharding `adr-0023-multi-raft-sharding-deferred.md` addresses. This ADR does not
foreclose it; it declines to adopt it for this config-control-plane workload, where one
ordered root meets the write-latency budget and avoids the fragmentation.**

---

**4. Data residency: a single global write root is non-compliant by construction in jurisdictions that require in-region authoritative commit.**

*Strongest form:* GDPR/Schrems II, India DPDP/RBI,
PIPL/CSL, 152-FZ require certain data be authoritatively committed within the jurisdiction.
Config values can carry tenant IDs, routing keyed to personal data, regional pricing/eligibility.
A single root commits every write - including a `REGIONAL` EU key - in us-east-1, durably writing
EU data outside the EU on the hot path: a per-write compliance violation. The fallback ("N
independent clusters with app-layer routing", `adr-0023:48-50`, `adr-0024:37-38`) re-creates
external coordination this design otherwise avoids, and destroys shared ordering.
Regional Raft groups give authoritative in-region commit while still participating globally via
non-voting replication.

*Response - not rebutted. Explicitly deferred with the residual stated (Amendment A3).*

- This is a genuine constraint that the Quicksilver shape does not solve, and no claim is made
  otherwise. Residency is deliberately deferred here (Amendment A3) rather than solved, with the
  residual stated below.
- **The strongest counter-claim here - that regional Raft groups cleanly solve residency - is
  itself only partly true and worth naming:** an EU regional group keeps EU `REGIONAL` keys in the
  EU, but the `GLOBAL` subset (the 10% relied on elsewhere) still commits in the core
  region, and the EU group's participation "in the global plane via non-voting replication"
  means GLOBAL data still crosses jurisdictions. So
  multi-region Raft solves residency for the regional subset only - it does not give a clean
  global answer either; it relocates the residual to the GLOBAL keys.
- **Residual for this ADR:** a single-root deployment is non-compliant for hard-localization data
  classes. The available path is N in-jurisdiction clusters plus app-layer routing (the same
  documented fallback, `adr-0023:48-50`, `adr-0024:37-38`), which loses cross-cluster shared
  ordering. The clean answer - per-jurisdiction roots bridged by an async, separately-specified
  consistency model - is exactly the cross-DC bridge that `adr-0024` describes and that is not
  built. Until it exists, Configd must not be deployed as a single global root for data under hard
  residency mandates.

**Residual risk: data residency unsolved by this topology; deferred with the residual stated
(Amendment A3). Mitigation: N in-jurisdiction clusters plus app-layer routing (loses shared
ordering) until a cross-DC bridge (`adr-0024`) provides per-jurisdiction roots with a defined
bridge consistency model - not built today.**

---

**5. Single-group failover is manual, split-brain-prone, and data-loss-windowed; Raft cross-region election is automatic and provably safe.**

*Strongest form:* with all voters in one failure domain,
losing the domain loses the quorum - you cannot Raft-elect out; recovery is manual standby-cluster
promotion (`adr-0024:30-33`). That is the textbook split-brain setup: if the "failed" root is
merely partitioned (gray failure) and a human promotes the standby, two clusters each believe they
are authoritative with divergent committed histories and no Raft term to fence the stale
leader; reconciliation means manual conflict resolution or data loss, and the bridge that would even
define merge semantics is deferred and unbuilt (`adr-0024:18, :28-29, :56`). Async standby has
lag delta, so a hard loss loses the last delta of acknowledged writes - a durability lie for a plane
that told the client "200 OK, version=N committed." Cross-region Raft makes failover automatic
(one timeout), split-brain-free (majority plus higher term plus CheckQuorum/PreVote - the project's
own TLA+-green Leader Completeness), and zero acknowledged-write-loss.

*Response - split-brain and acked-loss are addressed by A2 plus a fencing rule for the AZ case;
the full-region manual-cutover residual stands (and overlaps point 1).*

- **A2 removes the "single failure domain" premise for the common case.** With voters across at
  least 3 AZs/DCs, an AZ loss is survived by automatic Raft election - the same automatic, fenced,
  loss-free failover credited above to cross-region Raft, because it is Raft, just
  intra-region. Leader Completeness and CheckQuorum/PreVote (`docs/architecture.md:211-219`; the
  TLA+-green `ConsensusSpec`) apply unchanged. There is no split-brain
  and no acked-write loss for AZ loss, because no manual standby is involved.
- **The split-brain risk is real only on the manual full-region cutover path, and is mitigated by
  fencing discipline, not eliminated.** When a full-region cutover is required, the runbook must
  fence the old root before promoting the standby (e.g. revoke its cluster identity or refuse its
  writes at the distribution tier), and the standby must refuse to accept writes until fencing is
  confirmed - i.e. fail closed rather than dual-write. This converts split-brain into
  "unavailable until fenced," trading divergent histories for
  bounded downtime (which point 1 already counts against the availability target).
- **Acknowledged-write durability is preserved by acking only on root-quorum durability, not on
  standby replication.** The delta-loss described above exists only if the system acks before
  the standby has the write. Configd acks after the root group's majority fsync (Raft's normal
  rule, `RaftNode.java` quorum commit at `:1319`); the async standby is
  a recovery mechanism, and on a full-region loss the unreplicated tail is lost - this is the
  honest residual, identical in kind to any async-DR system and the same one `adr-0024` already
  carries.

**Residual: on a full-region loss requiring manual cutover, (a) downtime counts against the
availability target (point 1) and (b) the async-unreplicated write tail delta is lost (accepted
trade-off). Mitigations: A2 makes AZ loss automatic, fenced, and loss-free; the full-region
cutover runbook must fence before promoting and fail closed to prevent split-brain; sub-second
region failover and a defined bridge merge semantics would require the cross-DC bridge in
`adr-0024`, which is not built.**

---

**6. Operational blast radius & the "looks done" trap: centralizing on the single group the skeleton already has ratifies the system's biggest known weakness; capacity ceiling is a wall.**

*Strongest form:* one write group means one
correlated-failure surface for 100% of writes, no bulkhead - a slow or poisoned tenant stalls
everyone (`adr-0023:14-17` admits this and uses it to justify future sharding, so this is
already agreed to be a reliability risk). The known thread-unsafe
`RaftNode`/`ConfigStateMachine` race lives specifically in multi-node mode.
Adopting the shape because the skeleton matches is the institutional "looks done" trap
(a verified component wired unsafely is more dangerous than an
absent one). And the capacity ceiling is a wall: `adr-0023:24, :41` pins single-Raft at
~10k commits/s - exactly the write-throughput baseline with zero headroom for the 100k/s burst
target; the design is sized to fail the burst target on day one with sharding not yet built.

*Response - the framing-trap is conceded (handled above); the burst-ceiling is an accepted
trade-off that reconciles with `adr-0023`; the race is an integration bug, not a topology
argument.*

- **The "looks done" trap is real and accepted** - addressed in the framing note above: this
  decision is justified by latency arithmetic, not by code shape. Adopting this ADR does not mean
  marking the topology done; the fan-out half was, at authoring, explicitly unwired (Consequences)
  and the consistency contract explicitly unproven (point 7) - see the reality-update note above
  for the current, wired status.
- **The 100k/s burst ceiling is an accepted trade-off, and the resolution is multi-Raft sharding -
  which is deferred at authoring, not denied.** `adr-0023` at that point deferred multi-Raft and
  documented the <= 10k commits/s ceiling as a hard cap (`adr-0023:24, :41, :62`). This ADR is
  consistent with that: it adopts a single ordered root for the 10k/s baseline and does not claim
  the 100k/s burst is met by one group. Crucially, multi-Raft sharding (independent throughput
  lanes plus bulkheads) is fully compatible with the Quicksilver shape - each shard
  is a centralized root that fans out; sharding partitions the root, it does not require WAN
  write consensus. So the burst headroom and the per-tenant bulkhead wanted here are
  obtained by `adr-0023`'s multi-Raft sharding, not by the cross-region/hierarchical write
  consensus this ADR rejects. This conflates "more Raft groups for throughput/isolation"
  (compatible) with "Raft groups stretched across regions for write availability"
  (rejected on latency, per the Reasoning section).
- **The thread-unsafe `RaftNode`/`ConfigStateMachine` race is an
  integration defect, not evidence for either topology.** It is the inbound-message path
  (`ConfigdServer.java:257` routeMessage on per-connection virtual threads) running unsynchronized
  against the single tick thread (`:394` `configd-tick`). It must be fixed (serialize inbound onto
  the tick executor) regardless of which topology wins - and it is
  strictly worse under a multi-node cross-region design, which exercises exactly
  the multi-node path where the race is live. It is therefore not an argument for multi-region
  Raft. (Fixed since - see the reality-update note above.)

**Accepted trade-off: one root meets the 10k/s baseline but not the 100k/s burst; burst headroom
and per-tenant bulkheads are obtained via multi-Raft sharding (`adr-0023`, since built - see the
reality-update note above), which is Quicksilver-shape-compatible (partition the root, not the
regions). Single-group head-of-line blocking was a residual until sharding shipped.**

---

**7. The bounded-staleness contract is unfalsified today, so "Quicksilver-shaped is simpler/proven" is unearned; the verified-real part is the write consensus, which multi-region Raft would extend.**

*Strongest form:* the async-fan-out half does not run -
`FanOutBuffer.append` (`ConfigdServer.java:301`) has no draining reader in `src/main`, and
`PlumtreeNode.broadcast()` is benchmark-only. So every edge
guarantee the contract leans on (500 ms staleness bound, monotonic-read-on-failover, read-your-writes)
rides on a pipeline that has never moved a real delta end-to-end; the property tests that would
prove the bound don't exist and there is no history checker; the perf scorecard is
self-labeled "modeled, not measured". Meanwhile the write-consensus
half is genuinely real and TLA+-verified (`ConsensusSpec` green, 13.7M states).
So the honest comparison is: extend the verified-real consensus core
across regions (building on solid ground) versus betting the whole consistency story on an unrun
fan-out.

*Response - the unwired/unmeasured state at authoring is fully conceded as a residual; the
inference "therefore extend write consensus across regions" does not follow.*

- **Conceded, on the record (`file:line` verified in this repo at authoring).**
  `fanOutBuffer.append(delta)` at
  `ConfigdServer.java:301` had no `src/main` caller of `deltasSince`/`latest`
  (`FanOutBuffer.java:43, :56`; grep confirmed only ConfigdServer referenced the buffer, and only to
  append). The fan-out path was unwired; the 500 ms propagation p99, monotonic-read-on-failover, and
  read-your-writes guarantees in `consistency-contract.md` were modeled, not measured end-to-end. The
  under-500-ms propagation number in Reasoning point 3 was marked unverified for Quicksilver and
  unmeasured for Configd. This ADR did not claim the contract was proven at that point (see the
  reality-update note above for the current, wired and partly-measured status).
- **But "the fan-out is unbuilt" was an argument to build and measure the fan-out, not to adopt
  cross-region write consensus.** The two halves are orthogonal: wiring `FanOutBuffer` to
  `PlumtreeNode.broadcast` to transport to edge, with a real end-to-end propagation test and a real
  linearizability/staleness history checker, is required under
  this topology, and is independent of where the write voters sit. Choosing multi-region Raft
  would not make the fan-out exist - Quicksilver-class and `adr-0015`-class designs both fan out
  to edges; a design that "still participates in the global plane via ... replication"
  to remote regions and to edges inherits the same unmeasured-propagation risk.
- **"Extend the verified-real core across regions" is the part the Reasoning section's arithmetic
  refutes (point 3 / Reasoning point 1):** the core is verified as a single group (`ConsensusSpec`);
  multi-region/hierarchical Raft is unbuilt and unverified.
  Extending it across regions would be new, unbuilt, unverified work that
  also pays the ~288 ms cross-region floor - so it is neither building on solid ground nor free.
  Keeping the verified core as a centralized root and additionally building and measuring the
  fan-out is the lower-risk path. The "simpler/proven" claim for fan-out is unearned and is
  withdrawn here; the "simpler/cheaper" claim for the write topology (one ordered root vs
  PlacementDriver plus closed-timestamp side-transport) stands on Reasoning point 4.

**Residual risk (at authoring): the async fan-out and the entire bounded-staleness contract were
unwired and unmeasured end-to-end in this repo (verified: `ConfigdServer.java:301` append with no
drain). Adopting this ADR was conditional on building and measuring that path (wire fan-out drain,
then broadcast, then edge; add a real staleness/propagation probe and a linearizability history
checker) before any "propagation target met" or "contract verified" claim was made. See the
reality-update note above: the fan-out is now wired and measured at the scale run to date; higher
scales remain modeled.**

---

### Other rejected alternatives (from the research, for completeness)

- **Global single Raft group spanning regions:** ~288 ms end-to-end write floor for an APAC client
  vs the 150 ms target; adding voters worsens it (etcd FAQ). Rejected on latency.
- **Hierarchical Raft (global group + regional groups) - `adr-0015`'s design:** the global group
  still pays the cross-region floor for any GLOBAL-scope write from a distant client; adds
  PlacementDriver plus closed-timestamp side-transport complexity (Reasoning point 4) that loses on
  operational complexity; cross-group ordering is disclaimed (`consistency-contract.md:145`).
  Never implemented (confirmed by code inspection). Its read-locality concept is borrowed (see
  Influenced by); its write topology is rejected on latency and complexity. Its per-region
  throughput/isolation benefit is re-obtained via `adr-0023` multi-Raft sharding without WAN write
  consensus (point 6).
- **Leaderless / EPaxos-style global writes:** fast path still needs a fast cross-region quorum;
  degrades to 2 RTT under conflicts; dependency-graph commit is unjustified for a low-frequency
  config write load (Quicksilver's 2020 rate ~350/s; baseline 10k/s).
  Rejected: complexity for no latency gain on this workload.
- **CockroachDB/Spanner in-Raft non-voting replicas for cross-region reads:** solves the read
  problem but keeps the cross-region write quorum; read-locality is obtained more cheaply via
  async edge copies. Rejected for the write path; its closed-timestamp read concept is borrowed.

## Consequences

### Positive

- **Meets the write p99 target (modeled at authoring):** commits intra-cluster in one low-RTT
  region; no APAC writer pays the ~288 ms cross-region floor.
- **Meets the edge read target of p99 under 1 ms / p999 under 5 ms (read path verified real):**
  local lock-free MVCC serving with no consensus (`VersionedConfigStore.java:42, :189`),
  matching Quicksilver's published "90% under 1 ms".
- **Wins on operational complexity:** one root plus a fan-out tree vs PlacementDriver plus
  closed-timestamp side-transport (Reasoning point 4); zero external coordination.
- **One global total order** for the whole keyspace (the property a config control plane needs,
  `adr-0019-consistency-model.md:83`), without the fragmented per-region sequence spaces of
  scope-aware placement (`consistency-contract.md:145-149`).
- **AZ-loss survival is automatic and loss-free** via A2 (Raft election across AZs).
- **Existence proof at scale:** Cloudflare runs this shape at 330 cities / 3 B reads/s.

### Negative

- **Write availability under full-region loss is not five-nines** (points 1 and 5, residual):
  requires manual standby cutover; sub-second region failover would require the cross-DC bridge
  described in `adr-0024`, which is not built.
- **`GLOBAL`/security-key strong reads are high-latency** (single-root RTT, modeled ~220 ms
  worst-case APAC) and unavailable during root-region loss; enforcement must fail closed (point 2).
- **Data residency unsolved** (point 4, residual): single-root deployment non-compliant for
  hard-localization data classes; deferred with the residual stated (Amendment A3).
- **100k/s burst not met by one group** (point 6): addressed via multi-Raft sharding
  (`adr-0023`, since built - see the reality-update note above); single-group head-of-line
  blocking was a residual until then.
- **At authoring, the async fan-out and the entire bounded-staleness contract were unwired and
  unmeasured** (point 7, residual): `ConfigdServer.java:301` appended with no drain. All
  propagation/edge numbers here were modeled until that path was built and probed - see the
  reality-update note above for the current status.

### Risks and mitigations

- **Unwired fan-out (highest at authoring; fixed since - see the reality-update note above):**
  mitigation was to wire `FanOutBuffer` drain to `PlumtreeNode.broadcast` to transport to edge,
  and add an end-to-end propagation probe and a real linearizability/staleness history checker
  before claiming the propagation target is met.
- **Thread-unsafe `RaftNode`/`ConfigStateMachine` integration race**
  (`ConfigdServer.java:257` vs `:394`; fixed since - see the reality-update note above):
  mitigation was to serialize inbound `routeMessage` onto the tick executor; required under any
  topology.
- **No runtime invariant enforcement in production** (`ConfigdServer.java:248` was a no-op;
  fixed since - `InvariantMonitor` now runs in production, see the reality-update note above):
  mitigation was to wire a real invariant checker.
- **Root-region full loss / split-brain on manual cutover:** mitigation - A2 (AZ spread) plus
  fence-before-promote and a fail-closed standby (point 5); sub-second failover would require the
  cross-DC bridge in `adr-0024`.
- **Security-key staleness:** mitigation - the A1 strong-read class plus fail-closed enforcement
  (point 2).

### Write-availability impact (a known, accepted limitation under full-region loss)

- **The single-region root, even with A2's multi-AZ spread, does not meet the 99.999%
  write-availability target under full-region loss.** Recovery is a manual standby cutover
  (sub-second automatic region failover would require the cross-DC bridge described in
  `adr-0024`, which is not built), and a single multi-minute-RTO event breaches the 5.26 min/yr
  five-nines budget. A2 does meet the 99.999% target for single-AZ loss - that failover is
  automatic, fenced, and loss-free (Raft election across AZs).
- **Edge reads (the 99.9999% target) are unaffected by root-region loss** - they are served
  from local edge copies that do not depend on root liveness.
- Changing the write-availability target requires an ADR. This gap is therefore recorded as a
  known, accepted limitation, and the target renegotiation is raised and resolved in
  `adr-0031-renegotiate-write-availability-target.md` (now Accepted - see the reality-update note
  above).

### Normative invariants

These promote two concessions from prose (Rejected Alternatives 2 and 4) into explicit
requirements, each with its own verification obligation.

- **INV-1 (security/`GLOBAL` keys, from Rejected Alternative 2):** enforcement points must fail
  closed (deny) for a `GLOBAL`/security key when the linearizable root read path is unavailable;
  they must not fall back to a bounded-stale edge copy. Verification obligation: this should
  become a testable entry in `consistency-contract.md` (fail-closed on root-unreachable for
  `GLOBAL` keys) with a corresponding property test.
- **INV-2 (data residency, from Rejected Alternative 4):** Configd must not be deployed as a
  single global root for data classes under hard in-jurisdiction residency mandates until
  per-jurisdiction roots plus a defined bridge (`adr-0024`) exist. Verification obligation: this
  should be enforced by a deploy-time guardrail (deployment-manifest or admission check), not by
  documentation alone.

Both INV-1 and INV-2 remain outstanding: neither the property test nor the deploy-time guardrail
is built yet, so today they hold by documentation and operator discipline alone (see the
reality-update note above).

### Relationships to other ADRs

- **Supersedes** the write-topology of `adr-0015-multi-region-topology.md` (global 5-voter
  cross-region group, regional write groups, closed-timestamp side-transport). `adr-0015`'s
  read-locality concept (follower reads via a safe timestamp) is retained in spirit but delivered
  via async edge copies, not in-Raft non-voting replicas. `adr-0015` should be marked
  superseded by this ADR for its write topology.
- **Reconciles with `adr-0023-multi-raft-sharding-deferred.md`:** consistent - this ADR adopts one
  ordered root for the 10k/s baseline; the 100k/s burst and per-tenant bulkheads come from
  `adr-0023`'s multi-Raft sharding, which partitions the root and is fully Quicksilver-shape
  compatible (no WAN write consensus). No conflict.
- **Reconciles with `adr-0024-cross-dc-bridge-deferred.md`:** consistent - `adr-0024` already
  rejects WAN-stretched Raft on the SLO (`adr-0024:23-25`) and defers per-DC roots plus an async
  bridge. This ADR adopts the single-DC/single-region root that `adr-0024` fixes, and points
  the residency (A3) and full-region-failover (points 1 and 5) residuals at that bridge, which is
  not built.
- **Consistent with `adr-0019-consistency-model.md` and `consistency-contract.md`:** unchanged -
  linearizable writes at the root, bounded-stale edge reads, with A1 adding a strong-read GLOBAL
  key class (a read-path refinement, not a contract change to write linearizability).
- **Triggered `adr-0031-renegotiate-write-availability-target.md`:** changing the write-availability
  target requires an ADR, and this ADR's single-region root was a known violation of the 99.999%
  write-availability target under full-region loss (see the write-availability impact section
  above). ADR-0031 enumerates the renegotiation options (keep 99.999% and pursue sub-second
  failover via a cross-DC bridge; a tiered SLO; or explicit risk-acceptance) and resolves the
  question - see that ADR for the decision.

## Verification

The latency arithmetic and external citations were checked against primary sources at authoring
(era attributions correct, nothing unverified promoted to fact; the Spanner ~7 ms figure is
secondary-sourced). The rejected-alternatives analysis engages each counterargument at its
strongest form; the residuals it leaves open (full-region availability, GLOBAL strong-read cost,
data residency, burst/head-of-line blocking, the fan-out that was unwired at authoring) are
stated honestly rather than argued away, and Amendments A1, A2, and A3 do not reintroduce a
cross-region write quorum.
