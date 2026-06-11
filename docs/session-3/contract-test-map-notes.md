# Working notes — contract-test-map (Session 3, contract-qa-engineer)

Companion to `contract-test-map.md`. Everything below was verified against the working tree on
branch `session-3-data-plane` (HEAD `65a5212`), by reading files — not from memory or prior docs.

## Normalization pass (charter landed)

The Session-3 charter is now committed verbatim at `docs/session-3/charter.md`; the map's component
column was normalized to its §4 C1–C6 (the original inferred legend and its caveat are removed; this
map is the charter's §3 V3 deliverable). Remap deltas worth recording:

- Staleness rows (CT-01/02/04/07/08) → **C2** (the clock and serving surface live on the edge
  process's delta-apply/read path); CT-02 is **split**: clock = C2, distribution machinery = Phase-V
  **V2** propagation probe (charter:89-94), recorded as `C2 (+V2 probe)`.
- CT-16 (edge gap detection) → **C3**, not C2 — charter §4 C3 (:127) explicitly owns "Cursor-gap
  detection on the edge". (This row was not in the lead's remap list; verified directly against §4.)
- CT-06 → C3 with a recorded split (DISCONNECTED *trigger* = C2's staleness state machine;
  re-bootstrap *mechanism* = C3). CT-13 → C2/C3 split (serving semantics / restart re-bootstrap).
- CT-24 → **C5** (new-edge bootstrap); CT-27..30 → **C4** (slow-consumer policy); CT-31/32/33 → **C3**;
  CT-39 → **C6** (Compose E2E + RR-095 re-run); CT-34/CT-38 → **G3** (cross-cutting gate-3 mechanical).
- CT-25 is now a **C1/C2 split** under ADR-0038 (pending): subscribe handshake + full-chain delivery
  = C1; the post-verification prefix apply-filter = C2 (ADR-0038 §Consequences names C2 for it).
- Status counts unchanged by normalization: 7 / 11 / 0 / 22 / 0 / 1 (total 41).

## (a) Contract §7 named tests — existence audit

Surprise finding, in the *good* direction: **all eight §7-named tests exist** — but as `@Nested`
classes inside one file, `configd-testkit/src/test/java/io/configd/testkit/ConsistencyPropertyTests.java`,
not as standalone classes. Grep confirms no other definition sites.

| §7 name | Exists? | Where | Level reality |
|---|---|---|---|
| `MonotonicReadTest` | yes | nested, ConsistencyPropertyTests:803 | unit/sim hybrid; edge synced by hand (CT-09) |
| `MonotonicReadFailoverTest` | yes | nested, :904 | two in-process stores, no reconnect/wire (CT-11) |
| `SequenceMonotonicityTest` | yes | nested, :589 | real Raft in deterministic sim — proper level (CT-14) |
| `SequenceGapFreeTest` | yes | nested, :692 | proper level (CT-15) |
| `PerKeyTotalOrderTest` | yes | nested, :1071 | control-plane scope — not mapped (see below) |
| `IntraGroupOrderTest` | yes | nested, :1191 | control-plane scope — not mapped |
| `ReadYourWritesTest` | yes | nested, :1321 | edge synced by hand (CT-35) |
| `StalenessUpperBoundTest` | yes | nested, :415 | proxy-clock + self-driven sync loop (CT-02/CT-07) |

So the §7 "fiction" today is not *existence* but **level and clock**: the staleness test measures its
own sync loop against the idle-time proxy, and the edge-flavored tests hand-feed `LocalConfigStore`
via `DeltaComputer` instead of any propagation path.

Named-but-nonexistent tests found elsewhere (the task brief's "existing tests to verify" list):
- **`SlowConsumerPolicyTest` does NOT exist** anywhere in the repo. `SlowConsumerPolicy` (src/main)
  is referenced by exactly one test line: `ConfigdServerTest:254` (`assertNotNull(server.slowConsumerPolicy())`).
- **`CatchUpServiceTest` does NOT exist.** `CatchUpService` has zero test references.
- `StalenessUpperBoundTest` is **not** in configd-edge-cache (the brief placed it there); the
  edge-cache module's staleness coverage is `StalenessTrackerTest` + `LocalConfigStoreTest$StalenessTracking`.

Not mapped (control-plane scope, S2-owned artifacts): INV-L1 (configd-linz/Porcupine), INV-W1
(`PerKeyTotalOrderTest`), INV-W2 (`IntraGroupOrderTest`), plus `VersionMonotonicityEdgeTest`,
`NoStaleOverwriteTest`, `ElectionSafetyTest` in the same file. The map covers edge-facing clauses only.

## (b) ADR / design decisions the map is blocked on

### Still open

1. **Edge-failover semantics (CT-12).** Contract §3 is self-contradictory: mechanism (:99) =
   refuse-immediately, never-serve-stale, blocking variant "not implemented" (CM-017/CM-041); Edge
   Failover steps 3-4 (:105-106) = "waits briefly for catch-up (same timeout as above) … serves
   stale with notification" — and "same timeout as above" refers to a timeout the same section says
   does not exist. Decide consistent-refusal (amend §3 text) or implement blocking catch-up; charter
   §4 C2 (:122-124) additionally demands the failover clause be tested across reconnect to a
   *different* fan-out endpoint.
2. **Poison-pill + negative caching implement-or-descope (CT-32/CT-33).** Charter §4 C3 (:130-131):
   "implement or explicitly descope by ADR (do not leave it ambiguous)". `PoisonPillDetector` and
   `BloomFilter` are unit-tested orphans — zero src/main consumers; the §8 circuit-breaker (serve
   previous known-good) and `configd.edge.poison_pill` metric exist nowhere.
3. **Arch §7 vs ADR-0034 reconciliation (CT-26/CT-31).** Two backpressure/catch-up vocabularies
   coexist: arch §7's credit-based model (100 credits, 1000-entry buffer, 80%/100%) and WAL-delta +
   1 MB-CRC-chunked snapshot, vs ADR-0034's ring-10,000 + GAP + snapshot-equivalent `ReplaySource`.
   C1/C3 design notes must say which numbers/mechanisms govern (likely: ring+GAP+ReplaySource, with
   chunking retained for the snapshot transfer — cf. RR-019's 4 MiB cliff).
4. **Smaller, recordable in design notes (not necessarily ADRs):** the §2 DEGRADED "unhealthy to
   load balancer" surface is undefined (CT-05); §2 DISCONNECTED "regional relay" is superseded
   ADR-0030 vocabulary (CT-06); whether `secure/` keys are excluded from edge *storage* by the
   ADR-0038 filter (the *delivery* half is answered — see below) (CT-37).

### Pending ratification — ADR-0038 (Proposed; ratified at the C1 design review)

`docs/decisions/adr-0038-signed-chain-streaming-no-coalescing.md` resolves what was this section's
former item "coalescing/filtering vs gap detection (CT-17)" — drafted after the map's first edition:

- **No server-side coalescing of signed payloads** (the relay holds no signing key; coalescing would
  produce bytes the leader never signed → unsigned data at the edge or stream rejection). The
  charter's "may collapse" option (§4 C1 :108-109) is exercised as *may not*. Throughput is handled
  by **frame-level batching** (one `NOTIFY` frame, N consecutive notifications, chain + signatures
  intact). The exact rule the charter demanded: *the stream is the contiguous applied-mutation seq
  chain; nothing collapsed or skipped; gap detection stays exact; any observed skip is a real gap.*
- **Prefix subscription becomes an edge-side storage/serving filter, not a transport filter** —
  full signed chain to every edge; non-matching mutations advance the version chain without storing;
  a relay cannot suppress a delta (including `secure/` keys) without a detectable chain break.
- Map effect (applied now, statuses NOT flipped until ratification): CT-17's planned test renamed
  `CoalescingGapInteractionTest` → `FrameBatchingChainIntegrityTest`; CT-25's owed tests re-pointed
  to `FullChainDeliveryTest` (C1) + `EdgePrefixStorageFilterTest` (C2); CT-16/CT-26/CT-32/CT-37/CT-41
  notes reference ADR-0038. On ratification: CT-17 flips to ADR-RENEGOTIATED(adr-0038) with the
  frame-batching tests as evidence (per the ADR's own §Consequences), and CT-25's clause text is
  re-anchored to the storage-filter semantics.

The former caveat about the charter not being in-repo is resolved: the charter is committed at
`docs/session-3/charter.md` and the map's component column now cites its §4 directly.

## (c) Existing tests verified (what each actually asserts)

- `LocalConfigStoreTest` (edge-cache): cursor at/behind serves, ahead → NOT_FOUND
  (`MonotonicReads`); `MonotonicReadInvariant` asserts `invariant.violation.monotonic_read` counter
  increments and test-mode throws `AssertionError`; `ConcurrentAccess.multipleReadersOneWriter`
  (readers progress under a single writer, unit-level); delta apply/version-mismatch throws; snapshot
  immutability.
- `StalenessTrackerTest` (edge-cache): threshold state machine at 499/501ms, 5s, 30s boundaries +
  reset-from-any-state; `stalenessMs` accuracy over a `TestClock`; `InvariantMonitorWiring` asserts
  `staleness_bound` fires over threshold and throws in test mode. All against the **idle-time proxy
  clock** — `recordUpdate(version, timestamp)` still ignores `timestamp` (StalenessTracker.java:98-101).
- `DeltaApplierTest` (edge-cache): GAP_DETECTED on fromVersion mismatch/forward jump, STALE_DELTA on
  toVersion ≤ current, reset-gap-then-apply; `SignatureVerification` — invalid/unsigned/wrong-key
  rejected, fail-closed when verifier unset but delta signed, F-0004 single-mutation leader-signed
  verifies; `EpochPersistence` — epoch sidecar persisted (CRC32C), replay rejected **across restart**,
  corrupt sidecar demoted to epoch 0.
- `EdgeConfigClientTest` (edge-cache): client facade — get-with-cursor semantics, applyDelta updates
  version + resets staleness, loadSnapshot replaces store, subscription set management,
  metricsSnapshot reflects state. In-process only; no transport.
- `PoisonPillDetectorTest` / `BloomFilterTest` + `BloomFilterPropertyTest` (edge-cache): quarantine
  after max retries / release / listener; no-false-negatives + FPP below threshold. Classes are
  src/main orphans.
- `FanOutBufferTest` (distribution): legacy `append`/`deltasSince`/`latest` ring semantics, capacity
  boundaries, wraparound eviction. Exercises the **legacy non-atomic read path** retained for
  back-compat (RR-066) — fine as unit coverage, must never be the consumer pattern (CT-22).
- `CommitNotificationSourceTest` (distribution): bound (size ≤ cap over cap×100 publishes, seq window
  tracked); overflow (droppedTotal == evictions, stale cursor → GAP carrying `oldestRetainedSeq`,
  floor-predecessor cursor served contiguously); caught-up cursor → `Ok([])` not GAP;
  `commitTimestampMillis` carried through; 25-seed randomized replay-then-tail ends byte-equal to the
  authoritative model with cursor at authoritative seq (exactly-once over effect, boundary level).
- `FanOutBufferRaceTest` (distribution): 1 writer × 200k publishes, 4 tailing readers, cap 64 — any
  non-GAP run strictly ascending (no dup/skip), every seq eventually observed; reader-paced variant
  asserts exactly-once full stream. Complemented by jcstress
  `configd-jcstress/.../FanOutBufferReadSinceTest` (outcome 9 = FORBIDDEN torn class; `ExactlyFullWrap`
  caught RR-096 pre-fix, clean post-ADR-0036). Gate-3 must run these in `quick` mode.
- `ConsistencyPropertyTests` (testkit): see §(a) table. The Raft-backed nested classes
  (Linearizability, SequenceMonotonicity, SequenceGapFree, PerKeyTotalOrder, IntraGroupOrder) drive
  real `RaftNode`/`ConfigStateMachine` through `RaftSimulation` — genuine sim level. The edge-flavored
  ones (MonotonicRead*, ReadYourWrites, StalenessUpperBound, VersionMonotonicityEdge) sync the edge
  store **by hand** (`DeltaComputer.compute` + `applyDelta`/`loadSnapshot`) — no propagation path.
- `NoBlockingConnectOnConsensusPathTest` (transport): the static-guard pattern CT-22 copies — source
  scan asserting a forbidden call does not appear on a protected path.

## Untestable-as-written inventory (for the consolidated contract pass)

- §3 Edge Failover steps 3-4 (CT-12) — internal contradiction + dangling timeout reference.
- §2 DEGRADED "reports unhealthy to load balancer" (CT-05) — no health/LB surface defined anywhere.
- §2 DISCONNECTED "snapshot catch-up from regional relay" (CT-06) — "regional relay" is superseded
  (ADR-0030) topology vocabulary; the implementable form is the fan-out service's `ReplaySource`.
- §4/arch §7 seq+1 gap rule under coalescing/prefix filtering (CT-17) — rule was undefined;
  ADR-0038 (Proposed) defines it (verbatim chain, no collapse/skip) — testable once ratified.
- INV-S2 p9999 (CT-02) — statistically meaningful only with ≥10^4–10^5 samples; feasible in sim,
  live measurement is S5's; this session delivers the mechanism + sim distribution only.
