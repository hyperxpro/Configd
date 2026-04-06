# Phase 0 — Inventory Reconciliation

**Date:** 2026-04-17
**Scope:** Reconcile current code (`HEAD = 22d2bf3`, 2026-04-17 21:00 UTC) against the three prior hardening passes — `docs/prr/` (2026-04-11), `docs/battle-ready/` (2026-04-11), `docs/verification/` (Round 1 2026-04-14 + Round 2 2026-04-16).
**Purpose:** Establish ground truth before Phase 1 audit. Identify closed / stale / open items so the audit does not rediscover already-fixed issues and does not take already-stale doc claims as load-bearing.

---

## 1. Current Code Baseline

Measured from the working tree at `22d2bf3`:

| Module | Main files | Main LoC | Test files | Test LoC | Role (verified by file reading) |
|---|---:|---:|---:|---:|---|
| configd-common | 11 | 748 | 7 | 1,044 | `HybridClock` (packed long), `FileStorage`, `InMemoryStorage`, `NodeId`, `Buggify`, `ConfigScope`, `Storage` iface |
| configd-config-store | 14 | 2,948 | 13 | 3,897 | `HamtMap`, `VersionedConfigStore`, `ConfigStateMachine`, `ConfigSigner`, `SigningKeyStore`, `CommandCodec`, `Compactor`, `ConfigValidator`, `ReadResult`, `ConfigDelta`, `DeltaComputer` |
| configd-consensus-core | 21 | 3,186 | 8 | 4,133 | `RaftNode` (1,341 L), `RaftLog` (340 L), `ReadIndexState`, `ClusterConfig`, `DurableRaftState`, `SnapshotState`, message records |
| configd-control-plane-api | 7 | 874 | 5 | 639 | `ConfigReadService`, `ConfigWriteService`, `AdminService`, `HealthService`, `RateLimiter`, `AuthInterceptor`, `AclService` |
| configd-distribution-service | 10 | 2,067 | 9 | 2,220 | `PlumtreeNode`, `HyParViewOverlay`, `FanOutBuffer`, `WatchService`, `WatchCoalescer`, `SubscriptionManager`, `CatchUpService`, `SlowConsumerPolicy`, `RolloutController`, `WatchEvent` |
| configd-edge-cache | 9 | 1,328 | 9 | 2,170 | `LocalConfigStore`, `EdgeConfigClient`, `DeltaApplier`, `StalenessTracker`, `VersionCursor`, `BloomFilter`, `PoisonPillDetector`, `PrefixSubscription`, `EdgeMetrics` |
| configd-observability | 7 | 1,098 | 7 | 1,327 | `MetricsRegistry`, `SloTracker`, `InvariantMonitor`, `ProductionSloDefinitions`, `BurnRateAlertEvaluator`, `PrometheusExporter`, `PropagationLivenessMonitor` |
| configd-replication-engine | 5 | 1,028 | 5 | 1,570 | `MultiRaftDriver`, `ReplicationPipeline`, `HeartbeatCoalescer`, `FlowController`, `SnapshotTransfer` |
| configd-server | 5 | 1,658 | 3 | 1,368 | `ConfigdServer` (assembly), `ServerConfig`, `HttpApiServer`, `RaftMessageCodec`, `RaftTransportAdapter` |
| configd-testkit | 10 | 1,114 | 6 | 3,133 | `RaftSimulation`, `SimulatedNetwork`, `SimulatedClock`, 7 JMH benchmarks (moved to `src/main` per F-0043) |
| configd-transport | 9 | 1,250 | 7 | 1,261 | `TcpRaftTransport`, `TlsManager`, `TlsConfig`, `FrameCodec`, `BatchEncoder`, `MessageRouter`, `ConnectionManager`, `MessageType`, `RaftTransport` iface |
| **TOTAL** | **108** | **17,399** | **79** | **22,762** | 40,161 Java LoC |

### Build & runtime

