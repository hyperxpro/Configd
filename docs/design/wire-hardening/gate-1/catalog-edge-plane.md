# Gate 1 — Edge / fan-out plane frame catalog (field-by-field byte layout)

Scope: the **client-facing streaming protocol** (SUBSCRIBE, WATCH lifecycle, snapshots,
vector cursors). Canonical sources (all citations are `file:line`):

- `configd-distribution-service/src/main/java/io/configd/distribution/wire/EdgeFrameCodec.java` — the core edge codec (encode + decode + `peekLength`/`peekVersion`).
- `configd-distribution-service/.../wire/EdgeSnapshotCodec.java` — snapshot body serialize/chunk/reassemble/deserialize.
- `configd-distribution-service/.../wire/EdgeFrame.java` — the sealed frame model (record ctors carry value invariants).
- `configd-distribution-service/.../wire/FrameType.java`, `ErrorCode.java`, `WatchCursor.java`, `FrameSink.java`.
- Server ingress: `configd-server/.../fanout/ByteToEdgeFrameDecoder.java`, `NettyFanOutServer.java`; routing: `configd-distribution-service/.../fanout/FanOutConnectionDriver.java`.
- Client ingress: `configd-edge-node/.../node/EdgeStreamClient.java` (`readFrame`), `configd-edge-cache/.../edge/EdgeClientCore.java` (snapshot reassembly).

Everything on this plane is **big-endian** (`FrameSink` writes BE, matching `ByteBuffer` default; `FrameSink.java:21`).

---

## 1. Outer frame envelope (shared by ALL 18 frame types)

The edge wire has its **own** length/version/type/CRC envelope — it does **not** reuse the
Raft `FrameCodec`. A separate version byte and fixture gate (`EdgeFrameCodec.java:15-27,60-72`).

| Off | Width | Field | Meaning |
|----:|------:|-------|---------|
| 0 | 4 | `length` u32 BE | total frame size incl. length prefix + trailer (`encodeInto` back-patches, `EdgeFrameCodec.java:253,267`) |
| 4 | 1 | `version` u8 | `0x01` built / `0x02` watch-capable / `0x03` filtered-fan-out (`EdgeFrameCodec.java:72,85,99`) |
| 5 | 1 | `type` u8 | `FrameType` code `0x01..0x12` (`FrameType.java:16-44`) |
| 6 | var | `payload` | frame-type-specific (see per-frame sections) |
| len−4 | 4 | `CRC32C` u32 BE | Castagnoli over `[0 .. len−4)` i.e. length..end-of-payload (`EdgeFrameCodec.java:268-271`) |

`HEADER_SIZE = 6` (`:102`), `TRAILER_SIZE = 4` (`:105`). Fixed envelope overhead = **10 bytes**.

**Caps:** `MAX_EDGE_FRAME_SIZE = 2 MiB` (`:111`); `MAX_SNAPSHOT_CHUNK_BYTES = 1 MiB` (`:114`);
`MAX_NOTIFY_BATCH = 64` (`:117`); `MAX_NOTIFY_BATCH_BYTES = 256 KiB` (`:120`, **encode-only** — see gaps).

**Decode validation order** (`decode`, `EdgeFrameCodec.java:585-671`), deliberate:
1. `data.length >= HEADER_SIZE+TRAILER_SIZE` (10) → else `FRAME_CORRUPT` (`:587-591`).
2. `length` read; `minSize <= length <= MAX_EDGE_FRAME_SIZE` → else `FRAME_TOO_LARGE`/`FRAME_CORRUPT` (`:593-599`).
3. `length == data.length` (exactly one frame) → else `FRAME_CORRUPT` (`:600-603`).
4. **CRC32C verified BEFORE version/type are interpreted** (`:605-615`) — a flipped version/type surfaces as `FRAME_CORRUPT`, never a misleading "bad version".
5. `version ∈ {0x01,0x02,0x03}` → else `BAD_WIRE_VERSION` (`:621-629`); per-connection pin mismatch → `BAD_WIRE_VERSION` (`:633-637`).
6. `type = FrameType.fromCode` → unknown code = `FRAME_CORRUPT` (`:638-644`); a `WATCH_*` type on a non-`0x02` frame = `FRAME_CORRUPT` (`:649-653`).
7. payload decoded over window `[6, len−4)` (`:656`); **trailing bytes after payload = `FRAME_CORRUPT`** (`:659-662`); any `RuntimeException` (underflow) → `FRAME_CORRUPT` (`:666-670`).

