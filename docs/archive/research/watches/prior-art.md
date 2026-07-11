# Watches -- prior art (mechanism brief)

Research from 2026-06-27/28, docs-only, no code, no implementation.

Purpose: extract the *mechanisms* (not summaries) of change-subscription in three mature systems -- etcd (the gold standard, studied hardest), Consul, and ZooKeeper -- so the Configd watch design
(`configd-analysis.md`, `recommendation.md`) can borrow what works and consciously diverge where
Configd's sharded architecture forces it to.

The one question that organizes everything: **how does a client that disconnects and reconnects
guarantee it missed no change?** Every system answers it differently, and the answer drives the whole
model (streaming vs long-poll, resumable vs re-read, history-bounded vs stateless).

---

## 1. etcd v3 -- revision-resumable streaming (the gold standard)

Sources (all re-fetched and quoted verbatim where identifiers matter):
`rpc.proto`, `kv.proto`, `error.go`, `client/v3/watch.go` (etcd-io/etcd `main`);
etcd.io docs (`learning/api`, `learning/api_guarantees`, `learning/data_model`,
`dev-guide/interacting_v3`, `op-guide/maintenance`, `op-guide/configuration`);
clientv3 godoc; Jepsen etcd 3.4.3. URLs in the appendix.

### 1.1 The MVCC revision model -- one global logical clock

etcd maintains **one** 64-bit cluster-wide counter, the **store revision**, *"incremented each time the
key space is modified … a global logical clock, sequentially ordering all updates to the store"*
(learning/api). It is **per-transaction, not per-key**: *"Each modification of cluster state, which may
change multiple keys, is assigned a global unique ID, called a revision"* (learning/why). Each key
additionally carries, in `mvccpb.KeyValue`:

- `create_revision` -- *"revision of last creation on this key."*
- `mod_revision` -- *"revision of last modification on this key."*
- `version` -- *"version of the key. A deletion resets the version to zero and any modification … increases its version."*

**This single global revision is the entire reason etcd's watch resumption is clean.** One number names
a point in the history of the *whole* keyspace, and -- because the revision is produced by the Raft log
and applied identically on every member -- that number denotes the *same* point on every member. Hold
this thought: **Configd cannot have this** (no global log → no global revision; see §1 of
`configd-analysis.md`).

### 1.2 The Watch API -- one stream, many watches, demuxed by `watch_id`

`rpc Watch(stream WatchRequest) returns (stream WatchResponse)` -- a long-lived bidirectional gRPC
stream. *"The input stream is for creating and canceling watchers and the output stream sends events"*
(api_reference_v3).

`WatchCreateRequest` (the fields that matter for a watch protocol):
- `bytes key` / `bytes range_end` -- half-open range `[key, range_end)`. `range_end` one-bit-greater than
  `key` ⇒ **prefix watch**; `range_end == \0` ⇒ all keys ≥ `key`.
- `int64 start_revision` -- *"an optional revision to watch from (inclusive). No start_revision is 'now'."*
  **This is the resume cursor.**
- `bool progress_notify` (§1.5), `repeated FilterType filters` (`NOPUT`/`NODELETE`), `bool prev_kv`,
  `int64 watch_id` (3.4+, client-assigned id), `bool fragment` (§1.4).

**Multiplexing:** the client assigns `watch_id`; the server echoes it on every `WatchResponse`
(*"All events sent to the created watcher will attach with the same watch_id"*). So **one TCP/gRPC stream
carries many independent watches**, demultiplexed by `watch_id`. A process with 500 watches opens *one*
connection, not 500.

`WatchResponse`: `watch_id`, `created`, `canceled`, `compact_revision`, `cancel_reason`, `fragment`,
`repeated mvccpb.Event events`.

### 1.3 Resumption -- `start_revision = last_seen + 1`, leader-independent

The whole resume protocol: on a broken stream, *"establish a new watch starting after the last revision
received in a watch event before the break, so long as the revision is in the history window"*
(api_guarantees, "Resumable"). Because `start_revision` is **inclusive**, the official client uses
`start_revision = last_seen_revision + 1` (`client/v3/watch.go`: `nextRev = lastEvent.ModRevision + 1`;
for a `rev==0` "now" watch it pins `nextRev = header.Revision` so a Put committed *during* the disconnect
is not missed).

