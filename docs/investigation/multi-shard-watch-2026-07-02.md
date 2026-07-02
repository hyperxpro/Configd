# Multi-Shard Watch Investigation — the ordering contract, completeness, the coordinator, authz across shards, and the filtering interaction

**Date:** 2026-07-02
**Type:** READ-ONLY investigation (design-grounding, not building). Nothing in the repo was changed except this file.
**Method:** 5-lane Opus team — protocol (LEAD), reliability, security, prior-art, java-engineer — each grounded in code (file:line), RFC §2, and primary external sources. Load-bearing claims (the `_acl/` P0 wiring, the GAP-fix ancestry, the per-shard fan-out wiring, RFC §2 end-to-end) independently re-verified by the coordinator against source.
**Standing decision this serves:** multi-shard watches are **IN v1** (operator decision; there is no v2 for this feature). This doc is the design-grounding the build arc consumes; the operator ratifies the decisions in §0 before any build.

---

## 0. TL;DR — the seven resolutions (for ratification)

1. **The ordering contract needs no invention — RFC §2 already states it honestly, and the code contains no fabricated cross-shard order.** Guarantees: per-key ✅ (a key maps to exactly one shard for the cluster's lifetime), per-shard ✅ (dense monotonic `S`, contiguous-or-Gap replay), cross-shard/global ❌ (no sequencer, no usable clock). The client tells *ordered* from *concurrent* purely from the `(gid, S)` tag every event already carries: same `gid` ⇒ ordered by `S`; different `gid` ⇒ concurrent, always. One **editorial** RFC addition recommended (W6-2a, the orderedness predicate — drafted in §1.4) plus one honesty clause (W5-4b, §2.5).

2. **Completeness = a vector frontier that stalls rather than lies.** Coverage is driven by `ShardMap.shardIds()` — never inferred from the client's cursor. Every covered shard emits a `WATCH_PROGRESS` watermark component *even when idle*; a lagging/unreachable shard surfaces as a **frozen component while `server_now` advances** — explicitly distinguishable from "quiet", never a silent partial. "Caught up" is the per-shard conjunction (every component at its shard's tip). **No cross-shard "consistent as of T" is possible or offered** — Configd is structurally in the vector-cursor camp (Kafka/DynamoDB Streams), not the timestamp camp (Spanner/CockroachDB), having sharded away the global clock deliberately.

