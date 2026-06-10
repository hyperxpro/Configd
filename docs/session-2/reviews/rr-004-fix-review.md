# Review — RR-004 fix (commit-confirmed write acknowledgement) + RR-010 second-agent verification

- **Reviewer:** review-architect (Session 2)
- **Date:** 2026-06-10
- **Scope:** independent review of the RR-004 (P0, ack ≠ commit) fix — commits `88619a8`
  (discriminating test + pre-fix capture), `cdb7314` (fix), `4bb6323` (mutation tests +
  seq-prune bugfix + register/ADR/gates) — and second-agent verification of RR-010 (sim
  determinism, `c452aa1`).
- **Authoritative design:** `docs/decisions/adr-0033-commit-confirmed-write-acknowledgement.md`
  (Accepted) and `docs/session-2/reviews/adr-0033-review.md` (findings b/e/f/d the implementer
  had to honor).

---

## Verdicts

- **RR-004 fix: APPROVE-WITH-CHANGES.** The fix is correct, faithfully implements ADR-0033 and
  honors review findings (b)/(e)/(f)/(d), and the discriminating test is strong and
  non-vacuous. The single change required before final flip is **a test for the RR-029/W-1
  owner-thread tripwire's violation path** — the ADR calls it "load-bearing for this fix," the
  charter required me to find its test, and **none exists** (finding F-1 below). This does not
  reopen the ack=commit correctness — it closes the verification gap on the protective tripwire
  that shipped alongside it. Two minor notes (F-4, F-5), non-blocking.
- **RR-010 second-agent verification: SOUND — verified.** The determinism test genuinely
  covers the election schedule, the per-node election RNG is correctly decorrelated from the
  master seed with no other entropy reaching the determinism path, and the test re-runs green.

---

## JOB 1 — RR-004 fix

### A. The discriminating test (`AckEqualsCommitTest` + `CommitOutcomeSeamTest` + `RaftProposerCommitConfirmTest`)

**A.1 Capture internal consistency — CONSISTENT.** `rr-004-prefix-failure.txt` claims loss
rates 119 (CRASH) / 119 (ISOLATION) / 180 (SLOW_FOLLOWER) of 200 seeds; the verbatim
`[ERROR]` lines pin those exact counts at assertion lines `:75`/`:83`/`:91`; the printed
violations all carry `committedBeforeKill=false` (the kill landed in the append→commit window);
the re-runnable command and the pre-fix tree (`c452aa1` — RR-010 present, RR-004 absent) match.
The pre-fix test that produced these numbers (at `88619a8`: `SEEDS=200`, `killAfterTicks=1..4`,
`committedBeforeKill` flag) is the one whose output the capture quotes. No inconsistency.

**A.2 Vacuity hunt — NON-VACUOUS (guard is real and correct).** The sweep classifies each seed
as VIOLATION / ACKED_AND_PRESENT / NOT_ACKED / INCONCLUSIVE, and the test asserts BOTH
`violations == 0` AND `ackedAndSurvived > 0`. The non-vacuity guard is load-bearing: if every
seed bailed to INCONCLUSIVE (the SeedSweepTest silent-return failure mode), `violations == 0`
would pass but `ackedAndSurvived == 0` would FAIL the second assertion. `ackedAndSurvived`
counts the **baseline** committed write that must survive failover on every non-inconclusive
seed (and, for ACKED_AND_PRESENT seeds, the target too), so the guard holds even for
SLOW_FOLLOWER where the target write is intentionally never committed. The INCONCLUSIVE bail
paths (no stable leader, warm-up failed, propose stepped down, surviving quorum could not
elect) are honest "scenario did not set up" exits, not assertion-swallows — and they cannot
mask a violation because a masked seed contributes nothing to `ackedAndSurvived`, so wholesale
masking trips the guard.

