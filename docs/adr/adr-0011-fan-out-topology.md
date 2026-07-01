# ADR-0011: Fan-Out Topology — Plumtree over HyParView with Direct Regional Push

## Status
Accepted

## Context
Config changes committed to Raft groups must reach 10K-1M edge nodes within < 500ms p99 globally. The propagation mechanism must handle slow consumers, network partitions, and node churn while maintaining O(N) message complexity. Cloudflare Quicksilver's hierarchical replication tree exhibits 30-second failover thresholds when intermediate nodes fail, and the additive latency of tree depth makes sub-second global propagation unreliable at scale (gap-analysis.md section 1.1). The system targets < 500ms p99 edge staleness and < 1ms p99 edge reads (section 0.1).

## Decision
We adopt a **two-layer fan-out topology**:

### Layer 1: Direct Regional Push (Control Plane to Distribution Nodes)
- Regional Raft followers (non-voting replicas or dedicated distribution service nodes) subscribe to the committed log of their Raft group via persistent gRPC streams.
- Each distribution node handles up to 10K edge connections. For 1M edge nodes: ~100 distribution nodes globally.
- Events serialized once into a shared immutable byte buffer, then written to multiple gRPC output streams — avoiding per-subscriber serialization cost.
- Gap detection via monotonic sequence numbers (ADR-0004): `received_seq != last_applied_seq + 1` triggers delta catch-up or snapshot re-bootstrap.

### Layer 2: Plumtree Epidemic Broadcast (Distribution Nodes to Edge)
- Plumtree (Leitao et al., SRDS 2007) builds a spanning broadcast tree on top of a HyParView peer sampling overlay.
- **EagerPush peers** (tree edges): receive full config delta payloads immediately.
- **LazyPush peers** (non-tree overlay): receive IHave digests every 500ms.
- Self-healing: missed message detected via IHave triggers GRAFT within 1-2 gossip rounds (~2-4 seconds).
- **Steady-state message complexity: O(N)** — each payload traverses each tree edge exactly once.

### Hierarchical Backbone
- 2-3 tier tree: Control Plane → Regional Hubs (8-16) → Edge Clusters.
- Cross-tier links use persistent gRPC streams with sequence-number-based gap detection.
- Maximum 2-3 hops from Raft commit to any edge node globally.

## Influenced by
- **Plumtree (Leitao et al., SRDS 2007):** Proved O(N) message complexity while retaining gossip's self-healing properties. Tree edges carry payloads; overlay edges carry only IHave digests.
- **HyParView (Leitao et al., DSN 2007):** Peer sampling protocol maintaining connectivity with up to 80% node failures. Active view of log(N)+1 peers with TCP connections; passive view of 6x(log(N)+1) backup peers.
- **Cloudflare Quicksilver v1 (static tree):** Demonstrates that static replication trees fail under intermediate node failure — 30-second disconnection threshold before subtree failover. Plumtree's lazy push overlay eliminates this class of failure.
- **Fastly Powderhorn:** Bimodal multicast achieves ~150ms global purge propagation, demonstrating that push-dominant distribution outperforms pull-based replication trees for latency-critical workloads.

## Reasoning

### Why direct regional push instead of tree-only?
Quicksilver's replication tree adds latency proportional to tree depth. Each hop adds 5-50ms (intra-region) or 50-220ms (cross-region). A 4-hop tree path through congested intermediaries easily exceeds 500ms. Direct push from regional distribution nodes to edge limits cross-region hops to a maximum of 2-3, keeping the propagation budget within target.

### Propagation latency budget
With branching factor k=16, 2-tier Plumtree within each region:
- **10K nodes:** depth=4, latency = 4 x 5ms (intra-region) = 20ms + 100ms cross-region hop = ~120ms.
- **1M nodes:** depth=5, latency = 5 x 5ms = 25ms + 100ms cross-region = ~125ms.
- With hedged requests per tier and 2 cross-region hops max: ~250ms p95, ~400ms p99. Within 500ms target.

### Why Plumtree over pure gossip for Layer 2?
Pure gossip with fanout k=10 to 1M nodes generates 10M messages per config change. Plumtree achieves O(N) — a 10x reduction — while retaining gossip's resilience through the lazy push overlay. At 10K writes/s sustained throughput, this reduces message amplification from 100 billion messages/s to 10 billion messages/s.

### Why not pure push from control plane?
Direct push from control plane to 1M nodes requires 1M concurrent connections and consumes control plane bandwidth/CPU proportional to edge count. This violates the control plane / data plane separation (architecture.md section 1) and creates a single bottleneck. The distribution service layer absorbs fan-out load, shielding the consensus path.

## Rejected Alternatives
- **Static replication tree (Quicksilver v1 model):** 30-second failover threshold when an intermediate node fails. Entire subtrees miss updates during failover window. No self-healing — requires explicit tree reconstruction. At 10K writes/s, a 30-second stall means 300K missed entries per subtree, requiring expensive snapshot catch-up.
- **Pure gossip (SWIM-piggybacked data):** O(N x k) message complexity per broadcast. Piggybacking 1KB-1MB config payloads on membership messages is bandwidth-prohibitive at 10K+ nodes. SWIM is designed for small health metadata, not data distribution.
- **Kafka-style log consumption:** Requires all edge nodes to connect to a central log broker. Single bottleneck at 1M consumers. Kafka's consumer group model adds rebalancing latency during edge node churn. Not designed for 1M concurrent consumers.
- **Bimodal multicast (UDP broadcast + gossip repair):** UDP broadcast phase does not scale for 1MB payloads or 1M nodes. Requires multicast-capable network infrastructure, which is unavailable across WAN. Suitable for small notifications (Fastly purge), not full config payloads.

## Consequences
- **Positive:** O(N) messages per broadcast. Self-healing tree repairs within 1-2 gossip rounds. Fan-out load distributed across distribution nodes, not concentrated on control plane. 80% node failure resilience via HyParView overlay. Maximum 2-3 hops from commit to edge globally.
- **Negative:** Plumtree tree construction adds initial latency on first broadcast from a new node (~2-4 seconds before tree optimizes). IHave digests add background bandwidth (~640 bytes per update per lazy peer, ~20 lazy peers per node = ~13 KB/update/node). Distribution service nodes are stateful — must be drained gracefully during maintenance.
- **Risks and mitigations:** Tree topology oscillation under high churn mitigated by minimum stable time (5 seconds) before PRUNE/GRAFT decisions. Overlay partitioning mitigated by HyParView's passive view diversity and periodic shuffle protocol (30-second interval). Distribution node failure mitigated by edge nodes maintaining connections to 2 distribution nodes (primary + backup) with automatic failover.

## Reviewers
- principal-distributed-systems-architect: ✅
- distributed-data-plane-engineer: ✅
- site-reliability-engineer: ✅
