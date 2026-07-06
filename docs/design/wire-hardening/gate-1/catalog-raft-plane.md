# Wire-Hardening Gate 1 — Raft-Plane Frame Catalog

Field-by-field byte-layout catalog of every frame/message on the Configd **Raft
consensus wire**. Companion to the data/edge-plane catalog. Source-of-truth as
of the commit under review; every claim cites `file:line`. **No source was
modified to produce this document.**

Canonical sources (paths under `.claude/worktrees/` are stale agent copies and
were ignored):

| Layer | File |
|---|---|
| Outer framing | `configd-transport/src/main/java/io/configd/transport/FrameCodec.java` |
| Wire envelope + policy | `configd-transport/src/main/java/io/configd/transport/RaftWireProtocol.java` |
| Type codes | `configd-transport/src/main/java/io/configd/transport/MessageType.java` |
| **Payload codec (core)** | `configd-server/src/main/java/io/configd/server/RaftMessageCodec.java` |
| Log-entry command sub-format | `configd-config-store/src/main/java/io/configd/store/CommandCodec.java` |
| Keyring body (at-rest, **not on wire**) | `configd-consensus-core/src/main/java/io/configd/raft/KeyringCodec.java` |
| Inbound demux | `configd-server/src/main/java/io/configd/server/RaftTransportAdapter.java` |

---

## 0. Trust boundary & authentication

All Raft traffic is **intra-cluster over mTLS** (`RaftWireProtocol` javadoc,
lines 22–24: "consensus traffic is intra-cluster"; the TLS handshake timeout is
enforced at `RaftWireProtocol.java:56`). Two consequences the catalog leans on:

1. **Every field below is decoded *post*-TLS**, i.e. after the connection's
   cryptographic integrity/peer-auth check. The `FrameCodec` CRC32C
   (`FrameCodec.java:284–301`) is explicitly *defense-in-depth against bit
   flips inside a TLS session*, **not** an authenticator (`FrameCodec.java:40–45`).
2. **Sender identity is the 4-byte transport prefix, not a payload field.** The
   wire is `[4B senderId BE] || FrameCodec-frame` (`RaftWireProtocol.java:13–17`,
   encoded at `RaftWireProtocol.java:100–111`). The receiver injects this
   authenticated `from` for witness frames (`RaftTransportAdapter.java:96`) and
   coalesced heartbeats (`RaftTransportAdapter.java:111–112`). **But most
   payloads *also* carry an in-body `leaderId`/`candidateId`/`from` that is
   decoded and trusted without cross-checking it against the authenticated
   prefix** — see Gap G-7.

Because the boundary is mTLS, the realistic threat model for Gate 2 is a
**compromised / buggy in-cluster peer or a corrupted-memory sender**, not an
anonymous internet attacker. Allocation-amplification gaps still matter (a
single compromised node must not be able to OOM its peers).

---

## 1. Outer wire envelope (shared by every frame)

### 1a. Sender-id prefix — `RaftWireProtocol`

```
offset  width  field           endian  meaning
0        4     senderId        BE      authenticated origin NodeId (transport prefix)
4        ...   FrameCodec frame        (see 1b)
```

- Width constant: `SENDER_ID_SIZE = 4` (`RaftWireProtocol.java:44`).
- **Bound:** the 4-byte *frame-length* prefix that follows is validated against
  `[HEADER_SIZE+TRAILER_SIZE, MAX_FRAME_SIZE]` **before** any frame buffer is
  allocated — `isValidFrameLength` (`RaftWireProtocol.java:123–126`), applied by
  both the JDK and Netty readers. This is the primary allocation gate for the
  whole plane.

### 1b. FrameCodec frame (v2) — `FrameCodec`