**Bound-before-allocation gate.** Both readers size the frame buffer from `peekLength`
(`EdgeFrameCodec.java:1095-1110`) which bounds the declared length to `[10, 2 MiB]` **before**
`new byte[total]` — server `ByteToEdgeFrameDecoder.java:56-60`, edge `EdgeStreamClient.java:611-614`.
So no single frame buffer can exceed 2 MiB regardless of a lying prefix. Every internal
length/count field is then checked `≤ p.remaining()` before its own allocation. **There is no
single unbounded allocation in the server ingress codec** — the residual concerns are (a) a
bounded-but-amplifying preallocation (SUBSCRIBE prefixes) and (b) a genuinely unbounded
*cross-frame* accumulation on the **client** side (snapshot chunks). Both catalogued below.

### Version gating summary
- `0x01` legacy: types `0x01..0x09` only.
- `0x02` watch: types `0x01..0x12`; the ONLY version under which `WATCH_*` (`0x0A..0x12`) is legal (`:247-251` encode, `:649-653` decode).
- `0x03` filtered fan-out: types `0x01..0x09`; adds a trailing byte to SUBSCRIBE (`acceptsFiltered`) and SUBSCRIBE_OK (`filtered`) ONLY (`:315-317,324-326`). `WATCH_*` NOT legal.
- Per-connection pin: first frame accepts any of the three, then the connection is pinned; a later frame of another version = `BAD_WIRE_VERSION` (server `ByteToEdgeFrameDecoder.java:62-69`).

---

## Summary table

Payload offsets below are relative to the payload start (frame offset 6). "Fixed?" = fixed
payload size. Direction: C→S client→server (**inbound at server = attacker-controlled**), S→C
server→client. "Auth" per §"Pre-auth surface".

| # | Frame | Type | Dir | Fixed? | Variable fields | Key current bounds | Auth reach | Gap |
|--:|-------|:----:|:---:|:------:|-----------------|--------------------|:----------:|-----|
| 1 | SUBSCRIBE | 0x01 | C→S | no | prefix[] (count+each str), edgeId, (0x03) acceptsFiltered | `prefixCount ≤ remaining` **(loose, bytes not elems)**; each str len ≤ remaining; epoch≠0 | **pre-auth** | **G1 amplify** |
| 2 | SUBSCRIBE_OK | 0x02 | S→C | no | (0x03) filtered | `mode < Mode.values().length` | — | — |
| 3 | NOTIFY | 0x03 | S→C | no | notifications[] (≤64), each batch/sig/nonce | `count ≤ 64`; batch/sig/nonce len ≤ remaining | **pre-auth¹** | **G2 asym** |
| 4 | SNAPSHOT_BEGIN | 0x04 | S→C | **yes (20)** | — | snapshotSeq/chunkCount/totalBytes ≥ 0 | **pre-auth¹** | G4 no cross-check |
| 5 | SNAPSHOT_CHUNK | 0x05 | S→C | no | bytes (rest) | `len ≤ 1 MiB` | **pre-auth¹** | — (svr); **G3 client**|
| 6 | SNAPSHOT_END | 0x06 | S→C | **yes (8)** | — | snapshotSeq ≥ 0 | **pre-auth¹** | — |
| 7 | CURSOR_ACK | 0x07 | C→S | **yes (8)** | — | seq ≥ 0 | **pre-auth** | — |
| 8 | HEARTBEAT | 0x08 | S→C | **yes (16)** | — | serverNowMillis ≥ 0 | **pre-auth¹** | — |
| 9 | ERROR_CLOSE | 0x09 | both | no | message | code∈taxonomy; msgLen ≤ remaining | **pre-auth¹** | — |
| 10 | WATCH_CREATE | 0x0A | C→S | no | path, cursor vec, flags | path/cursor bounded; scope/kind/flags u8; FULL⇒empty path | **pre-auth** (0x02) | — |
| 11 | WATCH_CANCEL | 0x0B | C→S | **yes (8)** | — | (opaque u64) | **pre-auth** (0x02) | — |
| 12 | WATCH_CREATED | 0x0C | S→C | no | shards[] | `count·13 ≤ remaining`; mode ord; latestSeq ≥ 0 | **pre-auth¹** (0x02) | — |
| 13 | WATCH_EVENT | 0x0D | S→C | no | changes[] (key,kind,val) | `count·9 ≤ remaining`; key/val len ≤ remaining | **pre-auth¹** (0x02) | — |
| 14 | WATCH_PROGRESS | 0x0E | S→C | no | cursor vec | cursor bounded | **pre-auth¹** (0x02) | — |
| 15 | WATCH_CANCELED | 0x0F | S→C | no | opt cursor, message | code∈taxonomy; hasOldest∈{0,1}; msgLen ≤ remaining | **pre-auth¹** (0x02) | — |
| 16 | WATCH_SNAPSHOT_BEGIN | 0x10 | S→C | **yes (28)** | — | seq/chunkCount/totalBytes ≥ 0 | **pre-auth¹** (0x02) | G4 no cross-check |
| 17 | WATCH_SNAPSHOT_CHUNK | 0x11 | S→C | no | bytes (rest) | `len ≤ 1 MiB` | **pre-auth¹** (0x02) | — (svr); **G3 client**|
| 18 | WATCH_SNAPSHOT_END | 0x12 | S→C | **yes (20)** | — | snapshotSeq ≥ 0 | **pre-auth¹** (0x02) | — |

