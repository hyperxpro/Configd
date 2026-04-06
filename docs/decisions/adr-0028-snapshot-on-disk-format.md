# ADR-0028: Snapshot On-Disk / On-Wire Format

## Status
Accepted (closes DOC-028 / iter-1 D-004 + iter-2 R-002)

## Context

`ConfigStateMachine.snapshot()` / `restoreSnapshot()` produce and
consume the byte sequence transferred during Raft `InstallSnapshot`
RPCs and persisted on followers as the recovery base. Three open
issues motivated authoring a dedicated ADR:

1. **DOC-028:** three runbooks (`ops/runbooks/snapshot-install.md`,
   `ops/runbooks/restore-from-snapshot.md`, plus the conformance
   template) cite `docs/decisions/adr-0009-snapshot-format.md`. That
   file does not exist — `adr-0009-...` is the JDK runtime ADR
   (superseded by ADR-0022). The snapshot format had no ADR.
2. **iter-1 D-004 closure** added a trailing `signingEpoch` long to
   keep the monotonic Ed25519 epoch alive across `InstallSnapshot`.
3. **iter-2 R-002** observed that the `remaining() >= 8` probe used
   to detect the trailer is non-extensible: any second trailer field
   silently corrupts N-1 readers.

This ADR documents the format that closes (1)–(3).

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
  [length-byte payload — currently a sequence of TLV records]
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

1. **Legacy (pre-D-004):** body only, no trailer bytes after the last
   entry. `signingEpoch` stays at the in-memory value.
2. **iter-1 raw 8-byte trailer:** exactly `Long.BYTES` extra bytes
   after the body, interpreted as `signingEpoch`. This is the
   first-cut shipped by D-004; `ConfigStateMachine` reads it as
   `buf.remaining() >= 8 ⇒ buf.getLong()`.
3. **TLV trailer (this ADR):** magic `0xC0FD7A11` + length-prefixed
   record block.

A reader chooses by inspecting the trailer prefix: if the next four
bytes equal the magic, decode TLV; if exactly 8 trailing bytes remain,
decode the legacy raw long; otherwise treat as no trailer. F1 (R-002)
adds the regression tests `legacyNoTrailerLoads`,
`rawEpochTrailerStillLoads`, and `tlvTrailerLoadsAndPreservesEpoch` in
`configd-config-store/src/test/java/io/configd/store/ConfigStateMachineTest.java`.

### Determinism / signing contract

Entries are serialized in the order returned by `HamtMap.forEach`,
which is consistent for the same logical map contents. Keys are
length-prefixed with `int` (not `short`) — this is the F-0013 fix; the
2-byte short silently truncated keys > 65535 bytes.

Snapshot bytes are not themselves Ed25519-signed (the signature lives
on the per-delta `ConfigDelta` payload via `ConfigSigner`, see
ADR-0027 sign-or-fail-close). The trailer carries the
`signingEpoch` so a follower restored via `InstallSnapshot` resumes
strictly-monotonic epoch issuance and an offline edge cannot replay
pre-snapshot deltas under a re-issued low epoch.

### Bounds

To bound allocation under adversarial / corrupted input
(F-0053 fix), `restoreSnapshot()` rejects:

- `entryCount < 0` or `> MAX_SNAPSHOT_ENTRIES` (1e8)
- `keyLen < 0` or `> MAX_SNAPSHOT_KEY_LEN` (1 MiB)
- `valueLen < 0` or `> MAX_SNAPSHOT_VALUE_LEN` (1 MiB, matches
  `CommandCodec.MAX_VALUE_SIZE`)
- TLV trailer length running past the buffer end

A rejected snapshot increments
`configd_snapshot_install_failed_total` (via `StateMachineMetrics`).

### Chunking

The current implementation transfers the entire byte sequence in a
single `InstallSnapshot` RPC payload. Chunking (per the Raft paper
§7) is **deferred**: the bound above (1 MiB per value × 1e8 entries)
is theoretical; observed snapshots fit in the gRPC-equivalent frame.
When chunking is added, it will be a transport-level concern carrying
this same trailer at the end of the last chunk.

### CRC

A trailer CRC is **deferred**. The frame carrying the snapshot
(`configd-transport/FrameCodec`) already CRC-protects the wire bytes.
A separate snapshot-level CRC would be redundant for in-flight
corruption and would not catch the at-rest corruption case (the
signing trailer + the legacy `signingEpoch` already detect torn
trailers — a corrupted magic decodes as "no trailer"). If the on-disk
snapshot store ever moves out of an integrity-checking filesystem, a
trailer CRC tag (e.g. `0x0002 = crc32c_payload`) will be added under
this ADR's evolution rule.

## Influenced by

- iter-1 D-004 closure (signing-epoch durability across InstallSnapshot).
- iter-2 R-002 (non-extensible 8-byte probe corrupts future readers).
- iter-1 F-0013 / F-0053 (key length + envelope bound checks).
- Etcd's snapshot v3 format — TLV-with-magic is the standard
  forward-compat pattern.

## Reasoning

The TLV-with-magic envelope is the smallest change that lets us add
fields (CRC, chunk index, format version, signing-key fingerprint)
without ever breaking N-1 readers. The two prior-art forms (legacy /
raw-epoch) remain decodable because operators already have snapshots
on disk in those forms; rejecting them would force an offline migration.

## Rejected alternatives

- **Bump a leading format-version byte.** Forces every reader to
  branch up-front; once we add chunking the version-byte approach
  conflates wire and content versioning. TLV is more local.
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
  branch can be removed only after a §8.10 deprecation cycle.
- **Risks and mitigations:** A bug in trailer parsing could silently
  drop `signingEpoch` and reopen the D-004 fanout-skip window. Mitigated
  by the three regression tests above and by `configd_snapshot_install_failed_total`
  emission on any structural reject.

## Reviewers

- principal-distributed-systems-architect: ✅
- distributed-systems-researcher: ✅
- security-red-team: ✅ (TLV permits future signing-key-fingerprint
  field without breaking N-1)

## Verification

- **Testable via:** `configd-config-store/src/test/java/io/configd/store/ConfigStateMachineTest.java`
  — specifically `legacyNoTrailerLoads`, `rawEpochTrailerStillLoads`,
  `tlvTrailerLoadsAndPreservesEpoch`. The
  `configd-consensus-core/src/test/java/io/configd/raft/SnapshotInstallSpecReplayerTest.java`
  cross-checks against the TLA+ `SnapshotInstallSpec` traces.
- **Invalidated by:** any new field landing in the snapshot body
  without a matching TLV record, or any reader that probes
  `buf.remaining() >= N` for a hard-coded N (the iter-1 R-002 anti-pattern).
- **Operator check:** after an `InstallSnapshot` round-trip, confirm
  the new leader's first signed delta carries an epoch strictly
  greater than the highest pre-snapshot epoch the edge has stored.
  See `ops/runbooks/snapshot-install.md`.
