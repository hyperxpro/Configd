# C4 Contract-QA Audit — row-by-row against the contract-test-map

> **Auditor:** contract-qa-engineer (Session 3). **Date:** 2026-06-12. **HEAD:** `3e8ec7d`.
> **Scope:** component C4 as landed (`3e8ec7d`: the slow-consumer policy —
> `SlowConsumerGovernor` live in `FanOutServer` per-identity over C1's signals; the two
> shelfware orphans `SlowConsumerPolicy` and `CatchUpService` DELETED in the same change;
> as-built note `docs/session-3/design/c4-slow-consumer-design-note.md`, §9 row claims)
> audited against `docs/session-3/contract-test-map.md`. Plus the deleted-class rot check
> the deletions force on previously-flipped rows (CT-31's trailing note; the map footer)
> and the CT-38 owed-list movement.
>
> **Method (evidence discipline):** every claim below was verified by (1) **reading the
> named test bodies** in the working tree in full (`SlowConsumerWarningTransitionTest`,
> `SlowConsumerQuarantineTransitionTest`, `QuarantineReBootstrapTest`,
> `RepeatQuarantineUnhealthyTest`, `SlowConsumerPolicyConfigTest`, `RecordingPolicyProbe`,
> `FanOutServerQuarantineTest`, `SlowConsumerStateMachineWalkTest`,
> `RegistryFanOutSessionMetricsTest`'s C4 additions), (2) reading the production sources
> where an assertion's meaning depends on them (`SlowConsumerGovernor` in full — the
> window/cooldown boundary semantics, the reason-weighted ladders, the skip-distressed
> eviction; `SlowConsumerPolicyConfig`; the `FanOutServer` C4 wiring diff — admission at
> SUBSCRIBE on the BOUND identity, the demotion-listener teardown, the session-loop
> governor feed and its `governorEvalCadenceMs`; `RegistryFanOutSessionMetrics`; the
> `C1StreamDriver` opt-in governor plumbing — admission on every (re)subscribe, per-tick
> refusal retries, the dead-sink policy kick, the on-wire `EdgeStream.ErrorClose` mapped
> onto the REAL `EdgeClientCore.onFrame` reaction in `EdgeActor`), (3) the architecture §7
> verbatim table (:285-291) + §11 (:419-420), charter §4 C4 (:133-136) and the gate-3 walk
> step (:160), the c2-c5-design-screen §C4 conditions (C4-1..C4-3), and the design note's
> §6 deviations / §7 residuals, and (4) **fresh surefire**: the lead's full-reactor run
> (03:20–03:21, captured by the commit at 03:22:35; tree confirmed clean at audit start
> AND at audit close) plus my own targeted re-run.
>
> **2-vCPU discipline:** `pgrep -f "[o]rg.apache.maven"` CLEAR before my one targeted
> run (`-pl configd-distribution-service,configd-server,configd-testkit`, 9 suites,
> `-Dsurefire.failIfNoSpecifiedTests=false`). No full reactor, no PIT (the commit's PIT
> evidence — governor 100%, 94/94 — taken from the commit record, not re-run).

## Surefire evidence snapshot (all green; lead's run 03:20–03:21, my re-run 03:30)

| Suite | Tests | Failures |
|---|---|---|
| `SlowConsumerWarningTransitionTest` (distribution-service) | 8 | 0 |
| `SlowConsumerQuarantineTransitionTest` (distribution-service) | 8 | 0 |
| `QuarantineReBootstrapTest` (distribution-service) | 9 | 0 |
| `RepeatQuarantineUnhealthyTest` (distribution-service) | 6 | 0 |
| `SlowConsumerPolicyConfigTest` (distribution-service) | 2 | 0 |
| `FanOutServerQuarantineTest` (configd-server, PROCESS) | 2 | 0 |
| `RegistryFanOutSessionMetricsTest` (configd-server, GATE) | 4 | 0 |
| `SlowConsumerStateMachineWalkTest` (testkit, SIM, seed 31) | 3 | 0 |
| `EdgeSeedCompatTest` (gate-path byte-identity preservation) | 1 | 0 |

Deleted-class verification: `SlowConsumerPolicy.java` and `CatchUpService.java` are gone
from the tree; a repo-wide grep finds only inert mentions (the pom's RR-088 comment, the
`ConfigdServerTest:254` replacement comment, the governor's supersession javadoc) — no
live code or test references remain. The pom's two PIT `excludedClasses` entries are
removed (the exclusion debt died with the orphans).

## Row-by-row findings

### CT-27 (§7 :288 warn transition — log + metric) — UNIMPLEMENTED → **PASSING** ✅ FLIPPED

The orphan indictment in the old row is fully answered: the policy is live at runtime and
the clause's two observables (log + metric) are pinned at every level they exist at.
Unit (8, read in full): the **exact window edge the code implements** is pinned —
elapsed 9_999 ms still HEALTHY, elapsed == 10_000 ms (`>=` queueWarnWindowMs) SLOW — the
inclusive reading, consistent with the note's deviation 1 across the ladders (§7's literal
`>` rides the superseded credit table into the doc-pass ledger, below). Non-vacuity is
genuinely hard here: the transition fires ONCE (re-evaluate while SLOW does not re-fire —
edge, not level); a 9 s excursion re-arms from scratch and the second excursion's own
window is honored to the millisecond; a repeated above-LEVEL signal keeps the ORIGINAL
anchor **and** is itself a promotion point (the promotion provably does not depend on the
`evaluate()` cadence); `warnSinceMillis == 0` is a valid anchor (sentinel-vs-epoch-zero,
a classic off-by-sentinel killed); ack-progress drain returns SLOW→HEALTHY with reason +
gauges; an untracked identity costs no record. SIM: the walk's leg 1 drives the REAL
governor through the production `FanOutSessionCore` from a deliberately-lagging edge —
`slow_transitions == 1` and the leg's POSITION in the recorded order asserted (see CT-28).
GATE: `edge_fanout_slow_transitions_total` eagerly registered, exported name + movement
pinned. The structured log is not just claimed — the `edge_fanout_consumer_transition`
INFO lines were observed in my run's output with the full evidence fields. ADR-0034
handoff item 5 (GAP-rate signal) is discharged by the C4-2 separate GAP ladder fed from
the same demotion-listener seam. Honest nuance recorded in the row: no wire test elapses
the warn window on a live `FanOutServer` (the wire test's injected clock is static in its
demotion phase); SLOW has no wire surface by design (still streaming), the server's feed
loop is wire-exercised via the ack-progress resolve, and the pressure-edge + `≤1 Hz`
evaluate wiring was verified at source (one long comparison per busy iteration — hot-path
law honored).

### CT-28 (§7 :289 disconnect + quarantine) — UNIMPLEMENTED → **PASSING** ✅ FLIPPED

The strongest of the four rows. Unit (8): `demoteLimit` distress demotions in the sliding
window quarantine with `edge_fanout_quarantines_total` exactly once and the structured
event carrying the CURSOR EVIDENCE the row demanded (cursor, lastAckedSeq, BOTH window
counts at trip time — the transition is emitted BEFORE the ladders clear, verified at
source, so the counts that tripped the limit are the counts on the event). The screen
C4-2 ruling is pinned both ways: 4 GAPs past `demoteLimit` do NOT quarantine and the edge
returns HEALTHY on ack progress, while the 5th GAP at the test-scaled backstop trips
`REASON_GAP_DEMOTE_LIMIT`; the mixed-reason matrix (2+2 then the 3rd distress) proves the
ladders are genuinely separate, not just differently-limited. The window edge is pinned
exactly as implemented: a demotion exactly `demoteWindowMs` old STILL counts (prune is
strictly-older-only — inclusive). Post-quarantine stragglers are inert (no double-count,
no spurious transition — the dying-session race defused). PROCESS (2, real sockets,
injected clock, zero sleeps): deterministic 2-frame-queue overflows; the wire-level sync
(waiting out the first demotion snapshot before the second burst) shows the author
understood the collapse hazard — without it no second demotion could occur and the test
would hang red, not pass vacuously; the limit-tripping demotion ends the connection; the
second test proves admission keys on the IDENTITY (another edge admitted while the first
is quarantined). Honest residual, carried from design §7 into the row: the demotion-phase
close tolerates a torn bye (pre-existing writer race — the clean `ERROR_CLOSE` code 8 is
strictly asserted on the traffic-free refusal leg, and the sim walk delivers code 8
through the REAL `EdgeClientCore` fatal arm), so the clean-close evidence is not
overstated anywhere.

### CT-29 (§7 :290 quarantined must re-bootstrap) — UNIMPLEMENTED → **PASSING** ✅ FLIPPED

The row's own pinned demand — "a quarantined consumer is *forced* through it (cannot
resume tailing with its old cursor)" — is proven at the wire, which is what makes this
flip easy: after `clock.advance(60_001)` (the injected-clock cooldown elapse; no sleeps
anywhere in the suite) the readmitted SUBSCRIBE carries a **bogus-high resume cursor
(999_999) and still gets `SUBSCRIBE_OK(SNAPSHOT_FIRST)`** — the server demonstrably
rebinds the cursor to 0 and C3's `decideMode` cursor-0 rule (the RR-100 rule; reuse, not
duplication — verified in the `FanOutServer` admission rewrite at source) forces the
snapshot re-bootstrap; the head snapshot lands (snapshotSeq pinned to the published head),
and the CURSOR_ACK resolves CATCHUP→HEALTHY **through the live server's session-loop
governor feed** (the one place the server→governor ack plumbing is asserted at the wire).
Before that, the refusal half: every SUBSCRIBE during the cooldown refused with code 8 +
a diagnostic naming the refusal and the remaining cooldown, EVERY refusal counted
(C4-3's flapping-edge observability — boundary exact at unit level: refused at
cooldown−1 ms, readmitted at exactly cooldown). Unit non-vacuity controls are real: the
clean-slate test uses a cooldown SHORTER than the demote window so still-age-eligible
pre-quarantine demotions would re-trip if the quarantine merely relied on pruning — it
pins the CLEAR, not the window; refusals never mutate state; operator reset is proven
ADDITIONAL (full amnesty + gauge release + tracked-record drop), never required. SIM: the
walk's refusals ride the edge's REAL reconnect loop (code 8 → core fatal arm → directive →
per-tick driver resubscribe refused at admission, observably counted), then the cooldown
exit readmits and the edge converges on a post-quarantine commit.

