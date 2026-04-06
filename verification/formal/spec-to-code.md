# TLA+ Specification to Java Code Verification Report

Date: 2026-04-13

## TASK 1: TLA+ Spec Analysis

### 1.1 Invariant Coverage vs. Consistency Contract

The consistency contract (docs/consistency-contract.md) defines the following formal invariants:

| Contract Invariant | In TLA+ Spec? | Status |
|---|---|---|
| INV-L1 (Linearizability) | NO | NOT modeled. Requires real-time ordering, which TLA+ state invariants cannot express. Would need a refinement mapping or a separate linearizability spec. |
| INV-S1/S2 (Edge Staleness Bound) | NO | NOT modeled. These are quantitative timing properties (p99 < 500ms). TLA+ does not model wall-clock time. |
| INV-M1/M2 (Monotonic Reads) | NO | NOT modeled. Client-session monotonic reads require modeling clients with cursors. |
| INV-V1/V2 (Version Monotonicity, Gap-Free Sequence) | Partially (INV-5) | INV-5 checks `edgeVersion[e] >= 0` which is TRIVIALLY TRUE (see below). |
| INV-W1/W2 (Write Ordering) | NO | NOT modeled. Per-key total order and HLC ordering are not in the spec. |
| INV-RYW1 (Read-Your-Writes) | NO | NOT modeled. Requires modeling client sessions. |
| INV-1 (ElectionSafety) | YES | PASS |
| INV-2 (LeaderCompleteness) | YES (weakened) | See analysis below |
| INV-3 (LogMatching) | YES | PASS |
| INV-4 (StateMachineSafety) | YES | PASS |
| INV-5 (VersionMonotonicity) | YES (trivial) | MEANINGLESS (see below) |
| INV-6 (NoStaleOverwrite) | YES | PASS (identical to INV-4) |
| INV-7 (ReconfigSafety) | YES | PASS |
| INV-8 (SingleServerInvariant) | YES | PASS |
| INV-9 (NoOpBeforeReconfig) | YES | PASS |

**Summary: 6 of 15 contract invariants are NOT modeled in TLA+ at all. The spec covers only the core Raft safety properties, not the application-level consistency guarantees.**

### 1.2 Specific Invariant Weaknesses

#### INV-2 (LeaderCompleteness) — "State-level approximation"

The spec comment (lines 153-160) acknowledges this is a state-level approximation. The property checks:
> For any two leaders visible in the CURRENT state where one has a higher term, the higher-term leader's log contains all entries committed by the lower-term leader.

This is INSUFFICIENT because:
- It only compares leaders alive simultaneously in the same state. A leader that crashed and was replaced is never compared.
- The true Leader Completeness property is temporal: "for any leader elected in term T, its log contains all entries committed in terms < T." This requires comparing across time, not within a single state snapshot.
- The spec argues this is "guaranteed structurally by the voting restriction" and "verified transitively by LogMatching + StateMachineSafety passing together." This is a reasonable argument but unverified in the model checker — it depends on an informal proof that the voting restriction (LogUpToDate) is sufficient.

**VERDICT: Weak but defensible.** The structural argument is sound for standard Raft, but the approximation could miss bugs in the joint consensus extensions where quorum overlap properties are more complex. Recommend: add a history variable tracking committed entries across terms and check the full temporal property.

#### INV-3 (LogMatching) — Strength assessment

```tla
LogMatching ==
    \A n, m \in Nodes:
        \A i \in 1..Min(Len(log[n]), Len(log[m])):
            (log[n][i].term = log[m][i].term) =>
                \A j \in 1..i: log[n][j] = log[m][j]
```

This is the STANDARD formulation of the Log Matching Property from the Raft paper: "if two logs contain an entry with the same index and term, then the logs are identical in all entries up through that index."

**VERDICT: Correct and strong enough.** This is the canonical formulation.

#### INV-5 (VersionMonotonicity) — TRIVIALLY TRUE