| Property | Value | Source |
|---|---|---|
| Build | Maven 3.9 multi-module (root `pom.xml`) | `pom.xml` |
| Java | 25 (`maven.compiler.release=25`, `--enable-preview`) | `pom.xml:28,101-105` |
| SpotBugs | 4.9.3.0 pinned with ASM 9.9.1 (for class-file major 69) | `pom.xml:143-198` |
| HdrHistogram | 2.2.2 | `pom.xml:63-66` |
| Micrometer | 1.14.4 (declared; uses verified below) | `pom.xml:68-71` |
| Agrona / JCTools | declared in `<dependencyManagement>` only | `pom.xml:37-46` (F-0075 residual — no `.java` imports reference them) |
| TLA+ | TLC 1.8.0 (`spec/tla2tools.jar`); `ConsensusSpec.tla` 505 L | `spec/` |

### Documentation surface

| Dir | Artifacts | Purpose |
|---|---|---|
| `docs/decisions/` | 22 ADRs (0001-0022) | Architectural decisions |
| `docs/prr/` | 10 reports (2026-04-11) | Production Readiness Review — first formal pass |
| `docs/battle-ready/` | 8 reports (2026-04-11) | Hardening / pre-GO pass — closed all PRR findings |
| `docs/certification/` | 3 reports (2026-04-11) | Independent certification audit |
| `docs/audit/` | 12 reports (2026-04-13) | Code-audit sub-teams (concurrency, consensus, transport, etc.) |
| `docs/verification/` | 1 final-report + 18 findings (2026-04-14, 2026-04-16 Round 2) | Adversarial re-verification |
| `docs/runbooks/` | 8 runbooks | cert-rotation, edge-catchup-storm, leader-stuck, poison-config, reconfiguration-rollback, region-loss, version-gap, write-freeze |
| `docs/wiki/` | 7 pages (2026-04-10) | Design background |
| Root-level docs | architecture, audit, consistency-contract, gap-analysis, performance, production-deployment, research, rewrite-plan, security-heap-dump-policy | Design corpus |

---

## 2. Reconciliation Against Prior Passes

### 2.1 Claim map

The three prior passes made overlapping claims about what is done. This section inspects each claim against the current code and records the verdict. "Verified" means I read the code; "Stale" means the claim was correct when made but the code has moved; "Open residual" means the prior pass itself acknowledged the gap.

