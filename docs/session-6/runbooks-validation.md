# Session 6 — Runbook execution-validation (Workstream B)

> Prime Directive (charter §3): *a runbook is not done until its steps have been executed against the
> fault it addresses.* Each runbook below is paired with the **executed** fault-injection harness/test
> that drives its fault, and the green result that confirms its diagnosis + recovery hold. Where no
> threshold-crossing **injector** exists, the alert RULE is still proven to fire on the series value by
> the promtool fires/quiet test, the underlying mechanism is tested, and the live-threshold injection
> is honestly marked as the S7.5 / M-item gap — never claimed as validated.

## Validated by an executed fault (green)

| Runbook | Injected fault (executed) | Result | Recovery verified |
|---|---|---|---|
| `overload-shedding.md` | `OverloadChaosTest` (write flood past bounded queue) + `MetricsWiringContractTest.overloadedWriteRecordsRejectCounter` (`maxPendingProposals=3`) | 6 + 1 green | shed plateaus (bounded), `configd_write_rejected_overloaded_total` increments, then commits resume |
| `disk-full-fsync.md` | `StorageEnospcConsensusReactionTest` (`FaultInjectingStorage` ENOSPC + fsync failure) | 2 green | node re-accepts the write once space returns |
| `control-plane-down.md` | `MetricsWiringContractTest.uncommittedWriteRecordsFailureCounter` (unconfirmed write) + `gates/rr-002-blackhole-drill.sh` (S4 follower-DROP) + `e2e-compose-scenario.sh` phase 2 (SIGKILL leader, gate-3 CI) | green | `configd_write_commit_failed_total` increments; failover → new `X-Leader-Hint`; commits resume; no edge cursor regression |
| `propagation-delay.md` + `edge-catchup-storm.md` | `GameDayDrillTest` (lagging edge → staleness alert → catch-up) + `EdgeMetricsContractTest.stalenessGaugesAndViolationCounterTrackTheLiveCore` + `e2e-compose-scenario.sh` phase 3 (partition edge, gate-3 CI) | green | `edge_staleness_ms` crosses 500/2000 then returns < 500; `edge_staleness_state` → CURRENT, lag 0 |
| `restore-from-snapshot.md` | `BackupRestoreRoundTripTest` (snapshot → restore to fresh SM, state-equality) + `ops/scripts/restore-conformance-check.sh` | green | restored state byte-equal incl. overwrite+delete; fresh write commits |
| `snapshot-install.md` | `InstallSnapshotTest` / `SnapshotInstallSpecReplayerTest` (follower snapshot install) | green | follower state machine restored; nextIndex/matchIndex advance |
| `raft-saturation.md` (wedged-leader / livelock half, RR-095/RR-103) | `Rr095StallSeedDiagnosisTest` + `LivenessBoundedProgressSweepTest` (wedge family) | green | recycled leader resumes `configd_write_commit_total`; backlog → ~0 |
| `write-commit-latency.md` | `MetricsWiringContractTest` (commit latency recorded) + `StorageEnospcConsensusReactionTest` (fsync fault stalls commit) | green | commit p99 returns < 150 ms after the injected fsync fault clears |
| `edge-read-latency.md` | `gates/jmh-gc-check.sh` (read-path 0 B/op) + `EdgeMetricsContractTest` (read-latency series emitted) | green (gate-5) | `gc.alloc.rate.norm` 0 B/op; read p99 sub-ms |

## Alert-rule proven, live-threshold injector honestly PENDING (no injecting harness exists)

For these four alerts there is no harness that drives the live system *across the threshold* (the
condition is emergent or needs production-scale load). The alert RULE is nonetheless proven to FIRE on
the threshold-crossing series value and STAY QUIET below it by `ops/alerts/configd-slo-alerts.test.yaml`
(promtool), and the underlying mechanism is tested — so the runbook's diagnosis/recovery is sound; only
the live-threshold *drill* is deferred:

| Runbook / alert | Why no injector | Proven instead | Deferred to |
|---|---|---|---|
| `edge-read-latency.md` live p99 > 1 ms | the read path is 1.6 µs (600× headroom); a live breach needs a pathological regression | promtool fires/quiet + 0 B/op gate | S7.5 / M-4,M-5 |
| `raft-saturation.md` backlog > 5000 | needs sustained apply-slower-than-commit at scale | promtool fires/quiet + wedge-family tests | S7.5 / M-9 |
| `snapshot-install.md` ≥3 fails / 15 min | needs a repeatedly-failing install at rate | promtool fires/quiet + InstallSnapshotTest | S7.5 |
| `resource-leak.md` FD/thread/heap ceilings | leaks are emergent — there is no leak *injector* (S5 soak is a detector) | promtool fires/quiet + `perf/soak.sh` flat-trend detector | S7.5 / M-4 soak |

## Multi-node lane

The full multi-node fault→recovery drill (kill-leader failover, partition→staleness-ladder→
re-bootstrap, fresh-edge bootstrap) is `gates/e2e-compose-scenario.sh` — already a **gate-3 CI step**,
so the multi-node faults run on every push. `gates/game-day-drill.sh` wraps it with the S6
drill→alert→runbook overlay (the ops/nightly lane). The CI-gated subset of the alert→runbook→recovery
loop is `GameDayDrillTest` in gate-6.

## sre-reviewer sign-off

`sre-reviewer` (reliability-engineer) reviewed each runbook against "could a non-builder execute this at
3am" — see the closeout (`docs/session-6/closeout.md`) for the per-runbook verdict and sign-off line.
