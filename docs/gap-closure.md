# Gap Closure Plan — GA Hardening

**Phase:** 2 of 12
**Date:** 2026-04-17
**Inputs:** `docs/inventory.md` (Phase 0), `docs/prod-audit.md` + 6 cluster files (Phase 1).
**Output of this plan:** dependency-ordered execution sequence for Phases 3-10, with explicit code-only vs calendar-bounded split. The user directive is: code-only fixes get committed; calendar-bounded gates get a harness + measured real elapsed; nothing yellow gets marked green; nothing fabricated.

---

## 1. Partition

Total surface from Phase 1: 209 PA-IDs filed + 5 R-carries = 214 items. They
partition as follows.

| Partition | Description                                                                                | Count | Phases that own it |
|-----------|--------------------------------------------------------------------------------------------|-------|--------------------|
| **A**     | **Code-only — fixable in this session.** Local + bounded change with a clear test.         | ~135  | 3, 4, 5, 7, 8      |
| **B**     | **Cross-module structural — code-only but multi-PR, ordering matters.**                    | ~25   | 4, 6, 8            |
| **C**     | **Calendar-bounded — harness-only deliverable; yellow until external observation.**         | 5 R-series + 4 PA | 6, 11           |
| **D**     | **Hardening / hygiene (S2, S3) — should ship but not GA-blocking individually.**           | ~45   | 7, 11              |
| **E**     | **Out-of-scope — defer to v0.2 with ADR.**                                                 | 3 R-series        | none (ADR only)   |

The 209 PA-IDs span all four code partitions (A/B/D) plus partition C and
the spec-only items (D/E overlap). The breakdown is:

- **Partition A — code-only:** all 15 S0 + the 64 S1 minus the 12 S1 that
  belong to partition B + a subset of S2 (the rule-10 metric wave). Net ~135.
- **Partition B — structural:** the SLO/observability layer (5 items), the
  snapshot install cohort (5 items), the security perimeter cohort (8 items),
  the supply-chain pipeline (7 items). Net ~25.
- **Partition C — calendar-bounded:** R-08 (72h soak), R-09 (7d burn), R-10
  (30d longevity), R-11 (14d shadow), R-12 (external on-call). Plus the 4
  PA-IDs that depend on the harness existing (PA-5004 dashboard files —
  produced once observability is wired).
- **Partition D — hygiene:** the residual S2 + S3 from cluster files not
  pulled into A. Bug-fix release after GA.
- **Partition E — defer:** R-06 (multi-Raft), R-07 (cross-DC bridge), and
  the v0.2 stretch items.

## 2. Dependency DAG

The dependency graph below is the executable order. Each node is a "work
unit" (one commit or a small batch). The first letter encodes the layer
(F=foundation, S=security, N=snapshot, D=distribution, O=observability,
A=API/server, B=build/supply-chain, T=spec/TLA+, C=calendar-bounded). Edges
mean "depends-on".

