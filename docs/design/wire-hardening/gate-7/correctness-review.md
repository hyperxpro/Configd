# Gate 7 — holistic correctness / completeness / quality review

Lead's deep final read of the full wire-hardening arc (`git diff main..HEAD`, 14 commits, 63 files).
Fresh distinguished-engineer eyes on CORRECTNESS, COMPLETENESS, and QUALITY — beyond the per-gate and
security-only passes. READ-ONLY (no code changed).

**Verdict: the wire is sound and substantially complete.** The two HIGH-risk mechanisms flagged for this
pass (the WH-01 deterministic apply-skip + signing refactor, and the WH-08/09 additive identity-binding
constructors) are correct and byte-faithful. Two genuine COMPLETENESS gaps and one idiom nit remain, all
LOW–MEDIUM and none production-critical. Details and the verification trail below.

---

## Findings (ranked)

### F1 — MEDIUM (completeness + comment-honesty) · WH-06 strict-end is incomplete vs. its own uniformity claim
`configd-server/.../RaftMessageCodec.java`

WH-06 was applied to `decodeAppendEntries` (`:428`) and `decodeInstallSnapshot` (`:552`), with comments
asserting the check is "uniform with the coalesced heartbeat **and the edge plane**" and that "a hostile
peer cannot smuggle bytes past the grammar." But **five pure fixed-shape Raft decoders still silently accept
trailing bytes** (they gate a *lower bound* via `checkRemaining` but never reject a *surplus*):

- `decodeAppendEntriesResponse` (`:448`, reads 13B)
- `decodeRequestVote` (`:468`, reads 20B — a **request-side fixed-shape** decoder)
- `decodeRequestVoteResponse` (`:487`, reads 5B)
- `decodeTimeoutNow` (`:614`, reads 4B — a **request-side fixed-shape** decoder)
- `decodeWitness` / `decodeWitnessReply` (`:659`, reads 29B `WITNESS_BODY_LEN`)

The edge plane *is* strict on every frame; these five are not. `workstream-D-codec-strictness.md` narrowed
WH-06 to "only the request-side fixed-shape decoders," yet `decodeRequestVote` and `decodeTimeoutNow` **are**
request-side fixed-shape and were skipped — so the request side is itself inconsistent, not a defensible
line. The only decoder with a *justified* trailing-tolerance is `decodeInstallSnapshotResponse` (a genuine
optional `nextExpectedOffset` field, correctly documented).

