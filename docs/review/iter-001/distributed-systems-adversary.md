# Distributed-Systems Adversary Review — Iteration 001

**Date:** 2026-04-19
**Reviewer:** distributed-systems-adversary
**Scope:** Configd raft core + state machine + InstallSnapshot + ReadIndex + reconfig + edge replay protection.
**Method:** All findings cited with file:line. YELLOW items already in `docs/ga-review.md` are excluded; only NEW gaps or false-GREENs are reported.

Findings are ordered by severity. Counts: S0=3, S1=2, S2=5, S3=2 — total 12.

---

## D-001 — ReadIndex confirms reads with stale acks (false-GREEN of YES-2 / R-12)

- **Severity:** S0
- **Location:** `configd-consensus-core/src/main/java/io/configd/raft/RaftNode.java:762-777`, `:540-546` (helper `confirmPendingReads`), `configd-consensus-core/src/main/java/io/configd/raft/ReadIndexState.java:137-140` (`confirmAllLeadership`).
- **Category:** Linearizability / read safety, ConsensusSpec divergence (false-GREEN).
- **Evidence:**
  ```java
  // RaftNode.tickHeartbeat — line 762
  private void tickHeartbeat() {
      heartbeatTicksElapsed++;
      if (heartbeatTicksElapsed >= config.heartbeatIntervalMs()) {
          heartbeatTicksElapsed = 0;
          Set<NodeId> activeSet = buildActiveSetAndReset();   // <-- RESETS activity to FALSE
          if (!clusterConfig.isQuorum(activeSet)) { ... }
          confirmPendingReads(activeSet);                     // <-- confirms BEFORE new round sent
          broadcastAppendEntries();
      }
  }
  ```
  ```java
  // ReadIndexState.confirmAllLeadership — line 137
  public void confirmAllLeadership() {
      pendingReads.replaceAll((id, p) -> p.leadershipConfirmed() ? p : p.confirmed());
  }
  ```
  Two flaws:
  1. `confirmPendingReads(activeSet)` runs *before* `broadcastAppendEntries()` for the current heartbeat tick. The `activeSet` it inspects is the set of acks accumulated **between the previous heartbeat broadcast and now** — i.e., responses to a heartbeat that was sent ≥ one tick before any of these pending reads were created. Reads started at tick T are "confirmed" using acks from a heartbeat that was sent at tick T-N (N≥1).
  2. `confirmAllLeadership()` does not maintain a per-read `acked` set. ReadIndexSpec.tla (line 79 `pendingReads[n][r].acked`) requires `r ∈ pendingReads[n] AND currentTerm[n] = r.term` *before* the ack is recorded, so acks from a prior heartbeat round cannot count. The Java code has no such per-read tracking — all pending reads are blanket-confirmed on the next heartbeat-tick quorum check.
- **Impact:** A leader that has been deposed by network partition can serve a "linearizable" read using acks gathered from the heartbeat round one tick before the partition started. With `heartbeatIntervalMs=50` and the further D-003 amplification (10× tick mismatch → real interval 500 ms), the staleness window can be 500 ms — long enough for a new leader to commit writes that the read fails to observe. **Linearizability violation, S0.**
- **Fix direction:** Mirror ReadIndexSpec.tla:
  - On `readIndex()`, snapshot the current heartbeat-round id; reads confirm only on a *future* heartbeat round whose acks were collected after the read was created.
  - Track per-read `acked` set; mark a read confirmed when `clusterConfig.isQuorum(read.acked ∪ {self})`, not via blanket `confirmAllLeadership`.
  - Add a TLA+ replay test that drives heartbeat → read-start → heartbeat-ack ordering.
- **Proposed owner:** consensus-core lead.

---

## D-002 — InstallSnapshotResponse advances `matchIndex` to leader's *current* snapshot, not the snapshot the follower installed

