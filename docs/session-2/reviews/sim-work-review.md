# Review — adversarial-sim + seed-sweep second-agent verification (RR-012, RR-027)

- **Reviewer:** review-architect (Session 2)
- **Date:** 2026-06-11
- **Scope:** commits `1fae71a` (de-vacuate seed sweep + continuous checker), `cb6142a` (adversarial
  fault layer + clock skew + crash seam + linz history), `841e040` (507-seed gate set +
  crash-restart test + ddmin minimizer), `452aa2f` (10k sweep), `f4b3752` (register).
- **Central question:** can the new checkers ACTUALLY fail, and does every seed REALLY end with the
  invariant set asserted? (The RR-012 / RR-085/091 vacuity history.)

---

## Verdicts

- **RR-012 (vacuous seed sweep): APPROVE — stays RESOLVED.** The 3 silent returns are gone; the
  continuous checker is genuinely non-vacuous (proven by the test-the-tester, which I re-ran:
  reached=caught), runs every tick of every seed in BOTH SeedSweepTest and AdversarialSim, and the
  80%-real-rate sweep guard backstops the coverage claim. One presentational note: the register's
  "80,000,000 safety assertions" should be reworded to coverage *structure*, not a headline number
  (see Finding 4) — non-blocking.
- **RR-027 (fault injection): RESOLVED-with-note is CORRECT — stays RESOLVED-with-note.** The
  finding's substance ("simulation cannot inject duplication / clock-skew / disk-faults /
  crash-restart") is now demonstrably false: all four are injectable, wired, and replayable, and
  crash-restart is proven end-to-end. The `@Buggify` primitive (0 call sites) was correctly
  superseded; the owed ADR-0007 amendment must be assigned (see Finding 7).

No vacuity found. No safety violations found.

---

## 1. SimInvariants.checkAll() — can it fail, and is it wired every tick?

I read every check. None is a tautology, a swallow, or a flag-gated no-op:
- **`checkSingleLeaderPerTerm`** — throws if two LEADERs share a term (real Election-Safety RED).
- **`checkVersionMonotonicityPerObserver`** — throws if a node's `currentVersion()` regresses
  tick-to-tick.
- **`checkLogMatchingAcrossReplicas`** — over the common committed prefix
  `[max(snapshotIndex)+1 .. min(commitIndex)]`, throws on a term mismatch at the same index.
- **`checkStateMachineSafetyAcrossReplicas`** — records `idx@term` identity, throws on divergence
  (across replicas or across time).

The only "vacuous-on-quiet-seeds" shape is the standard one: the two cross-node committed-prefix
loops do nothing when nothing has committed (`commitIndex==0` ⇒ empty range). That is **correct**
(there is nothing to check) and is bounded by the RR-012 minimum-activity machinery (Finding 2),
not a hidden vacuity. There is **no try/catch** around `checkAll()` anywhere, and no predicate is
gated on a flag.

**Per-tick wiring verified in BOTH harnesses:**
- `SeedSweepTest` — `checkAll()` is called immediately after `cluster.tick()` in every driver loop
  (`electWhileChecking`, `proposeAndCommitWhileChecking`, `runTicksWhileChecking`,
  `awaitStableLeaderWhileChecking`) and in `electionSafety`. There is no path that ticks without
  checking; the former silent returns now `return activity` *after* having checked every prior tick.
- `AdversarialSim.tick()` — `invariants.checkAll()` is the unconditional last statement of every
  tick (`:185`), and `run()` calls `tick()` for every scheduled tick. No guard, no swallow.

## 2. Minimum-activity predicates (the RR-012 fix) — assert or just count?

The replacement paths **record** activity per seed (`Activity`) and the **sweep-level**
`sweepActivityIsNotVacuous` **asserts** it: it runs a fixed 200-seed batch and requires
`reachedRate >= 0.80` (≥80% of seeds elect + commit + fail over and hit the durability assertion).
An all-stall regression (cluster can no longer commit/fail-over) drives this toward 0 and FAILS
loudly with the honest counts — the assertion "20,000 green" never made.

**Honest nuance (stated, not a defect):** the *per-seed* `commitSurvivesLeaderFailure` (which runs
10,000×) does NOT itself assert it reached the property — an individual stalled seed returns without
asserting (a recorded stall). So vacuity is guarded at the **sweep level** (the 200-seed 80% floor)
and by the fact that *safety is checked on every tick of every seed regardless*. The quiet-seed
class is therefore bounded (≤20% by the guard) and asserted — exactly the right structure. The
register text should make clear the 80% guard runs on a 200-seed batch, not the full 10k (it does).

## 3. Test-the-tester — re-run, and disabled-by-default proof

