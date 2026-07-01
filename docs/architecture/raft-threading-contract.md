# Raft owner-thread threading contract

This is the concurrency specification for `RaftNode`. It is a load-bearing invariant: a future
consensus maintainer must follow it, because `RaftNode` carries all of its consensus state with **no
locks and no atomics**, and its safety rests entirely on single-thread ownership.

The authority of record is the runtime owner-thread assertion (`assertOwnerThread`) plus the
concurrency stress harness that exercises it -- not this prose. This document is the specification
those mechanisms enforce. Method names below are navigational; the classification (owner-only /
marshalled / safe) is the durable content.

## The invariant

> For each group `g`, every owner-only entry point of its `RaftNode` executes on `g`'s single owner
> executor thread, and it does so for the entire life of the process.

Different groups may make progress on different threads (this is the multi-shard throughput unlock).
The same group never does (this is the safety preservation). One group, one owner thread, forever.

## Why RaftNode is unsynchronized

`RaftNode` holds all consensus state -- term, vote, log, commit/apply indices, election and
heartbeat timers, callback maps, cluster configuration -- with no synchronization at all, **except a
small, closed set of volatile fields** that exist purely so other threads can read a few values
safely. The design is sound only because exactly one thread ever touches a given node's
non-volatile state. Remove that guarantee and every field becomes an undetected data race.

The single global tick thread that once provided this guarantee has been replaced by one owner
thread per group, so the guarantee is now stated and enforced per node rather than assumed.

## The safe cross-thread set

The only `RaftNode` fields any thread may read directly are:

- `role`
- `leaderId`
- `lastRecordedSeq`
- `nodeId` (immutable identity/config)

These are volatile or immutable. **The set is closed and small.** Anything else read from a
`RaftNode` off its owner thread is a violation. Widening this set is a deliberate act, not a
convenience: each addition must be genuinely volatile or immutable and must be justified.

## monitorView() -- the one safe monitoring read

Monitoring and metrics need more than the four safe fields (term, log indices, cluster
configuration, a `RaftMetrics` snapshot), and all of that is non-volatile owner-only state. Rather
than let a scrape thread read it directly, the owner **publishes an immutable `RaftMetrics` snapshot
through a single volatile reference at the end of every `tick()`**. Any thread reads it with one
volatile load via `monitorView()`.

`monitorView()` is the single safe cross-thread monitoring read. It:

- never tears and is never partially observed (immutable object behind one volatile publish),
- never blocks the owner,
- is at most one tick stale.

Every non-volatile accessor that monitoring might otherwise reach -- `currentTerm()`, `votedFor()`,
`log()`, `transferTarget()`, `clusterConfig()`, `metrics()` -- is owner-only and guarded. Off-owner
callers use `monitorView()` instead.

## Entry-point classification

Every entry point is exactly one of three kinds.

- **Owner-only (O).** Mutates or reads non-volatile `RaftNode`/`RaftLog` state. MUST run on the
  group's owner thread. Guarded by `assertOwnerThread()`.
- **Marshalled-external (M).** Callable from any thread, but its only action on the caller thread is
  to enqueue work onto the group's owner executor. It never touches `RaftNode` state inline. The hop
  is the safety boundary -- and the place bugs hide.
- **Safe cross-thread (S).** The closed volatile/immutable set above, plus the published
  `monitorView()`.

### Owner-only entry points

The public mutator and callback surface is owner-only and guarded: `tick`, `handleMessage`,
`propose`, `maybeCompact`, `readIndex`, `whenCommitOutcome`, `cancelCommitOutcome`, `metrics`,
`transferLeadership`, `triggerSnapshot`, `isReadReady`, `completeRead`, `whenReadReady`,
`proposeConfigChange`. The read-only accessors that touch non-volatile state -- `currentTerm`,
`votedFor`, `log`, `transferTarget`, `clusterConfig` -- are also owner-only and guarded; off-owner
monitoring uses `monitorView()`.

The message handlers (`handleAppendEntries`, `handleAppendEntriesResponse`, `handleRequestVote`,
`handlePreVoteRequest`, `handleRequestVoteResponse`, `handlePreVoteResponse`, `handleTimeoutNow`,
`handleInstallSnapshot`, `handleInstallSnapshotResponse`) are all owner-only, reachable only through
`handleMessage`; the guard there covers them. The internal state-machine helpers
(`tickElection`, `startElection`, `becomeLeader`, `broadcastAppendEntries`, `applyCommitted`,
`fireReadyCallbacks`, and the rest) are private and inherit the owner from their caller. `RaftLog`
mutators (`append`, `setCommitIndex`, `setLastApplied`, `syncWal`, `compact`, ...) are owner-only,
reachable only from `RaftNode`.

