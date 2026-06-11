# C2 Contract-QA Audit — row-by-row against the contract-test-map

> **Auditor:** contract-qa-engineer (Session 3). **Date:** 2026-06-11. **HEAD:** `a771550`.
> **Scope:** component C2 as landed (`7034f67` part a: EdgeClientCore + ADR-0039 frontier
> staleness + ADR-0038 storage filter; `37cf3c6` part b: the `configd-edge-node` module —
> mTLS stream shell, HTTP read surface, metrics, process tests — plus the contract §3
> failover amendment per the C2-4 screen ruling; as-built note
> `docs/session-3/design/c2-edge-node-design-note.md`) audited against
> `docs/session-3/contract-test-map.md`. The charter's standing question, answered per row:
> *which contract clause does this satisfy, and which test proves it?*
>
> **Method (evidence discipline):** every claim below was verified by (1) **reading the named
> test bodies** in the working tree, (2) reading the production sources where the assertion's
> meaning depends on them (`StalenessTracker` — full rework read; `EdgeClientCore` wiring;
> `EdgeStreamClient` timeout constants; `EdgeActor`'s production-core delegation;
> `NoDeltasSinceOnConsumerPathTest.SCAN_ROOTS`; `LocalConfigStoreReadBenchmark`), (3) the
> **amended contract text** (`docs/consistency-contract.md` §3 Edge Failover, read as amended)
> against the c2-c5-design-screen **C2-4 ruling**, and (4) the **fresh surefire reports**
> (timestamps 2026-06-11 23:03–23:05 — the lead's full-reactor verification run; tree clean,
> reports correspond byte-for-byte to HEAD's content since the commits at 23:06–23:07 captured
> the exact tree the run executed on). All named suites: 0 failures / 0 errors. No Maven
> invocation was needed (`pgrep -f org.apache.maven`: nothing running at audit time). The
> `configd-edge-node` dumpstream was inspected: benign keytool stdout noise from the mTLS
> cert fixture, not a test failure.

## Surefire evidence snapshot (all green, 2026-06-11 23:03–23:05)

| Suite | Tests | Failures |
|---|---|---|
| `StalenessTrackerTest` (8 nested classes, reworked) | 27 | 0 |
| `StalenessSkewTripwireTest` | 7 | 0 |
| `EdgeClientCoreTest` (11 nested classes) | 37 | 0 |
| `EdgePrefixStorageFilterTest` | 12 | 0 |
| `StrongReadKeyClassTest` | 10 | 0 |
| `EdgeNodeConfigTest` / `EdgeNodeMetricsTest` | 8 / 10 | 0 |
| `EdgeHttpServerTest` | 11 | 0 |
| `EdgeNodeIntegrationTest` (PROCESS) | 3 | 0 |
| `EdgeFailoverTest` (PROCESS) | 1 | 0 |
| `EdgeStrongReadFailClosedTest` (PROCESS) | 1 | 0 |
| `EdgeTransportMtlsTest` (PROCESS) | 3 | 0 |
| `MonotonicReadAcrossEdgeRestartTest` (testkit) | 2 | 0 |
| `EdgeStalenessFrontierSimTest` / `EdgeStalenessDistributionSimTest` | 2 / 1 | 0 |
| `ConsistencyPropertyTests$StalenessUpperBoundTest` (rebuilt) | 5 | 0 |
| `EdgeSeedCompatTest` (C2-1 digest preservation) / `EdgeAdversarialGateSeedSweepTest` | 1 / 1 | 0 |

## Row-by-row findings

### CT-01 (staleness measurement) — UNIMPLEMENTED → **PASSING** ✅ FLIPPED

