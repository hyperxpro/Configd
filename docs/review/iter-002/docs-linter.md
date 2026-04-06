# docs-linter — iter-002

**Date:** 2026-04-19
**Severity floor:** S3
**Scope:** verify §8.14/§8.15 closure post iter-1; sample-audit ADR Verification quality; runbook cross-references; CHANGELOG/RELEASE_NOTES; broken file references; stale doc trees.

## iter-1 closures verified

- **§8.14 — runbook 8-section skeleton.** All 9 operational runbooks under `ops/runbooks/` (`control-plane-down`, `disaster-recovery`, `edge-read-latency`, `propagation-delay`, `raft-saturation`, `release`, `restore-from-snapshot`, `snapshot-install`, `write-commit-latency`) carry the mandated headings: Symptoms / Impact / Operator-Setup / Diagnosis / Mitigation / Resolution / Rollback / Postmortem / Related. `grep ^## (Symptoms|Impact|Operator-Setup|Diagnosis|Mitigation|Resolution|Rollback|Postmortem|Related)` returns 9 hits per runbook. CLOSED.
- **§8.15 — every ADR has `## Verification`.** All 27 ADR files (0001–0027) contain a top-level `## Verification` section. Spot-check (5 sampled: 0001, 0003, 0005, 0014, 0027): each cites a real test file path; every cited test file was confirmed to exist via filesystem lookup. The "NOT YET WIRED — tracked as iter-2 follow-up" template is used honestly for the deferred ADRs (0013 session-mgmt, 0016 SWIM, 0023 multi-Raft, 0024 cross-DC, 0026 OTel) and they label themselves as `Not Implemented` / `Deferred` accordingly. CLOSED.
- **ADR-0006 superseded by ADR-0018.** ADR-0006 line 4: `Superseded by ADR-0018 (2026-04-19).` with a "Why superseded" rationale and historical `Accepted` status preserved. CLOSED.
- **ADR-0027 sign-or-fail-close.** Created; Status `Accepted (2026-04-19)`; Verification section points to the real test `signFailureFailsClose` in `ConfigStateMachineTest`. CLOSED.
- **CHANGELOG.md.** Created at repo root in Keep-a-Changelog format with `## [Unreleased]` + `## [0.1.0] - TBD` placeholder. CLOSED.
- **DOC-001/002/003/004/005/006/010/011/012/019(via demotion).** Verified via grep + filesystem checks. CLOSED.

## Findings (28)

## DOC-027 — `ops/runbooks/snapshot-install.md` still references nonexistent `SnapshotConformanceTest.java`
- **Severity:** S1
- **Location:** `ops/runbooks/snapshot-install.md:37` and `:50`
- **What's wrong:** Lines 37 and 50 cite `configd-config-store/src/test/java/io/configd/store/SnapshotConformanceTest.java`. `find` for that file in the tree returns nothing; the snapshot-install spec replayer that does exist is `configd-consensus-core/src/test/java/io/configd/raft/SnapshotInstallSpecReplayerTest.java`. iter-1 logged this as DOC-007 and marked it for F3, but the F3 rewrites preserved the broken citation rather than fixing it.
- **Quote:** `"the conformance test in configd-config-store/src/test/java/io/configd/store/SnapshotConformanceTest.java"`
- **Fix:** Replace both occurrences with `configd-consensus-core/src/test/java/io/configd/raft/SnapshotInstallSpecReplayerTest.java` (the F2 spec-replay test that was added in iter-1), or land the named conformance test.

## DOC-028 — Three runbooks reference nonexistent `docs/decisions/adr-0009-snapshot-format.md`
- **Severity:** S1
- **Location:** `ops/runbooks/snapshot-install.md:132`; `ops/runbooks/restore-from-snapshot.md:281`; F3 report claims this ADR exists.
- **What's wrong:** The actual ADR-0009 is `adr-0009-java21-zgc-runtime.md` (JDK runtime pin, partially superseded by ADR-0022). No snapshot-format ADR exists in the corpus. iter-1 DOC-008 flagged this; F3 task 1 listed `docs/decisions/adr-0009-snapshot-format.md` as if it existed but the file was never authored. The runbooks were rewritten to *keep* the broken cross-reference.
- **Quote:** snapshot-install.md:132 — `"docs/decisions/adr-0009-snapshot-format.md — snapshot-format decision; SnapshotConsistency invariant lives here"`. restore-from-snapshot.md:281 — same path.
- **Fix:** Either (a) author `docs/decisions/adr-0028-snapshot-format.md` (chunk size, CRC trailer, signature contract) and re-point both runbooks at it, or (b) drop the bullet and describe the `SnapshotConsistency` invariant inline (already enforced by `spec/SnapshotInstallSpec.tla`, which both runbooks already cite correctly).

