# ADR-0034: The commit-notification boundary (§4.6) — bounded, cursor-based, replayable interface for Session 3 (RR-001 / RR-042 / RR-066)

- **Status:** Proposed (review-architect sign-off pending — see §Sign-off). Implemented in Session 2 (§4.6 boundary-engineer scope).
- **Date:** 2026-06-11
- **Session:** 2 (Control-Plane Correctness) — boundary between S2 control plane and S3 data plane
- **Findings:** RR-001 (P0, edge undemonstrable — blast radius reduced, stays OPEN), RR-042 (P2, FanOutBuffer write-only sink — RESOLVED), RR-066 (P3, non-atomic head/tail race — RESOLVED)
- **Interacts with:** ADR-0033 (commit-outcome / applied-mutation seq S — this interface consumes that apply seam), ADR-0035 (leader-assigned commit timestamp = the §2 staleness clock — S2 emits, S3 consumes), ADR-0030 (single-group topology — one ordered mutation stream), ADR-0028 (snapshot format — the ReplaySource payload)

## Context

§4.6 requires a **commit-notification interface** that Session 3's eventually-consistent edge data plane consumes: versioned, cursor-based, replayable from the log/snapshot, with an explicit bounded-buffer overflow policy. The charter's premise that the buffer is *unbounded* is **incorrect** (RR-042): `FanOutBuffer` was already a bounded ring of 10,000 with drop-oldest eviction. So the deliverable is NOT "add a bound" — it is:

1. the **interface contract** S3 builds against (schema + cursor + overflow + replay), transport-agnostic;
2. an **explicit, documented overflow policy** with a metric and a gap signal;
3. a **replay contract** (the durable log+snapshot is the source of truth; the buffer is a hot-path cache);
4. the **RR-066 fix** (the read side was non-atomic — "harmless today, wrong the day a drain is wired"; this interface IS that day);
5. the **producer wiring** that publishes the full notification, including the ADR-0035 leader commit timestamp.

What exists already: the producer site `fanOutBuffer.append(delta)` (`ConfigdServer`), fed by `ConfigStateMachine`'s apply-thread `ConfigChangeListener(mutations, version)` where `version` is the ADR-0033 applied-mutation sequence S. No consumer/drain exists in `src/main` (RR-001).

## Decision

### 1. Notification schema — `CommitNotification`

One notification per **mutating** committed apply (PUT/DELETE/BATCH). Non-mutating committed entries (leader no-ops, RCFG) produce no notification — matching ADR-0033/0035's applied-mutation-sequence semantics (the listener fires only on mutating applies). Fields, each justified:

| Field | Type | Justification |
|---|---|---|
| `seq` | `long` | The ADR-0033 **applied-mutation sequence S** the state machine assigned. It is the client-visible commit sequence AND the consumer's cursor key. Carried explicitly (not derived from `delta.toVersion()`) so the cursor contract never depends on parsing a delta. |
| `commitTimestampMillis` | `long` | The **leader-assigned commit timestamp** (ADR-0035 §2): the leader's wall clock captured at apply time. This is the §2 staleness clock — S3's edge computes `staleness = edge_wall_now − commitTimestampMillis`. A single authoritative reference clock (the leader's), **not** a per-entry HLC carried in the Raft log (ADR-0035 descoped that). |
| `delta` | `ConfigDelta` | The existing delta — already carries the mutation list (keys + payloads), `fromVersion`/`toVersion`, and the F-0052 `signature`/`epoch`/`nonce` the edge needs to verify authenticity and reject replays. Carrying the whole delta avoids duplicating key/payload state and lets S3 forward the signed payload unchanged. |

The notification is the §4.6 unit. **No wire encoding** lives in it — S3 owns transport; the interface is transport-agnostic.

### 2. The interface — `CommitNotificationSource`

```
Result readSince(long cursor)   // contiguous run with seq > cursor, OR a GAP signal
long    latestSeq()             // highest retained S, or -1
long    oldestSeq()             // lowest retained S, or -1
long    droppedTotal()          // fanout_buffer_dropped_total, mirrored
```

**Cursor semantics.** The consumer holds the applied-mutation seq S of the last notification it processed (its cursor) and calls `readSince(c)`. The result is either `Ok(notifications)` — the contiguous run with `seq > c`, ascending — or `Gap(oldestRetainedSeq)`. A fresh consumer starts at cursor 0.

`Result` is a sealed `Ok | Gap`. A caught-up consumer gets `Ok([])` (empty, not GAP). **`readSince` NEVER returns a partial/duplicated run** that silently skips evicted notifications — that is exactly the RR-066 class of bug this interface exists to prevent.

### 3. Overflow policy — bounded ring, drop-oldest, metric + GAP

The buffer stays a **bounded ring of 10,000** (`ConfigdServer.FANOUT_BUFFER_CAPACITY`, unchanged — justified below). On overflow it evicts the oldest entry (**drop-oldest**), increments **`fanout_buffer_dropped_total`** (a real counter wired in `ConfigdServer`, exported by `PrometheusExporter` as `fanout_buffer_dropped_total`), and records the evicted seq so a lagging consumer's next `readSince` returns **GAP** rather than a truncated run.

**Why drop-oldest is safe.** The log+snapshot is the replay source: post-RR-003 the durable prefix (persisted snapshot at S + WAL suffix) reconstructs ALL committed state. The buffer is therefore a **hot-path cache, not the source of truth**. An evicted notification is never lost data — only evicted from the cache; the consumer recovers it via the `ReplaySource`. Dropping the *oldest* (not newest) keeps the cache useful for consumers that are roughly caught up (the common case) while a far-behind consumer is told to replay.

**Why 10,000.** At the §0.1 baseline 10k writes/s, 10,000 entries is ~1 s of buffered tail — comfortably longer than the §0.1 p99 propagation budget (< 500 ms), so a consumer within SLO never needs to replay; at 100k/s burst it is ~100 ms, still within the budget for a healthy consumer, and a bursting-behind consumer correctly falls back to replay rather than the cache growing unbounded. Memory: 10,000 references + small per-notification objects — negligible. No argument to change it.

### 4. Replay contract — `ReplaySource` / `SnapshotReplaySource`

On GAP the consumer replays from the `ReplaySource`, then resumes cursor-based tailing:

```
Replay replayFromSnapshot()   // -> (ConfigSnapshot snapshot, long seq)
```

`SnapshotReplaySource` is the minimal honest implementation: a `Supplier<ConfigSnapshot>` (typically `store::snapshot` — one volatile read of the immutable snapshot pointer; the HAMT inside is persistent and shareable). **What S3 gets: snapshot-equivalent state at sequence S, plus the floor seq to resume from.** The consumer applies the snapshot wholesale (it already encodes the cumulative effect of every committed mutation up to S), sets its cursor to S, then calls `readSince(S)` to tail forward. This is **exactly-once over effect**: across an overflow the consumer observes every committed mutation's *effect* on the store, with no hole and no double application.

**Why snapshot-equivalent, not full historical-log replay.** The edge data plane is eventually-consistent and applies cumulative state, not an audited mutation-by-mutation log. A consumer that snapshots at S and tails from S sees every later mutation individually and every earlier mutation folded into the snapshot — sufficient for the §4.6 contract and far cheaper than reconstructing the full historical delta sequence from the WAL (a WAL scan that buys the data plane nothing it can observe). A future *auditing* consumer needing the exact historical mutation stream would get a separate, WAL-backed replay seam — out of scope here.

### 5. RR-066 fix — race-safe read side (folded in)

The hazard: `deltasSince` read `tail` then `head` non-atomically, so a concurrent appender could lap a reader mid-scan and yield duplicated/wrong deltas. The new `readSince` closes it with a **Lamport-style verify-after-read** (single writer, lock-free readers):

1. Read `tail` (t1), then `head` (h).
2. If `h − t1 > capacity`, the writer is mid-eviction: publish order is `ring.set → head++ → tail=`, so `head` can be observed ahead of the matching `tail` advance, and scanning `[t1, h)` would visit a wrapped slot twice and return a **duplicate**. → signal GAP.
3. Copy the `[t1, h)` window (null slot ⇒ GAP — writer mid-append/torn).
4. Re-read `tail` (t2). If `t2 ≠ t1`, an eviction (hence possible in-place overwrite) happened during the copy ⇒ the copy may be torn → signal GAP.
5. When `t2 == t1` **and** `h − t1 ≤ capacity`: no slot in the window could have been overwritten (overwrite requires eviction past t1), so the run is provably strictly-ascending, contiguous, no-duplicate, no-skip.

A `lastEvictedSeq` watermark (an `AtomicLong`, published **before** `tail` advances in `publish`) provides a fast-path GAP for a cursor already lapped — exact even across natural seq gaps (no-op/RCFG entries skip sequence numbers), because it compares the cursor against an actual evicted seq, not position arithmetic. The **append path stays allocation-free** (it stores the incoming `CommitNotification` reference; no per-append allocation). The legacy `deltasSince` (still non-atomic) is retained only for the pre-existing fan-out tests and is on no consumer path; the production drain uses `readSince`.

### 6. Producer wiring (no fan-out, no edge code)

`ConfigdServer`'s state-machine listener now publishes the full notification:

```
long commitTimestampMillis = clock.currentTimeMillis();  // leader wall clock, on the apply thread
fanOutBuffer.publish(new CommitNotification(version, commitTimestampMillis, delta));
```

`version` is the ADR-0033 applied-mutation seq S (the listener fires only on mutating applies). `clock.currentTimeMillis()` here runs on the **leader's** apply path, so it IS the leader-assigned commit timestamp ADR-0035 §2 redefined — captured **without editing `RaftNode`** (the state-machine listener wiring is the boundary-engineer's to extend; the apply seam already exists). The buffer is constructed with a `FanOutMetrics` sink bridging to the `fanout.buffer.dropped` counter. `ConfigdServer.commitNotificationSource()` and `replaySource()` expose the seams to S3. **No fan-out, no subscription, no wire protocol, no edge code added** — the named overflow/gap/replay seams are the entire S2 deliverable.

