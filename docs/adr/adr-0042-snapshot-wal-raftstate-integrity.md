# ADR-0042 - At-rest integrity for snapshot, WAL, and Raft durable state

- **Status:** Accepted; crypto construction flagged for specialist review
- **Date:** 2026-06-14
- **Problem:** at-rest integrity was absent from snapshot, WAL, and Raft durable state.
- **Supersedes/extends:** the unversioned formats in `DurableRaftState`, `RaftLog.serializeSnapshot`,
  and the WAL entry payload. Composes with the durable-prefix work and the torn-write durability work.
- **Extended since:** the envelope's `algId` slot now also carries **`algId=2` node-local
  AES-256-GCM at-rest encryption** (opt-in, `-Dconfigd.raft.encryption.enabled=true`) - the GCM tag replaces
  the HMAC, the CRC32C corruption layer stays. Roots are custodied by a persisted dual-slot **keyring**
  (`NodeKeyring`, non-destructive rotation) with a pluggable KMS SPI (`local` HKDF-from-signing-key, or an
  external Vault Transit provider). This ADR's integrity construction is the seam; the encryption posture and
  keyring/upgrade contract are recorded in `docs/design/frozen-format-v1-2026-07-03.md` and
  `docs/design/group-b/07-upgrade-capability-as-built.md`.

## Problem

The on-disk Raft durability artifacts have **no integrity protection**:
- `DurableRaftState` (`raft.persistent_state`): 12 bytes `[term:8][votedFor:4]` - no magic/version/CRC.
  A flipped `votedFor` to a valid node id is undetectable and violates Election Safety.
- The snapshot blob (`raft-log.snapshot`): `[index][term][dataLen][data][cfgLen][cfg]` - **no
  CRC, no version, no authentication**. A tampered byte is silently restored as *authoritative
  committed state* - the highest-value injection in the system (bypasses transport + consensus).
- The WAL records (`raft-log`) already carry a per-record CRC32 via `FileStorage` with torn-tail
  tolerance - so corruption is already handled - but CRC32 is **not tamper-resistant** (an
  attacker who can write the file recomputes it), and the entry payload has no magic/version.

**Threat model:** an adversary with write access to snapshot/WAL/backup storage (a backup
bucket, shared NFS, the `restore-snapshot.sh` operator path) but **without** the node's protected
key material - the "storage/backup writer" adversary class in the threat model, who can write to
that storage but holds none of a node's key material. Transport mTLS does not protect data at
rest (defense in depth). Full host compromise (an adversary holding the node's key material
directly) is explicitly out of scope.

## Decision

Two layers, applied as a pure encode/decode transform over each artifact's existing payload.

### Layer A - versioned format + CRC32C (keyless: corruption / downgrade / format-evolution)

A self-describing envelope, mirroring the `FrameCodec` (ADR-0029) and `SigningKeyStore` precedents:

```
[ MAGIC: 4 ][ formatVersion: 2 ][ algId: 1 ][ reserved: 1 ][ payload ][ MAC: 0|32 ][ CRC32C: 4 ]
  +- artifact-specific magic        +- 0=NONE, 1=HMAC_SHA256          +- present iff algId!=NONE
```
- `MAGIC` distinct per artifact (`RAFT_STATE`, `RAFT_SNAP`, `RAFT_WALE`) to defeat cross-artifact
  confusion. CRC32C (not CRC32) matches the codebase convention (`java.util.zip.CRC32C`, `FrameCodec`).
- CRC32C covers `MAGIC..payload..MAC` - detects corruption, truncation, format-confusion. **Keyless,
  so it is *not* the security control** - it is corruption/forward-compat hardening. A CRC alone does
  not close the at-rest integrity gap.

### Layer B - HMAC-SHA-256 (keyed: tamper / forgery - the security control)

- `MAC = HMAC-SHA-256(K_integrity, MAGIC || formatVersion || algId || reserved || payload)`. Every
  header field is **inside** the MAC input -> an attacker cannot downgrade `algId` to NONE, roll
  `formatVersion` back, or mutate `reserved` without invalidating the MAC. (The `reserved` byte was
  folded into the MAC input during review, to remove its latent malleability before the construction
  reaches specialist crypto review.)
