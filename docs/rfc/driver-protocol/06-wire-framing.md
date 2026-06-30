# Configd Driver Protocol RFC — §06: Wire Framing & Transport

**Status: DRAFT (2026-06-30). Docs-only; normative; BYTE-LEVEL.** Sixth section of the Configd driver-protocol
RFC. This section specifies the **binary edge wire** to the byte: the **EdgeFrame envelope**, the **nine v1
frame payloads** (`0x01`–`0x09`), the **nested blobs** the watch section deferred (the `CommandCodec` batch
inside `NOTIFY`, the ADR-0028 snapshot body inside `*_SNAPSHOT_CHUNK`), the shared **cursor-vector** codec, the
**first-frame version pin** (the real "negotiation"), the **`u64 < 2^63`** field constraint, the **TLS
profile**, the **connection lifecycle, flow-control, and caps**, and the **fail-closed forward-compatibility**
rules. A driver implementing this section produces and consumes bytes **identical** to the deployed codec.

**This section is validated byte-for-byte against the implementation and its golden fixtures.** The authority
is [`EdgeFrameCodec.java`](../../../configd-distribution-service/src/main/java/io/configd/distribution/wire/EdgeFrameCodec.java)
and the **golden vectors** in
[`EdgeFrameGoldenBytes.java`](../../../configd-distribution-service/src/test/java/io/configd/distribution/wire/EdgeFrameGoldenBytes.java)
(CRC-correct frames per type, pinned by `EdgeFrameCodecGoldenFixtureTest` for v1 and
`EdgeFrameCodecV2GoldenFixtureTest` for v2). **Every layout here MUST match those golden bytes**; the fixture
name is cited next to each frame so a driver author can use the golden bytes as the cross-language conformance
vector. **Wire-format ADR note:** [`adr-0029-wire-format-v1.md`](../../decisions/adr-0029-wire-format-v1.md) is
the canonical origin of the framing **discipline** — a version byte, a CRC32C-Castagnoli trailer, CRC-before-
type validation, and fail-closed forward-compat — but its *concrete* diagram is the **Raft** frame
(`HEADER_SIZE = 18`, 16 MiB, with Group-Id/Term). The **edge** layout (`HEADER_SIZE = 6`, 2 MiB, no group/term)
is `EdgeFrameCodec`'s own (the edge codec attributes it to the ADR-0037 transport decision). **Take the byte
layout from F2-1 here, not from ADR-0029's Raft diagram**; ADR-0028 is the snapshot body. Where this section
and a prior RFC claim disagree, **the code wins**. This section is **normative**; it **composes with**:

- [`02-watches.md`](02-watches.md) — the **`0x02` watch frames `0x0A`–`0x12`** and the cursor mechanics. §06
  **completes** what §02 deferred: the envelope, the `0x01`–`0x09` payloads, and the nested
  `CommandCodec`/snapshot blobs. The cursor codec (F8) is the one §02 §3 (W3-5) references.
- [`03-authentication.md`](03-authentication.md) — the **mTLS** handshake is the edge authentication (AU3-2);
  §06 F9 gives its concrete TLS profile.
- [`07-errors.md`](07-errors.md) — the `ErrorCode` taxonomy carried in `ERROR_CLOSE`
  (`0x09`) and `WATCH_CANCELED` (`0x0F`). §06 specifies the *byte* (a `u8` code 1–11); §07 will own the
  *meaning* and the driver reaction.
- [`00-overview.md`](00-overview.md) — the two-plane architecture.

Clauses are referenced as **`F<n>-<m>`** (the framing-section clause prefix; parallel to §1 `A`, §2 `W`, §3
`AU`, §4 `D`, §5 `R`), so the composed RFC has no clashing identifiers.

---

## 1. Conventions, scope, versioning

### 1.1 Requirement keywords

The keywords **MUST**, **MUST NOT**, **REQUIRED**, **SHALL**, **SHALL NOT**, **SHOULD**, **SHOULD NOT**,
**MAY**, and **OPTIONAL** are to be interpreted as in RFC 2119 / RFC 8174.

### 1.2 Scope and conventions

This section specifies the **binary edge transport** (the fan-out / watch / streaming-read plane), **not** the
HTTP control plane (§04). **All multi-byte integers are big-endian** unless stated. `u8`/`u16`/`u32`/`u64`/
`i32` denote unsigned/signed fixed-width integers. Lengths are byte counts. "The wire" is what crosses the TLS
connection after the handshake.

### 1.3 Versioning — first-frame pin, not negotiation

The edge wire is versioned by a **1-byte version stamp on every frame** (`0x01` or `0x02`), **pinned by the
first frame of the connection** (F4). There is **no** hello/capabilities frame and **no** negotiation
round-trip. This **corrects the aspirational "negotiated at connection setup" language** of §02 (W1-2 / W1-3,
and W5-11 "Wire version and negotiation"): the real mechanism is **first-frame-wins + pin + fail-closed** (F4).

---

## 2. The EdgeFrame envelope (byte-for-byte)

**F2-1 (the envelope).** Every frame is a length-prefixed, CRC-trailered envelope
(`EdgeFrameCodec` :21–27, :73–101):

