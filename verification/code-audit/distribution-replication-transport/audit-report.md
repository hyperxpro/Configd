# Adversarial Code Audit: Distribution / Replication / Transport Modules

**Date:** 2026-04-13
**Auditor:** Blue Team code-line-auditor (Claude Opus 4.6)
**Scope:** 24 production source files across 3 modules
**Methodology:** Line-by-line manual review with adversarial assumptions

---

## Summary

| Severity | Count |
|----------|-------|
| Critical | 12    |
| High     | 18    |
| Medium   | 14    |
| Low      | 5     |

---

## Module 1: configd-distribution-service

### PlumtreeNode.java

```
PlumtreeNode.java:81:Medium: receivedMessages uses LinkedHashMap with removeEldestEntry, but eviction is by insertion order not by version/timestamp. Under re-delivery or GRAFT repair, a recently inserted old message ID can evict a newer one, causing the newer message to be re-delivered as "new" (duplicate delivery).

PlumtreeNode.java:123-128:High: broadcast() does not check whether eagerPeers is empty. If the active view is empty (all peers failed), the broadcast succeeds silently with no delivery to anyone. No warning, no fallback, no buffering. Message is effectively lost to remote nodes.

PlumtreeNode.java:143-146:Medium: On duplicate receive, a PRUNE is sent to the sender. But if the sender is not in eagerPeers (e.g., already in lazyPeers or unknown), the PRUNE is still sent. The receiving end of the PRUNE (receivePrune) is safe (remove returns false), but this generates unnecessary network traffic.

PlumtreeNode.java:155-165:Critical: receiveEagerPush forwards to eagerPeers and sends IHAVE to lazyPeers while iterating over them. If the callback that processes the outbox triggers a peer addition/removal (e.g., via HyParView listener), this would cause ConcurrentModificationException. The class documents "single-threaded" access but the outbox is drained externally, making re-entrant calls possible.

PlumtreeNode.java:208-227:High: tick() creates a new HashSet on every call (line 209). Under high tick frequency (1ms ticks), this is a hot-path allocation. Should preallocate or use a reusable list.

PlumtreeNode.java:232-236:Medium: drainOutbox() copies the entire outbox into a new LinkedList on every drain. This allocates on the hot path. Should swap references or return-and-clear.

PlumtreeNode.java:60:High: outbox (LinkedList) grows without bound. There is no backpressure mechanism. If the network is slow and broadcasts are fast, the outbox accumulates unbounded entries, leading to OOM. No documented overload behavior.

PlumtreeNode.java:199-202:Critical: receiveGraft unconditionally adds the sender to eagerPeers without checking maxActiveSize or consulting HyParView. A malicious or buggy peer can repeatedly send GRAFT messages to inflate the eager set far beyond the intended spanning tree fan-out, breaking the O(N) broadcast property and potentially causing broadcast storms.
```

### HyParViewOverlay.java

```
HyParViewOverlay.java:254-256:Critical: promotePassivePeer() promotes a peer from passive to active and sends a Neighbor message, but does NOT verify the peer is actually reachable. If all passive peers are stale/dead, the active view fills with unreachable nodes, and Plumtree broadcasts silently fail. There is no liveness check before promotion.

HyParViewOverlay.java:183-189:High: peerFailed() adds the failed peer BACK to passiveView (line 188). A peer that has genuinely crashed will keep cycling between passive-promote-fail-passive. This prevents the passive view from being consumed, but the failed peer poisons the passive view indefinitely. No TTL or failure counter on passive entries.

HyParViewOverlay.java:119-124:Medium: receiveJoin forwards ForwardJoin to ALL active peers. In a cluster of N nodes, a single join generates O(activeView) ForwardJoin messages per hop. With default TTL=shuffleTtl (up to 6), this can create O(activeView^TTL) messages -- exponential amplification. No rate limiting on joins.

HyParViewOverlay.java:224-237:Critical: addToActiveView evicts a random active peer when full. The evicted peer receives a Disconnect, but if the Disconnect message is lost (network partition), both sides believe the connection is active. The evicted peer's Plumtree eager set still includes this node, leading to one-way message flow and potential message loss.

HyParViewOverlay.java:88:Low: activeView is a HashSet, meaning iteration order is non-deterministic. This makes debugging and reproducibility harder. Consider LinkedHashSet (used in PlumtreeNode).

HyParViewOverlay.java:265-274:Medium: samplePassiveView uses ArrayList.remove(idx) which is O(n) per removal. For large passive views and large shuffle sizes, this is O(shuffleLength * passiveSize). Fisher-Yates shuffle would be more efficient.

HyParViewOverlay.java:68:High: outbox (LinkedList) has no size bound. Same backpressure concern as PlumtreeNode -- unbounded growth under network slowness. No documented overload behavior.
```

