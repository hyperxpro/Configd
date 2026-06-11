# C1 Contract-QA Audit — row-by-row against the contract-test-map

> **Auditor:** contract-qa-engineer (Session 3). **Date:** 2026-06-11. **HEAD:** `6c72a29`.
> **Scope:** component C1 as landed (`ca22214` part a, `a74bcbf` part b; as-built note
> `docs/session-3/design/c1-fanout-design-note.md`) audited against
> `docs/session-3/contract-test-map.md`. The charter's standing question, answered per row:
> *which contract clause does this satisfy, and which test proves it?*
>
> **Method (evidence discipline):** every claim below was verified by (1) **reading the named
> test bodies** in the working tree, (2) reading the production sources they exercise where the
> assertion's meaning depends on it (`FanOutConfig`, `RegistryFanOutSessionMetrics`,
> `C1StreamDriver`, `FanOutServer` wiring in `ConfigdServer`), and (3) the **fresh surefire
> reports** under `*/target/surefire-reports/` (timestamps 2026-06-11 20:00–20:12, from the
> lead's verification runs — all named suites 0 failures / 0 errors). No Maven invocation was
> needed (`pgrep -fl org.apache.maven`: none running at audit time; reports were fresh).
> C1's commits were diffed (`--name-only`) to confirm which `src/main` surfaces changed.
>
> **Snapshot caveat:** the review-architect agent was working in parallel during this audit; its
> in-flight (uncommitted at audit time) working-tree changes add a `maxSessions` admission bound +
> `edge_fanout_sessions_refused_total` counter to `FanOutServer`/`RegistryFanOutSessionMetrics` and
> re-scope the transport handshake guard. All claims here are against committed state (`6c72a29`);
> when that lands, CT-38's emitted-series list gains `edge_fanout_sessions_refused_total` (the row
> is open anyway) and nothing else in this audit is affected.

## Surefire evidence snapshot (all green, 2026-06-11)

| Suite | Tests | Failures |
|---|---|---|
| `FrameBatchingChainIntegrityTest` (distribution) | 1 (property, 400 tries) | 0 |
| `FullChainDeliveryTest` | 2 | 0 |
| `SubscriberQueueBoundTest` | 1 property + 1 | 0 |
| `SubscriberOverflowDemotionTest` | 3 | 0 |
| `NoDeltasSinceOnConsumerPathTest` | 2 | 0 |
| `FanOutSessionCoreTest` / `...BoundaryTest` | 15 / 4 | 0 |
| `EdgeFrameCodecGoldenFixtureTest` | 25 | 0 |
| `EdgeFrameCodecPropertyTest` / `EdgeSnapshotCodecTest` | 11 / 1 | 0 |
| `CommitNotificationSourceTest` / `FanOutBufferRaceTest` | 8 / 2 | 0 |
| `FanOutServerIntegrationTest` (configd-server) | 3 | 0 |
| `FanOutServerMtlsTest` / `RegistryFanOutSessionMetricsTest` | 3 / 3 | 0 |
| `EdgePropagationBacklogTest` (testkit) | 1 | 0 |

## Row-by-row findings

### CT-17 (coalescing/gap rule) — UNIMPLEMENTED → **ADR-RENEGOTIATED(adr-0038)** ✅ FLIPPED

ADR-0038 is **Accepted** (status header records the review-architect RATIFY of 2026-06-11,
`c1-design-review.md` §A2). The charter clause's "may collapse" option is renegotiated to
*may not* (no server-side coalescing of signed payloads; frame-level batching with chain and
signatures intact). Per the map's own legend, ADR-RENEGOTIATED = "clause renegotiated by an
accepted ADR; row closed by reference" — that is now literally true, **and** the renegotiated
rule is executable, not just declared:

- `FrameBatchingChainIntegrityTest` (read in full): a jqwik property over arbitrary
  publish/tick/ack/burst interleavings (including ring-overflow → GAP → snapshot paths)
  asserting the concatenation of all NOTIFY batches is a **verbatim** (`published.indexOf(seq)
  >= 0`, "merge/fabrication" failure message), **strictly-ascending** (`seq > prevEmitted`),
  **contiguous** (`idx == prevPublishedIdx + 1` over publish order — honoring the "contiguous
  over the published chain, not raw seq arithmetic" wording) subsequence, with the only allowed
  discontinuity an explicit `SNAPSHOT_BEGIN..SNAPSHOT_END` boundary after which the first seq
  must exceed the snapshot seq and contiguity re-baselines. This is exactly ADR-0038's "exact
  rule" made executable.
- Process-level corroboration: `FanOutServerIntegrationTest` delivers committed writes as
  verbatim in-order NOTIFYs over a real socket and resumes contiguously after a real
  demotion→snapshot.
- `WatchCoalescer` remains off the fan-out drain (re-verified by grep: no consumer in
  `fanout`/`wire` packages or `FanOutServer`).

**Why ADR-RENEGOTIATED rather than PASSING:** the clause *as written in the charter* (updates
"may collapse") was not implemented — it was renegotiated by an accepted ADR, and the tests
prove the renegotiated rule. Closing by ADR reference with the tests cited as evidence is the
honest legend entry; PASSING would misstate which clause text the tests satisfy. (Consistency
with CT-25 below: CT-17 closes because its renegotiated rule is *fully* tested; CT-25 does not
because half its renegotiated clause is unbuilt.)

### CT-18 (readSince non-GAP run discipline) — PASSING, **confirmed unaffected** (no change)

`git show --name-only ca22214 a74bcbf`: C1 touched **no** `src/main` file of the
ADR-0034 boundary (`FanOutBuffer.java`, `CommitNotificationSource`, `ReplaySource` unchanged;
C1(a) added only new `fanout/*` + `wire/*` files). `FanOutBufferRaceTest` fresh surefire:
2/0. Row text stays accurate, including the gate-3 jcstress `quick` requirement.

### CT-19 (overflow policy) — PASSING, **confirmed unaffected** (no change)

Same surface check as CT-18; `CommitNotificationSourceTest` fresh surefire 8/0. No edit.
(Same applies to CT-20/CT-21, which I spot-confirmed in passing — same suite, same surface.)

### CT-22 (no deltasSince consumer) — UNIMPLEMENTED → **PARTIAL(unit)**, with a REQUIRED gap

`NoDeltasSinceOnConsumerPathTest` exists, is green (2/0), and asserts the right thing: a
comment/string-stripped regex scan for `deltasSince(`/`::deltasSince`, exempting only
`FanOutBuffer.java` (the definition) and `FanOutBufferTest.java` (the sanctioned legacy
caller), with a second test asserting the scan roots resolve (no vacuous pass).

**REQUIRED gap — scan scope.** `SCAN_ROOTS` covers only
`configd-distribution-service/src/main` and `configd-testkit/src/test`. The **production
drain no longer lives there**: C1(b) put it in `configd-server/src/main`
(`io.configd.server.fanout.FanOutServer` → `FanOutSessionCore` over `readSince`). The guard
cannot catch a `deltasSince` consumer introduced in configd-server — precisely the module
where ADR-0034 handoff step 3 now matters most. C2's modules (configd-edge-cache today, the
future edge-node module) are also outside the roots. I verified by grep that **zero**
`deltasSince` references exist in `configd-server/src` or `configd-edge-cache/src` today, so
the *guarantee* currently holds — but the *guard* is incomplete, which is the row's whole
point (it "must pin that no production consumer ever appears as C1/C2 code lands").

**Required fix (code change — out of my doc-only lane, named for the lead):** add
`configd-server/src/main/java` (and the C2 modules as they land) to `SCAN_ROOTS` in
`configd-distribution-service/src/test/java/io/configd/distribution/fanout/NoDeltasSinceOnConsumerPathTest.java:41-43`.
Row flips to PASSING when the roots include every consumer module.

### CT-25 (subscription model) — stays **PARTIAL(unit)**, clause re-anchored ⚠ deliberately NOT closed

ADR-0038 (Accepted) re-anchors the clause: prefix subscription = **edge-side storage/serving
filter**; full signed chain on the wire ("per-key" N/A-by-construction at transport,
"full-store" universal). The split halves:

- **C1 half PROVEN.** `FullChainDeliveryTest` (read in full): a `["svc/"]`-prefix subscriber
  receives every published delta *including non-matching keys* (`db/`, `other/`), in exact
  order (asserts the full seq list `1..6` and every key on the wire), and prefix vs full-store
  subscribers drain **identical** chains. Subscribe handshake + delivery also run at process
  level (`FanOutServerIntegrationTest`).
- **C2 half NOT BUILT.** `EdgePrefixStorageFilterTest` (post-verification apply-filter:
  non-matching mutations advance the version chain without storing; out-of-subscription reads
  NOT_FOUND) does not exist; no edge process exists.

**Why NOT ADR-RENEGOTIATED, despite the design review's §A2 line** ("contract-qa flips CT-17
and CT-25 to ADR-RENEGOTIATED(adr-0038) on this ratification"): the legend defines
ADR-RENEGOTIATED as *row closed by reference*. CT-25's renegotiated clause still demands an
implementation (the C2 storage filter) and its named test. Closing the row now would delete
the only map line that still owes `EdgePrefixStorageFilterTest` — silent under-delivery, the
one unforgivable outcome. The row stays PARTIAL with the C1 half recorded as proven; it can
close (or flip PASSING against the re-anchored clause) when the C2 half lands. This is
consistent with CT-17, whose renegotiated rule is fully tested today.

### CT-26 (bounded queues / overflow demotion) — UNIMPLEMENTED → **PASSING** ✅ FLIPPED

Clause (charter §4 C1 :110-112): bounded per-subscriber queues; explicit overflow→catch-up
demotion; never unbounded, never a silent drop without cursor evidence; every policy threshold
a named config with a metric. Level required: PROCESS. Evidence, all read + green:

- **Bound:** `SubscriberQueueBoundTest` property — `inFlightFrames() <= queueFrames` after
  *every* action of arbitrary publish/tick/ack interleavings; plus the 80% warn threshold
  firing `onSlowConsumerWarning` (metric) at the configured depth.
- **Demotion semantics:** `SubscriberOverflowDemotionTest` — overflow → `DEMOTED_TO_CATCHUP`
  notice **on the wire** (never silent), `DemotionEvent` with cursor evidence
  (`cursor`, `lastAckedSeq` asserted exactly), metric fired, state → CATCHUP, session NOT
  closed (non-fatal); transport would-block also demotes (never buffers unboundedly); and the
  demotion is lossless — the post-demotion snapshot carries every committed key
  (reassembled via `EdgeSnapshotCodec` and checked key-by-key).
- **PROCESS level:** `FanOutServerIntegrationTest` demotion leg — a real unacked flood over a
  real socket produces `DEMOTED_TO_CATCHUP`, then `SNAPSHOT_BEGIN`/all-announced-chunks/`END`
  (BEGIN/END seq equality asserted), then a contiguous resumed tail past the snapshot seq; and
  an `edge_fanout_demotions_*_total` counter observably moved at `/metrics`.
- **Named config + metric per threshold:** `FanOutConfig` (validated record; each param's
  Javadoc names its config key and metric); `RegistryFanOutSessionMetrics` eagerly registers
  every series (RR-013), with `RegistryFanOutSessionMetricsTest` pinning the exact exported
  names and callback→counter wiring (including the unknown-reason `other` bucket).

The arch §7 credit-vocabulary conflict the old row flagged is resolved on the record: the C1
as-built note + ADR-0038 key the math off frames/bytes over the ADR-0034 ring (see also
`c1-design-review.md` B-3).

### CT-31 (catch-up protocol) — UNIMPLEMENTED → **PARTIAL(unit)** ✅ FLIPPED (partial only)

What C1 genuinely built of arch §7 :269-273: the **chunked snapshot** transfer — 1 MiB chunks
(`snapshotChunkBytes`, codec-capped, at-cap golden fixture) with a CRC32C per chunk frame
(codec trailer, corruption-rejected — `EdgeFrameCodecPropertyTest` single-bit-corruption leg),
demotion → snapshot → contiguous resume proven at session level
(`FanOutSessionCoreTest#gapMidStreamDemotesThenSnapshotsThenResumesTail`,
`SubscriberOverflowDemotionTest#noCommittedEffectLostAcrossTheDemotionBoundary`) **and** over
the real wire (`FanOutServerIntegrationTest`). Small-gap catch-up via `readSince` Ok-run is
CT-18/19/20 (boundary, already PASSING).

