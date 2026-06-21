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
- **H2 (tracked → A.1 follow-up / Workstream B):** documented "tick-thread-only" mutators remain
  UNGUARDED — `whenReadReady`, `completeRead`, `cancelCommitOutcome`, `isReadReady`, plus
  `transferLeadership`, `triggerSnapshot`, `proposeConfigChange`. R-01' enforcement is not yet total;
  these get guards as Workstream B re-threads each path. Production is inert until bound, so this is a
  completeness gap, not a live hazard.
- **H3 (noted):** the safe-rider thread asserts only by not-throwing (writes `failure` on any trip) —
  adequate as a canary.

## Conclusion

The R-01' owner-thread tripwire + the concurrent stress harness are **proven to catch an off-owner
access**. Per the prime directive, the verification machinery now exists and has been shown to catch
a real race — so (and only so) may Workstream B re-threading proceed, each step behind this harness.
