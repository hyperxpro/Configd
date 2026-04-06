# Runbook: Poison Config Propagation and Rollback

## Detection
- Alert: `configd_edge_poison_pill_detected_total` > 0 on any `PoisonPillDetector` instance.
- Dashboard: "Config Health" panel shows `configd_store_validation_failures_total` spiking.
- Symptom: edge nodes rejecting config updates; `ConfigValidator` returning errors; downstream applications reporting config parse failures.
- Metric: `configd_distribution_watch_error_total` increasing as `WatchService` delivers invalid events.

## Diagnosis
1. Identify the poisoned key and version:
   ```bash
   curl -s http://<node>:8080/metrics | grep configd_store_last_rejected_key
   ```
2. Read the bad config entry from `VersionedConfigStore`:
   ```bash
   curl -s http://<node>:8080/admin/config/get?key=<poisoned-key>&version=<version>
   ```
3. Check who wrote it -- correlate the `ConfigStateMachine.sequenceCounter()` with the Raft log index.
4. Verify if `ConfigValidator` was bypassed or if the validation rules are insufficient.
5. Check `PoisonPillDetector` on edge nodes -- it applies heuristic checks (value size, encoding, schema).

## Mitigation
1. Immediately write a corrected value to override the poison config:
   ```bash
   curl -X POST http://<leader>:8080/admin/config/put \
     -d '{"key": "<poisoned-key>", "value": "<corrected-value>"}'
   ```
   This increments `ConfigStateMachine.sequenceCounter()` and propagates via Raft.
2. If the poison config is causing crashes on edges, issue an emergency write freeze (see `write-freeze.md`).
3. If many keys are affected, use a batch rollback via `CommandCodec.Batch`:
   ```bash
   curl -X POST http://<leader>:8080/admin/config/batch-rollback \
     -d '{"keys": ["key1", "key2"], "target_version": <last-known-good>}'
   ```

## Recovery
1. After writing the correction, verify propagation:
   - Check `configd_raft_commit_index` advances on all core nodes.
   - Verify `PlumtreeNode` gossip delivers the update to all peers.
   - Confirm `WatchCoalescer` coalesces the correction into a single `WatchEvent.Updated`.
2. On edge nodes, verify `LocalConfigStore` reflects the corrected value.
3. Clear `PoisonPillDetector` counters after confirming the fix.
4. Review `ConfigSigner` signatures -- if the poison config was signed, investigate key compromise.

## Verification
- `configd_edge_poison_pill_detected_total` stops increasing.
- `configd_store_validation_failures_total` returns to 0.
- All edge nodes report the corrected config version via `StalenessTracker`.
- `WatchService` subscribers receive the corrected `WatchEvent`.
- Application health checks pass with the new config values.

## Prevention
- Enforce `ConfigValidator` rules for all writes at the `ConfigStateMachine` level.
- Deploy `ConfigSigner` to cryptographically sign config entries and verify on read.
- Add schema validation in `CommandCodec` before proposals reach `RaftNode.propose()`.
- Implement canary deployments via `RolloutController` -- stage config to a subset of edges before full rollout.
- Maintain a config changelog with author attribution for audit trails.