## Rejected alternatives

- **Block / backpressure the apply thread on a slow consumer.** Rejected — the apply thread is the single-writer Raft commit path (R-01); blocking it on a data-plane consumer couples control-plane liveness to edge health and violates §0.1 write-commit latency. Drop-oldest + replay decouples them.
- **Unbounded buffer / grow on overflow.** Rejected — unbounded memory on the commit path is a control-plane OOM risk under a stuck consumer; and it is unnecessary because the durable log+snapshot already reconstructs everything (the buffer's only job is to be a fast tail).
- **Lock the buffer for read/write.** Rejected — §5/§6 hard rules forbid locks on the read path; the verify-after-read is lock-free and the append stays allocation-free.
- **Per-entry HLC on the notification instead of one leader commit timestamp.** Rejected by ADR-0035 — conflates propagation latency with inter-node clock skew and adds a durable-format field for a deleted use case; one leader clock is the honest staleness instrument.
- **Carry only (seq, key, value) and drop the delta's signature/epoch/nonce.** Rejected — the edge needs the F-0052 signature/epoch/nonce to verify authenticity and reject replays; stripping them would force S3 to re-fetch or trust unsigned data.
- **Return a partial run + a "truncated" flag on overflow instead of GAP.** Rejected — a partial run that skips evicted notifications is precisely the RR-066 silent-wrong-data failure; an explicit GAP that routes to replay is the only safe signal.

## Consequences / blast radius

- **Positive:** RR-042 RESOLVED (read side is consumer-facing with a safe contract); RR-066 RESOLVED (race closed where it activates); RR-001 blast radius reduced (the bounded, replayable, race-safe interface now exists — only S3's wire/edge path remains). ADR-0035's §2 staleness clock is now emitted on the stream. The schema is transport-agnostic, so S3 is free to choose its wire format.
- **Negative / risks:** `readSince` returns GAP under contention even for a roughly-caught-up consumer during the transient eviction window (publish order exposes `head > tail+capacity` for one append); the consumer simply retries and the next read is contiguous (pinned by `readerPacedSeesContiguousStreamExactlyOnce`). Mitigation: the 10,000 ring keeps a healthy consumer far from the eviction frontier. The `commitTimestampMillis` is the leader's wall clock — inter-node skew (≤ 50 ms NTP, ADR-0035) is the documented residual error in the edge staleness measurement, not a bug here.
- **API:** new public types in `configd-distribution-service` (`CommitNotification`, `CommitNotificationSource`, `ReplaySource`, `SnapshotReplaySource`, `FanOutMetrics`); `FanOutBuffer` gains `publish`/`readSince`/`latestSeq`/`oldestSeq`/`droppedTotal` and now `implements CommitNotificationSource`. The legacy `append(ConfigDelta)`/`deltasSince`/`latest` are retained (back-compat for existing tests). The notification append is named `publish` (not an `append` overload) so `append(null)` stays unambiguous for existing callers.
- **Module placement:** the interfaces live in `configd-distribution-service` (which depends on `configd-config-store`, not on `configd-server`), so S3's edge module can depend on the boundary without dragging server internals.

## Discriminating proof

`CommitNotificationSourceTest`: (a) **bound** — `boundSustainedAppendsNeverGrowBeyondRing` (cap×100 appends, size never exceeds cap, oldest/latest seq track the window); (b) **overflow** — `overflowIncrementsDropCountAndStaleCursorGetsGap` (drop count == evictions; stale cursor ⇒ GAP carrying the floor; caught-up cursor ⇒ contiguous run), `caughtUpCursorReturnsEmptyOkNotGap`, `commitTimestampIsCarriedThrough`; (c) **replay** — `replayThenTailObservesEveryMutationEffectExactly` (25 seeds × randomized append/overflow/read interleaving; consumer that replays on GAP + tails ends state-equivalent to the authoritative model, cursor reaches the authoritative seq, every non-GAP run strictly ascending). `FanOutBufferRaceTest`: (d) **RR-066** — `concurrentReaderNeverSeesDuplicateOrSkippedSeqAndEventuallySeesAll` (4 readers, 200k writes, cap 64: no duplicate/skip in any non-GAP run, every seq eventually observed) + `readerPacedSeesContiguousStreamExactlyOnce` (reader-paced bounded handoff ⇒ exactly-once full stream). Listed for **B6 jcstress** (FanOutBuffer already on that list). All green; full `configd-distribution-service` suite (156) and `configd-server` suite green.

## Handoff to Session 3 — what to implement against this interface

S3's first task is to read this section. The S2 control-plane seams you build against (all in `configd-distribution-service`, exposed via `ConfigdServer`):

1. **Subscribe / tail.** Obtain `ConfigdServer.commitNotificationSource()`. Hold a cursor (last applied seq S; start at 0). Loop: `Result r = source.readSince(cursor)`.
   - `Ok(notifications)`: apply each in seq order, advance `cursor` to the last `seq`. Each `CommitNotification` gives you `seq`, `commitTimestampMillis`, and `delta` (keys/payloads + F-0052 signature/epoch/nonce — verify the signature and reject replays before applying at the edge).
   - `Gap(oldestRetainedSeq)`: go to step 2.
2. **Replay on GAP.** Call `ConfigdServer.replaySource().replayFromSnapshot()` → `(snapshot, seq)`. Apply the snapshot wholesale at the edge, set `cursor = seq`, then resume step 1 from `readSince(seq)`. This is exactly-once over effect — no hole, no duplicate.
3. **Wire / transport (S3 owns).** This interface is transport-agnostic. Build the push/pull transport, subscription model (per-key/prefix/full-store), and backpressure on top of `readSince`/`ReplaySource`. **Never** consume `FanOutBuffer.deltasSince` (non-atomic, RR-066) — only `readSince`.
4. **Edge staleness (ADR-0035 §2, joint with this work).** Feed `commitTimestampMillis` into `StalenessTracker.recordUpdate(version, timestamp)` (make the currently-ignored `timestamp` param load-bearing); compute `stalenessMs = edge_wall_now − commitTimestampMillis`. Assert INV-S2 (p99 < 500 ms / p9999 < 2 s) in `StalenessUpperBoundTest`. Document the ≤ 50 ms NTP-skew assumption + a negative/implausible-staleness tripwire.
5. **Slow-consumer policy.** A consumer that repeatedly GAPs (chronically behind the cache) should be quarantined / re-bootstrapped via full replay — wire `SlowConsumerPolicy` to a GAP-rate signal. `fanout_buffer_dropped_total` is your operational signal that consumers are falling behind the cache.

## Sign-off

- review-architect: _pending_ — please verify: the RR-066 memory-ordering argument (verify-after-read + the `h − t1 > capacity` lap case + the `lastEvictedSeq`-before-`tail` publish order); that the schema carries exactly what S3 needs and no more; that drop-oldest + replay does not lose committed effect (state-equivalence test); and that no fan-out / wire / edge code crept into S2 scope.
- second reviewer (RR-066 owed per Session-2 rule 7): _pending_ — independent verification of the concurrency fix (the register row carries the second-agent-verification-owed marker).
