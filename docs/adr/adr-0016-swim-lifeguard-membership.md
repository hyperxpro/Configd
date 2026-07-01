# ADR-0016: SWIM/Lifeguard Membership Protocol (Gossip for Discovery, NOT Data)

## Status
Not Implemented

> **Note (2026-04-14, Verification Phase V8):** SWIM/Lifeguard membership is
> not implemented in the codebase. The current system uses HyParView overlay
> (`HyParViewOverlay.java`) for Plumtree gossip topology management and
> standard Raft membership (`ClusterConfig.java`, joint consensus) for
> consensus group management. Peer discovery relies on the `--peers` flag
> at startup and Raft reconfiguration for membership changes. This ADR
> documents an aspirational design that was not carried forward into
> implementation. The HyParView + Raft membership combination provides
> adequate functionality for the current deployment scale.

## Context
The system requires a membership protocol for peer discovery and health monitoring among Raft voter nodes and distribution service nodes. Consul's experience demonstrates the dangers of using gossip for both membership AND data propagation: convergence at 5,000 agents takes 10-30 seconds for failure detection, and migrating 44K clients took 2 hours for state convergence (gap-analysis.md section 4.2). Cross-pollinated gossip (overlapping IP ranges) caused raft commit times of tens of seconds and leader loops up to 15 seconds (Consul issue #5309). The system must detect node failures within seconds while supporting 10K+ Raft voters and distribution nodes across all regions.

## Decision
We adopt **SWIM (Scalable Weakly-consistent Infection-style Process Group Membership)** with **Lifeguard extensions** (HashiCorp, 2018) for membership management with a strict architectural boundary: **gossip is used ONLY for peer discovery and health monitoring, NEVER for config data propagation**.

### Gossip Scope (What SWIM Manages)
1. **Peer discovery:** New Raft voter or distribution node announces itself via SWIM join. Existing members learn about it through infection-style dissemination.
2. **Health monitoring:** Periodic probe/ping-ack cycle detects node failures. Failed nodes disseminated as SUSPECT → DEAD through the gossip protocol.
3. **Metadata dissemination:** Small metadata only — node ID, region, tier (core/regional/edge), Raft group membership, load metrics. Maximum metadata size: 512 bytes per node.
4. **Topology changes:** Region joins/leaves, distribution node scaling events.

### What SWIM Does NOT Manage
- **Config data propagation:** Handled by Plumtree + gRPC streams (ADR-0011). Config payloads (1 KB - 1 MB) are NEVER piggybacked on gossip messages.
- **Raft log replication:** Handled by Raft AppendEntries over Netty (ADR-0010).
- **Edge node membership:** Edges are NOT gossip participants. Edge liveness tracked by distribution nodes via gRPC stream heartbeats.

### SWIM Protocol Parameters
- **Probe interval:** 1 second (time between successive probes of random members).
- **Probe targets per interval:** 1 direct probe + k=3 indirect probes on suspicion.
- **Suspicion timeout:** 5 x log(N) x probe_interval. At 100 Raft voters: ~33 seconds before DEAD declaration.
- **Protocol period:** 1 second. At 100 members: full failure detection in ~7 seconds (log(100) x 1s).
- **Message size:** UDP payload, max 1400 bytes. Piggybacks ALIVE/SUSPECT/DEAD updates on probe messages.

### Lifeguard Extensions
1. **Local Health-Aware Probe (LHAP):** Nodes monitor their own health (CPU, memory, disk I/O, network). Unhealthy nodes increase their suspicion timeout multiplier — accepting that others may suspect them for longer before they declare others dead. Prevents false positives from a node whose own health is degraded.
2. **Dogpile prevention:** When N nodes independently suspect the same target, only the first k=3 send indirect probes. Others wait for the result. Reduces probe amplification during cascading failures.
3. **Suspicion decay:** Suspicion counter decays over time if the suspected node responds to probes. Prevents permanent suspicion from transient network issues.

### Multi-Dimensional Health Monitoring (Beyond SWIM Probes)
SWIM probes test network reachability. The system supplements with:
- **Data-plane latency:** Track p99 of Raft AppendEntries round-trip. A node reachable via gossip but slow on data plane is flagged as DEGRADED (gray failure detection).
- **Disk I/O latency:** fsync latency > 1 second triggers voluntary leader step-down. Reported via gossip metadata.
- **Memory pressure:** Heap usage > 90% triggers write rejection. Reported via gossip metadata.
- **Raft health:** Raft heartbeat failures, log apply lag > 5,000 entries, snapshot transfer stalls. Reported via gossip metadata.

A node is considered healthy only if ALL dimensions are healthy. Any single dimension DEGRADED triggers a composite health downgrade.

