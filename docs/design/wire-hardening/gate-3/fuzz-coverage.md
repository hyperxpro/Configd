# Wire-Hardening Arc — Gate 3 · Structured fuzz coverage

Gate 3 puts EVERY frame codec under structured (jqwik property-based) fuzzing. Where Gate 2 wrote
hand-aimed reject tests for each finding, Gate 3 lets the machine sweep the whole input space and
asserts a single **resource oracle** for every codec × every frame type: on any hostile input the
parser must either **clean-accept** (a well-formed value) or **clean-reject** (a mapped domain
exception) — never crash, hang, over-allocate, or mis-parse.

## The oracle (per codec)

| Codec | Legal accept | Legal reject | FORBIDDEN (a defect if it escapes) |
|---|---|---|---|
| `FrameCodec` (transport envelope) | `Frame` | `IllegalArgumentException`, `UnsupportedWireVersionException`, `BufferUnderflowException`¹ | OOM, NPE, AIOOBE, `NegativeArraySizeException`, hang |
| `RaftMessageCodec` | `RaftMessage` / group-map | `IllegalArgumentException` | **`BufferUnderflowException`**², OOM, NPE, AIOOBE, `NegativeArraySizeException`, hang |
| `CommandCodec` | `DecodedCommand` | `MalformedCommandException` (only) | **any other throwable**, incl. a bare `IllegalArgumentException` / `BufferUnderflowException`, OOM, hang |
| `EdgeFrameCodec` | `EdgeFrame` | `CodecException` (only) | any other throwable, OOM, hang |
| `EdgeSnapshotCodec` | `ConfigSnapshot` / body | `IllegalArgumentException` | `BufferUnderflowException`, OOM, `NegativeArraySizeException`, NPE, AIOOBE, hang |

¹ `FrameCodec` admits `BufferUnderflowException` *defensively* — its length gate makes it unreachable
in practice, but it is a benign bounded JDK exception, so the suite fails loud only if a *forbidden*
throwable escapes. ² The Raft/Command/Snapshot codecs are **stricter**: every length read is
`checkRemaining`/`checkBlobLen`/`readBoundedLen`-gated before the read, so an underflow escaping is a
real hole — it is forbidden, not admitted.

## Per-codec × per-frame coverage

Legend: **F** = adversarial byte-level fuzz (resource oracle) · **P** = property round-trip · **B** =
bounded-allocation (tiny frame, huge declared count/len → reject pre-alloc) · **R** = permanent
hardcoded regression case (crash corpus).

### `FrameCodec` — transport frame envelope (`configd-transport`) — pre-existing, audited
`FrameCodecFuzzTest` (F,B,R) + `FrameCodecPropertyTest` (P) + `FrameCodecCrcVectorTest` /
`…EpochReservation` / `…EncoderBounds`.

| Field / frame | F | P | B | R |
|---|---|---|---|---|
| length prefix (neg / 0 / <min / >MAX / MAX_INT) | ✓ | ✓ | ✓ | ✓ |
| version byte, type code | ✓ | ✓ | | ✓ |
| CRC32C trailer (single-bit flip) | ✓ | ✓ | | |
| truncate-at-every-offset, trailing garbage | ✓ | ✓ | | |
| every `MessageType` envelope round-trip | ✓ | ✓ | | |

No Gate-2 reject path was added *inside* `FrameCodec` — the WH-07 negative-term reject was **reverted**
during Gate 2 (FrameCodec is the pure framing layer; `term`/`groupId` are opaque pass-through and the
`groupId` bound lives at the demux, not the codec). So the envelope needs no Gate-3 extension.

### `RaftMessageCodec` — consensus plane (`configd-server`) — **NEW: `RaftMessageCodecFuzzTest`**
`RaftMessageCodecFuzzTest` (F,B,R) + `RaftMessageCodecPropertyTest` (P, pre-existing).

