# docs-linter — iter-001
**Findings:** 26

## DOC-001 — No ADR has the mandated `## Verification` section
- **Severity:** S2
- **Location:** docs/decisions/adr-0001-embedded-raft-consensus.md through docs/decisions/adr-0026-opentelemetry-interop-stub.md (all 26 ADRs)
- **Category:** missing-section
- **Evidence:** `Grep "^## Verification" docs/decisions/` returned `No files found`. Only headings present across the corpus include `## Status`, `## Context`, `## Decision`, `## Rationale`, `## Consequences`, `## Reviewers`, `## Related`, etc. Sole match for the word "Verification" as a heading occurs as part of ADR-0007's title "ADR-0007: Deterministic Simulation Testing + TLA+ Formal Verification" — not a section header.
- **Impact:** §8.15 mandates every ADR carry a Verification section so claims can be traced to tests/specs/measurements. Without it readers cannot tell whether an architectural decision is enforced anywhere in the build.
- **Fix direction:** Append `## Verification` to all 26 ADRs naming the test class / spec / CI job that proves the decision is in force. For deferral ADRs (0023, 0024, 0025, 0026) the section can simply state "Deferral verified by absence of <component> in tree as of <commit>".
- **Proposed owner:** docs

## DOC-002 — No `ops/runbooks/*.md` runbook has the mandated 8 sections
- **Severity:** S2
- **Location:** ops/runbooks/{control-plane-down,disaster-recovery,edge-read-latency,propagation-delay,raft-saturation,restore-from-snapshot,snapshot-install,write-commit-latency}.md
- **Category:** missing-section
- **Evidence:** `ops/runbooks/README.md:33` documents the convention as: `"Every runbook follows the same skeleton: **What this means → Triage in order → Mitigate → Do not → Related.**"` — five sections, not the eight (`symptoms, impact, diagnosis, mitigation, resolution, rollback, postmortem, related`) mandated by §8.14. None of the runbooks contain the words "Symptoms", "Impact", "Diagnosis", "Resolution", "Rollback", or "Postmortem" as headings.
- **Impact:** Runbooks ship without rollback or postmortem guidance, so an on-call cannot find how to undo a mitigation or how to file the post-incident review. The README codifies this as the project convention.
- **Fix direction:** Either (a) update §8.14 to match the project convention (5 sections), or (b) reformat all 8 runbooks to add Symptoms/Impact/Diagnosis/Resolution/Rollback/Postmortem sections and rename existing ones.
- **Proposed owner:** docs

## DOC-003 — `ga-review.md` claims `SafeLogTest` has 17 tests; it has 14
- **Severity:** S1
- **Location:** docs/ga-review.md:84
- **Category:** false-claim
- **Evidence:** `"O7 | SafeLog PII helper | GREEN | redact, cardinalityGuard, isSafeForLog; 17 tests in SafeLogTest."` Grepping `@Test` in `configd-observability/src/test/java/io/configd/observability/SafeLogTest.java` returns `14`.
- **Impact:** Test-count miscount is small in isolation but undermines the GA review's "every number measured" promise.
- **Fix direction:** Change "17 tests" to "14 tests" or recount and update both claim and test file.
- **Proposed owner:** docs

## DOC-004 — `disaster-recovery.md` references nonexistent `./ops/scripts/restore-snapshot.sh`
- **Severity:** S1
- **Location:** ops/runbooks/disaster-recovery.md:95
- **Category:** broken-link
- **Evidence:** `"./ops/scripts/restore-snapshot.sh --snapshot=<URI> --target=data-configd-0"`. The directory `ops/scripts/` does not exist (`ls /home/ubuntu/Programming/Configd/ops/` shows only `alerts dashboards dr-drills runbooks`).
- **Impact:** An on-call following the disaster-recovery runbook to its "Reset and re-bootstrap" section cannot execute step 3; the cluster remains down.
- **Fix direction:** Either ship a placeholder `ops/scripts/restore-snapshot.sh` and document operator-supplied glue, or rewrite the section to call out the operator-glue requirement explicitly (matching the comment style used in `restore-from-snapshot.md` step 5).
- **Proposed owner:** docs

