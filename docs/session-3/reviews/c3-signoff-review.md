# C3 Component Sign-off Review — review-architect

> **Scope (JOB A):** the C3 catch-up / replay / gap-detection component sign-off against
> charter §1 rule 2's DONE-definition. As-built note:
> `docs/session-3/design/c3-catchup-design-note.md` (commit `f2d732c`). Prior gates:
> `docs/session-3/reviews/c2-c5-design-screen.md` §C3 (CLEARED-WITH-CONDITIONS, C3-1/C3-2
> tracked to THIS sign-off; ADR-0040 pre-ratified there, authored before implementation —
> sequencing gate honored), `docs/decisions/adr-0040-poison-pill-and-negative-cache.md`
> (Accepted). **Reviewer:** review-architect. **Date:** 2026-06-12. **Branch:**
> session-3-data-plane. Read-only on code except this file + one register row (RR-100
> flip, the owed P1 second-agent reproduction — transcript below). Maven runs executed
> after confirming no competing build (`pgrep -f "[o]rg.apache.maven"` clear before each):
> the RR-100 revert/restore cycle (`MonotonicReadAcrossEdgeRestartTest`, edge-node:
> FAILED-as-registered → GREEN), then targeted re-runs —
> `FanOutSessionCoreBoundaryTest` (4), `ReplayHorizonBoundaryTest` (5),
> `CatchUpProtocolTest` (2), `SnapshotChunkResumeTest` (1), `FanOutSessionCoreTest` (15),
> `PoisonPillRebootstrapTest` + `EdgeClientCoreTest` (edge-cache, 61 w/ nested),
> `EdgeSeedCompatTest` (1) — **all green, fresh surefire reports confirmed by mtime**.
> Full reactor green is the committer's verified claim, not re-run here (2-vCPU
> discipline). No PIT re-run (figures accepted as recorded; gate-3 re-runs per the map).

Severities: **BLOCKING** (gates the sign-off), **REQUIRED** (must land, tracked, does not
gate), **NOTE** (advisory). Every item carries a prod-blocking / non-blocking flag.

## Verdict: **SIGN-OFF** (no BLOCKING findings; 1 REQUIRED, 6 NOTE)

C3 meets the charter §1 rule-2 DONE bar. Both screen conditions (C3-1, C3-2) are
**discharged with code/test evidence** (table below). The as-built note is accurate
against the code — every load-bearing claim I checked (mechanisms, test names, test
counts, metric series, deviations, the §0 wedge anatomy) matched, with two naming nits
(Finding 6). The **RR-100 second-agent reproduction succeeded in both directions** (wedge
under the C1 rule, green under the C3 rule — transcript below; register row flipped to
RESOLVED). The **`decideMode` supersession is RATIFIED** (ruling below). The ADR-0040
ladder is implemented with §1.3 fidelity and the directive plumbing survived the
interleaving hunt with no P1 (Finding 2). The one REQUIRED item is a stale javadoc that
contradicts the Finding 5 deletion (Finding 4). **C4 implementation may start.**

---

## Screen conditions C3-1 / C3-2 — both DISCHARGED (verified, not taken from the note)

