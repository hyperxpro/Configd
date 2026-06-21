# Phase 0 — Threading Contract (the R-01 Replacement Specification)

> **Status:** DRAFT — spec written *before* the re-threading code, per session §4.3.
> **Replaces:** R-01 ("a single `configd-tick` thread owns every `RaftNode`").
> **Authority of record:** the runtime `assertOwnerThread()` tripwire + the concurrent
> stress harness — NOT this document. Line numbers below are as-of-current-`HEAD` and are
> navigational; the classification (O / M / S) is the durable content.
> **Prime directive:** no re-threading (Workstream B) is blessed until the harness that
> encodes this contract exists and is *proven to catch an injected off-owner-thread access*
> (the captured red). This doc is the specification that harness asserts.

---

## 0. Provenance (verified against source)

| Fact | Source |
|---|---|
| `RaftNode` has no synchronization; single-thread access by design | `RaftNode.java:15–37` |
| Volatile cross-thread fields are exactly `role`, `leaderId` (+ `lastRecordedSeq`) | `RaftNode.java:53–57` |
| `MultiRaftDriver` holds a plain `HashMap`, "must be accessed from a single thread only" | `MultiRaftDriver.java:22–23,41` |
| One `configd-tick` single-thread executor drives consensus | `ConfigdServer.java:367–371` |
| R-01 invariant: ALL RaftNode access on the tick thread; inbound/read/flush *marshal* onto it | `ConfigdServer.java:362–366,400–408,427–430` |
| The tick thread is **not** consensus-only — also watch/plumtree/propagation/compactor + metric reads | `ConfigdServer.java:779–809` |
| `RaftNode` has the `InvariantChecker` seam already (throws in sim, metric in prod) | `RaftNode.java:219–224,260–269` |
| The owner-thread tripwire pattern to mirror | `ConfigStateMachine.java:271–286` (RR-029/W-1) |
| `FlushScheduler` seam (INLINE default; prod dispatches onto the tick executor) | `RaftNode.java:159,164,176`; `ConfigdServer.java:400–408` |
| Single-group throughput ceiling is this thread, not fsync/CPU | RR-113, `docs/readiness-register.md`; `docs/session-7.5/throughput-part2.md` |

---

## 1. What R-01 is today (the thing being replaced)

`RaftNode` (≈2475 lines) carries all consensus state **with no locks and no atomics**, except
`role`/`leaderId`/`lastRecordedSeq` which are `volatile` purely so HTTP threads can read them.
Safety rests on a single fact: **exactly one thread ever touches a `RaftNode`.** That thread is
the process-wide `configd-tick` thread. Everything that is not naturally on it is *marshalled*
onto it:

- `MultiRaftDriver.tick()` loops every group's `RaftNode.tick()` inline on the tick thread.
- Inbound Raft messages: the transport inbound handler does `tickExecutor.execute(() -> driver.routeMessage(...))`.
- Linearizable reads: `readDispatchExecutor` hops to `tickExecutor` before touching the node.
- Group-commit flush: `FlushScheduler` dispatches `flushDurable` onto `tickExecutor`.
- Proposes: `ConfigWriteService` marshals onto `tickExecutor` before `driver.propose(...)`.

**Enforcement = marshalling discipline + a comment-only contract.** There is *no runtime guard*
on `RaftNode` (unlike `ConfigStateMachine`, which has `assertOwnerThread()`). A single missed hop
is an undetected data race in the consensus core.

**Why this caps throughput (RR-113):** one thread serializes propose + per-proposal broadcast +
apply + inbound + heartbeat. Above ~800–1000 writes/s the heartbeat slips the election timeout →
leadership churn → throughput inverts. Measured **not** fsync-bound (`iostat f/s`=0 on NVMe) and
**not** aggregate-CPU-bound (86% idle). The fix to this thread *is* the throughput fix — which is
why Phase 0 exists.

---

## 2. The new model — one *owner* thread per group, from a pool

```
ownerExecutor(groupId) = pool[ groupId % poolSize ]     // STATIC for the life of the process
```

