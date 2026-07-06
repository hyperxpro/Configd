# Gate 7 — Red-team re-attack pass (adversarial, fresh eyes)

Scope: `git diff main..HEAD` on `wire-hardening-arc` (14 commits, 63 files). READ-ONLY; no code
modified. Goal: break the fixes that survived the per-gate reviews. Re-attacked all five areas the
lead named. **One MEDIUM finding, one LOW residual. No Critical/High.** Everything else re-verified
clean; the hardening is genuinely solid.

---

## FINDING 1 — MEDIUM — In-body identity binding is bypassed on the coalesced-heartbeat path (CWE-290, identity spoofing)

- **Location:** `configd-server/src/main/java/io/configd/server/RaftTransportAdapter.java:216-220`
  (coalesced branch dispatches with **no** in-body check) vs `:228-235` (non-coalesced branch, which
  DOES enforce it). Root data source: `RaftMessageCodec.decodeCoalescedHeartbeat` →
  `configd-server/src/main/java/io/configd/server/RaftMessageCodec.java:336`
  (`NodeId leaderId = NodeId.of(buf.getInt())` — decoded from attacker wire bytes).
- **Class:** WH-09 in-body `leaderId` forgery, surviving in the coalesced heartbeat message.

### Summary
WH-08/09 bind a request's self-declared in-body `leaderId`/`candidateId` to the transport-authenticated
sender (`from`). That check lives ONLY in the `else` branch of the adapter's inbound handler
(`decode()` path, line 228). The `RAFT_COALESCED_HEARTBEAT` branch (lines 205-220) demuxes each
per-group `AppendEntriesRequest` and dispatches it via `handler.accept(from, groupId, ae)` **without**
the `enforceIdentity` check. The per-group `leaderId` inside a coalesced heartbeat is decoded straight
from attacker-controlled wire bytes (`RaftMessageCodec.java:336`), not derived from `from`.

The commit's own Javadoc asserts the opposite and is **false**:
> "The witness and coalesced-heartbeat paths already derive the sender from the authenticated `from`,
> so they need no extra check."

True for witness (`decodeWitness(frame, from)` injects `from`). **Not true** for the coalesced
heartbeat, whose `leaderId` is on the wire.

### Root cause / data flow
Byzantine-but-cert-valid peer B (senderId=B, passes the Layer-2 senderId pin on both transports) →
sends a `RAFT_COALESCED_HEARTBEAT` frame whose per-group record carries `leaderId = A` (a third,
innocent node) → `decodeCoalescedHeartbeat` faithfully returns `AppendEntriesRequest{leaderId=A}` →
adapter coalesced branch dispatches it to group A's owner **skipping** the `bodyId == from` gate that
the identical non-coalesced `AppendEntriesRequest` would hit → `RaftNode` processes a heartbeat it
believes came from leader A.

The senderId binding does **not** save you here: senderId=B is legitimate for B; the forgery is the
in-body `leaderId`, which is exactly what the (bypassed) check exists to catch.

### Reachability (live, in the config where the feature matters)
- Coalesced heartbeats are only produced/received when `shardCount > 1` (multi-Raft;
  `CoalescedHeartbeat.java:16` — single-group drains send a plain `AppendEntriesRequest`).
- A Byzantine peer requires cluster size > 1. `ConfigdServer.java:272` confirms N>1 boots.
- The gap only *matters* when the allow-list is enforced (`configd.raft.peerIdentity.allowedNodes`
  set) — precisely the posture where the regular-AppendEntries in-body forgery IS blocked but the
  coalesced one is not. So: **turning ON WH-08/09 protection leaves a hole for coalesced heartbeats.**

### Impact
A single enrolled Byzantine member can forge heartbeats as any other node:
- Adopt a forged `term` ≥ currentTerm → victim steps down, sets `currentLeader = A` (the forged id),
  resets its election timer. Flood to all nodes → cluster-wide election suppression / leadership
  confusion → **availability/liveness DoS**; clients get redirected to a non-leader A.
- Bounded by Raft safety: heartbeats are empty and commit advancement is gated by the
  prevLogIndex/prevLogTerm match, so this cannot fabricate committed state — it is a
  liveness/impersonation attack, not a data-integrity one. Same impact class as WH-09 itself
  (rated Medium in the register).

