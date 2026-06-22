# M2b S1 — deferred handoff sub-mechanisms + the net stays non-vacuous (incl. a NEW off-owner-flush class)

> **Prime directive (session §2.3, §5-S1):** the deferred sub-mechanisms land additively (dormant in
> prod / inert at N=1, D-016), and the net is re-proven NON-VACUOUS after they land — neuter→RED→
> revert→green. S1 also closes a real net GAP (an off-owner flush was previously SILENT) by guarding
> `flushDurable()`, so it adds a demonstrable catch, not just code.

## What S1 built (additive, dormant)

| Sub-mechanism | Where | What |
|---|---|---|
| **quiesce** | `RaftNode.quiesceForHandoff()` + `rehomeGroup` step 2 | Force-syncs buffered entries on the LOSING owner BEFORE publish+detach, so the gaining owner adopts a clean, durable state (no torn / half-buffered log across the handoff). |
| **FlushScheduler retarget** | `RaftNode.flushDurable()` guarded + `MultiRaftDriver.dispatchFlush`/`runFlushOnCurrentOwner` + `ConfigdServer` closure | The prod flush closure used to CAPTURE `defaultGroupOwner` once → after a rehome it would dispatch the flush onto the OLD owner: an off-owner touch of the unsynchronised log that, because `flushDurable` was unguarded, was **SILENT**. Now the flush is dispatched through the driver to the group's CURRENT owner (rehoming-aware, with the same check-and-bounce as `routeMessage`), and `flushDurable` is owner-guarded so any residual off-owner dispatch FIRES. |
| **abortHandoff** | `MultiRaftDriver.rehomeGroup` catch + `abortHandoff` | If the gaining owner cannot adopt after the losing owner detached, the handoff rolls back to the losing owner (routing restored to the EXACT pre-rehome state; the losing owner re-adopts from the HANDOFF sentinel) — no torn state, no lost message. Only if the losing owner is ALSO unavailable does the group stay loudly wedged on HANDOFF (never silently mis-owned). |

Production stays single-group and never rehomes; this surface is test-only. At N=1 / no-rehome the
mechanism is INERT (`dispatchFlush` → current owner == static owner; `migrating` always empty; no override).

## Permanent proofs — `RehomingSubMechanismsTest` (configd-replication-engine, 4 tests)

| Test | Proves |
|---|---|
| `quiesce_flushesBufferedEntriesDurableAcrossRehome` | With a DEFERRED flush scheduler (parked, never auto-run), a freshly-proposed entry stays buffered and CANNOT commit (durableIndex gate). After `rehomeGroup`, commitIndex grew past the baseline **while the parked flush is still un-run** — proving the rehome's quiesce step DIRECTLY force-synced the buffered entry across the handoff. Zero fires. |
| `flushRetarget_dispatchAfterRehome_runsOnNewOwner_noFire` | After a rehome 0→1, `driver.dispatchFlush(0, …)` re-resolves and runs the flush on the CURRENT owner (owner1) with zero fires. |
| `flushRetarget_offOwnerFlush_firesGuard` | The HAZARD the retarget closes: the captured `node::flushDurable` run on the OLD owner (owner0) after a rehome trips `raft_owner_thread` (the guard added to `flushDurable`); the same task on the current owner (owner1) does not fire. |
| `abortHandoff_gainingOwnerUnavailable_rollsBackToLosingOwner` | Rehoming to an owner whose executor was `shutdownNow()` throws (after rollback); routing is restored to owner0 (no leaked override), the group is still LEADER, keeps committing on owner0, and zero fires. |

## The neuter→RED→revert→green capture (net non-vacuous AFTER S1)

`assertOwnerThread()` neutered with `if ("".isEmpty()) return;` (non-const ⇒ compiles, disables the
guard). The catch tests went **RED**, the behaviour/clean tests stayed green:

```
[ERROR] Tests run: 7, Failures: 4 -- in RehomingHandoffTest
[ERROR]   RehomingHandoffTest.afterRehome_oldOwnerLockedOut_newOwnerOwns          <<< FAILURE!
[ERROR]   RehomingHandoffTest.missedHopOnNeverRehomedGroup_stillFiresNet_withPoolSet <<< FAILURE!   (M2a Defect-1 regression — still fires)
[ERROR]   RehomingHandoffTest.accessOnLosingOwnerAfterHandoff_trips               <<< FAILURE!
[ERROR]   RehomingHandoffTest.accessOnGainingOwnerBeforeAdopt_trips               <<< FAILURE!
[ERROR] Tests run: 4, Failures: 1 -- in RehomingSubMechanismsTest
[ERROR]   RehomingSubMechanismsTest.flushRetarget_offOwnerFlush_firesGuard        <<< FAILURE!   (NEW off-owner-flush class)
[INFO] BUILD FAILURE
```

Neuter reverted (no residue — `grep TEMP-NEUTER` empty), all green again:

```
[INFO] Tests run: 7, Failures: 0 -- in RehomingHandoffTest
[INFO] Tests run: 4, Failures: 0 -- in RehomingSubMechanismsTest
[INFO] BUILD SUCCESS
```

The clean/quiesce/dispatch/abort tests stayed green under the neuter (they assert behaviour or the
ABSENCE of a fire, not a catch) — isolating the property each catch test proves.

