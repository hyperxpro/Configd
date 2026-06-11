# C2 Component Sign-off Review — review-architect

> **Scope (JOB A):** the C2 edge node process component sign-off against charter §1 rule 2's
> DONE-definition (runs in the simulator under adversarial schedules AND unit/property tests
> pass AND a signed design note). As-built note:
> `docs/session-3/design/c2-edge-node-design-note.md` (commits `7034f67` part a + `37cf3c6`
> part b, note `a771550`). Prior gate: `docs/session-3/reviews/c2-c5-design-screen.md` §C2
> (CLEARED-WITH-CONDITIONS, conditions C2-1..C2-4 tracked to THIS sign-off).
> **Reviewer:** review-architect. **Date:** 2026-06-11. **Branch:** session-3-data-plane.
> Read-only on code except this file + one register row (RR-099, Finding 7). One targeted
> Maven run executed after confirming no competing build (`pgrep`/`ps` clear — the earlier
> PID 714056 had exited): `EdgeSeedCompatTest`, `EdgeFailoverTest`, `EdgeClientCoreTest`
> (37 w/ nested), `StalenessSkewTripwireTest` (7), `EdgeStrongReadFailClosedTest` — all
> green, EXIT=0 (fresh surefire reports confirmed by mtime). Full reactor green is the main
> thread's verified claim, not re-run here (2-vCPU discipline).

Severities: **BLOCKING** (gates the sign-off), **REQUIRED** (must land, tracked, does not
gate), **NOTE** (advisory). Every item carries a prod-blocking / non-blocking flag.

## Verdict: **SIGN-OFF** (no BLOCKING findings; 1 REQUIRED, 6 NOTE)

C2 meets the charter §1 rule-2 DONE bar. All four screen conditions (C2-1..C2-4) are
**discharged with code/test evidence** (table below). The as-built note is accurate against
the code — every load-bearing claim I checked (mechanisms, test names, test counts, metric
series, JMH figures, deviations, residuals) matched; nothing is silently under-delivered.
The threading review of the new shell found **no P1** (Finding 1). The part-(a) defect fix
is ruled **sound** with no staleness-masking window (Finding 2). The one REQUIRED item is
the INV-M1 SEVERE-noise register row, which I added myself as **RR-099** (Finding 7).
**C3 implementation may start** (ADR-0040 exists and was ratified at the screen; its C3
conditions stand unchanged).

---

## Screen conditions C2-1..C2-4 — all DISCHARGED (verified, not taken from the note)

