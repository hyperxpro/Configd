# Watches under Configd's sharded consensus -- analysis (the crux)

Research from 2026-06-27/28, docs-only at the time. Companion to `prior-art.md` (mechanisms borrowed) and
`recommendation.md` (the RFC-ready design and the staging call).

etcd's watch is clean because of one global revision. Configd has no global revision and cannot get one
cheaply, that is the entire difficulty, and this document works through its consequences. Every section
answers one of the open design questions with code evidence, and resolves the three central forks: the
multi-shard (prefix) watch, per-shard cursor-vector resumption, and edge-served vs. leader-served.

The headline, stated once up front so the rest reads in context:

> A Configd watch is a client-facing, prefix-filtered, multiplexed projection of the edge fan-out plane
> that already exists. The fan-out plane (already hardened, CI-green at N=1) is already a cursor-based,
> resumable, failover-safe, snapshot-catch-up streaming protocol. Most of "watches" is built. What is
> missing is (a) a thin client-facing protocol veneer (multiplex + per-watch prefix filter + an
> event-shaped API), and (b) the per-shard cursor vector that makes resumption work when N > 1. Both are
> analyzed below.

---

## 0. The building blocks that already exist (so this reuses, not reinvents)

| Concern | Existing mechanism | Where |
|---|---|---|
| The change stream | `CommitNotification` (applied-mutation seq `S`, commit-ts, signed delta) | `configd-distribution-service/.../CommitNotification.java` |
| Cursor + replay boundary | `CommitNotificationSource.readSince(cursor) → Ok(contiguous run) \| Gap(oldestRetainedSeq)` | `CommitNotificationSource.java:40-113` (ADR-0034) |
| Hot buffer (drop-oldest) | `FanOutBuffer` -- bounded ring, single-writer (owner thread), lock-free readers, `lastEvictedSeq` watermark | `configd-distribution-service/.../FanOutBuffer.java:61-217` (ADR-0036) |
| Per-shard fan-out | `ConfigdServer.registerShardedFanOut` -- one `FanOutBuffer`+`Compactor` per shard, fed by that group's commit listener on its own owner thread (single-writer-per-buffer, no lock) | the multi-shard server wiring |
| Wire session (server) | `FanOutSessionCore` -- `STREAMING`/`CATCHUP` states; `onSubscribe`→`SubscribeOk(latestSeq, TAIL\|SNAPSHOT_FIRST)`; `tick()` drains `readSince` into `NOTIFY`; `onCursorAck` | `.../fanout/FanOutSessionCore.java:178-239,371-427` |
| Wire protocol | `EdgeFrame` sealed family + `EdgeFrameCodec` (EDGE_WIRE_VERSION 0x01, length-prefixed, CRC32C) -- `SUBSCRIBE / SUBSCRIBE_OK / NOTIFY / SNAPSHOT_{BEGIN,CHUNK,END} / CURSOR_ACK / HEARTBEAT / ERROR_CLOSE` | `.../wire/EdgeFrame.java`, `EdgeFrameCodec.java` |
| Subscribe already carries prefix + resume + failover | `Subscribe(fullStore, prefixes, resumeCursor, failoverResumeCursor, edgeId)`; `effectiveResumeCursor() = max(resumeCursor, failoverResumeCursor)` | `EdgeFrame.java:50-88` |
| Resume decision | `decideMode(cursor, latest)`: `Gap`→`SNAPSHOT_FIRST`; `cursor==0`→`SNAPSHOT_FIRST`; else `TAIL` | `FanOutSessionCore.java:212-239` |
| Catch-up (too-old) | chunked snapshot `BEGIN/CHUNK/END`, `PendingSnapshotTransfer` backpressure pacing, cutover only after `END` accepted | `FanOutSessionCore.java:347-427` |
| Freshness | `StalenessTracker` frontier = `max(commitTs(lastApplied), serverNow(heartbeat where latestSeq==cursor))` | `configd-edge-cache/.../StalenessTracker.java:14-46` (ADR-0039) |
| Endpoint | `FanOutServer` -- `--edge-port`, mTLS cert-DN identity, `maxSessions` admission, per-conn bounded queue | `configd-server/.../fanout/FanOutServer.java` |
| Backpressure / abuse | `SlowConsumerGovernor` ladder HEALTHY→SLOW→CATCHUP→QUARANTINED→UNHEALTHY; 10-code `ErrorCode` taxonomy | `.../fanout/SlowConsumerGovernor.java`, `.../wire/ErrorCode.java` |