```
 offset  size  field        notes
 0       4     Length  u32  BIG-ENDIAN; covers the WHOLE frame (Length + Version + Type + Payload + CRC)
 4       1     Version u8   0x01 (built) | 0x02 (watch-capable)
 5       1     Type    u8   FrameType code (F6 / §02)
 6       L-10  Payload      type-specific (F6, §02)
 L-4     4     CRC32C  u32  BIG-ENDIAN; Castagnoli (CRC-32C) over bytes [0, L-4) — i.e. Length..end-of-payload
```

where `L` is the `Length` value. **`HEADER_SIZE = 6`** (Length+Version+Type), **`TRAILER_SIZE = 4`** (CRC),
**minimum frame = 10 bytes**, **`MAX_EDGE_FRAME_SIZE = 2 MiB`** (`2 * 1024 * 1024`). The `Length` field
**includes its own 4 bytes** and the CRC; it is the total frame size. The CRC is **CRC-32C (Castagnoli)** —
**not** the IEEE/zlib CRC-32 — over **`[0, L-4)`** (the length, version, type, and payload bytes), trailing as
the last 4 bytes. A driver **MUST** use CRC-32C (the polynomial `java.util.zip.CRC32C` implements); the
IEEE CRC-32 produces a different trailer and every frame would be rejected.

**F2-2 (golden anchor).** Each frame layout in this section **MUST** reproduce the corresponding
`EdgeFrameGoldenBytes` vector exactly. Worked example (`subscribe_ok_tail.bin`, the `SUBSCRIBE_OK` fixture):
`00000013` (L = 19) · `01` (v1) · `02` (`SUBSCRIBE_OK` type) · `00000000 00003039` (`latestSeq` = 12345) · `00`
(mode) · `429ac9fb` (CRC32C). A driver's encoder, fed the same fields, **MUST** emit these 19 bytes including
the identical CRC.

**F2-3 (length covers the whole frame; one frame per buffer).** A decoder reads the 4-byte `Length`, **bounds-
checks it BEFORE allocating** the frame buffer (`ByteToEdgeFrameDecoder` / `peekLength`), then consumes
exactly `Length` bytes as one frame. The decoded `Length` **MUST** equal the actual frame size; a mismatch is
`FRAME_CORRUPT` (F3). A driver **MUST** length-frame on the stream and **MUST NOT** assume frames align to TLS
records or TCP segments.

---

## 3. Decode and validation order (fail-closed)

**F3-1 (the exact order — a driver MUST follow it).** A decoder validates in this order
(`EdgeFrameCodec.decode` :557–640), mapping each failure to a streaming `ErrorCode` (§07):

1. `data.length ≥ 10` (else **`FRAME_CORRUPT`**).
2. read `Length` (u32 BE); `10 ≤ Length ≤ 2 MiB` — `Length > 2 MiB` ⇒ **`FRAME_TOO_LARGE`**, `Length < 10` ⇒
   **`FRAME_CORRUPT`**.
3. `Length == data.length` (else **`FRAME_CORRUPT`** — the buffer must hold exactly one frame).
4. **CRC32C over `[0, Length-4)` == trailer** (else **`FRAME_CORRUPT`**). **The CRC is verified BEFORE the
   version and type bytes are interpreted** — so a flipped version/type byte reads as corruption, never as a
   misleading `BAD_WIRE_VERSION`.
5. `Version ∈ {0x01, 0x02}` (else **`BAD_WIRE_VERSION`**); **and** if the connection is pinned (F4), `Version`
   == the pinned version (else **`BAD_WIRE_VERSION`**).
6. `Type` resolves to a known `FrameType` (else **`FRAME_CORRUPT`**); a `0x0A`–`0x12` watch type on a `0x01`
   frame ⇒ **`FRAME_CORRUPT`** (a watch frame is legal only under `0x02`).
7. decode the payload `[6, Length-4)`; **any** underflow / malformed field / out-of-range value ⇒
   **`FRAME_CORRUPT`**; **any trailing byte left after the payload ⇒ `FRAME_CORRUPT`** (F11).

**F3-2 (bounds before allocation — load-bearing).** A driver **MUST** bounds-check `Length` **and every inner
length/count prefix** — prefix count, `NOTIFY` count, batch length, cursor count, value length, **and the
server-controlled snapshot sizes (F7-2, F6-4)** — **against the remaining bytes (and a configured ceiling)
BEFORE allocating**, so a hostile or compromised peer cannot induce a giant allocation by lying in a length
field (`decodeCursor` :808–811, `decodeNotify` :693/:709, `decodeSubscribe` :667–669 all bound-then-allocate).
A value being "validated non-negative" (F5) is **not** an upper bound — the driver supplies the upper bound.

---

## 4. The first-frame version pin (the real "negotiation")

**F4-1 (first-frame-wins, then pinned for life).** There is **no** hello/capabilities frame and **no**
negotiation handshake (`ByteToEdgeFrameDecoder` :56–63). The connection's wire **version** is decided by the
**first frame's version byte** (offset 4), in practice:

- a first **`SUBSCRIBE`** (**type `0x01`**, a v1 frame) ⇒ the connection is pinned to **version `0x01`**
  (byte-identical to the legacy pre-watch path);
- a first **`WATCH_CREATE`** (**type `0x0A`**, a v2 frame) ⇒ the connection is pinned to **version `0x02`**.

