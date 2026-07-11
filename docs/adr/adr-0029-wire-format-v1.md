# ADR-0029: Wire Format v1 - Version Byte + CRC32C Trailer

**Status:** Accepted (2026-04-25)
**Supersedes:** ADR-0010 (v0 wire format - header-only, no version, no checksum)
**Related:** ADR-0028 (snapshot on-disk format - same TLV-style forward-compat philosophy applied to a different boundary)

> **Where the current bytes live.** This ADR is the canonical origin of the framing *discipline*
> - a version byte, a CRC32C-Castagnoli trailer, CRC-before-type validation, and fail-closed
> forward-compat - and the wire-compat CI gate still cites its section 8.10. The *concrete* diagram
> below predates later wire-hardening additions (the Raft frame is now `HEADER_SIZE = 26` with an
> 8-byte reserved `epoch` slot). For the byte-authoritative wire layout, read
> [`rfc/driver-protocol/06-wire-framing.md`](../rfc/driver-protocol/06-wire-framing.md) - the edge
> plane in section 1-12 and the Raft plane in section 13 - which is validated against the codecs and
> the golden fixtures. Where this ADR's diagram and that section disagree, **the code (and the RFC)
> win**.

## Context

The v0 wire format (`[length(4)][type(1)][groupId(4)][term(8)][payload]`) shipped in
internal builds but never to a customer. It had no version byte: any future
change to the wire format would be a hard break with no possibility of a
rolling upgrade. The test suite that pins the v1 format
(`FrameCodecPropertyTest`, `WireCompatGoldenBytesTest`,
`RaftMessageCodecPropertyTest`) was written ahead of the implementation
that satisfies it.

This ADR records the v1 format and the forward-compatibility contract
for v2.

## Decision

### Wire format v1 layout

```
+--------+---------+------+----------+--------+----------+----------+
| Length | Version | Type | Group-Id |  Term  | Payload  | CRC32C   |
| 4 B    | 1 B     | 1 B  | 4 B      | 8 B    | variable | 4 B      |
+--------+---------+------+----------+--------+----------+----------+
   <----------- HEADER_SIZE = 18 B ----------->          ^ TRAILER
```

| Field | Size | Notes |
|-------|------|-------|
| Length | 4 B BE | Total frame size in bytes, including length and trailer. Lower bound `HEADER_SIZE + TRAILER_SIZE = 22`; upper bound `MAX_FRAME_SIZE = 16 MiB`. |
| Version | 1 B | `WIRE_VERSION = 0x01`. Any other value triggers `UnsupportedWireVersionException`. |
| Type | 1 B | `MessageType` code (see `MessageType.java`). Unknown codes rejected by `MessageType.fromCode`. |
| Group-Id | 4 B BE | Raft group identifier. |
| Term | 8 B BE | Raft term. |
| Payload | N B | Message-specific bytes; opaque to the framing layer. |
| CRC32C | 4 B BE | Castagnoli polynomial (`java.util.zip.CRC32C`) over `[Length .. end of Payload]` - i.e., everything except the trailer itself. |

### Decode validation order

The order is deliberate; distinct exceptions narrow the diagnostic
question to a single layer.

1. **Buffer too short for header + trailer** -> `IllegalArgumentException`.
2. **Length prefix out of bounds or != actual length** -> `IllegalArgumentException`.
3. **CRC32C trailer mismatch** -> `IllegalArgumentException` with prefix `"CRC32C mismatch"`. Verified **before** any other field so a single bit-flip surfaces unambiguously as "corruption" rather than as a misleading "wire-version mismatch" or "unknown type code" - those would point an operator at the wrong root cause (deployment misconfiguration vs. hardware fault).
4. **Version byte != `WIRE_VERSION`** -> `UnsupportedWireVersionException` (carries the observed version for upstream logging).
5. **Type code not in `MessageType`** -> `IllegalArgumentException` (delegated to `fromCode`).

The CRC check covers all bytes from the length field through end of
payload, so once it passes, the version and type bytes are
trustworthy.

### Why CRC32C

- **Castagnoli (CRC32C)** is the modern default; `java.util.zip.CRC32C` is JDK 9+
  (we run JDK 25, so dependency is fine).