3. **Coordinator: the server-side aggregating endpoint** (RFC W4-5's named v1 topology), one per serving node. The decisive architectural fact: **every node hosts replicas of all N Raft groups and therefore all N per-shard `FanOutBuffer`s, fed by local apply** — scatter-gather is an in-process, zero-network-hop, **leadership-independent** operation (`ConfigdServer.java:499,519-524,618-635`; `FanOutServer` has no leadership check). Client-driver-side merge stays the sanctioned topology for a future disjoint-endpoint deployment (W4-5 "sharded endpoints") with an **identical protocol** — only connection count differs.

4. **Authz: `coversTarget` applies unchanged; one gate; all-or-none.** The predicate is purely logical-path, shard/gid-agnostic, static (`AclService.java:694-736`) — a scattered target is authorized by the same single whole-target check. Enforcement: **one** `authorizeWatch` decision **before any shard leg streams a byte**, then all N legs or none (**INV-MSW-ATOMIC**, §4.3); revocation (W7-7) re-checks once and cuts all N legs. **BUT the investigation found a P0** (§4.5): the `_acl/` policy loader is wired to the **primary Raft group only** while `_acl/` keys hash-scatter across all N shards — at N>1 the policy snapshot is structurally incomplete (missing DENYs ⇒ **under-deny authz bypass**; missing version advances ⇒ **unbounded revocation latency**). Dormant today (prod deploys no `_acl/` keys and N=1); **must be fixed with or before multi-shard watches** (fix shape §4.5; it is node-local, no new consensus).

5. **Track-1 (server-side SUBSCRIBE filtering) is NOT a prerequisite — the planes are orthogonal and compose.** The charter's "full firehose ×N" worry dissolves under the aggregating topology: the gather is in-process reads of local buffers; nothing crosses the network pre-filter, and the watch veneer already filters server-side per watch target. Filter-per-shard-then-gather is what the design does naturally. The only thing that would force a joint fix remains the declared-topic redesign (efficiency doc §5.5), which is not on the table.

6. **Wire-format deltas: ZERO.** The 0x02 wire is already fully vector-native, multi-shard-shaped, and golden-byte-pinned: the cursor vector, per-shard `List<ShardMode>` in `WATCH_CREATED`, `(gid,S)` on every `WATCH_EVENT`, the full vector in `WATCH_PROGRESS`, per-`(watch_id,gid)` snapshot frames, and the per-shard `oldest` vector in `WATCH_CANCELED` all exist on the wire today. **The entire N=1→N>1 gap is server veneer** — three hardcodes (§3.2) plus the boot guard. No wire bump beyond the already-shipped 0x02, no golden re-baseline, no new frame or field.

7. **Build shape: three arcs** (§7) — Arc 0: the `_acl/` shard-complete policy plane (P0, independently valuable, ~1 PR); Arc 1: the multi-shard veneer lift (the core build, 4 gated increments); Arc 2: posture + RFC editorial + the guard flip (small, last). Track 1, resharding, per-watch flow control, and leadership balancing are explicitly **not** prerequisites.

### 0.1 Corrections to the charter's priors

- **The FanOutBuffer conservative-GAP → spurious-demote bug is FIXED at HEAD**, not open: PR #47 (`30644f7`, verified ancestor of HEAD) classifies transient (still-retained boundary race → retry, never demote) vs genuine gaps, with a 128-tick live-lock backstop (`FanOutSessionCore.java:420-435`) and a three-arm regression test (`FanOutSessionCoreGapClassificationTest`). The efficiency doc §6 described the measurement commit `9e1f191`, which predates the fix. **Discharged precondition — build on HEAD; note the N-shard amplifier it would otherwise have been** (N buffers × eviction boundaries).
- **"The coordinator subscribes to all shards' fan-out planes" is the wrong mental model** — there is no network subscribe: all N planes are local memory on every node (§3.1).
- **RFC §2 W9-3 currently scopes the multi-shard server work as "v2"** — the operator's decision supersedes it; the RFC needs a rescope edit (Arc 2), not a redesign: W3-7/W6-3/W6-5 wrote the multi-shard obligations normatively and merely deferred them.

---

## 1. The ordering contract (§1.1 of the charter)

### 1.1 What is guaranteeable — confirmed from code

**Per-key order — YES.** `StaticShardMap.shardFor` is a pure stable function (FNV-1a + SplitMix64, `floorMod N`; `StaticShardMap.java:56-78`), `epoch()==0` for life (`:86-88`), and `enforceFixedShardCount` refuses to boot a node whose configured N differs from the deploy marker ("changing it would mis-route already-committed keys", `ConfigdServer.java:1414-1467`). So **a key maps to exactly one gid for the cluster's lifetime**; within its shard each key gets strictly increasing `S` (the `per_key_order` invariant, `ConfigStateMachine.java:304-306`).

**Per-shard order — YES.** `S` is a per-shard mutation counter: `seq = prevSeq + 1` on every mutating apply, no-ops produce no notification and consume no `S` (`ConfigStateMachine.java:107-108,291-329`) — so production `S` is **dense** per shard, matching RFC W3-2. `FanOutBuffer.readSince(S)` returns a contiguous ascending run (`Ok`) or `Gap(oldestRetainedSeq)` — never a silent skip (`FanOutBuffer.java:162-214`). One Raft entry = one `S` = one `CommitNotification` = one `WATCH_EVENT` (batch atomicity, W5-6).
  *Doc-drift flag (cosmetic, not a bug):* comments at `FanOutBuffer.java:40-41` and `FanOutSessionCore.java:410-411` claim "no-op/RCFG entries skip sequence numbers" — a defensive over-generalization. The contract relies only on **strict monotonicity + `readSince` contiguity**, never on density, so the design must not (and does not) do dense-arithmetic completeness; reconcile the comments in Arc 2.

**Cross-shard / global order — NO, and no mechanism exists to fake one.** Each shard is an independent Raft group with its own log and its own `S`; shard-0 `S=5` and shard-1 `S=5` name unrelated commits. There is no HLC, no TrueTime, no cross-shard sequencer. The two clock-ish fields are both unusable as an order:
- `CommitNotification.commitTimestampMillis` = the **leader's wall clock** at apply, explicitly "NOT a per-entry HLC" (`CommitNotification.java:26-29`); non-deterministic across leaders; used only for the staleness frontier (W3-3/W8-3).
- `VersionedValue.timestamp` is *named* "HLC timestamp" (`VersionedValue.java:11-15`) but is literally `clock.currentTimeMillis()` at apply (`VersionedConfigStore.java:96,116,139`) — a per-shard leader wall clock with no cross-shard message exchange. **The name is a trap; recommend a doc note** (Arc 2).
Code affirms the discipline: "Per-shard sequences + cursor vector; NO fabricated cross-shard global order" (`ConfigdServer.java:623,661,1667`); cross-shard multi-key writes are rejected (`CrossShardWriteGuard`), so there is no cross-shard write atomicity to project onto the stream.

### 1.2 How the client consumes the merged stream honestly

RFC §2 **already specifies** the consumption model — option (a) of the charter: the aggregating endpoint interleaves by arrival, **tags every event `(gid, S)`** (`EdgeFrame.java:491`), and the driver merges as a **UNION, not a sorted merge** ("there is no global key to sort on", W4-2), presenting per-key order only and never cross-shard order (W4-3, W6-2 — including the explicit example that a PUT to shard 0 followed by a PUT to shard 1 MAY deliver in the reverse order). Per-shard sub-streams (option b) are compatible but not required; W4-3 SHOULD-exposes the `(gid,S)` stamp so an application *can* separate them.

### 1.3 Ordered vs concurrent — the API surface

A client distinguishes them from the tags alone:
- **Same `gid`** ⇒ ordered by ascending `S` (and same key ⇒ always same `gid`, hence per-key order).
- **Different `gid`** ⇒ **concurrent** — no order in either direction, ever. Not from arrival sequence, not from `S` magnitude (incomparable across gids), not from `commit_ts` (per-leader wall clock).

### 1.4 The one normative addition — W6-2a (drafted; editorial, no new guarantee)

> **W6-2a (the orderedness predicate — how a client reads the tags).** Two delivered events e1, e2 are **ORDERED** iff they carry the same `gid`, and then their order is ascending `S` (e1 before e2 iff e1.S < e2.S); this relation is transitive and is the **only** order the stream asserts. Two events for the same key are always same-`gid` (a key maps to one shard for the cluster's lifetime, W2-3/INV-PATH) and hence always ordered — this **is** per-key order. Two events with different `gid` are **CONCURRENT**: no order in either direction, ever. A driver MUST NOT infer order from arrival sequence, from `S` magnitude across gids, or from `commit_ts` (a per-leader wall clock, W3-3). A driver MUST surface exactly this to the application: per-gid/per-key order only; the cross-gid interleaving it presents is an arbitrary, non-normative merge (W4-2).

### 1.5 At-least-once × the merge — safe per component

Dedup is per-component: drop a `WATCH_EVENT` iff `S ≤ cursor[gid]` (W4-2/W6-1). A redelivery on shard g is deduped by g's component independently and cannot be confused with a shard-h event (different gid); component-wise max-merge never regresses (W3-6). Exactly Kafka's per-partition offset dedup — no cross-contamination.

---

## 2. Completeness under a dynamic shard set (§1.2 of the charter)

### 2.1 The shard set — static, enumerable, authoritative

N is deploy-time (`configd.raft.shardCount`, validated [1, ~16], **fixed at deploy** via the `raft-shard-count.meta` marker; `ConfigdServer.java:118-125,247,1414-1467`) and **immutable for the cluster's lifetime** — online resharding is explicitly out (`ShardMap.java:30-44`; `StaticShardMap.java:86-93`, `epoch()==0` forever). No resharding path exists anywhere in code or docs; **fixed-N is the honest v1 scope** and a strength: the shard set is identical on every node with zero discovery. The enumeration API exists: `shardIds()` = `IntStream.range(0,N)` (`StaticShardMap.java:80-83`) — exactly how the bring-up loop enumerates groups (`ConfigdServer.java:499`).

**Hard rule: coverage MUST be driven by `shardIds()` — never inferred from the client's cursor vector.** A missing component (per W3-4: absent = 0 = "from now for that shard") still gets a materialized substream; a component naming a gid ∉ [0,N) is unroutable and MUST be rejected fail-closed (`BAD_SUBSCRIBE`) — under static-N it can only mean a cursor from a different deployment, and a silent drop would let the client believe it is covered. A component within range but outside the target's shard set (e.g. extra gids on a KEY watch) is ignored — the **target**, never the cursor, determines the materialized shard set (see §4.6 B4).

### 2.2 Shard unavailable / leadership move mid-watch

Serving is from the **local replica's buffer**, so a group-leader move does **not** interrupt that shard's substream — the buffer keeps filling from replicated applies, and `S` is deterministic and leader-independent (W3-2), so resume across an election is contiguous. What actually stalls one substream is the **serving node falling behind that group's replication** (partition from that group's leader, or a catching-up replica): that one buffer freezes while the other N−1 flow.

Of the three candidate behaviors, the resolution is **deliver-other-shards-with-an-explicit-lagging-signal**:
- **Never stall the whole merged stream** — a silent stall is indistinguishable from "no changes" (the false-completeness class, W7-2/W8-4).
- **Never fail the whole watch** for one shard's blip — that destroys N−1 healthy substreams (etcd's whole-watch-cancel mistake, which W6-3 was specifically written to avoid).
- **The lagging signal already exists and needs no new frame:** the per-shard `WATCH_PROGRESS` watermark (§2.3). A frozen `gid=g` component while `server_now` advances ⇒ g is lagging, visibly.