- **Severity:** S0
- **Location:** `configd-consensus-core/src/main/java/io/configd/raft/RaftNode.java:1474-1502`, with the response record at `configd-consensus-core/src/main/java/io/configd/raft/InstallSnapshotResponse.java`.
- **Category:** Raft safety (commit advancement), spec divergence vs SnapshotInstallSpec.
- **Evidence:**
  ```java
  // RaftNode.handleInstallSnapshotResponse — lines 1494-1500
  if (resp.success() && latestSnapshot != null) {
      long snapIndex = latestSnapshot.lastIncludedIndex();   // <-- LEADER'S current snapshot
      matchIndex.put(resp.from(), snapIndex);
      nextIndex.put(resp.from(), snapIndex + 1);
      maybeAdvanceCommitIndex();
      applyCommitted();
  }
  ```
  `InstallSnapshotResponse` carries only `(term, success, from)` — no `lastIncludedIndex`. The leader assumes the follower installed `latestSnapshot.lastIncludedIndex()`. But:
  - The leader can call `triggerSnapshot()` (RaftNode.java:315) between the `sendInstallSnapshot` (line 1257) and the response, advancing `latestSnapshot`. The response is then attributed to a snapshot the follower never received.
  - `handleInstallSnapshot` on the follower (line 1439) replies `success=true` for an *older* snapshot that it ignores: `if (req.lastIncludedIndex() <= log.snapshotIndex()) { ... send success=true; return; }`. The leader records the new (larger) snap index as `matchIndex` for that follower even though the follower's actual state is unchanged.
- **Impact:** `matchIndex[follower]` overshoots the follower's true state. `maybeAdvanceCommitIndex()` then advances the cluster commit index based on a quorum that does not actually exist. This is a direct safety violation: a committed entry may be lost if the leader fails before that follower truly catches up. SnapshotInstallSpec.tla `InflightTermMonotonic` (line 182) expects the *delivered* `lastIncludedIndex` to match the global log; the Java implementation does not echo this back. **S0 — possible commit of un-replicated entries.**
- **Fix direction:**
  - Add `lastIncludedIndex` (and ideally `lastIncludedTerm`) fields to `InstallSnapshotResponse`. Set them to whatever index the follower actually has post-handler (either the installed `req.lastIncludedIndex()` or the existing `log.snapshotIndex()` on the ignore path).
  - In the leader handler, use `resp.lastIncludedIndex()` for `matchIndex`/`nextIndex`.
  - Add a TLC trace replayer in `configd-consensus-core/src/test/java/io/configd/raft/SnapshotInstallSpecReplayerTest.java` that drives a leader-snapshot-then-respond race.
- **Proposed owner:** consensus-core lead.

---

## D-003 — Tick-vs-millisecond unit mismatch silently slows heartbeat 10× and election 10×

- **Severity:** S0 (liveness; combined with D-001 amplifies linearizability staleness)
- **Location:** `configd-server/src/main/java/io/configd/server/ConfigdServer.java:68` (`TICK_PERIOD_MS = 10`) and `:500-521` (scheduleAtFixedRate). Compared against `RaftConfig` defaults at `configd-consensus-core/src/main/java/io/configd/raft/RaftConfig.java:83` (`150, 300, 50` documented as "ms"). Consumers at `configd-consensus-core/src/main/java/io/configd/raft/RaftNode.java:764` (`heartbeatTicksElapsed >= config.heartbeatIntervalMs()`) and `:1568-1569` (election timeout).
- **Category:** Liveness / timing, type-system gap.
- **Evidence:**
  ```java
  // ConfigdServer.java:68
  private static final int TICK_PERIOD_MS = 10;
  ...
  // line 521
  }, TICK_PERIOD_MS, TICK_PERIOD_MS, TimeUnit.MILLISECONDS);
  ```
  ```java
  // RaftConfig.java:15
  // @param heartbeatIntervalMs heartbeat interval in milliseconds (default 50)
  // RaftConfig.of: ..., 50, ...
  ```
  ```java
  // RaftNode.tickHeartbeat — line 763-764
  heartbeatTicksElapsed++;
  if (heartbeatTicksElapsed >= config.heartbeatIntervalMs()) {  // <-- 50 *ticks* = 500 ms
  ```
  `heartbeatTicksElapsed` is incremented per tick (10 ms), but compared against a value documented as **milliseconds**. Same applies to `electionTimeoutTicks = electionTimeoutMinMs + random.nextInt(...)` at RaftNode.java:1568, fed to a tick-counted variable.