> *Type vs. version (do not conflate):* `0x01`/`0x02` above are wire **versions** (offset 4); a frame's **type**
> code (offset 5) is separate — `SUBSCRIBE` = type `0x01`, `WATCH_CREATE` = type `0x0A` (and type `0x02` is
> `SUBSCRIBE_OK`). The pin keys on the first frame's **version byte**; the SUBSCRIBE/WATCH_CREATE split above is
> the conformant case, and F4-2 ("stamp one version on every frame") is the binding rule.

The first frame is decoded accepting **either** version (after CRC validation); its stamped version then
**pins** the connection. **Every subsequent frame MUST carry the pinned version**; a frame stamped with the
other accepted version (a `0x02` frame on a `0x01`-pinned connection, or vice-versa) **fails closed** with
**`BAD_WIRE_VERSION`**. There is **no downgrade** and no mid-connection re-pin.

**F4-2 (driver rule).** A driver **MUST** pick its wire version by its **first** frame and stamp **that same
version on every frame** for the connection's life. To use a different version it **MUST** open a new
connection. A driver **MUST NOT** mix `0x01` and `0x02` frames on one connection.

---

## 5. The `u64 < 2^63` field constraint (a true-`u64` driver footgun)

**F5-1 (the constrained fields — `[0, 2^63)`, high-bit ⇒ `FRAME_CORRUPT`).** Nearly **every sequence and
timestamp `u64` field** — on **both** the v1 (`0x01`–`0x09`) and v2 (`0x0A`–`0x12`) frames — is validated
**non-negative** by the record constructors (and that `IllegalArgumentException` is mapped to `FRAME_CORRUPT`
at `EdgeFrameCodec` :635–638). Their effective range is **`[0, 2^63)`**; a **high-bit-set** value **decodes as
`FRAME_CORRUPT`**. The constrained set is:

| Frame | Constrained field(s) | Source |
|---|---|---|
| `SUBSCRIBE` (`0x01`) | `resumeCursor` | `EdgeFrame.Subscribe` ctor :103 |
| `NOTIFY` (`0x03`) | `seq`, `commitTimestampMillis`, `fromVersion`, `toVersion`, `epoch` | `CommitNotification`/`ConfigDelta` ctors |
| `SNAPSHOT_BEGIN` (`0x04`) | `snapshotSeq`, `totalBytes` | `EdgeFrame.SnapshotBegin` ctor |
| `SNAPSHOT_END` (`0x06`) | `snapshotSeq` | ctor |
| **`CURSOR_ACK` (`0x07`)** | `seq` — **a CLIENT-emitted field** | ctor |
| `HEARTBEAT` (`0x08`) | `serverNowMillis` | ctor |
| cursor vector (F8) | `S` | `WatchCursor.Component` :74–79 |
| `WATCH_EVENT` (`0x0D`) | `S`, `commitTs` | §02 |
| `WATCH_CREATED` (`0x0C`) | `ShardMode.latestSeq` (per-shard sub-record) | §02 |
| `WATCH_PROGRESS` (`0x0E`) | `serverNowMillis` | `EdgeFrame.WatchProgress` ctor :532 |
| `WATCH_SNAPSHOT_*` (`0x10`/`0x11`/`0x12`) | `snapshotSeq`, `totalBytes` | §02 |
| `WATCH_CANCELED` (`0x0F`) | oldest-vector `S` | §02 |

A driver in a language with a **true `u64`** (Rust, Go, C) **MUST** keep these fields within `[0, 2^63)` —
including the **client-emitted** `CURSOR_ACK.seq` and `SUBSCRIBE.resumeCursor` (emitting a high-bit value
produces a frame the server rejects as `FRAME_CORRUPT`).

**F5-2 (the genuinely raw / opaque fields).** Only these are **not** range-checked:

- **Truly raw `u64`** (any value, including high-bit): `SUBSCRIBE_OK.latestSeq` (`0x02`) and
  `HEARTBEAT.latestSeq` (`0x08`).
- **`SUBSCRIBE.failoverResumeCursor`** is **`[0, 2^63) ∪ { 0xFFFFFFFFFFFFFFFF }`**: the all-ones value (`= -1`
  as a signed long) is the **sole** legal high-bit value — the **"none" sentinel** — and **every other**
  high-bit pattern (`0x8000…0`…`0xFFFF…FE`) **decodes as `FRAME_CORRUPT`** (`EdgeFrame.Subscribe` ctor :106:
  `failoverResumeCursor < -1` throws). A driver **MUST** emit the `0xFFFF…FF` sentinel verbatim for "no
  failover cursor" and **MUST NOT** emit any other high-bit value.
- **Opaque full-range** (no constraint): `watch_id` (`u64`) and `gid` (`u32`).

> Net: a Rust/Go driver treats `resumeCursor` and `CURSOR_ACK.seq` like the F5-1 fields (keep `< 2^63`), uses
> only `0xFFFF…FF` as the failover sentinel, and may use the full `u64` range only for `watch_id` / the two
> `latestSeq` fields.

---

## 6. The nine v1 frame payloads (`0x01`–`0x09`, byte-for-byte)

