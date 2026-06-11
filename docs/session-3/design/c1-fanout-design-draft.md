# C1 Design Draft — Fan-out Distribution Service

> **Status: SCREENED — C1 IMPLEMENTATION CLEARED-WITH-CONDITIONS** (review-architect
> 2026-06-11, `docs/session-3/reviews/c1-design-review.md`; ADR-0037 ratified-with-changes
> [applied], ADR-0038 ratified). Conditions folded into this draft: error-code taxonomy +
> failover-resume field (§3), governing backpressure numbers / §7 credit model superseded
> (§4). Open decision 1 (idle staleness) arbitrated: ADR-0039 is owed BEFORE C2, C1 ships
> HEARTBEAT as a carrier only, and the idle-time StalenessTracker must NOT become the
> production staleness signal. V1 finding C-1 (sim must use per-node SkewedClock for commit
> timestamps) is owed before C2's staleness tests.
> After C1 lands, this file is superseded by the design NOTE (describing what IS, with test
> names as citations) per charter §1 rule 3. Contract rows: CT-17, CT-18, CT-19, CT-22,
> CT-25(C1 half), CT-26, CT-39, CT-41 (codec), plus the C4 rows' session-state substrate.

## 1. Scope

C1 delivers the server side of the wire path RR-001 indicts:

- `FanOutSessionCore` — transport-agnostic per-subscriber session engine (the class the
  simulator drives as its `StreamDriver`; the same code the live endpoint runs).
- `EdgeFrameCodec` + protocol v1 frames — the edge wire discipline (ADR-0037).
- `FanOutServer` — the mTLS endpoint in `configd-server` (accept loop, sessions).
- Named-config thresholds + metrics for every policy decision (charter §6 rule 8).

NOT C1: the edge process (C2), gap recovery on the edge (C3), the full slow-consumer
policy state machine (C4 — C1 provides the session states + transition events C4 governs),
bootstrap orchestration (C5).

## 2. Layering (sim-testability is the design driver)

```
                    ┌──────────────────────────────────────────────┐
                    │ ConfigdServer (configd-server)                │
                    │  FanOutServer: ServerSocket (TlsManager mTLS)│
                    │   1 virtual thread / connection               │
                    └───────────────┬──────────────────────────────┘
                                    │ frames (EdgeFrameCodec)
            ┌───────────────────────▼───────────────────────────┐
            │ FanOutSessionCore (configd-distribution-service)   │
            │  - cursor, bounded outbound queue, session state   │
            │  - drain loop: readSince(cursor) → NOTIFY batches  │
            │  - Gap → SNAPSHOT (chunked) → resume tail          │
            │  - demotion on overflow / ack-lag (C4 substrate)   │
            │  - TransportSink interface (frames out)            │
            └───────────────────────┬───────────────────────────┘
                                    │ consumes (ADR-0034 only)
            ┌───────────────────────▼───────────────────────────┐
            │ CommitNotificationSource.readSince / ReplaySource  │
            └────────────────────────────────────────────────────┘
```

`FanOutSessionCore` is deterministic and clock-injected: the simulator drives it tick-by-tick
as the V1 `StreamDriver` implementation (re-enabling `EdgePropagationBacklogTest` and putting
the real C1 logic under the 507-seed adversarial gate); the live `FanOutServer` drives the
identical code from virtual threads. No logic exists only on the live path.

## 3. Protocol v1 (`EdgeFrameCodec`, `EDGE_WIRE_VERSION = 0x01`)

Frame discipline per ADR-0037 (length-prefix bounds-checked before allocation, version byte,
type byte, CRC32C trailer, explicit frame cap, golden-fixture test from day one — CT-41).

