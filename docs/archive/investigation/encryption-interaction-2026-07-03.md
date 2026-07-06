# Encryption Interaction Verification — does encryption-ON compose with the drive-to-green + multi-shard features?

**Date:** 2026-07-03
**Type:** READ-ONLY cross-feature verification (pre-tag). Nothing in the repo was changed except this file.
**Scope:** main @ `012e213`. Encryption at rest (Gate 6: AES-256-GCM at the `IntegrityEnvelope` seam, per-segment DEKs, KMS-SPI) × the features added after it: server-side prefix filtering + the signed version position (Gate 1, `d1f5de3`), the multi-shard watch fan-out/fan-in coordinator (Gate 3, `f21b58d`), and wire `0x03`. Each feature passed its own tests; this verifies the *composition* — the "both green alone, broken together" gap.
**Method:** 4-lane Opus team — security (LEAD, crypto composition + AAD-truncation honesty), java-engineer (what decrypts where), reliability (N-shard key contexts in catch-up), protocol (signature × envelope layering, wire `0x03`) — each grounded at file:line. Load-bearing claims were double-sourced across independent lanes (the in-memory-only fan-out plane: java + reliability; signature-never-persisted: security + protocol) or re-verified by the coordinator against source (the AAD construction, the shared-key-manager wiring, the `v2-backlog.md` overclaim, the epoch-0 rejection).

---

## 0. TL;DR — THE VERDICT: encryption composes cleanly with ALL the new features. NO pre-tag blocker.

| # | Interaction | Verdict | One-line reason |
|---|---|---|---|
| 1.1 | Server-side prefix filtering × encryption-ON | **(a) works correctly** | The filter matches plaintext keys entirely *above* the decrypt boundary; it never sees ciphertext and never touches disk |
| 1.2 | Multi-shard catch-up × N encrypted shard stores | **composes cleanly** | Catch-up reads only in-memory post-apply state; the one shared key manager is self-describing per record, so a wrong-key-context read is structurally impossible; a key failure = node refuses to boot, never a silent missing shard leg |
| 1.3 | Signed version position × encryption envelope | **composes** | Sign-plaintext / seal-a-separate-copy; the signature is never persisted, so it never passes through the envelope; recovery re-signs over round-trip-identical plaintext |
| 1.4 | AAD-truncation limitation | **still open; ship-as-documented** | The signed version position is orthogonal (in-transit edge detection, no at-rest head anchor); docs honest where authoritative, with **one overclaim to fix (`docs/v2-backlog.md:7`)** and one operator-facing bullet to add |
| 1.5 | Wire `0x03` × encryption | **clean, no coupling** | Two appended capability bytes; no wire field carries anything tied to at-rest layout |

**What IS owed (none of it blocks the tag on correctness grounds):**

1. **Coverage gap (recommend closing before tag — cheap):** the encryption-ON and the fan-out/multi-shard/signature test sets have **zero intersection** anywhere in the repo (§5). One composed test closes the whole gap: a `MultiShardIntegratedSweepTest` variant that passes `IntegrityEnvelope.encrypting(new SegmentKeyManager(localRoot), null)` into `buildRaftGroup`, restarts, and asserts (i) per-shard WAL/snapshot recovery, (ii) a watch catch-up + filtered tail over the sharded fan-out, (iii) a fan-out delta signature still verifies. The traces below prove the paths *cannot* share data, but nothing in CI proves that invariant stays true.
2. **Two docs-only fixes (fold into the already-owed pre-tag doc punch-list):** (a) `docs/v2-backlog.md:7` overclaims that binding `segment-id || offset || term` into the GCM AAD "closes the whole-log truncation gap" — it does not (it would catch reorder/relocation/splice, not trailing truncation; the real fix is a durable authenticated head anchor). The same bullet is also stale, still describing encryption as unbuilt. (b) `docs/operations/known-limitations.md` §1 documents the cross-group AAD residual but is silent on trailing truncation — add one bullet mirroring ADR-0042 item 4.

---

## 1. §1.1 — Server-side prefix filtering × encryption-ON: **(a) works correctly**

### The encryption boundary (what is encrypted, where plaintext begins)

Encryption is a pure encode/decode transform applied ONLY at the Raft durable-artifact seam, over three artifacts with distinct magics:

- **WAL records:** `RaftLog.serializeEntry` builds `[8B index][8B term][N-byte command]` and wraps it (`configd-consensus-core/.../RaftLog.java:627-634`). The command is the raw `CommandCodec` PUT/DELETE/BATCH — **keys AND values are inside the ciphertext together**, one opaque blob (there is no plaintext key index on disk, and none is needed — see below).
- **Snapshot blob:** `serializeSnapshot` wraps the whole state-machine snapshot (`RaftLog.java:672-690`).
- **Raft persistent state:** `DurableRaftState.persistValues` (`DurableRaftState.java:157-165`).

Under encryption the wrap is AES-256-GCM over the whole payload (`configd-common/.../IntegrityEnvelope.java:407-433`); decryption happens ONLY on read/recovery — `RaftLog.deserializeEntry` (`:647-660`) and `readSnapshotBlob` (`:706-748`) during `RaftLog` construction, and `DurableRaftState.load()` — *before* any plaintext reaches the state machine. `appendNoSync` seals only the WAL **copy** while keeping the plaintext `LogEntry` in the in-memory `entries` list (`RaftLog.java:366-369`).

**Everything above `RaftLog` operates on plaintext:** `ConfigStateMachine.apply`/`applySwitch` (`configd-config-store/.../ConfigStateMachine.java:239,286-331`), the in-memory `VersionedConfigStore`, and all `CommitNotification` production. The distribution service has no reference to the envelope; the encrypting envelope is wired into `RaftLog` at `ConfigdServer.java:1606`.

### The filter's input — hop by hop

**Live tail:** `applySwitch` decodes the plaintext command and calls `notifyListeners` with plaintext `ConfigMutation`s (`ConfigStateMachine.java:310,319,328`) → the server commit listener builds a `ConfigDelta` and publishes a `CommitNotification` into the **in-memory** `FanOutBuffer` (`ConfigdServer.java:1777-1795`) → `FanOutSessionCore.drainStreaming` pulls `source.readSince(cursor)` and calls `prefixFilter.keep(n)` (`FanOutSessionCore.java:361-365,394`) → `ServerPrefixFilter.keep` matches `m.key()` via literal `startsWith` (`ServerPrefixFilter.java:69-77`) — **a plaintext key from the in-memory state machine**.

**Catch-up snapshot:** `FanOutSessionCore.performSnapshotTransfer` → `replaySource.replayFromSnapshot()` (`FanOutSessionCore.java:584`) → `FilteringReplaySource` (`FanOutConnectionDriver.java:309`) wrapping `SnapshotReplaySource(rt.configStore()::snapshot)` (`ConfigdServer.java:967-970`) — a single volatile read of the **in-memory** `ConfigSnapshot` (`SnapshotReplaySource.java:38-42`), never a disk read; the same literal key predicate walks `snap.data()` (`FilteringReplaySource.java:78-92`).

### Verdict

**(a) works correctly.** Filtering happens entirely above the decrypt boundary, on plaintext keys that were decrypted once at recovery (or never encrypted, on the live path) and live in memory thereafter. Not (b): no per-event decrypt exists anywhere on the filter path. Not (c): no ciphertext ever reaches the filter and there is no full-stream fallback tied to encryption. The efficiency feature is fully effective with encryption ON.

## 2. §1.2 — Multi-shard catch-up × N encrypted shard stores: **composes cleanly**

### Key-context topology at N>1: one node-global, self-describing keyring

There is **ONE** node-level `IntegrityEnvelope` holding **ONE** shared `SegmentKeyManager`, and the SAME instance is handed to every shard: declared at `ConfigdServer.java:273`, built at `:284`, single manager constructed at `:1335` with the explicit invariant comment at `:1328-1334` (global no-(key,nonce)-reuse from a single per-magic atomic counter; a future per-group split MUST draw fresh segmentIds). The same `raftIntegrity` is passed to every group by the bring-up loop (`:515-518` → `buildRaftGroup` `:1592-1606` for `RaftLog`, `:1642` for `DurableRaftState`). Per-shard *storage* is isolated (`dataDir/shard-<gid>`, `:1602-1604`); the *key context* is node-global.

**A wrong-context cross-shard read is structurally impossible:** every record stamps its own `keyTerm` + 16-byte random `segmentId` (`IntegrityEnvelope.java:468-478`), and `resolveDek` re-derives `DEK = HKDF(root[keyTerm], segmentId)` from those stamped fields alone (`SegmentKeyManager.java:166-179`). No per-shard state selects the key — any reader with the node root either derives correctly or fails closed on an unknown term. Nonce uniqueness across N concurrent shard writers holds because write segments are keyed by artifact magic in the one shared manager (`SegmentKeyManager.java:89-91,148-163`).

