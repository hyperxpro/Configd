# JDK-vs-Netty head-to-head — independent verification (adversarial 2nd-agent audit)

> **Mandate.** Independent `benchmark-verifier`: assume the harness may be (unintentionally) rigged
> toward a prior and try to break the three per-surface verdicts. Phase R's own 2nd-agent audit
> caught two understated numbers, so understatement/overstatement was expected here too. This report
> answers the five audit questions with evidence, reproduces the headline numbers on the same
> 2-vCPU box (JDK 25 Corretto, Netty 4.2.15.Final, one JMH job at a time), and states whether each
> verdict stands. **No benchmark/verdict file was edited** — this file is additive.

## Bottom line (the one-liner the brief asked for)

The comparison **is genuinely apples-to-apples**, and all three per-surface verdicts —
**JDK-wins-consensus, codec-rewrite-wins-fanout, Netty-wins-edge-read** — are **SUPPORTED by the
evidence**. None flips. Every headline allocation number (the trustworthy axis) reproduced within
noise; the "smoking gun" equalities (`jdkBestEncodeInto ≡ messageBuildingFloor`,
`nettyBest = floor + 256`, consensus Netty = 160 flat) reproduced exactly. I actively tried to beat
the best-Netty 160 B/op with a stronger CRC strategy and could not. Two minor calibration notes
(neither flips a verdict) and zero rigging found.

---

## Reproduced vs claimed — the trustworthy axis (gc.alloc.rate.norm, B/op)

Reproduced with `-prof gc -f 1 -wi 3 -i 3` (lighter than the authoritative `-f 2 -wi 5 -i 5`, so
ns/op carry more variance; **B/op is deterministic and is the axis the verdict turns on**).

### Surface 4 — consensus wire (`ConsensusWireH2HBenchmark`)

| leg | param | claimed B/op | **reproduced B/op** | claimed ns | repro ns | verdict |
|---|---|---|---|---|---|---|
| jdkStatusQuoSend | 0 / 256 / 4096 | 88 / 600 / 8280 | **88 / 600 / 8280** | 51 / 182 / 2523 | 51 / 169 / 2461 | exact |
| jdkBestSendInto | 0 / 256 / 4096 | ~0 / ~0 / ~0 | **0 / 0 / 0** | 55 / 121 / 1180 | 50 / 118 / 1049 | exact |
| nettyBestSendPooled | 0 / 256 / 4096 | 160 / 160 / 160 | **160 / 160 / 160** | 299 / 376 / 1290 | 273 / 339 / 1300 | exact |
| jdkDecode | 0 / 256 / 4096 | 48 / 304 / 4144 | **48 / 304 / 4144** | 34 / 118 / 1587 | 34 / 108 / 1547 | exact |

### Surface 3 — fan-out NOTIFY (`FanOutWireH2HBenchmark`)

| leg | param | claimed B/op | **reproduced B/op** | claimed ns | repro ns | verdict |
|---|---|---|---|---|---|---|
| jdkStatusQuoEncode | 1 / 16 / 64 | 1152 / 17352 / 69492 | **1152 / 17352 / 69480** | 348 / 5423 / 21551 | 350 / 5348 / 22742 | match (64: −12 B, 0.017%) |
| jdkBestEncodeInto | 1 / 16 / 64 | 392 / 6320 / 25520.1 | **392 / 6320 / 25520.1** | 292 / 3973 / 15455 | 294 / 3982 / 16019 | exact |
| messageBuildingFloor | 1 / 16 / 64 | 392 / 6320 / 25520.1 | **392 / 6320 / 25520.1** | 130 / 2288 / 9852 | 132 / 2270 / 8793 | exact |
| nettyBestEncodePooled | 1 / 16 / 64 | 648 / 6576 / 25776.1 | **648 / 6576 / 25776.1** | 749 / 5031 / 18360 | 711 / 5073 / 19008 | exact |
| jdkDecodeNotify | 1 / 16 / 64 | 904 / 13696 / 54616 | **904 / 13696 / 54616.1** | — | — | exact |

**Both smoking-gun equalities reproduce exactly:** `jdkBestEncodeInto ≡ messageBuildingFloor`
(392/6320/25520.1, byte-for-byte at every batch) and `nettyBestEncodePooled − messageBuildingFloor
= 256.0` at **every** batch size (1, 16, 64 — a *constant* per-op overhead, not per-notification).

