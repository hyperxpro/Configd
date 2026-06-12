# C4 Component Sign-off Review — review-architect

> **Scope (JOB A):** the C4 slow-consumer policy component sign-off against charter §1
> rule 2's DONE-definition. As-built note:
> `docs/session-3/design/c4-slow-consumer-design-note.md` (commit `3e8ec7d`, HEAD at
> review time, clean tree). Prior gates: `docs/session-3/reviews/c2-c5-design-screen.md`
> §C4 (CLEARED-WITH-CONDITIONS, C4-1/C4-2/C4-3 tracked to THIS sign-off);
> `c3-signoff-review.md` forward-handoff (CatchUpService disposition). **Reviewer:**
> review-architect. **Date:** 2026-06-12. **Branch:** session-3-data-plane. Read-only on
> code; the only file written is this one (the RR-088 narrow was verified ACCURATE — no
> register edit needed or made). Maven runs executed after confirming no competing build
> (`pgrep -f "[o]rg.apache.maven"` clear before each): targeted re-runs —
> `SlowConsumerWarningTransitionTest` (8), `SlowConsumerQuarantineTransitionTest` (8),
> `QuarantineReBootstrapTest` (9), `RepeatQuarantineUnhealthyTest` (6),
> `SlowConsumerPolicyConfigTest` (2), `FanOutServerQuarantineTest` (2),
> `RegistryFanOutSessionMetricsTest` (4), `SlowConsumerStateMachineWalkTest` (3),
> `EdgeSeedCompatTest` (1) — **all green, fresh surefire reports confirmed by mtime
> (2026-06-12 03:33–03:34)**. Full reactor green, the 507-seed sweep byte-identity, the
> 10k-seed standalone run, and the PIT figures (100% / 78.0%) are the committer's recorded
> claims, accepted as recorded (no PIT artifacts on disk; no PIT re-run — 2-vCPU
> discipline, the C3-review precedent).

Severities: **BLOCKING** (gates DONE), **REQUIRED** (must land, tracked, does not gate),
**NOTE** (advisory). Every item carries a prod-blocking / non-blocking flag.

## Verdict: **SIGNED-OFF** — finalized 2026-06-12 after the C4-A/C4-C discharge

> Initial verdict (same date, earlier): **SIGNED-OFF-WITH-CONDITIONS** (1 BLOCKING —
> C4-A; 2 REQUIRED — C4-B/C4-C; 7 NOTE). **C4-A and C4-C are now DISCHARGED** — fix,
> regression test, and RR-101 verified in the working tree on top of `3e8ec7d`,
> including this reviewer's own independent RED/GREEN reproduction (see the **Discharge
> Addendum** at the end of this file). The only remaining item is **C4-B** — REQUIRED,
> which by this review's own severity scheme does not gate (the C3 sign-off precedent:
> REQUIRED items tracked to the session-close doc pass). The verdict therefore finalizes
> to SIGNED-OFF. The original analysis below is preserved as written; the conditions
> table carries per-condition discharge status.

The governor itself is correct, deterministic, fully observable, and adversarially
tested — every unit/wire/sim claim I checked against the code held, all three screen
conditions are discharged in the governor and its tests, and both deletions are real,
safe, and register-disciplined. **But the live `FanOutServer` wiring has a dead branch:
the time-driven HEALTHY→SLOW evaluation can never fire on the production session loop**
(Finding 1 — a `Long.MIN_VALUE` anchor overflows the cadence comparison; reproduced
arithmetically; no server-level test covers the SLOW rung, which is why the full battery
is green around it). The enforcement tiers (CATCHUP/QUARANTINED/UNHEALTHY, refusal,
forced re-bootstrap) are unaffected and proven at the wire. Because the defect is a
single initialization constant with the underlying logic 100%-mutation-killed and
unit-pinned, this is a conditional sign-off, not a BLOCKED: **C5 may start**, but C4 is
NOT DONE — and CT-27 must not flip PASSING — until condition C4-A lands.

### Conditions of this sign-off

| # | Condition | Severity | Gate | Status |
|---|---|---|---|---|
| **C4-A** | Fix the dead governor evaluation in `FanOutServer.sessionLoop` (Finding 1) + add a server-level regression test that drives HEALTHY→SLOW through the real session loop with the injected clock (the `FanOutServerQuarantineTest` pattern: hold the queue above warn, advance `MutableClock` past `queueWarnWindowMs`, assert `governor.state()==SLOW` and `edge_fanout_slow_transitions_total==1`). The lead registers the P1 row (charter rule 6; the reproduction transcript is Finding 1 — arithmetic demonstration + dead-branch citation). | **BLOCKING** (prod-relevant: the §7 warn tier is dark at runtime) | Blocks C4 DONE, the CT-27 map flip, and any gate-3 claim. Does NOT block C5 start. | **DISCHARGED 2026-06-12** (Discharge Addendum: fix verified, regression test verified non-vacuous by reviewer RED/GREEN, RR-101 verified accurate) |
| **C4-B** | `docs/runbooks/edge-catchup-storm.md` instructs operators to inspect/configure `CatchUpService` and `SlowConsumerPolicy` — both now DELETED (Finding 2). Fold into the session-close consolidated doc pass (the RR-050 runbook-fiction family), together with the §7 text amendments already owed by the C3 review (Finding 8 extends that list with the C4 re-based thresholds). | REQUIRED (non-blocking; doc honesty) | Session-close doc pass | **OPEN** (the one remaining item; non-gating) |
| **C4-C** | `ErrorCode.QUARANTINED` javadoc (`ErrorCode.java:42`) says only "session quarantined" — UNHEALTHY teardowns and refusals share code 8 (note deviation 5). One sentence: "also carried by UNHEALTHY-state closes/refusals; governor state + message text distinguish." (Finding 3) | REQUIRED (trivial; taxonomy honesty) | Next touch | **DISCHARGED 2026-06-12** (javadoc landed and read — names the shared UNHEALTHY use, the closed taxonomy, and the message/state distinction) |

