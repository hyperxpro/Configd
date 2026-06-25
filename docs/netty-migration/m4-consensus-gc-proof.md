# M4 — consensus wire encode: the in-pipeline encoder ties the JDK at ~0 B/op (not 160)

> **What this proves.** The head-to-head ([verdict.md](../jdk-vs-netty/verdict.md) §Surface 4) found
> the consensus-wire send allocation win is the codec's existing zero-copy into-buffer write — the
> best-JDK reused-buffer path is ~0 B/op, and **idiomatic, event-loop-driven Netty ties it at ~0**.
> The "Netty loses at 160 B/op" figure was a **microbench artifact**: the per-op
> `io.netty.buffer.PooledDirectByteBuf` *holder*, measured on a plain JMH worker thread off any event
> loop ([raw/encode-only-attr.txt](../jdk-vs-netty/raw/encode-only-attr.txt) attributed it by JFR;
> `NettyEncodeOnlyProfileMain` reproduced the 160 on the main thread via the same
> `getThreadAllocatedBytes` axis used here). M4 shipped that idiomatic path as the production
> `NettyConsensusFrameEncoder` (DR-N17) and this measures the **production** encoder, in the pipeline,
> driven from a real event loop — contrasted against the naive 160 microbench — to prove it is on the
> ~0 side of the cliff.

## Method

[`NettyConsensusFrameEncoderAllocationTest`](../../configd-netty/src/test/java/io/configd/netty/NettyConsensusFrameEncoderAllocationTest.java)
(configd-netty, a JUnit measurement — not JMH, which would need the testkit/shade plumbing). Per-op
heap allocation via `java.lang.management.ThreadMXBean#getThreadAllocatedBytes(threadId)` on the
encoding thread — the trustworthy, CPU-count-independent axis on this 2-vCPU box (ns/op is not
claimed). One `FrameCodec.Frame` is reused across all ops, so the only per-op heap allocation is the
encoder's; 100k warmup ops drive JIT + the pool/recycler to steady state; 500k measured ops; every
produced `ByteBuf` is released. `io.netty.leakDetection.level=disabled` (no leak-tracking allocation
in the timed runs, matching the JMH/profile methodology). The allocator is
`PooledByteBufAllocator.DEFAULT`, identical to production (DR-N17).

Two harnesses, the only difference being the thread the encode runs on:
- **idiomatic / production** — the real `NettyConsensusFrameEncoder` in a Netty pipeline
  (`EmbeddedChannel`), writes originating **on a real `MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())`
  event-loop thread**; a terminal `ReleaseSink` frees the encoded buffer on that same thread —
  the production lifecycle (the `NettyRaftTransport.drain()` writes inline on the event loop, and the
  socket write releases the buffer there). This is exactly the production path, not a replica.
