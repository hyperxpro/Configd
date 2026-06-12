# C5 Contract-QA Audit — row-by-row against the contract-test-map

> **Auditor:** contract-qa-engineer (Session 3). **Date:** 2026-06-12. **HEAD:** `a099124`.
> **Scope:** component C5 as landed (`a099124`: the new-edge bootstrap adversarial proof —
> no new machinery except the RR-102 fix in `FanOutSessionCore.performSnapshotTransfer`;
> RR-102 found+fixed, RR-103 registered OPEN; as-built note
> `docs/session-3/design/c5-bootstrap-design-note.md`, §8 row claims) audited against
> `docs/session-3/contract-test-map.md`. Plus the RR-102 retro-sweep this finding forces:
> every PASSING row whose evidence contains a snapshot transfer that predates the fix.
>
> **Method (evidence discipline):** every claim below was verified by (1) **reading the
> named test bodies in full** (`EdgeBootstrapUnderSustainedWritesTest`,
> `EdgeBootstrapMidChurnTest`, `BootstrapCutoverExactnessTest`,
> `BootstrapSnapshotBackpressureTest`, `EdgeBootstrapUnderSustainedWritesProcessTest`,
> `SnapshotChunkResumeTest`), (2) reading the production/harness sources where an
> assertion's meaning depends on them (the `FanOutSessionCore` RR-102 diff vs `4cfa6da`
> in full — the `PendingSnapshotTransfer` high-water state, the END-acceptance-gated
> cutover, `lastAckedSeq` untouched; `EdgeInvariants` in full — the per-tick throwing
> invariants and `finalCheck`'s effect-equality semantics; `EdgeFanOutSim.tick()` phase
> order + the `joinEdge`/`setEdgeDupRateForTest`/`edgeDupCount` seam diff;
> `AdversarialNetwork`'s dup counter; `C1StreamDriver`'s lazy-subscribe and `SimSink.offer`
> semantics; `EdgeActor.lag()`), (3) the charter §4 C5 (:138-141), the
> c2-c5-design-screen §C5 (CLEARED, NOTEs C5-1/C5-2), the design note's §6 deviations /
> §7 gaps, and the register rows RR-102/RR-103 as committed, and (4) **fresh targeted
> surefire** (below).
>
> **2-vCPU discipline:** `pgrep -f "[o]rg.apache.maven"` verified CLEAR before EACH of my
> two runs (no review-architect drill collision observed; no RED encountered anywhere —
> nothing to disclose under the C4-audit precedent). Module-scoped targeted runs only
> (`-pl configd-distribution-service,configd-edge-cache,configd-testkit`, then
> `-pl configd-edge-node`); no full reactor, no PIT (the scoped-PIT figures — 235 mutants
> 80.9% killed RUN_ERROR=0, governor 100% — and the 507-seed sweep byte-identity are taken
> from the commit/register record, not re-run).

## Surefire evidence snapshot (all green; my runs 05:31–05:33)

| Suite | Module | Tests | Failures |
|---|---|---|---|
| `BootstrapSnapshotBackpressureTest` | distribution-service | 4 | 0 |
| `SnapshotChunkResumeTest` (CT-31 heal unchanged check) | distribution-service | 1 | 0 |
| `BootstrapCutoverExactnessTest` | edge-cache | 5 | 0 |
| `EdgeBootstrapUnderSustainedWritesTest` (seeds 41-44, 77, 91, 101) | testkit | 7 | 0 |
| `EdgeBootstrapMidChurnTest` (seed 4242) | testkit | 3 | 0 |
| `EdgeSeedCompatTest` (gate-path byte-identity) | testkit | 1 | 0 |
| `EdgeBootstrapUnderSustainedWritesProcessTest` (PROCESS, 5.8 s) | edge-node | 2 | 0 |

Total 23/23. The process run's console carries the known RR-099 SEVERE `monotonic_read`
spam (routine cursor-behind refusals during the fence poll routed through the monitor) —
registered P3 noise, not a C5 failure; disclosed for the record.

## Row-by-row findings

### CT-24 (charter §4 C5 — zero-state join under sustained writes) — UNIMPLEMENTED → **PASSING** ✅ FLIPPED

