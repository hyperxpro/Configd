## iter-001 — Verify + Decide

**Date:** 2026-04-19
**HEAD at verify start:** `22d2bf30`
**Severity floor:** S3 (per §4.7 — iter 1-3)
**Phase:** §4.5 Verify + §4.6 Decide

---

## Build / test gate (Type A)

Full reactor `./mvnw test` under JDK 25 (`--enable-preview`):

```
[INFO] Reactor Summary for configd 0.1.0-SNAPSHOT:
[INFO]
[INFO] configd ............................................ SUCCESS [  0.004 s]
[INFO] configd-common ..................................... SUCCESS [  2.540 s]
[INFO] configd-transport .................................. SUCCESS [ 18.369 s]
[INFO] configd-consensus-core ............................. SUCCESS [  3.146 s]
[INFO] configd-config-store ............................... SUCCESS [  4.398 s]
[INFO] configd-observability .............................. SUCCESS [  1.796 s]
[INFO] configd-edge-cache ................................. SUCCESS [  3.071 s]
[INFO] configd-replication-engine ......................... SUCCESS [  1.401 s]
[INFO] configd-distribution-service ....................... SUCCESS [  2.085 s]
[INFO] configd-control-plane-api .......................... SUCCESS [  1.069 s]
[INFO] configd-testkit .................................... SUCCESS [ 14.217 s]
[INFO] configd-server ..................................... SUCCESS [  6.366 s]
[INFO] BUILD SUCCESS in 58.7s
```

Per-module test counts (run lines):

| Module | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|
| configd-common | 88 | 0 | 0 | 0 |
| configd-transport | 105 | 0 | 0 | 0 |
| configd-consensus-core | 158 | 0 | 0 | 2 (WAL stub, intentional) |
| configd-config-store | 236 | 0 | 0 | 0 |
| configd-observability | 130 | 0 | 0 | 0 |
| configd-edge-cache | 147 | 0 | 0 | 0 |
| configd-replication-engine | 119 | 0 | 0 | 0 |
| configd-distribution-service | 146 | 0 | 0 | 0 |
| configd-control-plane-api | 50 | 0 | 0 | 0 |
| configd-testkit | 20149 | 0 | 0 | 0 (large jqwik property suite) |
| configd-server | 74 | 0 | 0 | 0 |

**Aggregate:** 21,402 tests, 0 failures, 0 errors, 2 skipped (intentional). Type-A gate **GREEN**.

---

## Fix dispatch result

| Lane | Owner | Scope | Outcome |
|---|---|---|---|
| F1 | in-process | 6 GREEN→YELLOW demotions in `docs/ga-review.md` | DONE — Phase 4/6/9, B6, PA-6012, O7 demoted with reason cells |
| F2 | in-process (Opus) | C-001, C-002, D-001, D-002, D-003, D-004 | DONE — see §F2 details below |
| F3 | subagent | §8.15 ADR `## Verification`, §8.14 runbook 8-section, ADR cross-refs, ADR-0006 superseded, CHANGELOG, ADR-0027 | DONE — `fixers/F3-report.md` |
| F4 | subagent | restore scripts, configd-bootstrap.yaml, kubectl confirmation guard, raftctl→curl admin docs | DONE — `fixers/F4-report.md` |
| F5 | subagent | MetricsRegistry + ConfigdMetrics SLO wire-up; commit/snapshot/edge-read/snapshot-install metrics | DONE — `fixers/F5-report.md` + `F5-metric-diff.md` |
| F6 | subagent | release.md rollback, RELEASE_NOTES_TEMPLATE, FrameCodec wire-version byte, golden-bytes wire-compat CI, snapshot/WAL stubs, release.yml | DONE — `fixers/F6-report.md` |
| F7 | subagent | P-001 — strike "HdrHistogram" claim, add measured/modeled disclaimer to cross-region perf, JMH log artifact placement | DONE — `fixers/F7-report.md` |

### F2 Tier-1-SAFETY surgery — detail

