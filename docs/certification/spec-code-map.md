# TLA+ Spec ↔ Java Implementation Mapping

> Generated during Phase 0 of terminal certification, updated Phase 2.
> Commit: HEAD
> Date: 2026-04-11

## Action Mapping

| # | TLA+ Action | Java Location | Status | Notes |
|---|---|---|---|---|
| A1 | `BecomeCandidate(n)` | `RaftNode.startElection():851` + `startPreVote():808` | FIXED | Was missing `n ∈ VotingMembers(config[n])` guard. Added non-voter check. |
| A2 | `GrantVote(n, m)` | `RaftNode.handleRequestVote():652` | FIXED | Was missing `m ∈ VotingMembers(config[m])` guard. Added non-voter check. |
| A3 | `BecomeLeader(n)` | `RaftNode.becomeLeader():890` | OK | Uses `clusterConfig.isQuorum(votesReceived)` — correct dual-majority. |
| A4 | `LeaderAppendNoOp(n)` | `RaftNode.becomeLeader():923` | OK | Appends `LogEntry.noop(noopIndex, currentTerm)` on election. |
| A5 | `ClientRequest(n, v)` | `RaftNode.propose():235` | OK | Checks leader state, appends with current term. Rejects RCFG-prefixed commands (CERT-0018). Adds backpressure (impl concern). |
| A6 | `ProposeConfigChange(n, newMembers)` | `RaftNode.proposeConfigChange():370` | OK | Checks: leader, noop committed, no pending, stable phase, new != old. Immediately adopts joint config. |
| A7 | `CommitJointConfig(n)` | `RaftNode.handleCommittedConfigChange():1205` | FIXED | Was clearing `configChangePending` too early + followers prematurely transitioning to C_new. Now: leader-only C_new transition, followers keep joint config until C_new arrives (CERT-0006, CERT-0017). |
| A8 | `AppendEntry(n, m)` | `RaftNode.sendAppendEntries():948` + `handleAppendEntries():569` | FIXED | Was NOT recomputing `clusterConfig` from log after receiving entries. Added `recomputeConfigFromLog()`. TLA+ spec line 451: `config' = EffectiveConfig(newLog)`. |
| A9 | `AdvanceCommitIndex(n)` | `RaftNode.maybeAdvanceCommitIndex():1022` | OK | Scans high-to-low, checks current-term only (Figure 8 safe), uses `clusterConfig.isQuorum()`. |
| A10 | `EdgeApply(e)` | `RaftNode.applyCommitted():1056` | OK | Advances lastApplied up to commitIndex. |

## Invariant Mapping

| # | TLA+ Invariant | Java Runtime Assertion | Status | Notes |
|---|---|---|---|---|
| INV-1 | `ElectionSafety` | `becomeLeader():892` | Partial | Checks quorum locally. Cross-node guarantee is structural (voting protocol). |
| INV-2 | `LeaderCompleteness` | `becomeLeader():898` | Partial | Checks `lastIndex >= commitIndex`. Full property guaranteed by vote restriction. |
| INV-3 | `LogMatching` | `handleAppendEntries():602` | OK | Spot-checks last appended entry. Structural guarantee from prevLog check. |
| INV-4 | `StateMachineSafety` | `applyCommitted():1069` | Weak | Only checks `entry.index() == nextApply` (tautological). Cross-node property structural. |
| INV-5 | `VersionMonotonicity` | `applyCommitted():1061` | OK | Checks `nextApply > lastApplied`. |
| INV-6 | `NoStaleOverwrite` | None | Missing | Same as StateMachineSafety — structural guarantee. |
| INV-7 | `ReconfigSafety` | `proposeConfigChange():414` | OK | Asserts joint config. Dual-majority enforced by `ClusterConfig.isQuorum()`. |
| INV-8 | `SingleServerInvariant` | `proposeConfigChange():401` | OK | Checks `!configChangePending`. |
| INV-9 | `NoOpBeforeReconfig` | `proposeConfigChange():394` | OK | Checks `noopCommittedInCurrentTerm`. |

## Crash Safety Mapping (Raft §5.2)

| Requirement | Java Location | Status | Notes |
|---|---|---|---|
| `currentTerm` persisted before use | `DurableRaftState.setTerm()` | FIXED | Persist-before-update pattern (CERT-0015, CERT-0016) |
| `votedFor` persisted before use | `DurableRaftState.vote()` | FIXED | Persist-before-update pattern (CERT-0015, CERT-0016) |
| WAL atomic rewrite | `RaftLog.rewriteWal()` | FIXED | Uses rename-over pattern, no delete-then-rename window (CERT-0004) |
| Snapshot metadata persisted | `RaftLog.compact()` | FIXED | `SNAPSHOT_META_KEY` persists snapshotTerm for recovery (CERT-0019) |
| Config state in snapshots | `RaftNode.triggerSnapshot()` | FIXED | `SnapshotState.clusterConfigData` preserves config across compaction (CERT-0020) |

## Key Helper Mapping

| TLA+ Operator | Java Method | Status |
|---|---|---|
| `EffectiveConfig(logSeq)` | `RaftNode.recomputeConfigFromLog()` | NEW — added to fix FINDING-1 |
| `IsQuorumOf(Q, cfg)` | `ClusterConfig.isQuorum(Set<NodeId>)` | OK |
| `QuorumsOf(cfg)` | `ClusterConfig.isQuorum(Set<NodeId>)` | OK (set-based, not enumerated) |
| `VotingMembers(cfg)` | `ClusterConfig.allVoters()` | OK |
| `LogUpToDate(candidate, voter)` | `RaftLog.isAtLeastAsUpToDate()` | OK |
| `HasUncommittedConfigEntry(n)` | `RaftNode.configChangePending` (boolean flag) | OK |
| `LeaderCommittedNoOpInTerm(n)` | `RaftNode.noopCommittedInCurrentTerm` (boolean flag) | OK |
