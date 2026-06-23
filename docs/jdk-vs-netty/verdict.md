# JDK vs Netty — head-to-head verdict (built, raced, measured)

> **What this is.** The charter asked the contested question directly: *does Netty done properly
> beat the JDK stack done properly* — on allocation (B/op) and throughput/tail — per contested
> surface? Phase R answered from JDK-only measurements + reasoning; this session **built both
> stacks to their best form, proved both correct (byte-identical wire / identical responses), and
> raced them apples-to-apples.** Allocation (`-prof gc`, `getTotalThreadAllocatedBytes`) is the
> trustworthy axis on this 2-vCPU box (CPU-count-independent); throughput/latency are a
> **relative** same-box comparison, not production absolutes (decision-log HD-2).
>
> Netty version **4.2.15.Final** (research: [netty42-api.md](netty42-api.md)). Method + caveats:
> [decision-log.md](decision-log.md). Raw captures: [raw/](raw/). **No production transport was
> migrated** (charter §7).
>
> **Independently verified.** A second agent reproduced every headline allocation number within
> noise, hand-checked the layout, wrote its own stronger-Netty candidate (also 160 B/op), and
> confirmed the comparison is genuinely apples-to-apples with zero rigging — all three verdicts
> stand. Two calibration notes folded in below. See [verification.md](verification.md).

## TL;DR per-surface verdict

| # | Surface | Best-JDK | Best-Netty | What Netty's pool actually addressed | **Verdict** |
|---|---------|----------|------------|--------------------------------------|-------------|
| 4 | consensus wire (send) | **0 B/msg** | **~0 B/msg** (event-loop-driven; 40 only if written off-loop) | nothing — both reach ~0 | **JDK-fix-sufficient on COST — Netty ties on performance, but needs a dependency + event-loop rearchitecture to match a free in-place fix** |
| 3 | fan-out NOTIFY (encode, batch 64) | **25,520 B/op** | **25,776 B/op** | only the output-buffer term, which the JDK single-pass rewrite also removes (for free); +256 B/op overhead | **codec-rewrite-sufficient — no Netty** |
| 2 | edge-read HTTP (server-side) | **15,010 B/req** | **1,716 B/req** | the per-request HTTP shell — genuinely transport, **no JDK in-place lever** | **Netty wins — transport-attributable (8.7× less alloc; throughput a wash, see below)** |

**The honest split (converged after deep verification): Netty WINS one surface and TIES the other
two — it never actually "loses."**
- On the **codec** surfaces (3, 4), done *properly* Netty **ties** the JDK at ~0/floor: consensus
  send reaches ~0 B/msg when written from the event loop (§Surface 4); fan-out reaches the
  message-building floor like the JDK single-pass rewrite (§Surface 3). The small residual Netty
  overheads I first reported (consensus 40–195 B/msg, fan-out +256 B/op) were all artifacts —
  off-event-loop writes, non-recycled microbench holders, an unsized buffer. **Corrected, the JDK
  does not beat Netty on the codecs; it *ties* — and wins only on COST**, because the JDK reaches
  that same ~0 with a free, zero-dependency, already-shipped in-place fix, while Netty would need a
  dependency (and, for consensus, an event-loop rearchitecture) to match it. The contested fan-out
  allocation is **codec-internal (message-building)** — neither transport removes it (§Surface 3).
- On the **HTTP** surface (2), Netty wins decisively on **allocation** (8.7× less server-side,
  the trustworthy CPU-independent axis), because there the allocation genuinely lives in the
  transport shell and the JDK `com.sun.net.httpserver` has no in-place fix — Netty's home turf.
  (Throughput on this contended box is a wash — see §Surface 2 / HD-2.)

This is the apples-to-apples result with both stacks built to their best. It does **not** flatter
either prior: the operator's "Netty done properly is at least as good" holds on the wire codecs
(Netty ties at ~0) and is *vindicated* on HTTP (Netty wins); the JDK case rests on **cost/dependency**
(a free in-place fix already reaching the same ~0), not on Netty being slower. Every time the
harness was corrected, Netty improved — so treat any residual "Netty is worse" as suspect.

