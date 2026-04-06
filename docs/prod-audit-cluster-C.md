# Production Audit — Cluster C (Fan-Out / Gossip Layer)

**Phase:** 1 of 12 (GA hardening pass)
**Scope:** `configd-distribution-service/src/main/java/io/configd/distribution/`
**LoC read:** 2067 across 10 files
 - `WatchService.java` (329 L, full)
 - `HyParViewOverlay.java` (314 L, full)
 - `RolloutController.java` (278 L, full)
 - `PlumtreeNode.java` (262 L, full)
 - `SlowConsumerPolicy.java` (212 L, full)
 - `CatchUpService.java` (171 L, full)
 - `WatchCoalescer.java` (165 L, full)
 - `SubscriptionManager.java` (154 L, full)
 - `WatchEvent.java` (93 L, full)
 - `FanOutBuffer.java` (89 L, full)

**Reference docs consulted:** `docs/verification/findings/F-0052.md`, `docs/consistency-contract.md`, `docs/decisions/adr-0003-plumtree-fan-out.md`, `docs/decisions/adr-0006-event-driven-notifications.md`, `docs/decisions/adr-0011-fan-out-topology.md`, `docs/decisions/adr-0018-event-driven-notifications.md`, `docs/decisions/adr-0020-prefix-subscription-model.md`.

**ID range:** PA-3001 – PA-3999.

---

## PA-3001: No per-hop signature verification in any distribution-layer forwarder (R-01 / F-0052 unmitigated)
- **Severity:** S0
- **Location:** `configd-distribution-service/src/main/java/io/configd/distribution/PlumtreeNode.java:117-168`, `HyParViewOverlay.java` (all receive paths), `FanOutBuffer.java:33-41`, `SubscriptionManager.java` (all match paths), `WatchService.java:191-193, 262-289`, `CatchUpService.java:80-129`
- **Category:** security
- **Evidence:** `grep -R "Signer\|Signature\|verify\|Verifier"` inside `configd-distribution-service/` returns zero matches. `PlumtreeNode.receiveEagerPush(from, id, payload)` (line 139) takes the payload as an opaque `byte[]` and forwards it verbatim to every other eager peer without ever invoking a signature verifier. `CatchUpService.recordDelta` / `.resolve` (line 80 / 97) store and replay `ConfigDelta` instances without checking the embedded signature. `WatchService.onConfigChange(List<ConfigMutation>, long)` (line 191) ingests mutations directly from the state machine apply path with no authenticity gate.
- **Impact:** F-0052's primary residual: "every hop verifies" is false. An attacker who injects an eager push into the Plumtree overlay, or replays a captured delta through the CatchUp path, propagates unauthenticated payloads to every downstream edge until a `DeltaApplier` with an explicitly-configured verifier is interposed at ingress. Any edge that is wired with the insecure-default `DeltaApplier` constructor (F-0052 note 1) will silently accept them. Direct violation of the signed-chain contract (F-0052 §Impact) and the ADR-0003 "cryptographic integrity at every hop" claim.
- **Fix direction:** Plumb a `DeltaVerifier` (signature + monotonic epoch + nonce per F-0052 suggested fix) into `PlumtreeNode.receiveEagerPush`, `CatchUpService.recordDelta`, and the `WatchService.onConfigChange` entry point. Reject with a counter bump (`fanout_verify_reject_total`) and PRUNE the sender in Plumtree on verify failure. Ensure `FanOutBuffer.append` only accepts verified `ConfigDelta` instances (enforce via a constructor flag or a separate `VerifiedDelta` wrapper type). Decision gate: do not GA until every inbound edge of the fan-out layer has non-nullable verification.
- **Owner:** security

