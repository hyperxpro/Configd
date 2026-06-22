# Netty-migration Phase R — allocation baseline + per-surface verdict

> **The charter's required-but-missing metric.** Measured with JMH `-prof gc`
> (`gc.alloc.rate.norm`, B/op) on the 2-vCPU box, git `001d373`. Allocation/op is
> CPU-count-independent, so these B/op are trustworthy (latency is **not** measured/claimed).
> Raw captures: `baseline/*.txt`. Method + caveats: [decision-log.md](decision-log.md) DR-3.
> The codec numbers are **REV2** (post-audit: signed delta for fan-out, faithful sender-id wrap
> for consensus). The whole baseline survived an independent second-agent replay — all four
> verdicts upheld, headline numbers corrected ([decision-log.md](decision-log.md) DR-5).
>
> **Conviction test (operator steer, DR-2):** migrate a surface to Netty only if its shell
> allocation is **material** AND the surface is **hot** AND **Netty is the cheapest fix**
> (i.e. the allocation is *not* removable in place). Otherwise leave it on the JDK stack.

## Results

### Surface 4 — inter-node consensus wire (`FrameCodec`, `TransportFrameAllocBenchmark`)
| leg | payload=0 (heartbeat) | 256 (small append) | 4096 (batch) |
|---|---|---|---|
| `encodeSendWire` — **TRUE per-message send** (`TcpRaftTransport.encodeWire`: codec frame **+** the 4-byte sender-id `byte[4+frame]` copy) | **88** | **600** | **8,280** |
| `encodeAllocating` — the codec frame alone (the larger half of send) | 40 | 296 | 4,136 |
| `encodeInto` — the **existing, unused** `encode(ByteBuffer)` variant | **≈ 0** | **≈ 0** | **≈ 0** |
| `decode` — receive side | 48 | 304 | 4,144 |

Production send allocates **two** arrays per message (codec frame + a `byte[4+frame]` sender-id
wrap) ≈ 2× the frame — M3 heartbeat send costs **88 B/op each**. **But the codec already ships a
zero-copy encode measuring ~0 B/op** — the transport just doesn't use it. Driving send to ~0
means using that into-variant *and* writing the sender id into the same reused buffer (a bit
more than swapping one overload, but still no new dependency).

### Surface 3 — edge fan-out wire (`EdgeFrameCodec`, `EdgeWireAllocBenchmark`)
SIGNED delta — the production steady-state shape (Ed25519 64-byte sig + non-zero epoch +
8-byte nonce; `ConfigdServer` makes signing mandatory):
| leg | 1 delta | 16 | 64 (`MAX_NOTIFY_BATCH`) |
|---|---|---|---|
| `encodeNotify` — server→edge push | 1,200 | 17,760 | **71,040** |
| `decodeNotify` — edge receive | 988 | 14,104 | 56,152 |
| `encodeHeartbeat` (coalesced keep-alive) | 80 | 80 | 80 |

**~1.1 KB allocated per signed notification on encode (~0.9 KB decode).** The NOTIFY codec
churns intermediate `List<byte[]>`, per-notification `ByteBuffer`s, per-mutation `encodeBatch`
blobs, signature/nonce clones, and `ConfigDelta`/mutation rebuilds — the largest per-op
allocation in the system, on the highest-volume path. (Still a floor: `FanOutSessionCore`'s
batch assembly + the `SSLSocket` write add more, same direction.)

### Surfaces 1 & 2 — HTTP (`com.sun.net.httpserver`, end-to-end loopback)
| surface | `configGet` | `healthLive` (shell floor) | read-path marginal | auth marginal |
|---|---|---|---|---|
| 1 admin (`HttpApiServer`) | 38,263 (off) / 39,578 (on) | 36,868 / 36,590 | ~1.4–3.0 KB | ~1.3 KB |
| 2 edge (`EdgeHttpServer`) | 38,500 | 36,116 | ~2.4 KB | n/a |

**The JDK HTTP round-trip floor is ~36 KB**, reproduced *independently by two different
servers* (strong cross-check). The config read adds only ~1.4–2.4 KB on top — the shell
dominates by ~15×. **Honesty caveat:** `-prof gc` is JVM-wide, so this ~36–39 KB is
**client + server** (the JDK `HttpClient` is itself allocation-heavy). It is an **upper
bound** on the server's per-request allocation; the exact server-side split needs
async-profiler `alloc` attribution (or an out-of-JVM client) — that split is the first task
*if* an HTTP surface is migrated, and is also the migration's own before/after instrument.

## Per-surface verdict

