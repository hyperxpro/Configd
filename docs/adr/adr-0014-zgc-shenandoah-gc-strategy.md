# ADR-0014: ZGC/Shenandoah GC Strategy with Zero-Allocation Read Path

## Status
Superseded — partially. The GC choice (ZGC on JDK 25) stands; library-level claims do not.

> **Note (2026-04-16, Verification Phase V8 re-audit):** Sections of this
> ADR assert Netty, gRPC-java, and Spring Boot as the implementation
> stack; none of those libraries are present in the actual codebase (see
> ADR-0010 Superseded note, ADR-0016 Not Implemented note, and finding
> F-0072). The "Why not GraalVM" and "Influenced by" sections describe
> a deployment model that was not carried forward. The ZGC / generational
> ZGC / heap sizing / allocation-rate guidance in this ADR remains
> authoritative. F-0041 and F-0042 (2026-04-16) document residual
> allocations above the HAMT leaf that this ADR's "zero-allocation read
> path" section did not account for; those are tracked separately.

## Context
The system requires < 1ms p99 edge reads and < 5ms p999. JVM garbage collection pauses are a known threat to coordination services: ZooKeeper's 500ms GC pauses trigger cascading session expirations across all connected clients (gap-analysis.md section 2.3). The ZK troubleshooting guide warns "things may start going wrong" when pauses exceed session timeout. The heap sizing dilemma (< 2 GB = frequent pauses, > 8 GB = longer full GC when they occur) limits ZooKeeper's effective in-memory data capacity to 4-8 GB. The system must handle datasets up to 10 GB per shard (10^9 keys / 100 shards x 1 KB) without GC-induced latency violations.

## Decision
We adopt a multi-layered GC mitigation strategy:

### 1. ZGC as Default Garbage Collector
```
-XX:+UseZGC
-XX:+ZGenerational          # Generational ZGC (JDK 21+)
-Xms4g -Xmx4g              # Fixed heap, no resize overhead
-XX:SoftMaxHeapSize=3g      # Soft ceiling triggers early collection
```

- ZGC delivers < 1ms pause times regardless of heap size (up to 16 TB). Pause time is proportional to root set size, not heap size.
- Generational ZGC (JDK 21+) reduces allocation rate pressure by collecting young generation independently.
- Shenandoah is a validated alternative (configured via `-XX:+UseShenandoahGC`) with similar sub-ms pause characteristics. ZGC is the default due to broader production track record (Oracle, LinkedIn, X/Twitter).

### 2. Zero-Allocation Read Path (Verified by JMH)
The edge read hot path must produce **zero heap allocations** in steady state:

- **HAMT traversal:** All internal nodes are pre-allocated during write path. Reader traverses existing objects via volatile reference load — no allocation.
- **Value return:** Values stored as `byte[]` or off-heap `DirectBuffer`. Returned via flyweight accessor pattern — no wrapper object created per read.
- **Version cursor:** Primitive `long` pair (sequence + timestamp), returned in pre-allocated `ReadResult` thread-local or via value type (JDK Valhalla, future).
- **No autoboxing:** All hot path methods use primitive specializations. `int`/`long` parameters, never `Integer`/`Long`.
- **No logging on hot path:** TRACE-level only, guarded by `if (logger.isTraceEnabled())` — no string concatenation or varargs array allocation.

Verification: JMH benchmarks with `-prof gc` must show 0 B/op for `HamtReadBenchmark` and `VersionedStoreReadBenchmark`.

### 3. Off-Heap Storage via Agrona
Data that must survive GC pressure or is too large for heap efficiency:

- **Raft WAL segments:** Written via Agrona `MappedByteBuffer` — off-heap, zero-copy from network.
- **Large config values (> 4 KB):** Stored in off-heap slab allocator (Agrona `DirectBuffer`). HAMT leaf stores pointer + length.
- **Ring buffers for thread hand-off:** Agrona `OneToOneRingBuffer` for Apply Thread → Distribution Thread hand-off. Fixed-size off-heap allocation, no GC involvement.
- **Lifecycle:** Reference counting with `Cleaner`-based release. Leak detection via `configd.offheap.leak` metric in test mode.

### 4. Lock-Free Structures via JCTools
- **MPSC queues:** Client request ingestion (multiple gRPC threads → single Raft I/O thread). JCTools `MpscArrayQueue` — lock-free, bounded, no GC pressure from queue operations.
- **SPSC queues:** Raft Apply Thread → Distribution Thread hand-off. JCTools `SpscArrayQueue` — wait-free, cache-line padded to avoid false sharing.
- **No `synchronized` on any read or write hot path.** Enforced by static analysis (ErrorProne custom check) in CI.

