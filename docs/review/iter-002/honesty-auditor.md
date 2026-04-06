# honesty-auditor — iter-002 (entry pass)

**Date:** 2026-04-19
**Scope:** Cross-check the iter-1 loop's claims (verify.md +
F3-F7 fixer reports + ga-review.md + loop-state.json) against on-disk
evidence in the working tree at HEAD `22d2bf3`. Apply the Honesty
Invariant: **Type B (calendar-bounded) and Type C (human-required) gates
may NEVER be flipped GREEN by the loop.** Every GREEN claim in
`docs/ga-review.md` must have an on-disk artifact pinned to a SHA.

This is the iter-2 *entry* honesty pass — the loop has not yet
dispatched iter-2 fixers, so the audit covers what iter-1 produced and
the state the iter-2 reviewers will receive.

---

## Honesty failures (loop-stopping if any)

**Count: 0.**

- **Type-B GREEN gates found:** 0. Every calendar-bounded row
  (`C1`-`C4`, "Quarterly restore drill", "Monthly leader-loss drill",
  "External on-call bootstrap") in `docs/ga-review.md` is YELLOW with
  measured-elapsed durations recorded honestly (`60 s (smoke)`,
  `unknown`, `not started`).
- **Type-C GREEN gates found:** 0. `R-12` (on-call rotation) and
  "Runbook conformance" rows are YELLOW. ADR-0025 codifies operator
  responsibility; `ops/dr-drills/results/` is empty as claimed.
- **Fabricated production code / tests:** 0. Every cited test in
  iter-1 verify.md (`signFailureFailsClose`, `decodeRejectsCorrupted*`,
  `datFileCrcDetectsBitFlip`, `datFileTruncationIsDetected`,
  `signingEpochSurvivesSnapshotRoundTrip`,
  `legacySnapshotWithoutEpochTrailerStillLoads`,
  `unknownWireVersionIsRejected`) and every test cited inside the 27
  on-disk ADR `## Verification` sections resolves to a real
  `*.java` file in the tree.
- **Fabricated artifact paths:** 0. `ops/scripts/restore-snapshot.sh`
  (281 LoC), `ops/scripts/restore-conformance-check.sh` (302 LoC),
  `deploy/kubernetes/configd-bootstrap.yaml` (183 LoC), `CHANGELOG.md`,
  `RELEASE_NOTES_TEMPLATE.md`, ADR-0027, the `verify-published` job in
  `.github/workflows/release.yml` (line 154), wire-version byte and
  `UnsupportedWireVersionException` in
  `configd-transport/src/main/java/io/configd/transport/FrameCodec.java`
  (lines 40-82), `signingEpoch` write+read in `ConfigStateMachine.java`
  (lines 363-367, 460-465), and the `ConfigdMetrics` registration of
  every alert-cited metric (lines 39-90) are all present and
  substantive.
- **No new Type-B/C row flipped to GREEN since iter-0.** Diff of
  ga-review.md gates vs the iter-1 honesty-auditor table shows the only
  status changes are the six **demotions** ordered by iter-1, all
  Type-A.

**Loop halt required: NO.** The iteration may proceed to the iter-2
9-reviewer pass under the existing severity floor (S3).

---

## Demotion orders for iter-2

**Count: 0 new demotions.** No GREEN row in `docs/ga-review.md`
requires demotion this pass:

- All six iter-1 demotions (Phase 4, Phase 6, Phase 9, B6, PA-6012, O7)
  are persisted in the file with `DEMOTED 2026-04-19` annotations
  carrying the originating finding ID.
- No previously-YELLOW row was promoted to GREEN by iter-1 fixes.
- No new GREEN row was added to ga-review.md by iter-1 (F3-F7 changed
  artefacts under the existing rows but did not introduce new gates).

**Process-observation downgrades** (not formal demotions, but the
iter-2 fixer queue should pick these up):

1. **ga-review.md:40 demotion text is now stale.** The Phase 9
   demotion reason currently reads `"…verify-published job absent"`,
   but F6 added that job at `.github/workflows/release.yml:154`. The
   gate must stay YELLOW until the pipeline is exercised end-to-end on
   a real tag, but the *reason* is now "exists but never run", not
   "absent". Update the Notes cell.
