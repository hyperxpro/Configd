# Phase 0 — Workstream B — H-3 Safe-Visibility Design (the monitoring-read hazard)

> **Status:** DESIGN (the §1.2 precondition: "H-3 has an explicit safety design" — required before
> the R-01 deletion merges). Implementation + proof land behind this doc; this doc is the spec the
> H-3 tests assert. Flip §6 of `threading-contract.md` to **CLOSED** only when the tests below are green.
> **Owner:** Workstream B (monitoring-safety). The A net does **not** cover H-3 by design — this builds
> the test that does.

---

## 1. The hazard, made precise (verified against source @ branch `phase0-B-rethreading`)

A (Workstream A) deliberately left five read-only `RaftNode` accessors **unguarded** —
`currentTerm()`, `votedFor()`, `log()`, `transferTarget()`, `clusterConfig()` — because guarding them
under R-01 would fire against today's legitimate **on-tick-thread** monitoring reads. Under R-01 the
tick thread *is* the owner, so those reads are safe. **The moment B deletes R-01 and binds per-group
owners, the tick-loop body stops being the owner of every group**, and those reads become
unsynchronized off-owner reads of non-volatile consensus state.

### 1.1 The single LIVE production site

`ConfigdServer.java:779–795` — the `tickExecutor` scheduled loop:

```java
tickExecutor.scheduleAtFixedRate(() -> {
    driver.tick();                                   // <-- under the pool: FANS OUT to owner threads
    RaftNode tickNode = driver.getGroup(DEFAULT_RAFT_GROUP);
    if (tickNode != null) {
        RaftLog tnLog = tickNode.log();              // (H-3) live RaftLog handed to a non-owner thread
        pendingApplyEntries.set(max(0, tnLog.commitIndex() - tnLog.lastApplied()));  // non-volatile reads
        long term = tickNode.currentTerm();          // (H-3) non-volatile long read off-owner
        ...
    }
    ...
}, ...);
```

Under R-01 this whole lambda runs on the consensus thread → safe. Under the owner-executor pool,
`driver.tick()` only *submits* per-group work to `ownerExecutor(gid)`; the lambda continues on the
`tickExecutor` thread, which is **not** `DEFAULT_RAFT_GROUP`'s owner. So `tickNode.log()` and
`tickNode.currentTerm()` become off-owner reads of `RaftLog.commitIndex/lastApplied` and
`RaftNode.currentTerm` — a torn/stale read with **no guard** to catch it. This is H-3.

### 1.2 The LATENT site

`AdminService.ClusterStateProvider.currentTerm()/commitIndex()/isLeader()/currentLeader()` is an
**interface** consumed by `AdminService.clusterStatus()` on an HTTP thread. `AdminService` is **not
wired into the production server** today (`grep`: no `new AdminService` in any `src/main`; only the
interface + tests). It is a latent off-owner reader: whoever wires it must back the provider with the
published view, never a direct off-owner `RaftNode` read. Recorded so the wiring does not reintroduce H-3.

### 1.3 The `clusterConfig()` subtlety (why "immutable value" is not enough)

`ClusterConfig` is `final` with `private final` fields, **but** carries a lazy
`Map<NodeId,Set<NodeId>> peersCache` populated on first `peersOf()` (a plain `HashMap`). `metrics()`
already calls `clusterConfig.peersOf(self)` on the owner, mutating that cache. Handing the live
`ClusterConfig` to a monitor thread that also calls `peersOf()` is a `HashMap` data race
(lost-update / structural corruption), independent of field visibility. → `clusterConfig()` must be
owner-only; monitors read a pre-computed summary from the published view, never the live object.

### 1.4 The S-set (already safe — unchanged)

`role()`, `leaderId()` are `volatile`; `lastRecordedSeq` is `volatile`; `nodeId()` is immutable.
These stay unguarded by design (atomic single-reference reads, JMM-visible).

---

## 2. Mechanism: one owner-published immutable monitor snapshot

```
   private volatile RaftMetrics monitorView;     // published by the owner, read by anyone
```

- `RaftMetrics` is **already** an immutable record "captured at a single point in time from the Raft
  I/O thread" (nodeId, role, currentTerm, leaderId, commitIndex, lastApplied, lastLogIndex,
  snapshotIndex, logSize, replicationLagMax). It is exactly a coherent monitor snapshot.
- The owner thread **publishes** it via a single volatile store at the **end of every `tick()`**
  (`RaftNode.java:437`, after the group-commit backstop, still on the owner). One store of an
  already-built immutable object.
- Any thread reads it via `monitorView()` — a single volatile load. No guard (it is the *defined*
  safe-cross-thread entry). The immutable record + volatile publish gives a happens-before edge, so
  every field of the observed snapshot is mutually coherent and fully visible. **It cannot tear and
  cannot be partially observed.**

```java
/** Owner-thread-only builder (no guard — private, called on the owner). */
private RaftMetrics buildMetrics() { /* the current metrics() body, minus assertOwnerThread() */ }

public RaftMetrics metrics() { assertOwnerThread(); return buildMetrics(); }   // unchanged for tests/owner

/** Republish the monitor snapshot. Owner-thread-only; called at the end of tick(). */
private void publishMonitorView() { this.monitorView = buildMetrics(); }

/** SAFE-CROSS-THREAD monitoring read: the last owner-published immutable snapshot.
 *  Never blocks the owner, never tears, at most one tick stale. */
public RaftMetrics monitorView() { return monitorView; }
```