### Surface 2 — edge-read HTTP (out-of-JVM client, server self-measures, N=80,000)

| server | claimed B/req | **reproduced B/req** | claimed req/s | repro req/s | epoll | bg floor |
|---|---|---|---|---|---|---|
| JDK `EdgeHttpServer` | 15,010 | **14,988** (−0.15%) | 5,948 | 5,001 | false | 88 B/s |
| Netty `NettyEdgeReadServer` | 1,716 | **1,703** (−0.8%) | 6,724 | 4,920 | true | 40 B/s |
| **ratio (JDK÷Netty)** | **8.7×** | **8.8×** | — | — | — | — |

Background idle floor (40–88 **B/s**) is ~6 orders of magnitude below the ~15 KB/req signal — the
noise-vs-signal claim holds; the per-request delta is real.

---

## The five audit questions

### Q1 — Apples-to-apples? **PASS**

- The authoritative `raw/codec.json` carries **one identical `jvmArgs` set across all 27 records**
  (both JDK and Netty legs): `--enable-preview`, `--enable-native-access=ALL-UNNAMED`,
  `-Dio.netty.leakDetection.level=DISABLED`, `-Dio.netty.allocator.numDirectArenas=2`. No leg gets
  flags another does not. `forks=2, warmupIterations=5, measurementIterations=5` for **every**
  record. Same `@Param` payload/batch sizes both sides. The JDK and Netty legs run in the **same
  JMH process** (one benchmark class), so JIT/GC conditions are shared.
- Payloads are byte-identical and built once in `@Setup` (same `value`, `senderId`, signed
  Ed25519-shaped delta with non-zero epoch + 8-byte nonce, matching the Phase R baseline shape).
- `-prof gc` (gc.alloc.rate.norm) excludes warmup by construction; the pooled allocator is warmed
  once in `@Setup` so the first measured op is not a one-time arena allocation.
- Edge-read: the SAME harness (`EdgeReadAllocServerMain` + out-of-JVM `EdgeReadLoadClientMain`)
  drives both servers; identical key count (256), value bytes (64), concurrency, request count.
- No asymmetry that flatters either side was found.

### Q2 — Is "best Netty" actually its best, or strawmanned? **PASS** (I tried to beat it; could not)

The consensus 160 B/op is a **genuine intrinsic cost**, not a harness artifact:
- Leak detection is genuinely DISABLED in the timed forks (verified in `codec.json` jvmArgs and my
  reruns), so Netty is not taxed for tracking records. Arenas pinned to 2 (1:1 with workers).
  `--enable-native-access` engages the JDK-25 `MemorySegment` path (no `Unsafe`).
- The 160 is the per-op `ByteBuf` holder bookkeeping plus the `nioBuffer()` view needed to run
  CRC32C over a pooled **direct** buffer (CRC32C has no `ByteBuf` API). I confirmed the harness
  does NOT allocate a fresh buffer where a real pipeline wouldn't: it allocates **one pooled buffer
  per op and releases it per op** — exactly the steady-state pattern a real
  `MessageToByteEncoder` follows (buffer per outbound message, released after the socket write).
- **I tried to build a legitimately stronger Netty and could not beat 160.** I wrote a JMH bench
  (in-package, calling the **real** `NettyWireEncoders.encodeSendWireInto`) plus an
  `internalNioBuffer()` candidate (reuses the buffer's cached view instead of a fresh `nioBuffer()`
  view). **Both land at exactly 160.0 B/op** — JIT escape-analysis already scalar-replaces the
  per-op `nioBuffer()` view, so the "stronger" `internalNioBuffer` ties, buys nothing. The only
  sub-160 option is a pooled **heap** `ByteBuf` + CRC-over-`array()` (96 B/op) — but that abandons
  off-heap pooling (Netty's entire raison-d'être on this surface) and is strictly worse than the
  JDK's own reused heap buffer (~0). So 160 is the honest pooled-direct floor; the verdict did not
  strawman Netty. (Methodology note: a hand-rolled standalone replica mis-measured the same path at
  224 B/op due to inlining/EA differences — always benchmark the actual method; the in-package
  call to the real method is authoritative and gives 160.)
- The fan-out `+256` likewise reproduces as a **constant** per-op overhead (not per-notification),
  consistent with a single pooled-`ByteBuf` allocation — Netty's genuine, unavoidable per-op cost.

### Q3 — Is "best JDK" actually best, or flattered? **PASS**

