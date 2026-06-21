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
| `clusterConfig()` | 1146 | **O (now guarded)** | **H-3 CLOSED.** Owner-only; also unsafe off-owner via its lazy `peersCache` HashMap (§6 H-3). Monitors read `monitorView()`. |
| `metrics()` | 1292 | **O** | Guarded; builds an immutable `RaftMetrics`. **H-3 CLOSED:** off-owner scrapes read `monitorView()`, never `metrics()` directly. |
| `monitorView()` | (B) | **S — published** | ⭐ The owner-published immutable `RaftMetrics` snapshot (volatile ref, republished at end of `tick()`). The ONE safe cross-thread monitoring read — never tears/partial, never blocks the owner, ≤ 1 tick stale. |
| `role()` / `leaderId()` / `nodeId()` | 1321–1326 | **S** | `volatile` / immutable. |
| `currentTerm()` / `votedFor()` / `log()` / `transferTarget()` | 1322–1327 | **O (now guarded)** | **H-3 CLOSED.** Non-volatile — owner-thread-only, `assertOwnerThread()` added; monitors use `monitorView()`. |

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
| `setGroupCommit(sched,…)` | 176 | **wiring (pre-bind)** | The one public *mutator* deliberately NOT guarded: called exactly once during construction/wiring (`ConfigdServer:400`), before the owner is bound, so it is **out of the owner-thread contract** (the tripwire is inert pre-bind by design). Not a coverage hole — it never runs concurrently with consensus. |

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

> **As-built (increment 2 — Workstream A closeout):** `volatile ownerThread`; **inert until
> `bindOwnerThread()`** (no lazy-bind, so production — not yet wired to bind — and existing
> single-threaded tests are unaffected). `bindOwnerThread()` is now a **public** wiring API (the
> owner-executor pool in Workstream B and the deterministic-sim harness both call it).
> `assertOwnerThread()` now guards the **complete mutator/callback O entry-point surface — all 14**:
> the core 7 (`tick`, `handleMessage`, `propose`, `maybeCompact`, `readIndex`, `whenCommitOutcome`,
> `metrics` — the H-1/H-3 + compaction vectors) **plus the 7 review-H2 orphaned riders**
> (`transferLeadership`, `triggerSnapshot`, `isReadReady`, `completeRead`, `whenReadReady`,
> `cancelCommitOutcome`, `proposeConfigChange`). Review-H2 is **CLOSED**.
>
> **The H-3 reader surface — now CLOSED in Workstream B.** The read-only O accessors
> `currentTerm()`, `votedFor()`, `log()`, `transferTarget()`, `clusterConfig()` (commented
> "tests and monitoring") were the **H-3** off-owner-read hazard. A left them unguarded so they would
> not fire against the then-legitimate on-tick-thread monitoring reads. B resolved H-3 with an
> owner-published immutable snapshot (`monitorView()`), retargeted the one live reader
> (`ConfigdServer` scrape) onto it, and **then guarded all five** with `assertOwnerThread()` — so the
> former blind spot is now net-covered (any off-owner read trips `raft_owner_thread`). The S set
> (`role()`, `leaderId()`, `nodeId()`) stays unguarded by design (volatile / immutable), joined by the
> new published `monitorView()`. See §6 H-3 and `docs/phase0-B/h3-monitor-view-design.md`.

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

> **As-built — Stage 1B (R-01 DELETED @ N=1, `682cbcf`; pool added in Stage 1A `4a1e3da`):**
> `OwnerExecutorPool(Integer.getInteger("configd.raft.ownerPoolSize", 1))`; the driver exposes
> `ownerExecutor(gid)` + per-owner `tickOwner(i)`/`maybeCompactOwner(i)` and holds `groups` as a
> `ConcurrentHashMap` (H-5). `ConfigdServer` removed the single `configd-tick` executor: the tick loop
> runs `driver.tickOwner(0)` on owner[0]; the four marshalling hops (inbound / propose / read
> double-hop / flush) target `driver.ownerExecutor(DEFAULT_RAFT_GROUP)`; `bindOwnerThread()` is the
> FIRST task submitted to owner[0] (H-6). At N=1 this is exact R-01 cadence/FIFO; the per-owner
> fan-out generalizes and the co-tenant riders (still on owner[0]) decompose to a housekeeping thread
> at **Stage 2 (N>1)**.

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
- **H-3 — monitoring reads of non-volatile state. ✅ CLOSED (Workstream B).** The five read-only
  accessors (`currentTerm`/`votedFor`/`log`/`transferTarget`/`clusterConfig`) and `metrics()` read
  non-volatile consensus state, safe under R-01 only because the scrape ran inline on the tick thread;
  once owners bind and `tick()` fans out, that scrape runs off the group's owner.
  **Mechanism:** the owner publishes an immutable `RaftMetrics` snapshot through one `volatile`
  reference (`monitorView`) at the end of every `tick()`; any thread reads it via `monitorView()` with
  a single volatile load — never tears, never partial, never blocks the owner, ≤ 1 tick stale. The one
  live reader (`ConfigdServer` scrape) was retargeted onto `monitorView()`; `AdminService`'s
  `ClusterStateProvider` (latent — unwired) is recorded to do the same. The five accessors were then
  **guarded** (`assertOwnerThread()`), converting the blind spot into net-covered surface.
  **Evidence:** `configd-jcstress` `RaftMonitorViewPublicationTest.PublishedSnapshotNeverTears` (JMM:
  immutable-via-volatile never observed torn; `PerFieldPublishCanTear` control shows the naïve
  alternative tears) + `RaftMonitorViewConcurrencyTest` (macro: coherent/monotonic/non-null/non-block
  under concurrent publish; the five accessors trip off-owner while `monitorView()`/S-set stay safe).
  Design: `docs/phase0-B/h3-monitor-view-design.md`.