| Condition | Verdict | Evidence (verified in code/tests, file:line) |
|---|---|---|
| **C3-1** resubscribe-only recovery, zero new wire surface; lapped-after-TAIL race forced deterministically | **DISCHARGED** | (a) **Zero wire surface:** `git diff 9595dea..f2d732c -- …/distribution/wire/` is EMPTY; `EDGE_WIRE_VERSION` still `0x01` (`EdgeFrameCodec.java:73`); no new frame type; the new recovery surface is `EdgeClientCore.ConnectionDirective` — core-internal, drained by the shell/sim, never encoded (`EdgeClientCore.java:103-141`). Recovery is a fresh SUBSCRIBE carrying the cursor (`EdgeStreamClient.openAndSubscribe:430-433`), resolved by the server's existing `decideMode`. (b) **The race is genuinely forced, not statically approximated:** `ReplayHorizonBoundaryTest#lappedAfterTailDecisionSelfHealsViaGapDemoteSnapshotResume` (`:216-251`) decides TAIL at the horizon-edge cursor 12, then publishes 10 commits into the capacity-8 ring BETWEEN the decision and the first drain (`assertTrue(buffer.oldestSeq() > 13)` proves the lap), asserts the first drain demotes with `REASON_GAP`, that no NOTIFY leaks across the gap, and judges the heal by full-state byte-equality vs the authoritative writer model with writes flowing through every phase (`assertConverged`, `:290-306`). The static ±1 matrix (`:148-209`) plus the decision-metric pin (`:347-369`, exact `horizonDistance` values −1/0/2/cursor+1) complete the four legs. Reviewer-reran, 5/5 green. |
| **C3-2** ADR-0040 narrow poison policy with the terminal-fail-loud case pinned | **DISCHARGED** | `PoisonPillPolicy` implements the §1 ladder verbatim: bounded per-seq retries via the existing `PoisonPillDetector` keyed `seq:N` (`PoisonPillPolicy.java:196-217`, `key():238-240`), quarantine emits `configd.edge.poison_pill` + SEVERE then forces cursor-0 re-bootstrap (`:209-216`), terminal latched with `configd.edge.poison_pill_terminal` emitted BEFORE the exit the shell performs (`:219-231`). §1.3's terminal condition is verbatim (`onSnapshotApplyFailure` during quarantine, `:162-171`). Skip-the-seq is structurally impossible: the policy never consults `isPoisoned`, and `EdgeClientCore.applyNotification` has no skip branch — a failure aborts the batch (`EdgeClientCore.java:437-457`). The terminal case is pinned at BOTH levels: core (`PoisonPillRebootstrapTest` edge-cache, 18 — both terminal conditions, latch, inert-but-serving terminal core, scope edges incl. signature-rejection-is-not-poison) and process (`PoisonPillRebootstrapTest` edge-node, 2 — recovery leg: poisoned key served FROM the snapshot, `terminalRuns == 0`, process LIVES; terminal leg: `terminalAction` exactly once, `configd_edge_poison_pill_terminal_total 1` scraped, no hot loop). `EdgeNodeMain` wires `System.exit(EXIT_POISON_TERMINAL=3)` (`EdgeNodeMain.java:57,135-136`), distinct from the config exit 1. ADR-0040's own conditions from the screen — BloomFilter disposition named (retained shelfware, S7), CT-32/CT-33 row flips listed — are in the ADR's Decision §2. Reviewer-reran the edge-cache suite (61/61 w/ EdgeClientCoreTest, green). |

---

## RR-100 second-agent reproduction — DONE; register row flipped to RESOLVED

Performed per the row's protocol, owning the box (no competing Maven, `pgrep` bracket
pattern clear before each run):

1. **Revert:** `FanOutSessionCore.decideMode` locally reverted to the C1
   backlog-vs-queueFrames rule (source: `git show 9595dea:…/FanOutSessionCore.java` —
   the `backlog > config.queueFrames()` branch restored verbatim; tree was clean at
   `f2d732c` beforehand).
2. **Rebuild (stale-artifact trap respected):** `./mvnw -pl configd-distribution-service
   install -DskipTests` (EXIT 0), then the consumer run with `clean`.
3. **Observe the wedge:** `./mvnw -pl configd-edge-node clean test
   -Dtest=MonotonicReadAcrossEdgeRestartTest` → **FAILED exactly as registered**: the
   restarted edge wedged at version 0 — an uninterrupted
   `SEVERE Invariant violated [monotonic_read]: key=svc/k seenVersion=2 newVersion=0`
   stream (the held cursor 2 refusing against a store stuck at 0, never clearing),
   failing at the poll deadline: `edge did not serve svc/k at held cursor 2 within
   deadline` (`pollUntilServed:169`), elapsed 45.75 s.
4. **Restore + confirm:** `git checkout` of the file, module reinstalled, same test
   re-run → **GREEN** (1/1, 0.92 s — the re-bootstrap snapshot lands nearly instantly;
   fresh surefire report mtime 2026-06-12 02:07).

The 45.75 s wedge vs 0.92 s heal is itself diagnostic: pre-fix the restarted edge never
re-bootstraps (TAIL → SEC-017 epoch-floor REPLAY_REJECTs forever); post-fix the cursor-0
SNAPSHOT_FIRST heals immediately. Register row RR-100
(`docs/readiness-register.md:209`) flipped RESOLVED-PENDING-REPRODUCTION → **RESOLVED**
with this transcript summary in the resolution-evidence cell.

---

## RULING: the `decideMode` supersession is **RATIFIED**

