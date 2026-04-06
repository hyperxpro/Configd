# iter-002 — Merged Findings & Fix Dispatch

**Date:** 2026-04-19
**HEAD:** `22d2bf3` + iter-1 fixers (F1..F7) committed in working tree
**Severity floor:** S3 (per `docs/loop-state.json` for iter 1-3)
**Reviewers:** 9 (chaos-skeptic, confused-new-engineer, distributed-systems-adversary, docs-linter, honesty-auditor, hostile-principal-sre, performance-skeptic, release-skeptic, security-red-team)

## Severity Rollup

| Reviewer | Findings | S0 | S1 | S2 | S3 | Notes |
|---|---|---|---|---|---|---|
| chaos-skeptic | 18 | 1 | 5 | 9 | 3 | C-101 is the highest-impact iter-2 finding (StateMachineSafety violation under ENOSPC) |
| confused-new-engineer | 14 | 2 | 1 | 8 | 3 | Onboarding regression (README empty + wiki cites Gradle/JDK21) |
| distributed-systems-adversary | 9 | 1 | 2 | 4 | 2 | D-013 is an iter-1 D-002 regression |
| docs-linter | 28 | 0 | 5 | 14 | 9 | Doc-drift density still high; F3-report.md ADR list is hallucinated |
| honesty-auditor | 0 failures, 0 demotions | — | — | — | — | Loop may advance; no Type-B/C GREEN flips |
| hostile-principal-sre | 14 | 2 | 5 | 6 | 1 | H-001/H-002 are critical operator-fatal drift |
| performance-skeptic | 18 | 0 | 0 | 9 | 9 | 13 iter-1 carryovers reach persistence count = 1 |
| release-skeptic | 13 | 2 | 5 | 3 | 3 | R-001/R-002 are iter-1 regression openers (R-002 is from D-004 fix itself) |
| security-red-team | 11 | 0 | 2 | 5 | 4 | SEC-017/SEC-018 are iter-1 closure-induced gaps |
| **TOTAL** | **125** | **8** | **25** | **58** | **34** | |

## Cross-Reviewer Dedup Clusters

### Cluster A — iter-1 Closures Opened New S0s (highest priority)

These are findings where an iter-1 fix landed correctly but exposed an adjacent gap that itself is now S0:

- **R-002** + (security-red-team SEC-017 mirror on edge) — D-004 added a snapshot trailer for `signingEpoch` but used a non-extensible `remaining() >= 8` probe. Adding any second trailer field will silently corrupt N-1 readers. Symmetric edge-side gap (`DeltaApplier.highestSeenEpoch` is in-memory only, resets on restart) is filed as SEC-017 (S1).
- **D-013** — D-002's `handleInstallSnapshot` fix correctly preserves the index but does not guard against `req.lastIncludedIndex < lastApplied`. Restoring such a snapshot regresses `sequenceCounter` and the snapshot map while the monotonic `setLastApplied` blocks re-application. Net: silently lost committed entries on the follower's state machine.

### Cluster B — Operator-Fatal Runbook/Script Drift

H-001 + H-002 + N-105 + N-106 + N-109 + DOC-027 + DOC-028 + DOC-029 + DOC-030 + DOC-039 + multiple H-003/H-007/H-011:

- `restore-snapshot.sh` calls `systemctl stop configd.service` but only deployment is K8s.
- `restore-from-snapshot.md` invokes the script with wrong flags (`--snapshot=` vs script's `--snapshot-path=`; omits required `--target-cluster`).
- `docs/runbooks/` (8 stale files) ships in parallel with `ops/runbooks/`, no superseded marker, references `*Ms` config fields (renamed in iter-1 D-003).
- `control-plane-down.md` voter-health probe uses `app=configd-server`; StatefulSet labels pods `app=configd`.
- 11+ runbook-cited metrics + 1 endpoint don't exist in production code.

### Cluster C — Onboarding Drift (S0/S2)

N-101 + N-102 + N-103 + N-104 + DOC-036 + DOC-038 + DOC-039 + DOC-040:

- README.md is a one-line stub.
- CONTRIBUTING.md absent.
- `docs/wiki/Getting-Started.md` cites Gradle + JDK 21; repo uses Maven + JDK 25.
- `docs/wiki/Home.md` claims half the implemented modules are "Planned".
- 11 `<!-- TODO PA-XXXX -->` placeholder issue numbers ship in production runbooks.

### Cluster D — Wire-Compat / Mixed-Version Operability (S0/S1)

R-001 + R-005 + R-006 + R-007 + R-008:

- `FrameCodec.decode` rejects any version != `WIRE_VERSION`. No N-1 ↔ N coexistence; no Hello/negotiation.
- Wire-compat fixture-bump enforcement allows fixture *deletion* without bump.
- No N-1 → N rolling-restart upgrade test.
- D-004 trailer added without a Migration SPI.
- Runtime image entrypoint (`/app/libs/*` classpath launch) vs StatefulSet command (`-jar configd-server.jar`) disagree → CrashLoopBackOff on `kubectl apply`.

### Cluster E — Performance Carryover Persistence (S2/S3)

P-002…P-014 (13 iter-1 findings) all reach persistence count = 1 with no movement; another no-op iteration triggers escalation per §4.7. Plus 13 new P-017…P-032 findings, mostly hot-path allocations that compound each carryover.

### Cluster F — Hard-Rule §8.1 Re-Opening Risk

P-017 — `perf/results/jmh-2026-04-19T00Z-PLACEHOLDER/` is a literal placeholder; iter-1 closed §8.1 with a directory but produced no JMH artefact. `docs/performance.md` table §3 still cites concrete ns/op numbers as "JMH Measured (avg)" without backing files.

## S0 Item-by-Item Disposition

| ID | Title | Owner module | Disposition |
|---|---|---|---|
| C-101 | RaftLog.append: in-memory list mutated before storage WAL → ENOSPC drops fsync, leader has volatile entry while followers fsync durably | configd-consensus-core/RaftLog | **F1** — reorder: WAL-fsync first, in-memory list second; rollback list on storage throw |
| D-013 | InstallSnapshot regresses follower SM when `lastApplied > req.lastIncludedIndex` | configd-consensus-core/RaftNode | **F1** — guard: reject install when `req.lastIncludedIndex() <= log.lastApplied()` |
| R-001 | FrameCodec hard-equality wire-version → no mixed-version cluster | configd-transport/FrameCodec | **F2** — add `SUPPORTED_VERSIONS` set; surface `UnsupportedWireVersionException` only when version not in set |
| R-002 | D-004 snapshot trailer is non-extensible | configd-config-store/ConfigStateMachine | **F1** — TLV trailer with magic `0xC0FD7A11` + length prefix; backward-compat with both the legacy "no trailer" and the iter-1 "raw 8-byte epoch" forms |
| H-001 | restore-snapshot.sh systemctl on a K8s-only deployment | ops/scripts | **F3** — replace systemctl with kubectl exec, or branch on detected deployment type |
| H-002 | restore-from-snapshot.md flags drift (`--snapshot=` vs `--snapshot-path=`; missing `--target-cluster`) | ops/runbooks | **F3** — sync runbook to script's actual CLI |
| N-101 | README.md is a one-line stub | repo root | **F4** — write 30-line README per N-101 fix proposal |
| N-103 | docs/wiki/Getting-Started.md says Gradle + JDK 21 | docs/wiki | **F4** — rewrite to Maven + JDK 25; sweep Docker.md and Testing.md for same drift |

## In-Scope S1 Item-by-Item Disposition

iter-2 fix dispatch capacity: at S3 floor we touch all in-scope S1s. Routing assigned by F-lane:

| ID | Title | F-lane |
|---|---|---|
| D-014 | InstallSnapshot null clusterConfigData drops joint-consensus state | F1 |
| D-015 | First post-election ReadIndex confirmed by optimistic peerActivity init | F1 |
| C-102/C-103 | Compactor.rewriteWal + FileStorage.put fault-injection coverage | F1 (test-only) |
| C-104 | SlowStorage primitive missing (deferred iter-1 C-009) | **defer** — needs harness rework |
| C-105 | HLC backward wall-clock jump overflows logical counter (deferred iter-1 C-010) | F1 (HLC clamp) |
| C-106 | CatchUpService zero tests | F1 (test-only) |
| H-003 | release.md rollback criteria reference nonexistent endpoints/metrics | F3 |
| H-004 | ConfigdControlPlaneAvailability denominator trap | F3 (alert math) |
| H-005 | ConfigdEdgeReadFastBurn is raw threshold, not burn-rate | F3 (alert rewrite) |
| H-006 | ConfigdSnapshotInstallStalled pages on single retry | F3 (debounce) |
| H-007 | K8s probes hard-coded HTTP; production runs HTTPS | F3 (statefulset.yaml) |
| H-009 | Tick loop swallows Throwable without metric | F2 (configd-server tick loop + ConfigdMetrics) |
| N-107 | ADR-0001 Verification cites nonexistent /raft/status | F4 |
| R-003 | R-004 canary + SLO-burn auto-abort still missing | **defer** to S1 carryover (needs real env per loop-state.json) |
| R-004 | release.yml doesn't scan runtime image; only fs scan | F4 (CI workflow) |
| R-005 | wire-compat fixture-bump enforcement allows deletion | F4 (CI workflow) |
| R-006 | No N-1 → N upgrade test | **defer** — needs harness; record in carryover |
| R-007 | D-004 trailer added without Migration SPI | covered by F1 R-002 (TLV is the SPI seed) |
| R-008 | Dockerfile vs StatefulSet command mismatch → CrashLoopBackOff | F3 (statefulset.yaml or Dockerfile.runtime entrypoint) |
| SEC-017 | Edge highestSeenEpoch is in-memory only | F2 (configd-edge-cache/DeltaApplier persistence) |
| SEC-018 | signCommand mutates store before signing → fanout-skip on signer throw | F2 (ConfigStateMachine apply ordering swap) |
| DOC-027 | snapshot-install.md cites nonexistent SnapshotConformanceTest | F4 |
| DOC-028 | 3 runbooks cite nonexistent adr-0009-snapshot-format.md | F4 |
| DOC-029 | runbook-conformance-template references InvariantMonitor.assertAll() | F4 |
| DOC-030 | docs/runbooks/ stale tree, no superseded marker | F4 (add README.md banner) |
| DOC-033 | 5 runbook metrics not emitted by any Java | F4 (rewrite prose) |
| DOC-035 | architecture.md "< 50ns" / "< 80ms" claims still unsourced | F4 |

## Fix Dispatch Plan (F1..F4)

Four F-lanes, parallelizable across subagents. F1 is in-process (highest risk surgery, must be done by main agent under review). F2/F3/F4 dispatched as background subagents.

### F1 — In-process (main agent): Consensus / store safety surgery

- **R-002 (S0):** rewrite ConfigStateMachine snapshot trailer as TLV — magic `0xC0FD7A11` + 4-byte length + payload; backward-compat with both `[seq][count][entries]` (no trailer) and `[seq][count][entries][8B-epoch]` (iter-1 raw form). Add 3 regression tests: (a) `legacyNoTrailerLoads` (already added in iter-1), (b) `rawEpochTrailerStillLoads` (the iter-1 format remains decodable), (c) `tlvTrailerLoadsAndPreservesEpoch`.
- **D-013 (S0):** in `RaftNode.handleInstallSnapshot`, reject when `req.lastIncludedIndex() <= log.lastApplied()`; surface a `configd_raft_snapshot_install_rejected_total{reason="stale_index"}` counter; add regression test `installSnapshotWithStaleIndexIsRejected`.
- **C-101 (S0):** in `RaftLog.append`, swap order — `storage.appendToLog(...)` first, then in-memory list mutation; on storage throw, do not mutate the list; add regression test `appendThrowsBeforeListMutation` using a fault-injecting storage stub.
- **D-014 (S1):** when `clusterConfigData == null` on InstallSnapshot, retain prior `clusterConfig` instead of falling back to static `RaftConfig.peers()`; regression test.
- **D-015 (S1):** clear `peerActivity` on `becomeLeader`; require real heartbeat ack before first ReadIndex confirmation; regression test.
- **C-102, C-103, C-105, C-106 (S1):** test-only additions: WAL rewrite fault-inject, FileStorage temp+rename fsync coverage, HLC wall-clock-backward clamp + counter overflow guard, CatchUpService delta-history-purged tests.

### F2 — Subagent: Server / observability / edge surgery

- **H-009 (S1):** tick-loop `try/catch (Throwable t)` should record `configd_tick_loop_throwable_total{class}` + structured-log via `Logger.log(SEVERE, ...)`, not `printStackTrace`.
- **SEC-017 (S1):** persist `highestSeenEpoch` in `LocalConfigStore` (or sidecar `epoch.lock`); regression `epochReplayRejectedAcrossRestart`.
- **SEC-018 (S1):** swap `apply()` ordering — sign first (compute payload + signature), then `store.put(...)`, then `notifyListeners(...)`; regression `signFailureLeavesStoreUnmutated`.

### F3 — Subagent: Operator / runbook / alert / k8s drift

- **H-001 (S0):** rewrite `restore-snapshot.sh` to use `kubectl` instead of `systemctl`.
- **H-002 (S0):** sync `restore-from-snapshot.md` flag names to script's actual CLI; require `--target-cluster`.
- **R-008 (S1):** reconcile `Dockerfile.runtime` entrypoint with `configd-statefulset.yaml` `command:`. Pick one (prefer dropping `command:` and using image entrypoint).
- **H-003, H-004, H-005, H-006, H-007, H-011 (S1/S2):** alert rewrite, probe scheme fix, debounce, runbook-metric reconciliation.
- **N-105, N-106, N-109 (S2):** delete `docs/runbooks/` or add a deprecation banner README; fix label selector and tick units.

### F4 — Subagent: Docs / CI / onboarding

- **N-101 (S0):** write `README.md` per N-101 fix proposal.
- **N-103 (S0):** rewrite `docs/wiki/Getting-Started.md` to Maven + JDK 25; sweep `Docker.md`, `Testing.md`.
- **N-102, N-104, N-107, N-108 (S1/S2):** create CONTRIBUTING.md; rewrite Home.md status table; fix ADR-0001 Verification operator-check; add PA-XXXX TODO marker to release.md.
- **R-004, R-005 (S1):** add image-scan step to `release.yml`; tighten wire-compat CI to detect fixture deletion.
- **DOC-027/028/029/030/033/035 (S1):** doc-drift sweep — fix nonexistent test/ADR/metric citations; add deprecation banner to `docs/runbooks/`.
- **P-017 (S2):** either run JMH and commit one artefact under `perf/results/jmh-<sha>/`, or annotate every concrete ns/op in `docs/performance.md` table §3 with the same MODELED disclaimer.

## Out-of-Scope (deferred per loop directive)

- **R-003 / R-006:** canary harness + N-1→N upgrade test — both require a real environment; carry over per §4.7.
- **C-104, C-108, C-111, C-112, C-113:** chaos primitives needing harness rework — carry over (already deferred in iter-1).
- **D-006, D-009, D-010, D-005:** re-flagged from iter-1 with deliberate defer rationale documented per finding.
- **All security S2/S3:** carry over to iter-3 unless covered above by F2.
- **All performance carryovers (P-002..P-014):** intentional defer, persistence count = 1; iter-3 escalation if no movement.

## Honesty / Hard-Rule Status

- Honesty-auditor entry pass: **0 failures, 0 demotions, 0 Type-B/C GREEN flips.** Loop may proceed.
- §8.1: violated again by P-017 (perf claims without artefact); F4 must close by either producing JMH artefacts or annotating numbers MODELED.
- §8.10/§8.14/§8.15: iter-1 closures still hold; honesty-auditor verified §8.14 (9-section runbook skeleton) and §8.15 (every ADR has Verification) are intact.
- Stability_signal forecast: F1 closes 4 of 8 S0s in-process (R-002, D-013, C-101, plus N-101 doc-only via F4). Per §5, stability_signal = 0 only if iter-2 introduces no new S0/S1 from its own fixes. Verify pass at iter-2 end re-runs the auditor.
