# Encryption interaction verification -- does encryption-ON compose with the fan-out, multi-shard, and signing features?

Cross-feature verification from 2026-07-03, read-only -- nothing in the repo changed except this file.
Scope: `main` @ `012e213`. Encryption at rest (AES-256-GCM at the `IntegrityEnvelope` seam, per-segment
DEKs, KMS-SPI) crossed with the features added after it: server-side prefix filtering plus the signed
version position (commit `d1f5de3`), the multi-shard watch fan-out/fan-in coordinator (commit
`f21b58d`), and wire `0x03`. Each feature passed its own tests; this verifies the composition -- the
"both green alone, broken together" gap. Findings are grounded at file:line and cross-checked against
source (the AAD construction, the shared-key-manager wiring, the at-rest AAD overclaim, the epoch-0
rejection).

---

## Summary: encryption composes cleanly with all the new features. No pre-tag blocker.

| # | Interaction | Verdict | One-line reason |
|---|---|---|---|
| 1.1 | Server-side prefix filtering x encryption-ON | works correctly | The filter matches plaintext keys entirely above the decrypt boundary; it never sees ciphertext and never touches disk |
| 1.2 | Multi-shard catch-up x N encrypted shard stores | composes cleanly | Catch-up reads only in-memory post-apply state, and the one shared key manager is self-describing per record, so a wrong-key-context read is structurally impossible; a key failure means the node refuses to boot, never a silent missing shard leg |
| 1.3 | Signed version position x encryption envelope | composes | Sign-plaintext / seal-a-separate-copy; the signature is never persisted, so it never passes through the envelope; recovery re-signs over round-trip-identical plaintext |
| 1.4 | AAD-truncation limitation | still open; ship as documented | The signed version position is orthogonal (in-transit edge detection, no at-rest head anchor); docs are honest where authoritative, with one at-rest AAD overclaim to fix and one operator-facing bullet to add |
| 1.5 | Wire `0x03` x encryption | clean, no coupling | Two appended capability bytes; no wire field carries anything tied to at-rest layout |

What is owed (none of it blocks the tag on correctness grounds):

1. **Coverage gap (recommend closing before tag, cheap):** the encryption-ON and the
   fan-out/multi-shard/signature test sets have zero intersection anywhere in the repo (section 5). One
   composed test closes the whole gap: a `MultiShardIntegratedSweepTest` variant that passes
   `IntegrityEnvelope.encrypting(new SegmentKeyManager(localRoot), null)` into `buildRaftGroup`, restarts,
   and asserts (i) per-shard WAL/snapshot recovery, (ii) a watch catch-up plus filtered tail over the
   sharded fan-out, (iii) a fan-out delta signature still verifies. The traces below prove the paths
   cannot share data, but nothing in CI proves that invariant stays true.
2. **Two docs-only fixes:** (a) the at-rest AAD documentation overclaims that binding `segment-id || offset ||
   term` into the GCM AAD "closes the whole-log truncation gap" -- it does not (it would catch
   reorder/relocation/splice, not trailing truncation; the real fix is a durable authenticated head
   anchor). The same bullet is also stale, still describing encryption as unbuilt. (b)
   `docs/operations/known-limitations.md` section 1 documents the cross-group AAD residual but is silent
   on trailing truncation; add one bullet mirroring ADR-0042 item 4.

---

## 1. Server-side prefix filtering x encryption-ON: works correctly

### The encryption boundary (what is encrypted, where plaintext begins)

Encryption is a pure encode/decode transform applied only at the Raft durable-artifact seam, over three
artifacts with distinct magics:

- **WAL records:** `RaftLog.serializeEntry` builds `[8B index][8B term][N-byte command]` and wraps it
  (`configd-consensus-core/.../RaftLog.java:627-634`). The command is the raw `CommandCodec` PUT/DELETE/
  BATCH -- keys and values are inside the ciphertext together, one opaque blob (there is no plaintext key
  index on disk, and none is needed, see below).
- **Snapshot blob:** `serializeSnapshot` wraps the whole state-machine snapshot (`RaftLog.java:672-690`).
- **Raft persistent state:** `DurableRaftState.persistValues` (`DurableRaftState.java:157-165`).

