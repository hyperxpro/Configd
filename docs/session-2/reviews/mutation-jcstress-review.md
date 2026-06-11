# Review — mutation + jcstress + gap-round second-agent verification (read-only)

- **Reviewer:** review-architect (Session 2)
- **Date:** 2026-06-11
- **Rows:** RR-085, RR-086(persist-sync equivalent), RR-089, RR-091, RR-092, RR-011.
- **Method:** READ-ONLY — a full gate-2 run owned all Maven/PIT, so I verified by reading source,
  the on-disk PIT `mutations.xml` reports, the kill-list, and the jcstress sources. **Ran no Maven,
  PIT, jcstress, or gate scripts.** The live `target/pit-reports` was mid-wipe by the running gate-2,
  so I used the kill-list's cited authoritative copies in `/tmp/pit-s2/` (the same files the scores
  were computed from).

---

## Verdicts

| Row | Verdict |
|---|---|
| RR-085 (mutation kernel/module) | **APPROVE — stays RESOLVED. Kernel-72.8%-floor-70 disposition defensible.** |
| RR-089 (call-site closure) | **APPROVE — gap agent is RIGHT; call-sites ARE killable, NOT equivalent.** |
| RR-091 (vacuous named tests) | **APPROVE — de-vacuation real.** |
| RR-092 (config-store gaps) | **APPROVE — equivalents sound by code inspection.** |
| RR-011 (jcstress) | **APPROVE — self-test real, @Outcome non-permissive, CF-31/W-2 reclassification honest.** |

**Headline risk — wrongly-claimed equivalent:** none found. Every itemized equivalent I checked
against code + PIT is genuinely unobservable; the one near-miss (`setCommitIndex >→>=`) is correctly
KILLED while its look-alike (`setLastApplied >→>=`) is correctly EQUIVALENT — the agent's
discriminator is real. No vacuous test found.

---

## 0. PIT report integrity (the foundation)

The kill-list's measurement-integrity story holds up against the on-disk reports:
- `/tmp/pit-s2/final2-module-mutations.xml`: **585 KILLED + 4 TIMED_OUT = 589/806 = 73.1%**, 177
  SURVIVED, 40 NO_COVERAGE, **RUN_ERROR=0**. Matches the headline.
- `/tmp/pit-s2/final2-kernel-mutations.xml`: **528 KILLED + 4 TIMED_OUT = 532/731 = 72.8%**,
  RUN_ERROR=0. Matches.
- `/tmp/pit-s2/final-module-mutations.xml`: **346 RUN_ERROR** — this IS the contaminated run the
  kill-list says inflated an earlier "80%" figure, correctly discarded.
- `clean-module` (68%, NO_COVERAGE=94) is the pre-record-round; the record-codec round
  (`MessageRecordCodecTest`) lifts NO_COVERAGE 94→40 and module-wide 68→73.1%.

So the discipline "verify RUN_ERROR==0 before trusting a PIT score" is real and was applied. The
73.1%/72.8% numbers are from clean runs.

## 1. Mutation-gap test quality — real discriminators

I read the highest-value gap tests and cross-checked their kills in `final2-module-mutations.xml`:
- **`RaftLogUnitTest`** — the `setCommitIndex >→>=` re-clamp discriminator is real (see §3). compact
  sync L490/494/516/520/521 all KILLED by RaftLogUnitTest/WalSyncCrashTest.
- **`RaftNodeVoteAndSnapshotUnitTest` / `RaftNodeReplicationUnitTest`** — handleRequestVote
  L1314/1321/1333/1336 KILLED; nextIndex walk-back L1290 KILLED.
- Named-survivor kills confirmed at the cited lines: §5.4.2 guard L1739←CertificationTest,
  vote-persist L1330←VotePersistenceCrashTest, clear L1445←ReadIndexStepDownClearTest, compact-sync
  L521←WalSyncCrashTest. Each test asserts the actual consequence its mutant controls (a prior-term
  entry stays uncommitted; a recovered node refuses a double-vote; an old read isn't served
  post-re-election; a deleted WAL prefix stays deleted across crash) — not coverage-only.

## 2 / RR-089. Call-site closure — ADJUDICATED: the gap agent is RIGHT