### SlowConsumerPolicy.java

```
SlowConsumerPolicy.java:120-131:Critical: State transitions from HEALTHY->SLOW->DISCONNECTED happen only inside recordSend(). If no messages are being sent (low traffic period), a consumer can remain in SLOW state indefinitely even though the slowThresholdMs has long elapsed. The timeout check requires message sends to progress, which is a design flaw -- time-based transitions should be evaluated on tick/poll, not piggy-backed on sends.

SlowConsumerPolicy.java:137-148:Critical: state() transitions from DISCONNECTED to QUARANTINED, but the transition direction is wrong. Per the class Javadoc (line 23), the flow is DISCONNECTED -> QUARANTINED. But the check on line 142 says "if disconnectedDuration > quarantineMs, move to QUARANTINED". This means a consumer stays DISCONNECTED for the quarantine period, then becomes QUARANTINED -- but QUARANTINED is supposed to be a time-limited state that eventually allows readmission. As written, QUARANTINED is a terminal state with no automatic expiry. A quarantined consumer can never automatically recover; it requires explicit readmit().

SlowConsumerPolicy.java:99-107:Medium: recordAck resets to HEALTHY even from DISCONNECTED or QUARANTINED state. This bypasses the quarantine entirely -- a misbehaving consumer can send a single ack to escape quarantine, defeating the purpose of the cooling-off period.

SlowConsumerPolicy.java:54:High: consumers HashMap grows without bound. There is no eviction of old/unregistered consumers. If consumers register and never unregister (e.g., crash without clean disconnect), the map accumulates zombie entries.

SlowConsumerPolicy.java:113-131:High: pendingCount only increments (in recordSend) and resets only on recordAck (line 103). There is no mechanism to decrement pending count when a consumer acknowledges individual messages. The count is effectively "total messages ever sent since last full ack", not "currently in-flight". This misrepresents the actual backlog.
```

### SubscriptionManager.java

```
SubscriptionManager.java:112-123:High: matchingNodes iterates ALL entries in prefixIndex for every mutation key. With P prefixes and K keys per mutation, this is O(P*K). No trie or sorted structure for prefix matching. For large subscription counts, this becomes a latency cliff on the hot path.

SubscriptionManager.java:90-103:Medium: unsubscribeAll iterates over prefixes while modifying prefixIndex via remove (line 99). This is safe because it iterates the previously-removed set, but worth noting.

SubscriptionManager.java:27-29:Medium: subscriptions and prefixIndex are synchronized data structures (via single-threaded access contract), but there is no protection if the contract is violated. No assertion or check.
```

### FanOutBuffer.java

