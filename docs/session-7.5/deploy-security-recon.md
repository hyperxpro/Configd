# Session 7.5 — Deploy-Security Recon (READ-ONLY)

> Authorized hardening campaign. This document is **recon only** — no source/test/gate/config was
> modified. Every mechanism is cited `file:line`. Each item gives MECHANISM (today) → CURRENT GAP →
> FIX LOCATION + APPROACH → NEGATIVE TEST → RR-002 / THREADING RISK. The closing **THREADING MAP**
> places the fixes off the consensus/request critical paths.

Repo root: `/mnt/nvme/Configd`. Java 25 Maven. Findings drive real S7.5/S8 fixes; this is the survey.

---

## THREADING MAP (read this first — every fix below references it)

Four thread domains carry traffic. The slowloris fix MUST NOT land on (a) or on the HTTP request
handler in a way that blocks; it belongs on the per-connection reader threads, which are already
isolated.

| # | Domain | Executor / thread | Created at | Notes |
|---|--------|-------------------|-----------|-------|
| **a** | **Raft tick / consensus** | `tickExecutor` — single thread `configd-tick` | `ConfigdServer.java:350-354` | Owns ALL `RaftNode` state (R-01): `driver.tick()` + inbound `routeMessage` + `propose` + `whenCommitOutcome` + ReadIndex, all marshalled here (`ConfigdServer.java:725-766`, `:1007`, `:1102`). **Nothing may block this thread** (RR-002 invariant). |
| **b** | **Raft transport accept + read + write** | `TcpRaftTransport.executor` = `Executors.newVirtualThreadPerTaskExecutor()` (accept loop + 1 reader vthread per inbound socket + 1 writer vthread per peer); plus `connectExecutor` = single `configd-transport-connector` thread for connect/handshake only | `TcpRaftTransport.java:184` (executor), `:185-189` (connector), accept loop submitted `:210`, reader submitted `:321`, writer submitted `:663` | Inbound reader = `handleInboundConnection` (`:337`). These vthreads are **off the tick thread** — RR-002 deliberately put establishment on the connector and reads on vthreads precisely so a stalled peer cannot park tick. |
| **c** | **HTTP API request handling** | `HttpApiServer.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor())` | `HttpApiServer.java:123` | JDK `HttpServer`/`HttpsServer`; one vthread per request. The write path then **marshals** the proposal to the tick thread and blocks the *request vthread* (not tick) on a `CompletableFuture` with a 5 s `WRITE_COMMIT_TIMEOUT_MS` deadline (`ConfigdServer.java:1095-1139`). |
| **d** | **Edge fan-out (server side)** | `FanOutServer.executor` = `Executors.newVirtualThreadPerTaskExecutor()`; per connection 3 vthreads (reader/writer/session) | `FanOutServer.java:109`, accept `:191`, per-conn threads `:392-397` | Session work pulls via `readSince`/replay only — never the apply path. Edge **read** HTTP surface is a separate plaintext `HttpServer` (`EdgeHttpServer.java:100,105`), one vthread per request. Edge process has its OWN tick-free thread set; no consensus here. |

**Key consequence for the slowloris fix:** the inbound *read* loops live on domain (b) vthreads
(Raft) and domain (d) vthreads (edge fan-out + edge HTTP). Adding a `setSoTimeout` / idle-read
deadline / connection cap on those paths touches **only** virtual threads and transport-internal
state — it never reaches `configd-tick`. **Verdict: the slowloris fix is RR-002-safe.** (Detail in
Item 1.)

---

## Item 1 — Slowloris / FD-exhaustion (F-S7-FUZZ-1, HIGH)

### MECHANISM (today)
The finding is pinned by **`InboundReadDeadlineFuzzTest`**
(`configd-transport/src/test/java/io/configd/transport/InboundReadDeadlineFuzzTest.java:53`). It
deterministically proves (no timing race): a freshly accepted socket has
`getSoTimeout()==0` (`:76`), a `read()` on a stalled peer blocks indefinitely (`:94-100`), and a
small `setSoTimeout(300)` is the available, standard mitigation that makes the same read fail fast
with `SocketTimeoutException` (`:128-131`). The class Javadoc (`:24-36`) names the exact defect.