The earlier B3 framing called the production `invariantChecker.check(...)` call-site removals
"equivalent." The gap agent says "actually killable." **The gap agent is correct, and it is a SOUND
kill, not a tautology dressed up.** `InvariantCallSiteTest`'s `ObservingChecker`:
- is the REAL production `InvariantChecker`, injected via the real 7-arg `RaftNode` ctor — **not** a
  synthetic seam like `AssertionTwinFiringTest`'s `fireInNodeTwinForTest`;
- is driven through REAL protocol paths: `singleNodeLeader` runs the actual election →
  `becomeLeader`; `node.propose(...)` commits + applies inline → the real `applyCommitted`;
  `follower.handleMessage(AppendEntriesRequest)` → the real `handleAppendEntries`;
  `leader.proposeConfigChange(...)` → the real `proposeConfigChange`;
- asserts `observed(name)` = "the production `check(name,...)` call FIRED on this real path."

A `VoidMethodCall` removal of the production call ⇒ the call never fires ⇒ `observed(name)` false ⇒
the test FAILS. This is the legitimate way to kill a VoidMethodCall removal on a defense-in-depth
assertion whose *condition* is unfalsifiable: the observable is "the call happened," a genuine
behavioral property, distinct from "the condition was false." **Confirmed in
`final2-module-mutations.xml`:** the 8 production call-site removals (election_safety L1567,
leader_completeness L1573, version_monotonicity L1802, state_machine_safety L1808, log_matching
L1225, single_server L924, no_op_before_reconfig L929, reconfig_safety L937) are all **KILLED by
InvariantCallSiteTest**; ctor durable_prefix L257 KILLED by SnapshotCrashRecoveryTest; only L1782
`durable_prefix_no_gap` remains NO_COVERAGE (matching "only 1 remains"). RR-089 is correctly closed
harder; B3's "equivalent" is superseded.

## 3. The itemized EQUIVALENTS — all sound, no disguised survivor

Each checked against the code AND the on-disk PIT status:

| Mutant | PIT status | Equivalence sound? |
|---|---|---|
| `setLastApplied >→>=` (L428) | **SURVIVED** | YES — body is `lastApplied = index`; at `index==lastApplied` it self-assigns the same value (no-op). |
| `setCommitIndex >→>=` (L419) — the near-miss | **KILLED** | (correctly NOT equivalent) — body re-runs `Math.min(newCommitIndex, lastIndex())`, which after a truncate clamps commitIndex DOWN, observable. The agent wrote the discriminator (RaftLogUnitTest); it kills. |
| `appendEntries prevLogIndex >0→>=0` (L356) | **SURVIVED** | YES — `termAt(0)==0` sentinel; at index 0 a valid prevLogTerm is 0, so `existingTerm != prevLogTerm` is false → proceeds identically. |
| `termAt L223 <→<=` | **SURVIVED** (1 of 2 ConditionalsBoundary at L223; the `>lastIndex` one is KILLED) | YES — L222 `if (index==snapshotIndex) return snapshotTerm` already handles the boundary, so the `<` flip is unreachable for `index==snapshotIndex`. |
| `confirmAllLeadership` lambda L165 EQUAL_ELSE | **SURVIVED** | YES — `PendingRead.confirmed()` on an already-confirmed read returns a record equal in every field; dropping the guard is invisible. |
| RaftLog `<init>` legacy-fallback L143/144/145 | **SURVIVED** | YES (with a precision note) — L155-158 cross-validate recomputes `snapshotIndex = firstEntry.index()-1` AFTER the legacy block, overwriting any inference when entries exist; the no-entries case can't reach these lines (`else if (!entries.isEmpty())`). **Precision note:** the L145 *snapshotTerm* guard is reachable only via a malformed 8-15-byte `snapMeta` that the production persist path never writes (it writes 16 bytes) — so "unreachable in practice" is a more exact characterization than "overwritten," but it is genuinely unobservable either way. Not a killable-by-normal-means survivor. |

**No wrongly-claimed equivalent caught.** The headline near-miss (`setCommitIndex`) the agent flagged
is genuinely killed, and its look-alike (`setLastApplied`) is genuinely equivalent — the
discrimination is correct and is the strongest evidence the equivalence analysis is careful, not
gamed.

## 4 / RR-011. jcstress — real detector, non-permissive outcomes, honest reclassification

