# Netty 4.2 API Reference (for the JDK-vs-Netty head-to-head)

**Purpose.** This document pins the *actual current* Netty **4.2.x** API so a strongest-possible
Netty prototype can be built against it. Netty 4.2 changed materially from 4.1 — most importantly the
event-loop model — so do **not** code from 4.1 memory. Every class/method/coordinate below was verified
against Maven Central / netty.io javadoc / the official 4.2 migration guide (see Sources at the end).

**Environment this targets.** JDK 25 (Corretto), Linux kernel reported as `7.0.0-1006-aws`, 2 vCPU,
Maven build (`./mvnw`). No Netty is currently a dependency anywhere in the repo.

> Scope note (read first): **io_uring will NOT be benchmarked in this session.** It is documented in
> §6 as a separate axis only — API + kernel requirement, no performance claims.

---

## 0. TL;DR for the prototype engineer

- **Version:** use **`4.2.15.Final`** (latest 4.2 GA on Maven Central; javadoc at `netty.io/4.2/...`
  renders `4.2.15.Final`). Latest 4.2 tags in order: 4.2.15 ← 4.2.14 ← 4.2.13 ← 4.2.12 ← 4.2.11.
- **Event loop (the big change):** there is no more `NioEventLoopGroup`/`EpollEventLoopGroup` as the
  idiomatic path — they are **deprecated**. Use one generic group,
  `MultiThreadIoEventLoopGroup`, parameterized by an `IoHandlerFactory`:
  `new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory())` or `EpollIoHandler.newFactory()`.
- **Allocator:** `PooledByteBufAllocator.DEFAULT` (pooled, off-heap/direct arenas), set via
  `ChannelOption.ALLOCATOR`. The 4.2 `io.netty` line **still ships `ByteBuf`** — the new
  `io.netty5.buffer.Buffer` API is *Netty 5*, a different group/package, NOT in 4.2.
- **HTTP read server:** `HttpServerCodec` + a custom inbound handler that responds to
  `LastHttpContent` with a `DefaultFullHttpResponse` over a pooled `ByteBuf`. **Avoid
  `HttpObjectAggregator`** on the hot path — it allocates a `FullHttpRequest` and copies the body.
- **JDK 25 caveat:** Netty **≥ 4.2.2** uses `MemorySegment` to avoid `sun.misc.Unsafe` (good for JDK
  25, where `Unsafe` is being removed). There is a known JDK-25-specific *io_uring shutdown slowness*
  issue — irrelevant here because we use Epoll/NIO, not io_uring.

---

## 1. Version + Maven coordinates

**Current 4.2 GA: `4.2.15.Final`.** All `io.netty` artifacts share this version (BOM-aligned). JDK 25
is supported (minimum is Java 8; see §8 for the JDK-25 specifics that actually matter).

