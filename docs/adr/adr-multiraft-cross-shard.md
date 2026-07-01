# ADR (multi-Raft, D-C): DISCLAIM Cross-Shard Atomicity; Co-Locate Related Keys; Do Not Build Distributed Transactions

## Status

**Proposed** (2026-06-21). Not yet Accepted - awaits operator sign-off.
Part of the multi-Raft arc. Pairs with `adr-multiraft-partitioning.md` (D-A),
`adr-multiraft-topology.md` (D-B), `adr-throughput-target.md`. **This ADR supersedes one bullet of ADR-0023** (see Decision).

This is the single biggest scope lever in the whole arc: BUILD (distributed transactions) is a
multi-month additional arc; DISCLAIM is cheap and matches the charter. The decision below is made with
the most rigor and the most explicit workload justification.

## Context

The moment two keys live in different shards, there is no cheap atomic multi-key write. Options:
- **DISCLAIM:** single-key writes are strongly consistent; multi-key atomicity across shards is
  explicitly NOT offered (matches the charter's stated non-goal).
- **BUILD:** distributed transactions (2PC / Percolator / a timestamp authority) - a major arc, a new
  failure surface, significant latency cost.

What the repo already says (verified):
- **section 0.2 non-goal (`PROMPT.md:35`):** Configd is **NOT** *"a multi-key transactional store (no
  cross-key atomicity beyond single-config writes)."* Scope creep requires architect + researcher
  sign-off.
- **`consistency-contract.md` section 5:** *"Applications needing two keys ordered must route both to the same
  Raft group (same scope)."* Cross-group order is already **N/A**, not "broken."
- **`docs/research.md:343/385/401`** already recorded the rejection of every cross-shard txn mechanism,
  with the reason inline: Percolator-2PC -> *"no cross-key transactions needed"*; write-intents /
  parallel-commits -> *"no distributed transactions needed"*; Spanner 2PC-for-cross-split -> rejected
  (*"must work with NTP"*).
- A grep for `atomic|transaction|2PC|all-or-nothing|saga|multi-key` across the repo surfaces **zero
  affirmative requirement** - every hit is a disclaimer, a rejected alternative, or the *single-group*
  BATCH.
- The atomic primitive already exists in code: `CommandCodec` encodes `BATCH` as one command
  (`TYPE_BATCH=0x03`, `[0x03][4-byte count][mutations...]`, `MAX_BATCH_COUNT=10_000`) -> one
  `propose(groupId,cmd)` -> one Raft log entry -> one `apply`, so atomicity is *structural* (all-or-none,
  on a single shard). Endpoint unwired (CM-033), but codec + wire + contract guarantee exist.

## Workload Verdict - is cross-shard atomic multi-key writes ever needed here?

**No named requirement forces it.** Config writes are naturally single-key or single-document:

| Use case | Natural shape | Forces cross-shard atomicity? |
|---|---|---|
| Feature flag (on/off/%) | single key, or one JSON bundle | No - industry idiom is "evaluate once, pass the result"; a coupled flag-set is one bundle value |
| A/B-test parameters | one key holding the param map | No - single document, one PUT |
| Routing rules / traffic split | one key per route, or one routing-table doc | No - atomic shift is one document write; if split, co-locate under the route prefix |
| TLS / security policy | `secure/*` strong-read class | No - document-shaped, co-located by construction |
| Capacity / rate limits | one key per limit | No - independent scalars, LWW per key |
| Secrets | one key per secret (refs a secrets manager) | No - section 0.2 non-goal; rotation is per-secret |
| *Hypothetical:* flip flag X (shard 1) **and** route Y (shard 2) all-or-nothing | two keys, two shards, one invariant | **Yes - but** escape: co-locate X,Y under one scope -> one shard -> atomic `BATCH` |

The "I have an invariant across a few keys" case is real but small (2-3 keys) and is fully covered by
co-location. Cross-key atomicity across *independently-sharded* keys is genuinely a non-goal for this
workload.

## Decision

**DISCLAIM cross-shard distributed transactions.** Configd guarantees per-key linearizability and
per-shard total order; cross-shard atomicity/ordering is explicitly NOT guaranteed. Multi-key
consistency needs are met by **co-locating related keys in one shard** (same scope/prefix routing,
D-A/D-B) and using the existing **single-group atomic `BATCH`**.