### 5. Allocation Budget
- **Steady-state allocation rate:** < 50 MB/s. Verified by JMH `-prof gc` and async-profiler allocation flame graphs.
- **At 10K writes/s:** HAMT structural sharing produces ~320 MB/s of short-lived objects (path-copy nodes). Generational ZGC handles this in young generation without pause impact.
- **Read path:** 0 B/op target. Any regression is a CI failure.

## Influenced by
- **ClickHouse Keeper:** Replaced ZooKeeper (JVM) with C++ Raft. Achieved 4.5x memory reduction (81.6 GB to 18 GB) and p99 latency improvement from 15s to 2s. Demonstrates that JVM GC overhead is real and measurable in coordination services.
- **LMAX Disruptor:** Mechanical sympathy — pre-allocated ring buffers, no GC on hot path, cache-line padding. Achieves < 1us latency in financial trading systems.
- **Aeron (Agrona):** Off-heap messaging primitives achieving sub-microsecond latency. Production-proven in high-frequency trading and military systems.
- **JCTools:** Lock-free concurrent queues used by Netty and Reactor. MPSC/SPSC queues with cache-line padding eliminate false sharing.

## Reasoning

### Why ZGC over Shenandoah?
Both deliver sub-ms pauses. The choice is not critical — either satisfies the < 1ms p99 requirement. ZGC is the default because:
1. Broader production deployment (Oracle, LinkedIn, X/Twitter at scale).
2. Colored pointer mechanism provides better scaling for large heaps (16 TB+).
3. Generational mode (JDK 21+) is more mature in ZGC.

Shenandoah is a supported alternative for Red Hat-based deployments. Configuration is a single flag change.

### Why not GraalVM Native Image?
Native Image eliminates GC entirely but restricts reflection, dynamic class loading, and JNI. Spring Boot (used for control plane API) has GraalVM support but with complexity. Virtual threads (critical for 10K+ connection handling per ADR-0009) have incomplete native image support in some configurations. The startup time benefit is irrelevant for a long-running service. The GC problem is solved by ZGC; Native Image adds constraints without proportional benefit.

### Why not G1GC?
G1GC's pause time target is 200ms by default. Tuned configurations achieve 10-50ms pauses, but cannot guarantee < 1ms. G1GC mixed collections during high allocation rates can spike to 100ms+. For a coordination service where GC pauses directly correlate with session timeouts (the ZooKeeper failure mode), sub-ms guarantees are non-negotiable.

### Why zero-allocation matters on the read path
A single allocation on the read hot path at 1M reads/s = 1M object allocations/s. At 100 bytes/object: 100 MB/s additional allocation rate. This accelerates GC pressure and increases the probability of GC coinciding with a latency-sensitive read. Zero allocation eliminates this entire failure class. The Linux kernel's RCU design principle: readers must have zero overhead.

## Rejected Alternatives
- **G1GC:** 10-50ms pause times under realistic workloads. Cannot guarantee < 1ms. Mixed collection pauses spike to 100ms+ during high allocation rates. Inadequate for a coordination service where GC pause = potential session expiration cascade.
- **GraalVM Native Image:** Eliminates GC but restricts virtual threads, reflection, and dynamic class loading. Adds build complexity (reflection configuration, resource registration) without proportional benefit since ZGC already delivers sub-ms pauses.
- **CMS (Concurrent Mark Sweep):** Deprecated since JDK 9, removed in JDK 14. Even when available, CMS experienced "concurrent mode failure" under allocation pressure, falling back to full STW collection with multi-second pauses.
- **No JVM (rewrite in Rust/C++):** Eliminates GC entirely but sacrifices the JVM ecosystem (Netty, gRPC-java, virtual threads, JCTools, Agrona). ClickHouse Keeper demonstrates C++ viability but required multi-year investment. The JVM with ZGC + zero-allocation discipline achieves comparable latency for this workload.

## Consequences
- **Positive:** Sub-ms GC pauses regardless of heap size. Zero-allocation read path eliminates GC-correlated read latency spikes. Off-heap storage decouples dataset size from GC pressure. Lock-free queues eliminate contention at thread hand-off points. Allocation budget enforced by CI prevents regression.
- **Negative:** Zero-allocation discipline requires careful coding practices — flyweight patterns, primitive specializations, thread-locals. Off-heap memory requires manual lifecycle management (reference counting, Cleaner callbacks). JCTools queues have fixed capacity — must be sized at startup.
- **Risks and mitigations:** Off-heap memory leaks mitigated by `Cleaner`-based reference tracking and leak detection metric (`configd.offheap.leak`) in test mode. Allocation budget regression mitigated by JMH `-prof gc` benchmarks in CI that fail on non-zero allocation. Virtual thread pinning (from `synchronized` blocks) mitigated by ErrorProne static analysis check that flags `synchronized` on any class in the hot path package.

## Reviewers
- principal-distributed-systems-architect: ✅
- performance-engineer: ✅
- senior-java-systems-engineer: ✅