```
offset  width  field      endian  meaning / validation
0        4     length     BE      total frame size incl. trailer.
                                   decode: minSize <= length <= MAX_FRAME_SIZE (16 MiB)
                                   AND length == data.length  (FrameCodec.java:272-282)
4        1     version    -       must == WIRE_VERSION (0x02) else
                                   UnsupportedWireVersionException (FrameCodec.java:305-308)
5        1     type       -       MessageType.fromCode() — unknown code throws
                                   (FrameCodec.java:310-311; MessageType.java:66-71)
6        4     groupId    BE      Raft group id — NO range check (used for routing)
10       8     term       BE      Raft term — NO negative/range check
18       8     epoch      BE      RESERVED, MBZ; decode REJECTS non-zero
                                   (FrameCodec.java:315-323)
26       var   payload    -       message-specific (sections 2+)
len-4    4     crc32c     BE      CRC32C over bytes [0, len-4); verified BEFORE
                                   version/type/epoch are trusted (FrameCodec.java:284-301)
```

- `HEADER_SIZE = 26` (`FrameCodec.java:76`), `TRAILER_SIZE = 4`
  (`FrameCodec.java:79`), `MAX_FRAME_SIZE = 16 MiB` (`FrameCodec.java:98`),
  `WIRE_VERSION = 0x02` (`FrameCodec.java:70`).
- **Validation order is deliberate** (`FrameCodec.java:238–256`): short-array →
  length-bounds → length==data.length → **CRC** → version → type → epoch-MBZ.
  CRC-before-everything means a bit-flip in version/type surfaces as
  "corruption", not a misleading version/type error.
- Encode is symmetric: `checkPayloadFitsFrame` rejects oversize payloads on the
  write path too (`FrameCodec.java:160`, `372–388`), so a locally-produced
  frame is always one a peer will accept.
- **`groupId` and `term` are passed through with no range validation.** A
  negative/huge `term` from the wire flows straight into the decoded
  `RaftMessage` (see G-6); `groupId` flows into `driver.routeMessage(groupId,…)`
  (`RaftTransportAdapter.java:62–63`, `116`).

`term` and `groupId` in the header are **authoritative for most frames** — the
payload codec reads `frame.term()` rather than re-encoding term in the body
(e.g. `RaftMessageCodec.java:421`, `440`, `460`). Exceptions: witness frames
carry `selfTerm` in the body *and* mirror it in the header
(`RaftMessageCodec.java:614–616`); coalesced heartbeats put per-group terms in
the body and use a **sentinel header term of 0** (`RaftMessageCodec.java:285`).

---

## 2. Raft payloads — `RaftMessageCodec`

Decode entry point: `RaftMessageCodec.decode(Frame)` switch at
`RaftMessageCodec.java:212–236` (witness and coalesced-heartbeat are routed to
dedicated decoders and throw a *directional* error if reached via `decode()` —
lines 226–232). All payloads are **big-endian** (class javadoc line 30). Shared
bound helpers: `checkRemaining` (`:123`), `checkBlobLen`
(negative + max + remaining, `:131`), `checkInstallSnapshotFitsFrame` (`:147`),
`checkAppendEntriesFitsFrame` (`:174`).

### 2.1 APPEND_ENTRIES (0x01)

Decode: `decodeAppendEntries` (`RaftMessageCodec.java:381–422`).

```
offset  width  field         meaning / bound
0        4     leaderId      NodeId.of(int) — no membership check (G-7)
4        8     prevLogIndex  signed long, unchecked
12       8     prevLogTerm   signed long, unchecked
20       8     leaderCommit  signed long, unchecked
28       4     numEntries    >=0 AND <= MAX_ENTRIES_PER_APPEND (10_000) (:391-399)
                             AND numEntries*20 <= remaining pre-alloc gate (:404-408)
32       ...   entries[numEntries]
  per entry (variable):
  +0     8     index         -> LogEntry(index>=1 enforced by record, LogEntry.java:16)
  +8     8     term          -> LogEntry(term>=0 enforced by record, LogEntry.java:19)
  +16    4     cmdLen        checkBlobLen(cmdLen, MAX_COMMAND_LEN=1 MiB, ...) (:416)
  +20    cmdLen command      opaque CommandCodec bytes (section 3)
```