Inbound accept/read paths, both planes:

- **Raft TCP transport (control plane):**
  `TcpRaftTransport.acceptLoop` (`TcpRaftTransport.java:311-335`) → `executor.submit` per socket
  (`:321`) → `handleInboundConnection` (`:337-416`). The reader uses
  `DataInputStream.readInt()`/`readFully()` (`:342`, `:346`, `:360`) with **no `setSoTimeout`** on
  the accepted socket. Accepted sockets are tracked in an **unbounded** `acceptedSockets` keyset
  (`:128-129`, add at `:320`). The accept loop also has **no admission cap**.
  (Confirmed: `grep setSoTimeout` over `configd-transport/src/main` matches only the *outbound*
  client handshake at `:477`/`:479` — the inbound server path sets none.)
- **HTTP API server (control plane):**
  `HttpApiServer` ctor binds the JDK `HttpServer`/`HttpsServer` (`HttpApiServer.java:102`, `:106`)
  and sets `Executors.newVirtualThreadPerTaskExecutor()` (`:123`). The JDK `HttpServer` has no
  configured request/idle read timeout, and the default backlog (`0` at `:102`/`:106`) does not cap
  *established* connections.
- **Edge HTTP server (edge plane):**
  `EdgeHttpServer` ctor (`EdgeHttpServer.java:100`, `:105`) — same JDK `HttpServer`, same
  unbounded-vthread executor, no read timeout.
- **Edge fan-out (data plane) — already bounded, for contrast:**
  `FanOutServer.acceptLoop` applies a `maxSessions` admission cap (default 1024) **before** the
  handshake (`FanOutServer.java:228-232`, `:87`) and bounds the handshake with `setSoTimeout`
  (`:255-257`). But its per-frame `readerLoop` (`:408-466`, `readFrame` `:488-507`) has **no
  inter-frame idle deadline** — the 1024 cap bounds the blast radius, the read deadline is still
  absent. So even the "good" plane is only half-covered.

### CURRENT GAP
On the Raft inbound path there is **neither a read/idle deadline NOR a connection cap**. A peer that
completes (or stalls mid-) handshake and then drips bytes — or sends the 4-byte sender id then stalls
before the length prefix — parks a reader virtual thread and **holds a socket FD forever**. Thousands
of such half-open connections exhaust file descriptors; `serverSocket.accept()` then fails
(`:328-332` logs and continues, but cannot admit legitimate peers). The bytes are well-formed-but-slow,
so the FrameCodec malformed-frame rejection (`:347-349`) never triggers. The HTTP API and edge HTTP
servers share the same "no read timeout + unbounded vthreads" shape.

### FIX LOCATION + APPROACH (minimal blast radius)
Three sub-fixes, all on domains (b)/(c)/(d), none on (a):

1. **Bounded idle/slow-read timeout on accepted Raft sockets.** In
   `TcpRaftTransport.acceptLoop` (`:314`), immediately after `serverSocket.accept()` returns,
   call `clientSocket.setSoTimeout(INBOUND_READ_TIMEOUT_MS)` (new constant, ~10–30 s, sibling to
   `HANDSHAKE_TIMEOUT_MS` `:110`). A `SocketTimeoutException` then surfaces inside
   `handleInboundConnection`'s `readInt`/`readFully`; catch it alongside the existing `SocketException`
   handler (`:407-410`) and drop the connection (close in the existing `try (socket)` `:338`). Because
   steady-state Raft heartbeats arrive every ≤50 ms (`ConfigdServer.java:266` budget), a 10–30 s idle
   deadline never trips a healthy peer. **Note:** this is a *read-idle* timeout (resets per read),
   not a total-connection timeout — long-lived healthy connections are unaffected, matching the
   RR-002 decision to clear the outbound handshake timeout to 0 for steady state (`:479`).
2. **Per-connection-progress / inbound connection cap.** Mirror `FanOutServer` exactly: add a
   `maxInboundConnections` bound checked in `acceptLoop` **before** `executor.submit` (model:
   `FanOutServer.java:228-232`), closing the socket + incrementing a counter when
   `acceptedSockets.size() >= cap`. `acceptedSockets` (`:128`) is already the live-set to size.
