# Red Team Consensus Attack Analysis

**Date:** 2026-04-13
**Scope:** Configd Raft consensus implementation (`configd-consensus-core`)
**Methodology:** Adversarial scenario construction against 10 attack vectors

---

## Attack 1: SPLIT VOTE STORM

**Scenario:** All nodes have identical or near-identical election timeouts, causing every election to split. Repeated split votes exhaust the cluster's ability to elect a leader (liveness failure).

**Analysis:**

The election timeout is randomized in `RaftNode.resetElectionTimeout()` (RaftNode.java:1434-1436):
```java
electionTimeoutTicks = config.electionTimeoutMinMs()
    + random.nextInt(config.electionTimeoutMaxMs() - config.electionTimeoutMinMs() + 1);
```

With defaults `electionTimeoutMinMs=150` and `electionTimeoutMaxMs=300` (RaftConfig.java:83), the range is [150, 300], giving 151 distinct timeout values. Additionally, PreVote prevents term inflation during repeated failed elections (RaftNode.java:935-970), so even if split votes occur, terms do not spiral upward.

**Does the code prevent it?** YES. The randomization range of 2x the minimum timeout is the standard Raft prescription, and PreVote further limits damage.

**Test coverage:**
- `RaftNodeTest.PreVoteTests.preVotePreventsTermInflationFromPartitionedNode` verifies no term inflation.
- `SeedSweepTest.electionSafety` runs 10,000 seeds checking election safety across 2000 ticks.

**However:** There is NO dedicated test that measures *liveness* -- i.e., that a leader is always eventually elected within a bounded number of rounds under a healthy network. The SeedSweepTest checks safety (at most 1 leader per term), not liveness (a leader is elected). The `electLeader` helpers in `ConsistencyPropertyTests.ClusterHarness` silently return -1 on timeout without failing, and the tests `return` early on failure.

**FINDING RT-01 (Medium/Liveness):** No test asserts that a leader *must* be elected within a bounded time under a fully connected network. A pathological seed could cause unbounded election rounds without detection. Recommend adding a liveness property test that fails if no leader is elected within, say, 5000 ticks under a healthy network, across all sweep seeds.

---

## Attack 2: LEADER ISOLATION

**Scenario:** The old leader is network-partitioned. Before CheckQuorum detects the partition and steps it down, the leader continues accepting writes. These writes will never commit (no quorum), but they occupy log space and may confuse clients.

**Analysis:**

CheckQuorum is implemented in `tickHeartbeat()` (RaftNode.java:649-663). It runs on every heartbeat interval (50ms). The `peerActivity` map starts with all peers set to `TRUE` on election (RaftNode.java:1049), so the first heartbeat pass after becoming leader always succeeds. After that first check, activity is reset to `FALSE` (RaftNode.java:1396-1398). If no responses arrive before the next heartbeat, the leader steps down.

**Critical window:** There is a window of up to **100ms** (two heartbeat intervals) where the leader is isolated but still accepts writes:
- 0-50ms: First heartbeat fires, peerActivity was initialized to TRUE, so CheckQuorum passes. Activity is reset to FALSE.
- 50-100ms: Second heartbeat fires. No responses arrived. CheckQuorum fails. Leader steps down.

During this 100ms window, `propose()` continues accepting commands (RaftNode.java:235-261). These entries will never commit because there is no quorum, but they bloat the log.

**Does the code prevent harm?** PARTIALLY. The uncommitted entries are harmless from a safety perspective -- they will be overwritten by the new leader. But client-facing code must handle `ACCEPTED` results that never commit.

**Test coverage:**
- `RaftNodeTest.CheckQuorumTests.leaderStepsDownWithoutQuorum` covers the step-down.
- CERT-0013 covers ReadIndex invalidation on step-down.

**FINDING RT-02 (Low/Correctness):** No test verifies that writes accepted during the pre-step-down window are truly never committed. A test should: (1) partition the leader, (2) accept a write, (3) verify it never commits even after a new leader is elected and the partition heals. This is implicitly covered by Raft safety (the new leader will overwrite), but an explicit test would strengthen confidence.

---

## Attack 3: LOG DIVERGENCE

**Scenario:** Conflicting entries at the same index survive log truncation. If `appendEntries()` fails to truncate divergent entries, two nodes could permanently disagree on committed state.

