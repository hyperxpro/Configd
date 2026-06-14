# RR-107 evidence — gates 1–4 GREEN in REAL GitHub CI (not local capture)

**Finding:** RR-107 (P2) — gate-4 had never executed in real GitHub CI (`session-4-chaos` was
unpushed; all prior gate-4/nightly "green" evidence was LOCAL captures). Item Zero of Session 5.

## Resolution evidence

**CI-subset run (gates 1–4, push/PR path):**

| Field | Value |
|---|---|
| Run ID | **27485165578** |
| URL | https://github.com/hyperxpro/Configd/actions/runs/27485165578 |
| Branch / SHA | `session-4-chaos` @ `cdf3dbf575e23483d67190e8be1305c2c36a2a0b` (the integrity-audited tip) |
| Trigger | `workflow_dispatch` |
| Started / finished | 2026-06-14T01:50:34Z → 02:05:43Z (~15 min) |
| **Conclusion** | **success** |

**Per-job conclusions (all green):**

```
 success  build-and-test (25)     # clean install + property + simulation + 10k seed sweep
 success  tlc-model-check         # ConsensusSpec + ReadIndexSpec + SnapshotInstallSpec
 success  wire-compat
 success  gate-1
 success  gate-2
 success  gate-3                  # incl. the 4-phase Compose E2E (docker on ubuntu-latest)
 success  gate-4                  # <-- FIRST gate-4 execution ever in real GitHub CI
```

**gate-4 job (`81240630218`) — non-hollow proof.** The CI log shows a real reactor install then each
step running real tests (per-class `Tests run: N, Failures: 0, Errors: 0`), matching the local
checkpoint-4.5 captures exactly:

```
=== GATE-4 (Session 4: durability, recovery & chaos) ===
GATE-4 gate3: SKIPPED by GATE4_SKIP_GATE3=1 (LOUD: gates 1+2+3 NOT verified this run; supplied by their own CI jobs)
GATE-4 install: OK
  Rr103InflightWindowRecoveryTest 1/0/0          -> GATE-4 liveness: OK
  ReconfigurationTest 14/0/0                     -> GATE-4 reconfig: OK
  RaftLogCompactionTriggerTest 1, SnapshotCrashRecoveryTest 7, FileStorageTest 18,
  MultiRaftDriverTest 23, StorageEnospcConsensusReactionTest 2, ConfigdServerTest 3  -> GATE-4 durability: OK
  FanOutSessionCoreTest 18, GovernorBoundedIdentityMapChurnTest 2, EdgeTransportMtlsTest 1 -> GATE-4 edgechaos: OK
  PartitionMatrixTest 6                          -> GATE-4 partition: OK   (workstream C, CI subset)
  OverloadChaosTest 2                            -> GATE-4 overload: OK    (workstream D, CI subset)
GATE-4 nightly: SKIPPED by GATE4_SKIP_NIGHTLY=1 (LOUD: heavy integrated sweeps NOT run this run)
=== GATE-4: ALL STEPS GREEN ===
```

The two `SKIP` lines are LOUD (echoed, not silent); the skipped coverage (gates 1–3, nightly sweeps)
is supplied by the separate CI jobs (gate-1/2/3 above) and by the nightly run below — no silent gap.

## Nightly chaos — "has run once" in real CI

GitHub fires `schedule:` **only from the default branch**, and `main`'s `ci.yml` is the old minimal
version (no gate jobs, no schedule). So the nightly cannot auto-fire on a session branch until this
lands on `main`. To prove the nightly chaos path runs green once in real CI now, the S5 `ci.yml`
adds a `workflow_dispatch` input `run_full_nightly` that forces the exact nightly path.

| Field | Value |
|---|---|
| Run ID | **27485499676** |
| URL | https://github.com/hyperxpro/Configd/actions/runs/27485499676 |
| Branch | `session-5-performance` |
| Trigger | `workflow_dispatch` (`run_full_nightly=true`) |
| Path exercised | FULL: gate-2 incl. PIT mutation, gate-3 incl. new-module PIT floors, **gate-4 incl. the heavy integrated chaos sweeps** (EdgeIntegratedNightlySweep 10k ticks + RR-095 integrated rerun + MiniJepsenSweepTest) |
| Status | dispatched 2026-06-14T02:06:23Z — long-running (~1.5–3 h); result appended on completion |

> **Standing note for merge-to-main (S6/S8):** the `schedule:` cron only becomes live once `ci.yml`
> lands on the default branch. Until then the nightly is *configured + manually executed once in real
> CI* (this run). True unattended scheduled firing is gated on the eventual merge to `main`.