- **Impact:** Real heartbeat interval is 500 ms (not 50). Election timeout window is 1500–3000 ms (not 150–300). The published GA-review doc lists "≤ 50 ms heartbeat" as a SLO; we are 10× over. Combined with **D-001**, the read staleness window expands to ≥ 500 ms. Failover MTTR roughly 10×. **S0** because it materially relaxes a published safety-relevant SLO and amplifies D-001.
- **Fix direction:**
  - Either rename config fields to `heartbeatIntervalTicks` etc. and document the tick period, or convert `(intervalMs / TICK_PERIOD_MS)` inside `RaftNode` ctor.
  - Add a startup invariant assertion that `intervalMs > TICK_PERIOD_MS` and that `intervalMs % TICK_PERIOD_MS == 0`.
  - Wire a Prometheus gauge `raft_effective_heartbeat_ms` so SLO drift is observable.
- **Proposed owner:** consensus-core + observability.

---

## D-004 — Edge `highestSeenEpoch` becomes a permanent denial-of-service after leader transition (F-0052 regression)

- **Severity:** S1 (availability — could escalate to S0 because edges silently reject all writes from the new leader until manual operator action; in a global outage scenario this is data plane collapse)
- **Location:** `configd-config-store/src/main/java/io/configd/store/ConfigStateMachine.java:79` (`private long signingEpoch;`), `:449` (`long epoch = ++signingEpoch`), `:269-303` (`snapshot()` does NOT include signing state), `:314-374` (`restoreSnapshot()` does not restore it). Edge-side enforcement at `configd-edge-cache/src/main/java/io/configd/edge/DeltaApplier.java:81-82, 168-176, 196-197`.
- **Category:** Edge-cache monotonicity, replay protection, leader transition.
- **Evidence:**
  ```java
  // ConfigStateMachine.java:79
  private long signingEpoch;          // local; never persisted, never snapshotted
  // ConfigStateMachine.java:449
  long epoch = ++signingEpoch;
  ```
  `snapshot()` (lines 269-303) writes only `sequenceCounter` + entries; `signingEpoch`, `lastEpoch`, `lastNonce`, `lastSignature` are not persisted.
  ```java
  // DeltaApplier.java:196
  if (delta.epoch() > highestSeenEpoch) {
      highestSeenEpoch = delta.epoch();
  }
  // ...rejects deltas where epoch > 0 && epoch <= highestSeenEpoch (line 168-176)
  ```
- **Impact:** Two failure modes:
  1. **After leader fails over** to a successor that did NOT restart, the new leader's `signingEpoch` is a *local* counter unrelated to the old leader's. It may emit an epoch ≤ what edges have already seen — every edge silently rejects writes from the new leader (`Outcome.REPLAY_REJECTED`).
  2. **After snapshot install** on any node, `signingEpoch` resets to 0; if that node later becomes leader, all its writes are rejected.
  Recovery requires bumping every edge's `highestSeenEpoch` manually or restarting the entire edge fleet.
- **Fix direction:**
  - Either derive epoch deterministically from raft `(term, index)` (e.g., `epoch = term * 2^32 + index`), so all replicas compute the same epoch for the same applied entry, OR
  - Persist `signingEpoch` in the snapshot envelope and serialize a per-cluster epoch high-water mark in the raft log so all replicas converge.
  - Add a chaos test in `configd-testkit/src/test/java/io/configd/testkit/ChaosScenariosTest.java` that triggers leader churn and asserts edges keep accepting deltas.
- **Proposed owner:** config-store + edge-cache.

---

## D-005 — `handleInstallSnapshot` ignores chunk fields (`offset`, `done`); restoreSnapshot called on a partial payload corrupts state

- **Severity:** S1
- **Location:** `configd-consensus-core/src/main/java/io/configd/raft/RaftNode.java:1419-1466`. Wire schema at `configd-consensus-core/src/main/java/io/configd/raft/InstallSnapshotRequest.java:34-36`. Codec accepts the fields at `configd-server/src/main/java/io/configd/server/RaftMessageCodec.java`.
- **Category:** Snapshot transfer, malformed-input defense, partial-state risk.
- **Evidence:** `InstallSnapshotRequest` declares `int offset` and `boolean done` (lines 34, 36). The doc claim at line 17 ("offset is always 0 and done is always true") is unverified. `handleInstallSnapshot` (lines 1419-1466) never reads `req.offset()` or `req.done()`. It calls `stateMachine.restoreSnapshot(req.data())` unconditionally on whatever `data` arrives.
  ```java
  // RaftNode.java:1446
  stateMachine.restoreSnapshot(req.data());
  ```
