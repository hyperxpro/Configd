# Protocol-Client Design: Module Split and API Shape

The design behind Configd's reference client: the module split, the public API shape, and the
hostile-server hardening it carries. It draws on primary-source lessons from libpq, etcd-clientv3,
and gRPC's conformance suite, and on an exhaustive pass over the driver-protocol RFC's normative
clauses that found and fixed a handful of real spec bugs (below).

## Thesis

A protocol is proven real by a **working conforming client + a conformance suite**, not by an RFC
document (the Postgres bar: libpq proves the wire). Configd has a real, conforming Java reference
client for both planes, built purely from the RFC and hardened against a hostile server, plus a
conformance suite that validates *client-conforms* and *server-obeys-its-own-RFC*. Standing rule:
**never defer** - every protocol path gets built; every RFC gap the client exposes gets fixed.

## Decisions

1. **Codec sharing: extract a new `configd-wire` module, keep package names.** A reference client must
   be thin (etcd-clientv3/libpq ship no server). Depending on `configd-distribution-service` drags the
   whole server data plane + consensus in. The frozen wire classes + the JDK-only `io.configd.store.*`
   value types they need are relocated **verbatim** (bytes unchanged; golden fixtures move with them and
   re-prove byte-identity). Package names are preserved (`io.configd.distribution.wire.*`,
   `io.configd.store.*` become split packages - harmless without JPMS), so no repo-wide import rewrite.
2. **Public API = hybrid, the right primitive per surface.**
   - **Watch / subscribe streams -> reactive `java.util.concurrent.Flow.Publisher`.** A watch is a
     backpressured, unbounded event stream; reactive is the honest model and maps directly onto the
     `CURSOR_ACK` flow-control the protocol already implements (`request(n)` gates how far the drain
     advances before the next ack). This is where serious high-concurrency consumers live - first-class.
   - **Request/response (connect, auth, get/put/delete, admin) -> `CompletableFuture`.** Naturally async
     request/response; futures compose cleanly.
   - **Blocking facade layered on both** - for the reference/conformance driver (which must stay simple,
     deterministic, readable) and for simple synchronous callers.
3. **Artifacts = split CRUD vs edge**, over a shared core:
   - `configd-client-core` - the §07 exception hierarchy (each exception type *is* its §07 reaction),
     the `(code, carrier)` retry classifier, `RetryPolicy`, `HostileServerLimits`, `CursorStore` SPI,
     `Configd` entry facade, shared TLS setup.
   - `configd-client-http` - the CRUD plane (`ConfigdHttpClient`, `LeaderRouter`, `ReplayGuardSigner`,
     `GetResult`, `WriteOutcome`).
   - `configd-client-edge` - the watch/subscribe plane (`ConfigdEdgeClient`, `EdgeConnection` state
     machine, `AuthLifecycle`, `SignedChainVerifier`, `SnapshotReassembler`, `Subscription`/`Watch`
     handles, the reactive publishers, `LocalConfigView`).

## Module graph

`configd-wire` is a new module that both `configd-common`/`configd-transport` and the client tree
depend on. It carries `EdgeFrame`, `EdgeFrameCodec`, `FrameType`, `ErrorCode`, `WatchCursor`,
`FrameSink`/`HeapFrameSink`, `CommitNotification`, plus the JDK-only store value types
(`ConfigDelta`, `ConfigMutation`, `CommandCodec`, `ConfigSnapshot`, `VersionedValue`, `ReadResult`,
`ConfigSigner` - the exact closure is compile-driven), and it uses `configd-transport` for TLS
(`SSLContext`/`TlsConfig`). The golden fixtures `EdgeFrameGoldenBytes` and the V1..V4 tests relocate
here too. `config-store` and `distribution-service` now depend on `configd-wire` for the moved
types.

The client tree is new:

```
configd-client-core  -> { configd-wire, configd-common, configd-transport }
configd-client-http  -> { configd-client-core }
configd-client-edge  -> { configd-client-core }
configd-conformance (test-scope driver) -> { client-http, client-edge, configd-server (live) }
```

Transport: **edge** = raw JDK blocking `SSLSocket` + one reader thread per connection (grows the
`EdgeProtocolClient` seed; no Netty dep, testable against a loopback mock). **HTTP** = JDK `HttpClient`
HTTP/1.1. Correctness/clarity/testability over throughput - this is a reference client.

## Hostile-server hardening (symmetric to the server's hostile-client hardening)

The client enforces, on every inbound server frame, the **same bounds the shared codec enforces on the
server** (free from reusing `EdgeFrameCodec`): `peekLength`-before-alloc, CRC-before-interpret,
client-side version pin + type<->version legality, strict-end, every inner length/count bounded. Plus
client-specific bounds: snapshot accumulation caps (bounded total + chunk-count), `HEARTBEAT`-silence
read-idle reconnect, no unbounded buffering (backpressure via `CURSOR_ACK`, handle `DEMOTED_TO_CATCHUP`
not close), branch on `(code, carrier)` + sanitize untrusted diagnostic strings, treat signature
verification as distinct from CRC, HTTPS endpoint-identification + no plaintext downgrade, and
capacity-vs-protocol distinction (a silent pre-handshake session-cap close => backoff, not a protocol
error). libpq CVE lesson: **discard anything read before the security handshake completes.**