3. **HTTP read timeout / cap (control + edge).** The JDK `com.sun.net.httpserver` exposes no direct
   socket read-timeout knob, so the minimal-blast-radius approach is a **front filter**: wrap each
   context handler in an `HttpServer` `Filter` that enforces a max time to read the request
   body/headers (the body is read at `HttpApiServer.java:397` / `EdgeHttpServer` reads via the
   handler), plus an admission `Semaphore` sized to a max-concurrent-request cap so unbounded vthread
   creation is bounded. (Alternatively, document that HTTP slowloris is mitigated at the
   ingress/L7 LB and scope the in-code fix to the Raft plane — a lead call. The Raft plane is the
   HIGH-severity one because it has no LB in front of it.)

Constants belong next to the existing transport bounds (`TcpRaftTransport.java:96-118`) so they are
named configs per the charter's "every threshold is a named config" rule.

### NEGATIVE TEST (attack → refused)
- **Existing fixture to extend:** `InboundReadDeadlineFuzzTest`
  (`InboundReadDeadlineFuzzTest.java:53`) already proves the mechanism on a bare socket. After the
  fix, add an end-to-end case: start a real `TcpRaftTransport` (pattern:
  `TcpRaftTransportBlackholeTest.java:80` `callingThreadReleasedWhenPeerBlackholed`), connect an
  attacker socket, send the 4-byte sender id then stall, and assert the server **closes the
  connection within `INBOUND_READ_TIMEOUT_MS`** (reader vthread released, socket removed from
  `acceptedSockets`) — the slow-drip is refused, not parked forever.
- **Connection-cap test — existing model to copy:**
  `FanOutServerAdmissionBoundTest`
  (`configd-server/src/test/java/io/configd/server/fanout/FanOutServerAdmissionBoundTest.java`)
  already proves "open `cap+1` connections → the extra is refused before handshake." Clone its shape
  for the Raft inbound cap: open `maxInboundConnections+1` stalled sockets, assert the last is closed
  immediately and a refusal counter increments.

### RR-002 / THREADING RISK
**No risk to consensus — CONFIRMED.** The accept loop and `handleInboundConnection` run on
`TcpRaftTransport.executor` virtual threads (`:184`, submitted `:210`/`:321`), **never on
`configd-tick`** (domain a). `setSoTimeout` on an accepted socket and an `acceptedSockets`-size check
touch only transport-internal, thread-safe state — they cannot delay a tick, heartbeat, election, or
read. The RR-002 resolution (`docs/readiness-register.md:36`) hinges on connect/handshake being off
the tick thread; this fix adds nothing to the tick path. **Re-run the static guard
`NoBlockingConnectOnConsensusPathTest`
(`configd-transport/src/test/java/io/configd/transport/NoBlockingConnectOnConsensusPathTest.java:88`)
after the fix** — it scans transport+consensus-core+server for timeout-less `new Socket`/
`createSocket(host,port)`/unbounded `startHandshake`. The fix uses neither `new Socket(...)` nor a new
`startHandshake`, so the guard stays green; the bounded-handshake look-back (`:78-86`) is untouched.
Also re-run the live drill `gates/rr-002-blackhole-drill.sh` (it probes PUT/GET/health under a SYN
black-hole) to confirm the added inbound timeout did not perturb the commit path.

---

## Item 2 — Leaf-anchor cert-expiry (F-S7-TLS-1, Med)

### MECHANISM (today)
Trust is established in **`TlsManager.createSslContext()`**
(`configd-transport/src/main/java/io/configd/transport/TlsManager.java:51-75`). It loads the trust
store PKCS12 (`:63-66`) and initializes the **JDK default** `TrustManagerFactory`
(`:68-71`, `TrustManagerFactory.getDefaultAlgorithm()`), then builds the `SSLContext`
(`:72-73`). The same `TlsManager` is shared by the Raft transport
(`TcpRaftTransport.createServerSocket` `:504-525` server side, `createClientSocket` `:447-502` client
side, `setNeedClientAuth(true)` `:512`) and the edge fan-out (`FanOutServer.createServerSocket`
`:309-329`, `setNeedClientAuth(true)` `:316`). The production trust model
(`deploy/compose/setup-secrets.sh`) imports each peer's **self-signed leaf directly as a trust
anchor** — there is no CA hierarchy.

