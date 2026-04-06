# chaos-skeptic — iter-002
**Findings:** 18

iter-1 closed C-001 (FrameCodec CRC32C) and C-002 (FileStorage CRC envelope).
The deferred S1s C-006 (disk-full), C-009 (slow-fsync), C-010 (HLC backward
clock jump) remain unresolved at HEAD `22d2bf30` — they are restated below
under their iter-2 IDs. Severity floor S3.

---

## C-101 — Disk-full / IOException on RaftLog.append silently corrupts leader's commit invariant
- **Severity:** S0
- **Location:** `configd-consensus-core/src/main/java/io/configd/raft/RaftLog.java:276-286` (`append`); `configd-common/src/main/java/io/configd/common/FileStorage.java:133-158` (`appendToLog`); `configd-consensus-core/src/main/java/io/configd/raft/RaftNode.java` apply path
- **Category:** disk-full
- **Evidence:** `RaftLog.append(LogEntry)` mutates the in-memory `entries` list **first** (line 282) and only **then** delegates to `storage.appendToLog` (line 284). `FileStorage.appendToLog` throws `UncheckedIOException` on ENOSPC / EIO / EROFS (line 156). Because the in-memory append already happened and there is no try/catch nor rollback, the leader returns from `append()` with a divergent in-memory log: `lastIndex()` advanced, no WAL record. The next `entriesBatch()` will hand that entry to followers, who fsync it durably while the leader has it only in volatile memory. After a leader crash, the cluster commits an entry the leader could not durably log — Raft's "leader has a superset of all committed entries" property is violated.
- **Steps to reproduce:** (a) Replace `Storage` with a `FaultyStorage` that returns OK for the first N appends, then throws `UncheckedIOException("ENOSPC")`. (b) Drive the leader to append entry N+1 and replicate. (c) Followers ack; commitIndex advances. (d) Crash the leader. (e) Recovery reads only N entries from WAL — entry N+1 is gone from the leader but durable on followers. **No regression test exercises this ordering.**
- **Fix proposal:** (1) Reorder `RaftLog.append` to write WAL first, then the in-memory list — `storage.appendToLog(...)` must succeed before `entries.add(entry)`. (2) Add `configd-testkit/src/main/java/io/configd/testkit/FaultyStorage.java` with `failOnAppend(int afterN, IOException cause)` and `failOnSync(...)` primitives. (3) Add `RaftLogWalTest.appendThrowsBeforeInMemoryMutationOnEnospc()`. (4) Above this, in `RaftNode`, catch `UncheckedIOException` from `log.append(...)` and step down (the leader cannot make progress without WAL durability).
- **Proposed owner:** consensus-core (RaftLog ordering) + testkit (FaultyStorage)

## C-102 — `Compactor.rewriteWal()` write-then-rename has no per-step fault-injection coverage
- **Severity:** S1
- **Location:** `configd-consensus-core/src/main/java/io/configd/raft/RaftLog.java:485-507` (`rewriteWal`); `configd-common/src/main/java/io/configd/common/FileStorage.java:231-241` (`renameLog`)
- **Category:** restart
- **Evidence:** `rewriteWal` performs three storage operations: `truncateLog(WAL_TMP_NAME)` → `appendToLog(WAL_TMP_NAME, ...)` per entry → `renameLog(WAL_TMP_NAME, WAL_NAME)`. The Javadoc claims atomicity, but there is no test that injects a crash **between** any two of those steps. Specifically, a SIGKILL between `appendToLog` (last entry) and `renameLog` leaves both `raft-log.wal` (old) AND `raft-log.tmp.wal` (new). On restart, `RaftLog(Storage)` calls `storage.truncateLog(WAL_TMP_NAME)` (line 96) — silently discarding the partial new WAL — and replays the **old** WAL with the **new** snapshot metadata. The stale-metadata recovery branch at line 136-146 catches one shape of this but only by zeroing `snapshotTerm`, which is "conservative" but actually permits granting votes that should be denied (the comment at line 142-145 admits this).
- **Steps to reproduce:** Drive a compaction; intercept the `Storage` to crash after the second `appendToLog` for the temp WAL but before `renameLog`. Restart. Assert that no stale entry from the old WAL is observed AFTER the snapshot's lastIncludedIndex.
- **Fix proposal:** (1) Promote `FaultyStorage` (see C-101) with `failBeforeOp(StorageOp op, int nthCall)` — including `RENAME`. (2) Add `RaftLogWalTest.rewriteWalCrashBeforeRenameRecovers()`. (3) Document that the on-disk format intentionally keeps both files and that recovery must prefer the temp file (which is newer and complete) when its CRCs are valid; `truncateLog(WAL_TMP_NAME)` at line 96 is wrong if the temp file is actually the source of truth post-crash.
- **Proposed owner:** consensus-core (RaftLog) + common (FileStorage) + testkit

