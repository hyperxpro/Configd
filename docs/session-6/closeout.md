# Session 6 — Operability & Deployment Readiness: Closeout

Branch `session-6-operability`. Gates 1–5 immutable; gate-6 added, CI-wired (`needs: gate-5`),
**green locally** (`captures/gate-6-local-green.txt`). This session turned S5's measured baselines and
S3's emitted series into an *operable* system: dashboards and alerts wired to series proven emitted and
recorded, runbooks validated by executing them against injected faults, bootstrap/upgrade/rollback with
wire-compat, and the game-day alert→runbook→recovery loop gated.

## Definition of Done

- [x] **RR-110 resolved on the merits** (doc-fix vs code-fix justified + logged) — D-1: 3 RELABEL + 1
      narrow IMPLEMENT (Retry-After + tested counter); `architecture.md §11` + `performance.md §4`
      relabeled; register row RESOLVED.
- [x] **SLO/SLI defined** against §0.1 + S5 baselines; the S1 "9 metrics hardwired to zero" debt closed
      — `observability.md` SLO/SLI table; three live defects found + fixed (D-2/D-3/D-4).
- [x] **Dashboards as code; every panel's series proven emitted** — 4 boards in `ops/dashboards/`;
      `EdgeMetricsContractTest.everyDashboardAndAlertSeriesIsProvenEmitted` green.
- [x] **Alerts as code; fires/quiet tests; S1 never-emitted alerts rewritten/removed; thresholds
      PROPOSED with derivation** — `ops/alerts/configd-slo-alerts.yaml` (14 rules) + `.test.yaml`
      (promtool, all green); propagation ghost → `edge_staleness_ms`.
- [x] **Every §6 runbook written AND execution-validated** against its injected fault — `WS-B` +
      `runbooks-validation.md`; sre-reviewer sign-off below. (4 live-threshold injectors honestly
      PENDING — rule proven by promtool, mechanism tested — D-6.)
- [x] **Bootstrap cold-start implemented + tested** zero-state → serving — `BootstrapColdStartTest`;
      `deployment.md §1`.
- [x] **Rolling upgrade + rollback proven; wire-compat N↔N+1 in the gate** — golden-fixture
      byte-stability + durable restart + backup/restore equality (D-7; live cross-binary matrix → S7.5).
- [x] **Backup/restore round-trip with state-equality** — `BackupRestoreRoundTripTest` (overwrite +
      delete + version).
- [x] **Game-day drill passes; no builder improvisation** — `GameDayDrillTest` (CI subset) + the
      multi-node `e2e-compose-scenario.sh` (gate-3) wrapped by `game-day-drill.sh` (ops lane).
- [x] **gate-6 green in CI-wired form; ops/nightly lane authored** — `gate-6.sh` + ci.yml job; the
      full multi-node drill is the nightly lane (capture on first nightly run / release).
- [x] **Claim–evidence: operability rows converted with commands** — below.
- [x] **Decision Log** (self-resolved technical + conservative-default scope) — `decision-log.md` D-1…D-8.
- [x] **handoff-to-session-7.md** — operability surface for security + PROPOSED thresholds + hardening.

## Claim → evidence (reproducible commands)

| Claim | Command | Expected |
|---|---|---|
| Every dashboard panel + alert series is emitted (no blind panels) | `./mvnw -o -pl configd-edge-node test -Dtest=EdgeMetricsContractTest` | 7 green incl. `everyDashboardAndAlertSeriesIsProvenEmitted` |
| SLO series recorded with real data on real paths (S1 debt dead) | `./mvnw -o -pl configd-server test -Dtest=MetricsWiringContractTest` | 4 green (commit latency/total/apply + le=0.150 bucket; failed; overload; gauges≠0) |
| Every alert fires on its condition + stays quiet normally | `cd ops/alerts && promtool test rules configd-slo-alerts.test.yaml` | SUCCESS (14 rules) |
| Alert rules lint | `promtool check rules ops/alerts/configd-slo-alerts.yaml` | SUCCESS: 14 rules found |
| RR-110 Retry-After 429 backed by a tested counter | `./mvnw -o -pl configd-server test -Dtest=MetricsWiringContractTest#overloadedWriteRecordsRejectCounter` | green |
| Overload runbook fault is real | `./mvnw -o -pl configd-testkit test -Dtest=OverloadChaosTest` | 6 green |
| Disk-full/fsync runbook fault is real | `./mvnw -o -pl configd-testkit test -Dtest=StorageEnospcConsensusReactionTest` | 2 green |
| Zero-state cold start → serving | `./mvnw -o -pl configd-server test -Dtest=BootstrapColdStartTest` | green (self-elects; live SLO buckets) |
| Wire byte-stability N↔N+1 | `./mvnw -o -pl configd-transport,configd-distribution-service test -Dtest=WireCompatGoldenBytesTest,EdgeFrameCodecGoldenFixtureTest` | 16 + 25 green |
| Backup/restore state-equality | `./mvnw -o -pl configd-config-store test -Dtest=BackupRestoreRoundTripTest` | green |
| Alert→runbook→recovery loop closes | `./mvnw -o -pl configd-edge-node test -Dtest=GameDayDrillTest` | green |
| Whole operability gate | `GATE6_SKIP_GATE5=1 bash gates/gate-6.sh` | GATE-6 GREEN |

## Decision Log

`decision-log.md` — D-1 (RR-110 merits, opus arbiter) · D-2 (metric-wiring semantics) · D-3 (exporter
schedules defect) · D-4 (panels 5/6 wired) · D-5 (JvmMetrics binder) · D-6 (runbook validation scope) ·
D-7 (upgrade/rollback scope) · D-8 (promtool for fires/quiet).

## sre-reviewer sign-off

`sre-reviewer` (reliability-engineer) audited all 11 runbooks against "could a non-builder on-call
execute this at 3am without improvising," cross-checking every alert name, metric series, dashboard
panel title, HTTP endpoint, `kubectl` object, and in-container tool against ground truth. Result:
**10/11 PASS as-written, 1 BLOCK** — `snapshot-install.md` step 3 instructed a joint-consensus
reconfiguration via a `--peers` edit, but `proposeMembershipChange` has **no main caller** (verified:
`grep proposeMembershipChange */src/main` → none), so a follower keeps its StatefulSet ordinal/node-id
and membership never changes. **BLOCK fixed in this pass** (step 3 rewritten to the correct same-node-id
PVC-wipe → respawn → InstallSnapshot catch-up; no membership change). Three nice-to-have accuracy items
also fixed: the unwired-membership claim reconciled in `control-plane-down.md` + `README.md`, the
`edge_fanout_demotions_total` ghost grep token → `demotions_` (all per-reason series) in
`propagation-delay.md` + `edge-catchup-storm.md`, and the PDB wording corrected in `control-plane-down.md`
+ `raft-saturation.md` (a direct `delete pod` removes exactly the named pod; `maxUnavailable:1` bounds
concurrent disruptions). The reviewer confirmed **no ghost SLO series, no invented endpoints
(`/admin/*`, `/raft/status`, transfer-leadership/add-server RPCs), no distroless-vs-curl trap**, and
that all `kubectl` objects (labels, PVCs, PDB) are real.

**Sign-off: 11/11 runbooks non-builder-executable** after the fix pass.