ADR-0039 is in the code, not just accepted. `StalenessTracker` (read in full): `recordUpdate(version,
commitTimestampMillis)` advances the covered frontier to the **leader commit timestamp** — the
formerly-ignored param is now load-bearing, and the idle-time proxy (`nanoTime − lastUpdateNanos`)
is **deleted**, no residual path. The decisive unit assertion is
`stalenessUsesCommitTimestampNotRecordTime`: commit ts lagging wall-now by 300ms at record time →
staleness 300 (data age), not 0 (record time). Heartbeat law in one place
(`recordFrontier`: advance iff `latestSeq == cursor`; behind-heartbeat refused). Core wiring:
`EdgeClientCoreTest#appliedNotifyAdvancesFrontierToCommitTimestamp` + `$HeartbeatFrontier`. SIM half:
`EdgeStalenessFrontierSimTest` drives the **production `EdgeClientCore`** via `C1StreamDriver` —
idle-but-heartbeating edges pinned CURRENT across ≥35s of sim time (the pre-fix proxy would have hit
DISCONNECTED at 30s); a partitioned edge walks the full ladder while its non-partitioned sibling
stays fresh (non-vacuity). The row's originally-named `StalenessTrackerCommitTimestampTest` never
landed under that name — the test-name column now cites what exists.

### CT-02 (INV-S2 distribution) — stays **PARTIAL(unit)**, evidence upgraded ⚠ deliberately NOT flipped

