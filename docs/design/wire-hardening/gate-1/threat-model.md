# Gate 1 — Hostile-Sender Threat Model (Configd Wire Protocol)

Status: audit deliverable (model + matrix). No source modified. Findings drive Gate 2.
Scope: this repository only. Author: redteam-auditor.

This document models a hostile peer/client that can put **arbitrary bytes** on an
established connection and asks, for every attack class against a binary frame parser:
*where does attacker data reach a dangerous sink, and what property stops it?*

The companion `attack-matrix.md` is the per-frame × attack-class status table this
narrative summarizes.

---

## 1. Attack surface & trust boundaries

There are **two independent wire planes**, each with its own framing codec, version byte,
golden-fixture gate, and read path. They must be modeled separately — they share
structural discipline but not code, caps, or trust model.

### 1.1 Raft consensus plane (node ↔ node)

- **Envelope codec:** `FrameCodec` (`configd-transport/.../FrameCodec.java`).
  Layout: `[len u32][ver u8][type u8][groupId u32][term u64][epoch u64 MBZ][payload][crc32c u32]`,
  `HEADER_SIZE=26`, `TRAILER_SIZE=4`, `MAX_FRAME_SIZE=16 MiB`, `WIRE_VERSION=0x02`.
- **Wire prefix:** each frame is preceded by a 4-byte `senderId` (`RaftWireProtocol`,
  `SENDER_ID_SIZE=4`).
- **Payload codec:** `RaftMessageCodec` (`configd-server/.../RaftMessageCodec.java`) —
  per-`MessageType` decode.
- **Read paths (two, byte-identical discipline):**
  - JDK: `TcpRaftTransport.handleInboundConnection` (`:366-454`) — one virtual thread per
    accepted socket, `DataInputStream.readInt/readFully`.
  - Netty: `RaftFrameDecoder` (`configd-netty/.../RaftFrameDecoder.java`) —
    `ByteToMessageDecoder`.
- **Trust boundary:** mTLS with `setNeedClientAuth(true)` (TLSv1.3-only). A peer must
  present a cert that chains to the cluster trust anchor. **Caveat (finding G-3):** the
  post-handshake code does **not** bind the 4-byte `senderId` to the client-cert identity,
  so any valid cluster member can spoof another member's `senderId`.

### 1.2 Edge fan-out plane (server → subscribers, plus small client→server control frames)

- **Envelope codec:** `EdgeFrameCodec` (`configd-distribution-service/.../EdgeFrameCodec.java`).
  Layout: `[len u32][ver u8][type u8][payload][crc32c u32]`, `HEADER_SIZE=6`,
  `MAX_EDGE_FRAME_SIZE=2 MiB`, `MAX_SNAPSHOT_CHUNK_BYTES=1 MiB`. Versions `0x01`/`0x02`
  (watch)/`0x03` (filtered).
- **Payload types:** `FrameType` 0x01–0x12; nested `CommandCodec` (mutation blobs inside
  NOTIFY deltas) and `EdgeSnapshotCodec` (snapshot body inside the SNAPSHOT_CHUNK stream).
- **Read paths (two):**
  - JDK: `FanOutServer.readerLoop`/`readFrame` (`:492-556`).
  - Netty: `ByteToEdgeFrameDecoder` (`configd-server/.../fanout/ByteToEdgeFrameDecoder.java`)
    → `NettyFanOutServer.FanOutConnection.channelRead0`.
- **Inbound frames the server actually accepts** are tiny control frames (SUBSCRIBE,
  CURSOR_ACK, WATCH_CREATE, WATCH_CANCEL). All SNAPSHOT_*/NOTIFY/WATCH_* server→client
  types, if sent inbound, decode structurally but are rejected as `PROTOCOL_VIOLATION` by
  the driver (`FanOutConnectionDriver.routeLegacy/routeWatch`, `:498-516`).
- **Trust boundary:** mTLS required in production; edge identity = verified client-cert
  Subject DN (`resolveCertIdentity`). Authorization gate (`WatchAuthorizer`) fails closed
  for watches when unwired.

### 1.3 Buffering before dispatch (the pre-allocation question)