**A.3 Ack observed at the HTTP seam — YES.** Post-fix the harness registers
`whenCommitOutcome(index, term, …)` and marks the write acknowledged **only** on `COMMITTED` —
the exact seam `ConfigdServer.raftProposer` blocks on and maps to HTTP 200. It does not read
internal raft commit/lastApplied state directly. Fail-safe property: the seam is resolved by
reflection; if `whenCommitOutcome` were renamed/removed, the harness falls back to the *pre-fix*
ack-on-accept branch, which would make the post-fix run FAIL — a reflection break cannot produce
a false green.

**A.4 Three real fault shapes inside the window — YES.** `killAfterTicks = 1 + floorMod(mix(seed),4)`
places the kill 1–4 ticks after local append, before quorum commit (the pre-fix capture's
`committedBeforeKill=false` on every violation empirically confirms the window placement).
LEADER_CRASH (`crashNode` = stop ticking + isolate), LEADER_ISOLATION (`isolateNode`, keeps
ticking), SLOW_FOLLOWER (pre-starve a quorum of 3 followers so the leader cannot commit the
target). After the fault, a NEW leader is elected on the surviving quorum, a fresh current-term
entry is committed on it (Raft §5.4.2, so it applies everything it ever will at the old
indices), and presence is checked in **the new leader's** store — not the dead leader's.

**A.5 Mutation coverage — VERIFIED present and reading as claimed.** Appendix B's three reverts
map to real tests that I read:
- ack-on-append regression → `RaftProposerCommitConfirmTest.appendedButUncommittedIsNotAckedAsCommitted`
  (severs followers after election, keeps the leader ticking — a true no-quorum case, asserts
  Indeterminate not Committed).
- ack-on-commit-but-wrong-seq → `CommitOutcomeSeamTest.committedCarriesTheAppliedMutationSeqForThisIndex`
  and `multipleEntriesAppliedInOnePassEachCarryTheirOwnSeq` (the SEQ_OFFSET=1000 decorrelates
  seq from index/lastApplied, so the per-index seq can only be right if threaded from
  `apply()`'s return — kills review finding e.2's "reads the last applied mutation's seq" mutant).
- COMMITTED reported for a different-term entry at the index → `CommitOutcomeSeamTest.differentTermAppliedAtIndexIsLostNotCommitted`
  (the surviving-registrant-observes-overwrite LOST case the e2e crash test cannot reach,
  because there the registrant is the killed leader).
LOST-reported-as-COMMITTED is the inverse of the same predicate test. All present, all read as
described.

**A.6 Determinism — NO wall-clock/sleep dependence in the discriminating sim.** The harness
drives the post-RR-010 `RaftSimulation` on the seeded virtual clock; kill points derive from the
seed; no `Thread.sleep`/`nanoTime` in `AckEqualsCommitTest`. (The two server-seam tests use a
real executor with a `Thread.sleep(300)` / 1 ms-deadline arm to provoke Indeterminate — that is
inherent to testing a real-millisecond deadline and is the right tool there, kept out of the
deterministic sweep.)

### B. The fix (`cdb7314` + the seq-prune fix in `4bb6323`)

**B.1 R-01 single-thread invariant — HELD, but the tripwire's violation path is untested (F-1).**
All seam state (`commitOutcomeCallbacks`, `appliedSeqByIndex`, `lastRecordedSeq`) is plain
(non-concurrent) maps/fields touched only from the tick thread; registration is fire-and-return
(`whenCommitOutcome` runs inline if already decidable, else `put`s — never joins the HTTP
future). The W-1 owner-thread tripwire (`ConfigStateMachine.assertOwnerThread`) binds the owner
on first apply and asserts on every later apply, with a metric hook
(`onApplyOwnerThreadViolation`) for prod. **Gap:** there is no test that drives a *second thread*
into `ConfigStateMachine.apply` to prove the assertion actually trips. The two `Thread[]` hits in
config-store tests are an unrelated HAMT concurrency test and a concurrent-readers store test;
neither exercises the apply path off-owner. Since the ADR designates this tripwire load-bearing
for the fix, the violation path must have a test (a 2-line: bind on one thread, apply on
another, assert the `InvariantChecker` throws / the metric increments).

