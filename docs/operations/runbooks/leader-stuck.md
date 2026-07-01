# Runbook: Leader Election Failure / Stuck Election

## Detection
- Alert: `configd_raft_role` shows no node in `LEADER` state for any Raft group for > 5s.
- Dashboard: "Election" panel shows `configd_raft_election_started_total` climbing without corresponding `configd_raft_election_won_total`.
- Symptom: all `MultiRaftDriver.propose()` calls return `ProposalResult.NOT_LEADER`.
- Metric: `configd_raft_current_term` increasing rapidly across multiple nodes (term inflation).

## Diagnosis
1. Check if PreVote is failing (term inflation without actual elections):
   ```bash
   curl -s http://<node>:8080/metrics | grep configd_raft_prevote
   ```
   If `preVotesGranted` never reaches `quorumSize()`, a node may have a stale log.
2. Inspect `RaftNode` state on each node: `currentTerm`, `votedFor`, `role`.
3. Check `DurableRaftState` persistence -- if `Storage.file()` is on a failed disk, the node cannot persist votes and will loop.
4. Verify clocks: if `tick()` is not being called at the expected 10ms interval (check `ScheduledExecutorService` health), timeouts will not fire.
5. Look for split votes: with an even number of nodes, elections can tie repeatedly. Check `RaftConfig.clusterSize()`.

## Mitigation
1. If a single node is causing disruption (e.g., repeatedly starting elections with a stale log):
   ```bash
   # Temporarily remove the disruptive node from the cluster
   curl -X POST http://<leader>:8080/admin/reconfigure -d '{"remove": [<node-id>]}'
   ```
2. If the tick loop has stalled, restart the affected node's JVM process.
3. If term inflation is severe, the PreVote protocol in `RaftNode` should prevent cascading term bumps -- verify PreVote is enabled.

## Recovery
1. Once a leader is elected, verify it commits a no-op entry (required before config changes per `noopCommittedInCurrentTerm` flag).
2. Confirm `RaftNode.leaderId()` is consistent across all nodes.
3. If a node was removed, add it back after it has caught up:
   ```bash
   curl -X POST http://<leader>:8080/admin/reconfigure -d '{"add": [<node-id>]}'
   ```

## Verification
- `configd_raft_role{role="LEADER"}` reports exactly 1 leader per Raft group.
- `configd_raft_heartbeat_sent_total` is incrementing on the leader.
- `configd_raft_current_term` is stable (not increasing) across all nodes.
- Test a write: `MultiRaftDriver.propose()` returns `ProposalResult.PROPOSED`.

## Prevention
- Always deploy an odd number of nodes (`RaftConfig.clusterSize()` should be 3 or 5).
- Tune `electionTimeoutMinMs` and `electionTimeoutMaxMs` to be well above network RTT (default 150-300ms).
- Ensure `heartbeatIntervalMs` (default 50ms) is significantly less than `electionTimeoutMinMs`.
- Monitor disk latency on `FileStorage` paths -- slow fsync in `DurableRaftState.persist()` delays vote persistence.

## Leadership transfer (operator-initiated)

Move a group's leader onto a specific node with an ADMIN-gated request:
```bash
curl -X POST 'https://<leader>:8080/v1/admin/groups/<group-id>/transfer-leadership?target=<node-id>' \
     -H 'Authorization: Bearer <admin-token>'
```

The response is `200 initiated` as soon as the transfer is ACCEPTED -- the actual leader move is asynchronous (the leader sends TimeoutNow once the target has caught up). A 200 therefore does NOT confirm the move: a transfer to a far-lagging target returns 200 but does not take effect until that node catches up. If the target cannot catch up within about one election timeout, the transfer AUTO-ABORTS and the current leader resumes (Raft dissertation section 3.10). Writes are NEVER blocked while a transfer is pending -- the group stays write-available throughout, and the abort restores normal proposals if the transfer stalls.

There is no dedicated status endpoint yet. To confirm a transfer took effect, re-read the leader: `configd_raft_role{role="LEADER"}` (or `RaftNode.leaderId()`) should report the target as the new leader.

A transfer requested against a node that is not the group's leader returns `503` with an `X-Leader-Hint` header pointing at the current leader -- retry against that node.