## Influenced by
- **SWIM (Das et al., DSN 2002):** O(1) message complexity per probe period per member. Infection-style dissemination achieves O(log N) propagation time. Membership changes detected within O(log N) protocol periods with high probability.
- **Lifeguard (HashiCorp, 2018):** Reduces SWIM false positive rate by 50x while accelerating true positive detection by 20x. Local health awareness prevents cascade from self-induced failures.
- **Consul (anti-pattern for data gossip):** Demonstrates that using gossip for data propagation creates convergence problems at 5K+ nodes. 44K-client migration took 2 hours. Config updates are NOT suitable for gossip piggybacking.
- **CockroachDB Liveness:** Raft heartbeats plus node liveness records. Multi-signal health monitoring for gray failure detection.

## Reasoning

### Why SWIM instead of full Serf/Memberlist gossip?
Serf (HashiCorp) implements SWIM + user event broadcasting + query/response. The user event and query features encourage using gossip for data propagation — exactly the anti-pattern we avoid. We use only the membership/failure detection core of SWIM, not the event broadcast layer.

### Why not Raft heartbeats alone for failure detection?
Raft heartbeats detect failure of Raft peers within a Raft group. But the system has multiple Raft groups, distribution nodes, and administrative nodes that are NOT members of any Raft group. SWIM provides a uniform failure detection mechanism across all node types. Additionally, Raft heartbeats only test the Raft communication path — a node can be Raft-reachable but data-plane-unhealthy (gray failure).

### Why not ZooKeeper-style ephemeral nodes for membership?
This would reintroduce the external coordination dependency that ADR-0001 explicitly rejected. It would also create a circular dependency: the membership system depends on ZooKeeper, which itself needs membership management.

### Why strict separation of gossip and data?
Consul's experience at 5K+ nodes shows that gossip convergence time grows with data volume piggybacked on membership messages. Config payloads (1 KB - 1 MB) would dominate gossip bandwidth, slowing failure detection. SWIM's O(1) per-probe message complexity depends on small, bounded message sizes. Piggybacking config data breaks this bound and converts O(1) to O(payload_size) per probe.

### Edge nodes are NOT gossip participants
At 1M edge nodes, SWIM's probe interval would generate 1M probes/second. Even with optimizations, this is 1.4 GB/s of probe traffic (1400 bytes x 1M). Edges are discovery-passive: distribution nodes track edge liveness via gRPC stream heartbeats (ADR-0013). This limits SWIM membership to ~100-1000 infrastructure nodes (Raft voters + distribution nodes), where probe overhead is negligible.

## Rejected Alternatives
- **Consul Serf for full membership + data:** Gossip convergence at 5K+ nodes takes 10-30 seconds. 44K-client migration took 2 hours. Cross-pollinated gossip (issue #5309) caused raft commit times of tens of seconds. Data piggybacking on gossip breaks the O(1) message complexity bound.
- **ZooKeeper ephemeral nodes for membership:** Reintroduces external coordination dependency (rejected by ADR-0001). Session management overhead at 10K+ members consumes consensus bandwidth (ADR-0013). Single point of failure.
- **Static membership file:** No failure detection. Manual updates required for every membership change. Not viable for dynamic scaling or automated failover.
- **DNS-based discovery (Kubernetes headless service):** DNS TTL creates stale membership view. No health monitoring. No failure dissemination. Polling-based — O(N) queries per TTL interval.
- **SWIM with data piggybacking:** Config payloads (1 KB - 1 MB) would dominate gossip bandwidth. 1 MB payload x 100 probes/second = 100 MB/s of gossip traffic at 100 nodes. Failure detection latency degrades proportionally to piggybacked data size.

## Consequences
- **Positive:** O(1) per-probe message overhead. O(log N) failure detection time. Lifeguard reduces false positives 50x. Multi-dimensional health monitoring catches gray failures that SWIM probes alone miss. Strict gossip/data separation prevents convergence degradation at scale.
- **Negative:** Two separate subsystems for membership (SWIM) and data distribution (Plumtree/gRPC). SWIM and Raft can disagree on node health — a node can be SWIM-alive but Raft-partitioned. Suspicion timeout of 33 seconds at 100 members means temporary false positives during that window.
- **Risks and mitigations:** SWIM/Raft health disagreement mitigated by composite health score that requires both SWIM-alive AND Raft-healthy for full health status. Suspicion timeout false positives mitigated by Lifeguard's local health awareness — truly unhealthy nodes accept longer suspicion, reducing cascading false positives. Gossip partition (split overlay) mitigated by seed node list (3-5 well-known nodes per region) that all members periodically probe directly.

## Reviewers
- principal-distributed-systems-architect: ✅
- distributed-systems-researcher: ✅
- site-reliability-engineer: ✅
