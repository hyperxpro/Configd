# Watches -- recommended design (RFC-ready), and the staging call

Research from 2026-06-27/28, docs-only at the time -- this is a recommendation, not an implementation.
Builds on `prior-art.md` (mechanisms) and `configd-analysis.md` (the sharded analysis). Written to be
rigorous enough to drop into the
driver protocol RFC and be implemented identically across the Rust / Go / Python / Java drivers. Normative
keywords (MUST / SHOULD / MAY) are used deliberately. (Watches, including the multi-shard case, have since
been built and shipped; see [`../../rfc/driver-protocol/`](../../../rfc/driver-protocol/) for the current
normative spec. This document is preserved as the design rationale.)

> This design needed to be incorporated into the driver protocol RFC once watches shipped. A watch is a
> protocol-level, client-facing feature; the polyglot drivers must implement it byte-identically. The
> single most important requirement (§3, §12): specify the watch cursor as a per-shard vector and the
> ordering guarantee as per-key-only from day one, even at N=1, or every driver written against a
> single-shard deployment would silently break the moment a cluster shards. (This requirement was carried
> into the shipped RFC.)

---

## 0. Recommendation at a glance

| Decision | Recommendation | Rationale (full analysis in `configd-analysis.md`) |
|---|---|---|
| Model | Persistent, resumable, cursor-vector watch (etcd-shaped, not ZK one-shot) | One-shot can't "see every change"; resumable replayable log is the proven model (§1) |
| Served from | Edge fan-out plane (primary); leader-served as an optional later fast-path | Reuses the hardened plane; offloads the scarce consensus tier; the etcd "any-member" precedent (§2) |
| Resume token | Per-shard cursor vector `{gid: S}` (scalar at N=1) | No global revision exists or should be built; the vector is already the cross-shard model (§3) |
| Transport | Streaming (the existing EDGE_WIRE session), `watch_id`-multiplexed | It is the fan-out plane; matches the binary driver protocol; supports push + catch-up (§9) |
| Filtering | At the edge, post-verification; trusted-edge for the near-term design; signed-skip-evidence as a later step | ADR-0038's calculus, reapplied edge-to-client (§10) |
| Guarantee | Per-key order yes, per-shard order yes, cross-shard/global order no; at-least-once + `(gid,S)` dedup | The deliberate sharding trade; identical to ADR-multiraft-cross-shard / ADR-0019 (§5) |
| Staging | Design the protocol now (vector-native); ship the N=1 watch as soon as the driver protocol ships and the veneer/drivers are funded; the multi-shard watch follows, riding on sharding | Substrate is built and hardened at N=1; the vector/scatter-gather is the sharded-edge work (§11) |

---

## 1. The watch model (normative)

A watch is a long-lived, client-initiated subscription to changes of a key or key prefix (within a
`ConfigScope`), delivered as an ordered stream of change events, resumable from a client-held cursor,
multiplexed with other watches over one connection.

- Watches MUST be persistent (fire repeatedly until canceled), not one-shot. Rationale: ZooKeeper's
  one-shot model "cannot reliably see every change" (`prior-art.md` §3); Configd already has the
  replayable substrate to do better.
- Watches MUST be resumable from a cursor with a no-missed-events guarantee while the cursor stays within
  the retained history window (etcd "Reliable"; ADR-0034 contiguity).
- The cursor MUST be a per-shard vector (§3). At N=1 it is a one-element vector; drivers MUST treat it as
  a vector regardless, for forward compatibility.
- A watch MUST deliver per-key and per-shard order and MUST NOT be assumed to deliver cross-shard / global
  order (§5).

Rejected models and why: one-shot (ZK), misses intermediate changes; long-poll/level-triggered (Consul),
re-read not event-log, index can reset, redundant second transport (§9); scalar global cursor (etcd),
requires a global revision Configd has no cheap way to produce (`configd-analysis.md` §1.2).

---

## 2. Served-from (normative)

- Watches MUST be servable from the edge fan-out plane (the recommended primary path). The edge holds a
  replicated, cursor-tracked, per-shard materialization of the store and serves the watch from it, the
  same way etcd serves a watch from any member (`prior-art.md` §1.6). This offloads the consensus plane
  and scales horizontally with edge nodes.
- A watch served from an edge is bounded-stale relative to the leader (frontier exposed via
  `StalenessTracker`, ADR-0039; budget under 500 ms p99, ADR-0019). A client needing read-after-write
  freshness on a key MUST use the linearizable strong-read path for that key, not the watch.