A C1-pinned decision (backlog-vs-queueFrames for cursor-0 subscribers) was superseded by
"cursor-0 ⇒ SNAPSHOT_FIRST whenever any data exists; empty ring stays TAIL"
(`FanOutSessionCore.decideMode:178-205`). Judged on the four demanded axes:

1. **Correctness — both wedge mechanisms verified, not taken on faith.**
   (a) *SEC-017 epoch floor:* reproduced live (transcript above) — the server cannot
   observe a restarted subscriber's persisted epoch floor, so ANY tail redelivery of
   old-epoch deltas to a cursor-0-with-epoch.lock edge is rejected as replay; only the
   epoch-free, monotonic-version-guarded snapshot is unconditionally safe.
   (b) *Ring genesis:* verified structurally in `FanOutBuffer` — gap detection is by
   evicted-seq watermark (`lastEvictedSeq`, initialized −1, `FanOutBuffer.java:67-74`); a
   fresh post-server-restart incarnation has evicted nothing, so `readSince(0)` on a ring
   holding only seqs > restored-V returns a non-genesis run with **no server-side GAP** —
   the C1 rule would TAIL it and the edge would gap immediately. Both holes share the one
   fix; snapshot+cutover is also exactly C5's bootstrap mechanism (alignment, not new
   machinery).
2. **Cost — weighed and ruled acceptable.** The marginal cost is one snapshot transfer
   per fresh/restarted subscriber where the old rule would have streamed a small backlog.
   A fleet restart of E edges costs E per-session snapshot serializations — but each is
   per-session chunked through the SAME bounded queue/ack machinery as C1's demotion
   snapshots (no unbounded buffering, no publish-path work, no O(all-subscribers) step),
   so this is not a new cost *class*: C1 already paid exactly this per demoted subscriber.
   The empty-ring TAIL keeps idle-system startups free. No per-update snapshot shipping
   exists on any path. NOTE (non-blocking): if real fleet-restart herds emerge, a
   serialized-snapshot-bytes cache shared across concurrent same-seq transfers is the
   obvious future optimization — a perf-row candidate, not a C3 defect.
3. **Gate-seed neutrality — reviewer-reproduced.** `EdgeSeedCompatTest` re-run green
   (digest byte-identity; the sim subscribes before any commit → empty ring → TAIL → the
   superseded branch is unreachable on the gate path). The 507-seed sweep byte-identity is
   the committer's recorded claim, consistent with the mechanism. The rewritten tripwire
   (`FanOutSessionCoreBoundaryTest#freshBacklogBoundaryAtQueueFramesDecidesMode:61-88`)
   pins the NEW rule **as tightly as the old pin pinned the old**: the new rule's only
   boundary (empty vs any-data) is pinned exactly at its edge (0 → TAIL, 1 →
   SNAPSHOT_FIRST) plus a non-boundary confirmation (5 → SNAPSHOT_FIRST), and the
   nonzero-cursor TAIL legs in `ReplayHorizonBoundaryTest` prevent the rule from leaking
   into snapshot-always. (Test-name nit: Finding 6.)
4. **Fixture migration — verified by reading all six.** 3 in
   `FanOutSessionCoreBoundaryTest` (`batchByteCapSplitsAtTheExactByteBoundary`,
   `cursorAckReleaseIsInclusiveAtTheBoundary`, `slowConsumerWarnFiresAtThresholdExactlyOnce`)
   and 3 in `FanOutSessionCoreTest` (`tickDrainsNotificationsInVerbatimAscendingOrder`,
   `batchMaxNotificationsSplitsIntoMultipleFrames`,
   `cursorAckReleasesInFlightFramesBelowThreshold`) — the diff shows ONLY the
   subscribe-before-publish reorder; every assertion line is byte-identical to C1's. No
   assertion weakened. Reviewer-reran both suites, green.

**Ratified.** The supersession deviation (note §7.1) is closed by this review.

---

## Finding 1 — Catch-up protocol + CT-31 renegotiation: implementation verified; RULING on where the renegotiation is recorded [REQUIRED — ruling; non-blocking]