Under encryption the wrap is AES-256-GCM over the whole payload (`configd-common/.../IntegrityEnvelope.java:407-433`);
decryption happens only on read/recovery -- `RaftLog.deserializeEntry` (`:647-660`) and `readSnapshotBlob`
(`:706-748`) during `RaftLog` construction, and `DurableRaftState.load()` -- before any plaintext reaches
the state machine. `appendNoSync` seals only the WAL copy while keeping the plaintext `LogEntry` in the
in-memory `entries` list (`RaftLog.java:366-369`).

Everything above `RaftLog` operates on plaintext: `ConfigStateMachine.apply`/`applySwitch`
(`configd-config-store/.../ConfigStateMachine.java:239,286-331`), the in-memory `VersionedConfigStore`,
and all `CommitNotification` production. The distribution service has no reference to the envelope; the
encrypting envelope is wired into `RaftLog` at `ConfigdServer.java:1606`.

### The filter's input, hop by hop

**Live tail:** `applySwitch` decodes the plaintext command and calls `notifyListeners` with plaintext
`ConfigMutation`s (`ConfigStateMachine.java:310,319,328`) -> the server commit listener builds a
`ConfigDelta` and publishes a `CommitNotification` into the in-memory `FanOutBuffer`
(`ConfigdServer.java:1777-1795`) -> `FanOutSessionCore.drainStreaming` pulls `source.readSince(cursor)`
and calls `prefixFilter.keep(n)` (`FanOutSessionCore.java:361-365,394`) -> `ServerPrefixFilter.keep`
matches `m.key()` via literal `startsWith` (`ServerPrefixFilter.java:69-77`), a plaintext key from the
in-memory state machine.

**Catch-up snapshot:** `FanOutSessionCore.performSnapshotTransfer` -> `replaySource.replayFromSnapshot()`
(`FanOutSessionCore.java:584`) -> `FilteringReplaySource` (`FanOutConnectionDriver.java:309`) wrapping
`SnapshotReplaySource(rt.configStore()::snapshot)` (`ConfigdServer.java:967-970`), a single volatile read
of the in-memory `ConfigSnapshot` (`SnapshotReplaySource.java:38-42`), never a disk read; the same literal
key predicate walks `snap.data()` (`FilteringReplaySource.java:78-92`).

### Verdict

Works correctly. Filtering happens entirely above the decrypt boundary, on plaintext keys that were
decrypted once at recovery (or never encrypted, on the live path) and live in memory thereafter. No
per-event decrypt exists anywhere on the filter path. No ciphertext ever reaches the filter and there is
no full-stream fallback tied to encryption. The efficiency feature is fully effective with encryption ON.

## 2. Multi-shard catch-up x N encrypted shard stores: composes cleanly

### Key-context topology at N>1: one node-global, self-describing keyring

There is one node-level `IntegrityEnvelope` holding one shared `SegmentKeyManager`, and the same instance
is handed to every shard: declared at `ConfigdServer.java:273`, built at `:284`, single manager
constructed at `:1335` with the explicit invariant comment at `:1328-1334` (global no-(key,nonce)-reuse
from a single per-magic atomic counter; a future per-group split must draw fresh segmentIds). The same
`raftIntegrity` is passed to every group by the bring-up loop (`:515-518` -> `buildRaftGroup` `:1592-1606`
for `RaftLog`, `:1642` for `DurableRaftState`). Per-shard storage is isolated (`dataDir/shard-<gid>`,
`:1602-1604`); the key context is node-global.

A wrong-context cross-shard read is structurally impossible: every record stamps its own `keyTerm` plus a
16-byte random `segmentId` (`IntegrityEnvelope.java:468-478`), and `resolveDek` re-derives
`DEK = HKDF(root[keyTerm], segmentId)` from those stamped fields alone (`SegmentKeyManager.java:166-179`).
No per-shard state selects the key; any reader with the node root either derives correctly or fails
closed on an unknown term. Nonce uniqueness across N concurrent shard writers holds because write
segments are keyed by artifact magic in the one shared manager (`SegmentKeyManager.java:89-91,148-163`).

### The catch-up path never decrypts anything

