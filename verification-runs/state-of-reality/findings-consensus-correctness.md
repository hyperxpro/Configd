# Findings — Consensus Correctness (state-of-reality audit)

Auditor lens: does consensus actually exist and run, and are the invariants real?
All runs performed live in an isolated worktree on Java 25 (Corretto) / TLC 2026.04.09
(`spec/tla2tools.jar`). READ-ONLY: no source/spec/cfg/doc modified.

## Bottom line (5 bullets)

- **The three TLA+ specs DO model-check GREEN under live re-run.** ConsensusSpec:
  13,775,323 states / 3,299,086 distinct / depth 25, all 9 invariants pass (3m30s).
  ReadIndexSpec: 12,403,444 / 2,276,125 / depth 38, 5 invariants pass (1m03s).
  SnapshotInstallSpec: 5,995,717 / 847,124 / depth 14, 5 invariants pass (23s).
  Counts match the documented claims. [VERIFIED-PASS]
- **`spec/tlc-results.md` is STALE and contradicts the .cfg actually run.** It lists
  `NoStaleOverwrite` as a checked invariant and omits `LeaderCompleteness` and
  `VersionMonotonicity`; the real `ConsensusSpec.cfg` removed `NoStaleOverwrite`
  (line 36) and checks the other two. The saved `tlc-output.txt` / `tlc-rerun.log`
  are from a different machine (paths `/home/ubuntu/Programming/Configd/`). [VERIFIED-FAIL on the doc]
- **Several "invariants" are tautological or vacuous and check nothing.**
  ReadIndexSpec `ReadFreshness` and `NoStaleLeaderServe` have consequent literally
  `TRUE`; SnapshotInstallSpec `NoCommitRevert` is `A<=B \/ A>B` (always true). The
  GREEN result on those specs is therefore much weaker than "linearizable reads /
  snapshot safety are proven." [VERIFIED-PASS that they pass; the pass is near-meaningless]
- **The Java Raft implementation is REAL, not stubs** — 1648-line `RaftNode.java`
  with PreVote+vote leader election, durable term/vote, AppendEntries replication,
  quorum-based commit, joint-consensus reconfiguration, InstallSnapshot, ReadIndex,
  leadership transfer. `configd-consensus-core` tests: **159 run, 0 fail, 0 error,
  2 skipped** (skips are honestly `@Disabled` wire-compat stubs). Multi-node (3- and
  5-node) clusters with partitions are genuinely exercised. [VERIFIED-PASS]
- **The runtime "invariant checkers" are wired to `NOOP` in production and are mostly
  tautological even when active.** `ConfigdServer.java:248` passes
  `RaftNode.InvariantChecker.NOOP`. So the TLA+->runtime "bridge" does nothing in a
  running server, and even in tests most checks re-assert local bookkeeping, not
  cross-node safety. **Apalache is ABSENT** (not installed; specs have no real
  `@type` annotations) — all "symbolic checking" claims are DOC-ONLY. [VERIFIED-FAIL / ABSENT]

## Spec-invariant -> code mapping

