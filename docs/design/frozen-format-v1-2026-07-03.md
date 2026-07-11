# Configd Frozen-Format v1: Permanent Format Design and Adversarial Review

This is the permanent design for every format Configd persists or speaks, on disk and on the wire.
It closes the two structural gaps that mattered most (no truncation/rollback anchor; key rotation
that destroyed data) and four format-versioning gaps (policy grammar, cursor topology epoch,
wrapped-key format, command/chunk self-versioning) as one coherent design, with completeness
proofs.

These formats are designed correct-from-scratch, with no migration/compat baggage: legacy
acceptance paths in the code that predate this design are deleted (§2.9, §3). Once a release is
tagged, they never change again - the only future doors are the version markers themselves.

**Document structure.** Part I (§0-§3) is the normative spec: the threat model, the frozen byte
layouts, and the decisions behind them. Part II (§4-§6) is the underlying design reasoning and
proofs by area (the anchor and key rotation; the version-marker scheme; fsync ordering); where it
disagrees with Part I, Part I governs. Part III (§7-§11) is a compile-checked prototype that
validated the byte layouts before the real build, a prior-art survey, a map from this design to
the production code, and the adversarial review that preceded release.

## What shipped, and where it differs from the design below

The build landed in stages: version markers on every persistent and wire format (fail closed on
unknown); a per-record scope, position, and SHA-256 hash chain in the WAL (catches reorder,
splice, and interior rollback - see §2.8, added after a review found that contiguity and
term-monotonicity alone miss an index-preserving, term-monotonic interior rollback); a
`TopologyDescriptor` (replacing the old plaintext shard-count file) with a cursor/SUBSCRIBE
topology epoch and `STALE_TOPOLOGY`; the durability kernel (`raft-anchor`, the persistent-state
merge, persist-before-ack, and the recovery gates in §2.17); the node-level `node-anchor` (topology
cross-check, audit head, and a `shardAnchorDigest` that closes the single-shard-wipe residual,
R-f); the peer-quorum `AnchorWitness` closing R-a' (see `docs/design/anchor-witness-peer-quorum-
2026-07-04.md`); and the persisted dual-slot `raft-keyring` with keyTerm-versioned at-rest
integrity in both the HMAC and encrypting postures, plus non-destructive rotation (append-and-
retain term rotation, rewrap-before-swap signing-key rotation, both crash-atomic; boot loads all
retained terms rather than a hardcoded term=1).

Two composition tests exercise this end to end: `EncryptedMultiShardClusterCompositionTest` (a
real 3-node cluster with encryption on, multiple shards, the witness armed, live watches, and a
follower restart recovering through the encrypted anchors/keyring) and
`Over4MiBEncryptedSnapshotRoundTripTest` (a snapshot over 4 MiB across both the multi-chunk wire
transfer and the whole-blob GCM-at-rest path, including tamper-refusal and keyTerm rotation).

A few places where the shipped system differs from, or narrows, the design below:

- **The AnchorWitness splits its strict mode into a boot gate and a vote gate.** The boot gate is
 unconditionally peer-majority (the default) and closes R-a' at N>=3 on its own, at the cost of a
 node that reboots into a partition staying unable to vote until the partition heals.
 Vote-deferral (withholding a granted vote until a peer-majority acks it) is an explicit opt-in
 (`-Dconfigd.raft.witnessStrict=true`), off by default, because an always-defer default broke
 single-fault leader failover in testing. The witness is armed only where real peers exist (a
 configured multi-node cluster; `tcpTransport != null`); a single-node deployment leaves it
 inert, since it cannot split-brain. Full mechanism in
 `docs/design/anchor-witness-peer-quorum-2026-07-04.md`.
- **Byte-identity has one narrow exception.** The encryption-off posture is byte-identical to
 before this design, except that an auth-on-but-not-encrypting node now writes the
 term-versioned HMAC envelope (keyTerm lives in the algId=1 body too, not only algId=2) rather
 than the old fixed-key one. The fully keyless (no signing key) posture stays byte-for-byte
 identical.
- **Two residuals surfaced during the build, beyond the ones designed in.** The security-audit
 HMAC chain is not term-versioned (`K_audit` derives directly from the signing key, §2.10): a
 signing-key rotation leaves pre-rotation audit records readable but no longer
 tamper-verifiable across the rotation boundary. And no live/admin rotation trigger ships: the
 term- and signing-key-rotation mechanisms are built, crash-atomic, and tested, but nothing
 calls them yet, so a rotation is an out-of-band maintenance operation on a stopped node.
 Neither is a data-loss defect; both are documented below rather than silently accepted.

The residual list in §0 and the completeness matrix in §4.3 carry the full detail, including the
two closed calls that mattered most at freeze time: R-a' (a within-term `votedFor` rollback is an
Election-Safety hazard, not mere staleness - closed at N>=3 by the AnchorWitness, with a residual
at N>=5 under fast-vote that needs sustained multi-peer announce loss to exploit, and is closed
absolutely by opting into strict vote) and R-f (a single-shard wipe-to-FRESH would otherwise be
silent data loss - closed by the node-anchor's `shardAnchorDigest`).

---

## §0 Executive summary

**The threat model** (normative; §1): the design detects tamper, truncation, rollback, reorder,
splice, and cross-artifact/shard/node replay by an adversary with **filesystem write access but no
key material**. Rollback of the anchor itself to a prior legitimately-authenticated state, and an
adversary holding the key, are explicitly out of scope (the dm-verity/AVB/Vault boundary) - with
one exception: an anchor rollback that crosses a term/vote boundary the WAL witnesses IS detected
(§2.17 step 2.5).

**The eight load-bearing decisions:**

1. **A durable authenticated anchor, dual-slot, per shard** (`raft-anchor`) carrying
 `{anchorSeq, currentTerm, votedFor, lastDurableIndex, lastDurableTerm, snapshotIndex,
 snapshotTerm}`. `raft.persistent_state.dat` and the bare `raft-log.snapshot-meta.dat` are
 removed, merged into it. Truncating or rolling back any committed-and-acked data now trips a
 fail-closed REFUSE at recovery.
2. **Anchor-before-ack**: the anchor fsync joins the existing fsync-before-ack barrier (leader
 self-vote and follower AppendEntries ACK both wait for it). One extra ordered fdatasync per
 group-commit batch; the estimated knee-throughput cost is 10-40% (likely 10-20%), confirmed by
 measurement after the build. A Postgres-style lagging anchor was rejected: a lag window would
 silently lose acked data under this threat model.
3. **IntegrityEnvelope FORMAT_VERSION 2->3**: adds an authenticated **`scopeId`** (shard binding,
 closing a verified cross-shard splice hole) and **`keyTerm` in the HMAC posture**
 (term-versioned integrity keys). CRC-before-version parse order; `reserved != 0` is now
 refused.
4. **A persisted, versioned keyring** (`raft-keyring`, the Vault model; doubles as the wrapped-key
 format): per-term **independent random roots**, wrapped under a KEK derived from the signing
 key; rotation appends a term and retains every old term forever; signing-key rotation
 rewraps-before-swap (crash-atomic, dual-slot). The previously data-destroying rotation becomes
 impossible by construction; boot no longer hardcodes term=1.
5. **New recovery-time checks** that make per-record authentication meaningful: WAL contiguity,
 term monotonicity, snapshot-join, the term-witness gate, and a mandatory reader `scopeId`
 assert. Before this design, recovery checked none of these, so reorder/splice was silent.
6. **A node-level anchor** (`node-anchor`) binding the topology descriptor (epoch + N) and the
 audit-log chain head. Topology rollback and audit-log truncation (outside a bounded tail
 window) become detected.
7. **Every artifact versioned**: one convention (self-versioned `[magic][version]`,
 carrier-versioned+assert, or documented-export), version-0-illegal, MBZ-enforced, unknown fails
 loud and closed. A topology descriptor replaces the plaintext shard-count file; the policy
 grammar gets a mandatory `#!configd-acl v1` pragma; watch cursors and SUBSCRIBE resumes bind a
 topology epoch (`STALE_TOPOLOGY` triggers a client re-hydrate); CRC is unified to CRC32C
 system-wide.
8. **fsync-failure policy is frozen**: a WAL- or anchor-fsync throw or lie means no durable
 advance, no ack, and process exit. This closes what was previously an open gap around fsync
 failure handling.

**Cost summary** (detail in §6): leader flush and follower append go from 1 to 2 fsyncs (the second
is a 512-byte in-place fdatasync of a preallocated slot, amortized per batch); term/vote writes get
cheaper (3 fsyncs down to 1); compaction is 2 fsyncs cheaper; conflict truncation is 2 fsyncs more
expensive (a rare path). Anchor ENOSPC is impossible after boot (preallocation, ext4/xfs).

**Residuals (documented, not overclaimed):**
- **R-a (freshness)**: anchor/keyring/whole-datadir rollback to a prior valid state, within a
 term - stale reads or lost recent writes. Detected only when the rollback crosses a
 WAL-witnessed term boundary (step 2.5, matrix row 17d); otherwise requires the optional external
 `AnchorWitness` (interface specified, not built).
- **R-a' (Election Safety)**: a within-term rollback of the merged anchor's `votedFor` (replaying
 an older slot image that recorded no vote, or a different vote, at the same term) is not caught
 by the term-witness gate (term unchanged; votes are not WAL-witnessed) and can cause a node to
 vote twice in one term, producing two leaders and divergent committed entries. It sits inside
 R-a's locally-undetectable class, but its worst case is cluster divergence, not staleness, which
 is why it is called out separately. **Closed at N>=3** by the peer-quorum `AnchorWitness`: the
 strict boot gate (always on) closes the boot-reply race; strict vote (opt-in) closes the
 grant-to-witnessed window absolutely. The residual under the default fast-vote mode at N>=5
 needs sustained multi-peer announce packet loss to exploit. See
 `docs/design/anchor-witness-peer-quorum-2026-07-04.md`.
- **R-b**: an adversary with the signing key. **R-c**: `VerifyKeyExporter` DER export unanchored.
 **R-d**: state fields cleartext in the HMAC-only posture. **R-e**: audit-tail truncation inside
 the last <=64-record/<=1-second un-anchored window.
- **R-f (data-loss via wipe-to-FRESH)**: deleting `raft-anchor`, truncating `raft-log.wal` to 0,
 and deleting the snapshot blob for a single shard would otherwise launder the "absent +
 non-empty -> REFUSE" case into "absent + empty -> FRESH" (a silent empty bootstrap). A
 multi-replica cluster self-heals via re-sync (equivalent to a disk replacement); a single
 replica, a degraded quorum, or N=1 would be permanent silent loss. **Closed**: the node-anchor's
 `shardAnchorDigest` binds per-shard `(gid, lastDurableIndex)`, so a wiped shard boots FRESH and
 the boot cross-check REFUSEs. R-f is now detected (matrix row 15b), reduced to R-a (a
 node-anchor rollback would still need the external witness to catch).

---

## §1 Threat model (normative)

> The at-rest anchor and keyring detect **tamper, truncation, rollback, reorder, splice, and
> cross-artifact/shard/node replay** performed by an adversary who has **filesystem write access to
> the data directory but does NOT hold the key material** (the cluster signing key, which is the
> root of every derived key and lives OUTSIDE the data directory - `ConfigdServer.java:1366-1397`).
> Two things are **explicitly out of scope**, exactly where dm-verity, dm-integrity, Android
> Verified Boot, and Vault draw the same line (§8, prior-art §1c): (a) **rollback of the anchor
> itself** to a prior *legitimately-authenticated* state - undetectable without external monotonic
> storage (TPM/RPMB NV counter) or a remote witness, because a valid older state is byte-for-byte a
> valid state - **except** where the rollback crosses a term/vote boundary the WAL witnesses, which
> the step-2.5 term-witness gate DOES detect; (b) an adversary holding **both** the disk **and** the
> key, who can forge anything. Crash-consistency (torn tails, partial writes, bit-rot) is a
> *non-adversarial* fault handled by CRC, dual-slot, and fsync ordering, and must not be conflated
> with the adversarial case - a design that bricks a node on a legal crash or a legal Raft
> transition fails just as surely as one that misses an attack.

Optional hardening for (a): the `AnchorWitness` SPI (§4 A1.7) - external monotonic storage of
anchor sequence numbers. The interface is frozen; the peer-quorum implementation is described in
`docs/design/anchor-witness-peer-quorum-2026-07-04.md`, and an external-monotonic-store
implementation remains a documented, unbuilt extension point.

The auth-off (keyless) posture carries **no adversarial guarantees**: envelopes are CRC-only.
Every guarantee in this document assumes the keyed-HMAC or encrypting posture.

---

## §2 The frozen formats (normative byte layouts)

All integers big-endian (`ByteBuffer` default). One CRC family system-wide: **CRC32C
(Castagnoli)**; a container CRC is corruption-only - authentication is always an
IntegrityEnvelope MAC/GCM-tag.

### 2.1 Magic & version registry

| Magic | Value | ASCII | Artifact | Scope |
|---|---|---|---|---|
| `WALE_MAGIC` | `0x5257414C` | RWAL | WAL entry envelope | per-shard, `scopeId=gid` |
| `SNAP_MAGIC` | `0x52534E50` | RSNP | snapshot blob envelope | per-shard, `scopeId=gid` |
| `STATE_MAGIC` | `0x52465354` | RFST | **RETIRED** (state merged into anchor); value reserved forever, never reused | - |
| `ANCHOR_MAGIC` | `0x52414E43` | RANC | per-shard anchor (container header + slot envelopes) | per-shard, `scopeId=gid` |
| `NODE_ANCHOR_MAGIC` | `0x524E414E` | RNAN | node anchor (container header + slot envelopes) | node, `scopeId=NODE_SCOPE` |
| `KEYRING_MAGIC` | `0x524B5952` | RKYR | keyring (container header + slot envelopes) | node, `scopeId=NODE_SCOPE`, envelope `keyTerm=0` |
| `TOPO_MAGIC` | `0x52544F50` | RTOP | topology descriptor envelope | node, `scopeId=NODE_SCOPE` |
| `AUDIT_MAGIC` | `0x52415544` | RAUD | audit record header (chain-bound) | node |
| `WAL_FILE_MAGIC` | `0x52574C46` | RWLF | WAL container file header (`raft-log.wal`, `raft-log.tmp.wal`, `security-audit.wal`) | container |
| `SNAPSHOT_TRAILER_MAGIC` | `0xC0FD7A11` | - | state-machine snapshot TLV trailer (existing, kept) | - |
| SigningKeyStore magic | `0xC0DF51C5` | - | `signing-key.bin` (existing, kept; version=1) | node |

Version constants: `IntegrityEnvelope.FORMAT_VERSION = 3` (u16); container `fileVersion = 1` (u8);
`keyringFormatVersion = 1` (u16, inner); TopologyDescriptor inner `formatVersion = 1` (u16);
audit `recordVersion = 1` (u8); edge wire versions `0x01/0x02/0x03`; raft wire `0x02`;
`NODE_SCOPE = 0xFFFFFFFF`. Reserved-value discipline (all formats): magic 0 illegal; version 0
illegal; version MAX (`0xFF`/`0xFFFF`) reserved as the future escape, unallocated in v1; MBZ
reserved fields are 0 on write and **checked** => non-zero fails closed. Unknown magic / higher
version / version 0 / legacy un-versioned form => fail loud, fail closed, never best-effort parse.

### 2.2 IntegrityEnvelope v3 (the shared authentication carrier)

```
Header (8 B, all algIds):   [magic:4][formatVersion:2 = 3][algId:1][reserved:1 MBZ, checked]

algId=0 NONE (keyless):     header || [scopeId:4] || [payload:N] || [CRC32C:4]
algId=1 HMAC_SHA256:        header || [scopeId:4] || [keyTerm:4] || [payload:N] || [MAC:32] || [CRC32C:4]
                              MAC = HMAC-SHA256(K, magic||fmtVer||algId||rsv||scopeId||keyTerm||payload)
algId=2 AES256_GCM:         header || [scopeId:4] || [keyTerm:4] || [segmentId:16] || [nonce:12]
                              || [ciphertext || tag:16] || [CRC32C:4]
                              AAD = the 44-byte prefix magic..nonce   (v2 was 40; scopeId inserted)
                              DEK = HKDF-SHA256(root[keyTerm], salt=segmentId,
                                                info="configd/raft-at-rest-encryption/dek/v1", 32)
```

- **Parse order (all postures): CRC32C first** (version-independent, over `[0, len-4)`), then
  magic/version/algId from CRC-validated bytes, then MAC/GCM. A bit-flip reports corruption, not a
  misleading version error. `formatVersion != 3` => throw; unknown `algId` => throw; `algId=0` under
  a key => downgrade-refused throw; `reserved != 0` => throw. Sub-floor buffer (< header+CRC) =>
  `null` = structurally absent (first boot / torn tail) - the only non-throw miss.
- **Key selection.** algId=1: `K = K_integrity[keyTerm] = HKDF(root[keyTerm], salt=nodeKeyId,
  info="configd/raft-at-rest-integrity/v3", 32)` - term-versioned in BOTH postures (the
  precondition for non-destructive rotation). algId=2: DEK as above. `keyTerm >= 1` from the
  keyring, with ONE exception:
- **The `keyTerm = 0` signing-key domain (KEYRING_MAGIC only).** The keyring file's own outer
  envelope is MAC'd under `K_keyringMac = HKDF(signingKey, info="configd/keyring-mac/v1")`
  (chicken-and-egg: it cannot reference a term it defines). For `KEYRING_MAGIC` the envelope
  `keyTerm` MUST be 0 and the posture MUST be algId=1 (HMAC) regardless of node posture; `keyTerm=0`
  under any OTHER magic => fail closed.
- **`scopeId` (normative reader rule).** Per-shard artifacts stamp `scopeId = gid`, and **`gid` is
  frozen to the range `[0, NODE_SCOPE)`** - `gid = 0xFFFFFFFF` is illegal, so a per-shard reader can
  never be fooled by a node-level artifact colliding on scope (unreachable at sane N, but the
  sentinel must be excluded now - it cannot be constrained post-freeze). Node-level artifacts stamp
  `NODE_SCOPE`. **Every read path MUST assert `scopeId == expected` and refuse a mismatch** - this
  is the sole cross-shard-splice defense, so the at-rest read call-sites it MUST cover are
  enumerated normatively in §9.1: WAL replay, anchor/keyring/node-anchor/topology open, snapshot
  persist->reload, and the InstallSnapshot re-persist->reload path. (Wire InstallSnapshot and edge
  hydration are frame-authenticated and re-wrapped under the receiver's own `gid` before any at-rest
  read, so they are covered on the local read.) Mechanism precision: a record copied verbatim from another shard still
  authenticates as bytes (keys are node-wide) - the *assert* is what detects it (the record's
  authenticated scopeId announces its true shard), and the MAC/tag is what makes the scopeId
  *unforgeable in place*. Both together close the cross-shard splice; the assert is not optional.
- Per-magic GCM write segments as today (`SegmentKeyManager`); `REKEY_LIMIT = 2^32` per segment;
  nonce = 4 zero bytes || u64 counter; anchors/keyring draw from their own magic's segment.
- **Nonce-domain invariant (freeze, promoted from `ConfigdServer:1328-1335`):** GCM write segments
  are keyed by MAGIC and are node-global; `scopeId` is AAD-only and MUST NOT key the segment or
  split the nonce counter per shard. The freeze *adds* a per-shard id, which invites exactly the
  per-shard-counter split the code comment warns breaks GCM (`(key,nonce)` reuse). Segment identity
  stays per-magic; nonce uniqueness rides the fresh-random 128-bit `segmentId` + the single per-magic
  counter. A refactor MUST NOT couple the segment to `gid`.

### 2.3 Container file header (8 B, unauthenticated, corruption/foreign-file guard)

```
[containerMagic:4][fileVersion:u8 = 1][flags:u8 = 0 MBZ][reserved:u16 = 0 MBZ]
```

Written once at file creation, fsync'd then; validated on open BEFORE any slot/frame read (bad
magic / unknown `fileVersion` / non-zero MBZ => REFUSE). **Freeze invariant:** this header is
unauthenticated by design (it must be key-lessly readable), so **neither `flags` nor `fileVersion`
may EVER gate a security decision, and slot offsets are compile-time CONSTANTS** - a flipped header
can only produce a clean REFUSE, never a slot-offset miscalculation, an OOB read, or a
different-slot read (red-team-verified, §11). A future `flags` bit must not become an
adversary-flippable control. Carried by: `raft-anchor`
(`ANCHOR_MAGIC`), `node-anchor` (`NODE_ANCHOR_MAGIC`), `raft-keyring` (`KEYRING_MAGIC`), and every
FileStorage log container - `raft-log.wal`, `raft-log.tmp.wal`, `security-audit.wal` - with
`WAL_FILE_MAGIC`. The header is intentionally outside the authenticated surface (it must be
readable with no key); all guarantees ride the authenticated slots/records behind it.

### 2.4 Per-shard anchor file `raft-anchor` (dual-slot; the A1 mechanism)

Location: `dataDir/shard-<gid>/raft-anchor` at N>1, `dataDir/raft-anchor` at N=1 (same placement
rule as the WAL). Written by a dedicated dual-slot writer (fixed-offset pwrite + fdatasync), NOT
`Storage.put`. **Same device/directory as the WAL is REQUIRED** (§6 §4.4).

```
[ container header @ 0, 8 B ]  (ANCHOR_MAGIC, §2.3)
Slot 0 @ offset 8; Slot 1 @ offset 8+512.  File size = 8 + 2x512 = 1032 B (fully preallocated).
Each slot: [recordLen:4][ envelopedAnchorRecord : recordLen ][ zero-pad to 512 ]
envelopedAnchorRecord = EnvelopeV3.wrap(ANCHOR_MAGIC, scopeId=gid, keyTerm=activeTerm, PAYLOAD)
  (HMAC posture 104 B; GCM posture 116 B - stride 512 >> max record)

ANCHOR_PAYLOAD (52 B):
    [anchorSeq:8]          strictly monotonic - the anti-rollback index
    [currentTerm:8]        merged from raft.persistent_state (Election Safety)
    [votedFor:4]           -1 = null (merged)
    [lastDurableIndex:8]   the WAL high-water mark - the truncation anchor
    [lastDurableTerm:8]    term at lastDurableIndex
    [snapshotIndex:8]      the authenticated snapshot boundary (bare snapshot-meta REMOVED)
    [snapshotTerm:8]
```

**Write protocol:** update the slot with the LOWER valid `anchorSeq` (the stale one), with
`anchorSeq = maxValid+1`, then fdatasync. One slot mutated per update; a torn write fails that
slot's CRC/MAC and the untouched slot survives. **Read:** validate container header; parse both
slots (envelope verify + `scopeId==gid`); take highest valid `anchorSeq`. Presence/gate tables:
§2.17.

### 2.5 Node anchor `node-anchor` (dual-slot, node-level)

Same container-header + slot mechanics (NODE_ANCHOR_MAGIC, `scopeId=NODE_SCOPE`), file size 1032 B.

```
NODE_ANCHOR_PAYLOAD (92 B):   # was 60 B before shardAnchorDigest was added
    [nodeAnchorSeq:8]      monotonic
    [topologyEpoch:8]      bound copy of the TopologyDescriptor epoch (rollback guard)
    [shardCount:4]         bound copy of N (deploy-guard tamper/rollback)
    [auditRecordCount:8]   audit-log high-water (periodic cadence, §6 §1)
    [auditHeadHash:32]     last anchored audit record's recordHash (chain head)
    [shardAnchorDigest:32] SHA-256 over the sorted (gid, lastDurableIndex) pairs (R-f closer)
```

Recovery cross-checks `TopologyDescriptor.{epoch,N} == nodeAnchor.{topologyEpoch,shardCount}` =>
mismatch REFUSE; audit replay must reach `{auditRecordCount, auditHeadHash}` => a shorter chain
REFUSEs (truncation confined to the un-anchored tail <=K records/<=T is residual R-e; K/T are
`-Dconfigd.nodeAnchor.auditRecords`/`.intervalMs`, default 64 / 1000 ms).

**shardAnchorDigest boot semantics.** Recovery recomputes the digest over the recovered per-shard
`raft-anchor.lastDurableIndex` values and compares it to the anchored digest. A strict "any change
=> REFUSE" would be unsound: `lastDurableIndex` advances legitimately between the periodic ticks,
so a normal crash restart differs (a FORWARD move), and §1 forbids bricking on a legal crash. The
trigger is narrower - "a shard **reset to index 0** => REFUSE" - so the check mirrors the per-shard
`W<A`/`W>A` asymmetry at the node level:
- digest matches => PROCEED;
- digest differs AND a shard booted **FRESH** (its `raft-anchor` was ABSENT - the R-f wipe signature; a
  legal node never deletes a per-shard anchor) => **REFUSE**;
- digest differs AND no shard is FRESH (per-shard recovery already refused any `W<A` on a present
  anchor) => a legitimate forward advance => **accept-forward: re-anchor + PROCEED**.

This closes R-f (the delete->FRESH variant, matrix 15b); the anchor-rolled-to-an-older-valid-slot
variant stays matrix-14 residual-(a) -> the external `AnchorWitness`. The digest is the frozen,
MAC-authenticated binding that makes "R-f = R-a": to hide a wipe an attacker must roll the node-anchor
back to a matching-digest version that never existed, i.e. forge/roll it (needs the key or the witness).
The refresh (audit head + digest) runs on the K/T cadence off the ack path (each shard's
`lastDurableIndex` read on its owner thread); a failed refresh is logged + retried, NOT the fail-closed
halt the per-shard anchor fsync is.

**Freshness bound (symmetry with R-e).** The digest detects a wipe *relative to the last-refreshed
value*, so there is a bounded freshness window - mint->first tick, and between ticks - in which a wipe
to a value the anchor already binds is invisible (this is the same R-a freshness residual as the
audit-tail R-e, on the shard-liveness field). At the mint over all-zero heads a single-shard wipe of a
*multi-shard* node is still caught (the surviving shards' non-zero heads keep the recomputed digest
different from the all-zero bind); the digest-matches-a-prior-bind case is the full-node wipe ->
rollback-to-first-mint variant, which is R-a (external `AnchorWitness`), not an R-f hole. Steady state
(refreshed to non-zero heads) reliably detects a single-shard wipe->0.

**Auth-off accept-forward preserves the audit head.** On the accept-forward branch when auth is OFF
(`auditLog == null`), the re-anchor writes the node-anchor's *existing* `auditRecordCount` /
`auditHeadHash` verbatim rather than the genesis value an un-observable auth-off boot would
otherwise compute. Regressing the head to genesis would let a later auth-ON boot skip the
audit-truncation cross-check for a truncation that predated the auth-off boot; preserving it keeps
that guard live. Auth ON advances the head normally. This is a strict tightening within the
documented auth-off residual (§1: auth-off carries no adversarial guarantees).

### 2.6 Keyring `raft-keyring` (dual-slot, node-level; the A2 + A5 format)

Exists whenever authentication is on (HMAC or encrypting posture). Lives in `dataDir` (its entries
are wrapped - compromise-value differs from the signing key; D-1 still governs the signing key).

