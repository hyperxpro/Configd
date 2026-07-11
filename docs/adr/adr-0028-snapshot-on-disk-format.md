# ADR-0028: Snapshot On-Disk / On-Wire Format

## Status
Accepted

> **Where the current bytes live.** This ADR records the *decision* for the snapshot body format
> and its three-form, skip-unknown-TLV trailer. The byte-authoritative description of that body as
> it rides the wire - the reassembled `SNAPSHOT_CHUNK` payload and its trailer detection - is now in
> [`rfc/driver-protocol/06-wire-framing.md`](../rfc/driver-protocol/06-wire-framing.md) §7 (F7-2),
> validated against the codecs. Read the RFC for the layout; read this ADR for why it is shaped that
> way. Where the two disagree, **the code (and the RFC) win**.

## Context

`ConfigStateMachine.snapshot()` / `restoreSnapshot()` produce and
consume the byte sequence transferred during Raft `InstallSnapshot`
RPCs and persisted on followers as the recovery base. Three things
motivated writing this ADR:

1. Three runbooks (`ops/runbooks/snapshot-install.md`,
   `ops/runbooks/restore-from-snapshot.md`, plus the conformance
   template) cited `adr-0009-snapshot-format.md`. That file does not
   exist - `adr-0009-...` is the JDK runtime ADR (superseded by
   ADR-0022). The snapshot format itself had no ADR.
2. A trailing `signingEpoch` long was added to keep the monotonic
   Ed25519 epoch alive across `InstallSnapshot`.
3. The `remaining() >= 8` probe originally used to detect that trailer
   is non-extensible: any second trailer field would silently corrupt
   N-1 readers.

This ADR documents the format that addresses all three.

## Decision

The snapshot byte sequence is:

```
  [8-byte sequence counter]                  (long, big-endian)
  [4-byte entry count]                       (int, big-endian)
  for each entry:
    [4-byte key length][key bytes (UTF-8)]
    [4-byte value length][value bytes]
  [optional TLV trailer block]
```

The trailer is **TLV with a magic prefix** so it can be evolved
without breaking N-1 readers:

```
  [4-byte magic   = 0xC0FD7A11]
  [4-byte length  = number of payload bytes that follow]
  [length-byte payload - currently a sequence of TLV records]
```

Each record inside the payload is:

```
  [2-byte type tag][4-byte value length][value bytes]
```

Defined tags:

| Tag    | Name           | Value                                |
|--------|----------------|--------------------------------------|
| 0x0001 | signing_epoch  | 8 bytes, big-endian long             |

Future tags MUST use values >= 0x0002. Readers MUST skip unknown tags
(forward-compatible) and MUST tolerate an absent trailer (legacy /
backward-compatible). A snapshot with a recognized magic but a
trailer length that runs past the buffer is rejected as corrupt.

### Backward compatibility

`restoreSnapshot()` accepts three byte forms:

1. **Legacy (original):** body only, no trailer bytes after the last
   entry. `signingEpoch` stays at the in-memory value.
2. **Raw 8-byte trailer:** exactly `Long.BYTES` extra bytes after the
   body, interpreted as `signingEpoch`. This was the first-cut trailer
   format; `ConfigStateMachine` reads it as `buf.remaining() >= 8 =>
   buf.getLong()`.
3. **TLV trailer (this ADR):** magic `0xC0FD7A11` + length-prefixed
   record block.

A reader chooses by inspecting the trailer prefix: if the next four
bytes equal the magic, decode TLV; if exactly 8 trailing bytes remain,
decode the legacy raw long; otherwise treat as no trailer. The
regression tests `legacyNoTrailerLoads`, `rawEpochTrailerStillLoads`,
and `tlvTrailerLoadsAndPreservesEpoch` in
`configd-config-store/src/test/java/io/configd/store/ConfigStateMachineTest.java`
cover all three forms.

### Determinism / signing contract

Entries are serialized in the order returned by `HamtMap.forEach`,
which is consistent for the same logical map contents. Keys are
length-prefixed with `int` (not `short`); a 2-byte short would silently
truncate keys longer than 65535 bytes.

Snapshot bytes are not themselves Ed25519-signed (the signature lives
on the per-delta `ConfigDelta` payload via `ConfigSigner`, see
ADR-0027 sign-or-fail-close). The trailer carries the
`signingEpoch` so a follower restored via `InstallSnapshot` resumes
strictly-monotonic epoch issuance and an offline edge cannot replay
pre-snapshot deltas under a re-issued low epoch.