## DOC-029 — `runbook-conformance-template.md` still references `InvariantMonitor.assertAll()` which does not exist
- **Severity:** S1
- **Location:** `ops/runbooks/runbook-conformance-template.md:30`
- **What's wrong:** The template's drill conformance step 3 names `InvariantMonitor.assertAll()` as the check operators must run. `Grep "assertAll"` in `configd-observability/src/main/java/io/configd/observability/InvariantMonitor.java` returns no hits. iter-1 logged this as DOC-025 and assigned it to F3; F3's report does not mention closing it, and the file is unchanged.
- **Quote:** `"3. No invariant is violated during recovery. InvariantMonitor.assertAll() (or an equivalent operator check) passes against the recovered cluster."`
- **Fix:** Either add `assertAll()` to `InvariantMonitor` (returning a structured `InvariantStatus` so the drill can pass/fail decisively), or rewrite to cite the methods that do exist (`InvariantMonitor.snapshot()` returns the per-invariant counters; the template can spell out the expected `violations==0` for each named invariant).

## DOC-030 — `docs/runbooks/` (8 stale runbooks) still exists with no README, no superseded marker, no redirect
- **Severity:** S1
- **Location:** `docs/runbooks/{cert-rotation,edge-catchup-storm,leader-stuck,poison-config,reconfiguration-rollback,region-loss,version-gap,write-freeze}.md`
- **What's wrong:** iter-1 DOC-013 flagged the parallel `docs/runbooks/` tree as out-of-date with metrics that the code does not emit (`configd_raft_role`, `configd_raft_quorum_reachable`, etc.). `ls docs/runbooks/` confirms all 8 stale files still ship; no `README.md` exists in the directory; no superseded banner has been added to the individual files. A reader landing on `docs/runbooks/leader-stuck.md` first (e.g. from an old bookmark or search hit) follows a runbook whose metrics will never fire. `ga-review.md` lines 138–153 acknowledge the drift but defer the fix to "human review, not auto-applied"; no human review has happened.
- **Quote (docs/runbooks/leader-stuck.md:4):** `"Alert: configd_raft_role shows no node in LEADER state for any Raft group for > 5s."`
- **Fix:** Land `docs/runbooks/README.md` with one line: `These design notes describe metrics planned for v0.2; the operational runbooks live in /ops/runbooks/. Do not follow these files in an incident.` Or delete the directory entirely.

## DOC-031 — `release.md` still ships the `sed -i "s|configd:GIT_SHA|...` foot-gun
- **Severity:** S2
- **Location:** `ops/runbooks/release.md:128–132`
- **What's wrong:** iter-1 DOC-026 documented this as a foot-gun: `kubectl apply -f deploy/kubernetes/configd-statefulset.yaml` without first running the `sed` substitution lands the operator in `ErrImagePull` because the manifest still says `image: configd:GIT_SHA`. The §8.14 rewrite preserved the unguarded `sed` step. The manifest now has a `# NEVER deploy a floating tag` comment, but nothing prevents skipping the `sed`. F4/F6 did not address this.
- **Quote:** `sed -i "s|configd:GIT_SHA|ghcr.io/<owner>/<repo>@${DIGEST}|" deploy/kubernetes/configd-statefulset.yaml`
- **Fix:** Wrap the substitution in `ops/scripts/deploy.sh` that fails-closed if `${DIGEST}` is unset and re-runs `cosign verify` before applying; or convert `configd-statefulset.yaml` into a Kustomize base + overlay so `kustomize build .` writes the digest from a pin file. Until then, add a top-of-file `# DO NOT APPLY DIRECTLY — run ops/scripts/deploy.sh` to the StatefulSet manifest itself.

## DOC-032 — `ops/runbooks/README.md` audience/convention sections contradict the new 9-section §8.14 skeleton
- **Severity:** S2
- **Location:** `ops/runbooks/README.md:41–46`
- **What's wrong:** F3 restructured every operational runbook to the 9-section skeleton (Symptoms / Impact / Operator-Setup / Diagnosis / Mitigation / Resolution / Rollback / Postmortem / Related), but the `## Convention` section in `README.md` still documents the old 5-section convention: `"Every runbook follows the same skeleton: What this means → Triage in order → Mitigate → Do not → Related."` A reader who consults the index to learn the convention is told the wrong thing.
- **Quote:** README.md:43 — `"Every runbook follows the same skeleton: **What this means → Triage in order → Mitigate → Do not → Related.**"`
- **Fix:** Replace the convention bullet with the §8.14 skeleton and a one-line pointer to the rule (e.g. "Per §8.14 of the gap-closure rules, every runbook in this directory has Symptoms / Impact / Operator-Setup / Diagnosis / Mitigation / Resolution / Rollback / Postmortem / Related; `Do not` is appended where the runbook has a non-negotiable.").

