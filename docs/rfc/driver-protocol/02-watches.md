# Configd Driver Protocol RFC — §02: The Watch Protocol

**Status: DRAFT (2026-06-29). Docs-only; normative.** Second section of the Configd driver-protocol RFC.
This section specifies the **watch** surface: the change-subscription model (targets, persistence,
streaming, multiplexing), the **per-shard cursor vector** that makes resumption correct under sharding,
the **wire frames**, the **delivery and ordering guarantees** (stated honestly — what holds and what does
**not**), the **watch-authorization contract** (a streaming read, restating §1 §6), the **served-from**
model, and the **transport**. It is written so a driver in **any** language (Rust / Go / Python / Java)
implements watches **identically** — most critically, **vector-native and per-key-ordered from the first
draft**.

This section **formalizes** the watch research in
[`../../research/watches/`](../../research/watches/) (`recommendation.md`, `configd-analysis.md`,
`prior-art.md`, `decision-log.md`). Where this RFC says MUST/SHOULD/MAY, the research explains *why*. This
section is **normative**; the research is explanatory.

It **composes with**:

- [`01-paths-and-access.md`](01-paths-and-access.md) (§1) — the **address model** (`(scope, path)`,
  subtree scatter), the **capability model** `{READ, LIST, WRITE, WATCH, ADMIN}`, the **shared cursor
  vector** ([A9-1](01-paths-and-access.md#9-forward-compatibility-and-composition-with-the-watch-section),
  [A4-4](01-paths-and-access.md#42-the-list-operation)), the **shared ordering contract**
  ([A4-2](01-paths-and-access.md#41-a-subtree-scatters-subtree-ops-are-scatter-gather),
  [A9-2](01-paths-and-access.md#9-forward-compatibility-and-composition-with-the-watch-section)), and the
  **watch-authorization contract** ([§6](01-paths-and-access.md#6-the-watch-authorization-contract-normative),
  clauses A6-1..A6-5). §1 already names this file and cross-references it. §2 **restates §1 §6 normatively
  and MUST NOT weaken it.**
- [`03-authentication.md`](03-authentication.md) (§3) — the **mTLS handshake** on the binary edge, the
  **authenticate-before-data** order ([AU4-1](03-authentication.md#4-the-connection-lifecycle--authenticate-before-any-data)),
  and the **authn → authz → stream** sequence on the watch path
  ([AU8-2](03-authentication.md#8-composition-with-1-and-2)). §3 owns the **401** (authentication) side;
  §2 owns neither the 401 nor the 403 *definitions* (those are §3 and §1 respectively) — it specifies how
  they surface **as terminal stream frames**.

Clauses in this section are referenced as **`W<n>-<m>`** (the watch-section clause prefix, parallel to §1's
`A<n>-<m>` and §3's `AU<n>-<m>`), so the composed RFC has **no clashing identifiers**.

---

## 1. Conventions, scope, versioning

### 1.1 Requirement keywords

The keywords **MUST**, **MUST NOT**, **REQUIRED**, **SHALL**, **SHALL NOT**, **SHOULD**, **SHOULD NOT**,
**MAY**, and **OPTIONAL** are to be interpreted as in RFC 2119 / RFC 8174.

### 1.2 Scope of this section

This section specifies the **client-facing watch protocol**: how a driver creates, resumes, and cancels a
watch; how change events, bookmarks, catch-up snapshots, and terminal closes arrive on the wire; the
**guarantees** a driver may and may not rely on; and the **authorization** gate at subscription. It does
**not** redefine the address model or capability evaluation (§1), nor authentication (§3); it **composes
with** both.

**W1-1 (the load-bearing property of this section).** The watch **resume token is a per-shard cursor
vector**, and the **only** ordering guarantees are **per-key** and **per-shard** — **never** cross-shard /
global. A driver **MUST** be **vector-native and per-key-ordered from the first implementation, even at
N = 1** (a one-element vector). A scalar-cursor or global-order assumption is **FORBIDDEN** even at N = 1.
*(Rationale: a single shard happens to have a total order, so a driver written against N = 1 that assumes a
scalar cursor and global order will compile, pass its tests, and then **silently corrupt its view the
moment the cluster shards.** This is the single most important rule in this section;
`../../research/watches/recommendation.md` §12, [DL-W-03](../../research/watches/decision-log.md).)*

### 1.3 Versioning and the wire-version bump

**W1-2 (the watch frames extend the edge wire; they require a version bump).** The watch frames in §5 are
**new frame types and one new error code** on the **existing** edge wire format (the `EdgeFrameCodec`
length-prefixed, CRC32C-checked, version-byte framing — the wire-format-v1 discipline of
[`adr-0029-wire-format-v1.md`](../../decisions/adr-0029-wire-format-v1.md); the concrete edge byte layout is
[`06-wire-framing.md`](06-wire-framing.md) §F2, the `EdgeFrameCodec` edge transport per ADR-0037). Adding them
is a wire-format change and **MUST** bump the edge wire version from `0x01` to **`0x02`** (§5.9). The version
is **pinned by the first frame** of the connection — there is **no** negotiation handshake
([`00-overview.md`](00-overview.md) §4, [`06-wire-framing.md`](06-wire-framing.md) §F4; §1
[§1.3](01-paths-and-access.md#13-versioning), §3 [§1.3](03-authentication.md#13-versioning)); a wire-version
bump is **operator-gated**, exactly as every prior Configd wire bump (the operator-gated wire-bump rule).

**W1-3 (per-connection version; existing frame payloads unchanged — design-A; fail-closed on unknown).** The
edge wire version is **pinned per connection by the first frame** (*"negotiated"* here = first-frame-pinned,
**not** a handshake; [`06-wire-framing.md`](06-wire-framing.md) §F4) and stamped on **every** frame of that
connection (the `EdgeFrameCodec` version byte). A `0x02` connection stamps `0x02` on **every** frame it carries, **including**
a reused `NOTIFY` (W5-2); a `0x01` connection stays entirely `0x01`. §2 **MUST NOT** change the **payload
byte layout** of any existing frame type (`SUBSCRIBE`, `SUBSCRIBE_OK`, `NOTIFY`, `SNAPSHOT_{BEGIN,CHUNK,END}`,
`CURSOR_ACK`, `HEARTBEAT`, `ERROR_CLOSE`) — only the version byte differs between a `0x01` and a `0x02`
connection. Consequently **"byte-identical to the built plane" is scoped to `0x01` connections**: the
existing `0x01` golden fixtures stay valid **unchanged**, and §2 **adds new `0x02` golden fixtures** rather
than rebaselining the `0x01` ones. The codec's current accept-only-`EDGE_WIRE_VERSION` decode check becomes a
**negotiated-version** check that accepts the connection's agreed version. A peer that negotiated only `0x01`
**MUST** fail closed on any `0x02` frame (an unknown version surfaces as `BAD_WIRE_VERSION`, an unknown type
as `FRAME_CORRUPT`; §1
[A9-4](01-paths-and-access.md#9-forward-compatibility-and-composition-with-the-watch-section), §3
[AU7-1](03-authentication.md#7-forward-compatibility)). A driver **MUST NOT** assume a watch capability it
has not negotiated. *(This is the per-connection "design-A" wire-version model; W5-11 restates it for the
frame layer, W9 reflects it in the EXISTS/ADDS split.)*

---

## 2. The watch model

**W2-1 (definition).** A **watch** is a long-lived, client-initiated subscription to the stream of changes
of a **target** within one `ConfigScope`, delivered as an ordered stream of change events, **resumable**
from a client-held **cursor vector** (§3), and **multiplexed** with other watches over one connection by a
client-assigned **`watch_id`** (§5).

### 2.1 Watch targets

**W2-2 (the three target forms — consistent with §1's path model).** A watch target is exactly one of:

| Target | Form (§1 [§3.4](01-paths-and-access.md#34-patterns-for-acl-rules-list-and-watch-targets--never-for-stored-paths)) | Shard reach | Authorized over |
|---|---|---|---|
| **KEY** | a concrete path `/a/b` | **exactly one** shard `shardFor(scope, /a/b)` | the exact path |
| **PREFIX** (subtree) | `/a/` ≡ `/a/**` | **scatter-gather across all N shards** (§4) | the whole subtree |
| **FULL** | the root `/**` (whole store within `scope`) | **all N shards** | the whole scope (root) |

**W2-3.** A KEY watch addresses **one** shard and therefore has a **single** cursor component; a PREFIX or
FULL watch **scatters across all shards** (§1
[A2-4 / INV-PATH](01-paths-and-access.md#22-no-path-segment-is-a-routing-input-the-invariant-drivers-must-respect),
[A4-1](01-paths-and-access.md#41-a-subtree-scatters-subtree-ops-are-scatter-gather)) because `shardFor`
hashes the **entire** `(scope, path)` — keys sharing a prefix are **not** co-located. A driver **MUST NOT**
assume a PREFIX watch is served by a single shard, and **MUST NOT** route, shard, or order by path prefix.

**W2-4 (target syntax).** A KEY or PREFIX target path **MUST** satisfy the §1 path grammar and
normalization (§1 [§3](01-paths-and-access.md#3-path-syntax): absolute, `seg-char`-only, UTF-8, canonical,
≤ 1024 B). A driver **MUST** reject a non-conforming target **client-side** (§1
[A3-4](01-paths-and-access.md#32-normalization-required--paths-have-one-canonical-form)); a server **MUST**
reject one with a `400`-class terminal close (W7-5a). `scope` is a typed field, **never** a path segment
(§1 [A2-1](01-paths-and-access.md#21-an-address-is-scope-path)); an out-of-range `scope` or `target_kind`
byte (§5.2) **MUST** likewise be rejected with a `400`-class (`BAD_SUBSCRIBE`) terminal close (W7-5a).

### 2.2 Persistent, resumable, streaming, multiplexed

**W2-5 (persistent, not one-shot).** A watch **MUST** be **persistent** — it fires repeatedly for every
matching change until canceled (§5, `WATCH_CANCEL`) or terminally closed (W6-4). A driver **MUST NOT**
assume one-shot semantics. *(Rationale: ZooKeeper's one-shot watch "cannot reliably see every change"
because of the re-registration gap. ZooKeeper 3.6+ added PERSISTENT / PERSISTENT_RECURSIVE watches that
remove the re-registration burden but still deliver a **signal-then-reread**, not a resumable event log
(`prior-art.md` §3). Configd's differentiator is therefore **resumability** — a replayable, cursor-keyed
history (W2-6) — not merely persistence; `../../research/watches/prior-art.md` §3.)*

**W2-6 (resumable with no-miss-in-window).** A watch **MUST** be **resumable** from its cursor vector
(§3) with a **no-missed-events** guarantee while each component stays within that shard's retained history
window (W6-3 governs the fallen-behind case). Resumption is by **re-sending the cursor vector** on a fresh
`WATCH_CREATE` (§5.2, W6-3).

**W2-7 (streaming, not long-poll).** A watch **MUST** be delivered over the **streaming** mTLS edge session
(§8), server-pushed. A driver **MUST NOT** implement a watch as a polling re-read. *(Rationale: the edge
plane is already a streaming session; long-poll is a redundant transport that cannot express snapshot
catch-up and whose index can reset; `../../research/watches/configd-analysis.md` §9,
`prior-art.md` §2. A long-poll/HTTP compatibility gateway is a named v2 extension, W10-7, not the canonical
watch.)*

**W2-8 (multiplexed by `watch_id`).** Many watches **MUST** be multiplexable over **one** connection,
demultiplexed by a **client-assigned `watch_id`** (a `uint64`). The server **MUST** tag **every**
server→client watch frame with the `watch_id` it belongs to. A `watch_id` **MUST** be **unique per
connection for the connection's lifetime** — a driver **MUST NOT** reuse a `watch_id` on the same
connection even after the prior watch is canceled or closed (so a late frame from a canceled watch can
never be mis-attributed to a new one). *(The `watch_id` multiplex is etcd's mechanism; `prior-art.md` §1.2
— a 500-watch process opens **one** connection, not 500. The **no-reuse** rule is Configd's own hardening,
**not** an etcd guarantee: it removes the late-frame-misattribution hazard the multiplex would otherwise
admit.)*

**W2-9 (rejected models — normative summary).** A driver **MUST NOT** model a Configd watch as any of:

| Rejected model | Why (RFC-normative) |
|---|---|
| **One-shot** (ZK) | misses intermediate changes (W2-5) |
| **Level-triggered re-read / long-poll** (Consul) | not an event log; resume index can move backwards / reset; redundant transport (W2-7) |
| **Scalar global cursor** (etcd revision) | no global revision exists under sharding and one **MUST NOT** be synthesized (§3; `configd-analysis.md` §1.2) |
| **Globally-ordered event stream** | cross-shard order does **not** hold (§6, W6-2) |

---

## 3. The resume cursor — the per-shard vector

This is the heart of §2. **Read W1-1 first.**

**W3-1 (the cursor is a per-shard vector).** A watch's resume position is a **cursor vector**:

```
WatchCursor := { gid_0 : S_0, gid_1 : S_1, …, gid_{N-1} : S_{N-1} }
```

where `S_i` is the **applied-mutation sequence `S`** (ADR-0033) of shard `gid_i` that the client has
**already processed**. A driver **MUST** represent the cursor as a vector keyed by shard id, **MUST NOT**
collapse it to a scalar, and **MUST NOT** assume N is 1 (W1-1).

**W3-2 (`S` is deterministic and leader-independent — why resume is safe).** `S` increments **only on
mutating applies** (PUT/DELETE/BATCH), not on Raft no-ops or reconfigurations, and **every replica of a
shard assigns the same `S` to the same committed mutation** (it applies the identical committed log in the
identical order). Therefore a cursor component **names the same point in that shard's history on every
replica and every leader** — which is exactly what makes resume **safe across a shard leader change**
(W6-5). `S` is the **per-shard analogue of etcd's global revision** (`configd-analysis.md` §1.1).

**W3-3 (do not confuse `S` with the freshness clock).** Resume/identity rides on **`S`** (deterministic).
**Freshness** rides on the **commit timestamp** (`commitTimestampMillis`, leader wall-clock; ADR-0035), is
**not** deterministic across leaders, and is used **only** for the staleness frontier (§8, W8-3) — **never**
as a cursor. A driver **MUST NOT** use a timestamp as a resume cursor.

**W3-4 (the "from-now per shard" rule — the etcd footgun, closed).** A fresh watch starts with all
components at **`0`** (equivalently, the component is **omitted** from the vector). A cursor component of
`0` means **"start at this shard's current `S`, and deliver only mutations after it"** — it does **NOT**
mean "replay all history." A driver that wants the **existing** state **MUST** request a snapshot
explicitly (it sets `with_initial_snapshot` and receives `SNAPSHOT_FIRST` for that shard, §5.2 / §5.8).
*(This is etcd's `start_revision = 0` ⇒ "now" rule; `prior-art.md` §1.3. A driver that assumes `0` means
"replay all history" silently gets "watch from now" instead and **misses the existing state** — which is
exactly why existing state **MUST** be requested as an explicit snapshot.)*

### 3.1 Wire encoding (shared with §1's list cursor)

**W3-5 (encoding — identical to §1 A9-1 / A4-4).** The cursor vector **MUST** be encoded on the wire as a
**length-prefixed list of `(uint32 gid, uint64 S)` pairs, ordered by `gid` in UNSIGNED ascending order**
(`gid` is a `uint32`; a driver **MUST** sort and compare it as unsigned, not signed):

```
cursor_vector := [ count:u32 ] ( gid:u32  S:u64 )*count        # ordered by gid UNSIGNED ascending
                  count == 0  ⇒  "from now per shard"  (W3-4)
```

This is the **same type** as the `list` continuation cursor (§1
[A4-4](01-paths-and-access.md#42-the-list-operation)) and is declared shared in §1
[A9-1](01-paths-and-access.md#9-forward-compatibility-and-composition-with-the-watch-section). A driver
**MUST** implement **one** cursor-vector type and reuse it for both `list` and `watch`. **At N = 1 the
vector has exactly one element** (`gid = 0`); a driver **MUST** still treat it as a vector (W1-1).

**W3-6 (the three driver operations on a cursor — REQUIRED).** A driver **MUST** be able to:

1. **update one component** — on a delivered event for shard `gid`, set `cursor[gid] = S` (W6-1 dedup);
2. **component-wise max-merge** two vectors — `merge[g] = max(a[g], b[g])` for every `g` (resume / failover,
   W6-5); a driver **MUST NOT** regress any component;
3. **serialize / deserialize** the vector (W3-5) for durable resume across process restarts.

**W3-7 (resume re-sends the full vector; shards resume independently).** To resume, a driver **MUST**
re-send the **full** cursor vector on a new `WATCH_CREATE` (§5.2). The server resumes **each** shard
substream from its component via the buffer's `readSince(S_i)` boundary **independently** — a `readSince`
returns either a contiguous run (`Ok`) or a `Gap` (W6-3). The **per-shard-independence** obligation — one
shard's gap or failover **MUST NOT** perturb another shard's substream or cursor component (W6-3, the vector
payoff) — is a **v2 server obligation**: it is **vacuous at N = 1** (one shard, one `readSince`, one
substream — the built single-session `FanOutSessionCore`) and becomes a live MUST only when the v2 sharded
edge serves N > 1 substreams (W9-3). The **driver** contract is vector-native from v1 regardless (W1-1).

---

## 4. Multi-shard and prefix watches

**W4-1 (single-key ⇒ one shard; prefix/full ⇒ scatter-gather).** A KEY watch addresses exactly
`shardFor(scope, key)` and has **one** cursor component. A PREFIX or FULL watch spans **all N shards**
(W2-3); the server-side aggregator **MUST** scatter-gather across the shards it materializes and deliver
**one** multiplexed stream, **tagging every event with its `(gid, S)`** (§5.4).

**W4-2 (the driver merge algorithm — UNION, normative).** A driver handling a PREFIX/FULL watch **MUST**
implement the following merge:

```
on WATCH_CREATED(watch_id, [ (gid, latestSeq, mode) … ]):
    record the covered shard set; for each gid in SNAPSHOT_FIRST, expect a catch-up substream (§5.8)

on WATCH_EVENT(watch_id, gid, S, commit_ts, changes[]):
    if S <= cursor[gid]:  drop                      # at-least-once dedup by (gid, S)  (W6-1)
    else:                 cursor[gid] = S
                          for change in changes: deliver(change) to the application   # per-key order

on WATCH_PROGRESS(watch_id, [ (gid, S) … ], server_now):
    for (gid, S): cursor[gid] = max(cursor[gid], S) # advance idle cursors with no events  (W4-4)

merge across shards is a UNION, not a sorted merge  # there is no global key to sort on  (W6-2)
```

**W4-3 (present per-key order, never cross-shard order).** A driver **MUST** present **per-key order** to
the application and **MUST NOT** present or imply cross-shard / global order (W6-2). A driver **SHOULD**
expose the per-event `(gid, S)` stamp (or an opaque equivalent) so applications can dedup and reason about
per-shard progress.

**W4-4 (idle watchers MUST advance on bookmarks).** A cursor component advances over **all** of a shard's
commits, but a filtered watcher receives **only matching** events. A driver **MUST** advance the relevant
cursor component on a `WATCH_PROGRESS` bookmark (§5.5), **even with no delivered events**. *(Rationale: an
idle prefix-watcher on a **busy** shard receives no matching events; if its component does not advance over
the non-matching commits, the shard compacts past it and forces an avoidable snapshot resync on reconnect.
This is etcd's `progress_notify`/"Bookmarkable" property — and it matters **more** under sharding because
each shard compacts independently; `configd-analysis.md` §8.1.)*

**W4-5 (topology).** Two server-side topologies satisfy W4-1:

- **Aggregating endpoint (v1 / N = 1 / full-store edge):** the driver sends **one** `WATCH_CREATE` to an
  endpoint that materializes **all** shards; that endpoint scatter-gathers internally and returns **one**
  `(gid, S)`-tagged stream. The driver does **no** cross-endpoint merge. **This is the v1 path.**
- **Sharded endpoints (v2):** if edges each serve only a **subset** of shards, the driver opens enough
  substreams to cover all N shards and unions them client-side per W4-2. This is the v2 sharded-edge work
  (W9-3); the protocol is **identical** — only the number of transport connections differs.

---

## 5. The watch frames

The watch protocol is a **client-facing multiplex/filter veneer over the existing edge session**
(`EdgeFrame` family + `EdgeFrameCodec`; mTLS; cert-DN identity). §5 defines the **new** frames; they are
encoded by the **same** length-prefixed, CRC32C-checked codec and obey the **same** bounds discipline
(`peekLength` before allocation; CRC before type/version; explicit caps) as every existing frame.

### 5.1 Frame vocabulary and direction

**W5-1 (the §2 frame set).** §2 adds the following frame types. The next free `FrameType` code at the time
of writing is `0x0A`; §2 assigns `0x0A`..`0x12`.

| Frame | Code | Direction | Purpose |
|---|---|---|---|
| `WATCH_CREATE` | `0x0A` | client→server | create/resume a watch (target + cursor vector + flags) |
| `WATCH_CANCEL` | `0x0B` | client→server | cancel a watch by `watch_id` |
| `WATCH_CREATED` | `0x0C` | server→client | acknowledge a created watch; per-shard initial mode vector |
| `WATCH_EVENT` | `0x0D` | server→client | a per-shard change batch, tagged `(gid, S)` |
| `WATCH_PROGRESS` | `0x0E` | server→client | bookmark: advance idle cursor components, no events |
| `WATCH_CANCELED` | `0x0F` | server→client | terminal per-watch close (authz reject, gap-unrecoverable, …) |
| `WATCH_SNAPSHOT_BEGIN` | `0x10` | server→client | per-`(watch_id, gid)` catch-up snapshot header |
| `WATCH_SNAPSHOT_CHUNK` | `0x11` | server→client | per-`(watch_id, gid)` catch-up snapshot chunk |
| `WATCH_SNAPSHOT_END` | `0x12` | server→client | per-`(watch_id, gid)` catch-up snapshot trailer |

**W5-2 (reused existing frames).** §2 also **reuses** these existing frames unchanged: `ERROR_CLOSE`
(`0x09`) for **connection-level** (not per-watch) terminal close; and, **only in `full_chain_verify` mode**
(W8-4), `NOTIFY` (`0x03`) to deliver the verbatim signed chain (the client then verifies and filters
locally). All other existing frames remain the connection-level fan-out vocabulary and are not part of the
client-facing watch surface.

**W5-3 (why §2 adds watch-scoped snapshot frames rather than reusing `SNAPSHOT_*`).** The existing
`SNAPSHOT_{BEGIN,CHUNK,END}` (`0x04`..`0x06`) are **connection-level** and carry **no** `watch_id`/`gid`.
A multiplexed connection may interleave catch-up substreams for **different** `(watch_id, gid)` pairs
concurrently, so the catch-up frames **MUST** carry the multiplex tag to be disambiguated. Because the
existing `SNAPSHOT_*` frames are golden-fixture-pinned and **MUST NOT** be mutated (W1-3), §2 adds
`WATCH_SNAPSHOT_*` (`0x10`..`0x12`) — structurally the existing snapshot frames **plus** a leading
`(watch_id, gid)`, **reusing the same chunked, backpressure-paced, cutover-after-END mechanism** (RR-102).
*(This is the one place where "reuse `SnapshotBegin/Chunk/End`" must mean "reuse the mechanism," not "reuse
the literal frame": multiplexing forces the tag, and byte-stability of the built plane forbids editing the
existing frame.)*

### 5.2 `WATCH_CREATE` (client→server)

```
WATCH_CREATE payload:
  [ watch_id:u64 ]
  [ scope:u8 ]                    # 0=GLOBAL, 1=REGIONAL, 2=LOCAL  (§1 A2-1)
  [ target_kind:u8 ]             # 0=KEY, 1=PREFIX, 2=FULL
  [ path_len:u32 ][ path:bytes ] # UTF-8 canonical path (§1 §3); path_len==0 for FULL
  [ cursor_vector ]              # W3-5 encoding; count==0 ⇒ "from now per shard"
  [ flags:u8 ]                   # bit0=full_chain_verify, bit1=prev_value, bit2=with_initial_snapshot (W5-4a)
```

**W5-4.** `WATCH_CREATE` creates a new watch, or **resumes** an existing logical watch after reconnect (a
resume is just a `WATCH_CREATE` carrying the saved cursor vector — there is no separate resume frame). The
server **MUST** authorize it at subscription (W7) **before** emitting any `WATCH_CREATED`, snapshot, event,
or progress frame for it. `watch_id` **MUST** be unique per connection (W2-8). For a KEY/PREFIX target the
`path` **MUST** be a canonical §1 path; for FULL the `path` **MUST** be empty.

**W5-4a (the `WATCH_CREATE` flags).** The `flags` byte carries:

- **`bit0 = full_chain_verify`** — selects the untrusted-edge mode (W8-4): the server streams the verbatim
  signed chain and the client verifies + filters locally. Requires root-scope grant (W7-3).
- **`bit1 = prev_value`** — requests the pre-image of each change (etcd `prev_kv`, `prior-art.md` §1.2). A
  server **MAY** leave this **unsupported in v1** (the snapshot/delta model carries the post-image; the
  pre-image is a named v2
  extension, W10-2) — if unsupported, the server **MUST** ignore the bit, **MUST NOT** populate a `prev`
  field, and a driver **MUST NOT** require it unless it has negotiated it.
- **`bit2 = with_initial_snapshot`** — requests the **existing state** before tailing (W3-4): the server
  puts each covered shard into `SNAPSHOT_FIRST` (W5-5) and transfers a prefix-filtered catch-up snapshot
  (§5.8) **before** the first tail event, then resumes tailing. This is the **only** way to request existing
  state; **cursor `0` alone means "from now per shard," not "replay"** (W3-4). A driver that wants a
  watch-plus-current-state **MUST** set this bit (the v1-mandated snapshot-then-tail path).

A server **MUST** reject (or ignore, per the bit's rule above) a flag bit it does not recognize per the
fail-closed-on-unknown discipline (W1-3); a driver **MUST NOT** set a flag it has not negotiated.

### 5.3 `WATCH_CREATED` (server→client)

```
WATCH_CREATED payload:
  [ watch_id:u64 ]
  [ shard_count:u32 ]
  ( gid:u32  latest_seq:u64  mode:u8 )*shard_count     # mode: 0=TAIL, 1=SNAPSHOT_FIRST
```

**W5-5.** A successful, **authorized** `WATCH_CREATE` is acknowledged by exactly one `WATCH_CREATED`, which
**MUST** be the **first** frame the server emits for that `watch_id`. It carries the **per-shard initial
mode vector**: for each covered shard, the shard's current `latest_seq` and whether that shard's substream
starts in `TAIL` (cursor recoverable from the buffer) or `SNAPSHOT_FIRST` (the cursor is **behind the
buffer**, **or** the `with_initial_snapshot` flag is set — W5-4a — a catch-up snapshot follows for that
`(watch_id, gid)`, §5.8). A cursor component of `0` **alone** yields `TAIL`/from-now (W3-4); it does **not**
by itself select `SNAPSHOT_FIRST`. A driver **MUST** treat `WATCH_CREATED` as the "authorized and live"
signal. *(The per-shard mode vector is why the
connection-level `SUBSCRIBE_OK(latestSeq, Mode)` — a single scalar mode — cannot serve a multi-shard watch:
shards resume independently, so one may `TAIL` while another needs `SNAPSHOT_FIRST`.)*

### 5.4 `WATCH_EVENT` (server→client)

```
WATCH_EVENT payload:
  [ watch_id:u64 ]
  [ gid:u32 ]
  [ S:u64 ]                       # the shard's applied-mutation seq for THIS shard-commit
  [ commit_ts:u64 ]               # leader commit wall-clock (ADR-0035); freshness only, NOT a cursor (W3-3)
  [ change_count:u32 ]
  ( key_len:u32  key:bytes  kind:u8  val_len:i32  val:bytes )*change_count
                                  # kind: 0=PUT, 1=DELETE
                                  # val_len is i32 — the sole signed length width among the §2 watch frames
                                  #   (all others are u32); a driver MUST read it signed (a u32 read misparses
                                  #   the -1 sentinel as 4294967295):
                                  #     val_len >= 0  ⇒ value PRESENT (val_len == 0 ⇒ empty value present)
                                  #     val_len == -1 ⇒ NO value (a DELETE)
                                  # (the pre-image `prev` is NOT a v1 field; it is the W10-2 v2 extension)
```

**W5-6 (one event = one shard-commit; batch-atomic; ascending within `(watch_id, gid)`).** A `WATCH_EVENT`
carries the **matching changes of exactly one shard-commit** (one Raft entry = one `CommitNotification`).
The server **MUST NOT** split one shard-commit across multiple `WATCH_EVENT` frames (batch atomicity, W6);
**MUST NOT** coalesce multiple commits into one `WATCH_EVENT`; and **MUST** emit events in **ascending `S`
order within a `(watch_id, gid)`**. `changes` contains **only** the keys of that commit that match the
watch's target filter (trusted-edge filtered mode; W8-4). A commit with no matching keys produces **no**
`WATCH_EVENT` (the cursor still advances via the next event or a `WATCH_PROGRESS`; W4-4). *(An explicit
**fragmentation** flag for a single oversized commit that cannot fit one frame is a named v2 extension,
W10-3; out of scope for v1. The existing `NOTIFY` batch caps —
`MAX_NOTIFY_BATCH`/`MAX_NOTIFY_BATCH_BYTES` — and the 2 MiB frame cap bound a single `WATCH_EVENT`.)*

### 5.5 `WATCH_PROGRESS` (server→client — the bookmark)

```
WATCH_PROGRESS payload:
  [ watch_id:u64 ]
  [ cursor_vector ]               # W3-5 encoding: per covered shard, its current S (no events)
  [ server_now_millis:u64 ]       # for the staleness frontier (W8-3)
```

**W5-7.** `WATCH_PROGRESS` is the **bookmark** (etcd `progress_notify` analog). It carries, with **no
events**, the current per-shard `S` for the watch's covered shards, so an **idle** watcher can advance its
cursor components (W4-4) and so the driver can compute the per-shard staleness frontier (W8-3). The server
**MUST** emit `WATCH_PROGRESS` **periodically** for an otherwise-idle watch.

A bookmark component is bounded on **both** sides:

- **Lower bound (monotonic).** A `WATCH_PROGRESS` component **MUST NOT** be lower than one the client could
  already have derived from a delivered `WATCH_EVENT` on a live stream — a bookmark only advances. (Combined
  with the W6-5 component-wise max-merge, this also handles etcd's node-local-progress caveat: a lagging
  replica's lower bookmark never regresses the client's cursor.)
- **Upper bound (the no-silent-gap clamp — normative).** A `WATCH_PROGRESS` component for shard `gid`
  **MUST NOT exceed the serving tier's verified-and-filtered frontier** for that shard — the edge's applied
  cursor over commits it has **actually received, verified, and filtered**. A bookmark **MUST NOT** advance
  the client past commits the serving tier has **not itself examined**, because the client would then skip a
  matching change that arrives later for an `S` it has already passed — a **silent gap** that violates W6-1.
  In particular, when a bookmark rides the connection-level `HEARTBEAT`, the server **MUST** apply the same
  `latestSeq == cursor` clamp `StalenessTracker.recordFrontier` already encodes (it advances the frontier
  only where the edge's applied cursor has caught up to `latestSeq`) — it **MUST NOT** forward the raw
  `HEARTBEAT.latestSeq`, which can be **ahead** of what the edge verified and filtered.

*(`WATCH_PROGRESS` MAY ride the connection-level `HEARTBEAT` for an all-idle connection under the clamp
above; the per-watch `WATCH_PROGRESS` is the normative bookmark. The clamp machinery already exists —
`StalenessTracker.recordFrontier` — so this is one clause, not new machinery.)*

### 5.6 `WATCH_CANCEL` (client→server)

```
WATCH_CANCEL payload:
  [ watch_id:u64 ]
```

**W5-8.** Cancels the watch. The server **MUST** stop emitting frames for that `watch_id` after it
processes the cancel; the client **MUST** tolerate in-flight frames for a just-canceled `watch_id` until it
observes a quiescence (which is why a `watch_id` is **not** reused, W2-8). The connection and other watches
are unaffected. A server-initiated terminal close is `WATCH_CANCELED` (§5.7), not `WATCH_CANCEL`.

### 5.7 `WATCH_CANCELED` (server→client — terminal per-watch close)

```
WATCH_CANCELED payload:
  [ watch_id:u64 ]
  [ code:u8 ]                      # an ErrorCode (W7-5 / W6-4)
  [ has_oldest:u8 ]                # 0 or 1
  [ cursor_vector ]                # present iff has_oldest==1: per-shard oldestRetainedSeq (GAP case)
  [ msg_len:u32 ][ msg:bytes ]     # diagnostic only
```

**W5-9.** `WATCH_CANCELED` terminates **one** watch (not the connection). It carries an `ErrorCode` (the
existing taxonomy plus the §2 addition, W7-5) and, for the `GAP_UNRECOVERABLE` case (W6-4), the per-shard
`oldestRetainedSeq` vector so the driver knows where a re-list must land. The connection and other watches
survive. *(A connection-level fatal error uses `ERROR_CLOSE`, W5-2.)*

### 5.8 The catch-up substream (`WATCH_SNAPSHOT_*`)

```
WATCH_SNAPSHOT_BEGIN payload:  [ watch_id:u64 ][ gid:u32 ][ snapshot_seq:u64 ][ chunk_count:u32 ][ total_bytes:u64 ]
WATCH_SNAPSHOT_CHUNK payload:  [ watch_id:u64 ][ gid:u32 ][ index:u32 ][ bytes:bytes ]      # bytes ≤ 1 MiB
WATCH_SNAPSHOT_END   payload:  [ watch_id:u64 ][ gid:u32 ][ snapshot_seq:u64 ]
```

**W5-10 (per-shard inline catch-up — reuses the RR-102 mechanism).** When a shard component is in
`SNAPSHOT_FIRST` (fresh-with-existing-state, or fallen behind the buffer — W6-3), the server **MUST**
transfer a **prefix-filtered snapshot of that one shard** as a `WATCH_SNAPSHOT_BEGIN` → `*_CHUNK` →
`*_END` substream tagged `(watch_id, gid)`, backpressure-paced, with **cutover only after `END`** — then
resume **tailing that shard from `snapshot_seq`**. The snapshot is **prefix-filtered** to the watch's
target (ADR-0020), so a watcher receives only its target's keys, not the whole shard. **Only the lagging
shard's substream snapshots; the other shards' substreams MUST continue uninterrupted** (the cursor-vector
payoff vs etcd's whole-watch cancel; W6-3, `configd-analysis.md` §8). A driver **MUST** apply the chunks
in order, **MUST NOT** act on a partial snapshot before `END`, and on `END` **MUST** set
`cursor[gid] = snapshot_seq` and resume.

### 5.9 Wire version and negotiation

**W5-11.** Per W1-2, the §2 frames require edge wire version **`0x02`**. A connection speaks **exactly one**
edge wire version for its lifetime, **pinned by the first frame** — *agreed* here means first-frame-pinned,
**not** a negotiation handshake (no hello/capabilities frame; [`06-wire-framing.md`](06-wire-framing.md) §F4) —
the per-connection "design-A" model (W1-3).
On a `0x02` connection **every** frame — including a reused `NOTIFY` (W5-2) — carries the `0x02` version
byte; on a `0x01` connection every frame carries `0x01`. A `0x02`-capable server **MUST** still serve a
`0x01` connection (the existing fan-out plane) **byte-identically** — the `0x01` golden fixtures stay valid,
and §2 adds separate `0x02` fixtures (W1-3). A `0x01`-only peer **MUST** fail closed on a `0x02` frame
(`BAD_WIRE_VERSION`). A driver **MUST NOT** send a `WATCH_*` frame on a connection that negotiated `0x01`.

### 5.10 Naming reconciliation: `SUBSCRIBE` vs `WATCH_CREATE`

**W5-12 (normative reconciliation — read with §3 AU4-1).** The RFC uses two subscription concepts; a driver
**MUST** keep them distinct:

- **`SUBSCRIBE` / `SUBSCRIBE_OK`** (`0x01`/`0x02`) is the **connection-level** edge fan-out subscription
  (one per connection; the built plane; carries a **scalar** `resumeCursor` + `failoverResumeCursor`). It is
  **not** the client-facing watch.
- **`WATCH_CREATE` / `WATCH_CANCEL`** (`0x0A`/`0x0B`) is the **per-watch** subscription (many per
  connection; carries a `watch_id` and the **cursor vector**).

§3's generic word **"SUBSCRIBE"** in the connection lifecycle
([AU4-1](03-authentication.md#4-the-connection-lifecycle--authenticate-before-any-data),
[AU8-2](03-authentication.md#8-composition-with-1-and-2)) refers, **on the watch path**, to
**`WATCH_CREATE`**: authentication (the mTLS handshake) completes first, then the **`WATCH_CREATE`** is
authorized as a streaming read **before any data frame** (W7, W8-2). The scalar `SUBSCRIBE.resumeCursor` is
the **N = 1 degenerate** of the `WATCH_CREATE` cursor vector; a driver **MUST** treat the client-facing
cursor as a vector regardless (W1-1).

---

## 6. Delivery and ordering guarantees

This is the **honesty** section: a driver **MUST** rely on exactly the guarantees that hold, and **MUST
NOT** rely on the ones that do not. This surface is the cross-shard consistency contract
(ADR-multiraft-cross-shard / ADR-0019) projected onto a change stream; it is **identical** to §1's subtree
ordering contract (§1 [A4-2](01-paths-and-access.md#41-a-subtree-scatters-subtree-ops-are-scatter-gather),
[A9-2](01-paths-and-access.md#9-forward-compatibility-and-composition-with-the-watch-section)).

**W6-1 (the guarantee table — normative).**

| Guarantee | Holds? | Basis / driver obligation |
|---|---|---|
| **Per-key order** — one key's PUT/DELETE events arrive in commit order | ✅ | a key → one shard → one monotonic `S` |
| **Per-shard order** — one shard's events arrive in ascending `S`, gap-free within the window | ✅ | one Raft log per shard; `readSince` contiguity |
| **Atomic per shard-commit** — all matching keys of one shard `BATCH` arrive together | ✅ | one Raft entry = one `CommitNotification` = one `WATCH_EVENT` (W5-6) |
| **No silent gap within the window** — no committed change to a watched key is skipped while the component stays in-window | ✅ | `readSince` ⇒ `Ok` (contiguous) **or** `Gap` (W6-3); never a silent skip |
| **At-least-once + `(gid, S)` dedup** — resume/failover MAY redeliver from the cursor | ✅ | a driver **MUST** dedup: **drop a `WATCH_EVENT` iff `S ≤ cursor[gid]`** (W4-2) |
| **Bounded-staleness freshness, per shard** | ✅ | exposed via the frontier (W8-3); a watch is **ordered, not linearizable** |
| **Cross-shard / global total order** | ❌ | **no** cross-shard log/sequencer (§3, W3-2); driver **MUST NOT** assume it (W6-2) |
| **Cross-shard atomicity** — a multi-shard change is all-or-nothing | ❌ | DISCLAIMed for writes; watches inherit it |
| **Linearizable / read-after-write via the watch** | ❌ | a watch may lag the leader; use the strong-read path for freshness (W8-1) |
| **A single scalar resume token** | ❌ | resume is a **vector** (§3, W1-1) |

**W6-2 (the cross-shard-order prohibition — restate to the application).** A driver **MUST NOT** present or
imply cross-shard / global order. Concretely: if a client PUTs `A` (shard 0) then `B` (shard 1), a PREFIX
watch covering both **MAY** deliver the `B` event before the `A` event. A driver's public API **MUST**
surface **per-key** and **per-shard** order only, and **SHOULD** document this explicitly so application
authors do not encode a false global-order assumption.

**W6-3 (compaction / too-old ⇒ inline per-shard snapshot resync, not whole-watch cancel).** When a
component `S_i` has fallen behind shard `i`'s retained window, the buffer's `readSince(S_i)` returns
`Gap(oldestRetainedSeq)`. The server **MUST** then put **only that shard's** substream into
`SNAPSHOT_FIRST` and perform an **inline per-shard catch-up snapshot** (§5.8), then resume tailing. That the
**other shards' substreams continue uninterrupted** is a **v2 server obligation** — vacuous at N = 1 (one
shard, one substream, the built single-session path), live only when the v2 sharded edge serves N > 1
substreams (W3-7, W9-3). A driver **MUST** handle a mid-stream `WATCH_SNAPSHOT_*` substream for one `gid`
without tearing down the whole watch. *(Contrast for implementors: etcd cancels the **whole** watch on
compaction (`ErrCompacted`) and the client re-lists everything — `prior-art.md` §1.4; Configd re-lists
**one shard, inline**, and keeps the rest streaming; `configd-analysis.md` §8.)*

**W6-4 (the genuinely-unrecoverable case ⇒ terminal `WATCH_CANCELED(GAP_UNRECOVERABLE)`).** If the replay
source cannot cover the needed range **at all** (not even via snapshot), the server **MUST** terminate that
watch with `WATCH_CANCELED(watch_id, GAP_UNRECOVERABLE, oldest=<per-shard oldestRetainedSeq>)`. The driver
**MUST** then **re-list current state** (per §1's paginated `list`, §1
[§4.2](01-paths-and-access.md#42-the-list-operation), using the **same** cursor-vector type) and
**re-create** the watch from the new frontier. This is the only case where a driver rebuilds its baseline
(the etcd `ErrCompacted` fallback, `prior-art.md` §1.4) — the common too-old case is self-healing (W6-3).
*(`WATCH_CANCELED(GAP_UNRECOVERABLE)` is a **new per-watch terminal**: the connection and sibling watches
survive. The built plane has only a **connection-level** `ERROR_CLOSE(GAP_UNRECOVERABLE)`, so this is new
session-layer state-machine work, recorded in W9-2.)*

**W6-5 (failover — no-miss / no-dup, per shard independently).** On an endpoint or shard-leader change, a
driver **MUST** resume each shard substream at its cursor component and **MUST NOT** regress a component
(it **MUST** component-wise max-merge any failover cursor — the scalar `effectiveResumeCursor =
max(resumeCursor, failoverResumeCursor)` of the built plane, generalized to a vector; W3-6). Because `S` is
deterministic and leader-independent (W3-2), resume against a freshly elected leader yields the contiguous
run `S > S_i` (or a `Gap` → W6-3) — **no missed events, no duplicates beyond the resume boundary** (the
driver dedups by `S`, W6-1). **Shards fail over independently** — a failover on one shard **MUST NOT**
perturb another's cursor or stream (the vector payoff vs a single global cursor, which would rebuild
wholesale; `configd-analysis.md` §7). This cross-shard failover-independence is, like W3-7 and W6-3, a
**v2 server obligation** (vacuous at N = 1, where there is one shard and one failover timeline); the
driver's per-component max-merge is vector-native from v1 regardless (W1-1).

**W6-6 (per-shard read-your-writes, not cross-shard).** A write ack returns the `(gid, S)` of the commit
(ADR-0033/0019). A driver that wants to observe its **own** write on a watched key **MUST** ensure its
watch cursor for that `gid` starts **≤ `S`**. This is **per-shard** read-your-writes (INV-RYW), **not**
cross-shard; a driver **MUST NOT** generalize it to a global guarantee.

---

## 7. The watch-authorization contract

> *The full streaming `ErrorCode` reaction table — including `NOT_AUTHORIZED` (the `403`-class per-watch reject
> this section defines, carried in `WATCH_CANCELED`) and the catch-up ladder — is consolidated in
> [§07](07-errors.md) (the single source of truth); §07 and this section **MUST** agree.*

A watch is authorized as a **streaming read**. This section **restates §1
[§6](01-paths-and-access.md#6-the-watch-authorization-contract-normative) normatively**; both **MUST
agree**, and **§2 MUST NOT weaken §1** (§1
[A9-3](01-paths-and-access.md#9-forward-compatibility-and-composition-with-the-watch-section)). The clauses
below are the §1 A6 clauses bound to the §2 wire.

**W7-1 (authorize at subscription, before any data — = §1 A6-1).** A watch on target `T` **MUST** be
authorized **at `WATCH_CREATE` time, before any payload-bearing server→client frame is emitted** for it —
specifically before any `WATCH_CREATED`, `WATCH_SNAPSHOT_*`, `WATCH_EVENT`, `WATCH_PROGRESS`, **or, in
`full_chain_verify` mode, any connection-level `NOTIFY`** (W5-2 / W8-4) — as a streaming read: the principal
(the mTLS cert-DN identity,
§3 [AU3-2](03-authentication.md#3-credential-presentation-on-the-wire)) **MUST** hold **`READ(T) ∧
WATCH(T)`** under §1's evaluation rule (§1
[A5-4](01-paths-and-access.md#53-evaluation-normative--identical-across-drivers-and-server): union of ALLOW
minus DENY, deny-precedence, default-deny) — **both** capabilities, covering **all** of `T`.

**W7-2 (whole-target coverage; reject over-broad, do not filter — = §1 A6-2).** If `T` extends beyond the
principal's authorized region, the server **MUST reject** the `WATCH_CREATE` (W7-5) and **MUST NOT**
silently narrow it to the authorized subset. A PREFIX/FULL watch requires the grant to cover the **whole**
subtree — **not** a single-key `isAllowed` check. *(Silent narrowing would give the client a
false-completeness view indistinguishable from "no changes"; a client wanting the authorized subset **MUST**
request that narrower target explicitly.)*

**W7-2a (whole-subtree coverage evaluation — the universal-quantifier lift of §1 A5-4).** §1
[A5-4](01-paths-and-access.md#53-evaluation-normative--identical-across-drivers-and-server) evaluates a
**single concrete path**. Lifting it to a PREFIX/FULL target `T` requires a **universal quantifier**: a
watch on `T` is authorized for capability `C` (here each of `READ` and `WATCH`) **iff for every key
`k ∈ T`: `C ∈ allow(k)` AND `C ∉ deny(k)`** — i.e. the union of matching ALLOW rules covers the **entire**
subtree **AND no DENY rule intersects it**. An intersecting **interior DENY** — e.g. ALLOW `READ,WATCH` on
`/a/` but DENY `READ` on `/a/secret`, target PREFIX `/a/` — **MUST** cause the subscription to be
**rejected** (W7-5), **never** silently filtered to the allowed remainder (false-completeness, W7-2). A
server **MUST NOT** discharge this by evaluating A5-4 once at the target root — that misses the interior
hole. *(Forward note: §1 §6 carries the same whole-target requirement (A6-2 / A6-4) and **may mirror this
explicit universal-quantifier phrasing** in a future §1 touch-up; this PR does **not** edit the merged §1.)*

**W7-3 (`full_chain_verify` / `FULL` requires root scope — = §1 A6-3).** A `WATCH_CREATE` with
`full_chain_verify = true`, or whose `target_kind = FULL`, streams the **entire signed chain verbatim with
no edge filtering** (W8-4) and therefore **MUST** require the principal to hold **`READ ∧ WATCH` over the
root `/**`** for that `scope`. **The full signed chain MUST NOT stream to a principal lacking the
full-scope grant.** A principal with only a subtree grant that sets `full_chain_verify`/`FULL` **MUST** be
rejected (W7-5) — it **MUST NOT** receive other subtrees' data under the guise of local verification.

**W7-4 (INV-WATCH-READ — = §1 A6-4).** For every key `k` a watch could deliver, if `READ k` would be denied
to the principal, the watch **MUST** be denied. A watch **MUST NEVER** deliver a change for a key the
principal could not read. The whole-target check at subscription (W7-2 / W7-2a) discharges this **as of
subscription, and as of each resume (W5-4),** without per-event re-checks. **But this sufficiency is scoped
to that instant:** Configd ships **live, atomic ACL reload** (the Increment-5/6 monotonic policy-version
snapshot), so a principal's grant **can be revoked while a persistent watch (W2-5) is mid-stream** on an
otherwise-healthy connection. The subscription-time check alone is therefore **not** sufficient for the
watch's whole lifetime — **W7-7** mandates bounded revocation to close that window.

**W7-5 (error surface + the mandatory negative test — = §1 A6-5; the 401/403 streaming taxonomy).** An
unauthorized `WATCH_CREATE` **MUST** be terminated by a `WATCH_CANCELED` carrying the **`403`-class**
`ErrorCode` `NOT_AUTHORIZED` (W7-5a), with **no data frame emitted first** — specifically **zero**
`WATCH_CREATED`, `WATCH_SNAPSHOT_*`, `WATCH_EVENT`, `WATCH_PROGRESS`, **or (in `full_chain_verify` mode)
`NOTIFY`** frames for that watch precede the close (i.e. **no payload-bearing server→client frame**, W7-1).
A failed **authentication** (the mTLS handshake / identity) is the **`401`-class** `AUTH_FAIL`,
surfaced at the **connection** level: the **TLS handshake fails / the connection is reset** — `AUTH_FAIL` is a
server-side close-reason/metric, **not** a wire frame a driver receives (§3
[AU4-1](03-authentication.md#4-the-connection-lifecycle--authenticate-before-any-data),
[AU5-1](03-authentication.md#5-error-taxonomy-the-401-side-composes-with-1-7); §07 E3-1). A conforming server
implementation **MUST** have a regression test proving that **(a)** an over-broad-target watch and **(b)** a
non-root-scope `full_chain_verify`/`FULL` watch are **each** rejected with a `403`-class `WATCH_CANCELED`
and **zero** preceding data frames — and case **(b) MUST explicitly assert zero `NOTIFY` frames** precede
the reject (the `full_chain_verify` carrier is the connection-level `NOTIFY`, W8-4; a test that checks only
the `WATCH_*` frames would pass while a `NOTIFY` firehose leaks the full chain to a non-root principal —
defeating the one test this contract mandates for the `full_chain_verify` gate).

**W7-5a (the 403-class error code — a REQUIRED §2 addition to the taxonomy).** §1
[A6-5](01-paths-and-access.md#6-the-watch-authorization-contract-normative) requires the streaming
authorization reject to be a **`403`-class code, distinct from the `401`-class**. The existing closed
`ErrorCode` taxonomy has `AUTH_FAIL` (the `401`/identity case) but **no distinct authorization code**.
Therefore §2 **adds one** `ErrorCode` — **`NOT_AUTHORIZED` (code `11`)**, the `403`-class authorization
reject — distinct from `AUTH_FAIL` (`4`, the `401`-class authentication failure). `11` is the **next free**
`ErrorCode` (the built taxonomy is codes `1`..`10`, with `PROTOCOL_VIOLATION = 10` — parallel to the
next-free `FrameType` `0x0A`, W5-1). This is a closed-taxonomy change and rides the same `0x02`
wire-version bump (W1-2). The mapping a server **MUST** use:

| Streaming condition | `ErrorCode` | HTTP-equivalent class (§1 [§7](01-paths-and-access.md#7-error-taxonomy)) |
|---|---|---|
| identity / credential unacceptable (authentication) | `AUTH_FAIL` (4) | **401** |
| authenticated but not granted (over-broad watch, non-root `full_chain_verify`, `DENY`, missing `WATCH`/`READ`) | `NOT_AUTHORIZED` (11) | **403** |
| malformed target / path / cursor at subscription | `BAD_SUBSCRIBE` (5) | **400** |

**W7-6 (driver reaction).** A driver **MUST** treat a `403`-class `WATCH_CANCELED` (`NOT_AUTHORIZED`) as
**permanently forbidden for this principal and target** — it **MUST NOT** retry the same `WATCH_CREATE`
unchanged (§1 [A7-2](01-paths-and-access.md#7-error-taxonomy), §3
[AU5-4](03-authentication.md#5-error-taxonomy-the-401-side-composes-with-1-7)). A driver **MUST** treat a
`401`-class connection close as **(re)authenticate**. A driver **MUST NOT** attempt a
`full_chain_verify`/`FULL` watch unless it expects the root-scope grant (W7-3; §3
[AU6-3](03-authentication.md#6-the-authenticated-principal-feeds-authorization-the-seam-driver-visible-consequences)),
to avoid a predictable `403`.

**W7-7 (bounded revocation under live ACL reload — normative).** A persistent watch (W2-5) outlives the
instant it was authorized, and Configd applies ACL policy changes **atomically at runtime** (the
Increment-5 monotonic ACL-snapshot version, observable at the serving tier). The server **MUST** therefore
**re-authorize every live watch** (per W7-1 / W7-2 / W7-2a / W7-3) on an **ACL policy-version change**, and
**MUST** force-close — `WATCH_CANCELED(watch_id, NOT_AUTHORIZED)` (W7-5) — any watch whose principal **no
longer** holds `READ ∧ WATCH` over its whole target, **within a bounded latency** of the policy-version
advance. **The bound is the property this clause fixes; the trigger is left to the veneer** (poll the
monotonic policy version, or subscribe to a policy change-notify — either satisfies the property). This is
**session/edge-layer** work, **not** consensus-tier: it composes with the **same monotonic ACL-snapshot
version the serving tier observes** (the edge has no authz today — this is part of the new watch-authz ADDS
work, W9-2), with the same monotonic discipline as `StalenessTracker`'s frontier (a
version only advances; a revocation is observed at or after the version that removed the grant). A driver
**MUST** treat a mid-stream `NOT_AUTHORIZED` `WATCH_CANCELED` as a revocation (W7-6 — do not retry
unchanged). *(This **strengthens** the contract; it does not weaken §1. Forward note: §1 A6-4's "without
per-event re-checks" carries the same implicit "as of subscription" scope and **should mirror this
bounded-revocation clause** in a future §1 touch-up; this PR does **not** edit the merged §1.)*

---

## 8. Served-from and transport

**W8-1 (edge-served is the v1 model; bounded-stale; not for read-after-write).** A watch **MUST** be
servable from the **edge fan-out plane** — replicas that already receive every committed change as a
per-shard `CommitNotification` stream — and **SHOULD** be served there by default. An edge-served watch is
**bounded-stale** relative to the leader (the fan-out latency; budget < 500 ms p99, ADR-0019; exposed via
the frontier, W8-3). A client needing **read-after-write freshness** on a specific key **MUST** use the
**linearizable strong-read path** for that key, **NOT** the watch (a watch is *ordered*, not
*linearizable*; W6-1). *(Rationale: the edge tier is the hardened, horizontally-scalable read/watch tier;
serving watches there offloads the scarce consensus plane — the etcd "any member serves a watch" precedent;
`configd-analysis.md` §4, `prior-art.md` §1.6.)*

**W8-2 (the connection lifecycle — authn → authz → stream).** A watch session **MUST** follow: **(1)**
authenticate — the mTLS handshake completes **before** any frame (§3
[AU3-2](03-authentication.md#3-credential-presentation-on-the-wire),
[AU4-1](03-authentication.md#4-the-connection-lifecycle--authenticate-before-any-data)); **(2)** for each
`WATCH_CREATE`, authorize the subscription as a streaming read **before any data frame** (W7); **(3)**
stream events / bookmarks / catch-up; **(4)** resume on reconnect by re-sending the cursor vector (W6-5).
A driver **MUST NOT** send any frame before the handshake completes, and **MUST** expect a terminal
`401`-class close for an unacceptable identity and a `403`-class `WATCH_CANCELED` for an over-grant
subscription — **with no data frame first** in either case (§3
[AU8-2](03-authentication.md#8-composition-with-1-and-2)).

**W8-3 (staleness frontier).** A driver **MAY** compute a per-shard staleness frontier from the
`commit_ts` on `WATCH_EVENT` (W5-6) and the `server_now_millis` on `WATCH_PROGRESS` (W5-7), mirroring the
edge `StalenessTracker` (frontier `= max(commit_ts of last applied, server_now where the cursor is caught
up)`; ADR-0039). This frontier is **freshness**, not identity — it **MUST NOT** be used as a cursor (W3-3).

**W8-4 (trust modes — trusted-edge filtered default vs `full_chain_verify`).** Two delivery modes, selected
by the `full_chain_verify` flag (W5-4a):

- **Trusted-edge filtered (default, `full_chain_verify = false`).** The edge **verifies** the leader-signed
  chain and **then** filters to the watch target, delivering matching changes as `WATCH_EVENT` (W5-6). **A
  malicious or compromised edge CAN selectively suppress a matching change undetectably in this filtered
  mode** (`configd-analysis.md` §5): the client sees only what the edge forwards and cannot, from a filtered
  stream alone, prove completeness. The property that "the residual risk is **wholesale stream-stall**
  (detected by the staleness frontier, W8-3), **not** selective suppression" therefore holds **ONLY under
  the trusted-edge assumption** — client ↔ its **own** edge/sidecar, in the operator's trust domain, over
  mTLS. A conforming deployment **MUST** document this trust boundary. An **untrusted** edge **MUST** use
  `full_chain_verify` (below) or the v2 signed-skip-evidence path (W10-5). *(This is a completeness/honesty
  property, not a confidentiality bypass — suppression shows the client **less**, never **more**. ADR-0038:
  server-side filtering would break the signed chain, so the edge filters **post-verification**;
  `configd-analysis.md` §4.3, §5.)*
- **`full_chain_verify = true` (untrusted edge).** The server streams the **full verbatim signed chain**
  (reusing `NOTIFY`, W5-2) with **no** edge filtering; the **client** verifies the signatures and filters
  locally — at the cost of receiving the whole write firehose (ADR-0038 ≈ write-stream rate). This mode
  **MUST** require root-scope grant (W7-3). It suits a client watching a **broad** prefix on an untrusted
  edge; it is inappropriate for a thin one-key watcher.

**W8-5 (leader-served is out of scope for v1).** A **leader-served** watch (a session on the shard owner
thread, freshest, no extra hop) **MAY** be offered as a **v2** latency fast-path for latency-critical
watchers, with the explicit understanding that it **loads the consensus plane**. It is **out of scope for
§2 v1** (W10-4).

**W8-6 (transport).** The watch session **MUST** run over the streaming **mTLS edge session** (§3
[AU3-2](03-authentication.md#3-credential-presentation-on-the-wire)); the cert-DN identity is
**authoritative** and any self-asserted identity field (e.g. an `edgeId`) is **advisory** (§3
[AU3-2](03-authentication.md#3-credential-presentation-on-the-wire)). Backpressure and abuse control reuse
the existing `CURSOR_ACK` flow-control and `SlowConsumerGovernor` ladder
(HEALTHY→SLOW→CATCHUP→QUARANTINED→UNHEALTHY) and the existing `ErrorCode` taxonomy unchanged (plus W7-5a).

**Backpressure and abuse control are per-CONNECTION in v1.** `CURSOR_ACK(seq)` is a **connection-level
scalar** (it carries **no** `watch_id`), and `SlowConsumerGovernor` acts **per connection / identity** — so
**all** multiplexed watches on one connection **share fate**: a single slow or greedy watch can demote
(`DEMOTED_TO_CATCHUP`) or quarantine (`QUARANTINED`) **every** sibling on that connection (head-of-line
blocking, inherent to one shared mTLS transport). A driver **MUST** tolerate a `DEMOTED_TO_CATCHUP` notice
(its session moved from streaming to snapshot catch-up) and a `QUARANTINED` close (re-bootstrap after
cooldown), and **MUST NOT** assume per-watch isolation of flow-control or fairness in v1. The mismatch — a
**scalar** `CURSOR_ACK` vs the per-watch cursor **vector** — is real and acknowledged; **per-watch
flow-control / fairness is a named v2 extension (W10-8)**.

**W8-7 (metadata side-channel — acknowledged; not an INV-WATCH-READ violation).** The per-shard sequence
`S` exposed in `WATCH_CREATED.latest_seq` (W5-5), `WATCH_PROGRESS` (W5-7), and `WATCH_CANCELED.oldest`
(W5-9) reveals a shard's **write volume and timing** even to a watcher authorized for only a narrow key on
that shard. This is an inherent **metadata side-channel** of any cursor/revision model (etcd's revision is
identical) and is **NOT** an INV-WATCH-READ violation (W7-4) — it discloses **aggregate count/timing**,
never another key's identity or value. The RFC acknowledges it; a deployment that treats per-shard
write-volume as sensitive **MUST** isolate such tenants onto separate shards/edges.

---

## 9. Implementability over the built fan-out plane

This section grounds §2 in the **built** substrate so the future veneer assumes no machinery that does not
exist. §2 **MUST NOT** require anything in the "ADDS" column to be invented elsewhere; it is the §2 work
item.

**W9-1 (what EXISTS — reused unchanged).**

| Capability | Built mechanism (HEAD `3e651ba`) |
|---|---|
| Change stream unit | `CommitNotification{seq = S, commitTimestampMillis, delta}` (ADR-0034) |
| Cursor + replay boundary | `CommitNotificationSource.readSince(cursor) → Result.Ok(run) \| Result.Gap(oldestRetainedSeq)` |
| Hot buffer (drop-oldest, GAP-signalling) | `FanOutBuffer` — bounded ring, single-writer, lock-free readers, `lastEvictedSeq` watermark (ADR-0036, RR-066/RR-096) |
| Per-shard fan-out | one `FanOutBuffer` **per shard** on its owner thread (Seam G1) |
| Wire codec | `EdgeFrame` family + `EdgeFrameCodec` (`EDGE_WIRE_VERSION 0x01`, length-prefixed, CRC32C, golden-pinned) |
| Connection-level subscribe | `Subscribe{fullStore, prefixes, resumeCursor (scalar), failoverResumeCursor, edgeId}`; `effectiveResumeCursor()` |
| Resume-mode decision | `SubscribeOk{latestSeq, Mode∈{TAIL, SNAPSHOT_FIRST}}` |
| Chunked catch-up | `SnapshotBegin/Chunk/End` (RR-102; backpressure-paced, cutover-after-END) |
| Freshness frontier | `StalenessTracker` (ADR-0039) |
| mTLS endpoint + admission | `FanOutServer` (cert-DN identity, `maxSessions`, bounded per-conn queue) |
| Backpressure / abuse | `SlowConsumerGovernor` ladder + 10-code `ErrorCode` taxonomy |

**W9-2 (what §2 ADDS — the veneer work item).**

| Addition | Where in §2 |
|---|---|
| Per-shard cursor **vector** on the wire (today `Subscribe.resumeCursor` is a **scalar**) | §3 (W3-5) |
| `watch_id` **multiplex** (many watches per connection; today one full-store stream per connection) | §2.2 (W2-8), §5 |
| Per-event **`(gid, S)` tagging** (today single-group; no `gid` on the wire) | §5.4 (W5-6) |
| Per-watch **prefix filter** at the edge (today the edge streams the full chain, ADR-0038) | §8 (W8-4) |
| The **`WATCH_*` frames** (`0x0A`..`0x12`) + `WATCH_SNAPSHOT_*` multiplex-tagged catch-up | §5 |
| The **`NOT_AUTHORIZED` (11)** 403-class error code | §7 (W7-5a) |
| The **`0x02`** wire-version bump | §1 (W1-2), §5.9 |
| The **`with_initial_snapshot`** request flag (watch-plus-current-state) | §5.2 (W5-4a) |
| **Per-watch terminal-close** state machine — `WATCH_CANCELED(GAP_UNRECOVERABLE \| NOT_AUTHORIZED)` per watch (built plane has only **connection-level** `ERROR_CLOSE`) | §5.7 (W5-9), §6 (W6-4) |
| **Bounded watch revocation** on an ACL policy-version change (composes with the Increment-5 monotonic ACL-snapshot version; session/edge-layer) | §7 (W7-7) |
| The **bookmark upper-bound clamp** (`WATCH_PROGRESS` ≤ verified+filtered frontier; reuses `StalenessTracker.recordFrontier`) | §5.5 (W5-7) |

**W9-3 (v1 = N = 1; multi-shard wire = v2; the protocol is vector-native from v1).** The built per-shard
`FanOutBuffer` exists but the **sharded edge client (cursor-vector + multi-shard scatter-gather) is
v2-deferred** — the edge endpoint **fail-closes at N > 1** today
([`configd-analysis.md`](../../research/watches/configd-analysis.md) §4.2), unless an operator sets
`-Dconfigd.edge.allowPartialShardView=true` — a **primary-shard-only, partial** view, **not** a real
multi-shard watch. Therefore an
**N = 1 edge-served watch is the v1-capable productization** of the hardened plane (a one-element cursor
vector; at N = 1 a single shard even has a total order, so the cross-shard caveats are **dormant**), while
the **multi-shard (N > 1) cursor-vector watch is v2**, riding the already-scoped v2 sharded edge. **The
wire and the drivers are specified vector-native from v1** (W1-1) so nothing breaks when the cluster shards;
the dormant complexity costs ~nothing at N = 1.

---

## 10. Forward-compatibility and composition with §1 and §3

**W10-1 (composition — the shared types and contracts).** §2 composes with §1 and §3 as follows; a driver
implements each **once** and reuses it:

- **The cursor vector is shared with §1.** The watch resume cursor (W3-5) and the §1 `list` continuation
  cursor (§1 [A4-4](01-paths-and-access.md#42-the-list-operation),
  [A9-1](01-paths-and-access.md#9-forward-compatibility-and-composition-with-the-watch-section)) are the
  **same** `(uint32 gid, uint64 S)[]` type; a driver **MUST** implement **one** cursor-vector type and use
  it for both — **vector even at N = 1** (W1-1).
- **The ordering contract is shared with §1.** §2's per-key/per-shard/never-cross-shard surface (W6-1) is
  **identical** to §1's subtree ordering (§1
  [A4-2](01-paths-and-access.md#41-a-subtree-scatters-subtree-ops-are-scatter-gather),
  [A9-2](01-paths-and-access.md#9-forward-compatibility-and-composition-with-the-watch-section)). A driver
  **MUST** present the same semantics for `list` and `watch`.
- **The authorization contract lives in §1; §2 restates and MUST NOT weaken it.** §7 ≡ §1
  [§6](01-paths-and-access.md#6-the-watch-authorization-contract-normative) (A6-1..A6-5), bound to the §2
  wire; the `full_chain_verify`/`FULL` root-scope gate (W7-3) is §1 A6-3.
- **Authentication is §3; §2 places it before authorization.** The watch order is authn (handshake) → authz
  (subscription) → stream (W8-2; §3 [AU8-2](03-authentication.md#8-composition-with-1-and-2)). §2 does
  **not** redefine the 401/403 *definitions* (§3 owns 401, §1 owns 403); it specifies how they surface as
  terminal frames (W7-5/W7-5a) and reconciles the `SUBSCRIBE`/`WATCH_CREATE` naming (W5-12).

**W10-2..W10-8 (named forward extensions — fail closed on the unrecognized).** The following are **named**
forward extensions; a driver **MUST** fail closed (not assume) any it has not negotiated (§1
[A9-4](01-paths-and-access.md#9-forward-compatibility-and-composition-with-the-watch-section), §3
[AU7-1](03-authentication.md#7-forward-compatibility)):

- **W10-2 — `prev_value` / pre-image** on `WATCH_EVENT` (etcd `prev_kv`; the `prev_value` flag, W5-4a).
- **W10-3 — single-commit fragmentation** for an oversized atomic shard-commit that cannot fit one
  `WATCH_EVENT` (etcd `fragment`, `prior-art.md` §1.5; W5-6).
- **W10-4 — leader-served fast-path** for latency-critical watchers (W8-5), accepting consensus-plane load.
- **W10-5 — signed-skip-evidence** (leader-signed per-range Merkle summaries) for
  **selective-suppression-proof filtered watches against an untrusted edge** without the full firehose
  (ADR-0038's named v2+ path; supersedes the W8-4 `full_chain_verify` cost).
- **W10-6 — a v2 `filtered-watch` mode** that returns the authorized subset of an over-broad target **with
  an explicit "narrowed" signal** (distinct from the W7-2 reject; §1
  [A9-4](01-paths-and-access.md#9-forward-compatibility-and-composition-with-the-watch-section)).
- **W10-7 — a long-poll / HTTP gateway** for HTTP-only clients (à la etcd's gRPC-gateway), **not** the
  canonical watch (W2-7).
- **W10-8 — per-watch flow-control / fairness** (a per-`watch_id` `CURSOR_ACK` / credit scheme) so a single
  watch no longer shares backpressure fate with its connection siblings (W8-6). v1 backpressure is
  **per-connection** (`CURSOR_ACK` is a connection-level scalar); this lifts it to per-watch.

A driver **MUST NOT** encode an assumption that ties it to any extension above; new server-emitted event
fields the driver does not recognize **MUST** be ignored, not rejected (forward-compat).

---

## 11. Summary of normative requirements (driver checklist)

- [ ] The resume cursor is a **per-shard vector** `(uint32 gid, uint64 S)[]` ordered by `gid`; **vector even
      at N = 1**; a scalar cursor or global-order assumption is **FORBIDDEN** (W1-1, W3-1, W3-5).
- [ ] One cursor-vector type, **shared** with §1's `list` cursor; support update-one, **component-wise
      max-merge**, serialize (W3-6, W10-1).
- [ ] Cursor `0`/omitted = **"from now per shard"**, not "replay all"; request existing state with the
      **`with_initial_snapshot`** flag (cursor `0` alone ⇒ TAIL/from-now) (W3-4, W5-4a, W5-5).
- [ ] Watches are **persistent, resumable, streaming, `watch_id`-multiplexed**; `watch_id` unique per
      connection, **never reused** (W2-5..W2-8).
- [ ] Targets are **KEY / PREFIX / FULL**; PREFIX/FULL **scatter across all shards**; never route/order by
      prefix (W2-2..W2-4).
- [ ] Merge multi-shard substreams as a **UNION**; present **per-key order only**; **advance idle cursors on
      `WATCH_PROGRESS` bookmarks** (W4-2..W4-4). A server's bookmark **never exceeds** the serving tier's
      verified+filtered frontier (no silent gap, W5-7).
- [ ] **Dedup**: drop a `WATCH_EVENT` iff `S ≤ cursor[gid]`; delivery is **at-least-once** (W6-1).
- [ ] Guarantees: per-key ✅, per-shard ✅, batch-atomic-per-shard-commit ✅; cross-shard/global order ❌,
      cross-shard atomicity ❌, linearizable ❌ (W6-1, W6-2).
- [ ] Too-old ⇒ **inline per-shard `WATCH_SNAPSHOT_*` resync** (others keep streaming); only
      `GAP_UNRECOVERABLE` forces a re-list + re-create (W6-3, W6-4).
- [ ] Failover: resume each component, **never regress**; shards fail over **independently** (W6-5).
- [ ] A watch is authorized **at subscription** as `READ ∧ WATCH` over the **whole** target, **before any
      data frame**; whole-subtree coverage with an **interior `DENY` ⇒ reject** (not filter); over-broad
      targets **rejected, not filtered**; `full_chain_verify`/`FULL` needs **root scope**; INV-WATCH-READ
      (W7-1..W7-4, W7-2a).
- [ ] Re-authorize/force-close a live watch within a **bounded latency** on an ACL policy-version change
      (revocation is not subscription-time-only) (W7-7).
- [ ] `403`-class `NOT_AUTHORIZED` `WATCH_CANCELED` with **zero preceding data frames** (incl. **zero
      `NOTIFY`** in `full_chain_verify` mode) on an unauthorized watch; `401`-class `AUTH_FAIL` at the
      connection; **mandatory negative test** (W7-5, W7-5a).
- [ ] **Edge-served, bounded-stale**; use the **strong-read** path for read-after-write, not the watch
      (W8-1); compute staleness from `commit_ts`/`server_now`, never as a cursor (W8-3).
- [ ] Trust modes: **trusted-edge filtered** (default) vs **`full_chain_verify`** (root-scope, client filters
      the verbatim chain) (W8-4).
- [ ] **Fail closed** on the `0x02` wire version you have not negotiated and on any unknown frame/extension
      (W1-3, W5-11, W10-2..W10-8).
