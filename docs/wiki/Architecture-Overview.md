# Architecture Overview

Configd separates concerns into a **control plane** (strong consistency via Raft) and a **data plane** (bounded staleness, sub-millisecond reads).

## High-Level Topology

```
┌──────────────────────────────────────────────────────────┐
│                     CONTROL PLANE                        │
│                                                          │
│  ┌─────────────────────────────────────────────────┐     │
│  │         Global Raft Group (5 voters)            │     │
│  │         Scope: GLOBAL config keys               │     │
│  └────────────┬────────────────┬───────────────────┘     │
│               │                │                         │
│     ┌─────────▼──────┐  ┌─────▼──────────┐              │
│     │ Regional Raft  │  │ Regional Raft   │  ...         │
│     │ Group (3 voters│  │ Group (3 voters)│              │
│     │ Scope: REGIONAL│  │ Scope: REGIONAL │              │
│     └───────┬────────┘  └───────┬─────────┘              │
└─────────────┼───────────────────┼────────────────────────┘
              │  Plumtree gossip  │
┌─────────────┼───────────────────┼────────────────────────┐
│             ▼   DATA PLANE      ▼                        │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐   │
│  │  Edge Node   │  │  Edge Node   │  │  Edge Node   │   │
│  │ LocalConfig  │  │ LocalConfig  │  │ LocalConfig  │   │
│  │   Store      │  │   Store      │  │   Store      │   │
│  └──────────────┘  └──────────────┘  └──────────────┘   │
└──────────────────────────────────────────────────────────┘
```

## Write Path (p99 < 150ms cross-region)

1. Client sends a write to the control plane API
2. The Raft leader proposes the entry (`RaftNode.propose()`)
3. Entry is replicated to a quorum via `AppendEntries`
4. On commit, entry is applied to `VersionedConfigStore`
5. Delta is computed and pushed to edge nodes via Plumtree gossip

## Read Path (p99 < 1ms, in-process)

1. Application thread calls `LocalConfigStore.get(key)`
2. Single volatile load of the `ConfigSnapshot` pointer
3. HAMT traversal: O(log32 N) — effectively O(1) for practical key counts
4. Zero allocation on miss (pre-allocated `ReadResult.NOT_FOUND` singleton)

## Consistency Model

- **Control plane writes**: Linearizable within a Raft group
- **Edge reads**: Bounded staleness (< 500ms p99 propagation target)
- **Monotonic reads**: Enforced per-session via `VersionCursor`
- **Staleness tracking**: `StalenessTracker` transitions through CURRENT → STALE → DEGRADED → DISCONNECTED

## Module Dependency Graph

```
configd-common ◄──── configd-transport
      ▲                     ▲
      │                     │
configd-config-store   configd-consensus-core
      ▲                     ▲
      │                     │
configd-edge-cache     configd-testkit (test only)
```

See [Module Reference](Module-Reference.md) for details on each module.