The no-loss guarantee that backs it: *"Reliable -- a sequence of events will never drop any subsequence of
events within the available history window. If … a < b < c, then if the watch receives a and c, it is
guaranteed to receive b as long as b is in the available history window."*

**Why resumption survives leader change** *(synthesized -- etcd docs don't use the phrase, but it follows
directly)*: every member applies the same Raft log in the same order, so a given `start_revision` denotes
the identical point in history on every member. Resume works against *any* member, including a freshly
elected leader. A lagging follower may not *yet* have the newest revisions (recency cost) but never
serves them out of order (correctness preserved).

### 1.4 Compaction -- the "required revision has been compacted" case

`Compact(revision)` *"drops all information about keys superseded prior to a given keyspace revision."*
The history window is finite; the resume cursor can fall off the back of it.

When `start_revision < compact_revision`, the watch is **canceled**, not silently truncated:
`WatchResponse.canceled = true` *"if the start_revision has already been compacted"*, with
`compact_revision` set to the minimum available index and `cancel_reason` populated. The client-visible
error is, verbatim:

> `etcdserver: mvcc: required revision has been compacted` -- gRPC code `OutOfRange` (11),
> Go id `rpctypes.ErrCompacted`.

*"The client should treat the watcher as canceled and should not try to create any watcher with the same
start_revision again."* **Recovery** *(the implied procedure)*: issue a `Get`/`Range` to learn the
current `header.revision`, re-list current state, then re-watch from that fresh revision. etcd does
**not** re-list for you -- the client rebuilds its baseline. Auto-compaction
(`--auto-compaction-retention`) sizes the window so *"slow watchers can still catch up within the
retention window."*

> **Configd will improve on this** (`configd-analysis.md` §6): the too-old case is handled by an *inline
> per-shard snapshot resync*, so the server does the re-list for the client, per shard.

### 1.5 Ordering, delivery, fragmentation, progress

**Ordering / delivery** (api_guarantees -- quote the named properties, not labels like "exactly-once"
which etcd never uses):
- **Ordered** -- *"events are ordered by revision."*
- **Atomic** -- *"a list of events is guaranteed to encompass complete revisions. Updates in the same
  revision over multiple keys will not be split over several lists of events."* (Exception: fragmentation.)
- **Unique** -- *"an event will never appear on a watch twice"* (on a single live stream).
- On a live stream, Unique + Reliable ⇒ effectively exactly-once *within the window*; **across a
  reconnect the client dedups by revision** (resumes "after the last revision received"). Do not assert
  etcd "guarantees exactly-once" -- it's at-least-once-with-revision-dedup across reconnects.
- **No cross-watch total delivery order is promised** beyond what the shared monotonic revision implies:
  *"etcd does not ensure linearizability for watch operations. Users are expected to verify the revision
  of watch events to ensure correct ordering with other operations."*

**Fragmentation** -- opt-in `fragment` flag. When one revision's event set exceeds
`--max-request-bytes` (default 1.5 MiB) + 512 B gRPC overhead, the server splits it across successive
responses each marked `fragment=true`, clearing it on the final chunk; clientv3 transparently
re-assembles. Large *atomic* revisions still arrive as one logical unit.

**Progress notifications / bookmarks** -- `progress_notify` makes the server *"periodically send a
WatchResponse with no events … useful when clients wish to recover a disconnected watcher starting from a
recent known revision."* The empty response carries an up-to-date `header.revision`. Property:
**"Bookmarkable -- Progress notification events guarantee that all events up to a revision have been
already delivered."** This lets an **idle** watcher advance its resume point without events, so
compaction does not strand it. (Kubernetes exposes the same idea as the `BOOKMARK` watch event.) Caveat:
the progress revision is **node-local** -- a partitioned follower may report a lower revision than a
quorum read.

### 1.6 Where watches are served from

A watch is a stream held with **whichever member the client connects to** -- not leader-only *(strongly
implied: serializable ranges are "served locally without needing to reach consensus", and a follower
applies the same log → same revision sequence → same ordered events)*. Watch consistency **is ordering,
explicitly not linearizability**. Range/Get default to linearizable (ReadIndex, leader RTT);
serializable is opt-in and may be stale. **Takeaway for Configd: serving a watch from a non-leader,
possibly-lagging replica is the industry-standard, correct design** -- the replica's only sin is
recency, never order. This is the etcd precedent for Configd's *edge-served* watches.

