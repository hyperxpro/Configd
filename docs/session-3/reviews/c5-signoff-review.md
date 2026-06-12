# C5 Component Sign-off Review — review-architect

> **Scope (JOB A):** the C5 new-edge-bootstrap component sign-off (the adversarial proof
> component) against charter §1 rule 2's DONE-definition. As-built note:
> `docs/session-3/design/c5-bootstrap-design-note.md` (commit `a099124`, HEAD at review
> time, clean tree). Prior gates: `docs/session-3/reviews/c2-c5-design-screen.md` §C5
> (CLEARED, no conditions, NOTEs C5-1/C5-2); draft `c5-bootstrap-design-draft.md`.
> **Reviewer:** review-architect. **Date:** 2026-06-12. **Branch:** session-3-data-plane.
> Files written: this one + the RR-102 register row flip (protocol-mandated). Maven runs
> executed after confirming no competing build (`pgrep -f "[o]rg.apache.maven"` clear
> before each). Runs performed by this reviewer (fresh `install -DskipTests` of the full
> upstream chain first — stale-artifact discipline): the **RR-102 RED/GREEN reproduction**
> (§RR-102 transcript below), `BootstrapSnapshotBackpressureTest` (4),
> `SnapshotChunkResumeTest` (1), `BootstrapCutoverExactnessTest` (5),
> `EdgeBootstrapUnderSustainedWritesTest` (7), `EdgeBootstrapMidChurnTest` (3),
> `EdgeSeedCompatTest` (1), `AdversarialGateSeedSweepTest` (507 seeds, 1),
> `EdgeBootstrapUnderSustainedWritesProcessTest` (2) — **all green post-fix**. The scoped
> PIT figures (235 mutants / 80.9% / RUN_ERROR=0; governor 100%) are the committer's
> recorded claims, accepted as recorded (the C3/C4 precedent: no PIT re-run on this box;
> the only `pit-reports` on disk is a stale pre-C5 Jun-10 run in `~/ws-clean`, not
> consulted as evidence). Full-reactor green is the committer's recorded claim.

Severities: **BLOCKING** (gates DONE), **REQUIRED** (must land, tracked, does not gate),
**NOTE** (advisory). Every finding carries a prod-blocking / non-blocking flag.

## Verdict: **SIGNED-OFF**

C5 delivered exactly what the charter scoped it as: a proof, and the proof drew blood
twice. Every claim in the as-built note that I checked against code, tests, git history,
and my own reruns held — including the two load-bearing ones: the RR-102 fix is correct
under every interaction I could construct (pause exactness, cutover-after-END-only,
close-mid-pause, no transfer interleave, no livelock), and the RR-103 restraint call is
right. The non-vacuity discipline demanded by screen NOTE C5-2 is genuinely implemented
as hard asserts, the equivalence judge is the real judge run directly against a
hard-asserted pure-stream control, and the gate path is byte-identical under my own
rerun of both seed-compat witnesses. **RR-102 is hereby flipped to RESOLVED** on my own
RED/GREEN reproduction (transcript below). One REQUIRED finding (pre-existing, not
C5-introduced, non-gating): `demote()` retains the last close-resurrection site in the
codebase — the exact bug-class RR-102 just killed in the cutover tail survives in the
demotion-notice path (Finding 1; lead to register). Three NOTEs. **C6 may start.**

### Conditions of this sign-off

| # | Condition | Severity | Gate | Status |
|---|---|---|---|---|
| **C5-A** | Lead registers a row for Finding 1 (the `demote()` notice-emission close-resurrection + spurious `transport_gone` metric — `FanOutSessionCore.java:465` via `emit():504-510`, then `:473` resurrects). Fix shape: the DEMOTED_TO_CATCHUP notice is advisory — offer it best-effort without `emit()`'s refusal-means-death semantics (the SNAPSHOT_BEGIN that follows carries the demotion signal), or re-check `state == CLOSED` after the emit and stop. P3: observability lie + doctrine violation, no data-plane incorrectness post-RR-102. | **REQUIRED** (non-blocking; pre-existing C1 code, surfaced by this review's read, not by C5's tests) | Register row at next register touch; fix at next `FanOutSessionCore` touch or S6 | OPEN |