```
FanOutBuffer.java:33-41:Critical: append() is NOT atomic. Lines 36-39 perform: (1) write to ring slot, (2) increment head, (3) update tail. Between steps 1 and 2, a concurrent reader calling deltasSince() sees the old head and misses the new entry. Between steps 2 and 3, a reader sees the new head but the tail hasn't caught up, potentially reading a stale slot that's about to be overwritten. The comment says "volatile write publishes the slot content" but the ring.set() on line 36 and the head write on line 37 are NOT a single atomic operation. A reader can observe head=N+1 but the slot at N still contains the OLD value if the CPU reorders the ring.set and the volatile head write.

FanOutBuffer.java:43-54:Critical: deltasSince() reads tail then head (lines 44-45), but between these two reads, multiple appends could occur, moving both head and tail. If tail advances past the reader's snapshot of currentTail, the reader will read overwritten ring slots (stale data). This is a classic TOCTOU race in a lock-free ring buffer. The single-writer claim does not protect the reader.

FanOutBuffer.java:49:High: The filter `delta.fromVersion() >= fromVersion` skips deltas whose fromVersion < the requested version. But this means if a consumer asks for "everything since version 5" and the buffer contains a delta that transforms version 3->6, it will be SKIPPED (fromVersion=3 < 5) even though the consumer needs version 6. The consumer misses this delta and has a permanent gap. The correct check should be `delta.toVersion() > fromVersion` or equivalent.

FanOutBuffer.java:56-60:Medium: latest() reads head once but does not read tail. If the buffer is empty (head==tail), it returns null. But if between the head read and the tail read the buffer wraps, the returned slot could be stale.

FanOutBuffer.java:25-31:High: No maximum payload size enforcement. A single ConfigDelta could be arbitrarily large. Combined with the ring buffer, this means the buffer's memory usage is O(capacity * max_delta_size), which is unbounded.
```

### WatchCoalescer.java

```
WatchCoalescer.java:90-91:Medium: version monotonicity check (line 90) uses <= but only when pending is non-empty. When pending is empty, any version is accepted including version 0. This allows the first event in a batch to have any version, potentially non-monotonic relative to a previously flushed batch.

WatchCoalescer.java:97-101:Low: Lazy initialization of batchStartNanos only when pending is empty. Uses clock.nanoTime() which is correct. No issue.

WatchCoalescer.java:85-102:High: add() does not enforce any bound on the pending list. If shouldFlush() is never called (caller bug) or if mutations arrive faster than they are flushed, the pending list grows without bound. No documented overload behavior for this path.
```

### WatchEvent.java

```
WatchEvent.java:38-58:Medium: The compact constructor accepts null for affectedKeys and computes it. This is a non-standard record pattern -- callers might not expect null to be valid for a record component. The two-argument constructor (line 64) passes null explicitly, which works but is fragile.

WatchEvent.java:43-44:Low: version validation rejects 0. But version 0 could be a valid initial version depending on the store implementation. This is a design choice, not a bug.
```

### WatchService.java

```
WatchService.java:262-289:Critical: dispatchEvent iterates watches.values() and calls watch.listener().onEvent() (line 285). If the listener callback calls cancel() on its own watch ID, this modifies the watches HashMap during iteration, causing ConcurrentModificationException. Even though it's single-threaded, re-entrant modification during iteration is a correctness violation.

WatchService.java:271-273:High: The cursor is advanced (line 272) BEFORE checking if matching mutations are empty (line 274). This means even if a watcher has no matching mutations in this event, its cursor still advances. This is correct for preventing re-delivery, but it means a watcher can silently skip over events that contain mutations for OTHER prefixes. If the watcher's prefix is later broadened (not supported in current API), historical events are lost.

WatchService.java:209-220:High: tick() calls coalescer.shouldFlush() then coalescer.flush(). Between these two calls (even in single-threaded execution), there's a logical gap: shouldFlush() may return false because the time window hasn't elapsed, but mutations keep accumulating. If tick() is called infrequently, mutations can pile up in the coalescer without bound. The coalescer's pending list has no upper bound check.

WatchService.java:150:Medium: nextWatchId is a long starting at 1. With 2^63 possible IDs and single-threaded access, overflow is theoretical but unhandled. After Long.MAX_VALUE, the next ID is Long.MIN_VALUE (negative), which fails the id>0 check in the Watch constructor.
```

### CatchUpService.java