```
[ container header @ 0, 8 B ]  (KEYRING_MAGIC, §2.3)
Slot 0 @ offset 8; Slot 1 @ offset 8+65536.  File size = 8 + 2x65536 = 131 080 B (preallocated).
  Slot stride FROZEN at 64 KiB - bounds retained terms (~900 local / ~200 cloud-blob terms);
  a rotate that would overflow the slot REFUSES loudly (operator escalation; ~centuries away at
  sane cadences).
Each slot: [recordLen:4][ envelopedKeyring ][ zero-pad to 65536 ]
envelopedKeyring = EnvelopeV3.wrap(KEYRING_MAGIC, scopeId=NODE_SCOPE, keyTerm=0, KEYRING_BODY)
  - ALWAYS algId=1 (HMAC) under K_keyringMac, regardless of node posture (§2.2)

KEYRING_BODY:
    [keyringFormatVersion:2 = 1]
    [keyringSeq:8]            monotonic across updates (dual-slot open = highest valid)
    [activeTerm:4]            >= 1
    [entryCount:4]
    entry x entryCount:
        [term:4]              >= 1 (term 0 illegal - distinct from the envelope keyTerm=0 domain)
        [wrapAlgId:1]         1 = local-KEK-GCM ; 2 = cloud-KMS-blob ; unknown => fail closed
        [nonceLen:1][nonce:nonceLen]          12 for local GCM; 0 for cloud
        [wrappedLen:4][wrappedRoot:wrappedLen]  local: AES-GCM ct+tag of the 32-B random root;
                                                cloud: opaque KMS blob
```

Per-entry local wrap: `KEK_wrap = HKDF(signingKey, info="configd/keyring-wrap/v1")`;
**wrap AAD binds `(KEYRING_MAGIC, keyringFormatVersion, term, wrapAlgId, nodeKeyId, "root")`** -
a wrapped root cannot be replayed into a different term slot or node. Key hierarchy, boot, and the
two rotate operations: §4 A2 (normative lifecycle summary in §2.18).

### 2.7 Topology descriptor (replaces `raft-shard-count.meta`, which is REMOVED)

`Storage.put` artifact `topology-descriptor.dat`, node-level:
`EnvelopeV3.wrap(TOPO_MAGIC, scopeId=NODE_SCOPE, keyTerm=activeTerm, PAYLOAD)` where

```
PAYLOAD (18 B): [formatVersion:u16 = 1][shardCount N:u32][topologyEpoch:u64][reserved:u32 = 0 MBZ]
```

Authoritative source for `ShardMap.epoch()` (v1 = 1, epoch 0 reserved-illegal; `StaticShardMap`
returns it instead of hardcoded 0) and the fixed-N boot guard (N mismatch => refuse to start, now
tamper-evident). Cross-checked against the node anchor (§2.5). A v2 dynamic reshard bumps the
epoch monotonically and updates both files.

### 2.8 WAL container + frame + inner record

- **Container**: `WAL_FILE_MAGIC` header (§2.3) at offset 0 of `raft-log.wal` / `raft-log.tmp.wal`
  / `security-audit.wal`; file < 8 B => fresh/empty; bad header => REFUSE.
- **Frame** (per entry, after the header): `[length:4][data:N][CRC32C:4]` - **CRC32C, not zlib
  CRC32** (unification). Torn trailing frame discarded on read (crash tail, kept); complete-frame
  CRC mismatch => throw.
- **Raft inner record** (the frame's `data`): `EnvelopeV3.wrap(WALE_MAGIC, scopeId=gid, ...)` over
  the posture-dependent payload - **authenticated postures (HMAC / GCM):**
  `[index:8][term:8][prevHash:32][command:N]`; **keyless:** `[index:8][term:8][command:N]`
  (byte-identical, no chain - keyless carries no adversarial guarantee, §1). Carrier-versioned; the
  legacy raw-record fallback is DELETED (a non-enveloped record => fail closed).
- **Per-record hash chain (`prevHash`, authenticated postures).** Contiguity and term-monotonicity
  alone do NOT catch an **index-preserving, term-monotonic content rollback**: an old authentic
  frame - from a since-conflict-overwritten term - spliced back over an interior index has
  contiguous indices, non-decreasing terms, a genuine MAC, and the correct scopeId, so every
  position check above passes. The per-record hash chain closes it:
  `prevHash(k) = SHA-256(serialized_inner_payload(k-1))`, genesis `prevHash = 32x0x00` at index 1;
  `prevHash` rides INSIDE the authenticated payload, so the envelope MAC (HMAC) / GCM tag makes it
  unforgeable in place. Recovery verifies each successor's `prevHash` against its predecessor's record
  hash => a break REFUSES (an interior splice is caught by the *successor's* binding, even when the
  spliced frame has a valid incoming link; a re-stamp to repair the chain breaks the successor's own
  authenticator). A compacted first record (`firstIndex > 1`) leaves its `prevHash` unverified for the
  §2.4 snapshot anchor to bind. **Residual (unchanged):** a whole-suffix rollback to a *wholly prior
  valid chain* (the overwritten suffix was necessarily uncommitted) is the head-anchor monotonic-floor
  / `AnchorWitness` case (residual R-a, §4 §4), not a chain hole.
- **Recovery-time checks (normative, NEW):** contiguity (`e[k].index == firstIndex+k`), term
  monotonicity (`e[k].term` non-decreasing), the per-record hash chain above (authenticated postures),
  snapshot-join (`firstIndex == anchor.snapshotIndex+1`; blob boundary equals anchor's), reader scopeId
  assert. Any violation => REFUSE. Position checks detect index permutations/gaps/dups; the hash chain
  detects index-preserving content rollback; the scopeId assert detects cross-shard splice.

### 2.9 Snapshot blob + trailer

Blob: `EnvelopeV3.wrap(SNAP_MAGIC, scopeId=gid, ...)` over
`[lastIncludedIndex:8][lastIncludedTerm:8][dataLen:4][data][configLen:4 (-1=null)][config]`.
Inner `data` ends with the magic-TLV trailer `[0xC0FD7A11][trailerLen:4][signingEpoch:8]` -
unknown tail beyond the known payload tolerated (TLV forward-compat, kept). **Legacy trailer forms
(a) empty and (c) bare-8-byte are DELETED** => throw. Chunking (1 MiB default / 4 MiB per-chunk cap
/ 512 MiB reassembly cap) and `EdgeSnapshotCodec` (lead u64 = DATA seq, not a version) unchanged -
carrier-versioned; documented as such.

**Encryption and chunking are orthogonal and never nest.** At rest the *entire* snapshot
blob is enveloped ONCE (`RaftLog.serializeSnapshot` -> `EnvelopeV3.wrap(SNAP_MAGIC, gid, ...)`): one
algId=2 GCM record with a single `keyTerm` and a single `segmentId` for the whole payload - there is
no per-chunk envelope. Chunking is a *wire* concern: `RaftNode.sendSnapshotChunk` slices the **raw,
unenveloped** state-machine bytes (`stateMachine.snapshot()`, the same bytes the at-rest envelope wraps
as a whole), so an InstallSnapshot chunk carries no `IntegrityEnvelope`; wire confidentiality/integrity
is the transport's job (mTLS in production, plus the per-frame CRC), and each receiving node
re-encrypts the reassembled blob independently under its **own** at-rest key when it persists it. A
`> 4 MiB` snapshot therefore spans multiple wire chunks *as plaintext* while being a single whole-blob
GCM record on every node's disk. `Over4MiBEncryptedSnapshotRoundTripTest` pins both halves (multi-chunk
byte-identical reassembly; whole-blob GCM round-trip + tamper-refused + keyTerm rotation). A "per-chunk
keyTerm/segmentId" mental model does not match the shipped system and would be redundant with mTLS.

### 2.10 Audit record (inside the `security-audit.wal` frames)

```
[AUDIT_MAGIC:4][recordVersion:u8 = 1][canonicalLen:8][canonical][prevHash:32][recordHash:32]
recordHash = HMAC-SHA256(K_audit, AUDIT_MAGIC || recordVersion || prevHash || canonical)   (keyed)
           = SHA-256(same input)                                                        (keyless)
```

The magic+version are **inside the chain input** - a version downgrade breaks the chain. Head
bound by the node anchor (§2.5). Bad magic/version => chain-verification throw.

**Residual: the audit chain is not term-versioned.** `K_audit = HKDF(signing-key)` derives directly
from the signing key, not from a keyring term, so it is the one authenticated at-rest key that
stayed outside the term-versioning work. A *signing-key* rotation (rewrap-before-swap, §2.18)
leaves every pre-rotation audit record readable but no longer tamper-*verifiable* under the new
key: the chain head the node anchor binds still checks under the current key, but records written
before the boundary verify only under the retired key. Term rotation is unaffected (it does not
touch the signing key). This is residual R-g in §4.4 - tamper-evidence is lost across a
signing-key rotation boundary for the pre-rotation audit tail; the records themselves are not
destroyed. Closing it (deriving `K_audit` from a keyring term like the integrity keys) would
require a frozen-format change, since the derivation itself is now frozen.

### 2.11 Watch cursor + topology epoch and SUBSCRIBE resume

```
frozen cursor := [topologyEpoch:u64][count:u32]([gid:u32][S:u64])*count
```

gid strictly ascending unsigned, `S  in  [0, 2^63)`; carrier-versioned by the edge frame version.
**Normative decoder bound (client-facing, unauthenticated input):** a cursor payload of length < 12
(the minimum `[topologyEpoch:u64][count:u32]` with count=0) => FRAME_CORRUPT; every client-facing
decoder MUST map a buffer-underflow to FRAME_CORRUPT, never an uncaught runtime exception (the
cursor rides a CRC-only edge frame from untrusted clients).
**Uniform rule: every resume token binds the epoch** - SUBSCRIBE payloads prepend
`[topologyEpoch:u64]` before the `resume`/`failoverResume` fields. Epoch source: §2.7 via
`ShardMap.epoch()`. Server checks: epoch `0` => FRAME_CORRUPT; epoch != current => **new
`ErrorCode.STALE_TOPOLOGY = 12`** (closed enum extended by one) delivered as WATCH_CANCELED
(watches) or ERROR_CLOSE (subscriptions) - client MUST drop the cursor and fully re-hydrate
(etcd `ErrCompacted` model). Edge wire stays 0x01/0x02/0x03 - payloads are redefined in place; the
edge golden fixtures were regenerated; the raft-wire goldens are untouched.

### 2.12 Policy text pragma

Policy values under `_acl/roles/<role>` and `_acl/bindings/<principal>` MUST begin with the pragma
line `#!configd-acl v1` as **line 1 exactly** (CRLF tolerated). Line 1 not exactly the pragma
(including a plain `#` comment on line 1, or the pragma appearing on line >=2) => parse failure =>
write-time 400 via `validateAclWrite`, load-time reject => loader keeps last-good. `v0` or unknown
`vN` => reject whole value. `#!` on line >= 2 is an ordinary `#` comment. Grammar after line 1
unchanged (role lines `<effect> <caps> <prefix>`, binding lines).

### 2.13 CommandCodec + edge-snapshot chunk body

**Carrier-versioned + assert** - no inner version byte. The command bytes never exist outside a
self-versioned carrier (WALE envelope / edge frame / SNAP envelope); the unknown-type-byte throw is
the assert; the carrier list is documented in code. The edge-snapshot chunk-body lead u64 is a DATA
sequence, not a format version - documented, carrier-versioned.

### 2.14 signing-key.bin

Format unchanged and adequate (magic `0xC0DF51C5`, version 1, keyId, DER key pair). Non-format
fixes made alongside this design: write via temp+fsync+atomic-rename+dir-fsync (previously a bare
`Files.write`, a torn-file risk); a docstring hex typo; the `writeForTest` chmod no-op deleted or
applied.

### 2.15 Wire frames

- **Edge** (`EdgeFrameCodec`): `[len:4][version:1][type:1][payload][CRC32C:4]`, versions
  0x01/0x02/0x03, first-frame pin, watch types 0x0A-0x12 on 0x02+ only - confirmed frozen; payload
  changes are §2.11's epoch fields only. `ErrorCode` gains `STALE_TOPOLOGY=12`.
- **Raft** (`FrameCodec`): `[len:4][ver:1=0x02][type:1][gid:4][term:8][epoch:8][payload][CRC32C:4]`,
  CRC-before-version - confirmed frozen. **The dormant 8-B `epoch` at offset 18 upgrades from
  decode-but-ignore to reject-if-nonzero** (MBZ enforcement; golden vectors carry 0 and stay valid).
- `RaftMessageCodec` payloads: carrier-versioned by the frame; caps unchanged.

### 2.16 Data-directory inventory (frozen v1)

| Artifact | Level | Version story |
|---|---|---|
| `signing-key.bin` (prod: OUTSIDE dataDir, D-1) | node | SV magic+v1 |
| `topology-descriptor.dat` | node | SV (TOPO_MAGIC envelope v3; inner fmt v1) |
| `node-anchor` | node | SV (container hdr + NODE_ANCHOR_MAGIC envelope v3) |
| `raft-keyring` | node | SV (container hdr + KEYRING_MAGIC envelope v3; inner keyring fmt v1) |
| `security-audit.wal` (if audit on) | node | SV container (RWLF v1) + SV records (RAUD v1, chain-bound) |
| `raft-anchor` | per-shard | SV (container hdr + ANCHOR_MAGIC envelope v3) |
| `raft-log.wal` (+ transient `raft-log.tmp.wal`) | per-shard | SV container (RWLF v1) + CV records (WALE envelope v3) |
| `raft-log.snapshot.dat` | per-shard | CV (SNAP envelope v3) + TLV trailer |
| VerifyKeyExporter output (operator path) | export | DE (X.509 DER) |

**REMOVED from the layout (clean break):** `raft.persistent_state.dat` (merged into `raft-anchor`),
`raft-log.snapshot-meta.dat` (folded into `raft-anchor`), `raft-shard-count.meta` (replaced by
`topology-descriptor.dat`). N>1 repeats the per-shard rows under `dataDir/shard-<gid>/`. Every
artifact is SV, CV, or DE - the completeness enumeration in §5.3 has no blank rows.

### 2.17 Recovery gates (normative summary; proofs in §4/§6)

Boot order: signing key -> keyring (dual-slot, highest valid `keyringSeq` under the CURRENT signing
key; absent-with-authenticated-data or MAC-fail => REFUSE with diagnostics) -> topology descriptor +
node anchor (cross-check equality; audit chain verified against the anchored head) -> per shard:
container header -> both anchor slots -> WAL.

- **Presence:** anchor file absent + shard dir empty (no WAL bytes, no snapshot blob) => FRESH
  (bootstrap `anchorSeq=1`); absent + non-empty => REFUSE; present + both slots invalid => REFUSE
  (tamper - distinct from FRESH); >=1 valid slot => proceed with highest `anchorSeq`.
- **WAL checks:** envelope verify + scopeId assert per record; contiguity; term monotonicity;
  snapshot-join (§2.8).
- **Step 2.5 term-witness gate:** `lastWALTerm > anchor.currentTerm` => REFUSE (an anchor rollback
  across a WAL-witnessed vote boundary; in every legal execution the anchor's term dominates the
  log's - proof §6 F-1, confirmed sound against pre-vote/stepdown/follower-append interleavings,
  §11). **Frozen invariant:** the term/vote anchor write MUST stay a standalone
  persist-before-memory fsync - it MUST NOT be folded into the flush-cycle anchor write, nor
  batched/deferred. Either would violate `anchor.currentTerm >= lastWALTerm` and turn this strict
  gate into a false-positive that bricks a healthy node.
- **Head reconciliation** (`W` = WAL last index, `A` = `anchor.lastDurableIndex`): `W == A` ACCEPT;
  `W > A` ACCEPT-FORWARD (adopt WAL head for the LOG only; `currentTerm`/`votedFor` verbatim from
  the anchor; rewrite anchor at `anchorSeq+1`) - safe because entries above `A` were never
  committed-and-client-acked (INV-ANCHOR-ACK); `W < A` => **REFUSE** (a committed entry vanished -
  the attack the anchor exists to catch).
- **Break-glass:** `configd.recovery.rebuildAnchorFromWal=true` - loud, one-shot, audit-logged,
  forensic sidecar of the prior slots; off by default (it is also the adversary's escape hatch).
  **Freeze invariant:** break-glass is a launch-only system property (`Boolean.getBoolean`,
  matching the existing convention at `ConfigdServer:908,1002,1225,1261,1318`); it MUST NEVER be
  read from a data-directory file, or it becomes the adversary's silent anchor-defeat switch.

**Ordering invariants (placed in §6):** INV-ANCHOR-ACK (leader: durableIndex/self-vote - and hence
commit/ack - only after the covering anchor fdatasync; follower: AppendEntries success only after
the covering anchor fdatasync); INV-ANCHOR-LOWER (conflict truncation lowers the anchor to
`conflictPoint-1` and fsyncs BEFORE the WAL rewrite; compaction advances `snapshotIndex` in the
anchor LAST). **fsync-failure policy:** WAL- or anchor-fsync throw/lie => no durable-advance, no
ack, process exit (fsyncgate). Anchor slots preallocated at creation => steady-state anchor ENOSPC
impossible (ext4/xfs; COW filesystems documented weaker). Anchor MUST live in the same directory
(same device) as its WAL.

### 2.18 Rotation lifecycle (normative summary; full lifecycle + proofs in §4 A2)

Roots are independent random 32-B secrets per term (NOT derived from the signing key), wrapped in
the keyring; the signing key only authenticates (`K_keyringMac`) and wraps (`KEK_wrap`) the
keyring. Boot seeds `SegmentKeyManager` with ALL terms + `activeTerm` (hardcoded term=1 is gone).
**Term rotation** (admin op): generate random `root[activeTerm+1]`, append entry, bump
`activeTerm`, write keyring slot (`keyringSeq+1`), fsync, then `rotateTo` - old terms retained
forever; never re-encrypt. **Signing-key rotation:** with old+new keys present, rewrap ALL entries
under the new KEK into a new slot (`keyringSeq+1`) BEFORE swapping `signing-key.bin`, then swap,
then restart - crash on either side of the swap boots on the matching slot; roots unchanged => all
old data verifies. Unknown term on read => fail closed. The anchor authenticates under
`K_integrity[keyTerm]` with its own `keyTerm` stamp, so neither rotation invalidates old anchors.

**Residual: the rotation mechanisms ship, but no live/admin trigger does (R-h).** `NodeKeyring.
rotateTerm`, `SegmentKeyManager.rotateTo`, and `NodeKeyring.rewrapForNewSigningKey` are built,
crash-atomic, and tested (`NodeKeyringTest`, `KeyringKeyTermSelectionTest`, `NodeKeyringRedteamTest`),
but they have **no `src/main` caller** - boot loads all retained terms and writes on the keyring's
`activeTerm`, and nothing on the running server or an admin endpoint invokes a rotation. So a
rotation today is an out-of-band maintenance action against a stopped node's data directory (or a
future tool), not a hot-path or online operation. This is honest scope, not a defect: the frozen
format makes the mechanism correct-by-construction whenever it is wired; wiring an admin/online
trigger needs no format change (the keyring format already supports append-and-retain).

---

## §3 Design notes and corrections

A few points where a passage elsewhere in this document revises or specializes an earlier one:

1. **Envelope v3 supersedes envelope v2.** Anywhere below still says "envelope v2," read v3; the
   CRC-before-version parse ordering and the explicit `reserved != 0` check are part of v3.
2. **Snapshot-meta is folded into the per-shard anchor.** `SNAPMETA_MAGIC` was never allocated as a
   separate format.
3. **The topology descriptor is a standalone file** (`topology-descriptor.dat`, §2.7), and the node
   anchor binds a copy of `{epoch, N}` for cross-checking, rather than the descriptor being folded
   into the anchor.
4. **The keyring/anchor "formatVersion=1" placeholders mean:** envelope v3 discriminated by a new
   magic plus an 8-byte container header (`fileVersion=1`); the keyring's inner
   `keyringFormatVersion=1` is a separate field (its body is the evolving entry format).
5. **The state file is merged into the anchor** (§2.4): `raft.persistent_state.dat` is removed and
   `STATE_MAGIC` is retired-reserved. Its test coverage (`forgedVotedForRefused`) moved to the
   anchor test surface.
6. **The `max(anchor.currentTerm, lastWALTerm)` repair rule was deleted** in favor of the strict
   term-witness REFUSE (§2.17 step 2.5); accept-forward never touches `currentTerm`/`votedFor`.
   This upgrades a slice of residual R-a into a detected case (matrix row 17d) - the proof is in
   §6 F-1.
7. **The anchor upper-bounds "everything committed-and-client-acked,"** not "everything ever
   acked": a follower's matchIndex ACK is a durability report, not a client promise.
   INV-ANCHOR-LOWER is sound because conflict points are always above `commitIndex` - see §6 F-2.
8. **`keyTerm = 0` is the signing-key domain** (§2.2): the keyring's outer envelope stamps
   `keyTerm=0`, illegal under any other magic; keyring slots are always algId=1.
9. **A few numbers were corrected while building the compile-checked prototype** (§7):
   `NODE_ANCHOR_PAYLOAD` is 60 bytes (an earlier "56 B" label was an arithmetic slip); the
   cross-shard-splice mechanism is reader-assert plus unforgeable-scope (§A1.2, matrix row 10);
   `TOPO`/`AUDIT`/`WAL_FILE` magic values are `RTOP`/`RAUD`/`RWLF` (§2.1, collision-checked); the
   keyring slot stride is frozen at 64 KiB (an earlier 4 KiB placeholder); the policy pragma's
   line-1-only rule is fully byte-specified (§2.12).
10. **Audit-head cadence is periodic** (K=64 records or 1 second, not per-record) - residual R-e is
    bounded and documented, and tunable to K=1 for audit-critical deployments.
11. **Naming:** the topology descriptor file is `topology-descriptor.dat` (a `Storage.put`
    artifact). The keyring and anchors are not `.dat` artifacts - they are dual-slot in-place
    writers.

Residuals R-a (narrowed by row 17d), R-b, R-c, R-d, and R-e are detailed in §4.4.

---

# Part II - design reasoning and proofs (Part I governs where they conflict)

# §4 The anchor and key rotation: attack matrix, rotation proofs, residuals

This section works through the anchor and rotation design in more depth than Part I: the facts
established against the source before the design started, the reasoning behind merging
`raft.persistent_state` into the anchor, the recovery-gate decision tables, the completeness proof
(attack to detection matrix), and the non-destructive rotation proof.

Every claim here is cited to `file:line`, verified against source before this design was built.
Byte layouts are exact to the byte. This design closes the truncation/rollback anchor gap and the
non-destructive-rotation gap, plus the cross-shard-splice and snapshot-meta-tamper holes surfaced
below.

---

## 0. Threat model

> The at-rest anchor and keyring detect **tamper, truncation, rollback, reorder, splice, and
> cross-artifact/shard/node replay** performed by an adversary who has **filesystem write
> access to the data directory but does NOT hold the key material** (the cluster signing key,
> which is the root of every derived key and, per D-1, lives OUTSIDE the data directory -
> `ConfigdServer.java:1366-1397`). Two things are **explicitly out of scope**, exactly where
> dm-verity, dm-integrity, Android Verified Boot, and Vault draw the same line (prior-art §1c):
> (a) **rollback of the anchor itself** to a prior *legitimately-authenticated* state - undetectable
> without external monotonic storage (TPM/RPMB NV counter) or a remote witness, because a valid
> prefix/older-state is byte-for-byte a valid state; (b) an adversary holding **both** the disk
> **and** the key, who can forge anything. Crash-consistency (torn tails, partial writes,
> bit-rot) is a *non-adversarial* fault handled by CRC + dual-slot + fsync ordering, and MUST
> NOT be conflated with the adversarial case - a design that bricks a node on a legal crash or a
> legal Raft transition fails just as surely as one that misses an attack.

Optional hardening for (a): a `configd.security.anchorWitness` SPI hook that additionally
writes `nodeAnchorSeq`/per-shard `anchorSeq` to external monotonic storage and refuses boot on
regression. Specified as an interface only (§A1.7); not built in v1.

---

## 1. Established facts (verified against source, load-bearing)

1. **Recovery does NOT enforce contiguity / slot-consistency.** The RaftLog constructor replays
   the WAL with `entries.add(deserializeEntry(raw))` in a bare loop (`RaftLog.java:151-154`).
   `deserializeEntry` reads the embedded `[index][term]` (`:647-660`) but **never checks that the
   embedded index equals the slot position or is contiguous**. The contiguity guard
   (`entry.index() != expectedIndex -> throw`) lives only in `append()`/`appendNoSync()`
   (`:360-365`), which recovery bypasses. Read-side access is **position-based**:
   `entryAt(i)`/`termAt(i)` index the array via `toOffset(i) = i - snapshotIndex - 1`
   (`:613-615, 261-266`) and **trust position, not the embedded index**. => Reordering or
   splicing complete WAL frames is **undetected today** and silently returns the wrong entry.
   The frozen design MUST add the recovery-time checks (§A1.4); "the bytes are authenticated"
   is *not* sufficient.

2. **`LocalDerivedKmsProvider.derive()` ignores the term entirely.** `derive()` computes
   `Hkdf.deriveKey(signingKeyIkm, salt, KEK_INFO, 32)` (`LocalDerivedKmsProvider.java:111-120`)
   - the `term` field is only stamped into the `KeyId` metadata (`:116`), never fed to the HKDF.
   => (a) bumping the term with the same signing key yields the **identical** root bytes (a
   term "rotation" achieves zero cryptographic separation); (b) rotating the signing key
   re-derives a **different** root **at the same hardcoded term=1** (`ConfigdServer.java:1325`),
   so every prior GCM record fails its tag and the node **bricks** on recovery. The documented
   `local` rotation procedure is data-destroying. Confirmed gap §2.3-4.

3. **No shard binding anywhere in the crypto.** DEK = `HKDF(root[term], salt=segmentId, DEK_INFO)`
   (`SegmentKeyManager.java:190-200`); the root is node-wide (ONE `SegmentKeyManager` shared
   across all N groups - `ConfigdServer.java:1328-1335`); `nextSeal` keys write segments by
   MAGIC only (`SegmentKeyManager.java:148-163`) and `WALE_MAGIC` is shared by every shard; the
   GCM AAD is `header||keyTerm||segmentId||nonce` with **no gid** (`IntegrityEnvelope.java:102-104,
   425`); the inner WAL payload is `[index][term][command]` with **no gid**
   (`RaftLog.java:627-634`). => **Cross-shard splice verifies today**: a WAL record from shard-1
   copied into shard-0's WAL at the slot matching its embedded index decrypts (same node root,
   same term, gid absent from AAD) and, in HMAC mode, MACs (same node K_integrity, gid absent
   from MAC input). The frozen format MUST bind shard identity (§A1.2).

4. **Cross-NODE replay is already caught** (confirm, keep). The `local` root chains to
   `signing-key.bin` (`ConfigdServer.java:1228-1236`, `LocalDerivedKmsProvider.java:111-120`),
   which is per-node (`SigningKeyStore`, inventory §16A) and, per D-1, off the attacker's
   storage. Another node's records derive a different root => different DEK/K_integrity => tag/MAC
   fails. True in both encrypting and keyed-HMAC postures.

5. **`raft.persistent_state` is a separate 12-byte atomic-rename file**, `[term:8][votedFor:4]`
   wrapped `STATE_MAGIC` (`DurableRaftState.java:157-165`), written only on term-change/vote
   (`:99-148`), authenticated but **with no monotonic sequence** => whole-file rollback to an
   older *legitimately-MAC'd* state is undetected (gap §2.3-2 tail).

