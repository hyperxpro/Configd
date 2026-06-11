# Mutation kill list (B3 / RR-085, RR-086, RR-089, RR-092)

Every named Session-1 survivor gets a named killing test, recorded as a
mutant -> test pair with the discrimination evidence. Equivalent-mutant claims
carry the actual mutant diff + why it is behaviorally invisible. "Ran out of
time" is NOT equivalence — honest documented-residuals are left as such.

PIT wiring: `-Pmutation` profile (parent pom + per-module thresholds), recipe
in `docs/audit-session-1/test-forensics.md` §5. Re-run a single survivor fast
with `-DtargetClasses=...` scoping; official numbers from one final per-module
run (recorded in the SCORES section at the bottom).

Status legend: KILLED (named test fails iff the mutant is applied) /
EQUIVALENT (mutant is behaviorally invisible, justified with the diff) /
RESIDUAL (documented, still surviving).

---

## RR-085 — consensus-core safety-kernel named survivors

### 1. `RaftNode.maybeAdvanceCommitIndex` — §5.4.2 prior-term commit guard
- Mutant: RemoveConditional / "replaced equality check with false" on
  `if (log.termAt(n) != currentTerm) { continue; }` (the prior-term guard).
- Killing test: `CertificationTest$Figure8Adversarial.`
  `leaderCannotCommitPriorTermEntryByReplicationCountAlone` (de-tautologised;
  also RR-091 F-C1). Constructs Raft Figure 8 and drives the production
  `maybeAdvanceCommitIndex` via `handleAppendEntriesResponse`: a prior-term
  entry at a quorum must NOT commit by replication count; a current-term entry
  at a quorum commits it indirectly.
- Status: **KILLED**. Verified 2026-06-11: guard present -> pass; guard ->
  `if(false)` -> FAIL (`expected: <1> but was: <2>`). Capture:
  `docs/session-2/captures/rr-085-figure8.txt`.

### 2. `RaftNode.handleRequestVote` — vote-persist removal (double-vote/split-brain)
- Mutant: "removed call to DurableRaftState::vote" at the grant branch
  (`durableState.vote(req.candidateId())`).
- Killing test: `VotePersistenceCrashTest.`
  `recoveredNodeRemembersItsVoteAndRefusesToDoubleVoteAcrossRestart`. A node
  grants a vote to A@term5 (persisted via the production grant path), CrashStorage
  crash, recover a fresh node over `recoveredView()`, then a different candidate B
  requests a vote in term 5 — must be REJECTED. The in-memory votedFor masks the
  bug within one process; the loss only shows across the restart.
- Status: **KILLED**. Verified 2026-06-11: present -> pass; `vote(...)` removed ->
  FAIL (`recovered.votedFor()` expected Node-2 but was null).

### 3. `RaftNode.becomeFollower` — `readIndexState.clear()` on step-down
- Mutant: VoidMethodCall removal of `readIndexState.clear()` in `becomeFollower`.
- Killing test: `ReadIndexStepDownClearTest.`
  `pendingReadIsClearedOnStepDownAndNotServedAfterReElection`. A single-node
  leader confirms a read, steps down (higher-term AppendEntries), re-elects, and
  the OLD read id must NOT be serveable. The per-call leadership re-check in
  `isReadReady` masks the bug while a follower, so the kill is observed across
  step-down -> re-election: with the clear removed, the old read survives and
  `isReadReady(oldReadId)` returns true (cross-term stale serve).
- Status: **KILLED**. Verified 2026-06-11: present -> pass; clear removed -> FAIL
  (`isReadReady(readId)` expected false but was true).

### 4. `RaftNode.handleAppendEntriesResponse` — nextIndex walk-back arithmetic
- Mutant(s): `nextIndex.put(from, Math.max(1, ni - 1))` arithmetic — primarily
  `ni - 1` -> `ni` (the leader freezes prevLogIndex and never reconciles a
  behind/divergent follower).
- Killing test: `NextIndexWalkBackTest.`
  `rejectionWalksNextIndexBackOneStepAtATimeUntilTheFollowerConverges`. Drives the
  production rejection loop and asserts the retried AppendEntries' prevLogIndex
  STRICTLY DECREASES by one per rejection, then that the follower converges.
- Status: **`ni - 1` -> `ni` KILLED** (verified: present -> pass; mutant -> FAIL,
  prevLogIndex freezes at top). The `Math.max(1, ...)` floor mutant
  (`max(1,...)` -> `max(0,...)`) is NOT killed by this test and is flagged for
  PIT verification: it only differs at the ni==1 boundary (nextIndex 0 vs 1 ->
  prevLogIndex -1 vs 0). Disposition (equivalent vs needs-a-boundary-test)
  resolved against the PIT baseline.