### The catch-up path never decrypts anything

Each shard leg's catch-up reads **in-memory post-apply state**, not disk: per-shard replay = `SnapshotReplaySource(rt.configStore()::snapshot)` (in-memory `ConfigSnapshot`, `SnapshotReplaySource.java:38-42`); per-shard commit source = the in-memory `FanOutBuffer` ring (`ConfigdServer.java:963-964`). `FanOutSessionCore` pulls only via `readSince`/`replayFromSnapshot` (`FanOutSessionCore.java:361,582-584`) and holds no `Storage`/`RaftLog`/`IntegrityEnvelope` reference. A grep of `FanOutConnectionDriver` for any disk access returns zero hits — the fan-in consumes exclusively `sources.get(g)` / `replaySources.get(g)` per group (`FanOutConnectionDriver.java:309-311`). The watermark/progress vector is likewise in-memory (`FanOutConnectionDriver.java:902-909`). Nothing at N=1-unencrypted relied on plaintext-on-disk, and nothing at N>1-encrypted changes any of these reads.

### Failure mode: fail-visible at boot, never a silent missing leg

A key-context error can only fire at boot recovery (the only at-rest decrypt sites are the `RaftLog` constructor and `DurableRaftState.load()`, both inside `buildRaftGroup`). An unknown `keyTerm` (`SegmentKeyManager.java:171-176`) or bad GCM tag (`IntegrityEnvelope.java:484-492`) throws `IntegrityException`, which propagates into the bring-up loop's `catch (Throwable)` at `ConfigdServer.java:541-551` — every built group is shut down and the node **refuses to start**. A watch can never be missing a shard leg due to encryption, because there is no serving node in that state. Runtime is encrypt-only on the hot path (cached DEK + fresh nonce; no decrypt, no fallible lookup mid-serve). Completeness (`materializeAllCores`/`seedAllCores`, `FanOutConnectionDriver.java:625-654`) is independent of encryption.

## 3. §1.3 — Signed version position × the encryption envelope: **composes** (orthogonal by construction)

The decisive fact: **the Ed25519 delta signature is never persisted, so it never passes through the at-rest envelope.** The composition is not sign-then-encrypt or encrypt-then-sign over shared bytes — the two operate on *different copies*:

- **Sign (plaintext, at apply time):** `ConfigStateMachine.applySwitch` → `signCommand` (`ConfigStateMachine.java:297,315,324` → `:621-669`) signs `canonicalize(command) || BE(seq-1,8) || BE(seq,8) || BE(epoch,8) || nonce` (`:639-646`), where `canonicalize` = plaintext `CommandCodec.encodeBatch` (`:678-691`). The command bytes come from the **in-memory** `LogEntry` (`RaftNode.java:2250,2302`). The signature is cached in `lastSignature` (`:651`) and attached to outgoing `ConfigDelta`s; the only persisted signing artifact is the `signingEpoch` in the snapshot trailer (`:384`).
- **Seal (a separate on-disk copy):** `RaftLog.serializeEntry` wraps `[index|term|command]` (`RaftLog.java:627-634`) — the signature is not in this payload.
- **Verify (plaintext, over the wire):** `encodeNotificationInto` writes fromVersion/toVersion/epoch/nonce + the plaintext `encodeBatch(mutations)` + signature (`EdgeFrameCodec.java:346-369`); the edge rebuilds the byte-identical payload via `ConfigDelta.signingPayload()` (`ConfigDelta.java:136-148`; `configd-edge-cache/.../DeltaApplier.java:239-240,341-347`) and verifies (`ConfigSigner.java:70-83`).

**The replay path (where an ordering bug would hide) is also clean:** catch-up delivers a *snapshot*, not signed deltas (`SnapshotReplaySource.java:37-42` — whole-state transfer, no per-delta signature). The only unseal of the envelope is `RaftLog` recovery on restart (`RaftLog.java:151-154,647-660`), which yields the byte-identical plaintext `[index|term|command]`; re-apply then **re-signs** over that round-tripped plaintext (fresh epoch/nonce by design — true encryption-OFF as well; edges reconcile via the epoch/version chain, not byte-equality). AES-GCM decrypt is an authenticated identity on the command, so signed bytes == verified bytes on both the live and the restart paths. Write/verify order is consistent; encryption-ON cannot make position-signature verification fail, and the signature cannot make decryption fail.

