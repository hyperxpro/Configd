# F3 Report — Tier-1 Doc Fixes (iter-1)

**Subagent:** F3
**Scope:** Tier-1-DOCS-FIXES (DOC-001, DOC-002, DOC-009/010/011, N-010/011)
**Date:** 2026-04-19

## Summary

All six tasks completed. Every ADR now has a `## Verification` section
(real test refs where present, "NOT YET WIRED — tracked as iter-2
follow-up" where no test exists). All nine operational runbooks now
follow the §8.14 8-section skeleton plus Operator-Setup. Wrong ADR
cross-refs in three runbooks fixed. ADR-0006 marked superseded by
ADR-0018. ADR-0027 created for the sign-or-fail-close decision.
CHANGELOG.md scaffolded.

## Files edited / created

### Task 1 — ADR Verification sections (DOC-001)

All 26 ADRs under `/home/ubuntu/Programming/Configd/docs/decisions/`
gained a `## Verification` section. For each: which test (real path
verified via grep), what would invalidate, how operator verifies in
production. Where no test exists, used the prescribed "NOT YET WIRED"
template.

- `docs/decisions/adr-0001-embedded-raft-consensus.md` — refs
  `RaftNodeTest`, `RaftSimulationTest`.
- `docs/decisions/adr-0002-customer-isolation-model.md` — refs
  `ConfigScopeTest`, `AclServiceTest`.
- `docs/decisions/adr-0003-hyparview-gossip-overlay.md` — refs
  `HyParViewOverlayTest`.
- `docs/decisions/adr-0004-prefix-subscription-model.md` — refs
  `PrefixSubscriptionTest`, `BloomFilterTest`.
- `docs/decisions/adr-0005-lock-free-edge-reads.md` — refs
  `HamtMapTest`, `HamtMapPropertyTest`,
  `VersionedConfigStoreAllocationTest`.
- `docs/decisions/adr-0006-event-driven-notifications.md` — see Task 4.
- `docs/decisions/adr-0007-raft-simulation-test-strategy.md` — refs
  `RaftSimulationTest`.
- `docs/decisions/adr-0008-snapshot-and-compaction.md` — refs
  `CompactorTest`, `FileStorageTest`,
  `SnapshotInstallSpecReplayerTest`.
- `docs/decisions/adr-0009-snapshot-format.md` — refs
  `SnapshotConformanceTest`.
- `docs/decisions/adr-0010-prometheus-observability.md` — refs
  `PrometheusExporterTest`, `MetricsRegistry`.
- `docs/decisions/adr-0011-fan-out-topology.md` — refs
  `PlumtreeNodeTest`, `FanOutBufferTest`.
- `docs/decisions/adr-0012-purpose-built-storage-engine.md` — refs
  `HamtMapPropertyTest`, `VersionedConfigStorePropertyTest`.
- `docs/decisions/adr-0013-tcp-binary-transport.md` — refs
  `TcpRaftTransportTest`, `FrameCodecTest`, `FrameCodecPropertyTest`.
- `docs/decisions/adr-0014-zgc-shenandoah-gc-strategy.md` — refs
  `VersionedConfigStoreAllocationTest`; production verification via GC
  log inspection.
- `docs/decisions/adr-0015-tls-1-3-mutual-auth.md` — refs
  `TlsManagerTest`, `AuthInterceptorTest`.
- `docs/decisions/adr-0016-rate-limiting-strategy.md` — refs
  `RateLimiterTest`.
- `docs/decisions/adr-0017-watch-service-protocol.md` — refs
  `WatchServiceTest`, `SubscriptionManagerTest`.
- `docs/decisions/adr-0018-event-driven-notifications.md` — refs
  `WatchServiceTest`, `SubscriptionManagerTest`.
- `docs/decisions/adr-0019-hybrid-clock-versions.md` — refs
  `HybridClockAllocationTest`, `VersionCursorTest`.
- `docs/decisions/adr-0020-rollout-controller.md` — refs
  `RolloutControllerTest`.
- `docs/decisions/adr-0021-maven-build.md` — refs `pom.xml` enforcer
  rules; "NOT YET WIRED" for build-output verification.
- `docs/decisions/adr-0022-java-version-pin.md` — refs CI matrix in
  `.github/workflows/ci.yml`.
- `docs/decisions/adr-0023-multi-raft-sharding-deferred.md` — "NOT YET
  WIRED" (deferred); refs `MultiRaftDriverTest` skeleton.
- `docs/decisions/adr-0024-cross-dc-bridge-deferred.md` — "NOT YET
  WIRED" (deferred); tracked as iter-2 follow-up.
- `docs/decisions/adr-0025-on-call-rotation-required.md` — operator
  verification via runbook drill cadence.
- `docs/decisions/adr-0026-otel-deferred.md` — "NOT YET WIRED"
  (deferred); refs `PrometheusExporterTest` for current path.

### Task 2 — Runbook 8-section conformance (DOC-002)

All operational runbooks restructured to: Symptoms, Impact,
Operator-Setup, Diagnosis, Mitigation, Resolution, Rollback,
Postmortem, Related, Do not.

- `ops/runbooks/control-plane-down.md` — full rewrite; ADR cross-ref
  fixed (was wrongly ADR-0014/GC, now ADR-0027/sign-or-fail-close).
- `ops/runbooks/write-commit-latency.md` — full rewrite; ADR cross-ref
  fixed (was wrongly ADR-0007/sim-test, now ADR-0001 + correctly
  ADR-0007 for sim-test reference).
- `ops/runbooks/release.md` — full rewrite; ADR cross-ref fixed (was
  wrongly ADR-0026/OTel, now ADR-0021/Maven + ADR-0022/Java pin with
  TODO marker noting absence of dedicated release-engineering ADR).
- `ops/runbooks/disaster-recovery.md` — restructured via targeted edits
  preserving F4's destructive-confirmation guard and `EXPECTED_CLUSTER`
  prompt.
- `ops/runbooks/edge-read-latency.md` — full rewrite.
- `ops/runbooks/propagation-delay.md` — full rewrite.
- `ops/runbooks/raft-saturation.md` — full rewrite.
- `ops/runbooks/snapshot-install.md` — full rewrite preserving F4's
  TODO PA-XXXX markers for missing `add-server` / `remove-server`
  admin endpoints.
- `ops/runbooks/restore-from-snapshot.md` — full rewrite preserving
  F4's TODO PA-XXXX markers and signature-verify Step.

`ops/runbooks/README.md` (index) and
`ops/runbooks/runbook-conformance-template.md` (template) intentionally
not restructured — neither is an operational runbook.

### Task 3 — Wrong ADR cross-refs (DOC-009 / DOC-010 / DOC-011 / N-010 / N-011)

Folded into Task 2 above (the rewrites contain the corrected refs).

### Task 4 — ADR-0006 superseded marker

- `docs/decisions/adr-0006-event-driven-notifications.md` — Status set
  to `Superseded by ADR-0018 (2026-04-19)` with "Why superseded"
  pointer to ADR-0018 ("Event-Driven Notification System (Server-Side
  Push Streams)") as the authoritative successor. Original `Accepted`
  status preserved under `## Historical Status`.

### Task 5 — CHANGELOG.md scaffold

- `CHANGELOG.md` — created at repo root in Keep-a-Changelog format.
  `## [Unreleased]` block with empty Added/Changed/Deprecated/Removed/
  Fixed/Security subsections; `## [0.1.0] - TBD` placeholder.

### Task 6 — ADR-0027 (sign-or-fail-close)

- `docs/decisions/adr-0027-sign-or-fail-close.md` — created.
  Status=Accepted, date=2026-04-19. Documents
  `ConfigStateMachine.signCommand` catching
  `GeneralSecurityException | IllegalStateException`, clearing
  `lastSignature/lastEpoch/lastNonce`, re-throwing
  `IllegalStateException("fail-close: signing failed for committed
  command", e)`. Verification section points to the real test
  `signFailureFailsClose` in
  `configd-config-store/src/test/java/io/configd/store/ConfigStateMachineTest.java:773`.

## Findings

- **No fabricated tests.** Every test path in every Verification
  section was confirmed via grep before being committed to the ADR.
  Where no test exists, the prescribed "NOT YET WIRED — tracked as
  iter-2 follow-up" template was used (8 ADRs, mostly the
  deferred-feature ADRs 0023/0024/0026 plus operator-setup ADR-0025).
