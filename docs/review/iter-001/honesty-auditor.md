# honesty-auditor — iter-001
**Final pass for iteration 1.** Veto applied.

Date: 2026-04-19. Auditor scope per §3 of the loop directive: every GREEN
gate in `docs/ga-review.md`, all 9 reviewer outputs (spot-check), Phase 8.1
perf hard-rule cross-check, and the 11-commit handoff plan.

---

## Section 1 — ga-review.md GREEN gates audited

Type taxonomy applied per §2: A = code-verifiable; B = calendar-bounded
(cannot be GREEN); C = human-required (cannot be GREEN). All B/C rows in
ga-review.md are already YELLOW — none are mis-marked GREEN.

| Row ID | Type | Original status | Verdict | Reason |
|---|---|---|---|---|
| Phase 0 — Inventory | A | GREEN | KEEP-GREEN | `docs/inventory.md` exists; cited |
| Phase 1 — Audit | A | GREEN | KEEP-GREEN | `docs/prod-audit-cluster-{A..F}.md` all present |
| Phase 2 — Gap closure | A | GREEN | KEEP-GREEN | `docs/gap-closure.md` exists |
| Phase 3 — Formal verification | A | GREEN | KEEP-GREEN | tla2tools SHA `4c1d62e0…` confirmed at `.github/workflows/ci.yml:61`; specs in `spec/` |
| Phase 4 — Test pyramid | A | GREEN | DEMOTE-YELLOW | "20,149 tests pass" — no on-disk build-log artifact pinning the count to a SHA. DOC-019 confirms. |
| Phase 5 — Chaos automation | A | GREEN | KEEP-GREEN (clarify) | 17 `@ParameterizedTest` cases at `ChaosScenariosTest.java` confirmed; doc cites raw count, fine |
| Phase 6 — Performance & capacity | A | GREEN | DEMOTE-YELLOW | JMH baselines exist in `docs/perf-baseline.md` but no committed `perf/results/<harness>-<UTC>/` artifact for the JMH runs. See §3 below; only `perf/results/smoke/result.txt` (a 60s soak smoke) lives on disk |
| Phase 9 — Release engineering | A | GREEN | DEMOTE-YELLOW | `release.yml` present and parses; never exercised end-to-end (handoff §5 known-hole #1 explicitly admits this). GREEN claim is "landed not exercised" |
| B1 — Maven wrapper | A | GREEN | KEEP-GREEN | `mvnw` + `.mvn/wrapper/` present |
| B2 — tla2tools SHA-256 pin | A | GREEN | KEEP-GREEN | `4c1d62e0f67c1d89f833619d7edad9d161e74a54b153f4f81dcef6043ea0d618` at `ci.yml:61` |
| B3 — Maven wrapper SHA-256 | A | GREEN | KEEP-GREEN | `4ec3f26f…` at `.mvn/wrapper/maven-wrapper.properties:4` |
| B4 — CI uses ./mvnw | A | GREEN | KEEP-GREEN | confirmed |
| B5 — CycloneDX SBOM | A | GREEN | KEEP-GREEN | jobs declared in ci.yml + release.yml |
| B6 — Cosign image signing | A | GREEN | DEMOTE-YELLOW | release.yml declares cosign v2.4.1 but pipeline never run end-to-end (R-001/R-004; handoff known-hole #1). Claim is "code present" not "verified" |
| B7 — Trivy fs scan | A | GREEN | KEEP-GREEN | `supply-chain-scan` job present |
| B8 — Docker base images digest-pinned | A | GREEN | KEEP-GREEN | confirmed |
| B9 — K8s manifest hardened | A | GREEN | KEEP-GREEN | `configd-statefulset.yaml` present with NetPol+RBAC (SEC-012 + R-010 are improvements, not falsifying) |
| B10 — PodSecurity restricted | A | GREEN | KEEP-GREEN | confirmed |
| B11 — SpotBugs scoped | A | GREEN | KEEP-GREEN | per-class scoping in `spotbugs-exclude.xml` |
| PA-6012 — SLSA provenance | A | GREEN | DEMOTE-YELLOW | release.yml uses `attest-build-provenance@v2` but not exercised (same root cause as B6) |
| S3 — Sign-or-fail-close | A | GREEN | KEEP-GREEN | `signFailureFailsClose` test present in `ConfigStateMachineTest` |
| S9 — Codec bounds | A | GREEN | KEEP-GREEN | fuzz suite in tree (`FrameCodecPropertyTest`, `RaftMessageCodecPropertyTest`, `CommandCodecPropertyTest`) |
| O6 — Histogram type emission | A | GREEN | KEEP-GREEN | `_bucket{le=…}`/`_sum`/`_count` lines in `PrometheusExporter` confirmed (note P-004/P-011 quality issues — separate bugs, not falsifying GREEN) |
| O7 — SafeLog PII helper | A | GREEN | DEMOTE-YELLOW | "17 tests in SafeLogTest" claim is false; actual `@Test` count = 14 (DOC-003 confirmed). Helper itself ships fine; the citation is wrong |
| PA-5016 — Exporter doesn't mutate registry | A | GREEN | KEEP-GREEN | `histogramIfPresent` + regression test cited |
| F1 — TLA+ specs | A | GREEN | KEEP-GREEN | `ConsensusSpec.tla`, `ReadIndexSpec.tla`, `SnapshotInstallSpec.tla` present |
| F2 — TLC in CI | A | GREEN | KEEP-GREEN | ci.yml job present |
| Network chaos (5 hooks) | A | GREEN | KEEP-GREEN | `SimulatedNetwork` present (C-013 notes the contract is wider than impl — improvement, not falsifying) |

**Type-B / Type-C audit:** every Type-B row in ga-review.md (C1, C2, C3,
C4, "Quarterly restore drill", "Monthly leader-loss drill", "External
on-call bootstrap") is already YELLOW. Every Type-C row (R-12 on-call,
runbook-conformance-no-drills) is already YELLOW. **Zero false-GREEN
calendar/human gates.**

---

## Section 2 — Reviewer findings audited (spot-check)

I read the cited file:line for the highest-impact and most-likely-fabricated
findings across all 9 reviewer files. Total findings sampled: 18 of 140.

| Finding | File:line spot-check | Verdict |
|---|---|---|
| H-003 (tick loop catches Throwable) | `ConfigdServer.java:512-521` | KEEP — verbatim match |
| H-004 (TLS reload silent fail) | `ConfigdServer.java:528-537` | KEEP — `System.err.println` confirmed at :533 |
| H-008 (HTTP body unbounded) | `HttpApiServer.java:269` | KEEP — `readAllBytes()` at L269 confirmed |
| H-009 (Plumtree outbox unbounded) | `PlumtreeNode.java:60` | KEEP — `Queue<OutboundMessage> outbox` declared L60 |
| D-001 (ReadIndex stale ack) | `RaftNode.java:762-777`, `ReadIndexState.java:137-140` | KEEP — `confirmAllLeadership` at :137 + `tickHeartbeat` ordering at :762-775 verbatim |
| D-002 (InstallSnapshotResponse overshoot) | `RaftNode.java:1494-1501` | KEEP — `latestSnapshot.lastIncludedIndex()` written into matchIndex confirmed |
| D-003 (tick/ms unit mismatch) | `ConfigdServer.java:68`, `RaftNode.java:763-764` | KEEP — `TICK_PERIOD_MS = 10` + tick-counted `heartbeatTicksElapsed >= heartbeatIntervalMs()` confirmed |
| D-004 (signing epoch not snapshotted) | `ConfigStateMachine.java:79` (signingEpoch field) | KEEP — field exists; not in `snapshot()` call cited |
| SEC-001 (auth-token argv leak) | `ServerConfig.java:118-121` | KEEP — `case "--auth-token"` handler verbatim |
| SEC-002 (HTTP body unbounded) | `HttpApiServer.java:269` | KEEP — duplicate of H-008 |
| SEC-004 (snapshot entries cap 100M) | `ConfigStateMachine.java:381` | KEEP — `MAX_SNAPSHOT_ENTRIES = 100_000_000` verbatim at L381 |
| P-001 (HdrHistogram never imported) | `docs/performance.md:75`, `pom.xml:64` | KEEP — claim present at :75; pom declared but `grep "import.*HdrHistogram"` over `**/*.java` returns zero |
| P-002 (Histogram bench `@Fork(1)`) | `HistogramBenchmark.java:35-37` | KEEP — file exists; benchmark in tree |
| P-004 (`_sum = mean × count`) | `PrometheusExporter.java:121` | KEEP — `Math.round(hist.mean() * count)` verbatim at L121 |
| R-001 (no rollback in release.yml) | `.github/workflows/release.yml` | KEEP — file exists; no `on_failure` block confirmed |
| R-007 (no wire-version byte) | `FrameCodec.java:9-29`, `CommandCodec.java:14-23` | KEEP — files exist; no version-byte field |
| C-001 (FrameCodec no CRC) | `FrameCodec.java:64-79`, `:113-136` | KEEP — citations land on encode/decode region |
| DOC-003 (SafeLogTest count) | `SafeLogTest.java` | KEEP — `grep -c @Test` returns 14, not 17 as ga-review.md claims |
| DOC-004/005/006 (missing scripts/manifests) | `ops/scripts/` (absent), `deploy/kubernetes/configd-bootstrap.yaml` (absent) | KEEP — `ls /ops/` shows no `scripts/`; `deploy/kubernetes/` contains only `configd-statefulset.yaml` |
| 001 (raftctl missing) | repo-wide `find raftctl*` | KEEP — no binary, no source, no Dockerfile install |

**No fabrications detected** in the spot-checked sample. Every cited
file:line that I opened contained the quoted evidence verbatim or in
substantively equivalent form. The reviewers cited current-tree code.

---

## Section 3 — Hard-rule violations confirmed this iteration

Per §8 of the directive:

- **§8.1 (no perf claim without HdrHistogram artifact committed)** —
  P-001 confirmed. `docs/performance.md:75` claims "HdrHistogram for all
  latency measurements"; zero `import org.HdrHistogram.*` in any `*.java`
  file. The active histogram is the hand-rolled
  `MetricsRegistry$DefaultHistogram` (4096-slot ring buffer +
  `Arrays.sort`). `docs/perf-baseline.md` numbers come from JMH
  `Mode.Throughput` runs with no committed JMH log file; `perf/results/`
  contains only `smoke/result.txt` (60-second duration smoke), not any
  HdrHistogram .hgrm or JMH json artifact. **§8.1 violated by P-001.**
- **§8.10 (deprecation ≥ 2 releases / wire-compat)** — R-005, R-007,
  R-008 confirmed: no protocol-version byte in `FrameCodec` or
  `CommandCodec`; no wire-compat CI job; no golden-bytes fixtures.
  Cannot enforce N↔N-1. **§8.10 unimplementable as currently coded.**
- **§8.14 (runbook 8-section skeleton)** — DOC-002 confirmed:
  `ops/runbooks/README.md:33` codifies a 5-section skeleton, not the
  mandated 8. None of the 8 runbooks in `ops/runbooks/` carry
  Symptoms/Impact/Diagnosis/Resolution/Rollback/Postmortem. **§8.14
  violated.**
- **§8.15 (every ADR has `## Verification`)** — DOC-001 confirmed:
  zero ADRs (0001-0026) carry a `## Verification` section. **§8.15
  violated.**

---

## Section 4 — Honesty-failure check

- **Type-B GREEN gates found:** 0. Every calendar-bounded row (C1-C4,
  drills, on-call bootstrap) is correctly YELLOW.
- **Type-C GREEN gates found:** 0. R-12 (on-call), runbook-conformance
  drills, and "external operator confirms" rows are correctly YELLOW.
- **Fabricated evidence detected:** 0. Spot-check of 18 findings across
  9 reviewers: every quoted file:line resolved to verbatim or
  substantively-equivalent text in the current tree.
- **Loop halt required?** **NO.** No Type-B or Type-C row is GREEN; no
  reviewer fabricated evidence. The iteration may proceed to §4 (Plan +
  Fix) under standard rules.

The iteration is honest. It is not, however, complete: §3 hard-rule
violations (§8.1 perf-artifact, §8.10 wire-compat, §8.14 runbook
skeleton, §8.15 ADR verification section) are real and must be addressed
before GA sign. They are tracked as findings, not as honesty failures.

---

## Section 5 — Demotions ordered

The following GREEN rows in `docs/ga-review.md` are demoted to YELLOW
for under-evidenced Type-A claims. Each requires a measurement artifact
or revised claim before re-promotion.

1. **Phase 4 (Test pyramid) GREEN → YELLOW** — claim "20,149 tests
   pass" has no on-disk build-log artifact pinned to a commit SHA. To
   re-promote: commit `docs/verification/test-counts/<UTC>-<sha>.txt`
   with `mvnw -T 1C test` output and the count.
2. **Phase 6 (Performance & capacity) GREEN → YELLOW** — JMH baselines
   asserted in `docs/perf-baseline.md` lack committed measurement
   artifacts. Only `perf/results/smoke/result.txt` (a duration-only
   60-second soak smoke) ships under `perf/results/`. To re-promote:
   commit JMH JSON output (or HdrHistogram .hgrm files) under
   `perf/results/<harness>-<UTC>/` for SubscriptionMatch and Histogram
   benchmarks; AND resolve P-001 by either using HdrHistogram or
   removing the claim from `docs/performance.md:75`.
3. **Phase 9 (Release engineering) GREEN → YELLOW** — `release.yml`
   present but never exercised end-to-end (handoff known-hole #1
   admits this). To re-promote: complete handoff Pre-GA Step 7 and
   commit verification log (cosign verify success, gh attestation
   verify success, GHCR digest URL).
4. **B6 (Cosign image signing) GREEN → YELLOW** — same root cause as
   Phase 9: pipeline declared but unverified. Re-promote with Step 7
   evidence.
5. **PA-6012 (SLSA build provenance) GREEN → YELLOW** — same root
   cause as B6. Re-promote with Step 7 evidence.
6. **O7 (SafeLog PII helper) GREEN → YELLOW** — citation count is
   wrong (14 tests, not 17). Re-promote by correcting `ga-review.md:84`
   to "14 tests in SafeLogTest" (DOC-003).

**Total demotions:** 6 (all Type-A under-evidenced).

---

## Section 6 — Commit-plan cross-check (handoff.md §1)

All 11 planned commits' artifact paths verified on disk:

| Commit | Status | Notes |
|---|---|---|
| 1 — Inventory + audit | OK | all 10 cited files present |
| 2 — TLA+ spec closures | OK | `ReadIndexSpec.tla`, `SnapshotInstallSpec.tla`, `.cfg` files, replayer tests all present |
| 3 — Property test pyramid | OK | all 4 cited test/source files present |
| 4 — Chaos primitives | OK | `SimulatedNetwork.java` + 2 tests present |
| 5 — JMH benchmarks | OK | `HistogramBenchmark.java` + `SubscriptionMatchBenchmark.java` present |
| 6 — Supply-chain | OK | all 6 cited files modified per `git status` |
| 7 — ADRs 23-25 | OK | all 3 ADR files present |
| 8 — Observability code | OK | all 5 cited files present |
| 9 — Ops surface | OK | runbooks + dashboard + alerts + ADR-0026 all present |
| 10 — Release engineering | OK | `release.yml` + Dockerfiles + StatefulSet + RELEASE_NOTES_TEMPLATE.md all present |
| 11 — DR + GA review | OK | all `perf/*.sh` scripts present; `docs/ga-review.md`, `docs/handoff.md`, `docs/progress.md` present |

Commit messages reviewed are accurate to the artifacts grouped. No
disagreement between handoff §1 and on-disk state.

**Note:** the commit plan does NOT include any of the missing files
flagged by reviewers (`ops/scripts/restore-snapshot.sh`,
`ops/scripts/restore-conformance-check.sh`,
`deploy/kubernetes/configd-bootstrap.yaml`, `CHANGELOG.md`, `raftctl`
binary). Those gaps are real and ship as residuals; the commit plan
correctly does not pretend to land them.
