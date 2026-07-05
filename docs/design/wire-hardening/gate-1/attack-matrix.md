# Gate 1 — Per-Frame × Attack-Class Matrix

Companion to `threat-model.md`. Rows = every frame type on both planes. Columns = the
attack classes from the threat model. Cell legend:

- **D** = DEFENDED — the check exists; `file:line` cited.
- **GAP** = a concrete missing check (finding id in the threat model).
- **N/A** = not applicable to this frame (reason inline).
- **·** = inherited from the envelope row (same-plane frames share the envelope defenses;
  the row lists only payload-specific deltas).

Attack classes (abbreviated headers):
`1`=version · `2`=oversize-len · `3`=truncation · `4`=len/content-mismatch ·
`5`=int-overflow/neg-len · `6`=type-confusion/wrong-dir · `7`=field/identity-injection ·
`8`=array-count abuse · `9`=conn-flood · `10`=slow-loris · `11`=checksum/MAC ·
`12`=protocol-specific.

Classes 1,2,3,4,9,10,11 are **envelope-level** (same for every frame on a plane); classes
5,6,8,12 are **payload-level** (per frame). The two envelope rows below carry 1/2/3/4/9/10/11;
each frame row carries the payload-level deltas.

---

## A. Raft consensus plane

### Envelope (`FrameCodec` + `RaftWireProtocol`, shared by every Raft frame)

| Class | Status | Citation |
|---|---|---|
| 1 version | **D** | `FrameCodec.decode:305-308` strict `!=0x02` reject; CRC-before-version `:290-308` |
| 2 oversize-len | **D** | `decode:273-277`, `peekLength:355-360`, `isValidFrameLength:123-126` gate `TcpRaftTransport:378` + `RaftFrameDecoder:45` before `new byte[]` |
| 3 truncation | **D** | min-size `decode:266-269`; `readFully` fills exact length `TcpRaftTransport:390` |
| 4 len/content | **D** | `length!=data.length` `decode:278-282` |
| 9 conn-flood | **D** | `maxInboundConnections=1024` before reader `TcpRaftTransport:330-334` |
| 10 slow-loris | **D** | `inboundReadTimeoutMs` (15s) `TcpRaftTransport:339`; handshake bound `:508`; trip `:437-444` |
| 11 checksum | **D**(by design) | CRC32C-before-interpret `decode:290-301`; authenticity = TLSv1.3 mTLS `:543` |
| epoch MBZ | **D** | reserved-epoch reject `decode:314-323` |

Payload rows (deltas only; envelope defenses apply to all):

| Frame (code) | 5 int/neg | 6 type/dir | 8 count | 12 protocol-specific |
|---|---|---|---|---|
| APPEND_ENTRIES (0x01) | **D** `RaftMessageCodec:391-394,404-409,416` (cmdLen `checkBlobLen`) | **D** typed switch `:214` | **D** `MAX_ENTRIES_PER_APPEND` `:395-399` + fits-remaining `:404-409` | inner cmd bytes opaque, decoded post-consensus by `CommandCodec` (bounded `:242-245,265-268`) |
| APPEND_ENTRIES_RESPONSE (0x02) | **D** fixed 13B `checkRemaining:436` | **D** `:215` | N/A no array | — |
| REQUEST_VOTE (0x03) | **D** `checkRemaining:456` | **D** `:216` | N/A | — |
| REQUEST_VOTE_RESPONSE (0x04) | **D** `:475` | **D** `:217` | N/A | — |
| PRE_VOTE (0x05) | **D** `:456` | **D** `:218` (preVote flag) | N/A | — |
| PRE_VOTE_RESPONSE (0x06) | **D** `:475` | **D** `:219` | N/A | — |
| INSTALL_SNAPSHOT (0x07) | **D** `dataLen`/`configLen` `checkBlobLen:511,526`, neg-config reject `:518-523`, `checkInstallSnapshotFitsFrame:147-172` | **D** `:220` | N/A (fixed blobs) | **D** offset abuse: contiguous-only + total cap + cross-snap guard `RaftNode:2912-2956,2932-2944` |
| INSTALL_SNAPSHOT_RESPONSE (0x0F) | **D** neg `lastIncludedIndex` `:553-561`, neg `nextExpectedOffset` `:569-572` | **D** `:221` | N/A | optional-trailing field decoded safely `:565-573` |
| TIMEOUT_NOW (0x10) | **D** `checkRemaining:587` | **D** `:222` | N/A | — |
| RAFT_COALESCED_HEARTBEAT (0x11) | **D** `n<0` `:310-312`, fits-remaining `:319-323`, per-group `checkRemaining:326` | **D** directional throw in `decode()` `:226-227`; type-guard `:302-306` | **D** `MAX_COALESCED_GROUPS` `:313-316` | **D** dup-gid reject `:335-338`, strict trailing-byte reject `:340-344` |
| RAFT_WITNESS (0x12) | **D** fixed 29B `checkRemaining:638` | **D** directional throw `:231-232`; `decodeWitness` type-guard `:632-635` | N/A | **G-3** sender keyed on unauthenticated `senderId` prefix (spoofable) — `decodeWitness` injects `from` from transport `:646` |
| RAFT_WITNESS_REPLY (0x13) | **D** `:638` | **D** `:231-232` | N/A | **G-3** as above `:648` |
| HEARTBEAT (0x0E) | N/A no codec | **D** `default` throw `:233-234` (handler-level, non-desync) | N/A | **G-4** per-frame `printStackTrace` log-flood `TcpRaftTransport:425-431` |
| PLUMTREE_* (0x08–0x0B) | N/A | **D** `default` throw `:233-234` | N/A | **G-4** log-flood |
| HYPARVIEW_* (0x0C–0x0D) | N/A | **D** `default` throw `:233-234` | N/A | **G-4** log-flood |
| *(undefined type byte)* | — | **D** `MessageType.fromCode:66-71` reject (after CRC) | — | — |