A second, dormant foundation also exists -- `WatchService` + `WatchCoalescer` + `SubscriptionManager`
(ADR-0006/0020). §11 explains why it is the wrong base to build on.

---

## 1. The sequence model under sharding -- a cursor vector, not a number

### 1.1 What `S` is, and why it is the right cursor

The cursor `S` is the ADR-0033 applied-mutation sequence: a per-state-machine monotonic counter that
increments only on mutating applies (PUT/DELETE/BATCH), not the Raft log index, and not on no-ops or
reconfigurations. Critically for resumption, `S` is deterministic and leader-independent: every replica
applies the identical committed log in the identical order and classifies each entry identically, so the
`S` assigned to a given committed mutation is the same on every replica. This is the per-shard analogue of
etcd's global revision (§1.3 of `prior-art.md`): a number that names the same point in a shard's history
on every member, which is exactly what makes cursor-resume safe across leader change (§7).

Two things ride on `S` and must not be confused:
- Identity / resume rides on `S` (deterministic), safe to resume on.
- Freshness rides on the commit timestamp (leader wall-clock, ADR-0035/0039), not deterministic across
  leaders, used only for the staleness frontier, never for cursor identity. Keep these separate.

### 1.2 Why there is no global revision (and why one should not be built)

etcd's single revision exists because etcd has a single Raft log. Configd shards: each shard is an
independent Raft group with its own log and its own `S` counter (ADR-0004, ADR-multiraft-cross-shard
§Decision). There is no cross-shard log, so there is no number that orders events across shards.