> Release-date note: the GitHub releases page renders the 4.2.15.Final tag date as "02 Jun" but the
> year field parsed ambiguously (the 4.1 line's 4.1.135.Final is the June-2026 release). The **version**
> `4.2.15.Final` is the load-bearing fact and is confirmed by the live javadoc titles; treat it as the
> current latest 4.2 GA.

### 1.1 Module layout (4.2 is modular — note the classes/native split)

In 4.2 the native transports are split into a **platform-independent classes** jar and a
**platform-specific native binary** jar (the binary carries a `<classifier>`). This split is *not*
how a naive 4.1 pom looked; pull both.

| Concern                | artifactId                                  | classifier needed?            |
|------------------------|---------------------------------------------|-------------------------------|
| Core transport + bootstrap | `netty-transport`                       | no                            |
| Buffers / allocator    | `netty-buffer`                              | no                            |
| Common utils (leak detector, etc.) | `netty-common`                  | no                            |
| Generic codecs (length-field, base) | `netty-codec`                  | no                            |
| HTTP/1.1 codec         | `netty-codec-http`                          | no                            |
| NIO transport          | (in `netty-transport`)                      | no                            |
| Epoll — Java classes   | `netty-transport-classes-epoll`             | no                            |
| Epoll — native binary  | `netty-transport-native-epoll`              | **yes** → `linux-x86_64`      |
| io_uring — Java classes (§6 only) | `netty-transport-classes-io_uring` | no                       |
| io_uring — native binary (§6 only) | `netty-transport-native-io_uring` | **yes** → `linux-x86_64`  |

`netty-all` exists but is a fat aggregate; for a benchmark you want the explicit modules above so the
dependency surface is honest. (Historically `netty-all` has *not* reliably bundled the native epoll
binary, which is exactly the split this table makes explicit.)

### 1.2 Copy-pasteable `<dependency>` blocks

Pin the version once (e.g. a property `<netty.version>4.2.15.Final</netty.version>`); shown expanded
here for clarity.

```xml
<!-- Core transport + bootstrap (pulls netty-buffer, netty-common, netty-resolver transitively) -->
<dependency>
  <groupId>io.netty</groupId>
  <artifactId>netty-transport</artifactId>
  <version>4.2.15.Final</version>
</dependency>

<!-- Buffers / PooledByteBufAllocator (transitive via netty-transport, declared for clarity) -->
<dependency>
  <groupId>io.netty</groupId>
  <artifactId>netty-buffer</artifactId>
  <version>4.2.15.Final</version>
</dependency>

<!-- Generic codecs: LengthFieldBasedFrameDecoder / LengthFieldPrepender / MessageToByteEncoder -->
<dependency>
  <groupId>io.netty</groupId>
  <artifactId>netty-codec</artifactId>
  <version>4.2.15.Final</version>
</dependency>

<!-- HTTP/1.1 codec: HttpServerCodec, HttpObjectAggregator, FullHttpResponse, ... -->
<dependency>
  <groupId>io.netty</groupId>
  <artifactId>netty-codec-http</artifactId>
  <version>4.2.15.Final</version>
</dependency>

<!-- Epoll native transport: platform-independent classes ... -->
<dependency>
  <groupId>io.netty</groupId>
  <artifactId>netty-transport-classes-epoll</artifactId>
  <version>4.2.15.Final</version>
</dependency>
<!-- ... and the native binary for this box (linux-x86_64). Use linux-aarch_64 on ARM. -->
<dependency>
  <groupId>io.netty</groupId>
  <artifactId>netty-transport-native-epoll</artifactId>
  <version>4.2.15.Final</version>
  <classifier>linux-x86_64</classifier>
</dependency>
```

NIO needs no extra artifact — `NioIoHandler` / `NioServerSocketChannel` live in `netty-transport`.

---

## 2. The 4.2 event-loop model (the headline change)

### 2.1 What changed vs 4.1

- **4.1 did:** `EventLoopGroup g = new NioEventLoopGroup();` (and `new EpollEventLoopGroup()`,
  `new KQueueEventLoopGroup()`, `new IOUringEventLoopGroup()`). Each transport had its *own* group
  class that hard-baked the IO mechanism.
- **4.2 does:** one generic `MultiThreadIoEventLoopGroup` (in `io.netty.channel`) that takes an
  **`IoHandlerFactory`**. The IO mechanism is supplied as an `IoHandler` via that factory. The
  per-transport group classes (`NioEventLoopGroup`, `EpollEventLoopGroup`, `KQueueEventLoopGroup`,
  `IOUringEventLoopGroup`) are **deprecated** (they still work as thin shims, but new code must not use
  them). `IOUringEventLoopGroup` no longer exists as the idiomatic type at all.

The factory methods, one per transport (all return `io.netty.channel.IoHandlerFactory`):

| Transport | Factory call                    | `IoHandler` impl (FQN)                         |
|-----------|---------------------------------|------------------------------------------------|
| NIO       | `NioIoHandler.newFactory()`     | `io.netty.channel.nio.NioIoHandler`            |
| Epoll     | `EpollIoHandler.newFactory()`   | `io.netty.channel.epoll.EpollIoHandler`        |
| KQueue    | `KQueueIoHandler.newFactory()`  | `io.netty.channel.kqueue.KQueueIoHandler`      |
| io_uring  | `IoUringIoHandler.newFactory()` | `io.netty.channel.uring.IoUringIoHandler`      |
| Local     | `LocalIoHandler.newFactory()`   | `io.netty.channel.local.LocalIoHandler`        |

`MultiThreadIoEventLoopGroup` constructors (verified, `io.netty.channel`):

```java
MultiThreadIoEventLoopGroup(IoHandlerFactory ioHandlerFactory);
MultiThreadIoEventLoopGroup(int nThreads, IoHandlerFactory ioHandlerFactory);
MultiThreadIoEventLoopGroup(ThreadFactory threadFactory, IoHandlerFactory ioHandlerFactory);
MultiThreadIoEventLoopGroup(Executor executor, IoHandlerFactory ioHandlerFactory);
MultiThreadIoEventLoopGroup(int nThreads, Executor executor, IoHandlerFactory ioHandlerFactory);
MultiThreadIoEventLoopGroup(int nThreads, ThreadFactory threadFactory, IoHandlerFactory ioHandlerFactory);
MultiThreadIoEventLoopGroup(int nThreads, Executor executor,
                            EventExecutorChooserFactory chooserFactory, IoHandlerFactory ioHandlerFactory);
```

**Important:** the group is now transport-agnostic — you **still** set the matching `Channel` type on
the bootstrap (`channel(EpollServerSocketChannel.class)` vs `NioServerSocketChannel.class`). The group
no longer implies the channel type; mixing them up is the #1 4.2 migration bug (real downstream
breakages: AWS SDK v2, Lettuce both shipped bugs from this exact change).

