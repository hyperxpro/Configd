# A.2 — The concurrent stress harness catches an injected race (captured red)

> **Hard rule (session §4.2, §8.1):** the concurrent stress harness must be *proven* to catch a
> violation — by injecting one and capturing the red — BEFORE any Workstream B re-threading is
> blessed. A harness not shown to catch a real race is unproven (the S1 vacuous-tests lesson).

## What is proven

`RaftNodeConcurrencyStressTest` (in `configd-consensus-core/src/test`):

| Test | Proves |
|---|---|
| `concurrentOwnerMarshalledAccessStaysGreen` | The correctly-marshalled path is clean: 6 producers marshal the guarded O entry points onto the owner executor + a foreign thread reads the volatile S fields off-owner → no invariant/tripwire fires; proposals commit (non-vacuous). |
| `offOwnerAccessTripsTheGuard_provesHarnessCatchesARace` | **Permanent, reproducible** proof: each OWNER-ONLY entry point invoked directly off-owner trips `raft_owner_thread` before touching state. |

## The injected-race capture ("test the tester")

To prove the *stress workload itself* (not just a unit assertion) catches a dropped hop, one
marshalling hop was deliberately removed inside `concurrentOwnerMarshalledAccessStaysGreen` — a
single `node.metrics();` called **directly from a producer thread** instead of via `owner.submit`,
simulating a future re-threading that forgets to marshal. The harness went **red**:

```
[ERROR] RaftNodeConcurrencyStressTest.concurrentOwnerMarshalledAccessStaysGreen <<< FAILURE!
java.lang.AssertionError: marshalled concurrent access violated an invariant or the owner-thread
  guard: raft_owner_thread: RaftNode entry off owner thread: bound to 'raft-owner' (id=26) but
  called from 'pool-2-thread-2' (id=28) — R-01' single-owner invariant violated
Caused by: java.lang.AssertionError: INVARIANT 'raft_owner_thread' violated: ...
[ERROR] Tests run: 1, Failures: 1, Errors: 0
BUILD FAILURE
```

The tripwire identified the exact off-owner thread (`pool-2-thread-2`) violating the bound owner
(`raft-owner`). The injection was then **reverted**; both tests pass green again (re-verified).

Raw red log: `/tmp/harness-injected-red.log`.

## Residual gaps (adversarial second-agent review — tracked)

An independent adversarial review re-proved the catch with a *different* injection (`readIndex`
off-owner → red), confirmed the tripwire is production-inert (`bindOwnerThread` is called nowhere in
production) and the tests non-vacuous (338-test regression green). It surfaced:

- **H1 (closed):** the off-owner fire-test now covers ALL 7 guarded entry points (added
  `handleMessage` + `whenCommitOutcome`) — each guard is proven to *fire*, not merely to be present.
- **H2 (CLOSED — Workstream A closeout):** the documented "tick-thread-only" mutators are now
  **guarded** — `transferLeadership`, `triggerSnapshot`, `isReadReady`, `completeRead`,
  `whenReadReady`, `cancelCommitOutcome`, `proposeConfigChange` each call `assertOwnerThread()` at the
  top. The off-owner fire-test (`offOwnerAccessTripsTheGuard_provesHarnessCatchesARace`) now drives
  **all 14** guarded entry points off-owner and proves each one trips. R-01' enforcement is total over
  the mutator/callback surface. *(The read-only O accessors — currentTerm/votedFor/log/transferTarget/
  clusterConfig — are deliberately NOT guarded: they are the H-3 monitoring-read hazard, resolved by a
  metrics snapshot in Workstream B, not by a tripwire. See threading-contract §4.1/§6 H-3.)*
- **H3 (noted):** the safe-rider thread asserts only by not-throwing (writes `failure` on any trip) —
  adequate as a canary.

## H2 closeout — the complete guarded surface (re-verified)

`assertOwnerThread()` now sits at the top of all 14 mutator/callback O entry points; 338 consensus-core
tests stay green (guards inert until bound), and the extended off-owner fire-test confirms each of the
14 trips `raft_owner_thread`:

```
[INFO] Tests run: 2, Failures: 0, Errors: 0 -- RaftNodeConcurrencyStressTest   (green path + 14-entry fire-test)
[WARNING] Tests run: 338, Failures: 0, Errors: 0, Skipped: 2                    (full consensus-core regression)
```

## JMM micro-race (`configd-jcstress` — RaftOwnerThreadGuardTest)

The macro harness runs on a normally-scheduled JVM; the jcstress micro-race pins the *memory-model*
property the net rests on. Two `@State` classes mirror the tripwire verbatim:

| Test | Mode | Proves |
|---|---|---|
| `OwnerGuardNoFalseNegativeInService` | gated/clean | once a node is in service, an off-owner caller **always** observes the binding and the guard fires — the `FALSE_NEGATIVE` outcome is JMM-unreachable (14/14 variants passed under `-m quick`, 0 forbidden). |
| `UnboundGuardIsInertAndRaces` | non-gated, forbidden-hitting | an **unbound** guard is inert, so two off-owner threads race the non-volatile consensus state to a **lost update** — observed at up to **33.71%** (`[FAILED] 1, 1 … Forbidden`). Proves the detector fires and that binding is mandatory. |

The clean variant is in the gate (`run-curated-subset.sh` → 12 tests, 168 planned / 168 passed / 0
failed); the forbidden-hitting control is excluded, exactly like `HarnessSelfTest.KnownRacyCounter`.

## Sim integration (`configd-testkit`)

The tripwire is now wired into the deterministic simulation: `ClusterHarness` and `AdversarialSim`
bind each node's owner to the single drive thread on the first tick, and `raft_owner_thread` rides the
same throwing `InvariantChecker` as the in-node safety invariants (threading-contract §5.4).

- **No spurious fire under real schedules:** `SeedSweepTest` (**20,001 seeds**) + `AdversarialSimTest`
  + `ConsistencyPropertyTests` all green with owners bound — the sim's randomized-schedule access
  pattern provably respects single-ownership.
- **Catches the injected race:** `OwnerThreadSimIntegrationTest.offDriveThreadAccessFailsTheSeed`
  elects a leader (binding owners), then touches a node from a FOREIGN thread → the tripwire fires and
  the throwing checker fails the seed (`raft_owner_thread`). The companion
  `boundSimRunsGreenAndNonVacuous` proves the bound path commits real work without firing.

## Conclusion

The R-01' owner-thread tripwire + the concurrent stress harness + the jcstress micro-race + the sim
integration are **proven to catch an off-owner access across the complete 14-entry-point contract
surface** — at the unit, memory-model, and randomized-schedule levels. Review-H2 is closed and
**Workstream A (the verification net) is complete**. Per the prime directive, the machinery exists and
has been shown to catch a real race at every level — so (and only so) may Workstream B re-threading
proceed, each step behind this net.