| Frame | Direction | Payload | Notes |
|---|---|---|---|
| `SUBSCRIBE` | edge→server | prefixes[] (or full-store marker), resume cursor, edge id, **failover-resume cursor (reserved: the cursor obtained from a PREVIOUS fan-out endpoint, for the contract §3 edge-failover clause — C2 populates it; v1 servers treat it as the resume cursor)** | one per connection; edge id bound to the mTLS cert identity (review condition, C2 note) |
| `SUBSCRIBE_OK` | server→edge | latestSeq, mode (TAIL or SNAPSHOT_FIRST) | server decides via cursor vs `oldestSeq()` |
| `NOTIFY` | server→edge | batch of `CommitNotification` (seq, commitTsMillis, **verbatim signed delta**) | batching per ADR-0038 — chain intact, no coalescing |
| `SNAPSHOT_BEGIN` | server→edge | snapshotSeq, chunkCount, totalBytes | chunked from day one (RR-019 lesson; CT-31's 1 MiB + per-chunk CRC) |
| `SNAPSHOT_CHUNK` | server→edge | index, bytes | CRC32C per frame already; cap 1 MiB |
| `SNAPSHOT_END` | server→edge | snapshotSeq | edge sets cursor = snapshotSeq, resumes tail |
| `CURSOR_ACK` | edge→server | highest applied seq | backpressure + C4 signal |
| `HEARTBEAT` | server→edge | latestSeq, serverNowMillis | idle-staleness input (§6 open decision) |
| `ERROR/CLOSE` | both | code (fixed taxonomy below), message | incl. demotion/quarantine notices |

**ERROR/CLOSE code taxonomy (fixed, pinned in the CT-41 golden fixture — review condition 3).**
C2/C3/C4 map to these codes; no free-form strings:

| Code | Name | Emitted when |
|---|---|---|
| 1 | `BAD_WIRE_VERSION` | version byte ≠ `EDGE_WIRE_VERSION` |
| 2 | `FRAME_TOO_LARGE` | declared length exceeds the frame cap |
| 3 | `FRAME_CORRUPT` | CRC32C mismatch / malformed payload |
| 4 | `AUTH_FAIL` | mTLS identity rejected / not authorized |
| 5 | `BAD_SUBSCRIBE` | malformed subscription spec / cursor |
| 6 | `GAP_UNRECOVERABLE` | replay source unavailable for a needed range |
| 7 | `DEMOTED_TO_CATCHUP` | session overflow/ack-lag demotion notice (non-fatal) |
| 8 | `QUARANTINED` | C4 policy: session quarantined, must re-bootstrap |
| 9 | `SERVER_SHUTDOWN` | orderly close |
| 10 | `PROTOCOL_VIOLATION` | unexpected frame for session state |

Per ADR-0038: every subscriber receives the full signed chain regardless of prefixes
(prefixes are echoed to C2 as the edge-side storage filter); a NOTIFY batch is N verbatim
deltas, never a merged one.

## 4. Backpressure & session states (charter C1 + §11; C4 substrate)

Per-session **bounded** outbound queue (frames). Named configs (each with a metric):

| Config | Default | Metric |
|---|---|---|
| `edge.fanout.session.queueFrames` | 256 | `edge_fanout_queue_depth` (per-session gauge) |
| `edge.fanout.session.queueWarnPct` | 80 | `edge_fanout_slow_consumer_warnings_total` |
| `edge.fanout.notify.batchMaxNotifications` | 64 | `edge_fanout_notify_batch_size` (histogram) |
| `edge.fanout.notify.batchMaxBytes` | 256 KiB | — (same histogram, bytes variant) |
| `edge.fanout.session.ackLagDemoteSeqs` | 8192 | `edge_fanout_demotions_total{reason=ack_lag}` |
| `edge.fanout.heartbeatMs` | 250 | `edge_fanout_heartbeats_total` |

**Which numbers govern (review condition 4, resolves CT-26):** the frame/byte thresholds in
the table above govern the edge streaming path. Architecture §7's credit model (100 credits /
1000-entry buffer / 80%-100% disconnect) predates ADR-0034's ring-10,000 and ADR-0038's
frame/byte accounting and is **superseded for this path**; C4's slow-consumer thresholds are
defined over these frame/byte/ack-lag signals, not credits. (Architecture §7 is amended by
reference here; the consolidated doc pass happens at session close.)

States (C1 implements; C4 adds quarantine policy on top of the events):
`STREAMING → (queue overflow | readSince Gap | ack lag breach) → CATCHUP (snapshot+chunks,
queue dropped — cursor evidence retained in the demotion event/log) → STREAMING`.
Never an unbounded queue; never a silent drop: every demotion emits a structured log event +
metric with (session, cursor, lastAckedSeq, reason). Disconnect/quarantine/re-bootstrap
thresholds are C4 rows (CT-27..30) layered on these transition events.

