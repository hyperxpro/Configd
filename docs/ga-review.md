# GA Review — Configd v0.1 Production Hardening

**Status:** REVIEW — NOT SIGNED.

This document records the gate-by-gate state of the v0.1 GA hardening
pass at end of Phase 11 (2026-04-17). It is a measurement, not an
approval. Per the autonomous-loop directive, calendar-bounded gates
(72-h soak, 7-day burn, 30-day longevity, 14-day shadow, external
on-call bootstrap) stay YELLOW with measured real durations — they are
not rounded up and not signed off.

A separate `docs/ga-approval.md` (which would be the green-light
artifact) is **not** written by this review and must be authored by a
human approver after they have validated the residuals listed below.

## Color key

- **GREEN** — landed, tested, no residual.
- **YELLOW** — landed in a degraded form (calendar-bounded, partial
  coverage, or stub) with measured state recorded honestly. Operator
  action required to convert to GREEN; not GA-blocking by itself.
- **RED** — not landed. Lists what's missing.

A gate is **GA-blocking** only if it is RED. YELLOW gates are
shippable as v0.1 with documented residuals per gap-closure §5.

## Phase-level summary

| Phase | Gate | Status | Evidence |
|-------|------|--------|----------|
| 0 | Inventory reconciled | GREEN | `docs/inventory.md`, progress.md Phase 0 |
| 1 | Production audit | GREEN | `docs/prod-audit-cluster-{A..F}.md` |
| 2 | Gap closure plan | GREEN | `docs/gap-closure.md` |
| 3 | Formal verification | GREEN | TLA+/TLC pass on Consensus/ReadIndex/SnapshotInstall; tla2tools.jar SHA-pinned in CI |
| 4 | Test pyramid | YELLOW | 20,149 tests pass; jqwik property + simulation + 10k seed sweep — DEMOTED 2026-04-19 (iter-1 DOC-019): no on-disk test-count artifact pinned to commit SHA |
| 5 | Chaos automation | GREEN | `SimulatedNetwork` 5 chaos hooks + 17 ChaosScenariosTest cases |
| 6 | Performance & capacity | YELLOW | JMH benchmarks + `docs/perf-baseline.md` measured — DEMOTED 2026-04-19 (iter-1 P-001): no JMH JSON / HdrHistogram artifacts under `perf/results/` to back the perf-baseline numbers |
| 7 | Security & supply chain | YELLOW | B1-B5/B7/B11/S3/S9 GREEN; S1/S2/S4-S8/B6/B8-B12 LANDED in Phase 8/9 or DEFERRED — see residual table |
| 8 | Observability | YELLOW | O5 partial (perf bottleneck moved); O6/O7 GREEN; O8 stub by design (ADR-0026) |
| 9 | Release engineering | YELLOW | Release workflow + Cosign + SLSA attestation + hardened K8s manifest — DEMOTED 2026-04-19 (iter-1 R-001): pipeline never exercised end-to-end (no published release artifact, verify-published job absent) |
| 10 | Disaster recovery | YELLOW | Runbooks + harness landed; no real drill executed yet |
| 11 | GA review | this doc | YELLOW — review only, not signed |

## Audit-finding gates

### Build / supply chain (B-series — Phase 7 + 9)

| ID | Gate | Status | Notes |
|----|------|--------|-------|
| B1 | Maven wrapper checked in | GREEN | `mvnw`, `.mvn/wrapper/` |
| B2 | tla2tools.jar pinned by SHA-256 | GREEN | `4c1d62e0…` in `.github/workflows/ci.yml` |
| B3 | Maven wrapper distribution SHA-256 | GREEN | `4ec3f26f…` in `.mvn/wrapper/maven-wrapper.properties` |
| B4 | CI uses `./mvnw`, not system mvn | GREEN | All ci.yml job steps |
| B5 | CycloneDX SBOM in CI + release | GREEN | ci.yml `supply-chain-scan` + release.yml |
| B6 | Cosign image signing | YELLOW | release.yml — keyless via GitHub OIDC, cosign v2.4.1 — DEMOTED 2026-04-19 (iter-1, same root cause as Phase 9): release pipeline never exercised end-to-end so no signed image exists yet to verify |
| B7 | Trivy fs scan, fail on HIGH/CRITICAL | GREEN | ci.yml `supply-chain-scan` job |
| B8 | Docker base images digest-pinned | GREEN | `Dockerfile.runtime` and `Dockerfile.build` |
| B9 | K8s manifest hardened (NetPol, RBAC scope) | GREEN | `deploy/kubernetes/configd-statefulset.yaml` |
| B10 | PodSecurity restricted profile enforced | GREEN | namespace label + pod/container securityContext |
| B11 | SpotBugs scoped (no blanket excludes) | GREEN | `spotbugs-exclude.xml` per-class scoping |
| B12 | ADR for `--enable-preview` in prod | YELLOW | drafted, not committed (low priority — no producibility risk) |
| PA-6012 | SLSA build provenance attestation | YELLOW | release.yml uses `actions/attest-build-provenance@v2` — DEMOTED 2026-04-19 (iter-1, same root cause as Phase 9): no published release, no provenance emitted yet |