- Each owner is a single-thread executor. Each group is bound to exactly **one** owner thread.
- All **OWNER-ONLY** entry points for a group execute on that group's owner thread — so the
  per-group single-writer invariant is preserved and `RaftNode` *stays unsynchronized*.
- Different groups may progress on different threads (the throughput unlock at N>1); the same
  group never does (the safety preservation).
- **Static mapping, v1:** a group's owner never changes. Dynamic resharding (which would require
  re-binding an owner) is **v2, explicitly out of scope** (`adr-multiraft-sharding-deferred`,
  `adr-multiraft-topology`).
- **At N=1 this is still a win:** the pool is size 1, but coalesced heartbeats + group-commit
  flush + batching (Workstream B) move heartbeat/flush *off the contended path*, and the
  owner-thread guard is identical. The decision gate (Workstream C) measures N=1-with-fix.

**R-01 → the new invariant (R-01′):** *For each group g, every OWNER-ONLY entry point of its
`RaftNode` executes on `ownerExecutor(g)`'s thread, and that is proven by a runtime tripwire that
throws in test/sim and counts-a-metric in production.*

---

## 3. Entry-point classification

Every entry point is exactly one of:

- **O — OWNER-ONLY.** Mutates or reads *non-volatile* `RaftNode`/`RaftLog` state. MUST run on the
  group's owner thread. Guarded by `assertOwnerThread()`.
- **M — MARSHALLED-EXTERNAL.** Callable from any thread, but its *only* action on the caller
  thread is to enqueue work onto `ownerExecutor(g)`. It never touches `RaftNode` state inline.
  **The hop is the safety boundary** — and the place bugs hide.
- **S — SAFE-CROSS-THREAD.** May be read from any thread because the field is `volatile` or
  immutable. **The S set is closed and small:** `role()`, `leaderId()`, `lastRecordedSeq`, and
  immutable config/identity (`nodeId`). Anything else read cross-thread is a violation.

### 3.1 `MultiRaftDriver` — the routing seam (`MultiRaftDriver.java`)

| Entry point | line | Class | Contract note |
|---|---|---|---|
| `tick()` | 100 | **O→fan-out** | Today loops inline. New: submit each `node.tick()` to `ownerExecutor(gid)`; the driver's own loop becomes a fan-out, not an executor of consensus. |
| `maybeCompact(t)` | 117 | **O→fan-out** | Same fan-out per group. |
| `routeMessage(gid,msg)` | 134 | **M** | Enqueue `node.handleMessage(msg)` onto `ownerExecutor(gid)`. Today the *caller* (inbound handler) marshals onto the single tick executor; new target is the group's owner. |
| `propose(gid,cmd)` | 154 | **M** | ⚠ **H-1**: returns `ProposeOutcome(index,term)` **synchronously** — the result crosses the marshalling boundary. See §6 H-1. |
| `addGroup` / `removeGroup` | 68 / 83 | **O (driver)** | Mutates the `groups` map. See §4.3 — the map itself becomes shared. |
| `getGroup`/`groupIds`/`groupCount` | 173–193 | **S** | Reads of `groups` — requires a concurrent/snapshot map (§4.3). |
| `localNode`/`clock` | 200–211 | **S** | Immutable. |

### 3.2 `RaftNode` public API (`RaftNode.java`)

All **O** unless marked. Reached either directly on the owner thread (tick path) or via an **M**
hop (inbound/propose/read).