## DOC-033 — Three runbooks reference unwired metrics `configd_edge_apply_lag_seconds`, `configd_changefeed_backlog_bytes`, `configd_raft_follower_lag`, `configd_apply_total`, `configd_edge_staleness_seconds`
- **Severity:** S1
- **Location:** `ops/runbooks/propagation-delay.md:12,38,45,59,83`; `ops/runbooks/raft-saturation.md:35`; `ops/runbooks/snapshot-install.md:14`; `ops/runbooks/disaster-recovery.md:134`; `ops/runbooks/restore-from-snapshot.md:223`
- **What's wrong:** iter-1 DOC-014 was scoped to "metrics that alerts depend on" and F5 wired the alert-cited subset. But the runbook prose still names metrics that are not emitted by any Java code: `Grep configd_edge_apply_lag_seconds|configd_changefeed_backlog_bytes|configd_raft_follower_lag|configd_edge_staleness_seconds` returns zero `*.java` matches. `configd_apply_total` is named in `raft-saturation.md:35` (`rate(configd_apply_total[1m])`); F5's metric-diff explicitly noted this would be "left as-is" and resolved by the alias `configd_write_commit_total`, but the runbook was never updated. So a triage step like "Check `rate(configd_apply_total[1m])`" returns the empty series.
- **Quote (propagation-delay.md:59):** `"Check configd_changefeed_backlog_bytes on the leader. If growing, the leader is producing faster than the edges can consume"`
- **Fix:** For each occurrence, either (a) wire the metric in `ConfigdMetrics` + emit it from the relevant subsystem, or (b) rewrite the prose to cite a metric that exists. The cheapest path for the apply-rate one is `s/configd_apply_total/configd_write_commit_total/` in raft-saturation.md.

## DOC-034 — `ga-review.md` test-count "20,149" contradicts iter-1 verify.md "21,402" and inventory.md "21,222"
- **Severity:** S2
- **Location:** `docs/ga-review.md:35`
- **What's wrong:** Phase-4 row reads `"4 | Test pyramid | YELLOW | 20,149 tests pass; ... — DEMOTED 2026-04-19 (iter-1 DOC-019): no on-disk test-count artifact pinned to commit SHA"`. The DEMOTION cell was added by F1 (correct), but the underlying number was not updated. The actual reactor green-build at iter-1 verify time reports `21,402 tests, 0 failures, 0 errors, 2 skipped`. `docs/verification/inventory.md` lists `21,222`. Three different numbers ship in three docs, all claiming to describe the same test corpus.
- **Quote (ga-review.md:35):** `"20,149 tests pass; jqwik property + simulation + 10k seed sweep"`
- **Fix:** Pick the post-iter-1 number (21,402 per `docs/review/iter-001/verify.md:48`), update `ga-review.md:35`, `docs/progress.md` (4 occurrences), `docs/handoff.md:456`. Pin the number in a `docs/verification/test-counts/<UTC>-<sha>.txt` artifact so the next refactor that drops the count is detectable.

## DOC-035 — `docs/architecture.md` "< 50 ns" read-path and "< 80 ms" cross-region write claims still unsourced
- **Severity:** S1
- **Location:** `docs/architecture.md:85`, `docs/architecture.md:116`
- **What's wrong:** iter-1 DOC-016/017 flagged these as untraced numbers; F7 was scoped to fix `docs/performance.md` only (added a "MODELED, NOT MEASURED" honesty banner there) and did not touch `architecture.md`. Result: `docs/performance.md:357` honestly says cross-region write commit is "MODELED, NOT MEASURED: ~100ms"; `docs/architecture.md:85` still asserts "< 80ms" with no banner. A reader takes architecture.md as authoritative because of the file name.
- **Quote (architecture.md:116):** `"Note over App: Total: < 50ns. Zero allocation on miss, ~24 B on hit. Zero locks."` (architecture.md:85): `"| Total write commit | **< 10ms** | **< 80ms** |"`
- **Fix:** Carry the F7 honesty banner into `architecture.md`. Replace `< 50ns` with `< 50 ns target — not measured this pass; see docs/performance.md §10` and replace `< 80ms` with `< 80 ms target; modeled at ~100 ms in docs/performance.md §11`.