- Per-entry header re-checked each iteration: `checkRemaining(buf, 20, …)`
  (`:412`). `cmdLen` bounded to **1 MiB** (`MAX_COMMAND_LEN`, `:62`) with
  negative + remaining checks (`checkBlobLen`).
- Encode enforces the same caps + whole-frame fit
  (`checkAppendEntriesFitsFrame`, `:365`).
- **Nested/recursive:** array of entries; each `command` is itself a
  `CommandCodec` grammar (PUT/DELETE/BATCH, and BATCH nests mutations) — decoded
  **later** at apply time, not here (section 3).
- **Trailing bytes after the last entry are NOT rejected** (no strict-end
  check) — G-4.

### 2.2 APPEND_ENTRIES_RESPONSE (0x02)

Decode: `decodeAppendEntriesResponse` (`:434–441`). Fixed 13 B, `checkRemaining(13)` (`:436`).

```
0  1  success     byte != 0
1  8  matchIndex  signed long, unchecked
9  4  from        NodeId.of(int)
```

### 2.3 REQUEST_VOTE (0x03) / PRE_VOTE (0x05)

Decode: `decodeRequestVote(frame, preVote)` (`:454–461`). Same 20-B layout; the
`preVote` flag is derived from the **type code**, not a body field
(`decode()` dispatch `:216–219`). Fixed 20 B, `checkRemaining(20)` (`:456`).

```
0   4  candidateId   NodeId.of(int)
4   8  lastLogIndex  signed long, unchecked
12  8  lastLogTerm   signed long, unchecked
```

### 2.4 REQUEST_VOTE_RESPONSE (0x04) / PRE_VOTE_RESPONSE (0x06)

Decode: `decodeRequestVoteResponse(frame, preVote)` (`:473–479`). Fixed 5 B,
`checkRemaining(5)` (`:475`). `preVote` from type code.

```
0  1  voteGranted  byte != 0
1  4  from         NodeId.of(int)
```

### 2.5 INSTALL_SNAPSHOT (0x07)

Decode: `decodeInstallSnapshot` (`:500–533`). Fixed header 29 B,
`checkRemaining(29)` (`:504`).

```
offset  width   field             meaning / bound
0        4      leaderId          NodeId.of(int) (G-7)
4        8      lastIncludedIndex signed long, unchecked
12       8      lastIncludedTerm  signed long, unchecked
20       4      offset            *** signed int, NO negative/range check ***  (G-1)
24       1      done              byte != 0
25       4      dataLen           checkBlobLen(dataLen, 4 MiB, ...) (:511)
29       dataLen data             this chunk's raw snapshot bytes
+0       4      configLen         OPTIONAL-trailing; present iff buf.hasRemaining()
                                  negative REJECTED (:518-524); if >0:
                                  checkBlobLen(configLen, 4 MiB, ...) (:526)
+4       configLen configData     cluster-config blob (final chunk only)
```

- **Chunked / nested:** a large snapshot is streamed as ordered chunks; `offset`
  is the byte position of this chunk (`InstallSnapshotRequest.java:16,26`). Total
  reassembled size is **not** bounded by this per-chunk cap — it is bounded by
  the follower's reassembly cap `RaftNode.maxReassembledSnapshotBytes`
  (`RaftMessageCodec.java:70–72`).
- `MAX_SNAPSHOT_BLOB_LEN = 4 MiB` is a **per-blob** ceiling (`:88`). Encode also
  enforces the combined data+config+header+frame fit in 16 MiB via
  `checkInstallSnapshotFitsFrame` (`:147–172`, called at `:485`) using long
  arithmetic against 32-bit overflow.
- **`offset` is unchecked at decode** whereas the *response's*
  `nextExpectedOffset` **is** negative-checked — an asymmetry (G-1). A negative
  `offset` flows into `InstallSnapshotRequest` and thence into reassembly
  indexing.