| Frame type (decode surface) | F | P | B | R |
|---|---|---|---|---|
| `APPEND_ENTRIES` (`decode`) | ✓ | ✓ | ✓ (numEntries, cmdLen) | ✓ (trailing WH-06) |
| `APPEND_ENTRIES_RESPONSE` | ✓ | ✓ | | |
| `REQUEST_VOTE` / `PRE_VOTE` | ✓ | ✓ | | |
| `REQUEST_VOTE_RESPONSE` / `PRE_VOTE_RESPONSE` | ✓ | ✓ | | |
| `INSTALL_SNAPSHOT` (`decode`) | ✓ | ✓ | ✓ (dataLen) | ✓ (neg-offset WH-05) |
| `INSTALL_SNAPSHOT_RESPONSE` | ✓ | ✓ | | |
| `TIMEOUT_NOW` | ✓ | ✓ | | |
| `RAFT_COALESCED_HEARTBEAT` (`decodeCoalescedHeartbeat`) | ✓ | — | ✓ (groupCount) | ✓ (dup group id) |
| `RAFT_WITNESS` / `RAFT_WITNESS_REPLY` (`decodeWitness`) | ✓ | — | | ✓ (truncated body) |

Every one of the 12 raft decode surfaces is dispatched through the oracle on arbitrary +
boundary-sized + mutated + truncated payloads. WH-05 (negative `InstallSnapshot.offset`) and WH-06
(strict-end trailing-byte rejection) — the Raft-plane Gate-2 reject paths — are machine-swept here.

### `CommandCodec` — committed log command (`configd-config-store`) — **NEW: `CommandCodecFuzzTest`**
`CommandCodecFuzzTest` (F,B,R) + `CommandCodecPropertyTest` (P, pre-existing). This is the WH-01/WH-02
total-codec property under the fuzzer: a malformed committed command must surface as
`MalformedCommandException`, never a `BufferUnderflowException`, or every replica crash-loops on
apply + WAL replay.

| Frame type | F | P | B | R |
|---|---|---|---|---|
| `NOOP` (empty payload sentinel) | ✓ | ✓ | | ✓ |
| `PUT` (keyLen u16, valueLen u32) | ✓ | ✓ | ✓ (valueLen) | ✓ (keyLen overrun, neg valueLen, blank key) |
| `DELETE` | ✓ | ✓ | | |
| `BATCH` (count u32 + nested PUT/DELETE) | ✓ | ✓ | ✓ (count) | ✓ (underfilled, unknown nested type) |
| unknown type discriminant | ✓ | ✓ | | ✓ |

Nested-batch mutation bytes are fuzzed directly (the deepest recursion in the grammar) so a nested
truncation is proven to surface as `MalformedCommandException`, not an underflow.

### `EdgeFrameCodec` — edge/distribution plane (`configd-distribution-service`) — **EXTENDED**
`EdgeFrameCodecFuzzTest` (F,B,R — **+3 Gate-2 sweeps added**) + `EdgeFrameCodecPropertyTest` (P) +
`EdgeFrameCodecStrictnessTest` + `EdgeSubscribeBoundsTest` + golden-fixture tests (V1/V2/V3).

| Frame type / field | F | P | B | R |
|---|---|---|---|---|
| length prefix, version, type, CRC | ✓ | ✓ | ✓ | ✓ |
| `SUBSCRIBE` `prefixCount` (WH-12 tight bound + `MAX_PREFIXES`) | **✓ (new sweep)** | | ✓ | ✓ (element-cap boundary) |
| `SUBSCRIBE_OK`, `CURSOR_ACK`, `HEARTBEAT`, `ERROR_CLOSE` | ✓ | ✓ | | |
| `NOTIFY` payload byte-cap (WH-14 `MAX_NOTIFY_BATCH_BYTES`) | **✓ (new sweep)** | ✓ (encode-cap) | | ✓ (cap boundary) |
| `SNAPSHOT_BEGIN` / `SNAPSHOT_CHUNK` / `SNAPSHOT_END` | ✓ | ✓ | | |
| `WATCH_*` (0x02): create/cancel/event/progress/canceled/created/snapshot-* | ✓ | ✓ | ✓ (cursor count) | ✓ (dup/desc gid, bad val_len, type-on-v1) |

