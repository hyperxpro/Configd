# Netty 4.2 API + transport selector — migration reference (Phase R, charter §4)

> **Status:** Phase R deliverable for the **Netty transport migration** (the platform decision —
> see [adr-0043-netty-transport-platform.md](../decisions/adr-0043-netty-transport-platform.md)).
> This is the *production* counterpart to the head-to-head research
> [docs/jdk-vs-netty/netty42-api.md](../jdk-vs-netty/netty42-api.md), which pinned the current 4.2
> API for the benchmark prototypes and is an **immutable prior artifact** (charter §9). That
> document already verified — against Maven Central / netty.io javadoc / the 4.2 migration guide —
> the event-loop model, the `ByteBuf`/pooled-allocator/leak-detector lifecycle, the zero-alloc
> `HttpServerCodec` read pipeline, and the length-prefixed framing codec. **This document does not
> repeat that; it cites it** and adds the two things the migration needs that the head-to-head
> deliberately left out:
>
> 1. **The full three-tier `io_uring → Epoll → NIO` runtime-detected transport selector.** The
>    head-to-head benchmarked only the two-tier `Epoll → NIO` fallback (io_uring was quarantined to
>    its own axis, head-to-head §6 / charter hard-rule 6). Production must detect and prefer io_uring
>    where the kernel supports it, and **fall back, proven, where it does not** (dev box, CI runner).
> 2. **Production concerns the benchmark did not have:** graceful shutdown, leak-detector policy in
>    a long-lived process, allocator sizing, JDK-25 native-access flags, and the CI-fallback proof.

Version pinned for the migration: **Netty `4.2.15.Final`** (root pom `<netty.version>`), JDK 25
(Corretto), build `./mvnw --enable-preview`. The box this is authored on reports kernel
`7.0.0-1006-aws` (≫ the io_uring ≥5.9 floor).

---

## 1. The transport-selector tiers (the migration's core new design)

Netty 4.2 decoupled the *IO mechanism* from the event-loop group: there is one generic
`io.netty.channel.MultiThreadIoEventLoopGroup` parameterized by an `IoHandlerFactory`, and the
per-transport group classes (`NioEventLoopGroup`, `EpollEventLoopGroup`, …) are deprecated
(head-to-head §2.1). **Critically, the group no longer implies the channel type** — you still set a
matching `ServerSocketChannel` class on the bootstrap, and *mismatching the factory and the channel
class is the #1 4.2 migration bug* (head-to-head §2.1 cites the AWS-SDK-v2 and Lettuce breakages).
So a correct selector must resolve **a coherent triple per tier**, never a factory in isolation:

| Tier | Availability guard (static) | `IoHandlerFactory` | Server channel class | Client channel class |
|---|---|---|---|---|
| **1 io_uring** | `io.netty.channel.uring.IoUring.isAvailable()` | `IoUringIoHandler.newFactory()` | `IoUringServerSocketChannel` | `IoUringSocketChannel` |
| **2 Epoll** | `io.netty.channel.epoll.Epoll.isAvailable()` | `EpollIoHandler.newFactory()` | `EpollServerSocketChannel` | `EpollSocketChannel` |
| **3 NIO** (always) | — (pure-Java; always present) | `NioIoHandler.newFactory()` | `NioServerSocketChannel` | `NioSocketChannel` |

**Detection order and semantics:**

1. Try tier 1: if `IoUring.isAvailable()` → use the io_uring triple.
2. Else try tier 2: if `Epoll.isAvailable()` → use the Epoll triple.
3. Else tier 3: NIO — the pure-Java selector transport, always available on any JVM/OS.