---

## B. Edge fan-out plane

### Envelope (`EdgeFrameCodec`, shared by every edge frame)

| Class | Status | Citation |
|---|---|---|
| 1 version | **D** | allowlist `{0x01,0x02,0x03}` `decode:621-629`; per-conn **pin** `ByteToEdgeFrameDecoder:62-69` + `decode(data,ver):633-637`; watch-vs-version `:649-653` |
| 2 oversize-len | **D** | `peekLength:1103-1108` + `decode:594-599` gate `ByteToEdgeFrameDecoder:56` and `FanOutServer.readFrame:540` before `new byte[]` |
| 3 truncation | **D** | min-size `decode:588-591`; every field guarded (`readBytes/readString:1052-1078`, cursor floor `:871-873`); underflow → `FRAME_CORRUPT` `:666-670` |
| 4 len/content | **D** | `length!=data.length` `:600-603`; strict no-trailing `:659-662` |
| 9 conn-flood | **D** | `maxSessions=1024` counted before handshake `NettyFanOutServer:376-381`, `FanOutServer:301` |
| 10 slow-loris | **GAP-2** | **no post-handshake read-idle deadline**: `FanOutServer:330` `setSoTimeout(0)`; `NettyFanOutServer.initChannel:267-281` installs no `IdleStateHandler` |
| 11 checksum | **D**(by design) | CRC-before-interpret `:606-615`; authenticity = mTLS `NettyFanOutServer:297` |

Payload rows (deltas only). Server-inbound-legal control frames are marked **[in]**; the
rest are server→client and, if sent inbound, are rejected by the driver as
`PROTOCOL_VIOLATION` (`FanOutConnectionDriver:498-516`) — that is their class-6 defense.

