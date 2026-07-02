# Drive-to-green EC2 measurement (2026-07-02)

Gate 4 of the drive-to-green arc. One paid box (c6i.2xlarge, 8 vCPU, 15 GB, ap-south-1,
Ubuntu 26.04, JDK 25) built the release commit `9e1f191` and ran two measurements: the
faulted-linearizability matrix (condition C3) and the edge-staleness-under-load
distribution (INV-S2). The box was terminated and API-verified clean (instance
terminated, both EBS volumes gone via DeleteOnTermination, no orphan by tag, no
allocated EIP). Nothing billable remained.

## C3 - faulted linearizability: GREEN

The `linearizability-under-fault` job could never be captured green because the
`configd-linz` harness launched each spawned server with its signing key co-located in
the server's own data dir, which the D-1 co-location guard (`enforceSigningKeyNotColocated`)
fail-closes on. Every node crashed before electing, so every seed reported
`VERDICT: INDETERMINATE (no leader elected)`. This was never a consensus defect - the
histories were never produced.

The fix (this branch) mounts each test node's signing key OUTSIDE its data dir via
`--signing-key-file`, satisfying the real guard rather than disabling it (no
`allowColocatedSigningKey` opt-out). With the fix, the matrix runs green on the release
commit `9e1f191`:

| n | seed | verdict | faults | ops |
|---|------|---------|--------|-----|
| 3 | 2001 | LINEARIZABLE | 4 | 809 |
| 3 | 2002 | LINEARIZABLE | 4 | 804 |
| 3 | 2003 | LINEARIZABLE | 4 | 803 |
| 3 | 2004 | LINEARIZABLE | 5 | 799 |
| 5 | 2001 | LINEARIZABLE | 4 | 809 |
| 5 | 2002 | LINEARIZABLE | 4 | 804 |
| 5 | 2003 | LINEARIZABLE | 4 | 803 |
| 5 | 2004 | LINEARIZABLE | 5 | 799 |

Reproducibility gate (iv) passed: `schedule-777-n3.json` byte-identical across two runs.
Full log: `artifacts/c3-faulted-linz-green.log`. Because Gates 5 and 6 of this arc change
consensus and storage code after this capture, the definitive C3 for the shipped bytes
must be re-run on the final release SHA.

## INV-S2 - edge staleness under load: measurement blocked by a discovered finding

INV-S2 (consistency-contract section 2): under normal conditions, edge-read staleness
p99 < 500 ms and p9999 < 2 s. The staleness mechanism was validated directly - a
freshly-subscribed edge reads `edge_staleness_ms=30001` (DISCONNECTED floor) with zero
writes, and drops to single-digit ms (CURRENT) within one heartbeat of the first
committed write, thereafter hovering 2-251 ms bounded by the 250 ms heartbeat cadence.

The distribution-under-sustained-load run, however, could not complete: under sustained
writes the subscribed edges are spuriously demoted and quarantined by the
`SlowConsumerGovernor`, their frontier freezes, and measured staleness ramps linearly to
the histogram ceiling (`artifacts/invs2-frozen-frontier-histogram.txt`).

### Root cause (confirmed, not a contention artifact)

Reproduced with a SINGLE edge at 50 w/s on a near-idle box (load average 0.37 on 8
cores), the edge is gap-demoted while EXACTLY caught up:

```
consumer_transition identity=edge-1 from=HEALTHY to=CATCHUP reason=gap cursor=10000 lastAckedSeq=10000 gapDemotionsInWindow=1
```

`cursor == lastAckedSeq` means zero consumer lag - the edge has applied and acknowledged
everything. The `reason=gap` comes from `FanOutSessionCore` treating a `readSince()`
GAP as a demotion. `FanOutBuffer.readSince` returns a conservative GAP not only when the
cursor has genuinely fallen off the retention window (`cursor < lastEvictedSeq`) but also
on transient lock-free-read races at the full-buffer eviction boundary (`h - t1 > capacity`,
torn read, or a not-yet-published slot). Once the ring is full (after `capacity` = 10000
writes) and writes continue, a read that coincides with a write+eviction returns one of
these self-healing conservative GAPs - and each is counted toward the gap-demote
quarantine limit (`gapDemoteLimit` = 10 within 60 s). A perfectly healthy, caught-up edge
therefore accumulates spurious gap demotions under any sustained write stream and is
quarantined (~28 min cooldown), never recovering within a measurement window.

Governor evidence: `artifacts/invs2-governor-gap-quarantine-excerpt.log`.

### Impact

This is an edge-fan-out reliability finding, not a measurement artifact: a caught-up edge
on an idle box under trivial load is quarantined. It bears on the v1 edge-hydration story
for any deployment with sustained writes past the buffer capacity. The mechanism-level
staleness guarantee holds while an edge is subscribed; the open question the operator must
decide is whether to fix the transient-GAP-counted-as-demotion accounting before v1, treat
it as a documented known limitation, or investigate further. See the arc handoff for the
disposition decision.