| Spec invariant (file:line) | Real runtime enforcement in Java? | Classification |
|---|---|---|
| ElectionSafety — one leader/term (ConsensusSpec.tla:149) | `becomeLeader` requires `clusterConfig.isQuorum(votesReceived)`; vote granted once per term via durable `votedFor` (RaftNode.java:1000, 934-942, 1165). Dual-majority quorum is real (ClusterConfig.java:117-123). | VERIFIED-PASS (logic real); the line-1165 `invariantChecker.check` itself is NOOP in prod |
| LeaderCompleteness (ConsensusSpec.tla:161) | Structural: voting restriction `log.isAtLeastAsUpToDate` (RaftNode.java:935). Runtime check at :1171 is only `lastIndex >= commitIndex` — a local bound, NOT completeness. | EXISTS-UNTESTED at runtime / DOC-ONLY for the assertion |
| LogMatching (ConsensusSpec.tla:170) | `RaftLog.appendEntries` consistency check (prevLogIndex/Term). Runtime check at :833 only reads back the just-stored term locally — not cross-node. | VERIFIED-PASS (replication logic) / assertion is weak |
| StateMachineSafety (ConsensusSpec.tla:177) | Commit only via quorum + current-term rule (RaftNode.java:1319-1349). Runtime check at :1369 only asserts `entry.index()==nextApply` (bookkeeping). | VERIFIED-PASS (commit logic) / assertion is weak |
| VersionMonotonicity (ConsensusSpec.tla:185) | Runtime check at :1361 is `nextApply > lastApplied` where `nextApply=lastApplied+1` — **tautology**. | DOC-ONLY (tautological assertion) |
| ReconfigSafety / joint quorum (ConsensusSpec.tla:216) | REAL: `maybeAdvanceCommitIndex` uses `clusterConfig.isQuorum` which requires both majorities when joint (RaftNode.java:1343 + ClusterConfig.java:121-122). Check at :545 just mirrors an `if`. | VERIFIED-PASS (quorum logic) |
| SingleServerInvariant — 1 config change in flight (ConsensusSpec.tla:229) | `proposeConfigChange` guards on `configChangePending` (RaftNode.java:521). Check at :532 re-asserts the same guard. | VERIFIED-PASS (guard real) |
| NoOpBeforeReconfig (ConsensusSpec.tla:245) | `proposeConfigChange` requires `noopCommittedInCurrentTerm` (RaftNode.java:524); flag set in `applyCommitted` (:1374). | VERIFIED-PASS (guard real) |
| ReadIndex freshness (ReadIndexSpec ReadFreshness:237) | Spec invariant body is literally `TRUE`. Java `isReadReady` re-checks leadership (RaftNode.java:421). | Spec invariant VACUOUS; Java check EXISTS-UNTESTED-against-spec |

## Findings table