**Analysis:**

Log conflict resolution is implemented in `RaftLog.appendEntries()` (RaftLog.java:313-341):

```java
long existingTerm = termAt(idx);
if (existingTerm == -1) {
    append(newEntry);           // Beyond current log
} else if (existingTerm != newEntry.term()) {
    truncateFrom(idx);          // Conflict -- truncate and replace
    append(newEntry);
}
// else: same term -- skip (idempotent)
```

This correctly handles all three cases. The `truncateFrom()` method (RaftLog.java:350-363) removes entries from the given index onward. After the recomputation, `recomputeConfigFromLog()` is called (RaftNode.java:714) to ensure config state is consistent.

**Does the code prevent it?** YES.

**Test coverage:**
- `RaftNodeTest.LogConflictTests.followerTruncatesDivergentEntries` directly tests truncation at a conflict point.
- `RaftNodeTest.LogConflictTests.followerRejectsIfPrevLogDoesNotMatch` tests the mismatch rejection.
- CERT-0014 tests config entry truncation and revert.
- `RaftLogWalTest.appendAfterTruncationIsRecoveredCorrectly` tests WAL persistence through truncation.

**VERDICT:** Well covered. No finding.

---

## Attack 4: SNAPSHOT + RECONFIG RACE

**Scenario:** A follower is in the middle of a reconfiguration (has a joint config entry in its log). It then receives an InstallSnapshot whose `lastIncludedIndex` is past the joint config entry. If the snapshot does not carry config state, the follower loses the reconfig and reverts to the original config -- potentially creating a split-brain where some nodes use C_old and others use C_new.

**Analysis:**

The `triggerSnapshot()` method (RaftNode.java:301-316) captures the current cluster config:
```java
byte[] configData = serializeConfigChange(clusterConfig);
latestSnapshot = new SnapshotState(snapshotData, appliedIndex, appliedTerm, configData);
```

The `sendInstallSnapshot()` method (RaftNode.java:1120-1142) includes `clusterConfigData` in the request.

The `handleInstallSnapshot()` method (RaftNode.java:1288-1335) passes the config data to `recomputeConfigFromLog()`:
```java
recomputeConfigFromLog(req.clusterConfigData());
```

And `recomputeConfigFromLog(byte[] snapshotConfigData)` (RaftNode.java:559-585) first scans the remaining log for config entries, then falls back to the snapshot config:
```java
if (snapshotConfigData != null && isConfigChangeEntry(snapshotConfigData)) {
    clusterConfig = deserializeConfigChange(snapshotConfigData);
}
```

**Does the code prevent it?** YES, with a caveat.

**FINDING RT-03 (Medium/Safety):** The backward-compatible `InstallSnapshotRequest` constructor (InstallSnapshotRequest.java:43-47) sets `clusterConfigData = null`. Several test files (InstallSnapshotTest.java) use this constructor, meaning those tests exercise the *null config* path. While the production code always uses the full constructor (via `sendInstallSnapshot()`), a bug in a custom transport or a third-party implementation could send snapshots without config data. When `snapshotConfigData` is null AND the log is empty after compaction AND `latestSnapshot` is also null, the code falls back to the initial static config (RaftNode.java:580-583), which may be wrong if a reconfig has occurred.

**Specific dangerous scenario:**
1. Leader takes snapshot at index 10, which includes a committed C_new config.
2. Leader sends InstallSnapshot to follower F with the config data.
3. F receives it and correctly adopts C_new.
4. F crashes and restarts with no durable latestSnapshot.
5. On restart, F re-creates RaftNode with the original static config from `RaftConfig.peers()`.
6. F's log is compacted (empty), so `recomputeConfigFromLog()` finds no config entries.
7. F falls back to the initial config -- **config state is lost**.

This is partially mitigated because the snapshot itself would be re-sent by the leader after F restarts, but there is a window where F uses the wrong config.

**Test coverage:** No test covers InstallSnapshot with config data during an active reconfig. CERT-0012 tests leader failure during joint consensus but not the snapshot interaction.

---

## Attack 5: COMMIT INDEX REGRESSION

**Scenario:** The commitIndex decreases, causing the state machine to re-apply or skip entries.

**Analysis:**