Re-ran `-Dconfigd.testTheTester=true -Dconfigd.testTheTester.batch=30`:
**reached=30 caught=30** — every seed that reached the injection point was caught. The injection
rewrites a follower's committed entry to a divergent term (`origTerm + 999`) — the exact RR-085
§5.4.2 mutant symptom — and asserts `checkAll()` throws; a vacuous checker would make `reached !=
caught` and FAIL. **Genuinely off by default:** `@EnabledIfSystemProperty(named =
"configd.testTheTester", matches = "true")`; a normal run shows **Skipped: 1** (verified). The
injection is sim-layer only (`RaftLog` rewrite), never production code. The capture
(`rr-012-test-the-tester.txt`) is internally consistent with my re-run.

## 4. The assertion arithmetic — honest, but reword the headline

`10,000 seeds × 2,000 ticks × 4 cross-node checks = 80,000,000` is **arithmetically correct** for
the `electionSafety` sweep (2000 ticks/seed, `checkAll()` once/tick, 4 predicates). **But** the
charter's warning applies: RR-012 was about replacing a vanity *execution count* — the fix must not
mint a new vanity *assertion count*. The load-bearing claim is the **structure**: the full
safety-invariant set is evaluated on *every tick of every seed* (4 cross-node + 8 in-node
predicates), the non-vacuity is proven by the test-the-tester, and the coverage is guarded by the
80% real-rate floor. **Recommendation (non-blocking):** the RR-012 register cell should present
"every tick × every seed × N invariants, test-the-tester-proven, 80%-rate-guarded" as the headline
and demote "80,000,000" to an illustrative parenthetical (it is a derived count, not an
achievement). I have not rewritten the implementer's substance; I flag it in the verification cell.

## 5. Determinism + gate set — re-runs

- `SimulationDeterminismTest` **2/2** (0.56 s).
- `AdversarialScheduleDeterminismTest` **3/3** (0.08 s).
- `AdversarialGateSeedSweepTest` (507 gate seeds) **1/1 green, 4.996 s.**
- Supporting: `AdversarialSimTest` 4/5 (the 5th is the nightly 10k, gated/skipped),
  `ScheduleMinimizerTest` 1/1, `OpHistoryTest` 1/1, `AdversarialCrashRecoveryTest` 1/1 (1.6 s —
  proves the test-jar crash-restart reuse end-to-end), `SeedSweepTest` @500 seeds **1001/1001,
  33.5 s** (incl. the vacuity guard passing).

## 6. The 7 liveness stalls — replayable, characterized, and a register-row draft

I reproduced the 10k sweep's stall set deterministically. **Exactly 7 of 10,000**, matching the
capture, at seeds **452, 869, 4740, 5100, 5159, 5500, 8319**. Every stall has the identical
signature: `leader=false, terms=0, commit=false, faults=8, crashes=0` — i.e. **no leader ever
formed** under the injected schedule, with all 8 scheduled faults fired.

I replayed seed 452 twice (replay-identical: both stall) and dumped its schedule. The cause is a
hostile-but-legal network schedule that **never restores connectivity**: a `DROP_WINDOW_BEGIN` at
tick 154 with `param=0.861` sets a **~44 % cluster-wide drop rate** (`0.1 + 0.4·0.861`) **with no
paired `DROP_WINDOW_END`**, so the drop persists for the rest of the run, layered with three
`PARTITION_ADD`s (one asymmetric) and delay spikes. Under sustained ~44 % loss plus partitions, a
5-node cluster legitimately cannot complete a PreVote→vote round within budget, so no leader
emerges. **This is an expected partition/drop-schedule artifact, not a suspicious stall:** Raft is
correctly choosing safety over liveness (no split-brain), and `safetyViolations=0` across all 7.
One minor schedule-generator observation (non-blocking): a `DROP_WINDOW_BEGIN` without a guaranteed
`DROP_WINDOW_END`/`HEAL_ALL` before end-of-run makes "no progress" the *expected* outcome for that
seed class — fine for an adversarial sweep, but if a future sweep wants every seed to be
*eventually* live, pair each drop/partition window with a scheduled heal a bounded time later.

**Proposed register row (for the lead to add in the final pass):**

> | RR-0NN | Liveness: 7/10,000 adversarial sweep seeds (452, 869, 4740, 5100, 5159, 5500, 8319)
> elect no leader within the 1500-tick budget — each runs a schedule with a sustained ~44 % drop
> window and/or partitions that is never healed before end-of-run, so the cluster correctly makes no
> progress (safety preserved, 0 violations). | **P3** | Verification / liveness (expected artifact)
> | `AdversarialSimTest#nightlyAdversarialSweep` (`sweep-10k-run.txt`); replay by seed:
> `new AdversarialSim(452,5,1500).run()` ⇒ `leaderElected=false`, deterministic | — | OPEN |
> Characterized as an expected never-healed-fault-schedule artifact, not a bug. Optional hardening:
> pair every drop/partition window with a scheduled heal so each seed is eventually-live; then any
> residual stall is genuinely suspicious. No safety implication. |

**Severity justification — P3:** these are *recorded, expected* liveness artifacts of an
intentionally hostile, never-healed schedule, with zero safety impact; they require no fix, only
registration (charter: liveness findings registered, not hidden). They are not P2 because nothing
is wrong — a partitioned/heavily-dropped cluster *should* not elect.