- A leader-served fast-path MAY be offered later for latency-critical watchers, with the explicit
  understanding that it loads the consensus plane (a watch session runs on the shard owner thread).

---

## 3. Resumption -- the per-shard cursor vector (normative)

```
WatchCursor := { gid_0: S_0, gid_1: S_1, …, gid_{N-1}: S_{N-1} }
```

- `S_i` is the ADR-0033 applied-mutation sequence of shard `gid_i` the client has processed. It is
  deterministic and leader-independent (every replica assigns the same `S` to the same committed
  mutation), which is what makes resume safe across leader change (§6).
- A fresh watch starts with all components at 0 (or omitted, meaning 0), meaning "from the current
  frontier" per shard, drivers MUST follow the etcd "now" rule: a cursor of 0 means start at the shard's
  current `S`, plus 1, not "replay all history" (etcd footgun, `prior-art.md` §1.6 Jepsen note: rev 0 means
  "from now"). A client that wants existing state MUST take a snapshot first (it will receive
  `SNAPSHOT_FIRST`, §8).
- To resume, the driver re-sends the full vector; the server resumes each shard substream from its
  component via `readSince(S_i)`.
- **Encoding (RFC):** the wire cursor MUST be encoded as a length-prefixed list of `(uint32 gid, uint64
  S)` pairs, ordered by `gid`. The driver MUST treat it as opaque-but-structured: it MUST be able to
  (a) update one component, (b) max-merge two vectors component-wise (failover, §6), and (c) serialize/
  deserialize it for durable resume. A scalar-only encoding is forbidden even at N=1 (forward
  compatibility, §12).

### 3.1 Compaction / too-old (normative)

When a component `S_i` has fallen behind shard `i`'s retained window, `readSince(S_i)` returns
`Gap(oldestRetainedSeq)`. The server MUST then:
1. Put only that shard's substream into `SNAPSHOT_FIRST` and transfer a prefix-filtered snapshot of that
   shard (`SNAPSHOT_BEGIN/CHUNK/END`, backpressure-paced, cutover after `END`), then resume tailing from
   `snapshotSeq`. Other shards' substreams MUST continue uninterrupted (partial resync, the vector payoff,
   §8).
2. If the replay source cannot cover the range at all, the server MUST send `WATCH_CANCELED(watch_id,
   GAP_UNRECOVERABLE)`; the driver MUST then re-list current state and re-create the watch from the new
   frontier (the etcd `ErrCompacted` fallback).

Contrast for implementors: unlike etcd (which cancels the watch and makes the client re-list on every
compaction), Configd performs the re-list inline as a per-shard snapshot; the terminal `WATCH_CANCELED` is
only the genuinely-unrecoverable case.

### 3.2 Idle bookmark (normative)

The cursor advances over all commits on a shard; a filtered watcher receives only matching events.
Therefore the server MUST emit periodic bookmark frames (the existing `HEARTBEAT(latestSeq,
serverNowMillis)` repurposed, or a `WATCH_PROGRESS(watch_id, cursor_vector)`) carrying the current
per-shard `S` with no events, and the driver MUST advance its cursor component on bookmarks. Without this,
a busy shard's non-matching traffic compacts past an idle watcher and forces an avoidable snapshot (§8.1).
This is etcd's `progress_notify` / "Bookmarkable" guarantee, which matters more under sharding (each shard
compacts independently).

---

## 4. Multi-shard / prefix-watch handling (normative)

- A single-key watch addresses exactly `shardFor(scope, key)`, one shard, one cursor component.
- A prefix or full-store watch spans all N shards (the prefix's keys scatter, `shardFor` hashes the whole
  key; `configd-analysis.md` §2). The server-side aggregator MUST scatter-gather across the shards it
  materializes and deliver one multiplexed stream tagged per event with `(gid, S)`.
- **Driver merge algorithm (normative):**

  ```
  on WATCH_CREATE(watch_id, prefix, cursor_vector):
      ensure connection(s) cover all N shards   # one full-store edge, or several sharded edges (later)
      send WATCH_CREATE on each covering stream with the relevant cursor sub-vector
  on EVENT(watch_id, gid, S, key, kind, payload):
      if S <= cursor_vector[gid]: drop            # at-least-once dedup by (gid, S)
      else: cursor_vector[gid] = S; deliver(key, kind, payload) to application
  on BOOKMARK(watch_id, gid, S):
      cursor_vector[gid] = max(cursor_vector[gid], S)   # advance even with no events
  merge across shards is a UNION, not a sorted merge   # no global order exists to sort on
  ```