### 2.2 Epoll-availability detection + NIO fallback (use this verbatim)

`io.netty.channel.epoll.Epoll` exposes the static guard:

```java
import io.netty.channel.epoll.Epoll;
// ...
boolean useEpoll = Epoll.isAvailable();          // false if native lib missing/unloadable
// Epoll.unavailabilityCause() returns the Throwable explaining why, if you want to log it.
```

### 2.3 Strongest server bootstrap — Epoll with NIO fallback (boss + worker)

```java
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.epoll.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;

final boolean epoll = Epoll.isAvailable();

final IoHandlerFactory ioFactory =
        epoll ? EpollIoHandler.newFactory() : NioIoHandler.newFactory();

// 1 acceptor thread; worker threads default to 2*cores (here 2 vCPU -> pin explicitly for the bench).
EventLoopGroup boss   = new MultiThreadIoEventLoopGroup(1, ioFactory);
EventLoopGroup worker = new MultiThreadIoEventLoopGroup(/*nThreads=*/2, ioFactory);

Class<? extends ServerChannel> serverChannel =
        epoll ? EpollServerSocketChannel.class : NioServerSocketChannel.class;

ServerBootstrap b = new ServerBootstrap()
    .group(boss, worker)
    .channel(serverChannel)
    .option(ChannelOption.SO_BACKLOG, 1024)
    .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)        // server channel
    .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)   // accepted channels
    .childOption(ChannelOption.SO_REUSEADDR, true)
    .childOption(ChannelOption.TCP_NODELAY, true)                           // latency: disable Nagle
    .childHandler(new ChannelInitializer<Channel>() {
        @Override protected void initChannel(Channel ch) {
            ch.pipeline().addLast(/* codecs + business handler, see §4 / §5 */);
        }
    });

ChannelFuture f = b.bind(port).sync();
// ... f.channel().closeFuture().sync(); then boss.shutdownGracefully(); worker.shutdownGracefully();
```

Epoll-specific knob worth setting for the bench (Linux only): level- vs edge-triggered and
`SO_REUSEPORT` for multi-acceptor sharding:

```java
b.option(EpollChannelOption.SO_REUSEPORT, true);   // multiple bootstraps share the port (sharded accept)
```

---

## 3. Pooled buffers, `ByteBuf` lifecycle, leak detection

### 3.1 Which buffer API does 4.2 actually ship?

**`io.netty.buffer.ByteBuf` is the 4.2 mainline — period.** The "new Buffer API"
(`io.netty5.buffer.Buffer`, no aliasing, no auto-grow capacity/max-capacity split) is **Netty 5**
(`io.netty5.*` group/package, currently 5.x Alpha). It is **not** part of the `io.netty` 4.2.x line.
So for this benchmark: write `ByteBuf`, allocate from `PooledByteBufAllocator`, manage refcounts.

### 3.2 `PooledByteBufAllocator`

- FQN: `io.netty.buffer.PooledByteBufAllocator`, implements `ByteBufAllocator` (and
  `ByteBufAllocatorMetricProvider`).
- **`public static final PooledByteBufAllocator DEFAULT`** — use this; it reads the JVM-wide tuning
  system properties below. Set it on the bootstrap via
  `ChannelOption.ALLOCATOR` / `childOption(ChannelOption.ALLOCATOR, …)` (see §2.3).
- Direct (off-heap) arenas are the default and are what you want for socket I/O (avoids a heap→direct
  copy on write). `directBuffer()` / `ioBuffer()` give pooled direct buffers.

Tuning knobs (constructor args **and** equivalent system properties — set the properties for the
benchmark so the config is reproducible from the command line):

| Constructor arg            | System property                                  |
|----------------------------|--------------------------------------------------|
| `preferDirect`             | `io.netty.noPreferDirect` (set `false` to keep direct) |
| `nHeapArena`               | `io.netty.allocator.numHeapArenas`               |
| `nDirectArena`             | `io.netty.allocator.numDirectArenas`             |
| `pageSize`                 | `io.netty.allocator.pageSize`                     |
| `maxOrder` (chunk = pageSize << maxOrder) | `io.netty.allocator.maxOrder`     |
| `smallCacheSize`           | `io.netty.allocator.smallCacheSize`              |
| `normalCacheSize`          | `io.netty.allocator.normalCacheSize`             |
| `useCacheForAllThreads`    | `io.netty.allocator.useCacheForAllThreads`       |
| `directMemoryCacheAlignment` | (constructor only)                             |

For a 2-vCPU box, the default arena count = `min(cores, maxMem/chunk/2/3)` may pick more arenas than
you have worker threads; for a clean head-to-head, consider matching arena count to worker-thread count
(e.g. `-Dio.netty.allocator.numDirectArenas=2`) so per-thread arena affinity is 1:1 and the allocator
isn't doing cross-arena work the JDK baseline never pays. Record whatever you choose.