## RFC gaps the client build found and fixed

Building a real client against the RFC exposed a handful of spec bugs, all fixed rather than
deferred:

1. **An undocumented 5th HTTP route**, `POST /v1/admin/groups/{gid}/transfer-leadership?target=`,
   contradicted the RFC's "only 4 routes else 404." Documented in §04/§05 with its taxonomy; the
   naive "unknown-path=>404" client expectation was fixed.
2. **An AUTH-ordering contradiction:** "any non-AUTH frame before auth => PROTOCOL_VIOLATION" vs
   the server buffering and replaying a `SUBSCRIBE` pipelined behind `AUTH` (<=8, no AUTH-OK ack).
   Split "before AUTH" vs "pipelined behind AUTH" in §03/§06.
3. **Edge auth-unavailable** collapsed into `AUTH_FAIL(4)` with no retryable signal (HTTP has 503);
   a stale footnote is fixed. The edge behavior is now documented honestly, with the missing edge
   row added.
4. **A single-shared-drain silent-data-loss footgun** was buried in one clause; promoted to a
   prominent MUST plus a required negative conformance case. The client's default is one connection
   per independently-resumed watch.
5. Stale `AdminApiHandler` line citations across the RFC (the file had grown) were refreshed.
6. **Ambiguities resolved honestly:** session-layer vs codec "MUST reject" (scope/target_kind/
   path-grammar); a self-contradictory "reject (or ignore)" clause for an unknown WATCH_CREATE flag
   bit. Both pinned to the real code behavior.
7. **Not-testable-at-v1 cases are annotated, never false-covered:** `STALE_TOPOLOGY(12)`,
   `has_oldest=1` vector, positive `prev_value`, cross-shard ordering semantics.

## Conformance suite model - mirrors the protobuf/gRPC split

- **Runner I - wire-format:** golden-byte vectors (encode + decode round-trip vs `EdgeFrameGoldenBytes`)
  + a **poison-frame corpus** (every reject path: bad version, bad CRC, over-cap length, illegal
  type<->version, trailing bytes, bad inner lengths) with a `--failure_list`-style **expected-results
  manifest** that fails on an unexpected pass *or* fail (the ratchet).
- **Runner II - behavioral:** one case per normative RFC MUST, run **both directions** - *client-conforms*
  (drive the reference client against a mock/loopback server, incl. a hostile server) and
  *server-obeys-RFC* (drive a **live `ConfigdServer`** through every operation + every reject/error/
  fail-closed path with the real client) - across the 4 auth modes. Per-requirement pass/fail breakdown.
- **Frozen contract:** the suite is what a future other-language SDK is validated against. Wired into CI.
- **Permanent review invariant:** Configd has *no* version negotiation, yet every reference client
  (libpq/etcd/grpc) negotiates/downgrades. The suite MUST carry explicit **negative** cases proving our
  client never attempts a hello/downgrade and fails closed on an unknown version - a well-meaning
  contributor will eventually add a downgrade path.

## What the client covers

- **Wire extraction and connection/auth lifecycle.** `configd-wire` extracted with byte-identity
  re-proven. `configd-client-core` + `configd-client-edge` implement the connection and
  `AuthLifecycle`: TLS/mTLS connect, the 4 auth modes as the client presents them, a single
  pre-auth `AUTH`, proactive `REFRESH_AUTH` in the lead-time window,
  `CLOSE`/`CREDENTIAL_EXPIRED`->reconnect, and cert lead-time reconnect. Hostile-server-hardened.
- **Read, subscribe, and hydrate.** `SUBSCRIBE` plus full-store hydration, filtered where
  applicable, the `SignedChainVerifier` the client runs, `SnapshotReassembler`, and
  resume-after-disconnect.
- **Watch, including multi-shard fan-in.** `WATCH_CREATE`/`CANCEL`, `WATCH_EVENT`, the vector
  cursor, multi-shard fan-in with the **honest ordering** (per-shard order and watermark, no false
  global order) surfaced through the reactive stream, `WATCH_PROGRESS`, resume-from-vector across
  reconnect/failover, and the catch-up ladder.
- **Control plane** (`configd-client-http`): get/put/delete plus the admin ops (including the
  undocumented 5th route), auth and ACL applied, leader-following with the vector cursor, and the
  indeterminate-write contract.
- **The conformance suite:** both runners, CI-wired, with a per-requirement breakdown, the
  not-testable annotations, and the no-negotiation negative cases.

Verification beyond the conformance suite includes real-cluster end-to-end tests across every auth
mode, failover-resume, and encryption-on, plus a redteam pass in both directions (client against a
hostile server, and server against a hostile client).