- The driver MUST present per-key order to the application and MUST NOT present or imply cross-shard
  order. The driver SHOULD expose `(gid, S)` (or an opaque per-event stamp) so applications can dedup and
  reason about per-shard progress.
- **Topology:** at N=1 or against a full-store edge, one stream suffices and the edge does the
  scatter-gather (recommended near-term). Against sharded edges (a later topology), the driver opens
  enough substreams to cover all shards and unions them.

---

## 5. Delivery + ordering guarantees (normative -- state exactly this in the RFC)

**Guaranteed:**
- Per-key order -- a single key's PUT/DELETE events arrive in commit order.
- Per-shard order -- one shard's events arrive in `S` order, gap-free within the retained window.
- Atomic per shard-commit -- all keys of a single-shard `BATCH` arrive together.
- At-least-once delivery with `(gid, S)` dedup -- resume/failover MAY redeliver from the cursor; the
  client MUST dedup by `(gid, S)`.
- Bounded-staleness freshness, per shard -- exposed via the frontier (ADR-0039).

**Not guaranteed (drivers MUST NOT assume):**
- Cross-shard / global total order -- events from different shards interleave arbitrarily.
- Cross-shard atomicity -- a multi-key change spanning shards is not all-or-nothing (disclaimed for
  writes; watches inherit it).
- Linearizable watch -- a watch is ordered, not linearizable; it may lag the leader.

This is identical to the consistency contract's drop-in language (ADR-multiraft-cross-shard); watches are
simply that contract projected onto a change stream.

---

## 6. Failover behavior (normative)

- On endpoint or leader change, the driver MUST resume each shard substream at its cursor component; it
  MUST NOT regress a component (it MUST max-merge: `effectiveResumeCursor` per shard =
  `max(resumeCursor, failoverResumeCursor)`, the existing wire field generalized to a vector).
- Because `S` is deterministic and leader-independent (§3), resume against a freshly elected leader yields
  the contiguous run `S > S_i` (or a `Gap`, §3.1), no missed events, no duplicates beyond the resume
  boundary (client dedups by `S`).
- Shards fail over independently; a failover on one shard MUST NOT perturb the cursor or stream of
  another. The vector makes this a per-component operation (a strict advantage over a single global
  cursor, which would rebuild wholesale).

---

## 7. Wire protocol sketch (RFC-ready)

The client-facing watch protocol is a thin multiplex/filter veneer over the existing EDGE_WIRE session
(`EdgeFrame` family, EDGE_WIRE_VERSION; length-prefixed, CRC32C, mTLS, cert-DN identity). It adds
client-to-server `WATCH_CREATE` / `WATCH_CANCEL` and server-to-client `WATCH_EVENT` / `WATCH_PROGRESS` /
`WATCH_CANCELED`, all carrying a `watch_id` for multiplexing (etcd's mechanism, `prior-art.md` §1.2). It
reuses
`SNAPSHOT_{BEGIN,CHUNK,END}` for catch-up, `ERROR_CLOSE`/`ErrorCode` for the taxonomy, and the
`SlowConsumerGovernor` for backpressure unchanged.

```
WATCH_CREATE   { watch_id:u64, scope:enum, target: KEY(bytes) | PREFIX(bytes) | FULL,
                 cursor: [ (gid:u32, S:u64) … ],   # empty ⇒ all-zero ⇒ "from now per shard"
                 flags: { prev_value:bool, full_chain_verify:bool } }      # client→server
WATCH_CANCEL   { watch_id:u64 }                                           # client→server
WATCH_EVENT    { watch_id:u64, gid:u32, S:u64,
                 changes: [ { key:bytes, kind: PUT|DELETE, value?:bytes, prev?:bytes } ] }  # server→client
WATCH_PROGRESS { watch_id:u64, cursor: [ (gid:u32, S:u64) … ] }            # server→client (bookmark, §3.2)
WATCH_CANCELED { watch_id:u64, code:ErrorCode, oldest: [ (gid:u32, S:u64) … ]? }  # server→client (§3.1)
```

Normative notes for implementors:
- One connection multiplexes many `watch_id`s; the server MUST tag every server-to-client watch frame
  with its `watch_id`. A `watch_id` MUST be unique per connection for the connection's lifetime.
- `WATCH_EVENT.changes` MUST be in ascending `S`/commit order within a `(watch_id, gid)`; a single
  `WATCH_EVENT` MUST NOT split one shard-commit across frames (atomicity, §5), except an explicit
  fragmentation flag may be added later for oversized commits (etcd `fragment`, `prior-art.md` §1.4; out
  of scope for the near-term design, recorded as the extension point).
- `full_chain_verify:true` selects the untrusted-edge mode (§10): the server streams the full verbatim
  signed chain (no edge filtering) and the client verifies plus filters locally. Default `false` means
  trusted-edge filtered mode.
- `prev_value:true` requests the pre-image (etcd `prev_kv`); MAY be unsupported at first (the
  snapshot/delta model carries the post-image; pre-image is a later nicety).
- Wire-version: these frames require an EDGE_WIRE_VERSION bump (or a distinct CLIENT_WIRE_VERSION). The
  existing `FrameCodec`/`EdgeFrameCodec` are already versioned and golden-fixture-gated, so the bump is
  mechanical; it MUST be operator-gated like every prior wire bump.

---

## 8. Security / trust (normative)

- **Default (trusted edge):** the edge filters post-verification and asserts completeness up to `S`. The
  residual risk is wholesale stream-stall (detected by the staleness frontier), not selective suppression,
  only within the operator's own trust domain (client to its own edge/sidecar, mTLS). The RFC MUST document
  this trust boundary explicitly.
- **Untrusted edge:** the client MUST use `full_chain_verify` (receive plus verify the full signed chain,
  filter locally), at the cost of write-stream bandwidth (ADR-0038: ~80 Mbit/s typical / ~800 burst per
  subscriber). This is appropriate for a broad-prefix watcher; inappropriate for a thin one-key watcher on
  an untrusted edge.
- **A later upgrade:** ADR-0038's named path, leader-signed skip-evidence (signed per-range Merkle
  summaries), gives selective-suppression-proof filtered watches against an untrusted edge without the
  firehose. Out of scope for the near-term design; recorded as the extension.
