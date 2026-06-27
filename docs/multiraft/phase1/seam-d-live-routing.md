# Seam D — live write/read routing + cross-shard guard

> Charter Step 4 / handoff §2.D. Wire the sim-verified ShardMap routing into the LIVE write and read
> paths; the cross-shard multi-key write guard active on the live path; shard-aware leader redirect.
> **N=1 byte-identical** (every resolution lands on group 0). Four-way. The N>1 boot guard still holds
> (removed in Seam G), so N>1 routing is exercised by tests, not production boot.

## 1. Writes — routing + cross-shard guard unified through `requireSingleShard`

- **SPI:** `RaftProposer.propose(ConfigScope scope, byte[] command)` → `propose(ConfigScope scope,
  List<String> keys, byte[] command)`. `ConfigWriteService.put/delete` pass `List.of(key)`.
- **Server proposer:** `int gid = CrossShardWriteGuard.requireSingleShard(shardMap, scope, keys)` — this
  ONE call is both the router (single key ⇒ `shardFor(scope,key)`) AND the DISCLAIM guard (multi-key
  spanning shards ⇒ `CrossShardBatchException`, caught synchronously on the HTTP thread before any Raft
  work ⇒ `ProposeCommitResult.CrossShardRejected` ⇒ `WriteResult.ValidationFailed` ⇒ HTTP 400). Then
  `driver.propose(gid, command)` on `driver.ownerExecutor(gid)`, registering the commit outcome on
  `driver.getGroup(gid)` (the captured group-0 executor/groupId are dropped — resolved per call).
- **Shard-aware redirect:** `LeaderHintSupplier.currentLeader()` → `currentLeader(ConfigScope scope,
  String key)`; the server resolves `driver.getGroup(shardFor(scope,key)).leaderId()`, so a `NotLeader`
  redirect points at the OWNING shard's leader (a keyless hint would loop forever at N>1).
  `mapOutcome` threads `(scope, key)`.
- **N=1:** `requireSingleShard` always returns 0; `getGroup(0)`; byte-identical. `CrossShardBatchException`
  is unreachable (one shard). The single-key live path is the only HTTP write path (BATCH stays
  codec-only — CM-033); the guard is LIVE + non-vacuous (a multi-key `keys` list spanning shards is
  rejected — tested at N>1).

## 2. Reads — shard-aware on EVERY live read path

The whole live read path is sharded (the first four-way pass caught two group-0-pinned read surfaces —
the stale `GET` and the read 503 hint — now fixed; DL-W-D-05):

- **Sharded reader** (`ConfigdServer.shardedConfigReader`, package-private + testable):
  `get(key)`/`get(key,minVersion)` resolve `shardFor(READ_SCOPE, key)` and read THAT shard's
  `configStore`; `getPrefix(prefix)` **scatter-gathers** across all shards and merges (prefix keys may
  hash to different shards); `currentVersion()` is the max across shards (best-effort; per-key version
  still from `ReadResult.version()`). `READ_SCOPE = GLOBAL` — every HTTP write is `ConfigScope.GLOBAL`,
  so reads route on the same scope (single-key linearizability preserved; documented coupling).
- **Stale `GET`** (`AdminApiHandler`) now routes through `readService.staleRead` → the sharded reader
  (was a direct group-0 `configStore.get` — the BLOCKER). At N=1 it resolves group 0 (byte-identical).
- **Linearizable / strong read:** `LeadershipConfirmer.confirmLeadership()` → `confirmLeadership(key)`;
  the ReadIndex protocol runs on the OWNING shard's node via its owner.
- **Read 503 `X-Leader-Hint`** is now keyed (`Function<String,NodeId>` through `NettyHttpApiServer`/
  `HttpApiServer`) — resolves the owning shard's leader (mirrors the write redirect). At N=1, group 0.
- **Still group-0 (Seam G, by design):** the fan-out/watch state-machine listeners + compactor (the
  N-way merge is Seam G) and the health-readiness leader check.

## 3. SPI deltas (configd-control-plane-api)

| SPI | before | after |
|---|---|---|
| `RaftProposer.propose` | `(scope, command)` | `(scope, List<String> keys, command)` |
| `ProposeCommitResult` | {Committed,NotLeader,Lost,Indeterminate,Overloaded} | + `CrossShardRejected(String reason)` |
| `LeaderHintSupplier.currentLeader` | `()` | `(ConfigScope scope, String key)` |
| `LeadershipConfirmer.confirmLeadership` | `()` | `(String key)` |

Callers updated mechanically: `ConfigWriteServiceTest`, `ConfigWriteServicePerPrincipalRateLimitTest`,
`RaftProposerCommitConfirmTest`, `MetricsWiringContractTest`, `RaftInboundMarshallingTest`,
`ConfigdServerTest`, `AbstractAdminApiServerContract`.

## 4. N=1 byte-identity re-proof
- `requireSingleShard(map, GLOBAL, [key])` at N=1 == `shardFor(GLOBAL,key)` == 0 ⇒ same group, same
  executor, same node, same propose/commit path, same outcome mapping.
- Reads resolve shard 0 ⇒ same store; `getPrefix` over one store ⇒ same map; confirmer on group 0's node.
- The full `configd-server` suite + `ConfigdServerTest` boot/restart + `NettyConsensusLivenessTest` stay
  green unchanged.

## 5. Tests (Seam D)
- `ShardedRoutingTest` (new): N>1 — a write routes to `shardFor(scope,key)`'s group and applies to THAT
  shard's store only; a read for key k reads shard k; `getPrefix` scatter-gathers across shards; the
  cross-shard guard rejects a multi-key `keys` list spanning shards (`CrossShardRejected`); the
  leader-hint resolves the owning shard's leader.
- Regression: the whole `configd-server` + `configd-control-plane-api` suites (N=1 byte-identity).

## 6. Red-team focus
- The redirect race (failover mid-retry) + the stale-map window on the live path; the groupId-trust
  boundary (unchanged from Seam C — reads/writes resolve gid locally from the key, never from a peer
  frame). Exactly-once on redirect: a retried write is a fresh propose (the prior may also commit) —
  last-writer-wins idempotent payload, unchanged from today's NotLeader retry semantics.
