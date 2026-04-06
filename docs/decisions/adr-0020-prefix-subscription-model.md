# ADR-0020: Prefix-Based Subscription Model for Edge Nodes

## Status
Accepted

## Context
At 10x current Cloudflare scale, the keyspace reaches 50 billion KV pairs / 16 TB (gap-analysis.md section 1.3). Replicating the full keyspace to every edge node is infeasible: 16 TB per edge at 1M edge nodes = 16 exabytes of aggregate storage. Quicksilver's negative lookup problem is already significant — negative lookups are 10x more frequent than positive, and Bloom filters for the full keyspace would require 6 GB/instance (gap-analysis.md section 1.3). Edge nodes must receive only the data they need. The system targets < 500ms p99 edge staleness, < 1ms p99 edge reads, and must scale to 10^9 keys in the control plane with edge nodes holding only their working set.

## Decision
We adopt a **prefix-based subscription model** where edge nodes subscribe to specific key prefixes, not the full keyspace. Regional replicas hold the full dataset; edge nodes hold only subscribed prefixes.

### Subscription Mechanism
- Edge node declares its subscriptions at connection time: a set of key prefixes (e.g., `/service/api/*`, `/global/routing/*`, `/us-east/features/*`).
- Distribution service (ADR-0011) filters events: only events matching at least one subscribed prefix are forwarded to the edge node.
- Subscriptions can be updated dynamically (add/remove prefixes) without reconnection — sent as `UpdateSubscription` message on the existing gRPC stream.
- Default subscription: none. Edge nodes must explicitly subscribe. No implicit full-keyspace subscription.

### Subscription Matching
- **Prefix matching:** A subscription to `/service/api/*` matches all keys starting with `/service/api/` (hierarchical, not glob).
- **Exact match:** A subscription to `/service/api/rate_limit` (no wildcard) matches only that exact key.
- **Multi-level wildcard:** A subscription to `/service/**` matches all keys under `/service/` at any depth.
- Matching implemented via radix trie on the distribution node. Event-to-subscription matching: O(key_length), not O(subscription_count). At 100K subscriptions and 1 KB key: ~1us matching time, not 100ms linear scan.

### Tiered Storage Model

| Tier | Dataset | Storage | Purpose |
|---|---|---|---|
| **Control Plane (Raft)** | Full keyspace (10^9 keys) | HAMT + WAL per shard | Source of truth. All writes. |
| **Regional Replica** | Full regional dataset + stale global | HAMT + WAL | Full dataset for region. Catch-up source for edges. |
| **Distribution Node** | Full event stream | Event buffer (bounded, ~1 GB) | Fan-out to edges. No persistent storage needed — events replayed from Raft WAL on restart. |
| **Edge Node** | Subscribed prefixes only | HAMT (in-memory) | Working set. < 1ms reads. |

### Edge Working Set Sizing
- Typical edge node subscribes to 10-50 prefixes covering 10K-100K keys.
- At 1 KB average value: 10 MB - 100 MB per edge node.
- At 10 KB average value: 100 MB - 1 GB per edge node.
- Versus full keyspace: 10^9 keys x 1 KB = 1 TB. Prefix subscription reduces edge storage by 10,000x-100,000x.

### Bootstrap Protocol
1. Edge node connects to distribution node and declares subscriptions.
2. Distribution node checks if edge's `resume_from_seq` is within the event buffer window.
   - If yes: stream missed events from buffer.
   - If no (cold start or long disconnection): send prefix-filtered snapshot from regional replica, then stream events from snapshot sequence.
3. Snapshot is prefix-filtered at the source: only keys matching subscribed prefixes are serialized. A full-keyspace snapshot (1 TB) is never sent to an edge node.
4. Snapshot transfer: chunked (1 MB chunks, CRC-32 per chunk, resumable on failure).

### Negative Lookup Optimization
- Edge node maintains a prefix-scoped Bloom filter: only keys within subscribed prefixes are indexed.
- For 100K keys at 10 bits/key: ~122 KB Bloom filter (vs 6 GB for 5 billion keys full-keyspace).
- Negative lookups for keys outside subscribed prefixes: instant rejection (prefix mismatch check, ~10ns).
- Negative lookups for keys within subscribed prefixes but non-existent: Bloom filter check (~100ns, 1% false positive rate).

### Subscription Validation and Authorization
- Subscriptions are validated against the client's namespace ACL (ADR-0017).
- A client can only subscribe to prefixes within its authorized namespaces.
- Cross-namespace subscriptions require explicit grant.
- Subscription count per edge node: soft limit 100 prefixes, hard limit 1000. Exceeding soft limit triggers a warning metric.

## Influenced by
- **Cloudflare Quicksilver v2 (cache sharding):** Three-tier cache hierarchy with working-set-aware sharding. Demonstrates the need for selective data placement — not all nodes need all keys.
- **Kafka Topic Subscriptions:** Consumers subscribe to specific topics/partitions, not the entire cluster's data. Offset-based resumption. Subscription changes are lightweight (rebalance).
- **MQTT Topic Filters:** Prefix and wildcard subscription matching. Hierarchical topic structure with per-level and multi-level wildcards. Proven at IoT scale (millions of subscribers).
- **CockroachDB Zone Configurations:** Table-level data placement rules determine which nodes hold which data ranges. Region-aware placement minimizes cross-region traffic.

