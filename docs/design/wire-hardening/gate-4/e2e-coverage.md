# Wire-Hardening Arc — Gate 4 · Integration + multi-node E2E coverage

Gate 4 proves the Gate-1/2/3 hardening holds **end-to-end over the real transport, in a live cluster,
under real conditions** — not just at the codec seam a fuzzer drives. Gates 1–3 catalogued every frame,
fixed every reject path, and machine-swept every codec in isolation; Gate 4 stands up real nodes on real
sockets and shows the fixes survive first contact with a running consensus/edge plane.

Two genuinely-missing proofs were written net-new this gate (the crown jewels); the remaining scenarios
were already covered by mature integration tests, mapped below. **Nothing is papered over: where an
existing test is the evidence, it is named; where this gate added the evidence, it is marked NEW.**

## New tests added this gate

| Test (NEW) | Module | Scenario | What it proves over the REAL wire |
|---|---|---|---|
| `RaftFailoverE2ETest` | configd-server | #1 leader failover | 3 real `RaftNode`s, each behind its own plaintext `NettyRaftTransport` + `RaftTransportAdapter` + `CoalescingRaftTransport` on real localhost TCP. Elect a leader; commit + replicate a batch to all three; **KILL the leader** (close transport, stop ticks); the two survivors **re-elect** (new leader, term advances); **every pre-failover committed key survives** with its exact value on both survivors (read from their stores); a fresh write commits + replicates on the 2-of-3 quorum. Closes the "N>1 never failover-tested" gap. |
| `HostilePeerInjectionE2ETest` | configd-server | #5 hostile peer (KEY) | Same live 3-node real-wire cluster. After a stable leader, a **raw TCP attacker** connects to a FOLLOWER's real consensus listen port and blasts a 5-vector hostile-frame battery (oversized length prefix, corrupt CRC32C, dormant/undecodable type [WH-10], structurally-malformed `APPEND_ENTRIES` [numEntries=`Integer.MAX_VALUE`], truncated-then-close). Asserts: the follower **counts** the decode-boundary rejects (`onInboundFrameDropped`), the leader **holds its term** across a stability window (no spurious election), and an honest write **still commits + replicates on the attacked node** (its inbound pipeline + apply loop survived). |

Both are **deterministic-where-possible + deadline-polled** (no `Thread.sleep`-as-synchronization; the
`@Timeout` is pure hang detection), mirroring the repo's de-flake discipline and the proven
`NettyConsensusLivenessTest.RealWireCluster` wiring. Wall-clock on the throttled 2-vCPU box: **~6.7 s**
(failover) and **~8.5 s** (hostile) — cheap enough for the CI inner loop.

### Harness reuse (did NOT reinvent)
The two new tests mirror `NettyConsensusLivenessTest.RealWireCluster` — the project's proven real-wire
Raft cluster pattern (per-node owner thread, plaintext `NettyRaftTransport`, `RaftTransportAdapter`,
`CoalescingRaftTransport` with coalescing ACTIVE, `monitorView()`-based off-owner observation, a generous
election budget in the `NettyConsensusLivenessTest`-validated ratio-20 range). The additions are the
minimum each scenario needs: retained per-node `VersionedConfigStore`s (read committed values back for the
no-data-loss claim), a `killNode` primitive, a counting `RaftTransportMetrics` sink, and a raw-socket
hostile-frame injector.

## Scenario coverage map (all 6 arc Gate-4 scenarios)