Both planes are **length-prefixed with bounds-before-allocation**: the declared length is
range-checked (`isValidFrameLength` / `peekLength`) *before* any per-frame buffer is
allocated and *before* the reader waits for the rest of the frame. Consequently the
maximum in-flight buffer per connection is one frame (16 MiB Raft / 2 MiB edge), never an
attacker-chosen multi-GiB allocation. Aggregate exposure is bounded by the connection cap
(§ class 9).

---

## 2. Attack-class catalog

Each class: **definition** · **mechanism against THIS protocol** · **defending property**
· **status**. Status is one of DEFENDED (both planes hold), PARTIAL/GAP (a concrete
weakness), or N/A.

### Class 1 — Unknown / downgrade / future version
- *Def:* peer stamps a version the reader does not implement, hoping for misparse or a
  silent downgrade.
- *Mechanism:* set `ver` byte ≠ known. Raft: any ≠ `0x02`. Edge: any ∉ {0x01,0x02,0x03},
  or a `0x02` watch type on a `0x01`/`0x03` frame (downgrade smuggling).
- *Defense:* strict allowlist. `FrameCodec.decode:305-308` throws
  `UnsupportedWireVersionException`; `EdgeFrameCodec.decode:621-629` → `BAD_WIRE_VERSION`;
  per-connection version **pin** (`ByteToEdgeFrameDecoder:62-69`, `decode(data,ver):633-637`)
  rejects a mid-connection version switch; watch-type-vs-version cross-check
  (`EdgeFrameCodec:649-653`). No negotiation, no "highest common" downgrade.
- *Status:* **DEFENDED.**

### Class 2 — Oversized declared length
- *Def:* claim a huge frame to force a huge allocation.
- *Mechanism:* set `len` to `0x7fffffff`.
- *Defense:* `FrameCodec.decode:273-277` and `peekLength:355-360` bound to
  `[HEADER+TRAILER, MAX_FRAME_SIZE]`; `RaftWireProtocol.isValidFrameLength:123-126` gates
  both raft readers *before* `new byte[frameLength]` (`TcpRaftTransport:378`,
  `RaftFrameDecoder:45`). Edge: `EdgeFrameCodec.peekLength:1103-1108` +
  `decode:594-599`, gating both edge readers before allocation.
- *Status:* **DEFENDED.**

### Class 3 — Truncated frame
- *Def:* send fewer bytes than the header/fields require, hoping for an OOB read or partial
  parse.
- *Mechanism:* short array; or a length that promises fields the payload does not contain.
- *Defense:* min-size gate (`FrameCodec.decode:266-269`, `EdgeFrameCodec.decode:588-591`);
  every payload field is preceded by a `checkRemaining` (`RaftMessageCodec.checkRemaining:123-129`,
  used at every decode) or a `p.remaining()<n` guard (`EdgeFrameCodec.readBytes/readString:1052-1078`,
  cursor floor `:871-873`). `ByteBuffer` underflow that slips through is caught and mapped to
  `FRAME_CORRUPT` (`EdgeFrameCodec.decode:666-670`). The streaming decoders wait for the full
  declared length before decoding, so a mid-frame stall is a starvation issue (class 10), not a
  truncation misparse.
- *Status:* **DEFENDED.**

### Class 4 — Length / content mismatch
- *Def:* header length disagrees with the actual byte count (a smuggling/desync primitive).
- *Defense:* `FrameCodec.decode:278-282` (`length != data.length` → reject);
  `EdgeFrameCodec.decode:600-603`; strict **no-trailing-bytes** after payload
  (`EdgeFrameCodec.decode:659-662`; `decodeCoalescedHeartbeat:340-344`). A single reader
  owns framing on each connection, so there is no proxy↔app parser differential.
- *Status:* **DEFENDED.**

### Class 5 — Integer overflow / underflow / negative length
- *Def:* a signed length read as negative, or `count × record` overflowing to a small
  positive, driving a bad allocation or negative-size array.
