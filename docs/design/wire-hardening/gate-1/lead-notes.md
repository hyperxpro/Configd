# Wire-hardening arc — lead grounding notes (independent read)

These are the arc lead's own findings from reading the codecs directly, recorded so Gate 2
has them regardless of the specialist agents' catalogs. Cross-check against those.

## Overall posture (as-built truth, 2026-07-05)

The wire protocol is ALREADY substantially hardened by prior gates. Both codec families carry
mature discipline; this arc REFINES to the Postgres bar and closes residual gaps — it is not a
from-scratch hardening.

- **Raft plane** — `configd-transport/FrameCodec.java` (outer framing: length-bound-before-alloc,
  CRC-before-interpret, strict version tripwire, MBZ reserved-epoch fail-closed, 16 MiB cap) +
  `configd-server/RaftMessageCodec.java` (payloads: MAX_ENTRIES_PER_APPEND, MAX_COMMAND_LEN,
  MAX_SNAPSHOT_BLOB_LEN, count×record ≤ remaining pre-checks, negative guards, strict trailing-byte
  rejection on coalesced heartbeat, witness sender from authenticated transport prefix).
- **Edge plane** — `configd-distribution-service/wire/EdgeFrameCodec.java` (separate version byte,
  0x01/0x02/0x03 with per-connection version pin, CRC-before-interpret, peekLength bound-before-alloc,
  2 MiB frame cap, 1 MiB chunk cap, NOTIFY batch caps, per-field len ≤ remaining, strict trailing-byte
  rejection after payload) + `EdgeSnapshotCodec`.
- **Transport anti-exhaustion (Raft):** `RaftWireProtocol` — bounded connect/handshake timeouts,
  bounded outbound queue (drop-on-overflow), max inbound connections, inbound read deadline; Netty
  path has `IdleStateHandler`. Proven by `InboundReadDeadlineFuzzTest`.
- **Edge plane is mTLS-authenticated** (`NettyFanOutServer.newSslHandler` → `setNeedClientAuth(true)`).
  So "pre-auth" for this codebase = pre-mTLS-handshake; frames only flow after a client cert is
  verified. The "minimal-until-authenticated" property is largely met by mTLS + the pre-handshake
  connection cap (`liveConnections.incrementAndGet() > maxSessions` in `channelActive`, BEFORE the
  handshake completes).

## Candidate residual gaps for Gate 2 (lead-identified; confirm/expand with agent matrix)

1. **[EDGE / slow-loris parity] `NettyFanOutServer` pipeline has NO idle/read-deadline handler.**
   `NettyRaftTransport`, `NettyHttpApiServer`, and `NettyEdgeHttpServer` all install
   `IdleStateHandler`; the client-facing fan-out server does not (pipeline = SslHandler →
   ByteToEdgeFrameDecoder → EdgeFrameToByteEncoder → conn, see NettyFanOutServer.java:274-279).
   A peer that completes mTLS then stalls (sends nothing, or a length prefix then dribbles) parks a
   connection + up to ~2 MiB cumulator indefinitely, bounded only by maxSessions=1024. Note the
   nuance: a *legitimate* fan-out subscriber is idle by design (server pushes, client rarely sends),
   so a naive read-idle timeout is WRONG — the right control is a **pre-SUBSCRIBE handshake deadline**
   (reap a connection that has not completed its SUBSCRIBE within a bound), matching the JDK
   FanOutServer's slow-loris intent (FanOutServer.java:89, HANDSHAKE_TIMEOUT_MS). Verify JDK vs Netty
   parity here — this is the top candidate.

2. **[RAFT / strictness] `decodeAppendEntries` and `decodeInstallSnapshot` do NOT reject trailing
   bytes** after the declared entries/blobs (RaftMessageCodec.java:381-422, 500-533), unlike
   `decodeCoalescedHeartbeat` which is strict (line 340-344). Trailing garbage still passes the outer
   CRC so it is a "valid" frame; a strict codec should reject it (no smuggling channel, canonical
   encoding). Low severity, but a uniformity gap.

3. **[RAFT / strictness] `InstallSnapshot.offset` has no negative/`>=0` check at the codec**
   (RaftMessageCodec.java:508). It is an int used for chunk reassembly position; a negative offset is
   nonsensical and should be rejected at the wire, consistent with the negative-guard discipline
   applied to lengths and lastIncludedIndex. Confirm downstream reassembly (`RaftNode`) treats it
   safely regardless.

4. **[RAFT / strictness] Witness `flags` byte silently ignores unknown bits**
   (RaftMessageCodec.java:643 — `flags & WITNESS_FLAG_QUERY`). Only bit0 is defined; bits 1-7 are
   neither MBZ-checked nor rejected. For fail-closed-on-unknown uniformity, unknown flag bits should
   be rejected (or explicitly documented reserved-MBZ). Low severity.