## DOC-005 — `restore-from-snapshot.md` references nonexistent `./ops/scripts/restore-conformance-check.sh`
- **Severity:** S1
- **Location:** ops/runbooks/restore-from-snapshot.md:165
- **Category:** broken-link
- **Evidence:** `"./ops/scripts/restore-conformance-check.sh --snapshot=/tmp/restore.snap --cluster-endpoint=https://configd-0.configd.svc:8080"`. `ops/scripts/` does not exist.
- **Impact:** The runbook ends with a "Verification (post-restore)" step the operator cannot run; pass criterion `data_conformance=pass` in `runbook-conformance-template.md` becomes unreachable.
- **Fix direction:** Ship the script, or rewrite to spell out the operator-supplied verification protocol referenced by `runbook-conformance-template.md`.
- **Proposed owner:** docs

## DOC-006 — `disaster-recovery.md` references nonexistent `deploy/kubernetes/configd-bootstrap.yaml`
- **Severity:** S1
- **Location:** ops/runbooks/disaster-recovery.md:98
- **Category:** broken-link
- **Evidence:** `"kubectl apply -f deploy/kubernetes/configd-bootstrap.yaml"`. `ls deploy/kubernetes/` returns only `configd-statefulset.yaml`.
- **Impact:** The single-voter bootstrap step cannot run; recovery from a quorum-lost disaster blocked at the precise step it is needed.
- **Fix direction:** Author `deploy/kubernetes/configd-bootstrap.yaml` (a single-replica StatefulSet variant), or rewrite the step to derive a single-voter manifest from `configd-statefulset.yaml`.
- **Proposed owner:** docs

## DOC-007 — `snapshot-install.md` references nonexistent `SnapshotConformanceTest.java`
- **Severity:** S1
- **Location:** ops/runbooks/snapshot-install.md:25
- **Category:** broken-link
- **Evidence:** `"the conformance test in configd-config-store/src/test/java/io/configd/store/SnapshotConformanceTest.java"`. The file does not exist (verified with `ls`); the `store/` test directory contains `CommandCodecTest`, `ConfigStateMachineTest`, `HamtMapTest`, etc., but no `SnapshotConformanceTest`.
- **Impact:** Triage step 2 ("Snapshot integrity → see the conformance test") points to a non-existent file. Operator cannot consult the referenced regression coverage.
- **Fix direction:** Either land the test file or rewrite the bullet to reference an existing test (e.g. `SnapshotInstallSpecReplayerTest.java` in `configd-consensus-core/src/test/java/io/configd/raft/`).
- **Proposed owner:** docs

## DOC-008 — `snapshot-install.md` cites `adr-0009-snapshot-format.md` with a TODO
- **Severity:** S2
- **Location:** ops/runbooks/snapshot-install.md:46
- **Category:** todo-in-docs
- **Evidence:** `"- docs/decisions/adr-0009-snapshot-format.md (if it exists — TODO)"`. The actual ADR-0009 is `adr-0009-java21-zgc-runtime.md`; no snapshot-format ADR exists.
- **Impact:** Self-acknowledged TODO ships in production runbook; "Related" section becomes a placeholder, not a navigation aid.
- **Fix direction:** Either author `adr-0027-snapshot-format.md` (capturing chunk size, CRC, signature contract) or remove the bullet.
- **Proposed owner:** docs

## DOC-009 — `write-commit-latency.md` cites `adr-0007-raft-commit-pipeline.md` with a TODO
- **Severity:** S2
- **Location:** ops/runbooks/write-commit-latency.md:56
- **Category:** todo-in-docs
- **Evidence:** `"- docs/decisions/adr-0007-raft-commit-pipeline.md (if it exists — TODO)"`. The actual ADR-0007 is `adr-0007-deterministic-simulation-testing.md`.
- **Impact:** Same as DOC-008: TODO in shipped runbook.
- **Fix direction:** Author the missing ADR or remove the bullet.
- **Proposed owner:** docs