`CatchUpProtocolTest` (2) pins §7's protocol selection through the real resubscribe path
(gap<window → pure delta replay, no SnapshotBegin, contiguous 5..10; gap>window →
multi-chunk snapshot then contiguous tail at snapshotSeq+1). `SnapshotChunkResumeTest`
pins the renegotiated CT-31 recovery: lost transfer → `lastAckedSeq` NOT advanced (the
C1(a) fix is the load-bearing mechanism) → ack-lag re-demote → idempotent whole re-send →
quiesce on ack → contiguous resume. The renegotiation rationale (chunk-level resume =
new wire surface + per-chunk acks + resume session state vs. idempotent re-send measured
healing 100% on the lossy sim) is sound and currently lives in the two test javadocs.

**Ruling (the check-6 call):** the test-javadoc record is sufficient as the *interim*
citation but is NOT the final resting place for a contract-row mechanism renegotiation.
It must be recorded in the **consolidated architecture/contract doc pass at session
close** — the §7 "resume on failure" wording amended to transfer-level self-healing,
cross-referencing ADR-0034 and these two tests — exactly the C2-4 precedent (a mechanism
clarification with intent preserved goes in the doc/contract pass, NOT a new ADR, and NOT
an ADR-0040 addendum: ADR-0040 owns poison/negative-cache scope and must not accrete
unrelated transfer semantics). The note §7.2's "consolidated doc pass *may* lift it" is
hereby upgraded to MUST. The CT-31 map-row flip is the contract-qa half's, per the
established split; this review's evidence is their input.

## Finding 2 — ADR-0040 directive plumbing: interleaving hunt found no P1; one deliberate asymmetry recorded [NOTE — non-blocking]

Hunted the gap latch + `snapshotExpected` suppression + DISCONNECTED entry detector for
missed-entry / double-fire interleavings, adversarially:

- **Gap latch:** one directive per wedge (`reconnectPending`, `EdgeClientCore.java:469-471`);
  re-arms after the snapshot lands; suppressed while `snapshotExpected || inSnapshot` so
  C1's in-session heal stays primary. Pinned by `EdgeClientCoreTest$GapResubscribeDirective`
  (3) — incl. the re-arm-after-heal leg. ✔
- **Boot exclusion:** the entry baseline is seeded at construction with the boot state
  (DISCONNECTED, `:335-338`), so process start never "enters" DISCONNECTED — pinned by
  `bootStateNeverFiresTheRebootstrap`. `onReconnected()` re-baselines (`:694-697`) so an
  entry that happened while disconnected cannot bounce the fresh connection — pinned. ✔
- **Missed-entry-while-suppressed is recoverable in every case I could construct:** entry
  during `reconnectPending` — the pending reconnect IS the same recovery action; entry
  during `snapshotExpected` with a dead connection — the shell's transport-silence guard
  (`EdgeStreamClient.runConnection:364-367`) cycles the connection, `onReconnected`
  clears the suppression, and the server re-decides. No permanent suppression path
  found. ✔
- **One deliberate asymmetry (the NOTE):** the PROCESS-level trigger
  (`EdgeNodeMetrics.syncFromCore` → `requestRebootstrap`) is NOT `snapshotExpected`-
  suppressed: a DISCONNECTED entry landing mid-snapshot-transfer tears down the in-flight
  transfer once. Bounded — the detector is edge-triggered, the state STAYS DISCONNECTED,
  so the restarted transfer is not re-interrupted (no livelock; worst case one wasted
  transfer at the 30 s boundary). Recorded so it is a decision, not an accident.
- **Cursor derivation cannot lose the forced re-bootstrap or resurrect a stale cursor:**
  the SUBSCRIBE cursor is derived from durable core state at connect time
  (`quarantinedSeq() >= 0 ? 0 : core.cursor()`, `EdgeStreamClient.openAndSubscribe:430`),
  never from one-shot directive memory; the quarantine clears only when `onProgress`
  covers the quarantined seq, so EVERY connect attempt during quarantine — including
  after failed connects, lost directives, or an interleaved silence-reconnect — carries
  cursor 0 until the snapshot actually lands. Post-recovery the derivation reads the new
  (higher) cursor. A backward snapshot remains refused with a real-cursor re-ack
  (`onSnapshotEnd:540-544`). No loss/resurrection interleaving found. ✔
- **Residual double-fault corner (NOTE, no action):** a quarantined edge subscribing at 0
  against a ring that is EMPTY (server restarted+restored, zero new commits) gets TAIL
  and idles — the quarantine resolves only at the first new commit (gap → resubscribe →
  SNAPSHOT_FIRST). The staleness ladder surfaces the window (DEGRADED/DISCONNECTED);
  requires poison + server restart + idle system simultaneously.