## DOC-036 — `README.md` is empty (one-line placeholder)
- **Severity:** S2
- **Location:** `README.md:1` — file contains exactly `# Configd\n`
- **What's wrong:** The repo root README has been a one-line stub since at least iter-1. There is no description of what Configd is, no quick-start, no link to `docs/architecture.md`, no link to `ops/runbooks/`, no badge for build status, no Maven coordinates, no contributing pointer. A new engineer landing on the GitHub page sees nothing actionable.
- **Quote (full file):** `# Configd`
- **Fix:** Land a 30-line README with: one-paragraph product description (lift from `docs/architecture.md` §1), `./mvnw -B verify` quickstart, links to `docs/architecture.md` / `docs/decisions/` / `ops/runbooks/` / `CHANGELOG.md`, supported JDK pin (Corretto 25 per ADR-0022), license, and a "this is pre-GA" status banner per `docs/ga-review.md`.

## DOC-037 — F3 report cites ADR file paths that do not exist
- **Severity:** S2
- **Location:** `docs/review/iter-001/fixers/F3-report.md:29–80`
- **What's wrong:** The F3 report enumerates ADRs by paths that do not match the on-disk filenames: it cites `adr-0002-customer-isolation-model.md` (actual: `adr-0002-hierarchical-raft-replication.md`), `adr-0003-hyparview-gossip-overlay.md` (actual: `adr-0003-plumtree-fan-out.md`), `adr-0004-prefix-subscription-model.md` (actual: `adr-0004-global-monotonic-sequence-numbers.md` — the prefix-subscription ADR is 0020), `adr-0007-raft-simulation-test-strategy.md` (actual: `adr-0007-deterministic-simulation-testing.md`), `adr-0008-snapshot-and-compaction.md` (actual: `adr-0008-progressive-rollout.md`), `adr-0009-snapshot-format.md` (does not exist), `adr-0010-prometheus-observability.md` (actual: `adr-0010-netty-grpc-transport.md`), `adr-0013-tcp-binary-transport.md` (actual: `adr-0013-lightweight-session-management.md`), `adr-0015-tls-1-3-mutual-auth.md` (does not exist; actual 0015 is `multi-region-topology.md`), `adr-0016-rate-limiting-strategy.md` (actual: `adr-0016-swim-lifeguard-membership.md`), `adr-0017-watch-service-protocol.md` (actual: `adr-0017-namespace-multi-tenancy.md`), `adr-0019-hybrid-clock-versions.md` (actual: `adr-0019-consistency-model.md`), `adr-0020-rollout-controller.md` (actual: `adr-0020-prefix-subscription-model.md`), `adr-0021-maven-build.md` (actual: `adr-0021-maven-build-system.md`), `adr-0022-java-version-pin.md` (actual: `adr-0022-java-25-runtime.md`), `adr-0026-otel-deferred.md` (actual: `adr-0026-opentelemetry-interop-stub.md`). Either F3 was operating on a different set of ADR filenames than what shipped, or the report was authored against an imagined naming scheme. The actual ADR files do all carry `## Verification` sections (verified independently), so the §8.15 closure is real — but the F3 report itself is misleading documentation.
- **Quote (F3-report.md:29):** `"docs/decisions/adr-0002-customer-isolation-model.md — refs ConfigScopeTest, AclServiceTest."`
- **Fix:** Rewrite the F3 file list against the actual on-disk filenames so future iter-N reviewers can audit what was changed without having to grep around to discover the divergence. Mark the report `# F3 Report (errata 2026-04-19): file path list re-aligned to actual ADR names`.

## DOC-038 — `release.md:138` carries an unresolved `<!-- TODO: confirm correct ADR -->`
- **Severity:** S2
- **Location:** `ops/runbooks/release.md:138–143`
- **What's wrong:** F3 fixed the wrong ADR cross-reference (was pointing at ADR-0026 OTel — wrong topic) by re-pointing at ADR-0021 (Maven) + ADR-0022 (JDK pin), and left a `<!-- TODO -->` comment saying a dedicated release-engineering ADR is needed. That TODO ships in production runbook prose. The ga-review.md Phase 9 is YELLOW for the same reason (release pipeline never exercised end-to-end). An ADR-0028 (release engineering: Cosign keyless + SLSA provenance + release.yml ownership) would close both.
- **Quote:** `"<!-- TODO: confirm correct ADR — there is no dedicated release-engineering ADR yet; ... A dedicated adr-XXXX-release-engineering.md should be authored when the release pipeline is exercised end-to-end (Phase 9 demotion in iter-1). -->"`
- **Fix:** Author `docs/decisions/adr-0028-release-engineering.md` documenting the cosign-keyless / SLSA-provenance / GHCR-digest contract that `.github/workflows/release.yml` already encodes, then drop the TODO marker. Keep ADR-0021/0022 in the Related list.

