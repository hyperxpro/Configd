# ADR-0001: Embedded Raft Consensus (Reject External Coordination)

## Status
Accepted

## Context
The system requires a consensus mechanism for strongly consistent writes in the control plane. The traditional approach uses an external coordination service (ZooKeeper, etcd) for leader election, metadata management, and configuration watches. Our system targets 99.999% control plane availability, < 150ms cross-region write commit p99, and 10K/s sustained write throughput.

## Decision
We will embed a Raft consensus engine directly into the Configd process, following the KRaft model (Kafka KIP-500). The system will own its own consensus with zero external coordination dependencies.

## Influenced by
- **Kafka KRaft (KIP-500):** Embedded Raft replacing ZooKeeper. Controller failover improved from 5-7s to < 1s. 2M partition ceiling (vs 200K with ZK — 10×). "42 Ways ZooKeeper Removal Improves Kafka" enumerates specific state divergence, operational burden, and metadata propagation failures.
- **ClickHouse Keeper:** Replaced ZK with C++ Raft. 4.5× memory reduction (81.6 GB → 18 GB), p99 insertion latency 15s → 2s.
- **Meta Zelos/Delos:** Migrated 50% of ZK workloads. Zelos achieved 56K vs ZK's 36K ops/sec on identical hardware.

## Reasoning
External coordination services have a documented track record of:
1. **State divergence:** ZK watches are one-shot (pre-3.6), creating race windows. KIP-500: "The state in ZooKeeper often doesn't match the state held in memory in the controller."
2. **Operational burden:** Separate cluster to deploy, monitor, upgrade, secure. 30-40% infrastructure cost reduction documented by Confluent after ZK removal.
3. **Write throughput ceiling:** ZK maxes at ~21K writes/sec on 3-node ensemble, inversely correlated with ensemble size. etcd: ~50K QPS with 256-byte values, dramatically less with realistic payloads.
4. **GC pauses (JVM-based systems):** ZK's 500ms GC pause can trigger cascading session expirations. Twitter 2018: session storms from hundreds of thousands of clients reconnecting after partition.
5. **Transitive failure domain:** External coordination outage takes down all dependent systems simultaneously.

Embedded Raft eliminates all five issues. The metadata log is an internal, event-sourced construct with offset-based consumption. Single deployment artifact.

## Rejected Alternatives
- **ZooKeeper:** Write throughput ceiling, JVM GC pauses, watch explosion (ZOOKEEPER-1177: 200M watches = 20 GB RAM), session storm vulnerability. Industry is actively removing ZK (Kafka, ClickHouse, Pulsar, Druid, Meta).
- **etcd:** 8 GB database ceiling, BoltDB single-writer fragmentation, v3.5 data corruption (10 months exposed), no horizontal write scaling. Still an external dependency.
- **Consul:** 512 KB value limit, 1 GB total KV recommended, 5K agent gossip ceiling, 2-hour convergence at 44K clients. Blocking queries (long-poll) inefficient.

## Consequences
- **Positive:** Single deployment artifact. No transitive dependency. Full control over consensus tuning (election timeout, heartbeat interval, batching). Metadata operations are local (ms, not seconds).
- **Negative:** Must implement and maintain our own Raft engine. Risk of consensus implementation bugs.
- **Risks and mitigations:** Implementation correctness mitigated by TLA+ specification (ADR-0007), deterministic simulation testing (ADR-0007), and reference validation against etcd/hashicorp Raft implementations.

## Reviewers
- principal-distributed-systems-architect: ✅
- distributed-systems-researcher: ✅
- site-reliability-engineer: ✅