---

## 2. Consul -- index-resumable long-poll (edge-triggered re-read)

Sources: developer.hashicorp.com `api-docs/features/blocking`, `.../consistency`.

**The handshake.** Most reads return `X-Consul-Index`, *"a unique identifier representing the current
state of the requested resource."* To long-poll: re-issue the same `GET` with `?index=N&wait=<dur>`; the
server *"will 'hang' until a change … occurs, or the maximum timeout is reached"*, then returns the
**current full value** plus a new `X-Consul-Index`.

**Edge-triggered, not an event log.** A blocking query says only *"the state at index > N differs; here
is the current value"* -- **you re-read the whole object and diff it yourself**. There is no event
stream and no per-change history. Spurious wakeups are explicit: *"the return of a blocking request is no
guarantee of a change … the timeout was reached or … an idempotent write that does not affect the result."*

**The index can go backwards -- the reset rule.** *"While indexes in general are monotonically increasing
… there are several real-world scenarios in which they can go backwards"* (snapshot restore, list-head
removal, version upgrade). Mandatory client rules:
- *"Implementations must check to see if a returned index is lower than the previous value, and if it is,
  should reset index to 0"* (and re-baseline).
- Only block again when `newIndex > oldIndex`; failing to do so *"may cause the client to miss future
  updates for an unbounded time."*
- *"Sanity check that their index is at least 1 after each blocking response"* (a `0`/`<1` index would
  busy-loop).

`wait` defaults to 5 min, max 10 min; the server adds up to `wait/16` jitter to avoid thundering herds.
Consistency modes: `default` (leader, tiny stale window at election), `stale` (any follower, *"within 50
ms of the leader … no upper limit"*), `consistent` (leader + quorum verify).

**Mechanism contrast vs etcd:** Consul's `index` *looks* like a resume cursor but delivers
**current-state re-reads, not a replayable event log**, and it can legitimately move backwards (forcing a
reset). It is a *level-triggered* signal ("something under here changed; go look") rather than etcd's
*replayable change history*.

---

## 3. ZooKeeper -- one-shot (and persistent) watches, NOT resumable

Sources: ZooKeeper Programmer's Guide; `AddWatchMode` / `ZooKeeper.addWatch` Javadoc (3.6/3.9).

**One-shot watches.** *"a watch event is one-time trigger … which occurs when the data for which the
watch was set changes … if you get a watch event and you want to get notified of future changes, you must
set another watch."* The structural consequence, stated by the guide: *"Because standard watches are one
time triggers and there is latency between getting the event and sending a new request … **you cannot
reliably see every change that happens to a node**. Be prepared to handle the case where the znode
changes multiple times between getting the event and setting the watch again."* You never miss the
**final** state (your re-read returns current data), but you can miss **intermediate** events.

**The ordering guarantee** (verbatim, "What ZooKeeper Guarantees about Watches"):
- *"A client will see a watch event for a znode it is watching before seeing the new data that
  corresponds to that znode."*
- *"Watches are ordered with respect to other events, other watches, and asynchronous replies."*
- *"The order of watch events … corresponds to the order of the updates as seen by the ZooKeeper service."*

**Watch types.** `getData()`/`exists()` set **data** watches; `getChildren()` sets **child** watches.

**3.6+ persistent / recursive** (`addWatch`, `AddWatchMode`): `PERSISTENT` *"does not get removed when
triggered"*; `PERSISTENT_RECURSIVE` *"applies not only to the registered path but all child paths
recursively … including children added later."* These remove the re-registration burden but **still
deliver signals-then-reread, not a resumable log.**

**Session semantics.** Watches are *"maintained locally at the ZooKeeper server to which the client is
connected. When a client reconnects, any previously registered watches will be reregistered and triggered
if needed."* On **session expiry**, watches (and ephemerals) are lost. There is **no "give me everything
since version X"** -- a watch is a bare signal plus a mandatory current-state re-read. (znodes carry
`version`/`cversion`/`mzxid` for optimistic-CAS and ordering, *not* as a resumable watch cursor.)

---

## 4. The design spectrum