### CT-30 (§7 :291 repeat-quarantine → unhealthy) — UNIMPLEMENTED → **PARTIAL(unit)** ⚠ upgraded, deliberately NOT flipped to PASSING

What exists is exactly what the old row demanded **in kind**: a clock-driven (explicit
`nowMillis`, zero sleeps) suite pinning the escalation arithmetic — the 3rd quarantine
within the sliding 1 h window escalates to UNHEALTHY (`edge_fanout_unhealthy_total`
exactly once, `REASON_REPEAT_QUARANTINE`, `quarantinesInWindow=3` on the event, state
gauge), a stale quarantine does NOT count (sliding window proven), refusal through the
1 h unhealthy cooldown is counted and boundary-exact (HOUR−1 refused / HOUR readmitted),
and the C4-3 anti-permanent-lockout is pinned: the cooldown ALONE auto-readmits with
snapshot-first forced and a clean ladder (operator reset additional). The bounded-map
test is a real adversarial check: a quarantined identity at the access-order head
survives a healthy flood while the bound is still ENFORCED past it (skip-distressed
eviction — both halves asserted). **Why not PASSING:** the row's level is PROCESS+GATE
and charter §4 C4 demands the full state-machine walk in the simulator — the walk runs
ONE quarantine cycle and **never reaches UNHEALTHY**, and no sim/wire test drives the
escalation through a live server: `FanOutServer.onDemotionEvent`'s UNHEALTHY teardown arm
(the `|| UNHEALTHY` branch) and the unhealthy-cooldown refusal at the wire are
structurally shared with the process-proven QUARANTINED path (one boolean-or; wire code 8
shared by design — note deviation 5) but **unexercised by any test**. Unlike CT-16's
wire-GAP (impossible by construction) or CT-06's process-leg fixture corner (the chain
pinned elsewhere), this leg is cheaply reachable — `quarantineLimit` lag/readmit cycles in
the walk, or a phase 4 in the wire test — and simply was not written. Named closing
condition in the row; the only C4 residual on the map.

