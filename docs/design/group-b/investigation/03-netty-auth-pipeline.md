# §2.3 — The Netty Auth Pipeline (investigation)

**Status: investigation, read-only. 2026-07-06.** Grounds the Group B auth pipeline design in Netty's actual
threading/lifecycle model and in Configd's current pipelines at `file:line`. No production code was changed.
Netty citations are the Javadoc of Netty 4.2.15.Final (the version pinned in the root pom, `netty.version`
`4.2.15.Final`), read from the sources jar (`io.netty:netty-transport:4.2.15.Final:sources`,
`io.netty:netty-common:4.2.13.Final:sources`); the same Javadoc is published at `netty.io/4.2/api/`.

This section owns *where the authentication gate sits in the pipeline*, *the channel-attribute lifecycle of the
authenticated state*, *the non-blocking expiry tick*, *minimal-allocation-until-authenticated*, and *the shared
Authenticator SPI with its two transport adapters*. It composes with RFC §03 (`docs/rfc/driver-protocol/03-authentication.md`):
mTLS is the edge credential in v1 (AU3-2), bearer is the HTTP credential (AU3-1), authenticate-before-any-data is
normative (AU4-1), 401/503 fail-closed (AU5-2), one principal on both planes (AU6-1).

---

## 1. Executive summary and recommendations

The single most important grounding fact is that **Configd's two Netty servers already have exactly the right
shape for an auth gate, and one plane's auth is already correctly placed today.** The fan-out plane authenticates
by mTLS: the `SslHandler` is the first handler, the verified cert identity is read post-handshake, and the driver
refuses an unauthorized `SUBSCRIBE`/`WATCH_CREATE` with zero data frames. The remaining Group B work is (a) a
*token-bearing* AUTH path on the binary plane (RFC AU3-3 forward extension — the `AUTH`/`REFRESH_AUTH`/`CLOSE`
frames of Gate 3), (b) a unified per-connection **credential-expiry** lifecycle (Gate 5), and (c) hoisting the
per-request bearer check on the HTTP plane onto a shared `Authenticator` SPI. Every recommendation below is chosen
to reuse machinery that already exists in the tree.

**Headline recommendations (one call per fork):**

1. **Pipeline placement — fan-out (binary).** Keep `SslHandler` first (it must be, to have mTLS identity and to
   reject pre-TLS bytes). Insert a dedicated **`EdgeAuthGateHandler`** *after* `ByteToEdgeFrameDecoder` and
   *before* the business handler `FanOutConnection`. It sits after the decoder so it only ever sees a
   bounds-checked, fully-assembled frame (hostile oversize frames are already rejected pre-allocation by the
   decoder's `peekLength`); it sits before `FanOutConnection` so no session/driver/core state is built until the
   connection is authenticated. For **v1 mTLS-only** the gate is a thin pass-through that merely *records* the
   post-handshake principal into the channel attribute (below) — the handshake is the authentication, so no frame
   is gated; the gate becomes load-bearing when the token-`AUTH`-frame extension lands.

2. **Pipeline placement — HTTP (control).** *No new pipeline handler.* HTTP auth is request-shaped and
   stateless-per-request (RFC AU4-2); the right seam is the existing per-request `AdminApiHandler.checkAuth`
   (`AdminApiHandler.java:696`), generalized to call the shared `Authenticator` SPI. Keep `SslHandler` →
   `HttpServerCodec` → `HttpObjectAggregator` → `IdleStateHandler` → `AdminHandler` exactly as is.

3. **`AttributeKey<AuthState>` over a handler field.** Use a channel attribute, not a field on one handler,
   because the auth state is read by *four* distinct places on the channel — the decoder (pre-auth frame
   ceiling), the auth gate, the business handler, and the expiry-timer callback. There is direct in-repo
   precedent: `NettyRaftTransport.PEER_IDENTITY` is an `AttributeKey<NodeId>` set post-handshake and read on later
   frames (`NettyRaftTransport.java:101,451,483`). This is the identical pattern for peer identity on the other
   wire plane.

4. **Expiry tick — `ctx.executor().schedule(...)`, not `HashedWheelTimer`, not `IdleStateHandler`.** Schedule a
   one-shot reap task on the **channel's own event loop**, armed to `expiresAt`, cancelled and re-armed on
   `REFRESH_AUTH`. This is byte-for-byte the WH-11 first-frame-deadline discipline already in the tree
   (`NettyFanOutServer.java:452`) and the HTTP arrival-deadline discipline (`NettyHttpApiServer.java:267`). The
   callback fires on the event loop, so the terminal CLOSE is emitted on the correct thread with no cross-thread
   hop and the `AuthState` transition needs no lock. `HashedWheelTimer` is rejected because its `TimerTask` runs
   on the timer's *own* worker thread (a category mismatch that forces an `eventLoop().execute` hop and
   cross-thread state reads); `IdleStateHandler` is rejected because expiry is a wall-clock property of the
   *credential*, not an I/O-idleness property of the *connection* (edge subscribers are idle by design).

