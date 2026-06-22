# Netty-migration Phase R — transport/framework inventory (R2)

> **Status:** Phase R, gating deliverable. Authored before any migration (charter §3 hard
> rule 1: inventory before removing anything from a request path).
> **Decision in force:** *measure-first, evidence-gated* (operator steer, this session).
> Introduce Netty ONLY on a surface whose per-request/per-message shell allocation the
> `-prof gc` baseline convicts AND that matters at the real workload. A negligible-shell
> surface stays on the JDK stack (no migration for consistency). Any surface we migrate
> supersedes [ADR-0037](../decisions/adr-0037-edge-transport-jdk-stack.md) via a new ADR
> citing the allocation evidence — the axis ADR-0037 never weighed.

## 0. Headline: the charter's premise, corrected against reality

The charter frames this session as "migrate JDK/Spring transport → Netty," with the
dangerous step being "remove Spring from the request path without losing S7 security."
**Reality, established by `grep io.netty` / `grep org.springframework` across all poms and
sources → zero hits:**

- **There is no Spring.** [ADR-0010](../decisions/adr-0010-netty-grpc-transport.md) (the
  "Netty + gRPC + Spring Boot" design) is **Superseded** and was *documented fiction* since
  the Session-1 audit — it was never implemented. So this is a Netty **introduction**, not a
  Spring teardown.
- **There is no Netty.** Never has been a dependency.
- **There are no NIO selectors.** No `ServerSocketChannel` / `Selector` / `SocketChannel`
  anywhere in `src/main`.
- **Every server surface is JDK blocking-socket + virtual-thread-per-connection.**
- **The S7 security controls are already framework-decoupled plain-Java services** wired
  directly into the request handlers (see §2). There is no interceptor/filter framework to
  silently drop — the charter's worst-case risk is materially smaller than assumed.

