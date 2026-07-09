# Reference-Client & Conformance-Suite Research (Group C investigation)

**Status: investigation notes (2026-07-08). Non-normative.** Primary-source study grounding the design of
(a) a Java **reference client / SDK** for the Configd driver protocol and (b) a **protocol conformance
suite**. Thesis of the arc: *a protocol is proven real by a working conforming client + a conformance suite,
not by an RFC document* (the "Postgres bar": libpq proves the wire).

This document extracts **lessons**, not code. Every claim is tagged with its source and how it maps onto the
Configd RFC (`docs/rfc/driver-protocol/00..07`). Where a claim is inference rather than sourced fact it is
marked **(inference)**.

Sources studied:

- **libpq** — the PostgreSQL C client; the canonical "client that proves a wire protocol."
  Protocol flow: <https://www.postgresql.org/docs/current/protocol-flow.html>;
  message formats: <https://www.postgresql.org/docs/current/protocol-message-formats.html>.
- **etcd clientv3** (Go) — the closest analog to the Configd watch plane.
  Watch API: <https://etcd.io/docs/v3.5/learning/api/>; guarantees:
  <https://etcd.io/docs/v3.5/learning/api_guarantees/>.
- **protobuf / gRPC conformance model** — how a case-per-requirement suite is run against *any*
  implementation. protobuf conformance: <https://github.com/protocolbuffers/protobuf/tree/main/conformance>;
  gRPC interop behavioral tests:
  <https://github.com/grpc/grpc/blob/master/doc/interop-test-descriptions.md>.
