# Devil's Advocate Case — KEEP Global Multi-Region / Hierarchical Raft Write Consensus

> **Role:** devils-advocate, Session 0, branch `session-0-topology-adr`. **Date:** 2026-06-06.
> **Charge:** Build the strongest possible case *against* the pending QUICKSILVER-SHAPED decision
> (centralized single-group strongly-consistent writes + async fan-out to eventually-consistent
> edges under a bounded-staleness contract) and *for* retaining global multi-region / hierarchical
> Raft write consensus. This is a steelman. I do not concede; rebuttal is the architect's job.
>
> **Numbers** are taken from `docs/architecture.md` (RTT matrix §12, latency budget §2),
> `docs/decisions/adr-0015-multi-region-topology.md`, `PROMPT.md §0.1`, and `STATE-OF-REALITY.md`.
> RTTs the team itself published: us-east↔us-west 57 ms, us-east↔eu-west 68 ms,
> us-east↔ap-northeast 148 ms, us-east↔ap-southeast 220 ms, ap-ne↔ap-se 69 ms (`architecture.md:430-436`).
> Anything I could not confirm from those sources is marked **[UNVERIFIED]**.
>
> **A framing point the team must not dodge:** the Quicksilver-shaped design is being sold as
> "matching reality" because the code today registers only ONE Raft group
> (`ConfigdServer.java:82,252` → `DEFAULT_RAFT_GROUP=0`; `STATE-OF-REALITY §4.1, §5.6`). That is an
> *implementation gap*, not an *architecture validation*. "The skeleton only has one group, so let's
> declare one group the design" is the **streetlight fallacy** — adopting the topology that happens
> to be half-built rather than the one the SLOs in §0.1 require. The single-group reality is an
> argument to *finish the multi-region build*, not to *ratify its absence*. Keep that distinction
> in view through every argument below.

---

## 1. Single-group write availability collapses GLOBALLY on root-region loss; cross-region Raft survives it by design — and the SLO is 99.999%

**(a) Scenario.** The Quicksilver-shaped design has exactly one strongly-consistent write group
with one leader in one root region (call it us-east-1, per `architecture.md:355`). A correlated
root-region event occurs: AZ-wide power/network event, a bad deploy to the leader region, a
control-plane BGP/DNS withdrawal, or a fat-fingered `kubectl` against the leader's namespace
(recall `adr-0024:56` pins the whole cluster to *one* Kubernetes namespace / one DC). Every write
in the entire system — global, regional, and local config alike, because everything collapses to
group 0 (`ConfigdServer.java:369`) — must reach that one group's leader.

**(b) Concrete cost / failure mode.**
- **Blast radius = 100% of writes, globally.** In the Quicksilver-shaped single-DC, single-group
  design, there is no surviving quorum member outside the failed DC (`adr-0024:37-39,58`: voters
  are confined to one failure domain). When the root region is gone, *no quorum can be formed
  anywhere*. Writes do not slow down — they **stop**, worldwide, until a human rebuilds or fails
  over the cluster.
- **Downtime math vs the SLO.** §0.1 demands **99.999%** control-plane write availability =
  **5.26 min/year** total budget. `adr-0024:30-33` itself admits v0.1's DR story for a DC loss is
  "direct traffic to a *separately provisioned standby cluster*" — i.e. a **manual** cluster
  failover. A realistic manual cross-cluster cutover (detect → decide → repoint → re-bootstrap edge
  subscriptions) is on the order of **minutes to tens of minutes** **[UNVERIFIED — no measured RTO
  in repo; `adr-0015:50` even lists "Minutes (manual)" for the majority-loss case]**. **A single
  such event exhausts or blows the entire annual five-nines budget.** One root-region outage per
  year of >5.3 min = SLO breach for the year.
- This is not hypothetical for a config plane: the very outage this project cites as its
  motivation (`adr-0019:77,83`, "December 2025" Cloudflare config event) is a *config-distribution*
  failure. Centralizing all config writes behind one region's liveness recreates exactly that
  single-point-of-failure class.