## flushDurable-guard safety (no false positives)

Guarding `flushDurable()` could in principle fire on a legitimate path. It does not: every legacy
single-thread test leaves `ownerThread==null` (the guard is inert — e.g. `GroupCommitDurabilityTest`
pumps the parked flush on the test thread and stays green), and every bound-owner path runs the flush
on the owner. Evidence: **configd-consensus-core 342/0/0** and **configd-replication-engine 134/0/0**
green with the guard in place.

## M2a re-confirmed intact

`RehomingHandoffTest` 7/7 green UNCHANGED after S1 — including `missedHopOnNeverRehomedGroup_stillFiresNet_withPoolSet`
(Defect-1) and `removeGroup_clearsRehomingState` (Defect-2). The S1 additions did not regress the M2a
red-team fixes (session §7 rule 4).

## Four-way: the red-team broke it (TWO findings) → fixed red/green

The first S1 cut (`3a44cf0`) was four-way verified: implementer build + an independent line-by-line
diff-review (SOUND) + an independent re-run (consensus-core 342/0, replication-engine 134/0, server
165/0; neuter→RED→green replayed) + an adversarial red-team. The red-team **broke it** — two proven
defects, both confined to the dormant rehoming path, **NO P0/safety breach** (every wedged path stays
LOUD: no double-ownership, no lost/torn committed entry, no silent off-owner touch):

| Finding | Sev | Defect | Fix |
|---|---|---|---|
| **1** | P1 | `Future.get()` interrupt does NOT cancel the submitted owner task; the pre-fix interruptible barrier abandoned the wait while the queued publish/detach task ran later → group wedged on HANDOFF with both owners alive (`detached` tracked coordinator control-flow, not node state). | `runOnOwnerAwait` is **UNINTERRUPTIBLE** — keeps waiting for the bounded owner task, then re-asserts the interrupt; the handoff completes (or rolls back) atomically. `rehomeGroup`/`abortHandoff` drop `throws InterruptedException`. |
| **2** | P2 | A dispatched flush on a HANDOFF-wedged group (`groupOwner` override + `boundToAnotherThread`, never `migrating`) re-dispatched FOREVER (no real owner ever runs it) — the "loudly wedged" state was a SILENT livelock for the flush path. | `runFlushOnCurrentOwner` gates the bounce on `&& !node.isDetached()` (new non-firing `RaftNode.isDetached()`): a wedged node falls through so `flushDurable`'s guard FIRES once (loud), no spin. |

Both fixed and proven red/green with adopted (red-team-authored, adapted) regressions in
`RehomingRobustnessTest` (3):
- `interruptDuringHandoff_completesAtomically_reassertsInterrupt_notWedged` — neuter `runOnOwnerAwait`
  back to abandon-on-interrupt → **RED**; restore → green.
- `flushDispatch_onWedgedGroup_doesNotLivelock` — drop the `!isDetached` gate → **RED** (flush never
  lands / livelock); restore → green.
- `quiesceThrowsMidRehome_leavesGroupCleanOnLosingOwner` — confirmation (green pre- and post-fix).

Post-fix green: consensus-core **342/0**, replication-engine **0 fail**, server **165/0**. Net
non-vacuity and the M2a missed-hop detector unaffected (the fixes touch `runOnOwnerAwait`/
`runFlushOnCurrentOwner`/`isDetached`, never `assertOwnerThread`).

## Second-agent replay → one SHOULD-FIX (routeMessage symmetric livelock), folded in

An independent second-agent **replayed** `a78e662`: reproduced green (consensus-core 342/0,
replication-engine 137/0, server 165/0), replayed BOTH fixes red/green (neuter→RED→restore→green) and
the owner-net non-vacuity (4 catch tests RED on a neutered guard), and adversarially scrutinised the
fixes — **SOUND, no new P0/safety defect**: the uninterruptible await honours the interrupt exactly once
and cannot deadlock (owner steps are bounded + lock-free); the `isDetached` gate does not livelock a
wedged group nor wrongly drop a legitimate settled-rehome stale bounce.

It found ONE pre-existing **SHOULD-FIX (P2)**: `routeMessage`'s bounce had the SAME wedged-group
re-dispatch pathology as Finding 2 (the fix had been applied to `runFlushOnCurrentOwner` but not the
symmetric inbound path) — a wedged group spins the owner thread (~745ms CPU / 800ms, message never
delivered; liveness-only, no safety breach). **Folded in** (commit follows): the identical
`&& !node.isDetached()` gate on `routeMessage`, so a wedged-group inbound message falls through to
`handleMessage` and the net FIRES once (loud) — coherent with the flush path. Regression
`RehomingRobustnessTest#routeMessage_onWedgedGroup_firesOnceDoesNotLivelock` (neuter→RED→restore→green);
`propose` needs no change (it returns NOT_LEADER immediately — clean failure, no spin). Post-fix:
consensus-core 342/0, replication-engine **138/0** (RehomingRobustnessTest 4/4), RehomingHandoffTest 7/7.

S1 is then a clean, fully-four-way-verified seam: mechanism + the deferred sub-mechanisms + every
red-team / replay finding closed red/green, net non-vacuous across all classes, prod paths inert at N=1.
