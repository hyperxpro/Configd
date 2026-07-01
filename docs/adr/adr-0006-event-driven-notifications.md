# ADR-0006: Event-Driven Push Notifications (Reject Watches and Polling)

## Status
Accepted

## Context
Edge nodes and application clients need to be notified of config changes. Three models exist: one-shot watches (ZooKeeper), streaming watches (etcd), and polling/blocking queries (Consul). Each has documented failure modes at scale.

## Decision
We adopt **event-driven server-push via persistent gRPC bidirectional streams**. Clients subscribe to key prefixes and receive ordered event streams. No re-registration. No polling. Fan-out handled by non-leader distribution nodes.

### Subscription Model
- **Prefix-based:** Client subscribes to a key prefix (e.g., `/service/api/*`). Receives all create/update/delete events for matching keys.
- **Full-store:** Available for regional replica nodes only. Receives all events.
- **Per-key:** Available but discouraged (use prefix with specific key as degenerate case).

### Event Stream Protocol
```
message ConfigEvent {
  uint64 sequence = 1;        // Monotonic within Raft group
  int64  hlc_timestamp = 2;   // HLC for cross-group ordering
  string key = 3;
  bytes  value = 4;           // Empty for DELETE
  EventType type = 5;         // PUT, DELETE
  string raft_group_id = 6;
}

message SubscribeRequest {
  string prefix = 1;
  uint64 resume_from_seq = 2; // 0 for "from latest"
}

message SubscribeResponse {
  oneof payload {
    ConfigEvent event = 1;
    ProgressNotification progress = 2;  // Liveness signal when idle
    SnapshotChunk snapshot = 3;         // For catch-up
  }
}
```

### Fan-out Architecture
- Control plane Raft leaders do NOT directly fan out to edge nodes.
- **Distribution service nodes** (non-voting Raft followers or dedicated relay nodes) subscribe to the Raft log and fan out to edge nodes.
- Each distribution node handles up to 10K edge connections.
- For 1M edge nodes: ~100 distribution nodes.
- Events are serialized once into a shared immutable buffer, then written to multiple gRPC streams — avoiding per-subscriber serialization cost.

## Influenced by
- **etcd gRPC streaming watches:** Persistent, multiplexed, revision-based resumption. Progress notifications for liveness. Cost: ~350 bytes/watching instance.
- **Chubby KeepAlive-piggybacked events:** Eliminates separate watch connections. 93% of RPCs are KeepAlives.
- **Kafka consumer groups:** Offset-based resumption. Snapshot + incremental catch-up for slow consumers.

## Reasoning
### Why not ZooKeeper-style watches?
- One-shot: client must re-register after each notification. Event loss window between trigger and re-registration.
- Memory: ~100 bytes/watch. 10K clients × 20K keys = 200M watches = 20 GB RAM (ZOOKEEPER-1177).
- Thundering herd: mass re-registration after reconnection saturates write pipeline (Twitter 2018 session storms).

### Why not Consul blocking queries?
- One HTTP connection per watched key. 10K keys = 10K connections per client.
- No multiplexing. No event streaming.
- Each poll is a new request with full HTTP overhead.

### Why not polling?
- Propagation delay = polling interval + processing time. For 500ms interval: 250ms average delay + jitter.
- O(N) requests per interval from N edge nodes. At 1M nodes with 1s interval: 1M requests/s to control plane.
- Wastes bandwidth when no changes exist.

## Consequences
- **Positive:** Ordered delivery. Exact-once semantics (sequence-based dedup). No re-registration. No thundering herd. Resume from any sequence number. Shared serialization buffer for efficient fan-out.
- **Negative:** Long-lived gRPC streams require connection management, keepalives, and graceful reconnection. Distribution nodes are stateful (maintain subscriber connections).
- **Risks and mitigations:** Stream starvation (etcd #17529) mitigated by per-subscriber output buffers with independent backpressure. Connection storms after distribution node restart mitigated by exponential backoff with jitter on client reconnect.

## Reviewers
- principal-distributed-systems-architect: ✅
- distributed-data-plane-engineer: ✅
- chaos-engineer: ✅
