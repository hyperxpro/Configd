# EXP-005 — Workstream A3: the four owed edge-chaos legs (S3 handoff §1)

- **Workstream:** A3 (charter §3 "the four owed chaos legs … your opening work"; S3 handoff §1)
- **Register rows:** no new RR — these de-vacuate the four named-but-unexercised legs (CT-40 edge-side gap; c6-e2e §7 ack-lag gap; c5-signoff F2 stalled-transfer; C4 churn never load-tested).
- **Owner:** data-plane-reliability-engineer · matrix arbitration: review-architect
- **Status:** GREEN — all four legs executed against their declared oracles (`fault-matrix.md §A3`); two mutation-discriminated, no production change.

## Why these four (cited)

The S3 charter §4 chaos matrix covered consensus faults; S3 named four data-plane legs but never
exercised them (S3 handoff §1, "Chaos legs S4 specifically owes"). Reconnaissance located each
enforcement point + injection seam before any test ran (matrix-before-execution, charter §1).

## A3-1 — Accept-then-black-hole fan-out endpoint (CT-40 edge-side)

**Fault:** an endpoint completes the TCP accept but never performs the TLS handshake (accept +
hold the socket open, never read/write/close).
**Cited expectation:** `EdgeStreamClient.createClientSocket` (`:453`) bounds the handshake with
`socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS=2000)` around `startHandshake()` (`:474`), mirroring
`TcpRaftTransport` (RR-002). Without it `startHandshake()` blocks forever and the edge wedges on
the first peer.
**Test:** `EdgeTransportMtlsTest.blackholedEndpointHandshakeTimesOutAndEdgeKeepsRetrying`
(real-socket, real TLS via the keytool fixture). A plain `ServerSocket` accepts + holds; a
TLS-configured edge points at it.
**Oracle:** the reconnect counter advances (`awaitReconnectAttempts(edge, 2)` — the edge timed out
of a black-holed handshake and looped to retry) while it never subscribes (`core().mode()==null`,
`heartbeatsObserved()==0`, `currentVersion()==0`). GREEN (15.8 s — two handshake-timeout cycles).
**Discriminator (not run — 60 s hang):** dropping `setSoTimeout(HANDSHAKE_TIMEOUT_MS)` parks the
session thread in `startHandshake()`; the reconnect counter cannot advance → the test hangs to its
`@Timeout(120)`. The counter advancing is therefore *only* possible if the bound bites.
**Note:** plaintext mode has no post-connect handshake timeout (`setSoTimeout(0)`), but plaintext
is test/single-node only (handoff §3); production is mTLS.

## A3-2 — Prod-threshold ack-lag demotion (8192)

**Fault:** a consumer reads but never acks, driven past the **production** `ackLagDemoteSeqs=8192`
(the E2E/integrated sim can only reach the tuned-down `=2`).
**Cited expectation:** `FanOutSessionCore.drainStreaming` (`:291`) demotes `REASON_ACK_LAG` when
`cursor − lastAckedSeq > ackLagDemoteSeqs` (ADR-0039 / §11 ladder; bounded, no unbounded buffer).
**Construction subtlety:** ack-lag is checked once at the top of `drainStreaming`; queue-overflow
per-frame inside the loop. 8193 seqs / batch 64 = 129 frames < `queueFrames=256` → no overflow, so
**ack-lag is the gate** (not queue-overflow). The cell also pins the strict-`>` boundary.
**Tests:** `FanOutSessionCoreTest.prodThresholdAckLagOverThresholdDemotes` (8193 → CATCHUP +
`REASON_ACK_LAG`, with cursor/lastAcked evidence) and `...AtThresholdDoesNotDemote` (8192 →
STREAMING, no demotion).
**Mutation M-acklag** (`>` → `>=` at `:291`): `...AtThresholdDoesNotDemote` RED
(`expected <STREAMING> but was <CATCHUP>`); over-threshold stays GREEN.
Capture `captures/exp-005-a32-acklag-offbyone-RED.txt`.