| Axis | **etcd v3** | **Consul blocking query** | **ZooKeeper** |
|---|---|---|---|
| Model | Streaming bidi gRPC; many watches per stream (`watch_id`) | HTTP long-poll, one request per cycle (`?index=N&wait=`) | One-shot trigger (re-register) **+** persistent/recursive (3.6+) |
| Resume | **Revision-resumable** -- `start_revision`, replayable history | Index *handshake* -- opaque state token, **not** a replay cursor | **Not resumable** -- signal + re-read current state |
| Delivery | **Event log** -- ordered `events`, each with `mod_revision` | **Edge-triggered re-read** -- current value; spurious wakeups possible | **Edge-triggered re-read** -- bare event, then `getData`/`getChildren` |
| Ordering | Revision-ordered; atomic per revision; reliable per watcher | None (level); index monotonic *except documented resets* | "event before the data"; ordered wrt the client's other ops |
| History gone | **Compaction** → `canceled` + `compact_revision` (`ErrCompacted`); client re-lists + re-watches | **Index reset** → client resets `index=0`, re-reads, sanity-checks `≥1` | **N/A** (no history); session-expiry loses watches → re-establish + re-read |
| Served from | Any member; watch is *ordered*, not linearizable | `default`/`stale`/`consistent` (leader / follower / leader+quorum) | The connected server; restored on reconnect to same session |
| Idle handling | `progress_notify` bookmarks advance resume point | The `wait` timeout returns the same index | No idle concept; watch persists until fired/expired |

**The lesson Configd takes from each:**
- **From etcd:** the *resumable, history-bounded, replayable event log* keyed by a monotonic cursor, with
  the too-old case handled explicitly; one stream multiplexing many watches; idle bookmarks; serve from a
  lagging replica without breaking order. etcd's model is the target -- **except its single global
  revision, which sharding makes impossible.**
- **From Consul:** honesty that a resume token can move backwards / fall off, and the discipline of a
  defined reset rule; that "tell me it changed, I'll re-read" is a legitimate, simpler contract.
- **From ZooKeeper:** the *minimum* guarantee a notification must give ("you see the event before the
  data"; ordered wrt your own ops) and the cost of one-shot designs (you cannot see every intermediate
  change) -- which argues for **persistent, resumable** watches over one-shot.

The next document maps this target onto Configd's sharded, failover-prone, edge-fronted architecture -- where the single global revision is replaced by a **per-shard cursor vector**, and the "serve from a
lagging replica" precedent becomes **serve from the edge fan-out plane**.

---

## Appendix -- primary sources

etcd: `rpc.proto` https://raw.githubusercontent.com/etcd-io/etcd/main/api/etcdserverpb/rpc.proto ·
`kv.proto` https://raw.githubusercontent.com/etcd-io/etcd/main/api/mvccpb/kv.proto ·
`error.go` https://raw.githubusercontent.com/etcd-io/etcd/main/api/v3rpc/rpctypes/error.go ·
`watch.go` https://raw.githubusercontent.com/etcd-io/etcd/main/client/v3/watch.go ·
api_guarantees https://etcd.io/docs/latest/learning/api_guarantees/ ·
api https://etcd.io/docs/latest/learning/api/ ·
data_model https://etcd.io/docs/latest/learning/data_model/ ·
interacting_v3 https://etcd.io/docs/latest/dev-guide/interacting_v3/ ·
maintenance https://etcd.io/docs/latest/op-guide/maintenance/ ·
configuration https://etcd.io/docs/latest/op-guide/configuration/ ·
Jepsen https://jepsen.io/analyses/etcd-3.4.3

Consul: blocking queries https://developer.hashicorp.com/consul/api-docs/features/blocking ·
consistency https://developer.hashicorp.com/consul/api-docs/features/consistency

ZooKeeper: Programmer's Guide https://zookeeper.apache.org/doc/current/zookeeperProgrammers.html ·
AddWatchMode https://zookeeper.apache.org/doc/r3.6.3/apidocs/zookeeper-server/org/apache/zookeeper/AddWatchMode.html ·
addWatch https://zookeeper.apache.org/doc/r3.9.2/apidocs/zookeeper-server/org/apache/zookeeper/ZooKeeper.html
</content>
</invoke>