## C-103 — `FileStorage.put` temp+rename has no fsync-of-temp coverage; partial-write of `.dat.tmp` undetected on retry
- **Severity:** S1
- **Location:** `configd-common/src/main/java/io/configd/common/FileStorage.java:50-92`
- **Category:** disk
- **Evidence:** `put` writes the framed payload to `<key>.dat.tmp`, calls `channel.force(true)` (good), then `Files.move(..., ATOMIC_MOVE)`, then `sync()` to fsync the directory. But on a previous-call crash, a partial `.dat.tmp` may be left behind. The next `put(key, ...)` opens the tmp file with `TRUNCATE_EXISTING` (line 76) which discards the leftover, but **between** previous-crash and next-call there is a window where an out-of-band reader (e.g. backup script, log shipper, `restore-conformance-check.sh:131` which `tail -c +13`s an arbitrary file) sees a `.dat.tmp` whose CRC trailer is bogus. There is also no test that the **directory fsync** at line 88 is called even when `Files.move` throws — if move fails, `sync()` never runs and the prior overwrite of an unrelated key may be lost on power loss.
- **Steps to reproduce:** (1) Crash mid-write of `.dat.tmp` (partial write of length+crc+payload). (2) Restart. (3) Operator runs a backup script that picks up `*.dat.tmp` — they get a corrupt file with no warning. (4) Separately: `Files.move` throws (read-only filesystem during DR drill) — no directory fsync, so a previous successful `put` of a different key may not be durable yet.
- **Fix proposal:** (1) Wrap `Files.move + sync` in a try/finally so the dir-fsync runs even on move failure. (2) On `FileStorage` construction, sweep `*.dat.tmp` and delete; add a structured log "leftover_temp_purged". (3) Add `FileStorageTest.crashBetweenForceAndRenameLeavesNoOrphan()` using a `FaultyStorage`-style injection that throws between `force` and `move`.
- **Proposed owner:** common (FileStorage)

## C-104 — Slow-fsync stall (≥10s) on quorum is still not exercised — `SlowStorage` primitive missing
- **Severity:** S1 (re-deferred from iter-1 C-009)
- **Location:** `configd-common/src/main/java/io/configd/common/Storage.java`; `configd-testkit/src/main/java/io/configd/testkit/` (no `SlowStorage`); `configd-consensus-core/src/main/java/io/configd/raft/RaftLog.java:284, :367` (calls `appendToLog` and `sync`)
- **Category:** slow-fsync
- **Evidence:** iter-1 explicitly deferred this. Search confirms zero hits for `SlowStorage`, `fsyncDelay`, or `failOnSync` across the testkit. `SimulatedNetwork.addLinkSlowness` covers the network side only. The single-threaded apply path in `RaftLog` (line 24 comment) means a 10s `channel.force(true)` blocks the tick thread — heartbeats, ReadIndex confirmations, snapshot dispatch — for the entire stall. There is no test that asserts the cluster **transfers leadership** when the leader's fsync exceeds half the election timeout.
- **Steps to reproduce:** Wrap `Storage.file(path)` with `SlowStorage(delegate, fsyncDelayMs=15_000)`. Run a 3-node cluster, bring node 1 to leader, drive a write. Expected: leader steps down (or pre-vote loss), node 2 becomes leader, no commit divergence. Today: untestable.
- **Fix proposal:** Add `configd-testkit/src/main/java/io/configd/testkit/SlowStorage.java` decorator with `setFsyncDelayMs(long)`, `setAppendDelayMs(long)`. Add `ChaosScenariosTest.fsyncStallTriggersLeadershipTransfer()`. In `RaftNode`, document the maximum tolerable per-tick fsync as `electionTimeoutMin × heartbeatInterval / 4` and surface a `configd_raft_apply_stall_ms` gauge (already a planned metric per `restore-conformance-check.sh:191`).
- **Proposed owner:** testkit + consensus-core

