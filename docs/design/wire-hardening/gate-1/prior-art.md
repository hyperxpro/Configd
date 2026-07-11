# Wire-Hardening Prior Art - Mature Binary Protocols vs. Configd Framing

Grounds the Configd wire-hardening effort in how four mature,
adversarially-tested binary protocols frame, version, bound, and fail closed:
**gRPC/HTTP-2**, **Apache Kafka**, **PostgreSQL FE/BE**, and **QUIC**. The goal is
*lessons*, not ports: each attack class below states what the mature protocols DO,
the concrete limit/mechanism (parameter name + typical default), the primary-source
citation, and the lesson for our two codecs.

Our current scheme (the thing being compared against):

- **Raft plane** - `configd-transport/.../FrameCodec.java`. Fixed 26-byte header
  `[len u32][ver u8][type u8][groupId u32][term u64][epoch u64 MBZ]` + payload +
  `[CRC32C u32]`. `WIRE_VERSION = 0x02`, **strict single-version tripwire**.
  `MAX_FRAME_SIZE = 16 MiB`. `RaftWireProtocol` adds a bounded TLS-handshake timeout
  and an inbound read timeout (`configd.raft.inboundReadTimeoutMs`).
- **Edge plane** - `configd-distribution-service/.../EdgeFrameCodec.java`. 6-byte
  header `[len u32][ver u8][type u8]` + payload + `[CRC32C u32]`. Accepts `0x01/0x02/0x03`
  with a **per-connection version pin**. `MAX_EDGE_FRAME_SIZE = 2 MiB`, plus payload
  sub-caps (`MAX_SNAPSHOT_CHUNK_BYTES = 1 MiB`, `MAX_NOTIFY_BATCH = 64`,
  `MAX_NOTIFY_BATCH_BYTES = 256 KiB`).

Both codecs already follow the two disciplines the mature protocols converged on:
**(a) bound the declared length before allocating** (`peekLength`), and **(b) verify the
integrity trailer before interpreting any field** (CRC-before-version/type). This
document confirms where that matches best practice and flags the four places prior art
says we are thin.

---

## Reference inventory (one line each)

| Protocol | Framing primitive | Size bound (param / default) | Version negotiation | Integrity |
|---|---|---|---|---|
| HTTP/2 (RFC 9113) | 9-byte header, 24-bit length | `SETTINGS_MAX_FRAME_SIZE` 16384, range 2^14..2^24-1 | connection preface + SETTINGS; ALPN `h2` | TLS (+ per-frame length) |
| gRPC-over-HTTP/2 | `[compressed u8][len u32-BE][msg]` | `grpc.max_receive_message_length` 4 MiB default | inherits HTTP/2 | TLS |
| Kafka | `[Size int32][request]` | `socket.request.max.bytes` 100 MiB; `message.max.bytes` ~1 MiB | `ApiVersions` (key 18): broker advertises min/max per API key | none on wire (CRC only inside record batch) |
| PostgreSQL FE/BE | `[type u8][len int32]` (len includes itself, not type) | 32-bit length; pre-auth `StartupMessage` has no type byte | `StartupMessage` protocol-version int32; `NegotiateProtocolVersion` | TLS via `SSLRequest` |
| QUIC (RFC 9000) | typed frames, varint lengths | varint max 2^62-1; `max_udp_payload_size`, `initial_max_data` | Version field + Version Negotiation packet | AEAD (crypto integrity) |

---

## Attack class -> what prior art does -> lesson for us

### 1. Unknown / downgrade version