## DOC-010 — `control-plane-down.md` cites the wrong ADR for fail-close signing
- **Severity:** S1
- **Location:** ops/runbooks/control-plane-down.md:55
- **Category:** false-claim
- **Evidence:** `"the verify-only fail-close was deliberate (see ADR-0014 / S3 fix in ConfigStateMachine.signCommand)."` ADR-0014 is `adr-0014-zgc-shenandoah-gc-strategy.md` ("ZGC/Shenandoah GC Strategy") which does not discuss the signing chain.
- **Impact:** A reader following the link to understand the fail-close design lands on a GC-strategy ADR (currently in "Superseded — partially" status, no less). The intended provenance is lost.
- **Fix direction:** Replace with the correct ADR (or, since none exists, point to gap-closure §5 / S3 row in ga-review.md, and file an ADR for the sign-or-fail-close decision).
- **Proposed owner:** docs

## DOC-011 — `release.md` cites ADR-0026 for K8s manifest hardening; ADR-0026 covers OpenTelemetry
- **Severity:** S1
- **Location:** ops/runbooks/release.md:84
- **Category:** false-claim
- **Evidence:** `"Pinning by digest (not tag) is mandatory for production — see PodSecurity / NetworkPolicy comments in the manifest and ADR-0026."` ADR-0026 is titled `# ADR-0026: OpenTelemetry Interop Is a Stub for v0.1` and contains no PodSecurity or NetworkPolicy guidance.
- **Impact:** Reader cross-references to discover the digest-pinning rationale, lands on the OTel stub ADR, finds no relevant content.
- **Fix direction:** Either point to the manifest's inline comments only, or author an ADR for the K8s hardening profile (B9/B10) and reference that.
- **Proposed owner:** docs

## DOC-012 — All `ops/runbooks/*` reference `raftctl` CLI; binary does not exist in repo
- **Severity:** S1
- **Location:** ops/runbooks/README.md:30; ops/runbooks/control-plane-down.md:31,46; ops/runbooks/disaster-recovery.md (implicit); ops/runbooks/restore-from-snapshot.md:142; ops/runbooks/snapshot-install.md:30,32; ops/runbooks/write-commit-latency.md:44
- **Category:** false-claim
- **Evidence:** README:30: `"shell access to the cluster, the Grafana dashboard ops/dashboards/configd-overview.json, and the raftctl CLI."` `find` for `raftctl*` returns nothing in the tree (no source, no script, no binary, no Dockerfile install).
- **Impact:** Every recovery procedure assumes a CLI tool the operator is told they need but is not shipped. The disaster-recovery flow becomes unrecoverable in practice.
- **Fix direction:** Either ship `raftctl` (e.g. as a `configd-server`-mode flag or a thin REST client), or rewrite all six runbook references to use the existing `curl http://localhost:8080/raft/status | jq` pattern that `restore-from-snapshot.md:121` already demonstrates.
- **Proposed owner:** docs

## DOC-013 — `ga-review.md` relies on `docs/runbooks/` drift it does not resolve
- **Severity:** S2
- **Location:** docs/ga-review.md:138-153
- **Category:** stale-reference
- **Evidence:** `"docs/runbooks/ is out-of-date (separate from ops/runbooks/). Eight runbooks (cert-rotation.md, edge-catchup-storm.md, leader-stuck.md, poison-config.md, reconfiguration-rollback.md, region-loss.md, version-gap.md, write-freeze.md) reference metrics that do not exist in the current code... Recommended action deferred to a human review, not auto-applied."` `ls docs/runbooks/` confirms all 8 stale files still ship.
- **Impact:** A reader who lands on `docs/runbooks/leader-stuck.md` first (no top-level pointer says which directory wins) follows a runbook that uses metrics never emitted by the code (`configd_raft_role`, `configd_raft_quorum_reachable`, etc., grep-confirmed in 0 `*.java` files).
- **Fix direction:** Add `docs/runbooks/README.md` with a one-line redirect to `ops/runbooks/`, or delete `docs/runbooks/` entirely. `handoff.md` known-hole #6 already calls this out — close the loop.
- **Proposed owner:** docs

