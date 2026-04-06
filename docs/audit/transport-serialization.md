# Audit Report: Transport & Serialization (Phase 4)

**System:** Configd Distributed Configuration System  
**Date:** 2026-04-13  
**Auditor:** Production Readiness Review  
**Status:** PASS (2 critical findings fixed)

---

## 1. CRITICAL-1: TcpRaftTransport Wired into ConfigdServer

| Check | Result |
|-------|--------|
| TcpRaftTransport instantiated and started in ConfigdServer | **PASS** (FIXED) |

**Finding:** The TCP transport was not wired into the server -- consensus messages had no physical transport path between nodes.

**Fix applied:** `ConfigdServer.java:128-159` -- When peer addresses are configured, `TcpRaftTransport` is instantiated with the bind address and peer address map. A `RaftTransportAdapter` bridges it to the consensus-core `RaftTransport` interface. The adapter is registered as the inbound handler for incoming frames.

**Evidence:**
- `ConfigdServer.java:132-133` -- `TcpRaftTransport` created with node ID, bind address, peer addresses
- `ConfigdServer.java:134` -- `RaftTransportAdapter adapter = new RaftTransportAdapter(tcpTransport, DEFAULT_RAFT_GROUP);`
- `ConfigdServer.java:135` -- `transport = adapter;` (passed to `RaftNode` constructor at line 142)
- `ConfigdServer.java:151-159` -- Inbound handler registered and `tcpTransport.start()` called
- `ConfigdServer.java:137-139` -- Fallback no-op transport for single-node/test mode

---

## 2. CRITICAL-2: RaftMessageCodec Bridges Consensus-Core with Transport

| Check | Result |
|-------|--------|
| RaftMessageCodec encodes RaftMessage to FrameCodec.Frame | **PASS** (FIXED) |
| RaftMessageCodec decodes FrameCodec.Frame to RaftMessage | **PASS** (FIXED) |
| RaftTransportAdapter connects the two layers | **PASS** (FIXED) |

**Finding:** The consensus-core module uses sealed `RaftMessage` types; the transport module uses `FrameCodec.Frame`. There was no bridge between the two type systems.

**Fix applied:** `RaftMessageCodec.java` provides static `encode()` and `decode()` methods. `RaftTransportAdapter.java` wires them together.

**Evidence:**
- `RaftMessageCodec.java:50-59` -- `encode()` exhaustive switch over all 7 `RaftMessage` variants
- `RaftMessageCodec.java:69-83` -- `decode()` exhaustive switch over all `MessageType` enum values
- `RaftTransportAdapter.java:41-44` -- Outbound: `RaftMessageCodec.encode(message, groupId)` then `tcpTransport.send(target, frame)`
- `RaftTransportAdapter.java:54-65` -- Inbound: `RaftMessageCodec.decode(frame)` then dispatch to handler

---

## 3. RaftMessage Variant Encode/Decode Coverage

Every `RaftMessage` sealed interface variant has dedicated encode and decode paths in `RaftMessageCodec.java`:

| Variant | Encode Method | Decode Method | Result |
|---------|--------------|---------------|--------|
| `AppendEntriesRequest` | `encodeAppendEntries()` (line 87) | `decodeAppendEntries()` (line 107) | **PASS** |
| `AppendEntriesResponse` | `encodeAppendEntriesResponse()` (line 128) | `decodeAppendEntriesResponse()` (line 136) | **PASS** |
| `RequestVoteRequest` | `encodeRequestVote()` (line 146) | `decodeRequestVote()` (line 155) | **PASS** |
| `RequestVoteResponse` | `encodeRequestVoteResponse()` (line 165) | `decodeRequestVoteResponse()` (line 173) | **PASS** |
| `InstallSnapshotRequest` | `encodeInstallSnapshot()` (line 182) | `decodeInstallSnapshot()` (line 198) | **PASS** |
| `InstallSnapshotResponse` | `encodeInstallSnapshotResponse()` (line 222) | `decodeInstallSnapshotResponse()` (line 229) | **PASS** |
| `TimeoutNowRequest` | `encodeTimeoutNow()` (line 238) | `decodeTimeoutNow()` (line 244) | **PASS** |

PreVote is handled as a flag on `RequestVoteRequest`/`RequestVoteResponse`: the `preVote` boolean maps to `MessageType.PRE_VOTE` / `MessageType.PRE_VOTE_RESPONSE` at `RaftMessageCodec.java:151` and `RaftMessageCodec.java:169`.

---

## 4. Round-Trip Tests

| Check | Result |
|-------|--------|
| All 7 message variants have round-trip tests | **PASS** |

**Evidence:** `RaftMessageCodecTest.java` provides comprehensive round-trip tests:

- `AppendEntriesRoundTrip.heartbeatRoundTrip()` (line 25) -- empty entries
- `AppendEntriesRoundTrip.withEntriesRoundTrip()` (line 41) -- multiple entries including no-op
- `appendEntriesResponseRoundTrip()` (line 61) -- success case
- `appendEntriesResponseFailureRoundTrip()` (line 75) -- failure case
- `RequestVoteRoundTrip.regularVoteRoundTrip()` (line 86) -- standard vote
- `RequestVoteRoundTrip.preVoteRoundTrip()` (line 101) -- PreVote flag preserved
- `RequestVoteResponseRoundTrip.grantedRoundTrip()` (line 113) -- granted
- `RequestVoteResponseRoundTrip.preVoteResponseRoundTrip()` (line 124) -- PreVote response
- `InstallSnapshotRoundTrip.withDataRoundTrip()` (line 137) -- snapshot with data
- `InstallSnapshotRoundTrip.withClusterConfigRoundTrip()` (line 153) -- with cluster config
- `InstallSnapshotRoundTrip.emptyDataRoundTrip()` (line 165) -- edge case: empty data
- `installSnapshotResponseRoundTrip()` (line 174) -- response
- `timeoutNowRoundTrip()` (line 185) -- TimeoutNow
- `groupIdPreservedInFrame()` (line 195) -- groupId preservation
- `termPreservedInFrame()` (line 202) -- term preservation

---

## 5. MessageType Extensions

| Check | Result |
|-------|--------|
| INSTALL_SNAPSHOT_RESPONSE added (0x0F) | **PASS** |
| TIMEOUT_NOW added (0x10) | **PASS** |

**Evidence:** `MessageType.java:15-16`:
```
INSTALL_SNAPSHOT_RESPONSE(0x0F),
TIMEOUT_NOW(0x10);
```

Lookup array sized correctly at `MessageType.java:35` -- `new MessageType[0x11]` (17 entries, covering codes 0x00 through 0x10).

Full `MessageType` enum values (`MessageType.java:8-16`):

| Type | Code |
|------|------|
| APPEND_ENTRIES | 0x01 |
| APPEND_ENTRIES_RESPONSE | 0x02 |
| REQUEST_VOTE | 0x03 |
| REQUEST_VOTE_RESPONSE | 0x04 |
| PRE_VOTE | 0x05 |
| PRE_VOTE_RESPONSE | 0x06 |
| INSTALL_SNAPSHOT | 0x07 |
| PLUMTREE_EAGER_PUSH | 0x08 |
| PLUMTREE_IHAVE | 0x09 |
| PLUMTREE_PRUNE | 0x0A |
| PLUMTREE_GRAFT | 0x0B |
| HYPARVIEW_JOIN | 0x0C |
| HYPARVIEW_SHUFFLE | 0x0D |
| HEARTBEAT | 0x0E |
| INSTALL_SNAPSHOT_RESPONSE | 0x0F |
| TIMEOUT_NOW | 0x10 |

---

## 6. Frame Boundary Handling

| Check | Result |
|-------|--------|
| 4-byte length prefix enforced | **PASS** |
| Max frame size 16MB check | **PASS** |

**Evidence:**
- `FrameCodec.java:68-69` -- `encode()` writes `totalLength` as first 4 bytes (big-endian `putInt`)
- `FrameCodec.java:121-125` -- `decode()` reads and validates length: rejects if `length != data.length`
- `FrameCodec.java:115-117` -- `decode()` rejects frames shorter than `HEADER_SIZE` (17 bytes)
- `TcpRaftTransport.java:212-213` -- Inbound handler validates frame length: `if (frameLength < FrameCodec.HEADER_SIZE || frameLength > 16 * 1024 * 1024)` throws IOException on violation

The 16MB maximum frame size check prevents denial-of-service via oversized frames.

---

## 7. Endianness

| Check | Result |
|-------|--------|
| Consistent big-endian throughout | **PASS** |

**Evidence:** All serialization uses `java.nio.ByteBuffer` which defaults to big-endian (`ByteOrder.BIG_ENDIAN`). No code in the codebase calls `ByteBuffer.order(ByteOrder.LITTLE_ENDIAN)`.

Verified in:
- `FrameCodec.java:70` -- `ByteBuffer.wrap(frame)` (default big-endian)
- `RaftMessageCodec.java:92` -- `ByteBuffer.allocate(payloadSize)` (default big-endian)
- `DurableRaftState.java:129` -- `ByteBuffer.allocate(12)` (default big-endian)
- `ConfigStateMachine.java:263` -- `ByteBuffer.allocate(size)` (default big-endian)

Wire format is consistently big-endian (network byte order) across all protocol layers.

---

## Summary

| Item | Status |
|------|--------|
| CRITICAL-1: TcpRaftTransport wired into ConfigdServer | PASS (FIXED) |
| CRITICAL-2: RaftMessageCodec bridges consensus-core with transport | PASS (FIXED) |
| All 7 RaftMessage variants encoded/decoded | PASS |
| Round-trip tests for all variants (RaftMessageCodecTest) | PASS |
| MessageType extended with INSTALL_SNAPSHOT_RESPONSE (0x0F) and TIMEOUT_NOW (0x10) | PASS |
| 4-byte length prefix enforced, max frame size 16MB | PASS |
| Consistent big-endian throughout (ByteBuffer default) | PASS |

Both critical transport integration gaps (CRITICAL-1, CRITICAL-2) have been resolved. The transport layer correctly serializes all Raft message types, enforces frame boundaries, and maintains consistent endianness across the wire protocol.