No BLOCKING conditions. CT-24 may flip PASSING (the contract-qa side of the dual
sign-off audits the map flips themselves).

---

## Check 1 — RR-102 second-agent reproduction: **DONE — RED→GREEN, row flipped to RESOLVED**

Protocol followed as registered (whole-file variant: `git diff 4cfa6da a099124` confirms
`FanOutSessionCore.java` is the ONLY file delta and the fix is its only content, so the
whole-file `git show 4cfa6da:` replacement IS the surgical revert). Transcript in the
dedicated section below. Outcome: pre-fix **RED 3/4 deterministic** with the registered
signatures (`expected: <CATCHUP> but was: <STREAMING>`; transfer never completes after
the writer drains; cutover declared while BEGIN still owed) → restore → **GREEN 4/4**,
plus `SnapshotChunkResumeTest` green in the same run (the CT-31-unchanged claim,
verified not taken on faith). One row-accuracy nuance, recorded in the flip: the row's
"both test legs RED" phrasing predates the test growing to four methods — the
replay-failure leg is green by design pre- and post-fix; 3/4 RED is the correct pre-fix
state and the registered signatures all appear.

## Check 2 — the RR-102 fix itself: **PASS** (read end-to-end; one pre-existing residual → Finding 1)

- **Pause/resume exactness.** The frame plan is immutable (`PendingSnapshotTransfer`
  holds seq + chunk list + declared bytes, `FanOutSessionCore.java:110-122`); progress
  marks advance ONLY after an accepted offer (`beginEmitted` set post-accept `:370-376`;
  `nextChunk++` post-accept `:377-383`; END gate `:384-386`). A refused offer returns
  with marks unmoved → resume is at the exact refused frame, same envelope, no chunk
  skipped or doubled. Restart is impossible: a new plan is built only when
  `pendingTransfer == null` (`:353`), and it is nulled only at completion (`:400`).
  Pinned by the 1-slot leg (every frame refused at least once; envelope exactly-once in
  order; `onSnapshotTransfer` fires exactly once, at completion —
  `BootstrapSnapshotBackpressureTest:317-369`).
- **Cutover only after END accepted.** All cutover bookkeeping (`pendingTransfer=null`,
  `catchupSnapshotOwed=false`, `onSnapshotTransfer`, `cursor=S`, in-flight clear,
  STREAMING) sits strictly after the END offer's accept (`:388-407`). `lastAckedSeq` is
  untouched on the entire path — the C1(a) self-heal discipline survives (asserted at
  `BootstrapSnapshotBackpressureTest:244-245`; `SnapshotChunkResumeTest` green).
- **Close mid-pause: terminal.** `closeWith` → CLOSED; `tick()` returns at `:246` before
  the CATCHUP arm — the orphaned `pendingTransfer` is never touched again, no frame
  emitted, no resurrection. The unconditional cutover tail is **genuinely gone**: cutover
  is now unreachable without END acceptance, and nothing in the class re-enters
  `performSnapshotTransfer` once CLOSED (`tick:245-248` is the only caller's gate).
  Replay-failure path stays down across later ticks (test `:391-397`, rerun green).
- **Demotion mid-pause / second transfer interleave: impossible by construction.**
  `demote()` is called only from `drainStreaming` (`:273,:279,:292,:314`), which runs
  only in STREAMING (`tick:256`); STREAMING is reachable only through the cutover tail,
  which nulls `pendingTransfer` first. So no demotion can arrive while a transfer is
  pending, and two envelopes can never interleave — a re-demotion (e.g. the same-tick
  ack-lag re-check after cutover, `:272-274`) starts a NEW envelope only after the
  previous END was accepted. C4 quarantine acts at the shell (connection teardown →
  `alive=false` → offers refuse → benign pause until the loop exits on the alive flag);
  it never mutates session state mid-pause.
- **Livelock hunt: none.** A forever-refusing sink costs exactly one refused offer per
  tick (O(1)); the session holds CATCHUP with the transfer owed (asserted for 50
  blocked ticks, test `:276-285`). The live loop does not spin: an emitting-or-paused
  transfer changes neither cursor nor `inFlightFrames`, so `sessionLoop`'s progress
  check parks with adaptive backoff up to `idlePollMs` (`FanOutServer.java:617-628`).
  Death of a genuinely dead transport is the shell's (writer IOException → teardown;
  edge-side staleness ladder). Residuals, neither gating: the parked re-poll adds only
  bounded latency to a paced transfer (backoff resets only on cursor/in-flight change —
  cosmetic); and a wedged-but-open transport has no server-side signal (Finding 2).