```
CatchUpService.java:100-118:Critical: resolve() calls snapshotProvider.currentSnapshot() inside the while loop on EVERY iteration (line 114). If the snapshot changes between iterations (new commits), the target version moves, and the catch-up loop may never terminate or may assemble an inconsistent delta chain that spans two different snapshot versions.

CatchUpService.java:107-109:High: The delta chain breaks on the first gap (`delta == null`). But this means if deltas are recorded out of order (possible if recordDelta is called from different sources), a valid chain might exist but not be found because an intermediate version was not yet recorded. Falls back to expensive full snapshot unnecessarily.

CatchUpService.java:159-169:High: trimHistory() iterates ALL keys to find the minimum on every call to recordDelta. This is O(n) per insertion, making the overall cost O(n^2) for n deltas. Should use a TreeMap or maintain an explicit oldest pointer.

CatchUpService.java:53:Medium: deltaHistory is a HashMap keyed by fromVersion. If two deltas have the same fromVersion (possible during compaction or if the store produces multiple deltas from the same base version), the second overwrites the first, silently losing a delta chain path.

CatchUpService.java:57-58:Medium: catchUpNodes is never cleaned up except inside resolve() on successful completion. If resolve() is never called for a node (e.g., it disconnects permanently), the catchUpNodes map leaks entries indefinitely.
```

### RolloutController.java

```
RolloutController.java:98:Low: rollouts HashMap grows without bound until cleanup() is called. No automatic GC. If cleanup() is never called, completed/rolled-back rollouts accumulate.

RolloutController.java:161-179:Medium: advance() calls clock.currentTimeMillis() twice (lines 166 and 174). Between these two calls, time progresses. The canAdvance check uses the first timestamp, but the stageEnteredAtMs is set with the second. For extremely tight soak times, this could allow a few extra milliseconds of soak time.

RolloutController.java:142-143:Medium: IMMEDIATE policy sets stage to FULL and state to COMPLETED atomically in startRollout(). The status() call on line 153 will show COMPLETED immediately. No audit trail of intermediate stages bypassed.
```

---

## Module 2: configd-replication-engine

### FlowController.java

```
FlowController.java:70-77:High: acquireCredits returns the minimum of available and requested. But the caller receives no signal to WAIT or RETRY -- it just gets fewer credits than requested. The caller must implement its own retry loop or accept partial sends. This is not documented as a requirement on the caller. Missing backpressure signal.

FlowController.java:99:Medium: releaseCredits caps at initialCredits, which handles duplicate acks correctly. However, if a follower becomes partitioned and then returns, all previously consumed credits are lost (not restored). The follower remains throttled until explicit acks arrive. No mechanism to detect this state and auto-reset.

FlowController.java:130:Low: addFollower uses putIfAbsent, so re-adding a follower is a no-op. This is correct but means you cannot reset a follower's credits via addFollower -- you must call resetAll(). This could be surprising.
```

### HeartbeatCoalescer.java

```
HeartbeatCoalescer.java:78-81:Medium: recordHeartbeat does not set windowStartNanos. The window start is only set lazily in shouldFlush (line 151). This means the first heartbeat intent's actual time is not recorded -- the window starts at the next shouldFlush() call, which could be significantly later. Heartbeats may be held longer than intended.

HeartbeatCoalescer.java:80:High: pending map grows without bound per peer. Each peer's group set grows as new groups are added. If groups are created dynamically and never removed, the per-peer Sets grow indefinitely. No documented overload behavior.

HeartbeatCoalescer.java:123-134:Medium: drainAll() iterates pending, builds a result map with unmodifiable wrappers, then clears pending. The returned sets reference the original HashSets wrapped in Collections.unmodifiableSet(). After pending.clear(), the backing map is empty but the returned sets are snapshots (they are the ORIGINAL Set objects, now unreferenced by pending). This is correct -- the sets survive because drainAll wraps the original objects, not copies. But if the caller holds the result and then more heartbeats are recorded, the original Set objects are no longer in the pending map, so they won't be modified. Correct, but subtle.
```

### ReplicationPipeline.java