**HTTP/2** - the connection is established under ALPN `h2`; unknown *frame types* are a
different axis (see §5). There is no per-frame version byte - version is a connection
property fixed at negotiation. **Kafka** - every request carries an `api_key` +
`api_version`; the broker advertises the `[min_version, max_version]` it supports per
key via the `ApiVersions` response, and rejects an out-of-range version with
`UNSUPPORTED_VERSION` (error code 35). Crucially, if the broker receives an `ApiVersions`
*request* at a version it does not understand, it replies **using v0** so the client can
still learn the supported range - a deliberate "always leave a negotiation door open"
pattern. **PostgreSQL** - the `StartupMessage` leads with a protocol-version int32; a
server that supports a lower minor replies `NegotiateProtocolVersion` rather than
hanging up. **QUIC** - the long-header Version field; an unsupported version triggers a
Version Negotiation packet listing what the server supports (RFC 9000 §6).

**Where we stand.** Raft `FrameCodec` is a **strict tripwire**: any byte != `0x02` ->
`UnsupportedWireVersionException`, connection dies. That is correct for a
homogeneous-fleet consensus plane (every node runs the same `WIRE_VERSION`; the header
comment says so). Edge `EdgeFrameCodec` accepts `{0x01,0x02,0x03}` and **pins per
connection** - a `0x02` frame on a `0x01`-negotiated connection is `BAD_WIRE_VERSION`.

**Lesson.** Our fail-closed posture is *stricter* than Kafka/PG/QUIC, which all
negotiate. That is the right trade for an internal fleet, but it means we have **no
graceful downgrade path** - the moment we need N-1/N rolling upgrade of the Raft wire,
the tripwire must become a Hello/handshake that accepts two adjacent versions (the
`FrameCodec` comment already anticipates this). The Kafka lesson to bank now:
**version negotiation should be explicit and its own message, and the negotiation
message itself must be the most conservative/oldest-parseable frame on the wire** so a
new client can always discover an old server's capabilities. Our edge `peekVersion`
already supports establishing the connection version from the first frame - the missing
piece is a symmetric server-advertises-supported-range step.

### 2. Oversized length - reject before allocate

Every mature protocol bounds the declared length **before** allocating and treats an
over-limit declaration as a connection-fatal event, not a recoverable one.

- **HTTP/2**: `SETTINGS_MAX_FRAME_SIZE`, initial **16384**, negotiable up to **2^24-1**;
  a frame whose 24-bit length exceeds the receiver's advertised value is
  `FRAME_SIZE_ERROR` (RFC 9113 §4.2, §6.5.2). The 24-bit field itself caps any single
  frame at 16 MiB structurally.
- **gRPC**: `grpc.max_receive_message_length`, default **4 MiB (4194304)**; a longer
  length-prefixed message is `RESOURCE_EXHAUSTED` and the read never allocates the full
  buffer.
- **Kafka**: `socket.request.max.bytes`, default **104857600 (100 MiB)**; a `Size`
  prefix beyond it causes the broker to close the connection before reading the body.
  `message.max.bytes` (~1 MiB) bounds an individual record batch.
- **QUIC**: lengths are varints capped at **2^62-1** (RFC 9000 §16), and flow-control
  transport parameters (`initial_max_data`, `initial_max_stream_data`) cap how much a
  peer may ever send before being granted more.

**Where we stand - MATCHES.** `FrameCodec.peekLength` / `EdgeFrameCodec.peekLength`
reject `length > MAX_*` on the first 4 bytes, before any `new byte[]`. `decode`
re-checks. The header comments explicitly call out "an adversary cannot induce a
multi-GiB allocation by lying in the length prefix." This is exactly the mature pattern.

**Lesson / gap.** Two nuances prior art surfaces: **(a)** our caps are **fixed
constants**, not negotiated (§8). HTTP/2 and QUIC let the *receiver* advertise a smaller
cap than the structural max - worth considering if we ever want a server to accept large
snapshot frames from trusted peers but a tiny cap from unauthenticated ones. **(b)**
Kafka separates a *transport* cap (`socket.request.max.bytes`, 100 MiB) from a *semantic*
cap (`message.max.bytes`, 1 MiB). We already mirror this on the edge (`MAX_EDGE_FRAME_SIZE`
2 MiB vs `MAX_SNAPSHOT_CHUNK_BYTES` 1 MiB) - good. The Raft plane has only the single
16 MiB frame cap; consider whether the *pre-auth/pre-handshake* Raft frame should have a
tighter cap than a post-handshake one (§7).

