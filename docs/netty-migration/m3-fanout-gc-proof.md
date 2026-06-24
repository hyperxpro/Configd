# M3 — fan-out NOTIFY encode: the single-pass codec floor (`-prof gc`)

> **What this proves.** The head-to-head ([verdict.md](../jdk-vs-netty/verdict.md) Surface 3) found
> the fan-out 69 KB→floor allocation win is the **single-pass codec rewrite**, codec-internal and
> **transport-independent**: a reused/pooled output buffer drives the output-buffer term to zero,
> leaving exactly the message-building floor that *no* transport removes. M3 shipped that rewrite as
> the production `EdgeFrameCodec.encodeInto(EdgeFrame, FrameSink)` (DR-N10) and this measures the
> **production** encoder (not the testkit head-to-head encoders) on both buffer backends.

## Method
`FanOutEncodeIntoBenchmark` (configd-testkit), the batch-64 signed NOTIFY (Ed25519 sig + non-zero
epoch + 8-byte nonce + 64-byte value) — the same steady-state shape as the head-to-head. JMH
`-prof gc`, 2 forks × (5 warmup + 5 measurement) iterations, JDK 25. `gc.alloc.rate.norm` (B/op) is
the trustworthy, CPU-count-independent axis on this 2-vCPU box; ns/op is relative-only.

```
java --enable-preview -jar configd-testkit/target/benchmarks.jar \
    'FanOutEncodeIntoBenchmark' -prof gc -f 2 -wi 5 -i 5 -w 1 -r 1
```
Raw capture: [m3-bench/floor-encode-into.txt](m3-bench/floor-encode-into.txt).

## Result (batch 64)

| Leg | B/op | ns/op | What it is |
|---|---|---|---|
| `legacyMultiPassEncode` | **69,492.166** ± 19.1 | ~21,900 | the pre-M3 multi-pass `encode` (intermediate `List<byte[]>` + per-notification + payload + out arrays) — the baseline |
| `messageBuildingFloor` | **25,520.063** ± 0.002 | ~9,900 | the irreducible floor: `CommandCodec.encodeBatch` + signature/nonce defensive clones, per notification |
| **`prodEncodeIntoHeapReused`** | **25,520.111** ± 0.004 | ~15,800 | production `EdgeFrameCodec.encodeInto` into a reused `HeapFrameSink` (the JDK fan-out backend) |
| **`prodEncodeIntoByteBufPooled`** | **25,760.153** ± 0.003 | ~16,400 | production `encodeInto` into a pooled, released Netty `ByteBuf` (the in-pipeline encoder) |

## Reading

- **The production single-pass encode hits the floor byte-for-byte.**
  `prodEncodeIntoHeapReused` = **25,520.111** ≡ `messageBuildingFloor` **25,520.063** (Δ ≈ 0.05 B/op,
  noise). The single-pass into-buffer rewrite drives the output-buffer term — the entire 63% churn
  of the status quo — to *literally zero*; what remains is exactly the codec-internal
  message-building floor. This is the `jdkBestEncodeInto ≡ messageBuildingFloor` identity the
  head-to-head predicted (25,520), now reproduced by the **shipped production** encoder.

- **Netty does not regress the floor.** `prodEncodeIntoByteBufPooled` = **25,760.153** = floor +
  **240 B/op** — the pooled-`ByteBuf` holder bookkeeping (the head-to-head predicted +256; the
  production encoder is marginally leaner). Idiomatic Netty (single-pass `encodeInto` into a pooled
  *direct* `ByteBuf`, on the event loop via `EdgeFrameToByteEncoder extends MessageToByteEncoder`,
  `preferDirect`) ties the JDK reused-buffer path at the floor. The 240 B/op is heap holder
  bookkeeping; the frame bytes are off-heap (direct), so they do not appear in this heap `-prof gc`.

- **The win is the rewrite, not the transport.** Baseline **69,492** → floor **25,520** is the
  single-pass rewrite (DR-N10), which is identical regardless of transport (proven on a heap
  `byte[]` backend). Netty contributes nothing to the floor and removes nothing from it — exactly
  the charter's framing: *the fan-out floor win is codec-internal; Netty ties at the floor.*

## Byte-identity (the rewrite is faithful)
`encode(EdgeFrame): byte[]` delegates to `encodeInto`, so `EdgeFrameCodecGoldenFixtureTest`,
`EdgeFrameCodecPropertyTest`, `EdgeFrameCodecFuzzTest`, and `EdgeCodecBoundaryTest` (all unchanged)
exercise the single-pass path through `encode` and stay green — the golden fixtures guard the
rewrite's wire bytes for free. The Netty `ByteBufFrameSink` and the JDK `HeapFrameSink` are proven
to produce identical bytes across transports by the `FanOutServerContract` wire checks.

## Honest caveats
- **Allocation only.** No throughput/latency claim — that is a same-box wash on 2 vCPU, as the
  head-to-head established; the verdict rests on allocation.
- **ns/op is relative.** The reused-heap leg's ns/op (~15.8 µs) exceeds the pure-floor leg (~9.9 µs)
  because it also writes the full frame; this is not a regression — the *allocation* is the floor.
- **io_uring deferred to Phase V.** The encode floor is a CPU/heap property, orthogonal to the
  io_uring syscall axis (EC2-gated, not run here).
