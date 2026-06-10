# Test Forensics — Phase C (mutation, coverage, vacuity)

> Session-1 Ground Truth Audit, Phase C. Question under test: **do the 21,408 green tests
> (1,408 real + 20,000 seed-sweep cases) actually protect anything?** All measurements were
> executed in this session in the build workspace `/home/ubuntu/ws-clean` (clone of
> `session-1-ground-truth` @ `423a654`, fully built). The audited repo's poms are untouched;
> every harness edit is in §5. Severity per charter: **P0** safety/data-loss/cannot-run ·
> **P1** correctness risk · **P2** quality gap · **P3** polish. Charter rule for §1: module
> mutation score <60% = finding, <40% = P1 "test suite provides minimal protection".

## 1. Mutation testing (PIT)

### 1.1 Setup

- `org.pitest:pitest-maven:1.25.4` (newest on Maven Central as of 2026-06-10;
  `maven-metadata.xml` `<release>1.25.4</release>`) + `org.pitest:pitest-junit5-plugin:1.2.3`.
  **PIT 1.25.4 parses JDK 25 class files (major 69) without errors** — no TOOLING-BLOCKED.
  The vestigial `--enable-preview` is a non-issue for tooling: 0 class files are
  preview-flagged (`javap -verbose RaftNode.class` → `minor version: 0, major version: 69`);
  PIT minions were nevertheless given `--enable-preview` as a jvmArg (harmless, matches the
  surefire argLine).
- Config: mutator group **DEFAULTS**, `threads=2`, per-mutant timeout
  `timeoutConstant=8000ms` / `timeoutFactor=1.5`, 25-minute wall cap per module
  (`timeout 1500 ./mvnw -pl <module> org.pitest:pitest-maven:mutationCoverage`).
  **No module hit the cap; no targetClasses restriction was needed** (slowest: config-store
  at 11m30s).
- jqwik `@Property` tests run under the junit5 plugin (jqwik is a JUnit Platform engine);
  PIT logs confirm jqwik tests executing (e.g. slowest test in consensus-core was a jqwik
  property).

**Methodological caveat (stated up front):** PIT counts only same-module tests.
configd-consensus-core also receives real exercise from `configd-testkit` simulations
(SeedSweepTest, ConsistencyPropertyTests, EndToEndTest) and configd-linz, which PIT cannot
credit here. The same applies to `ConfigDelta.signingPayload` (verified from
configd-edge-cache's DeltaApplier tests, NO_COVERAGE within config-store). **Scores below are
a floor, not a ceiling.** That said: the testkit sweep asserts only two invariants (election
safety, commit-survives-leader-failure) with structural bail-out paths (see §3.2/F-C5), so
the cross-module credit for fine-grained consensus logic is thin.

### 1.2 Per-module results

| Module | Mutants | Killed (incl. timed-out) | Survived | No-coverage | **Mutation score** | PIT line coverage (mutated classes) | Runtime |
|---|---|---|---|---|---|---|---|
| configd-consensus-core | 658 | 381 (376 K + 5 TO) | 175 | 102 | **58%** | 861/1005 (86%) | 7m51s |
| configd-replication-engine | 134 | 112 | 10 | 12 | **84%** | 216/227 (95%) | 31s |
| configd-config-store | 590 | 416 (410 K + 6 TO) | 88 | 86 | **71%** | 772/942 (82%) | 11m30s |
| configd-edge-cache | 186 | 147 (146 K + 1 TO) | 25 | 14 | **79%** | 304/343 (89%) | 3m53s |
| configd-distribution-service | 307 | 168 | 51 | 88 | **55%** | 459/633 (73%) | 8m02s |

Aggregate over the 5 modules: 1,224/1,875 = **65%**; 302 mutants (16%) sit in code no
same-module test executes at all.

Test-strength note (kills ÷ covered mutants): consensus-core 381/556 = **69%**,
config-store 416/504 = **83%**, edge-cache 147/172 = **85%**, distribution-service
168/219 = **77%**, replication-engine 112/122 = **92%**. The consensus-core number is the
one that matters: even where its tests DO execute the mutated line, they fail to notice a
third of behavior changes.

Irony note: the best score (replication-engine, 84%/92% strength) protects code with
**zero production callers** (Phase B CF-03–CF-06: ReplicationPipeline, SnapshotTransfer,
FlowController, HeartbeatCoalescer are test-only) — the suite guards the shelfware tightly
and the live consensus kernel loosely.

Two modules fall under the charter's 60% line:
- **configd-consensus-core 58%** — survivor analysis in §1.3.
- **configd-distribution-service 55%** — concentration: `SlowConsumerPolicy` 25/25 mutants
  NO_COVERAGE (no test class exists), `CatchUpService` 22/22 NO_COVERAGE (zero references
  anywhere — Phase B CF-07 re-confirmed), `HyParViewOverlay` 31 NO_COVERAGE + 15 SURVIVED
  concentrated in `receiveForwardJoin`/shuffle/passive-view repair — the membership
  protocol's failure-repair machinery is untested (its test class covers only the happy
  join/eviction paths).

Boilerplate discount check (for fairness): of consensus-core's 102 no-coverage mutants, 47
are record-generated `equals`/`hashCode`/`toString` on `InstallSnapshotRequest` and
`SnapshotState`. Excluding all 47 still yields 381/611 = **62%** — the 58% headline is not
an artifact of record noise; the survivors below are in live consensus logic.

### 1.3 The 10 most alarming surviving mutants

All are SURVIVED (line executed by tests, behavior change undetected) unless marked
NO_COVERAGE. Sources: `<module>/target/pit-reports/mutations.xml` in ws-clean.