### 3. Truncated / mismatched length

**HTTP/2** frames are self-delimiting via the length header; a stream that ends
mid-frame is a connection error. **PostgreSQL**'s length "includes self," so the reader
knows exactly how many more bytes to await; a short read simply blocks for more, and a
declared length that runs past a sane bound is a fatal protocol error. **QUIC** frames
that claim more bytes than the packet contains are `FRAME_ENCODING_ERROR` (RFC 9000
§19/§12.4).

**Where we stand - MATCHES and exceeds.** `FrameCodec.decode` requires
`length == data.length` (rejects both over- and under-declaration), and `EdgeFrameCodec`
additionally rejects **trailing bytes after the typed payload** (`payload.remaining() != 0
-> FRAME_CORRUPT`) and enforces per-field minimum-length floors (e.g. the `CURSOR_MIN_BYTES
= 12` cursor prefix floor, the `readBytes`/`readString` "truncated reading length" guard).
Any `BufferUnderflowException` from a structurally-valid-but-short payload is mapped to
`FRAME_CORRUPT` rather than escaping. This is stronger than PG's "find the end without the
byte count" laxity.

**Lesson.** Keep the "consumed == declared, no trailing slack" invariant. It is the
single most effective guard against a smuggled second frame hidden in one frame's tail
(request-smuggling analogue). Ensure the streaming reader (the layer above the codec)
enforces the same: it must deliver **exactly** `peekLength` bytes to `decode`, never a
buffer that happens to contain frame + start-of-next.

### 4. Negative / overflow length

The canonical bug class: a length field read as a signed integer goes negative, or
`len + header` overflows 32 bits and wraps to a small allocation, then the copy runs off
the end.

- **PostgreSQL** historically hardened its startup path against absurd length values;
  the length is treated as a bound to validate, not to trust ("it also aids validity
  checking").
- **QUIC** sidesteps signedness entirely - varints are unsigned, max 2^62-1, and any
  value exceeding a semantic limit (e.g. a stream count > 2^60) is
  `FRAME_ENCODING_ERROR` (RFC 9000 §16, §4.6).
- **Kafka**'s `Size` is a signed int32; a negative or over-cap value closes the
  connection.

**Where we stand - MATCHES.** `FrameCodec.checkPayloadFitsFrame` uses **long arithmetic**
(`(long) payloadLength + HEADER_SIZE + TRAILER_SIZE`) precisely to avoid 32-bit overflow
near `Integer.MAX_VALUE`. `decode` rejects `length < minSize`. Every count/length read
in `EdgeFrameCodec` is checked `< 0` and against `p.remaining()`, and the array-count
multiplications use `(long) count * ELEMENT_BYTES` so the product cannot overflow before
the comparison (see `decodeWatchEvent`, `decodeCursor`, `decodeWatchCreated`). The signed
`-1` value is used *deliberately* as a null sentinel (signature absent, DELETE value) and
is disambiguated from "negative length" everywhere it appears.

**Lesson.** This is a place we are already at best practice **because** someone thought
about the `(long)` cast. The prior-art reinforcement: this is a **property-test
invariant worth fuzzing directly** - for every length/count field, feed `{-1, Integer.MIN_VALUE, 0,
MAX+1, values that overflow when multiplied by element size}` and assert `FRAME_CORRUPT`,
never an `OutOfMemoryError`/`NegativeArraySizeException`/`ArrayIndexOOB`. QUIC's move to
unsigned varints is a design lesson we can't retrofit cheaply, but the *effect* (no field
can be negative-then-trusted) is achievable with the disciplined `< 0` guards we have.

