# ADR-0019: Consistency Model — Linearizable Writes with Bounded-Staleness Edge Reads

## Status
Accepted

## Context
The system must provide strong consistency guarantees for config writes (correctness) while achieving < 1ms p99 edge reads (performance). These are inherently conflicting requirements: linearizable reads require leader contact (adding network RTT), while sub-millisecond reads require local serving without coordination. Cloudflare Quicksilver provides only sequential consistency per-node with no cross-node read-your-writes, no causal consistency, and no linearizable operations (gap-analysis.md section 1.2). etcd provides linearizable reads via ReadIndex but at the cost of leader RTT on every read. The system targets: < 150ms write commit p99, < 500ms edge staleness p99, < 1ms edge read p99, 10K/s sustained writes.

## Decision
We adopt a **split consistency model**: linearizable writes via Raft consensus in the control plane, with bounded-staleness reads at the edge and explicit monotonic-read guarantees per client session.

### Linearizable Writes
All write operations to any Raft group (global or regional) are linearizable:
- `PUT(key, value)` — config creation/update.
- `DELETE(key)` — config removal.
- `BATCH(mutations[])` — atomic batch within a single Raft group.
- Linearizability is provided by Raft consensus: single leader serializes all writes. If write W1 completes before write W2 begins (real time), W1's sequence number < W2's sequence number.

### Per-Key Total Ordering via Global Monotonic Sequence Numbers
- Each Raft group maintains an independent, monotonically increasing 64-bit sequence counter (ADR-0004).
- Every committed log entry receives `seq = previous_seq + 1` (gap-free within a group).
- Each entry carries an HLC timestamp for cross-group approximate ordering.
- Per-key total order: all writes to the same key are totally ordered because they traverse the same Raft group and the same log.

### Bounded Staleness at Edge
Edge nodes receive updates via Plumtree push (ADR-0011) and serve reads from local immutable HAMT snapshots (ADR-0005).

| Percentile | Maximum Staleness | Mechanism |
|---|---|---|
| p99 | 500ms | Plumtree push + 2-3 hop propagation |
| p999 | 1s | Hedged requests + catch-up protocol |
| p9999 | 2s | Snapshot re-bootstrap for lagging nodes |

Edge node freshness tracked by `StalenessTracker`:
- `CURRENT` (< 500ms): Normal operation. Full performance.
- `STALE` (500ms - 5s): `X-Configd-Stale: true` header on responses. Metric increment.
- `DEGRADED` (5s - 30s): Alert emitted. Node reports unhealthy to load balancer. Continue serving stale data.
- `DISCONNECTED` (> 30s): Trigger re-bootstrap via snapshot catch-up from regional relay.

### Monotonic-Read Guarantee per Client Session
Once a client reads a value at version V, all subsequent reads return values at version >= V:

1. Every read response includes a `VersionCursor(sequence, hlc_timestamp)`.
2. Client stores the cursor and passes it on subsequent reads.
3. Edge node receiving a read with cursor checks: `local_version >= cursor.version`.
   - If yes: serve, return updated cursor.
   - If no: block for up to `monotonic_read_timeout` (default: 100ms).
   - If timeout: serve stale with `X-Configd-Stale: true` header.

This guarantee holds across edge node failover: client presents cursor to new node, which blocks briefly or serves stale-with-notification.

### Read-Your-Writes (Same Region)
After a client writes key K and receives commit sequence S:
1. Client's `VersionCursor.version = S`.
2. Subsequent reads from any edge node in the same region return version >= S.
3. Intra-region Plumtree propagation: < 50ms typical.
4. If edge node hasn't received version S: blocks for `ryw_timeout` (100ms), then falls back to stale-allowed.

### Linearizable Reads (Control Plane, On-Demand)
For applications requiring read linearizability:
1. Client sends read to control plane with linearizable flag.
2. Leader records current commit index, sends heartbeat to confirm leadership (ReadIndex, Raft section 6.4).
3. Once confirmed, waits for local state machine to apply through recorded index.
4. Returns result — guaranteed to reflect all writes committed before the read began.
5. Cost: 1 additional RTT to leader for heartbeat confirmation.

### Cross-Group Ordering: NOT Guaranteed
Writes to keys in different Raft groups have independent sequence numbers. Cross-group ordering is approximate via HLC timestamps. Applications needing cross-group causality must:
1. Use HLC timestamps and accept bounded uncertainty (max clock skew: 500ms), OR
2. Route both keys to the same Raft group by assigning the same scope.

This is explicitly disclaimed in the consistency contract (consistency-contract.md section 5).

## Influenced by
- **CockroachDB Bounded Staleness Reads:** `AS OF SYSTEM TIME with_max_staleness('10s')` enables follower reads without leader contact. Closed timestamp mechanism provides the staleness bound.
- **Spanner TrueTime:** Externally consistent reads via hardware clocks. We achieve similar guarantees via HLC + closed timestamps without TrueTime hardware.
- **Cloudflare Quicksilver (anti-pattern):** Sequential consistency per-node, but no cross-node guarantees. No linearizable operations. No read-your-writes. No conflict resolution. Demonstrates that weak consistency in config distribution is a liability — the December 2025 outage involved config propagation with no ability to verify global consistency.
- **Amazon DynamoDB (consistency spectrum):** Offers eventual, strong, and transactional reads. Demonstrates that a single system can support multiple consistency levels for different access patterns.

