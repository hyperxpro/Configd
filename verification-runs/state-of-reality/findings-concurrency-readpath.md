# Findings — Concurrency of the Read/Write Path (state-of-reality)

Teammate: `concurrency-readpath`. READ-ONLY audit. Every claim cites `file:line`
against the actual code, classified with one literal tag. Docs/comments/prior
audits were NOT trusted as evidence — re-derived from source and a real build/test run.

## Bottom line (5 bullets)

1. **The "lock-free edge reads" claim (ADR-0005) is TRUE for the read path.**
   grep over `configd-edge-cache/src/main` and `configd-config-store/src/main`
   finds **zero** `synchronized`, `ReentrantLock`, `ReadWriteLock`, `StampedLock`,
   `AtomicReference`, `VarHandle`, or CAS on the read path. A read is a single
   `volatile` load + immutable HAMT traversal. [VERIFIED-PASS] (grep + code).
2. **Snapshot publication is SAFE.** The pointer is a real `volatile ConfigSnapshot`
   field (`LocalConfigStore.java:48`, `VersionedConfigStore.java:42`). The published
   graph — `ConfigSnapshot` (record), `HamtMap`, `BitmapIndexedNode`/`ArrayNode`/
   `CollisionNode`, `VersionedValue` (record) — is entirely **final-field**, built
   before publication and never mutated after. A reader seeing the new pointer sees a
   fully-constructed snapshot. [VERIFIED-PASS] for "all fields final"; [EXISTS-UNTESTED]
   for the JMM negative (no jcstress to demonstrate it).
3. **The ADR's stated reason for `volatile` over `AtomicReference` mis-describes the
   barriers, but the code is correct.** ADR-0005:48 calls the writer's `volatile` store
   a "plain store (StoreStore barrier)." A `volatile` store is a **release**
   (StoreStore+LoadStore), not "plain"; the read is an **acquire** (LoadLoad+LoadStore).
   Implemented `volatile` semantics give the needed happens-before edge; only the prose
   is wrong. [DOC-ONLY] discrepancy.
4. **One real shared-mutable-state ESCAPE on the read path:** `ReadResult.value()`
   (`ReadResult.java:56-58`) returns the **live internal** `byte[]` — that array is
   `VersionedValue`'s internal array shared inside the HAMT (via `valueUnsafe()`). A
   caller mutating `store.get(k).value()` corrupts the value every other reader sees,
   contradicting the class's "Immutable result" javadoc. Not a JMM bug; a
   mutable-aliasing escape. [VERIFIED-PASS] (path traced; untested).
5. **"Stress testing" is one CountDownLatch test per store; there is NO jcstress.**
   grep for `jcstress` across the repo and root `pom.xml`: none. The two concurrency
   tests are single-writer/4-reader loops that pass but cannot demonstrate absence of a
   JMM reordering bug. Build is green: config-store **127** tests, edge-cache **151**
   tests, 0 fail / 0 skip ([VERIFIED-PASS], totals below).

---

## Read-path walkthrough (entry -> snapshot read -> return), file:line

Edge read (the ADR-0005 hot path):

1. `EdgeConfigClient.get(String)` `EdgeConfigClient.java:66` -> delegates to `store.get(key)`.
2. `LocalConfigStore.get(String)` `LocalConfigStore.java:119`
   - `ConfigSnapshot snap = currentSnapshot;` `:121` — **single volatile read** (acquire).
     The only synchronization on the entire read path.
   - `VersionedValue vv = snap.data().get(key);` `:122` — immutable HAMT traversal.
3. `HamtMap.get(K)` `HamtMap.java:87` -> `root.get(...)` -> `BitmapIndexedNode.get` `:209`
   / `ArrayNode.get` `:421` / `CollisionNode.get` `:534`. All read **final** fields
   (`bitmap`, `array`, `count`, `children`, `hash`, `pairs`), recurse, zero allocation,
   no locks.
4. Return: miss -> `ReadResult.NOT_FOUND` singleton `:124`; hit ->
   `ReadResult.found(vv.valueUnsafe(), vv.version())` `:126` (allocates one `ReadResult`;
   the value array is **shared, not copied** — see Bug R-1).

Cursor-bound read `LocalConfigStore.get(String, VersionCursor)` `:141`: same volatile read
`:144`, then a plain `snap.version() < cursor.version()` staleness gate `:145` — coherent
because `version` is a field of the same immutable snapshot just read (no second volatile
load, so version/data cannot tear).

