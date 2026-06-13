# B2 — Kill matrix (two planes): cells, oracles, and status

Charter §B2. Extends Session 2's crash-recovery matrix (`SnapshotCrashRecoveryTest`) — does
NOT fork it. Every cell declares its ORACLE before execution (matrix-before-execution rule).
Universal oracle unless a cell overrides: **restart → recovered state == committed state;
`durable_prefix_no_gap` holds; the complete prefix (snapshot@S + WAL(S..last]) reconstructs
all committed entries; a detected gap fails LOUDLY, never silently.**

Status legend: ✅ done (test cited) · 🔬 partial (mechanism exists, cell not yet run) ·
⏳ pending (this/next run).

## Control plane — WAL & snapshot lifecycle

| Cell | Oracle | Status / evidence |
|---|---|---|
| Crash before snapshot persist | full WAL intact; recover from WAL; no loss | ✅ `SnapshotCrashRecoveryTest` (RR-003), 240-cell matrix |
| Crash after snapshot persist, before WAL truncate | recover from snapshot+WAL; no loss; no double-apply | ✅ `SnapshotCrashRecoveryTest` |
| Crash after WAL truncate | recover from snapshot+suffix; no gap | ✅ `SnapshotCrashRecoveryTest` |
| Torn final WAL record | detect torn tail; discard if uncommitted; fail loud if it would drop committed | ✅ `SnapshotCrashRecoveryTest` torn-tail cell |
| Unrecoverable snapshot blob (gap) | `durable_prefix_no_gap` FIRES; refuse to boot silently | ✅ `SnapshotCrashRecoveryTest.gapDetectionFires...` |
| **Write failure during apply on a follower** | surface (metric + SEVERE log), not swallow → mute zombie | ✅ **RR-008 / EXP-003** (`InboundRoutingThrowableHandlerTest`) |
| fsync-lie on WAL (firmware ACK then drop) | restart detects the gap; refuse to boot / fail loud | ⏳ pending — extend `CrashStorage` with `lieOnSync`; oracle in design §2 |
| ENOSPC during WAL append | leader sheds (503), follower surfaces; defined degradation, no crash-loop, no silent loss | 🔬 `FaultInjectingStorage.enospcAfterBytes` built (B1); cell not yet run (B3) |
| ENOSPC during snapshot write | snapshot fails cleanly; WAL prefix NOT truncated (no loss); retry next interval | ⏳ pending (B3) |
| Crash during snapshot install on a FOLLOWER | persist-before-compact on the install path; recover; no gap | 🔬 RR-003 fix covers `handleInstallSnapshot` persist-then-compact; dedicated kill cell ⏳ |
| Crash during leadership transfer | new leader elected; committed prefix preserved; no split-brain | ⏳ pending |
| short read on WAL recovery | detect truncation (contiguity); fail loud | 🔬 `FaultInjectingStorage.shortReadLog` built; cell ⏳ |

## Reconfiguration (ties into Workstream D §2)

| Cell | Oracle | Status |
|---|---|---|
| kill -9 pre-joint (`C_old,new` not yet committed) | restart → config == old; change can be re-proposed | ⏳ D §2 |
| kill -9 with `C_old,new` committed, `C_new` not | restart → `recomputeConfigFromLog` rebuilds joint; new leader (dual majority) finalizes | ⏳ D §2 (the genuine mid-joint case — see reconfiguration-status-check.md) |
| kill -9 with `C_new` committed | restart → config == new (4-voter) | ✅ in-sim `recomputeConfigFromLogRestoresMembershipAcrossRestart` (RR-018); kill-cell variant ⏳ |
| leader crash mid-joint-config | new leader under dual majority completes the transition; no committed-entry loss | ⏳ D §2 (gap: existing test finalizes before the election — see status check) |

## Edge data plane

| Cell | Oracle | Status |
|---|---|---|
| Edge crash mid-delta-apply | restart → cursor-0 → SNAPSHOT_FIRST (RR-100); snapshot==delta equivalence | 🔬 `MonotonicReadAcrossEdgeRestartTest` (S3); chaos-kill variant ⏳ (A3) |
| Edge crash mid-bootstrap | resume / re-bootstrap; exact cutover cursor | 🔬 S3 bootstrap tests; kill-cell ⏳ (A3) |
| Edge crash mid-catchup | contiguous resume or GAP→snapshot | 🔬 S3 catch-up tests; kill-cell ⏳ (A3) |

## Done this run (B increment)
- B1 `FaultInjectingStorage` layer + self-test (write-failure / ENOSPC / fsync-fail /
  short-read / latency-hook). `FaultInjectingStorageTest` 5/5.
- RR-008 (write-failure-during-apply → mute zombie) RESOLVED with red→green discriminating
  test + mutation-revert.
- Storage-fault design note with the full oracle catalogue + ENVIRONMENT-BLOCKED list.

## Pending (next run — resume at these seams, not mid-cell)
- fsync-lie + short-read + ENOSPC cells executed (extend `CrashStorage` with `lieOnSync`;
  storage-back a follower's `RaftLog` so `FaultInjectingStorage` reaches the WAL append path).
- B3 disk pathology under load (ENOSPC degradation; slow-disk follower must not drag leader;
  fsync >1s → voluntary step-down per arch §6).
- RR-005 (compaction unreachable in the wired server + `FileStorage` `(int) fileSize` 2 GiB
  cast) — verify + fix. RR-019, RR-086, RR-064 review.
- Crash cells for snapshot-install-on-follower, leadership transfer, edge mid-*.