```tla
VersionMonotonicity ==
    \A e \in Nodes: edgeVersion[e] >= 0  \* Strengthened in temporal property
```

This checks that `edgeVersion >= 0`. Since `edgeVersion` starts at 0 (Init) and only increases (EdgeApply sets it to `commitIndex[e]` which is >= 0), this is always true. **This is NOT a monotonicity check.** A real monotonicity check would be:

```tla
\* What it SHOULD be (as a temporal property):
VersionMonotonicityReal ==
    \A e \in Nodes: []( edgeVersion[e] <= edgeVersion'[e] )
```

Or using a history variable:
```tla
\* Track previous edgeVersion
prevEdgeVersion' = edgeVersion
VersionMonotonicityStrong ==
    \A e \in Nodes: edgeVersion[e] >= prevEdgeVersion[e]
```

The comment says "Strengthened in temporal property" referring to EdgePropagationLiveness, but that property is about eventual propagation, NOT about monotonicity. The EdgeApply action does preserve monotonicity structurally (it only sets `edgeVersion[e] = commitIndex[e]` when `edgeVersion[e] < commitIndex[e]`), but the invariant does not verify this.

**VERDICT: FINDING. INV-5 provides zero verification value. Replace with a proper monotonicity check using a history variable or temporal formula.**

#### INV-6 (NoStaleOverwrite) — Redundant

```tla
NoStaleOverwrite ==
    \A n, m \in Nodes:
        \A i \in 1..Min(commitIndex[n], commitIndex[m]):
            log[n][i] = log[m][i]
```

This is byte-for-byte identical to StateMachineSafety (INV-4). The bug fix history (Bug 6 in tlc-results.md) shows it was weakened to match INV-4. It adds no incremental verification value.

**VERDICT: FINDING. INV-6 is a dead invariant — identical to INV-4.**

### 1.3 Unmodeled Mechanisms

| Mechanism | In Java Code? | In TLA+ Spec? | Safety Impact |
|---|---|---|---|
| PreVote (Raft 9.6) | YES (startPreVote, handlePreVoteRequest/Response) | NO | Low. PreVote is a liveness optimization to prevent term inflation. It does not affect safety. However, bugs in PreVote can cause liveness issues (e.g., the livelock bug fixed at line 644 where leaderId must be cleared on election timeout). |
| CheckQuorum | YES (tickHeartbeat, buildActiveSetAndReset) | NO | Medium. CheckQuorum prevents a stale leader from serving writes after a partition heals. Without it modeled, the spec cannot verify that a partitioned leader steps down. A stale leader continuing to accept proposals (even if they never commit) could confuse clients. |
| ReadIndex | YES (readIndex, isReadReady, ReadIndexState) | NO | Medium. ReadIndex is the mechanism for linearizable reads. Without modeling it, the spec cannot verify INV-L1 (linearizability). Bugs in ReadIndex (e.g., serving reads before confirming leadership) would not be caught. |
| Leadership Transfer | YES (transferLeadership, handleTimeoutNow) | NO | Low. Transfer is an availability optimization. The safety concern (split-brain during transfer) is guarded in code by blocking transfers during reconfig (`if (configChangePending) return false`), but this guard is not verified by the spec. |
| Pipelining (inflightCount) | YES (sendAppendEntries throttle) | NO | None for safety. Pipelining is a performance optimization that limits concurrent RPCs. |
| Backpressure (maxPendingProposals) | YES (propose() rejects when overloaded) | NO | None for safety. Backpressure is a resource management mechanism. |
| Snapshots (InstallSnapshot) | YES (handleInstallSnapshot, triggerSnapshot) | NO | Medium. Snapshot installation replaces the log and state machine. The code recomputes config from log after snapshot install (recomputeConfigFromLog with fallback), but this is not verified in the spec. A bug in config recovery after snapshot could violate ReconfigSafety. |

### 1.4 TLC Run Sufficiency