5. **Minimal-allocation-until-authenticated.** Pre-auth, a connection must hold essentially nothing: the
   per-channel decoder cumulator, the shared `UNAUTHENTICATED` sentinel in the attribute (zero per-connection
   allocation), a single `ScheduledFuture` auth deadline, and the admission-counter slot. **No
   `FanOutConnectionDriver`, no per-shard cores, no `WatchRegistry`, and no session virtual thread** until
   `AUTHENTICATED`. The pre-auth *frame-size ceiling* is enforced in the decoder's `peekLength` stage, gated on
   the auth attribute: cap the declared length at a small pre-auth bound (a few KB — an `AUTH` frame is tiny)
   versus the 2 MiB steady-state `MAX_EDGE_FRAME_SIZE` (`EdgeFrameCodec.java:111`). The per-connection auth
   deadline reuses the WH-11 `firstFrameDeadline` mechanism verbatim, re-labelled "authenticate within N ms".

6. **Shared SPI, two adapters.** One `Authenticator` SPI (`authenticate(Credential) → Authenticated(Principal) |
   Denied(reason) | Unavailable(retryable)`), one immutable `Principal` seam (id + roles + attributes +
   provenance; never carries the credential), and a sealed `Credential` (bearer token | client certificate). Two
   transport adapters differ *only* in lifecycle: the HTTP adapter re-presents per request (no channel state); the
   M2M adapter authenticates once, stores `Principal + expiresAt` in the channel attribute, honours
   `REFRESH_AUTH`, and closes on expiry. Authorization (`WatchAuthorizer`/`AclService`) is unchanged and consumes
   the `Principal` on both planes (RFC AU8-3). The existing `AuthInterceptor` (`AuthInterceptor.java`) is the
   bearer-only precursor the new SPI subsumes.

---

## 2. Netty model findings (with citations)

All citations are the class Javadoc of Netty 4.2.15.Final.

**M-1 — A channel is bound to exactly one event loop, and every callback for it runs serially on that one
thread.** `Channel.eventLoop()` returns "the `EventLoop` this `Channel` was registered to" (`io.netty.channel.Channel`).
`EventLoop`'s Javadoc: "Will handle all the I/O operations for a `Channel` once registered. One `EventLoop`
instance will usually handle more than one `Channel` …" — i.e. the mapping is many-channels-to-one-loop, never
one-channel-to-many-loops. `EventLoop extends OrderedEventExecutor`, whose Javadoc is: "Marker interface for
`EventExecutor`s that will process all submitted tasks in an ordered / serial fashion."
(`io.netty.util.concurrent.OrderedEventExecutor`). **Consequence:** all of a channel's inbound callbacks
(`channelActive`, `channelRead`, `userEventTriggered`, `channelInactive`) *and* any task submitted via
`ctx.executor().execute/schedule` run one-at-a-time on the same thread. State touched only from that thread needs
no synchronization. This is the foundation for a lock-free `AuthState`.

**M-2 — Pipeline event order.** From `io.netty.channel.ChannelPipeline`: "the handler evaluation order is 1, 2, 3,
4, 5 when an event goes inbound. When an event goes outbound, the order is 5, 4, 3, 2, 1." Inbound events flow
head→tail (first-added first); outbound flow tail→head. Netty "skips the evaluation of certain handlers" that do
not implement the relevant direction. The recommended "Building a pipeline" order is *Protocol Decoder → Protocol
Encoder → Business Logic Handler*. **Consequence:** an inbound auth gate added *after* the decoder and *before* the
business handler sees decoded frames first and can stop them reaching the business handler by simply not calling
`ctx.fireChannelRead(...)`.

