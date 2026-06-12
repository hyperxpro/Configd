# C3 As-Built Design Note — Catch-up, Replay, Gap Detection (ADR-0040)

> **Status: AS-BUILT** — for dual sign-off (review-architect + contract-qa, charter §2).
> Test names are citations. Draft: `c3-catchup-design-draft.md`; screen conditions:
> `../reviews/c2-c5-design-screen.md` §C3; governing ADR: ADR-0040 (Accepted,
> pre-ratified). Deviations §7; named gaps §8.

## 0. Headline: a production-restart wedge found by the row-mandated test, fixed server-side

The CT-13 process test (`MonotonicReadAcrossEdgeRestartTest`, configd-edge-node) found
that **every production edge restart would wedge at version 0**: a restarted edge reads
its persisted SEC-017 `epoch.lock` floor and — correctly, by F-0052 design —
REPLAY_REJECTS every tail-redelivered old-epoch delta. C1's `decideMode` gave TAIL to a
cursor-0 subscriber with a small backlog, so the restarted edge sat rejecting everything
behind the production ack-lag threshold (8192 seqs). A second hole with the same fix:
after a server restart the ring retains only seqs > the restored version, so
`readSince(0)` returns a non-genesis run with no GAP signal.

**Fix** (`FanOutSessionCore.decideMode`): a cursor-0 subscriber gets SNAPSHOT_FIRST
whenever any data exists; an empty ring stays TAIL. Snapshots are epoch-free cumulative
state, monotonic-version-guarded — always epoch-safe; snapshot+cutover is also exactly
C5's bootstrap mechanism. **This supersedes C1's backlog-vs-queueFrames refinement (a
pinned decision) and requires explicit sign-off ratification.**
`FanOutSessionCoreBoundaryTest#freshBacklogBoundaryAtQueueFramesDecidesMode` is
rewritten as the new rule's regression tripwire; six C1 streaming-mechanics fixtures
migrated to subscribe-on-empty-then-publish with no assertion weakened. Gate-path
impact: none (sim sessions subscribe before any commit → empty ring → TAIL); the
507-seed sweep statistics are byte-identical to the C2 baseline. Registered as a
P1-class found-and-fixed register row (second-agent reproduction: revert `decideMode`,
re-run `MonotonicReadAcrossEdgeRestartTest`, observe the wedge).

## 1. Recovery model (draft §2, built as drawn; zero new wire surface)

Recovery is **resubscribe-only** (screen condition C3-1): no new frame, no new field, no
wire-version bump. The server's existing decision machinery routes a resubscribing
cursor: within the replay horizon → TAIL delta replay (contiguous from cursor+1); behind
the tail (beyond horizon) → SNAPSHOT_FIRST re-bootstrap; the C1 ack-lag→demotion path
remains the in-session heal.

- **Edge half** (`EdgeClientCore`): GAP_DETECTED queues
  `ConnectionDirective.ReconnectResubscribe(cursor)` — one per wedge (latched), and
  suppressed while a snapshot is expected (`SUBSCRIBE_OK(SNAPSHOT_FIRST)` /
  `DEMOTED_TO_CATCHUP` set the latch; `SNAPSHOT_END`/`onReconnected` clear it) so C1's
  in-session heal stays primary. Cited:
  `EdgeClientCoreTest$GapResubscribeDirective` (3).
- **DISCONNECTED → re-bootstrap (CT-06)**: an **entry-transition** detector in
  `tick()` (baseline seeded at construction so boot never fires; re-seeded at
  `onReconnected()` so a mid-disconnect entry cannot bounce a fresh connection); fires
  the re-bootstrap orchestration exactly once per entry. Sim:
  `EdgeReBootstrapOnDisconnectTest` (testkit — partition → ladder → ONE firing →
  resubscribe at CURRENT cursor → convergence); process: `EdgeReBootstrapOnDisconnectTest`
  (edge-node — composed hook, reconnect counted, converges). The cursor on a CT-06
  re-bootstrap is the CURRENT cursor (the draft's rule; cursor 0 is reserved for poison
  quarantine), and `EdgeStreamClient` derives the SUBSCRIBE cursor from core state
  (`quarantined ⇒ 0, else core.cursor()`) — never one-shot directive memory, so a failed
  connect cannot lose a forced re-bootstrap.