---

## Screen conditions C4-1 / C4-2 / C4-3 — all DISCHARGED (verified, not taken from the note)

| Condition | Verdict | Evidence (verified in code/tests/history, file:line) |
|---|---|---|
| **C4-1** both deletions real+safe, register row updated in the same change | **DISCHARGED** | (a) **Deletions real:** `git show 3e8ec7d --stat` removes `SlowConsumerPolicy.java` (212 lines) and `CatchUpService.java` (171); both `-Pmutation` `excludedClasses` entries removed (`configd-distribution-service/pom.xml` diff). (b) **Safe / zero consumers:** `git grep` at `3e8ec7d^` shows the orphan's ONLY consumers were the decorative `ConfigdServer.slowConsumerPolicy()` accessor (`:1006`) and the `assertNotNull` at `ConfigdServerTest:254` — both removed with it; nobody ever called `register/recordSend/recordAck`. Post-delete repo grep: remaining references are deletion *records* (governor javadoc `SlowConsumerGovernor.java:14-16`, pom comment `:71-74`, `ConfigdServerTest:254-257` comment) and dated audit/history docs — plus two genuinely stale live-claims (Findings 2, 9). (c) **Not a rename-and-shrink:** the orphan (read in full at `3e8ec7d^`) was a `NodeId`-keyed clock-threshold tracker with NO unhealthy tier, NO auto-readmission, NO admission seam, NO metric, NO log, NO test, and a manual `readmit()`; the governor is identity-keyed, implements all four §7 rungs incl. the "3 quarantines in 1 hour" tier the orphan never had, with windows, cooldowns, eviction bound, and full observability. Strictly broader — genuinely supersedes the documented §7 role. (d) **Register discipline:** the RR-088 narrow is in the SAME commit (the screen's "do not let the delete happen without the register reflecting it"), and `CatchUpService` is separately dispositioned (DELETED, C3 note §8 rec) exactly as the condition demanded. RR-088 narrow verified accurate (section below). |
| **C4-2** GAP-vs-distress reason weighting | **DISCHARGED** | Separate ladders in code: `SlowConsumerGovernor.onDemotion:235-249` selects the deque and the limit by `REASON_GAP` (`gapDemoteLimit` 10 vs `demoteLimit` 3, same window). Pinned by the `SlowConsumerQuarantineTransitionTest` matrix: 4 GAPs > distress-limit do NOT quarantine (`:91-108`), the gap backstop still trips (`:110-125`), mixed reasons count on their own ladders with the window counts asserted on the event (`:127-149`), window-edge inclusivity pinned (`:170-183`). Sim flap scenario verified non-vacuous (Finding 6). Mixed-sequence dodge analysis: ruling on check 2 below. |
| **C4-3** cooldown-only exits + observable refusals + flap test | **DISCHARGED** | UNHEALTHY auto-readmission with NO operator action: `admit()`'s UNHEALTHY arm readmits the moment `remaining <= 0` (`SlowConsumerGovernor.java:300-305`), pinned at the exact boundary (`RepeatQuarantineUnhealthyTest:112-123` — refused at `q3+HOUR-1`, `ALLOW_FORCE_SNAPSHOT` at `q3+HOUR`). Operator reset is additional, not required (`operatorResetIsAnAdditionalExitNotTheOnlyOne`, `QuarantineReBootstrapTest:111-125`). EVERY refusal is metered+logged: `REFUSE` is only constructible via `refuse()` which fires `onReconnectRefused()` + the structured `edge_fanout_admission_refused` line with `cooldownRemainingMs` (`SlowConsumerGovernor.java:422-431`); the counter is eagerly registered (`RegistryFanOutSessionMetrics.java:117`); the wire close carries the diagnostic (`FanOutServer.java:436-438`, asserted at `FanOutServerQuarantineTest:170-176`). Flap test non-vacuity: Finding 6 — it PROVES recovery fired each cycle. |

---

## RR-088 narrow — VERIFIED ACCURATE; no register edit made

Checked claim-by-claim against code/history: deletions real (above); "decorative
ConfigdServer wiring … the orphan's only consumer" — verified by `git grep` at
`3e8ec7d^` (accessor + one `assertNotNull`, nothing else); "zero `src/main` consumers
proven by repo grep + full reactor compiling green post-delete" — reproduced (grep) /
consistent (HEAD builds and all targeted suites compile+pass); "both pom excludedClasses
entries removed" — verified in the diff; "narrowed to HyParViewOverlay, close at S7" —
matches the pom comment. The 100% (94/94) and 78.0% (518/664) PIT figures could not be
re-verified (no `target/pit-reports` on disk; PIT re-runs prohibited on this box) — they
are accepted as the committer's recorded claims, the same standing the C3 review gave its
PIT figures. The row stays as placed.

