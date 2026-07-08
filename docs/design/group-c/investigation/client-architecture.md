# Group C — Reference Client / SDK: Module & Class Architecture

**Status: DESIGN / INVESTIGATION (2026-07-08). No product code changed.** This document designs the module
layout, transport choices, package/class decomposition, hostile-server hardening, and test/conformance model
for a **conforming Java reference client / SDK** for the Configd driver protocol. The build happens in later
gates against this design.

**Thesis (the arc).** Prove the protocol is *real* by shipping a working conforming client for **both planes**,
built to the RFC, **hardened against a hostile/buggy server** — the exact mirror of the server's hostile-client
hardening. Standing rule: **NEVER DEFER** — the design covers *all* protocol paths (§01–§07), no seams-for-later.

**Sources of truth.** RFC `docs/rfc/driver-protocol/00..07`; the frozen edge codec
`configd-distribution-service/.../wire/{EdgeFrame,EdgeFrameCodec,FrameType,ErrorCode,WatchCursor,FrameSink,HeapFrameSink,EdgeSnapshotCodec}.java`;
the server handlers a client mirrors (`configd-server/.../fanout/{ByteToEdgeFrameDecoder,EdgeAuthGateHandler}.java`,
`configd-server/.../AdminApiHandler.java`); the raw-socket seed
`configd-server/src/test/.../fanout/EdgeProtocolClient.java`; the HTTP seed
`configd-linz/.../client/ConfigClient.java`; auth/TLS in `configd-common/.../auth/` and `configd-transport`.

---

## 1. Module decision (and the codec-extraction verdict)

### 1.1 The dependency reality (measured, not assumed)

The frozen edge codec is the crux. Its transitive dependency footprint, read from imports:

- **`io.configd.distribution.wire.*`** (the 8 wire classes) imports: `io.configd.common.auth.Credential`
  (configd-common, **agrona-only**), `io.configd.distribution.CommitNotification` (lives *in* distribution-service),
  and `io.configd.store.{CommandCodec,ConfigDelta,ConfigMutation,ConfigSnapshot,VersionedValue}` (config-store).
  It imports **no** `java.net` / socket / TLS / Netty type (verified — the codec is transport-free by construction).
- **`CommitNotification`** imports only `io.configd.store.ConfigDelta`.
- **`EdgeSnapshotCodec`** imports `io.configd.store.{ConfigSnapshot,VersionedValue}`.
- The client must **verify** the signed delta chain (OV7 trust model, F6-3): that is `io.configd.store.ConfigSigner`
  (Ed25519, JDK-only) plus `ConfigDelta`/`CommandCodec`.

Two module facts make the naive "just depend on it" answer wrong:

1. **`configd-distribution-service` is the whole server data plane.** Beyond the wire package it contains
   `FanOutSessionCore`, `HyParViewOverlay`, `PlumtreeNode`, `WatchService`, `SubscriptionManager`, `ReplaySource`,
   `RolloutController`, the fan-out server core — server machinery a reference client must **not** ship (libpq /
   etcd-clientv3 do not ship the server). It also drags `configd-config-store` + `configd-transport` + jctools.
2. **`configd-config-store` is not thin either.** It depends on **`configd-consensus-core`** (→ `configd-transport`)
   because it also houses `ConfigStateMachine` and the Raft-facing storage. So even "extract just the wire package"
   would drag consensus-core in *through config-store*, via the `io.configd.store.*` value types the codec needs.

**But the value types themselves are clean.** Verified: `ConfigDelta`, `ConfigMutation`, `CommandCodec`,
`ConfigSnapshot`, `VersionedValue`, `ReadResult`, `ConfigSigner`, and `ConfigScope` import **only JDK + each other**
(no consensus/transport/distribution import). The consensus-core coupling in config-store is created by *other*
classes in that module, not by these value types. And `consensus-core` does **not** import `io.configd.store.*`
— so relocating the value types below config-store creates **no cycle**. `ConfigScope` already lives in
`configd-common` (agrona-only).

### 1.2 Verdict: EXTRACT a thin shared wire module; do NOT depend on distribution-service

