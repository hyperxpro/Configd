# M1 edge-read — production `-prof gc` allocation proof (the 8.7× holds on the migrated pipeline)

> **Charter §3 / DoD:** "edge-read: Netty HTTP, 8.7× alloc held (`-prof gc`)." The head-to-head
> measured the win on a read-only **prototype** (`NettyEdgeReadServer`, 1,716 B/req). M1 must prove
> it **holds on the production server** (`NettyEdgeHttpServer`) — which adds the shared
> `EdgeReadHandler`, the io_uring→Epoll→NIO selector, and the DoS hardening. It does:
> **14,999 → 1,704 B/req = 8.80×** (idiomatic), reproduced from the same harness that cross-checks
> the JDK baseline against the head-to-head's 15,010.

## Method (identical to the head-to-head, charter-comparable)

Server-side allocation is isolated exactly as the head-to-head did (`docs/jdk-vs-netty/verdict.md`
§Surface 2): the load client runs **out-of-JVM** (`EdgeReadLoadClientMain`, so its `HttpClient`
allocation is excluded by construction); the server self-measures
`com.sun.management.ThreadMXBean.getTotalThreadAllocatedBytes()` (exact, not sampled) across a
control-socket-delimited window. Harness: `EdgeReadAllocServerMain` (the `netty-prod` mode added in
M1 starts the production `NettyEdgeHttpServer`). B/request is CPU-count-independent → trustworthy on
this 2-vCPU box; throughput/latency are relative-only.

```
# server (one of: jdk | netty-prod)
java --enable-preview --enable-native-access=ALL-UNNAMED -Dio.netty.leakDetection.level=DISABLED \
     -Dio.netty.allocator.numDirectArenas=2 -cp configd-testkit/target/benchmarks.jar \
     io.configd.edge.node.EdgeReadAllocServerMain netty-prod 0 19097 256 64
# out-of-JVM client (host port controlPort keyCount valueBytes concurrency warmup measure)
java --enable-preview -cp configd-testkit/target/benchmarks.jar \
     io.configd.edge.node.EdgeReadLoadClientMain 127.0.0.1 <port> 19097 256 64 8 20000 50000
```
KEY_COUNT=256, VALUE_BYTES=64, concurrency=8, warmup=20k, measure=50k. Idle background floor was
**56 bytes/sec** (noise — confirms ~zero contamination). Git: `netty-migration` (this session).

## Results

| server | server-side B/request | vs JDK | tier | throughput (rel., 2-vCPU wash) |
|---|---|---|---|---|
| JDK `EdgeHttpServer` (best-JDK; `com.sun.net.httpserver`) | **14,999.2** | 1.0× | jdk | ~3,130 req/s |
| head-to-head prototype `NettyEdgeReadServer` | 1,716 (prior) | 8.7× | epoll | (prior) |
| **production `NettyEdgeHttpServer` — naive** (per-request deadline `schedule()` + per-request sink) | 1,820.3 | **8.24×** ✗ | io_uring | ~3,470 req/s |
| **production `NettyEdgeHttpServer` — idiomatic** (allocation-free) | **1,703.8** | **8.80×** ✓ | io_uring | ~3,140 req/s |

- The JDK number (**14,999.2**) independently reproduces the head-to-head's 15,010 — the harness and
  baseline are sound.
- **The 8.7× win HOLDS on production: 8.80×** (1,703.8 B/req), slightly better than the prototype's
  1,716 (the shared `EdgeReadHandler` writes headers straight into the response's one `HttpHeaders`,
  no intermediate map).

## The red→green allocation discipline (the same lesson the head-to-head taught)

The **first** production cut regressed to 1,820 B/req (**8.24×, below the bar**) because the
hardening was added naively — exactly the "naive Netty usage inflates allocation" failure mode the
head-to-head documented. Two hot-path allocations, ~104 B/req combined, both eliminated:

1. **Per-request deadline `schedule()` (~+72 B/req).** The slowloris deadline was *re-armed on every
   keep-alive request* (`ctx.executor().schedule(...)` → a `ScheduledFutureTask` + lambda per
   request). Fixed: a single self-rescheduling watcher keyed off a `deadlineNanos` **timestamp** — a
   completed request now costs one `long` write; the watcher reschedules itself only when it fires
   (≈ once per timeout window, not per request). Slowloris coverage is unchanged (the deadline is
   still armed at connection open and not reset per byte — `NettyEdgeHttpServerHardeningTest`).
2. **Per-request `NettySink` object (~+32 B/req).** The response sink was a fresh object per request.
   Fixed: the `ReadHandler` *is* the `EdgeReadHandler.Sink` (reused across keep-alive requests); the
   only per-response allocation is the one `HttpHeaders` the response needs anyway.

This is the charter's rule 3 in action ("idiomatic Netty only — naive usage that regresses the floor
is a defect, not Netty being slower"). Red (1,820 / 8.24×) → green (1,704 / 8.80×), re-measured.

## io_uring note (why the tier doesn't move the number)

The selector picked **io_uring** on this box (kernel 7.0.0-1006-aws), yet the allocation is the same
shell cost as epoll would be. That is expected and on-message: **io_uring's benefit is syscall
reduction, not bytes** — an axis `-prof gc`/`getTotalThreadAllocatedBytes` cannot see. The
allocation win (8.80×) is the HTTP-shell replacement; the io_uring upside is measured separately in
**Phase V** (EC2-gated). The fallback NIO tier serves the identical responses
(`NettyEdgeHttpServerNioFallbackTest`, full contract green on NIO).

## Verdict

The edge-read migration's headline justification — the measured 8.7× server-side allocation win —
**holds on the production Netty pipeline (8.80×)**, with the DoS hardening in place and at no
allocation cost (idiomatic implementation). The QPS gate ("hot enough to matter") remains a
deployment fact to confirm before/at rollout (head-to-head's second gate); the *allocation* gate is
met on production code.