## DOC-039 — Six `<!-- TODO PA-XXXX -->` placeholder issue numbers ship across operational runbooks
- **Severity:** S2
- **Location:** `ops/runbooks/control-plane-down.md:58,85`; `disaster-recovery.md:187`; `restore-from-snapshot.md:171,188,206`; `snapshot-install.md:68,82`; `write-commit-latency.md:68`; `README.md:32,37`
- **What's wrong:** F4 inserted 11 `<!-- TODO PA-XXXX -->` markers documenting missing admin endpoints (`POST /admin/raft/{add,remove}-server`, `/raft/status`, `/admin/raft/transfer-leadership`). The literal `XXXX` is a placeholder; no real issue/ticket ID was assigned. An on-call who hits one of these markers cannot file the work or even tell whether the gap is tracked.
- **Quote (control-plane-down.md:58):** `"<!-- TODO PA-XXXX: admin endpoint missing — there is no /raft/status or /admin/raft/status on HttpApiServer today."`
- **Fix:** Allocate a real PA-NNNN range (e.g. PA-7001–PA-7011) for the admin-endpoint backlog, replace each `XXXX` with the assigned number, and either land the issues in the operator's tracker or list them in `docs/gap-closure.md` §5 (deferred residuals). The README's `Search the runbooks for "TODO PA-" to enumerate the gaps` instruction only works if the markers carry distinguishable identifiers.

## DOC-040 — `ga-review.md` row B6 (Cosign) and B2 (tla2tools) still cite without `file:line` despite iter-1 DOC-022/023
- **Severity:** S3
- **Location:** `docs/ga-review.md:51` (B2) and `:55` (B6)
- **What's wrong:** iter-1 DOC-022 asked for `(.github/workflows/release.yml:101)` after the cosign claim; iter-1 DOC-023 asked for the full SHA-256 for tla2tools instead of the prefix. Neither was applied. B6 row reads `"cosign v2.4.1"` with no line ref; B2 row reads `"4c1d62e0…"` with the trailing ellipsis. The actual SHA at `.github/workflows/ci.yml:61` is `4c1d62e0f67c1d89f833619d7edad9d161e74a54b153f4f81dcef6043ea0d618`; cosign-release pin is at `.github/workflows/release.yml:101` (and again at :170).
- **Quote (ga-review.md:51):** `"| B2 | tla2tools.jar pinned by SHA-256 | GREEN | 4c1d62e0… in .github/workflows/ci.yml |"`
- **Fix:** Replace the prefix with the full SHA and add the file:line for cosign — both verifications then pass at-a-glance without grep.

## DOC-041 — ADR-0010 status `Superseded` but no successor ADR named
- **Severity:** S2
- **Location:** `docs/decisions/adr-0010-netty-grpc-transport.md:4-14`
- **What's wrong:** Status line is bare `Superseded` (no link to the supersedor). The 2026-04-14 verification note explains the actual implementation chose plain TCP + JDK HttpServer rather than Netty/gRPC, but no replacement ADR captures the decision. The ADR cross-references pointing at ADR-0010 (e.g. CHANGELOG.md:9, every Verification section that cites `TcpRaftTransportTest`) inherit the orphaned-supersession problem. Compare ADR-0006 which says `Superseded by ADR-0018 (2026-04-19).` — that's the standard.
- **Quote:** `"## Status\nSuperseded\n\n> Note (2026-04-14, Verification Phase V8): The actual implementation uses plain Java TCP sockets ..."`
- **Fix:** Either author `docs/decisions/adr-0029-tcp-virtual-thread-transport.md` capturing the actually-shipped TCP+TLS+VirtualThread design and update ADR-0010's Status to `Superseded by ADR-0029 (2026-04-19)`, or change ADR-0010's Status to `Accepted (revised) — see verification note for shipped substitution`.

## DOC-042 — ADR-0014 status `Superseded — partially` references ADR-0010 / ADR-0016 verification notes circularly
- **Severity:** S3
- **Location:** `docs/decisions/adr-0014-zgc-shenandoah-gc-strategy.md:4-15`
- **What's wrong:** Status reads `Superseded — partially. The GC choice (ZGC on JDK 25) stands; library-level claims do not.` The 2026-04-16 note refers to ADR-0010 Superseded note and ADR-0016 Not Implemented note as evidence — but ADR-0010's Status is bare `Superseded` (DOC-041) and ADR-0016 status / "Not Implemented" labelling is not visible in the Verification section quoted above. The chain of supersession is hard to follow.
- **Quote:** `"Sections of this ADR assert Netty, gRPC-java, and Spring Boot as the implementation stack; none of those libraries are present in the actual codebase (see ADR-0010 Superseded note, ADR-0016 Not Implemented note ..."`
- **Fix:** Either rewrite ADR-0014 to retain only the GC decision (move the implementation-stack claims into the historical-context section), or split ADR-0014 into ADR-0014a (GC, still Accepted) and ADR-0014b (legacy stack assumptions, fully Superseded by ADR-0010/0016).