**M-3 — The pipeline is mutable at runtime and thread-safe.** `ChannelPipeline` Javadoc, "Thread safety": "A
`ChannelHandler` can be added or removed at any time because a `ChannelPipeline` is thread safe. For example, you
can insert an encryption handler when sensitive information is about to be exchanged, and remove it after the
exchange." **Consequence:** an auth gate *may* remove itself from the pipeline once `AUTHENTICATED` to shed its
per-frame cost, though for Configd's low pre-auth frame count keeping it in place is simpler and adequate.

**M-4 — Channel attributes are the sanctioned per-channel state store.** `Channel extends AttributeMap`
(`io.netty.channel.Channel:77`); `ChannelHandlerContext extends AttributeMap` too. `AttributeMap.attr(key)` "will
never return null, but may return an `Attribute` which does not have a value set yet", and the interface contract
is "Implementations must be Thread-safe" (`io.netty.util.AttributeMap`). `AttributeKey.valueOf(name)` returns a
process-wide singleton constant keyed by name (`io.netty.util.AttributeKey`). `ChannelHandlerContext`'s Javadoc,
"Storing stateful information": "`attr(AttributeKey)` allow you to store and access stateful information that is
related with a `ChannelHandler`/`Channel` and its context." **Consequence:** `AttributeKey<AuthState>` is the
idiomatic cross-handler, channel-scoped slot; because a channel is single-threaded (M-1) the mutations are lock-
free, and the `AttributeMap` thread-safety contract additionally makes a rare cross-thread *read* (e.g. from the
session thread) safe.

**M-5 — `ChannelHandlerContext.executor()` is the channel's event loop, and a context may be held for later,
cross-thread use.** "Returns the `EventExecutor` which is used to execute an arbitrary task" — this is the
channel's `EventLoop`. The "Retrieving for later use" section notes a context can be kept "for later use, such as
triggering an event outside the handler methods, even from a different thread." **Consequence:**
`ctx.executor().schedule(reapTask, ttl, MILLIS)` runs the reap on the channel's loop; and code on another thread
(the session thread, or an external revocation signal) drives a transition safely via
`ctx.channel().eventLoop().execute(...)` — precisely the hop `NettyFanOutServer.teardown` already uses
(`NettyFanOutServer.java:556-560`).

**M-6 — `HashedWheelTimer` runs its tasks on its own thread, fires approximately, and must be a shared
singleton.** `io.netty.util.HashedWheelTimer` Javadoc: "A `Timer` optimized for approximated I/O timeout
scheduling"; "this timer does not execute the scheduled `TimerTask` on time. `HashedWheelTimer`, on every tick,
will check if there are any `TimerTask`s behind the schedule and execute them" (default tick 100 ms). "Do not
create many instances. `HashedWheelTimer` creates a new thread whenever it is instantiated and started. Therefore,
you should make sure to create only one instance and share it across your application. One of the common mistakes,
that makes your application unresponsive, is to create a new instance for every connection." **Consequence:** a
wheel timer would be one shared instance whose callbacks fire on a *non-event-loop* thread — every credential-
expiry close would then need a hop back onto the channel's loop, and the `AuthState` read in the callback would be
cross-thread. That is strictly more machinery and more failure surface than `eventLoop().schedule`, for no benefit
at Configd's connection scale.

**M-7 — mTLS identity is delivered as a user event, post-handshake.** (Grounded in Configd code, standard Netty
`SslHandler` behaviour.) The `SslHandler` emits an `SslHandshakeCompletionEvent` through
`userEventTriggered(...)`; the verified peer principal is read from `sslHandler.engine().getSession()
.getPeerPrincipal()`. Configd already does exactly this (`NettyFanOutServer.java:399-427`). **Consequence:** the
mTLS→`AUTHENTICATED` transition is driven from `userEventTriggered` on the event loop, before any application
frame is admitted.

---

## 3. Configd pipeline grounding (exact current order, both planes)

### 3.1 Fan-out (M2M binary / edge) — `NettyFanOutServer`

Pipeline built in `initChannel` (`configd-server/.../fanout/NettyFanOutServer.java:270-283`), in inbound order:

| # | Handler | Line | Role |
|---|---------|------|------|
| 1 | `SslHandler` (only if `tlsManager != null`) | `:278` (built `:296`) | mTLS, server mode, `setNeedClientAuth(true)` `:300`, TLSv1.3 protocols/ciphers `:302-308` |
| 2 | `ByteToEdgeFrameDecoder` | `:280` | length-prefixed decode; `peekLength` bounds the declared length *before* allocating the frame buffer (`ByteToEdgeFrameDecoder.java:53-60`); per-connection inbound wire-version pin |
| 3 | `EdgeFrameToByteEncoder` | `:281` | outbound, in-pipeline pooled encode (position among inbound handlers is irrelevant) |
| 4 | `FanOutConnection` (business: `SimpleChannelInboundHandler<EdgeFrame>` + `TransportSink`) | `:282` | admission, session lifecycle, per-frame routing into the driver |

Auth-relevant control flow in the business handler:
- **Admission before handshake:** `channelActive` bumps `liveConnections` and refuses over `maxSessions`
  *before* the TLS handshake, so a half-open slowloris cannot exhaust FDs/threads
  (`NettyFanOutServer.java:380-396`).
- **Identity resolution (the current auth point):** on `SslHandshakeCompletionEvent` success,
  `resolveCertIdentity` reads the cert Subject DN; a handshake that "succeeded" with no verifiable cert is
  rejected `AUTH_FAIL` (`:398-434`). Plaintext mode starts the session immediately with identity `"plaintext"`
  (`:392-394`).
- **Session build (what must move behind AUTH for the token path):** `startSession` (`:436-454`) builds the
  `FanOutConnectionDriver`, fires `onSubscriberConnected`, spawns the **virtual session thread**, and arms the
  **WH-11 first-frame deadline** via `ctx.executor().schedule(onFirstFrameDeadline, firstFrameDeadlineMs, MS)`
  (`:452`). The deadline is disarmed on the first routed frame in `channelRead0` (`:466-473`).
- **Authorization (zero-data-before-deny, in the driver, off the event loop):** `admitLegacySubscribe` binds the
  cert identity then calls `authorizeSubscribe` before any session command
  (`FanOutConnectionDriver.java:426-436, 853-862`); `handleWatchCreate` validates then `authorize`s the whole
  target once before any shard leg seeds (`:557-587, 833-842`). Both fail closed on `null`/plaintext/throwable.

The WH-11 first-frame deadline config is shared by both edge transports:
`configd.edge.firstFrameDeadlineMs`, default `10_000` (`FanOutServer.java:88-106`). The JDK twin
`FanOutServer` implements the same absolute-deadline discipline in blocking form (`FanOutServer.java:514-669`);
any pipeline change must keep the two transports contract-equivalent (they are re-proven by the shared
`FanOutServerContract` across JDK + Netty(auto) + Netty(forced-NIO), per the class Javadoc).

### 3.2 HTTP (control plane) — `NettyHttpApiServer`

Pipeline built in `initChannel` (`configd-server/.../NettyHttpApiServer.java:185-204`), inbound order:

| # | Handler | Line | Role |
|---|---------|------|------|
| 1 | `SslHandler` (only if `sslContext != null`) | `:193` | server-mode TLS; **no** client auth (`:191` — "Client identity is the Bearer token; mTLS is the fan-out/consensus surface") |
| 2 | `HttpServerCodec(8192,8192,8192)` | `:196` | bounded request line/headers → 400 on oversize |
| 3 | `HttpObjectAggregator(maxRequestBytes)` | `:198` | assembles the full request incl. PUT body; auto-413 on oversize (`maxRequestBytes` default `1<<20`, `:164`) |
| 4 | `IdleStateHandler(0,0,idleTimeoutMillis)` | `:200` | idle keep-alive reaping (default 60 s, `:163`) |
| 5 | `AdminHandler` (business: `SimpleChannelInboundHandler<FullHttpRequest>`) | `:203` | per-connection FIFO; hops the blocking decision to a virtual-thread executor; writes back on the loop |

Auth-relevant control flow:
- **Per-request auth is inside the handler, not the pipeline:** `AdminHandler.channelRead0` snapshots the request
  into a transport-free carrier and dispatches `handler.handle(...)` on a virtual thread (`:302-353`).
  `AdminApiHandler.handle` runs `checkAuth` first (`:696`), which extracts the bearer token
  (`bearerToken`, `:760-766`), calls `authInterceptor.authenticate(...)` (`:710`), and maps the outcome:
  `Denied → 401 + WWW-Authenticate: Bearer` (`:654-662`), authenticated-but-not-permitted `→ 403` (`:664`); a
  `null` interceptor is the loudly-warned auth-off mode with a reserved-prefix write still refused (`:699-707`).