All code paths that modify commitIndex:

1. **`RaftLog.setCommitIndex(long)`** (RaftLog.java:371-375):
   ```java
   if (newCommitIndex > commitIndex) {
       this.commitIndex = Math.min(newCommitIndex, lastIndex());
   }
   ```
   The guard `newCommitIndex > commitIndex` prevents regression. The `Math.min` with `lastIndex()` prevents setting commitIndex beyond the log.

2. **`handleAppendEntries` follower path** (RaftNode.java:718-721):
   ```java
   if (req.leaderCommit() > log.commitIndex()) {
       long lastNewIndex = ...;
       log.setCommitIndex(Math.min(req.leaderCommit(), lastNewIndex));
   }
   ```
   The outer `>` guard plus `setCommitIndex`'s own guard provides double protection.

3. **`handleInstallSnapshot`** (RaftNode.java:1321-1323):
   ```java
   if (req.lastIncludedIndex() > log.commitIndex()) {
       log.setCommitIndex(req.lastIncludedIndex());
   }
   ```
   Also guarded.

4. **`maybeAdvanceCommitIndex`** (RaftNode.java:1154-1183): Only moves commitIndex forward by calling `log.setCommitIndex(n)` where `n > log.commitIndex()` (the for loop starts at `log.lastIndex()` and checks `n > log.commitIndex()`).

**Does the code prevent it?** YES. All paths are guarded by monotonicity checks.

**Test coverage:**
- Implicit in many tests, but no test explicitly verifies commitIndex monotonicity across operations.

**FINDING RT-04 (Low/Testing gap):** While the code structurally prevents commitIndex regression through the guard in `setCommitIndex`, there is no explicit property test that instruments `setCommitIndex` to assert it never decreases. Adding an invariant checker to the simulation harness that tracks commitIndex monotonicity per node would catch any future regression.

---

## Attack 6: READINDEX LINEARIZABILITY

**Scenario:** A leader issues a ReadIndex, records commitIndex=5. The leader then gets partitioned but hasn't stepped down yet (CheckQuorum hasn't fired). A new leader is elected and commits entries 6, 7, 8. The old leader's heartbeat responses come back (from before the partition) confirming "leadership." The old leader serves a stale read at commitIndex=5, missing entries 6-8.

**Analysis:**

The ReadIndex protocol requires:
1. Record commitIndex at read start (RaftNode.java:336: `readIndexState.startRead(log.commitIndex())`)
2. Confirm leadership via heartbeat quorum response
3. Wait for `lastApplied >= readIndex`

The key question: can stale heartbeat responses from *before* the partition trick the old leader into thinking it still holds leadership?

**CheckQuorum mechanism:**
- `buildActiveSetAndReset()` (RaftNode.java:1384-1401) builds the active set and **resets all activity to FALSE** after each check.
- `confirmPendingReads()` (RaftNode.java:1409-1413) confirms reads only if the active set forms a quorum.

The critical sequence:
1. Leader issues ReadIndex at tick T.
2. Leader sends heartbeats.
3. Before heartbeat responses arrive, partition starts.
4. Heartbeat responses from *before* the partition arrive.
5. `handleAppendEntriesResponse` sets `peerActivity.put(resp.from(), TRUE)` (RaftNode.java:754).
6. Next heartbeat tick: `buildActiveSetAndReset()` builds the active set, finds quorum, confirms reads.
7. Leader serves stale read.

**Wait -- is this actually possible?** For this to work, the heartbeat responses must arrive between the partition start and the next heartbeat tick. If the partition is total (messages dropped), no responses can arrive. But if responses were *in flight* when the partition started, they could arrive.

**However:** After those in-flight responses are processed and activity is reset, the *next* heartbeat will fail CheckQuorum (no new responses from the partitioned side), and the leader steps down, clearing all pending reads via `readIndexState.clear()` in `becomeFollower()` (RaftNode.java:926).

The vulnerability is: if the ReadIndex is confirmed by the in-flight heartbeat responses AND `lastApplied >= readIndex` is already true, the read could be served *in the same tick* before the next CheckQuorum fires.

**Does the code prevent it?** MOSTLY. The step-down clears reads, but there is a one-heartbeat-interval window (50ms) where a read confirmed by in-flight responses could be served.