The *mechanism* half is now real at SIM level: `EdgeStalenessDistributionSimTest` attaches a
`PropagationProbe` fed real publish→visible samples through the production C2 path and asserts a
non-vacuous, monotone (p50 ≤ p99 ≤ max) distribution — deliberately with **no p99 target**, per
charter V2 ("simulator (logical time — correctness of the bound's mechanism)"; "Session 5 will use
this for the real p99 < 500 ms target"). **Refused the flip to PASSING:** INV-S2 is a performance
distribution bound, and sim logical time cannot prove it; the legend's required level for this
guarantee is "sim with real propagation" — Session 5's. Flipping now would close the map line that
tracks the bound itself. **Probative-value defect (recorded in the row):** the legacy
`#edgeStalenessStaysWithinBoundsUnderNormalConditions` still self-drives a 20ms sync loop and stamps
`commitTs = sync time` — it measures its own loop (the CM-049 critique survives the frontier rework
for this one test). It is now explicitly NOT cited as this row's evidence.

### CT-03 (X-Configd-Stale header) — UNIMPLEMENTED → **PASSING** ✅ FLIPPED

Process level: `EdgeNodeIntegrationTest#staleHeaderAndReadinessDegradeAfterSourceLoss` — a real edge
loses its real source (server killed mid-run), the frontier freezes, and the header appears on reads
over the real wire while the data is still served. HTTP matrix: header on ALL reads while STALE+
including **misses** (`EdgeHttpServerTest#staleHeaderSetOnAllReadsWhenStalePlus` — the clause says
"all read responses", and the test checks a miss too). Non-vacuous: the CURRENT-state test asserts
the header is absent.

### CT-04 (staleness violation counter) — UNIMPLEMENTED → **PASSING** ✅ FLIPPED

`EdgeNodeMetricsTest` pins entry-counting semantics exactly: counts once per STALE+ *entry*
(staying STALE does not re-count; re-entry counts again; a single observed CURRENT→DEGRADED jump
counts once; boot state is not a transition — process start is bootstrap, not a violation). Eagerly
registered (RR-013, `#everySeriesIsRegisteredEagerly`). Process corroboration:
`configd_edge_staleness_violation_total` observed moving at a live `/metrics` **with no live
stream** (the disconnected-pump path — the state where the counter matters most). Distinct from
`invariant.violation.staleness_bound`, as the clause requires. The GATE half (consolidated
checklist presence test) stays delegated to CT-38, which remains open.

### CT-05 (DEGRADED unhealthy reporting) — UNIMPLEMENTED → **PASSING** ✅ FLIPPED

The old row called the clause untestable because no health surface was specified; C2 specified and
built it. `/health/ready` 503 at DEGRADED+ and at boot (a never-synced edge is not ready);
`/health/live` unconditionally 200. Process level: real source loss → readiness flips 503 past 5s,
liveness stays 200, stale data keeps being served. One honest nuance (noted in the row): "continue
serving stale data" is asserted while STALE (200 + header); at DEGRADED it holds by construction —
the serving path is staleness-state-independent except the CT-37 strong-read fail-close. "Emit
alert" = the `edge_staleness_state` gauge + the CT-04 counter; alert *wiring* is Session 6's.

### CT-07 (staleness state machine) — PARTIAL(unit) → **PASSING** ✅ FLIPPED

The CT-01 prerequisite landed, so the transitions are contract-true: driven by a TRUE STALL (both
deltas AND heartbeats withheld), reset on a fresh commit-ts update from any state, exact `>`
boundaries pinned at 500/5000/30000. The defect ADR-0035 rejected relabeling for is now pinned in
the correct direction: an idle-but-heartbeating edge stays CURRENT for 250s, and a heartbeat
carrying `latestSeq > cursor` does **not** reset state — real data-age lag surfaces
(`behindHeartbeatDoesNotAdvanceFrontierAndShowsLag`). `ConsistencyPropertyTests$StalenessUpperBoundTest`
was rebuilt to the ADR-0039 §Consequences shape (true-stall transitions + the idle-but-heartbeating
regression test). SIM corroboration: the partitioned-edge full ladder walk.

### CT-08 (skew tripwire) — UNIMPLEMENTED → **PASSING** ✅ FLIPPED

`StalenessSkewTripwireTest` (7, read in full) is non-vacuous in both directions: beyond-allowance
future frontier **counts + clamps**; within-allowance (exactly 50ms) clamps **without counting** —
no false positives; backwards frontier counts + holds; both the commitTs and heartbeat paths;
clamping survives a missing counter; a normal lagging frontier never counts. The dedicated series
is `edge_staleness_implausible_total` (the "distinct metric" the handoff item demands). Signal
integrity: the part-(a) defect — ADR-0028 snapshot bodies stamp ts 0, so every legitimate cutover
would have fired the tripwire and drowned real skew — was caught during C2(b) and fixed
(`EdgeClientCoreTest$SnapshotFrontierIntegrity`); `EdgeNodeIntegrationTest` confirms zero
implausible samples on an ordered same-clock stream.

### CT-11 (failover to a different endpoint) — PARTIAL(unit) → **PASSING** ✅ FLIPPED

`EdgeFailoverTest` (read in full) is the exact owed scenario at process level: two fan-out
endpoints over the SAME ADR-0034 boundary, the subscribed one killed **mid-stream**, the edge
reconnects to the other carrying `resumeCursor = core.cursor()`, and the helper enforces on EVERY
response that it is either the consistent refusal or version ≥ cursor (cursor-monotonic across the
reconnect). Fixture discipline is right: endpoint B's port is reserved but not started, making the
dead-endpoint refusal window deterministic rather than a race. `edge_reconnects_total` observably
moved.

### CT-12 (failover steps 3–4) — UNIMPLEMENTED/blocked → **PASSING** ✅ FLIPPED (against the amended clause)

The contract-internal contradiction is resolved on the record, in the place the screen ruled:
**C2-4** (c2-c5-design-screen) directed the §3 amendment be recorded in the consistency-contract
pass, NOT a new ADR, and explicitly directed "the CT-12 row flipping to
PASSING-against-the-amended-clause". I verified the amended text in `docs/consistency-contract.md`
§3 Edge Failover steps 3–4: cursor-behind refused immediately (404 + `X-Configd-Refused:
cursor-behind` + the edge's cursor + both counters), never blocks, never serves stale, uniform
across steady state / catch-up / failover; the old block-then-serve-stale text is quoted as
superseded with the CM-017/CM-041 lineage. The refusal is strictly *stronger* than what the old
clause promised — not a silent weakening. Tested at process level in a deterministic no-endpoint
window (`EdgeFailoverTest`), at HTTP level (`EdgeHttpServerTest` — refusal vs true-miss vs
malformed-cursor distinguished), and as an every-response invariant during real catch-up
(`EdgeNodeIntegrationTest`). Level column updated from "— (blocked)" to PROCESS.

### CT-13 (monotonic reads across edge restart) — UNIMPLEMENTED → **PARTIAL(unit)** ✅ FLIPPED (partial only)

`MonotonicReadAcrossEdgeRestartTest` (read in full) pins precisely the silently-droppable clause,
through the production `EdgeClientCore` (the `EdgeActor` refactor — verified: EdgeActor builds and
delegates to the real core) and the real INV-M1 seam: crash loses the cache → restart at cursor 0 →
held cursor-5 read REFUSES (test-mode AssertionError); a re-bootstrap snapshot at 3 (**below** the
cursor) still refuses — the cursor check runs against the **post-bootstrap** version; only a
snapshot ≥ 5 serves again, and serves post-bootstrap state; a cursorless read never resurrects the
pre-crash value. Stays PARTIAL for the C3 half: the same property across a REAL `EdgeNodeMain`
kill/restart, and the re-bootstrap orchestration itself (the `rebootstrapHook()` seam is a stub).

### CT-16 (edge gap detection) — stays **PARTIAL(unit)**, notes updated

The C2 process drain now exists and routes every inbound frame through the production core's seq
rule; mid-batch GAP stops the cursor at the last contiguous seq and **acks the real cursor** (not
the gapped seq); stale NOTIFY never overwrites; GAP→forward-snapshot heals and contiguous tailing
resumes (`EdgeClientCoreTest`). Still owed and still C3's: the explicit edge-side GAP→replay
orchestration (resubscribe-with-cursor per the C3-1 ruling / ADR-0040) at process level. Not
flipped — the row's remaining obligation is unchanged in kind.

### CT-23 (signed-chain verification) — PARTIAL(unit) → **PASSING** ✅ FLIPPED

The old row's missing level — "verification must sit on the C2 receive path, not only in the
in-process applier" — is now satisfied and process-proven in **both directions**:
`EdgeNodeIntegrationTest` runs a real server signing with a real persistent Ed25519 key, the verify
key exported by the real `VerifyKeyExporter` path; the chain verifies-and-applies (0 rejections);
and an edge with NO verify key rejects the signed chain fail-closed — 0 applied, store never
advances. Tampered-signature rejection is pinned at the same core receive seam the process test
proves live (`EdgeClientCoreTest$VerificationSeam`), counted on `edge_verify_rejections_total` and
**not** folded into the gap signal (the honest-gap-series split, a real design decision verified in
`EdgeClientCore.onNotify`). Honest residuals recorded in the row, judged non-blocking: no literal
mid-stream tampered-FRAME injection over a socket (frame integrity is the codec CRC32C, CT-41
property tests; the post-decode signature check is the seam above); epoch-replay-across-restart
remains applier-level (`DeltaApplierTest$EpochPersistence`).

### CT-25 (subscription model) — PARTIAL(unit) → **ADR-RENEGOTIATED(adr-0038)** ✅ FLIPPED/CLOSED

Closed **exactly per the recorded plan** — the C1 audit kept this row open solely because closing
it would have deleted the map line owing `EdgePrefixStorageFilterTest`; that test now exists and is
green (12), plus `EdgeClientCoreTest$StorageFilterThroughCore` (3) proves the same semantics through
the production core. Verified by reading: matching keys stored; non-matching mutations advance the
version chain WITHOUT storing (from/to preserved; all-filtered batches still advance; the gap guard
still fires on a wrong fromVersion — filtering cannot mask a gap); reads outside the subscription
NOT_FOUND; `secure/` ALWAYS stored regardless of subscription; empty subscription = full store;
`filterForStorage` deterministic/idempotent (the read-store lockstep contract). Both halves of the
ADR-0038-renegotiated clause are now executable, matching the CT-17 closure standard. Residual
noted, not load-bearing: no process run with a non-empty prefix subscription yet (the serving
surface consumes the same lockstep-filtered store); C6 Compose should include one prefix-subscribed
edge.

### CT-34 (hot-path law) — stays **PARTIAL(unit)**, evidence upgraded ⚠ deliberately NOT flipped

`LocalConfigStoreReadBenchmark` landed (getMiss/getIntoHit/getHit/getHitWithCursor, sizes
1k/10k/100k) and was run with `-prof gc` on this box — figures recorded in the Javadoc + design
note §6: getMiss ≈6ns/0 B/op, getIntoHit ≈117ns/0 B/op, getHit ≈89ns/32 B (exactly the one
documented `ReadResult`). **Refused the flip:** the row's level is GATE, and the gate does not
exist — `gates/gate-3.sh` is not written, and there is no machine-readable saved run artifact under
`perf/results/` (only an April placeholder). Javadoc-recorded numbers are evidence a human ran it
once; a GATE row requires the mechanical re-runnable check. Also kept visible for the
review-architect's C2 sign-off: the benchmark/design declare the §3 law's scope boundary at the
in-process read path, pricing the HTTP shell as non-hot-path — a scope ruling, not mine to ratify.

### CT-35 (read-your-writes) — PARTIAL(unit) → **PASSING** ✅ FLIPPED

`EdgeNodeIntegrationTest#writePropagatesOverSignedChainAndServesWithCursor` is the clause run
end-to-end: HTTP write → `Committed: seq=S` → boundary → wire → verified apply → edge read with
cursor S. The poll helper is the proof's core: every pre-propagation response MUST be the immediate
refusal (no blocking ryw_timeout — §6:187 as amended), and any 200 is asserted version ≥ S; so the
test fails on either a blocking serve or a below-cursor serve anywhere along the way. Repeated for
a second write. Single-edge/single-region = exactly the clause's "same region" scope; the
"any edge node" multi-edge form remains CT-09's (unclaimed by C2, unchanged).

### CT-37 (strong-read fail-closed) — UNIMPLEMENTED → **PASSING** ✅ FLIPPED

`EdgeStrongReadFailClosedTest` (read in full) is the clause with no soft edges: the `secure/` value
IS stored (byte-checked through the core — the ADR-0038 always-store half, C2-2 ruling), and the
serving path fail-closes 503 + `X-Fail-Closed: strong-read` with AND without a satisfiable cursor,
no value bytes in any response; a normal key serves from the same store; the refusal metric moved;
and the RR-098 disk sweep walks `--data-dir` proving the literal secret bytes never land on disk
(epoch metadata only). `EdgeHttpServerTest` additionally pins that the fail-close happens BEFORE
the store is consulted and that a satisfiable cursor does not unlock it. The serving-half question
the old row left open is answered: store-and-never-serve, fail-closed (routing to ReadIndex is the
client's move, RR-020-consistent). RR-098 residual (in-memory exposure on edge hosts) is priced to
S5, per the design note §8.

### CT-40 (edge process mTLS) — UNIMPLEMENTED → **PASSING** ✅ FLIPPED

The clause's substance is process-proven in both directions with the control plane's own TLS stack
(`TlsConfig`/`TlsManager` — consistency BY CONSTRUCTION, not by resemblance): trusted client cert →
subscribes and **applies a published notification** (functional proof, not a handshake check);
rogue client cert → never subscribes while the reconnect counter proves the edge kept trying (the
timing-robust unusable-connection discipline, find0051); rogue SERVER cert → the client trust path
refuses (F-0051 endpoint identification — coverage the C1 server-half test could not give). Wire
edgeId deliberately differs from the cert DN (cert-DN-authoritative, RR-094). Honest corners
recorded in the row rather than blocking the flip (all are fixture limitations, not untested
guarantees): edge-side absent-client-cert not exercised edge-side (server-side no-cert rejection is
C1-proven in `FanOutServerMtlsTest`); CLI `TlsConfig.mtls` path test-injected via
`EdgeNodeMain.start(cfg, tls)` (keytool cannot produce the empty store password — the documented
precedent, pre-existing debt class); bounded connect/handshake are implemented constants
(1s/2s, verified in `EdgeStreamClient`, mirroring `TcpRaftTransport`) but not behavior-asserted
under a stalled peer — see REQUIRED-adjacent gap 3 below.

### Other rows touched (notes-only updates, statuses unchanged)

- **CT-06** (UNIMPLEMENTED): the DISCONNECTED *trigger* now exists and fires exactly once per
  entry (`EdgeNodeMetricsTest#disconnectedTransitionFiresTheRebootstrapSeamOnce`;
  `edge_rebootstrap_triggered_total`; boot state excluded), detected with no live stream. The
  seam is a stub — nothing re-bootstraps — so the row stays UNIMPLEMENTED with C3, per the design
  note's own claim ("the named stub seam + metric only").
- **CT-22** (PARTIAL): the C1 REQUIRED scan-scope gap is **fixed** (04384b5: configd-server +
  configd-edge-cache added; self-check intact) — but a **new gap of the same class** opened: the
  `configd-edge-node` module (created in 37cf3c6, after the hardening commit) is not in
  `SCAN_ROOTS`. Grep: zero `deltasSince`/`FanOutBuffer` refs there today, so the guarantee holds,
  but the guard cannot catch an edge-node regression. One-line addition owed.
- **CT-38** (PARTIAL): C2's edge series are all emitted, eagerly registered, and pinned
  (registration: `EdgeNodeMetricsTest`; presence+movement at a live `/metrics`); the owed list
  shrinks (staleness gauge ✓, cursor lag ✓, CT-04 counter ✓) but the row stays open: staleness
  HISTOGRAM, CT-27..30 transition counters, V2 probe histograms, `configd.edge.poison_pill`, and
  the consolidated `EdgeMetricsContractTest`.
- **CT-39** (PARTIAL): the edge-process half of the headline now exists (1 CP × 1 edge process
  propagation over the real wire); the C6 Compose E2E is still the closing condition.

## Rows flipped (old → new)

| Row | Old | New |
|---|---|---|
| CT-01 | UNIMPLEMENTED | PASSING |
| CT-03 | UNIMPLEMENTED | PASSING |
| CT-04 | UNIMPLEMENTED | PASSING |
| CT-05 | UNIMPLEMENTED | PASSING |
| CT-07 | PARTIAL(unit) | PASSING |
| CT-08 | UNIMPLEMENTED | PASSING |
| CT-11 | PARTIAL(unit) | PASSING |
| CT-12 | UNIMPLEMENTED (blocked) | PASSING (against §3 as amended; C2-4 ruling) |
| CT-13 | UNIMPLEMENTED | PARTIAL(unit) — C3 re-bootstrap/process-restart half still owed |
| CT-23 | PARTIAL(unit) | PASSING |
| CT-25 | PARTIAL(unit) | ADR-RENEGOTIATED(adr-0038) — closed per the C1 audit's recorded condition |
| CT-35 | PARTIAL(unit) | PASSING |
| CT-37 | UNIMPLEMENTED | PASSING |
| CT-40 | UNIMPLEMENTED | PASSING |

## Rows deliberately NOT flipped (refusals, each with the named reason)

- **CT-02** stays PARTIAL(unit) — the SIM mechanism is proven, but INV-S2 is a performance bound
  over real propagation; flipping would close the line that tracks the Session-5 measurement. The
  legacy in-suite INV-S2 ratio test remains self-measuring (CM-049) and is now explicitly
  disclaimed as evidence in the row.
- **CT-16** stays PARTIAL(unit) — the seq rule now runs in the process drain, but the clause's
  GAP→replay orchestration at the edge is C3's and unbuilt.
- **CT-34** stays PARTIAL(unit) — a GATE row cannot pass on Javadoc-recorded numbers; gate-3.sh
  does not exist and no machine-readable run artifact was saved.
- **CT-06** stays UNIMPLEMENTED — a tested trigger firing a stub seam is not a re-bootstrap
  sequence; C2's own design note agrees.
- **CT-22** stays PARTIAL(unit) — the C1 gap was fixed but the same gap class recurred for the new
  configd-edge-node module.

## REQUIRED gaps found

1. **CT-22 scan scope, recurrence (REQUIRED, code change — out of my doc-only lane, named for the
   lead):** add `configd-edge-node/src/main/java` to `SCAN_ROOTS` in
   `configd-distribution-service/src/test/java/io/configd/distribution/fanout/NoDeltasSinceOnConsumerPathTest.java`.
   The C1 audit's gap was fixed in 04384b5, then C2(b) created a new consumer-adjacent module
   outside the roots — the second occurrence of this failure mode; consider scanning every
   `*/src/main/java` with an explicit exempt-list so new modules are covered by default.
2. **CT-34 gate artifact (for gate-3):** the JMH `-prof gc` run must be re-run by `gates/gate-3.sh`
   with its output saved under `perf/results/` and the 0 B/op rows asserted mechanically; the row
   stays PARTIAL until then. The declared law-scope boundary (HTTP shell excluded) needs the
   review-architect's explicit ratification at the C2 sign-off.
3. **CT-40 bounded-handshake behavior (non-blocking, named):** `CONNECT_TIMEOUT_MS`/
   `HANDSHAKE_TIMEOUT_MS` exist and mirror `TcpRaftTransport`, but no test stalls a peer to prove
   the bound bites. Candidate: a C6/chaos leg (accept-then-black-hole endpoint) or a register row
   for Session 4's chaos surface.
4. **INV-M1 SEVERE log noise (register row, from design note §8):** every cursor-behind refusal —
   including routine post-failover catch-up — emits a SEVERE `InvariantMonitor` log via the
   contract-mandated routing. Pre-existing behavior now exercised at much higher frequency by the
   consistent-refusal surface; needs a register row + Session 6 alert-design note so the SEVERE
   stream stays meaningful.
   > *Reconciliation (lead, 2026-06-11, post-audit):* DISCHARGED in parallel — the sign-off
   > review (Finding 7) registered exactly this as **RR-099** (P3, owner S6, with the
   > reviewer-reproduced live evidence and the conflation analysis). No second row needed.
   > Likewise gap 2's "law-scope boundary needs the review-architect's explicit ratification"
   > is discharged by the sign-off's Finding 4 (boundary ruled honest, CT-34 claims recorded);
   > the MECHANICAL gate artifact remains owed and is tracked for the gate-3 assembly.

## Defects found in tests' probative value

- **`StalenessUpperBoundTest#edgeStalenessStaysWithinBoundsUnderNormalConditions`** (testkit):
  still stamps `commitTs = clock.currentTimeMillis()` inside its own 20ms sync loop — the INV-S2
  ratios it asserts are true by construction of the loop, not evidence about propagation (CM-049
  survives for this test). Not load-bearing for any flipped row; disclaimed in CT-02's row. Worth
  deleting or rewriting when V2's probe work lands, so nobody re-cites it.
- No other vacuity found: the C2 suites consistently assert the negative space (headers absent when
  CURRENT, within-skew not counted, true-miss vs refusal distinguished, boot state not a
  transition, rejection ≠ gap), which is what makes the flips defensible.

## New summary line (recounted: 21 + 11 + 0 + 6 + 2 + 1 = 41)

```
CONTRACT-MAP-SUMMARY: total=41 passing=21 partial=11 failing-captured=0 unimplemented=6 adr=2 na=1
```

## Sign-off

The contract-qa sign-off line in `c2-edge-node-design-note.md` may be marked against this audit:
C2's claims in the as-built note §9 were verified test-by-test and are accurate as stated — every
claimed row either flipped on real evidence or stayed open for exactly the residual the note's §8
names. The two follow-ups that gate later flips: the CT-22 scan-roots recurrence (gap 1) and the
CT-34 gate-3 mechanical step (gap 2).