## C-105 — HLC backward wall-clock jump still overflows the 16-bit logical counter
- **Severity:** S1 (re-deferred from iter-1 C-010)
- **Location:** `configd-common/src/main/java/io/configd/common/HybridClock.java:89-108` (`now`); `:114-142` (`receive`); `configd-testkit/src/main/java/io/configd/testkit/SimulatedClock.java:52-55` (`setTimeMs` allows backward jumps but no chaos test exercises the HLC under it)
- **Category:** clock
- **Evidence:** Implementation unchanged since iter-1. The `pt > curPhys` branch still freezes `nextPhys` to `curPhys` when wall-clock goes backward; `nextLogical` increments without bound and the encoded long silently truncates (`encode` masks with `0xFFFF`). At 100k ops/sec, NTP step-corrections >650 ms cause silent timestamp collisions. `SimulatedClock.setTimeMs` already supports backward jumps (`SimulatedClockTest.canSetTimeBackward`, line 184), so the harness side is one assertion away — but no test wires it to `HybridClock`.
- **Steps to reproduce:** `SimulatedClock c = new SimulatedClock(1_000_000)`; `HybridClock h = new HybridClock(c)`; call `h.now()` 70_000 times; `c.setTimeMs(500_000)` (backward jump 500 s); call `h.now()` 70_000 more times. Assert: every returned packed long is strictly greater than the previous — today this fails after ~65k events.
- **Fix proposal:** (1) On overflow, throw `LogicalCounterOverflowException` AND advance `nextPhys` by 1 ms (the standard HLC rescue). (2) Add `HybridClockTest.backwardClockJumpDoesNotOverflowLogical()`. (3) Document maximum tolerated burst rate per ms of clock-step in `HybridClock` Javadoc.
- **Proposed owner:** common (HybridClock) + testkit

## C-106 — `CatchUpService` has zero tests; delta-history-purged-mid-catchup behaviour unspecified
- **Severity:** S1
- **Location:** `configd-distribution-service/src/main/java/io/configd/distribution/CatchUpService.java:97-129`; `configd-distribution-service/src/test/java/io/configd/distribution/` — **no `CatchUpServiceTest.java`** (verified by `ls`)
- **Category:** edge-catchup
- **Evidence:** `CatchUpService` is referenced by 20 docs files including `runbooks/edge-catchup-storm.md` but has no unit tests. The `resolve()` method walks `deltaHistory.get(currentVersion)` in a loop — if `recordDelta` runs concurrently with `resolve` (they share `HashMap` with no synchronization, and the class is "designed for single-threaded access" per the Javadoc, but `WatchService` is not visibly single-threaded), `trimHistory` may evict the delta the loop is currently following, leaving the loop with stale state. Additionally, `snapshotProvider.currentSnapshot()` is called **inside** the loop (line 114) — if the snapshot pointer flips mid-walk, the chain may be falsely declared incomplete and a full snapshot retransmit triggered for an edge that was 95% caught up.
- **Steps to reproduce:** (1) Spin up 1 producer thread calling `recordDelta` at 1 kHz with `maxDeltaHistory=10`. (2) From a second thread, call `resolve(node, oldVersion)` where `oldVersion` is just barely in-window. (3) Race-detector observes mid-loop eviction → `chain` is partial but not detected, falls through to snapshot, edge does an unnecessary full sync.
- **Fix proposal:** (1) Add `CatchUpServiceTest` covering: empty history, complete chain, partial chain → snapshot, snapshot null → Unavailable. (2) Add a chaos primitive `CatchUpService.purgeOldestN(int)` so a test can simulate trim races deterministically. (3) Add a `purgeRaceDoesNotMisclassify()` test that drives concurrent `recordDelta`/`resolve` under `SimulatedClock`. (4) Document and assert the threading model — either fully synchronize or document the single-thread invariant with a `Thread.holdsLock` runtime check.
- **Proposed owner:** distribution (CatchUpService) + testkit