```
Foundation (F) — must land first; everything else depends on these primitives.
  F1: project-wide HLC unification helper       (lifts PA-1001, PA-1002 + supports PA-2005/2006 fix)
  F2: BoundedExecutor + Bounded queues utility  (unblocks every "unbounded queue" finding)
  F3: MetricRegistry.counter(name).inc(...) helper + JSON dump endpoint exists
       — unblocks the rule-10 cohort (≥ 25 fixes)
  F4: HLC-aware tick scheduler / TickClock      (PA-2005 / PA-2006 tick-vs-ms collapse)

Security perimeter (S) — depends on F3 for metrics; F1 for HLC-bound nonces.
  S1: DeltaVerifier API spec + impl              (closes R-01 / PA-3001 root cause)
       └─ S1a: ConfigDelta carries (clusterId, epoch, keyId, monotonicNonce) — F-0052 §Suggested
       └─ S1b: SigningKeyStore exposes (key-id, public-key, not-before, not-after) bundle
       └─ S1c: PlumtreeNode.receiveEagerPush gates on verify+nonce
       └─ S1d: CatchUpService.recordDelta gates on verify+nonce
       └─ S1e: WatchService.onConfigChange gates on verify
       └─ S1f: FanOutBuffer.append only accepts VerifiedDelta wrapper
       └─ S1g: DeltaApplier insecure default removed → factory `DeltaApplier.insecure(...)`
  S2: SubscriptionAuthorizer                      (PA-3011, PA-3021)
       └─ S2a: subscribe-time check
       └─ S2b: dispatch-time re-check (revocation takes effect)
       └─ S2c: catch-up filter by subscription set
  S3: ConfigStateMachine.signCommand fail-close   (PA-1004; depends on S1)
  S4: TcpRaftTransport peer-id binding             (PA-4007 — bind senderId to TLS cert subject)
  S5: TLS reload rebuilds SSLServerSocket         (PA-4005)
  S6: Auth-token requires TLS at startup          (PA-4004)
  S7: AclService root principal override          (PA-4011)
  S8: AuditLogger implementation                  (R-13 / PA-4001 — depends on F3)
  S9: Codec attacker-length bounds                (PA-1003, PA-4017 — depends on F3)

Snapshot subsystem (N) — depends on F2 (bounded transfer).
  N1: SnapshotChunk has CRC32C                    (PA-2001)
  N2: RaftNode.sendInstallSnapshot uses chunks    (PA-2002 → wire the existing infra)
  N3: handleInstallSnapshot honours offset/done   (PA-2008)
  N4: InstallSnapshot retry / timeout / drop      (PA-2004)
  N5: acceptChunk total-size cap                  (PA-2007 — depends on F2)
  N6: assemble single-pass (no double materialise) (PA-2025)
  N7: applyCommitted poison handling              (PA-2003 — depends on F3 metric)

Distribution layer (D) — depends on F2 (bounded queues), F3 (metrics), S1 (verify).
  D1: WatchService dispatch try/catch + cursor-after-deliver   (PA-3002, PA-3003)
  D2: WatchService per-watcher bounded queue + work-stealing pool (PA-3007 — depends on F2)
  D3: WatchCoalescer pending hard cap                          (PA-3008 — depends on F2)
  D4: SlowConsumerPolicy per-ack decrement + hysteresis        (PA-3004)
  D5: SlowConsumerPolicy reconnect backoff                     (PA-3005)
  D6: SlowConsumerPolicy.recordSend returns drop signal        (PA-3006)
  D7: PlumtreeNode dedup window TTL                            (PA-3012 — depends on F1 HLC)
  D8: PlumtreeNode outbox + lazyNotifications bounded          (PA-3015 — depends on F2)
  D9: HyParView active-view eviction policy                    (PA-3016)
  D10: HyParView isolation recovery + persistent passive view  (PA-3017)
  D11: HyParView built-in shuffle ticker                       (PA-3018)
  D12: HyParView FailureDetector contract                      (PA-3019 — wired to SWIM)
  D13: SubscriptionManager radix-trie prefix index             (PA-3010)
  D14: RolloutController emits revert mutation on rollback     (PA-3033)
  D15: Distribution-package metrics                            (PA-3037 — depends on F3)
  D16: FanOutBuffer ring epoch-tagged for wrap detection       (PA-3028)

Observability (O) — depends on F3 metric registry.
  O1: SloTracker latency-percentile type           (STRUCTURAL — PA-5003)
  O2: Wire tracker.recordSuccess/Failure at write/read/propagation handlers (PA-5002 — depends on O1)
  O3: Schedule BurnRateAlertEvaluator on tickExecutor (PA-5001 — depends on O2)
  O4: Multi-window / multi-burn-rate evaluator (PA-5007 — depends on O3)
  O5: DefaultHistogram bounds & ring race fix     (PA-5008, PA-5009, PA-5010)
  O6: Prometheus exposition uses histogram, not summary (PA-5015)
  O7: PII-safe log + cardinality guard            (PA-5012, PA-5013, PA-5019)
  O8: OpenTelemetry / tracing surface             (PA-5018 — Phase 8 deliverable)
  O9: Runtime invariant assertion bridge          (PA-5006 — depends on O1, F3)
  O10: Alert-rule generator → ops/prometheus/*.yml + ops/grafana/*.json (PA-5004 — Phase 8)

Server / control-plane / transport (A) — depends on F2 (bounded queue), F3 (metrics).
  A1: HTTP body size cap + socket timeouts        (PA-4002, PA-4003)
  A2: ConfigdServer.start transactional unwind    (PA-4015)
  A3: Wire AdminService to /admin/*               (PA-4012)
  A4: Per-cluster rate limit                      (PA-4013)
  A5: readDispatchExecutor bounded queue + reject policy (PA-4014 — depends on F2)
  A6: PeerConnection.sendFrame non-blocking       (PA-4019)
  A7: TcpRaftTransport dedup outbound reader      (PA-4020)
  A8: TLS reload failure metric + alert           (PA-4006 — depends on F3)
  A9: TlsConfig CLI flag for keystore password    (PA-4022)
  A10: Health endpoint redacts cluster topology   (PA-4009)

Build / CI / supply-chain (B) — independent of code; depends only on tools.
  B1: .gitignore expansion + spec/states/ removed from tracking (PA-6001, PA-6004)
  B2: tla2tools.jar SHA pin in spec/build.sh + checksum file    (PA-6002)
  B3: maven-wrapper.properties distributionSha256Sum            (PA-6007)
  B4: CI invokes ./mvnw not system mvn                          (PA-6006)
  B5: Syft/CycloneDX SBOM in CI + release artifact              (PA-6008)
  B6: Cosign / Sigstore image signing in release pipeline       (PA-6009)
  B7: Trivy + OWASP Dependency-Check in CI                      (PA-6010)
  B8: Docker base images pinned by sha256 digest                (PA-6011)
  B9: K8s manifest pins image digest                            (PA-6025)
  B10: SecurityContext + readOnlyRootFilesystem                  (PA-6024, PA-6028)
  B11: spotbugs-exclude.xml — replace blanket suppressions       (PA-6029)
  B12: ADR for `--enable-preview` in production                  (PA-6035)

Spec / TLA+ (T) — independent; depends on Apalache install for one item.
  T1: ConsensusSpec.tla — model ReadIndex                       (PA-5023, R-14b)
  T2: ConsensusSpec.tla — model snapshot install                (PA-5027)
  T3: Stale-doc cleanup: NoStaleOverwrite + VersionMonotonicity references (PA-5022, PA-5024, PA-5030)
  T4: Apalache MonotonicCommit (replaces TLC spurious counter)  (PA-5020, PA-5021, R-03)
  T5: Liveness invariants documented as un-checked              (PA-5025 explicitly)

Calendar-bounded (C) — harness-only; produces yellow with measured elapsed.
  C1: 72-h soak harness — `perf/soak-72h.sh`                    (R-08)
  C2: 7-d burn harness — `perf/burn-7d.sh`                      (R-09)
  C3: 30-d longevity harness — `perf/longevity-30d.sh`          (R-10)
  C4: 14-d shadow-traffic harness — `chaos/scenarios/shadow-14d.yaml` (R-11)
  C5: External on-call bootstrap checklist — `docs/runbooks/oncall-bootstrap.md` (R-12)
       (cannot run from inside the session; harness = the checklist + drill template)
```

