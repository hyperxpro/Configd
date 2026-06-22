# Netty-migration — handoff (Phase R complete; paused at the baseline)

**Status:** Phase R (research + inventory + allocation baseline) is **complete and audited**.
Work **paused at the baseline by operator decision** — **no transport or codec code was
changed** this session. The only code added is **test-only, additive** JMH benchmarks under
`configd-testkit`. `main` is untouched; this is a clean, green seam.

## What this session established

The charter's premise ("migrate JDK/Spring transport → Netty for zero-alloc") was corrected
against reality and then **measured**:

1. **No Spring, no Netty, no NIO selectors exist.** All four surfaces are JDK blocking-socket
   + virtual threads. [ADR-0010](../decisions/adr-0010-netty-grpc-transport.md) (Netty+gRPC+
   Spring) is Superseded fiction; [ADR-0037](../decisions/adr-0037-edge-transport-jdk-stack.md)
   ratified the JDK stack and prices Netty behind a `>1k-subs/node` precondition not met. The
   S7 controls are already framework-decoupled plain-Java services (low migration risk). See
   [inventory.md](inventory.md).
2. **The transport-shell allocation was measured for the first time** (the charter's required-
   but-missing metric), JMH `-prof gc`, all four surfaces, **second-agent-audited** (two
   headline numbers were understated and corrected). See [baseline.md](baseline.md),
   [decision-log.md](decision-log.md) DR-1..5, raw captures in `baseline/`.

## The verdict (measurement reshaped "migrate everything")

| # | Surface | Allocation | **Verdict** |
|---|---------|-----------|-------------|
| 1 | admin API (`HttpApiServer`) | ~38 KB/req e2e | **Acquit — stay on JDK** (control-plane QPS → immaterial) |
| 2 | edge read HTTP (`EdgeHttpServer`) | ~38 KB/req e2e (client+server) | **Convict for Netty — PROVISIONAL** (the one genuine case) |
| 3 | fan-out NOTIFY (`EdgeFrameCodec`) | **1.2 → 71 KB/op** signed | **Convict — codec rewrite first (no Netty)** |
| 4 | consensus wire (`FrameCodec`/`TcpRaftTransport`) | 88 → 8 280 B/op send; floor **≈0** | **Acquit for Netty — in-place fix** |

**Netty earns ~one surface (provisionally); two hot surfaces have cheaper no-Netty fixes; one
is acquitted.**

## Recommended next steps (when work resumes)

Priority order (the cheap, certain wins first; the Netty bet last, behind evidence):

1. **Consensus (4) — no-Netty in-place fix.** `TcpRaftTransport.encodeWire` uses the allocating
   `FrameCodec.encode`; the codec's existing `encode(ByteBuffer)` variant measures ~0 B/op.
   Encode the frame **and** the 4-byte sender id into one reused per-connection buffer; add a
   decode-into for receive. Re-prove: golden-fixture wire bytes unchanged, **M3
   no-spurious-election**, S2–S4. Expected: send 88→~0 B/op.
2. **Fan-out (3) — no-Netty codec rewrite.** Rewrite `EdgeFrameCodec.encode`/`decode` to a
   single-pass into-buffer form (the `FrameCodec` into-variant pattern), killing the
   intermediate `List<byte[]>`/`ByteBuffer`/clone churn. **Re-prove `EdgeFrameCodecGoldenFixtureTest`
   bytes byte-identical** + the signed-delta round-trip (`EdgeFrameCodecPropertyTest`). Expected:
   71 KB → ~frame size per batch.
3. **Edge read (2) — resolve the provisional Netty conviction BEFORE building.** Get the two
   open numbers: (a) the **server-side** allocation split (async-profiler `alloc`, or run the
   client out-of-JVM so `-prof gc` sees only the server), and (b) the **production edge read
   QPS** (confirm "hot"). Only if both hold, build the hand-rolled zero-alloc Netty HTTP/1.1
   read pipeline; a **new ADR supersedes ADR-0037 for this surface only**, citing allocation.
4. **Admin (1) — no change.** Documented acquittal.

Anything migrated to Netty must re-prove its S7 negative tests on the new pipeline (charter §4)
and supersede ADR-0037 via a new ADR for that surface only.

## How to reproduce the baseline

```
./mvnw -B -pl configd-testkit -am package -Dmaven.test.skip=true   # builds benchmarks.jar
java --enable-preview -jar configd-testkit/target/benchmarks.jar \
    'TransportFrameAllocBenchmark|EdgeWireAllocBenchmark|AdminHttpAllocBenchmark|EdgeHttpAllocBenchmark' \
    -prof gc -f 2 -wi 3 -i 4 -w 1 -r 1
```
Benchmarks: `configd-testkit/src/main/java/io/configd/bench/{TransportFrameAlloc,EdgeWireAlloc,AdminHttpAlloc}Benchmark.java`
and `.../io/configd/edge/node/EdgeHttpAllocBenchmark.java` (in the `edge.node` package to reach
package-private `EdgeNodeMetrics`). 2-vCPU box: run JMH one job at a time; B/op is
CPU-count-independent so the numbers are trustworthy (latency is not measured/claimed).

## Residual caveats (carry forward)
- HTTP numbers are **end-to-end (client+server)** — an upper bound on server allocation. The
  edge-read conviction is **not proven** until the server-side split + QPS are measured.
- Fan-out 71 KB/op is itself a **floor**: `FanOutSessionCore` batch assembly + the `SSLSocket`
  write add more (same direction), not included in the codec benchmark.
- Consensus M3 heartbeat path is touched by fix (1) — its timing (no-spurious-election) must be
  re-verified, not assumed.