### Fix (root cause, one place)
In the coalesced branch, apply the same binding before dispatch. Every entry in a *legitimate*
coalesced heartbeat is emitted by the leader-of-those-groups = the sender, so
`ae.leaderId() == from` for all entries (verified: `encodeCoalescedHeartbeat` is fed the sender's own
per-group heartbeats, `leaderId = self`). Reject the whole frame on any mismatch:

```java
} else if (frame.messageType() == MessageType.RAFT_COALESCED_HEARTBEAT) {
    Map<Integer, AppendEntriesRequest> heartbeats =
            RaftMessageCodec.decodeCoalescedHeartbeat(frame);
    for (Map.Entry<Integer, AppendEntriesRequest> e : heartbeats.entrySet()) {
        AppendEntriesRequest ae = e.getValue();
        if (enforceIdentity && !ae.leaderId().equals(from)) {   // WH-09 parity
            transportMetrics.onPeerIdentityRejected();
            logInBodyRejectionThrottled(from, ae.leaderId(), frame.messageType());
            return; // drop the whole coalesced frame; senderId itself is already bound
        }
    }
    for (Map.Entry<Integer, AppendEntriesRequest> e : heartbeats.entrySet()) {
        handler.accept(from, e.getKey(), e.getValue());
    }
}
```

Regression test: feed a coalesced heartbeat with one record's `leaderId != from` under an enforced
policy; assert the frame is dropped and `onPeerIdentityRejected()` fired; a matching-`leaderId`
coalesced heartbeat still dispatches (valid traffic unaffected). Byte format unchanged → goldens stay
green.

---

## FINDING 2 — LOW / INFO — WH-10 log-flood anti-pattern still present in the JDK transport's handler-error catch (currently unreachable)

- **Location:** `configd-transport/src/main/java/io/configd/transport/TcpRaftTransport.java:571-579`
  — the inbound handler dispatch is inside the `while(running)` read loop; a handler
  `RuntimeException` triggers `System.err.println(...)` **plus `e.printStackTrace(System.err)` per
  frame**, then "continue reading" (connection is NOT dropped).
- **Class:** WH-10 (unbounded per-frame stack print). The Gate-1 register explicitly named
  `TcpRaftTransport` alongside `RaftTransportAdapter` as a WH-10 site; the fix (5423116) throttled the
  **adapter** but left the JDK transport's own handler-error catch as raw per-frame `printStackTrace`.

### Assessment (honest downgrade)
Currently **not reachable as a flood**: the sole registered handler is the adapter's
`registerInboundHandler` lambda, whose body is entirely wrapped in its own `catch (Exception e)`
(`RaftTransportAdapter.java:238`) that swallows + throttles and never rethrows. So `handler.onMessage`
does not propagate to line 571. Production also defaults to the Netty transport, not this JDK path.
It is a latent defense-in-depth inconsistency, not a live vector — flagged because it is the exact
anti-pattern the arc set out to eliminate and any future handler that throws would flood.

### Fix
Replace lines 573-576 with a throttled `LOG.warning` (drop the `printStackTrace`), mirroring
`RaftTransportAdapter.logDecodeDropThrottled`. Metric on every drop; log rate-limited. Trivial, no
wire impact.

---

## Re-verified CLEAN (genuinely attacked, found no gap)

**Peer-identity binding (WH-08/09) — beyond Finding 1:**
- Both JDK ingresses (`TcpRaftTransport` acceptLoop serverAccepted + outbound-reverse reader) and both
  Netty ingresses (`channelRead0` server-accepted + `PeerHandler` reverse) enforce the senderId pin.
  The F1 reverse-path bypass is genuinely closed on both tiers.
- Reverse-path pin (`dialTarget`) is sound even without trusting the peer cert: it pins the *dial
  intent*, and `createClientSocket` sets `setEndpointIdentificationAlgorithm("HTTPS")`
  (`TcpRaftTransport.java:662`) so the far end's cert must match the dialed host anyway.
