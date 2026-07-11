# ADR-0010: Netty for Data Plane, gRPC for Control Plane Transport

## Status
Superseded by ADR-0043

> **Note (2026-04-14):** At the time this note was first written, the actual implementation used
> plain Java TCP sockets with virtual threads (`TcpRaftTransport.java`) and TLS 1.3
> (`TlsManager.java`) for the data plane, and `com.sun.net.httpserver.HttpServer` (REST/JSON) for
> the control plane API - not Netty, not gRPC. [ADR-0043](adr-0043-netty-transport-platform.md)
> subsequently migrated all four transport surfaces (edge-read HTTP, admin API, fan-out streaming,
> and the inter-node consensus wire) onto Netty, so the data plane and control plane both run on
> Netty in production today (`NettyRaftTransport`, `NettyHttpApiServer`, `NettyFanOutServer`); the
> JDK socket implementations are kept only as a documented fast-revert. The custom framed wire
> format described below (`FrameCodec` and the edge frame codec) is unchanged by that migration -
> Netty replaced the socket layer, not the wire format. gRPC and Spring Boot were never adopted:
> the control plane API is still plain REST/JSON, not gRPC.

## Context
The system has two distinct transport needs: (1) high-throughput, low-latency data plane for Raft replication and edge fan-out, and (2) request-response control plane API for admin operations, writes, and reads.

## Decision
- **Data plane (Raft replication, Plumtree fan-out):** Custom framed protocol over Netty with mTLS. Direct ByteBuf manipulation for zero-copy where possible. Nagle disabled, manual batching with bounded delay (200us).
- **Control plane API:** gRPC over Netty (grpc-java). Spring Boot for admin APIs (REST). gRPC for programmatic client access and streaming subscriptions.
- **mTLS everywhere:** All node-to-node and client-to-node communication over mTLS. Certificate rotation via control plane config (self-bootstrapping).

### Data Plane Wire Format
```
+--------+--------+----------+---------+-------------+
| Length  | Type   | Group ID | Term    | Payload     |
| 4 bytes| 1 byte | 4 bytes  | 8 bytes | Variable    |
+--------+--------+----------+---------+-------------+
| uint32 | enum   | uint32   | uint64  | protobuf or |
|        |        |          |         | raw bytes   |
+--------+--------+----------+---------+-------------+

Message types:
  0x01: AppendEntries
  0x02: AppendEntriesResponse
  0x03: RequestVote
  0x04: RequestVoteResponse
  0x05: PreVote
  0x06: PreVoteResponse
  0x07: InstallSnapshot (chunked)
  0x08: PlumtreeEagerPush
  0x09: PlumtreeIHave
  0x0A: PlumtreePrune
  0x0B: PlumtreeGraft
  0x0C: HyParViewJoin
  0x0D: HyParViewShuffle
  0x0E: Heartbeat (coalesced across Raft groups)
```

### Batching Strategy
- Raft AppendEntries: batch pending entries with 200us bounded delay. If batch reaches 64 entries or 256 KB, send immediately.
- Plumtree EagerPush: batch delta events with 100us bounded delay. If batch reaches 32 events or 128 KB, send immediately.
- Nagle disabled (TCP_NODELAY). Manual batching provides better control.

## Influenced by
- **TiKV:** Custom Raft transport over gRPC with batched Raft I/O across groups sharing one RocksDB.
- **CockroachDB MultiRaft:** Coalesced heartbeats - one per node pair per tick regardless of shared range count.
- **Aeron:** Zero-copy messaging with Agrona buffers. Sub-microsecond latency.
- **Netty:** Industry standard for high-performance Java networking. Used by Cassandra, Elasticsearch, gRPC-java.

## Reasoning
### Why not gRPC for everything?
gRPC adds protobuf serialization/deserialization overhead on every message. For Raft AppendEntries on the hot path (potentially 10K+/s per group), this overhead is measurable. Custom framing with direct ByteBuf access avoids unnecessary copies.

### Why not raw TCP sockets?
Netty provides: non-blocking I/O multiplexing, connection management, SSL/TLS handling, flow control, and a mature ecosystem. Reinventing these is unjustified.

### Why gRPC for control plane?
gRPC provides: streaming (for subscriptions), protobuf schema evolution, deadline propagation, interceptor chains (for auth, tracing), and client library generation for multiple languages.

## Consequences
- **Positive:** Zero-copy data plane. gRPC ecosystem for control plane. mTLS for all traffic. Manual batching for optimal throughput/latency tradeoff.
- **Negative:** Two wire formats to maintain (custom framing + protobuf). Custom framing requires careful version management.
- **Risks and mitigations:** Custom protocol bugs mitigated by exhaustive property tests on frame encoding/decoding. Version incompatibility mitigated by protocol version field in connection handshake.

## Reviewers
- principal-distributed-systems-architect: yes
- senior-java-systems-engineer: yes
- security-engineer: yes
