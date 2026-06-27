# Multi-Raft Phase 1 — Seam F: the `WIRE_VERSION 0x01→0x02` bump (D1 epoch + D2 CoalescedHeartbeat)

> ONE wire-format major-version bump covering **both** operator-settled wire decisions (charter §2):
> **D1** — reserve an 8-byte epoch field in the frame header; **D2** — define the CoalescedHeartbeat
> frame. **Both dormant at N=1.** Atomic, protocol-critical, four-way. N=1 frames are byte-identical to
> v1 **except** the version byte (`01→02`) and the 8 reserved epoch bytes (all zero).

## 0. Operator decisions honoured (charter §2, SETTLED)

- **D1 epoch field: RESERVE NOW**, dormant. Encode `0`, decode-but-ignore (forward-compatible: a
  future v2.x sender that populates epoch is still decodable by this v2.0 receiver — so *activating*
  epoch later needs **no further wire bump**).
- **D2 CoalescedHeartbeat frame: DEFINE NOW**, dormant, **SAME** bump as D1 — ONE wire break covers
  both. Not two.
- Backward/forward compat: a **clean cutover**. The decoder is a strict single-version tripwire
  (`UnsupportedWireVersionException` on any byte ≠ `WIRE_VERSION`); there is no v1/v2 negotiation
  (that waits for the ADR-0030+ peer Hello). There are **no external deployments** of v1, so the
  cutover is total and sanctioned: every node moves to v2 together. Recorded here explicitly.

## 1. The v2 frame layout

```
  [Length:  4 bytes]                       (unchanged — total frame size, big-endian)
  [Version: 1 byte]   01 -> 02             (the intended change)
  [Type:    1 byte]
  [GroupId: 4 bytes]
  [Term:    8 bytes]
  [Epoch:   8 bytes]  NEW (D1) — RESERVED  (MBZ on send; ignored on receive)
  [Payload: variable]
  [CRC32C:  4 bytes]                        (recomputed over the new byte sequence)
```

`HEADER_SIZE 18 → 26` (`4+1+1+4+8+8`). Every frame grows 8 bytes — the **one sanctioned exception**
to "N=1 wire bytes identical" (charter §2 D1). N=1 *behaviour* is unchanged; the epoch bytes are zero
and ignored, so no peer's decode logic observes anything but the larger frame + the version byte.

### Why the epoch is NOT added to the `FrameCodec.Frame` record (DL-F-01)

A reserved/MBZ field that no caller consumes yet should not widen the public `Frame` record — that
record has **60 construction sites** (mostly tests; census in the decision log), and exposing a field
nobody reads is premature plumbing + a 60-site blast radius for zero behavioural gain. Instead:
`encode(type, gid, term, payload)` writes the 8 zero bytes internally; `decode` skips them
(decode-but-ignore). When epoch *activates* (DL-P1-04, a future operator decision), that session adds
the `Frame.epoch()` accessor + an `encode` overload — a purely additive change that needs **no wire
bump** because the bytes are already reserved on the wire. This is the textbook protocol-reserved-field
pattern (cf. reserved bits in TCP): allocate the bytes now, thread the API when used.

## 2. D2 — the CoalescedHeartbeat frame

`MessageType.RAFT_COALESCED_HEARTBEAT(0x11)` (`BY_CODE` resized `0x11 → 0x12`). The wire form the
Phase-0 M3 heartbeat coalescer needs once N>1 puts more than one group on an owner that heartbeats the
same peer in a tick (`CoalescedHeartbeat` / `CoalescedHeartbeatTransport` in consensus-core).

### Payload codec — `RaftMessageCodec.{encode,decode}CoalescedHeartbeat` (DL-F-02)