**FINDING RT-05 (High/Safety - Linearizability):** There is a theoretical linearizability gap in the ReadIndex protocol. If a partition occurs and in-flight heartbeat responses arrive, they can confirm a ReadIndex for the old leader. If `lastApplied >= readIndex`, the read becomes ready and can be served before the next CheckQuorum fires. The new leader may have committed entries that the old leader does not see, violating linearizability.

The fix would be to track the heartbeat *round* -- each ReadIndex should only be confirmed by responses to heartbeats sent *after* the ReadIndex was issued. Currently `confirmPendingReads()` confirms *all* pending reads based on generic peer activity, not per-round tracking.

**Test coverage:**
- CERT-0013 tests read invalidation on step-down (higher term arriving).
- `ConsistencyPropertyTests.LinearizabilityTest.linearizabilitySurvivesLeaderFailover` tests a controlled failover scenario.
- **But no test constructs the in-flight heartbeat response scenario described above.**

---

## Attack 7: LEADERSHIP TRANSFER + CRASH

**Scenario:** Leader L1 sends `TimeoutNow` to follower F. F starts an election and wins (becomes L2). Meanwhile, L1 hasn't stepped down yet because it hasn't seen F's higher term. Now there are two leaders: L1 (old term) and L2 (new term).

**Analysis:**

When the leader sends `TimeoutNow` (RaftNode.java:1419-1428):
```java
transport.send(transferTarget, new TimeoutNowRequest(currentTerm, config.nodeId()));
transferTarget = null;
```

The target F receives `TimeoutNow` (RaftNode.java:899-908) and calls `startElection()`, which increments the term:
```java
durableState.setTermAndVote(currentTerm + 1, config.nodeId());
currentTerm++;
```

F sends `RequestVote` at term T+1. When F wins and sends `AppendEntries` at term T+1, L1 will see the higher term and step down.

**Can two leaders coexist?** YES, briefly -- but only in *different terms*. L1 is leader at term T, L2 is leader at term T+1. This is permitted by Raft: the invariant is *at most one leader per term*, not *at most one leader at any instant*.

**Does the code prevent safety violations?** YES. L1 at term T cannot commit any new entries because:
1. L1 sets `transferTarget = null` after sending TimeoutNow.
2. L1 does NOT step down immediately -- but it will step down when it receives any message from term T+1.
3. Proposals are not blocked after TimeoutNow is sent (transferTarget is already null).

**Wait -- this IS a problem.** After sending TimeoutNow, `transferTarget` is cleared, so `propose()` will accept new proposals again. L1 is still leader at term T. If a client sends a proposal to L1 in this window:
- L1 appends the entry at term T.
- L1 sends AppendEntries to followers.
- Followers have already voted for F at term T+1, so they reject L1's term-T AppendEntries.
- The entry never commits.

**Does the code prevent it?** The entry never commits, so it's safe. The client gets `ACCEPTED` but the command never commits -- same as any leader that loses its majority.

**Test coverage:**
- `RaftNodeTest.LeadershipTransferTests.leadershipTransferToTarget` covers the happy path.
- No test covers the race where proposals arrive after TimeoutNow but before step-down.

**FINDING RT-06 (Low/Liveness):** After sending `TimeoutNow`, the old leader clears `transferTarget` and can accept new proposals that will never commit. This is safe but wastes client effort. The old leader should arguably reject proposals immediately after initiating a transfer, not just during the catch-up phase. Currently, the rejection happens only while `transferTarget != null`, which is cleared as soon as `TimeoutNow` is sent.

---

## Attack 8: PRE-VOTE LIVELOCK

**Scenario:** Two candidates both start PreVote. Each rejects the other's PreVote because both have "recent leader" (their own PreVote response). Neither ever wins PreVote, so neither starts a real election. Permanent livelock.

**Analysis:**

The "hasRecentLeader" check in `handlePreVoteRequest` (RaftNode.java:839-841):
```java
boolean hasRecentLeader = role == RaftRole.FOLLOWER
    && leaderId != null
    && electionTicksElapsed < electionTimeoutTicks;
```

Critical: when a follower's election timer fires in `tickElection()` (RaftNode.java:633-646):
```java
if (electionTicksElapsed >= electionTimeoutTicks) {
    electionTicksElapsed = 0;
    leaderId = null;  // <-- THIS IS KEY
    startPreVote();
}
```

