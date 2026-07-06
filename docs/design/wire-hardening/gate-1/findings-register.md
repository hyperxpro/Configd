# Wire-Hardening Arc — Consolidated Findings Register (Gate 1 output → Gate 2 backlog)

Unifies the four Gate-1 deliverables (`catalog-raft-plane.md`, `catalog-edge-plane.md`,
`threat-model.md`, `attack-matrix.md`) plus the lead's independent read (`lead-notes.md`) into ONE
finding namespace (`WH-nn`). Each row maps back to the source id(s). Ordered by severity.

## Trust-boundary framing (applies to every finding)

Both planes sit behind **mTLS** (Raft: node certs; edge: `setNeedClientAuth(true)`). No finding is
reachable by an anonymous off-path attacker in a TLS deployment. The realistic adversary is:
(a) a **compromised/Byzantine but cert-valid cluster member** (Raft plane), (b) a **cert-valid but
unauthorized client** or **malicious/compromised distribution server** (edge plane), or (c) a
**plaintext test/dev deployment** (no mTLS). Gate 4 explicitly injects "a hostile peer into a real
cluster", so these are in-scope, not theoretical.

Every well-formed frame must remain **byte-identical** after Gate 2 (golden-fixture gates:
`WireCompatGoldenBytesTest`, `EdgeFrameCodecGoldenFixtureTest` + V2/V3). A fix that changes valid-frame
bytes needs a wire-version bump = a STOP-for-operator fork.

---

## Severity: HIGH

### WH-01 — Malformed committed command crashes the apply loop (poison-pill → durable crash-loop)
- **Source:** lead-notes (independent; NOT caught by the agent catalogs, which stop at "alloc bounded").
- **Where:** `ConfigStateMachine.apply` (`configd-config-store/.../ConfigStateMachine.java:241`, second
  site `:673`) calls `CommandCodec.decode(command)` **outside** the try/catch (try begins `:245`).
- **Mechanism:** AppendEntries validates only the OUTER entry (cmdLen ≤ 1 MiB), not the inner
  PUT/DELETE/BATCH grammar. A cert-valid-but-Byzantine leader proposes a command that frames cleanly
  but is grammatically malformed (unknown mutation type → `IllegalArgumentException`; over-large inner
  keyLen → `BufferUnderflowException`). Once committed it is durable in the WAL; every replica
  re-decodes on apply **and on WAL replay** → deterministic cluster-wide crash-loop.
- **Fix direction (Gate 2 analysis owed):** (1) make `CommandCodec.decode` total/fail-closed (feeds
  WH-02/03/04); (2) the real defense is UPSTREAM — validate the command grammar at **propose time**
  (leader rejects the client write) and/or at **AppendEntries append** before commit, so a poison pill
  never enters the log; (3) apply-time must be deterministic across replicas (a committed-but-
  undecodable entry cannot simply be skipped on some nodes and not others). **Candidate STOP-for-operator
  fork** if the fix touches the append/commit contract. Analyze reachability end-to-end first.

---

## Severity: MEDIUM

### WH-11 — Edge plane has no post-mTLS pre-SUBSCRIBE read-idle deadline (slow-loris)
- **Source:** threat-model G-2 · matrix (edge×class10) · lead-notes #1 · edge-catalog pre-auth §.
- **Where:** `NettyFanOutServer.initChannel` (`:267-281`) installs no `IdleStateHandler`; JDK
  `FanOutServer` does `ssl.setSoTimeout(0)` after handshake (`:330`).
- **Mechanism:** a peer completes mTLS then stalls (sends nothing, or a length prefix then dribbles),
  parking a session slot + FD + up to a ~2 MiB cumulator until OS reap. Bounded to `maxSessions=1024`
  but denies service to real subscribers. The Raft plane already enforces `inboundReadTimeoutMs` (15 s)
  — **the fix pattern exists in-repo.**
- **Nuance:** a *legitimate* fan-out subscriber is idle by design (server pushes; client rarely sends),
  so a naive read-idle timeout is WRONG. The correct control is a **pre-SUBSCRIBE handshake deadline**
  (reap a connection that has not sent its first routed control frame within a bound), plus optionally a
  generous liveness tied to the existing server→client HEARTBEAT. Highest-value net-new gap.

