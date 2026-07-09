# Module Reference

Configd is a Maven multi-module reactor. Dependencies point downward: everything rests on
`configd-common`, and the runnable services (`configd-server`, `configd-edge-node`) wire the
libraries together. The three test harnesses depend on the runtime modules, not the reverse.

## Runtime libraries

### configd-common

Shared value types and low-level utilities used across every module.

- `NodeId` -- node identifier (int-based value type)
- `Clock` / `HybridClock` / `HybridTimestamp` -- time abstractions and the Hybrid Logical Clock
- `ConfigScope` -- scope tier marker (selects a shard pool)
- `Storage` / `FileStorage` / `InMemoryStorage` -- durable/in-memory storage abstraction (WAL-backed)
- `Hkdf`, `IntegrityEnvelope`, `IntegrityException` -- HKDF derivation and the at-rest integrity envelope
- `BuggifyRuntime` -- fault-injection hooks for simulation

Dependencies: Agrona.

### configd-transport

The transport abstraction, wire framing, and the JDK-socket baseline.

- `RaftTransport` / `RaftTransportEndpoint` -- send/receive interface between nodes
- `TcpRaftTransport` -- the JDK TCP implementation (retained as the Netty baseline)
- `FrameCodec` / `BatchEncoder` / `RaftWireProtocol` -- length-prefixed framing and the wire protocol
- `MessageType` / `InboundMessage` / `MessageRouter` -- message typing and routing
- `TlsManager` / `TlsConfig` -- TLS setup for the transport

Dependencies: `configd-common`, Agrona.

### configd-netty

The Netty 4.2 implementation of the transport, used in production across all four network surfaces
(ADR-0043).

- `NettyTransport` -- transport selection (auto-default Epoll, then NIO; io_uring opt-in via
  `-Dconfigd.netty.transport=io_uring`)
- `NettyRaftTransport` -- the inter-node consensus wire on Netty
- `NettyConsensusFrameEncoder` -- in-pipeline consensus frame encoding

Dependencies: `configd-transport`, Netty 4.2 (`netty-transport`, `netty-buffer`, `netty-codec-http`,
`netty-handler`) plus the Epoll and io_uring native transports.

### configd-consensus-core

The Raft consensus implementation. Single-owner-thread per group, tick-driven.

- `RaftNode` -- the core state machine, driven by `tick()` and `handleMessage()`
- `RaftLog` -- append-only log with snapshot support
- `RaftConfig` -- immutable cluster configuration (node id, peers, timeouts, batch limits)
- `RaftRole` -- FOLLOWER, CANDIDATE, LEADER
- `RaftMessage` (sealed) -- `AppendEntries*`, `RequestVote*`, `InstallSnapshot*`, `TimeoutNowRequest`
- `StateMachine` -- interface for applying committed entries (`long apply(index, term, command)`)
- `RaftMetrics` -- the immutable snapshot published for safe cross-thread monitoring
- `CoalescedHeartbeat` / `HeartbeatCoalescer` / `CoalescingRaftTransport` -- per-tick heartbeat
  coalescing (traffic flat in group count)
- `DurableRaftState` -- persistent term/vote, integrity-enveloped

Implemented Raft features: leader election with PreVote, log replication with batching, CheckQuorum,
leadership transfer (TimeoutNow), joint-consensus reconfiguration, no-op commit on election. The
owner-thread concurrency rules are specified in
[`../architecture/raft-threading-contract.md`](../architecture/raft-threading-contract.md).

Dependencies: `configd-common`, `configd-transport`, Agrona, JCTools.

### configd-config-store

The control-plane MVCC store and the state machine that applies committed Raft entries.

- `VersionedConfigStore` -- the control-plane store (`put`, `delete`, `applyBatch`, `get`)
- `ConfigStateMachine` -- applies committed entries to the store
- `ConfigSnapshot` -- immutable point-in-time snapshot (HAMT + version + timestamp)
- `HamtMap<K,V>` -- persistent Hash Array Mapped Trie with structural sharing
- `ConfigDelta` / `DeltaComputer` / `ConfigMutation` (`Put` or `Delete`) -- minimal diffs between snapshots
- `VersionedValue` / `ReadResult` -- values and reads (a `NOT_FOUND` singleton for zero-allocation misses)
- `ConfigSigner` / `SigningKeyStore` / `VerifyKeyExporter` -- config signing and key handling
- `CommandCodec`, `ConfigValidator`, `Compactor`

Dependencies: `configd-common`, `configd-consensus-core`, Agrona.

### configd-edge-cache

The lock-free, edge-local read store -- the hot read path -- plus the edge client engine.