## Finding 3 — Charter §6 rule 4 performance screen on the recovery paths: PASS [verified]

- **No unbounded structures:** the directive deque is latch-bounded for reconnects and
  failure-cadence-bounded for poison directives (the shell drains ALL per loop);
  `pendingChunks` is codec-capped; the `PoisonPillDetector` map is bounded in production
  (apply-throws are only reachable on the contiguous next seq — an out-of-order seq GAPs
  before apply — and entries are cleared on progress/release).
- **Terminal does not spin:** the core goes inert (`onFrame`/`tick` return immediately,
  `EdgeClientCore.java:373-377,616-618`), the shell sets `running=false` and runs
  `terminalAction` once (`onTerminalFailure:380-387`); pinned exactly-once at process
  level with the metric scraped first.
- **No O(all-subscribers) work:** `decideMode` + the snapshot transfer are per-session;
  the new `onSubscribeMode` metric is one call per subscribe; nothing touches the publish
  path (CT-22 discipline intact).
- **No per-update snapshot shipping:** snapshots only on subscribe-time decisions and
  C1's demotion path; the cursor-0 rule's cost is per-subscribe-event (ruled in the
  ratification, axis 2).

## Finding 4 — Finding 5 disposition (c2-signoff-review): deletion CONFIRMED; one stale javadoc contradicts it [REQUIRED — non-blocking; trivial fix]

- The one-arg `EdgeConfigClient.applyDelta(ConfigDelta)` and `DeltaApplier.offer(ConfigDelta)`
  + the `NO_COMMIT_TIMESTAMP` sentinel are **gone** — grep finds only the two-arg methods
  and javadoc *records of the deletion*; the deletion is documented at both methods citing
  c2-signoff-review Finding 5 (`DeltaApplier.java:182-188`, `EdgeConfigClient.java:174-179`).
- Negative-timestamp rejection is loud: `offer(delta, ts)` throws
  `IllegalArgumentException` naming the deleted sentinel (`DeltaApplier.java:198-202`).
- No caller regression by construction (the methods do not exist; full module suites
  reviewer-reran green; ~20 test sites pass their fixture clock explicitly per the diff).
- **The defect:** `EdgeConfigClient`'s CLASS-level javadoc still reads "The legacy
  `{@link #applyDelta(ConfigDelta)}` (no commit timestamp) records the frontier from the
  local clock — retained for direct callers and pre-C2 tests" (`EdgeConfigClient.java:36-37`)
  — a dangling `{@link}` asserting the exact local-clock fallback the disposition deleted
  still exists. Documentation-only, but it documents the ADR-0039 idle-proxy as live —
  the precise two-meanings lie the deletion exists to kill. **Fix: delete/replace the two
  sentences.** REQUIRED (blocking-for-honesty of the documented surface, not for prod).

## Finding 5 — ApplyFaultInjector public test seam: placement JUSTIFIED, guards verified [verified, no finding]

The justification is honest and specific: Configd stores opaque bytes, so no delta that
decodes and verifies can be made to throw through the production codec/applier — ADR-0040
is a defensive net for defects that do not yet exist, and the ONLY way to exercise the
production catch → policy → directive → terminal path verbatim is to inject the throw
inside the try-block (`EdgeClientCore.java:222-238,440-441,546-547`). Guards verified:
interface + setter javadoc say TEST-ONLY; the setter is named `setApplyFaultInjectorForTest`;
the field defaults null; **zero production callers** (grep: only the two test files);
`public` is necessary (the edge-node tests live in another module) and follows the
`loadSnapshotForced` precedent. Hot-path cost is two predictable null-checks on the
single-writer apply path (not the read hot path). ACCEPT.

## Finding 6 — Note/naming accuracy nits [NOTE — non-blocking]

- The as-built note §1 (and the commit message) name the directive
  `ConnectionDirective.ReconnectResubscribe(cursor)`; the actual type is the C2-era
  `ConnectionDirective.ReconnectNextEndpoint` (reused, correctly). Recorded so nobody
  greps for a type that does not exist.
- `FanOutSessionCoreBoundaryTest#freshBacklogBoundaryAtQueueFramesDecidesMode` now
  misdescribes the rule it pins (there is no queueFrames boundary anymore — the javadoc
  inside is accurate, the method name is fossil). Rename at the next touch.
