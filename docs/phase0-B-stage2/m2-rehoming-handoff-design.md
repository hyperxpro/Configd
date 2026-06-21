# M2 — H-4 co-tenant rehoming: the group→owner handoff protocol (DESIGN, contract-first)

> **Status:** DESIGN (written before the code, per session §8.3). The build is the irreversible part —
> see the scope flag below before building. **Scope:** D-016 — Stage 2 builds the rehoming MECHANISM,
> kept DORMANT in production (single-group, static `floorMod`); the placement POLICY (when/where to
> rehome) is Phase 1. This design supersedes the threading-contract §2 "static v1" note FOR THE
> MECHANISM only; live use stays v2-deferred.

> **Scope flag (retroactive veto, D-016):** if the operator intended group-rehoming to remain fully
> v2 / out of Stage 2, veto D-016 before building. The contract's DoD ("rehoming protocol designed +
> H-4 CLOSED") and the brief §4-M2 direct this build; this doc is the design it asks for.

---

## 1. The hazard (the Stage-2 analogue of H-3)

At N>1 a group `g` may need to MOVE from owner thread A (losing) to owner thread B (gaining) — for
balance and for future shard placement. The move is a cross-thread handoff of **unsynchronised**
`RaftNode` state. During the handoff there is a moment where ownership is ambiguous; a tick, message,
propose, or commit-callback arriving then must NOT (a) execute on the wrong owner, (b) be lost,
(c) be double-applied, and there must be NO window where two threads both believe they own `g`
(double-ownership). The N=1/M1 net never exercised this — `ownerThread` was *bound once, never
reassigned*. M2 makes it re-bindable and proves the handoff safe.

## 2. What changes from the M1 (static) model

| Concern | M1 (static) | M2 (rehoming-capable) |
|---|---|---|
| Ownership mapping | `ownerExecutor(gid) = pool[floorMod(gid,N)]` (pure function) | `groupOwner: ConcurrentHashMap<Integer,Integer>` seeded to `floorMod(gid,N)` at `addGroup`; the **authority** for routing. `ownerExecutor(gid)`/`tickOwner(i)` consult it. |
| `RaftNode.ownerThread` | bound ONCE (`bindOwnerThread`), never reassigned (v1) | RE-BINDABLE: `beginHandoff()` → `HANDOFF` sentinel; `adoptOwnerThread()` → new owner thread. Still one volatile field; still the net's check. |
| Tick eligibility | `groupOwner[g]==i` | `groupOwner[g]==i && g ∉ migrating` |
| Marshalled work (inbound/propose/read/flush) | submit to `ownerExecutor(g)`; run inline | submit to `ownerExecutor(g)`; **check-and-bounce-and-wait** at execution (below) |

`migrating: Set<Integer>` (concurrent) marks groups mid-handoff. The `HANDOFF` sentinel is a
`Thread` object that is never started, so it equals no running thread → while `ownerThread==HANDOFF`,
**every** guarded entry point fires for any real caller (the window is fully net-covered).

## 3. The handoff protocol — quiesce → publish → adopt

`rehomeGroup(g, B)` (losing owner A = `groupOwner[g]`), orchestrated by a coordinator using executor
`.get()` barriers for happens-before. **All `groupOwner`/`ownerThread` writes for `g` happen on a
single thread at a time, ordered by the barriers.**

```
Precondition: groupOwner[g]==A, node.ownerThread==A's thread, g ∉ migrating.

1. COORDINATOR:   migrating.add(g)
                  // tickOwner(A)/tickOwner(B) now both SKIP g (gated by !migrating).

2. ON A (await):  // single-thread ⇒ serialized with any in-flight A-work for g
     node.flushDurable()/quiesce      // B will adopt a clean, durable state  [no torn state]
     groupOwner[g] = B                 // PUBLISH routing flip — on A's thread, BEFORE detach
     node.beginHandoff()               // ownerThread = HANDOFF (volatile)     [A detaches]
   // Ordering on A's thread: flip THEN detach. A stale marshalled task on A that runs
   //   - before the flip: groupOwner==A, ownerThread==A  → touches node on A (A still owns) OK
   //   - after the flip:  groupOwner==B                  → check-and-bounce to B (no touch)

3. ON B (await):  node.adoptOwnerThread()   // ownerThread = B's thread (volatile)  [B adopts]
   // adopt uses the UNGUARDED bind primitive (like bindOwnerThread), so it does not self-fire.

4. COORDINATOR:   migrating.remove(g)
                  // tickOwner(B) now ticks g; marshalled work on B proceeds (groupOwner==B,
                  //   ownerThread==B ⇒ net OK). Handoff complete.
```

Happens-before chain (no torn state): all of A's writes to `g`'s `RaftNode` → A-task completes →
`.get()` returns to coordinator → coordinator submits B-task → B-task starts → B's `adoptOwnerThread`
and subsequent ticks. The executor barriers give B a full view of A's final state.

## 4. Marshalled work: check-and-bounce-and-wait (closes the routing races)

Every marshalled `RaftNode` task for `g` (inbound `routeMessage`, `propose`, read double-hop, flush),
when it RUNS on owner thread X, executes this guard BEFORE touching the node:

```
if (migrating.contains(g))            { resubmit to ownerExecutor(g); return; }  // handoff in flight → wait
if (groupOwner[g] != myOwnerIndex(X)) { resubmit to ownerExecutor(g); return; }  // routing moved → bounce
node.<entry>(...);                                                                // safe: settled + I own it
```

This makes the only RaftNode touch happen when `!migrating && groupOwner[g]==X`, which (by the
protocol ordering) implies `ownerThread==X` → the net stays silent on the correct path and **no
message is lost (re-queued, never dropped) or misrouted (only the current owner touches the node)**.
Note: `routeMessage` today silently drops for an absent group; during migration the group EXISTS, so
the path is re-queue, not drop. Re-queue is bounded (the handoff is a few executor hops); a small
backoff avoids busy-spin (acceptable: dormant in prod, exercised by tests).

## 5. Net extension — catch the rehoming-race (neuter→RED→revert→green, like M1)

The `HANDOFF` sentinel makes the existing `assertOwnerThread()` catch the new class for free:
- **access on the LOSING owner A after handoff** → `ownerThread∈{HANDOFF,B} ≠ A` → fires.
- **access on the GAINING owner B before adopt** → `ownerThread==HANDOFF ≠ B` → fires.

New permanent proof `RehomingNetCatchesRaceTest` (replication-engine), two halves like M1:
- clean run: rehome `g` A→B under concurrent tick+propose+inbound (with check-and-bounce); zero fires;
  non-vacuous (g keeps committing — commitIndex grows across the rehome, on BOTH A's and B's epochs).
- injected: (i) call a guarded entry point on A after `beginHandoff()` → fires; (ii) on B before
  `adoptOwnerThread()` → fires. Neuter the guard (or skip `beginHandoff`) → the injected halves go RED;
  revert → green. Captured under `docs/phase0-B-stage2/captures/`.

## 6. No double-ownership (the JMM question → jcstress)

Claim: there is never a moment where two distinct real threads both pass `assertOwnerThread()` for the
same `g`. `ownerThread` is one volatile field; its write sequence is `A → HANDOFF → B`, each write on a
single thread, ordered by the `.get()` barriers (A's `beginHandoff` happens-before B's `adoptOwnerThread`).
So at any instant at most one real thread equals `ownerThread`.

`configd-jcstress` `RehomingDoubleOwnershipTest`:
- `@State`: a node, threads A and B racing `assertOwnerThread`-style reads around `beginHandoff`/`adopt`.
- FORBIDDEN outcome: both A and B observe themselves as owner (double-ownership). Must be unreachable
  with the volatile field + barrier ordering.
- CONTROL (forbidden-hitting, excluded from the gate like `KnownRacyCounter`): a naive non-volatile or
  un-ordered re-bind DOES hit double-ownership — proving the test is non-vacuous and the volatile+barrier
  discipline is load-bearing.

## 7. Build staging (each four-way verified; STOP CLEAN at any sub-seam)

1. **M2a (additive, dormant):** `groupOwner` map + `migrating` set + check-and-bounce in the 4 marshalled
   paths + `beginHandoff`/`adoptOwnerThread`/`HANDOFF` sentinel on `RaftNode` + `rehomeGroup()` on the
   driver. Production never calls `rehomeGroup` (static single-group) ⇒ behaviourally identical to M1 at
   N=1 (groupOwner[0]≡0, never migrating). Net stays green; S2–S4 re-closed. Checkpoint.
2. **M2b (the proofs):** `RehomingNetCatchesRaceTest` (+ neuter→RED capture), `RehomingDoubleOwnershipTest`
   (jcstress, + forbidden control), S2–S4 surface re-run WITH rehoming injected (a harness rehomes groups
   under adversarial schedules during the sweep). Four-way (impl + diff-review + independent re-run +
   red-team). Update threading-contract: rehoming protocol as-built + **H-4 → CLOSED**. Checkpoint.

## 8. Risks / open questions for the build

- **Re-queue starvation / ordering:** confirm re-queue preserves per-group FIFO enough for Raft (Raft
  tolerates message reorder/loss, so bounce-reorder is safe; proposes returning `ProposeOutcome` across
  a bounce need the H-1 treatment — capture (index,term) only after the node is touched, or reject with
  retry while migrating). Decide: reject-while-migrating (simplest, caller retries) vs. re-queue propose.
- **`adoptOwnerThread` vs. the "bound once" net invariant:** the jcstress `RaftOwnerThreadGuardTest`
  asserts no-false-negative once in service; re-binding must not open a false-negative — adopt is a
  volatile write on B ordered after A's detach, so a post-adopt off-owner caller still observes B. Re-run
  that jcstress under a rehome.
- **`monitorView()` across a rehome:** the H-3 snapshot is owner-published at end of tick; after rehome B
  publishes it. A scrape mid-rehome reads the last-published (≤1 tick stale) view — still safe (S-set).
- **Driver `groupOwner` vs. `addGroup`/`removeGroup` (H-5):** keep all three consistent under concurrent
  iteration (CHM weakly-consistent iteration already tolerated in M1).
- This mechanism stays **dormant in production** (no caller) until a Phase-1 placement policy wires it.