6. **`raft-log.snapshot-meta` is written BARE** - `storage.put(SNAPSHOT_META_KEY, metaBuf.array())`
   with a raw 16-byte `[snapshotIndex:8][snapshotTerm:8]`, **no envelope/magic/CRC/MAC**
   (`RaftLog.java:584-587`), read back raw (`:161-165`). => snapshot-boundary tamper is undetected.

7. **The audit chain has the same headless-truncation hole.** `security-audit.wal` records chain
   `recordHash = HMAC(K_audit, prevHash||canonical)` (`AuditLog.java:347-364, 381-389`) but nothing
   persists the chain HEAD, so deleting trailing records leaves a self-consistent shorter chain
   (inventory §16B). Same root cause as the WAL.

---

## A1. The anchor

### A1.1 Decision: merge `raft.persistent_state` into the anchor (recommended)

The anchor is **one dual-slot, authenticated, per-shard file** that carries a monotonic
`anchorSeq` AND the Raft persistent state (`currentTerm`, `votedFor`). This is the clean-break
choice, weighed against keeping the state file separate:

| Axis | Merged anchor | Keep state separate |
|---|---|---|
| State-file-only rollback | **Impossible by construction** - there is no separate state file; reverting term/vote requires reverting the whole anchor (lower `anchorSeq`), which is the documented anchor-rollback residual, strictly harder than today's silent independent file swap | Undetected today (fact 5); would need its own monotonic seq to fix - i.e. re-invent the anchor anyway |
| Write cost for a vote | **One dual-slot write + one fsync** (in-place pwrite to the stale slot) | Today: tmp-write + fsync + rename + dir-fsync (4 ops) - merged is *cheaper* |
| Crash-atomicity | Dual-slot A/B: torn slot fails CRC/MAC, other slot wins (bbolt/LMDB pattern, prior-art §1d) | rename is atomic but single-copy |
| Write cadence coupling | Head advances every flush; term/vote rarely. Both serialize on the owner thread; `anchorSeq` monotone across both. Contention negligible (a leader flushes but doesn't vote; a candidate votes but doesn't flush) | - |
| **Raft-safety coupling (the con)** | A format/write bug now breaks **Election Safety AND durability** together. Mitigated: the record is tiny, fixed-shape, dual-slot, MAC'd, and gets the heaviest test surface | A bug is isolated to one axis |

**Recommendation: MERGE.** The security win - state rollback goes from *silent and undetected*
to *requires full-anchor rollback (the residual)* - is decisive, and it removes a whole
unauthenticated-position artifact class. The coupling con is real but contained by the trivial,
fixed-size record. (This subsumes the separate `raft.persistent_state.dat`; that file no longer
exists in the frozen layout.)

### A1.2 The frozen IntegrityEnvelope (adds `scopeId` + `keyTerm`)

To bind shard identity (fact 3) and term-versioned integrity keys (needed for non-destructive
rotation, §A2.6), the envelope gains a 4-byte **`scopeId`** (authenticated in every posture) and,
for the HMAC posture, a 4-byte **`keyTerm`** (GCM already had one). `FORMAT_VERSION` bumps to
**3**. Header stays 8 bytes and the version marker stays at offset 4, readable before any crypto
(Kafka magic-before-CRC lesson, prior-art §Q4).

```
Header (all algIds, 8 B):  [magic:4][formatVersion:2 = 3][algId:1][reserved:1 MBZ]
then, for ALL algIds:      [scopeId:4]           # authenticated; NOT just CRC in keyed/enc

algId=0 NONE (keyless):    header || [scopeId:4] || [payload:N] || [CRC32C:4]
algId=1 HMAC_SHA256:       header || [scopeId:4] || [keyTerm:4] || [payload:N] || [MAC:32] || [CRC32C:4]
                             MAC = HMAC(K_integrity[keyTerm], magic||fmtVer||algId||rsv||scopeId||keyTerm||payload)
algId=2 AES256_GCM:        header || [scopeId:4] || [keyTerm:4] || [segmentId:16] || [nonce:12]
                             || [ciphertext+tag] || [CRC32C:4]
                             AAD = the 44-byte prefix magic..nonce (was 40; scopeId inserted after header)
                             DEK = HKDF(root[keyTerm], salt=segmentId, DEK_INFO)
```

**`scopeId` values:** per-shard artifacts (WAL entry, snapshot blob, per-shard anchor) ->
`scopeId = gid` (the group id, `0..N-1`). Node-level artifacts (keyring, node-anchor, audit) ->
`scopeId = NODE_SCOPE = 0xFFFFFFFF`. Mechanism precision: a
shard-1 WAL record copied byte-for-byte into shard-0's WAL still MAC/tag-VERIFIES as bytes (keys are
node-wide; the AAD/MAC input is built from the record's own bytes) - detection is the **reader's
mandatory `scopeId == gid` assert**, which refuses the record because its authenticated `scopeId`
announces its true shard (1 != 0). The MAC/tag's role is that the adversary **cannot forge the
`scopeId` in place** (any edit invalidates the MAC/tag). Reader-assert + unforgeable-scope together
=> **cross-shard splice detected** in both postures; neither alone suffices, and the assert is a
normative MUST on every read path. A per-shard artifact replayed as node-scope (or vice versa)
fails the same assert. `magic` still
blocks cross-artifact confusion; `scopeId` adds the cross-shard axis; `keyTerm` selects the
retained-term key.

`RaftLog`/`DurableRaftState` are constructed per group (`ConfigdServer.buildRaftGroup:1592-1606`),
so each knows its `gid` and passes it as the envelope `scopeId` on write and asserts it on read.

### A1.3 Per-shard anchor file byte layout (dual-slot)

File `raft-anchor` (in `dataDir/shard-<gid>/` at N>1, `dataDir/` at N=1 - same placement rule as
the WAL, `buildRaftGroup:1600-1604`). **Not** a `Storage.put` artifact: it is written by a
dedicated dual-slot writer (fixed-offset `pwrite` + `fsync`), the bbolt/LMDB meta-page pattern
(prior-art §1d), via a small `AnchorFile` class rather than the tmp+rename `Storage.put` path.

Per the container convention in §2.2, the FILE self-identifies at offset 0
with an 8-byte container header (foreign-file / version guard, corruption-only - authentication is
the per-slot envelope); each slot is then a self-versioned `IntegrityEnvelope`.

```
[ container header @ 0, 8 B ]  [ANCHOR_MAGIC:4]["RANC"][fileVersion:u8 = 1][flags:u8 = 0][reserved:u16 = 0 MBZ]
Slot 0 @ offset 8,  Slot 1 @ offset 8+512.   File size = 8 + 2*512 = 1032 B.  (Stride 512 >> max record.)
Each slot:  [recordLen:4][ envelopedAnchorRecord : recordLen ][ zero-pad to 512 ]

envelopedAnchorRecord = IntegrityEnvelope.wrap(ANCHOR_MAGIC, scopeId=gid, ANCHOR_PAYLOAD)
    HMAC posture size = 8+4+4 +52 +32 +4 = 104 B ;  GCM posture = 8+4+4+16+12 +(52+16) +4 = 116 B

ANCHOR_PAYLOAD (52 B):
    [anchorSeq:8]          # strictly monotonic; the anti-rollback index (the whole point)
    [currentTerm:8]        # merged from raft.persistent_state (Election Safety)
    [votedFor:4]           # -1 = null (merged)
    [lastDurableIndex:8]   # the WAL high-water mark - the truncation anchor
    [lastDurableTerm:8]    # term at lastDurableIndex (binds the tip to a term)
    [snapshotIndex:8]      # authenticates the currently-BARE snapshot-meta (fact 6)
    [snapshotTerm:8]
```

`ANCHOR_MAGIC = 0x52414E43 "RANC"` (distinct from RFST/RSNP/RWAL,
`RaftArtifactMagic.java:22-29`). The bare `raft-log.snapshot-meta` is **removed** - the anchor is
the authenticated snapshot boundary; `RFST`/`raft.persistent_state.dat` is **removed** - merged.

**Write protocol (crash-atomic):** to update, pick the slot with the *lower* valid `anchorSeq`
(the stale one), write `[recordLen][envelope]` there with `anchorSeq = maxValid+1`, then `fsync`.
Only one slot is ever mutated per update; the other stays intact, so a torn write is detected by
that slot's CRC/MAC and the untouched slot (lower seq, still valid) remains a fallback. Atomicity
comes from **CRC+MAC detection + write-one-slot**, not from sector-atomic hardware.

**Read/open:** parse both slots, `unwrapOrNull` each (asserting `scopeId==gid`), and take the slot
with the **highest valid `anchorSeq`**. See §A1.4 for what happens when zero/one/both are valid.

### A1.4 Recovery gates (decision tables) and the new contiguity checks

**Step 1 - WAL replay integrity (per record).** FileStorage drops any torn trailing frame first
(`FileStorage:271`), so every frame reaching RaftLog is complete; `deserializeEntry` verifies the
envelope (`RaftLog:647-660`) - MAC/tag/CRC/version/`scopeId==gid` all fail-closed. Then, **NEW,
the checks recovery lacks today (fact 1):**

- **Contiguity:** for the surviving entries `e[0..m-1]`, require `e[k].index == firstIndex + k`
  (first slot's embedded index defines `firstIndex`; every subsequent embedded index is exactly
  +1). Any gap/dup/reorder => **REFUSE**. This is what makes reorder/splice detection *real*.
- **Term monotonicity:** require `e[k].term` non-decreasing. A term that goes down mid-log =>
  **REFUSE** (Raft never writes a lower term after a higher one at a later index).
- **Snapshot join:** require `firstIndex == snapshotIndex + 1` (WAL non-empty) against the
  anchor's `snapshotIndex`; and if a snapshot blob is present, `blob.lastIncludedIndex ==
  anchor.snapshotIndex` and `blob.lastIncludedTerm == anchor.snapshotTerm`.

**Step 2 - anchor presence** (dual-slot):

| Anchor slots | Other artifacts (WAL/snapshot) | Decision |
|---|---|---|
| both absent (no file) | all absent | **FRESH NODE** - bootstrap anchor at `anchorSeq=1, lastDurableIndex=0, currentTerm=0, votedFor=-1` |
| both absent (no file) | any present (non-empty data dir) | **REFUSE (fail closed)** - an anchor was deleted; a non-empty data dir must carry its anchor |
| >=1 slot valid | - | proceed to Step 3 with the highest-valid-`anchorSeq` slot |
| file present, **both slots invalid** | - | **REFUSE (fail closed)** - tamper (distinct from FRESH, which has no file at all) |

Break-glass: `configd.recovery.rebuildAnchorFromWal=true` (loud, one-shot) rebuilds the anchor
from the current WAL head after a *genuine* catastrophic anchor loss. It MUST: print the WARNING
banner, emit an **audit record** `{action=anchor.rebuild, target=<gid>, rebuiltTo=<walHead,
walTerm, snapIndex>, operator=<id>, ts}`, and record the pre-rebuild slot bytes (if any) to a
sidecar for forensics. It accepts whatever the WAL says - so it is *also* the adversary's escape
hatch and is therefore audit-logged and off by default.

**Step 2.5 - term-witness gate** (F-1, §6). Under the merge, `currentTerm` is
anchored (persist-before-memory, §A1.1) strictly BEFORE any *local* WAL entry at that term can be
written: a leader persists `setTermAndVote(T)` at election before it proposes at T; a follower
persists the term update on RPC receipt before it appends. Every subsequent flush write also carries
`currentTerm=T`. Hence **`anchor.currentTerm >= lastWALTerm` is an invariant of every legal
execution, including every crash window.** Recovery asserts it:

> `lastWALTerm > anchor.currentTerm  =>  REFUSE (fail closed).`

The only way this fires is an **anchor rollback across a term/vote boundary that the WAL witnesses**,
so the gate *upgrades* that slice of residual (a) into a DETECTED case. (My earlier `max()`-repair
rule was wrong: it would have silently *accepted* exactly that rollback and cleared `votedFor` - a
double-vote hazard. Reliability's proof retired it.)

**Step 3 - head reconciliation** (the security asymmetry). Let `W = WAL last index`,
`A = anchor.lastDurableIndex`:

| Relation | Cause | Decision | Why safe |
|---|---|---|---|
| `W == A` | clean shutdown | **ACCEPT** as-is | - |
| `W  >  A` | crash between WAL fsync and anchor fsync (leader flush) OR mid-conflict-truncate re-adopt | **ACCEPT & reconcile forward**: adopt the WAL head for the LOG only; take `currentTerm`/`votedFor` **verbatim from the anchor, unchanged**; rewrite the anchor to the new head | Entries `(A, W]` were **never committed-and-client-acked** - the leader counts its self-copy toward quorum only after the anchor covers it, and a follower reports matchIndex only after its anchor covers it (§A1.5), so no client promise rests on them; a normal uncommitted crash tail Raft may re-truncate. `currentTerm` is untouched because Step-2.5 already proved `anchor.currentTerm >= lastWALTerm` - the anchor's term is already correct-and-current, so there is nothing to repair, and the former `max()`+clear-vote rule would have masked an anchor rollback instead of refusing it |
| `W  <  A` | **adversarial trailing truncation** or catastrophic WAL loss | **REFUSE (fail closed)** | The anchor asserts `A` was the committed-and-acked durable floor; its disappearance means a committed entry vanished. This is precisely the attack the anchor exists to catch |

### A1.5 The ordering invariant

- **INV-ANCHOR-ACK (leader):** the leader may count its self-copy of entry `i` toward the commit
  quorum - and therefore ack the client - ONLY AFTER an anchor slot with `lastDurableIndex >= i`
  is `fsync`'d. (Today the leader gates on `durableIndex` from the WAL fsync,
  `RaftNode.flushDurable:2223-2237`, `maybeAdvanceCommitIndex:2153-2175`; the anchor fsync joins
  that barrier.)
- **INV-ANCHOR-ACK (follower):** a follower may send an AppendEntries success reporting
  `matchIndex = i` ONLY AFTER its anchor slot with `lastDurableIndex >= i` is `fsync`'d. (Today the
  WAL fsync precedes the ACK, `RaftLog.appendEntries:448-451`; the anchor fsync joins it.)
- **INV-ANCHOR-LOWER (conflict truncation & compaction):** before any WAL rewrite that would leave
  `WAL head < a previously-anchored lastDurableIndex`, the anchor must FIRST be made durable with
  `lastDurableIndex <= new head`. Concretely, conflict truncation (`RaftLog.truncateFrom:461-479`
  -> `appendEntries:440-445`) becomes: (1) anchor-write lowering `lastDurableIndex` to
  `conflictPoint-1`, fsync; (2) WAL rewrite + append the leader's entries, fsync; (3) anchor-write
  raising to the new head, fsync; (4) ACK. Compaction (`compact:551-590`) never lowers the *head*
  (it drops a prefix), so it only advances `snapshotIndex` in the anchor after `persistSnapshot`
  and the WAL rewrite - anchor last, as today's ordering already does.
  - **Corollary - the downward move never uncovers a committed entry (F-2, §6).** A
    conflict point is always `> commitIndex` (Raft never truncates a committed entry - Log Matching
    + Leader Completeness), so lowering the anchor to `conflictPoint-1 >= commitIndex` only ever
    exposes *uncommitted* tail entries, which Raft is entitled to re-truncate. The downward anchor
    move is therefore sound: it can never drop the anchor below the committed-and-client-acked floor.

**Security argument for anchor-BEFORE-ack (not Postgres-style anchor-lag):** a *client* ack is a
promise that the entry is committed and durable. (A follower's matchIndex ACK to the leader is a
durability report that *feeds* the commit quorum, not itself a client promise - but it must still be
anchor-covered, or the leader could commit on durability that isn't there.) If the anchor is allowed
to lag, there is a window `(anchor, committed]` in which an adversary can trim the WAL to just below
a committed index and recovery's Step-3 `W >= A => accept-forward` rule would **silently accept the
truncated log** - the committed entry vanishes with no refusal. Anchor-before-ack guarantees, at
every node, `A >= every index that node durably contributed to a committed-and-client-acked entry`,
so truncating any such entry always trips `W < A => REFUSE`. Postgres tolerates a lagging
`pg_control` precisely because it is a *crash* anchor, not an *anti-rollback* anchor (prior-art
§Q2, §1a); our stated threat model forbids that lag. etcd achieves the same by committing the
`consistent_index` in the *same* backend txn as the data (prior-art §Q2) - our single ordered
anchor fsync per flush cycle is the fs-level equivalent.

**Cost shape:** one additional ordered fsync per flush cycle (WAL fsync -> anchor fsync),
amortized by group commit (one anchor fsync per batch, `RaftLog.syncWal:378-382`); plus one extra
anchor fsync on the *rare* conflict-truncation path.

### A1.6 Node-level anchor (topology + audit head)

Two node-level artifacts share the WAL's headless-truncation shape and are cheap to cover, so
they are covered by a second dual-slot file `node-anchor` in `dataDir/` (`scopeId =
NODE_SCOPE`), `NODE_ANCHOR_MAGIC = 0x524E414E "RNAN"`, same container header + slot
mechanics as §A1.3 (8-byte `[NODE_ANCHOR_MAGIC:4][fileVersion:u8=1][flags:u8=0][reserved:u16=0]`
header, then two 512-byte slots):

```
NODE_ANCHOR_PAYLOAD (92 B):   # was 60 B before shardAnchorDigest was added
    [nodeAnchorSeq:8]      # monotonic
    [topologyEpoch:8]      # binds the standalone TopologyDescriptor's epoch (rollback guard)
    [shardCount:4]         # binds N (deploy-guard tamper/rollback)
    [auditRecordCount:8]   # audit-log high-water
    [auditHeadHash:32]     # the last audit record's recordHash - binds the chain head
    [shardAnchorDigest:32] # SHA-256 over the sorted (gid, lastDurableIndex) pairs - the R-f closer
```

- Topology: the standalone versioned `TopologyDescriptor` `{formatVersion, N,
  topologyEpoch}` (§2.7) remains the **authoritative source** read by `ShardMap.epoch()`
  and the fixed-N boot guard - it is node-level, cluster-consistent, deploy-authored, and
  envelope-authenticated (a plaintext N would be editable to bypass the
  reshard refusal). The node-anchor binds a **copy** of `{topologyEpoch, shardCount}`; recovery
  cross-checks equality (`TopologyDescriptor.{epoch,N} == nodeAnchor.{topologyEpoch,shardCount}`)
  => mismatch REFUSE. This catches a **topology-file rollback** (swap the descriptor for an older
  legitimately-MAC'd one - a live threat only in a v2 dynamic reshard, since v1 static-N has just
  one legitimate topology, but frozen now for free). A legitimate v2 reshard updates BOTH files
  (advancing `nodeAnchorSeq`).
- Audit: the node-anchor advances `auditRecordCount`/`auditHeadHash` on a
  **periodic cadence** (K=64 records or 1 s, not per-record - audit is off the ack path, so a
  per-record anchor fsync is pure overhead). On recovery the replayed chain head must match the
  anchored `auditRecordCount`/`auditHeadHash`; a shorter self-consistent chain that drops records
  **below the last anchored head is DETECTED (REFUSE).** **Bounded residual (R-e):** truncation
  confined to the un-anchored tail (the last <=K records / <=1 s before a crash) is undetected - a
  strict improvement over a fully-undetected chain, honestly bounded rather than claimed
  closed. `VerifyKeyExporter` output stays an export (not server state), unanchored - documented
  residual.
- **Shard liveness (the R-f closer):** the node-anchor binds a
  `shardAnchorDigest` = SHA-256 over the sorted `(gid, lastDurableIndex)` pairs of every per-shard
  `raft-anchor`, refreshed on the SAME K/T cadence (off the ack path; each shard's `lastDurableIndex`
  is read on its owner thread). On recovery the digest is recomputed over the recovered per-shard
  heads. **Boot semantics (mirroring the per-shard `W<A`/`W>A` asymmetry so a
  legal crash never bricks - §1):** digest matches => PROCEED; digest differs AND a shard booted FRESH
  (its `raft-anchor` was ABSENT - the R-f wipe signature) => **REFUSE**; digest differs AND no shard is
  FRESH (per-shard recovery already refused any `W<A`) => a legitimate forward advance => accept-forward
  (re-anchor + PROCEED). A strict "any change => REFUSE" would be wrong: `lastDurableIndex` advances
  between ticks, so it would refuse every crash restart under load (esp. N=1). This raises R-f from
  silent-loss to a detected node-anchor rollback (= R-a): to hide a wipe an attacker must roll/forge the
  node-anchor to a matching-digest version that never existed.

### A1.7 External-witness hook (residual (a) mitigation)

`interface AnchorWitness { void record(int scopeId, long anchorSeq); long lastSeen(int scopeId); }`
When configured, the anchor writer calls `record` after each fsync and boot calls `lastSeen`; a
`storedSeq < lastSeen` => REFUSE (anchor rollback detected via external monotonic storage). This is
the only construct that can close anchor-rollback.

The SPI is realized by a **peer-quorum** `AnchorWitness`
(`PeerQuorumAnchorWitness`): a node's monotonic `anchorSeq` (and its per-term vote) is witnessed over
the existing Raft channel by a quorum of peers, and a node REFUSES to boot or grant a vote below the
highest value a peer quorum witnessed from it. This closes **R-a'** exactly where split-brain is
possible (N>=3); see `docs/design/anchor-witness-peer-quorum-
2026-07-04.md`. The **external-monotonic-store** realization (TPM/RPMB/remote KV - the closer for the
R-a *freshness* residual, a node-local within-term rollback with no live peer to witness it) is NOT
built; it slots into this same SPI as a future extension. So: the SPI plus the peer-quorum realization
close R-a'; the external-store realization remains a documented, unbuilt extension point, and R-a
stands.

---

## A2. Non-destructive key rotation

### A2.1 Root cause (verified) and the fix shape

Today roots are HKDF(signing key) ignoring term (fact 2), so "term" is meaningless and
signing-key rotation bricks the node. The frozen fix is the **Vault keyring** (prior-art §Q3):
roots become **independent random 32-byte secrets**, generated once per term, **wrapped** and
persisted in a versioned keyring; new writes use the active term, **all old terms are retained
forever for decrypt**, unknown term fails closed. Rotation *appends a term*, never re-encrypts.
This decouples the roots from the signing key: the signing key becomes only the KEK that *wraps*
the keyring, so rotating it **rewraps** (roots unchanged => all old data still verifies).

### A2.2 The keyring frozen format (doubles as the WrappedKey envelope)

File `raft-keyring` in `dataDir/` (node-level). **It may live inside `dataDir`** and does NOT need
the D-1 guard: its compromise-value differs from the signing key's - the entries are wrapped by a
KEK derived from the signing key, so an adversary with only data-dir write access cannot unwrap
them. (D-1 still protects the *signing key* itself, `ConfigdServer:1366-1397`.) **Dual-slot** (to
make signing-key rotation crash-atomic, §A2.4), same container header + slot mechanics as the
anchor:

```
[ container header @ 0, 8 B ]  [KEYRING_MAGIC:4]["RKYR"][fileVersion:u8 = 1][flags:u8 = 0][reserved:u16 = 0]
Slot 0, Slot 1 (fixed offsets):  [recordLen:4][ envelopedKeyring ][ pad ]

envelopedKeyring = IntegrityEnvelope.wrap(KEYRING_MAGIC, scopeId=NODE_SCOPE, KEYRING_BODY)
    posture = HMAC under K_keyringMac  (see §A2.3)  - the OUTER integrity of the whole keyring

KEYRING_BODY:
    [keyringFormatVersion:2 = 1]   # 0 illegal; unknown => fail closed (protocol §0.2)
    [keyringSeq:8]                 # monotonic across rotations (dual-slot open = highest valid)
    [activeTerm:4]                 # >= 1 ; term 0 reserved-illegal
    [entryCount:4]
    entry * entryCount:
        [term:4]                   # >= 1 ; term 0 reserved-illegal
        [wrapAlgId:1]              # 1 = local-KEK-GCM ; 2 = cloud-KMS-blob ; unknown => fail closed
        [nonceLen:1][nonce: nonceLen]        # 12 for local GCM ; 0 for cloud
        [wrappedLen:4][wrappedRoot: wrappedLen]   # local: AES-GCM ct+tag ; cloud: opaque KMS blob
```

`KEYRING_MAGIC = 0x524B5952 "RKYR"`.

- **Outer envelope MAC** (`K_keyringMac`) authenticates the **entire body** - the entry set,
  `activeTerm`, and count - so strip / swap / add / truncate of keyring entries **fails loud**
  (age's "MAC over the whole header" property, prior-art §Q6). This is what closes the keyring's
  own truncation hole.
- **Per-entry local wrap** binds `AAD = KEYRING_MAGIC||keyringFormatVersion||term||wrapAlgId||
  nodeKeyId||"root"`, so a wrapped root cannot be replayed into a different term slot or a
  different node (JWE header-as-AAD / KMS encryption-context, prior-art §Q6). Defense-in-depth
  over the outer MAC.
- **Unknown `keyringFormatVersion`, `wrapAlgId`, or (at read) `term` => fail closed.**

### A2.3 Key hierarchy (everything term-versioned, rooted in the keyring)

```
signing-key.bin
  -> HKDF(info="configd/keyring-mac/v1")   -> K_keyringMac (authenticates the keyring file)
  -> HKDF(info="configd/keyring-wrap/v1")  -> KEK_wrap (GCM-wraps local roots)
     (KEK unwraps the keyring; the keyring holds the independent random roots)

root[term]  (random 32 B, retained forever)
  -> HKDF(salt=keyId,    info="configd/raft-at-rest-integrity/v3")    -> K_integrity[term]  (HMAC posture MAC key)
  -> HKDF(salt=segmentId, info="configd/raft-at-rest-encryption/dek/v1") -> DEK[term,segmentId] (GCM posture)