## 3. Critical-path schedule

The order below is the executable schedule. Items in `[brackets]` are
parallelisable.

1. **Foundation pre-amble (small, fast, blocks everything else).**
   `[F1, F2, F3, F4]` in one batch — they touch shared utilities.
2. **S1: DeltaVerifier** + **S2: Authorizer** + **S3: signCommand fail-close**
   in one security-foundation commit. These three together close R-01 root
   cause and the secondary leaks.
3. `[S4, S5, S6, S7, S8, S9]` — the rest of the security perimeter, each
   self-contained.
4. `[D1, D2, D3, D4, D5, D6, D7, D8, D9, D10, D11, D13, D14, D15, D16]` —
   distribution-layer fixes, each self-contained after the F + S
   foundation. D2 depends on D1; D8 depends on F2; D13 is independent.
5. `[N1, N2, N3, N4, N5, N6, N7]` — snapshot subsystem in one cohesive
   commit (chunked path + CRC + retry + size cap + poison handling).
6. `[A1, A2, A3, A4, A5, A6, A7, A8, A9, A10]` — server / API hardening,
   each self-contained.
7. **O1 (structural) → O2 → O3 → O4 → O5 → O6 → O7 → O9 → O10** —
   observability layer top-down. O8 (OpenTelemetry) is Phase 8.
8. `[B1..B12]` — supply-chain / build hardening (independent of code; can
   run in parallel with anything above).
9. `[T1, T2, T3, T5]` — TLA+ work; T4 (Apalache) parallel if Apalache
   installs cleanly.