| # | Scenario | Covering test(s) | Status |
|---|---|---|---|
| 1 | Multi-node Raft replication + **failover** over real transport | **`RaftFailoverE2ETest`** (NEW) + `EncryptedMultiShardClusterCompositionTest` (replicate + restart-rejoin) | ✅ real wire |
| 2 | Multi-shard watch fan-in over real wire | `EncryptedMultiShardClusterCompositionTest` (a follower's `WatchService` fires on a replicated shard-0 write); edge multi-shard WATCH plane: `FanOutServerIntegrationTest`, `EdgeNodeIntegrationTest` | ✅ existing |
| 3 | Edge hydration + streaming (SUBSCRIBE→snapshot→NOTIFY delta) | `EdgeNodeIntegrationTest` (real `ConfigdServer` `--edge-port` + real `EdgeNodeMain`, HTTP write→propagate→cursor read), `FanOutServerIntegrationTest` | ✅ existing |
| 4 | Under fault: restart/rejoin, dropped conn, partial frames, slow consumers, oversized | restart/rejoin: `EncryptedMultiShardClusterCompositionTest`; dropped conn/failover: `EdgeFailoverTest`; partial+oversized frames at a live node: **`HostilePeerInjectionE2ETest`** (NEW); slow-loris / read-idle: `TcpRaftTransportSlowlorisTest`, `InboundReadDeadlineFuzzTest`, `NettyRaftTransportHandshakeTimeoutTest`; client snapshot-chunk backpressure: `EdgeSnapshotAccumulationBoundsTest`, `BootstrapSnapshotBackpressureTest` | ✅ mixed |
| 5 | **Hostile peer injected into a real cluster** (KEY) | **`HostilePeerInjectionE2ETest`** (NEW, codec/transport reject vectors in a live cluster); identity forgery (WH-08/09) over mTLS: `RaftPeerIdentityBindingTest` + `NettyRaftPeerIdentityBindingTest` | ✅ real wire |
| 6 | Encryption-ON E2E over the wire | `EncryptedMultiShardClusterCompositionTest` (encryption at rest ON × 3 real nodes × 2 shards × witness armed × watches × restart) | ✅ existing |

Scenario 5's split is deliberate and honest: the **codec/transport reject paths** (oversized, corrupt-CRC,
dormant-type, malformed payload, truncation) do not need mTLS and are proven in a live plaintext cluster by
the NEW test; the **identity-binding reject** (forged `senderId`, non-node client cert, reverse-path
forgery — WH-08/09) is an mTLS property already proven over real mTLS sockets by the peer-identity binding
tests on BOTH transports. The **poison-pill committed command** (WH-01) is a state-machine apply property
(deterministic non-mutating skip in `ConfigStateMachine.apply`, machine-swept by `CommandCodecFuzzTest`); a
non-leader peer cannot inject it without first being rejected at the Raft term/log layer, so it is not a
live network-injection vector and is not claimed as one.

## Per-frame E2E / integration coverage

Legend: **RW** = exercised over the real transport (TCP/Netty sockets); **INT** = process-level
integration (real server/edge, in-process seams); **U/F** = unit + Gate-3 fuzz (codec seam).

### Raft consensus plane

| Frame | Real-wire / integration test exercising it | Note |
|---|---|---|
| `APPEND_ENTRIES` (0x01) | **RW** `RaftFailoverE2ETest`, `HostilePeerInjectionE2ETest`, `NettyConsensusLivenessTest`, `RaftTransportAdapterLoopbackTest` | replication + heartbeat + malformed-inject |
| `APPEND_ENTRIES_RESPONSE` (0x02) | **RW** `RaftFailoverE2ETest` (commit acks), `NettyConsensusLivenessTest` | |
| `REQUEST_VOTE` / `PRE_VOTE` (0x03/0x05) | **RW** `RaftFailoverE2ETest` (the re-election), `NettyConsensusLivenessTest` (non-vacuity election) | |
| `REQUEST_VOTE_RESPONSE` / `PRE_VOTE_RESPONSE` (0x04/0x06) | **RW** `RaftFailoverE2ETest` re-election path | |
| `INSTALL_SNAPSHOT` (0x07) | **RW** `ChunkedInstallSnapshotTest`; **INT** `EncryptedMultiShardClusterCompositionTest` restart-recovery (follower catch-up) | |
| `INSTALL_SNAPSHOT_RESPONSE` (0x0F) | **RW** `ChunkedInstallSnapshotTest` | |
| `TIMEOUT_NOW` (0x10) | **U/F** `RaftMessageCodecFuzzTest`; leadership-transfer wiring is dormant (see readiness register) | codec-covered |
| `RAFT_COALESCED_HEARTBEAT` (0x11) | **RW** `RaftFailoverE2ETest` / `HostilePeerInjectionE2ETest` (coalescing ACTIVE), `NettyConsensusLivenessTest` (THE coalesced-HB liveness proof) | |
| `RAFT_WITNESS` / `_REPLY` (0x12/0x13) | **RW** `RaftTransportAdapterLoopbackTest` (authenticated `from` over TCP); **INT** composition witness armed | |
| `LogEntry.command` (nested, CommandCodec) | **RW** every committed write in the two NEW tests; **U/F** `CommandCodecFuzzTest` | 100% codec cov |
| dormant `PLUMTREE_*`/`HYPARVIEW_*`/`HEARTBEAT` (0x08–0x0E) | **RW** `HostilePeerInjectionE2ETest` (HYPARVIEW_JOIN injected → WH-10 counted drop) | |