### 5. Type confusion (unknown / wrong-context type code)

Two sub-patterns diverge in prior art:

- **HTTP/2 ignores unknown frame *types***: "Implementations MUST discard frames that
  have unknown or unsupported types" (RFC 9113 §5.5) - extensibility over strictness,
  because the frame length lets you skip an unknown frame safely.
- **QUIC errors** on unknown frame types (`FRAME_ENCODING_ERROR`) - strictness, because
  a QUIC frame has no self-length to skip by.
- **PostgreSQL** dispatches on the 1-byte type and errors on an unexpected one for the
  current protocol state (e.g. a message that is illegal pre-auth).

**Where we stand - strict, and correct for us.** `MessageType.fromCode` /
`FrameType.fromCode` **throw on an unknown code** (-> `FRAME_CORRUPT`), and the edge codec
additionally enforces **context**: a `WATCH_*` type is legal *only* on a `0x02` frame,
rejected as `FRAME_CORRUPT` otherwise (`isWatchType` gate in both encode and decode). We
are QUIC-style (strict), not HTTP/2-style (skip), which is right because our frames,
though self-lengthed, carry no "skip me, I'm an extension" semantics and an unknown type
almost certainly means corruption or a hostile/mismatched peer.

**Lesson.** The context gate (type-legal-under-this-version) is the subtle, valuable part
- prior art shows type confusion is rarely "unknown code" and usually "known code in the
wrong state." Extend the same discipline to the Raft plane if any `MessageType` is only
legal in a specific role/phase (e.g. a snapshot-transfer type should not be honored
outside a snapshot exchange).

### 6. Decompression / array-count bomb

The highest-severity recent class. **HPACK** (HTTP/2 header compression) is a
decompression bomb vector: a small compressed header block expands to huge decompressed
size. Defenses: `SETTINGS_HEADER_TABLE_SIZE` (default **4096**, bounds the dynamic
table) and the advisory `SETTINGS_MAX_HEADER_LIST_SIZE` (bounds *decompressed*
header-list size) (RFC 9113 §6.5.2). The **CONTINUATION flood** (CERT VU#421644;
CVE-2024-27316 Apache httpd, CVE-2024-27919 + CVE-2024-30255 Envoy, CVE-2024-28182
nghttp2, CVE-2023-45288 Go, CVE-2024-27983 Node, CVE-2024-31309 ATS) showed the deeper
lesson: it is not enough to bound the *final* header list - you must bound the
**number and cumulative size of frames consumed while assembling it**, *before*
END_HEADERS, or an attacker streams unbounded CONTINUATION frames and exhausts
CPU/memory even though no single frame or final block violates a limit.

The array-count analogue (no compression needed): a small frame declares a huge element
count, and the decoder does `new ArrayList<>(count)` or a `count`-iteration loop that
each allocates - amplification without compression.

**Where we stand - MATCHES on array counts.** Every repeated structure in
`EdgeFrameCodec` bounds its count against remaining bytes **before** the allocation loop:
`decodeNotify` (`count > MAX_NOTIFY_BATCH`), `decodeSubscribe` (`prefixCount >
p.remaining()`), `decodeWatchEvent` (`(long) count * MIN_CHANGE_BYTES > p.remaining()`),
`decodeCursor`, `decodeWatchCreated`. Because a count can never exceed the
already-bounded frame size divided by the minimum element size, there is no amplification.
`MAX_NOTIFY_BATCH_BYTES` additionally caps the cumulative encoded size.