| Condition | Verdict | Evidence (verified in code/tests, file:line) |
|---|---|---|
| **C2-1** sim-parity digest + INV-M1 liveness | **DISCHARGED** | (a) `EdgeSeedCompatTest.edgeMachineryDoesNotPerturbControlPlaneDigest` genuinely compares digests: SHA-256 fold of per-tick CP state (role/term/leader/log indices/store version, `EdgeSeedCompatTest.java:72-104`), plain `AdversarialSim` vs `EdgeFanOutSim` at 0 and 3 edges, 3 seeds × 1200 ticks — **reviewer-reran, green**. (b) `EdgeActor` drives the REAL `EdgeClientCore` (`EdgeActor.java:74-75,125`) with a **test-mode** `InvariantMonitor` (`:118-120` — a `monotonic_read` violation throws and fails the seed, so INV-M1 is live under the 507-seed gate). (c) The mechanism: `EdgeClientCore` builds its read store WITH the monitor (`EdgeClientCore.java:244`); the HTTP shell routes refused reads through `core.get(key, cursor)` → `LocalConfigStore.get(key, cursor)` which fires `assertMonotonicRead` (`LocalConfigStore.java:146-150`; `EdgeHttpServer.java:179-182` reads the store BEFORE classifying). Pinned by `EdgeClientCoreTest$MonotonicReadSeam.cursorAheadOfStoreReturnsNotFoundAndFiresMonitor` (asserts the registry counter moved through the core's read path) — reviewer-reran, green. |
| **C2-2** exfiltration residual stated + registered | **DISCHARGED** | Note §8 states it with the mitigations and the S4/S5 handoff obligation. `RR-098` row EXISTS (`docs/readiness-register.md:207`): P2, owner session 5, says exactly what the note claims, and names the in-memory-only boundary as "a constraint C2 implementation must preserve or re-register" — which C2 then ENFORCES in a test: `EdgeStrongReadFailClosedTest` walks `--data-dir` and asserts no secure-value bytes on disk (`EdgeStrongReadFailClosedTest.java:136-142`); `--data-dir` holds only `epoch.lock` (`EdgeClientCore.java:221-223`, `EdgeNodeMain` javadoc). Reviewer-reran, green. |
| **C2-3** idle-proxy deletion total + CT-08 tripwire | **DISCHARGED** | Grep of `configd-edge-cache`/`configd-edge-node` main code for `nanoTime`/`lastUpdateNanos`/idle-time: **zero non-javadoc hits** — `StalenessTracker` has no idle-time field or path; the frontier is the only measurement (`StalenessTracker.java:97,231-254,312-320`). The tripwire exists and both counts and clamps: future-beyond-50ms → count + clamp-to-now (`:238-241`); regression → count + hold (`:248-251`); never silently trusted. `StalenessSkewTripwireTest` 7/7 covers within-skew (no count), beyond-skew, gross-future-via-heartbeat, regression via update AND heartbeat, no-counter-still-clamps, plausible-lag-never-counts — reviewer-reran, green. The ADR-0039 regression test exists (`ConsistencyPropertyTests$StalenessUpperBoundTest#idleButHeartbeatingEdgeStaysCurrentAndBehindHeartbeatDoesNot`, `ConsistencyPropertyTests.java:469`) and the sim-level `EdgeStalenessFrontierSimTest` pins idle≥35s CURRENT + the partitioned full ladder. One residual local-clock path is NOT wired in production — see Finding 5 (NOTE). |
| **C2-4** §3 amendment in the contract pass, not an ADR | **DISCHARGED** | `docs/consistency-contract.md:105-106`: steps 3–4 now read immediate refusal (`404` + `X-Configd-Refused: cursor-behind`), "never serves stale on a cursor-behind read — the refusal rule is uniform across steady state, catch-up after reconnect, and failover", citing **ADR-0035 + ADR-0039**, naming the real per-reason series, recording the resolution "at the C2 contract pass per the c2-c5-design-screen C2-4 ruling", and pinning with **`EdgeFailoverTest`** — exactly the ruling: contract-pass amendment, no new ADR, refusal is stronger than the old block-and-serve-stale text (named as the replaced text, not silently weakened). The CT-12 map row flip is the contract-qa audit's (note §9 says so explicitly, the C1 precedent); the map row at `docs/session-3/contract-test-map.md:73` still shows the pre-amendment state — owed to the contract-qa half of the dual sign-off, not a C2 defect. |

---

## Finding 1 — EdgeStreamClient threading: session/reader/writer lifecycle, teardown, the full-inbound-queue corner [NOTE ×3 — no P1; non-blocking]

**Verified against `EdgeStreamClient.java`, adversarially.** The shape is cleaner than C1's
server-side equivalent: there is **no two-writer OutputStream window** (the C1 sign-off
Finding 1 sub-item) because the only out-of-writer-thread socket write is the synchronous
SUBSCRIBE, which happens BEFORE the reader/writer threads exist (`openAndSubscribe:339-352`);
every later write goes through the writer thread alone.

- **Teardown idempotence holds.** `alive.compareAndSet(true,false)` (`:537`) admits exactly
  one thread; reachable from the session loop's `finally` (`:229-231`), the writer's
  `finally` (`:531`), and `close()` (`:182-184`) — socket close, POISON, CLOSED-post, and
  interrupts happen once. `joinQuietly`/`interruptQuietly` skip `currentThread()` so a
  writer-initiated teardown cannot self-join (`:552-567`). `start()`/`close()` are CAS-guarded
  and `close()` bounds on `join(2_000)`. ✔
- **The full-inbound-queue corner is genuinely covered.** A reader parked on
  `inbound.put` (queue full, `:466`) is not unblocked by the socket close; teardown handles
  it twice over: `postClosed` clear-then-post guarantees CLOSED lands (after `clear()` at
  most one racing `put` can occupy 1 of 256 slots, so the re-`offer` cannot fail, `:502-511`)
  AND the explicit reader interrupt (`:544-547`, with the in-code comment naming exactly this
  corner). Cleared frames are safe to drop: the chain is cursor-resumable and a re-delivered
  delta is `STALE_DELTA`-discarded idempotently (`EdgeClientCore.applyNotification:339-342`).
  **NOTE (non-blocking):** in one interleaving a racing frame can land BEFORE the CLOSED
  sentinel and be applied by a still-polling session loop after teardown began — harmless
  (the frame was genuinely received pre-teardown; the single-writer discipline is preserved
  because only the session thread calls `onFrame`), recorded for completeness. ✔
- **Interrupt handling is correct.** Reader/writer catch `InterruptedException`, re-assert,
  and fall into their `finally` paths; `runConnection`'s poll-interrupt returns into the
  session `finally` → teardown (`:263-266`). Virtual-thread socket ops are interruptible, and
  connect/handshake are bounded anyway (1s/2s, `:83-85`). **NOTE (non-blocking):** when the
  session thread reaches `teardown` with its own interrupt flag set (the `close()` path),
  `joinQuietly`'s `t.join(1_000)` throws immediately and the joins are effectively skipped —
  bounded and leak-free (socket closed + `alive=false` make both loops exit promptly; virtual
  threads hold no pooled resources), but the teardown returns slightly before thread exit.
  Cleanliness smell only.
- **Outbound discipline:** `offer` is non-blocking, checks `alive`, bounded 64; a refused
  CURSOR_ACK is retried by the core's next tick (`EdgeClientCore.tick:439-441`,
  `ackCursor:453-459` — `lastAckedSeq` only advances on a successful offer, so the retry is
  structural, not hopeful). POISON unblocks the writer's `take()`; a full outbound queue at
  teardown is covered by the writer interrupt. ✔
