# ADR: Disclaim Cross-Shard Atomicity; Co-Locate Related Keys; Do Not Build Distributed Transactions

## Status

Accepted. This is the design as built: Configd disclaims cross-shard atomicity and does not build
distributed transactions. Pairs with [adr-multiraft-partitioning](adr-multiraft-partitioning.md),
[adr-multiraft-topology](adr-multiraft-topology.md), and [adr-throughput-target](adr-throughput-target.md).
This decision supersedes one bullet of ADR-0023 (see Decision).

Building distributed transactions would have been a multi-month project of its own; disclaiming
cross-shard atomicity and relying on co-location instead is far cheaper and matches what the workload
actually needs. The decision below is made with the most rigor and the most explicit workload
justification, because it is the single biggest scope lever in the whole multi-Raft design.

## Context

The moment two keys live in different shards, there is no cheap atomic multi-key write. Options:
- **Disclaim:** single-key writes are strongly consistent; multi-key atomicity across shards is
  explicitly not offered - this matches Configd's stated non-goal.
- **Build:** distributed transactions (2PC / Percolator / a timestamp authority) - a major project, a
  new failure surface, significant latency cost.

What the design already commits to:
- Configd's stated non-goal is explicit: it is not "a multi-key transactional store" - there is no
  cross-key atomicity beyond single-config writes.
- The consistency contract already says applications that need two keys ordered must route both to the
  same Raft group (same scope). Cross-group order is already not offered, not "broken."
- Earlier design research already recorded rejecting every cross-shard transaction mechanism
  considered, for the same reason each time: Percolator-style 2PC and write-intents/parallel-commits
  were rejected because no cross-key transactions were needed; a Spanner-style 2PC-for-cross-split was
  rejected because it needs a TrueTime-grade clock, and Configd's model is NTP-only.
- A search for `atomic|transaction|2PC|all-or-nothing|saga|multi-key` across the repo surfaces zero
  affirmative requirement for cross-shard atomicity - every hit is a disclaimer, a rejected alternative,
  or the single-group `BATCH`.
- The atomic primitive already exists in code: `CommandCodec` encodes `BATCH` as one command
  (`TYPE_BATCH=0x03`, `[0x03][4-byte count][mutations...]`, `MAX_BATCH_COUNT=10_000`) -> one
  `propose(groupId,cmd)` -> one Raft log entry -> one `apply`, so atomicity is structural (all-or-none,
  on a single shard). The endpoint is not wired: the codec, wire format, and contract guarantee exist,
  but there is no `BATCH` route on `HttpApiServer` today.

## Workload verdict: is cross-shard atomic multi-key writes ever needed here?

**No named requirement forces it.** Config writes are naturally single-key or single-document:

| Use case | Natural shape | Forces cross-shard atomicity? |
|---|---|---|
| Feature flag (on/off/%) | single key, or one JSON bundle | No - industry idiom is "evaluate once, pass the result"; a coupled flag-set is one bundle value |
| A/B-test parameters | one key holding the param map | No - single document, one PUT |
| Routing rules / traffic split | one key per route, or one routing-table doc | No - atomic shift is one document write; if split, co-locate under the route prefix |
| TLS / security policy | `secure/*` strong-read class | No - document-shaped, co-located by construction |
| Capacity / rate limits | one key per limit | No - independent scalars, LWW per key |
| Secrets | one key per secret (refs a secrets manager) | No - explicit non-goal; rotation is per-secret |
| *Hypothetical:* flip flag X (shard 1) **and** route Y (shard 2) all-or-nothing | two keys, two shards, one invariant | **Yes - but** escape: co-locate X,Y under one scope -> one shard -> atomic `BATCH` |

The "I have an invariant across a few keys" case is real but small (2-3 keys) and is fully covered by
co-location. Cross-key atomicity across *independently-sharded* keys is genuinely a non-goal for this
workload.

## Decision