---

## Finding 1 — The live session loop's time-driven SLOW evaluation is DEAD: `Long.MIN_VALUE` anchor overflows the cadence check [BLOCKING — prod-relevant; condition C4-A]

`FanOutServer.sessionLoop` initializes
`long lastGovernorEvalMillis = Long.MIN_VALUE;` (`FanOutServer.java:567`) and gates the
governor evaluation with
`if (above && nowMillis - lastGovernorEvalMillis >= governorEvalCadenceMs)`
(`FanOutServer.java:605`). For ANY `nowMillis ≥ 0`, `nowMillis - Long.MIN_VALUE`
**overflows to a negative long** (`0 − MIN = MIN`; `1.7e12 − MIN ≈ −9.22e18`; verified by
execution: `now=0/1/1.7e12/MAX_VALUE → diff < cadence` in every case). The condition is
false forever, `lastGovernorEvalMillis` is never reassigned, and `governor.evaluate()` is
**never called from the production path**.

Consequence — the HEALTHY→SLOW rung is unreachable at runtime: the only other promotion
point is a *repeat* above-warn pressure signal arriving after the window
(`SlowConsumerGovernor.onQueuePressure` → `maybePromoteSlow`, pinned by
`SlowConsumerWarningTransitionTest#aRepeatedAboveSignalItselfPromotesOnceTheWindowElapsed`),
but the server feed is deliberately edge-gated (`above != aboveWarn`,
`FanOutServer.java:601`) so a sustained-above queue produces exactly one above-edge at
elapsed≈0 and then nothing — and a below-edge resets the anchor
(`SlowConsumerGovernor.java:189-195`). In production, `edge_fanout_slow_transitions_total`
and `edge_fanout_consumer_state_slow` will read 0 forever and the §7 warning tier
(CT-27's process-level substance) is silently dark. The quarantine/unhealthy ladders,
admission, and readmission are demotion/subscribe-driven and unaffected — every wire-level
behavior `FanOutServerQuarantineTest` pins is genuinely live.

Why the battery is green around it: the unit suite calls `evaluate()` directly; the sim
walk's SLOW leg runs through `C1StreamDriver.feedQueuePressure` which calls
`evaluate()` per tick with no such anchor (`C1StreamDriver.java:321-323`); no test drives
the SLOW rung through the real `sessionLoop` (the field's own javadoc — "capped below the
warn window so a short test window is still promotable", `FanOutServer.java:100-106` —
describes a test that does not exist). PIT could not catch it: the mutation profile
targets `io.configd.distribution.*`, not `configd-server`.

The fix is one line (any non-overflowing arm: initialize to `0`, or guard with a boolean,
or saturate) plus the C4-A regression test. The as-built note's §1 claim ("≤1 Hz
evaluation on the session loop") and §5 ("one clock read per session-loop iteration…")
describe the *intended* wiring; as built, the cadence branch is dead code.

## Finding 2 — `docs/runbooks/edge-catchup-storm.md` now directs operators at DELETED classes [REQUIRED — non-blocking; condition C4-B]

The runbook's triage/mitigation steps name `CatchUpService` (":18, :23, :32, :52" —
"enable rate limiting on CatchUpService", "set CatchUpService to prefer snapshot…") and
`SlowConsumerPolicy` (":19, :28" — "verify SlowConsumerPolicy is active", "activate
SlowConsumerPolicy.DROP_OLDEST"), all of which were already fiction-adjacent (RR-050
family) and are now references to deleted code. This is the one *operational* doc that
claims the orphans exist as live machinery; the dated audit/history docs
(`docs/inventory.md`, `docs/gap-closure.md`, `docs/STATE-OF-REALITY.md`, prr/review
trees) are point-in-time records and correctly left untouched. Fold the runbook into the
session-close doc pass; the replacement content is the governor's actual knobs
(`edge.fanout.policy.*`) and series.

## Finding 3 — Code-8 reuse for UNHEALTHY: golden fixtures untouched; one under-describing javadoc [REQUIRED (trivial) — condition C4-C]

`git show 3e8ec7d` touches NO file under `distribution/wire/` — the golden fixtures and
the codec are byte-untouched; the closed taxonomy (1..10) is unchanged and a new code
would indeed have been a wire-version bump (`ErrorCode.java:11-13`). The reuse is
internally consistent: refusals and policy teardowns for BOTH states carry
`ErrorCode.QUARANTINED` with the state named in the message
(`FanOutServer.java:436-438,553-555`). The defect is documentation only:
`ErrorCode.java:42` ("C4 policy: session quarantined, must re-bootstrap") does not record
that UNHEALTHY shares the code — the exact under-description deviation 5 exists to
prevent. One sentence (C4-C).

## Finding 4 — Teardown bye race (note §7 residual): residual confirmed pre-existing; the strict assertion verifiably rides the clean refusal path; torn frames cannot decode as anything [verified — no new finding]

