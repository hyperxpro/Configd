# Runbook: Edge Fleet Catch-Up Storm After Extended Partition

## Detection
- Alert: `configd_distribution_fanout_queue_depth` exceeds threshold (> 10,000) across multiple `PlumtreeNode` instances.
- Dashboard: "Edge Distribution" panel shows `configd_edge_catchup_bytes_total` spiking to multi-GB.
- Symptom: core cluster CPU and bandwidth saturated; `FanOutBuffer` filling faster than draining.
- Metric: `configd_edge_staleness_seconds` on edge nodes is high (> 60s) and not decreasing.

## Diagnosis
1. Count how many edge nodes are simultaneously catching up:
   ```bash
   curl -s http://<core>:8080/metrics | grep configd_edge_connected_clients
   ```
2. Check `VersionCursor` positions on reconnecting edges -- large version gaps mean full delta replay:
   ```bash
   curl -s http://<edge>:8080/metrics | grep configd_edge_version_cursor
   ```
3. Inspect `CatchUpService` to see if it is serving snapshots or incremental deltas.
4. Verify `SlowConsumerPolicy` is active -- slow edges should be disconnected, not block the fanout.
5. Check `BloomFilter` false-positive rates in `EdgeConfigClient` -- high FP causes unnecessary delta fetches.

## Mitigation
1. Enable rate limiting on `CatchUpService`:
   ```bash
   curl -X POST http://<core>:8080/admin/catchup-rate-limit -d '{"max_bytes_per_sec": 52428800}'
   ```
2. If the storm is severe, temporarily reduce `PlumtreeNode` eager push fanout to limit concurrent transfers.
3. Activate `SlowConsumerPolicy.DROP_OLDEST` to prevent backpressure from stalling the core cluster.
4. Stagger edge reconnections by configuring jittered backoff on `EdgeConfigClient`.

## Recovery
1. Let `CatchUpService` drain naturally with rate limiting in place.
2. For edges that are too far behind (version gap > snapshot threshold), force a snapshot-based resync:
   ```bash
   curl -X POST http://<edge>:8080/admin/force-snapshot-resync
   ```
3. Monitor `configd_edge_staleness_seconds` -- it should decrease monotonically as edges catch up.
4. Once all edges report staleness < 5s, remove the rate limit:
   ```bash
   curl -X POST http://<core>:8080/admin/catchup-rate-limit -d '{"max_bytes_per_sec": 0}'
   ```

## Verification
- `configd_distribution_fanout_queue_depth` returns to normal (< 100).
- `configd_edge_staleness_seconds` on all edge nodes is within SLA (< 5s).
- `StalenessTracker` reports all edges within acceptable version delta.
- Core cluster CPU and network utilization return to baseline.
- `WatchCoalescer` is coalescing events normally (no burst backlog).

## Prevention
- Configure `EdgeConfigClient` with exponential backoff and jitter for reconnection.
- Set `CatchUpService` to prefer snapshot transfer over incremental delta when version gap > 1000.
- Deploy `BloomFilter`-based delta detection in `DeltaApplier` to minimize data transferred.
- Run partition-recovery simulation tests (`ConsistencyPropertyTests`) to validate catch-up behavior.
- Size `FanOutBuffer` to handle worst-case reconnection storms (2x edge fleet size).
