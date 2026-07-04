# Configd Frozen-Format v1 — Permanent Format Design & Adversarial Review

**Status: RATIFIED — 2026-07-04. Build arc (Group A) authorized. Formats freeze at the release tag.**
Repo `main @ 012e213` · designed 2026-07-03, ratified 2026-07-04 · Group A of the production-standard
gap assessment (`docs/readiness/production-standard-gap-assessment-2026-07-03.md`, items A1–A6).

> **Ratification record (2026-07-04).** The operator ratified all twelve §10.2 decisions. Items 1–10
> are accepted as written. The two freeze-window severity calls (items 11–12, surfaced by the
> red-team tear RT-2/RT-3) were ruled on explicitly:
>
> - **Item 12 (R-f, single-shard wipe→FRESH):** **ADD the per-shard liveness binding NOW.**
>   `NODE_ANCHOR_PAYLOAD` gains `[shardAnchorDigest:32]` = SHA-256 over the sorted
>   `(gid, lastDurableIndex)` pairs, refreshed on the node-anchor's existing periodic cadence; a
>   shard reset to index 0 changes the digest ⇒ boot REFUSE. Payload grows **60 B → 92 B** (still
>   free in the 512-B slot). This raises R-f from silent-loss to a detected node-anchor rollback (=
>   R-a). §2.5, §A1.6, and matrix row 15b are amended accordingly by the build arc.
> - **Item 11 (R-a′, within-term `votedFor` rollback = Election Safety):** the "accept documented"
>   default was **REJECTED**. Analysis during ratification established that **un-merging `votedFor`
>   does NOT remove R-a′** — the residual is intrinsic to an un-witnessed durable vote (today's bare
>   `raft.persistent_state` already carries it; matrix row 4b closer is "X only"), so a separate
>   state file relocates the hole without closing it. **Resolution: KEEP the ⟦SEC-MERGE⟧ merge
>   (item 1) — it is strictly better and improves the term-crossing case — and CLOSE R-a′ by
>   BUILDING an `AnchorWitness` (§A1.7, previously interface-only).** The chosen implementation is a
>   **peer-quorum witness**: each node's monotonic `anchorSeq` (and its per-term vote) is witnessed
>   by a quorum of peers over the existing Raft channel; a node REFUSES to start or to grant a vote
>   below the highest value a quorum has witnessed from it. This closes R-a′ exactly where
>   split-brain is possible (N≥3); N=1 has no witness but cannot split-brain (documented). A focused
>   security+reliability design pass produces the witness design addendum
>   (`docs/design/anchor-witness-peer-quorum-2026-07-04.md`) BEFORE it is built; it lands as an added
>   sub-gate alongside Gate 3 (the anchor/vote path). R-a (freshness, N=1) remains a documented
>   residual, closable later by an external-store witness through the same SPI.
>
> - **WAL per-record hash chain (added during Gate 2, operator-mandated).** The Gate 2 red-team proved
>   the originally-drafted §2.8 (contiguity + term-monotonicity only) does NOT catch an index-preserving,
>   term-monotonic **interior content rollback**. The operator ruled to build the charter's per-record
>   SHA-256 hash chain (each authenticated WAL record binds `prevHash = H(predecessor)`), which closes
>   it. §2.8 and matrix row 8b are amended to the as-built, red-team-verified reality; keyless stays
>   byte-identical (no chain).

This document is the permanent design for every format Configd persists or speaks: it closes the
two frozen-format kernel blockers (A1 no truncation/rollback anchor; A2 data-destroying key
rotation) and the four versioning gaps (A3 policy grammar, A4 cursor topology epoch, A5 wrapped-key
format, A6 command/chunk self-versioning) as ONE coherent design, with completeness proofs. It was
produced by a five-lane design team (reference-researcher, security LEAD, protocol, reliability,
Java engineering) with cross-lane adversarial review, a compile-checked sketch (75/75 self-test
cases green on JDK 25), and a final red-team tear.

**Document structure and precedence.** Part I (§0–§3) is **normative** — where any later section
disagrees, Part I governs. Part II (§4–§6) is the three lanes' full design records and proofs,
included verbatim (they are the evidence; a handful of their statements are superseded by the
cross-lane resolutions tabulated in §3). Part III (§7–§10) is the compile-checked sketch, the
prior-art report, the consolidated build-arc obligations, and the ratification checklist.

**Clean break.** Nothing is shipped. These formats are designed correct-from-scratch with no
migration/compat baggage; legacy acceptance paths in the current code are deliberately DELETED by
this design (§2.9, §3). Once the build arc lands and a release is tagged, these formats never
change again — the only future doors are the version markers themselves.

---

## §0 Executive summary

**The threat model** (normative; §1): the design detects tamper, truncation, rollback, reorder,
splice, and cross-artifact/shard/node replay by an adversary with **filesystem write access but no
key material**. Rollback of the anchor itself to a prior legitimately-authenticated state, and an
adversary holding the key, are explicitly out of scope (the dm-verity/AVB/Vault boundary) — with
one carve-out won during review: an anchor rollback that crosses a term/vote boundary the WAL
witnesses IS detected (§2.17 Step 2.5).

**The eight load-bearing decisions:**

1. **A durable authenticated anchor, dual-slot, per shard** (`raft-anchor`) carrying
   `{anchorSeq, currentTerm, votedFor, lastDurableIndex, lastDurableTerm, snapshotIndex,
   snapshotTerm}` — and **`raft.persistent_state.dat` and the bare `raft-log.snapshot-meta.dat`
   are REMOVED, merged into it** (⟦SEC-MERGE⟧, a ratification item). Truncating or rolling back
   any committed-and-acked data now trips a fail-closed REFUSE at recovery.
2. **Anchor-before-ack**: the anchor fsync joins the existing fsync-before-ack barrier (leader
   self-vote and follower AppendEntries ACK both wait for it). One extra ordered fdatasync per
   group-commit batch; honest knee bound −10–40 % (likely −10–20 %), EC2 re-measure is a build-arc
   obligation. The Postgres-style lagging anchor was REJECTED — a lag window silently loses acked
   data under this threat model.
3. **IntegrityEnvelope FORMAT_VERSION 2→3**: adds an authenticated **`scopeId`** (shard binding —
   closes a verified cross-shard splice hole) and **`keyTerm` in the HMAC posture** (term-versioned
   integrity keys). CRC-before-version parse order; `reserved != 0` now refused.
4. **A persisted, versioned keyring** (`raft-keyring`, the Vault model; doubles as the A5
   wrapped-key format): per-term **independent random roots**, wrapped under a KEK derived from the
   signing key; rotation appends a term and retains every old term forever; signing-key rotation
   rewraps-before-swap (crash-atomic, dual-slot). **The documented data-destroying rotation
   becomes impossible by construction**; boot no longer hardcodes term=1.
5. **New recovery-time checks** that make per-record authentication meaningful: WAL contiguity,
   term monotonicity, snapshot-join, the term-witness gate, and a mandatory reader `scopeId`
   assert. (Verified: today recovery checks NONE of these — reorder/splice is currently silent.)
6. **A node-level anchor** (`node-anchor`) binding the topology descriptor (epoch + N) and the
   audit-log chain head — topology rollback and audit-log truncation (outside a bounded tail
   window) become detected.
7. **Every artifact versioned**: one convention (self-versioned `[magic][version]` /
   carrier-versioned+assert / documented-export), version-0-illegal, MBZ-enforced, unknown ⇒ fail
   loud and closed; a topology descriptor replaces the plaintext shard-count file; the policy
   grammar gets a mandatory `#!configd-acl v1` pragma; watch cursors and SUBSCRIBE resumes bind a
   topology epoch (`STALE_TOPOLOGY` ⇒ client re-hydrates); CRC unified to CRC32C system-wide.
8. **fsync-failure policy frozen**: a WAL- or anchor-fsync throw/lie ⇒ no durable-advance, no ack,
   **process exit** (the fsyncgate rule) — closing assessment gap 2.1-6 consistently.

**Cost summary** (per §6): leader flush and follower append go 1→2 fsyncs (the second is a 512-B
in-place fdatasync of a preallocated slot, amortized per batch); term/vote writes get CHEAPER
(3 fsyncs→1); compaction −2; conflict truncation +2 (rare). Anchor ENOSPC is impossible after boot
(preallocation, ext4/xfs).

**Residuals (documented, not overclaimed; severity split per the red-team tear, §11):**
- **R-a (freshness)** anchor/keyring/whole-datadir rollback to a prior valid state, *within a term* —
  stale reads / lost recent writes; detected only when the rollback crosses a WAL-witnessed term
  boundary (Step-2.5, matrix 17d); otherwise requires the optional external `AnchorWitness`
  (interface specified, not built).
- **R-a′ (SAFETY — Election Safety)** a *within-term* rollback of the merged anchor's `votedFor`
  (replay an older slot image that recorded no vote / a different vote at the same term) is NOT
  caught by the term-witness gate (term unchanged; votes are not WAL-witnessed) and can cause a node
  to vote twice in one term ⇒ two leaders ⇒ divergent committed entries. It is inside R-a's
  locally-undetectable class, but its worst case is cluster divergence, not staleness — called out
  separately so the ratifier sees the true severity. Only `AnchorWitness` closes it. **This is a
  ratification decision (§10.2 item 11), not a silent acceptance.**
- **R-b** adversary with the signing key. **R-c** `VerifyKeyExporter` DER export unanchored.
  **R-d** state fields cleartext in HMAC-only posture. **R-e** audit-tail truncation inside the last
  ≤64-record/≤1-s un-anchored window.
- **R-f (data-loss via wipe→FRESH)** deleting `raft-anchor` + truncating `raft-log.wal` to 0 +
  deleting the snapshot blob for a single shard launders the "absent + non-empty ⇒ REFUSE" case into
  "absent + empty ⇒ FRESH" (silent empty bootstrap). Multi-replica self-heals via re-sync (≈ disk
  replacement); single-replica / degraded-quorum / N=1 = permanent silent loss. The node-anchor
  binds shard *count* but not per-shard liveness, so it cannot detect it — and its 60-byte payload
  freezes forever. **This is a ratification decision (§10.2 item 12): add a per-shard liveness
  binding to the node-anchor NOW (the only freeze window) or accept R-f documented.**
  **CLOSED (AS-BUILT Gate 3b):** item 12 was ratified ADD — the 92-byte node-anchor now binds a
  `shardAnchorDigest` over per-shard `(gid, lastDurableIndex)`; a wiped shard boots FRESH and the boot
  cross-check REFUSEs. R-f is now DETECTED (matrix 15b), reduced to R-a (node-anchor rollback → `X`).

---

## §1 Threat model (normative)

> The at-rest anchor and keyring detect **tamper, truncation, rollback, reorder, splice, and
> cross-artifact/shard/node replay** performed by an adversary who has **filesystem write access to
> the data directory but does NOT hold the key material** (the cluster signing key, which is the
> root of every derived key and, per D-1, lives OUTSIDE the data directory —
> `ConfigdServer.java:1366-1397`). Two things are **explicitly out of scope**, exactly where
> dm-verity, dm-integrity, Android Verified Boot, and Vault draw the same line (§8, prior-art §1c):
> (a) **rollback of the anchor itself** to a prior *legitimately-authenticated* state — undetectable
> without external monotonic storage (TPM/RPMB NV counter) or a remote witness, because a valid
> older state is byte-for-byte a valid state — **except** where the rollback crosses a term/vote
> boundary the WAL witnesses, which the Step-2.5 term-witness gate DOES detect; (b) an adversary
> holding **both** the disk **and** the key, who can forge anything. Crash-consistency (torn tails,
> partial writes, bit-rot) is a *non-adversarial* fault handled by CRC + dual-slot + fsync ordering,
> and MUST NOT be conflated with the adversarial case — a design that bricks a node on a legal
> crash or a legal Raft transition fails just as surely as one that misses an attack.

Optional hardening for (a): the `AnchorWitness` SPI (§4 A1.7) — external monotonic storage of
anchor sequence numbers; interface frozen, implementation not built in v1.

Auth-off (keyless) posture carries **no adversarial guarantees** (as today): envelopes are
CRC-only. Every guarantee in this document assumes the keyed-HMAC or encrypting posture.

---

## §2 The frozen formats (normative byte layouts)

All integers big-endian (`ByteBuffer` default). One CRC family system-wide: **CRC32C
(Castagnoli)**; a container CRC is corruption-only — authentication is always an
IntegrityEnvelope MAC/GCM-tag.

### 2.1 Magic & version registry

| Magic | Value | ASCII | Artifact | Scope |
|---|---|---|---|---|
| `WALE_MAGIC` | `0x5257414C` | RWAL | WAL entry envelope | per-shard, `scopeId=gid` |
| `SNAP_MAGIC` | `0x52534E50` | RSNP | snapshot blob envelope | per-shard, `scopeId=gid` |
| `STATE_MAGIC` | `0x52465354` | RFST | **RETIRED** (state merged into anchor); value reserved forever, never reused | — |
| `ANCHOR_MAGIC` | `0x52414E43` | RANC | per-shard anchor (container header + slot envelopes) | per-shard, `scopeId=gid` |
| `NODE_ANCHOR_MAGIC` | `0x524E414E` | RNAN | node anchor (container header + slot envelopes) | node, `scopeId=NODE_SCOPE` |
| `KEYRING_MAGIC` | `0x524B5952` | RKYR | keyring (container header + slot envelopes) | node, `scopeId=NODE_SCOPE`, envelope `keyTerm=0` |
| `TOPO_MAGIC` | `0x52544F50` | RTOP | topology descriptor envelope | node, `scopeId=NODE_SCOPE` |
| `AUDIT_MAGIC` | `0x52415544` | RAUD | audit record header (chain-bound) | node |
| `WAL_FILE_MAGIC` | `0x52574C46` | RWLF | WAL container file header (`raft-log.wal`, `raft-log.tmp.wal`, `security-audit.wal`) | container |
| `SNAPSHOT_TRAILER_MAGIC` | `0xC0FD7A11` | — | state-machine snapshot TLV trailer (existing, kept) | — |
| SigningKeyStore magic | `0xC0DF51C5` | — | `signing-key.bin` (existing, kept; version=1) | node |

Version constants: `IntegrityEnvelope.FORMAT_VERSION = 3` (u16); container `fileVersion = 1` (u8);
`keyringFormatVersion = 1` (u16, inner); TopologyDescriptor inner `formatVersion = 1` (u16);
audit `recordVersion = 1` (u8); edge wire versions `0x01/0x02/0x03`; raft wire `0x02`;
`NODE_SCOPE = 0xFFFFFFFF`. Reserved-value discipline (all formats): magic 0 illegal; version 0
illegal; version MAX (`0xFF`/`0xFFFF`) reserved as the future escape, unallocated in v1; MBZ
reserved fields are 0 on write and **checked** ⇒ non-zero fails closed. Unknown magic / higher
version / version 0 / legacy un-versioned form ⇒ fail loud, fail closed, never best-effort parse.

### 2.2 IntegrityEnvelope v3 (the shared authentication carrier)

```
Header (8 B, all algIds):   [magic:4][formatVersion:2 = 3][algId:1][reserved:1 MBZ, checked]

algId=0 NONE (keyless):     header ‖ [scopeId:4] ‖ [payload:N] ‖ [CRC32C:4]
algId=1 HMAC_SHA256:        header ‖ [scopeId:4] ‖ [keyTerm:4] ‖ [payload:N] ‖ [MAC:32] ‖ [CRC32C:4]
                              MAC = HMAC-SHA256(K, magic‖fmtVer‖algId‖rsv‖scopeId‖keyTerm‖payload)
algId=2 AES256_GCM:         header ‖ [scopeId:4] ‖ [keyTerm:4] ‖ [segmentId:16] ‖ [nonce:12]
                              ‖ [ciphertext ‖ tag:16] ‖ [CRC32C:4]
                              AAD = the 44-byte prefix magic..nonce   (v2 was 40; scopeId inserted)
                              DEK = HKDF-SHA256(root[keyTerm], salt=segmentId,
                                                info="configd/raft-at-rest-encryption/dek/v1", 32)
```

- **Parse order (all postures): CRC32C first** (version-independent, over `[0, len−4)`), then
  magic/version/algId from CRC-validated bytes, then MAC/GCM. A bit-flip reports corruption, not a
  misleading version error. `formatVersion != 3` ⇒ throw; unknown `algId` ⇒ throw; `algId=0` under
  a key ⇒ downgrade-refused throw; `reserved != 0` ⇒ throw. Sub-floor buffer (< header+CRC) ⇒
  `null` = structurally absent (first boot / torn tail) — the only non-throw miss.
- **Key selection.** algId=1: `K = K_integrity[keyTerm] = HKDF(root[keyTerm], salt=nodeKeyId,
  info="configd/raft-at-rest-integrity/v3", 32)` — term-versioned in BOTH postures (the
  precondition for non-destructive rotation). algId=2: DEK as above. `keyTerm ≥ 1` from the
  keyring, with ONE exception:
- **The `keyTerm = 0` signing-key domain (KEYRING_MAGIC only).** The keyring file's own outer
  envelope is MAC'd under `K_keyringMac = HKDF(signingKey, info="configd/keyring-mac/v1")`
  (chicken-and-egg: it cannot reference a term it defines). For `KEYRING_MAGIC` the envelope
  `keyTerm` MUST be 0 and the posture MUST be algId=1 (HMAC) regardless of node posture; `keyTerm=0`
  under any OTHER magic ⇒ fail closed.
- **`scopeId` (normative reader rule).** Per-shard artifacts stamp `scopeId = gid`, and **`gid` is
  frozen to the range `[0, NODE_SCOPE)`** — `gid = 0xFFFFFFFF` is illegal, so a per-shard reader can
  never be fooled by a node-level artifact colliding on scope (unreachable at sane N, but the
  sentinel must be excluded now — it cannot be constrained post-freeze). Node-level artifacts stamp
  `NODE_SCOPE`. **Every read path MUST assert `scopeId == expected` and refuse a mismatch** — this
  is the sole cross-shard-splice defense, so the at-rest read call-sites it MUST cover are
  enumerated normatively in §9.1: WAL replay, anchor/keyring/node-anchor/topology open, snapshot
  persist→reload, and the InstallSnapshot re-persist→reload path. (Wire InstallSnapshot and edge
  hydration are frame-authenticated and re-wrapped under the receiver's own `gid` before any at-rest
  read, so they are covered on the local read.) Mechanism precision: a record copied verbatim from another shard still
  authenticates as bytes (keys are node-wide) — the *assert* is what detects it (the record's
  authenticated scopeId announces its true shard), and the MAC/tag is what makes the scopeId
  *unforgeable in place*. Both together close the cross-shard splice; the assert is not optional.
- Per-magic GCM write segments as today (`SegmentKeyManager`); `REKEY_LIMIT = 2^32` per segment;
  nonce = 4 zero bytes ‖ u64 counter; anchors/keyring draw from their own magic's segment.
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
magic / unknown `fileVersion` / non-zero MBZ ⇒ REFUSE). **Freeze invariant:** this header is
unauthenticated by design (it must be key-lessly readable), so **neither `flags` nor `fileVersion`
may EVER gate a security decision, and slot offsets are compile-time CONSTANTS** — a flipped header
can only produce a clean REFUSE, never a slot-offset miscalculation, an OOB read, or a
different-slot read (red-team-verified, §11). A future `flags` bit must not become an
adversary-flippable control. Carried by: `raft-anchor`
(`ANCHOR_MAGIC`), `node-anchor` (`NODE_ANCHOR_MAGIC`), `raft-keyring` (`KEYRING_MAGIC`), and every
FileStorage log container — `raft-log.wal`, `raft-log.tmp.wal`, `security-audit.wal` — with
`WAL_FILE_MAGIC`. The header is intentionally outside the authenticated surface (it must be
readable with no key); all guarantees ride the authenticated slots/records behind it.

### 2.4 Per-shard anchor file `raft-anchor` (dual-slot; the A1 mechanism)

Location: `dataDir/shard-<gid>/raft-anchor` at N>1, `dataDir/raft-anchor` at N=1 (same placement
rule as the WAL). Written by a dedicated dual-slot writer (fixed-offset pwrite + fdatasync), NOT
`Storage.put`. **Same device/directory as the WAL is REQUIRED** (§6 §4.4).

```
[ container header @ 0, 8 B ]  (ANCHOR_MAGIC, §2.3)
Slot 0 @ offset 8; Slot 1 @ offset 8+512.  File size = 8 + 2×512 = 1032 B (fully preallocated).
Each slot: [recordLen:4][ envelopedAnchorRecord : recordLen ][ zero-pad to 512 ]
envelopedAnchorRecord = EnvelopeV3.wrap(ANCHOR_MAGIC, scopeId=gid, keyTerm=activeTerm, PAYLOAD)
  (HMAC posture 104 B; GCM posture 116 B — stride 512 ≫ max record)

ANCHOR_PAYLOAD (52 B):
    [anchorSeq:8]          strictly monotonic — the anti-rollback index
    [currentTerm:8]        merged from raft.persistent_state (Election Safety)
    [votedFor:4]           −1 = null (merged)
    [lastDurableIndex:8]   the WAL high-water mark — the truncation anchor
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
NODE_ANCHOR_PAYLOAD (92 B):   # AS-BUILT Gate 3b (was 60 B; +shardAnchorDigest per ratification item 12)
    [nodeAnchorSeq:8]      monotonic
    [topologyEpoch:8]      bound copy of the TopologyDescriptor epoch (rollback guard)
    [shardCount:4]         bound copy of N (deploy-guard tamper/rollback)
    [auditRecordCount:8]   audit-log high-water (periodic cadence, §6 §1)
    [auditHeadHash:32]     last anchored audit record's recordHash (chain head)
    [shardAnchorDigest:32] SHA-256 over the sorted (gid, lastDurableIndex) pairs (R-f closer)
```

Recovery cross-checks `TopologyDescriptor.{epoch,N} == nodeAnchor.{topologyEpoch,shardCount}` ⇒
mismatch REFUSE; audit replay must reach `{auditRecordCount, auditHeadHash}` ⇒ a shorter chain
REFUSEs (truncation confined to the un-anchored tail ≤K records/≤T is residual R-e; K/T are
`-Dconfigd.nodeAnchor.auditRecords`/`.intervalMs`, default 64 / 1000 ms).