| # | Mutant (class:line, mutator) | What the mutation does | Why survival is scary |
|---|---|---|---|
| 1 | `RaftNode.maybeAdvanceCommitIndex:1330`, RemoveConditional EQUAL_ELSE | Deletes the `log.termAt(n) != currentTerm → continue` guard | This **is** Raft §5.4.2: a leader must not commit prior-term entries by replication count alone. With the guard gone, the Figure-8 lost-write scenario becomes reachable and **no test fails**. The test named for exactly this property is tautological (§3.1/F-C1) — PIT empirically confirms the suite cannot see the most famous data-loss bug in Raft. |
| 2 | `RaftNode.handleRequestVote:938`, VoidMethodCall (removes `DurableRaftState::vote`) | Vote granted but never persisted | A node that crashes and restarts can vote twice in one term → two leaders in one term → split brain + committed-write loss. No test crash-restarts a node between votes, so the persistence call is dead weight as far as the suite knows. |
| 3 | `RaftLog.truncateFrom:367`, VoidMethodCall (removes `Storage::sync`) | Deletes the directory fsync after conflict-truncation WAL rewrite | Silently reverts fix **F-0012**. Its named regression test (`RaftLogWalTest.truncateFromPersistsDurablyAcrossRestart:311`) reopens the same directory in-process — rename visibility does not depend on fsync, so the test passes with the fix deleted. A crash after truncation can resurrect conflicting entries → log-matching violation on recovery. |
| 4 | `DurableRaftState.persistValues:133`, VoidMethodCall (removes `Storage::sync`) | Term/vote writes no longer fsynced | Same family as #3, worse target: the class is named **Durable**RaftState and its durability is unverifiable by the suite. Crash → lost term/vote → double vote (same blast radius as #2). |
| 5 | `RaftNode.becomeFollower:1053`, VoidMethodCall (removes `ReadIndexState::clear`) | Pending ReadIndex reads survive step-down | Stale-leader linearizable reads. The named test (`CertificationTest.pendingReadsInvalidatedOnStepDown:494`) passes anyway because its read was never quorum-confirmed — `isReadReady` is false for an unrelated reason. A confirmed-but-unapplied read serving after step-down is exactly the linearizability violation the linz harness exists to catch, and the unit net has a hole there. |
| 6 | `RaftNode.handleAppendEntriesResponse:897–898`, MathMutator ×2 (`ni - 1` → `ni + 1`; fallback `lastIndex() + 1` → `lastIndex() - 1`) | nextIndex backoff walks **forward** on rejection | Log conflict resolution never converges for a divergent follower — the leader probes increasingly wrong indices. `leaderDecrementsNextIndexOnRejection` (RaftNodeTest:702) exists but does not pin the arithmetic. Divergent followers stay divergent until a snapshot accidentally rescues them. |
| 7 | `RaftNode.applyCommitted:1383`, RemoveConditional EQUAL_ELSE (on `isConfigChangeEntry`) | Committed joint-consensus config entries are fed to the user state machine as data; `handleCommittedConfigChange` never runs | Reconfiguration silently never completes (cluster stuck in joint config) AND raw `RCFG` bytes corrupt the config store. Survives because no test drives a config change through commit→apply→final-config (§3.1/F-C2). 34 further survivors sit in `proposeConfigChange` (11), `handleCommittedConfigChange` (9), `configAtIndex` (9) — the whole reconfig path is mutation-soft. |
| 8 | `ConfigStateMachine.signCommand:587`, VoidMethodCall (removes `SecureRandom::nextBytes`) | Signing nonce is all-zeros forever | F-0052's replay protection (nonce+epoch bound into the signed payload) is silently disabled; replayed deltas under a rolled-back edge verify fine. No test asserts nonce uniqueness/randomness. |
| 9 | `VersionedConfigStore.delete:114` + `applyBatch:137`, RemoveConditional ORDER_ELSE | Deletes the `sequence <= currentVersion → throw` monotonicity guard on the delete and batch write paths | MVCC version monotonicity — the store's core invariant (INV-5 feeds off it) — is only regression-tested on `put`. A Raft replay/duplicate-apply bug that re-applies an old delete/batch would silently regress versions. |
| 10 | `RaftNode.applyCommitted:1367/1373`, `becomeLeader:1165/1171`, `handleAppendEntries:833`, VoidMethodCall ×5 (removes `InvariantChecker::check`) | All runtime invariant checks (election_safety, leader_completeness, version_monotonicity, state_machine_safety, log_matching) deleted | The project's celebrated "runtime invariant net" is itself unverified: every check call can be removed with zero test failures. Root cause is structural — consensus-core unit tests build RaftNode via the 5-arg constructor, which wires `InvariantChecker.NOOP` (RaftNode.java:159), so the checks are no-ops in every unit test; only production (`ConfigdServer.java:206`) wires a real monitor. Nothing proves the net is plugged in or that its conditions are correctly phrased. |

Honorable mentions (consensus): `RaftNode.handlePreVoteRequest:960/966` (PreVote grant
boundary conditions mutable — the §9.6 disruption shield is soft),
`RaftNode.handleInstallSnapshotResponse:1535–1581` (5 survivors: snapshot-response
term/match accounting), `VersionedConfigStore.getInto:251` (removing the `System.arraycopy`
that fills the caller's buffer survives — the zero-alloc hot read path can return garbage
undetected), `ConfigStateMachine.decodeTrailer:446–481` (9 boundary survivors in TLV
trailer bounds checks = corrupted-snapshot handling untested at the edges).
Deliberately excluded as near-equivalent: `RaftLog.appendEntries:330` EQUAL_ELSE (falls
through to truncate-then-append; behavior almost identical), `RaftLog.setCommitIndex:378`
boundary (guarded idempotent).