| Entry point | line | Class | Note |
|---|---|---|---|
| `tick()` | 375 | **O** | The bind point (§4.1). |
| `handleMessage(msg)` | 398 | **O** | Entry from `routeMessage` (M). |
| `propose(cmd)` | 422 | **O** | Entry from `MultiRaftDriver.propose` (M); see H-1. |
| `transferLeadership(t)` | 477 | **O** | |
| `triggerSnapshot()` | 507 | **O** | |
| `maybeCompact(t)` | 568 | **O** | |
| `readIndex()` | 616 | **O** | Entry from read-dispatch (M); see H-2. |
| `isReadReady` / `completeRead` / `whenReadReady` | 641 / 715 / 736 | **O** | F-0010 callback maps are tick-thread-only. |
| `whenCommitOutcome` / `cancelCommitOutcome` | 854 / 875 | **O** | RR-004 callback maps are tick-thread-only. |
| `proposeConfigChange(voters)` | 1023 | **O** | |
| `clusterConfig()` | 1084 | **O** | (Safe only during single-thread construction.) |
| `metrics()` | 1230 | **O** | ⚠ **H-3**: reads non-volatile `nextIndex`/`matchIndex`/`currentTerm`. Safe today only because scraped inline on the tick thread (`ConfigdServer:786–795`). Any off-thread scrape is a violation. |
| `role()` / `leaderId()` | 1258–1264 | **S** | `volatile`. |
| `currentTerm()` / `votedFor()` / `log()` / `transferTarget()` | 1258–1264 | **O** | Non-volatile — owner thread only. |

### 3.3 Message handlers — all **O**, reachable only via `handleMessage` (O)

`handleAppendEntries`(1348), `handleAppendEntriesResponse`(1415), `handleRequestVote`(1458),
`handlePreVoteRequest`(1500), `handleRequestVoteResponse`(1528), `handlePreVoteResponse`(1557),
`handleTimeoutNow`(1577), `handleInstallSnapshot`(2163), `handleInstallSnapshotResponse`(2327).
No external thread reaches these except through the `handleMessage` (O) entry; the guard there
suffices.

### 3.4 Internal state-machine helpers — all **O** (private; inherit owner from caller)

`tickElection`, `tickHeartbeat`, `startPreVote`, `startElection`, `becomeFollower`, `becomeLeader`,
`broadcastAppendEntries`, `sendAppendEntries`, `sendInstallSnapshot`, `maybeAdvanceCommitIndex`,
`applyCommitted`, `fireReadyCallbacks`, `fireCommitOutcomes`, `recordAppliedSeq`,
`recomputeConfigFromLog`, `handleCommittedConfigChange`, `buildActiveSetAndReset`,
`confirmPendingReads`, `maybeSendTimeoutNow`, `resetElectionTimeout`, `decideCommitOutcome`.
These are private; the public-entry guard covers them. They are listed so the harness can confirm
none is exposed as a new cross-thread entry by the re-threading.

### 3.5 The durability flush seam — **O**, but dispatched (`RaftNode.java:159,164,176,1938,1962`)

| Entry point | line | Class | Note |
|---|---|---|---|
| `scheduleFlush()` | 1938 | **O** | Called from tick/propose (owner). |
| `flushDurable()` | 1962 | **O via FlushScheduler** | `FlushScheduler` INLINE default runs it on the caller thread (an owner thread → owner-safe in tests). Production must dispatch to `ownerExecutor(gid)`, **not** the single `tickExecutor`. See §4.4. |
| `setGroupCommit(sched,…)` | 176 | wiring | Called once at construction; rewires the dispatch target. |

### 3.6 `RaftLog` mutators — all **O**, reachable only from `RaftNode` (O)

`appendNoSync`, `appendEntries`, `append`, `setCommitIndex`, `setLastApplied`, `syncWal`,
`compact`, and the non-volatile getters (`commitIndex`, `lastApplied`, `lastIndex`,
`snapshotIndex`). No independent external entry; inherits owner from `RaftNode`.

### 3.7 Co-tenant tick work — today on the tick thread (`ConfigdServer.java:779–809`)

These are **not** `RaftNode` entry points but they currently share its thread and so are part of
the contract surface the re-threading must re-home:

| Work | line | New home (to be specified in Workstream B) |
|---|---|---|
| `driver.maybeCompact(...)` (Raft) | 800 | O, fan-out per owner. |
| `propagationMonitor.checkAll()` | 801 | ⚠ **H-4** — needs an explicit executor; reads consensus-derived state. |
| `watchService.tick()` | 802 | ⚠ **H-4** |
| `plumtreeNode.tick()` | 803 | ⚠ **H-4** |
| `compactor.compact()` (every ~10s) | 808 | ⚠ **H-4** |
| inline metric reads (`tickNode.log()`, `currentTerm()`) | 786–795 | O — must run on the group's owner (see H-3). |