## DOC-043 — `CHANGELOG.md` is empty under `## [Unreleased]` despite many iter-1 fixes (F2/F3/F4/F5/F6/F7) landing
- **Severity:** S2
- **Location:** `CHANGELOG.md:15-27`
- **What's wrong:** Recent commits include CRC32C frame trailer (C-001), file-storage CRC envelope (C-002), per-round ReadIndex confirmation (D-001), InstallSnapshot index echo (D-002), tick-unit rename (D-003), signingEpoch in snapshot (D-004), ConfigdMetrics SLO wire-up (F5), release.yml + RELEASE_NOTES_TEMPLATE (F6), wire-version golden bytes (F6). None of these are in the `## [Unreleased]` block. The CHANGELOG was created and immediately stayed empty, which is the failure mode the file is supposed to prevent.
- **Quote (CHANGELOG.md:15-27):** `"## [Unreleased]\n\n### Added\n\n### Changed\n\n### Deprecated\n\n### Removed\n\n### Fixed\n\n### Security\n\n## [0.1.0] - TBD"`
- **Fix:** Populate `## [Unreleased]` from the iter-1 fix-dispatch result table in `docs/review/iter-001/verify.md`. Keep the source-of-truth pointer to `git log` per `RELEASE_NOTES_TEMPLATE.md:9` but at minimum list the F2 Tier-1-SAFETY surgery items under `### Security` (`fail-close on signature failure`, `CRC32C wire-frame trailer`, `file-storage CRC envelope`).

## DOC-044 — `RELEASE_NOTES_TEMPLATE.md` references `SnapshotWireCompatStubTest.java` and `WalWireCompatStubTest.java` — only one of two exists
- **Severity:** S3
- **Location:** `RELEASE_NOTES_TEMPLATE.md:45-48`
- **What's wrong:** The template's "Snapshot/WAL Compatibility" checkbox names two stub tests as the wire-compat regression hooks. `find` for `SnapshotWireCompatStubTest.java` and `WalWireCompatStubTest.java` should return both; only `SnapshotInstallSpecReplayerTest.java` and similar exist under `configd-consensus-core/src/test/java/io/configd/raft/`. A release engineer following the template literally cannot tick the box.
- **Quote (RELEASE_NOTES_TEMPLATE.md:45-48):** `"- [ ] Snapshot binary format unchanged OR backward-compat test added under configd-consensus-core/src/test/java/io/configd/raft/SnapshotWireCompatStubTest.java and enabled\n- [ ] WAL segment format unchanged OR backward-compat test added under WalWireCompatStubTest.java and enabled"`
- **Fix:** Either land the two stub test files (asserting the current snapshot/WAL format hash, so a future format change forces the regression test update) or rename the template references to the closest extant tests (`SnapshotInstallSpecReplayerTest.java` for snapshots; the WAL format is exercised by `FileStorageTest.datFileCrcDetectsBitFlip` per the F2 C-002 fix).

## DOC-045 — `release.md:153` resolution criterion references `CHANGELOG.md` link "filed" but template/runbook gives no instructions
- **Severity:** S3
- **Location:** `ops/runbooks/release.md:153`
- **What's wrong:** Resolution criterion 5 reads `"The release notes link from CHANGELOG.md is filed."` Neither the template nor the runbook describes what "filed" means: should the engineer add a `[v0.1.0]` reference link block? Should they paste the GitHub release URL into the version heading? CHANGELOG.md as it ships has no such link block.
- **Quote (release.md:153):** `"The release notes link from CHANGELOG.md is filed."`
- **Fix:** Either expand the criterion to say `"CHANGELOG.md has the corresponding ## [vX.Y.Z] - YYYY-MM-DD heading promoted from ## [Unreleased], with a hyperlink to the GitHub release"`, or modify CHANGELOG.md to include a `[Unreleased]: https://github.com/<owner>/<repo>/compare/v0.1.0...HEAD` reference-style link block at the bottom (Keep-a-Changelog standard), and document that as the artifact to update.

## DOC-046 — `disaster-recovery.md:94` references `audit_log` "when AuditLogger lands per S8" but S8 RED in ga-review
- **Severity:** S3
- **Location:** `ops/runbooks/disaster-recovery.md:93-95`
- **What's wrong:** The runbook conditions a forensic step on `audit_log` "when AuditLogger lands per S8" — `ga-review.md:75` records S8 as RED (`"AuditLogger ... Not landed; blocked on F3 (HLC-aware time source). v0.2 candidate."`). The hedge "until then this step is best-effort with whatever logs exist" is honest but renders the step undefined: an on-call cannot tell what counts as a satisfactory `audit_log` substitute today.
- **Quote (disaster-recovery.md:93-95):** `"3. Compare the missing commit's signed envelope against audit_log (when AuditLogger lands per S8 — until then this step is best-effort with whatever logs exist)."`
- **Fix:** Until S8 lands, name a concrete substitute (e.g. `kubectl logs -l app=configd --previous --since=24h` for the relevant window) so the operator has a deterministic action.

