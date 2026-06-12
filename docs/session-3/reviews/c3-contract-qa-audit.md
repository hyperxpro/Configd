# C3 Contract-QA Audit — row-by-row against the contract-test-map

> **Auditor:** contract-qa-engineer (Session 3). **Date:** 2026-06-12. **HEAD:** `f2d732c`.
> **Scope:** component C3 as landed (`f2d732c`: catch-up / replay / gap detection — the
> resubscribe-only recovery model per screen C3-1, the ADR-0040 poison-pill ladder per
> screen C3-2, the RR-100 restart-wedge fix in `FanOutSessionCore.decideMode`, and the
> ADR-0040 §2 negative-caching descope's executable half; as-built note
> `docs/session-3/design/c3-catchup-design-note.md`) audited against
> `docs/session-3/contract-test-map.md`. Plus two non-C3 rows the post-C2-sign-off
> hardening commit (`9595dea`) left stale in the map (CT-22, CT-34) — folded in because
> the map is the progress bar and leaving it factually behind reality is the silent
> under-reporting this lane exists to prevent.
>
> **Method (evidence discipline):** every claim below was verified by (1) **reading the
> named test bodies** in the working tree (all nine new/changed C3 suites in full), (2)
> reading the production sources where an assertion's meaning depends on them
> (`FanOutSessionCore.decideMode` + the horizon-distance metric; `EdgeClientCore`'s
> directive machinery, gap latch, and `ApplyFaultInjector` seam; `PoisonPillPolicy` in
> full; `EdgeStreamClient`'s SUBSCRIBE-cursor derivation (`quarantined ⇒ 0, else
> core.cursor()`) and `requestRebootstrap`; `C1StreamDriver.resubscribe`'s dead-sink +
> fresh-session semantics; `EdgeFanOutSim.enableEdgeRecovery`'s opt-in directive drain;
> `FanOutConfig`'s parameter order — confirming the sim's ack-lag-disable control is
> real; the rewritten `NoDeltasSinceOnConsumerPathTest` scan-root derivation), (3)
> ADR-0040 (Accepted, authored pre-implementation per the screen's hard gate — both
> screen conditions verified satisfied in its text) and the c2-c5-design-screen §C3
> rulings, and (4) the **fresh surefire reports** (timestamps 2026-06-12 01:54–01:55 —
> the lead's full-reactor verification run; the commit at 01:56:29 captured the exact
> tree the run executed on; tree confirmed clean at audit start). All named suites:
> 0 failures / 0 errors (nested-class counts summed: edge-cache
> `PoisonPillRebootstrapTest` 18/18, `EdgeClientCoreTest` 43/43).
>
> **2-vCPU discipline + a live collision, disclosed:** `pgrep -f "[o]rg.apache.maven"`
> was clean before my one targeted run (`-pl configd-distribution-service`,
> 5 suites). That run came back 13/14 with ONE failure —
> `FanOutSessionCoreBoundaryTest#freshBacklogBoundaryAtQueueFramesDecidesMode` expecting
> SNAPSHOT_FIRST, got TAIL — which turned out to be the review-architect's **RR-100
> reproduction drill in progress**: the working tree briefly carried a marked TEMPORARY
> local revert of `decideMode` to the C1 rule (verified in `git diff`; the revert touches
> ONLY the cursor-0 branch). Consequences, all to C3's credit: (a) the rewritten boundary
> tripwire demonstrably goes RED on the old rule — direct, accidental corroboration of
> its probative value; (b) the 13 passing tests (incl. all three new distribution-service
> C3 suites and the CT-22 guard) do not touch the reverted branch, so their green is
> valid evidence even on the drilled tree; (c) the surefire report for
> `FanOutSessionCoreBoundaryTest` under `configd-distribution-service/target/` is now the
> drill-state RED one — anyone reading reports after this audit must check timestamps
> against the drill (the HEAD-true green run is the 01:54 one, captured at commit).
> No further Maven was run against the drilled tree. This accidental observation is NOT
> the owed second-agent RR-100 reproduction (that protocol names the edge-node restart
> test and the wedge observation; it remains owed at sign-off).

## Surefire evidence snapshot (all green, 2026-06-12 01:54–01:55, tree == f2d732c)

| Suite | Tests | Failures |
|---|---|---|
| `ReplayHorizonBoundaryTest` (distribution-service) | 5 | 0 |
| `CatchUpProtocolTest` (distribution-service) | 2 | 0 |
| `SnapshotChunkResumeTest` (distribution-service) | 1 | 0 |
| `FanOutSessionCoreBoundaryTest` (rewritten tripwire) | 4 | 0 |
| `PoisonPillRebootstrapTest` (edge-cache, 5 nested classes) | 18 | 0 |
| `EdgeClientCoreTest` (incl. new `$GapResubscribeDirective` 3, `$DisconnectedRebootstrapDirective` 3) | 43 | 0 |
| `PoisonPillRebootstrapTest` (edge-node, PROCESS) | 2 | 0 |
| `MonotonicReadAcrossEdgeRestartTest` (edge-node, PROCESS) | 1 | 0 |
| `EdgeReBootstrapOnDisconnectTest` (edge-node, PROCESS) | 2 | 0 |
| `NotSubscribedReadTest` (edge-node, PROCESS) | 1 | 0 |
| `EdgeHttpServerTest` (incl. the new not-subscribed matrix) | 12 | 0 |
| `EdgeReBootstrapOnDisconnectTest` (testkit, SIM) | 1 | 0 |
| `EdgeGapRecoveryTest` (testkit, SIM) | 3 | 0 |
| `MonotonicReadAcrossEdgeRestartTest` (testkit, C2 half — unchanged) | 2 | 0 |
| `EdgeSeedCompatTest` / `EdgeAdversarialGateSeedSweepTest` (digest/sweep preservation) | 1 / 1 | 0 |
| `NoDeltasSinceOnConsumerPathTest` (CT-22, structural roots) | 2 | 0 |

Targeted re-run at audit time (pre-drill-discovery): `CatchUpProtocolTest`,
`ReplayHorizonBoundaryTest`, `SnapshotChunkResumeTest`, `NoDeltasSinceOnConsumerPathTest`
all green independently.

## Row-by-row findings

### CT-06 (DISCONNECTED re-bootstrap) — UNIMPLEMENTED → **PASSING** ✅ FLIPPED

The row's three-part chain (trigger → real re-bootstrap → recovery) is closed across three
levels that cite each other coherently. SIM (the row-named test, read in full): a partitioned
edge walks the ADR-0039 frontier ladder on the LOGICAL clock — no wall-clock sleeps for a 30s
threshold — and the DISCONNECTED **entry** fires exactly once (staying DISCONNECTED across
2,000 further ticks does not re-fire; non-vacuity in the negative direction), drives a REAL
`C1StreamDriver.resubscribe` at the **CURRENT cursor** (the C3-1/design-§7.3 rule — cursor 0
is the poison path's), and after heal the edge converges to the authoritative version with
the frontier back to CURRENT and the missed writes present. Core: the entry-transition
detector is boot-safe (fresh core ticking for 50s fires nothing — process start is C5
bootstrap) and reconnect-re-seeded (an entry observed while disconnected cannot bounce a
fresh connection), and re-arms after a heal (`EdgeClientCoreTest$DisconnectedRebootstrapDirective`).
PROCESS: the composed hook runs `requestRebootstrap` FIRST + the injected observer second
(the composition contract — C2's metric observer is not silently replaced), and
`requestRebootstrap` on a LIVE session tears down, re-SUBSCRIBEs, and keeps converging.
Production detail verified at the source: `EdgeStreamClient` derives the SUBSCRIBE cursor
from core state at connect time, never from one-shot directive memory — a failed connect
cannot lose a forced re-bootstrap. Honest fixture corner, recorded in the row: the process
leg triggers `requestRebootstrap` directly rather than waiting out a wall-clock DISCONNECTED
transition; the transition→hook chain is C2's pinned metrics test + the SIM leg. "Regional
relay" stays flagged as superseded topology language for the consolidated pass.

### CT-13 (monotonic reads across edge restart) — PARTIAL(unit) → **PASSING** ✅ FLIPPED

The exact owed half landed, and it is the session's best argument for row-mandated tests:
**it found RR-100 on its first run** (every production edge restart would have wedged at
version 0 — the SEC-017 epoch floor REPLAY_REJECTing tail-redelivered old-epoch deltas
behind C1's cursor-0 TAIL decision, plus the ring-genesis hole). The test (read in full):
real `EdgeNodeMain` restarted on the SAME data dir; the fixture asserts nothing but
`epoch.lock` persists (cache genuinely lost); a held pre-restart cursor REFUSES with the
consistent refusal on EVERY response through the restart/re-bootstrap window (the poll
helper enforces per-response: non-200 ⇒ 404 + `X-Configd-Refused: cursor-behind`; 200 ⇒
version ≥ cursor — the "never serve pre-crash old data" clause is checked on each
iteration, not just at the end); then serves the post-restart authoritative bytes at ≥
cursor; incarnation #2's core provably re-applied to ≥ the held cursor; and the recovered
edge converges on NEW writes (full recovery, not a one-off). The decideMode fix this test
forced is itself tripwired (`freshBacklogBoundaryAtQueueFramesDecidesMode`, rewritten — and
accidentally proven RED-on-revert during this audit, see header). Honest nuance recorded:
the "kill" is `shutdown()` in the established in-JVM process composition — equivalent for
cache loss since the store is memory-only by design. RR-100's second-agent reproduction
stays owed at the C3 sign-off (register protocol; review-architect flips the register row).

### CT-16 (edge gap detection incl. GAP→replay) — PARTIAL(unit) → **PASSING** ✅ FLIPPED

The owed orchestration exists and its strong claims survived scrutiny. Core seam
(`$GapResubscribeDirective`): a detected gap queues `ReconnectNextEndpoint(cursor)` ONCE per
wedge (a second gapped notify counts the gap but does not spam a directive — latch
verified), and the directive is **suppressed while a snapshot is in flight** in both
suppression modes (post-`DEMOTED_TO_CATCHUP` and `SNAPSHOT_FIRST` handshake), re-arming
after the snapshot lands — so C1's in-session heal stays primary and a racing gap cannot
bounce a healthy connection. The sim leg's non-vacuity controls are REAL, verified at the
source level: `noAckLagHealConfig()` sets `ackLagDemoteSeqs = 1_000_000` (confirmed against
`FanOutConfig`'s field order — the C1 ack-lag heal is provably out of the loop, so the
recovery exercised is C3's resubscribe and nothing else); `enableEdgeRecovery` is opt-in
and wires the core's directives to `C1StreamDriver.resubscribe`, which kills the old sink
(frames from the torn-down session can never leak into the edge) and runs a FRESH
production `FanOutSessionCore` through the real TAIL/SNAPSHOT_FIRST decision. Within-horizon
recovery asserts **zero new snapshots** (a snapshot would mean the server wrongly chose
re-bootstrap); beyond-horizon asserts the ring genuinely lapped (fixture assert) and the
recovery snapshots; the 20-seed adversarial sweep runs safety invariants every tick with
recovery live on all edges and asserts the recovery actually fired (`resubscribes > 0`).
Honest residual, named in the row (the CT-23 precedent): no literal wire-induced GAP at
process level — the production server provably never emits one on a healthy socket (that is
CT-17/CT-18's guarantee), so the GAP trigger is pinned at the same core seam the process
drain routes through, and the directive→teardown→resubscribe shell machinery is
process-proven over real sockets by the poison/re-bootstrap tests.

### CT-31 (catch-up protocol incl. horizon boundary + chunk resume) — PARTIAL(unit) → **PASSING** ✅ FLIPPED (against the renegotiated resume clause)

Three obligations, three resolutions:

1. **Protocol selection** (`CatchUpProtocolTest`, read in full): gap<window → pure delta
   replay (no `SnapshotBegin`; exactly the missed tail, per-seq contiguity asserted);
   gap>window → genuinely multi-chunk snapshot (chunkCount > 1 asserted at a forced small
   chunk size; the 1 MiB production chunking + per-chunk CRC stays codec-pinned by CT-41)
   then deltas from snapshotSeq+1. The §7 "WAL-delta replay" wording is reconciled per
   ADR-0034 in the test's javadoc — a consolidated-doc-pass item, recorded.
2. **Horizon boundary under concurrent writes** (`ReplayHorizonBoundaryTest`, read in
   full): the ±1 matrix at the ring-retention edge with writes flowing through every phase,
   judged by full-state byte-equality against an authoritative writer model
   (exactly-once-over-effect — both directions: no divergent value, no missing key, no
   extra key); the screen C3-1 condition is genuinely met — the lapped-after-TAIL race is
   **deterministically forced** (TAIL decided at the horizon edge, the writer then laps the
   cap-8 ring before the first drain; fixture-asserted lap) and the self-heal is pinned
   step-by-step (GAP → demote(REASON_GAP), no NOTIFY leaks across the gap → snapshot →
   converged). The subscribe-mode decision metric is pinned to exact horizon distances
   (−1/0/+2, empty-ring = cursor+1; exactly one decision per subscribe).
3. **"Resume on failure" — RENEGOTIATED, accepted as the row's resolution.** This is the
   exact disposition my C1 audit's REQUIRED gap 2 demanded ("implement or renegotiate").
   `SnapshotChunkResumeTest` pins the renegotiated behavior end to end: the transfer is
   unacknowledged by design (`lastAckedSeq` stays at 3 after the transfer — asserted, with
   the C1(a) fix cited as what makes the loss recoverable), a lost transfer re-demotes via
   ack-lag and is re-sent WHOLE (every re-send carries the full state at the same seq —
   idempotent), the loop **quiesces once acked** (no re-send after CURSOR_ACK; an acked
   transfer is never re-sent), and the post-heal tail resumes contiguously. The rationale
   is on the record in the test javadoc + design note §7.2 (chunk-level resume = per-chunk
   acks + transfer-resume session state = new wire surface and new failure modes, against
   a sim-measured 100% heal rate for idempotent re-send). What this flip does NOT grant:
   architecture §7's literal text still says "resume on failure" — the consolidated doc
   pass at session close owes the amendment, and the review-architect's C3 sign-off
   ratifies the deviation via the as-built note §7 (where it is honestly listed). Both are
   tracked in the row.

`CatchUpService` is now fully superseded shelfware (zero consumers) — its deletion is
C4-1's register-row condition, already on the screen's record.

### CT-32 (negative caching) — PARTIAL(unit) → **ADR-RENEGOTIATED(adr-0040)** ✅ FLIPPED/CLOSED

Closed by ADR-0040 §2 (Accepted; the descope pre-ratified at the design screen with its
conditions verified met: the `BloomFilter` disposition is named in the ADR — retained
tested-but-unwired for S7's orphan sweep — and the row flip is the ADR's own directive),
**with executable evidence**, matching the CT-17/CT-25 closure standard. The descope's
load-bearing premise is process-proven on a genuinely prefix-subscribed edge
(`NotSubscribedReadTest`, read in full — the first process run with a non-empty prefix
subscription, incidentally discharging part of CT-25's recorded residual): in-slice miss =
plain authoritative 404 with NO refusal header (the HAMT miss path IS the negative cache);
out-of-slice read on a key that EXISTS upstream — and whose chain version passed through
this very edge — refuses distinctly (`404 + X-Configd-Refused: not-subscribed`, own counter
moved at a live `/metrics`); strong-read keys keep 503 precedence (`secure/` is outside
`svc/` and must never degrade to a mere not-subscribed 404 — asserted, and asserted NOT to
increment the not_subscribed series). The unit matrix additionally pins the refusal decided
BEFORE the store is consulted (`EdgeHttpServerTest`).

### CT-33 (poison pill) — PARTIAL(unit) → **PASSING** ✅ FLIPPED (against ADR-0040 §1's narrow policy)

Status choice, reasoned: ADR-0040 directs "CT-32 → ADR-RENEGOTIATED; CT-33 → the narrow
policy's tests" — i.e., this row is judged against the renegotiated clause and closed by
evidence, the CT-12 pattern. The clause-as-renegotiated is fully proven; the kept fragment
of the original §8 text (`configd.edge.poison_pill` metric) is implemented under its exact
name. Core (18, read in full): the ladder's every rung and its edges — bounded retries
resubscribing at the CURRENT cursor; quarantine at maxRetries with the metric + forced
cursor-0 re-bootstrap; recovery ONLY at a snapshot ≥ the poison seq (one short releases
nothing); batch abort after a throw WITHOUT polluting the honest gap series (the seq-3
sibling is never offered against the unadvanced cursor — asserted via `gapsDetected() == 0`);
per-seq retry isolation; progress-resets (above resets, below does not); different-seq
failure exits quarantine as a fresh ladder; BOTH terminal conditions; terminal latched
(exactly once, counters frozen, state preserved for post-mortem), emitted BEFORE exit,
inert-but-serving until the shell exits. Scope edges are the non-vacuity backbone: an
invalid SIGNATURE never touches the policy (F-0052's fail-closed is not mis-classified as
poison), and a snapshot failure OUTSIDE a quarantine gets the bounded ladder, not instant
death. PROCESS (2): the recovery leg engineers SNAPSHOT_FIRST over a real socket
(queueFrames-4 endpoint, backlog 7 — though post-RR-100 the cursor-0 rule alone would also
snapshot) and proves the poisoned key served FROM THE SNAPSHOT with the process LIVING;
the terminal leg proves the injected `terminalAction` runs EXACTLY once (no hot loop), at
version 0 (no divergence), with both poison counters scrape-pinned at a live `/metrics`
before exit. The `ApplyFaultInjector` seam is justified by ADR-0040's own opaque-bytes
premise (no real delta can throw; everything downstream of the injected throw is
production code) — the `loadSnapshotForced` precedent, accepted. Named gap carried in the
row (design §8, non-blocking): the literal forked-JVM exit-code-3 observation is untested;
the wiring is one lambda pinned by the recorder seam.

### CT-22 (deltasSince guard) — PARTIAL(unit) → **PASSING** ✅ FLIPPED (fix landed in 9595dea, verified here)

Not a C3 change, but the map was stale against reality and the row's recorded closure
condition is met — structurally exceeded: `SCAN_ROOTS` is now DERIVED from the reactor
pom's `<modules>` (every `<module>/src/main/java`, so `configd-edge-node` and any FUTURE
module are covered by default) plus configd-testkit's test tree (the sim drivers);
`EXEMPT_MODULES` is empty and justification-bearing by format; the tripwire test asserts
scanned == modules − exemptions with a pom-parse sanity anchor. The gap CLASS my C1 and C2
audits each caught one instance of is now closed by construction. 2/2 green in the lead's
run AND in my targeted run today.

### CT-34 (hot-path law) — stays **PARTIAL(unit)**, evidence upgraded ⚠ deliberately NOT flipped

The C2 audit's REQUIRED gap 2 is three-quarters discharged: `gates/jmh-gc-check.sh` exists
(9595dea), re-runs the benchmark `-prof gc`, saves a provenance-headed artifact
(`docs/session-3/captures/ct34-jmh-gc-check.txt` — verified present, headed with UTC/SHA/
size/gated-vs-trend), asserts < 1 B/op on the structurally-zero legs with
parse-failure-is-RED non-vacuity, and was red-path drilled (the drill caught a real parser
bug — the ± error column false-green — before commit; exactly why drills exist).
**Refused the flip**, on the hardening commit's own discipline ("stays PARTIAL until
gate-3 assembly cites the artifact"): the row's level is GATE and `gates/gate-3.sh` still
does not exist; a mechanical check nothing invokes is not yet a gate.

### Other rows touched (notes-only updates, statuses unchanged)

- **CT-38** (PARTIAL): C3's series all emitted and pinned — `configd_edge_poison_pill_total`
  (the checklist item, done), `configd_edge_poison_pill_terminal_total`,
  `edge_poison_retries_total`, `edge_read_refusals_not_subscribed_total` (eager
  registration + live-scrape assertions), and the server-side subscribe-decision series
  with exact horizon distances. Owed list shrinks again; the row stays open for the
  staleness histogram, CT-27..30 counters, V2 probe histograms, and the consolidated gate.
- **CT-24** (UNIMPLEMENTED): honest progress note added — the C5 bootstrap MECHANISM is now
  the production rule (cursor-0 ⇒ SNAPSHOT_FIRST, the RR-100 fix, wire- and
  restart-proven); what C5 owes is the adversarial straddle proof, unchanged in kind.

## Rows flipped (old → new)

| Row | Old | New |
|---|---|---|
| CT-06 | UNIMPLEMENTED | PASSING |
| CT-13 | PARTIAL(unit) | PASSING — the wedge-finder (RR-100) |
| CT-16 | PARTIAL(unit) | PASSING |
| CT-22 | PARTIAL(unit) | PASSING (structural fix, 9595dea) |
| CT-31 | PARTIAL(unit) | PASSING (against the renegotiated resume clause; doc-pass amendment owed) |
| CT-32 | PARTIAL(unit) | ADR-RENEGOTIATED(adr-0040) — with executable evidence |
| CT-33 | PARTIAL(unit) | PASSING (against ADR-0040 §1's narrow policy, per the ADR's row direction) |

## Rows deliberately NOT flipped (refusals, each with the named reason)

- **CT-34** stays PARTIAL(unit) — the mechanical jmh-gc check + artifact now exist and are
  drilled, but a GATE row needs the gate: `gates/gate-3.sh` is unwritten, and the hardening
  commit itself defers the flip to the assembly citing the artifact.
- **CT-02** stays PARTIAL(unit) — untouched by C3; the Session-5 real-latency p99
  measurement remains the closing condition (re-affirmed, no change).
- **CT-39** stays PARTIAL(unit) — C3 strengthens the recovery story the Compose scenarios
  will exercise, but the row's closing condition (C6 Compose E2E) is unchanged.
- **CT-24 / CT-27..30** stay UNIMPLEMENTED — C5/C4 lanes; C3 evidence noted where it
  genuinely moves them (CT-24's mechanism note), nothing more.

## REQUIRED / tracked gaps found

1. **RR-100 second-agent reproduction (blocking for the register row, not for the map
   flip):** owed at the C3 sign-off per the register protocol (revert `decideMode`,
   re-run the edge-node `MonotonicReadAcrossEdgeRestartTest`, observe the wedge, restore).
   My audit's accidental run against the reviewer's drill-reverted tree confirmed the
   boundary tripwire goes RED on the old rule — corroboration, but NOT the named
   reproduction (different test, different observation). The review-architect also owes
   the explicit ratification of the `decideMode` supersession of C1's pinned
   backlog-vs-queueFrames decision (design note §7.1) and of the CT-31 resume-clause
   renegotiation (§7.2) at sign-off.
   > *Reconciliation (2026-06-12, before this audit closed):* **DISCHARGED in parallel** —
   > the drill I collided with WAS the named reproduction: the review-architect reverted
   > `decideMode`, observed the registered wedge verbatim (edge wedged at version 0,
   > cursor-behind refusals never clearing, 45.75 s deadline failure), restored, and
   > confirmed green (fresh surefire 02:07). Register row RR-100 is now **RESOLVED** with
   > the reproduction transcript (`c3-signoff-review.md`); the tree is restored (verified:
   > `git diff` clean on `FanOutSessionCore.java` at audit close). The §7.1/§7.2
   > ratifications remain with the review-architect's half of the dual sign-off.
2. **Consolidated doc-pass ledger (for session close), now three items against
   architecture §7/§8:** §7 chunk-level "resume on failure" → transfer-level self-healing
   (rationale: `SnapshotChunkResumeTest` javadoc / design §7.2); §7 "WAL-delta replay"
   wording → ADR-0034 reconciliation (`CatchUpProtocolTest` javadoc); §8 negative-caching
   + poison-pill sections → ADR-0040 pointers (the ADR's own "amended by reference"
   clause). Plus the standing "regional relay" superseded-topology flag (CT-06).
3. **Surefire report hygiene after the RR-100 drill (housekeeping):** my targeted run
   against the drill-reverted tree briefly left a RED
   `FanOutSessionCoreBoundaryTest` report in `configd-distribution-service/target/`.
   > *Reconciliation (before this audit closed):* already overwritten — the
   > review-architect's post-restore re-run left the report GREEN (4/4, mtime 02:08,
   > restored tree). Nothing owed.

## Defects found in tests' probative value

- **None blocking.** The C3 suites' distinguishing virtue is that their strong claims
  carry their own controls, and the controls checked out at source level: the
  ack-lag-disable in `EdgeGapRecoveryTest` is real (`ackLagDemoteSeqs=1_000_000`, verified
  against `FanOutConfig`'s field order); the zero-new-snapshots assertion makes the
  within-horizon leg falsifiable; the lapped-ring fixture asserts the lap actually
  happened; the poison scope edges assert the policy does NOT fire (signature rejections,
  out-of-quarantine snapshot failures); the boundary tripwire was accidentally
  demonstrated RED-on-revert during this audit.
- Two **fixture limitations recorded in rows, not defects** (each justified in the test's
  own javadoc): CT-06's process leg triggers `requestRebootstrap` directly (the wall-clock
  transition is SIM-pinned on the logical clock — the no-sleeps discipline, correctly
  applied); CT-16 has no literal wire-induced GAP at process level (the production server
  provably cannot emit one — manufacturing it would mean breaking CT-18's guarantees).
- One **observation for C4** (non-blocking): `EdgeGapRecoveryTest`'s adversarial sweep
  asserts `converged >= seeds/2` given heal — a deliberately loose liveness bucket
  consistent with the gate's RR-095 characterization. When C4's quarantine ladder lands on
  top of recovery, this floor is worth revisiting upward.

## New summary line (recounted: 27 + 5 + 0 + 5 + 3 + 1 = 41)

```
CONTRACT-MAP-SUMMARY: total=41 passing=27 partial=5 failing-captured=0 unimplemented=5 adr=3 na=1
```

## Sign-off

The contract-qa sign-off line in `c3-catchup-design-note.md` may be marked against this
audit: C3's claims in the as-built note were verified test-by-test and are accurate as
stated — the §7 deviations are each honestly recorded where the note says they are, the §8
gaps are real and none is silently load-bearing for a flip, and every C3-owned map row
(CT-06/13/16/31/32/33, the note's own §8 list) is now closed on read-and-run evidence.
Conditions that ride the review-architect's half of the dual sign-off: the `decideMode`
supersession ratification and the CT-31 renegotiation ratification. The RR-100
second-agent reproduction completed in parallel with this audit (register row RESOLVED —
see gap 1's reconciliation note).