- **Trailing bytes after `configData` are NOT rejected** (no strict-end check) — G-4.

### 2.6 INSTALL_SNAPSHOT_RESPONSE (0x0F)

Decode: `decodeInstallSnapshotResponse` (`:547–575`). Mandatory 13 B,
`checkRemaining(13)` (`:549`); `nextExpectedOffset` is optional-trailing.

```
0   1  success            byte != 0
1   4  from               NodeId.of(int)
5   8  lastIncludedIndex  REJECTED if < 0 (:553-561)
13  4  nextExpectedOffset OPTIONAL-trailing (absent => 0); if present REJECTED if <0 (:566-573)
```

- The optional trailing field is a back-compat slot: a peer that omits it
  decodes to offset 0, which the sender safely re-syncs from (`:562–564`).

### 2.7 TIMEOUT_NOW (0x10)

Decode: `decodeTimeoutNow` (`:585–590`). Fixed 4 B, `checkRemaining(4)` (`:587`).

```
0  4  leaderId  NodeId.of(int)
```

### 2.8 RAFT_COALESCED_HEARTBEAT (0x11)

**Not a `RaftMessage`** — it bundles many groups. Dedicated decoder
`decodeCoalescedHeartbeat` (`:299–346`); `decode()` throws a directional error
if it is reached there (`:226–227`). The sending node id is the transport prefix,
**not** in the payload; header `groupId`/`term` are sentinels (0)
(`:248–251`, `:285`).

```
offset  width  field   bound
0        4     count   >=0 (:310-312) AND <= MAX_COALESCED_GROUPS (1024) (:313-316)
                       AND count*40 <= remaining pre-alloc gate (:319-323)
4        ...   group[count]  (each COALESCED_GROUP_RECORD = 40 bytes, :103)
  per group (fixed 40 B, checkRemaining(40) each :326):
  +0   4  groupId       duplicate id REJECTED via putIfAbsent (:335-338)
  +4   8  term          per-group term (body-authoritative here)
  +12  4  leaderId      NodeId.of(int)
  +16  8  prevLogIndex
  +24  8  prevLogTerm
  +32  8  leaderCommit
```

- Every coalesced entry is an **empty** AppendEntries (heartbeat); encode rejects
  a group carrying non-empty entries (`:271–277`).
- **Strictest frame in the plane:** rejects trailing bytes after the last group
  (`:340–344`) and duplicate group ids. This is the model the other frames
  should follow (see G-4).
- Demux: each group is dispatched to its own owner thread
  (`RaftTransportAdapter.java:109–113`).

### 2.9 RAFT_WITNESS (0x12) / RAFT_WITNESS_REPLY (0x13)

Dedicated decoder `decodeWitness(frame, from)` (`:630–649`); `decode()` throws a
directional error if reached there (`:231–232`). **Sender is the authenticated
transport `from`, injected at decode — never a body field** (`:645–648`,
`RaftTransportAdapter.java:96`). Fixed 29-B body (`WITNESS_BODY_LEN`, `:116`),
`checkRemaining(29)` (`:638`).

```
offset  width  field         meaning
0        8     selfAnchorSeq  announced per-group anchorSeq
8        8     selfTerm       authoritative term (also mirrored in header, :614-616)
16       4     selfVotedFor   NodeId as raw int (not wrapped)
20       8     seenOfYouSeq   highest anchorSeq replier has witnessed of the querier
28       1     flags          bit0 = WITNESS_FLAG_QUERY (0x01); REPLY never sets it (:600-602)
```

- Type discriminates WITNESS vs WITNESS_REPLY (`:645–648`); a reply carries the
  same body but never the QUERY flag.
- Additive feature (Gate 3c): does not touch any other frame layout
  (`MessageType.java:36–39`).

---

## 3. Log-entry command sub-format — `CommandCodec`