### The durability flush seam

`scheduleFlush()` and `flushDurable()` are owner-only. The flush scheduler's inline default runs on
the calling owner thread (owner-safe); in production the flush is dispatched onto the group's owner
executor, never a shared executor. The one exception is `setGroupCommit(...)`, a pure wiring mutator
called exactly once during construction before the owner is bound -- it is intentionally outside the
owner-thread contract because it never runs concurrently with consensus.

### Marshalled seams

Every path that reaches a `RaftNode` from another thread hops onto the group's owner first:

- **Inbound messages** -- routing demultiplexes by `groupId` before dispatch, then enqueues
  `handleMessage` onto the group's owner.
- **Propose** -- marshalled onto the owner; the `(index, term)` result is captured inside the
  marshalled task so it does not cross the boundary raw.
- **Linearizable reads** -- the ReadIndex confirm path hops to the owner before touching the node.
- **Group-commit flush** -- dispatched to the owner.

Each is an M boundary. The harness proves that none of them ever leaks an inline `RaftNode` touch;
remove a hop and the assertion trips.

## The owner-executor model

```
ownerExecutor(groupId) = pool[groupId % poolSize]   // static for the life of the process
```

- Each owner is a single-thread executor. Each group binds to exactly one owner thread.
- The pool size is `configd.raft.ownerPoolSize` (default 1). At N=1 the pool is size 1 and the
  behavior is identical to the earlier single-thread model, with heartbeat coalescing and
  group-commit flush layered on.
- The mapping is **static in v1**: a group's owner never changes. Dynamic resharding -- which would
  require re-binding an owner -- is a v2 concern (ADR-multiraft-topology), and its group-rehoming
  handoff mechanism ships dormant. If a future placement policy ever activates it, that mechanism
  must be re-verified live before use; the dormant-state proofs do not transfer to live rehoming.

## Enforcement

`assertOwnerThread()` sits at the top of every owner-only entry point. It routes through the
existing invariant checker: the test/sim checker **throws** on a violation; the production checker
records a metric and a `SEVERE` log. One seam, both modes, no separate verification plumbing.

**The guard is per-node, not per-pool.** Each `RaftNode` checks against its own bound owner thread.
A group whose entry point is invoked on a different owner thread from the same pool still trips the
guard -- co-tenancy in one pool is not co-ownership of one group.

## Binding rule

Bind the owner explicitly, as the **first task submitted to the group's owner executor**, via
`bindOwnerThread()`. Never bind in the constructor: construction runs on the wiring thread and
legitimately reads log and configuration, so binding there would capture the wrong owner.

Until a node is bound, the assertion is inert (it returns without firing). This is deliberate and is
not lazy-binding: a pre-bind, off-thread call must not capture a wrong owner. The node becomes
guarded the moment its owner runs `bindOwnerThread()`, and stays guarded thereafter.

## The rule for future maintainers

This is the part that matters when you add code:

> **Any new `RaftNode` method that touches non-volatile state MUST either assert the owner thread
> (if it is owner-only) or read only through `monitorView()` / the safe set (if it must be reachable
> cross-thread).**

A new cross-thread read of non-volatile state is a data race, full stop. If you need a value for
monitoring, publish it in the tick-end snapshot and read it via `monitorView()`. If you add a field
to the safe set, prove it is genuinely volatile or immutable first. When in doubt, make the method
owner-only and marshal callers onto the owner.

## Co-tenant housekeeping

Watch, Plumtree, propagation-health, and compaction housekeeping ride the primary owner thread as
singleton work. They hold no `RaftNode` reference (the distribution-service and observability modules
do not even depend on the consensus-core module), so a single home is safe. Moving them to a
dedicated housekeeping executor is a cleanliness option, not a correctness requirement.

## Verification

The contract is enforced, not just asserted:

- **Runtime assertion** on every guarded owner-only entry point (above).
- **Concurrency stress harness** proven to catch an injected off-owner access at every guarded entry
  point, under concurrent tick + inbound + propose + commit-callback + flush + compaction +
  ReadIndex + metric-read.
- **JMM-level checks** that the published `monitorView()` snapshot never tears, and that an unbound
  guard genuinely races to a lost update (so binding is demonstrably mandatory, not decorative).
- **Deterministic simulation** binds each node's owner to its drive thread and rides the same
  throwing checker as the in-node invariants, across large seed sweeps and adversarial schedules,
  with an injected off-thread access proven to fail the seed.

This contract is the specification. The runtime assertion and the stress harness are the
enforcement. A captured, deliberately-injected violation firing red is the proof.
