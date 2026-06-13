# EXP-007 — fsync-lie durability cell (Workstream B-rest)

- **Workstream:** B-rest (charter §4 — "verify fsync is actually durable, not just called").
- **Register row:** none new — closes the kill-matrix `fsync-lie` cell. Oracle is RR-003's `durable_prefix_no_gap`.
- **Status:** GREEN — in-JVM cell landed with a load-bearing-injection mutation capture; real-firmware variant ENVIRONMENT-BLOCKED. No production change (test-infra only).

## Fault + expected behavior (cited)

`storage-fault-layer-design.md §2`: **fsync-lie** = disk firmware that ACKs an fsync then loses
the write on power cut. **Oracle:** a node that "synced" then lost data must, on restart, DETECT
the resulting gap and **fail loud** (`durable_prefix_no_gap`) — never silently serve missing
committed state.

## Injection (extend `CrashStorage`, do NOT fork — design §1/§4)

New `CrashStorage.lieOnSyncForKey(String key)`: a `put(key, …)` to the lied key returns success
(the device ACKs the fsync) but writes to the live `working` image ONLY, never `durable` — so
`crash()` / `recoveredView()` loses it. Mirrors the existing `crashBeforeKeyPut` arming; other keys
are unaffected (still self-durable).

## Test + oracle

`SnapshotCrashRecoveryTest.gapDetectionFiresWhenSnapshotFsyncLied`:
1. boot a single-node leader over `CrashStorage`; commit `k0`,`k1`.
2. `lieOnSyncForKey("raft-log.snapshot")`; `triggerSnapshot()` → `persistSnapshot` puts the blob
   (LIED → working only) and `compact` truncates the WAL prefix + `sync()` (durable). The live node
   sees a consistent snapshot; the durable image has **no blob + a truncated WAL**.
3. `crash()` + `recoveredView()` → a gap below the snapshot boundary.
4. restart (`boot` + `electLeader`) → **`durable_prefix_no_gap` FIRES** (the `THROWING` checker
   throws an `AssertionError` whose message contains `durable_prefix_no_gap`), never a silent skip.

**Key result (verified):** a faithful WAL-level fsync-lie recovers to a state *indistinguishable*
from "blob unrecoverable," so the SAME oracle the existing `gapDetectionFiresWhenSnapshotBlobUnrecoverable`
test proves catches it — this cell adds the **injection-path-agnostic** proof (lied fsync ≡
never-written, at the WAL level).

## Non-vacuity (mutation M-lie)

Defeat the lie (write the lied key to `durable` too) → the blob survives → no gap → recovery does
not fire the invariant → the test goes RED (`expected: not <null>`). Capture:
`captures/exp-007-fsync-lie-defeated-RED.txt`. Reverted; GREEN (`SnapshotCrashRecoveryTest` 7/7).
The injection is therefore load-bearing — the green is not vacuous.

## ENVIRONMENT-BLOCKED (honest, specific)

The **real-firmware** lie — a device with a volatile write cache acknowledging fsync under a true
power cut — can only be proven on hardware. **Staging recipe** (`storage-fault-layer-design.md §3`):
disable the write-cache barrier (`hdparm -W1`, no `fua`) on a power-cuttable node, run the kill
matrix, assert the same oracle; record device + FS + mount flags. Carried to the S5 ENVIRONMENT-BLOCKED
list. The in-JVM cell here proves the *recovery-side detection* is correct; only hardware proves the
*device* honors (or lies about) fsync.

## Reproduction

```
./mvnw -o -pl configd-consensus-core test -Dtest='SnapshotCrashRecoveryTest' -Dsurefire.failIfNoSpecifiedTests=false
# RED: in CrashStorage.put lie branch, also write durable.kv.put(key, …) → gapDetectionFiresWhenSnapshotFsyncLied fails.
```
