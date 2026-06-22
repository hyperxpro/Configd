# M2b S3 — the rehoming-injected S2–S4 invariant surface (real-executor) + the deterministic sweep intact

> **Prime directive (session §5-S3):** re-run the S2–S4 invariant surface WITH rehoming injected — groups
> moving between owners under adversarial schedules WHILE the multi-owner workload runs. This is where a
> subtle handoff bug the unit/jcstress proofs missed would surface. Any failing seed = P0.

S3 has two parts: **(a)** a NEW real-executor stress test that injects rehoming under a concurrent
multi-owner workload (the deterministic single-drive-thread sim cannot model multi-owner concurrency —
handoff §M2b item 2), and **(b)** the existing deterministic S2–S4 sim re-run UNCHANGED, to confirm S1's
consensus-core changes (the `flushDurable` guard + `isDetached`) regressed nothing.

## (a) `RehomingInjectedSweepTest` (configd-replication-engine) — NON-VACUOUS, ZERO fires

N=3 owners, 6 single-node groups. Concurrent: 6 producers drive per-owner `tickOwner(i)` (owner-indexed,
self-filtering) + `propose` + marshalled inbound `routeMessage`, each onto the group's CURRENT owner; a
REHOMING INJECTOR moves random groups to random target owners continuously under adversarial timing; a
safe-rider reads the S-set + `monitorView()` off-owner. Each group is wired with the PRODUCTION retargeted
flush (`driver.dispatchFlush`). A `CountingThrowingChecker` throws on ANY invariant (the 9 RaftNode checks)
and counts `raft_owner_thread` fires. The handoff uses the M2b uninterruptible barriers, so the final
`shutdownNow()` of the injector cannot wedge a group (a live S1 Finding-1 validation).

Invariants asserted (hold under ALL interleavings if the mechanism is correct → robust, not flaky):
owner isolation across rehomes (zero fires — incl. fire-and-forget inbound/flush paths, caught via the
`ownerFires` counter since `ScheduledThreadPoolExecutor` swallows the throw there), in-node safety (the
throwing checker), liveness/non-vacuity (every group's commitIndex grows past baseline **PRE-DRAIN** — a
self-sufficient assertion that consensus progressed DURING the concurrent rehoming phase, not just in the
drain), no deadlock/livelock (completes in time).

**Seed-sweep results** (`-Dconfigd.sweep.count`, `-Dconfigd.sweep.iters`; seed drives the rehome sequence,
real-executor scheduling adds interleaving diversity):

```
10 seeds, iters=800:   rehomes 862-9781/seed,  accepted ~3900-4000,  commitGrowth min 614-660/group,  ownerFires=0 (all)
12 seeds, iters=1500:  rehomes 1794-16581/seed, accepted ~7250-7460, commitGrowth min 1172-1230/group, ownerFires=0 (all)
```

22 seeds, **tens of thousands of injected rehomes** under heavy concurrent load, every group committing
**600–1,230+ entries ACROSS the rehomes**, and **`ownerFires=0` on EVERY seed** — owner isolation holds
through every handoff (no entry point ran off its current owner ⇒ no double-ownership manifested), commits
keep flowing (check-and-bounce re-queues, never drops ⇒ no lost message), groups stay leaders + commit
durably (quiesce + barriers ⇒ no torn state), and nothing wedged or deadlocked. **No failing seed = no P0.**

CI runs 1 sweep (fast smoke); the S3 evidence is the multi-seed run above. The vacuity guards
(`accepted>0`, `rehomes>0`, commit-growth per group) fail loudly if the sweep ever goes shallow.

**Test-the-tester (the sweep CATCHES a real owner-isolation violation under rehoming):** neuter the
`migrating` gate in `tickOwner` (drop `&& !migrating.contains(g)`) so a group mid-handoff gets ticked
off-owner → the sweep goes **RED** on the first hit:

```
AssertionError: rehoming-injected sweep violated an invariant/tripwire (first: raft_owner_thread:
RaftNode entry off owner thread: bound to 'raft-owner-handoff-sentinel' but called from
'configd-raft-owner-0' — R-01' single-owner invariant violated)
```

Restore → green. So `ownerFires=0` is a load-bearing assertion, not vacuous.

## (b) The deterministic S2–S4 sim — UNCHANGED, no regression

Re-ran the existing deterministic multi-node surface (single-drive-thread; S1's core changes are inert
there — flush runs on the drive thread, no rehoming):

```
SeedSweepTest:                20001 / 0   (the full 20,001-seed invariant sweep — 61s)
AdversarialSimTest:               4 / 0   (1 skipped)
OwnerThreadSimIntegrationTest:    2 / 0
SeedSweepTestTheTesterTest:       1 / 0   (1 skipped)
=> 20008 run, 0 failures.
```

The cross-node invariants (linearizability, version monotonicity, no-stale-overwrite, durable-prefix,
leader completeness, log matching, state-machine safety) + the owner-thread net in the sim hold UNCHANGED
with S1's changes. consensus-core 342/0 + server 165/0 (S1) complete the regression picture.

## Four-way verification (independent)

An independent four-way verifier reproduced the sweep at scale (**54 distinct seeds** across three seed
bases, iters up to 1500 — 0 fires on every seed, rehomes up to 27,489/seed, every group non-vacuous) and
the deterministic sim (SeedSweepTest 20,001/0), and confirmed it **SOUND + NON-VACUOUS, no gap**:
- **Test-the-tester: the committed one + 3 MORE, ALL caught** — drop the routeMessage bounce (RED via the
  counter at 408 fires), wrong-thread adopt (RED), drop the flush bounce (RED via the counter at 8). No
  class of real off-owner access stays GREEN.
- **Swallowed-fire RESOLVED:** the fire-and-forget `execute()` paths (inbound/flush) have their thrown
  `AssertionError` swallowed by `ScheduledThreadPoolExecutor`, but `ownerFires.incrementAndGet()` runs
  BEFORE the throw, so the final `assertEquals(0, ownerFires)` + the post-`.get()`-joined-drain re-assert
  catch them — proven end-to-end by the two counter-only RED replays. The counter is load-bearing there.
- **Overlap RESOLVED:** ~100% of rehomes occur while ≥1 producer is live (densely interleaved, not a quiet
  period). **Faithfulness CONFIRMED** (owner-indexed tick; current-owner-marshalled propose/inbound/flush;
  non-firing safe-rider reads).
- **Liveness hardening (applied):** the verifier noted the post-drain `now > baseline` could technically
  pass on drain-only growth. Tightened to a SELF-SUFFICIENT PRE-DRAIN assertion (every group commits past
  baseline DURING the concurrent phase, before the drain). Validated: preDrainGrowth min 606–643/group.

## What this closes

H-4's three failure modes are now demonstrated to hold under the live fault matrix WITH rehoming injected:
no double-ownership (zero fires across tens of thousands of rehomes + the JMM jcstress proof S2), no
lost/misrouted message (commits keep flowing; check-and-bounce), no torn state (durable commit growth
across handoffs). With S2 (the JMM proof) this is the full evidence for H-4 → CLOSED, recorded at S4.