The last UNIMPLEMENTED row on the map, and the flip survives the hard non-vacuity bar I
set against the three screen-mandated questions:

**(a) Is the judge the V1 machinery, not a parallel weaker judge? YES — verified at
source.** The per-tick safety invariants (`EdgeInvariants.checkAll`: per-edge version
monotonicity, per-key no-stale-overwrite via full-store diff, read-side INV-M1 through the
test-mode monitor wired into the actor's read store) run inside every `sim.tick()` and
THROW. End-of-run judging is `EdgeInvariants.finalCheck` — the same code the gate sweep
uses — comparing effect (key set, value bytes, store version; per-key provenance stamps
correctly exempted per the ADR-0028 snapshot-stamp rationale in its javadoc, with the
future-stamp sanity guard). Scenario 1 states the charter's equivalence claim *directly*:
`sim.invariants().finalCheck(List.of(joiner), veteran.snapshot())` — the
snapshot-bootstrapped joiner judged against a pure-stream control by the SAME code. The
control's purity is not assumed: `veteran.snapshotsApplied() == 0` is hard-asserted AND
structurally forced — I verified the 5th `FanOutConfig` parameter of
`noAckLagHealConfig()` is `1_000_000` (ack-lag heal provably out of the loop, the
EdgeGapRecoveryTest control discipline), so the only recovery is C3's TAIL resubscribe.

**(b) Are the C5-2 hard asserts genuinely load-bearing? YES — including the one subtle
measurement question.** `straddleWrites >= 1` is asserted per seed; I checked the
measurement's exactness rather than taking the comment's word: `s` is captured after the
T0 tick, and the sim's phase order (CP applies in phase 1, `streamDriver.drive` — which
lazily subscribes the joiner and emits the transfer at the source store's *current*
version — in phase 4, no CP applies afterward in the same tick) makes the post-tick store
version exactly the transfer's S; any deviation could only make the measured straddle an
*undercount* of the true straddle, so the `>= 1` assert is sound, never optimistic. The
window is genuinely widened (12 `lag()` ticks — verified `lag()` stops inbox processing
while frames queue; the deliberate slow-joiner shape, deviation 1 honestly named).
`dupsAcrossBootstrap > 0` (seed 101) is backed by a counter that increments only on an
actual duplicate enqueue in `AdversarialNetwork`, and the rate seam is RNG-neutral as
claimed (the dup draw happens on every send regardless of rate — verified in the diff;
`EdgeSeedCompatTest` green corroborates). `snapshotsApplied() == 0` is hard-asserted at
both lost-transfer fault points (scenario 3 after 30 partitioned ticks, with
`currentVersion == 0`; mid-churn leg 3 after the kill+cut churn) — the transfer is
*proven* lost before the heal is allowed to win. `writtenAtCutover > writtenAtEdgeStart`
is hard on the process paced leg. Scenario 1's `snapshotsApplied() <= 2` upper bound is a
nice extra pin (at most one channel-dup re-apply; re-sends impossible with the heal
disabled).

**(c) Does the double-apply tripwire actually catch a double-apply? Reasoned through —
yes at SIM level, and at PROCESS level with one honest scope note.** Every write carries a
monotonic per-write-unique value over 8 keys. In the SIM, a duplicate application with
different effect (an older unique value resurrecting over a newer one) is caught at the
tick it happens — the per-key no-stale-overwrite invariant diffs the full store every tick
and throws — and a duplicate surviving to the end diverges `finalCheck`'s byte equality.
The one theoretical escape (resurrect + newer re-overwrite applied within a single
inbox-drain tick) is closed one level down: the core crafted-frame matrix
(`BootstrapCutoverExactnessTest`) proves a redelivered seq S with poisoned different bytes
is STALE-refused with the snapshot's effect surviving byte-identically — the core cannot
apply a stale frame at all. At PROCESS level there is no per-tick checker; the final byte
sweep catches any divergence persisting to the fence, and transient cutover-window
double-applies are pinned by the counters instead (`gapsDetected == 0`,
`snapshotsApplied == 1` exactly, `backwardSnapshotsRefused == 0`,
`currentVersion == fenceSeq`) plus the same core matrix. Recorded in the row as honest
scope, not hidden.