**shardAnchorDigest boot semantics (AS-BUILT, the sound reading of ratification item 12).** Recovery
recomputes the digest over the recovered per-shard `raft-anchor.lastDurableIndex` values and compares
to the anchored digest. A strict "any change ⇒ REFUSE" is unsound: `lastDurableIndex` advances
legitimately between the periodic ticks, so a normal crash restart differs (a FORWARD move) — and §1
forbids bricking on a legal crash. The ratified trigger is narrower ("a shard **reset to index 0** ⇒
REFUSE"), so the check mirrors the per-shard `W<A`/`W>A` asymmetry at the node level:
- digest matches ⇒ PROCEED;
- digest differs AND a shard booted **FRESH** (its `raft-anchor` was ABSENT — the R-f wipe signature; a
  legal node never deletes a per-shard anchor) ⇒ **REFUSE**;
- digest differs AND no shard is FRESH (per-shard recovery already refused any `W<A` on a present
  anchor) ⇒ a legitimate forward advance ⇒ **accept-forward: re-anchor + PROCEED**.

This closes R-f (the delete→FRESH variant, matrix 15b); the anchor-rolled-to-an-older-valid-slot
variant stays matrix-14 residual-(a) → the external `AnchorWitness`. The digest is the frozen,
MAC-authenticated binding that makes "R-f = R-a": to hide a wipe an attacker must roll the node-anchor
back to a matching-digest version that never existed, i.e. forge/roll it (needs the key or the witness).
The refresh (audit head + digest) runs on the K/T cadence off the ack path (each shard's
`lastDurableIndex` read on its owner thread); a failed refresh is logged + retried, NOT the fail-closed
halt the per-shard anchor fsync is.

**Freshness bound (symmetry with R-e).** The digest detects a wipe *relative to the last-refreshed
value*, so there is a bounded freshness window — mint→first tick, and between ticks — in which a wipe
to a value the anchor already binds is invisible (this is the same R-a freshness residual as the
audit-tail R-e, on the shard-liveness field). At the mint over all-zero heads a single-shard wipe of a
*multi-shard* node is still caught (the surviving shards' non-zero heads keep the recomputed digest
different from the all-zero bind); the digest-matches-a-prior-bind case is the full-node wipe →
rollback-to-first-mint variant, which is R-a (external `AnchorWitness`), not an R-f hole. Steady state
(refreshed to non-zero heads) reliably detects a single-shard wipe→0.

**Auth-off accept-forward preserves the audit head (as-built).** On the accept-forward branch when
auth is OFF (`auditLog == null`), the re-anchor writes the node-anchor's *existing* `auditRecordCount`
/ `auditHeadHash` verbatim rather than the genesis value the un-observable auth-off boot would compute.
Regressing the head to genesis would let a later auth-ON boot skip the audit-truncation cross-check for
a truncation that predated the auth-off boot; preserving it keeps that guard live. Auth ON advances the
head normally. This is a strict tightening within the documented auth-off residual (§1: auth-off carries
no adversarial guarantees), surfaced by the Gate-3b red-team.

### 2.6 Keyring `raft-keyring` (dual-slot, node-level; the A2 + A5 format)

Exists whenever authentication is on (HMAC or encrypting posture). Lives in `dataDir` (its entries
are wrapped — compromise-value differs from the signing key; D-1 still governs the signing key).

```
[ container header @ 0, 8 B ]  (KEYRING_MAGIC, §2.3)
Slot 0 @ offset 8; Slot 1 @ offset 8+65536.  File size = 8 + 2×65536 = 131 080 B (preallocated).
  Slot stride FROZEN at 64 KiB — bounds retained terms (~900 local / ~200 cloud-blob terms);
  a rotate that would overflow the slot REFUSES loudly (operator escalation; ~centuries away at
  sane cadences).
Each slot: [recordLen:4][ envelopedKeyring ][ zero-pad to 65536 ]
envelopedKeyring = EnvelopeV3.wrap(KEYRING_MAGIC, scopeId=NODE_SCOPE, keyTerm=0, KEYRING_BODY)
  — ALWAYS algId=1 (HMAC) under K_keyringMac, regardless of node posture (§2.2)

KEYRING_BODY:
    [keyringFormatVersion:2 = 1]
    [keyringSeq:8]            monotonic across updates (dual-slot open = highest valid)
    [activeTerm:4]            ≥ 1
    [entryCount:4]
    entry × entryCount:
        [term:4]              ≥ 1 (term 0 illegal — distinct from the envelope keyTerm=0 domain)
        [wrapAlgId:1]         1 = local-KEK-GCM ; 2 = cloud-KMS-blob ; unknown ⇒ fail closed
        [nonceLen:1][nonce:nonceLen]          12 for local GCM; 0 for cloud
        [wrappedLen:4][wrappedRoot:wrappedLen]  local: AES-GCM ct+tag of the 32-B random root;
                                                cloud: opaque KMS blob
```

Per-entry local wrap: `KEK_wrap = HKDF(signingKey, info="configd/keyring-wrap/v1")`;
**wrap AAD binds `(KEYRING_MAGIC, keyringFormatVersion, term, wrapAlgId, nodeKeyId, "root")`** —
a wrapped root cannot be replayed into a different term slot or node. Key hierarchy, boot, and the
two rotate operations: §4 A2 (normative lifecycle summary in §2.18).

### 2.7 Topology descriptor (replaces `raft-shard-count.meta`, which is REMOVED)

`Storage.put` artifact `topology-descriptor.dat`, node-level:
`EnvelopeV3.wrap(TOPO_MAGIC, scopeId=NODE_SCOPE, keyTerm=activeTerm, PAYLOAD)` where

```
PAYLOAD (18 B): [formatVersion:u16 = 1][shardCount N:u32][topologyEpoch:u64][reserved:u32 = 0 MBZ]
```

Authoritative source for `ShardMap.epoch()` (v1 = 1, epoch 0 reserved-illegal; `StaticShardMap`
returns it instead of hardcoded 0) and the fixed-N boot guard (N mismatch ⇒ refuse to start, now
tamper-evident). Cross-checked against the node anchor (§2.5). A v2 dynamic reshard bumps the
epoch monotonically and updates both files.

### 2.8 WAL container + frame + inner record

- **Container**: `WAL_FILE_MAGIC` header (§2.3) at offset 0 of `raft-log.wal` / `raft-log.tmp.wal`
  / `security-audit.wal`; file < 8 B ⇒ fresh/empty; bad header ⇒ REFUSE.
- **Frame** (per entry, after the header): `[length:4][data:N][CRC32C:4]` — **CRC32C, not zlib
  CRC32** (unification). Torn trailing frame discarded on read (crash tail, kept); complete-frame
  CRC mismatch ⇒ throw.
- **Raft inner record** (the frame's `data`): `EnvelopeV3.wrap(WALE_MAGIC, scopeId=gid, …)` over
  the posture-dependent payload — **authenticated postures (HMAC / GCM):**
  `[index:8][term:8][prevHash:32][command:N]`; **keyless:** `[index:8][term:8][command:N]`
  (byte-identical, no chain — keyless carries no adversarial guarantee, §1). Carrier-versioned; the
  legacy raw-record fallback is DELETED (a non-enveloped record ⇒ fail closed).
- **Per-record hash chain (`prevHash`, authenticated postures — AMENDED 2026-07-04, operator-mandated,
  red-team-verified).** The originally-drafted §2.8 relied on contiguity + term-monotonicity alone; a
  red-team pass proved these do NOT catch an **index-preserving, term-monotonic content rollback** (an
  old authentic frame — from a since-conflict-overwritten term — spliced back over an interior index:
  contiguous indices, non-decreasing terms, a genuine MAC and correct scopeId, so every §2.8 position
  check passes). The operator ruled to build the charter's per-record chain, which closes it:
  `prevHash(k) = SHA-256(serialized_inner_payload(k−1))`, genesis `prevHash = 32×0x00` at index 1;
  `prevHash` rides INSIDE the authenticated payload, so the envelope MAC (HMAC) / GCM tag makes it
  unforgeable in place. Recovery verifies each successor's `prevHash` against its predecessor's record
  hash ⇒ a break REFUSES (an interior splice is caught by the *successor's* binding, even when the
  spliced frame has a valid incoming link; a re-stamp to repair the chain breaks the successor's own
  authenticator). A compacted first record (`firstIndex > 1`) leaves its `prevHash` unverified for the
  §2.4 snapshot anchor to bind. **Residual (unchanged):** a whole-suffix rollback to a *wholly prior
  valid chain* (the overwritten suffix was necessarily uncommitted) is the head-anchor monotonic-floor
  / `AnchorWitness` case (residual R-a, §4 §4), not a chain hole.
- **Recovery-time checks (normative, NEW):** contiguity (`e[k].index == firstIndex+k`), term
  monotonicity (`e[k].term` non-decreasing), the per-record hash chain above (authenticated postures),
  snapshot-join (`firstIndex == anchor.snapshotIndex+1`; blob boundary equals anchor's), reader scopeId
  assert. Any violation ⇒ REFUSE. Position checks detect index permutations/gaps/dups; the hash chain
  detects index-preserving content rollback; the scopeId assert detects cross-shard splice.

### 2.9 Snapshot blob + trailer

Blob: `EnvelopeV3.wrap(SNAP_MAGIC, scopeId=gid, …)` over
`[lastIncludedIndex:8][lastIncludedTerm:8][dataLen:4][data][configLen:4 (−1=null)][config]`.
Inner `data` ends with the magic-TLV trailer `[0xC0FD7A11][trailerLen:4][signingEpoch:8]` —
unknown tail beyond the known payload tolerated (TLV forward-compat, kept). **Legacy trailer forms
(a) empty and (c) bare-8-byte are DELETED** ⇒ throw. Chunking (1 MiB default / 4 MiB per-chunk cap
/ 512 MiB reassembly cap) and `EdgeSnapshotCodec` (lead u64 = DATA seq, not a version) unchanged —
carrier-versioned; documented as such.

### 2.10 Audit record (inside the `security-audit.wal` frames)

```
[AUDIT_MAGIC:4][recordVersion:u8 = 1][canonicalLen:8][canonical][prevHash:32][recordHash:32]
recordHash = HMAC-SHA256(K_audit, AUDIT_MAGIC ‖ recordVersion ‖ prevHash ‖ canonical)   (keyed)
           = SHA-256(same input)                                                        (keyless)
```

The magic+version are **inside the chain input** — a version downgrade breaks the chain. Head
bound by the node anchor (§2.5). Bad magic/version ⇒ chain-verification throw.

### 2.11 Watch cursor + topology epoch (A4) and SUBSCRIBE resume

```
frozen cursor := [topologyEpoch:u64][count:u32]([gid:u32][S:u64])*count
```

gid strictly ascending unsigned, `S ∈ [0, 2^63)`; carrier-versioned by the edge frame version.
**Normative decoder bound (client-facing, unauthenticated input):** a cursor payload of length < 12
(the minimum `[topologyEpoch:u64][count:u32]` with count=0) ⇒ FRAME_CORRUPT; every client-facing
decoder MUST map a buffer-underflow to FRAME_CORRUPT, never an uncaught runtime exception (the
cursor rides a CRC-only edge frame from untrusted clients).
**Uniform rule: every resume token binds the epoch** — SUBSCRIBE payloads prepend
`[topologyEpoch:u64]` before the `resume`/`failoverResume` fields. Epoch source: §2.7 via
`ShardMap.epoch()`. Server checks: epoch `0` ⇒ FRAME_CORRUPT; epoch ≠ current ⇒ **new
`ErrorCode.STALE_TOPOLOGY = 12`** (closed enum extended by one) delivered as WATCH_CANCELED
(watches) or ERROR_CLOSE (subscriptions) — client MUST drop the cursor and fully re-hydrate
(etcd `ErrCompacted` model). Edge wire stays 0x01/0x02/0x03 — payloads redefined in place (clean
break); edge golden fixtures regenerate (build arc); raft-wire goldens untouched.

### 2.12 Policy text pragma (A3)

Policy values under `_acl/roles/<role>` and `_acl/bindings/<principal>` MUST begin with the pragma
line `#!configd-acl v1` as **line 1 exactly** (CRLF tolerated). Line 1 not exactly the pragma
(including a plain `#` comment on line 1, or the pragma appearing on line ≥2) ⇒ parse failure ⇒
write-time 400 via `validateAclWrite`, load-time reject ⇒ loader keeps last-good. `v0` or unknown
`vN` ⇒ reject whole value. `#!` on line ≥ 2 is an ordinary `#` comment. Grammar after line 1
unchanged (role lines `<effect> <caps> <prefix>`, binding lines).

### 2.13 CommandCodec + edge-snapshot chunk body (A6 ruling)

**Carrier-versioned + assert** — no inner version byte. The command bytes never exist outside a
self-versioned carrier (WALE envelope / edge frame / SNAP envelope); the unknown-type-byte throw is
the assert; the carrier list is documented in code. The edge-snapshot chunk-body lead u64 is a DATA
sequence, not a format version — documented, carrier-versioned.

### 2.14 signing-key.bin

Format unchanged and adequate (magic `0xC0DF51C5`, version 1, keyId, DER key pair). Build-arc
fixes (not format changes): write via temp+fsync+atomic-rename+dir-fsync (today: bare
`Files.write`, torn-file risk); docstring hex typo; delete/apply the `writeForTest` chmod no-op.

### 2.15 Wire frames

- **Edge** (`EdgeFrameCodec`): `[len:4][version:1][type:1][payload][CRC32C:4]`, versions
  0x01/0x02/0x03, first-frame pin, watch types 0x0A–0x12 on 0x02+ only — confirmed frozen; payload
  changes are §2.11's epoch fields only. `ErrorCode` gains `STALE_TOPOLOGY=12`.
- **Raft** (`FrameCodec`): `[len:4][ver:1=0x02][type:1][gid:4][term:8][epoch:8][payload][CRC32C:4]`,
  CRC-before-version — confirmed frozen. **The dormant 8-B `epoch` at offset 18 upgrades from
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
artifact is SV, CV, or DE — the completeness enumeration in §5 §3 has no blank rows.

### 2.17 Recovery gates (normative summary; proofs in §4/§6)

Boot order: signing key → keyring (dual-slot, highest valid `keyringSeq` under the CURRENT signing
key; absent-with-authenticated-data or MAC-fail ⇒ REFUSE with diagnostics) → topology descriptor +
node anchor (cross-check equality; audit chain verified against the anchored head) → per shard:
container header → both anchor slots → WAL.

- **Presence:** anchor file absent + shard dir empty (no WAL bytes, no snapshot blob) ⇒ FRESH
  (bootstrap `anchorSeq=1`); absent + non-empty ⇒ REFUSE; present + both slots invalid ⇒ REFUSE
  (tamper — distinct from FRESH); ≥1 valid slot ⇒ proceed with highest `anchorSeq`.
- **WAL checks:** envelope verify + scopeId assert per record; contiguity; term monotonicity;
  snapshot-join (§2.8).
- **Step 2.5 term-witness gate:** `lastWALTerm > anchor.currentTerm` ⇒ REFUSE (an anchor rollback
  across a WAL-witnessed vote boundary; in every legal execution the anchor's term dominates the
  log's — proof §6 F-1, red-team-confirmed sound against pre-vote/stepdown/follower-append
  interleavings, §11). **Build-arc invariant (freeze):** the term/vote anchor write MUST stay a
  standalone persist-before-memory fsync — it MUST NOT be folded into the flush-cycle anchor write,
  nor batched/deferred. Either would violate `anchor.currentTerm ≥ lastWALTerm` and turn this strict
  gate into a false-positive that bricks a healthy node.
- **Head reconciliation** (`W` = WAL last index, `A` = `anchor.lastDurableIndex`): `W == A` ACCEPT;
  `W > A` ACCEPT-FORWARD (adopt WAL head for the LOG only; `currentTerm`/`votedFor` verbatim from
  the anchor; rewrite anchor at `anchorSeq+1`) — safe because entries above `A` were never
  committed-and-client-acked (INV-ANCHOR-ACK); `W < A` ⇒ **REFUSE** (a committed entry vanished —
  the attack the anchor exists to catch).
- **Break-glass:** `configd.recovery.rebuildAnchorFromWal=true` — loud, one-shot, audit-logged,
  forensic sidecar of the prior slots; off by default (it is also the adversary's escape hatch).
  **Freeze invariant:** break-glass is a launch-only system property (`Boolean.getBoolean`,
  matching the existing convention at `ConfigdServer:908,1002,1225,1261,1318`); it MUST NEVER be
  read from a data-directory file, or it becomes the adversary's silent anchor-defeat switch.

**Ordering invariants (placed in §6):** INV-ANCHOR-ACK (leader: durableIndex/self-vote — and hence
commit/ack — only after the covering anchor fdatasync; follower: AppendEntries success only after
the covering anchor fdatasync); INV-ANCHOR-LOWER (conflict truncation lowers the anchor to
`conflictPoint−1` and fsyncs BEFORE the WAL rewrite; compaction advances `snapshotIndex` in the
anchor LAST). **fsync-failure policy:** WAL- or anchor-fsync throw/lie ⇒ no durable-advance, no
ack, process exit (fsyncgate). Anchor slots preallocated at creation ⇒ steady-state anchor ENOSPC
impossible (ext4/xfs; COW filesystems documented weaker). Anchor MUST live in the same directory
(same device) as its WAL.

### 2.18 Rotation lifecycle (normative summary; full lifecycle + proofs in §4 A2)

Roots are independent random 32-B secrets per term (NOT derived from the signing key), wrapped in
the keyring; the signing key only authenticates (`K_keyringMac`) and wraps (`KEK_wrap`) the
keyring. Boot seeds `SegmentKeyManager` with ALL terms + `activeTerm` (hardcoded term=1 is gone).
**Term rotation** (admin op): generate random `root[activeTerm+1]`, append entry, bump
`activeTerm`, write keyring slot (`keyringSeq+1`), fsync, then `rotateTo` — old terms retained
forever; never re-encrypt. **Signing-key rotation:** with old+new keys present, rewrap ALL entries
under the new KEK into a new slot (`keyringSeq+1`) BEFORE swapping `signing-key.bin`, then swap,
then restart — crash on either side of the swap boots on the matching slot; roots unchanged ⇒ all
old data verifies. Unknown term on read ⇒ fail closed. The anchor authenticates under
`K_integrity[keyTerm]` with its own `keyTerm` stamp, so neither rotation invalidates old anchors.

---

## §3 Cross-lane resolutions and supersessions (the integration record)

The lane records in Part II are included verbatim as evidence. The following resolutions—agreed
between lanes during the session or adopted by the integrator from the JDE's byte-level findings—
**govern where a record predates them**:

1. **Envelope v3 supersedes the protocol record's "freeze formatVersion=2"** (§5 §2.1 was written
   before the security lane's scopeId/keyTerm addition). Everywhere §5 says "envelope v2," read v3;
   the protocol lane's Δs (CRC-before-version, reserved!=0 check) are ADOPTED INTO v3.
2. **⟦SEC-1⟧ snapshot-meta**: folded into the per-shard anchor (security lane owns);
   `SNAPMETA_MAGIC` never allocated; §5 §2.6's fallback is moot; §5 §3 row 7 reads "folded".
3. **⟦SEC-2⟧ topology**: standalone `topology-descriptor.dat` (protocol lane owns, §2.7), with the
   node anchor binding a copy of `{epoch, N}` (security lane owns the cross-check). §5 §3 row 2
   stands with the envelope now v3.
4. **⟦SEC-3/4⟧**: the keyring/anchor "formatVersion=1" placeholders in §5 §2.13/§2.15 map to
   "envelope v3 discriminated by new magic + 8-B container header (fileVersion=1)"; the keyring's
   inner `keyringFormatVersion=1` stays (its body is the evolving-entry format).
5. **State file merged** (⟦SEC-MERGE⟧, ratification item 1): `raft.persistent_state.dat` removed;
   §5 §2.4 and §5 §3 row 3 are superseded; STATE_MAGIC retired-reserved. Its test obligation
   (`forgedVotedForRefused`) moves to the anchor test surface.
6. **F-1 adopted** (reliability → security, both signed off): the `max(anchor.currentTerm,
   lastWALTerm)` repair rule is DELETED; Step-2.5 strict term-witness REFUSE replaces it;
   accept-forward never touches `currentTerm`/`votedFor`. This upgrades a slice of residual R-a
   into a detected case (matrix row 17d).
7. **F-2 adopted**: the anchor upper-bounds "everything committed-and-client-acked" (a follower's
   matchIndex ACK is a durability report, not a client promise); INV-ANCHOR-LOWER is sound because
   conflict points are always above `commitIndex`.
8. **`keyTerm = 0` signing-key domain** (integrator, adopted by the sketch): §2.2. Keyring outer
   envelope stamps `keyTerm=0`; illegal under any other magic; keyring slots always algId=1.
9. **Sketch DESIGN-DELTAS adopted**: NODE_ANCHOR_PAYLOAD is 60 B (the record's "56 B" label was an
   arithmetic slip — fixed in place); cross-shard-splice mechanism reworded to reader-assert +
   unforgeable-scope (fixed in the security record §A1.2 and matrix row 10, flagged inline);
   `TOPO/AUDIT/WAL_FILE` magic values allocated as RTOP/RAUD/RWLF (§2.1, collision-checked);
   **keyring slot stride frozen at 64 KiB** (the sketch's 4 KiB placeholder re-pinned; sketch
   recompiled + re-run green, §7); the policy pragma line-1-only rule byte-specified (§2.12).
10. **Audit-head cadence** (reliability): periodic K=64/1 s, not per-record — residual R-e bounded
    and documented; tunable to K=1 for audit-critical deployments.
11. **Naming**: the topology descriptor file is `topology-descriptor.dat` (a `Storage.put`
    artifact). The keyring and anchors are NOT `.dat` artifacts (dual-slot in-place writers).

Residuals R-a (narrowed by 17d), R-b, R-c, R-d, R-e: §4 §4 — all carried verbatim into §10's
ratification list.

---

# Part II — the lane design records (evidence; Part I governs on conflict)

# §4 Security LEAD record — A1 anchor + A2 rotation (attack matrix, rotation proofs, residuals)

# A1 Anchor + A2 Rotation — Permanent Frozen-Format Design (security LEAD)

Repo `main @ 012e213`. Design is **correct-from-scratch, no compat modes** (nothing
shipped). Every as-built claim is cited to `file:line`. Byte layouts are to the byte.
This closes gap-assessment §2.3-2 (truncation/rollback anchor) and §2.3-4 (non-destructive
rotation), plus the cross-shard-splice and snapshot-meta-tamper holes surfaced below.

New formats introduced here get a leading magic + format version per IntegrityEnvelope
convention; the **protocol lane owns the version/magic registry** — the values I pick are
proposals flagged `[PROTOCOL]`. The **reliability lane owns exact fsync placement** — I own
the ordering *invariant* and its security proof, flagged `[RELIABILITY]`.

---

## 0. Threat model (state verbatim; do not overclaim)

> The at-rest anchor and keyring detect **tamper, truncation, rollback, reorder, splice, and
> cross-artifact/shard/node replay** performed by an adversary who has **filesystem write
> access to the data directory but does NOT hold the key material** (the cluster signing key,
> which is the root of every derived key and, per D-1, lives OUTSIDE the data directory —
> `ConfigdServer.java:1366-1397`). Two things are **explicitly out of scope**, exactly where
> dm-verity, dm-integrity, Android Verified Boot, and Vault draw the same line (prior-art §1c):
> (a) **rollback of the anchor itself** to a prior *legitimately-authenticated* state — undetectable
> without external monotonic storage (TPM/RPMB NV counter) or a remote witness, because a valid
> prefix/older-state is byte-for-byte a valid state; (b) an adversary holding **both** the disk
> **and** the key, who can forge anything. Crash-consistency (torn tails, partial writes,
> bit-rot) is a *non-adversarial* fault handled by CRC + dual-slot + fsync ordering, and MUST
> NOT be conflated with the adversarial case — a design that bricks a node on a legal crash or a
> legal Raft transition fails just as surely as one that misses an attack.

Optional hardening for (a): a `configd.security.anchorWitness` SPI hook that additionally
writes `nodeAnchorSeq`/per-shard `anchorSeq` to external monotonic storage and refuses boot on
regression. Specified as an interface only (§A1.7); not built in v1.

---

## 1. Established facts I verified against source (load-bearing)

1. **Recovery does NOT enforce contiguity / slot-consistency.** The RaftLog constructor replays
   the WAL with `entries.add(deserializeEntry(raw))` in a bare loop (`RaftLog.java:151-154`).
   `deserializeEntry` reads the embedded `[index][term]` (`:647-660`) but **never checks that the
   embedded index equals the slot position or is contiguous**. The contiguity guard
   (`entry.index() != expectedIndex → throw`) lives only in `append()`/`appendNoSync()`
   (`:360-365`), which recovery bypasses. Read-side access is **position-based**:
   `entryAt(i)`/`termAt(i)` index the array via `toOffset(i) = i - snapshotIndex - 1`
   (`:613-615, 261-266`) and **trust position, not the embedded index**. ⇒ Reordering or
   splicing complete WAL frames is **undetected today** and silently returns the wrong entry.
   The frozen design MUST add the recovery-time checks (§A1.4); "the bytes are authenticated"
   is *not* sufficient.

2. **`LocalDerivedKmsProvider.derive()` ignores the term entirely.** `derive()` computes
   `Hkdf.deriveKey(signingKeyIkm, salt, KEK_INFO, 32)` (`LocalDerivedKmsProvider.java:111-120`)
   — the `term` field is only stamped into the `KeyId` metadata (`:116`), never fed to the HKDF.
   ⇒ (a) bumping the term with the same signing key yields the **identical** root bytes (a
   term "rotation" achieves zero cryptographic separation); (b) rotating the signing key
   re-derives a **different** root **at the same hardcoded term=1** (`ConfigdServer.java:1325`),
   so every prior GCM record fails its tag and the node **bricks** on recovery. The documented
   `local` rotation procedure is data-destroying. Confirmed gap §2.3-4.

3. **No shard binding anywhere in the crypto.** DEK = `HKDF(root[term], salt=segmentId, DEK_INFO)`
   (`SegmentKeyManager.java:190-200`); the root is node-wide (ONE `SegmentKeyManager` shared
   across all N groups — `ConfigdServer.java:1328-1335`); `nextSeal` keys write segments by
   MAGIC only (`SegmentKeyManager.java:148-163`) and `WALE_MAGIC` is shared by every shard; the
   GCM AAD is `header‖keyTerm‖segmentId‖nonce` with **no gid** (`IntegrityEnvelope.java:102-104,
   425`); the inner WAL payload is `[index][term][command]` with **no gid**
   (`RaftLog.java:627-634`). ⇒ **Cross-shard splice verifies today**: a WAL record from shard-1
   copied into shard-0's WAL at the slot matching its embedded index decrypts (same node root,
   same term, gid absent from AAD) and, in HMAC mode, MACs (same node K_integrity, gid absent
   from MAC input). The frozen format MUST bind shard identity (§A1.2).

4. **Cross-NODE replay is already caught** (confirm, keep). The `local` root chains to
   `signing-key.bin` (`ConfigdServer.java:1228-1236`, `LocalDerivedKmsProvider.java:111-120`),
   which is per-node (`SigningKeyStore`, inventory §16A) and, per D-1, off the attacker's
   storage. Another node's records derive a different root ⇒ different DEK/K_integrity ⇒ tag/MAC
   fails. True in both encrypting and keyed-HMAC postures.

5. **`raft.persistent_state` is a separate 12-byte atomic-rename file**, `[term:8][votedFor:4]`
   wrapped `STATE_MAGIC` (`DurableRaftState.java:157-165`), written only on term-change/vote
   (`:99-148`), authenticated but **with no monotonic sequence** ⇒ whole-file rollback to an
   older *legitimately-MAC'd* state is undetected (gap §2.3-2 tail).

6. **`raft-log.snapshot-meta` is written BARE** — `storage.put(SNAPSHOT_META_KEY, metaBuf.array())`
   with a raw 16-byte `[snapshotIndex:8][snapshotTerm:8]`, **no envelope/magic/CRC/MAC**
   (`RaftLog.java:584-587`), read back raw (`:161-165`). ⇒ snapshot-boundary tamper is undetected.

7. **The audit chain has the same headless-truncation hole.** `security-audit.wal` records chain
   `recordHash = HMAC(K_audit, prevHash‖canonical)` (`AuditLog.java:347-364, 381-389`) but nothing
   persists the chain HEAD, so deleting trailing records leaves a self-consistent shorter chain
   (inventory §16B). Same root cause as the WAL.

---

## A1. THE ANCHOR

### A1.1 Decision: MERGE `raft.persistent_state` INTO the anchor (recommended)

The anchor is **one dual-slot, authenticated, per-shard file** that carries a monotonic
`anchorSeq` AND the Raft persistent state (`currentTerm`, `votedFor`). This is the clean-break
choice. Weighing exactly what the LEAD asked:

| Axis | Merged anchor | Keep state separate |
|---|---|---|
| State-file-only rollback | **Impossible by construction** — there is no separate state file; reverting term/vote requires reverting the whole anchor (lower `anchorSeq`), which is the documented anchor-rollback residual, strictly harder than today's silent independent file swap | Undetected today (fact 5); would need its own monotonic seq to fix — i.e. re-invent the anchor anyway |
| Write cost for a vote | **One dual-slot write + one fsync** (in-place pwrite to the stale slot) | Today: tmp-write + fsync + rename + dir-fsync (4 ops) — merged is *cheaper* |
| Crash-atomicity | Dual-slot A/B: torn slot fails CRC/MAC, other slot wins (bbolt/LMDB pattern, prior-art §1d) | rename is atomic but single-copy |
| Write cadence coupling | Head advances every flush; term/vote rarely. Both serialize on the owner thread; `anchorSeq` monotone across both. Contention negligible (a leader flushes but doesn't vote; a candidate votes but doesn't flush) | — |
| **Raft-safety coupling (the con)** | A format/write bug now breaks **Election Safety AND durability** together. Mitigated: the record is tiny, fixed-shape, dual-slot, MAC'd, and gets the heaviest test surface | A bug is isolated to one axis |

**Recommendation: MERGE.** The security win — state rollback goes from *silent and undetected*
to *requires full-anchor rollback (the residual)* — is decisive, and it removes a whole
unauthenticated-position artifact class. The coupling con is real but contained by the trivial,
fixed-size record. (This subsumes the separate `raft.persistent_state.dat`; that file no longer
exists in the frozen layout.)

### A1.2 The frozen IntegrityEnvelope (adds `scopeId` + `keyTerm`) `[PROTOCOL]`

To bind shard identity (fact 3) and term-versioned integrity keys (needed for non-destructive
rotation, §A2.6), the envelope gains a 4-byte **`scopeId`** (authenticated in every posture) and,
for the HMAC posture, a 4-byte **`keyTerm`** (GCM already had one). `FORMAT_VERSION` bumps to
**3**. Header stays 8 bytes and the version marker stays at offset 4, readable before any crypto
(Kafka magic-before-CRC lesson, prior-art §Q4).

```
Header (all algIds, 8 B):  [magic:4][formatVersion:2 = 3][algId:1][reserved:1 MBZ]
then, for ALL algIds:      [scopeId:4]           # authenticated; NOT just CRC in keyed/enc

algId=0 NONE (keyless):    header ‖ [scopeId:4] ‖ [payload:N] ‖ [CRC32C:4]
algId=1 HMAC_SHA256:       header ‖ [scopeId:4] ‖ [keyTerm:4] ‖ [payload:N] ‖ [MAC:32] ‖ [CRC32C:4]
                             MAC = HMAC(K_integrity[keyTerm], magic‖fmtVer‖algId‖rsv‖scopeId‖keyTerm‖payload)
algId=2 AES256_GCM:        header ‖ [scopeId:4] ‖ [keyTerm:4] ‖ [segmentId:16] ‖ [nonce:12]
                             ‖ [ciphertext+tag] ‖ [CRC32C:4]
                             AAD = the 44-byte prefix magic..nonce (was 40; scopeId inserted after header)
                             DEK = HKDF(root[keyTerm], salt=segmentId, DEK_INFO)
```

**`scopeId` values:** per-shard artifacts (WAL entry, snapshot blob, per-shard anchor) →
`scopeId = gid` (the group id, `0..N-1`). Node-level artifacts (keyring, node-anchor, audit) →
`scopeId = NODE_SCOPE = 0xFFFFFFFF`. Effect *(mechanism precision per the JDE byte-level check)*: a
shard-1 WAL record copied byte-for-byte into shard-0's WAL still MAC/tag-VERIFIES as bytes (keys are
node-wide; the AAD/MAC input is built from the record's own bytes) — detection is the **reader's
mandatory `scopeId == gid` assert**, which refuses the record because its authenticated `scopeId`
announces its true shard (1 ≠ 0). The MAC/tag's role is that the adversary **cannot forge the
`scopeId` in place** (any edit invalidates the MAC/tag). Reader-assert + unforgeable-scope together
⇒ **cross-shard splice detected** in both postures; neither alone suffices, and the assert is a
normative MUST on every read path. A per-shard artifact replayed as node-scope (or vice versa)
fails the same assert. `magic` still
blocks cross-artifact confusion; `scopeId` adds the cross-shard axis; `keyTerm` selects the
retained-term key.

`RaftLog`/`DurableRaftState` are constructed per group (`ConfigdServer.buildRaftGroup:1592-1606`),
so each knows its `gid` and passes it as the envelope `scopeId` on write and asserts it on read.

### A1.3 Per-shard anchor file byte layout (dual-slot)

File `raft-anchor` (in `dataDir/shard-<gid>/` at N>1, `dataDir/` at N=1 — same placement rule as
the WAL, `buildRaftGroup:1600-1604`). **Not** a `Storage.put` artifact: it is written by a
dedicated dual-slot writer (fixed-offset `pwrite` + `fsync`), the bbolt/LMDB meta-page pattern
(prior-art §1d). `[RELIABILITY]`/`[JDE]`: this needs a small `AnchorFile` class, not the
tmp+rename `Storage.put` path.

Per the protocol lane's container convention (their §2.2), the FILE self-identifies at offset 0
with an 8-byte container header (foreign-file / version guard, corruption-only — authentication is
the per-slot envelope); each slot is then a self-versioned `IntegrityEnvelope`.

```
[ container header @ 0, 8 B ]  [ANCHOR_MAGIC:4]["RANC"][fileVersion:u8 = 1][flags:u8 = 0][reserved:u16 = 0 MBZ]
Slot 0 @ offset 8,  Slot 1 @ offset 8+512.   File size = 8 + 2*512 = 1032 B.  (Stride 512 ≫ max record.)
Each slot:  [recordLen:4][ envelopedAnchorRecord : recordLen ][ zero-pad to 512 ]

envelopedAnchorRecord = IntegrityEnvelope.wrap(ANCHOR_MAGIC, scopeId=gid, ANCHOR_PAYLOAD)
    HMAC posture size = 8+4+4 +52 +32 +4 = 104 B ;  GCM posture = 8+4+4+16+12 +(52+16) +4 = 116 B

ANCHOR_PAYLOAD (52 B):
    [anchorSeq:8]          # strictly monotonic; the anti-rollback index (the whole point)
    [currentTerm:8]        # merged from raft.persistent_state (Election Safety)
    [votedFor:4]           # -1 = null (merged)
    [lastDurableIndex:8]   # the WAL high-water mark — the truncation anchor
    [lastDurableTerm:8]    # term at lastDurableIndex (binds the tip to a term)
    [snapshotIndex:8]      # authenticates the currently-BARE snapshot-meta (fact 6)
    [snapshotTerm:8]
```

`ANCHOR_MAGIC = 0x52414E43 "RANC"` `[PROTOCOL]` (distinct from RFST/RSNP/RWAL,
`RaftArtifactMagic.java:22-29`). The bare `raft-log.snapshot-meta` is **removed** — the anchor is
the authenticated snapshot boundary; `RFST`/`raft.persistent_state.dat` is **removed** — merged.

**Write protocol (crash-atomic):** to update, pick the slot with the *lower* valid `anchorSeq`
(the stale one), write `[recordLen][envelope]` there with `anchorSeq = maxValid+1`, then `fsync`.
Only one slot is ever mutated per update; the other stays intact, so a torn write is detected by
that slot's CRC/MAC and the untouched slot (lower seq, still valid) remains a fallback. Atomicity
comes from **CRC+MAC detection + write-one-slot**, not from sector-atomic hardware.

**Read/open:** parse both slots, `unwrapOrNull` each (asserting `scopeId==gid`), and take the slot
with the **highest valid `anchorSeq`**. See §A1.4 for what happens when zero/one/both are valid.

### A1.4 Recovery gates (decision tables) + the NEW contiguity checks

**Step 1 — WAL replay integrity (per record).** FileStorage drops any torn trailing frame first
(`FileStorage:271`), so every frame reaching RaftLog is complete; `deserializeEntry` verifies the
envelope (`RaftLog:647-660`) — MAC/tag/CRC/version/`scopeId==gid` all fail-closed. Then, **NEW,
the checks recovery lacks today (fact 1):**

- **Contiguity:** for the surviving entries `e[0..m-1]`, require `e[k].index == firstIndex + k`
  (first slot's embedded index defines `firstIndex`; every subsequent embedded index is exactly
  +1). Any gap/dup/reorder ⇒ **REFUSE**. This is what makes reorder/splice detection *real*.
- **Term monotonicity:** require `e[k].term` non-decreasing. A term that goes down mid-log ⇒
  **REFUSE** (Raft never writes a lower term after a higher one at a later index).
- **Snapshot join:** require `firstIndex == snapshotIndex + 1` (WAL non-empty) against the
  anchor's `snapshotIndex`; and if a snapshot blob is present, `blob.lastIncludedIndex ==
  anchor.snapshotIndex` and `blob.lastIncludedTerm == anchor.snapshotTerm`.

**Step 2 — anchor presence** (dual-slot):

| Anchor slots | Other artifacts (WAL/snapshot) | Decision |
|---|---|---|
| both absent (no file) | all absent | **FRESH NODE** — bootstrap anchor at `anchorSeq=1, lastDurableIndex=0, currentTerm=0, votedFor=-1` |
| both absent (no file) | any present (non-empty data dir) | **REFUSE (fail closed)** — an anchor was deleted; a non-empty data dir must carry its anchor |
| ≥1 slot valid | — | proceed to Step 3 with the highest-valid-`anchorSeq` slot |
| file present, **both slots invalid** | — | **REFUSE (fail closed)** — tamper (distinct from FRESH, which has no file at all) |

Break-glass: `configd.recovery.rebuildAnchorFromWal=true` (loud, one-shot) rebuilds the anchor
from the current WAL head after a *genuine* catastrophic anchor loss. It MUST: print the WARNING
banner, emit an **audit record** `{action=anchor.rebuild, target=<gid>, rebuiltTo=<walHead,
walTerm, snapIndex>, operator=<id>, ts}`, and record the pre-rebuild slot bytes (if any) to a
sidecar for forensics. It accepts whatever the WAL says — so it is *also* the adversary's escape
hatch and is therefore audit-logged and off by default.

**Step 2.5 — term-witness gate** (per reliability F-1, adopted). Under the merge, `currentTerm` is
anchored (persist-before-memory, §A1.1) strictly BEFORE any *local* WAL entry at that term can be
written: a leader persists `setTermAndVote(T)` at election before it proposes at T; a follower
persists the term update on RPC receipt before it appends. Every subsequent flush write also carries
`currentTerm=T`. Hence **`anchor.currentTerm ≥ lastWALTerm` is an invariant of every legal
execution, including every crash window.** Recovery asserts it:

> `lastWALTerm > anchor.currentTerm  ⇒  REFUSE (fail closed).`

The only way this fires is an **anchor rollback across a term/vote boundary that the WAL witnesses**,
so the gate *upgrades* that slice of residual (a) into a DETECTED case. (My earlier `max()`-repair
rule was wrong: it would have silently *accepted* exactly that rollback and cleared `votedFor` — a
double-vote hazard. Reliability's proof retired it.)

**Step 3 — head reconciliation** (the security asymmetry). Let `W = WAL last index`,
`A = anchor.lastDurableIndex`:

| Relation | Cause | Decision | Why safe |
|---|---|---|---|
| `W == A` | clean shutdown | **ACCEPT** as-is | — |
| `W  >  A` | crash between WAL fsync and anchor fsync (leader flush) OR mid-conflict-truncate re-adopt | **ACCEPT & reconcile forward**: adopt the WAL head for the LOG only; take `currentTerm`/`votedFor` **verbatim from the anchor, unchanged**; rewrite the anchor to the new head | Entries `(A, W]` were **never committed-and-client-acked** — the leader counts its self-copy toward quorum only after the anchor covers it, and a follower reports matchIndex only after its anchor covers it (§A1.5), so no client promise rests on them; a normal uncommitted crash tail Raft may re-truncate. `currentTerm` is untouched because Step-2.5 already proved `anchor.currentTerm ≥ lastWALTerm` — the anchor's term is already correct-and-current, so there is nothing to repair, and the former `max()`+clear-vote rule would have masked an anchor rollback instead of refusing it |
| `W  <  A` | **adversarial trailing truncation** or catastrophic WAL loss | **REFUSE (fail closed)** | The anchor asserts `A` was the committed-and-acked durable floor; its disappearance means a committed entry vanished. This is precisely the attack the anchor exists to catch |

### A1.5 The ordering invariant I own `[RELIABILITY places it]`

- **INV-ANCHOR-ACK (leader):** the leader may count its self-copy of entry `i` toward the commit
  quorum — and therefore ack the client — ONLY AFTER an anchor slot with `lastDurableIndex ≥ i`
  is `fsync`'d. (Today the leader gates on `durableIndex` from the WAL fsync,
  `RaftNode.flushDurable:2223-2237`, `maybeAdvanceCommitIndex:2153-2175`; the anchor fsync joins
  that barrier.)
- **INV-ANCHOR-ACK (follower):** a follower may send an AppendEntries success reporting
  `matchIndex = i` ONLY AFTER its anchor slot with `lastDurableIndex ≥ i` is `fsync`'d. (Today the
  WAL fsync precedes the ACK, `RaftLog.appendEntries:448-451`; the anchor fsync joins it.)
- **INV-ANCHOR-LOWER (conflict truncation & compaction):** before any WAL rewrite that would leave
  `WAL head < a previously-anchored lastDurableIndex`, the anchor must FIRST be made durable with
  `lastDurableIndex ≤ new head`. Concretely, conflict truncation (`RaftLog.truncateFrom:461-479`
  → `appendEntries:440-445`) becomes: (1) anchor-write lowering `lastDurableIndex` to
  `conflictPoint-1`, fsync; (2) WAL rewrite + append the leader's entries, fsync; (3) anchor-write
  raising to the new head, fsync; (4) ACK. Compaction (`compact:551-590`) never lowers the *head*
  (it drops a prefix), so it only advances `snapshotIndex` in the anchor after `persistSnapshot`
  and the WAL rewrite — anchor last, as today's ordering already does.
  - **Corollary — the downward move never uncovers a committed entry (per reliability F-2).** A
    conflict point is always `> commitIndex` (Raft never truncates a committed entry — Log Matching
    + Leader Completeness), so lowering the anchor to `conflictPoint-1 ≥ commitIndex` only ever
    exposes *uncommitted* tail entries, which Raft is entitled to re-truncate. The downward anchor
    move is therefore sound: it can never drop the anchor below the committed-and-client-acked floor.

**Security argument for anchor-BEFORE-ack (not Postgres-style anchor-lag):** a *client* ack is a
promise that the entry is committed and durable. (A follower's matchIndex ACK to the leader is a
durability report that *feeds* the commit quorum, not itself a client promise — but it must still be
anchor-covered, or the leader could commit on durability that isn't there.) If the anchor is allowed
to lag, there is a window `(anchor, committed]` in which an adversary can trim the WAL to just below
a committed index and recovery's Step-3 `W ≥ A ⇒ accept-forward` rule would **silently accept the
truncated log** — the committed entry vanishes with no refusal. Anchor-before-ack guarantees, at
every node, `A ≥ every index that node durably contributed to a committed-and-client-acked entry`,
so truncating any such entry always trips `W < A ⇒ REFUSE`. Postgres tolerates a lagging
`pg_control` precisely because it is a *crash* anchor, not an *anti-rollback* anchor (prior-art
§Q2, §1a); our stated threat model forbids that lag. etcd achieves the same by committing the
`consistent_index` in the *same* backend txn as the data (prior-art §Q2) — our single ordered
anchor fsync per flush cycle is the fs-level equivalent.

**Cost shape:** one additional ordered fsync per flush cycle (WAL fsync → anchor fsync),
amortized by group commit (one anchor fsync per batch, `RaftLog.syncWal:378-382`); plus one extra
anchor fsync on the *rare* conflict-truncation path. Exact scheduling is reliability's.

### A1.6 Node-level anchor (topology + audit head) — IN SCOPE

Two node-level artifacts share the WAL's headless-truncation shape and are cheap to cover, so
they are **in scope** via a second dual-slot file `node-anchor` in `dataDir/` (`scopeId =
NODE_SCOPE`), `NODE_ANCHOR_MAGIC = 0x524E414E "RNAN"` `[PROTOCOL]`, same container header + slot
mechanics as §A1.3 (8-byte `[NODE_ANCHOR_MAGIC:4][fileVersion:u8=1][flags:u8=0][reserved:u16=0]`
header, then two 512-byte slots):

```
NODE_ANCHOR_PAYLOAD (92 B):   # AS-BUILT Gate 3b (+shardAnchorDigest per ratification item 12)
    [nodeAnchorSeq:8]      # monotonic
    [topologyEpoch:8]      # A4 epoch — binds the §SEC-2 standalone TopologyDescriptor (rollback guard)
    [shardCount:4]         # binds N (deploy-guard tamper/rollback)
    [auditRecordCount:8]   # audit-log high-water
    [auditHeadHash:32]     # the last audit record's recordHash — binds the chain head
    [shardAnchorDigest:32] # SHA-256 over the sorted (gid, lastDurableIndex) pairs — the R-f closer
```

- Topology (SEC-2): the standalone versioned `TopologyDescriptor` `{formatVersion, N,
  topologyEpoch}` (protocol §2.7) remains the **authoritative source** read by `ShardMap.epoch()`
  and the fixed-N boot guard — it is node-level, cluster-consistent, deploy-authored, and
  envelope-authenticated (I agree it MUST be enveloped: a plaintext N is editable to bypass the
  reshard refusal). The node-anchor binds a **copy** of `{topologyEpoch, shardCount}`; recovery
  cross-checks equality (`TopologyDescriptor.{epoch,N} == nodeAnchor.{topologyEpoch,shardCount}`)
  ⇒ mismatch REFUSE. This catches a **topology-file rollback** (swap the descriptor for an older
  legitimately-MAC'd one — a live threat only in a v2 dynamic reshard, since v1 static-N has just
  one legitimate topology, but frozen now for free). A legitimate v2 reshard updates BOTH files
  (advancing `nodeAnchorSeq`).
- Audit: the node-anchor advances `auditRecordCount`/`auditHeadHash` on the reliability lane's
  **periodic cadence** (K=64 records or 1 s, not per-record — audit is off the ack path, so a
  per-record anchor fsync is pure overhead). On recovery the replayed chain head must match the
  anchored `auditRecordCount`/`auditHeadHash`; a shorter self-consistent chain that drops records
  **below the last anchored head is DETECTED (REFUSE).** **Bounded residual (R-e):** truncation
  confined to the un-anchored tail (the last ≤K records / ≤1 s before a crash) is undetected — a
  strict improvement over today's fully-undetected chain, honestly bounded rather than claimed
  closed. `VerifyKeyExporter` output stays an export (not server state), unanchored — documented
  residual.
- **Shard liveness (R-f closer — AS-BUILT Gate 3b, ratification item 12):** the node-anchor binds a
  `shardAnchorDigest` = SHA-256 over the sorted `(gid, lastDurableIndex)` pairs of every per-shard
  `raft-anchor`, refreshed on the SAME K/T cadence (off the ack path; each shard's `lastDurableIndex`
  is read on its owner thread). On recovery the digest is recomputed over the recovered per-shard
  heads. **Boot semantics (sound reading of item 12, mirroring the per-shard `W<A`/`W>A` asymmetry so a
  legal crash never bricks — §1):** digest matches ⇒ PROCEED; digest differs AND a shard booted FRESH
  (its `raft-anchor` was ABSENT — the R-f wipe signature) ⇒ **REFUSE**; digest differs AND no shard is
  FRESH (per-shard recovery already refused any `W<A`) ⇒ a legitimate forward advance ⇒ accept-forward
  (re-anchor + PROCEED). A strict "any change ⇒ REFUSE" was rejected: `lastDurableIndex` advances
  between ticks, so it would refuse every crash restart under load (esp. N=1). This raises R-f from
  silent-loss to a detected node-anchor rollback (= R-a): to hide a wipe an attacker must roll/forge the
  node-anchor to a matching-digest version that never existed.

### A1.7 External-witness hook (residual (a) mitigation) — interface only

`interface AnchorWitness { void record(int scopeId, long anchorSeq); long lastSeen(int scopeId); }`
When configured, the anchor writer calls `record` after each fsync and boot calls `lastSeen`; a
`storedSeq < lastSeen` ⇒ REFUSE (anchor rollback detected via external monotonic storage). Default
= no witness (residual (a) stands). This is the only construct that can close anchor-rollback.

---

## A2. NON-DESTRUCTIVE KEY ROTATION

### A2.1 Root cause (verified) and the fix shape

Today roots are HKDF(signing key) ignoring term (fact 2), so "term" is meaningless and
signing-key rotation bricks the node. The frozen fix is the **Vault keyring** (prior-art §Q3):
roots become **independent random 32-byte secrets**, generated once per term, **wrapped** and
persisted in a versioned keyring; new writes use the active term, **all old terms are retained
forever for decrypt**, unknown term fails closed. Rotation *appends a term*, never re-encrypts.
This decouples the roots from the signing key: the signing key becomes only the KEK that *wraps*
the keyring, so rotating it **rewraps** (roots unchanged ⇒ all old data still verifies).

### A2.2 The keyring frozen format (doubles as the A5 WrappedKey envelope) `[PROTOCOL]`

File `raft-keyring` in `dataDir/` (node-level). **It may live inside `dataDir`** and does NOT need
the D-1 guard: its compromise-value differs from the signing key's — the entries are wrapped by a
KEK derived from the signing key, so an adversary with only data-dir write access cannot unwrap
them. (D-1 still protects the *signing key* itself, `ConfigdServer:1366-1397`.) **Dual-slot** (to
make signing-key rotation crash-atomic, §A2.4), same container header + slot mechanics as the
anchor:

```
[ container header @ 0, 8 B ]  [KEYRING_MAGIC:4]["RKYR"][fileVersion:u8 = 1][flags:u8 = 0][reserved:u16 = 0]
Slot 0, Slot 1 (fixed offsets):  [recordLen:4][ envelopedKeyring ][ pad ]

envelopedKeyring = IntegrityEnvelope.wrap(KEYRING_MAGIC, scopeId=NODE_SCOPE, KEYRING_BODY)
    posture = HMAC under K_keyringMac  (see §A2.3)  — the OUTER integrity of the whole keyring

KEYRING_BODY:
    [keyringFormatVersion:2 = 1]   # 0 illegal; unknown ⇒ fail closed (protocol §0.2)
    [keyringSeq:8]                 # monotonic across rotations (dual-slot open = highest valid)
    [activeTerm:4]                 # >= 1 ; term 0 reserved-illegal
    [entryCount:4]
    entry * entryCount:
        [term:4]                   # >= 1 ; term 0 reserved-illegal
        [wrapAlgId:1]              # 1 = local-KEK-GCM ; 2 = cloud-KMS-blob ; unknown ⇒ fail closed
        [nonceLen:1][nonce: nonceLen]        # 12 for local GCM ; 0 for cloud
        [wrappedLen:4][wrappedRoot: wrappedLen]   # local: AES-GCM ct+tag ; cloud: opaque KMS blob
```

`KEYRING_MAGIC = 0x524B5952 "RKYR"` `[PROTOCOL]`.

- **Outer envelope MAC** (`K_keyringMac`) authenticates the **entire body** — the entry set,
  `activeTerm`, and count — so strip / swap / add / truncate of keyring entries **fails loud**
  (age's "MAC over the whole header" property, prior-art §Q6). This is what closes the keyring's
  own truncation hole.
- **Per-entry local wrap** binds `AAD = KEYRING_MAGIC‖keyringFormatVersion‖term‖wrapAlgId‖
  nodeKeyId‖"root"`, so a wrapped root cannot be replayed into a different term slot or a
  different node (JWE header-as-AAD / KMS encryption-context, prior-art §Q6). Defense-in-depth
  over the outer MAC.
- **Unknown `keyringFormatVersion`, `wrapAlgId`, or (at read) `term` ⇒ fail closed.**

### A2.3 Key hierarchy (everything term-versioned, rooted in the keyring)

```
signing-key.bin  ──HKDF(info="configd/keyring-mac/v1")──►  K_keyringMac   (authenticates the keyring file)
     │           ──HKDF(info="configd/keyring-wrap/v1")──►  KEK_wrap       (GCM-wraps local roots)
     │
     ▼ (KEK unwraps the keyring; keyring holds the independent random roots)
root[term]  (random 32 B, retained forever)
     ├─HKDF(salt=keyId, info="configd/raft-at-rest-integrity/v3")─► K_integrity[term]   (HMAC posture MAC key)
     └─HKDF(salt=segmentId, info="configd/raft-at-rest-encryption/dek/v1")─► DEK[term,segmentId]  (GCM posture)
```

The keyring exists **whenever authentication is on** (keyed-HMAC OR encrypting). In HMAC-only
(integrity, no confidentiality) mode the roots are used solely to derive `K_integrity[term]`; in
encrypting mode they additionally derive DEKs. This makes the integrity MAC key term-versioned in
*both* postures — the precondition for non-destructive rotation of an integrity-only node. Info
strings are all domain-separated (they already are today,
`LocalDerivedKmsProvider.java:48-50`, `SegmentKeyManager.java:68-70`, `ConfigdServer:1234`); the
integrity info bumps to `v3` because its derivation source changes (signing key → keyring root).

### A2.4 Boot / bootstrap / fail-closed

- **Boot:** open `raft-keyring` (highest-valid `keyringSeq` slot whose outer MAC verifies under the
  **current** signing key's `K_keyringMac`). Unwrap **every** entry → seed the `SegmentKeyManager`
  with the full `term → root` map and `activeTerm` (replacing the hardcoded `term=1`,
  `ConfigdServer:1325`, and `SegmentKeyManager.unsealFrom:121-125` already accepts a provider).
- **First boot (bootstrap):** no keyring + empty data dir ⇒ create the keyring with one random
  `root[1]`, `activeTerm=1`, wrapped under the current KEK, dual-slot, fsync.
- **Fail closed:** keyring absent but encrypted/authenticated data present ⇒ REFUSE (can't be a
  fresh node; the keyring was lost/deleted). Unknown term at read ⇒ REFUSE (already true,
  `SegmentKeyManager.resolveDek:170-176`). Outer MAC fails under the current signing key ⇒ REFUSE
  with a diagnostic ("keyring is under a prior KEK — complete the signing-key rotation or restore
  the prior signing key"), never a silent re-derive.

### A2.5 The rotate operations

**(local) term rotation — new key material, same signing key** (admin-triggered, online or at
restart): unwrap all roots under the current KEK; generate a fresh random `root[activeTerm+1]`;
wrap all roots under the same KEK; write a new dual-slot entry with `keyringSeq+1`, `activeTerm+1`;
fsync. Then `SegmentKeyManager.rotateTo(newRoot)` (`:137-146`, already correct: retains old terms,
clears write segments) installs it; new writes stamp the new `keyTerm`, old data reads under its
retained term. **This is the independent encryption-key rotation the local provider "can't do"
today** — enabled because roots are now independent random material, not HKDF(signing key).

**(local) signing-key rotation — crash-atomic handover:** (1) with BOTH the old and new signing
keys present, load the keyring (old `K_keyringMac`/`KEK_wrap`), unwrap all roots, **rewrap** them
under the new signing key's KEK, and write them as a NEW dual-slot entry (`keyringSeq+1`) —
**BEFORE** swapping the signing-key file; fsync. Now both slots are valid (old slot verifies under
the old signing key, new slot under the new). (2) Swap `signing-key.bin` to the new key. (3)
Restart. Boot picks the highest-`keyringSeq` slot that verifies under the *current* (new) signing
key. Roots are unchanged ⇒ every DEK/`K_integrity[term]` is unchanged ⇒ **all prior data still
decrypts/verifies.** Crash between (1) and (2): old signing key still active ⇒ old slot valid ⇒
boots fine. Crash between (2) and (3): new key active ⇒ new slot valid ⇒ boots fine. No window
bricks the node.

**(cloud SPI) rotation:** `wrapAlgId=2`; entries are opaque KMS blobs (root wrapped by the external
KMS). Rotation = `KmsProvider.rotateTo` at an admin-triggered call site: generate/register a new
KMS-wrapped root, append the entry, bump `activeTerm`. The outer keyring MAC is still
`K_keyringMac` from the signing key (the signing key always authenticates the keyring *file*; the
provider governs root *confidentiality/custody*). A KMS-unreachable unwrap fails closed at boot
(`SegmentKeyManager.unsealFrom` propagates `KmsUnavailableException:121-125`).

**Retention:** old terms are **never deleted** and never re-encrypted; rewrap is the only op on
local signing-key rotation. Interaction with `requireEncrypted` (`ConfigdServer:1318-1321`):
unchanged — once the legacy HMAC prefix is compacted away the operator drops the legacy read key;
the keyring path is orthogonal (it governs GCM/`K_integrity` terms, not the legacy HMAC read key).

### A2.6 Rotation × anchor interaction (the cross-item invalidation this session exists to catch)

The anchor is authenticated by the **same** IntegrityEnvelope in the **same** posture, carrying its
own `keyTerm` (§A1.2). Therefore:

- **Term rotation:** new anchor writes stamp the new `keyTerm`; **old anchor slots carry the old
  `keyTerm` and verify under the retained `K_integrity[oldTerm]`/`DEK[oldTerm]`**. Rotation does
  NOT invalidate the anchor. (Had the anchor MAC key been the un-versioned signing-key-derived
  `K_integrity` of today, a term bump could not have coexisted with old anchors — this is exactly
  the invalidation avoided by rooting `K_integrity` in the keyring.)
- **Signing-key rotation:** roots (hence every `K_integrity[term]`) are unchanged, so old anchors,
  WAL, snapshots, and state all still verify. Non-destructive across the board.

### A2.7 Non-destruction proof (walkthroughs)

1. **write@term1 → term-rotate → write@term2 → restart → both decrypt.** term1 records carry
   `keyTerm=1`; keyring retains `root[1]`; on read `resolveDek(1,·)` succeeds. term2 records carry
   `keyTerm=2` under `root[2]`. Both verify. ✔
2. **term-rotate-then-crash mid-rewrite.** Keyring is dual-slot; a torn new slot fails its outer
   MAC/CRC ⇒ the prior slot (all old terms) wins ⇒ node boots on the pre-rotation keyring, no data
   loss; the operator re-runs the rotate. ✔
3. **signing-key rotation with keyring → old data still readable.** §A2.5: rewrap-before-swap; roots
   unchanged ⇒ all `keyTerm`s resolve. The old documented data-destroying outcome is now
   **impossible by construction**: a mismatched keyring/signing-key **fails closed loudly with a
   diagnostic**, never silently re-derives a wrong root and bricks GCM tags. ✔
4. **unknown term on read** ⇒ fail closed (`resolveDek:170-176`). ✔
5. **rotation does not invalidate the anchor** — §A2.6. ✔

---

## 3. The completeness proof — attack → detection matrix

Scope of "DETECTED": an in-scope adversary (disk write, no key). Mechanisms: `E`=per-record
IntegrityEnvelope MAC/GCM-tag (incl. `magic`, `scopeId`, `keyTerm` binding); `C`=NEW recovery
contiguity/term-monotonicity checks (§A1.4 Step 1); `H`=anchor head gate `W<A ⇒ REFUSE` (§A1.4
Step 3); `S`=monotonic `anchorSeq`/`keyringSeq` dual-slot; `X`=external witness (§A1.7, optional).

| # | Attack | Detection | Mechanism |
|---|---|---|---|
| 1 | mid-frame tail truncation | crash-equivalent; FileStorage drops torn frame, then `H` if it dropped an anchored index | FileStorage:271 + H |
| 2 | frame-boundary tail truncation (1..k trailing frames) | **DETECTED** — every survivor is valid, but `W < A` | H |
| 3 | whole-WAL-file rollback to an older valid WAL | **DETECTED** — older WAL has `W < A` | H |
| 4 | state rollback ACROSS a term boundary (term goes backward vs the WAL) | **DETECTED** — no separate state file; the Step-2.5 term-witness gate REFUSES `lastWALTerm > anchor.currentTerm` | merged §A1.1 + Step-2.5 |
| 4b | state rollback WITHIN a term (`votedFor` reset by replaying an older same-term slot) | **RESIDUAL (R-a′ — SAFETY/Election-Safety, red-team §11)** — term unchanged so Step-2.5 is silent; votes aren't WAL-witnessed; worst case = double-vote → divergence, NOT staleness | X only (AnchorWitness) |
| 5 | snapshot+meta rollback (older pair) | **DETECTED** — anchor binds `snapshotIndex/Term`; older pair mismatches the anchor / `W<A` | E + H |
| 6 | snapshot-meta-only tamper | **DETECTED** — meta is removed; boundary now lives authenticated in the anchor | E (anchor) |
| 7 | in-log reorder (index permutation) | **DETECTED** — embedded index ≠ slot position | C |
| 8 | record splice / duplication / gap (same shard, index-level) | **DETECTED** — contiguity + term-monotonicity | C |
| 8b | interior stale-content rollback (index-preserving, term-monotonic: an old authentic frame spliced back over a since-overwritten index — the red-team finding) | **DETECTED (AMENDED 2026-07-04)** — the per-record hash chain: the successor's authenticated `prevHash` no longer matches; C+term-monotonicity alone MISS this (they were a documented overclaim) | Chain (§2.8) |
| 9 | cross-artifact replay (WAL↔snapshot↔state) | **DETECTED** — distinct `magic` in AAD/MAC | E |
| 10 | **cross-SHARD replay** (shard-1 record → shard-0) | **DETECTED (NEW)** — reader's mandatory `scopeId==gid` assert refuses (record's authenticated scopeId announces its true shard); in-place scopeId forge invalidates MAC/tag | E + reader assert (§A1.2) |
| 11 | cross-NODE replay (another node's files) | **DETECTED** — node-local root (per-node signing key) ⇒ different DEK/`K_integrity` | E, fact 4 |
| 12 | rollback-then-truncate combinations | **DETECTED** — whichever leaves `W<A` or a bad `magic/scope/term/contiguity` trips first | H + C + E |
| 13 | anchor-file-only tamper (forge a slot) | **DETECTED** — slot MAC/tag fails ⇒ other slot / REFUSE | E + S |
| 14 | **anchor rollback to a prior valid slot pair** | **PARTIAL** — DETECTED if it lowers a WAL-witnessed term (`lastWALTerm > anchor.currentTerm`, Step-2.5 gate); otherwise **RESIDUAL (a)** unless `X` | Step-2.5 gate / X |
| 15 | whole-datadir clone rollback (WAL+anchor+state moved together to an older consistent point) | **RESIDUAL (a)** unless `X` — term & head stay mutually consistent so the gates don't fire | X only |
| 15b | single-shard wipe→FRESH (delete anchor + truncate WAL to 0 + delete snapshot blob for one shard) | **DETECTED (AS-BUILT Gate 3b)** — the node-anchor's `shardAnchorDigest` binds per-shard `lastDurableIndex`; a wiped shard boots FRESH (its `raft-anchor` absent) with head reset to 0 ⇒ digest differs AND a shard is FRESH ⇒ node-anchor cross-check REFUSE. Raised from silent-loss to a detected node-anchor rollback (= R-a). The anchor-rolled-to-an-older-valid-slot variant (file NOT deleted) stays matrix-14 residual-(a) → `X` | node-anchor `shardAnchorDigest` (§2.5) / X |
| 16 | keyring entry strip/swap/add/truncate | **DETECTED** — outer keyring MAC over the whole body | E (§A2.2) |
| 17 | keyring rollback to a prior valid slot | **RESIDUAL (a)** unless `X`; but `activeTerm` can only be *dropped*, and old-term data still reads (no data loss, only a stale active term for new writes) | S + X |
| 17b | topology-descriptor tamper (edit N or epoch) | **DETECTED** — the standalone descriptor is envelope-MAC'd (protocol §2.7) | E |
| 17c | topology-descriptor rollback (swap for older valid) | **DETECTED** — node-anchor binds `{topologyEpoch,shardCount}`; boot cross-check mismatch ⇒ REFUSE (v1 static-N has no prior legit topology to roll to anyway) | E + §A1.6 |
| 17d | anchor-state rollback across a vote/term boundary the WAL witnesses | **DETECTED (NEW, F-1)** — `lastWALTerm > anchor.currentTerm` ⇒ REFUSE (the retired `max()` rule would have silently accepted it + cleared the vote) | Step-2.5 gate |
| **False-positive rows (MUST NOT trip):** | | | |
| 18 | torn anchor write (crash, not attack) | **NOT REFUSED** — torn slot fails CRC/MAC, the other valid slot (lower seq) wins | S |
| 19 | legitimate Raft conflict truncation | **NOT REFUSED** — INV-ANCHOR-LOWER lowers the anchor first; recovery sees `W≥A` (accept-forward re-adopts uncommitted entries, re-truncated by the leader) | §A1.5 |
| 20 | legitimate compaction (WAL rewrite + snapshot advance) | **NOT REFUSED** — head unchanged; `snapshotIndex` advances in the anchor after `persistSnapshot`; a crash leaves the anchor lagging (accept-forward) | §A1.5 |
| 21 | crash between WAL fsync and anchor fsync | **NOT REFUSED** — `W>A` accept-forward; entries were never committed-and-client-acked | §A1.4 Step 3 |
| 22 | signing-key rotation (legitimate) | **NOT REFUSED** — rewrap-before-swap; roots unchanged ⇒ all terms verify | §A2.5 |

False-positive analysis is first-class: rows 18–22 prove no legal crash or Raft transition bricks a
node. The whole design rests on the single asymmetry of row 21 vs row 2/3 — *the anchor may lag
durable data (accept-forward) but must never lead it (refuse)* — which is sound only because
anchor-before-ack (§A1.5) makes `A`, at every node, an upper bound on every index that node durably
contributed to a **committed-and-client-acked** entry.

---

## 4. Residuals (documented, not overclaimed)

- **R-a Anchor / keyring / whole-datadir rollback to a prior legitimately-authenticated state**
  (matrix 14,15,17): out of scope without external monotonic storage; the §A1.7 `AnchorWitness`
  SPI is the only closer. This is the dm-verity/AVB/Vault line. **Narrowed by reliability F-1:** an
  anchor/state rollback that crosses a term/vote boundary the WAL *witnesses*
  (`lastWALTerm > anchor.currentTerm`) is now DETECTED by the Step-2.5 term-witness gate (matrix
  17d). R-a is thus reduced to rollbacks that do NOT lower a log-witnessed term — a within-term
  index rollback, or a whole-datadir clone where WAL and anchor move together and stay mutually
  consistent (matrix 15).
- **R-b Adversary holding the signing key** forges anything (fate-sharing of the local provider,
  `LocalDerivedKmsProvider.java:36-44`): mitigated only by graduating to an off-host KMS
  (`wrapAlgId=2`), which moves root custody off the node.
- **R-c `VerifyKeyExporter` DER export** (inventory §16, raw X.509, no frame): an operator export,
  not server state — left unanchored, documented.
- **R-d Passive confidentiality of `raft.persistent_state`/anchor fields** in HMAC-only mode: term
  and votedFor are integrity-protected but cleartext (as today). Enabling encryption covers them.
- **R-e Un-anchored audit tail** (§A1.6): audit-head anchoring is periodic (reliability's K=64
  records / 1 s), so truncation confined to the last ≤K records / ≤1 s before a crash is undetected.
  Bounded and documented; strictly better than today's fully-undetected audit chain.

---

## 5. Interfaces to the other lanes

- `[PROTOCOL]` new magics: `ANCHOR_MAGIC 0x52414E43 "RANC"`, `NODE_ANCHOR_MAGIC 0x524E414E "RNAN"`,
  `KEYRING_MAGIC 0x524B5952 "RKYR"` (each doubles as its file's container-header sigil,
  `fileVersion:u8=1`); **retire `STATE_MAGIC "RFST"`** (state merged into the anchor, §A1.1);
  `SNAPMETA_MAGIC` not needed (folded, §SEC-1). `IntegrityEnvelope.FORMAT_VERSION 2→3` (adds
  `scopeId`, and `keyTerm` in the HMAC body — a from-scratch layout change); `keyringFormatVersion=1`;
  integrity info bumps to `.../v3`. I **adopt** your §2.1 Δ (CRC-before-version + explicit
  `reserved != 0 ⇒ throw`) in the shared envelope; my files inherit both. All get a leading
  magic+version readable before crypto; unknown ⇒ fail closed. Please register these and confirm no
  collision with the wire/edge codecs (disjoint namespaces — RaftArtifactMagic vs
  FrameCodec/EdgeFrameCodec — but the registry is yours).
- `[RELIABILITY]` place INV-ANCHOR-ACK (leader) at the `flushDurable`/`maybeAdvanceCommitIndex`
  barrier (`RaftNode:2223-2237, 2153-2175`), INV-ANCHOR-ACK (follower) before the AppendEntries
  ACK (`RaftLog.appendEntries:448-451`), and INV-ANCHOR-LOWER in
  `truncateFrom`/`appendEntries`/`compact`. I own the invariant + proof (§A1.5); you own the
  scheduling and the group-commit amortization of the second fsync.
- `[JDE]` the anchor and node-anchor are dual-slot fixed-offset `pwrite`+`fsync` files (a small
  `AnchorFile` writer), NOT `Storage.put` (tmp+rename). The keyring IS a `Storage.put`-style
  atomic artifact but held in two slots for the signing-key handover (§A2.4).

## 6. Interface resolutions with the protocol lane (verbatim for the assembled doc)

Settling the four points in the lead's contract + protocol §5. My files reuse the
**`IntegrityEnvelope` as their authentication carrier**, so their "self-version" is the envelope's
`magic || formatVersion` (a NEW per-file `*_MAGIC` + `formatVersion=3`), NOT a separate per-file
version integer — this reconciles the protocol lane's `formatVersion=1` placeholder in their
§2.13/§2.15 to **`formatVersion=3` discriminated by a new magic**. Each *file* additionally carries
an unauthenticated 8-byte container header (`[*_MAGIC][fileVersion:u8=1][flags:u8][reserved:u16]`)
for foreign-file/version rejection at offset 0, exactly as protocol §2.2 rules for the WAL container.

- **⟦SEC-1⟧ snapshot-meta → FOLDED into the anchor. RESOLVED, I own it.** `snapshotIndex` +
  `snapshotTerm` are authenticated fields of the per-shard `ANCHOR_PAYLOAD` (§A1.3). The bare
  `raft-log.snapshot-meta.dat` **ceases to exist** in the frozen layout; `SNAPMETA_MAGIC` is not
  allocated. Protocol §3 row 7 resolves to "folded into anchor."
- **⟦SEC-2⟧ topology descriptor → STANDALONE (protocol owns the file), anchor BINDS its epoch.
  RESOLVED.** The versioned `TopologyDescriptor {formatVersion, N, topologyEpoch}` stays a
  node-level standalone envelope-authenticated file (protocol §2.7) — it is the authoritative
  `ShardMap.epoch()` source and the fixed-N boot guard, cluster-consistent and deploy-authored,
  which are protocol/routing concerns, not per-shard-durability concerns. My node-anchor binds a
  **copy** of `{topologyEpoch, shardCount}` and recovery cross-checks equality (§A1.6), so a
  topology-file **rollback** (not just tamper) is caught. It MUST be envelope-authenticated
  (agreed). `topologyEpoch=0` reserved-illegal; v1=1.
- **⟦SEC-3/4⟧ keyring + anchor follow the §0 convention. RESOLVED.** Leading `magic || formatVersion`
  readable before the crypto check (via both the container header and the per-slot envelope);
  unknown magic/version/`wrapAlgId`/`term` ⇒ **fail loud, closed**; `reserved` MBZ with an explicit
  `!= 0 ⇒ throw` (I adopt the protocol §2.1 Δ: **CRC-before-version** ordering and the
  `reserved != 0` check in the shared envelope — my files inherit both). Keyring per-entry
  **AAD binds `(keyringFormatVersion, term, wrapAlgId, scope=nodeKeyId‖"root")`** (§A2.2). **Term 0
  reserved-illegal**; rotation is **append-a-term, all retained, unknown-term fail-closed**
  (§A2.5). `version==0` illegal everywhere (envelope `formatVersion=3≠0`).
- **CRC unification → already CRC32C. RESOLVED.** All my new files authenticate via
  `IntegrityEnvelope` (CRC32C) per slot; the container header and `recordLen`/pad are not CRC'd (a
  corrupt one just fails the slot ⇒ the other slot wins ⇒ fail-closed). **No CRC32/zlib anywhere in
  the anchor/node-anchor/keyring.** Consistent with the protocol §0.4 "one CRC family, CRC32C" ruling.

- **⟦SEC-MERGE⟧ (raise for ratification):** my A1.1 merge **removes `raft.persistent_state.dat`**
  (its `currentTerm`/`votedFor` move into the per-shard anchor). This **supersedes protocol §2.4 and
  §3 row 3** — strike that row; **`STATE_MAGIC` (RFST) is retired** (unused after the merge). If
  ratification rejects the merge, the fallback is protocol §2.4 as-is PLUS a standalone monotonic
  sequence on `raft.persistent_state` (which is re-inventing a mini-anchor) — I recommend the merge.

## 7. Prior-art citations
Vault keyring/term model (§A2): prior-art §Q3, §Q6. bbolt/LMDB dual-meta head anchor (§A1.3):
§Q1d. CT STH authenticated high-water mark shape (§A1): §Q1b. Postgres/etcd fsync ordering &
the anchor-lag asymmetry (§A1.5): §Q2, §1a. dm-verity/AVB/Vault anchor-rollback boundary
(threat model, R-a): §1c. JWE/age/KMS AAD-binds-scope for the wrapped-key envelope (§A2.2): §Q6.
Kafka magic-before-CRC version marker (§A1.2): §Q4.

---

# §5 Protocol record — version-marker scheme (per-format table, completeness enumeration)

# Configd Frozen-Format Version-Marker Scheme (Group-A)

Protocol-expert lane. Repo `/home/ubuntu/Code/Configd`, main @ `012e213`. **No production code
changes in this session** — this is the design the build arc executes. Every as-built claim below
is verified against source with a `file:line` cite. Clean break: nothing has shipped, so there is
**no migration/compat baggage** — the freeze may redefine any as-built layout, and *should* delete
un-versioned legacy acceptance paths rather than preserve them.

Assumptions about the **security lane's** new files (anchor file, wrapped-key keyring, topology
descriptor) are flagged `⟦SEC-ASSUMPTION-n⟧`. Where this design and the security lane both want to
own a file (snapshot-meta, the shard-count marker), I state my assumption **and** the fallback.

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
   makes decoding safe (an unknown discriminant/type byte ⇒ throw). The carrier's version pins the
   inner grammar. This is the correct — not the lazy — choice when adding a redundant inner version
   byte would bloat a hot path (e.g. every `CommandCodec` command in every WAL entry) for no
   decode-time benefit, **provided the carrier argument holds for *every* carrier the bytes ride in.**

3. **Documented-export.** An operator-facing artifact in a **self-describing industry standard**
   (X.509 / PKCS#8 DER) consumed by standard tooling (`openssl`), never re-parsed by Configd as
   server state. Its "version" is the standard's own structure. Documented, not wrapped.

### 0.1 Placement — magic and version, then the integrity check

- **Leading `magic || version`, at a fixed offset, readable before the version-*dependent* check.**
  Kafka moved `magic` *ahead* of the CRC in message-format v2 precisely so a reader can pick the
  format before it knows how to verify (prior-art §Q4).
- **The version-*independent* CRC runs first for error attribution.** Configd's CRC is a fixed
  trailer with a fixed algorithm (see §0.4) — it does **not** need the version to be located or
  computed. So the correct ordering is: **CRC (needs no version) → read version from CRC-validated
  bytes → version-dependent MAC/GCM/grammar.** This satisfies both principles at once: a bit-flip
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
  `SigningKeyStore`=1, edge=1/2/3, raft wire=0x02) — freeze that: 0 is "unset/torn," rejected. A
  reader that sees version 0 fails closed.
- **`version == MAX` (`0xFF` for `u8`, `0xFFFF` for `u16`) is reserved as the future "extended
  version" escape.** Do **not** allocate it in v1. It is the pre-agreed door for a post-freeze
  format break (a v1 reader rejects it — fail closed — but a future reader knows the slot's meaning).
- **MBZ reserved fields must be 0 on write; a non-zero value fails closed on read.** Where the field
  is MAC/AAD-covered, a non-zero value is *either* tamper *or* a newer writer — both must fail
  closed, so "covered by the MAC" is **not** a substitute for an explicit `== 0` check (see
  `IntegrityEnvelope.reserved` and `FrameCodec.epoch`, §2).

### 0.3 Unknown-version / unknown-magic ⇒ fail LOUD and CLOSED

A reader at frozen-v1 **never** best-effort-parses. The four failure inputs and their required
behavior (tested per §4):

| Input | Required behavior |
|---|---|
| **Unknown magic** (full-length buffer, wrong sigil) | **Throw** under any authenticated posture. (`IntegrityEnvelope:275-280` already does this; a *sub-floor* buffer `< HEADER+CRC` is "structurally absent" ⇒ `null`, which is correct and kept.) |
| **Known magic + higher version** | **Throw** — refuse; never parse a newer format with an older grammar. |
| **Known magic + version 0** | **Throw** — reserved-illegal (§0.2). |
| **Structurally-valid-but-legacy (un-versioned) form** | **Throw / reject.** Clean break: the legacy acceptance paths are **deleted** (see §0.5). |

### 0.4 CRC family — UNIFY to CRC32C (flag 1)

As-built there are **two** CRC families: `CRC32` (zlib, `java.util.zip.CRC32`) in the FileStorage WAL
frame *only* (`FileStorage.frame()` / `readLog()`), and `CRC32C` (Castagnoli) everywhere else
(`IntegrityEnvelope`, `FrameCodec`, `EdgeFrameCodec`). This is a footgun for anyone writing
recovery/verification tooling (two checksum routines over the same file — the outer frame vs the
inner envelope).

**Ruling: unify to CRC32C in the clean break.** The FileStorage container CRC is a *corruption /
torn-tail* detector only — authentication is the inner `IntegrityEnvelope` (CRC32C + MAC/GCM), so
this is not a security change, only a consistency one. Cost: 2 sites (`frame()`, `readLog()`); it
invalidates any existing on-disk WAL (fine — nothing shipped) and there is **no** WAL golden fixture
to regenerate (`WalWireCompatStubTest` is a `@Disabled` stub with no vector). Document the invariant:
**"one CRC family, CRC32C/Castagnoli, system-wide; a container CRC is corruption-only, authentication
is always the inner envelope."** (Alternative considered — *bless-and-document* two families — is
rejected: freeze-forever is the one moment to remove the discrepancy for free, and CRC32C has the
same HW acceleration, so there is no perf reason to keep zlib CRC32.)

### 0.5 Clean-break deletions (kill un-versioned legacy acceptance paths)

Nothing shipped ⇒ no legacy artifact exists ⇒ the readers that tolerate un-versioned forms are dead
weight that only *weaken* the frozen format. **Delete them:**

- **WAL legacy raw-record fallback.** `RaftLog.deserializeEntry` (`:647-660`) falls back to
  `body = raw` when `unwrapOrNull` returns `null` (a pre-envelope record). Under freeze **every** WAL
  record MUST be enveloped; a non-enveloped record ⇒ fail closed. (Keep `unwrapOrNull`'s sub-floor
  `null` = "absent/torn-tail" for the empty/first-boot file case; that is torn-tail handling, not a
  legacy-format path.)
- **Snapshot trailer legacy forms (a) and (c).** `ConfigStateMachine.decodeTrailer` (`:482-527`)
  accepts three forms: (a) empty=legacy, (b) magic-TLV canonical, (c) bare 8-byte epoch. The writer
  emits **only** (b) (`SNAPSHOT_TRAILER_MAGIC` + len + epoch, unconditionally, per inventory §5).
  **Freeze to accept only (b);** (a) and (c) ⇒ malformed ⇒ throw. (Build arc must confirm no path
  emits a trailer-less or raw-8-byte snapshot — `serialize()` does not.)

---

## §1. Version 0-illegal / magic-0-illegal summary (the freeze's reserved values)

| Field | Frozen rule |
|---|---|
| any `magic` | `0x00000000` illegal |
| `u8` version | `0x00` illegal; `0xFF` reserved-escape (unallocated) |
| `u16` version | `0x0000` illegal; `0xFFFF` reserved-escape (unallocated) |
| MBZ reserved | `0` on write; non-zero ⇒ fail closed on read |
| topology epoch | `0` reserved = "unset/pre-epoch" ⇒ reject; v1 uses `1` (see §2.9) |

---

## §2. Per-format decision table

Legend for **Kind**: `SV`=self-versioned, `CV`=carrier-versioned+assert, `DE`=documented-export.
"Δ" marks a build-arc change from as-built.

### 2.1 IntegrityEnvelope — `configd-common/.../IntegrityEnvelope.java` — **SV, adequate (best in repo)**
As-built: `[magic:4][formatVersion:2 = 2][algId:1][reserved:1]` header (`:76-79`), CRC32C trailer,
MAC/AAD covers the whole header incl. reserved. Version gate throws on `formatVersion != 2`
(`:299-305`); unknown `algId` throws (`:330`); downgrade `algId=NONE`-under-key throws (`:322`).
- **Decision: confirm adequate; freeze `formatVersion = 2`, `HEADER_SIZE = 8`.** This is the model
  the whole system inherits.
- **Δ (from §0.1):** reorder to **CRC-before-version** for error attribution.
- **Δ (from §0.2):** the **`reserved` byte is the future escape** — freeze it MBZ and add an explicit
  **`reserved != 0 ⇒ throw`** check (today it is MAC-covered but never validated `== 0`, `:307`), so
  a future writer that repurposes it can't be silently mis-parsed by a v1 reader. This preserves the
  reserved byte as a genuine forward-compat sub-version/feature-flag slot inside an otherwise
  version-strict format.
- **Unknown-version behavior:** throw (already). **Test obligation:** existing
  `rolledFormatVersionThrows` (`IntegrityEnvelopeTest:105`) + **new** `reservedNonZeroThrows`,
  `corruptHeaderReportsCrcNotVersion`.

### 2.2 FileStorage WAL frame + WAL file — `configd-common/.../FileStorage.java` — **container: Δ SV; records: CV**
As-built: per-entry frame `[len:4][data:N][CRC32-zlib:4]` (`frame():213`); the file itself has **no
header** (frames start at offset 0). Torn-tail discard at `readLog:271`.
- **Decision — records:** the `data` is always a self-versioned `IntegrityEnvelope` (Raft) or a
  self-versioned audit record (§2.8), so **records are CV** by their envelope/record magic.
- **Δ Decision — the container:** add a **one-time WAL file header** so the container itself
  self-identifies rather than relying only on its records:
  `[WAL_FILE_MAGIC:4][fileVersion:u8 = 1][flags:u8 = 0][reserved:u16 = 0]` (8 B) at offset 0, written
  as the leading bytes on first append and fsync'd before the first frame is acked. `readLog`: file
  `< 8 B` ⇒ empty/fresh; else validate header **first** (bad magic/version ⇒ fail closed), then the
  existing frame scan + torn-tail discard runs unchanged. This closes flag-5's "WAL container is
  unversioned" row and is the right call under freeze-forever (the framing never changes again, so
  the header's job is foreign-file rejection + a named forward-compat `flags` slot). Applies
  identically to `raft-log.wal`, the transient `raft-log.tmp.wal`, and `security-audit.wal`.
- **Δ CRC:** `CRC32` → `CRC32C` (§0.4).
- **Unknown-version behavior:** bad file-header magic/version ⇒ throw (refuse to load the WAL). A
  torn *record* tail is discarded (crash-consistency, unchanged); a complete-but-tampered record
  fails the inner envelope (unchanged). **Test obligation:** `walFileHeaderBadMagicRejected`,
  `walFileHeaderHigherVersionRejected`, `emptyWalFileIsFresh`, `headerOnlyFileIsFresh`.

### 2.3 RaftLog inner WAL record — `configd-consensus-core/.../RaftLog.java` — **CV**
As-built inner payload `[index:8][term:8][command:N]` then `integrity.wrap(WALE_MAGIC, …)`
(`serializeEntry:627-633`).
- **Decision: CV** — carrier is the `WALE_MAGIC` `IntegrityEnvelope` (`formatVersion=2`). No inner
  version byte. **Assert:** the envelope unwraps and MAC-verifies before these bytes are trusted.
- **Δ:** delete the legacy raw-record fallback (§0.5).
- **Test obligation:** `nonEnvelopedWalRecordRejectedUnderKey` (the deleted-fallback negative).

### 2.4 raft.persistent_state — `configd-consensus-core/.../DurableRaftState.java` — **CV**
`[currentTerm:8][votedFor:4]` then `wrap(STATE_MAGIC, …)` (`:157-163`). **CV** by `STATE_MAGIC`
envelope v2 — already covered by `IntegrityEnvelopeTest`. No change. **Test obligation (transitive):**
`forgedVotedForRefused`.

### 2.5 Snapshot blob (Raft) — `RaftLog.serializeSnapshot` + `ConfigStateMachine` — **CV + inner trailer CV**
Blob `[lastIncludedIndex:8][lastIncludedTerm:8][dataLen:4][data][configLen:4(-1=null)][config]`
then `wrap(SNAP_MAGIC, …)`. Inner `data` = `ConfigStateMachine.snapshot()`, which ends with a
**magic-TLV trailer** `[SNAPSHOT_TRAILER_MAGIC=0xC0FD7A11:4][trailerLen:4][signingEpoch:8]`
(`:559`, `:487`).
- **Decision: CV** for the blob (carrier `SNAP_MAGIC` envelope v2). The inner trailer already has its
  own magic-TLV (a mini self-versioned sub-format keyed by magic, with `trailerLen` = the
  forward-compat length escape — an *unknown tail beyond the 8-byte epoch is tolerated*, `:507-510`,
  which is the correct TLV forward-compat behavior and is kept).
- **Δ:** kill legacy trailer forms (a)/(c) (§0.5) — accept only the magic-TLV.
- **Unknown-version behavior:** unknown trailer magic / out-of-range `trailerLen` ⇒ throw (`:490-499`,
  kept). **Test obligation:** `snapshotTrailerLegacyEmptyRejected`, `snapshotTrailerRaw8Rejected`,
  `snapshotTrailerUnknownTailTolerated` (keep the TLV forward-compat path green).

### 2.6 raft-log.snapshot-meta — `RaftLog.compact` — **Δ (bare 16 B → CV or folded)  ⟦SEC-ASSUMPTION-1⟧**
As-built: **bare 16 B `[snapshotIndex:8][snapshotTerm:8]`, NO magic / NO version / NO CRC / NO MAC**
via `storage.put` (`:584-587`). This is the single worst-protected persistent artifact and it names
the durable-prefix boundary, so it is security-critical.
- **Assumption ⟦SEC-ASSUMPTION-1⟧:** the security lane's **authenticated anchor file** subsumes the
  snapshot index/term (an anchor that MACs `(index/count + hash-over-head)` naturally carries the
  snapshot boundary). **Preferred:** snapshot-meta ceases to exist as a standalone file; its two
  fields live in the anchor (self-versioned by the anchor's own `magic||version`).
- **Fallback (if it stays standalone):** wrap it in `IntegrityEnvelope` under a **new
  `SNAPMETA_MAGIC`** → instantly SV/CV with `formatVersion=2`, CRC32C, and (under a key) a MAC. Never
  leave it bare.
- Either way it becomes non-blank in §3. **Test obligation:** `snapshotMetaTamperedRefused` (belongs
  to whichever lane owns the file).

### 2.7 raft-shard-count.meta → **versioned topology descriptor** — `ConfigdServer.enforceFixedShardCount` — **Δ SV  ⟦SEC-ASSUMPTION-2⟧**
As-built: **plain UTF-8 decimal `N`** (`SHARD_COUNT_MARKER`, `:125`, write `:1521-1528`), temp+fsync+
atomic-rename; a different `N` on restart ⇒ refuse to start (`:1507-1514`). No magic/version.
- **Decision: replace with a single authenticated, versioned topology descriptor** that serves BOTH
  the fixed-N deploy guard AND A4's epoch source:
  ```
  TopologyDescriptor  (wrapped in IntegrityEnvelope under a new TOPO_MAGIC)
    [formatVersion:u16 = 1]      # inner, redundant-with-envelope but explicit for operators
    [shardCount N:u32]
    [topologyEpoch:u64]          # the A4 epoch authority (§2.9); v1 = 1
    [reserved:u32 = 0]
  ```
  Envelope gives magic + CRC32C + (under a key) MAC — so the deploy guard is now tamper-evident, not
  a plaintext integer an attacker can edit to bypass the reshard refusal.
- **⟦SEC-ASSUMPTION-2⟧:** if the security lane's anchor already binds `N`/epoch, fold this into that
  file instead of a second descriptor; the *interface contract* (below) is what matters, not the file
  count.
- **Interface contract (both lanes depend on this):** `ShardMap.epoch()` returns the descriptor's
  `topologyEpoch`; `StaticShardMap` is wired to return it instead of the hardcoded `0`
  (`StaticShardMap.java:86-88`). The value is **cluster-wide-consistent** (all nodes read the same
  deploy-time descriptor); v1 initializes it to `1` at first boot and never bumps (static-N); a v2
  dynamic reshard bumps it monotonically. Reserved `0` = pre-epoch ⇒ illegal.
- **Unknown-version behavior:** bad `TOPO_MAGIC`/`formatVersion` ⇒ refuse to start (same class as the
  current corrupt-marker refusal `:1502-1505`). **Test obligation:** `topologyDescriptorTampered
  RefusesStart`, `topologyEpochZeroRejected`, `reshardNChangeStillRefused`.

### 2.8 security-audit.wal record — `configd-control-plane-api/.../AuditLog.java` — **Δ SV record**
As-built: FileStorage frame ⊃ `[canonicalLen:8][canonical][prevHash:32][recordHash:32]`
(`encode:381-389`); **no magic, no version**; `recordHash = HMAC/ SHA-256(prevHash‖canonical)`
(`chainHash:347`).
- **Δ Decision: prepend a self-versioned record header** inside the FileStorage frame:
  `[AUDIT_MAGIC:4][recordVersion:u8 = 1][canonicalLen:8][canonical][prevHash:32][recordHash:32]`.
- **Bind the version into the tamper-evident chain:** change the chain input to
  `HMAC/SHA-256(AUDIT_MAGIC ‖ recordVersion ‖ prevHash ‖ canonical)` so a version-downgrade of a
  record is detectable, not just a payload edit. (The file also gets the WAL file header from §2.2.)
- **Unknown-version behavior:** bad `AUDIT_MAGIC`/`recordVersion` ⇒ throw on chain verification (the
  audit reader already refuses a broken chain). **Test obligation:** `auditRecordBadMagicRejected`,
  `auditVersionIsChainBound` (flip version, expect MAC mismatch).

### 2.9 WatchCursor + topology epoch — `WatchCursor.java` + `EdgeFrameCodec` — **Δ CV + epoch binding (A4)**
As-built: vector `[count:u32]([gid:u32][S:u64])*` via `encodeCursorInto` (`EdgeFrameCodec.java:427`)
/ `decodeCursor` (`:853`); **no version, no epoch**; server checks gid membership out-of-band only.
The cursor rides inside **WATCH_CREATE** (`:443`), **WATCH_PROGRESS** (`:482`), and **WATCH_CANCELED
oldest** (`:491`) payloads — all edge wire **0x02+** (watch types are 0x02-only,
`EdgeFrameCodec:642`). The `ShardMap` interface **already specifies** the epoch-on-routing-envelope
pattern (TiKV RegionEpoch): "*carry it so a stale router … is told 'wrong epoch, re-resolve' rather
than mis-committing … the v1 StaticShardMap never bumps it (returns 0 forever)*" (`ShardMap.java`
docstring). A4 is the realization of that dormant seam on the edge wire.
- **Decision — the cursor's *format* is CV** by the enclosing edge frame version (0x02/0x03). No
  per-cursor version byte is needed — the frame version pins the cursor grammar, and adding a
  redundant `cursorVersion` byte buys nothing (the cursor never travels outside a versioned frame).
- **Δ Decision — bind the topology epoch (the load-bearing new field).** Prepend the epoch to the
  cursor wire:
  ```
  frozen cursor := [topologyEpoch:u64][count:u32]([gid:u32][S:u64])*count
  ```
  Source of `topologyEpoch` = the §2.7 topology descriptor via `ShardMap.epoch()` (v1 = `1`).
- **Uniform "every resume token binds the epoch" rule (recommended).** The **SUBSCRIBE** scalar
  resume `[…][resume:8][failoverResume:8][…]` (`encodeSubscribeInto:298-309`) is the legacy
  edge-hydration path and is *also* topology-sensitive (a redeploy at a different `N` invalidates a
  scalar seq just as it does a vector). **Prepend `topologyEpoch:u64` before the resume fields** in
  SUBSCRIBE too, so the rule is uniform across every resume/cursor field. (Minimal alternative:
  watches-only. I recommend the uniform rule — one invariant, one test class, no "which frames are
  epoch-checked?" ambiguity.)
- **Server reject on mismatch (etcd `ErrCompacted` model).** A cursor whose `topologyEpoch ≠ current`
  is refused with a **new dedicated `ErrorCode.STALE_TOPOLOGY(12)`** (the enum is closed and
  golden-pinned 1..11, `ErrorCode.java`; clean break extends it by one). Distinct from
  `GAP_UNRECOVERABLE(6)` ("data fell off retention" — resume from an *earlier* position) — `STALE_
  TOPOLOGY` means "the cursor's whole topology generation is invalid, **re-hydrate from scratch**."
  Reject carrier: **WATCH_CANCELED** with `code=STALE_TOPOLOGY` for a watch; **ERROR_CLOSE** with the
  same code for a SUBSCRIBE. Client's only correct recovery: drop the cursor, re-issue from
  `WatchCursor.fromNow()` / a fresh SUBSCRIBE (full re-hydrate).
- **Does this force edge wire 0x04?** **No.** Clean break lets us redefine the 0x02/0x03 payloads in
  place (watches only ever existed on 0x02+, nothing shipped). Keep 0x02 (watch-capable) and 0x03
  (filtered) as-frozen and regenerate the golden vectors. **Build arc MUST update these golden
  fixtures** (they pin the exact cursor/subscribe bytes):
  `configd-distribution-service/src/test/java/io/configd/distribution/wire/EdgeFrameGoldenBytes.java`,
  `EdgeFrameGoldenBytesGenerator.java`, `EdgeFrameCodecGoldenFixtureTest.java`,
  `EdgeFrameCodecV2GoldenFixtureTest.java`, `EdgeFrameCodecV3GoldenFixtureTest.java`. (The raft-wire
  `GoldenFixtures.java` / `WireCompatGoldenBytesTest.java` are **not** touched — FrameCodec is frozen
  as-is, §2.11.)
- **Unknown-version / mismatch behavior:** epoch `0` ⇒ FRAME_CORRUPT (reserved-illegal, §0.2);
  epoch ≠ current ⇒ `STALE_TOPOLOGY`. **Test obligation:** `staleEpochCursorRejectedWithReHydrate`,
  `epochZeroCursorIsFrameCorrupt`, `matchingEpochResumes`, `subscribeCarriesEpoch`, and a
  **resharding negative** (`N=a` cursor replayed at `N=b` ⇒ STALE_TOPOLOGY) — closes 2.9-9.

### 2.10 CommandCodec — `configd-config-store/.../CommandCodec.java` — **CV (carrier holds for all 3 carriers)**
As-built: `[type:1]…` (`0x01`=PUT / `0x02`=DELETE / `0x03`=BATCH), empty = Noop; unknown type ⇒
throw (`:17-20`). No format version.
- **Decision: CV + assert.** The type byte is a *discriminant*, not a version. These bytes appear in
  **three** carriers, and the carrier argument holds for **all** of them: (1) WAL entries — inside
  the `WALE_MAGIC` envelope (v2); (2) NOTIFY deltas — inside the edge frame (version byte); (3)
  snapshot values — inside the `SNAP_MAGIC` envelope / the edge snapshot body which itself rides an
  edge frame. Every carrier is self-versioned, so no inner version byte is warranted (it would bloat
  every command in every log entry for zero decode benefit). **Document "never standalone; versioned
  by carrier" + keep the unknown-type throw as the assert.**
- **Unknown behavior:** unknown type byte ⇒ throw (kept). **Test obligation:** `commandUnknownType
  Throws` (exists) + a doc-comment stating the carrier list.

### 2.11 Edge/raft wire that is already versioned — **SV, confirm; one Δ on the dormant epoch**
- **Edge frame** `EdgeFrameCodec` — `[len:4][version:1 = 01/02/03][type:1][payload][CRC32C:4]`
  (`:1111`, decode `:578`), first-frame version pin (`:626`). **SV, confirmed.** Per-type payloads =
  **CV** by this version byte (assert: watch types 0x0A-0x12 legal only on 0x02, `:642`, kept).
- **Raft transport frame** `FrameCodec` — `[len:4][ver:1 = 0x02][type:1][gid:4][term:8][epoch:8]
  [payload][CRC32C:4]`; CRC-before-version (`:285-303`, the reference discipline). **SV, confirmed;
  freeze `WIRE_VERSION=0x02`.**
  - **Δ on the dormant `epoch` slot (flag 9).** Offset 18, 8 B, `RESERVED_EPOCH=0`, currently
    **decode-but-ignore** (`:309`). This is the raft-wire twin of the A4 topology epoch (same
    RegionEpoch concept, consensus layer). **Freeze it MBZ and upgrade decode-but-ignore →
    reject-if-nonzero** (fail closed), per §0.2: in v1 no legitimate peer sets it, so a non-zero
    value is corruption (already CRC-caught) or a newer peer we cannot safely talk to. Golden vectors
    carry `epoch=0` so they stay valid; this only *adds* a negative test. **Test obligation:**
    `nonZeroReservedEpochRejected`.
- **RaftMessageCodec payloads** (`configd-server/.../RaftMessageCodec.java`) incl. chunked
  InstallSnapshot fields — **CV** by the FrameCodec version. No change (caps already bound sizes).
- **EdgeSnapshotCodec chunk body** (`EdgeSnapshotCodec.java:81`) — lead `u64` = `snapshot.version()`
  = **DATA seq, NOT a format version.** **CV** by the enclosing edge frame version. **Document the
  data-seq-not-format-version distinction** (this is the exact confusion 2.9-11 flags) + keep the
  field-bound assert. **Test obligation:** doc-comment + `edgeSnapshotBodyIsCarrierVersioned`.

### 2.12 signing-key.bin — `configd-config-store/.../SigningKeyStore.java` — **SV, adequate; build-arc fixes only**
`[magic 0xC0DF_51C5:4][version:2 = 1][keyId:16][privLen:4][privDER][pubLen:4][pubDER]` (`:40-41`,
`:118-127`); load validates magic+version strict (`:79-85`). **SV, confirmed adequate — freeze
`version = 1`.** Non-format build-arc items (NOT format changes):
- **Durability:** `Files.write(CREATE_NEW)` (`:134`) has **no fsync and no atomic-rename** — a crash
  during first generation can leave a torn key file; fix to temp+fsync+atomic-rename+dir-fsync
  (mirror `FileStorage.put`).
- **flag 3:** docstring `:29` says `0xC0DF51G5` (invalid hex `G`); code is `0xC0DF_51C5` — doc fix.
- **flag 4:** `writeForTest` (`:169`) computes `PosixFilePermissions.fromString("rw-------")` but
  **never applies it** (latent no-op, test-only) — delete or apply.

### 2.13 WrappedKey persisted keyring — `configd-common/.../kms/*` — **⟦SEC-ASSUMPTION-3⟧ SV-from-birth, contract only**
As-built: **nothing persisted** for the `local` provider — the `WrappedKey` is a 0-byte descriptor
regenerated each boot (`LocalDerivedKmsProvider`, inventory §10). The security lane designs the
keyring *file* for a future persisting provider. **I own the marker/envelope convention it MUST
follow** (synthesis of prior-art §Q6 — Vault keyring / JWE / age):
```
Keyring file (SV, from birth):
  [KEYRING_MAGIC:4][formatVersion:u16 = 1][keyCount:u32]
  per key:  [term:u32][wrapAlgId:u8][wrapNonce:12][wrappedDekLen:u32][wrappedDek][gcmTag:16]
  ...wrapped under the root/unseal key; the WHOLE file MAY additionally ride an IntegrityEnvelope.
```
**Contract clauses the security lane's file MUST satisfy:**
- Leading `magic || formatVersion`, readable before the crypto check; unknown magic/formatVersion/
  `wrapAlgId` ⇒ **fail closed**.
- Each wrapped-DEK's **AAD binds `(formatVersion, term, wrapAlgId, scope/purpose)`** so a wrapped key
  lifted from one slot cannot be replayed into another (JWE header-as-AAD / KMS encryption-context /
  age header-MAC).
- **Rotation = append a term; every historical term retained for decrypt; unknown term ⇒ fail
  closed** (already the `SegmentKeyManager.resolveDek` contract, `:165`). Reserve `term=0` illegal
  (mirrors the hardcoded-`term=1` boot value, inventory §13). This is the A5 marker convention;
  the *rotation lifecycle* is the security lane's (A2).
- **Test obligation (security lane):** `keyringBadMagicFailClosed`, `keyringUnknownWrapAlgFailClosed`,
  `wrappedDekReplayIntoOtherSlotFailsAAD`, `unknownTermFailClosed`.

### 2.14 VerifyKeyExporter output — `configd-config-store/.../VerifyKeyExporter` — **DE**
Raw X.509 **SubjectPublicKeyInfo DER** written to an operator path (inventory §16). **Decision:
documented-export** — DER is self-describing (ASN.1 structure + algorithm OID), consumed by standard
tooling; no custom magic/version. Its "version" is the X.509 standard's own. Document it as such;
never re-parse as server state. **No test obligation** beyond "round-trips through `openssl pkey`."

### 2.15 Security lane's NEW anchor file — **⟦SEC-ASSUMPTION-4⟧ SV-from-birth, contract only**
The truncation/rollback anchor (A1) that MACs `(index/count + hash-over-head)`. **Contract:** SV from
birth — `[ANCHOR_MAGIC:4][formatVersion:u16 = 1]…`, magic+version before the MAC check, unknown ⇒
fail closed, MBZ reserved per §0.2. If it subsumes snapshot-meta (⟦SEC-ASSUMPTION-1⟧) and/or the
topology descriptor (⟦SEC-ASSUMPTION-2⟧), those fields inherit the anchor's version story. Dual-slot
A/B sequence numbers (prior-art §Q1d) are the security lane's structural choice; the **marker
convention** above is the only thing this lane mandates.

---

## §3. Completeness enumeration — every artifact × its version story

Checklist basis: inventory §15/16 file sweep + the wire codecs. **No row may be blank.** Kinds:
`SV` self-versioned · `CV` carrier-versioned+assert · `DE` documented-export.

### On disk (N=1 under `dataDir/`; N>1 repeats the per-shard rows under `dataDir/shard-<gid>/`)

| # | Artifact | Kind | Version story | Δ? |
|---|---|---|---|---|
| 1 | `signing-key.bin` (node-level) | SV | magic `0xC0DF_51C5` + `version=1` | fixes only (§2.12) |
| 2 | topology descriptor (was `raft-shard-count.meta`, node-level) | SV | `TOPO_MAGIC` envelope + inner `formatVersion=1`; holds `N`+`topologyEpoch` | Δ (§2.7) |
| 3 | `raft.persistent_state.dat` (per-shard) | CV | `STATE_MAGIC` envelope v2 | — |
| 4 | `raft-log.wal` file (per-shard) | SV | `WAL_FILE_MAGIC` + `fileVersion=1` header | Δ (§2.2) |
| 4a | `raft-log.wal` records | CV | `WALE_MAGIC` envelope v2 (legacy-raw path deleted) | Δ (§0.5) |
| 5 | `raft-log.tmp.wal` (transient, per-shard) | SV | identical to #4/#4a (compaction rewrite) | Δ (§2.2) |
| 6 | `raft-log.snapshot.dat` (per-shard) | CV | `SNAP_MAGIC` envelope v2 | — |
| 6a | snapshot inner trailer | CV | magic-TLV `0xC0FD7A11` (forms a/c deleted) | Δ (§0.5) |
| 7 | `raft-log.snapshot-meta.dat` (per-shard) | SV/CV | folded into anchor ⟦SEC-1⟧ **or** `SNAPMETA_MAGIC` envelope | Δ (§2.6) |
| 8 | `security-audit.wal` file (node-level, if audit on) | SV | `WAL_FILE_MAGIC` header | Δ (§2.2) |
| 8a | `security-audit.wal` records | SV | `AUDIT_MAGIC` + `recordVersion=1`, chain-bound | Δ (§2.8) |
| 9 | keyring file (node-level, **only if a persisting KMS provider ships**) | SV | `KEYRING_MAGIC` + `formatVersion=1` ⟦SEC-3⟧ | new-from-birth |
| 10 | anchor file (node-level, security lane) | SV | `ANCHOR_MAGIC` + `formatVersion=1` ⟦SEC-4⟧ | new-from-birth |
| 11 | VerifyKeyExporter output (operator path) | DE | X.509 SubjectPublicKeyInfo DER | — |
| — | KMS `RootKey`/`WrappedKey` (`local`) | n/a | **in-memory only**, 0-byte descriptor, never on disk | — |

### On the wire

| # | Artifact | Kind | Version story | Δ? |
|---|---|---|---|---|
| 12 | Raft transport frame (`FrameCodec`) | SV | `WIRE_VERSION=0x02` byte, CRC-before-version | Δ epoch reject (§2.11) |
| 12a | dormant frame `epoch` (offset 18) | — | MBZ reserved; reject-if-nonzero | Δ (§2.11) |
| 13 | `RaftMessageCodec` payloads (+ chunked InstallSnapshot) | CV | by FrameCodec version | — |
| 14 | Edge frame (`EdgeFrameCodec`) | SV | version byte `01/02/03`, first-frame pin | — |
| 15 | Edge per-type payloads | CV | by edge frame version (watch-types-on-0x02 assert) | — |
| 16 | WatchCursor (WATCH_CREATE/PROGRESS/CANCELED) | CV | by edge frame version **+ `topologyEpoch` binding** | Δ (§2.9) |
| 17 | SUBSCRIBE scalar resume | CV | by edge frame version **+ `topologyEpoch` prepend** | Δ (§2.9) |
| 18 | `CommandCodec` bytes (WAL / NOTIFY / snapshot value) | CV | by each of 3 carriers (all self-versioned) | doc only (§2.10) |
| 19 | `EdgeSnapshotCodec` chunk body | CV | by edge frame version (lead u64 = data-seq) | doc only (§2.11) |
| 20 | NOTIFY delta / `ConfigDelta.signingPayload` | CV | by edge frame version | — |
| 21 | `IntegrityEnvelope` (the shared carrier itself) | SV | `formatVersion=2`, best-in-repo | Δ CRC-order + reserved check (§2.1) |

Every row is `SV`, `CV`, or `DE`. No blank. Transient (#5) and per-shard N>1 layout are enumerated.

---

## §4. Fail-closed semantics + per-format test obligations (the build arc's checklist)

**Uniform reader contract** (every SV format; every CV format via its carrier):

| Input | Behavior | Where tested (build arc adds/keeps) |
|---|---|---|
| **Unknown magic** | Throw (authenticated posture); sub-floor buffer ⇒ absent/`null` only for the torn-tail/first-boot case | `IntegrityEnvelopeTest` (magic mismatch), `walFileHeaderBadMagicRejected`, `auditRecordBadMagicRejected`, `topologyDescriptorTamperedRefusesStart` |
| **Known magic + higher version** | Throw — never parse newer with older grammar | `rolledFormatVersionThrows` (envelope), `walFileHeaderHigherVersionRejected`, edge `wrongVersionWithValidCrcIsRejectedAsBadVersion` (kept) |
| **Known magic + version 0** | Throw — reserved-illegal | `versionZeroRejected` (per SV format), `epochZeroCursorIsFrameCorrupt`, `topologyEpochZeroRejected` |
| **Structurally-valid legacy (un-versioned) form** | Reject — legacy paths deleted (§0.5) | `nonEnvelopedWalRecordRejectedUnderKey`, `snapshotTrailerLegacyEmptyRejected`, `snapshotTrailerRaw8Rejected` |
| **Corrupt header (bit-flip)** | Report **corruption**, not version-mismatch (CRC-before-version) | `corruptHeaderReportsCrcNotVersion` (envelope), `FrameCodecFuzzTest` (kept) |
| **MBZ reserved != 0** | Throw (fail closed) | `reservedNonZeroThrows` (envelope), `nonZeroReservedEpochRejected` (frame) |
| **Stale topology epoch** | `STALE_TOPOLOGY(12)` → client full re-hydrate (etcd `ErrCompacted` model) | `staleEpochCursorRejectedWithReHydrate`, `reshardEpochChangeForcesReHydrate`, `subscribeCarriesEpoch` |

**Golden-fixture files the build arc MUST regenerate** (A4 cursor/subscribe wire change only):
`EdgeFrameGoldenBytes.java`, `EdgeFrameGoldenBytesGenerator.java`, `EdgeFrameCodecGoldenFixtureTest
.java`, `EdgeFrameCodecV2GoldenFixtureTest.java`, `EdgeFrameCodecV3GoldenFixtureTest.java`. The
raft-wire goldens (`GoldenFixtures.java`, `WireCompatGoldenBytesTest.java`) are **unchanged** (frame
epoch stays `0`; the new rule only *adds* a reject test).

---

## §5. Open coordination items (must resolve before the freeze is ratified)

1. **⟦SEC-1⟧ snapshot-meta ownership** — anchor subsumes it (preferred) vs standalone `SNAPMETA_MAGIC`
   envelope. Decide with the security lane; either way it is no longer bare 16 B.
2. **⟦SEC-2⟧ topology descriptor ownership** — one authenticated `{formatVersion, N, topologyEpoch}`
   file serving both the deploy-guard and A4's epoch. Confirm `ShardMap.epoch()` reads it and
   `StaticShardMap` returns it (not hardcoded `0`).
3. **⟦SEC-3/4⟧ keyring + anchor markers** — the security lane's new files adopt the §0 convention
   (magic+version before crypto, unknown ⇒ fail closed, AAD binds purpose/scope). Contract in
   §2.13/§2.15.
4. **`STALE_TOPOLOGY(12)` enum extension** — one-value extension of the closed/golden-pinned
   `ErrorCode`; confirm with whoever owns the edge-protocol RFC freeze.
5. **Uniform-epoch-on-SUBSCRIBE** — recommended (one invariant); confirm the build arc accepts the
   0x01 golden regeneration cost.

---

# §6 Reliability record — persist-before-ack ordering (placement, crash matrix, cost, no-regression)

# PERSIST-BEFORE-ACK Ordering for the Durability Anchor — reliability-engineer lane

Repo `main @ 012e213`. Design only, no production code. I own the exact fsync placement and its
crash-interleaving proof; the security lane owns the anchor format (`anchor-rotation-design.md`),
the protocol lane owns magics/versions. Every as-built claim is cited `file:line`, verified against
source this session. Where I disagree with the security lane's ordering assumptions, see **§7
FINDINGS** — the largest is that the `max(anchor.currentTerm, lastWALTerm)` *repair* rule is
unnecessary and should be a strict assert-or-REFUSE.

The anchor is the merged per-shard `raft-anchor` carrying
`{anchorSeq, currentTerm, votedFor, lastDurableIndex, lastDurableTerm, snapshotIndex, snapshotTerm}`,
subsuming `raft.persistent_state.dat` and the bare `raft-log.snapshot-meta.dat`.

**File shape (per sec-lead update, §A1.3):** an 8-byte **unauthenticated container header**
`[magic][fileVersion=1][flags][reserved]` at offset 0, written **once at file creation** (fsync'd
then), followed by two fixed-offset 512-B slots. The header is a version marker readable before any
crypto (Kafka magic-before-CRC); it is write-once and thus **outside every hot path** — it changes
none of the fsync ordering below. It only adds a boot-time read+validate (§5) and folds into the
one-time creation preallocation (§4.2).

---

## 0. As-built ground truth (verified line numbers)

| Path | As-built today | fsyncs today |
|---|---|---|
| Leader flush cycle | `RaftNode.flushDurable:2223` → `log.syncWal():2236` → `durableIndex=target:2237` → `maybeAdvanceCommitIndex:2238`; self-vote gated `durableIndex>=n` at `:2175` | **1** WAL `force(true)` (`FileStorage.syncLog:179`; +dir `:183` only on first-ever syncLog of a new WAL) |
| Follower append | `RaftLog.appendEntries:414`; conflict `truncateFrom:442`; single trailing `syncWal():449` **before** the method returns → before the ACK | **1** WAL `force(true)` per RPC batch |
| Term/vote | `DurableRaftState.setTerm:99`/`vote:122`/`setTermAndVote:143` → `persistValues:157` = `storage.put` (`FileStorage` file `force(true):88` + dir `sync():96`) **+ extra** `storage.sync():164`; **persist strictly BEFORE in-memory update** (`:106-108,130-131,145-147`) | **3** (1 file + 2 dir) + 1 rename |
| Conflict truncation | `truncateFrom:461` → `rewriteWal:472` → `storage.sync():477` (dir) | `rewriteWal` = **N** per-entry `force(true)` (durable `appendToLog:136`) + rename + 1 dir |
| Compaction | `compact:551`: `rewriteWal:583` → `put(SNAPSHOT_META):587` (bare 16 B) → `storage.sync():588`; `persistSnapshot:520` MUST precede (`:502` durable-prefix invariant) | N (rewrite) + 2 (meta put) + 1 dir |
| Recovery | `RaftLog` ctor `:151-154` bare `entries.add(deserializeEntry)` — **NO contiguity/position check** (guard lives only in `appendNoSync:361-364`, bypassed on replay); snapshot boundary from bare meta `:161-165`, "trust WAL over stale meta" `:179-182` | — |
| Client write-ack | `RaftNode.applyCommitted:2246` fires `whenCommitOutcome:1030` → `ConfigdServer:2150-2152` completes `Committed`. Ack is **downstream of `setCommitIndex`+`applyCommitted`** which needs the `durableIndex` gate `:2175` | — |

Two brief corrections: the brief's ack site `ConfigdServer:833/861` is the **read-index** path
(150 ms linearizable read), not the write ack (`:2150-2152`); and the vote path is **3** fsyncs,
not 4 (the "4th" is the rename syscall). Neither changes the design.

---

## 1. PLACEMENT TABLE — every write path × ordered writes/fsyncs

Notation: `W-append` = `appendToLogNoSync`; `W-fsync` = `syncLog` `force(true)` (WAL grows →
metadata → needs `force(true)`); `A-write` = dual-slot in-place `pwrite` of the stale slot;
`A-fsync` = `force(false)`/fdatasync of the preallocated anchor (in-place, no metadata change → data
sync suffices — a real, cheaper-than-`force(true)` choice, §3). Anchor is written LAST in every
cycle; `A-write` snapshots the CURRENT in-memory `{currentTerm,votedFor,...}`.

| Path | Ordered sequence | fsyncs (today→designed) |
|---|---|---|
| **Leader flush** | `W-append`(each propose, no fsync) … then flush cycle: **1** `W-fsync` → **2** `A-write(lastDurableIndex=target, currentTerm=cur)` → **3** `A-fsync` → **4** `durableIndex=target` → `maybeAdvanceCommitIndex` (self-vote now counts) → commit → ack | 1 → **2** |
| **Follower append** | `W-append`(each entry) → **1** `W-fsync` (once/RPC) → **2** `A-write(lastDurableIndex=newHead)` → **3** `A-fsync` → **4** return → send ACK | 1 → **2** |
| **Term/vote** | **1** `A-write(currentTerm/votedFor=new, lastDurableIndex=cur)` → **2** `A-fsync` → **3** in-memory update. Persist-BEFORE-memory PRESERVED (the anchor write replaces `persistValues`) | 3 → **1** |
| **Conflict truncation** (INV-ANCHOR-LOWER) | **1** `A-write(lastDurableIndex=conflictPoint-1)` → **2** `A-fsync` → **3** `rewriteWal` (tmp+rename) → **4** dir `sync()` (rename durable) → **5** `W-append`+`W-fsync` (leader's entries) → **6** `A-write(lastDurableIndex=newHead)` → **7** `A-fsync` → **8** ACK | +2 anchor (rare path) |
| **Compaction** | **1** `persistSnapshot` (blob durable, `put`) → **2** `rewriteWal` → **3** dir `sync()` → **4** `A-write(snapshotIndex/Term=B, lastDurableIndex=head)` → **5** `A-fsync`. Anchor LAST; replaces `put(META)+sync` | −2 net (rare path) |
| **Node-anchor: topology** | boot / shard-count-change only: `A-write({topologyEpoch, shardCount})`+`A-fsync`, cross-checked at boot vs the TopologyDescriptor file. ~0 steady-state | negligible |
| **Node-anchor: audit head** | **PERIODIC, not per-record** (§ below) | +1 per K records |

**Can the two per-RPC forces be batched into one?** No — `W-fsync` and `A-fsync` target *different
files* (`raft-log.wal`, `raft-anchor`); one fsync syscall cannot cover two descriptors, and the WAL
must be durable *before* the anchor references it, so they are strictly ordered. What IS batched:
**both are per-RPC/per-group-commit-batch, not per-entry** — one `W-fsync` + one `A-fsync` per
AppendEntries RPC regardless of entry count, and the anchor advances once per batch to the batch
head. etcd pays only one fsync because `consistent_index` rides the same bolt txn as data; our WAL
is an append-only file, not a transactional B-tree, so we cannot co-commit — we pay two.

**Audit head cadence (honest, documented residual).** Per-record anchoring doubles audit fsyncs
(`AuditLog` uses durable `appendToLog` = 1 `force(true)`/record; audit writes only on auth events).
I choose **periodic**: advance `nodeAnchor.{auditRecordCount,auditHeadHash}` every **K=64 records or
T=1 s, whichever first**. **Documented detection window:** trailing audit records within the last
un-anchored batch (≤ 64 records or ≤ 1 s of audit tail) can be truncated without tripping the head
check. This is an **accepted-and-documented residual**, not a silent hole — stated in the runbook
and the threat model, tunable to K=1 for audit-critical deployments at the cost of a doubled audit
fsync rate.

---

## 2. CRASH-INTERLEAVING MATRIX (the INV-ANCHOR-ACK + recovery-asymmetry proof)

`W` = WAL last index at recovery; `A` = `anchor.lastDurableIndex`. Recovery gates (from
`anchor-rotation-design.md §A1.4` Step-3, which I place here): `W==A` ACCEPT; `W>A` ACCEPT-FORWARD
(adopt WAL head, rewrite anchor); `W<A` **REFUSE**. FileStorage drops any torn WAL tail first
(`:271`), so every frame reaching recovery is CRC-complete.

### 2a. Leader flush cycle — crash between each adjacent pair

Steps: (0 pre) → **1** `W-fsync` → **2** `A-write` → **3** `A-fsync` → **4** `durableIndex=target` →
(commit/ack).

| Crash after step | Recovery sees | Gate row | Safe because |
|---|---|---|---|
| before 1 | WAL append not fsync'd → torn tail dropped `:271` | `W==A` | entry never durable, never counted |
| **1, before 2** (the key case) | WAL has `[A+1..W]` durable; anchor still `A` | **`W>A` accept-forward** | `[A+1..W]` were **UNACKED** — the self-vote needs anchor coverage (`durableIndex` advances only at step 4, after `A-fsync`), so `maybeAdvanceCommitIndex:2175` never counted them → never committed → never client-acked. Re-cover: set `A=W`, rewrite anchor. See 2c. |
| 2, before 3 | torn anchor slot (unforced) fails CRC/MAC → prior slot (seq−1, `A` old) wins | `W>A` accept-forward | identical to the row above; the half-written slot is ignored by dual-slot pick-highest-valid |
| 3, before 4 | anchor durable at `A=W`; `durableIndex` not yet advanced in-memory (irrelevant — rebuilt from anchor) | `W==A` | entries now anchored; recovery adopts `A=W` — still UNACKED (commit hadn't advanced), re-offered to quorum normally |
| after 4, before commit | `W==A`, entries durable+anchored, uncommitted | `W==A` | normal uncommitted tail; leader re-replicates |

### 2b. Follower append cycle

Steps: **1** `W-fsync` → **2** `A-write` → **3** `A-fsync` → **4** return → send ACK.

| Crash after step | Recovery | Gate | Safe because |
|---|---|---|---|
| before 1 | torn tail dropped | `W==A` | not durable, ACK never sent |
| 1, before 3 | WAL `[A+1..W]` durable, anchor `A` (or torn newer slot ignored) | `W>A` accept-forward | ACK is sent only after step 4 → the leader never saw `matchIndex=W` → those entries never counted toward quorum on the leader → never committed. Re-cover safe. |
| 3, before 4 | anchor `A=W` durable, ACK not yet sent | `W==A` | leader will re-send; idempotent |
| after 4 | ACK in flight | `W==A` | matchIndex reported == durable+anchored (persist-before-ACK holds) |

### 2c. (a) crash between W-fsync and A-fsync — what distinguishes re-cover from adversarial truncation

**Nothing distinguishes them for UNACKED entries, and that is exactly correct.** The anchor's
`lastDurableIndex A` is the precise fault line:

- **At or below `A`** = the committed/client-acked prefix. Losing any of it ⇒ `W<A` ⇒ **REFUSE**.
  This is the whole point of the anchor.
- **Above `A`** = unacked. An adversary truncating `[A+1..W]` to any `W'≥A` is **byte-for-byte
  indistinguishable from the leader having simply crashed one flush-cycle earlier** — and it must
  be, because *no client was ever promised those entries*. Presenting fewer unacked entries is a
  strict subset of what an honest earlier crash produces; the adversary gains nothing. Accept-forward
  is safe not because we *detect* honesty but because there is *nothing to protect* above `A`.

So the security boundary is the index `A` itself: protected at/below, free above. This asymmetry is
sound **only because anchor-before-ack makes `A` an upper bound on everything ever committed-and-acked**
(§4 rejected alternative shows why a lagging anchor breaks it).

### 2d. Compaction crash-at-any-point (folding the bare meta into the anchor)

Order: **1** `persistSnapshot(blob@B)` → **2** `rewriteWal` (WAL now starts `B+1`) → **3** dir sync →
**4** `A-write(snapshotIndex=B)` → **5** `A-fsync`.

| Crash after | Recovery sees | Decision | Why non-refusing |
|---|---|---|---|
| 1, before 2 | blob@B durable, WAL still full from old boundary, anchor.snapshotIndex=old | ignore blob (ahead of anchor), replay full WAL | full prefix intact (today's `:511` invariant) |
| 2, before 4 | blob@B durable, WAL starts `B+1`, anchor.snapshotIndex=old<B | **adopt boundary = WAL.firstIndex−1 = B, REQUIRE blob@B present**, rewrite anchor to B | today's "trust WAL over stale meta" `:179-182`; blob@B was persisted at step 1. If WAL.firstIndex−1 has **no** matching authenticated blob ⇒ REFUSE (that is the adversarial prefix-truncation) |
| after 4 | anchor.snapshotIndex=B == WAL.firstIndex−1 == blob.lastIncludedIndex | clean | all three agree |

Adversarial protection: the blob is `SNAP_MAGIC`+`scopeId`-authenticated; once the anchor commits
`snapshotIndex=B`, rolling the blob back to `B'<B` mismatches the anchor ⇒ REFUSE.

---

## 3. COST MODEL

fsyncs per operation (force calls), today → designed:

| Op | Today | Designed | Δ |
|---|---|---|---|
| Leader flush cycle | 1 | 2 | **+1** |
| Follower append (per RPC) | 1 | 2 | **+1** |
| Term/vote | 3 (1 file+2 dir) | 1 (in-place `force(false)`) | **−2** |
| Conflict truncation | N+1 | N+3 | +2 (rare) |
| Compaction | N+3 | N+1 | −2 (rare) |
| Audit record | 1 | 1 + (1 per K=64) | +~1.5% |

**Amortization:** both hot-path fsyncs are **per-group-commit-batch**, and the anchor advances once
to the batch head. At the knee the batch is largest, so per-op overhead is *smallest*; the relative
cost is highest only at batch-size-1 (low load), where absolute throughput is a non-issue.

**Knee-impact bound (engineering estimate, NOT measured — measurement obligation for the build
arc).** The measured single-group knee is **~800 w/s, fsync-bound** on the m6i-class EC2 box
(memory RR-113). A second sequential barrier per flush cycle:
- Pessimistic: if barriers fully serialize and don't coalesce, ~2× barrier time/cycle → knee toward
  ~450–600/s.
- Realistic: the anchor flush is a **512-B in-place `force(false)`** of a preallocated, cache-warm
  page — no allocation, no inode writeback, back-to-back with the WAL barrier on the same device
  (often coalesced by the device cache), and cheaper than the WAL flush (which writes a variable,
  frequently multi-page append). A 4 KiB in-place fdatasync costs roughly one device barrier (~same
  latency as the WAL barrier on a pure-flush-bound device) but skips the page-cache writeback the
  WAL pays.
- **Honest bound: 10–40% knee regression (likely ~10–20%), heaviest at low batch sizes.**
  **Obligation:** re-run the EC2 N×knee under this design before ship; if measured > ~25%, the lever
  is to raise `groupCommitLingerMicros`/`groupCommitMaxBatch` so the fixed 2-fsync cost amortizes
  over a larger batch (`scheduleFlush:2199-2213`).

**Alternative REJECTED — anchor-lag with reconcile window (Postgres `pg_control` style).** Let the
anchor lag the ack and reconcile forward on recovery (1 fsync/cycle, no regression). **Forbidden for
acked data:** a lag window `(anchor, acked]` lets an adversary trim the WAL to just below an acked
index; recovery's `W≥A ⇒ accept-forward` then **silently accepts the truncated log** and the acked
entry vanishes with no REFUSE. Postgres tolerates a lagging control file because it is a *crash*
anchor, not an *anti-rollback* anchor; our threat model (adversary with disk write, no key) forbids
the lag. The extra fsync is the price of anti-rollback.

---

## 4. FAIL-CLOSED POLICY at the anchor seam (closes assessment 2.1-6 for both fsync sites)

Frozen policy, applied identically to a WAL-fsync throw (`syncLog`→`force`) and an anchor-fsync
throw:

1. **fsync THROWS (`IOException`) or is detected to have lied:** the flush cycle **must not** advance
   `durableIndex`, **must not** `A-write`/advance commit, **must not** ack. Then: **PANIC — process
   exit** after a SEVERE log + a fatal counter (`durability.fsync.failed`). **Recommendation:
   process exit, not step-down-and-serve.** Rationale (fsyncgate; Postgres `data_sync_retry=off`
   PANIC): on Linux a failed fsync can mark dirty pages clean, so a *later* fsync can falsely
   "succeed" while the bytes are lost; a step-down-but-alive node would keep unsynced in-memory
   state and could re-ack. Exit + rebuild from the durable WAL/anchor on restart is the only sound
   response. Consistent for both seams. Build-arc test: wire `FaultInjectingStorage.failNextSyncs`
   (`:56`) into a **live-RaftNode** flush cycle, assert no-durable-advance + no-ack + exit signal —
   the exact close for 2.1-6.
2. **ENOSPC on an anchor write:** **impossible after boot.** The one-time file-creation write lays
   down the 8-B container header **and** both 512-B slots (≈1032 B total) and fsyncs once, so all
   blocks are allocated at creation; steady-state anchor writes are in-place overwrites of the stale
   slot only and never allocate. Verified this eliminates anchor-ENOSPC on the
   ack path: the flush order is `W-append` (grows, CAN ENOSPC — already handled, 2.1-5) → `W-fsync`
   → `A-write` (in-place, CANNOT ENOSPC). The failure-prone step is first; the anchor never
   half-commits on ENOSPC. **Caveat (documented):** on COW filesystems (Btrfs/ZFS) an in-place
   overwrite may still allocate — so the guarantee is stated for **ext4/xfs with real preallocated
   blocks** (the measured/supported stack); COW filesystems weaken it to the WAL's ENOSPC handling.
3. **Partial / torn anchor write:** dual-slot. Only the *stale* slot is mutated per update; a torn
   slot fails CRC32C/MAC and the untouched slot (seq−1) wins (`anchor-rotation-design.md §A1.3`
   pick-highest-valid). A torn write never damages the authoritative slot.
4. **Anchor on a DIFFERENT device than the WAL:** **REQUIRE same device (same shard directory).**
   The anchor lives at `dataDir/shard-<gid>/raft-anchor`, next to `raft-log.wal` (`§A1.3`) — same
   directory, hence same filesystem/device/failure-domain. A split device breaks the ordering
   guarantee: if the WAL device is lost but the anchor device survives, `anchor.lastDurableIndex`
   references WAL entries that no longer exist → spurious `W<A` REFUSE (or, reversed, a lost anchor
   over a live WAL). Enforce same-directory at construction; reject a configured cross-device anchor.

---

## 5. BOOT / RECOVERY SEQUENCE (exact order)

Keyring load precedes anchor read because the anchor's MAC/DEK key is `K_integrity[keyTerm]` /
`DEK[keyTerm]` derived from the **keyring root**, not the raw signing key (`§A2.3`).

1. **Load signing key** — `SigningKeyStore`, off-datadir (D-1, `ConfigdServer:1366`). Yields
   `K_keyringMac` + `KEK_wrap`.
2. **Load keyring** (`raft-keyring`, dual-slot, highest valid `keyringSeq` whose outer MAC verifies
   under the *current* signing key) → `term→root` map + `activeTerm`. Absent-but-encrypted-data ⇒
   REFUSE; outer-MAC-fail ⇒ REFUSE with the "keyring under a prior KEK" diagnostic (`§A2.4`).
3. **Per shard, read `raft-anchor`** — first read the 8-B container header at offset 0 and validate
   `magic` + `fileVersion==1` (unknown `fileVersion` ⇒ REFUSE, fail loud; the header is
   unauthenticated, so a garbled/forged header simply fails this check — the authenticated slots are
   never reached). Then parse both slots at their fixed post-header offsets; for each verify
   IntegrityEnvelope (CRC32C → MAC/GCM tag under `K_integrity[slot.keyTerm]`, `scopeId==gid`,
   `formatVersion==3`); take highest-valid `anchorSeq`. Apply the presence table:

   | Anchor slots | Shard dir | Decision |
   |---|---|---|
   | file absent | **empty** (no `raft-log.wal` with size>0 AND no `raft-log.snapshot.dat`) | **FRESH** — bootstrap `anchorSeq=1,lastDurableIndex=0,currentTerm=0,votedFor=-1` |
   | file absent | **non-empty** (WAL size>0 OR snapshot blob present) | **REFUSE** (anchor deleted) |
   | ≥1 slot valid | — | proceed to step 5 |
   | file present, both slots invalid | — | **REFUSE** (tamper — distinct from FRESH which has *no file*) |

   "Non-empty" defined precisely: `raft-log.wal` exists with size > 0, **or** `raft-log.snapshot.dat`
   exists. A zero-byte WAL is FRESH-equivalent (`FileStorage.readLog` returns empty).
4. **Read WAL** — FileStorage drops torn tail; `deserializeEntry` verifies envelope + `scopeId==gid`.
5. **NEW recovery checks (the `RaftLog` ctor lacks these today, `:151-154`):** contiguity
   (`e[k].index == firstIndex+k`), term-monotonicity (`e[k].term` non-decreasing), snapshot-join
   (`firstIndex == anchor.snapshotIndex+1`; blob boundary matches). Any violation ⇒ REFUSE.
6. **Head reconciliation** — `W` vs `A`: `W==A` accept; `W>A` accept-forward (**assert
   `anchor.currentTerm ≥ lastWALTerm` else REFUSE — see §7**; set `lastDurableIndex=W`, rewrite
   anchor at `anchorSeq+1`); `W<A` REFUSE.
7. **Restore** `currentTerm`/`votedFor` from the anchor (replaces `DurableRaftState.load`);
   rebuild state machine from snapshot blob + WAL suffix.

---

## 6. NO-REGRESSION ARGUMENT (point-by-point vs assessment §2.1)

| Row | Property | Preserved? | Test impact |
|---|---|---|---|
| 2.1-1 | fsync-before-ack (leader self-vote gated on `durableIndex`; follower fsync-before-ACK) | **Preserved + strengthened** — anchor fsync joins the barrier; `durableIndex` advance now additionally gated on anchor-durable | `GroupCommitDurabilityTest` (`gateBlocksCommitUntilLeaderEntryIsDurable:94`, `queuedFlushAfterStepDown…:122`): core claim holds; **ADD** an assert that the covering anchor is durable before commit |
| 2.1-2 | kill-9 / crash recovery matrix | Preserved; matrix **grows** (new anchor-vs-WAL crash points, §2) | `SnapshotCrashRecoveryTest` (`:105/:111/:117/:123`): **rewrite** to assert snapshot boundary via the **anchor** (bare `snapshot-meta` removed); `gapDetectionFiresWhenSnapshotFsyncLied:274` kept |
| 2.1-3 | torn / partial write | **Byte-identical** — FileStorage torn-tail `:271` + CRC32 untouched | `WalRecordIntegrityTest:87`, `FileStorageTest.crc32IntegrityVerification:106`: **keep byte-identical**; **ADD** a torn-anchor-slot dual-slot-fallback test |
| 2.1-5 | ENOSPC clean rejection | Preserved for WAL; anchor is ENOSPC-immune post-boot (§4.2) | `StorageEnospcConsensusReactionTest:45/:98`: **keep**; **ADD** a boot-time anchor-preallocation-ENOSPC clean-refuse cell |
| — | vote durability / no double-vote | Preserved — persist-before-memory kept, now via anchor write | `VotePersistenceCrashTest:98`, `DurableRaftStateIntegrityTest`: **rewrite** to assert vote durability + forgery-refusal via the **anchor** |
| 2.1-6 | fsync-failure fail-closed | **New** — was the open GAP; §4.1 closes it | **NEW** live-RaftNode `failNextSyncs` cell (both WAL and anchor seams) |

Kept byte-identical: FileStorage framing/torn-tail/CRC path (2.1-3 core). Rewritten (semantics
changed by the merge): snapshot-boundary and vote-durability tests (bare meta + separate state file
removed). New: the §2 crash-interleaving matrix, torn-anchor-slot, `W<A` REFUSE, fresh-vs-tampered,
anchor-lower-before-truncate, the `currentTerm ≥ lastWALTerm` assertion, and the fsync-throw exit.

---

## 7. FINDINGS (design review — where I disagree with the security lane)

**F-1 (load-bearing): the `max(anchor.currentTerm, lastWALTerm)` REPAIR rule in §A1.4 Step-3 is
unnecessary and should be a strict INVARIANT + assert-or-REFUSE.** Claim: under the merge,
`anchor.currentTerm ≥ lastWALTerm` **always** holds at recovery, so no `max()` repair is ever
needed.

Proof. A WAL entry at term `T` is written only after the node has adopted `currentTerm = T`. Every
term adoption goes through `DurableRaftState.setTerm/setTermAndVote`, which — under the merge — is an
**anchor write that persists `currentTerm=T` BEFORE the in-memory update** (persist-before-memory,
`:106-108,130-131,145-147`), and that anchor write happens strictly before any term-`T` WAL append:
- Leader: wins election in `T` via `setTermAndVote(T,self)` (anchor write, `currentTerm=T`) → *then*
  appends its noop/client entries at `T`.
- Follower: on AppendEntries with `leaderTerm=T>currentTerm`, `setTerm(T)` (anchor write) fires in
  the term-update step *before* `log.appendEntries` writes the term-`T` entries.

So at the instant any term-`T` entry reaches the WAL, the anchor already carries `currentTerm ≥ T`;
every later `A-write` re-snapshots the current (still `≥ T`) term. This holds for the UNANCHORED
accept-forward tail too (the `setTerm` anchor write precedes the WAL append that the crash left
unanchored). Therefore `anchor.currentTerm ≥ lastWALTerm` is an invariant maintained by the ordering
— it is Raft's own "`currentTerm ≥ every log term`" property made durable, which is precisely why
`DurableRaftState` persists-before-memory today.

Consequence — and why `max()` is not just unnecessary but **harmful**: the security lane motivated
`max()` as a defense against anchor-state rollback across a vote boundary. But if an adversary rolls
the anchor to an older slot with a *lower* `currentTerm` while leaving higher-term WAL entries, the
result is exactly `anchor.currentTerm < lastWALTerm`. The `max()` rule would **silently repair
forward and boot the rolled-back node**, masking the tamper. The assertion **REFUSES** it — the
correct fail-closed response to a detected anchor/WAL contradiction. `max()` converts a detectable
partial-rollback into a silent one; it should be deleted. (If both anchor and WAL are rolled back
*consistently* to the same old term, that is the R-a residual — undetectable without an external
witness — and `max()` never helped there either.)

Corollary: in accept-forward (`W>A`), `currentTerm` is **never raised** (it already dominates), so
`votedFor` is **never cleared** — the §A1.4 "clear votedFor if term raised" branch is dead code.
Recovery simply keeps `currentTerm`/`votedFor` from the anchor. Simpler and strictly safe.

**F-2 (refinement, not a disagreement): "A ≥ everything ever acked" should read "≥ everything ever
COMMITTED-and-client-acked."** A follower's `matchIndex` ACK is a replication-progress report to the
leader, not a client durability promise; the client is acked only when the *leader* commits
(quorum, `applyCommitted`→`whenCommitOutcome`). This matters for **INV-ANCHOR-LOWER**: conflict
truncation lowers a follower's `lastDurableIndex` below a previously-ACKed `matchIndex`, and that is
**safe** because Raft guarantees the conflict point is *above* `commitIndex` (Leader Completeness
never truncates committed entries), so no *committed/client-acked* index is ever lowered. The `W<A`
REFUSE gate is about crash-consistency of anchor-vs-WAL (maintained by lower-first ordering); the
"never lose acked data" property is about the *committed* floor. Keeping these two arguments distinct
removes the apparent contradiction between "anchor is an upper bound on acked" and "the anchor may
legitimately go down during truncation."

**F-3 (concur, placement note): INV-ANCHOR-LOWER must lower the anchor BEFORE `rewriteWal`.** Verified
against `truncateFrom:461-479` — today it `rewriteWal`s then dir-syncs with no anchor. If the anchor
were lowered *after* the truncate, a crash in between leaves `anchor.lastDurableIndex > W` = spurious
`W<A` REFUSE on a **legal** Raft truncation (false positive, row 19 of the security matrix). Lower
first ⇒ a crash between lower and rewrite leaves `W≥A` (accept-forward re-adopts, leader re-truncates)
⇒ non-refusing. The security lane's step order is correct; I confirm it is load-bearing and place it.

**F-4 (concur): anchor writes should use `force(false)`/fdatasync, not `force(true)`.** The
preallocated fixed-size slots never change file metadata after creation, so a data-only sync is
sufficient and skips the inode writeback — a real (if modest) cost saving the security lane didn't
specify. The WAL keeps `force(true)` (append grows the file → metadata).

---

# Part III — §7 Compile-checked format sketch (design artifact — NOT production code)

Standalone `io.configd.frozen` sketch rendering §2 into compilable codecs; built and run this
session on JDK 25. The self-test exercises golden round-trips plus every fail-closed path
(bad CRC, rolled/zero version, non-zero reserved, unknown algId, downgrade-under-key,
per-field-boundary truncation of the anchor record, torn slot A with valid slot B, unknown
keyring term, cross-scope replay, keyring rewrap/rotation round-trips incl. crash-either-side
of the signing-key swap, cursor epoch mismatch, pragma cells). The production build arc
re-implements these at the real seams (§9.1); this sketch is the proof the byte layouts
compose and fail closed as specified.

## 7.1 Compile + run evidence

```
== FINAL RUN (post-integration: KEYRING_SLOT_STRIDE frozen at 65536) ==
$ javac --release 25 -Xlint:all -d out $(find io -name '*.java')
(compiled: 0 warnings, 0 errors)

$ java -cp out io.configd.frozen.SelfTest
=== Configd frozen-format sketch self-test (JDK 25) ===

-- EnvelopeV3 (v3: +scopeId, +keyTerm in HMAC, CRC-before-version, reserved==0) --
[PASS] envelope NONE round-trip
[PASS] envelope HMAC round-trip
[PASS] envelope GCM round-trip
[PASS] envelope GCM hides plaintext
[PASS] envelope bad CRC -> CRC
[PASS] envelope corrupt header reports CRC not VERSION -> CRC
[PASS] envelope rolled formatVersion -> VERSION
[PASS] envelope version 0 illegal -> VERSION
[PASS] envelope reserved != 0 -> RESERVED
[PASS] envelope unknown algId -> ALGID
[PASS] envelope downgrade NONE under key -> DOWNGRADE
[PASS] envelope wrong magic under key -> MAGIC
[PASS] envelope truncated sub-floor -> TRUNCATED
[PASS] envelope GCM unknown keyTerm -> UNKNOWN_TERM
[PASS] HMAC cross-shard splice (scope assert) -> SCOPE
[PASS] GCM cross-shard splice (scope assert) -> SCOPE
[PASS] HMAC scope-forged splice (MAC catches) -> MAC
[PASS] GCM scope-forged splice (tag catches) -> TAG

-- AnchorRecord + AnchorCodec (dual-slot, merged raft.persistent_state) --
[PASS] anchor record round-trip
[PASS] anchor payload wrong length -> MALFORMED
[PASS] anchor bootstrap opens PROCEED seq=1 slot=0
[PASS] anchor update -> PROCEED seq=2 slot=1
[PASS] anchor torn newer slot -> falls back to older valid slot 0
[PASS] anchor both slots invalid -> REFUSE
[PASS] anchor bad container header -> REFUSE
[PASS] anchor no file + empty dir -> FRESH
[PASS] anchor no file + non-empty dir -> REFUSE
[PASS] anchor cross-shard file -> REFUSE (scope)
[PASS] anchor record truncated at every field boundary -> all fail closed

-- NodeAnchorRecord (bound {topologyEpoch, shardCount} + audit-chain head) --
[PASS] node-anchor record round-trip
[PASS] node-anchor wrong length -> MALFORMED
[PASS] node-anchor bad hash length -> MALFORMED
[PASS] node-anchor via dual-slot codec -> PROCEED
[PASS] node-anchor consistent with matching TopologyDescriptor
[PASS] node-anchor detects topology-descriptor rollback (epoch) -> MALFORMED
[PASS] node-anchor detects topology-descriptor mismatch (N) -> MALFORMED

-- KeyringCodec (term-versioned wrapped roots, non-destructive rotation) --
[PASS] keyring seal/open body round-trip
[PASS] keyring unseal recovers root[1]
[PASS] keyring append-term -> activeTerm 2, seq 2, 2 entries
[PASS] keyring retains root[1] after append
[PASS] keyring term-1 record still decrypts after term rotation
[PASS] keyring rewrap preserves roots (non-destructive signing-key rotation)
[PASS] keyring term-1 record still decrypts after signing-key rotation
[PASS] keyring rewrapped body verifies under new K_keyringMac
[PASS] keyring rewrapped body rejected under OLD K_keyringMac -> MAC
[PASS] keyring unknown term at read -> fail closed -> UNKNOWN_TERM
[PASS] keyring wrapped-root AAD replay into another term -> TAG -> TAG
[PASS] keyring body tamper -> outer MAC fails -> MAC
[PASS] keyring unknown formatVersion -> VERSION
[PASS] keyring unknown wrapAlgId -> ALGID
[PASS] keyring envelope carries keyTerm=0 (signing-key domain)
[PASS] keyTerm=0 illegal under a non-keyring magic (WALE) -> MALFORMED
[PASS] keyring entry term 0 illegal (distinct field from keyTerm=0) -> MALFORMED
[PASS] keyring file bootstrap -> PROCEED seq=1
[PASS] keyring file: new signing key opens the rewrapped slot (seq 3)
[PASS] keyring file: OLD signing key still opens the pre-rotation slot (seq 1) -- crash-safe both ways

-- TopologyDescriptor (authenticated shard-count + epoch) --
[PASS] topology payload round-trip
[PASS] topology seal/open round-trip
[PASS] topology epoch 0 rejected on encode -> MALFORMED
[PASS] topology epoch 0 rejected on decode -> MALFORMED
[PASS] topology formatVersion 0 rejected -> VERSION
[PASS] topology reserved != 0 rejected -> RESERVED
[PASS] topology sealed tamper -> MAC -> MAC

-- WatchCursorV2 (epoch-bound, strictly-ascending unsigned gid) --
[PASS] cursor round-trip (unsigned gid order)
[PASS] cursor epoch mismatch -> STALE_TOPOLOGY -> STALE_TOPOLOGY
[PASS] cursor epoch 0 -> frame-corrupt -> MALFORMED
[PASS] cursor non-ascending gid -> reject -> MALFORMED
[PASS] cursor negative S -> reject -> MALFORMED

-- PolicyPragma (#!configd-acl v1 shebang) --
[PASS] pragma valid v1
[PASS] pragma valid v1 with CRLF
[PASS] pragma absent (plain # comment on line 1) -> MALFORMED
[PASS] pragma absent (policy line on line 1) -> MALFORMED
[PASS] pragma unknown version v2 -> VERSION
[PASS] pragma version 0 illegal -> VERSION
[PASS] pragma shebang on line 2 is not the pragma (absence) -> MALFORMED
=========================================================
TOTAL: 75 passed, 0 failed, 75 cases
exit=0
```

## 7.2 Sources

### FrozenFormats.java

```java
package io.configd.frozen;

/**
 * The single authoritative constants table for the Configd frozen on-disk / on-wire formats.
 * Mirrors the design docs' magic / version / algId / size tables. Every value here is FROZEN:
 * a change is a format break gated by the reserved "extended version" escape (§0.2 of version-markers).
 *
 * <p>Magic sigils are 4 ASCII bytes, big-endian, and are folded into the MAC/AAD input of every
 * authenticated artifact (cross-artifact-confusion defence). {@code magic == 0} is illegal.
 */
public final class FrozenFormats {

    private FrozenFormats() {}

    // ---- IntegrityEnvelope v3 --------------------------------------------------------------
    /** Envelope format version. As-built = 2; v3 adds scopeId (all postures) + keyTerm (HMAC body). */
    public static final short ENVELOPE_FORMAT_VERSION = 3;
    /** magic(4) + formatVersion(2) + algId(1) + reserved(1). */
    public static final int ENVELOPE_HEADER_SIZE = 8;
    /** scopeId(4), present in EVERY posture, immediately after the header, authenticated. */
    public static final int SCOPE_ID_SIZE = 4;
    /** keyTerm(4), present in the HMAC and GCM bodies (not in NONE). */
    public static final int KEY_TERM_SIZE = 4;
    public static final int CRC_SIZE = 4;
    public static final int MAC_SIZE = 32;               // HMAC-SHA-256
    public static final int GCM_TAG_SIZE = 16;           // 128-bit
    public static final int GCM_TAG_BITS = 128;
    public static final int SEGMENT_ID_LEN = 16;
    public static final int NONCE_LEN = 12;              // 4 zero bytes || 8-byte BE counter
    /** header(8) + scopeId(4) + keyTerm(4) + segmentId(16) + nonce(12) = the GCM AAD-covered prefix. */
    public static final int ENC_PREFIX_SIZE =
            ENVELOPE_HEADER_SIZE + SCOPE_ID_SIZE + KEY_TERM_SIZE + SEGMENT_ID_LEN + NONCE_LEN; // 44
    public static final int ENC_MIN_SIZE = ENC_PREFIX_SIZE + GCM_TAG_SIZE + CRC_SIZE;          // 64

    public static final byte ALG_NONE = 0;
    public static final byte ALG_HMAC_SHA256 = 1;
    public static final byte ALG_AES256_GCM = 2;

    /** Node-level artifacts (keyring, node-anchor, topology, audit) carry this scopeId. */
    public static final int NODE_SCOPE = 0xFFFFFFFF;

    /**
     * keyTerm 0 = the signing-key-derived key domain (K_keyringMac). Legal ONLY for
     * {@link #KEYRING_MAGIC} (whose outer envelope predates any keyring root); illegal for every
     * other magic (§6 integrator resolution). Distinct from the keyring ENTRY {@code term} field,
     * which is >= 1.
     */
    public static final int KEY_TERM_SIGNING_DOMAIN = 0;

    // ---- Artifact magics -------------------------------------------------------------------
    // As-built (RaftArtifactMagic.java) -- kept verbatim.
    public static final int STATE_MAGIC = 0x52465354;    // "RFST" -- RETIRED (§6/§A1.1): raft.persistent_state MERGED into the anchor; not allocated in the freeze
    public static final int SNAP_MAGIC  = 0x5253_4E50;   // "RSNP" snapshot blob
    public static final int WALE_MAGIC  = 0x5257_414C;   // "RWAL" per WAL entry
    // Security-lane new magics (anchor-rotation-design §5 [PROTOCOL]).
    public static final int ANCHOR_MAGIC      = 0x52414E43; // "RANC" per-shard anchor
    public static final int NODE_ANCHOR_MAGIC = 0x524E414E; // "RNAN" node anchor
    public static final int KEYRING_MAGIC     = 0x524B5952; // "RKYR" wrapped-key keyring
    // Protocol-lane magics the version-marker doc NAMES but never assigns a value -> PROPOSED here,
    // flagged [PROTOCOL-PROPOSAL] in the summary (designers own the registry).
    public static final int TOPO_MAGIC     = 0x52544F50; // "RTOP" topology descriptor (§2.7)
    public static final int AUDIT_MAGIC    = 0x52415544; // "RAUD" security-audit record (§2.8)
    public static final int WAL_FILE_MAGIC = 0x52574C46; // "RWLF" FileStorage WAL container header (§2.2)

    // ---- Slotted files: unauthenticated container header (§6) + dual authenticated slots -----
    /**
     * Every slotted file (anchor, node-anchor, keyring) opens with an 8-byte UNAUTHENTICATED
     * container header at offset 0: {@code [*_MAGIC:4][fileVersion:u8=1][flags:u8=0][reserved:u16=0]}
     * where {@code *_MAGIC} is the file's own artifact magic. Foreign-file/version rejection only --
     * NOT CRC'd, NOT the security control (that is the per-slot envelope). Slots follow the header.
     */
    public static final int CONTAINER_HEADER_SIZE = 8;
    public static final byte CONTAINER_FILE_VERSION = 1;

    public static final int ANCHOR_SLOT_STRIDE = 512;    // slot i @ CONTAINER_HEADER_SIZE + i*stride
    public static final int ANCHOR_SLOT_COUNT  = 2;
    /** header(8) + 2*512 = 1032. */
    public static final int ANCHOR_FILE_SIZE   = CONTAINER_HEADER_SIZE + ANCHOR_SLOT_COUNT * ANCHOR_SLOT_STRIDE;
    public static final int ANCHOR_RECLEN_SIZE = 4;      // [recordLen:4] prefix inside each slot
    /** anchorSeq(8)+currentTerm(8)+votedFor(4)+lastDurableIndex(8)+lastDurableTerm(8)+snapIndex(8)+snapTerm(8). */
    public static final int ANCHOR_PAYLOAD_SIZE = 52;
    /** nodeAnchorSeq(8)+topologyEpoch(8)+shardCount(4)+auditRecordCount(8)+auditHeadHash(32) = 60 (doc label "56 B" is an arithmetic slip). */
    public static final int NODE_ANCHOR_PAYLOAD_SIZE = 60;
    public static final int AUDIT_HEAD_HASH_LEN = 32;
    /** Dual-slot codec contract: the monotonic u64 seq lives at this offset within the slot's payload/body. */
    public static final int ANCHOR_SEQ_OFFSET = 0;
    /** The keyring reuses the dual-slot codec; keyringSeq is at body offset 2 (after keyringFormatVersion:u16). */
    public static final int KEYRING_SEQ_OFFSET = 2;
    /** Keyring slot stride, FROZEN at 64 KiB: bounds retained terms (~900 local / ~200 cloud-blob); rotate REFUSES loud on overflow. */
    public static final int KEYRING_SLOT_STRIDE = 65536;

    // ---- Keyring ---------------------------------------------------------------------------
    public static final short KEYRING_FORMAT_VERSION = 1;
    public static final byte WRAP_ALG_LOCAL_GCM = 1;     // root wrapped by KEK-AES-GCM
    public static final byte WRAP_ALG_CLOUD_KMS = 2;     // opaque external-KMS blob
    public static final int  WRAP_NONCE_LEN_LOCAL = 12;
    public static final int  KEYRING_ROOT_LEN = 32;      // independent random 256-bit root per term

    // ---- Topology descriptor (§2.7) --------------------------------------------------------
    public static final short TOPO_FORMAT_VERSION = 1;
    /** formatVersion(2)+shardCount(4)+topologyEpoch(8)+reserved(4). */
    public static final int TOPO_PAYLOAD_SIZE = 18;
    public static final long TOPO_EPOCH_RESERVED = 0L;   // 0 = pre-epoch, illegal on read

    // ---- WAL container header (§2.2) -------------------------------------------------------
    public static final byte WAL_FILE_VERSION = 1;
    /** magic(4)+fileVersion(1)+flags(1)+reserved(2). */
    public static final int WAL_FILE_HEADER_SIZE = 8;

    // ---- Reserved-value discipline (§0.2) --------------------------------------------------
    /** version 0 is illegal (unset/torn) for every self-versioned format. */
    public static final int VERSION_ILLEGAL = 0;
    /** u16 0xFFFF and u8 0xFF are the reserved "extended version" escape -- unallocated in v1. */
    public static final int VERSION_ESCAPE_U16 = 0xFFFF;
    public static final int VERSION_ESCAPE_U8  = 0xFF;

    // ---- HKDF info strings (key hierarchy, anchor-rotation-design §A2.3) --------------------
    public static final String INFO_KEYRING_MAC  = "configd/keyring-mac/v1";
    public static final String INFO_KEYRING_WRAP = "configd/keyring-wrap/v1";
    public static final String INFO_INTEGRITY    = "configd/raft-at-rest-integrity/v3"; // v2->v3: source is keyring root
    public static final String INFO_DEK          = "configd/raft-at-rest-encryption/dek/v1";
}
```

### EnvelopeV3.java

```java
package io.configd.frozen;

import static io.configd.frozen.FrozenFormats.*;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32C;

/**
 * Frozen IntegrityEnvelope v3 -- the shared authenticated carrier for every at-rest artifact.
 * A conscious delta from the as-built v2 (IntegrityEnvelope.java):
 *
 * <ol>
 *   <li><b>+scopeId:4 (all postures)</b>, inserted immediately after the 8-byte header and folded
 *       into the MAC input / GCM AAD. Binds shard identity (scopeId=gid) or NODE_SCOPE; the reader
 *       asserts {@code scopeId == expectedScopeId}, closing the cross-shard splice hole (fact 3).</li>
 *   <li><b>+keyTerm:4 in the HMAC body</b> (GCM already had it) so the integrity MAC key is
 *       term-versioned in BOTH postures -- the precondition for non-destructive rotation (§A2.3).</li>
 *   <li><b>CRC-before-version parse order</b> (§0.1): a bit-flipped header now reports CRC
 *       corruption, not a misleading "unsupported version".</li>
 *   <li><b>reserved==0 enforced</b> on read (§0.2): the MBZ byte is a genuine forward-compat slot,
 *       not silently mis-parsed.</li>
 *   <li><b>formatVersion 2 -> 3</b>; version 0 / unknown / downgrade / unknown algId all fail closed.</li>
 * </ol>
 *
 * <pre>
 *   header (8):  [magic:4][formatVersion:2 = 3][algId:1][reserved:1 MBZ]
 *   NONE (0):    header ‖ [scopeId:4] ‖ [payload:N] ‖ [CRC32C:4]
 *   HMAC (1):    header ‖ [scopeId:4] ‖ [keyTerm:4] ‖ [payload:N] ‖ [MAC:32] ‖ [CRC32C:4]
 *                  MAC = HMAC(macKey[keyTerm], magic‖ver‖algId‖rsv‖scopeId‖keyTerm‖payload)
 *   GCM  (2):    header ‖ [scopeId:4] ‖ [keyTerm:4] ‖ [segmentId:16] ‖ [nonce:12] ‖ [ct+tag] ‖ [CRC32C:4]
 *                  AAD = the 44-byte prefix (magic..nonce); DEK = HKDF(root[keyTerm], salt=segmentId)
 * </pre>
 *
 * <p>Clean-break simplification vs as-built: each posture reads only its own algId (no
 * encrypting-reader-also-verifies-legacy-HMAC hybrid); nothing shipped, so §0.5 deletes legacy paths.
 */
public final class EnvelopeV3 {

    private enum Posture { KEYLESS, HMAC, GCM }

    private static final String HMAC = "HmacSHA256";
    private static final String GCM_TRANSFORM = "AES/GCM/NoPadding";
    private static final int PREFIX_MAC = ENVELOPE_HEADER_SIZE + SCOPE_ID_SIZE + KEY_TERM_SIZE; // 16
    private static final long REKEY_LIMIT = 1L << 32; // NIST SP 800-38D per-key nonce ceiling

    private static final ThreadLocal<Cipher> GCM_CIPHER = ThreadLocal.withInitial(() -> {
        try {
            return Cipher.getInstance(GCM_TRANSFORM);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES/GCM/NoPadding unavailable", e);
        }
    });

    private final Posture posture;
    private final KeyRing keys;
    // GCM writer sealing state (fresh segmentId per instance; monotonic counter nonce).
    private final SecureRandom random = new SecureRandom();
    private volatile byte[] writeSegmentId;
    private final AtomicLong nonceCounter = new AtomicLong();

    private EnvelopeV3(Posture posture, KeyRing keys) {
        this.posture = posture;
        this.keys = keys;
    }

    public static EnvelopeV3 keyless() {
        return new EnvelopeV3(Posture.KEYLESS, null);
    }

    public static EnvelopeV3 hmac(KeyRing keys) {
        return new EnvelopeV3(Posture.HMAC, Objects.requireNonNull(keys, "keys"));
    }

    public static EnvelopeV3 encrypting(KeyRing keys) {
        EnvelopeV3 e = new EnvelopeV3(Posture.GCM, Objects.requireNonNull(keys, "keys"));
        e.writeSegmentId = e.freshSegmentId();
        return e;
    }

    private boolean authenticated() {
        return posture != Posture.KEYLESS;
    }

    private byte[] freshSegmentId() {
        byte[] s = new byte[SEGMENT_ID_LEN];
        random.nextBytes(s);
        return s;
    }

    // ---- WRITE ------------------------------------------------------------------------------

    public byte[] wrap(int magic, int scopeId, byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        if (magic == KEYRING_MAGIC && posture != Posture.HMAC) {
            throw new IntegrityException(IntegrityException.Reason.MALFORMED,
                    "keyring must be sealed HMAC-only (algId=1), posture was " + posture);
        }
        return switch (posture) {
            case KEYLESS -> wrapPlain(magic, scopeId, payload);
            case HMAC -> wrapHmac(magic, scopeId, payload);
            case GCM -> wrapGcm(magic, scopeId, payload);
        };
    }

    /**
     * keyTerm 0 = the signing-key-derived key domain (K_keyringMac), legal ONLY for KEYRING_MAGIC;
     * illegal for any other magic. Every other magic must carry a real keyring term >= 1 (§6).
     */
    private static void validateKeyTerm(int magic, int keyTerm) {
        if (magic == KEYRING_MAGIC) {
            if (keyTerm != KEY_TERM_SIGNING_DOMAIN) {
                throw new IntegrityException(IntegrityException.Reason.MALFORMED,
                        "keyring envelope must carry keyTerm=0 (signing-key domain), was " + keyTerm);
            }
        } else if (keyTerm == KEY_TERM_SIGNING_DOMAIN) {
            throw new IntegrityException(IntegrityException.Reason.MALFORMED,
                    "keyTerm=0 is reserved for the keyring domain; illegal for magic 0x" + Integer.toHexString(magic));
        }
    }

    private byte[] wrapPlain(int magic, int scopeId, byte[] payload) {
        int total = ENVELOPE_HEADER_SIZE + SCOPE_ID_SIZE + payload.length + CRC_SIZE;
        ByteBuffer buf = ByteBuffer.allocate(total);
        putHeader(buf, magic, ALG_NONE);
        buf.putInt(scopeId);
        buf.put(payload);
        return finishCrc(buf.array());
    }

    private byte[] wrapHmac(int magic, int scopeId, byte[] payload) {
        int keyTerm = keys.activeTerm();
        validateKeyTerm(magic, keyTerm);
        int total = PREFIX_MAC + payload.length + MAC_SIZE + CRC_SIZE;
        byte[] out = new byte[total];
        ByteBuffer buf = ByteBuffer.wrap(out);
        putHeader(buf, magic, ALG_HMAC_SHA256);
        buf.putInt(scopeId);
        buf.putInt(keyTerm);
        buf.put(payload);
        // MAC over the 16-byte prefix (magic..keyTerm) + payload, using the term's key.
        byte[] mac = hmac(keys.macKey(keyTerm), out, PREFIX_MAC, payload);
        buf.put(mac);
        return finishCrc(out);
    }

    private byte[] wrapGcm(int magic, int scopeId, byte[] payload) {
        int keyTerm = keys.activeTerm();
        validateKeyTerm(magic, keyTerm);     // GCM is never KEYRING_MAGIC; enforces keyTerm >= 1
        byte[] nonce = nextNonce();          // may roll writeSegmentId at the 2^32 ceiling
        byte[] segmentId = writeSegmentId;   // capture AFTER any roll so (segmentId, nonce) stay paired
        int total = ENC_PREFIX_SIZE + payload.length + GCM_TAG_SIZE + CRC_SIZE;
        byte[] out = new byte[total];
        ByteBuffer buf = ByteBuffer.wrap(out);
        putHeader(buf, magic, ALG_AES256_GCM);
        buf.putInt(scopeId);
        buf.putInt(keyTerm);
        buf.put(segmentId);
        buf.put(nonce);
        byte[] aad = Arrays.copyOfRange(out, 0, ENC_PREFIX_SIZE);
        byte[] ct = gcmEncrypt(keys.dek(keyTerm, segmentId), nonce, aad, payload);
        buf.put(ct);
        return finishCrc(out);
    }

    private byte[] nextNonce() {
        long c = nonceCounter.getAndIncrement();
        if (c >= REKEY_LIMIT) { // roll to a fresh segment/DEK before the 2^32 ceiling
            writeSegmentId = freshSegmentId();
            nonceCounter.set(1);
            c = 0;
        }
        byte[] nonce = new byte[NONCE_LEN]; // 4 zero bytes || 8-byte BE counter
        ByteBuffer.wrap(nonce).position(4).putLong(c);
        return nonce;
    }

    private static void putHeader(ByteBuffer buf, int magic, byte algId) {
        buf.putInt(magic);
        buf.putShort(ENVELOPE_FORMAT_VERSION);
        buf.put(algId);
        buf.put((byte) 0); // reserved MBZ
    }

    private static byte[] finishCrc(byte[] out) {
        CRC32C crc = new CRC32C();
        crc.update(out, 0, out.length - CRC_SIZE);
        ByteBuffer.wrap(out, out.length - CRC_SIZE, CRC_SIZE).putInt((int) crc.getValue());
        return out;
    }

    // ---- READ -------------------------------------------------------------------------------

    /** Throwing unwrap; treats a structurally-absent buffer as TRUNCATED. */
    public byte[] unwrap(int expectedMagic, int expectedScopeId, byte[] enveloped) {
        byte[] p = unwrapOrNull(expectedMagic, expectedScopeId, enveloped);
        if (p == null) {
            throw new IntegrityException(IntegrityException.Reason.TRUNCATED,
                    "absent/too-short envelope for magic 0x" + Integer.toHexString(expectedMagic));
        }
        return p;
    }

    /**
     * Returns null ONLY for a structurally-absent buffer (below floor, or keyless + wrong magic):
     * the empty-slot / first-boot / torn-tail case. A present-but-bad envelope FAILS LOUD.
     */
    public byte[] unwrapOrNull(int expectedMagic, int expectedScopeId, byte[] enveloped) {
        if (enveloped == null || enveloped.length < Integer.BYTES) {
            return null;
        }
        int magic = ByteBuffer.wrap(enveloped).getInt();
        if (magic != expectedMagic) {
            if (authenticated() && enveloped.length >= ENVELOPE_HEADER_SIZE + CRC_SIZE) {
                throw new IntegrityException(IntegrityException.Reason.MAGIC,
                        "expected magic 0x" + Integer.toHexString(expectedMagic)
                                + " but found 0x" + Integer.toHexString(magic));
            }
            return null; // keyless back-compat or sub-floor: structurally absent
        }
        if (enveloped.length < ENVELOPE_HEADER_SIZE + CRC_SIZE) {
            if (authenticated()) {
                throw new IntegrityException(IntegrityException.Reason.TRUNCATED,
                        "envelope truncated (magic present, length " + enveloped.length + ")");
            }
            return null;
        }

        // (§0.1) CRC FIRST -- version-independent, so a corrupt header reports corruption.
        verifyCrc(enveloped, expectedMagic);

        ByteBuffer buf = ByteBuffer.wrap(enveloped);
        buf.position(Integer.BYTES);
        short formatVersion = buf.getShort();
        if (formatVersion != ENVELOPE_FORMAT_VERSION) {
            throw new IntegrityException(IntegrityException.Reason.VERSION,
                    "unsupported envelope formatVersion " + formatVersion
                            + " (expected " + ENVELOPE_FORMAT_VERSION + ")");
        }
        byte algId = buf.get();
        byte reserved = buf.get();
        if (reserved != 0) { // (§0.2) MBZ enforced -- covered by MAC/AAD is NOT a substitute
            throw new IntegrityException(IntegrityException.Reason.RESERVED,
                    "reserved byte must be zero, was " + (reserved & 0xFF));
        }
        int scopeId = buf.getInt(); // at fixed offset 8 for every algId
        if (scopeId != expectedScopeId) {
            throw new IntegrityException(IntegrityException.Reason.SCOPE,
                    "scopeId 0x" + Integer.toHexString(scopeId)
                            + " != expected 0x" + Integer.toHexString(expectedScopeId)
                            + " (cross-shard/scope splice)");
        }
        if (expectedMagic == KEYRING_MAGIC && algId != ALG_HMAC_SHA256) {
            throw new IntegrityException(IntegrityException.Reason.DOWNGRADE,
                    "keyring envelope must be HMAC (algId=1), was " + (algId & 0xFF));
        }

        return switch (algId) {
            case ALG_NONE -> readPlain(expectedMagic, enveloped);
            case ALG_HMAC_SHA256 -> readHmac(expectedMagic, enveloped);
            case ALG_AES256_GCM -> readGcm(expectedMagic, enveloped);
            default -> throw new IntegrityException(IntegrityException.Reason.ALGID,
                    "unknown algId " + (algId & 0xFF));
        };
    }

    private byte[] readPlain(int expectedMagic, byte[] enveloped) {
        if (authenticated()) {
            throw new IntegrityException(IntegrityException.Reason.DOWNGRADE,
                    "algId=NONE under a configured key (downgrade refused) for magic 0x"
                            + Integer.toHexString(expectedMagic));
        }
        int start = ENVELOPE_HEADER_SIZE + SCOPE_ID_SIZE;
        return Arrays.copyOfRange(enveloped, start, enveloped.length - CRC_SIZE);
    }

    private byte[] readHmac(int expectedMagic, byte[] enveloped) {
        if (posture != Posture.HMAC) {
            throw new IntegrityException(IntegrityException.Reason.DOWNGRADE,
                    "HMAC record under a " + posture + " reader for magic 0x"
                            + Integer.toHexString(expectedMagic));
        }
        int keyTerm = ByteBuffer.wrap(enveloped, ENVELOPE_HEADER_SIZE + SCOPE_ID_SIZE, KEY_TERM_SIZE).getInt();
        validateKeyTerm(expectedMagic, keyTerm);
        int payloadLen = enveloped.length - PREFIX_MAC - MAC_SIZE - CRC_SIZE;
        if (payloadLen < 0) {
            throw new IntegrityException(IntegrityException.Reason.TRUNCATED,
                    "HMAC envelope truncated (length " + enveloped.length + ")");
        }
        byte[] payload = Arrays.copyOfRange(enveloped, PREFIX_MAC, PREFIX_MAC + payloadLen);
        byte[] storedMac = Arrays.copyOfRange(enveloped, PREFIX_MAC + payloadLen, PREFIX_MAC + payloadLen + MAC_SIZE);
        byte[] computed = hmac(keys.macKey(keyTerm), enveloped, PREFIX_MAC, payload);
        if (!MessageDigest.isEqual(computed, storedMac)) {
            throw new IntegrityException(IntegrityException.Reason.MAC,
                    "HMAC mismatch (tamper) for magic 0x" + Integer.toHexString(expectedMagic));
        }
        return payload;
    }

    private byte[] readGcm(int expectedMagic, byte[] enveloped) {
        if (posture != Posture.GCM) {
            throw new IntegrityException(IntegrityException.Reason.DOWNGRADE,
                    "GCM record under a " + posture + " reader for magic 0x"
                            + Integer.toHexString(expectedMagic));
        }
        if (enveloped.length < ENC_MIN_SIZE) {
            throw new IntegrityException(IntegrityException.Reason.TRUNCATED,
                    "GCM envelope truncated (length " + enveloped.length + ", min " + ENC_MIN_SIZE + ")");
        }
        ByteBuffer buf = ByteBuffer.wrap(enveloped);
        buf.position(ENVELOPE_HEADER_SIZE + SCOPE_ID_SIZE);
        int keyTerm = buf.getInt();
        validateKeyTerm(expectedMagic, keyTerm);
        byte[] segmentId = new byte[SEGMENT_ID_LEN];
        buf.get(segmentId);
        byte[] nonce = new byte[NONCE_LEN];
        buf.get(nonce);
        int cipherLen = enveloped.length - CRC_SIZE - ENC_PREFIX_SIZE;
        byte[] ct = new byte[cipherLen];
        buf.get(ct);
        SecretKey dek = keys.dek(keyTerm, segmentId); // unknown term -> UNKNOWN_TERM (fail closed)
        byte[] aad = Arrays.copyOfRange(enveloped, 0, ENC_PREFIX_SIZE);
        try {
            return gcmDecrypt(dek, nonce, aad, ct);
        } catch (AEADBadTagException e) {
            throw new IntegrityException(IntegrityException.Reason.TAG,
                    "GCM tag failure (tamper) for magic 0x" + Integer.toHexString(expectedMagic), e);
        } catch (GeneralSecurityException e) {
            throw new IntegrityException(IntegrityException.Reason.MALFORMED,
                    "GCM decrypt error for magic 0x" + Integer.toHexString(expectedMagic), e);
        }
    }

    private static void verifyCrc(byte[] enveloped, int expectedMagic) {
        int crcOffset = enveloped.length - CRC_SIZE;
        CRC32C crc = new CRC32C();
        crc.update(enveloped, 0, crcOffset);
        int computed = (int) crc.getValue();
        int stored = ByteBuffer.wrap(enveloped, crcOffset, CRC_SIZE).getInt();
        if (computed != stored) {
            throw new IntegrityException(IntegrityException.Reason.CRC,
                    "CRC32C mismatch for magic 0x" + Integer.toHexString(expectedMagic)
                            + " (computed=0x" + Integer.toHexString(computed)
                            + ", stored=0x" + Integer.toHexString(stored) + ")");
        }
    }

    private static byte[] hmac(SecretKey key, byte[] prefixSource, int prefixLen, byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(key);
            mac.update(prefixSource, 0, prefixLen);
            mac.update(payload);
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable or bad key", e);
        }
    }

    private static byte[] gcmEncrypt(SecretKey dek, byte[] nonce, byte[] aad, byte[] plaintext) {
        try {
            Cipher c = GCM_CIPHER.get();
            c.init(Cipher.ENCRYPT_MODE, dek, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            c.updateAAD(aad);
            return c.doFinal(plaintext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-256-GCM encrypt failed", e);
        }
    }

    private static byte[] gcmDecrypt(SecretKey dek, byte[] nonce, byte[] aad, byte[] ct)
            throws GeneralSecurityException {
        Cipher c = GCM_CIPHER.get();
        c.init(Cipher.DECRYPT_MODE, dek, new GCMParameterSpec(GCM_TAG_BITS, nonce));
        c.updateAAD(aad);
        return c.doFinal(ct);
    }
}
```

### IntegrityException.java

```java
package io.configd.frozen;

/**
 * Thrown on any frozen-format verification failure. Carries a typed {@link Reason} so callers
 * (and the self-test) can assert WHICH gate fired -- in particular that a corrupt header reports
 * {@link Reason#CRC} (corruption) rather than {@link Reason#VERSION} (§0.1 CRC-before-version).
 */
public final class IntegrityException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Reason {
        TRUNCATED,     // structurally too short (magic present)
        MAGIC,         // wrong / unexpected artifact magic
        CRC,           // CRC32C mismatch (corruption) -- checked before version
        VERSION,       // unsupported/rolled formatVersion (incl. version 0)
        RESERVED,      // MBZ reserved field != 0
        ALGID,         // unknown algId
        DOWNGRADE,     // algId=NONE (or unexpected posture) under a configured key
        SCOPE,         // scopeId != expected (cross-shard / cross-scope splice)
        MAC,           // HMAC mismatch (tamper)
        TAG,           // AES-GCM tag failure (tamper)
        UNKNOWN_TERM,  // keyTerm not present in the keyring (fail closed)
        STALE_TOPOLOGY,// cursor topologyEpoch != current -> full re-hydrate
        MALFORMED      // grammar / field-level malformation
    }

    private final Reason reason;

    public IntegrityException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public IntegrityException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
```

### AnchorRecord.java

```java
package io.configd.frozen;

import static io.configd.frozen.FrozenFormats.ANCHOR_PAYLOAD_SIZE;

import java.nio.ByteBuffer;

/**
 * Per-shard anchor payload (52 B), merged from {@code raft.persistent_state} + the durable-prefix /
 * snapshot boundary (anchor-rotation-design §A1.3). Rides EnvelopeV3 under {@code ANCHOR_MAGIC},
 * {@code scopeId = gid}. The monotonic {@code anchorSeq} is the anti-rollback index and, per the
 * dual-slot codec contract, occupies the first 8 bytes.
 *
 * <pre>
 *   [anchorSeq:8][currentTerm:8][votedFor:4][lastDurableIndex:8][lastDurableTerm:8][snapshotIndex:8][snapshotTerm:8]
 * </pre>
 */
public record AnchorRecord(
        long anchorSeq,
        long currentTerm,
        int votedFor,          // -1 = null
        long lastDurableIndex,
        long lastDurableTerm,
        long snapshotIndex,
        long snapshotTerm) {

    public byte[] encode() {
        ByteBuffer buf = ByteBuffer.allocate(ANCHOR_PAYLOAD_SIZE);
        buf.putLong(anchorSeq);
        buf.putLong(currentTerm);
        buf.putInt(votedFor);
        buf.putLong(lastDurableIndex);
        buf.putLong(lastDurableTerm);
        buf.putLong(snapshotIndex);
        buf.putLong(snapshotTerm);
        return buf.array();
    }

    public static AnchorRecord decode(byte[] payload) {
        if (payload.length != ANCHOR_PAYLOAD_SIZE) {
            throw new IntegrityException(IntegrityException.Reason.MALFORMED,
                    "anchor payload must be " + ANCHOR_PAYLOAD_SIZE + " B, was " + payload.length);
        }
        ByteBuffer buf = ByteBuffer.wrap(payload);
        return new AnchorRecord(
                buf.getLong(), buf.getLong(), buf.getInt(),
                buf.getLong(), buf.getLong(), buf.getLong(), buf.getLong());
    }
}
```

### AnchorCodec.java

```java
package io.configd.frozen;

import static io.configd.frozen.FrozenFormats.*;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

/**
 * The dual-slot slotted-file writer/reader (bbolt/LMDB meta-page pattern, anchor-rotation-design
 * §A1.3 + §6 container header). Generic over the payload and the slot geometry -- reused by the
 * per-shard {@link AnchorRecord}, the {@link NodeAnchorRecord}, AND the keyring body (via the
 * {@code (stride, seqOffset)} params). Bound to one {@code (envelope, magic, scopeId)}.
 *
 * <pre>
 *   File = [container header:8][slot 0:stride][slot 1:stride]
 *     container header @ 0 (UNAUTHENTICATED, §6): [magic:4][fileVersion:u8=1][flags:u8=0][reserved:u16=0]
 *     slot i @ CONTAINER_HEADER_SIZE + i*stride: [recordLen:4][ EnvelopeV3.wrap(magic,scopeId,payload) ][ zero-pad ]
 * </pre>
 *
 * Contract: the payload's monotonic u64 seq is at {@code seqOffset}. A normal update
 * ({@link #writeUpdate}) writes the fresh record into the STALE (lower-seq / empty / tampered) slot
 * only, so a torn write is confined to one slot. The signing-key handover uses {@link #writeSlot}
 * (a TARGETED write) because during rotation the still-valid old-key slot must be PRESERVED, and it
 * is intentionally unreadable under the new key (§A2.5).
 */
public final class AnchorCodec {

    public enum Status { FRESH, PROCEED, REFUSE }

    /** Result of opening a slotted file; {@code payload} is the winning slot's verified payload. */
    public record OpenResult(Status status, byte[] payload, int slot, long seq, String reason) {}

    private enum Kind { EMPTY, VALID, TAMPERED }

    private record Slot(Kind kind, long seq, byte[] payload) {}

    private final EnvelopeV3 envelope;
    private final int magic;
    private final int scopeId;
    private final int slotStride;
    private final int seqOffset;
    private final int fileSize;

    public AnchorCodec(EnvelopeV3 envelope, int magic, int scopeId, int slotStride, int seqOffset) {
        this.envelope = Objects.requireNonNull(envelope, "envelope");
        this.magic = magic;
        this.scopeId = scopeId;
        this.slotStride = slotStride;
        this.seqOffset = seqOffset;
        this.fileSize = CONTAINER_HEADER_SIZE + ANCHOR_SLOT_COUNT * slotStride;
    }

    /** Convenience for the 512-byte, seq-at-0 per-shard / node anchors. */
    public static AnchorCodec forAnchor(EnvelopeV3 envelope, int magic, int scopeId) {
        return new AnchorCodec(envelope, magic, scopeId, ANCHOR_SLOT_STRIDE, ANCHOR_SEQ_OFFSET);
    }

    private long seqOf(byte[] payload) {
        return ByteBuffer.wrap(payload, seqOffset, Long.BYTES).getLong();
    }

    private int slotOffset(int i) {
        return CONTAINER_HEADER_SIZE + i * slotStride;
    }

    private void writeContainerHeader(byte[] file) {
        ByteBuffer h = ByteBuffer.wrap(file, 0, CONTAINER_HEADER_SIZE);
        h.putInt(magic);
        h.put(CONTAINER_FILE_VERSION);
        h.put((byte) 0);        // flags
        h.putShort((short) 0);  // reserved MBZ
    }

    /** Foreign-file/version guard: magic match, fileVersion==1, reserved==0. flags ignored (forward-compat). */
    private boolean containerHeaderValid(byte[] file) {
        ByteBuffer h = ByteBuffer.wrap(file, 0, CONTAINER_HEADER_SIZE);
        int m = h.getInt();
        int fv = h.get() & 0xFF;
        h.get(); // flags -- reserved for forward-compat, not validated
        int rsv = h.getShort() & 0xFFFF;
        return m == magic && fv == (CONTAINER_FILE_VERSION & 0xFF) && rsv == 0;
    }

    /** Encodes one {@code slotStride}-byte slot image for {@code payload}. */
    public byte[] slotBytes(byte[] payload) {
        byte[] env = envelope.wrap(magic, scopeId, payload);
        if (ANCHOR_RECLEN_SIZE + env.length > slotStride) {
            throw new IntegrityException(IntegrityException.Reason.MALFORMED,
                    "slot record " + env.length + " B exceeds stride " + slotStride);
        }
        byte[] slot = new byte[slotStride]; // zero-padded
        ByteBuffer buf = ByteBuffer.wrap(slot);
        buf.putInt(env.length);
        buf.put(env);
        return slot;
    }

    /** First write on a fresh node: container header + slot0 = record, slot1 = empty. */
    public byte[] bootstrap(byte[] payload) {
        byte[] file = new byte[fileSize];
        writeContainerHeader(file);
        System.arraycopy(slotBytes(payload), 0, file, slotOffset(0), slotStride);
        return file;
    }

    /** The seq the NEXT update should stamp = max valid seq + 1 (0 -> 1 on a fresh/absent file). */
    public long nextSeq(byte[] fileOrNull) {
        if (fileOrNull == null) {
            return 1L;
        }
        long max = 0L;
        for (int i = 0; i < ANCHOR_SLOT_COUNT; i++) {
            Slot s = parseSlot(fileOrNull, i);
            if (s.kind == Kind.VALID) {
                max = Math.max(max, s.seq);
            }
        }
        return max + 1;
    }

    /** Normal update: writes {@code payload} into the stale slot; returns the new file. */
    public byte[] writeUpdate(byte[] file, byte[] payload) {
        long s0 = slotSeqOrStale(file, 0);
        long s1 = slotSeqOrStale(file, 1);
        return writeSlot(file, (s0 <= s1) ? 0 : 1, payload); // overwrite the lower-seq (stale) slot
    }

    /** Targeted write into a specific slot, preserving the header and the other slot (the §A2.5 handover). */
    public byte[] writeSlot(byte[] file, int slotIndex, byte[] payload) {
        if (file.length != fileSize) {
            throw new IntegrityException(IntegrityException.Reason.MALFORMED,
                    "slotted file must be " + fileSize + " B, was " + file.length);
        }
        byte[] next = file.clone();
        System.arraycopy(slotBytes(payload), 0, next, slotOffset(slotIndex), slotStride);
        return next;
    }

    private long slotSeqOrStale(byte[] file, int i) {
        Slot s = parseSlot(file, i);
        return s.kind == Kind.VALID ? s.seq : -1L; // empty/tampered are maximally stale
    }

    /** Opens the file and classifies per the §A1.4 Step-2 decision table. */
    public OpenResult open(byte[] fileOrNull, boolean nonEmptyDir) {
        if (fileOrNull == null) {
            return nonEmptyDir
                    ? new OpenResult(Status.REFUSE, null, -1, 0, "anchor absent but data directory non-empty (deleted)")
                    : new OpenResult(Status.FRESH, null, -1, 0, "no anchor file, empty data directory");
        }
        if (fileOrNull.length != fileSize) {
            return new OpenResult(Status.REFUSE, null, -1, 0, "slotted file wrong size " + fileOrNull.length);
        }
        if (!containerHeaderValid(fileOrNull)) {
            return new OpenResult(Status.REFUSE, null, -1, 0, "bad/foreign container header");
        }
        Slot best = null;
        int bestSlot = -1;
        boolean anyTampered = false;
        for (int i = 0; i < ANCHOR_SLOT_COUNT; i++) {
            Slot s = parseSlot(fileOrNull, i);
            if (s.kind == Kind.TAMPERED) {
                anyTampered = true;
            } else if (s.kind == Kind.VALID && (best == null || s.seq > best.seq)) {
                best = s;
                bestSlot = i;
            }
        }
        if (best != null) {
            return new OpenResult(Status.PROCEED, best.payload, bestSlot, best.seq,
                    "highest valid seq=" + best.seq + " in slot " + bestSlot);
        }
        return new OpenResult(Status.REFUSE, null, -1, 0,
                anyTampered ? "all slots invalid (tamper)" : "file present but carries no valid slot");
    }

    private Slot parseSlot(byte[] file, int i) {
        int off = slotOffset(i);
        int recLen = ByteBuffer.wrap(file, off, ANCHOR_RECLEN_SIZE).getInt();
        if (recLen == 0) {
            return new Slot(Kind.EMPTY, -1L, null);
        }
        if (recLen < 0 || recLen > slotStride - ANCHOR_RECLEN_SIZE) {
            return new Slot(Kind.TAMPERED, -1L, null);
        }
        byte[] env = Arrays.copyOfRange(file, off + ANCHOR_RECLEN_SIZE, off + ANCHOR_RECLEN_SIZE + recLen);
        try {
            byte[] payload = envelope.unwrap(magic, scopeId, env);
            return new Slot(Kind.VALID, seqOf(payload), payload);
        } catch (IntegrityException e) {
            return new Slot(Kind.TAMPERED, -1L, null); // MAC/CRC/scope/version failure -> tamper
        }
    }
}
```

### NodeAnchorRecord.java

```java
package io.configd.frozen;

import static io.configd.frozen.FrozenFormats.AUDIT_HEAD_HASH_LEN;
import static io.configd.frozen.FrozenFormats.NODE_ANCHOR_PAYLOAD_SIZE;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

/**
 * Node-level anchor payload (60 B) -- authenticates the topology (a COPY of the standalone
 * {@link TopologyDescriptor}'s {@code {topologyEpoch, shardCount}}) and the audit-chain head
 * (anchor-rotation-design §A1.6). Rides EnvelopeV3 under {@code NODE_ANCHOR_MAGIC},
 * {@code scopeId = NODE_SCOPE}. Shares the dual-slot codec (nodeAnchorSeq is the first 8 bytes).
 *
 * <pre>
 *   [nodeAnchorSeq:8][topologyEpoch:8][shardCount:4][auditRecordCount:8][auditHeadHash:32]
 * </pre>
 *
 * The {@code TopologyDescriptor} remains the authoritative epoch/N source; this bound copy lets
 * recovery catch a topology-file ROLLBACK (swap for an older legitimately-MAC'd descriptor) via the
 * cross-check in {@link #requireConsistentWith}.
 */
public record NodeAnchorRecord(
        long nodeAnchorSeq,
        long topologyEpoch,
        int shardCount,
        long auditRecordCount,
        byte[] auditHeadHash) {

    public NodeAnchorRecord {
        Objects.requireNonNull(auditHeadHash, "auditHeadHash");
        if (auditHeadHash.length != AUDIT_HEAD_HASH_LEN) {
            throw new IntegrityException(IntegrityException.Reason.MALFORMED,
                    "auditHeadHash must be " + AUDIT_HEAD_HASH_LEN + " B, was " + auditHeadHash.length);
        }
        auditHeadHash = auditHeadHash.clone();
    }

    public byte[] encode() {
        ByteBuffer buf = ByteBuffer.allocate(NODE_ANCHOR_PAYLOAD_SIZE);
        buf.putLong(nodeAnchorSeq);
        buf.putLong(topologyEpoch);
        buf.putInt(shardCount);
        buf.putLong(auditRecordCount);
        buf.put(auditHeadHash);
        return buf.array();
    }

    public static NodeAnchorRecord decode(byte[] payload) {
        if (payload.length != NODE_ANCHOR_PAYLOAD_SIZE) {
            throw new IntegrityException(IntegrityException.Reason.MALFORMED,
                    "node-anchor payload must be " + NODE_ANCHOR_PAYLOAD_SIZE + " B, was " + payload.length);
        }
        ByteBuffer buf = ByteBuffer.wrap(payload);
        long seq = buf.getLong();
        long epoch = buf.getLong();
        int shardCount = buf.getInt();
        long auditCount = buf.getLong();
        byte[] hash = new byte[AUDIT_HEAD_HASH_LEN];
        buf.get(hash);
        return new NodeAnchorRecord(seq, epoch, shardCount, auditCount, hash);
    }

    /**
     * Recovery gate (§A1.6): the standalone {@link TopologyDescriptor} MUST agree with this bound
     * copy. A mismatch means a topology-file rollback/tamper -> REFUSE (fail closed).
     */
    public void requireConsistentWith(TopologyDescriptor topo) {
        if (topo.topologyEpoch() != topologyEpoch || topo.shardCount() != shardCount) {
            throw new IntegrityException(IntegrityException.Reason.MALFORMED,
                    "node-anchor {epoch=" + topologyEpoch + ",N=" + shardCount + "} != topology descriptor {epoch="
                            + topo.topologyEpoch() + ",N=" + topo.shardCount() + "} (topology rollback/mismatch)");
        }
    }

    @Override
    public byte[] auditHeadHash() {
        return auditHeadHash.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof NodeAnchorRecord n)) return false;
        return nodeAnchorSeq == n.nodeAnchorSeq && topologyEpoch == n.topologyEpoch
                && shardCount == n.shardCount && auditRecordCount == n.auditRecordCount
                && Arrays.equals(auditHeadHash, n.auditHeadHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodeAnchorSeq, topologyEpoch, shardCount, auditRecordCount, Arrays.hashCode(auditHeadHash));
    }
}
```

### KeyRing.java

```java
package io.configd.frozen;

import javax.crypto.SecretKey;

/**
 * Term-versioned key material, as it exists AFTER boot has unwrapped {@code raft-keyring}
 * (anchor-rotation-design §A2.3): the active write term plus, for every retained term, the HMAC
 * integrity key and the per-segment DEK. Rotation appends a term; old terms are retained forever;
 * an unknown term FAILS CLOSED (never re-derives, never returns null) -- the precondition for
 * non-destructive rotation.
 */
public interface KeyRing {

    /** The active term new writes stamp. */
    int activeTerm();

    /** HMAC-SHA-256 integrity key for {@code keyTerm}. Unknown term -> IntegrityException(UNKNOWN_TERM). */
    SecretKey macKey(int keyTerm);

    /** AES-256 DEK = HKDF(root[keyTerm], salt=segmentId). Unknown term -> IntegrityException(UNKNOWN_TERM). */
    SecretKey dek(int keyTerm, byte[] segmentId);
}
```

### KeyringCodec.java

```java
package io.configd.frozen;

import static io.configd.frozen.FrozenFormats.*;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The {@code raft-keyring} body codec + rotation primitives (anchor-rotation-design §A2). Roots are
 * INDEPENDENT random 32-byte secrets (not HKDF of the signing key), wrapped per-term and retained
 * forever; rotation APPENDS a term. The whole body rides an outer HMAC EnvelopeV3 under
 * {@code K_keyringMac} (strip/swap/add/truncate of entries fails loud). Each local entry's root is
 * AES-GCM-wrapped under {@code KEK_wrap} with an AAD binding {@code (magic, formatVersion, term,
 * wrapAlgId, nodeKeyId, "root")}, so a wrapped root cannot be replayed into a different term/node.
 *
 * <pre>
 *   KEYRING_BODY:
 *     [keyringFormatVersion:2 = 1][keyringSeq:8][activeTerm:4][entryCount:4]
 *     entry*: [term:4][wrapAlgId:1][nonceLen:1][nonce][wrappedLen:4][wrappedRoot]
 * </pre>
 *
 * The physical file is dual-slot (same mechanics as {@link AnchorCodec}) so signing-key rotation is
 * crash-atomic; this codec models the body + rotation pure-functions (the slot placement is reused).
 */
public final class KeyringCodec {

    private static final byte[] ROOT_LABEL = "root".getBytes(StandardCharsets.UTF_8);
    private static final String GCM_TRANSFORM = "AES/GCM/NoPadding";

    private KeyringCodec() {}

    /** One wrapped root. For {@code wrapAlgId=CLOUD_KMS} the {@code wrappedRoot} is an opaque blob and nonce is empty. */
    public record KeyringEntry(int term, byte wrapAlgId, byte[] nonce, byte[] wrappedRoot) {
        public KeyringEntry {
            Objects.requireNonNull(nonce, "nonce");
            Objects.requireNonNull(wrappedRoot, "wrappedRoot");
            nonce = nonce.clone();
            wrappedRoot = wrappedRoot.clone();
        }
    }

    /** The keyring body model (entries still wrapped). */
    public record Keyring(int keyringFormatVersion, long keyringSeq, int activeTerm, List<KeyringEntry> entries) {
        public Keyring {
            entries = List.copyOf(entries);
        }
    }

    // ---- body serialization -----------------------------------------------------------------

    public static byte[] encodeBody(Keyring k) {
        ByteBuffer head = ByteBuffer.allocate(2 + 8 + 4 + 4);
        head.putShort((short) k.keyringFormatVersion());
        head.putLong(k.keyringSeq());
        head.putInt(k.activeTerm());
        head.putInt(k.entries().size());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(head.array());
        for (KeyringEntry e : k.entries()) {
            if (e.nonce().length > 0xFF) {
                throw new IntegrityException(IntegrityException.Reason.MALFORMED, "nonce too long");
            }
            ByteBuffer eb = ByteBuffer.allocate(4 + 1 + 1 + e.nonce().length + 4 + e.wrappedRoot().length);
            eb.putInt(e.term());
            eb.put(e.wrapAlgId());
            eb.put((byte) e.nonce().length);
            eb.put(e.nonce());
            eb.putInt(e.wrappedRoot().length);
            eb.put(e.wrappedRoot());
            out.writeBytes(eb.array());
        }
        return out.toByteArray();
    }

    public static Keyring decodeBody(byte[] body) {
        ByteBuffer buf = ByteBuffer.wrap(body);
        int fmt = buf.getShort() & 0xFFFF;
        if (fmt == VERSION_ILLEGAL || fmt != KEYRING_FORMAT_VERSION) {
            throw new IntegrityException(IntegrityException.Reason.VERSION,
                    "unsupported keyringFormatVersion " + fmt);
        }
        long seq = buf.getLong();
        int activeTerm = buf.getInt();
        int count = buf.getInt();
        if (count < 0) {
            throw new IntegrityException(IntegrityException.Reason.MALFORMED, "negative entryCount");
        }
        List<KeyringEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int term = buf.getInt();
            if (term == 0) { // term 0 reserved (mirrors hardcoded term=1 boot value)
                throw new IntegrityException(IntegrityException.Reason.MALFORMED, "keyring term 0 is reserved");
            }
            byte wrapAlgId = buf.get();
            if (wrapAlgId != WRAP_ALG_LOCAL_GCM && wrapAlgId != WRAP_ALG_CLOUD_KMS) {
                throw new IntegrityException(IntegrityException.Reason.ALGID,
                        "unknown keyring wrapAlgId " + (wrapAlgId & 0xFF));
            }
            int nonceLen = buf.get() & 0xFF;
            byte[] nonce = new byte[nonceLen];
            buf.get(nonce);
            int wrappedLen = buf.getInt();
            if (wrappedLen < 0 || wrappedLen > buf.remaining()) {
                throw new IntegrityException(IntegrityException.Reason.MALFORMED, "bad wrappedLen " + wrappedLen);
            }
            byte[] wrapped = new byte[wrappedLen];
            buf.get(wrapped);
            entries.add(new KeyringEntry(term, wrapAlgId, nonce, wrapped));
        }
        return new Keyring(fmt, seq, activeTerm, entries);
    }

    /** Wraps the whole body in the outer HMAC envelope under K_keyringMac. */
    public static byte[] seal(EnvelopeV3 outerMac, Keyring k) {
        return outerMac.wrap(KEYRING_MAGIC, NODE_SCOPE, encodeBody(k));
    }

    /** Verifies the outer MAC then parses the body. Unknown format/wrapAlgId/term -> fail closed. */
    public static Keyring openSealed(EnvelopeV3 outerMac, byte[] enveloped) {
        return decodeBody(outerMac.unwrap(KEYRING_MAGIC, NODE_SCOPE, enveloped));
    }

    // ---- root wrap / unwrap (local GCM, AAD-bound) ------------------------------------------

    private static byte[] entryAad(int keyringFormatVersion, int term, byte wrapAlgId, byte[] nodeKeyId) {
        ByteBuffer aad = ByteBuffer.allocate(4 + 2 + 4 + 1 + nodeKeyId.length + ROOT_LABEL.length);
        aad.putInt(KEYRING_MAGIC);
        aad.putShort((short) keyringFormatVersion);
        aad.putInt(term);
        aad.put(wrapAlgId);
        aad.put(nodeKeyId);
        aad.put(ROOT_LABEL);
        return aad.array();
    }

    public static KeyringEntry wrapRoot(SecretKey kek, byte[] nodeKeyId, int term, byte[] root) {
        if (root.length != KEYRING_ROOT_LEN) {
            throw new IntegrityException(IntegrityException.Reason.MALFORMED, "root must be 32 B");
        }
        byte[] nonce = new byte[WRAP_NONCE_LEN_LOCAL];
        ByteBuffer.wrap(nonce).position(4).putInt(term); // deterministic-per-term nonce is fine: KEK is single-use per term
        byte[] aad = entryAad(KEYRING_FORMAT_VERSION, term, WRAP_ALG_LOCAL_GCM, nodeKeyId);
        try {
            Cipher c = Cipher.getInstance(GCM_TRANSFORM);
            c.init(Cipher.ENCRYPT_MODE, kek, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            c.updateAAD(aad);
            return new KeyringEntry(term, WRAP_ALG_LOCAL_GCM, nonce, c.doFinal(root));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("keyring wrap failed", e);
        }
    }

    public static byte[] unwrapRoot(SecretKey kek, byte[] nodeKeyId, int keyringFormatVersion, KeyringEntry e) {
        if (e.wrapAlgId() != WRAP_ALG_LOCAL_GCM) {
            throw new IntegrityException(IntegrityException.Reason.ALGID,
                    "cannot locally unwrap wrapAlgId " + (e.wrapAlgId() & 0xFF) + " (cloud KMS is external)");
        }
        byte[] aad = entryAad(keyringFormatVersion, e.term(), e.wrapAlgId(), nodeKeyId);
        try {
            Cipher c = Cipher.getInstance(GCM_TRANSFORM);
            c.init(Cipher.DECRYPT_MODE, kek, new GCMParameterSpec(GCM_TAG_BITS, e.nonce()));
            c.updateAAD(aad);
            return c.doFinal(e.wrappedRoot());
        } catch (javax.crypto.AEADBadTagException tag) {
            throw new IntegrityException(IntegrityException.Reason.TAG,
                    "keyring root unwrap tag failure (wrong KEK / replayed into another term/node)", tag);
        } catch (GeneralSecurityException e2) {
            throw new IntegrityException(IntegrityException.Reason.MALFORMED, "keyring unwrap error", e2);
        }
    }

    /** Boot: unwrap every local entry -> term->root map. Unknown term is a caller lookup concern; here we materialise all retained terms. */
    public static Map<Integer, byte[]> unsealRoots(SecretKey kek, byte[] nodeKeyId, Keyring k) {
        Map<Integer, byte[]> roots = new LinkedHashMap<>();
        for (KeyringEntry e : k.entries()) {
            if (e.wrapAlgId() == WRAP_ALG_LOCAL_GCM) {
                roots.put(e.term(), unwrapRoot(kek, nodeKeyId, k.keyringFormatVersion(), e));
            }
        }
        return roots;
    }

    // ---- rotation pure-functions ------------------------------------------------------------

    /** First boot: a keyring with one random root[1], activeTerm=1, keyringSeq=1. */
    public static Keyring bootstrap(SecretKey kek, byte[] nodeKeyId, byte[] root1) {
        return new Keyring(KEYRING_FORMAT_VERSION, 1L, 1,
                List.of(wrapRoot(kek, nodeKeyId, 1, root1)));
    }

    /** Term rotation: append root[activeTerm+1]; keyringSeq+1; activeTerm bumps. Old entries untouched. */
    public static Keyring appendTerm(SecretKey kek, byte[] nodeKeyId, Keyring old, byte[] newRoot) {
        int newTerm = old.activeTerm() + 1;
        List<KeyringEntry> entries = new ArrayList<>(old.entries());
        entries.add(wrapRoot(kek, nodeKeyId, newTerm, newRoot));
        return new Keyring(old.keyringFormatVersion(), old.keyringSeq() + 1, newTerm, entries);
    }

    /**
     * Signing-key rotation: unwrap every root under {@code oldKek}, rewrap under {@code newKek}.
     * ROOTS ARE UNCHANGED (so every DEK/K_integrity[term] is unchanged -> all prior data still
     * verifies); only the wrapping KEK changes. keyringSeq+1, activeTerm unchanged. Pure function.
     */
    public static Keyring rewrapUnderNewKek(SecretKey oldKek, SecretKey newKek, byte[] nodeKeyId, Keyring old) {
        List<KeyringEntry> rewrapped = new ArrayList<>(old.entries().size());
        for (KeyringEntry e : old.entries()) {
            if (e.wrapAlgId() == WRAP_ALG_LOCAL_GCM) {
                byte[] root = unwrapRoot(oldKek, nodeKeyId, old.keyringFormatVersion(), e);
                rewrapped.add(wrapRoot(newKek, nodeKeyId, e.term(), root));
            } else {
                rewrapped.add(e); // cloud blobs are opaque; custody handled by the external KMS
            }
        }
        return new Keyring(old.keyringFormatVersion(), old.keyringSeq() + 1, old.activeTerm(), rewrapped);
    }
}
```

### TopologyDescriptor.java

```java
package io.configd.frozen;

import static io.configd.frozen.FrozenFormats.*;

import java.nio.ByteBuffer;

/**
 * The authenticated topology descriptor (version-markers §2.7) replacing the plaintext
 * {@code raft-shard-count.meta}. Rides EnvelopeV3 under {@code TOPO_MAGIC}, {@code scopeId =
 * NODE_SCOPE}. Serves BOTH the fixed-N deploy guard AND the {@code ShardMap.epoch()} source
 * ({@code topologyEpoch}); v1 initialises the epoch to 1 and never bumps (static N).
 *
 * <pre>
 *   [formatVersion:u16 = 1][shardCount:u32][topologyEpoch:u64][reserved:u32 = 0]
 * </pre>
 */
public record TopologyDescriptor(int shardCount, long topologyEpoch) {

    public byte[] encodePayload() {
        if (topologyEpoch == TOPO_EPOCH_RESERVED) {
            throw new IntegrityException(IntegrityException.Reason.MALFORMED,
                    "topologyEpoch 0 is reserved (pre-epoch)");
        }
        ByteBuffer buf = ByteBuffer.allocate(TOPO_PAYLOAD_SIZE);
        buf.putShort(TOPO_FORMAT_VERSION);
        buf.putInt(shardCount);
        buf.putLong(topologyEpoch);
        buf.putInt(0); // reserved MBZ
        return buf.array();
    }

    public static TopologyDescriptor decodePayload(byte[] payload) {
        if (payload.length != TOPO_PAYLOAD_SIZE) {
            throw new IntegrityException(IntegrityException.Reason.MALFORMED,
                    "topology payload must be " + TOPO_PAYLOAD_SIZE + " B, was " + payload.length);
        }
        ByteBuffer buf = ByteBuffer.wrap(payload);
        int fmt = buf.getShort() & 0xFFFF;
        if (fmt == VERSION_ILLEGAL || fmt != TOPO_FORMAT_VERSION) {
            throw new IntegrityException(IntegrityException.Reason.VERSION,
                    "unsupported topology formatVersion " + fmt);
        }
        int shardCount = buf.getInt();
        long epoch = buf.getLong();
        int reserved = buf.getInt();
        if (reserved != 0) {
            throw new IntegrityException(IntegrityException.Reason.RESERVED,
                    "topology reserved must be zero, was " + reserved);
        }
        if (epoch == TOPO_EPOCH_RESERVED) {
            throw new IntegrityException(IntegrityException.Reason.MALFORMED,
                    "topologyEpoch 0 is reserved (pre-epoch) -- fail closed");
        }
        return new TopologyDescriptor(shardCount, epoch);
    }

    /** Wraps in the authenticated envelope (magic + CRC + MAC) so the deploy guard is tamper-evident. */
    public byte[] seal(EnvelopeV3 env) {
        return env.wrap(TOPO_MAGIC, NODE_SCOPE, encodePayload());
    }

    public static TopologyDescriptor openSealed(EnvelopeV3 env, byte[] enveloped) {
        return decodePayload(env.unwrap(TOPO_MAGIC, NODE_SCOPE, enveloped));
    }
}
```

### WatchCursorV2.java

```java
package io.configd.frozen;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The frozen, epoch-bound watch cursor (version-markers §2.9). Prepends {@code topologyEpoch} to
 * the as-built per-shard vector so a resume token is invalidated by a redeploy at a different N
 * (etcd {@code ErrCompacted} model). The vector stays strictly-ascending by UNSIGNED gid.
 *
 * <pre>
 *   [topologyEpoch:u64][count:u32]([gid:u32][S:u64])*count
 * </pre>
 *
 * Reserved: {@code topologyEpoch == 0} is FRAME_CORRUPT (reserved-illegal, §0.2). A cursor whose
 * epoch != current is refused with STALE_TOPOLOGY -> the client re-hydrates from scratch. The
 * cursor itself carries no version byte: it is carrier-versioned by the enclosing edge frame.
 */
public record WatchCursorV2(long topologyEpoch, List<Component> components) {

    public record Component(int gid, long s) {
        public Component {
            if (s < 0) {
                throw new IntegrityException(IntegrityException.Reason.MALFORMED, "cursor S must be >= 0");
            }
        }
    }

    public WatchCursorV2 {
        Objects.requireNonNull(components, "components");
        components = List.copyOf(components);
    }

    public byte[] encode() {
        if (topologyEpoch == 0) {
            throw new IntegrityException(IntegrityException.Reason.MALFORMED,
                    "topologyEpoch 0 is reserved (frame-corrupt)");
        }
        checkAscending(components);
        ByteBuffer buf = ByteBuffer.allocate(Long.BYTES + Integer.BYTES + components.size() * (Integer.BYTES + Long.BYTES));
        buf.putLong(topologyEpoch);
        buf.putInt(components.size());
        for (Component c : components) {
            buf.putInt(c.gid());
            buf.putLong(c.s());
        }
        return buf.array();
    }

    /**
     * Decodes and validates against {@code currentEpoch}. epoch 0 -> FRAME_CORRUPT; epoch !=
     * current -> STALE_TOPOLOGY (full re-hydrate); non-ascending/dup gid -> FRAME_CORRUPT.
     */
    public static WatchCursorV2 decode(byte[] bytes, long currentEpoch) {
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        long epoch = buf.getLong();
        if (epoch == 0) {
            throw new IntegrityException(IntegrityException.Reason.MALFORMED,
                    "topologyEpoch 0 is reserved (frame-corrupt)");
        }
        if (epoch != currentEpoch) {
            throw new IntegrityException(IntegrityException.Reason.STALE_TOPOLOGY,
                    "cursor topologyEpoch " + epoch + " != current " + currentEpoch + " -> re-hydrate from scratch");
        }
        int count = buf.getInt();
        if (count < 0 || (long) count * (Integer.BYTES + Long.BYTES) > buf.remaining()) {
            throw new IntegrityException(IntegrityException.Reason.MALFORMED, "bad cursor count " + count);
        }
        List<Component> components = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            components.add(new Component(buf.getInt(), buf.getLong()));
        }
        checkAscending(components);
        return new WatchCursorV2(epoch, components);
    }

    private static void checkAscending(List<Component> components) {
        for (int i = 1; i < components.size(); i++) {
            if (Integer.compareUnsigned(components.get(i).gid(), components.get(i - 1).gid()) <= 0) {
                throw new IntegrityException(IntegrityException.Reason.MALFORMED,
                        "cursor gids must be strictly ascending (unsigned); dup/out-of-order at " + i);
            }
        }
    }
}
```

### PolicyPragma.java

```java
package io.configd.frozen;

/**
 * The {@code _acl/} policy-value self-versioning pragma (freeze addition: the as-built policy value
 * grammar carries NO version marker). Line 1 MUST be the shebang {@code #!configd-acl v<N>}.
 *
 * <p><b>The {@code #} ambiguity, resolved (DESIGN-RESOLUTION -- not spelled out byte-level in the
 * source docs; flagged for designer confirmation):</b> the as-built {@code PolicySerializer} treats
 * every {@code #}-prefixed line as a comment. The pragma also begins with {@code #}. Resolution is
 * the standard shebang rule: {@code #!} is the pragma ONLY as the first two characters of line 1.
 * <ul>
 *   <li>Line 1 begins with {@code #!} -> parse as the pragma; {@code configd-acl} + a supported
 *       version required, else reject.</li>
 *   <li>Line 1 does not begin with {@code #!} (a plain {@code #} comment, a policy line, or blank)
 *       -> NO pragma present -> reject (absence).</li>
 *   <li>{@code #!...} on any later line -> an ordinary comment (shebang is line-1-only).</li>
 * </ul>
 */
public final class PolicyPragma {

    private static final String PREFIX = "#!configd-acl";
    public static final int SUPPORTED_VERSION = 1;

    private PolicyPragma() {}

    /** Returns the pragma version, or throws {@link IntegrityException} on absence/unknown version. */
    public static int parse(String content) {
        if (content == null || content.isEmpty()) {
            throw new IntegrityException(IntegrityException.Reason.MALFORMED, "empty policy document (no pragma)");
        }
        int nl = content.indexOf('\n');
        String line1 = (nl < 0 ? content : content.substring(0, nl));
        if (line1.endsWith("\r")) {
            line1 = line1.substring(0, line1.length() - 1);
        }
        if (!line1.startsWith("#!")) {
            // A plain '#' comment or any non-shebang line 1 => the pragma is absent. Fail closed.
            throw new IntegrityException(IntegrityException.Reason.MALFORMED,
                    "policy document missing line-1 pragma '#!configd-acl v" + SUPPORTED_VERSION + "'");
        }
        // Split the shebang on whitespace: "#!configd-acl" "v<N>".
        String[] parts = line1.strip().split("\\s+");
        if (parts.length != 2 || !parts[0].equals(PREFIX)) {
            throw new IntegrityException(IntegrityException.Reason.MALFORMED,
                    "malformed policy pragma: '" + line1 + "'");
        }
        String token = parts[1];
        if (token.length() < 2 || token.charAt(0) != 'v') {
            throw new IntegrityException(IntegrityException.Reason.MALFORMED,
                    "malformed policy pragma version token: '" + token + "'");
        }
        int version;
        try {
            version = Integer.parseInt(token.substring(1));
        } catch (NumberFormatException e) {
            throw new IntegrityException(IntegrityException.Reason.MALFORMED,
                    "non-numeric policy pragma version: '" + token + "'");
        }
        if (version == FrozenFormats.VERSION_ILLEGAL) {
            throw new IntegrityException(IntegrityException.Reason.VERSION, "policy pragma version 0 is illegal");
        }
        if (version != SUPPORTED_VERSION) {
            throw new IntegrityException(IntegrityException.Reason.VERSION,
                    "unsupported policy pragma version " + version + " (this reader is v" + SUPPORTED_VERSION + ")");
        }
        return version;
    }
}
```

### Hkdf.java

```java
package io.configd.frozen;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/**
 * RFC 5869 HKDF-SHA-256 (extract-then-expand). Dependency-free stand-in for the production
 * {@code Hkdf} helper, so this sketch compiles against the plain JDK. Models the key hierarchy of
 * anchor-rotation-design §A2.3 (signing key -> KEK/MAC keys; keyring root -> K_integrity/DEK).
 */
final class Hkdf {

    private static final String HMAC = "HmacSHA256";
    private static final int HASH_LEN = 32;

    private Hkdf() {}

    static byte[] deriveKey(byte[] ikm, byte[] salt, String info, int outLen) {
        return expand(extract(salt, ikm), info.getBytes(StandardCharsets.UTF_8), outLen);
    }

    private static byte[] extract(byte[] salt, byte[] ikm) {
        try {
            byte[] effectiveSalt = (salt == null || salt.length == 0) ? new byte[HASH_LEN] : salt;
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(effectiveSalt, HMAC));
            return mac.doFinal(ikm);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HKDF extract failed", e);
        }
    }

    private static byte[] expand(byte[] prk, byte[] info, int outLen) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(prk, HMAC));
            ByteArrayOutputStream out = new ByteArrayOutputStream(outLen);
            byte[] t = new byte[0];
            int counter = 1;
            while (out.size() < outLen) {
                mac.update(t);
                mac.update(info);
                mac.update((byte) counter);
                t = mac.doFinal();
                out.write(t, 0, Math.min(t.length, outLen - out.size()));
                counter++;
            }
            return Arrays.copyOf(out.toByteArray(), outLen);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HKDF expand failed", e);
        }
    }
}
```

(The 537-line `SelfTest.java` driver is omitted for length; its 75 cases are enumerated in the §7.1 log. The full sketch tree ran from the session scratchpad.)

---

# §8 Prior-art report (reference-researcher record)

# Prior Art: How Bar-Setting Systems Freeze, Version, Anchor, and Rotate On-Disk/Wire Formats

Research deliverable for the Configd frozen-format design session. Configd is a
Raft-based config store (Java) with AES-256-GCM encryption-at-rest at an
`IntegrityEnvelope` seam, node-local key material. Every claim below names the
system, the mechanism, and cites a primary source (URL or source-file path).
Where the bar systems disagree, that is called out with a recommendation for a
node-local-key config store.

---

## Framing: the threat-model boundary that governs everything below

The single most important distinction the bar systems draw, and the one Configd
must state explicitly, is:

- **Crash-consistency** (torn tails, partial writes, bit-rot) — a *non-adversarial*
  fault. Every system here handles it with CRCs, magic numbers, dual meta pages,
  and fsync ordering.
- **Adversarial tampering with filesystem write access** — an attacker who can
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
can forge anything — so that is explicitly out of scope, and must be stated as
such. (dm-verity: "This hash should be trusted as there is no other authenticity
beyond this point" — the root hash is supplied out-of-band via a verified boot
chain, kernel command line, or signature, never trusted from the protected device.
https://docs.kernel.org/admin-guide/device-mapper/verity.html)

---

## Q1. Tail-truncation / rollback detection against a disk-write adversary

### 1a. Postgres WAL — what each mechanism actually detects (and what it can't)

Source files: `src/include/access/xlog_internal.h`, `src/include/access/xlogrecord.h`,
`src/include/catalog/pg_control.h` (PostgreSQL master).

- **`XLOG_PAGE_MAGIC` = `0xD120`** — a 16-bit page-header magic that doubles as a
  WAL format-version indicator (the comment literally says "can be used as WAL
  version indicator"). Detects: wrong/foreign/older-format pages, and gross
  garbage. Does NOT detect: a validly-formatted shorter log.
- **Per-record CRC (`xl_crc`, a `pg_crc32c`)** — covers the record header and
  payload except the CRC field itself (`SizeOfXLogRecord = offsetof(XLogRecord,
  xl_crc) + sizeof(pg_crc32c)`). Detects: bit-flips and torn/partial records.
  Recovery recomputes the CRC and **halts at the first record whose CRC fails**,
  treating everything after as non-existent.
- **`xl_prev` back-chain** — every `XLogRecord` carries "ptr to previous record in
  log." On replay Postgres checks that the record it reads back-links to the record
  it just processed; a mismatch means it followed a stale/garbage pointer and stops.
  Detects: mis-sequenced or spliced records. Does NOT detect: truncation.
- **`pg_control` checkpoint/redo pointers** — `ControlFileData` holds
  `checkPoint` (last checkpoint record LSN) and `checkPointCopy.redo` (where REDO
  must start). Recovery reads `pg_control` first, then scans WAL forward from the
  redo pointer. This is the anchor that says "start here."

**The key insight to pin down:** per-record integrity + prev-chaining can **never**
detect adversarial trailing truncation, because *a prefix of a valid WAL is itself
a valid WAL*. Every remaining record's CRC still verifies, every `xl_prev` still
links correctly; nothing internal to the log records "how many records there were
supposed to be." Postgres **accepts the shorter log by design** — crash recovery
*must* stop at the first invalid/torn record, because a crash and an adversarial
truncation-at-a-record-boundary are byte-for-byte indistinguishable from inside the
log. Detecting truncation therefore requires an **external high-water mark** that
lives somewhere the adversary can't rewrite consistently. Postgres never claims to
provide this; `pg_control` is a crash anchor, not an anti-rollback anchor (it is
CRC-protected against corruption, not MAC-protected against forgery).

### 1b. Certificate Transparency / verifiable logs — the authenticated high-water mark

Source: RFC 6962 (https://www.ietf.org/rfc/rfc6962.txt).

- A **Signed Tree Head (STH)** commits to, and signs over: `tree_size` (number of
  entries), `timestamp`, `sha256_root_hash` (Merkle root over all entries), and
  `version`. The signature (`TreeHeadSignature`) is what makes it non-repudiable.
- This is exactly the shape of an authenticated high-water mark: it binds **size/
  index AND a hash over the entire head state**, under a signature the log operator
  cannot deny. A truncated or rolled-back log must present an STH with a smaller
  `tree_size` or a different root for a size it previously signed — which is
  cryptographic proof of misbehavior.
- Detection is via **Merkle consistency proofs** between two STHs (proving the
  earlier tree is a prefix of the later) plus **gossip** — "Violation of the
  append-only property is detected by global gossiping ... comparing their versions
  of the latest Signed Tree Heads." The anchor's power comes from being witnessed
  *off the log's own storage*.
- **Crosby–Wallach, "Efficient Data Structures for Tamper-Evident Logging" (USENIX
  Security 2009)** generalizes this: a *history tree* lets a logger produce a
  commitment (a signed root over the first *n* events) such that it cannot later
  present a different event *i < n* or a shorter/altered history without detection.
  The lesson for Configd: an authenticated head must commit to **(count/index +
  hash-or-MAC over the head)**, and detection of rollback ultimately depends on
  that commitment being compared against a copy the adversary cannot rewrite.

### 1c. Anti-rollback in practice — what is realistic WITHOUT trusted hardware

- **TPM monotonic counters** (TCG TPM 2.0 NV counters): hardware counters that can
  only increase and survive reboots. Anchoring a log's head index to a TPM counter
  makes rollback detectable because replaying an old head presents a counter value
  below the hardware's current value. Requires trusted hardware.
- **Android Verified Boot rollback protection**
  (https://source.android.com/docs/security/features/verifiedboot/verified-boot):
  "Rollback protection is typically implemented by using **tamper-evident storage**
  to record the most recent version ... and refusing to boot ... if it's lower than
  the recorded version." The rollback index lives in RPMB / TrustZone — *separate
  from the partition it protects* — precisely because "If an attacker could rewrite
  this stored index value, they could make the system accept arbitrarily old
  versions, defeating the entire protection mechanism." This is the general shape
  of every honest anti-rollback design: **the monotonic marker must live where the
  adversary cannot rewrite it.**
- **fs-verity** — read-only Merkle-tree integrity for individual files; the root
  hash is the anchor and, like dm-verity, must be signed / supplied out of band.
  Protects against modification of a file's *contents*, not against replacing the
  whole file+signature with an older signed version (that is left to the caller's
  key/signature policy).
- **dm-integrity** (https://docs.kernel.org/admin-guide/device-mapper/dm-integrity.html):
  per-sector integrity + journaling for **write atomicity** ("either both data and
  tag or none of them are written"). When combined with dm-crypt it gives
  *authenticated* encryption so "if the attacker modifies the encrypted device, an
  I/O error is returned." But this is **live per-sector tamper detection, not
  whole-device rollback/replay protection** — reverting the entire device (data +
  tags together) to an earlier consistent snapshot is out of scope by design.
- **dm-verity** (URL above): read-only device integrity where the **root hash is
  the trust anchor and must be supplied externally** ("trusted as there is no other
  authenticity beyond this point"), because storing it on the protected device
  "would create a circular dependency."
- **Vault**: encrypts every storage entry with AES-256-GCM so tampering is detected
  by the GCM tag on read, but Vault's threat model **does not claim protection
  against an attacker who rolls the storage backend back to an earlier consistent
  state** — the GCM tag over an old-but-valid ciphertext still verifies. This is the
  same ceiling.

**The crisp boundary for Configd (state it verbatim in the design):** *With a
node-local key, the design goal is to detect truncation/rollback by an adversary
who has disk write access but NOT the key material — via an authenticated head that
MACs (count/index + hash-over-head). Rollback of that authenticated head to a prior
legitimately-MAC'd state cannot be detected without external monotonic storage
(TPM/RPMB) or a remote witness, and an adversary holding BOTH the disk and the key
can forge anything. dm-verity, dm-integrity, Android Verified Boot, and Vault all
draw the line in exactly this place.*

### 1d. bbolt / LMDB dual meta pages, SQLite change counter — the head-anchor pattern for crashes

- **bbolt** (https://github.com/etcd-io/bbolt): two meta pages, each carrying a
  monotonically increasing **txid** and a **checksum**. Commit = (1) write+fsync
  dirty pages, (2) write+fsync a new meta page with `txid+1`. On open, bbolt picks
  **the meta page with the highest *valid* (checksum-passing) txid**; a torn meta
  page fails its checksum and is ignored, so the previous meta page (lower txid)
  wins. "Partially written data pages are ignored ... the meta page pointing to them
  is never written." The highest-valid-txid meta page *is* the head anchor.
- **LMDB**: identical dual-meta-page design — two meta pages alternating by
  transaction id; the reader/opener trusts the one with the greater txid that
  validates. Commit writes the data pages, fsyncs, then writes one meta page and
  fsyncs; a crash mid-commit leaves the old meta page authoritative (MVCC, no undo
  log). (LMDB design: Howard Chu, "MDB: A Memory-Mapped Database", and
  http://www.lmdb.tech/doc/.)
- **SQLite** (https://www.sqlite.org/fileformat2.html): the **file change counter**
  (offset 24, 4-byte big-endian) is incremented whenever the DB is unlocked after
  modification; readers compare it to detect a stale page cache. The
  **version-valid-for** number (offset 92) stores the change-counter value that was
  current when the in-header DB size (offset 28) was last written — the size is only
  trusted when counter == version-valid-for, which detects a legacy writer that
  changed the file without updating the size.

**Pattern to extract for a small mutable state file:** dual A/B slots each with a
**sequence number + checksum**, pick the highest-valid-sequence slot. This makes
the state file crash-atomic *without* write-then-rename, and gives you a natural
place to hang a MAC when you need adversary-resistance (bbolt/LMDB checksums are
for corruption, not forgery — Configd would use a keyed MAC instead of a plain
checksum on each slot, and the sequence number becomes the anti-rollback index).
Note both crash-consistent systems accept the *older* slot on a torn newer write —
the same "accept the shorter/older state" behavior Postgres shows for the WAL tail.

---

## Q2. Fsync ordering: keeping the anchor never AHEAD of durable data

The universal invariant is **"the anchor must never reference data that isn't
durable yet."** The systems differ on whether they *also* guarantee the converse
(anchor is never behind), and mostly they **do not** — they let the anchor lag and
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

- Raft rule: **an entry is fsync'd to the WAL before it is committed/applied** — no
  data is acked to a client before it is durable in the WAL.
- On apply, etcd writes the applied data **and** the `consistent_index` (8-byte
  big-endian, plus term since v3.5) into the bolt backend's `meta` bucket **inside
  the same backend transaction** as the data (`UnsafeUpdateConsistentIndex` →
  `tx.UnsafePut(Meta, MetaConsistentIndexKeyName, ...)`; "tx has been locked in
  TxnBegin"). So the anchor and the data it covers commit atomically together.
- On restart etcd replays the WAL; entries whose index ≤ the stored
  `consistent_index` are already reflected in the backend and are re-applied
  idempotently / skipped. **etcd effectively gets BOTH invariants**: WAL-first fsync
  ⇒ data never acked before durable; consistent-index-in-same-txn ⇒ the backend
  anchor is never ahead of applied data (they're one commit).

### The dual-meta / doublewrite pattern for a crash-atomic state file

- **bbolt/LMDB dual meta page** (Q1d): the meta page (anchor) is written *after* and
  fsync'd *separately from* the data pages, so a crash between the two leaves the
  previous meta authoritative — anchor never ahead of data.
- **write-new-then-rename** (POSIX): write `state.tmp`, fsync it, fsync the
  directory, `rename()` over `state` — atomic pointer swap. Simpler but rename
  durability requires the directory fsync, and there is exactly one live copy.
- **dual-slot A/B with sequence numbers**: keeps *two* valid copies so you always
  have a fallback if the newest is torn, and the sequence number is a ready-made
  monotonic index. This is the better fit when the state file also has to be an
  anti-rollback anchor (Configd), because you can MAC each slot and reject a slot
  whose sequence number is below the last one you accepted.
- **InnoDB doublewrite buffer**: writes each page to a doublewrite area first, then
  to its final location, so a torn final write can be recovered from the intact
  doublewrite copy — same "write durable copy before overwriting the authoritative
  one" idea at page granularity.

**Recommendation for Configd:** adopt Postgres/etcd ordering — *flush the log/data
first, write the anchor last, fsync between*; never ack a write before the anchor
covering it is durable; and prefer the **dual-slot-with-sequence-number** state
file over rename, because the sequence number is your anti-rollback index and the
second slot is your torn-write fallback.

---

## Q3. Key rotation that never destroys data

The bar pattern is **versioned key terms, append-only, decrypt with the term the
ciphertext names, encrypt new writes with the newest term, retain old terms
forever.** Rotation NEVER re-encrypts in place; rewrap is a separate optional op.

### Vault barrier keyring — exactly

`vault/keyring.go` + `vault/barrier_aes_gcm.go`.

- **`Key` struct**: `Term` (uint32, sequential version id), `Version` (schema),
  `Value` ([]byte, the actual AES key), `InstallTime`, `Encryptions` (usage
  counter). The **`Keyring`** holds `keys map[uint32]*Key` indexed by term and an
  `activeTerm uint32`.
- **Ciphertext prefix carries the term** — barrier layout:
  `[term: 4 bytes big-endian][version: 1 byte][nonce: 12 bytes][GCM ciphertext+tag]`.
  Decrypt reads the first 4 bytes → term → looks up that term's AEAD in the keyring;
  reads byte 4 → AES-GCM version (`AESGCMVersion1 = 0x1` no AAD; `AESGCMVersion2 =
  0x2` uses the storage path as AAD).
- **Rotate = `AddKey`**: installs a new `Key` at `term+1` and advances `activeTerm`
  if higher. "Old keys remain stored in the map, enabling decryption of legacy
  data. The system only encrypts with the active term but preserves historical keys
  for backward compatibility." New writes use the newest term; **old terms are never
  deleted** so old ciphertext stays readable — rotation is *append a term*, not
  *re-encrypt*.
- **The keyring itself is encrypted with the root key and persisted**: serialized
  via `EncodedKeyring { MasterKey []byte; Keys []*Key; ... }` (JSON), and the root
  key is obtained by unsealing (Shamir shares or auto-unseal KMS). Root key encrypts
  the keyring; unseal key encrypts the root key
  (https://developer.hashicorp.com/vault/docs/concepts/seal).
- **Unknown term at decrypt ⇒ fail closed**: if the term in the prefix isn't in the
  keyring, decryption cannot proceed (no key to select). GCM tag mismatch also fails
  closed.
- **Rewrap is separate**: `sys/rotate` appends a term; re-encrypting existing data to
  the new term is an explicit, optional, background operation — never coupled to
  rotation.

### Secondary examples (same two-tier master-key indirection)

- **MySQL InnoDB tablespace encryption**: two-tier — each tablespace has its own key,
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
  KEK rotation only rewraps DEKs — same indirection, chosen precisely to avoid
  rewriting encrypted heap/WAL.

**Recommendation for Configd:** this is a solved problem — copy the Vault term model
exactly at the `IntegrityEnvelope` seam. Ciphertext (or envelope) carries a
**key-term/version id**; a keyring maps term → key material; **new envelopes use the
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
- **Postgres WAL page**: `xlp_magic = XLOG_PAGE_MAGIC (0xD120)` — magic *is* the WAL
  format-version indicator, bumped on incompatible format changes. (`xlog_internal.h`.)
- **SQLite header** (https://www.sqlite.org/fileformat2.html): 16-byte magic
  `"SQLite format 3\0"` (offset 0); **file-format write version** (offset 18) and
  **read version** (offset 19) as *separate* bytes — a clean split enabling "I can
  read this but must treat it read-only" (write>2 ⇒ read-only; read>2 ⇒ can't open
  at all); `SQLITE_VERSION_NUMBER` (offset 96) records the library that last wrote.
  The read/write split is the notable idea: version compatibility is per-operation,
  not a single number.
- **Kafka log segment record batch** (KIP-32/KIP-98 message format v2): a 1-byte
  **`magic`** at a fixed header offset (v0=0, v1=1, v2=2) lets a broker parse any
  segment written by any historical version. Crucially, in v2 the layout is
  `baseOffset(8) | batchLength(4) | partitionLeaderEpoch(4) | magic(1) | crc(4) |
  attributes(2) | ...`, i.e. **the magic byte sits BEFORE the CRC and is NOT covered
  by it** — deliberately, so a reader can determine the format version *before* it
  knows how to compute/verify the CRC. (v0/v1 put magic *after* the crc; v2 moved it
  ahead precisely to fix this.) Reserved attribute bits are defined-as-zero for
  forward compat. (https://cwiki.apache.org/confluence/display/KAFKA/KIP-98,
  https://kafka.apache.org/documentation/#recordbatch)
- **etcd WAL**: each WAL file opens with a **metadata record** and version handling;
  snap files carry their own headers. Records are CRC-chained. (etcd `wal` package.)

**Where the version lives:** leading `magic (fixed bytes) || version (small int)`,
positioned so it is readable **without** and **before** the integrity/crypto check
(Kafka's magic-before-CRC is the canonical lesson), with **reserved bits/values
defined-as-zero** and **unknown version ⇒ hard fail (fail loud), never best-effort
parse.**

### Text / operator-facing grammars (Configd has `_acl/` policy-as-config text)

- **Shebang-style / pragma first line**: the minimum viable version pragma for a
  frozen line-oriented grammar is a mandatory first line like
  `#!configd-acl v1` (or a `#version: 1` pragma). Parser reads line 1, rejects
  unknown versions, and only then interprets the body — the text analogue of a
  leading magic+version.
