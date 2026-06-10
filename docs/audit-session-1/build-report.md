# Build-Integrity Report — Session 1 Ground Truth

Role: build-integrity-engineer. Date: 2026-06-10.
Audited commit: `423a654cf6c2a2a7a443e9bebbbe952572a470b3` (branch `session-1-ground-truth`).
Method: every claim below cites a re-runnable command or file:line. Docs were treated as claims, not evidence. No source file in the audited tree was modified. One verification-only experiment was performed in a throwaway clone (`/tmp/pv-strip`, see §2.3) — the audited clone `/home/ubuntu/ws-clean` is byte-identical to the repo (its post-build `git status --short` is empty).

---

## 1. Environment

| Item | Value | Evidence |
|---|---|---|
| OS | Ubuntu 26.04 LTS, kernel `7.0.0-1006-aws`, x86_64 | `uname -a; cat /etc/os-release` |
| Hardware | 2 CPUs, 7.7 GiB RAM | `nproc; free -h` |
| JDK | OpenJDK 25 (Corretto-25.0.0.36.2, build 25+36-LTS) | `java -version` |
| Maven | 3.9.9 via wrapper | `./mvnw -version` (emits 4 cosmetic JDK-25 native-access WARNINGs from jansi — launcher noise, not project) |
| Repo HEAD | `423a654cf6c2a...`, branch `session-1-ground-truth`, clean working tree | `git rev-parse HEAD; git status --short` in /home/ubuntu/Code/Configd |
| Clean clone | `/home/ubuntu/ws-clean`, same HEAD | `git clone -b session-1-ground-truth /home/ubuntu/Code/Configd /home/ubuntu/ws-clean && git rev-parse HEAD` |

### `--enable-preview`: configured but NOT required (docs claim is overstated)

- The pom passes it to javac (`pom.xml:104`) and to surefire JVMs (`pom.xml:114`).
- **Zero** of the 797 compiled class files are preview-marked (preview classes must carry minor version `0xFFFF` per JEP 12; all are `0x0000`):
  `find . -name "*.class" -path "*/target/*" | while read f; do m=$(od -A n -t x1 -j 4 -N 2 "$f" | tr -d ' '); [ "$m" != "0000" ] && echo "$m $f"; done` → no output, 797 files checked.
- Conclusive experiment: a throwaway clone with both `--enable-preview` args deleted from `pom.xml` compiles the **full reactor (main + test sources, all 12 modules)** successfully:
  `git clone -b session-1-ground-truth /home/ubuntu/Code/Configd /tmp/pv-strip && cd /tmp/pv-strip && sed -i 's|<arg>--enable-preview</arg>||; s|<argLine>--enable-preview</argLine>||' pom.xml && ./mvnw -B -q clean test-compile -o` → exit 0.
- Conclusion: the flag is vestigial. Build requires JDK 25 (`<release>25</release>`, `pom.xml:102`) but no preview features.

---

## 2. Clean-checkout build result

Command (exact):
```
git clone -b session-1-ground-truth /home/ubuntu/Code/Configd /home/ubuntu/ws-clean
cd /home/ubuntu/ws-clean && ./mvnw -B -fae clean verify 2>&1 | tee /home/ubuntu/ws-clean/build.log
```

**Result: BUILD SUCCESS, exit code 0, wall time 05:25 min** (build.log:2764-2766; log finished 2026-06-10T18:00:40Z). No fallback to `clean test` was needed; `verify` ran tests + SpotBugs + shade without anything pathological. Per-module (build.log:2750-2762): parent + all 12 modules SUCCESS; slowest module configd-testkit at 01:21 min (the seed sweep).

Note for comparisons: historical docs quote "~61s" for `./mvnw test`; this run is `clean verify` on a 2-CPU box and additionally pays for SpotBugs (13 forked executions) and two shaded jars.

---

## 3. Warnings (complete inventory from build.log; 65 `[WARNING]` lines total)