### CURRENT GAP
Under **RFC 5280 §6.1**, certificate path validation treats a trust anchor as an *input* and does
**not** evaluate the anchor's own validity period. When a peer presents a certificate that *is* the
trust anchor (exact self-signed leaf match), JSSE's `PKIXValidator` never checks its `notAfter` —
**an expired self-signed leaf is accepted.** This is empirically confirmed, not theoretical:
`docs/session-7/transport-security.md:§2` records that the first draft of the expired-cert test used
the leaf-as-anchor model and **failed** (server accepted the expired client, `inboundCount==1`); the
shipped test had to switch to a CA-signed end-entity to make JSSE enforce `notAfter`. The discipline
is documented in `RaftTransportMtlsAttackTest.java:58-67` (the "why the expired-cert case needs a CA"
Javadoc) and the CA fixture builder `genCaSignedExpiredEndEntity` (`:385-407`).

### FIX LOCATION + APPROACH (minimal blast radius)
Two options (both noted in `transport-security.md:§2` recommendation):

- **Preferred (topology):** move to a real (even single-level) CA so leaves are validated as
  end-entities and `notAfter` is enforced natively. This changes `deploy/compose/setup-secrets.sh` +
  fixtures, not Java code — out of scope for a code-only seam but the durable fix.
- **Code seam if the self-signed model is retained:** wrap the default trust managers in
  `TlsManager.createSslContext()` (`:68-73`). After `tmf.init(trustStore)`, take
  `tmf.getTrustManagers()`, find the `X509TrustManager`, and wrap it in a **custom
  `X509TrustManager`** that, in `checkClientTrusted`/`checkServerTrusted`, first delegates to the
  default, then **additionally** calls `cert.checkValidity()` on the presented leaf (the
  `chain[0]`) — turning an expired anchor-leaf into a `CertificateExpiredException`. Pass the wrapped
  array to `ctx.init(...)` at `:73`. This is a single-method, single-file change; mTLS posture
  (`setNeedClientAuth(true)`, TLSv1.3, HTTPS endpoint-id) is untouched because it lives in the socket
  setup, not the trust manager.

### NEGATIVE TEST (attack → refused)
- **Existing fixture proving the inverse (CA path enforces expiry):**
  `RaftTransportMtlsAttackTest.expiredClientCertificateIsRejected`
  (`RaftTransportMtlsAttackTest.java:192-205`) — already asserts a CA-signed expired end-entity is
  rejected (`inboundCount==0`).
- **New fixture the fix must make pass:** a **leaf-as-anchor expired** variant — generate a
  self-signed leaf with `-startdate -2d -validity 1` (reuse `genKeyPair`
  `RaftTransportMtlsAttackTest.java:351`, import the expired leaf directly into the server trust store
  via `importCert` `:409`), connect, and assert `inboundCount==0` (handshake refused). Pre-fix this
  test is RED (the leaf-anchor blind spot admits it, per `:62-66`); post-fix the custom
  `X509TrustManager` makes it GREEN. This is the discriminating attack-proves-rejection test the
  charter requires.

### RR-002 / THREADING RISK
**None.** `createSslContext()` runs at construction (`TlsManager` ctor `:35-38`) and on the
`tlsReloadExecutor` (`configd-tls-reload`, `ConfigdServer.java:360-364`, `:774-781`) — a dedicated
slow-I/O thread deliberately isolated so cert work never delays tick or reads
(`ConfigdServer.java:107-114`). The added `checkValidity()` runs inside the TLS handshake on the
**connector / accept vthreads**, never on `configd-tick`. No consensus risk.

---

## Item 3 — Edge `/metrics` exposure (F-S7-TLS-2, Low)