## C-107 — `SnapshotTransfer.acceptChunk` has no out-of-order / duplicate-chunk fuzz coverage; `expectedOffset` integer overflow at 2 GiB snapshot
- **Severity:** S2
- **Location:** `configd-replication-engine/src/main/java/io/configd/replication/SnapshotTransfer.java:266-285`; integer field `expectedOffset` (line 144)
- **Category:** snapshot-corruption
- **Evidence:** `expectedOffset` is `int`. Each accepted chunk does `state.expectedOffset = offset + data.length` — at `Integer.MAX_VALUE` it wraps negative, and the next `acceptChunk(state, offset, ...)` with offset == previous expectedOffset (still negative) silently succeeds. A snapshot >= 2 GiB (well within `MAX_SNAPSHOT_CHUNK_BYTES * many-chunks` budget) corrupts the receive state. There is no test for: (a) duplicate chunk submitted twice (current code rejects it via offset mismatch — good — but no test asserts this), (b) chunk arrives with `done=true` but offset+length != totalSize (no totalSize is tracked, so this is undetectable), (c) overflow.
- **Steps to reproduce:** Submit chunks adding up to `Integer.MAX_VALUE - 100` then a chunk of length 1024: `expectedOffset` overflows, next chunk accepted at negative offset. `assemble` writes to a `ByteArrayOutputStream` regardless. Receiver installs garbage with valid `lastIncludedIndex/term`.
- **Fix proposal:** (1) Promote `expectedOffset` to `long` and `assemble` to use `ByteArrayOutputStream`-with-capacity-check. (2) Track `totalSize` either by carrying it on the first chunk or by validating on `done=true`. (3) Add property test: random shuffles + duplicates of N chunks must either produce an exact byte-equal snapshot or be rejected; never partial garbage.
- **Proposed owner:** replication-engine (SnapshotTransfer)

## C-108 — Chunked InstallSnapshot: no chaos test for sender-crash-mid-stream + leader-change combination
- **Severity:** S2 (related to deferred iter-1 C-011)
- **Location:** `configd-server/src/main/java/io/configd/server/RaftMessageCodec.java:209-265`; `configd-consensus-core/src/main/java/io/configd/raft/RaftNode.java:1434-1490` (single-shot install — no chunk reassembly state)
- **Category:** restart + leader-change
- **Evidence:** `handleInstallSnapshot` treats every `InstallSnapshotRequest` as **complete** (line 1467: `restoreSnapshot(req.data())`). The `done`/`offset` fields exist in the wire format but are not used to maintain `SnapshotReceiveState` on the follower side. The chunked transfer machinery in `SnapshotTransfer` exists but is **not wired** to the RPC path — `RaftNode` cannot accept a multi-chunk install. Meanwhile `MAX_SNAPSHOT_CHUNK_BYTES = 16 MiB`. A snapshot >16 MiB cannot be installed at all, and the leader-change-mid-install scenario therefore is also untested because the only-test code path doesn't have an "in-flight" state to interrupt.
- **Steps to reproduce:** Take a snapshot >16 MiB via `ConfigStateMachine.snapshot()` (set 100k entries, each with 200B value). Leader sends InstallSnapshot. Receiver throws because `dataLen > MAX_SNAPSHOT_CHUNK_BYTES`. Cluster cannot recover a far-behind follower without operator surgery.
- **Fix proposal:** (1) Wire `SnapshotTransfer.SnapshotReceiveState` into `RaftNode.handleInstallSnapshot` keyed by `(leaderId, lastIncludedIndex, lastIncludedTerm)`. (2) Reset on leader change (drop in-flight state, re-request from new leader). (3) Add `InstallSnapshotTest.leaderChangeMidChunkedInstallRestartsCleanly()` and `multiChunkLargerThanMaxChunkBytesInstalls()`.
- **Proposed owner:** consensus-core (RaftNode) + replication-engine

