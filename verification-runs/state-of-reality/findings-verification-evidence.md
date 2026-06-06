# Findings — Verification Evidence (CRUX lens: what RUNS GREEN)

**Teammate:** verification-evidence · **Date:** 2026-06-06 · **Tree:** `/home/ubuntu/Code/Configd` (single worktree; `git worktree list` shows one entry at `main`)
**JDK:** Corretto 25.0.0.36.2 · **Build:** `./mvnw` (Maven 3.9.9 wrapper)

## Bottom line (5 bullets)

- **LIVE AGGREGATE (authoritative, from 390 surefire `TEST-*.xml` across all 11 modules): `Tests run: 21,394, Failures: 0, Errors: 0, Skipped: 2`.** Full `./mvnw -fae test` completed **exit code 0 (BUILD SUCCESS)**. Command: `./mvnw -q -fae test` -> exit 0; counts aggregated from `**/surefire-reports/TEST-*.xml`.
- **The headline "20,132 tests, all passing" in `docs/audit/test-completeness.md` is the `configd-testkit` MODULE total, NOT the whole suite — and 20,000 of those 20,132 are a SINGLE parameterized test (`SeedSweepTest`: 10,000 seeds x 2 methods).** Strip the seed sweep and testkit has 132 tests; the other 10 modules sum to 1,262. So "~21k tests" is dominated by one seed loop, not 21k distinct scenarios. Not a failure — but a materially misleading count.
- **Verification types that RUN GREEN here:** unit (JUnit 5), property-based (jqwik, 64 `@Property` methods), deterministic simulation (real seeded harness — `RaftSimulation`/`SimulatedNetwork` both `new Random(seed)`) + proven reproducible across two runs, TLC model check (I re-ran ConsensusSpec: **13,775,323 states / 3,299,086 distinct / depth 25 / "No error has been found"**, 1m32s), JMH (I built `benchmarks.jar` and ran `HybridClockBenchmark` -> real numbers).
- **ABSENT / DOC-ONLY:** **Jepsen is ABSENT** — zero Clojure project, zero `jepsen/` dir, zero nemesis harness; the word appears only in `docs/*.md` + `PROMPT.md`. **No JMH job and no Jepsen job in CI** (`grep -ci jmh|jepsen .github/workflows/ci.yml` -> 0/0). The `perf/results/jmh-...-PLACEHOLDER/` dir is an honestly-labeled empty placeholder; `perf/results/smoke/result.txt` is `status=YELLOW (no workload wired)`.
- **Two doc/artifact discrepancies found:** (1) `spec/tlc-results.md` lists a STALE invariant set (`NoStaleOverwrite`, no `LeaderCompleteness`) — the live `ConsensusSpec.cfg` checks 9 invariants incl. `LeaderCompleteness` + `VersionMonotonicity` and has *removed* `NoStaleOverwrite`. (2) `tlc-rerun.log` references path `/home/ubuntu/Programming/Configd/spec/` (a different tree than this audit's `/home/ubuntu/Code/Configd`).

---

## Verification Scorecard

| Type | Classification | Command (as run by me) | Headline numbers |
|---|---|---|---|
| **Unit (JUnit 5)** | **[VERIFIED-PASS]** | `./mvnw -q -fae test` (exit 0) | Whole suite 21,394 tests, 0 fail/0 err, 2 skipped. Non-testkit modules: 1,262 tests all green |
| **Property-based (jqwik)** | **[VERIFIED-PASS]** | same full run; 9 files / 64 `@Property` methods | All green within suite. tries range 1-500 (mostly 100-500). **Caveat:** 5 methods use `tries=1` (single-example, not a sweep) |
| **Deterministic Simulation (DST)** | **[VERIFIED-PASS]** | full run + 2x `./mvnw -pl configd-testkit -am test -Dtest=SeedSweepTest` | Both reruns exit 0, identical `tests=20000, failures=0`. Seeding is real: `RaftSimulation`/`SimulatedNetwork` -> `new java.util.Random(seed)`. Determinism proven (same input -> same green result twice) |
| **TLC model check** | **[VERIFIED-PASS]** (ConsensusSpec) | `java -XX:+UseParallelGC -jar spec/tla2tools.jar -config ConsensusSpec.cfg -workers auto ConsensusSpec.tla` | 13,775,323 states / 3,299,086 distinct / depth 25 / **No error**. 1m32s. Reproduces prior `tlc-rerun.log` distinct count exactly |
| **TLC — ReadIndexSpec / SnapshotInstallSpec** | **[EXISTS-UNTESTED]** | not run by me (CI only runs ConsensusSpec) | `.tla`+`.cfg` present with committed `_TTrace` artifacts; CI workflow runs ConsensusSpec only |
| **JMH benchmarks** | **[VERIFIED-PASS]** (infra executes) | `./mvnw -pl configd-testkit -am package -DskipTests` -> `benchmarks.jar` (6.9 MB); then `java -jar .../benchmarks.jar HybridClockBenchmark.now -f 1 -wi 1 -i 1 -r 1 -w 1` | Ran green: HybridClock `now` ~31 ns/op (system), ~3.5 ns/op (fixed). 9 benchmark classes present |
| **Integration (EndToEnd)** | **[VERIFIED-PASS]** | full run | `EndToEndTest` nested suites all green (e.g. `VersionCursorEnforcement` 4/4) |
| **Wire-compat (golden)** | **[VERIFIED-PASS]** (in-suite) + CI guard | full run + CI job `wire-compat` | `FrameCodecPropertyTest` etc. green; 2 `@Disabled` wire-compat *stubs* awaiting R-005 version bump (= the 2 skips) |
| **Jepsen** | **[ABSENT]** | `find -iname '*jepsen*'` / `find -name '*.clj'` -> nothing | No Clojure project, no nemesis/docker harness. Word appears only in docs/PROMPT |
| **Perf results artifacts** | **[DOC-ONLY / placeholder]** | inspected `perf/results/*` | `jmh-...-PLACEHOLDER/README.md` = "intentionally empty"; `smoke/result.txt` = `YELLOW (no workload wired)`. `docs/performance.md` numbers are NOT backed by committed JMH artifacts |

---

## Findings (claim | classification | command + output)

| # | Claim / expectation | Classification | Evidence (command + output) |
|---|---|---|---|
| 1 | "Full test suite verified: 20,132 tests, all passing" (`docs/audit/test-completeness.md`) | **[VERIFIED-PASS but MISLEADING]** | Live whole-suite = **21,394** tests (0F/0E/2S). 20,132 = testkit module only; of which **20,000 = `SeedSweepTest` (10k seeds x 2 methods)**. Per-module XML aggregate proves it. |
| 2 | Suite is GREEN | **[VERIFIED-PASS]** | `./mvnw -q -fae test` -> `EXIT_CODE=0`; 390 XML reports, `Failures: 0, Errors: 0` |
| 3 | 2 skipped tests | **[VERIFIED-PASS]** (deliberate) | `WalWireCompatStubTest` + `SnapshotWireCompatStubTest`, both `@Disabled("Stub: enable on first version bump (R-005)...")` |
| 4 | Deterministic Simulation Testing is real (ADR-0007) | **[VERIFIED-PASS]** | `RaftSimulation.java:37` + `SimulatedNetwork.java:34` -> `new java.util.Random(seed)`. Ran `SeedSweepTest` twice via `-pl configd-testkit -am`: both exit 0, identical `tests=20000 failures=0`. |
| 5 | TLC proves consensus safety | **[VERIFIED-PASS]** (ConsensusSpec) | Re-ran TLC myself -> 3,299,086 distinct states, depth 25, "No error has been found", 1m32s |
| 6 | `spec/tlc-results.md` invariant list | **[STALE / DISCREPANCY]** | Doc lists `NoStaleOverwrite` (8 invariants); live `ConsensusSpec.cfg` checks **9** incl. `LeaderCompleteness`+`VersionMonotonicity` and a comment "Removed NoStaleOverwrite — identical to StateMachineSafety". Committed results doc != actual spec. |
| 7 | JMH perf numbers in `docs/performance.md` reproducible | **[EXISTS-UNTESTED / DOC-ONLY artifacts]** | Harness executes (finding 8) but the committed result dirs are PLACEHOLDER/YELLOW. Docs' own command `./mvnw -pl configd-testkit test -Dtest='io.configd.bench.*'` finds **NO tests** — benches live in `src/main`, not `src/test`; only `benchmarks.jar` runs them. So that documented repro command is wrong. |
| 8 | JMH benchmark harness works | **[VERIFIED-PASS]** | Built `benchmarks.jar`, ran HybridClock -> real ns/op numbers (above) |
| 9 | Jepsen testing | **[ABSENT]** | No `.clj`, no jepsen dir, 0 CI jobs. Docs-only. |
| 10 | `tlc-rerun.log` provenance | **[DISCREPANCY]** | References `/home/ubuntu/Programming/Configd/spec/` — a different tree from this audit's `/home/ubuntu/Code/Configd`. Prior-claim artifact, not from this checkout. |
| 11 | `tries=1` property "sweeps" | **[CAVEAT]** | 5 `@Property(tries = 1)` methods (e.g. CommandCodec:92,189; FrameCodec:264; Snapshot:261; RaftMessageCodec:324) run exactly ONE case — effectively examples, not property exploration. Counted as passing but provide near-zero fuzzing coverage. |

---

## Divergence vs prior claims (rule 3)

- `verification-runs/mvn-test-baseline.log` contains **no `Tests run:` summary lines** (run with `-q`/jqwik verbose only) — cannot be numerically compared line-for-line; my authoritative XML aggregate (21,394) is the reliable figure. Anyone citing "20,132" as the suite total is citing one module.
- `tlc-rerun.log`: distinct-state count **3,299,086 matches my live run exactly** — TLC result is reproducible. Only the *path* in that log diverges (Programming vs Code tree).
- `spec/tlc-results.md`: invariant table is **stale** vs live `.cfg` (finding 6).

---

## Cross-examination requests for peers

1. **-> consensus-correctness:** The 20,000-case `SeedSweepTest` is the bulk of the "passing" test count, but `commitSurvivesLeaderFailure` has FOUR `return;` early-exits ("skip gracefully" when no leader elected / commit timeout / no new leader) — under many seeds it asserts **nothing** and still counts green. Please assess: what fraction of the 10k seeds actually reach the final `assertEquals("sweep-val", ...)`? If most bail early, the headline "20k tests pass" overstates multi-node safety coverage. (`configd-testkit/.../SeedSweepTest.java:64-96`)
2. **-> consensus-correctness:** TLC is green but CI + my run only check **ConsensusSpec** at N=3/MaxTerm=3/MaxLogLen=3, and check only **safety** invariants — the liveness property `EdgePropagationLiveness` is commented out in `ConsensusSpec.cfg`, and `ReadIndexSpec`/`SnapshotInstallSpec` are never run in CI. Confirm whether the safety-only, ConsensusSpec-only model check substantiates the broader "formally verified" claims.
3. **-> design-vs-reality:** `docs/performance.md` presents specific allocation/latency tables, but `perf/results/` is a PLACEHOLDER + a YELLOW "no workload wired" smoke file, and the doc's own reproduction command (`-Dtest='io.configd.bench.*'`) matches no tests. Please confirm whether any committed artifact backs the perf numbers, or whether they are estimates presented as measurements.
4. **-> concurrency-readpath:** `docs/performance.md` asserts "Read path: ZERO lock acquisitions... Verified by JMH `-prof perfnorm` showing zero MONITOR_ENTER events" and references `jcstress`/`async-profiler`. I found NO jcstress dependency, NO perfnorm artifact, and no committed profiler output. Please confirm whether the zero-lock claim is verified by any runnable harness or is source-inspection only.

---

## Phase 2 — Cross-examination

### CX-1 (design-vs-reality: does "linearizability verified" have ANY backing test?) — **REFUTE-as-stated / REFINE**

**(a) Do the 9 named classes exist?** design-vs-reality (findings-design-vs-reality.md:28,77,108) asserts "**none of those 9 classes exist**". **REFUTE.** All 9 exist as `@Nested` classes inside ONE file, `configd-testkit/src/test/java/io/configd/testkit/ConsistencyPropertyTests.java`, and all ran GREEN in my Phase-1 full suite:
- `LinearizabilityTest`:235, `StalenessUpperBoundTest`:397, `MonotonicReadTest`:785, `MonotonicReadFailoverTest`:886, `SequenceMonotonicityTest`:571, `SequenceGapFreeTest`:674, `PerKeyTotalOrderTest`:1053, `IntraGroupOrderTest`:1173, `ReadYourWritesTest`:1303.
- Command: `grep -rn "class <Name>" --include=*.java .` -> all 9 found. Surefire XML (Phase 1): `ConsistencyPropertyTests$LinearizabilityTest tests=6`, `$ReadYourWritesTest tests=4`, `$MonotonicReadFailoverTest tests=4`, etc., **0 failures**. **Classification: [VERIFIED-PASS] that the classes exist and run.** design-vs-reality looked for top-level files (`find -name X.java` -> none) and missed the nested declarations.

**(b) The harder question — do they assert a real LINEARIZABILITY / edge-staleness INVARIANT, or only per-component behavior?** Here design-vs-reality's *spirit* is **CORRECT** and I REFINE my own (a) accordingly:
- **`LinearizabilityTest` does NOT run a Wing & Gong history checker.** `consistency-contract.md:188` claims "verify history is linearizable using Wing & Gong algorithm." The actual code (lines 242-384) is **scripted, single-threaded, sequential** write→`readIndex()`→`store().get()` and asserts `result.version() >= commitSeq` / value equality. No concurrent client operations, no operation history, no linearizability checker. It is a real-time **visibility** check on a sequential schedule — NOT a concurrent-history linearizability verification. **Classification: [DOC-ONLY] for "Wing & Gong linearizability checking"; [VERIFIED-PASS] only for sequential ReadIndex visibility.**
- **No Wing & Gong / Knossos / Elle history checker exists ANYWHERE.** `grep -rniE "wing.?(and|&|gong)|knossos|elle|LinearizabilityChecker" --include=*.java .` -> **0 hits.** **Classification: [ABSENT].**
- **The two replayer tests are randomised PROPERTY tests, NOT TLA-trace replays.** `ReadIndexLinearizabilityReplayerTest` (header lines 19-40) and `SnapshotInstallSpecReplayerTest` (lines 21-28) both "drive the concrete Java implementation through a **randomised sequence** of model-equivalent actions" via `@Property(tries=200/300)` and a shadow ledger — they do NOT read the `spec/*_TTrace_*.bin` files (`grep TTrace|\.bin|readTrace` -> 0 hits). This is *stronger* than trace-replay (it's stateful model-based testing), but it asserts **ReadIndex/Snapshot protocol invariants** (`ReadIndexBoundedByMaxIndex`, `ReadFreshness`, `NoStaleLeaderServe`), **not a linearizability history property**. **Classification: [VERIFIED-PASS] as protocol-invariant property tests; [ABSENT] as linearizability checkers.** (NB consensus-correctness flagged ReadIndexSpec `ReadFreshness`/`NoStaleLeaderServe` consequents as vacuous in the .tla — that is their lane; I confirm the Java replayer is a real randomised driver regardless.)
- **`RaftSimulationTest$InvariantChecker` asserts nothing about Raft safety** — its 6 tests verify the invariant-checker *plumbing* (callback registration count, that a thrown `AssertionError` propagates: lines 129-199). Meta-tests of the harness, not safety properties. The genuine multi-node safety assertions live in `ConsistencyPropertyTests` + `SeedSweepTest`.

**CX-1 verdict:** design-vs-reality is **wrong that the 9 classes are absent** (they exist and pass), but **right that "linearizability verified (Wing & Gong)" has no backing checker** — the LinearizabilityTest is sequential visibility only, and no history checker exists. Net: the *consistency-contract.md:188 description* of these tests overstates what they do.

### CX-2 (consensus-correctness: are stale artifacts cited as current proof?) — **AGREE / REFINE**

Greps over `docs/certification/*`, `verification/*final-report.md`, `verification/claims-register.md`, `docs/ga-*.md`, `docs/audit/*`. Every citation classified:

| # | Doc citation | What it cites | Classification |
|---|---|---|---|
| 1 | `docs/certification/verdict.md:43` | "TLC model check: **8 invariants** PASS over 13.8M states \| **Not re-run**" | **[VERIFIED-FAIL]** — cites the STALE 8-invariant count (live `.cfg` checks 9; `NoStaleOverwrite` removed) AND self-admits not re-run, yet the row is in the GA acceptance table. |
| 2 | `docs/certification/verdict.md:86` | "[x] TLA+ spec model-checked (13.8M states), all divergences found and fixed" | **[DOC-ONLY]** — checkbox asserts model-check as done; relies on the not-re-run artifact. |
| 3 | `docs/certification/spec-code-map.md:31` | "INV-6 \| `NoStaleOverwrite` \| None \| Missing" | **[DOC-ONLY / stale]** — still lists `NoStaleOverwrite` as a spec invariant; it was removed from `ConsensusSpec.cfg:36`. Stale invariant carried forward. |
| 4 | `docs/verification/final-report.md:93` & `:310` | TLC proof log = `verification-runs/tlc-rerun.log` | **[VERIFIED-FAIL]** — that log is the IMPORTED one from `/home/ubuntu/Programming/Configd/spec/` (tlc-rerun.log:5), a different tree, cited as this build's raw artifact. (Numbers reproduce on my live run, so the *result* is true; the *cited artifact provenance* is wrong.) |
| 5 | `docs/ga-review.md:34` | Phase 3 GREEN: "TLA+/TLC pass on **Consensus/ReadIndex/SnapshotInstall**" | **[VERIFIED-FAIL]** — CI (`ci.yml:61`) runs **only `ConsensusSpec.tla`**. ReadIndex/SnapshotInstall have NO TLC job (only Java replayer property tests). Claiming all three are TLC-GREEN in CI is an overclaim. |
| 6 | `docs/ga-review.md:107` | "F2 \| TLC model check in CI \| GREEN" | **[VERIFIED-PASS but narrow]** — CI does run TLC, but for ConsensusSpec only; GREEN is accurate solely for that one spec. |
| 7 | `verification/claims-register.md:99-100` | T-01 "8 invariants pass", T-02 "13.7M states" — both **Status=Unverified, "re-run in progress"** | **[DOC-ONLY, honest]** — does NOT treat the stale artifact as proven; transparently marks Unverified. Not a violation. |
| 8 | `docs/verification/final-report.md:81-91` | Lists the CURRENT 9 invariants (incl. LeaderCompleteness, VersionMonotonicity; no NoStaleOverwrite) | **[VERIFIED-PASS]** — final-report's invariant *list* is correct/current; it is `tlc-results.md` + `verdict.md` + `spec-code-map.md` that are stale. |

**Additional discrepancy surfaced during CX-2 (test-count drift):** the suite size is reported as **four different numbers, none = my live 21,394**: `docs/audit/test-completeness.md:14` = 20,132; `docs/ga-review.md:35` = 20,149; `docs/verification/final-report.md:7,64,275` = 21,246 **and** `:358,402` = 21,285 (two numbers in the SAME file). `ga-review.md:35` itself DEMOTED the test pyramid to YELLOW because "no on-disk test-count artifact pinned to commit SHA" — i.e. the project already knows these counts float. **Classification: [DOC-ONLY drift]** — no count is a reproducible artifact for this commit. Also: `final-report.md:362` cites a *third* log path `docs/verification/runs/tlc-round2-rerun.log` (exists, 3228 B) with a *different* runtime (3m55s vs the 5m12s in tlc-rerun.log) — internally inconsistent timing for "byte-exact" reruns.

**CX-2 verdict:** **AGREE** with consensus-correctness. The stale 8-invariant list and the imported `/home/ubuntu/Programming/Configd` log ARE cited as current GA/certification evidence in `verdict.md:43,86`, `spec-code-map.md:31`, and `final-report.md:93,310`. The claims-register (line 99-100) is the one honest exception. Plus an over-broad "all 3 specs TLC-pass in CI" claim in `ga-review.md:34`. The underlying TLC *result* is real (I reproduced it), but the documents marshal stale/imported artifacts and an inflated CI scope as the proof.