- **Residual close-resurrection in `demote()` — Finding 1 (REQUIRED).** The fix's own
  WOULD-BLOCK doctrine is not applied to the demotion notice: `demote()` emits
  DEMOTED_TO_CATCHUP through `emit()` (`:465`), whose refusal marks the session CLOSED +
  fires `onSessionClosed("transport_gone")` (`:504-510`), after which `demote()`
  unconditionally resurrects to CATCHUP (`:473`). On REASON_TRANSPORT_BLOCK the queue is
  full at that instant by definition (`:311-315`), so at the live server most
  transport-block demotions fire a spurious close metric and transit CLOSED→CATCHUP
  (→ eventually STREAMING). Pre-existing (byte-identical in `4cfa6da`), invisible to
  the sim (its sinks accept), functionally self-correcting post-RR-102 — but it is the
  last remaining "closed is not terminal" site and a standing metrics lie. Condition
  C5-A.

## Check 3 — RR-103 row accuracy + discipline ruling: **ACCURATE; RATIFIED**

Every code citation verified in `configd-consensus-core/.../RaftNode.java`: increments
post-successful-send at `:1669` (`sendAppendEntries`) and `:1721`
(`sendInstallSnapshot`); decrements ONLY in the two response handlers `:1280`/`:2137`;
reset ONLY at `becomeLeader` `:1593-1598`; the window gate silently returns at
`:1631-1634` (no metric, no log — "no metric" claim ✔); grep confirms no other
`inflightCount` mutation site. Heartbeats route through the same gated
`broadcastAppendEntries → sendAppendEntries` (`:1618-1622`, `:1187`), so the silencing
is total per peer — consistent with the registered repro (deposed node parked behind a
vote-protection regime, FOLLOWER throughout). Sharpening the row's credibility: the
adjacent comment `:1657-1661` documents this exact silencing mode for the
encoder-reject case — the kernel author understood the leak class locally and guarded
one cause; network drop is the same mechanism unguarded. **Discipline call RATIFIED:**
the kernel is S2-owned under gate-2 PIT floors + TLA twins; a drive-by C5 fix would
bypass that regime with no spec/floor accountability. The quarantine is real and
honest: the deposed-source leg judges per-source with the SAME equivalence machinery
(`EdgeBootstrapMidChurnTest:162-163`), the exclusion is named in the test javadoc
(`:38-47`) and in `settleAndJudgeEdges`' fence scoping (`:270-276`), and the leg reran
green here. Fix shape + owner (S4 with RR-095) + deterministic seed are in the row. No
register edit needed or made.

## Check 4 — equivalence-judge honesty (the charter's C5 criterion): **REAL, not ceremonial**

- **Same judge code, invoked directly.** Scenario 1 calls
  `sim.invariants().finalCheck(List.of(joiner), veteran.snapshot())`
  (`EdgeBootstrapUnderSustainedWritesTest:160`) — the identical
  `EdgeInvariants.finalCheck` (`EdgeInvariants.java:266`, byte-effect diff with
  precise-key reporting) that `sim.finalCheck()` runs against the CP leader
  (`EdgeFanOutSim.java:421-445`). The joiner≡control claim is therefore judged by the
  production judge, not a parallel weaker one, and is asserted directly rather than
  left to transitivity through the leader.
