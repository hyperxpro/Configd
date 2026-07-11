# ADR-0023: Multi-Raft Sharding

## Status
Accepted (2026-04-17)

## Context

A single Raft group's write-throughput ceiling is bounded by the leader's
append-and-fsync pipeline. Multi-tenant deployments at scale (audit log
ingest, ML feature flags, A/B-test parameters) can need throughput well
above what one group sustains, and at that load a single Raft group
becomes the bottleneck: head-of-line blocking means a slow tenant can
stall the whole cluster.

## Decision

Multi-Raft sharding is built and available now. The default topology is
still a single Raft group across all key-space (`shardId = 0`), which is
the simplest deployment and enough for most workloads. An operator turns
on multiple shards to scale horizontally: each shard runs its own Raft
group, and a decentralized balancer keeps roughly one leader per box
across the shards.

## Rationale

1. **A single Raft group stays the default.** It is the simplest thing
   to reason about and to operate, and it is enough for deployments that
   never approach the single-group ceiling.
2. **Multi-Raft was a substantial design change.** It reshapes routing,
   per-shard Raft groups, cross-shard coordination for keys that move,
   and the topology of the fan-out overlay. That design work is recorded
   separately in [`adr-multiraft-topology.md`](adr-multiraft-topology.md),
   [`adr-multiraft-partitioning.md`](adr-multiraft-partitioning.md), and
   [`adr-multiraft-cross-shard.md`](adr-multiraft-cross-shard.md).
3. **Shard count is a deploy-time choice, not a runtime one.** Sharding
   solves the throughput ceiling; it does not (yet) solve elastic,
   online rebalancing - see Consequences below.

## Consequences

- The default deployment remains a single Raft group
  (`ClusterConfig.shardId = 0`). Sharding is an operator-enabled mode for
  horizontal scale, not a requirement.
- Shard count is static, fixed at deploy time via a static shard map.
  There is no online dynamic resharding: no runtime split, merge, or
  rebalance of shard boundaries. Growing the shard count is a new
  deployment, not a live operation.
- Leadership across shards is auto-balanced by a decentralized balancer
  that keeps roughly one leader per box; an ADMIN-gated route can also
  move a group's leadership by hand.
- See [`adr-multiraft-topology.md`](adr-multiraft-topology.md),
  [`adr-multiraft-partitioning.md`](adr-multiraft-partitioning.md), and
  [`adr-multiraft-cross-shard.md`](adr-multiraft-cross-shard.md) for the
  fuller shipped design, and
  [`adr-throughput-target.md`](adr-throughput-target.md) for the measured
  throughput numbers, including the horizontal-scaling results.

## Related

- ADR-0001 (embedded Raft consensus)
- [`adr-multiraft-topology.md`](adr-multiraft-topology.md)
- [`adr-multiraft-partitioning.md`](adr-multiraft-partitioning.md)
- [`adr-multiraft-cross-shard.md`](adr-multiraft-cross-shard.md)
- [`adr-throughput-target.md`](adr-throughput-target.md)

## Verification

- **Testable via:** `configd-consensus-core/src/test/java/io/configd/raft/ClusterConfigTest.java` covers the `ClusterConfig` schema, including the default single-group case (`shardId = 0`). `configd-replication-engine/src/test/java/io/configd/replication/MultiRaftDriverTest.java` (its `GroupManagement` tests) exercises multiple Raft groups under one driver, which is what a sharded deployment runs in production.
- **Invalidated by:** a `ClusterConfig` that cannot express `shardId != 0`, or a shard count that changes at runtime without a redeploy (online resharding).
- **Operator check:** `configd_raft_groups_total` reports 1 for a single-group deployment and N for an N-shard deployment.