- **Arrival deadline (the HTTP analogue of WH-11):** `AdminHandler` arms a self-rescheduling arrival-deadline
  watcher via `ctx.executor().schedule(...)` (`:262-293`) — the exact `eventLoop().schedule` idiom recommended for
  the auth-expiry tick.

### 3.3 The current auth/authz seams to build on

- `AuthInterceptor` (`configd-control-plane-api/.../api/AuthInterceptor.java`): bearer-only authN; a
  `TokenValidator` returns `Authenticated(principal, roles)` (roles defensively `Set.copyOf`) or `Denied(reason)`.
  This is the precursor to the general `Authenticator` SPI. Note it has **no** `Unavailable/503` variant yet —
  RFC AU5-2 (fail-closed, retryable-503) requires adding one.
- `WatchAuthorizer` (`configd-distribution-service/.../fanout/WatchAuthorizer.java`): the fail-closed authZ SPI
  the edge veneer already calls; the module seam is LOCKED (distribution-service is built before control-plane-api,
  so it sees only the SPI; the `AclServiceWatchAuthorizer` adapter lives in `configd-server`). The new
  `Authenticator` SPI must follow this same lower-module-SPI + server-module-adapter discipline.
- `ErrorCode` (`configd-distribution-service/.../wire/ErrorCode.java`): closed, golden-pinned 1..12.
  `AUTH_FAIL(4)` is the 401-class edge code; `NOT_AUTHORIZED(11)` is the 403-class watch reject;
  `PROTOCOL_VIOLATION(10)` is reused by WH-11. **There is no dedicated "credential expired" code.** Expiry can
  reuse `AUTH_FAIL(4)` (it is an authentication-state failure) to avoid a golden-fixture/wire-version bump — see
  §5 open question O-4.

---

## 4. Recommended design

### 4.1 Pipeline order (both planes)

**Fan-out (binary), with the token-`AUTH` extension:**
```
SslHandler?  →  ByteToEdgeFrameDecoder  →  EdgeFrameToByteEncoder  →  EdgeAuthGateHandler  →  FanOutConnection
```
- `EdgeAuthGateHandler` is a new inbound handler inserted at `NettyFanOutServer.java:281/282` (between encoder
  and business handler). It forwards frames to `FanOutConnection` **only** once the channel attribute is
  `AUTHENTICATED`; before that it admits **only** an `AUTH` frame (and, on a watch-capable connection,
  `REFRESH_AUTH`), driving the transition, and closes the connection on any other frame or on the auth deadline.
- For **v1 mTLS-only**, `EdgeAuthGateHandler` records the post-handshake `Principal` into the attribute
  (transition M-7) and then passes everything through — the handshake already guarantees authenticate-before-data,
  and the driver's existing `authorize`/`authorizeSubscribe` gates guarantee zero-data-before-deny. The gate
  earns its keep only when a token credential can arrive as a frame (AU3-3).
- **Why after the decoder, not before:** the decoder's `peekLength` already bounds allocation before the frame
  buffer is created; the gate wants *typed, assembled* frames to decide `AUTH` vs everything-else. Placing the
  gate before the decoder would force it to re-implement framing.
- **Why the pre-auth ceiling lives in the decoder:** to truly *not allocate* a large buffer pre-auth, the length
  cap must apply at `peekLength`, before allocation. Give `ByteToEdgeFrameDecoder` a per-channel pre-auth cap
  (read from the `AuthState` attribute or a boolean it already holds): while `UNAUTHENTICATED`, bound the declared
  length at a small `PRE_AUTH_MAX_FRAME` (a few KB) instead of `MAX_EDGE_FRAME_SIZE` (2 MiB). This is the concrete
  realization of minimal-allocation-until-authenticated.

**HTTP (control):** unchanged pipeline. Generalize `AdminApiHandler.checkAuth` to call the shared `Authenticator`
SPI. Per-request, no channel attribute needed (each request re-presents; AU4-2). An optional per-connection cache
keyed by a token hash could skip re-validating an unchanged OIDC JWT across pipelined requests — an optimization,
not required (open question O-3).

### 4.2 The `AuthState` machine