- **The control is hard-asserted snapshot-free.** `assertEquals(0,
  veteran.snapshotsApplied(), ...)` (`:152-153`), and structurally so:
  `noAckLagHealConfig()` sets `ackLagDemoteSeqs = 1,000,000` (`:84-86`) so the only
  veteran recovery is C3's TAIL resubscribe — the pure-stream side is enforced, not
  assumed.
- **C5-2 hard asserts are load-bearing.** `straddleWrites >= 1` (`:135-139`) sits
  mid-test before any judging — with `straddleWrites == 0` the test fails. The
  measurement is conservative (S sampled after the T0 tick → undercount direction), and
  the 12-tick `joiner.lag()` widening plus per-tick pump make it robust, not
  seed-lottery. Likewise hard and placed to fail: `writtenAtCutover >
  writtenAtEdgeStart` (process leg 2, `:278-282`), `dupsAcrossBootstrap > 0`
  (`:316-319`), `snapshotsApplied()==0` at every fault point (scenario 3 `:252-254`;
  mid-churn `:97-98`,`:139-140`,`:202-204`). The unique-value-per-write tripwire is real
  (`pumpAndTick:339-348`).

## Check 5 — crafted-frame matrix (`BootstrapCutoverExactnessTest`): **PASS**

- **Poisoned-redelivery leg proves byte-survival by CONTENT.** Seq S redelivered with
  DIFFERENT bytes ("POISONED"); the leg asserts cursor unchanged, zero applies, and
  `assertArrayEquals(bytes("v5"), core.get("k5").value())` (`:147-153`) — the snapshot's
  effect compared by value bytes, not version bookkeeping. The worst case (non-idempotent
  dup) is the one crafted. ✔
- **S+2-skip leg proves never-applied AND heal-at-real-cursor.** Cursor pinned at S, gap
  counted, `k7` absent (`:165-169`), AND the queued directive is
  `ReconnectNextEndpoint` carrying `resumeCursor == S` (`:170-175`) — the heal targets
  the real cursor, then the in-order redelivery applies each exactly once (`:177-183`).
  ✔
- Plus: whole-transfer duplication idempotent over effect with equal-seq-is-not-backward
  pinned (`:187-204`), and the late backward transfer refused with re-ack of the real
  cursor (`:207-221`). INV-M1 throwing mode wired (`testMode=true`, `:87`). 5/5 rerun
  green here. The C5-1 disposition holds: the matrix proves the defense-in-depth catches
  a violated mechanism, exactly as the screen framed it.

## Check 6 — gate-path neutrality: **VERIFIED (reran both witnesses)**

The dup-draw claim is true as stated for the gate path: `rng.nextDouble() < dupRate`
fires on EVERY send and predates C5 (`AdversarialNetwork.java:132`, byte-identical at
`4cfa6da`); C5 adds only `dupCount++` inside the branch and a draw-free accessor
(`:136-147`). `joinEdge` appends a roster `EdgeActor` (no RNG in its construction —
`EdgeFanOutSim.java:321-326`, `EdgeActor:115`) and is never invoked on the gate path.
My reruns: `EdgeSeedCompatTest` green (CP digest byte-identity, 3 seeds × 0-edge +
3-edge) and `AdversarialGateSeedSweepTest` green (the committed 507-seed set). One
precision nuance, no action: when a test DOES set `setEdgeDupRateForTest(1.0)`, fired
dups consume the pre-existing extra `nextInt` (`:133`) — draw sequences inside that
opted-in test differ from ambient-rate runs, which is irrelevant to gate-path
neutrality and inherent to firing dups at all; the note's "seams consume no extra
draws" is accurate for the gate path it speaks to.

## Check 7 — charter §6 rule 4 screen on the fix: **PASS**