## 7. RR-027 judgment

The finding's substance (CF-16/HF-5/CM-067) is "the simulation **cannot** inject message
duplication, per-node clock skew, disk faults, or crash/restart-with-state-loss." That is now
**false** — I verified each is injectable AND wired:
- **Duplication** — `AdversarialNetwork.send` re-enqueues a seed-probability duplicate at
  `deliverAt + small delay` (Raft RPCs idempotent).
- **Per-node clock skew** — `AdversarialSim` gives each node a `SkewedClock` with bounded ±50 ms
  offset on the state-machine timestamp surface (RaftNode is tick-driven, so skew correctly does not
  perturb elections — a sound separation).
- **Disk faults + crash/restart-with-state-loss** — RR-003 `CrashStorage` via the consensus-core
  test-jar; `AdversarialCrashRecoveryTest` (re-run green) proves durable-prefix survival across a
  seed-derived crash among unsynced writes.

All are replayable by seed alone (verified: distinct seeds → distinct schedules; same seed →
identical run). **`@Buggify` / `BuggifyRuntime` still has 0 call sites outside `configd-common`**
(grep confirms) — but that primitive was correctly rejected (global static mutable
`enabledPoints`/`random`, not sim-instance-scoped; two sims in one JVM would share state). The
capability gap the finding describes is closed by a *better* mechanism.

**Decision: keep RESOLVED-with-note.** The load-bearing substance is the fault-class capability, not
the specific `@Buggify` primitive, so "0 @Buggify call sites" is not itself the defect — it is a
stale artifact of a superseded design. I do **not** re-scope to OPEN. **However the ADR-0007
amendment is genuinely owed and currently unowned:** ADR-0007 still advertises "~1000 @Buggify
injection points" as the mechanism, which is now superseded by the centralized seed-derived
`AdversarialSchedule`/`AdversarialNetwork`/`AdversarialSim` layer. **Recommended owner:** the
verification/simulation owner who authored ADR-0007 (route via the lead); the amendment is a
~1-paragraph "Superseded-by" note on ADR-0007 pointing at `adversarial-sim-design.md` and this
commit set — it is a doc task, not a reason to reopen RR-027. The register row should name that
owner rather than leaving the amendment unassigned.

## 8. test-jar pattern — clever, with bounded hazards

`io.configd.raft.CrashStorageAdapter` lives in **configd-testkit's test sources** in package
`io.configd.raft` so it can see the package-private `CrashStorage` published by consensus-core's
**test-jar**. The dependency is acyclic (`testkit(test) → consensus-core(test-jar)`; the reverse
would cycle, correctly avoided), and the failure mode is loud (`NoClassDefFoundError` at
`CrashStorageAdapter.create()` if the test-jar is absent). Hazards (all bounded, note for the team):
1. **Targeted-run / reactor-order:** a `-pl configd-testkit test` run requires consensus-core's
   test-jar already `install`ed in `~/.m2` — the project's known stale-artifact trap. A full-reactor
   build orders it correctly. (I `install`ed consensus-core before my targeted runs for this reason.)
2. **Split package across modules:** `io.configd.raft` now appears in two modules' test source roots
   (consensus-core/test and testkit/test). Legal on the **classpath** (no `module-info`), but it is
   an illegal split package under JPMS — a latent migration hazard if the project ever modularizes.
3. **IDE:** IntelliJ/Eclipse may warn "package exists in two modules" and resolve `CrashStorage`
   navigation to the source module rather than the test-jar; usually benign, occasionally confuses
   "find usages." Worth a one-line comment in the testkit README so a future contributor isn't
   surprised.
None of these is a correctness defect; the pattern is the right call given "do not duplicate the
RR-003 fixtures, zero edits to RR-003 files."

---

## Re-run evidence (summary)

| Test | Result |
|---|---|
| `SimulationDeterminismTest` | 2/2, 0.56 s |
| `AdversarialScheduleDeterminismTest` | 3/3, 0.08 s |
| `AdversarialGateSeedSweepTest` (507) | 1/1, 4.996 s |
| `AdversarialSimTest` (+Minimizer/OpHistory) | 4/5 (nightly gated) + 1/1 + 1/1 |
| `AdversarialCrashRecoveryTest` (test-jar reuse) | 1/1, 1.6 s |
| `SeedSweepTest` @500 seeds (incl. vacuity guard) | 1001/1001, 33.5 s |
| `SeedSweepTestTheTesterTest` (batch 30) | reached=30 caught=30; Skipped:1 by default |
| 10k stall reproduction | 7/10000 stalls at the exact capture seeds; seed 452 replay-identical |

RR-012 stays RESOLVED (reword the 80M headline — non-blocking). RR-027 stays RESOLVED-with-note
(assign the ADR-0007 amendment owner). The 7 liveness stalls are an expected never-healed-schedule
artifact (proposed P3 row above) with zero safety impact.
