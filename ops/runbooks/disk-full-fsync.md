# Runbook: Disk Full (ENOSPC) / fsync Degradation on a Voter

**Alerts:** none direct — surfaces through `ConfigdWriteCommitFastBurn`
(slow fsync), `ConfigdControlPlaneAvailability` (a voter cannot persist),
or `ConfigdSnapshotInstallStalled` (follower disk full). This runbook is
the disk-layer branch those three point at.
**Severity:** page (it degrades or stalls the commit path)

A Configd voter's durability contract is **durable-before-commit**: the WAL
append must `fsync` before the entry counts toward quorum. A full or lying
disk therefore either slows commits (fsync latency) or refuses appends
(ENOSPC). The storage-fault test suite proves the failure **surfaces and
never silently advances the log** — but the operator still has to free the
disk.

## Symptom

- Write commit p99 climbing with apply backlog ~0 → the time is in the
  leader's fsync, not the state machine.
- A voter logs WAL append failures (ENOSPC) and stops counting toward
  quorum; if it is the leader, writes stall until a new leader is elected.
- `kubectl -n configd exec <pod> -- df -h /data` shows the data volume at
  or near 100%.

## Diagnosis

1. **Find the saturated voter.** Check disk on every voter:
   ```sh
   for p in configd-0 configd-1 configd-2; do
     echo "== $p =="; kubectl -n configd exec "$p" -- df -h /data
   done
   ```
2. **Confirm fsync is the latency, not the network/state machine.**
   `Configd Control Plane` dashboard: **"Write commit p99"** high while
   **"State-machine apply p99"** (`configd_apply_seconds`) and
   **"Raft apply backlog"** (`configd_raft_pending_apply_entries`) are
   normal → the cost is in the leader's append/fsync stage.
   Cross-check disk on the leader:
   ```sh
   kubectl -n configd exec <leader> -- sh -c 'iostat -x 1 3 2>/dev/null || cat /proc/diskstats'
   ```
   A growing await / queue depth on the data device is fsync saturation.
3. **What is consuming the disk** — usually WAL growth because snapshots
   are not truncating, or a runaway log:
   ```sh
   kubectl -n configd exec <pod> -- du -sh /data/* | sort -rh | head
   ```
   A large WAL with no recent snapshot means compaction is not keeping up.

## Resolution steps

1. **ENOSPC, single voter, quorum intact:** the cluster still commits on
   the surviving majority (the full voter's appends are rejected, not
   silently lost — that is the durability contract at work). Free its disk:
   - Expand the volume (preferred — no data risk):
     ```sh
     kubectl -n configd patch pvc data-<pod> -p \
       '{"spec":{"resources":{"requests":{"storage":"<new-size>Gi"}}}}'
     ```
     (requires a storage class with `allowVolumeExpansion: true`).
   - If expansion is unavailable, the voter must be drained, its PV wiped,
     and re-added as a fresh voter so it catches up via InstallSnapshot —
     see [snapshot-install.md](snapshot-install.md) "drain and re-add".
     Do this on **one** voter at a time; never below quorum.
2. **ENOSPC on the leader:** it cannot append, so it loses leadership on
   the next election timeout and a healthy voter takes over (writes resume
   automatically). Then free the old leader's disk per step 1. If election
   does not converge, go to [control-plane-down.md](control-plane-down.md).
3. **Slow fsync (disk not full, high await):** this is hardware. Page the
   platform/storage team. Tactically, step the leader off the bad disk by
   recycling the leader pod so re-election picks a healthier voter
   (`kubectl -n configd delete pod <leader>`); only the leader is evicted.
   Do **not** disable fsync or relax durability to "speed up commits" — the
   durable-before-commit contract is what prevents data loss on power-cut.
4. **WAL not truncating (disk filling from log growth):** confirm snapshots
   are being produced (`configd_snapshot_install_failed_total` not climbing,
   and the leader is snapshotting). If snapshotting itself is failing, the
   WAL will grow unbounded — treat as a code/config issue and file against
   the storage engine; freeing disk only buys time.

## Verification

- `df -h /data` shows healthy free space on the affected voter.
- Write commit p99 returns below 150 ms; `ConfigdWriteCommitFastBurn`
  clears.
- All voters count toward quorum again: `configd_write_commit_total` rate
  back to baseline, `ConfigdControlPlaneAvailability` clear.
- A test write commits and reads back from every voter.

## Escalation

- Page platform/storage immediately for the slow-fsync (high await,
  not-full) case — Configd cannot mitigate hardware latency.
- Escalate to [disaster-recovery.md](disaster-recovery.md) if ENOSPC hit
  ≥ ⌊N/2⌋+1 voters at once (quorum is at risk) or if freeing one voter's
  disk drops the cluster below quorum during the drain.

## Validation (fault injection)

`StorageEnospcConsensusReactionTest`
(`configd-testkit/src/test/java/io/configd/testkit/StorageEnospcConsensusReactionTest.java`)
arms `FaultInjectingStorage.enospcAfterBytes(...)` and asserts the WAL
append failure **surfaces** (not swallowed), **never silently advances the
log** (durable-first), the node **recovers once space returns**, and a
snapshot-write ENOSPC leaves the WAL prefix intact (persist-before-truncate).
`FaultInjectingStorage.failNextSyncs(n)` injects fsync failures for the
slow/failing-fsync branch. Recovery-verified = the test's "node recovers
once space returns" assertion = the cluster re-accepts the test write after
`df` shows free space.

## Related

- Storage-fault test suite — ENOSPC + fsync-lie correctness.
- [snapshot-install.md](snapshot-install.md) — drain/re-add path for a
  wiped voter.
- [control-plane-down.md](control-plane-down.md) — when a full leader stalls
  the cluster.
- `configd-testkit/.../FaultInjectingStorage.java` — the injection harness.
