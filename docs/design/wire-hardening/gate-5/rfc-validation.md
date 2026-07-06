# Gate 5 — RFC ⇄ Code Spec Validation (wire-hardening arc)

**Scope.** Make the driver-protocol RFC (`docs/rfc/driver-protocol/`) COMPLETELY and NORMATIVELY specify the
**hardened** wire for **both** planes, matching the code **byte-for-byte**, so a driver author can build a
conformant, safe client (safe even against a hostile server) from the RFC alone. Docs-only gate — **no source
changes**. Everything left uncommitted for the lead to integrate.

**Source of truth (validated against, not modified):**
`FrameCodec.java`, `RaftMessageCodec.java`, `RaftWireProtocol.java`, `PeerIdentityPolicy.java`,
`MessageType.java` (Raft plane); `EdgeFrameCodec.java`, `EdgeFrame.java`, `WatchCursor.java`, `FrameType.java`,
`ErrorCode.java`, `EdgeSnapshotCodec.java`, `CommandCodec.java` (edge plane); the golden vectors in
`EdgeFrameGoldenBytes.java` and `WireCompatGoldenBytesTest`.

---

## 1. Drift found and fixed (SPEC was wrong → corrected to match CODE)

| # | RFC claim (before) | Code truth (file:line) | Fix |
|---|---|---|---|
| **D1 — driver-breaking** | `06 F6-1` `SUBSCRIBE` payload had **no `topologyEpoch`** field (fullStore → prefixes → resumeCursor …) | `EdgeFrameCodec.encodeSubscribeInto` :318 writes `topologyEpoch:u64` between prefixes and `resumeCursor`, **unconditionally on all versions**; golden `subscribe_full_store.bin` carries `…0000000000000001…` | Added `[8 u64] topologyEpoch` in the exact position + a golden-hex note. A driver following the old layout emits a SUBSCRIBE **8 bytes short ⇒ `FRAME_CORRUPT`**. |
| **D2 — driver-breaking** | `06 F8-1` and `02 W3-5` cursor vector = `[count u32]( gid u32  S u64 )*` (**no epoch prefix**) | `EdgeFrameCodec.encodeCursorInto` :445–453 writes `topologyEpoch:u64` **then** `count`; `decodeCursor` :903–926 enforces a 12-byte floor + epoch≠0 | Added `[8 u64] topologyEpoch` prefix + the 12-byte `CURSOR_MIN_BYTES` floor to both F8-1 and W3-5. |
| **D3** | `07 §3` titled "the **11**"; `code() (1..11)`; only 11 rows | `ErrorCode.java` :14–107 has **12** values — `STALE_TOPOLOGY(12)` | Retitled "the 12"; `1..12`; added the code-12 row; updated E3-3 scope list, E7, checklist. Added STALE_TOPOLOGY to `02 W3-5` and `06 F5-3/F6-9`. |
| **D4** | `07 E3-1` row 1: version byte ∈ `{0x01,0x02}` | `EdgeFrameCodec.decode` :634–635 accepts `{0x01,0x02,0x03}` | Corrected to `{0x01,0x02,0x03}`. |
| **D5** | `06 F10-1d`: "**no** post-handshake idle timeout — a known v1 limitation" | `FanOutServer` :83–106 — `configd.edge.firstFrameDeadlineMs`, default **10000 ms**, on both transports; disarmed after the first routed frame | Rewrote F10-1d to specify the pre-SUBSCRIBE first-frame deadline that reaps a silent post-mTLS connection but does **not** read-idle-reap an established (idle-by-design) subscriber. |
| **D6** | `06` intro: Raft frame `HEADER_SIZE = 18`, "with Group-Id/Term" | `FrameCodec` :76 `HEADER_SIZE = 26` (adds the 8-byte reserved `epoch` MBZ slot, v2) | Corrected to 26 + reserved-epoch note; pointed to the new §13. |
| **D7** | `06 F6-9`: "ErrorCode 1..11"; intro "`u8` code 1–11" | 12 codes | Corrected to 1..12 with the STALE_TOPOLOGY note. |

## 2. Gaps filled (CODE behavior that the RFC did not specify at all)