- **The `current`/sink race is benign:** `sink()` reads the volatile `current` and offers
  against a possibly-torn-down connection; `offer` checks `alive` and returns false → next-tick
  retry on the new connection (`:159-164,444-456`). ✔
- **NOTE (non-blocking):** `backoff()` runs even after a healthy connection ends
  (`consecutiveFailures` reset to 0 → delay = base 100ms ±50% jitter), so a clean failover
  carries a ≤150ms pause before trying the next endpoint. Trivial against the silence window
  (2s default) and arguably desirable (no reconnect-storm); named here so it is a decision,
  not an accident.

**No race or leak found that loses a frame undetected, double-drives the core, or wedges
teardown. No P1.**

## Finding 2 — Part-(a) defect fix (snapshot cutover vs CT-08): `recordVersion`-without-frontier-advance is SOUND; no CURRENT-forever window [verified by reasoning + test]

The question I was asked to rule on: does recording the snapshot version WITHOUT advancing
the frontier open a window where a freshly-bootstrapped edge reports CURRENT forever despite
no heartbeats? **No — the failure direction is the opposite (conservative), and it is the
correct one.**

- Read from the code: `EdgeConfigClient.loadSnapshot` calls `recordVersion` only when
  `snapshot.timestamp() <= 0` (`EdgeConfigClient.java:238-248`); `recordVersion` sets
  `lastVersion` and **leaves `frontierMillis` untouched** (`StalenessTracker.java:187-189`).
  On a fresh bootstrap the frontier is still the `Long.MIN_VALUE` "no frontier yet" sentinel,
  and `stalenessMs()` then returns `DISCONNECTED_THRESHOLD_MS + 1` (`:312-317`) — the edge
  reports **DISCONNECTED**, not CURRENT, until the first cursor-matched HEARTBEAT or
  post-snapshot NOTIFY commitTs advances the frontier through the tripwire-guarded
  `advanceFrontier`. A freshly-bootstrapped edge with no heartbeats can never claim
  freshness it cannot attest — exactly ADR-0039's frontier law ("the latest point the edge
  *knows* it has covered"; an ADR-0028 snapshot attests state at seq S but no wall-clock
  point, so not advancing is the honest reading).