Epoch handling is unchanged under encryption: the edge rejects a signature carried on an epoch-0 delta (`DeltaApplier.java:233-237` — coordinator-verified; note the class lives in `configd-edge-cache`, not `configd-edge-node`), and the epoch carry-forward rides the snapshot trailer, which round-trips byte-identically through the envelope (`ConfigStateMachine.java:384,500-505`).

## 4. §1.4 — The AAD-truncation limitation: **still open; the signed version position is orthogonal; ship-as-documented + two doc fixes**

**Still open — confirmed.** The GCM AAD is exactly the 40-byte prefix `header(8) || keyTerm(4) || segmentId(16) || nonce(12)` (`IntegrityEnvelope.java:102-104` definition, `:425` write, `:481` read — coordinator-verified). No log position, no whole-log count, no head anchor. No commit since the Gate-6 merge touched the AAD (Gates 1–4 are fan-out/ACL/watch changes). A record's index IS inside the authenticated ciphertext, so an individual record cannot be forged or relocated — but deleting whole trailing frames leaves each remaining frame independently valid, and recovery accepts the shorter log as legit crash-truncation. The same root cause admits whole-file rollback of `raft.persistent_state` (an old authentic file has a valid tag; no monotonic anchor).

**The signed version position does NOT change this.** It is per-record, on-the-wire, and never persisted (§3): it lets an *edge* detect an in-transit relay suppressing/splicing deltas or a rolled-back leader presenting a lower fromVersion/epoch (ADR-0045's anti-suppression goal) — but a recovering *node* has no signed head-version to check its own disk against. `DurableRaftState` persists term+votedFor only; commitIndex/lastApplied are volatile per Raft Figure 2 (`RaftLog.java:53-59`). Detected: in-transit suppression/splice/rollback (edge-side). Not detected: at-rest trailing truncation of the WAL, at-rest rollback of `raft.persistent_state`, a node booting at a stale head. Orthogonal, as the charter suspected.

**Docs honesty: honest where authoritative; one overclaim; one operator-facing gap.**

- **Honest:** ADR-0042 item 4 states it plainly ("an attacker who truncates committed records from the tail produces a shorter-but-valid log… a whole-log anchor is flagged", `docs/adr/adr-0042-snapshot-wal-raftstate-integrity.md:102-104`); the go/no-go (`docs/archive/readiness/v1-go-no-go-2026-07-01.md:287`) and register §8.12 list WAL tail-truncation as an open residual; the threat model's B-DISK row claims only tampered-record-refused / torn-tail-tolerated; README and `known-limitations.md` §1 claim confidentiality + tamper detection, never truncation protection.
- **Overclaim (fix pre-tag, docs-only):** `docs/v2-backlog.md:7` — "Binding `segment-id || offset || term` into the GCM additional-authenticated-data also closes the whole-log truncation gap the integrity layer leaves open." Incorrect: offset-in-AAD detects reorder/relocation/cross-segment splice, not trailing truncation (removing the tail leaves every remaining record's offset-AAD self-consistent; nothing attests set size). This contradicts ADR-0042's own (correct) "whole-log anchor" framing. The same bullet is independently stale (describes encryption as unbuilt — already on the owed doc-reconciliation punch-list). The archived research docs echo the same overclaim (`docs/archive/research/encryption-at-rest/configd-analysis.md:154-155`, `recommendation.md:198`) — archive, historical record, lower priority.
- **Gap (add pre-tag, docs-only):** operator-facing `known-limitations.md` §1 documents the cross-group AAD residual (`:50-55`) but is silent on trailing truncation; the honest statement lives only in the ADR/register/go-no-go. Add one bullet mirroring ADR-0042 item 4.

**Recommendation: ship-as-documented-limitation; defer the real fix to v2.** The pre-tag review's suggested "bind position into the AAD" would NOT close trailing truncation (above). The real fix is a durable authenticated monotonic high-water mark (persist a last-durable-index inside the already-authenticated `raft.persistent_state` envelope + a recovery gate + anti-rollback for the state file) — a new authenticated persistent field with migration/compat and a crash-matrix test surface: a durability-kernel change, high-risk under a tag deadline. The residual is bounded and in-model (an A2 storage-writer without T0 keys; a truncated follower self-heals from the leader; the dangerous case — a truncated leader silently breaking persist-before-ack — exists identically with encryption OFF and is a durability attack, not a confidentiality break). It is already tracked honestly in three authoritative docs.

## 5. §1.5 — Wire `0x03` × encryption: **clean, no coupling**

The entire `0x03` delta is two appended boolean capability bytes, absent (byte-identical) under `0x01`/`0x02`: SUBSCRIBE trailing `acceptsFiltered` (`EdgeFrameCodec.java:313-315` encode, `:705-710` decode; `EdgeFrame.java:95`) and SUBSCRIBE_OK trailing `filtered` confirm (`:322-324`/`:729-734`; `EdgeFrame.java:162`), selecting the edge's filtered-vs-classic apply mode. The signed version position on the wire is purely logical (`fromVersion`/`toVersion` u64, epoch, nonce — `EdgeFrameCodec.java:356-357,366-368`); the mutation blob is plaintext `encodeBatch`; `WatchCursor` is the per-shard `(gid, S)` revision vector (`WatchCursor.java:29-85`). No wire field carries a persisted file offset, an at-rest checksum, ciphertext, or the envelope's `keyTerm`/`segmentId`/nonce. Confirmed: no accidental coupling to at-rest layout.

## 6. Test coverage — the one real gap

The composition is proven by tracing, but **no test anywhere runs any of the new features with encryption ON**. The two test populations are disjoint by module and by flag:

- **Encryption-ON tests (all N=1, none touch the fan-out/signer):** `IntegrityEnvelopeEncryptionTest`, `SegmentKeyManagerTest`, `LocalKmsEncryptionIntegrationTest` (configd-common); `RaftLogEncryptionTest` (consensus-core: restart round-trip, no-plaintext-on-disk, tamper-refusal, mixed algId 1/2); `EncryptionAtRestWiringTest` (server, explicit no-full-boot unit test). Run by `gates/gate-7.sh:108-116`.
- **N>1 / fan-out / signature tests (all encryption OFF):** `MultiGroupBringupTest` (N=8) and `MultiShardIntegratedSweepTest` (N=4, real `buildRaftGroup` + real sharded fan-out) use the keyed-HMAC envelope; `MultiShardCoordinatorTest`/`MultiShardSim`/`ShardedFanOutTest` use synthetic in-memory sources; `FanOutSessionCorePrefixFilterTest` + the filter divergence/authz tests never set the flag; the signed-position tests (`ConfigStateMachineTest`, `ConfigSignerTest`, `DeltaApplier` tests, `EdgeFrameCodecV3GoldenFixtureTest`) never enable encryption.
- Greps co-locating the encryption enable (`configd.raft.encryption.enabled` / `CONFIGD_ENCRYPTION_AT_REST` / `IntegrityEnvelope.encrypting` / `ALG_AES256_GCM`) with the filter, the coordinator, or the signer paths return **empty** across all test trees.

**Cheapest close (one test, recommended before tag):** a `MultiShardIntegratedSweepTest` variant passing `IntegrityEnvelope.encrypting(new SegmentKeyManager(localRoot), null)` into `buildRaftGroup` — asserting per-shard WAL/snapshot recovery after restart, a multi-shard watch catch-up + filtered tail over the sharded fan-out, and that a fan-out delta signature verifies. That single test intersects §1.1 + §1.2 + §1.3 at once. Risk of the gap is LOW (the fan-out plane structurally cannot reach the encrypted artifacts), so this is a coverage recommendation, not a blocker.

## 7. Definition-of-done mapping

- [x] §1.1 verdict **(a) works** + coverage: none (gap flagged, §6)
- [x] §1.2 per-shard key contexts **correct by self-description**; fail-visible at boot; coverage: none (gap flagged, §6)
- [x] §1.3 **composes** — write/verify order consistent (sign-plaintext / seal-separate-copy / re-sign-on-recovery); round-trip test: none (gap flagged, §6)
- [x] §1.4 still open ✓, docs honest-with-one-overclaim (`v2-backlog.md:7`) + one operator-facing bullet owed; **ship-documented**, real fix (durable head anchor) → v2
- [x] §1.5 confirmed no coupling
- [x] VERDICT: **composes cleanly — safe to tag**, with (i) one recommended encryption-ON composition test and (ii) two docs-only fixes folded into the owed pre-tag doc punch-list
- [x] Read-only: nothing changed but this file
