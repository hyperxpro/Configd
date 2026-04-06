# ADR-0002: Hierarchical Raft Replication Topology

## Status
Accepted

## Context
The system must support global writes (< 150ms p99 cross-region), 10K/s sustained write throughput, and edge propagation to 10K-1M nodes. The replication topology must be explicitly chosen from: multi-Raft (range-partitioned), hierarchical Raft (regional groups + global coordinator), or leaderless (EPaxos-style).

## Decision
We adopt **hierarchical Raft** with three layers:

1. **Global Raft Group (5 voters across 3+ regions):** Handles globally-scoped configuration keys. Voters placed in {us-east-1, us-west-2, eu-west-1} with non-voting replicas in {ap-northeast-1, ap-southeast-1}. Commit latency: RTT to 2nd-closest voter = ~68ms (us-east-1 leader).

2. **Regional Raft Groups (3 voters within each region):** Handle region-scoped configuration keys. Commit latency: 2-5ms intra-region. Each region has its own independent Raft group.

3. **Edge Fan-out (non-consensus):** Edge nodes NEVER participate in Raft consensus. They receive updates via Plumtree epidemic broadcast (ADR-0003) and serve reads from local immutable snapshots.

Each config key has a declared **scope**: `GLOBAL`, `REGIONAL`, or `LOCAL`. Scope determines which Raft group handles writes.

## Influenced by
- **CockroachDB:** Non-voting replicas for remote region reads. ZONE survival (2-5ms) vs REGION survival (20-30ms). Closed-timestamp mechanism for bounded-staleness follower reads.
- **TiKV:** Multi-Raft management with batched Raft I/O across groups. Epoch-based stale request detection.
- **Kafka KRaft:** Single controller quorum for metadata + pull-based propagation to brokers (non-voting consumers).
- **Cloudflare Quicksilver:** Hierarchical fan-out tree where edge nodes do not participate in consensus.

## Reasoning

### Why not multi-Raft (TiKV/CockroachDB style)?
Multi-Raft partitions the keyspace into ranges, each with its own Raft group and independent leader. This is designed for partitioned transactional data where different ranges have different access patterns.

Config distribution is fundamentally different: it needs fast broadcast from a single source of truth, not partitioned consensus. The overhead of managing thousands of Raft groups (heartbeat storms, per-group state, split/merge operations) is unjustified. TiKV reports performance degradation with millions of regions.

Additionally, multi-Raft requires a Placement Driver (centralized coordinator) to manage region metadata — reintroducing a centralized dependency.

### Why not leaderless (EPaxos)?
EPaxos has no production deployment track record. Multiple published correctness bugs: ballot management (Sutra, 2019), fast-quorum check (reference implementation issue #10), recovery deadlock (Ryabinin et al., 2025). Dependency graph management is operationally opaque. Under sustained conflict, SCCs grow unboundedly, stalling execution.

### Why hierarchical?
The config keyspace naturally partitions by scope:
- **Global config** (~1% of keys, ~10% of writes): Routing rules, global feature flags, security policies. These MUST be consistent globally. Cross-region Raft is acceptable because writes are infrequent relative to regional config.
- **Regional config** (~30% of keys, ~60% of writes): Region-specific settings, regional feature flags, capacity parameters. These need fast commits (2-5ms) and don't require global consensus.
- **Local config** (~69% of keys, ~30% of writes): Per-node tuning, debug flags. Non-replicated, local-only.

This separation means the majority of writes hit regional Raft groups (2-5ms commit) while only truly global writes pay cross-region latency (68-92ms).

### Latency budget verification
Global Raft group, leader in us-east-1, voters in {us-east-1, us-west-2, eu-west-1}:
- Commit = max(RTT to us-west-2, RTT to eu-west-1) = max(57, 68) = **68ms** (wait for both, majority = 3 of 5 with 2 non-voters)
- Actually with 5 voters: need 3 acks. Sorted RTTs: 57 (us-west-2), 68 (eu-west-1), 148 (ap-northeast-1), 220 (ap-southeast-1). Commit = 68ms (2nd closest).
- With batching overhead: ~80ms. Well within 150ms target.

Regional Raft group (3 voters in same region): 2-5ms commit.

## Rejected Alternatives
- **Multi-Raft (TiKV-style):** Overhead of managing thousands of Raft groups unjustified for config workload. Heartbeat storms at scale. Requires centralized PD. Config keys don't need partitioned consensus.
- **EPaxos (leaderless):** No production track record. Multiple known correctness bugs. Operationally opaque dependency graphs.
- **Single global Raft group:** Cross-region RTT on every write. Cannot achieve 10K/s with realistic payloads.

## Consequences
- **Positive:** Majority of writes (regional) commit in 2-5ms. Global writes commit in ~68ms. Edge nodes isolated from consensus overhead. Natural mapping to config key semantics.
- **Negative:** More complex than single Raft group. Requires routing logic to direct writes to correct group. Cross-group ordering not guaranteed (disclaimed in consistency contract).
- **Risks and mitigations:** Group membership management complexity mitigated by TLA+ verification of reconfiguration protocol. Cross-group ordering gaps mitigated by HLC timestamps.

## Reviewers
- principal-distributed-systems-architect: ✅
- distributed-systems-researcher: ✅
- performance-engineer: ✅