```
ReplicationPipeline.java:85-91:Critical: offer() has NO backpressure. There is no maximum pending count or byte limit enforced at offer-time. The pending list and pendingBytes grow without bound if flush is never called. While shouldFlush checks thresholds, nothing PREVENTS offer from adding more entries. A burst of proposals can consume unbounded memory before the next shouldFlush/flush cycle.

ReplicationPipeline.java:89-90:High: pendingBytes tracks total bytes but uses a long, which is correct. However, command.length is an int. If a single command is Integer.MAX_VALUE bytes (2GB), this is accepted without validation. No maximum single-command size check.

ReplicationPipeline.java:104-126:Medium: shouldFlush initializes firstEntryNanos lazily on the first call after an offer. If offer() is called many times before shouldFlush() is ever called, the timer starts late, effectively extending the batch window. The batch delay is measured from the first shouldFlush, not the first offer.
```

### SnapshotTransfer.java

```
SnapshotTransfer.java:46:Medium: SnapshotChunk.offset is an int, limiting snapshot size to 2GB. For very large config stores, this could be a problem. The lastIncludedIndex/lastIncludedTerm are longs, which is correct.

SnapshotTransfer.java:99-100:High: SnapshotSendState holds the ENTIRE snapshot in memory as a byte array. For large snapshots (hundreds of MB), this is a significant memory pressure point. No streaming interface is provided.

SnapshotTransfer.java:143-144:High: SnapshotReceiveState.chunks is an ArrayList of byte arrays. All chunks are held in memory until assemble() is called. For large snapshots, this doubles the memory requirement (chunks list + assembled output). No disk-spill option.

SnapshotTransfer.java:266-285:Medium: acceptChunk copies the incoming data (Arrays.copyOf on line 278) which is defensive but doubles memory usage during receive. If the caller already owns the byte array, the copy is wasteful.

SnapshotTransfer.java:298-314:Medium: assemble() uses ByteArrayOutputStream which grows dynamically with array copies. For a 500MB snapshot, this causes multiple large array allocations and copies. A pre-sized output array would be more efficient.

SnapshotTransfer.java:266-276:High: acceptChunk rejects out-of-order chunks silently (returns false). But there is no mechanism to REQUEST retransmission of a missed chunk. If a chunk is lost on the network, the entire snapshot transfer stalls with no recovery path. The caller must detect this and restart the entire transfer.
```

### MultiRaftDriver.java

```
MultiRaftDriver.java:99-103:High: tick() iterates all groups via groups.values(). If a group's tick() callback triggers addGroup() or removeGroup() (e.g., via a committed config change that creates/removes a Raft group), this causes ConcurrentModificationException on the HashMap iterator.

MultiRaftDriver.java:116-121:Medium: routeMessage silently drops messages for unknown groups. No metric, no log. In production, this makes it difficult to distinguish "group not yet created" from "group was removed" from "routing bug".

MultiRaftDriver.java:40:Medium: groups is a HashMap, so tick iteration order is non-deterministic. This could cause subtle timing differences between restarts. A LinkedHashMap would give deterministic tick order.
```

---

## Module 3: configd-transport

### MessageType.java

```
MessageType.java:33:Critical: BY_CODE array is sized 0x0F (15), but HEARTBEAT has code 0x0E (14), which fits. However, if a new message type is added with code >= 0x0F, the static initializer will throw ArrayIndexOutOfBoundsException during class loading, crashing the JVM. The array should be sized dynamically based on the maximum code value, not hardcoded.

MessageType.java:40-45:Medium: fromCode throws IllegalArgumentException for unknown codes. In a network protocol, receiving an unknown message type should be handled gracefully (skip the message), not crash the handler. This can be weaponized for denial-of-service by sending a frame with an invalid type byte.
```

### RaftTransport.java

```
RaftTransport.java:18:Medium: send() accepts Object as the message type. This provides no compile-time type safety. A caller can pass any object, and the error is only caught at runtime (in TcpRaftTransport which casts to FrameCodec.Frame). Should use a bounded type.

RaftTransport.java:29:Medium: MessageHandler.onMessage uses Object for the message parameter. Same type-safety concern.
```

### BatchEncoder.java