### Security (S-series)

| ID | Gate | Status | Notes |
|----|------|--------|-------|
| S1 | DeltaVerifier (R-01 / PA-3001) | RED | Not landed; substantial new component. **v0.2 candidate, NOT GA-blocking** because the signing chain (S3) provides commit-level integrity even without delta-level verification. |
| S2 | SubscriptionAuthorizer (PA-3011/3021) | RED | Not landed. v0.2 candidate; v0.1 ships with subscription auth as operator-procured (gateway-level). |
| S3 | Sign-or-fail-close in state machine | GREEN | `ConfigStateMachine.signCommand` catches GSE+ISE, throws fail-close; regression test `signFailureFailsClose` |
| S4 | TcpRaftTransport binds peer-id to TLS cert subject | RED | Not landed (PA-4007). v0.2 candidate. |
| S5 | TLS reload rebuilds SSLServerSocket | YELLOW | Negative path exercised in chaos (Phase 5); production rebuild not landed. |
| S6 | Auth-token requires TLS at startup | RED | Not landed (PA-4004). v0.2 candidate. |
| S7 | AclService root-principal override | RED | Not landed (PA-4011). v0.2 candidate. |
| S8 | AuditLogger (R-13 / PA-4001) | RED | Not landed; blocked on F3 (HLC-aware time source). v0.2 candidate. Disaster-recovery runbook calls out the gap. |
| S9 | Codec bounds (PA-1003 / PA-4017) | GREEN | Already in Phase 4; fuzz suite is the regression guard. |

### Observability (O-series — Phase 8)

| ID | Gate | Status | Notes |
|----|------|--------|-------|
| O5 | Lock-free histogram min/max | YELLOW | VarHandle CAS replaces sync, but bench shows real bottleneck is `cursor.AtomicLong.getAndIncrement` cache contention. Real fix needs per-thread cursor striping; v0.2 candidate. NOT GA-blocking — this is a perf optimisation, not a correctness gap. |
| O6 | PrometheusExporter emits histogram type | GREEN | `_bucket{le=...}` + `_sum` + `_count` lines; SLO alerts now have wire matches. |
| O7 | SafeLog PII helper | YELLOW | `redact`, `cardinalityGuard`, `isSafeForLog`; 14 tests in `SafeLogTest` — DEMOTED 2026-04-19 (iter-1 DOC-003): test count was overstated as 17 in the original gate annotation. Helper itself is sound; demotion is for evidence-honesty, not capability. |
| O8 | OpenTelemetry interop | YELLOW BY DESIGN | ADR-0026 documents v0.1 ships Prometheus only with documented OTel collector bridge. |
| PA-5016 | Exporter doesn't mutate registry | GREEN | `histogramIfPresent(String) → Optional`; regression test `exportDoesNotCreatePhantomHistograms`. |
| PA-5018 | Bucket ladder unit alignment | YELLOW | Default ladder is unit-agnostic (records `long`); operator must align `le=` thresholds in alerts to recorded units. Documented in `PrometheusExporter` Javadoc and ADR-0026. |

### Chaos / DR (C-series — Phase 5 + 10)

| ID | Gate | Status | Notes |
|----|------|--------|-------|
| C1 | 72-h soak harness | YELLOW | Stub with duration contract; `perf/soak-72h.sh`. Smoke result: `measured_elapsed_sec=60`, status=YELLOW. Real cluster bring-up is operator action. |
| C2 | 7-day burn-rate harness | YELLOW | `perf/burn-7d.sh` stub. |
| C3 | 30-day longevity harness | YELLOW | `perf/longevity-30d.sh` stub. |
| C4 | 14-day shadow-traffic harness | YELLOW | `perf/shadow-14d.sh` landed in Phase 10; smoke verified at 60 s. |
| Network chaos (5 hooks) | All GREEN | `SimulatedNetwork` + `ChaosScenariosTest` |
| Process-death / disk-full / fsync-stall chaos | RED | Out-of-scope for network-only harness; Jepsen-style harness is v0.2. NOT GA-blocking — formal model `SnapshotInstallSpec.tla` covers safety; unit tests cover impl. |

### Formal verification (F-series — Phase 3)

| ID | Gate | Status |
|----|------|--------|
| F1 | TLA+ specs for Consensus, ReadIndex, SnapshotInstall | GREEN |
| F2 | TLC model check in CI | GREEN |
| F3 | HLC-aware time source | RED (deferred; blocks S8) |

### Organizational (R-series)

| ID | Gate | Status | Notes |
|----|------|--------|-------|
| R-12 | On-call rotation procured | YELLOW | ADR-0025 codifies operator responsibility. Marked YELLOW until operator confirms rotation exists. |
| Runbook conformance | YELLOW | Template landed (`ops/runbooks/runbook-conformance-template.md`); zero drills executed (`ops/dr-drills/results/` empty). |

