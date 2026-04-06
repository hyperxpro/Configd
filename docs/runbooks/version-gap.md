# Runbook: Version Gap Divergence Between Replicas

## Detection
- Alert: `configd_raft_log_replication_lag > 1000` on any follower for > 30s.
- Dashboard: "Replication Health" panel shows `configd_raft_match_index` diverging between leader and followers.
- Symptom: `VersionedConfigStore.currentVersion()` differs significantly across nodes; `StalenessTracker` on edges reports high staleness.
- Metric: `configd_raft_snapshot_transfer_in_progress == 1` indicates a follower is so far behind it needs a snapshot.

## Diagnosis
1. Compare committed versions across all nodes:
   ```bash
   for node in node1 node2 node3; do
     echo "$node: $(curl -s http://$node:8080/metrics | grep configd_store_current_version)"
   done
   ```
2. Check `RaftLog` state on the lagging follower:
   - `snapshotIndex` vs leader's `commitIndex` -- if the gap exceeds the log retention, a snapshot transfer is needed.
   - `matchIndex` on the leader for this follower -- if stuck, `AppendEntriesRequest` may be failing.
3. Inspect `configd_raft_inflight_appends` on the leader -- if at `maxInflightAppends` (default 10), the pipeline is saturated.
4. Check `FlowController` in the replication engine for backpressure signals.
5. Verify disk I/O on the lagging follower -- slow `FileStorage.sync()` causes apply backpressure.

## Mitigation
1. If the gap is growing (not recovering):
   - Check network bandwidth between leader and follower -- `BatchEncoder` may be hitting `maxBatchBytes` (256KB) limit.
   - Temporarily increase `RaftConfig.maxBatchSize` and `maxBatchBytes` on the leader:
     ```bash
     curl -X POST http://<leader>:8080/admin/tune-replication \
       -d '{"max_batch_bytes": 1048576, "max_inflight_appends": 20}'
     ```
2. If a follower is completely stuck, force a snapshot transfer:
   ```bash
   curl -X POST http://<leader>:8080/admin/force-snapshot -d '{"target": <follower-id>}'
   ```
   This triggers `InstallSnapshotRequest` via the `SnapshotTransfer` service.
3. If the follower has a corrupted log, restart it with a clean data directory -- it will bootstrap from a snapshot.

## Recovery
1. Monitor `configd_raft_match_index` on the leader for the lagging follower -- it should be advancing.
2. Once `matchIndex` catches up to within 100 of `commitIndex`, the follower is healthy.
3. Verify `ConfigStateMachine.sequenceCounter()` matches across all nodes.
4. If replication parameters were temporarily tuned, restore defaults:
   ```bash
   curl -X POST http://<leader>:8080/admin/tune-replication \
     -d '{"max_batch_bytes": 262144, "max_inflight_appends": 10}'
   ```
5. Confirm `PlumtreeNode` gossip includes the recovered follower in its active view.

## Verification
- `configd_raft_log_replication_lag < 100` on all followers.
- `configd_store_current_version` matches on all nodes (within 1-2 entries of leader).
- `configd_raft_snapshot_transfer_in_progress == 0` on all nodes.
- `MultiRaftDriver.propose()` succeeds with normal latency.
- Edge nodes connected to the recovered follower report normal staleness via `StalenessTracker`.

## Prevention
- Monitor `configd_raft_log_replication_lag` with alerts at 100, 500, and 1000 entries.
- Size `RaftLog` retention to hold at least 10 minutes of entries before compaction via `Compactor`.
- Provision sufficient network bandwidth for worst-case replication (burst writes after maintenance).
- Run `ReplicationPipeline` performance benchmarks (`RaftCommitBenchmark`) before production changes.
- Set `HeartbeatCoalescer` to batch heartbeats efficiently, reducing per-group overhead.