Each shard leg's catch-up reads in-memory post-apply state, not disk: per-shard replay is
`SnapshotReplaySource(rt.configStore()::snapshot)` (in-memory `ConfigSnapshot`,
`SnapshotReplaySource.java:38-42`); per-shard commit source is the in-memory `FanOutBuffer` ring
(`ConfigdServer.java:963-964`). `FanOutSessionCore` pulls only via `readSince`/`replayFromSnapshot`
(`FanOutSessionCore.java:361,582-584`) and holds no `Storage`/`RaftLog`/`IntegrityEnvelope` reference. A
grep of `FanOutConnectionDriver` for any disk access returns zero hits -- the fan-in consumes exclusively
`sources.get(g)` / `replaySources.get(g)` per group (`FanOutConnectionDriver.java:309-311`). The
watermark/progress vector is likewise in-memory (`FanOutConnectionDriver.java:902-909`). Nothing at
N=1-unencrypted relied on plaintext-on-disk, and nothing at N>1-encrypted changes any of these reads.

### Failure mode: fail-visible at boot, never a silent missing leg

A key-context error can only fire at boot recovery (the only at-rest decrypt sites are the `RaftLog`
constructor and `DurableRaftState.load()`, both inside `buildRaftGroup`). An unknown `keyTerm`
(`SegmentKeyManager.java:171-176`) or bad GCM tag (`IntegrityEnvelope.java:484-492`) throws
`IntegrityException`, which propagates into the bring-up loop's `catch (Throwable)` at
`ConfigdServer.java:541-551`: every built group is shut down and the node refuses to start. A watch can
never be missing a shard leg due to encryption, because there is no serving node in that state. Runtime
is encrypt-only on the hot path (cached DEK plus fresh nonce; no decrypt, no fallible lookup mid-serve).
Completeness (`materializeAllCores`/`seedAllCores`, `FanOutConnectionDriver.java:625-654`) is independent
of encryption.

## 3. Signed version position x the encryption envelope: composes (orthogonal by construction)

The decisive fact: the Ed25519 delta signature is never persisted, so it never passes through the at-rest
envelope. The composition is not sign-then-encrypt or encrypt-then-sign over shared bytes -- the two
operate on different copies:

- **Sign (plaintext, at apply time):** `ConfigStateMachine.applySwitch` -> `signCommand`
  (`ConfigStateMachine.java:297,315,324` -> `:621-669`) signs
  `canonicalize(command) || BE(seq-1,8) || BE(seq,8) || BE(epoch,8) || nonce` (`:639-646`), where
  `canonicalize` is plaintext `CommandCodec.encodeBatch` (`:678-691`). The command bytes come from the
  in-memory `LogEntry` (`RaftNode.java:2250,2302`). The signature is cached in `lastSignature` (`:651`)
  and attached to outgoing `ConfigDelta`s; the only persisted signing artifact is the `signingEpoch` in
  the snapshot trailer (`:384`).
- **Seal (a separate on-disk copy):** `RaftLog.serializeEntry` wraps `[index|term|command]`
  (`RaftLog.java:627-634`); the signature is not in this payload.
- **Verify (plaintext, over the wire):** `encodeNotificationInto` writes fromVersion/toVersion/epoch/nonce
  plus the plaintext `encodeBatch(mutations)` plus signature (`EdgeFrameCodec.java:346-369`); the edge
  rebuilds the byte-identical payload via `ConfigDelta.signingPayload()` (`ConfigDelta.java:136-148`;
  `configd-edge-cache/.../DeltaApplier.java:239-240,341-347`) and verifies (`ConfigSigner.java:70-83`).

The replay path (where an ordering bug would hide) is also clean: catch-up delivers a snapshot, not
signed deltas (`SnapshotReplaySource.java:37-42`, whole-state transfer, no per-delta signature). The only
unseal of the envelope is `RaftLog` recovery on restart (`RaftLog.java:151-154,647-660`), which yields the
byte-identical plaintext `[index|term|command]`; re-apply then re-signs over that round-tripped plaintext
(fresh epoch/nonce by design, true with encryption off as well; edges reconcile via the epoch/version
chain, not byte-equality). AES-GCM decrypt is an authenticated identity on the command, so signed bytes
equal verified bytes on both the live and the restart paths. Write/verify order is consistent;
encryption-ON cannot make position-signature verification fail, and the signature cannot make decryption
fail.