¹ "pre-auth¹" = a **server→client** frame that a *client* can nonetheless send inbound; the
server codec fully decodes and allocates it **before** `onInboundFrame` rejects the unexpected
type as `PROTOCOL_VIOLATION` (§Pre-auth). So its decode-time allocation IS attacker-reachable.

---

## Per-frame layouts

Sequence/timestamp u64 fields (cursor `S`, WATCH_EVENT `S`/`commitTs`, `latestSeq`, snapshot
`snapshotSeq`/`totalBytes`, oldest-vector `S`) are validated **≥ 0** by their record ctors, so
their effective range is `[0, 2^63)`; a high-bit-set value decodes as `FRAME_CORRUPT`
(`EdgeFrameCodec.java:847-859`). `watchId` and `gid` are opaque full-range u64/u32 (no sign check).

### 1 — SUBSCRIBE (0x01) · C→S · decode `decodeSubscribe` `EdgeFrameCodec.java:696`
Payload:
```
[fullStore u8][prefixCount u32] ( [prefixLen u32][prefix bytes] ){prefixCount}
[topologyEpoch u64][resumeCursor u64][failoverResumeCursor u64]
[edgeIdLen u32][edgeId bytes]
[acceptsFiltered u8]        // ONLY under 0x03 (encode :315-317, decode :718-724)
```
Bounds/validation:
- `prefixCount < 0 || prefixCount > p.remaining()` → `FRAME_CORRUPT` (`:699-701`). **Loose**: bound is in *bytes*, not *elements* (a prefix is ≥4 bytes), unlike the tight `count·MIN` used by watch decoders. → **Gap G1**.
- each prefix via `readString` — `remaining ≥ 4`, then `len ≤ remaining` (`:1066-1078`).
- `topologyEpoch == 0` reserved-illegal → `FRAME_CORRUPT` (`:707-711`); note the prefix list is fully read **before** this check.
- `edgeId` via `readString`. Under `0x03`, `remaining ≥ 1` for the opt-in byte (`:719-722`).
- record ctor (`EdgeFrame.java:99-123`): `fullStore ⇒ prefixes empty`; `fullStore ⇒ !acceptsFiltered`; `topologyEpoch ≥ 1`; `resumeCursor ≥ 0`; `failoverResumeCursor ≥ −1` (−1 = absent). Ctor `IllegalArgumentException` mapped to `FRAME_CORRUPT` (`:728-729`).

