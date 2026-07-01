# Runbook: Failed Reconfiguration Rollback

## Detection
- Alert: `configd_raft_config_change_pending == 1` for > 60s on any Raft group.
- Dashboard: "Cluster Membership" panel shows joint consensus state (C_old,new) persisting.
- Symptom: `RaftNode.proposeConfigChange()` rejects new changes because `configChangePending` is true.
- Log: repeated `AppendEntriesRequest` with config entry that never reaches commit index.

## Diagnosis
1. Identify which Raft group has a stuck config change:
   ```bash
   curl -s http://<node>:8080/metrics | grep configd_raft_config_change_pending
   ```
2. Check `ClusterConfig` state on the leader -- is it in joint (C_old,new) or transitioning to C_new?
3. Verify the new node(s) being added are reachable and their `RaftLog` is catching up:
   ```bash
   curl -s http://<leader>:8080/metrics | grep configd_raft_match_index
   ```
   If `matchIndex` for the new node is stuck, it cannot participate in the new quorum.
4. Check if the leader has stepped down mid-reconfiguration (inspect `configd_raft_role` transitions).

## Mitigation
1. If the new node is unreachable or permanently failed:
   - The joint config requires quorum from BOTH old and new configs.
   - If the new node is the only addition and it is down, the joint config CAN still commit if old-config quorum is met.
2. If the leader stepped down during joint consensus:
   - The new leader will continue the reconfiguration automatically once elected.
   - Verify `noopCommittedInCurrentTerm` becomes true on the new leader before any new config changes.
3. Do NOT manually modify `DurableRaftState` or `RaftLog` -- this violates Raft safety invariants.

## Recovery
1. If the new node will not recover, propose a NEW config change that removes it:
   ```bash
   curl -X POST http://<leader>:8080/admin/reconfigure -d '{"remove": [<failed-node-id>]}'
   ```
   This will transition from joint(C_old, C_old+new) to C_old, effectively rolling back.
2. Wait for the config entry to commit (monitor `configd_raft_commit_index`).
3. Verify `configChangePending` returns to false on the leader.
4. Confirm `ClusterConfig` on all nodes matches the intended membership.

## Verification
- `configd_raft_config_change_pending == 0` on all nodes.
- `RaftConfig.peers()` matches the expected cluster membership on every node.
- `configd_raft_cluster_size` reports the correct count.
- A test write via `MultiRaftDriver.propose()` succeeds.
- `PlumtreeNode` gossip overlay reflects the updated membership.

## Prevention
- Always verify new nodes are reachable and have caught up BEFORE initiating reconfiguration.
- Add one node at a time; never change more than one member per reconfiguration.
- Set a reconfiguration timeout and alert if the joint config persists beyond it.
- Test reconfiguration scenarios regularly using `ReconfigurationTest` in the test suite.