`isAvailable()` returns `false` (never throws) when the native library is missing, unloadable, or
the kernel is too old; `unavailabilityCause()` returns the `Throwable` explaining why, which the
selector logs once at startup so an operator can see *why* a tier was skipped (e.g. "io_uring
native lib present but kernel < 5.9" vs "epoll classifier jar not on the classpath"). The selector
must be **deterministically overridable** for the CI-fallback proof (below) — a system property
(e.g. `configd.netty.transport=nio|epoll|io_uring`) forces a tier so CI can exercise the NIO and
Epoll paths on a runner that *does* have io_uring, and vice-versa. An explicit override that names
an unavailable tier is a **fail-loud** startup error (not a silent downgrade) — silent downgrade is
how a "we tested io_uring" claim becomes fiction.

### 1.1 Selector shape (what M1.2 implements)

A single resolver returns an immutable record of the coherent triple plus the chosen tier name,
and the group factory uses it; nothing else in the code references a concrete transport class:

```java
// io.configd.edge.node.NettyTransport (production; mirrors head-to-head §2.3 but 3-tier + forced-tier)
public record Selection(String tier,
                        IoHandlerFactory ioHandlerFactory,
                        Class<? extends ServerChannel> serverChannelClass) {}

public static Selection select() {
    String forced = System.getProperty("configd.netty.transport");
    if (forced != null) return forceOrThrow(forced);              // fail-loud if unavailable
    if (IoUring.isAvailable())
        return new Selection("io_uring", IoUringIoHandler.newFactory(),
                             IoUringServerSocketChannel.class);
    if (Epoll.isAvailable())
        return new Selection("epoll", EpollIoHandler.newFactory(),
                             EpollServerSocketChannel.class);
    return new Selection("nio", NioIoHandler.newFactory(), NioServerSocketChannel.class);
}

// EventLoopGroup boss   = new MultiThreadIoEventLoopGroup(1, sel.ioHandlerFactory());
// EventLoopGroup worker = new MultiThreadIoEventLoopGroup(N, sel.ioHandlerFactory());
// new ServerBootstrap().group(boss, worker).channel(sel.serverChannelClass())...
```

The chosen `tier` string is surfaced (a gauge label / startup log line / test accessor) so:
(a) operators see the active transport, (b) the gc-proof records which tier produced its number,
and (c) the CI-fallback test can **assert** which tier ran.

### 1.2 Why three tiers and not "io_uring everywhere"

io_uring is the platform upside (charter §0/§6: syscall-batching, validated in Phase V), but it is
*not* universally available: the kernel floor is ≥5.9 in practice and CI runners / containers
frequently lack it (or block the syscalls via seccomp). Epoll covers all modern Linux; NIO covers
literally everything (other OSes, locked-down sandboxes). The migration therefore **never depends
on io_uring for correctness** — it is a performance tier, and Epoll/NIO are the always-correct
fallback. This is charter prime-constraint §3.2 ("io_uring → Epoll → NIO, runtime-detected, fallback
CI-proven").

---

## 2. io_uring 4.2 specifics (the new tier — API + kernel + caveats)

Verified coordinates (head-to-head §6, re-stated here as the production tier-1 facts):

- **Package/classes (4.2 promoted io_uring out of `incubator`):** `io.netty.channel.uring.*` —
  `IoUring` (availability guard), `IoUringIoHandler` (with `newFactory()`,
  `newFactory(int ringSize)`, `newFactory(IoUringIoHandlerConfig)`), `IoUringIoHandlerConfig`,
  `IoUringServerSocketChannel`, `IoUringSocketChannel`. (4.1 names `IOUring*` in
  `io.netty.incubator.channel.uring` are **gone**.)
- **Artifacts (same classes/native split as epoll):**
  - `io.netty:netty-transport-classes-io_uring` — Java classes, **no** classifier.
  - `io.netty:netty-transport-native-io_uring` — native binary, **`<classifier>linux-x86_64</classifier>`**
    (use `linux-aarch_64` on ARM). Both pinned at `${netty.version}` via the root pom
    `dependencyManagement` (added in M1.1).
- **Submission/Completion-Queue model (why it can reduce syscalls):** io_uring is a pair of
  shared ring buffers between user space and the kernel — a **submission queue (SQ)** of IO
  requests and a **completion queue (CQ)** of results. The application batches many operations
  into the SQ and submits them with **one** `io_uring_enter(2)` syscall (or zero, in
  SQPOLL/kernel-poll mode), then reaps many completions from the CQ without a syscall per event.
  Contrast `epoll`, which still issues a syscall per `read`/`write`/`accept`. **This batching is
  the io_uring benefit the allocation benchmarks could not see (they measure bytes, not syscalls)
  — it is the axis Phase V measures** (charter §6). `IoUringIoHandlerConfig` exposes the ring size
  (depth of in-flight ops); the default is fine for M1, tuned in Phase V if warranted.
- **Registration / event-loop integration:** in 4.2 you do not touch the ring directly — the
  `IoUringIoHandler` (created via the factory and run inside `MultiThreadIoEventLoopGroup`) owns
  the SQ/CQ and drives channel IO through it, exactly as `EpollIoHandler`/`NioIoHandler` do for
  their mechanisms. The channel registers with the event loop's `IoHandler`; the same pipeline /
  `ByteBuf` / handler code runs unchanged on top — only the triple (§1) differs.
- **Kernel requirement:** Linux **≥ 5.9.0** is Netty's practical floor (interface landed in 5.1;
  ≥5.9 avoids known slowdowns). This box's `7.0.0-1006-aws` clears it. Stated as *capability*; no
  performance is claimed here (Phase V measures it).
- **JDK 25 caveat (record, do not trip on):** known **io_uring event-loop-group shutdown
  slowness on Java 25** (Netty issue #16174). Mitigation: bound `shutdownGracefully(quietPeriod,
  timeout, …)` so process shutdown cannot hang on it; the Epoll/NIO tiers are unaffected. This is
  a shutdown-latency nuisance, not a correctness or steady-state-throughput issue.
- **`Unsafe`-free on JDK 25:** Netty ≥4.2.2 uses `java.lang.foreign.MemorySegment` instead of
  `sun.misc.Unsafe` — so `4.2.15.Final` is clean on JDK 25 (`Unsafe` is being removed). Pass
  `--enable-native-access=ALL-UNNAMED` to silence the restricted-method warnings on the FFM/native
  path (applies to all three native-capable tiers; head-to-head §8).

---

## 3. CI-fallback proof (charter §3.2 / §7 — non-negotiable)

io_uring being the *stated justification* (charter §0/§4) makes the fallback a first-class
correctness property, not an afterthought: the system must run on a box **without** io_uring (and
without epoll) and pass every test. The proof has three parts, all CI-wired:

1. **Forced-NIO run** (`-Dconfigd.netty.transport=nio`): the entire M1 negative-test suite passes
   on the pure-Java tier. This is the universal-floor proof and is what a generic GitHub-hosted
   runner (often no io_uring) exercises by default — but we **force** it so the result does not
   depend on the runner's kernel.
2. **Forced-Epoll run** (`-Dconfigd.netty.transport=epoll`) where epoll is available: the suite
   passes on tier 2.
3. **Selector assertion:** a unit test asserts (a) `select()` resolves to a coherent triple whose
   channel class matches its factory's transport, (b) a forced unavailable tier fails loud (no
   silent downgrade), and (c) the default order is io_uring→epoll→nio.

The gc-proof (M1.5) separately records *which tier* produced the 1,716 B/req number (it will be
epoll on this box, since io_uring's allocation profile is the same shell — the win is syscalls, not
bytes; that is Phase V's axis).

---

## 4. Production concerns beyond the benchmark

- **Graceful shutdown:** `boss.shutdownGracefully(q, t, unit)` + `worker.shutdownGracefully(...)`
  with a **bounded** quiet-period/timeout (so the JDK-25 io_uring shutdown-slowness cannot hang a
  redeploy). Close the server channel first, then the groups; await with a timeout.
- **Leak detector:** run correctness/CI at `ResourceLeakDetector.Level.PARANOID` (head-to-head §3.4)
  to *prove* the production pipeline has zero `ByteBuf` leaks (a leak silently shrinks the pool and
  inflates allocation — it would corrupt the gc-proof and, worse, degrade a long-lived edge over
  days). Steady-state production can drop to `SIMPLE` (sampled) for throughput; the **gate** runs
  PARANOID. This is a real risk the hand-rolled handler must earn against, not assume.
- **Allocator:** `PooledByteBufAllocator.DEFAULT` on both `option` (server channel) and
  `childOption` (accepted channels). Consider pinning `-Dio.netty.allocator.numDirectArenas` to the
  worker count for arena affinity (head-to-head §3.2); record whatever is chosen.
- **Backpressure / DoS hardening (new for production, absent from the read-only prototype):** the
  prototype skipped these because a benchmark client is friendly; a public edge read port is not.
  M1.3 must add: an HTTP **request-size ceiling** (the 1 MB charter ceiling — `HttpServerCodec`
  `maxInitialLineLength`/`maxHeaderSize` + reject oversize bodies; `HttpObjectAggregator` is *not*
  used on the hot path, so the body cap is enforced by counting `HttpContent` bytes and failing
  fast, or by an explicit `maxContentLength` guard), and an **idle/slowloris timeout**
  (`io.netty.handler.timeout.ReadTimeoutHandler` / `IdleStateHandler`) so a client that opens a
  connection and dribbles bytes is closed rather than holding an event-loop slot. Both are S7
  controls re-proven by negative test (M1.4).
- **JVM flags (record in the run manifest):** `--enable-preview`,
  `--enable-native-access=ALL-UNNAMED`, `-Dio.netty.leakDetection.level=…`, allocator arena props,
  and (for forced-tier CI) `-Dconfigd.netty.transport=…`.

---

## 5. What is reused verbatim from the head-to-head research (do not re-derive)

These are correct and current in [docs/jdk-vs-netty/netty42-api.md](../jdk-vs-netty/netty42-api.md);
the migration uses them unchanged:

- §2 event-loop model (`MultiThreadIoEventLoopGroup` + `IoHandlerFactory`), constructors, the
  channel/factory pairing rule.
- §3 `ByteBuf` lifecycle / reference counting (inbound auto-release via
  `SimpleChannelInboundHandler`, outbound release-after-write, `ReferenceCountUtil.safeRelease`),
  `PooledByteBufAllocator` tuning, `ResourceLeakDetector` levels.
- §4 zero/low-allocation `HttpServerCodec` read handler (no `HttpObjectAggregator` on the hot path;
  respond on `LastHttpContent`; shared retained-slice body; keep-alive; `voidPromise`; flush on
  `channelReadComplete`) — this is exactly the prototype the production server is hardened from, and
  §4.3's honest list of *unavoidable* per-request allocations (the residual 1,716 B/req).
- §5 length-prefixed CRC-trailered framing codec (`LengthFieldBasedFrameDecoder` +
  `MessageToByteEncoder` in-pipeline encode) — **for M3 fan-out / M4 consensus**, not M1.
- §8 JDK-25 specifics (MemorySegment path, `--enable-native-access`, direct-memory sizing).

**Sources:** all URLs verified in the head-to-head research doc's Sources section (Netty 4.2
migration guide; `MultiThreadIoEventLoopGroup` / `IoUringIoHandler` / `IoUringIoHandlerConfig` /
`PooledByteBufAllocator` / `ResourceLeakDetector` / `HttpServerCodec` / `LengthFieldBasedFrameDecoder`
/ `MessageToByteEncoder` javadocs at `netty.io/4.2/...`; the io_uring kernel-floor release note;
JDK-25 issues #15566 and #16174). io_uring SQ/CQ model: the io_uring submission/completion-queue
design as documented by Netty's io_uring transport and the kernel `io_uring` interface.