**Where we stand - GAP to note.** We currently ship **no wire-level compression** on
either plane, so we have no HPACK-class bomb *today*. Two forward-looking lessons: **(a)**
If encryption-at-rest / snapshot payloads ever carry a *compressed* blob that the codec
or a downstream layer inflates, the CONTINUATION lesson applies: bound the *inflated*
size and the *inflation ratio*, and stream-inflate with a hard output cap rather than
`inflate-then-check`. **(b)** Our per-element bound is "count x min-element-size <=
remaining," which is correct, but the CONTINUATION flood teaches that when a structure is
assembled across *multiple frames* (our multi-chunk snapshots: `SNAPSHOT_BEGIN` declares
`chunkCount`/`totalBytes`, then N `SNAPSHOT_CHUNK` frames arrive), the **assembler above
the codec** must enforce a cumulative cap and a max-chunk-count, not just per-frame caps.
This needs verifying: that a hostile `SNAPSHOT_BEGIN` claiming a huge `chunkCount`/
`totalBytes`, or an endless stream of `SNAPSHOT_CHUNK`s that never reaches `SNAPSHOT_END`,
is bounded by the reassembly layer - this is the exact shape of the CONTINUATION flood.

### 7. Pre-auth resource exhaustion

Mature protocols treat the **pre-authentication / pre-handshake** window as the most
hostile and give it the tightest budget.

- **QUIC** anti-amplification: a server MUST NOT send more than **3x** the bytes it has
  received from an unvalidated address (RFC 9000 §8.1), and every Initial-carrying
  datagram MUST be **>= 1200 bytes** (§14.1) - the small-request/large-response
  amplification defense. Pre-handshake, a server commits minimal state.
- **PostgreSQL** handles the `StartupMessage` / `SSLRequest` **before** authentication
  with deliberately narrow parsing, and the pre-auth message has *no type byte* and a
  bounded, well-known shape.
- **Kafka** applies `socket.request.max.bytes` from the very first byte, before any
  SASL/auth, so an unauthenticated client cannot post a 100 MB+ buffer.

**Where we stand - PARTIAL / GAP.** The Raft plane has a **bounded TLS-handshake timeout**
(`RaftWireProtocol`, "a peer that completes the TCP connect but stalls mid-handshake
parks the connector indefinitely") and a bounded inbound read timeout - good, that is the
slow-handshake defense. But two prior-art defenses are **not obviously present**:
1. **A per-connection pre-auth byte cap** distinct from the 16 MiB / 2 MiB frame cap. An
   unauthenticated peer can, today, cause us to buffer up to one full max-size frame
   (16 MiB Raft / 2 MiB edge) before mTLS/authz completes. Kafka/QUIC would cap the
   pre-auth budget far tighter. Consider a small pre-handshake frame ceiling (e.g. the
   handshake/Hello frame only, a few KiB) that widens to the full cap **only after** the
   peer authenticates.
2. **Amplification discipline** - QUIC's 3x rule. Our transports are TCP+TLS (TCP's
   handshake already blunts blind spoofing), so this is lower priority than for QUIC's
   UDP, but the *principle* - do not allocate large per-connection state for an
   unauthenticated peer, and cap concurrent half-open/unauthenticated connections -
   is worth an explicit check.

**Lesson (one of the two most important).** Add an explicit **pre-auth resource budget**:
(i) cap the number of concurrent unauthenticated connections; (ii) give the
pre-handshake frame a much smaller size cap than the post-handshake data cap; (iii) keep
the existing handshake/read timeouts. This is the single clearest place prior art says we
are thinner than the bar.

### 8. Slow-loris / partial-frame

**HTTP/2** mitigates via `SETTINGS_MAX_CONCURRENT_STREAMS`, keepalive PING, and idle
timeouts; a peer that opens streams and dribbles is bounded by stream limits and by the
CONTINUATION-flood fixes (frame-count limits during header assembly). **Kafka** and
**PostgreSQL** rely on socket read timeouts. **QUIC** has idle-timeout transport
parameters and bounds unacknowledged data via flow control.