- **Server half**: `decideMode` (§0) + the §6-rule-8 observability:
  `FanOutSessionMetrics.onSubscribeMode(snapshotFirst, horizonDistance)` with
  `horizonDistance = cursor − (oldest − 1)` (≥0 tail-recoverable; <0 beyond horizon;
  empty ring = cursor+1), exported as `edge_fanout_subscribe_tail_total` /
  `edge_fanout_subscribe_snapshot_first_total` / `edge_fanout_subscribe_horizon_distance`.
  Cited: `ReplayHorizonBoundaryTest#subscribeModeMetricReportsTheDecisionAndTheExactHorizonDistance`.

## 2. Horizon boundary — the four-leg matrix under concurrent writes

`ReplayHorizonBoundaryTest` (distribution-service, 5): cursor exactly at the horizon
edge (TAIL, zero snapshots), one below (SNAPSHOT_FIRST), one above (TAIL), and the
screen-demanded **deterministically-forced lapped-after-TAIL race**
(`lappedAfterTailDecisionSelfHealsViaGapDemoteSnapshotResume`: TAIL decided, the writer
laps the cap-8 ring between decision and first drain → GAP → demote(REASON_GAP) →
snapshot → resume), writes flowing through every phase, judged by full-state
byte-equality against the writer model (exactly-once-over-effect).
`CatchUpProtocolTest` (2) pins protocol selection through the resubscribe path:
gap<window → pure delta replay (no SnapshotBegin); gap>window → multi-chunk snapshot
then contiguous tail. `EdgeGapRecoveryTest` (testkit, 3) proves the same end-to-end in
the sim — within-horizon replay with **zero new snapshots** (ack-lag heal disabled so
the recovery is provably C3's), beyond-horizon snapshot heal, and a 20-seed
full-adversarial sweep with recovery live: zero safety violations.

## 3. Poison pill (ADR-0040; screen condition C3-2)

`PoisonPillPolicy` (edge-cache, NEW): bounded retries per seq (reusing
`PoisonPillDetector`, keyed `seq:N`, per ADR-0040 "re-pointed at apply exceptions") →
quarantine (`configd.edge.poison_pill` + SEVERE structured log) → **forced snapshot
re-bootstrap** (resubscribe at cursor 0) → **TERMINAL** (latched;
`configd.edge.poison_pill_terminal` emitted before exit). Recovery: `onProgress(cursor)`
clears the in-flight count once the failing seq is passed and ends the quarantine once
the snapshot covers it. A different-seq failure during re-bootstrap exits the quarantine
as a fresh failure; skipping a seq is structurally impossible (no skip path exists).
Wired at the previously-commented `EdgeStreamClient` catch site; the remaining catch is
only a non-apply protocol backstop. Terminal behavior: SEVERE structured log → final
metrics pump → stop → `terminalAction` (production: `System.exit(3)`,
`EdgeNodeMain.EXIT_POISON_TERMINAL`; injectable for tests — the TlsManager-seam
precedent). Named config: `--poison-max-retries` (`edge.poisonpill.maxRetries`,
default 3). Apply faults are injected via `EdgeClientCore.ApplyFaultInjector`, a public
TEST-ONLY seam (the `loadSnapshotForced` precedent): Configd stores opaque bytes, so no
real delta can be made to throw; the injected throw exercises the production catch and
ladder verbatim.

Cited: `PoisonPillRebootstrapTest` (edge-cache, 18 — ladder, batch abort without gap
pollution, snapshot-past-poison recovery, BOTH terminal conditions, terminal-latched
inert-but-serving core, scope edges: signature rejection never touches the policy) and
`PoisonPillRebootstrapTest` (edge-node, 2 — recovery leg over the real wire with the
poisoned key served FROM THE SNAPSHOT and the process LIVING; terminal leg with
`terminalAction` exactly once, metric scraped, no hot loop). Post-§0, the
quarantined-seq-redelivered-as-TAIL terminal corner is unreachable over the real wire
(cursor-0 always snapshots when data exists) — kept pinned as defense-in-depth.

## 4. Negative caching / not-subscribed refusal (ADR-0040 §2 descope honored)

Architecture §8's negative caching is descoped per ADR-0040 §2 (the in-slice miss is
already authoritative). What C3 adds is the honest **out-of-slice refusal**:
`GET` on a key outside the subscribed slice → `404 + X-Configd-Refused: not-subscribed`
+ `edge_read_refusals_not_subscribed_total`, decided BEFORE the store is consulted
(strong-read 503 takes precedence); an in-slice miss stays a plain authoritative 404.
Cited: `NotSubscribedReadTest` (process), `EdgeHttpServerTest#notSubscribedKey…` (unit).
`EdgeConfigClient.servesKey`/`PrefixSubscription.isEmpty()` are the alloc-free
predicates.