### MECHANISM (today)
- **Edge:** `EdgeHttpServer` registers `/metrics` at `EdgeHttpServer.java:104` →
  `handleMetrics` (`:281-294`), which returns the full Prometheus exposition with **no auth, no
  TLS**. The server is a **plaintext** JDK `HttpServer` bound to the **wildcard** address:
  `HttpServer.create(new InetSocketAddress(port), 0)` (`:100`) — `InetSocketAddress(int)` binds all
  interfaces (`0.0.0.0`), not loopback. `EdgeNodeConfig` has **no bind-address field** (the record
  `EdgeNodeConfig.java:58-71` carries `apiPort` but no interface), and the edge HTTP server is never
  given an `SSLContext` (only the fan-out *client* uses TLS — `EdgeNodeMain.java:82-92`,
  `:160-163`).
- **Control plane (contrast — already hardened, F-0055):** `HttpApiServer` registers `/metrics` at
  `HttpApiServer.java:116` → `MetricsHandler` (`:194-229`), which **requires a bearer token** when an
  `AuthInterceptor` is configured (`:212-224`, 401 + `WWW-Authenticate: Bearer` otherwise). It also
  uses `HttpsServer` when an `SSLContext` is supplied (`:101-104`). So the two planes diverge: control
  `/metrics` is auth-gated, edge `/metrics` is open.

### CURRENT GAP
The edge Prometheus surface leaks operational reconnaissance (staleness state, reconnect counts,
subscribed-prefix activity, read/refusal rates, JVM/version labels) to anyone who can reach the
wildcard-bound port — maps to threat-model **AS-5** (telemetry → reconnaissance). Confirmed by reading
the code in `docs/session-7/transport-security.md:§3`.

### FIX LOCATION + APPROACH (minimal blast radius)
Priority order (from `transport-security.md:§3`):

1. **Infra segmentation (preferred, no code):** firewall / K8s `NetworkPolicy` so only the Prometheus
   scraper + edge loopback reach the edge API port. S7.5 infra manifest, zero code risk.
2. **Bearer-token scrape auth on edge `/metrics`** mirroring F-0055: give `EdgeHttpServer` an
   optional `AuthInterceptor` (new `EdgeNodeConfig` token field + thread it through
   `EdgeNodeMain.start` `:160-163`) and gate `handleMetrics` (`EdgeHttpServer.java:281`) exactly like
   `HttpApiServer.MetricsHandler` (`:212-224`). Adds edge config + token distribution — deliberate
   S7.5/S8 item, not a slip-in.
3. **Cheapest in-code guard — bind address:** add a configurable bind interface to `EdgeNodeConfig`
   (default loopback) and change the bind at `EdgeHttpServer.java:100` from `new
   InetSocketAddress(port)` to `new InetSocketAddress(bindAddr, port)`. One field + one line. Still a
   behavior change (operators relying on wildcard must set the new address), so lead-approved.

### NEGATIVE TEST (attack → refused)
No existing edge `/metrics` auth test (none exists today — confirmed). After option 2: a
`ConfigHandlerAuthTest`-style fixture (model:
`configd-server/src/test/java/io/configd/server/ConfigHandlerAuthTest.java`) asserting `GET /metrics`
without a token → **401**, with the valid token → 200. After option 3: a test asserting a connection
to the edge API port from a non-loopback local address is refused while loopback succeeds.

### RR-002 / THREADING RISK
**None.** Edge `/metrics` is served on the edge process's HTTP request vthreads
(`EdgeHttpServer.java:105`). The edge process runs no Raft consensus at all (it is a read cache —
`EdgeNodeMain` wires `EdgeClientCore` + `EdgeStreamClient`, no `RaftNode`). Zero interaction with any
`configd-tick`.

---

## Item 4 — Passive-only replay (Med)

### MECHANISM (today)
`ReplayGuard` (`configd-control-plane-api/src/main/java/io/configd/api/ReplayGuard.java:46`) checks a
client-stamped `X-Configd-Timestamp` + `X-Configd-Nonce`: rejects timestamps outside a ±window
(`:133-135`, default ±5 min `:49`) and rejects an already-seen nonce inside the window
(`:139-141`), returning `409` on a verbatim replay. The nonce store is bounded by TTL eviction
(`:159-161`) + an LRU hard cap (`:102-107`, default 1M `:52`). It is wired into the write path in
`HttpApiServer.ConfigHandler.replayRejected` (`HttpApiServer.java:608-633`), called from `handlePut`
(`:393`) and `handleDelete` (`:419`) **after** auth — `REPLAY → 409` (`:626-629`),
`STALE/MALFORMED → 401` (`:620-625`). It is **opt-in / default OFF**
(`ConfigdServer.java:648-653`, `-Dconfigd.replay.enabled=true`). Auth itself is bearer-token only
(`AuthInterceptor.java:50-55`; the server validator is a constant-time token compare,
`ConfigdServer.java:480-489`).

