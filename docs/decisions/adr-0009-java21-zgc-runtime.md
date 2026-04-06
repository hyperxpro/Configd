# ADR-0009: Java 21+ with ZGC, Virtual Threads, Off-Heap Storage

## Status
Superseded (see ADR-0022 for the Java 25 upgrade)

> **Note (2026-04-16, Verification Phase V8 re-audit):** This ADR was
> originally written targeting Java 21 with a Netty/gRPC/Spring Boot
> implementation stack. The actual implementation (see ADR-0010, ADR-0016
> notes) uses plain Java TCP sockets + `com.sun.net.httpserver` + custom
> framed protocol — no Netty, no gRPC-java, no Spring Boot. The Agrona and
> JCTools dependencies declared in the root `pom.xml` are historical; grep
> for imports returns zero hits in `.java` sources (see F-0075). The
> "Thread Model" diagram and "Why not GraalVM" section in this ADR
> describe aspirational integration with libraries the codebase does not
> actually use. ADR-0022 supersedes the Java version choice (Java 25).
> Hot-path constraints listed below remain in force as written.

## Context
The system has strict latency requirements: < 1ms p99 edge reads, < 5ms p999. JVM GC pauses are a known threat to coordination services (ZooKeeper GC pauses causing cascading session expirations). The runtime must support zero-allocation hot paths, off-heap data structures, and efficient concurrency for 10K+ connections per node.

## Decision
- **Java 21 LTS** (or latest LTS at build time). Required for virtual threads (Project Loom), modern ZGC, and pattern matching.
- **ZGC** as the default garbage collector. Sub-millisecond pause times regardless of heap size (< 1ms p99 pauses on heaps up to 16 TB).
- **Virtual threads** for I/O-bound operations (gRPC stream handling, Raft RPC, client connections). NOT for CPU-bound hot paths.
- **Off-heap storage via Agrona** for data that must avoid GC pressure (ring buffers, direct byte buffers, off-heap maps).
- **JCTools** for lock-free MPSC/SPSC queues on hand-off points between threads.
- **Explicit allocation budget:** < 50 MB/s allocation rate in steady state, verified by JMH `-prof gc`.

### Hot Path Constraints (FORBIDDEN)
- `synchronized` or `ReentrantLock` on any read path
- Object allocation in steady-state read path (use primitive collections, flyweight patterns)
- Reflection or dynamic proxies
- Logging at INFO+ per request (use TRACE, sampled)
- Autoboxing (use primitive specializations)

### Thread Model
```
┌─────────────────────┐
│ Raft I/O Thread     │ ← Single thread per Raft group, drives consensus
│ (Platform thread,   │   AppendEntries, elections, log persistence
│  pinned to core)    │
├─────────────────────┤
│ Apply Thread        │ ← Single thread, applies committed entries to
│ (Platform thread)   │   state machine, triggers distribution
├─────────────────────┤
│ Distribution        │ ← Virtual threads, one per gRPC stream to
│ Threads (Virtual)   │   edge nodes, fan-out from apply thread
├─────────────────────┤
│ gRPC Server         │ ← Netty event loop (platform threads) +
│ Threads             │   virtual thread per RPC for control plane
├─────────────────────┤
│ Reader Threads      │ ← Any thread. Zero synchronization.
│ (Application)       │   Single volatile read of HAMT pointer.
└─────────────────────┘
```

## Influenced by
- **ClickHouse Keeper:** Replaced ZK (JVM) with C++ Raft. 4.5× memory reduction, p99 latency 15s → 2s. Demonstrates JVM overhead is real.
- **Netty:** Event-loop model for high-performance networking. Used by TiKV (via gRPC), CockroachDB (via Go equivalent).
- **Agrona:** Aeron's off-heap primitives. Used in production for sub-microsecond messaging.
- **JCTools:** Lock-free queues used by Netty, LMAX Disruptor.

## Reasoning
### Why ZGC over Shenandoah?
Both offer sub-ms pauses. ZGC has broader production track record (Oracle, LinkedIn, Twitter). ZGC's colored pointers provide better scaling characteristics for large heaps. Shenandoah is a valid alternative — the choice is not critical.

### Why not GraalVM Native Image?
Native image eliminates GC but restricts reflection, dynamic class loading, and JNI. Spring Boot (used for control plane API) has GraalVM support but with significant startup time savings we don't need (long-running service). Virtual threads are not fully supported in all native image configurations.

### Why virtual threads for I/O but not hot paths?
Virtual threads excel at I/O-bound blocking operations (waiting for network responses). They are NOT faster for CPU-bound work — they still need platform thread carriers. The read path is CPU-bound (HAMT traversal, ~50ns) and should not incur virtual thread scheduling overhead.

## Consequences
- **Positive:** Sub-ms GC pauses. Virtual threads handle 10K+ concurrent connections without thread pool exhaustion. Off-heap data avoids GC pressure for large datasets. Lock-free queues eliminate contention at hand-off points.
- **Negative:** Java is slower than C/C++/Rust for raw compute. Virtual thread debugging is still maturing. Off-heap memory requires manual lifecycle management.
- **Risks and mitigations:** Virtual thread pinning (synchronized blocks pin carrier threads) mitigated by using `ReentrantLock` in virtual thread contexts and avoiding `synchronized` entirely. Off-heap memory leaks mitigated by `Cleaner`-based reference tracking and leak detection in test mode.

## Reviewers
- principal-distributed-systems-architect: ✅
- performance-engineer: ✅
- senior-java-systems-engineer: ✅
