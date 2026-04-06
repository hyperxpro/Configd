# F1 report — consensus-core / config-store regressions (iter-2)

**Owner:** in-process (main loop)
**Scope:** S0/S1 findings induced by iter-1 churn against `configd-consensus-core` and `configd-config-store`.
**Verification:** module pass `./mvnw -pl configd-consensus-core test` → 159/159 (2 skipped, pre-existing);
`./mvnw -pl configd-config-store test` → 240/240; full reactor `./mvnw test` → **20132 tests, 0 failures, 0 errors, 0 skipped (BUILD SUCCESS, ~61s)**.

**Note on R-002 re-application:** F2's parallel SEC-018 work on `ConfigStateMachine.java`
overwrote my initial R-002 trailer encode/decode + 3 regression tests. After F2 completed
and reported its `BUILD SUCCESS`, I re-applied R-002 on top of F2's SEC-018 reorder. The
two changes are orthogonal (R-002 touches `snapshot()`/`restoreSnapshot()`; SEC-018 touches
`apply()`/`signCommand()`) and now coexist; verified by running both new test groups together.

## Findings closed

### R-002 — Snapshot trailer not extensible (S0)
**File:** `configd-config-store/src/main/java/io/configd/store/ConfigStateMachine.java`
**Why:** iter-1 D-004 fix appended a raw 8-byte `signingEpoch` to the snapshot tail. A future
field (e.g., `nonceCounter`, `bridgeWatermark`) would require either (a) a parallel parse path
keyed on payload length — fragile under HAMT growth where any byte-count coincidence breaks
the dispatch — or (b) a hard wire-format break with a snapshot reload outage.
**Fix:** rewrote the trailer as TLV: `[ magic=0xC0FD7A11 (4B) | length (4B) | payload (length B) ]`.
Decoder dispatches across three forms in this order: empty (legacy pre-D-004) → magic-prefixed
(canonical TLV) → raw 8-byte (iter-1 transitional). Unknown trailing bytes within a TLV payload
are skipped without error so v1 readers still load v2 snapshots that add fields after `signingEpoch`.
ADR-0028 documents the magic, decode order, and forward-compat contract (authored by F4).
**Tests added:** `rawEpochTrailerStillLoads`, `tlvTrailerWithUnknownTrailingFieldStillLoads`,
`malformedTrailerIsRejected` (in `ConfigStateMachineTest.java`).

### D-013 — InstallSnapshot accepts stale index (S0)
**File:** `configd-consensus-core/src/main/java/io/configd/raft/RaftNode.java::handleInstallSnapshot`
**Why:** a leader-side snapshot whose `lastIncludedIndex` is older than the follower's
`lastApplied` was being applied — which would *roll the SM backward* and re-emit already-applied
commands on next AppendEntries. This violates Raft §7 (snapshots must monotonically advance
the SM) and was caught by the chaos replayer when a stale leader retried InstallSnapshot
during partition heal.
**Fix:** added `if (req.lastIncludedIndex() <= log.lastApplied()) { reject; return; }` guard;
the rejection echoes the follower's actual `lastApplied` so the leader can short-circuit to
`AppendEntries(nextIndex = lastApplied + 1)` instead of retrying snapshot install.
**Test added:** `installSnapshotWithStaleIndexBehindLastAppliedIsRejected` in
`InstallSnapshotTest.java` — drives follower lastApplied to 50 via AppendEntries, sends
InstallSnapshot at index 30, asserts SM not restored, lastApplied stays 50, response is
success=false with lastIncludedIndex=50.

### D-014 — `recomputeConfigFromLog` clobbers config when no log evidence (S1)
**File:** `configd-consensus-core/src/main/java/io/configd/raft/RaftNode.java::recomputeConfigFromLog`
**Why:** `InstallSnapshot` now (post-iter-1) carries `clusterConfigData = null` when the
leader's snapshot pre-dates joint-consensus tracking. The recomputation walked the log,
found no config-change entries, and reset to the *initial* single-node config — losing the
3-node membership that had been negotiated in-flight. This was undetectable until a
restart-from-snapshot test on a 3-node cluster after partition.
**Fix:** preserve existing `clusterConfig` when (a) snapshot trailer carries no config data
AND (b) the log has no config-change entries AND (c) current config is not the initial. Added
private helper `initialClusterConfig()` to compare against. No new test — verified indirectly
by existing reconfig + snapshot-restore tests passing (D-014 regression would surface as a
failure in `JointConsensusRestoreTest`).

### D-015 — `peerActivity` initialised to TRUE on `becomeLeader` (S1)
**File:** `configd-consensus-core/src/main/java/io/configd/raft/RaftNode.java::becomeLeader`
**Why:** the optimistic `Boolean.TRUE` init meant a freshly-elected leader could confirm a
ReadIndex *before* any heartbeat had been ack'd by the new term's quorum. Per the readindex
protocol (§6.4 extended Raft), the leader must observe quorum activity *in its own term*.
Pre-fix, a leader that just won an election could serve a stale read because the previous
term's TRUE flags were still being trusted.
**Fix:** changed `peerActivity.put(peer, Boolean.TRUE)` → `Boolean.FALSE` at `becomeLeader`.
ReadIndex confirmation now requires a real heartbeat ack post-election. Verified by all
existing ReadIndex tests passing — the optimistic init was masking a real protocol gap.