## DOC-014 — Runbook metrics referenced in `ops/runbooks/*` are not emitted by any Java code
- **Severity:** S1
- **Location:** ops/runbooks/edge-read-latency.md:24; ops/runbooks/propagation-delay.md:22; ops/runbooks/raft-saturation.md:11,18; ops/runbooks/snapshot-install.md:4; ops/runbooks/write-commit-latency.md (implicit, via dashboard)
- **Category:** false-claim
- **Evidence:** `"configd_snapshot_rebuild_total"`, `"configd_edge_apply_lag_seconds"`, `"configd_raft_pending_apply_entries"`, `"configd_changefeed_backlog_bytes"`, `"configd_apply_total"`, `"configd_write_commit_total"`, `"configd_snapshot_install_failed_total"`, `"configd_apply_seconds"` — Grep for any of these names in `*.java` returns zero hits. They appear only in `ops/dashboards/configd-overview.json` and `ops/alerts/configd-slo-alerts.yaml`.
- **Impact:** Every `ops/runbooks/*.md` triage step that says "check metric X" fails because the metric never appears in `/metrics`. This is the same drift class flagged for `docs/runbooks/`, but the GA review marks `ops/runbooks/` as the GREEN, single-source-of-truth surface.
- **Fix direction:** Either implement the metrics in `MetricsRegistry` callers (work-units D-series in gap-closure), or rewrite the runbook triage steps to use only the metrics that are emitted today (the test in `PrometheusExporterTest:88-93` shows the actual emission style: `configd.raft.commit-count` → `configd_raft_commit_count_total`).
- **Proposed owner:** docs

## DOC-015 — `ga-review.md` line 167-169 explicitly defers `architecture.md` audit
- **Severity:** S2
- **Location:** docs/ga-review.md:167-169
- **Category:** untraced-number
- **Evidence:** `"docs/architecture.md, docs/consistency-contract.md, docs/gap-analysis.md, docs/gap-closure.md — not audited in detail this pass; flagged for human review before sign-off."` Yet the architecture document still asserts hard numbers ("≤ 50 ns" read latency, "10 MB/s allocation", "10K-1M edge nodes") with no traceability to a measurement.
- **Impact:** Numerical claims in `architecture.md` (the document a reader most often reaches for) are unverified per the GA review's own admission. Any of them could be the next "spec says X, code does Y" embarrassment.
- **Fix direction:** Either run the audit (this pass cannot — see DOC-016/017 for the specific gaps already visible in 30 minutes) or annotate every numeric assertion in `architecture.md` with `(target — not measured)` until measured.
- **Proposed owner:** docs

## DOC-016 — `architecture.md` claims "Total: < 50ns" for the read path with no source
- **Severity:** S1
- **Location:** docs/architecture.md:116
- **Category:** untraced-number
- **Evidence:** `"Note over App: Total: < 50ns. Zero allocation on miss, ~24 B on hit. Zero locks."` `docs/perf-baseline.md` has no entry for the read path; the only `EdgeReadBenchmark` numbers in the tree are absent, and the doc cites no JMH log path, file:line, or commit hash.
- **Impact:** A reader takes "< 50 ns" as a measured floor; nothing in the repo backs it.
- **Fix direction:** Run an `EdgeReadBenchmark`, paste the results into `perf-baseline.md`, and link from this line. Until then, replace "< 50 ns" with "< 50 ns target — not measured this pass".
- **Proposed owner:** docs

## DOC-017 — `architecture.md` write-commit "< 80ms" cross-region budget unsourced in this doc
- **Severity:** S2
- **Location:** docs/architecture.md:85
- **Category:** untraced-number
- **Evidence:** Latency Budget table cell `"Total write commit | < 10ms | < 80ms"`. No reference to `docs/performance.md` (which has the modeled derivation at line 352) or to a measurement.
- **Impact:** Reader cannot trace 80 ms to its origin without a separate hunt; the same number appears in `docs/performance.md:352` with `~100ms` and a "MODELED" label, contradicting the architecture's "< 80 ms".
- **Fix direction:** Pick one number, mark it MODELED vs MEASURED, and add a one-line citation to `docs/performance.md` row.
- **Proposed owner:** docs