All v1 frames are legal under `0x01` (and `NOTIFY`/`ERROR_CLOSE` are also reused under `0x02` — §02). Each
layout below is the **payload** (the bytes at offset 6, between the Type byte and the CRC). Golden fixture
names are cited for cross-language verification.

**F6-1 `SUBSCRIBE` (`0x01`)** — *client→server*; golden `subscribe_full_store.bin`, `subscribe_prefixes.bin`:

```
[1  u8 ] fullStore        (1 = whole-store subscription; 0 = prefix-filtered)
[4  u32] prefixCount
  repeated prefixCount times:
    [4 u32] prefixLen
    [prefixLen] prefix    (UTF-8)
[8  u64] resumeCursor          ([0, 2^63); high-bit ⇒ FRAME_CORRUPT — F5-1)
[8  u64] failoverResumeCursor  ([0, 2^63) ∪ {0xFFFFFFFFFFFFFFFF = none}; any other high bit ⇒ FRAME_CORRUPT — F5-2)
[4  u32] edgeIdLen
[edgeIdLen] edgeId        (UTF-8; ADVISORY — the server overrides identity with the mTLS cert DN, §03 AU3-2)
```

**F6-2 `SUBSCRIBE_OK` (`0x02`)** — *server→client*; golden `subscribe_ok_tail.bin`,
`subscribe_ok_snapshot_first.bin`:

```
[8 u64] latestSeq         (the shard's current applied-mutation seq; RAW u64 — F5-2)
[1 u8 ] mode              (0 = tail / resume-from-cursor; 1 = snapshot-first — the EdgeFrame.Mode ordinal)
```

**F6-3 `NOTIFY` (`0x03`)** — *server→client*; golden `notify_single_unsigned.bin`, `notify_batch_signed.bin`,
`notify_empty.bin`. A batch of commit notifications (the *encoder* caps it at **64** = `MAX_NOTIFY_BATCH` and
**256 KiB** = `MAX_NOTIFY_BATCH_BYTES`; the **decoder** enforces `count ≤ 64` but **not** the 256 KiB cap — a
driver **MUST** bound a received `NOTIFY` by the **2 MiB frame cap** (F3-2), not assume 256 KiB):

```
[4 u32] count
  repeated count times (one CommitNotification):
    [8  u64] seq                    (applied-mutation seq; [0,2^63) — F5-1)
    [8  u64] commitTimestampMillis  ([0,2^63))
    [8  u64] fromVersion            ([0,2^63))
    [8  u64] toVersion              ([0,2^63))
    [4  u32] batchLen
    [batchLen] mutationsBlob        (CommandCodec.encodeBatch — F7-1)
    [4  i32] sigLen                 (SIGNED: -1 = unsigned/no-signature sentinel; ≥0 = signature length)
    [sigLen] signature              (present only if sigLen ≥ 0; Ed25519, ADR-0027)
    [8  u64] epoch                  (signing epoch; [0,2^63))
    [4  u32] nonceLen
    [nonceLen] nonce                (may be 0-length = legacy; never the -1 sentinel)
```

> The `sigLen = -1` sentinel (a **signed** `i32`) distinguishes "unsigned" from "empty signature"; a driver
> doing `full_chain_verify` (§02) **MUST** decode the `mutationsBlob` with F7-1 to recompute the signing
> payload. `notify_empty.bin` is `count = 0`.

**F6-4 `SNAPSHOT_BEGIN` (`0x04`)** — *server→client*; golden `snapshot_begin.bin`:

```
[8 u64] snapshotSeq       ([0,2^63))
[4 u32] chunkCount        (server-controlled; a driver MUST bound it before pre-sizing — F3-2)
[8 u64] totalBytes        ([0,2^63); server-controlled; bound before pre-allocating — F3-2)
```

**F6-5 `SNAPSHOT_CHUNK` (`0x05`)** — *server→client*; golden `snapshot_chunk_small.bin` (and the 1-MiB at-cap
fixture pinned by `goldenCrc()`):

```
[4 u32] index             (0-based; chunks are reassembled in index order — F-reassembly below)
[bytes] chunkBytes        (NO inner length prefix — runs to end of payload = Length - 6(header) - 4(index)
                           - 4(CRC); ≤ 1 MiB = MAX_SNAPSHOT_CHUNK_BYTES; a slice of the ADR-0028 body, F7-2)
```

**F6-6 `SNAPSHOT_END` (`0x06`)** — *server→client*; golden `snapshot_end.bin`: `[8 u64] snapshotSeq`
(`[0,2^63)`).

**Snapshot reassembly (REQUIRED).** Across `SNAPSHOT_BEGIN` → `SNAPSHOT_CHUNK`* → `SNAPSHOT_END`, a driver
**MUST**: buffer chunks until `SNAPSHOT_END`; verify it received **exactly `chunkCount`** chunks; verify the
**reassembled length == `totalBytes`**; and **discard + re-subscribe** on any short / truncated / count-
mismatched snapshot — it **MUST NOT** apply a partial snapshot as complete (silent store divergence). Chunk
`index` is sequential and frames arrive in order over TCP, but a driver **SHOULD** order by `index` defensively.

**F6-7 `CURSOR_ACK` (`0x07`)** — *client→server*; golden `cursor_ack.bin`: `[8 u64] seq` (`[0,2^63)` — F5-1;
**client-emitted**). This is **mandatory flow-control**, not optional progress: see F10-3.