### 2 — SUBSCRIBE_OK (0x02) · S→C · decode `decodeSubscribeOk` `:733`
```
[latestSeq u64][mode u8] [filtered u8]   // filtered ONLY under 0x03 (:743-749)
```
`modeOrd >= Mode.values().length` → `FRAME_CORRUPT` (`:737-739`). `0x03` needs `remaining ≥ 1`.

### 3 — NOTIFY (0x03) · S→C · decode `decodeNotify` `:753`
```
[count u32]
( [seq u64][commitTs u64][fromVersion u64][toVersion u64]
  [batchLen u32][batch bytes]                       // CommandCodec.encodeBatch blob
  [sigLen i32][sig bytes?]                           // sigLen == -1 ⇒ null (no sig); else >=0
  [epoch u64][nonceLen u32][nonce bytes]
){count}
```
Bounds: `count < 0 || count > 64` → `FRAME_CORRUPT` (`:755-757`). Per notification (`:765-799`):
`batchLen < 0 || > remaining` (`:771`); `sigLen == -1` null-sentinel else `< 0 || > remaining`
(`:783`); `nonceLen < 0 || > remaining` (`:791`). `batch` is re-decoded via
`CommandCodec.decode` (`:802-813`; its own caps out of edge scope) — a `Noop`/empty batch →
`FRAME_CORRUPT` (`:810-811`). **`MAX_NOTIFY_BATCH_BYTES` (256 KiB) is NOT enforced on decode**
(encode-only, `:341-345`) → **Gap G2** (bounded only by the 2 MiB frame cap).

### 4 — SNAPSHOT_BEGIN (0x04) · S→C · decode `decodeSnapshotBegin` `:815` · **fixed 20 B**
```
[snapshotSeq u64][chunkCount u32][totalBytes u64]
```
Ctor (`EdgeFrame.java:232-244`): all three ≥ 0. **No cross-field check** that `chunkCount` /
`totalBytes` agree with the chunk stream that follows → **Gap G4** (client trusts the stream, see G3).

### 5 — SNAPSHOT_CHUNK (0x05) · S→C · decode `decodeSnapshotChunk` `:822`
```
[index u32][bytes … rest-of-frame]
```
`len = remaining`, `len > 1 MiB` → `FRAME_TOO_LARGE` (`:825-829`); `new byte[len]` (`:830`).
`index ≥ 0` via ctor (`EdgeFrame.java:261-267`). Server-side per-frame allocation is bounded (≤1 MiB); **client-side accumulation is not** → **Gap G3**.

### 6 — SNAPSHOT_END (0x06) · S→C · decode inline `:680` · **fixed 8 B**
```
[snapshotSeq u64]
```
`snapshotSeq ≥ 0` (`EdgeFrame.java:309-315`).

### 7 — CURSOR_ACK (0x07) · C→S · decode inline `:681` · **fixed 8 B**
```
[seq u64]
```
`seq ≥ 0` (`EdgeFrame.java:330-336`). The only routine post-SUBSCRIBE client→server frame.

### 8 — HEARTBEAT (0x08) · S→C · decode inline `:682` · **fixed 16 B**
```
[latestSeq u64][serverNowMillis u64]
```
`serverNowMillis ≥ 0` (`EdgeFrame.java:351-357`).

### 9 — ERROR_CLOSE (0x09) · either · decode `decodeErrorClose` `:835`
```
[code u8][msgLen u32][msg bytes]
```
`ErrorCode.fromCode(code)` — unknown → `FRAME_CORRUPT` (`:838-842`); `msg` via `readString` (bounded).