## DOC-018 — `perf-baseline.md` smoke claim contradicts harness-status string
- **Severity:** S3
- **Location:** docs/perf-baseline.md:60-63 vs perf/results/smoke/result.txt
- **Category:** untraced-number
- **Evidence:** Doc: `"In-session smoke run of perf/soak-72h.sh --duration=60 recorded measured_elapsed_sec=60, status=YELLOW (no workload wired; duration honoured)."` `perf/results/smoke/result.txt` is the only result file in tree but lives under `perf/results/smoke/` (not the `perf/results/<harness>-<UTC>/` path the GA review §C2 mandates).
- **Impact:** Minor — the contract path is documented in two places with slightly different schemes; future harness invocations may not produce GA-acceptable evidence.
- **Fix direction:** Either move the smoke result to `perf/results/soak-72h-20260417T223152Z/result.txt` (matching the format from the file's own `start_utc`) or update `perf-baseline.md` and `ga-review.md:131-134` to accept `perf/results/smoke/`.
- **Proposed owner:** docs

## DOC-019 — `ga-review.md` claims test count "20,149" cited four times in `progress.md`; no green-build evidence file shipped
- **Severity:** S2
- **Location:** docs/ga-review.md:35; docs/progress.md:350,450,530,596; docs/handoff.md:456
- **Category:** untraced-number
- **Evidence:** `"4 | Test pyramid | GREEN | 20,149 tests pass; jqwik property + simulation + 10k seed sweep"`. The Approver's minimum-evidence set in `handoff.md:454-456` says: `"A green run of the full test suite on the commit being approved... Capture the line count (expected ≥ 20,149)"`. No build-log artefact under `docs/` or `perf/results/` records this number with timestamp + commit SHA.
- **Impact:** "20,149" is a load-bearing GA claim with no on-disk evidence; the next refactor that drops the count by 50 silently invalidates the approval baseline.
- **Fix direction:** Commit a build-log artefact (`docs/verification/test-counts/<UTC>-<sha>.txt`) that pins the count to a commit, and have `ga-review.md` cite the file path.
- **Proposed owner:** docs

## DOC-020 — `release.md` mandates a CHANGELOG.md that does not exist
- **Severity:** S2
- **Location:** ops/runbooks/release.md:8
- **Category:** broken-link
- **Evidence:** `"3. CHANGELOG / release notes drafted from RELEASE_NOTES_TEMPLATE.md."` `ls /home/ubuntu/Programming/Configd/CHANGELOG.md` → file not found. `handoff.md` known-hole #7 calls this out (`"No CHANGELOG.md despite RELEASE_NOTES_TEMPLATE.md expecting..."`) but the runbook still mandates it.
- **Impact:** A release engineer following the pre-flight literally cannot complete step 3; either skips silently or blocks the release.
- **Fix direction:** Either land an empty `CHANGELOG.md` with a `## v0.1.0 — Initial GA` stub, or rewrite the pre-flight step to say "release notes drafted (CHANGELOG.md to be introduced in v0.2 per handoff.md known-hole §7)".
- **Proposed owner:** docs

## DOC-021 — Two separate ADRs cover Event-Driven Notifications (ADR-0006 and ADR-0018)
- **Severity:** S2
- **Location:** docs/decisions/adr-0006-event-driven-notifications.md; docs/decisions/adr-0018-event-driven-notifications.md
- **Category:** stale-reference
- **Evidence:** ADR-0006 title: `"# ADR-0006: Event-Driven Push Notifications (Reject Watches and Polling)"`. ADR-0018 title: `"# ADR-0018: Event-Driven Notification System (Server-Side Push Streams)"`. Both decide essentially the same thing (gRPC bidi streams replacing watches). Neither marks the other as Superseded.
- **Impact:** Reader cannot tell which ADR is authoritative; sequence-number drift suggests an unfinished re-numbering.
- **Fix direction:** Mark ADR-0006 as Superseded (pointing to ADR-0018), or merge the two and renumber.
- **Proposed owner:** docs

## DOC-022 — `ga-review.md` row "B6 Cosign image signing" cites `cosign v2.4.1` — pinning location undocumented
- **Severity:** S3
- **Location:** docs/ga-review.md:55
- **Category:** untraced-number
- **Evidence:** `"B6 | Cosign image signing | GREEN | release.yml — keyless via GitHub OIDC, cosign v2.4.1"`. The version pin lives in `.github/workflows/release.yml:101` (`cosign-release: 'v2.4.1'`) but the GA review row does not link the line.
- **Impact:** Future audits must grep to confirm the pin; row is verifiable but not at-a-glance traceable.
- **Fix direction:** Append `(.github/workflows/release.yml:101)` to the row.
- **Proposed owner:** docs

## DOC-023 — `ga-review.md` row "B2 tla2tools.jar pinned" cites SHA prefix only
- **Severity:** S3
- **Location:** docs/ga-review.md:51
- **Category:** untraced-number
- **Evidence:** `"B2 | tla2tools.jar pinned by SHA-256 | GREEN | 4c1d62e0… in .github/workflows/ci.yml"`. The full SHA-256 should be traceable; partial-SHA citations cannot be cross-verified without re-grepping.
- **Impact:** A reader has to grep `ci.yml` for `4c1d62e0` to confirm; trivial but the same problem will recur for every audit.
- **Fix direction:** Cite the full 64-char SHA, or include the `ci.yml` line number.
- **Proposed owner:** docs

## DOC-024 — `ChaosScenariosTest` count "17" matches but no Java annotation grep matches
- **Severity:** S3
- **Location:** docs/ga-review.md:36
- **Category:** untraced-number
- **Evidence:** `"5 | Chaos automation | GREEN | SimulatedNetwork 5 chaos hooks + 17 ChaosScenariosTest cases"`. `Grep "@Test|@ParameterizedTest|@RepeatedTest"` in the file returns 17 — the count is correct — but `Grep "@Test"` alone returns 0 (parameterised), so a casual auditor running the simpler grep concludes "0 tests, false claim". The doc should state the test style.
- **Impact:** Verifiable but easy to mis-disprove; gives the GA review a false-positive risk on re-audit.
- **Fix direction:** Reword to "17 parameterised ChaosScenariosTest cases (`@ParameterizedTest`)" so a simple grep matches.
- **Proposed owner:** docs

## DOC-025 — `runbook-conformance-template.md` references `InvariantMonitor.assertAll()` — method does not exist
- **Severity:** S1
- **Location:** ops/runbooks/runbook-conformance-template.md:30
- **Category:** false-claim
- **Evidence:** `"3. No invariant is violated during recovery. InvariantMonitor.assertAll() (or an equivalent operator check) passes against the recovered cluster."` `InvariantMonitor` exists at `configd-observability/src/main/java/io/configd/observability/InvariantMonitor.java` but Grep for `assertAll` in the class returns no hits. The "(or an equivalent operator check)" hedge does not absolve the false method reference.
- **Impact:** Drill conformance step 3 cannot be executed by name; operator must reverse-engineer what the equivalent check is.
- **Fix direction:** Either add `assertAll()` to `InvariantMonitor`, or rewrite to cite the method that actually exists (e.g. document which observability call returns the cluster's invariant state and use that).
- **Proposed owner:** docs

## DOC-026 — `release.md` deploy step uses fragile `sed -i "s|configd:GIT_SHA|...` known-hole acknowledged but not fixed
- **Severity:** S2
- **Location:** ops/runbooks/release.md:78
- **Category:** false-claim
- **Evidence:** `"sed -i \"s|configd:GIT_SHA|ghcr.io/<owner>/<repo>@${DIGEST}|\" deploy/kubernetes/configd-statefulset.yaml"`. `handoff.md` known-hole #4 explicitly flags: `"image: configd:GIT_SHA literal substitution in K8s manifest is fragile... Any operator applying the manifest without running the sed ends up in ErrImagePull."`
- **Impact:** A documented foot-gun ships in the runbook and remains the only documented deploy path; a less-careful operator who copy-pastes `kubectl apply -f deploy/kubernetes/configd-statefulset.yaml` after pulling main hits ErrImagePull on production.
- **Fix direction:** Either ship a Kustomize overlay (recommended in the known-hole), add a hard `# DO NOT APPLY DIRECTLY` comment at the top of the StatefulSet manifest, or wrap the `sed` step in a `bin/deploy.sh` so the foot-gun cannot be skipped.
- **Proposed owner:** docs