| # | Warning class | Count | Where | Evidence |
|---|---|---|---|---|
| 1 | `Parameter 'spotbugsVersion' is unknown for plugin 'spotbugs-maven-plugin:4.9.3.0:spotbugs (spotbugs-verify)'` | 13 (parent + 12 modules) | plugin config | build.log:32 et al.; config at pom.xml:161 — the pin is **ignored** by the plugin |
| 2 | javac deprecation: `confirmAll(int,int) in io.configd.raft.ReadIndexState has been deprecated` | 7 | consensus-core test code: `ReadIndexStateTest.java:153,166,177,185,232,250`; `ReadIndexLinearizabilityReplayerTest.java:86` | `grep deprecated build.log` |
| 3 | javac `[lossy-conversions]`: `implicit cast from int to byte in compound assignment is possibly lossy` | 3 | `configd-common/src/test/.../FileStorageTest.java:116,132`; `configd-config-store/src/test/.../ConfigSignerTest.java:99` | build.log:60,61,620 |
| 4 | maven-shade overlapping resources/classes (META-INF/LICENSE, NOTICE, MANIFEST.MF; micrometer/junit/jmh/HdrHistogram overlaps) | ~35 lines across 2 shade executions | configd-testkit, configd-server uber-jars | `grep "overlapping" build.log` |
| 5 | surefire skip-summary warnings (`Tests run: ... Skipped: N`) | 5 lines | WalWireCompatStubTest, SnapshotWireCompatStubTest, CheckerSelfTest + 2 module summaries | build.log:588,2737 |
| 6 | `No processor claimed any of these annotations: ...junit...` | 1 | configd-testkit testCompile (JMH annprocessor present) | build.log:2172 |
| 7 | (env, stderr, not `[WARNING]`) JDK-25 native-access warnings from the mvnw/jansi launcher | 4 | head of log | build.log:1-4 |

- **Zero preview-feature warnings** (`grep -ci "preview" build.log` → 0 relevant), **zero unchecked warnings**, **zero javac `Note:` lines** — consistent with §1 (no preview usage) and `-Xlint:all` being active (pom.xml:105).
- Runtime test-output noise (intentional negative-path tests, not build warnings): `Authentication is DISABLED (--auth-token not set)` banner ×15, `Rejecting delta ...: signature verification failed` ×2, `SEVERE: Invariant violated [version.monotonic]` lines from InvariantMonitor tests.

---

## 4. Per-module test table (parsed from `*/target/surefire-reports/TEST-*.xml`)