- TLS already covers the connection layer for confidentiality and
  integrity against an active attacker. The CRC32C is **defense in
  depth** against bit-flip / bug-induced corruption inside a TLS
  session - for example a buffer-pool reuse bug that hands the
  encoder a stale buffer.
- 4 bytes of overhead per frame is negligible against the typical 18 B
  header + payload.
- CRC32C is hardware-accelerated on x86 (SSE 4.2 `crc32`) and ARMv8
  (`crc32c` instruction), so cost is measured in cycles per cache line.

### MessageType extensibility

`MessageType` enum codes are dense (0x01..0x10) today. Adding a new
type in v1 is allowed *as long as the code is unique*; v0 readers are
non-existent (v0 never shipped to a customer), so the question is
purely v1<->v2. v2 readers MUST treat unknown type codes as "drop frame and
log unknown_message_type" - never as a hard reject - to preserve the
N-1 / N coexistence invariant.

The decode-side validation (`MessageType.fromCode` throwing) is a v1
behaviour; a v2 reader operating in mixed mode must wrap that throw
in a degraded "log + skip" handler at the dispatcher, not at the
framing layer.

### Public API surface (`FrameCodec`)

```java
public static final byte WIRE_VERSION = 0x01;
public static final int  HEADER_SIZE   = 18;
public static final int  TRAILER_SIZE  = 4;
public static int  frameSize(int payloadLength);
public static byte[] encode(MessageType type, int groupId, long term, byte[] payload);
public static void   encode(ByteBuffer buf, MessageType type, int groupId, long term, byte[] payload);
public static Frame  decode(byte[] data);
public static int    peekLength(byte[] data);

public static final class UnsupportedWireVersionException extends RuntimeException {
    public int observedVersion();
}
```

Allocation: `decode` allocates one `byte[]` (payload) and one `Frame`
record per call; the CRC computation is one short-lived `CRC32C`
instance. No allocation on the encode hot path beyond the result
`byte[]` (or zero allocation if the caller supplies a `ByteBuffer`).

## Consequences

### Positive

- **First green-field wire format with a version byte.** Every future
  change has a clean migration path.
- **Defense-in-depth integrity.** Bit-flips inside a TLS session no
  longer produce silently corrupt frames; the decoder rejects them
  with a structured error.
- **Distinct exception types** narrow operator diagnostics. A frame
  failure is unambiguously "framing", "version", "checksum", or "type
  code" - never one disguised as another.
- **Bounded allocation on decode.** `MAX_FRAME_SIZE = 16 MiB` is
  enforced at the framing layer; per-message bounds (`MAX_ENTRIES_PER_APPEND`,
  `MAX_COMMAND_LEN`, `MAX_SNAPSHOT_BLOB_LEN`) are enforced inside
  `RaftMessageCodec`. A 22-byte adversary frame cannot trigger a
  multi-GB allocation.
- **Wire-compat fixtures pin the byte layout.** 16 fixtures (one per
  `MessageType`) under `configd-transport/src/test/resources/wire-fixtures/v1/`
  are byte-asserted by `WireCompatGoldenBytesTest`. Any drift
  triggers immediate test failure.

### Negative

- **5 bytes per frame overhead** (1 version + 4 trailer) vs. v0.
  Negligible against typical payload sizes; meaningful only on
  HEARTBEAT, where it brings the frame from 17 -> 22 bytes (~30 %
  overhead). HEARTBEATs are infrequent enough that this is invisible
  in aggregate bandwidth.
- **CRC32C compute cost**, even hardware-accelerated, is non-zero.
  Measured ~0.5 ns/byte on Skylake; for a 1 KiB AppendEntries that
  is ~500 ns per encode + 500 ns per decode. Acceptable; not on the
  edge read path.
- **No backward compatibility with v0 deployed peers.** Acceptable
  because v0 was never shipped to a customer; the empty-set
  intersection.

### Forward-compat contract for v2

The v2 wire format MAY:

- Add new `MessageType` codes (must be globally unique; v1 will
  reject them - see "MessageType extensibility" above).