- **The strict leg is genuinely clean:** on a SUBSCRIBE refusal the reader thread calls
  `close → teardown` (`FanOutServer.java:434-440`); the session was never subscribed, the
  outbound queue is empty, and the writer is parked in `outbound.take()` until the POISON
  that `teardown` offers AFTER writing the bye (`FanOutServer.java:661-670`) — no
  concurrent writer bytes exist to tear the frame. `FanOutServerQuarantineTest` phase 2
  asserts this leg strictly (`readUntil(ErrorClose)` + code 8 + message, `:168-177`);
  phase 1 (demotion teardown, where the writer CAN be mid-frame) is correctly tolerant
  (`drainUntilQuarantinedOrClosed:280-300`) and the policy is pinned by governor state +
  metrics instead. Exactly what the note claims.
- **A torn frame can only look like a dead connection:** every frame carries a CRC32C
  trailer validated BEFORE the version/type bytes are interpreted
  (`EdgeFrameCodec.java:26,342-350`), and the length prefix is bounds-checked before
  allocation. Interleaved bye bytes inside an in-flight frame corrupt the CRC →
  `CodecException` → the edge-node reader loop exits via the same path as EOF
  (`EdgeStreamClient.readerLoop:555-565` → `postClosed()`) — unparseable-then-disconnect,
  never a half-frame decoded as a different frame. (Residual probability is the CRC32C
  collision class, accidental-fault grade; the interleaving source is the server itself,
  not an attacker.) The writer-handoff fix remains correctly out of C4 scope.
- **Edge reaction arm:** code 8 lands on `EdgeClientCore.onErrorClose`'s default
  (fatal) arm → reconnect directive at cursor (`EdgeClientCore.java:589-598`), pinned by
  `EdgeClientCoreTest$ErrorCloseHandling.fatalCloseQueuesReconnectAtCursor` (the arm, via
  code 6) and driven with code 8 through the REAL core by the sim walk
  (`EdgeActor.java:230-234`); the shell's bounded+jittered backoff
  (`EdgeStreamClient.backoff:395-403`, cap `MAX_BACKOFF_MS`) absorbs the refusal loop.
  The note's residual ("no new edge-cache unit test for code 8 specifically") is honest.

## Finding 5 — State-machine soundness hunt: no P1 in the governor; interleavings bounded; eviction judged sufficient [verified; 3 NOTEs]

Hunted the identity-keyed shared state under `synchronized`, adversarially:

- **Demotion (conn A) racing SUBSCRIBE (conn B), same identity:** serialized by the
  governor lock; if B admits first (HEALTHY→ALLOW) and A's demotion then quarantines, B
  is a live session of a quarantined identity — only A is torn down synchronously
  (`teardown` is connection-scoped). B dies at its next demotion (the
  QUARANTINED/UNHEALTHY early-return keeps it from double-counting,
  `SlowConsumerGovernor.java:230-234`, pinned by `demotionsAfterQuarantineDoNotDoubleCount`
  and `aStragglerDemotionAgainstAnUnhealthyIdentityIsInert`) or next subscribe. The note
  names this residual (§7 "multi-connection identity", degenerate by design — one cert
  per edge). **NOTE (non-blocking):** one additional wrinkle the note does not name:
  `admit()`'s default arm resets `queueWarnSinceMillis = -1`
  (`SlowConsumerGovernor.java:307-311`) — correct for the reconnect case it documents,
  but a *second concurrent* connection's subscribe wipes a live sibling connection's
  pending warn window, suppressing its SLOW promotion. Same degenerate topology, warn
  tier only; record it next to the named residual (and moot until Finding 1 is fixed).
- **Quarantine teardown racing readmission:** the readmit transitions QUARANTINED→CATCHUP
  with windows already cleared at quarantine time (`quarantine():414-419` — clear AFTER
  the transition so the event carries the tripping counts, verified); a straggler
  demotion from the dying previous connection lands on CATCHUP and counts ONE entry into
  the fresh window (bounded: the old session loop exits at teardown; at most one
  in-flight demotion). Window decay absorbs it. No premature re-trip path found.
- **Bounded-map eviction:** `evictIfAtBound` runs only on new-identity insert at the
  bound and evicts the LRU **HEALTHY** record, skipping every distressed one
  (`SlowConsumerGovernor.java:370-385`) — an attacker with many identities CANNOT evict a
  QUARANTINED/UNHEALTHY/SLOW/CATCHUP record and reset its ladder; the honest overflow
  (all-distressed map exceeds the bound) is documented and bounded by real distinct certs
  in distress. Pinned incl. the skip-past-distressed-head leg
  (`RepeatQuarantineUnhealthyTest#trackedIdentityMapIsBoundedAndNeverEvictsADistressedIdentity`).
  The gauge stays consistent (the −1/+1 pair publishes once in `record()`). **NOTE
  (non-blocking):** eviction CAN forget the sub-limit demotion history of an identity
  that returned to HEALTHY (the windowed deques survive CATCHUP→HEALTHY but die with the
  evicted record) — a ladder reset costs ≥ `maxTrackedIdentities` (4096) distinct
  identities per cycle. Under mTLS identities are operator-issued certs: judged
  SUFFICIENT. Over plaintext the wire `edgeId` makes identities free — but plaintext is
  test/single-node with the same trust posture as C1's identity binding (note §7 names
  it). Accepted.