**The proof did the charter's job:** it found RR-102 (P1) in the mechanism itself — the
burst-emitted transfer tore against the bounded transport, was resurrected to STREAMING,
and routed the edge into the ADR-0040 ladder ending TERMINAL; stores over
`transportQueueFrames × snapshotChunkBytes` could never bootstrap. The fix is the only
main-code change in C5 and I read it in full: a refused snapshot-frame offer is
WOULD-BLOCK (pause at the exact frame — BEGIN / chunk index / END — resume next tick,
same envelope), cutover bookkeeping (cursor=S, STREAMING, `onSnapshotTransfer`) runs only
after END is ACCEPTED, `lastAckedSeq` untouched. `BootstrapSnapshotBackpressureTest` pins
all of it deterministically (the test plays the writer): exact envelope (one BEGIN, every
declared chunk in order, one END, nothing interleaved, chunk count > queue capacity
asserted as its own non-vacuity), the fully-blocked transport holding CATCHUP at cursor 0
for 50 ticks without closing or prematurely cutting over, the 1-slot transport pacing
EVERY frame with `onSnapshotTransfer` exactly once at END acceptance (and asserted ZERO
before — the premature-completion mutant dies), the straddle writes emerging as the
contiguous tail at exactly S+1, and the replay-source failure staying down (the
resurrection arm cannot return). The byte-identical-when-never-refused claim is
corroborated by `SnapshotChunkResumeTest` green (always-accepting sink). RR-103 (P1,
consensus inflight-window leak) is registered OPEN with a deterministic repro and
correctly NOT fixed (S2-owned kernel); the mid-churn deposed-source leg is judged
per-source — a quarantine, not a weakened judge: the same test still asserts full-cluster
convergence for the healthy-source veteran, and legs 1 and 3 judge the full roster
against the post-churn leader.

**Level demand met:** SIM (10 tests over the production `FanOutSessionCore` +
`EdgeClientCore` via `C1StreamDriver`), core crafted frames (5), server core under
bounded transport (4), PROCESS (2 — real `EdgeNodeMain`, real `ConfigdServer`, real
sockets, leg 1 at production `FanOutConfig.defaults()`, leg 2 genuinely paced:
2 KiB chunks / 8-frame queue / ~370 chunks mid-storm, the leg that cannot complete
pre-fix).

**Deviations (§6) checked against the code — all honest, none silently load-bearing:**
(1) sim straddle widening via `lag()` is real widening, and the literal
big-store/small-chunks lever genuinely lives at core + process level; (2) the dup seam is
the only way to *guarantee* a cutover-window dup (C5-2 demands a hard assert; ambient
2-5% cannot); (3) the re-homing delegation to `EdgeFailoverTest` is named in the
mid-churn javadoc and that test exists at PROCESS level (CT-39's evidence); (4) the
process test over-delivers the draft. §7 gaps verified real and correctly non-blocking
(RR-103 in the register; no stalled-transfer gauge — S6 candidate; the
`settleAndJudge` fence rationale is documented in its javadoc and is a pre-existing
characterization, not a C5 regression).

### CT-31 (PASSING since C3) — evidence AUGMENTED + flip-time claim honestly re-scoped, status unchanged

The instruction case: the C3 audit's flip predates RR-102, and the flip note's renegotiated
"transfer-level self-healing … sim measured 100% heal" claim deserved a hard look. Finding:
the renegotiated heal had a then-unknown **unhealable** failure mode — a transfer wider
than the transport queue's free slots tore deterministically on *every* re-send (the heal
loop re-sends the same burst; each re-send tears identically; the edge poison-ladders to
TERMINAL). No flip-time evidence could have seen it: `SnapshotChunkResumeTest`'s recording
sink never refuses, the sim's `SimSink.offer` returns true unconditionally ("the sim
transport never blocks" — read at source; this is also why the 507-seed gate never caught
RR-102), and every wire-level snapshot leg shipped transfers that burst-fit the 64-frame
queue. The row note now: (1) scopes the "100% heal" measurement to loss-in-transit, NOT
backpressure tears; (2) records that the fix leaves `lastAckedSeq` untouched so this row's
heal mechanism is mechanically unchanged (`SnapshotChunkResumeTest` green in my run); and
(3) cites the new paced-transfer evidence (`BootstrapSnapshotBackpressureTest` +
the process leg 2). Status PASSING stands — the row's clauses (protocol selection, horizon
boundary, renegotiated resume) were and remain correctly proven; the hole was in CT-24's
clause space, where C5 found it.