- Append new fields *after* the existing payload, contained inside
  the per-`MessageType` decoder's framing (not at the FrameCodec
  layer). The CRC32C trailer remains; new bytes must precede it.
- Bump `WIRE_VERSION` to `0x02` and supply a v2 fixture set under
  `wire-fixtures/v2/`.

The v2 wire format MUST NOT:

- Change the layout or semantics of the first 5 bytes
  (`[Length][Version]`); peers must always be able to size and
  identify a frame off the first 5 bytes alone, regardless of
  version.
- Re-use a `MessageType` code with different semantics. Codes are
  burned forever once shipped.
- Drop the CRC32C trailer. Peers MUST always validate it.
- Land without the section 8.10 fixture-bump CI guardrail extended to
  `wire-fixtures/v2/`.

A v2 reader that wishes to interoperate with a v1 peer MUST:

- Accept v1 frames as well as v2 frames in `decode`.
- Encode v2 only after a Hello handshake confirms the peer speaks v2
  (handshake protocol is out of scope for this ADR; would be
  ADR-0030+).
- Surface a metric `configd_transport_wire_version_observed{version}`
  so operators can see the live mix during a rolling upgrade.

### section 8.10 fixture-bump CI guardrail - scope and known gap

The `wire-compat` CI job greps the PR diff for `WIRE_VERSION` change
and fails if any byte under `wire-fixtures/v<N>/` changed without it.
This guardrail covers **only the `FrameCodec` layer**. Specifically:

- The fixtures are produced by `WireFixtureGenerator` calling
  `FrameCodec.encode(...)` with a fixed 4-byte payload
  (`{0xDE, 0xAD, 0xBE, 0xEF}`) for every non-heartbeat
  `MessageType`. They pin the FrameCodec wire shape (length, version,
  type, groupId, term, payload, CRC32C) but not the application-layer
  payload encoded by `RaftMessageCodec`.
- A change to `RaftMessageCodec.encodeAppendEntries` (or any other
  per-`MessageType` encoder) that reorders/adds/removes payload
  fields will not trip the section 8.10 fixture comparison. The defence at
  the application-layer is `RaftMessageCodecTest` round-trip plus
  `RaftMessageCodecPropertyTest` (jqwik). Round-trip passes do not
  catch wire-shape drift between v1 and v2 the way golden bytes do.

**Known gap.** A separate `RaftMessageCodecGoldenBytesTest` with
per-`MessageType` payload fixtures that pin the application-layer byte
layout would close this gap. Until it exists, RaftMessageCodec wire
changes are governed by code-review discipline, not CI. This is
documented honestly here rather than oversold.

### Snapshot size: a per-chunk cap, not a total-state ceiling

`MAX_SNAPSHOT_BLOB_LEN = 4 MiB` is the ceiling for a single
`InstallSnapshot` chunk, not for total snapshot size. Chunked snapshot
install is implemented: a snapshot larger than one chunk streams to a
lagging follower as ordered chunks using the wire format's `offset` and
`done` fields (Raft section 7). The leader sizes chunks at
`RaftNode.snapshotChunkBytes` (default 1 MiB), hard-capped at
`RaftNode.MAX_SNAPSHOT_CHUNK_BYTES` (4 MiB, mirroring
`RaftMessageCodec.MAX_SNAPSHOT_BLOB_LEN`) so a chunk can never be born
unencodable. The leader drives the transfer and resumes from the
follower's echoed offset (`InstallSnapshotResponse.nextExpectedOffset`)
after a drop, reorder, or restart; it only restarts from offset 0 after
several heartbeat intervals of total silence from that follower.

The follower reassembles chunks into a single in-memory buffer, bounded
by `configd.raft.maxReassembledSnapshotBytes` (default 512 MiB, operator
tunable). A snapshot that would exceed that bound, or a transfer that
never reaches its final chunk, is refused and the partial buffer is
dropped before it can exhaust the follower's heap - it is not bounded by
disk, only by that cap.

Related bounds:

- `RaftNode.propose` rejects client commands > 1 MiB so the **log**
  cannot grow per-entry past the codec cap.
