# ADR-0017: Namespace-Based Multi-Tenancy as Architectural Primitive

## Status
Accepted

## Context
Multi-tenancy is required for shared deployments where multiple teams, services, or organizational units use the same Configd cluster. Consul gates multi-tenancy (Admin Partitions), read scaling (non-voting servers), gossip isolation (Network Segments), and AZ-aware failover (Redundancy Zones) behind Enterprise licensing — indicating these capabilities were bolted onto an architecture not designed for them (gap-analysis.md section 4.5). The system must provide tenant isolation for reads, writes, ACLs, rate limits, and storage quotas without requiring separate cluster deployments per tenant. Target: 10K/s aggregate writes across all tenants, with per-tenant rate limiting and fair scheduling.

## Decision
We adopt **namespace-based multi-tenancy** as a first-class architectural primitive, integrated with Multi-Raft shard groups:

### Namespace Model
- A **namespace** is the top-level isolation boundary. Every config key belongs to exactly one namespace.
- Key format: `/{namespace}/{scope}/{key_path}`. Example: `/team-payments/regional/feature.flags.checkout`.
- The `default` namespace exists on every cluster. Keys without explicit namespace belong to `default`.

### Isolation Guarantees per Namespace

| Dimension | Isolation Mechanism |
|---|---|
| **Key visibility** | Keys in namespace A are invisible to clients authenticated to namespace B. No cross-namespace reads. |
| **Write authorization** | ACL policies are namespace-scoped. A token grants permissions within one or more namespaces. |
| **Rate limiting** | Per-namespace write rate limit (configurable, default: 10K writes/s — matches the wired global limiter, see F-0054). Enforced at control plane API before Raft proposal. |
| **Storage quota** | Per-namespace key count limit and total value size limit. Enforced at state machine apply time. |
| **Raft group affinity** | Namespaces can be pinned to specific Raft shard groups for performance isolation. |
| **Subscription isolation** | Edge nodes subscribe to prefixes within their authorized namespaces only. Plumtree fan-out filters events by namespace. |

### Multi-Raft Integration
- Multi-Raft naturally supports tenant-scoped shard groups. A namespace can be assigned to its own Raft shard group, providing:
  - **Write throughput isolation:** One tenant's write burst does not affect another tenant's commit latency.
  - **Storage isolation:** Per-shard storage limits map to per-namespace quotas.
  - **Failure isolation:** A shard group failure (e.g., leader election) affects only the namespaces assigned to that group.
- Small namespaces can share a shard group (multi-tenant shard). Large namespaces get dedicated shard groups.
- Shard assignment managed by PlacementDriver. Automatic splitting when a shared shard exceeds its write throughput budget.

### Namespace Lifecycle
1. **Create:** Admin API call. Creates namespace metadata entry in the system namespace (`/_system/namespaces/{name}`). Consensus operation.
2. **Configure:** Set rate limits, storage quotas, shard affinity, default scope, ACL policy root. Consensus operation.
3. **Active:** Normal operation. Clients authenticate and are authorized to specific namespaces.
4. **Drain:** Soft-delete. New writes rejected. Existing reads continue. Grace period (default: 7 days) before data deletion.
5. **Delete:** All keys in namespace deleted. Shard group freed for reuse. Consensus operation.

### ACL Model
- Tokens carry namespace claims: `{namespace: "team-payments", permissions: ["read", "write", "admin"]}`.
- Cross-namespace access requires explicit grant (e.g., a monitoring service reading metrics from all namespaces).
- System namespace (`/_system`) requires elevated permissions — not accessible by regular tenant tokens.

## Influenced by
- **Kubernetes Namespaces:** Isolation boundary for resources, RBAC policies, and resource quotas within a shared cluster. Proven model for multi-tenancy at scale.
- **Consul Admin Partitions (Enterprise):** Demonstrates the need for multi-tenancy in service mesh / config systems. Bolted onto the architecture post-hoc, requiring Enterprise licensing. Our design builds it in from the start.
- **CockroachDB Multi-Tenant Architecture:** Tenant-scoped SQL virtual clusters sharing physical KV infrastructure. Per-tenant resource governance (CPU, storage) via admission control.
- **Kafka Multi-Tenancy (KIP-36, KIP-580):** Topic-level quotas, ACLs, and resource isolation. Demonstrates that multi-tenancy in distributed log systems requires quotas at every layer (network, disk, CPU).