### CT-31 (PASSING since C3) — note corrected, status unchanged (rot check)

The C4 commit deleted `CatchUpService`, which CT-31's trailing note still described as
"superseded shelfware — deletion is C4-1's register row". Checked: none of CT-31's test
evidence cited the deleted class (the supersession was `FanOutSessionCore`+`ReplaySource`,
already the row's named machinery), so nothing rots; the trailing note now records the
deletion as DONE at `3e8ec7d` with the pom-exclusion removal and the RR-088 narrowing.
Same check run over every other PASSING row's evidence: no row cites `SlowConsumerPolicy`
or `CatchUpService` (the old CT-27 note was the last live citation; rewritten this audit).
`ConfigdServerTest:254`'s decorative assert is gone (replaced by a comment naming the
supersession — read at source).

### CT-38 (PARTIAL, stays) — notes-only update

The row's owed checklist item "slow-consumer state-transition counters (one per
CT-27..CT-30 transition)" is DONE: six `edge_fanout_*` policy counters + the five
`consumer_state_*` gauges, eagerly registered (RR-013), exact exported names + movement
pinned (`RegistryFanOutSessionMetricsTest`), refusal/readmission/quarantine counters
observed against a live server. Row stays open for the staleness histogram, V2 probe
histograms, and the consolidated `EdgeMetricsContractTest` gate — unchanged in kind.

