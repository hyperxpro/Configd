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