- `LocalConfigStore` -- volatile pointer to an immutable HAMT; `get()`, `applyDelta()`, `loadSnapshot()`
- `DeltaApplier` -- the single writer thread that builds and publishes the next snapshot
- `VersionCursor` -- opaque cursor for monotonic-read enforcement
- `StalenessTracker` -- CURRENT / STALE / DEGRADED / DISCONNECTED transitions
- `EdgeClientCore` / `EdgeConfigClient` -- the edge-side client engine and frontier tracking
- `StrongReadKeyClass` / `PrefixSubscription` -- the `secure/` strong-read class and prefix subscriptions
- `PoisonPillDetector` / `PoisonPillPolicy` -- serve-last-good on a bad value
- `BloomFilter`, `EdgeMetrics`

Dependencies: `configd-common`, `configd-config-store`, `configd-distribution-service`,
`configd-observability`.

### configd-replication-engine

The multi-Raft driver and the static-N sharding seam.

- `MultiRaftDriver` -- routes ticks, messages, and proposals to per-group `RaftNode`s
- `ShardMap` / `StaticShardMap` -- routing (`shardFor(scope, key)`), membership, and topology epoch (v1 static-N epoch is `1`)
- `OwnerExecutorPool` -- the per-group owner-executor pool (see the threading contract)
- `FlowController` -- admission / in-flight bounding
- `ReplicationPipeline`, `SnapshotTransfer`
- `CrossShardWriteGuard` / `CrossShardBatchException` -- the cross-shard write disclaimer

Dependencies: `configd-common`, `configd-consensus-core`, `configd-transport`, Agrona, JCTools.

### configd-distribution-service

The fan-out data plane: committed deltas out to subscribed edges.

- `PlumtreeNode` / `HyParViewOverlay` -- push-based fan-out over a peer-sampling overlay
- `FanOutBuffer` / `FanOutSessionCore` / `FanOutConnectionDriver` -- bounded per-session queues and drain
- `SlowConsumerGovernor` / `DemotionEvent` -- the per-identity slow-consumer ladder
- `CommitNotification` / `CommitNotificationSource` / `ReplaySource` -- the commit-notification boundary
  and catch-up replay
- the edge wire types (`EdgeFrame` / `EdgeFrameCodec` / `EdgeSnapshotCodec` / `FrameType` / `ErrorCode` /
  `WatchCursor`) now live in **`configd-wire`** (below); the fan-out service consumes them
- `WatchService` / `SubscriptionManager` / `FanOutSessionCore` -- watches, prefix subscriptions, and the
  per-shard aggregating coordinator that serves multi-shard (N>1) watches
- `FanOutConfig` / `FanOutMetrics`

Dependencies: `configd-common`, `configd-wire`, `configd-config-store`, `configd-transport`, Agrona, JCTools.

### configd-wire

The frozen edge/driver wire codec and shared value types, extracted so a client can depend on the wire
without the server. This is the byte-authority the driver-protocol RFC validates against (golden fixtures
`EdgeFrameGoldenBytes`).

- `EdgeFrameCodec` / `EdgeFrame` / `EdgeSnapshotCodec` / `FrameType` / `ErrorCode` -- the edge wire envelope,
  the `0x01`-`0x14` frame payloads (incl. the `0x13`/`0x14` auth frames), and the 13-code taxonomy
- `WatchCursor` -- the per-shard `(gid, S)` cursor vector (`INITIAL_TOPOLOGY_EPOCH = 1`, the v1 static-N epoch)
- `CommandCodec` / `ConfigMutation` / `ConfigDelta` / `ConfigSnapshot` / `VersionedValue` / `HamtMap` --
  shared store value types
- `CommitNotification` -- the commit-notification record

Dependencies: `configd-common`.

### configd-control-plane-api

The control-plane request services and the in-core security model.

- `ConfigWriteService` / `ConfigReadService` -- the write and read services
- `AclService` -- in-core RBAC (`{READ, LIST, WRITE, WATCH, ADMIN}`, union-of-ancestors, deny-precedence)
- `Role` / `Policy` / `PolicyRule` / `ConfigPolicy` / `PolicySerializer` / `PolicyParseException` --
  roles and policy-as-config under `_acl/`
- `AuthInterceptor` / `AuthenticatorChain` / `AuthenticatorFactory` -- the pluggable authenticator chain
  (No-Auth / HTTP Basic / Bearer / mTLS cert-DN), ServiceLoader-discovered, shared by both planes
- `AuditLog` -- keyed-HMAC audit chain (enabled when auth is enabled)
- `ReplayGuard` -- opt-in replay protection
- `RateLimiter` -- unconditional rate limiting
- `HealthService`, `AdminService`

Dependencies: `configd-common`, `configd-config-store`, `configd-consensus-core`,
`configd-replication-engine`, `configd-observability`.

### configd-authn-oidc