- **`K_integrity = HKDF-SHA256(IKM = cluster Ed25519 private-key encoding, info = "configd/raft-at-rest-integrity/v2", salt = keyId)`** - derived from the **existing cluster-shared signing key**
  (`SigningKeyStore`), so **no new key file and no new key-distribution channel** is introduced
  (no new attack surface). The verify side uses the same derivation.
- Verify on every load / restore / install-snapshot. Compare in **constant time**
  (`MessageDigest.isEqual`).

### Downgrade posture (sticky fail-closed)

- A node with an integrity key configured runs **fail-closed**: it refuses an artifact whose
  `algId=NONE` (or whose MAC is absent) - the MAC covering `algId` is necessary but not sufficient;
  posture, not bytes, defeats strip-the-MAC. A node with **no** integrity key configured still gets
  Layer A (version+CRC32C) and reads either form (opt-in authentication, pre-production only).
- **Production default must flip to fail-closed** - tracked as a follow-up item, not closed here.

### Torn-vs-tamper rule (the critical disambiguation)

- **WAL:** authentication lives **inside** the `FileStorage` per-record frame, *after* the existing
  torn-tail/length/CRC32 checks. A partial trailing record from a mid-append crash is dropped as
  **torn** by the existing logic *before* the MAC is ever checked. A record that is
  complete + CRC32-valid + **MAC-invalid** is **tamper -> fail loud** (throw), never dropped.
- **Atomic-rename artifacts** (snapshot blob, `raft.persistent_state`): never torn (temp+fsync+
  atomic-rename), so a MAC mismatch on a structurally-complete artifact **always fails loud**.
- **`readSnapshotBlob` must throw on a MAC mismatch of a structurally-complete blob - it must not
  return `null` (the current torn/short -> WAL-fallback behavior)**, or a silent-downgrade
  vulnerability is reintroduced. Structurally-short/absent stays `null` (legit torn / first boot).

### Composition with the durable-prefix work

- Applied as a pure transform inside `serialize*`/`read*` - the persist-before-truncate ordering
  in `compact()` and the "ahead-of-WAL blob is ignored" recovery rule are unchanged. The durability
  crash-recovery test suite (`SnapshotCrashRecoveryTest`, `WalSyncCrashTest`,
  `VotePersistenceCrashTest`, `AdversarialCrashRecoveryTest`, `StorageEnospcConsensusReactionTest`,
  `RaftLogUnitTest`) must re-run **green** after the change.

## Negative tests

Each attack succeeds pre-fix (captured), fails post-fix, and is independently reproduced:
1. Tampered snapshot byte -> `restore/recovery refused` (throws, does not load).
2. Tampered WAL record (complete) -> recovery refused; **torn tail still tolerated** (separate cell).
3. Forged/rolled-back `formatVersion` or `algId=NONE` under a configured key -> refused.
4. Forged `DurableRaftState` (valid-looking `votedFor`, recomputed CRC, **no valid MAC**) -> refused.
5. Forged install-snapshot from a peer -> refused on the same path as local restore.

## Consequences / residual risk (crypto review follow-up)

Flagged for **specialist cryptographic review** (we verify behavior, do not self-certify primitive):
1. Using the Ed25519 private-key encoding as HKDF IKM - soundness of that IKM choice.
2. MAC message canonicalization (fixed-width prefix; no length-extension concern with HMAC).
3. Downgrade-policy completeness; constant-time comparison.
4. **Suffix truncation:** a per-record WAL MAC authenticates each record but not the *set* - an
   attacker who truncates committed records from the tail produces a shorter-but-valid log. Mitigated
   operationally by the snapshot boundary + commit index; a whole-log anchor is flagged as a
   follow-up.
5. CRC32C vs CRC32 rationale.

**Key-location requirement:** `K_integrity`'s value depends on the cluster signing key
living **outside** attacker-writable storage. Production Compose already mounts it at
`/secrets/signing-key.bin:ro` (`deploy/compose/setup-secrets.sh`). The **insecure default**
(`dataDir.resolve("signing-key.bin")`, `ConfigdServer.java:197-199`) co-locates it - the server
emits a loud startup warning when the key resolves inside the data dir, and relocating the
default was tracked as a follow-up item. **ADR-0044 supersedes this warn-only behavior**: the
server now refuses to start on a co-located key by default rather than warning and continuing.