Home: `RaftMessageCodec` (already THE consensus payload↔Frame codec; reuses its `checkRemaining` /
bounds idioms). `CoalescedHeartbeat` is deliberately **not** a `RaftMessage`, so it does not go through
the sealed `encode`/`decode` switch — it gets sibling methods. The `decode(Frame)` switch gains an
explicit `case RAFT_COALESCED_HEARTBEAT ->` that throws a *directional* error ("decode via
decodeCoalescedHeartbeat()"), so a misroute is loud, not a silent `default`.

Payload (all big-endian), a **count-bounded fixed-size-record** format:

```
  [Count: 4 bytes]                          (number of groups, n)
  n × {
    [GroupId:      4 bytes]
    [Term:         8 bytes]
    [LeaderId:     4 bytes]
    [PrevLogIndex: 8 bytes]
    [PrevLogTerm:  8 bytes]
    [LeaderCommit: 8 bytes]
  }                                          (40 bytes/group — COALESCED_GROUP_RECORD)
```

- `from` (the sending node) is **not** in the payload — the transport already carries it as the
  4-byte sender-id prefix (`RaftWireProtocol`); the inbound side gets it from the `InboundMessage`.
- Each coalesced entry is a **heartbeat = empty `AppendEntriesRequest`** (M3 contract). The encoder
  **rejects** a non-empty AE ("only empty heartbeats may be coalesced") rather than silently dropping
  entries; the decoder reconstructs each AE with `List.of()` entries.
- Frame-header `groupId`/`term` are **sentinels (0)** — the real per-group ids/terms live in the
  payload; the inbound demux dispatches by *type*, never by `frame.groupId()`, for this type.

### Adversary bounds (count-bounded, DL-F-02)

- `MAX_COALESCED_GROUPS = 1024` — a hard cap on `n`. Production ceiling is the shard count (≤16);
  1024 is generous headroom that still bounds allocation. Reject `n<0` and `n>max`.
- `(long) n * COALESCED_GROUP_RECORD > buf.remaining()` pre-check — a tiny hostile frame cannot
  declare a huge `n` to force a big map allocation (mirrors `MAX_ENTRIES_PER_APPEND`).
- **Reject duplicate group ids** and **reject trailing bytes** after the n records — strict wire
  hygiene (a well-formed coalesced HB has distinct groups and no padding).

## 3. Send + receive wiring (dormant at N=1)

> **"Dormant at N=1" means EMISSION is dormant, not that the code is unreachable.** At N=1 the send
> drain never produces a coalesced frame (one group per peer → plain AppendEntries). The *receive*
> decode branch, however, is reachable in production today: a hostile/buggy peer can send a `0x11`
> frame regardless of local N. That is fine — the decoder is fully bounds-hardened (DL-F-02) and each
> demuxed heartbeat goes through the same unregistered-group drop as any AppendEntries, so an
> unexpected coalesced frame is trust-equivalent to a plain heartbeat, never a new hole.


- **Send** (`ConfigdServer` `enableHeartbeatCoalescing` drain): `groupHeartbeats.size() == 1` → the
  single normal `AppendEntries` frame (the **only** case at N=1 → wire byte-identical); `> 1` → ONE
  `encodeCoalescedHeartbeat` frame. The `>1` branch is unreachable at N=1 (one group per peer).
- **Receive** (`RaftTransportAdapter.registerInboundHandler`): on `RAFT_COALESCED_HEARTBEAT`,
  `decodeCoalescedHeartbeat` then dispatch **each** group's heartbeat via the existing per-group
  `InboundHandler.accept(from, gid, ae)` — so every group's AE is marshalled onto **its own** owner
  executor (R-01′), goes through the Seam-C unregistered-group drop, and the RR-008 throwable guard.

### Why NOT `driver.routeCoalescedHeartbeat` on the inbound thread (DL-F-03 — the threading trap)

The handoff sketched "inbound demux → `driver.routeCoalescedHeartbeat`". That method calls
`routeMessage(gid, ae)` in a loop, and `routeMessage` runs `node.handleMessage` **on the calling
thread** for a non-rehomed group (every production group). `handleMessage` asserts the owner thread
(`RaftNode.assertOwnerThread()`), and a coalesced frame can bundle groups with **different** owners at
N>1. Calling it on the single Netty inbound thread would therefore run `handleMessage` off-owner for
all but one group — firing the assertion / racing the explicitly non-synchronized `RaftNode`
(ADR-0009). The existing test proves the *demux logic* only because its groups all share owner 0.
The correct production path demuxes at the adapter and routes each group through the per-group owner
hop. `routeCoalescedHeartbeat` stays as the sim/single-owner-test helper.

## 4. wire-compat gate (intentional bump)

`WireCompatGoldenBytesTest` encodes one frame per `MessageType` and asserts byte-equality vs
`GoldenFixtures.forVersion(WIRE_VERSION)`. The CI `wire-compat` job fails a `GoldenFixtures.java`
byte change **unless** `FrameCodec.WIRE_VERSION` also changed — so this bump is detected as
**intentional**. Actions: `GoldenFixtures` gains `v2()` (regenerated via `WireFixtureGenerator`),
`forVersion` routes `2 → v2()`; v1 is superseded (clean cutover, unreachable — the codec rejects v1).
`raft_coalesced_heartbeat.bin` joins the set with the generic 4-byte fixture payload (a frame-level
fixture — payload *semantics* are pinned by the dedicated codec tests, not the frame golden).

## 5. Four-way + N=1 byte-identity

- N=1 byte-identity re-proven: a v1 frame and its v2 counterpart differ **only** by the version byte
  and the 8 zero epoch bytes — asserted by a dedicated test that strips those and compares.
- Implementer → diff-review → independent clean re-run → adversarial red-team → fresh-context Verifier
  (APPROVE-0-must-fix) before the PR. Decisions logged in `server-wiring-decision-log.md` (DL-F-*).