Per-session added state = one `PendingSnapshotTransfer`: the chunked copy of one
serialized snapshot (chunks are copied slices, `EdgeSnapshotCodec.chunk:110-133`, so
≈1× body) + two progress marks — bounded, freed at completion (`:400`). Tick cost:
O(frames-accepted-this-tick) when flowing, O(1) when paused; zero O(all-sessions) work
added anywhere; the publish path is untouched (CT-22 scope unaffected — the change is
entirely inside the per-session pull engine). Aggregate residency: N concurrently-paused
bootstraps hold N snapshot copies for the pause duration (pre-fix the same bytes lived
one tick) — bounded by the C1-hardening `maxSessions` admission bound; named as NOTE in
Finding 3's margin, not a defect.

---

## Findings

| # | Finding | Severity | Prod-blocking? |
|---|---|---|---|
| 1 | **`demote()` notice-emission close-resurrection** (the last site of the RR-102 bug-class): refusal of the advisory DEMOTED_TO_CATCHUP frame under a full queue — near-certain on REASON_TRANSPORT_BLOCK — marks the session CLOSED + fires a spurious `onSessionClosed("transport_gone")`, then `demote()` resurrects it to CATCHUP (`FanOutSessionCore.java:465` → `:504-510` → `:473`). Pre-existing (identical at `4cfa6da`), self-correcting post-RR-102, no data loss; impact = double-counted close metrics + "CLOSED is terminal" doctrine violated in one path. → Condition C5-A (lead registers; P3). | REQUIRED | No (observability/doctrine) |
| 2 | **As-built note §7 overstates the stalled-transfer mitigation.** "(queue-depth gauge shows pressure)" is wrong-ish: during a paused transfer the session's in-flight accounting was cleared at demote (`:467`) and snapshot frames never enter it, so `onQueueDepth` is silent through the pause; `edge_fanout_queue_depth` is a process-wide max high-watermark anyway (`RegistryFanOutSessionMetrics.java:172-174`), not live pressure. Worse, no governor rung can fire on a wedged-but-open transport mid-pause (queue-pressure arm needs `inFlightFrames ≥ warn` — zero during pause; no repeat demotions while CATCHUP): server-side death is owed entirely to edge-side/socket teardown. The gap is correctly on the S6 handoff list — keep it there with the gauge parenthetical corrected. | NOTE | No |
| 3 | **Re-demote churn on very-deep-store bootstraps.** Post-cutover, `lastAckedSeq` stays behind by design, so the same-tick ack-lag check (`:272-274`) re-demotes any bootstrap with S − acked > 8192 (defaults), scheduling a redundant envelope until the edge's CURSOR_ACK lands — each costing a fresh serialize+chunk (O(snapshot)) now paced over real wire time. Deliberate C1(a) self-healing (comment `:392-399`; sim pins `snapshotsApplied <= 2`; edge equal-seq re-apply proven idempotent in check 5), but worth one S6 efficiency line for the 64-MiB-class stores RR-102 just made bootstrappable. | NOTE | No |
| 4 | **Scenario-4 dup witness is network-wide, not joiner-scoped.** `edgeDupCount()` counts all CP→edge dups; at rate 1.0 every joiner-bound frame is structurally duplicated so the witness is sound — but if the rate is ever lowered the assert could be satisfied by dups that never touched the joiner's cutover. Fine as written at 1.0; a comment-level caveat at most. | NOTE | No |

---

## RR-102 reproduction transcript (this reviewer = the second agent)

Box checked free before each Maven run (`pgrep -f "[o]rg.apache.maven"` → empty).
Working tree clean at `a099124` (HEAD) throughout; `git diff 4cfa6da a099124 --stat --
.../FanOutSessionCore.java` confirms the file is the commit's only main-code delta
(105 changed lines = the fix + its state), so the whole-file replacement is the
registered surgical revert.