---

## 4. Enforcement mechanism (concrete — the R-01 replacement)

### 4.1 Owner-thread tripwire on `RaftNode` (mirror of `ConfigStateMachine`)

Add to `RaftNode`, modeled exactly on `ConfigStateMachine.assertOwnerThread()` (`:271–286`):

```java
private volatile Thread ownerThread;   // bound once on the owner thread; never reassigned in v1

/** Bind explicitly as the FIRST task on the owner executor (NOT during construction,
 *  which runs on the wiring thread and legitimately touches state). */
void bindOwnerThread() { this.ownerThread = Thread.currentThread(); }

private void assertOwnerThread() {
    Thread owner = ownerThread;
    if (owner == null) return;          // INERT until explicitly bound — NOT lazy-bind: a
                                        // pre-bind off-thread call must not capture a wrong owner
    Thread cur = Thread.currentThread();
    if (owner != cur) {
        // No metrics call: RaftNode has no metrics sink (verified :240–364). The PROD
        // InvariantChecker records the metric + SEVERE; the test/harness checker throws.
        // One seam, both modes.
        invariantChecker.check("raft_owner_thread", false,
            "RaftNode entry off owner thread: bound '" + owner.getName()
            + "' but called from '" + cur.getName() + "' — R-01′ violated");
    }
}
```

> **As-built (increment 1):** `volatile ownerThread`; **inert until `bindOwnerThread()`** (no
> lazy-bind, so production — not yet wired to bind — and existing single-threaded tests are
> unaffected). Guards placed at the core O entry points `tick`, `handleMessage`, `propose`,
> `maybeCompact`, `readIndex`, `whenCommitOutcome`, `metrics` (the last two are the H-3/H-1 +
> compaction race vectors). Remaining O entry points (`transferLeadership`, `triggerSnapshot`,
> `proposeConfigChange`, `completeRead`, `whenReadReady`, `cancelCommitOutcome`) are a tracked
> follow-up within A.1.

- Call `assertOwnerThread()` at the **top of every O public entry point** in §3.2/§3.3
  (`tick`, `handleMessage`, `propose`, `readIndex`, `whenCommitOutcome`, `maybeCompact`,
  `transferLeadership`, `proposeConfigChange`, `flushDurable`, `metrics`, …).
- Route through the **existing `invariantChecker`** (`RaftNode.java:224`): the harness injects a
  throwing checker (red on violation); production injects the metric/SEVERE one. **No new
  verification plumbing** — it reuses the seam that already drives the 9 in-node invariants.
- **Binding rule:** bind explicitly via `bindOwnerThread()` submitted as the first task to
  `ownerExecutor(gid)` at wiring. Construction must *not* bind (it runs on `main` and legitimately
  reads log/config — `RaftNode.java:330`).

### 4.2 Owner-executor pool

`MultiRaftDriver` gains `ownerExecutor(gid) = pool[gid % poolSize]`. `tick()`/`maybeCompact()`
fan out by submitting per-group; `routeMessage`/`propose` target the group's owner. The single
`configd-tick` executor is decomposed; `poolSize=1` reproduces today's behavior (the N=1 decision-
gate config) with heartbeat/flush coalescing layered on.

### 4.3 `groups` map safety

`MultiRaftDriver.groups` (plain `HashMap`, `:41`) is read by every owner thread once tick fans
out. It becomes shared state. Required: a `ConcurrentHashMap` (or a copy-on-write snapshot taken
on the fan-out thread). `addGroup`/`removeGroup` are infrequent (config change) — marshal them onto
a driver-owner or guard with the same discipline; they must not race iteration.

### 4.4 Flush dispatch retarget

`setGroupCommit` wiring (`ConfigdServer:400–408`) must dispatch `flushDurable` onto
`ownerExecutor(gid)` for the group, not the global `tickExecutor`. The INLINE test default stays
owner-safe (runs on the calling owner thread).