- **Impact: LOW security, real consistency/comment-honesty defect.** Trailing bytes are bounded by the
  frame length prefix, cause no allocation amplification (fixed reads), and cannot desync (frames are
  length-delimited). No exploit. But the code claims a uniformity it does not deliver, and the Gate-3 fuzz
  oracle (`RaftMessageCodecFuzzTest:150-162`) deliberately tolerates trailing bytes ("either accepted or
  rejected"), so nothing catches it.
- **Fix (cheap, byte-safe):** add `if (buf.hasRemaining()) throw new IllegalArgumentException(...)` to the
  five decoders. Byte-identity is preserved — every encoder allocates exactly the fixed payload size, so no
  valid frame carries trailing bytes; `WireCompatGoldenBytesTest` checks *encode* bytes, not decode
  tolerance, so goldens stay green. Then tighten the fuzz oracle to *assert* rejection for these types.
  Alternatively (weaker): soften the two "uniform with the edge plane" / "smuggle bytes past the grammar"
  comments to state the deliberate narrow scope and why these five tolerate trailing — but making them
  strict is the consistent, correct choice and closes the claim honestly.

### F2 — LOW–MEDIUM (completeness / transport-parity) · JDK FanOutServer WH-11 deadline is per-read, not absolute
`configd-server/.../fanout/FanOutServer.java:519,530`

The production-default Netty path (`NettyFanOutServer`) implements WH-11 as a **one-shot scheduled reap**
armed at admission and cancelled on the first routed frame — a true absolute pre-SUBSCRIBE deadline
(event-loop-serialized; no arm/disarm race — verified). The JDK `FanOutServer` instead arms
`socket.setSoTimeout(firstFrameDeadlineMs())`, a **per-read** timeout that resets on every successful read.
`readFrame` (`:570`) does `in.readInt()` + `in.readFully(...)`, so a slow-loris that dribbles ≥1 byte per
(deadline − ε) of a *never-completing* first frame keeps resetting the timer and holds a session slot far
past `firstFrameDeadlineMs` (bounded only by the declared frame length ≤ max-frame, and by `maxSessions`).
This is the exact resource-hold WH-11 set out to prevent, defeated on the JDK transport.

- **Impact: LOW.** `FanOutServer` is *retained but not the production default* (`ConfigdServer:1122` wires
  `NettyFanOutServer`); the hold is bounded by `maxSessions`. But it is a real transport-equivalence gap —
  the shared docs/comments describe an absolute "first-frame deadline" the JDK path does not enforce.
- **Fix:** in `readerLoop`, track an absolute deadline armed at loop start (`long deadlineNanos =
  System.nanoTime() + ms*1e6`) and, before completing the first routed frame, fail if
  `System.nanoTime() > deadlineNanos` (or bound cumulative pre-first-frame read time) rather than relying on
  per-read `setSoTimeout`. The `setSoTimeout(0)` disarm on first frame (`:530`) is otherwise correct.

### F3 — LOW (idiom) · TcpRaftTransport inbound path still uses raw `System.err` + `printStackTrace`
`configd-transport/.../TcpRaftTransport.java:547,554,576,588-597`

The WH-10 sweep converted `RaftTransportAdapter`'s decode-drop to a counted, rate-limited `Logger`. The JDK
`TcpRaftTransport` inbound reader still prints raw `System.err.println` per connection-drop and
`e.printStackTrace(System.err)` on a handler throw (`:576`) — inconsistent with the discipline the arc
established.

- **Not a hostile-reachable flood** (verified): the decode-failure paths (`:547/:554`) `return` = drop the
  connection, so one line per *reconnect* (mTLS-handshake-gated), not per frame; and the handler-error catch
  (`:571`) can no longer fire on hostile input because the adapter now self-swallows decode failures
  post-WH-10 — it fires only on a genuine handler bug. JDK transport is not the production default.
- **Fix (optional, low priority):** route these through a rate-limited `Logger` for uniformity, or leave as
  a documented JDK-transport nit. No security consequence.

---

## What was verified sound (no finding)

- **WH-01 deterministic apply-skip (correctness + completeness).** `ConfigStateMachine.apply` catches *only*
  `MalformedCommandException` and returns `NON_MUTATING`; `RaftNode.applyCommitted:2749-2757` treats that
  identically to a no-op (advances `lastApplied`, surfaces `currentAppliedSequence()`), so every replica
  skips the identical entry deterministically — no crash, no wedge, no divergence. **Completeness checked:**
  the only non-`Malformed` throws reachable inside `applySwitch` are the store's internal
  `sequence <= currentSnapshot.version()` monotonicity guards (`VersionedConfigStore:90/110/133`), which are
  never attacker-reachable (`seq = prevSeq + 1` is always monotone). The *one* content-reachable
  `IllegalArgumentException` (a blank key, previously thrown deep in apply) was correctly pulled forward into
  `CommandCodec.readKey` so it now surfaces as `MalformedCommandException` at the guarded decode site. The
  poison-pill surface is fully closed.
- **WH-01 signing refactor (byte-identity).** The former second decode site inside `canonicalize` was
  removed; the single `decoded` instance from the apply-boundary is threaded through `signCommand` →
  `canonicalize`. Byte-identical on the happy path (same decoded object, same canonical switch), and the
  malformed-decode guard now lives at exactly one site.
- **WH-08/09 identity binding (both transports, both layers).** Legacy 5-arg constructors delegate to the
  fuller constructors with `PeerIdentityPolicy.unenforced()` + `RaftTransportMetrics.NOOP` — behaviour- and
  byte-identical, enforcement fully dormant until an allow-list is supplied. Layer-1 (cert→NodeId pin on
  handshake) and Layer-2 (`senderId` prefix + in-body `leaderId`/`candidateId`) are enforced consistently
  across JDK and Netty; the outbound-reverse path binds the dialed target (closes the F1 reverse bypass). The
  soTimeout dance on the JDK accept path (`:471-473`) correctly restores `inboundReadTimeoutMs` (pre-set in
  `acceptLoop:417`). `PreVote` is a flag on `RequestVoteRequest`, so `inBodyRoutingId` covers it. `start()`
  fails closed when an allow-list is set without mTLS (parity on both transports).
- **Wiring agreement.** `ConfigdServer` builds one `PeerIdentityPolicy.fromSystemProperties()`, passes it to
  the transport, and derives the adapter's `enforceIdentity` from `tcpTransport.peerIdentityEnforced()` — so
  both layers provably agree. All three rejection sites share `ServerRaftTransportMetrics` →
  `configd_raft_peer_identity_mismatch`.
- **WH-07 revert + real content.** `FrameCodec.decode` correctly reverted to opaque pass-through of `term`/
  `groupId` (only negative *payload length* rejected) — the right layer call, documented. WH-07's real
  content (unregistered-group drop) is enforced at the demux (`ConfigdServer:2182`, membership-check-drop),
  which is stronger than a numeric `[0,shardCount)` bound.
- **WH-10 (adapter), WH-12, WH-13, WH-14, WH-15.** All correct: rate-limited counted drop; tight
  `prefixCount*4 <= remaining` + `MAX_PREFIXES`; chunk-count-before-add + byte sum capped to
  `min(declared, ceiling)`; NOTIFY decode cap; BEGIN cross-field caps. The WATCH-snapshot veneer has no
  client-side reassembly, so WH-13 needs no second site.
- **WH-16 defer.** Correctly documented as an operator sizing note (RFC §F10-2a): aggregate bound =
  (session cap) × (max frame), no global cross-connection counter (which would add a hot shared atomic per
  frame for a bound already reachable via `maxSessions`). Right call.
- **Metric plumbing.** New counters (`command.malformed`, `raft.peer.identity.mismatch`,
  `raft.decode.dropped`, `edge.snapshot_chunks_rejected`, `edge.fanout.first_frame_timeouts`) are wired
  correctly across catalog (`ConfigdMetrics`, eager-created → `_total 0`) + SPI (default no-op methods) +
  bridge (`ServerStateMachineMetrics` / `ServerRaftTransportMetrics` / `RegistryFanOutSessionMetrics` /
  `EdgeNodeMetrics`).