- The `jdkBestSendInto` ~0 is **real escape analysis**, re-derived and re-measured: it calls the
  **production** `FrameCodec.encode(ByteBuffer)` into-variant on a reused **heap** `ByteBuffer`;
  the `new CRC32C()` and `buf.duplicate()` inside never escape → scalar-replaced → 0 B/op steady
  state. This is not a cheaper fake — it is the genuine production code path.
- **Byte-identity to production wire is proven, not asserted.** `WireH2HCorrectnessTest` and
  `NettyWireH2HCorrectnessTest` compare every best-JDK and best-Netty encoder, at every benchmarked
  size, against the **real** `FrameCodec.encode`/`EdgeFrameCodec.encode` output via
  `assertArrayEquals`, AND round-trip the bytes through the production decoder. I read them — they
  are **not vacuous** (real expected-vs-actual byte arrays; round-trip asserts the decoded type and
  count). All three correctness tests pass: 2/2, 2/2, 1/1.
- **Edge-read: no obvious JDK optimization was skipped to flatter Netty.** The JDK
  `EdgeHttpServer` already runs the strongest JDK form: `newVirtualThreadPerTaskExecutor()`
  (unbounded vthreads — best-case concurrency, NOT throttled), fixed-length responses
  (`sendResponseHeaders(200, len)`), counters-only request-path logging. Its per-request allocation
  (`HttpExchange`, `Headers` HashMaps, request/response streams) is allocated **inside
  `com.sun.net.httpserver`** and is not user-tunable without forking the JDK library — there is
  genuinely no into-buffer lever as the codecs had. The claim "no JDK in-place lever for the HTTP
  shell" is accurate.

### Q4 — Edge-read server-side measurement soundness? **PASS** (the load-bearing assumption holds)

- **The critical assumption — that `getTotalThreadAllocatedBytes()` captures virtual-thread
  allocation — is TRUE.** I verified it empirically: a probe allocating a known 200 MiB across
  virtual threads measured a delta of 200.4 MiB (capture fraction **1.002**); the platform-thread
  control was 200.0 MiB. So the JDK server's vthread-resident request handlers are **fully
  counted** — the 15,010 B/req is **not understated** by uncounted vthread allocation. (This rules
  out the most plausible "JDK secretly flattered" rigging hypothesis.)
- The client is genuinely **out-of-JVM** (separate `java` process per the run script), so its
  `HttpClient` allocation is excluded by construction.
- **Both servers do equivalent business work**, verified by reading both: same
  `core.stalenessState()` / `isStrongReadKey` / `servesKey` / `parseCursor` / `currentVersion()` /
  `core.get(key)`, same `X-Configd-Cursor/Version/Content-Type` headers, same body bytes, same
  `metrics.onRead()` + `recordReadLatency()`. The Netty server cuts no corner (it still parses
  keep-alive/uri/cursor headers); the JDK server's extra `/health` + `/metrics` contexts are never
  hit by the load client (it drives only `/v1/config/`), so they don't contaminate per-request.
  `NettyEdgeReadServerCorrectnessTest` proves response equivalence across hit/not-subscribed and
  passes. The 15,010-vs-1,716 delta is therefore the transport shell, a fair fight.
- The background idle floor (40–88 B/s) is negligible vs the ~15 KB/req signal.

### Q5 — Under/overstatement spot-check? **PASS** (numbers reconcile to object layout)

- **Consensus status-quo 88 B/op at payload 0 reconciles by hand:** `FrameCodec.encode` →
  `byte[frameSize(0)=22]` = 16-hdr+22 → 40 B (8-aligned); the sender-id wrap `byte[4+22=26]` =
  16+26 → 48 B; **40 + 48 = 88**. Matches exactly. (Confirms my prior note: the production send
  path is *two* arrays — frame + sender-id wrap — and a codec-only micro-bench would understate it;
  this H2H correctly measures both.)
- **Fan-out floor scales linearly** at ~395–400 B per added notification (392→6320→25520 across
  1→16→64), consistent with per-notification message-building (encodeBatch blob + sig/nonce
  defensive clones). The status-quo 69,480 B/op (my repro) vs 69,492 (claimed) differ by 12 B
  (0.017%) — `ArrayList` growth jitter, immaterial.
- No headline number failed to reconcile.

---

## Findings (calibration only — none flips a verdict)

