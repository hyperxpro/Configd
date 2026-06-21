# M1 — the owner-isolation net catches a CROSS-GROUP race at N>1 (captured red→green)

> **Prime directive (session §1.2, §6.2):** the net must be re-proven NON-VACUOUS for the new N>1
> violation classes. Stage 1 proved the guard catches an *off-owner* access at N=1. M1 must prove it
> catches a *cross-group* access at N>1 — a group's entry point invoked on a **different group's real
> owner thread** — by neutering the guard, watching the proof go RED, reverting, and going green.

## The new violation class M1 introduces

At N=1 there is one owner thread; "off-owner" means "any foreign thread". At N>1 a new, more
dangerous class appears: **cross-group access on a legitimate owner thread.** owner[0] is a *real*
owner (it owns groups 0, 3, …); a naive **per-pool** guard ("am I on *some* owner thread?") would
wave through `group1.tick()` running on owner[0]. The actual guard is **per-node**
(`assertOwnerThread()` compares against *this node's* bound `ownerThread`), so it fires. This capture
proves the proof depends on that per-node property.

## What is proven (permanent)

`OwnerIsolationMultiOwnerTest` (`configd-replication-engine/src/test`):

| Test | Proves |
|---|---|
| `perOwnerTick_cleanRun_zeroFires_nonVacuousAcrossAllOwners` | The correct per-owner path is clean under real concurrency: 6 producers drive `tickOwner(i)` + `maybeCompactOwner(i)` + `propose`, each marshalled onto the group's owner, across **N=3** owners, while a foreign safe-rider reads the S-set (`role`/`leaderId`) + `monitorView()` off-owner → zero `raft_owner_thread` fires, zero in-node-invariant fires; and **every** owner makes real consensus progress (`commitIndex>0` on a group bound to each of owners 0/1/2 — non-vacuous on all three). |
| `crossGroupAccessOnARealOwnerTripsThePerNodeNet` | **Permanent, reproducible** proof of the new class: group 1's OWNER-ONLY entry points (`tick`/`propose`/`handleMessage`/`readIndex`/`metrics`/`triggerSnapshot`/`maybeCompact`), bound to owner[1], are invoked on **owner[0]'s thread** (a real owner of group 0) — each trips `raft_owner_thread`. A CONTROL runs the same entry points on group 1's *correct* owner and shows **zero** additional fires (the guard discriminates by node, not "always-on"). |

## The neuter→RED→revert→green capture ("a guard that stopped covering the new surface is the worst outcome")

The guard was neutered by an unconditional early return at the top of
`RaftNode.assertOwnerThread()` (`if ("".isEmpty()) return;` — always true, but not a compile-time
constant, so it compiles without an unreachable-code error and disables the guard completely). With the
guard disabled, the cross-group access no longer trips, and the proof went **RED**:

```
[ERROR] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0 <<< FAILURE! -- in OwnerIsolationMultiOwnerTest
[ERROR] OwnerIsolationMultiOwnerTest.crossGroupAccessOnARealOwnerTripsThePerNodeNet -- Time elapsed: 0.029 s <<< FAILURE!
org.opentest4j.AssertionFailedError: a group-1 entry point on owner[0] must trip the per-node owner
  net ==> Expected java.util.concurrent.ExecutionException to be thrown, but nothing was thrown.
[INFO] BUILD FAILURE
```

Note the clean-run half stayed **green** under the neuter — exactly as it should: the correct path has
no fires whether or not the guard is armed; only the *catch* depends on the guard. This isolates the
property being proven (the catch), not a coincidence.

The neuter was then **reverted** (`git diff` on `RaftNode.java` is empty — no residue; the shipped
guard has no bypass), and the proof is green again:

```
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- in OwnerIsolationMultiOwnerTest
[INFO] BUILD SUCCESS
```

Raw logs: `/tmp/m1-neuter-red.log` (red), `/tmp/m1-revert-green.log` (green).

## Why this complements the Stage-1 net (not a re-skin)

The Stage-1 proofs (`RaftNodeConcurrencyStressTest`, `OwnerNetCatchesOffOwnerInboundTest`,
`OwnerThreadSimIntegrationTest`) all catch *off-owner from a foreign (non-owner) thread* at N=1. M1's
proof is the first to fire the guard from **another group's bona-fide owner thread** — the only
violation shape that a per-pool (rather than per-node) guard would miss, and the shape that only exists
once N>1 and multiple owners run. Together they show the guard is non-vacuous for the off-owner AND the
cross-group classes.

## The production change M1 verifies

`ConfigdServer` schedules the consensus tick **per owner**: `for i in [0,N): ownerByIndex(i)
.scheduleAtFixedRate(() -> { driver.tickOwner(i); if (i==0) {H-3 scrape}; driver.maybeCompactOwner(i,…);
if (i==0) {co-tenant riders + ~10s snapshot-compact} })`. The owner[0] housekeeping is split around
`maybeCompactOwner` so the N=1 operation order (tick → scrape → compact → riders) is **order-exact** to
the deleted Stage-1B schedule. At N=1 the loop runs once and is behaviourally **exact** to the Stage-1B
single-owner schedule. The singleton housekeeping (H-3 scrape of group 0 + the co-tenant riders, which
do not touch any `RaftNode`) rides owner[0] only. Production stays single-group; the multi-group surface
is test-only until Phase 1 sharding.