These bytes ride **inside** an AppendEntries `LogEntry.command` (section 2.1)
and are **carrier-versioned** — no self version byte; the carrier (WAL envelope /
NOTIFY frame / snapshot envelope) pins the grammar (`CommandCodec.java:26–35`).
Decoded at **apply time**, not at wire-decode; the bytes have already passed the
frame CRC and the 1-MiB `MAX_COMMAND_LEN` cap by the time `decode` runs.

Decode: `CommandCodec.decode(byte[])` (`:142–158`). Empty command → `Noop`
(`:145–147`). Type byte discriminates (`:151`); unknown type throws (`:155`).

```
byte0  type    0x01 PUT | 0x02 DELETE | 0x03 BATCH   (empty array => NOOP)

PUT   (decodePut :235-250):
  1  2       keyLen    UNSIGNED short (0..65535)  *** new byte[keyLen] with NO
                       explicit remaining-check — relies on BufferUnderflow *** (G-2)
  3  keyLen  key       UTF-8, no non-blank check on decode (G-3)
  +  4       valueLen  REJECTED if <0 or > MAX_VALUE_SIZE (1 MB) (:242-245)
  +  valueLen value

DELETE (decodeDelete :252-259):
  1  2       keyLen    UNSIGNED short; new byte[keyLen], no remaining-check (G-2)
  3  keyLen  key       UTF-8

BATCH (decodeBatch :263-288):
  1  4       count     REJECTED if <0 or > MAX_BATCH_COUNT (10_000) (:265-268)
             *** NO count*minSize <= remaining pre-alloc gate (G-2) ***
  then count mutations, each a PUT- or DELETE-body as above (nested/recursive);
  unknown mutation type throws (:282)
```

- **Bounds present:** value length 1 MB per PUT (`MAX_VALUE_SIZE`, `:233`), batch
  count 10_000 (`MAX_BATCH_COUNT`, `:261`), both negative-checked.
- **Bounds missing (G-2):** `keyLen` allocations (`new byte[keyLen]`, `:237`,
  `:246` via value too, `:254`) have **no explicit `<= remaining` check** before
  allocation — they rely on `ByteBuffer` throwing `BufferUnderflowException`.
  Inconsistent with `RaftMessageCodec`'s `checkBlobLen` discipline. Within the
  Raft plane the transitive 1-MiB `MAX_COMMAND_LEN` frame cap bounds the total,
  so amplification is limited; **but `CommandCodec` is also used to decode
  snapshot values** (per its own javadoc, `:26–35`), a carrier with **no 1-MiB
  cap**, where a valid `count` + repeated up-to-1 MB values has only the
  per-value cap and `BufferUnderflow` as guards, not an aggregate bound.

---

## 4. `KeyringCodec` — AT-REST ONLY, not on the Raft wire

`KeyringCodec` (`configd-consensus-core/.../KeyringCodec.java`) is a
**package-private at-rest body codec**, sealed inside an at-rest HMAC
`IntegrityEnvelope` under `K_keyringMac` and physically placed by `KeyringFile`
(`KeyringCodec.java:38–48`, `:193–200`). **It never travels on the Raft wire** —
it is neither reachable from `RaftMessageCodec` nor placed in InstallSnapshot
`data`/`configData` (which carry the state-machine snapshot). Documented here
only to record the exclusion the lead asked for. Its `decodeBody`
(`:131–188`) is itself well-bounded (`entryCount <= remaining` `:147`,
`wrappedLen <= remaining` `:173`, 1-byte `nonceLen`, reserved-value fail-closed
`:137,157,162`), so it is **not** a Gate 2 target for the wire arc.

---

## 5. Summary table