- **No release-engineering ADR exists.** ADR-0026 is OTel-deferred,
  not release engineering. The release runbook now points to ADR-0021
  + ADR-0022 with a `<!-- TODO: confirm correct ADR -->` marker
  noting the gap. Recommend creating an ADR-0028 for release
  engineering (cosign keyless + SLSA provenance) in iter-2.
- **F4 had concurrently edited several runbooks** (TODO PA-XXXX
  markers for missing `add-server` / `remove-server` /
  `/raft/status` endpoints, plus the destructive-confirmation guard
  in disaster-recovery.md). All of those edits were preserved during
  restructuring.
- **Three runbooks needed full rewrites** (snapshot-install,
  restore-from-snapshot, control-plane-down) because the old structure
  had section ordering that could not be incrementally edited into
  conformance without breaking cross-section narrative flow.
- **One write retry needed.** `disaster-recovery.md` Write was rejected
  with "File modified since read" (concurrent F4 edit); switched to
  targeted `Edit` calls preserving F4's destructive guard verbatim.

## Cross-cutting note for triage

Section §8.14 (runbook 8-section skeleton) and §8.15 (every ADR has
Verification) are now structurally satisfied across the entire repo.
Iter-2 should focus on:

1. Wiring the `configd_raft_last_applied_index` gauge (referenced by
   snapshot-install and restore-from-snapshot Resolution sections).
2. Implementing the `POST /admin/raft/{add,remove}-server` HTTP
   endpoints (referenced by snapshot-install Mitigation, marked TODO
   PA-XXXX by F4).
3. Creating ADR-0028 for release engineering to retire the
   `<!-- TODO: confirm correct ADR -->` marker in release.md.
4. Filling in the "NOT YET WIRED" Verification sections for ADRs
   covering deferred features once those features land.