**Where we stand - PARTIAL.** Raft has `inboundReadTimeoutMs` + handshake timeout, which
is the core slow-loris defense: a peer that sends a length prefix and then dribbles the
body is killed when the read stalls. **Confirm the edge plane has an equivalent** - the
grep for `IdleStateHandler`/`ReadTimeout` in `configd-distribution-service` returned no
handler (only authorization comments), so the edge streaming path's slow-loris posture
needs closing (see G-2 in `threat-model.md`). A partial-frame that never completes must
be bounded by *both* a read/idle timeout *and* a max-buffered-incomplete-frame cap.

**Lesson.** Pair the size cap (bounds a *complete* hostile frame) with an **idle/read
timeout** (bounds an *incomplete* dribbled frame) - you need both; a size cap alone does
nothing against a peer that sends 1 byte/minute. Verify the edge path has the timeout the
Raft path already has.

### 9. Checksum / integrity

**QUIC** provides cryptographic AEAD integrity on every packet - the strongest posture;
corruption or tampering fails authentication. **HTTP/2, gRPC, PG, Kafka-on-the-wire**
rely on **TLS** for integrity and do *not* add a redundant application-layer checksum
(Kafka has a CRC *inside* the persisted record batch, for storage integrity, not wire
integrity).

**Where we stand - exceeds the transport-security baseline, deliberately.** Both codecs
append a **CRC32C (Castagnoli)** trailer and **verify it before interpreting version/type/
payload**. The documented rationale is defense-in-depth *inside* a TLS session - against
bit-flips and bug-induced corruption - and, importantly, a **diagnostic** one: a flipped
version or type byte surfaces as `FRAME_CORRUPT` (a hardware/bug signal) rather than a
misleading `BAD_WIRE_VERSION`/`unknown type` (a config/deployment signal), so operators
are pointed at the right root cause. The CRC-before-interpret ordering is the same
principle as verifying a MAC before parsing plaintext.

**Lesson.** The CRC is *not* a security control (it is not keyed; an active on-path
attacker inside TLS could recompute it) - TLS/mTLS is the integrity-and-authenticity
boundary, and the code comments correctly say so. Keep the CRC for corruption detection
and diagnostics, but **do not let it drift into being treated as a tamper defense** in
docs or reviews. The one prior-art caution: CRC-before-parse is right; just ensure the
CRC covers **every** interpreted byte including the length prefix (it does -
`crc.update(data, 0, length - TRAILER_SIZE)`), so a length-prefix flip is also caught.

---

## Scorecard - where we match, where prior art says we are thin

**Already at or above best practice (keep, and lock in with tests):**

1. **Length-bound-before-allocate** (`peekLength`) - matches HTTP/2/gRPC/Kafka/QUIC exactly.
2. **Integrity-trailer-before-interpret** (CRC before version/type) - stronger diagnostic
   posture than the TLS-only protocols; ordering is correct.
3. **Negative/overflow-safe length math** (`(long)` casts, `< 0` guards, `count x min-elem
   <= remaining`) - the QUIC-effect (no field is negative-then-trusted) achieved in Java.
4. **Explicit array-count bounds** on every repeated structure - the array-count-bomb
   defense HPACK/CONTINUATION taught, done right.
5. **Strict type dispatch + context gate** (`fromCode` throws; `WATCH_*` only under `0x02`)
   - QUIC-style strictness plus the "known type in wrong state" guard prior art shows is
   the real type-confusion vector.
6. **No-trailing-bytes / consumed==declared** invariant - smuggling defense stronger than PG.
7. **Fixed frame caps split transport vs. semantic** on the edge (2 MiB frame vs 1 MiB
   chunk / 64-notification batch) - the Kafka `socket.request.max.bytes` vs
   `message.max.bytes` split.

**Thin vs. prior art (candidate hardening work):**

- **A. Per-connection pre-auth byte cap (highest).** Kafka/QUIC give the unauthenticated
  window a tight, separate budget; we let an unauthenticated peer buffer a full 16 MiB
  (Raft) / 2 MiB (edge) frame before authz. Add a small pre-handshake frame ceiling +
  a cap on concurrent unauthenticated connections.