An OIDC/JWT bearer-token authenticator, discovered via `ServiceLoader` (`META-INF/services/...AuthenticatorFactory`).

- `OidcAuthenticator` / `OidcAuthenticatorFactory` -- validate a bearer token as an OIDC/JWT access token
- `OidcDiscovery` / `OidcIssuerValidator` / `OidcIssuerConfig` -- issuer/JWKS discovery and validation
- `ClaimsRoleMapper` -- map OIDC claims to Configd roles

Dependencies: `configd-common`.

### configd-kms-vault

An external HashiCorp **Vault Transit** KMS provider for off-host key custody, discovered via `ServiceLoader`
(`META-INF/services/...KmsProviderFactory`; select with `-Dconfigd.raft.encryption.kms.provider=vault-transit`).

- `VaultTransitKmsProvider` / `VaultKmsProviderFactory` -- seal/unseal the per-node keyring-custody secret
- `VaultTransitClient` / `VaultConfig` -- the Vault Transit HTTP client and configuration

Dependencies: `configd-common`.

### configd-client, configd-client-core, configd-client-http, configd-client-edge

The conforming **Java reference client** for the driver protocol (built from the RFC, validated against the
golden vectors). `configd-client` is the hybrid public API (Flow.Publisher streams + CompletableFuture
request/response + a blocking facade); `-core` is the transport-agnostic protocol core; `-http` and `-edge`
implement the control-plane and edge planes respectively.

### configd-conformance

The driver conformance suite (CI-wired) -- exercises a driver against both planes of a real cluster and the
golden wire vectors; the executable form of the RFC's per-section driver checklists.

### configd-observability

Metrics, SLO tracking, and the production invariant monitor.

- `ConfigdMetrics` -- the metric-series catalog (single source of truth for series names)
- `MetricsRegistry` / `PrometheusExporter` / `JvmMetrics` -- registry and Prometheus exposition
- `SloTracker` / `ProductionSloDefinitions` / `BurnRateAlertEvaluator` -- SLO and burn-rate alerting
- `InvariantMonitor` -- runs the consensus invariant checks in production (metric + log, not throw)
- `PropagationLivenessMonitor`, `SafeLog`

Dependencies: `configd-common`, Micrometer, HdrHistogram.

## Runnable services

### configd-server

The control-plane node. Wires consensus, sharding, the config store, the control-plane API, fan-out,
observability, and the Netty transports, and exposes the HTTP API. Main class
`io.configd.server.ConfigdServer`.

- `ConfigdServer` -- assembly + `main`; the sharding switch (`configd.raft.shardCount`) and owner pool
  (`configd.raft.ownerPoolSize`) live here
- `ServerConfig` -- CLI parsing (`--node-id`, `--data-dir`, `--peer-addresses`, `--api-port`,
  `--bind-port`, `--edge-port`, `--auth-token`, `--tls-*`, `--signing-key-file`, ...)
- `NettyHttpApiServer` / `AdminApiHandler` -- the HTTP admin/config surface
- `NettyFanOutServer` / `FanOutServer` / `FanOutEndpoint` -- the edge fan-out surface
- `RaftMessageCodec` / `RaftTransportAdapter` -- consensus wire encoding and per-frame `groupId` routing
- `AclServiceWatchAuthorizer` / `StrongReadPolicy` -- watch authorization and the strong-read policy

### configd-edge-node

The standalone edge reader process. Subscribes to the fan-out stream, applies deltas into a
`LocalConfigStore`, and serves reads. Main class `io.configd.edge.node.EdgeNodeMain`.

- `EdgeNodeMain` / `EdgeNodeConfig` -- assembly + `main` + CLI
- `EdgeStreamClient` -- the mTLS fan-out client
- `NettyEdgeHttpServer` / `EdgeHttpServer` / `EdgeReadHandler` -- the (plaintext, by design) edge-read
  HTTP surface

## Test and verification harnesses

These are not published as runtime artifacts.

- **configd-testkit** -- deterministic simulation (`RaftSimulation`, `SimulatedNetwork`, `AdversarialSim`,
  `MultiShardSim`, `EdgeFanOutSim`, `InMemoryRaftCluster`) plus the JMH allocation/throughput benchmarks
  and load-client mains. See [Testing](Testing.md).
- **configd-linz** -- the Porcupine linearizability harness (`PorcupineChecker`, `Cluster`, `Schedule`,
  `FaultInjector`, `Verdict`) that checks recorded histories for linearizability.
- **configd-jcstress** -- Java Memory Model concurrency tests (`RaftOwnerThreadGuardTest`,
  `RaftMonitorViewPublicationTest`, `RehomingDoubleOwnershipTest`, `HamtMapStructuralSharingTest`, ...)
  that stress the publication and ownership invariants.
