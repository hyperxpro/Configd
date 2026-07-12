# Configd Driver Protocol RFC — §06: Wire Framing & Transport

**Status: DRAFT (2026-06-30). Docs-only; normative; BYTE-LEVEL.** Sixth section of the Configd driver-protocol
RFC. This section specifies the **binary edge wire** to the byte: the **EdgeFrame envelope**, the **nine v1
frame payloads** (`0x01`–`0x09`), the **two auth-phase frames** (`0x13` AUTH / `0x14` REFRESH_AUTH, under the
auth-phase wire version `0x04`; §6A), the **nested blobs** the watch section deferred (the `CommandCodec` batch
inside `NOTIFY`, the ADR-0028 snapshot body inside `*_SNAPSHOT_CHUNK`), the shared **cursor-vector** codec, the
**first-frame version pin** (the real "negotiation") and the **version-pin exemption** of the auth frames, the
**`u64 < 2^63`** field constraint, the **TLS profile**, the **connection lifecycle, flow-control, and caps**,
and the **fail-closed forward-compatibility** rules. A driver implementing this section produces and consumes
bytes **identical** to the deployed codec.

**This section is validated byte-for-byte against the implementation and its golden fixtures.** The authority
is [`EdgeFrameCodec.java`](../../../configd-wire/src/main/java/io/configd/distribution/wire/EdgeFrameCodec.java)
and the **golden vectors** in
[`EdgeFrameGoldenBytes.java`](../../../configd-wire/src/test/java/io/configd/distribution/wire/EdgeFrameGoldenBytes.java)
(CRC-correct frames per type, pinned by `EdgeFrameCodecGoldenFixtureTest` for v1 and
`EdgeFrameCodecV2GoldenFixtureTest` for v2). **Every layout here MUST match those golden bytes**; the fixture
name is cited next to each frame so a driver author can use the golden bytes as the cross-language conformance
vector. **Wire-format ADR note:** [`adr-0029-wire-format-v1.md`](../../adr/adr-0029-wire-format-v1.md) is
the canonical origin of the framing **discipline** — a version byte, a CRC32C-Castagnoli trailer, CRC-before-
type validation, and fail-closed forward-compat — but its *concrete* diagram predates the reserved-epoch
hardening: the **Raft** frame as implemented is `HEADER_SIZE = 26`, 16 MiB, with Group-Id/Term **and an
8-byte reserved `epoch` MBZ slot** (§13 here specifies it byte-for-byte against `FrameCodec.java`). The **edge** layout
(`HEADER_SIZE = 6`, 2 MiB, no group/term) is `EdgeFrameCodec`'s own (it predates the ADR-0043 Netty
migration and is unchanged by it). **Take the edge byte layout from F2-1 here and the Raft byte layout from §13
here, not from ADR-0029's Raft diagram**; ADR-0028 is the snapshot body. Where this section and a prior RFC (or
ADR) claim disagree, **the code wins**. This section is **normative**; it **composes with**:

- [`02-watches.md`](02-watches.md) — the **`0x02` watch frames `0x0A`–`0x12`** and the cursor mechanics. §06
  **completes** what §02 deferred: the envelope, the `0x01`–`0x09` payloads, and the nested
  `CommandCodec`/snapshot blobs. The cursor codec (F8) is the one §02 §3 (W3-5) references.
- [`03-authentication.md`](03-authentication.md) — the **mTLS** handshake is the edge authentication (AU3-2);
  §06 F9 gives its concrete TLS profile.
- [`07-errors.md`](07-errors.md) — the `ErrorCode` taxonomy carried in `ERROR_CLOSE`
  (`0x09`) and `WATCH_CANCELED` (`0x0F`). §06 specifies the *byte* (a `u8` code 1–13, incl. `13
  CREDENTIAL_EXPIRED`); §07 will own the *meaning* and the driver reaction.
- [`03-authentication.md`](03-authentication.md) — the **edge connection-level auth lifecycle** (mTLS at the
  handshake, and the token/basic `AUTH`/`REFRESH_AUTH` frames §6A carries): §06 owns the auth-frame *bytes* and
  the version-pin exemption; §03 owns *when* a driver authenticates, refreshes, and handles an expiry-close.
- [`00-overview.md`](00-overview.md) — the two-plane architecture.

Clauses are referenced as **`F<n>-<m>`** (the framing-section clause prefix; parallel to §1 `A`, §2 `W`, §3
`AU`, §4 `D`, §5 `R`), so the composed RFC has no clashing identifiers.

---

## 1. Conventions, scope, versioning

### 1.1 Requirement keywords

The keywords **MUST**, **MUST NOT**, **REQUIRED**, **SHALL**, **SHALL NOT**, **SHOULD**, **SHOULD NOT**,
**MAY**, and **OPTIONAL** are to be interpreted as in RFC 2119 / RFC 8174.

### 1.2 Scope and conventions

This section specifies the **binary edge transport** (the fan-out / watch / streaming-read plane — §1–§12) and,
for conformance completeness, the **intra-cluster Raft consensus transport** (§13, a **non-driver** surface).
It does **not** specify the HTTP control plane (§04). **All multi-byte integers are big-endian** unless stated. `u8`/`u16`/`u32`/`u64`/
`i32` denote unsigned/signed fixed-width integers. Lengths are byte counts. "The wire" is what crosses the TLS
connection after the handshake.

### 1.3 Versioning — first-frame pin, not negotiation