1. **Revert:** `git show 4cfa6da:configd-distribution-service/src/main/java/io/configd/distribution/fanout/FanOutSessionCore.java > <same path>` — verified: 0 occurrences of `PendingSnapshotTransfer` in the working file; diff −87/+18.
2. **RED run (fresh build, upstreams from source):**
   `./mvnw -pl configd-distribution-service -am clean test -Dtest=BootstrapSnapshotBackpressureTest -Dsurefire.failIfNoSpecifiedTests=false`
   → **Tests run: 4, Failures: 3** — deterministic, signatures verbatim:
   - `fullyBlockedTransportHoldsTheTransferOpenWithoutClosingOrRegressing:280 ... expected: <CATCHUP> but was: <STREAMING>` — the registered close-resurrection signature;
   - `transferExceedingTheTransportQueuePausesResumesAndDeliversExactChunkSequence:194 transfer must complete once the writer drains ==> expected: <true> but was: <false>` — the registered never-completes signature;
   - `singleSlotTransportPacesEveryFrameYetTheEnvelopeStaysExactlyOnceInOrder:335 no cutover while even BEGIN is owed ==> expected: <0> but was: <6>` — premature cutover, the unconditional tail;
   - `replaySourceFailureClosesGapUnrecoverableAndStopsTheSession` green (by design pre- and post-fix; the row's "both legs" phrasing predates the test's growth to 4 methods).
3. **Restore:** `git checkout -- <file>`; tree clean; 5 occurrences of `PendingSnapshotTransfer` back.
4. **GREEN run:** `./mvnw -pl configd-distribution-service clean test -Dtest="BootstrapSnapshotBackpressureTest,SnapshotChunkResumeTest" ...`
   → **Tests run: 5, Failures: 0** (4/4 backpressure + the CT-31 transfer-level self-heal unchanged).
5. **Corroboration at the other two levels (post-fix, fresh installs):** edge-cache
   `BootstrapCutoverExactnessTest` 5/5; testkit `EdgeBootstrapUnderSustainedWritesTest`
   7/7 + `EdgeBootstrapMidChurnTest` 3/3 + `EdgeSeedCompatTest` 1/1 +
   `AdversarialGateSeedSweepTest` 1/1; edge-node
   `EdgeBootstrapUnderSustainedWritesProcessTest` 2/2 (the paced ~370-chunk
   real-socket leg — the pre-fix-impossible bootstrap — in 5.1s).

**RR-102 → RESOLVED** (row updated in the same change as this review).

---

## RR-103 discipline ruling

**RATIFIED.** Verified row-accurate at every citation (check 3). Fixing
`RaftNode.inflightCount` from a C5 edge task would have been exactly the indiscipline
the charter's ownership regime exists to prevent: the kernel carries gate-2 mutation
floors and TLA+ twin obligations that a drive-by patch satisfies by accident or not at
all. The C5 deliverable — a deterministic reproduction (seed 4242), the precise
mechanism with code citations, a fix shape for the owner, an honest test quarantine
that still extracts the per-source safety claim, and the RR-095 linkage — is worth more
to S4 than an unaccountable fix. The one debt this creates (the deposed-source leg's
full-cluster convergence is unasserted) is named in the test javadoc and the row, which
is the correct shape for carried debt.

---

## As-built note §§4-8 spot-audit (verified, not taken on faith)

Seeds/legs match the note exactly (41-44 + 77/91/101; 3 mid-churn legs; 5 crafted-frame
legs; 2 process legs — all reran green). ~64 MiB impact arithmetic checks:
`DEFAULT_TRANSPORT_QUEUE_FRAMES = 64` (`FanOutServer.java:78`) × 1 MiB default chunk
(`FanOutConfig.java:92`). The ADR-0040 TERMINAL-exit-3 routing exists
(`EdgeNodeMain.java:57,:136`). Deviations §6 are real and named where claimed
(`joiner.lag()` at the tests' `:121-129`-equivalents; re-homing delegated to
`EdgeFailoverTest` named in `EdgeBootstrapMidChurnTest:49-57`). §8's CT-24/CT-31/CT-39
dispositions are consistent with everything verified here; the map flips are the
contract-qa reviewer's half of the dual sign-off.