- *Mechanism:* `dataLen=-1`, `numEntries=0x40000000`, `configLen<0`, `prefixCount<0`, etc.
- *Defense:* every length/count is explicitly `<0`-checked and every multiply is done in
  `long` before comparison: `RaftMessageCodec.checkBlobLen:131-145`,
  `checkInstallSnapshotFitsFrame:147-172`, `numEntries<0 :391-394`,
  `(long)n*record>remaining :404-409` / `:319-323`; `CommandCodec` value `:242-245`,
  batch count `:265-268`. Edge: `readBytes/readString:1057/1071`, `decodeNotify:755`,
  `decodeCursor:880`, `decodeWatchEvent:940` (`(long)count*MIN_CHANGE_BYTES`),
  `decodeWatchCreated:914`. Negative `configLen` and `nextExpectedOffset` explicitly
  rejected rather than coerced (`RaftMessageCodec:518-523, 569-572`).
- *Status:* **DEFENDED.** (This is the most thoroughly covered class in the codebase.)

### Class 6 — Type confusion / cross-type smuggling / wrong-direction frames
- *Def:* a valid type byte routed to the wrong decoder, or a server→client frame injected
  inbound.
- *Mechanism:* send `RAFT_COALESCED_HEARTBEAT`/`RAFT_WITNESS` through the scalar `decode()`;
  send a `WATCH_EVENT`/`SNAPSHOT_CHUNK` to the server; send an undefined type byte.
- *Defense:* directional guards throw loudly (`RaftMessageCodec.decode:226-232`,
  `decodeCoalescedHeartbeat` type-guard `:302-306`, `decodeWitness:632-635`); undefined type
  byte → `MessageType.fromCode:66-71` / `FrameType.fromCode:64-71`; wrong-direction edge
  frames → `PROTOCOL_VIOLATION` teardown (`FanOutConnectionDriver:498-516`). Dormant Raft
  gossip types (PLUMTREE_*/HYPARVIEW_*/HEARTBEAT) have no `RaftMessageCodec` case → the
  `default` throw at `:233-234` (a handler-level throw: logged, non-desync — see class 12
  note on log-flood).
- *Status:* **DEFENDED** (routing) with a minor **log-flood** sub-note (class 12/G-4).

### Class 7 — Field injection (CRLF / header / log / identity)
- *Def:* inject control content into a downstream sink via a parsed field.
- *Mechanism:* binary framing has no header-delimiter to inject into (no CRLF surface). The
  realistic injection surface is **identity**: the `senderId` prefix (Raft) and wire
  `edgeId` (edge) are attacker-controlled.
- *Defense:* `edgeId` is explicitly **advisory** — the enforced identity is the verified
  client-cert Subject DN (`bindIdentity`, `resolveCertIdentity`). Raft `senderId` is **not**
  bound to the cert (finding **G-3**). Decoded strings are UTF-8 byte arrays, not eval'd.
- *Status:* **PARTIAL** — see **G-3** (sender-id spoofing).

### Class 8 — Nesting / recursion / array-count abuse
- *Def:* a tiny frame declares a huge element count to force a large `ArrayList`/`Map`
  allocation or a long loop (amplification).
- *Mechanism:* `numEntries=2^31`, coalesced `n=2^31`, NOTIFY `count`, WATCH change/shard
  count, `prefixCount`, cursor component count, `CommandCodec` batch count.
- *Defense:* hard element caps **and** "declared count can't fit remaining bytes" pre-checks
  before allocation: `MAX_ENTRIES_PER_APPEND=10_000` (`:395-409`),
  `MAX_COALESCED_GROUPS=1024` (`:313-323`), `MAX_NOTIFY_BATCH=64` (`decodeNotify:755`),
  `MAX_BATCH_COUNT=10_000` (`CommandCodec:265`), cursor/shard/change counts bounded by
  `count×recordSize ≤ remaining` (`EdgeFrameCodec:880,914,940`). No recursion in either codec
  (flat TLV; `CommandCodec` batch is one level deep and cannot nest a batch).
- *Status:* **DEFENDED.**

### Class 9 — Pre-auth resource exhaustion (connection flood)
- *Def:* open many connections to exhaust FDs / threads / memory.
- *Defense:* accepted-connection cap applied **before** the reader/handshake:
  Raft `maxInboundConnections=1024` (`TcpRaftTransport:330-334`); edge `maxSessions=1024`,
  counted in `channelActive` *before* the TLS handshake (`NettyFanOutServer:376-381`;
  `FanOutServer:301`). Per-frame allocation bounded (class 2), so aggregate memory ≈
  cap × max-frame.