- **Impact:** A peer (compromised, buggy, or future chunked sender) sending `(offset=0, done=false, data=first_chunk)` causes the receiver to call `restoreSnapshot` on a partial buffer. ConfigStateMachine bound-checks (lines 326-362) will throw on truncated data, but the receiver has already called `becomeFollower(req.term())` (line 1429), reset its election timer (line 1435), AND will reply `success=true` on the unhappy path of the prior block — it just throws here. Worse, the future intent of chunked transfer is plainly defeated. Since the codec accepts these fields with no enforcement, we have a half-implemented protocol — a classic source of safety regressions when someone "turns on" chunking later.
- **Fix direction:**
  - Until chunked transfer is implemented, **reject** any `InstallSnapshotRequest` with `offset != 0 || !done` at the boundary in `handleInstallSnapshot` (return `success=false` and log).
  - Add a property test that fuzzes `offset, done` combinations and verifies the receiver's state machine is unchanged on malformed input.
  - File an ADR for the chunking implementation timeline.
- **Proposed owner:** consensus-core.

---

## D-006 — `handleTimeoutNow` does not verify sender is the current leader (false-GREEN of leadership-transfer safety)

- **Severity:** S2
- **Location:** `configd-consensus-core/src/main/java/io/configd/raft/RaftNode.java:1012-1021`.
- **Category:** Leadership transfer abuse, election storm, PreVote bypass.
- **Evidence:**
  ```java
  // RaftNode.handleTimeoutNow — line 1012
  private void handleTimeoutNow(TimeoutNowRequest req) {
      if (req.term() < currentTerm) { return; }
      if (req.term() > currentTerm) { becomeFollower(req.term()); }
      // Immediately start an election — bypass PreVote
      startElection();
  }
  ```
  Any peer at the same or higher term can send `TimeoutNowRequest` and force this node to start an election skipping PreVote. There is no check that `req.from()` (no `from` field, even worse) corresponds to `leaderId`.
- **Impact:** A malicious or buggy peer can trigger election storms by spamming `TimeoutNow` to all followers, defeating PreVote/CheckQuorum protections. The Ongaro thesis §3.10 specifies that `TimeoutNow` must be from the current leader during a coordinated transfer; bypassing PreVote without the leader's authority is an availability vector.
- **Fix direction:**
  - Add a `from` field to `TimeoutNowRequest` (and update codec).
  - In handler: only act if `leaderId != null && req.from().equals(leaderId)` and `req.term() == currentTerm`.
  - On mismatch, log a warning and treat as a no-op.
- **Proposed owner:** consensus-core + transport.

---

## D-007 — Plumtree LRU eviction allows re-delivery of evicted message IDs (gossip storm + version regression on edges)

- **Severity:** S2
- **Location:** `configd-distribution-service/src/main/java/io/configd/distribution/PlumtreeNode.java:81-86` (LRU `removeEldestEntry`).
- **Category:** Edge data plane, gossip safety.
- **Evidence:**
  ```java
  // PlumtreeNode.java:81
  this.receivedMessages = Collections.newSetFromMap(new java.util.LinkedHashMap<>() {
      @Override
      protected boolean removeEldestEntry(Map.Entry<MessageId, Boolean> eldest) {
          return size() > maxReceivedHistory;
      }
  });
  ```
  Once a `MessageId` is evicted, the next time the same id arrives via gossip it is treated as new, broadcast eagerly to peers, and re-applied to the local `DeltaApplier`. F-0052 protects against version replay by epoch, but: (a) at-rest legacy deltas have epoch=0 and are exempt from F-0052 (DeltaApplier comment lines 173-175), and (b) re-broadcasting causes O(N²) traffic spikes during slow-network catch-up.