### WH-12 — SUBSCRIBE `prefixCount` loose byte-bound → ~8–16× heap amplification (pre-auth)
- **Source:** edge-catalog G1.
- **Where:** `EdgeFrameCodec.decodeSubscribe:699` — `prefixCount ≤ p.remaining()` (bytes, not elems),
  then `new ArrayList<>(prefixCount)`.
- **Mechanism:** min prefix = 4 bytes, so a 2 MiB frame can declare `prefixCount ≈ 2.1M` →
  `Object[~2.1M]` (~8–17 MB) before the read loop; padded with empty strings, ~0.5M `String`+`byte[0]`
  (~30 MB retained). Reachable pre-authorization (and anonymous in plaintext). Sharpest client-facing
  codec amplifier.
- **Fix:** `(long)prefixCount * 4 > remaining` pre-check (parity with cursor/shard/change decoders);
  consider a `MAX_PREFIXES` cap. Valid frames unchanged (golden stays green).

### WH-13 — Unbounded client-side snapshot-chunk accumulation → edge-node OOM
- **Source:** edge-catalog G3 (the one genuinely UNBOUNDED gap).
- **Where:** `EdgeClientCore.onSnapshotChunk` (`:541-548`, list `:191`) appends every chunk with no
  count/byte bound and without consulting `SnapshotBegin.chunkCount`; only `reassemble` caps at
  `Integer.MAX_VALUE` (~2 GiB) AFTER full accumulation (`EdgeSnapshotCodec:163-165`).
- **Mechanism:** a malicious/compromised distribution server (or plaintext) streams ≥2048×1 MiB chunks,
  driving edge heap toward 2 GiB before any check → OOM. Also verify the WATCH_SNAPSHOT_CHUNK veneer.
- **Fix:** cap `pendingChunks` at `SnapshotBegin.chunkCount` (reject the N+1-th) and accumulated bytes
  at `totalBytes` (both already on the wire) + a hard ceiling. Couples with WH-15.

### WH-08 / WH-09 — Raft identity not bound to authenticated transport (in-body id + senderId)
- **Source:** raft-catalog G-6/G-7 · threat-model/matrix G-3.
- **Where:** in-body `leaderId`/`candidateId` decoded and trusted with no cross-check to the
  authenticated `from` (`RaftMessageCodec:386,457,505`); the 4-byte `senderId` prefix
  (`RaftWireProtocol`/`TcpRaftTransport:371`, `RaftFrameDecoder:43`) is unauthenticated attacker bytes,
  yet keys witness anti-rollback tables (Gate 3c) and vote routing; comments call the prefix
  "authenticated" (`RaftMessageCodec:110-116`) — **overstatement (comment-honesty issue).**
- **Mechanism:** a cert-valid member can impersonate another member's `senderId`/in-body id, forging
  witness attestations. Raft is crash- not Byzantine-tolerant, so consensus term/log checks blunt
  impact, but the wire layer does not cross-check.
- **Fix (policy decision):** bind `senderId`/in-body id ↔ client-cert identity (per-node certs + SAN
  check), OR document precisely why the body id may differ and fix the "authenticated" comments.
  **Candidate operator-ratified decision** (deployment impact of per-node cert identity binding).

---

## Severity: LOW (consistency / hardening — batch these)

### WH-05 — `INSTALL_SNAPSHOT.offset` decoded with no negative/range check
- raft-catalog G-1. `RaftMessageCodec:508` reads `offset` raw; the *response's* `nextExpectedOffset`
  IS negative-checked (`:569-572`) — asymmetry. Downstream reassembly is contiguous-prefix + guarded
  (safe), but fix for symmetry: reject `offset < 0` at decode.

### WH-06 — Inconsistent trailing-byte strictness
- raft-catalog G-4. Only `RAFT_COALESCED_HEARTBEAT` rejects trailing bytes; `APPEND_ENTRIES`,
  `INSTALL_SNAPSHOT` (Raft) tolerate padding. Edge plane IS strict (`decode:659-662`). `CommandCodec`
  tolerates trailing. Fix: strict-end `hasRemaining()` on every fixed-shape decoder.