5. **[cross-cutting] Confirm the two length conventions are documented as intentionally different:**
   Raft frame cap = 16 MiB, edge frame cap = 2 MiB; Raft uses a reserved epoch field, edge does not.
   These are deliberate (different planes, different cadences) but the RFC (Gate 5) must state both
   explicitly so a driver-writer knows which cap applies where.

## CommandCodec — the least-hardened codec (rides inside AppendEntries AND NOTIFY)

`configd-config-store/CommandCodec.java` decodes the PUT/DELETE/BATCH log-command grammar. It is
nested inside two carriers: (a) a Raft LogEntry command blob (AppendEntries payload) and (b) an edge
NOTIFY delta's mutation batch. It is noticeably less defensive than FrameCodec/RaftMessageCodec/
EdgeFrameCodec:

6. **[CommandCodec] `decodePut`/`decodeDelete` do NOT bounds-check `keyLen` against `buf.remaining()`
   before `new byte[keyLen]` + `buf.get(keyBytes)`** (lines 236-238, 253-255). keyLen is a u16 (≤64KB
   so alloc is bounded), but an over-large keyLen relative to the blob throws `BufferUnderflowException`
   rather than a clean domain error. On the EDGE path this is caught (EdgeFrameCodec.decode wraps
   decodePayload's RuntimeException → FRAME_CORRUPT). **On the RAFT apply path it may NOT be:** a hostile
   leader can place a well-framed AppendEntries entry whose cmdLen ≤ MAX_COMMAND_LEN but whose internal
   command bytes are malformed; `ConfigStateMachine` calls `CommandCodec.decode` at APPLY time. VERIFY
   whether apply wraps decode — an uncaught `BufferUnderflowException`/`IllegalArgumentException` in the
   apply loop is a fail-stop/availability bug. This is the top CommandCodec item.

7. **[CommandCodec] `decodeBatch` lacks a `count × minMutationSize ≤ remaining` pre-check** (line 263)
   — it bounds `count ≤ MAX_BATCH_COUNT=10_000` and then `new ArrayList<>(count)` (≈80 KB backing for
   a tiny blob claiming 10k) before the loop underflows. Bounded, but the count-vs-remaining pre-check
   that AppendEntries/cursor/notify all use is missing here; add for uniformity + to kill the small
   amplification.

8. **[CommandCodec] `decode` does not reject trailing bytes** after a PUT/DELETE/BATCH (line 142-158).
   Same canonical-encoding strictness gap as items 2/3 above.

VERIFY (DONE — CONFIRMED, headline finding): `ConfigStateMachine.apply()`
(configd-config-store/ConfigStateMachine.java:241) calls `CommandCodec.decode(command)` **outside**
the try/catch (the try starts at line 245, around `applySwitch`, AFTER decode). A malformed committed
command → `IllegalArgumentException` (unknown mutation/command type) or `BufferUnderflowException`
(over-large inner keyLen) propagates out of `apply()`. There is a second call site at line 673.

**Poison-pill-log-command threat:** the command bytes arrive via AppendEntries from the leader; the
AppendEntries decoder validates only the OUTER structure (cmdLen ≤ MAX_COMMAND_LEN), NOT the inner
CommandCodec grammar. A Byzantine/compromised-but-mTLS-authenticated leader (Gate 4 explicitly injects
"a hostile peer into a real cluster") can propose a command that frames cleanly but is grammatically
malformed. Once committed it is durable in the WAL; every replica re-decodes on apply AND on WAL replay
→ deterministic crash-loop across the cluster = permanent availability loss. Worst class of bug.

Gate-2 analysis owed (redteam LEAD + reliability): (a) confirm reachability end-to-end (propose →
append → commit → apply, and WAL replay); (b) determine the RIGHT defense — the proper Raft answer is
UPSTREAM rejection (validate the command grammar at propose-time and/or at AppendEntries append BEFORE
commit, so a poison pill never enters the log), NOT apply-time skipping (which is a determinism/policy
question). Making `CommandCodec.decode` total (fail-closed domain exception + full bounds discipline)
is necessary but not sufficient — the entry must be rejected before it commits. This likely needs a
frozen-format/design decision → **candidate STOP-for-operator fork** if the fix touches the append/commit
contract. Analyze first, then decide.

## Notes for later gates
- Gate 3: fuzz harnesses ALREADY exist — `FrameCodecFuzzTest`, `EdgeFrameCodecFuzzTest`,
  `InboundReadDeadlineFuzzTest`, plus property/boundary/redteam tests. Gate 3 is about COMPLETING
  coverage (every frame type, every reject path) and CI-integration, not greenfield.
- Golden-fixture gates guard byte-identity: `WireCompatGoldenBytesTest` (Raft),
  `EdgeFrameCodecGoldenFixtureTest` + V2/V3 variants (edge). Any Gate-2 fix MUST keep these green
  (well-formed frames byte-identical) — a fix that changes valid-frame bytes needs a version bump and
  is a STOP-for-operator fork.
