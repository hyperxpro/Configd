# Research Synthesis — Next-Generation Global Configuration Distribution System

> **Phase 1 deliverable.** Every claim is sourced or derived from cited mechanisms.
> Reviewed by: principal-distributed-systems-architect, distributed-systems-researcher, formal-methods-engineer.

---

## Table of Contents

1. [Consensus & Replication Protocols](#1-consensus--replication-protocols)
2. [Coordination Systems](#2-coordination-systems)
3. [Modern Production Systems](#3-modern-production-systems)
4. [Global Scale Constraints](#4-global-scale-constraints)
5. [Formal Verification](#5-formal-verification)
6. [Cross-System Insights](#6-cross-system-insights)
7. [Tradeoffs Table](#7-tradeoffs-table)

---

## 1. Consensus & Replication Protocols

### 1.1 Raft (Ongaro & Ousterhout, ATC 2014)

- **Core model:** Leader-based replicated state machine over an asynchronous network with crash-fault tolerance. A cluster of 2f+1 nodes tolerates f crash failures. Decomposes consensus into leader election (randomized timeouts), log replication (AppendEntries RPC), and safety (restrictions on which servers may be elected). A single leader per term serializes all client requests. Terms act as logical clocks, monotonically increasing, with at most one leader per term.

- **Safety invariants** (Figure 3, original paper):
  1. *Election Safety:* At most one leader per term. Each server votes for at most one candidate per term; strict majority required.
  2. *Leader Append-Only:* A leader never overwrites or deletes log entries.
  3. *Log Matching:* If two logs contain an entry with same index and term, they store the same command and all preceding entries are identical. Enforced by AppendEntries consistency check.
  4. *Leader Completeness:* If a log entry is committed in a given term, it is present in all future leaders' logs. Proved by quorum intersection between committing majority and electing majority.
  5. *State Machine Safety:* If a server applies entry at index i, no other server applies a different entry at i.

  **Critical commit rule:** Leader only commits entries from its current term (§5.4.2, Figure 8). Prevents overwrite of prior-term entries that appear committed by count.

- **Liveness assumptions / FLP boundary:** No liveness in pure asynchrony (FLP, 1985). Relies on partial synchrony: `broadcastTime << electionTimeout << MTBF`. Howard et al. (2020, "Raft does not Guarantee Liveness in the face of Network Faults") showed Raft loses liveness under partial connectivity even with majority non-faulty:
  - Disconnected minority servers inflate terms, forcing leader step-downs.
  - **Resolution:** PreVote + CheckQuorum together restore liveness. PreVote prevents term inflation from partitioned nodes (§9.6, Ongaro dissertation). CheckQuorum forces leader step-down if no majority heartbeat within election timeout.
  - **Leadership Transfer** (§3.10, dissertation): Leader catches up designated successor, sends TimeoutNow RPC for immediate election. Falls back on timeout.

- **Reconfiguration correctness:**
  - *Joint Consensus* (§6): Two-phase — C_old,new requires agreement from both old and new majorities. Safe but complex.
  - *Single-Server Changes* (dissertation Ch. 4): Add/remove one server at a time, guaranteeing quorum overlap. **Known bug:** Leader can begin replicating a new config while an uncommitted config from a prior term exists, breaking the single-server-difference invariant. **Fix:** Leader must commit a no-op in its current term before processing any config change (Ongaro, raft-dev mailing list, 2015).
  - *MongoRaftReconfig* (Schultz et al., OPODIS 2021): Logless dynamic reconfiguration verified in TLA+ with TLAPS safety proof. Decouples config from operation log.

- **Quorum cost / commit-path latency:** Majority quorum (⌊N/2⌋+1). Steady-state: 1 RTT (leader → followers → leader). CockroachDB achieves 2-10ms intra-region per Raft range. With batching/pipelining (etcd), ~2.4× throughput improvement.

- **Strengths:** Understandability (primary design goal, validated in classroom studies §9.1). Strong leader simplifies reasoning. Extensive production deployments (etcd, Consul, CockroachDB, TiKV). Follower reads via ReadIndex (2 message delays) or LeaseRead (zero extra RTT, bounded clock skew assumption).

- **Failure modes at scale:**
  - *Disruptive candidate / term inflation:* Partitioned node rejoining with inflated term forces leader step-down. Cloudflare 2020 incident: 6 hours of impact from 6 minutes of etcd unavailability. Mitigated by PreVote.
  - *Stale leader reads:* Without CheckQuorum or ReadIndex, a deposed leader serves stale reads.
  - *PreVote migration deadlock:* Rolling upgrade from CheckQuorum-only to PreVote-only can deadlock mixed-version clusters (etcd issue #8501).
  - *InstallSnapshot saturation:* Snapshot transfer can saturate network when followers lag significantly.
  - *Gray failures:* Leader heartbeats pass but data replication fails silently. CheckQuorum does not detect this.

- **What we will steal / reject and why:**
  - **STEAL:** PreVote + CheckQuorum (mandatory). ReadIndex for linearizable reads. Single-server reconfig with no-op fix. Leadership transfer for graceful maintenance. Multi-Raft pattern (CockroachDB/TiKV) for horizontal scaling. Non-voting replicas for remote region reads.
  - **REJECT:** Naive joint consensus (too complex for config distribution). Leader-only reads in WAN context (need follower/lease reads). Single monolithic Raft group spanning all regions.

### 1.2 Paxos / Multi-Paxos / Fast Paxos (Lamport)

- **Core model:**
  - *Single-Decree Paxos* ("The Part-Time Parliament," 1998; "Paxos Made Simple," 2001): Consensus on a single value among N acceptors tolerating f < N/2 crashes. Three roles: proposers, acceptors, learners. Two-phase: Phase 1 (Prepare/Promise) establishes proposer's right; Phase 2 (Accept/Accepted) commits value.
  - *Multi-Paxos:* Sequence of consensus instances. Stable leader skips Phase 1, reducing steady-state to single Phase 2 RTT.
  - *Fast Paxos* (Lamport, 2006): Clients send Accept directly to acceptors — 2 message delays on fast path. Requires N ≥ 3f+1 and fast-path quorum of ⌈(3N-1)/4⌉. Falls back to classic path on collision.

- **Safety invariants:**
  1. All ballot numbers are unique and totally ordered.
  2. Value selection rule: if proposal (v, b) is issued to majority C, v must equal the highest-ballot previously-accepted value in C (if any exist).
  - Safety theorem: If value v is chosen with ballot b, every higher-ballot proposal also proposes v. Proof by quorum intersection.
  - **Safety is unconditional** — holds regardless of timing, message loss, or duplication.

- **Liveness assumptions:** Per FLP, no guaranteed termination in async. Dueling proposers can cause indefinite delay. Solved by randomized backoff or stable leader election (failure detector Ω).

- **Reconfiguration correctness:** No built-in mechanism. Lamport's "Reconfiguration" paper (2010) proposes using a Paxos instance to agree on config changes.

- **Quorum cost / commit-path latency:**
  - Classic: 2 RTTs (Phase 1 + Phase 2). Phase 1 amortized in Multi-Paxos.
  - Multi-Paxos steady state: 1 RTT. Spanner achieves 5-15ms intra-region.
  - Fast Paxos fast path: 2 message delays. Requires N ≥ 3f+1.

- **Strengths:** Proven optimal for safety. Flexible role separation. Foundation for Spanner, Chubby, Megastore.

- **Failure modes at scale:** Dueling proposers/livelock (documented in Chubby paper). "Paxos Made Live" (Chandra et al., PODC 2007) documents the enormous gap between theory and production. Fast Paxos collision storms under contention.

- **What we will steal / reject and why:**
  - **STEAL:** Multi-Paxos Phase 1 amortization principle (Raft does this with stable leader). Phase 1/Phase 2 quorum size separation (leads to Flexible Paxos).
  - **REJECT:** Fast Paxos (3f+1 requirement costly; config writes low-frequency enough that 1 RTT suffices). Raw Paxos implementation (prefer Raft's equivalent formulation per Howard & Schwarzkopf, PaPoC 2020).

### 1.3 EPaxos / Egalitarian Paxos (Moraru, Andersen, Kaminsky, SOSP 2013)

- **Core model:** Leaderless state machine replication. Every replica can coordinate any command. Commands organized into a dependency graph. Two commit paths:
  - *Fast path (1 RTT):* Coordinator sends PreAccept to fast-path quorum of f + ⌊(f+1)/2⌋. If all agree on dependencies, committed.
  - *Slow path (2 RTTs):* On dependency disagreement, coordinator merges dependency sets, runs Accept phase, then commits.
  - Execution: compute SCCs of dependency graph, sort in reverse topological order, within each SCC order by sequence number.

- **Safety invariants:**
  1. *Agreement:* All replicas agree on a committed command's dependencies and payload.
  2. *Visibility:* Conflicting committed commands see each other in their dependency sets.

- **Liveness assumptions:** Partial synchrony. Known livelock: interfering commands create unbounded dependency chains. Recovery can deadlock even with finitely many commands (Ryabinin et al., 2025).

- **Quorum cost / commit-path latency:** Fast path: 1 RTT to nearest quorum (key WAN advantage — coordinator is closest replica to client). For N=5 across WAN: fast path = RTT to 2nd-closest replica, significantly less than Raft's median. Example: client in ap-southeast-1 coordinating: EPaxos 159ms vs Raft (leader in us-east-1) 288ms total.

- **Strengths:** Optimal WAN latency when conflicts rare. No single latency bottleneck. Exploits command commutativity.

- **Failure modes at scale:**
  - *Ballot management bug* (Sutra, 2019): Single ballot variable insufficient — can "forget" accepted dependency set during recovery, breaking linearizability. Fix: two ballot variables.
  - *TentativePreAccept deadlock:* State mutation before safety checks creates circular waiting.
  - *Fast-quorum check bug:* Reference implementation has incorrect fast quorum check (GitHub issue #10).
  - *Dependency graph explosion:* Under sustained high-conflict workloads, SCCs grow large, stalling execution.

- **Reconfiguration correctness:** EPaxos has no built-in reconfiguration mechanism. Membership changes require an external mechanism or a full stop-and-restart procedure. This is an additional operational concern that compounds the protocol's already high complexity and lack of production deployment experience.

- **What we will steal / reject and why:**
  - **STEAL:** Nearest-replica coordination *principle* for WAN — implement via Raft follower reads or WPaxos-style object stealing. Command commutativity awareness (most config keys are independent).
  - **REJECT:** Full EPaxos — too complex, multiple known bugs, no production track record, dependency graph operationally opaque.

### 1.4 Flexible Paxos (Howard, Malkhi, Spiegelman, OPODIS 2016)

- **Core model:** Classical Paxos requires both Phase 1 and Phase 2 quorums to be majorities. FPaxos proves the actual requirement is: **Q1 ∩ Q2 ≠ ∅**, i.e., |Q1| + |Q2| > N. Phase 1 and Phase 2 can use different-sized quorums as long as they intersect.

- **Safety invariants:** Same as Paxos. Proof (Theorem 2): Phase 1/Phase 2 intersection guarantees value discovery across proposals.

- **Liveness assumptions:** Same as Paxos. Trades leader election availability for steady-state performance: small Q2 tolerates more failures during normal operation, but leader election (large Q1) becomes harder.

- **Quorum cost / commit-path latency:**
  - N=10: Q1=8, Q2=3. Commits need only 3 acks (fast); leader election needs 8 (rare).
  - N=5: Q1=4, Q2=2. Commits need only 1 follower ack (nearest) vs majority (2nd-nearest).
  - Prototype (8 replicas): latency 42ms → 37ms; throughput 198 → 264 req/s with smaller Q2.
  - **WPaxos** (Ailijiang et al., IEEE TPDS 2019): Multi-leader Paxos with flexible quorums and object stealing. Evaluated across 5 AWS regions.

- **Reconfiguration correctness:** FPaxos inherits Paxos's reconfiguration approach — a separate consensus instance to agree on configuration changes. The asymmetric quorum sizes do not introduce new reconfiguration concerns beyond ensuring that both Q1 and Q2 are updated consistently when membership changes.

- **Failure modes at scale:** The primary failure mode is aggressive Q2 reduction. With Q2=2 on a 5-node cluster, a single acceptor failure during steady-state blocks progress until leader election (requiring the larger Q1) completes. The smaller the Q2, the more fragile the steady-state commit path becomes, trading availability for latency.

- **What we will steal / reject and why:**
  - **STEAL:** Asymmetric quorum insight for WAN config distribution. Small Q2 for fast regional commits, large Q1 for rare elections. WPaxos object-stealing for write-hot keys.
  - **REJECT:** Aggressive Q2=1 reduction (single acceptor failure blocks progress).

### 1.5 Hierarchical / Federated Raft Variants

- **Core model:** Architectural decomposition instead of single global Raft group:
  - *Multi-Raft (TiKV, CockroachDB):* Keyspace partitioned into ranges, each with its own Raft group. Strategic placement — voting replicas nearby, non-voting in distant regions.
  - *Non-voting replicas (CockroachDB):* Follow Raft log for read serving without affecting write quorum latency.
  - *Regional table pinning (CockroachDB):* REGIONAL BY TABLE pins leaseholders/voters to a region. ZONE survival: 2-5ms. REGION survival: 20-30ms.
  - *GLOBAL tables:* Voting replicas in primary region, non-voting everywhere. Writes via consensus in primary; reads served locally with bounded staleness.

- **Quorum cost / commit-path latency:**
  - ZONE survival (3 replicas, same region): 2-5ms.
  - REGION survival (5 replicas, multi-region): 20-30ms (1 cross-region RTT).
  - Non-voting replica lag to furthest region: ~100-125ms.

- **Safety invariants:** Safety properties compose from the underlying Raft groups. Each individual group maintains Raft's standard safety invariants (election safety, log matching, leader completeness, state machine safety). Cross-group safety depends on the coordination layer (e.g., PD in TiKV, leaseholder in CockroachDB) to ensure consistent routing and epoch-based stale request detection.

- **Liveness conditions and FLP boundary:** Liveness similarly composes from the underlying Raft groups — each group independently requires partial synchrony for leader election. The hierarchical coordinator (PD or equivalent) is itself a Raft group subject to FLP. A coordinator failure temporarily halts scheduling and rebalancing but does not affect in-progress reads or writes to existing groups.

- **Reconfiguration correctness:** Reconfiguration is performed per-group using the underlying Raft reconfiguration mechanism (joint consensus or single-server changes). The coordinator orchestrates membership changes across groups but each group independently ensures reconfiguration safety. Adding or removing an entire region involves coordinating replica placement across many groups, which is an availability concern rather than a safety one.

- **Known production failure modes:** Coordinator (PD/leaseholder) failure temporarily stalls scheduling and region rebalancing. Cross-group ordering ambiguity arises when operations span multiple Raft groups without an external transaction protocol — CockroachDB addresses this with its transaction layer, TiKV with Percolator-style 2PC. Hot regions can create load imbalance if the coordinator's splitting/merging logic lags behind traffic shifts.

- **What we will steal / reject and why:**
  - **STEAL:** Multi-Raft with per-key-range groups. Non-voting replicas for remote reads. Regional pinning for write-hot config keys.
  - **REJECT:** Single monolithic Raft group spanning all regions.

### 1.6 Viewstamped Replication (Oki & Liskov, 1988; Liskov & Cowling, 2012)

- **Core model:** Primary-backup with view-based leadership. Deterministically assigned primary (round-robin by replica index — no election campaign). Primary serializes operations with monotonic op-nums, replicates via PREPARE messages. View change protocol selects most up-to-date log from responding replicas.

- **Safety invariants:** Operations applied in op-num order. Committed operations never lost across view changes.

- **Quorum cost / commit-path latency:** Same as Raft: 1 RTT. Primary waits for f+1 responses. 10 message types total (vs Raft's 4 RPCs).

- **Liveness conditions:** VR is subject to the same FLP impossibility constraints as Raft and Paxos — liveness requires partial synchrony. View changes rely on a majority of replicas detecting primary failure via timeouts and coordinating to install a new view. Cascading view changes can occur if the newly elected primary is also unreachable, delaying recovery.

- **Reconfiguration correctness:** VR handles reconfiguration through its view change mechanism — a new configuration is proposed as part of a view change, requiring agreement from a majority of the current configuration. This is analogous to Raft's joint consensus but integrated into the view change protocol rather than the log.

- **Known production failure modes:** VR has seen limited modern production deployment. The historical Harp file system (Liskov et al.) used VR but predates modern distributed systems practice. The deterministic primary assignment, while simplifying leader election, means a predictable failure sequence can cascade through replicas in order. No large-scale production failure data is publicly available.

- **What we will steal / reject and why:**
  - **STEAL:** Deterministic primary assignment as inspiration for predictable failover routing.
  - **REJECT:** The protocol itself — Raft provides equivalent safety with fewer message types and larger ecosystem (per Howard & Schwarzkopf, 2020, they are the same algorithm with different leader election).

---

## 2. Coordination Systems

### 2.1 ZooKeeper

- **Core model:** Hierarchical namespace (znodes) with replicated state machine using ZAB (ZooKeeper Atomic Broadcast) — a primary-backup protocol distinct from Paxos and Raft. All state in-memory with WAL and periodic snapshots. Znodes: persistent, ephemeral (session-lifetime), or sequential. Typical ensemble: 3-5 replicas.

- **Ordering guarantees:**
  - Writes: Linearizable. All mutations totally ordered through leader via ZAB. Monotonically increasing zxid.
  - Reads: Sequentially consistent within a session, NOT linearizable by default. Followers may serve stale data. `sync` before read achieves linearizable reads at cost.
  - Session: FIFO client ordering.

- **Watch vs poll semantics:** One-shot watch model (pre-3.6): server sends one notification per znode change, watch consumed. Client must re-register — creates event loss window. Since ZK 3.6 (ZOOKEEPER-1416): persistent recursive watches that fire for all event types without auto-removal.

  **Critical cost:** Each traditional watch ~100 bytes server memory (ZOOKEEPER-1177). 10K clients × 20K znodes = 200M watches = ~20 GB RAM. Proposed bitmap optimization reduces to ~120 MB.

- **Write throughput ceiling:**
  - Original paper (Hunt et al., USENIX ATC 2010): ~21,000 ops/sec write-only on 3-server ensemble.
  - Read-only: ~87,000 ops/sec on 3 servers, scaling linearly with ensemble size.
  - Meta (Zelos comparison): ZK 36K ops/sec mixed vs Zelos 56K ops/sec on identical hardware.
  - **Fundamental ceiling:** All writes through single leader. Adding servers *decreases* write throughput.

- **Strengths:** Extremely fast reads (in-memory, local). Battle-tested 15+ years. Ephemeral nodes for failure detection. Observer nodes for read scaling.

- **Failure modes at scale:**
  - Write bottleneck: single-leader, non-scalable.
  - Session storms: reconnection after partition saturates write pipeline (Twitter Engineering, 2018).
  - Ephemeral node leaks: ZOOKEEPER-4837, ZOOKEEPER-1809, ZOOKEEPER-3890.
  - GC pauses: 500ms pause triggers session timeouts, cascading ephemeral deletions.
  - Watch memory explosion: 200M watches = 20 GB heap.

- **What we will steal / reject and why:**
  - **STEAL:** Ephemeral node concept (session-lifetime keys). Observer node pattern. Persistent recursive watches.
  - **REJECT:** Single-leader write path. JVM runtime (GC pauses existential threat). One-shot watch model.

### 2.2 Chubby (Google, Burrows, OSDI 2006)

- **Core model:** Lock service providing coarse-grained advisory locks + small reliable storage. 5 replicas using Paxos, single master. Throughput explicitly a non-goal. 93% of RPCs are KeepAlives.

- **Ordering guarantees:** Linearizable — all reads and writes through master. Write-through cache with master-driven invalidation.

- **Watch vs poll semantics:** Event-driven callbacks piggybacked on KeepAlive RPCs. Master delays KeepAlive response, attaches invalidations. Blocks mutation until all caching clients acknowledge. Push model without separate watch connections.

- **Write throughput ceiling:** Not published (non-goal). 90,000 concurrent clients per cell. Scales via namespace sharding across cells.

- **Key mechanisms:**
  - **Sequencer/fencing:** Lock acquisition returns opaque byte-string (name + mode + generation number). Downstream servers validate to detect stale locks.
  - **Grace period:** 45-second grace on session expiry — tolerance for brief master failovers.

- **What we will steal / reject and why:**
  - **STEAL:** KeepAlive-piggybacked event delivery (eliminates separate watch connections). Sequencer/fencing pattern. Grace period on session expiry. Coarse-grained lock philosophy.
  - **REJECT:** Cache invalidation blocking writes (doesn't scale to millions of subscribers).

### 2.3 etcd

- **Core model:** Raft-based distributed KV store. BoltDB/bbolt with MVCC — every write creates a new revision. gRPC v3 API. 3-5 nodes. Backbone of Kubernetes.

- **Ordering guarantees:**
  - Writes: Linearizable via Raft leader.
  - Reads: Linearizable (default, via ReadIndex, ~0.7ms) or Serializable (local store, ~0.3ms, may be stale).
  - Global revision counter provides total order across all keys.

- **Watch vs poll semantics:** gRPC streaming watches. Persistent, multiplexed on single stream per connection. Revision-based resumption. Progress notifications for liveness. Memory cost: ~17 KB/connection, ~18 KB/stream, ~350 bytes/watching instance. At 1M watches: ~2.4 GB. gRPC proxy coalesces duplicate watches.

- **Write throughput ceiling:** (3× 8 vCPU, 16 GB, 50 GB SSD):
  - 1 client to leader: 583 QPS, 1.6ms.
  - 1000 clients distributed: 50,104 QPS, 20ms.
  - etcd v3.6.0 (May 2025): ~10% throughput improvement, 50% memory reduction.
  - **Caveat:** Benchmarks use 256-byte values. Real Kubernetes objects are 10-100 KB — throughput drops dramatically.

- **Failure modes at scale:**
  - Database size ceiling: 8 GB suggested max. `mvcc: database space exceeded` halts all writes.
  - MVCC revision explosion without compaction.
  - Watch reconnection storms: node restart forces mass watch re-establishment, CPU spikes (Discussion #18381).
  - BoltDB freelist fragmentation: defrag is O(N) and blocks writes.
  - **v3.5 data inconsistency** (Issue #13766): Race condition where consistent index updated before WAL entries applied. 10 months of production exposure (v3.5.0-v3.5.2).

- **What we will steal / reject and why:**
  - **STEAL:** gRPC streaming watches with revision-based resumption. MVCC revision model. gRPC proxy coalescing. Progress notifications.
  - **REJECT:** BoltDB (single-writer, fragmentation). 8 GB ceiling. No horizontal write scaling.

### 2.4 Consul

- **Core model:** Hybrid — gossip (Serf/SWIM) for membership + Raft for strongly consistent state (KV, service catalog). Within DC: LAN gossip pool. Across DCs: WAN gossip pool. Only server agents (3-5) participate in Raft. KV backed by MemDB (in-memory MVCC).

- **Ordering guarantees:** Three read modes: *default* (leader leasing, bounded stale), *consistent* (leader verification with quorum, linearizable), *stale* (any server, lowest latency).

- **Watch vs poll semantics:** Blocking queries (long-poll) — client holds HTTP connection with `?wait=` parameter. No multiplexing. Each watched key requires a separate HTTP connection.

- **Write throughput ceiling:** ~10-15K QPS. Values max 512 KB. Recommended total KV < 1 GB. Max ~250K keys without issues.

- **Strengths:** First-class WAN federation via WAN gossip and mesh gateways. Cluster peering. Sub-second gossip failure detection. Prepared queries for cross-DC failover.

- **Failure modes at scale:**
  - Gossip convergence: migrating 44K clients took 2 hours. Stability degrades above 5,000 nodes per pool.
  - Network partition split-brain: Issue #4084 — separated server enters endless election loop, persists after recovery.
  - KV store limits: 512 KB values, 1 GB total, 250K keys.
  - Blocking query inefficiency: O(N) HTTP connections for N watched keys.

- **What we will steal / reject and why:**
  - **STEAL:** WAN federation architecture. Gossip-based membership/failure detection. Network segments for gossip isolation. Cluster peering.
  - **REJECT:** Blocking queries (connection-inefficient). 5,000-agent gossip ceiling. 2-hour convergence. KV size limits.

---

## 3. Modern Production Systems

### 3.1 Cloudflare Quicksilver (PRIMARY BASELINE)

- **Core model / architecture:** Globally replicated KV store for config distribution. Evolved through v1 (full replication, LMDB), v1.5 (replica/proxy split, RocksDB), v2 (three-tier caching). Hierarchical fan-out tree — NOT consensus at edge. Raft-based root cluster (etcd Raft library) for write durability. Async replication fans changes through tree to edge. ~10 Quicksilver instances per server (one per service).

  V2 three-tier caching within each DC:
  - **L1:** Per-server local cache (recently accessed KV pairs)
  - **L2:** DC-wide sharded cache (1,024 logical shards)
  - **L3:** Full dataset on dedicated storage replicas (RocksDB)
  - **Reactive prefetching:** One cache miss fills entire DC

- **Write path:** Producer → QSrest (ACL enforcement, per-producer quotas) → batch every 500ms → Raft root cluster (durability, sequence numbers, CRC checksums) → intermediate nodes pull transaction logs → leaf/edge nodes pull from local DC intermediates.

- **Read path:** L1 cache → L2 sharded cache → L3 storage replica. Reactive prefetching broadcasts resolved misses to all proxies in DC.

- **Consistency guarantees:** Eventual consistency with sequential ordering. Monotonically increasing sequence numbers detect gaps. MVCC with ~2-hour retention window. Sliding window on proxies for temporal consistency.

- **Scale numbers:**
  - 5+ billion KV pairs, 1.6 TB total (growing 50%/year)
  - 3+ billion reads/second globally
  - 30 million writes/day (~350 writes/sec)
  - 330 cities, 125+ countries, 90,000+ database instances
  - 99.99%+ cache hit rate in v2

- **Latency profile:**
  - Read: average ~500μs, p90 < 1ms, p99.9 < 7ms
  - Edge propagation: "within seconds" globally
  - Write commit: batched every 500ms, Raft consensus

- **Failure handling:** 30-second disconnection threshold. Week of delta history for offline catch-up. CAS at root prevents duplicates. Serialized bootstrapping with round-robin locking prevents I/O saturation.

- **What we will steal / reject and why:**
  - **STEAL:** Hierarchical fan-out (consensus doesn't scale to 300+ cities). Reactive prefetching. MVCC sliding window. Key-value separation with Bloom filters. Monotonic sequence numbers with incremental hashing. Three-tier storage.
  - **REJECT:** Full replication (v1). LMDB (80× write amplification). Pull-only with 500ms batch windows (we can push with lower latency). Salt-based hardcoded topology.

### 3.2 TiKV / TiDB

- **Core model:** Multi-Raft distributed transactional KV. Keyspace divided into **Regions** (~96 MB each), each an independent Raft group with 3 replicas. **Placement Driver (PD)** — centralized Raft cluster managing region metadata, scheduling, timestamps.

- **Write path:** Client → lookup Region via cached PD metadata → forward to Region's Raft leader → propose as log entry → replicate to majority → apply to RocksDB → respond. Uses **RocksDB WriteBatch** to atomically persist Raft logs for multiple groups.

- **Read path:** Three modes:
  - *Leader read (ReadIndex):* Confirm leadership via heartbeat, wait for local apply catch-up. Linearizable.
  - *LeaseRead:* Time-based lease (default 10s election timeout, 9s renewal). Reads served without heartbeat during valid lease. Renewed via write operations, not heartbeats.
  - *Follower/Stale reads:* Each peer maintains **safe-ts** — timestamp below which all transactions locally applied. Reads at timestamp ≤ safe-ts served from any replica.

- **Scale numbers:** 3-node: 212K point-get/sec, 43.2K updates/sec (YCSB). Sub-10ms average latency.

- **Key innovations:** Epoch-based stale request detection (two logical clocks per Region). safe-ts for bounded-staleness follower reads. Batched Raft I/O across groups. PD-style centralized scheduling.

- **What we will steal / reject and why:**
  - **STEAL:** Epoch mechanism. safe-ts for follower reads. Batched Raft I/O. PD-style scheduling.
  - **REJECT:** Full multi-Raft at edge (too heavyweight for config). Percolator-style 2PC (no cross-key transactions needed). Centralized timestamp oracle (latency bottleneck globally).

### 3.3 Kafka KRaft (KIP-500)

- **Core model:** Embedded Raft replacing ZooKeeper for Kafka's metadata. All metadata in single-partition `__cluster_metadata` topic managed by controller quorum. Active controller (Raft leader) processes all writes. All controllers maintain in-memory caches for instant failover.

- **Write path:** Metadata change → active controller validates → append to metadata log via Raft → replicate to majority → commit → apply to in-memory cache → brokers fetch updates via MetadataFetch API.

- **Read path:** Brokers maintain local in-memory caches updated via incremental MetadataFetch. Each fetch includes last offset, returns only newer entries. If too far behind, controller sends full snapshot. MetadataFetch doubles as heartbeat.

- **Scale numbers:** 2M partitions (vs 200K with ZK — 10×). Test clusters: 5M+ partitions. Controller failover: < 1 second. Topic reassignment: 42s (vs 600s with ZK — 14×).

- **Key innovations:** Embedded consensus replacing external coordination. Event-sourced metadata log with offset tracking. Snapshot + incremental catch-up. Broker state machine (Offline/Fenced/Online/Stopping). MetadataFetch as heartbeat.

- **What we will steal / reject and why:**
  - **STEAL:** Embedded consensus (our system must own its consensus). Event-sourced log with offset tracking. Snapshot + incremental catch-up. Broker state machine. Dual-purpose heartbeat.
  - **REJECT:** Single-partition metadata log (won't scale for our keyspace). Pull-only propagation (need push for edge latency).

### 3.4 CockroachDB

- **Core model:** Geo-distributed SQL on distributed KV. Data divided into **ranges** (~64 MB each), each an independent Raft group with 3-5 replicas. One replica per range holds **range lease** (leaseholder) coordinating reads/writes. **Hybrid Logical Clocks (HLC)** instead of TrueTime. Serializable isolation with MVCC. Pebble storage engine.

- **Write path:** Client → gateway → KV operations → route to leaseholder → write intent (provisional MVCC value) → replicate through Raft → quorum ack → commit. **Parallel commits:** Transaction record marked "staged" during first round-trip; flipped to "committed" asynchronously. Single round-trip common case.

- **Read path (closed timestamp mechanism):**
  - Leaseholder continuously advances a **closed timestamp** — promise that no new writes at or below that timestamp.
  - Default: 3 seconds in the past (configurable).
  - Propagation: (1) piggybacked on Raft log entries during writes, (2) **side-transport** every 200ms for idle ranges using delta encoding.
  - Updates are `<timestamp, Lease Applied Index>` pairs — follower serves reads at timestamp T once applied through associated index.
  - **Write Tracker:** Two-bucket approximate data structure tracking minimum timestamp of in-flight writes for safe advancement without per-write locking.

- **Scale numbers:**
  - Single-row read: 1ms intra-AZ. Single-row write: 2ms intra-AZ.
  - 160K QPS write-only on 20 nodes; 1.2M QPS on 200 nodes.
  - Global tables: write latency 250-800ms.

- **MultiRaft optimization:** Coalesces heartbeats — each pair of nodes exchanges heartbeats once per tick regardless of shared range count. 3 goroutines per store (vs one per range).

- **Key innovations:** Closed timestamp with write tracker. Side-transport with delta encoding. Epoch-based lease management. MultiRaft heartbeat coalescing. Non-voting replicas. Read refresh instead of transaction restart.

- **What we will steal / reject and why:**
  - **STEAL:** Closed timestamp mechanism (directly applicable to safe stale reads at edge). Side-transport with delta encoding. Write tracker two-bucket scheme. Epoch-based leases. MultiRaft coalescing.
  - **REJECT:** Full serializable transactions (overkill). Write intents/parallel commits (no distributed transactions needed). Per-range 64 MB granularity (too fine for config).

### 3.5 Google Spanner / TrueTime

- **Core model:** Globally-distributed, strongly-consistent relational database. Data partitioned into **splits** replicated via Paxos groups. **TrueTime** provides globally-synchronized timestamps with bounded uncertainty using GPS receivers and atomic clocks. Achieves **external consistency** (linearizability) — if T1 commits before T2 starts, T1's timestamp < T2's.

- **Write path:** Request → split's Paxos leader → acquire write lock → TrueTime timestamp → replicate to majority → **commit-wait** (wait until TrueTime guarantees timestamp has passed — overlaps with Paxos replication, ~1-7ms, often zero additional latency) → respond.

- **Read path:** Strong reads at current TrueTime timestamp, sent to any replica, replica verifies catch-up. Stale reads (10s tolerance) avoid leader RPC entirely — leaders publish safe timestamps to replicas every 10s. Read-only transactions need no locks.

- **Scale numbers:** 4+ billion QPS at peak. 15+ exabytes. 99.999% availability multi-regional. TrueTime uncertainty: < 1ms p99 (down from 7ms originally).

- **Key innovations:** TrueTime + commit-wait for external consistency without distributed read locks. Stale reads as first-class optimization. Safe timestamp propagation from leaders.

- **What we will steal / reject and why:**
  - **STEAL:** Safe timestamp propagation (we can do better than every 10s). Read-only transactions as zero-coordination snapshot reads. Stale reads as first-class optimization.
  - **REJECT:** TrueTime hardware dependency (must work with NTP). Paxos per split (too heavy for 300+ locations). 2PC for cross-split transactions.

### 3.6 FoundationDB

- **Core model:** Distributed ordered KV with strict serializability. **Unbundled architecture:**
  - *Control plane:* Coordinators (Disk Paxos), ClusterController, Sequencer, DataDistributor, Ratekeeper.
  - *Transaction system (in-memory):* Proxies (client-facing), Resolvers (OCC conflict detection).
  - *Log system:* WAL replicated on f+1 LogServers.
  - *Storage system:* Redwood/RocksDB, serving reads.

- **Write path:** Client buffers writes locally → send read set + write set to Proxy → Proxy requests commit version from Sequencer → Resolvers check OCC conflicts (5-second MVCC window) → if clean, persist to f+1 LogServers → commit → StorageServers async pull and apply.

- **Read path:** Client gets read version from Proxy → Sequencer returns version ≥ all prior commits → client reads directly from StorageServers at that version → read-only transactions commit locally.

- **Scale numbers:** Read: 1ms average, 19ms p99.9. Commit: 22ms average, 281ms p99.9. MTTR: < 5 seconds. Apple iCloud: billions of databases.

- **Deterministic Simulation Testing (critical innovation):**
  - All production code runs inside a deterministic discrete-event simulator. Single physical process simulates entire cluster.
  - **Flow:** C++ language extension for actor-based concurrency. Global `g_network` pointer swaps between real (`Net2`) and simulated network.
  - **Determinism:** `deterministicRandom()` — seeded PRNG for all randomness. Same seed = same execution.
  - **BUGGIFY macro:** ~1000 locations in production code. Only fires in simulation. Injects latency, wrong error codes, corruption. Each point enabled/disabled per run.
  - **Fault injection:** Machine, rack, network, disk failures at accelerated rates (yearly events simulated every minute). Hurst exponent clusters failures.
  - **Results:** "Only 1-2 customer-reported bugs across entire company history." Found two independent ZK bugs, leading to replacing ZK with custom Paxos.

  **For Java:** No mature JVM DST framework yet. Key approaches:
  - *OpenDST* (Ping Identity): Java Agent bytecode instrumentation, intercepts `System.currentTimeMillis()`, `new Thread()`, etc. Requires Java 25+.
  - *Project Loom approach* (James Baker): Virtual threads give fine-grained execution control. Custom executor with priority queue and simulated time. 40,000 simulated Raft rounds/second.

- **What we will steal / reject and why:**
  - **STEAL:** Deterministic simulation testing (non-negotiable). BUGGIFY-style fault injection in production code. Crash-recovery philosophy (fast restart < 5s). Unbundled architecture. Client-side write buffering.
  - **REJECT:** 5-second transaction limit. Single Sequencer (centralized bottleneck). Flow/C++ (building in Java). OCC conflict detection (unnecessary for low-contention config writes).

### 3.7 MongoDB Replication

- **Core model:** Raft-inspired with significant modifications. Primary receives all writes, records in **oplog** (capped, append-only). Secondaries asynchronously replicate. Elections use term-based voting.

- **Key differences from pure Raft:**
  1. *Early log application:* MongoDB applies oplog entries BEFORE commit. Enables `readConcern: local` but requires rollback mechanism.
  2. *Pull-based replication:* Secondaries pull from primary (and can chain).
  3. *Tunable consistency:* `local` (uncommitted), `majority` (committed), `linearizable` (no-op write + majority wait), `snapshot` (transaction-scoped).

- **Key innovations:** Tunable consistency spectrum under single protocol. No-op trick for linearizable reads. OpTime versioning (timestamp + term). Oplog window for bounded catch-up.

- **What we will steal / reject and why:**
  - **STEAL:** Tunable consistency model. No-op linearizable reads. OpTime versioning. Oplog window concept.
  - **REJECT:** Early application before commit (rolled-back config = outage). Pull-only replication. Single-primary bottleneck. Max 7 voting members.

---

## 4. Global Scale Constraints

### 4.1 Cross-Region Quorum Latency Math

**Measured inter-region RTTs (AWS CloudPing + Azure):**

| Route | RTT (ms) |
|---|---|
| us-east-1 ↔ us-east-2 | 13 |
| us-east-1 ↔ us-west-2 | 57-65 |
| us-east-1 ↔ eu-west-1 | 68 |
| us-east-1 ↔ eu-central-1 | 92-93 |
| us-east-1 ↔ ap-northeast-1 (Tokyo) | 148 |
| us-east-1 ↔ ap-southeast-1 (Singapore) | 220 |
| eu-west-1 ↔ eu-central-1 | 20-22 |
| eu-west-1 ↔ ap-southeast-1 | 175-178 |
| ap-northeast-1 ↔ ap-southeast-1 | 69-73 |
| Brazil ↔ Indonesia (worst case) | 343 |

**Commit latency analysis for Raft (leader-based):**

5-node cluster, commit = RTT to 2nd-closest follower:

| Scenario (leader in us-east-1) | Sorted RTTs | Commit latency |
|---|---|---|
| {us-east-1, us-west-2, eu-west-1, ap-southeast-1, ap-northeast-1} | 57, 68, 148, 220 | **68ms** |
| Same with Flexible Paxos Q2=2 | wait for nearest only | **57ms** |
| EPaxos from ap-southeast-1 | fast quorum: 69, 159 | **159ms** (vs Raft 220+68=288ms) |

7-node cluster (adding eu-central-1, sa-east-1): commit = RTT to 3rd-closest = **92ms** from us-east-1.

**Key insight:** Leader placement critically affects latency. With FPaxos, the asymmetric quorum reduces commit by 1 RTT hop. EPaxos wins for geographically distributed clients when conflicts are rare.

### 4.2 WAN Partition Behavior

**Symmetric partition:** Standard — majority continues, minority becomes unavailable. Well-handled by all majority-quorum protocols.

**Asymmetric / partial partitions:** A reaches B and C; B reaches A but not C; C reaches A but not B. Documented impact:
- Cloudflare 2020 (etcd/Raft): Partial switch failure caused asymmetric connectivity. Disconnected node inflated terms, destabilizing leader. 6+ hours of impact. PreVote + CheckQuorum required.

**Gray failures** (Huang et al., HotOS 2017): Component appears healthy from some vantage points but is degraded from others. Heartbeats pass (low-bandwidth), data-plane fails (under load). Azure: unhealthy VMs with severe network issues not detected by health checks. **Mitigation:** Multi-dimensional health monitoring, client-observed latency feedback, proactive leader transfer.

**Submarine cable failures (2024-2025):** Red Sea: 4 cables severed simultaneously, 25% of Asia-Europe-Middle East traffic disrupted. East Africa: 44 cable damage events across 32 locations. **Implication:** Edge nodes must operate on stale config indefinitely when partitioned. Multiple root/regional nodes in different geographic corridors.

### 4.3 Edge Caching Strategies

**Persistent data structures:** Hash Array Mapped Tries (HAMTs) provide O(log32 N) lookup — effectively O(1) for practical key counts — with structural sharing for copy-on-write updates. For 10^6 keys: ~4 levels. For 10^9 keys: ~6 levels. SurrealDB's VART (Versioned Adaptive Radix Trie) demonstrates production-grade approach.

**Lock-free reads:** Use atomic-swap pointer pattern (ArcSwap equivalent). Readers load current pointer without contention; writers atomically swap in new version. Reads in 10-100 nanosecond range. Epoch-based reclamation frees old versions. This is RCU: readers proceed without synchronization, writers create complete new version and atomically publish.

**Delta vs full snapshot:** At 10^6 keys × 1 KB = 1 GB, full snapshots expensive. Delta essential for steady-state. Threshold: if gap exceeds configurable window (e.g., 1 hour or size threshold), switch from delta to full snapshot (Quicksilver pattern). Secondary nodes store week-long delta history.

**Anti-entropy:** Merkle-tree based reconciliation every 30-60 seconds with random peers. For 10^6 keys with branching factor 16: depth ~5. Few KB exchanged to identify divergent keys among millions.

**Negative caching:** Quicksilver V2 found negative lookups 10× more frequent than positive. Store all keys (without values) on every proxy; use Bloom filters for fast negative lookups. Rejected Cuckoo filters (18 GB memory per instance).

### 4.4 Gossip Protocols

**SWIM:** O(N) messages per period, O(1) per node. Failure detection: O(protocol_period × log N). Lifeguard extensions (HashiCorp): 50× fewer false positives, 20% faster detection. **Verdict:** Use for membership/health monitoring, not config data distribution.

**HyParView:** Active view: log(N)+c peers (TCP). Passive view: k×(log(N)+c). For 10K nodes: ~15 active, ~90 passive. For 1M: ~21 active, ~132 passive. Maintains connectivity with up to 80% node failures. **Verdict:** Use as overlay management layer.

**Plumtree (Epidemic Broadcast Trees):** Builds spanning tree on gossip overlay. EagerPush peers get full payload; LazyPush peers get IHave digests. Steady-state: O(N) total messages. Tree self-heals within 1-2 gossip rounds. **Verdict:** Primary distribution protocol — tree efficiency with gossip resilience.

**Bimodal Multicast:** UDP broadcast + gossip anti-entropy. Fastly achieves ~150ms global purge propagation. **Verdict:** Suitable for small notifications, not payload distribution.

### 4.5 Fan-out Distribution

**Optimal branching factor for tree-based fan-out:**

| Branching | Depth (10K nodes) | Depth (1M nodes) | Latency (100ms/hop) |
|---|---|---|---|
| 8 | 5 | 7 | 500-700ms |
| 16 | 4 | 5 | 400-500ms |
| 32 | 3 | 4 | 300-400ms |

**Tail latency amplification** (Dean & Barroso, "The Tail at Scale"):
- Fan-out k: P(at least one child hits p99) = 1 - 0.99^k
- k=10: 9.6%. k=50: 39.5%. k=100: 63.4%.
- For parent p99 at k=16: each child must achieve p99.94.

**Recommendation:** k=16-32 for first tier (core-to-regional), k=8-16 for second tier (regional-to-edge). 2-3 tier tree covering 1M nodes with 3-4 hops. Target: 2 cross-region + 1-2 intra-region = 200-350ms p99.

**Backpressure:** Bounded output buffers per child. 30-second disconnection threshold (Quicksilver pattern). Quarantine + re-bootstrap for disconnected nodes.

**Catch-up protocol (Kafka/Raft model):**
1. Check last-applied sequence number against parent.
2. If parent has delta log entries: stream deltas.
3. If compacted past follower's position: send full snapshot + stream from snapshot point.
4. Chunked snapshot transfer (Raft InstallSnapshot).

---

## 5. Formal Verification

### 5.1 TLA+ Specifications

**Raft TLA+ (Ongaro, github.com/ongardie/raft.tla):** Covers core protocol but NOT reconfiguration. Log Completeness proved via TLAPS with auxiliary invariants not mechanically checked. The single-server reconfiguration bug was found through manual reasoning, not model checking.

**ZAB TLA+ (Yin et al., JCST 2020):** Three-level specification: protocol, system, test. Verified 2 liveness and 1 safety property with TLC. Huang et al. (2023) found **6 deep bugs** in ZooKeeper (v3.4.10 through v3.9.1) using multi-grained TLA+ and Remix model-checking framework.

**Key lesson:** TLA+ is most valuable when specs track implementation closely. Protocol-level specs catch design bugs; implementation-level specs catch code bugs. Both needed.

### 5.2 Known Bugs Caught / Missed

**Caught by formal methods:**
- ZAB: 6 deep bugs across ZK versions via multi-grained TLA+ (Huang et al., 2023).
- MongoRaftReconfig: Safety proved in TLA+ with TLAPS (Schultz et al., OPODIS 2021).

**Missed (found by other means):**
- Raft single-server reconfig bug (manual reasoning, 2015).
- PreVote + CheckQuorum interaction issues (implementation experience).
- EPaxos ballot management bug (Sutra, 2019 — theoretical analysis).
- etcd v3.5 data inconsistency (production incident).

### 5.3 Implications for Configd

Our TLA+ specs must cover:
1. Leader election safety and liveness
2. Log matching and state machine safety
3. Reconfiguration safety (joint consensus or single-server with no-op fix)
4. Version monotonicity (no version ever decreases at any observer)
5. No stale overwrite (write at version V cannot be overwritten by write at version < V)
6. Edge propagation correctness (every committed write eventually reaches every live edge, bounded by staleness contract)

Each invariant must also exist as a runtime assertion in Java (assertion-mode in tests, metric-mode in production).

Recommended: Multi-grained TLA+ approach (protocol + implementation levels) checked with both Apalache (symbolic, finds bugs faster) and TLC (exhaustive, higher confidence for small models).

---

## 6. Cross-System Insights

### 6.1 Consensus vs Coordination: Why the Distinction Matters

- **Consensus is expensive:** Raft/Paxos require RTTs to a quorum. Globally: 250-800ms (CockroachDB global tables).
- **Coordination can be cheaper:** Quicksilver achieves sub-second global propagation WITHOUT consensus at edge. Root uses consensus for durability; edge uses pure coordination (hierarchical fan-out + sequence numbers).
- **Key insight:** For config distribution, consensus for SOURCE OF TRUTH (writes), coordination for DISTRIBUTION (reads/propagation). Spanner's stale reads and CockroachDB's closed timestamps demonstrate: once you have a "safe" timestamp, followers serve reads without consensus.

### 6.2 Embedded vs External Consensus: The KRaft Lesson

ZooKeeper's problems drove KIP-500: state divergence, one-shot watch races, separate operational burden, push-based notification with incomplete update handling.

KRaft's solution: embed consensus directly. Single deployment artifact. Event-sourced metadata log as single source of truth. Pull-based with offset tracking.

**Implications:** Our system must own its own consensus. No external etcd/ZK dependency. Internal event-sourced log with offset-based consumption.

### 6.3 Event-Driven vs Polling Propagation

| System | Model | Latency |
|---|---|---|
| Quicksilver v1 | Pull with 500ms batch | Seconds |
| KRaft | Pull with offset tracking | Milliseconds (intra-cluster) |
| CockroachDB | Push via Raft + side-transport (200ms) | Continuous advancement |
| Spanner | Push from leader (every 10s) | 10s staleness |
| MongoDB | Pull from primary (oplog tailing) | Seconds |

**Winner: Hybrid (CockroachDB model).** Active propagation piggybacks on writes; idle propagation via periodic side-transport; catch-up via snapshot + incremental (KRaft pattern).

### 6.4 Single Raft vs Multi-Raft vs Hierarchical

| Approach | Systems | Max Scale | When to Use |
|---|---|---|---|
| Single Raft | KRaft, etcd | 5-7 nodes | Metadata/control plane |
| Multi-Raft | TiKV, CockroachDB | 100K+ groups | Data plane with partitioned keyspace |
| Hierarchical fan-out | Quicksilver | 330+ cities, 90K+ instances | Distribution/propagation plane |

**Critical insight:** These are not mutually exclusive. Optimal architecture is **layered:**
1. **Core:** Single Raft group (3-5 nodes) for write consensus (KRaft controller / Quicksilver root).
2. **Regional:** Optional intermediate Raft groups or relay nodes.
3. **Edge:** Hierarchical fan-out with no consensus, only coordination (Quicksilver proxy/relay).

Multi-Raft (TiKV/CockroachDB) is the wrong abstraction for config distribution. Config needs fast broadcast from single source, not partitioned consensus.

### 6.5 Why ZooKeeper Is Being Removed Industry-Wide

| System | Replacement | Key Motivation |
|---|---|---|
| Kafka | KRaft (embedded Raft) | State divergence, operational cost, controller failover 5-7s→<1s, 10× partition scale |
| ClickHouse | ClickHouse Keeper (C++ Raft, ZK wire protocol) | JVM overhead: 81.6 GB→18 GB memory, 4.5× reduction, p99 insertion 15s→2s |
| Pulsar | Pluggable metadata store | Infrastructure footprint, ZK operational complexity |
| Druid | HTTP-based alternatives | Operational complexity, cloud cost (3 ZK pods for quickstart) |
| Meta | Zelos/Delos | Monolithic coupling, inability to extend, 56K vs 36K ops/sec |

**Common thread:** (1) JVM operational overhead, (2) write throughput ceiling, (3) operational cost of separate system, (4) inability to horizontally scale metadata.

### 6.6 Deterministic Simulation: The Path to Correctness

FoundationDB proved DST catches bugs no other method finds. Requirements for Java:
1. Single-threaded simulation via Project Loom virtual threads with custom executor.
2. Priority queue with simulated time for controlled scheduling.
3. Seeded PRNG for deterministic randomness.
4. Interface-based Network/Clock/Storage swappable between real and simulated.
5. `@Buggify` annotation for fault injection in production code.
6. Property-based assertions after each simulated operation.

James Baker demonstrated 40,000 simulated Raft rounds/second with Loom approach.

---

## 7. Tradeoffs Table

### 7.1 Consensus Protocols

| Dimension | Raft | Multi-Paxos | Fast Paxos | EPaxos | Flexible Paxos | Hierarchical Raft | VR Revisited |
|---|---|---|---|---|---|---|---|
| **Write Latency** | 1 RTT to majority | 1 RTT (steady) | 2 msg delays fast / 3 classic | 1 RTT fast / 2 RTT slow | < 1 RTT (to Q2 subset) | 1 RTT intra-region (2-10ms) | 1 RTT to majority |
| **Throughput** | High (batch/pipeline) | High (batch) | Moderate (collisions) | High (no leader bottleneck) | High (smaller ack set) | Very high (parallel groups) | High |
| **Consistency** | Linearizable | Linearizable | Linearizable | Linearizable (if fixed) | Linearizable | Linearizable (per-range) | Linearizable |
| **Availability under partition** | f of 2f+1 | f of 2f+1 | f of 3f+1 | f of 2f+1 | Up to Q2-1 steady | f per group | f of 2f+1 |
| **Operational Complexity** | Low-Medium | High | High | Very High | Medium | Medium-High | Low-Medium |
| **WAN Suitability** | Poor (single group) / Good (multi) | Poor (single) | Moderate | Good (low conflict) | Good (small Q2) | Excellent | Poor (single) |
| **Production Track Record** | etcd, Consul, CRDB, TiKV | Spanner, Chubby | Limited | None | WPaxos (research) | CRDB, TiKV, YugabyteDB | Harp (historical) |

### 7.2 Coordination Systems

| Dimension | ZooKeeper | Chubby | etcd | Consul |
|---|---|---|---|---|
| **Write QPS Ceiling** | ~21K (3-node) | Not published | ~50K (1000 clients) | ~10-15K |
| **Read Latency** | Sub-ms (in-memory) | Sub-ms (cache hit) | 0.3ms serializable / 0.7ms linearizable | Sub-ms stale |
| **Consistency** | Writes: linearizable. Reads: sequential | Full linearizability | Writes: linearizable. Reads: tunable | Writes: linearizable. Reads: tunable |
| **Watch Scalability** | Poor pre-3.6 (100 bytes/watch) | N/A (KeepAlive) | Good (350 bytes/watch, 2.5M in 5.7 GB) | Poor (1 HTTP per key) |
| **Operational Complexity** | High (JVM, GC, separate system) | Medium (internal) | Medium (defrag, compaction) | High (gossip tuning) |
| **WAN Support** | None native | Multi-cell (internal) | None native | First-class |
| **DB Size Limit** | Memory-bound | Small (design goal) | 8 GB suggested | 1 GB recommended |
| **Horizontal Write Scaling** | No | Cell sharding | No | No |

### 7.3 Modern Production Systems

| Dimension | Quicksilver | TiKV | KRaft | CockroachDB | Spanner | FoundationDB |
|---|---|---|---|---|---|---|
| **Write Latency** | 500ms batch + Raft | 1-5ms intra-DC | ms (metadata) | 2-10ms intra-AZ | single-digit ms | 22ms avg |
| **Read Latency** | ~500μs avg, <7ms p99.9 | sub-ms (LeaseRead) | ms (local cache) | 1ms intra-AZ | sub-ms stale | 1ms avg |
| **Edge Propagation** | "within seconds" | N/A | ms (intra-cluster) | 3s (closed TS) | 10s (safe TS) | N/A |
| **Scale** | 5B KV, 330 cities | 100+ TB | 5M partitions | 200+ nodes | 15+ EB | Apple iCloud |
| **Consistency** | Eventual + sequential | Linearizable + SI | Linearizable (ctrl) | Serializable | External | Strict serializable |
| **Key Innovation** | Hierarchical fan-out + 3-tier cache | Multi-Raft + safe-ts | Embedded consensus | Closed timestamps | TrueTime | Deterministic simulation |

---

## Sources

### Papers
- Ongaro & Ousterhout, "In Search of an Understandable Consensus Algorithm," ATC 2014
- Ongaro, "Consensus: Bridging Theory and Practice," Stanford PhD Dissertation 2014
- Lamport, "The Part-Time Parliament," 1998; "Paxos Made Simple," 2001; "Fast Paxos," 2006
- Moraru, Andersen, Kaminsky, "There Is More Consensus in Egalitarian Parliaments," SOSP 2013
- Howard, Malkhi, Spiegelman, "Flexible Paxos: Quorum Intersection Revisited," OPODIS 2016
- Howard et al., "Raft does not Guarantee Liveness in the face of Network Faults," 2020
- Howard & Schwarzkopf, "Paxos vs Raft: Have we reached consensus on distributed consensus?" PaPoC 2020
- Sutra, "On the correctness of Egalitarian Paxos," 2019
- Ryabinin et al., "EPaxos*," OPODIS 2025
- Ailijiang et al., "WPaxos: Wide Area Network Flexible Consensus," IEEE TPDS 2019
- Schultz et al., "MongoRaftReconfig," OPODIS 2021
- Liskov & Cowling, "Viewstamped Replication Revisited," 2012
- Fischer, Lynch, Paterson, "Impossibility of Distributed Consensus with One Faulty Process," JACM 1985
- Chandra et al., "Paxos Made Live," PODC 2007
- Hunt et al., "ZooKeeper: Wait-free Coordination for Internet-scale Systems," USENIX ATC 2010
- Burrows, "The Chubby Lock Service for Loosely-Coupled Distributed Systems," OSDI 2006
- Corbett et al., "Spanner: Google's Globally Distributed Database," OSDI 2012
- Zhou et al., "FoundationDB: A Distributed Unbundled Transactional Key Value Store," SIGMOD 2021
- Cockroach Labs, "CockroachDB: The Resilient Geo-Distributed SQL Database," SIGMOD 2020
- Huang et al., "Gray Failure: The Achilles' Heel of Cloud-Scale Systems," HotOS 2017
- Dean & Barroso, "The Tail at Scale," Communications of ACM 2013
- Yin et al., "ZAB TLA+ Specification," JCST 2020
- Huang et al., "Leveraging TLA+ for ZooKeeper Reliability," 2023

### Blog Posts & Technical Reports
- Cloudflare: Introducing Quicksilver, Quicksilver v2 Parts 1 & 2, Moving Quicksilver into Production
- Cloudflare: November 18 2025, December 5 2025, February 20 2026 outage postmortems; Code Orange
- Confluent: KIP-500, 42 Ways ZooKeeper Removal Improves Kafka, KRaft documentation
- CockroachDB: Scaling Raft, Follower Reads Epic, Joint Consensus, Multi-Region Under the Hood
- TiKV: Multi-Raft Deep Dive, Lease Read, Follower Reads
- HashiCorp: Lifeguard (Making Gossip More Robust with Lifeguard)
- Baker, "Project Loom for Distributed Systems," 2022
- Amplify Partners, "A DST Primer," 2025

### Bug Reports & Issues
- ZOOKEEPER-1177, ZOOKEEPER-4837, ZOOKEEPER-3890, ZOOKEEPER-3911, ZOOKEEPER-2926
- etcd #13766, #15247, #9360, #17529, #11495, #8501, #2656
- Consul #4084, #5309, #2535, #6306
- EPaxos reference implementation issues #10, #14
- Kubernetes #32361, #110210
- Raft single-server reconfig bug (Ongaro, raft-dev 2015)
