# Phase 0 — Workstream B — Stage 1 Design: the owner-executor pool @ N=1 (R-01 deletion)

> **Status:** DESIGN (written during the baseline-CI window, per §1.1; the deletion IMPLEMENTS only
> after CI on df3f3b7 is green and H-3 is adversarially confirmed). The §2 prime directive: stage at
> **N=1 first** to isolate "re-threading broke it" from "multi-group broke it".
> **Depends on:** H-3 CLOSED (the scrape/monitoring riders can now read `monitorView()` off-owner —
> this is what makes decomposing the single tick thread possible). See `h3-monitor-view-design.md`.

---

## 1. The model

```
OwnerExecutorPool: N single-thread ScheduledExecutorServices, owner[0..N).
ownerExecutor(groupId) = owner[ groupId % N ]          // STATIC for process life (v1, no resharding)
ownerGroups(i)        = { g : g % N == i }             // the groups owner i drives
```

- Each group binds to exactly one owner; `RaftNode` stays unsynchronised because every OWNER-ONLY
  entry point for a group runs on that group's owner thread (the A net's `assertOwnerThread()` proves it).
- **Stage 1 sets N=1**: a single owner drives every group — behaviourally the R-01 tick thread, minus
  the co-tenant riders (which move off it, §3). Stage 2 raises N and adds coalesced heartbeats.

## 2. What R-01 is, concretely (the thing being deleted)