- **Hostile/buggy-server client safety** — grpc-java message-size caps + HTTP/2 flow control
  (<https://grpc.io/docs/guides/flow-control/>), Netty `LengthFieldBasedFrameDecoder.maxFrameLength`
  (<https://netty.io/4.1/api/io/netty/handler/codec/LengthFieldBasedFrameDecoder.html>), and the libpq
  pre-auth-buffer CVEs (CVE-2021-23214 / CVE-2021-23222).

---

## 1. libpq — the canonical "client that proves the wire"

**What it solves / why it's the reference.** libpq is the reference frontend for the PostgreSQL FE/BE
protocol; the wire is considered "real" precisely because a single, widely-deployed C client exercises every
message and every other language's driver either wraps libpq or is validated against the same wire. Its value
here is not features but *discipline*: a small, explicit connection state machine, a self-describing frame,
typed machine-readable errors, and a hard rule that the client tolerates asynchronous messages at any time.

**L1.1 — Self-describing 1-byte-type + Int32-length framing.**
*(source: PostgreSQL protocol-message-formats; mechanism)* Every backend message is
`[Int8 type][Int32 length]` where the length **includes itself but not the type byte**, then the payload.
The frame is length-prefixed so the reader always knows how many bytes to consume before interpreting them.
→ **Maps to Configd §06 F2/F3:** the EdgeFrame envelope is `[u32 Length][...][CRC32C]` with `Length ==
buffer.length` and a strict decode order. Configd is *stricter* (CRC before type/version; fixed-positional,
not TLV) — but the libpq lesson stands: **read the length, bound it, then interpret; never interpret before
you have the whole frame.**

**L1.2 — The error is a typed field set; the CODE is the branch point, the message is for humans.**
*(source: protocol-flow ErrorResponse/NoticeResponse; mechanism)* `ErrorResponse`/`NoticeResponse` carry a
sequence of typed fields terminated by NUL: `S` severity, `C` **SQLSTATE code** (the 5-char machine code,
e.g. `23505`), `M` primary human message, plus optional `D`/`H`/`P`/… (detail/hint/position). Well-written
clients **branch on `C` (SQLSTATE), never on `M`** — `M` is localized/human and unstable.
→ **Maps to Configd §07 E6 exactly:** "branch on the code (+ carrier frame), never the body/message; the
message may echo attacker input and is not escaped — sanitize before logging." libpq is the industry
precedent that makes E6 non-negotiable. **The conformance suite must assert the client never branches on a
message string.**

**L1.3 — A frontend MUST accept `ErrorResponse`/`NoticeResponse`/`ParameterStatus`/`NotificationResponse` at
*any* time.** *(source: protocol-flow, "A frontend must be prepared to accept ErrorResponse and
NoticeResponse messages whenever it is expecting any other type of message"; mechanism)* Async/out-of-band
messages interleave with the request/response flow; the read loop is a demultiplexer, not a
call-and-wait.
→ **Maps to Configd:** `HEARTBEAT` (§06 F6-8), `WATCH_PROGRESS` (§02 W5-7), a mid-stream
`WATCH_SNAPSHOT_*` resync (W6-3), a mid-stream `NOT_AUTHORIZED` revocation (W7-7), and `DEMOTED_TO_CATCHUP`
(a **non-fatal** notice riding `ERROR_CLOSE`, §07 E3-2) all arrive interleaved. **The client read loop must
be a frame demultiplexer that dispatches by (type, carrier), not a state machine that assumes "the next
frame answers my last request."** This is the single most under-appreciated libpq lesson for the watch
plane.

**L1.4 — An explicit resync point after every command and every error (`ReadyForQuery`).**
*(source: protocol-flow; mechanism)* `ReadyForQuery` is emitted after startup and after *each* command cycle
(including after an error), giving the client an unambiguous "safe to proceed / I am live again" signal.
libpq's state machine keys resynchronization on it.
→ **Maps to Configd §02 W5-5:** `WATCH_CREATED` is the mandated "authorized and live" signal and **MUST be
the first frame for a `watch_id`**; the HTTP plane's analog is the response status itself. **The client must
not deliver any watch data to the application until it has seen `WATCH_CREATED`** for that watch, exactly as
libpq gates on `ReadyForQuery`. (inference: Configd has no *connection-level* resync frame; per-watch
`WATCH_CREATED` is the closest analog and should be treated as load-bearing.)

**L1.5 — Non-blocking connect + auth is an explicit polled state machine.**
*(source: libpq `PQconnectStartParams`/`PQconnectPoll`, `PGRES_POLLING_READING/WRITING/OK/FAILED`;
mechanism)* libpq exposes connection establishment as a state machine you pump, so TLS handshake, auth
exchange, and startup are observable, cancelable, and timeout-able rather than a hidden blocking call.
→ **Maps to Configd §06 F10-1 lifecycle:** `connect → TLS/mTLS handshake → authenticate-before-any-business-
frame → first-frame pin → operate`. **The Configd client should model the connection lifecycle as an
explicit state machine** (states: `CONNECTING, TLS_HANDSHAKING, PRE_AUTH, AUTHENTICATED, PINNED/OPERATING,
DRAINING, CLOSED`) so every wait has a timeout and every illegal transition is a typed error — this is what
makes the AU4 pre-auth discipline (one `AUTH` frame, no business frame before auth, first-frame deadline)
testable rather than emergent.

**L1.6 — Version handling: request a version, let the server negotiate down or reject.**
*(source: protocol-flow; StartupMessage protocol version + `NegotiateProtocolVersion`; mechanism)* libpq
requests protocol `3.0`; a server that supports a lower minor answers `NegotiateProtocolVersion`, otherwise
`ErrorResponse`. **This is a divergence to flag, not a lesson to copy:** Configd deliberately has **no
negotiation** (HTTP `/v1/` path prefix; edge **first-business-frame pin**, §00 OV4, §06 F4). The Configd
client MUST **not** attempt a hello/downgrade round-trip; it stamps its version on the first business frame
and **fails closed** (`BAD_WIRE_VERSION`) on anything else. **(inference)** The conformance value of libpq's
model is the *contrast*: our suite must include a negative case proving the client never expects a
negotiation reply and never downgrades.

**Non-goals libpq chose (worth mirroring):** libpq does **not** parse result *semantics* for you, does not
retry for you, and does not hide the async-message stream — it gives you a correct, minimal wire and a
demultiplexer. Configd's SDK should likewise keep the "wire-correct core" separate from any convenience/retry
layer (see §5).

---

## 2. etcd clientv3 — the closest analog (study this hardest)

**What it solves / architecture.** etcd's clientv3 is a Go client for a Raft-replicated KV store with a
**Watch** stream (revision-resumable change subscription), **Lease KeepAlive** (renew-before-expiry), and a
**reconnecting endpoint balancer**. The mapping to Configd is nearly one-to-one:

| etcd clientv3 | Configd |
|---|---|
| global `revision` (mvcc) | per-shard applied-seq `S` (§02 W3-1..W3-2); Configd's is a **vector**, etcd's a scalar |
| `WithProgressNotify` / `RequestProgress` → empty `WatchResponse` | `WATCH_PROGRESS` bookmark (§02 W5-7 / W4-4) |
| `WatchResponse.Created` / `Canceled` | `WATCH_CREATED` (0x0C) / `WATCH_CANCELED` (0x0F) |
| `WatchResponse.CompactRevision` + `ErrCompacted` | `GAP_UNRECOVERABLE` (6) / `STALE_TOPOLOGY` (12) → re-list + re-create |
| `start_revision = 0` ⇒ "from now" | cursor component `0`/`count==0` ⇒ "from now per shard" (§02 W3-4) |
| watch multiplexed over **one** gRPC stream by watch id | `watch_id` multiplex over one edge connection (§02 W2-8) |
| Lease KeepAlive before TTL | `REFRESH_AUTH` before credential expiry (§03 AU5-6) |
| reconnecting balancer / endpoint list | `X-Leader-Hint` follow + `NodeId→address` map + reconnect (§05) |

**L2.1 — Revision-resume is the whole ballgame; `start_revision = 0` means "now," not "replay-all."**
*(source: etcd Watch API docs; mechanism)* A watch without `start_revision` streams from the create-response
header revision; to recover existing state you must first read/snapshot, then watch from that revision.
→ **Maps to Configd §02 W3-4** (already cited in the RFC as "the etcd footgun, closed"). **Reinforces that
the client must expose `with_initial_snapshot` (bit2) as the *only* way to get current state**, and must
default a fresh watch to from-now. The conformance suite must include the negative: a fresh watch with a zero
cursor and no snapshot flag receives **no** historical events.

**L2.2 — Progress notifications exist because idle watches otherwise force full recovery.**
*(source: etcd `WithProgressNotify`, the `RequestProgress` RPC, and the "Bookmarking" guarantee: "Progress
notification events guarantee that all events up to a revision have been already delivered"; mechanism)* An
idle watcher whose revision never advances falls behind compaction and is forced into a costly re-list on
reconnect. etcd added periodic and on-demand progress to advance the resume point with no events.
→ **Maps to Configd §02 W4-4 / W5-7** — and the RFC notes it "matters *more* under sharding because each
shard compacts independently." **The client MUST advance the relevant cursor component on `WATCH_PROGRESS`
even with zero delivered events.** The conformance suite must assert: after a long idle period a
progress-advanced client resumes with an incremental tail (not a snapshot re-bootstrap).

**L2.3 — Compaction is a *distinct, terminal* signal carrying the recovery boundary; the client re-lists.**
*(source: etcd `CompactRevision` + `ErrCompacted`; "creating new watches with the same start_revision will
fail"; mechanism)* When a watch's revision is compacted away, etcd sets `CompactRevision`, terminates the
watch, and the client must re-list current state and re-watch from the new revision — it must **not** retry
the same revision.
→ **Maps to Configd two distinct codes:** `GAP_UNRECOVERABLE` (6) — "data fell off retention, re-bootstrap
that watch" — and `STALE_TOPOLOGY` (12) — "the cursor's whole topology generation is invalid, drop it
entirely" (§07 E3-1; the RFC explicitly calls STALE_TOPOLOGY "the etcd `ErrCompacted` model"). **Lesson: the
client must treat these as *re-list-then-re-create*, never as *resume-from-earlier-S*** — conflating them
(re-sending a stale cursor) is the classic bug. etcd's single scalar `CompactRevision` becomes, for Configd,
a **per-shard `oldest` vector** (deferred in v1, `has_oldest=0`, §02 W5-9a) — so the v1 client must recover
from *its own* last-held vector, not a server-supplied one.

**L2.4 — The reconnecting balancer is the hardest, most bug-prone component — make it first-class.**
*(source: etcd clientv3 balancer history — the repeated rewrites atop grpc-go's balancer, e.g. the
long-standing "watch/keepalive stuck on a dead endpoint after partition" class of issues; hard-won lesson)*
etcd's endpoint-failover/reconnect logic was rewritten several times because a watch or keepalive could
silently hang on a failed endpoint, or a balancer could pin to a black-holed member. The durable lesson:
**endpoint selection, failover, and stream re-establishment must be an explicit, independently tested
component with its own timeouts — not glue.**
→ **Maps to Configd §05 (leader-following, `NodeId→address` map, retry) + §06 F10-1a (reconnect keeps only
the persisted cursor; loses all `watch_id`s and multiplex state).** **Lesson for our SDK: the
reconnect/resume machinery is where correctness bugs hide** — it must be a named module with fault-injection
tests (drop the socket mid-snapshot, mid-`WATCH_EVENT`, right after `WATCH_CREATED`) proving no missed/
duplicated events across the boundary (§02 W6-5 max-merge dedup).

**L2.5 — `WithRequireLeader`: a partitioned server can serve a watch that silently never advances.**
*(source: etcd `clientv3.WithRequireLeader` → `ErrNoLeader`; hard-won lesson — added after watches on a
lost-quorum member hung indefinitely)* etcd let clients demand the serving member have a leader, so a
partitioned member returns an error instead of a silently-stale stream.
→ **Maps to Configd's staleness frontier + heartbeat liveness (§02 W8-1/W8-3, §06 F6-8/F10-3).** A Configd
watch is edge-served and **bounded-stale, not linearizable** (W6-1); the client's defense against a
silently-stalled stream is **read-idle detection on `HEARTBEAT`/`WATCH_PROGRESS`** and a
staleness-frontier check, then reconnect. **The client MUST have a read-idle timeout and MUST NOT assume "no
frames" means "no changes."**

**L2.6 — Lease KeepAlive = renew-before-expiry, proactively, on a timer.**
*(source: etcd `LeaseKeepAlive` bidi stream returning the refreshed TTL; mechanism)* The client streams
keepalives at roughly TTL/3 to hold the lease; if it stops, the server expires the lease.
→ **Maps to Configd §03 AU5-6 exactly:** static token has a server-side session cap (default 1h); OIDC/JWT
expires at `exp`; the client **SHOULD** send `REFRESH_AUTH` within a lead-time window **before** expiry, and
treat `CREDENTIAL_EXPIRED` (13) as *re-authenticate/reconnect*, never a codec bug or a permanent forbid.
**Lesson: proactive refresh on a timer keyed to the credential's known lifetime, not reactive on the close.**

**L2.7 — Watch fragmentation: a single revision's events can exceed the frame/message cap and wedge the
stream.** *(source: etcd `WithFragment()` / server `--max-request-bytes`, added in etcd 3.4 after large
single-revision watch responses could not be sent and got stuck; hard-won lesson)* etcd had to add response
fragmentation because one revision's event batch could exceed the max send size and the watch would stall
permanently.
→ **Maps to Configd §02 W5-6:** "one event = one shard-commit, batch-atomic, MUST NOT split" — bounded by
`MAX_NOTIFY_BATCH*` and the 2 MiB frame cap; **a single oversized commit that cannot fit one frame is a named
v2 fragmentation extension (W10-3).** **Lesson: the client must fail *closed and legibly* if it ever meets a
commit that would exceed the frame cap (it surfaces as `FRAME_TOO_LARGE` (2), a producer bug), and the
conformance suite must include the boundary case** — this is a real etcd outage class, not a hypothetical.

**Non-goals etcd chose:** clientv3 does **not** give cross-key/global ordering beyond revision (Configd is
weaker still: **per-shard only**, no global order — §02 W6-2); it does **not** make watches linearizable; it
does **not** reuse a canceled watch id implicitly. Configd hardens the last point into a **MUST NOT reuse a
`watch_id`** rule (W2-8) to kill the late-frame-misattribution hazard — a defensive pattern etcd does not
guarantee.

---

## 3. gRPC / protobuf conformance model — how a suite becomes the frozen contract

**What it solves / architecture.** Two complementary models, both worth copying:

**(A) protobuf conformance suite — wire-format correctness, both directions.**
*(source: protobuf `conformance/` README + `conformance.proto`; mechanism)* A single
`conformance_test_runner` process drives a **testee** process over a pipe (stdin/stdout). The wire between
them is **length-prefixed** `ConformanceRequest`/`ConformanceResponse` messages (a 4-byte little-endian size
prefix + the serialized proto). Each **test case asserts one normative requirement**; the runner sends a
payload plus *which format to emit*, and checks the round-trip. Crucially it validates **both directions**:
*parse* (bytes → message: does the testee accept valid input and **reject** malformed input?) and *serialize*
(message → bytes). Results are per-case pass/fail.

**L3.1 — The suite is language-agnostic because the runner only speaks a tiny harness protocol to the
testee.** The implementation under test exposes a thin adapter ("here is input in format X, give me output in
format Y"); the runner owns all the cases. → **Maps to Configd:** define a minimal **conformance harness
protocol** (a testee adapter the runner drives) so the *same* suite can validate a Java client today and a
Rust/Go/Python client later. **This is the mechanism that makes the suite the frozen contract rather than a
Java unit test.**

**L3.2 — The `--failure_list` / expected-failures file is the anti-regression ratchet.**
*(source: protobuf conformance `--failure_list`; mechanism)* An implementation ships a text file naming the
cases it is *known* to fail; the runner passes iff the actual failure set **equals** the declared set — an
**unexpected failure** (a regression) **and an unexpected pass** (stale list / silently-fixed) both fail CI.
→ **Maps to Configd:** the conformance suite must have an explicit, checked-in expected-results manifest so
the contract can only change deliberately. This is exactly the Configd ethos ("the code won"; golden fixtures
are the wire test vectors, §00 OV5-5). **Lesson: bidirectional drift detection (no unexpected pass *or* fail)
is what makes a suite a contract.**

**L3.3 — Test *both directions* of every codec, and especially the *reject* direction.** The protobuf suite's
most valuable cases are the malformed-input cases that a correct parser must reject.
→ **Maps to Configd §06 F3/F11 fail-closed:** unknown version ⇒ `BAD_WIRE_VERSION`; unknown type / trailing
bytes / illegal-type-for-version ⇒ `FRAME_CORRUPT`; `u64` high-bit set ⇒ `FRAME_CORRUPT` (F5). **The suite
must feed the client hostile/corrupt frames and assert the exact mapped rejection — the golden fixtures cover
the *accept/emit* direction; a corpus of *poison frames* is needed for the *reject* direction.**

**(B) gRPC interop tests — behavioral (semantic) conformance.**
*(source: gRPC `interop-test-descriptions.md`; mechanism)* Separately from wire-format, gRPC ships a
cross-language behavioral suite: named scenarios (`empty_unary`, `large_unary`, `client_streaming`,
`cancel_after_begin`, `cancel_after_first_response`, `timeout_on_sleeping_server`, auth scenarios, …) run by
a test driver against any language's client/server. Each scenario asserts a *behavior*, not a byte layout.
→ **Maps to Configd's per-section driver checklists (§00–§07)**, which are behavioral, not byte-level.
**Lesson: Configd needs *two* suites, mirroring gRPC's split:** (1) **wire conformance** = golden bytes +
poison frames (protobuf-conformance model); (2) **behavioral conformance** = one runnable case per RFC MUST
clause (interop-test model) — e.g. "fresh zero-cursor watch delivers no history," "mid-stream
`NOT_AUTHORIZED` drops one watch and keeps siblings," "`DEMOTED_TO_CATCHUP` is handled, not closed."

**L3.4 — Validate BOTH endpoints from BOTH directions.** gRPC interop runs the same scenario with each
language as client *and* as server. → **Maps to the team's two-suite framing:** a **client-conforms** suite
(drive the reference client against a reference/mock server and assert client behavior) **and** a
**server-obeys-RFC** suite (drive the real server with a conformance client that emits both valid and hostile
inputs and asserts the server's mapped responses). **Both directions are required or half the contract is
unproven.**

**Non-goals of the conformance model:** protobuf conformance does **not** test performance or transport; gRPC
interop does **not** test wire bytes. Keeping wire-format and behavioral suites *separate* is a deliberate
choice — Configd should not fold them into one runner.

---

## 4. Hostile / buggy-**server** safety in the **client**

The ask: what a correct client does to defend itself against a malicious or broken *server*. The unifying
principle across all sources: **a client trusts the server for *data* only after it has bounded every
*resource* the server can influence — length, time, and buffer occupancy.**

**L4.1 — Bound every server-declared length *before* allocating (reject-before-allocate).**
*(source: Netty `LengthFieldBasedFrameDecoder.maxFrameLength` → `TooLongFrameException`, `failFast=true`
rejects as soon as the length is known, before the whole frame arrives; mechanism)* A length-prefixed
protocol's cardinal defense is a hard `maxFrameLength`; without it a lying length field forces an unbounded
allocation → OOM.
→ **Maps to Configd §06 F3-2 (bounds-before-allocation, load-bearing) and the 2 MiB frame cap
(`FRAME_TOO_LARGE`).** The RFC already mandates bounding **`Length` *and every inner length/count prefix*** —
prefix count, `NOTIFY` count, batch length, cursor count, value length, **and server-controlled snapshot
sizes** — against remaining bytes *and* a configured ceiling. **The client MUST NOT trust "validated
non-negative" as an upper bound (F5); it supplies its own ceiling on every count.** This is the most
important single defensive pattern and it applies to *inner* counts, not just the outer frame — the place
naive length-framed clients get it wrong.

**L4.2 — A hard cap on the largest single server message.**
*(source: grpc-java `GrpcUtil.DEFAULT_MAX_MESSAGE_SIZE = 4 MiB`; `maxInboundMetadataSize` default 8 KiB;
mechanism)* gRPC caps a single inbound message at 4 MiB and inbound headers at 8 KiB by default, so a server
cannot force the client to buffer an arbitrarily large message.
→ **Maps to Configd's 2 MiB edge frame cap and the aggregate ceiling note (§06 F10-2a: session-cap × max-
frame).** **The client should expose its own configurable max-frame/max-snapshot ceiling** (defaulting at or
below the server's 2 MiB) so a compromised server cannot exceed it, and should bound total snapshot bytes
across chunks, not just per-chunk.

**L4.3 — Backpressure, never unbounded buffering of server-sent frames.**
*(source: gRPC/HTTP-2 flow control — per-stream + per-connection windows; "ensure a receiver is not
overwhelmed by a fast sender"; a slow reader stops the sender via the window; mechanism)* The client must
apply backpressure so a fast server cannot grow its heap without bound; it reads at its own pace and the
transport window throttles the sender.
→ **Maps to Configd §06 F10-3 (flow-control is MANDATORY): `CURSOR_ACK` + *drain your socket promptly*; a
slow reader/acker is `DEMOTED_TO_CATCHUP` then `QUARANTINED`.** Configd inverts the usual concern — the
*server* protects itself from a slow client — but the **client-side defense is the dual: the client must
drain and ack rather than let its own receive buffer grow, and must handle the demotion rather than treating
it as fatal (§07 E3-2).** A client that buffers the whole stream in memory instead of applying backpressure
is both self-DoSing and courting quarantine.

**L4.4 — Time-bound every wait; a silent server must not park a client forever (slow-loris, both ways).**
*(source: gRPC keepalive PINGs; etcd dial timeout + keepalive; Configd's own first-frame deadline; mechanism
+ hard-won lesson)* Connect timeout, TLS-handshake timeout, and a **read-idle timeout** are mandatory;
gRPC/etcd send keepalive PINGs and reconnect on silence.
→ **Maps to Configd §06 F10-1d (server-side pre-SUBSCRIBE first-frame deadline, 10s — the anti-slow-loris
defense) + §06 F6-8/F10-3 (`HEARTBEAT` liveness).** **The client's dual obligation: a read-idle timeout keyed
to the `HEARTBEAT` cadence, a TLS-handshake timeout, and a connect timeout — so a black-holed or wedged
server triggers reconnect (L2.5) instead of a hung thread.**

**L4.5 — Do not process any server bytes that arrive *before* the security handshake completes.**
*(source: libpq CVE-2021-23214 / CVE-2021-23222 — a MITM/malicious server could inject plaintext bytes that
libpq buffered and processed as if post-authentication; hard-won security lesson)* PostgreSQL had to fix both
the server and libpq to **discard any data buffered before the SSL/GSS handshake succeeded**, closing an
injection where pre-auth bytes were trusted post-auth.
→ **Maps to Configd §03 AU4-1 / §06 F10-1 (authenticate-before-any-business-frame) and AU3-4 (never send a
credential over plaintext).** **The client MUST NOT emit or accept any business frame before the mTLS
handshake (or the accepted `AUTH` frame) completes, and MUST NOT carry over any bytes read before that point
— a pre-auth buffer is an injection surface.** This is the strongest argument for modeling the lifecycle as
an explicit state machine (L1.5): the pre-auth window is a distinct state with its own tiny frame ceiling
(§06 F6A-5).

**L4.6 — Redirect/endpoint targets are a trust boundary; validate them.**
*(source: general TLS client hygiene + Configd §00 OV7-2 / §05 §8; mechanism)* A client that follows a
server-supplied `NodeId→address` redirect or leader hint must confine it to the configured, same-trust-domain
map and verify the endpoint's TLS identity (§06 F9-4) — a server must not be able to redirect the client to
an arbitrary host.
→ **Maps to Configd §05 R3/§08 (the map is operator config, a trust boundary) and OV7-1 (verify the server;
no plaintext downgrade).** **The client MUST resolve `X-Leader-Hint`/`NodeId` only through its configured map
and MUST fully verify TLS on the redirected connection.**

**L4.7 — The error/diagnostic string is untrusted; never machine-branch on it, sanitize before logging.**
*(source: libpq L1.2 + Configd §07 E6; mechanism)* Configd error bodies are "plaintext under a misleading
`application/json`," may echo attacker-influenced input, and are not escaped; `ERROR_CLOSE`/`WATCH_CANCELED`
messages may carry control/escape bytes.
→ **The client MUST branch only on `(code, carrier)`, MUST NOT `JSON.parse` an error body, and MUST sanitize
any message before logging/rendering** (log-injection / terminal-escape defense).

---

## 5. Checklists

### 5.1 What a correct, safe reference client MUST do

Framing & decode (both planes):
- [ ] Read the frame `Length`, enforce `10 ≤ Length ≤ 2 MiB` (`FRAME_TOO_LARGE`/`FRAME_CORRUPT`), require
      `Length == buffer`, verify **CRC32C before** interpreting version/type (§06 F3). *(L1.1, L4.1)*
- [ ] Bound **every inner length/count** (prefix count, NOTIFY/batch count, cursor count, value length,
      snapshot sizes) against remaining bytes **and a client-configured ceiling before allocating** — never
      trust "non-negative" as an upper bound (§06 F3-2/F5). *(L4.1)*
- [ ] Read constrained `u64` fields as `[0, 2^63)` (high-bit ⇒ `FRAME_CORRUPT`); read `val_len` as **signed
      i32** (`-1` = no value) — a `u32` read misparses the DELETE sentinel (§06 F5, §02 W5-6). *(L3.3)*
- [ ] Fail **closed** on unknown version/type/illegal-type-for-version/trailing bytes — map to the exact
      `ErrorCode`, never a silent downgrade or misparse (§06 F11, §00 OV7-3). *(L3.3, L1.6)*
- [ ] Pin the edge business version by the **first business frame** and stamp it on every business frame;
      stamp `0x04` on **exactly** the auth frames; never negotiate/downgrade (§06 F4). *(L1.6)*

Connection lifecycle & auth:
- [ ] Model the lifecycle as an explicit state machine with a timeout on every wait: connect, TLS-handshake,
      pre-auth, first-frame (§06 F10-1/F10-1d). *(L1.5, L4.4)*
- [ ] **Authenticate before any business frame**; never emit/accept a business frame or carry over any bytes
      read before the handshake / accepted `AUTH` (§03 AU4, §06 F10-1). *(L4.5)*
- [ ] Present the credential opaquely (mTLS cert / bearer / Basic), never parse a bearer token, never depend
      on how the server verifies it, never send a credential over plaintext (§03 AU2/AU3-4). *(libpq §1)*
- [ ] Edge token path: send **one** pre-auth `AUTH` (a reject ⇒ new connection, no hot-loop); `REFRESH_AUTH`
      renews the same identity; **proactively refresh before expiry** on a timer keyed to the credential
      lifetime; treat `CREDENTIAL_EXPIRED` (13) as re-auth/reconnect (§03 AU4-4..AU5-6). *(L2.6)*
- [ ] Treat `401`/`AUTH_FAIL` as (re)authenticate, `403`/`NOT_AUTHORIZED` as permanently-forbidden-do-not-
      retry-unchanged; do not hot-loop a `401` (§07 E4). *(libpq §1)*

Watch / cursor correctness:
- [ ] Be **vector-native and per-key-ordered from the first line of code, even at N=1** — one cursor-vector
      type shared by list + watch; never a scalar; never present cross-shard/global order (§02 W1-1/W3/W6-2).
      *(L2.1, the load-bearing rule)*
- [ ] Treat a zero/from-now cursor as "from now"; request current state **only** via `with_initial_snapshot`
      (§02 W3-4/W5-4a). *(L2.1)*
- [ ] Dedup by `(gid, S)` — drop a `WATCH_EVENT` iff `S ≤ cursor[gid]`; component-wise **max-merge** on
      resume/failover, never regress a component (§02 W4-2/W6-5). *(L2.4)*
- [ ] Advance the cursor component on `WATCH_PROGRESS` even with **zero** events (§02 W4-4). *(L2.2)*
- [ ] Gate application delivery on `WATCH_CREATED` (the "authorized and live" signal); never reuse a
      `watch_id`; tolerate in-flight frames after a `WATCH_CANCEL` until quiescence (§02 W2-8/W5-5/W5-8).
      *(L1.4, defensive)*
- [ ] Handle a **mid-stream** per-shard `WATCH_SNAPSHOT_*` resync without tearing down the whole watch
      (`GAP_UNRECOVERABLE` too-old case is self-healing per-shard) (§02 W6-3). *(L2.3)*
- [ ] On `GAP_UNRECOVERABLE`/`STALE_TOPOLOGY`: **re-list then re-create**, never re-send the stale cursor;
      recover from the client's own last-held vector (v1 `has_oldest=0`) (§02 W5-9a/W6-4, §07 E3-1). *(L2.3)*
- [ ] Use **one connection per independently-resumed watch** — do not re-`CREATE` N cursor-bearing watches on
      one connection (only watch #1 resumes; the rest silently start at "now") (§06 F10-1b). *(silent-loss
      footgun; defensive)*

Read loop, backpressure, liveness, errors:
- [ ] Run the read loop as a **frame demultiplexer** dispatching by `(type, carrier)` — expect `HEARTBEAT`,
      `WATCH_PROGRESS`, resync, revocation, and the **non-fatal** `DEMOTED_TO_CATCHUP` to interleave at any
      time (§07 E3-2). *(L1.3)*
- [ ] Send `CURSOR_ACK` periodically and **drain the socket promptly**; apply backpressure; never buffer the
      whole stream; handle `DEMOTED_TO_CATCHUP` (ingest snapshot, ack) rather than closing; back off the
      identity cooldown on `QUARANTINED` (§06 F10-3/F10-4). *(L4.2, L4.3)*
- [ ] Read-idle timeout keyed to `HEARTBEAT`; do not treat "no frames" as "no changes"; reconnect on silence
      (§02 W8, §06 F6-8). *(L2.5, L4.4)*
- [ ] Branch on **`(status | ErrorCode byte + carrier frame)`**, never the body/message; do not `JSON.parse`
      an error body; sanitize any message before logging (§07 E6). *(L1.2, L4.7)*
- [ ] Distinguish the **silent pre-handshake session-cap refusal** (retry/backoff) from frame-bearing rejects
      (§06 F10-2); implement the §07 retry classification (transient / indeterminate-idempotent-LWW /
      re-auth / stream-action / terminal). *(§07 E7)*

Routing & trust:
- [ ] Implement **leader-following + vector cursor even at N=1** (`X-Leader-Hint` follow + bounded backoff);
      resolve hints/`NodeId` **only** through the configured map (a trust boundary); fully verify TLS; never
      downgrade to plaintext (§05, §00 OV5-4/OV7). *(L4.6)*
- [ ] For indeterminate mutation outcomes (504 / mutation timeout / other mutation 5xx): retry-to-definite as
      idempotent LWW; **no read-modify-write** across the uncertainty (§07 E7, §04 D4-8). *(defensive)*

### 5.2 What the conformance suite MUST assert (and how, both directions)

Two suites, mirroring the gRPC split *(L3.1–L3.4)*:

**(I) Wire-format conformance (protobuf-conformance model — golden bytes + poison frames).**
- [ ] **Encode direction:** the client's encoder reproduces every `EdgeFrameGoldenBytes` fixture **byte-for-
      byte** (envelope, `0x01`–`0x09`, `0x0A`–`0x12`, `0x13`/`0x14` auth frames, nested blobs) (§00 OV5-5,
      §06). *How:* fixture-driven equality.
- [ ] **Decode/accept direction:** the client decodes each golden fixture to the correct structured frame.
- [ ] **Decode/reject direction (the high-value cases):** a **poison-frame corpus** — CRC mismatch, bad
      length (`> 2 MiB`, `!= buffer`, `< 10`), unknown version, unknown type, watch-type-off-`0x02`, auth-
      type-off-`0x04`, business-type-on-`0x04`, trailing bytes, `u64` high-bit set, negative inner counts —
      each asserts the **exact mapped `ErrorCode`** (`BAD_WIRE_VERSION`/`FRAME_CORRUPT`/`FRAME_TOO_LARGE`)
      (§06 F3/F11). *How:* one case per malformed input → expected code. *(L3.3, L4.1)*
- [ ] **Expected-results manifest** (protobuf `--failure_list` analog): a checked-in file of pass/known-fail;
      CI fails on an unexpected pass **or** fail (bidirectional drift). *(L3.2)*
- [ ] **Language-agnostic harness protocol:** the runner drives a thin testee adapter so the same corpus
      validates a future Rust/Go/Python client. *(L3.1)*

**(II) Behavioral / RFC-clause conformance (gRPC-interop model — one case per MUST, both directions).**
- [ ] **Client-conforms direction:** drive the reference client against a reference/mock server; assert
      client behavior per §00–§07 checklists — e.g. fresh zero-cursor watch delivers no history (W3-4);
      cursor advances on `WATCH_PROGRESS` (W4-4); `(gid,S)` dedup + max-merge across a reconnect (W6-5);
      mid-stream resync keeps siblings (W6-3); `DEMOTED_TO_CATCHUP` handled not closed (E3-2); `GAP`/`STALE`
      → re-list-not-resume (E3-1); one-connection-per-independent-resume (F10-1b); proactive `REFRESH_AUTH`
      (AU5-6); `403` not retried unchanged (E4). *(L2.*, L3.4)*
- [ ] **Server-obeys-RFC direction:** drive the **real server** with a conformance client emitting valid
      **and hostile** inputs; assert the server's mandated responses — e.g. over-broad / non-root
      `full_chain_verify` watch ⇒ `NOT_AUTHORIZED` with **zero preceding data frames, and explicitly zero
      `NOTIFY`** (§02 W7-5, the one test the contract mandates); `AUTH` before business, else
      `PROTOCOL_VIOLATION` (AU4-7); stray `AUTH` on an authenticated connection ⇒ `PROTOCOL_VIOLATION`
      (AU4-6); business frame stamped wrong version ⇒ `BAD_WIRE_VERSION` (F4); first-frame deadline reap
      (F10-1d); slow-acker demotion ladder (F10-3). *(L3.4, this is the "server proves the RFC" half)*
- [ ] **Auth-mode matrix:** run the auth-sensitive cases across all four modes (No-Auth / Basic / OIDC-bearer
      / mTLS) on both planes (§03 AU2-4).
- [ ] **Both-directions rule made explicit:** every codec case runs encode *and* decode; every behavioral
      MUST that has a client obligation *and* a server obligation is asserted from both ends. *(L3.4)*

---

## 6. Recommended shape for Configd (with the industry-standard call on each fork)

### 6.1 Reference client / SDK architecture

A layered client, keeping the **wire-correct core** strictly separate from convenience (the libpq non-goal
discipline, §1):

1. **Codec layer** — pure, allocation-bounded encode/decode of the EdgeFrame envelope + payloads + nested
   blobs + auth frames; the golden fixtures are its test oracle. No I/O. *(protobuf-conformance shows this
   layer is independently testable and is where the frozen contract lives — L3.1.)*
2. **Connection state machine** — one per TCP connection: `CONNECTING → TLS_HANDSHAKING → PRE_AUTH →
   AUTHENTICATED → PINNED/OPERATING → DRAINING → CLOSED`, every wait timeout-bounded; owns the pre-auth
   frame ceiling and first-frame deadline. **Call: explicit polled/evented state machine (libpq
   `PQconnectPoll` model), not a blocking connect** — it is what makes AU4/F10-1 testable and slow-loris-
   safe (L1.5, L4.4, L4.5).
3. **Read-loop demultiplexer** — dispatches inbound frames by `(type, carrier)`; expects async/interleaved
   `HEARTBEAT`/`WATCH_PROGRESS`/resync/revocation/`DEMOTED_TO_CATCHUP`. **Call: demultiplexer, not
   request-reply (libpq L1.3).**
4. **Watch/cursor engine** — the vector cursor (`{gid→S}` + `topologyEpoch`), the UNION merge, `(gid,S)`
   dedup, max-merge, per-shard resync, `WATCH_CREATED`-gated delivery, no `watch_id` reuse. **Call: model
   the cursor as a first-class vector type shared with `list`, even at N=1 (§02 W1-1, the etcd revision
   analog done right — L2.1).**
5. **Reconnect/resume + leader-follow module** — the highest-risk component; owns endpoint selection through
   the operator map, `X-Leader-Hint` follow, bounded backoff, quarantine-cooldown honoring, and
   persist-cursor/re-`CREATE`-with-fresh-ids. **Call: make it a named, fault-injection-tested module
   (etcd's balancer is the cautionary tale — L2.4); one connection per independently-resumed watch
   (F10-1b).**
6. **Auth/credential manager** — opaque-credential presentation across the four modes, proactive
   `REFRESH_AUTH` on a lifetime-keyed timer, `CREDENTIAL_EXPIRED` → reconnect. **Call: proactive
   renew-before-expiry (etcd KeepAlive — L2.6).**
7. **Error/retry classifier** — single table over `(HTTP status | ErrorCode + carrier)` → the §07 E7 retry
   class; branch on code only. **Call: one shared classifier for both planes (libpq SQLSTATE discipline —
   L1.2, §07 E5).**
8. **HTTP control-plane client** — thin: `get/put/delete`, header-version/body-seq cursor, `?scope=`,
   strong-read, leader-follow; shares the cursor-vector type and error classifier with the edge client.

Fork calls to flag for the lead:
- **Sync vs async I/O for the SDK core.** Recommend **NIO/event-loop (Netty) for the edge read-loop**
  (natural backpressure + demultiplex + Configd already runs Netty edge transports per the memory), with a
  **blocking façade** for the HTTP CRUD path and simple embedders. *(Backpressure = HTTP/2 flow-control
  lesson, L4.3.)* **Risk/decision for lead:** whether the public API is blocking, `CompletableFuture`, or
  reactive — a product call, not a wire call.
- **Ship the codec layer as a standalone artifact** so the future conformance harness and any third-language
  client can reuse the golden-fixture oracle. **(inference)**

### 6.2 Conformance-suite architecture

**Two runners, mirroring gRPC's deliberate split (L3.1–L3.4):**

- **Wire-format runner** (protobuf-conformance model): golden-bytes encode/decode + a **poison-frame corpus**
  for the reject direction, driven through a **thin language-agnostic testee adapter**, gated by a checked-in
  **expected-results manifest** (`--failure_list` analog; fails on unexpected pass *or* fail). This runner is
  the **frozen wire contract**. **Call: separate from behavioral; fixture+manifest driven (L3.2/L3.3).**
- **Behavioral runner** (gRPC-interop model): one runnable case per RFC **MUST**, exercised in **both
  directions** — a **client-conforms** profile (reference client vs mock/reference server) and a
  **server-obeys-RFC** profile (conformance client, incl. hostile inputs, vs the real server). Runs the
  auth-sensitive cases across the four auth modes on both planes. **Call: two directions are mandatory —
  the RFC is only "proven real" when the server is driven by an adversarial client *and* the client is
  driven against a conforming server (L3.4).**
- **CI wiring:** both runners in CI (the arc's Gate 5); the server-obeys-RFC profile must include the one
  test §02 W7-5 mandates (non-root `full_chain_verify` ⇒ zero `NOTIFY` leaked). **Call: the mandated
  negative tests are contract, not nice-to-have.**

### 6.3 The one divergence worth the lead's attention

Configd has **no version negotiation** (HTTP path prefix + edge first-frame pin + fail-closed), whereas
libpq/gRPC/etcd all negotiate or downgrade. This is a *deliberate, defensible* choice (§00 OV4-2), but it
means **the conformance suite must include explicit negative cases proving the client never attempts a
hello/downgrade and always fails closed** — because every mainstream reference client the divergence-analysts
will compare against *does* negotiate, and a well-meaning contributor will add a downgrade path. Flag this as
a standing review invariant, not a one-time test. *(L1.6)*