- **F-V1 (minor, throughput volatility — strengthens the verdict's own caveat).** In MY edge-read
  rerun the JDK was marginally *faster* (5,001 vs 4,920 req/s) — the **opposite** of the claimed
  run (5,948 vs 6,724, Netty faster). Throughput flips between runs on this co-located 2-vCPU box.
  This is exactly why the verdict labels throughput **relative-only / not production-grade** (HD-2)
  and rests the headline on **allocation**. The Netty "≈13% higher throughput / lower p50" phrasing
  in the verdict is fragile and run-dependent; the **allocation** win (8.7–8.8×) is rock-solid and
  is the right thing to lead with. Recommend the verdict not lean on the throughput delta for
  edge-read beyond "not slower" — the allocation result alone carries the surface.
- **F-V2 (cosmetic).** Verdict prose says best-Netty consensus is "**2–5× slower**" at the hot
  small frames; at payload 4096 Netty is actually *faster* than JDK status-quo and ~comparable to
  jdkBestSendInto (1290 vs 1180 ns). The "2–5× slower" is true only at the small/heartbeat frames
  (the M3 hot path, which is the relevant one), so the claim is fair in context but is a
  small-frame statement, not a blanket one. No allocation impact.

## What I did NOT find (rigging hypotheses tested and rejected)

- Netty NOT taxed by leak detection (DISABLED, verified in jvmArgs + reruns).
- Netty NOT penalized by a fresh-buffer-per-op that a real pipeline would avoid (per-op allocate+
  release IS the realistic steady state).
- A stronger Netty CRC strategy (`internalNioBuffer`) does NOT beat 160 — no understatement of
  Netty.
- JDK edge-read NOT flattered by uncounted vthread allocation (capture fraction 1.002).
- JDK edge-read NOT given an artificially weak config (unbounded vthreads, fixed-length responses).
- best-JDK codecs NOT a cheaper fake — byte-identical to production via the real codec, proven by
  passing non-vacuous correctness tests.
- No `@Fork`/`@Warmup`/`@Measurement`/payload asymmetry between legs.

---

## Final verdict on the verdict

Apples-to-apples: **YES, genuinely.** The three per-surface verdicts stand:

| surface | verdict | verifier ruling |
|---|---|---|
| 4 — consensus wire | JDK-fix-sufficient (Netty loses both axes) | **SUPPORTED** — JDK ~0 vs Netty 160 reproduced exactly; 160 is the honest pooled-direct floor (could not beat it). |
| 3 — fan-out NOTIFY | codec-rewrite-sufficient (no Netty) | **SUPPORTED** — `jdkBestEncodeInto ≡ messageBuildingFloor` and `nettyBest = floor + 256` reproduced exactly; the dominant residual is codec-internal message-building neither transport removes. |
| 2 — edge-read HTTP | Netty wins (transport-attributable) | **SUPPORTED** — 8.8× less server-side allocation reproduced; measurement sound (vthread alloc captured); fair business-work parity; throughput is the soft part (F-V1) but allocation carries the surface. |

No verdict flips. Two calibration notes (F-V1 throughput volatility, F-V2 small-frame "2–5×"
scoping), zero rigging.

## How to reproduce this verification

```
# Codec H2H (consensus + fan-out), trustworthy B/op axis:
java --enable-preview -jar configd-testkit/target/benchmarks.jar \
    'ConsensusWireH2HBenchmark' -prof gc -f 1 -wi 3 -i 3 -w 1 -r 1
java --enable-preview -jar configd-testkit/target/benchmarks.jar \
    'FanOutWireH2HBenchmark' -prof gc -f 1 -wi 3 -i 3 -w 1 -r 1
# Edge-read H2H (server-side alloc via out-of-JVM client):
docs/jdk-vs-netty/run-edge-read-h2h.sh 8 20000 80000
# Correctness gates (non-vacuous byte-identity + response equivalence):
./mvnw -pl configd-testkit test \
    -Dtest='WireH2HCorrectnessTest,NettyWireH2HCorrectnessTest,NettyEdgeReadServerCorrectnessTest'
```
Verifier-only probes used (transient, not committed): a vthread-allocation-capture probe
(`getTotalThreadAllocatedBytes` over `newVirtualThreadPerTaskExecutor`, fraction 1.002) and an
in-package JMH probe calling the real `NettyWireEncoders.encodeSendWireInto` alongside an
`internalNioBuffer` candidate (both 160 B/op).