| Finding | Code change | Regression test | Verify |
|---|---|---|---|
| **C-001** wire-frame integrity | `FrameCodec.java`: 4-byte CRC32C trailer; new `FrameChecksumException`; both `encode` overloads + `decode` updated; `frameSize()` includes `TRAILER_SIZE` | `FrameCodecTest`: `decodeRejectsCorruptedPayload`, `decodeRejectsCorruptedHeader`, `decodeRejectsTamperedCrc` | configd-transport 22/22 + 105/105 module |
| **C-002** durable-state integrity | `FileStorage.java`: `[length:4][crc32:4][payload]` framing for `.dat` writes; `get` re-verifies before returning | `FileStorageTest`: `datFileCrcDetectsBitFlip`, `datFileTruncationIsDetected` | configd-common 88/88 |
| **D-001** ReadIndex per-round confirmation | `ReadIndexState.java`: added `startRound` field + `startRead(commitIndex, currentRound)` + `confirmLeadershipForReadsBefore(currentRound)`; deprecated old paths. `RaftNode.java`: `heartbeatRound` field, `tickHeartbeat` advances round before activeSet, `confirmPendingReads` confirms only reads issued in strictly earlier rounds | `ReadIndexLinearizabilityReplayerTest` (4 spec-replay properties, 200 tries each) | configd-consensus-core 158/158 |
| **D-002** InstallSnapshot index echo | `InstallSnapshotResponse`: added `lastIncludedIndex` field. `RaftNode.handleInstallSnapshot` echoes installed index in 3 response sites; `handleInstallSnapshotResponse` uses `resp.lastIncludedIndex()` with regression guard. `RaftMessageCodec`: `[1B success][4B from][8B lastIncludedIndex]` encoding | `InstallSnapshotTest` (3 nested suites updated), `RaftMessageCodec*Test` | consensus-core + server |
| **D-003** tick/ms unit fix | `RaftConfig`: renamed `*Ms` → `*Ticks` for `electionTimeoutMin/Max` and `heartbeatInterval`; updated validation messages and Javadoc. `RaftNode`: 3 callsites updated to `*Ticks` accessors | existing `RaftConfigTests` + `RaftNodeTest` continue to pass | consensus-core 158/158 |
| **D-004** signingEpoch in snapshot | `ConfigStateMachine.snapshot()`: appends 8-byte `signingEpoch` trailer. `restoreSnapshot()`: reads trailer if present and takes `max(current, restored)` to never regress; legacy snapshots without trailer still load | `signingEpochSurvivesSnapshotRoundTrip`, `legacySnapshotWithoutEpochTrailerStillLoads` (new) | config-store 236/236 (was 234, +2) |

All F2 fixes carry inline citations to the originating finding ID and the §-rule they enforce.

---

## §8 hard-rule violations status (post-fix)

| Rule | Pre-iter1 | Post-iter1 | Source of close |
|---|---|---|---|
| §8.1 — no perf claim without HdrHistogram | OPEN (P-001) | CLOSED | F7 — claim struck + measured/modeled disclaimer + JMH log dir |
| §8.10 — wire-compat deprecation ≥ 2 releases | OPEN (R-005/007/008) | CLOSED | F6 — wire-version byte + golden-bytes CI job + stubs |
| §8.14 — runbook 8-section skeleton | OPEN (DOC-002) | CLOSED | F3 — all 9 runbooks restructured, grep-verified |
| §8.15 — every ADR has `## Verification` | OPEN (DOC-001) | CLOSED | F3 — all 26 ADRs verified, "NOT YET WIRED" template used where honest |

---

## S0 closure (8/8)

| ID | Status | Evidence |
|---|---|---|
| H-001 | CLOSED | F5: metrics registered + wired; SLO recorder calls in HttpApiServer/ConfigStateMachine |
| D-001 | CLOSED | F2: per-round future-confirmation; spec replayer green |
| D-002 | CLOSED | F2: follower-echoed lastIncludedIndex; regression guard |
| D-003 | CLOSED | F2: `*Ticks` rename; 3 callsites converted |
| C-001 | CLOSED | F2: CRC32C trailer + 3 corruption tests |
| C-002 | CLOSED | F2: file-storage CRC envelope + bitflip/truncation tests |
| N-013 | CLOSED | F4: kubectl confirmation guard wired into disaster-recovery runbook |
| R-002 | CLOSED | F6: rollback section in release.md |

---

## Stability signal (§4.6)

`stability_signal = (S0 introduced this iteration) + (S1 regressions)`.

- New S0 introduced by fixes: **0** (all F2 changes carry their own regression tests; full reactor green).
- New S1 introduced: **0** (no API drift, no test deletions, no metric removals; F5 race-condition fix in `HttpApiServerMetricsTest` was a pre-existing test flake hardened by F5).
- Demoted GREEN→YELLOW gates remain YELLOW (Type B/C honesty invariant respected).

**stability_signal = 0** for iter-1.

Per §5: termination requires `stability_signal = 0` for **two consecutive iterations**. iter-1 alone is not termination — iter-2 must continue at the S3 floor.

---

## Decision (§4.6)

- **Continue to iter-2** at floor S3 per §4.7.
- iter-2 scope: ~70 deferred S1/S2/S3 items remaining from the ~95-item triaged set, plus any new findings from a second 9-reviewer pass.
- Honesty invariant violations this run: **0**.
- Persistence signal: **false** (no item has aged out of two iterations yet).

---

## Artifacts

- `docs/review/iter-001/merged.md` — triage + fix dispatch
- `docs/review/iter-001/fixers/F3-report.md` … `F7-report.md` — subagent reports
- `docs/ga-review.md` — gates updated with 6 demotions (F1)
- `CHANGELOG.md` — created (F3)
- `docs/decisions/adr-0027-sign-or-fail-close.md` — created (F3)
- All 26 ADRs now carry a `## Verification` section (F3)
- All 9 runbooks restructured to §8.14 8-section skeleton (F3)
- `ops/scripts/restore-snapshot.sh`, `ops/scripts/restore-conformance-check.sh`, `deploy/kubernetes/configd-bootstrap.yaml` — created (F4)
- `ops/alerts/configd-slo-alerts.yaml` metrics now backed by `ConfigdMetrics` registrations (F5)
- `.github/workflows/release.yml`, `RELEASE_NOTES_TEMPLATE.md` — created (F6)
- `docs/performance.md` — corrected (F7)