## C-109 — `Dockerfile.runtime` has no SIGTERM-to-SIGKILL escalation test; entrypoint uses exec-form java but no STOPSIGNAL nor preStop in K8s
- **Severity:** S2
- **Location:** `docker/Dockerfile.runtime:69-75` (ENTRYPOINT exec-form, no STOPSIGNAL); `deploy/kubernetes/configd-statefulset.yaml:53` (`terminationGracePeriodSeconds: 30`, no `lifecycle.preStop`); `configd-server/src/main/java/io/configd/server/ConfigdServer.java:603-651` (shutdown hook with 5+2+2s = 9s budget against pod-termination 30s)
- **Category:** process-death
- **Evidence:** No `STOPSIGNAL` directive in the Dockerfile (defaults to SIGTERM, which is correct), but no test confirms the JVM shutdown hook actually drains the apply thread and persists `currentTerm`/`votedFor` before the 30s grace expires. No `preStop` hook → the kubelet sends SIGTERM and starts the timer immediately, racing with iptables removal from Service endpoints — peers may still send AppendEntries the dying node tries to process. A K8s `lifecycle.preStop: { exec: { command: ["/bin/sh","-c","sleep 5"] } }` is the standard remediation. The shutdown order in `ConfigdServer.shutdown()` (line 621) is good but the 30s `terminationGracePeriodSeconds` budget against `5+2+2s` executor drains plus an in-flight 16 MiB snapshot install on a slow disk could exceed budget — kubelet then SIGKILLs while WAL fsync is in flight.
- **Steps to reproduce:** Manual: `kubectl delete pod configd-0`, observe whether `currentTerm`/`votedFor` are durable on restart. Automated: write a `ContainerLifecycleTest` that runs the image with `docker stop --time=10` while a write is in-flight; assert the WAL is consistent on restart.
- **Fix proposal:** (1) Add `lifecycle.preStop: exec: ["/bin/sleep","5"]` to the StatefulSet so endpoint deregistration completes before SIGTERM. (2) Add `STOPSIGNAL SIGTERM` explicitly in Dockerfile (defensive). (3) Add `docker/test/sigterm-during-write.sh` that runs the image, fires a write, sends SIGTERM, asserts post-restart `lastApplied >= the-write-index`. (4) Document the relationship between `terminationGracePeriodSeconds`, executor drain budget, and worst-case fsync time.
- **Proposed owner:** ops/Docker + server

## C-110 — `restore-snapshot.sh` does not handle disk-full / preexisting `.partial` / interrupted prior restore
- **Severity:** S2
- **Location:** `ops/scripts/restore-snapshot.sh:215-232` (snapshot copy) and `:260-278` (restart loop)
- **Category:** restore + disk-full
- **Evidence:** `cp -f "$SNAPSHOT_PATH" "${DEST_FILE}.partial"` (line 227) does not check ENOSPC — `cp` will return non-zero and `set -e` will abort with no cleanup. The `.partial` file is left on disk; subsequent restore retries do NOT scan for stale `.partial`s and may fill the disk with orphaned partials. There is no preflight `df` check ensuring the data dir has at least `2 × snapshot_size` free (need room for the snapshot AND the existing data dir during transition). Step 5 (restart) waits 60s for `is-active` but if the restored snapshot triggers a panic (e.g. C-107 corruption), the script exits 3 with no rollback to the prior data dir — operator must manually recover. The runbook reference at line 7 (`ops/runbooks/disaster-recovery.md`) does not document this hole.
- **Steps to reproduce:** Fill the data dir to 95%; run `restore-snapshot.sh --dry-run=false --i-have-a-backup` with a 1 GiB snapshot. `cp` fails with ENOSPC. Run again — orphan `.partial` from prior attempt is now in the way; the second run also fails because the dir is even more full. No automated cleanup.
- **Fix proposal:** (1) Add `preflight_disk_check()` that reads `stat -c %s "$SNAPSHOT_PATH"` and `df -B1 --output=avail "$DATA_DIR"` and aborts if `available < 2 * snapshot_size + 256MiB`. (2) Add a startup `find "$SNAPSHOT_DIR" -name 'restore-*.partial' -mmin +60 -delete` (or equivalent BSD/GNU portable). (3) On Step-5 restart failure, `systemctl start configd@previous` rollback path (snapshot the data dir to `.../data.pre-restore` BEFORE step 3, restore on step-5 failure). (4) Add `ops/scripts/test-restore-disk-full.sh` (BATS or shellcheck-clean shell test) using `truncate -s 100M /tmp/tinyfs && mkfs.ext4 -F /tmp/tinyfs && ...`.
- **Proposed owner:** ops/scripts + runbooks