- **Epoch-0/time-0 anchors:** the governor side is clean — the warn-window sentinel is
  −1, not 0 (`queueWarnSinceMillis`, anchor-at-`t=0` pinned by
  `SlowConsumerWarningTransitionTest#epochZeroAnchorsTheWarnWindowCorrectly`);
  `stateEnteredMillis` is always written by `transition()` before any cooldown read. The
  ONE time-anchor defect in the component is the server-side `Long.MIN_VALUE` (Finding 1)
  — exactly this hunt's class.
- **`cooldownRemainingMs` boundaries:** `remaining = cooldown − (now − entered)`;
  `remaining > 0` refuses, `0` readmits — pinned at both exact edges (59_999/60_000 in
  `QuarantineReBootstrapTest:50-65,73-76`; HOUR−1/HOUR in
  `RepeatQuarantineUnhealthyTest:108-118`). A backwards clock inflates `remaining`
  (refuse-with-large-diagnostic) — fail-safe, no escape. With
  `unhealthyCooldownMs == unhealthyWindowMs` (defaults) the old quarantine stamps age out
  by readmission (+re-quarantine time), pinned (`:126-133`); an operator who configures
  cooldown ≪ window gets faster re-escalation — window semantics, not a defect.

## Finding 6 — Walk + flap tests: order-pinned and non-vacuous; two honest scope notes [verified; NOTE]

- **The walk's order-recorded assertion is real:** it asserts the EXACT ordered legs list
  `HEALTHY→SLOW(queue_warn_sustained) → SLOW→CATCHUP(queue_overflow) →
  CATCHUP→QUARANTINED(demote_limit) → QUARANTINED→CATCHUP(readmitted_after_quarantine_cooldown)
  → CATCHUP→HEALTHY(catchup_resolved)` from the recorded structured events
  (`SlowConsumerStateMachineWalkTest:132-141`), plus refusals observed during the
  cooldown (`:107-110,142`), cursor evidence on the quarantine event (`:144-148`), and
  post-readmission convergence to a fresh commit (`:117-120`). Replay determinism
  compares the full `TransitionEvent` record streams (timestamps, cursors, window
  counts) plus counters (`:151-164`) — the non-vacuous discharge of draft §4's digest
  folding (deviation 4 ACCEPTED: the governor is opt-in and absent on the gate path, so
  a gate-digest fold would be vacuous). **NOTE:** the walk pins the charter-enumerated
  spine (demote→quarantine→disconnect→re-bootstrap) but not the UNHEALTHY tier or the
  SLOW→HEALTHY ack exit — both clock-pinned at unit level
  (`RepeatQuarantineUnhealthyTest`, `SlowConsumerWarningTransitionTest`); the UNHEALTHY
  wire behavior shares the refusal path the walk DOES drive. Acceptable against charter
  §4 C4's wording; recorded so nobody reads "full machine" as "all eight transitions in
  one sim run".
- **The flap test PROVES recovery each cycle, not just "no transitions":** per-cycle
  convergence is asserted inside the loop (`tickUntil(hasValue("flap/c/after"))`,
  `:197-200`) and the recovery path is proven taken (`driver.resubscribes() >= 3`,
  `:203-204`); the zero-escalation half (no transitions, no refusals, no quarantines,
  ends HEALTHY, `:205-209`) is therefore non-vacuous. **NOTE (honesty):** in this
  topology the partition heals at SUBSCRIBE time (edge-side gap → resubscribe →
  `decideMode`), so no `REASON_GAP` `DemotionEvent` ever reaches the governor — the
  zero-transitions result is *stronger* than no-escalation, but it means the in-session
  gap ladder's tolerance (C4-2's literal mechanism) is exercised at unit level only
  (`gapDemotionsAreWeightedSeparately…`, `aGenuineGapLoopStillTripsTheGapBackstop`).
  The note's §3.1 wording ("recovery fires each time, zero policy escalation") is
  accurate as written.

## Finding 7 — Hot-path law (charter §6 rules 3-4): PASS; the ack feed is the closest call [verified; NOTE]

- **Edge read path (rule 3):** untouched — the governor lives server-side; no edge-cache
  read-path file changed in `3e8ec7d`.
- **Session-loop feed:** exactly ONE clock read per iteration — `nowMillis` is read once
  (`FanOutServer.java:583`) and reused for `tick` + the whole feed (net-zero added clock
  reads vs C3: the loop already read the clock for `tick`). No allocation per frame: the
  busy-path additions are long/boolean compares; governor calls (which do allocate/box)
  are edge- or cadence-gated.
- **Every governor call site found and classified:** `FanOutServer` — `admit` (per
  SUBSCRIBE), `onDemotion` (per demotion), `onQueuePressure` (pressure EDGES only),
  `evaluate` (cadence-gated — currently dead, Finding 1), `onAckProgress` (ack-ADVANCE,
  coalesced to ≤1/loop-iteration); `C1StreamDriver` (sim, per-tick by design);
  `ConfigdServer` (construction only); tests. **No per-frame caller — no P1.**