- Operators can watch the `configd_snapshot_bytes` gauge, which reports
  the size of the last snapshot the state machine produced, and alert
  when it approaches the reassembly cap.

### Bounds - encoder is symmetric with decoder

`FrameCodec.encode(...)` enforces `MAX_FRAME_SIZE` on the write side
(throws `IllegalArgumentException` if `payload.length + HEADER_SIZE +
TRAILER_SIZE > MAX_FRAME_SIZE`). Symmetric with the decoder so a
local encoder can never produce a frame the receiver will reject -
the failure surfaces at the encoder with a clear message instead of
on the wire as a misleading "frame length out of bounds" on the
peer.

`RaftMessageCodec` enforces additional caps on the application
payload before allocating:

- `MAX_ENTRIES_PER_APPEND = 10_000` - caps the entry-count field
  that the decoder uses to size an `ArrayList`.
- `MAX_COMMAND_LEN = 1 MiB` - caps any single LogEntry command.
- `MAX_SNAPSHOT_BLOB_LEN = 4 MiB` - a per-chunk cap under chunked
  InstallSnapshot (`RaftNode.MAX_SNAPSHOT_CHUNK_BYTES`; a large snapshot
  streams as ordered chunks and the follower reassembles them under
  `configd.raft.maxReassembledSnapshotBytes`), not a total-state
  ceiling. Applies per-blob, to both `data` and `clusterConfigData`;
  sized so that two blobs at the cap plus the InstallSnapshot fixed
  header (33 B) plus the FrameCodec header+trailer (22 B) totals
  ~8 MiB, comfortably under `MAX_FRAME_SIZE`. A snapshot larger than one
  chunk is split across chunks via the `offset`/`done` fields, driven by
  the leader (see "Snapshot size" above).
- `RaftMessageCodec.checkInstallSnapshotFitsFrame(...)` further
  enforces the combined-fits-in-frame constraint at encode time so
  that a maximally-sized request is provably encodable and
  decodable.

## Verification

- `FrameCodecPropertyTest` (jqwik, 11 properties x 50-500 tries each):
  encode/decode roundtrip, length-field consistency, byte-buffer / array
  parity, truncation rejection, unknown-type rejection, unknown-version
  rejection, length-mismatch rejection, large-payload roundtrip,
  peek-length agreement, signed groupId/term preservation, minimum-frame
  size.
- `WireCompatGoldenBytesTest` (16 dynamic tests, one per `MessageType`):
  byte-equality against the checked-in `wire-fixtures/v1/*.bin`.
- `RaftMessageCodecPropertyTest` (jqwik, 12 properties): every Raft
  RPC round-trips; non-Raft `MessageType` is rejected; oversized entry
  count, command length, and snapshot blob length all rejected with
  `IllegalArgumentException` (no `BufferUnderflowException`
  amplification).
- `FrameCodecTest` (existing 8 tests): API-shape regression covered.
- Full reactor: `./mvnw test` -> 21,394 tests, 0 failures, 0 errors,
  ~66 s on JDK 25 + `--enable-preview` (2026-04-25 measurement; later
  CRC-ordering swap + encoder-bounds tests added 9 tests over the
  initial baseline of 21,385).

## Alternatives considered

- **Magic-number prefix instead of version byte.** Rejected - gives
  no upgrade path; just a hard fingerprint check.
- **MessagePack / Protobuf for the framing layer.** Rejected - the
  framing layer is too hot to pay schema-decoder overhead, and the
  v1 layout is small enough that hand-rolling is the obviously
  correct call. The *payload* may use protobuf in v2; the *frame*
  stays hand-rolled.
- **CRC32 (IEEE) instead of CRC32C.** Rejected - CRC32 is a worse
  polynomial for catching common error patterns and lacks the
  hardware acceleration.
- **HMAC-SHA256 trailer instead of CRC32C.** Rejected - TLS already
  handles cryptographic integrity; HMAC at the frame layer is
  redundant with TLS and cuts throughput by ~5x.
- **Variable-length frame header (e.g., varint length).** Rejected -
  fixed-width header is cheaper to peek and parse, and the 4 B saving
  on small frames is irrelevant.