## C-111 — Asymmetric / bridge-node partition still unrepresentable; iter-1 C-003 unresolved
- **Severity:** S2
- **Location:** `configd-testkit/src/main/java/io/configd/testkit/SimulatedNetwork.java:71-89`; no `bridgeNode` helper; no `ChaosScenariosTest.BridgeNode` nested class
- **Category:** partition
- **Evidence:** Confirmed unchanged since iter-1. `addPartition` is correctly one-way but the API gives no ergonomic way to express "N1 talks to N2 and N3, but N2 cannot reach N3 except via N1." The simulator also has no concept of "queued message at the bridge that arrives 30s late after N1 also dies", which is the canonical Jepsen "asymmetric partition w/ slow link" scenario that breaks lease-based ReadIndex (D-001 area).
- **Fix proposal:** Add `SimulatedNetwork.bridgeNode(NodeId pivot, Set<NodeId> islandA, Set<NodeId> islandB)` helper that calls the right `addPartition` pairs. Add `ChaosScenariosTest.bridgeNodeWithSlowLinkDoesNotElectTwoLeaders()` driving 5 nodes for 30 election timeouts. Per iter-1 fix direction, also add a `BridgePartition` invariant scenario to `spec/ConsensusSpec.tla`.
- **Proposed owner:** testkit (SimulatedNetwork) + spec

## C-112 — Out-of-order / duplicate delivery primitive still missing; iter-1 C-013 unresolved
- **Severity:** S2
- **Location:** `configd-testkit/src/main/java/io/configd/testkit/SimulatedNetwork.java:188-203` (`deliverDue`)
- **Category:** reorder
- **Evidence:** `deliverDue` is still strict FIFO per `(deliverAtMs, sendSequence)`. No `duplicateRate(double)`, no `swapAdjacentDeliveries(seed)`. The `RaftTransport.java:8-13` Javadoc still claims the simulator controls "reordering and loss" — the contract overstates the implementation.
- **Fix proposal:** Add `SimulatedNetwork.setDuplicateRate(double)` (re-enqueues a copy at `deliverAt + jitter`), `setReorderRate(double)` (swaps `compareTo`), and a property test asserting Raft safety under random duplicate+reorder × 1000 messages × 100 seeds. Update `RaftTransport` Javadoc so it does not promise behaviour that is unimplemented.
- **Proposed owner:** testkit (SimulatedNetwork)

## C-113 — Per-link `dropEveryNth` + Byzantine-impersonation primitives still missing; iter-1 C-014 + C-015 unresolved
- **Severity:** S2
- **Location:** `configd-testkit/src/main/java/io/configd/testkit/SimulatedNetwork.java:97-98`; `configd-transport/src/main/java/io/configd/transport/TcpRaftTransport.java:222-223` (cleartext `senderId` accepted)
- **Category:** partition + Byzantine
- **Evidence:** `dropEveryNth` is still global (single counter at line 97). `TcpRaftTransport.handleInboundConnection` still reads `int senderId = in.readInt()` (line 222) with no validation against the TLS cert subject — a peer with a valid cluster cert can claim any NodeId. The simulator cannot inject `injectImpersonatedMessage(trueFrom, claimedFrom, msg)` to drive coverage of "what does RaftNode do when it receives a `RequestVoteResponse` from a node it never sent a `RequestVoteRequest` to?"
- **Fix proposal:** Per-link `Map<Long,Integer> perLinkDropEveryNth`. Add `injectImpersonatedMessage` that bypasses `partitions` checks. Add `ChaosScenariosTest.impersonatedVoteResponseIsIgnored()` and `forgedAppendEntriesFromNonLeaderIsRejected()`. Land the consensus-side fix: `RaftNode` must remember `votingPeers` and reject responses from non-peers.
- **Proposed owner:** testkit + transport + consensus-core