- With a PRE-existing frontier (mid-session demotion snapshot), the frontier holds at its
  old value and staleness keeps accruing until the heal — again conservative
  (over-reports staleness during the window, never under-reports). The healing window is
  bounded by the 250ms heartbeat cadence: the first post-cutover heartbeat with
  `latestSeq == cursor` advances it; if `latestSeq > cursor`, NOTIFYs are in flight and
  their commitTs advances it. There is no frozen-gauge path either: `stalenessMs()` is
  computed against the live clock at read time and the gauges read it at scrape time
  (`EdgeNodeMetrics.bind:110-118`), so staleness GROWS while unattested — CURRENT-forever
  is unreachable.
- Boot-time interaction checked: the rebootstrap trigger (CT-06 seam) is edge-triggered on
  the transition INTO DISCONNECTED and `bind()` seeds the baseline with the boot state
  (DISCONNECTED), so a fresh boot does NOT spuriously fire
  `edge_rebootstrap_triggered_total` (`EdgeNodeMetrics.java:114-117,152-159`). `/health/ready`
  correctly reports 503 during the bootstrap window (a never-attested edge is not ready —
  CT-05).
- Pinned by `EdgeClientCoreTest$SnapshotFrontierIntegrity.snapshotCutoverDoesNotTripImplausibilityCounter`
  (cutover does not move the CT-08 counter; the next NOTIFY heals to CURRENT) — reviewer-reran,
  green. The pre-fix behavior (frontier→0) would have been BOTH a frontier regression
  (counted) and false-positive pollution masking real skew; the fix removes the false
  positives without weakening the tripwire (all 7 skew tests still pass).

**Ruling: sound w.r.t. ADR-0039; no masking window; the right fix rather than a fabricated
timestamp.** (The alternative — stamping the snapshot with local receive time — would have
quietly reintroduced a local-clock frontier, the ADR-0039 disease.)

## Finding 3 — Charter §6 rule 4 performance screen on the new shell code: PASS [verified]

- **No unbounded queues.** Inbound `ArrayBlockingQueue(256)`, outbound
  `ArrayBlockingQueue(64)` (`EdgeStreamClient.java:428-429`); core-internal collections are
  per-snapshot-transfer (`pendingChunks`, bounded by the codec's chunk caps) and the
  directive deque (one entry per reconnect latch, `reconnectPending` dedupes,
  `EdgeClientCore.java:465-468`). The metrics registry is eager-registered, label-less,
  fixed-size.
- **Backpressure is the right kind.** A full inbound queue blocks the READER
  (`inbound.put`, `:466`) → TCP backpressure toward the server, whose bounded per-subscriber
  queue then demotes this edge through C1's tested machinery — the slow consumer is handled
  by the component built for it, not buffered locally without bound.
- **No O(subscribers) work, no global lock** — client side has one session; the read path is
  the lock-free volatile-snapshot store, untouched by session state
  (`EdgeClientCore.get:521-528`).
- **No per-update full-snapshot shipping** — the client consumes the verbatim chain;
  snapshots arrive only on the server's demotion/bootstrap decision (C1's tested paths).
- **The backoff staleness pump is real and necessary:** ≤1s slices with
  `metrics.syncFromCore` per slice (`backoff:315-328`) AND a pump between connection cycles
  (`sessionLoop:240`) — DISCONNECTED detection and the CT-04 counter work precisely while
  disconnected. Cap and jitter verified (`MAX_BACKOFF_MS=10_000`, ±50% full jitter, doubling
  shift clamped at 10 to avoid overflow).

## Finding 4 — Charter rule 3 (hot-path law / CT-34): claims recorded, boundary honest [NOTE — non-blocking]

