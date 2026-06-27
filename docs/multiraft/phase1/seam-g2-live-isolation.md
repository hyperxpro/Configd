# Seam G2 — live shared-node isolation sim (C3a / SF1) (design)

> Charter Step 7 / Seam G part 2. Prove, on the REAL `MultiRaftDriver` + `OwnerExecutorPool` (not the
> abstract V sim), that a fault in one group on a node does not corrupt or stall another group on the same
> node — and that the isolation check is genuinely NON-VACUOUS (a real coupling leak goes RED). FOUR-WAY.
> N>1 stays boot-refused until G3 is green.

## Why the existing proofs are not enough (the SF1 gap)

- The Phase-1 V machinery used **independent per-shard harnesses**, so cross-shard isolation was
  *structural* — no real coupling leak could be injected (`c3-multigroup-wiring.md` SF1).
- `OwnerIsolationMultiOwnerTest` (replication-engine) proves the **missed-hop** isolation class: a group's
  owner-only entry point run on the *wrong* owner trips `assertOwnerThread`. But `assertOwnerThread` is a
  thread-IDENTITY net — it **cannot** catch the **starvation** class: when two groups share an owner
  thread and one group's work *blocks* that thread, the sibling is starved with no thread violation at all.

The owner-thread model means co-owned groups (when `ownerPoolSize P < N`) share exactly ONE coupling
channel: the owner thread. So the load-bearing isolation questions at the shared-node fidelity are:
1. Does a **thread-blocking** fault on group A starve a co-owned sibling? (It must be *detectable*.)
2. Is that starvation **confined to the shared owner** (other owners keep running — the node doesn't
   globally stall)?
3. Does per-shard **safety** (monotone sequence, no cross-shard leak) survive the fault + contention?

## What G2 adds — `SharedNodeFaultIsolationLiveTest` (replication-engine)

N=4 groups on P=2 owners (`owner0={0,2}`, `owner1={1,3}` — genuine shared owners), each a real
single-node `RaftNode` with a `BlockableTrackingStateMachine` (records the per-shard applied sequence;
its mutating apply can be ARMED to block on a latch — the "stuck/slow apply" fault running on the owner
thread; `applyCommitted` is synchronous on the tick/owner thread, so a blocked apply blocks the owner).

### The per-group LIVENESS WITNESS (the new instrument)
`witnessProgresses(gid, budget)` submits a propose + repeated ticks for `gid` onto its owner
**fire-and-forget** (so a stuck owner cannot block the witness thread) and polls the shard's applied
count. A free owner ⇒ progress ⇒ GREEN; a stuck owner ⇒ the tasks queue and never run ⇒ no progress ⇒
RED. This is the instrument `assertOwnerThread` cannot provide.

### Test 1 — the coupling-leak RED (the SF1 mandate)
- **Baseline:** the witness is GREEN for all four groups (proves it is non-vacuous — it *can* go GREEN).
- **Inject:** arm group 0's apply to block, drive a mutating apply on owner0 (fire-and-forget) → owner0's
  single thread is STUCK inside group 0's apply.
- **RED:** group 2 (co-owned on owner0) is STARVED — `witnessProgresses(2)` returns false. The coupling
  leak is detected. (A vacuous witness — one that never goes GREEN — is ruled out by the baseline; a
  vacuous RED — one that never goes RED — would fail this assertion.)
- **Cross-owner GREEN:** groups 1 & 3 (owner1, a different thread) keep committing — the fault is
  **owner-confined**, not node-wide. This is the property that makes shared-node co-tenancy safe.
- **Recover:** release the latch → owner0 drains the queued work → group 0 AND the starved group 2 both
  resume (the stall was transient back-pressure, not corruption or deadlock).
- **Safety survived:** every shard's applied sequence is strictly monotone and never contains a foreign
  shard's command (no cross-shard leak).

### Test 2 — per-shard safety under shared-owner concurrency (S2/S4)
One producer per group proposes + ticks its OWN group on the group's owner, so co-owned groups genuinely
contend for one owner thread. Assert: no producer throws; each shard's applied sequence is strictly
monotone and contains ONLY its own shard's commands (isolation, no corruption); real progress (non-vacuous).

## Relationship to the missed-hop net
G2 is complementary to `OwnerIsolationMultiOwnerTest`: together they cover BOTH isolation failure classes
at N>1 — missed-hop (caught by `assertOwnerThread`, non-vacuously fired there) and starvation (caught by
the G2 liveness witness, non-vacuously RED here). G3 runs both in the integrated sweep.

## Notes / forward
- Rehoming is DORMANT in Phase 1; the single-writer/owner-thread coupling channel is the static
  `floorMod(gid, P)` owner. If rehoming activates, re-verify the owner-handoff quiesce (D-016; G1 red-team
  INFO) — a rehome-during-fault would add a second coupling channel.
- The fault is injected via a test state machine's blockable apply (the realistic "slow apply" vector);
  the driver, owner pool, tick, propose, and apply paths are the REAL production code.

## Verification (G2 DONE)
- `SharedNodeFaultIsolationLiveTest` 2/0, stable across repeated runs (no 2-vCPU timing flake — the
  coupling-leak ordering is FIFO-deterministic on the single-thread owner, not sleep-timed).
- Full `configd-replication-engine` suite green (no regression to `OwnerIsolationMultiOwnerTest`).
- Four-way: implementer + diff-review + independent re-run + red-team (attack: a vacuous RED, a flaky
  witness, a false isolation claim, a cross-shard leak under contention).
