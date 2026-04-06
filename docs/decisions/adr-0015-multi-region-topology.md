# ADR-0015: Multi-Region Topology with Region Tiers and Scope-Aware Placement

## Status
Accepted

## Context
The system must support global deployment across 5+ regions with < 150ms p99 cross-region write commit, < 500ms p99 edge staleness, and < 1ms p99 edge reads. ZooKeeper was never designed for WAN deployment — ensemble across DCs requires RTT < 100ms p99, and observers cannot be auto-promoted or improve write throughput (gap-analysis.md section 2.5). Consul's WAN federation requires average RTT <= 50ms with p99 <= 100ms, eliminating intercontinental federation for many deployments (gap-analysis.md section 4.1). A single global Raft group imposes cross-region RTT on every write, making 10K/s throughput unachievable with realistic payloads.

## Decision
We adopt a **three-tier multi-region topology** with scope-aware Raft group placement:

### Region Tiers

| Tier | Role | Raft Participation | Dataset | Example |
|---|---|---|---|---|
| **Core** (3 regions) | Global Raft voters. Full dataset. | Global group voters + regional group voters | Full (all scopes) | us-east-1, eu-west-1, us-west-2 |
| **Regional** (N regions) | Regional Raft groups. Non-voting global replicas. | Regional group voters + global group non-voters | Full regional + stale global | ap-northeast-1, ap-southeast-1, sa-east-1 |
| **Edge** (10K-1M nodes) | Plumtree consumers. Working set only. | None — zero Raft participation | Subscribed prefixes only | CDN PoPs, edge servers |

### Scope-Aware Shard Placement

Each config key has a declared **scope** that determines which Raft group handles its writes:

| Scope | Raft Group | Voter Placement | Commit Latency | Example Keys |
|---|---|---|---|---|
| `GLOBAL` | Global group (5 voters) | {us-east-1, us-west-2, eu-west-1} + 2 non-voters | ~68ms | `global.routing.rules`, `security.tls.policy` |
| `REGIONAL` | Regional group (3 voters) | 3 AZs within the target region | 2-5ms | `us.feature.flags`, `eu.capacity.limits` |
| `LOCAL` | None (local only) | N/A | < 1ms | `node.debug.level`, `node.tuning.gc` |

### Cross-Region Raft Only for GLOBAL Scope
- Global Raft group: 5 voters placed in 3 core regions (2+2+1 distribution for optimal quorum latency). Leader in us-east-1.
- Commit latency: RTT to 2nd-closest voter. Sorted RTTs from us-east-1: 57ms (us-west-2), 68ms (eu-west-1), 148ms (ap-northeast-1), 220ms (ap-southeast-1). With 5 voters needing 3 acks: commit = 68ms. With batching overhead: ~80ms. Within 150ms target.
- Regional Raft groups: 3 voters within a single region. Commit latency: 2-5ms intra-AZ.

### Non-Voting Replicas for Remote Reads
Inspired by CockroachDB's closed timestamp mechanism:
1. Each Raft leader periodically advances a **closed timestamp** — a promise that no new writes will occur at or below that timestamp.
2. Non-voting replicas in remote regions track this closed timestamp.
3. Reads at timestamps <= closed timestamp served locally without leader contact.
4. Default closed timestamp target: 3 seconds behind real time.
5. Side-transport publishes closed timestamp updates every 200ms for idle Raft groups.
6. **Bounded staleness guarantee:** Remote region reads are at most `closed_timestamp_target + propagation_delay` stale. At 3s target + 200ms propagation: ~3.2s max staleness for follower reads on control plane.

### Region Failure Scenarios

| Scenario | Impact | Recovery | Target RTO |
|---|---|---|---|
| Loss of 1 non-core region | Regional key writes unavailable. Edge reads continue from stale cache. | Promote non-voting replica to voter in surviving region. | < 30s |
| Loss of 1 core region (minority) | Global writes continue (3 of 5 voters remain). Regional writes in that region unavailable. | Replace lost voters from surviving core regions. | < 10s (election) |
| Loss of 2 core regions (majority) | Global writes unavailable. Regional writes in surviving regions continue. Edge reads continue. | Manual intervention. Emergency reconfiguration. | Minutes (manual) |
| Edge node loss | Zero impact on system. | New edge bootstraps via snapshot + delta catch-up. | < 30s |
| Submarine cable cut | Edge nodes in affected regions serve stale (DEGRADED state). Cross-region Raft may lose quorum depending on cut. | Traffic rerouted (+100-200ms latency) or manual reconfiguration. | Varies |

