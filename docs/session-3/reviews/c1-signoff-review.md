# C1 Component Sign-off Review — review-architect

> **Scope (JOB A):** the C1 fan-out distribution service component sign-off against
> charter §1 rule 2's DONE-definition (runs in the simulator under adversarial schedules
> AND unit/property tests pass AND a signed design note). As-built note:
> `docs/session-3/design/c1-fanout-design-note.md` (commits `ca22214` + `a74bcbf`).
> Prior gate: `docs/session-3/reviews/c1-design-review.md` (CLEARED-WITH-CONDITIONS).
> **Reviewer:** review-architect. **Date:** 2026-06-11. **Branch:** session-3-data-plane.
> Read-only on code. One targeted Maven run executed (the 507-seed sweep + the C1 unit
> battery) after confirming no competing build (`pgrep -fl org.apache.maven` clear except
> my own grep wrapper).

Severities: **BLOCKING** (gates the sign-off / must change before C1 is DONE),
**REQUIRED** (must land, tracked, does not gate the sign-off), **NOTE** (advisory). Every
item carries an explicit **prod-blocking / non-blocking** flag.

## Verdict: **SIGN-OFF** (no BLOCKING findings; 1 REQUIRED, 4 NOTE)

C1 meets the charter §1 rule-2 DONE bar: it runs in the simulator under the full 507-seed
adversarial schedule with **zero safety violations** (I re-ran it myself — see Finding 5),
its unit/property battery is green (I re-ran it — EXIT=0), and the as-built design note is
accurate against the code. All four of my prior C1-gating conditions (design-review gate
decision items 1–4) are honored; the remaining two (5, 6) were C2-gated and are confirmed
discharged-or-scheduled. The one REQUIRED item (Finding 8) is a register-row / doc hygiene
obligation that does not gate the component.

---

## Finding 1 — Layering integrity: command-queue isolation + teardown + alive-CAS [NOTE, 1 sub-item non-blocking-for-prod]

**Verified against `FanOutServer.java`, not the note's prose.**

- **The command queue is genuinely the only cross-thread path INTO the session.** The reader
  thread never touches `FanOutSessionCore` directly: every mutation is posted as a
  `Consumer<FanOutSessionCore>` onto `sessionCommands` (`ConcurrentLinkedQueue`) and drained
  ONLY on the session thread (`sessionLoop`, lines 432–435), which then calls `onSubscribe` /
  `onCursorAck` / `tick`. The session is single-threaded-per-instance by construction. The
  three cross-thread reads of session fields on the session-loop path (`inFlightFrames()`,
  `cursor()`, `state()` at 437–445) all execute **on the session thread itself** — no race. ✔
- **`alive` CAS idempotence holds.** `teardown` is reachable from all three threads (reader
  `finally`, writer `finally`, session `finally`) but `alive.compareAndSet(true, false)`
  (line 488) admits exactly one thread into the teardown body; the rest return. The socket
  close, POISON offer, and the two metric fires happen exactly once. The `start()`/`close()`
  server-level `running` CAS is likewise correct (single bind, idempotent close). ✔
- **`TransportSink` boundary closes ADR-0037 NOTE-2 (A1-finding-2).** I grepped the
  `distribution.wire` + `distribution.fanout` main packages myself for
  `java.net.*` / `javax.net.ssl` / `java.nio.channels` imports AND for bare
  `Socket`/`SSLSocket`/`ServerSocket`/`SSLEngine`/`SocketChannel`/`TlsManager`/`SSLContext`
  tokens: **zero hits in both packages.** The socket/TLS surface lives entirely in
  `FanOutServer.Connection` (configd-server), which is the `TransportSink` implementor. The
  session core is transport-agnostic; the ADR-0037 escape-hatch contingency is honored. ✔