### Gate-3 walk + determinism (cross-cutting, informs CT-27..29)

`SlowConsumerStateMachineWalkTest` is the charter's gate-3 step and it does pin ORDER,
not mere visitation: the recorded transition stream must equal the exact five-leg list
`HEALTHY→SLOW (queue_warn_sustained) → SLOW→CATCHUP (queue_overflow) →
CATCHUP→QUARANTINED (demote_limit) → QUARANTINED→CATCHUP (readmitted) →
CATCHUP→HEALTHY (catchup_resolved)` — an out-of-order or extra transition fails the
equality, and the quarantine event's cursor evidence + `distressDemotionsInWindow == 3`
are asserted on top. Determinism is proven where the governor actually runs (deviation 4
accepted): a full second walk replays and the `TransitionEvent` streams must be EQUAL
(timestamps, cursors, window counts) — vacuity-resistant because the events carry sim
time and cursors, not just state names. The C4-2 flap scenario meets the hard bar I set
for it: recovery is proven **per cycle** (in-loop convergence assertion on each heal,
cycles crossing the replay horizon — ring cap 8, 12 commits per partition) AND tied to
the real path (`resubscribes() >= 3` — the C3 recovery actually fired), on top of the
zero-escalation asserts (no transitions, no refusals, no quarantines). Note the honest
mechanism: a partition/heal flap recovers via fresh-subscribe SNAPSHOT_FIRST, so the
governor sees NO demotions at all — even stronger than "GAPs under the limit"; the
mid-session GAP-ladder weighting itself is unit-pinned (CT-28). Gate path untouched:
the governor is opt-in (`null` = historical behavior, verified in `C1StreamDriver`) and
`EdgeSeedCompatTest` is green in my re-run.

## Rows flipped (old → new)

| Row | Old | New |
|---|---|---|
| CT-27 | UNIMPLEMENTED | PASSING |
| CT-28 | UNIMPLEMENTED | PASSING |
| CT-29 | UNIMPLEMENTED | PASSING |
| CT-30 | UNIMPLEMENTED | PARTIAL(unit) — named remainder: the UNHEALTHY sim/wire leg |