**Parameters used:** MaxTerm=3, MaxLogLen=3, 3 Nodes

| Parameter | Value | Concern |
|---|---|---|
| Nodes | 3 | Minimum for quorum. Does NOT test 5-node configurations which have different quorum dynamics. Joint consensus with 3->5 or 5->3 transitions cannot be tested. |
| MaxTerm | 3 | Allows at most 3 leader changes. Complex scenarios (e.g., cascading elections during reconfig) may need 4+ terms. The NoOpBeforeReconfig invariant is tested with at most 3 terms of no-ops. |
| MaxLogLen | 3 | CRITICAL LIMITATION. With MaxLogLen=3, the log can hold at most: 1 no-op + 1 joint config + 1 final config = exactly one full reconfig. There is NO room for a data entry + reconfig, or for testing what happens when a leader inherits uncommitted config entries from a prior term AND needs to do its own no-op. |
| Values | 2 | Sufficient for distinguishing writes. |
| States explored | 13.8M / 3.3M distinct | Reasonable state space but bounded by the small parameters. |

**Bugs that could hide at larger bounds:**
1. **MaxLogLen=4+:** A leader could inherit an uncommitted joint config entry from a prior leader, then commit its own no-op, then attempt its own config change. With MaxLogLen=3, this scenario is impossible (no room). The SingleServerInvariant check (`<= 1` config entries in current term) might miss a bug where inherited entries interact with new ones at index 4+.
2. **Nodes=5:** Joint consensus with 3->5 nodes cannot be tested with only 3 nodes in the model. The quorum intersection property (old majority must overlap with new majority) might not hold if the new config is much larger.
3. **MaxTerm=4+:** With more terms, you could have: leader A reconfigs, crashes, leader B inherits, crashes, leader C inherits the inherited config. This chain is not testable with MaxTerm=3.

**Recommendation:** Run at least MaxTerm=4, MaxLogLen=5, Nodes={n1,n2,n3,n4,n5} if computationally feasible. Use Apalache with symmetry reduction if TLC times out.

### 1.5 Liveness Property (EdgePropagationLiveness)

```tla
EdgePropagationLiveness ==
    \A n \in Nodes:
        state[n] = "leader" =>
            \A i \in 1..commitIndex[n]:
                \A e \in Nodes:
                    <>(edgeVersion[e] >= i)
```

**Was it actually checked?** NO. The TLC config file (ConsensusSpec.cfg) has the PROPERTIES block commented out:

```
\* Liveness properties require fairness; enable only with Spec (not Next).
\* Uncomment the following to check liveness (substantially slower):
\* PROPERTIES
\*     EdgePropagationLiveness
```

Furthermore, the cfg uses `INIT Init` and `NEXT Next`, NOT `SPECIFICATION Spec`. The `Spec` definition includes `WF_vars(Next)` (weak fairness), which is required for temporal properties. Without it, TLC will not check any liveness properties.

**VERDICT: FINDING. The liveness property has NEVER been checked by TLC.** The tlc-results.md does not list it as checked, and the config file has it commented out. The claim that edge propagation is verified is unsubstantiated.

---

## TASK 2: Spec-to-Code Action Mapping

### TLA+ Actions to Java Methods