- **H-4 — co-tenant tick work** (watch / plumtree / propagation / compactor). Biggest one: when
  Raft tick fans out, these lose their implicit thread. They need an explicit home and an O/M/S
  re-classification against *their own* owner. The naive "owner-executor pool" design omits this.
- **H-5 — `groups` map** concurrent iteration vs. add/remove (§4.3).
- **H-6 — bind timing.** Construction touches state on `main`; binding must happen on the owner
  executor's first task, never during the constructor.

---

## 7. Definition of "contract satisfied"

- [x] `assertOwnerThread()` at EVERY **mutator/callback** O public entry point — **all 14 done**
      (core 7: tick, handleMessage, propose, maybeCompact, readIndex, whenCommitOutcome, metrics;
      review-H2 7: transferLeadership, triggerSnapshot, isReadReady, completeRead, whenReadReady,
      cancelCommitOutcome, proposeConfigChange). Review-H2 CLOSED. The read-only O accessors
      (currentTerm/votedFor/log/transferTarget/clusterConfig) — formerly the **H-3** monitoring-read
      hazard — are now ALSO guarded (Workstream B), with the owner-published `monitorView()` snapshot
      as the safe cross-thread read. **H-3 CLOSED** — see §6 H-3 + `docs/phase0-B/h3-monitor-view-design.md`.
- [x] Concurrent stress harness (`RaftNodeConcurrencyStressTest`) encodes obligations §5.1–§5.4 and
      is **proven to catch an injected off-owner-thread access across all 14 guarded entry points**
      (the off-owner fire-test covers the complete surface; captured red — see
      `captures/harness-catches-injected-race.md`) before any Workstream B re-threading is blessed.
- [x] **JMM micro-race** (`configd-jcstress` `RaftOwnerThreadGuardTest`): the guard's `volatile`
      publication has **no false negative once a node is in service** (gated/clean), and an *unbound*
      guard genuinely **races to a lost update** (forbidden-hitting control, observed ≈34% — proving
      binding is mandatory). Complements the macro harness with memory-model rigor.
- [x] **Sim integration**: the tripwire is bound across the deterministic sim
      (`ClusterHarness`/`AdversarialSim` bind each node's owner to the drive thread) and rides the
      same throwing checker as the in-node invariants — **20,001 seed-sweep + adversarial schedules
      green** (no spurious fire), and `OwnerThreadSimIntegrationTest` proves an injected
      off-drive-thread access fails the seed (§5.4).
- [x] Owner-executor pool wired; `poolSize=1` reproduces today's single-group behavior. **(Stage 1B
      `682cbcf` — R-01 deleted @ N=1; behaviourally exact-R-01; the net is now ACTIVE in production.)**
- [~] H-1, H-2, H-4, H-5, H-6 — resolved at N=1: **H-1** ((index,term) captured inside the marshalled
      task — preserved), **H-2** (read double-hop retargeted to the owner), **H-5** (`groups` →
      `ConcurrentHashMap`, Stage 1A), **H-6** (`bindOwnerThread()` first task on owner[0]). **H-4**
      (co-tenant rehoming) DEFERRED to Stage 2 — recon shows the riders don't touch `RaftNode` so they
      ride owner[0] safely at N=1. **H-3 ✅ CLOSED** — `monitorView()` snapshot + five accessors guarded.
- [x] S2–S4 invariant surface re-runs green under the new threading **at N=1**: 2052-seed sim sweep +
      consistency/failover + `OwnerThreadSimIntegrationTest` (0 fail, 0 unintended `raft_owner_thread`),
      server suite 165/0, and the net RE-PROVEN to catch off-owner inbound under the pool
      (`OwnerNetCatchesOffOwnerInboundTest`). The **multi-owner (N>1)** surface is Stage 2.

**Workstream A (the verification net) is CLOSED.** The remaining unchecked boxes are Workstream B
(the re-threading the net now guards).

*This contract is the spec. The harness is the enforcement. The captured red is the proof.*