## DOC-047 — `ga-review.md:171-198` "Pre-GA checklist for a human approver" lists 8 items but no #4 / #5 status reflects iter-1 demotions
- **Severity:** S3
- **Location:** `docs/ga-review.md:171-198`
- **What's wrong:** The pre-GA checklist tells the approver to (4) confirm operator's on-call rotation exists (R-12), (5) decide on S1/S2/S4-S8 RED items, (7) verify a release end-to-end. Since iter-1 demoted Phase 9 / B6 / PA-6012 to YELLOW for the same root cause as #7 (release pipeline never exercised), the checklist could explicitly cite the demotions instead of restating the work. Right now an approver reading top-to-bottom sees the demotion in the table and the same task in the checklist with no cross-reference.
- **Quote (ga-review.md:189):** `"7. Verify a release end-to-end. Tag a v0.1.0-rc.1, push, watch release.yml complete ..."`
- **Fix:** Add `(See Phase 9 / B6 / PA-6012 demotion rows; running this checklist item is what converts those rows to GREEN.)` after item 7. Same pattern for items 1/2 (Phase 6 / C1-C4 demotions) and item 6 (DOC-030 — the `docs/runbooks/` drift).

## DOC-048 — `docs/decisions/adr-0001-embedded-raft-consensus.md:42` Verification cites `kubectl exec configd-0 -- curl -sf http://localhost:8080/raft/status` but the endpoint does not exist
- **Severity:** S2
- **Location:** `docs/decisions/adr-0001-embedded-raft-consensus.md:46` (Operator check bullet)
- **What's wrong:** ADR-0001's Verification gives the operator check `kubectl exec configd-0 -- curl -sf http://localhost:8080/raft/status returns a leader from the in-cluster voter set`. F4 explicitly noted that `/raft/status` does NOT exist on `HttpApiServer` (`docs/review/iter-001/fixers/F4-report.md:52`). Five other ADRs/runbooks also assert curl probes against admin endpoints that don't exist (`/admin/raft/add-server`, `/admin/raft/remove-server`, `/admin/raft/transfer-leadership`). The Verification section is supposed to be the runnable ground truth.
- **Quote (adr-0001:46):** `"kubectl exec configd-0 -- curl -sf http://localhost:8080/raft/status returns a leader from the in-cluster voter set."`
- **Fix:** Replace the operator check with one that uses an endpoint that actually exists (e.g. `kubectl exec configd-0 -- curl -sf http://localhost:8080/health/ready` plus `/metrics | grep configd_raft_pending_apply_entries` per the F5 wire-up). Sweep the other ADRs that name nonexistent admin endpoints similarly.

## DOC-049 — `docs/decisions/adr-0007-deterministic-simulation-testing.md` Verification claim about `verify-sim` CI job
- **Severity:** S3
- **Location:** `docs/decisions/adr-0007-deterministic-simulation-testing.md:71`
- **What's wrong:** Verification states `"CI job verify-sim in .github/workflows/ci.yml runs the seed sweep on every PR and gates merge"`. `Grep "verify-sim"` in `ci.yml` returns no hits — there is no job by that name. The TLC model check job is `tlc-model-check`; the seed-sweep is part of `./mvnw test` invocation in the main `build` job, not a dedicated job.
- **Quote (adr-0007:71):** `"CI job verify-sim in .github/workflows/ci.yml runs the seed sweep on every PR and gates merge"`
- **Fix:** Replace the bullet with the real job name (`build` for the seed sweep, `tlc-model-check` for the TLC verification, both gated on PR merge per `branch-protection.json` if it exists). Or rename one of the existing jobs to `verify-sim` if the naming is preferred.