## 5. CT-13 — the C3 re-bootstrap half (the wedge-finder)

`MonotonicReadAcrossEdgeRestartTest` (edge-node): real `EdgeNodeMain` killed and
restarted on the same data dir (only `epoch.lock` persists — swept), a held client
cursor refuses (`404 cursor-behind`) on EVERY response until the re-bootstrap reaches
the cursor, then serves post-restart bytes at version ≥ cursor — never pre-crash data;
the recovered edge converges on new writes. Complements the C2 sim-level half
(`MonotonicReadAcrossEdgeRestartTest`, testkit).

## 6. Finding 5 disposition: DELETED

`EdgeConfigClient.applyDelta(ConfigDelta)` (one-arg) and `DeltaApplier.offer(ConfigDelta)`
+ the `NO_COMMIT_TIMESTAMP` sentinel are **deleted**, not fenced;
`offer(delta, commitTs)` rejects negative timestamps loudly. The fallback was the
ADR-0039 idle-proxy in different clothes for any caller taking the convenient path.
Every caller now states which clock stamps the frontier (~20 test sites pass their
fixture clock explicitly; byte-identical behavior). Recorded in the methods' javadoc
citing c2-signoff-review Finding 5.

## 7. Deviations (each justified)

1. **`decideMode` supersession** (§0) — supersedes a C1-pinned decision; explicit
   sign-off ratification requested.
2. **CT-31 chunk-level resume renegotiated to transfer-level self-healing**
   (`SnapshotChunkResumeTest`: lost transfer → unacked → ack-lag re-demote → re-send
   whole → contiguous resume): chunk-level resume would be new wire surface + new
   failure modes; re-send is idempotent; the sim measured 100% heal. Rationale in the
   test javadoc (consolidated doc pass may lift it).
3. **CT-06 cursor**: current-cursor resubscribe (draft/screen rule), NOT cursor-0
   (cursor 0 is the poison-quarantine path only).
4. `ApplyFaultInjector` public TEST-ONLY seam (justified in javadoc; the
   `loadSnapshotForced` precedent).

## 8. Named gaps / handoffs

- Forked-JVM literal exit-code-3 observation untested (a subprocess poison test is
  impossible by ADR-0040's own opaque-bytes premise); the exit wiring is one lambda,
  pinned by the recorder seam.
- `CatchUpService` is now fully superseded shelfware (zero consumers); its deletion is
  C4-1's register row — recommendation to C4: DELETE.
- Poison scenarios are pinned core+process, not sim-driven; the sim's
  `terminalFailures()` channel exists for future use.
- `edge_fanout_subscribe_horizon_distance` is a last-decision gauge (registry has no
  labeled histograms); documented in the metric javadoc.
- Map flips (CT-06/13/16/31/32/33) belong to the contract-qa audit.
- Pre-existing C1-era mutation survivors in `FanOutSessionCore`/`EdgeFrameCodec`
  unchanged (named in the PIT report; not regressed by C3).

## 9. Verification snapshot

Full reactor `clean test` green (agent run + lead's independent run). PIT (RUN_ERROR=0
all three): edge-cache 83.9% (PoisonPillPolicy 91.5%), edge-node 83.1%,
distribution-service 74.3% (FanOutSessionCore 66.7%, up from 61.8% post-rework).
`EdgeSeedCompatTest` digest byte-identical; 507-seed sweep stats byte-identical to the
C2 baseline; no new mixSeed tags consumed.