**F6-8 `HEARTBEAT` (`0x08`)** — *server→client*; golden `heartbeat.bin`: `[8 u64] latestSeq` (RAW — F5-2)
`[8 u64] serverNowMillis` (`[0,2^63)` — F5-1). A driver **SHOULD** treat a prolonged absence of `HEARTBEAT`
(beyond a configured read-idle deadline) as a liveness failure and reconnect (F10-3).

**F6-9 `ERROR_CLOSE` (`0x09`)** — *server→client* terminal; golden `error_*.bin` (codes 1–10):

```
[1 u8 ] code              (ErrorCode 1..11; §07 owns the meaning — the byte is the taxonomy value)
[4 u32] msgLen
[msgLen] message          (UTF-8; UNTRUSTED DIAGNOSTIC — see below)
```

> `error_bad_wire_version.bin` … `error_protocol_violation.bin` pin codes 1–10; `error_not_authorized.bin`
> pins code 11 (carried here as an `ERROR_CLOSE` in the v2 fixture so the byte value is pinned) — **but the
> live protocol carrier for the `NOT_AUTHORIZED` per-watch authz reject is `WATCH_CANCELED` (`0x0F`), not
> `ERROR_CLOSE`** (§02 W7-5; §07). The `message` is **untrusted, server-controlled, arbitrary bytes**: under a
> compromised/malicious server it may contain control characters, newlines, or ANSI escapes. A driver **MUST
> NOT** use it for control flow (branch on the numeric `code`), **MUST NOT** machine-parse it, and **MUST**
> sanitize/escape it before logging or display (log-forging / terminal-injection).

---

## 7. The nested blobs (`CommandCodec` and the ADR-0028 snapshot body)

These ride **inside** F6 payloads and §02 frames; a watch/snapshot driver needs their bytes.

**F7-1 (the `CommandCodec` batch blob — inside `NOTIFY.mutationsBlob` and `WATCH_EVENT`).** The mutation blob
is the `CommandCodec` encoding (`CommandCodec` :15–22, :55–117), big-endian:

```
[1 u8 ] type              (0x01 PUT | 0x02 DELETE | 0x03 BATCH; an empty 0-byte command = no-op/election)
 PUT (0x01):    [2 u16 keyLen][key UTF-8][4 i32 valueLen][value]   (valueLen ≤ 1 MiB)
 DELETE (0x02): [2 u16 keyLen][key UTF-8]
 BATCH (0x03):  [4 u32 count][ mutation ]*count                    (count ≤ 10000)
   where each mutation is a PUT or DELETE body as above, WITHOUT its own BATCH wrapper
```

A `NOTIFY`/`WATCH_EVENT` `mutationsBlob` is a **`BATCH`** (`0x03`) (the server always encodes
`encodeBatch`, even for one mutation). A `full_chain_verify` driver **MUST** decode this to reconstruct the
exact bytes the signature covers, and **MUST** bound `count`/`valueLen`/`keyLen` against the remaining bytes
(F3-2). **Note:** the key length here is a `u16` (max 65535) — comfortably **wider** than the upper-layer
1024-byte path limit (the 1024 limit is a path/target bound, not a `CommandCodec` field bound); the value
length is a **signed `i32`** bounded to `[0, 1 MiB]`.

**F7-2 (the ADR-0028 snapshot body — the reassembled `SNAPSHOT_CHUNK` bytes).** Concatenating the
`SNAPSHOT_CHUNK` `chunkBytes` in `index` order yields the ADR-0028 snapshot body
([`adr-0028-snapshot-on-disk-format.md`](../../decisions/adr-0028-snapshot-on-disk-format.md)), big-endian:

```
[8 u64] sequenceCounter
[4 u32] entryCount                (server-controlled; bound before allocating — F3-2)
  repeated entryCount times:
    [4 u32 keyLen][key UTF-8]      (bound keyLen before allocating)
    [4 u32 valueLen][value]        (bound valueLen before allocating)
[optional trailer — three accepted forms, detected at the END of the body]:
    if the remaining bytes begin with magic 0xC0FD7A11:
        [4 u32 magic=0xC0FD7A11][4 u32 payloadLen][payloadLen bytes of TLV records]
            each TLV record: [2 u16 tag][4 u32 valueLen][value]
            defined: tag 0x0001 = signing_epoch (8-byte BE long); a reader MUST SKIP unknown tags (≥ 0x0002)
    else if EXACTLY 8 bytes remain (no magic):
        a legacy raw [8-byte BE long] signing_epoch (ADR-0028 iter-1 form)
    else (no bytes remain):
        no trailer
```

> **Three-way trailer detection (ADR-0028 backward-compat):** `magic ⇒ TLV` / `exactly 8 bytes, no magic ⇒
> raw epoch long` / `else ⇒ none`. The edge typically serves **freshly-produced** snapshots (TLV or none); a
> defensive driver **SHOULD** implement all three so a legacy on-disk snapshot does not mis-parse. Bound
> `payloadLen` and each `valueLen` before allocating (F3-2).
>
> **Contrast (load-bearing, F11):** the snapshot body's TLV trailer is **extensible** — a reader skips unknown
> tags. This is the **opposite** of the EdgeFrame *envelope*, which is **fixed-positional and fail-closed**
> (trailing bytes ⇒ `FRAME_CORRUPT`). A driver **MUST** apply skip-unknown to the *snapshot trailer* and
> reject-unknown to the *frame*.