- **Self-test is a REAL test-the-tester.** `HarnessSelfTest.KnownRacyCounter` (two actors `++x` on a
  shared plain int) marks the lost-update `(1,1)` as `Expect.FORBIDDEN`, so observing it (which
  jcstress does, 2-26% across forks per the doc) yields `[FAILED]` — proving the detector fires.
  `KnownSafeDisjoint` forbids any non-`(1,1)` and stays clean. The detector is bounded from both
  sides — a detector seen firing on a known race.
- **@Outcome annotations encode the real invariant, not permissively.**
  `FanOutBufferReadSinceTest.classify()` collapses every torn/dup/skip/null/seq≤cursor to code `9`,
  and every occupancy variant (PartiallyFull/ExactlyFullWrap/LappedCursorBelowWindow) marks `id="9"`
  `Expect.FORBIDDEN` with GAP/clean-run/empty ACCEPTABLE. The classifier's range is exactly
  `{0,1,2,9}` and the @Outcome list covers all four, so no torn read can escape classification. The
  doc's "multiple distinct ACCEPTABLE outcomes per test" (e.g. ExactlyFullWrap observing BOTH GAP and
  clean-run) proves the race window is genuinely sampled (non-vacuous), not a test that never hits
  the interleaving.
- **CF-31/W-2 "safe-by-construction" reclassification is SOUND and honest.** `VersionedValue` is
  immutable + defensively-copied and published via the volatile `currentSnapshot` swap
  (happens-before), so the aliased `byte[]` a reader observes is frozen; `AliasedArrayNoTear` ran
  clean (decoded values were always genuinely-published versions, the never-published `99` never
  appeared). CF-31 is correctly recorded as an encapsulation smell (a caller *could* mutate the
  returned array — a defensive-copy hardening question), NOT a concurrency race. W-2
  (`ConfigStateMachine` non-volatile getters) is honestly flagged as NOT reachable through the
  read-path structures this module owns → **remains an OPEN residual on RR-029**, not silently
  closed. Both are documented as "not demonstrated to be races on the read path" — the honest call.

## 5 / §4.1 disposition — kernel 72.8% (floor 70) is DEFENSIBLE

- Module-wide **73.1% MEETS the ≥70 §4.1 target with margin** — the headline deliverable.
- The kernel **72.8%** (vs 80 aspiration) residual is HONESTLY itemized, with no gameable killable
  cluster passed off as residual. I checked the kernel survivor distribution
  (`final2-kernel-mutations.xml`): it is **DIFFUSE** — 11 in RaftLog.<init> (the verified-equivalent
  legacy/cross-validate region), then 8/8/6/6/6/5… spread across distinct
  snapshot-install/recompute-config/deserialize-config/configAtIndex/election methods. There is **no
  single 30-40-mutant killable cluster** sitting untargeted. The residual splits into: (a)
  provably-equivalent (verified sound in §3); (b) the commit-outcome snapshot-INDETERMINATE machinery
  (~25 NO_COVERAGE+SURVIVED) honestly labeled **killable-but-high-cost RESIDUAL, NOT equivalent**
  (verified: the kill-list does not claim these equivalent); (c) diffuse snapshot/reconfig boundary
  survivors. The lead's "accept 72.8% kernel with floor 70 + documented residual" is **defensible** —
  the gap to 80 is dominated by genuine equivalents plus honestly-disclosed high-cost machinery, not
  gamed, and the enforced floor prevents regression.

## Notes

- `RR-092` was verified by code+test inspection: config-store is not in the current gate-2 mutation
  `targetClasses` (gate-2 mutates consensus-core + distribution-service), so no fresh config-store
  `mutations.xml` was on disk. The two equivalents are sound by inspection (`unknownTail` `position(+0)`
  no-op; the `0xC0FD7A11` magic's sign bit makes an 8-byte misroute a negative long the carry-forward
  guard rejects). The guard/trailer killing tests exist and assert the consequence.
- distribution-service control-plane **190/240 = 79%** (FanOutBuffer 91%) confirmed from its on-disk
  report — above the ≥65 floor; the RR-001/RR-088 shelfware is correctly excluded from the split.

All rows stay RESOLVED. No wrongly-claimed equivalent, no vacuous test.
