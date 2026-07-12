# Configd Wiki

Configd is a region-local, strongly-consistent configuration control plane. Writes go through Raft
consensus for durable, linearizable ordering; reads are served from a lock-free, in-process cache at
the edge in microseconds. The core serves writes; the edge serves reads.

New here? Start with [Getting Started](Getting-Started.md), then skim the
[Architecture Overview](Architecture-Overview.md). The authoritative deep-dive lives outside the
wiki in [`../architecture/architecture.md`](../architecture/architecture.md).

## Pages

- [Getting Started](Getting-Started.md) -- build from source, run the tests, run a server
- [Architecture Overview](Architecture-Overview.md) -- control plane, data plane, topology, modules
- [HTTP API](HTTP-API.md) -- every endpoint, parameter, header, and status code
- [Configuration](Configuration.md) -- every CLI argument and `configd.*` property, with defaults
- [Integration Guide](Integration-Guide.md) -- embedding the Configd libraries in a Java application
- [Module Reference](Module-Reference.md) -- per-module purpose, key types, and dependencies
- [Docker](Docker.md) -- building and running with Docker and Compose
- [Testing](Testing.md) -- unit tests, deterministic simulation, linearizability, and jcstress
- [FAQ](FAQ.md) -- common questions, answered honestly
- [Comparison](Comparison.md) -- Configd versus etcd, Consul, ZooKeeper, Spring Cloud Config

## What it is

A single, region-local sharded-Raft cluster. It runs one Raft group by default (N=1); hash-within-
scope sharding is wired and horizontal scale is proven (near-linear, about 2.45x across three
machines), with a decentralized balancer keeping one leader per box and an ADMIN-gated route to move
a group's leadership by hand. It is **not** a global, multi-region, hierarchical-Raft design -- that
was considered and rejected (ADR-0030, ADR-0031) and never built.

At-rest protection is **integrity** by default (HMAC-SHA-256 tamper detection); node-local
AES-256-GCM encryption is available as an opt-in, one-way door. With encryption off, values
(including `secure/` keys) are plaintext -- do not store secrets in Configd unless you turn it on.
See [`../operations/known-limitations.md`](../operations/known-limitations.md) for the honest
edges.

## Modules

All of the following are shipped and real (Maven multi-module build, one module per directory):

- Runtime libraries: `configd-common`, `configd-config-store`, `configd-consensus-core`,
  `configd-replication-engine`, `configd-distribution-service`, `configd-control-plane-api`,
  `configd-transport`, `configd-netty`, `configd-edge-cache`, `configd-observability`.
- Runnable services: `configd-server` (the control-plane node, main class
  `io.configd.server.ConfigdServer`) and `configd-edge-node` (the edge reader, main class
  `io.configd.edge.node.EdgeNodeMain`).
- Test and verification harnesses: `configd-testkit` (deterministic simulation + benchmarks),
  `configd-linz` (Porcupine linearizability), and `configd-jcstress` (Java Memory Model concurrency).

See the [Module Reference](Module-Reference.md) for details.

## Key design decisions

Decisions are recorded as ADRs in [`../adr/`](../adr/). A few that shape the system:

- **Embedded Raft** ([ADR-0001](../adr/adr-0001-embedded-raft-consensus.md)) -- no external
  coordinator (no ZooKeeper, no etcd).
- **Quicksilver-shaped topology** ([ADR-0030](../adr/adr-0030-quicksilver-shaped-topology.md)) --
  one centralized region-local Raft root for writes plus asynchronous bounded-staleness edge fan-out;
  global multi-region write consensus is rejected.
- **Hash-within-scope sharding** ([adr-multiraft-partitioning](../adr/adr-multiraft-partitioning.md))
  -- route a key to a shard by hashing it within its scope; point-lookup workload, no range scans.
- **Lock-free edge reads** ([ADR-0005](../adr/adr-0005-lock-free-edge-reads.md)) -- a volatile
  pointer to an immutable HAMT snapshot, zero allocation on a miss.
- **Deterministic simulation** ([ADR-0007](../adr/adr-0007-deterministic-simulation-testing.md)) --
  FoundationDB-style seeded testing.
- **Java 25 + generational ZGC** ([ADR-0022](../adr/adr-0022-java-25-runtime.md),
  [ADR-0041](../adr/adr-0041-gc-collector.md)) -- sub-millisecond GC pauses for the read/commit tail.
- **Netty 4.2 transport** ([ADR-0043](../adr/adr-0043-netty-transport-platform.md)) -- one transport
  stack across all four network surfaces.
- **At-rest integrity + fail-closed signing key**
  ([ADR-0042](../adr/adr-0042-snapshot-wal-raftstate-integrity.md),
  [ADR-0044](../adr/adr-0044-signing-key-management.md)) -- tamper detection on the durability
  artifacts, with the signing key required to live outside attacker-writable storage.