**B.2 LOST predicate matches the ADR exactly — YES.** `decideCommitOutcome` returns LOST only
when `lastApplied >= index` AND a *different-term* entry occupies `index` (Log Matching makes
the slot permanent). `becomeFollower` re-evaluates pending outcomes (`fireCommitOutcomes`) but
explicitly does NOT drain to LOST — the in-code comment contrasts this with the read callbacks
(which ARE drained) and explains why (a stepped-down leader's entry may still commit). Truncation
without a replacement applied stays pending → surfaces as Indeterminate at the deadline. Exactly
the ADR's "sole definite-loss trigger."

**B.3 Leak/lifecycle + the seq-prune bugfix — CORRECT, and the self-found bug is real.** The
seq-prune bug: `recordAppliedSeq` called `lowestPendingCommitIndex()` which returns
`Long.MAX_VALUE` when no callback is pending; the old guard `if (floor > 0)` was therefore TRUE
and `removeIf(k < MAX_VALUE)` wiped the whole `appliedSeqByIndex` map — so a single-node
immediate-commit registration arriving right after the apply read the wrong (missing) per-index
seq. The fix gates the floor-prune on `floor != Long.MAX_VALUE` (prune only when callbacks are
pending) and relies on the 4096-entry hard cap otherwise. This is subtle and correctly reasoned;
it is exercised by `CommitOutcomeSeamTest.committedCarriesTheAppliedMutationSeqForThisIndex`
(which registers post-apply on a single-node leader and would read a wrong seq under the bug).
One-shot firing removes the map entry before invoking the callback (no double-fire);
`cancelCommitOutcome` (dispatched on the tick thread by the proposer's timeout path) releases an
abandoned entry; `fireSnapshotIndeterminate` drains snapshot-covered unrecorded indices.

**B.4 5s deadline is real milliseconds — YES.** `WRITE_COMMIT_TIMEOUT_MS = 5_000` is passed to
`raftProposer` and consumed as `f.get(writeCommitTimeoutMs, TimeUnit.MILLISECONDS)` on a
`CompletableFuture` — not a tick count, not routed through the RR-006-affected tick-config path.
The 150 ms accept budget is subsumed: a SINGLE marshalled tick task does `driver.propose` AND
the `whenCommitOutcome` registration, capturing `(index, term)` *inside* the task (so a slow
tick queue cannot lose the position and force a spurious Indeterminate). Honors review finding f
exactly.

**B.5 HTTP mapping table implemented exactly — YES.** `HttpApiServer.sendWriteResult` is an
exhaustive sealed-`switch` over `WriteResult`: Committed→200 `Committed: seq=S`; NotLeader→503 +
`X-Leader-Hint`; Lost→503 + `X-Leader-Hint`; Indeterminate→504; ValidationFailed→400;
Overloaded→429. No `default` arm — the compiler enforces exhaustiveness (the intended tripwire).
200 is returned on no path other than Committed.

**B.6 Blast radius — consumer table completed correctly.** `gates/smoke-multinode.sh`: the stale
"200 == local-append ACK (R-14)" comments are gone, replaced with commit-confirmed semantics;
the leader-probe and write `--max-time` raised 2/3 → 8 to tolerate the 5 s commit-wait; a
follower returns 503 promptly so `find_leader` still moves on. (Behavior note, documented:
`__leader_probe__` is now a really-committed side-effecting write on each find_leader call —
acceptable for a smoke test.) `gates/gate-1.sh`: the stale RR-004 caveat ("the suite asserts the
IMPLEMENTED pre-commit-ack behavior") is corrected to point at the new discriminating suites.
`ConfigWriteService`/`ProposeCommitResult`/`RaftProposer`/`HttpApiServer` handlers updated;
`ConfigWriteServiceTest` rewritten off the defect.

**B.7 Discriminating-suite re-run (second-agent fix verification) — GREEN.** Ran each targeted,
after `install -DskipTests` of the changed upstream modules and `clean` on the module under test
(per the known stale-artifact false-green trap):
- `CommitOutcomeSeamTest` (configd-consensus-core): **Tests run: 5, Failures: 0, 0.879 s**
- `RaftProposerCommitConfirmTest` (configd-server): **Tests run: 4, Failures: 0, 1.181 s**
- `AckEqualsCommitTest` (configd-testkit, 200 seeds × 3 fault shapes): **Tests run: 3,
  Failures: 0, 4.481 s**

### Required change (before final RESOLVED flip)

1. **(F-1) Add a test for the RR-029/W-1 owner-thread tripwire violation path.** Drive
   `ConfigStateMachine.apply` from a thread other than the one that bound the owner and assert
   the `InvariantChecker` throws in test/sim (and/or `onApplyOwnerThreadViolation` increments).
   The tripwire is the protection the ADR (§6) declares load-bearing for this fix; right now only
   its happy path (single-thread apply) is exercised, so a regression that broke the assertion
   would not be caught.

### Non-blocking notes

- **(F-4)** `AckEqualsCommitTest.java:157` has a redundant `(int)` cast (compiler warning) —
  cosmetic.
- **(F-5)** For A2 (next to modify `RaftNode` snapshot/recovery): `appliedSeqByIndex` is pruned
  only by pending-callback floor and the 4096 hard cap — **not** by log compaction. The seam's
  snapshot correctness (`decideCommitOutcome`'s `index <= snapshotIndex()` branch and
  `fireSnapshotIndeterminate`) depends on `log.lastApplied()`/`log.snapshotIndex()`/`entryAt`
  staying consistent across an install. A2 should re-run `CommitOutcomeSeamTest` +
  `RaftProposerCommitConfirmTest` after touching `handleInstallSnapshot`/compaction/recovery.
  No current defect — an interaction to keep green.

---

## §RR-010 — second-agent verification (`SimulationDeterminismTest`, `RaftSimulation.electionRandom`)

**Vacuity of the digest — NON-VACUOUS.** `runScenarioDigest` folds, per tick across all 5 nodes,
`(tick, role.ordinal, currentTerm, leaderId, lastIndex, commitIndex, lastApplied,
store.currentVersion)` into SHA-256 over 1500 ticks. Because the tick index is in the digest, an
election-timeout divergence shifts a role/term transition to a different tick → different digest;
the digest is genuinely sensitive to the election schedule (the exact RR-010 failure mode). The
second test (`distinctSeedsAreReplayableAndDiffer`) guards against a degenerate constant digest
with `assertNotEquals(seed2, seed7)` — so the digest provably varies with the seed while
replaying identically per seed. The pre-fix capture confirms it FAILED pre-fix (run#1 ≠ run#2),
and the only change in `c452aa1` is threading the seed, so the digest's per-tick role/term fold
is what catches the entropy.

**`electionRandom` decorrelation + single entropy source — CORRECT.** `electionRandom(nodeId)`
= `L64X128MixRandom` seeded from `mixSeed(seed, nodeId)` where `mixSeed = SplitMix64Finalizer(seed
+ 0x9E3779B97F4A7C15 * (nodeId+1))`. The `(nodeId+1)` multiplier gives each node a distinct,
well-mixed stream (no two nodes share an election sequence) while remaining a pure function of
`(seed, nodeId)`. `ClusterHarness` threads `sim.electionRandom(nodeId)` into every `RaftNode` at
construction — the master seed is the single source of simulated randomness on this path. The
`clock.currentTimeMillis()` calls are the **sim virtual clock**, not wall-clock. The three
`System.nanoTime()` self-seeds at `ConsistencyPropertyTests.java:352/600/1684` are the
`@RepeatedTest` failover tests (self-seeding by design) and are NOT in the determinism-test path.

**Pre-fix capture plausibility — PLAUSIBLE.** `rr-010-prefix-failure.txt` shows both tests
failing with divergent digests at a fixed seed (run#1 ≠ run#2), consistent with an
entropy-seeded election RNG; the diagnostic message names the exact root cause the fix addresses.

**Re-run — GREEN.** `./mvnw -pl configd-testkit -am test -Dtest=SimulationDeterminismTest
-Dsurefire.failIfNoSpecifiedTests=false` → **Tests run: 2, Failures: 0, 0.513 s.**

RR-010 is sound; updating the register row to RESOLVED.