- **OpenSSH `authorized_keys`**: evolved by adding **named options** (`command=`,
  `restrict`, `cert-authority`, ...) with the rule that an unknown option makes the
  line **fail closed** (the key is rejected), not silently ignored — a discipline
  worth copying for a security-relevant grammar.
- **Prometheus exposition format**: versioned out-of-band via the HTTP
  `Content-Type` (`text/plain; version=0.0.4`) plus `# HELP`/`# TYPE` typed comment
  lines; the newer OpenMetrics carries an explicit `# EOF` terminator so a truncated
  scrape is detectable — a text-format truncation guard worth noting.

**Minimum viable pragma for Configd's frozen text grammar:** a required first line
`#!<grammar-name> v<N>`; unknown `N` ⇒ reject the whole file (fail loud); reserve a
comment convention; if the file is security-relevant and truncation matters, add an
explicit end-marker line so a truncated policy file is rejected rather than
partially applied.

---

## Q5. Cursor / resume-token versioning across topology change

The pattern to extract: **a resume token must bind the topology epoch it was minted
under, and the server rejects a mismatched epoch so the client re-hydrates full
state.** The bar systems achieve this in one of two ways — bind-to-shard (reject on
mismatch) or explicit-invalidation-signal (client re-syncs).

- **Kafka consumer offsets — invalidation is explicit, offsets are per-partition.**
  An offset is meaningful **only within a `(topic, partition)`**. Kafka partitions
  can only be *added*, never split/merged, and adding partitions **breaks key→
  partition hashing** for future records — so offsets are never "migrated"; the
  topology change is simply visible as new partitions the consumer must discover and
  start reading from a defined position. The token (offset) is inseparable from its
  partition (its topology unit).