Notes-only updates: CT-31 (deletion recorded, rot check clean), CT-38 (CT-27..30 counters
struck from the owed list), map header (+c4 audit reference), footer + summary recounted.

## Rows deliberately NOT flipped (refusals, each with the named reason)

- **CT-30 not flipped to PASSING** — the charter's "full state machine" walk stops one
  state short (no UNHEALTHY leg in the sim or at the wire; the server's UNHEALTHY teardown
  arm is unexercised). The leg is cheaply reachable and was simply not written — that is a
  coverage gap, not a fixture impossibility, so it gets PARTIAL with a closing condition,
  not a justified-nuance PASSING.
- **CT-38 stays PARTIAL(unit)** — C4's counters close one checklist item; the consolidated
  gate and the remaining series are untouched.
- **CT-26 stays PASSING unchanged** — re-checked because C4 touched its machinery
  (`FanOutSessionCore` demotion-listener param, `FanOutServer` constructor): the listener
  seam is optional/pre-existing in signature style, the C1 evidence cites neither deleted
  class, and the full reactor at `3e8ec7d` is green.
- **CT-34/CT-02/CT-39/CT-24** — untouched by C4; closing conditions unchanged.

## REQUIRED / tracked gaps

1. **CT-30's UNHEALTHY leg (the one C4 residual, non-blocking for sign-off but named):**
   extend `SlowConsumerStateMachineWalkTest` (or `FanOutServerQuarantineTest`) with
   `quarantineLimit` quarantine/readmit cycles → UNHEALTHY → unhealthy-cooldown
   auto-readmission. Until then the `|| UNHEALTHY` teardown arm in
   `FanOutServer.onDemotionEvent` and the wire-level unhealthy refusal are dead-reckoned
   from the QUARANTINED twin, not observed.
2. **Consolidated doc-pass ledger grows by one §7 item:** the Slow Consumer Policy table's
   credit thresholds are now wholesale re-based on the C1 frame/ack-lag signals with the
   inclusive `>=` boundary reading (design note §6 deviation 1) — the session-close doc
   pass owes the table amendment alongside the already-ledgered chunk-resume, WAL-delta,
   ADR-0040-pointer, and regional-relay items.
3. **Commit-message nit (record-keeping only):** `3e8ec7d` says
   `RepeatQuarantineUnhealthyTest(7)`; the suite has **6** tests (surefire + body count
   agree). The design note makes no count claim, so nothing load-bearing is wrong — but
   anyone reconciling counts against the commit message should use the surefire numbers.

## Defects found in tests' probative value

- **One real (drives the CT-30 verdict):** the walk's `CountingPolicyMetrics.unhealthy`
  is recorded but never asserted anywhere in the suite — symptomatic of the missing
  UNHEALTHY leg rather than an oversight in an existing assertion.
- **None blocking in the flipped rows.** The suites carry their own controls and they
  checked out at source level: the warn-window tests kill the sentinel/epoch-zero and
  cadence-dependence mutants; the clean-slate test's short-cooldown config makes
  clear-vs-prune distinguishable; the wire test's snapshot-completion sync is what makes
  the second demotion possible at all (and the bogus-high cursor makes the forced
  re-bootstrap falsifiable); the walk asserts order by list equality, and the flap
  scenario proves recovery per cycle rather than inferring it from silence.
- **Fixture limitations recorded in rows, not defects** (each justified): CT-28's
  torn-bye tolerance on the demotion-phase close (pre-existing writer race, named in
  design §7; the clean code 8 strictly asserted on the refusal leg + via the real edge
  core in the sim); CT-27's SLOW promotion not driven at the wire (no wire surface by
  design; sim-pinned through the same governor+core pair; the server feed verified at
  source and wire-exercised on its ack-progress edge).
- **Nit, not tracked:** the unhealthy window's exact edge (a quarantine exactly
  `unhealthyWindowMs` old) is not explicitly pinned the way the demote window's is — same
  `prune` helper, mutation-killed at 100% governor PIT, so the inclusive semantics are
  already enforced transitively.

## New summary line (recounted: 30 + 6 + 0 + 1 + 3 + 1 = 41)