### Bounds

To bound allocation under adversarial or corrupted input,
`restoreSnapshot()` rejects:

- `entryCount < 0` or `> MAX_SNAPSHOT_ENTRIES` (1e8)
- `keyLen < 0` or `> MAX_SNAPSHOT_KEY_LEN` (1 MiB)
- `valueLen < 0` or `> MAX_SNAPSHOT_VALUE_LEN` (1 MiB, matches
  `CommandCodec.MAX_VALUE_SIZE`)
- TLV trailer length running past the buffer end

A rejected snapshot increments
`configd_snapshot_install_failed_total` (via `StateMachineMetrics`).

### Chunking

A large snapshot streams to a lagging follower as ordered chunks rather
than one `InstallSnapshot` RPC payload - see
[`adr-0029-wire-format-v1.md`](adr-0029-wire-format-v1.md) and
`RaftNode.MAX_SNAPSHOT_CHUNK_BYTES`. Chunking is a transport-level
concern: this trailer format is unaffected, since it rides at the end
of the last chunk and is decoded once, after the follower reassembles
the full byte sequence.

### CRC

A trailer CRC is **deferred**. The frame carrying the snapshot
(`configd-transport/FrameCodec`) already CRC-protects the wire bytes.
A separate snapshot-level CRC would be redundant for in-flight
corruption and would not catch the at-rest corruption case (the
signing trailer + the legacy `signingEpoch` already detect torn
trailers - a corrupted magic decodes as "no trailer"). If the on-disk
snapshot store ever moves out of an integrity-checking filesystem, a
trailer CRC tag (e.g. `0x0002 = crc32c_payload`) will be added under
this ADR's evolution rule.

## Influenced by

- The need for signing-epoch durability across InstallSnapshot.
- The realization that a non-extensible 8-byte probe corrupts future
  readers once a second trailer field is added.
- Key-length and envelope bound checks against adversarial input.
- Etcd's snapshot v3 format - TLV-with-magic is the standard
  forward-compat pattern.

## Reasoning

The TLV-with-magic envelope is the smallest change that lets us add
fields (CRC, chunk index, format version, signing-key fingerprint)
without ever breaking N-1 readers. The two prior-art forms (legacy /
raw-epoch) remain decodable because operators already have snapshots
on disk in those forms; rejecting them would force an offline migration.

## Rejected alternatives

- **Bump a leading format-version byte.** Forces every reader to
  branch up-front; once chunking enters the picture, the version-byte
  approach conflates wire and content versioning. TLV is more local.
- **Sign the entire snapshot bytes with Ed25519.** Doubles the
  signing-key surface (state-machine snapshot vs per-delta) without
  adding integrity over the per-delta path. Deferred until an at-rest
  threat model demands it.
- **Move to Protobuf / FlatBuffers.** Pulls in a build-time codegen
  dependency, conflicts with the "zero external deps" choice
  documented in the inventory.

## Consequences

- **Positive:** Format is forward-compatible. New fields (CRC,
  chunking metadata, signing-key fingerprint) can be added without a
  wire-version bump.
- **Negative:** Two trailer forms must be supported in `restoreSnapshot`
  for at least one major release after this ADR; the legacy raw-epoch
  branch can be removed only after a full deprecation cycle.
- **Risks and mitigations:** A bug in trailer parsing could silently
  drop `signingEpoch` and reopen the window where a stale edge replays
  pre-snapshot deltas under a re-issued low epoch. Mitigated by the
  three regression tests above and by
  `configd_snapshot_install_failed_total` emission on any structural
  reject.

## Verification

- **Testable via:** `configd-config-store/src/test/java/io/configd/store/ConfigStateMachineTest.java`
  - specifically `legacyNoTrailerLoads`, `rawEpochTrailerStillLoads`,
  `tlvTrailerLoadsAndPreservesEpoch`. The
  `configd-consensus-core/src/test/java/io/configd/raft/SnapshotInstallSpecReplayerTest.java`
  cross-checks against the TLA+ `SnapshotInstallSpec` traces.
- **Invalidated by:** any new field landing in the snapshot body
  without a matching TLV record, or any reader that probes
  `buf.remaining() >= N` for a hard-coded N instead of checking the
  magic prefix.
- **Operator check:** after an `InstallSnapshot` round-trip, confirm
  the new leader's first signed delta carries an epoch strictly
  greater than the highest pre-snapshot epoch the edge has stored.
  See `ops/runbooks/snapshot-install.md`.
