# JDK-vs-Netty head-to-head — Decision Log

Per charter §3 (autonomy): technical/methodology decisions are self-resolved and logged here for
retroactive veto; scope/sequencing decisions default conservative and logged. This session
*races* the two stacks at their best forms — it does not migrate any production transport
(charter §7/§8). It supersedes nothing in `docs/netty-migration/` (Phase R); it tests Phase R's
reasoned verdicts with built-and-measured evidence.

---

## HD-1 — Apples-to-apples is enforced by construction, not asserted
**Type:** methodology · **Status:** decided · **Veto window:** open

The charter's prime directive (§1): both stacks built to their BEST form, raced under identical
conditions; a comparison against the *unoptimized* JDK stack proves nothing. Enforcement:

- **Best-JDK codec** = the single-pass into-buffer encoders (`H2HCodecs`): the consensus
  `encode(ByteBuffer)` into-variant + sender-id folded into one reused buffer; the NOTIFY
  single-pass into one reused heap buffer (kills the `List<byte[]>` / per-notification
  `ByteBuffer` / double payload+out arrays). No new dependency.
- **Best-Netty codec** = single-pass into a pooled direct `ByteBuf` (`NettyWireEncoders`),
  exactly as a tuned `MessageToByteEncoder(preferDirect=true)` would (research doc §5.2), with a
  **reused thread-local `CRC32C`** so Netty is not taxed for an allocation the JDK path gets free
  via escape analysis (HD-4).
- **Identical** payloads, warmup (5×1s), measurement (5×1s), forks (2), `@Param` sizes both
  sides; the codec benches run both legs in one JMH process.
- **Correctness gate (charter hard-rule 5):** every best-JDK and best-Netty encoder is proven
  **byte-identical** to the production `FrameCodec`/`EdgeFrameCodec` (`WireH2HCorrectnessTest`,
  `NettyWireH2HCorrectnessTest`); the Netty HTTP server is proven to serve responses identical to
  the production JDK server (`NettyEdgeReadServerCorrectnessTest`). A faster stack that changes
  the wire / response is disqualified.

## HD-2 — Allocation is the trustworthy axis; throughput/latency are relative-only
**Type:** methodology · **Status:** decided · **Veto window:** open

This is a 2-vCPU shared box. `gc.alloc.rate.norm` (B/op) is CPU-count-independent → trustworthy
in absolute terms, and is the charter's central question (allocation/GC). Throughput (ns/op,
req/s) and HdrHistogram tail are reported only as a **relative** JDK-vs-Netty comparison on the
identical workload on the same box — never as production-grade absolutes. For the HTTP surface,
client and server are co-located on the 2 vCPU, so throughput is doubly relative-only; allocation
(B/request) is unaffected by the co-location and remains the trustworthy axis.

## HD-3 — Edge-read server-side allocation isolated via an out-of-JVM client + thread-allocation deltas
**Type:** methodology · **Status:** decided · **Veto window:** open

Phase R's edge-read conviction was explicitly *provisional*, gated on the **server-side**
allocation split (its `-prof gc` was JVM-wide = client + server, an upper bound). Resolution:

- The load client runs in a **separate JVM** (`EdgeReadLoadClientMain`), so all client
  allocation (the JDK `HttpClient`, plausibly the larger half of Phase R's ~36 KB) is excluded
  by construction.
- The server JVM self-measures `com.sun.management.ThreadMXBean.getTotalThreadAllocatedBytes()`
  (exact, not sampled; counts terminated threads and the carriers that back virtual threads)
  across a window delimited by a plain control socket (`START n` … `STOP`). Delta ÷ n =
  server-side B/request.
- An `IDLE <ms>` command measures the background-thread allocation floor (GC/JIT housekeeping) so
  the report shows noise-vs-signal; `measureReqs` is large (≥200k) so per-request dominates.

This is what makes the edge-read comparison apples-to-apples — the same harness drives the
production JDK `EdgeHttpServer` and the strongest-Netty `NettyEdgeReadServer`, so the B/request
delta is the transport-shell allocation the verdict turns on.

## HD-4 — Why best-JDK codec hits ~0 B/op and best-Netty does not (the decomposition)
**Type:** technical/analysis · **Status:** decided · **Veto window:** open

Measured: best-JDK consensus send = **~0 B/op** across all payloads; best-Netty (pooled
`ByteBuf`) = **~160 B/op flat** (pre-CRC-reuse). The difference is escape analysis, and it is the
crux of the codec-internal-vs-transport question:

- The JDK reused **heap** `ByteBuffer` is a stable scalar-replaceable target; the `new CRC32C()`
  and `buf.duplicate()` inside `FrameCodec.encode(ByteBuffer)` never escape and are
  scalar-replaced → 0 heap allocation steady state.
- The Netty pooled **direct** `ByteBuf` *escapes* (it is passed to `release()`, and in a real
  pipeline handed to the socket write), so EA cannot scalar-replace it; `out.nioBuffer()` (the
  zero-copy view needed to CRC over direct memory) allocates a `DirectByteBuffer` view object per
  op, and the per-message `ByteBuf` holder is recycled but the view is not. Reusing the `CRC32C`
  (HD-1) removes that term; the residual is the pooled-buffer's intrinsic per-op heap cost.

Conclusion the data forces: for these length-prefixed CRC frames, the *output-buffer* allocation
is removable to ~0 **in the JDK with a reused buffer**, and Netty's pooled buffer (off-heap for
the payload bytes) cannot beat zero on heap B/op — it adds a small per-op heap cost the JDK path
does not pay. The pooled `ByteBuf` only ever addresses the output-buffer term; it does nothing for
the message-building term (below).

## HD-5 — The codec-internal (message-building) term is measured directly and is transport-agnostic
**Type:** technical/analysis · **Status:** decided · **Veto window:** open

Phase R claimed the fan-out 71 KB/op is dominated by **codec-internal churn upstream of the
transport** — `CommandCodec.encodeBatch` blobs + `ConfigDelta.signature()`/`.nonce()` defensive
clones per notification — which a pooled socket buffer cannot touch. The `messageBuildingFloor`
bench leg measures exactly this term in isolation. Both best-JDK (reused buffer) and best-Netty
(pooled `ByteBuf`) still call this code (it is the public data-model API the production codec
calls), so the floor is paid identically by both. The head-to-head therefore separates:
*output-buffer* allocation (transport-addressable: reused JDK buffer ≈ pooled Netty buffer) from
*message-building* allocation (transport-agnostic floor neither removes; removable only by a
JDK-side data-model into-variant refactor, orthogonal to Netty).

## HD-6 — Netty 4.2 anti-rigging discipline enforced in the run manifest
**Type:** methodology · **Status:** decided · **Veto window:** open

Per the research doc's anti-rigging checklist (and charter hard-rule 4): the Netty benchmark
forks run with `-Dio.netty.leakDetection.level=DISABLED` (the detector samples and allocates
tracking records — leaving it on taxes Netty in a way the JDK baseline never sees; zero-leak is
proven separately by the `release()`-in-`finally` discipline + correctness tests at default
SIMPLE detection), `--enable-native-access=ALL-UNNAMED` (JDK 25 FFM/`MemorySegment` path, no
`Unsafe`), and `-Dio.netty.allocator.numDirectArenas=2` (arenas pinned to the 2-vCPU box, 1:1
with worker threads — reproducible, no cross-arena artifacts). The pooled allocator is warmed
once in `@Setup` so the first measured op is not a one-time arena/chunk allocation.

## HD-7 — io_uring is documented, not benchmarked (charter hard-rule 6)
**Type:** scope · **Status:** decided

The research doc (§6) records the 4.2 io_uring API (`io.netty.channel.uring.IoUringIoHandler`)
and its kernel floor (~5.9; this box's `7.0.0-1006-aws` exceeds it, so it would *in principle* be
supported). No io_uring numbers are produced — Netty is measured on Epoll/NIO only. io_uring is a
separate axis; any future io_uring claim requires its own measured run.

## HD-9 — Independent 2nd-agent verification: all three verdicts upheld, two calibrations folded in
**Type:** methodology/verification · **Status:** decided