## Reasoning

### Why namespaces instead of separate clusters?
Separate clusters per tenant multiply operational burden: N clusters = N deployments, N monitoring setups, N upgrade cycles, N failure domains to manage. Resource utilization drops because each cluster must be provisioned for peak load independently. With namespace-based multi-tenancy, a single cluster serves all tenants with shared infrastructure, and resources are dynamically allocated based on actual usage.

At 100 tenants with separate clusters: 100 x 3 Raft voters minimum = 300 nodes. With namespace-based multi-tenancy: 3-15 nodes (depending on shard count) serving all 100 tenants. 20-100x infrastructure cost reduction.

### Why namespace-scoped Raft shard groups?
Without shard affinity, all tenants share the same Raft groups. One tenant's write burst (e.g., bulk config update) blocks other tenants' writes because Raft serializes all proposals. Namespace-scoped shard groups provide write throughput isolation: tenant A's burst only affects tenant A's shard group's commit latency.

### Why not partition by scope only (ADR-0002)?
Scope-based partitioning (GLOBAL/REGIONAL/LOCAL) determines Raft group placement for latency optimization. Namespace partitioning is orthogonal — it provides tenant isolation within each scope tier. A namespace's GLOBAL keys go to the global Raft group (or a tenant-specific global group for large tenants), and its REGIONAL keys go to regional groups. The two dimensions compose naturally.

### Why rate limiting at API layer, not Raft layer?
Raft proposal rejection is expensive — it consumes leader CPU to deserialize and reject. Rate limiting at the API layer (before proposal) is cheaper: a counter check (~10ns) vs Raft proposal + rejection (~1ms). Additionally, API-layer rate limiting can provide informative error messages (HTTP 429 with `Retry-After` header and per-namespace quota information).

## Rejected Alternatives
- **Separate clusters per tenant:** 20-100x infrastructure cost increase. Multiplicative operational burden. Underutilized resources per cluster. No resource sharing or dynamic allocation.
- **Key prefix convention (no enforcement):** Relies on clients self-isolating by key prefix. No ACL enforcement, no rate limiting, no storage quotas. A misbehaving client in one "namespace" can read/write another's keys. Not suitable for production multi-tenancy.
- **Database-per-tenant (CockroachDB v1 model):** Separate physical storage per tenant. Eliminates resource sharing benefits. CockroachDB itself moved to virtual clusters for multi-tenancy, recognizing the overhead of physical isolation.
- **Consul Admin Partitions model (bolt-on):** Adding multi-tenancy after the architecture is designed leads to incomplete isolation. Consul's partitions do not isolate gossip (requires Network Segments, also Enterprise), do not isolate Raft (shared server resources), and do not provide per-partition rate limiting. Architectural primitive is superior to bolt-on.

## Consequences
- **Positive:** Tenant isolation without infrastructure multiplication. Per-namespace rate limiting prevents noisy-neighbor effects on write throughput. Shard affinity provides write isolation for high-value tenants. ACL model prevents cross-tenant data access. Namespace quotas prevent individual tenants from exhausting cluster storage.
- **Negative:** Namespace metadata adds overhead to every key lookup (namespace prefix matching). Cross-namespace operations (e.g., admin reading all namespaces) require special handling. Shard assignment decisions add PlacementDriver complexity.
- **Risks and mitigations:** Namespace metadata lookup overhead mitigated by caching namespace ACL decisions per connection (amortized to ~0ns per read after first request). Shard assignment complexity mitigated by simple default policy (all namespaces share default shard group; explicit opt-in for dedicated shards). Tenant escape (accessing another tenant's data) mitigated by namespace enforcement at both API layer (ACL check) and state machine layer (key prefix validation) — defense in depth.

## Reviewers
- principal-distributed-systems-architect: ✅
- security-engineer: ✅
- site-reliability-engineer: ✅