Still owed — and why the row stays only PARTIAL: the clause's C3 form. Edge-side gap→protocol
selection (gap<window stream vs gap>replay-horizon re-bootstrap), the **horizon-boundary case
under concurrent writes** (`ReplayHorizonBoundaryTest`), and arch §7's "resume on failure" at
*chunk* granularity — C1's behavior on a lost snapshot is re-demote → re-send whole
(self-healing; design-note bug fix 1), which is a deviation the C3 design note must implement
or renegotiate. `CatchUpProtocolTest`/`SnapshotChunkResumeTest` still do not exist;
`CatchUpService` is still an untested src/main orphan. The §7 WAL-delta wording reconciliation
also remains with C3.

### CT-41 (EdgeFrameCodec + golden fixture) — UNIMPLEMENTED → **PASSING** ✅ FLIPPED

Verified by reading `EdgeFrameCodecGoldenFixtureTest` + `EdgeFrameFixtures` +
`EdgeFrameGoldenBytes`:

- **Every type, every code, structurally enforced:** `fixtureSetCoversEveryTypeAndErrorCode`
  iterates `FrameType.values()` (9 types, verified in source) and `ErrorCode.values()` (10
  codes, verified) — a coverage *tripwire*: a future type/code cannot land without a golden
  entry. Fixtures also pin the empty-NOTIFY edge case, the **ADR-0038 batched NOTIFY form**
  (`notify_batch_signed.bin`), and the at-cap 1 MiB snapshot chunk (byte-pinned via full-frame
  CRC32C since the hex is too large to inline — and round-trip decoded).
- **Byte-equality + reverse direction:** every fixture asserts `encode == golden hex` AND
  golden bytes decode back to the canonical frame; `everyGoldenEntryDecodesCleanly` re-checks
  `peekLength`/re-encode length on each entry.
