# Group C — Protocol-Client Proof Arc: synthesized design & decisions

**Status: as-designed (2026-07-08). Anchors the build.** This is the synthesis of the three
investigation lanes (all under `investigation/`) plus the operator decisions taken at the
investigation→build checkpoint. It is the design of record for the arc; the gate build follows it.

- `investigation/ref-client-and-conformance-research.md` — libpq / etcd-clientv3 / gRPC-conformance
  primary-source lessons + the two-checklist "what a correct client / conformance suite MUST do".
- `investigation/rfc-requirements-catalog.md` — the exhaustive normative-clause catalog (~270 clauses,
  §00–§07), the frame catalog, the auth + watch state machines, and the **gaps/drift list** (§7 of that
  doc) — the RFC bugs the client build must fix now.
- `investigation/client-architecture.md` — the module + class architecture and the codec-extraction
  analysis.

## Thesis

A protocol is proven real by a **working conforming client + a conformance suite**, not by an RFC
document (the Postgres bar: libpq proves the wire). This arc builds a real, conforming Java reference
client for both planes, built purely from the RFC, hardened against a hostile server, plus a
conformance suite that validates *client-conforms* and *server-obeys-its-own-RFC*. Standing rule:
**never defer** — every protocol path built now; every RFC gap the client exposes fixed now.

## Decisions (operator-ratified at the checkpoint)

1. **Codec sharing = EXTRACT a new `configd-wire` module, keep package names.** A reference client must
   be thin (etcd-clientv3/libpq ship no server). Depending on `configd-distribution-service` drags the
   whole server data plane + consensus in. The frozen wire classes + the JDK-only `io.configd.store.*`
   value types they need are relocated **verbatim** (bytes unchanged; golden fixtures move with them and
   re-prove byte-identity). Package names are preserved (`io.configd.distribution.wire.*`,
   `io.configd.store.*` become split packages — harmless without JPMS), so no repo-wide import rewrite.
2. **Public API = hybrid, the right primitive per surface.**
   - **Watch / subscribe streams → reactive `java.util.concurrent.Flow.Publisher`.** A watch is a
     backpressured, unbounded event stream; reactive is the honest model and maps directly onto the
     `CURSOR_ACK` flow-control the protocol already implements (`request(n)` gates how far the drain
     advances before the next ack). This is where serious high-concurrency consumers live — first-class.
   - **Request/response (connect, auth, get/put/delete, admin) → `CompletableFuture`.** Naturally async
     request/response; futures compose cleanly.
   - **Blocking façade layered on both** — for the reference/conformance driver (which must stay simple,
     deterministic, readable) and for simple synchronous callers.
3. **Artifacts = split CRUD vs edge**, over a shared core:
   - `configd-client-core` — the §07 exception hierarchy (each exception type *is* its §07 reaction),
     the `(code, carrier)` retry classifier, `RetryPolicy`, `HostileServerLimits`, `CursorStore` SPI,
     `Configd` entry façade, shared TLS setup.
   - `configd-client-http` — the CRUD plane (`ConfigdHttpClient`, `LeaderRouter`, `ReplayGuardSigner`,
     `GetResult`, `WriteOutcome`).
   - `configd-client-edge` — the watch/subscribe plane (`ConfigdEdgeClient`, `EdgeConnection` state
     machine, `AuthLifecycle`, `SignedChainVerifier`, `SnapshotReassembler`, `Subscription`/`Watch`
     handles, the reactive publishers, `LocalConfigView`).

## Module graph

```
configd-common ─┐                         configd-transport ─┐
                └──► configd-wire ◄────────────────────────── │  (TLS: SSLContext/TlsConfig)
                     (NEW: EdgeFrame, EdgeFrameCodec, FrameType, ErrorCode, WatchCursor,
                      FrameSink/HeapFrameSink, CommitNotification, + the JDK-only store value
                      types: ConfigDelta, ConfigMutation, CommandCodec, ConfigSnapshot,
                      VersionedValue, ReadResult, ConfigSigner — exact closure is compile-driven.
                      Golden fixtures EdgeFrameGoldenBytes + V1..V4 tests relocate here.)
                          ▲                    ▲
      config-store, distribution-service ──────┘  (now depend on configd-wire for the moved types)

Client tree (all NEW):
  configd-client-core ──► { configd-wire, configd-common, configd-transport }
  configd-client-http ──► { configd-client-core }
  configd-client-edge ──► { configd-client-core }
  configd-conformance (Gate 5, test-scope driver) ──► { client-http, client-edge, configd-server(live) }
```

Transport: **edge** = raw JDK blocking `SSLSocket` + one reader thread per connection (grows the
`EdgeProtocolClient` seed; no Netty dep, testable against a loopback mock). **HTTP** = JDK `HttpClient`
HTTP/1.1. Correctness/clarity/testability over throughput — this is a reference client.

## Hostile-server hardening (symmetric to the server's hostile-client hardening)

The client enforces, on every inbound server frame, the **same bounds the shared codec enforces on the
server** (free from reusing `EdgeFrameCodec`): `peekLength`-before-alloc, CRC-before-interpret,
client-side version pin + type↔version legality, strict-end, every inner length/count bounded. Plus
client-specific bounds: snapshot accumulation caps (bounded total + chunk-count), `HEARTBEAT`-silence
read-idle reconnect, no unbounded buffering (backpressure via `CURSOR_ACK`, handle `DEMOTED_TO_CATCHUP`
not close), branch on `(code, carrier)` + sanitize untrusted diagnostic strings, treat signature
verification as distinct from CRC, HTTPS endpoint-identification + no plaintext downgrade, and
capacity-vs-protocol distinction (a silent pre-handshake session-cap close ⇒ backoff, not a protocol
error). libpq CVE lesson: **discard anything read before the security handshake completes.**