## PA-3002: `WatchService.dispatchEvent` swallows exceptions implicitly — silent data loss per watcher
- **Severity:** S0
- **Location:** `WatchService.java:262-289`
- **Category:** correctness
- **Evidence:** The dispatch loop calls `watch.listener().onEvent(filtered)` without a try/catch. If one listener throws (e.g., a slow watcher's queue rejects, a remote gRPC stream is half-closed and throws `IllegalStateException`), the exception propagates out of `dispatchEvent` and aborts the entire for-loop. Every subsequent watcher in `watches.values()` that has not yet been visited in this iteration has already had `watch.advanceCursor(event.version())` called on line 272 **before** the listener is invoked on line 285 — so on the next `tick()` those watchers will be silently skipped because their cursor is now `>= event.version()` (line 267 check).
- **Impact:** INV-W1 (ordering) and INV-W2 (no-drop) violations. A single misbehaving listener can (a) prevent all following watchers from ever receiving the current event and (b) additionally cause them to skip it forever because their cursor was advanced pre-delivery. This is data loss with no exception, no log, and no metric.
- **Fix direction:** (1) Wrap `listener.onEvent` in try/catch; on exception, emit a structured log + bump `watch_dispatch_failures_total{watch_id=...}`, do NOT swallow via the caller. (2) Advance the cursor only **after** successful `onEvent` return, or accept at-least-once with explicit documentation. (3) Consider quarantining a listener after N consecutive failures (integrate with `SlowConsumerPolicy`). (4) Add a test: register three watches where the middle one throws, assert the third still receives the event.
- **Owner:** distribution

## PA-3003: WatchService cursor-advance precedes delivery — crash mid-dispatch = silent drop
- **Severity:** S0
- **Location:** `WatchService.java:271-285`
- **Category:** correctness
- **Evidence:**
  ```
  271  List<ConfigMutation> matching = filterByPrefix(...);
  272  watch.advanceCursor(event.version());     // <-- advance first
  ...
  285  watch.listener().onEvent(filtered);       // <-- deliver second
  ```
  If the process crashes, is interrupted, or `onEvent` throws (see PA-3002) between lines 272 and 285, the watch's cursor says "delivered at version V" but the listener never received it. There is no WAL for the cursor and no acknowledgement model back from the listener.
- **Impact:** Violates INV-W2 (no-drop). On crash-recovery the watcher resumes at an advanced cursor and misses the event entirely. Compounds PA-3002.
- **Fix direction:** Invert the order: deliver first, then advance the cursor on success. For full correctness, delivery should be a two-phase operation (cursor advance only after listener returns an explicit ACK or after the subscriber's ack has been observed).
- **Owner:** distribution

## PA-3004: SlowConsumerPolicy `pendingCount` is never decremented on successful send — monotonically grows to DISCONNECTED
- **Severity:** S0
- **Location:** `SlowConsumerPolicy.java:113-132, 97-107`
- **Category:** correctness
- **Evidence:** `recordSend` increments `tracker.pendingCount++` (line 117) on every send. `recordAck` (line 99) sets `tracker.pendingCount = 0`. There is no per-message decrement. If the acking consumer ACKs each delta individually (the natural model with version cursors), each ACK resets to 0 correctly. But if the consumer batches ACKs (which the edge-cache model supports — each delta is ack'd once it is applied), there are periods where `pendingCount` is inflated. Worse, because `recordSend` uses `>` vs `maxPendingEntries` and `recordAck` resets to 0, a single lost/delayed ack during a burst (e.g. `>1000` sends within one ack window) forces state→SLOW even if the consumer is healthy. Once SLOW, line 126-130 will flip to DISCONNECTED after `disconnectMs` regardless of whether the consumer actually fell behind.
- **Impact:** False-positive disconnects of healthy consumers under burst traffic → PA-3001 reconnect storm → service disruption. Misalignment with ADR-0003 §7 intent of "disconnect truly slow consumers".
- **Fix direction:** Add `recordAck(NodeId node, int ackedCount)` that decrements `pendingCount -= ackedCount` (clamped to 0). Reset `slowSinceMs` only when `pendingCount` drops back below `maxPendingEntries * 0.5` (hysteresis). Alternatively track `pendingCount` as `highWatermark - ackedVersion` derived from cursors, not from send/ack counters.
- **Owner:** distribution

## PA-3005: SlowConsumerPolicy has no reconnect rate-limit — QUARANTINED consumers can reconnect immediately via `readmit`
- **Severity:** S1
- **Location:** `SlowConsumerPolicy.java:162-171`
- **Category:** reliability
- **Evidence:** `readmit(NodeId node)` resets the tracker to HEALTHY with no check on how often it has been called for this node, no minimum interval between re-admissions, and no cap on lifetime re-admissions. A consumer that repeatedly falls into SLOW → DISCONNECTED → QUARANTINED → (operator calls readmit) → SLOW again is not throttled by the policy. There is also no backoff multiplier on repeated quarantine: each cycle uses the same `quarantineMs` (default 60s), not `60s, 120s, 240s, ...`.
- **Impact:** "Slow-consumer death spiral" (explicit concern in audit scope): a misconfigured or genuinely-sick edge node churns through disconnect/readmit cycles, and each cycle re-triggers full catch-up (potentially full snapshot per `CatchUpService`) — which itself can make it SLOW again immediately. Under N such consumers, this becomes a thundering-herd on the leader's snapshot provider.
- **Fix direction:** Track per-node re-admission history (count, last-readmit-ts). Apply exponential backoff on `quarantineMs` based on re-admission count within a rolling window (e.g., 1h). Reject `readmit` if in quarantine and the quarantine window has not elapsed. Emit `slow_consumer_readmit_backoff_ms` metric.
- **Owner:** distribution

## PA-3006: `SlowConsumerPolicy.recordSend` does not gate on state — continues to send to DISCONNECTED/QUARANTINED consumers
- **Severity:** S1
- **Location:** `SlowConsumerPolicy.java:113-132` vs caller integration
- **Category:** reliability
- **Evidence:** `recordSend` unconditionally does `tracker.pendingCount++` and transitions SLOW → DISCONNECTED. It never returns a signal like `shouldDrop`. The only gate is `canDeliver(node)` which the caller must remember to check separately before enqueueing. Nothing in the distribution package ties `recordSend` to a check that `state != DISCONNECTED`. A caller that forgets `canDeliver` will inflate `pendingCount` on an already-disconnected consumer indefinitely. This is the "death spiral — disconnect propagated up before more data enqueued?" concern from the audit scope: the answer is **no** — the policy trusts the caller.
- **Impact:** Unbounded enqueue to disconnected consumers; depending on caller, memory leak on disconnect. Disconnect is not propagated back into the fan-out decision path atomically.
- **Fix direction:** Make `recordSend` return an enum `{ACCEPTED, DROPPED_SLOW, DROPPED_DISCONNECTED, DROPPED_QUARANTINED}` and force callers to handle it. Alternatively, expose an `enqueue(NodeId, msg)` method on the policy that encapsulates the check + record. Add an assertion that `canDeliver` is called before `recordSend`.
- **Owner:** distribution

## PA-3007: WatchService per-subscriber queue is UNBOUNDED — head-of-line blocking, no fairness, no backpressure
- **Severity:** S1
- **Location:** `WatchService.java:262-289`
- **Category:** resource
- **Evidence:** The fan-out path calls `watch.listener().onEvent(filtered)` synchronously on the I/O thread. There is no per-watcher queue at all — the listener is expected to be fast and non-blocking (javadoc line 55-57). But "non-blocking" is not enforced and not monitored. If a listener is a gRPC server-stream `StreamObserver.onNext` and the client's network is slow, `onNext` can block the I/O thread (gRPC uses flow control, but the blocking path is on the producer) or enqueue internally without bound. The dispatch loop iterates *all* watches (line 266) for every event — one slow listener blocks every other watcher on the same node.
- **Impact:** Head-of-line blocking: a single slow watcher stalls propagation to all other watchers on the same distribution node. Direct hit on INV-S1 (staleness p99 ≤ 500 ms). Unbounded memory growth if listeners internally queue.
- **Fix direction:** Interpose a per-watcher bounded queue with an explicit drop-or-close policy (drive through `SlowConsumerPolicy`). Dispatch through a work-stealing pool, not the I/O thread. Alternatively document a hard SLA for `WatchListener.onEvent` (e.g., p99 < 1 ms) and add instrumentation (`watch_listener_latency_ns_bucket{watch_id}`). Add a test that registers 100 watches where watch #1 sleeps 10s and asserts watches 2-100 still receive events within 500 ms.
- **Owner:** distribution

## PA-3008: WatchCoalescer has NO upper bound on `pending` list — unbounded growth when `tick()` stops being called
- **Severity:** S1
- **Location:** `WatchCoalescer.java:85-102, 109-118`
- **Category:** resource
- **Evidence:** `add(mutations, version)` calls `pending.addAll(mutations)` with no size check. `shouldFlush` returns true only when `pending.size() >= maxBatch` (default 64) OR the window elapses — but both checks only gate **whether** `WatchService.tick()` should flush, not whether `add` should reject. If the I/O thread falls behind and stops calling `tick()` (or if `tick()` is called but listeners block per PA-3007), `add` is called from the Raft apply thread with no back-pressure. `pending` grows without bound, holding references to every `ConfigMutation` ever applied since the last flush. The `maxBatch = 64` only bounds the *batch that gets flushed*, not the accumulator.
- **Impact:** OOM under burst writes + slow fan-out. The coalescer is described as a safety mechanism against thundering herd, but it becomes the thundering herd's staging buffer.
- **Fix direction:** Add a hard cap `maxPending` (e.g., 10 × `maxBatch`). When exceeded: either (a) force-flush immediately from `add`, breaking the coalescing window contract but preserving memory, or (b) apply backpressure by throwing a checked exception that the caller (state machine apply path) must handle. Option (a) is more practical. Add a metric `coalescer_pending_high_watermark`.
- **Owner:** distribution

## PA-3009: WatchCoalescer drops affected-keys pre-computation — allocates HashSet per flushed event
- **Severity:** S2
- **Location:** `WatchCoalescer.java:126-134`, `WatchEvent.java:38-58`
- **Category:** perf
- **Evidence:** `WatchCoalescer.flush` calls `new WatchEvent(List.copyOf(pending), latestVersion)`. That constructor (line 64) delegates to the compact constructor which allocates a `new HashSet<String>()` and iterates the mutations to populate it (line 49-56). Then the payload is `Set.copyOf(keys)` — a second allocation. For a default batch of 64 mutations, this is ~130 extra object allocations per coalesced flush, on the hot fan-out path. Since `WatchService.dispatchEvent` also allocates a fresh `ArrayList` per watcher in `filterByPrefix` (line 317), allocation pressure per event scales as O(batch × watches).
- **Impact:** GC pressure on the fan-out hot path. Allocation rate scales with both batch size and watcher count. Contributes to tail latency under fan-out fan-out (no INV violation yet, but a regression vector under scale).
- **Fix direction:** (1) Reuse a thread-local `HashSet<String>` for affected-keys computation and wrap with `Collections.unmodifiableSet` copy on publish. (2) Replace `ArrayList` in `filterByPrefix` with a pooled list. (3) Benchmark with JMH and set a per-event allocation budget (e.g., < 4 objects).
- **Owner:** java-systems

## PA-3010: `SubscriptionManager.matchingNodes` is O(prefixes × N) — linear scan on every mutation
- **Severity:** S1
- **Location:** `SubscriptionManager.java:112-123`
- **Category:** perf
- **Evidence:** `matchingNodes(String key)` iterates `prefixIndex.entrySet()` (line 116) and for each prefix does `key.startsWith(prefix)`. No trie, no radix tree, no sorted index. With 10k prefix subscribers (realistic for large fleets), each mutation does 10k string-prefix checks. For a batch of 64 mutations (a default coalesced event), that's 640k comparisons **per event**.
- **Impact:** INV-S1 (p99 ≤ 500 ms) violation at fleet scale. ADR-0020 (prefix subscription model) is silent on time complexity but the current O(P × M) is untenable past ~1k prefixes.
- **Fix direction:** Index prefixes in a radix/compressed trie. Lookup becomes O(|key|) per mutation regardless of subscriber count. Alternatively maintain a sorted `TreeMap<String, Set<NodeId>>` and use `headMap(key, true).lastKey()` walk; the trie is preferable. Benchmark with 10k prefixes.
- **Owner:** distribution

## PA-3011: `SubscriptionManager` has no ACL integration — any node can subscribe to any prefix, including sensitive namespaces
- **Severity:** S0
- **Location:** `SubscriptionManager.java:43-56`
- **Category:** security
- **Evidence:** `subscribe(NodeId node, String prefix)` accepts the subscription unconditionally. There is no check against ADR-0017 namespaces, no capability grant, and no cross-reference to an authn/authz subsystem. An edge node that subscribes to `""` receives the entire store. Nothing in this module, nor any reference from `WatchService.dispatchEvent`, consults an ACL when filtering mutations.
- **Impact:** Confidentiality breach: any compromised edge node subscribes to all config, including other tenants' secrets if mounted in the same store. Violates the tenant-isolation implication of ADR-0017.
- **Fix direction:** Plumb a `SubscriptionAuthorizer` (NodeId, prefix) -> allow/deny. Reject unauthorized subscriptions with a typed exception and structured audit log. Integrate at subscribe time and re-check at dispatch (so a revocation takes effect without requiring the subscriber to re-subscribe).
- **Owner:** security

## PA-3012: PlumtreeNode message dedup window is a bare `LinkedHashMap` — no timestamp TTL, no memory bound in payload bytes
- **Severity:** S1
- **Location:** `PlumtreeNode.java:81-89, 143-149`
- **Category:** correctness
- **Evidence:** `receivedMessages` is a `LinkedHashMap` with `removeEldestEntry` evicting at `size() > maxReceivedHistory`. The dedup is purely count-based, not time-based. If `maxReceivedHistory = 1000` (typical) and the cluster is quiescent for an hour, then a burst of 1001 messages arrives — the very first of that burst is evicted. A delayed duplicate of the evicted message, arriving right after the burst (e.g., from a slow lazy peer), is treated as **new**: re-delivered to the application and re-broadcast. This re-enters the gossip tree and can cause a re-propagation loop under specific network delay profiles.
- **Impact:** Duplicate delivery on re-convergence; potential gossip re-amplification. `MessageId` carries a `timestamp` (line 43) but it is never consulted for TTL. Violates the "at-most-once after dedup window" intent.
- **Fix direction:** Key the dedup window on `(version, timestamp)` but evict by `now - timestamp > DEDUP_TTL` in `tick()`. Alternatively combine count-based + time-based: evict older of the two triggers. Add a test: inject a burst of `maxReceivedHistory + 10` msgs, then replay the first msg, assert it is still deduped.
- **Owner:** distribution

## PA-3013: PlumtreeNode eagerPush loop allocates per-peer message records on hot path
- **Severity:** S2
- **Location:** `PlumtreeNode.java:123-128, 155-165`
- **Category:** perf
- **Evidence:** `broadcast` and `receiveEagerPush` do `outbox.add(new OutboundMessage.EagerPush(peer, id, payload))` for every peer. With an eager fanout of 6 peers, each broadcast allocates ~6 new record objects plus the `LinkedList.Node`. Under a burst of 1k broadcasts, that's 6k+ short-lived records on the I/O thread. Combined with `LinkedList` node allocation, GC pressure is noticeable at scale.
- **Impact:** GC hitch on the broadcast path. Not an SLO violation by itself; contributes to tail latency.
- **Fix direction:** Use an `ArrayDeque<OutboundMessage>` instead of `LinkedList`. Pool `EagerPush` records (record re-use is tricky because they're value-like — consider a `OutboundMessageBatch` that amortizes allocation). Benchmark with JMH.
- **Owner:** java-systems

## PA-3014: PlumtreeNode graft timeout can fire against a peer already in eager set — redundant graft, state flap
- **Severity:** S2
- **Location:** `PlumtreeNode.java:208-227`
- **Category:** correctness
- **Evidence:** `tick()` checks `!receivedMessages.contains(id)` before grafting (line 219) but does not check whether `notification.from` is **already** in `eagerPeers`. If a peer was moved to eager between IHAVE receipt and timeout, we still issue a `Graft` message to it. The local side `lazyPeers.remove(peer); eagerPeers.add(peer)` is idempotent, but the wire message is wasted and the recipient may respond with a PRUNE (believing this is a protocol violation).
- **Impact:** Minor wire waste, potential eager-lazy flap between two peers. Not a correctness violation of the broadcast but a protocol hygiene issue.
- **Fix direction:** Check `if (eagerPeers.contains(notification.from)) continue;` before emitting the `Graft`. Remove the lazy-notification entry without the graft.
- **Owner:** distribution

## PA-3015: PlumtreeNode `outbox` / `lazyNotifications` grow unbounded on partition
- **Severity:** S1
- **Location:** `PlumtreeNode.java:60, 87, 232-236`
- **Category:** resource
- **Evidence:** `outbox` is a `LinkedList<OutboundMessage>` with no size cap. `lazyNotifications` is a `HashMap<MessageId, LazyNotification>` with no size cap. If the I/O thread consumes `drainOutbox()` less often than `broadcast` / `receiveEagerPush` are called — for example during a CPU-bound GC pause or when the transport is partitioned and the drain is not wired — these structures grow without bound. `receiveIHave` uses `putIfAbsent` so duplicate IHAVEs don't add, but distinct messages from distinct peers can still accumulate `lazyNotifications` entries up to the rate of IHAVE arrival.
- **Impact:** Memory blow-up under network partition. Recovery requires restart. No alarm fires because there's no size metric exposed.
- **Fix direction:** Bound `outbox` with a drop policy (drop oldest IHAVE but never drop EagerPush / Graft — EagerPush loss causes gap, Graft loss delays repair). Bound `lazyNotifications` with a LRU eviction (or TTL). Expose `outbox.size()` and `lazyNotifications.size()` as gauges.
- **Owner:** distribution

## PA-3016: HyParView `addToActiveView` evicts a RANDOM active peer when full — not LRU, not fewest-msgs
- **Severity:** S2
- **Location:** `HyParViewOverlay.java:221-238`
- **Category:** correctness
- **Evidence:** Line 226 picks `randomActive()` and evicts. This is correct per the HyParView paper, but the paper assumes uniform peer quality. In practice, a freshly-joined, unmeasured peer has equal probability of eviction as a long-stable, high-quality peer. Worse, the evicted peer is sent a `Disconnect` (line 231) **while** the new peer is added — so during a join storm, good peers get kicked out to make room for transient joiners.
- **Impact:** Overlay churn under join storm. Not a correctness violation but degrades fan-out stability.
- **Fix direction:** Prefer to evict the most recently added active peer (i.e., reject the incoming over displacing an established one) when the overlay is "warm", or score peers by observed RTT / message age and evict the worst.
- **Owner:** distribution

## PA-3017: HyParView `peerFailed` / `receiveDisconnect` can orphan the overlay if passive view is empty
- **Severity:** S1
- **Location:** `HyParViewOverlay.java:183-200, 254-263`
- **Category:** reliability
- **Evidence:** `peerFailed` removes the peer from active and calls `promotePassivePeer`. If `passiveView` is empty (line 255 early-return), no promotion happens — active view shrinks by one with no replacement. If this cascades (all active peers fail while passive is empty), the node is completely isolated with no recovery path. The only way to recover is for another peer to initiate a `Join` against this node, or for an out-of-band bootstrap.
- **Impact:** Partition-healing failure. A node that loses its entire active view with no passive entries cannot reconnect. Combined with PA-3018 (shuffle never initiated), the passive view is empty at startup and small after.
- **Fix direction:** On detect "active view size == 0 AND passive view empty", trigger a re-bootstrap against the configured contact nodes. Persist passive view to storage and reload on restart. Emit an `overlay_isolated` alarm.
- **Owner:** distribution

## PA-3018: HyParView shuffle is never initiated from within the class — `initiateShuffle` has no scheduler
- **Severity:** S1
- **Location:** `HyParViewOverlay.java:150-159`
- **Category:** correctness
- **Evidence:** `initiateShuffle()` is a public method with javadoc saying "called periodically (e.g., every 30 seconds)". There is no `tick()` equivalent on `HyParViewOverlay` that auto-invokes it, unlike `PlumtreeNode.tick()`. Nothing in the distribution package wires a scheduler. If the outer caller forgets to schedule `initiateShuffle` (or its schedule period is too long), the passive view goes stale and `promotePassivePeer` pulls stale peers.
- **Impact:** Shuffle frequency is entirely dependent on external wiring. If forgotten, the protocol silently degrades to static active view only, with no peer discovery. Matches "partition healing" concern in the audit scope.
- **Fix direction:** Add a `tick(long nowMs)` on `HyParViewOverlay` that itself drives shuffle at a configured interval. Alternatively document prominently that the caller **must** schedule `initiateShuffle` at a specified cadence, and fail loudly (a metric at 0) if it isn't happening.
- **Owner:** distribution

## PA-3019: HyParView neighbor-failure detection does not exist in this class
- **Severity:** S1
- **Location:** `HyParViewOverlay.java:183-189` (only `peerFailed(NodeId)` called externally)
- **Category:** reliability
- **Evidence:** `peerFailed` is a method the caller invokes; there is no heartbeat or ping logic inside `HyParViewOverlay`. ADR-0016 describes SWIM/Lifeguard integration for failure detection, but nothing in this file references a SWIM module or a heartbeat tick. Failure detection is assumed to be delivered from outside. If the outer transport layer doesn't wire SWIM → `peerFailed`, dead peers stay in the active view indefinitely, and broadcast messages target them forever.
- **Impact:** Silent black-hole of broadcast to dead peers; slow convergence on node crash. Coupling between this module and the failure detector is implicit and easy to miss during deployment.
- **Fix direction:** Document the required integration contract explicitly (interface `FailureDetector { void onFailed(Consumer<NodeId>); }`). Add an integration test. Expose `overlay_active_peers_last_heartbeat_age_max_ms` as a metric so that a stuck overlay can be alarmed.
- **Owner:** distribution

## PA-3020: HyParView `samplePassiveView` is O(n²) via `remove(idx)` on ArrayList
- **Severity:** S3
- **Location:** `HyParViewOverlay.java:265-274`
- **Category:** perf
- **Evidence:** `sample.add(all.remove(idx))` — `ArrayList.remove(int)` is O(n). In a loop over `n` iterations, total O(n²). With a passive view of 30 and shuffle length of say 6, that's ~180 ops per shuffle — negligible. But if ADR-0011 scales passive view to hundreds, this becomes noticeable.
- **Impact:** Hygiene only at current scale. Flag for scaling.
- **Fix direction:** Use reservoir sampling or Fisher-Yates shuffle on a copied array.
- **Owner:** distribution

## PA-3021: CatchUpService does not hand back ACL-scoped deltas — cross-tenant leak on replay
- **Severity:** S0
- **Location:** `CatchUpService.java:97-129`
- **Category:** security
- **Evidence:** `resolve(NodeId node, long nodeVersion)` returns every delta in the history chain regardless of what the requesting node was subscribed to. The node then receives mutations to keys it was never subscribed to / authorized for. This is consistent with PA-3011 (no ACL) but worse in the catch-up path because the blast radius is "all history we retained" instead of "live events".
- **Impact:** On catch-up, an edge node reconstructs a version of the store that spans keys outside its subscription, including (if multi-tenant) another tenant's data.
- **Fix direction:** Filter each `ConfigDelta` by the requesting node's subscription set before enqueuing into the replay chain. Requires deltas to be filterable by key — probably a helper on `ConfigDelta`. Pair with PA-3011 ACL integration.
- **Owner:** security

## PA-3022: CatchUpService `resolve` chain-walk cost: O(maxDeltaHistory) with snapshot provider call EVERY iteration
- **Severity:** S1
- **Location:** `CatchUpService.java:105-119`
- **Category:** perf
- **Evidence:**
  ```
  while (maxChain-- > 0) {
      ConfigDelta delta = deltaHistory.get(currentVersion);
      ...
      ConfigSnapshot snap = snapshotProvider.currentSnapshot();   // <-- every iteration
      if (snap != null && currentVersion >= snap.version()) { ... }
  }
  ```
  `snapshotProvider.currentSnapshot()` is called on every chain step. If the snapshot provider materializes a snapshot (reads the state machine, takes a lock, allocates), this is O(chainLength × snapshotCost). A node 100 deltas behind triggers 100 snapshot reads.
- **Impact:** Replay storm amplifier: when N edge nodes simultaneously catch up (post-deploy, post-partition), each does N × snapshot-cost. Hits leader CPU/memory.
- **Fix direction:** Call `currentSnapshot()` **once** before the loop and compare `currentVersion` to its cached version. Only re-call if the walk is still ongoing after X iterations or if the cached snapshot is older than `currentVersion`. Expose a gauge `catchup_snapshot_calls_per_resolve`.
- **Owner:** distribution

## PA-3023: CatchUpService `trimHistory` is O(n²) — full scan per insert over the cap
- **Severity:** S2
- **Location:** `CatchUpService.java:159-170`
- **Category:** perf
- **Evidence:**
  ```
  while (deltaHistory.size() > maxDeltaHistory) {
      long oldest = Long.MAX_VALUE;
      for (long version : deltaHistory.keySet()) { ... }  // linear scan
      deltaHistory.remove(oldest);
  }
  ```
  Each `recordDelta` that triggers trim does a full linear scan to find the oldest. At steady state, trim runs ~once per insert with O(maxDeltaHistory) cost. For `maxDeltaHistory = 1000`, that's 1000 ops per delta recorded.
- **Impact:** Leader CPU overhead scales with history size. Not a cliff today, but a silent ~1000× amplification on a hot path.
- **Fix direction:** Use a `TreeMap<Long, ConfigDelta>` and `firstEntry()` / `pollFirstEntry()` — O(log n). Or use an `ArrayDeque<ConfigDelta>` since deltas arrive in order.
- **Owner:** distribution

## PA-3024: CatchUpService has no switchover threshold between delta replay and snapshot — chooses "first available" not "cheapest"
- **Severity:** S1
- **Location:** `CatchUpService.java:97-129`
- **Category:** perf
- **Evidence:** The current policy is: try delta chain → if any gap, fall back to snapshot. There is no size comparison. If a node is 5 deltas behind and each delta is 100 bytes but the snapshot is 100 MB, the current code replays the deltas (good). If a node is 500 deltas behind at 100 bytes each (50 KB total) but the snapshot is 10 KB, the current code still replays deltas (bad). There's no heuristic for "prefer snapshot when cheaper".
- **Impact:** Slow catch-up when many small deltas cumulatively exceed snapshot size. Replay storm on bursty churn.
- **Fix direction:** Add `if (estimatedChainSize > snapshotSize × α) return SnapshotRequired`. Estimate chain size via accumulated byte count as the chain is built.
- **Owner:** distribution

## PA-3025: CatchUpService has no burst-request backoff — each call to `resolve` starts the full walk
- **Severity:** S1
- **Location:** `CatchUpService.java:97-129`
- **Category:** reliability
- **Evidence:** The `catchUpNodes` map tracks who is catching up but doesn't rate-limit `resolve` calls. If a slow consumer calls `resolve` repeatedly (e.g., edge retries), each call does the full walk + snapshot probe. No dedup of in-flight requests, no "please wait" response.
- **Impact:** Under thundering herd (many edges catching up post-deploy), the leader executes N full resolution walks concurrently. Combined with PA-3022 (per-iteration snapshot) and PA-3023 (O(n²) trim), this is a multiplicative hit on the leader.
- **Fix direction:** Cache the last resolve result per node for a short window (e.g., 1s). If a request comes in while the prior one's result is still valid, return a `Pending` response. Also track request rate per node and emit a metric.
- **Owner:** distribution

## PA-3026: `SubscriptionManager` mutation during dispatch is not protected — ConcurrentModificationException possible
- **Severity:** S1
- **Location:** `SubscriptionManager.java` (all public methods) + `WatchService.java:266` iterator
- **Category:** correctness
- **Evidence:** Javadoc on both classes says "designed for single-threaded access from the distribution service I/O thread". But `WatchService.dispatchEvent` iterates `watches.values()` and inside the loop calls `watch.listener().onEvent(filtered)`. A listener that reaches back into `WatchService.cancel(watchId)` (a very plausible "one-shot watch" pattern) will call `watches.remove(watchId)` — concurrent modification on a `HashMap` iterator = `ConcurrentModificationException`, aborting dispatch mid-loop with no error handling (PA-3002 again).
- **Impact:** Legitimate listener patterns (one-shot, self-unregister-on-error) are silent bombs. This is a re-entrance hazard even within a single-threaded design.
- **Fix direction:** Document that listeners MUST NOT call `cancel`/`register` synchronously. Enforce by deferred-mutation: queue register/cancel operations during dispatch and apply after the dispatch loop. Add a test.
- **Owner:** distribution

## PA-3027: WatchService `nextWatchId` wraps silently after 2^63 ops — but the `id <= 0` check in `Watch` ctor crashes first
- **Severity:** S3
- **Location:** `WatchService.java:102, 150, 84`
- **Category:** correctness
- **Evidence:** `nextWatchId` starts at 1 and `long` wraps to negative after `Long.MAX_VALUE` allocations. The `Watch` constructor throws on `id <= 0` (line 84), so register() will throw at 2^63. Practically unreachable, but silent-wrap on a counter is always a smell.
- **Impact:** Hygiene.
- **Fix direction:** Document the overflow bound or use `AtomicLong.incrementAndGet` with explicit wrap check.
- **Owner:** distribution

## PA-3028: `FanOutBuffer` volatile-only semantics are insufficient for lock-free MPSC — `deltasSince` can observe a torn ring
- **Severity:** S1
- **Location:** `FanOutBuffer.java:33-54`
- **Category:** correctness
- **Evidence:**
  ```
  33  public void append(ConfigDelta delta) {
  35      int slot = (int)(head % capacity);
  36      ring.set(slot, delta);
  37      head = head + 1;
  38      if (head - tail > capacity) tail = head - capacity;
  39  }
  ```
  `append` claims single-writer, but the order is: write slot, then bump `head`, then bump `tail`. A concurrent reader in `deltasSince` does: `currentTail = tail; currentHead = head`. Because `tail` is read **before** `head`, a reader can see `currentTail = 0, currentHead = 0` even while the writer is mid-append with `head = 5, tail = 3` — but that's fine (empty range). The problem is the opposite: reader sees `currentTail = 0, currentHead = 5` (post-write) but because `tail` is read first and the writer bumps `tail` after `head`, the reader could see `currentTail` before the writer's tail update and `currentHead` after — the range `[0, 5)` may include slots that the writer has already overwritten (since ring wraps). The reader's `ring.get((int)(i % capacity))` returns the NEW value, not the one at version `i`. `deltasSince` filters by `delta.fromVersion() >= fromVersion` (line 49) which would catch the wrong delta: it's not null and its version is >= fromVersion, but its version is actually higher than expected (an overwrite). Result: ordering violation; a reader can see deltas out of order or skip a delta.
- **Impact:** Violates INV-S1 fresh-snapshot semantics on concurrent readers. Low frequency (requires reader to be mid-scan while writer wraps) but corrupts replay.
- **Fix direction:** Use an `AtomicLongArray` of version tags per slot or package the version inside the `ConfigDelta` slot and check `delta.fromVersion() == expectedVersion` per slot. Better: use an MPSC queue library (JCTools) or a proper lock-free ring like LMAX Disruptor. Current implementation is subtly wrong.
- **Owner:** java-systems

## PA-3029: `FanOutBuffer.append` `tail = head - capacity` without CAS — compound write races with concurrent readers
- **Severity:** S2
- **Location:** `FanOutBuffer.java:37-40`
- **Category:** correctness
- **Evidence:** The sequence `head = head + 1; if (head - tail > capacity) tail = head - capacity` is two writes. A reader that runs `long currentTail = tail; long currentHead = head;` can observe the writes in any order on a weak memory model. Java's volatile provides happens-before, so the individual reads are consistent — but the *relationship* between them is not atomic. If a reader observes `head = X` and then `tail = Y`, X and Y may be from different writer states. Combined with PA-3028, this enlarges the torn-ring window.
- **Impact:** Same as PA-3028. Compounds.
- **Fix direction:** Same fix: use a proper lock-free ring or move to SPSC/MPSC from JCTools.
- **Owner:** java-systems

## PA-3030: `FanOutBuffer.deltasSince` allocates a `new ArrayList<>()` on every call — no pooling, no size hint
- **Severity:** S3
- **Location:** `FanOutBuffer.java:43-53`
- **Category:** perf
- **Evidence:** Every reader call allocates a fresh ArrayList and resizes it as deltas are added. With a ring of 10k deltas and N concurrent readers, this allocates N × (array growth cost) per scan.
- **Impact:** GC pressure on the reader side of the fan-out hot path. Hygiene.
- **Fix direction:** Accept a caller-provided list / consumer callback, or pre-size `new ArrayList<>((int)(currentHead - currentTail))`.
- **Owner:** java-systems

## PA-3031: `RolloutController` has no abort-on-stuck-cohort detection
- **Severity:** S1
- **Location:** `RolloutController.java:161-180, 220-225`
- **Category:** reliability
- **Evidence:** `advance` checks `canAdvance` (soak time + health passing). If health is never asserted (`updateHealth` never called), `healthPassing` stays `true` (default from line 150) and the rollout progresses through all stages regardless of actual fleet health. Conversely if health is set `false` once and `updateHealth(rolloutId, true)` is never called again due to a bug, the rollout is stuck at the current stage forever with no timeout-based abort, no alert, and `RolloutState.IN_PROGRESS` reported to operators.
- **Impact:** (a) False-positive rollout to FULL when health data is missing (silent default "healthy"); (b) False-negative stuck at stage forever when health data is stale.
- **Fix direction:** Change `healthPassing` default to `null` (unknown) and require explicit health assertion before advance. Add a `healthLastUpdatedMs`; if stale beyond a threshold, consider health UNKNOWN → no advance. Add a maximum total rollout duration — after that, either auto-rollback or escalate.
- **Owner:** distribution

## PA-3032: RolloutController `IMMEDIATE` policy skips ALL gates including the health check that would catch a bad config
- **Severity:** S1
- **Location:** `RolloutController.java:141-148`
- **Category:** reliability
- **Evidence:** `initialStage = (policy == RolloutPolicy.IMMEDIATE) ? Stage.FULL : Stage.CANARY;` and immediately marks state `COMPLETED`. There is no health check, no operator reconfirm, no dry-run validation. The javadoc notes "Requires elevated ACL" but no ACL check is implemented in this class.
- **Impact:** A break-glass path with zero safety net. An operator with elevated ACL who pastes the wrong rollout ID can FULL-deploy an arbitrary config with no gate. Breaks ADR-0008 spirit.
- **Fix direction:** Even IMMEDIATE should (a) still go through CANARY with an operator-configurable short soak (e.g., 10s) and (b) check health at least once before marking COMPLETED. Require an explicit `immediate_override_reason` audit field. Integrate ACL check in this class, not just in the caller.
- **Owner:** security

## PA-3033: RolloutController `rollback` does not actually revert config — only flips state
- **Severity:** S1
- **Location:** `RolloutController.java:208-214`
- **Category:** correctness
- **Evidence:**
  ```
  public RolloutStatus rollback(String rolloutId) {
      RolloutTracker tracker = rollouts.get(rolloutId);
      if (tracker != null) {
          tracker.state = RolloutState.ROLLED_BACK;
      }
      return status(rolloutId);
  }
  ```
  No call to a config-store undo, no emission of a revert mutation, no notification back to the distribution layer. The javadoc says "The change should be reverted" — passive voice by whom? Not this class.
- **Impact:** Rollback is cosmetic. Operators seeing `ROLLED_BACK` state may assume the config is reverted when in fact it's still live on the fleet.
- **Fix direction:** Rollback must emit a reverse-mutation sequence through the state machine. Take a `Runnable revertAction` in `startRollout` or make `RolloutController` call an injected `ConfigReverter`. Add integration test.
- **Owner:** distribution

## PA-3034: RolloutController `cleanup` runs at caller's discretion — completed rollouts accumulate indefinitely
- **Severity:** S3
- **Location:** `RolloutController.java:252-256`
- **Category:** resource
- **Evidence:** `cleanup()` is public but has no scheduler. If the caller never invokes it, the `rollouts` map grows forever.
- **Impact:** Memory leak over years of rollouts. Not an immediate SLO hit but hygiene.
- **Fix direction:** Either auto-cleanup on `startRollout` (piggyback) or document the cleanup cadence requirement and add a metric `rollout_map_size`.
- **Owner:** distribution

## PA-3035: RolloutController `RolloutTracker.soakTimes` global — cannot customize per-rollout
- **Severity:** S3
- **Location:** `RolloutController.java:97, 126-128`
- **Category:** ops
- **Evidence:** `soakTimes` is per-controller, not per-rollout. `setSoakTime` is a global mutation. Two concurrent rollouts cannot have different soak schedules without creating two controllers. Not a safety issue but an ops nuisance.
- **Impact:** Hygiene / ergonomics.
- **Fix direction:** Move soak times into `RolloutTracker` so each rollout captures its own schedule at start time.
- **Owner:** distribution

## PA-3036: RolloutController has no per-stage cohort-fraction enforcement — just a passive `Stage.fraction()` value
- **Severity:** S2
- **Location:** `RolloutController.java:36-52`
- **Category:** correctness
- **Evidence:** `Stage.CANARY.fraction()` returns 0.001 — but nothing in `RolloutController` communicates "send this delta to only 0.1% of nodes" to the distribution layer. The controller is purely a state machine; it provides a fraction value but does not gate fan-out. The enforcement of cohort membership is presumably in the caller, but this is implicit.
- **Impact:** If the caller forgets to gate fan-out by `Stage.fraction()`, every rollout is effectively IMMEDIATE.
- **Fix direction:** Take a `CohortFilter` at `startRollout` time. Have `RolloutController` own the fraction-to-nodeset mapping (deterministic hash of NodeId -> position in [0,1)). Expose `shouldDeliver(rolloutId, NodeId)` as the single gate.
- **Owner:** distribution

## PA-3037: No observability — zero metrics, counters, gauges, or structured logs in any of the ten classes
- **Severity:** S1
- **Location:** all files
- **Category:** observability
- **Evidence:** `grep -R "Metric\|Counter\|Gauge\|log\|Logger"` in the distribution package returns nothing. No `MetricRegistry` import, no slf4j, no micrometer. Errors are silently swallowed (PA-3002), deduplication is invisible (PA-3012), catch-up replay cost is invisible (PA-3022, PA-3025), slow-consumer cycles are invisible (PA-3005). Operators flying blind.
- **Impact:** Every PA-finding above is harder to diagnose in production because there is no instrumentation. INV-S1 p99 budget cannot be monitored without gauges.
- **Fix direction:** Add a standard metric taxonomy:
  - `fanout_events_dispatched_total{watch_id}`
  - `fanout_event_dispatch_latency_ns_bucket`
  - `coalescer_pending_high_watermark`
  - `plumtree_dedup_hit_ratio`
  - `plumtree_graft_total`, `plumtree_prune_total`
  - `hyparview_active_view_size`, `hyparview_passive_view_size`
  - `catchup_resolve_latency_ms_bucket`, `catchup_replay_chain_length`
  - `slow_consumer_state{node,state}`, `slow_consumer_readmits_total{node}`
  - `rollout_stage_age_seconds{rollout_id, stage}`, `rollout_healthPassing{rollout_id}`
  - `fanout_verify_reject_total` (once PA-3001 fix is implemented)
- **Owner:** distribution

## PA-3038: No per-subscription fairness — single noisy prefix starves others
- **Severity:** S2
- **Location:** `WatchService.java:262-289`
- **Category:** perf
- **Evidence:** `dispatchEvent` iterates `watches.values()` in HashMap iteration order (effectively random). There is no priority or round-robin; no fairness guarantee that a quiet-prefix watch that registered earlier gets dispatched before a noisy one registered later. Under load the HashMap iteration order can pessimize specific watchers.
- **Impact:** Unpredictable tail latency per watcher. Quiet watchers may occasionally be delayed.
- **Fix direction:** Maintain a `LinkedHashMap` for deterministic order, or sort watches by `cursor()` ascending (neediest first) on each tick.
- **Owner:** distribution

## PA-3039: `WatchService.flushAndDispatch` can be called concurrently with `tick` with no mutex — re-entrance breaks coalescer
- **Severity:** S1
- **Location:** `WatchService.java:209-234`
- **Category:** correctness
- **Evidence:** `tick()` and `flushAndDispatch()` both call `coalescer.flush()`. The class javadoc says single-threaded, but `flushAndDispatch` is described as "Useful for tests and shutdown draining" — test code and shutdown code typically run on a different thread than the I/O loop. If both execute, `coalescer.pending` (a plain `ArrayList`) is mutated concurrently → `ConcurrentModificationException` or data corruption.
- **Impact:** Shutdown drain race.
- **Fix direction:** Document that `flushAndDispatch` MUST be called from the same I/O thread as `tick`, or add a synchronized block around both (accept the perf hit on the one-time shutdown path).
- **Owner:** distribution

## PA-3040: HyParView `integrateSample` does not cap the passive view growth across repeated shuffles with new peers
- **Severity:** S3
- **Location:** `HyParViewOverlay.java:276-282`
- **Category:** resource
- **Evidence:** `integrateSample` calls `addToPassiveView` which is cap-enforced (line 243-250), so this is bounded per call. But the eviction choice is "first iterator entry" (line 246-249) — effectively random in `HashSet` order. Repeated shuffles will churn the passive view with no preference for freshness or reachability.
- **Impact:** Passive view quality degrades over time, harming `promotePassivePeer` quality.
- **Fix direction:** Track last-seen timestamp per passive entry; evict oldest-last-seen instead of hash-first.
- **Owner:** distribution

---

## Summary Tally
- S0 (data loss / unverified / ordering): **6** — PA-3001, PA-3002, PA-3003, PA-3004, PA-3011, PA-3021
- S1 (SLO miss / propagation): **16** — PA-3005, PA-3006, PA-3007, PA-3008, PA-3010, PA-3012, PA-3015, PA-3017, PA-3018, PA-3019, PA-3022, PA-3024, PA-3025, PA-3026, PA-3028, PA-3031, PA-3032, PA-3033, PA-3037, PA-3039
- S2 (UX degradation): **7** — PA-3009, PA-3013, PA-3014, PA-3016, PA-3023, PA-3029, PA-3036, PA-3038
- S3 (hygiene): **5** — PA-3020, PA-3027, PA-3030, PA-3034, PA-3035, PA-3040

## Coverage Attestation
All ten files in `configd-distribution-service/src/main/java/io/configd/distribution/` read in full (2067 L confirmed via `wc -l`). No TODO / FIXME / XXX markers were found. No references to Signer/Signature/Verify exist anywhere in the distribution package — R-01 / F-0052 residual is **unmitigated**; PA-3001 carries it forward at S0.

## Carry-Forward to Phase 2
- PA-3001 requires a verifier API in `configd-security` first; Phase 2 (security module audit) should surface whether that API exists and is plumbed into `DeltaApplier`. If not, PA-3001 escalates from "distribution unwired" to "verifier not implemented".
- PA-3011 / PA-3021 ACL integration depends on the namespace / multi-tenancy module (ADR-0017); Phase 2 should confirm whether a `NamespaceAuthorizer` exists to wire in.
- PA-3028 / PA-3029 (FanOutBuffer lock-free correctness) should be re-evaluated with JCStress or similar tooling in Phase 3.