## A3-3 — Wedged-but-open transport during a paced snapshot transfer (characterization)

**Fault:** the transport sink returns would-block forever mid snapshot transfer (RR-102 pause path,
never drains).
**Cited expectation:** `FanOutSessionCore.performSnapshotTransfer` (`:371–427`) pauses/resumes on
the SAME envelope; cutover (cursor=S, STREAMING) runs ONLY when SNAPSHOT_END is accepted; a refused
snapshot frame is treated as would-block, NOT transport death (no session close).
**Test:** `FanOutSessionCoreTest.wedgedTransportDuringSnapshotPausesSafelyThenResumesAsOneEnvelope`
— multi-chunk snapshot, wedge for 20 ticks, then unwedge.
**Oracle:** while wedged — state stays CATCHUP every tick, cursor does NOT advance, no
SnapshotBegin/End delivered, no hot loop, no exception (bounded work). On unwedge — completes with
**exactly one** SnapshotBegin + one SnapshotEnd (not a restarted/torn envelope, RR-102), all chunks
contiguous, cursor=snapshot seq, STREAMING. GREEN. The pause/resume contract makes this inherently
discriminating (premature cutover or envelope-restart fails it); the paced path is already
mutation-covered by `BootstrapSnapshotBackpressureTest`.
**Observability (charter §8.9):** while wedged, the only detection proxy today is *stuck in CATCHUP
+ no `snapshot_transfers_total` increment + `edge_fanout_queue_depth` pinned*. Detection IS possible
via the proxy, so **no new metric is emitted here**; a dedicated stalled-transfer signal stays an
S6 item (c5-signoff F2) — recorded for the S5/S6 handoff.

## A3-4 — Long-running governor churn (bounded identity map)

**Fault:** repeated quarantine/readmit across **more distinct identities than the bound**
`maxTrackedIdentities` (default 4096) — never load-tested at C4.
**Cited expectation:** `SlowConsumerGovernor.evictIfAtBound` (`:370–385`) evicts only the
least-recently-touched **HEALTHY** record; distressed (SLOW/CATCHUP/QUARANTINED/UNHEALTHY) records
are **never** evicted (forgetting a quarantine = policy escape: cooldown skipped on re-admit). §11
bounded-memory.
**Tests:** `GovernorBoundedIdentityMapChurnTest` — (a) `churnNeverEvictsDistressedAndBoundsHealthyGrowth`:
3 quarantined survive 5000 distinct HEALTHY churns, map stays == bound; (b)
`allDistressedOverflowsHonestlyWithoutEvictingPolicyState`: when every tracked identity is
distressed, the map honestly exceeds the bound (no HEALTHY victim) rather than drop a quarantine.
**Mutation M-evict** (evict the access-order head regardless of state): both tests RED — a
quarantined identity dropped (`expected <QUARANTINED> but was <HEALTHY>`).
Capture `captures/exp-005-a34-governor-evict-distressed-RED.txt`.

## Verdict

All four owed legs executed against their declared oracles; A3-2/A3-4 mutation-discriminated,
A3-1 structurally discriminating (hang-on-no-timeout), A3-3 a characterization that pins the
RR-102 pause/resume contract. Production source byte-clean. The A3-3 stalled-transfer signal is the
only follow-up — explicitly an S6 observability item, recorded.

## Reproduction

```
./mvnw -o -pl configd-distribution-service test -Dtest='FanOutSessionCoreTest,GovernorBoundedIdentityMapChurnTest' -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -o -pl configd-edge-node test -Dtest='EdgeTransportMtlsTest#blackholedEndpointHandshakeTimesOutAndEdgeKeepsRetrying' -Dsurefire.failIfNoSpecifiedTests=false
# RED replays: M-acklag (> → >=, FanOutSessionCore:291) → A3-2 at-threshold RED;
#              M-evict (drop the HEALTHY guard in SlowConsumerGovernor.evictIfAtBound) → A3-4 RED.
```