**Disclaim cross-shard distributed transactions.** Configd guarantees per-key linearizability and
per-shard total order; cross-shard atomicity and ordering are explicitly not guaranteed. Multi-key
consistency needs are met by co-locating related keys in one shard (same scope/prefix routing - see
[adr-multiraft-partitioning](adr-multiraft-partitioning.md) and
[adr-multiraft-topology](adr-multiraft-topology.md)) and using the single-group atomic `BATCH`.

Three qualifications that make disclaiming honest rather than a regression:
- **The default is N=1 (a single shard).** A deployment that fits one shard keeps whole-keyspace atomic
  `BATCH` - the property etcd and single-group Configd both give - and an operator shards only when
  measured throughput forces N>1. Sharding by default would be a consistency regression for small
  operators who never needed it.
- **Wiring single-group `BATCH` is meant to be a hard co-delivery requirement of shipping sharding, not
  an "ideally."** As things stand, `BATCH` is still codec-only (`CommandCodec.TYPE_BATCH=0x03`, no HTTP
  endpoint) - the disclaimer is hollow without a usable multi-key primitive, and this remains a real,
  open gap between this decision and what has actually shipped.
- **Co-location is an unenforced obligation, not a system guarantee.** It should carry a write-time
  guard (warn or reject a multi-key `BATCH` whose keys resolve to more than one shard) so the
  silent-partial-write footgun is observable; this guard is not built either.

**This decision supersedes the ADR-0023 migration-plan bullet** that named a "cross-shard transaction
coordinator (2PC over Raft)" as a later migration step. The sharding design keeps shard-routing and
per-shard Raft groups but drops the cross-shard 2PC coordinator, replacing it with co-location plus
single-shard `BATCH`.

### The cross-shard guarantee (as documented in the consistency contract)

> **Cross-shard atomicity and ordering are not guaranteed, by design.**
> Under a multi-shard (multi-Raft) deployment each shard is an independent Raft group with its own log,
> its own linearizable order, and its own applied-mutation sequence.
>
> Guaranteed: *per-key linearizability* - every `PUT`/`DELETE` to a key is linearizable within that
> key's shard (ReadIndex). *Per-shard total order* - all writes within one shard share a single Raft
> log and are totally ordered. *Single-shard atomic multi-key* - a `BATCH(mutations[])` whose keys all
> reside in one shard commits atomically (one log entry, all-or-nothing) and is linearizable.
>
> Not guaranteed: *Cross-shard atomicity* - two writes to keys in different shards are independent; a
> reader may observe one applied and the other not. *Cross-shard ordering* - no happens-before between
> writes to different shards. *Cross-shard read snapshot* - a read spanning shards is not a consistent
> point-in-time snapshot; it composes per-shard cursors.
>
> Escape hatch - co-locate related keys: applications needing 2-N keys ordered or atomic route them to
> the same shard by assigning the same scope/prefix; co-located keys then get single-group atomic
> `BATCH` and full intra-shard ordering, with no distributed transaction required. Configd deliberately
> does not provide cross-shard transactions; co-location is the supported mechanism for multi-key
> consistency.

## Rationale

1. **No named requirement; an explicit non-goal.** The stated non-goal fences it out; the contract
   already routes coupled keys to one group; earlier design research already rejected every
   build-mechanism for this exact reason. Building it would be uncharted scope creep.
2. **The escape hatch covers the realistic case with zero new failure surface.** Put related keys under
   one scope (`team-x/experiment-42/*`) -> one shard -> one `BATCH` (one log entry). Atomic multi-key
   plus total order, at single-group latency (<150 ms p99). Coupled keys *should* share a scope - that
   is correct data modeling, the same advice the contract already gives for ordering, extended to
   atomicity.
3. **Building it breaks the latency budget and re-opens consensus-grade verification.** The simplest
   viable approach (2PC over Raft groups plus a coordinator) costs at least 2 cross-shard round trips
   plus a durable coordinator-log write (plus 2 oracle round trips if Percolator-ordered) - over the
   <150 ms write-latency budget - and introduces a categorically new failure surface (coordinator
   failure leaves in-doubt transactions; lock/intent lifecycle and garbage collection; cross-shard
   deadlocks; participant failover mid-transaction). It would also force upgrading `configd-linz` from
   per-key independent registers to a multi-register strict-serializable checker - a major harness
   rewrite, not an extension - plus a new TLA+ commit-protocol spec.