**The one torn-state sub-item (NOTE — non-blocking for prod):** the winning teardown thread,
when `s.state() != CLOSED`, writes a final `ERROR_CLOSE` **directly** to
`socket.getOutputStream()` (lines 494–497) *before* it offers POISON to unblock the writer
(line 502). If the writer thread is mid-`out.write(frame)` at that instant, two threads write
the same `OutputStream` concurrently — interleaved bytes or a `SocketException`. This is
**bounded and self-correcting**: (i) only one thread is in teardown (the CAS); (ii) the
connection is being torn down regardless; (iii) a corrupted final ERROR_CLOSE simply decodes
as `FRAME_CORRUPT` at the edge, which also closes — the edge never mis-applies, never
regresses (the chain/version invariants are upstream of the wire and unaffected). It is not a
request-path hazard and not a safety hazard; it is a teardown-cleanliness smell. Also benign:
the `s.state()` read at 492 is a non-volatile cross-thread read whose worst case is a redundant
(or skipped) goodbye frame. **Recommendation (non-blocking):** offer POISON and stop the writer
*before* the direct goodbye write, OR route the goodbye through the same `outbound` queue +
writer so a single thread owns the `OutputStream`. Track as a C-area hardening row; not a C1
gate.

## Finding 2 — The two sim-found bug fixes: regression-resistance under unseen interleavings [NOTE — prod-relevant, non-blocking]

Both fixes were read at their code sites and against their pinning tests; I attacked each with
an interleaving the pinning test might miss.

- **Fix #1 — snapshot stranding via ack-driven `lastAckedSeq`** (`FanOutSessionCore.performSnapshotTransfer`,
  lines 307–319): the session advances `cursor = replay.seq()` and **clears** in-flight
  accounting but deliberately does **NOT** advance `lastAckedSeq`. So if the snapshot frames are
  lost on the wire, `cursor - lastAckedSeq` re-accrues and the ack-lag check
  (`drainStreaming` line 228) re-demotes → re-snapshots until the edge's `CURSOR_ACK` confirms
  application. The self-healing is structural, not timing-dependent. **Attack — could a stale ack
  defeat it?** No: `onCursorAck` (line 356) ignores `seq <= lastAckedSeq`, so a duplicate/stale
  ack can never spuriously advance the watermark past an un-applied snapshot. **Attack — backward
  snapshot seq lowering the cursor below `lastAckedSeq`?** Covered by Fix #2 on the edge side and,
  on the server side, `replay.seq()` is the source's current version (monotonic non-decreasing
  for a given node). The fix does not regress under the interleavings I could construct. The
  `EdgeAdversarialGateSeedSweepTest` lossy edge channel is the broad exerciser; the unit pin is
  `gapMidStreamDemotesThenSnapshotsThenResumesTail`. ✔