- Subscriptions MUST be authorized against the client's namespace ACL: a client MUST only watch prefixes
  within its authorized namespaces.

---

## 9. Transport -- streaming (normative)

Streaming over the existing mTLS EDGE_WIRE session (§7). Long-poll is rejected (re-read not event-log;
index can reset; a redundant second transport; can't express snapshot catch-up cleanly --
`prior-art.md` §2, `configd-analysis.md` §9). A long-poll/HTTP
compatibility surface MAY be added later for HTTP-only clients (a la etcd's gRPC-gateway) but is not the
canonical watch and was not in the initial RFC.

---

## 10. The staging call (the decision the operator had to make)

The pieces and their state at the time of this research:

| Piece | State | Ready for a near-term ship? |
|---|---|---|
| Streaming session, cursor, snapshot catch-up, slow-consumer governor, staleness, mTLS endpoint | built, hardened, CI-green at N=1 | yes, available |
| `Subscribe` carrying `prefixes` + `resumeCursor` + `failoverResumeCursor` | built | yes |
| Per-shard `FanOutBuffer` | built, dormant beyond N=1 | N=1 yes / N>1 later |
| Client-facing veneer: `watch_id` multiplex + per-watch prefix filter + `WATCH_*` frames + event API | not built (new protocol surface) | near-term work item |
| Per-shard cursor vector on the wire + scatter-gather merge | not built; the sharded edge client was explicitly deferred at the time | later |
| Polyglot drivers implementing the watch API (Rust/Go/Python/Java) | not built (no driver protocol yet) | gated on the driver protocol |

### The recommendation

1. **Design the watch protocol now, cursor-vector-native** (this document). It costs ~nothing extra at
   N=1 (a one-element vector) and prevents a protocol break when sharding lands. This is the cheap,
   high-leverage action regardless of when watches ship.
2. **A near-term watch is buildable iff two conditions hold:** (a) it ships at N=1 (single shard, the
   default below the throughput threshold, ADR-multiraft-cross-shard), where a watch is an edge-served,
   single-cursor projection of the already-hardened plane with no cursor vector and no scatter-gather; and
   (b) the driver protocol itself ships with it (a watch is part of that protocol, it cannot ship without
   it) and the veneer plus drivers are funded. Under those conditions the work is productizing a hardened
   plane, not new consensus research. At N=1 the watch even has etcd-like global order (one shard, one
   total order); the cross-shard caveats are dormant until the cluster shards.
3. **The full multi-shard (N>1) cursor-vector watch is a later feature**, gated on the same sharding
   go/no-go that gates sharding itself. It rides on the sharded edge (cursor-vector plus scatter-gather
   merge), which is already scoped as follow-on work. Watches do not pull sharding forward; they ride it.

This refines an earlier blanket "defer all watches until sharding" instinct: the evidence says the N=1
watch is a near-term-capable productization of a hardened plane (if the driver protocol ships and is
funded), while the multi-shard watch genuinely has to wait for sharding. The operator chooses based on
whether the driver protocol ships with watches and whether the veneer/driver cost is funded. (In practice,
both stages were built: the N=1 watch shipped with the driver protocol, and the multi-shard watch followed
once sharding landed.)

### Effort / risk

- **N=1 edge-served watch** -- moderate, low-medium risk.
  - Server: edge-side watch-registry plus filter (reuse `WatchService.filterByPrefix`, drop coalescing)
    plus `watch_id` multiplex plus `WATCH_*` frames on a versioned codec bump. Reuses the
    session/catch-up/governor/staleness machinery unchanged.
  - Drivers: the watch API plus multiplex plus resume plus `(gid,S)` dedup, per language (across the 4
    drivers).
  - Risk drivers: protocol-design churn and cross-driver consistency, not consensus/durability (the
    substrate is hardened and CI-green). No write-plane risk. Dependency: cannot ship before the driver
    protocol exists.
- **N>1 cursor-vector watch** -- medium, medium risk, but mostly the sharded-edge work that is already
  scoped. Adds: vector resume on the wire, per-shard bookmark plumbing, driver-side vector management plus
  cross-edge merge (if edges are sharded). Same protocol, more shards.
- **Untrusted-edge signed-skip-evidence** -- larger, separate; only if a named untrusted-edge
  filtered-watch requirement appears (ADR-0038's recorded extension).

---

## 11. What feeds the driver protocol RFC (explicit)

Once watches are in scope, the RFC MUST specify, identically for all drivers:
1. The cursor as a per-shard vector with the `(gid:u32, S:u64)[]` encoding (§3), never a scalar, even at
   N=1.
2. The guarantee surface verbatim from §5, drivers MUST NOT assume cross-shard/global order, MUST dedup by
   `(gid, S)`, MUST treat delivery as at-least-once.
3. The `WATCH_*` frames and the `watch_id` multiplex (§7).
4. The driver merge algorithm (§4), union, per-key order, bookmark-advances-cursor.
5. The resume plus compaction protocol (§3.1), inline per-shard snapshot; terminal `WATCH_CANCELED`.
6. The failover protocol (§6), per-component max-merge, independent per-shard failover.
7. The trust modes (§8), trusted-edge filtered (default) vs. `full_chain_verify`.

The biggest forward-compatibility trap, restated: a driver written against a single-shard deployment that
assumes a scalar cursor and global order will silently corrupt its view when the cluster shards. The
vector plus per-key-order contract had to be normative from the first RFC draft.

---

## 12. Open questions for the operator (what had to be decided)

1. **Ship now, or defer?** Ship the N=1 edge-served watch as soon as the driver protocol ships and the
   veneer/drivers are funded, or defer all watches until sharding lands? Recommendation: design the
   protocol now; ship the N=1 watch once the driver protocol ships and the cost is funded; otherwise
   defer.
2. **Served-from:** confirm edge-served as the model (vs. leader-served). Recommendation: edge-served;
   leader-served fast-path is an optional later addition.
3. **The guarantee surface:** confirm per-key order, no cross-shard order, at-least-once plus dedup as the
   public contract for watches (it must match the sharding consistency contract). Recommendation: yes, it
   is the only honest surface under hash sharding.
4. **Trust model for filtered watches:** confirm trusted-edge filtering as the default, with
   `full_chain_verify` for untrusted edges and signed-skip-evidence as the named later path.
5. **Does the initial driver protocol ship with watches at all?** Watches are part of the driver protocol;
   this gates everything. The watch design had to be in the RFC from the first draft if the answer was
   yes (it was).

---

## 13. One-paragraph handoff

Watches in Configd are a client-facing projection of the already-built, already-hardened edge fan-out
plane, not a green-field feature. The hard part is sharding: there is no global revision, so the resume
token must be a per-shard cursor vector, a prefix watch scatters across all shards, and the only honest
guarantee is per-key / per-shard order, never cross-shard order, at-least-once with `(gid,S)` dedup. Serve
watches from the edge (reuses the plane, offloads consensus, the etcd "any-member" precedent), filter at
the edge post-verification (ADR-0038), and handle the too-old case with an inline per-shard snapshot
resync (better than etcd's cancel-and-relist). Design the protocol cursor-vector-native from the start; an
N=1 edge-served watch is a near-term-capable productization of the hardened plane if the driver protocol
ships and is funded; the multi-shard watch follows, riding on the sharded edge. This design had to be in
the driver protocol RFC, most critically the vector cursor and the per-key-only ordering, from the first
draft, or every polyglot driver would break when a cluster shards. (Both stages have since shipped.)