4. **Disclaiming keeps the existing model intact under sharding.** The monotonic sequence (ADR-0004) and
   the edge cursor (ADR-0035) are already per-group; sharding instantiates N independent counters -
   exactly what those ADRs designed for ("a global counter would reintroduce the single-writer
   bottleneck"). A client reading across shards composes per-shard cursors (a cursor vector); per-shard
   monotonic-read and read-your-writes survive unchanged within each shard. Nothing the contract
   promises breaks.
5. **Disclaiming is reversible; building is not cheaply reversible.** If a concrete future requirement
   appears, this can be re-opened with that requirement as justification. Building 2PC and finding it
   unneeded would bake in locks/intents/oracle and a harder harness forever.

## Prior-Art Mechanism Borrowed

**The etcd / ZooKeeper / Consul / AWS AppConfig camp - single-group atomic multi-key, no cross-shard
transactions.** etcd `Txn`, ZK `multi()`, and Consul KV `Txn` are all *intra-group* (single Raft / single
ensemble) - which Configd matches via single-shard `BATCH`. None of them offers *cross*-shard atomicity
either, so disclaiming it is parity, not a gap. AppConfig/LaunchDarkly/Unleash deploy a config profile
(one versioned document) atomically and trade strict cross-key consistency for availability by design.
The build camp - TiKV Percolator plus a timestamp oracle; CockroachDB write-intents plus
parallel-commits; Spanner 2PC-over-Paxos plus TrueTime commit-wait (~7 ms) - is rejected: those are
distributed transactional databases for which cross-shard atomicity is the product. The only
cross-group correctness work worth doing is stable, epoch-versioned scope->group routing (TiKV
`RegionEpoch{ConfVer,Version}`, owned by the topology decision), not transactions.

## Rejected Alternative: Build Distributed Transactions

2PC-over-Raft / Percolator / parallel-commits / Spanner-2PC. Rejected: contradicts the stated non-goal;
no named requirement demands it; a multi-month cost with a new durable coordinator plus a lock/intent
subsystem plus a new in-doubt failure surface; at least 2 cross-shard round trips (plus an oracle round
trip) over the <150 ms budget; forces a per-key -> strict-serializable rewrite of the linearizability
harness; the prior-art camp Configd already chose rejected exactly these mechanisms.

## Consequences

- Sharding ships shard-routing and per-shard Raft groups but no cross-shard coordinator; per-shard
  sequence (ADR-0004) and per-shard edge cursors (ADR-0035) are the model; cross-shard reads compose
  per-shard cursors and are not a consistent snapshot.
- **Single-group `BATCH` should be wired as a hard co-delivery requirement of sharding, not "ideally":**
  it remains codec-only today (`CommandCodec.TYPE_BATCH=0x03`, no endpoint on `HttpApiServer`); shipping
  the disclaimer without a usable multi-key primitive leaves neither cross-shard atomicity nor
  whole-keyspace atomicity - strictly worse than a single group. This gap is still open.
- **N=1 stays the default below the throughput threshold**, so small deployments keep whole-keyspace
  `BATCH`; sharding is an operator choice made when measured throughput forces N>1.
- **A write-time cross-shard-multi-key guard** (warn or reject a `BATCH` whose keys resolve to more than
  one shard) should ship alongside sharding so co-location's unenforced obligation is observable, not a
  silent data bug - this guard is not built yet either.
- **ADR-0023's "cross-shard 2PC over Raft" migration bullet is struck and superseded** by this decision.
- Doc precision (not a break): the edge-staleness commit-timestamp is per-shard-leader; cross-shard
  staleness comparisons inherit per-leader NTP skew (<=50 ms) - already the only residual error term in
  the single-group case, now across more leaders.

## Known limitations

- **ADR-0023 literally lists a cross-shard 2PC coordinator as a later step.** That line was an offhand
  migration note, not a requirement, and it conflicts with the stated non-goal and the earlier design
  research. This ADR supersedes it.
- **An etcd/Consul/ZK user expects an atomic multi-key `Txn` and will be surprised.** Mitigated by
  parity plus honesty: those `Txn`s are intra-group, which Configd matches via single-shard `BATCH`; the
  only thing disclaimed is *cross-shard* atomicity, which none of them offer either. Docs need to tell
  the etcd refugee: your `Txn` maps to our co-located `BATCH`.
- **A future requirement could force building it.** Handled by the re-open trigger below - disclaiming
  is reversible.
- **Co-location creates hotspots - forcing related keys onto one shard concentrates load.** Bounded:
  co-location is opt-in and applies only to the small coupled-key minority, not the bulk keyspace; the
  throughput target is met by sharding the independent majority. This is a routing input for the
  partitioning decision (honoring a co-location/scope-affinity hint), not a blocker.
- **Disclaiming is strictly weaker than etcd for the common single-shard deployment.** Mitigated by
  defaulting to N=1: a deployment that fits one shard gets whole-keyspace `Txn` under etcd (and
  single-group Configd); hashing across N by default would lose that. Defaulting to N=1 until throughput
  forces N>1 keeps the parity claim true at every scale.
- **Co-location is a silent-partial-write footgun, not simply "correct data modeling."** Mitigated in
  design by the write-time guard described above - but that guard is not built, so today this remains an
  unenforced obligation the system imposes on the operator.
- **The escape-hatch primitive does not exist on any client path.** `BATCH` is a hard co-delivery
  requirement in this decision, not an open question - but it has not shipped, so the escape hatch is
  currently theoretical, not available.
- **"Reversible" is asymmetric - migration debt lives in customer data models.** Clients who co-locate
  unrelated keys for atomicity become anti-partition artifacts a later build-out would have to
  un-co-locate; an operator cannot file a re-open trigger before hitting the footgun first. Mitigation:
  the write-time guard (once built) would surface the need early, and staying at N=1 at small scale
  avoids forcing co-location until sharding is genuinely required.

## Re-Open Trigger (the named requirement that would justify building it)

A concrete, named workload requiring all-or-nothing writes across keys that cannot be co-located -
genuinely independent high-throughput shards bound by a hard cross-shard invariant, where co-locating
them would violate the throughput/partitioning goal. Absent that, remain disclaimed.

## Verification Extension (additive under disclaiming, no rewrite)

- Keep `configd-linz` checking each key as an independent linearizable register (ADR-0032) - sharding
  does not change per-key registers.
- Assert per-shard monotonic sequence and per-shard edge-cursor monotonic-read / read-your-writes hold
  independently per shard.
- Add a negative test pinning the disclaimer: a cross-shard read is explicitly non-atomic.
- Add a single-group `BATCH` atomicity test (all-or-nothing within one shard) once `BATCH` is wired.
- No new commit-protocol TLA+ and no strict-serializable multi-register checker - these are exactly the
  costs disclaiming avoids.

## Open Questions for the Operator

1. **Wire single-group `BATCH`?** Disclaiming cross-shard transactions is materially stronger once the
   escape hatch's atomicity primitive ships alongside sharding. This has not happened yet - approve
   scoping `BATCH` into the work.
2. **Co-location vs partitioning tension** (input to the partitioning and topology decisions): confirm
   routing honors a co-location/scope-affinity hint for the coupled-key minority.
3. **Messaging to etcd/Consul/ZK users:** confirm docs state single-shard `BATCH` is the analog of
   `etcd Txn` / `consul Txn` / `zk multi()`, and that cross-shard atomicity is offered by none of them.

## Related

ADR-0004 (per-group sequence), ADR-0019 (consistency model - why per-group sequence numbers),
ADR-0033 (commit-confirmed ack), ADR-0035 (per-group cursor / HLC descope), ADR-0023 (superseded
migration bullet), `docs/operations/consistency-contract.md`.