### 4.5 Marshalling points preserved, retargeted

Inbound handler (`ConfigdServer:427–430`), read dispatch, and ConfigWriteService propose all keep
their hop — but the target changes from the single `tickExecutor` to `ownerExecutor(gid)`. Each is
an **M** boundary the harness must prove never leaks an inline `RaftNode` touch.

---

## 5. Verification obligations (what the harness in §A.1 must prove)

1. **Tripwire fires.** Inject an off-owner-thread call to each O entry point → `raft_owner_thread`
   check goes red. (The §A.2 *prove-it-catches-a-race* capture.)
2. **No O reachable from an M caller without the hop.** Drive `routeMessage`/`propose`/read/flush
   concurrently from foreign threads; assert zero inline `RaftNode` touches (tripwire silent under
   correct marshalling, red when a hop is removed).
3. **The S set is exactly `{role, leaderId, lastRecordedSeq}` + immutable.** No other field is read
   cross-thread (static check + runtime scrape audit for H-3).
4. **Invariants hold under concurrent drive.** All 9 in-node checks (`RaftNode.java:683–702`) + the
   4 cross-node (`SimInvariants`) green while tick + inbound + propose + commit-callback +
   flush + compaction + ReadIndex + metric-read race.
5. **Coalesced-heartbeat property** (Workstream B): heartbeat traffic flat in group count.

---

## 6. Open hazards (the things that will bite — each gets a red/green in Workstream B)

- **H-1 — `propose()` return crosses the boundary.** `(index,term)` is assigned on the owner
  thread but the caller wants it synchronously. Resolution options: (a) submit-and-await on the
  owner executor; (b) make the propose path fully callback-based via the existing
  `whenCommitOutcome` seam and return only an accept/reject + a ticket. Must be chosen and
  red/greened before propose is re-threaded.
- **H-2 — ReadIndex confirm path.** `readIndex()`/`whenReadReady` today hop read-dispatch→tick;
  retarget to the owner and prove the linearizable-read safety checks (`RaftNode.java:780–791`)
  still hold under concurrency.
- **H-3 — metrics scrape reads non-volatile state.** `metrics()` reads `nextIndex`/`matchIndex`/
  `currentTerm`. Safe only on the owner thread. Either keep scraping on-owner (snapshot into
  volatile/immutable carriers) or make a published metrics snapshot. A Prometheus scrape thread
  must never call `metrics()` directly.
- **H-4 — co-tenant tick work** (watch / plumtree / propagation / compactor). Biggest one: when
  Raft tick fans out, these lose their implicit thread. They need an explicit home and an O/M/S
  re-classification against *their own* owner. The naive "owner-executor pool" design omits this.
- **H-5 — `groups` map** concurrent iteration vs. add/remove (§4.3).
- **H-6 — bind timing.** Construction touches state on `main`; binding must happen on the owner
  executor's first task, never during the constructor.

---

## 7. Definition of "contract satisfied"

- [ ] `assertOwnerThread()` at EVERY O public entry point — **core 7 done** (tick, handleMessage,
      propose, maybeCompact, readIndex, whenCommitOutcome, metrics); remaining O entry points
      (transferLeadership, triggerSnapshot, proposeConfigChange, completeRead, whenReadReady,
      cancelCommitOutcome) are a tracked A.1 follow-up.
- [ ] Owner-executor pool wired; `poolSize=1` reproduces today's single-group behavior.
- [x] Concurrent stress harness (`RaftNodeConcurrencyStressTest`) encodes obligations §5.1–§5.4 and
      is **proven to catch an injected off-owner-thread access** (captured red — see
      `captures/harness-catches-injected-race.md`) before any Workstream B re-threading is blessed.
- [ ] H-1…H-6 each resolved behind a red/green stress test.
- [ ] Full S2–S4 invariant surface (sim + linearizability + jcstress + chaos subset) re-runs green
      under the new threading.

*This contract is the spec. The harness is the enforcement. The captured red is the proof.*