- Fail-closed parity holds: `enforced() && tlsManager == null` is a hard boot error on both tiers;
  JDK denies on a null pin (`:509-510`); Netty denies on null attr (`channelRead0`) and closes on a
  handshake that resolves to no allow-listed id.
- `enforceIdentity` (adapter in-body gate) is wired to `tcpTransport.peerIdentityEnforced()`
  (`ConfigdServer.java:1835-1836`) so the senderId gate and in-body gate cannot diverge.
- `PeerIdentityPolicy`: LdapName parsing fails closed on an unparseable DN; unknown/absent marker →
  `null` → reject; separator-only allow-list throws; duplicate id throws; most-significant-RDN-first
  iteration prefers the leaf CN. No DN I could construct resolves to the wrong node or fails open.
- Handshake-timeout fix (8749d19): `setSoTimeout(HANDSHAKE_TIMEOUT_MS=2s)` immediately precedes the
  forced `startHandshake()` and restores `inboundReadTimeoutMs` after (`:471-473`) — correct, bounded,
  reaps a stalled TLS handshake.

**Poison-pill / total CommandCodec (WH-01/02/03/04):** `CommandCodec.decode` is total — every length
field is bounds-checked before allocation (`checkRemaining`), `keyLen` is a `u16` (never negative),
`valueLen`/`count` are explicitly negative- and max-checked (`CommandCodec.java:319, 340`), strict-end
`hasRemaining()` at the top boundary. The only decode call site (`ConfigStateMachine.java:243`) is
inside a `try` that catches **only** `MalformedCommandException` and returns `NON_MUTATING` — a
deterministic skip on every replica (decode is pure) → no divergence, no crash-loop. No BufferUnderflow
or NegativeArraySize can escape as a non-Malformed throwable.

**Edge anti-exhaustion (WH-11/12/13/15):**
- WH-12 prefixCount: `(long)prefixCount * 4 > remaining` (long cast binds before multiply → no
  overflow) + `MAX_PREFIXES=4096`. Sound.
- WH-11 first-frame deadline: armed on admission, disarmed on the first routed frame. Stalling *after*
  the first frame is the documented supported case (idle subscriber rides the server→client HEARTBEAT
  and is backstopped by the SlowConsumerGovernor on write backpressure) — not a bypass.
- WH-13/15 snapshot caps: `pendingChunks.size() >= pendingChunkCount` rejects the N+1-th chunk;
  `accumulatedSnapshotBytes` checked against both the BEGIN-declared `totalBytes` and the hard
  `MAX_SNAPSHOT_TOTAL_BYTES=512 MiB`; BEGIN header itself capped to the ceilings before any chunk.
  Watch-veneer claim verified: no production client-side WATCH_SNAPSHOT reassembly path exists.

**Codec strictness + WH-07 revert (WH-05/06/10/14 + be2ed37):** The WH-07 term-reject revert does
**not** reopen a hole — a negative `term` is signed-compared as always `< currentTerm (≥0)` → stale →
ignored by consensus, and `term` is never used as an allocation size or index. `INSTALL_SNAPSHOT.offset`
now negative-checked; strict-end applied uniformly; NOTIFY 256 KiB cap enforced on decode.

---

## Explicitly out of scope (pre-existing, not an arc regression)
Raft **term-inflation** (a Byzantine peer sending `term = Long.MAX_VALUE` forces adoption and wedges
the term space on overflow) exists on `main` independent of this arc and is inherent to Raft's
crash-tolerance trust model; it is not what WH-07 addressed and the revert did not introduce it. Note
for a future Byzantine-hardening effort, not a Gate-7 finding.

---

## Round 2 — re-attack of the round-1 fixes

Scope: re-attack ONLY the four code fixes in commit `63d731e` (C1-C4) with fresh eyes; confirm each is
CORRECT + COMPLETE + introduced NO new issue. READ-ONLY, no build. Verdict: **C1, C3, C4 CLEAN. C2
INCOMPLETE — one LOW finding: the strict-end sweep missed the fixed-shape witness decoder.** No fix
introduced a new race/alloc/leak.