## 2. Coverage map (JaCoCo)

### 2.1 Run conditions (recorded)

- JaCoCo 0.8.14 (`prepare-agent` + `report@test`, §5), reactor `./mvnw test
  "-Dtest=!SeedSweepTest" -Dsurefire.failIfNoSpecifiedTests=false`.
- **Recorded exclusion:** `SeedSweepTest` (20,000 parameterized cases) skipped for time. Its
  marginal line coverage is limited to consensus paths already exercised by
  `ConsistencyPropertyTests`/`RaftSimulationTest`/`EndToEndTest`, which ran. Coverage
  numbers for consensus-core therefore *understate* nothing structural; mutation results
  (§1) carry the protection question anyway.
- **Recorded failures + mitigation (forensically interesting in its own right):** under the
  JaCoCo agent, the two keytool-shelling TLS tests reproducibly exceed their hardcoded
  10-second timeouts: `TcpRaftTransportTest.find0051_clientHandshakeRejectsCertWithWrongHostname`
  (class-level `@Timeout(10)`, TcpRaftTransportTest.java:25) failed 3/3 attempts at
  11.13s/10.26s/10.23s — the third on a near-idle box (load 2.2) — and
  `ConfigdServerTest.find0050_tcpRaftTransportExposesTlsManagerGetter` failed 2/2 at
  10.24s. Both pass without the agent (Phase A: whole TcpRaftTransportTest class in 7.09s).
  Diagnosis: instrumentation overhead, not box load. Mitigation used (no test files
  touched): `-Djunit.jupiter.execution.timeout.mode=disabled` for the affected modules
  (configd-transport, configd-server+configd-linz), re-run; coverage data unaffected.
  **CI-reliability note: two security regression tests sit ~2s under their own timeout on
  2-CPU hardware — any slowdown (agent, load, slower CI runner) flips the suite red.**
- Final state: all 12 modules green under coverage (transport/server/linz with the timeout
  mode disabled), `Tests run` totals matching Phase A minus the 20,000 excluded sweep cases.

### 2.2 Per-module coverage

JaCoCo counts only same-module test execution (same caveat as PIT — e.g. ConfigReadService
is exercised by configd-server's tests but shows 0% in its home module).

| Module | LINE | BRANCH | METHOD |
|---|---|---|---|
| configd-common | 77% (164/214) | 73% (44/60) | 78% (45/58) |
| configd-transport | 91% (429/473) | 72% (111/154) | 97% (75/77) |
| configd-consensus-core | 86% (879/1024) | **72% (412/572)** | 89% (136/153) |
| configd-config-store | 83% (797/964) | 74% (279/379) | 84% (147/174) |
| configd-edge-cache | 88% (323/367) | 84% (97/116) | 96% (73/76) |
| configd-observability | 94% (447/475) | 83% (158/190) | 93% (94/101) |
| configd-replication-engine | 95% (215/226) | 94% (92/98) | 93% (53/57) |
| configd-distribution-service | 72% (478/666) | **58% (183/318)** | 74% (109/148) |
| configd-control-plane-api | **70% (159/226)** | **65% (62/96)** | 67% (34/51) |
| configd-testkit | 2% (117/5443)* | 2%* | 9%* |
| configd-server | **61% (480/792)** | **46% (133/292)** | 70% (70/100) |
| configd-linz | **9% (66/774)**† | 12% (37/299) | 10% (10/101) |

†linz: expected artifact of the `PORCUPINE_BIN` env-gate — the 6 checker self-tests skip in
any default build (only the 4 HistoryWriter unit tests run). The number is real, though:
**in CI as configured, 91% of the linearizability harness's code is never executed.**

*testkit's 2% is an artifact: JaCoCo's denominator is dominated by ~5,000 lines of
JMH-**generated** `*_jmhTest` benchmark classes in target/classes. The hand-written
simulation classes are fine (`RaftSimulation` 55/55 lines). Not a finding.

The headline numbers hide the story; branch coverage on the consensus kernel (72%) and the
drill-down below are the real map.

### 2.3 Critical-path drill-down (charter list)

Method-level line/branch coverage from each module's `target/site/jacoco/jacoco.xml`.

1. **Leader-election edge cases — partially tested, rejection edges thin.**
   `RaftNode.handlePreVoteRequest` 7/8 lines, **9/12 branches**; line 961 (the
   `req.term() < currentTerm → wouldGrantPreVote=false` stale-term rejection arm) is
   **uncovered**, and the `hasRecentLeader`/`logOk` composite at :966/:970 has uncovered
   branch arms (matches the surviving boundary mutants, §1.3 honorable mentions).
   `startPreVote` 22/23, `startElection` 21/24, `handleRequestVote` 18/24 lines (14/16
   branches), `handleRequestVoteResponse` 12/15. Split vote: covered by a real test
   (`splitVoteResolvesViaRandomizedTimeout`). CheckQuorum step-down: covered
   (`leaderStepsDownWithoutQuorum`), but `becomeFollower` itself is 12/20 lines — the
   F-0022 read-callback-fire-on-stepdown block (:1060-1068) never executes in-module
   (3 NO_COVERAGE mutants there).
2. **Log conflict resolution — the happy conflict is tested, the machinery around it is
   not.** `handleAppendEntries` 33/33 lines but branch gaps at :833/:834 (log-matching
   invariant arms). `RaftLog.appendEntries` 15/16 lines (:327 snapshot-skip arm uncovered);
   `truncateFrom` 8/10 lines, 4/6 branches; `RaftLog.entriesFrom` **0/7 lines** and
   `appendAll` **0/4** (dead read/write APIs). The §1.3 #6 nextIndex-walk survivors show
   the covered lines are weakly asserted.
3. **Snapshot install — send-side error path never executed.** Follower restore:
   `handleInstallSnapshot` 26/26 lines (9/10 branches — good). Leader send:
   `sendInstallSnapshot` **12/18 lines, 2/4 branches**; the uncovered lines are exactly
   :1277/:1280 (no-snapshot early returns) and **:1300-1304 — the
   `IllegalArgumentException` catch that drops >4MiB-encoded snapshots
   (RaftNode.java:1298-1305)**. The ADR-0029 "dominant cause of legitimate IAE in
   production" path has never run, in any test, anywhere (grep: no test references the
   drop message). A lagging follower needing a snapshot >4MiB wire limit hits a path with
   zero executions: the leader drops, logs to stderr, and retries forever — silent
   replication stall, verified only by code reading.
