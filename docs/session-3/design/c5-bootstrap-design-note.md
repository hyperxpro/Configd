# C5 As-Built Design Note — New-Edge Bootstrap (the adversarial proof)

> **Status: AS-BUILT** — for dual sign-off (review-architect + contract-qa, charter §2).
> Test names are citations. Draft: `c5-bootstrap-design-draft.md`; screen:
> `../reviews/c2-c5-design-screen.md` §C5 (cleared, no conditions, NOTEs C5-1/C5-2).
> Deviations §6; named gaps §7.

## 0. What C5 is, as built

C5 was scoped as a **proof component**, not new machinery: C3's `decideMode` change
(RR-100) already routes every zero-state subscriber through snapshot-transfer + exact
cutover, so the mechanism runs on every fresh subscribe. C5's job was to prove it exact
under sustained concurrent writes at three levels (crafted frames at the core, the sim's
equivalence judge, a real process joining a live server mid-write-storm) — and the proof
worked exactly as the charter intends: it found one P1 hole in the mechanism (RR-102,
fixed + red/green-proven) and one P1 in the consensus kernel underneath it (RR-103,
registered with a deterministic reproduction, deliberately left to its owner).

## 1. RR-102 — the snapshot-transfer backpressure hole (found, fixed, pinned)

`performSnapshotTransfer` burst-emitted the whole envelope (BEGIN + all chunks + END) in
one tick; `emit()`'s refusal semantics treated a refused non-NOTIFY frame as transport
death. Against the live `FanOutServer.Connection` (non-blocking 64-frame outbound
queue), any transfer with more chunks than free queue slots tore: session closed at the
first full-queue chunk, then **resurrected to STREAMING** by the unconditional cutover
tail, having silently dropped middle chunks — and the edge's reassembly failure routes
into the ADR-0040 poison ladder, ending in TERMINAL exit 3. Net effect: a store larger
than `transportQueueFrames × snapshotChunkBytes` (~64 MiB at production defaults) could
**never bootstrap**, and a merely large bootstrap could kill the edge process.

**Fix** (`FanOutSessionCore`, the only main-code change in C5): `PendingSnapshotTransfer`
high-water state — a refused snapshot-frame offer is WOULD-BLOCK (pause at the exact
frame, resume next tick, same envelope, never a restart); cutover bookkeeping (cursor=S,
STREAMING, `onSnapshotTransfer`) happens only after END is **accepted**; `lastAckedSeq`
is untouched throughout, so CT-31's transfer-level self-heal is unchanged
(`SnapshotChunkResumeTest` green). Byte-identical behavior when the sink never refuses.
Red-first proof: `BootstrapSnapshotBackpressureTest` written against `4cfa6da` — both
legs RED deterministically; fix → 4/4 green (exact-envelope through 8-slot and 1-slot
transports; straddle writes resume as the contiguous tail at exactly S+1; cutover never
declared before END acceptance; replay-source failure stays down). Scoped PIT: 235
mutants, 80.9% killed, RUN_ERROR=0; `SlowConsumerGovernor` stayed 100%.

## 2. RR-103 — the consensus inflight leak (registered, not fixed — discipline)

`RaftNode.inflightCount` per-peer windows are incremented on send, decremented only by a
response, reset only at `becomeLeader`: once `maxInflightAppends` messages to a peer
drop, the leader is permanently silenced toward that peer for its term (no backfill, no
InstallSnapshot, no metric). Deterministic reproduction: seed 4242, 5 nodes — isolated
leader heals but stays ~47 entries behind for 10k+ quiet ticks. Surfaced by
`EdgeBootstrapMidChurnTest`; likely explains part of RR-095's residual. NOT fixed here:
the consensus kernel is S2-owned machinery under its own gate regime — the C5 test
quarantines the dependency (deposed-source leg judged per-source); the fix shape
(deadline decay or rejection-triggered window reset) is in the register row, owner S4
with RR-095.

## 3. The proof, scenario by scenario

- **Sim, row-named** (`EdgeBootstrapUnderSustainedWritesTest`, seeds 41-44 + 77/91/101):
  zero-state `joinEdge` mid-run with writes before/during/after; **hard non-vacuity per
  C5-2**: `straddleWrites >= 1` asserted per seed. Judge: per-tick throwing invariants +
  final byte-equality vs the leader + the equivalence claim asserted **directly** — the
  joiner is compared by the same `EdgeInvariants.finalCheck` code against a hard-asserted
  pure-stream control (`veteran.snapshotsApplied()==0`). Adversarial variants: other
  edges faulted while the joiner is clean (seed 77); the joiner's own transfer genuinely
  lost (`snapshotsApplied==0` hard-asserted at the fault point) then self-healed via
  ack-lag re-demote (seed 91); dup rate 1.0 across the cutover window with
  `dupsAcrossBootstrap > 0` hard-asserted and duplicates invisible in effect (seed 101).