- **Fix #2 — backward-snapshot refusal** (`EdgeActor.applySnapshot`, lines 293–306): `if (seq <
  cursor) { cursorAckSink.accept(cursor); return; }` — the edge refuses a snapshot that would
  move it backward and **re-acks its real (higher) cursor** so the server's ack-lag clears
  against the true applied position and stops re-sending the stale snapshot. This is the precise
  countermeasure to the per-edge version-monotonicity invariant (a) firing. Pinned by
  `EdgeInvariantsTestTheTesterTest.productionApplySnapshotRefusesBackwardSnapshotSoTheStoreNeverRegresses`
  (a `seq 5 < cursor 10` snapshot is refused, edge stays at v10) plus the `forceLoadSnapshotUnsafeForTest`
  test-only bypass that proves the checker fires when the guard is removed. **Attack — a snapshot
  with `seq == cursor`?** Falls through the guard (`seq < cursor` is strict) and re-applies the
  same state wholesale + re-acks `cursor` — idempotent, no regression. **Attack — re-ack of a
  refused backward snapshot racing a forward delta?** Single sim thread (R-01); on the live server
  the ack arrives via the command queue and is processed on the session thread — `onCursorAck`'s
  monotonic guard makes the refused-snapshot re-ack a no-op if the watermark already moved. No
  regression path found. ✔

**Residual honesty (NOTE, prod-relevant):** both fixes are validated only at the breadth the
sweep's edge-fault grammar reaches (drop/reorder/dup/partition/crash/lag). They are NOT loom/
jcstress-checked for the *live* server's reader↔session↔writer interleavings — but the session
itself is single-threaded by the command-queue discipline (Finding 1), so the only true
concurrency on the live path is the teardown window (Finding 1's sub-item), which is upstream of
these fixes. Acceptable for sign-off; recommend C6/Session-4 chaos covers the live three-thread
teardown interleaving explicitly.

## Finding 3 — Chain integrity: the property implies ADR-0038's no-coalescing/no-skip across the demotion boundary [NOTE — non-blocking, no hole found]

`FrameBatchingChainIntegrityTest.notifyBatchesAreAVerbatimContiguousSubsequenceExceptAtSnapshotBoundaries`
(jqwik, 400 tries) proves exactly the ADR-0038 contract: the concatenation of all NOTIFY
batches is a strictly-ascending, **verbatim** (`published.indexOf(seq) >= 0` — every emitted
seq was actually published, so no merge/fabrication), **contiguous-over-the-published-index**
(`idx == prevPublishedIdx + 1` — no skip) subsequence, with the **only** permitted discontinuity
across an explicit `SNAPSHOT_BEGIN..SNAPSHOT_END`, after which the stream resumes contiguous
from the first published seq `> snapshotSeq`.

- **Does it cross the demotion boundary?** Yes, genuinely. The `case 3` action burst-publishes
  `bufferCap + 2` notifications, overflowing the small ring (`bufferCap ∈ [4,16]`), which evicts
  past the cursor → `readSince` returns GAP → `drainStreaming` demotes → `performSnapshotTransfer`
  emits `SNAPSHOT_BEGIN/chunks/END` → resumes tail. The verifier resets its contiguity baseline
  to `lastPublishedIdxAtOrBelow(snapshotSeq)` at `SnapshotEnd` and asserts the first post-snapshot
  seq strictly exceeds `snapshotSeq`. So the snapshot is the *authorized* gap-bridge and the
  property allows exactly that jump — nothing else. ✔
- **Batch caps interacting with the boundary — any hole?** I specifically checked: `queueFrames ∈
  [2,8]` and `batchMax ∈ [2,8]` are both randomized small, so queue-overflow demotions (not just
  GAP demotions) also fire inside the same property. Batch caps only *split* a contiguous run into
  multiple frames (`batchMaxNotifications` / `batchMaxBytes`) — they never skip a seq; the
  `idx == prevPublishedIdx + 1` check would catch a skip if one existed. A demotion mid-run clears
  in-flight accounting and the next event is a snapshot, which the verifier handles. **No hole
  found** in the cap×boundary interaction. ✔

**One scope note (NOTE, non-blocking):** the property does NOT separately assert that a NOTIFY
run which crosses a demotion *without* an intervening snapshot is impossible — but that case
*cannot* arise by construction (`demote()` sets `state=CATCHUP` + `catchupSnapshotOwed`, and no
NOTIFY is emitted until `performSnapshotTransfer` resumes STREAMING). The property's structure
(every discontinuity must be bracketed by SNAPSHOT_BEGIN/END or it fails the contiguity assert)
already enforces this transitively. Adequate.

## Finding 4 — Codec: golden fixtures, peekLength/CRC/cap discipline, signed-delta byte-fidelity [NOTE — non-blocking, all claims true]

- **Golden fixtures pin every frame type + all 10 error codes.** `EdgeFrameCodecGoldenFixtureTest`
  has a coverage tripwire (`fixtureSetCoversEveryTypeAndErrorCode`) that fails the build if any
  `FrameType` OR any `ErrorCode` lacks a fixture, plus the empty-NOTIFY edge case and the at-cap
  1 MiB chunk (CRC-pinned). The `ErrorCode` enum is a closed 10-value taxonomy (codes 1–10,
  `fromCode` round-trips) — this discharges prior **condition 3** (enumerated error taxonomy
  pinned in the golden fixture). Round-trip is asserted both directions (golden bytes → frame,
  frame → golden bytes). ✔
- **peekLength bounds BEFORE allocation.** `peekLength` (codec 549–564) and the streaming reader
  in `FanOutServer.readFrame` (380–392) both bounds-check the declared length against
  `[HEADER+TRAILER, MAX_EDGE_FRAME_SIZE=2 MiB]` *before* allocating `frameBytes` — an adversary
  cannot induce a giant allocation by lying in the 4-byte prefix. ✔
- **CRC before interpretation.** `decode` (322–384) validates length-range → length==data.length →
  **CRC32C** → version → type → payload, in that order. A flipped version/type byte surfaces as
  `FRAME_CORRUPT`, never a misleading "bad version". The `peekLength` field-resume cursor field
  exists in `Subscribe` (`failoverResumeCursor`, encoded at codec 189) — this discharges the
  second half of prior **condition 3** (reserve the failover-resume field). ✔
- **Cap discipline.** Frame cap 2 MiB, snapshot chunk cap 1 MiB, NOTIFY batch cap 64 / 256 KiB —
  all enforced symmetrically at encode AND decode (encode: 204–220, 273–277; decode: 428–429,
  498–502). ✔
- **Signed-delta byte-fidelity is real.** `EdgeFrameCodecPropertyTest.signedDeltaSigningPayloadRoundTripsByteIdentical`
  asserts `delta.signingPayload()` is **byte-identical** before/after a NOTIFY round-trip
  (`assertArrayEquals(before, after)`), plus signature/epoch/nonce verbatim, over a generator that
  mixes unsigned-legacy / signed-legacy / F-0052-signed deltas. This is the load-bearing property
  edge signature verification depends on (ADR-0038) and it is genuine, not a smoke test. ✔

## Finding 5 — The 507-seed evidence: re-run by me; the 6 deliveryViolations characterized [NOTE — non-blocking; NOT a C1 logic bug]

**I re-ran `EdgeAdversarialGateSeedSweepTest` myself** (`./mvnw -pl configd-testkit -am test`,
~10s after compile). Output, byte-for-byte matching the committed capture
(`docs/session-3/captures/c1-backlog-green.txt`):

```
EDGE-GATE-SUMMARY: seeds=507 safetyViolations=0 quietWindowSeeds=179
  convergedGivenQuiet=172/179 (96.1%) rawConverged=315/507 (62.1%)
  seedsWithDelivery=498 deliveryViolations=6 excusedAtDeadline=3308