## DOC-050 — ADR-0027 Operator-check cites a metric not yet emitted with parenthetical `(when wired by F5/F6 metric work)`
- **Severity:** S3
- **Location:** `docs/decisions/adr-0027-sign-or-fail-close.md:100`
- **What's wrong:** Verification "Operator check" reads `configd_state_machine_apply_failure_total{reason="sign_fail_close"} (when wired by F5/F6 metric work) increments visibly`. F5's metric-diff (`docs/review/iter-001/fixers/F5-metric-diff.md`) does NOT include `configd_state_machine_apply_failure_total` with a `reason` label; the wired metric is `configd_write_commit_failed_total` (no `reason` label). So the operator check still fails today. The "(when wired by F5/F6 metric work)" hedge means the section ships pre-broken.
- **Quote (adr-0027:100):** `"in the live cluster, configd_state_machine_apply_failure_total{reason=\"sign_fail_close\"} (when wired by F5/F6 metric work) increments visibly"`
- **Fix:** Either land the labelled metric (extend `ConfigdMetrics` to record `configd_state_machine_apply_failure_total{reason}` from the `IllegalStateException("fail-close: signing failed for committed command", e)` path) or rewrite the operator check to use the metric that does exist (`rate(configd_write_commit_failed_total[5m]) > 0` with the log line `fail-close: signing failed` as the discriminator).

## DOC-051 — `docs/architecture.md:188` "10K-1M edge nodes" capacity claim has no measurement source
- **Severity:** S2
- **Location:** `docs/architecture.md:188`
- **What's wrong:** Tier table cell `"Edge (10K-1M nodes)"` asserts a capacity range. iter-1 DOC-015 flagged that `architecture.md` was deferred from the GA review audit and contains hard numbers with no traceability. F7 only fixed `performance.md`. There is no row in `docs/perf-baseline.md` that backs the 10K-1M edge fan-out claim with a measurement; HyParViewOverlayTest exercises a small simulated overlay, not 10^6 nodes.
- **Quote:** `"| **Edge** (10K-1M nodes) | Plumtree consumers. Working set only. No Raft participation. | CDN PoPs, edge servers |"`
- **Fix:** Either add a perf-baseline row that shows the largest measured overlay size (likely << 1M) and replace `10K-1M` with `<measured-max> measured; 1M target — see ADR-0011 capacity discussion`, or annotate the cell with `(target — not measured this pass)` per the F7 pattern.

## DOC-052 — `docs/decisions/adr-0008-progressive-rollout.md:57` cites `audit_log` "when AuditLogger lands per gap-closure S8"
- **Severity:** S3
- **Location:** `docs/decisions/adr-0008-progressive-rollout.md:57`
- **What's wrong:** Same issue as DOC-046 in a different file: the ADR's Operator-check is conditioned on a not-yet-landed component. ADR Verifications are supposed to assert what's true today, not what will be true.
- **Quote:** `"Audit trail for IMMEDIATE overrides is captured in audit_log (when AuditLogger lands per gap-closure S8)."`
- **Fix:** Until S8 lands, change to `Audit trail for IMMEDIATE overrides is NOT captured today; audit-trail wiring blocks on S8 (AuditLogger). See gap-closure §S8 for the residual.`

## DOC-053 — `docs/decisions/adr-0019-consistency-model.md:126` Verification line was redacted in the audit grep — likely too long for readable diff
- **Severity:** S3
- **Location:** `docs/decisions/adr-0019-consistency-model.md:126`
- **What's wrong:** During the iter-2 grep audit the line at `:126` was returned as `[Omitted long context line]` — meaning the Verification section's "Testable via" bullet is long enough to break the typical reviewer's diff window. ADR-0018 and ADR-0007 hit the same limit. Long single-line Verification bullets are an honesty hazard: a reviewer can't see what's being claimed without manual scrolling.
- **Quote (truncated by audit tool, then by file inspection):** Verification "Testable via" bullet on a single very-long line.
- **Fix:** Wrap the Verification "Testable via" bullets at ~100 characters with explicit hard-wraps, matching the rest of the ADR prose. Apply to ADR-0007, ADR-0018, ADR-0019 at minimum.

## DOC-054 — `ops/runbooks/disaster-recovery.md:9` "Runbook: Disaster Recovery — Coordination" header uses an em-dash that breaks markdown anchor generation
- **Severity:** S3
- **Location:** `ops/runbooks/disaster-recovery.md:1`
- **What's wrong:** The H1 contains an em-dash (`—`, U+2014) which most markdown anchor generators (GitHub, jekyll-kramdown, mkdocs) collapse to nothing. The auto-generated anchor becomes `#runbook-disaster-recovery-coordination` (em-dash dropped) — inconsistent with how other runbooks generate anchors. Cross-document links like `[disaster-recovery](disaster-recovery.md#runbook-disaster-recovery--coordination)` (with double-hyphen for the em-dash) won't work; nor will `#runbook-disaster-recovery-coordination` if the operator's deployed renderer keeps the em-dash. Other runbook H1s do not use em-dash, so this is also a stylistic outlier.
- **Quote:** `"# Runbook: Disaster Recovery — Coordination"`
- **Fix:** Rename to `# Runbook: Disaster Recovery Coordination` or `# Runbook: Disaster Recovery (Coordination)`. The em-dash adds no semantic value here.