| # | Gap | Where added |
|---|---|---|
| **G1** | The **entire Raft (consensus) plane wire** was specified nowhere (04/05 are HTTP; 06 was edge-only, referencing only a stale ADR diagram). | New **`06 §13`** — normative, non-driver: envelope (`HEADER_SIZE=26`, 16 MiB, reserved-epoch MBZ), sender-id prefix, strict single-version `0x02` tripwire, decode/validation order, every `MessageType` payload + cap (AppendEntries, InstallSnapshot, votes, TimeoutNow, CoalescedHeartbeat, Witness), dormant-type drop, mTLS + `PeerIdentityPolicy` binding, timeouts/caps, aggregate ceiling, fail-closed exception taxonomy. |
| **G2** | `topologyEpoch` field constraint `[1, 2^63)` (0 reserved-illegal) — the A4 binding + STALE_TOPOLOGY reaction. | New **`06 F5-3`**; woven into F5-1 table, F6-1, F8, `02 W3-5`, `07` code-12 row. |
| **G3** | CRC32C is **integrity, not authenticity** (auth = TLS/mTLS + Ed25519). | New **`06 F2-4`** (+ referenced from F9 / §13). |
| **G4** | **WH-16** aggregate in-flight buffer ceiling (Gate 2 deferred its documentation here). | New **`06 F10-2a`** (edge: 1024 × 2 MiB ≈ 2 GiB) and **`06 F13-8`** (Raft: 1024 × 16 MiB ≈ 16 GiB), with the operator sizing note. |

## 3. Frame-by-frame validation table (RFC claim → code → match)

### Edge envelope + v1 frames (`EdgeFrameCodec`, `EdgeFrame`, `FrameType`, `ErrorCode`)

| RFC | Claim | Code (file:line) | Result |
|---|---|---|---|
| F2-1 | `[L u32][ver u8][type u8][payload][crc32c u32]`, HEADER=6, TRAILER=4, min 10, MAX 2 MiB | `EdgeFrameCodec` :101–114 | ✓ |
| F2-4 | CRC = integrity not authenticity | :277–280 (un-keyed CRC32C); F9 (TLS) | ✓ added |
| F3-1 | order: len-bounds → len==data → CRC → version(+pin) → type → payload → strict-end | :597–683 | ✓ |
| F4 | version pin `0x01`/`0x02`/`0x03`, first-frame-wins, mismatch ⇒ BAD_WIRE_VERSION | :633–649, `peekVersion` :1161–1168 | ✓ |
| F5-1 | seq/ts `u64` fields `[0,2^63)`; high-bit ⇒ FRAME_CORRUPT | record ctors + :678–682 | ✓ |
| F5-2 | `latestSeq` raw; `failoverResumeCursor` = `[0,2^63) ∪ {−1}` | `EdgeFrame.Subscribe` :118–121 | ✓ |
| **F5-3** | `topologyEpoch` `[1,2^63)`, 0 ⇒ FRAME_CORRUPT | ctor :111–114, `decodeSubscribe` :729–733, `decodeCursor` :908–912 | ✓ **added** |
| **F6-1** | SUBSCRIBE incl. `topologyEpoch` between prefixes and resumeCursor | `encodeSubscribeInto` :306–327 | ✓ **drift fixed (D1)** |
| F6-1a | `0x03` trailing `acceptsFiltered u8` | :324–326 / :740–746 | ✓ |
| F6-2 | SUBSCRIBE_OK `[8 latestSeq][1 mode]` | :329–336 / :755–773 | ✓ |
| F6-2a | `0x03` trailing `filtered u8` | :333–335 / :765–771 | ✓ |
| F6-3 | NOTIFY `[count u32]( seq,commitTs,fromV,toV, batchLen+blob, i32 sigLen(+sig), epoch, nonceLen+nonce )` | :338–380 / :775–833 | ✓ |
| F6-4/5/6 | SNAPSHOT_BEGIN/CHUNK/END | :382–397 / :849–867 | ✓ |
| F6-7 | CURSOR_ACK `[8 seq]` | :291 / :693 | ✓ |
| F6-8 | HEARTBEAT `[8 latestSeq][8 serverNow]` | :399–402 / :694 | ✓ |
| F6-9 | ERROR_CLOSE `[1 code][4 msgLen][msg]`; codes **1..12** | :404–409 / :869–879; `ErrorCode` :14–107 | ✓ **count fixed (D7)** |
| F7-1 | nested `CommandCodec` PUT/DELETE/BATCH, u16 keyLen, i32 valLen≤1 MiB, count≤10000 | `CommandCodec` :39–372 | ✓ |
| F7-2 | ADR-0028 snapshot body + 3-form skip-unknown TLV trailer | `EdgeSnapshotCodec` :43–234 | ✓ |
| **F8** | cursor `[8 topologyEpoch][4 count]( gid u32  S u64 )`, 12-byte floor, ascending unsigned gid | `encodeCursorInto` :445–453, `decodeCursor` :903–926 | ✓ **drift fixed (D2)** |
| F10-2 | caps: 1024 sessions / 1024 live watches / 16384 lifetime ids / 1024 B target | `FanOutServer` :109–118 etc. | ✓ |
| **F10-2a** | WH-16 edge aggregate ≈ 2 GiB | maxSessions × MAX_EDGE_FRAME_SIZE | ✓ **added (G4)** |
| **F10-1d** | first-frame deadline 10 s, disarmed after first frame | `FanOutServer` :83–106 | ✓ **drift fixed (D5)** |
| F11 | fixed-positional, reject-unknown-type/version, strict-end; skip-unknown only in snapshot trailer | :661–674, F7-2 | ✓ |