### 10 — WATCH_CREATE (0x0A) · C→S · 0x02-only · decode `decodeWatchCreate` `:894`
```
[watchId u64][scope u8][targetKind u8][pathLen u32][path bytes]
[cursor: topologyEpoch u64][count u32] ( [gid u32][S u64] ){count}
[flags u8]
```
Bounds: `path` via `readBytes` (`remaining ≥ 4`, `len ≤ remaining`, `:1052-1064`); `cursor`
via `decodeCursor` (§Cursor); after cursor `remaining ≥ 1` for flags (`:900-902`). Ctor
(`EdgeFrame.java:411-431`): `scope/targetKind/flags ∈ [0,255]`; `WATCH_TARGET_FULL (2) ⇒ path
empty`. Ctor throw → `FRAME_CORRUPT` (`:906-907`). (Path-grammar validation is a session-layer
`BAD_SUBSCRIBE` concern, not the codec's.)

### 11 — WATCH_CANCEL (0x0B) · C→S · 0x02-only · decode inline `:685` · **fixed 8 B**
```
[watchId u64]
```
Opaque u64 (no sign check).

### 12 — WATCH_CREATED (0x0C) · S→C · 0x02-only · decode `decodeWatchCreated` `:911`
```
[watchId u64][shardCount u32] ( [gid u32][latestSeq u64][mode u8] ){shardCount}
```
`count < 0 || (long)count·13 > remaining` → `FRAME_CORRUPT` (`:914-916`, `SHARD_MODE_BYTES=13`
`:413`). Per shard: `modeOrd ≥ Mode.values().length` → `FRAME_CORRUPT` (`:923-925`); `latestSeq
≥ 0` via `ShardMode` ctor (`EdgeFrame.java:745-752`).

### 13 — WATCH_EVENT (0x0D) · S→C · 0x02-only · decode `decodeWatchEvent` `:931`
```
[watchId u64][gid u32][S u64][commitTs u64][changeCount u32]
( [keyLen u32][key bytes][kind u8][valLen i32][val bytes?] ){changeCount}
```
`valLen == −1` ⇒ DELETE (null value, the sole **signed** length sentinel); `valLen ≥ 0` ⇒ PUT
value present (`0` = empty value). Bounds: `count < 0 || (long)count·9 > remaining` →
`FRAME_CORRUPT` (`:940-942`, `MIN_CHANGE_BYTES=9` `:416`). Per change: `key` via `readBytes`
(bounded); `valLen == −1` null / `< 0` `FRAME_CORRUPT` / `> remaining` `FRAME_CORRUPT`
(`:947-960`). `WatchChange` ctor (`EdgeFrame.java:771-789`) couples kind↔value (PUT non-null,
DELETE null); mismatch → `FRAME_CORRUPT` (`:962-966`). `WatchEvent` ctor: `S ≥ 0`, `commitTs ≥ 0`.

### 14 — WATCH_PROGRESS (0x0E) · S→C · 0x02-only · decode `decodeWatchProgress` `:975`
```
[watchId u64][cursor][serverNowMillis u64]
```
`cursor` via `decodeCursor`; `serverNowMillis ≥ 0` via ctor.

### 15 — WATCH_CANCELED (0x0F) · S→C · 0x02-only · decode `decodeWatchCanceled` `:986`
```
[watchId u64][code u8][hasOldest u8][cursor iff hasOldest==1][msgLen u32][msg bytes]
```
`ErrorCode.fromCode` (`:989-993`); `hasOldest ∈ {0,1}` else `FRAME_CORRUPT` (`:995-1003`);
optional `cursor` via `decodeCursor`; `msg` via `readString`.

### 16 — WATCH_SNAPSHOT_BEGIN (0x10) · S→C · 0x02-only · decode `:1008` · **fixed 28 B**
```
[watchId u64][gid u32][snapshotSeq u64][chunkCount u32][totalBytes u64]
```
Ctor (`EdgeFrame.java:631-644`): `snapshotSeq/chunkCount/totalBytes ≥ 0`. Same no-cross-check as SNAPSHOT_BEGIN (**G4**).

### 17 — WATCH_SNAPSHOT_CHUNK (0x11) · S→C · 0x02-only · decode `:1021`
```
[watchId u64][gid u32][index u32][bytes … rest-of-frame]
```
`len = remaining`, `> 1 MiB` → `FRAME_TOO_LARGE` (`:1026-1030`); `index ≥ 0` via ctor. Client accumulation (**G3**).

### 18 — WATCH_SNAPSHOT_END (0x12) · S→C · 0x02-only · decode `:1040` · **fixed 20 B**
```
[watchId u64][gid u32][snapshotSeq u64]
```
`snapshotSeq ≥ 0` via ctor.

### Cursor (shared by WATCH_CREATE / WATCH_PROGRESS / WATCH_CANCELED-oldest) · `decodeCursor` `:869`
```
[topologyEpoch u64][count u32] ( [gid u32][S u64] ){count}
```
- `remaining < 12` (`CURSOR_MIN_BYTES`, `:410`) → `FRAME_CORRUPT` (`:871-873`) — the RT-5 floor, never an uncaught underflow.
- `topologyEpoch == 0` reserved-illegal → `FRAME_CORRUPT` (`:875-878`).
- `count < 0 || (long)count·12 > remaining` (`CURSOR_COMPONENT_BYTES=12`, `:407`) → `FRAME_CORRUPT` (`:879-882`) — the `(long)` cast binds before the multiply (no overflow).
- `WatchCursor` ctor (`WatchCursor.java:64-81`): epoch ≥ 1; components strictly ascending by **unsigned** gid (dup/out-of-order → reject); `Component` ctor `S ≥ 0` (`:127-132`). All mapped to `FRAME_CORRUPT` (`:889-890`).

### Snapshot body (carried inside SNAPSHOT_CHUNK / WATCH_SNAPSHOT_CHUNK payloads) · `EdgeSnapshotCodec`
Body (no format version of its own; versioned by the enclosing frame — `EdgeSnapshotCodec.java:33-42`):
```
[snapshotSeq u64][entryCount u32] ( [keyLen u32][key][valLen u32][val] ){entryCount}
```
- `serialize` caps each key/value at `MAX_ENTRY_FIELD_BYTES = 1 MiB` (`:45-46,76-83`); total body ≤ `Integer.MAX_VALUE` (`:86-88`).
- `chunk(body, chunkBytes)`: `chunkBytes ∈ [1, 1 MiB]` (`:120-124`).
- `reassemble`: chunk indices must be the contiguous run `0..n−1` (`:150-160`); **total ≤ `Integer.MAX_VALUE` (~2 GiB)** (`:163-165`) — the ceiling is enforced only **after** full accumulation (see **G3**).
- `deserialize`: `remaining ≥ 12` (`:192-195`); `entryCount ≥ 0` (`:198-200`); each key/val via `readBoundedLen` — `len ∈ [0, 1 MiB]` and `≤ remaining` (`:216-233`). Bounded by the reassembled body size, which is bounded only by the 2 GiB `reassemble` ceiling.

---

## Pre-auth surface (client-facing resource-exhaustion — Gate 2's priority)

**Transport auth model.** The Netty fan-out pipeline is `SslHandler → ByteToEdgeFrameDecoder →
EdgeFrameToByteEncoder → conn` (`NettyFanOutServer.java:273-279`) with
`setNeedClientAuth(true)` (`:297`). So with mTLS configured, **no application frame is decoded
until the mTLS handshake completes** — the decoded-frame sender is always a **cert-verified**
peer. "Pre-auth" on this plane therefore has two distinct meanings:

1. **Pre-authentication (no mTLS):** `tlsManager == null` ⇒ plaintext, no `SslHandler`
   (`:274`, "test/single-node"). Here the entire decode surface is reachable by any anonymous
   TCP peer. This is the worst case and the one Gate 2 must reason about for untrusted networks.
2. **Pre-authorization (mTLS on):** the ACL gate (`WatchAuthorizer`) runs **after** the codec
   has fully decoded and allocated the frame — SUBSCRIBE is authorized in `admitLegacySubscribe`
   (`FanOutConnectionDriver.java:426-432`) and WATCH_CREATE in `handleWatchCreate` (`:557`),
   both *after* `onInboundFrame` receives an already-decoded `EdgeFrame`. So **every codec-level
   allocation is pre-authorization** even under mTLS: a cert-valid-but-unauthorized client
   reaches it.

**Direction is NOT enforced at decode.** `ByteToEdgeFrameDecoder` calls `EdgeFrameCodec.decode`
with no restriction to the client→server subset (`ByteToEdgeFrameDecoder.java:64,68`). A client
may send **any** frame type — including server→client-only types (NOTIFY, SNAPSHOT_*,
WATCH_EVENT, WATCH_CREATED, …). The codec fully decodes and allocates it; only afterward does
`onInboundFrame` reject the unexpected type (`FanOutConnectionDriver.java:414,500,513,515`) as
`PROTOCOL_VIOLATION`. A client can also make its **first** frame a `0x02` WATCH_* frame: the
decoder accepts any version on the first frame and pins to it (`ByteToEdgeFrameDecoder.java:62-65`),
so the frame decodes (allocating its change/shard lists) before the driver tears the connection
down. **Net: the pre-auth allocation surface is the union of all 9 legacy decode paths and,
after a 0x02/0x03 first frame, all 9 watch decode paths — i.e. every frame in this catalog.**

**What already mitigates it:**
- Connection cap applied **before** the handshake: `liveConnections.incrementAndGet() > maxSessions` in `channelActive` (`NettyFanOutServer.java:376-381`) — half-open handshakes count, so a slowloris cannot exhaust fds/threads. Default `maxSessions = 1024`.
- Every frame buffer ≤ 2 MiB via `peekLength` before allocation; every internal length/count ≤ remaining before its allocation. So per-frame heap is bounded (2 MiB, except the amplifying G1 case).

**What does NOT (Gate 2 territory, cross-refs lead-notes §1):**
- **No pre-SUBSCRIBE handshake deadline / idle reaper** on the Netty fan-out pipeline (unlike Raft/HTTP transports which install `IdleStateHandler`). A peer that completes mTLS then stalls parks a connection + up to a 2 MiB cumulator, bounded only by `maxSessions`. (Transport-level, not a codec gap — noted for completeness; owned by lead-notes item 1.)

---

## Allocation / bound gap list

Ordered by relevance to Gate 2. **None of the server-ingress codec paths is strictly
unbounded** (all gated by the 2 MiB frame cap + `len ≤ remaining`); the one genuinely unbounded
allocation is client-side and cross-frame (**G3**).

- **G1 — SUBSCRIBE `prefixCount`: bounded-but-amplifying preallocation (pre-auth).**
  `decodeSubscribe` bounds `prefixCount ≤ p.remaining()` (`EdgeFrameCodec.java:699`) — a **loose,
  byte-denominated** bound, inconsistent with the tight `(long)count·MIN_BYTES ≤ remaining` used
  by every watch decoder (cursor `:880`, shards `:914`, changes `:940`). A minimum prefix is 4
  bytes (a zero-length string), so from one 2 MiB frame an attacker can declare `prefixCount ≈
  2.1M`, forcing `new ArrayList<>(prefixCount)` = an `Object[~2.1M]` (~8–17 MB) **before** the
  read loop, and — if the frame is padded with `prefixCount ≈ remaining/4` empty strings — up to
  ~0.5M `String`+`byte[0]` objects (~30 MB retained). A **~8–16× heap-vs-wire amplification**,
  reachable pre-authorization (and pre-authentication in plaintext). Not unbounded, but the
  sharpest codec amplifier. **Fix candidate:** bound `(long)prefixCount·4 > remaining` for parity
  with the watch decoders; consider a `MAX_PREFIXES` cap. (Keep golden fixtures green — valid
  frames unchanged.)

- **G3 — SNAPSHOT_CHUNK / WATCH_SNAPSHOT_CHUNK: UNBOUNDED client-side accumulation.**
  `EdgeClientCore.onSnapshotChunk` appends every chunk to `pendingChunks` with **no bound on
  chunk count or accumulated bytes** and **without consulting `SnapshotBegin.chunkCount`**
  (`EdgeClientCore.java:541-548`, list declared `:191`). The only ceiling is `reassemble`'s
  `total ≤ Integer.MAX_VALUE` (~2 GiB), enforced **after** full accumulation
  (`EdgeSnapshotCodec.java:163-165`) — i.e. an adversarial sender can drive the edge heap toward
  2 GiB (≥2048 × 1 MiB chunks) before any check fires → **OOM on the edge node**. Threat model:
  this is a **server→client** channel; under mTLS the sender is a cert-verified *distribution
  server*, so exploitation requires a **compromised/malicious server or a plaintext deployment**
  (lower likelihood than the client-facing G1, but a true unbounded gap). **Fix candidate:** cap
  `pendingChunks` at `SnapshotBegin.chunkCount` (reject the `chunkCount+1`-th chunk) and cap
  accumulated bytes at `SnapshotBegin.totalBytes` (both already on the wire, frame 4/16), with a
  hard ceiling. Verify the WATCH_SNAPSHOT_CHUNK accumulation path in the watch veneer mirrors
  this fix.

- **G2 — NOTIFY `MAX_NOTIFY_BATCH_BYTES` asymmetry.** The 256 KiB payload cap is enforced only
  at **encode** (`EdgeFrameCodec.java:341-345`); **decode enforces only `count ≤ 64` and
  `len ≤ remaining`** (`:755,771,783,791`). A decoded NOTIFY may therefore reach ~2 MiB (the
  frame cap), 8× the encoder's own limit. Bounded (no OOM), but a spec/enforcement asymmetry a
  strict codec should close for canonical-encoding parity. **Fix candidate:** enforce the 256 KiB
  payload sum on decode too.

- **G4 — SNAPSHOT_BEGIN / WATCH_SNAPSHOT_BEGIN: no cross-field validation.** `chunkCount` and
  `totalBytes` are validated only `≥ 0` individually (`EdgeFrame.java:232-244,631-644`); the
  codec never checks the following chunk stream against them. Harmless in isolation, but it is the
  missing invariant that would let the client bound its accumulation cheaply — tightly coupled to
  the **G3** fix (use these declared values as the accumulation cap).

### Non-gaps confirmed (defense-in-depth already present)
- Length-before-allocation via `peekLength` at both readers (server `ByteToEdgeFrameDecoder.java:56`, edge `EdgeStreamClient.java:611`).
- CRC-before-interpret; `length == data.length`; strict trailing-byte rejection after payload (`EdgeFrameCodec.java:659-662`).
- Every internal length/string bounded `≤ remaining` before `new byte[]` (`readBytes` `:1052`, `readString` `:1066`; NOTIFY `:771,783,791`; snapshot chunk `:825,1026`).
- Watch count fields use the tight `(long)count·MIN_BYTES` pre-check (cursor/shards/changes) — overflow-safe.
- Cursor min-length floor (12 B) prevents underflow; reserved epoch `0` fail-closed (`:871-878`).
- Snapshot body per-entry key/val capped at 1 MiB with `≤ remaining` re-check on deserialize (`EdgeSnapshotCodec.java:216-233`).
- Per-connection version pin: mixed-version mid-connection = `BAD_WIRE_VERSION` (`ByteToEdgeFrameDecoder.java:66-69`).
- Pre-handshake connection cap (`maxSessions`) blunts slowloris fd/thread exhaustion.

---

## Pre-auth-reachable frame inventory (attacker = any client; plaintext = anonymous, mTLS = cert-valid-unauthorized)
All 18 frame **decode** paths are reachable pre-authorization, because (a) the server decoder
does not restrict inbound frames by direction and (b) the version pin is set by the attacker's
own first frame. The genuinely *routed* client→server frames are **SUBSCRIBE (0x01),
CURSOR_ACK (0x07), WATCH_CREATE (0x0A), WATCH_CANCEL (0x0B)** (and ERROR_CLOSE 0x09, either
direction); the remaining 13 server→client types are decode-then-`PROTOCOL_VIOLATION`, but their
**decode-time allocation still runs pre-auth**. The highest-value pre-auth target is **SUBSCRIBE
(G1)**; the highest-severity overall is the client-side **G3**.