`LocalConfigStoreReadBenchmark` exists (`configd-testkit/src/main/java/io/configd/bench/`),
its javadoc records the measured figures (getMiss ≈6ns / **0 B/op**, getIntoHit ≈117ns /
**0 B/op**, getHit ≈89ns / 32 B = the one documented `ReadResult`, getHitWithCursor
identical — matching note §6 verbatim and matching `VersionedConfigStore`'s established
figure), and the **scope-honesty paragraph is in the benchmark javadoc itself**: the HTTP
shell "allocates per request … deliberately NOT measured here — it is not the §3 library
read path". The same boundary statement appears at the consumption site
(`EdgeHttpServer.java:55-60`) with the counters-only request path (verified: no per-request
logging in `ConfigReadHandler`). The law's boundary is documented, not quietly waived; the
in-process path the law binds (`EdgeClientCore.get` → `LocalConfigStore.get`) was read and
allocates nothing on miss and branches on no session state. **NOTE:** I did not re-run JMH
(2-vCPU box discipline; the figures are the committer's recorded run) — the claim is
code-consistent and consistent with the V1 baseline figure, which I accept for sign-off;
gate-3 re-runs it anyway per the map.

## Finding 5 — Idle-proxy deletion: one legacy local-clock frontier fallback survives, UNREACHABLE from the C2 process path [NOTE — non-blocking; disposition owed]

`EdgeConfigClient.applyDelta(ConfigDelta)` (one-arg, `EdgeConfigClient.java:183-185`) and
`DeltaApplier.offer(delta)` → `NO_COMMIT_TIMESTAMP` (`DeltaApplier.java:269`) record the
frontier from the **local clock at apply time** — for an idle-then-stalling consumer of that
path, staleness ≡ time-since-last-apply, i.e. the idle-proxy measure in different clothes.
Verified it is NOT wired as a production signal: the C2 process path is exclusively
`EdgeClientCore.applyNotification` → `applier.offer(delta, notification.commitTimestampMillis())`
(`EdgeClientCore.java:325-326`), and `CommitNotification` validates
`commitTimestampMillis >= 0` (`CommitNotification.java:53-55`) so the `-1` sentinel cannot
arrive from the wire; grep confirms zero other production callers. The fallback exists for
pre-C2 direct callers/tests (documented in its javadoc as exactly that). This satisfies
C2-3's "no residual idle-time path **wired as the production signal**" — but the orphaned
fallback is the kind of two-meanings seam ADR-0039 warned about ("two staleness numbers is
how dashboards lie"). **Track:** disposition (delete the one-arg overloads or static-guard
them test-only) at C3's apply-path work or S7's orphan sweep; not a C2 gate.

## Finding 6 — Read-surface semantics: miss-vs-refusal classification, strong-read precedence, probe resistance [verified, no finding]

- The pre-read `localVersion` snapshot makes the `!found` classification sound exactly as
  the note claims: store version is monotonic, so `localVersion >= cursor` at snapshot time
  implies the read's snapshot also satisfied the cursor → a miss is a true not-found;
  the race direction (`localVersion < cursor` while the store advances mid-flight)
  classifies as refusal — the safe side, healed by client retry
  (`EdgeHttpServer.java:174-208`).
- Strong-read fail-close happens BEFORE the store is consulted (`:157-166`) and before
  cursor parsing — no path serves or even reads a `secure/` value. Probe analysis: the
  predicate runs on the percent-decoded URI path, so `%2F` games decode back to `secure/…`
  and refuse; a non-decoding probe string (`secure%2Fk` via double-encoding) misses the
  predicate but also misses the HAMT (exact-string keys) → plain 404, no leak. The
  process-level test additionally asserts the refusal body leaks no value bytes
  (`EdgeStrongReadFailClosedTest.java:113-120`).
- `X-Configd-Stale` is set on ALL responses (hits, misses, refusals — set before branching,
  `:149-155`) while STALE+, per CT-03; `/health/ready` thresholds at DEGRADED+ per CT-05.

## Finding 7 — INV-M1 SEVERE-log noise: RULED — register row added as **RR-099** [REQUIRED — non-blocking for prod, blocking-for-honesty; DONE in this review]

**Ruling: register it.** I reproduced it live in my own run: `EdgeFailoverTest`'s catch-up
window emits an uninterrupted SEVERE stream (`Invariant violated [monotonic_read]:
key=svc/x seenVersion=2 newVersion=1`, dozens of lines in seconds). Two distinct hazards,
both operability (not correctness — the refusal behavior itself is correct and
contract-pinned): (a) SEVERE-channel spam during every routine post-failover catch-up buries
real SEVERE events (alert fatigue); (b) deeper, at the edge the
`invariant.violation.monotonic_read` series is now **conflated**: in the sim it can only
mean a store regression (the actor reads with its own cursor → test-mode throws), but at
the serving surface it fires on every legitimately-ahead client cursor — so Session 6 must
NOT page on that series alone at edges. The contract MANDATES the monitor routing (INV-M1
liveness is condition C2-1), so the fix is log-channel shaping / alert-design at S6, not
unwiring the seam. **Severity P3** (no correctness impact; the safe behavior is the noisy
one; the per-reason refusal counters already exist as the routine-rate series), **owner
Session 6**. Row added: `docs/readiness-register.md` RR-099 (next free number after RR-098,
verified).

## Finding 8 — Note accuracy nits [NOTE — non-blocking]

- Commit `7034f67`'s message says "EdgeClientCoreTest (73 w/ nested)"; the actual count is
  **37** (sum of the 12 nested classes, reviewer-counted from surefire) — the as-built NOTE
  (the document being signed) says 37 and is correct; the commit message has a transposition.
  Recorded so nobody later "verifies" against the commit message.
- The CT-12/CT-08/CT-37/CT-34 map rows still read UNIMPLEMENTED — correct per the declared
  process (note §9: "The contract-qa audit, not this note, flips the map", the C1
  precedent). The contract-qa half of the dual sign-off owns the flips; this review's
  evidence (conditions table above) is their input.
- mTLS coverage claim verified at the test level: `EdgeTransportMtlsTest` asserts the rogue
  CLIENT cert never subscribes (mode null, zero heartbeats, store at 0, reconnects climbing)
  AND the rogue SERVER cert is rejected by the client trust path — the latter is genuinely
  new coverage beyond C1's server-half test, as the note claims. CT-40's edge-side
  absent-client-cert case remains honestly named as not exercised (note §8, pre-existing
  parity with the control plane's own CLI TLS path).

---

## Prior conditions from the C2–C5 screen: status

| Screen condition | Status |
|---|---|
| C2-1 (digest byte-identity + INV-M1 liveness) | **DISCHARGED** (table above; reviewer-reran both proofs) |
| C2-2 (exfiltration residual stated + registered) | **DISCHARGED** (note §8 + RR-098 row + the disk-sweep test enforcing the in-memory boundary) |
| C2-3 (idle-proxy deletion + CT-08 tripwire) | **DISCHARGED** (deletion total in production; tripwire counts+clamps; Finding 5 NOTE on the unreachable legacy fallback) |
| C2-4 (§3 amendment in the contract pass, not an ADR) | **DISCHARGED** (contract §3:105-106; CT-12 row flip owed to contract-qa) |

## SIGN-OFF STATEMENT

C2 (the edge node process) is **SIGNED OFF** as DONE per charter §1 rule 2: the simulator
drives the production `EdgeClientCore` under the 507-seed adversarial gate with the CP
digest byte-identical (reviewer-reproduced), the unit/property/process battery passes
(targeted suites reviewer-reproduced, EXIT=0; full reactor green per the main thread), and
the as-built design note is accurate against the code with all four screen conditions
discharged. The threading review of the new shell found no P1. The one REQUIRED item
(INV-M1 SEVERE noise) is discharged into the register as RR-099 (owner S6) by this review.
The NOTEs (legacy local-clock frontier fallback disposition, teardown-cleanliness smells,
post-clean-connection backoff, JMH figures accepted-not-rerun) are tracked, none gating.
**C3 implementation may start**, subject to the screen's standing C3 gate (ADR-0040 ratified
— done; `PoisonPillRebootstrapTest` incl. the terminal case — owed by C3).

— review-architect, 2026-06-11
