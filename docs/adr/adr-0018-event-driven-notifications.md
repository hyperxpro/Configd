# ADR-0018: Event-Driven Notification System (Server-Side Push Streams)

## Status
Accepted

## Context
Edge nodes and application clients need to be notified of config changes with < 500ms p99 edge staleness. Three existing models have documented failure modes at scale: ZooKeeper's one-shot watches create a thundering herd during reconnection (200M watches = 20 GB RAM per ZOOKEEPER-1177; 10K notifications processed synchronously on leader thread block writes — gap-analysis.md section 2.2), etcd's streaming watches consume ~1,960 MB at 100K streams with per-subscriber serialization cost (gap-analysis.md section 3.2), and Consul's blocking queries require one HTTP connection per watched key with no multiplexing. The system must support 1M edge subscriptions with O(1) per-event fan-out cost and no re-registration requirement.

## Decision
We adopt **event-driven server-push via persistent gRPC bidirectional streams**, replacing watches entirely:

### Subscription Model
- **Prefix-based (primary):** Client subscribes to a key prefix (e.g., `/service/api/*`). Receives all create/update/delete events for matching keys. Single subscription covers an entire namespace subtree.
- **Full-store:** Available for regional relay nodes only. Receives all events across all keys.
- **Per-key:** Supported as a degenerate case of prefix subscription (prefix = exact key). Discouraged for large key counts due to per-subscription metadata.

### Event Stream Protocol
```
message ConfigEvent {
  uint64 sequence = 1;        // Monotonic within Raft group (ADR-0004)
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
    SnapshotChunk snapshot = 3;         // For catch-up (resume_from_seq too old)
  }
}
```

### No Re-Registration
- Subscription is established once and persists for the lifetime of the gRPC stream.
- No ZooKeeper-style one-shot semantics. No re-registration after each notification.
- Sequence-based resumption: on reconnection, client sends `resume_from_seq = last_applied_seq`. Server streams missed events from that point.

### Shared Immutable Event Buffers
- Committed events serialized **once** into a shared immutable byte buffer.
- Multiple subscriber streams reference the same buffer — no per-subscriber serialization.
- For 10K subscribers receiving the same event: 1 serialization + 10K buffer-reference writes, not 10K serializations.
- Buffer retained until all subscribers have consumed it (reference-counted, released when last subscriber advances past it).

### Fan-Out Architecture
- Control plane Raft leaders do NOT directly fan out to clients.
- **Distribution service nodes** (non-voting Raft followers or dedicated relay nodes) subscribe to the Raft log and fan out to edge nodes.
- Each distribution node handles up to 10K edge connections (gRPC streams).
- For 1M edge nodes: ~100 distribution nodes.
- Fan-out from distribution nodes to edges uses Plumtree (ADR-0011) for efficient O(N) propagation.

### Ordered Event Delivery
- Events within a Raft group are delivered in strict sequence order (monotonic sequence numbers per ADR-0004).
- Gap detection is trivial: `received_seq != last_applied_seq + 1` triggers catch-up.
- Cross-group events are delivered with HLC timestamps for approximate ordering.
- Exactly-once delivery semantics via sequence-based deduplication: if `received_seq <= last_applied_seq`, discard as duplicate.

### Progress Notifications
- When no events exist for a subscribed prefix for > 1 second, the server sends a `ProgressNotification` with the current sequence number.
- This serves as a liveness signal (connection is alive) and a freshness signal (subscriber knows it is caught up).
- Prevents the subscriber from entering STALE state during idle periods.

## Influenced by
- **etcd gRPC streaming watches:** Persistent, multiplexed, revision-based resumption. Progress notifications for liveness. Key improvement over ZooKeeper. But per-subscriber serialization cost at 100K streams is excessive (~1,960 MB per etcd benchmarks).
- **Chubby KeepAlive-piggybacked events (Burrows, OSDI 2006):** 93% of Chubby RPCs are KeepAlives. Events piggybacked on existing streams eliminate dedicated watch connections.
- **Kafka consumer groups:** Offset-based resumption. Snapshot + incremental catch-up for lagging consumers. The gold standard for ordered event consumption.
- **Cloudflare Quicksilver v2 (cache invalidation events):** Demonstrates the need for event ordering and gap detection. Their monotonic sequence number approach inspired ADR-0004.

## Reasoning