- *Status:* **DEFENDED**, with a hardening note: worst-case aggregate buffer is
  `1024 × 16 MiB ≈ 16 GiB` (Raft) / `1024 × 2 MiB ≈ 2 GiB` (edge) if every peer parks a
  max-size frame. Bounded, but large; a smaller cap or aggregate ceiling is defense-in-depth
  (**G-5**, Low).

### Class 10 — Slow-loris / partial-frame starvation
- *Def:* complete admission, then send bytes arbitrarily slowly (or stop mid-frame), pinning
  a reader thread / FD / session slot indefinitely.
- *Mechanism:* send the 4-byte length promising a large frame, then dribble/stop; or
  complete mTLS then never send SUBSCRIBE.
- *Defense — Raft plane:* an inbound read-idle deadline (`inboundReadTimeoutMs`, default 15 s)
  is set on every accepted socket (`TcpRaftTransport:339`) and a bounded handshake timeout
  (`:508`); a stalled peer trips `SocketTimeoutException` and the connection drops
  (`:437-444`). **DEFENDED.**
- *Defense — Edge plane:* **MISSING.** After the TLS handshake the JDK server executes
  `ssl.setSoTimeout(0)` (`FanOutServer:330`) so `readInt`/`readFully` block forever; the
  Netty pipeline installs **no** `IdleStateHandler`/`ReadTimeoutHandler`
  (`NettyFanOutServer.initChannel:267-281` — only `SslHandler`, decoder, encoder, handler).
  A client that completes mTLS and then stalls holds a session slot + FD + virtual thread
  until the OS reaps the TCP connection. `maxSessions` bounds it to 1024 slots, but 1024
  slow/idle authenticated (or, in plaintext test mode, unauthenticated) connections deny
  service to legitimate subscribers.
- *Status:* **GAP — finding G-2** (edge plane lacks the read-idle deadline the Raft plane
  enforces). This is the highest-severity *net-new* gap because the Raft plane already
  demonstrates the fix.

### Class 11 — Checksum / MAC bypass or absence
- *Def:* forge or corrupt payload past an integrity check.
- *Mechanism:* the trailer is **CRC32C, not a MAC** — it is not keyed and provides no
  cryptographic integrity. CRC is trivially recomputable by any sender.
- *Defense:* CRC32C is validated **before** any field is interpreted
  (`FrameCodec.decode:290-301`, `EdgeFrameCodec.decode:606-615`) — its job is detecting
  bit-flips/bug-corruption and forcing a "corruption" diagnosis rather than a misleading
  "bad version". **Cryptographic integrity/authenticity is provided by the TLS session**
  (TLSv1.3, mandatory in production). An off-path attacker cannot inject frames without the
  TLS keys; an on-path attacker is stopped by TLS AEAD, not CRC. The CRC is correctly scoped
  as defense-in-depth, not a security boundary.
- *Status:* **DEFENDED by design** (CRC is not, and does not claim to be, the auth control;
  the caveat is only that all authenticity rests on TLS + the class-7 identity binding).

### Class 12 — Protocol-specific classes revealed by the code

- **Chunked-snapshot offset abuse (Raft INSTALL_SNAPSHOT).** A hostile leader could send
  out-of-order/overlapping/never-ending chunks to corrupt or OOM the follower's reassembly.
  *Defense:* contiguous-prefix-only acceptance (`RaftNode:2918-2956` — reject any gap or
  duplicate, splice never occurs), cross-snapshot `(index,term)` guard (`:2912-2916`),
  fail-closed total heap cap `maxReassembledSnapshotBytes` (default 512 MiB, clamped;
  `:2932-2944`). **DEFENDED.**
- **Coalesced-heartbeat sub-frame abuse.** Duplicate group ids / trailing padding / lying
  group count. *Defense:* `putIfAbsent` duplicate reject (`:335-338`), strict trailing-byte
  reject (`:340-344`), count cap + fits-remaining (`:313-323`). **DEFENDED.**
- **Reserved `epoch` misuse (Raft).** The 8-byte epoch is MBZ; a non-zero value that passed
  CRC is a future peer this reader must not guess at. *Defense:* fail-closed reject
  (`FrameCodec.decode:314-323`). **DEFENDED.**