The edge wire is versioned by a **1-byte version stamp on every frame** (`0x01`, `0x02`, `0x03`, or the
auth-phase `0x04`), and the **business** version is **pinned by the first business frame of the connection**
(F4). There is **no** hello/capabilities frame and **no** negotiation round-trip. This **corrects the
aspirational "negotiated at connection setup" language** of §02 (W1-2 / W1-3, and W5-11 "Wire version and
negotiation"): the real mechanism is **first-frame-wins + pin + fail-closed** (F4). The decoder accepts a
version byte of **`0x01`, `0x02`, `0x03`, or `0x04`**; any other value is `BAD_WIRE_VERSION`
(`EdgeFrameCodec.decode` :711–718).

`0x03` is the **filtered-fan-out** version (ADR-0045): it carries a server-side-filtered SUBSCRIBE stream.
Under `0x03`, `SUBSCRIBE` gains a trailing `acceptsFiltered` opt-in byte and `SUBSCRIBE_OK` a trailing
`filtered` confirm byte (F6-1a / F6-2a below); every other frame is byte-identical to its `0x01` form save the
version byte, and the `0x0A`–`0x12` watch frames are **not** legal under `0x03` (they remain `0x02`-only). A
`0x03` SUBSCRIBE to a server that only speaks `0x01`/`0x02` **fails closed** with `BAD_WIRE_VERSION` (no silent
misparse); an old edge to a new server sends `0x01` and gets the unfiltered legacy stream.

`0x04` is the **auth-phase** version (`EDGE_WIRE_VERSION_V4`, `EdgeFrameCodec` :102–113): it carries **only**
the `0x13` `AUTH` and `0x14` `REFRESH_AUTH` frames (§6A), the client-to-server token/basic credential frames. It
is **not** a business version and it does **not** participate in the first-frame pin: a `0x04` auth frame is
**version-pin-exempt** — it may interleave on a connection whose business version is pinned to `0x01`, `0x02`,
or `0x03` (F6A-4). Conversely a **business/watch type stamped `0x04`, or an `AUTH`/`REFRESH_AUTH` type stamped
`0x01`/`0x02`/`0x03`, is `FRAME_CORRUPT`** (F6A-3). The auth frames are **purely additive**: a driver that
authenticates only by mTLS (never sending a `0x04` frame) is **byte-identical to a client from before
authentication was added**.

---

## 2. The EdgeFrame envelope (byte-for-byte)

**F2-1 (the envelope).** Every frame is a length-prefixed, CRC-trailered envelope
(`EdgeFrameCodec` :21–27, :73–101):

```
 offset  size  field        notes
 0       4     Length  u32  BIG-ENDIAN; covers the WHOLE frame (Length + Version + Type + Payload + CRC)
 4       1     Version u8   0x01 (built) | 0x02 (watch-capable) | 0x03 (filtered fan-out, ADR-0045) | 0x04 (auth-phase, §6A)
 5       1     Type    u8   FrameType code (F6 / §6A / §02)
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

**F2-4 (the CRC32C is integrity, NOT authenticity — do not treat it as a security control).** The CRC32C
trailer is **defense-in-depth against bit-flips and bug-induced corruption**, not a cryptographic MAC: it is
un-keyed and any party that can write the payload can compute the matching trailer. **The wire's cryptographic
authenticity and confidentiality are provided by TLS/mTLS** (F9), which is **mandatory in production**; the
per-delta Ed25519 signatures (F6-3) provide end-to-end authenticity of the config chain **above** the frame
layer. A conformant driver **MUST NOT** treat a valid CRC as evidence that a frame is authentic or authorized,
and **MUST NOT** accept frames on an unauthenticated transport in production on the strength of the CRC. A CRC
**mismatch** is `FRAME_CORRUPT` (F3 step 4) and is verified **before** the version/type bytes are interpreted.

---

## 3. Decode and validation order (fail-closed)

**F3-1 (the exact order — a driver MUST follow it).** A decoder validates in this order
(`EdgeFrameCodec.decode` :597–683), mapping each failure to a streaming `ErrorCode` (§07):

1. `data.length ≥ 10` (else **`FRAME_CORRUPT`**).
2. read `Length` (u32 BE); `10 ≤ Length ≤ 2 MiB` — `Length > 2 MiB` ⇒ **`FRAME_TOO_LARGE`**, `Length < 10` ⇒
   **`FRAME_CORRUPT`**.
3. `Length == data.length` (else **`FRAME_CORRUPT`** — the buffer must hold exactly one frame).
4. **CRC32C over `[0, Length-4)` == trailer** (else **`FRAME_CORRUPT`**). **The CRC is verified BEFORE the
   version and type bytes are interpreted** — so a flipped version/type byte reads as corruption, never as a
   misleading `BAD_WIRE_VERSION`.
5. `Version ∈ {0x01, 0x02, 0x03, 0x04}` (else **`BAD_WIRE_VERSION`**); **and** if the frame is a **business**
   frame (`Version ∈ {0x01,0x02,0x03}`) and the connection is pinned (F4), `Version` == the pinned version (else
   **`BAD_WIRE_VERSION`**). A **`0x04` auth frame is exempt from the pin** (F6A-4): it is decoded under `0x04`
   regardless of the connection's business version and never establishes or violates the pin.
6. `Type` resolves to a known `FrameType` (else **`FRAME_CORRUPT`**); and the **type↔version legality** holds
   (else **`FRAME_CORRUPT`**, `EdgeFrameCodec.decode` :739–755): a `0x0A`–`0x12` watch type is legal only under
   `0x02`; the `0x13`/`0x14` auth types are legal **only** under `0x04`; **no** business/watch type is legal
   under `0x04`; and **no** auth type is legal under `0x01`/`0x02`/`0x03` (F6A-3). Because the CRC is already
   verified (step 4), a legality violation is a deliberately-constructed frame and surfaces as `FRAME_CORRUPT`,
   never a misleading `BAD_WIRE_VERSION`.
7. decode the payload `[6, Length-4)`; **any** underflow / malformed field / out-of-range value ⇒
   **`FRAME_CORRUPT`**; **any trailing byte left after the payload ⇒ `FRAME_CORRUPT`** (F11).

**F3-2 (bounds before allocation — load-bearing).** A driver **MUST** bounds-check `Length` **and every inner
length/count prefix** — prefix count, `NOTIFY` count, batch length, cursor count, value length, **and the
server-controlled snapshot sizes (F7-2, F6-4)** — **against the remaining bytes (and a configured ceiling)
BEFORE allocating**, so a hostile or compromised peer cannot induce a giant allocation by lying in a length
field (`decodeCursor` :914–917, `decodeNotify` :783/:789, `decodeSubscribe` :717–724 all bound-then-allocate).
A value being "validated non-negative" (F5) is **not** an upper bound — the driver supplies the upper bound.

---

## 4. The first-frame version pin (the real "negotiation")

**F4-1 (first-business-frame-wins, then pinned for life).** There is **no** hello/capabilities frame and **no**
negotiation handshake (`ByteToEdgeFrameDecoder` :46–108). The connection's wire **business version** is decided
by the **first business frame's version byte** (offset 4) — a `0x04` `AUTH`/`REFRESH_AUTH` frame, which may
precede it on a token edge, is pin-exempt and does **not** set the pin (F4-3). In practice:

- a first **`SUBSCRIBE`** (**type `0x01`**) stamped **`0x01`** ⇒ the connection is pinned to **version `0x01`**
  (byte-identical to the legacy pre-watch path);
- a first **`SUBSCRIBE`** stamped **`0x03`** ⇒ the connection is pinned to **version `0x03`** (the filtered
  fan-out; the server stamps `0x03` on `SUBSCRIBE_OK` and every subsequent frame, ADR-0045);
- a first **`WATCH_CREATE`** (**type `0x0A`**, a v2 frame) ⇒ the connection is pinned to **version `0x02`**.

> *Type vs. version (do not conflate):* `0x01`/`0x02` above are wire **versions** (offset 4); a frame's **type**
> code (offset 5) is separate — `SUBSCRIBE` = type `0x01`, `WATCH_CREATE` = type `0x0A` (and type `0x02` is
> `SUBSCRIBE_OK`). The pin keys on the first frame's **version byte**; the SUBSCRIBE/WATCH_CREATE split above is
> the conformant case, and F4-2 ("stamp one version on every frame") is the binding rule.

The first **business** frame is decoded accepting **any** business version (after CRC validation); its stamped
version then **pins** the connection. **Every subsequent business frame MUST carry the pinned version**; a
business frame stamped with another accepted business version (a `0x02` frame on a `0x01`-pinned connection, or
vice-versa) **fails closed** with **`BAD_WIRE_VERSION`**. There is **no downgrade** and no mid-connection
re-pin. A `0x04` auth frame is exempt and may interleave regardless of the business pin (F4-3).

**F4-2 (driver rule).** A driver **MUST** pick its wire **business** version by its **first business** frame
(`SUBSCRIBE`/`WATCH_CREATE`) and stamp **that same version on every business frame** for the connection's life.
To use a different business version it **MUST** open a new connection. A driver **MUST NOT** mix `0x01`, `0x02`,
and `0x03` business frames on one connection. The **sole exception** is a `0x04` `AUTH`/`REFRESH_AUTH` frame,
which is version-pin-exempt (F4-3) and does **not** count as a business frame for the pin.

**F4-3 (the auth-phase pin exemption — normative).** A `0x04` `AUTH`/`REFRESH_AUTH` frame (§6A) is **exempt from
the first-frame pin**: it neither establishes nor is checked against the connection's business version
(`ByteToEdgeFrameDecoder.decode` :95–108 / `FanOutServer.readFrame` :921–935 decode a `0x04` frame under `0x04`
and never touch the business pin). Consequently:

- A driver on a token/basic-authenticated edge sends its `AUTH` frame stamped **`0x04`** (regardless of which
  business version it will subsequently pin), then its first business frame stamps and pins the business version
  as usual (F4-1). A later `REFRESH_AUTH` is likewise stamped `0x04` and interleaves without disturbing the pin.
- A **business/watch type stamped `0x04`**, or an **`AUTH`/`REFRESH_AUTH` type stamped `0x01`/`0x02`/`0x03`**, is
  **`FRAME_CORRUPT`** (F6A-3) — a driver **MUST** stamp `0x04` on **exactly** the auth frames and never on a
  business frame, and **MUST NOT** stamp a business version on an auth frame.

---

## 5. The `u64 < 2^63` field constraint (a true-`u64` driver footgun)

**F5-1 (the constrained fields — `[0, 2^63)`, high-bit ⇒ `FRAME_CORRUPT`).** Nearly **every sequence and
timestamp `u64` field** — on **both** the v1 (`0x01`–`0x09`) and v2 (`0x0A`–`0x12`) frames — is validated
**non-negative** by the record constructors (and that `IllegalArgumentException` is mapped to `FRAME_CORRUPT`
at `EdgeFrameCodec` :635–638). Their effective range is **`[0, 2^63)`**; a **high-bit-set** value **decodes as
`FRAME_CORRUPT`**. The constrained set is:

| Frame | Constrained field(s) | Source |
|---|---|---|
| `SUBSCRIBE` (`0x01`) | `resumeCursor`; **`topologyEpoch` is stricter — `[1, 2^63)`, see F5-3** | `EdgeFrame.Subscribe` ctor :99–123 |
| `NOTIFY` (`0x03`) | `seq`, `commitTimestampMillis`, `fromVersion`, `toVersion`, `epoch` | `CommitNotification`/`ConfigDelta` ctors |
| `SNAPSHOT_BEGIN` (`0x04`) | `snapshotSeq`, `totalBytes` | `EdgeFrame.SnapshotBegin` ctor |
| `SNAPSHOT_END` (`0x06`) | `snapshotSeq` | ctor |
| **`CURSOR_ACK` (`0x07`)** | `seq` — **a CLIENT-emitted field** | ctor |
| `HEARTBEAT` (`0x08`) | `serverNowMillis` | ctor |
| cursor vector (F8) | `S`; **`topologyEpoch` is stricter — `[1, 2^63)`, see F5-3** | `WatchCursor.Component` :127–132 / `WatchCursor` ctor :64–81 |
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

**F5-3 (`topologyEpoch` — `[1, 2^63)`, `0` is reserved-illegal; the topology-generation binding).** The
`topologyEpoch:u64` that prefixes both the `SUBSCRIBE` payload (F6-1) and every cursor vector (F8) is
constrained to **`[1, 2^63)`** — **stricter** than the F5-1 non-negative rule: **`0` is reserved-illegal** and
decodes as **`FRAME_CORRUPT`** (`EdgeFrame.Subscribe`/`WatchCursor` ctors and `decodeSubscribe` :729–733 /
`decodeCursor` :908–912 reject `topologyEpoch == 0`). It **binds the whole resume token to the topology
generation that minted it** (the server's `ShardMap.epoch()`, sourced from the authenticated
`topology-descriptor.dat`). A driver **MUST**:

- **emit the server-supplied epoch verbatim.** At **v1 static-N** it is always **`1`**
  (`WatchCursor.INITIAL_TOPOLOGY_EPOCH`); a fresh driver with no prior epoch uses `1`.
- **never emit `0`** (nor a high-bit value) — either is `FRAME_CORRUPT`.
- **persist the epoch with the cursor and re-send it on resume.** If the server's epoch has advanced past the
  token's, the server rejects the whole generation with **`STALE_TOPOLOGY`** (§07 code 12) — the driver
  **MUST** then **drop the cursor and fully re-hydrate from scratch** (not resume from an earlier `S`, which is
  the `GAP_UNRECOVERABLE` reaction). At v1 static-N the epoch never changes, so `STALE_TOPOLOGY` never fires and
  behavior is byte-identical.

---

## 6. The nine v1 frame payloads (`0x01`–`0x09`, byte-for-byte)

All v1 frames are legal under `0x01` (and `NOTIFY`/`ERROR_CLOSE` are also reused under `0x02` — §02). Each
layout below is the **payload** (the bytes at offset 6, between the Type byte and the CRC). Golden fixture
names are cited for cross-language verification.

**F6-1 `SUBSCRIBE` (`0x01`)** — *client→server*; golden `subscribe_full_store.bin`, `subscribe_prefixes.bin`:

```
[1  u8 ] fullStore        (1 = whole-store subscription; 0 = prefix-filtered)
[4  u32] prefixCount      (element cap MAX_PREFIXES = 4096; count > 4096 ⇒ FRAME_CORRUPT — F3-2)
  repeated prefixCount times:
    [4 u32] prefixLen
    [prefixLen] prefix    (UTF-8)
[8  u64] topologyEpoch         (REQUIRED; [1, 2^63); 0 ⇒ FRAME_CORRUPT — F5-3; v1 static-N = 1)
[8  u64] resumeCursor          ([0, 2^63); high-bit ⇒ FRAME_CORRUPT — F5-1)
[8  u64] failoverResumeCursor  ([0, 2^63) ∪ {0xFFFFFFFFFFFFFFFF = none}; any other high bit ⇒ FRAME_CORRUPT — F5-2)
[4  u32] edgeIdLen
[edgeIdLen] edgeId        (UTF-8; ADVISORY — the server overrides identity with the mTLS cert DN, §03 AU3-2)
```

> **`topologyEpoch` position (REQUIRED — do NOT omit).** The `topologyEpoch:u64` sits
> **between the last prefix and `resumeCursor`** and is present on **every** version (`0x01`/`0x02`/`0x03`) —
> it is NOT version-gated. The golden `subscribe_full_store.bin` v1 image is
> `00000031 01 01 01 00000000 `**`0000000000000001`**` 0000000000000000 ffffffffffffffff 00000006 656467652d41 1f55c56f` —
> the bolded `0000000000000001` is the epoch. A driver that omits it emits a SUBSCRIBE **8 bytes short**, which
> the server rejects as `FRAME_CORRUPT`. A full-store SUBSCRIBE still carries the epoch (with `prefixCount = 0`).

**F6-1a `SUBSCRIBE` under `0x03`** (filtered fan-out, ADR-0045) — the F6-1 payload with **one trailing byte**:

```
... (the F6-1 payload) ...
[1  u8 ] acceptsFiltered   (0x03 ONLY; 1 = the edge understands filtered-stream semantics)
```

The edge sets `acceptsFiltered = 1` to opt this prefix-scoped subscription into server-side filtering. A
full-store SUBSCRIBE **MUST** set it `0` (a root edge wants the whole chain). The byte is present **only** on a
`0x03` frame; a `0x01`/`0x02` SUBSCRIBE has no such byte (`acceptsFiltered` decodes false), so those golden
images are unchanged. Golden `subscribe_prefixes_filtered.bin` (v3).

**F6-2 `SUBSCRIBE_OK` (`0x02`)** — *server→client*; golden `subscribe_ok_tail.bin`,
`subscribe_ok_snapshot_first.bin`:

```
[8 u64] latestSeq         (the shard's current applied-mutation seq; RAW u64 — F5-2)
[1 u8 ] mode              (0 = tail / resume-from-cursor; 1 = snapshot-first — the EdgeFrame.Mode ordinal)
```

**F6-2a `SUBSCRIBE_OK` under `0x03`** (filtered fan-out, ADR-0045) — the F6-2 payload with **one trailing byte**:

```
... (the F6-2 payload) ...
[1  u8 ] filtered          (0x03 ONLY; 1 = the server is filtering this session server-side)
```

`filtered = 1` tells the edge to select the filtered-stream apply mode: a dense **covered-S** cursor advanced
by the HEARTBEAT (F6-8a) and a **forward-only** version chain (a `NOTIFY` whose `fromVersion` is **greater
than** the applied version is expected — the dropped non-matching deltas bumped the global version — not a
gap; a regression below the applied version **is** a gap). Golden `subscribe_ok_filtered.bin`,
`subscribe_ok_unfiltered.bin` (v3).

**F6-3 `NOTIFY` (`0x03`)** — *server→client*; golden `notify_single_unsigned.bin`, `notify_batch_signed.bin`,
`notify_empty.bin`. A batch of commit notifications (the *encoder* caps it at **64** = `MAX_NOTIFY_BATCH` and
**256 KiB** = `MAX_NOTIFY_BATCH_BYTES`; the **decoder** enforces **both** — `count ≤ 64` **and** the **256 KiB**
payload cap (`FRAME_TOO_LARGE` above it), for canonical-encoding parity. The 256 KiB cap is nested under
the **2 MiB frame cap** (F3-2); a driver MAY additionally apply its own tighter bound):

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
>
> **Signing payload (ADR-0045, Track 0).** For a signed (`epoch > 0`) delta the Ed25519 signature covers
> `encodeBatch(mutations) || BE(fromVersion,8) || BE(toVersion,8) || BE(epoch,8) || nonce` — the **version
> position is inside the signature**, so a relay cannot rewrite `fromVersion`/`toVersion` to splice a delta out
> of the chain undetectably. A legacy `epoch == 0` delta keeps the batch-only payload
> (`encodeBatch(mutations)`). This is a signing-payload-composition change, **not** a wire-layout change
> (the position fields and the signature are already on the wire), so it does **not** bump the wire version
> or rebaseline any golden fixture. A verifying driver **MUST** reject a signature carried on an `epoch == 0`
> delta (production never emits that shape).

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

**F6-6a (the hydration snapshot is transport-authenticated, not signed — a normative trust boundary).** The
reassembled snapshot body (F7-2) carries **no per-snapshot signature or MAC** — unlike the `NOTIFY` delta chain,
whose per-delta Ed25519 signatures (F6-3) are end-to-end authenticity **above** the transport. A driver
hydrating from a snapshot therefore **trusts the server's base-state bytes on the strength of the authenticated
transport** (the mTLS server identity, F9) plus the frame CRC (transport integrity, F2-2) — **not** a
cryptographic signature. The signed delta chain is the tamper-evidence on every **increment** applied over that
trusted base. Three consequences a conforming driver **MUST** internalize:

1. Signature verification (`full_chain_verify` / a configured verifier) applies to the **delta chain**, **not**
   the snapshot: a verifying driver verifies deltas and **trusts** the snapshot base — this is **correct and
   deliberate**, not an unverified-input gap.
2. The snapshot's authenticity is **only as strong as the transport**. A driver **MUST NOT** accept and rely on
   a hydration snapshot over an unauthenticated / plaintext transport (F9, OV7-1) — nothing else binds the base
   state to the real leader.
3. A driver **MUST** still bound the snapshot **structurally** (exactly `chunkCount` chunks, reassembled length
   == `totalBytes`, and its own accumulation ceilings) so a hostile or buggy server cannot amplify allocation —
   even though it cannot **forge** the base state without also defeating mTLS.

**F6-7 `CURSOR_ACK` (`0x07`)** — *client→server*; golden `cursor_ack.bin`: `[8 u64] seq` (`[0,2^63)` — F5-1;
**client-emitted**). This is **mandatory flow-control**, not optional progress: see F10-3.

**F6-8 `HEARTBEAT` (`0x08`)** — *server→client*; golden `heartbeat.bin`: `[8 u64] latestSeq` (RAW — F5-2)
`[8 u64] serverNowMillis` (`[0,2^63)` — F5-1). A driver **SHOULD** treat a prolonged absence of `HEARTBEAT`
(beyond a configured read-idle deadline) as a liveness failure and reconnect (F10-3). On an unfiltered
session `latestSeq` is the buffer tip (the staleness clock); a driver **MUST NOT** advance its applied cursor
from it (the tip may include commits it has not received).

**F6-8a filtered-HEARTBEAT semantics** (a `0x03` session that got `SUBSCRIBE_OK.filtered = 1`, ADR-0045). The
frame layout is unchanged; `latestSeq` is **re-typed as the drained-through covered-S**: the server MUST have
delivered every matching delta with `toVersion ≤ latestSeq` **before** emitting the heartbeat (drain-then-
heartbeat ordering). The edge advances its dense covered cursor to it and acks it (`CURSOR_ACK.seq = covered-S`),
so a narrow-prefix edge is never demoted for ack-lag caused by filtering. The edge advances the covered cursor
**monotonically** (advance-if-greater) and **MUST NOT** regress it; a **regressed** covered-S on the HEARTBEAT
is **safely ignored**, not a resync trigger. A genuine gap is signalled instead by a **delivered `NOTIFY`**
whose position regresses below the applied version (the forward-only gap check, F6-2a), which triggers resync.
"Caught up and quiet" is `covered-S == latestSeq`; a frozen covered-S while the server keeps committing is
"stalled." A well-formed suppression of a matching delta behind a correct covered-S is **not** edge-detectable
— the documented trusted-server boundary (ADR-0045; a genuine data-loss gap from ring eviction is still
detected server-side and healed by a re-snapshot).

**F6-9 `ERROR_CLOSE` (`0x09`)** — *server→client* terminal; golden `error_*.bin` (codes 1–10):

```
[1 u8 ] code              (ErrorCode 1..13; §07 owns the meaning — the byte is the taxonomy value)
[4 u32] msgLen
[msgLen] message          (UTF-8; UNTRUSTED DIAGNOSTIC — see below)
```

> The taxonomy is **codes 1..13** (`ErrorCode.java` :14–116): `1 BAD_WIRE_VERSION` … `11 NOT_AUTHORIZED`,
> `12 STALE_TOPOLOGY` (the v2-only topology-generation code — F5-3 / §07), `13 CREDENTIAL_EXPIRED` (the token-TTL /
> cert-`notAfter` / refresh-reject expiry close on a token-auth edge — §6A / §07). An unknown code byte decodes
> as `FRAME_CORRUPT`. `error_bad_wire_version.bin` … `error_protocol_violation.bin` pin codes 1–10;
> `error_not_authorized.bin` pins code 11 (carried here as an `ERROR_CLOSE` in the v2 fixture so the byte value
> is pinned) — **but the
> live protocol carrier for the `NOT_AUTHORIZED` per-watch authz reject is `WATCH_CANCELED` (`0x0F`), not
> `ERROR_CLOSE`** (§02 W7-5; §07). The `message` is **untrusted, server-controlled, arbitrary bytes**: under a
> compromised/malicious server it may contain control characters, newlines, or ANSI escapes. A driver **MUST
> NOT** use it for control flow (branch on the numeric `code`), **MUST NOT** machine-parse it, and **MUST**
> sanitize/escape it before logging or display (log-forging / terminal-injection).

---

## 6A. The auth-phase frames (`0x13` AUTH / `0x14` REFRESH_AUTH — wire version `0x04`)

The token/basic edge-authentication surface. These two **client→server** frames ride the dedicated auth-phase
wire version **`0x04`** (`EDGE_WIRE_VERSION_V4`) and are the driver-visible mechanism by which a
**certificate-less** edge client presents (and later renews) a bearer or HTTP-Basic credential. The
connection-level auth **lifecycle** — when a driver sends them, the single-attempt rule, the pre-auth deadline,
the expiry-close and proactive refresh — is normative in [`03-authentication.md`](03-authentication.md) §3–§5;
this section owns the **bytes** and the **version rules**.

**F6A-1 (the two frame types).** Under version `0x04` there are exactly two legal types
(`FrameType` :51–55, `EdgeFrame.Auth`/`EdgeFrame.RefreshAuth` :393–437):

| Type | Name | Direction | Meaning |
|---|---|---|---|
| `0x13` | `AUTH` | client→server | present a bearer/basic credential to authenticate the connection |
| `0x14` | `REFRESH_AUTH` | client→server | present a fresh credential to **extend** an already-authenticated connection (renews the **same** identity's session; §03 AU4-6) |

Both carry the **identical payload shape** (F6A-2); the distinct type makes the intent self-describing on the
wire. A client certificate is an **mTLS handshake artifact and is NEVER carried in a frame** (`EdgeFrame`
:439–448, `encodeAuthCredentialInto` :360–362 rejects it); a cert-authenticating edge sends **no** `0x04`
frame at all.

**F6A-2 (the AUTH / REFRESH_AUTH payload — byte-for-byte).** The payload (the bytes at offset 6, between the
Type byte and the CRC) is a **1-byte scheme tag** then length-prefixed credential fields
(`encodeAuthCredentialInto` :347–368 / `decodeAuthCredential` :806–814), big-endian:

```
[1  u8 ] scheme            (1 = BEARER | 2 = BASIC; any other value ⇒ FRAME_CORRUPT — "unknown auth scheme")

 scheme = 1 (BEARER):
   [4  u32] tokenLen
   [tokenLen] token        (UTF-8; the opaque bearer/OIDC token — §03 AU2-2)

 scheme = 2 (BASIC):
   [4  u32] userLen
   [userLen] username      (UTF-8)
   [4  u32] passLen
   [passLen] password      (UTF-8)
```

Golden fixtures (`EdgeFrameGoldenBytes.v4()`, pinned by `EdgeFrameCodecV4GoldenFixtureTest`):

- **`auth_bearer.bin`** = `0000001e` (L = 30) · `04` (v4) · `13` (`AUTH`) · `01` (BEARER) · `0000000f` (tokenLen
  = 15) · `676f6c64656e2d746f6b656e2d3432` (`"golden-token-42"`) · `d1a2e2f1` (CRC32C).
- **`auth_basic.bin`** = `0000001e` (L = 30) · `04` · `13` · `02` (BASIC) · `00000005` (userLen = 5) ·
  `616c696365` (`"alice"`) · `00000006` (passLen = 6) · `733363726574` (`"s3cret"`) · `c4c94315` (CRC32C).
- **`refresh_auth_bearer.bin`** = `0000001f` (L = 31) · `04` · `14` (`REFRESH_AUTH`) · `01` (BEARER) ·
  `00000010` (tokenLen = 16) · `726566726573682d746f6b656e2d3939` (`"refresh-token-99"`) · `739ee8e1` (CRC32C).

The envelope (F2), decode order (F3), CRC-before-interpret, bounds-before-allocation, and **strict-end**
(trailing bytes ⇒ `FRAME_CORRUPT`) rules apply **identically** to a `0x04` frame. A driver **MUST** length-bound
`tokenLen`/`userLen`/`passLen` against the remaining payload before allocating (F3-2), and **MUST** treat the
token as **opaque** (§03 AU2-2). Error messages on the server never echo a token/password byte (redaction),
only the scheme number and lengths.

**F6A-3 (type↔version legality — a `FRAME_CORRUPT` tripwire).** The `0x13`/`0x14` auth types are legal **only**
under version `0x04`, and **no** business/watch type is legal under `0x04` (`EdgeFrameCodec` encode :275–293 /
decode :739–755). Every combination outside the matrix is `FRAME_CORRUPT` (the CRC is verified first, so a
violation is a constructed frame, never a `BAD_WIRE_VERSION`):

| Version | Legal types | Illegal (⇒ `FRAME_CORRUPT`) |
|---|---|---|
| `0x01` | `0x01`–`0x09` (business) | `0x0A`–`0x12` (watch), **`0x13`/`0x14` (auth)** |
| `0x02` | `0x01`–`0x09`, `0x0A`–`0x12` (watch) | **`0x13`/`0x14` (auth)** |
| `0x03` | `0x01`–`0x09` (business; +filtered opt-in bytes) | `0x0A`–`0x12` (watch), **`0x13`/`0x14` (auth)** |
| `0x04` | **`0x13` `AUTH`, `0x14` `REFRESH_AUTH` only** | **every** `0x01`–`0x12` business/watch type |

**F6A-4 (the version-pin exemption + additivity — normative).** A `0x04` auth frame is **version-pin-exempt**
(F4-3): it does **not** establish or violate the connection's business version pin, so it may interleave on a
connection pinned to any of `0x01`/`0x02`/`0x03`. A driver **MUST** stamp `0x04` on **exactly** its `AUTH`/
`REFRESH_AUTH` frames and a business version on **exactly** its business frames; crossing the two
(`0x04` on a business frame, or `0x01`/`0x02`/`0x03` on an auth frame) is `FRAME_CORRUPT` (F6A-3). Because the
auth frames live on their own version, the surface is **purely additive**: an **mTLS-only driver that never
sends a `0x04` frame is byte-identical to a client from before authentication was added**, and every
`0x01`/`0x02`/`0x03` golden image
is unchanged.

**F6A-5 (receive-side credential caps — server policy, not fixed wire constants).** While a token connection is
**unauthenticated**, the decoder caps the declared frame length at a small **pre-auth ceiling**
(`configd.edge.preAuthMaxFrameBytes`, default **16384**) — a larger declared length ⇒ `FRAME_TOO_LARGE`
**before** any frame buffer is allocated (`ByteToEdgeFrameDecoder` :83–89 / `FanOutServer.readFrame` :906–910).
The credential itself is size-policed **before** verification: a bearer token > `configd.edge.maxAuthTokenBytes`
(default **8192** UTF-8 bytes), a Basic username > 256 UTF-8 bytes, or a Basic password > 1024 chars is rejected
(`EdgeAuthConfig.credentialWithinCaps` :137–146). These are **deployment policy bounds** (not frozen golden
wire constants like `MAX_EDGE_FRAME_SIZE`); a conformant driver keeps its credential well within them and treats
an over-cap rejection as `AUTH_FAIL` (pre-auth) / `CREDENTIAL_EXPIRED` (on a `REFRESH_AUTH`; §07).

**F6A-6 (pipelining a business frame behind `AUTH` — the invariant is ordering, not a round-trip).** The `AUTH`
frame is **not acknowledged** — there is **no `AUTH-OK` frame** — so a certificate-less driver **MAY pipeline**
its first business frame(s) (`SUBSCRIBE` / `WATCH_CREATE`) **immediately behind** its single `AUTH`, without
waiting for a round-trip. While the **first** authentication is still resolving (credential verification runs
off the accept path), the server **buffers** up to **`MAX_PENDING_PREAUTH_FRAMES` = 8** such pipelined non-`AUTH`
frames — each already bounded by the pre-auth frame ceiling (F6A-5) — and **replays them into the session once
authentication succeeds**; if authentication **fails**, the buffered frames are **discarded** with the
`AUTH_FAIL` close (`EdgeAuthGateHandler.channelRead` / `bufferPendingFrame` / `replayPendingFrames`). What is a
`PROTOCOL_VIOLATION` (§07 code 10) is therefore an **ordering** fault, **not** the pipelining itself: a frame
sent **before** the `AUTH` (i.e. `AUTH` is not the first routed frame), a **second `AUTH`** (while the first is
resolving or after it has succeeded), or **more than 8** frames pipelined behind the `AUTH` before it resolves.
A driver **MUST** make `AUTH` its **first** routed frame and **MUST NOT** pipeline more than a small handful of
frames behind it before authentication completes; it **MAY** rely on this buffer to avoid an auth round-trip.
This **refines §03 AU4-5 / AU4-7**: "no business frame **before** `AUTH`" is the invariant — a business frame
**pipelined behind** `AUTH` is buffered-and-replayed, not a violation. (An **mTLS** edge has no `AUTH` frame and
no pre-auth window at all; this clause is the token/basic path only.)

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
([`adr-0028-snapshot-on-disk-format.md`](../../adr/adr-0028-snapshot-on-disk-format.md)), big-endian:

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

**F8-1.** A resume cursor is a `topologyEpoch:u64` prefix followed by a **per-shard vector**, encoded
(`encodeCursorInto` :445–453 / `decodeCursor` :903–926):

```
[8 u64] topologyEpoch  (REQUIRED; [1, 2^63); 0 ⇒ FRAME_CORRUPT — F5-3; v1 static-N = 1)
[4 u32] count
  repeated count times:
    [4 u32] gid      (shard group id; compared/ordered UNSIGNED; opaque full-range)
    [8 u64] S        (applied-mutation seq processed; [0, 2^63) — F5-1)
```

The wire floor is **12 bytes** (`topologyEpoch:u64 + count:u32`); a payload shorter than that is truncated ⇒
`FRAME_CORRUPT` (the `CURSOR_MIN_BYTES` floor, `decodeCursor` :905–907). This cursor is carried inline by
`WATCH_CREATE` (F/§02 §5.2), `WATCH_PROGRESS` (§5.5), and the `WATCH_CANCELED` `oldest` vector (§5.7).

**F8-2 (invariants — a driver MUST honor).** The `topologyEpoch` **MUST** be in `[1, 2^63)` (`0` ⇒
`FRAME_CORRUPT`; F5-3). Components **MUST** be **strictly ascending by UNSIGNED `gid`** (no duplicates); a
duplicate or out-of-order `gid`, or a negative `S`, **decodes as `FRAME_CORRUPT`** (`WatchCursor` constructor →
mapped at :923–924). `count = 0` is the **empty "from now per shard"** cursor (start at each shard's current
`S`) — **not** "replay all history". A driver **MUST** encode the cursor as an epoch-prefixed vector **even at
N = 1** (epoch `1`, the one-element `(gid=0, S)`); a scalar-cursor assumption is **FORBIDDEN** (§1 A9-1 / §2
W1-1) because it silently breaks when the cluster shards. Two cursors with the same vector but different epochs
are **different topology generations** (not equal). (This is the cursor §02 §3 / W3-5 references; §06 pins its
bytes.)

---

## 9. The TLS profile

**F9-1 (mTLS REQUIRED — or, on a token edge, WANTED).** When TLS is enabled, the edge endpoint in the
**mTLS-only posture requires a client certificate** — `setNeedClientAuth(true)` (`FanOutServer`
:531 / `NettyFanOutServer`). When **token/basic auth is configured** (§6A) the edge **relaxes to
`setWantClientAuth(true)`** (`FanOutServer` :528–531) so a **certificate-less** token client can connect and
authenticate with an `AUTH` frame — a **presented** certificate is still verified and remains the authoritative
identity (§03 AU3-2). Plaintext is permitted **only** for single-node/test (matching the Raft transport policy).
A **production** driver **MUST** require TLS and **MUST NOT** silently fall back to plaintext (a downgrade
footgun on an untrusted network). A driver on an mTLS edge **MUST** present a client certificate during the TLS
handshake (this is the edge **authentication**, §03 AU3-2); a driver on a token/basic edge instead authenticates
with an `AUTH` frame (§6A / §03 AU4-4).

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

**F10-1 (lifecycle).** `connect (TCP)` → **TLS/mTLS handshake** (F9) → **authenticate before any business
frame** (§03 AU4-1: the **mTLS** handshake is the auth on a cert edge; a **token/basic** edge instead sends one
`AUTH` frame here, F10-1e) → **first business frame decides type + version** (F4) → **operate** (stream
`NOTIFY`/`WATCH_EVENT`/`SNAPSHOT_*`/`HEARTBEAT`; client sends `CURSOR_ACK`/`WATCH_CANCEL`, and MAY send
`REFRESH_AUTH` on a token edge) → on disconnect, **resume by re-creating** the subscription/watch on a **new**
connection with a **resume cursor** (F8). There is **no** mid-connection re-pin and **no** server-side session
resumption token.

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

**F10-1d (the post-handshake pre-SUBSCRIBE first-frame deadline).** After a successful mTLS handshake,
an admitted connection **MUST send its first routed control frame (`SUBSCRIBE` or `WATCH_CREATE`) within the
pre-SUBSCRIBE first-frame deadline** — **`configd.edge.firstFrameDeadlineMs`**, default **10000 ms**
(`FanOutServer.DEFAULT_FIRST_FRAME_DEADLINE_MS`; enforced on **both** the JDK and Netty edge transports) — or
the server **reaps** the connection (close + a counted metric). This closes the post-mTLS slow-loris that would
otherwise park a session slot / FD / cumulator indefinitely (it mirrors the Raft plane's
`configd.raft.inboundReadTimeoutMs`, §13). A healthy subscriber sends its `SUBSCRIBE`/`WATCH_CREATE` well
within 10 s, so this never trips it.

> **The deadline is DISARMED after the first routed frame — it is NOT a read-idle timeout for an established
> subscriber.** A fan-out subscriber is idle **by design** (the server pushes; the client rarely sends), so
> once the first frame arrives the deadline is cancelled and the connection is never read-idle-reaped; its
> liveness is instead the server→client `HEARTBEAT` (F6-8) and the driver's own read-idle reconnect (F10-3). A
> driver **MUST** send its first `SUBSCRIBE`/`WATCH_CREATE` promptly after the handshake (do not idle a
> just-opened connection), and **SHOULD** still close connections it is no longer using so it does not hold a
> session slot (F10-2).

**F10-1e (token-auth admission — the `AUTH` frame precedes the first business frame).** On a **token/basic**
edge (the server has token auth configured; §03 AU3-3), a **certificate-less** driver's connection-level
authentication is an **`AUTH` frame (`0x04`), not the handshake**: the driver **MUST** send exactly one `AUTH`
frame (F6A) as its **first routed frame**, before any `SUBSCRIBE`/`WATCH_CREATE`, and only then send the
business frame that pins the business version (F4-1). The lifecycle rules are wire-visible here: the **pre-auth
frame ceiling** (F6A-5) caps the declared length until authenticated; a **single pre-auth `AUTH` attempt** is
allowed (a rejected `AUTH` closes the connection `AUTH_FAIL` — a retry costs a fresh connection); and the
**pre-SUBSCRIBE first-frame deadline** (F10-1d) covers the `AUTH` frame, so a connection that authenticates then
idles without subscribing is still reaped. A driver that authenticates by **mTLS** (a verified client
certificate at the handshake) sends **no** `AUTH` frame and is byte-identical to F10-1 (F6A-4). The full
lifecycle — misuse ⇒ `PROTOCOL_VIOLATION`, `REFRESH_AUTH` renews the same identity, expiry-close ⇒
`CREDENTIAL_EXPIRED` — is normative in §03 AU4/AU5.

**F10-2 (caps).** Deployed limits (`FanOutServer`, `FanOutConnectionDriver`):

| Limit | Value | Constant | Refusal mechanism |
|---|---|---|---|
| concurrent sessions (server-wide) | **1024** (default; **tunable** via `edge.fanout.transport.maxSessions`) | `DEFAULT_MAX_SESSIONS` | **silent: immediate TCP close BEFORE the handshake**, no `ErrorCode` frame (metric `edge_fanout_sessions_refused_total`) — a driver sees a connect/reset/EOF and **MUST retry with backoff** (do **not** treat it as a protocol error) |
| live watches per connection | **1024** | `MAX_LIVE_WATCHES_PER_CONNECTION` | a `BAD_SUBSCRIBE`-class reject **frame**; recoverable by `WATCH_CANCEL` (frees a live slot) |
| lifetime `watch_id`s per connection | **16384** | `MAX_WATCH_IDS_PER_CONNECTION` | a `BAD_SUBSCRIBE`-class reject **frame**; **not** reclaimable in-connection (ids are never reused) — exhausting it ⇒ **reconnect** (a fresh connection resets the budget) |
| watch target length | **1024 bytes** UTF-8 | `WatchTargetValidator.MAX_PATH_BYTES` | reject **frame** |

A driver **MUST** distinguish the **silent, pre-handshake session-cap refusal** (retry/backoff — a routine
capacity condition) from the **frame-bearing** per-connection rejects.

**F10-2a (the aggregate in-flight buffer ceiling — documented-and-bounded).** There is **no** single
global in-flight-bytes counter across connections; the aggregate worst-case receive buffer is bounded by
**(session cap) × (max frame)** = **1024 × 2 MiB ≈ 2 GiB** for the edge plane (the Raft plane's analogous
bound is **1024 × 16 MiB ≈ 16 GiB**, §13). This is **bounded but generous** — each individual frame is
reject-before-allocate length-gated (F3-2) and each connection is independently capped, so no single peer can
exceed `1 × max-frame` at a time, but a full fleet of `maxSessions` hostile connections each mid-frame is the
ceiling. **A cross-connection global ceiling is deliberately not enforced here:** an operator who
needs a tighter aggregate bound **SHOULD** lower `edge.fanout.transport.maxSessions` and/or front the edge with
a connection-count limiter (the product, not either factor alone, is the memory budget). The driver contract is
unaffected — this is an operator sizing note, not a wire rule.

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
`QUARANTINED`. The terminal message MAY carry a human-readable remaining cooldown, but that text is **advisory
diagnostic only** — a driver **MUST NOT** machine-parse it (§07 E6, the untrusted-message rule). Instead, after
a `QUARANTINED` teardown a driver **MUST** back off with its **own bounded backoff** **before** reconnecting/re-
`CREATE`ing — an immediate reconnect-storm is refused regardless of the advisory value. ("No resumption token"
(F10-1) does **not** mean reconnect is stateless: the quarantine state is.)

---

## 11. Fail-closed forward-compatibility (the asymmetry vs. HTTP)

**F11-1 (the frame is fixed-positional and fail-closed).** The EdgeFrame envelope and payloads use a **fixed
positional layout — NOT TLV**. Consequently, at the **frame** level:

- an **unknown `Type`** ⇒ **`FRAME_CORRUPT`** (F3);
- an **unknown / future `Version`** ⇒ **`BAD_WIRE_VERSION`** (F3);
- a **known type on an illegal version** (a watch type off `0x02`, an auth type off `0x04`, or any business/
  watch type on `0x04`) ⇒ **`FRAME_CORRUPT`** (F3 step 6 / F6A-3);
- **trailing bytes** after a known payload ⇒ **`FRAME_CORRUPT`** (F3 step 7);
- the **CRC is verified before** version/type (F3 step 4).

**A future field CANNOT be appended to an existing frame and silently ignored** — the trailing-bytes check
rejects it. This is the **deliberate asymmetry vs. HTTP** (§04), which ignores unknown query params/headers.
To evolve the edge wire a deployment **MUST bump the version byte** (a new frame type or field rides a new
version); older drivers then fail closed on the unknown version rather than mis-parsing. **The auth-phase `0x04`
frames (§6A) are the worked example of an additive version-byte extension**: they added two new frame types on
a new version without touching any `0x01`/`0x02`/`0x03` byte, so a driver written before these frames existed
is byte-identical, and a
driver that does not speak `0x04` simply never emits it. A driver **MUST** treat any unknown
version/type/illegal-type-for-version/trailing-byte as a hard error, never as a forward-compatible extension.

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
      (type `0x0A`)⇒version `0x02`; stamp that version on **every business** frame; a mismatch ⇒
      `BAD_WIRE_VERSION`; **no** hello frame, **no** downgrade (F4). The decoder accepts a version byte of
      `0x01`–`0x04`; anything else ⇒ `BAD_WIRE_VERSION`.
- [ ] **Auth-phase frames** (token/basic edge only): send `AUTH`/`REFRESH_AUTH` on version **`0x04`** with the
      `[scheme u8][len-prefixed fields]` payload (bearer=1 / basic=2; §6A); `0x04` is **version-pin-exempt**
      (interleaves on any business pin), auth types are legal **only** under `0x04`, business/watch types are
      **never** legal under `0x04`, and crossing the two ⇒ `FRAME_CORRUPT` (F6A-3/F6A-4). An mTLS-only driver
      sends **no** `0x04` frame and is byte-identical to a client from before authentication was added.
      Lifecycle/expiry ⇒ §03.
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

---

## 13. The Raft (consensus) plane wire — intra-cluster (normative, non-driver)

**This section is NOT a driver surface.** A configd **driver** speaks only the edge wire (§1–§12) and the HTTP
control plane (§04); it never opens a Raft connection. §13 is specified byte-for-byte for **conformance
completeness** — so the RFC matches the code for **both** planes and a second implementation of the
node-to-node consensus transport (or an auditor) can build/verify it from the RFC alone. The authority is
[`FrameCodec.java`](../../../configd-transport/src/main/java/io/configd/transport/FrameCodec.java),
[`RaftMessageCodec.java`](../../../configd-server/src/main/java/io/configd/server/RaftMessageCodec.java),
[`RaftWireProtocol.java`](../../../configd-transport/src/main/java/io/configd/transport/RaftWireProtocol.java),
[`PeerIdentityPolicy.java`](../../../configd-transport/src/main/java/io/configd/transport/PeerIdentityPolicy.java),
and [`MessageType.java`](../../../configd-transport/src/main/java/io/configd/transport/MessageType.java), pinned
by `WireCompatGoldenBytesTest`. Where this section and ADR-0029's diagram disagree, **the code wins** (ADR-0029
predates the reserved-`epoch` addition).

### 13.1 The Raft frame envelope (byte-for-byte)

**F13-1 (the envelope).** Every consensus frame is a length-prefixed, CRC-trailered envelope
(`FrameCodec` :55–98, :157–180). **All multi-byte integers are big-endian.**

```
 offset  size  field         notes
 0       4     Length  u32   covers the WHOLE frame (Length + header + Payload + CRC)
 4       1     Version u8    0x02 (WIRE_VERSION) — strict single version, NO negotiation (F13-2)
 5       1     Type    u8    MessageType code (F13-4)
 6       4     GroupId u32   Raft group / shard id (opaque in the codec; bounded at demux — F13-3)
 10      8     Term    u64   Raft term (opaque full 64-bit range — pass-through, F13-3)
 18      8     Epoch   u64   RESERVED, MUST BE ZERO (MBZ): zero on send, rejected-if-nonzero on receive
 26      L-30  Payload       message-specific (F13-5)
 L-4     4     CRC32C  u32   Castagnoli (CRC-32C) over bytes [0, L-4) — Length..end-of-payload
```

**`HEADER_SIZE = 26`**, **`TRAILER_SIZE = 4`**, **minimum frame = 30 bytes**, **`MAX_FRAME_SIZE = 16 MiB`**
(`16 * 1024 * 1024`) — **deliberately larger than the edge's 2 MiB** (F2), because an `INSTALL_SNAPSHOT` chunk
carries up to two 4 MiB blobs (F13-5). The CRC is **CRC-32C (Castagnoli), not IEEE/zlib CRC-32**, and is
**integrity, not authenticity** (F2-4 applies identically here; consensus authenticity is mTLS + the
peer-identity binding, F13-6).

**F13-1a (the transport sender-id prefix — OUTSIDE the frame, NOT CRC-covered).** On the wire each frame is
preceded by a 4-byte big-endian **sender NodeId** (`RaftWireProtocol` :46–47, :103–114):

```
[4 bytes: sender NodeId, big-endian] [ FrameCodec frame (itself starting with its own 4-byte Length) ]
```

`SENDER_ID_SIZE = 4`. The prefix lets the receiver read the claimed origin without parsing the payload; it is
**not** covered by the frame CRC and is a **self-declared** value until bound to the peer certificate identity
(F13-6). A reader bounds-checks the frame `Length` against `[30, 16 MiB]` (`isValidFrameLength` /
`peekLength`) **before** allocating the frame buffer.

### 13.2 Version — strict single-version tripwire (no negotiation)

**F13-2.** The Raft plane is **strict single-version**: `Version` **MUST** equal **`0x02`** (`WIRE_VERSION`).
Unlike the edge's first-frame pin (F4), there is **no** version negotiation and **no** accepted second version:
a frame whose version byte is anything other than `0x02` is rejected with `UnsupportedWireVersionException` and
the connection is dropped (mixed-version consensus traffic terminates — every node **MUST** run the same
`WIRE_VERSION`). The reserved **`epoch` MBZ** slot (F13-1) is the forward-compatibility door: a future version
that assigns `epoch` meaning lands with its own peer handshake, and a `0x02` reader **fails closed** on a
non-zero epoch rather than mis-parsing. Bumping `WIRE_VERSION` is a controlled action gated by the
`wire-compat` CI job (any golden-byte change without a version bump fails CI).

### 13.3 Decode & validation order (fail-closed)

**F13-3 (the exact order — `FrameCodec.decode` :263–330).** A decoder validates in this order, so a flipped
version/type/epoch byte reads as **corruption**, never as a misleading version/type error:

1. `data.length ≥ 30` (else `IllegalArgumentException` — "Frame too short").
2. read `Length` (u32 BE); `30 ≤ Length ≤ 16 MiB` (else `IllegalArgumentException` — "out of bounds"); and
   `Length == data.length` (else `IllegalArgumentException` — "length mismatch").
3. **CRC32C over `[0, Length-4)` == trailer** (else `IllegalArgumentException` "CRC32C mismatch") — verified
   **BEFORE** version/type/epoch.
4. `Version == 0x02` (else `UnsupportedWireVersionException`).
5. `Type` resolves via `MessageType.fromCode` (else `IllegalArgumentException` — "Unknown message type code").
6. `Epoch == 0` (else `IllegalArgumentException` — reserved-epoch MBZ, fail closed).

**`Term` and `GroupId` are opaque pass-through at the framing layer** — `FrameCodec` does **not** range-check
them (a deliberate layering decision: the byte-identity tests exercise the full `i64` `Term` range including
`Long.MIN_VALUE`, so the framing codec treats live semantic header fields as opaque). Their invariants are
enforced **above** the codec: a negative/stale `Term` is ignored by the consensus layer (`RaftNode` treats
`term < currentTerm` as stale), and `GroupId` is bounded to `[0, shardCount)` **at the demux**
(`RaftTransportAdapter`), not in `FrameCodec`. An unregistered/out-of-range `GroupId` is a **counted,
rate-limited drop at the demux**, not a codec error.

### 13.4 Message types and the per-message payloads

**F13-4 (the `MessageType` byte).** `MessageType.fromCode` (`:66–71`) resolves these codes; any other byte is
`IllegalArgumentException`:

| Code | Type | Payload (F13-5) |
|---|---|---|
| `0x01` | `APPEND_ENTRIES` | F13-5a |
| `0x02` | `APPEND_ENTRIES_RESPONSE` | F13-5b |
| `0x03` | `REQUEST_VOTE` | F13-5c |
| `0x04` | `REQUEST_VOTE_RESPONSE` | F13-5d |
| `0x05` | `PRE_VOTE` | F13-5c (same shape; `preVote` flag set) |
| `0x06` | `PRE_VOTE_RESPONSE` | F13-5d (same shape) |
| `0x07` | `INSTALL_SNAPSHOT` | F13-5e |
| `0x0F` | `INSTALL_SNAPSHOT_RESPONSE` | F13-5f |
| `0x10` | `TIMEOUT_NOW` | F13-5g |
| `0x11` | `RAFT_COALESCED_HEARTBEAT` | F13-5h |
| `0x12` | `RAFT_WITNESS` | F13-5i |
| `0x13` | `RAFT_WITNESS_REPLY` | F13-5i |
| `0x08`–`0x0E` | `PLUMTREE_*` / `HYPARVIEW_*` / `HEARTBEAT` (**dormant**) | **no codec** — see below |

The `0x08`–`0x0E` codes are **dormant**: `fromCode` accepts them (they are defined enum values) but no payload
codec exists, so a frame of one of these types is a **counted, rate-limited drop** (`raft_unknown_type_drop`),
**not** a per-frame stack-trace print. A conformant consensus peer **MUST NOT** emit them in v1.

**F13-5 (payloads — big-endian; `RaftMessageCodec`).** Every count/length is bounded before allocation and every
fixed-shape decoder is **strict-end** (trailing bytes ⇒ `IllegalArgumentException`). Caps:
`MAX_ENTRIES_PER_APPEND = 10000`, `MAX_COMMAND_LEN = 1 MiB`, `MAX_SNAPSHOT_BLOB_LEN = 4 MiB` (**per blob**),
`MAX_COALESCED_GROUPS = 1024`.

- **F13-5a `APPEND_ENTRIES` (`0x01`).** The frame `Term` = `req.term`.
  ```
  [4 u32] leaderId  [8 u64] prevLogIndex  [8 u64] prevLogTerm  [8 u64] leaderCommit  [4 u32] numEntries
    repeated numEntries times:  [8 u64] index  [8 u64] term  [4 u32] cmdLen  [cmdLen] command
  ```
  `numEntries ≤ 10000` and `numEntries * 20 ≤ remaining` (pre-alloc bound); each `cmdLen ≤ 1 MiB`; the total
  payload + frame header/trailer **MUST** fit `16 MiB`. Each `command` is a **`CommandCodec` blob** (F7-1 — the
  identical grammar the edge `NOTIFY.mutationsBlob` carries: `PUT`/`DELETE`/`BATCH`, `u16` keyLen, `i32` valueLen
  ≤ 1 MiB, `MAX_BATCH_COUNT = 10000`, blank key rejected, total/fail-closed `MalformedCommandException`); an
  empty (0-byte) command is an election no-op.
- **F13-5b `APPEND_ENTRIES_RESPONSE` (`0x02`).** `[1 u8] success  [8 u64] matchIndex  [4 u32] from`.
- **F13-5c `REQUEST_VOTE` / `PRE_VOTE` (`0x03` / `0x05`).** `[4 u32] candidateId  [8 u64] lastLogIndex  [8 u64] lastLogTerm`.
- **F13-5d `REQUEST_VOTE_RESPONSE` / `PRE_VOTE_RESPONSE` (`0x04` / `0x06`).** `[1 u8] voteGranted  [4 u32] from`.
- **F13-5e `INSTALL_SNAPSHOT` (`0x07`).**
  ```
  [4 u32] leaderId  [8 u64] lastIncludedIndex  [8 u64] lastIncludedTerm  [4 u32] offset  [1 u8] done
  [4 u32] dataLen  [dataLen] data  [4 u32] configLen  [configLen] configData
  ```
  `offset ≥ 0` (rejected at decode); `dataLen ≤ 4 MiB` and `configLen ≤ 4 MiB`; the **combined**
  payload + header/trailer **MUST** fit `16 MiB` (`checkInstallSnapshotFitsFrame`). The `configData` blob is
  **optional-trailing** (absent ⇒ decode consumes nothing after `data`); any bytes past it are strict-end
  rejected. Snapshots larger than one chunk are split into ordered chunks each ≤ this bound.
- **F13-5f `INSTALL_SNAPSHOT_RESPONSE` (`0x0F`).** `[1 u8] success  [4 u32] from  [8 u64] lastIncludedIndex`
  then an **optional-trailing** `[4 u32] nextExpectedOffset` (absent decodes to `0`). `lastIncludedIndex` and
  `nextExpectedOffset` are rejected if negative. (This is the **sole** deliberately optional-trailing Raft
  decoder — do not apply strict-end to it.)
- **F13-5g `TIMEOUT_NOW` (`0x10`).** `[4 u32] leaderId`.
- **F13-5h `RAFT_COALESCED_HEARTBEAT` (`0x11`).** Bundles one tick's per-group empty heartbeats for one peer;
  emitted only at N > 1. The frame-header `GroupId`/`Term` are **sentinels (`0`)** — the real per-group ids/terms
  are in the payload and the demux dispatches by **message type**, never by `frame.groupId()`.
  ```
  [4 u32] count
    repeated count times:  [4 u32] gid  [8 u64] term  [4 u32] leaderId  [8 u64] prevLogIndex  [8 u64] prevLogTerm  [8 u64] leaderCommit
  ```
  `count ≤ 1024` and `count * 40 ≤ remaining`; **duplicate `gid` is rejected**; strict-end. Each entry is an
  **empty** AppendEntries (no log entries may be coalesced).
- **F13-5i `RAFT_WITNESS` / `RAFT_WITNESS_REPLY` (`0x12` / `0x13`).** A fixed **29-byte** body (the
  peer-quorum anchor-witness). The frame `Term` = `selfTerm`.
  ```
  [8 u64] selfAnchorSeq  [8 u64] selfTerm  [4 u32] selfVotedFor  [8 u64] seenOfYouSeq  [1 u8] flags
  ```
  `flags` bit0 = `QUERY` (a `RAFT_WITNESS` asking for a reply; a `RAFT_WITNESS_REPLY` never sets it). **The
  sender is NOT in the body** — it is the transport sender-id prefix (F13-1a), injected as the authenticated
  `from` at decode; the receiver keys its witness tables on that authenticated origin, not a spoofable body
  field.

### 13.5 Identity & pre-auth discipline

**F13-6 (mTLS + peer-identity binding).** The consensus transport **requires mutual TLS** (node certificates;
`setNeedClientAuth` on both the JDK `TcpRaftTransport` and the Netty transport). The self-declared sender-id
prefix (F13-1a) and the in-body ids (`leaderId` / `candidateId`) are **attacker-influenceable** bytes; a
`PeerIdentityPolicy` (`PeerIdentityPolicy` :38–178) binds them to the peer's **authenticated certificate
identity** under an **enforce-when-configured, warn-when-not** posture (etcd `--peer-cert-allowed-cn`
semantics):

- **Enforced** (an allow-list is configured via `configd.raft.peerIdentity.allowedNodes` =
  comma-separated `identity=nodeId` pairs, the identity read from a configurable Subject-DN marker RDN —
  `configd.raft.peerIdentity.marker`, **default `CN`**): a connection whose cert identity is not in the
  allow-list is rejected, and **every frame's `senderId` (and the in-body `leaderId`/`candidateId`) MUST equal
  the connection's resolved `NodeId`** — a mismatch drops the connection and increments
  `raft_peer_identity_mismatch`. Both dial directions are bound (the reverse reader of a connection we dialed
  is pinned to the known target), so **every** frame on **every** connection is identity-bound when enforced.
- **Unenforced** (the default / a single-shared-cert fleet): the transport keeps CA-chain-only admission and
  emits a **loud one-time warning** that peer-identity verification is unconfigured. In this posture a
  cert-valid peer **can** forge another node's id. A misconfigured (non-blank but empty) allow-list **fails
  closed** at boot.

**Trust model:** consensus is **crash-tolerant, not Byzantine-tolerant** — the term/log safety checks blunt a
forged frame's impact, and the identity binding, **when enforced**, closes cert-valid-peer impersonation of
another member's id (forged votes/acks/witness attestations). A conformant peer **MUST NOT** treat the CRC or a
self-declared prefix as authentication (F2-4 / F13-1a).

### 13.6 Timeouts, connection caps, and the aggregate ceiling

**F13-7 (bounded timeouts & caps — `RaftWireProtocol`).**

| Control | Value | Constant / property |
|---|---|---|
| TCP connect timeout | **1000 ms** | `CONNECT_TIMEOUT_MS` |
| TLS handshake timeout | **2000 ms** | `HANDSHAKE_TIMEOUT_MS` |
| inbound read-idle deadline | **15000 ms** | `configd.raft.inboundReadTimeoutMs` (`inboundReadTimeoutMs()`) |
| max concurrent inbound connections | **1024** | `configd.raft.maxInboundConnections` (`maxInboundConnections()`) |
| per-peer outbound queue | **1024** frames, **drop-oldest on overflow** | `OUTBOUND_QUEUE_CAPACITY` |

Unlike the edge (idle-by-design subscribers, F10-1d), a Raft peer exchanges heartbeats continuously, so the
inbound socket carries a **read-idle deadline** (15 s ≫ the heartbeat interval): a stalled/slow-drip peer fails
its read and releases the FD rather than parking a reader (the slow-loris control). The bounded outbound queue
drops the oldest undeliverable frames (counted) rather than blocking — Raft re-sends on the next heartbeat.

**F13-8 (the aggregate ceiling — Raft side).** As on the edge (F10-2a), there is no global in-flight-bytes
counter; the aggregate worst case is **`maxInboundConnections × MAX_FRAME_SIZE` = 1024 × 16 MiB ≈ 16 GiB**.
Bounded but generous; an operator needing a tighter bound lowers `configd.raft.maxInboundConnections` (the
product, not either factor alone, is the memory budget).

### 13.7 Fail-closed reject taxonomy (Raft)

**F13-9.** A conformant consensus decoder maps every malformed-input class to one of three exception types, and
**a decode failure desyncs the framing stream ⇒ the connection is dropped** (a handler-level throw on an
already-decoded frame does **not** desync framing — the reader continues):

| Input class | Result |
|---|---|
| too short, `Length` out of `[30, 16 MiB]`, `Length ≠ data.length`, CRC mismatch, non-zero reserved `epoch`, truncated/over-declared/negative length or count, trailing bytes | `IllegalArgumentException` |
| version byte ≠ `0x02` | `UnsupportedWireVersionException` |
| malformed nested command grammar (unknown type, over-declared/blank key, trailing) | `MalformedCommandException` (a subtype of `IllegalArgumentException`) |
| dormant type (`0x08`–`0x0E`) or unregistered/out-of-range `GroupId` | counted, **rate-limited drop** at the demux (not an exception path) |