10. `[C1..C5]` — harness creation. Run what fits in-session; record
    measured elapsed; mark **yellow**.

## 4. Calendar-bounded handling (yellow rules)

Per the user directive: **do not mark these green; do not round up;
record measured elapsed**.

| Gate                          | Harness                                | Real-elapsed in this session             | Status     |
|-------------------------------|----------------------------------------|------------------------------------------|------------|
| 72-h soak                     | `perf/soak-72h.sh`                     | up to in-session budget (e.g. 30-60 min) | **YELLOW** |
| 7-d burn-in                   | `perf/burn-7d.sh`                      | smoke-only (5-15 min)                    | **YELLOW** |
| 30-d longevity                | `perf/longevity-30d.sh`                | smoke-only (5-15 min)                    | **YELLOW** |
| 14-d shadow-traffic           | `chaos/scenarios/shadow-14d.yaml`      | smoke-only (5-15 min)                    | **YELLOW** |
| External on-call bootstrap    | `docs/runbooks/oncall-bootstrap.md` + drill template | n/a — humans witness   | **YELLOW** |

The `ga-review.md` row for each calendar gate must read:
`YELLOW — harness committed at $path; measured elapsed in-session = Xs;
remaining budget = Y; full duration requires external observation drill;
do not mark GREEN until that drill is signed off by the on-call lead.`

## 5. Out-of-scope (ADR-deferred to v0.2)

Each requires an ADR with:
(a) why it's out of scope for v0.1 GA,
(b) the failure modes that v0.1 explicitly does not cover,
(c) the deployment caveat that goes in the README.

| Item                     | ADR target           | Failure mode v0.1 doesn't cover                                         |
|--------------------------|----------------------|--------------------------------------------------------------------------|
| R-06 multi-Raft sharding | ADR-0023             | Single-Raft cluster scales to ~10K writes/s; multi-tenant scale needs sharding. |
| R-07 cross-DC bridge     | ADR-0024             | One Raft per DC + async bridge; v0.1 supports only one DC = one Raft.    |
| R-12 external on-call    | (runbook) ADR-0025   | "Production-ready" requires a 24/7 on-call rotation; v0.1 docs the requirement but the rotation is operator-procured. |

## 6. Phase mapping

How partition A/B/C/D items map to the original 12 phases:

| Phase                            | Items                                                                  |
|----------------------------------|------------------------------------------------------------------------|
| Phase 3 — formal verification    | T1, T2, T3, T4, T5                                                      |
| Phase 4 — test pyramid           | tests for every A1-A10, D1-D16, S1-S9, N1-N7 fix; JCStress for D16 / O5; jqwik for codec bounds |
| Phase 5 — chaos automation       | snapshot-drop-Nth-chunk, slow-consumer death spiral, partition healing, slow-peer freeze (PA-4019), TLS reload races (PA-4005) |
| Phase 6 — performance & capacity | C1, C2, C3 + JMH for D13 (radix trie), O5 (lock-free histogram) |
| Phase 7 — security & supply chain| S1-S9 + B1-B12 + ADR-0023..0025                                         |
| Phase 8 — observability          | O1-O10 + alert-rule generator + Grafana dashboards + OpenTelemetry      |
| Phase 9 — release engineering    | B5-B11 + signed image + provenance attestation                          |
| Phase 10 — disaster recovery     | C4 (shadow-traffic harness), runbook conformance template, restore drills |
| Phase 11 — GA review             | green/yellow/red per gate, doc-drift purge, ga-review.md (do NOT sign)  |

## 7. Exit criteria for Phase 2

This plan is complete; Phase 3 starts now. The criteria for Phase 2 done:

- [x] Every PA-ID from `prod-audit.md` is mapped to a partition (A/B/C/D/E).
- [x] Every Partition A/B item has a work-unit ID (F#, S#, N#, D#, A#, B#, O#, T#, C#).
- [x] Dependency edges are explicit; the DAG is executable.
- [x] Calendar-bounded items are scoped to "harness only" + yellow-rule
      stated.
- [x] Out-of-scope items have an ADR target.
- [x] Phase mapping is consistent with the original 12-phase brief.

Phase 3 (formal verification closure) starts on the T-cluster now;
in parallel, the Foundation (F1-F4) batch can land since it has no
external dependency.