## C-114 — TLS cert hot-reload during in-flight TCP connection: `currentContext` swap doesn't tear down old `SSLSocket`s
- **Severity:** S2 (extends iter-1 C-005)
- **Location:** `configd-transport/src/main/java/io/configd/transport/TlsManager.java:84-86`; `configd-transport/src/main/java/io/configd/transport/TcpRaftTransport.java:295-340` (peer connections cached in `outbound`)
- **Category:** cert-rotation
- **Evidence:** `TlsManager.reload()` swaps `volatile currentContext`. But `TcpRaftTransport.PeerConnection.socket` is set ONCE in `ensureConnected` (line 410-426) — it is the SSL socket created with the OLD context. The socket and its session keys persist until the next IOException. A long-lived AppendEntries stream survives a key-rotation event (good for liveness) but uses the OLD cert-private-key to sign records (bad for compromise-recovery: a leaked private key cannot actually be revoked by rotation). The `tlsReloadExecutor` runs every `TLS_RELOAD_INTERVAL_MS` (`ConfigdServer.java:600`) — but no test cycles two reloads while a connection is open and asserts the connection is eventually rebuilt.
- **Steps to reproduce:** Open a peer connection with cert-A. Call `reload()` with cert-B. Observe via `socket.getSession().getPeerCertificates()` that cert-A is still in use indefinitely.
- **Fix proposal:** (1) Add a `generation` counter to `TlsManager`; record the generation on each `PeerConnection` at open time; on each `sendFrame`, if `tlsManager.generation() > conn.generation`, close-and-reopen. (2) Add `SimulatedNetwork.expireCertOnConnection(NodeId, generation)` so a chaos test can drive the rebuild path. (3) Add `TcpRaftTransportTest.certRotationEventuallyRebuildsLongLivedConnection()`.
- **Proposed owner:** transport (TlsManager + TcpRaftTransport)

## C-115 — `ConnectionManager` reconnect-storm: 3 nodes restarting simultaneously create N² synchronized retries (no jitter)
- **Severity:** S2
- **Location:** `configd-transport/src/main/java/io/configd/transport/ConnectionManager.java:86-96` (`markDisconnected`)
- **Category:** reconnect-storm
- **Evidence:** Backoff is `currentBackoffMs = min(currentBackoffMs * 2.0, MAX_BACKOFF_MS)`. **No jitter.** All N peers that lose connection at the same instant (rolling-restart, switch reboot) will retry at exactly the same backoff phase — at `t=100ms`, `t=200ms`, `t=400ms` … `t=30s` and stay synchronized indefinitely. Combined with the lack of DNS-failure injection (iter-1 C-007 still open), a CoreDNS blip during a rolling restart triggers a thundering-herd reconnect. There is no test that fires 5 nodes' `markDisconnected` simultaneously and asserts the reconnect attempts are temporally jittered.
- **Fix proposal:** (1) Add `± 50%` decorrelated jitter to `currentBackoffMs` (AWS-style `sleep = random_between(base, prev_sleep * 3)`). (2) Add `ConnectionManagerTest.simultaneousFailuresProduceJitteredRetries()` — assert that with seeded PRNG, no two of 100 peers retry within the same 50 ms bucket. (3) Wire jitter to a per-`ConnectionManager` `Random` seeded from `Clock.nanoTime()` for production, injectable for tests.
- **Proposed owner:** transport (ConnectionManager)