## Calendar-bounded measurement summary

The directive: **calendar-bounded gates stay yellow with measured real
durations.** Following that, the table below records actual, not
intended, elapsed times. Do not round up. Do not mark green.

| Gate | Required duration | Measured elapsed | Status |
|------|-------------------|------------------|--------|
| C1 — 72-h soak | 259 200 s | 60 s (smoke) | YELLOW |
| C2 — 7-day burn | 604 800 s | unknown | YELLOW (no run on file) |
| C3 — 30-day longevity | 2 592 000 s | unknown | YELLOW |
| C4 — 14-day shadow | 1 209 600 s | 60 s (smoke) | YELLOW |
| External on-call bootstrap | one full rotation cycle | not started | YELLOW |
| Quarterly restore drill | 90 days × ≥ 1 successful drill | zero drills | YELLOW |
| Monthly leader-loss drill | 30 days × ≥ 1 successful drill | zero drills | YELLOW |

Converting any of these to GREEN requires a measured result file (per
`runbook-conformance-template.md` for drills,
`perf/results/<harness>-<UTC>/result.txt` for harnesses) and an
operator's signature on `docs/ga-approval.md`.

## Doc-drift purge — findings

- **`docs/runbooks/` is out-of-date** (separate from `ops/runbooks/`).
  Eight runbooks (`cert-rotation.md`, `edge-catchup-storm.md`,
  `leader-stuck.md`, `poison-config.md`, `reconfiguration-rollback.md`,
  `region-loss.md`, `version-gap.md`, `write-freeze.md`) reference
  metrics that do not exist in the current code:
  `configd_transport_tls_cert_expiry_days`,
  `configd_distribution_fanout_queue_depth`, `configd_raft_role`,
  `configd_edge_poison_pill_detected_total`,
  `configd_raft_quorum_reachable`, `configd_raft_log_replication_lag`,
  and others. **Recommendation:** these are design-aspirational from
  the rewrite-plan. Either:
  1. Implement the metrics they reference (v0.2), then demote
     `docs/runbooks/` to "design notes for v0.2 instrumentation," or
  2. Move the salvageable pieces into `ops/runbooks/` and delete
     `docs/runbooks/`.
  Recommended action **deferred to a human review**, not auto-applied.

- **Single source of truth for runbooks is now `ops/runbooks/`** — all
  alert annotations, the release pipeline, and the disaster-recovery
  flow point there.

- **Audit-cluster references** (`docs/prod-audit-cluster-{D,E}.md`)
  reference the same legacy metrics. These are FINDINGS documents
  describing gaps; leaving them unchanged is correct (they describe
  the gap that motivated the rewrite).

- **`docs/perf-baseline.md`** numbers are accurate (in-session JMH
  re-run on 2026-04-17). Keep.

- **`docs/architecture.md`, `docs/consistency-contract.md`,
  `docs/gap-analysis.md`, `docs/gap-closure.md`** — not audited in
  detail this pass; flagged for human review before sign-off.

## Pre-GA checklist for a human approver

Before signing `docs/ga-approval.md`, an approver should:

1. **Run a real soak.** `perf/soak-72h.sh --duration=$((72*3600))` on a
   real 5-node cluster. Verify result file shows
   `status=PASS_PRODUCTION` (not YELLOW).
2. **Run a real shadow.** `perf/shadow-14d.sh` against two real
   clusters with mirrored production traffic.
3. **Execute one of each drill** (restore-from-snapshot,
   leader-loss recovery) and commit the result file under
   `ops/dr-drills/results/`.
4. **Confirm operator's on-call rotation exists** (ADR-0025).
5. **Decide on the S1/S2/S4-S8 RED items.** Either implement (likely
   v0.2 per gap-closure §5) or accept the residual risk for v0.1 and
   document the acceptance.
6. **Resolve the `docs/runbooks/` drift** per the recommendation
   above.
7. **Verify a release end-to-end.** Tag a `v0.1.0-rc.1`, push, watch
   `release.yml` complete, run the full Cosign + SLSA verify, deploy
   to a staging cluster, and confirm the verify chain still passes
   when consumed.
8. **Independent review of the formal specs.** Have one engineer who
   did not author the specs read `spec/ConsensusSpec.tla` end-to-end
   and confirm it matches the Java implementation's invariants.

This document is **not** the sign-off. It is the input to the sign-off
decision. The decision belongs to a human approver.

## Related

- `docs/progress.md` — phase-by-phase what was done
- `docs/prod-audit.md` — original findings
- `docs/gap-closure.md` — closure plan and §5 deferred-residuals
- `docs/decisions/adr-0023-multi-raft-sharding-deferred.md`
- `docs/decisions/adr-0024-cross-dc-bridge-deferred.md`
- `docs/decisions/adr-0025-on-call-rotation-required.md`
- `docs/decisions/adr-0026-opentelemetry-interop-stub.md`