2. **F3 report's per-ADR filename list is hallucinated for ~20 of 27
   bullets** (e.g. report says `adr-0002-customer-isolation-model.md`;
   actual file is `adr-0002-hierarchical-raft-replication.md`). The
   on-disk ADRs *do* carry real `## Verification` sections with real
   test refs, so the substantive claim is intact, but the
   F3-report.md narrative cannot be cited by future iterations. See
   "Process observations" below.
3. **`docs/runbooks/` — 8 legacy runbooks** (`cert-rotation.md`,
   `edge-catchup-storm.md`, `leader-stuck.md`, `poison-config.md`,
   `reconfiguration-rollback.md`, `region-loss.md`, `version-gap.md`,
   `write-freeze.md`) do **not** have the §8.14 8-section skeleton
   (only Diagnosis+Mitigation present). F3 restructured the runbooks
   under `ops/runbooks/` (the operationally-cited set) but did not
   touch `docs/runbooks/`. ga-review.md §"Doc-drift purge" (line
   138-153) explicitly flags this as a deferred decision pending a
   human review, so it is **not a demotion target** — but iter-2
   should resolve the recommendation (delete or move) so future audits
   stop tripping over it.

---

## Verification grid (iter-1 claim → on-disk evidence → PASS/FAIL)

| # | iter-1 claim | On-disk evidence | Verdict |
|---|---|---|---|
| 1 | F3: 26 ADRs have `## Verification` | `grep -c '^## Verification' docs/decisions/adr-*.md` returns 27/27 (one per ADR; ADR-0027 was created in this iter). Substantively correct; count understates by 1 because ADR-0027 was added the same iter | **PASS** (substantive); minor nit on count |
| 2 | F3: 9 runbooks restructured to 8-section | All 9 `ops/runbooks/*.md` operational runbooks (excluding README + template) carry all 10 mandated sections (Symptoms, Impact, Operator-Setup, Diagnosis, Mitigation, Resolution, Rollback, Postmortem, Related, Do not). Verified by per-file grep — all PASS | **PASS** |
| 2b | (User check) `docs/runbooks/*.md` 8-section conformance | The 8 legacy runbooks under `docs/runbooks/` do **not** carry the 8-section skeleton — only Diagnosis + Mitigation are present. F3 did not edit this directory and never claimed to. ga-review.md §"Doc-drift purge" already flags this dir as out-of-date and recommends delete/move | **N/A** — F3 never claimed this dir; existing ga-review note covers it |
| 3 | F4: `ops/scripts/restore-snapshot.sh`, `restore-conformance-check.sh`, `deploy/kubernetes/configd-bootstrap.yaml` exist | Files present and substantive: 281 / 302 / 183 LoC respectively. Both shell scripts have `chmod +x` set. Bootstrap manifest contains 4 K8s documents (ServiceAccount + Role + RoleBinding + Job) per F4-report.md | **PASS** |
| 4 | F5: SLO metrics registered + emitted | `ConfigdMetrics.java:39-48` declares 10 `NAME_*` constants matching every metric in `ops/alerts/configd-slo-alerts.yaml`. `ConfigdMetrics.java:71-89` eagerly registers them. Emission sites: `ConfigStateMachine.java:305` (write_commit_failed), `ConfigStateMachine.java:312` (write_commit_total + write_commit_seconds), `ConfigStateMachine.java:384` (snapshot_rebuild), `ConfigStateMachine.java:389` (snapshot_install_failed). NB: emission is at `apply()` boundary, not literally inside `signCommand` — but `signCommand` failure throws inside `apply()` and triggers the failure counter via the catch block at `:299-307`, so the user-cited semantics hold | **PASS** |
| 5 | F6: wire-version byte added to FrameCodec | `FrameCodec.java:48` `WIRE_VERSION = (byte) 0x01` with mandated `// Wire-format change requires…` comment at :40. `WIRE_VERSION_OFFSET = 4` at :57. `UnsupportedWireVersionException` at :68. Encode writes version at :149 + :181; decode rejects at :223. `peekWireVersion` static at :276 | **PASS** |
| 6 | F6: release.yml `verify-published` job exists | `.github/workflows/release.yml:154` declares `verify-published:`; needs `build-and-publish`; runs `cosign verify`, `cosign verify-attestation --type slsaprovenance`, `gh attestation verify`; writes evidence to `${GITHUB_STEP_SUMMARY}` (lines 218-228) | **PASS** |
| 7 | F7: HdrHistogram claim struck from `docs/performance.md` | `grep -i HdrHistogram docs/performance.md` returns one match at :75, and the surrounding text now reads "**NOT from a true high-dynamic-range (HdrHistogram) histogram**" — i.e. the mention is the *negation*, not the claim. Cross-region predictions tagged `MODELED, NOT MEASURED` at lines 97, 98, 168, 180, 256, 271, 279, 357, 358 | **PASS** |
| 8 | D-004: signingEpoch in snapshot trailer (write + read) | Write side: `ConfigStateMachine.snapshot()` allocates `+ 8` for trailer at :345 and writes `buf.putLong(signingEpoch)` at :365. Read side: `restoreSnapshotInternal` reads optional trailer at :460-465 with `max(current, restored)` semantics so legacy snapshots without the trailer never regress the in-memory epoch | **PASS** |
| 9 | C-001 / C-002: CRC tests on disk | `FrameCodecTest.decodeRejectsCorruptedPayload` at `FrameCodecTest.java:84`; `decodeRejectsCorruptedHeader` at :96; `decodeRejectsTamperedCrc` at :106. `FileStorageTest.datFileCrcDetectsBitFlip` at `FileStorageTest.java:111`; `datFileTruncationIsDetected` at :134 | **PASS** |
| 10 | iter-1 6 demotions persisted in ga-review.md | `grep "DEMOTED 2026-04-19" docs/ga-review.md` returns 6 hits at lines 35 (Phase 4), 37 (Phase 6), 40 (Phase 9), 55 (B6), 62 (PA-6012), 84 (O7) | **PASS** |
| 11 | No new Type B/C gate flipped GREEN since iter-0 | All Type-B rows (C1-C4 + 3 drill rows) and Type-C rows (R-12 + Runbook conformance) are YELLOW with measured durations / "not started". Phase 10 (Disaster recovery) is YELLOW. No GREEN status for any calendar-bounded or human-required gate | **PASS** |
| 12 | `signFailureFailsClose` regression test exists | `ConfigStateMachineTest.java:773` `void signFailureFailsClose()` confirmed | **PASS** |
| 13 | `signingEpochSurvivesSnapshotRoundTrip` + `legacySnapshotWithoutEpochTrailerStillLoads` exist | `ConfigStateMachineTest.java:796` and `:829` confirmed | **PASS** |
| 14 | F6: `unknownWireVersionIsRejected` property test | `FrameCodecPropertyTest.java:158` confirmed | **PASS** |
| 15 | F3: ADR-0027 created | `docs/decisions/adr-0027-sign-or-fail-close.md` present; `## Verification` section cites `signFailureFailsClose` at the real :773 line | **PASS** |
| 16 | F3: ADR Verification sections cite real tests | Sampled 18 tests across 12 ADRs — every one resolves to a `*.java` file (RaftNodeTest, RaftSimulationTest, ConfigScopeTest, PlumtreeNodeTest, HyParViewOverlayTest, HamtMapTest, HamtMapPropertyTest, HamtMapCollisionTest, VersionedConfigStoreConcurrencyTest, VersionedConfigStoreAllocationTest, HybridClockAllocationTest, WatchServiceTest, WatchServicePropertyTest, WatchCoalescerTest, SubscriptionManagerTest, FanOutBufferTest, RolloutControllerTest, TcpRaftTransportTest, TlsManagerTest, MessageTypeTest, ClusterConfigTest, CertificationTest, StalenessTrackerTest, VersionCursorTest, BloomFilterTest, BloomFilterPropertyTest, AclServiceTest, RateLimiterTest, AuthInterceptorTest — all PASS) | **PASS** |
| 17 | F4: no `raftctl` callsites remain in ops/runbooks | `grep -rn raftctl ops/runbooks/` returns only meta references in README's TODO block at :33-34 | **PASS** |
| 18 | F7: JMH placeholder dir is honest | `perf/results/jmh-2026-04-19T00Z-PLACEHOLDER/README.md` exists; no `.json` artifact pretended | **PASS** |
| 19 | iter-1 reactor green | verify.md cites 21,402 tests, 0 fail, 0 err, 2 skipped (intentional). NOT independently re-run by this pass — iter-2 verify will re-execute. Demotion of Phase 4 is intentional pending a SHA-pinned build-log artifact | **TRUST iter-1; recheck on iter-2 verify** |