### CURRENT GAP
The guard's own Javadoc is explicit (`ReplayGuard.java:17-24`): it defends against a **passive**
attacker who re-sends a captured request verbatim. It does **NOT** stop a holder of the bearer token
from minting a **fresh** request (new nonce + current timestamp). Because the bearer token is the only
credential and there is no per-request content binding, a token-holder (or anyone who captured a token
in transit on a misconfigured plaintext deployment) has full mint capability — the replay guard adds
nothing against an active token-holder. Recorded as a known residual in
`docs/session-7/api-security.md:60-69`.

### FIX LOCATION + APPROACH (minimal blast radius)
Add **active per-request content signing** (SigV4-style HMAC), as recommended in
`api-security.md:62` and `ReplayGuard.java:20-23`. Approach:

- Define a canonical request string = `method + "\n" + path + "\n" + sha256(body) + "\n" + timestamp
  + "\n" + nonce`, and require an `Authorization`/`X-Configd-Signature` header =
  `HMAC-SHA256(K_request, canonical)` where `K_request` is per-principal (derived from / associated
  with the principal's credential, not a shared bearer).
- **Where:** the natural seam is a new check co-located with `replayRejected`
  (`HttpApiServer.java:608`), called from the same `handlePut`/`handleDelete` sites (`:393`, `:419`)
  immediately after auth — the signature binds method+path+body, so the existing `ReplayGuard`
  nonce/window becomes the anti-replay half of a now-*active* scheme (signature proves freshness +
  integrity, nonce prevents reuse). The signing-key material can reuse the HKDF domain-separation
  pattern already used for `K_audit` (`ConfigdServer.java:906-917`) keyed per principal.
- This is additive (a new optional gate beside the replay gate); the bearer path stays for
  back-compat behind the same opt-in flag style.

### NEGATIVE TEST (attack → refused)
- **Existing passive-replay fixture:** `ConfigHandlerReplayTest`
  (`configd-server/src/test/java/io/configd/server/ConfigHandlerReplayTest.java:96`
  `verbatimReplayIsRejectedWhileFreshNonceIsAccepted`, plus `:116`/`:125`/`:139`) proves verbatim
  replay → 409 and fresh nonce → accepted.
- **New active-rejection fixture the fix must make pass:** capture a validly-signed request, then
  **mint a fresh request** (new nonce + current timestamp) over a *tampered* body or path **without
  re-signing** → assert **401/403** (signature mismatch). The discriminating assertion is that a
  valid bearer token alone is **insufficient** to mutate a body the principal did not sign — the gap
  today is that this exact attack returns 200.

### RR-002 / THREADING RISK
**None.** HMAC verification runs on the HTTP request vthread (domain c,
`HttpApiServer.java:123`) **before** the proposal is marshalled to the tick thread
(`ConfigdServer.java:1102`). It is pure CPU on the request thread; it never touches `RaftNode` or
`configd-tick`. (It does add request-thread latency, not consensus latency.)

---

## Item 5 — Per-principal rate limiting (Med)

### MECHANISM (today)
`RateLimiter` (`configd-control-plane-api/src/main/java/io/configd/api/RateLimiter.java:21`) is a
single lock-free token-bucket (CAS over one `AtomicLong` pair, `:30-31`, `:82-128`). It is
constructed **once, globally**, at 10k/s + 10k burst (`ConfigdServer.java:522-524`) and gates the
write path in `ConfigWriteService.put` (`ConfigWriteService.java:187-189` →
`WriteResult.Overloaded`) and `delete` (`:218-220`). Crucially it gates **BEFORE** the proposal
reaches Raft: `tryAcquire()` is checked at `:187`/`:218`, and only on success does control reach
`proposer.propose(scope, command)` at `:199`/`:223` (which marshals onto the tick thread,
`ConfigdServer.java:1102`). So the limiter correctly sits in front of the Raft proposal queue today —
the only gap is its *granularity*.

### CURRENT GAP
The bucket is **global**, not per-principal (recorded `api-security.md:71`,
"the limiter is currently global"). One noisy/hostile authenticated principal can consume the entire
10k/s budget and starve every other principal — there is no fairness or per-tenant isolation. The
principal is already resolved at the call site (the `AuthCheck.principal()` from
`HttpApiServer.java:543-573`) but is **not** passed to the limiter.

### FIX LOCATION + APPROACH (minimal blast radius)
Key the limiter per authenticated principal:

- Change `ConfigWriteService` to hold a **per-principal limiter map** (`ConcurrentHashMap<String,
  RateLimiter>` with `computeIfAbsent`, sized/evicted to bound memory), or pass a principal-keyed
  limiter facade. Add the principal as a parameter to `put`/`delete`
  (`ConfigWriteService.java:170`, `:210`) — the caller already has it in `handlePut`/`handleDelete`
  (`HttpApiServer.java:404` calls `writeService.put(...)`; the principal is `authCheck.principal()`,
  available at `:383`/`:411`). Wire it from `HttpApiServer` so the existing `tryAcquire()` checks
  (`:187`/`:218`) become per-principal `tryAcquire()`.
- Keep the **gate position unchanged** — still before `proposer.propose` (`:199`/`:223`), so it
  still protects the Raft proposal queue (the whole point: shed at the edge, never enqueue onto the
  tick thread). Optionally retain a global ceiling as a second bucket so the cluster total is still
  bounded.
- Construction site moves from the single `new RateLimiter(...)` (`ConfigdServer.java:524`) to a
  factory the write service uses per principal (same params as defaults).

### NEGATIVE TEST (attack → refused)
- **Existing limiter fixture:** `RateLimiterTest`
  (`configd-control-plane-api/src/test/java/io/configd/api/RateLimiterTest.java`) proves the bucket
  math (sustained rate, burst, refill).
- **New per-principal fixture the fix must make pass:** drive principal A past its per-principal
  limit (assert A gets `Overloaded`/429) while principal B, under its own limit, **still succeeds** —
  i.e. A's flood does NOT starve B. A `ConfigWriteServiceTest`-style test
  (`configd-control-plane-api/src/test/java/io/configd/api/ConfigWriteServiceTest.java`) injecting a
  small-rate limiter and two principals is the discriminating proof; pre-fix B is starved (global
  bucket), post-fix B is isolated.

### RR-002 / THREADING RISK
**None — and it actively protects the tick thread.** `tryAcquire()` is a lock-free CAS on the HTTP
request vthread (`RateLimiter.java:82-128`, domain c) and runs **before** the marshal to
`configd-tick` (`ConfigWriteService.java:187`→`:199`; `ConfigdServer.java:1102`). Per-principal
keying adds only a `ConcurrentHashMap` lookup on the request thread. Sharding the limit cannot block,
slow, or touch consensus; it makes the shed *fairer* while keeping load off the tick thread.

---

## Cross-cutting notes

- **Highest risk = Item 1 (Raft inbound slowloris, HIGH).** It is the only HIGH, it is on the plane
  with no LB in front of it, and it has **neither** mitigation (no read deadline, no cap) — unlike the
  edge fan-out which at least has `maxSessions` (`FanOutServer.java:228`). FD exhaustion there denies
  consensus admission cluster-wide.
- **The edge fan-out is the template.** `FanOutServer` already does admission-before-handshake
  (`:228`) + bounded handshake (`:255`); Items 1's cap fix is "do what `FanOutServer` already does"
  on `TcpRaftTransport` and the HTTP servers.
- **RR-002 fence holds for every fix here.** None of the five fixes adds work to `configd-tick`
  (domain a). The slowloris fix lands on transport vthreads (domain b), the TLS-expiry check on the
  reload/connector threads, and the replay/rate-limit fixes on HTTP request vthreads (domain c) — all
  strictly before the marshal to tick. The `NoBlockingConnectOnConsensusPathTest` static guard
  (`:88`) remains the gate to re-run after the Item 1 change.
