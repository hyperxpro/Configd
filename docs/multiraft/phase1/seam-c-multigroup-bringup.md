# Seam C — N-group consensus bringup (the `buildRaftGroup` factoring)

> Charter Step 2 / handoff §2.C. Generalize the single-group consensus setup in `ConfigdServer.start()`
> to N groups over `shardMap.shardIds()` via an extracted `buildRaftGroup(gid, …)` returning a
> `RaftGroupRuntime`. **N=1 byte-identical** (the highest bar). Per-group outbound adapters (the DL-P1-06
> outbound half deferred from Seam B). mTLS + client-auth proven by negative test (§3). Four-way.

## 1. What Seam C does — and does NOT do

**Does:** factor the intricate per-group consensus bringup (storage → log → store → state machine →
node → per-group outbound transport → group-commit) into ONE helper used for ALL groups; loop it over
`shardMap.shardIds()`; register each group on the driver; bind each group's owner thread; bind each
group's coalescer. The per-owner tick loop (Phase 0 M1) and the inbound demux (Seam B) are ALREADY
group-parametric, so they need no change. Decouple the captured-constant-0 assumptions in the bringup
path so N groups are genuinely independent.

**Does NOT:** route writes/reads by shard (Seam D — the proposer/reader stay bound to the primary group
0), make observability per-shard (Seam E), bump the wire (Seam F), or N-way the fan-out (Seam G). The
**temporary N>1 boot guard stays** (removed only in Seam G when N>1 is correct end-to-end); so in
production `start()` still runs the loop exactly once (group 0). N>1 bringup is proven by a component
test that drives the real `buildRaftGroup` × N on the real driver + owner pool.

## 2. The singletons that stay bound to the PRIMARY group (group 0) in Seam C

These reference group-0's objects today and remain bound to the primary `RaftGroupRuntime` after the
refactor (Seam D/E/G re-point them). At N=1 group 0 is the only group ⇒ byte-identical:
- write proposer + write-service `leaderId` (Seam D shards the proposer);
- linearizable-read dispatch + `ConfigReader` + strong-read `leaderId` (Seam D shards reads);
- health readiness `leaderId`; HTTP API `configStore`/`leaderId`; audit-log;
- fan-out + watch state-machine listeners; snapshot replay source; the `stateMachine()` accessor;
- the `pendingApplyEntries` H-3 gauge read (Seam E makes it per-shard).

## 3. `RaftGroupRuntime` — the per-group object bundle

```
record RaftGroupRuntime(
    int groupId,
    Storage storage,                         // node-level instance at N=1; per-shard dir at N>1
    RaftLog raftLog,
    VersionedConfigStore configStore,
    ConfigStateMachine stateMachine,
    RaftNode raftNode,
    RaftTransportAdapter adapter,            // null in no-peer (single-node/test) mode
    CoalescingRaftTransport coalescingTransport) // null in no-peer mode
```

## 4. `buildRaftGroup` — the one path for every group

Inputs are the SHARED node-level deps (clock, signer, raftIntegrity, checkers, configdMetrics, the
shared `RaftConfig`, nodeId, the node-level `tcpTransport` (nullable), group-commit tunables, the
driver, the node-level `storage`, `shardCount`, `dataDir`). Body:

1. **Per-group storage** — `shardCount == 1 ? storage : Storage.file(dataDir.resolve("shard-"+gid))`.
   At N=1 the group reuses the **node-level `storage` instance** (the same one the AuditLog uses), so
   the RaftLog WAL/snapshot bytes and their on-disk paths are byte-identical to today. `AuditLog` /
   signing key stay at the node level (NOT per shard) — handoff §2.C.
2. `RaftLog(groupStorage, raftIntegrity)` — the keyed at-rest envelope is shared (node key).
3. `VersionedConfigStore(new ConfigSnapshot(HamtMap.empty(), 0, clock.now()), clock)`.
4. `ConfigStateMachine(configStore, clock, smChecker, signer, new ServerStateMachineMetrics(metrics))`.
5. **Per-group RandomGenerator** — seed `nodeId.id()*31 + gid*GID_STRIDE + nanoTime()`. At gid=0 the
   `gid*GID_STRIDE` term is 0 ⇒ the seed FORMULA is identical to today's group-0 seed. Distinct per
   group so (a) each node touches only its own RNG on its own owner thread (no cross-group RNG data
   race at N>1) and (b) election timeouts are STAGGERED across shards (ADR D-B correlated-election-
   storm mitigation). RNG affects only election *timing* jitter (already `nanoTime`-non-deterministic);
   the WAL/wire/snapshot FORMAT — the byte-identity bar — is unaffected. HyParView gets its own RNG in
   `start()` (it previously shared the group-0 RaftNode RNG instance; splitting changes no format).
6. **Per-group transport** — `tcpTransport != null` ⇒ `adapter = new RaftTransportAdapter(tcpTransport,
   gid)` (stamps ITS gid outbound — the DL-P1-06 outbound half), wrapped in `new
   CoalescingRaftTransport(adapter, gid)`; else a no-op `RaftTransport` (single-node/test). At N=1 group
   0 stamps gid 0 ⇒ byte-identical.
