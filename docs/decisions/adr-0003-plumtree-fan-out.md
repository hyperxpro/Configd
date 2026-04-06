# ADR-0003: Plumtree + HyParView for Edge Fan-out Distribution

## Status
Accepted

## Context
The system must propagate config changes from the control plane to 10K-1M edge nodes within 500ms p99 globally. The fan-out mechanism must handle slow consumers, network partitions, and node churn gracefully. Message complexity must scale sub-quadratically.

## Decision
We adopt **Plumtree (Epidemic Broadcast Trees)** built on top of a **HyParView** peer sampling overlay for config distribution from regional replicas to edge nodes.

### Plumtree
- Builds a spanning broadcast tree on top of the gossip overlay.
- **EagerPush peers** (tree edges): Receive full config payloads immediately.
- **LazyPush peers** (non-tree overlay): Receive only message IDs (IHave digests) periodically.
- When a node receives a message it already has from an eager peer: sends PRUNE, downgrading to lazy.
- When a node learns via IHave that it missed a message: sends GRAFT, promotes to eager, requests payload.
- **Steady-state message complexity: O(N)** — each payload traverses each tree edge exactly once.
- Self-healing within 1-2 gossip rounds after tree branch failure.

### HyParView
- Maintains two views per node:
  - **Active view:** log(N)+1 peers with TCP connections (15 peers at 10K nodes, 21 at 1M).
  - **Passive view:** 6×(log(N)+1) backup peers (90 at 10K, 132 at 1M).
- ForwardJoin propagates new node joins through overlay with TTL.
- Shuffle protocol periodically exchanges peer info for passive view freshness.
- Failed active peers replaced from passive view automatically.
- Maintains connectivity with up to 80% node failures.

### Hierarchical Backbone
- 2-3 tier tree: Control Plane → Regional Hubs (8-16) → Edge Clusters.
- Plumtree operates within each tier for intra-cluster distribution.
- Cross-tier links use persistent gRPC streams with sequence-number-based gap detection.

## Influenced by
- **Plumtree paper** (Leitão et al., SRDS 2007): Proved O(N) message complexity with gossip resilience.
- **HyParView paper** (Leitão et al., DSN 2007): Proved 100% reliability with up to 95% node failures.
- **Fastly Powderhorn:** Bimodal multicast achieves ~150ms global purge propagation.
- **HashiCorp Lifeguard:** SWIM extensions reduce false positives 50× while accelerating detection 20%.

## Reasoning
### Why not pure gossip?
Pure gossip (each node forwards to k random peers) has O(N × k) message complexity per broadcast. For 1M nodes with fanout=10: 10M messages per config change. Plumtree achieves O(N) — 10× reduction.

### Why not static tree?
Static trees are fragile — single node failure disconnects entire subtree with no self-healing. Plumtree's lazy push overlay provides automatic repair. Quicksilver's static tree requires 30-second timeout before failover; Plumtree repairs in 1-2 gossip rounds (~2-4 seconds).

### Why not pure push from control plane?
Direct push from control plane to 1M nodes requires 1M concurrent connections and consumes control plane bandwidth/CPU proportional to edge count. Plumtree distributes load across the tree.

### Propagation latency budget
With branching factor k=16, 2 tiers:
- 10K nodes: depth=4, latency = 4 × 5ms (intra-region) = 20ms regional + 100ms cross-region hop = ~120ms.
- 1M nodes: depth=5, latency = 5 × 5ms = 25ms regional + 100ms cross-region = ~125ms.
- With hedged requests per tier and 2 cross-region hops max: ~250ms p95, ~400ms p99. Within 500ms target.

## Rejected Alternatives
- **SWIM for data distribution:** SWIM is membership/health protocol, not data distribution. Piggybacking config payloads (1KB-1MB) on membership messages is bandwidth-prohibitive at 10K+ nodes.
- **Bimodal multicast:** UDP broadcast phase doesn't scale for 1MB payloads or 1M nodes. Suitable for notifications, not payloads.
- **Static replication tree (Quicksilver v1):** 30-second failover threshold. No self-healing. Tree depth additive latency.
- **Kafka-style log consumption:** Requires all edge nodes to connect to a central log. Single bottleneck. Not designed for 1M consumers.

## Consequences
- **Positive:** O(N) messages per broadcast. Self-healing tree. Distributed load. 80% failure resilience. Efficient bandwidth (payload only on tree edges, IDs on overlay).
- **Negative:** Tree construction adds initial latency (first broadcast from a new node traverses overlay before tree optimizes). IHave digests add background bandwidth (~640 bytes per update per lazy peer, ~20 lazy peers = ~13 KB/update).
- **Risks and mitigations:** Tree topology oscillation under high churn mitigated by minimum stable time before PRUNE/GRAFT. Overlay partitioning mitigated by HyParView's passive view diversity.

## Reviewers
- principal-distributed-systems-architect: ✅
- distributed-data-plane-engineer: ✅
- site-reliability-engineer: ✅
