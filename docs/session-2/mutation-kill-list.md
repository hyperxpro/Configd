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
- Mutant: "removed call to DurableRaftState::vote" at the grant branch.
- Killing test: (pending) crash-restart between votes using CrashStorage.
- Status: (in progress)

### 3. ReadIndexState::clear on step-down (~becomeFollower) + nextIndex walk-back
- Status: (in progress)

## RR-086 — consensus-path `Storage::sync` removals (crash durability)
- (in progress)

## RR-089 — `InvariantChecker::check` VoidMethodCall removals
- (pending PIT confirmation of which AssertionTwinFiringTest kills land)

## RR-092 — config-store targeted assertion gaps
- (in progress)

---

## Official PIT scores (final per-module run)
- (pending — run after tlc2 vanishes; see SCORES section)