```
CONTRACT-MAP-SUMMARY: total=41 passing=30 partial=6 failing-captured=0 unimplemented=1 adr=3 na=1
```

## Sign-off

The contract-qa sign-off line in `c4-slow-consumer-design-note.md` may be marked against
this audit **with one carve-out**: the note's §9 claims CT-26..CT-30-class rows; CT-27/28/29
verified accurate as claimed (the §6 deviations are each honestly recorded and none is
silently load-bearing; the §7 residuals are real and correctly non-blocking), but
**CT-30 lands PARTIAL, not PASSING** — the note's own §4 walk description honestly shows
the missing UNHEALTHY leg, and the gap rides it. Conditions for the review-architect's half
of the dual sign-off: nothing beyond the standing ratifications; the C4-1 deletion condition
is verified met in-tree, and the gate path is byte-identical (`EdgeSeedCompatTest` re-run
green at audit time).

---

# ADDENDUM — re-verification after the CT-30 closure + the C4-A/RR-101 discharge

> **Date:** 2026-06-12 (same day, post-sign-off). **Tree:** uncommitted working tree on
> top of `3e8ec7d` (the C4-A fix lot: `FanOutServer.sessionLoop` overflow fix, two new
> tests, `ErrorCode.QUARANTINED` javadoc (sign-off C4-C), register row RR-101).
> **Method:** both new test bodies read in full; the `FanOutServer` fix diff read (the
> `Long.MIN_VALUE` subtraction replaced with the overflow-proof next-deadline idiom,
> comment naming the P1); RR-101 register row read (owner-reproduced RED by revert —
> second-agent discipline satisfied); the sign-off review's conditions table and Finding 1
> read; targeted runs below.

## A live collision, disclosed (and what it accidentally proved)

My first combined re-run (`-pl configd-server,configd-testkit`, pgrep CLEAR at launch)
came back with `sustainedQueueWarnPromotesToSlowOnTheLiveSessionLoop` **FAILING** —
"governor state is HEALTHY, expected SLOW", the full 20 s deadline — while a parallel
Maven run (another lane re-verifying the same lot) overwrote the surefire reports with a
green 4/4 at 03:57:26 moments later. The failure signature is **verbatim the RR-101
broken-form RED** (the register row's revert-reproduction describes exactly this assert
failing exactly this way): my forked test JVM almost certainly executed a stale pre-fix
`FanOutServer.class` mid-collision (`target/classes` predated the fix at my launch).
Handling per the C3-audit precedent: no conclusions from the collided run, two CLEAN
isolated re-runs performed with pgrep verified clear before AND after each. To the
evidence's credit: the collided run is an accidental third demonstration that the
regression test goes RED against the broken form — it cannot pass vacuously.

## Clean re-run results (isolated, 2-vCPU rules observed)

| Suite | Tests | Failures | When |
|---|---|---|---|
| `FanOutServerQuarantineTest` (now 4: + live-loop SLOW, + wire UNHEALTHY) | 4 | 0 | 04:00:16 |
| `FanOutServerQuarantineTest` (stability repeat) | 4 | 0 | 04:00:50 |
| `SlowConsumerStateMachineWalkTest` (now 4: + the UNHEALTHY sim leg) | 4 | 0 | 04:01 |
| `EdgeSeedCompatTest` (gate path still byte-identical) | 1 | 0 | 04:01 |

## CT-30 — PARTIAL(unit) → **PASSING** ✅ FLIPPED (the named remainder closed as written)

Both halves of my closing condition landed, and both survive the non-vacuity bar:

- **SIM** (`#quarantineLimitCyclesEscalateToUnhealthyThenAutoReadmit`, read in full):
  `quarantineLimit` (3) lag/readmit cycles walk the machine to its LAST state. The 3rd
  quarantine escalates (the cycle-3 loop guard exits on the DIRECT CATCHUP→UNHEALTHY
  escalation — the driver-side UNHEALTHY kick arm genuinely runs); the alert metric is
  asserted **exactly once** — closing this audit's probative-value defect 1 (the
  recorded-but-never-asserted `unhealthy` counter); the `repeat_quarantine` event carries
  `quarantinesInWindow=3`; refusals during the unhealthy cooldown are DELTA-counted (not
  just nonzero); and the readmission is pinned to the `readmitted_after_unhealthy_cooldown`
  leg specifically (a quarantine-cooldown readmit could not satisfy the assert), ending
  converged HEALTHY on a fresh commit.