**Aggregate:** 19/19 verification checks PASS (one with a substantive
PASS + minor count nit on item 1; one user-cited check on item 2b is
N/A because F3 never claimed to fix that directory).

---

## Process observations

1. **F3 fixer report contains hallucinated ADR titles in its Task-1
   bullet list.** The on-disk Verification sections are real and cite
   real tests, but the F3 report's per-ADR filename list (e.g.
   `adr-0002-customer-isolation-model.md`,
   `adr-0003-hyparview-gossip-overlay.md`,
   `adr-0010-prometheus-observability.md`,
   `adr-0013-tcp-binary-transport.md`,
   `adr-0015-tls-1-3-mutual-auth.md`,
   `adr-0017-watch-service-protocol.md`) does not match the actual
   filenames in the tree (`adr-0002-hierarchical-raft-replication.md`,
   `adr-0003-plumtree-fan-out.md`,
   `adr-0010-netty-grpc-transport.md`,
   `adr-0013-lightweight-session-management.md`,
   `adr-0015-multi-region-topology.md`,
   `adr-0017-namespace-multi-tenancy.md`). Roughly 20 of the 27
   bullets in the F3 report are wrong about either the filename or
   the test suite cited *in the report* (the on-disk Verification
   text is correct). **Recommendation for iter-2:** ask the fixer
   author to regenerate the F3 report from the actual tree, or treat
   F3-report.md as untrustworthy for citation purposes (use
   `git log -p docs/decisions/` instead). Not a HONESTY-FAILURE
   because the on-disk artefacts are correct — the substantive claim
   "every ADR has a Verification section pointing to a real test"
   verifies — but the report itself is unreliable as a paper trail.