Three red-team-driven qualifications that make DISCLAIM honest rather than a regression:
- **Default to N=1 (a single shard) below the measured throughput threshold.** A deployment that fits one
  shard then retains **whole-keyspace** atomic `BATCH` - the property etcd and today's single-group
  Configd give - and shards only when measured throughput forces N>1. Sharding-by-default would be a
  consistency *regression* for small operators who never needed it.
- **Wiring single-group `BATCH` (CM-033) is a HARD co-delivery requirement of any sharding ship**, not an
  "ideally": it is codec-only today (`CommandCodec.TYPE_BATCH=0x03`, no endpoint), and the disclaimer is
  hollow without a usable multi-key primitive.
- **Co-location is an UNENFORCED obligation**, not a system guarantee; it SHOULD carry a **write-time
  guard** (warn/reject a multi-key `BATCH` whose keys resolve to >1 shard) so the silent-partial-write
  footgun is observable.

**This decision supersedes the ADR-0023 migration-plan bullet** that named a *"cross-shard transaction
coordinator (2PC over Raft)"* for v0.2 (`adr-0023:46-48`). The v2 sharding plan keeps shard-routing +
per-shard Raft groups but **drops the cross-shard 2PC coordinator**, replacing it with co-location +
single-shard BATCH. (Flagged for operator confirmation - Open Q1.)

### Exact consistency-contract language (drop-in for `consistency-contract.md` section 5/section 9)

> **Cross-shard atomicity and ordering - NOT GUARANTEED (by design).**
> Under a multi-shard (multi-Raft) deployment each shard is an independent Raft group with its own log,
> its own linearizable order, and its own applied-mutation sequence (section 4).
>
> **Guaranteed:** *Per-key linearizability* - every `PUT`/`DELETE` to a key is linearizable within
> that key's shard (ReadIndex; section 1). *Per-shard total order* - all writes within one shard share a
> single Raft log and are totally ordered. *Single-shard atomic multi-key* - a `BATCH(mutations[])`
> whose keys all reside in **one** shard commits atomically (one log entry, all-or-nothing) and is
> linearizable.
>
> **NOT guaranteed:** *Cross-shard atomicity* - two writes to keys in different shards are independent;
> a reader may observe one applied and the other not. *Cross-shard ordering* - no happens-before
> between writes to different shards. *Cross-shard read snapshot* - a read spanning shards is not a
> consistent point-in-time snapshot; it composes per-shard cursors (section 3/section 6).
>
> **Escape hatch - co-locate related keys:** applications needing 2-N keys ordered *or* atomic MUST
> route them to the **same shard** by assigning the same scope/prefix; co-located keys then get
> single-group atomic `BATCH` and full intra-shard ordering - no distributed transaction required.
> Configd deliberately does not provide cross-shard transactions (section 0.2); co-location is the supported
> mechanism for multi-key consistency.

## Rationale

1. **No named requirement; an explicit non-goal.** section 0.2 fences it out; the contract already routes
   coupled keys to one group; `research.md` already rejected every build-mechanism for this exact
   reason. BUILD would be chartered scope creep.
2. **The escape hatch covers the realistic case with zero new failure surface.** Put related keys under
   one scope (`team-x/experiment-42/*`) -> one shard -> one `BATCH` (one log entry). Atomic multi-key +
   total order, at single-group latency (<150 ms p99). Coupled keys *should* share a scope - that is
   correct data modeling, the same advice the contract already gives for ordering, extended to
   atomicity.
3. **BUILD breaks the latency budget and re-opens consensus-grade verification.** The simplest viable
   (2PC over Raft groups + coordinator) costs >=2 cross-shard RTT + a durable coordinator-log write
   (+2 oracle round-trips if Percolator-ordered) - over the section 0.1 <150 ms budget - and introduces a
   categorically new failure surface (coordinator failure -> in-doubt transactions; lock/intent
   lifecycle + GC; cross-shard deadlocks; participant failover mid-txn). It also forces **upgrading
   configd-linz from per-key independent registers to a multi-register strict-serializable checker** - a
   *major harness rewrite*, not an extension - plus a new TLA+ commit-protocol spec.