Could one synthesize one, an N-way sequencer/merger in front of fan-out that stamps a global order? No,
and deliberately not. Such a sequencer is a single serialization point for all shards, exactly the
single-writer bottleneck sharding exists to remove (ADR-0019 "Why per-group sequence numbers": "A single
global sequence number across all Raft groups would require cross-group coordination on every write,
reintroducing the single-writer bottleneck"). The multi-Raft ADRs note such a merger would be required to
fabricate global order and is therefore not built. Configd trades global order for aggregate throughput.
Watches inherit that trade.

### 1.3 Therefore: the watch cursor is a per-shard vector

A watch's resume position is a cursor vector:

```
cursor_vector = { gid_0 : S_0, gid_1 : S_1, …, gid_{N-1} : S_{N-1} }
```

where `S_i` is the applied-mutation seq of shard `gid_i` the client has already processed. This is not a
new invention, it is exactly what ADR-multiraft-cross-shard already specifies: "cross-shard reads ...
compose per-shard cursors (a cursor vector); per-shard monotonic-read (INV-M) and read-your-writes
(INV-RYW) survive unchanged within each shard." The fan-out plane already maintains a per-shard
`FanOutBuffer` with its own `S`; the vector is just the set of those per-shard cursors held by one client.

**At N=1 the vector has one element and degenerates to a scalar**, byte-identical to a single-group
cursor. The vector's complexity (and the loss of global order) materializes only at N > 1, which is
itself the gated sharding feature. This degeneracy is the spine of the staging call (§`recommendation.md`).

---

## 2. The central design question -- prefix watches scatter across shards

### 2.1 The routing function hashes the full key, so a prefix is not local

`StaticShardMap.shardFor(scope, key)` hashes both the scope ordinal and every character of the key
(FNV-1a, SplitMix64 avalanche, `floorMod shardCount`):

```java
// configd-replication-engine/.../StaticShardMap.java:56-79
long h = FNV_OFFSET_BASIS;
h ^= scope.ordinal();  h *= FNV_PRIME;            // scope folded first
for (int i = 0; i < key.length(); i++) {           // then EVERY key character
    h ^= key.charAt(i); h *= FNV_PRIME;
}
… SplitMix64 finalizer …
return Math.floorMod(h, shardCount);
```

Because the whole key is hashed, two keys sharing a prefix (`cfg/a`, `cfg/b`) hash independently and land
on different shards. A prefix is therefore not a shard-local unit:

```
"cfg/a" → shard 2     "cfg/b" → shard 0     "cfg/c" → shard 1   …
```

This is confirmed in production: `getPrefix` is implemented as a scatter-gather across all shards
(`ConfigdServer.shardedConfigReader`, `ConfigdServer.java:1460-1501`: "a getPrefix SCATTER-GATHERS across
all shards (a prefix's keys may hash to different shards) and merges"; `ShardedRoutingTest` asserts a
6-key prefix is merged from its several owning shards). The data model is a flat string keyspace per
`ConfigScope` {GLOBAL, REGIONAL, LOCAL}; the `/` in a key is not a routing delimiter.

### 2.2 The consequence for watches

- A single-key watch is a single-shard watch, it touches exactly `shardFor(scope, key)`.
- A prefix watch (and a full-store watch) is a scatter across all N shards, the prefix's keys may live on
  any/every shard, so the watch must observe every shard's change stream and filter to the prefix. There
  is no way to serve a prefix watch from one shard.

This is a hard, honest limitation of hash sharding, and it is the central thing the watch design must
handle. Two structural alternatives were considered and rejected as the near-term design:

| Alternative partitioning | Prefix locality? | Why rejected for the near-term design |
|---|---|---|
| Hash full `(scope,key)` (built, sim-verified) | No, prefix scatters | This is the shipped, hardened design. Even load. Accept scatter-gather. |
| Range-partition by key | Yes, prefix is contiguous | Creates hotspots (sequential keys → one shard); needs split/merge machinery; a different sharding design, not sim-verified. A later redesign. |
| Hash a key domain/first segment (co-location key) | Partial, a chosen domain co-locates | `ConfigScope` is only 3 values (too coarse); hashing a key-prefix component changes `shardFor` and breaks the verified routing + even distribution. Deferred. |

**Resolution:** embrace scatter-gather for prefix/full watches on top of the existing partitioning;
recommend the range/domain-partition redesign only as a later option if measured prefix-watch fan-out
cost proves too high (§`recommendation.md`). Note the co-location escape hatch already in the contract
(ADR-multiraft-cross-shard): keys an application wants ordered/atomic should be co-located under one
scope, so one shard, and then they are also a single-shard watch with per-shard total order.

### 2.3 What a multi-shard watch can and cannot promise

Because a prefix watch spans shards and there is no cross-shard order (§1.2):
- Per-key order holds: a key lives on exactly one shard; its events come from one monotonic `S`; the
  client sees that key's changes in commit order.
- Per-shard order holds: all events from one shard arrive in its `S` order (the stream's contiguity, §6).
- Cross-shard / global order does not hold: events from different shards interleave arbitrarily. If a
  client PUTs `A` (shard 0) then `B` (shard 1), a prefix watch covering both may deliver the `B`-event
  before the `A`-event. This is fundamental and unavoidable under sharding.

So the merge of per-shard substreams into the client's single watch stream is a union, not a sorted merge,
there is no key to sort across shards on. Each event is tagged `(gid, S)`; the client gets per-key and
per-shard order, nothing stronger. This is stated precisely as the guarantee surface in §6.

---

## 3. How the driver manages a multi-shard watch (merge + topology)

The driver (Rust/Go/Python/Java, the RFC must specify this identically) handles a prefix/full watch as:

1. **Learn N and the topology.** The shard map is fixed-at-deploy (`StaticShardMap`, epoch 0 today); N is
   stable for the connection's lifetime. The driver fetches `{N, shard ids, endpoints}` from a
   cluster-info call and caches it. (Epoch-versioned resharding is a later concern; N does not change
   under the driver's feet.)
2. **Open the watch.** Two server-side topologies, depending on whether one server materializes all
   shards:
   - **Aggregating endpoint (near-term / N=1 / full-store edge):** the client sends one
     `WATCH_CREATE(prefix, cursor_vector)` to an endpoint that materializes all shards (a full-store edge,
     or, at N=1, the single group). That endpoint does the scatter-gather internally (it already does for
     `getPrefix`) and returns one multiplexed event stream tagged by `(gid, S)`. The driver does no
     cross-server merge. This is the simple, recommended near-term path.
   - **Sharded endpoints (a later, sharded edge):** if edges each serve only a subset of shards, the
     driver opens substreams to enough edges to cover all N shards and merges client-side. Each substream
     carries its shards' `(gid, S)` events; the driver unions them.
3. **Maintain the cursor vector.** The driver updates `S_i` as events for shard `gid_i` arrive, and
   persists/uses the whole vector as the resume token.
4. **Resume** (reconnect/failover) by re-sending `WATCH_CREATE(prefix, cursor_vector)` with the full
   vector; the server resumes each shard's substream from its component (§7).
5. **Merge for the application** as a union, surfacing `(gid, S, key, kind∈{PUT,DELETE}, value/delta)` per
   event; promise per-key order; do not promise cross-shard order (§6).

**The current wire already mostly supports step 2's aggregating path:** `Subscribe` carries `prefixes` and
a `resumeCursor`. The missing pieces are (a) a vector resume (a scalar `resumeCursor` is fine at N=1,
needs widening at N>1) and (b) the client-facing multiplex/event veneer (the edge stream was a single
full-chain session, not a per-watch multiplexed API). Both are specified in `recommendation.md`.

---

## 4. The major fork -- edge-served vs. leader-served

A watch can be served from the shard leaders (authoritative consensus nodes) or from the edge fan-out
plane (replicas that already receive every committed change). Both analyzed; recommendation: edge-served.

### 4.1 Leader-served

The client subscribes directly to each owning shard leader; the leader runs a `FanOutSessionCore`-style
session off its per-shard `FanOutBuffer` (the watch is "bound to primary", on the owner thread).

- **Pro:** authoritative and freshest (no extra hop); the dormant `WatchService` is already wired to the
  state-machine listener (`ConfigdServer:632`) so the seam exists.
- **Con, it taxes the scarce resource.** Every watching client becomes a session on a consensus node,
  consuming connection, serialization, and `tick()` work on the owner thread that also drives apply. The
  entire project exists to raise consensus throughput; loading the write plane with fan-out is working
  against that goal. A prefix watch holds a session on every shard leader (N connections), and those
  sessions must migrate on every leader failover. This is the model that does not scale to large watch
  fleets.

### 4.2 Edge-served (recommended)

The client subscribes to edge nodes, which already hold a replicated, cursor-tracked, per-shard copy of
the store via the fan-out plane, and serve the watch from that local materialized stream.

- **Pro, it reuses the entire built plane.** A watch becomes the edge protocol plus a per-client prefix
  filter plus a multiplex layer. `FanOutSessionCore`, snapshot catch-up, `SlowConsumerGovernor`,
  `StalenessTracker`, the mTLS endpoint, all already hardened and CI-green at N=1.
- **Pro, it offloads the consensus plane entirely**, and scales horizontally: add edge nodes, not
  consensus nodes. This is precisely aligned with the project's reason for existing.
- **Pro, it is the etcd precedent.** etcd serves watches from any member, including a lagging follower,
  because the follower applies the same log, same revision order, same ordered events (`prior-art.md`
  §1.6). The Configd edge is to Configd what a follower is to etcd, except the edge is a dedicated
  read/watch-scaling tier, which is strictly better for offloading writes. The edge receives the same
  per-shard `CommitNotification` stream in the same `S` order, so the same ordered events; its only cost is
  bounded recency, never order.
- **Con, bounded extra staleness.** The edge lags the leader by the fan-out latency (measured p99
  commit-to-boundary about 4 ms in an early probe; the `StalenessTracker` frontier exposes it, ADR-0039;
  the consistency contract already budgets under 500 ms p99 edge staleness, ADR-0019). A watcher needing
  read-after-write freshness on a specific key uses the linearizable strong-read path for that key (ADR-0019
  ReadIndex), not the watch.
- **Con, the edge was N=1-only at the time of this research.** The sharded edge client (cursor-vector plus
  multi-shard scatter-gather) was explicitly deferred at the time (the edge endpoint fail-closed at N>1
  unless `-Dconfigd.edge.allowPartialShardView=true`). So edge-served watches worked at N=1 first; the
  multi-shard case landed with the sharded edge later (this staging is in fact what shipped).

**Recommendation: edge-served as the primary model**, with a leader-served fast-path offered only as an
optional later capability for latency-critical watchers (acknowledging it taxes consensus). The decision
hinges on the project's own thesis: consensus is the scarce resource; serve watches from the tier built to
scale, not the tier built to agree.

### 4.3 The corollary: prefix filtering happens at the edge, post-verification

This is forced by ADR-0038 and is a key integration fact. The fan-out path does not filter or coalesce
server-side, it streams the full, verbatim, leader-signed delta chain to every subscriber, because
(ADR-0038 §Decision): "Coalescing rewrites payloads ... the fan-out service cannot re-sign them ... Prefix
filtering breaks the chain ... a compromised or buggy relay could silently suppress arbitrary keys ... a
freshness/suppression attack that per-delta signatures exist to make detectable." The edge receives
everything, verifies the signed chain, and then applies its prefix filter (the applied-version cursor
advances over non-matching mutations; only matching payloads are stored/served, ADR-0038 §Decision 2).

So a client prefix-watch is filtered at the edge, on the already-verified stream, clean layering:

```
leader  ──full verbatim signed chain (integrity)──▶  edge  ──filtered, multiplexed watch events──▶  client
        (no server-side filter/coalesce, ADR-0038)         (filter post-verification; the watch veneer)
```

This both reuses the security property server-to-edge and isolates the new client-facing veneer to the
edge. It also raises a real trust question, analyzed next.

---

## 5. The trust tradeoff filtered watches reintroduce (a key finding)

ADR-0038's threat is a compromised relay between the signing leader and the edge: it must not be able to
suppress a matching delta undetectably. The fix was "send the full signed chain; the edge detects any
chain break." A filtered client watch reintroduces exactly this threat one hop later, at edge-to-client,
because the edge sends the client only matching events and asserts "these are all of them up to `S`." A
malicious/buggy edge could omit a matching event; the client cannot tell from a filtered stream alone.

Resolution (reusing ADR-0038's own analysis and named upgrade path):

- **Trust domain matters.** The common case is a client talking to its own edge/sidecar in the same
  operator trust domain, mTLS-authenticated. There the edge is not the adversary; edge-side filtering is
  appropriate. Residual risk is wholesale stream-stall (not selective suppression), which the
  `StalenessTracker` frontier plus commit-timestamp clock already detect and surface (ADR-0038
  §Consequences, ADR-0039).
- **Untrusted edge, two honest options:** (a) the client subscribes to the full chain and filters locally,
  full end-to-end verification, at the cost of receiving the whole write firehose (the ADR-0038 bandwidth:
  about write-stream rate, ~80 Mbit/s typical / ~800 Mbit/s burst per subscriber); this is the "client
  behaves like an edge" mode and suits a client that watches a broad prefix anyway. Or (b) the
  signed-skip-evidence design ADR-0038 already names as the upgrade path: "leader-signed skip-evidence
  (e.g., signed per-range Merkle summaries)" lets the edge prove "nothing matching in `(S_a, S_b]`" without
  sending payloads, selective-suppression-proof and cheap. Out of scope for the near-term design; recorded
  as the named later extension.

**Near-term: filtered watches served from a trusted edge**, with the trust boundary documented honestly,
and full-chain-verify-locally available for clients that need end-to-end verification. This is a rigorous,
non-hand-waved resolution that inherits ADR-0038's security envelope rather than re-litigating it.

---

## 6. Delivery + ordering guarantees under sharding -- stated precisely

This is the honesty section. Configd's watch guarantee surface is exactly the ADR-multiraft-cross-shard /
ADR-0019 surface, projected onto a change stream:

| Guarantee | Holds? | Basis |
|---|---|---|
| Per-key order -- a single key's events arrive in commit order | yes | key → one shard (`shardFor`) → one monotonic `S`; stream is `S`-contiguous |
| Per-shard order -- one shard's events arrive in `S` order | yes | one Raft log per shard; contiguity of `readSince` |
| Atomic per shard-commit -- all keys in one `BATCH` arrive together | yes | one Raft entry = one `CommitNotification` = one `NOTIFY` unit (ADR-0019 BATCH, ADR-0038 verbatim) |
| Reliable / no-gap within the retained window -- no committed change to a watched key is skipped while the cursor stays in-window | yes | `readSince`→`Ok` contiguous or `Gap` (never silent skip), ADR-0034 |
| At-least-once with `(gid,S)` dedup -- resume/failover may redeliver from the resume cursor; client dedups by `(gid, S)` | yes | deterministic per-shard `S` (§1.1); stream contiguity; mirrors etcd dedup-by-revision |
| Bounded-staleness freshness, per shard -- the watcher knows how fresh each shard's stream is | yes | `StalenessTracker` frontier, ADR-0039; budget under 500 ms p99, ADR-0019 |
| Cross-shard / global total order | no | no cross-shard log/sequencer (§1.2); the deliberate throughput trade |
| Cross-shard atomicity | no | disclaimed for writes (CrossShardWriteGuard); watches inherit it |
| A single scalar resume token | no | resume is a vector (§1.3) |
| Linearizable watch (read-after-write freshness via the watch) | no | neither does etcd, watch is ordered, not linearizable; use strong-read for freshness (ADR-0019) |

Two refinements worth stating in the RFC:
- "Exactly-once over effect" within a session (ADR-0034): across a buffer overflow the consumer still
  observes every committed mutation's effect exactly once (the snapshot resync, §8, closes the hole), but
  across reconnect the contract is at-least-once plus dedup, because a resume may replay from the cursor.
  State the weaker (at-least-once) contract in the RFC so drivers dedup.
- Read-your-writes on a watched key is achievable per-shard: a write ack returns `(gid, S)` of the commit
  (ADR-0033/0019: "client's VersionCursor.version = S"); the client can ensure its watch cursor for `gid`
  starts at or before `S`, guaranteeing it observes its own write on that key. This is per-shard RYW
  (INV-RYW), not cross-shard.

---

## 7. Leader failover -- no-miss / no-dup

A watch (edge-fed or leader-served) on a shard must survive that shard's leader failing over. The
mechanism is the deterministic per-shard `S` (§1.1) plus the existing resume protocol:

1. The new leader has applied the same committed log, so it produces the same `S` sequence for
   `S ≤ commitIndex`. `S` is leader-independent (the per-shard analogue of etcd's "revision denotes the
   same point on every member").
2. The client/edge re-subscribes at its last cursor for that shard; the new leader serves
   `readSince(S_i)` → the contiguous run `S > S_i` (or `Gap` → snapshot, §8). No miss (the cursor names
   the exact resume point), no dup beyond the resume boundary (stream contiguity; the client dedups by
   `S`).
3. The wire already has the endpoint-failover form of this: `Subscribe.failoverResumeCursor` with
   `effectiveResumeCursor() = max(resumeCursor, failoverResumeCursor)` (`EdgeFrame.java:75-82`) so a client
   moving to a new endpoint cannot regress its cursor. At N > 1 this becomes a per-shard max-merge of the
   vector components.

The only failover subtlety under sharding: a prefix watch holds substreams to multiple shards, and those
shards fail over independently. The cursor vector makes this clean, each component resumes on its own
shard's timeline; a failover on shard 3 does not perturb the cursor or stream for shard 1. This is a
genuine advantage of the vector model over a single global cursor (which would have to be rebuilt wholesale
on any one shard's failover).

---

## 8. Compaction / too-old -- per-shard snapshot resync (partial, not global)

Each shard's `FanOutBuffer` is a bounded ring (drop-oldest); its `Compactor` retains a bounded snapshot
window. A resuming client whose cursor component `S_i` has fallen behind shard `i`'s `oldestRetainedSeq`
hits the same wall as etcd's compaction, but Configd handles it better for the client:

- `readSince(S_i)` returns `Result.Gap(oldestRetainedSeq)` (never a silent skip, ADR-0034).
- `decideMode` flips that shard's session to `SNAPSHOT_FIRST`; the server transfers a prefix-filtered
  snapshot of that shard (`SNAPSHOT_BEGIN/CHUNK/END`, backpressure-paced, cutover only after `END`
  accepted) and resumes tailing from `snapshotSeq`. ADR-0020's bootstrap already specifies prefix-filtered
  snapshots so a watcher gets only its prefix's keys, not the whole shard.
- **Partial resync is the vector payoff:** only the lagging shard's substream snapshots; the other
  shards' substreams continue uninterrupted. etcd cancels the whole watch on compaction and makes the
  client re-list everything; Configd re-lists one shard, inline, and keeps the rest streaming.

Contrast for the RFC: where etcd returns `ErrCompacted`+`canceled` and the client rebuilds its baseline,
Configd performs the rebuild for the client as an in-band per-shard snapshot. The driver still must handle
a `WATCH_CANCELED(unrecoverable)` terminal case (e.g., the replay source itself cannot cover the range,
`ErrorCode.GAP_UNRECOVERABLE`), but the common too-old case is self-healing.

### 8.1 Idle filtered watchers must advance their cursor (or compaction strands them)

A subtle, important derivation. The cursor advances over all commits on a shard, but a prefix watcher
receives only matching events (ADR-0038: "non-matching mutations advance the version chain without storing
payloads ... the applied-version cursor is global"; `WatchService.dispatchEvent` likewise advances the
cursor even when the filtered match set is empty, `WatchService.java:272`). So an idle prefix-watcher on a
busy shard (lots of non-matching writes) receives no events, and if its resume cursor does not advance
over those non-matching commits, the shard compacts past it, an avoidable `Gap`/snapshot on reconnect.

The fix is already in the protocol: the `HEARTBEAT(latestSeq, serverNowMillis)` frame is exactly etcd's
progress notification / bookmark, it carries the shard's latest `S` with no events, letting the watcher
advance its resume cursor without matching events. The RFC must require: a filtered watcher advances its
per-shard cursor component on bookmarks, not only on delivered events, or busy non-matching traffic will
strand it. (This is precisely why etcd added `progress_notify`, §1.5 of `prior-art.md`, and under sharding
it matters more, because
each shard compacts on its own.)

---

## 9. Transport -- streaming, not long-poll

| | Streaming (etcd-style; what Configd's edge plane already is) | Long-poll (Consul-style) |
|---|---|---|
| Fit with built plane | Exact -- EDGE_WIRE_VERSION 0x01 is already a persistent streaming session | New, redundant transport |
| Latency | Server-push, no poll interval | Bounded by `wait`; re-establish each cycle |
| Flow control / catch-up | `CURSOR_ACK` backpressure + snapshot catch-up already built | None; can't express snapshot transfer cleanly |
| Multiplex many watches | One mTLS conn, many `watch_id`s (like etcd) | One request per watch per cycle |
| Resume semantics | Replayable cursor (vector) | Edge-triggered re-read; index can reset |

**Recommend streaming.** It is the fan-out plane; it matches the planned binary driver protocol; it
supports push, flow-control, snapshot catch-up, and `watch_id` multiplex that long-poll cannot. A
long-poll/HTTP compatibility surface (a la etcd's gRPC-gateway) is a possible later convenience for
HTTP-only clients, but the canonical watch, and the RFC, should be streaming.

---

## 10. Multiplexing -- `watch_id` over the session (borrow etcd directly)

The fan-out session was, at the time, one full-store stream per connection. A client-facing watch API
needs many watches per connection, each with its own `(prefix, cursor_vector)`, demultiplexed by a
client-assigned `watch_id`, exactly etcd's `WatchCreateRequest.watch_id` mechanism (§1.2 of
`prior-art.md`). This is a thin layer over the existing session:
`WATCH_CREATE(watch_id, prefix, cursor_vector, flags)`, `WATCH_CANCEL(watch_id)`,
events tagged with `watch_id` and `(gid, S)`, `WATCH_PROGRESS(watch_id)` riding the heartbeat/bookmark. It
does not change consensus or fan-out; it is pure client-facing veneer at the edge.

---

## 11. Which foundation -- the edge plane, not the dormant `WatchService`

There are two server-internal candidates. They embody different and partly conflicting models:

| | `WatchService`/`WatchCoalescer`/`SubscriptionManager` (ADR-0006/0020) | Edge fan-out plane (ADR-0034/0037/0038/0039) |
|---|---|---|
| Cursor | in-memory `version` per watch (`WatchService.java:81`) | persisted, replayable `S`; `Result.Ok\|Gap` boundary |
| Coalescing | yes, `WatchCoalescer` collapses a 10 ms/64 window into one event at the latest version | no, verbatim signed chain (ADR-0038 forbids coalescing) |
| Resumable across disconnect/restart | no, cursor is process memory; no replay | yes, `readSince` + snapshot catch-up |
| Failover-safe | no | yes, `failoverResumeCursor`, deterministic `S` |
| Too-old handling | none | snapshot resync (§8) |
| Wire protocol | none (in-process listener) | EDGE_WIRE_VERSION 0x01, mTLS endpoint |
| Sharding | per-node, single listener | per-shard `FanOutBuffer` |
| Status | dormant, wired to the listener (`ConfigdServer:632`) but no `register()` caller, no client API | hardened, CI-green at N=1 |

The `WatchService`'s coalescing directly conflicts with ADR-0038 (collapsing deltas produces bytes the
leader never signed, breaking signature verification and suppression-detectability). And it lacks every
property this design needs: resumption, failover, too-old/compaction handling. Build client watches on the
edge fan-out plane. The `WatchService`'s reusable idea is its prefix-registry plus `filterByPrefix` plus
per-watch cursor bookkeeping, that logic is a fine starting point for the edge-side watch-registry / filter
layer (§4.3, §10), but with coalescing dropped (or moved client-side, post-verification) and its scalar
cursor replaced by the per-shard vector. The dormant triad is otherwise superseded.

---

## 12. Summary of open questions (index)

- **Sharding / prefix watch** → §2 (scatter across all shards; per-key order yes, global no), §3 (driver
  merge = union tagged `(gid,S)`; aggregating-edge vs. sharded-edge topologies).
- **Revision/sequence under sharding** → §1 (per-shard cursor vector, deterministic `S`, no global
  revision and why one should not be built; reuse the per-shard buffers).
- **Leader failover** → §7 (deterministic `S` implies no-miss/no-dup; `failoverResumeCursor`; per-shard
  independence is the vector payoff).
- **Edge plane (served-from)** → §4 (edge-served recommended; leader-served taxes consensus), §4.3 (filter
  at edge post-verification), §5 (the trust tradeoff plus the signed-skip-evidence later path).
- **Delivery / ordering guarantee** → §6 (precise can/cannot table; at-least-once plus `(gid,S)` dedup;
  per-key yes, cross-shard no).
- **Compaction** → §8 (per-shard inline snapshot resync; partial, not global; idle-watcher bookmark
  requirement).
- **Transport** → §9 (streaming) + §10 (`watch_id` multiplex).

The recommended design, the staging call, and the effort/risk estimate follow in `recommendation.md`.