### CT-39 (PARTIAL, stays) — notes-only update; remainder CONFIRMED exact

Checked as instructed: the named remainder is exactly C6's Compose-scale E2E (3 CP + ≥3
edge processes, production `FanOutConfig.defaults()` per the C1-audit gap-3 rule, the four
scripted scenarios) + the RR-095 stall-seed re-run. C5 moves one scripted scenario
("fresh-edge bootstrap mid-load") to proven-at-1×1-process-scale, recorded as progress in
the row, and adds one materially useful fact for the C6 re-run: RR-103 is a named likely
component of RR-095's residual (deterministic seed 4242 in the register) — the re-run
should account for it. Nothing in C5 closes the row; PARTIAL(unit) stands.

### RR-102 retro-sweep — every PASSING row whose evidence contains a pre-fix snapshot transfer

The question: does any PASSING row's evidence now contradict the RR-102 reality — i.e.,
did a row claim transfer correctness on tests that only passed because transfers were
small? Swept rows CT-06, CT-13, CT-16, CT-20, CT-26, CT-29, CT-31, CT-33, CT-39
(every row citing a snapshot transfer at any level). Findings:

- **No row's CLAIM is contradicted; no flips.** Every pre-C5 snapshot-transfer evidence
  falls in one of two classes: (1) never-refusing sinks (all sim evidence via `SimSink`;
  all unit/core fixtures via recording sinks) where the pre-fix burst path and the fixed
  pacing path are byte-identical by construction (the fix only changes behavior on
  refusal), or (2) real-wire transfers that burst-fit the 64-frame queue (~1-3 frames at
  1 MiB chunks over tiny fixture stores: `FanOutServerIntegrationTest` demotion/snapshot
  legs, `FanOutServerQuarantineTest`/`QuarantineReBootstrapTest` readmission snapshots,
  `PoisonPillRebootstrapTest`/`MonotonicReadAcrossEdgeRestartTest`/
  `EdgeReBootstrapOnDisconnectTest`/`EdgeNodeIntegrationTest` process legs). The claims
  those rows pin — demotion policy, admission/quarantine, poison ladder, restart recovery,
  gap healing, the boundary contract (CT-20 is pre-wire by level) — are
  transfer-size-independent and remain true on the fixed tree (the lead's full reactor at
  `a099124` is green, and my targeted re-runs cover the closest suites).
- **One row got the sweep note on the map:** CT-26, whose wire demotion leg most directly
  *contains* a "chunked snapshot → contiguous resumed tail" transfer-correctness claim
  that predates the fix — noted that it passed because the transfer burst-fit free queue
  space, with wide-transfer pacing now owned by CT-24's evidence. For the rest, this
  audit section is the record (adding nine identical sentences to the map would be noise,
  not honesty).
- **Worth naming once:** pre-fix, every wire snapshot leg carried a latent race — a slow
  writer thread at emit time could have torn even a small transfer. Nobody ever observed
  it (loopback writers drain fast); it is gone now. This is why the sweep produces notes,
  not indictments: the fixture smallness was never load-bearing for any row's stated
  claim, only for the unstated assumption RR-102 exposed.

## Rows flipped (old → new)

| Row | Old | New |
|---|---|---|
| CT-24 | UNIMPLEMENTED | PASSING |

Notes-only updates: CT-31 (evidence augmented; flip-time heal claim re-scoped), CT-39
(1×1 process progress recorded; exact remainder confirmed; RR-103 linked to the RR-095
re-run), CT-26 (RR-102 sweep note), map header (+c5 audit reference), summary section +
footer recounted.

## Rows deliberately NOT flipped (refusals, each with the named reason)