### Edge watch frames `0x0A`–`0x12` (`02 §5.2–5.8` + `EdgeFrame`/`EdgeFrameCodec`)

| RFC | Claim | Code | Result |
|---|---|---|---|
| W3-5 | cursor now epoch-prefixed | `encodeCursorInto` :445–453 | ✓ **drift fixed (D2)** |
| 5.2 WATCH_CREATE | `[8 watchId][1 scope][1 targetKind][4 pathLen][path][cursor][1 flags]` | :455–464 / :928–943 | ✓ |
| 5.3 WATCH_CREATED | `[8 watchId][4 shardCount]( gid u32  latestSeq u64  mode u8 )` | :466–475 / :945–963 | ✓ |
| 5.4 WATCH_EVENT | `[8 watchId][4 gid][8 S][8 commitTs][4 changeCount]( keyLen,key,kind,i32 valLen,val )` | :477–497 / :965–1007 | ✓ |
| 5.5 WATCH_PROGRESS | `[8 watchId][cursor][8 serverNow]` | :499–503 / :1009–1018 | ✓ |
| 5.6 WATCH_CANCEL | `[8 watchId]` | :295 / :697 | ✓ |
| 5.7 WATCH_CANCELED | `[8 watchId][1 code][1 hasOldest][cursor?][4 msgLen][msg]` | :505–517 / :1020–1040 | ✓ |
| 5.8 WATCH_SNAPSHOT_* | BEGIN/CHUNK/END with leading `(watchId, gid)` | :519–544 / :1042–1083 | ✓ |
| W5-11 | watch types legal **only** under `0x02`; `0x01`/`0x03` ⇒ FRAME_CORRUPT | :256–260 / :661–665 | ✓ |

### Raft plane (`06 §13` — all newly added, validated against code)