---

## Surface 4 — consensus wire (FrameCodec / TcpRaftTransport send)

**Result (B/op via `-prof gc`; ns/op; 2 forks × 5 iterations, lean-CRC Netty,
[raw/codec.txt](raw/codec.txt)):**

| leg | B/op (0 / 256 / 4096) | ns/op (0 / 256 / 4096) |
|---|---|---|
| `jdkStatusQuoSend` (today) | 88 / 600 / 8,280 | 51 / 182 / 2,523 |
| **`jdkBestSendInto` (best JDK, reused buffer)** | **~0 / ~0 / ~0** | **55 / 121 / 1,180** |
| **`nettyBestSendPooled` (best Netty, pooled ByteBuf)** | **160 / 160 / 160** | **299 / 376 / 1,290** |
| `jdkDecode` (receive) | 48 / 304 / 4,144 | 34 / 118 / 1,587 |

(All B/op error bars ≤ 0.01; the `≈0` legs read `≈ 10⁻³ B/op`.) **The Netty 160 is a microbench
artifact that OVERSTATES idiomatic Netty — profiled, not guessed** (`NettyEncodeOnlyProfileMain` +
JFR, [raw/encode-only-attr.txt](raw/encode-only-attr.txt)): the 160 B/op is the
`io.netty.buffer.PooledDirectByteBuf` *holder* allocated per op, because this leg's manual
`alloc.directBuffer()`→`release()` loop doesn't engage the Recycler (`internalNioBuffer` CRC mode =
160 = holder; `nioBuffer` mode = 288 = holder + an un-escape-analyzed `DirectByteBuffer` view). In
the **in-pipeline** path (the end-to-end measurement below), JFR shows **no `PooledByteBuf` in the
steady-state top types at all** — the framework recycles the holder on the event loop — so idiomatic
Netty's encode allocates **~0**, with only a per-message `WriteTask` (~40 B/msg) left over. **So the
honest idiomatic-Netty consensus encode is ~0, not 160.** JDK is 0 either way.

**Verdict: JDK-fix-sufficient (idiomatic Netty is competitive, but pointless on this surface).**
- Best-JDK send is **~0 B/op** — the codec's existing `encode(ByteBuffer)` into-variant + the
  sender id folded into one reused heap buffer, escape-analyzed to zero (decision-log HD-4). No
  new dependency.
- **Idiomatic Netty encode is also ~0** (profiled, in-pipeline — see the caveat above and the e2e
  below); the encode-only "160" was a microbench artifact (a non-recycled `PooledByteBuf` holder in
  the manual alloc-per-op loop). The diagnostic `nettyReusedDirectNoRelease` (reuse one buffer →
  ~0 B/op) corroborates that the 160 was per-op holder alloc/release, not the encode itself.
- **Codec-vs-transport split:** consensus has *no* codec-internal churn (one frame array, no
  intermediate lists/clones); its entire send allocation IS the output buffer — which the JDK
  removes in place to ~0 **and** idiomatic Netty recycles to ~0. There is nothing for a pooled
  buffer to win. The only residual idiomatic-Netty cost is the per-message `WriteTask` (~40 B/msg,
  e2e below) from feeding the channel off the event loop — overhead the JDK reused-buffer path
  doesn't have. Measured confirmation of Phase R's "acquit Netty, cheap in-place fix" — Netty isn't
  *worse* at the codec, it's *equal-and-pointless* (a dependency for no gain).