`ConfigdServer` creates ONE `configd-tick` single-thread scheduled executor (`:367`) and runs ONE
`scheduleAtFixedRate` lambda (`:779–823`, period `TICK_PERIOD_MS`) that does FOUR distinct jobs on
that one thread:
1. **Consensus tick** — `driver.tick()` (`MultiRaftDriver.tick()` loops every group's `node.tick()`).
2. **Metric scrape** — reads `tickNode.log()`/`currentTerm()` for the apply-backlog gauge + election
   counter (`:786–795`). **Already retargeted to `monitorView()` by H-3.**
3. **Raft-log compaction** — `driver.maybeCompact(threshold)` (`:803`), O(groups).
4. **Co-tenant housekeeping (H-4)** — `propagationMonitor.checkAll()`, `watchService.tick()`,
   `plumtreeNode.tick()`, and `compactor.compact()` every `COMPACTION_INTERVAL_TICKS` (`:804–811`).

Plus four **marshalling points** that hop work onto that one thread:
- inbound routing — `raftInboundHandler(... tickExecutor ...)` → `driver.routeMessage(gid,msg)` (`:430`,`:1101`)
- propose — the `raftProposer(... tickExecutor ...)` task: `driver.propose(gid,cmd)` + `whenCommitOutcome` register, **(index,term) captured INSIDE the task, never returned across the boundary** (`:1217`, H-1)
- linearizable read — `readDispatchExecutor → tickExecutor`: `readIndex/whenReadReady/isReadReady/completeRead` (`:630–663`, H-2)
- group-commit flush — `setGroupCommit((flush,delay) -> tickExecutor.execute/schedule(flush))` (`:400–408`)

## 3. Decomposing the one tick thread (the heart of Stage 1)

| Old rider (on `configd-tick`) | New home @ N=1 | Why safe |
|---|---|---|
| consensus `driver.tick()` | **per-owner scheduled tick** — each owner schedules ONE task that iterates `ownerGroups(i)` calling `node.tick()` | at N=1 the single owner iterates ALL groups = the exact R-01 loop; `assertOwnerThread()` holds (tick runs on the group's owner) |
| metric scrape | **monitoring executor**, reads `node.monitorView()` | H-3 CLOSED — `monitorView()` is a safe off-owner volatile read |
| `driver.maybeCompact()` | **per-owner** — folded into the owner's tick (iterate `ownerGroups(i)`) | `maybeCompact` is OWNER-ONLY; running it on the group's owner is correct |
| co-tenant `propagationMonitor/watchService/plumtree/compactor` (H-4) | **housekeeping executor** (a dedicated single-thread scheduled executor, NOT an owner) | these read consensus-DERIVED state, not `RaftNode` internals directly; where they need consensus state they read `monitorView()` or a marshalled snapshot — audited in §5 H-4 |

→ Net thread picture @ N=1: **1 owner + 1 monitoring + 1 housekeeping** (vs R-01's single thread). The
**consensus path is wholly on the 1 owner** (R-01-equivalent); only the *non-consensus* riders split
off — which is exactly the decomposition H-3 was the precondition for.

**The central tick scheduler:** the per-owner tick is scheduled on `ownerExecutor(i)` itself
(`owner[i].scheduleAtFixedRate(tickOwnerGroups(i), …)`), so there is no separate "driver loop" thread
and no extra hop. `MultiRaftDriver.tick()`/`maybeCompact()` become **per-owner-scoped** iterations
(iterate the caller-owner's groups), or are replaced by a `tickOwner(i)` the pool drives. The driver's
"must be accessed from a single thread" contract is replaced: the `groups` map becomes shared (§5 H-5).

## 4. Marshalling retargets (mechanical, pattern-preserving)

Each hop changes its target from the single `tickExecutor` to `ownerExecutor(gid)`; the *shape* is unchanged:
- inbound: `raftInboundHandler(driver, gid, ownerExecutor(gid), metrics)`
- propose: `raftProposer(driver, gid, ownerExecutor(gid), …)` — the (index,term)-captured-inside-the-task pattern (H-1) is retained verbatim
- read: `readDispatchExecutor.execute(() -> ownerExecutor(gid).execute(() -> readIndex/whenReadReady/…))`
- flush: `setGroupCommit((flush,delay) -> ownerExecutor(gid).execute/schedule(flush))` — per-node, so the wiring must know each node's gid→owner

Each is an **M** boundary the net must prove never leaks an inline `RaftNode` touch (re-run the harness's
hop-removal red under the new model — §6).

## 5. The open hazards (each a red/green in Stage 1)

- **H-1 propose return.** ALREADY resolved by the existing server pattern: `driver.propose()`'s
  synchronous `(index,term)` is consumed ON the owner inside the proposer task (to register
  `whenCommitOutcome`); the HTTP thread gets a future completed from the owner. Retarget only. Verify no
  OTHER caller marshals the result back across threads (recon: only the server proposer + testkit
  benches, which call on their own drive thread).
- **H-2 readIndex confirm.** Retarget the read double-hop to `ownerExecutor(gid)`; re-prove the
  linearizable-read safety checks hold under concurrency (the read confirm path stays owner-only).
- **H-3 monitoring reads.** ✅ CLOSED (published `monitorView()`); this is the enabler for §3.
- **H-4 co-tenant work. — RECON DONE, more tractable than feared.** Audit of the four riders found
  **none reads `RaftNode`/`MultiRaftDriver`/consensus internals directly** (grep across their classes
  for `RaftNode`/`currentTerm`/`commitIndex`/`role`/`monitorView` is empty): `PropagationLivenessMonitor`
  is **push-fed** (`updateLeaderCommit(commitIndex)`), `WatchService`/`PlumtreeNode` operate on their own
  distribution state, and `Compactor` is the config-store snapshot-retention compactor (no Raft
  coupling). So H-4 is **rehoming, not re-synchronising**: move the four onto a dedicated housekeeping
  scheduled executor; the only consensus-state read in the old tick lambda was the metric scrape, which
  H-3 already moved to `monitorView()`. The net still backstops a missed direct touch once owners bind.
- **H-5 groups map.** `MultiRaftDriver.groups` (plain `HashMap`) is now read by every owner + inbound
  thread → `ConcurrentHashMap`. `addGroup`/`removeGroup` are infrequent; they must not race iteration
  (CHM gives weakly-consistent iteration — acceptable for tick fan-out; document it).
- **H-6 bind timing.** For each group, submit `node.bindOwnerThread()` as the FIRST task on
  `ownerExecutor(gid)` at wiring, BEFORE scheduling its tick and BEFORE the transport accept loop
  publishes the inbound handler. Never bind in the constructor (it runs on `main`).

### 5.1 Carry-forwards from the H-3 adversarial review (`reviews/h3-adversarial-review.md`)

The independent review confirmed H-3 CLOSED (could not break the safety) and flagged two items that
become live in Stage 1 — both must be honored when the pool binds owners:
- **C2 (safe publication of the node reference).** `monitorView`'s "never null" today relies on the
  `RaftNode` being safely published (constructor → `addGroup` → executor-submit, all on the bootstrap
  thread before any scrape). When Stage 1 shares nodes cross-thread, that safe-publication edge MUST be
  preserved (publish each node into the `groups` map with a happens-before edge to every reader) or a
  racing monitoring read could observe `monitorView == null` and NPE. The bind-as-first-task ordering
  (H-6) + a `ConcurrentHashMap` put (H-5) provide it; verify it explicitly.
- **C3 (the scrape still reads the `groups` MAP off-owner).** H-3 fixed the node-STATE read, but the
  scrape obtains the node *reference* via `driver.getGroup()` from a non-owner thread, and
  `MultiRaftDriver.groups` is a plain `HashMap` (`:53`). This is **H-5**, and the review rightly
  elevates it: it is on the LIVE scrape path, so the `HashMap → ConcurrentHashMap` change MUST land
  with (or before) the move of the scrape off the wiring thread — i.e. as part of Stage 1, before owners bind.

## 6. N=1 staging + verification (the §2 prime directive)

**Behavioural equivalence @ N=1** = the *consensus* observable behaviour is identical to R-01: same
commits for the same proposals, same elections/failover, same linearizable-read semantics, no new
race. The co-tenant rehoming (§3) is a deliberate, separately-verified change (watch/plumtree/
propagation/compaction keep their own tests green), NOT part of the consensus-equivalence claim.

Verification surface to re-close @ N=1 (each captured under `docs/phase0-B/captures/`):
1. **Net still GREEN** — `RaftNodeConcurrencyStressTest` + the sim owner-binding, under the pool wiring.
2. **Net still CATCHES** — re-run the "test the tester": remove one marshalling hop (inbound or propose)
   so a `RaftNode` touch happens off the group's owner → the tripwire must fire (captured red). A guard
   that stopped firing after the refactor is worse than no guard.
3. **S2–S4 invariant surface** — adversarial sim seed-sweep (full count) + linearizability/Porcupine
   checker + jcstress curated + chaos subset, all green under the new threading.
4. **Behavioural equivalence** — a targeted test: drive the same proposal/failover script under R-01
   wiring and N=1-pool wiring, assert identical commit/leader outcomes.

## 7. Deferred (NOT Stage 1)

- **Stage 2:** N>1 (multiple owners), owner-isolation proof (a deliberate cross-group access is
  caught), coalesced heartbeats (per-owner single tick coalesces heartbeats to shared peers → cost
  flat in group count). The per-owner-tick design (§3) is already the right shape for coalescing.
- **Throughput levers** (proposal batching / replication pipelining / per-tick broadcast coalescing):
  if context allows, else handed off.
- **Phase 1:** sharding logic (routing, ShardMap) — B delivers only the threading model it sits on.

## 8. Implementation order (so the deletion is staged safely)

1. Introduce `OwnerExecutorPool` (N=1) + `ownerExecutor(gid)` on the driver; bind owners (H-6); keep the
   OLD tick lambda intact but route its consensus tick through the owner — prove the net green + the
   S2–S4 surface, with R-01 still structurally present. (Additive; reversible.)
2. Move the metric scrape to `monitorView()` (done) and the co-tenant riders to housekeeping (H-4),
   leaving consensus on the owner. Prove watch/plumtree/propagation tests green.
3. **Delete R-01**: remove the single-`configd-tick`-thread assumption; the per-owner tick is now the
   only consensus driver. Retarget all four marshalling points to `ownerExecutor(gid)`. Re-run §6 (net
   green + net catches + S2–S4 + equivalence). Checkpoint (net-green, pushed).

This keeps a fully-verified seam at every step; if context runs low mid-stage, REVERT to the last
clean seam rather than leaving R-01 half-deleted.