One `AttributeKey<AuthState>` — `AttributeKey.valueOf("configd.auth.state")` — set on every channel that carries a
principal (both planes for symmetry, even though HTTP re-derives per request). `AuthState` is an immutable value;
each transition sets a new instance. All transitions run on the channel's event loop (M-1).

```
                 channelActive
                      │  (attribute ← UNAUTHENTICATED sentinel; auth-deadline armed;
                      │   decoder pre-auth ceiling in force)
                      ▼
              ┌───────────────┐   mTLS handshake OK + cert verified (edge)      ┌──────────────────────────────┐
              │ UNAUTHENTICATED│──  OR valid AUTH frame (edge token)         ──▶ │ AUTHENTICATED(principal,      │
              │                │      OR (HTTP) valid bearer on the request      │              expiresAt)       │
              └───────┬────────┘                                                 └────────┬───────────────┬──────┘
                      │ auth deadline elapsed,                                            │ REFRESH_AUTH   │ expiry timer fires
                      │ or invalid credential                                            │ re-presents OK │ (now ≥ expiresAt),
                      │                                                                   │ (expiresAt' ↑, │ or revocation signal,
                      ▼                                                                   │ timer re-armed)│ or REFRESH_AUTH fails
              ┌───────────────┐                                                          └───────┬────────┘        │
              │  CLOSING       │◀──────────────────────────────────────────────────────────────┴─────────────────┘
              │ (terminal CLOSE with reason, then teardown; no further business frames)
              └───────────────┘
```

Transition drivers, by plane:
- **UNAUTHENTICATED:** set in `channelActive` (edge: `NettyFanOutServer.java:381`; HTTP: implicit, per-request).
  The `UNAUTHENTICATED` sentinel is a shared constant → zero per-connection allocation.
- **→ AUTHENTICATED (edge mTLS):** in `userEventTriggered` on handshake success (`NettyFanOutServer.java:399`),
  the gate calls `Authenticator.authenticate(ClientCertificate)` and stores `Principal + expiresAt`. `expiresAt`
  for a pure cert is the cert `notAfter` (already enforced by TLS; the connection-level timer is belt-and-braces).
- **→ AUTHENTICATED (edge token, AU3-3):** the `EdgeAuthGateHandler` receives an `AUTH` frame, calls
  `Authenticator.authenticate(BearerToken)`, and on success sets the attribute and arms the expiry timer to the
  token's `exp` (or a configured default TTL). *Only here* does it then allow `FanOutConnection` to build the
  driver + spawn the session thread.
- **REFRESH_AUTH:** a `REFRESH_AUTH` frame on an `AUTHENTICATED` M2M connection re-runs the `Authenticator`; on
  success it cancels and re-arms the expiry `ScheduledFuture` with the new `expiresAt`; on failure it drives
  `→ CLOSING`.
- **→ CLOSING:** driven by (a) the expiry-timer callback when `now ≥ expiresAt` and no refresh intervened, (b) a
  Gate-5 revocation signal delivered onto the loop via `eventLoop().execute`, or (c) an auth-deadline elapse while
  still `UNAUTHENTICATED`. It emits a terminal `ERROR_CLOSE`/`WatchCanceled` with an auth code (§5 O-4) and calls
  the existing idempotent `teardown` (`NettyFanOutServer.java:544`).

### 4.3 The non-blocking expiry tick

Reuse the WH-11 mechanism exactly. On `→ AUTHENTICATED`:
```java
this.expiryTask = ctx.executor().schedule(() -> onExpiry(ctx),
        Math.max(0, expiresAt - clock.currentTimeMillis()), TimeUnit.MILLISECONDS);
```
On `REFRESH_AUTH` success: `expiryTask.cancel(false); expiryTask = ctx.executor().schedule(...)`. On
`channelInactive`/teardown: `expiryTask.cancel(false)`. `onExpiry` runs on the event loop, re-checks `now ≥
expiresAt` (guards a refresh that landed between the fire and the callback — the same defensive re-check the HTTP
`checkDeadline` does at `NettyHttpApiServer.java:286`), and drives `→ CLOSING`.

- **Per-connection one-shot, not a shared periodic sweep.** One `ScheduledFuture` per authenticated connection is
  cheaper than a global periodic poll (no wakeups until the actual deadline) and fires on the right thread. At
  thousands of connections these are heap-timer entries on the event loops that already multiplex those channels —
  not threads.