### Why not ZooKeeper-style watches?
ZooKeeper watches are **one-shot**: client must re-register after each notification. The event loss window between notification and re-registration is a documented correctness issue — a write can occur during the gap and be missed. Additionally:
- **Memory:** ~100 bytes/watch. 10K clients x 20K keys = 200M watches = 20 GB RAM (ZOOKEEPER-1177).
- **Thundering herd:** After a partition heals, all clients re-register watches simultaneously, saturating the write pipeline. Twitter 2018: session storms from reconnection.
- **Synchronous notification:** One write triggering 10K watches serially processes 10K notifications on the leader thread, blocking other writes.
- Persistent push streams eliminate all three failure modes: no re-registration, no thundering herd, no synchronous fan-out on leader.

### Why not Consul blocking queries?
Consul uses long-poll HTTP requests — one connection per watched key. For 10K keys per client: 10K HTTP connections. No multiplexing. Each poll is a new request with full HTTP overhead. At 1M clients watching 100 keys each: 100M connections globally. Not viable.

### Why not polling?
Polling with interval T introduces average delay of T/2. For 500ms target: need < 500ms polling interval. At 1M edge nodes with 500ms interval: 2M requests/s to the control plane. This is pure waste when no changes exist. Push-based delivery sends messages only when changes occur — zero bandwidth cost during idle periods.

### Why shared immutable buffers instead of per-subscriber serialization?
etcd benchmarks show 100K streams consuming ~1,960 MB. The dominant cost is per-subscriber serialization: each event is serialized independently for each subscriber's gRPC stream. With shared immutable buffers, a single 1 KB event serving 10K subscribers costs: 1 KB serialization + 10K x 8 bytes (buffer reference) = ~81 KB. Per-subscriber serialization: 10K x 1 KB = 10 MB. **123x reduction.**

### Why prefix subscriptions instead of exact-key subscriptions?
Config keys are hierarchically organized (`/service/api/rate_limit`, `/service/api/timeout`, `/service/api/retry_policy`). A client interested in all API service config creates 1 prefix subscription (`/service/api/*`) instead of N exact-key subscriptions. At 1000 keys per prefix: 1000x reduction in subscription count. Fewer subscriptions = less memory, less metadata, faster subscription matching.

## Rejected Alternatives
- **ZooKeeper one-shot watches:** Event loss window between notification and re-registration. 200M watches = 20 GB RAM. Thundering herd on reconnection. Synchronous leader-thread notification blocks writes.
- **Consul blocking queries (long-poll):** One HTTP connection per watched key. No multiplexing. 100M connections at 1M clients x 100 keys. Full HTTP overhead per poll.
- **Polling-based updates:** Average delay = half the polling interval. 2M requests/s at 1M nodes with 500ms interval. Wastes bandwidth when no changes exist. Cannot meet < 500ms p99 staleness target without sub-500ms polling, which further amplifies request load.
- **Webhook-style HTTP callbacks:** Requires edge nodes to expose HTTP endpoints. Retry semantics are complex (at-least-once, idempotency). No ordered delivery guarantee. Each callback is an independent HTTP request — no multiplexing, no shared serialization.
- **Server-Sent Events (SSE):** Unidirectional (server-to-client only). No bidirectional communication for subscription management. No built-in reconnection with offset resumption. Less efficient than gRPC binary framing for high-throughput scenarios.

## Consequences
- **Positive:** Ordered delivery with sequence-based gap detection. Exactly-once semantics via dedup. No re-registration — subscription persists for stream lifetime. No thundering herd. Shared immutable buffers provide 123x memory reduction vs per-subscriber serialization. Resume from any sequence number after reconnection. Progress notifications prevent false staleness during idle periods.
- **Negative:** Long-lived gRPC streams require connection management, keepalives, and graceful reconnection logic. Distribution nodes are stateful (maintain subscriber connections and their last-delivered sequence). Stream lifecycle management adds complexity (backpressure, slow consumer detection, disconnection policy).
- **Risks and mitigations:** Stream starvation (etcd issue #17529: 722 unsynced slow watchers in production) mitigated by per-subscriber output buffers with independent backpressure and slow consumer disconnection (architecture.md section 7). Connection storms after distribution node restart mitigated by exponential backoff with jitter (base: 100ms, max: 30s, jitter: +/- 50%) on client reconnect. Subscription prefix matching overhead mitigated by radix trie index over active subscriptions — O(key_length) matching, not O(subscription_count).

## Reviewers
- principal-distributed-systems-architect: ✅
- distributed-data-plane-engineer: ✅
- chaos-engineer: ✅