## Reasoning

### Why linearizable writes, not eventual consistency?
Config distribution is a **single-source-of-truth** system: the control plane is the authoritative writer, and all edges must converge to the same state. Eventual consistency permits temporary divergence, which in a config system means different servers running different configurations simultaneously. The Cloudflare December 2025 outage demonstrates the risk: a bad config propagated inconsistently across nodes. Linearizable writes ensure that once a config change is committed, it has a definitive version and ordering that all observers will eventually see.

### Why bounded staleness at edge, not linearizable reads everywhere?
Linearizable reads require leader contact: 1 RTT to confirm leadership via ReadIndex. For cross-region reads: 68-220ms. For 1M edge nodes each requiring leader contact: 1M RPCs/read to the control plane — impossible to scale. Bounded staleness (< 500ms p99) provides a quantifiable freshness guarantee while enabling local serving from immutable HAMT at < 1ms p99. The 500ms staleness window is acceptable for config reads because config changes are operational (feature flags, rate limits, routing rules) where sub-second propagation is sufficient.

### Why monotonic reads per session?
Without monotonic reads, a client that fails over from edge node A to edge node B could read an older version of a key (if B is behind A in Plumtree propagation). This creates a "time travel" experience: a feature flag appears to revert from enabled to disabled and back. Monotonic reads via version cursors prevent this: the client's cursor enforces that version never decreases, even across edge failover.

### Why per-group sequence numbers, not global sequence numbers?
A single global sequence number across all Raft groups would require cross-group coordination on every write — reintroducing the single-writer bottleneck. Per-group sequence numbers are independent, enabling parallel writes across groups. Cross-group ordering is provided approximately by HLC timestamps, which is sufficient for config distribution (cross-group causality is rare and can be handled by HLC uncertainty bounds).

### Formal invariants
```
INV-L1: Linearizability — if op1 completes before op2 begins (real time) in same Raft group,
         op1's effect is visible to op2.
INV-S1: Staleness bound — P(staleness > 500ms) < 0.01 under normal conditions.
INV-M1: Monotonic reads — for client c, all reads r1 before r2:
         version(response(r2)) >= version(response(r1)).
INV-V1: Per-key total order — for key k, writes w1 before w2: seq(w1) < seq(w2).
INV-W1: Sequence gap-free — consecutive entries e_i, e_{i+1}: seq(e_{i+1}) = seq(e_i) + 1.
```

All invariants verified by TLA+ specification (ADR-0007), runtime assertions in production, and property-based tests in the test suite (consistency-contract.md section 7).

## Rejected Alternatives
- **Linearizable reads everywhere:** Requires leader RTT on every read. At 1M edge nodes: 1M RPCs/read to control plane. Violates < 1ms p99 edge read target by 3-5 orders of magnitude (68-220ms leader RTT). Not scalable.
- **Pure eventual consistency:** No ordering guarantees. No gap detection. No monotonic reads. Config changes could be applied out of order at different edges, creating inconsistent state. Cloudflare's eventual model contributed to outage propagation patterns.
- **Causal consistency (vector clocks):** Requires tracking causal dependencies across all writers. For a single-writer-per-key model (control plane is sole writer), causal consistency degenerates to linearizability — all the overhead, no additional benefit. Vector clocks at 10^9 keys would consume 8+ GB of version metadata per edge node (ADR-0004 rejected alternatives analysis).
- **Sequential consistency (Quicksilver model):** Per-node sequential consistency means two nodes in the same DC can serve different versions simultaneously. No cross-node guarantees. No read-your-writes across nodes. Insufficient for operational correctness when config changes affect multiple coordinating services.
- **Strong serializable (Spanner model):** Requires TrueTime or equivalent hardware for external consistency. Hardware dependency unacceptable for an OSS system targeting commodity infrastructure. Over-specified for config distribution — config writes are not transactions needing serializable isolation.

## Consequences
- **Positive:** Config writes are linearizable — strong ordering guarantee for control plane operations. Edge reads at < 1ms p99 via local HAMT serving. Bounded staleness (< 500ms p99) provides quantifiable freshness. Monotonic reads prevent "time travel" across edge failover. Read-your-writes within same region. Linearizable reads available on-demand via control plane ReadIndex.
- **Negative:** Edge reads are not linearizable — applications requiring absolute freshness must use control plane ReadIndex (higher latency). Cross-group ordering is approximate (HLC-based), not total. The staleness window (up to 500ms p99) means an edge node may briefly serve stale config after a control plane write commits.
- **Risks and mitigations:** Staleness exceeding bounds during network partition mitigated by StalenessTracker state machine (CURRENT to STALE to DEGRADED to DISCONNECTED) with automatic re-bootstrap. Monotonic read cursor stale after long disconnection mitigated by cursor TTL — cursors older than `max_cursor_age` (default: 1 hour) are treated as expired, and the client receives a fresh snapshot. Cross-group ordering ambiguity mitigated by explicit consistency contract (consistency-contract.md) that disclaims cross-group total order and recommends same-scope routing for causally related keys.

## Reviewers
- principal-distributed-systems-architect: ✅
- distributed-systems-researcher: ✅
- formal-methods-engineer: ✅