## Reasoning

### Why prefix-based, not full-keyspace replication at edge?
Full-keyspace replication to 1M edge nodes is the single largest scalability bottleneck in any config distribution system. At 10^9 keys x 1 KB = 1 TB per edge node — this is physically impossible for edge hardware (CDN PoPs, embedded systems, container sidecars). Prefix subscription reduces edge storage by 4-5 orders of magnitude.

Additionally, full-keyspace replication means every config change (10K/s) propagates to every edge node (1M). That is 10 billion messages/s of propagation traffic. Prefix filtering at the distribution node reduces this to only relevant events per edge — typically 1-10% of total writes, reducing propagation traffic by 10-100x.

### Why not per-key subscriptions?
Per-key subscriptions require one subscription entry per key. At 100K keys per edge: 100K entries in the subscription table on the distribution node. At 10K edge nodes per distribution node: 1 billion subscription entries. Memory: ~64 bytes/entry = 64 GB. Prefix subscriptions collapse 100K keys into 10-50 prefix entries, reducing subscription metadata by 2,000-10,000x.

Per-key subscriptions also require subscription updates whenever new keys are created under a known prefix. Prefix subscriptions automatically include new keys — no update needed.

### Why not tag-based or attribute-based subscriptions?
Tag-based subscriptions (e.g., "subscribe to all keys with tag=production") require maintaining a tag index at the distribution node. Tag assignment changes require re-evaluation of all subscriptions. The tag index grows with keyspace x tag cardinality. For 10^9 keys with 10 tags each: 10 billion index entries. Prefix-based subscriptions leverage the natural key hierarchy — no additional indexing required.

### Why radix trie for subscription matching?
A linear scan of N subscriptions per event costs O(N x prefix_length). At 100K subscriptions per distribution node: 100K comparisons per event. At 10K events/s: 1 billion comparisons/s. A radix trie matches in O(key_length) regardless of subscription count. At 1 KB key: ~1us per match vs ~100ms for linear scan. The radix trie is built once at subscription registration time and updated incrementally.

### Working set miss handling
When an edge node receives a request for a key outside its subscribed prefixes:
1. **Prefix mismatch:** Immediate rejection. Client receives `KEY_NOT_IN_SCOPE` error with the list of subscribed prefixes. Client should direct the request to a regional replica or control plane.
2. **Within prefix but not yet replicated (bootstrap in progress):** Edge returns `NOT_READY` with estimated time to readiness. Client retries or falls back.
3. **Within prefix but deleted:** Bloom filter may false-positive. HAMT lookup returns null. Edge returns `KEY_NOT_FOUND`.

## Rejected Alternatives
- **Full-keyspace replication to all edges:** 1 TB per edge at 10^9 keys. Physically impossible for edge hardware. 10 billion messages/s propagation traffic at 10K writes/s x 1M edges. Bloom filter for negative lookups: 6 GB at 5 billion keys.
- **Per-key subscriptions:** 100K subscription entries per edge x 10K edges per distribution node = 1 billion entries (64 GB). Requires explicit subscription update for every new key. Does not scale.
- **Tag-based / attribute-based subscriptions:** Requires full-keyspace tag index at distribution nodes. Tag index of 10 billion entries for 10^9 keys x 10 tags. Tag changes require subscription re-evaluation across all edges. Operationally complex.
- **Pull-based on-demand fetching (no subscription):** Edge fetches keys on first access (cache miss). First access latency = RTT to regional replica (5-200ms), violating < 1ms p99 target. Cache miss storms after edge restart overwhelm regional replicas. No proactive push — edge is always stale until accessed.
- **Content-based routing (publish/subscribe with predicates):** Predicate evaluation per event per subscription is expensive. Complex predicate language increases operational surface area. Prefix matching is sufficient for hierarchical config keys and is O(key_length) vs O(predicate_complexity) for predicate evaluation.

## Consequences
- **Positive:** Edge storage reduced by 10,000-100,000x vs full-keyspace replication. Propagation traffic reduced by 10-100x (prefix filtering at distribution nodes). Negative lookups resolved in ~10ns (prefix mismatch) or ~100ns (Bloom filter). New keys automatically included in existing prefix subscriptions. Subscription metadata: 50 prefix entries vs 100K per-key entries per edge.
- **Negative:** Edge nodes cannot serve reads for keys outside their subscribed prefixes — clients must know which edge to query or fall back to regional replicas. Subscription misconfiguration (missing prefix) causes silent data absence, not errors. Prefix-filtered snapshots require prefix matching during serialization, adding ~10% overhead to snapshot generation.
- **Risks and mitigations:** Subscription misconfiguration mitigated by subscription coverage validation — distribution node warns if an edge's subscriptions cover < 90% of recently requested keys (based on access patterns). Prefix explosion (too many fine-grained prefixes) mitigated by soft limit of 100 prefixes per edge, with monitoring metric for edges exceeding the limit. Bootstrap storm after distribution node restart mitigated by staggered snapshot delivery: distribute snapshot transfers over `bootstrap_spread_interval` (default: 60 seconds) using consistent hashing of edge node IDs to schedule delivery order.

## Reviewers
- principal-distributed-systems-architect: ✅
- distributed-data-plane-engineer: ✅
- performance-engineer: ✅