- The `ReconnectNextEndpoint` record javadoc says "the shell must subscribe at the
  directive's cursor, not the core's" (`EdgeClientCore.java:110-114`) while
  `EdgeStreamClient` deliberately (and more robustly) derives from core state, ignoring
  the directive's cursor; the sim sink uses the directive's cursor (`EdgeFanOutSim:284`) —
  equivalent there because the sim sink is synchronous and lossless. The javadoc should
  state the derivation contract ("the cursor MUST be derived from core state at SUBSCRIBE
  time; the directive's cursor is advisory") so the next shell author copies the robust
  pattern, not the sentence.
- Counts verified: `PoisonPillRebootstrapTest` edge-cache = 18 (3+1+4+3+7 nested),
  edge-node = 2, `EdgeClientCoreTest` 43 w/ nested (37 C2 + 6 new) — note and code agree.

## Finding 7 — Not-subscribed refusal (ADR-0040 §2 descope honored) [verified, no finding]

The descope's obligations landed exactly: `servesKey` checked BEFORE the store is
consulted, AFTER the strong-read 503 (precedence verified, `EdgeHttpServer.java:163-185`);
404 + `X-Configd-Refused: not-subscribed` + its own counter
(`EdgeNodeMetrics.REASON_NOT_SUBSCRIBED`); in-slice miss stays a plain authoritative 404.
`BloomFilter` remains unwired shelfware as ADR-0040 requires (its tests still run —
correct, it is tested-but-unwired by decision). Pinned by `NotSubscribedReadTest`
(process) and `EdgeHttpServerTest#notSubscribedKeyRefusesDistinctlyWhileInSliceMissIsAuthoritative`.

---

## Prior conditions from the C2–C5 screen: status

| Screen condition | Status |
|---|---|
| C3-1 (resubscribe-only, no new frames; lapped-after-TAIL forced deterministically) | **DISCHARGED** (table above; wire diff empty; race forced + byte-equality judged; reviewer-reran) |
| C3-2 (ADR-0040 authored+ratified before implementation; terminal-fail-loud pinned) | **DISCHARGED** (ADR-0040 Accepted, dated pre-implementation; both terminal conditions pinned core+process; reviewer-reran) |
| ADR-0040 condition (i): BloomFilter disposition + CT-32/33 flips named in the ADR | **DISCHARGED** (ADR-0040 Decision §2; map-row flips owed to contract-qa per the established split) |
| C4-1 forward-handoff (`CatchUpService` disposition) | **ON TRACK** — grep confirms zero `src/main` consumers (fully superseded shelfware); note §8 hands the DELETE to C4-1's register row, which the C4 sign-off must verify |

## SIGN-OFF STATEMENT

C3 (catch-up, replay, gap detection, ADR-0040 poison-pill) is **SIGNED OFF** as DONE per
charter §1 rule 2: the recovery paths run in the simulator under adversarial schedules
(`EdgeGapRecoveryTest` 20-seed sweep with recovery live, `EdgeReBootstrapOnDisconnectTest`
sim leg — both opt-in seams, gate digest reviewer-reproduced byte-identical), the
unit/property/process battery passes (targeted suites reviewer-reproduced green: 5 + 5
distribution-service suites, 61 edge-cache tests, the CT-13 process wedge-finder), and
the as-built note is accurate against the code with both screen conditions discharged.
The **RR-100 P1 reproduction succeeded in both directions** and the register row is
RESOLVED. The **`decideMode` supersession is RATIFIED** on correctness (both wedges
verified), cost (no new cost class; empty-ring TAIL preserved), gate neutrality
(reviewer-reproduced), and tripwire tightness (boundary pinned at its exact new edge; six
migrated fixtures assertion-identical). The REQUIRED items: the stale
`EdgeConfigClient` class javadoc (Finding 4 — trivial) and the CT-31 doc-pass recording
ruling (Finding 1 — session-close obligation, "may" upgraded to MUST). The NOTEs
(process-trigger snapshot asymmetry, quarantine-on-empty-ring double-fault corner,
fleet-restart snapshot-cache candidate, naming nits) are tracked, none gating.
**C4 implementation may start**, subject to its own screen conditions (C4-1 register-row
discipline for the `SlowConsumerPolicy` AND `CatchUpService` deletes; C4-3
anti-lockout cooldown + GAP-vs-distress weighting).

— review-architect, 2026-06-12