| Claim source | Claim | Verdict | Evidence (file:line or §ref) |
|---|---|---|---|
| battle-ready/verdict §2.1 | "Zero open BLOCKER/MAJOR findings, 37/37 closed" | **Verified** at 2026-04-11 baseline | `docs/battle-ready/finding-closure-report.md` §Summary table |
| battle-ready/verdict §2.3 | "Zero allocation on hot read path" | **Refuted 2026-04-16, re-fixed 2026-04-16** | `configd-common/.../HybridClock.java` (packed long); `configd-config-store/.../VersionedConfigStore.java` `getInto(...)` API; regression tests `HybridClockAllocationTest`, `VersionedConfigStoreAllocationTest` |
| battle-ready/verdict §2.4 | "mTLS enforced between cluster members" | **Refuted 2026-04-16 (F-0050/F-0051), re-fixed 2026-04-16** | `configd-transport/.../TlsManager.java` now threaded through `TcpRaftTransport`; `ConfigdServer.start` fail-closes if TLS flag set without certs; regression `TcpRaftTransportTest.find0051_clientHandshakeRejectsCertWithWrongHostname` |
| battle-ready/verdict §2.4 | "Signing wired through state machine" | **Partially refuted, partially re-fixed** | Ed25519 key now persisted via `SigningKeyStore`; `ConfigDelta` carries `(epoch, nonce)` in the signed payload; `DeltaApplier` fail-closes on signed deltas without a verifier; **residual**: `PlumtreeNode`/`HyParViewOverlay`/`FanOutBuffer`/`WatchService`/`CatchUpService`/`SubscriptionManager` still forward without per-hop verification — see F-0052.md §Residual |
| battle-ready/verdict §2.4 | "Rate limiter active" | **Verified** | `configd-control-plane-api/.../RateLimiter.java` + `ConfigdServer` wiring; docs+code aligned at 10k/s (F-0054 closed) |
| battle-ready/verdict §2.4 | "Auth on all endpoints" | **Verified with one past-refutation closed** | `AuthInterceptor` + `AclService`; `/metrics` now requires auth when `--auth-token` set (F-0055 closed); `/health/*` intentionally public |
| verification/final-report §V6 | "HAMT get p50 < 50ns" | **Refuted, retuned** | F-0040 — avgt was tail-skewed; `Mode.SampleTime` measured p50 80 ns / p99 170 ns / p999 800 ns at 10K; targets retuned via `docs/verification/decisions/vdr-0002.md`. Original 50 ns target now **stale** in any doc that cites it |
| verification/final-report §V7 | "Signed-chain end-to-end" | **Narrowed** | Only `DeltaApplier`-gated hops verify. Gossip hops unverified (F-0052 residual) |
| verification/final-report §V8 | "Doc-vs-code drift reconciled" | **Verified** for ADRs 0009/0010/0014/0016 | ADR-0009 + ADR-0014 are marked Superseded/revised; ADR-0022 documents the Java 25 pivot; ADR-0021 documents the Maven pivot |
| PRR §0 | "System cannot be deployed, no transport" | **Obsolete** | `TcpRaftTransport` exists (`configd-transport/.../TcpRaftTransport.java`) and is wired through `ConfigdServer:170-171` |
| PRR §1.7 | "No AclService, no AuditLogger" | **AclService present; AuditLogger still missing** | `AclService.java` + `AclServiceTest` (16 tests). **No `AuditLogger.java` file exists** — verified by glob. This is an **open item** carried forward to Phase 1 audit |
| PRR §2 SC-17 | "Jepsen-class chaos matrix" | **Open** — partition matrix is ~53% covered in deterministic simulation, no wire-level Jepsen | `docs/verification/inventory.md` §5 |
| PRR §5 | "No value size enforcement" | **Closed** | `ConfigWriteService` checks `MAX_VALUE_SIZE`; `CommandCodec.MAX_BATCH_COUNT=10000`; regression `ConfigWriteServiceTest` |
| PRR §6 R11 | "TLA+ liveness commented out" | **Open** — unchanged | `spec/ConsensusSpec.cfg:*` — `EdgePropagationLiveness` still commented. Bounded model yields a spurious violation (documented); Apalache symbolic check not attempted |
| verification/inventory §SPEC-GAP-1 | "`NoStaleOverwrite` is byte-identical to `StateMachineSafety`" | **Open** — unchanged | `spec/ConsensusSpec.tla` — dedup needed |
| verification/inventory §SPEC-GAP-3/4 | "`VersionMonotonicity` and `ReadIndex` not modeled in TLA+" | **Open** — unchanged | Runtime assertion only via `InvariantMonitor` |

### 2.2 Closed finding register (rolled up)

Consolidated from all prior passes. Findings listed here MUST NOT be rediscovered in the Phase 1 audit unless I see live regression in the code.

**PRR-era (FIND-0001 → FIND-0025) — 25 findings, all Closed.** Source: `docs/battle-ready/finding-closure-report.md` §Re-verification. 19 fixed at PRR time + 6 fixed during hardening.

**Hardening-era (NEW-001 → NEW-012) — 12 findings, all Closed.** Source: same file §New Issues.

**V1 verification (F-0009 → F-0014) — 6 findings, all Closed.** Source: `docs/verification/inventory.md` §7. Includes the P0 linearizable-read fix and P0 `ReadIndexState` race.

**V2 (Round-2) verification (F-0020 → F-0075) — 21 findings, all Closed.** Source: `docs/verification/final-report.md` §Remediation register. Includes `TcpRaftTransport` mTLS wiring, hostname verification, persisted signing key with replay protection, zero-alloc `getInto` API, SpotBugs configured, `/metrics` auth, `maven-jar-plugin` pinned.

**Certification audit disputes** — `docs/certification/disputes/` — reviewed in certification `verdict.md`; all resolved.

Total closed: **64 findings** with regression tests.

### 2.3 Open residuals (carry-forward, by authority of the prior pass)

These are gaps the prior passes filed and explicitly did not close. They remain open as of `22d2bf3`.

