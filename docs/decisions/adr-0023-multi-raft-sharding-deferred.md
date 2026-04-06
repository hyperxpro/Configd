# ADR-0023: Multi-Raft Sharding Deferred to v0.2

## Status
Accepted (2026-04-17). Defers R-06; tracked under PA-3001.

## Context

The v0.1 cluster runs a single Raft group across all key-space. The
write-throughput ceiling for a single Raft is bounded by the leader's
append-and-fsync pipeline; measured on the perf baseline (Phase 6) the
sustainable write rate is ~10 k commits/s on the reference hardware.

Multi-tenant deployments at scale need ~100 k+ commits/s (audit log
ingest, ML feature flags, A/B-test parameters). At that load a single
Raft becomes the bottleneck and head-of-line blocking across tenants
becomes a real reliability risk: a slow tenant can stall the cluster.

## Decision

Defer multi-Raft sharding to v0.2. v0.1 ships single-Raft only.

## Rationale

1. **Single Raft scales to the GA target.** Quicksilver's commit budget
   is `< 150 ms p99` at 1 k commits/s; the perf baseline shows headroom
   to ~10 k. v0.1 is sized for that ceiling.
2. **Multi-Raft is a substantial design change.** It re-shapes routing,
   joint consensus across shards, cross-shard transactions, and the
   topology of the gossip overlay. Doing it cleanly takes a separate
   ADR cycle and a fresh round of TLA+ modelling.
3. **No production customer in the v0.1 cohort exceeds the single-Raft
   ceiling.** The deferral is risk-driven, not capability-driven.

## Consequences

- v0.1 deployment tooling assumes a single Raft group per cluster.
- The `ClusterConfig` schema reserves the `shardId` field but always
  sets it to 0; v0.2 will populate it.
- The capacity calibration table in `docs/perf-baseline.md` defines the
  recommended cluster-size cap (≤ 5 k subscribed prefixes, ≤ 10 k
  commits/s) above which v0.2 with sharding is required.

## Migration Plan

- v0.2 (target Q3 2026): introduce shard-routing in the API gateway,
  per-shard Raft groups, cross-shard transaction coordinator (2PC over
  Raft).
- Until then, customers above the single-Raft ceiling deploy multiple
  independent Configd clusters with shard-key partitioning at the
  application layer.

## Related

- PA-3001 (R-06 root cause)
- ADR-0001 (embedded Raft consensus)
- `docs/perf-baseline.md` (single-Raft ceiling)

## Verification

- **Testable via:** the single-shard invariant is asserted by `configd-consensus-core/src/test/java/io/configd/raft/ClusterConfigTest.java` (the `shardId` field is reserved in `ClusterConfig` but always 0 in v0.1). Multi-Raft driver wiring is exercised by `configd-replication-engine/src/test/java/io/configd/replication/MultiRaftDriverTest.java` even though only one driver is provisioned in v0.1.
- **Invalidated by:** any v0.1 deployment that provisions more than one Raft group per cluster, or a `ClusterConfig` produced with `shardId != 0`.
- **Operator check:** `configd_raft_groups_total` gauge equals 1 in production v0.1 clusters; the capacity table in `docs/perf-baseline.md` documents the ≤ 10 k commits/s ceiling above which v0.2 sharding is required.