Zero-copy read `getInto` `:178-198`: single volatile read `:185`, `arraycopy` out of the
shared internal array `:195`. Safe to copy concurrently because the source array is never
mutated.

Server-side MVCC mirror: `VersionedConfigStore.get` `VersionedConfigStore.java:189-197`
(identical pattern). `getPrefix` `:263-273` does an O(N) `forEach` over the same immutable
snapshot into a local `LinkedHashMap` — safe (local map, immutable source).

Write path (single writer, publishes the new pointer):

- Edge: `EdgeConfigClient.applyDelta` `EdgeConfigClient.java:130` ->
  `LocalConfigStore.applyDelta` `:229`. Reads `currentSnapshot` `:232`, folds mutations
  into a fresh immutable HAMT `:243-254`, then `currentSnapshot = new ConfigSnapshot(...)`
  `:256` — **volatile release store, the publication point.** No object mutated after this.
- Server: `ConfigStateMachine.applySwitch` `ConfigStateMachine.java:247-303` (Raft apply
  thread) -> `store.put/delete/applyBatch` (`VersionedConfigStore.java:91/112/132`), each
  ending in `currentSnapshot = new ConfigSnapshot(...)` (`:103/:122/:159`).

Single-writer is a **documented precondition, not enforced** — no guard, lock, or
owner-thread assertion anywhere (`LocalConfigStore.java:32-33`,
`VersionedConfigStore.java:23-28`, `ConfigStateMachine.java:24-28` all say "caller must
ensure"). See Bug W-1.

---

## Bug / finding table

| # | finding | severity | classification | file:line | interleaving / argument | tested? |
|---|---------|----------|----------------|-----------|--------------------------|---------|
| R-1 | `ReadResult.value()` returns the HAMT's **live internal** `byte[]` (no copy); javadoc claims "Immutable result." A reader mutating it corrupts the shared value for all readers. | High (if any caller mutates) | [VERIFIED-PASS] (path traced) | `ReadResult.java:56-58`; array originates `VersionedValue.valueUnsafe()` `VersionedValue.java:45-47`, passed at `LocalConfigStore.java:126`/`VersionedConfigStore.java:196` | Thread A: `byte[] v = store.get("k").value(); v[0]=0;`. Thread B: `store.get("k").value()` now sees the mutated byte — same array instance held in the HAMT leaf `array[2i+1]`. Pure aliasing escape, no JMM issue. | **No.** No test asserts `value()` is a copy. Contrast: `VersionedValue.value()` `:36-38` and `ConfigMutation.Put.value()` `:37-39` DO clone; `ReadResult.value()` does not. |
| W-1 | Single-writer is unenforced. Two concurrent writers (misconfig) -> **lost updates** + broken version monotonicity. | Medium (precondition) | [EXISTS-UNTESTED] (reasoned) | `LocalConfigStore.applyDelta:229-257`; `VersionedConfigStore.put:91-104` | Writer1 and Writer2 both read `currentSnapshot` (v=5) at `:232`/`:102`, each build a new HAMT from v5, each store back. Second store wins; first writer's mutation silently lost, version still advanced. The `sequence <= version` check `:94` is itself check-then-act and does not make the RMW atomic. | No test drives concurrent writers (both concurrency tests use exactly one writer). |
| R-2 | `currentSnapshot` is non-final volatile, reassigned post-construction (`loadSnapshot` `:265-268`, `restoreSnapshot` `:174-177`). By design (RCU) and SAFE — listed to confirm checked. | Info | [VERIFIED-PASS] | `LocalConfigStore.java:48,256,267`; `VersionedConfigStore.java:42,103,122,159,176` | Every assignment stores a fully-built immutable graph; readers always see a complete `ConfigSnapshot`. No interleaving yields a torn object. | Indirectly — `concurrentReadersObserveConsistentSnapshotsWhileWriterMutates` asserts no torn `(version,value)` over 10k writes x 4 readers; passes. |
| R-3 | `StalenessTracker` has **three independent volatile** fields updated non-atomically; `recordUpdate` writes `lastVersion` then `lastUpdateNanos` `:99-100`; INV-S1 diagnostic in `isStale` reads `lastVersion`+`lastObservedRemoteVersion` `:125-126` possibly from different instants. | Low (diagnostics only) | [VERIFIED-PASS] (code) | `StalenessTracker.java:48-60,98-101,121-129` | Writer sets `lastVersion=10` `:99`; reader's `isStale` reads `lastVersion=10` but `lastObservedRemoteVersion=7` (stale) -> slightly inconsistent log line. Cannot corrupt store state. Explicitly documented acceptable (`EdgeConfigClient.java:185-191`). | No (tolerated monitoring race). |
| W-2 | `ConfigStateMachine` mutates **non-volatile** fields on the apply thread (`sequenceCounter` `:107`, `lastSignature` `:72`, `signingEpoch` `:79`, `lastEpoch`/`lastNonce`) read by **public getters** (`sequenceCounter()` `:698`, `lastSignature()` `:663`, `lastEpoch()` `:671`, `lastNonce()` `:679`, `signingEpoch()` `:689`). A non-apply-thread caller with no HB edge may read a stale value. | Medium (depends on caller threading, outside these modules) | [EXISTS-UNTESTED] (reasoned) | `ConfigStateMachine.java:72,79,107,611,663-699` | Apply thread sets `lastSignature=sig` (plain store) `:611`, `sequenceCounter=seq` `:274`. Server thread calls `lastSignature()` `:663` with no synchronization -> may observe `null` / previous signature (no HB edge for the plain store). Reachability depends on `configd-server` wiring (out of scope here). | No. |
| D-1 | ADR-0005 barrier prose inaccurate: line 48 calls the `volatile` store "plain store (StoreStore barrier)"; line 47 says read gives "LoadLoad + LoadStore." Store is release (StoreStore+LoadStore); read is acquire (LoadLoad+LoadStore). Code correct; doc misstates JMM. | Cosmetic | [DOC-ONLY] | `docs/decisions/adr-0005-lock-free-edge-reads.md:47-48` | Documentation-vs-JMM wording only; implemented `volatile` semantics are sufficient and correct. | N/A |

### Claims that ARE true (re-verified, not taken on faith)

- "Lock-free / zero-CAS / zero-AtomicReference read path" — [VERIFIED-PASS]: grep of both
  `src/main` trees returns no lock/atomic/varhandle/CAS construct; the only
  `AtomicReference` token is a comment in `LocalConfigStore.java:45` explaining its absence.
- "Immutable HAMT with structural sharing" — [VERIFIED-PASS]: `HamtMap` and all three node
  types have only `final` fields and copy-on-write `put`/`remove`
  (`HamtMap.java:57-63,189-195,412-418,520-526`; `cloneAndSet` `:338`, `replaceChild` `:473`).
  `put`/`remove` return `this` on no-op (`:112,130`) — zero allocation.
- "Snapshot isolation" — [VERIFIED-PASS]: `snapshotIsolationAcrossBulkWrites`
  (`VersionedConfigStoreConcurrencyTest.java:147`) ran and passed (run below).
- "Defensive copy on `VersionedValue`/`ConfigMutation.Put` construction" — [VERIFIED-PASS]:
  `VersionedValue.java:28` (`value = value.clone()`), `ConfigMutation.java:32`. Note the
  asymmetry with R-1: inputs copied, but the `ReadResult` output is not.

---

## Test run evidence

Command (run in main tree `/home/ubuntu/Code/Configd`):

```
./mvnw -pl configd-edge-cache,configd-config-store -am test
```

Result: **BUILD SUCCESS.** Per-module reactor totals (`-am` also builds common /
observability / consensus-core; the two target modules are):

```
config-store reactor:  Tests run: 127, Failures: 0, Errors: 0, Skipped: 0
edge-cache reactor:    Tests run: 151, Failures: 0, Errors: 0, Skipped: 0
```

Concurrency-specific tests that executed and passed:
- `io.configd.store.VersionedConfigStoreConcurrencyTest` —
  `concurrentReadersObserveConsistentSnapshotsWhileWriterMutates` (10k writes x 4 readers;
  asserts no torn `(version,value)` and monotonic per-reader versions);
  `snapshotIsolationAcrossBulkWrites`.
- `io.configd.edge.LocalConfigStoreTest$ConcurrentAccess` — 1 test, passed (1 writer / 4 readers).

What the tests catch vs miss:
- They catch R-2 (torn snapshot) and it does NOT fire — consistent with the safe-publication
  argument.
- They do NOT catch R-1 (no test mutates the returned array), W-1 (no concurrent-writer
  test), W-2 (no cross-thread getter test), or R-3 (tolerated).
- There is NO jcstress module, so no test positively demonstrates the *absence* of a JMM
  reordering bug; the safe-publication claim rests on the final-field + volatile argument.
  [EXISTS-UNTESTED] for the JMM negative.

---

## Cross-examination requests (for peers)

1. **verification-evidence / design-vs-reality:** Trace the real caller of
   `ReadResult.value()` in `configd-server` / `configd-control-plane-api` /
   `configd-distribution-service`. Does any path mutate the returned array (or hand it to a
   reused buffer)? If yes, R-1 is a live bug; if all callers are read-only, R-1 is a latent
   hazard / doc bug only. (My audit is scoped to the two store modules.)
2. **design-vs-reality:** Is `ConfigStateMachine` ever read (via `lastSignature()` /
   `sequenceCounter()` / `lastEpoch()` / `lastNonce()`) from a thread other than the Raft
   apply thread with no intervening lock/volatile? That decides whether W-2 is a real
   visibility risk. Check the server wiring that attaches signatures to outgoing `ConfigDelta`s.
3. **consensus-correctness:** Confirm the "single writer" precondition is actually guaranteed
   upstream — Raft apply loop and edge `DeltaApplier` each strictly single-threaded. If
   anything can call `applyDelta`/`put`/`restoreSnapshot` concurrently, W-1 (lost updates)
   becomes live. The store provides NO enforcement.
4. **consensus-correctness / design-vs-reality:** `LocalConfigStore.get(key, cursor)` returns
   `NOT_FOUND` `:152` when the local snapshot is *behind* the cursor, conflating "stale node"
   with "key absent." Is that the intended monotonic-read contract end-to-end, or can a
   caller misread a stale-node `NOT_FOUND` as a real delete? (Semantics, not a JMM bug.)

---

## Phase 2 — Cross-examination

Two challenges from the lead. Verdicts with literal tags + file:line. I read peers'
files (`findings-consensus-correctness.md`, `findings-verification-evidence.md`) and the
cited code outside my Phase-1 scope (`RaftNode.java`, `ConfigdServer.java`,
`ConfigReadService.java`, `ReadIndexState.java`).

### CX-1 — ReadIndex TOCTOU / visibility on the Java path (consensus-correctness's challenge)

**Verdict: REFUTE the "stale value served" hazard. [VERIFIED-PASS] (the read path is
TOCTOU-safe by single-thread confinement + a CompletableFuture happens-before edge). One
[REFINE]: a tautological-but-harmless `role` re-check, and a real-but-benign "leadership
can lapse between confirm and the HTTP-side store read" window that cannot serve a
*stale* (backwards) value.**

I AGREE with consensus-correctness that `ReadFreshness`/`NoStaleLeaderServe` are vacuous
(`ReadIndexSpec.tla:237,251`, consequent `TRUE`) so linearizable-read safety rests on the
Java path. Tracing that path:

The entire ReadIndex decision is **tick-thread-confined**, not cross-thread:
- `ConfigdServer.java:453` wraps `readIndex()` → `whenReadReady()` → `isReadReady()` →
  `completeRead()` inside `readDispatchExecutor.execute(() -> tickExecutor.execute(...))`.
  All four run on the single tick thread.
- `role`/`leaderId` are written ONLY by the tick thread (`becomeFollower` `RaftNode.java:1050-1051`,
  `becomeLeader` `:1176-1177`, `becomeCandidate` `:1128-1129`) and are `volatile`
  (`:56-57`). The leadership re-check `isReadReady` `:421` (`role != LEADER`) reads `role`
  on the SAME tick thread that mutates it → **no TOCTOU within the confirmation**; the
  `volatile` is belt-and-suspenders for any non-tick reader of `leaderId`.
- `isReadReady` `:424` → `ReadIndexState.isReady` `ReadIndexState.java:92-97` returns true
  only when `leadershipConfirmed() && lastApplied >= readIndex`. Both the quorum-confirmed
  leadership flag and the applied-index gate are evaluated on the tick thread. `ReadIndexState`
  is documented + used single-threaded (`:18`, F-0010).

**The concrete interleaving the challenge asks about, and why it is safe:**
- t0 (tick): `readIndex()` records `readId` at `commitIndex=C` (`RaftNode.java:398`).
- t1 (tick): heartbeat quorum acks → `confirmAllLeadership()`; later `lastApplied>=C` →
  `whenReadReady` fires the callback, which calls `isReadReady` (re-reads `role==LEADER`),
  `completeRead`, and `resultFuture.complete(true)` (`ConfigdServer.java:464-467`).
- t2: HTTP thread's `resultFuture.get(150ms)` (`:474`) returns true. **CompletableFuture
  completion happens-before the return from `get`** → everything the tick thread did
  through t1 (including the apply that advanced the store snapshot to ≥ C) is visible to
  the HTTP thread.
- t3 (tick, concurrently): node receives a higher-term AppendEntries/Vote →
  `becomeFollower` `:1044` flips `role=FOLLOWER`, `leaderId=null`, `readIndexState.clear()`.
- t4: HTTP thread now runs `ConfigReadService.linearizableRead` line 75
  (`reader.get(key)` → `configStore.get` volatile snapshot read).

At t4 the node may no longer be leader (t3 happened in the window). **But the value
served cannot be stale-in-the-linearizability-sense:** the store is single-writer and
**monotonic** (every apply produces a higher-version immutable snapshot; versions never
go backwards — my Phase-1 R-2/safe-publication finding). The served snapshot is ≥ the
confirmed read index C, which by ReadIndex covers all writes committed at request
receipt. A *newer* committed write being reflected is permitted by linearizability; a
*backwards* value is impossible. So losing leadership in [confirm, serve) yields an "at
least as fresh as required" read, not a stale one. [VERIFIED-PASS] by code structure
(I did not build a multi-node race harness — no such test exists — so the *negative* is
a reasoned JMM/ordering argument, not a demonstrated run: the safety claim is
[VERIFIED-PASS] on structure, the absence of a triggering test is [EXISTS-UNTESTED]).

**[REFINE] on the re-check's value:** `isReadReady`'s `role != LEADER` guard `:421` runs
on the tick thread at the same instant as `confirmAllLeadership`, so within a single
tick-thread turn it is essentially re-asserting state that `readIndexState` already
gates; the *real* protection against a deposed leader serving is that `becomeFollower`
`clear()`s `readIndexState` `:1053` and fires/clears callbacks `:1058-1060`, so a stepped-
down node's pending read resolves `ready=false`. The `:421` guard is a correct cheap
backstop, not the load-bearing mechanism. The FIND-0002 comment `:418-420` slightly
overstates it as "the" TOCTOU closer.

**Net:** the Java ReadIndex path is concurrency-correct for the linearizable contract;
the vacuous TLA invariants don't undermine it. No [VERIFIED-FAIL].

### CX-2 — "Verified by JMH perfnorm, zero MONITOR_ENTER" (verification-evidence's challenge)

**Verdict: AGREE — the doc's "verified by JMH `-prof perfnorm`" provenance is FALSE.
[VERIFIED-FAIL] on the doc claim's evidentiary basis; the underlying zero-lock PROPERTY
is independently TRUE by source inspection ([VERIFIED-PASS], my Phase-1 grep).**

`docs/performance.md:53`: "Read path: ZERO lock acquisitions. **Verified by JMH
`-prof perfnorm` showing zero `MONITOR_ENTER` events.**" Also `:48` claims `jcstress`
"for concurrency correctness", `:49` async-profiler lock profiles, `:50`/`:349` perfnorm.

What actually exists (greps run this phase):
- **No jcstress** in any `pom.xml` (`grep -rilE 'jcstress|perfnorm' --include=pom.xml` →
  empty). Confirms my Phase-1 finding and verification-evidence's.
- **No committed perfnorm output / MONITOR_ENTER artifact / async-profiler lock profile.**
  The only files containing those tokens are **docs prose** (`file -b` → "UTF-8 text" for
  all six: performance.md, handoff.md, rewrite-plan.md, prod-audit-cluster-E.md,
  inventory.md, adr-0014) — none is captured tool output.
- **No runnable harness** invokes `-prof perfnorm` (`grep` over `perf/`, `ops/`, scripts →
  empty). `perf/results/` is only `jmh-...-PLACEHOLDER/README.md` + `smoke/result.txt`
  (YELLOW, "no workload wired") — matches verification-evidence finding #7.
- The same `performance.md` block `:60` admits the JCTools hand-off queues are
  "**planned, not yet integrated**" — the section mixes done and not-done as if measured.

So the zero-lock claim is **source-inspection asserted as measurement**. The *property*
holds (I proved it by grep: no lock/CAS/AtomicReference on either read path — Phase-1
bullet 1); the *"Verified by JMH perfnorm"* sentence describes a measurement that has no
committed artifact and no runnable harness in this tree. [VERIFIED-FAIL] on the doc's
"verified" wording; [VERIFIED-PASS] that reads are in fact lock-free.

### Phase-2 classification summary
- CX-1: REFUTE the stale-read hazard — read path is TOCTOU-safe [VERIFIED-PASS structure / EXISTS-UNTESTED negative]; REFINE the `:421` re-check's billing.
- CX-2: AGREE with verification-evidence — [VERIFIED-FAIL] doc provenance, [VERIFIED-PASS] underlying property.