## RR-086 — consensus-path `Storage::sync` removals (crash durability)

### `RaftLog.compact` — `storage.sync()` after the WAL rewrite
- Mutant: "removed call to Storage::sync" at the compaction WAL-rewrite site.
- Killing test: `WalSyncCrashTest.compactionWalDeletionSurvivesCrashRestart`.
  Full compaction (snapshot folds the whole WAL) deletes the WAL via a
  rename-style truncate; the trailing sync makes that durable. CrashStorage
  reverts un-synced renames on crash, so without the sync the stale WAL reappears
  and the snapshot boundary resolves back to 0.
- Status: **KILLED**. Verified 2026-06-11: present -> pass; sync removed -> FAIL.
  Independently confirmed the existing `SnapshotCrashRecoveryTest` (6 cells) does
  NOT kill this mutant (all green with the sync removed) — i.e. this is a genuine
  new kill, not pre-covered.

### `RaftLog.truncateFrom` — `storage.sync()` after the WAL rewrite
- Mutant: "removed call to Storage::sync" at the conflict-truncation site.
- Note: the conflict-truncation rewriteWal shape (truncate(tmp) ->
  appendToLog(tmp) -> rename) is a CrashStorage modelling corner — a self-durable
  append following a deferred truncate of the same log is not captured for THAT
  path, so a CrashStorage test cannot cleanly observe this specific sync. The
  identical durability mechanism (rename + trailing sync) IS pinned on the
  compact path above. Disposition deferred to the PIT baseline: if it still
  survives, it is recorded as a documented residual with this modelling rationale
  (NOT claimed equivalent — the sync is load-bearing; only the harness cannot
  reach it on this exact path).
- Status: (PIT-verification owed)

### `DurableRaftState.persistValues` — `storage.sync()` after `storage.put`  [EQUIVALENT]
- Mutant: "removed call to Storage::sync" at `persistValues:133`, immediately
  after `storage.put(STORAGE_KEY, ...)`.