```
BatchEncoder.java:84-91:Critical: offer() has no upper bound on the per-peer batch size between flush cycles. While it returns true when maxBatchSize is reached, the caller is not REQUIRED to flush. If the caller ignores the return value, messages accumulate without bound. No documented overload behavior. No hard limit enforcement.

BatchEncoder.java:38:High: batches HashMap never removes entries for peers. Even after a peer is removed from the cluster, its PeerBatch object persists in the map. Over time with dynamic peer membership, this leaks memory.

BatchEncoder.java:170-177:Medium: reset() clears message lists but does NOT remove PeerBatch entries from the batches map. Empty PeerBatch objects accumulate for every peer ever seen.

BatchEncoder.java:80-91:Medium: offer() stores messages as Object. The batch provides no ordering guarantee documentation. Messages are added in ArrayList order (insertion order), so within a single peer's batch, order IS preserved. But this is not documented and could be accidentally broken by future refactoring.
```

### ConnectionManager.java

```
ConnectionManager.java:86-96:High: markDisconnected multiplies currentBackoffMs by BACKOFF_MULTIPLIER on EVERY call, even if the peer was in CONNECTED state. The first disconnection for a previously-healthy peer sets backoff to 200ms (100 * 2.0), not the initial 100ms. The initial backoff is effectively skipped -- the first reconnect attempt will wait 200ms instead of 100ms.

ConnectionManager.java:49:High: peers HashMap grows without bound. removePeer exists but if it's never called for decommissioned peers, the map leaks. No expiry or maximum size.

ConnectionManager.java:101-113:Medium: state() has a side effect -- it transitions BACKING_OFF to DISCONNECTED when the backoff period elapses. A query method modifying state is a code smell and can cause subtle bugs if state() is called from multiple locations with different timing assumptions.

ConnectionManager.java:119-122:Medium: canSend returns true for DISCONNECTED state, meaning a peer that has never been connected and was never registered (returns DISCONNECTED on line 104 for unknown peers) is considered "can send". This could cause send attempts to entirely unknown peers.
```

### MessageRouter.java

```
MessageRouter.java:82-96:Medium: route() falls through to defaultHandler when no group handler matches. This means ALL unmatched messages go to the default handler. If a group handler is accidentally unregistered (race condition during shutdown), Raft messages intended for that group silently go to the default (Plumtree/HyParView) handler, causing protocol confusion.

MessageRouter.java:41:Low: groupHandlers is a HashMap. Lookup is O(1) amortized but with small group counts, an array indexed by groupId would be faster and avoid boxing.
```

### FrameCodec.java

```
FrameCodec.java:68-69:Critical: totalLength = HEADER_SIZE + payload.length. If payload.length is close to Integer.MAX_VALUE, totalLength overflows to a negative value. The ByteBuffer allocation uses this as the array size, throwing NegativeArraySizeException. No overflow check. A malicious payload size can crash the encoder.

FrameCodec.java:113-136:Critical: decode() reads the length field from the frame, but the only validation is that it equals data.length (line 122). The length field itself is trusted from the wire. In the streaming context (TcpRaftTransport.handleInboundConnection), the frame length is read first (line 212), and frames up to 16MB are accepted. A malicious sender can force allocation of a 16MB byte array per frame. With concurrent connections, this enables OOM attacks. The 16MB limit in TcpRaftTransport is a partial mitigation, but there is no per-connection or per-peer rate limit.

FrameCodec.java:145-149:High: peekLength returns the raw int from the wire without validation. A negative length value or absurdly large value is returned as-is. The caller (TcpRaftTransport) does validate, but peekLength itself is unsafe to use in other contexts.

FrameCodec.java:127:Medium: MessageType.fromCode(typeCode) throws IllegalArgumentException for unknown types. In the decode path, this exception propagates up and kills the connection handler thread (in TcpRaftTransport). A single malformed frame drops the entire connection. Should return a sentinel or skip the frame.
```

### TlsConfig.java