Epoch handling is unchanged under encryption: the edge rejects a signature carried on an epoch-0 delta
(`DeltaApplier.java:233-237`; note the class lives in `configd-edge-cache`, not `configd-edge-node`), and
the epoch carry-forward rides the snapshot trailer, which round-trips byte-identically through the
envelope (`ConfigStateMachine.java:384,500-505`).

## 4. The AAD-truncation limitation: still open; the signed version position is orthogonal; ship as documented plus two doc fixes

Still open, confirmed. The GCM AAD is exactly the 40-byte prefix `header(8) || keyTerm(4) ||
segmentId(16) || nonce(12)` (`IntegrityEnvelope.java:102-104` definition, `:425` write, `:481` read). No
log position, no whole-log count, no head anchor. No commit since the encryption-at-rest merge touched the
AAD (the fan-out/ACL/watch commits since then are unrelated). A record's index is inside the authenticated
ciphertext, so an individual record cannot be forged or relocated, but deleting whole trailing frames
leaves each remaining frame independently valid, and recovery accepts the shorter log as legitimate
crash-truncation. The same root cause admits whole-file rollback of `raft.persistent_state` (an old
authentic file has a valid tag; no monotonic anchor).

The signed version position does not change this. It is per-record, on-the-wire, and never persisted
(section 3): it lets an edge detect an in-transit relay suppressing/splicing deltas or a rolled-back
leader presenting a lower fromVersion/epoch (ADR-0045's anti-suppression goal), but a recovering node has
no signed head-version to check its own disk against. `DurableRaftState` persists term+votedFor only;
commitIndex/lastApplied are volatile per Raft Figure 2 (`RaftLog.java:53-59`). Detected: in-transit
suppression/splice/rollback (edge-side). Not detected: at-rest trailing truncation of the WAL, at-rest
rollback of `raft.persistent_state`, a node booting at a stale head. Orthogonal, as expected going into
this review.

Docs honesty: honest where authoritative; one overclaim; one operator-facing gap.

- **Honest:** ADR-0042 item 4 states it plainly ("an attacker who truncates committed records from the
  tail produces a shorter-but-valid log ... a whole-log anchor is flagged",
  `docs/adr/adr-0042-snapshot-wal-raftstate-integrity.md:102-104`); the go/no-go
  (`docs/archive/readiness/v1-go-no-go-2026-07-01.md:287`) and the readiness register list WAL
  tail-truncation as an open residual; the threat model's B-DISK row claims only tampered-record-refused /
  torn-tail-tolerated; README and `known-limitations.md` section 1 claim confidentiality plus tamper
  detection, never truncation protection.
- **Overclaim (fix, docs-only):** the at-rest AAD documentation stated "Binding `segment-id || offset || term`
  into the GCM additional-authenticated-data also closes the whole-log truncation gap the integrity layer
  leaves open." This is incorrect: offset-in-AAD detects reorder/relocation/cross-segment splice, not
  trailing truncation (removing the tail leaves every remaining record's offset-AAD self-consistent;
  nothing attests set size). This contradicts ADR-0042's own, correct, "whole-log anchor" framing. The
  same bullet is independently stale (describes encryption as unbuilt). The archived research docs echo
  the same overclaim (`docs/archive/research/encryption-at-rest/configd-analysis.md:154-155`,
  `recommendation.md:198`), a lower-priority historical record.
- **Gap (add, docs-only):** operator-facing `known-limitations.md` section 1 documents the cross-group
  AAD residual (`:50-55`) but is silent on trailing truncation; the honest statement lives only in the
  ADR/register/go-no-go. Add one bullet mirroring ADR-0042 item 4.

Recommendation: ship as documented limitation; defer the real fix. Binding position into the AAD would
not close trailing truncation (above). The real fix is a durable authenticated monotonic high-water mark
(persist a last-durable-index inside the already-authenticated `raft.persistent_state` envelope plus a
recovery gate plus anti-rollback for the state file), a new authenticated persistent field with
migration/compat and a crash-matrix test surface -- a durability-kernel change, high-risk under a tag
deadline. The residual is bounded and in-model (an A2 storage-writer without root keys; a truncated
follower self-heals from the leader; the dangerous case, a truncated leader silently breaking
persist-before-ack, exists identically with encryption off and is a durability attack, not a
confidentiality break). It is already tracked honestly in three authoritative docs.

## 5. Wire `0x03` x encryption: clean, no coupling

The entire `0x03` delta is two appended boolean capability bytes, absent (byte-identical) under
`0x01`/`0x02`: SUBSCRIBE trailing `acceptsFiltered` (`EdgeFrameCodec.java:313-315` encode, `:705-710`
decode; `EdgeFrame.java:95`) and SUBSCRIBE_OK trailing `filtered` confirm (`:322-324`/`:729-734`;
`EdgeFrame.java:162`), selecting the edge's filtered-vs-classic apply mode. The signed version position on
the wire is purely logical (`fromVersion`/`toVersion` u64, epoch, nonce,
`EdgeFrameCodec.java:356-357,366-368`); the mutation blob is plaintext `encodeBatch`; `WatchCursor` is the
per-shard `(gid, S)` revision vector (`WatchCursor.java:29-85`). No wire field carries a persisted file
offset, an at-rest checksum, ciphertext, or the envelope's `keyTerm`/`segmentId`/nonce. Confirmed: no
accidental coupling to at-rest layout.

## 6. Test coverage -- the one real gap

The composition is proven by tracing, but no test anywhere runs any of the new features with encryption
ON. The two test populations are disjoint by module and by flag:

- **Encryption-ON tests (all N=1, none touch the fan-out/signer):** `IntegrityEnvelopeEncryptionTest`,
  `SegmentKeyManagerTest`, `LocalKmsEncryptionIntegrationTest` (configd-common); `RaftLogEncryptionTest`
  (consensus-core: restart round-trip, no-plaintext-on-disk, tamper-refusal, mixed algId 1/2);
  `EncryptionAtRestWiringTest` (server, explicit no-full-boot unit test).
- **N>1 / fan-out / signature tests (all encryption OFF):** `MultiGroupBringupTest` (N=8) and
  `MultiShardIntegratedSweepTest` (N=4, real `buildRaftGroup` plus real sharded fan-out) use the
  keyed-HMAC envelope; `MultiShardCoordinatorTest`/`MultiShardSim`/`ShardedFanOutTest` use synthetic
  in-memory sources; `FanOutSessionCorePrefixFilterTest` plus the filter divergence/authz tests never set
  the flag; the signed-position tests (`ConfigStateMachineTest`, `ConfigSignerTest`, `DeltaApplier` tests,
  `EdgeFrameCodecV3GoldenFixtureTest`) never enable encryption.
- Greps co-locating the encryption enable flag (`configd.raft.encryption.enabled` /
  `CONFIGD_ENCRYPTION_AT_REST` / `IntegrityEnvelope.encrypting` / `ALG_AES256_GCM`) with the filter, the
  coordinator, or the signer paths return empty across all test trees.

Cheapest close (one test, recommended before tag): a `MultiShardIntegratedSweepTest` variant passing
`IntegrityEnvelope.encrypting(new SegmentKeyManager(localRoot), null)` into `buildRaftGroup`, asserting
per-shard WAL/snapshot recovery after restart, a multi-shard watch catch-up plus filtered tail over the
sharded fan-out, and that a fan-out delta signature verifies. That single test intersects sections 1, 2,
and 3 at once. Risk of the gap is low (the fan-out plane structurally cannot reach the encrypted
artifacts), so this is a coverage recommendation, not a blocker.

## 7. Summary of findings

- Section 1: works. Coverage gap flagged in section 6.
- Section 2: per-shard key contexts are correct by self-description; fail-visible at boot. Coverage gap
  flagged in section 6.
- Section 3: composes; write/verify order consistent (sign-plaintext / seal-separate-copy /
  re-sign-on-recovery). Round-trip test: none (gap flagged in section 6).
- Section 4: still open, confirmed; docs honest with one at-rest AAD overclaim plus one
  operator-facing bullet owed; ship as documented; the real fix (durable head anchor) is deferred.
- Section 5: confirmed, no coupling.
- Overall: composes cleanly, safe to tag, with (i) one recommended encryption-ON composition test and
  (ii) two docs-only fixes.
- Read-only: nothing changed but this file.