- **Sim, mid-churn** (`EdgeBootstrapMidChurnTest`, seed 4242): leader killed
  mid-transfer (joiner on a healthy follower) → converges; source-IS-the-killed-leader →
  bootstraps from frozen committed state, judged per-source (RR-103 quarantine);
  leader-killed AND transfer-lost → self-healing re-send bootstraps after heal.
- **Core, crafted frames** (`BootstrapCutoverExactnessTest` — the C5-1 off-by-one
  catcher): snapshot at S → S+1 applies exactly; redelivered S with poisoned different
  bytes → STALE-refused, snapshot's effect survives byte-identical; crafted S+2 skip →
  GAP-refused with heal at the real cursor; duplicated whole transfer idempotent over
  effect; late backward transfer refused + re-acks. INV-M1 throwing mode throughout.
- **Process** (`EdgeBootstrapUnderSustainedWritesProcessTest`): real `EdgeNodeMain`
  joins a live server mid-write-storm (unique value per write = double-apply tripwire);
  every in-flight read refusal-or-≥cursor; exactness at the HTTP surface
  (`snapshotsApplied==1`, `gapsDetected==0`, `verifyRejections==0`,
  `currentVersion==fenceSeq`). Second leg: the **paced transfer over a real socket** —
  2 KiB chunks / 8-frame queue / ~370-chunk store, `writtenAtCutover >
  writtenAtEdgeStart` hard-asserted, bulk content byte-exact. Pre-RR-102-fix this leg
  could not complete.

## 4. Gate-path neutrality

`EdgeSeedCompatTest` green; 507-seed sweep summary byte-identical to the C2/C3/C4
baseline. The new sim seams (`joinEdge`, `setEdgeDupRateForTest` + dup counter) are
opt-in and RNG-neutral (the dup draw happens on every send regardless of rate, so the
seams consume no extra draws).

## 5. Screen NOTE dispositions

- **C5-1** (exact-cutover justification): honored — the one proven seam carries the
  correctness burden at all three levels including under transport backpressure; the
  idempotent-apply defense-in-depth is separately proven able to catch a violated
  mechanism (the crafted-frame matrix).
- **C5-2** (non-vacuity): implemented exactly as recommended — every "under concurrent
  writes" claim is hard-asserted (`straddleWrites >= 1`, `writtenAtCutover >
  writtenAtEdgeStart`, `dupsAcrossBootstrap > 0`, `snapshotsApplied==0` at fault points).

## 6. Deviations (named)

1. Sim straddle widening via `joiner.lag()` (12 ticks) instead of literal
   big-store/small-chunks — the sim transfer rides one message; chunk pacing is
   exercised deterministically at core level and over real sockets at process level.
2. Dup coverage via the deterministic `setEdgeDupRateForTest(1.0)` seam, not the ambient
   seeded 2-5% rate (which cannot guarantee a dup across the cutover — C5-2 demands a
   hard assert).
3. The mid-churn "resubscribe to another node" re-homing leg is delegated to the
   process-level `EdgeFailoverTest` (the sim topology has fixed subscriptions) — named
   in the test javadoc.
4. The process test delivers more than the draft's "C6 assertion script" (a full 1×1
   process test now); the Compose-scale leg remains C6's.

## 7. Named gaps

- RR-103 OPEN (owner S4 with RR-095; deterministic seed in the row).
- No dedicated "transfer stalled" observability for a long-paused transfer (queue-depth
  gauge shows pressure) — S6 candidate, on the handoff list.
- End-of-run sim judging needs fence writes (a final-delta reorder strand below the
  ack-lag threshold is only healable in production via the wall-clock ADR-0039 ladder) —
  documented in `settleAndJudge`'s javadoc; pre-existing characterization.
- Test-only additions in testkit/edge-cache/edge-node carry no PIT obligation; the
  distribution-service main-code obligation is discharged (RUN_ERROR=0, floors hold).

## 8. Contract rows this component claims (the contract-qa audit flips)

CT-24 → PASSING (the row-named sim test + the full matrix above + the RR-102
found-and-fixed note); CT-31 evidence augmented (paced-transfer self-heal), stays
PASSING; CT-39 stays PARTIAL (C6 owes the Compose-scale E2E + RR-095 re-run).