- **Rebaseline rule present** in both the test Javadoc and `EdgeFrameGoldenBytes` ("READ
  BEFORE editing any byte here"): revert, or bump `EDGE_WIRE_VERSION` and regenerate via
  `EdgeFrameGoldenBytesGenerator`; an unbumped drift fails by design.
- `EdgeFrameCodecPropertyTest` (11): jqwik round-trip, per-byte truncation, single-bit
  corruption → CRC error, cap rejection **before** allocation. ADR-0037 is now Accepted.
- Day-one requirement honored: codec and fixture landed in the same commit (`ca22214`).

Component column corrected C2 → C1: ADR-0037's shared-stack decision put the codec in
`configd-distribution-service` and it shipped with C1(a); C2 consumes it.

### CT-39 (end-to-end propagation) — UNIMPLEMENTED → **PARTIAL(unit)** ✅ FLIPPED (partial only)

Now proven (server half): `FanOutServerIntegrationTest` — committed write via real HTTP →
`Committed: seq=S` → verbatim NOTIFY over a real mTLS-capable socket endpoint, plus
demotion/snapshot/heartbeat legs and `/metrics` movement; `EdgePropagationBacklogTest` —
the RR-001 executable backlog, re-enabled verbatim and GREEN: sim-level eventual delivery
within the bound + every live edge converges byte-equal to the leader via the **production**
`FanOutSessionCore` (`C1StreamDriver`); corroborated by `EdgeAdversarialGateSeedSweepTest`
(507 seeds, 0 safety violations) and `EdgeLeaderKillScenarioTest`. RR-001's "zero committed
writes can reach any edge" is no longer factually true at the server side.

Stays PARTIAL: the clause needs the **edge process** (C2) and the **Compose E2E** (C6 — 3 CP
+ ≥3 edge processes, scripted scenarios, RR-095 stall-seed re-run). Honesty note carried into
the map: the 507-seed sweep records 6 delivery-bound liveness violations and raw convergence
62.1% under never-healed schedules (96.1% given a quiet window) — characterized as CP
liveness under hostile schedules (RR-095-class), per the sign-off checklist; the no-fault
backlog test is clean.

### CT-38 (metrics checklist) — UNIMPLEMENTED → **PARTIAL(unit)** ✅ FLIPPED (partial only)

Now emitted and test-pinned (`RegistryFanOutSessionMetricsTest` asserts exact exported names;
`FanOutServerIntegrationTest` asserts movement at a live `/metrics`), from
`RegistryFanOutSessionMetrics` (read in full — eager registration per RR-013, unknown-reason
`other` buckets):

- `edge_fanout_notify_batches_total`, `edge_fanout_notify_batch_size` (histogram),
  `edge_fanout_heartbeats_total`, `edge_fanout_slow_consumer_warnings_total`,
  `edge_fanout_snapshot_transfers_total`
- `edge_fanout_demotions_{queue_overflow|ack_lag|gap|transport_block|other}_total`
- `edge_fanout_sessions_closed_{server_shutdown|protocol_violation|frame_corrupt|bad_wire_version|auth_fail|gap_unrecoverable|transport_gone|other}_total`
- `edge_fanout_queue_depth` (gauge — **process-level high-water**, not per-subscriber),
  `edge_fanout_connected_subscribers` (gauge)

**Deviations, priced not hidden:** (1) design's `edge_fanout_demotions_total{reason=...}`
labels become per-reason **name suffixes** (`MetricsRegistry` is label-free); (2)
per-subscriber queue depth needs a label-capable backend — the gauge is an honest process
aggregate. Still owed (row stays open): edge staleness gauge+histogram (now ADR-0039 frontier
form), edge cursor lag, CT-27..30 slow-consumer transition counters, V2 probe histograms,
`configd.edge.staleness_violation_total`, `configd.edge.poison_pill`, and the consolidated
`EdgeMetricsContractTest` gate.

### Other rows touched by C1 (notes-only updates, statuses unchanged)

- **CT-16** (edge gap detection, PARTIAL(unit)): the notification-seq drain now *exists and
  runs in sim* — `EdgeActor` applies CommitNotifications through real `DeltaApplier`
  gap/stale semantics with cursor = applied seq, fed by the production session core; GAP →
  ack-stall → ack-lag demotion → snapshot heal is exercised by the seed sweep. Still owed:
  the same rule in the C2/C3 edge-process drain. ADR-0038 reference updated to Accepted.
- **CT-06** (DISCONNECTED re-bootstrap, UNIMPLEMENTED): the re-bootstrap *mechanism*
  (demotion → chunked snapshot → resume) now exists server-side at wire level; the
  staleness-trigger wiring (now ADR-0039-governed) and edge side remain.
- **CT-01/CT-02/CT-07/CT-08** (staleness family): ADR-0039 (Accepted, arbitrated at the C1
  design review §B-1) now governs the §2 measurement — frontier-based staleness via the C1
  `HEARTBEAT(latestSeq, serverNowMillis)` carrier; prod-blocking at the C2 gate (the
  idle-time `StalenessTracker` may not be the production signal). Thresholds/state machine
  unchanged (CT-07); the CT-08 tripwire survives "unchanged in spirit" (ADR-0039 §5: clamp +
  distinct metric, never silently trusted). Statuses unchanged — nothing new is *tested*.
- **CT-37** (strong-read keys): ADR-0038 reference updated Proposed → Accepted (delivery half
  settled: full chain to every edge); storage/serving half still the C2 design note's.
- **CT-32** (negative caching): ADR-0038 reference updated to Accepted; implement-or-descope
  still open with C3.
- **CT-40** (edge process mTLS): ADR-0037 now Accepted; the C1 *fan-out endpoint* mTLS is
  process-tested (`FanOutServerMtlsTest`: no-cert and wrong-CA connections unusable — no
  `SUBSCRIBE_OK` ever served, timing-robust per find0051; trusted client subscribes under
  cert-DN identity). The clause itself (the C2 edge *process*) remains UNIMPLEMENTED.

## Sim-tuned FanOutConfig assessment (ackLagDemoteSeqs=2 vs prod 8192)

`C1StreamDriver.simConfig()` scales `ackLagDemoteSeqs` to 2 (and queueFrames to 64) because
sim runs commit only tens of seqs — the prod threshold would never fire at sim scale
(documented at the site, design note §5). Does any flipped row's evidence lean on the sim
value in a way that weakens the claim?

- **CT-26 (PASSING):** No. The demotion legs that carry the flip use *explicit small
  queueFrames* with the **prod ack-lag value 8192** (`SubscriberQueueBoundTest`,
  `SubscriberOverflowDemotionTest` cfgs: `8_192L`) — the trigger under test is queue
  overflow / transport block, not ack lag. The ack-lag *transition itself* is unit-pinned
  with an explicit threshold (`FanOutSessionCoreTest#ackLagBreachDemotes`, threshold 2 by
  construction of the test config) — the threshold is a named, validated config parameter and
  the transition logic is threshold-parameterized, so exercising it at 2 proves the same code
  path that runs at 8192. The live server uses `FanOutConfig.defaults()`
  (`ConfigdServer.java:604`), and the integration test demotes under defaults over a real
  socket. **Claim not weakened**; noted in the map row.
- **CT-17/CT-25 (chain integrity / full chain):** the property tests sweep
  ackStride/queue/batch parameters and assert invariants that must hold at *any* threshold;
  no dependence on the sim value.
- **CT-39 (PARTIAL):** the sim-level delivery/convergence evidence *does* run at
  ackLagDemoteSeqs=2 — fine for what the row claims (mechanism-level delivery + convergence;
  the demotion-recovery path must fire at sim scale to be exercised at all). The wall-clock
  flavor of the claim is C6/S5's anyway. One residual, recorded in the row: sim convergence
  relies on demotion-based healing being reachable at the configured threshold; the C6
  Compose runs must use **production defaults** so the E2E claim is made at prod-shaped
  thresholds.

## Rows flipped (old → new)

| Row | Old | New |
|---|---|---|
| CT-17 | UNIMPLEMENTED | ADR-RENEGOTIATED(adr-0038) |
| CT-22 | UNIMPLEMENTED | PARTIAL(unit) — REQUIRED scan-scope gap (configd-server/src/main) |
| CT-26 | UNIMPLEMENTED | PASSING |
| CT-31 | UNIMPLEMENTED | PARTIAL(unit) |
| CT-38 | UNIMPLEMENTED | PARTIAL(unit) |
| CT-39 | UNIMPLEMENTED | PARTIAL(unit) |
| CT-41 | UNIMPLEMENTED | PASSING |

## Rows deliberately NOT flipped

- **CT-25** stays PARTIAL(unit) (not ADR-RENEGOTIATED, diverging from the review's §A2
  wording) — closing by reference would drop the still-owed C2 `EdgePrefixStorageFilterTest`;
  rationale above.
- **CT-18, CT-19** stay PASSING — confirmed unaffected (C1 touched none of their src/main
  surfaces; suites green). CT-20/CT-21 likewise.
- **CT-16** stays PARTIAL(unit) — sim drain is real progress but the required level is the
  edge-process drain (C2/C3).
- **CT-06, CT-40, CT-01/02/07/08, CT-32, CT-37** stay as-is — notes updated only (ADR
  statuses, new machinery); nothing new is tested at the clause's level.

## REQUIRED gaps found

1. **CT-22 scan scope (REQUIRED):** `NoDeltasSinceOnConsumerPathTest.SCAN_ROOTS` must add
   `configd-server/src/main/java` — the module that now hosts the only production drain —
   and the C2 modules as they land. Until then the static guard does not protect the path it
   was written to protect. (Code change; named for the lead — out of doc-only scope.)
2. **CT-31 "resume on failure" deviation (for the C3 design note):** chunk-level resume is
   not implemented; lost snapshots re-send wholesale. Implement or renegotiate explicitly.
3. **CT-39/C6 (forward-looking):** Compose E2E must run **production** `FanOutConfig
   defaults()` so demotion thresholds are exercised at prod shape, not sim shape.

## New summary line (recounted: 9 + 15 + 0 + 15 + 1 + 1 = 41)

```
CONTRACT-MAP-SUMMARY: total=41 passing=9 partial=15 failing-captured=0 unimplemented=15 adr=1 na=1
```

## Sign-off

The contract-qa sign-off line in `c1-fanout-design-note.md` may be marked against this audit:
C1's claims in the as-built note §2/§3 were verified test-by-test and are accurate as stated,
with the CT-22 scan-scope gap and the CT-31 resume deviation as the two REQUIRED follow-ups.