- **Cursor / vector abuse (edge watch).** Unsorted/duplicate gid, negative S, reserved epoch
  0, huge component count. *Defense:* `decodeCursor` floor + epoch-0 reject + count bound +
  `WatchCursor` constructor invariant (`EdgeFrameCodec:869-892`). **DEFENDED.**
- **Sender-id spoofing (Raft identity).** See **G-3** below.
- **Unused/dormant type log-flood.** A peer streaming valid-but-undecodable types
  (HEARTBEAT/PLUMTREE_*/HYPARVIEW_*) causes a `System.err` line per frame
  (`RaftMessageCodec:233-234` → handler throw → `TcpRaftTransport:425-431` prints stack).
  Non-desync, bounded by the read path, but an unbounded `printStackTrace` per hostile frame
  is a log-amplification / disk-fill nuisance. **G-4, Low.**

---

## 3. Findings ranked by severity

| ID | Sev | Class | Finding | Where |
|----|-----|-------|---------|-------|
| **G-2** | **Medium** | 10 | **Edge plane has no inbound read-idle deadline.** Post-handshake, JDK does `setSoTimeout(0)` and the Netty pipeline has no `IdleStateHandler`; a client that completes mTLS then stalls pins a session slot/FD/thread until OS reap. The Raft plane already enforces `inboundReadTimeoutMs` — the fix pattern exists in-repo. Bounded to `maxSessions=1024` but denies service to real subscribers. | `FanOutServer.java:330`; `NettyFanOutServer.java:267-281` (no idle handler) |
| **G-3** | **Medium** | 6/7 | **Raft `senderId` not bound to TLS identity.** mTLS proves "a valid cluster member"; the 4-byte `senderId` prefix (used to key witness anti-rollback tables and vote routing) is unauthenticated attacker bytes. Any member can impersonate another's `senderId`, forging witness attestations that Gate 3c relies on. Partially by-design (Raft is crash-, not Byzantine-, tolerant), but the code comments call the prefix "authenticated" — an overstatement. Bind `senderId → client-cert identity` (or per-node certs + SAN check) as defense-in-depth. | `TcpRaftTransport.java:371-372`; `RaftFrameDecoder.java:43,61`; comment `RaftMessageCodec.java:110-116` |
| **G-4** | **Low** | 12 | **Undecodable-type log-flood.** Valid-but-unhandled Raft types print a per-frame stack trace; a hostile peer can amplify logs / fill disk. Rate-limit or drop-with-counter instead of `printStackTrace`. | `TcpRaftTransport.java:425-431`; `RaftMessageCodec.java:233-234` |
| **G-5** | **Low** | 9 | **Large aggregate buffer ceiling.** `cap × max-frame` ≈ 16 GiB (Raft) / 2 GiB (edge). Bounded but generous; consider a smaller default frame cap or a global in-flight-bytes ceiling. | `FrameCodec.MAX_FRAME_SIZE`; `EdgeFrameCodec.MAX_EDGE_FRAME_SIZE` |
| — | Info | 11 | CRC32C is not a MAC; all authenticity rests on TLS. Correct by design — documented here so Gate 5 states it normatively (no wire-level integrity without TLS). | — |
| — | Info | 12 | Edge-**client**-side snapshot reassembly (hostile-server → client) accumulates chunks under `chunkCount`/`totalBytes` the server declares; total-buffer bound at the client is out of the server-defender scope but should be checked when the driver client is audited. | `EdgeSnapshotCodec.deserialize`; `FanOutSessionCore` pending transfer |

**Everything else is DEFENDED** — the length/count/overflow/version/type/trailing-byte
discipline is thorough and symmetric across the JDK and Netty readers of each plane, and the
InstallSnapshot chunk reassembly (the one stateful multi-frame accumulator on the server) is
fail-closed on order, cross-snapshot splice, and total heap.

Gate 2 should: (1) add an edge inbound read-idle deadline mirroring `inboundReadTimeoutMs`
(G-2), (2) decide the `senderId↔cert` binding (G-3), (3) replace per-frame stack-trace prints
with a counted/rate-limited drop (G-4).

---
See `attack-matrix.md` for the per-frame status grid.