| Frame (code) | 5 int/neg | 6 type/dir | 8 count | 12 protocol-specific |
|---|---|---|---|---|
| SUBSCRIBE (0x01) **[in]** | **D** `prefixCount<0` `:699-701`, `readString:1071` | **D** admitted only as first/legacy frame `routeFirstFrame:361`; dup → `PROTOCOL_VIOLATION` `:513-514` | **D** `prefixCount ≤ remaining` `:699` | **D** epoch-0 reject `:707-711`; `edgeId` advisory, identity=cert `bindIdentity` |
| SUBSCRIBE_OK (0x02) | **D** mode-ord bound `:736-739` | **D** server→client; inbound → PROTOCOL_VIOLATION | N/A | filtered byte only under 0x03 `:743-749` |
| NOTIFY (0x03) | **D** `batchLen`/`sigLen`/`nonceLen` `≤remaining` `:771,783,791` | **D** server→client | **D** `count ≤ MAX_NOTIFY_BATCH` `:754-757` | **D** nested `CommandCodec.decode` bounded (value `:242-245`, batch `:265-268`); empty-delta reject `:810-812` |
| SNAPSHOT_BEGIN (0x04) | **D** fixed fields | **D** server→client | N/A | `chunkCount`/`totalBytes` are hints; client-side reassembly bound = Info item |
| SNAPSHOT_CHUNK (0x05) | **D** `len ≤ MAX_SNAPSHOT_CHUNK_BYTES` `:824-829` | **D** server→client | N/A | per-chunk 1 MiB cap `:825`; client total-bound = Info |
| SNAPSHOT_END (0x06) | **D** single u64 | **D** server→client | N/A | — |
| CURSOR_ACK (0x07) **[in]** | **D** single u64 `decodePayload:681` | **D** legal in legacy+watch route `:499,511` | N/A | opaque seq |
| HEARTBEAT (0x08) | **D** two u64 `:682` | **D** server→client | N/A | — |
| ERROR_CLOSE (0x09) | **D** code bound `fromCode` `:838-841`, `readString` | **D** server→client | N/A | — |
| WATCH_CREATE (0x0A) **[in]** | **D** `readBytes(path)` `:898`, flags-present `:900-902` | **D** 0x02-only `:649-653`; watch-route `:507` | **D** cursor count bound (via `decodeCursor`) | **D** `decodeCursor` floor+epoch0+count `:869-892`; ctor invariant `:904-908` |
| WATCH_CANCEL (0x0B) **[in]** | **D** single u64 `:685` | **D** 0x02-only; watch-route `:508` | N/A | — |
| WATCH_CREATED (0x0C) | **D** `gid/latestSeq/mode` bounded, mode-ord `:922-925` | **D** server→client | **D** `count×SHARD_MODE_BYTES ≤ remaining` `:914` | — |
| WATCH_EVENT (0x0D) | **D** `readBytes(key)`, `valLen` signed-sentinel `-1` handled, `<0` reject `:947-960` | **D** server→client | **D** `count×MIN_CHANGE_BYTES ≤ remaining` `:940` | **D** `WatchChange` kind/value invariant `:961-966`; `WatchEvent` ctor `:968-972` |
| WATCH_PROGRESS (0x0E) | **D** cursor + u64 `:976-978` | **D** server→client | **D** via `decodeCursor` | **D** cursor invariants `:869-892` |
| WATCH_CANCELED (0x0F) | **D** code bound `:988-993`, `has_oldest` strict `:995-1003` | **D** server→client | **D** optional cursor bounded | **D** epoch/count via `decodeCursor` |
| WATCH_SNAPSHOT_BEGIN (0x10) | **D** fixed fields, ctor `:1014-1018` | **D** server→client | N/A | seq/totalBytes ≥0 via ctor (see `:852-858` u64 range note) |
| WATCH_SNAPSHOT_CHUNK (0x11) | **D** `len ≤ MAX_SNAPSHOT_CHUNK_BYTES` `:1026-1030` | **D** server→client | N/A | per-chunk 1 MiB cap |
| WATCH_SNAPSHOT_END (0x12) | **D** fixed fields `:1040-1048` | **D** server→client | N/A | — |
| *(undefined type byte)* | — | **D** `FrameType.fromCode` → `FRAME_CORRUPT` `:640-644` | — | — |
| *(watch type on 0x01/0x03 frame)* | — | **D** `FRAME_CORRUPT` `:649-653` | — | downgrade-smuggling closed |

---

## C. Nested / carrier codecs (no self-framing; inherit the enclosing frame's envelope)

| Codec | Carried by | 5 int/neg | 8 count | Notes |
|---|---|---|---|---|
| `CommandCodec` (PUT/DELETE/BATCH) | AppendEntries `cmd` (post-consensus apply) **and** NOTIFY delta (`decodeNotification`) | **D** value `≤MAX_VALUE_SIZE` `:242-245`; keyLen u16 (≤65535, alloc trivially bounded) | **D** `MAX_BATCH_COUNT=10_000` `:265-268` | unknown type byte → throw `:155-157,282-284`; in the NOTIFY path wrapped by `EdgeFrameCodec` try/catch → `FRAME_CORRUPT` `:666-670` |
| `EdgeSnapshotCodec` (snapshot body) | SNAPSHOT_CHUNK stream (server→client) | **D** per-entry field `≤1 MiB` + remaining-check `readBoundedLen:216-232`; `entryCount<0` reject `:198-199` | `entryCount` implicitly bounded by body length (each entry ≥4B, `readBoundedLen` guards) | **Info:** total reassembled-body bound at the client is out of server-defender scope; audit when the driver client is reviewed |

---

## Summary of non-DEFENDED cells

| Cell | Finding | Sev |
|---|---|---|
| Edge envelope × class 10 (slow-loris) | **G-2** no post-handshake inbound read-idle deadline (JDK `setSoTimeout(0)`; Netty no `IdleStateHandler`) | Medium |
| RAFT_WITNESS / _REPLY × class 12 (identity) | **G-3** `senderId` prefix unauthenticated vs TLS cert; witness attestations keyed on a spoofable field | Medium |
| Dormant Raft types × class 12 | **G-4** per-frame `printStackTrace` log-flood | Low |
| Both envelopes × class 9 (aggregate) | **G-5** `cap × max-frame` ≈ 16 GiB / 2 GiB worst-case buffer | Low |
| SNAPSHOT/WATCH_SNAPSHOT client reassembly | Info — hostile-server→client total-bound, out of server-defender scope | Info |

Every other cell is DEFENDED with the cited check. Gate 2 closes G-2 (mirror the Raft
`inboundReadTimeoutMs` on the edge), decides G-3 (bind `senderId`↔cert), and G-4/G-5 as
hardening.