7. `RaftNode(raftConfig, raftLog, transport, stateMachine, groupRandom, groupStorage, raftChecker,
   raftIntegrity)`.
8. **Group commit** — `raftNode.setGroupCommit((flush, delay) -> driver.dispatchFlush(gid, flush,
   delay), maxBatch, linger)` when enabled. At gid 0 identical to today.

## 5. `start()` wiring order (preserves every existing happens-before)

```
shardCount = resolveShardCount(dataDir)            // N>1 still REFUSED here (guard stays)
shardMap   = new StaticShardMap(shardCount)
storage    = Storage.file(dataDir)                 // node-level (AuditLog + N=1 RaftLog share it)
… signer / raftIntegrity / auditLogKey / metricsRegistry / invariantMonitor / checkers / configdMetrics …
raftConfig = new RaftConfig(nodeId, peers, …)      // SHARED across groups (same node+peers)
… TLS … node-level tcpTransport (NettyRaftTransport, mTLS+client-auth when TLS on) …
driver = new MultiRaftDriver(nodeId, clock)
ownerPool = new OwnerExecutorPool(ownerPoolSize); driver.setOwnerPool(ownerPool)
if (tcpTransport != null) driver.enableHeartbeatCoalescing(<node-level drain: frame each gid via tcp>)
List<RaftGroupRuntime> runtimes = shardMap.shardIds().mapToObj(gid -> {
    RaftGroupRuntime rt = buildRaftGroup(gid, …)
    driver.addGroup(gid, rt.raftNode())
    if (rt.coalescingTransport() != null)
        rt.coalescingTransport().bindCoalescer(() -> driver.heartbeatCoalescer(driver.currentOwnerIndex(gid)))
    driver.ownerExecutor(gid).execute(rt.raftNode()::bindOwnerThread)   // H-6: FIRST task on the owner
    return rt
}).toList()
primary = runtimes.get(0)                            // group 0 — the Seam-C singleton home
if (tcpTransport != null) { register raftDemuxInboundHandler ONCE; tcpTransport.start() }  // after all binds
… build singletons against `primary` … schedule per-owner ticks (already group-parametric) …
```

Ordering invariants preserved (the four reviewers must re-confirm):
- `setOwnerPool` before `enableHeartbeatCoalescing` before any `heartbeatCoalescer(idx)` (in bindCoalescer).
- Every group's `bindOwnerThread` is the FIRST task on its owner, submitted BEFORE the inbound demux is
  published (`tcpTransport.start()`) and BEFORE ticks are scheduled — single-thread FIFO then orders
  every later tick/handleMessage/propose AFTER the bind, so `assertOwnerThread()` never spuriously trips.
- The inbound demux is registered EXACTLY ONCE on the shared transport (it routes every gid); per-group
  adapters are OUTBOUND-only. (`registerInboundHandler` delegates to the shared `transport.registerHandler`
  which REPLACES, so registering once is both necessary and sufficient.)

## 6. mTLS + client-auth (§3, non-negotiable)

The node-level `NettyRaftTransport` already sets `engine.setNeedClientAuth(true)` (server handler) +
`EndpointIdentificationAlgorithm=HTTPS` (client handler) whenever TLS is enabled — peers are mutually
authenticated, and a frame's groupId is therefore only ever demuxed for an AUTHENTICATED peer. Seam C
adds the **negative proof**: a client presenting no / an untrusted cert cannot complete the handshake,
so its frames never reach the demux (no group sees them). Proven on the real Netty wire.

## 7. N=1 byte-identity — the re-proof after Seam C

1. The group-0 storage is the SAME node-level `Storage.file(dataDir)` instance ⇒ identical WAL/snapshot
   bytes + paths.
2. The group-0 outbound adapter stamps gid 0; coalescing wraps gid 0 ⇒ identical frames.
3. Group commit dispatches on group 0 ⇒ identical flush behaviour.
4. All singletons bind to the primary (group 0) ⇒ identical write/read/fan-out/health/http/audit paths.
5. `NettyConsensusLivenessTest` (real 3-node wire, group 0) + the marshalling/owner-net/loopback/
   demux/proposer suites stay green unchanged.

## 8. Tests (Seam C)

- `MultiGroupBringupTest` (new) — drives the REAL `buildRaftGroup` for N∈{1,3}: N single-node groups on
  one driver + owner pool, each self-elects LEADER on its owner, each commits + applies a PUT to its OWN
  `configStore` (per-shard linearizability + isolation: a write to group k never appears in group j),
  the per-group outbound adapter stamps the correct gid, and N=1 reproduces today's single objects.
- Negative mTLS: an unauthenticated/wrong-cert peer's frames are rejected before the demux (real wire).
- Regression (unchanged, must stay green): `ConfigdServerTest`, `NettyConsensusLivenessTest`,
  `RaftInboundDemuxTest`, `RaftProposerCommitConfirmTest`, `RaftInboundMarshallingTest`,
  `RaftTransportAdapterLoopbackTest`, `OwnerNetCatchesOffOwnerInboundTest`, `ShardCountConfigTest`.
