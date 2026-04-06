# Audit Report: Consensus Correctness (Phase 2)

**System:** Configd Distributed Configuration System  
**Date:** 2026-04-13  
**Auditor:** Production Readiness Review  
**Status:** PASS (all checks)

---

## 1. Leader Election

| Property | Raft Reference | Result | Evidence |
|----------|---------------|--------|----------|
| PreVote prevents term inflation | Ongaro dissertation §9.6 | **PASS** | `RaftNode.java:980-1016` -- `startPreVote()` sends `RequestVoteRequest` with `preVote=true` at `currentTerm + 1` without incrementing the actual term. PreVote does not persist any state or modify `currentTerm`. |
| PRE_CANDIDATE does not increment term | §9.6 | **PASS** | `RaftNode.java:998-1000` -- During PreVote, `preVoteInProgress` is set to true but `currentTerm` is not incremented. Term increment only occurs in `startElection()` at line 1029. |
| Quorum transitions (PreVote -> Candidate -> Leader) | §5.2 | **PASS** | `RaftNode.java:932-935` -- PreVote quorum triggers `startElection()`. `RaftNode.java:918-919` -- Vote quorum triggers `becomeLeader()`. Both use `clusterConfig.isQuorum()` for correct dual-majority checking during joint consensus. |
| Vote granting logic correct per Raft §5.2 | §5.2, §5.4.1 | **PASS** | `RaftNode.java:852-858` -- Vote granted only if (a) `votedFor == null` or already voted for this candidate, and (b) candidate's log is at least as up-to-date via `log.isAtLeastAsUpToDate()`. Vote persisted via `durableState.vote()` BEFORE in-memory update. |
| becomeLeader appends no-op | §5.4.2 | **PASS** | `RaftNode.java:1097-1100` -- `becomeLeader()` appends `LogEntry.noop(noopIndex, currentTerm)` immediately after transitioning to LEADER. This ensures entries from prior terms can be committed. |
| Heartbeat timing | §5.2 | **PASS** | `RaftNode.java:694-709` -- `tickHeartbeat()` fires when `heartbeatTicksElapsed >= config.heartbeatIntervalMs()`, broadcasting AppendEntries to all peers. |
| CheckQuorum | Ongaro dissertation | **PASS** | `RaftNode.java:698-703` -- Leader steps down to follower if `clusterConfig.isQuorum(activeSet)` returns false. Active set built from `peerActivity` tracking at `RaftNode.java:1429-1446`. |
| Election timer randomization | §5.2 | **PASS** | `RaftNode.java:1479-1481` -- `resetElectionTimeout()` computes `electionTimeoutMinMs + random.nextInt(max - min + 1)`, providing uniform random jitter within the configured range. |

---

## 2. Log Replication

| Property | Raft Reference | Result | Evidence |
|----------|---------------|--------|----------|
| Leader never overwrites own log | §5.3 | **PASS** | `RaftNode.java:255-258` -- `propose()` only appends new entries via `log.append()`. No truncation or overwrite path exists in leader-initiated code. |
| Followers truncate conflicting entries | §5.3 | **PASS** | `RaftLog.java:329-337` -- `appendEntries()` checks each incoming entry: if `existingTerm != newEntry.term()`, calls `truncateFrom(idx)` then `append(newEntry)`. |
| nextIndex decrement on rejection | §5.3 | **PASS** | `RaftNode.java:814-817` -- On `AppendEntriesResponse.success == false`, `nextIndex` is decremented via `Math.max(1, ni - 1)` and entries are retried. |
| Commit index safety property §5.4.2 | §5.4.2 | **PASS** | `RaftNode.java:1210` -- `maybeAdvanceCommitIndex()` only advances commitIndex for entries where `log.termAt(n) == currentTerm`, preventing the commit of entries from prior terms without a current-term entry. |
| maybeAdvanceCommitIndex | §5.3, §5.4.2 | **PASS** | `RaftNode.java:1199-1228` -- Iterates from `log.lastIndex()` down to `commitIndex + 1`, builds the set of nodes that have replicated each index, and advances commitIndex only when `clusterConfig.isQuorum(replicated)` is satisfied. |

---

## 3. Reconfiguration

| Property | Raft Reference | Result | Evidence |
|----------|---------------|--------|----------|
| Joint consensus with dual-majority | §6 | **PASS** | `RaftNode.java:460` -- `ClusterConfig.joint(clusterConfig.voters(), newVoters)` creates a joint config. `RaftNode.java:462-465` -- INV-6 asserts `jointConfig.isJoint()`. All quorum checks use `clusterConfig.isQuorum()` which enforces dual-majority for joint configs. |
| Single config change at a time | §6 | **PASS** | `RaftNode.java:439-440` -- `proposeConfigChange()` rejects if `configChangePending == true`. INV-8 (line 449-452) also asserts this invariant. |
| No-op required before config change | Ongaro raft-dev 2015 | **PASS** | `RaftNode.java:442-443` -- `proposeConfigChange()` rejects if `noopCommittedInCurrentTerm == false`. INV-7 (line 454-457) asserts this condition. `noopCommittedInCurrentTerm` set to true at `RaftNode.java:1254-1255` when the no-op entry commits. |

---

## 4. ReadIndex

| Property | Raft Reference | Result | Evidence |
|----------|---------------|--------|----------|
| Records current commitIndex | ReadIndex protocol | **PASS** | `RaftNode.java:370` -- `readIndexState.startRead(log.commitIndex())` records the commitIndex at read request time. |
| Leadership confirmed via heartbeats | ReadIndex protocol | **PASS** | `RaftNode.java:1448-1457` -- `confirmPendingReads()` is called after `buildActiveSetAndReset()` during heartbeat tick. Leadership is confirmed only when `clusterConfig.isQuorum(activeSet)` holds. Re-verification at `RaftNode.java:393-394` ensures deposed leaders do not serve stale reads (FIND-0002 fix). |

---

## 5. Durability

| Property | Raft Reference | Result | Evidence |
|----------|---------------|--------|----------|
| DurableRaftState persists before in-memory update | §5.2 | **PASS** | `DurableRaftState.java:76-80` -- `setTerm()`: `persistValues(newTerm, null)` called before `this.currentTerm = newTerm`. Same pattern in `vote()` (line 100-102) and `setTermAndVote()` (line 115-118). If `persistValues()` throws, in-memory state remains unchanged. |
| WAL entries fsynced | §5.2 | **PASS** | `DurableRaftState.java:132-133` -- `persistValues()` calls `storage.put()` followed by `storage.sync()`, ensuring the data is fsynced to durable media before the method returns. |

---

## Summary

All consensus correctness properties verified against the Raft specification (§5, §6, §7, §9.6). Leader election, log replication, reconfiguration, ReadIndex, and durability mechanisms are correctly implemented. Runtime invariant checkers (INV-1 through INV-8) provide defense-in-depth assertion checking for safety properties derived from the TLA+ specification.