- Disposition: **EQUIVALENT MUTANT**. `Storage.put` is SELF-DURABLE: `FileStorage.put`
  writes a temp file, `force(true)` (data fsync), atomic-renames, then dir-fsyncs
  (calls `sync()` internally at `FileStorage.java:70`) BEFORE returning; CrashStorage
  models `put` as reaching the durable image immediately. The trailing
  `storage.sync()` after that `put` is therefore REDUNDANT — the value is already
  fully durable when `put` returns. Removing it changes no observable behavior in
  either the real FileStorage or the CrashStorage model. The vote/term DURABILITY
  the row cares about is provided by the `put` and is pinned by
  `VotePersistenceCrashTest` (RR-085 #2). Mutant diff: delete line 133
  `storage.sync();`. Behaviorally invisible because `put` already dir-fsynced.

## RR-091 — vacuous named tests
- **F-C1** `CertificationTest.leaderCannotCommitPriorTermEntryByReplicationCountAlone` —
  de-tautologised (see RR-085 #1 above). **FIXED + mutant-killing.**
- **F-C4** `ConfigdServerTest.tickLoopContinuesAfterDriverException` — used to only
  sleep twice + assertNotNull(driver()). Rewritten to inject a throwable through the
  REAL `ConfigdServer.raftInboundHandler` route seam (routeMessage ->
  node.handleMessage -> throwing transport.send) on the same single-thread tick
  executor, asserting (a) the inbound handler SWALLOWS the throwable (RR-008's first
  observation — no propagation to the caller) and (b) the fixed-rate tick task keeps
  advancing afterward (FIND-0005 zombie-tick property). **FIXED.** (F-C2/F-C3 were
  de-vacuated under RR-018 in an earlier S2 commit; F-C6 is the RR-086 RaftLogWalTest
  blind spot, addressed by the CrashStorage WalSyncCrashTest above.)

## RR-089 — `InvariantChecker::check` VoidMethodCall removals
- The new `AssertionTwinFiringTest` fires every twin and asserts a wired checker
  OBSERVES each — but it fires the structurally-guarded RaftNode twins through a
  SYNTHETIC seam (`fireInNodeTwinForTest`), NOT the production call site. So a PIT
  VoidMethodCall removal of the PRODUCTION `invariantChecker.check(...)` at e.g.
  `applyCommitted` (version_monotonicity / state_machine_safety), `becomeLeader`
  (election_safety / leader_completeness), or `handleAppendEntries` (log_matching)
  is NOT killed by that test. **Empirically confirmed 2026-06-11** (manual
  call-site removal):
  * production `version_monotonicity` check removed @ applyCommitted ->
    AssertionTwinFiringTest still GREEN (mutant survives).
  * production `read_freshness` check removed @ assertReadServeInvariants ->
    still GREEN (the same poisoned read also trips `read_index_bounded`, so the
    runnable still throws and `expectFires` is satisfied by the wrong twin — the
    firing test is not call-site-discriminating).
- Root cause (RR-089 as written): these production checks are structurally
  defense-in-depth — their condition is true by construction on the real path, so
  no protocol input makes them fire; only a poisoned input through the EXACT
  production call site, with an assertion that ONLY that check firing satisfies,
  kills the call-site mutant.
- Plan: after the PIT baseline pins the exact surviving check-call set, add
  REAL-MONITOR wiring tests that (a) drive each surviving production call site
  with a RecordingChecker and a poisoned input and (b) assert the SPECIFIC twin
  fires (distinct from sibling checks), so the call removal fails the test. Where
  a check is genuinely unreachable-to-fire on the production path even with
  poisoning (pure defense-in-depth), record it as a documented equivalent with
  the structural argument. **Status: PIT-baseline-gated (in progress).**

## RR-092 — config-store targeted assertion gaps

### `VersionedConfigStore.delete` + `.applyBatch` — sequence-monotonicity guard
- Mutant: RemoveConditional ORDER_ELSE on `if (sequence <= currentSnapshot.version())
  throw ...` (delete:114 and applyBatch:137). Only `put`'s guard was regression-tested.
- Killing tests: `VersionedConfigStoreTest$SequenceMonotonicityGuard.`
  `deleteWithStaleSequenceThrows`, `applyBatchWithStaleSequenceThrows`
  (+ `monotonicDeleteAndBatchStillApply` for liveness). A replayed delete/batch at a
  stale-or-equal sequence must throw and must NOT regress the version or mutate state.
- Status: **KILLED** (both). Verified 2026-06-11: present -> pass; both guards
  removed -> 2 failures (the stale-delete and stale-batch tests).

### `ConfigStateMachine.decodeTrailer` — 9 boundary survivors
- Killing tests: `ConfigStateMachineTest$SnapshotTrailerCompatibility` (10 new
  boundary cases: empty/legacy, 7-byte reject, raw-8-epoch, 8-byte TLV,
  trailerLen==MAX accept, MAX+1 reject, negative reject, truncated reject,
  trailerLen<Long.BYTES skip-no-epoch, trailerLen==Long.BYTES read-epoch-no-tail).
- LOAD-BEARING boundaries KILLED (verified 2026-06-11, clean builds):
  * `trailerLen > MAX` -> `>= MAX`  (tlvTrailerLengthAtMaxIsAccepted) — KILLED
  * `trailerLen >= Long.BYTES` -> `> Long.BYTES` (tlvTrailerExactlyEpochSizeReadsEpochWithNoTail) — KILLED
  * `buf.remaining() < trailerLen` -> `<= ` (tlvTruncatedTrailerIsRejected) — KILLED
  * `trailerLen < 0` reject — pinned (tlvNegativeTrailerLengthIsRejected)
- DOCUMENTED EQUIVALENT / near-equivalent boundary mutants (verified surviving even
  with the new tests, with the reason — NOT papered over):
  * `unknownTail > 0` -> `unknownTail >= 0`: the guarded body is
    `buf.position(buf.position() + unknownTail)`; with unknownTail==0 that is a
    no-op (`position(+0)`). `> 0` vs `>= 0` is behaviorally identical. **EQUIVALENT.**
  * `remaining >= 8` -> `remaining > 8` (the magic gate): only differs for an
    exactly-8-byte trailer that starts with the MAGIC. Such a trailer misrouted to
    the raw-epoch path reads the 8 magic+len bytes as a long = 0xC0FD7A1100000000,
    which is NEGATIVE; the carry-forward guard `restoredEpoch > signingEpoch(0)` is
    then false, so the epoch stays 0 — identical observable to the TLV path
    (entries load, epoch 0). The fixed magic's sign makes this **near-EQUIVALENT**
    for the only observable (signingEpoch); the lower side (remaining<8) is pinned
    by sevenTrailerBytesIsRejected. PIT-baseline will confirm the exact survivor
    set; these two are documented equivalents, not residual gaps.

---

## Official PIT scores (final per-module run)
- (pending — run after tlc2 vanishes; see SCORES section)