### Edge / distribution plane

| Frame | Test exercising it | Note |
|---|---|---|
| SUBSCRIBE (0x01) / SUBSCRIBE_OK (0x02) | **INT** `EdgeNodeIntegrationTest`, `FanOutServerIntegrationTest`, `EdgeFailoverTest` | hydration handshake |
| NOTIFY (0x03) | **INT** `EdgeNodeIntegrationTest` (delta stream), `FanOutServerIntegrationTest` | |
| SNAPSHOT_BEGIN/CHUNK/END (0x04–0x06) | **INT** `EdgeNodeIntegrationTest` (bootstrap snapshot); `EdgeSnapshotAccumulationBoundsTest` (WH-13/15 client cap) | |
| CURSOR_ACK (0x07) | **INT** `EdgeNodeIntegrationTest` (cursor-monotonic reads) | |
| HEARTBEAT (0x08) | **INT** `EdgeNodeIntegrationTest`, `EdgeFailoverTest` (staleness/liveness) | |
| ERROR_CLOSE (0x09) | **INT** fan-out driver refusal paths (`FanOutConnectionDriver` tests) | |
| WATCH_CREATE/CANCEL (0x0A/0x0B) | **INT** multi-shard WATCH plane (`FanOutServerIntegrationTest`) | |
| WATCH_CREATED/EVENT/PROGRESS/CANCELED (0x0C–0x0F) | **INT** `EncryptedMultiShardClusterCompositionTest` (follower watch fires), fan-out WATCH tests | |
| WATCH_SNAPSHOT_BEGIN/CHUNK/END (0x10–0x12) | **INT** watch-snapshot veneer tests; `EdgeSnapshotAccumulationBoundsTest` | |

## Per-reject-path (WH-\*) E2E / integration coverage

Every Gate-1 finding's reject path has an explicit test. **Bold** = a Gate-4 real-wire/live-cluster test.

| WH | Reject path | Covering test(s) |
|---|---|---|
| WH-01 | malformed committed command → deterministic non-mutating skip (no crash-loop) | `CommandCodecFuzzTest` (total codec) + `ConfigStateMachine.apply` catch (`ConfigStateMachineMetricsTest`) |
| WH-02 | CommandCodec key/value/batch bound-before-alloc | `CommandCodecFuzzTest` (B), `CommandCodecTest` — **codec 100% cov** |
| WH-03 | CommandCodec empty/blank key reject | `CommandCodecFuzzTest` (R), `CommandCodecTest` |
| WH-05 | `INSTALL_SNAPSHOT.offset < 0` reject | `RaftMessageCodecFuzzTest` (R neg-offset) |
| WH-06 | strict-end trailing-byte reject (AppendEntries / InstallSnapshot) | `RaftMessageCodecFuzzTest` (R trailing) |
| WH-07 | groupId bound at demux (unregistered/hostile gid dropped) | **`RaftInboundDemuxTest`** (hostile `Integer.MIN/MAX`, negative, large gids dropped, owner not wedged) |
| WH-08/09 | forged `senderId` / in-body id / non-node cert → rejected + counted (enforced policy) | **`RaftPeerIdentityBindingTest`** + **`NettyRaftPeerIdentityBindingTest`** (real mTLS sockets, both tiers, reverse-path) |
| WH-10 | dormant/undecodable Raft type → counted rate-limited drop, connection kept | **`HostilePeerInjectionE2ETest`** (HYPARVIEW_JOIN + malformed AE at a live follower → `onInboundFrameDropped`) |
| WH-11 | edge pre-SUBSCRIBE handshake / read-idle deadline (slow-loris) | `InboundReadDeadlineFuzzTest`, `TcpRaftTransportSlowlorisTest`, `NettyRaftTransportHandshakeTimeoutTest` |
| WH-12 | SUBSCRIBE `prefixCount` tight bound + `MAX_PREFIXES` | `EdgeSubscribeBoundsTest`, `EdgeFrameCodecFuzzTest` (prefixCount sweep) |
| WH-13 | client snapshot-chunk accumulation cap (`chunkCount`/`totalBytes`) | `EdgeSnapshotAccumulationBoundsTest`, `PoisonPillRebootstrapTest`, `BootstrapSnapshotBackpressureTest` |
| WH-14 | NOTIFY decode `MAX_NOTIFY_BATCH_BYTES` cap | `EdgeFrameCodecFuzzTest` (NOTIFY byte-cap sweep), `EdgeFrameCodecStrictnessTest` |
| WH-15 | SNAPSHOT_BEGIN cross-field validation (couples WH-13) | `EdgeSnapshotAccumulationBoundsTest` |
| framing | oversized length prefix (>16 MiB) → reject before alloc; corrupt CRC → drop; truncation → no half-frame | **`HostilePeerInjectionE2ETest`** (live follower) + `FrameCodecFuzzTest` / `EdgeFrameCodecFuzzTest` (B) |