The `leaderId = null` ensures that after a node's own election timeout fires, it no longer considers itself as having a "recent leader." This means:
1. Node A times out, clears leaderId, starts PreVote.
2. Node B receives A's PreVote. If B still has a recent leader (leaderId != null, timer not expired), B rejects.
3. B eventually times out too, clears leaderId, starts its own PreVote.
4. A receives B's PreVote. A is now either still in PreVote (leaderId is null, role might be FOLLOWER with preVoteInProgress=true) -- the `hasRecentLeader` check passes because leaderId is null.

**Wait:** When A starts PreVote, does A's role change? No. Looking at `startPreVote()` (RaftNode.java:935-970): it sets `preVoteInProgress = true` but does NOT change `role` from FOLLOWER. So A is still a FOLLOWER.

When B's PreVote arrives at A:
- `role == RaftRole.FOLLOWER` -- true
- `leaderId != null` -- **false** (cleared when timeout fired)
- Therefore `hasRecentLeader = false`
- A checks log up-to-dateness and grants PreVote to B.

**Does the code prevent it?** YES. The key insight is `leaderId = null` on election timeout (RaftNode.java:644), which breaks the livelock cycle. This was clearly an intentional fix -- the comment at line 639-643 explicitly explains this.

**Test coverage:**
- No specific PreVote livelock test exists.

**FINDING RT-07 (Low/Testing gap):** While the code correctly handles PreVote livelock via `leaderId = null` on timeout (with an explanatory comment), there is no test that constructs the exact two-candidate PreVote livelock scenario and verifies that one of them eventually wins. The SeedSweepTest may cover this probabilistically but not deterministically.

---

## Attack 9: TERM CONFUSION

**Scenario:** A stale RPC from an old term arrives and corrupts state. For example, an `AppendEntriesResponse` from term 3 arrives when the node is at term 5.

**Analysis:**

Every message handler checks the term:

- **AppendEntriesRequest** (RaftNode.java:672): `if (req.term() < currentTerm)` -- reject.
- **AppendEntriesResponse** (RaftNode.java:743-750): `if (resp.term() > currentTerm)` -- step down. `if (resp.term() != currentTerm)` -- ignore stale.
- **RequestVoteRequest** (RaftNode.java:793-798): `if (req.term() < currentTerm)` -- reject.
- **RequestVoteResponse** (RaftNode.java:851-854): `if (resp.term() > currentTerm)` -- step down. Lines 866-867: `if (resp.term() != currentTerm)` -- ignore.
- **InstallSnapshotRequest** (RaftNode.java:1289-1293): `if (req.term() < currentTerm)` -- reject.
- **InstallSnapshotResponse** (RaftNode.java:1349-1356): Higher term -> step down. Not current term -> ignore.
- **TimeoutNowRequest** (RaftNode.java:900): `if (req.term() < currentTerm)` -- return (ignore).

**Does the code prevent it?** YES. Every handler either rejects or ignores stale-term messages. Higher-term messages cause appropriate step-down.

**Test coverage:**
- `InstallSnapshotTest.DirectInstallSnapshotTests.followerRejectsSnapshotWithStaleTerm`
- `InstallSnapshotTest.InstallSnapshotResponseHandlingTests.leaderIgnoresStaleTermSnapshotResponse`
- `InstallSnapshotTest.InstallSnapshotResponseHandlingTests.leaderStepsDownOnHigherTermInSnapshotResponse`
- Implicit in many election tests.