| Claim | Classification | Evidence |
|---|---|---|
| ConsensusSpec.tla model-checks GREEN (all invariants) | VERIFIED-PASS | `java -XX:+UseParallelGC -cp tla2tools.jar tlc2.TLC -workers 2 -config ConsensusSpec.cfg ConsensusSpec.tla` -> "Model checking completed. No error has been found. 13775323 states generated, 3299086 distinct... depth 25. Finished in 03min 30s. EXIT=0" |
| ReadIndexSpec.tla model-checks GREEN | VERIFIED-PASS | Same harness -> "No error... 12403444 states generated, 2276125 distinct... depth 38. Finished in 01min 03s. EXIT=0" |
| SnapshotInstallSpec.tla model-checks GREEN | VERIFIED-PASS | Same harness -> "No error... 5995717 states generated, 847124 distinct... depth 14. Finished in 23s. EXIT=0" |
| Documented state counts in tlc-results.md (13.78M/3.30M/25) | VERIFIED-PASS | My live ConsensusSpec rerun reproduces 13,775,323 / 3,299,086 / depth 25 exactly. |
| tlc-results.md invariant list matches what is checked | VERIFIED-FAIL | tlc-results.md:17,37 list `NoStaleOverwrite`; ConsensusSpec.cfg:36 says it was REMOVED and replaced by LeaderCompleteness+VersionMonotonicity (cfg:29,31; tla:188-191). Doc omits both. Doc is stale. |
| tlc-output.txt / tlc-rerun.log are this repo's runs | VERIFIED-FAIL | Both reference `/home/ubuntu/Programming/Configd/spec/...` (tlc-output.txt:3, tlc-rerun.log:5) — a different tree than this audit's `/home/ubuntu/Code/Configd`. They are imported artifacts, re-verified by my live runs. |
| ReadIndexSpec `ReadFreshness` proves read freshness | VERIFIED-FAIL (vacuous) | ReadIndexSpec.tla:237-244 — consequent is `TRUE`. Checks nothing. |
| ReadIndexSpec `NoStaleLeaderServe` proves stale leader can't serve | VERIFIED-FAIL (vacuous) | ReadIndexSpec.tla:251-264 — consequent is `TRUE`. |
| ReadIndexSpec `ReadIndexBoundedByMaxIndex` is a meaningful safety invariant | EXISTS-UNTESTED-as-safety | tla:223-224 — only `r.readIdx <= MaxIndex`; trivially true since commitIndex <= MaxIndex by construction (tla:103). |
| SnapshotInstallSpec `NoCommitRevert` is a real invariant | VERIFIED-FAIL (tautology) | SnapshotInstallSpec.tla:173-176 — `A<=B \/ A>B`, always TRUE for integers. |
| SnapshotInstallSpec models a buggy/malicious leader sending a bad snapshot | ABSENT | `SendInstallSnapshot` (tla:106-115) only ever sends `snapshot[leader]`, itself bounded by LocalSnapshot; the spec cannot express "leader sends a snapshot it doesn't have." InflightTermMonotonic holds by construction, not by adversarial coverage. |
| TLC historically found real violations | VERIFIED-PASS | 11 `*_TTrace_*` counterexample files exist. `ReadIndexSpec_TTrace_1776462705.tla:132` final state has TWO leaders at term 1 (`state=(n1:>"leader"@@n2:>"leader"@@n3:>"follower")`, `currentTerm=(n1:>1@@n2:>1@@...)`) — an ElectionSafety violation under the old MaxTerm=3 abstraction, since fixed (tla:78-79) and parameters cut to MaxTerm=2. |
| ConsensusSpec dev found+fixed 7 bugs | EXISTS-UNTESTED (corroborated) | 7 `ConsensusSpec_TTrace_*.bin` files exist matching the 7 bugs in tlc-results.md:43-79. .bin not decoded standalone, but count + the decoded ReadIndex/Snapshot .tla traces corroborate the narrative. |
| RaftNode is a real consensus impl (election/term/vote/replication/commit/quorum) | VERIFIED-PASS | RaftNode.java 1648 lines: PreVote (startPreVote:1077), election (startElection:1119), durable vote (handleRequestVote:938 `durableState.vote`), replication (sendAppendEntries:1221, handleAppendEntries:797), quorum commit (maybeAdvanceCommitIndex:1319), joint reconfig (proposeConfigChange:514). |
| ClusterConfig implements correct dual-majority quorum | VERIFIED-PASS | ClusterConfig.java:117-123 — joint requires `countIntersection(...,voters)>=majorityOf(voters)` AND same for newVoters; `majorityOf=size/2+1` (:164). Mirrors spec IsQuorumOf (ConsensusSpec.tla:71-76). |
| consensus-core tests pass | VERIFIED-PASS | `./mvnw -pl configd-consensus-core -am test` -> module Results: "Tests run: 159, Failures: 0, Errors: 0, Skipped: 2". BUILD SUCCESS. |
| Tests exercise real multi-node consensus, not single-node | VERIFIED-PASS | RaftNodeTest TestCluster(3)/(5) with message capture+delivery, partitions ("Do NOT deliver"), section 5.4.2 current-term commit test (RaftNodeTest.java:280-400). |
| 2 skipped tests are honest stubs, not hidden failures | VERIFIED-PASS | WalWireCompatStubTest / SnapshotWireCompatStubTest are `@Disabled` with "NOT pretending this passes" (WalWireCompatStubTest.java:40,58). |
| Runtime InvariantChecker enforces TLA+ invariants in production | VERIFIED-FAIL | ConfigdServer.java:248 constructs RaftNode with `RaftNode.InvariantChecker.NOOP` -> every `invariantChecker.check(...)` is a no-op in a running server. |
| Apalache (symbolic) is used | ABSENT | No apalache binary on system (`find / -iname '*apalache*'` empty); ConsensusSpec.tla has no real `@type` annotations (only the word "Apalache" in comments :12, cfg:10). Only TLC (tla2tools.jar) exists. |

## Cross-examination requests (for peers)

1. **To verification-evidence:** `spec/tlc-results.md` lists an invariant (`NoStaleOverwrite`)
   that no longer exists in `ConsensusSpec.cfg`, and the saved `tlc-output.txt`/`tlc-rerun.log`
   come from a different machine path (`/home/ubuntu/Programming/Configd`). Do the
   `docs/certification/` and `verification/final-report.md` claims cite these stale
   artifacts as proof? Flag every place a stale invariant list or imported log is treated
   as current evidence.