| Frame | Type | Fixed size? | Variable fields | Current bounds | Gaps |
|---|---|---|---|---|---|
| (envelope) FrameCodec | — | 26 B hdr + 4 B trailer | payload | len ∈ [30, 16 MiB]; CRC; ver==2; epoch MBZ | `term`/`groupId` unranged (G-6) |
| APPEND_ENTRIES | 0x01 | no | entries[], command[] | numEntries≤10k + pre-gate; cmdLen≤1 MiB | trailing bytes tolerated (G-4) |
| APPEND_ENTRIES_RESPONSE | 0x02 | 13 B | — | checkRemaining(13) | — |
| REQUEST_VOTE / PRE_VOTE | 0x03/0x05 | 20 B | — | checkRemaining(20) | — |
| REQUEST_VOTE_RESPONSE / PRE_VOTE_RESPONSE | 0x04/0x06 | 5 B | — | checkRemaining(5) | — |
| INSTALL_SNAPSHOT | 0x07 | no | data, configData | blobs ≤4 MiB; combined-fit check | **`offset` unchecked (G-1)**; trailing tolerated (G-4) |
| INSTALL_SNAPSHOT_RESPONSE | 0x0F | 13 B (+4 opt) | nextExpectedOffset? | lastIdx≥0; nextOffset≥0 | — |
| TIMEOUT_NOW | 0x10 | 4 B | — | checkRemaining(4) | — |
| RAFT_COALESCED_HEARTBEAT | 0x11 | no | group[] (40 B ea) | count≤1024 + pre-gate; dup-reject; strict-end | reference impl (no gap) |
| RAFT_WITNESS / _REPLY | 0x12/0x13 | 29 B | — | checkRemaining(29) | — |
| LogEntry command (nested) | — | no | key, value, mutations[] | value≤1 MB; batch count≤10k | **keyLen alloc unchecked (G-2)**; no non-blank key (G-3) |
| PLUMTREE_* / HYPARVIEW_* / HEARTBEAT | 0x08–0x0E | — | — | valid enum code, **no codec** | reserved/dead code (G-5) |
| KeyringCodec | — | — | — | at-rest, well-bounded | **not on wire** (excluded) |

---

## 6. Allocation / bound / validation gaps (Gate 2 backlog)

Ordered by significance. None is exploitable by an anonymous attacker (mTLS
boundary, §0); all are reachable by a **compromised or corrupted-memory peer**.

- **G-1 — `INSTALL_SNAPSHOT.offset` decoded with NO negative/range check
  (validation gap).** `RaftMessageCodec.java:508` reads
  `int offset = buf.getInt()` and passes it straight to `InstallSnapshotRequest`
  (`:532`). The *response's* `nextExpectedOffset` **is** negative-checked
  (`:569–572`) — this is an asymmetry. A negative/garbage `offset` flows into the
  follower's chunk-reassembly indexing. **Fix: reject `offset < 0` at decode**,
  mirroring the response field. *(Not a raw allocation gap, but the highest-value
  missing bound on this plane.)*

- **G-2 — `CommandCodec` key/value/batch allocations lack explicit
  remaining-checks (allocation gap, transitively bounded on the Raft plane).**
  `decodePut`/`decodeDelete` do `new byte[keyLen]` (`:237`, `:254`) and
  `decodeBatch` does `new ArrayList<>(count)` (`:269`) **without a
  `declared <= remaining` pre-check** — they rely on `BufferUnderflowException`.
  Inside AppendEntries the 1-MiB `MAX_COMMAND_LEN` cap bounds the total, but
  `CommandCodec` is **also used for snapshot values** (no 1-MiB carrier cap,
  `CommandCodec.java:26–35`), where only the per-value 1 MB cap + underflow guard
  apply — no aggregate bound. **Fix: adopt the `checkBlobLen`/pre-gate discipline
  used in `RaftMessageCodec` (bound `keyLen`/`count*minSize` against remaining
  before allocation).**

- **G-3 — `CommandCodec` PUT/DELETE key not validated on decode (semantic
  gap).** `encodePut`/`encodeDelete` require non-null but the record `Put`/`Delete`
  and the decode path accept an **empty/blank key** (`decodePut :239`,
  `Put` record has no non-blank check). A `keyLen=0` PUT decodes to an empty
  config key. **Fix: reject empty/blank keys at decode.**