A fresh `benchmark-verifier` adversarially audited the harness (the charter §4 second agent) and
**independently reproduced every headline allocation number** on the same box — see
[verification.md](verification.md). Outcome: **all three verdicts SUPPORTED, none flips, zero
rigging.** Q1–Q5 all PASS. Highlights: (a) it wrote its own *stronger*-Netty consensus candidate
(`internalNioBuffer` reusing the cached view) and it also landed at **160 B/op** — confirming 160
is the honest pooled-direct floor, not a strawman (the only sub-160 option, a pooled *heap* buffer
at 96 B/op, abandons off-heap pooling and is still worse than the JDK's ~0); (b) it empirically
verified the load-bearing edge-read assumption — `getTotalThreadAllocatedBytes()` **does** capture
virtual-thread allocation (capture fraction 1.002), so the JDK 15,010 B/req is not understated;
(c) the smoking-gun equalities (`jdkBestEncodeInto ≡ messageBuildingFloor`; `nettyBest = floor +
256` constant) reproduced exactly; (d) consensus 88 B/op reconciles by hand (40 B frame + 48 B
sender-id wrap). Two **calibration notes**, both folded into [verdict.md](verdict.md):
- **F-V1** — edge-read *throughput* is volatile (the verifier's run saw JDK marginally ahead, the
  opposite direction); the edge-read verdict now rests on **allocation** (8.7–8.8×, reproduced),
  not throughput. Validates HD-2.
- **F-V2** — consensus "2–5× slower" is a small/heartbeat-frame statement (the M3 hot path); at
  4096 B the two converge. Verdict prose scoped accordingly.

The verifier's `run-edge-read-h2h.sh` reproduction (N=80k) overwrote the intermediate
`raw/edge-read-{jdk,netty}-{server,client}.txt`; the authoritative aggregate
`raw/edge-read-h2h.txt` (N=200k, the verdict's source) is intact.

## HD-10 — End-to-end consensus-send harness: two operator-caught flaws, corrected
**Type:** methodology/verification · **Status:** decided

To answer the operator's "move to `ByteBuf` entirely, the Netty way," an end-to-end send harness
(`ConsensusSendE2EMain` + `ConsensusDrainServerMain`, plaintext, separate drain JVM) was built.
The **first version was wrong and is corrected** — it drove Netty non-idiomatically, and the
operator caught two of the three flaws:

1. **Per-message `writeAndFlush`** (a syscall each) defeated Netty's flush batching → unfair
   throughput. Fix: idiomatic `write()` + batched flush, and a **batched-JDK peer**
   (`BufferedOutputStream`, flush/64) so throughput is compared batching-to-batching.
2. **(operator) Allocating the `ByteBuf` on the main thread + `writeAndFlush` from outside the
   event loop** → a per-message `AbstractChannelHandlerContext$WriteTask` + cross-thread Recycler
   misses + a per-message `nioBuffer` view. A JFR `jdk.ObjectAllocationSample` profile named all
   three. Fix: an in-pipeline `MessageToByteEncoder` (`NettyConsensusFrameEncoder`) so encode/
   alloc/release run on the event loop (Recycler works), with `internalNioBuffer` for the CRC.
3. **(operator) Rebuilding the event-loop group for the measured call** → the measured window ran
   on a cold event-loop thread (empty arena caches / Recycler). Fix: build the connection ONCE and
   run warmup AND measurement on that same warm connection.

**Corrected result (payload-0 heartbeat, warm connection, 500k sends, reproduced 3×):** jdk &
jdk-batched **0 B/msg**; netty-manual 195.6 B/msg; **netty-idiomatic 44.0 B/msg** (the residual
`WriteTask` from writing to a Netty channel from an external protocol thread). Throughput:
unbatched JDK 72.6k ≈ netty-manual 73.4k (**tied** — the first version's "JDK faster" was noise);
batched, jdk-batched 1.44M > netty-idiomatic 426k. The 4096 leg was dropped (the non-idiomatic
netty-manual path starves under the harness's park-backpressure at large frames — a harness
artifact). **Net correction:** idiomatic Netty on consensus is *competitive, not catastrophic*
(44 B/msg, throughput-tied unbatched); the verdict stays **JDK-fix-sufficient** because Netty adds
a dependency + 44 B/msg + event-loop machinery for **zero gain** on a single ordered stream — not
because it is slow. The same idiomatic Netty won edge-read HTTP 8.7× (its best-case shape). The
earlier verdict text claiming "195 B/msg, JDK faster at every payload" was the artifact and has
been corrected in [verdict.md](verdict.md).

## HD-11 — Two more anti-Netty artifacts found + profiled away (buffer sizing; encode-only holder)
**Type:** methodology/verification · **Status:** decided

Continuing the HD-10 pattern (every harness flaw biased against Netty), two more were found and the
consensus-Netty cost was pinned by JFR allocation profiling — not guessed:

1. **(operator-prompted) Unsized `MessageToByteEncoder` buffer.** `NettyConsensusFrameEncoder` let
   the framework allocate the default 256 B `out`, so a 4 KB frame reallocated+copied as it grew
   (`ensureWritable`→`reallocate`→`DirectByteBuffer.duplicate`, confirmed in the JFR stack) — every
   message. This inflated the e2e 4096 number to 104 B/msg. Fixed by overriding `allocateBuffer` to
   the exact frame size → **4096 dropped to 39.6 B/msg**, matching the heartbeat (≈ one `WriteTask`).
2. **The encode-only JMH "160 B/op" overstates idiomatic Netty.** `NettyEncodeOnlyProfileMain` + JFR
   ([raw/encode-only-attr.txt](raw/encode-only-attr.txt)): the 160 is the `PooledDirectByteBuf`
   *holder* allocated per op, because the manual `alloc.directBuffer()`→`release()` microbench loop
   doesn't engage the Recycler (`internalNioBuffer` mode = 160 = holder; `nioBuffer` mode = 288 =
   holder + an un-EA'd `DirectByteBuffer` view). In the **in-pipeline** path the e2e JFR shows **no
   `PooledByteBuf` in the steady-state top types** — the framework recycles the holder on the event
   loop — so **idiomatic Netty's encode is ~0**, and the only residual is the per-message
   `WriteTask` (~40 B/msg) from writing off the event loop. Confirmed by the `nettyReusedDirectNoRelease`
   diagnostic (reuse one buffer → ~0).

**Net:** the honest idiomatic-Netty consensus send is **~0 encode + ~40 B/msg WriteTask**, not the
160/195/104 earlier numbers (all artifacts). The consensus verdict (**JDK-fix-sufficient**) is
UNCHANGED in direction — JDK is 0 B/op with a free, zero-dependency in-place fix; Netty is
*equal-and-pointless* on a single ordered stream (a dependency + WriteTask for no gain) — but the
framing "Netty loses badly" was wrong and is corrected in [verdict.md](verdict.md). Standing caveat
for any future reader: this investigation repeatedly found my Netty harness too pessimistic; trust
the profiled in-pipeline numbers, and treat isolation microbenches as upper bounds on Netty cost.

## HD-12 — JDK side reverified = 0 B/msg; the profiled "40" was a JFR jdk.SocketWrite artifact
**Type:** methodology/verification · **Status:** decided

The operator suspected the JDK numbers too. Reverified — and the JDK 0 holds, but the check
surfaced a real **profiling** artifact worth recording:

- **JDK e2e send = 0.0 B/msg**, exact (`getTotalThreadAllocatedBytes`), **no JFR**, 2,000,000 sends,
  both payloads (0 and 4096), both variants (jdk, jdk-batched). Triple-confirmed
  ([raw/jdk-reverify.txt](raw/jdk-reverify.txt)) — the reused heap buffer + escape-analyzed
  `CRC32C`/`duplicate` inside `FrameCodec.encode(ByteBuffer)` + the socket write's cached temp
  direct buffer mean ~0 heap. The work is real (the drain receives correct framed bytes, so the CRC
  is computed; it is EA that removes the *objects*, not DCE that removes the *work*).
- **The artifact:** when the JDK leg is profiled *with* JFR (`settings=profile`), it reports
  **40 B/msg**, and the JFR breakdown is dominated by `java.net.InetSocketAddress` +
  `InetSocketAddressHolder` ([raw/e2e-attr-jdk-0.txt](raw/e2e-attr-jdk-0.txt)) — **JFR's own
  `jdk.SocketWrite` event** allocates an `InetSocketAddress` per write to record the peer. It fires
  for **JDK sockets but NOT Netty's native epoll**, so JFR profiling silently adds ~40 B/msg to the
  JDK path only. Had I trusted the *profiled* JDK number I would have wrongly reported JDK = 40.
- **Why the headline is unaffected:** all headline numbers (the four-way e2e, the codec JMH, the
  edge-read HTTP server-side split) are measured **without** JFR — JFR was used only for *type
  attribution*. So the JFR `SocketWrite` pollution never touched a reported comparison; the JDK 0
  and Netty ~40 (WriteTask) headline both come from non-JFR exact counters.

**Net:** JDK consensus send = 0 B/msg (verified); idiomatic Netty = ~0 encode + ~40 B/msg WriteTask
(verified). The consensus verdict (JDK-fix-sufficient; Netty equal-and-pointless) stands. Standing
caveat: **never read allocation off a JFR-profiled run for a JDK-socket path** — its `SocketWrite`
events tax that path; use `getTotalThreadAllocatedBytes` without JFR, and use JFR only to name types.

## HD-13 — The WriteTask is eliminable: event-loop-driven Netty consensus send ties JDK at ~0
**Type:** technical/analysis · **Status:** decided

The operator asked whether the ~40 B/msg `WriteTask` can be brought to 0. Investigated in Netty
source and confirmed empirically — **yes.**

- **Source:** `AbstractChannelHandlerContext.write` (netty-transport 4.2.15, line 780) allocates a
  `WriteTask` **only** on its `else` (off-`inEventLoop()`) branch; called *from* the event loop it
  invokes the handler write **inline**, no allocation. So the `WriteTask` exists purely because the
  e2e wrote from the main (producer) thread.
- **Empirical:** an event-loop-driven variant (`Drainer`, a reused self-rescheduling `Runnable` that
  writes a chunk inline + flushes) — modelling how heartbeats (`eventLoop.scheduleAtFixedRate`) and
  batched appends are actually sent — shows **0.0 B/msg at payload 4096** and **13.8 at the
  heartbeat**, with **no `WriteTask` in the JFR breakdown** ([raw/e2e-attr-eventloop-0.txt],
  [raw/eventloop-check.txt]). The 13.8 residual is event-loop task-queue churn (`Object[]` /
  `AtomicReferenceArray`) from the demo's self-reschedule under backpressure — not `WriteTask` —
  and reducible to ~0 with a `scheduleAtFixedRate` heartbeat. Throughput was also higher (1.18M vs
  jdk-batched 1.04M msg/s at payload 0).

**Consequence for the verdict:** done properly (in-pipeline encoder + sized buffer + written from
the event loop) **Netty consensus send ties the JDK at ~0 B/msg.** The whole "Netty allocates more
on the wire codec" story dissolves — it was off-event-loop / microbench / unsized-buffer artifacts
(40 → 195 → 104 → 160 → 43 → now ~0). The consensus verdict shifts from "Netty loses" to
**"JDK-fix-sufficient on COST"**: both reach ~0, but the JDK gets there with a free, zero-dependency,
already-shipped in-place fix, whereas Netty needs a dependency + an event-loop rearchitecture to
match it — no performance gain. Recorded in [verdict.md](verdict.md). This is the Nth time deeper
verification moved Netty closer to (here, level with) the JDK; the operator's "Netty done properly
is at least as good" is borne out on the wire codecs.

## HD-8 — Background-task harness gotcha (process hygiene, logged)
**Type:** technical · **Status:** decided

A consensus run was launched with both `nohup … &` *and* the harness `run_in_background` flag;
the inner `&` detached the JVM so the shell returned exit 0 immediately and the harness reported
"completed" while JMH was still at 45% (caught by tailing the raw file; the run was allowed to
finish and its results are valid). Remaining runs use `run_in_background` **without** an inner
`&`. No data was affected — the raw `.txt`/`.json` are written by JMH directly and were verified
complete before use.