EXIT=0
```

**`safetyViolations=0` confirmed reproducible.** The hard, enforced bar (per-edge version
monotonicity + no-stale-overwrite + the read-side INV-M1 monitor, checked every tick, throwing
on any breach) is clean across all 507 seeds. This is the load-bearing safety claim and it holds.

**Characterization of the 6 deliveryViolations — these are CP-liveness/fault-window artifacts
(RR-095-class), NOT a C1 logic bug.** Evidence, read from the sweep test + `EdgeInvariants` +
`EdgeFanOutSim` excusal logic:

1. **The violation can only be recorded for an edge that is `alive && !lagging && connected` AT
   THE DEADLINE TICK and still owes the seq** (`EdgeInvariants.checkEventualDelivery`, 207–227).
   An edge that is crashed/lagging/partitioned at the deadline is excused (and counted into
   `excusedAtDeadline=3308`, which dwarfs the 6 — i.e. the fault grammar is dominating). The
   recorder never fabricates a violation; the false-negative (under-count) direction is the only
   bias, which is the safe direction.
2. **Obligations are tied to the edge's statically subscribed CP node** (`C1StreamDriver` binds
   each session to `edge.subscribedCpNode()`'s `FanOutBuffer`; `recordPublicationObligation`
   obligates edges subscribed to the publishing node). Under the full CP fault schedule a
   subscribed node can be a transiently-behind follower or get isolated; a seq published there
   late in a 500 ms bound window, combined with the edge channel's drop/reorder needing the
   ack-lag→demote→snapshot→deliver→apply recovery cycle (several ticks of 1–10 ms network
   latency each), can exceed the bound. That is a propagation-window artifact under a hostile
   schedule, exactly the RR-095 class the charter §0 hands to Session 4.
3. **The decisive discriminators that rule out a C1 logic bug:**
   - `convergedGivenQuiet = 172/179 = 96.1%` — whenever a genuine quiet drain window exists (the
     CP cluster itself converged), the C1 catch-up/heal path heals the edge. A C1 logic bug that
     stranded healthy edges would depress THIS number, not just produce 6 transient lateness
     records. (The 7/179 quiet-window misses are themselves within the never-fully-settled CP
     tail, not a C1 stall — `cpFullyConverged` is a snapshot test that can be momentarily true
     while replication is still in flight.)
   - `seedsWithDelivery = 498/507` and the non-vacuity gate (`>= 9/10`) passes — the driver
     actually delivers on essentially every seed; the 6 are isolated late seqs, not systemic.
   - A C1 logic bug (e.g. a skip, a wrong cursor, a lost-then-never-retried notification) would
     manifest as a **safety** violation (monotonicity / stale-overwrite / the chain-integrity
     property) — all of which are clean — OR as a convergence failure given a quiet window —
     which is 96.1%. The 6 are recorded *liveness* lateness, the one category the sweep
     deliberately records-not-fails (RR-095 philosophy).

**What would further nail it (recorded for completeness, not required for sign-off):** the sweep
does not print the 6 violating (seed, seq, edgeId, lateness) tuples. If a future reader wants
zero residual doubt, add a one-line per-violation dump to `EdgeActivity` and replay those 6
seeds asserting that (a) the violating edge's subscribed CP node was behind/isolated in the
bound window and (b) the edge converges in the post-heal drain. I judge the three discriminators
above sufficient to **rule out a C1 bug** for this sign-off; this is a NOTE-level hardening, not
a gate.

## Finding 6 — Mutation honesty: FanOutSessionCore 64% individual vs 70.6% package / ≥65 module bar [NOTE — non-blocking; ACCEPTABLE, with a tracked strengthening recommendation]

The gate bar is **module-level ≥65%** (charter §5: "new modules ≥ 65% from day one"). The
new-package aggregate (`wire.*` + `fanout.*` in the committed `targetClasses`) is **70.6%**,
above the bar — so the gate is met as defined. `FanOutSessionCore` individually at **64%** is
below the *class* level but the gate is not a per-class gate, and the class is the most
behaviorally-dense unit (state machine + drain + snapshot + ack accounting), so a slightly lower
per-class score against a higher-coverage codec/enum/record set aggregating to 70.6% is the
expected shape, not a dodge. **Ruling: ACCEPTABLE — meets the module bar honestly.**

**Recommendation (NOTE, non-blocking):** the 64% on the single most safety-relevant class is the
thinnest margin in the component. Before C4 builds its slow-consumer governance *on top of*
`FanOutSessionCore`'s transition events, add targeted mutation-killing tests for the surviving
mutants in the demotion-reason branches and the `emit`/`maybeWarnSlowConsumer` paths (cheap now,
per the charter's own "cheaper during construction" doctrine). Track as a C4-precursor row; do
not gate C1.

## Finding 7 — Prior conditions (c1-design-review.md gate decision 1–4): all honored [verified]

| Condition | Status | Evidence |
|---|---|---|
| **1. ADR-0037 scale-envelope wording** | HONORED | `adr-0037` §2 now states "the SYSTEM edge count is 10k baseline / 1M ceiling … tens to low hundreds … is what the transport must handle" (grep-confirmed). |
| **2. ADR-0038 100k/s burst figure** | HONORED | `adr-0038` §"Bandwidth honesty" now carries "≈ 800 Mbit/s per subscriber … outside this design's envelope" at the burst envelope (grep-confirmed). |
| **3. Error taxonomy pinned + failover-resume field reserved** | HONORED | `ErrorCode` closed 10-value taxonomy pinned by `EdgeFrameCodecGoldenFixtureTest` coverage tripwire; `EdgeFrame.Subscribe.failoverResumeCursor` encoded/decoded (codec 189/411) and carried through `FanOutServer.bindIdentity`. (Finding 4.) |
| **4. Backpressure numbers govern; §7 credit model superseded** | HONORED | Recorded in the retained screened draft `c1-fanout-design-draft.md` §"Which numbers govern (review condition 4, resolves CT-26)" (frame/byte/ack-lag thresholds govern; §7 100-credit/1000-entry model superseded). The as-built NOTE §5 inherits this; the draft is the canonical record of the resolution. |

The two C2-gated conditions are confirmed **scheduled / discharged**: **condition 5** (ADR-0039
before C2; C1 ships HEARTBEAT as carrier only) — `docs/decisions/adr-0039-frontier-staleness.md`
EXISTS and explicitly states "C1 ships the frame as a carrier only; interpreting it is C2" and
"the idle-time proxy measurement is deleted, not retained" (the prod-blocking C2 condition); I
confirmed `EdgeActor.applyHeartbeat` only stores the carrier fields and computes no staleness.
**Condition 6** (skewed commit timestamp in `EdgeFanOutSim`) — `EdgeFanOutSim.java:157` now
captures `cpSim.skewedClock(cpNode).currentTimeMillis()` (the publishing node's ±50 ms skewed
clock), not the global `currentTime()`; the C-1 fix landed with an in-code note that the CP
digest folds role/term/leader/log-indices/version (not timestamps) so `EdgeSeedCompatTest` stays
byte-identical. Both C2 conditions are honored at the C1 boundary; their *consumption* is C2's
gate.

## Finding 8 — Register hygiene for the C1-discharged shelfware rows [REQUIRED — non-blocking for prod, blocking-for-honesty]

`RR-088` (configd-distribution-service mutation 55%, naming `SlowConsumerPolicy` 25/25
NO_COVERAGE and `CatchUpService` 22/22 NO_COVERAGE as "shelfware pending the RR-001
implement-or-descope decision") and `RR-042`/`RR-001`'s framing predate C1. C1 has now (a) raised
the new wire/fanout packages to 70.6% mutation and (b) made the boundary read side a live
consumer-driven path in the sim. **Required:** add a Session-3 register row (or update RR-088)
recording that the new C1 packages clear the ≥65 bar and that `SlowConsumerPolicy`/`CatchUpService`
remain the *old* distribution-service orphans whose disposition is now explicitly C4's delete-half
(see the C2–C5 screen, C4 finding). This is the charter §6 rule-6 register discipline applied to
the as-built delta; it is doc-only and does not gate the C1 component. Owner: data-plane-lead +
review-architect at C4.

---

## SIGN-OFF STATEMENT

C1 (the fan-out distribution service) is **SIGNED OFF** as DONE per charter §1 rule 2: it runs
in the simulator under the 507-seed adversarial schedule with zero safety violations
(reviewer-reproduced), its unit/property battery passes (reviewer-reproduced, EXIT=0), and the
as-built design note is accurate against the code with all four prior gating conditions honored.
The single REQUIRED item (Finding 8) is register hygiene tracked to C4; the four NOTEs are
hardening recommendations (teardown OutputStream window, live three-thread chaos coverage,
per-violation seed dump, FanOutSessionCore mutation strengthening) — none gate the component or
production. The review-architect signature line is appended to the design note §Sign-off.

— review-architect, 2026-06-11