| ID | Title | Source | Why not closed | Carries to Phase |
|---|---|---|---|---|
| R-01 | Distribution-hop signature verification | F-0052.md §Residual | Requires API change in `PlumtreeNode`/`HyParView`/`CatchUpService` to plumb the verifier through | Phase 7 (security) code-only |
| R-02 | Unused `agrona` / `jctools` in `<dependencyManagement>` | F-0075 residual | Hygiene; no function impact | Phase 7 code-only |
| R-03 | Liveness property `EdgePropagationLiveness` unchecked | `spec/ConsensusSpec.cfg`; verification §V2 | TLC with bounds produces spurious violation; Apalache symbolic-bounds attempt is calendar-heavy | Phase 3 formal — attempt; mark yellow if Apalache not reachable in-session |
| R-04 | 72-hour soak test | battle-ready verdict §2.3 | Calendar-bounded | Phase 6 — write harness, run what fits, record actual elapsed |
| R-05 | Wire-level Jepsen harness | verification §V5 | Calendar + infra | Phase 5 — build a real-network fault injector as far as fits |
| R-06 | Game days (MTTD/MTTM measured) | battle-ready §2.2 | Requires staging + on-call | Phase 5 — scripted; yellow |
| R-07 | Cert rotation live drill | battle-ready §2.4 | Requires running cluster | Phase 7 — harness-only; yellow |
| R-08 | Canary automation e2e | battle-ready §2.6 | Requires staging | Phase 9 — harness-only; yellow |
| R-09 | Multi-version wire-format compat matrix | battle-ready §2.6 | Requires a prior release | Phase 9 — harness + N-vs-N matrix skeleton |
| R-10 | SBOM + artifact signing | verification §V7 | Sigstore integration is post-launch per prior ADR | Phase 7 — generate SBOM now; signing stays as future work |
| R-11 | On-call rotation / new-engineer bootstrap | battle-ready §2.5 | Organizational | Phase 11 — document; yellow |
| R-12 | Capacity headroom validated at scale | battle-ready §2.5 | Infra | Phase 6 — what fits in a single JVM; extrapolation documented; yellow for ≥3× targets |
| R-13 | `AuditLogger` planned but never implemented | PRR §1.7 | **Never started** | Phase 7 — code-only, implement |
| R-14 | INV-W1 / INV-W2 / INV-RYW1 TLA+ modeling | verification §SPEC-GAP-3/4 | Modeling effort | Phase 3 — attempt; yellow if not reachable |
| R-15 | `NoStaleOverwrite` dedup | SPEC-GAP-1 | Trivial | Phase 3 code-only |

### 2.4 Stale documentation (to be refreshed or deleted)

Documents with load-bearing claims contradicted by the current code. Phase 2 will decide per-doc whether to refresh, archive, or delete.

| Doc | Why stale | Action |
|---|---|---|
| `docs/rewrite-plan.md` | Plans Gradle, Spring Boot, Netty, gRPC — none present | Archive or annotate as historical |
| `docs/prr/inventory.md` §0 | "Transport layer has no real I/O" — obsolete since `TcpRaftTransport` | Annotate as point-in-time |
| `docs/prr/inventory.md` §2 SC-10, SC-19 | "Cannot be deployed" — obsolete | Annotate |
| `docs/performance.md` — §with 50 ns HAMT target | Target retuned via VDR-0002 to 80 ns p50 | Align with VDR-0002 numbers |
| `docs/decisions/adr-0010-netty-grpc-transport.md` | Implementation is plain Java TCP + virtual threads, not Netty/gRPC | Verify status flag says Superseded; if not, correct |
| `docs/decisions/adr-0016-swim-lifeguard-membership.md` | HyParView used, not SWIM | Verify status flag |

### 2.5 New/changed code since last verification (2026-04-16 → 2026-04-17)

The single commit `22d2bf3` (2026-04-17 21:00 UTC) changed 385 files with +6,461 / -11,836 lines. Diff breakdown by touched source file:

| File (main) | Δ LoC | Phase 1 focus |
|---|---:|---|
| `configd-common/.../HybridClock.java` | +176 | verify packed-long concurrency |
| `configd-common/.../FileStorage.java` | +15 | verify fsync on rename |
| `configd-config-store/.../ConfigDelta.java` | +87 | epoch+nonce field layout |
| `configd-config-store/.../ConfigStateMachine.java` | +119 | bounds-check snapshot restore |
| `configd-config-store/.../SigningKeyStore.java` | new (+177) | keypair persistence path & perms |
| `configd-config-store/.../VersionedConfigStore.java` | +39 | `getInto` allocation audit |
| `configd-consensus-core/.../RaftLog.java` | +6 | fsync on truncate |
| `configd-consensus-core/.../RaftNode.java` | +86 | `whenReadReady` callback — read-dispatch rework |
| `configd-edge-cache/.../DeltaApplier.java` | +74 | fail-closed on signed-delta-no-verifier |
| `configd-edge-cache/.../LocalConfigStore.java` | +66 | INV-M1 runtime assertion + `getInto` |
| `configd-edge-cache/.../StalenessTracker.java` | +48 | INV-S1 runtime assertion |
| `configd-observability/.../InvariantMonitor.java` | +78 | INV-M1 + INV-S1 bridges |
| `configd-server/.../ConfigdServer.java` | +279 | three-executor split; TLS reload; signer wiring |
| `configd-server/.../HttpApiServer.java` | +27 | /metrics auth |
| `configd-server/.../ServerConfig.java` | +11 | `--signing-key-file` flag |

Test files added/changed: `FileStorageTest`, `HybridClockAllocationTest`, `HybridClockTest`, `ConfigStateMachineTest`, `HamtMapCollisionTest` (+524), `VersionedConfigStoreAllocationTest`, `VersionedConfigStoreConcurrencyTest`, `RaftLogWalTest`, `DeltaApplierTest`, `LocalConfigStoreTest`, `StalenessTrackerTest`, `InvariantMonitorTest`, `ConfigdServerTest` (+501).

The commit also checked in `verification-runs/tlc-rerun.log` and `verification-runs/mvn-test-baseline.log` (evidence artefacts), and the `spec/states/…` TLC on-disk fingerprint set (~225 MB; probably should be `.gitignore`d — open item for Phase 1).

**No functional code regressions observed at the file-diff level**; all deltas correspond to V2 remediation.

---

## 3. Phase 1 Audit — Scope Derived from this Inventory

Based on §2, the Phase 1 audit must focus on:

1. **New code since 2026-04-16** (§2.5) — 15 files, highest concentration in `ConfigdServer.java`, `RaftNode.java`, `HybridClock.java`, `SigningKeyStore.java`. Verify the fixes match the claims in the final-report remediation register.
2. **Open residuals R-01, R-02, R-13, R-15** (§2.3) — code-only items that should not block audit.
3. **Every module's current code** for §12 hard-rule violations (locks on read path, allocation in hot loops, unowned alerts, TODOs in safety paths, plaintext secrets, silent swallows, unbounded queues). Prior audits are not a substitute — I must re-verify on current code.
4. **Stale doc claims** (§2.4) — record each contradiction as an S3 doc finding.
5. **Spec gaps** — SPEC-GAP-1 through SPEC-GAP-5 (verification/inventory §2).
6. **Security**: per-hop verification (R-01), `AuditLogger` absence (R-13), `--enable-preview` on production (preview API stability risk).

Audit will proceed module-by-module with subagents in parallel. Findings will be written to `docs/prod-audit.md`.

---

## 4. What Is Explicitly Out of Scope for This Pass

- Re-running the 13.8M-state TLC check: already re-run and byte-exactly reproduced on 2026-04-16 (`docs/verification/runs/tlc-round2-rerun.log`); a third re-run adds no signal unless `ConsensusSpec.tla` changes.
- Re-running the full 21,285-test suite for baseline verification: `verification-runs/mvn-test-baseline.log` is the committed evidence. I will run the suite after code-only fixes in later phases.
- Re-opening Closed findings on the prior passes' word unless I find live code contradicting the closure claim.
- Changing ADRs 0009/0010/0014/0016 in this phase — those have already been reconciled in Round 2.

---

## 5. Sign-off

- Author: Claude Opus 4.7, autonomous pass, 2026-04-17
- Ground: file contents at `22d2bf3`
- Reviewed-by: **n/a** (autonomous). Review by a human SRE remains required before any GA claim — see `docs/ga-review.md` Phase 11.