4. **DISCLAIM keeps the existing model intact under sharding.** The monotonic sequence (ADR-0004) and
   the edge cursor (ADR-0035) are *already per-group*; sharding instantiates N independent counters -
   exactly what those ADRs designed for ("a global counter would reintroduce the single-writer
   bottleneck"). A client reading across shards composes per-shard cursors (a cursor *vector*);
   per-shard monotonic-read (INV-M) and read-your-writes (INV-RYW) survive unchanged within each shard.
   Nothing the contract promises breaks.
5. **DISCLAIM is reversible; BUILD is not cheaply reversible.** If a concrete future requirement
   appears, re-open with that requirement as justification. Building 2PC and finding it unneeded bakes
   in locks/intents/oracle and a harder harness forever.

## Prior-Art Mechanism Borrowed

**The etcd / ZooKeeper / Consul / AWS AppConfig camp - single-group atomic multi-key + no cross-shard
transactions.** etcd `Txn`, ZK `multi()`, and Consul KV `Txn` are all **intra-group** (single Raft /
single ensemble) - which Configd **matches** via single-shard `BATCH`. None of them offers *cross*-shard
atomicity either, so disclaiming it is **parity, not a gap.** AppConfig/LaunchDarkly/Unleash deploy a
config profile (one versioned document) atomically and trade strict cross-key consistency for
availability by design. We **reject** the build camp (TiKV Percolator + timestamp oracle; CockroachDB
write-intents + parallel-commits; Spanner 2PC-over-Paxos + TrueTime commit-wait ~7 ms) - those are
distributed transactional databases for which cross-shard atomicity is the product. The only cross-group
correctness work worth doing is **stable, epoch-versioned scope->group routing** (TiKV
`RegionEpoch{ConfVer,Version}`, owned by D-B), not transactions.

## Rejected Alternative - BUILD distributed transactions

2PC-over-Raft / Percolator / parallel-commits / Spanner-2PC. Rejected: contradicts section 0.2; no named
requirement demands it; multi-month cost with a new durable coordinator + lock/intent subsystem + new
in-doubt failure surface; >=2 cross-shard RTT (+oracle) over the <150 ms budget; forces a
per-key->strict-serializable rewrite of the linearizability harness; the prior-art camp Configd already
chose rejected exactly these mechanisms.

## Consequences

- v2 sharding ships shard-routing + per-shard Raft groups but **no cross-shard coordinator**; per-shard
  sequence (ADR-0004) and per-shard edge cursors (ADR-0035) are the model; cross-shard reads compose
  per-shard cursors and are not a consistent snapshot.
- **Single-group `BATCH` MUST be wired (CM-033) - a HARD co-delivery requirement of sharding (Red-Team),
  not "ideally":** codec-only today (`CommandCodec.TYPE_BATCH=0x03`, no endpoint on `HttpApiServer`/
  `configd-api`); shipping the disclaimer without a usable multi-key primitive leaves **neither
  cross-shard atomicity nor whole-keyspace atomicity - strictly worse than today's single group**.
- **Default N=1 below the throughput threshold** (Red-Team) so small deployments keep whole-keyspace
  `BATCH`; shard only when measured throughput forces N>1.
- **A write-time cross-shard-multi-key guard** (warn/reject a `BATCH` whose keys resolve to >1 shard)
  ships with sharding so co-location's unenforced obligation is observable, not a silent data bug.
- **ADR-0023's "cross-shard 2PC over Raft" migration bullet is struck/superseded.**
- Doc precision (not a break): the edge-staleness commit-timestamp (section 2) is per-shard-leader; cross-shard
  staleness comparisons inherit per-leader NTP skew (<=50 ms) - already the only residual error term in
  section 2, now across more leaders.

## Red-Team Critique (surviving)

- **"ADR-0023 literally lists a cross-shard 2PC coordinator for v0.2."** *Surviving - the one genuine
  internal contradiction.* Resolved explicitly: that line is an offhand migration note, not a
  requirement, and it conflicts with section 0.2 and `research.md`. This ADR supersedes it (Open Q1). Recorded,
  not averaged.
- **"An etcd/Consul/ZK user expects atomic multi-key `Txn` and will be surprised."** *Surviving;
  mitigated by parity + honesty.* Those `Txn`s are intra-group, which Configd matches via single-shard
  `BATCH`; the only thing disclaimed is *cross-shard* atomicity, which none of them offer. Docs must
  tell the etcd refugee: "your `Txn` maps to our co-located `BATCH`."
- **"A future requirement could force it."** *Handled by the v2 re-open trigger* (below) - DISCLAIM is
  reversible.
- **"Co-location creates hotspots - forcing related keys onto one shard concentrates load."**
  *Surviving but bounded:* co-location is opt-in and applies only to the small coupled-key minority,
  not the bulk keyspace; the throughput target is met by sharding the independent majority. Handed to
  D-A/D-B as a routing input (honor a co-location/scope-affinity hint), not a blocker.
- **DISCLAIM is strictly weaker than etcd for the COMMON single-shard deployment.** *Surviving ->
  mitigated by default-N=1.* A deployment that fits one shard gets whole-keyspace `Txn` under etcd (and
  today's single-group Configd); hashing it across N by default would lose that. Defaulting N=1 until
  throughput forces N>1 keeps the parity claim true at every scale.
- **Co-location is a silent-partial-write footgun, not "correct data modeling."** *Surviving -> mitigated
  by the write-time guard.* It is an unenforced obligation the system newly imposes; the guard
  (reject/warn a cross-shard multi-key `BATCH`) makes it observable.
- **The escape-hatch primitive does not exist on any client path (verified).** *Surviving -> BATCH is now a
  HARD co-delivery requirement* (Decision/Consequences), not an open question.
- **"Reversible" is asymmetric - migration debt in customer data models.** *Surviving.* Clients who
  co-located unrelated keys for atomicity become anti-partition artifacts a later BUILD would
  un-co-locate; and an OSS operator cannot *file* the named-requirement re-open trigger - they hit the
  footgun first. Mitigation: the write-time guard surfaces the need early, and N=1-at-small-scale avoids
  forcing co-location until sharding is genuinely required.

## v2 Re-Open Trigger (the named requirement that would justify BUILD)

A concrete, named workload requiring **all-or-nothing across keys that cannot be co-located** -
genuinely independent high-throughput shards bound by a hard cross-shard invariant, where co-locating
them would violate the throughput/partitioning goal. Absent that, remain disclaimed.

## Verification Extension (additive under DISCLAIM - no rewrite)

- Keep configd-linz checking **each key as an independent linearizable register** (ADR-0032) - sharding
  does not change per-key registers.
- Assert **per-shard** monotonic sequence (INV-V1/V2) and per-shard edge-cursor monotonic-read / RYW
  (INV-M/RYW) hold independently per shard.
- Add a **negative test** pinning the disclaimer: a cross-shard read is explicitly NON-atomic.
- Add a **single-group `BATCH` atomicity** test (all-or-nothing within one shard) when BATCH is wired.
- **No new commit-protocol TLA+; no strict-serializable multi-register checker** - these are exactly
  the costs DISCLAIM avoids.

## Open Questions for the Operator

1. **SCOPE-DEFINING (flagged): cross-shard transactions IN or OUT?** Recommendation: **OUT (disclaim)**.
   Requires confirming the supersession of ADR-0023's "2PC over Raft" bullet.
2. **Wire single-group `BATCH` now (CM-033)?** DISCLAIM is materially stronger if the escape hatch's
   atomicity primitive is delivered alongside sharding. Approve scoping BATCH into the work?
3. **Co-location vs partitioning tension** (input to D-A/D-B): confirm routing honors a
   co-location/scope-affinity hint for the coupled-key minority.
4. **Messaging to etcd/Consul/ZK users:** confirm docs state single-shard `BATCH` is the analog of
   `etcd Txn` / `consul Txn` / `zk multi()`, and that cross-shard atomicity is offered by none of them.

## Related

ADR-0004 (per-group sequence), ADR-0019 (consistency model - "why per-group sequence numbers"),
ADR-0033 (commit-confirmed ack), ADR-0035 (per-group cursor / HLC descope), ADR-0023 (superseded
migration bullet), `consistency-contract.md` section 5/section 9.