| # | Surface | Material? | Hot? | Cheapest fix | **Verdict** |
|---|---------|-----------|------|--------------|-------------|
| 1 | admin API | yes (~38 KB/req e2e) | **no** (control-plane QPS) | n/a | **ACQUIT — stay on JDK** |
| 2 | edge read HTTP | yes, but **e2e upper bound** (client+server); shell floor ~15× the read marginal, ~1000× the 32 B in-process read | **yes** (edge = high-volume read serving) | **must replace `com.sun.net.httpserver`** | **CONVICT for Netty — PROVISIONAL** (gated on server-side split + QPS) |
| 3 | fan-out NOTIFY | **yes (up to 71 KB/op, signed)** | **yes** (every commit → every subscriber) | **codec rewrite (no Netty)** | **CONVICT — codec first; Netty secondary** |
| 4 | consensus wire | yes (88–8 280 B/op true send) | **yes** (heartbeats/appends) | **the existing into-variant + sender-id-into-buffer (no Netty)** | **ACQUIT for Netty — cheap in-place fix** |

### Reasoning
- **Admin (1):** large per-request garbage, but control-plane QPS makes it immaterial at the
  real workload (37 KB × ~10–100 req/s ≈ sub-MB/s — a non-event for ZGC). The charter itself
  accepts "bounded, justified alloc on the admin path." Migrating means re-proving *all* of S7
  (mTLS/authn/authz/audit/replay/429/strong-read) on a hand-rolled Netty pipeline for
  negligible payoff and maximal risk. **Leave on JDK.**
- **Edge read (2):** the unique case where the charter's thesis holds — *provisionally*. It is
  the high-volume read path whose *in-process* read was deliberately tuned to 32 B/op, then
  wrapped in an HTTP shell. `com.sun.net.httpserver` **cannot be made low-alloc in place** (it
  allocates `HttpExchange`/header maps/streams per request, none reusable), so if the path is
  hot the only lever is replacing the server — Netty's home turf. **The catch (audit DR-5):**
  the ~36 KB floor is `-prof gc` JVM-wide = **client + server**, and the JDK `HttpClient` is
  plausibly the larger half, so the *server-side* shell-vs-read ratio is an **upper bound, not
  measured**. So this conviction is **provisional**, gated on two numbers before any code:
  (a) the server-side allocation split (async-profiler `alloc` / out-of-JVM client), and
  (b) the production edge read QPS (confirm "hot"). It is the best — likely only — Netty
  candidate, but is not "proven" until those land.
- **Fan-out (3):** the biggest per-op allocation in the system, on the hottest data path —
  unambiguously worth fixing. But the dominant term is **codec-internal churn**, removable by
  a single-pass into-buffer encoder/decoder (the `FrameCodec` into-variant pattern) **without
  Netty**. Netty's pooled `ByteBuf` would help the residual socket-write buffer, but does not
  address the ~71 KB of intermediate allocation — a codec rewrite does. ADR-0037 already
  argued the fan-out's *connection scale* doesn't need Netty; the *allocation* axis is
  codec-fixable. **Codec rewrite first; reassess Netty for the socket layer only after.**
- **Consensus (4):** the send-path allocation is real and on a high-frequency path (M3
  heartbeats currently cost **88 B/op** *each* — codec frame + sender-id wrap), but the codec's
  **own `encode(ByteBuffer)` variant already measures ~0 B/op** — the transport simply calls
  the allocating overload. Using that into-variant + a reused buffer in `TcpRaftTransport`
  (writing the sender id into the same buffer, and a decode-into for the receive side) drives
  this to ~0 with **no new dependency**. Netty is not the right tool. M3 timing must be
  re-verified after the change (heartbeat path touched).

## Recommended scope (evidence-based, supersedes "migrate everything")

1. **Netty — edge read HTTP serving only (surface 2).** First confirm the server-side
   allocation split + the production edge read QPS (the conviction's two open numbers), then
   build the hand-rolled zero-alloc Netty HTTP/1.1 read pipeline. A **new ADR supersedes
   ADR-0037 for this surface only**, citing the allocation axis.
2. **No-Netty codec win — fan-out NOTIFY (surface 3).** Rewrite `EdgeFrameCodec` to a
   single-pass into-buffer encoder/decoder; re-prove the golden-fixture wire bytes unchanged.
3. **No-Netty in-place win — consensus (surface 4).** Use the existing `encode(ByteBuffer)`
   into-variant + a reused buffer in `TcpRaftTransport`, writing the 4-byte sender id into the
   same buffer (so the `encodeWire` second array goes away too); add a decode-into; re-run M3
   no-spurious-election + S2–S4.
4. **Admin API (surface 1) — no change.** Documented acquittal.

Net: Netty earns its place on **one** surface; two surfaces get cheaper in-place allocation
wins; one is acquitted. This is more surgical, lower-risk, and higher-ROI than the
"pure-Netty-everywhere" framing — and it is what the measurement supports.