**Recommendation: create a new low-level module `configd-wire` (`io.configd:configd-wire`), depending only on
`configd-common`, and move into it the frozen edge codec + its pure value/serialization types.** Both the server
(`distribution-service`, `config-store`) and the new thin `configd-client` depend on `configd-wire`; neither drags
the other. This is the **etcd-clientv3 shape**: the shared *wire contract* (etcd's `etcdserverpb` protobufs) is a
thin artifact both sides import; the *client logic* is independent. It is **not** the libpq shape (libpq
re-implements the FE/BE protocol and shares nothing) — see the alternative in §1.4.

Contents to relocate into `configd-wire` (bytes unchanged — a class move is not a wire change):

| From | Classes | Notes |
|---|---|---|
| `configd-distribution-service` `io.configd.distribution.wire` | `EdgeFrame`, `EdgeFrameCodec`, `FrameType`, `ErrorCode`, `WatchCursor`, `FrameSink`, `HeapFrameSink`, `EdgeSnapshotCodec` | the frozen codec + its golden-fixture tests move with it and **re-prove byte-identity** in the new module |
| `configd-distribution-service` `io.configd.distribution` | `CommitNotification` | the `NOTIFY` payload unit |
| `configd-config-store` `io.configd.store` | `ConfigDelta`, `ConfigMutation`, `CommandCodec`, `ConfigSnapshot`, `VersionedValue`, `ReadResult`, `ConfigSigner`, `MalformedCommandException` | consensus-clean value/serialization/verify types |

Resulting DAG (all above `configd-common`): `configd-common → configd-wire`; `configd-wire` sits beside
`configd-consensus-core`; `config-store → {consensus-core, configd-wire}`; `distribution-service → configd-wire`
(+ its existing deps); **`configd-client → {configd-wire, configd-common, configd-transport}` + JDK only.** The
client drags **no** distribution-service, **no** consensus-core, **no** server, **no** Netty, **no** jctools.

**Why extract-and-share beats re-implement (the DE call).** The codec is a *value-type / serialization contract*,
not application logic. It is already the frozen, adversarially-fuzzed, golden-pinned **single source of wire
truth**. Re-implementing 65 KB of security-critical length/CRC/bounds parsing would create a **second parser to
fuzz and harden** for zero product benefit, and — decisively for this arc — the client's hostile-server hardening
gets the **same bounds the server enforces on hostile clients for free** (symmetric by construction). Fork a codec
and that symmetry must be re-derived and kept in sync forever. "Built purely from the RFC" is satisfied where
conformance bugs actually live: the client's **session state machine, auth lifecycle, signed-chain verification,
cursor/ordering, catch-up ladder, and leader-following** are all independent and RFC-derived. Nobody's conformance
bug is "my CRC-32C table is wrong."

### 1.3 Package strategy for the move (a fork for the lead)

The class *bytes* don't change; only the owning module does. Two ways to place packages:

- **Fork A (recommended — low churn): keep the existing package names.** Classes keep
  `io.configd.distribution.wire.*`, `io.configd.distribution.CommitNotification`, `io.configd.store.*`; only the
  **POMs** move (config-store & distribution-service add a `configd-wire` dependency; the ~15 downstream modules'
  imports are unchanged because they don't care which JAR provides the class). Cost: **split packages**
  (`io.configd.store` and `io.configd.distribution` each now span two JARs). This repo has **no JPMS**
  (`module-info.java` absent everywhere — verified), so on the classpath a split package compiles and runs fine;
  it is only illegal under JPMS. This is the pragmatic, minimal-risk move.
- **Fork B (clean, higher churn): rename the moved packages** to `io.configd.wire.*`. No split packages,
  JPMS-ready. Cost: a repo-wide **mechanical import rewrite** across ~15 modules + updating the RFC's source-path
  citations (§06/§07 cite `configd-distribution-service/.../wire/...`). Reserve for if/when the repo modularizes.

**Recommend Fork A now**, Fork B as a named follow-up. Under either, the golden-fixture tests are the safety net:
they assert bytes, not class location, so they stay green and *prove* the move changed nothing observable.

### 1.4 The alternative I considered and rejected for the product (libpq / independent codec)

An independent re-implementation of the codec + value types (share nothing) better serves the literal "from the doc
alone" thesis and touches no frozen module — but it duplicates a security-critical parser, invites silent
client↔server drift, and (in-repo, same JDK, same `CRC32C`) proves little about *cross-language* implementability.
**Adopt a scoped version of it instead:** the **conformance suite** (§5) carries a *tiny independent decoder* that
re-derives a handful of frame layouts straight from RFC §06 prose and asserts byte-equality against
`EdgeFrameGoldenBytes`. That closes the "RFC-prose vs reference-code drift" gap cheaply, without a second
production parser. (This is the one place the client legitimately re-implements the wire.)

### 1.5 One `configd-client` module, two independent facades

The Minimal-CRUD (HTTP) and Watch (edge) profiles are separable (OV5-1/OV5-2). **Recommend one module
`configd-client`** with two independent public facades (`ConfigdHttpClient`, `ConfigdEdgeClient`) so a CRUD-only
user pulls only the HTTP classes (JDK `HttpClient`, no streaming code path exercised) and a watch-only user pulls
only the edge classes. If the lead wants a Minimal-CRUD *artifact* that does not even ship the edge codec, split
into `configd-client-http` + `configd-client-edge` over a shared `configd-client-core` — a minor packaging fork,
noted, not recommended for v1 (one artifact is simpler and both profiles share `configd-wire` + TLS anyway).

---

## 2. Transport choice per plane

A reference client optimizes for **correctness, clarity, testability, and hostile-server safety** — not throughput.

### 2.1 Edge plane (binary streaming) — raw JDK blocking `SSLSocket` + one reader thread per connection

**Not Netty, not NIO selectors.** Rationale:

- The edge client holds **one connection per independently-resumed watch/subscription** (F10-1b) and consumes a
  server-*push* stream, writing only tiny `CURSOR_ACK` / `WATCH_*` / `AUTH` / `REFRESH_AUTH` frames. A blocking
  read-loop on a dedicated owner thread is the simplest *correct* model. It mirrors the `EdgeProtocolClient` seed
  and the `TcpRaftTransport.createClientSocket` recipe already in the tree.
- Netty would impose a heavy dependency on the thin client and event-loop complexity for zero reference-client
  benefit. NIO selectors add complexity without the throughput justification a reference client needs.
- It is trivially testable against a **real loopback socket** driven by a hostile mock server (§5) — no
  `EmbeddedChannel` (that is a Netty seam; the client is not Netty).

**Concurrency model.** One **owner thread per `EdgeConnection`** runs: `read → peekLength → decode → verify →
apply-to-local-view → emit CURSOR_ACK`. App-thread operations (`WATCH_CREATE`/`WATCH_CANCEL`, `REFRESH_AUTH`,
`close`) are serialized onto the owner via a single-writer command queue (or a single connection lock), so the
socket has exactly one writer and one reader and no cross-thread frame interleaving. A small shared
`ScheduledExecutorService` drives the token-refresh lead-time timer and the HEARTBEAT-silence read-idle deadline
(or these are computed inline on each HEARTBEAT/timer tick — the client is single-threaded per connection, so
inline is viable and simplest). This is the "one owner thread per node" real-wire model used elsewhere in the repo.

**Bounded I/O.** `connect(addr, connectTimeoutMs)`; `setSoTimeout(handshakeTimeoutMs)` around `startHandshake()`
then reset; steady-state `setSoTimeout(readIdleDeadlineMs)` so a stalled server surfaces as a
`SocketTimeoutException` → reconnect (the HEARTBEAT-silence liveness control, F6-8/F10-3).

### 2.2 HTTP control plane (unary get/put/delete + admin) — JDK `java.net.http.HttpClient` (HTTP/1.1)

**Recommend JDK `HttpClient`, synchronous, HTTP/1.1.** Rationale:

- It is the established client transport across the repo (`ConfigClient`, the bench write/read drivers) — zero
  extra dependency, keep-alive/pooling handled, TLS via an `SSLContext` from `TlsManager`.
- The server serves HTTP/1.1 (`AdminApiHandler` via `HttpApiServer`/`NettyHttpApiServer`; `ConfigClient` pins
  `HTTP_1_1`). HTTP/2 is unnecessary and would be a forward extension a driver must fail closed on.
- The client wraps `HttpClient` with the value the seed lacks: **leader-following** (503 + `X-Leader-Hint`
  follow-once + bounded backoff), the **`NodeId → address` map**, **strong-read** header interpretation
  (`X-Fail-Closed`), the **vector cursor** (per-shard, even at N=1), credentials (Bearer/Basic over TLS), and the
  optional replay-guard headers.

**Blocking-first.** The reference API is blocking (`send`) for clarity; an async veneer (`sendAsync` →
`CompletableFuture`) is an additive later gate — the API surface is shaped to allow it without a redesign.

### 2.3 Client-side TLS (both planes) — one place, the `TcpRaftTransport.createClientSocket` recipe

`TlsManager`/`TlsConfig` (in `configd-transport`, **pure JDK, no Netty** — verified) build an `SSLContext` but
expose **no client-side socket helper**. The client owns a single `io.configd.client.tls.ClientTls` that builds
the `SSLContext` (PKCS12 keystore/truststore, TLSv1.3, the two AEAD suites) and the client `SSLSocket` per F9:
unconnected `SSLSocket` → bounded `connect(host, timeout)` **by hostname** (SNI) → set protocols/ciphers →
**`setEndpointIdentificationAlgorithm("HTTPS")`** (F9-4, mandatory) → bounded `startHandshake()`. The HTTP plane
feeds the same `SSLContext` into `HttpClient.Builder.sslContext(...)`; `HttpClient` does HTTPS SAN verification by
default. **No plaintext downgrade in production** (F9-1) — plaintext is a test-only opt-in.

---

## 3. Package & class design

Module `configd-client`, root package `io.configd.client`. Sub-packages: `.http`, `.edge`, `.edge.session`
(internal state machine), `.tls`. The **public API surface** (what an SDK user sees) is marked **[public]**;
internals are **[internal]**.

### 3.1 Shared entry point & config — `io.configd.client`

- **`Configd` [public]** — the builder/facade (etcd `Client` shape). `Configd.builder()…build()` holds shared
  config and yields plane clients: `http()` → `ConfigdHttpClient`, `edge()` → `ConfigdEdgeClient`. `AutoCloseable`
  (closes the HTTP client, all edge connections, the scheduler). A Full driver uses both and **shares one
  cursor-vector type** across planes (OV5-3 / A9-1).
- **`ConfigdConfig` [public]** — immutable config: TLS material (`ClientTls` / keystore paths), a `Supplier<Credential>`
  (so tokens can be re-minted for refresh), the `NodeId → URI` map (R3, operator-provided — there is no wire
  discovery), timeouts, `RetryPolicy`, `HostileServerLimits`, `CursorStore`, optional `ReplayGuardSigner`.
- **`Credential`** — reused from `configd-common` (`BearerToken` / `BasicCredential`; `ClientCertificate` is a
  handshake artifact, never framed). The SDK user constructs these directly; `wipeSecret()` is honored after send.
- **`ConfigScope`** — reused from `configd-common`.
- **Exception hierarchy [public]** — each type *is* the §07 reaction, so the user branches on **type**, never on
  a parsed message (E6):
  - `ConfigdException` (root, unchecked)
  - `ConfigdProtocolException` — `BAD_WIRE_VERSION`/`FRAME_TOO_LARGE`/`FRAME_CORRUPT`/`PROTOCOL_VIOLATION`,
    HTTP 400/405 → a bug; do not retry unchanged.
  - `ConfigdAuthException` — HTTP 401 / `AUTH_FAIL`(4) / `CREDENTIAL_EXPIRED`(13) → (re)authenticate; carries a
    `boolean sessionExpired` to distinguish "aged out" from "never valid".
  - `ConfigdForbiddenException` — HTTP 403 / `NOT_AUTHORIZED`(11) → terminal for this principal/target.
  - `ConfigdUnavailableException` — HTTP 503 / pre-handshake refusal / transport drop on a *read* → retryable
    (carries the `X-Leader-Hint` NodeId and `X-Fail-Closed` flag when present).
  - `ConfigdIndeterminateException` — HTTP 504 / transport timeout on a **mutation** → outcome unknown;
    idempotent-LWW retry-to-definite; **no read-modify-write across it** (D4-8 surfaced as a type).
  - `ConfigdVerificationException` — signature/chain-verify failure → **security, fail-closed** (never silently
    dropped).
  - `ConfigdQuarantinedException` — `QUARANTINED`(8) → honor the identity cooldown before reconnect.
- **`RetryPolicy` [public]** — bounded exponential backoff + jitter with an attempt/deadline budget (R6-3);
  classifies every outcome per E7 (retry / indeterminate / terminal / re-auth / watch-action). Default provided.
- **`HostileServerLimits` [public]** — the inbound caps the client enforces on server frames (see §4); all
  overridable, all fail-closed. Defaults mirror the server's constants.
- **`CursorStore` [public SPI]** — persistence for the resume cursor (F10-1a: a driver **MUST** persist its
  cursor and re-CREATE with fresh ids after reconnect). Default in-memory; users supply a durable impl. The cursor
  is the shared per-shard `WatchCursor` vector (vector-native even at N=1).

### 3.2 HTTP control plane — `io.configd.client.http`

- **`ConfigdHttpClient` [public]** — the Minimal-CRUD driver:
  - `GetResult get(ConfigScope, String key)` (stale) · `getLinearizable(...)` (`?consistency=linearizable`) —
    `getStrong` is just a linearizable read that also surfaces the `X-Strong-Read`/`X-Fail-Closed` observation.
  - `long put(ConfigScope, String key, byte[] value)` → committed `seq` parsed from the **body** (D4-2); raw
    `application/octet-stream` body, no JSON.
  - `boolean delete(ConfigScope, String key)`.
  - admin: `transferLeadership(int groupId, NodeId target)`, `Health health()`, `String metrics()` (raw
    exposition; bearer-gated).
  - Every method drives credential attachment + leader-following + retry internally.
- **`GetResult` [public]** — `record(byte[] value, long version, boolean found, Consistency observed, boolean strongRead)`.
- **`WriteOutcome` [public]** — `Committed(long seq)` | `Indeterminate` (504/timeout — the D4-8 warning is a
  first-class outcome, not a silent success). `put`/`delete` return `Committed.seq` or throw
  `ConfigdIndeterminateException`.
- **`LeaderRouter` [internal]** — holds the `NodeId → URI` map; tracks the suspected leader per `(scope)`; on
  `503` reads `X-Leader-Hint` (bare decimal `NodeId`, **not** an address — resolved only through the map:
  anti-SSRF, R2-2/R3-1), follows **once** (`hop < 2`, R4-3) with backoff between hops, degrades to hintless
  rotation on an unresolvable hint (R3-3), handles the hintless-election `503` at N=1 (R4-2).
- **`ReplayGuardSigner` [public, optional]** — attaches `X-Configd-Timestamp` + nonce when the deployment's
  passive replay guard is on; handles `409` (fresh nonce) and `401`-stale-timestamp (fresh timestamp, not re-auth).

### 3.3 Edge plane (streaming/watch) — `io.configd.client.edge`

- **`ConfigdEdgeClient` [public]** — the Watch/fan-out driver entry point. Owns credentials/TLS/endpoints and
  opens connections:
  - `Subscription subscribeFullStore(SubscribeOptions)` / `subscribePrefixes(List<String>, SubscribeOptions)` —
    the legacy `0x01`/`0x03` fan-out surface. **Doc warning surfaced in the API**: this streams the whole change
    stream and is **not** per-key authorized (OV6-1) — the SDK marks it as requiring a segregated deployment.
  - `Watch watch(WatchTarget, WatchOptions, WatchListener)` — the `0x02` per-key watch surface. The **single
    shared drain** caveat (F10-1b) is enforced in the API: creating N independently-resumed watches on one
    connection resumes only #1, so the client **routes each independently-resumed watch to its own
    `EdgeConnection`** by default (a `sharedConnection` opt-in lets a caller co-locate watches that share one
    live tail).
- **`Subscription` [public handle]** — one legacy fan-out subscription (one connection). Exposes the verified,
  monotonic-read-guarded `LocalConfigView`, the staleness `State`, the current cursor, a `SubscriptionListener`,
  and `close()`.
- **`Watch` [public handle]** — one `watch_id` (or a per-shard-fan-in group). `cancel()`, cursor, per-shard state.
- **`WatchTarget` [public]** — `(ConfigScope scope, Kind {KEY,PREFIX,FULL}, byte[] path, EnumSet<Flag>)` where
  `Flag ∈ {FULL_CHAIN_VERIFY, PREV_VALUE, WITH_INITIAL_SNAPSHOT}` (the F/§02 flag bits). Validates FULL ⇒ empty
  path (mirrors the record invariant).
- **`WatchListener` / `SubscriptionListener` [public]** — the SDK user's hook: `onEvent(changes, gid, s, commitTs)`,
  `onSnapshot(...)`, `onProgress(cursor, serverNow)`, `onStale(State)`, `onError(ErrorCode code, Carrier carrier,
  String sanitizedMessage)`, `onReconnect(...)`. Delivered from the owner thread (or a bounded dispatch queue).
- **`LocalConfigView` [public read model]** — the client's **own** verified, monotonic-read-guarded local store
  (built independently, RFC-derived, **not** reusing edge-cache's `EdgeConfigClient`/`LocalConfigStore`, which
  would drag `distribution-service`). Backed by a concurrent `(scope,key) → VersionedValue` map; `get(key)` and
  `get(key, cursor)` enforce monotonic reads (a read behind the cursor is refused, mirroring the edge server's
  `cursor-behind` 404). Reuses only the pure value types (`VersionedValue`, `ConfigDelta`, `ConfigSnapshot`) from
  `configd-wire`.
- **`EdgeConnection` [internal, `.edge.session`]** — owns one `SSLSocket` + reader thread + the connection state
  machine: connect+handshake (F9) → auth (`AuthLifecycle`) → **first business frame stamps + pins the client's
  chosen business version** (F4-2; the client is the *pinner*) → operate → teardown. Responsibilities: send/recv
  (via the shared `EdgeFrameCodec` + `HeapFrameSink`), read-idle deadline, `CURSOR_ACK` flow-control cadence, the
  catch-up ladder (`DEMOTED_TO_CATCHUP` → keep streaming + ack/drain; `QUARANTINED` → cooldown + reconnect),
  reconnect-with-cursor (keep cursor; drop watch_ids/budget/multiplex/unacked — F10-1a), and the (code, carrier)
  → reaction mapping (E3-3).
- **`AuthLifecycle` [internal]** — the token/cert lifecycle manager:
  - **Token/basic edge**: sends the **single** pre-auth `AUTH` frame as the first routed frame (F10-1e / AU4-4),
    pipelines the first business frame right behind it (no AUTH-OK frame — the server buffers it, EdgeAuthGate
    behavior); schedules a **proactive `REFRESH_AUTH`** in the lead-time window `W` before `exp`
    (`W = clamp(0.20·lifetime, 30 s, 5 m)`, AU5-6) carrying a freshly-minted token from the `Supplier<Credential>`;
    on `CREDENTIAL_EXPIRED`(13) reconnects with a fresh credential; a refresh keeps the **same** identity (AU4-6).
  - **mTLS edge**: authenticates at the handshake, sends **no** `0x04` frame (byte-identical to a pre-auth-arc
    client); arms a cert lead-time reconnect (`clamp(0.10·lifetime, 5 m, 1 h)`, AU5-6) since a cert cannot refresh
    in-band.
- **`SignedChainVerifier` [internal]** — wraps `ConfigSigner` (verify-only, Ed25519, from `configd-wire`) to
  verify **every** `NOTIFY` delta: signature over the F6-3 signing payload
  (`encodeBatch(mutations) || BE(from,to,epoch) || nonce`), `fromVersion → toVersion` chain continuity, reject a
  signature carried on an `epoch == 0` delta (F6-3), and **fail-closed-when-verifier-configured-but-delta-unsigned**
  (the edge `DeltaApplier` F-0052 semantics). The CRC is integrity, **not** authenticity (F2-4) — verification is
  a distinct, mandatory security layer (OV7). A failure raises `ConfigdVerificationException` and tears the
  connection down.
- **`SnapshotReassembler` [internal]** — `SNAPSHOT_BEGIN → SNAPSHOT_CHUNK* → SNAPSHOT_END` with the F6-6
  discipline: buffer until END, verify **exactly `chunkCount`** chunks and reassembled length **== `totalBytes`**,
  order by `index` defensively, **discard + re-subscribe** on any short/truncated/count-mismatch (never apply a
  partial snapshot as complete). Enforces the cross-frame **accumulation caps** (WH-13/15:
  `maxSnapshotTotalBytes=512 MiB`, `maxSnapshotChunks=65536`) — the mirror of `EdgeClientCore.onSnapshotBegin`.
  Decodes the ADR-0028 body via `EdgeSnapshotCodec` and applies the three-form **skip-unknown-TLV** trailer
  (F7-2/F11-2 — the *only* skip-unknown region; the frame itself is reject-unknown).

### 3.4 The shared cursor-vector type

Both planes use the **one** `WatchCursor` (from `configd-wire`) — `topologyEpoch:u64` + strictly-ascending
unsigned-`gid` `(gid,S)` vector, **vector-native even at N=1** (`of(0,S)`), `fromNow()` = empty = "from now per
shard" (OV5-4 / A9-1 / W1-1). The HTTP per-key `X-Config-Version` is treated as per-shard, never a global scalar
comparable across shards (D6-3). The `CursorStore` persists this exact type.

---

## 4. Hostile-server hardening (the exact bounds the client enforces on inbound server frames)

The client is the **mirror image** of the server's `ByteToEdgeFrameDecoder` + `EdgeClientCore` hardening. Because
it **shares `EdgeFrameCodec`**, bounds 1–6 below are the *same code path* the server enforces on hostile clients —
symmetric by construction. Bounds 7–14 are client-side policy the state machine adds.

1. **Bounds before allocation (`peekLength`).** Read the 4-byte length, bounds-check to
   `[10, MAX_EDGE_FRAME_SIZE = 2 MiB]` **before** allocating the frame buffer (F2-3/F3-2). A lying prefix cannot
   induce a giant allocation. *(Same `EdgeFrameCodec.peekLength` the server calls; the seed already does this.)*
2. **CRC-before-interpret.** `decode()` verifies CRC-32C over `[0, L-4)` **before** reading version/type; a
   flipped byte is `FRAME_CORRUPT`, never a misleading `BAD_WIRE_VERSION` (F2-4/F3 step 4).
3. **Client-side version pin.** The client pins its chosen business version and decodes every server→client
   business frame under that pin (`decode(bytes, pinnedVersion)`) — a server frame stamped with a *different*
   business version is `BAD_WIRE_VERSION`; `0x04` is pin-exempt (F4).
4. **Type↔version legality.** Watch types only under `0x02`, auth types only under `0x04`, no business/watch under
   `0x04` — every violation `FRAME_CORRUPT` (F6A-3/F3 step 6).
5. **Strict-end.** Any trailing byte after a known payload ⇒ `FRAME_CORRUPT` (F3 step 7 / F11). No silent
   forward-compat at the frame; **reject-unknown** version/type/trailing-byte (OV7-3).
6. **Every inner length/count bounded before allocation.** `NOTIFY count ≤ 64` and payload `≤ 256 KiB`
   (F6-3/WH-14); cursor `count·12 ≤ remaining` (overflow-safe); `SNAPSHOT_BEGIN.chunkCount`/`totalBytes` bounded
   (F6-4); nested `CommandCodec` `count ≤ 10000`, `valueLen ≤ 1 MiB`, `keyLen` u16 (F7-1); snapshot body
   `entryCount`/`valueLen`/`payloadLen` bounded (F7-2). *(All in the shared codec.)*
7. **Cross-frame snapshot accumulation caps** (added by `SnapshotReassembler`, not per-frame): reject the
   `(chunkCount+1)`-th chunk, accumulated bytes `> totalBytes`, or the hard ceilings
   `maxSnapshotTotalBytes = 512 MiB` / `maxSnapshotChunks = 65536` (WH-13/15) — the mirror of the server-facing
   client's caps.
8. **Read / handshake / idle timeouts.** TCP `connectTimeoutMs` (~2 s); TLS handshake timeout via `setSoTimeout`
   around `startHandshake()` (~2 s); steady-state **HEARTBEAT-silence read-idle deadline** — reconnect if no
   `HEARTBEAT` within `silenceFactor × heartbeatMs` (default 8×250 ms). Not a per-byte idle timeout (the stream is
   idle-by-design; liveness is the HEARTBEAT, F6-8/F10-3).
9. **No unbounded buffering.** Exactly one frame in flight (length-gated); snapshot reassembly capped (7);
   `NOTIFY` batch capped (6); the listener-dispatch queue (if async) bounded with backpressure. The client applies
   synchronously on the owner thread and **drains its socket + acks (`CURSOR_ACK`) promptly** (F10-3) so it is
   never the slow consumer.
10. **Fail-clean, no hot-loop.** Any decode failure → clean connection close + a mapped exception; a one-shot
    reconnect on `FRAME_CORRUPT`(3); never a crash, hang, or `OutOfMemoryError`.
11. **Untrusted message text.** Never machine-parse the `ERROR_CLOSE` / `WATCH_CANCELED` `message`; **sanitize/escape
    before logging** (control chars / ANSI / log-forging); branch on **(numeric code, carrier frame)** only
    (F6-9/E6). Exceptions carry the sanitized string + the numeric code + carrier.
12. **Signature verification is a security control** (not the CRC). Verify every delta's Ed25519 signature + chain
    continuity + reject signed-on-`epoch==0`; fail-closed if a verifier is configured but a delta is unsigned
    (§4 / OV7). A CRC pass is **never** treated as authenticity.
13. **TLS.** `setEndpointIdentificationAlgorithm("HTTPS")` + supply the host so SAN matching is meaningful
    (F9-4); TLSv1.3-only + the two AEAD suites (F9-2); **no plaintext downgrade in production** (F9-1). A trusted
    CA alone is insufficient — endpoint identification is mandatory.
14. **Capacity vs protocol distinction.** A pre-handshake TCP close/reset/EOF (the silent session-cap refusal,
    F10-2) is a **capacity** condition → retry with backoff, **not** a protocol error. `QUARANTINED`(8) → honor
    the identity cooldown (own bounded backoff; the cooldown is in the message only — don't machine-parse it,
    F10-4).

HTTP-plane hardening mirrors §07: branch on **status + headers** never the body (bodies are plaintext under a
misleading `application/json`, may echo input, unescaped — never `JSON.parse`, E6); follow `X-Leader-Hint`
(opaque `NodeId`, resolved only through the operator map — anti-SSRF, R2-2); `504`/mutation-timeout is
indeterminate with **no RMW** across it (D4-8).

---

## 5. Testability & conformance

### 5.1 Unit — hostile mock servers on loopback (symmetric to the server's hostile-client probes)

- **Edge: `MockEdgeServer` [test]** — a raw-byte server on a loopback socket (plaintext for speed; a TLS variant
  for the F9 tests) that a test scripts to emit hostile sequences, each asserting the client fails **clean** (right
  exception type, connection closed, no crash/hang/OOM, no unbounded alloc): truncated frame; oversize length
  prefix; bad CRC; wrong/unknown version; trailing bytes; unknown type; watch-type-off-`0x02`; over-cap `NOTIFY`;
  snapshot short of `totalBytes` / over `chunkCount` / over the accumulation caps; missing HEARTBEAT (idle
  deadline); each `ErrorCode` on each carrier (`ERROR_CLOSE` vs `WATCH_CANCELED`); the `DEMOTED_TO_CATCHUP →
  QUARANTINED` ladder; a hostile `message` with control chars; a delta with a **bad signature** / signed-on-`epoch
  0` / unsigned-under-verifier; an epoch-regressed cursor (`STALE_TOPOLOGY`). This is the exact counterpart of the
  live raw-socket probes used against the server's Netty edge.
- **HTTP: `MockControlPlaneServer` [test]** — `com.sun.net.httpserver` on loopback (the server's own JDK adapter
  shape) returning each status+header combo (200/400/401/403/404/405/409/429/`503`+hint/`503`+`X-Fail-Closed`/504)
  and asserting the reaction: follow-hint-once + backoff, hintless-election backoff, re-auth, indeterminate-no-RMW,
  fresh-nonce, `Retry-After` honored.
- **Property/fuzz** — a jqwik property drives random byte streams into the client decoder and asserts it **only
  ever** throws a mapped `CodecException` → clean close, never hangs/OOMs/crashes (the mirror of the server's
  wire-security fuzz).

### 5.2 E2E — live `ConfigdServer` cluster (both planes, both auth modes)

Against a real 3-node cluster (the C6 compose-E2E pattern: empty-password PKCS12 repack, shared signing key,
HTTPS API): `put` via HTTP with a real **leader-follow** across a triggered failover; `subscribe → hydrate →
verify` the signed chain via edge; `watch` a key end-to-end; **refresh a token** before expiry and observe no
`CREDENTIAL_EXPIRED`; ingest a **snapshot re-bootstrap** on a stale resume; survive a **QUARANTINE** cooldown.
Exercised under **both** mTLS and token-auth on both planes.

### 5.3 Conformance suite — `configd-conformance` module, two halves, wired into CI

1. **client-conforms.** (a) The client's encode/decode is asserted **byte-for-byte** against `EdgeFrameGoldenBytes`
   (the RFC §06 cross-language vectors) — plus the **tiny independent decoder** of §1.4 re-derives a few layouts
   from RFC prose and asserts byte-equality against the same goldens (closing the RFC-vs-reference-code drift gap).
   (b) The **behavioral §01–§07 driver checklists** are each a named test driven against the mock + live server:
   vector-native at N=1; leader-following at N=1; mandatory `CURSOR_ACK`; the catch-up ladder; fail-closed-on-unknown;
   cursor persistence + fresh-id re-CREATE; one-connection-per-independent-resume; the (code, carrier) reaction map.
2. **server-obeys-RFC.** The **same** suite pointed at a live `ConfigdServer`, with the client as the **oracle**:
   it asserts the server emits exactly what the RFC says (the reverse direction — the hardened client that assumes
   a hostile server *also* documents-and-checks the honest server's conformance, catching server drift from the
   RFC). This is the symmetric proof the arc wants.

CI: the mock-server half runs always; the live-server half runs in the E2E job; the golden-byte gate is the
wire-compat tripwire. Every RFC gap the client exposes feeds the review-loop gate (task #8).

---

## 6. Forks for the lead to decide before the build

1. **Codec strategy (the big one).** Recommend **EXTRACT to `configd-wire` + share** (§1.2), *not* depend on
   `distribution-service` (drags the server) and *not* fully re-implement (duplicate parser + drift). Sub-fork:
   **package strategy** — **Fork A keep-packages** (low churn, split-package smell, no JPMS here so it's fine —
   *recommended now*) vs **Fork B rename to `io.configd.wire.*`** (clean/JPMS-ready, repo-wide mechanical import
   rewrite + RFC path-citation updates). The scoped independent decoder lives only in the conformance suite (§1.4).
2. **config-store value-type relocation.** The move touches a foundational module (`ConfigDelta`/`CommandCodec`/
   `ConfigSigner`/etc.) that ~everything depends on. It is byte-safe (golden fixtures + full-reactor CI prove no
   behavior change) and cycle-free (consensus-core does not import store types — verified). Confirm the lead
   accepts the relocation vs. the lighter-but-not-thin alternative (client depends on `config-store` directly and
   swallows the `consensus-core` drag — rejected here as it defeats the thin-client thesis).
3. **One artifact vs two (§1.5).** Recommend one `configd-client` with two independent facades; split into
   `configd-client-http` + `configd-client-edge` only if a Minimal-CRUD artifact must not ship the edge codec.
4. **Blocking-first HTTP** (recommended) with async as an additive later gate — confirm.
5. **Watch-fan-in default (F10-1b).** Recommend routing each independently-resumed watch to its **own**
   `EdgeConnection` by default (correctness over connection economy), with a `sharedConnection` opt-in for watches
   that share one live tail. Confirm this default.
