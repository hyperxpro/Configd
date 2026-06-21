# Phase 0 — Workstream B — Handoff (Stage 1 checkpoint)

> **State as of `a3c8349` on branch `phase0-B-rethreading` (pushed).** The R-01 deletion (the most
> dangerous change in the project) is DONE and verified at N=1. H-3 is CLOSED. Stage 2 (N>1 +
> coalesced heartbeats) is designed and not yet built. This handoff lets Stage 2 resume cleanly — or
> the branch merge to `main` at the §6 gate once Stage 2 + gate-B land.

---

## 1. The threading model (what sharding/Phase 1 will build on)

R-01 ("one `configd-tick` thread owns every `RaftNode`") is **replaced** by the owner-executor pool:

```
ownerExecutor(gid) = pool[ floorMod(gid, N) ]      // OwnerExecutorPool, STATIC for process life (v1)
```

- `OwnerExecutorPool` (`configd-replication-engine`): N single-thread scheduled executors. Each group
  binds to one owner; every OWNER-ONLY `RaftNode` entry point for a group runs on that group's owner
  thread → `RaftNode` stays unsynchronised, asserted by the `assertOwnerThread()` net (now ACTIVE in
  production after `bindOwnerThread()`).
- `MultiRaftDriver`: `setOwnerPool`, `ownerExecutor(gid)`, per-owner `tickOwner(i)`/`maybeCompactOwner(i)`;
  `groups` is a `ConcurrentHashMap` (H-5).
- `ConfigdServer` wiring: pool created (`configd.raft.ownerPoolSize`, **default N=1**); owner[0] bound
  first; tick loop on owner[0] (`tickOwner(0)`); the 4 marshalling hops (inbound / propose / read
  double-hop / flush) target `driver.ownerExecutor(gid)`; per-owner shutdown.
- **At N=1 this is behaviourally exact-R-01** (one owner thread, same cadence/FIFO) — the deletion is
  of the single-thread *assumption*, not yet a multi-thread runtime. Raising N is Stage 2.
- **Phase 1 (NOT here):** sharding logic (routing, ShardMap) sits on this model. Dynamic resharding
  (re-binding an owner) is v2/out of scope (`adr-multiraft-sharding-deferred`).

## 2. What is BUILT and VERIFIED (Stage 1, @ N=1)

| Piece | Commit | Verified |
|---|---|---|
| Baseline repair (gitleaks FP) | `cedc706` (main) | full-nightly CI green (gates 1–7) on `cedc706` |
| **H-3 CLOSED** — owner-published `monitorView()` snapshot + 5 accessors guarded | `711b30f` | jcstress `PublishedSnapshotNeverTears` 182/182 + macro `RaftMonitorViewConcurrencyTest` 4/4 + adversarial replay |
| Stage 1A — `OwnerExecutorPool` + driver support (additive) | `4a1e3da` | consensus-core 342/0, replication-engine green |
| **Stage 1B — R-01 DELETED @ N=1** | `682cbcf` | net re-proven to catch under the pool (`OwnerNetCatchesOffOwnerInboundTest`); sim S2–S4 2052/0 (zero owner-thread fires); server 165/0; behavioural equivalence; **adversarial red-team SOUND** |

Decisions D-010..D-013; threading-contract §4.2/§7 as-built; reviews under `docs/phase0-B/reviews/`.

## 3. What is DEFERRED — Stage 2 (designed, NOT built)

The Stage-1 design (`stage1-design.md §7`) + the session brief §6.4 define Stage 2:

1. **N>1 pool capability + owner-isolation proof.** Raise `ownerPoolSize`; exercise multiple owner
   threads driving multiple groups (a TEST harness adds groups — production stays single-group until
   Phase 1 sharding). Prove group→owner isolation: a deliberate cross-group access (an entry point for
   group A run on group B's owner) trips the net. The wiring already expresses every hop as
   `ownerExecutor(gid)`, so raising N fans groups across owners with NO further server-wiring change to
   the marshalling points — but the **tick loop must generalize** from `tickOwner(0)` on owner[0] to
   `tickOwner(i)` scheduled on each owner[i].
2. **H-4 — co-tenant rehoming.** At N>1 the riders (watch/plumtree/propagation/compactor) + the H-3
   scrape can't ride a single owner. Move them to a dedicated **housekeeping** scheduled executor.
   Recon (D-013 / stage1-design §5) confirms they don't read `RaftNode` directly → this is rehoming,
   not re-synchronising; the scrape already uses `monitorView()` (safe off-owner).
3. **Coalesced heartbeats** (CockroachDB/TiKV): one per-owner tick coalesces heartbeats to shared peers
   so heartbeat cost is **flat in group count** — prove it (benefits N=1 too). The per-owner-tick shape
   is already in place (`tickOwner(i)`).
4. **Re-run the S2–S4 surface with multiple groups active** (the multi-owner verification).

## 4. Deferred beyond Stage 2

- **Throughput levers** (proposal batching / replication pipelining / per-tick broadcast coalescing) —
  the etcd-class single-group throughput fixes. Each behind the net, S2–S4 re-closed.
- **The single-group throughput MEASUREMENT** (the Phase-0 v1-vs-v2 decision gate) — needs **real
  hardware**; it is **Workstream C on a box**, not this 2-vCPU env (D-003). B builds the levers; C
  measures.
- **gate-B** (`gates/gate-B.sh`, CI-wired, cumulative with 1–7): the net re-proven to catch under the
  pool (have it), owner-isolation at N>1 (Stage 2), coalesced-heartbeat cost-flat (Stage 2), H-3 tests
  (have them), S2–S4 under the new threading (have N=1), `cedc706` baseline-green precondition. Author
  after Stage 2 so it gates the full B surface.

## 5. Residual risks / notes

- **Shutdown caveat (pre-existing, R-01-identical):** the propose-timeout `cancelCommitOutcome` cleanup
  is submitted to an owner from the HTTP thread and may be dropped in the owner's shutdown window —
  harmless best-effort map cleanup at process exit; the write already returned `Indeterminate`. Not a
  regression (R-01 did the same). Recorded by the adversarial review.
- **C2 (safe publication) at N>1:** `monitorView` non-null relies on the node being published into the
  `groups` CHM (happens-before) before any off-owner scrape. Preserve this when Stage 2 moves the scrape
  to housekeeping. Currently fine (CHM `put` in `addGroup`).
- **The net only catches GUARDED entry points.** The adversarial review confirmed no ungated off-owner
  path to non-volatile `RaftNode` state exists today — but any NEW public `RaftNode` method that reads/
  mutates non-volatile state MUST be guarded (or the consumer must use `monitorView()`/the S-set).

## 6. Merge-gate status (§6)

- [x] CI green on the baseline (`cedc706`, the repaired `df3f3b7`).
- [x] The net catches a race UNDER the owner-executor pool (`OwnerNetCatchesOffOwnerInboundTest`,
      permanent; proven non-vacuous by the adversarial guard-neuter red).
- [x] H-3 closed.
- [x] S2–S4 surface green at the stage merged — **at N=1** (Stage 1).
- [ ] Owner-isolation at N>1 + coalesced-heartbeat cost-flat (Stage 2).
- [ ] gate-B green in CI, cumulative with 1–7.

**Stage 1 is merge-ready to `main` on its own** (a complete, verified R-01-deletion-@-N=1). The
conservative path is to land Stage 2 + gate-B on the branch first, then merge the whole of B at the
§6 gate. Either way `main` stays pinned at `cedc706` until the gate (D-010).