4. **Joint-consensus reconfiguration — coverage looks decent, protection is absent.**
   `proposeConfigChange` 27/29 lines (11/14 branches; :519/:525 leader/no-op preconditions
   partially uncovered — F-C3's missing case), `handleCommittedConfigChange` 14/16,
   `configAtIndex` 7/10 lines **5/12 branches**, `deserializeConfigChange` 16/20,
   `recomputeConfigFromLog` 14/16 (11/18 branches), `isConfigChangeEntry` 8/12 branches.
   Branch-level: roughly **55-60% of reconfig decision points execute**; mutation score on
   the same path is 46% (TF-3). Zero production callers (re-confirmed). The TLA+-spec'd
   joint-consensus feature is executed-but-not-verified in tests and unreachable in prod.
5. **Edge catch-up / deltasSince — the gap is production wiring, not unit coverage.**
   `FanOutBuffer.deltasSince` 8/8 lines (5/6 branches) from unit tests, but **zero src/main
   callers** (re-confirmed); `CatchUpService` is **0% on every method** (0/46 lines) and has
   zero references even from tests (Phase B CF-07 re-confirmed). The edge replay/recovery
   story does not exist below the unit-test horizon.
6. **Write-path error branches — partially exercised.** `ConfigWriteService.put` 18/20
   lines 12/16 branches, `delete` 9/11 lines **4/8 branches** (NotLeader-hint and
   rate-limit arms on delete undertested). `ConfigReadService`: **0% in-module** (no test
   class exists in control-plane-api; exercised only indirectly from configd-server).
   `AdminService`: **0% everywhere** — `addNode`/`removeNode`/`transferLeadership`/
   `clusterStatus` have never executed (and have no callers: CF-08).
   **`HttpApiServer$ConfigHandler` — the entire client data API — is 7% line, 0/60
   branches**: `handle` (method/path routing, incl. the 400 missing-key reply) 0/12 lines,
   `handleGet` (incl. linearizable-vs-stale switch and the 503 Not-Leader reply) 0/23,
   `handlePut` (incl. 400 empty-body, 400 validation-failed, 503 Not-Leader+hint) 0/22,
   `handleDelete` 0/18, `checkAuth` (the entire authorization gate) 0/13 lines 0/14
   branches. `ReadinessHandler.handle` 0/8. The only tested handlers are liveness (partial)
   and `/metrics`. Nothing has ever sent an HTTP request to the config endpoints in a test.
7. **Marshalled-task exception path (ConfigdServer.java:690 area)** —
   `raftInboundHandler` returns `(from,message) -> raftExecutor.execute(() ->
   driver.routeMessage(groupId, message))`. The executor is the single-thread
   **Scheduled**ExecutorService (`tickExecutor`, ConfigdServer.java:292,316); for STPE,
   `execute()` wraps the task in an unobserved `ScheduledFutureTask`, so **a throw from
   `routeMessage`/`RaftNode.handleMessage` is swallowed silently** — no log, no metric, no
   thread death; the inbound Raft message is simply lost. `RaftInboundMarshallingTest`
   verifies threading only and never throws; the H-009 throwable handler guards only the
   tick `Runnable`, not inbound-message tasks. JaCoCo: the factory line :690 executes
   (ci=17); no test injects an exception into `routeMessage` (grep over marshalling/server
   tests: zero hits), so the swallowing behavior has never been observed. Adjacent and
   worse: **`RaftTransportAdapter` — the production bridge RaftNode↔TcpRaftTransport,
   including the inbound decode-and-dispatch lambda — is 0% on every method** (0/16
   lines); server tests use in-process fakes, so the real network seam (encode → send →
   decode → marshal → handleMessage) is never traversed end-to-end by any test. Also in
   the same module: `ConfigdServer.start`'s linearizable-read dispatch lambdas
   (:483-512, the F-0022 CompletableFuture path, CF-24's code) are **all zero-covered** —
   the two linearizable-read regression tests build the components by hand instead of
   going through the server's real wiring; `ServerConfig.parsePeerAddresses` (production
   boot parsing of `--peer-addresses`) is 0/15 lines; `ConfigdServer.main` 0/18 and
   `printBanner` 0/13 (the operator-facing "(wired)" banner Phase B flagged is asserted
   by nothing).
8. **Bonus orphans surfaced by the map:** `RaftNode.whenReadReady` **0/6 lines** and
   `fireReadyCallbacks` 2/14 in-module (the F-0022 fix's machinery — its real coverage
   lives only in configd-server tests); `SigningKeyStore` **0% in-module** (0/74 lines:
   `load`/`generateAndWrite`/`loadOrCreate` — on-disk key lifecycle never tested where it
   lives, and `ConfigSignerTest` injects in-memory keys); `LocalConfigStore.getInto`
   **0/16 lines** and zero callers anywhere (the "zero-alloc edge read" API is shelfware);
   `HyParViewOverlay` **32% branch** — `receiveForwardJoin`/`receiveShuffleRequest`/
   `receiveShuffleReply`/`integrateSample`/`addToPassiveView` all 0%: the overlay's entire
   repair/anti-entropy surface is untested (and unreachable in prod, CF-11).

### 2.4 Ranked top-10 critical-but-untested (blast radius × absence of coverage)

| Rank | Path | Absence | Blast radius |
|---|---|---|---|
| 1 | **Snapshot send IAE drop, `RaftNode.sendInstallSnapshot` :1300-1304** | 0 executions anywhere (JaCoCo lines uncovered; no test references the path) | Live production path (single-blob InstallSnapshot is the only catch-up for compacted followers). A snapshot exceeding the wire limit is dropped + stderr; leader retries forever → **permanent, silent replication stall** of every lagging follower once state outgrows the frame limit. ADR-0029 itself calls this the dominant legitimate IAE source. |
| 2 | **Inbound Raft message exception path, `ConfigdServer.raftInboundHandler` :690** | 0 tests throw through it; the throwing branch of the lambda has never executed | A decode/invariant/state throw inside `routeMessage` on the single consensus thread is **silently swallowed** by the unobserved `ScheduledFutureTask` (no log, no metric, message lost). H-009's handler covers only the tick Runnable. Undiagnosable message loss in the consensus inbox. |
| 3 | **Crash-durability of consensus state** (`DurableRaftState.persistValues`, `RaftLog.truncateFrom` fsync, vote persistence) | Lines execute, but no test crash-restarts; all fsyncs deletable per PIT (§1.3 #2-#4); `DurableRaftState.setTerm` decreasing-term throw branch 3/4 never hit | Split brain / committed-write loss after power loss — the precise failure class Raft exists to prevent. |
| 4 | **`HttpApiServer$ConfigHandler` + `checkAuth`** | 7% line, **0/60 branches**; `handleGet`/`handlePut`/`handleDelete`/`checkAuth`/readiness all at 0 executions | The only client-facing surface of the only entrypoint. Every 400/503/auth-denial branch, the linearizable-vs-stale read switch, and the NotLeader redirect hint have never run. The auth gate at 0% means an authz regression ships green. |
| 5 | **Linearizable read seam** — `ConfigReadService` 0% in-module; `RaftNode.whenReadReady` 0/6, `fireReadyCallbacks` 2/14, `becomeFollower` callback-fire block :1060-1068 uncovered in-module; ReadIndex invalidation unpinned (§1.3 #5) | In-module zero; cross-module: exactly 2 server tests (F-0009/F-0022 regressions), and those build the read components by hand — the server's own dispatch path (ConfigdServer:483-512) is 0% | The product's headline guarantee. The stale-read window on step-down is exactly what linz gate runs found hard to discriminate; unit-level protection is a single tautology away from absent. |
| 6 | **Joint-consensus completion** — `configAtIndex` 5/12 branches, `recomputeConfigFromLog` 11/18, `isConfigChangeEntry` 8/12, `proposeConfigChange` preconditions :519/:525 uncovered | No test completes joint→final; 46% path mutation score; zero prod callers | Latent: committed `RCFG` entries route into the user state machine if the guard decays (§1.3 #7). Today unreachable in prod (CF-14), which caps the radius — but every spec/TLA+ claim about reconfiguration safety is unverified against the implementation. |
| 7 | **`SigningKeyStore` on-disk key lifecycle** — 0/74 lines in-module (`load` 0/25, `generateAndWrite` 0/24) | Zero in-module; `ConfigSignerTest` injects in-memory keys | Key-file corruption/permission/rotation behavior unknown; a malformed key file's failure mode (refuse to start? silently regenerate and **break delta verification cluster-wide**?) has never been observed. |
| 8 | **PreVote/election storm shields** — `handlePreVoteRequest` :961 stale-term arm uncovered, :960/:966/:970 partial branches; `handleTimeoutNow` 4/6 lines; CheckQuorum boundary survivors | Partial branches + surviving boundary mutants | Disruption resistance (the §9.6 mechanism) degrades silently; a wrong boundary re-admits the partitioned-node term-inflation storms PreVote exists to stop. |
| 9 | **Corrupt-snapshot ingestion** — `ConfigStateMachine.decodeTrailer` 16/22 branches (9 surviving boundary mutants §1.3 hm), `restoreSnapshotImpl` 16/18 branches | Bounds checks executed only on well-formed data | A truncated/corrupted snapshot from a (future) wire path or disk feeds the TLV parser; off-by-one acceptance of corrupt trailers is currently undetectable by the suite. |
| 10 | **Edge catch-up** — `CatchUpService` 0/46 lines, zero refs; `FanOutBuffer.deltasSince`/`LocalConfigStore.getInto` orphans; `HyParViewOverlay` repair surface 0% | Total (code + tests + wiring) | Capped only because the paths are unreachable in prod (CF-07/-09/-11/-12): the edge recovery/replay story is absent at every layer — this is a product gap wearing a test-gap costume. |

## 3. Vacuity review (manual, sampled)

**Method:** read 59 test methods in full across all 12 modules (skewed to consensus-core,
config-store, server), plus a mechanical zero-assertion scan over every `@Test`/`@Property`/
`@ParameterizedTest` body in the tree (Python brace-matcher; 2 hits, both confirmed by eye)
and pattern scans for `assertDoesNotThrow` (18), `Thread.sleep` in tests (9, in 2 files),
`@Disabled` (2), catch-and-swallow (3 sites, all legitimate retry/negative-path patterns).

### 3.1 Vacuous / misleading tests found

| ID | Test (file:line) | Defect |
|---|---|---|
| F-C1 | `CertificationTest.leaderCannotCommitPriorTermEntryByReplicationCountAlone` — configd-consensus-core/src/test/java/io/configd/raft/CertificationTest.java:250 (assertion at :319-320) | **Tautological final assertion.** The safety check is `assertTrue(term1EntryIndex > commitBefore \|\| leader1.log().termAt(term1EntryIndex) == term1)`. The second disjunct was already asserted true at :275 (`assertEquals(term1, leader1.log().termAt(term1EntryIndex))`), so the assertion can never fail regardless of whether the prior-term entry was wrongly committed. The marquee Figure-8 safety test asserts `X \|\| true`. Mutant #1 in §1.3 is the empirical proof. |
| F-C2 | `ReconfigurationTest.configChangePreservedAcrossElections` — io/configd/raft/ReconfigurationTest.java:259 | **Body tests neither config changes nor elections.** It elects one leader, proposes a *normal* command, and asserts `commitIndex >= 2`. The class javadoc claims coverage of "Leader step-down when removed from cluster" — no test in the file does this. No test anywhere completes a joint→final transition (PIT: `handleCommittedConfigChange` has 9 survivors). |
| F-C3 | `ReconfigurationTest.rejectsConfigChangeBeforeNoopCommitted` — io/configd/raft/ReconfigurationTest.java:165 | **Asserts the opposite of its name.** The body's comment concedes the no-op is already committed, then asserts the proposal **succeeds** (`assertTrue`). The named precondition (reject before no-op commit) is tested nowhere. |
| F-C4 | `ConfigdServerTest.tickLoopContinuesAfterDriverException` — io/configd/server/ConfigdServerTest.java:288 | **Claims to be the FIND-0005 zombie-tick-loop regression test but never injects an exception.** Body = start server, `Thread.sleep(100)` ×2, `assertNotNull(server.driver())` (true from construction). Cannot fail unless `start()` throws. The actual regression (executor silently cancelling after a throw) is unobserved. |
| F-C5 | `SeedSweepTest.commitSurvivesLeaderFailure` — io/configd/testkit/SeedSweepTest.java:61 (bail-outs at :67, :74, :87) | **Three silent early-`return` paths** (no leader elected / commit timeout / no new leader) make the test pass while asserting nothing for those seeds. 10,000 of the 20,000 sweep cases run this method; an unknown fraction is vacuous-by-construction. (Corroborates harness-runs.md §3, which also notes `electionSafety` is vacuous for seeds that never elect a leader.) |
| F-C6 | `RaftLogWalTest.truncateFromPersistsDurablyAcrossRestart` — io/configd/raft/RaftLogWalTest.java:311 | **Durability regression test that cannot detect its regression.** Verifies post-truncation recovery by reopening the directory in the same OS session; the F-0012 fix it guards (`storage.sync()` after WAL rewrite) only matters across a crash. PIT mutant #3 (§1.3) survives it. Same blind spot covers `DurableRaftState.persistValues` (#4). |
| F-C7 | `ConfigdServerTest.shutdownIsIdempotent` (ConfigdServerTest.java:89) and `AclServiceTest.revokeNonexistentPrefixIsNoOp` (configd-control-plane-api .../AclServiceTest.java:150) | **Zero assertions** (the only 2 in the tree, per mechanical scan). Implicit "doesn't throw" contract; acceptable scope, but they inflate the green count. |
| F-C8 | `SloTrackerTest.defineSloSucceeds` / `defineSloWithZeroTargetSucceeds` / `defineSloWithPerfectTargetSucceeds` — io/configd/observability/SloTrackerTest.java:33-47 | "No exception thrown" is the entire assertion (`assertDoesNotThrow` with no state check). Contrast: the same file's InvariantMonitor tests correctly pair `assertDoesNotThrow` with `violations().isEmpty()`. |
| F-C9 | `RaftMessageCodecPropertyTest.dispatcherAcceptsEveryRaftType` — io/configd/server/RaftMessageCodecPropertyTest.java:326 | Round-trips all 9 Raft message types but asserts only `msg.getClass() == round.getClass()` — a codec that corrupts every field passes. (Field-level roundtrips exist elsewhere in the file, so partial mitigation.) |
| F-C10 | `SnapshotInstallSpecReplayerTest.emptyTraceTriviallySatisfiesInvariants` — io/configd/raft/SnapshotInstallSpecReplayerTest.java:261 | Self-described trivial property on an empty world; ends with a constant assertion on a "sentinel" HashSet built solely "to suppress unused-import noise" (`assertFalse(sentinel.isEmpty())` on a set seeded from a non-empty constant). |
| F-C11 | `WalWireCompatStubTest.java:58` + `SnapshotWireCompatStubTest.java:58` (consensus-core) | The only 2 `@Disabled` tests in the tree: honest, well-documented stubs — but the consequence stands that **WAL and snapshot wire/format compatibility have zero executable protection**. |

**`tries = 1` claim — VERIFIED, exactly 5** (`grep -rn "tries = 1"` excluding `tries = 1[0-9]+`):
`SnapshotInstallSpecReplayerTest.java:261`, `RaftMessageCodecPropertyTest.java:324`,
`FrameCodecPropertyTest.java:264` (transport), `CommandCodecPropertyTest.java:92` and `:189`
(config-store). Mitigating nuance the prior claim missed: **all five take no `@ForAll`
parameters** — they are deterministic example tests written in `@Property` clothing, so
`tries = 1` does not weaken any input space (they belong in `@Example`/`@Test`; two of the
five are weak for other reasons, F-C9/F-C10).

`Thread.sleep` as synchronization: 9 occurrences in 2 files. `TcpRaftTransportTest` (4) are
bounded retry loops around latch awaits — acceptable. `ConfigdServerTest` (5, lines
141/148/295/302/575) substitute for observing tick progress — F-C4 is the worst case; the
TLS-reload test (:539) at least counts ticks after the sleep. No test mocks its own unit
under test anywhere in the tree (no mocking framework exists; fakes are hand-rolled
recorders — verified by grep and by reading).

### 3.2 Per-module assertion-quality verdicts

| Module | Verdict (sampled evidence) |
|---|---|
| configd-consensus-core | **Mixed.** Broad, genuinely behavioral scenario tests (RaftNodeTest's PreVote/CheckQuorum/conflict suites, CertificationTest's Figure-8 staging, two strong jqwik spec-replayers) — but the deepest safety assertions are soft exactly where it matters (F-C1, F-C2, F-C3, F-C6; PIT test strength 69%). |
| configd-config-store | **Good with gaps.** Dense, real assertions (1,005-line ConfigStateMachineTest incl. 70KB-key snapshot roundtrip; 5 solid jqwik MVCC properties; hand-rolled recording fakes). Gaps: delete/batch monotonicity guard, signing nonce, TLV-trailer bounds (PIT §1.3 #8/#9). |
| configd-server | **Weakest of the core** (JaCoCo: 61% line / 46% branch — worst real module). Wiring smoke tests + reflection pokes; F-C4, F-C7; HttpApiServer has no dedicated test class — only reflection-based port extraction — and its ConfigHandler/auth gate sit at 0 executions (§2.3). Strong spots: RaftInboundMarshallingTest genuinely proves R-01 serialization; TickLoopThrowableHandlerTest asserts metrics+log+exporter; InvariantNetMetricTest proves the invariant net is non-NOOP in a live server. |
| configd-edge-cache | **Good.** BloomFilter FPR property with numeric bound, DeltaApplier gap/stale/epoch-replay suites, PoisonPill quarantine lifecycle — real state assertions throughout. |
| configd-distribution-service | **Good.** WatchServicePropertyTest asserts exactly-once/monotonic-cursor/prefix-filter properties; RolloutController full stage progression; FanOutBuffer eviction. (Production wiring of all this is a different story — §2.) |
| configd-replication-engine | **Good** (84% mutation score backs it). assertDoesNotThrow sites are legitimate no-op contracts, paired with behavioral checks. |
| configd-transport | **Good.** Real TLS handshake/SAN-mismatch negative tests, CRC golden vectors, codec bounds; sleeps are bounded retries. |
| configd-control-plane-api | **Adequate.** Result-type assertions on real units with lambda fakes; thin on assertion depth (instanceof-only in places), 1 zero-assert test. |
| configd-common | **Adequate-to-good.** FileStorage CRC corruption tests are real; Buggify lifecycle tests assert state transitions. |
| configd-observability | **Adequate.** Burn-rate/invariant tests assert values; SLO definition tests mirror constants (change-detector value only); F-C8 cluster. |
| configd-testkit | **Mixed.** Simulation infrastructure tests are real; the headline 20,000-case sweep has structural vacuity (F-C5) and asserts only 2 invariants. |
| configd-linz | **Untestable in CI as shipped:** 6 of 10 tests (the entire checker self-test suite) are env-gated on `PORCUPINE_BIN` and skip silently in the default build; the 4 HistoryWriter unit tests are real. |

## 4. Candidate findings

| ID | Severity | Finding | Evidence |
|---|---|---|---|
| TF-1 | **P1** | **configd-consensus-core mutation score 58% (<60% charter line; 175 survivors + 102 no-coverage in 658), with survivors concentrated in the exact safety kernel**: commit-rule §5.4.2, vote persistence, WAL/term fsync, ReadIndex invalidation, nextIndex conflict walk-back, joint-consensus completion. Not the <40% auto-P1, but escalated to P1 because the surviving mutants are individually data-loss/split-brain class and three of them have named-but-vacuous tests pointing at them (F-C1/F-C5/F-C6) — the protection *looks* present and is not. | §1.2, §1.3 #1–#7, #10 |
| TF-2 | **P1** | **Durability is asserted nowhere it counts: every `Storage::sync` on the consensus path can be deleted without a test failing** (`RaftLog.truncateFrom:367`, `DurableRaftState.persistValues:133`), and both vote persistence (#2) and term persistence sit behind crash-restart scenarios no test performs. The "Persist BEFORE in-memory update (crash safety)" comments are unverified claims. | §1.3 #2/#3/#4, F-C6 |
| TF-3 | **P2** | **Joint-consensus reconfiguration is triple-orphaned**: zero production callers (Phase B CF-14, re-confirmed by grep here), no test completes a transition (F-C2), and a **46% mutation score on exactly that path** (79 mutants across `proposeConfigChange`/`handleCommittedConfigChange`/`configAtIndex`/`recomputeConfigFromLog`/`deserializeConfigChange`, 36 killed, 43 surviving or uncovered). JaCoCo confirms branch-level: `configAtIndex` 5/12, `recomputeConfigFromLog` 11/18, `isConfigChangeEntry` 8/12 branches — roughly 55-60% of reconfig decision points ever execute. | §1.3 #7, F-C2, §2.3-4 |
| TF-4 | **P2** | **The runtime invariant net's call-sites are unverified** — all 5 `InvariantChecker::check` sites in RaftNode can be removed with no test failure (unit tests wire `InvariantChecker.NOOP`); same pattern in `ConfigStateMachine` (`applySwitch:269` removable; `per_key_order`/INV-W1 referenced by no test). Mitigating: `InvariantNetMetricTest` (configd-server) does prove the net is non-NOOP in a running server for **one** synthetic violation — so the wiring is tested once, but each individual check, and every check condition's phrasing, is not. A2's own ledger documented one such check as locally vacuous (RaftNode.java:1362-1366 comment); nothing now prevents the others from being equally vacuous. | §1.3 #10 |
| TF-5 | **P2** | **Replay/signing protection (F-0052) untested at its core**: nonce generation removable (PIT), `SigningKeyStore.load` NO_COVERAGE within its module — 11 mutants — (key-file corruption/rotation behavior unknown). | §1.3 #8 + honorable mentions |
| TF-6 | **P2** | **Vacuous/misleading named regression tests** (F-C1, F-C2, F-C3, F-C4, F-C6): five tests whose names promise safety/regression coverage their bodies do not deliver. These corrupt any "we have a test for that" reasoning, including prior sessions' ledger closures. | §3.1 |
| TF-7 | **P2** | **Seed sweep over-counts protection ~14×**: 20,000 of 21,408 green tests are 2 invariants × 10,000 seeds, with silent bail-outs (F-C5) and non-deterministic per-node RNG (harness-runs.md). Suite size is a vanity metric here. | §3.1 F-C5 |
| TF-8 | **P1** | **The deployable artifact is the least-tested module, and its untested code is exactly the correctness-bearing seams.** configd-server: 61% line / **46% branch**. At zero executions: the entire client data API + auth gate (`HttpApiServer$ConfigHandler` 0/60 branches), the production network bridge (`RaftTransportAdapter` 0/16 lines — no test ever traverses encode→TCP→decode→dispatch), the in-server linearizable-read dispatch (`ConfigdServer:483-512`), the snapshot-drop path (RaftNode:1300-1304, reached only via this server's transport), the inbound-message exception swallow (:690), and boot-time peer-address parsing. Everything between "RaftNode is correct" and "a client got the right answer" rides on unexecuted code. | §2.2, §2.3 ##3/6/7, §2.4 ##1/2/4/5 |
| TF-9 | **P2** | **Coverage-agent sensitivity / CI fragility:** two keytool-shelling TLS regression tests (`find0050`, `find0051`) sit ~2s under their hardcoded `@Timeout(10)` on 2-CPU hardware and failed 5/5 attempts under the JaCoCo agent. Any instrumentation, slower runner, or co-tenancy flips the suite red — and conversely, today's green depends on never measuring coverage in CI. | §2.1 |
| TF-10 | **P3** | configd-linz runs at 9% line coverage in any default build (PORCUPINE_BIN env-gate skips 6 of 10 tests); CI never exercises the linearizability checker glue it gates releases on (corroborates Phase A's gating observation). | §2.2 |

## 5. Exact harness edits (ws-clean only) + reproduction

All edits in `/home/ubuntu/ws-clean/pom.xml` (parent pom). **No source or test file was
modified anywhere; the audited repo `/home/ubuntu/Code/Configd` was not touched except for
this report.** Edits, in order:

1. **`<properties>`**: added empty default `<argLine></argLine>` so surefire's late-bound
   `@{argLine}` resolves when the JaCoCo agent is absent (JaCoCo's `prepare-agent`
   overwrites the property at runtime).
2. **surefire `pluginManagement` config**: `<argLine>--enable-preview</argLine>` →
   `<argLine>@{argLine} --enable-preview</argLine>` (JaCoCo agent injection; preview flag
   preserved as found).
3. **`pluginManagement`**: added `org.jacoco:jacoco-maven-plugin:0.8.14` with executions
   `audit-jacoco-prepare` (`prepare-agent`, default phase) and `audit-jacoco-report`
   (`report` bound to `test`).
4. **`pluginManagement`**: added `org.pitest:pitest-maven:1.25.4` with plugin-dependency
   `org.pitest:pitest-junit5-plugin:1.2.3` and configuration: `mutators=DEFAULTS`,
   `threads=2`, `timeoutConstant=8000`, `timeoutFactor=1.5`, `jvmArgs=--enable-preview`,
   `outputFormats=XML,HTML`, `timestampedReports=false`. No lifecycle binding.
5. **`<build><plugins>`**: declared `jacoco-maven-plugin` (activates 3's executions in every
   module) and `pitest-maven` (no executions; makes 4's config apply to CLI goal
   invocations).

One transient fix during wiring: the first version of edit 2's XML comment contained the
literal string `--enable-preview`, which is illegal inside an XML comment (`--`); Maven
failed with "Non-parseable POM ... two dashes". Reworded the comment.

Reproduction:

```bash
# Mutation testing, per target module (25-min cap each):
cd /home/ubuntu/ws-clean
for m in configd-consensus-core configd-replication-engine configd-config-store \
         configd-edge-cache configd-distribution-service; do
  timeout 1500 ./mvnw -q -pl $m org.pitest:pitest-maven:mutationCoverage
done
# Reports: <module>/target/pit-reports/{index.html,mutations.xml}

# Coverage (SeedSweepTest excluded — see §2 for why and for the recorded impact):
./mvnw -q test -Dtest='!SeedSweepTest' -Dsurefire.failIfNoSpecifiedTests=false
# configd-transport, configd-server, configd-linz additionally need (see §2.1 flake note):
#   -Djunit.jupiter.execution.timeout.mode=disabled
# Reports: <module>/target/site/jacoco/{index.html,jacoco.xml}
```

Raw logs from this session: `/tmp/pit-<module>.log` (PIT stdout incl. stats blocks);
`/tmp/jacoco-reactor.log` (attempt 1, fails at transport), `/tmp/jacoco-reactor2.log`
(attempt 2, same flake), `/tmp/jacoco-reactor3.log` (consensus-core→linz, fails at server),
`/tmp/jacoco-transport2.log` + `/tmp/jacoco-server-linz2.log` (green with
`-Djunit.jupiter.execution.timeout.mode=disabled`).