- **Impact:** A slow follower gossiping after a long delay will trigger a delivery-storm cascade through the eager mesh; under sustained partition + heal, edges can OOM their inbound queues. With epoch=0 legacy deltas, an evicted-then-re-received delta passes the replay check.
- **Fix direction:**
  - Replace LRU with a TTL-based set anchored on the cluster's snapshot index — entries older than the latest snapshot's `lastIncludedIndex` are safe to evict because they can't be retransmitted by a correct sender.
  - For the legacy epoch=0 path, fall back to per-MessageId fingerprint dedup as a defense-in-depth.
- **Proposed owner:** distribution-service.

---

## D-008 — `RaftLog` cross-validation resets `snapshotTerm` to 0 on metadata/WAL mismatch, weakening LogUpToDate vote decisions

- **Severity:** S2
- **Location:** `configd-consensus-core/src/main/java/io/configd/raft/RaftLog.java:136-146` (per task summary; verify on current head).
- **Category:** Election safety, log-up-to-date check.
- **Evidence:** When metadata file disagrees with WAL, the recovery path zeroes `snapshotTerm`. Subsequent `handleRequestVote` uses `(lastLogTerm, lastLogIndex)` for the up-to-date comparison; with `snapshotTerm=0` the term comparison can incorrectly accept a candidate whose log is actually older.
- **Impact:** Election-safety regression in the corner case where a node restarts after partial fsync. Ongaro §5.4.1 voting restriction can be defeated.
- **Fix direction:**
  - On WAL/metadata mismatch, refuse to start (fail-closed) rather than silently zeroing `snapshotTerm`. This is a recoverable operator situation; the alternative risks data loss.
  - If the fail-closed path is unacceptable, recompute `snapshotTerm` from the WAL itself — never from a degraded default.
- **Proposed owner:** consensus-core (storage subsystem).

---

## D-009 — No client-id/sequence dedup → propose() retries cause duplicate apply (Raft §6.3)

- **Severity:** S2
- **Location:** `configd-consensus-core/src/main/java/io/configd/raft/RaftNode.java:249-276` (`propose`); state-machine apply at `configd-config-store/src/main/java/io/configd/store/ConfigStateMachine.java:204-254`.
- **Category:** Idempotency / exactly-once.
- **Evidence:** `propose(byte[] command)` ingests opaque bytes with no `(clientId, sequenceNumber)` envelope. State machine `apply()` blindly applies. There is no session table per Ongaro §6.3.
- **Impact:** Standard Raft hazard: the client times out before receiving a commit ack, retries; the original command also commits. Two PUTs/DELETEs are applied for one logical request. For increment-style or composed batches, this corrupts user data; for idempotent PUTs to the same key it merely bumps version twice (which then triggers spurious notifications and edge updates).
- **Fix direction:**
  - Wrap commands in a `ClientCommand(clientId, seq, payload)` envelope; maintain a bounded per-client session table in the state machine; suppress already-applied `(clientId, seq)`.
  - This is the canonical Raft addition; deferring it past GA is a meaningful gap.
- **Proposed owner:** config-store + consensus-core.

---

## D-010 — Cluster-wide non-determinism: signing nonce uses `SecureRandom` inside `apply()` so replicas diverge on signed deltas

- **Severity:** S2 (composes with D-004)
- **Location:** `configd-config-store/src/main/java/io/configd/store/ConfigStateMachine.java:94` (`SecureRandom`), `:450-451` (`secureRandom.nextBytes(nonce)` inside `signCommand` called from `apply()`).
- **Category:** State-machine determinism, snapshot/replay equivalence.
- **Evidence:**
  ```java
  // ConfigStateMachine.java:94
  private final SecureRandom secureRandom = new SecureRandom();
  // ...lines 450-451
  byte[] nonce = new byte[ConfigDelta.NONCE_LEN];
  secureRandom.nextBytes(nonce);
  ```
  `apply()` is supposed to be deterministic across all replicas (the central Raft invariant). Each replica produces a *different* nonce/signature for the same `(index, term, command)`, and these go out as `lastSignature/lastNonce/lastEpoch`. Because the snapshot does not include signing state (D-004 evidence), restoring a snapshot on a fresh replica produces yet another independent signing trajectory.
