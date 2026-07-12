# Configd versus etcd, Consul, ZooKeeper, Spring Cloud Config

An honest positioning page. etcd, Consul, and ZooKeeper are mature, battle-tested systems with large
ecosystems; Configd is young and has exactly one conforming client (Java). If you need what they are
good at, use them. This page exists because Configd makes one trade none of them make — and if that
trade is your workload, the rest of the table follows from it.

**The trade:** Configd separates the write path (Raft, linearizable, durable) from the read path
(an in-process, lock-free cache in your application's own memory, fed by asynchronous fan-out).
A read is a volatile load plus a hash-trie traversal on your own thread — no network hop, no server
round trip — at the price of explicit bounded staleness. etcd, Consul, and ZooKeeper all serve reads
over the network from the cluster (or a follower/agent), typically in hundreds of microseconds to
milliseconds; Configd serves them from process-local memory in microseconds, and tells you honestly
via headers and a staleness state machine how far behind you might be.

## Feature comparison

| | Configd | etcd | Consul | ZooKeeper | Spring Cloud Config |
|---|---|---|---|---|---|
| Primary purpose | Config distribution | Coordination KV (backs Kubernetes) | Service discovery + mesh + KV | Coordination primitives | Config serving from Git/Vault |
| Consensus | Raft (embedded, no external coordinator) | Raft | Raft | ZAB | None — stateless server over a backend |
| Default read | Bounded-stale, in-process at the edge, marked `X-Consistency: stale` | Linearizable over the network | Default-consistent via leader (stale opt-in) | Sequentially consistent from any server | Whatever the backend returns |
| Linearizable read | Opt-in (`?consistency=linearizable`), fail-closed; always-on for `secure/` keys | Default | `?consistent` mode | `sync()` then read | No |
| Watches | Yes — per-key/per-shard order, at-least-once + dedup, multi-shard | Yes — global revision order | Blocking queries | One-shot watches, re-arm | Poll / refresh events |
| Transactions | **No** | Mini-transactions (txn) | Check-and-set, txn API | Multi-op | No |
| Locks / leases / sessions | **No — deliberately** | Leases, elections, locks | Sessions, locks | Ephemeral + sequential znodes | No |
| Range / list queries | **No** (point lookups only) | Yes | Yes (prefix) | Children listing | N/A |
| Data model | Opaque bytes, hierarchical path keys, ≤ 1 MiB values | Flat binary KV, MVCC revisions | KV + service catalog | Znode tree | Files/properties |
| Multi-region | **No — single region by design** (ADR-0030/0031) | Single cluster (mirroring add-ons) | Multi-datacenter federation | Single ensemble (observers) | Yes (stateless) |
| AuthN/AuthZ | Basic, Bearer, mTLS, OIDC/JWT; ACLs with deny-precedence; ADMIN-gated reserved keys | RBAC + TLS | ACL system | SASL/digest ACLs | Delegated (Spring Security) |
| At-rest protection | HMAC integrity by default; opt-in AES-256-GCM + KMS (Vault) | None built-in | Optional gossip/TLS, no at-rest encryption of KV | None built-in | Backend's |
| Clients | Java reference client + stand-alone wire RFC; plain HTTP for everything else | Many languages (gRPC) | Many languages + DNS | Java-centric (+ Curator) | JVM/Spring |
| Maturity | Young; measured envelope published, limitations documented | Battle-tested at massive scale | Battle-tested | Two decades in production | Mature in the Spring ecosystem |

## Choose something else if…

- **You need coordination** — locks, leader election, ephemeral presence, leases. That is etcd or
  ZooKeeper's core competence, and Configd does not do it at all.
- **You need service discovery or a service mesh** — that is Consul.
- **You need transactions or range scans** — etcd's MVCC + txn model is built for it; Configd is
  point-lookup only.
- **You need multi-region write consensus** — Configd rejected that topology on purpose
  (ADR-0030/0031); Consul federates across datacenters.
- **You need a mature non-JVM client today** — etcd and Consul have them; Configd has a wire RFC you
  would have to implement (or use plain HTTP without watches).
- **Your "config" is really files in Git** — Spring Cloud Config's model (config as versioned files,
  served stateless) may be a better fit than any consensus store.

## Choose Configd if…

- Your workload is **config distribution**: written rarely and read constantly, on hot paths where a
  network round trip per read is unacceptable and a bounded-stale in-process read is exactly right.
- You want **linearizable writes with honest reads** — every response tells you whether it was
  linearizable or stale, staleness is tracked as a state machine (CURRENT → STALE → DEGRADED →
  DISCONNECTED), and read-your-writes is available when you ask for it.
- You want a **security-critical key class**: `secure/` keys are never served stale — linearizable or
  fail-closed — for revocations, kill-switches, and legal gates.
- You value **measured claims over adjectives**: failover, staleness bounds, throughput knees, and a
  faulted-linearizability matrix are published with their test setups in
  [`docs/measurement/`](../measurement/), and what is *not* measured is listed in
  [known limitations](../operations/known-limitations.md).
- You are on the **JVM**, or content with plain HTTP plus the documented wire protocol.

## The blunt version

Configd is not an etcd replacement, and etcd is not a Configd replacement. etcd is a general
coordination kernel that many systems (including Kubernetes) build on; Configd is a special-purpose
config plane that trades away coordination features, range queries, and ecosystem breadth to make
one read pattern — process-local, microsecond, honestly-stale — first-class, with linearizable
writes behind it. If you are unsure which you need, you probably need etcd.