- **NOTE (Session-5 watch item, non-blocking):** `onAckProgress` is the closest to the
  line: on a busy stream the coalesced ack-advance cadence approaches once per outbound
  NOTIFY batch per session, and every call takes the ONE shared governor monitor for an
  access-ordered `LinkedHashMap.get` (O(1), tens of ns). No rule-4 disqualifier (no
  O(subscribers) work under the lock, nothing unbounded, publish path untouched), but at
  ~1k sessions × per-batch acks the shared monitor becomes a measurable serialization
  point. Cheap future gate if Session 5 measures contention: only feed `onAckProgress`
  for connections that have seen a demotion or a forced-snapshot admission (its only
  effect is CATCHUP→HEALTHY resolution). The note's "every call is a policy-frequency
  event" is accurate for everything except this deliberately-coalesced feed; recorded.

## Finding 8 — Charter §4 C4 vs architecture §7 verbatim: deltas named and ruled [ruling]

§7's ladder (architecture.md:285-291) vs the implementation:

| §7 row | As built | Delta + ruling |
|---|---|---|
| "0 credits for > 10 s → warning log + metric" | queue ≥ warn sustained `queueWarnWindowMs` 10 s → SLOW + counter + structured log | **Re-based** trigger (credit numbers superseded — C1 review condition 4; screen C4-2 ratified the mapping). RATIFIED. *Runtime reachability is Finding 1.* |
| "0 credits for > 30 s → disconnect, mark quarantined" | `demoteLimit` 3 distress demotions / 60 s (or `gapDemoteLimit` 10) → QUARANTINED → `ERROR_CLOSE` code 8 + socket close | **Re-based** from time-at-zero-credit to demotion frequency over the C1 signals — the screen's C4-2 sanity check ratified exactly these numbers; `>=` (Nth event trips) is deviation 1, consistent across both ladders and pinned. RATIFIED. "Disconnect" is implemented verbatim (`FanOutServer.onDemotionEvent:544-557`, wire-asserted). |
| "Quarantined → must re-bootstrap via catch-up protocol" | refused for `quarantineCooldownMs` 60 s, THEN readmitted `ALLOW_FORCE_SNAPSHOT` — server rebinds the resume cursor to 0 (`FanOutServer.java:441-442`) so C3's `decideMode` cursor-0 rule yields SNAPSHOT_FIRST (process-pinned against a bogus-high cursor, `FanOutServerQuarantineTest:186-195`) | **Extension:** §7 is silent on a refusal window before readmission; the draft added it (anti-reconnect-storm) and the screen cleared those numbers. "Must re-bootstrap" is satisfied by REUSE of the C3 mechanism, not duplication — verified at the wire. RATIFIED. |
| "3 quarantines in 1 hour → unhealthy, removed from distribution tree" | `quarantineLimit` 3 / `unhealthyWindowMs` 1 h → UNHEALTHY (alert metric), refused for `unhealthyCooldownMs` 1 h, then AUTO-readmitted | **Deliberately softer than verbatim "removed":** the auto time-based exit is MANDATED by screen C4-3 (anti-permanent-lockout — a permanently dark edge serving stale config forever is worse). RATIFIED as the C4-3 supersession of §7's terminal wording. |

The charter's "disconnect" and "re-bootstrap" are both implemented and proven at the
wire. **Obligation (rides condition C4-B):** the session-close consolidated doc pass that
already owes the §7 catch-up text amendment (C3 review Finding 1) must also amend the §7
slow-consumer table to the re-based thresholds and the C4-3 unhealthy-cooldown exit, so
architecture.md stops describing superseded credit semantics as current.

## Finding 9 — Note/claim accuracy nits [NOTE — non-blocking]

- The commit message counts `RepeatQuarantineUnhealthyTest(7)`; actual is **6** (surefire
  + method count). All other counts verified exact (8/8/9/2 unit, 2 wire, 3 walk).
- `docs/session-3/contract-test-map.md:90` (CT-27) still reads "UNIMPLEMENTED …
  `SlowConsumerPolicy` class exists in src/main" — stale on both facts, but the map flip
  belongs to the contract-qa audit per the established split (note §9 says so
  explicitly). Owed to contract-qa — with the constraint that **CT-27 must not flip
  PASSING until C4-A lands** (the process-level warn rung is the very thing Finding 1
  kills).
- Draft §3's "its useful test patterns move to the governor's suite" had nothing to move
  — the orphan had zero tests (`contract-test-map-notes.md:52`). The governor's suite is
  new work, which is fine; recorded so the draft sentence isn't read as a discharged
  migration.
- The note §2's series name `edge_fanout_sessions_closed_quarantined_total` materializes
  as `edge.fanout.sessions_closed.quarantined` (`RegistryFanOutSessionMetrics.java:107`)
  — the registry's established dot-dialect; eager (RR-013) verified for ALL policy series
  incl. the five `consumer_state_*` gauges (`:111-138`).
- `FanOutSessionMetrics` additions are default no-ops (non-breaking, the C3
  `onSubscribeMode` pattern) — verified; NOOP/sim sinks unaffected.

## Check-by-check rulings (the review brief's eight checks)

1. **C4-1 deletions real+safe; supersession genuine; RR-088 accurate** — PASS (screen
   table above; RR-088 section above; no register edit needed).