**Publish-path isolation (hard rule 4 screen):** the apply thread's only interaction remains
`fanOutBuffer.publish` (lock-free, allocation-free — unchanged). Sessions PULL via
`readSince`; no per-subscriber work happens on the publish path; no global lock anywhere
(per-session state is session-confined; the subscriber registry is a concurrent map touched
only on connect/disconnect). Drain wake-up: poll with adaptive backoff (active: immediate
re-poll while `readSince` returns data; idle: parkNanos backoff capped at
`edge.fanout.idlePollMs` = 5 ms default). Polling a lock-free ring at 5 ms costs nothing
measurable and avoids touching the apply path with any signaling primitive.

## 5. Module placement & dependencies

- `FanOutSessionCore`, `EdgeFrameCodec`, frames, session states: `configd-distribution-service`
  (already depends on config-store + transport; gains NO new third-party deps).
- `FanOutServer` + wiring (`--edge-port`, TlsManager reuse): `configd-server`.
- Mutation profile: extend configd-distribution-service's existing PIT profile to the new
  classes at ≥ 65 from day one (gate-3).

## 6. Open decisions for the review (blocking items marked)

1. **[BLOCKING] Idle staleness is unsound in the contract as written.** Contract §2 / INV-S1
   defines staleness = `wall_now − commit_ts(last_applied_notification)`. Under a quiet
   system (no commits for 30 s — normal for config workloads) every healthy edge marches
   CURRENT→STALE→DEGRADED→DISCONNECTED and triggers re-bootstrap storms while perfectly in
   sync. The existing proxy implementation (idle-time) has the same defect. Proposed
   resolution (needs ADR-0039): staleness is measured against the **frontier the edge knows
   it has covered**: `staleness = wall_now − max(commitTs(lastApplied),
   serverNowMillis(last HEARTBEAT where latestSeq == cursor))`. The HEARTBEAT is
   relay-asserted (not leader-signed); residual trust documented (a stalled-but-heartbeating
   relay can mask staleness for the keys it suppresses — already the ADR-0038 residual,
   detectable only via chain breaks on the next real delta). Affects CT-01/02/07; the
   protocol carries the frame either way.
2. **[BLOCKING] ADR-0037 + ADR-0038 ratification** (this design assumes both).
3. SNAPSHOT payload format: reuse ADR-0028 snapshot serialization (`serializeSnapshot`'s
   opaque state-machine `data`) chunked at the frame layer vs. a new key-value streaming
   format. Draft picks ADR-0028 reuse (no second format to version); confirm.
4. `secure/` keys at the edge (CT-37): ADR-0038 delivers them on the chain (suppression
   detectability); C2 decides store-and-never-serve vs store-nothing+route. C1 carries them
   regardless — confirm no C1-level filtering.
5. Heartbeat cadence vs staleness thresholds: 250 ms default heartbeat against the 500 ms
   STALE threshold gives 2× margin on an idle stream — confirm.

## 7. Test plan (written FIRST when C1 opens, per the map)

- `EdgeFrameCodecGoldenFixtureTest` + `EdgeFrameCodecPropertyTest` (CT-41; jqwik round-trip,
  truncation/corruption/cap cases mirroring `FrameCodecPropertyTest`).
- `FanOutSessionCoreTest` (unit: drain/batch/Gap→snapshot/demotion transitions; SimulatedClock).
- `FrameBatchingChainIntegrityTest` (CT-17: batches never break the seq chain; property test).
- `FullChainDeliveryTest` (CT-25 C1-half: prefix subscriber still receives the full chain).
- `SubscriberQueueBoundTest`, `SubscriberOverflowDemotionTest` (CT-26: queue never exceeds
  bound; overflow demotes with cursor evidence; no silent drop).
- `NoDeltasSinceOnConsumerPathTest` (CT-22 static guard).
- Sim: C1 `StreamDriver` impl → `EdgePropagationBacklogTest` re-enabled (CT-39 sim-level),
  507-seed gate with edge invariants, plus a mid-stream leader-kill seed scenario.
- Live: `FanOutServerMtlsTest` (RR-094 pattern), loopback session integration test.
