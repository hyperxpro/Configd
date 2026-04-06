# Configd Wiki

Configd is a next-generation global configuration distribution system. It provides a strongly-consistent control plane backed by Raft consensus and an eventually-consistent edge data plane with sub-millisecond lock-free reads.

## Pages

- [Getting Started](Getting-Started.md) — Build from source, run tests, first integration
- [Architecture Overview](Architecture-Overview.md) — Control plane, data plane, modules, topology
- [Integration Guide](Integration-Guide.md) — Embedding Configd in your Java application
- [Docker](Docker.md) — Building and running with Docker
- [Testing](Testing.md) — Unit tests, integration tests, deterministic simulation
- [Module Reference](Module-Reference.md) — Per-module purpose, API surface, and dependencies

## Project Status

Configd is in active development (Phase 5 — Implementation). The core modules are implemented and tested:

| Module | Status |
|--------|--------|
| configd-common | Implemented |
| configd-consensus-core | Implemented |
| configd-config-store | Implemented |
| configd-edge-cache | Implemented |
| configd-transport | Implemented |
| configd-testkit | Implemented |
| configd-replication-engine | Planned |
| configd-distribution-service | Planned |
| configd-control-plane-api | Planned |
| configd-observability | Planned |

## Key Design Decisions

All architectural decisions are documented as ADRs in `docs/decisions/`. Notable choices:

- **Embedded Raft** (ADR-0001) — no external coordination (ZooKeeper, etcd)
- **Hierarchical Raft** (ADR-0002) — global + regional groups
- **Lock-free edge reads** (ADR-0005) — volatile HAMT pointer, zero allocation on miss
- **Deterministic simulation** (ADR-0007) — FoundationDB-style seeded testing
- **Java 21 + ZGC** (ADR-0009) — single-threaded Raft I/O thread