## C-116 — `LocalConfigStore` cold-start race: `loadSnapshot()` is unsynchronized vs concurrent `applyDelta()`; no chaos test for snapshot-arrives-during-delta
- **Severity:** S2
- **Location:** `configd-edge-cache/src/main/java/io/configd/edge/LocalConfigStore.java:229-268` (`applyDelta` and `loadSnapshot` both write `currentSnapshot` with no CAS)
- **Category:** cache-corruption
- **Evidence:** Class Javadoc says "writer thread must be externally serialized" but both `applyDelta` (line 256) and `loadSnapshot` (line 267) write `currentSnapshot` without synchronization. If a follower is mid-`applyDelta` when `EdgeConfigClient.loadSnapshot` is called from a different thread (e.g. catch-up handler races with delta-applier), the store can publish a snapshot that goes BACKWARD in version — `currentVersion()` decreases — which a holder of a `VersionCursor` then sees as a monotonic-read violation. The `InvariantMonitor` catches it but the data is already corrupt. There is no test that verifies the single-writer invariant by running concurrent writers under JCStress / Lincheck and asserting failure (i.e., a guard test for the documented invariant).
- **Fix proposal:** (1) Either synchronize both writers or add an `AtomicReference<ConfigSnapshot> currentSnapshot` with a CAS that rejects backward version moves. (2) Add `LocalConfigStoreTest.concurrentApplyDeltaAndLoadSnapshotIsRejected()` (Lincheck linearizability test). (3) Add a `setSnapshot` helper that takes both old and new snapshots and asserts `new.version >= old.version` before publish — fail loudly with `IllegalStateException`.
- **Proposed owner:** edge-cache (LocalConfigStore)

## C-117 — `@Buggify` runtime is still dead code; iter-1 C-016 unresolved
- **Severity:** S3
- **Location:** `configd-common/src/main/java/io/configd/common/Buggify.java`; `configd-common/src/main/java/io/configd/common/BuggifyRuntime.java`
- **Evidence:** Re-verified — `BuggifyRuntime.shouldFire` has zero production call sites. The CHANGELOG shows no Buggify wiring in iter-1.
- **Fix proposal:** EITHER instrument 3 hot paths now (`RaftNode.tick`, `RaftLog.append`, `TcpRaftTransport.send` — each gated by a unique buggify-key string) OR delete the class and remove all docs references. Half-measures (annotation present, runtime present, no call sites) are worse than nothing because they imply coverage that does not exist.
- **Proposed owner:** common (Buggify) + consensus-core or transport

## C-118 — `ChaosScenariosTest.DeterminismGuard` sorts traces by `Object::toString`, hiding ordering bugs
- **Severity:** S3
- **Location:** `configd-testkit/src/test/java/io/configd/testkit/ChaosScenariosTest.java:340`
- **Evidence:** `trace.sort(Comparator.comparing(Object::toString));` — the determinism assertion is therefore on the SET of delivered messages, not the SEQUENCE. A bug that delivers `m1,m2,m3` on one seed and `m1,m3,m2` on another is invisible. The test name promises "same trace" but the comparison is "same multiset."
- **Steps to reproduce:** Inject a non-deterministic ordering (e.g. swap the priority-queue tie-breaker to `random.nextLong()`); the test still passes.
- **Fix proposal:** Drop the `sort` call and assert list equality directly. If the underlying `deliverDue` is not yet deterministic (it is — `(deliverAtMs, sendSequence)` is a total order), then the sort is masking real issues. Add a second test `interleavedSendsAndDeliveriesProduceIdenticalSequences()` that does NOT sort.
- **Proposed owner:** testkit (ChaosScenariosTest)

---

## Summary

| Sev | Count | IDs |
|---|---|---|
| S0 | 1  | C-101 |
| S1 | 5  | C-102, C-103, C-104, C-105, C-106 |
| S2 | 10 | C-107, C-108, C-109, C-110, C-111, C-112, C-113, C-114, C-115, C-116 |
| S3 | 2  | C-117, C-118 |

Reused-from-iter-1 (now restated with current-HEAD evidence): C-104 (was C-009), C-105 (was C-010), C-111 (was C-003), C-112 (was C-013), C-113 (was C-014/C-015), C-114 (extends C-005), C-117 (was C-016).