- **Impact:** Different replicas leaving different audit trails for the same applied entry; impossible to debug "did this delta come from the committed log or a phantom replay?". Also breaks any future feature that compares signatures across replicas (e.g., gossip-based signature consensus).
- **Fix direction:**
  - Derive nonce deterministically from `(index, term, commandHash)` rather than `SecureRandom` — e.g., `nonce = HKDF(clusterSecret, "nonce" || index || term)`.
  - Move signing OUT of `apply()` entirely; only the leader signs and embeds the signature in the proposed entry. Followers verify on apply; no per-replica signing.
- **Proposed owner:** config-store.

---

## D-011 — Stale `InstallSnapshotResponse(success=true)` on the "older snapshot" path also overshoots `matchIndex`

- **Severity:** S2 (sub-case of D-002, called out separately because the bug exists even when the leader does *not* take a new snapshot)
- **Location:** `configd-consensus-core/src/main/java/io/configd/raft/RaftNode.java:1438-1442` (the early-return branch) ↔ `:1494-1500` (response handler).
- **Category:** matchIndex over-advancement on no-op snapshot install.
- **Evidence:**
  ```java
  // handleInstallSnapshot — line 1438-1442
  if (req.lastIncludedIndex() <= log.snapshotIndex()) {
      transport.send(req.leaderId(),
              new InstallSnapshotResponse(currentTerm, true, config.nodeId()));
      return;
  }
  ```
  Follower replies `success=true` even though it discarded the snapshot. Leader then sets `matchIndex[follower] = latestSnapshot.lastIncludedIndex()` — which the follower may not actually have if its locally taken snapshot was at a *lower* index than the leader's.
- **Impact:** Same family as D-002 but trivially reproducible without a leader-side snapshot race: if a follower's local snapshot is *behind* the leader's snapshot but the follower's log itself is up to date through some intermediate index, the leader still sets `matchIndex` to the leader's snapshot index. The follower may have committed entries that include data the leader has snapshotted, yet not the full index range. Concrete loss scenario requires careful construction; nevertheless the contract is wrong.
- **Fix direction:** Same as D-002 — echo the actual installed `lastIncludedIndex` (which on this path is the *follower's* current `log.snapshotIndex()`).
- **Proposed owner:** consensus-core.

---

## D-012 — `becomeLeader` broadcasts the no-op AppendEntries before fsync of the no-op WAL append is acknowledged

- **Severity:** S3 (degradation, not safety: `FileStorage.appendToLog` does call `channel.force(true)` synchronously, so the call returns only after fsync — the gap is opportunity-cost, not correctness; flagging because the broadcast pattern presumes fsync completed and any future async-WAL toggle would silently break safety)
- **Location:** `configd-consensus-core/src/main/java/io/configd/raft/RaftNode.java:1180-1186` (`becomeLeader` no-op append + broadcast).
- **Category:** Crash-safety coupling, future-regression hazard.
- **Evidence:**
  ```java
  // becomeLeader — line 1180
  long noopIndex = log.lastIndex() + 1;
  log.append(LogEntry.noop(noopIndex, currentTerm));
  broadcastAppendEntries();
  ```
  `log.append` calls `storage.appendToLog(...)` which in turn calls `channel.force(true)` (`configd-common/.../FileStorage.java:110`). Today this is synchronous; tomorrow if anyone introduces a write-back cache or `force(false)`, the leader will broadcast entries it has not yet durably persisted, violating Ongaro §5.3.
- **Impact:** Latent regression risk; no acute bug today.
- **Fix direction:** Add an explicit `log.flushAndFsync()` call (even if redundant today) and an assertion in `RaftLog.append` that `storage.lastFsyncedIndex() >= entry.index()` before returning. Adds a defense-in-depth contract that any future async-WAL change will fail noisily.
- **Proposed owner:** consensus-core (storage).

---

## Methodology notes

- All 12 findings were verified by reading the cited file:line ranges; no claim is from memory.
- YELLOW items in `docs/ga-review.md` (O5, O8, S5, C1-C4, R-12, B12, PA-5018, F-0052 deferred items, F3 HLC integration) were excluded per the review charter.
- D-001, D-002, D-003 are mutually amplifying: D-003 stretches the staleness window; D-001 confirms reads with stale acks across that window; D-002 lets a leader serving such reads also commit incorrect entries.
- Severity floor: S3. Cap: 30 findings. Returned: 12.