```
TlsConfig.java:23:Medium: storePassword is stored as char[] (correct for security), but the default value is new char[0] which is equivalent to no password. There is no validation that a non-empty password is provided. A PKCS12 store with no password is unusual and may fail at runtime.

TlsConfig.java:39-43:High: mtls() factory hardcodes cipher suites and protocols. If the JVM doesn't support TLS_AES_256_GCM_SHA384, socket creation fails at runtime with an opaque SSLException. No validation at construction time.

TlsConfig.java:20-26:Medium: The compact constructor does NOT make defensive copies of the ciphers and protocols lists. If the caller modifies these lists after construction, the TlsConfig is silently mutated despite being a record (which implies immutability).
```

### TcpRaftTransport.java

```
TcpRaftTransport.java:151:Critical: outbound.computeIfAbsent(target, this::createPeerConnection) creates a new PeerConnection for ANY target, even one not in peerAddresses. createPeerConnection (line 253) throws IllegalArgumentException for unknown peers, but this exception propagates unchecked from send(), crashing the caller. No graceful handling for sends to unknown peers.

TcpRaftTransport.java:188-199:Critical: acceptLoop has NO connection limit. Each accepted connection spawns a virtual thread (line 192). A malicious actor can open thousands of connections, each consuming a virtual thread and an open file descriptor. This is a denial-of-service vector. No maxConnections, no rate limiting, no peer authentication before resource allocation.

TcpRaftTransport.java:202-248:Critical: handleInboundConnection reads senderId as a raw int from the wire (line 207) and trusts it as the sender's identity. There is NO authentication of this sender ID. Even with TLS (mTLS), the NodeId in the stream is self-declared and not cross-checked against the TLS client certificate. A peer with valid certificates can impersonate any other peer by sending a different senderId.

TcpRaftTransport.java:260-266:High: handleSendFailure uses `synchronized (connectionManager)` (line 263), but connectionManager is designed for single-threaded access (its Javadoc says "no synchronization is used"). The synchronized block here implies multi-threaded access, contradicting the ConnectionManager's design contract. Moreover, markConnected (line 372) also synchronizes on connectionManager. This suggests the developer recognized the thread-safety issue but applied ad-hoc locking instead of fixing the design.

TcpRaftTransport.java:358-369:High: ensureConnected() creates a new Socket AND starts a reader thread (line 369) inside the sendLock. If the socket creation fails partway (e.g., after socket is assigned but before reader starts), the socket is leaked. Also, starting a reader thread for outbound connections means each outbound connection has BOTH a writer (via sendFrame) and a reader (via handleInboundConnection). If the peer doesn't send back on this socket, the reader thread blocks on read indefinitely.

TcpRaftTransport.java:57:High: outbound ConcurrentHashMap grows without bound. PeerConnection objects are removed on send failure (line 262), but if a peer recovers and new PeerConnections are created, old ones may not be fully cleaned up (the Socket/OutputStream could linger if close() races with a concurrent send).

TcpRaftTransport.java:99:Medium: executor uses newVirtualThreadPerTaskExecutor() which creates unbounded virtual threads. Each accepted inbound connection + each outbound connection's reader spawns a new virtual thread. No upper bound on thread count.

TcpRaftTransport.java:212-213:High: Frame length validation accepts up to 16MB (16 * 1024 * 1024). A slow-loris style attack can force the server to allocate 16MB per connection, and with unlimited connections (see acceptLoop issue), this becomes an unbounded memory allocation vector.

TcpRaftTransport.java:269-288:Critical: On the outbound client side, when TLS is configured, createClientSocket configures protocols and ciphers from TlsConfig, but does NOT call setNeedClientAuth or setWantClientAuth. This is correct for a client socket. However, the client does NOT verify the server's certificate hostname/identity. The SSLSocket.startHandshake() validates the certificate chain but NOT the server's identity against the peer's expected NodeId or address. A MITM with a valid CA-signed cert can intercept connections.
```

### TlsManager.java

```
TlsManager.java:84-86:High: reload() replaces currentContext but does NOT affect already-established connections. Existing SSL sessions continue using the old key material. If a certificate is revoked and reload() is called, the revoked cert is still in use on all existing connections until they are reconnected. No mechanism to force re-handshake on existing connections.

TlsManager.java:54-75:Medium: Both keyStore and trustStore use the SAME password (config.storePassword()). If the key store and trust store have different passwords, this silently fails at load time with an opaque security exception.

TlsManager.java:72:Low: SSLContext.getInstance("TLSv1.3") hardcodes TLSv1.3. If running on an older JDK that doesn't support TLSv1.3, this throws NoSuchAlgorithmException at construction time. The TlsConfig.protocols field is not used here -- it's only used to configure the socket later.
```