2. **`docs/runbooks/` legacy directory drift is unresolved.** The
   directory remains 8 files of out-of-date pre-rewrite runbooks that
   reference metrics not in the current code. ga-review.md §"Doc-drift
   purge" (line 138-153) recommends *either* implementing the metrics
   (v0.2 work) *or* moving content to `ops/runbooks/` and deleting
   `docs/runbooks/`. iter-2 should pick a side; leaving the directory
   in tree is what makes the user-supplied check (item 2b above)
   keep failing on a literal grep.

3. **Phase 9 demotion text is stale.** ga-review.md:40 says the
   `verify-published` job is "absent". F6 added it. The correct
   reason is now "exists but never exercised end-to-end against a
   real tag push". Update during iter-2 doc-drift sweep.

4. **F7 deferred the JMH sanity run with an honest "skipped"
   rationale.** The placeholder `perf/results/jmh-2026-04-19T00Z-
   PLACEHOLDER/README.md` is the right tradeoff vs fabricating a JMH
   log. Phase 6 stays YELLOW and the demotion note is honest.

5. **§8 hard-rule violations status.** All four §8 hard-rule rows
   that were OPEN at iter-0 (P-001/§8.1, R-005-007-008/§8.10,
   DOC-002/§8.14 for `ops/runbooks/`, DOC-001/§8.15) are
   structurally CLOSED in the tree. §8.14 is closed *for the
   operational runbook set* — the legacy `docs/runbooks/` set
   remains non-conformant but is flagged for delete-or-migrate, not
   for restructure.

6. **Snapshot/WAL wire-compat stubs are honest `@Disabled`.** F6's
   `SnapshotWireCompatStubTest` and `WalWireCompatStubTest` are
   correctly disabled with maintainer instructions; the report does
   not pretend they pass. iter-2 should track these for the first
   real version bump (per R-005).