`monitorView` is seeded with an initial empty snapshot at construction so a scrape that races startup
sees a coherent zero-snapshot, never `null`.

### 2.1 The staleness / coherence contract

- **Never tears, never partial:** immutable record published by one volatile write → coherent under JMM.
- **Never blocks the owner:** publication is one volatile store of a pre-built object; readers never lock.
- **Bounded staleness ≤ one tick interval:** the view reflects the owner's last end-of-tick publish.
  commitIndex/term may advance inside `handleMessage` between ticks; the view lags by at most one tick.
  This is **within contract for monitoring** (Prometheus scrapes at multi-second cadence; the apply-
  backlog gauge and admin status tolerate one-tick lag). "stale-beyond-contract" = older than the last
  completed tick, which the publish-every-tick discipline forbids.

---

## 3. Per-accessor decision (the §4 deliverable)

| Accessor | New class | Mechanism | Justification |
|---|---|---|---|
| `role()` | **S** | volatile (unchanged) | single volatile-ref read; JMM-visible |
| `leaderId()` | **S** | volatile (unchanged) | single volatile-ref read |
| `nodeId()` | **S** | immutable (unchanged) | final identity |
| `monitorView()` **(NEW)** | **S (published)** | volatile immutable snapshot, owner-published end-of-tick | the one safe cross-thread monitoring entry; never tears/partial/blocks |
| `currentTerm()` | **O (now guarded)** | `assertOwnerThread()` added | no prod off-owner caller after retarget; value surfaced via `monitorView().currentTerm()` |
| `votedFor()` | **O (now guarded)** | `assertOwnerThread()` added | test/owner-only; no prod reader |
| `transferTarget()` | **O (now guarded)** | `assertOwnerThread()` added | test/owner-only; no prod reader |
| `clusterConfig()` | **O (now guarded)** | `assertOwnerThread()` added | lazy `peersCache` makes off-owner read unsafe regardless of visibility (§1.3) |
| `log()` | **O (now guarded)** | `assertOwnerThread()` added | returns the live mutable `RaftLog`; scrape switches to `monitorView()` scalars |
| `metrics()` | **O (unchanged guard)** | `assertOwnerThread()` (already) | off-owner scrape forbidden; monitors use `monitorView()` |

**Net effect:** H-3's five-accessor blind spot is converted from *unguarded* to *guarded owner-only*,
with `monitorView()` as the safe alternative. The A net now **covers** the former H-3 surface — any
future off-owner read of these accessors trips `raft_owner_thread`. This strictly strengthens the net.

---

## 4. Retarget (the one live site)

`ConfigdServer.java:786–795` reads `tickNode.monitorView()` instead of the live node:

```java
RaftMetrics view = tickNode.monitorView();
pendingApplyEntries.set(Math.max(0L, view.commitIndex() - view.lastApplied()));
long term = view.currentTerm();
```

Off-owner-safe under the pool; behaviorally identical under R-01 (the view was published by the
`driver.tick()` that just ran on the same thread). Forward-compatible — lands now, correct after the
deletion.

---

## 5. Proof obligations (the test the net does not provide)

1. **JMM visibility (jcstress, `configd-jcstress`).** Two `@State` actors mirror the publish/read:
   the owner publishes monotonically-stamped snapshots via the volatile ref; the reader loads
   `monitorView()` and checks coherence. **Forbidden:** a torn/incoherent snapshot (a field from
   publish *k* mixed with a field from publish *j≠k*) or a stale-beyond-last-publish read. Proves the
   volatile-published-immutable pattern has no JMM hole.
2. **Targeted concurrency (`configd-consensus-core` test).** Bind an owner; the owner rapidly mutates
   term/commit through guarded entry points and republishes; a foreign thread spins on `monitorView()`
   asserting every observed snapshot is internally coherent (`snapshotIndex ≤ lastApplied ≤
   commitIndex ≤ lastLogIndex`, term non-decreasing), never null, never throws. **And** asserts the
   live accessors now **trip the guard** when called from the foreign thread (the net extension fires).
3. **No regression.** Full `configd-consensus-core` + `configd-server` suites green: the new guards are
   inert until `bindOwnerThread()` (unit tests unaffected); sim/owner-path callers read on the owner.

---

## 6. Why this is safe to land now (under R-01) and forward-compatible

- `publishMonitorView()` needs no binding — it just builds + volatile-stores; works under R-01 and the pool.
- The new accessor guards are **inert until `bindOwnerThread()`**, which production calls only in
  Stage 1 (B wires the pool). So in the current R-01 build the guards never fire in production; in the
  sim they fire only off the drive thread (the bound owner). No behavior change to the live server now.
- The scrape retarget is behaviorally identical under R-01 and required under the pool.

→ H-3 closes as additive, net-strengthening work during the CI window, before the R-01 deletion — exactly
as §1.2 / the working order require.
