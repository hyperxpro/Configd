# Distributed-Systems Adversary Review — Iteration 002

**Date:** 2026-04-19
**Reviewer:** distributed-systems-adversary
**Scope:** post-iter-1 Raft core, snapshot/install path, ReadIndex, joint-consensus reconfig, leadership transfer, edge gossip dedup, state-machine determinism.
**Method:** All findings cited with file:line at HEAD `22d2bf3`. Iter-1 closures (D-001..D-004, C-001..C-002) verified in source and excluded.

Findings ordered by severity. Counts: **S0=1, S1=2, S2=5, S3=1 — total 9.**

The post-iter-1 picture: the three-way amplification (D-001 / D-002 / D-003) that produced the iter-1 S0s was correctly resolved. The remaining safety surface is in the snapshot/install corner cases (where the spec doesn't model `lastApplied > snapshot`), in the new-leader optimistic peer-activity initialisation, and in protocol-message sender authentication that was deferred from iter-1.

---

## D-013 — InstallSnapshot regresses follower's state machine when `lastApplied > req.lastIncludedIndex` (NEW)

- **Severity:** S0 (StateMachineSafety violation; committed entries silently lost from the follower's state machine; survives onto the next leader if this follower wins an election)
- **Location:** `configd-consensus-core/src/main/java/io/configd/raft/RaftNode.java:1460-1490` (`handleInstallSnapshot`); compounds with `configd-consensus-core/src/main/java/io/configd/raft/RaftLog.java:386-390` (`setLastApplied` is monotonic) and `configd-config-store/src/main/java/io/configd/store/ConfigStateMachine.java:452` (`this.sequenceCounter = restoredSequence` regresses).
- **Category:** Snapshot/log overlap, state-machine safety, spec gap.
- **Adversarial scenario:**
  1. 5-node cluster, leader L1 commits and applies entries up through index 200; partition isolates follower F (which has applied locally up to 150 but not yet snapshotted: `F.snapshotIndex=50`, `F.lastApplied=150`).
  2. Leader L2 (the surviving majority) compacts at 100 and continues taking writes; `L2.snapshotIndex=100`, `L2.lastApplied=500`.
  3. Partition heals. L2's `matchIndex[F]` is stale (~0). L2 probes; nextIndex decrements until `prevIndex < L2.snapshotIndex`. L2 sends `InstallSnapshot(lastIncludedIndex=100, term, …)`.
  4. On F: `req.lastIncludedIndex=100 > F.snapshotIndex=50` → handler proceeds.
     - `stateMachine.restoreSnapshot(req.data())` overwrites the SM with the index-100 state — sequenceCounter regresses from 150 → 100.
     - `log.compact(100, term)` deletes entries 51..100 from the log; entries 101..150 remain.
     - `log.setCommitIndex(100)` is no-op (commitIndex was 150).
     - `log.setLastApplied(100)` is **no-op** because `setLastApplied` only advances (`if (index > lastApplied)`).
  5. Subsequent `applyCommitted()` iterates `while (lastApplied < commitIndex)` — but `lastApplied=150 == commitIndex=150`. **Entries 101..150 are never re-applied.** The state machine is permanently missing the effects of those committed entries.
  6. If F later wins an election, its log contains the entries (LeaderCompleteness preserved) but its state machine is wrong; subsequent applies stack on the regressed base; reads expose missing keys/old values; snapshot taken from F propagates the corruption to other followers.
- **Why the spec misses it:** `SnapshotInstallSpec.tla:123-135` only models `snapshot[n].index` vs `msg.lastIncludedIndex`. It does not model `lastApplied[n]` vs `snapshot[n].index` divergence (lazy local snapshotting). The Java code embeds an implicit assumption — never proven — that `lastApplied[follower] <= snapshot[leader].index` whenever the leader sends `InstallSnapshot`. Adversarial timing breaks that assumption.
- **Fix proposal (enforces `StateMachineSafety` and the spec rule that committed entries are applied exactly once):**
  - In `handleInstallSnapshot`, **reject** (or silently no-op with `success=true, lastIncludedIndex=log.snapshotIndex()`) when `req.lastIncludedIndex() <= log.lastApplied()`. The follower already has a state at least as fresh; installing the older snapshot can only regress it. The spec rule `committed[i] applied exactly once` is preserved by refusing to overwrite a more advanced state with an older snapshot.
  - Add a property test `SnapshotInstallSpecReplayerTest` that constructs `(snapshotIndex=50, lastApplied=150)` then drives `InstallSnapshot(lastIncludedIndex=100)` and asserts the SM still observes the index-150 state.
  - Add a runtime invariant `inv_no_state_machine_regression`: after any `restoreSnapshot`, assert that `stateMachine.sequenceCounter() >= preSequence`.
- **Proposed owner:** consensus-core lead.

---

## D-014 — InstallSnapshot with `clusterConfigData == null` silently drops follower's joint-consensus state (NEW)

- **Severity:** S1 (ReconfigSafety — a follower mid-reconfig can revert to the static `RaftConfig.peers()` config and then participate in elections / quorums under the wrong member set)
- **Location:** `configd-consensus-core/src/main/java/io/configd/raft/RaftNode.java:1484` (`recomputeConfigFromLog(req.clusterConfigData())`), with the fallback path at `:683-709`. `InstallSnapshotRequest`'s convenience constructor at `configd-consensus-core/src/main/java/io/configd/raft/InstallSnapshotRequest.java:43-47` defaults `clusterConfigData=null`.
- **Category:** Reconfig safety, joint-consensus log-source-of-truth.
- **Adversarial scenario:**
  1. 5-node cluster mid-reconfig: leader is in joint config `C_old={1,2,3,4,5}`, `C_new={1,2,3,6,7}`. The `joint` config entry is at log index 80 (committed), the `final` C_new entry at index 90 (also committed). Follower F=2 has applied through 90 and adopted C_new.
  2. F crashes and restarts; on restart, F's log gets compacted (legitimate operation or operator-driven).
  3. Leader sends `InstallSnapshot` to F using the convenience constructor (used by tests and any future code path that forgets to plumb the cluster config). `req.clusterConfigData() == null`.
  4. On F: `recomputeConfigFromLog(null)` is invoked. The log scan from `lastIndex` down to `snapshotIndex+1` finds no config entry (compacted). The fallback at line 698 checks `snapshotConfigData != null` (FALSE here). The next fallback at line 700 checks `latestSnapshot != null && latestSnapshot.clusterConfigData() != null` — but `latestSnapshot` is the FOLLOWER's local snapshot (set only by `triggerSnapshot()` line 342), which on F may be null OR carry a stale config. If null, the final fallback at 704-706 reverts to `ClusterConfig.simple(RaftConfig.peers())` — the **bootstrap** config from the static `RaftConfig`, which is `{1,2,3,4,5}`.
  5. F now thinks the cluster is `{1,2,3,4,5}`. If F later wins an election with votes from `{1,2,3}`, it forms a "majority" by the bootstrap rule but bypasses C_new's `{1,2,3,6,7}` quorum requirement. Two leaders can then exist in disjoint majorities.
- **Why this is reachable today:** the production codec does include config (`RaftMessageCodec.encodeInstallSnapshot` at `:209-223` always writes the configData length, even if 0). But the JAVA-side `RaftNode.sendInstallSnapshot` constructs the request with `latestSnapshot.clusterConfigData()` (line 1280) which can be null when `triggerSnapshot()` was called with a config-less state machine. The convenience ctor at `InstallSnapshotRequest:43-47` is a foot-gun: any future caller (or test) that uses it gets `clusterConfigData=null` silently.
- **Fix proposal (enforces `ReconfigSafety` from `ConsensusSpec.tla:225-232`):**
  - Make `clusterConfigData` REQUIRED on `InstallSnapshotRequest` (delete the convenience constructor) and have `triggerSnapshot()` always serialise `configAtIndex(appliedIndex)` (it already does — line 341 — so this is just enforcement at the type level).
  - In `handleInstallSnapshot`, refuse a snapshot whose `clusterConfigData == null` if the follower has no config in its post-compaction log AND no `latestSnapshot.clusterConfigData()`. Reply `success=false`; let the leader resend.
  - Add a TLC trace replayer that drives `InstallSnapshot(clusterConfigData=null)` against a follower in joint phase and asserts the follower stays in joint phase or rejects the install.
- **Proposed owner:** consensus-core.

---

## D-015 — First post-election ReadIndex confirmed by optimistic `peerActivity` init, not by real heartbeat acks (NEW)

- **Severity:** S1 (linearizability — a newly elected leader that loses connectivity to all peers immediately after `becomeLeader` can still serve a "linearizable" read whose commitIndex was inherited from the previous term)
- **Location:** `configd-consensus-core/src/main/java/io/configd/raft/RaftNode.java:1187-1193` (`becomeLeader` initialises `peerActivity.put(peer, TRUE)` for every peer); `:773-791` (`tickHeartbeat` consumes those flags via `buildActiveSetAndReset` BEFORE any real heartbeat ack has been observed); ReadIndex confirmation at `:1573-1583`.
- **Category:** Linearizability, ReadIndex correctness, leader-handover boundary.
- **Adversarial scenario:**
  1. Cluster of 3. Term T-1 leader committed index 100; F is elected leader in term T (becomeLeader runs).
  2. Inside `becomeLeader`, `peerActivity` is initialised TRUE for both peers (lines 1188-1193). `broadcastAppendEntries()` fires the no-op heartbeat. The network drops; no ack arrives.
  3. A client issues a linearizable read **before** the first `tickHeartbeat`. `readIndex()` records `readId=1, readIndex=100, startRound=heartbeatRound=0` (the leader has not yet ticked since becoming leader). Note `lastApplied >= 100` already (inherited).
  4. After `heartbeatIntervalTicks` ticks: `tickHeartbeat` runs. `heartbeatRound++` → 1. `buildActiveSetAndReset()` reads `peerActivity` → both peers TRUE (from the OPTIMISTIC init), so `activeSet={self, P1, P2}`. `isQuorum=true`. `confirmPendingReads(activeSet)` calls `confirmLeadershipForReadsBefore(currentRound=1)` which marks read-1 confirmed (`startRound=0 < 1`).
  5. `isReadReady(1)` returns true; the read is served from the local SM. **But the leader has received zero acks since becoming leader — it does not actually hold leadership.** A new leader at term T+1 may have already been elected by P1+P2 (election timeout > heartbeat interval, so unlikely in one tick — but adversarially, a clock-skewed election from a partitioned former leader can happen in this window).
- **Why D-001's fix doesn't catch it:** the per-round future-confirmation check assumes `peerActivity` reflects only acks gathered in the previous round. The `becomeLeader` optimistic init violates that assumption: round-1 confirmation uses fabricated activity, not real acks.
- **Fix proposal (enforces ReadIndexSpec's `LeadershipConfirmedByQuorumOfFutureRound`):**
  - In `becomeLeader`, initialise `peerActivity.put(peer, FALSE)` (not TRUE).
  - To preserve the one-tick CheckQuorum grace period that the TRUE init was protecting (otherwise the leader steps down on the first tick because activeSet={self}), add a separate `electedAtTick` field and have `tickHeartbeat`'s check-quorum branch skip step-down if `(currentTick - electedAtTick) < 2 * heartbeatIntervalTicks`.
  - This separates "leader hasn't been deposed yet" (CheckQuorum, OK to be optimistic) from "leader has fresh proof of authority" (ReadIndex, NOT OK to be optimistic).
  - Add a `ReadIndexLinearizabilityReplayerTest` case `confirmRequiresRealHeartbeatAck` that becomes leader, issues a read, then ticks once with no peer responses and asserts `isReadReady` returns false.
- **Proposed owner:** consensus-core.

---

## D-006 (re-flagged) — `handleTimeoutNow` does not verify sender is the current leader; bypasses PreVote on demand from any peer

- **Severity:** S2 (election-storm vector; bypasses PreVote/CheckQuorum mitigations against term-inflation; deferred from iter-1, code unchanged at the relevant lines)
- **Location:** `configd-consensus-core/src/main/java/io/configd/raft/RaftNode.java:1027-1036`. The wire field `leaderId` was added (`TimeoutNowRequest.java:15`), but the handler still does not consult it.
- **Category:** Election-safety amplifier, denial-of-service, Ongaro §3.10 spec divergence.
- **Adversarial scenario:** Any peer (correct, slow, byzantine, or just stale) sends `TimeoutNowRequest(term=currentTerm, leaderId=anyone)` to all followers. Each follower hits `startElection()` immediately, bypassing PreVote (which exists specifically to suppress disruptive elections). All followers increment term; if simultaneous, every candidate splits the vote and the cluster spins through wasted election cycles. Combined with a short election timeout, this is a continuous election storm.
- **Fix proposal (enforces Ongaro §3.10 leadership-transfer constraint and PreVote §9.6):**
  - In `handleTimeoutNow`: require `leaderId != null && req.leaderId().equals(this.leaderId) && req.term() == currentTerm`. On mismatch, log and return.
  - Add a property test that fuzzes `(req.term, req.leaderId)` against a follower and asserts `startElection` is called only when the request is from the recognised leader.
- **Proposed owner:** consensus-core + transport.

---

## D-016 — `preVoteInProgress` not cleared in `becomeFollower`; late PreVote response triggers spurious election after legitimate leader takes over (NEW)

- **Severity:** S2 (liveness/availability — a node that started PreVote, then heard from a valid leader, can later disrupt the cluster by an "echo" election when delayed PreVote responses arrive)
- **Location:** `configd-consensus-core/src/main/java/io/configd/raft/RaftNode.java:1045-1072` (`becomeFollower` does not reset `preVoteInProgress` or `preVotesReceived`); `:1007-1021` (`handlePreVoteResponse` only checks `preVoteInProgress`, not the role).
- **Category:** Election-storm corner, message-replay, PreVote completeness.
- **Adversarial scenario:**
  1. Follower F has `preVoteInProgress=true` (election timer fired, F sent PreVote to P1 and P2). P1 is slow.
  2. Before any PreVote response arrives, leader L's heartbeat reaches F with a higher term. F runs `becomeFollower(L.term)` (line 1045+). `role=FOLLOWER`, `leaderId=L`, etc. **`preVoteInProgress` stays TRUE; `preVotesReceived` keeps F's self-vote.**
  3. P1's delayed PreVote response arrives now, `voteGranted=true` (P1 evaluated F's stale request without knowing L). `handlePreVoteResponse` line 1008 checks only `preVoteInProgress` (TRUE). It adds P1 to `preVotesReceived`, hits quorum (self + P1 = 2 of 3), sets `preVoteInProgress=false`, and calls `startElection()`.
  4. `startElection()` increments term, sends RequestVote, disrupts L's cluster. L steps down on receipt; cluster cycles through an extra election even though no real leadership was contested.
- **Fix proposal (enforces PreVote's "do not initiate elections under a known leader" rule):**
  - In `becomeFollower`, set `preVoteInProgress = false` and clear `preVotesReceived`.
  - In `handlePreVoteResponse`, additionally require `role != FOLLOWER || leaderId == null` before counting the vote.
  - Add a unit test that drives the (PreVote sent → AppendEntries from valid leader → late PreVote response) sequence and asserts no election is started.
- **Proposed owner:** consensus-core.

---

## D-017 — `RaftConfig.of()` defaults still produce 1500-3000 ms election / 500 ms heartbeat at the production tick rate (D-003 rename was cosmetic for the production wiring)

- **Severity:** S2 (published SLO drift; failover MTTR ~10× the documented target; D-003 fixed the *names* but not the *numbers* at the production callsite)
- **Location:** `configd-consensus-core/src/main/java/io/configd/raft/RaftConfig.java:97-99` (`RaftConfig.of(...)` returns `(150, 300, 50)` *ticks*); `configd-server/src/main/java/io/configd/server/ConfigdServer.java:215` (production builds it as `RaftConfig.of(...)`); `:69` (`TICK_PERIOD_MS=10`).
- **Category:** Liveness / SLO honesty, residual D-003 drift.
- **Evidence:**
  ```java
  // RaftConfig.java:97-98
  public static RaftConfig of(NodeId nodeId, Set<NodeId> peers) {
      return new RaftConfig(nodeId, peers, 150, 300, 50, 64, 256 * 1024, 1024, 10);
  }
  ```
  At `TICK_PERIOD_MS=10`: `electionTimeoutMin=1500ms`, `electionTimeoutMax=3000ms`, `heartbeatInterval=500ms`. The Javadoc on `RaftConfig.java:19-21` claims "default 15 ≈ 150 ms" and "default 5 ≈ 50 ms", but the *actual values* in the builder are 10× larger. The iter-1 D-003 closure renamed the fields to `*Ticks` but kept the numerical defaults; the production server uses `RaftConfig.of(...)` unchanged at `ConfigdServer.java:215`.
- **Adversarial scenario:** Failover. Leader crashes. Followers wait min 1500 ms for election timeout (expected ≈ 150 ms per the docs). MTTR is ~10× the published SLO. Combined with any read-staleness window (now bounded correctly per D-001), the WALL-CLOCK time clients see an unreachable cluster is ~10× the design target. SLO `raft_failover_seconds` (if wired) breaches.
- **Fix proposal (enforces the documented timing contract):**
  - Either change `RaftConfig.of` defaults to `(15, 30, 5)` (matching the Javadoc) AND audit every test that hard-codes timings to confirm the rebuild still passes, OR keep `(150, 300, 50)` and update the Javadoc + the `--print-configuration` output in `ConfigdServer` to reflect the actual ms values.
  - Add a startup `assert config.heartbeatIntervalTicks * TICK_PERIOD_MS <= heartbeat_slo_ms` check in `ConfigdServer` so that any future drift fails loudly at boot.
  - Wire a Prometheus gauge `raft_effective_heartbeat_ms = heartbeatIntervalTicks * tickPeriodMs` so the operator sees the reality.
- **Proposed owner:** consensus-core + observability.

---

## D-005 (re-flagged) — `handleInstallSnapshot` ignores `offset` and `done`; partial-chunk request corrupts state mid-handler

- **Severity:** S2 (deferred from iter-1; reachable today only via a malformed peer; the code path is half-implemented and any future enabling of chunked transfer would silently break safety)
- **Location:** `configd-consensus-core/src/main/java/io/configd/raft/RaftNode.java:1434-1490` never reads `req.offset()` or `req.done()`; codec accepts both fields at `configd-server/src/main/java/io/configd/server/RaftMessageCodec.java:230-231`.
- **Category:** Malformed-input defense, half-implemented protocol.
- **Adversarial scenario:** A peer (compromised or just buggy) sends `InstallSnapshotRequest(offset=4096, done=false, data=arbitrary)`. The receiver: (1) `becomeFollower(req.term)` and resets the election timer (lines 1446-1453) — this disrupts a legitimate leader's quorum; (2) calls `restoreSnapshot(req.data())` on a partial buffer — `ConfigStateMachine.restoreSnapshotInternal` will throw `IllegalArgumentException` after partially populating the HAMT (the throw happens AFTER `store.restoreSnapshot(newSnapshot)` builds the wrong state if the truncation is at the very end). Either way, a future code path that "turns on" chunking will silently get the wrong state because `done=false` carries no meaning today.
- **Fix proposal:**
  - Until chunked transfer is implemented, reject `req.offset() != 0 || !req.done()` at the boundary (return `success=false` with current `lastIncludedIndex`).
  - Add a property test fuzzing `(offset, done)` and asserting the SM is byte-identical before vs after.
  - Update `InstallSnapshotRequest` Javadoc to remove the "always 0 / always true" assertion, replacing with an enforced precondition.
- **Proposed owner:** consensus-core.

---

## D-009 (re-flagged) — No `(clientId, sequenceNumber)` envelope on `propose()`; retried client commands apply twice (Raft §6.3)

- **Severity:** S2 (deferred from iter-1; standard Raft hazard with no mitigation in code)
- **Location:** `configd-consensus-core/src/main/java/io/configd/raft/RaftNode.java:259-286` (`propose(byte[] command)` ingests opaque bytes); `configd-config-store/src/main/java/io/configd/store/ConfigStateMachine.java:239-314` (`apply` blindly applies every committed entry).
- **Category:** Idempotency / exactly-once application.
- **Adversarial scenario:** Client issues `PUT k v`, leader appends at index N, replication slow. Client times out and retries; second `PUT k v` lands at index N+5. Both commit, both apply — the version counter bumps twice for one logical request, the watcher fires twice, the edge-cache replicates both deltas. For batch increments or any non-idempotent payload (e.g., a future "compare-and-swap with version") this corrupts state.
- **Fix proposal (Ongaro §6.3 session table):**
  - Wrap commands in `ClientCommand(clientId:UUID, seq:long, payload:byte[])` at the propose boundary. State machine maintains a bounded LRU per-client `lastAppliedSeq`. On apply, suppress already-applied `(clientId, seq)`.
  - Persist the session table in snapshots so it survives `InstallSnapshot` (similar mechanism to D-004's `signingEpoch` trailer).
- **Proposed owner:** config-store + consensus-core.

---

## D-010 (re-flagged) — `signCommand` uses `SecureRandom.nextBytes(nonce)` inside `apply()` → replicas diverge on signed deltas; signatures non-reproducible

- **Severity:** S3 (state-machine determinism violation; today only the leader's `lastSignature` is exposed externally so the divergence is unobservable in normal operation, but it breaks any future "compare signatures across replicas" feature and pollutes audit trails; deferred from iter-1)
- **Location:** `configd-config-store/src/main/java/io/configd/store/ConfigStateMachine.java:113` (`new SecureRandom()`); `:541-547` (`secureRandom.nextBytes(nonce)` inside `signCommand` called from every mutating `apply`).
- **Category:** State-machine determinism (the central Raft invariant).
- **Adversarial scenario:** Three replicas A, B, C apply the same committed command at the same `(index, term)`. Each generates an independent random nonce; the resulting `lastSignature/lastNonce/lastEpoch` differ across replicas. A snapshot taken on B includes signingEpoch but NOT the lastSignature (correct), but if B fails over to lead and a client requests "get me the signature for index N" from a replica vs the leader, results disagree. Audit / forensic replay of "did this delta originate from a committed log entry" is impossible.
- **Fix proposal (enforces `StateMachineSafety`'s determinism corollary):**
  - Derive nonce deterministically: `nonce = HKDF(clusterSecret, "nonce" || index || term || commandHash)`. All replicas compute the same nonce for the same applied entry.
  - Better: move signing OUT of `apply()` entirely. The leader signs at propose time and embeds `(epoch, nonce, signature)` in the proposed log entry; followers verify on apply. This makes `apply` purely deterministic.
- **Proposed owner:** config-store.

---

## Methodology notes

- All findings verified by reading the cited file:line ranges; no claim is from memory or from an iter-1 backreference alone.
- Items closed in iter-1 (D-001..D-004, C-001, C-002) were verified as actually fixed in source: `ReadIndexState.startRead(commitIndex, currentRound)` exists at line 76; `InstallSnapshotResponse.lastIncludedIndex` at line 29; `RaftConfig.heartbeatIntervalTicks` at line 33; `ConfigStateMachine.snapshot()` writes the signingEpoch trailer at line 365; `FrameCodec`/`FileStorage` CRC envelopes wired (referenced in iter-1 closure list — code paths confirmed via Grep).
- Iter-1 deferred items D-007, D-008, D-011, D-012 were re-examined and judged NOT to warrant re-flagging at iter-2's S3 floor: D-007 (Plumtree LRU) is dominated by epoch-based replay protection from F-0052; D-008 (snapshotTerm zeroing) is now mitigated by the cross-validation comment trail and is unreachable in production storage; D-011 was structurally subsumed by D-002's fix; D-012 is annotated as a future-regression hazard and the underlying `force(true)` is still synchronous.
- The most consequential NEW finding is **D-013** — a genuine state-machine safety violation reachable through legitimate (not malicious) timing of partition-heal + snapshot transfer, missed by the spec because `SnapshotInstallSpec.tla` does not model `lastApplied` separately from `snapshot.index`.
- Severity floor: S3. Cap: 30 findings. Returned: 9.