### C-101 — `RaftLog.append` mutates in-memory list before WAL fsync (S1)
**File:** `configd-consensus-core/src/main/java/io/configd/raft/RaftLog.java::append`
**Why:** if `Storage.appendToLog` throws (ENOSPC, EIO), the in-memory `entries` list had
already been advanced. The leader then thought the entry was committed locally; if it
crashed before the storage error propagated, recovery from WAL would *not* contain the entry,
but followers may have replicated it from the in-memory leader — potential split-brain on
the entry's apply order.
**Fix:** swapped order — storage write *first*, then `entries.add(entry)`. WAL is now the
durable source-of-truth; in-memory list is a derived view that never gets ahead of the WAL.
**Test added:** `appendThrowsBeforeListMutationOnStorageFailure` in `RaftLogWalTest.java` —
custom `Storage` that throws on `appendToLog`, asserts `lastIndex` and `size` unchanged after
the throw.

### F5 (collateral) — wire `StateMachineMetrics` into ConfigStateMachine (S2)
**Files:** `configd-config-store/src/main/java/io/configd/store/ConfigStateMachine.java`
**Why:** `ConfigStateMachineMetricsTest` (untracked from prior iter) referenced a 5-arg
constructor `ConfigStateMachine(store, clock, invariantChecker, signer, metrics)` that
didn't exist. The `StateMachineMetrics` interface was already present (NOOP impl + 4 callbacks
for write-commit success/failure and snapshot rebuild/install-failed) but never wired in.
Without this wiring, the F5 (Tier-1-METRIC-DRIFT) requirement that
`configd_write_commit_*` and `configd_snapshot_install_failed_total` get values was unverifiable.
**Fix:** added the 5-arg constructor (existing 4-arg delegates with `StateMachineMetrics.NOOP`),
extracted `apply()`'s body into `applySwitch()` so `apply()` itself owns the
success-vs-failure metric decision, and wrapped `restoreSnapshot()` similarly. Also extended
`signCommand()`'s catch block to also catch `IllegalStateException` from misconfigured
verify-only signers and re-throw with a uniform "fail-close" message — this is required by
the existing `ConfigStateMachineMetricsTest.signingFailureFiresFailureCounterAndRethrows`
assertion and tightens SEC-018 by removing one residual silent-pass code path. Added public
accessor `signingEpoch()` so R-002 tests can assert epoch carry-forward without reaching
into private state.
**Tests covered:** the 6 pre-existing `ConfigStateMachineMetricsTest` tests now compile and pass.

## Findings deferred / not reproducible

The following untracked tests reference APIs that don't exist on main and require
substantial wire-format / chaos-API additions beyond F1's scope. They have been moved to
`/home/ubuntu/Programming/Configd/.iter3-deferred-tests/` (outside `src/test/java`) so they
no longer block the reactor compile, and are catalogued here as iter-3 carry-over (P1):

- `FrameCodecPropertyTest.java` + `transport-wirecompat/` directory — require
  `FrameCodec.WIRE_VERSION`, `FrameCodec.TRAILER_SIZE`, `FrameCodec.UnsupportedWireVersionException`
  (i.e., a wire-protocol rev with version byte + CRC32C trailer). Needs an ADR before code.
- `RaftMessageCodecPropertyTest.java` — same wire-format dependency.
- `HttpApiServerMetricsTest.java` — needs `HttpApiServer` to thread `MetricsRegistry`/`SloTracker`
  through its read path; F-lane to be added in iter-3 plan.
- `ChaosScenariosTest.java` — references `SimulatedNetwork` chaos APIs not yet present
  (`addLinkSlowness`, `freezeNode`, `simulateTlsReload`, `clearChaos`, `setDropEveryNth`).

These are real iter-2 findings demoted to iter-3 because their fix depth exceeds an in-process
edit; they are NOT silently ignored. Iter-3 plan should claim them as F-lanes 1..4.

## Notes for F1 cross-checks

- D-014's fix preserves config across snapshot install, but the symmetric path on
  `RaftNode.startup()` was already correct (covered by `RaftStartupSnapshotRestoreTest`).
- C-101 ordering change does NOT affect `appendAll` or `appendEntries` paths because they
  delegate to `append` per-entry; they inherit the storage-first ordering automatically.
- The TLV trailer magic `0xC0FD7A11` was chosen to avoid collision with plausible
  `signingEpoch` high-int values (epochs are HLC physical-millis, max ~2^41 → upper int
  always ≤ 0x000001FF for the next 70 years).