### 2.3 The watermark — per-shard WATCH_PROGRESS, the vector frontier

`WATCH_PROGRESS` already carries the **full cursor vector** on the wire (`EdgeFrame.java:520`; codec `EdgeFrameCodec.java:397-417,794-814`) — it *is* the per-shard watermark: each component asserts "shard g has delivered everything matching ≤ S_g". The multi-shard obligations (server-side only):

1. **Emit a watermark component for every covered gid, even when idle** — an idle-but-healthy shard's component keeps advancing over non-matching commits (W4-4); a lagging shard's freezes. This is precisely what makes **quiet distinguishable from lagging** (the Kafka KIP-353 lesson, §6.3). Prefer one coalesced N-component `WATCH_PROGRESS` over N single-component frames (matches W5-7's plural).
2. **Each component clamped to that shard's drained+verified+filtered frontier** — the W5-7 upper-bound (no-silent-gap) clamp per component. The built clamp already uses the drained cursor, not raw `HEARTBEAT.latestSeq` (`WatchMultiplexSink.java:248-260`); generalize the single `drainedCursor` supplier to one per shard.
3. **"Caught up" is the conjunction**: the client is caught up iff every component has reached its shard's tip. The frontier is **irreducibly a vector** — min-over-shards is not a scalar because per-shard `S` values are incomparable (§6.2). Under a lagging shard the honest posture is "caught up on {others}, stalled on g since S_g" — **the frontier stalls; the stream never lies** (the universal prior-art pattern, §6.4).

### 2.4 Resume with a partial/stale vector — composes per shard

Each component resumes independently via that shard's `readSince(S_i)` → `Ok` (TAIL) or `Gap` → **that shard alone** goes `SNAPSHOT_FIRST` with an inline per-`(watch_id,gid)` catch-up (`WATCH_SNAPSHOT_*`, W6-3/W5-10) while the other shards keep streaming — the cursor-vector payoff vs etcd's whole-watch cancel. There is **no cross-component invariant**: `WATCH_CREATED` already carries a per-shard `List<ShardMode>` so shards can mix TAIL and SNAPSHOT_FIRST (`EdgeFrame.java:464-472`, W5-5). The genuinely-unrecoverable case terminates the watch with the per-shard `oldest` **vector** (`WATCH_CANCELED(GAP_UNRECOVERABLE)`, W6-4) → re-list + re-create.

### 2.5 `with_initial_snapshot` at N>1 — per-shard, NOT a consistent cut (new honesty clause)

Each covered shard snapshots at its **own** `snapshot_seq`, captured independently at different instants, then tails from there. That is the only honest option — there is no cross-shard consistent snapshot to take (no global clock, cross-shard atomicity DISCLAIMed). W5-4a does not currently warn about this. Recommended addition:

> **W5-4b (multi-shard initial snapshot is per-shard, not a consistent cut).** With `with_initial_snapshot` at N>1, each covered shard delivers its state as of its **own** `snapshot_seq`, captured independently at different instants. The union is NOT a global point-in-time cut. A driver MUST NOT treat the assembled initial state as a cross-shard consistent snapshot; it is per-shard-current, then per-shard-tailed from each `snapshot_seq`.

### 2.6 The completeness argument (proven-or-signaled)

Every committed change to the target on shard g enters g's buffer on apply (`registerShardedFanOut`, `ConfigdServer.java:1676-1722`); the coordinator materializes a drain for **every** g ∈ `shardIds()` (§2.1); each drain delivers contiguously or signals (`Ok`/`Gap`→snapshot/`GAP_UNRECOVERABLE`) — never silently skips (§1.1); a stalled drain surfaces as a frozen watermark component (§2.3). Therefore every change is delivered, or the lag is explicitly visible per shard. **No silent partial exists in the design.** The one residual liveness (not completeness) caveat: v1 backpressure is per-connection (§3.4).

---

## 3. The coordinator architecture (§1.3 of the charter)

### 3.1 The decisive fact: all N planes are local to every node

Every node builds **every** Raft group (`int[] gids = shardMap.shardIds().toArray()`, `ConfigdServer.java:499,519-524`) and one `FanOutBuffer` + `Compactor` per shard, each fed by that group's state machine on its owner thread — **followers apply too**, so all N buffers fill on every node regardless of leadership (`:618-635,1676-1722`). Each shard's delta chain carries its own signature/epoch/nonce (`:1700-1708`). `FanOutServer` serves from a local `CommitNotificationSource` with **no leadership check** (`FanOutServer.java:93,275-294`) — confirming RFC W8-1 (any replica serves). The `ShardedFanOut` record (`Map<Integer,FanOutBuffer>`, `ConfigdServer.java:626-629,1645`) is the one place all N buffers are already visible to one component — the natural home for the coordinator. `FanOutBuffer.readSince` is lock-free multi-reader (`FanOutBuffer.java:23-27`), so a coordinator can read all N buffers from any thread with no locking; `FanOutSessionCore` is single-threaded-per-instance (`:25-30`), currently driven by one virtual session thread per connection (`NettyFanOutServer.java:388-389`).

### 3.2 What is single-shard today (the three hardcodes + the guard)

The 0x02 wire is N-native; the server is not, in exactly these places:
1. `FanOutSessionCore.cursor` is a scalar `long` — the core is single-source/single-cursor (`FanOutSessionCore.java:89`); one core cannot serve a multi-shard watch. ⇒ N cores (one per shard substream) behind one connection.
2. `WatchMultiplexSink` stamps constant `GID_0=0` on every frame and reads one `drainedCursor` supplier (`WatchMultiplexSink.java:76,88,201-253`).
3. `FanOutConnectionDriver.startCursorS` honors only the `gid==0` component and silently drops all others (`FanOutConnectionDriver.java:590-597`); `handleWatchCreate` drives one shared drain and emits `ShardMode(0, …)` (`:453-481`).
Plus: exactly one `FanOutEndpoint` bound to the **primary** buffer + primary-only `SnapshotReplaySource` (`ConfigdServer.java:170,634,941,965`), and the **N>1 boot guard** (`:247-259`) which aborts the **entire server boot** (not just the endpoint) when N>1 ∧ `--edge-port` ∧ not `allowPartialShardView` — watches and legacy SUBSCRIBE share the edge port, so both are refused together. Also avoid reusing the dormant `WatchService` (`:657-663`) — bound to primary with a single version cursor that "collides across shards" (a latent global-version footgun, flagged in its own comment).

### 3.3 The three options, assessed

- **(a) Edge-side merge** — warranted only if edges served disjoint shard subsets; they don't (all N buffers co-located per node). Adds N connections and moves dedup state outward for zero locality gain. **Rejected for the built topology.**
- **(b) Server-side aggregating coordinator — RECOMMENDED.** The serving node scatter-gathers across its N **local** buffers (local `readSince`, no network), merges in-process, tags `(gid,S)`, emits one multiplexed 0x02 stream. This is RFC W4-5's "aggregating endpoint" — the named v1 topology — generalized from 1 to N local drains. Trade-offs: local reads only; merge state in the existing veneer (`WatchRegistry`/`WatchMultiplexSink`); per-shard failure isolation structurally free once the veneer runs one drain per shard (independent buffers/owner threads); authz at the server, once, whole-target (§4); blast radius = the serving node itself, and the client reconnects to **any other node** (all have all shards); **leadership-not-auto-balanced op gap: NEUTRAL** (serving is leadership-independent — a clean win vs any leader-pinned design).
- **(c) Client-driver-side merge** — RFC-native (W4-2 UNION is specified for the driver anyway), but N× connections per client, merge state in every client, and the dangerous authz topology (per-leg decisions on different nodes can diverge, §4.2c). Its only advantage (survive node loss via per-shard reconnect) is already covered by (b) since every node has every shard. **Stays the sanctioned model for a future disjoint-endpoint topology (W4-5 v2) with no protocol change** — the wire is identical; only connection count differs.

### 3.4 Known shared-fate caveat (v1-acceptable, documented)

The v1 aggregating endpoint's N drains share **one** connection-level `CURSOR_ACK` scalar and one `SlowConsumerGovernor` fate (W8-6; `WatchMultiplexSink.java:58-64`): a single slow shard substream can demote every sibling substream and watch on that connection (head-of-line blocking across shards and watches). Per-watch/per-shard flow control is the named W10-8 extension. **State this in the multi-shard posture doc**; the min-frontier partly mitigates (the demotion is visible), and per-shard drains make later isolation natural.

### 3.5 Failure-semantics table (design (b))

| Failure | Client-visible behavior | Mechanism |
|---|---|---|
| Shard-g leader election | Nothing, while the serving node stays a replica; contiguous resume (deterministic leader-independent `S`) | Local-replica serving; W3-2; W6-5 |
| Eviction past a slow watch's cursor on g | Only g → `SNAPSHOT_FIRST` → inline `WATCH_SNAPSHOT_*(watch_id, g)`, then tail; others uninterrupted; client sets `cursor[g]=snapshot_seq` on END | `readSince→Gap` + snapshot flow (`FanOutSessionCore.java:460-516`), `FilteringReplaySource`, W6-3 |
| Serving-node (coordinator) crash | Connection drops; client reconnects to **any** node, re-sends the full vector; per-component TAIL or snapshot; max-merge prevents regress | W6-5; every-node-has-every-shard |
| Reconnect with stale vector | Per-gid: in-window→TAIL; behind→inline snapshot; unrecoverable→`WATCH_CANCELED(GAP_UNRECOVERABLE, oldest-vector)`→re-list+re-create; coverage `shardIds()`-driven | W6-3/W6-4; §2.1 |
| One-shard partition (serving node cut off from g's leader) | g's watermark component freezes while others + `server_now` advance ⇒ "g lagging" explicit; others deliver; **never a silent partial**; contiguous resume on rejoin | Per-shard buffers + per-shard `WATCH_PROGRESS` (W5-7) + frontier (W8-3) |
| Revocation mid-stream | All N legs cut with `WATCH_CANCELED(NOT_AUTHORIZED)` within the poll-cadence bound | W7-7 generalized (§4.4) |

---

## 4. The authz extension (§1.4 of the charter)

### 4.1 The predicate is shard-independent — confirmed, zero change

`AclService.coversTarget(rules, target, cap)` is **static, reads no instance state**, and decides from the literal-prefix rule set alone: ancestor-or-equal ALLOW carrying the capability AND no intersecting DENY (ancestor or interior) (`AclService.java:694-712`); `authorizesWatch` = `coversTarget(READ) ∧ coversTarget(WATCH)` (`:733-736`). The adapter (`AclServiceWatchAuthorizer.java:73-86`) consumes only target kind/path/flags. **No gid, no shard count anywhere.** A PREFIX/FULL target scatters across all N shards, but the check is a single whole-subtree cover over the *logical* prefix — one call authorizes the scattered target unchanged. A per-shard authz loop is neither needed nor wanted (it would reintroduce divergence, below).

### 4.2 The enforcement point — one gate, before any leg streams a byte

The single-shard establish order is already correct and generalizes: validate → snapshot policy version → **authorize whole target** → register → only then drive the drain(s); zero data frames precede a reject (`FanOutConnectionDriver.java:387-482`, W7-1/W7-5).
- **(a/b) Aggregating coordinator:** ONE `authorizeWatch(principal, wholeTarget)` against the serving node's single `AclService` **before opening, seeding, or tailing any per-shard leg**. One decision, one snapshot, all-or-nothing; per-leg divergence is impossible (same node, same snapshot).
- **(c) Sharded endpoints (future):** each leg terminates on a different node with its own policy snapshot — per-leg decisions **can** diverge. Never enforce client-side (the client is the attacker); gate server-side on each terminating node, and any divergence fails the **whole** watch (§4.3), never a subset.

**The F1 generalization (N seeds, N tails):** the single-shard whole-store-snapshot leak (fixed by `FilteringReplaySource` + TAIL) becomes N catch-up seeds + N live tails. Obligations: (1) **every** shard's seed strictly after the one authz success (eagerly warming legs pre-authz = F1×N); (2) each leg gets its own `FilteringReplaySource` set to the **same logical target** — per-leg filtering is pure **routing, not authz** (the whole-subtree cover already proved every matching key on every shard authorized), preserving the single-decision property; (3) there is no "authorize k, seed k, authorize j" — one decision, then fan out N seeds; per-shard Gap/resume floors never perturb the gate.

### 4.3 INV-MSW-ATOMIC (the hard rule)

> A multi-shard watch is authorized as a **single indivisible whole-target decision** and served as **all N legs or none**. If the authorizer is unavailable, the policy snapshot is unloadable **or incomplete**, or any required shard leg cannot be established, the server MUST reject/close the WHOLE watch (`WATCH_CANCELED(NOT_AUTHORIZED)` at create; whole-watch cancel mid-stream). It MUST NEVER silently degrade to the subset of shards that happen to be reachable/authorized.

A served subset is **two violations at once**: silent-partial/false-completeness (indistinguishable from "no changes" for the missing shards — what W7-2 forbids), and an authz hole (the W7-2a universal quantifier over every k ∈ T was not actually discharged). Degrade-to-fewer-shards is a security downgrade, not graceful degradation.

### 4.4 Revocation across shards (W7-7 generalized)

Re-check **once at the coordinator** — logical whole-target re-authorization on policy-version advance — then cut **all N legs** atomically from the client's view (`WATCH_CANCELED(NOT_AUTHORIZED)`). Never per-leg re-checks (reintroduces the divergent-subset hazard). The built poll (`maybeReauthorizeWatches`, `FanOutConnectionDriver.java:510-540`, every session-loop tick) and the seed-TOCTOU fix (version read **before** the gate, `:402-449`) generalize unchanged — authorization is over the logical target against one node-level snapshot, evaluated once above the per-shard fan-out; it does not multiply per shard. Bound (with the §4.5 fix): max over shards of (commit+apply of the revoking write on the serving node's replica) + loader rebuild + one poll tick; a multi-key `_acl/` revocation is fully effective only when its **last** shard's write applies (cross-shard `_acl/` edits are non-atomic — `CrossShardWriteGuard.java:42-58`). **Without the fix: unbounded** for any non-primary-shard revocation — the gating defect.

### 4.5 THE P0 — the `_acl/` policy plane is primary-shard-only at N>1

**Finding (coordinator-verified):** `AclConfigPolicyLoader` is constructed against the **primary group's** store and registers `onConfigChange`/`onSnapshotInstalled` on the **primary group's** state machine only (`ConfigdServer.java:714-719`; `stateMachine` = the primary group, `:576`); `rebuild()` reads `getPrefixVersioned(ACL_PREFIX)` from that store alone (`AclConfigPolicyLoader.java:119`). But `_acl/roles/X`, `_acl/bindings/Y` are ordinary keys routed by `shardFor(scope, fullKey)` — **they scatter across all N groups** (each node holds a separate per-group store: `runtimesByGid`, `:582-585`; the read path already scatter-gathers `getPrefix` across all runtimes, `:1615-1625`). The code comment at `:710-711` ("observes every `_acl/`-touching apply") is true only at N=1. At N>1:
- The loader sees only ~1/N of `_acl/` keys; a role/binding/**DENY** on a non-primary shard is silently absent from the snapshot. Missing ALLOW ⇒ under-grant (fail-closed availability bug). **Missing DENY ⇒ under-deny — a genuine authz bypass** defeating W7-2a: a watch that an interior DENY should reject is authorized.
- `configPolicyVersion()` never advances on non-primary `_acl/` applies ⇒ the W7-7 revocation trigger **never fires** — a double miss.

**Dormant today** (prod deploys no `_acl/` keys → `ConfigPolicy.EMPTY` → byte-identical; and N=1), but multi-shard watches are an N>1 feature and the roles/policy arc exists precisely to express deny carve-outs. **Fix must land with or before multi-shard watches.**

**Fix shape (node-local, no new consensus):** point the loader's rebuild at a scatter-gather over `runtimesByGid` (the `:1594-1633` pattern), register `onConfigChange`/`onSnapshotInstalled` on **every** group's state machine, publish the version as a monotonic function of all groups' `_acl/` applies (so `configPolicyVersion()` stays one monotonic scalar). Every node already holds every group's store. The idempotent whole-subtree rebuild already tolerates non-atomic cross-shard `_acl/` edits (a binding to a not-yet-loaded role is inert). **Until fixed, the honest posture: N>1 + any `_acl/` policy ⇒ refuse** (mirror the existing boot-refuse discipline). Regression test: write a DENY to a key chosen to hash to shard≠0 → the covered watch is rejected/revoked (**fails today** — the test that proves the fix).

### 4.6 Bypass classes → killing property → test

| # | Attack | Killed by | Test |
|---|---|---|---|
| B1 | Coordinator authorizes/serves only reachable shards → partial view / false-empty | INV-MSW-ATOMIC (§4.3) | Force one leg unavailable → whole watch `NOT_AUTHORIZED`, zero data frames |
| B2 | Leg opened after the re-auth sweep rides stale authorization (failover / lazy open) | Seed-before-authorize lifted (Gate-2, `:400-449`); every late leg goes through the same create gate | Advance version between gate and leg-open → late leg re-authorized |
| B3 | Resume-with-vector hoping to skip the gate | Resume = create for authz (W5-4); the cursor is data, never an authz token | Revoke, resume with valid old cursor → `NOT_AUTHORIZED`, zero frames |
| B4 | gid-spoofed cursor components to widen scope / touch foreign shards | The authorized **target** determines the materialized shard set, never the cursor; gid ∉ [0,N) ⇒ `BAD_SUBSCRIBE`; in-range-but-irrelevant components ignored; spoofed S only self-harms (skips own events) | Foreign/out-of-range gid → rejected / ignored, no extra shard materialized |
| B5 | Cross-scope confusion: `shardFor` folds scope but the authz path is scope-blind (DL-O6-02) — a grant "under GLOBAL" would authorize the same path under REGIONAL/LOCAL | v1 is single-scope (GLOBAL-only) ⇒ inert; **pin as an explicit known limitation**; if multi-scope ever ships, scope-aware ACLs are a prerequisite | Guard: multi-scope watch refused (or scope-aware authz enforced) at N>1 |
| B6 | The §4.5 P0 as an attack: write a tightening DENY to a non-primary-shard `_acl/` key | Shard-complete loader + all-shards monotonic version | DENY on shard≠0 → watch rejected/revoked (fails today) |
| B7 | Revocation race: a leg keeps delivering after the logical decision flipped | One logical re-check → all N legs cut atomically; bound off the slowest shard | Revoke mid-stream → all legs cut within the poll-cadence bound |

Fail-closed posture is already absolute at the seam (null authorizer / "plaintext" / any throwable ⇒ deny, `FanOutConnectionDriver.java:554-563`) and carries over.

---

## 5. The Track-1 (server-side filtering) interaction (§1.5 of the charter)

**They compose; Track 1 is NOT a prerequisite; neither changes the other.**

- The charter's efficiency worry — "each shard sends the full firehose ×N to the coordinator" — **dissolves under the aggregating topology**: the gather is in-process reads of N local buffers; nothing crosses the network before the watch veneer's per-target filter (`WatchMultiplexSink.translateNotify` per-NOTIFY + `FilteringReplaySource` for catch-up). Filter-per-shard-then-gather is what the design does naturally — the filter just runs at the veneer over local data rather than on a remote shard.
- **Cost honesty:** the coordinator examines each cluster commit exactly once per connection (the union of N buffers = total cluster writes). Per-connection filter CPU therefore scales with aggregate write rate — the same shape as today's N=1 full-store drain, just at whatever higher write rate N shards sustain. The filter is a literal prefix match; this is a capacity note, not a blocker.
- **Track 1** (posture-flagged prefix filtering on the legacy SUBSCRIBE drain, efficiency doc §5.1) addresses a different plane: network egress of the full signed chain to *edge hydration* subscribers. Building multi-shard watches changes nothing about how Track 1 should work, and vice versa — the watch plane already ships the filter-server-side trust model Track 1 would adopt. They share `FanOutSessionCore`, so **sequencing** them avoids merge friction, but neither depends on the other.
- The only thing that would force a joint redesign remains the **declared-topic** model (efficiency doc §3.2c/§5.5) — a product-model change reshaping the partition axis for both planes. Not on the table for v1.

---

## 6. Prior art — cross-partition change feeds done right (§2 of the charter)

### 6.1 Two regimes

- **Timestamp camp** (global commit clock ⇒ scalar frontier): **CockroachDB CHANGEFEED** — per-key ordering only ("no total message ordering"); the **resolved timestamp** = min closed timestamp over all watched ranges, "no previously unseen rows with an earlier update timestamp will be emitted"; a slow range **holds the frontier back** (staleness grows, `max_behind_nanos`/`lagging_ranges`), never a gap. **Spanner change streams** — per-partition commit-ts order, exactly-once per partition, no cross-partition order; **heartbeat records** assert per-partition progress when idle; the documented caught-up rule is min-over-partitions of heartbeats/records ≥ T ⇒ "all records committed at or before T received". Their single-number frontier and "consistent as of T" exist **because** T is a globally comparable commit timestamp.
- **Vector-cursor camp** (no global clock — Configd's camp): **Kafka** — per-partition order only, offset arithmetic, no watermark primitive; **DynamoDB Streams/Kinesis** — per-shard sequence + independent shard iterators, per-shard caught-up signal (`MillisBehindLatest → 0`), no cross-shard "as of T". **etcd** — the single-revision simplicity Configd cannot copy (bought by not sharding); its `progress_notify`/compaction-resync is what Configd already mirrors per shard. **FoundationDB** — global versionstamps via a single sequencer (the road not taken), and even then its watches are deliberately weaker than a change feed.

### 6.2 What Configd can and cannot promise

The caught-up signal in the vector camp is per-partition and stays per-partition; "caught up overall" = the **conjunction** of per-shard caught-up statements. Configd's honest equivalent: per-shard `WATCH_PROGRESS` heartbeat + client-side vector frontier — each component means "shard g: everything ≤ S_g delivered"; nothing more. **Configd CANNOT promise** (state so the design never accidentally claims it): (a) a single "as of T" caught-up point; (b) any cross-shard consistent snapshot/cut; (c) cross-shard transactional or causal ordering. Those require the global comparable clock or single sequencer that sharding traded away for throughput.

### 6.3 The cautionary tale — why the idle heartbeat is load-bearing

Kafka Streams' stream-time (min record timestamp across input partitions) is the one attempt in the vector camp to synthesize a cross-partition order — and it breaks exactly as KIP-353 documents: an **empty partition stalls stream-time** (quiet indistinguishable from lagging), and advancing anyway causes non-deterministic reprocessing. The fix (`max.task.idle.ms`) just trades latency for blind waiting. **Configd avoids the ambiguity only because each shard emits progress even when idle** — dropping the idle watermark reintroduces it. No counterexample was found of a vector-camp system successfully faking a global order at its public API.

### 6.4 The universal pattern — frontier stalls, stream never lies

All systems converge: a lagging/unreachable partition yields **increasing staleness, never a gap and never a false caught-up** (Cockroach's resolved ts stops; Spanner's min-of-heartbeats stalls; Kinesis' per-shard `MillisBehindLatest` grows while others progress). §2.3 adopts exactly this.

### 6.5 Authorization of cross-partition feeds — thin prior art (said plainly)

Spanner/Cockroach/DynamoDB authorize at the stream/table level (whole feed or none); none documents per-key/per-subtree change-stream authz. Configd's whole-target watch-authz is **more** granular than any precedent; the cross-shard contract (§4) is Configd's own invariant, not borrowable.

---

## 7. The build-arc shape (§3 of the charter)

Three arcs, ordered. All build on HEAD (post-`30644f7`); zero wire changes anywhere.

**Arc 0 — the shard-complete ACL policy plane (the §4.5 P0). Do first or in parallel; independently valuable.**
Scatter-gather loader over `runtimesByGid` + listeners on every group's state machine + all-shards monotonic `configPolicyVersion` + the B6 regression test (DENY on shard≠0). Node-local, no consensus change, no wire change; fixes a latent N>1 authz bug that exists **regardless** of watches. Size: ~1 PR / 1–2 gated increments.

**Arc 1 — the multi-shard veneer lift (the core build).** One coordinator per serving node over the N local buffers. Four gated increments, each with the four-way review discipline:
- **1a — N drains, one connection:** one `FanOutSessionCore` per shard substream behind `WatchMultiplexSink`; cursor demux (`startCursorS` → every component, `shardIds()`-driven coverage, gid ∉ [0,N) ⇒ `BAD_SUBSCRIBE`); real gids in every frame; `WATCH_CREATED` with N `ShardMode`s; TAIL-only first. (The threading decision — N cores on one virtual session thread vs per-shard threads — is 1a's design gate; `readSince` is lock-free so either works.)
- **1b — per-shard catch-up:** per-leg `FilteringReplaySource` + per-shard `SnapshotReplaySource`s (today primary-only, `ConfigdServer.java:941`); mixed TAIL/SNAPSHOT_FIRST; inline per-`(watch_id,gid)` resync; `GAP_UNRECOVERABLE` with the oldest-vector; `with_initial_snapshot` per-shard (W5-4b semantics).
- **1c — the completeness surface:** per-shard drained-cursor suppliers + the coalesced N-component `WATCH_PROGRESS` watermark (per-component W5-7 clamp); frozen-component lagging visibility; idle-emission guarantees.
- **1d — authz + revocation at N:** the single front gate (one decision → all-N legs), INV-MSW-ATOMIC establishment, whole-watch revocation cutting all legs, and the B1–B7 test suite (§4.6).
Size: the dominant arc — comparable to or slightly larger than the N=1 watch arc (its 3 gates), since the wire and authz predicate are done but the session layer is restructured. Est. 4 gated increments / PRs.

**Arc 2 — posture, RFC editorial, and the switch-flip (small, last).**
RFC §2 edits: add W6-2a (§1.4) + W5-4b (§2.5); rescope W9-3/W3-7/W6-3/W6-5's "v2 server obligation" language to v1 (the operator decision); the `VersionedValue` "HLC" doc note; reconcile the sparse-seq comments. Known-limitations: per-connection shared-fate backpressure across shards (W8-6/W10-8), B5 scope-blindness, no-consistent-cut. Lift the N>1 boot guard (`ConfigdServer.java:247-259`) **only after Arc 0 + Arc 1 gates are green** (keep `allowPartialShardView` as the explicit escape hatch); gate-style CI for the multi-shard watch plane. Size: ~1 PR.

**Explicitly not prerequisites:** Track-1 SUBSCRIBE filtering (orthogonal, §5); dynamic resharding (out of scope by construction); per-watch flow control (W10-8, v2); leadership auto-balancing (neutral — the plane is leadership-independent); drivers (the server plane is client-facing-complete without them; the RFC is the contract).

---

## 8. Definition-of-done checklist (charter §5)

- [x] **Ordering contract resolved** — per-key/per-shard yes (dense monotonic S, key→one-gid-for-life), cross-shard-global no (no mechanism; commit_ts unusable); client consumes a UNION with `(gid,S)` tags; ordered-vs-concurrent decidable from tags alone; RFC needs only editorial W6-2a + W5-4b + the W9-3 rescope (§1, §2.5).
- [x] **Completeness model resolved** — `shardIds()`-driven subscribe-all; lagging shard = frozen watermark component (deliver-others + explicit signal; never stall-all, never fail-all, never silent partial); per-shard resume/compaction composes with no cross-component invariant; the watermark = the existing `WATCH_PROGRESS` vector, emitted per covered shard even when idle, clamped per component (§2).
- [x] **Coordinator recommended** — server-side aggregating endpoint, one per node, leadership-independent, over N local buffers (the decisive co-location fact); client-side merge remains the protocol-identical future topology; shared-fate backpressure documented (§3).
- [x] **Authz extension resolved** — `coversTarget` unchanged (purely logical); ONE whole-target gate before any leg streams a byte; INV-MSW-ATOMIC all-or-none; whole-watch revocation; **P0 found and scoped** (`_acl/` primary-only loader) with fix shape + regression test; bypass classes B1–B7 enumerated with killing properties (§4).
- [x] **Track-1 interaction resolved** — compose cleanly; filter-at-veneer-then-gather is inherent to the aggregating topology; Track 1 NOT a prerequisite; joint redesign only under declared-topics, not on the table (§5).
- [x] **Prior art** — resolved-timestamp/watermark model extracted (Cockroach/Spanner) and honestly adapted: the frontier is a vector, caught-up is a conjunction, the frontier stalls; Kafka KIP-353 = why idle heartbeats are load-bearing; no "as of T" claim possible (§6).
- [x] **Build-arc shape** — 3 arcs (ACL-plane P0 → veneer lift ×4 increments → posture/RFC/guard-flip), sized and ordered; non-prerequisites named (§7).
- [x] **Read-only** — findings in this file; nothing else changed.
- [ ] **Operator ratification** — §0 decisions surfaced; build arc proceeds only after ratification.