**(c) What multi-region / hierarchical Raft buys that Quicksilver-shaped does not.**
A 5-voter global group placed 2+2+1 across three core regions (`adr-0015:31,73-74`) **survives the
loss of any single region with automatic, built-in leader election** — no human, no separate
cluster. Loss of us-east-1 (2 voters) leaves 3 of 5 voters live → quorum intact → a new leader is
elected in us-west-2 or eu-west-1 in **one election timeout (~150–300 ms randomized;
`RaftNode.java:1645`, `architecture.md:212`)**. Documented RTO: **< 10 s** (`adr-0015:49`) vs
**minutes manual** for single-group. **That is the difference between a sub-second blip and an
SLO-busting outage.** Global write availability that is robust to whole-region loss is *the* reason
to pay for cross-region quorum; the Quicksilver-shaped design throws this away and substitutes a
human runbook for what Raft does automatically. Five-nines with a single-region write group and a
manual failover is **arithmetically not achievable.**

---

## 2. "Strongly consistent everywhere" is a lie the bounded-staleness contract tells: security kill-switches, ACL/auth revocations, and legal gates have NO safe staleness window

**(a) Scenario.** A credential is leaked / a token is compromised / a tenant must be legally
cut off (GDPR erasure honoring, OFAC sanctions enforcement, a court-ordered takedown, a paid-tier
downgrade). An operator pushes the revocation: `auth.acl.revoke`, `security.killswitch.tenantX`,
`feature.gate.eu_only=false`. Under the Quicksilver-shaped contract, the write commits
linearizably at the root, then **fan-out is async** and edges serve **bounded-stale** reads:
**500 ms p99, 1 s p999, 2 s p9999** (`consistency-contract.md:38-44`) — and those bounds are the
*happy path*; under partition the edge state machine explicitly *keeps serving stale* through
`STALE` (to 5 s) and `DEGRADED` (to 30 s) (`consistency-contract.md:55-57`).

**(b) Concrete cost / failure mode.** For a *throughput knob* (rate limit, GC tuning), 500 ms of
staleness is fine. For a *negative authorization*, staleness is a **security incident measured in
requests**:
- At a busy edge serving, say, 50k req/s **[UNVERIFIED rate, illustrative]**, a **2 s p9999**
  staleness window = **up to ~100,000 requests served by a revoked credential** *after* the
  revocation was acknowledged as committed. Under a partition holding an edge in `DEGRADED`, that
  window is **30 s** = **~1.5 million requests** on a killed credential, by contract, not by bug.
- The contract's own `monotonic_read_timeout` / `ryw_timeout` fall back to **"serve stale with
  `X-Configd-Stale: true`"** (`consistency-contract.md:81-82,168-169`). A header is not enforcement.
  An attacker does not honor `X-Configd-Stale`. *Read-your-writes is cross-region only "opt-in"*
  (`consistency-contract.md:170-173`) — so the operator who pushed the kill-switch from us-east is
  **not even guaranteed to observe their own revocation** at an apac edge without explicitly routing
  to ReadIndex.
- Bounded staleness is **fundamentally the wrong contract for negative authorization** because the
  cost of staleness is *unbounded* (one leaked admin token in a 30 s window can exfiltrate the
  store), while the contract only bounds *time*, not *damage*.

**(c) What multi-region / hierarchical Raft buys.** With cross-region voters, the *security-class*
keys can be marked `GLOBAL` and **read linearizably via ReadIndex against a quorum that includes a
local-ish voter** (`consistency-contract.md:16-20`, `adr-0019:59-65`) — every edge in/near a core
region can get a *strongly consistent* answer for the kill-switch at ~one regional RTT, not "wait
up to 2–30 s for gossip." Even where edges still read stale for perf, a regional Raft voter exists
**in-region** so the *enforcement points* (API gateways, auth services) can do a linearizable check
locally instead of crossing an ocean to the single root. **The multi-region design lets you choose
strong-read for the keys where staleness is a breach, and stale-read for the keys where it isn't —
the Quicksilver-shaped design forces *all* edge reads onto the bounded-staleness contract and
offers strong reads only at a single distant root.** Some config classes have **no acceptable
staleness window**; a topology that cannot serve them strongly *near* the reader is unfit for them.

---

## 3. Write latency for clients far from the root blows the §0.1 < 150 ms budget the moment you centralize — the math is brutal for a global client base

**(a) Scenario.** Root/leader is in us-east-1 (`architecture.md:355`). A client (or a regional
service performing a write) sits in apac. Under the Quicksilver-shaped single-group design, *every*
write — including what would have been a `REGIONAL` write — must round-trip to the us-east leader
and that leader must reach its own quorum.