The two new client-facing amplifier bounds (WH-12 `prefixCount`, WH-14 NOTIFY byte-cap) are swept
across the full int range with CRC repaired, so the bound — not the CRC — is what fires. The
`SNAPSHOT_CHUNK` **decode** cap is bounded by `MAX_EDGE_FRAME_SIZE` and covered by the arbitrary-byte
oracle; its **encode** cap `MAX_SNAPSHOT_CHUNK_BYTES` is pinned by `EdgeSnapshotCodecTest`.

### `EdgeSnapshotCodec` — snapshot body (`configd-distribution-service`) — **NEW: `EdgeSnapshotCodecFuzzTest`**
`EdgeSnapshotCodecFuzzTest` (F,P,B,R) + `EdgeSnapshotCodecTest` (P, pre-existing). Previously had
**neither** fuzz nor a hostile-input property — this is the largest net-new gap closed.

| Surface | F | P | B | R |
|---|---|---|---|---|
| `deserialize` (seq u64, entryCount, keyLen/valLen ≤ 1 MiB) | ✓ | ✓ (round-trip) | ✓ (keyLen overrun) | ✓ (short header, neg count, over-declare, cap) |
| `serialize` (round-trip + re-serialize byte-identity) | | ✓ | | |
| `chunk` (chunkBytes range) | | | | ✓ (hostile chunkBytes) |
| `reassemble` (contiguous 0..n-1 index run) | ✓ | ✓ (lossless) | | ✓ (gap/dup/descending) |

## Tries budget (sized for the 2-vCPU CI box)

Decode is microseconds; every class below is well under ~15 s wall on the throttled box. Each
`@Property` pins a **fixed seed** so a failing input is reproducible and lands in the corpus (matching
the golden-fixture discipline); the `tries = 1` cases are hardcoded regression `byte[]`s.

| Test class | Module | Broad oracle tries | Notes |
|---|---|---|---|
| `RaftMessageCodecFuzzTest` | configd-server | 2000 + 1500 (boundary) | 800 mutation, 60 truncate-every-offset; 14 tests, ~5.8 s |
| `CommandCodecFuzzTest` | configd-config-store | 3000 + 800 + 2000 (typed-tail) | 1500 nested-batch; 17 tests, ~10 s |
| `EdgeSnapshotCodecFuzzTest` | configd-distribution-service | 3000 + 800 | 800 mutation, 300 round-trip; 15 tests, ~12 s |
| `EdgeFrameCodecFuzzTest` (extended) | configd-distribution-service | 2000 + 1500 (prefixCount sweep) | 14 tests, ~6.9 s |

## Attack-matrix seeding

The generators are weighted toward the Gate-1 attack-matrix hostile shapes: **oversized length**
(MAX_INT, cap+1), **truncated** (every-offset + boundary sizes), **negative / overflow count**
(numEntries, groupCount, prefixCount, batch count, entryCount), **type confusion** (arbitrary type
byte, WATCH-on-v1), **trailing bytes** (WH-06 strict-end), and **unknown version**. Size distributions
are pinned at each codec's fixed-header frontier (where off-by-one index math is likeliest).

## Findings

**No crash, hang, over-allocation, or mis-parse was found.** Every new suite is green per-module. The
Gate-2 hardening holds under machine-driven fuzzing: each codec is either total (fail-closed to its
mapped domain exception) or bounded (a tiny hostile frame declaring a huge count/length is rejected
before the allocation), across the full swept input space. No new crash-corpus regression case was
required beyond the attack-matrix cases already encoded as `tries = 1` `byte[]` fixtures.

## Out-of-pure-codec scope (noted, not a Gate-3 gap)

The two **stateful** hostile-accumulation paths are not pure-codec and are out of this gate's scope:
`EdgeClientCore` snapshot-chunk accumulation (WH-13) and `RaftNode` InstallSnapshot reassembly. Both
are bounded by their Gate-2 caps (`SnapshotBegin.chunkCount`/`totalBytes`, `maxReassembledSnapshotBytes`)
and belong to Gate 4's integration/E2E coverage, not the codec fuzz layer.