---

## Cross-Cutting Findings

### 1. Backpressure (Mandatory Check)

```
CROSS:backpressure:Critical: The following hot paths have NO documented overload behavior and no backpressure mechanism:
  - PlumtreeNode.outbox (unbounded LinkedList)
  - HyParViewOverlay.outbox (unbounded LinkedList)
  - FanOutBuffer.append (overwrites oldest, but reader can see stale data)
  - WatchCoalescer.pending (unbounded ArrayList)
  - ReplicationPipeline.pending (unbounded ArrayList, offer() never rejects)
  - BatchEncoder per-peer batches (unbounded ArrayList)
  - TcpRaftTransport inbound connections (unbounded accept)
  - TcpRaftTransport outbound map (unbounded ConcurrentHashMap)

Every one of these is a potential OOM vector under sustained load or slow consumers.
```

### 2. Flow Control

```
CROSS:flowcontrol:High: FlowController implements credit-based flow control correctly for the Raft replication path. However, there is NO integration point visible -- the MultiRaftDriver and ReplicationPipeline do not reference FlowController. It is unclear where credits are actually checked before sending AppendEntries. Flow control may be implemented but disconnected from the actual send path.
```

### 3. Slow Consumer Policy

```
CROSS:slowconsumer:Critical: SlowConsumerPolicy exists but has the state machine bug described above (DISCONNECTED->QUARANTINED transition uses wrong semantics). Additionally, there is no visible integration with the distribution pipeline -- FanOutBuffer, WatchService, and PlumtreeNode do not reference SlowConsumerPolicy. The policy may be dead code.
```

### 4. mTLS Enforcement

```
CROSS:mtls:Critical: mTLS CAN be bypassed in two ways:
  1. TlsManager is nullable in TcpRaftTransport (line 53). If null, plaintext sockets are used. There is no enforcement that TLS must be configured in production.
  2. Even with TLS, the sender NodeId is self-declared on the wire (line 207-208 of TcpRaftTransport) and not verified against the TLS client certificate. This means mTLS authenticates the machine but not the logical node identity.
```

### 5. Message Ordering

```
CROSS:ordering:High: BatchEncoder preserves insertion order within a per-peer batch (ArrayList append order). However, if messages for the same stream are sent via different BatchEncoder instances (e.g., Raft via forRaft() and Plumtree via forPlumtree()), there is no cross-encoder ordering guarantee. Messages from different encoders may interleave on the wire.
```

---

## Top 10 Most Urgent Findings

| # | File | Line | Severity | Issue |
|---|------|------|----------|-------|
| 1 | TcpRaftTransport.java | 188 | Critical | Unbounded inbound connections -- DoS vector |
| 2 | TcpRaftTransport.java | 207 | Critical | Sender NodeId not authenticated against TLS cert |
| 3 | FanOutBuffer.java | 33-41 | Critical | Non-atomic append in "lock-free" ring buffer -- data races |
| 4 | SlowConsumerPolicy.java | 137-148 | Critical | QUARANTINED is terminal -- no automatic recovery |
| 5 | WatchService.java | 262-289 | Critical | ConcurrentModificationException if listener cancels watch |
| 6 | CatchUpService.java | 114 | Critical | Snapshot called inside loop -- inconsistent delta chain |
| 7 | PlumtreeNode.java | 199-202 | Critical | receiveGraft inflates eager set without bound |
| 8 | FrameCodec.java | 68-69 | Critical | Integer overflow on payload.length near MAX_VALUE |
| 9 | ReplicationPipeline.java | 85-91 | Critical | offer() has zero backpressure -- unbounded growth |
| 10 | HyParViewOverlay.java | 224-237 | Critical | Lost Disconnect causes one-way message flow |