---

## 8. The cursor-vector codec (shared by §1 list and §2 watch)

**F8-1.** A resume cursor is a **per-shard vector**, encoded (`encodeCursorInto` :407–414 / `decodeCursor`
:804–819):

```
[4 u32] count
  repeated count times:
    [4 u32] gid      (shard group id; compared/ordered UNSIGNED; opaque full-range)
    [8 u64] S        (applied-mutation seq processed; [0, 2^63) — F5-1)
```

**F8-2 (invariants — a driver MUST honor).** Components **MUST** be **strictly ascending by UNSIGNED `gid`**
(no duplicates); a duplicate or out-of-order `gid`, or a negative `S`, **decodes as `FRAME_CORRUPT`**
(`WatchCursor` constructor → mapped at :818). `count = 0` is the **empty "from now per shard"** cursor (start
at each shard's current `S`) — **not** "replay all history". A driver **MUST** encode the cursor as a vector
**even at N = 1** (the one-element `(gid=0, S)`); a scalar-cursor assumption is **FORBIDDEN** (§1 A9-1 / §2
W1-1) because it silently breaks when the cluster shards. (This is the cursor §02 §3 / W3-5 references; §06
pins its bytes.)

---

## 9. The TLS profile

**F9-1 (mTLS REQUIRED).** When TLS is enabled, the edge endpoint **requires a client certificate** —
`setNeedClientAuth(true)` (`FanOutServer`/`NettyFanOutServer`). Plaintext is permitted **only** for
single-node/test (matching the Raft transport policy). A **production** driver **MUST** require TLS and **MUST
NOT** silently fall back to plaintext (a downgrade footgun on an untrusted network). A driver **MUST** present
a client certificate during the TLS handshake (this is the edge **authentication**, §03 AU3-2).

**F9-2 (TLSv1.3-only; cipher suites).** The profile is **TLSv1.3-only** (`TlsManager`:
`SSLContext.getInstance("TLSv1.3")`) with cipher suites **`TLS_AES_256_GCM_SHA384`** and
**`TLS_AES_128_GCM_SHA256`** (`TlsConfig` defaults; suite **order is not server-pinned** —
`setUseCipherSuitesOrder` is not set — so either may be selected; both are equally strong AEAD). A driver
**MUST** support TLSv1.3 and **MUST** offer at least one of these suites; it **MUST NOT** expect TLS 1.2 or a
non-listed suite.

**F9-3 (identity = verified client-cert Subject DN).** The server's authoritative client identity is the
**verified client certificate Subject DN**; any `edgeId` the driver places in a `SUBSCRIBE` frame is
**advisory** and is overridden by the cert identity (§03 AU3-2; in a plaintext deployment the wire identity is
replaced wholesale). A driver **MUST NOT** rely on a self-asserted `edgeId` being trusted.

**F9-4 (client MUST verify the server endpoint — `HTTPS` identification).** A driver **MUST** set TLS
**endpoint identification to `"HTTPS"`** (`EdgeStreamClient`: `params.setEndpointIdentificationAlgorithm
("HTTPS")`) so the **server certificate's SAN MUST cover the host** it connected to, **and MUST supply that
host name to the TLS layer** (SNI / peer-host) so SAN matching is meaningful — connecting by bare IP or
omitting the host leaves `HTTPS` verification with no name to match (and may silently weaken to nothing). **A
trusted CA alone is insufficient** — without endpoint identification, any cert the CA signed would be
accepted, enabling a man-in-the-middle. A driver **MUST NOT** disable hostname/SAN verification.

---

## 10. Connection lifecycle, flow-control, and caps

**F10-1 (lifecycle).** `connect (TCP)` → **TLS/mTLS handshake** (F9; authentication completes before any
application frame, §03 AU4-1) → **first frame decides type + version** (F4) → **operate** (stream
`NOTIFY`/`WATCH_EVENT`/`SNAPSHOT_*`/`HEARTBEAT`; client sends `CURSOR_ACK`/`WATCH_CANCEL`) → on disconnect,
**resume by re-creating** the subscription/watch on a **new** connection with a **resume cursor** (F8). There
is **no** mid-connection re-pin and **no** server-side session resumption token.

**F10-1a (what a reconnect keeps vs. loses).** A reconnect is a **fresh** connection with **fresh** per-
connection state. A driver **keeps only its persisted cursor**; it **loses** all `watch_id`s (and the lifetime
`watch_id` budget **resets**), all multiplex/registry state, and **any unacked in-flight frames** (recovered
only by cursor replay). A driver **MUST** persist its cursor and **MUST** re-`CREATE` its watches with fresh
ids after a reconnect.

**F10-1b (resume positions a single shared drain — one connection per independent resume).** v1 fan-out uses
**one shared drain per connection**: only the **first** authorized watch/subscription's cursor positions that
drain; **every subsequent watch on the same connection is started TAIL-from-the-current-frontier and its
requested resume cursor is discarded** (`FanOutConnectionDriver` :443–463 — "independent resume positions need
a separate connection: the v1 single-shared-drain boundary"). **Consequence (a silent-data-loss footgun):** a
driver that reconnects and re-`CREATE`s N watches on **one** connection, each with its persisted cursor,
resumes **only watch #1**; watches #2…N **start at "now" and drop every event between their cursor and the
live frontier**. A driver that needs **independent** per-watch resume **MUST** use **one connection per
independently-resumed watch** (§02 W8-6). Watches that share a single live tail (no independent backfill) MAY
share a connection.

**F10-1c (stale resume ⇒ full re-bootstrap).** If a resume cursor is older than the server's retained replay
window, the server responds with a **full snapshot** (`SUBSCRIBE_OK.mode = 1`, snapshot-first) rather than a
tail. A driver **MUST** be prepared for a snapshot re-bootstrap (and its bandwidth/"replay-storm") on a stale
resume, not just an incremental tail. (An unrecoverable gap is `GAP_UNRECOVERABLE`, §07.)

**F10-1d (no post-handshake idle timeout — a known v1 limitation).** The pre-handshake bound stops slowloris
fd/thread exhaustion, but **after** a successful handshake there is currently **no** first-frame or read-idle
deadline on the server: an authenticated connection that sends no frame (or goes silent) holds a session slot
indefinitely. A driver **SHOULD NOT** rely on the server reclaiming an idle connection, and **SHOULD** itself
close connections it is no longer using (so it does not consume a slot, F10-2). *(This is a known server-side
hardening candidate, flagged for the operability backlog; the driver contract is unchanged.)*

**F10-2 (caps).** Deployed limits (`FanOutServer`, `FanOutConnectionDriver`):

| Limit | Value | Constant | Refusal mechanism |
|---|---|---|---|
| concurrent sessions (server-wide) | **1024** (default; **tunable** via `edge.fanout.transport.maxSessions`) | `DEFAULT_MAX_SESSIONS` | **silent: immediate TCP close BEFORE the handshake**, no `ErrorCode` frame (metric `edge_fanout_sessions_refused_total`) — a driver sees a connect/reset/EOF and **MUST retry with backoff** (do **not** treat it as a protocol error) |
| live watches per connection | **1024** | `MAX_LIVE_WATCHES_PER_CONNECTION` | a `BAD_SUBSCRIBE`-class reject **frame**; recoverable by `WATCH_CANCEL` (frees a live slot) |
| lifetime `watch_id`s per connection | **16384** | `MAX_WATCH_IDS_PER_CONNECTION` | a `BAD_SUBSCRIBE`-class reject **frame**; **not** reclaimable in-connection (ids are never reused) — exhausting it ⇒ **reconnect** (a fresh connection resets the budget) |
| watch target length | **1024 bytes** UTF-8 | `WatchTargetValidator.MAX_PATH_BYTES` | reject **frame** |

A driver **MUST** distinguish the **silent, pre-handshake session-cap refusal** (retry/backoff — a routine
capacity condition) from the **frame-bearing** per-connection rejects.

**F10-3 (flow-control is MANDATORY — ack or be quarantined).** A driver **MUST** send `CURSOR_ACK` (`0x07`)
periodically to signal progress **and MUST drain its socket promptly**. Two distinct server-side bounds back
this (both lead to `DEMOTED_TO_CATCHUP`): the per-connection **transport outbound queue** (default **64**
frames — "drain your socket", a slow *reader*), and the **session in-flight NOTIFY queue** (default **256**
frames — the *overflow-demotion* bound, a slow *acker*). A session that lets either back up without ack/read
progress is **demoted** (`DEMOTED_TO_CATCHUP`, §07 — a non-fatal switch to catch-up/snapshot mode the driver
**MUST** handle), and repeated/continued pressure escalates to **`QUARANTINED`** with a connection teardown. `CURSOR_ACK` and prompt
draining are therefore **liveness-critical**, not optional "progress." A driver that consumes but never acks
(or reads too slowly) will be demoted and ultimately quarantined.

**F10-4 (quarantine is identity-stateful across reconnects).** Quarantine is keyed to the **certificate
identity (DN)** and persists **across** connections: a reconnect by a quarantined identity is **refused** with
`QUARANTINED` (the message carries the remaining cooldown). After a `QUARANTINED` teardown a driver **MUST**
back off the indicated cooldown **before** reconnecting/re-`CREATE`ing — an immediate reconnect-storm is
refused. ("No resumption token" (F10-1) does **not** mean reconnect is stateless: the quarantine state is.)

---

## 11. Fail-closed forward-compatibility (the asymmetry vs. HTTP)

**F11-1 (the frame is fixed-positional and fail-closed).** The EdgeFrame envelope and payloads use a **fixed
positional layout — NOT TLV**. Consequently, at the **frame** level:

- an **unknown `Type`** ⇒ **`FRAME_CORRUPT`** (F3);
- an **unknown / future `Version`** ⇒ **`BAD_WIRE_VERSION`** (F3);
- **trailing bytes** after a known payload ⇒ **`FRAME_CORRUPT`** (F3 step 7);
- the **CRC is verified before** version/type (F3 step 4).

**A future field CANNOT be appended to an existing frame and silently ignored** — the trailing-bytes check
rejects it. This is the **deliberate asymmetry vs. HTTP** (§04), which ignores unknown query params/headers.
To evolve the edge wire a deployment **MUST bump the version byte** (a new frame type or field rides a
`0x03`+ version); older drivers then fail closed on the unknown version rather than mis-parsing. A driver
**MUST** treat any unknown version/type/trailing-byte as a hard error, never as a forward-compatible
extension.

**F11-2 (the exception is the nested snapshot trailer).** The **only** TLV-extensible region is the ADR-0028
snapshot body's trailer (F7-2): a reader **MUST** skip unknown TLV tags there. A driver **MUST** keep these
two rules distinct — **reject-unknown at the frame, skip-unknown in the snapshot trailer**.

**F11-3 (a watch driver and a read driver share the envelope).** Because the watch path reuses `NOTIFY`
(`0x03`, e.g. `full_chain_verify`) and `ERROR_CLOSE` (`0x09`), a watch driver needs the F6 bytes of those
frames and the F7-1 nested blob, not only the `0x0A`–`0x12` frames of §02. A minimal read/fan-out driver needs
F6-1…F6-9; a watch driver additionally needs §02 + F7.

---

## 12. Summary of normative requirements (driver checklist)

- [ ] Frame envelope: **`[L u32 BE][ver u8][type u8][payload][CRC32C u32 BE]`**, `L` = whole frame, min 10 B,
      max 2 MiB, **CRC-32C (Castagnoli, not IEEE) over `[0,L-4)`** (F2); reproduce the **golden fixtures
      byte-for-byte**.
- [ ] Validate in order: length-bounds → `L==data.length` → **CRC before version/type** → version (+pin) →
      type → payload → **trailing bytes ⇒ `FRAME_CORRUPT`**; **bounds-check every length/count (incl.
      server-set snapshot sizes) before allocating** (F3).
- [ ] **First-frame pin** on the **version byte** — `SUBSCRIBE` (type `0x01`)⇒version `0x01`, `WATCH_CREATE`
      (type `0x0A`)⇒version `0x02`; stamp that version on **every** frame; a mismatch ⇒ `BAD_WIRE_VERSION`;
      **no** hello frame, **no** downgrade (F4).
- [ ] Keep **sequence/timestamp `u64` fields in `[0, 2^63)`** (incl. the client-emitted `CURSOR_ACK.seq` and
      `SUBSCRIBE.resumeCursor`) — high bit ⇒ `FRAME_CORRUPT`; `failoverResumeCursor`'s only legal high-bit value
      is the `0xFFFF…FF` "none" sentinel; only `SUBSCRIBE_OK`/`HEARTBEAT` `latestSeq` and `watch_id`/`gid` are
      full-range (F5).
- [ ] Encode the **nine v1 payloads** `0x01`–`0x09` exactly (F6); the `NOTIFY` `sigLen = -1` is a **signed**
      no-signature sentinel; `SNAPSHOT_CHUNK.chunkBytes` is the frame remainder (no inner prefix); **reassemble
      a snapshot to `chunkCount`/`totalBytes` or discard** (F6-5/F6-6).
- [ ] Decode the nested **`CommandCodec` batch** (`[type][u16 keyLen][key][i32 valLen][val]`, PUT/DELETE/BATCH)
      and the **ADR-0028 snapshot body** (entries + a **three-form, skip-unknown-TLV** trailer), bounding every
      length first (F7).
- [ ] Cursor on the wire is **`[count u32]( gid u32  S u64 )`**, strictly ascending unsigned `gid`,
      vector-native **even at N = 1**; `count = 0` = from-now (F8).
- [ ] TLS: **mTLS required** (no plaintext downgrade in prod), **TLSv1.3-only**,
      `TLS_AES_256_GCM_SHA384`/`TLS_AES_128_GCM_SHA256`, identity = cert Subject DN (`edgeId` advisory),
      **`HTTPS` endpoint identification with the host supplied** so SAN matching is meaningful (F9).
- [ ] Lifecycle: handshake → first-frame-pins → operate → **resume by re-CREATE with a cursor** (keep cursor;
      **lose** `watch_id`s/budget/multiplex/unacked frames); **one connection per independently-resumed watch**
      (shared drain) (F10-1).
- [ ] **Send `CURSOR_ACK` periodically and drain promptly — flow-control is mandatory** (transport outbound
      queue 64 = drain your socket; session in-flight queue 256 = overflow-demotion bound);
      `DEMOTED_TO_CATCHUP` is a non-fatal mode to handle, not a close; continued lag ⇒ `QUARANTINED` with an
      identity cooldown to honor before reconnecting (F10-3, F10-4).
- [ ] Respect caps (≤1024 sessions [tunable], ≤1024 live watches/conn, ≤16384 lifetime `watch_id`s/conn,
      ≤1024 B target); the **session-cap refusal is a silent pre-handshake TCP close ⇒ retry with backoff**
      (not a protocol error) (F10-2).
- [ ] Treat the `ERROR_CLOSE`/`WATCH_CANCELED` `message` as **untrusted** (sanitize before logging; branch on
      the numeric code) (F6-9); **fail closed** at the frame level, **skip-unknown** only in the snapshot
      trailer (F11).
