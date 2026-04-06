# Runbook: Emergency Write Freeze

## Detection
- Trigger: operator decision to halt all config writes due to ongoing incident (poison config, data corruption, security breach).
- Alert: this runbook is invoked manually -- there is no automatic trigger.
- Symptom: need to prevent any new config mutations from being committed while investigating an issue.

## Diagnosis
1. Confirm the need for a write freeze -- this is a high-impact action that blocks ALL config updates.
2. Identify which Raft groups need to be frozen:
   ```bash
   curl -s http://<node>:8080/metrics | grep configd_raft_group_ids
   ```
3. Document the current state before freezing:
   - Record `ConfigStateMachine.sequenceCounter()` on the leader for each group.
   - Record `configd_raft_commit_index` on all nodes.
   - Record the last known-good config version.

## Mitigation
1. **Activate write freeze** on the leader node for each Raft group:
   ```bash
   curl -X POST http://<leader>:8080/admin/write-freeze -d '{"enabled": true}'
   ```
   This causes `RaftNode.propose()` to return `ProposalResult.NOT_LEADER` for all new proposals while keeping the node as leader (heartbeats continue).

2. Verify freeze is active:
   ```bash
   curl -s http://<leader>:8080/admin/write-freeze-status
   ```

3. Reads continue to work -- `VersionedConfigStore.get()` serves the last committed snapshot. Edge nodes via `LocalConfigStore` continue serving cached config.

4. `WatchService` stops emitting new `WatchEvent`s since no mutations are applied.

## Recovery
1. Resolve the underlying issue (see relevant runbook: `poison-config.md`, etc.).
2. If a config rollback is needed, prepare the batch rollback command BEFORE unfreezing.
3. **Disable write freeze**:
   ```bash
   curl -X POST http://<leader>:8080/admin/write-freeze -d '{"enabled": false}'
   ```
4. If a rollback is prepared, execute it immediately after unfreezing:
   ```bash
   curl -X POST http://<leader>:8080/admin/config/batch-rollback \
     -d '{"keys": [...], "target_version": <version>}'
   ```
5. Monitor `configd_raft_commit_index` to confirm writes resume.
6. Verify `PlumtreeNode` gossip propagates new entries to all peers.

## Verification
- `configd_raft_proposals_accepted_total` resumes incrementing after unfreeze.
- `ConfigStateMachine.sequenceCounter()` advances for new writes.
- `WatchCoalescer` resumes coalescing and delivering `WatchEvent` notifications.
- Edge nodes via `EdgeConfigClient` receive updates and `StalenessTracker` reports decreasing staleness.
- No `ProposalResult.NOT_LEADER` errors from legitimate write attempts.

## Prevention
- Implement `ConfigValidator` and `ConfigSigner` to prevent incidents that require write freezes.
- Add rate limiting on `RaftNode.propose()` to cap write throughput.
- Deploy canary-based rollout via `RolloutController` to catch bad configs before full propagation.
- Document the write-freeze procedure in on-call handbooks and practice it during game days.
- Ensure monitoring dashboards clearly indicate freeze state for on-call engineers.