### C1 — coalesced-heartbeat in-body identity binding (RaftTransportAdapter.java:218-238) — CONFIRMED CLEAN
- **Before any dispatch, no partial-apply:** the `enforceIdentity` check is a full pre-scan loop
  (`:226-235`) that runs to completion (or `return`s) BEFORE the separate dispatch loop (`:236-238`)
  begins. A forgery in the last map entry drops the whole frame; no earlier entry is dispatched.
- **Covers every entry:** iterates the full `heartbeats.entrySet()`; any `!bodyId.equals(from)` returns.
- **No unguarded dispatch path:** the coalesced branch's only `handler.accept` is the guarded second
  loop. The witness branch (`:203-204`) injects the authenticated `from` (body carries no sender). The
  single-message branch (`:246-254`) has its own gate. There is no third coalesced dispatch site.
- **`from` is authenticated:** it is the Layer-2 transport senderId (pinned on all four ingresses,
  re-verified round 1); the in-body gate is wired to `peerIdentityEnforced()` so it cannot diverge.
- **No false-reject of valid traffic:** a legitimate coalesced frame is one owner's own per-group
  heartbeat drain (`ConfigdServer.frameHeartbeatDrain` ← `enableHeartbeatCoalescing`,
  `ConfigdServer.java:490-492`), so every entry's `leaderId == self == from`. The whole-frame drop only
  fires on a genuine mismatch. Encode-side also rejects non-empty entries (`RaftMessageCodec.java:294`).