2. **To concurrency-readpath:** The ReadIndexSpec invariants `ReadFreshness` and
   `NoStaleLeaderServe` have consequent `TRUE` (prove nothing). The actual linearizable-read
   safety therefore rests entirely on the Java path (`readIndex`/`isReadReady`/
   `confirmPendingReads` + the heartbeat-quorum lease). Please confirm whether the Java
   ReadIndex path is concurrency-safe given `RaftNode` is single-thread-per-ADR-0009 but
   `role`/`leaderId` are read cross-thread (RaftNode.java:56-57) — is there a TOCTOU between
   `isReadReady` and serving?

3. **To design-vs-reality:** Production wires `InvariantChecker.NOOP` (ConfigdServer.java:248),
   so the documented "TLA+ invariants bridged to runtime assertions (Rule 13)" do nothing in a
   running server, and most checks are tautological even in tests. Do the design/certification
   docs claim runtime invariant enforcement as a live safety net? If so, that is design-vs-reality drift.

4. **To verification-evidence:** SnapshotInstallSpec cannot model a leader sending a snapshot
   it doesn't hold (`SendInstallSnapshot` only ships `snapshot[leader]`), and its `NoCommitRevert`
   is a tautology. Do any docs claim this spec "proves InstallSnapshot safety" without that caveat?

## Phase 2 — Cross-examination

### CX-1 — Does "20k SeedSweep tests pass" represent real leader-failure coverage? — REFUTE (the "mostly trivially green" hypothesis), with a REFINE on seed diversity

verification-evidence said `commitSurvivesLeaderFailure` has FOUR early `return;` and "asserts nothing yet counts green."

- **Count is THREE, not four.** SeedSweepTest.java:65-68 (no leader), :72-75 (commit timeout),
  :85-88 (no new leader). Each bail asserts nothing. The final assert is :92-96.
- **Empirically the bail paths essentially NEVER fire.** I compiled a probe replicating the
  test body exactly (`/tmp/cxdrv/io/configd/testkit/Cx1Probe.java`) against the compiled
  test classpath and ran it:
  - 300 seeds (run A): `bailNoLeader=0 bailCommit=0 bailNoNewLeader=0 REACHED_ASSERT_PASS=300 ASSERT_FAIL=0` → bail_fraction=0.0%
  - 300 seeds (run B): identical 0% bail / 100% reach
  - 1000 seeds (run C): `bailNoLeader=0 bailCommit=0 bailNoNewLeader=0 REACHED_ASSERT_PASS=1000` → bail_fraction=0.0%, reached_fraction=100.0%
  So ~100% of seeds reach `assertEquals("sweep-val", ...)`. The leader-failure safety assertion
  IS exercised. Reason: timeouts are generous vs config (election 150-300ms, heartbeat 50ms;
  RaftConfig.java:14-15,83) and budgets are electLeader=1200, commit=200, awaitStableLeader=2000 ticks.
  **Classification: [VERIFIED-PASS]** — the test does real multi-node leader-failure safety coverage, NOT trivially-green.
- **REFINE on "10,000 distinct seeds":** the per-node election RNG is UNSEEDED —
  `ConsistencyPropertyTests.java:77` builds each RaftNode with
  `RandomGenerator.of("L64X128MixRandom")` (no seed). Only `RaftSimulation(seed,…)` and
  `SimulatedNetwork(seed,1,10)` are seeded (RaftSimulation.java:37,39). So `seed` varies network
  jitter/partition RNG, NOT election timeouts; the scenario is the SAME structural test
  (commit→isolate leader→elect→read) repeated 10,000× with timing variation, not 10,000 distinct
  adversarial schedules. Runs A and B being identical confirms the *outcome* is stable, but
  "10k seeds" overstates execution diversity. **[VERIFIED-FAIL] on the implicit "10k distinct executions" framing.**

### CX-2 — Single-group correctness

**(a) Joint-consensus leader change mid-config — REFINE (code path correct; ZERO real test coverage; the test that claims it is vacuous).**
- A follower that received a joint (C_old,new) entry sets its in-memory `clusterConfig=joint`
  and `configChangePending=(i>commitIndex)` via `recomputeConfigFromLog` (RaftNode.java:686-712),
  called on every non-empty AppendEntries (:830-841) and at construction (:182). If it then wins
  election, `becomeLeader` (:1163) does NOT recompute but RETAINS the joint config it held as a
  follower — which is correct (it must use dual-majority). On commit of the joint entry,
  `handleCommittedConfigChange` (:1397-1444) drives leader→C_new append. Truncation of an
  uncommitted joint entry arrives via a non-empty AppendEntries → recompute fires. The code path
  is internally consistent. **[EXISTS-UNTESTED]** for the runtime behavior.