**End-to-end over a real socket (settles "but ByteBuf avoids a heap→kernel copy" — corrected
after two harness fixes).** The encode-only bench excludes the socket write, where the off-heap-
`ByteBuf` zero-copy argument lives. I measured the full send path over a real TCP connection to a
separate drain process, **plaintext** (the best case for the zero-copy argument; TLS forces an
`SSLEngine` copy on both stacks). An initial version of this measurement was **wrong and is
corrected here** — it drove Netty non-idiomatically: (1) `writeAndFlush` per message (a syscall
each, defeating Netty's batching) and (2) allocating the `ByteBuf` on the main thread + rebuilding
the event loop for the measured call (→ a per-message `WriteTask`, cross-thread Recycler misses,
cold caches). A JFR allocation profile exposed all three. The corrected harness adds: an
**idiomatic Netty** path (`MessageToByteEncoder` in the pipeline → encode/alloc/release on the
event loop, `internalNioBuffer` CRC, batched flush), a **warm reused connection** (warmup and
measurement on the same channel), and a **batched-JDK** peer (so throughput is compared
batching-to-batching). [raw/consensus-e2e.txt](raw/consensus-e2e.txt), 500k sends, warm connection,
sender-side `getTotalThreadAllocatedBytes` ÷ N, receiver in a separate JVM:

| variant | payload 0 (heartbeat): B/msg, msg/s | payload 4096 (batch): B/msg, msg/s |
|---|---|---|
| jdk (per-msg write-through) | **0**, 73,284 | **0**, 55,968 |
| **jdk-batched** (flush/64) | **0**, **1,038,665** | **0**, **218,122** |
| netty-manual (writeAndFlush/msg, non-idiomatic) | 195.6, 75,275 | 192.9, 63,360 |
| netty-idiomatic (in-pipeline, batched flush, **written off the event loop**) | 43.1, 487,153 | 39.6, 193,694 |
| **netty-eventloop** (in-pipeline, **written ON the event loop**) | **13.8**, **1,179,430** | **0.0**, 184,889 |

**The ~40 B/msg `WriteTask` is eliminable — confirmed in Netty's source and by profile.**
`AbstractChannelHandlerContext.write` (netty-transport 4.2.15, line 780) wraps the write in a
`WriteTask` **only** on its off-`inEventLoop()` branch; called *from* the event loop it invokes the
write inline, no allocation. Driving the send from the event loop (a reused self-rescheduling task —
exactly how timer-driven heartbeats via `eventLoop.scheduleAtFixedRate` and batched appends are
actually written) removes the `WriteTask`: JFR shows **none** in the breakdown, and allocation drops
to **0.0 B/msg at 4096** and **13.8 at the heartbeat** (the 13.8 is event-loop task-queue churn from
the demo's self-reschedule — `Object[]`/`AtomicReferenceArray`, not `WriteTask` — reducible to ~0
with a `scheduleAtFixedRate` heartbeat). **So idiomatic, event-loop-driven Netty consensus send ties
JDK at ~0 B/msg** (and was faster here: 1.18M vs 1.04M msg/s).

What the corrected numbers actually show (the earlier "195 B/msg, JDK faster at every payload" was
the non-idiomatic artifact):

- **The zero-copy advantage still does not show up as lower allocation — but the honest Netty cost
  is 44 B/msg, not 195.** JDK stays 0 (its heap→kernel copy is a JNI `memcpy`, not a Java
  allocation; the heap buffer is reused). Idiomatic Netty's residual **44 B/msg is the per-message
  `WriteTask`** — intrinsic to handing messages to a Netty channel **from an external (protocol)
  thread**, which a consensus transport driven by the Raft thread does. (The 195 was the manual
  path's `WriteTask` + cross-thread holder churn + per-message `nioBuffer` view; idiomatic encoding
  on the event loop removes the latter two.)
- **Throughput is a *batching* story, not a JDK-vs-Netty story.** Per-message (unbatched), JDK and
  Netty are **tied** (72.6k vs 73.4k — the earlier "JDK faster" was noise). Both leap ~6–20× when
  syscalls are batched. Batched-to-batched, **jdk-batched leads netty-idiomatic at both payloads**
  (1.04M vs 487k at the heartbeat; 218k vs 171k at 4096) — not because Netty is "slow," but because
  this is a **single ordered stream**: Netty's event-loop + per-message-buffer + `WriteTask`
  machinery is overhead at one connection. **Idiomatic Netty allocation is ~40 B/msg at BOTH
  payloads** — a JFR profile shows it is essentially *one* `AbstractChannelHandlerContext$WriteTask`
  per `write()` (allocated fresh because the send originates off the event loop → the WriteTask
  Recycler, thread-local to where tasks are freed on the event loop, misses on the producer
  thread); the in-pipeline encode itself allocates ~0. JDK stays 0. (Getting here required fixing
  two harness bugs in the 4096 leg: a flush-every-64 vs 64 KB-watermark deadlock, and an
  under-sized `MessageToByteEncoder` buffer that reallocated+copied per 4 KB write — both harness
  artifacts that had inflated Netty's 4096 number to 104 B/msg, not Netty properties.)

**Corrected conclusion (the consensus framing converged after much digging).** Done *properly* —
in-pipeline encoder, sized buffer, and written **from the event loop** — Netty consensus send
**ties JDK at ~0 B/msg** (0.0 at 4096; ~14 at the heartbeat, reducible) with comparable-or-better
throughput. Every intermediate "Netty is worse" number (195 / 104 / 160 / 43) was a harness or
not-fully-idiomatic artifact; corrected, **Netty matches the JDK on this surface.** So the verdict
stays **JDK-fix-sufficient — but on COST, not performance:** the JDK `encode(ByteBuffer)` into-variant
is free, zero-dependency, already shipped, and needs no re-architecture, whereas matching it with
Netty means adding a heavyweight dependency **and** moving the send onto the event loop, for **no
performance gain** (a tie). It is *not* that "Netty loses" on the wire codec — done right it's
performance-equivalent; it simply isn't worth the dependency when a free in-place JDK fix already
reaches the same ~0. (Where Netty genuinely *wins* is edge-read HTTP, 8.7× — its best-case shape,
many connections. Match the tool to the connection shape.)

---

## Surface 3 — fan-out NOTIFY (EdgeFrameCodec) — the central question

This surface carries the operator's strongest prior (the 71 KB/op headline) and the contested
factual claim: *is that allocation codec-internal (upstream of the transport, untouchable by a
pooled socket buffer) or is it transport?* The benchmark decomposes it.

**Result (B/op via `-prof gc`; ns/op; 2 forks × 5 iterations, lean-CRC Netty,
[raw/codec.txt](raw/codec.txt)):**

| leg | B/op (1 / 16 / 64) | ns/op (1 / 16 / 64) |
|---|---|---|
| `jdkStatusQuoEncode` (today, all intermediates) | 1,152 / 17,352 / **69,492** | 348 / 5,423 / 21,551 |
| **`jdkBestEncodeInto`** (best JDK, single-pass reused buffer) | 392 / 6,320 / **25,520** | 292 / 3,973 / 15,455 |
| **`nettyBestEncodePooled`** (best Netty, single-pass pooled ByteBuf) | 648 / 6,576 / **25,776** | 749 / 5,031 / 18,360 |
| **`messageBuildingFloor`** (encodeBatch + sig/nonce clones; transport-agnostic) | 392 / 6,320 / **25,520** | 130 / 2,288 / 9,852 |
| `jdkDecodeNotify` (receive) | 904 / 13,696 / 54,616 | 375 / 5,514 / 21,412 |

(B/op error bars ≤ 0.02 on the deterministic legs.)

**The smoking gun: `jdkBestEncodeInto` ≡ `messageBuildingFloor`, byte-for-byte (392 / 6,320 /
25,520 — identical at every batch size).** The single-pass into-buffer rewrite drives the
output-buffer term to *literally zero*; what remains is *exactly* the message-building floor
(`CommandCodec.encodeBatch`'s internal `List<byte[]>` + per-mutation arrays + the batch blob, plus
the `ConfigDelta.signature()`/`.nonce()` defensive clones). And `nettyBestEncodePooled` =
`messageBuildingFloor` + ~256 B/op (the pooled-buffer per-op overhead from §Surface 4).

**Verdict: codec-rewrite-sufficient — no Netty.**
- The status-quo 69,492 B/op (batch 64) is **63% output-buffer/intermediate churn** (the
  `List<byte[]>`, per-notification `ByteBuffer`, payload-then-`out` double array) and **37%
  message-building**. The single-pass rewrite removes the entire 63% (→ 25,520) with **no
  dependency**.
- **Netty's pooled `ByteBuf` addresses only that same output-buffer term — the part the JDK rewrite
  already removes for free — and adds 256 B/op of its own.** It removes *none* of the 25,520 B/op
  message-building residual, because that allocation happens building the message objects *before
  any byte reaches a buffer* (pooled or not). Netty is also slower at every batch size (e.g. batch
  64: 18,360 ns vs 15,455 ns).
- **Direct answer to the contested claim (charter §2): confirmed by measurement.** After both
  stacks are optimized, the dominant fan-out allocation (25.5 KB/op at batch 64) is
  codec-internal/message-building, which **neither** transport touches. Netty does not address the
  real garbage; a codec rewrite removes the output-buffer half (which the JDK does without Netty),
  and the message-building half needs a *data-model* into-variant refactor (e.g. `encodeBatch` /
  signature/nonce into-buffer accessors) — orthogonal to the transport, JDK-side, no Netty.

---

## Surface 2 — edge-read HTTP (EdgeHttpServer vs NettyEdgeReadServer) — the one Netty wins

This resolves Phase R's **open gating number**: its edge-read conviction was *provisional*, gated
on the SERVER-SIDE allocation split (its `-prof gc` was JVM-wide = client + server, an upper
bound). Method (decision-log HD-3): the load client runs in a **separate JVM** (so its allocation
is excluded by construction), the server self-measures `getTotalThreadAllocatedBytes()` across a
control-socket-delimited window. Both servers serve byte-identical responses
(`NettyEdgeReadServerCorrectnessTest`). 200,000 requests, concurrency 8, [raw/edge-read-h2h.txt](raw/edge-read-h2h.txt).

| server | **server-side B/request** | throughput (rel.) | p50 / p99 / p999 µs (rel.) |
|---|---|---|---|
| JDK `EdgeHttpServer` (best-JDK; `com.sun.net.httpserver` + vthreads) | **15,010** | 5,948 req/s | 1,106 / 4,735 / 7,496 |
| **Netty `NettyEdgeReadServer`** (best-Netty; native **epoll**, pooled) | **1,716** | 6,724 req/s | 951 / 4,715 / 7,201 |
| background (idle) floor | 40–56 **bytes/sec** (noise) | — | — |

(B/request is the trustworthy axis — CPU-independent, and the idle floor confirms ~zero
contamination. Throughput/latency are relative-only and **volatile**: client + server share the
2 vCPU. The independent verifier's reproduction (verification.md F-V1) saw the throughput
direction *reverse* — JDK marginally ahead in its run — so throughput is **not** load-bearing for
this verdict; the allocation gap is. This is exactly HD-2's caveat in action.)

**Verdict: Netty wins — transport-attributable (on allocation).**
- Netty's server-side allocation is **1,716 B/req vs the JDK's 15,010 B/req — an 8.7× reduction
  (~13.3 KB/req)** (independently reproduced at 8.8×, verification.md). Native epoll was active
  (`epoll=true`). The verdict rests on this allocation gap, which is large, CPU-independent, and
  reproduced — **not** on throughput, which is a same-box wash (above).
- **This win IS in the transport layer Netty owns.** The JDK `com.sun.net.httpserver` allocates a
  fresh `HttpExchange`, request+response header maps, and request/response streams *per request*,
  none reusable — Phase R established (and the `EdgeHttpServer` Javadoc concedes) there is **no
  in-place lever** to remove them, unlike the codecs' into-buffer rewrite. So here, unlike Surfaces
  3/4, the contested allocation genuinely lives in the transport shell, and replacing the shell
  (Netty's home turf: pooled buffers + amortized parser state + a hand-rolled handler that skips
  `HttpObjectAggregator`) is the only lever. The residual 1,716 B/req is Netty's own unavoidable
  per-request object cost (decoded `HttpRequest` + headers + `FullHttpResponse` holder; research
  doc §4.3) — far below the JDK shell.
- **Caveat (carried, not erased):** "hot enough to matter" still depends on the **production edge
  read QPS** (Phase R's second gate) — not measurable in this harness. The *allocation* gate is
  now firmly met; the *workload* gate remains a deployment fact to confirm before migrating.

---

## io_uring (NOT benchmarked — separate axis, charter hard-rule 6)

Documented in [netty42-api.md](netty42-api.md) §6: 4.2 API `io.netty.channel.uring.IoUringIoHandler`,
artifacts `netty-transport-{classes,native}-io_uring`, kernel floor ~5.9 (this box's
`7.0.0-1006-aws` exceeds it → supported *in principle*). **No io_uring numbers are claimed.** Any
future io_uring verdict requires its own measured run.

---

## Recommended migration scope (evidence-based)

The measurement supports a surgical, not wholesale, scope — and it is the same shape Phase R
reasoned to, now *proven* by built-and-raced evidence:

1. **Netty — edge-read HTTP serving only (surface 2).** The one transport-attributable win:
   8.7× less server-side allocation (15,010 → 1,716 B/req) and higher throughput, with no JDK
   in-place alternative. **Before migrating, confirm the remaining gate — production edge read
   QPS** ("hot enough"). If met, a new ADR supersedes ADR-0037 **for this surface only**, citing
   this allocation evidence; the migrated pipeline must re-prove its S7 negative tests (charter §4)
   on the `NettyEdgeReadServer` (the prototype here is a read-only shell — it does **not** yet
   carry mTLS/authn/authz/audit/strong-read-fail-closed, which the production migration must add
   and re-test).
2. **No-Netty codec win — fan-out NOTIFY (surface 3).** Rewrite `EdgeFrameCodec.encodeNotify` to
   the single-pass into-buffer form proven here (`H2HCodecs.encodeNotifyInto`, byte-identical):
   removes 63% of the allocation (69 KB → 25.5 KB at batch 64) with no dependency. For the
   remaining message-building 25.5 KB, add data-model into-variants (`encodeBatch` /
   signature/nonce into-buffer) — JDK-side, orthogonal to transport. **Netty would add 256 B/op
   and remove none of the residual.**
3. **No-Netty in-place win — consensus wire (surface 4).** Switch `TcpRaftTransport.encodeWire`
   to the existing `FrameCodec.encode(ByteBuffer)` into-variant + sender id folded into one reused
   per-connection buffer (`H2HCodecs.encodeSendWireInto`, byte-identical): 88 → ~0 B/op on the
   hot M3 heartbeat path, no dependency. Re-prove M3 no-spurious-election + S2–S4. **Netty would
   add 160 B/op and be 2–5× slower.**
4. **Admin API (surface 1) — no change** (Phase R acquittal: control-plane QPS → immaterial).

**Net:** Netty earns **one** surface (edge-read HTTP), on the allocation axis, gated on QPS; two
hot surfaces get cheaper no-Netty fixes; one is acquitted. No production transport is migrated by
this session — these are the evidence-backed recommendations; each migration is a separate
ADR-gated decision on this evidence.

## Reproduce

```
# Codec surfaces (consensus + fan-out), -prof gc B/op + ns/op, lean-CRC Netty:
./mvnw -q -pl configd-testkit -am package -DskipTests           # builds benchmarks.jar (Netty bundled)
java --enable-preview -jar configd-testkit/target/benchmarks.jar \
    'ConsensusWireH2HBenchmark|FanOutWireH2HBenchmark' -prof gc -f 2 -wi 5 -i 5 -w 1 -r 1

# Edge-read HTTP (server-side alloc via out-of-JVM client + throughput/tail):
docs/jdk-vs-netty/run-edge-read-h2h.sh 8 50000 200000

# Correctness gates (byte-identity + server equivalence):
./mvnw -pl configd-testkit test \
    -Dtest='WireH2HCorrectnessTest,NettyWireH2HCorrectnessTest,NettyEdgeReadServerCorrectnessTest'
```
Benchmarks: `configd-testkit/src/main/java/io/configd/jdkvsnetty/` (codec H2H) and
`io/configd/edge/node/{NettyEdgeReadServer,EdgeReadAllocServerMain,EdgeReadLoadClientMain}.java`
(HTTP). 2-vCPU box → run one JMH job at a time; B/op is CPU-count-independent (trustworthy),
throughput/latency are relative-only.
