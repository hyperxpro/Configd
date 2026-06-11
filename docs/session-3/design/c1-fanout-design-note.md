# C1 Design Note — Fan-out Distribution Service (AS BUILT)

> **Status: AS-BUILT design note for component sign-off** (charter §1 rules 2-3: describes
> what IS, with test names as citations; supersedes `c1-fanout-design-draft.md` which is
> retained as the screened pre-implementation record). Commits: `ca22214` (part a:
> codec + session core + sim), `a74bcbf` (part b: mTLS endpoint).
> Sign-offs: review-architect _pending below_; contract-qa _pending below_.

## 1. What exists at runtime

A committed write now travels: `ConfigStateMachine` apply → `FanOutBuffer.publish`
(unchanged, lock-free, ADR-0034/0036) → per-subscriber `FanOutSessionCore` pull via
`readSince(cursor)` → `NOTIFY` frames over mTLS sockets → a subscribed client. Verified
live by `FanOutServerIntegrationTest.subscribeReceivesNotifiesAndAcksFlowControlAndRecoversAndHeartbeats`
(real `ConfigdServer`, real HTTP write returning `Committed: seq=S`, real socket client
receiving the verbatim signed delta). RR-001's "no drain caller, no wire path" is no
longer factually true at the server side; the row stays OPEN until the edge process (C2)
and E2E (C6) complete the claim.

## 2. Layers as built (all hard-rule screens held)

| Layer | Class (module) | Proof |
|---|---|---|
| Protocol model | `EdgeFrame` sealed, 9 types; `ErrorCode` 10-code taxonomy; reserved `failoverResumeCursor` (io.configd.distribution.wire) | `EdgeFrameCodecGoldenFixtureTest` (25 fixtures: every type, every error code, empty NOTIFY, 1 MiB chunk; rebaseline rule = EDGE_WIRE_VERSION bump, commented) |
| Codec | `EdgeFrameCodec` — `EDGE_WIRE_VERSION=0x01`, `[4B len][1B ver][1B type][payload][4B CRC32C]`, peekLength bounds-check before allocation, CRC before interpretation, 2 MiB frame / 1 MiB chunk caps, NOTIFY 64/256 KiB encode caps | `EdgeFrameCodecPropertyTest` (jqwik round-trip, per-byte truncation, single-bit corruption → CRC error, cap rejection pre-allocation), `EdgeCodecBoundaryTest`; signed-delta byte-fidelity: round-trip preserves `signingPayload()` byte-identically |
| Snapshot transport | `EdgeSnapshotCodec` — ADR-0028 body reuse, chunk/reassemble | `EdgeSnapshotCodecTest` (6) |
| Session engine | `FanOutSessionCore` — deterministic, clock-injected, pull-only; TAIL vs SNAPSHOT_FIRST; NOTIFY batching (verbatim, ADR-0038); ack-window backpressure; demotion → chunked snapshot → resume; HEARTBEAT(latestSeq, serverNow) carrier | `FanOutSessionCoreTest` (15), `FanOutSessionCoreBoundaryTest`, `FrameBatchingChainIntegrityTest`, `FullChainDeliveryTest`, `SubscriberQueueBoundTest`, `SubscriberOverflowDemotionTest` |
| Sim driver | `C1StreamDriver` (testkit) — the V1 `StreamDriver` seam implemented by the production session core | `EdgePropagationBacklogTest` (RE-ENABLED, GREEN — capture `c1-backlog-green.txt`), `EdgeAdversarialGateSeedSweepTest` (507 seeds, 0 safety violations, convergence-given-quiet 96.1%), `EdgeLeaderKillScenarioTest`, `EdgeSimDeterminismTest` C1-driver variant |
| Wire endpoint | `FanOutServer` (configd-server) — `--edge-port`, mTLS via shared `TlsManager`, virtual threads, reader→command-queue→single-writer session thread (R-01 style), bounded writer queue, cert-DN-authoritative identity, fan-out-first shutdown | `FanOutServerMtlsTest` (3: no-cert/wrong-CA unusable, trusted accepted under cert identity), `FanOutServerIntegrationTest` (3) |
| Metrics | `RegistryFanOutSessionMetrics` — eager registration (RR-013) | `RegistryFanOutSessionMetricsTest` (3) + integration `/metrics` assertions |

## 3. The chain-integrity invariant (CT-17's renegotiated rule, as tested)

`FrameBatchingChainIntegrityTest`: for arbitrary publish/ack/tick interleavings, the
concatenation of all NOTIFY batches a session emits is a strictly-ascending, contiguous
subsequence of the published applied-mutation seq chain — no duplicate, no merge (every
emitted notification is a verbatim published one), no skip — except across an explicit
`SNAPSHOT_BEGIN..SNAPSHOT_END` boundary, after which the stream resumes contiguous from
the first seq > snapshot seq. This is ADR-0038's "no coalescing" made executable.

## 4. Bugs found by the machinery before integration (the Phase-V dividend)

1. **Snapshot stranding:** the session advanced `lastAckedSeq` to the snapshot seq on
   send; a dropped snapshot stranded the edge forever. Fix: only `CURSOR_ACK` advances
   `lastAckedSeq` → lost snapshots rebuild ack-lag → re-demote → re-send (self-healing).
2. **Backward-snapshot regression:** a demotion snapshot from a transiently-behind node
   regressed an edge (a monotonicity violation caught by the V1 invariant). Fix: edge
   refuses `snapshot.seq < cursor` and re-acks its position.

Both were surfaced by `EdgeAdversarialGateSeedSweepTest` seeds, fixed, and are pinned by
session-core unit tests.

## 5. Known deviations / residuals (all documented at their site)

- `EdgeInvariants.finalCheck` compares **effect** (value bytes + store version), not
  per-key version stamps — ADR-0028 snapshots carry no per-key versions; matches
  ADR-0034 "exactly-once over effect"; guard: no per-key stamp may exceed store version.
- Metric labels → name suffixes (`edge_fanout_demotions_ack_lag_total` etc.):
  `MetricsRegistry` is label-free. Priced, not hidden.
- `edge_fanout_queue_depth` is a process-level high-water gauge (label-free registry);
  per-session breakdown needs a label-capable backend.
- TLS 1.3 reject tests assert connection-unusable (no `SUBSCRIBE_OK` ever served), not
  handshake-throw — timing-robust per the find0051 discipline.
- Sim uses a sim-tuned `FanOutConfig` (`ackLagDemoteSeqs=2`) because sim runs commit tens
  of seqs; production uses `defaults()` (8192). Both paths exercise the same transitions.
- 507-seed sweep: 6 delivery-bound liveness violations + raw convergence 62.1% under
  never-healed schedules (quiet-window convergence 96.1%) — recorded honestly in
  `EDGE-GATE-SUMMARY`; the RR-095-class characterization (CP liveness under hostile
  schedules, not C1 logic) is part of the sign-off review's checklist.
- The ADR-0037 contingency held: zero socket/TLS types in `wire`/`fanout` packages
  (verified by the engineer; reviewer re-verifies at sign-off).

## 6. Mutation & suite evidence

New packages 70.6% (threshold 65, committed profile: `wire.*` + `fanout.*` in
targetClasses): `EdgeFrameCodec` 75%, `EdgeSnapshotCodec` 70%, `FanOutSessionCore` 64%,
config/enums/records 100%. Suites: configd-distribution-service 242/0,
configd-server 125/0/0/0, configd-testkit 1168/0 (2 expected nightly skips).

## Sign-off

- review-architect: _pending_
- contract-qa-engineer: _pending_