## Codec / wire-path coverage numbers (JaCoCo)

Measured with `-Pmutation` (JaCoCo 0.8.14 agent) on the codec + fuzz + golden test suites, plus the two
NEW Gate-4 E2E tests for the Raft consensus classes. **These are a conservative floor** — the edge codecs
are additionally driven by the process-level integration tests (`EdgeNodeIntegrationTest`,
`FanOutServerIntegrationTest`), which are not in the scoped runs below, so full-suite coverage is higher.

| Class | Module | Instruction | Branch | Line | Method |
|---|---|---|---|---|---|
| `CommandCodec` | configd-config-store | **100%** (437/437) | **100%** (35/35) | **100%** (99/99) | **100%** (10/10) |
| `MessageType` | configd-transport | **100%** (191/191) | **100%** (8/8) | **100%** (31/31) | **100%** (4/4) |
| `FrameCodec` | configd-transport | 98.4% (367/373) | 96.2% (25/26) | 98.9% (87/88) | 100% (6/6) |
| `RaftMessageCodec` | configd-server | 94.0% (1319/1403) | 89.9% (116/129) | 94.6% (264/279) | 100% (26/26) |
| `RaftTransportAdapter` | configd-server | 93.6% (280/299) | 69.0% (20/29) | 95.5% (63/66) | 100% (13/13) |
| `EdgeFrameCodec` | configd-distribution-service | 91.3% (2097/2296) | 88.4% (205/232) | 93.2% (480/515) | 98.0% (48/49) |
| `EdgeSnapshotCodec` | configd-distribution-service | 89.2% (398/446) | 83.3% (40/48) | 91.3% (84/92) | 100% (6/6) |

The `RaftTransportAdapter` branch gap (69%) is the identity-enforcement predicate set, exercised only under
an enforced `PeerIdentityPolicy` (mTLS) by the peer-identity binding tests, which are outside this scoped
run; the two NEW E2E tests run it unenforced (plaintext). The residual `RaftMessageCodec`/`EdgeFrameCodec`
uncovered lines are encode-side symmetry paths and defensive `default` arms that the Gate-3 fuzzers already
sweep for non-crash behaviour.

## Flakiness / determinism notes

- **No `Thread.sleep`-as-synchronization.** Every wait is a deadline-poll on a real condition
  (`monitorView()` role/term/lastApplied, store values, drop counters). `@Timeout` is hang detection only.
- **Election-budget margin.** Both new tests pin heartbeat 50 ms ≪ election 1000–2000 ms (ratio 20, the
  `NettyConsensusLivenessTest`-proven-stable range) and 1 Netty worker thread, so 2-vCPU scheduling jitter
  cannot manufacture a spurious election or failover.
- **Deterministic ports + seeds.** Distinct loopback ports reserved up front; per-node RNG seeded.
- **Observed stable** across repeated targeted runs on the throttled box; no flake encountered.

## Findings

**No end-to-end defect was found.** The Gate-2 hardening holds in a live cluster: a leader can die and the
cluster loses no committed data; a hostile peer can blast the full malformed-frame battery at a running
follower and the node neither crashes, wedges, nor destabilizes consensus, and stays consistent. The two
net-new proofs close the last two integration gaps the per-gate suites could not (real-wire failover, and
the hardening under live hostile injection). Everything is left uncommitted for the lead to integrate.