2. **C4-2 mixed-sequence dodge + window edges** — PASS with one economics NOTE. A
   *genuinely wedged* consumer cannot sneak past both limits: reason labels are assigned
   server-side by C1's detection (`DemotionEvent.REASON_*`, `FanOutSessionCore.demote`),
   not client-controllable; a wedged consumer stops acking, so the distress ladder
   (ack-lag/overflow/transport) accrues on C1's re-demote cadence and trips 3/60 s
   regardless of interleaved gaps (pinned: `mixedReasonsCountOnTheirOwnLadders` — the 3rd
   distress trips with 2 gaps in-window). Pruning is consistent strict-older-than
   (`prune`, `:478-482`) = inclusive window edge, pinned at the exact edge
   (`theDemoteWindowIsInclusiveAtItsExactEdge`) and matching the `>=` limit semantics
   (deviation 1). **NOTE:** the dodge that DOES exist is economic, not a quarantine
   escape — a consumer engineering ≤9 GAP demotions/min sustains indefinite snapshot
   re-bootstraps without tripping the 10/60 s backstop. Bounded per identity, fully
   metered (`edge_fanout_demotions/quarantines` + decision metrics), and tunable via the
   named `gapDemoteLimit`/`demoteWindowMs`; a threshold-economics row for Session 5/6
   dashboards, not a C4 defect.
3. **C4-3 cooldown exits + observability + flap non-vacuity** — PASS (screen table;
   Finding 6).