- **BUT the test `configChangePreservedAcrossElections` (ReconfigurationTest.java:257-270) is
  VACUOUS/MISNAMED:** its body proposes a NORMAL command (`new byte[]{42}`, :267), never calls
  `proposeConfigChange`, never triggers an election or isolation, and only asserts
  `commitIndex >= 2`. It exercises neither a config change nor a leadership transition.
  There is NO test covering mid-joint leader change. **[VERIFIED-FAIL]** on the claim that this is tested.
- Residual gap (untested): an *empty* heartbeat that advances commit over a joint entry does NOT
  call recompute (:830 guard `!req.entries().isEmpty()`); followers intentionally keep joint until
  C_new arrives, so this is by-design, but it is unverified by any test.

**(b) Single-node ReadIndex gating — AGREE (correct).** `readIndex()` (RaftNode.java:392-404):
for a single-node group `clusterConfig.peersOf(self).isEmpty()` is true, so it calls
`readIndexState.confirmAllLeadership()` immediately — correct, since a 1-node group is its own
quorum and needs no heartbeat. `isReadReady` (:417-425) still re-checks `role==LEADER` and
`isReady` requires `lastApplied >= readIndex` (ReadIndexState.java:92-98). Live caller marshals
onto the single tick thread (ConfigdServer.java:453-468). **[VERIFIED-PASS]** for single-node correctness.

### CX-3 — Is single-writer to the state machine actually guaranteed upstream? — REFUTE. W-1 CAN FIRE in production.

The store's single-writer precondition is **NOT enforced** by the consensus/server wiring:
- Tick path: `tickExecutor = Executors.newSingleThreadScheduledExecutor` (ConfigdServer.java:394)
  runs `driver.tick()` (:520) → `node.tick()` (MultiRaftDriver.java:99-103) → `tickHeartbeat`
  → `applyCommitted` → `stateMachine.apply` (RaftNode.java:1356-1390).
- Inbound path: `adapter.registerInboundHandler((from,msg) -> driver.routeMessage(...))`
  (ConfigdServer.java:257). `routeMessage` calls `node.handleMessage(message)` **directly, no
  marshalling, no lock** (MultiRaftDriver.java:116-121). `handleMessage` →
  `handleAppendEntries`/`handleInstallSnapshot` → `applyCommitted` / `stateMachine.restoreSnapshot`
  (RaftNode.java:797-862, 1456-1522).
- **That inbound handler runs on a per-connection VIRTUAL thread, not the tick thread:**
  `TcpRaftTransport.executor = Executors.newVirtualThreadPerTaskExecutor()` (TcpRaftTransport.java:100);
  each connection is `executor.submit(() -> handleInboundConnection(socket))` (:207,:426) and the
  blocking `in.readFully` loop calls `inboundHandler.accept(...)` (:268-269) on that virtual thread.
- RaftNode and ReadIndexState are explicitly documented "single-threaded… No synchronization is
  used" (RaftNode.java:18-19, ReadIndexState.java:18-19), and ConfigStateMachine mutates
  non-volatile fields on "the apply thread."

**Conclusion:** in a real multi-peer cluster there are ≥2 inbound virtual threads PLUS the tick
thread, all able to enter `applyCommitted`/`apply`/`restoreSnapshot` with NO mutual exclusion.
Single-writer is NOT guaranteed upstream — concurrent `apply`+`apply` or `apply`+`restoreSnapshot`
is reachable. So concurrency-readpath's **W-1 (lost updates) and W-2 (stale getters) are LIVE in
production**, and the apply loop is NOT strictly single-threaded as documented (ADR-0009 is violated
by the wiring). **[VERIFIED-FAIL]** on "single-writer / single apply thread." (Note: this is a
design-vs-wiring claim from reading; I did not build a concurrent-apply reproducer. The thread
identities are unambiguous from the cited file:lines.)