A ratified, adversarially-reviewed decision — **ADR-0037** — already chose this JDK stack on
a *measured-workload* rationale (per-node connection count is tens-to-low-hundreds via tree
fan-out, so Netty's connection-scaling advantage "is not this workload"), and **prices**
Netty behind a `>1k-subscribers-per-node` precondition that is not currently met. The
charter's justification is a **different axis — allocation/GC** — that ADR-0037 never
weighed, and which is **unmeasured** today. This baseline supplies that missing axis.

## 1. The four network surfaces

| # | Surface | Module · entry class | Transport | Threading | Wire/codec |
|---|---------|----------------------|-----------|-----------|------------|
| 1 | Control/admin API | `configd-server` · `HttpApiServer` | `com.sun.net.httpserver.HttpServer`/`HttpsServer` (mTLS via `HttpsConfigurator`) | virtual-thread-per-request executor | HTTP/1.1 + JSON/octet-stream by hand |
| 2 | Edge read-serving API | `configd-edge-node` · `EdgeHttpServer` | `com.sun.net.httpserver.HttpServer` | virtual-thread-per-request | HTTP/1.1 + octet-stream |
| 3 | Edge fan-out streaming | `configd-server` · `fanout/FanOutServer` ↔ `configd-edge-node` · `EdgeStreamClient` | JDK `SSLServerSocket`/`SSLSocket` via `TlsManager` (TLSv1.3 mTLS) | virtual-thread-per-subscriber, bounded outbound queues | `EdgeFrameCodec` (`configd-distribution-service`): len-prefix + version + type + CRC32C, 2 MiB cap |
| 4 | Inter-node consensus | `configd-transport` · `TcpRaftTransport` | `SSLSocket`/`SSLServerSocket` via `TlsManager` (TLSv1.3 mTLS, `setNeedClientAuth(true)`) | virtual-thread inbound; per-connection `DataOutputStream` | `FrameCodec`: len + version + type + groupId + term + CRC32C, 16 MiB cap; coalesced heartbeats (M3) |

Non-surfaces (clients / test/sim, not server transport): `configd-linz/ConfigClient` (JDK
`HttpClient`), `configd-jcstress/PeerModel`, `configd-linz/FaultInjector`.

## 2. Framework responsibilities carried per surface (what a migration must preserve)

The charter's hard rule 2 — *re-prove every S7 control on the new pipeline* — applies only
where we migrate. The reassuring inventory finding: these are **not** framework magic; they
are explicit method calls in the handlers, so a Netty handler re-wires the *same objects*.

**Surface 1 — `HttpApiServer.ConfigHandler` (`configd-server`):**
- Routing — `server.createContext(path, handler)` + manual method/`switch` dispatch (`handle`).
- mTLS — `HttpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext))`.
- authn (401) — `AuthInterceptor.authenticate(bearer)` → `WWW-Authenticate: Bearer`.
- authz (403) — `AclService.isAllowed(principal, key, perm)`.
- audit — `AuditLog.record(actor, action, key, outcome)` (fail-loud; every mutating attempt).
- replay protection — `ReplayGuard.check(ts, nonce)` → 401/409.
- rate-limit / backpressure — `ConfigWriteService.WriteResult.Overloaded` → 429 + `Retry-After`.
- strong-read fail-closed — `StrongReadPolicy.isStrongReadKey` → 503 `X-Fail-Closed` (RR-020).
- 1 MB / frame ceiling — request-body read; content negotiation by hand.

**Surface 2 — `EdgeHttpServer` (`configd-edge-node`):** cursor/staleness read semantics
(`X-Configd-Cursor`/`-Stale`/`-Refused`), strong-read fail-close (`X-Fail-Closed`),
not-subscribed refusal, optional `/metrics` bearer gate (F-S7-TLS-2). No write path.

**Surface 3 — fan-out:** mTLS (`TlsManager`), `EdgeFrameCodec` frame discipline
(peek-length-before-allocate, CRC-before-interpret, 2 MiB cap, NOTIFY batch caps),
ADR-0038 verbatim-signed-chain delivery, slow-consumer governance / bounded queues.

**Surface 4 — consensus:** mTLS + `setNeedClientAuth(true)` (RR-002 hardened), `FrameCodec`
discipline (peek-length, CRC, 16 MiB cap, golden-fixture wire-compat gate ADR-0029),
bounded connect/handshake timeouts, coalesced heartbeats (M3 timing — must not regress).

## 3. Allocation reality (what is already known vs. the gap this baseline fills)

- **In-process read/store path is already near-zero-alloc and is NOT transport.**
  `LocalConfigStore`/HAMT: **32 B/op** for a hit (one `ReadResult`), **~0** for a miss,
  *constant at 100M keys* (S3 `ct34-jmh-gc-check.txt`, S7.5 `scale-read-gc.md`). Netty does
  not change this — it is the data-structure lookup the HTTP/socket shell wraps.
- **The transport shell allocation has NEVER been measured.** Both HTTP servers *document*
  that they allocate per request (`EdgeHttpServer` Javadoc "CT-34 hot-path honesty": "THIS
  HTTP shell allocates per request (exchange, headers, strings) and is honestly not the
  law's scope"). The codecs allocate per message — `TcpRaftTransport.send` calls the
  **allocating** `FrameCodec.encode` (the zero-alloc `encode(ByteBuffer)` variant exists but
  is **unused**); `FanOutServer`/`EdgeStreamClient` call the allocating `EdgeFrameCodec.encode`
  (the NOTIFY path allocates multiple intermediate `byte[]`/`List`/`ByteBuffer` per frame).
- **The charter's success metric requires this measurement.** "A migration that still
  allocates per request has failed … proven by `-prof gc`." We never had the baseline.

## 4. The baseline (Phase R measurement) — what is measured, how

Four JMH `-prof gc` benchmarks added under `configd-testkit/.../io/configd/bench/`
(`gc.alloc.rate.norm`, B/op; allocation/op is largely CPU-count-independent, so the 2-vCPU
box is fine — latency is explicitly *not* the point here):

| Surface | Benchmark | Legs | Captures |
|---|---|---|---|
| 4 consensus | `TransportFrameAllocBenchmark` | `encodeAllocating` (status quo) · `encodeInto` (achievable floor, unused variant) · `decode` · `roundTrip` | per-message wire alloc + how much is removable *without* Netty |
| 3 fan-out | `EdgeWireAllocBenchmark` | `encodeNotify` / `decodeNotify` (batch 1/16/64) · `encodeHeartbeat` | the heavy NOTIFY push path, the a-priori conviction candidate |
| 1 admin | `AdminHttpAllocBenchmark` | `configGet` (auth off/on) · `healthLive` (shell-floor control) | end-to-end JDK HTTP shell per-request garbage |
| 2 edge | `EdgeHttpAllocBenchmark` | `configGet` · `healthLive` (control) | end-to-end edge read shell per-request garbage |

**Method honesty.** HTTP legs are end-to-end loopback (JVM-wide `-prof gc` ⇒ client + server
+ handler), so each pairs the real endpoint with a trivial-`/health/live` **control**: the
delta isolates the read path's marginal cost, the control is the shell+client floor. Codec
legs isolate the app-controlled per-message allocation (the term pooled `ByteBuf` replaces);
`SSLSocket`/exchange I/O allocation is a separate component, measured end-to-end only for a
surface the baseline convicts. Raw captures: `docs/netty-migration/baseline/`.

## 5. Per-surface migration disposition (to be filled from the baseline)

| # | Surface | A-priori expectation | Verdict (post-baseline + audit) |
|---|---------|----------------------|---------------------------------|
| 1 | admin API | shell allocates per req; low QPS, high S7 re-prove cost → likely *leave on JDK* | **ACQUIT — stay on JDK** (large alloc but control-plane QPS makes it immaterial) |
| 2 | edge read | shell allocates per req; high QPS read path → candidate | **CONVICT for Netty (PROVISIONAL)** — the one genuine case; gated on server-side alloc split + production read QPS |
| 3 | fan-out | NOTIFY codec allocation-heavy; high volume → strong candidate | **CONVICT — codec rewrite first (no Netty)**; Netty only for the residual socket buffer |
| 4 | consensus | per-message `encode` byte[]; **may reach ~0 via the existing into-variant, no Netty** | **ACQUIT for Netty — cheap in-place fix** (existing into-variant measures ~0) |

See [baseline.md](baseline.md) for the measured B/op and the full reasoning; the conviction
verdicts survived an independent second-agent replay ([decision-log.md](decision-log.md) DR-5).
