# Seam G1 — N-way fan-out merge/sequencer (design)

> Charter Step 8 / Seam G part 1. At N>1 the distribution node must ingest the committed-mutation
> streams of ALL N groups, not just the primary. This note pins the merge/sequencing **semantics**, the
> **thread-safety** argument, and the **N=1 byte-identity** argument, and records the explicit
> **v2 edge-client boundary**. Consensus-adjacent → FOUR-WAY. The N>1 boot guard stays until G3 green.

## The problem (what is wired today, and why it is wrong at N>1)

The Seam C bring-up builds one `RaftGroupRuntime` per shard, each with its own `ConfigStateMachine` +
`VersionedConfigStore`. But the distribution wiring binds the fan-out to the **PRIMARY group only**:

- `ConfigdServer.start()` created ONE `FanOutBuffer` + ONE `Compactor` and registered the commit
  listener (`fanOutBuffer.publish(...)` + `compactor.addSnapshot(...)`) on the **primary** state machine
  (`stateMachine == primaryGroup.stateMachine()`), plus `watchService::onConfigChange` on the primary.

At N>1 every non-primary group commits to its own state machine, and those commits **never reach the
fan-out view** — an edge would silently miss every write to a non-primary shard. This is the ADR-D-A
"surviving (verified)" consequence: *"Fan-out is single-source today … N shards = N owner threads
writing one bounded, drop-oldest buffer → an N-way merge/sequencer in front of fan-out is required."*