## RFC drift/gaps to FIX during the build (no deferral — arc §5/§6)

From `investigation/rfc-requirements-catalog.md` §7. Each is a spec bug the client build exposes; owned
by the gate that touches it, re-validated in the review loop.

1. **Undocumented 5th HTTP route** `POST /v1/admin/groups/{gid}/transfer-leadership?target=` contradicts
   the RFC's "only 4 routes else 404." Document it in §04/§05 with its taxonomy; fix the naive
   "unknown-path⇒404" expectation. (Gate 4.)
2. **AU4-5 contradiction:** "any non-AUTH frame before auth ⇒ PROTOCOL_VIOLATION" vs the server buffering
   + replaying a `SUBSCRIBE` pipelined behind `AUTH` (≤8, no AUTH-OK ack). Split "before AUTH" vs
   "pipelined behind AUTH" in §03/§06. (Gate 1.)
3. **Edge auth-unavailable** collapses into `AUTH_FAIL(4)` with no retryable signal (HTTP has 503); E2-1
   footnote stale. Document the edge behavior honestly + add the missing E4-1 edge row. (Gate 1.)
4. **F10-1b single-shared-drain silent-data-loss** footgun is buried in one §06 clause — promote to a
   prominent §02 MUST + a **required negative** conformance case; the client default is one connection
   per independently-resumed watch. (Gate 3.)
5. **Stale `AdminApiHandler` line citations** across §04/§05/§07 (file grew). Refresh. (Gate 4/review.)
6. **Ambiguities to resolve honestly:** session-layer vs codec "MUST reject" (scope/target_kind/
   path-grammar); W5-4a "reject (or ignore)" unknown WATCH_CREATE flag bits is self-contradictory.
   Pin the real behavior against the code. (Gate 3.)
7. **Not-testable-at-v1** (annotate, never false-cover): `STALE_TOPOLOGY(12)`, `has_oldest=1` vector,
   positive `prev_value`, cross-shard ordering semantics. (Gate 5.)

## Conformance suite model (Gate 5) — mirrors the protobuf/gRPC split

- **Runner I — wire-format:** golden-byte vectors (encode + decode round-trip vs `EdgeFrameGoldenBytes`)
  + a **poison-frame corpus** (every reject path: bad version, bad CRC, over-cap length, illegal
  type↔version, trailing bytes, bad inner lengths) with a `--failure_list`-style **expected-results
  manifest** that fails on an unexpected pass *or* fail (the ratchet).
- **Runner II — behavioral:** one case per normative RFC MUST, run **both directions** — *client-conforms*
  (drive the reference client against a mock/loopback server, incl. a hostile server) and
  *server-obeys-RFC* (drive a **live `ConfigdServer`** through every operation + every reject/error/
  fail-closed path with the real client) — across the 4 auth modes. Per-requirement pass/fail breakdown.
- **Frozen contract:** the suite is what a future other-language SDK is validated against. Wired into CI.
- **Permanent review invariant:** Configd has *no* version negotiation, yet every reference client
  (libpq/etcd/grpc) negotiates/downgrades. The suite MUST carry explicit **negative** cases proving our
  client never attempts a hello/downgrade and fails closed on an unknown version — a well-meaning
  contributor will eventually add a downgrade path.

## Gate plan

- **Gate 1 — wire extraction + reference-client core (connection + auth lifecycle).** Extract
  `configd-wire` (full reactor green, byte-identity re-proven). Build `configd-client-core` +
  `configd-client-edge` connection/`AuthLifecycle`: TLS/mTLS connect, the 4 auth modes as the client
  presents them, single pre-auth `AUTH`, proactive `REFRESH_AUTH` in the lead-time window,
  `CLOSE`/`CREDENTIAL_EXPIRED`→reconnect, cert lead-time reconnect. Hostile-server-hardened. Fix RFC
  gaps 2, 3.
- **Gate 2 — read/subscribe/hydrate.** `SUBSCRIBE` + full-store hydration, filtered where applicable,
  the `SignedChainVerifier` the client must run, `SnapshotReassembler`, resume-after-disconnect.
- **Gate 3 — watch (single + multi-shard fan-in).** `WATCH_CREATE/CANCEL`, `WATCH_EVENT`, the vector
  cursor, multi-shard fan-in with the **honest ordering** (per-shard order + watermark, no false global
  order) surfaced through the reactive stream, `WATCH_PROGRESS`, resume-from-vector across
  reconnect/failover, the catch-up ladder. Fix RFC gaps 4, 6.
- **Gate 4 — control plane (`configd-client-http`).** get/put/delete + the admin ops (incl. route #5),
  auth + ACL applied, leader-following + vector cursor, the indeterminate-write contract. Fix RFC gaps
  1, 5.
- **Gate 5 — conformance suite.** Both runners; CI-wired; per-requirement breakdown; the not-testable
  annotations; the no-negotiation negative cases. Fix RFC gap 7.

Each gate merged on **actual full-reactor CI green**. Then §4 exhaustive tests (conformance green,
real-cluster E2E across auth modes + failover-resume + encryption-ON, redteam both directions) and §5
the distinguished-engineer review loop to clean, fixing every remaining RFC gap the client exposed.