**(b) Concrete cost / failure mode (using the team's own RTT matrix, `architecture.md:430-436`).**
- **Client → distant leader leg alone:** an apac client to a us-east leader is ~**220 ms one way**
  for ap-southeast / ~**148 ms** for ap-northeast (`architecture.md:433-434`). The *commit
  acknowledgment* must come back: that is a full **client↔leader RTT on top of the quorum RTT.**
- **Total write commit for an ap-southeast client:** ≈ client→leader (220) + leader-side quorum
  commit (the 2nd-fastest ack from a 5-voter group = 68 ms, `adr-0015:32`) + leader→client (220) ≈
  **~508 ms p50, before queueing, batching delay, or tail.** That is **>3.3× the 150 ms p99
  budget** in §0.1 — and that's the *median*, not the tail.
- Even a **same-region apac→apac REGIONAL write** that *should* commit in **2–5 ms** intra-region
  (`adr-0015:33`) is forced to **~440 ms round-trip to us-east and back** under single-group
  centralization. **A ~100× regression on 60% of the write traffic** (regional config is "~60% of
  writes", `adr-0015:68`).
- ADR-0015's *own* rejected-alternative analysis nails this: "Single global Raft group: every write
  pays cross-region RTT (~220 ms to ap-southeast). At 10K writes/s with 220 ms commit: 2,200
  in-flight writes. Pipeline depth creates head-of-line blocking and makes the 150 ms p99 target
  impossible when leader is distant from the writer." (`adr-0015:80`). The Quicksilver-shaped
  proposal is **literally re-adopting the alternative ADR-0015 already rejected with numbers.**

**(c) What multi-region / hierarchical Raft buys.** Scope-aware placement keeps the **60% of
regional writes at 2–5 ms** (committed by a 3-voter group *in the writer's region*,
`adr-0015:33,68`) and the **30% local writes at <1 ms** (`adr-0015:69`), paying the cross-region
68 ms cost **only for the ~10% genuinely-global keys** (`adr-0015:67`). ADR-0015's weighted average:
**~9 ms** vs **68 ms** for uniform global, and *far worse* (~440–508 ms) for centralization at a
*distant* root (`adr-0015:71`). The hierarchical design is the only one of the two that *meets*
§0.1's < 150 ms for a globally distributed write population; the Quicksilver-shaped design meets it
only for clients colocated with the single root and silently abandons everyone else.

---

## 4. Data residency / regulatory: a single global write root is non-compliant by construction in jurisdictions that require in-region authoritative commit

**(a) Scenario.** EU (GDPR / Schrems II / data-localization), India (DPDP / RBI data-localization),
China (PIPL / CSL), Russia (152-FZ), and various sectoral regimes require that certain data be
**authoritatively stored and committed within the jurisdiction**, and in several cases that it not
transit or be made durable in a foreign jurisdiction. Config values are not always benign: they can
contain tenant identifiers, routing rules keyed to personal data, regional pricing/eligibility,
and legally-significant feature gates.

**(b) Concrete cost / failure mode.**
- In the Quicksilver-shaped single-group design, **every write is committed (made durable, ordered,
  and acknowledged) in the single root region.** A `REGIONAL` EU key's authoritative commit happens
  in us-east-1 — the EU data is durably written *outside the EU* on the hot path. For data classes
  under localization mandates this is a **per-write compliance violation**, not an edge case.
- The fallback the deferral ADRs offer is **"deploy N independent clusters with application-layer
  routing"** (`adr-0023:48-50`, `adr-0024:37-38`). That pushes cross-region *consistency and
  routing* into every client application, re-creating the very "ZK-shaped external coordination"
  the PROMPT forbids (`PROMPT.md §5 rule 5`) — and N independent clusters have **no shared ordering,
  no shared version space, and no cross-cluster failover**, so the "globally consistent config
  plane" promise is gone.
- There is **no in-region authoritative commit primitive** anywhere in the Quicksilver-shaped
  design — strong consistency exists only at the single root, and everything else is gossip.

**(c) What multi-region / hierarchical Raft buys.** Regional Raft groups give **authoritative,
linearizable, in-region commit** for `REGIONAL` keys (`adr-0015:17,33`): an EU regional group's
voters are in EU AZs, so EU config is committed in the EU, with a real total order and a real
version space — *while still* participating in the global plane via non-voting replication for the
GLOBAL subset. This is the **only** topology of the two that can satisfy "authoritative commit
in-region" **without** shattering the system into N uncoordinated clusters. Data residency is a
**hard external constraint**, not a tuning knob; a design that can only commit in one region cannot
be sold into regulated markets.

---

## 5. Single-group root failover is a manual, split-brain-prone, data-loss-windowed procedure; Raft cross-region election is automatic and proven safe

**(a) Scenario.** The root region/leader becomes unreachable (§1's event, or a gray failure: leader
is up but fsync-stalled / packet-lossy — `architecture.md:227-232`). Something must promote a new
authoritative writer.

**(b) Concrete cost / failure mode for the Quicksilver-shaped single-group/single-DC design.**
- **No automatic intra-design failover exists.** Because all voters are in one failure domain
  (`adr-0024:37-39`), losing the domain loses the quorum; you cannot Raft-elect your way out. The
  only recovery is **manual promotion of the standby *cluster*** (`adr-0024:30-33`).
- **Split-brain risk.** Manual cross-cluster failover with async-replicated state is the textbook
  setup for **dual-write / split-brain**: if the "failed" root is merely partitioned (gray failure,
  not dead) and a human promotes the standby, you now have **two clusters each believing they are
  authoritative**, with **divergent, conflicting committed histories** and *no Raft term to fence
  the stale leader*. Reconciling that means **manual conflict resolution / data loss** — and the
  cross-DC bridge that would even *define* the merge semantics is **explicitly deferred and unbuilt**
  (`adr-0024:18,28-29,56` → "last-writer-wins or CRDT semantics … ADR for that model is separate
  work", "Verification: NOT YET WIRED").
- **Data-loss window.** Any async replication to the standby has lag δ; a hard root loss loses the
  last δ of committed writes. δ is **unspecified and untested** in the repo **[UNVERIFIED]**, which
  for a control plane that just told a client "200 OK, version=N committed" (`architecture.md:71`)
  is a **durability lie**: the client's acknowledged write can vanish on failover.

**(c) What multi-region / hierarchical Raft buys.** Raft makes failover **automatic, bounded, and
provably safe**:
- **Automatic election** in one timeout (~150–300 ms, `RaftNode.java:1645`), no human in the loop.
- **No split-brain by construction:** a new leader requires a *majority* and a *higher term*; a
  partitioned old leader is fenced by **CheckQuorum + PreVote** (`architecture.md:212-219`), so two
  leaders cannot both commit — this is exactly the **Leader Completeness / State Machine Safety**
  property the project's own TLA+ `ConsensusSpec` model-checks green (`STATE-OF-REALITY §3`,
  13.7M states).
- **Zero acknowledged-write loss:** Raft only acks after majority durability, and a new leader's log
  provably contains every committed entry (Leader Completeness). The **data-loss window is zero by
  design**, versus an unbounded-δ window for async standby failover.
The Quicksilver-shaped design trades a *verified, automatic, loss-free* failover for a *manual,
split-brain-capable, lossy* one — and then **defers the very ADR that would make the manual path
safe** (ADR-0024). That is strictly worse on every failover-correctness axis.

---

## 6. Operational blast radius & "looks done" trap: centralizing on the single group the skeleton already has ratifies the system's biggest known weakness instead of fixing it

**(a) Scenario.** The decision is made; the team marks the topology "done" because the code already
matches (one group). Six months later the central write group hits a correlated failure: a poison
config that crashes the apply path, a leader GC pause cascade, the **known thread-unsafe
RaftNode/ConfigStateMachine race** that `STATE-OF-REALITY §5.1 (🔴)` documents as live *specifically
in multi-node mode*, or simply the §1 region loss.

**(b) Concrete cost / failure mode.**
- **All eggs, one basket, by design.** A single write group means a single correlated-failure
  surface for **100% of writes**. There is no bulkhead: a slow/poisoned tenant stalls *everyone*
  (`adr-0023:14-17` admits exactly this head-of-line-blocking risk for single-Raft and uses it to
  justify *future* sharding — i.e. the team **already agrees single-group is a reliability risk**,
  they've just deferred the fix).
- **The "looks done" hazard is acute and documented.** `STATE-OF-REALITY` is blunt: the system
  "registers exactly **one** Raft group" (§4.1), multi-region is "a deploy-shaped false promise"
  (§5.6), and — critically — its ordering principle is *"a verified-correct component wired in an
  unverified/unsafe way is more dangerous than an absent component, because it looks done"* (§5
  preamble). **Adopting the Quicksilver-shaped topology because the skeleton already matches it is
  the institutional version of that exact trap:** you ratify the half-built state as the target,
  declare victory, and the multi-region capability that the SLOs require **never gets built** —
  while the docs (`architecture.md`, `adr-0015`) still *promise* it, widening the doc↔reality gap
  the forensic review already flagged as the top danger.
- **The capacity ceiling is a wall, not a slope.** `adr-0023:24,41` pins single-Raft at **~10k
  commits/s** — which is *exactly* the §0.1 **baseline**, with **zero** headroom for the
  **100k/s burst** target. The Quicksilver-shaped design is sized to fail the burst SLO on day one
  and has no in-design path to absorb it (sharding is deferred, `adr-0023:18`).

**(c) What multi-region / hierarchical Raft buys.** Multiple Raft groups (regional + global) are
**independent failure domains and independent throughput lanes**: a poisoned/slow `REGIONAL` group
in apac cannot stall EU or global writes; the global group's 100k/s burst can be absorbed by
batching on a path that *isn't* also carrying all regional+local traffic; and a region loss degrades
*that region's* writes, not the planet's. Crucially, choosing multi-region **forces the team to
actually build and test the multi-group path** — closing the exact gap `STATE-OF-REALITY` calls the
system's central weakness (the unverified multi-node wiring, §5.1) — instead of papering over it by
declaring the single-group skeleton "the design." **The decision that looks cheaper today (adopt
what's already half-built) is the one that permanently caps the system below its own stated targets
and bakes in its single largest correlated-failure surface.**

---

## 7. (Supporting) The bounded-staleness contract is unfalsified vapor today, so "Quicksilver-shaped is simpler/proven" is unearned — single-group is not the safe default it's dressed up as

**(a) Scenario.** The decision is justified partly on "the strong-write + async-fan-out model is
simpler and good enough." But the async fan-out half of that model **does not run.**

**(b) Concrete cost / failure mode.** `STATE-OF-REALITY §4.2, §5.3` and `findings-design-vs-reality
§2` establish, by file:line, that the fan-out is a **write-only sink**: `FanOutBuffer.append`
(`ConfigdServer.java:301`) has **no draining reader anywhere in `src/main`**, and
`PlumtreeNode.broadcast()` is called **only in a benchmark**. So **every** edge guarantee the
Quicksilver-shaped contract leans on — the 500 ms p99 staleness bound, monotonic-read-on-failover,
read-your-writes — **rides on a pipeline that has never moved a real delta end-to-end.** The
`consistency-contract.md §7` property tests that would prove the bound (`StalenessUpperBoundTest`,
`MonotonicReadFailoverTest`, the `LinearizabilityTest` with a real history checker) **do not exist /
have no history checker** (`STATE-OF-REALITY §4.4, §5.4`). The perf scorecard backing "good enough"
is self-labeled **"MODELED, NOT MEASURED"** (`STATE-OF-REALITY §4.6`).

**(c) What this means for the decision.** The Quicksilver-shaped design is being presented as the
*conservative, proven, simpler* choice. It is not: its consistency-critical half is **unbuilt and
unverified**, and its core safety claim (bounded staleness) is **untested vapor**. Meanwhile the
*write-consensus* half that multi-region Raft would extend is the part that is **genuinely real and
TLA+-verified** (`STATE-OF-REALITY §3`, ConsensusSpec green, 13.7M states). So the honest risk
comparison is: **extend the verified-real consensus core across regions** (hard but building on
solid ground) **vs. bet the whole consistency story on an async fan-out that has never run.** Framed
honestly, multi-region Raft is the choice that keeps correctness on the part of the system that
actually works. "Simpler" is doing a lot of unearned work in the pro-Quicksilver argument.

---

## Closing (no concession)

The Quicksilver-shaped decision wins on exactly one axis: it matches the code that happens to exist
today. On every axis that §0.1 actually grades — **write availability under region loss (five-nines
is unreachable with a single-region group + manual failover), write latency for a global client
base (3–100× over budget), failover safety (manual + split-brain + lossy vs automatic + fenced +
loss-free), data residency (non-compliant by construction), and the consistency needs of
security/auth/legal config classes (no safe staleness window)** — global multi-region / hierarchical
Raft write consensus is superior, and the project's *own* ADR-0015 already proved it with numbers
before someone decided to un-prove it to fit the skeleton. The single-group reality is a reason to
**finish the build**, not to **canonize the gap**. If the architect wants the Quicksilver-shaped
decision to stand, it must answer — concretely, with numbers — how it hits 99.999% write
availability through a root-region loss, how it serves an apac writer inside 150 ms, how it commits
EU data in the EU, and how it fails over without split-brain or acknowledged-write loss. Until then,
the burden is not met.