4. **State-machine soundness hunt** — one P1 found, and it is the *server wiring's*
   time-anchor (Finding 1, exactly this check's epoch-0/time-0 class); the governor
   itself survived the hunt (Finding 5: interleavings bounded, eviction sufficient,
   boundaries pinned).
5. **Wire/protocol: code 8 + teardown race + torn frames** — PASS (Finding 4: golden
   fixtures untouched; strict assertion verifiably on the clean refusal path; CRC32C
   makes torn frames unparseable-then-disconnect) with the C4-C javadoc sentence
   (Finding 3).
6. **Hot-path law** — PASS, no per-frame caller (Finding 7; the dead `evaluate` branch
   is Finding 1's concern, not a hot-path one; the ack feed is a recorded Session-5
   watch item).
7. **Sim integration: walk order + gate neutrality** — PASS (Finding 6; gate path
   verified governor-free: every non-walk `C1StreamDriver` construction uses the
   null-governor constructors (`C1StreamDriver.java:125-131`, grep across testkit), the
   `EdgeStream.ErrorClose` message is sent ONLY by the opt-in wiring
   (`EdgeStream.java:101-121` javadoc verified against `C1StreamDriver.policyKick`),
   `EdgeSeedCompatTest` reviewer-reran green; the 507-seed byte-identity is the
   committer's recorded claim, consistent with the opt-in mechanism).
8. **Charter "disconnect + re-bootstrap" vs §7 verbatim** — deltas named and ruled
   (Finding 8): two ratified re-basings, one ratified extension (quarantine cooldown),
   one mandated softening (UNHEALTHY auto-exit per C4-3); §7 text amendment owed to the
   session-close doc pass.

## SIGN-OFF STATEMENT

C4 (slow-consumer policy — `SlowConsumerGovernor`; `SlowConsumerPolicy` and
`CatchUpService` deleted) is **SIGNED OFF WITH CONDITIONS**. The governor implements the
§7 ladder re-based on the C1 signals with every transition named, metered, logged, and
tested; all three screen conditions are discharged with code/test evidence; both
deletions are real, safe, register-disciplined, and genuinely superseded (not
rename-and-shrink); the wire behavior (code 8 disconnect, observable cooldown refusal,
forced SNAPSHOT_FIRST readmission via C3's `decideMode` — reuse, not duplication) is
process-pinned with an injected clock; the gate path is verifiably governor-free and
`EdgeSeedCompatTest` green; the walk pins the charter spine in recorded order with a
byte-equal replay. **The one BLOCKING defect is condition C4-A:** the production session
loop's `Long.MIN_VALUE` evaluation anchor overflows the cadence comparison, so the
HEALTHY→SLOW rung — and therefore the §7 warning tier and its two metric series — is
unreachable at runtime, undetected because no test drives that rung through the real
loop. C4 is NOT DONE, CT-27 must not flip PASSING, and gate-3 must not be claimed until
the one-line fix plus the server-level regression test land (the lead registers the P1
row; Finding 1 is the reproduction). The REQUIRED items (runbook/§7 doc pass C4-B;
`ErrorCode` javadoc C4-C) and the seven NOTEs (ack-feed contention watch, gap-loop
economics, walk scope, flap-test topology, multi-connection warn-reset wrinkle, eviction
history-forgetting bound, naming nits) are tracked, none gating. **C5 implementation may
start**; C4-A must land as its own commit before the C4 rows close.

— review-architect, 2026-06-12

---

## DISCHARGE ADDENDUM — C4-A and C4-C verified; verdict finalized to SIGNED-OFF (2026-06-12)

Verified against the working tree on top of `3e8ec7d` (uncommitted at review time;
`git diff --stat`: `ErrorCode.java`, `FanOutServer.java`,
`FanOutServerQuarantineTest.java`, `SlowConsumerStateMachineWalkTest.java`,
`readiness-register.md`, `contract-test-map.md`). Box owned for every Maven run
(`pgrep -f "[o]rg.apache.maven"` bracket clear before each).

### C4-A — DISCHARGED

1. **The fix is the overflow-proof next-deadline idiom, read in the diff:**
   `nextGovernorEvalMillis = Long.MIN_VALUE` compared DIRECTLY —
   `if (above && nowMillis >= nextGovernorEvalMillis)` then
   `nextGovernorEvalMillis = nowMillis + governorEvalCadenceMs`
   (`FanOutServer.java:573,611-612`). No sentinel subtraction exists anymore; any
   `nowMillis ≥ 0` satisfies `>= MIN_VALUE`, so the first warned iteration evaluates
   immediately and every subsequent evaluation is cadence-paced; `now + cadence` cannot
   overflow for any realistic clock. The comment names the P1 and the pinning test —
   the failure mode is now documented at the site that had it.
2. **The regression test is exactly the conditioned shape and is non-vacuous —
   reviewer-reproduced RED/GREEN independently** (the third reproduction, after my
   arithmetic demo and the owner's):
   - `FanOutServerQuarantineTest#sustainedQueueWarnPromotesToSlowOnTheLiveSessionLoop`
     (`:223-252`): live server, one unacked frame holds the queue at warn (threshold 1)
     with NO overflow/demotion; frozen injected clock → asserted still-HEALTHY;
     `clock.advance(10_000)` (no sleeps) → the SESSION LOOP promotes (the test makes no
     direct governor mutation — `awaitGovernorState` polls the read-only `state()`
     accessor, which performs no promotion); `edge_fanout_slow_transitions_total == 1`;
     then the ack-driven SLOW→HEALTHY exit rides the same live loop.
   - **RED:** with `FanOutServer.java` locally reverted to the committed (`3e8ec7d`)
     arithmetic (`git checkout 3e8ec7d -- …/FanOutServer.java`, broken form confirmed by
     grep at `:567/:605`), the test FAILED exactly as RR-101 records: *"the live session
     loop must run the time-driven evaluation (C4-A) — governor state is HEALTHY,
     expected SLOW"* (20.3 s, the await deadline).
   - **GREEN:** fix restored (byte-identical to the owner's tree — verified by re-grep
     and `git diff --stat` unchanged), full `FanOutServerQuarantineTest` re-run: **4/4
     green** (fresh surefire mtime 03:56–03:57), with the structured evidence visible in
     the log: `HEALTHY→SLOW reason=queue_warn_sustained atMillis=T0+10_000` — promotion
     at exactly the window edge, through the live loop — and
     `SLOW→HEALTHY reason=ack_progress`.
3. **RR-101 row verified accurate** (`docs/readiness-register.md:210`): P1, RESOLVED;
   discovery evidence cites this review's Finding 1 (arithmetic + dead-branch citation
   `FanOutServer.java:567/:605` — correct); second-agent reproduction is the owner's
   red/green with the exact failure string I independently reproduced; resolution
   describes the idiom as implemented; the generalized lesson (cadence-gated server
   behavior needs a live-loop test leg, not just direct-call unit coverage) is the right
   handoff. Charter rule 6 satisfied.
4. **Bonus coverage landed with the discharge, verified green:** the UNHEALTHY tier is
   now process-proven at the wire
   (`FanOutServerQuarantineTest#secondQuarantineWithinTheWindowEscalatesToUnhealthyAtTheWire`
   — the live `onDemotionEvent` UNHEALTHY teardown arm, code 8 with "UNHEALTHY" named in
   the diagnostic, unhealthy-cooldown refusal, auto-readmission SNAPSHOT_FIRST) and in
   the sim walk
   (`SlowConsumerStateMachineWalkTest#quarantineLimitCyclesEscalateToUnhealthyThenAutoReadmit`,
   suite now 4/4 green, reviewer-reran). This closes the walk-scope NOTE (Finding 6's
   "walk omits the UNHEALTHY tier") and the related Finding 9 constraint context: the
   CT-27..CT-30 map flips in this tree (`contract-test-map.md`, contract-qa's half, with
   `c4-contract-qa-audit.md` alongside) now sit in the SAME tree as the C4-A fix — the
   "CT-27 must not flip before C4-A" constraint is honored provided fix and flips commit
   together, which this single working tree guarantees.

### C4-C — DISCHARGED

`ErrorCode.java:42-47` read: the QUARANTINED(8) javadoc now states UNHEALTHY *shares*
the wire code, that the taxonomy is closed and golden-pinned, and that the escalation is
distinguished by the diagnostic message + governor state. The taxonomy doc no longer
under-describes. (No wire/golden file changed — javadoc only, confirmed in the diff.)

### Remaining

**C4-B only** (runbook + §7 text amendments → session-close consolidated doc pass;
REQUIRED, non-gating — tracked alongside the C3 review's Finding 1 doc-pass obligation).
The seven NOTEs stand as recorded, minus the walk-scope NOTE closed above. Full reactor
green on this tree is the lead's verified claim, accepted per the 2-vCPU discipline.

**FINAL VERDICT: C4 is SIGNED OFF.** DONE per charter §1 rule 2; the CT-27..CT-30 flips
may close with this tree's commit; gate-3's walk row stands. C5 proceeds unconditionally.

— review-architect, 2026-06-12 (discharge addendum)