| TLA+ Action | Java Method | Location | Match Quality |
|---|---|---|---|
| Init | `RaftNode(...)` constructor | Lines 137-171 | GOOD. Both initialize all state to defaults. Java also loads durable state and recomputes config from log (not in spec). |
| BecomeCandidate | `startElection()` | Lines 977-1013 | GOOD. Both increment term, vote for self, set role to candidate. Java also resets election timer and sends RequestVote RPCs (spec models this implicitly through GrantVote). |
| GrantVote | `handleRequestVote()` | Lines 780-820 | GOOD. Both check votedFor, term, log up-to-date. Java persists vote to durable storage before in-memory update (not in spec). Java also has voter check (`clusterConfig.isVoter`). |
| BecomeLeader | `becomeLeader()` | Lines 1021-1058 | GOOD. Both set role to leader, initialize nextIndex/matchIndex. Java also appends no-op within becomeLeader (spec has this as a separate LeaderAppendNoOp action). |
| LeaderAppendNoOp | `becomeLeader()` (appends no-op at line 1054-1055) | Line 1055 | DIVERGENCE. In the TLA+ spec, LeaderAppendNoOp is a separate action that can be taken at any point after becoming leader (guarded by `leaderHasCommittedNoOp[n] = FALSE`). In Java, the no-op is always appended immediately in becomeLeader(). This is actually stricter/safer — the Java code cannot forget to append the no-op. |
| ClientRequest | `propose()` | Lines 235-261 | GOOD. Both append a data entry. Java adds backpressure and transfer-in-progress checks not in spec. |
| ProposeConfigChange | `proposeConfigChange()` | Lines 387-441 | GOOD. Both check: leader, no-op committed, no config pending, stable phase, different config. Java adds transfer-in-progress check. |
| CommitJointConfig | `handleCommittedConfigChange()` | Lines 1229-1276 | GOOD. Both append C_new entry when joint config is committed. Java only does this on the leader (followers wait for replication). Matches TLA+ semantics. |
| AppendEntry | `handleAppendEntries()` | Lines 670-735 | GOOD. Both check term, prevLog match, truncate conflicting suffix, append entry, step down to follower. Java also recomputes config from log (matching spec's EffectiveConfig). |
| AdvanceCommitIndex | `maybeAdvanceCommitIndex()` | Lines 1154-1183 | GOOD. Both only commit current-term entries, use config-aware quorum. Java includes self in quorum set. Matches spec's `{n} \cup {m \in Nodes : matchIndex[n][m] >= newCI}`. |
| EdgeApply | `applyCommitted()` | Lines 1191-1221 | PARTIAL. Spec's EdgeApply simply advances edgeVersion to commitIndex. Java's applyCommitted() is much richer: it applies entries to the state machine, tracks no-op commitment, handles config changes. The edgeVersion concept maps to `log.lastApplied()` in Java. |

### TLA+ Invariants to Java Runtime Assertions

| TLA+ Invariant | Java Assertion | Location | Match Quality |
|---|---|---|---|
| INV-1: ElectionSafety | `invariantChecker.check("election_safety", ...)` | `becomeLeader()`, line 1023-1026 | WEAK. Checks that votes form a quorum, but does NOT check that at most one leader exists per term (the actual invariant). The check is: "did I get a quorum?" not "is there already another leader in this term?" A single node cannot verify the cross-cluster property. |
| INV-2: LeaderCompleteness | `invariantChecker.check("leader_completeness", ...)` | `becomeLeader()`, lines 1029-1032 | WEAK. Checks `log.lastIndex() >= log.commitIndex()`. This verifies the new leader's log is not shorter than its OWN commitIndex, but does NOT verify it contains all entries committed by any previous leader. This is a necessary but not sufficient condition. |
| INV-3: LogMatching | `invariantChecker.check("log_matching", ...)` | `handleAppendEntries()`, lines 706-708 | WEAK. Only checks that the just-appended entry has the expected term at the expected index. Does NOT check the full LogMatching property (all preceding entries identical). This is a spot-check, not a full invariant. |
| INV-4: StateMachineSafety | `invariantChecker.check("state_machine_safety", ...)` | `applyCommitted()`, lines 1204-1206 | VERY WEAK. Checks `entry.index() == nextApply`. This only verifies the entry's stored index matches the expected apply position. It does NOT verify that the entry at this index is the same across all nodes (the actual invariant). A single node cannot check a cross-node invariant, but it could verify that the entry at the commit index matches a digest. |
| INV-5: VersionMonotonicity | `invariantChecker.check("version_monotonicity", ...)` | `applyCommitted()`, lines 1196-1198 | GOOD (but trivial). Checks `nextApply > log.lastApplied()`. This correctly verifies monotonic advancement of the apply index on this node. This is actually more meaningful than the TLA+ invariant (which only checks `>= 0`). |
| INV-6: NoStaleOverwrite | No dedicated assertion | N/A | MISSING. There is no runtime assertion for NoStaleOverwrite. As noted above, INV-6 is identical to INV-4 in the spec, so the absence is understandable but should be documented. |
| INV-7: ReconfigSafety | `invariantChecker.check("reconfig_safety", ...)` | `proposeConfigChange()`, line 418-420 | WEAK. Checks `jointConfig.isJoint()`, which is always true when creating a joint config. Does not verify the actual invariant (that a node in joint phase has a matching joint entry in its log). |
| INV-8: SingleServerInvariant | `invariantChecker.check("single_server_invariant", ...)` | `proposeConfigChange()`, line 405-407 | GOOD. Checks `!configChangePending`, which is the operational enforcement of at most one config change in-flight. |
| INV-9: NoOpBeforeReconfig | `invariantChecker.check("no_op_before_reconfig", ...)` | `proposeConfigChange()`, line 410-412 | GOOD. Checks `noopCommittedInCurrentTerm`, which is the operational enforcement of the no-op-before-reconfig rule. |

### FINDING: Invariants with NO or WEAK runtime assertions

**Critical gaps (violates Hard Rule #13: "No invariant lives only in TLA+"):**

1. **INV-1 (ElectionSafety):** The runtime check is a self-check ("did I get a quorum?"), not the invariant ("at most one leader per term"). The actual invariant is a cross-node property that a single node cannot fully verify alone. Recommendation: add a protocol-level check where a node stepping up as leader broadcasts its term, and if any peer knows of another leader in the same term, it raises an invariant violation.

2. **INV-2 (LeaderCompleteness):** The runtime check (`lastIndex >= commitIndex`) is trivially true for any healthy node. It does not verify that the new leader's log actually contains all entries committed in prior terms. Recommendation: when a new leader discovers committed entries from prior terms (via replication), verify they match.

3. **INV-3 (LogMatching):** Only spot-checks the most recent appended entry. Could be strengthened to also check the preceding entry during AppendEntries consistency checks.

4. **INV-4 (StateMachineSafety):** Checks entry index consistency, not cross-node agreement. This invariant fundamentally requires coordination to check. Consider: when the leader commits an entry, include a hash of the log prefix in the commit notification. Followers can verify they have the same hash.

5. **INV-6 (NoStaleOverwrite):** No runtime assertion exists.

6. **INV-7 (ReconfigSafety):** The runtime check (`isJoint()`) is always true at the point where it's checked. It does not verify the actual safety property.

---

## TASK 3: Spec/Code Divergences

### 3.1 Features in Code but NOT in Spec

| Feature | Code Location | Safety Risk |
|---|---|---|
| **PreVote** | `startPreVote()`, `handlePreVoteRequest()`, `handlePreVoteResponse()` (lines 822-893, 932-971) | Low. PreVote is a liveness optimization. The code has a subtle bug-fix (line 644: clearing leaderId on election timeout to prevent PreVote livelock). This interaction is not modeled in the spec and could have variants that are not covered. |
| **CheckQuorum** | `tickHeartbeat()`, `buildActiveSetAndReset()` (lines 649-664, 1384-1401) | Medium. CheckQuorum causes the leader to step down if fewer than a majority of peers are active. In joint consensus, the code uses `clusterConfig.isQuorum(activeSet)` which requires dual-majority. This logic is not verified in the spec. |
| **ReadIndex** | `readIndex()`, `isReadReady()`, `ReadIndexState` (lines 330-361) | Medium. ReadIndex is the mechanism for linearizable reads (INV-L1). It is completely unmodeled. The code relies on heartbeat-based leadership confirmation (`confirmPendingReads` in `tickHeartbeat`). |
| **Leadership Transfer** | `transferLeadership()`, `handleTimeoutNow()` (lines 271-289, 899-908) | Low. The code blocks transfers during reconfig (`if (configChangePending) return false` at line 281). This safety guard is not verified by the spec. The target bypasses PreVote when receiving TimeoutNow (line 907: directly calls `startElection()`). |
| **Pipelining** | `inflightCount`, `maxInflightAppends()` (lines 1081-1083) | None for safety. |
| **Backpressure** | `maxPendingProposals` check in `propose()` (lines 251-253) | None for safety. |
| **Snapshots** | `handleInstallSnapshot()`, `triggerSnapshot()` (lines 1288-1371) | Medium. Snapshot install replaces the entire state. The code recomputes config from log with snapshot fallback (line 1331). If the snapshot config metadata is lost or corrupt, the node could revert to the initial config, violating ReconfigSafety. |
| **Durable State** | `DurableRaftState`, persist before in-memory update (lines 811, 919, 944, 983) | The spec models state updates atomically. The code persists to durable storage BEFORE updating in-memory state. A crash between persist and in-memory update is safe (restart will reload from durable state). This is correct but not modeled. |

### 3.2 Spec vs. Code: EffectiveConfig

**TLA+ spec (lines 105-113):**
```tla
EffectiveConfig(logSeq) ==
    IF \E i \in 1..Len(logSeq): logSeq[i].type \in {"joint", "final"}
    THEN LET maxIdx == CHOOSE i \in 1..Len(logSeq):
                /\ logSeq[i].type \in {"joint", "final"}
                /\ \A j \in (i+1)..Len(logSeq): logSeq[j].type \notin {"joint", "final"}
         IN IF logSeq[maxIdx].type = "joint"
            THEN JointConfig(logSeq[maxIdx].value.old, logSeq[maxIdx].value.new)
            ELSE StableConfig(logSeq[maxIdx].value.members)
    ELSE InitConfig
```

**Java code (lines 560-585, `recomputeConfigFromLog`):**
```java
for (long i = log.lastIndex(); i > log.snapshotIndex(); i--) {
    LogEntry entry = log.entryAt(i);
    if (entry != null && isConfigChangeEntry(entry.command())) {
        clusterConfig = deserializeConfigChange(entry.command());
        configChangePending = (i > log.commitIndex());
        return;
    }
}
// Fallback: snapshot config or initial config
```

**Assessment:** Semantically equivalent. Both scan from the end of the log and find the latest config entry. The Java code additionally:
1. Handles the snapshot boundary (`i > log.snapshotIndex()`) — the spec doesn't model snapshots.
2. Has a fallback chain: snapshot config data -> latest snapshot object -> initial config. This is more complex than the spec's simple `InitConfig` fallback.
3. Tracks `configChangePending` based on whether the latest config entry is committed. The spec doesn't have an explicit `configChangePending` variable; it uses `HasUncommittedConfigEntry(n)` as a computed predicate.

**DIVERGENCE: The snapshot fallback path is unverified by the spec.** If the snapshot config data is null and the latest snapshot's clusterConfigData is also null, the code reverts to the initial static config from `RaftConfig.peers()`. This could be wrong if reconfigurations have occurred but all evidence is lost.

### 3.3 Spec vs. Code: Quorum Calculation

**TLA+ spec (lines 71-76):**
```tla
IsQuorumOf(Q, cfg) ==
    IF cfg.phase = "stable"
    THEN Cardinality(Q \cap cfg.old) * 2 > Cardinality(cfg.old)
    ELSE /\ Cardinality(Q \cap cfg.old) * 2 > Cardinality(cfg.old)
         /\ Cardinality(Q \cap cfg.new) * 2 > Cardinality(cfg.new)
```

**Java code (ClusterConfig.java, lines 117-123):**
```java
public boolean isQuorum(Set<NodeId> respondents) {
    if (!joint) {
        return countIntersection(respondents, voters) >= majorityOf(voters.size());
    }
    return countIntersection(respondents, voters) >= majorityOf(voters.size())
            && countIntersection(respondents, newVoters) >= majorityOf(newVoters.size());
}

private static int majorityOf(int size) {
    return size / 2 + 1;
}
```

**Assessment: EQUIVALENT.** The spec uses `Cardinality(Q \cap S) * 2 > Cardinality(S)` which is the same as `|Q \cap S| > |S|/2`, i.e., strict majority. The Java uses `countIntersection >= size/2 + 1`. For any integer `n`, `x >= n/2 + 1` (integer division) is equivalent to `2*x > n`:
- n=3: Java requires >= 2, spec requires > 1.5, i.e., >= 2. Match.
- n=5: Java requires >= 3, spec requires > 2.5, i.e., >= 3. Match.
- n=4: Java requires >= 3, spec requires > 2, i.e., >= 3. Match.

The quorum calculations are correctly aligned.

### 3.4 Spec vs. Code: Follower Step-Down in AppendEntry

**TLA+ spec (lines 438-444):**
The spec unconditionally sets the receiver to "follower" and resets `votedFor` to Nil only when the leader's term is higher.

**Java code (lines 679-685):**
```java
if (req.term() > currentTerm) {
    becomeFollower(req.term());
} else if (role == RaftRole.CANDIDATE) {
    becomeFollower(req.term());
}
```

**Assessment: MATCH.** Both handle the same cases. The spec achieves this with `state' = [state EXCEPT ![m] = "follower"]` (unconditional) and a conditional `votedFor` reset. The Java code steps down explicitly for higher terms and for candidates in the same term. Followers in the same term remain followers (no state change needed). The net effect is the same — any node receiving a valid AppendEntries becomes a follower.

---

## Summary of Critical Findings

### FINDING 1: INV-5 (VersionMonotonicity) is trivially true
The TLA+ invariant `edgeVersion[e] >= 0` provides zero verification value. Replace with a history-variable-based monotonicity check.

### FINDING 2: EdgePropagationLiveness was NEVER checked
The liveness property is commented out in the TLC config. The cfg uses INIT/NEXT (no fairness) instead of SPECIFICATION Spec. No temporal properties have been verified.

### FINDING 3: INV-6 (NoStaleOverwrite) is identical to INV-4 (StateMachineSafety)
After Bug 6 fix, both invariants have the same formula. INV-6 adds no incremental value and has no runtime assertion.

### FINDING 4: 6 of 15 consistency contract invariants are NOT in TLA+
INV-L1 (Linearizability), INV-S1/S2 (Staleness Bounds), INV-M1/M2 (Monotonic Reads), INV-V1/V2 (Version Sequences), INV-W1/W2 (Write Ordering), INV-RYW1 (Read-Your-Writes) are not modeled.

### FINDING 5: Multiple invariant runtime assertions are weak or missing
INV-1, INV-2, INV-3, INV-4, INV-7 have runtime assertions that check necessary but not sufficient conditions. INV-6 has no runtime assertion at all. This violates Hard Rule #13.

### FINDING 6: Model bounds too small for confident reconfig verification
MaxLogLen=3 allows exactly one full reconfig cycle (no-op + joint + final). Cannot test data entries interleaved with reconfigs, or inherited config entries from prior leaders.

### FINDING 7: Snapshot config recovery path is unverified
The Java code's `recomputeConfigFromLog` has a multi-level fallback for snapshot recovery that is not modeled in the TLA+ spec. A bug in this path could silently revert a node to its initial configuration.

### FINDING 8: CheckQuorum with joint consensus is unverified
CheckQuorum uses `clusterConfig.isQuorum(activeSet)` for dual-majority checking, but this interaction is not modeled in the spec. A leader in joint consensus must have dual-majority liveness to avoid stepping down, which could interact with the reconfig safety properties.