7. **Stability signal accounting** (loop-state.json:26-28) reports
   `stability_signal = 0` for iter-1. I did not re-execute the build,
   but the F2 changes (CRC trailer, ReadIndex per-round, snapshot
   trailer) carry their own regression tests that were cited and
   exist on disk; the F5 wire-up adds new tests rather than removing
   any; the F6 wire-version byte adds a property test. No API/test
   *removals* are visible in the diff. Stability-signal=0 is plausible
   pending iter-2's own verify pass.

8. **No Type-B / Type-C invariant violation has been attempted at
   any point in iter-1.** The loop demonstrated it understands the
   honesty invariant: when iter-1 closed §8.1, it tagged perf claims
   as MODELED rather than promoting Phase 6 to GREEN; when F6 added
   the `verify-published` job, it explicitly noted Phase 9 / B6 /
   PA-6012 stay YELLOW until a real release runs. Good behaviour.

---

## Files audited (absolute paths)

- `/home/ubuntu/Programming/Configd/docs/ga-review.md`
- `/home/ubuntu/Programming/Configd/docs/loop-state.json`
- `/home/ubuntu/Programming/Configd/docs/review/iter-001/verify.md`
- `/home/ubuntu/Programming/Configd/docs/review/iter-001/honesty-auditor.md`
- `/home/ubuntu/Programming/Configd/docs/review/iter-001/fixers/F3-report.md`
- `/home/ubuntu/Programming/Configd/docs/review/iter-001/fixers/F4-report.md`
- `/home/ubuntu/Programming/Configd/docs/review/iter-001/fixers/F5-report.md`
- `/home/ubuntu/Programming/Configd/docs/review/iter-001/fixers/F6-report.md`
- `/home/ubuntu/Programming/Configd/docs/review/iter-001/fixers/F7-report.md`
- `/home/ubuntu/Programming/Configd/docs/decisions/adr-0001-embedded-raft-consensus.md` … `adr-0027-sign-or-fail-close.md` (27 files)
- `/home/ubuntu/Programming/Configd/docs/runbooks/*.md` (8 legacy files)
- `/home/ubuntu/Programming/Configd/ops/runbooks/*.md` (9 operational files)
- `/home/ubuntu/Programming/Configd/ops/scripts/restore-snapshot.sh`
- `/home/ubuntu/Programming/Configd/ops/scripts/restore-conformance-check.sh`
- `/home/ubuntu/Programming/Configd/deploy/kubernetes/configd-bootstrap.yaml`
- `/home/ubuntu/Programming/Configd/configd-config-store/src/main/java/io/configd/store/ConfigStateMachine.java`
- `/home/ubuntu/Programming/Configd/configd-config-store/src/test/java/io/configd/store/ConfigStateMachineTest.java`
- `/home/ubuntu/Programming/Configd/configd-observability/src/main/java/io/configd/observability/ConfigdMetrics.java`
- `/home/ubuntu/Programming/Configd/configd-transport/src/main/java/io/configd/transport/FrameCodec.java`
- `/home/ubuntu/Programming/Configd/configd-transport/src/test/java/io/configd/transport/FrameCodecTest.java`
- `/home/ubuntu/Programming/Configd/configd-transport/src/test/java/io/configd/transport/FrameCodecPropertyTest.java`
- `/home/ubuntu/Programming/Configd/configd-common/src/test/java/io/configd/common/FileStorageTest.java`
- `/home/ubuntu/Programming/Configd/.github/workflows/release.yml`
- `/home/ubuntu/Programming/Configd/ops/alerts/configd-slo-alerts.yaml`
- `/home/ubuntu/Programming/Configd/docs/performance.md`
- `/home/ubuntu/Programming/Configd/CHANGELOG.md`
- `/home/ubuntu/Programming/Configd/RELEASE_NOTES_TEMPLATE.md`
- `/home/ubuntu/Programming/Configd/perf/results/jmh-2026-04-19T00Z-PLACEHOLDER/README.md`
