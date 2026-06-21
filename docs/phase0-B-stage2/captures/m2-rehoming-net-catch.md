# M2 — the net catches the REHOMING-RACE class + clean handoff is non-vacuous (captured red→green)

> **Prime directive (session §1.2, §6.2):** the net must be re-proven NON-VACUOUS for each new N>1
> violation class. M1 proved the CROSS-GROUP class. M2 introduces the **rehoming-race** class — a group
> entry point on the LOSING owner after handoff, or on the GAINING owner before adopt — and proves the
> net catches it (neuter→RED→revert→green), while the clean handoff keeps committing on the new owner.

## The mechanism under test (M2a — additive, dormant in production)

`MultiRaftDriver.rehomeGroup(g, B)` moves a group from owner A to owner B via quiesce→publish→adopt
(docs/phase0-B-stage2/m2-rehoming-handoff-design.md):
- the DYNAMIC `groupOwner` map + a `migrating` set replace the static `floorMod`-only mapping;
- `RaftNode.ownerThread` is RE-BINDABLE — `beginHandoff()` (losing owner) → the **HANDOFF sentinel**
  (a never-started Thread that equals no running thread, so EVERY guarded entry point fires during the
  window), `adoptOwnerThread()` (gaining owner) → the new owner;
- marshalled work (inbound/propose) uses **check-and-bounce**: it touches the node only when the group
  is settled and this thread owns it, else re-dispatches (no loss, no misroute, no fire).

Production stays single-group and never calls `rehomeGroup` (the mechanism is dormant until a Phase-1
placement policy); this surface is test-only. At N=1/no-rehome the mechanism is INERT — the legacy
`MultiRaftDriverTest`, the M1 `OwnerIsolationMultiOwnerTest`, the macro `RaftNodeConcurrencyStressTest`,
and the full `configd-server` suite stay green unchanged.

## What is proven (permanent) — `RehomingHandoffTest` (configd-replication-engine)

| Test | Proves |
|---|---|
| `cleanRehome_preservesState_keepsCommitting_zeroFires` | A group rehomed owner0→owner1 stays LEADER (state preserved, no torn state) and keeps COMMITTING on the new owner (commitIndex grows past the pre-rehome baseline — **non-vacuous, on owner1's epoch**); zero `raft_owner_thread` fires; a stale message dispatched to the OLD owner BOUNCES to the new owner without firing. |
| `accessOnLosingOwnerAfterHandoff_trips` | Touching the group on the LOSING owner after `beginHandoff()` trips `raft_owner_thread` (HANDOFF sentinel covers the window). |
| `accessOnGainingOwnerBeforeAdopt_trips` | Touching it on the GAINING owner BEFORE `adoptOwnerThread()` trips `raft_owner_thread`. |
| `afterRehome_oldOwnerLockedOut_newOwnerOwns` | **NO DOUBLE-OWNERSHIP (unit level):** after the handoff the OLD owner is locked out (trips), while the NEW owner does not — exactly one thread owns the group. |
| `adoptOnNonMigratingNode_trips` | Adopting a node not mid-handoff trips `raft_owner_adopt` (double-adopt / wrong-state guard). |

## The neuter→RED→revert→green capture (rehoming-race non-vacuity)

`assertOwnerThread()` was neutered (unconditional early return). The three rehoming-race-catch tests
went **RED** — exactly the ones whose proof depends on the guard firing — while the clean-handoff test
and the (separately-guarded) adopt-guard test stayed green:

```
[ERROR] Tests run: 5, Failures: 3, Errors: 0 <<< FAILURE! -- in RehomingHandoffTest
[ERROR]   RehomingHandoffTest.afterRehome_oldOwnerLockedOut_newOwnerOwns <<< FAILURE!
[ERROR]   RehomingHandoffTest.accessOnLosingOwnerAfterHandoff_trips <<< FAILURE!
[ERROR]   RehomingHandoffTest.accessOnGainingOwnerBeforeAdopt_trips <<< FAILURE!
[INFO] BUILD FAILURE
```

The neuter was reverted (RaftNode restored to its M2a methods, no neuter residue), and all five pass:

```
[INFO] Tests run: 5, Failures: 0, Errors: 0 -- in RehomingHandoffTest
[INFO] BUILD SUCCESS
```

Raw logs: `/tmp/m2-neuter-red.log` (red), `/tmp/m2-restore-green.log` (green). The clean-handoff half
stayed green under the neuter (as it should — the correct path has no fires either way); only the
*catch* depends on the guard.

> **Process note:** reverting the neuter via `git checkout -- RaftNode.java` overshot to HEAD and
> discarded the uncommitted M2a methods (the file had other uncommitted work, unlike M1). The methods
> were re-applied by Edit. Lesson: revert a temporary neuter with an Edit (remove only that line) when
> the file carries other uncommitted changes — never `git checkout` the whole file.

## M2a vs M2b (what this capture covers, what remains)

- **M2a (this capture):** the rehoming MECHANISM + the unit/macro net-catch of the rehoming-race class
  + no-double-ownership at the unit/macro level + inert-at-N=1. Four-way verified.
- **M2b (remaining for H-4 → CLOSED):** the JMM-level no-double-ownership (`configd-jcstress`
  `RehomingDoubleOwnershipTest` + a forbidden-hitting control), and the S2–S4 invariant surface re-run
  WITH rehoming injected during the sweep (groups moving under adversarial schedules). Only after M2b
  is the threading-contract H-4 marked CLOSED.