## Influenced by
- **CockroachDB Multi-Region:** ZONE survival (2-5ms), REGION survival (20-30ms), GLOBAL tables with non-voting replicas everywhere. Closed timestamp mechanism enables bounded-staleness follower reads without leader contact. Documented trade-offs between latency and survival guarantees.
- **TiKV Multi-Raft:** Per-range Raft groups with independent leaders. Epoch-based stale request detection. Batched Raft I/O across groups sharing one RocksDB (we share one WAL per shard group).
- **Cloudflare Quicksilver:** Hierarchical fan-out from core to edge — demonstrates the need for tiered topology. But static tree + 30s failover is insufficient (ADR-0011).
- **Spanner TrueTime:** Global consistency via hardware clocks. We achieve similar bounded-staleness guarantees via HLC + closed timestamps without TrueTime hardware dependency.

## Reasoning

### Why three tiers instead of flat topology?
A flat topology (all regions equal) requires either: (a) all regions participate in global Raft, which limits the write quorum to the slowest region (220ms RTT to ap-southeast-1), or (b) every region runs independent Raft groups with cross-region replication, creating O(R^2) replication links. Three tiers match the natural hierarchy: a small set of core regions for global consistency, regional groups for fast local writes, and edges for read-only consumption.

### Why scope-aware placement instead of uniform replication?
Config workload analysis from gap-analysis.md:
- **Global config (~1% of keys, ~10% of writes):** Must be globally consistent. Worth paying 68ms cross-region commit.
- **Regional config (~30% of keys, ~60% of writes):** Only needs regional consistency. 2-5ms commit. No reason to replicate across all regions.
- **Local config (~69% of keys, ~30% of writes):** Node-specific. Zero replication cost.

Uniform replication would impose 68ms latency on 90% of writes (regional + local) that do not require it. Scope-aware placement reduces the average write commit latency to: (0.1 x 68) + (0.6 x 3.5) + (0.3 x 0.5) = 6.8 + 2.1 + 0.15 = **~9ms weighted average**, vs 68ms for uniform global replication.

### Why 5 voters for global group, not 3?
With 3 voters across 3 regions, loss of any single region loses quorum. With 5 voters across 3 core regions (2+2+1), loss of any single region still has 3 or 4 voters surviving — quorum maintained. This provides REGION survival for global config at the cost of slightly higher commit latency (68ms vs potentially 57ms with 3 voters in 2 regions).

### Why non-voting replicas instead of independent read replicas?
Non-voting replicas participate in Raft log replication (they receive AppendEntries) but do not vote. This means they are always at most one Raft heartbeat behind the leader. Independent read replicas would need a separate replication mechanism (e.g., polling), adding complexity and increasing staleness. The non-voting replica model is proven in CockroachDB and TiKV.

## Rejected Alternatives
- **Single global Raft group (all regions):** Every write pays cross-region RTT (~220ms to ap-southeast-1). At 10K writes/s with 220ms commit: 2,200 in-flight writes. Pipeline depth creates head-of-line blocking and makes the 150ms p99 target impossible when leader is distant from writer.
- **Flat multi-Raft (no region tiers):** All regions treated equally. Requires either uniform replication (wasteful for regional/local config) or a placement driver that rediscovers the hierarchical pattern we've defined explicitly. CockroachDB and TiKV both evolved toward region-aware placement, validating that flat topology is insufficient for WAN.
- **ZooKeeper observers for remote regions:** Observers cannot be auto-promoted to voters. Observers do not improve write throughput. No built-in bounded-staleness mechanism — observers are eventually consistent with unbounded lag. Documentation warns against cross-DC deployment.
- **Consul WAN federation:** Requires RTT <= 50ms average, <= 100ms p99 — eliminates us-east to ap-southeast (220ms). KV data not replicated by default. `consul-replicate` daemon is not transactional, not ordered, can lose updates on crash.

## Consequences
- **Positive:** 60% of writes (regional) commit in 2-5ms. Global writes commit in ~68ms (within 150ms target). Edge reads served from local HAMT at < 1ms. Non-voting replicas enable low-latency reads in remote regions. Single region failure does not affect global write availability.
- **Negative:** Topology is more complex than single-group deployment. Requires PlacementDriver to manage group membership and tier assignments. Cross-group ordering not guaranteed (disclaimed in consistency-contract.md section 5).
- **Risks and mitigations:** Region misconfiguration (wrong tier assignment) mitigated by PlacementDriver validation rules — core regions must have >= 3, total voters must be odd. Network asymmetry between regions mitigated by adaptive leader placement — leader migrated to region with lowest aggregate RTT to other voters. Region promotion (regional to core) requires careful Raft membership change — mitigated by TLA+-verified reconfiguration protocol (ADR-0007).

## Reviewers
- principal-distributed-systems-architect: ✅
- distributed-systems-researcher: ✅
- site-reliability-engineer: ✅