- **Defaults (Gate-5 owns the numbers).** Mechanism: `expiresAt` derives from the credential where it carries one
  (JWT `exp`, cert `notAfter`); otherwise a configured default TTL via a `-D` property in the WH-11 style, e.g.
  `configd.edge.authTtlMs`. The auth *deadline* (authenticate-within) reuses `configd.edge.firstFrameDeadlineMs`
  semantics (default 10 s). Exact production TTL/skew values are an operator decision (O-1).

### 4.4 The smallest pre-auth state

A pre-auth connection holds only: the decoder's bounded cumulator; the `AuthState` attribute pointing at the
shared `UNAUTHENTICATED` sentinel; one `ScheduledFuture` auth deadline; and the `liveConnections` slot. It does
**not** hold a `FanOutConnectionDriver`, per-shard `FanOutSessionCore`s, a `WatchRegistry`, or a session virtual
thread — all of those are created at/after the `AUTHENTICATED` transition. The decoder caps the pre-auth declared
frame length at `PRE_AUTH_MAX_FRAME` (a few KB) so a hostile peer cannot induce even a mid-size allocation before
proving identity. (Today, v1 mTLS builds the driver + session thread right after the handshake, which is
acceptable because the handshake *is* authentication and the driver holds no keyspace state until `onSubscribe`;
the deferral becomes mandatory for the token-`AUTH`-frame path, where the first frame is untrusted.)

### 4.5 Shared `Authenticator` SPI + two adapters

**SPI (in a lower module both planes can see — mirror the `WatchAuthorizer` seam):**
```java
public interface Authenticator {
    AuthResult authenticate(Credential credential);   // fail-closed; never throws for "denied"
}
sealed interface Credential permits BearerToken, ClientCertificate { }
sealed interface AuthResult permits Authenticated, Denied, Unavailable { }
record Authenticated(Principal principal) implements AuthResult { }
record Denied(String reason) implements AuthResult { }          // → 401 / AUTH_FAIL
record Unavailable(String reason) implements AuthResult { }     // → 503 / retryable (RFC AU5-2)
```
`Principal` is the typed, immutable seam already sketched in the arc rules: id + Configd roles + attributes +
provenance; it **never** carries the credential. It is the object `WatchAuthorizer`/`AclService` already need
(they take a principal string + roles today; the `Principal` generalizes that).

**HTTP adapter (request-shaped).** In `AdminApiHandler.checkAuth`: extract the bearer (existing `bearerToken`),
build `BearerToken`, call `authenticate(...)`, map `Denied → 401 + WWW-Authenticate`, `Unavailable → 503`,
`Authenticated → Principal` handed to the existing ACL check. No channel attribute; each request re-presents. This
is the existing `AuthInterceptor` path with `Unavailable/503` added and `Principal` in place of the ad-hoc
`(principal, roles)` pair.

**M2M adapter (connection-shaped).** In `EdgeAuthGateHandler`: on the mTLS handshake (v1) or an `AUTH` frame
(AU3-3), build `ClientCertificate`/`BearerToken`, call the **same** `authenticate(...)`, store `Principal +
expiresAt` in the `AttributeKey<AuthState>`, arm the expiry timer, and only then let `FanOutConnection` build the
session. `REFRESH_AUTH` re-invokes the SPI and re-arms; expiry/`Denied`/`Unavailable` drive `→ CLOSING`. The
driver's `authorize`/`authorizeSubscribe` gates then consume the stored `Principal` (RFC AU8-3: the principal this
establishes is exactly the one §1 authorizes).

The split is: **one** `Authenticator` + `Principal` + `Credential`, **two** adapters that differ only in
lifecycle (stateless-per-request vs authenticate-once + refresh + expire). Same principal both planes (AU6-1);
authorization unchanged.

---

## 5. Open questions needing an operator decision

- **O-1 — Credential TTL and clock-skew policy on the M2M plane.** When a token carries no `exp` (or a cert-only
  connection), what default connection TTL applies, and what skew tolerance does the expiry timer allow? This is
  Gate-5's core number; the mechanism (§4.3) is skew-agnostic but the value is a deployment policy.