- **naive trap** — the same field writes as a manual `alloc.ioBuffer() → write → CRC → release()`
  loop per op (the head-to-head's microbench shape), run on a **plain (non-Netty) thread** — the JMH
  worker condition that produced the 160.

```
./mvnw -o -pl configd-netty test \
  -Dtest='NettyConsensusFrameEncoderByteIdentityTest,NettyConsensusFrameEncoderAllocationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

## Result (B/op, deterministic — identical across 3 runs)

| payload | idiomatic (event-loop) | naive trap (plain thread) |
|---|---|---|
| 0 (heartbeat) | **12** | 172 |
| 256 (small append) | **12** | 172 |
| 4096 (batch) | **12** | 172 |

**Mechanism — the cliff is the thread type, not pipeline-vs-manual** (payload 256):

| | naive (manual loop) | idiomatic (production encoder) |
|---|---|---|
| **plain thread** (off the loop) | 172 | 172 |
| **event-loop thread** (production) | 12 | 12 |

## Reading

- **The production encoder allocates ~0 B/op on the event loop.** 12 B/op, payload-independent,
  dramatically below the naive 172 and far under the test's generous 64 B/op `~0` ceiling. The 12 is
  **not the encoder's holder** — that is fully recycled to 0. Note from the mechanism table that the
  *bare manual loop* (no channel at all) also reads 12 on the event-loop thread, so the 12 is **not**
  channel write/flush machinery either; it is a small `getThreadAllocatedBytes` floor on a busy
  event-loop thread (background/bookkeeping attribution), common to both legs and an order of
  magnitude under the trap. The frame bytes are off-heap (pooled direct), so they never appear in
  this heap axis. (Cf. the head-to-head's e2e, which measured the event-loop send at ~14 B/msg at the
  heartbeat and 0.0 at 4096 — same ~0 order.)

- **The 160/172 is an off-event-loop artifact, now pinned by the 2×2.** Netty's pooled-`ByteBuf`
  holder `Recycler` and `PoolThreadCache` engage only on a `FastThreadLocalThread` (an event-loop
  thread). On a plain thread **both** the manual loop and the production encoder hit 172 (the holder
  per op); on the event loop **both** drop to 12. So the discriminator is the thread, and the
  production encoder runs **on the event loop by construction** (`NettyRaftTransport.drain()` executes
  on the channel's event loop). This is the precise mechanism behind verdict §Surface 4's "the
  framework recycles the holder on the event loop", and it corrects the tempting (wrong) reading that
  the 160 was about manual-vs-pipeline.

- **The win is the codec + the event loop, not a pooled buffer beating the JDK.** Consensus has no
  codec-internal churn (one frame, no intermediate lists/clones); the entire send allocation is the
  output buffer, which the JDK removes in place to ~0 (reused heap buffer, escape-analyzed) and which
  idiomatic Netty recycles to ~0 on the event loop. Netty does not regress the floor and does not beat
  it — it **ties at ~0**, exactly the charter's framing for this surface.

## Byte-identity (the ~0 is on the *identical* wire)

The allocation win is measured on the exact JDK wire, not a cheaper encoding.
[`NettyConsensusFrameEncoderByteIdentityTest`](../../configd-netty/src/test/java/io/configd/netty/NettyConsensusFrameEncoderByteIdentityTest.java)
drives the production encoder in a pipeline (`EmbeddedChannel`) for a spread of frames (every payload
size incl. empty/256B/4KB, a `MessageType` spread, groupId/term extremes, and six sender ids incl. 0,
the sign bit, and both 32-bit extremes) and asserts the outbound bytes equal
`RaftWireProtocol.encodeWire(senderId, frame)` — `[4B big-endian senderId] || FrameCodec frame` —
byte-for-byte, and that the emitted frame round-trips through `FrameCodec.decode` (CRC-correct). 72
cases, all green.

## Honest caveats

- **Allocation only.** No throughput/latency claim — that is a same-box wash on 2 vCPU, as the
  head-to-head established; the verdict rests on allocation. `getThreadAllocatedBytes` is the
  trustworthy, CPU-independent axis here.
- **12, not literally 0.** The encoder's holder recycles to 0; the 12 B/op is a small measurement-axis
  floor on a busy event-loop thread — the bare manual loop shows the same 12 on the loop (mechanism
  table), so it is not the encoder and not channel machinery. It is payload-independent and an order
  of magnitude below the trap; the production path is unambiguously on the ~0 side. (Production's
  `drain()` is even leaner than this harness: it writes inline on the loop with at-most-one CAS-gated
  wake, then one flush — the verdict's event-loop path reached 0.0 B/msg at 4096 that way.)
- **172 vs the verdict's 160.** Same finding, slightly different meter: the verdict's 160 is JMH
  `gc.alloc.rate.norm`; this is single-thread `getThreadAllocatedBytes` (which also attributes a
  little adjacent bookkeeping). Both name the same `PooledDirectByteBuf` holder allocated per op off
  the event loop — the same side of the cliff.
- **Determinism.** Allocation is CPU-count-independent; the numbers above were identical across 3
  consecutive runs (the test's thresholds are generous precisely because the claim is *which side of
  the cliff*, which is structural, not a tight number).
- **Self-contained.** Test-only + this doc; no `src/main` change, no new module dependency, no SBOM
  impact (configd-testkit is untouched — the proof lives entirely in configd-netty's own tests).