- **No NPE / new issue:** `leaderId()` is `NodeId.of(getInt())` — never null, so the direct
  `bodyId.equals(from)` (stricter than the single-message path's null-guard) is safe. Reject path only
  bumps a metric + throttled log and returns; connection stays (senderId already bound). No new alloc,
  no race (per-connection reader thread), decode already completed before the check.
- **Cannot still forge a per-group leaderId:** the forged `leaderId` is exactly what the gate compares
  against the authenticated `from`; a Byzantine peer B forging `leaderId=A` is dropped.

### C2 — WH-06 strict-end on fixed-size Raft decoders (RaftMessageCodec.java) — INCOMPLETE (LOW)
`rejectTrailingBytes()` was correctly added to the five enumerated decoders and is placed correctly:
- AppendEntriesResponse `:469`, RequestVote/PreVote `:490`, RequestVoteResponse `:509`, TimeoutNow
  `:640` — all fixed-shape, strict-end after the last field. Correct.
- InstallSnapshotResponse `:624` — placed AFTER the optional `nextExpectedOffset` (whose presence is
  gated by the `hasRemaining()` at `:613`), so a frame WITH and WITHOUT the optional field both decode;
  only surplus-past-the-optional is rejected. Verified both cases decode; no valid frame rejected.
- decodeInstallSnapshot `:570` and decodeCoalescedHeartbeat `:362` already had strict-end (variable
  shape, own end-handling). decodeAppendEntries `:443` already strict.

**NEW FINDING — LOW (CWE-20, missed fixed-shape decoder). `decodeWitness` still accepts trailing bytes.**
- **Location:** `configd-server/src/main/java/io/configd/server/RaftMessageCodec.java:688-701`
  (`decodeWitness`, the `RAFT_WITNESS` / `RAFT_WITNESS_REPLY` decoder).
- The witness body is a FROZEN fixed 29-byte shape (`WITNESS_BODY_LEN = 8+8+4+8+1`, `:123`; encode
  allocates exactly that, `:659`). The decoder does `checkRemaining(buf, WITNESS_BODY_LEN)` (`:690`) —
  a lower bound only — reads the 29 bytes, and returns WITHOUT a `rejectTrailingBytes()`. A cert-valid
  hostile peer can append surplus bytes to a witness frame and they are silently ignored.
- **Why it matters:** C2's own stated invariant is "now EVERY fixed-shape Raft decoder rejects trailing
  bytes." decodeWitness/WitnessReply is a fixed-shape Raft decoder and it does not — the fix's
  completeness claim is still overstated, for exactly the same class it set out to close. (The witness
  path is the multi-Raft anti-rollback AnchorWitness surface.)
- **Impact:** LOW / hardening. Trailing bytes are bounded by the frame length (no unbounded alloc), the
  frame is decoded once by one receiver (no parser differential / smuggling), and the surplus is inert.
  Impact is strictness/grammar-uniformity only — but it is the identical residual C2 was meant to
  eliminate, so it should be closed for the invariant to hold.
- **Fix (one line, same helper, no wire change):** in `decodeWitness`, after reading the flags byte
  (`:695`) and before the `return`, add `rejectTrailingBytes(buf, "Witness")`. Covers both
  WITNESS and WITNESS_REPLY (shared decoder). Regression test: a witness frame with one surplus
  trailing byte must throw; a clean 29-byte body must still decode. Byte format unchanged → goldens
  stay green.

### C3 — JDK FanOutServer absolute first-frame deadline (FanOutServer.java:514-676) — CONFIRMED CLEAN
- **Truly absolute:** `firstFrameDeadlineNanos` is fixed once at reader-loop start (`:527-528`);
  `readBounded` calls `armReadBudget` before EVERY underlying `in.read` (`:645-650`), which recomputes
  `remaining = deadline - now` and throws `SocketTimeoutException` once `remaining <= 0` (`:666-668`).
  A slow-loris dribbling >=1 byte per window is reaped at the wall-clock deadline — the budget shrinks
  monotonically and is NOT reset by partial reads. The SAME absolute deadline is passed to both the
  header read (`:586`) and the body read (`:613`), so header-then-dribble-body is also bounded.
- **Disarmed correctly:** on the first routed frame, `firstFrameRouted=true` and
  `socket.setSoTimeout(0)` (`:534-538`) BEFORE the next `readFrame(in, 0L)` switches to the unbounded
  steady-state path (`:592-603`, byte-identical to pre-C3). An established idle subscriber is NOT
  reaped; liveness rides the server→client HEARTBEAT. No stuck small-timeout can leak into steady state
  (the `0L` path never re-arms, and soTimeout was reset to 0).
- **No race:** `firstFrameRouted` and the socket are touched only by the single per-connection reader
  thread.
- **No new leak / unbounded alloc:** `peekLength` still bounds the declared length BEFORE allocation on
  both paths (`:606-607`); its `CodecException` and the `SocketTimeoutException` both land in the
  readerLoop catch (`:556-563`) → `onFirstFrameTimeout` metric + clean `close()`. `readBounded` EOF
  semantics are sound (false only on clean EOF-before-any-byte; partial-then-EOF → EOFException →
  truncated-frame teardown).

### C4 — TcpRaftTransport throttled logger (TcpRaftTransport.java) — CONFIRMED CLEAN
- **No unbounded log path remains:** all three former `System.err` sites — wire-version mismatch
  (`:581-588`), decode failure (`:591-597`), and the per-frame handler-error `printStackTrace`
  (`:610-620`) — now route through `logInboundFailureThrottled` (`:296-307`): 1/sec CAS gate, suppressed
  counter reported+reset on the emitting line, stack trace dropped (bounded line, mirrors the adapter).
- **Lazy message build:** callers pass `LongFunction<String>` lambdas evaluated only inside the emitted
  `LOG.warning(() -> …)`, so the suppressed path pays no per-frame string-concat cost.
- **No behavioral change:** decode-failure paths still `return` (drop connection); the handler-error
  path still "continue reading" (framing intact). Only logging changed.
- **No new race of consequence:** the throttle state is shared across per-connection reader threads;
  the CAS elects one emitter per interval and losers increment the suppressed counter. The
  `getAndSet(0)` vs concurrent `incrementAndGet` can undercount suppressed by a few — benign log
  accounting only, no correctness/security impact.

### Did any fix introduce a NEW issue?
No. C1/C3/C4 are regression-free (no new unbounded alloc, no new state-leaking reject, no new race, no
broken invariant). C2's only defect is INCOMPLETENESS (the missed witness decoder above), not a
regression — the five decoders it did touch are correct and reject no valid frame.

### Round-2 verdict
- **C1 — CONFIRMED CLEAN.**
- **C2 — INCOMPLETE:** one LOW residual (`decodeWitness` fixed-shape decoder still accepts trailing
  bytes; one-line `rejectTrailingBytes` fix). The five touched decoders are correct.
- **C3 — CONFIRMED CLEAN.**
- **C4 — CONFIRMED CLEAN.**