- **B. Edge-plane slow-loris timeout (high).** Raft has `inboundReadTimeoutMs`; the edge
  streaming path has no visible idle/read timeout. A size cap does not stop a dribbled
  partial frame - verify/add the timeout.
- **C. Multi-frame reassembly caps (high).** Per-frame caps are solid, but the
  CONTINUATION-flood lesson is that structures assembled *across* frames (multi-chunk
  snapshots) need a cumulative-size + max-chunk-count + must-terminate bound at the
  reassembly layer, not just per-chunk caps. Verify the snapshot assembler enforces this.
- **D. Negotiated vs. fixed max frame size, and an explicit version-negotiation message
  (medium).** Our caps and versions are fixed constants and our version handling is a
  strict tripwire (Raft) / fixed accept-set (edge). Right for a homogeneous fleet today,
  but Kafka's `ApiVersions` "advertise supported range, and make the negotiation frame the
  most conservative parseable one" is the pattern to adopt *before* the first rolling
  wire-version upgrade - otherwise the tripwire forces a flag-day.

---

## Primary sources

- **HTTP/2** - RFC 9113: §4.1 (frame header, 24-bit length), §4.2 (frame size,
  `FRAME_SIZE_ERROR`), §5.5 (unknown frame types discarded), §6.5.2 (`SETTINGS_MAX_FRAME_SIZE`
  16384/2^24-1, `SETTINGS_HEADER_TABLE_SIZE` 4096, `SETTINGS_MAX_HEADER_LIST_SIZE`), §5.2
  (flow control, `SETTINGS_INITIAL_WINDOW_SIZE` 65535). https://www.rfc-editor.org/rfc/rfc9113.html
- **gRPC** - Length-Prefixed-Message: `[Compressed-Flag u8][Message-Length u32-BE][Message]`.
  https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-HTTP2.md ; default
  `grpc.max_receive_message_length` = 4 MiB (runtime default, grpc/grpc).
- **Kafka** - request framing (`Size` int32 prefix) and `ApiVersions` negotiation:
  https://kafka.apache.org/protocol.html ; broker configs `socket.request.max.bytes`
  (default 104857600 = 100 MiB), `message.max.bytes` (~1 MiB), `UNSUPPORTED_VERSION`
  error (code 35): https://kafka.apache.org/documentation/#brokerconfigs
- **PostgreSQL** - FE/BE message format (`[type u8][len int32 incl. self]`), `StartupMessage`
  (no type byte, leads with length + protocol-version int32), `NegotiateProtocolVersion`,
  `SSLRequest`: https://www.postgresql.org/docs/current/protocol-message-formats.html and
  https://www.postgresql.org/docs/current/protocol-flow.html
- **QUIC** - RFC 9000: §6 (version negotiation), §8.1 (3x anti-amplification), §14.1
  (1200-byte Initial minimum), §16 (variable-length integers, max 2^62-1), §18.2
  (transport parameters), §19/§12.4 (frame types, `FRAME_ENCODING_ERROR`).
  https://www.rfc-editor.org/rfc/rfc9000.html
- **CONTINUATION flood** - CERT/CC VU#421644: CVE-2023-45288 (Go), CVE-2024-27316 (Apache
  httpd), CVE-2024-27919 + CVE-2024-30255 (Envoy), CVE-2024-28182 (nghttp2),
  CVE-2024-27983 (Node.js), CVE-2024-31309 (Apache Traffic Server), CVE-2024-2653 (amphp),
  CVE-2024-2758 (Tempesta). https://kb.cert.org/vuls/id/421644

_Note: Kafka broker-config default byte values (100 MiB / ~1 MiB) are the well-established
defaults from the config reference; the live docs fetch during authoring returned a
navigation redirect, so confirm against the running broker's `kafka-configs` if an exact
byte value becomes load-bearing._