```

The keyring exists **whenever authentication is on** (keyed-HMAC OR encrypting). In HMAC-only
(integrity, no confidentiality) mode the roots are used solely to derive `K_integrity[term]`; in
encrypting mode they additionally derive DEKs. This makes the integrity MAC key term-versioned in
*both* postures - the precondition for non-destructive rotation of an integrity-only node. Info
strings are all domain-separated (they already are today,
`LocalDerivedKmsProvider.java:48-50`, `SegmentKeyManager.java:68-70`, `ConfigdServer:1234`); the
integrity info bumps to `v3` because its derivation source changes (signing key -> keyring root).

### A2.4 Boot / bootstrap / fail-closed

- **Boot:** open `raft-keyring` (highest-valid `keyringSeq` slot whose outer MAC verifies under the
  **current** signing key's `K_keyringMac`). Unwrap **every** entry -> seed the `SegmentKeyManager`
  with the full `term -> root` map and `activeTerm` (replacing the hardcoded `term=1`,
  `ConfigdServer:1325`, and `SegmentKeyManager.unsealFrom:121-125` already accepts a provider).
- **First boot (bootstrap):** no keyring + empty data dir => create the keyring with one random
  `root[1]`, `activeTerm=1`, wrapped under the current KEK, dual-slot, fsync.
- **Fail closed:** keyring absent but encrypted/authenticated data present => REFUSE (can't be a
  fresh node; the keyring was lost/deleted). Unknown term at read => REFUSE (already true,
  `SegmentKeyManager.resolveDek:170-176`). Outer MAC fails under the current signing key => REFUSE
  with a diagnostic ("keyring is under a prior KEK - complete the signing-key rotation or restore
  the prior signing key"), never a silent re-derive.

### A2.5 The rotate operations

**(local) term rotation - new key material, same signing key** (admin-triggered, online or at
restart): unwrap all roots under the current KEK; generate a fresh random `root[activeTerm+1]`;
wrap all roots under the same KEK; write a new dual-slot entry with `keyringSeq+1`, `activeTerm+1`;
fsync. Then `SegmentKeyManager.rotateTo(newRoot)` (`:137-146`, already correct: retains old terms,
clears write segments) installs it; new writes stamp the new `keyTerm`, old data reads under its
retained term. **This is the independent encryption-key rotation the local provider "can't do"
today** - enabled because roots are now independent random material, not HKDF(signing key).

**(local) signing-key rotation - crash-atomic handover:** (1) with BOTH the old and new signing
keys present, load the keyring (old `K_keyringMac`/`KEK_wrap`), unwrap all roots, **rewrap** them
under the new signing key's KEK, and write them as a NEW dual-slot entry (`keyringSeq+1`) -
**BEFORE** swapping the signing-key file; fsync. Now both slots are valid (old slot verifies under
the old signing key, new slot under the new). (2) Swap `signing-key.bin` to the new key. (3)
Restart. Boot picks the highest-`keyringSeq` slot that verifies under the *current* (new) signing
key. Roots are unchanged => every DEK/`K_integrity[term]` is unchanged => **all prior data still
decrypts/verifies.** Crash between (1) and (2): old signing key still active => old slot valid =>
boots fine. Crash between (2) and (3): new key active => new slot valid => boots fine. No window
bricks the node.

**(cloud SPI) rotation:** `wrapAlgId=2`; entries are opaque KMS blobs (root wrapped by the external
KMS). Rotation = `KmsProvider.rotateTo` at an admin-triggered call site: generate/register a new
KMS-wrapped root, append the entry, bump `activeTerm`. The outer keyring MAC is still
`K_keyringMac` from the signing key (the signing key always authenticates the keyring *file*; the
provider governs root *confidentiality/custody*). A KMS-unreachable unwrap fails closed at boot
(`SegmentKeyManager.unsealFrom` propagates `KmsUnavailableException:121-125`).

**Retention:** old terms are **never deleted** and never re-encrypted; rewrap is the only op on
local signing-key rotation. Interaction with `requireEncrypted` (`ConfigdServer:1318-1321`):
unchanged - once the legacy HMAC prefix is compacted away the operator drops the legacy read key;
the keyring path is orthogonal (it governs GCM/`K_integrity` terms, not the legacy HMAC read key).

**Audit-log residual across a signing-key rotation (documented, not term-versioned).** The
security-audit chain is keyed by `K_audit = HKDF(signing-key,
info="configd/audit-log-integrity/v1")` (`ConfigdServer.deriveAuditLogKey`), a *raw* keyed HMAC
OUTSIDE the term-versioned `IntegrityEnvelope` - so it is NOT rooted in the keyring and does NOT
rotate by term. Consequently a **signing-key** rotation (now a supported operation via the
rewrap-before-swap handover, §A2.5) changes `K_audit`, and audit records written **before** the
rotation become **un-verifiable** under the new key: their canonical bytes remain fully READABLE, but
their keyed-HMAC tamper-evidence is lost across the rotation boundary (an operator who retains the
prior signing key can still verify them offline). This is a documented residual, not a data-loss
bug; extending keyring term-versioning to the audit chain would require an
`AUDIT_MAGIC` term stamp and a keyring-rooted `K_audit[term]`, out of scope here.

### A2.6 Rotation x anchor interaction

The anchor is authenticated by the **same** IntegrityEnvelope in the **same** posture, carrying its
own `keyTerm` (§A1.2). Therefore:

- **Term rotation:** new anchor writes stamp the new `keyTerm`; **old anchor slots carry the old
  `keyTerm` and verify under the retained `K_integrity[oldTerm]`/`DEK[oldTerm]`**. Rotation does
  NOT invalidate the anchor. (Had the anchor MAC key been the un-versioned signing-key-derived
  `K_integrity` of today, a term bump could not have coexisted with old anchors - this is exactly
  the invalidation avoided by rooting `K_integrity` in the keyring.)
- **Signing-key rotation:** roots (hence every `K_integrity[term]`) are unchanged, so old anchors,
  WAL, snapshots, and state all still verify. Non-destructive across the board.

### A2.7 Non-destruction proof (walkthroughs)

1. **write@term1 -> term-rotate -> write@term2 -> restart -> both decrypt.** term1 records carry
   `keyTerm=1`; keyring retains `root[1]`; on read `resolveDek(1, segmentId)` succeeds. term2 records carry
   `keyTerm=2` under `root[2]`. Both verify.
2. **term-rotate-then-crash mid-rewrite.** Keyring is dual-slot; a torn new slot fails its outer
   MAC/CRC => the prior slot (all old terms) wins => node boots on the pre-rotation keyring, no data
   loss; the operator re-runs the rotate.
3. **signing-key rotation with keyring -> old data still readable.** §A2.5: rewrap-before-swap; roots
   unchanged => all `keyTerm`s resolve. The old documented data-destroying outcome is now
   **impossible by construction**: a mismatched keyring/signing-key **fails closed loudly with a
   diagnostic**, never silently re-derives a wrong root and bricks GCM tags.
4. **unknown term on read** => fail closed (`resolveDek:170-176`).
5. **rotation does not invalidate the anchor** - §A2.6.

---

## 3. The completeness proof - attack -> detection matrix

Scope of "DETECTED": an in-scope adversary (disk write, no key). Mechanisms: `E`=per-record
IntegrityEnvelope MAC/GCM-tag (incl. `magic`, `scopeId`, `keyTerm` binding); `C`=NEW recovery
contiguity/term-monotonicity checks (§A1.4 Step 1); `H`=anchor head gate `W<A => REFUSE` (§A1.4
Step 3); `S`=monotonic `anchorSeq`/`keyringSeq` dual-slot; `X`=external witness (§A1.7, optional).

| # | Attack | Detection | Mechanism |
|---|---|---|---|
| 1 | mid-frame tail truncation | crash-equivalent; FileStorage drops torn frame, then `H` if it dropped an anchored index | FileStorage:271 + H |
| 2 | frame-boundary tail truncation (1..k trailing frames) | **DETECTED** - every survivor is valid, but `W < A` | H |
| 3 | whole-WAL-file rollback to an older valid WAL | **DETECTED** - older WAL has `W < A` | H |
| 4 | state rollback ACROSS a term boundary (term goes backward vs the WAL) | **DETECTED** - no separate state file; the Step-2.5 term-witness gate REFUSES `lastWALTerm > anchor.currentTerm` | merged §A1.1 + Step-2.5 |
| 4b | state rollback WITHIN a term (`votedFor` reset by replaying an older same-term slot) | **CLOSED at N>=3** - the peer-quorum `AnchorWitness` REFUSES a boot/vote below the highest `anchorSeq` a peer quorum witnessed. Strict-boot (default) closes the N=3 boot race; strict-vote (opt-in) closes the grant->witnessed window absolutely. **N>=5 fast-vote residual** needs sustained multi-peer announce loss. N=1 has no witness but cannot split-brain. | X (AnchorWitness, §A1.7) |
| 5 | snapshot+meta rollback (older pair) | **DETECTED** - anchor binds `snapshotIndex/Term`; older pair mismatches the anchor / `W<A` | E + H |
| 6 | snapshot-meta-only tamper | **DETECTED** - meta is removed; boundary now lives authenticated in the anchor | E (anchor) |
| 7 | in-log reorder (index permutation) | **DETECTED** - embedded index != slot position | C |
| 8 | record splice / duplication / gap (same shard, index-level) | **DETECTED** - contiguity + term-monotonicity | C |
| 8b | interior stale-content rollback (index-preserving, term-monotonic: an old authentic frame spliced back over a since-overwritten index) | **DETECTED** - the per-record hash chain: the successor's authenticated `prevHash` no longer matches; C+term-monotonicity alone MISS this | Chain (§2.8) |
| 9 | cross-artifact replay (WAL<->snapshot<->state) | **DETECTED** - distinct `magic` in AAD/MAC | E |
| 10 | **cross-SHARD replay** (shard-1 record -> shard-0) | **DETECTED (NEW)** - reader's mandatory `scopeId==gid` assert refuses (record's authenticated scopeId announces its true shard); in-place scopeId forge invalidates MAC/tag | E + reader assert (§A1.2) |
| 11 | cross-NODE replay (another node's files) | **DETECTED** - node-local root (per-node signing key) => different DEK/`K_integrity` | E, fact 4 |
| 12 | rollback-then-truncate combinations | **DETECTED** - whichever leaves `W<A` or a bad `magic/scope/term/contiguity` trips first | H + C + E |
| 13 | anchor-file-only tamper (forge a slot) | **DETECTED** - slot MAC/tag fails => other slot / REFUSE | E + S |
| 14 | **anchor rollback to a prior valid slot pair** | **PARTIAL** - DETECTED if it lowers a WAL-witnessed term (`lastWALTerm > anchor.currentTerm`, Step-2.5 gate); otherwise **RESIDUAL (a)** unless `X` | Step-2.5 gate / X |
| 15 | whole-datadir clone rollback (WAL+anchor+state moved together to an older consistent point) | **RESIDUAL (a)** unless `X` - term & head stay mutually consistent so the gates don't fire | X only |
| 15b | single-shard wipe->FRESH (delete anchor + truncate WAL to 0 + delete snapshot blob for one shard) | **DETECTED** - the node-anchor's `shardAnchorDigest` binds per-shard `lastDurableIndex`; a wiped shard boots FRESH (its `raft-anchor` absent) with head reset to 0 => digest differs AND a shard is FRESH => node-anchor cross-check REFUSE. Raised from silent-loss to a detected node-anchor rollback (= R-a). The anchor-rolled-to-an-older-valid-slot variant (file NOT deleted) stays matrix-14 residual-(a) -> `X` | node-anchor `shardAnchorDigest` (§2.5) / X |
| 16 | keyring entry strip/swap/add/truncate | **DETECTED** - outer keyring MAC over the whole body | E (§A2.2) |
| 17 | keyring rollback to a prior valid slot | **RESIDUAL (a)** unless `X`; but `activeTerm` can only be *dropped*, and old-term data still reads (no data loss, only a stale active term for new writes) | S + X |
| 17b | topology-descriptor tamper (edit N or epoch) | **DETECTED** - the standalone descriptor is envelope-MAC'd (protocol §2.7) | E |
| 17c | topology-descriptor rollback (swap for older valid) | **DETECTED** - node-anchor binds `{topologyEpoch,shardCount}`; boot cross-check mismatch => REFUSE (v1 static-N has no prior legit topology to roll to anyway) | E + §A1.6 |
| 17d | anchor-state rollback across a vote/term boundary the WAL witnesses | **DETECTED (NEW, F-1)** - `lastWALTerm > anchor.currentTerm` => REFUSE (the retired `max()` rule would have silently accepted it + cleared the vote) | Step-2.5 gate |
| **False-positive rows (MUST NOT trip):** | | | |
| 18 | torn anchor write (crash, not attack) | **NOT REFUSED** - torn slot fails CRC/MAC, the other valid slot (lower seq) wins | S |
| 19 | legitimate Raft conflict truncation | **NOT REFUSED** - INV-ANCHOR-LOWER lowers the anchor first; recovery sees `W>=A` (accept-forward re-adopts uncommitted entries, re-truncated by the leader) | §A1.5 |
| 20 | legitimate compaction (WAL rewrite + snapshot advance) | **NOT REFUSED** - head unchanged; `snapshotIndex` advances in the anchor after `persistSnapshot`; a crash leaves the anchor lagging (accept-forward) | §A1.5 |
| 21 | crash between WAL fsync and anchor fsync | **NOT REFUSED** - `W>A` accept-forward; entries were never committed-and-client-acked | §A1.4 Step 3 |
| 22 | signing-key rotation (legitimate) | **NOT REFUSED** - rewrap-before-swap; roots unchanged => all terms verify | §A2.5 |

False-positive analysis is first-class: rows 18-22 prove no legal crash or Raft transition bricks a
node. The whole design rests on the single asymmetry of row 21 vs row 2/3 - *the anchor may lag
durable data (accept-forward) but must never lead it (refuse)* - which is sound only because
anchor-before-ack (§A1.5) makes `A`, at every node, an upper bound on every index that node durably
contributed to a **committed-and-client-acked** entry.

---

## 4. Residuals (documented, not overclaimed)

- **R-a Anchor / keyring / whole-datadir rollback to a prior legitimately-authenticated state**
  (matrix 14,15,17): out of scope without external monotonic storage; the §A1.7 `AnchorWitness`
  SPI is the only closer. This is the dm-verity/AVB/Vault line. **Narrowed by F-1 (§6):** an
  anchor/state rollback that crosses a term/vote boundary the WAL *witnesses*
  (`lastWALTerm > anchor.currentTerm`) is now DETECTED by the Step-2.5 term-witness gate (matrix
  17d). R-a is thus reduced to rollbacks that do NOT lower a log-witnessed term - a within-term
  index rollback, or a whole-datadir clone where WAL and anchor move together and stay mutually
  consistent (matrix 15).
- **R-b Adversary holding the signing key** forges anything (fate-sharing of the local provider,
  `LocalDerivedKmsProvider.java:36-44`): mitigated only by graduating to an off-host KMS
  (`wrapAlgId=2`), which moves root custody off the node.
- **R-c `VerifyKeyExporter` DER export** (inventory §16, raw X.509, no frame): an operator export,
  not server state - left unanchored, documented.
- **R-d Passive confidentiality of `raft.persistent_state`/anchor fields** in HMAC-only mode: term
  and votedFor are integrity-protected but cleartext (as today). Enabling encryption covers them.
- **R-e Un-anchored audit tail** (§A1.6): audit-head anchoring is periodic (K=64
  records / 1 s), so truncation confined to the last <=K records / <=1 s before a crash is undetected.
  Bounded and documented; strictly better than a fully-undetected audit chain.
- **R-a' Within-term `votedFor` rollback (SAFETY / Election-Safety)** - **CLOSED at N>=3 by the
  peer-quorum `AnchorWitness`** (§A1.7; `docs/design/anchor-witness-peer-quorum-
  2026-07-04.md`). The strict-**boot** gate (default, unconditional peer-majority) closes the boot-reply
  race at N=3; strict-**vote** (opt-in, `-Dconfigd.raft.witnessStrict=true`) closes the grant->witnessed
  window absolutely. **Residual:** at **N>=5 under the default fast-vote**, a rollback can
  still escape only under *sustained* multi-peer announce packet loss (a single drop is defeated by the
  heartbeat-cadence re-announce); it is moot at N<=3, and strict-vote removes it at the cost of
  single-fault leader failover (why it is opt-in). N=1 has no witness but cannot split-brain.
- **R-f Single-shard wipe->FRESH** - **CLOSED by the node-anchor's `shardAnchorDigest`**
  (matrix 15b, §2.5). Reduced to R-a (an attacker must roll the node-anchor to a matching-digest prior
  version, i.e. forge/roll it -> needs the key or the witness).
- **R-g Security-audit chain not term-versioned** (§2.10): `K_audit = HKDF(signing-key)` is the one
  authenticated at-rest key that stayed outside the term-versioning work. A *signing-key* rotation leaves pre-rotation
  audit records readable but not tamper-verifiable across the boundary (records intact; tamper-evidence
  lost for the pre-rotation tail). Term rotation is unaffected. Fixing it (deriving `K_audit` from a
  keyring term) would need a frozen-derivation change.
- **R-h No live/admin rotation trigger** (§2.18): the term- and signing-key-rotation mechanisms are
  built, crash-atomic, and tested but have no `src/main` caller; a rotation today is an out-of-band maintenance
  operation. No format change is needed to wire an online/admin trigger later.

---

## 5. Implementation notes (magics, fsync placement, file writers)

- **New magics:** `ANCHOR_MAGIC 0x52414E43 "RANC"`, `NODE_ANCHOR_MAGIC 0x524E414E "RNAN"`,
  `KEYRING_MAGIC 0x524B5952 "RKYR"` (each doubles as its file's container-header sigil,
  `fileVersion:u8=1`); `STATE_MAGIC "RFST"` is retired (state merged into the anchor, §A1.1);
  `SNAPMETA_MAGIC` is not needed (folded into the anchor). `IntegrityEnvelope.FORMAT_VERSION 2->3` adds
  `scopeId`, and `keyTerm` in the HMAC body (a from-scratch layout change); `keyringFormatVersion=1`;
  integrity info bumps to `.../v3`. The shared envelope's CRC-before-version ordering and the explicit
  `reserved != 0 => throw` check (§2.1) apply to these files too. All get a leading
  magic+version readable before crypto; unknown => fail closed. These magics are disjoint from the
  wire/edge codecs' namespace (`RaftArtifactMagic` vs `FrameCodec`/`EdgeFrameCodec`).
- **fsync placement:** INV-ANCHOR-ACK (leader) sits at the `flushDurable`/`maybeAdvanceCommitIndex`
  barrier (`RaftNode:2223-2237, 2153-2175`); INV-ANCHOR-ACK (follower) sits before the AppendEntries
  ACK (`RaftLog.appendEntries:448-451`); INV-ANCHOR-LOWER sits in
  `truncateFrom`/`appendEntries`/`compact`. §A1.5 has the invariant and its proof; the exact
  scheduling and the group-commit amortization of the second fsync are covered in §6.
- **File writers:** the anchor and node-anchor are dual-slot fixed-offset `pwrite`+`fsync` files (a small
  `AnchorFile` writer), NOT `Storage.put` (tmp+rename). The keyring IS a `Storage.put`-style
  atomic artifact but held in two slots for the signing-key handover (§A2.4).

## 6. Resolved points on formats shared with §2/§5

- **Snapshot-meta is folded into the anchor.** `snapshotIndex` +
  `snapshotTerm` are authenticated fields of the per-shard `ANCHOR_PAYLOAD` (§A1.3). The bare
  `raft-log.snapshot-meta.dat` **ceases to exist** in the frozen layout; `SNAPMETA_MAGIC` is not
  allocated.
- **The topology descriptor is standalone; the anchor binds its epoch.** The versioned
  `TopologyDescriptor {formatVersion, N, topologyEpoch}` stays a
  node-level standalone envelope-authenticated file (§2.7) - it is the authoritative
  `ShardMap.epoch()` source and the fixed-N boot guard, which are routing concerns, not
  per-shard-durability concerns. The node-anchor binds a
  **copy** of `{topologyEpoch, shardCount}` and recovery cross-checks equality (§A1.6), so a
  topology-file **rollback** (not just tamper) is caught. It is envelope-authenticated.
  `topologyEpoch=0` is reserved-illegal; v1=1.
- **Keyring and anchor follow the §0 convention.** Leading `magic || formatVersion`
  readable before the crypto check (via both the container header and the per-slot envelope);
  unknown magic/version/`wrapAlgId`/`term` => **fail loud, closed**; `reserved` MBZ with an explicit
  `!= 0 => throw` (the shared envelope's **CRC-before-version** ordering and the
  `reserved != 0` check, §2.1, apply here too). Keyring per-entry
  **AAD binds `(keyringFormatVersion, term, wrapAlgId, scope=nodeKeyId||"root")`** (§A2.2). **Term 0
  is reserved-illegal**; rotation is **append-a-term, all retained, unknown-term fail-closed**
  (§A2.5). `version==0` is illegal everywhere (envelope `formatVersion=3!=0`).
- **CRC is unified to CRC32C.** Every new file authenticates via
  `IntegrityEnvelope` (CRC32C) per slot; the container header and `recordLen`/pad are not CRC'd (a
  corrupt one just fails the slot => the other slot wins => fail-closed). **No CRC32/zlib anywhere in
  the anchor/node-anchor/keyring** - consistent with the "one CRC family, CRC32C" rule (§0.4).
- **The state-file merge** removes `raft.persistent_state.dat`
  (its `currentTerm`/`votedFor` move into the per-shard anchor); `STATE_MAGIC` (RFST) is retired,
  unused after the merge. The alternative - keeping the state file separate plus giving it its own
  monotonic sequence - would just re-invent a second anchor, so the merge is the better choice.

## 7. Prior-art citations
Vault keyring/term model (§A2): prior-art §Q3, §Q6. bbolt/LMDB dual-meta head anchor (§A1.3):
§Q1d. CT STH authenticated high-water mark shape (§A1): §Q1b. Postgres/etcd fsync ordering &
the anchor-lag asymmetry (§A1.5): §Q2, §1a. dm-verity/AVB/Vault anchor-rollback boundary
(threat model, R-a): §1c. JWE/age/KMS AAD-binds-scope for the wrapped-key envelope (§A2.2): §Q6.
Kafka magic-before-CRC version marker (§A1.2): §Q4.

---

# §5 The version-marker scheme (per-format table, completeness enumeration)

Every claim below is verified against source with a `file:line` cite. Clean break: nothing had
shipped when this was designed, so there is **no migration/compat baggage** - the freeze
redefines any pre-existing layout, and deletes un-versioned legacy acceptance paths rather than
preserving them.

Where this section's assumptions touch the anchor file, wrapped-key keyring, or topology
descriptor (owned in §4), each states the assumption **and** the fallback if it turns out wrong.

---

## §0. The one convention (normative)

Every artifact that can exist **on disk or on the wire** is exactly one of three kinds. No fourth
kind is permitted; a blank version story is a design bug (this is the completeness invariant in §3).

1. **Self-versioned.** A leading, fixed **`[magic:4][version]`** that self-identifies the format,
   readable structurally with no key. `version` is `u8` or `u16` (per artifact, see table). Used
   where the artifact is the outermost thing on its medium (a file's first bytes; a frame's first
   bytes after the length prefix).

2. **Carrier-versioned + assert.** The artifact **never exists outside a versioned carrier** (it is
   always nested inside a self-versioned envelope or frame). It carries **no** version bytes of its
   own; instead the design **documents the carrier** and the codec **asserts** the invariant that
   makes decoding safe (an unknown discriminant/type byte => throw). The carrier's version pins the
   inner grammar. This is the correct - not the lazy - choice when adding a redundant inner version
   byte would bloat a hot path (e.g. every `CommandCodec` command in every WAL entry) for no
   decode-time benefit, **provided the carrier argument holds for *every* carrier the bytes ride in.**

3. **Documented-export.** An operator-facing artifact in a **self-describing industry standard**
   (X.509 / PKCS#8 DER) consumed by standard tooling (`openssl`), never re-parsed by Configd as
   server state. Its "version" is the standard's own structure. Documented, not wrapped.

### 0.1 Placement - magic and version, then the integrity check

- **Leading `magic || version`, at a fixed offset, readable before the version-*dependent* check.**
  Kafka moved `magic` *ahead* of the CRC in message-format v2 precisely so a reader can pick the
  format before it knows how to verify (prior-art §Q4).
- **The version-*independent* CRC runs first for error attribution.** Configd's CRC is a fixed
  trailer with a fixed algorithm (see §0.4) - it does **not** need the version to be located or
  computed. So the correct ordering is: **CRC (needs no version) -> read version from CRC-validated
  bytes -> version-dependent MAC/GCM/grammar.** This satisfies both principles at once: a bit-flip
  surfaces as *corruption*, not as a misleading "unsupported version," yet the version is still read
  before any check that actually depends on it.
  - `FrameCodec` already does this: CRC32C at `FrameCodec.java:285-296`, **then** `version` at
    `:300-303`. This is the reference discipline.
  - **`IntegrityEnvelope` is the one inconsistency to fix:** it parses `formatVersion` at
    `IntegrityEnvelope.java:299-305` **before** the CRC32C at `:341-353`, so a bit-flip in the
    2-byte version field reports "unsupported formatVersion" instead of "CRC mismatch." **Frozen
    ruling: reorder to CRC-first** (compute CRC over `[0, len-4)`, compare, *then* read
    `formatVersion`/`algId`). Behavior for a valid frame is unchanged; `rolledFormatVersionThrows`
    still throws (a good-CRC + wrong-version still fails on version); only a *corrupt* header now
    reports corruption. Golden vectors unaffected (they carry a valid CRC).

### 0.2 Reserved-value discipline (uniform across all self-versioned formats)

- **`magic == 0` is illegal.** (A zero-filled/torn leading word can never be a valid artifact.)
- **`version == 0` is illegal / reserved.** No format uses 0 today (`IntegrityEnvelope`=2,
  `SigningKeyStore`=1, edge=1/2/3, raft wire=0x02) - freeze that: 0 is "unset/torn," rejected. A
  reader that sees version 0 fails closed.
- **`version == MAX` (`0xFF` for `u8`, `0xFFFF` for `u16`) is reserved as the future "extended
  version" escape.** Do **not** allocate it in v1. It is the pre-agreed door for a post-freeze
  format break (a v1 reader rejects it - fail closed - but a future reader knows the slot's meaning).
- **MBZ reserved fields must be 0 on write; a non-zero value fails closed on read.** Where the field
  is MAC/AAD-covered, a non-zero value is *either* tamper *or* a newer writer - both must fail
  closed, so "covered by the MAC" is **not** a substitute for an explicit `== 0` check (see
  `IntegrityEnvelope.reserved` and `FrameCodec.epoch`, §2).

### 0.3 Unknown-version / unknown-magic: fail loud and closed

A reader at frozen-v1 **never** best-effort-parses. The four failure inputs and their required
behavior (tested per §4):

| Input | Required behavior |
|---|---|
| **Unknown magic** (full-length buffer, wrong sigil) | **Throw** under any authenticated posture. (`IntegrityEnvelope:275-280` already does this; a *sub-floor* buffer `< HEADER+CRC` is "structurally absent" => `null`, which is correct and kept.) |
| **Known magic + higher version** | **Throw** - refuse; never parse a newer format with an older grammar. |
| **Known magic + version 0** | **Throw** - reserved-illegal (§0.2). |
| **Structurally-valid-but-legacy (un-versioned) form** | **Throw / reject.** Clean break: the legacy acceptance paths are **deleted** (see §0.5). |

### 0.4 CRC family: unify to CRC32C

Before this design there are **two** CRC families: `CRC32` (zlib, `java.util.zip.CRC32`) in the FileStorage WAL
frame *only* (`FileStorage.frame()` / `readLog()`), and `CRC32C` (Castagnoli) everywhere else
(`IntegrityEnvelope`, `FrameCodec`, `EdgeFrameCodec`). This is a footgun for anyone writing
recovery/verification tooling (two checksum routines over the same file - the outer frame vs the
inner envelope).

**Ruling: unify to CRC32C in the clean break.** The FileStorage container CRC is a *corruption /
torn-tail* detector only - authentication is the inner `IntegrityEnvelope` (CRC32C + MAC/GCM), so
this is not a security change, only a consistency one. Cost: 2 sites (`frame()`, `readLog()`); it
invalidates any existing on-disk WAL (fine - nothing shipped) and there is **no** WAL golden fixture
to regenerate (`WalWireCompatStubTest` is a `@Disabled` stub with no vector). Document the invariant:
**"one CRC family, CRC32C/Castagnoli, system-wide; a container CRC is corruption-only, authentication
is always the inner envelope."** (Alternative considered - *bless-and-document* two families - is
rejected: freeze-forever is the one moment to remove the discrepancy for free, and CRC32C has the
same HW acceleration, so there is no perf reason to keep zlib CRC32.)

### 0.5 Clean-break deletions (kill un-versioned legacy acceptance paths)

Nothing shipped => no legacy artifact exists => the readers that tolerate un-versioned forms are dead
weight that only *weaken* the frozen format. **Delete them:**

- **WAL legacy raw-record fallback.** `RaftLog.deserializeEntry` (`:647-660`) falls back to
  `body = raw` when `unwrapOrNull` returns `null` (a pre-envelope record). Under freeze **every** WAL
  record MUST be enveloped; a non-enveloped record => fail closed. (Keep `unwrapOrNull`'s sub-floor
  `null` = "absent/torn-tail" for the empty/first-boot file case; that is torn-tail handling, not a
  legacy-format path.)
- **Snapshot trailer legacy forms (a) and (c).** `ConfigStateMachine.decodeTrailer` (`:482-527`)
  accepts three forms: (a) empty=legacy, (b) magic-TLV canonical, (c) bare 8-byte epoch. The writer
  emits **only** (b) (`SNAPSHOT_TRAILER_MAGIC` + len + epoch, unconditionally, per inventory §5).
  **Freeze to accept only (b);** (a) and (c) => malformed => throw. (Confirmed no path
  emits a trailer-less or raw-8-byte snapshot - `serialize()` does not.)

---

## §1. Version 0-illegal / magic-0-illegal summary (the freeze's reserved values)

| Field | Frozen rule |
|---|---|
| any `magic` | `0x00000000` illegal |
| `u8` version | `0x00` illegal; `0xFF` reserved-escape (unallocated) |
| `u16` version | `0x0000` illegal; `0xFFFF` reserved-escape (unallocated) |
| MBZ reserved | `0` on write; non-zero => fail closed on read |
| topology epoch | `0` reserved = "unset/pre-epoch" => reject; v1 uses `1` (see §2.9) |

---

## §2. Per-format decision table

Legend for **Kind**: `SV`=self-versioned, `CV`=carrier-versioned+assert, `DE`=documented-export.
"Δ" marks a change from the pre-existing format.

### 2.1 IntegrityEnvelope - `configd-common/.../IntegrityEnvelope.java` - **SV, adequate (best in repo)**
Before this design: `[magic:4][formatVersion:2 = 2][algId:1][reserved:1]` header (`:76-79`), CRC32C trailer,
MAC/AAD covers the whole header incl. reserved. Version gate throws on `formatVersion != 2`
(`:299-305`); unknown `algId` throws (`:330`); downgrade `algId=NONE`-under-key throws (`:322`).
- **Decision: confirm adequate; freeze `formatVersion = 2`, `HEADER_SIZE = 8`.** This is the model
  the whole system inherits.
- **Δ (from §0.1):** reorder to **CRC-before-version** for error attribution.
- **Δ (from §0.2):** the **`reserved` byte is the future escape** - freeze it MBZ and add an explicit
  **`reserved != 0 => throw`** check (today it is MAC-covered but never validated `== 0`, `:307`), so
  a future writer that repurposes it can't be silently mis-parsed by a v1 reader. This preserves the
  reserved byte as a genuine forward-compat sub-version/feature-flag slot inside an otherwise
  version-strict format.
- **Unknown-version behavior:** throw (already). **Test obligation:** existing
  `rolledFormatVersionThrows` (`IntegrityEnvelopeTest:105`) + **new** `reservedNonZeroThrows`,
  `corruptHeaderReportsCrcNotVersion`.

### 2.2 FileStorage WAL frame + WAL file - `configd-common/.../FileStorage.java` - **container: SV; records: CV**
Before this design: per-entry frame `[len:4][data:N][CRC32-zlib:4]` (`frame():213`); the file itself has **no
header** (frames start at offset 0). Torn-tail discard at `readLog:271`.
- **Decision - records:** the `data` is always a self-versioned `IntegrityEnvelope` (Raft) or a
  self-versioned audit record (§2.8), so **records are CV** by their envelope/record magic.
- **Δ Decision - the container:** add a **one-time WAL file header** so the container itself
  self-identifies rather than relying only on its records:
  `[WAL_FILE_MAGIC:4][fileVersion:u8 = 1][flags:u8 = 0][reserved:u16 = 0]` (8 B) at offset 0, written
  as the leading bytes on first append and fsync'd before the first frame is acked. `readLog`: file
  `< 8 B` => empty/fresh; else validate header **first** (bad magic/version => fail closed), then the
  existing frame scan + torn-tail discard runs unchanged. This closes flag-5's "WAL container is
  unversioned" row and is the right call under freeze-forever (the framing never changes again, so
  the header's job is foreign-file rejection + a named forward-compat `flags` slot). Applies
  identically to `raft-log.wal`, the transient `raft-log.tmp.wal`, and `security-audit.wal`.
- **Δ CRC:** `CRC32` -> `CRC32C` (§0.4).
- **Unknown-version behavior:** bad file-header magic/version => throw (refuse to load the WAL). A
  torn *record* tail is discarded (crash-consistency, unchanged); a complete-but-tampered record
  fails the inner envelope (unchanged). **Test obligation:** `walFileHeaderBadMagicRejected`,
  `walFileHeaderHigherVersionRejected`, `emptyWalFileIsFresh`, `headerOnlyFileIsFresh`.

### 2.3 RaftLog inner WAL record - `configd-consensus-core/.../RaftLog.java` - **CV**
Before this design, the inner payload was `[index:8][term:8][command:N]` then `integrity.wrap(WALE_MAGIC, ...)`
(`serializeEntry:627-633`).
- **Decision: CV** - carrier is the `WALE_MAGIC` `IntegrityEnvelope` (`formatVersion=2`). No inner
  version byte. **Assert:** the envelope unwraps and MAC-verifies before these bytes are trusted.
- **Δ:** delete the legacy raw-record fallback (§0.5).
- **Test obligation:** `nonEnvelopedWalRecordRejectedUnderKey` (the deleted-fallback negative).

### 2.4 raft.persistent_state - `configd-consensus-core/.../DurableRaftState.java` - **CV**
`[currentTerm:8][votedFor:4]` then `wrap(STATE_MAGIC, ...)` (`:157-163`). **CV** by `STATE_MAGIC`
envelope v2 - already covered by `IntegrityEnvelopeTest`. No change. **Test obligation (transitive):**
`forgedVotedForRefused`.

### 2.5 Snapshot blob (Raft) - `RaftLog.serializeSnapshot` + `ConfigStateMachine` - **CV + inner trailer CV**
Blob `[lastIncludedIndex:8][lastIncludedTerm:8][dataLen:4][data][configLen:4(-1=null)][config]`
then `wrap(SNAP_MAGIC, ...)`. Inner `data` = `ConfigStateMachine.snapshot()`, which ends with a
**magic-TLV trailer** `[SNAPSHOT_TRAILER_MAGIC=0xC0FD7A11:4][trailerLen:4][signingEpoch:8]`
(`:559`, `:487`).
- **Decision: CV** for the blob (carrier `SNAP_MAGIC` envelope v2). The inner trailer already has its
  own magic-TLV (a mini self-versioned sub-format keyed by magic, with `trailerLen` = the
  forward-compat length escape - an *unknown tail beyond the 8-byte epoch is tolerated*, `:507-510`,
  which is the correct TLV forward-compat behavior and is kept).
- **Δ:** kill legacy trailer forms (a)/(c) (§0.5) - accept only the magic-TLV.
- **Unknown-version behavior:** unknown trailer magic / out-of-range `trailerLen` => throw (`:490-499`,
  kept). **Test obligation:** `snapshotTrailerLegacyEmptyRejected`, `snapshotTrailerRaw8Rejected`,
  `snapshotTrailerUnknownTailTolerated` (keep the TLV forward-compat path green).

### 2.6 raft-log.snapshot-meta - `RaftLog.compact` - **bare 16 B -> CV or folded**
Before this design: **bare 16 B `[snapshotIndex:8][snapshotTerm:8]`, NO magic / NO version / NO CRC / NO MAC**
via `storage.put` (`:584-587`). This is the single worst-protected persistent artifact and it names
the durable-prefix boundary, so it is security-critical.
- **Preferred: the authenticated anchor file** subsumes the
  snapshot index/term (an anchor that MACs `(index/count + hash-over-head)` naturally carries the
  snapshot boundary), so snapshot-meta ceases to exist as a standalone file; its two
  fields live in the anchor (self-versioned by the anchor's own `magic||version`). This is what
  shipped (§2.4).
- **Fallback (if it stayed standalone):** wrap it in `IntegrityEnvelope` under a **new
  `SNAPMETA_MAGIC`** -> instantly SV/CV with `formatVersion=2`, CRC32C, and (under a key) a MAC. Never
  leave it bare.
- Either way it is non-blank in §3. Tests: `snapshotMetaTamperedRefused`.

### 2.7 raft-shard-count.meta -> **versioned topology descriptor** - `ConfigdServer.enforceFixedShardCount` - **SV**
Before this design: **plain UTF-8 decimal `N`** (`SHARD_COUNT_MARKER`, `:125`, write `:1521-1528`), temp+fsync+
atomic-rename; a different `N` on restart => refuse to start (`:1507-1514`). No magic/version.
- **Decision: replace with a single authenticated, versioned topology descriptor** that serves BOTH
  the fixed-N deploy guard AND the cursor epoch source:
  ```
  TopologyDescriptor  (wrapped in IntegrityEnvelope under a new TOPO_MAGIC)
    [formatVersion:u16 = 1]      # inner, redundant-with-envelope but explicit for operators
    [shardCount N:u32]
    [topologyEpoch:u64]          # the epoch authority (§2.9); v1 = 1
    [reserved:u32 = 0]
  ```
  Envelope gives magic + CRC32C + (under a key) MAC - so the deploy guard is now tamper-evident, not
  a plaintext integer an attacker can edit to bypass the reshard refusal. If the anchor already
  binds `N`/epoch, this can fold into that file instead of a second descriptor - what matters is
  the interface contract below, not the file count.
- **Interface contract:** `ShardMap.epoch()` returns the descriptor's
  `topologyEpoch`; `StaticShardMap` is wired to return it instead of the hardcoded `0`
  (`StaticShardMap.java:86-88`). The value is **cluster-wide-consistent** (all nodes read the same
  deploy-time descriptor); v1 initializes it to `1` at first boot and never bumps (static-N); a v2
  dynamic reshard bumps it monotonically. Reserved `0` = pre-epoch => illegal.
- **Unknown-version behavior:** bad `TOPO_MAGIC`/`formatVersion` => refuse to start (same class as the
  prior corrupt-marker refusal `:1502-1505`). Tests: `topologyDescriptorTampered
  RefusesStart`, `topologyEpochZeroRejected`, `reshardNChangeStillRefused`.

### 2.8 security-audit.wal record - `configd-control-plane-api/.../AuditLog.java` - **SV record**
Before this design: the FileStorage frame contains `[canonicalLen:8][canonical][prevHash:32][recordHash:32]`
(`encode:381-389`); **no magic, no version**; `recordHash = HMAC/ SHA-256(prevHash||canonical)`
(`chainHash:347`).
- **Decision: prepend a self-versioned record header** inside the FileStorage frame:
  `[AUDIT_MAGIC:4][recordVersion:u8 = 1][canonicalLen:8][canonical][prevHash:32][recordHash:32]`.
- **Bind the version into the tamper-evident chain:** change the chain input to
  `HMAC/SHA-256(AUDIT_MAGIC || recordVersion || prevHash || canonical)` so a version-downgrade of a
  record is detectable, not just a payload edit. (The file also gets the WAL file header from §2.2.)
- **Unknown-version behavior:** bad `AUDIT_MAGIC`/`recordVersion` => throw on chain verification (the
  audit reader already refuses a broken chain). Tests: `auditRecordBadMagicRejected`,
  `auditVersionIsChainBound` (flip version, expect MAC mismatch).

### 2.9 WatchCursor + topology epoch - `WatchCursor.java` + `EdgeFrameCodec` - **CV + epoch binding**
Before this design: a vector `[count:u32]([gid:u32][S:u64])*` via `encodeCursorInto` (`EdgeFrameCodec.java:427`)
/ `decodeCursor` (`:853`); **no version, no epoch**; server checks gid membership out-of-band only.
The cursor rides inside **WATCH_CREATE** (`:443`), **WATCH_PROGRESS** (`:482`), and **WATCH_CANCELED
oldest** (`:491`) payloads - all edge wire **0x02+** (watch types are 0x02-only,
`EdgeFrameCodec:642`). The `ShardMap` interface **already specifies** the epoch-on-routing-envelope
pattern (TiKV RegionEpoch): "*carry it so a stale router ... is told 'wrong epoch, re-resolve' rather
than mis-committing ... the v1 StaticShardMap never bumps it (returns 0 forever)*" (`ShardMap.java`
docstring). This design realizes that dormant seam on the edge wire.
- **Decision - the cursor's *format* is CV** by the enclosing edge frame version (0x02/0x03). No
  per-cursor version byte is needed - the frame version pins the cursor grammar, and adding a
  redundant `cursorVersion` byte buys nothing (the cursor never travels outside a versioned frame).
- **Decision - bind the topology epoch (the load-bearing new field).** Prepend the epoch to the
  cursor wire:
  ```
  frozen cursor := [topologyEpoch:u64][count:u32]([gid:u32][S:u64])*count
  ```
  Source of `topologyEpoch` = the §2.7 topology descriptor via `ShardMap.epoch()` (v1 = `1`).
- **Uniform "every resume token binds the epoch" rule (recommended).** The **SUBSCRIBE** scalar
  resume `[...][resume:8][failoverResume:8][...]` (`encodeSubscribeInto:298-309`) is the legacy
  edge-hydration path and is *also* topology-sensitive (a redeploy at a different `N` invalidates a
  scalar seq just as it does a vector). **Prepend `topologyEpoch:u64` before the resume fields** in
  SUBSCRIBE too, so the rule is uniform across every resume/cursor field. (The minimal alternative,
  watches-only, was rejected in favor of the uniform rule: one invariant, one test class, no
  "which frames are epoch-checked?" ambiguity.)
- **Server reject on mismatch (etcd `ErrCompacted` model).** A cursor whose `topologyEpoch != current`
  is refused with a **new dedicated `ErrorCode.STALE_TOPOLOGY(12)`** (the enum is closed and
  golden-pinned 1..11, `ErrorCode.java`; clean break extends it by one). Distinct from
  `GAP_UNRECOVERABLE(6)` ("data fell off retention" - resume from an *earlier* position) - `STALE_
  TOPOLOGY` means "the cursor's whole topology generation is invalid, **re-hydrate from scratch**."
  Reject carrier: **WATCH_CANCELED** with `code=STALE_TOPOLOGY` for a watch; **ERROR_CLOSE** with the
  same code for a SUBSCRIBE. Client's only correct recovery: drop the cursor, re-issue from
  `WatchCursor.fromNow()` / a fresh SUBSCRIBE (full re-hydrate).
- **Does this force edge wire 0x04?** **No.** Clean break lets us redefine the 0x02/0x03 payloads in
  place (watches only ever existed on 0x02+, nothing shipped). Keep 0x02 (watch-capable) and 0x03
  (filtered) as-frozen and regenerate the golden vectors, which pin the exact cursor/subscribe
  bytes:
  `configd-distribution-service/src/test/java/io/configd/distribution/wire/EdgeFrameGoldenBytes.java`,
  `EdgeFrameGoldenBytesGenerator.java`, `EdgeFrameCodecGoldenFixtureTest.java`,
  `EdgeFrameCodecV2GoldenFixtureTest.java`, `EdgeFrameCodecV3GoldenFixtureTest.java`. (The raft-wire
  `GoldenFixtures.java` / `WireCompatGoldenBytesTest.java` are **not** touched - FrameCodec is frozen
  as-is, §2.11.)
- **Unknown-version / mismatch behavior:** epoch `0` => FRAME_CORRUPT (reserved-illegal, §0.2);
  epoch != current => `STALE_TOPOLOGY`. Tests: `staleEpochCursorRejectedWithReHydrate`,
  `epochZeroCursorIsFrameCorrupt`, `matchingEpochResumes`, `subscribeCarriesEpoch`, and a
  resharding negative (`N=a` cursor replayed at `N=b` => STALE_TOPOLOGY).

### 2.10 CommandCodec - `configd-config-store/.../CommandCodec.java` - **CV (carrier holds for all 3 carriers)**
Before this design: `[type:1]...` (`0x01`=PUT / `0x02`=DELETE / `0x03`=BATCH), empty = Noop; unknown type =>
throw (`:17-20`). No format version.
- **Decision: CV + assert.** The type byte is a *discriminant*, not a version. These bytes appear in
  **three** carriers, and the carrier argument holds for **all** of them: (1) WAL entries - inside
  the `WALE_MAGIC` envelope (v2); (2) NOTIFY deltas - inside the edge frame (version byte); (3)
  snapshot values - inside the `SNAP_MAGIC` envelope / the edge snapshot body which itself rides an
  edge frame. Every carrier is self-versioned, so no inner version byte is warranted (it would bloat
  every command in every log entry for zero decode benefit). Documented as "never standalone;
  versioned by carrier," with the unknown-type throw as the assert.
- **Unknown behavior:** unknown type byte => throw (kept). Tests: `commandUnknownType
  Throws` (exists) plus a doc-comment stating the carrier list.

### 2.11 Edge/raft wire that is already versioned - **SV, confirmed; one change on the dormant epoch**
- **Edge frame** `EdgeFrameCodec` - `[len:4][version:1 = 01/02/03][type:1][payload][CRC32C:4]`
  (`:1111`, decode `:578`), first-frame version pin (`:626`). **SV, confirmed.** Per-type payloads =
  **CV** by this version byte (assert: watch types 0x0A-0x12 legal only on 0x02, `:642`, kept).
- **Raft transport frame** `FrameCodec` - `[len:4][ver:1 = 0x02][type:1][gid:4][term:8][epoch:8]
  [payload][CRC32C:4]`; CRC-before-version (`:285-303`, the reference discipline). **SV, confirmed;
  freeze `WIRE_VERSION=0x02`.**
  - **The dormant `epoch` slot.** Offset 18, 8 B, `RESERVED_EPOCH=0`, previously
    **decode-but-ignore** (`:309`). This is the raft-wire twin of the topology epoch (same
    RegionEpoch concept, consensus layer). **Freeze it MBZ and upgrade decode-but-ignore ->
    reject-if-nonzero** (fail closed), per §0.2: in v1 no legitimate peer sets it, so a non-zero
    value is corruption (already CRC-caught) or a newer peer we cannot safely talk to. Golden vectors
    carry `epoch=0` so they stay valid; this only *adds* a negative test. Tests:
    `nonZeroReservedEpochRejected`.
- **RaftMessageCodec payloads** (`configd-server/.../RaftMessageCodec.java`) incl. chunked
  InstallSnapshot fields - **CV** by the FrameCodec version. No change (caps already bound sizes).
- **EdgeSnapshotCodec chunk body** (`EdgeSnapshotCodec.java:81`) - lead `u64` = `snapshot.version()`
  = **DATA seq, NOT a format version.** **CV** by the enclosing edge frame version. The
  data-seq-not-format-version distinction is documented explicitly to avoid confusion, and the
  field-bound assert is kept. Tests: doc-comment + `edgeSnapshotBodyIsCarrierVersioned`.

### 2.12 signing-key.bin - `configd-config-store/.../SigningKeyStore.java` - **SV, adequate; non-format fixes only**
`[magic 0xC0DF_51C5:4][version:2 = 1][keyId:16][privLen:4][privDER][pubLen:4][pubDER]` (`:40-41`,
`:118-127`); load validates magic+version strict (`:79-85`). **SV, confirmed adequate - freeze
`version = 1`.** Non-format fixes (not format changes):
- **Durability:** `Files.write(CREATE_NEW)` (`:134`) has **no fsync and no atomic-rename** - a crash
  during first generation can leave a torn key file; fix to temp+fsync+atomic-rename+dir-fsync
  (mirror `FileStorage.put`).
- A docstring said `0xC0DF51G5` (invalid hex `G`); the code is `0xC0DF_51C5` - doc fix.
- `writeForTest` (`:169`) computed `PosixFilePermissions.fromString("rw-------")` but
  **never applied it** (a latent no-op, test-only) - deleted or applied.

### 2.13 WrappedKey persisted keyring - `configd-common/.../kms/*` - **SV-from-birth, contract only**
Before this design: **nothing persisted** for the `local` provider - the `WrappedKey` is a 0-byte descriptor
regenerated each boot (`LocalDerivedKmsProvider`, inventory §10). The keyring *file* is designed
here for a future persisting provider, synthesizing prior-art §Q6 (Vault keyring / JWE / age):
```
Keyring file (SV, from birth):
  [KEYRING_MAGIC:4][formatVersion:u16 = 1][keyCount:u32]
  per key:  [term:u32][wrapAlgId:u8][wrapNonce:12][wrappedDekLen:u32][wrappedDek][gcmTag:16]
  ...wrapped under the root/unseal key; the WHOLE file MAY additionally ride an IntegrityEnvelope.
```
**Contract clauses the file MUST satisfy:**
- Leading `magic || formatVersion`, readable before the crypto check; unknown magic/formatVersion/
  `wrapAlgId` => **fail closed**.
- Each wrapped-DEK's **AAD binds `(formatVersion, term, wrapAlgId, scope/purpose)`** so a wrapped key
  lifted from one slot cannot be replayed into another (JWE header-as-AAD / KMS encryption-context /
  age header-MAC).
- **Rotation = append a term; every historical term retained for decrypt; unknown term => fail
  closed** (already the `SegmentKeyManager.resolveDek` contract, `:165`). Term `0` is reserved-illegal
  (mirrors the hardcoded-`term=1` boot value, inventory §13). This is the marker convention;
  the rotation lifecycle itself is in §4 A2.
- **Tests:** `keyringBadMagicFailClosed`, `keyringUnknownWrapAlgFailClosed`,
  `wrappedDekReplayIntoOtherSlotFailsAAD`, `unknownTermFailClosed`.

### 2.14 VerifyKeyExporter output - `configd-config-store/.../VerifyKeyExporter` - **DE**
Raw X.509 **SubjectPublicKeyInfo DER** written to an operator path (inventory §16). **Decision:
documented-export** - DER is self-describing (ASN.1 structure + algorithm OID), consumed by standard
tooling; no custom magic/version. Its "version" is the X.509 standard's own. Document it as such;
never re-parse as server state. No tests needed beyond "round-trips through `openssl pkey`."

### 2.15 The anchor file - **SV-from-birth, contract only**
The truncation/rollback anchor (§4 A1) that MACs `(index/count + hash-over-head)`. **Contract:** SV from
birth - `[ANCHOR_MAGIC:4][formatVersion:u16 = 1]...`, magic+version before the MAC check, unknown =>
fail closed, MBZ reserved per §0.2. If it subsumes snapshot-meta and/or the
topology descriptor, those fields inherit the anchor's version story. Dual-slot
A/B sequence numbers (prior-art §Q1d) are the structural choice; the **marker
convention** above is the only thing mandated here.

---

## §3. Completeness enumeration - every artifact x its version story

Checklist basis: inventory §15/16 file sweep + the wire codecs. **No row may be blank.** Kinds:
`SV` = self-versioned, `CV` = carrier-versioned+assert, `DE` = documented-export.

### On disk (N=1 under `dataDir/`; N>1 repeats the per-shard rows under `dataDir/shard-<gid>/`)

| # | Artifact | Kind | Version story | Δ? |
|---|---|---|---|---|
| 1 | `signing-key.bin` (node-level) | SV | magic `0xC0DF_51C5` + `version=1` | fixes only (§2.12) |
| 2 | topology descriptor (was `raft-shard-count.meta`, node-level) | SV | `TOPO_MAGIC` envelope + inner `formatVersion=1`; holds `N`+`topologyEpoch` | Δ (§2.7) |
| 3 | `raft.persistent_state.dat` (per-shard) | CV | `STATE_MAGIC` envelope v2 | - |
| 4 | `raft-log.wal` file (per-shard) | SV | `WAL_FILE_MAGIC` + `fileVersion=1` header | Δ (§2.2) |
| 4a | `raft-log.wal` records | CV | `WALE_MAGIC` envelope v2 (legacy-raw path deleted) | Δ (§0.5) |
| 5 | `raft-log.tmp.wal` (transient, per-shard) | SV | identical to #4/#4a (compaction rewrite) | Δ (§2.2) |
| 6 | `raft-log.snapshot.dat` (per-shard) | CV | `SNAP_MAGIC` envelope v2 | - |
| 6a | snapshot inner trailer | CV | magic-TLV `0xC0FD7A11` (forms a/c deleted) | Δ (§0.5) |
| 7 | `raft-log.snapshot-meta.dat` (per-shard) | SV/CV | folded into anchor **or** `SNAPMETA_MAGIC` envelope | Δ (§2.6) |
| 8 | `security-audit.wal` file (node-level, if audit on) | SV | `WAL_FILE_MAGIC` header | Δ (§2.2) |
| 8a | `security-audit.wal` records | SV | `AUDIT_MAGIC` + `recordVersion=1`, chain-bound | Δ (§2.8) |
| 9 | keyring file (node-level, **only if a persisting KMS provider ships**) | SV | `KEYRING_MAGIC` + `formatVersion=1` | new-from-birth |
| 10 | anchor file (node-level) | SV | `ANCHOR_MAGIC` + `formatVersion=1` | new-from-birth |
| 11 | VerifyKeyExporter output (operator path) | DE | X.509 SubjectPublicKeyInfo DER | - |
| - | KMS `RootKey`/`WrappedKey` (`local`) | n/a | **in-memory only**, 0-byte descriptor, never on disk | - |

### On the wire

| # | Artifact | Kind | Version story | Δ? |
|---|---|---|---|---|
| 12 | Raft transport frame (`FrameCodec`) | SV | `WIRE_VERSION=0x02` byte, CRC-before-version | Δ epoch reject (§2.11) |
| 12a | dormant frame `epoch` (offset 18) | - | MBZ reserved; reject-if-nonzero | Δ (§2.11) |
| 13 | `RaftMessageCodec` payloads (+ chunked InstallSnapshot) | CV | by FrameCodec version | - |
| 14 | Edge frame (`EdgeFrameCodec`) | SV | version byte `01/02/03`, first-frame pin | - |
| 15 | Edge per-type payloads | CV | by edge frame version (watch-types-on-0x02 assert) | - |
| 16 | WatchCursor (WATCH_CREATE/PROGRESS/CANCELED) | CV | by edge frame version **+ `topologyEpoch` binding** | Δ (§2.9) |
| 17 | SUBSCRIBE scalar resume | CV | by edge frame version **+ `topologyEpoch` prepend** | Δ (§2.9) |
| 18 | `CommandCodec` bytes (WAL / NOTIFY / snapshot value) | CV | by each of 3 carriers (all self-versioned) | doc only (§2.10) |
| 19 | `EdgeSnapshotCodec` chunk body | CV | by edge frame version (lead u64 = data-seq) | doc only (§2.11) |
| 20 | NOTIFY delta / `ConfigDelta.signingPayload` | CV | by edge frame version | - |
| 21 | `IntegrityEnvelope` (the shared carrier itself) | SV | `formatVersion=2`, best-in-repo | Δ CRC-order + reserved check (§2.1) |

Every row is `SV`, `CV`, or `DE`. No blank. Transient (#5) and per-shard N>1 layout are enumerated.

---

## §4. Fail-closed semantics + per-format test coverage

**Uniform reader contract** (every SV format; every CV format via its carrier):

| Input | Behavior | Where tested |
|---|---|---|
| **Unknown magic** | Throw (authenticated posture); sub-floor buffer => absent/`null` only for the torn-tail/first-boot case | `IntegrityEnvelopeTest` (magic mismatch), `walFileHeaderBadMagicRejected`, `auditRecordBadMagicRejected`, `topologyDescriptorTamperedRefusesStart` |
| **Known magic + higher version** | Throw - never parse newer with older grammar | `rolledFormatVersionThrows` (envelope), `walFileHeaderHigherVersionRejected`, edge `wrongVersionWithValidCrcIsRejectedAsBadVersion` (kept) |
| **Known magic + version 0** | Throw - reserved-illegal | `versionZeroRejected` (per SV format), `epochZeroCursorIsFrameCorrupt`, `topologyEpochZeroRejected` |
| **Structurally-valid legacy (un-versioned) form** | Reject - legacy paths deleted (§0.5) | `nonEnvelopedWalRecordRejectedUnderKey`, `snapshotTrailerLegacyEmptyRejected`, `snapshotTrailerRaw8Rejected` |
| **Corrupt header (bit-flip)** | Report **corruption**, not version-mismatch (CRC-before-version) | `corruptHeaderReportsCrcNotVersion` (envelope), `FrameCodecFuzzTest` (kept) |
| **MBZ reserved != 0** | Throw (fail closed) | `reservedNonZeroThrows` (envelope), `nonZeroReservedEpochRejected` (frame) |
| **Stale topology epoch** | `STALE_TOPOLOGY(12)` -> client full re-hydrate (etcd `ErrCompacted` model) | `staleEpochCursorRejectedWithReHydrate`, `reshardEpochChangeForcesReHydrate`, `subscribeCarriesEpoch` |

**Golden-fixture files regenerated** (cursor/subscribe wire change only):
`EdgeFrameGoldenBytes.java`, `EdgeFrameGoldenBytesGenerator.java`, `EdgeFrameCodecGoldenFixtureTest
.java`, `EdgeFrameCodecV2GoldenFixtureTest.java`, `EdgeFrameCodecV3GoldenFixtureTest.java`. The
raft-wire goldens (`GoldenFixtures.java`, `WireCompatGoldenBytesTest.java`) are **unchanged** (frame
epoch stays `0`; the new rule only *adds* a reject test).

---

# §6 Persist-before-ack ordering for the durability anchor

This section works through the exact fsync placement and its crash-interleaving proof, separately
from §4's anchor design and §5's magic/version registry. The largest finding here (§7) is that the
`max(anchor.currentTerm, lastWALTerm)` *repair* rule discussed as an option in §4 is unnecessary and
should be a strict assert-or-REFUSE - which is what shipped (§2.17 step 2.5).

The anchor is the merged per-shard `raft-anchor` carrying
`{anchorSeq, currentTerm, votedFor, lastDurableIndex, lastDurableTerm, snapshotIndex, snapshotTerm}`,
subsuming `raft.persistent_state.dat` and the bare `raft-log.snapshot-meta.dat`.

**File shape (§A1.3):** an 8-byte **unauthenticated container header**
`[magic][fileVersion=1][flags][reserved]` at offset 0, written **once at file creation** (fsync'd
then), followed by two fixed-offset 512-B slots. The header is a version marker readable before any
crypto (Kafka magic-before-CRC); it is write-once and thus **outside every hot path** - it changes
none of the fsync ordering below. It only adds a boot-time read+validate (§5) and folds into the
one-time creation preallocation (§4.2).

---

## 0. Ground truth before this design (verified line numbers)

| Path | Behavior before this design | fsyncs before |
|---|---|---|
| Leader flush cycle | `RaftNode.flushDurable:2223` -> `log.syncWal():2236` -> `durableIndex=target:2237` -> `maybeAdvanceCommitIndex:2238`; self-vote gated `durableIndex>=n` at `:2175` | **1** WAL `force(true)` (`FileStorage.syncLog:179`; +dir `:183` only on first-ever syncLog of a new WAL) |
| Follower append | `RaftLog.appendEntries:414`; conflict `truncateFrom:442`; single trailing `syncWal():449` **before** the method returns -> before the ACK | **1** WAL `force(true)` per RPC batch |
| Term/vote | `DurableRaftState.setTerm:99`/`vote:122`/`setTermAndVote:143` -> `persistValues:157` = `storage.put` (`FileStorage` file `force(true):88` + dir `sync():96`) **+ extra** `storage.sync():164`; **persist strictly BEFORE in-memory update** (`:106-108,130-131,145-147`) | **3** (1 file + 2 dir) + 1 rename |
| Conflict truncation | `truncateFrom:461` -> `rewriteWal:472` -> `storage.sync():477` (dir) | `rewriteWal` = **N** per-entry `force(true)` (durable `appendToLog:136`) + rename + 1 dir |
| Compaction | `compact:551`: `rewriteWal:583` -> `put(SNAPSHOT_META):587` (bare 16 B) -> `storage.sync():588`; `persistSnapshot:520` MUST precede (`:502` durable-prefix invariant) | N (rewrite) + 2 (meta put) + 1 dir |
| Recovery | `RaftLog` ctor `:151-154` bare `entries.add(deserializeEntry)` - **NO contiguity/position check** (guard lives only in `appendNoSync:361-364`, bypassed on replay); snapshot boundary from bare meta `:161-165`, "trust WAL over stale meta" `:179-182` | - |
| Client write-ack | `RaftNode.applyCommitted:2246` fires `whenCommitOutcome:1030` -> `ConfigdServer:2150-2152` completes `Committed`. Ack is **downstream of `setCommitIndex`+`applyCommitted`** which needs the `durableIndex` gate `:2175` | - |

Two clarifications: `ConfigdServer:833/861` is the **read-index** path
(150 ms linearizable read), not the write ack (`:2150-2152`); and the vote path is **3** fsyncs,
not 4 (the "4th" is the rename syscall). Neither changes the design.

---

## 1. Placement table - every write path x ordered writes/fsyncs

Notation: `W-append` = `appendToLogNoSync`; `W-fsync` = `syncLog` `force(true)` (WAL grows ->
metadata -> needs `force(true)`); `A-write` = dual-slot in-place `pwrite` of the stale slot;
`A-fsync` = `force(false)`/fdatasync of the preallocated anchor (in-place, no metadata change -> data
sync suffices - a real, cheaper-than-`force(true)` choice, §3). Anchor is written LAST in every
cycle; `A-write` snapshots the CURRENT in-memory `{currentTerm,votedFor,...}`.

| Path | Ordered sequence | fsyncs (today->designed) |
|---|---|---|
| **Leader flush** | `W-append`(each propose, no fsync) ... then flush cycle: **1** `W-fsync` -> **2** `A-write(lastDurableIndex=target, currentTerm=cur)` -> **3** `A-fsync` -> **4** `durableIndex=target` -> `maybeAdvanceCommitIndex` (self-vote now counts) -> commit -> ack | 1 -> **2** |
| **Follower append** | `W-append`(each entry) -> **1** `W-fsync` (once/RPC) -> **2** `A-write(lastDurableIndex=newHead)` -> **3** `A-fsync` -> **4** return -> send ACK | 1 -> **2** |
| **Term/vote** | **1** `A-write(currentTerm/votedFor=new, lastDurableIndex=cur)` -> **2** `A-fsync` -> **3** in-memory update. Persist-BEFORE-memory PRESERVED (the anchor write replaces `persistValues`) | 3 -> **1** |
| **Conflict truncation** (INV-ANCHOR-LOWER) | **1** `A-write(lastDurableIndex=conflictPoint-1)` -> **2** `A-fsync` -> **3** `rewriteWal` (tmp+rename) -> **4** dir `sync()` (rename durable) -> **5** `W-append`+`W-fsync` (leader's entries) -> **6** `A-write(lastDurableIndex=newHead)` -> **7** `A-fsync` -> **8** ACK | +2 anchor (rare path) |
| **Compaction** | **1** `persistSnapshot` (blob durable, `put`) -> **2** `rewriteWal` -> **3** dir `sync()` -> **4** `A-write(snapshotIndex/Term=B, lastDurableIndex=head)` -> **5** `A-fsync`. Anchor LAST; replaces `put(META)+sync` | -2 net (rare path) |
| **Node-anchor: topology** | boot / shard-count-change only: `A-write({topologyEpoch, shardCount})`+`A-fsync`, cross-checked at boot vs the TopologyDescriptor file. ~0 steady-state | negligible |
| **Node-anchor: audit head** | **PERIODIC, not per-record** (§ below) | +1 per K records |

**Can the two per-RPC forces be batched into one?** No - `W-fsync` and `A-fsync` target *different
files* (`raft-log.wal`, `raft-anchor`); one fsync syscall cannot cover two descriptors, and the WAL
must be durable *before* the anchor references it, so they are strictly ordered. What IS batched:
**both are per-RPC/per-group-commit-batch, not per-entry** - one `W-fsync` + one `A-fsync` per
AppendEntries RPC regardless of entry count, and the anchor advances once per batch to the batch
head. etcd pays only one fsync because `consistent_index` rides the same bolt txn as data; our WAL
is an append-only file, not a transactional B-tree, so we cannot co-commit - we pay two.

**Audit head cadence (honest, documented residual).** Per-record anchoring doubles audit fsyncs
(`AuditLog` uses durable `appendToLog` = 1 `force(true)`/record; audit writes only on auth events).
I choose **periodic**: advance `nodeAnchor.{auditRecordCount,auditHeadHash}` every **K=64 records or
T=1 s, whichever first**. **Documented detection window:** trailing audit records within the last
un-anchored batch (<= 64 records or <= 1 s of audit tail) can be truncated without tripping the head
check. This is an **accepted-and-documented residual**, not a silent hole - stated in the runbook
and the threat model, tunable to K=1 for audit-critical deployments at the cost of a doubled audit
fsync rate.

---

## 2. Crash-interleaving matrix (the INV-ANCHOR-ACK + recovery-asymmetry proof)

`W` = WAL last index at recovery; `A` = `anchor.lastDurableIndex`. Recovery gates (from
`anchor-rotation-design.md §A1.4` Step-3, which I place here): `W==A` ACCEPT; `W>A` ACCEPT-FORWARD
(adopt WAL head, rewrite anchor); `W<A` **REFUSE**. FileStorage drops any torn WAL tail first
(`:271`), so every frame reaching recovery is CRC-complete.

### 2a. Leader flush cycle - crash between each adjacent pair

Steps: (0 pre) -> **1** `W-fsync` -> **2** `A-write` -> **3** `A-fsync` -> **4** `durableIndex=target` ->
(commit/ack).

| Crash after step | Recovery sees | Gate row | Safe because |
|---|---|---|---|
| before 1 | WAL append not fsync'd -> torn tail dropped `:271` | `W==A` | entry never durable, never counted |
| **1, before 2** (the key case) | WAL has `[A+1..W]` durable; anchor still `A` | **`W>A` accept-forward** | `[A+1..W]` were **UNACKED** - the self-vote needs anchor coverage (`durableIndex` advances only at step 4, after `A-fsync`), so `maybeAdvanceCommitIndex:2175` never counted them -> never committed -> never client-acked. Re-cover: set `A=W`, rewrite anchor. See 2c. |
| 2, before 3 | torn anchor slot (unforced) fails CRC/MAC -> prior slot (seq-1, `A` old) wins | `W>A` accept-forward | identical to the row above; the half-written slot is ignored by dual-slot pick-highest-valid |
| 3, before 4 | anchor durable at `A=W`; `durableIndex` not yet advanced in-memory (irrelevant - rebuilt from anchor) | `W==A` | entries now anchored; recovery adopts `A=W` - still UNACKED (commit hadn't advanced), re-offered to quorum normally |
| after 4, before commit | `W==A`, entries durable+anchored, uncommitted | `W==A` | normal uncommitted tail; leader re-replicates |

### 2b. Follower append cycle

Steps: **1** `W-fsync` -> **2** `A-write` -> **3** `A-fsync` -> **4** return -> send ACK.

| Crash after step | Recovery | Gate | Safe because |
|---|---|---|---|
| before 1 | torn tail dropped | `W==A` | not durable, ACK never sent |
| 1, before 3 | WAL `[A+1..W]` durable, anchor `A` (or torn newer slot ignored) | `W>A` accept-forward | ACK is sent only after step 4 -> the leader never saw `matchIndex=W` -> those entries never counted toward quorum on the leader -> never committed. Re-cover safe. |
| 3, before 4 | anchor `A=W` durable, ACK not yet sent | `W==A` | leader will re-send; idempotent |
| after 4 | ACK in flight | `W==A` | matchIndex reported == durable+anchored (persist-before-ACK holds) |

### 2c. (a) crash between W-fsync and A-fsync - what distinguishes re-cover from adversarial truncation

**Nothing distinguishes them for UNACKED entries, and that is exactly correct.** The anchor's
`lastDurableIndex A` is the precise fault line:

- **At or below `A`** = the committed/client-acked prefix. Losing any of it => `W<A` => **REFUSE**.
  This is the whole point of the anchor.
- **Above `A`** = unacked. An adversary truncating `[A+1..W]` to any `W'>=A` is **byte-for-byte
  indistinguishable from the leader having simply crashed one flush-cycle earlier** - and it must
  be, because *no client was ever promised those entries*. Presenting fewer unacked entries is a
  strict subset of what an honest earlier crash produces; the adversary gains nothing. Accept-forward
  is safe not because we *detect* honesty but because there is *nothing to protect* above `A`.

So the security boundary is the index `A` itself: protected at/below, free above. This asymmetry is
sound **only because anchor-before-ack makes `A` an upper bound on everything ever committed-and-acked**
(§4 rejected alternative shows why a lagging anchor breaks it).

### 2d. Compaction crash-at-any-point (folding the bare meta into the anchor)

Order: **1** `persistSnapshot(blob@B)` -> **2** `rewriteWal` (WAL now starts `B+1`) -> **3** dir sync ->
**4** `A-write(snapshotIndex=B)` -> **5** `A-fsync`.

| Crash after | Recovery sees | Decision | Why non-refusing |
|---|---|---|---|
| 1, before 2 | blob@B durable, WAL still full from old boundary, anchor.snapshotIndex=old | ignore blob (ahead of anchor), replay full WAL | full prefix intact (today's `:511` invariant) |
| 2, before 4 | blob@B durable, WAL starts `B+1`, anchor.snapshotIndex=old<B | **adopt boundary = WAL.firstIndex-1 = B, REQUIRE blob@B present**, rewrite anchor to B | today's "trust WAL over stale meta" `:179-182`; blob@B was persisted at step 1. If WAL.firstIndex-1 has **no** matching authenticated blob => REFUSE (that is the adversarial prefix-truncation) |
| after 4 | anchor.snapshotIndex=B == WAL.firstIndex-1 == blob.lastIncludedIndex | clean | all three agree |

Adversarial protection: the blob is `SNAP_MAGIC`+`scopeId`-authenticated; once the anchor commits
`snapshotIndex=B`, rolling the blob back to `B'<B` mismatches the anchor => REFUSE.

---

## 3. Cost model

fsyncs per operation (force calls), today -> designed:

| Op | Today | Designed | Δ |
|---|---|---|---|
| Leader flush cycle | 1 | 2 | **+1** |
| Follower append (per RPC) | 1 | 2 | **+1** |
| Term/vote | 3 (1 file+2 dir) | 1 (in-place `force(false)`) | **-2** |
| Conflict truncation | N+1 | N+3 | +2 (rare) |
| Compaction | N+3 | N+1 | -2 (rare) |
| Audit record | 1 | 1 + (1 per K=64) | +~1.5% |

**Amortization:** both hot-path fsyncs are **per-group-commit-batch**, and the anchor advances once
to the batch head. At the knee the batch is largest, so per-op overhead is *smallest*; the relative
cost is highest only at batch-size-1 (low load), where absolute throughput is a non-issue.

**Knee-impact bound (an engineering estimate, made before measuring).** The measured single-group
knee is **~800 w/s, fsync-bound** on an m6i-class EC2 box. A second sequential barrier per flush
cycle:
- Pessimistic: if barriers fully serialize and don't coalesce, ~2x barrier time/cycle -> knee toward
  ~450-600/s.
- Realistic: the anchor flush is a **512-B in-place `force(false)`** of a preallocated, cache-warm
  page - no allocation, no inode writeback, back-to-back with the WAL barrier on the same device
  (often coalesced by the device cache), and cheaper than the WAL flush (which writes a variable,
  frequently multi-page append). A 4 KiB in-place fdatasync costs roughly one device barrier (~same
  latency as the WAL barrier on a pure-flush-bound device) but skips the page-cache writeback the
  WAL pays.
- **Honest bound: 10-40% knee regression (likely ~10-20%), heaviest at low batch sizes**, confirmed
  by re-measuring the knee under this design after the build. If it comes in above ~25%, the lever
  is to raise `groupCommitLingerMicros`/`groupCommitMaxBatch` so the fixed 2-fsync cost amortizes
  over a larger batch (`scheduleFlush:2199-2213`).

**Alternative REJECTED - anchor-lag with reconcile window (Postgres `pg_control` style).** Let the
anchor lag the ack and reconcile forward on recovery (1 fsync/cycle, no regression). **Forbidden for
acked data:** a lag window `(anchor, acked]` lets an adversary trim the WAL to just below an acked
index; recovery's `W>=A => accept-forward` then **silently accepts the truncated log** and the acked
entry vanishes with no REFUSE. Postgres tolerates a lagging control file because it is a *crash*
anchor, not an *anti-rollback* anchor; our threat model (adversary with disk write, no key) forbids
the lag. The extra fsync is the price of anti-rollback.

---

## 4. Fail-closed policy at the anchor seam

Frozen policy, applied identically to a WAL-fsync throw (`syncLog`->`force`) and an anchor-fsync
throw:

1. **fsync THROWS (`IOException`) or is detected to have lied:** the flush cycle **must not** advance
   `durableIndex`, **must not** `A-write`/advance commit, **must not** ack. Then: **PANIC - process
   exit** after a SEVERE log + a fatal counter (`durability.fsync.failed`). **Recommendation:
   process exit, not step-down-and-serve.** Rationale (fsyncgate; Postgres `data_sync_retry=off`
   PANIC): on Linux a failed fsync can mark dirty pages clean, so a *later* fsync can falsely
   "succeed" while the bytes are lost; a step-down-but-alive node would keep unsynced in-memory
   state and could re-ack. Exit + rebuild from the durable WAL/anchor on restart is the only sound
   response. Consistent for both seams. Test: wire `FaultInjectingStorage.failNextSyncs`
   (`:56`) into a **live-RaftNode** flush cycle, assert no-durable-advance + no-ack + exit signal.
2. **ENOSPC on an anchor write:** **impossible after boot.** The one-time file-creation write lays
   down the 8-B container header **and** both 512-B slots (~1032 B total) and fsyncs once, so all
   blocks are allocated at creation; steady-state anchor writes are in-place overwrites of the stale
   slot only and never allocate. This eliminates anchor-ENOSPC on the
   ack path: the flush order is `W-append` (grows, CAN ENOSPC - already handled) -> `W-fsync`
   -> `A-write` (in-place, CANNOT ENOSPC). The failure-prone step is first; the anchor never
   half-commits on ENOSPC. **Caveat (documented):** on COW filesystems (Btrfs/ZFS) an in-place
   overwrite may still allocate - so the guarantee is stated for **ext4/xfs with real preallocated
   blocks** (the measured/supported stack); COW filesystems weaken it to the WAL's ENOSPC handling.
3. **Partial / torn anchor write:** dual-slot. Only the *stale* slot is mutated per update; a torn
   slot fails CRC32C/MAC and the untouched slot (seq-1) wins (`anchor-rotation-design.md §A1.3`
   pick-highest-valid). A torn write never damages the authoritative slot.
4. **Anchor on a DIFFERENT device than the WAL:** **REQUIRE same device (same shard directory).**
   The anchor lives at `dataDir/shard-<gid>/raft-anchor`, next to `raft-log.wal` (`§A1.3`) - same
   directory, hence same filesystem/device/failure-domain. A split device breaks the ordering
   guarantee: if the WAL device is lost but the anchor device survives, `anchor.lastDurableIndex`
   references WAL entries that no longer exist -> spurious `W<A` REFUSE (or, reversed, a lost anchor
   over a live WAL). Enforce same-directory at construction; reject a configured cross-device anchor.

---

## 5. Boot / recovery sequence (exact order)

Keyring load precedes anchor read because the anchor's MAC/DEK key is `K_integrity[keyTerm]` /
`DEK[keyTerm]` derived from the **keyring root**, not the raw signing key (`§A2.3`).

1. **Load signing key** - `SigningKeyStore`, off-datadir (D-1, `ConfigdServer:1366`). Yields
   `K_keyringMac` + `KEK_wrap`.
2. **Load keyring** (`raft-keyring`, dual-slot, highest valid `keyringSeq` whose outer MAC verifies
   under the *current* signing key) -> `term->root` map + `activeTerm`. Absent-but-encrypted-data =>
   REFUSE; outer-MAC-fail => REFUSE with the "keyring under a prior KEK" diagnostic (`§A2.4`).
3. **Per shard, read `raft-anchor`** - first read the 8-B container header at offset 0 and validate
   `magic` + `fileVersion==1` (unknown `fileVersion` => REFUSE, fail loud; the header is
   unauthenticated, so a garbled/forged header simply fails this check - the authenticated slots are
   never reached). Then parse both slots at their fixed post-header offsets; for each verify
   IntegrityEnvelope (CRC32C -> MAC/GCM tag under `K_integrity[slot.keyTerm]`, `scopeId==gid`,
   `formatVersion==3`); take highest-valid `anchorSeq`. Apply the presence table:

   | Anchor slots | Shard dir | Decision |
   |---|---|---|
   | file absent | **empty** (no `raft-log.wal` with size>0 AND no `raft-log.snapshot.dat`) | **FRESH** - bootstrap `anchorSeq=1,lastDurableIndex=0,currentTerm=0,votedFor=-1` |
   | file absent | **non-empty** (WAL size>0 OR snapshot blob present) | **REFUSE** (anchor deleted) |
   | >=1 slot valid | - | proceed to step 5 |
   | file present, both slots invalid | - | **REFUSE** (tamper - distinct from FRESH which has *no file*) |

   "Non-empty" defined precisely: `raft-log.wal` exists with size > 0, **or** `raft-log.snapshot.dat`
   exists. A zero-byte WAL is FRESH-equivalent (`FileStorage.readLog` returns empty).
4. **Read WAL** - FileStorage drops torn tail; `deserializeEntry` verifies envelope + `scopeId==gid`.
5. **NEW recovery checks (the `RaftLog` ctor lacks these today, `:151-154`):** contiguity
   (`e[k].index == firstIndex+k`), term-monotonicity (`e[k].term` non-decreasing), snapshot-join
   (`firstIndex == anchor.snapshotIndex+1`; blob boundary matches). Any violation => REFUSE.
6. **Head reconciliation** - `W` vs `A`: `W==A` accept; `W>A` accept-forward (**assert
   `anchor.currentTerm >= lastWALTerm` else REFUSE - see §7**; set `lastDurableIndex=W`, rewrite
   anchor at `anchorSeq+1`); `W<A` REFUSE.
7. **Restore** `currentTerm`/`votedFor` from the anchor (replaces `DurableRaftState.load`);
   rebuild state machine from snapshot blob + WAL suffix.

---

## 6. No-regression argument

| Property | Preserved? | Test impact |
|---|---|---|
| fsync-before-ack (leader self-vote gated on `durableIndex`; follower fsync-before-ACK) | **Preserved + strengthened** - anchor fsync joins the barrier; `durableIndex` advance now additionally gated on anchor-durable | `GroupCommitDurabilityTest` (`gateBlocksCommitUntilLeaderEntryIsDurable:94`, `queuedFlushAfterStepDown...:122`): core claim holds; **ADD** an assert that the covering anchor is durable before commit |
| kill-9 / crash recovery matrix | Preserved; matrix **grows** (new anchor-vs-WAL crash points, §2) | `SnapshotCrashRecoveryTest` (`:105/:111/:117/:123`): **rewrite** to assert snapshot boundary via the **anchor** (bare `snapshot-meta` removed); `gapDetectionFiresWhenSnapshotFsyncLied:274` kept |
| torn / partial write | **Byte-identical** - FileStorage torn-tail `:271` + CRC32 untouched | `WalRecordIntegrityTest:87`, `FileStorageTest.crc32IntegrityVerification:106`: **keep byte-identical**; **ADD** a torn-anchor-slot dual-slot-fallback test |
| ENOSPC clean rejection | Preserved for WAL; anchor is ENOSPC-immune post-boot (§4.2) | `StorageEnospcConsensusReactionTest:45/:98`: **keep**; **ADD** a boot-time anchor-preallocation-ENOSPC clean-refuse cell |
| vote durability / no double-vote | Preserved - persist-before-memory kept, now via anchor write | `VotePersistenceCrashTest:98`, `DurableRaftStateIntegrityTest`: **rewrite** to assert vote durability + forgery-refusal via the **anchor** |
| fsync-failure fail-closed | **New** - was previously an open gap; §4.1 closes it | **NEW** live-RaftNode `failNextSyncs` cell (both WAL and anchor seams) |

Kept byte-identical: FileStorage framing/torn-tail/CRC path. Rewritten (semantics
changed by the merge): snapshot-boundary and vote-durability tests (bare meta + separate state file
removed). New: the §2 crash-interleaving matrix, torn-anchor-slot, `W<A` REFUSE, fresh-vs-tampered,
anchor-lower-before-truncate, the `currentTerm >= lastWALTerm` assertion, and the fsync-throw exit.

---

## 7. Findings from cross-checking §4's design

**F-1 (load-bearing): the `max(anchor.currentTerm, lastWALTerm)` REPAIR rule in §A1.4 Step-3 is
unnecessary and should be a strict INVARIANT + assert-or-REFUSE.** Claim: under the merge,
`anchor.currentTerm >= lastWALTerm` **always** holds at recovery, so no `max()` repair is ever
needed.

Proof. A WAL entry at term `T` is written only after the node has adopted `currentTerm = T`. Every
term adoption goes through `DurableRaftState.setTerm/setTermAndVote`, which - under the merge - is an
**anchor write that persists `currentTerm=T` BEFORE the in-memory update** (persist-before-memory,
`:106-108,130-131,145-147`), and that anchor write happens strictly before any term-`T` WAL append:
- Leader: wins election in `T` via `setTermAndVote(T,self)` (anchor write, `currentTerm=T`) -> *then*
  appends its noop/client entries at `T`.
- Follower: on AppendEntries with `leaderTerm=T>currentTerm`, `setTerm(T)` (anchor write) fires in
  the term-update step *before* `log.appendEntries` writes the term-`T` entries.

So at the instant any term-`T` entry reaches the WAL, the anchor already carries `currentTerm >= T`;
every later `A-write` re-snapshots the current (still `>= T`) term. This holds for the UNANCHORED
accept-forward tail too (the `setTerm` anchor write precedes the WAL append that the crash left
unanchored). Therefore `anchor.currentTerm >= lastWALTerm` is an invariant maintained by the ordering
- it is Raft's own "`currentTerm >= every log term`" property made durable, which is precisely why
`DurableRaftState` persists-before-memory today.

Consequence - and why `max()` is not just unnecessary but **harmful**: §4 motivated
`max()` as a defense against anchor-state rollback across a vote boundary. But if an adversary rolls
the anchor to an older slot with a *lower* `currentTerm` while leaving higher-term WAL entries, the
result is exactly `anchor.currentTerm < lastWALTerm`. The `max()` rule would **silently repair
forward and boot the rolled-back node**, masking the tamper. The assertion **REFUSES** it - the
correct fail-closed response to a detected anchor/WAL contradiction. `max()` converts a detectable
partial-rollback into a silent one; it should be deleted. (If both anchor and WAL are rolled back
*consistently* to the same old term, that is the R-a residual - undetectable without an external
witness - and `max()` never helped there either.)

Corollary: in accept-forward (`W>A`), `currentTerm` is **never raised** (it already dominates), so
`votedFor` is **never cleared** - the §A1.4 "clear votedFor if term raised" branch is dead code.
Recovery simply keeps `currentTerm`/`votedFor` from the anchor. Simpler and strictly safe.

**F-2 (refinement, not a disagreement): "A >= everything ever acked" should read ">= everything ever
COMMITTED-and-client-acked."** A follower's `matchIndex` ACK is a replication-progress report to the
leader, not a client durability promise; the client is acked only when the *leader* commits
(quorum, `applyCommitted`->`whenCommitOutcome`). This matters for **INV-ANCHOR-LOWER**: conflict
truncation lowers a follower's `lastDurableIndex` below a previously-ACKed `matchIndex`, and that is
**safe** because Raft guarantees the conflict point is *above* `commitIndex` (Leader Completeness
never truncates committed entries), so no *committed/client-acked* index is ever lowered. The `W<A`
REFUSE gate is about crash-consistency of anchor-vs-WAL (maintained by lower-first ordering); the
"never lose acked data" property is about the *committed* floor. Keeping these two arguments distinct
removes the apparent contradiction between "anchor is an upper bound on acked" and "the anchor may
legitimately go down during truncation."

**F-3 (placement note): INV-ANCHOR-LOWER must lower the anchor BEFORE `rewriteWal`.** Verified
against `truncateFrom:461-479` - before this design it `rewriteWal`s then dir-syncs with no anchor. If the anchor
were lowered *after* the truncate, a crash in between leaves `anchor.lastDurableIndex > W` = spurious
`W<A` REFUSE on a **legal** Raft truncation (false positive, row 19 of the matrix in §4). Lower
first => a crash between lower and rewrite leaves `W>=A` (accept-forward re-adopts, leader re-truncates)
=> non-refusing. This confirms the step order in §4 is load-bearing and pins its exact placement.

**F-4: anchor writes should use `force(false)`/fdatasync, not `force(true)`.** The
preallocated fixed-size slots never change file metadata after creation, so a data-only sync is
sufficient and skips the inode writeback - a real (if modest) cost saving. The WAL keeps
`force(true)` (append grows the file -> metadata).

---

# Part III - §7 A compile-checked prototype (validated the byte layouts before the build)

Before writing the real implementation, a standalone `io.configd.frozen` prototype rendered §2's
byte layouts into compilable codecs, built and run on JDK 25. Its self-test exercised golden
round-trips plus every fail-closed path: bad CRC, rolled/zero version, non-zero reserved, unknown
algId, downgrade-under-key, per-field-boundary truncation of the anchor record, a torn slot falling
back to its valid sibling, an unknown keyring term, cross-scope replay, keyring rewrap/rotation
round-trips (including a crash on either side of the signing-key swap), a cursor epoch mismatch,
and the policy pragma. It covered `EnvelopeV3`, the anchor record and codec, the node-anchor
record, the keyring codec, the topology descriptor, the watch cursor, and the policy pragma - 75
cases, all passing, compiling clean with `javac --release 25 -Xlint:all`.

The real implementation (§9.1) re-implements these at the production seams; the prototype's only
job was to prove the byte layouts compose and fail closed as specified before committing to them.

---

# §8 Prior art: how bar-setting systems freeze, version, anchor, and rotate on-disk/wire formats

Configd is a Raft-based config store (Java) with AES-256-GCM encryption-at-rest at an
`IntegrityEnvelope` seam, node-local key material. Every claim below names the
system, the mechanism, and cites a primary source (URL or source-file path).
Where the bar systems disagree, that is called out with a recommendation for a
node-local-key config store.

---

## Framing: the threat-model boundary that governs everything below

The single most important distinction the bar systems draw, and the one Configd
must state explicitly, is:

- **Crash-consistency** (torn tails, partial writes, bit-rot) - a *non-adversarial*
  fault. Every system here handles it with CRCs, magic numbers, dual meta pages,
  and fsync ordering.
- **Adversarial tampering with filesystem write access** - an attacker who can
  rewrite bytes on disk. This is a *strictly harder* threat and most storage
  engines (Postgres, bbolt, SQLite, Kafka) **do not** defend against it at all;
  their integrity checks are corruption detectors, not authentication.
- **The node-local-key sub-case** (Configd's actual situation): the attacker has
  disk write access **but not the key material**. This is the only place an
  integrity story is even possible, and it has a hard ceiling.

The honest boundary, drawn identically by dm-verity, dm-integrity, and Android
Verified Boot: **an authenticated anchor can detect tampering/rollback by an
adversary who lacks the signing/MAC key, but CANNOT detect rollback of the anchor
itself to a prior legitimately-signed state unless the anchor lives in storage the
adversary cannot rewrite** (out-of-band, monotonic hardware, or a remote witness).
With a node-local key, an adversary holding *both* disk write access *and* the key
can forge anything - so that is explicitly out of scope, and must be stated as
such. (dm-verity: "This hash should be trusted as there is no other authenticity
beyond this point" - the root hash is supplied out-of-band via a verified boot
chain, kernel command line, or signature, never trusted from the protected device.
https://docs.kernel.org/admin-guide/device-mapper/verity.html)

---

## Q1. Tail-truncation / rollback detection against a disk-write adversary

### 1a. Postgres WAL - what each mechanism actually detects (and what it can't)

Source files: `src/include/access/xlog_internal.h`, `src/include/access/xlogrecord.h`,
`src/include/catalog/pg_control.h` (PostgreSQL master).

- **`XLOG_PAGE_MAGIC` = `0xD120`** - a 16-bit page-header magic that doubles as a
  WAL format-version indicator (the comment literally says "can be used as WAL
  version indicator"). Detects: wrong/foreign/older-format pages, and gross
  garbage. Does NOT detect: a validly-formatted shorter log.
- **Per-record CRC (`xl_crc`, a `pg_crc32c`)** - covers the record header and
  payload except the CRC field itself (`SizeOfXLogRecord = offsetof(XLogRecord,
  xl_crc) + sizeof(pg_crc32c)`). Detects: bit-flips and torn/partial records.
  Recovery recomputes the CRC and **halts at the first record whose CRC fails**,
  treating everything after as non-existent.
- **`xl_prev` back-chain** - every `XLogRecord` carries "ptr to previous record in
  log." On replay Postgres checks that the record it reads back-links to the record
  it just processed; a mismatch means it followed a stale/garbage pointer and stops.
  Detects: mis-sequenced or spliced records. Does NOT detect: truncation.
- **`pg_control` checkpoint/redo pointers** - `ControlFileData` holds
  `checkPoint` (last checkpoint record LSN) and `checkPointCopy.redo` (where REDO
  must start). Recovery reads `pg_control` first, then scans WAL forward from the
  redo pointer. This is the anchor that says "start here."

**The key insight to pin down:** per-record integrity + prev-chaining can **never**
detect adversarial trailing truncation, because *a prefix of a valid WAL is itself
a valid WAL*. Every remaining record's CRC still verifies, every `xl_prev` still
links correctly; nothing internal to the log records "how many records there were
supposed to be." Postgres **accepts the shorter log by design** - crash recovery
*must* stop at the first invalid/torn record, because a crash and an adversarial
truncation-at-a-record-boundary are byte-for-byte indistinguishable from inside the
log. Detecting truncation therefore requires an **external high-water mark** that
lives somewhere the adversary can't rewrite consistently. Postgres never claims to
provide this; `pg_control` is a crash anchor, not an anti-rollback anchor (it is
CRC-protected against corruption, not MAC-protected against forgery).

### 1b. Certificate Transparency / verifiable logs - the authenticated high-water mark

Source: RFC 6962 (https://www.ietf.org/rfc/rfc6962.txt).

- A **Signed Tree Head (STH)** commits to, and signs over: `tree_size` (number of
  entries), `timestamp`, `sha256_root_hash` (Merkle root over all entries), and
  `version`. The signature (`TreeHeadSignature`) is what makes it non-repudiable.
- This is exactly the shape of an authenticated high-water mark: it binds **size/
  index AND a hash over the entire head state**, under a signature the log operator
  cannot deny. A truncated or rolled-back log must present an STH with a smaller
  `tree_size` or a different root for a size it previously signed - which is
  cryptographic proof of misbehavior.
- Detection is via **Merkle consistency proofs** between two STHs (proving the
  earlier tree is a prefix of the later) plus **gossip** - "Violation of the
  append-only property is detected by global gossiping ... comparing their versions
  of the latest Signed Tree Heads." The anchor's power comes from being witnessed
  *off the log's own storage*.
- **Crosby-Wallach, "Efficient Data Structures for Tamper-Evident Logging" (USENIX
  Security 2009)** generalizes this: a *history tree* lets a logger produce a
  commitment (a signed root over the first *n* events) such that it cannot later
  present a different event *i < n* or a shorter/altered history without detection.
  The lesson for Configd: an authenticated head must commit to **(count/index +
  hash-or-MAC over the head)**, and detection of rollback ultimately depends on
  that commitment being compared against a copy the adversary cannot rewrite.

### 1c. Anti-rollback in practice - what is realistic without trusted hardware

- **TPM monotonic counters** (TCG TPM 2.0 NV counters): hardware counters that can
  only increase and survive reboots. Anchoring a log's head index to a TPM counter
  makes rollback detectable because replaying an old head presents a counter value
  below the hardware's current value. Requires trusted hardware.
- **Android Verified Boot rollback protection**
  (https://source.android.com/docs/security/features/verifiedboot/verified-boot):
  "Rollback protection is typically implemented by using **tamper-evident storage**
  to record the most recent version ... and refusing to boot ... if it's lower than
  the recorded version." The rollback index lives in RPMB / TrustZone - *separate
  from the partition it protects* - precisely because "If an attacker could rewrite
  this stored index value, they could make the system accept arbitrarily old
  versions, defeating the entire protection mechanism." This is the general shape
  of every honest anti-rollback design: **the monotonic marker must live where the
  adversary cannot rewrite it.**
- **fs-verity** - read-only Merkle-tree integrity for individual files; the root
  hash is the anchor and, like dm-verity, must be signed / supplied out of band.
  Protects against modification of a file's *contents*, not against replacing the
  whole file+signature with an older signed version (that is left to the caller's
  key/signature policy).
- **dm-integrity** (https://docs.kernel.org/admin-guide/device-mapper/dm-integrity.html):
  per-sector integrity + journaling for **write atomicity** ("either both data and
  tag or none of them are written"). When combined with dm-crypt it gives
  *authenticated* encryption so "if the attacker modifies the encrypted device, an
  I/O error is returned." But this is **live per-sector tamper detection, not
  whole-device rollback/replay protection** - reverting the entire device (data +
  tags together) to an earlier consistent snapshot is out of scope by design.
- **dm-verity** (URL above): read-only device integrity where the **root hash is
  the trust anchor and must be supplied externally** ("trusted as there is no other
  authenticity beyond this point"), because storing it on the protected device
  "would create a circular dependency."
- **Vault**: encrypts every storage entry with AES-256-GCM so tampering is detected
  by the GCM tag on read, but Vault's threat model **does not claim protection
  against an attacker who rolls the storage backend back to an earlier consistent
  state** - the GCM tag over an old-but-valid ciphertext still verifies. This is the
  same ceiling.

**The crisp boundary for Configd (state it verbatim in the design):** *With a
node-local key, the design goal is to detect truncation/rollback by an adversary
who has disk write access but NOT the key material - via an authenticated head that
MACs (count/index + hash-over-head). Rollback of that authenticated head to a prior
legitimately-MAC'd state cannot be detected without external monotonic storage
(TPM/RPMB) or a remote witness, and an adversary holding BOTH the disk and the key
can forge anything. dm-verity, dm-integrity, Android Verified Boot, and Vault all
draw the line in exactly this place.*

### 1d. bbolt / LMDB dual meta pages, SQLite change counter - the head-anchor pattern for crashes

- **bbolt** (https://github.com/etcd-io/bbolt): two meta pages, each carrying a
  monotonically increasing **txid** and a **checksum**. Commit = (1) write+fsync
  dirty pages, (2) write+fsync a new meta page with `txid+1`. On open, bbolt picks
  **the meta page with the highest *valid* (checksum-passing) txid**; a torn meta
  page fails its checksum and is ignored, so the previous meta page (lower txid)
  wins. "Partially written data pages are ignored ... the meta page pointing to them
  is never written." The highest-valid-txid meta page *is* the head anchor.
- **LMDB**: identical dual-meta-page design - two meta pages alternating by
  transaction id; the reader/opener trusts the one with the greater txid that
  validates. Commit writes the data pages, fsyncs, then writes one meta page and
  fsyncs; a crash mid-commit leaves the old meta page authoritative (MVCC, no undo
  log). (LMDB design: Howard Chu, "MDB: A Memory-Mapped Database", and
  http://www.lmdb.tech/doc/.)
- **SQLite** (https://www.sqlite.org/fileformat2.html): the **file change counter**
  (offset 24, 4-byte big-endian) is incremented whenever the DB is unlocked after
  modification; readers compare it to detect a stale page cache. The
  **version-valid-for** number (offset 92) stores the change-counter value that was
  current when the in-header DB size (offset 28) was last written - the size is only
  trusted when counter == version-valid-for, which detects a legacy writer that
  changed the file without updating the size.

**Pattern to extract for a small mutable state file:** dual A/B slots each with a
**sequence number + checksum**, pick the highest-valid-sequence slot. This makes
the state file crash-atomic *without* write-then-rename, and gives you a natural
place to hang a MAC when you need adversary-resistance (bbolt/LMDB checksums are
for corruption, not forgery - Configd would use a keyed MAC instead of a plain
checksum on each slot, and the sequence number becomes the anti-rollback index).
Note both crash-consistent systems accept the *older* slot on a torn newer write -
the same "accept the shorter/older state" behavior Postgres shows for the WAL tail.

---

## Q2. Fsync ordering: keeping the anchor never ahead of durable data

The universal invariant is **"the anchor must never reference data that isn't
durable yet."** The systems differ on whether they *also* guarantee the converse
(anchor is never behind), and mostly they **do not** - they let the anchor lag and
reconcile on recovery.

### Postgres (checkpoint ordering)

`src/backend/access/transam/xlog.c` `CreateCheckPoint()` + `pg_control.h`.

1. Flush dirty buffers to the data files.
2. Write the checkpoint WAL record and **flush the WAL** (data-covering WAL is
   durable).
3. **Update `pg_control` LAST** (`UpdateControlFile()`), with its own fsync.

`pg_control` is deliberately tiny so its update is a single atomic sector write:
the header comment requires "pg_control updates be atomic writes ... active data
can't be more than one disk sector, which is 512 bytes," enforced by a static
assertion `sizeof(ControlFileData) <= PG_CONTROL_MAX_SAFE_SIZE`, and a trailing
`crc` ("CRC of all above ... MUST BE LAST!"). **Postgres picks "anchor never ahead
of durable WAL" and tolerates "anchor behind."** A crash after step 2 but before
step 3 leaves `pg_control` pointing at an *older* checkpoint; recovery simply
replays WAL forward from that older redo pointer (idempotent), reaching a correct
state. The control file lagging the WAL is safe; the reverse would not be.

### etcd (consistent-index vs WAL fsync)

`server/storage/schema/cindex.go` + raft persistence rule.

- Raft rule: **an entry is fsync'd to the WAL before it is committed/applied** - no
  data is acked to a client before it is durable in the WAL.
- On apply, etcd writes the applied data **and** the `consistent_index` (8-byte
  big-endian, plus term since v3.5) into the bolt backend's `meta` bucket **inside
  the same backend transaction** as the data (`UnsafeUpdateConsistentIndex` ->
  `tx.UnsafePut(Meta, MetaConsistentIndexKeyName, ...)`; "tx has been locked in
  TxnBegin"). So the anchor and the data it covers commit atomically together.
- On restart etcd replays the WAL; entries whose index <= the stored
  `consistent_index` are already reflected in the backend and are re-applied
  idempotently / skipped. **etcd effectively gets BOTH invariants**: WAL-first fsync
  => data never acked before durable; consistent-index-in-same-txn => the backend
  anchor is never ahead of applied data (they're one commit).

### The dual-meta / doublewrite pattern for a crash-atomic state file

- **bbolt/LMDB dual meta page** (Q1d): the meta page (anchor) is written *after* and
  fsync'd *separately from* the data pages, so a crash between the two leaves the
  previous meta authoritative - anchor never ahead of data.
- **write-new-then-rename** (POSIX): write `state.tmp`, fsync it, fsync the
  directory, `rename()` over `state` - atomic pointer swap. Simpler but rename
  durability requires the directory fsync, and there is exactly one live copy.
- **dual-slot A/B with sequence numbers**: keeps *two* valid copies so you always
  have a fallback if the newest is torn, and the sequence number is a ready-made
  monotonic index. This is the better fit when the state file also has to be an
  anti-rollback anchor (Configd), because you can MAC each slot and reject a slot
  whose sequence number is below the last one you accepted.
- **InnoDB doublewrite buffer**: writes each page to a doublewrite area first, then
  to its final location, so a torn final write can be recovered from the intact
  doublewrite copy - same "write durable copy before overwriting the authoritative
  one" idea at page granularity.

**Recommendation for Configd:** adopt Postgres/etcd ordering - *flush the log/data
first, write the anchor last, fsync between*; never ack a write before the anchor
covering it is durable; and prefer the **dual-slot-with-sequence-number** state
file over rename, because the sequence number is your anti-rollback index and the
second slot is your torn-write fallback.

---

## Q3. Key rotation that never destroys data

The bar pattern is **versioned key terms, append-only, decrypt with the term the
ciphertext names, encrypt new writes with the newest term, retain old terms
forever.** Rotation NEVER re-encrypts in place; rewrap is a separate optional op.

### Vault barrier keyring - exactly

`vault/keyring.go` + `vault/barrier_aes_gcm.go`.

- **`Key` struct**: `Term` (uint32, sequential version id), `Version` (schema),
  `Value` ([]byte, the actual AES key), `InstallTime`, `Encryptions` (usage
  counter). The **`Keyring`** holds `keys map[uint32]*Key` indexed by term and an
  `activeTerm uint32`.
- **Ciphertext prefix carries the term** - barrier layout:
  `[term: 4 bytes big-endian][version: 1 byte][nonce: 12 bytes][GCM ciphertext+tag]`.
  Decrypt reads the first 4 bytes -> term -> looks up that term's AEAD in the keyring;
  reads byte 4 -> AES-GCM version (`AESGCMVersion1 = 0x1` no AAD; `AESGCMVersion2 =
  0x2` uses the storage path as AAD).
- **Rotate = `AddKey`**: installs a new `Key` at `term+1` and advances `activeTerm`
  if higher. "Old keys remain stored in the map, enabling decryption of legacy
  data. The system only encrypts with the active term but preserves historical keys
  for backward compatibility." New writes use the newest term; **old terms are never
  deleted** so old ciphertext stays readable - rotation is *append a term*, not
  *re-encrypt*.
- **The keyring itself is encrypted with the root key and persisted**: serialized
  via `EncodedKeyring { MasterKey []byte; Keys []*Key; ... }` (JSON), and the root
  key is obtained by unsealing (Shamir shares or auto-unseal KMS). Root key encrypts
  the keyring; unseal key encrypts the root key
  (https://developer.hashicorp.com/vault/docs/concepts/seal).
- **Unknown term at decrypt => fail closed**: if the term in the prefix isn't in the
  keyring, decryption cannot proceed (no key to select). GCM tag mismatch also fails
  closed.
- **Rewrap is separate**: `sys/rotate` appends a term; re-encrypting existing data to
  the new term is an explicit, optional, background operation - never coupled to
  rotation.

### Secondary examples (same two-tier master-key indirection)

- **MySQL InnoDB tablespace encryption**: two-tier - each tablespace has its own key,
  stored encrypted in the tablespace header under a **master key** in a keyring.
  `ALTER INSTANCE ROTATE INNODB MASTER KEY` generates a new master key and
  **re-encrypts only the per-tablespace key headers**, never the tablespace data.
  Old master keys are retained so data encrypted before rotation stays readable.
  (https://dev.mysql.com/doc/refman/8.0/en/innodb-data-encryption.html)
- **AWS KMS key hierarchy** (https://docs.aws.amazon.com/kms/latest/developerguide/concepts.html):
  a KMS key is a container of versioned **HSM backing keys (HBKs)**. On rotation "a
  new HBK is created and associated ... as the active HBK ... The older HBKs are
  preserved and can be used to decrypt and verify previously protected data. But
  only the active cryptographic key can be used to protect new information." Byte-
  identical policy to Vault terms: **active-for-encrypt, all-retained-for-decrypt.**
- **Postgres TDE proposals** (cluster file encryption, WIP): consistently propose a
  **KEK/DEK split** (a key-encryption-key wraps per-file data-encryption-keys) so
  KEK rotation only rewraps DEKs - same indirection, chosen precisely to avoid
  rewriting encrypted heap/WAL.

**Recommendation for Configd:** this is a solved problem - copy the Vault term model
exactly at the `IntegrityEnvelope` seam. Ciphertext (or envelope) carries a
**key-term/version id**; a keyring maps term -> key material; **new envelopes use the
active term, every historical term is retained for decrypt, unknown term fails
closed.** Never re-encrypt in place on rotation; make rewrap a separate optional
migration. Configd's KMS-SPI (`RootKey`/`WrappedKey`/`KeyId`) already matches the
KEK/DEK + typed-material shape.

---

## Q4. Version markers on every persistent artifact

Convention across every system: a **leading magic + version** on each artifact,
read **before** the integrity check, with **reserved-value discipline** and
**fail-loud on unknown**.

- **Postgres `pg_control`**: `pg_control_version` (PG_CONTROL_VERSION = 1902) and a
  separate `catalog_version_no`; checked at startup, mismatch refuses to start.
  (`pg_control.h`.)
- **Postgres WAL page**: `xlp_magic = XLOG_PAGE_MAGIC (0xD120)` - magic *is* the WAL
  format-version indicator, bumped on incompatible format changes. (`xlog_internal.h`.)
- **SQLite header** (https://www.sqlite.org/fileformat2.html): 16-byte magic
  `"SQLite format 3\0"` (offset 0); **file-format write version** (offset 18) and
  **read version** (offset 19) as *separate* bytes - a clean split enabling "I can
  read this but must treat it read-only" (write>2 => read-only; read>2 => can't open
  at all); `SQLITE_VERSION_NUMBER` (offset 96) records the library that last wrote.
  The read/write split is the notable idea: version compatibility is per-operation,
  not a single number.
- **Kafka log segment record batch** (KIP-32/KIP-98 message format v2): a 1-byte
  **`magic`** at a fixed header offset (v0=0, v1=1, v2=2) lets a broker parse any
  segment written by any historical version. Crucially, in v2 the layout is
  `baseOffset(8) | batchLength(4) | partitionLeaderEpoch(4) | magic(1) | crc(4) |
  attributes(2) | ...`, i.e. **the magic byte sits BEFORE the CRC and is NOT covered
  by it** - deliberately, so a reader can determine the format version *before* it
  knows how to compute/verify the CRC. (v0/v1 put magic *after* the crc; v2 moved it
  ahead precisely to fix this.) Reserved attribute bits are defined-as-zero for
  forward compat. (https://cwiki.apache.org/confluence/display/KAFKA/KIP-98,
  https://kafka.apache.org/documentation/#recordbatch)
- **etcd WAL**: each WAL file opens with a **metadata record** and version handling;
  snap files carry their own headers. Records are CRC-chained. (etcd `wal` package.)

**Where the version lives:** leading `magic (fixed bytes) || version (small int)`,
positioned so it is readable **without** and **before** the integrity/crypto check
(Kafka's magic-before-CRC is the canonical lesson), with **reserved bits/values
defined-as-zero** and **unknown version => hard fail (fail loud), never best-effort
parse.**

### Text / operator-facing grammars (Configd has `_acl/` policy-as-config text)

- **Shebang-style / pragma first line**: the minimum viable version pragma for a
  frozen line-oriented grammar is a mandatory first line like
  `#!configd-acl v1` (or a `#version: 1` pragma). Parser reads line 1, rejects
  unknown versions, and only then interprets the body - the text analogue of a
  leading magic+version.
- **OpenSSH `authorized_keys`**: evolved by adding **named options** (`command=`,
  `restrict`, `cert-authority`, ...) with the rule that an unknown option makes the
  line **fail closed** (the key is rejected), not silently ignored - a discipline
  worth copying for a security-relevant grammar.
- **Prometheus exposition format**: versioned out-of-band via the HTTP
  `Content-Type` (`text/plain; version=0.0.4`) plus `# HELP`/`# TYPE` typed comment
  lines; the newer OpenMetrics carries an explicit `# EOF` terminator so a truncated
  scrape is detectable - a text-format truncation guard worth noting.

**Minimum viable pragma for Configd's frozen text grammar:** a required first line
`#!<grammar-name> v<N>`; unknown `N` => reject the whole file (fail loud); reserve a
comment convention; if the file is security-relevant and truncation matters, add an
explicit end-marker line so a truncated policy file is rejected rather than
partially applied.

---

## Q5. Cursor / resume-token versioning across topology change

The pattern to extract: **a resume token must bind the topology epoch it was minted
under, and the server rejects a mismatched epoch so the client re-hydrates full
state.** The bar systems achieve this in one of two ways - bind-to-shard (reject on
mismatch) or explicit-invalidation-signal (client re-syncs).

- **Kafka consumer offsets - invalidation is explicit, offsets are per-partition.**
  An offset is meaningful **only within a `(topic, partition)`**. Kafka partitions
  can only be *added*, never split/merged, and adding partitions **breaks key->
  partition hashing** for future records - so offsets are never "migrated"; the
  topology change is simply visible as new partitions the consumer must discover and
  start reading from a defined position. The token (offset) is inseparable from its
  partition (its topology unit).
- **Kinesis shard iterators - bound to a shard, parent-before-child lineage.**
  (https://docs.aws.amazon.com/streams/latest/dev/kinesis-using-sdk-java-resharding.html,
  .../after-resharding.html) Resharding is pairwise split/merge producing **child
  shards** with a **`ParentShardId`** lineage; parents go OPEN->CLOSED->EXPIRED. A
  shard iterator is valid only for one shard, and **the consumer must drain each
  parent shard to `SHARD_END` (next-iterator == null) BEFORE reading its children**,
  or it "could read data for a particular hash key out of the order given by the
  ... sequence numbers." An old iterator simply ends (returns null) at the parent's
  close; the client must re-discover children via `DescribeStream`. Sequence numbers
  are per-shard and don't carry across the split.
- **DynamoDB Streams shard iterators** - same shape: shard iterators **expire (15
  min)** and reshard produces new shards; a consumer must walk the parent->child
  shard tree and cannot reuse a parent iterator on a child.
- **etcd watch revision compaction - the cleanest "re-sync full state" contract.**
  (https://etcd.io/docs/v3.5/learning/api/) A watch resumes from a global
  `revision`; when history older than a point is **compacted**, a watch started at a
  compacted revision is **canceled with `ErrCompacted`** ("mvcc: required revision
  has been compacted") and the response carries `compact_revision`. The client's
  only correct recovery is to **re-fetch current state via a Range/Get and resume
  watching from the fresh header revision** - an explicit "your cursor is no longer
  valid, re-hydrate" signal.

**Who does exactly "token binds topology epoch, server rejects mismatch"?** Kinesis
and DynamoDB Streams bind the token to a *shard identity* (the epoch is implicit in
the shard id + parent lineage) and the server ends/expires it on reshard. etcd binds
the token to a *revision* and rejects with `ErrCompacted` when it's out of range.
Kafka makes the binding structural (offset is per-partition; partitions never
reshuffle). All three converge on: **a stale cursor is refused, never silently
reinterpreted, and the client re-hydrates.**

**Recommendation for Configd (multi-shard watches):** mint every resume/watch cursor
with an explicit **shard-map/topology epoch** (and the per-shard position), and have
the server **reject a cursor whose epoch != current epoch** with a distinct
"recompute from scratch" error (etcd's `ErrCompacted` is the model), forcing a full
re-hydrate rather than a silent, possibly-gap-or-reorder resume. Configd's existing
"signed version position" + per-shard watermark should carry the epoch and fail
closed on mismatch.

---

## Q6. Wrapped-key / key-descriptor file formats

Minimum fields the bar demands for a frozen wrapped-DEK-at-rest envelope: **format
version, key id / term, wrapping-algorithm id, wrap nonce/IV, wrapped bytes, and an
AAD binding that pins the key's purpose/scope** so a wrapped key can't be replayed
into a different slot.

- **Vault keyring JSON** (`vault/keyring.go`): per-key fields `Term`, `Version`,
  `Value` (the wrapped/held key), `InstallTime`, `Encryptions`; the file is
  `EncodedKeyring { MasterKey, Keys[] }` encrypted under the root key. Term is the
  key id; Version is the schema/format marker.
- **JWE (RFC 7516)** - the general-purpose versioned wrapped-key envelope. Five
  compact components: `BASE64URL(Protected Header).EncryptedKey.IV.Ciphertext.Tag`.
  The **protected header names the algorithms**: `alg` = key-management/wrapping
  algorithm (e.g. `RSA-OAEP`, `A256KW`), `enc` = content-encryption AEAD. The header
  **is the AAD**: "the Additional Authenticated Data ... be ASCII(Encoded Protected
  Header)" - so the declared algorithms and any bound parameters are authenticated
  by the tag, defeating algorithm-substitution. This is the canonical
  "version/alg-id lives in an authenticated header" pattern.
  (https://www.rfc-editor.org/rfc/rfc7516.txt)
- **age** (https://github.com/C2SP/C2SP/blob/main/age.md): file begins with the
  **version line `age-encryption.org/v1`**, then recipient **stanzas** (each wraps
  the file key for one recipient), then a **header MAC** = `HMAC-SHA-256` over the
  whole header keyed by `HKDF(file key, "header")`. The MAC binds *all* recipient
  stanzas and the version line, so you can't strip/swap a wrapped-key stanza. Clean
  minimal model: leading version string + per-recipient wrapped key + MAC-over-header.
- **AWS KMS ciphertext blob**: an opaque but **internally versioned** structure
  (AWS does not publish the layout but guarantees backward-compatible decrypt of old
  blobs, mirroring the HBK "old versions still decrypt" guarantee). KMS binds an
  **encryption context as AAD**
  (https://docs.aws.amazon.com/kms/latest/developerguide/concepts.html) - key/value
  pairs authenticated with the ciphertext, so a blob decrypts only under the same
  context, the direct analogue of "AAD pins purpose/scope."
- **PKCS#8 `EncryptedPrivateKeyInfo` (RFC 5958)**: `SEQUENCE { encryptionAlgorithm
  AlgorithmIdentifier, encryptedData OCTET STRING }`. The `AlgorithmIdentifier`
  carries the wrapping/KDF alg **OID + parameters** (e.g. PBES2 salt + iteration
  count) - version/alg-id and its params travel *with* the wrapped bytes. General
  lesson: the descriptor is self-describing about how to unwrap.

**What the AAD should bind, for Configd:** the wrapped-DEK envelope's AAD must pin
the **key's purpose and slot/scope** - e.g. `(format-version, key-id/term,
wrap-alg-id, node/scope id, "purpose=dek")` - so a wrapped DEK lifted from one slot
cannot be replayed into another (JWE binds the header; KMS binds the encryption
context; age MACs the whole header - all three prevent exactly this
substitution/replay).

**Minimum frozen wrapped-DEK envelope fields (synthesis of the above):**
`magic || format_version || key_id(term) || wrap_alg_id || wrap_nonce/iv ||
wrapped_dek_bytes || auth_tag`, with the **AAD = (format_version, key_id,
wrap_alg_id, scope/purpose)** and **unknown format_version or wrap_alg_id => fail
closed.**

---

## Where the bar systems disagree (and what fits a node-local-key config store)

1. **Anchor integrity: CRC vs MAC vs external.** Postgres/bbolt/LMDB/SQLite protect
   their head anchor with a **CRC/checksum** - corruption detection only, forgeable.
   CT/age/Vault use a **signature/MAC** - forgery-resistant but only against an
   adversary lacking the key. AVB/dm-verity/TPM put the anchor in **external tamper-
   evident storage** - the only defense against anchor rollback itself.
   *For Configd:* a plain CRC is insufficient for the stated adversary; use a
   **keyed MAC** over `(index/count + hash-over-head)` under the node key, and
   **document that anchor-rollback-to-a-prior-valid-MAC is the residual gap** absent
   TPM/RPMB or a remote witness. Do not overclaim.

2. **Does the anchor guarantee "never behind" too?** Postgres deliberately lets
   `pg_control` **lag** the WAL and reconciles on replay; etcd commits the
   consistent-index **in the same txn** as data so it neither leads nor lags.
   *For Configd:* the etcd "anchor + data in one atomic commit" is stronger and
   simpler to reason about for a small state file; prefer it. If you must separate
   them, follow Postgres: **write data durable first, anchor last, and make recovery
   tolerate an anchor that lags** (never one that leads).

3. **Single live copy vs dual slot.** rename-based (one copy) vs bbolt/LMDB dual
   meta (two copies). *For Configd:* **dual-slot with sequence-number + MAC** wins,
   because the sequence number is your anti-rollback index and the second slot is
   your torn-write fallback.

4. **Cursor invalidation: structural vs signaled.** Kafka makes it structural
   (the offset is independent of the partition); etcd signals `ErrCompacted`. *For Configd:* **bind the
   epoch into the cursor and signal an explicit re-hydrate error** (etcd model) -
   safer than hoping clients notice a structural change.

---

## One-page checklist: what the bar demands of Configd's frozen format

**Threat model (state it, verbatim, up front)**
- [ ] Define the boundary: detect tamper/truncation/rollback by an adversary with
      **disk write access but NOT the key**; adversary-with-key OR anchor-rollback-
      to-a-prior-valid-MAC is **out of scope** without TPM/RPMB/remote witness
      (same line dm-verity, dm-integrity, AVB, Vault draw). No overclaiming.

**Every persistent artifact (log segment, state file, snapshot, envelope, keyring, ACL text)**
- [ ] Leading `magic || format_version`, **readable before any CRC/crypto check**
      (Kafka magic-before-CRC lesson). Reserved fields defined-as-zero.
- [ ] **Unknown version => fail loud / fail closed.** Never best-effort parse.
- [ ] Consider a SQLite-style **read-version vs write-version split** if forward-
      compatible read of newer files matters.

**Append-only log - truncation/rollback**
- [ ] Per-record CRC + prev-chain for **corruption/torn tails** (Postgres model) -
      but document that these **cannot** detect adversarial truncation (a valid
      prefix is a valid log).
- [ ] An **authenticated head anchor** that MACs **(record count/index + hash-over-
      head)** under the node key (CT STH shape), stored in a dual A/B slot with a
      **monotonic sequence number**; reject a slot whose sequence < last accepted.

**Small mutable state / anchor file**
- [ ] **Dual-slot A/B, each with sequence-number + keyed MAC**; open = highest-valid-
      sequence slot (bbolt/LMDB pattern, MAC instead of plain checksum).
- [ ] Keep it <= one sector for atomic write where possible (Postgres pg_control).

**Fsync ordering**
- [ ] **Data/log durable BEFORE the anchor; anchor written LAST; fsync between.**
- [ ] Never ack a write before the anchor covering it is durable.
- [ ] Recovery **tolerates an anchor that lags** data (replay forward); never trusts
      an anchor ahead of durable data. (etcd: commit anchor + data in one txn if you
      can.)

**Key rotation (never destroys data)**
- [ ] Ciphertext/envelope carries a **key-term/version id** (Vault 4-byte term +
      1-byte alg-version prefix).
- [ ] **Newest term for new writes; ALL old terms retained forever for decrypt**
      (Vault / AWS KMS HBK). Rotation = **append a term**, never re-encrypt in place;
      rewrap is a separate optional migration.
- [ ] Keyring persisted **encrypted under the root/unseal key**, itself versioned.
- [ ] **Unknown term at decrypt => fail closed.**

**Wrapped-DEK / key-descriptor envelope**
- [ ] Fields: `magic || format_version || key_id(term) || wrap_alg_id ||
      wrap_nonce/iv || wrapped_dek || auth_tag`.
- [ ] **AAD binds (format_version, key_id, wrap_alg_id, scope/purpose)** so a wrapped
      key can't be replayed into another slot (JWE header-as-AAD / KMS encryption-
      context / age header-MAC).
- [ ] Unknown `format_version`/`wrap_alg_id` => fail closed.

**Cursors / resume tokens (multi-shard watches)**
- [ ] Every cursor **binds the topology/shard-map epoch** + per-shard position.
- [ ] Server **rejects epoch mismatch with an explicit re-hydrate error** (etcd
      `ErrCompacted` model); client re-syncs full state. Never silently reinterpret
      a stale cursor.

**Operator-facing text grammars (`_acl/` policy-as-config)**
- [ ] Mandatory first-line pragma `#!<grammar> v<N>`; unknown N => reject whole file.
- [ ] Unknown option/directive => **fail closed** (ssh authorized_keys discipline),
      not silently ignored.
- [ ] If truncation matters, an explicit **end-marker** line (OpenMetrics `# EOF`)
      so a truncated policy is rejected, not partially applied.

---

### Primary sources cited
- PostgreSQL source: `src/include/catalog/pg_control.h`, `src/include/access/xlog_internal.h`,
  `src/include/access/xlogrecord.h`, `src/backend/access/transam/xlog.c`;
  https://www.postgresql.org/docs/current/wal-internals.html
- RFC 6962 (Certificate Transparency, STH): https://www.ietf.org/rfc/rfc6962.txt
- Crosby & Wallach, "Efficient Data Structures for Tamper-Evident Logging," USENIX Security 2009
- bbolt: https://github.com/etcd-io/bbolt (README, dual meta pages / txid)
- LMDB: http://www.lmdb.tech/doc/ (dual meta page design)
- SQLite file format: https://www.sqlite.org/fileformat2.html
- dm-verity: https://docs.kernel.org/admin-guide/device-mapper/verity.html
- dm-integrity: https://docs.kernel.org/admin-guide/device-mapper/dm-integrity.html
- Android Verified Boot rollback protection: https://source.android.com/docs/security/features/verifiedboot/verified-boot
- etcd consistent-index: `server/storage/schema/cindex.go`; watch/compaction: https://etcd.io/docs/v3.5/learning/api/
- Vault: `vault/keyring.go`, `vault/barrier_aes_gcm.go`;
  https://developer.hashicorp.com/vault/docs/internals/security ,
  https://developer.hashicorp.com/vault/docs/concepts/seal
- AWS KMS key hierarchy + encryption context: https://docs.aws.amazon.com/kms/latest/developerguide/concepts.html
- MySQL InnoDB tablespace encryption / master-key rotation: https://dev.mysql.com/doc/refman/8.0/en/innodb-data-encryption.html
- Kafka message format v2 (KIP-32, KIP-98): https://cwiki.apache.org/confluence/display/KAFKA/KIP-98 ,
  https://kafka.apache.org/documentation/#recordbatch
- Kinesis resharding: https://docs.aws.amazon.com/streams/latest/dev/kinesis-using-sdk-java-resharding.html ,
  .../kinesis-using-sdk-java-after-resharding.html
- JWE: RFC 7516 https://www.rfc-editor.org/rfc/rfc7516.txt
- age: https://github.com/C2SP/C2SP/blob/main/age.md
- PKCS#8 EncryptedPrivateKeyInfo: RFC 5958

---

# Part III-b - §9 Where this design is implemented

Consolidated from §4/§5/§6 (each row cites its owner section) into the production classes that
carry it.

## 9.1 Production seams

| Seam | Change | Source |
|---|---|---|
| `IntegrityEnvelope` | v3 layout (scopeId all postures; keyTerm in HMAC; AAD 44 B), CRC-before-version, `reserved!=0` throw, keyTerm=0 domain rule | §2.2 |
| `SegmentKeyManager` / `ConfigdServer` boot | keyring load (`unsealFrom` full term map + activeTerm); delete hardcoded term=1 (`ConfigdServer:1325`); rotate call path (admin op) | §2.18, §4 A2 |
| NEW `AnchorFile` writer + `KeyringCodec` + `TopologyDescriptor` codec | dual-slot pwrite/fdatasync writer; container headers; §2.4-2.7 layouts | §2.3-2.7, §7 sketch |
| `RaftLog` | delete legacy raw-record fallback; recovery contiguity/term-monotonicity/snapshot-join checks; conflict-truncation anchor-lower-first; compaction anchor-last; remove bare snapshot-meta | §2.8, §6 §1 |
| **scopeId assert - enumerated read call-sites** (the whole cross-shard-splice defense; a blanket "every path" is where a build slips one) | assert `scopeId==expected` at: WAL replay (`RaftLog` ctor); anchor/node-anchor/keyring/topology open; snapshot persist->reload (`ConfigStateMachine`); InstallSnapshot re-persist->reload (`RaftNode`). Add a negative test per site. | §2.2 |
| `DurableRaftState` | REMOVED - term/vote through the anchor (persist-before-memory preserved) | §2.4, §6 §1 |
| `RaftNode.flushDurable` / `appendEntries` | anchor write+fdatasync joins the barrier (leader before durableIndex advance; follower before ACK) | §6 §1 |
| fsync-failure policy | WAL/anchor fsync throw => no advance, no ack, process exit; wire `FaultInjectingStorage.failNextSyncs` live-RaftNode cell | §6 §4.1 |
| `FileStorage` | CRC32C frames; WAL container header; same-device anchor enforcement | §2.3, §2.8, §6 §4.4 |
| `ConfigStateMachine` | delete legacy trailer forms (a)/(c) | §2.9 |
| `AuditLog` | record header (RAUD v1) inside the chain input; periodic node-anchor head advance (K=64/1 s) | §2.10, §6 §1 |
| `PolicySerializer` / `AclConfigPolicyLoader` | line-1 pragma parse + validateAclWrite reject | §2.12 |
| `EdgeFrameCodec` / watch plane | cursor+SUBSCRIBE epoch fields; `STALE_TOPOLOGY=12`; `ShardMap.epoch()` from the descriptor | §2.11 |
| `FrameCodec` | epoch reject-if-nonzero | §2.15 |
| `SigningKeyStore` | durable write (temp+fsync+rename+dir), docstring hex typo, writeForTest chmod no-op | §2.14 |

## 9.2 Test coverage (new/rewritten)

- **Envelope v3**: `rolledFormatVersionThrows` (kept), `reservedNonZeroThrows`,
  `corruptHeaderReportsCrcNotVersion`, `keyTermZeroOnlyUnderKeyringMagic`, scope-assert
  (cross-shard replay refused; in-place scopeId forge fails MAC/tag), downgrade rows (kept).
- **Anchor**: torn-slot fallback; both-slots-invalid REFUSE vs FRESH; `W<A` REFUSE; accept-forward;
  Step-2.5 term-witness REFUSE; anchor-lower-before-truncate (legal truncation non-refusing);
  contiguity/term-monotonicity/snapshot-join REFUSE cells; `forgedVotedForRefused` (moved from
  DurableRaftState); break-glass rebuild audit record.
- **Crash matrix**: §6 §2's interleavings (leader flush x5, follower x4, compaction x3) as
  seedxcrashpoint cells extending `SnapshotCrashRecoveryTest`/`AdversarialCrashRecoveryTest`;
  `GroupCommitDurabilityTest` + anchor-durable-before-commit assert; `VotePersistenceCrashTest`
  rewritten to the anchor; kill-9 matrix rerun.
- **fsync-failure**: live-RaftNode `failNextSyncs` on WAL and anchor seams => no-advance/no-ack/exit.
- **Rotation**: write@N -> rotate -> write@N+1 -> restart -> both decrypt; crash-mid-rewrap (both
  sides of the signing-key swap); keyring absent-with-data REFUSE; unknown-term REFUSE; keyring
  entry strip/swap/truncate REFUSE; wrapped-root cross-slot replay fails AAD; rotate-overflow
  REFUSE (slot capacity).
- **Versioning**: per §5 §4's table - `walFileHeaderBadMagic/HigherVersion`, `emptyWalFileIsFresh`,
  `nonEnvelopedWalRecordRejectedUnderKey`, `snapshotTrailerLegacyEmptyRejected`/`Raw8Rejected`/
  `UnknownTailTolerated`, `auditRecordBadMagicRejected`, `auditVersionIsChainBound`,
  `topologyDescriptorTamperedRefusesStart`, `topologyEpochZeroRejected`, `reshardNChangeStillRefused`,
  `nonZeroReservedEpochRejected`, `staleEpochCursorRejectedWithReHydrate`, `epochZeroCursorIsFrameCorrupt`,
  `subscribeCarriesEpoch`, resharding negative (N=a cursor at N=b), pragma cells (§7).
- **Node anchor**: topology cross-check REFUSE (epoch rollback, N mismatch); audit-head REFUSE below
  anchored head; R-e window documented in the runbook.
- **Kept byte-identical**: FileStorage torn-tail semantics; `WalRecordIntegrityTest` torn-tail row;
  the existing ENOSPC rows plus a new boot-time anchor-preallocation ENOSPC cell.

## 9.3 Golden fixtures

Regenerate (edge wire epoch fields): `EdgeFrameGoldenBytes.java`, `EdgeFrameGoldenBytesGenerator.java`,
`EdgeFrameCodecGoldenFixtureTest.java`, `EdgeFrameCodecV2GoldenFixtureTest.java`,
`EdgeFrameCodecV3GoldenFixtureTest.java`. NEW goldens: envelope v3 (all three postures), anchor
slot, keyring slot, topology descriptor, audit record. Raft-wire goldens (`GoldenFixtures.java`,
`WireCompatGoldenBytesTest.java`) unchanged (epoch stays 0; only a reject test is added).
`RaftArtifactMagic` gains RANC/RNAN/RKYR/RTOP/RAUD/RWLF; RFST marked retired-reserved.

## 9.4 Measurement + docs obligations

- **EC2 re-measure of the single-group knee under anchor-before-ack** (bound: -10-40 %, likely
  -10-20 %; lever: group-commit linger/batch). Ship-gate: measured regression <= ~25 % or an
  explicit operator acceptance.
- RFC/operator docs: rotation runsheet (both operations, step-by-step, with the crash-safety
  argument); break-glass procedure; R-a..R-e residuals in the threat-model doc; the runbook rows
  for REFUSE diagnostics (each gate's message names the artifact and the recovery options);
  deployment doc: same-device rule, ext4/xfs preallocation caveat, audit K tunable.
- The GCM AAD binds the artifact-type magic but not the Raft groupId, so cross-group at-rest integrity
  leans on the Raft log-consistency layer and the per-shard durability anchor, not the envelope. Binding
  groupId into the AAD is the tighter belt before an N>1 deployment relies on envelope-level cross-group
  integrity. This is recorded as a present-day limitation in
  [`known-limitations.md`](../operations/known-limitations.md).

---

# Part III-c - §10 Freeze summary

Every decision here is permanent once shipped. §0 lists the eight load-bearing decisions and the
documented residuals; §2 has the exact byte layouts, including the frozen constants (keyring slot
stride 64 KiB, anchor slot stride 512 B, the magic/version registry in §2.1); §4 and §6 have the
completeness and crash-interleaving proofs; §7 is the compile-checked prototype that validated the
byte layouts before the build; §8 is the prior art each mechanism draws on.

Two calls mattered most at freeze time, because a frozen format admits no do-over:

- **Close R-a' now, or accept it as a documented residual?** A within-term `votedFor` rollback
  (replaying an older same-term anchor slot) is an Election-Safety hazard, not mere staleness: it
  can cause a double vote and cluster divergence, and the term-witness gate does not catch it
  (§0). The decision was to close it now by building the peer-quorum `AnchorWitness` (§A1.7,
  `docs/design/anchor-witness-peer-quorum-2026-07-04.md`) rather than merely documenting it.
- **Add the node-anchor's per-shard liveness binding now, or accept R-f as documented?** The
  node-anchor's payload cannot grow again after the freeze, so a single-shard wipe-to-FRESH (silent
  data loss on N=1 or a degraded quorum) could only ever be closed at this moment. The decision was
  to add it: `shardAnchorDigest` (§2.5, §A1.6).

Both shipped, as described in "What shipped, and where it differs from the design below" at the
top of this document.

---

# §11 Adversarial review: findings and dispositions

An adversarial review attacked this design on seven axes (matrix completeness, recovery gates,
the F-1 term-witness gate, rotation, version markers, cross-item invalidation, byte-level
ambiguity), read §0-§10 in full, compiled and ran the prototype (75/75), ground-checked load-bearing
claims against real source (`RaftNode`/`RaftLog`/`SegmentKeyManager`/`ConfigdServer`), and validated
its one byte-level break with a proof of concept. **Verdict: the durability/rollback kernel and
crypto are SOUND - no blocker-class "claims to detect X but doesn't."** Nine findings; all
dispositioned below.

## 11.1 Five axes attacked hard and clean

- **F-1 term-witness gate (the highest-value target): SOUND.** `anchor.currentTerm >=
  lastWALTerm` is maintainable AND the code already enforces the ordering -
  `handleAppendEntries` calls `becomeFollower -> durableState.setTerm` (persist-before-memory,
  fsync'd) at `RaftNode:1592-1593,1827-1830` BEFORE `log.appendEntries` at `:1605`. No legal
  crash / pre-vote / stepdown / follower-append interleaving false-positives. -> frozen as the §2.17
  invariant (do not fold/batch/defer the term fsync).
- **Anchor-before-ack TOCTOU: SOUND at the quorum level.** No window leaves an acked entry on fewer
  than a quorum of anchor-covered nodes (incl. the leader-commits-via-follower-quorum-with-own-A<i
  case - the entry survives on the follower quorum). F-2's "committed-and-client-acked" reframing is
  what makes it hold.
- **Mixed anchor rollback / snapshot accept-forward: SOUND.** All anchor fields are bound in one
  MAC'd record (no field-mixing); dual-slot yields only whole-record rollback (= R-a); compaction
  accept-forward requires an authenticated blob@B that exists only for legitimately-committed state.
- **Rotation / keyring: SOUND.** Dual-slot signing-key handover is crash-safe on both sides of the
  swap; `keyTerm=0` is locked to `KEYRING_MAGIC`; delete-keyring => REFUSE; unknown-term => REFUSE;
  64 KiB overflow => loud refuse (reads still work, no brick); `entryCount`/`wrappedLen` overflow is
  post-outer-MAC (unforgeable).
- **Version markers / fail-closed: SOUND.** CRC-before-version opens no hole; unknown
  magic/algId/version-0/rolled/reserved!=0/downgrade all fail closed; the CommandCodec carrier holds
  (decode only on post-WALE-unwrap committed bytes, `ConfigStateMachine:241,679`).

## 11.2 Findings and dispositions

| # | Severity | Finding | Disposition |
|---|---|---|---|
| RT-1 | MAJOR | Topology descriptor self-contradicted: one passage said 16 B/`reserved:u16`, another said 18 B/`reserved:u32` - two implementers would have produced a mutually-unreadable `topology-descriptor.dat`, and the prototype's self-test never exercised the normative layout. | **FIXED.** Reconciled to **18 B / `reserved:u32`** (§2.7, the tested layout); re-ran the prototype against the now-normative layout - 18 B, round-trip OK. |
| RT-2 | MAJOR | A within-term `votedFor` rollback (from merging the vote into the anchor) is an **Election-Safety** (divergence) residual, not the "staleness" the framing implied; matrix row 4 obscured this. | **FIXED.** Split into **R-a' (SAFETY)** in §0; matrix rows 4/4b relabeled so the real worst case is visible; closed by the peer-quorum `AnchorWitness` (§A1.7). |
| RT-3 | MAJOR | A full single-shard wipe (delete anchor, truncate WAL to 0, delete snapshot) laundered "absent+non-empty => REFUSE" into "absent+empty => FRESH" - silent data loss on N=1 or a degraded quorum; the original 60-byte node-anchor could never detect it once frozen. Unlisted in the matrix. | **CLOSED.** Added **R-f** (§0), matrix row 15b, and the node-anchor's `shardAnchorDigest` (a 32-byte per-shard digest, periodic-cadence, fits free in the 512-byte slot) - the one freeze-window design choice that could only be made now. |
| RT-4 | MINOR | The scopeId assert was mandated in general terms but the read call-sites were not enumerated; a build could slip one. | **FIXED.** §2.2 and §9.1 enumerate the at-rest read call-sites (WAL replay, anchor/keyring/node-anchor/topology open, snapshot reload, InstallSnapshot reload) with a per-site negative test. |
| RT-5 | MINOR | `WatchCursorV2.decode` is client-reachable and unauthenticated with no min-length guard, risking an uncaught `BufferUnderflowException` instead of FRAME_CORRUPT. | **FIXED.** §2.11 freezes: length < 12 => FRAME_CORRUPT; every client-facing decoder maps a buffer-underflow -> FRAME_CORRUPT. |
| RT-6 | MINOR | `gid`'s range was not reserved to exclude `NODE_SCOPE` (0xFFFFFFFF), risking scope confusion if ever reached. | **FIXED.** §2.2 freezes `gid in [0, NODE_SCOPE)`. |
| RT-7 | INFO | Container-header `flags`/`fileVersion` and slot `recordLen` are unauthenticated by design; verified flips yield only a clean REFUSE (slot offsets are constants; `recordLen` is bounded to `[0, stride-4]`). | **FROZEN as an invariant** in §2.3: the header may never gate a security decision; a future `flags` bit must not become an adversary-flippable control. |
| RT-8 | INFO | The GCM nonce-domain guardrail lived only in a code comment; the new per-shard id invites a per-shard counter split that would break GCM. | **PROMOTED** into normative §2.2: segment identity stays per-magic/node-global; `scopeId` is AAD-only and must not key the segment or split the counter. |
| RT-9 | INFO | The break-glass flag is safe today (a launch-only system property) but must stay that way. | **FROZEN as an invariant** in §2.17: break-glass must be launch-only, never read from a data-dir file. |

## 11.3 Net

The topology contradiction (RT-1) is fixed and re-verified; every MINOR/INFO finding is folded into
the normative text as a frozen invariant. The five hard-attacked axes came back clean - the kernel
is sound.