Two structural hazards block the naive fix (register the SAME listener on every group's state machine):

1. **Thread-safety.** `FanOutBuffer` is documented **single-writer** (`head`/`tail` are plain `volatile
   long` with read-modify-write); `WatchService` is documented **single-threaded, no synchronization**.
   At N>1 the per-group listeners fire on the groups' **owner threads concurrently** (`ownerExecutor(gid)
   = pool[floorMod(gid, P)]`), so a single shared buffer/coalescer would be raced.
2. **Per-shard sequence collision.** Each `VersionedConfigStore` counts its own version 1,2,3,…, so two
   shards both emit "seq 5". A single buffer keyed by `seq` (gap detection, the `readSince(cursor)`
   contract, the `lastEvictedSeq` watermark) cannot disambiguate them.

## Decision — one FanOutBuffer + one Compactor + one ReplaySource PER SHARD

Faithful to **ADR-D-A** (hash partitioning), **ADR-D-C** (DISCLAIM cross-shard atomicity/ordering),
**ADR-0004** (per-group sequence) and **ADR-0035** (per-group edge cursor): the committed model is
**per-shard sequences consumed via a per-shard cursor (a cursor *vector*)**, NOT a fabricated global
order. ADR-D-C §4: *"the monotonic sequence (ADR-0004) and the edge cursor (ADR-0035) are already
per-group; sharding instantiates N independent counters … A client reading across shards composes
per-shard cursors."* ADR-D-A red-team (surviving): *"a prefix subscription under hash needs O(N)
per-shard cursors per subscriber (each shard's slice gap-tracked independently; one shard's gap stalls
its slice)."*

So G1 gives **each shard its own** `FanOutBuffer`, `Compactor`, and `SnapshotReplaySource`:

- **Each group's commit listener publishes to ITS OWN buffer + compactor, on ITS OWN owner thread.**
  A group's apply (and therefore its `notifyListeners`) runs only on that group's owner thread (R-01′,
  the `assertOwnerThread` net). A group is owned by exactly one owner thread at a time, so **each
  per-shard buffer has exactly one writer** — the `FanOutBuffer` single-writer invariant is preserved
  *per shard, with no lock*. Two groups sharing an owner thread (when `P < N`) write their two buffers
  serially on that one thread; no buffer is ever touched by two threads. This is the key insight that
  makes the merge thread-safe **by construction** rather than by adding a lock to a hot, lock-free path.
- **Per-shard sequence stays per-shard.** `CommitNotification.seq` is that shard's own version; each
  buffer's `readSince` / gap detection / `lastEvictedSeq` operate within one shard's monotone sequence —
  no collision. Per-shard monotonicity, no lost/dup/reorder-within-shard, is exactly the existing
  single-buffer guarantee applied per shard.
- **Per-shard replay (derived on demand).** Each shard's replay is `new
  SnapshotReplaySource(group.configStore()::snapshot)` — a stateless wrapper built on demand from the
  shard's own store (the `registerShardedFanOut` helper returns only the buffers + compactors; it does
  not store replay sources). Each shard replays its own cumulative snapshot at its own per-shard version.
  No merged snapshot, no global floor. The instance-level `replaySource()` accessor stays primary-scoped
  at N>1 (the per-shard sources are the v2 sharded-edge-client cursor vector).
- **Drop counter.** The existing `fanout.buffer.dropped` counter is `LongAdder`-backed (thread-safe), so
  all shards share it and it remains the aggregate `fanout_buffer_dropped_total` (the ADR-D-A
  cross-shard "drop-amplification" total). Per-shard `fanout.buffer.dropped.<gid>` is a minor follow-up
  (Seam-E style), noted not built.

### Cross-shard ordering semantics (DOCUMENTED — the charter requires this explicitly)

- **Within a shard:** total order + monotone sequence + read-your-writes + monotonic-read, exactly as
  today (single-key linearizability: a key always hashes to one shard).
- **Across shards:** **NO global order.** There is no happens-before between writes to different shards;
  a reader spanning shards composes per-shard cursors (a vector) and does **not** get a consistent
  point-in-time snapshot. One shard's GAP stalls only that shard's slice, never the whole view. The edge
  **must not** infer a cross-shard total order from any interleaving — none is offered (ADR-D-C §"NOT
  guaranteed"). G1 deliberately does **not** fabricate a global merge sequence (which would imply a
  cross-shard order the contract denies and would re-serialize N owner threads through one writer).

## Watch path — bound to the primary group; cross-shard watch is the v2 edge client

`WatchService` is **single-threaded by contract** and uses a **single version cursor** (same collision
problem as the buffer). It has **no production `register()` path** (the standalone server never registers
a watch; like CM-033 BATCH and the edge endpoint, it is dormant infrastructure). G1 therefore keeps
`watchService::onConfigChange` bound to the **primary group only**:

- N=1: byte-identical (one group → today's exact two listeners on the one state machine).
- N>1: the watch listener stays on a single owner thread (the primary's) → **no race** on the
  non-synchronized coalescer. The cross-shard watch aggregation (per-shard watch services + a cursor
  vector, ticked per-owner) rides the **v2 sharded edge client** (handoff §5: *"Client-SDK maturity for
  sharded routing … a polished client is v2"*). This avoids half-building a per-shard watch that would
  also have to touch the consensus tick loop (highest byte-identity risk).

This is not "half-wired N>1": the **fan-out** (G1's mandate) is fully per-shard and correct; the watch
is a separate dormant push mechanism whose multiplexing client is explicitly deferred, and the
limitation is loud (comment + decision log + the N>1 edge-endpoint startup warning below).

## Edge endpoint at N>1 — serves the primary shard, with a loud warning

`NettyFanOutServer` (only when `--edge-port` is set; **off by default**) takes one
`CommitNotificationSource` + one `ReplaySource`. Its single-cursor wire protocol cannot address N
per-shard sequences without the cursor-vector client (v2). At N=1 it gets the primary buffer/replay —
byte-identical. At N>1 it serves the **primary shard** and the server prints a **loud startup warning**
that the sharded edge client (multiplexing the N per-shard sources) is v2 — observable, not silent.

## Implementation (additive; N=1 byte-identical)

- New static package-private helper `ConfigdServer.registerShardedFanOut(runtimes, clock,
  droppedCounter, capacity) → ShardedFanOut(Map<gid,FanOutBuffer>, Map<gid,Compactor>)`: builds one
  buffer + compactor per shard and registers the per-group commit listener (the **same** ConfigDelta
  construction + `publish` + `addSnapshot` as today). Static + testable directly (mirrors
  `buildRaftGroup` / `registerPerShardMetrics` / `shardedConfigReader`).
- `start()` calls the helper over `runtimes`; the **primary** entries become the `fanOutBuffer` /
  `compactor` locals passed downstream + to the `ConfigdServer` constructor + the existing accessors
  (byte-identical). The watch listener stays on the primary. The periodic `compact()` rider on owner[0]
  compacts **every** shard's compactor (`Compactor.compact()` is thread-safe; one compactor at N=1).
- N>1 edge-endpoint warning near the `config.edgeEnabled()` block.

## N=1 byte-identity argument

At `shardCount == 1`, `runtimes` holds only the primary. The helper builds exactly one `FanOutBuffer`
(same capacity, same `fanout.buffer.dropped` counter) + one `Compactor`, and registers exactly the same
fan-out listener (identical delta/notification/publish/addSnapshot logic) on the same primary state
machine, in the same order (fan-out then watch). The downstream wiring receives the same instances.
Nothing on the consensus / WAL / wire / read / write / tick path changes. The per-shard maps hold one
entry; the compact rider iterates one compactor. **Behaviourally identical.**

## Thread-safety (Seam-G §3.3)

- Per-shard buffer/compactor: single-writer-per-buffer (its owner thread); compactor additionally
  thread-safe. No shared mutable fan-out state across owner threads.
- Shared `fanout.buffer.dropped` counter: `LongAdder` (thread-safe).
- Watch: primary-only → single owner thread.
- The listener touches only its group's `stateMachine`/`configStore` (the owner's own objects) — no
  cross-group RaftNode access; the `assertOwnerThread` net is unaffected.

## Verification (G1 DONE criteria)

- `ShardedFanOutTest`: N synthetic groups; per-group applies → each shard's buffer receives exactly its
  shard's commits, in ascending per-shard seq, no cross-shard contamination (isolation); per-shard
  monotonicity; concurrent applies from N threads (one per group) → no corruption (single-writer per
  buffer holds under real concurrency); N=1 registers exactly the primary buffer/compactor identical to
  the prior single-buffer wiring; the cross-shard "no global order" property asserted (interleavings
  vary, per-shard order invariant).
- Re-prove N=1 byte-identity: full `configd-server` suite green (incl. boot/restart, the real-wire
  liveness test, metrics regression).
- Four-way: implementer + diff-review + independent re-run + red-team (attack: a cross-shard buffer leak,
  a concurrent-publish corruption, an N=1 regression, a fabricated global order).

## Four-way outcome + folded findings

- **Diff-review (java-distinguished-engineer): APPROVE-WITH-NITS, 0 must-fix / 0 should-fix.** N=1
  byte-identity verified object-by-object; single-writer-per-buffer sound by construction; the F-0052
  signature/epoch/nonce reads correctly re-scoped per shard (a naive "same listener on every group" would
  have read the primary's signature for every shard — avoided). NITs folded: ordered-immutable return
  maps; primary-scoped accessor Javadoc; doc reconciliation (replay on demand); the concurrency-test
  comment clarified.
- **Red-team (redteam-auditor): SHIP, no CRITICAL/HIGH/MEDIUM.** Could not break concurrency
  (single-writer-per-buffer reduces to `apply`'s pre-existing owner-thread discipline; the compact rider
  vs sibling `addSnapshot` is safe — `ConcurrentSkipListMap` + defensive re-check), cross-shard leak
  (per-iteration capture correct), N=1 identity (test-green + traced), fabricated global order (per-shard
  seq spaces), or silent live data loss (watch dormant + edge off-by-default + N>1 boot-guarded + loud
  warning). Defensive duplicate-gid guard folded.
- **Independent re-run:** `ShardedFanOutTest` 6/0; full `configd-server` 343/0 (incl. boot/restart, the
  real-wire `NettyConsensusLivenessTest`, the metrics regression) — N=1 byte-identity.

### Forward items (recorded, not built in G1)
- **Per-shard `fanout.buffer.dropped.<gid>` (red-team LOW).** The shared aggregate counter conflates real
  drops with non-primary-shard churn once N>1 boots (no consumer reads non-primary buffers until the v2
  client). Ship per-shard drop counters (Seam-E name-encoding) **with or before G4** (when N>1 actually
  boots). Latent behind the boot guard today.
- **Rehoming-quiesce (red-team INFO → G2/G3).** The single-writer-per-buffer guarantee is inherited from
  `apply`'s owner-thread discipline; when rehoming activates (DORMANT in Phase 1), the owner handoff must
  quiesce the state machine (no in-flight `apply→publish`) before the new owner applies, else a brief
  two-writer window. `assertOwnerThread` is the tripwire. G2/G3 live sims should exercise a
  rehome-during-fan-out (D-016 re-verify-on-activation).
