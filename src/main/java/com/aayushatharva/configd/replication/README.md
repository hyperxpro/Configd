# Replication Protocol

Configd uses a pull-based hierarchical fan-out replication protocol that guarantees sequential consistency. This is the mechanism that distributes configuration changes from a single write point to every server in the global network.

## How It Works

```
  ROOT (seq=100)
    ^
    | pull: "give me entries after seq=95"
    | response: [96, 97, 98, 99, 100]
    |
  INTERMEDIATE (seq=95 -> applies -> seq=100)
    ^
    | pull: "give me entries after seq=90"
    | response: [91, 92, ..., 100]
    |
  LEAF PROXY (seq=90 -> applies -> seq=100)
```

Every node tracks its own `lastAcknowledgedSequence`. Periodically (every 1 second by default), it sends a pull request to its upstream with this sequence number. The upstream reads from its transaction log and responds with all entries after that point.

## Components

### ReplicationManager

Orchestrates replication for a node based on its role:

| Role + Mode | Behavior |
|-------------|----------|
| ROOT REPLICA | No upstream. Only serves replication to downstream nodes. |
| INTERMEDIATE REPLICA | Pulls from upstream AND serves to downstream. |
| LEAF REPLICA | Pulls from intermediate nodes, serves to local relays/proxies. |
| LEAF RELAY | Pulls from replicas within its data center. |
| LEAF PROXY | Pulls from relays. Uses sliding window for consistency. |

The manager creates both a `ReplicationPuller` (if the node has upstream) and a `ReplicationServer` (always, to serve downstream nodes).

### ReplicationPuller

Runs a scheduled task that periodically pulls from upstream:

1. **Build request**: Encode the last-known sequence number and batch size limit.
2. **Send via Netty**: Use the connection pool to get/reconnect a client to the upstream node.
3. **Parse response**: Deserialize the list of `TransactionLogEntry` from the response payload.
4. **Apply entries**: For each entry, call `store.put()` or `store.delete()` in sequence order.
5. **Update state**: Set the new last-acknowledged sequence and update the pull timestamp.
6. **Staleness detection**: If lag exceeds 30 seconds and no new entries arrived, rotate to the next upstream.

**Multiple upstreams**: The puller can be configured with multiple upstream addresses. It cycles through them on failure or staleness, providing automatic failover.

**Listeners**: Other components (like the reactive prefetcher and sliding window) register as `ReplicationListener` to be notified when entries are applied.

### ReplicationServer

Handles incoming `REPLICATION_PULL_REQUEST` messages from downstream nodes:

1. Parse the `afterSequence` and `limit` from the request payload.
2. Read entries from the local transaction log via `txLog.readAfter()`.
3. Serialize entries into the response: `[4 bytes count][for each: [4 bytes len][serialized entry]]`.
4. Track the downstream node's state for monitoring.

### ReplicationState

Tracks per-connection replication state:

- `lastAcknowledgedSequence` -- the highest sequence number the peer has confirmed
- `lastPullTimestamp` -- when the last pull occurred
- `healthy` -- whether the connection is considered healthy
- `lagMs()` -- how long since the last successful pull

### SlidingWindow

Addresses a consistency edge case for proxy nodes. A proxy can momentarily advance ahead of its upstream replica when the replica hasn't yet received the latest updates. The sliding window preserves all recent updates within a configurable time window (default: 60 seconds).

Properties:
- Entries within the window **cannot be evicted** from the cache
- Lookups check the window for the latest version of a key
- Entries are automatically evicted when they fall outside the window
- Backed by a `ConcurrentLinkedDeque` for lock-free concurrent access

## Wire Protocol

Replication uses the internal binary protocol over TCP (via Netty):

**Pull Request** (`REPLICATION_PULL_REQUEST`, type `0x01`):
```
[8 bytes] afterSequence (big-endian long)
[4 bytes] limit (big-endian int)
```

**Pull Response** (`REPLICATION_PULL_RESPONSE`, type `0x02`):
```
[4 bytes] entry count
[for each entry:]
  [4 bytes] entry byte length
  [N bytes] serialized TransactionLogEntry
```

## Consistency Guarantees

**Sequential consistency**: If key A was written at sequence 10 and key B at sequence 20, no node will ever see B without also having seen A. This is guaranteed because:

1. The root node assigns sequence numbers from a single atomic counter
2. The transaction log stores entries in sequence order
3. `readAfter` returns entries in order
4. Downstream nodes apply entries in the order received

**Eventual convergence**: All nodes will eventually reach the same state as the root, bounded by replication latency. In Cloudflare's deployment, median global propagation is under 5 seconds.

**No data loss on node failure**: As long as the transaction log on the upstream is intact, a recovered node simply resumes pulling from its last-known sequence number.

## Configuration

| Parameter | Default | Description |
|-----------|---------|-------------|
| replicationPullIntervalMs | 1000 | How often to pull from upstream |
| replicationBatchSize | 1000 | Max entries per pull request |
| stalenessThresholdMs | 30000 | When to rotate to next upstream |
| slidingWindowDurationMs | 60000 | Sliding window retention for proxies |