Also relevant on JDK 25: ensure direct memory is available —
`-Dio.netty.maxDirectMemory=<bytes>` (or `0` to track via Netty's own counter), and grant
`--enable-native-access=ALL-UNNAMED` to silence the JDK 25 restricted-method warnings (see §8).

### 3.3 `ByteBuf` reference counting (get this right or the benchmark lies)

- `ByteBuf` is reference-counted (`ReferenceCounted`): starts at refCnt 1; `retain()` ++,
  `release()` --; freed back to the pool at 0. Pooled buffers that are not released are **leaks** that
  silently shrink the pool and inflate allocation — exactly the kind of bug that would make a Netty
  benchmark look artificially good *or* bad.
- **Inbound rule:** a handler that consumes a `ByteBuf`/`ByteBufHolder` and does not pass it on must
  `release()` it. Extending `SimpleChannelInboundHandler<T>` auto-releases the inbound message after
  `channelRead0` — convenient for the read-server handler. If you use raw
  `channelRead(ctx, Object)`, you own the release.
- **Outbound rule:** once you `write()` a `ByteBuf`, Netty releases it after the write completes — do
  **not** also release it yourself.
- Use `ReferenceCountUtil.release(msg)` / `.safeRelease(msg)` in `finally`/`exceptionCaught`.

### 3.4 `ResourceLeakDetector`

- FQN: `io.netty.util.ResourceLeakDetector`; levels enum `io.netty.util.ResourceLeakDetector.Level`:
  `DISABLED`, `SIMPLE` (default — sampled), `ADVANCED`, `PARANOID` (highest overhead, test-only).
- Set via `ResourceLeakDetector.setLevel(Level.PARANOID)` or system property
  `-Dio.netty.leakDetection.level=PARANOID`.
- **Benchmark protocol:** run **correctness/soak passes at `PARANOID`** to prove zero leaks, then run
  the **timed throughput passes at `DISABLED`** (the detector samples and allocates tracking records;
  leaving it on taxes Netty in a way the JDK baseline never sees). Document both runs. This is an
  anti-rigging requirement, not optional.

---

## 4. Zero/low-allocation HTTP/1.1 read server

All in `io.netty.handler.codec.http`.

### 4.1 The pipeline pieces

- **`HttpServerCodec`** — the combined server codec = `HttpRequestDecoder` + `HttpResponseEncoder` in
  one handler. This is the minimal correct HTTP/1.1 server pipeline element.
- **`HttpObjectAggregator(int maxContentLength)`** — aggregates an `HttpMessage` + its trailing
  `HttpContent`s into a single `FullHttpRequest`/`FullHttpResponse`. Convenient, but **allocates**:
  it accumulates the body into a `CompositeByteBuf`/copy and produces a new `Full*` object **per
  request**. For a fixed-read server that ignores the request body, **do not use it** on the hot path.
  If the accumulated content exceeds `maxContentLength` it calls `handleOversizedMessage()`.
- Streaming message types you handle directly to avoid aggregation:
  `HttpRequest` (headers/line), `HttpContent` (a body chunk), `LastHttpContent` (terminal chunk;
  `LastHttpContent.EMPTY_LAST_CONTENT` is the sentinel for no body). All in
  `io.netty.handler.codec.http`.
- Response types: `FullHttpResponse` / `DefaultFullHttpResponse`; status `HttpResponseStatus.OK`;
  header constants `HttpHeaderNames.CONTENT_TYPE` / `CONTENT_LENGTH` / `CONNECTION` and values
  `HttpHeaderValues.*`; keep-alive helpers on `HttpUtil` (`isKeepAlive`, `setKeepAlive`,
  `setContentLength`). (These types are stable API carried unchanged from 4.1; the
  `HttpServerCodec`/`HttpObjectAggregator` 4.2 javadoc confirms the package.)

### 4.2 Minimal-allocation fixed-read handler (the strongest HTTP build)

Strategy: skip the aggregator; respond once per request when `LastHttpContent` arrives; reuse a
constant body via a retained slice; honor keep-alive so the connection (and its pooled buffers) is
reused across requests.

```java
import io.netty.buffer.*;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.util.AsciiString;
import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

public final class FixedReadHandler extends ChannelInboundHandlerAdapter {

    // Pre-encode the constant payload ONCE into an unpooled buffer; slice+retain per response.
    private static final byte[] BODY_BYTES = /* the fixed read record */ ...;
    private static final ByteBuf BODY =
            Unpooled.unreleasableBuffer(Unpooled.directBuffer(BODY_BYTES.length).writeBytes(BODY_BYTES));
    private static final int BODY_LEN = BODY_BYTES.length;
    private static final AsciiString CT_VALUE = AsciiString.cached("application/octet-stream");

    private boolean keepAlive = true;

    @Override public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof HttpRequest req) {
            keepAlive = HttpUtil.isKeepAlive(req);
            // body of the *request* is ignored for a read server; chunks released below.
        }
        if (msg instanceof HttpContent hc) {
            hc.release();                          // we don't read the request body
            if (msg instanceof LastHttpContent) {
                // Duplicate the shared body (cheap: shares memory, own reader index, no copy).
                ByteBuf body = BODY.retainedDuplicate();
                FullHttpResponse resp = new DefaultFullHttpResponse(HTTP_1_1, OK, body);
                HttpHeaders h = resp.headers();
                h.set(CONTENT_TYPE, CT_VALUE);
                h.setInt(CONTENT_LENGTH, BODY_LEN);
                if (keepAlive) {
                    h.set(CONNECTION, HttpHeaderValues.KEEP_ALIVE);
                    ctx.write(resp, ctx.voidPromise());            // voidPromise: skip promise alloc
                } else {
                    h.set(CONNECTION, HttpHeaderValues.CLOSE);
                    ctx.write(resp).addListener(ChannelFutureListener.CLOSE);
                }
            }
        }
    }

    @Override public void channelReadComplete(ChannelHandlerContext ctx) { ctx.flush(); }

    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { ctx.close(); }
}
```

Pipeline: `ch.pipeline().addLast(new HttpServerCodec(), new FixedReadHandler());`
(`HttpServerKeepAliveHandler` is available if you want Netty to manage keep-alive/`Connection`
headers for you, but doing it by hand as above is one fewer handler on the hot path.)

### 4.3 What is *unavoidably* allocated per request even in the best Netty HTTP pipeline

Be honest about this in the verdict — these are not removable without leaving HTTP semantics:

1. A decoded **`HttpRequest`/`DefaultHttpRequest`** object (request line + a `HttpHeaders` instance).
   The decoder must materialize parsed headers as objects.
2. **Header `String`/`AsciiString` entries** for whatever headers the client sends (names are often
   cached/interned via `HttpHeaderNames`, but values generally are not).
3. The **`DefaultFullHttpResponse`** wrapper object per response (the *body* bytes can be a shared
   retained slice as above, so payload bytes need not be re-allocated — but the holder object is new).
4. Decoder internal **cumulation** `ByteBuf` activity (pooled, so amortized, but non-zero).
5. Per-write plumbing unless mitigated: use `ctx.voidPromise()` to avoid a `ChannelPromise`
   allocation per write where you don't need completion notification.

Netty's edge over a hand-rolled JDK HTTP path is *pooling the byte buffers and amortizing parser
state*, not making per-request object allocation literally zero. The benchmark should measure
**allocation bytes/op** (e.g. JFR `jdk.ObjectAllocationSample` or async-profiler `-e alloc`) so the
residual per-request object cost is visible for both sides, not hidden.

---

## 5. Length-prefixed binary framing codec (maps to Configd consensus / fan-out wire)

Configd's consensus and fan-out wire formats are length-prefixed with a CRC32C trailer. Netty pieces,
all in `io.netty.handler.codec`:

### 5.1 Decode side — `LengthFieldBasedFrameDecoder`

```java
public LengthFieldBasedFrameDecoder(int maxFrameLength,
                                    int lengthFieldOffset,
                                    int lengthFieldLength,
                                    int lengthAdjustment,
                                    int initialBytesToStrip);
```

For a frame laid out as `[u32 length][payload bytes...][u32 CRC32C]` where the length field counts
`payload + trailer`:

- `lengthFieldOffset = 0` (length is first),
- `lengthFieldLength = 4`,
- `lengthAdjustment = 0` (length already covers everything after itself),
- `initialBytesToStrip = 4` (drop the length header; hand the handler `payload+CRC`),
- `maxFrameLength` = your hard cap (reject oversize frames — security-relevant; the decoder throws
  `TooLongFrameException`).

The decoder hands downstream a `ByteBuf` sliced from the (pooled) cumulation buffer — **no `byte[]`
copy** for the framing step. Your handler then verifies/strips the CRC32C trailer from that `ByteBuf`
directly (`buf.readableBytes()-4`), without copying into a `byte[]`: read the trailing 4 bytes via
`buf.getIntLE(...)`/`getInt(...)` and CRC the preceding region. You can feed
`java.util.zip.CRC32C` from the `ByteBuf` via `buf.nioBuffer(index, len)` (zero-copy view of the
backing memory) → `crc.update(ByteBuffer)`, avoiding an intermediate array.

### 5.2 Encode side — `MessageToByteEncoder<T>` writing straight into a pooled `ByteBuf`

`io.netty.handler.codec.MessageToByteEncoder<I>`:

```java
protected abstract void encode(ChannelHandlerContext ctx, I msg, ByteBuf out) throws Exception;
```

The framework allocates `out` from the channel's allocator (pooled) **before** calling `encode`, and
honors a `preferDirect` flag (constructor `MessageToByteEncoder(boolean preferDirect)` /
`isPreferDirect()`) — pass `true` so `out` is a pooled **direct** buffer ready for the socket. You
write the whole frame into `out` with no intermediate `byte[]`:

```java
public final class FrameEncoder extends MessageToByteEncoder<MyMsg> {
    public FrameEncoder() { super(/*preferDirect=*/ true); }   // pooled direct 'out'

    @Override protected void encode(ChannelHandlerContext ctx, MyMsg msg, ByteBuf out) {
        int lengthIndex = out.writerIndex();
        out.writeInt(0);                       // placeholder for u32 length, fix up after
        int bodyStart = out.writerIndex();

        msg.writeTo(out);                      // serialize payload directly into 'out' (no byte[])

        // CRC32C over the payload region, read as a zero-copy NIO view of 'out':
        int bodyLen = out.writerIndex() - bodyStart;
        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        crc.update(out.nioBuffer(bodyStart, bodyLen));
        out.writeInt((int) crc.getValue());    // u32 CRC32C trailer

        int total = out.writerIndex() - bodyStart;   // payload + trailer
        out.setInt(lengthIndex, total);              // back-patch the length prefix
    }
}
```

`out.ensureWritable(n)` before large writes lets you size in one shot. This is allocation-free beyond
the single pooled `out` buffer Netty already had to allocate for the socket write.

### 5.3 `LengthFieldPrepender` (simpler alternative)

`io.netty.handler.codec.LengthFieldPrepender(int lengthFieldLength)` prepends a length header to an
outbound `ByteBuf` you've already produced. Useful if your payload encoder emits a bare `ByteBuf` and
you want framing as a separate, composable handler. For the CRC-trailered Configd format the
hand-rolled `MessageToByteEncoder` in §5.2 is cleaner (length must cover the trailer, which it computes
after CRC).

---

## 6. io_uring — DOCUMENTED ONLY, NOT BENCHMARKED THIS SESSION

> **Flag:** io_uring is a **separate benchmark axis** and is explicitly **out of scope** for this
> head-to-head. No io_uring performance numbers will be produced here. This section records API +
> kernel facts only.

- **Status in 4.2:** io_uring is now **first-class in `io.netty`** (4.2 merged it in from the old
  `io.netty.incubator` line). Package renamed `io.netty.incubator.channel.uring` → **`io.netty.channel.uring`**;
  classes renamed `IOUring*` → **`IoUring*`**.
- **Artifacts (same classes/native split as epoll):**
  - `io.netty:netty-transport-classes-io_uring` (Java classes, no classifier)
  - `io.netty:netty-transport-native-io_uring` (native binary, `<classifier>linux-x86_64</classifier>`)
- **Key classes:** `io.netty.channel.uring.IoUringIoHandler` with
  `IoUringIoHandler.newFactory()`, `newFactory(int ringSize)`,
  `newFactory(IoUringIoHandlerConfig config)`; `IoUringIoHandlerConfig`;
  `IoUringServerSocketChannel` / `IoUringSocketChannel`; availability guard
  `io.netty.channel.uring.IoUring.isAvailable()`. Wiring mirrors §2.3, substituting the io_uring
  factory + channel classes.
- **Java requirement:** io_uring requires **Java 9+** (this repo is JDK 25 — fine).
- **Kernel requirement:** Linux **≥ 5.9.0** is the practically-stable floor Netty calls out (the
  io_uring interface itself landed in 5.1, but ≥5.9 avoids known slowdowns/bugs). **This box reports
  kernel `7.0.0-1006-aws`, which is far newer than 5.9 — so io_uring would in principle be supported
  here.** (Stated as capability only; no claim it is faster.)
- **JDK-25 caveat to record:** there is a known issue of **event-loop-group shutdown being slow with
  io_uring on Java 25** (vs Java 21). Another reason it's quarantined to its own axis. Does **not**
  affect the Epoll/NIO builds this session benchmarks.

---

## 7. "Strongest Netty build" — concrete checklists

### 7.1 (a) HTTP read server

Dependencies: `netty-transport`, `netty-buffer`, `netty-codec-http`,
`netty-transport-classes-epoll`, `netty-transport-native-epoll:linux-x86_64`.

1. `Epoll.isAvailable()` → pick `EpollIoHandler.newFactory()` else `NioIoHandler.newFactory()`.
2. Two `MultiThreadIoEventLoopGroup`s: boss `nThreads=1`, worker `nThreads=2` (match the 2 vCPU).
3. Channel class matches the factory: `EpollServerSocketChannel` / `NioServerSocketChannel`.
4. `ServerBootstrap`: `SO_BACKLOG=1024`; `childOption(TCP_NODELAY,true)`;
   `option/childOption(ALLOCATOR, PooledByteBufAllocator.DEFAULT)`;
   optionally `EpollChannelOption.SO_REUSEPORT=true` for sharded accept.
5. Pipeline = `HttpServerCodec` + custom `ChannelInboundHandlerAdapter` (§4.2). **No
   `HttpObjectAggregator`.**
6. Respond on `LastHttpContent` with `DefaultFullHttpResponse` over a `BODY.retainedDuplicate()`;
   set `CONTENT_LENGTH`; honor keep-alive; `ctx.write(resp, ctx.voidPromise())`; flush in
   `channelReadComplete`.
7. Release ignored request-body `HttpContent` chunks; `ctx.close()` in `exceptionCaught`.
8. JVM flags: `-Dio.netty.leakDetection.level=DISABLED` for timed runs (PARANOID for a separate
   correctness run); `--enable-native-access=ALL-UNNAMED`; pin
   `-Dio.netty.allocator.numDirectArenas=2`; set `-Dio.netty.maxDirectMemory=…`.
9. Graceful shutdown: `boss.shutdownGracefully(); worker.shutdownGracefully();`.

### 7.2 (b) Binary length-prefixed message encoder (+ decoder)

Dependencies: `netty-transport`, `netty-buffer`, `netty-codec` (+ epoll artifacts as above).

1. Inbound: `LengthFieldBasedFrameDecoder(maxFrameLength, 0, 4, 0, 4)` → frames arrive as pooled
   `ByteBuf` slices (no `byte[]` copy).
2. Verify CRC32C trailer directly on the `ByteBuf` via `buf.nioBuffer(idx,len)` → `CRC32C.update(...)`;
   reject mismatch; enforce `maxFrameLength` (`TooLongFrameException`).
3. Outbound: `MessageToByteEncoder<T>` with `preferDirect=true`; serialize payload into the
   framework-provided pooled direct `out`; write u32 length placeholder, payload, u32 CRC32C trailer,
   then back-patch the length (§5.2). No intermediate arrays.
4. Same event-loop / allocator / leak-detector / shutdown discipline as §7.1.
5. Decoder business handler: prefer `SimpleChannelInboundHandler<ByteBuf>` (auto-release) or release
   the frame `ByteBuf` yourself.

### 7.3 Cross-cutting anti-rigging (applies to both)

- Time at `ResourceLeakDetector.Level.DISABLED`; prove zero-leak at `PARANOID` separately.
- Measure **allocation bytes/op** for both Netty and JDK builds (don't let pooling hide behind
  throughput-only numbers; §4.3 lists Netty's unavoidable per-request allocations).
- Pin worker threads + arena count to the 2 vCPU so Netty isn't over- or under-threaded vs the JDK
  baseline.
- Record every JVM flag and every allocator system property in the run manifest.

---

## 8. JDK 25 specifics that actually matter

- **Minimum Java for 4.2:** 8. JDK 25 is supported.
- **`Unsafe` removal:** JDK 25 continues removing `sun.misc.Unsafe`. Netty **≥ 4.2.2** can use the
  **`java.lang.foreign` `MemorySegment`** path instead of `Unsafe` — so **4.2.15.Final is the right
  call** for a clean JDK-25 run. Pass `--enable-native-access=ALL-UNNAMED` to avoid restricted-method
  warnings on the FFM/native path; without it you get noisy (harmless) JDK-25 warnings.
- **Direct memory:** make sure direct memory is sized (`-Dio.netty.maxDirectMemory`,
  or `-XX:MaxDirectMemorySize`), since pooled buffers are direct by default.
- **io_uring + JDK 25:** known shutdown-slowness issue (see §6) — avoided by not using io_uring.
- GraalVM-25 note (not relevant unless building a native image): GraalVM 25 doesn't enable
  `Arena.ofShared` by default, which Netty's `CleanerJava25` uses — a non-issue on a normal HotSpot
  JDK 25 run.

---

## 9. 4.1 → 4.2 quick diff (so nobody codes from memory)

| Concern             | 4.1 (do NOT use)                              | 4.2 (use this)                                                        |
|---------------------|-----------------------------------------------|----------------------------------------------------------------------|
| Event loop group    | `new NioEventLoopGroup()` / `new EpollEventLoopGroup()` | `new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory())` / `EpollIoHandler.newFactory()` |
| io_uring group      | `new IOUringEventLoopGroup()`                 | `MultiThreadIoEventLoopGroup(IoUringIoHandler.newFactory())` (type gone) |
| io_uring package    | `io.netty.incubator.channel.uring`, `IOUring*`| `io.netty.channel.uring`, `IoUring*`                                  |
| io_uring group id   | `io.netty.incubator:netty-incubator-transport-*` | `io.netty:netty-transport-*-io_uring`                             |
| Native epoll dep    | often just `netty-transport-native-epoll`     | `netty-transport-classes-epoll` **+** `netty-transport-native-epoll:linux-x86_64` |
| Buffer API          | `ByteBuf`                                      | **still `ByteBuf`** (the `io.netty5.buffer.Buffer` API is Netty 5, separate) |
| Min Java            | 6                                             | 8 (and `MemorySegment`/no-`Unsafe` path since 4.2.2 → JDK 25 clean)   |
| Allocator           | `PooledByteBufAllocator.DEFAULT`              | unchanged: `PooledByteBufAllocator.DEFAULT`                           |
| HTTP codec          | `HttpServerCodec` / `HttpObjectAggregator`    | unchanged class names                                                 |
| Length-field codec  | `LengthFieldBasedFrameDecoder` / `LengthFieldPrepender` / `MessageToByteEncoder` | unchanged class names |

**Bottom line:** in 4.2 the *event-loop construction*, the *io_uring coordinates/package*, and the
*native-transport dependency split* are what changed; the *buffer*, *allocator*, *HTTP codec*, and
*length-field codec* class names are stable from 4.1.

---

## Sources (verified)

- Netty 4.2 Migration Guide — https://netty.io/wiki/netty-4.2-migration-guide.html
  (mirror: https://github.com/netty/netty/wiki/Netty-4.2-Migration-Guide) — event-loop model, JDK 8
  minimum, io_uring package/Java-9 rename.
- `MultiThreadIoEventLoopGroup` javadoc (4.2.15.Final) —
  https://netty.io/4.2/api/io/netty/channel/MultiThreadIoEventLoopGroup.html — constructors.
- `IoUringIoHandler` javadoc (4.2.15.Final) —
  https://netty.io/4.2/api/io/netty/channel/uring/IoUringIoHandler.html — `newFactory()` signatures.
- `IoUringIoHandlerConfig` / `io.netty.channel.uring` package —
  https://netty.io/4.2/api/io/netty/channel/uring/package-summary.html
- `PooledByteBufAllocator` javadoc (4.2.15.Final) —
  https://netty.io/4.2/api/io/netty/buffer/PooledByteBufAllocator.html — DEFAULT, tuning knobs,
  `io.netty.allocator.*` system properties.
- `ResourceLeakDetector.Level` javadoc (4.2.15.Final) —
  https://netty.io/4.2/api/io/netty/util/ResourceLeakDetector.Level.html — DISABLED/SIMPLE/ADVANCED/PARANOID.
- `HttpObjectAggregator` javadoc (4.2.15.Final) —
  https://netty.io/4.2/api/io/netty/handler/codec/http/HttpObjectAggregator.html — aggregation cost,
  `maxContentLength`.
- `HttpServerCodec` javadoc (4.2.15.Final) —
  https://netty.io/4.2/api/io/netty/handler/codec/http/HttpServerCodec.html — combines
  HttpRequestDecoder + HttpResponseEncoder.
- `LengthFieldBasedFrameDecoder` javadoc (4.2.15.Final) —
  https://netty.io/4.2/api/io/netty/handler/codec/LengthFieldBasedFrameDecoder.html — constructor.
- `MessageToByteEncoder` javadoc (4.2.15.Final) —
  https://netty.io/4.2/api/io/netty/handler/codec/MessageToByteEncoder.html — `encode(...)`,
  `preferDirect`.
- Maven Central — `netty-all` versions (latest 4.2 GA = 4.2.15.Final) —
  https://central.sonatype.com/artifact/io.netty/netty-all/versions
- Maven Central — `netty-transport-classes-epoll` (confirms 4.2 classes/native split, 4.2.15.Final) —
  https://central.sonatype.com/artifact/io.netty/netty-transport-classes-epoll/versions
- Netty native-transports wiki (epoll classifier `linux-x86_64`) —
  https://netty.io/wiki/native-transports.html
- GitHub releases (4.2.15.Final latest 4.2 tag; 4.2.14/13/12/11 preceding) —
  https://github.com/netty/netty/releases
- JDK-25 compatibility: Netty issue #15566 (Java 25 native-access warning),
  https://github.com/netty/netty/issues/15566 ; Java-24/Unsafe wiki
  https://netty.io/wiki/java-24-and-sun.misc.unsafe.html (MemorySegment path since 4.2.2) ; io_uring
  shutdown-slow-on-Java-25 issue #16174, https://github.com/netty/netty/issues/16174
- io_uring kernel floor (≥ 5.9.0 practical) — Netty io_uring release note
  https://netty.io/news/2020/11/16/io_uring-0-0-1-Final.html and io_uring background
  https://en.wikipedia.org/wiki/Io_uring
- Downstream breakages from the 4.2 event-loop change (illustrating the channel/group pairing
  pitfall): AWS SDK v2 #6757 https://github.com/aws/aws-sdk-java-v2/issues/6757 ; Lettuce #3584
  https://github.com/redis/lettuce/issues/3584