**FINDING RT-08 (Low/Testing gap):** No explicit test sends a stale-term `AppendEntriesResponse` to a leader and verifies it is ignored (not just that it doesn't crash). The `CertificationTest.InflightCountSafety.inflightCountClampedAtZero` tests a spurious response but at the current term. A test sending a response from term T-2 when leader is at term T would strengthen coverage.

---

## Attack 10: QUORUM OVERLAP DURING JOINT CONSENSUS

**Scenario:** During joint consensus (C_old,new), the quorum calculation requires only the old majority OR only the new majority (instead of BOTH). This could allow a split commit: old-majority nodes commit entry X while new-majority nodes commit a different entry Y at the same index.

**Analysis:**

`ClusterConfig.isQuorum()` (ClusterConfig.java:117-123):
```java
if (!joint) {
    return countIntersection(respondents, voters) >= majorityOf(voters.size());
}
return countIntersection(respondents, voters) >= majorityOf(voters.size())
    && countIntersection(respondents, newVoters) >= majorityOf(newVoters.size());
```

The `&&` ensures BOTH majorities are required. This is used in:
- `maybeAdvanceCommitIndex()` (RaftNode.java:1178): `clusterConfig.isQuorum(replicated)`
- `handleRequestVoteResponse()` (RaftNode.java:873): `clusterConfig.isQuorum(votesReceived)`
- `handlePreVoteResponse()` (RaftNode.java:887): `clusterConfig.isQuorum(preVotesReceived)`
- `tickHeartbeat()` (RaftNode.java:657): `clusterConfig.isQuorum(activeSet)`
- `confirmPendingReads()` (RaftNode.java:1410): `clusterConfig.isQuorum(activeSet)`

**Does the code prevent it?** YES. The dual-majority check is consistently applied everywhere that matters.

**Test coverage:**
- `ClusterConfigTest.JointConfig.isQuorumRequiresBothMajorities` explicitly tests the dual-majority logic with both passing and failing cases.
- CERT-0012 tests joint consensus with leader failure.
- `ReconfigurationTest.SafetyInvariants` tests config preservation.

**VERDICT:** Well covered. No finding.

---

## Summary of Findings

| ID | Severity | Category | Description |
|---|---|---|---|
| RT-01 | Medium | Liveness | No liveness property test: no test asserts a leader is eventually elected under a healthy network |
| RT-02 | Low | Testing gap | No test verifies writes accepted during pre-CheckQuorum step-down window never commit |
| RT-03 | Medium | Safety | InstallSnapshot without `clusterConfigData` (backward-compat constructor) can cause config loss after follower restart with compacted log |
| RT-04 | Low | Testing gap | No explicit commitIndex monotonicity invariant checker in simulation |
| RT-05 | **High** | **Safety** | ReadIndex linearizability gap: in-flight heartbeat responses can confirm reads after partition starts, before CheckQuorum fires |
| RT-06 | Low | Correctness | Old leader accepts proposals after sending TimeoutNow (transferTarget cleared) that will never commit |
| RT-07 | Low | Testing gap | No deterministic test for two-candidate PreVote livelock resolution |
| RT-08 | Low | Testing gap | No test for stale-term AppendEntriesResponse being properly ignored |

### Critical Finding: RT-05 ReadIndex Linearizability Gap

This is the most dangerous finding. The attack sequence:

1. Leader L at term T has `commitIndex=5`, `lastApplied=5`.
2. Client requests ReadIndex. L records `readIndex=5`, starts tracking.
3. L sends heartbeat to all followers.
4. Network partition isolates L from all followers.
5. Heartbeat responses that were **already in-flight** before the partition arrive at L.
6. `handleAppendEntriesResponse` marks peers as active in `peerActivity`.
7. Next `tickHeartbeat` fires (up to 50ms later): `buildActiveSetAndReset()` builds active set from in-flight responses, `confirmPendingReads()` confirms the ReadIndex.
8. `isReadReady(readId)` returns true because `lastApplied(5) >= readIndex(5)`.
9. Client reads stale data from L.
10. Meanwhile, new leader L2 at term T+1 has committed entries 6, 7, 8 that L does not know about.

**Mitigation options:**
- (a) Tag each ReadIndex with a "heartbeat generation" and only confirm from responses to heartbeats sent *after* the ReadIndex was issued.
- (b) Require the confirming heartbeat round to include the ReadIndex's commit index in the request, so followers can reject if they've seen a higher term.
- (c) Reduce the CheckQuorum interval to match the heartbeat interval (currently both are 50ms, so this is already the case -- meaning the window is exactly one heartbeat round, ~50ms).

Note: option (c) is already in effect since `heartbeatIntervalMs == 50` and CheckQuorum runs at the heartbeat interval. This means the window is at most one heartbeat round. In practice, the in-flight response would need to arrive in the tick *between* the last activity reset and the next CheckQuorum check. Given 1ms tick granularity and 1-10ms network latency, this is a narrow but nonzero window.
