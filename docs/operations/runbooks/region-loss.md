# Runbook: Region Loss (Minority/Majority Partition)

## Detection
- Alert: `configd_raft_quorum_reachable == 0` on any Raft group for > 30s.
- Dashboard: "Cluster Health" panel shows peer connectivity matrix with red cells.
- Symptom: write proposals return `ProposalResult.NOT_LEADER` from all nodes in the affected region; `MultiRaftDriver.propose()` consistently fails.
- Metric: `configd_raft_election_timeout_total` spikes across multiple nodes simultaneously.

## Diagnosis
1. Identify which region is unreachable:
   ```bash
   curl -s http://<node>:8080/metrics | grep configd_raft_peer_last_contact_ms
   ```
   Peers with `last_contact_ms > election_timeout_max_ms (300)` are partitioned.
2. Check `RaftNode.leaderId()` across all reachable nodes -- if no leader exists, the cluster has lost quorum.
3. Verify network-level connectivity between regions (VPC peering, security groups, DNS).
4. Count reachable nodes: if `reachable < RaftConfig.quorumSize()`, writes are blocked.

## Mitigation
- If **minority** partition (quorum intact): no action needed for writes. Reads on partitioned nodes may serve stale data from `VersionedConfigStore` snapshots.
- If **majority** partition (quorum lost):
  1. Do NOT force-reconfigure the cluster -- this risks split-brain.
  2. Engage network/infrastructure team to restore connectivity.
  3. If a region is permanently lost, plan a controlled reconfiguration via `RaftNode.proposeConfigChange()` to remove dead nodes.

## Recovery
1. Once network connectivity is restored, partitioned nodes will automatically rejoin:
   - `RaftNode.handleMessage()` processes `AppendEntriesRequest` from the leader.
   - Lagging followers catch up via log replication or `InstallSnapshotRequest` if too far behind.
2. Monitor `configd_raft_log_replication_lag` to confirm followers are catching up.
3. Verify `PlumtreeNode` gossip overlay reconnects (check `HyParViewOverlay` active view size).

## Verification
- All nodes report the same `leaderId` for each Raft group.
- `configd_raft_commit_index` converges across all nodes within 5s.
- `EdgeConfigClient` instances reconnect and `StalenessTracker` reports versions within acceptable delta.
- `WatchService` resumes delivering `WatchEvent` notifications to subscribers.

## Prevention
- Deploy nodes across 3+ availability zones with anti-affinity rules.
- Set `RaftConfig.electionTimeoutMinMs` high enough to tolerate cross-region latency jitter.
- Monitor `configd_transport_connection_failures_total` to detect early signs of network degradation.
- Run quarterly region-failover drills using the simulation test harness (`ConsistencyPropertyTests`).