- **G-4 — Inconsistent trailing-byte strictness across frames (consistency
  gap).** Only `RAFT_COALESCED_HEARTBEAT` rejects trailing bytes after the
  logical message (`:340–344`). `APPEND_ENTRIES` (after last entry) and
  `INSTALL_SNAPSHOT` (after `configData`) **silently tolerate** trailing padding.
  The outer frame length pins total size exactly (`FrameCodec.java:278–282`), so
  this is not an allocation risk, but it is a wire-conformance hole (a malformed
  encoder or covert-channel padding is accepted). **Fix: add strict-end
  `buf.hasRemaining()` rejection to every fixed-shape decoder, matching the
  coalesced-heartbeat model.**

- **G-5 — Reserved type codes `PLUMTREE_*` (0x08–0x0B), `HYPARVIEW_*`
  (0x0C–0x0D), `HEARTBEAT` (0x0E) are accepted by `FrameCodec`/`MessageType` but
  have NO payload codec (dead attack surface).** `MessageType.fromCode` accepts
  them (`MessageType.java:59–71`), so a peer can send a CRC-valid frame with one
  of these types; `RaftMessageCodec.decode` then throws
  *"Not a Raft message type"* (`:233–234`), caught and **logged per frame** in
  `RaftTransportAdapter.java:118–120`. Production never emits them (only testkit
  benchmarks reference `HEARTBEAT`). Fail-closed, but a cheap per-frame
  **log-spam / error-path** surface. **Fix (Gate 2 decision): either remove the
  unused codes from the enum, or handle them with a single rate-limited "unknown
  raft type" drop instead of an exception+log per frame.**

- **G-6 — Header `term` (signed long) and `groupId` (signed int) pass through
  with no range validation (validation gap).** `FrameCodec.java:312–313` reads
  both raw; `term` becomes the RPC term in every decoded message
  (`RaftMessageCodec.java:421` etc.) and `groupId` is used directly for routing
  (`RaftTransportAdapter.java:116`). `LogEntry` validates *entry* term ≥ 0
  (`LogEntry.java:19`) but the **RPC** term is never checked negative, and
  `groupId` is never range-checked against the configured shard count at the
  codec/demux layer. **Fix: reject negative `term`; bound `groupId` to
  `[0, shardCount)` before routing.**

- **G-7 — In-body `leaderId`/`candidateId` is trusted without cross-checking the
  authenticated transport `from` (spoofing surface within the trust boundary).**
  AppendEntries/RequestVote/InstallSnapshot/TimeoutNow decode `leaderId`/
  `candidateId` from the **body** (`NodeId.of(buf.getInt())`, e.g. `:386`,
  `:457`, `:505`) with no membership check and no comparison to the
  transport-authenticated prefix `from`. Witness frames and coalesced heartbeats
  deliberately use the authenticated `from` instead (`:645–648`,
  `RaftTransportAdapter.java:96`,`111`) — the RPCs do not. A compromised peer can
  therefore claim to *be* a different leader/candidate in the body while
  authenticated as itself. Consensus-layer term/log checks blunt the impact, but
  the divergence is uncross-checked at the wire layer. **Fix (Gate 2 policy
  decision): either bind body identity to the authenticated `from`, or document
  why the body id is allowed to differ (relaying is not a thing here).**

### Non-gaps confirmed (well-bounded)

`APPEND_ENTRIES` (numEntries ≤ 10k + `count*20 ≤ remaining` pre-gate + 1-MiB
per-command `checkBlobLen`), `RAFT_COALESCED_HEARTBEAT` (count ≤ 1024 + pre-gate
+ dup-reject + strict-end), `INSTALL_SNAPSHOT` blobs (4 MiB per-blob +
combined-fit long-arithmetic check), all fixed-size responses
(`checkRemaining`), the outer `FrameCodec` length/CRC/version/epoch gates, and
`KeyringCodec` (at-rest, still fully bounded). The plane's allocation posture is
**already strong**; the gaps above are validation/consistency/asymmetry issues,
not a missing top-level allocation gate.