Parse command: python over `glob('*/target/surefire-reports/TEST-*.xml')` summing `tests/failures/errors/skipped` attributes (matches Maven's per-module console summaries at build.log:112,335,588,1413,1561,1772,1890,2080,2140,2277,2657,2737).

| Module | Tests run | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| configd-common | 86 | 0 | 0 | 0 |
| configd-transport | 111 | 0 | 0 | 0 |
| configd-consensus-core | 159 | 0 | 0 | 2 |
| configd-config-store | 240 | 0 | 0 | 0 |
| configd-edge-cache | 151 | 0 | 0 | 0 |
| configd-observability | 127 | 0 | 0 | 0 |
| configd-replication-engine | 119 | 0 | 0 | 0 |
| configd-distribution-service | 146 | 0 | 0 | 0 |
| configd-control-plane-api | 50 | 0 | 0 | 0 |
| configd-testkit | 20,132 | 0 | 0 | 0 |
| configd-server | 77 | 0 | 0 | 0 |
| configd-linz | 10 | 0 | 0 | 6 |
| **Total** | **21,408** | **0** | **0** | **8** |

---

## 5. Suite-count reconciliation

Live clean-run total: **21,408**. Of the six historical totals:

| Claimed total | Source (claim) | Matches live? |
|---:|---|---|
| 20,132 | `docs/loop-state.json:33,63` (iter-2) | No (point-in-time; coincidentally equals today's testkit-only count) |
| 20,149 | `docs/progress.md:356` (Phase 8) | No |
| 21,246 | `docs/verification/final-report.md:16,73` | No |
| 21,285 | `docs/STATE-OF-REALITY.md:133` (quoting final-report era) | No |
| 21,394 | `docs/STATE-OF-REALITY.md:29,77` (live count 2026-06-06) | No — stale by +14: +10 from configd-linz added by commit 423a654 (`git diff --stat 56c1684~1..HEAD -- '*src/test*'` → CheckerSelfTest +6, HistoryWriterUnitTest +4) and +4 from A2-era changes |
| **21,408** | `docs/READINESS-LEDGER.md:123,415` (A3-B entry) | **YES — exact match, incl. 0 fail / 0 err / 8 skipped** |

Docs actually quote **eight** distinct totals, not six: `docs/prr/HISTORICAL-NOTICE.md:14` and `docs/review/HISTORICAL-NOTICE.md:15` additionally list 21,222 and 21,402 (21,402 also at `docs/loop-state.json:32`).

### Dominant parameterized contributor — verified

- `configd-testkit/src/test/java/io/configd/testkit/SeedSweepTest.java` contributes **exactly 20,000** executions: 2 `@ParameterizedTest` methods (`electionSafety` line 31, `commitSurvivesLeaderFailure` line 60) × `LongStream.range(0, 10_000)` default (line 25, `-Dconfigd.seedSweep.count`). Surefire XML: `TEST-io.configd.testkit.SeedSweepTest.xml` → `tests=20000`.
- Split: **sweep = 20,000 (93.4%)**; testkit non-sweep = 132; all other modules = 1,276. **"Real" (non-sweep) tests protecting the system: 1,408.** STATE-OF-REALITY.md:77 claimed non-testkit = 1,262 — now 1,276 (stale, direction consistent).
- Quality caveat on the sweep: `commitSurvivesLeaderFailure` has **three silent-return early exits** (SeedSweepTest.java:65-67, 72-75, 85-88 — "skip gracefully" via `return`). Seeds that never elect a leader, never commit, or never re-elect count as **passes**, not skips. The reported 20,000 green therefore overstates assertion coverage by an unmeasured amount.

---

## 6. Skips & disables

Skipped at runtime: 8 total, fully accounted for:

| Tests | Class | Mechanism / reason |
|---|---|---|
| 6 | `io.configd.linz.CheckerSelfTest` | `@EnabledIfEnvironmentVariable(named = "PORCUPINE_BIN", matches = ".+")` (CheckerSelfTest.java:26) — skipped because no Porcupine binary on this machine; **also skipped in CI** (ci.yml never sets PORCUPINE_BIN) |
| 1 | `io.configd.raft.SnapshotWireCompatStubTest` | `@Disabled("Stub: enable on first version bump (R-005). No v0 snapshot fixture exists yet.")` (SnapshotWireCompatStubTest.java:58) |
| 1 | `io.configd.raft.WalWireCompatStubTest` | `@Disabled("Stub: enable on first version bump (R-005). No v0 WAL fixture exists yet.")` (WalWireCompatStubTest.java:58) |

`grep -rn "@Disabled\|@Ignore" --include=*.java /home/ubuntu/ws-clean/` → exactly the 2 occurrences above; no `@Ignore` anywhere. Cross-check vs surefire XML skip counts: consistent (2 + 6 = 8). The ledger's "8 skipped (6 = Porcupine self-test ... 2 pre-existing)" (READINESS-LEDGER.md:415) is **accurate**.

---

## 7. Dependency & static-analysis audit

**Dependencies** (root `pom.xml:27-92`; module poms checked): agrona 1.23.1, jctools-core 4.0.5, junit-jupiter 5.11.4, jmh 1.37, HdrHistogram 2.2.2, micrometer-core 1.14.4, jqwik 1.9.2. Per-module third-party usage is small (agrona/jctools in core modules; HdrHistogram+micrometer in observability; jmh in testkit; control-plane-api, edge-cache, linz, server have none beyond test deps).

- **No third-party SNAPSHOTs, no `<repositories>`/`<pluginRepositories>`, no enforcer plugin anywhere**: `grep -rn "SNAPSHOT\|<repositories>\|<repository>\|enforcer\|pluginRepositor" /home/ubuntu/ws-clean/*/pom.xml /home/ubuntu/ws-clean/pom.xml | grep -v "0.1.0-SNAPSHOT"` → empty (only the project's own `0.1.0-SNAPSHOT` version exists). Everything resolves from Maven Central. No maven-enforcer-plugin = no requireMavenVersion/requireJavaVersion/dependency-convergence gates.

**SpotBugs — bound and runs, but cannot fail the build:**
- Bound to `verify` phase, goal `spotbugs` (pom.xml:190-198) — it executed 13× in this run (e.g. build.log "`--- spotbugs:4.9.3.0:spotbugs (spotbugs-verify) ---`" per module). It is NOT decorative in the "never runs" sense.
- BUT: goal is `spotbugs` (report-only), not `check`, and `failOnError=false` (pom.xml:151) — so **no finding can ever break the build**. Gate-wise it is decorative.
- The `<spotbugsVersion>4.9.3</spotbugsVersion>` pin (pom.xml:161) is rejected: `Parameter 'spotbugsVersion' is unknown` ×13 (build.log:32 etc.).
- Exclusions (`spotbugs-exclude.xml`): all `*Test*` classes, EI_EXPOSE_REP/REP2, SE_BAD_FIELD, DM_DEFAULT_ENCODING, RV_RETURN_VALUE_IGNORED_BAD_PRACTICE — broad, each with a stated reason.

**What SpotBugs flagged this run** (`grep -o "<BugInstance " */target/spotbugsXml.xml | wc -l` per module): 5,660 total —
| Module | Count | Breakdown |
|---|---:|---|
| configd-testkit | 5,633 | all `UUF_UNUSED_FIELD` on JMH-**generated** padding classes (`io.configd.bench.jmh_generated.*_jmhType_B1/B3`) — pure noise; exclude filter doesn't cover generated sources |
| configd-consensus-core | 19 | **15× `AT_STALE_THREAD_WRITE_OF_PRIMITIVE` + 3× `AT_NONATOMIC_OPERATIONS_ON_SHARED_VARIABLE` on `io.configd.raft.RaftNode`** + 1 other |
| configd-linz | 4 | `REC_CATCH_EXCEPTION` in `io.configd.linz.client.ConfigClient` |
| configd-distribution-service | 3 | `VO_VOLATILE_INCREMENT` + `AT_NONATOMIC_OPERATIONS_ON_SHARED_VARIABLE` on `io.configd.distribution.FanOutBuffer`, `URF_UNREAD_FIELD` |
| configd-server | 1 | `DLS_DEAD_LOCAL_STORE` in `ConfigdServer` |
| other 7 modules | 0 | |

The 18 concurrency-class findings on `RaftNode` may be benign under a single-threaded tick design, but nothing in the build asserts that — handing to the consensus auditor.

---

## 8. CI audit (`.github/workflows/ci.yml`, 116 lines; `release.yml`, 248 lines — both read in full)

**ci.yml — 3 jobs** (triggers: push/PR to main):
1. `build-and-test` (ci.yml:13-46): JDK 25 Corretto; steps: `mvn clean install` (line 29), then re-runs subsets: property tests (line 32), simulation tests (line 35), and `SeedSweepTest` with `-Dconfigd.seedSweep.count=10000` (line 38) — which is the **default** count, so the 20k-execution sweep runs **twice** per CI run (once inside `install`, once explicitly). Uploads surefire XMLs.
2. `tlc-model-check` (ci.yml:48-67): downloads tla2tools v1.8.0, runs TLC on **ConsensusSpec, ReadIndexSpec, SnapshotInstallSpec** — which is **all 3** `.tla` specs in `spec/` (verified: `find . -name "*.tla" -not -path "./spec/*"` → none elsewhere). 30-min timeout.
3. `wire-compat` (ci.yml:69-116): fails if `GoldenFixtures.java` changed without a `FrameCodec.WIRE_VERSION` bump (ADR-0029 §8.10 guardrail).

**Failure swallowing:** none found. `grep -n "continue-on-error\||| true\|failure.ignore\|set +e" .github/workflows/*.yml` → only `-Dmaven.test.failure.ignore=false` (explicitly strict) and `if: always()` on the artifact-upload step (normal). GitHub's default `bash -e -o pipefail` applies to multi-command steps.

**Claimed Trivy + gitleaks supply-chain job: DOES NOT EXIST and NEVER EXISTED.**
- `docs/loop-state.json:36-40` claims `ci_secret_scan_wired: true`, `ci_dep_cve_scan_wired: true`, evidence: "`.github/workflows/ci.yml supply-chain-scan job: Trivy fs scanners=vuln,secret,misconfig + gitleaks-action@v2 over full history`".
- Live ci.yml has no such job. Git history: only 2 commits ever touched ci.yml (`git log --all --oneline -- .github/workflows/ci.yml` → d849eb1, 53c86f8); `git show <each>:.github/workflows/ci.yml | grep -i "trivy\|gitleaks\|supply"` → no match in either version.
- Two more in-repo references to the phantom job: `.gitleaks.toml:10` ("This config is referenced by .github/workflows/ci.yml" — false) and `release.yml:97-99` ("The CI workflow ... only Trivy-scans the filesystem" — false).
- The only Trivy that exists is the **release-time runtime-image scan** (release.yml:108-114, exit-code 1 on HIGH/CRITICAL) — it runs only on `v*.*.*` tag pushes, i.e. never on PRs/main.

**Multi-node testing in CI: none.** The "Simulation tests" step is the in-process `SimulatedNetwork` testkit (single JVM). The separate-JVM linearizability harness (`configd-linz`, R-04 "CLOSED" per READINESS-LEDGER.md:123) is not invoked by any workflow; its only JUnit entry point (`CheckerSelfTest`) is env-gated on `PORCUPINE_BIN`, which no workflow sets — so in CI those 6 tests silently skip.

**CI vs developer command drift:** CI uses bare `mvn` (unpinned runner Maven) for all 4 build steps (ci.yml:29,32,35,38); developers and release.yml use the pinned wrapper `./mvnw` (3.9.9). Also `mvn clean install` (CI) vs `./mvnw clean verify` (docs/release) — `install` ⊃ `verify`, so SpotBugs runs in CI too; functionally close but not identical.

**release.yml** (tag-driven): tag↔POM version check → `./mvnw clean verify` → CycloneDX SBOM → buildx image from `docker/Dockerfile.runtime` (exists, verified) → Trivy image scan (blocking) → cosign keyless sign → SLSA attestation → GH release; then `verify-published` job re-pulls by digest and re-verifies signature + provenance. No swallowed failures (`set -euo pipefail` in run steps). Never exercised on this repo's history (no tags in clone: implied by 18-commit local-only history; out of scope to assert further).

---

## 9. The `.iter3-deferred-tests/` trail

Claim chain: `docs/loop-state.json:33,64`, `docs/review/iter-002/fixers/F1-report.md:105-117`, `docs/review/iter-002/verify.md:38-55` — five test artifacts with API drift were moved out of `src/test/java` into `/home/ubuntu/Programming/Configd/.iter3-deferred-tests/` (note the old repo path) as "P1 carry-over ... reversible by `mv`".

**Git facts** (`git log --all --oneline` → 18 commits total; `git log --all --name-status -- '*iter3*'` → empty):
- No path matching `*iter3*` was **ever** committed on any branch. The deferred files were explicitly untracked ("not git-tracked since pre-existing untracked", verify.md:51-52).
- The directory exists nowhere on this machine: `ls /home/ubuntu/Code/Configd/.iter3-deferred-tests` → No such file; `/home/ubuntu/Programming` → No such directory; filesystem-wide `find / -name "HttpApiServerMetricsTest.java" -o -name "ChaosScenariosTest.java"` → no results.

**Per-file disposition:**

| Deferred file | Claimed plan | Where it is now | Verdict |
|---|---|---|---|
| `FrameCodecPropertyTest.java` | promote in iter-3 W1+W2 | `configd-transport/src/test/java/io/configd/transport/FrameCodecPropertyTest.java`, committed in d849eb1 | RESTORED |
| `wirecompat/` (WireCompatGoldenBytesTest, WireFixtureGenerator, GoldenFixtures) | promote in iter-3 | `configd-transport/src/test/java/io/configd/transport/wirecompat/*`, committed in d849eb1 | RESTORED |
| `RaftMessageCodecPropertyTest.java` | promote in iter-3 | `configd-server/src/test/java/io/configd/server/RaftMessageCodecPropertyTest.java`, committed in d849eb1 | RESTORED |
| `HttpApiServerMetricsTest.java` | "Test file remains in `.iter3-deferred-tests/`" (production-readiness-code-level.md:223); still an open item (loop-state.json:16) | **nowhere** — not in tree, not in git history, not on disk | **LOST** |
| `ChaosScenariosTest.java` | "Test file remains in `.iter3-deferred-tests/`" (production-readiness-code-level.md:224); still an open item (loop-state.json:17) | **nowhere** | **LOST** |

The two lost files were never committed, and the directory holding them did not survive the repo's move from `/home/ubuntu/Programming/Configd` to `/home/ubuntu/Code/Configd`. The P1 carry-over items that reference them are now unactionable as written; the documented "reversible by `mv`" guarantee (verify.md:55) is false today.

---

## 10. Candidate findings

### BI-1 (P1) — Docs claim CI supply-chain scanning (Trivy fs + gitleaks) that never existed
- `docs/loop-state.json:37-39` asserts `ci_secret_scan_wired: true` / `ci_dep_cve_scan_wired: true` with evidence naming a "supply-chain-scan job" in ci.yml. `.gitleaks.toml:10` and `release.yml:97-99` repeat the claim.
- Evidence: `grep -in "trivy\|gitleaks\|supply" .github/workflows/ci.yml` → no match; `git log --all --oneline -- .github/workflows/ci.yml` → 2 commits (d849eb1, 53c86f8), neither version contains the job (`git show <sha>:.github/workflows/ci.yml`).
- Impact: an iter-0 prerequisite is recorded as satisfied with fabricated/stale evidence; no secret or CVE scanning runs pre-release (the only Trivy is the tag-time image scan).

### BI-2 (P1) — Two deferred test files silently lost (`HttpApiServerMetricsTest.java`, `ChaosScenariosTest.java`)
- Promised location `.iter3-deferred-tests/` (loop-state.json:64) exists nowhere; files never committed (`git log --all --name-status -- '*iter3*'` → empty) and absent from the entire filesystem (`find / -name ...` → empty). Open items loop-state.json:16-17 still depend on them; verify.md:55's "reversible by `mv`" is false.
- Impact: HttpApiServer metrics coverage and 5 chaos-API scenarios (W3/W4 P1 carry-overs) dropped without record; the work must be re-authored from prose descriptions.

### BI-3 (P2) — R-04 linearizability harness never runs in CI; its self-test skips everywhere by default
- `configd-linz` CheckerSelfTest 6/6 skip without `PORCUPINE_BIN` (CheckerSelfTest.java:26); no workflow sets it or invokes the harness (`grep -rn PORCUPINE .github/` → nothing). READINESS-LEDGER.md:123 marks R-04 CLOSED on local-only evidence; nothing protects it against regression.

### BI-4 (P2) — 93.4% of the suite is one seed-sweep test, with silent-pass paths
- 20,000 / 21,408 executions = `SeedSweepTest` (2 methods × 10,000 seeds; surefire XML `tests=20000`). Real non-sweep coverage = **1,408** tests. `commitSurvivesLeaderFailure` returns silently when no leader elected / commit times out / re-election fails (SeedSweepTest.java:65-67,72-75,85-88) — those seeds count as passes, so "20,000 green" overstates verified behavior. Headline suite-size claims in docs inherit this distortion.

### BI-5 (P2) — Static analysis is gate-free, and flags 18 concurrency warnings in the core Raft class
- SpotBugs runs at `verify` but with report-only goal + `failOnError=false` (pom.xml:151,190-198): findings can never fail any build, local or CI. Current findings include 15× `AT_STALE_THREAD_WRITE_OF_PRIMITIVE` + 3× `AT_NONATOMIC_OPERATIONS_ON_SHARED_VARIABLE` on `io.configd.raft.RaftNode` and `VO_VOLATILE_INCREMENT` on `io.configd.distribution.FanOutBuffer` (per `*/target/spotbugsXml.xml`). Possibly benign under tick-thread design — needs consensus-team disposition, but today nobody is forced to look.

### BI-6 (P3) — CI/dev build drift: bare unpinned `mvn` in CI, `./mvnw` everywhere else; sweep runs twice in CI
- ci.yml:29,32,35,38 use system `mvn`; release.yml + docs use wrapper-pinned 3.9.9. ci.yml:38 re-runs SeedSweepTest at the default 10k count already executed by `mvn clean install` (line 29).

### BI-7 (P3) — `--enable-preview` is vestigial; docs overstate the toolchain requirement
- Full reactor compiles with the flag removed (§1, /tmp/pv-strip experiment, exit 0); 0/797 class files preview-marked. Docs (e.g. loop-state.json:52,63 "under JDK 25 --enable-preview") imply a preview dependency that does not exist. Carrying the flag costs compatibility risk for zero benefit.

### BI-8 (P3) — Build hygiene: ignored plugin parameter, unkept exclude filter, deprecation/lossy warnings, 8 suite-size figures
- `spotbugsVersion` parameter rejected ×13 (build.log:32; pom.xml:161) — the documented analyzer pin doesn't apply.
- 5,633 SpotBugs `UUF_UNUSED_FIELD` noise from JMH-generated classes (testkit) drowns the 27 real findings.
- 7 deprecation warnings (`ReadIndexState.confirmAll`) + 3 lossy-conversion warnings in test code.
- Docs quote eight different suite totals (20,132/20,149/21,222/21,246/21,285/21,394/21,402/21,408); only 21,408 matches live. STATE-OF-REALITY.md:29 (21,394 / 2 skipped) is stale at HEAD.
- README.md is a single line (`# Configd`) — no build instructions at repo root.

---

## Appendix: artifacts kept for later phases
- `/home/ubuntu/ws-clean` — clean clone, fully built (`build.log` = 2,768 lines, surefire XMLs + spotbugsXml.xml per module). Working tree still clean (`git status --short` → empty).
- `/tmp/pv-strip` — preview-strip experiment clone (pom.xml modified there ONLY; not part of the audit tree).