| RFC | Claim | Code (file:line) | Result |
|---|---|---|---|
| F13-1 | envelope `[L][ver 0x02][type][gid u32][term u64][epoch u64 MBZ][payload][crc]`, HEADER=26, MAX 16 MiB | `FrameCodec` :55–98, :157–180 | ✓ |
| F13-1a | 4-byte BE sender-id prefix outside frame, not CRC-covered | `RaftWireProtocol` :46–47, :103–114 | ✓ |
| F13-2 | strict single version `0x02`, no negotiation; epoch MBZ door | :70, :305–323 | ✓ |
| F13-3 | decode order len→CRC→version→type→epoch; term/gid opaque (WH-07) | `decode` :263–330; gate-2 workstream-D layering note | ✓ |
| F13-4 | MessageType 0x01–0x13; 0x08–0x0E dormant ⇒ rate-limited drop (WH-10) | `MessageType` :8–71; workstream-D | ✓ |
| F13-5a | AppendEntries + caps 10000/1 MiB, count×20≤rem, strict-end | `RaftMessageCodec` :357–436 | ✓ |
| F13-5e | InstallSnapshot, offset≥0, 4 MiB/blob, combined-fits-frame, optional configData, strict-end | :497–562 | ✓ |
| F13-5f | InstallSnapshotResponse optional-trailing nextExpectedOffset | :576–604 | ✓ |
| F13-5h | CoalescedHeartbeat count≤1024, ×40≤rem, no dup gid, sentinel header, strict-end | :265–353 | ✓ |
| F13-5i | Witness 29-byte body, sender = transport prefix, QUERY flag | :109–123, :623–678 | ✓ |
| F13-6 | mTLS + PeerIdentityPolicy enforce-when-configured/warn-when-not, marker default CN | `PeerIdentityPolicy` :38–178 | ✓ |
| F13-7 | connect 1 s / handshake 2 s / read-idle 15 s / maxInbound 1024 / outbound 1024 drop-oldest | `RaftWireProtocol` :53–129 | ✓ |
| **F13-8** | WH-16 Raft aggregate ≈ 16 GiB | maxInboundConnections × MAX_FRAME_SIZE | ✓ **added (G4)** |
| F13-9 | IllegalArgumentException / UnsupportedWireVersionException / MalformedCommandException taxonomy | `FrameCodec`, `RaftMessageCodec`, `CommandCodec` | ✓ |

## 4. Fail-closed / reject behavior — normatively specified (feeds §07)

Every malformed-input class now maps to a named result a conformant driver/peer reproduces identically:

- **Edge:** unknown version → `BAD_WIRE_VERSION`; oversize length → `FRAME_TOO_LARGE`; truncation /
  length-mismatch / negative-or-overflow length/count / unknown type / trailing bytes / bad cursor / epoch=0 →
  `FRAME_CORRUPT` (`CodecException` → `ErrorCode`). (`06 F3`, `F5-3`, `F11`; `07 E3`.)
- **Raft:** framing/length/CRC/MBZ-epoch/negative/overflow/trailing → `IllegalArgumentException`; version →
  `UnsupportedWireVersionException`; nested command → `MalformedCommandException`; dormant type / bad groupId →
  counted rate-limited drop. (`06 F13-3`, `F13-9`.)
- CRC verified **before** version/type on both planes; CRC is integrity-not-authenticity (`06 F2-4`).

## 5. RFC files enhanced (in place, uncommitted)

- **`06-wire-framing.md`** — the core: F2-4 (CRC integrity), F5-1/F5-3 (topologyEpoch), **F6-1 (SUBSCRIBE
  epoch — driver-breaking drift fixed)**, F6-9 (12 codes), F8 (cursor epoch + floor), F10-1d (first-frame
  deadline), F10-2a (WH-16 edge), intro/1.2 (Raft scope), and the new **§13 Raft-plane wire** (F13-1…F13-9).
- **`02-watches.md`** — W3-5 cursor epoch prefix + STALE_TOPOLOGY resume rule.
- **`07-errors.md`** — 11→12 codes, STALE_TOPOLOGY(12) row + scope/retry/checklist, `{0x01,0x02,0x03}`.

## 6. Conformance conclusion

A **safe conformant client is buildable from the RFC alone.** The two latent driver-breaking drifts (the
missing `SUBSCRIBE`/cursor `topologyEpoch`, D1/D2) — which would have made every SUBSCRIBE/WATCH_CREATE 8 bytes
short and rejected as `FRAME_CORRUPT` — are corrected and anchored to the golden bytes. Both planes are now
specified field-by-field with offsets, widths, endianness, caps, the reject-before-allocate discipline, the
fail-closed taxonomy, the mTLS/identity trust model, timeouts, and the WH-16 aggregate bound. No overclaim
remains: every layout and bound in the RFC is tied to a codec constant/check at a cited `file:line`, and where
the RFC disagreed with the code the RFC was corrected to the code.