- **Kinesis shard iterators — bound to a shard, parent-before-child lineage.**
  (https://docs.aws.amazon.com/streams/latest/dev/kinesis-using-sdk-java-resharding.html,
  .../after-resharding.html) Resharding is pairwise split/merge producing **child
  shards** with a **`ParentShardId`** lineage; parents go OPEN→CLOSED→EXPIRED. A
  shard iterator is valid only for one shard, and **the consumer must drain each
  parent shard to `SHARD_END` (next-iterator == null) BEFORE reading its children**,
  or it "could read data for a particular hash key out of the order given by the
  ... sequence numbers." An old iterator simply ends (returns null) at the parent's
  close; the client must re-discover children via `DescribeStream`. Sequence numbers
  are per-shard and don't carry across the split.
- **DynamoDB Streams shard iterators** — same shape: shard iterators **expire (15
  min)** and reshard produces new shards; a consumer must walk the parent→child
  shard tree and cannot reuse a parent iterator on a child.
- **etcd watch revision compaction — the cleanest "re-sync full state" contract.**
  (https://etcd.io/docs/v3.5/learning/api/) A watch resumes from a global
  `revision`; when history older than a point is **compacted**, a watch started at a
  compacted revision is **canceled with `ErrCompacted`** ("mvcc: required revision
  has been compacted") and the response carries `compact_revision`. The client's
  only correct recovery is to **re-fetch current state via a Range/Get and resume
  watching from the fresh header revision** — an explicit "your cursor is no longer
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
the server **reject a cursor whose epoch ≠ current epoch** with a distinct
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
- **JWE (RFC 7516)** — the general-purpose versioned wrapped-key envelope. Five
  compact components: `BASE64URL(Protected Header).EncryptedKey.IV.Ciphertext.Tag`.
  The **protected header names the algorithms**: `alg` = key-management/wrapping
  algorithm (e.g. `RSA-OAEP`, `A256KW`), `enc` = content-encryption AEAD. The header
  **is the AAD**: "the Additional Authenticated Data ... be ASCII(Encoded Protected
  Header)" — so the declared algorithms and any bound parameters are authenticated
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
  (https://docs.aws.amazon.com/kms/latest/developerguide/concepts.html) — key/value
  pairs authenticated with the ciphertext, so a blob decrypts only under the same
  context, the direct analogue of "AAD pins purpose/scope."
- **PKCS#8 `EncryptedPrivateKeyInfo` (RFC 5958)**: `SEQUENCE { encryptionAlgorithm
  AlgorithmIdentifier, encryptedData OCTET STRING }`. The `AlgorithmIdentifier`
  carries the wrapping/KDF alg **OID + parameters** (e.g. PBES2 salt + iteration
  count) — version/alg-id and its params travel *with* the wrapped bytes. General
  lesson: the descriptor is self-describing about how to unwrap.

**What the AAD should bind, for Configd:** the wrapped-DEK envelope's AAD must pin
the **key's purpose and slot/scope** — e.g. `(format-version, key-id/term,
wrap-alg-id, node/scope id, "purpose=dek")` — so a wrapped DEK lifted from one slot
cannot be replayed into another (JWE binds the header; KMS binds the encryption
context; age MACs the whole header — all three prevent exactly this
substitution/replay).

**Minimum frozen wrapped-DEK envelope fields (synthesis of the above):**
`magic || format_version || key_id(term) || wrap_alg_id || wrap_nonce/iv ||
wrapped_dek_bytes || auth_tag`, with the **AAD = (format_version, key_id,
wrap_alg_id, scope/purpose)** and **unknown format_version or wrap_alg_id ⇒ fail
closed.**

---

## Where the bar systems DISAGREE (and what fits a node-local-key config store)

1. **Anchor integrity: CRC vs MAC vs external.** Postgres/bbolt/LMDB/SQLite protect
   their head anchor with a **CRC/checksum** — corruption detection only, forgeable.
   CT/age/Vault use a **signature/MAC** — forgery-resistant but only against an
   adversary lacking the key. AVB/dm-verity/TPM put the anchor in **external tamper-
   evident storage** — the only defense against anchor rollback itself.
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
   (offset ⟂ partition); etcd signals `ErrCompacted`. *For Configd:* **bind the
   epoch into the cursor and signal an explicit re-hydrate error** (etcd model) —
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
- [ ] **Unknown version ⇒ fail loud / fail closed.** Never best-effort parse.
- [ ] Consider a SQLite-style **read-version vs write-version split** if forward-
      compatible read of newer files matters.

**Append-only log — truncation/rollback**
- [ ] Per-record CRC + prev-chain for **corruption/torn tails** (Postgres model) —
      but document that these **cannot** detect adversarial truncation (a valid
      prefix is a valid log).
- [ ] An **authenticated head anchor** that MACs **(record count/index + hash-over-
      head)** under the node key (CT STH shape), stored in a dual A/B slot with a
      **monotonic sequence number**; reject a slot whose sequence < last accepted.

**Small mutable state / anchor file**
- [ ] **Dual-slot A/B, each with sequence-number + keyed MAC**; open = highest-valid-
      sequence slot (bbolt/LMDB pattern, MAC instead of plain checksum).
- [ ] Keep it ≤ one sector for atomic write where possible (Postgres pg_control).

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
- [ ] **Unknown term at decrypt ⇒ fail closed.**

**Wrapped-DEK / key-descriptor envelope**
- [ ] Fields: `magic || format_version || key_id(term) || wrap_alg_id ||
      wrap_nonce/iv || wrapped_dek || auth_tag`.
- [ ] **AAD binds (format_version, key_id, wrap_alg_id, scope/purpose)** so a wrapped
      key can't be replayed into another slot (JWE header-as-AAD / KMS encryption-
      context / age header-MAC).
- [ ] Unknown `format_version`/`wrap_alg_id` ⇒ fail closed.

**Cursors / resume tokens (multi-shard watches)**
- [ ] Every cursor **binds the topology/shard-map epoch** + per-shard position.
- [ ] Server **rejects epoch mismatch with an explicit re-hydrate error** (etcd
      `ErrCompacted` model); client re-syncs full state. Never silently reinterpret
      a stale cursor.

**Operator-facing text grammars (`_acl/` policy-as-config)**
- [ ] Mandatory first-line pragma `#!<grammar> v<N>`; unknown N ⇒ reject whole file.
- [ ] Unknown option/directive ⇒ **fail closed** (ssh authorized_keys discipline),
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

# Part III-b — §9 Consolidated build-arc obligations

The design is rigorous enough that the build is mechanical. Consolidated from §4/§5/§6 (each row
cites its owner section). NO code changes in this session — this is the arc that follows
ratification.

## 9.1 Production seams to modify

| Seam | Change | Source |
|---|---|---|
| `IntegrityEnvelope` | v3 layout (scopeId all postures; keyTerm in HMAC; AAD 44 B), CRC-before-version, `reserved!=0` throw, keyTerm=0 domain rule | §2.2 |
| `SegmentKeyManager` / `ConfigdServer` boot | keyring load (`unsealFrom` full term map + activeTerm); delete hardcoded term=1 (`ConfigdServer:1325`); rotate call path (admin op) | §2.18, §4 A2 |
| NEW `AnchorFile` writer + `KeyringCodec` + `TopologyDescriptor` codec | dual-slot pwrite/fdatasync writer; container headers; §2.4–2.7 layouts | §2.3–2.7, §7 sketch |
| `RaftLog` | delete legacy raw-record fallback; recovery contiguity/term-monotonicity/snapshot-join checks; conflict-truncation anchor-lower-first; compaction anchor-last; remove bare snapshot-meta | §2.8, §6 §1 |
| **scopeId assert — enumerated read call-sites** (the whole cross-shard-splice defense; a blanket "every path" is where a build slips one) | assert `scopeId==expected` at: WAL replay (`RaftLog` ctor); anchor/node-anchor/keyring/topology open; snapshot persist→reload (`ConfigStateMachine`); InstallSnapshot re-persist→reload (`RaftNode`). Add a negative test per site. | §2.2 |
| `DurableRaftState` | REMOVED — term/vote through the anchor (persist-before-memory preserved) | §2.4, §6 §1 |
| `RaftNode.flushDurable` / `appendEntries` | anchor write+fdatasync joins the barrier (leader before durableIndex advance; follower before ACK) | §6 §1 |
| fsync-failure policy | WAL/anchor fsync throw ⇒ no advance, no ack, process exit; wire `FaultInjectingStorage.failNextSyncs` live-RaftNode cell | §6 §4.1 |
| `FileStorage` | CRC32C frames; WAL container header; same-device anchor enforcement | §2.3, §2.8, §6 §4.4 |
| `ConfigStateMachine` | delete legacy trailer forms (a)/(c) | §2.9 |
| `AuditLog` | record header (RAUD v1) inside the chain input; periodic node-anchor head advance (K=64/1 s) | §2.10, §6 §1 |
| `PolicySerializer` / `AclConfigPolicyLoader` | line-1 pragma parse + validateAclWrite reject | §2.12 |
| `EdgeFrameCodec` / watch plane | cursor+SUBSCRIBE epoch fields; `STALE_TOPOLOGY=12`; `ShardMap.epoch()` from the descriptor | §2.11 |
| `FrameCodec` | epoch reject-if-nonzero | §2.15 |
| `SigningKeyStore` | durable write (temp+fsync+rename+dir), docstring hex typo, writeForTest chmod no-op | §2.14 |

## 9.2 Test obligations (new/rewritten; names from the lane records)

- **Envelope v3**: `rolledFormatVersionThrows` (kept), `reservedNonZeroThrows`,
  `corruptHeaderReportsCrcNotVersion`, `keyTermZeroOnlyUnderKeyringMagic`, scope-assert
  (cross-shard replay refused; in-place scopeId forge fails MAC/tag), downgrade rows (kept).
- **Anchor**: torn-slot fallback; both-slots-invalid REFUSE vs FRESH; `W<A` REFUSE; accept-forward;
  Step-2.5 term-witness REFUSE; anchor-lower-before-truncate (legal truncation non-refusing);
  contiguity/term-monotonicity/snapshot-join REFUSE cells; `forgedVotedForRefused` (moved from
  DurableRaftState); break-glass rebuild audit record.
- **Crash matrix**: §6 §2's interleavings (leader flush ×5, follower ×4, compaction ×3) as
  seed×crashpoint cells extending `SnapshotCrashRecoveryTest`/`AdversarialCrashRecoveryTest`;
  `GroupCommitDurabilityTest` + anchor-durable-before-commit assert; `VotePersistenceCrashTest`
  rewritten to the anchor; kill-9 matrix rerun.
- **fsync-failure**: live-RaftNode `failNextSyncs` on WAL and anchor seams ⇒ no-advance/no-ack/exit
  (closes assessment 2.1-6).
- **Rotation**: write@N → rotate → write@N+1 → restart → both decrypt; crash-mid-rewrap (both
  sides of the signing-key swap); keyring absent-with-data REFUSE; unknown-term REFUSE; keyring
  entry strip/swap/truncate REFUSE; wrapped-root cross-slot replay fails AAD; rotate-overflow
  REFUSE (slot capacity).
- **Versioning**: per §5 §4's table — `walFileHeaderBadMagic/HigherVersion`, `emptyWalFileIsFresh`,
  `nonEnvelopedWalRecordRejectedUnderKey`, `snapshotTrailerLegacyEmptyRejected`/`Raw8Rejected`/
  `UnknownTailTolerated`, `auditRecordBadMagicRejected`, `auditVersionIsChainBound`,
  `topologyDescriptorTamperedRefusesStart`, `topologyEpochZeroRejected`, `reshardNChangeStillRefused`,
  `nonZeroReservedEpochRejected`, `staleEpochCursorRejectedWithReHydrate`, `epochZeroCursorIsFrameCorrupt`,
  `subscribeCarriesEpoch`, resharding negative (N=a cursor at N=b), pragma cells (§7 self-test list).
- **Node anchor**: topology cross-check REFUSE (epoch rollback, N mismatch); audit-head REFUSE below
  anchored head; R-e window documented in the runbook.
- **Kept byte-identical**: FileStorage torn-tail semantics; `WalRecordIntegrityTest` torn-tail row;
  ENOSPC rows (2.1-5) + new boot-time anchor-preallocation ENOSPC cell.

## 9.3 Golden fixtures

Regenerate (edge wire epoch fields): `EdgeFrameGoldenBytes.java`, `EdgeFrameGoldenBytesGenerator.java`,
`EdgeFrameCodecGoldenFixtureTest.java`, `EdgeFrameCodecV2GoldenFixtureTest.java`,
`EdgeFrameCodecV3GoldenFixtureTest.java`. NEW goldens: envelope v3 (all three postures), anchor
slot, keyring slot, topology descriptor, audit record. Raft-wire goldens (`GoldenFixtures.java`,
`WireCompatGoldenBytesTest.java`) unchanged (epoch stays 0; only a reject test is added).
`RaftArtifactMagic` gains RANC/RNAN/RKYR/RTOP/RAUD/RWLF; RFST marked retired-reserved.

## 9.4 Measurement + docs obligations

- **EC2 re-measure of the single-group knee under anchor-before-ack** (bound: −10–40 %, likely
  −10–20 %; lever: group-commit linger/batch). Ship-gate: measured regression ≤ ~25 % or an
  explicit operator acceptance.
- RFC/operator docs: rotation runsheet (both operations, step-by-step, with the crash-safety
  argument); break-glass procedure; R-a..R-e residuals in the threat-model doc; the runbook rows
  for REFUSE diagnostics (each gate's message names the artifact and the recovery options);
  deployment doc: same-device rule, ext4/xfs preallocation caveat, audit K tunable.
- Update `docs/readiness/production-standard-gap-assessment-2026-07-03.md` rows A1–A6 → closed-by-
  design (build pending); reconcile `docs/v2-backlog.md:7` (the offset-in-AAD overclaim this design
  corrects) and `known-limitations.md` trailing-truncation bullet.

---

# Part III-c — §10 Definition-of-Done mapping + ratification

## 10.1 DoD mapping (charter §4)

| DoD item | Where |
|---|---|
| Complete permanent byte layouts (WAL/snapshot/wire/envelope/policy/cursor/wrapped-key) with anchor, per-record position, version marker | §2 (normative), §4/§5 (records), §7 (compile-checked) |
| Truncation/rollback/reorder/splice completeness proof | §4 §3 (24 attack + 5 false-positive rows), §2.17 gates, §6 §2 crash matrix |
| Key-rotation lifecycle + non-destruction proof | §2.18, §4 A2 (walkthroughs 1–5) |
| Version-marker scheme, all formats enumerated, fail-closed | §2.1–2.16, §5 §3 (no blank rows), §5 §4 |
| Persist-before-ack ordering, no durability-kernel regression | §6 (placement, crash matrix, §6 §6 no-regression table) |
| Compile-checked sketch | §7 (12 files, javac --release 25 clean, self-test 75/75, log embedded) |
| Prior art cited | §8 (full report); every §4/§5/§6 mechanism cites it |
| Design doc written; read-only otherwise | this file only; zero production-code changes |
| Surfaced for operator ratification | §10.2 |

## 10.2 Ratification checklist (the operator's decisions)

Approving this design freezes the formats forever. The specific decisions being ratified:

1. **⟦SEC-MERGE⟧** Merge `raft.persistent_state` into the per-shard anchor (recommended; the
   fallback — a separate state file with its own monotonic seq — re-invents a second anchor).
2. **Envelope v3** (scopeId + keyTerm; byte-incompatible with the built-but-unshipped v2 — clean
   break).
3. **Anchor-before-ack** with its honest cost (one extra ordered fdatasync per batch; −10–40 %
   knee bound, likely −10–20 %, EC2 re-measure obligated). The anchor-lag alternative is rejected
   as unsound for this threat model.
4. **Independent-random-root keyring** (the signing key becomes wrap/MAC-only; local provider
   gains real term rotation; the documented data-destroying procedure becomes impossible).
5. **STALE_TOPOLOGY(12)** ErrorCode extension + uniform epoch-on-every-resume rule (edge golden
   regeneration accepted).
6. **CRC32C unification** (FileStorage frames leave zlib CRC32).
7. **Clean-break deletions**: WAL legacy raw-record fallback; snapshot trailer legacy forms;
   raft-wire epoch decode-but-ignore → reject-if-nonzero.
8. **fsync-throw ⇒ process exit** policy (both WAL and anchor seams).
9. **Residuals accepted as documented**: R-a (narrowed by the term-witness gate), R-b, R-c, R-d,
   R-e (audit tail ≤64 records/≤1 s; K tunable). See items 11–12 for the two residuals that carry
   a real severity the ratifier must see, not a routine acceptance.
10. **Freeze constants**: keyring slot stride 64 KiB (≈900-term ceiling, rotate refuses loud on
    overflow); anchor slot stride 512 B; magic/version registry §2.1.
11. **R-a′ within-term `votedFor` rollback = Election Safety (NOT staleness).** Merging the vote
    into the anchor (⟦SEC-MERGE⟧) makes a within-term anchor rollback able to reset `votedFor` and
    cause a double-vote → cluster divergence. The term-witness gate does NOT catch it (term
    unchanged; votes aren't WAL-witnessed). It is locally undetectable without the external
    `AnchorWitness` (not built in v1). **Decision:** accept R-a′ documented for v1 (the recommended
    default — it is the same locally-undetectable class Raft's on-disk vote already lives in, and
    the merge does not make it *worse* than a separate rolled-back state file would), OR gate v1 on
    building `AnchorWitness`. If accepted, the ⟦SEC-MERGE⟧ ratification (item 1) is made with this
    severity in view — the doc no longer labels it "N.A."
12. **R-f single-shard wipe→FRESH — the one freeze-window design choice.** The 60-byte node-anchor
    freezes forever and binds shard *count* but not per-shard liveness, so a shard wiped to empty
    boots FRESH undetected. **Recommended:** add a per-shard liveness binding to the node-anchor
    NOW — e.g. `[shardAnchorDigest:32]` = SHA-256 over the sorted `(gid, lastDurableIndex)` pairs,
    refreshed on the node-anchor's existing periodic cadence; a shard reset to index 0 changes the
    digest ⇒ boot REFUSE (raises R-f from silent-loss to a full node-anchor rollback = R-a). Cost:
    +32 B in a 512-B slot (free) + one digest recompute per periodic tick (negligible; off the ack
    path). Fallback: accept R-f documented (multi-replica heals; N=1/degraded-quorum is permanent
    loss). This binding can ONLY be added at freeze time — declining it closes the door forever.

**RATIFIED 2026-07-04 — build arc authorized.** All twelve decisions above are ruled on (see the
Ratification record at the top of this document). Items 1–10 accepted as written. **Item 12 (R-f):
ADD the `shardAnchorDigest:32` per-shard liveness binding NOW** (node-anchor payload 60 B → 92 B).
**Item 11 (R-a′): KEEP the ⟦SEC-MERGE⟧ merge and CLOSE R-a′ by building a peer-quorum `AnchorWitness`**
— the "accept documented" default was rejected, because un-merging `votedFor` was shown not to remove
the residual (it is intrinsic to an un-witnessed vote). A design-pass addendum
(`docs/design/anchor-witness-peer-quorum-2026-07-04.md`) precedes the witness build. The adversarial
red-team tear that surfaced items 11–12 and the topology-layout blocker (now fixed) is recorded in §11.

---

# §11 Adversarial red-team tear — findings & dispositions

A fresh red-team auditor attacked this design on seven axes (matrix completeness, recovery gates,
the F-1 term-witness gate, rotation, version markers, cross-item invalidation, byte-level
ambiguity), read §0–§10 in full, compiled and ran the sketch (75/75), ground-checked load-bearing
claims against real source (`RaftNode`/`RaftLog`/`SegmentKeyManager`/`ConfigdServer`), and validated
its one byte-level break with a PoC. **Verdict: the durability/rollback kernel and crypto are SOUND
— no BLOCKER-class "claims to detect X but doesn't."** Nine findings; all dispositioned below.

## 11.1 Five axes attacked hard and CLEAN (freeze-positive)

- **F-1 term-witness gate (the flagged highest-value target): SOUND.** `anchor.currentTerm ≥
  lastWALTerm` is maintainable AND the current code already enforces the ordering —
  `handleAppendEntries` calls `becomeFollower → durableState.setTerm` (persist-before-memory,
  fsync'd) at `RaftNode:1592-1593,1827-1830` BEFORE `log.appendEntries` at `:1605`. No legal
  crash / pre-vote / stepdown / follower-append interleaving false-positives. → frozen as the §2.17
  build-arc invariant (do not fold/batch/defer the term fsync).
- **Anchor-before-ack TOCTOU: SOUND at the quorum level.** No window leaves an acked entry on fewer
  than a quorum of anchor-covered nodes (incl. the leader-commits-via-follower-quorum-with-own-A<i
  case — the entry survives on the follower quorum). F-2's "committed-and-client-acked" reframing is
  what makes it hold.
- **Mixed anchor rollback / snapshot accept-forward: SOUND.** All anchor fields are bound in one
  MAC'd record (no field-mixing); dual-slot yields only whole-record rollback (= R-a); compaction
  accept-forward requires an authenticated blob@B that exists only for legitimately-committed state.
- **Rotation / keyring: SOUND.** Dual-slot signing-key handover is crash-safe on both sides of the
  swap; `keyTerm=0` is locked to `KEYRING_MAGIC`; delete-keyring ⇒ REFUSE; unknown-term ⇒ REFUSE;
  64 KiB overflow ⇒ loud refuse (reads still work, no brick); `entryCount`/`wrappedLen` overflow is
  post-outer-MAC (unforgeable).
- **Version markers / fail-closed: SOUND.** CRC-before-version opens no hole; unknown
  magic/algId/version-0/rolled/reserved!=0/downgrade all fail closed; the CommandCodec carrier holds
  (decode only on post-WALE-unwrap committed bytes, `ConfigStateMachine:241,679`).

## 11.2 Findings and dispositions

| # | Severity | Finding | Disposition |
|---|---|---|---|
| RT-1 | MAJOR (blocked ratification) | Topology descriptor self-contradicts: §2.7 said 16 B/`reserved:u16`, but §2.13 + the tested sketch use 18 B/`reserved:u32` — two implementers produce mutually-unreadable `topology-descriptor.dat`, and the "75/75" proof never exercised the normative layout. PoC rejected the 16-B layout. | **FIXED.** Reconciled §2.7 to **18 B / `reserved:u32`** (the tested layout); re-ran the sketch against the now-normative layout → 18 B, round-trip OK. Blocker cleared. |
| RT-2 | MAJOR | Within-term `votedFor` rollback under ⟦SEC-MERGE⟧ is an **Election-Safety** (divergence) residual, not the "staleness" the framing implied; matrix row 4 "DETECTED / N.A." obscured it. Inside R-a (locally undetectable w/o the unbuilt `AnchorWitness`), but severity mislabeled. | **FIXED (doc/honesty).** Split into **R-a′ (SAFETY)** in §0; matrix rows 4/4b relabeled; added **ratification item 11** so ⟦SEC-MERGE⟧ is ratified with the real worst case in view. |
| RT-3 | MAJOR | Full single-shard wipe (delete anchor + truncate WAL to 0 + delete snapshot) launders "absent+non-empty ⇒ REFUSE" into "absent+empty ⇒ FRESH" — silent data loss on N=1/degraded-quorum; the 60-B node-anchor can NEVER detect it post-freeze. Unlisted in the matrix. | **SURFACED as the one freeze-window design choice.** Added **R-f** (§0), matrix row 15b, and **ratification item 12** with a concrete recommended closer (a 32-B per-shard `shardAnchorDigest` in the node-anchor, periodic-cadence, +32 B free in the 512-B slot). Operator's call — it can only be added at freeze time. |
| RT-4 | MINOR | scopeId assert mandated blanket but read call-sites not enumerated; a build could slip one. | **FIXED.** §2.2 + §9.1 now enumerate the at-rest read call-sites (WAL replay, anchor/keyring/node-anchor/topology open, snapshot reload, InstallSnapshot reload) with a per-site negative test. |
| RT-5 | MINOR | `WatchCursorV2.decode` is client-reachable + unauthenticated with no min-length guard → uncaught `BufferUnderflowException` instead of FRAME_CORRUPT. | **FIXED (grammar freeze).** §2.11 now freezes: length < 12 ⇒ FRAME_CORRUPT; all client-facing decoders map buffer-underflow → FRAME_CORRUPT. Build-arc test obligation added. |
| RT-6 | MINOR | `gid` range not reserved to exclude `NODE_SCOPE` (0xFFFFFFFF) → scope confusion if ever reached. | **FIXED.** §2.2 freezes `gid ∈ [0, NODE_SCOPE)`. |
| RT-7 | INFO | Container-header `flags`/`fileVersion` + slot `recordLen` are unauthenticated by design; verified flips yield only a clean REFUSE (slot offsets are constants; `recordLen` bounded to `[0, stride-4]`). | **FROZEN as an invariant** in §2.3: the header may never gate a security decision; a future `flags` bit must not become an adversary-flippable control. |
| RT-8 | INFO | The GCM nonce-domain guardrail lived only in a code comment (`ConfigdServer:1328-1335`); the freeze's new per-shard id invites a per-shard counter split that breaks GCM. | **PROMOTED** into normative §2.2: segment identity stays per-magic/node-global; `scopeId` is AAD-only and must not key the segment or split the counter. |
| RT-9 | INFO | Break-glass flag is safe today (launch-only sysprop) but must stay that way. | **FROZEN as an invariant** in §2.17: break-glass MUST be launch-only, never read from a data-dir file. |

## 11.3 Net

The design is ratification-ready once the operator rules on the two freeze-window severity calls
(items 11 R-a′ and 12 R-f) that the tear surfaced. The topology blocker (RT-1) is fixed and
re-verified; all MINOR/INFO findings are folded into the normative text as frozen invariants or
build-arc obligations. The five hard-attacked axes came back clean — the kernel is sound. The PoC
that validated RT-1 is preserved at `docs/design/frozen-format-v1-2026-07-03/` (sketch snapshot) in
the build arc; in this session it lives in the scratchpad sketch tree.