- **CT-39 stays PARTIAL(unit)** — C5's process test is 1×1; the row's level demands the
  Compose-scale E2E (3 CP + ≥3 edge processes) plus the RR-095 stall-seed re-run, both
  C6-owned. Over-delivery at the wrong scale does not close a scale-defined row.
- **CT-31 stays PASSING (not re-litigated, not demoted)** — RR-102 was a hole in CT-24's
  clause space (bootstrap under load), not in CT-31's proven clauses; the fix is
  mechanically neutral to the heal loop (`lastAckedSeq` untouched, suite green), and the
  row note now carries the honest re-scoping rather than a status churn.
- **No sweep row flipped** — see the sweep section: no claim contradicted; fixture
  smallness was not load-bearing for any stated claim.
- **CT-02/CT-09/CT-34/CT-38** — untouched by C5; closing conditions unchanged.

## Defects found in tests' probative value

1. **Process leg 1's during-join write assert is vacuous** (`storm.written.get() >=
   writtenAtEdgeStart` — a monotone counter is trivially `>=` its past value). The
   design note §5's "every 'under concurrent writes' claim is hard-asserted" is therefore
   accurate only because leg 2 carries the process-level hard assert
   (`writtenAtCutover > writtenAtEdgeStart`) where the window is genuinely wide; leg 1 at
   production defaults completes the transfer in milliseconds and a strict `>` would
   flake. Verdict: minor, non-blocking — the C5-2 obligation IS hard-asserted at every
   level somewhere; recorded in the row as honest scope. If leg 1 ever wants its own
   teeth, assert on the bootstrap-window poll loop having observed ≥1 refusal-or-served
   response instead (it already enforces refusal-or-≥cursor per response).
2. **Transient double-applies at PROCESS level are not directly observable** (no per-tick
   store diff exists outside the sim) — pinned transitively by core counters + the
   crafted-frame matrix; the unique-value sweep catches persistent divergence only.
   Recorded in the row; the multi-level structure makes this acceptable rather than a
   gap (the sim leg DOES catch transients per tick, over the same production core).
3. **Cosmetic only, not tracked:** `BootstrapSnapshotBackpressureTest`'s fully-blocked
   leg computes `chunks` twice (a dead first read before the final drain) and
   `BootstrapCutoverExactnessTest`'s `assertNull(core.get("k7").found() ? ... : null)`
   is an awkward-but-correct refusal assert. Neither affects probative value.
4. **None blocking anywhere.** The suites carry their own vacuity controls and they
   checked out at source: the straddle measure cannot overcount (tick-phase analysis),
   the dup counter counts actual enqueues, the lost-transfer scenarios prove the loss
   before the heal, the 1-slot leg asserts the zero-before-END completion accounting,
   and the equivalence control's purity is both asserted and structurally forced.

## New summary line (recounted: 32 + 5 + 0 + 0 + 3 + 1 = 41)

```
CONTRACT-MAP-SUMMARY: total=41 passing=32 partial=5 failing-captured=0 unimplemented=0 adr=3 na=1
```

Zero UNIMPLEMENTED rows for the first time this session. Every remaining open row
(5 PARTIAL) has a named owner and a named remainder: CT-02 (Session 5 real-latency p99),
CT-09 (C6 multi-edge), CT-34 (G3 assembly), CT-38 (G3 consolidated metrics gate),
CT-39 (C6 Compose E2E + RR-095 re-run).

## Sign-off

The contract-qa half of the C5 dual sign-off may be marked against this audit: the design
note §8's three row claims verified EXACTLY as written (CT-24 → PASSING, CT-31 evidence
augmented + stays PASSING, CT-39 stays PARTIAL), the §6 deviations are each honestly
recorded and none is silently load-bearing, and the §7 gaps are real and correctly
non-blocking. **One carve-out for the review-architect's half:** RR-102 remains
RESOLVED-PENDING-REPRODUCTION until the revert drill (register P1 discipline) — this
audit's flip does not depend on it (my green runs are against the fixed tree; the
RED-first claim is taken from the register/commit record and is the drill's to confirm),
but the register row must not flip to RESOLVED on this document.