### WH-02 — `CommandCodec` allocations lack remaining pre-checks
- raft-catalog G-2. `decodePut/Delete` `new byte[keyLen]` (`:237,254`), `decodeBatch`
  `new ArrayList<>(count)` (`:269`) with no `declared ≤ remaining` pre-check (rely on underflow).
  keyLen is u16 (≤64 KB, bounded); used ALSO for snapshot values (no 1-MiB carrier cap). Fix: adopt
  `checkBlobLen`/`count*minSize ≤ remaining` discipline. (Necessary sub-part of WH-01's total-codec.)

### WH-03 — `CommandCodec` accepts empty/blank key on decode
- raft-catalog G-3. `decodePut:239` / `Put` record accept `keyLen=0`. Fix: reject empty/blank key.

### WH-07 — RPC `term`/`groupId` no range validation
- raft-catalog G-6. `FrameCodec:312-313` reads `term`(i64)/`groupId`(i32) raw; RPC term never
  negative-checked, `groupId` never bounded to `[0, shardCount)` at codec/demux. Fix: reject negative
  term; bound groupId before routing.

### WH-14 — NOTIFY decode doesn't enforce `MAX_NOTIFY_BATCH_BYTES`
- edge-catalog G2. 256 KiB cap is encode-only (`:341-345`); decode allows ~2 MiB (frame cap). Bounded,
  but a canonical-encoding asymmetry. Fix: enforce the 256 KiB sum on decode.

### WH-15 — SNAPSHOT_BEGIN / WATCH_SNAPSHOT_BEGIN no cross-field validation
- edge-catalog G4. `chunkCount`/`totalBytes` validated only `≥0`; never checked against the chunk
  stream. Couple with WH-13 (use them as the accumulation cap).

### WH-10 — Undecodable/dormant Raft types → per-frame `printStackTrace` log-flood
- raft-catalog G-5 · threat-model G-4. `PLUMTREE_*`/`HYPARVIEW_*`/`HEARTBEAT` accepted by
  `MessageType.fromCode` but have no codec → `default` throw → per-frame stack print
  (`RaftTransportAdapter`/`TcpRaftTransport:425-431`). Fix: rate-limited counted drop; consider
  removing the unused enum codes (a frozen-format touch — check golden fixtures).

### WH-16 — Large aggregate buffer ceiling
- threat-model G-5. `cap × max-frame` ≈ 16 GiB (Raft) / 2 GiB (edge) worst case. Bounded but generous.
  Defense-in-depth: a global in-flight-bytes ceiling, or a smaller default frame cap. Likely
  document-and-defer unless cheap.

---

## Gate 2 execution plan

1. **Analyze WH-01 reachability end-to-end** (propose→append→commit→apply + WAL replay); decide
   upstream-reject design. If it touches the append/commit contract → **STOP, present fork to operator**
   with a concrete recommendation. Otherwise implement upstream validation + total codec + deterministic
   apply policy.
2. **WH-08/WH-09 identity binding** — analyze; if binding `senderId`↔cert changes deployment, present as
   operator-ratified decision. At minimum, fix the comment-honesty overstatement now.
3. **WH-11 edge slow-loris** — add pre-SUBSCRIBE deadline (+ heartbeat-tied liveness), mirroring
   `inboundReadTimeoutMs`; prove with a slow-loris test like `InboundReadDeadlineFuzzTest`.
4. **WH-12, WH-13, WH-15** — bound SUBSCRIBE prefixCount; bound client snapshot accumulation with
   BEGIN cross-field caps.
5. **Batch the LOW consistency fixes** WH-02/03/04(=06)/05/07/14 as one "codec strictness uniformity"
   change (total CommandCodec, strict-end everywhere, negative-offset, term/groupId range, NOTIFY
   decode cap). One consistent scheme, protocol-expert enforces uniformity.
6. **WH-10** log-flood → counted rate-limited drop. **WH-16** → document/defer.
7. Every fix: prove well-formed frames byte-identical (golden green); each with a real hostile-input
   test. Merge on ACTUAL CI green.

## Prior-art grounding (see `prior-art.md`)
Lessons that shape the fixes: Kafka/PG/HTTP2 all **reject-before-allocate** with a fixed max (we match);
PG/Kafka **cap element/array counts explicitly** (WH-12 aligns); HTTP/2 CONTINUATION-flood + QUIC
anti-amplification motivate **pre-auth/pre-handshake resource discipline** (WH-11, WH-16); everyone
**fails closed on unknown version with no silent downgrade** (we match, strongly).