- **O-2 — v1 mTLS: gate on a frame or only record identity?** Recommendation is that the edge gate is a
  pass-through in v1 (handshake = auth) and becomes load-bearing only with the token-`AUTH` frame. Confirm we are
  *not* introducing a mandatory `AUTH` frame on the mTLS-only path in v1 (that would break existing drivers and
  the golden fixtures). RFC AU3-2/AU3-3 support "no" (mTLS-only in v1; token frame is a named forward extension).
- **O-3 — HTTP per-connection auth cache.** Do we validate the bearer on *every* request (simplest, matches AU4-2)
  or cache the `Authenticated` outcome per connection keyed by a token hash to avoid re-validating an unchanged
  OIDC JWT under pipelining? The cache is an optimization with a revocation-latency cost; default recommendation is
  no cache in v1.
- **O-4 — Credential-expired wire code.** The `ErrorCode` set is closed and golden-pinned (1..12). Recommendation
  is to reuse `AUTH_FAIL(4)` for expiry/refresh-failure closes (it *is* an authentication-state failure) to avoid
  a wire-version bump and a golden-fixture change. If the operator wants expiry distinguishable on the wire from a
  first-handshake failure, that is a new `ErrorCode` (e.g. `AUTH_EXPIRED(13)`) and a deliberate
  `EDGE_WIRE_VERSION` bump + golden regen — a real cost, so it needs an explicit call.
- **O-5 — `AuthInterceptor` migration.** The new `Authenticator` SPI subsumes `AuthInterceptor`
  (`AuthInterceptor.java`). Do we (a) replace `AuthInterceptor` outright, or (b) keep it as a thin bearer adapter
  over the new SPI? (b) is lower-risk for the byte-identity discipline the arc favours; confirm.
- **O-6 — Pre-auth frame ceiling value.** `PRE_AUTH_MAX_FRAME` (§4.1) — a few KB is ample for an `AUTH` frame, but
  the exact bound depends on the token size the largest supported IdP emits (OIDC JWTs can run several KB). Pick a
  value that fits the largest legitimate `AUTH` frame with margin, well under the 2 MiB steady-state cap.

---

## Appendix — file:line index

- Fan-out pipeline: `configd-server/src/main/java/io/configd/server/fanout/NettyFanOutServer.java:270-283`
  (init), `:296-311` (SslHandler), `:380-396` (admission), `:399-434` (handshake/identity), `:436-454`
  (startSession + WH-11 arm), `:464-494` (channelRead0 + disarm), `:544-578` (teardown).
- Fan-out decoder (pre-auth ceiling site): `.../fanout/ByteToEdgeFrameDecoder.java:48-70`.
- JDK edge twin (contract-equivalence + WH-11 blocking form): `.../fanout/FanOutServer.java:88-106, 349-402,
  514-669`.
- Driver authZ gates (zero-data-before-deny): `configd-distribution-service/.../fanout/FanOutConnectionDriver.java:426-469`
  (legacy SUBSCRIBE), `:557-660` (WATCH_CREATE), `:833-862` (authorize/authorizeSubscribe), `:524-532`
  (bindIdentity).
- HTTP pipeline: `configd-server/src/main/java/io/configd/server/NettyHttpApiServer.java:185-204` (init),
  `:262-293` (arrival deadline — the eventLoop().schedule idiom), `:302-353` (per-request dispatch).
- HTTP auth seam: `configd-server/src/main/java/io/configd/server/AdminApiHandler.java:696-744` (checkAuth),
  `:654-664` (401/403 mapping), `:760-766` (bearer extraction).
- Auth SPI precursors: `configd-control-plane-api/.../api/AuthInterceptor.java`;
  `configd-distribution-service/.../fanout/WatchAuthorizer.java`.
- Wire codes: `configd-distribution-service/.../wire/ErrorCode.java` (AUTH_FAIL 4, NOT_AUTHORIZED 11,
  PROTOCOL_VIOLATION 10); `.../wire/EdgeFrameCodec.java:72,85,99` (wire versions), `:111` (MAX_EDGE_FRAME_SIZE = 2
  MiB); `.../wire/FrameType.java` (0x01..0x12, ERROR_CLOSE 0x09).
- AttributeKey precedent: `configd-netty/src/main/java/io/configd/netty/NettyRaftTransport.java:101,451,483`.
- Transport tier: `configd-netty/src/main/java/io/configd/netty/NettyTransport.java` (Epoll→NIO auto, io_uring
  opt-in).