- **PROCESS** (`#secondQuarantineWithinTheWindowEscalatesToUnhealthyAtTheWire`, read in
  full): the previously-unexercised `FanOutServer.onDemotionEvent` UNHEALTHY teardown arm
  now runs at a live server (`demoteLimit=1`/`quarantineLimit=2` scaling; quarantine #1 →
  injected-clock cooldown elapse → forced-SNAPSHOT_FIRST readmission, snapshot completion
  awaited so the second overflow is a real second demotion → quarantine #2 escalates →
  wire disconnect). The refusal leg strictly asserts code 8 **with `UNHEALTHY` named in
  the diagnostic** — the deviation-5 distinguishability is now wire-asserted, and the
  `ErrorCode` javadoc amendment (C4-C) landed alongside. The 120 s unhealthy cooldown
  advances by clock only; readmission defeats a bogus-high cursor (999_999) into
  SNAPSHOT_FIRST → ack → HEALTHY. "Removed from distribution tree" and the C4-3 exit are
  both observed at the wire. Torn-bye tolerance on the escalation disconnect is the same
  named CT-28 residual (clean code 8 strictly asserted on the refusal leg).

## CT-27 — stays PASSING, evidence note REWRITTEN (RR-101; an audit self-correction)

The review-architect's sign-off held CT-27 against condition C4-A, in parallel with my
flip — and the hold was right where my audit was wrong: my CT-27 note claimed the
"pressure-edge/evaluate cadence wiring was verified at source", but the source I read had
the dead branch (the `Long.MIN_VALUE` cadence anchor compared by subtraction overflows
negative for any real clock value, so `governor.evaluate()` never ran on the production
loop). I read that exact line and did not do the overflow arithmetic. The defect class —
direct-call unit/sim green around a dead production branch — is precisely why the
reviewer demanded a live-loop leg, and RR-101's generalized lesson ("any cadence-gated
server behavior needs a live-loop test leg") is hereby adopted into this lane's checklist
for future rows. C4-A is now discharged: the fix is overflow-proof
(`nowMillis >= nextGovernorEvalMillis`, no sentinel subtraction), RR-101 is RESOLVED with
the owner's revert-RED/fix-GREEN reproduction, and the regression test drives
HEALTHY→SLOW through the REAL session loop with no direct governor calls (frozen-clock
negative control first, then clock-advance promotion + the metric exactly once + the
ack-driven exit on the same loop). The CT-27 row now cites this as its strongest proof
and the stale "verified at source" sentence is replaced by the honest record. My flip
predates the hold's discharge only textually — the map was never committed in between,
so no green was shown against a tree where the hold applied.

## Updated tallies

| Row | Old (this audit's main body) | New (addendum) |
|---|---|---|
| CT-30 | PARTIAL(unit) | PASSING — remainder closed as named |
| CT-27 | PASSING (evidence: unit+sim+gate, source-read wiring) | PASSING (evidence upgraded: + the RR-101 live-loop regression at PROCESS level) |

All four C4 rows are now closed; C4 carries no map residual (doc-pass items C4-B + the
§7 table amendment ride the session-close ledger). Defect 1 from the main body (the
unasserted `unhealthy` walk counter) is closed by the new sim leg. The commit-message
miscount (finding: `RepeatQuarantineUnhealthyTest(7)` vs actual 6) stands for the
eventual C4-A commit message to avoid repeating.

## New summary line (recounted: 31 + 5 + 0 + 1 + 3 + 1 = 41)

```
CONTRACT-MAP-SUMMARY: total=41 passing=31 partial=5 failing-captured=0 unimplemented=1 adr=3 na=1
```
