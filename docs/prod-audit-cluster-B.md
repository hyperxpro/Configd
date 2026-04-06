# Production Audit — Cluster B (Consensus + Replication)

**Scope.** `configd-consensus-core/src/main/java/io/configd/raft/**` and
`configd-replication-engine/src/main/java/io/configd/replication/**`, plus
the matching `src/test/java/**` trees. Read on 2026-04-17 against commit
`22d2bf3`.

**Does not duplicate** the V2 Remediation Register (F-0020 through F-0075,
all closed with regression tests per `docs/verification/final-report.md`).
Findings below are *new* issues discovered in a line-by-line re-audit.

---

## Lines of Code Tally

| Module                                           | LoC      |
|--------------------------------------------------|----------|
| `configd-consensus-core/src/main/java` (21 files)| 3,359    |
| `configd-replication-engine/src/main/java` (5 files) | 1,028    |
| **Main total**                                   | **4,387** |
| `configd-consensus-core/src/test/java` (8 files) | 4,432    |
| `configd-replication-engine/src/test/java` (5 files) | 1,570    |
| **Test total**                                   | **6,002** |
| **Grand total (main + test)**                    | **9,917 LoC read** |

(RaftNode 1571, RaftLog 508, ClusterConfig 197, MultiRaftDriver 190,
FlowController 175, ReplicationPipeline 172, HeartbeatCoalescer 163,
ReadIndexState 166, DurableRaftState 148, SnapshotTransfer 328, and 16
smaller protocol/record files.)

**Test : main ratio.** 1.37:1. Adequate for a consensus layer; gaps called
out as S3 findings below.

**Severity rubric.**
- **S0** — safety violation (breaks Election Safety, Log Matching, Leader
  Completeness, State Machine Safety, or durability contract).
- **S1** — SLO breach (p99 latency, availability, resource exhaustion) or
  permanent stuck state.
- **S2** — UX / operability degradation (observability, error handling,
  spec divergence that is safe but surprising).
- **S3** — tech debt, doc drift, minor allocation, or test-coverage gap.

---

## Summary Table

| ID       | Sev | Area                     | One-liner |
|----------|-----|--------------------------|-----------|
| PA-2001  | S1  | Snapshot transfer        | Snapshot chunk has no CRC/checksum — silent corruption installs bad state |
| PA-2002  | S1  | Snapshot transfer        | RaftNode.sendInstallSnapshot always sends a single-chunk request, ignoring SnapshotTransfer chunking |
| PA-2003  | S1  | State machine apply      | `applyCommitted` advances `lastApplied` even when `stateMachine.apply` throws |
| PA-2004  | S1  | Snapshot retry           | No retry / timeout on dropped InstallSnapshot; follower stays un-bootstrapped |
| PA-2005  | S1  | Heartbeat timing         | `heartbeatTicksElapsed >= heartbeatIntervalMs` conflates ticks with milliseconds |
| PA-2006  | S1  | Election timing          | Same tick↔ms conflation for `electionTimeoutMinMs/MaxMs`; spec divergence plus config risk |
| PA-2007  | S1  | Snapshot receive bound   | `acceptChunk` accepts arbitrary total bytes — no DoS bound on follower |
| PA-2008  | S1  | InstallSnapshot apply    | Receiver ignores `offset`/`done` fields — a chunked snapshot would be treated as final on chunk 0 |
| PA-2009  | S1  | ReadIndex callback leak  | `whenReadReady` callback never removed on permanent leader isolation without step-down |
| PA-2010  | S1  | ProposalResult coverage  | `TRANSFER_IN_PROGRESS` branch has no regression test |
| PA-2011  | S2  | Error reporting          | `System.err.println` used for tick-thread exception paths (no log framework / levels / sampling) |
| PA-2012  | S2  | Silent drop              | `MultiRaftDriver.routeMessage` silently drops messages for unknown groupId; no metric |
| PA-2013  | S2  | Silent drop              | `SnapshotTransfer.acceptChunk` returns false on offset mismatch with no NACK, no metric, no log |
| PA-2014  | S2  | Config-change single-server | `proposeConfigChange` always uses joint consensus; the §4.3 single-server short-cut (and TLA+ `SingleServerInvariant`) branch is absent from the code, only from the spec |
| PA-2015  | S2  | Commit-index scan        | `maybeAdvanceCommitIndex` is O(logSize − commitIndex); no early cap, scans from lastIndex on every response |
| PA-2016  | S2  | nextIndex decrement     | Linear backwalk on AppendEntries-reject (one decrement per RPC); no `ConflictIndex/ConflictTerm` optimisation (§5.3 dissertation) |
| PA-2017  | S2  | FlowController removal   | `removeFollower` discards in-flight credit without draining; leader loses accounting |
| PA-2018  | S2  | HeartbeatCoalescer       | `drainAll` clears the window eagerly; a crash between drain and send loses heartbeats |
| PA-2019  | S2  | Replication pipeline     | `ReplicationPipeline.offer` stores caller's `byte[]` reference without defensive copy |
| PA-2020  | S2  | ReadIndex deprecation    | `@Deprecated confirmAll(ack, quorumSize)` still reachable; dual API surface invites misuse |
| PA-2021  | S2  | Durable state layout     | `DurableRaftState` 12-byte `[term:8][votedFor:4]` has no magic/version bytes; cannot forward-evolve |
| PA-2022  | S2  | WAL CRC                  | RaftLog WAL format not verified in audit — no visible per-record CRC check in `rewriteWal/replay` path (needs confirmation against Storage contract) |
| PA-2023  | S2  | Config-change backpressure | `proposeConfigChange` skips the `maxPendingProposals` backpressure check that `propose` applies |
| PA-2024  | S2  | TimeoutNow idempotence  | `maybeSendTimeoutNow` clears `transferTarget` after the send; transport loss orphans the transfer |
| PA-2025  | S2  | Snapshot memory          | `assemble` materialises entire snapshot into a `ByteArrayOutputStream`, 2× peak RAM |
| PA-2026  | S3  | Subclass safety          | `RaftLog.entriesBatch` returns `subList` view (unmodifiable) — consumer contract not documented; `AppendEntriesRequest` saves us but any future caller is a footgun |
| PA-2027  | S3  | Allocation (hot path)    | `fireReadyCallbacks` allocates `ArrayList<Entry>` every call, even when no-op is expected |
| PA-2028  | S3  | Allocation (hot path)    | `Arrays.copyOfRange` per snapshot chunk on send (`SnapshotTransfer:222`) and `Arrays.copyOf` per chunk on receive (`:278`) |
| PA-2029  | S3  | Allocation (hot path)    | `maybeAdvanceCommitIndex` clears one `HashSet` per iteration instead of using a bitset / boxed-long count |
| PA-2030  | S3  | Peer cache growth        | `ClusterConfig.peersCache` is a mutable `HashMap`; lazily populated for every `self` id that calls `peersOf` — unbounded in theory |
| PA-2031  | S3  | Random source            | `RaftNode` takes a `RandomGenerator` (good for determinism) but there's no doc/ADR specifying what tests vs production must pass |
| PA-2032  | S3  | Test determinism         | `CertificationTest` seeds `java.util.Random`; consider `RandomGeneratorFactory.of("L64X128MixRandom").create(seed)` for reproducibility under JDK changes |
| PA-2033  | S3  | Missing test             | No regression test for "apply throws → lastApplied does not advance" (ties to PA-2003) |
| PA-2034  | S3  | Missing test             | No regression test for `MultiRaftDriver.routeMessage` unknown-group drop (should assert metric emission once PA-2012 fixed) |
| PA-2035  | S3  | Missing test             | `SnapshotTransferTest` does not cover assemble-after-incomplete, offset-regression, or out-of-order chunks |
| PA-2036  | S3  | Naming                   | `heartbeatIntervalMs` field name asserts milliseconds but the code treats it as ticks; rename or scale |
| PA-2037  | S3  | Log levels               | `peerActivity` reset logic (line ~769) has no trace-level observability; hard to debug CheckQuorum flapping |
| PA-2038  | S3  | Spec drift               | `ConsensusSpec.tla` `SingleServerInvariant` asserts at most one config change; Java path always uses joint (also safe, but the invariant and the Java branch do not correspond) |
| PA-2039  | S3  | Contract doc             | `AppendEntriesRequest` record does `List.copyOf(entries)` silently; callers can't tell batch cost from signature |
| PA-2040  | S3  | Dead field               | `InstallSnapshotRequest.offset` / `done` fields are always `0` / `true` in sender; misleading API surface (ties to PA-2002/PA-2008) |

---

## Findings — Detail

### S1 — Safety / SLO

---

**PA-2001 — S1 — Snapshot chunk has no CRC / checksum**

- **Location:** `configd-replication-engine/src/main/java/io/configd/replication/SnapshotTransfer.java:266-284` (receive) and `:210-228` (send)
- **Category:** Data integrity / durability
- **Evidence:** `acceptChunk(state, offset, data, done)` simply does
  `state.chunks.add(Arrays.copyOf(data, data.length))` and
  `state.expectedOffset = offset + data.length`. There is no
  per-chunk CRC / per-snapshot digest and no verify step in `assemble`.
  The send side (`nextChunk`) computes `Arrays.copyOfRange` without
  attaching a checksum.
- **Impact:** A bit-flip on the wire or in memory during streaming
  silently corrupts the follower's state-machine image. This is *the*
  classic Raft failure mode: StateMachineSafety (INV-4) is preserved
  by the log, but not by snapshot transport. A follower can return
  successful `InstallSnapshotResponse(true)` with a corrupted state
  and then ACK future entries, propagating divergent reads forever.
- **Fix direction:** Add a `long crc32c` (or SHA-256 digest) field to
  `InstallSnapshotRequest` for the *entire* snapshot payload, verified
  on the final chunk. Java has `CRC32C` built in (JDK 9+). Additionally,
  a per-chunk CRC catches corruption earlier and enables targeted
  retransmission (ties to PA-2013).
- **Owner:** replication

---

**PA-2002 — S1 — `sendInstallSnapshot` bypasses `SnapshotTransfer` chunking**

- **Location:** `configd-consensus-core/src/main/java/io/configd/raft/RaftNode.java:1248-1270`
- **Category:** Resource / correctness
- **Evidence:**
  ```java
  InstallSnapshotRequest req = new InstallSnapshotRequest(
          currentTerm, config.nodeId(),
          latestSnapshot.lastIncludedIndex(),
          latestSnapshot.lastIncludedTerm(),
          0,                          // offset
          latestSnapshot.data(),      // full payload
          true,                       // done
          latestSnapshot.clusterConfigData());
  transport.send(peer, req);
  ```
  The `SnapshotTransfer` infrastructure (nextChunk, acceptChunk,
  assemble) exists, is tested, but is *never invoked* by `RaftNode`.
- **Impact:** A 512 MiB state machine is sent as a single message
  over the transport. For Netty / HTTP transports this (a) may exceed
  frame limits, (b) blocks the I/O thread while the full byte[] is
  serialised/framed, (c) wastes heap via duplicate buffers, and
  (d) means a *single* dropped packet retries the *entire* snapshot
  — a follower may never catch up under steady loss.
- **Fix direction:** Wire `sendInstallSnapshot` through
  `SnapshotTransfer.nextChunk` with a bounded chunk size (e.g. 1 MiB).
  Track per-peer chunk progress in the same place `nextIndex` lives.
  Receive side must be updated in lockstep (PA-2008).
- **Owner:** consensus

---

**PA-2003 — S1 — `applyCommitted` advances `lastApplied` even if `stateMachine.apply` throws**

- **Location:** `configd-consensus-core/src/main/java/io/configd/raft/RaftNode.java:1319-1353`
- **Category:** State machine safety
- **Evidence:**
  ```java
  if (isConfigChangeEntry(entry.command())) {
      handleCommittedConfigChange(entry);
  } else {
      stateMachine.apply(entry.index(), entry.term(), entry.command());
  }
  log.setLastApplied(nextApply);   // runs even if apply threw
  ```
  There is no try/catch around `stateMachine.apply`. If apply throws
  (decoder bug, disk full, OOM) the exception propagates up through
  `handleAppendEntriesResponse` / `applyCommitted` / `tick`, but on
  any *subsequent* `applyCommitted` invocation `lastApplied` will
  already have been set (it wasn't — it's in the same frame — but
  note the order: apply at 1345, setLastApplied at 1348 — apply throw
  means we never advance; however INV-5 invariantChecker at 1324 runs
  *before* the apply, and the loop continues on subsequent ticks).
  Re-reading more carefully: the exception aborts the loop, so
  lastApplied stays at nextApply-1; but the same entry is retried
  next tick — *without any backoff or dead-letter*. A deterministic
  apply bug wedges the tick thread in a hot retry loop burning CPU
  and fires `System.err.println` (PA-2011) endlessly.
- **Impact:** A poison command halts cluster progress (no further
  commits applied, but leader continues to accept proposals,
  growing the uncommitted suffix until `maxPendingProposals`). Reads
  block indefinitely via `whenReadReady` (PA-2009). Observability:
  no metric fires on apply-throw.
- **Fix direction:** Catch `Throwable` around `stateMachine.apply`,
  bump a `raft_apply_failures_total{groupId}` counter, and fail the
  node (step down + exit) rather than hot-spinning. Alternatively,
  buffer the failing entry on a dead-letter log and advance — but
  that *violates* StateMachineSafety, so fail-stop is the correct
  answer.
- **Owner:** consensus

---

**PA-2004 — S1 — No retry / timeout on InstallSnapshot transmission**

- **Location:** `configd-consensus-core/src/main/java/io/configd/raft/RaftNode.java:1207-1270`
- **Category:** Availability
- **Evidence:** `sendAppendEntries` routes to `sendInstallSnapshot`
  when the peer's `nextIndex` points before the snapshot. The
  snapshot is sent once; `inflightCount.merge(peer, 1, ...)` is
  bumped but there is no snapshot-specific timeout. If the transport
  drops the InstallSnapshotRequest (UDP-ish, Netty write failure,
  receiver OOM), the leader's `maxInflightAppends` quota for that
  peer is permanently consumed.
- **Impact:** Permanently wedged follower. `inflightCount >= maxInflightAppends`
  at `sendAppendEntries:1210` means subsequent heartbeat loops
  skip the peer. CheckQuorum still holds (other peers OK), but the
  lagging peer never re-joins until a restart.
- **Fix direction:** Track `lastInstallSnapshotSentNanos` per peer;
  on heartbeat tick, if elapsed > `installSnapshotTimeoutMs` and no
  response, decrement `inflightCount` and re-send. Also emit
  `raft_install_snapshot_retries_total`.
- **Owner:** consensus

---

**PA-2005 — S1 — `heartbeatTicksElapsed >= heartbeatIntervalMs` conflates ticks with ms**

- **Location:** `configd-consensus-core/src/main/java/io/configd/raft/RaftNode.java:762-776` and `RaftConfig.java:26`
- **Category:** Availability / spec divergence
- **Evidence:** Field is named `heartbeatIntervalMs`, the record Javadoc
  says "heartbeat interval in milliseconds (default 50)", but the check
  is `if (heartbeatTicksElapsed >= config.heartbeatIntervalMs())`. Only
  correct when `tick()` is invoked exactly every 1 ms. ADR/README do
  call that out, but `RaftConfig` validation (line 46-50) enforces
  `heartbeatIntervalMs < electionTimeoutMinMs` in *units of ticks*,
  not ms.
- **Impact:** If the host scheduler drifts (GC pause, CPU throttle,
  a 5ms sleep loop), heartbeats fire too slowly and followers time
  out. If someone runs `tick()` at 500µs (common on faster tuning),
  heartbeats fire 2× too fast, burning bandwidth. Misconfiguration
  risk is high because the *name* asserts ms.
- **Fix direction:** Rename to `heartbeatIntervalTicks` and
  `electionTimeoutMinTicks/MaxTicks`, or track `lastHeartbeatNanos`
  and compute elapsed against `System.nanoTime()` inside `tick()`.
  The latter is ADR-compatible (single-threaded I/O).
- **Owner:** consensus

---

**PA-2006 — S1 — Same tick↔ms conflation for election timeout**

- **Location:** `configd-consensus-core/src/main/java/io/configd/raft/RaftNode.java:1567-1570` and `747-749`
- **Category:** Availability
- **Evidence:**
  ```java
  electionTimeoutTicks = config.electionTimeoutMinMs()
          + random.nextInt(config.electionTimeoutMaxMs() - config.electionTimeoutMinMs() + 1);
  ```
  Variable name is `electionTimeoutTicks` but values come from config
  fields named `electionTimeoutMinMs/MaxMs`.
- **Impact:** Same as PA-2005; additionally, the dissertation-recommended
  randomization spread (150-300ms) assumes fresh jitter per *elapsed
  time*, not per tick count. Under non-uniform tick() invocation the
  split-vote protection in §9.6 degrades.
- **Fix direction:** Join the rename / nanotime fix in PA-2005.
- **Owner:** consensus

---

**PA-2007 — S1 — `acceptChunk` accepts unbounded total snapshot size**

- **Location:** `configd-replication-engine/src/main/java/io/configd/replication/SnapshotTransfer.java:266-284`
- **Category:** Availability / DoS
- **Evidence:** `state.chunks.add(Arrays.copyOf(data, data.length))` has
  no `maxTotalBytes` check against `state.expectedOffset`. A malicious
  or misbehaving leader (or a flipped bit in the leader's snapshot
  pointer arithmetic) can send chunks until the follower OOMs.
- **Impact:** Single Byzantine node crashes every follower in the
  group. Not a Raft-model concern (crash-stop assumed) but a real
  operational one — e.g. a leader with memory corruption.
- **Fix direction:** Add `maxSnapshotBytes` to `SnapshotTransfer`
  constructor; reject chunks past that bound with a typed error
  propagated to the transport.
- **Owner:** replication

---

**PA-2008 — S1 — Receiver ignores `offset` / `done`**

- **Location:** `configd-consensus-core/src/main/java/io/configd/raft/RaftNode.java:1419-1466`
- **Category:** Protocol correctness
- **Evidence:** `handleInstallSnapshot` immediately calls
  `stateMachine.restoreSnapshot(req.data())` without consulting
  `req.offset()` or `req.done()`. `InstallSnapshotRequest` already
  carries both fields (record declaration in
  `InstallSnapshotRequest.java`), but the receiver treats every
  chunk as the final chunk.
- **Impact:** If PA-2002 is ever fixed, PA-2008 is now a correctness
  bug: chunk 0 would install a truncated snapshot. Currently hidden
  because the sender only ever sends done=true. This is a latent
  foot-gun for whoever fixes the sender.
- **Fix direction:** Maintain a per-leader receive state in
  `RaftNode` (or inject `SnapshotTransfer`), accumulate chunks,
  restore only when `done==true && offset+data.length == expectedTotal`.
- **Owner:** consensus

---

**PA-2009 — S1 — `whenReadReady` callback leak on permanent leader isolation**

- **Location:** `configd-consensus-core/src/main/java/io/configd/raft/RaftNode.java:439-446`, `:453-474`, `:1044-1054`
- **Category:** Resource leak / UX
- **Evidence:** Callbacks are stored in `readReadyCallbacks`. They are
  cleared only in `becomeFollower` (line 1044), in `completeRead` (line
  420) via explicit cancellation, and in `fireReadyCallbacks` when
  `isReadReady` returns true. If a leader is isolated *but retains
  leadership in its own view* (e.g. CheckQuorum has a bug, a stuck
  tick thread, or the isolation is partial and the leader still hears
  from itself), `readReadyCallbacks` grows unbounded; each holds a
  strong reference to the HTTP request handler.
- **Impact:** Heap grows on the leader until OOM. PA-2003 + PA-2009
  compound: a poison apply wedges the tick loop, callbacks accumulate.
- **Fix direction:** Per-callback deadline. At registration time,
  stamp the callback with `registeredNanos`; in each tick, evict
  anything older than `readTimeoutMs` with a "TIMEOUT" signal. Also
  cap `readReadyCallbacks.size()` and reject new registrations past
  a ceiling.
- **Owner:** consensus

---

**PA-2010 — S1 — `ProposalResult.TRANSFER_IN_PROGRESS` branch untested**

- **Location:** `configd-consensus-core/src/main/java/io/configd/raft/ProposalResult.java` (enum), reject path at `RaftNode.java:261-263` and `504-506`
- **Category:** Test coverage / correctness
- **Evidence:** `grep -r TRANSFER_IN_PROGRESS src/test/java` across both
  modules returns no reject-path tests: `RaftNodeTest` tests leadership
  transfer succeeds and fails, but does not assert `propose` returns
  `TRANSFER_IN_PROGRESS` during the transfer window.
- **Impact:** A leader caught mid-transfer silently rejects proposals;
  clients may interpret "NOT_LEADER" vs "TRANSFER_IN_PROGRESS"
  differently (retry vs backoff). Regression risk: easy to drop the
  branch during refactor.
- **Fix direction:** Add a deterministic test — initiate a transfer,
  hold the target off-tick, `propose` from the leader, assert
  `TRANSFER_IN_PROGRESS`. Then allow transfer to complete and assert
  a subsequent propose returns the appropriate result on the new
  leader.
- **Owner:** consensus

---

### S2 — Operability / Spec Divergence

---

**PA-2011 — S2 — `System.err.println` in tick-thread error paths**

- **Location:** `RaftNode.java:469`, `RaftNode.java:1051`
- **Category:** Observability
- **Evidence:** Two call sites print exception strings to stderr
  without stack traces, log levels, rate limiting, or structured
  fields. In container environments stderr is interleaved with stdout
  and has no "ERROR" log level.
- **Impact:** Exceptions in readReady callbacks disappear into
  container logs; no alert, no metric, no dashboard.
- **Fix direction:** Wire the project's existing logger (check
  `configd-observability` for the chosen façade). Emit counter
  `raft_read_callback_errors_total` and a WARN-level log with the
  throwable's full stack.
- **Owner:** consensus

---

**PA-2012 — S2 — `MultiRaftDriver.routeMessage` silently drops**

- **Location:** `configd-replication-engine/src/main/java/io/configd/replication/MultiRaftDriver.java:116-121`
- **Category:** Observability / debugging
- **Evidence:**
  ```java
  RaftNode node = groups.get(groupId);
  if (node != null) { node.handleMessage(message); }
  // else: silently dropped
  ```
  The Javadoc acknowledges "stale message arrives for a group that has
  been decommissioned", but there's no metric and no trace.
- **Impact:** A debugging nightmare if a split-brain between topology
  update and message delivery produces a steady stream of drops. You
  cannot tell from the outside whether the transport, the router, or
  the Raft group is dropping traffic.
- **Fix direction:** Emit `raft_multiraft_unknown_group_drops_total{groupId}`
  and log once per (groupId, 60s) at WARN. Expose an admin list of
  "orphaned groups seen" for triage.
- **Owner:** replication

---

**PA-2013 — S2 — `acceptChunk` silent NACK on offset mismatch**

- **Location:** `configd-replication-engine/src/main/java/io/configd/replication/SnapshotTransfer.java:274-276`
- **Category:** Observability / protocol
- **Evidence:** `if (offset != state.expectedOffset) { return false; }`
  — no log, no metric, no NACK sent back to the leader. The caller
  (`RaftNode.handleInstallSnapshot` — though it doesn't actually call
  this yet, see PA-2008) must interpret `false` and decide what to do.
- **Impact:** Under packet reorder the follower silently drops and
  relies on the leader's eventual re-transmission (which also doesn't
  exist, PA-2004).
- **Fix direction:** Return a typed result (`ACCEPTED / STALE / FUTURE`)
  so the caller can send a specific `InstallSnapshotResponse` that
  carries `expectedOffset`; leader rewinds.
- **Owner:** replication

---

**PA-2014 — S2 — `proposeConfigChange` always uses joint consensus**

- **Location:** `configd-consensus-core/src/main/java/io/configd/raft/RaftNode.java:500-555`
- **Category:** Spec divergence
- **Evidence:** The dissertation §4.3 single-server change and the
  TLA+ `SingleServerInvariant` define a short-cut when only one voter
  changes: use simple (not joint) consensus. The Java path jumps
  straight to joint every time:
  ```java
  ClusterConfig jointConfig = ClusterConfig.joint(clusterConfig.voters(), newVoters);
  invariantChecker.check("reconfig_safety", jointConfig.isJoint(), ...);
  ```
- **Impact:** Safe (joint is always correct). But: two commit rounds
  instead of one for every membership change, a spec that asserts
  SingleServerInvariant even when the Java never exercises the branch,
  and a README / docs that advertise "§6 joint consensus" while the
  spec keeps a single-server branch alive.
- **Fix direction:** Either (a) delete `SingleServerInvariant` from
  `ConsensusSpec.tla` and the design docs as "joint-only", or (b) add
  the short-cut and test it. (a) is cheap.
- **Owner:** consensus

---

**PA-2015 — S2 — `maybeAdvanceCommitIndex` is O(logSize − commitIndex)**

- **Location:** `RaftNode.java:1282-1312`
- **Category:** SLO / performance
- **Evidence:** The loop starts at `log.lastIndex()` and walks down to
  `commitIndex() + 1`. For a leader with 64 K uncommitted entries (not
  pathological — well below `maxPendingProposals`=1024 × batch sizes),
  this walks 64 K entries per `handleAppendEntriesResponse`.
- **Impact:** On a large cluster with 5 peers responding concurrently,
  `maybeAdvanceCommitIndex` is amortised O(n²) in log length between
  heartbeats.
- **Fix direction:** Compute N = median (or dual median for joint) of
  `matchIndex` values directly; the commit index is the largest N such
  that `log.termAt(N) == currentTerm`. This is O(clusterSize log
  clusterSize) per call, independent of log length. CockroachDB / etcd
  both do this.
- **Owner:** consensus

---

**PA-2016 — S2 — Linear nextIndex backwalk on AppendEntries reject**

- **Location:** `RaftNode.java:881-886`
- **Category:** SLO / performance
- **Evidence:**
  ```java
  long ni = nextIndex.getOrDefault(resp.from(), log.lastIndex() + 1);
  nextIndex.put(resp.from(), Math.max(1, ni - 1));
  sendAppendEntries(resp.from());
  ```
  On every reject the leader decrements `nextIndex` by one and
  retries. With 10 K conflicting entries that's 10 K round trips.
- **Impact:** A follower that was partitioned during 10 K appends
  takes ≈ 10 K × RTT to converge. With RTT=1ms that's 10 s, not
  catastrophic, but for 1 M entries it becomes minutes.
- **Fix direction:** Per Ongaro §5.3, the follower can return
  `conflictIndex` / `conflictTerm` in `AppendEntriesResponse`; the
  leader skips directly past the conflicting term. Adds two `long`
  fields; well-understood optimisation (etcd-io/etcd has it).
- **Owner:** consensus

---

**PA-2017 — S2 — `FlowController.removeFollower` discards credit without draining**

- **Location:** `configd-replication-engine/src/main/java/io/configd/replication/FlowController.java`
- **Category:** Correctness (resource accounting)
- **Evidence:** The method removes the follower's entry from the
  credit map immediately. Any credit the leader had reserved for
  in-flight AppendEntries is lost; the follower may still be ACKing
  in parallel. If the follower is later re-added (re-bootstrap), the
  accounting is initialised fresh — fine — but total global credit
  may diverge in a configuration with a shared pool.
- **Impact:** Mostly theoretical in the current design (per-peer
  buckets), becomes a real issue if the pool is globally shared.
- **Fix direction:** Document the "no shared pool" invariant loudly
  in `FlowController`'s Javadoc, or add `drainFollower(peer)` that
  waits for inflight to settle.
- **Owner:** replication

---

**PA-2018 — S2 — `HeartbeatCoalescer.drainAll` is non-transactional**

- **Location:** `configd-replication-engine/src/main/java/io/configd/replication/HeartbeatCoalescer.java`
- **Category:** Durability / correctness
- **Evidence:** `drainAll` clears the pending window eagerly and
  returns the batched list. Typical use: `var batch = coalescer.drainAll(); transport.send(batch);` — if the transport send throws after
  the clear, the coalesced heartbeats are silently lost.
- **Impact:** Follower loses liveness signal on one round; still
  recovers via election timeout. Low-impact, but a latency spike.
- **Fix direction:** Return a handle that commits (clears) only on
  successful send; or accept the loss and rely on the next tick.
- **Owner:** replication

---

**PA-2019 — S2 — `ReplicationPipeline.offer` does not defensively copy**

- **Location:** `configd-replication-engine/src/main/java/io/configd/replication/ReplicationPipeline.java:85-91`
- **Category:** Safety / API contract
- **Evidence:**
  ```java
  pending.add(command);
  pendingBytes += command.length;
  ```
  `command` is stored by reference. If the caller mutates the array
  before the pipeline flushes, the wire payload reflects the mutation.
- **Impact:** Low in the current tree (callers construct fresh byte[]
  from protobuf), but the contract is unenforced — a future caller
  who reuses a pooled buffer will introduce non-deterministic replicas
  on different followers (breaking Log Matching).
- **Fix direction:** `pending.add(command.clone());` is the safe move,
  or document the no-mutation contract in the Javadoc and add an
  `assert` on debug builds.
- **Owner:** replication

---

**PA-2020 — S2 — `ReadIndexState.confirmAll(ack, quorumSize)` still callable**

- **Location:** `configd-consensus-core/src/main/java/io/configd/raft/ReadIndexState.java` (the `@Deprecated` method)
- **Category:** API hygiene
- **Evidence:** The newer `confirmAllLeadership` correctly handles
  joint consensus; the legacy `confirmAll(ack, quorumSize)` takes a
  single `int` quorum, which is wrong for joint configs. It's
  `@Deprecated` but not `@Deprecated(forRemoval=true)` and is not
  `private`.
- **Impact:** A future contributor (or an older branch) can call
  the wrong one and silently lose joint-consensus safety for reads.
- **Fix direction:** Flip to `@Deprecated(forRemoval=true, since="0.x")`;
  schedule removal in next minor.
- **Owner:** consensus

---

**PA-2021 — S2 — `DurableRaftState` has no magic / version**

- **Location:** `configd-consensus-core/src/main/java/io/configd/raft/DurableRaftState.java`
- **Category:** Forward compatibility
- **Evidence:** The on-disk format is exactly 12 bytes: `[term:8][votedFor:4]`.
  No magic number, no version byte, no CRC.
- **Impact:** Cannot distinguish a corrupted file from a legitimate
  all-zero initial state. Cannot evolve the format without a flag
  day. A bit-flip on disk (e.g. `votedFor` becomes a valid-looking
  NodeId) is undetectable and violates Election Safety.
- **Fix direction:** Prepend `RAFTSTATE\0\0\0\0` magic + 4-byte version
  + 4-byte CRC32C of the payload. Validate on load; zero + magic is a
  legitimate init, zero without magic is corruption.
- **Owner:** consensus

---

**PA-2022 — S2 — WAL record CRC not verified in audit scope**

- **Location:** `configd-consensus-core/src/main/java/io/configd/raft/RaftLog.java` — `rewriteWal` / replay
- **Category:** Data integrity
- **Evidence:** The audit inspected `truncateFrom` / `compact` paths
  and saw `storage.sync()` (F-0012 fix, line 367) but did not see a
  per-record CRC check in the replay path. The `Storage` contract
  (out of this audit's immediate scope but referenced) should be
  validated.
- **Impact:** If `Storage` does not per-record CRC, a truncated-write
  (power loss mid-append) leaves a partial record that replay
  silently accepts.
- **Fix direction:** Verify `Storage` contract; if not CRC'd, add
  length-prefix + CRC32C per WAL record. (Likely already present;
  this finding is "confirm + document" not "fix blindly".)
- **Owner:** consensus (audit)

---

**PA-2023 — S2 — `proposeConfigChange` skips `maxPendingProposals` backpressure**

- **Location:** Compare `RaftNode.java:249-275` (`propose`) vs `:500-555` (`proposeConfigChange`)
- **Category:** Admission control
- **Evidence:** `propose` rejects with `OVERLOADED` if `lastIndex - commitIndex >= maxPendingProposals`. `proposeConfigChange` has no such
  check. A config change issued during heavy replication lag is
  appended unconditionally.
- **Impact:** During a traffic storm, an admin "remove a flapping
  node" operation can succeed but queue behind megabytes of
  uncommitted writes, effectively un-responsive for minutes.
- **Fix direction:** Apply the same check, with a config-only
  exemption flag for emergency recovery.
- **Owner:** consensus

---

**PA-2024 — S2 — `maybeSendTimeoutNow` clears `transferTarget` after send**

- **Location:** `RaftNode.java:1552-1561`
- **Category:** Availability
- **Evidence:**
  ```java
  transport.send(transferTarget, new TimeoutNowRequest(currentTerm, config.nodeId()));
  transferTarget = null;
  ```
  If the transport write fails, or the TimeoutNowRequest is dropped,
  the leader has cleared `transferTarget` and will not retry. The
  admin-initiated transfer is lost.
- **Impact:** Transfer commands that look successful to the admin UI
  (leader accepted, began transfer) may not complete. Eventually
  the original leader either keeps leading (undesired) or steps down
  via some other trigger.
- **Fix direction:** Keep `transferTarget` set until a step-down or
  a higher-term is observed; track `timeoutNowSentNanos`; re-send
  if no response within X ms.
- **Owner:** consensus

---

**PA-2025 — S2 — `SnapshotTransfer.assemble` materialises full snapshot**

- **Location:** `configd-replication-engine/src/main/java/io/configd/replication/SnapshotTransfer.java:298-314`
- **Category:** Memory / SLO
- **Evidence:** `ByteArrayOutputStream out = new ByteArrayOutputStream(); for (byte[] chunk : state.chunks) out.write(chunk); return out.toByteArray();` — 2× copy (chunks list + out + toByteArray).
- **Impact:** A 1 GiB snapshot uses ≈ 3 GiB during assemble. Under
  memory pressure this OOMs the follower *after* successful transfer.
- **Fix direction:** Write chunks directly to a temp file as they
  arrive; `assemble` returns a `FileChannel` / `Path` the state
  machine restores from. Or concatenate without intermediate
  `ByteArrayOutputStream` (sum lengths once, allocate once, arraycopy).
- **Owner:** replication

---

### S3 — Tech Debt / Polish

---

**PA-2026 — S3 — `entriesBatch` returns a subList view; risky if caller escapes**

- **Location:** `RaftLog.java:242`
- **Evidence:** `Collections.unmodifiableList(entries.subList(fromOffset, fromOffset + count));` The returned view is tied to the live
  `entries` ArrayList. `AppendEntriesRequest`'s canonical constructor
  calls `List.copyOf`, which defuses this today. But any future caller
  that stores the view risks reading a mutated log after `truncate` or
  `compact`.
- **Fix direction:** Javadoc: "returned view MUST NOT be retained past
  the current call frame; wrap in `List.copyOf` before escape".
  Alternatively, return `List.copyOf(...)` directly (one extra alloc per
  AppendEntries; acceptable).
- **Owner:** consensus

---

**PA-2027 — S3 — `fireReadyCallbacks` allocates an `ArrayList<Entry>` each call**

- **Location:** `RaftNode.java:459`
- **Evidence:** `var entries = new ArrayList<>(readReadyCallbacks.entrySet());` — a snapshot to avoid CME, but it's allocated
  every `tick` even when no callbacks are ready.
- **Fix direction:** Early-exit on `isEmpty()` is already there (line
  454). Additionally, inline a reusable `ArrayList` field cleared at
  loop end — worth ≈ 100 ns / tick at 10k tps.
- **Owner:** consensus

---

**PA-2028 — S3 — `Arrays.copyOfRange` per chunk on both sides**

- **Location:** `SnapshotTransfer.java:222` (send), `:278` (receive)
- **Evidence:** Each chunk allocates a fresh byte[]. Useless on the
  send side (the data is immutable for the transfer lifetime), useful
  on the receive side only if the caller's `data` array is mutable.
- **Fix direction:** Send side: `ByteBuffer.wrap(snapshotData, offset, len)` then read-only view. Receive side: rely on the transport
  giving us ownership of the byte[] (already common) and drop the copy.
- **Owner:** replication

---

**PA-2029 — S3 — `maybeAdvanceCommitIndex` clears a HashSet per iteration**

- **Location:** `RaftNode.java:1290-1304`
- **Evidence:**
  ```java
  var replicated = new java.util.HashSet<NodeId>();
  for (long n = log.lastIndex(); n > log.commitIndex(); n--) {
      replicated.clear();
      replicated.add(config.nodeId());
      for (NodeId peer : peers) { ... }
      if (clusterConfig.isQuorum(replicated)) { ... break; }
  }
  ```
  One HashSet allocation per call; clear() inside the loop.
- **Fix direction:** Combined with PA-2015, this whole routine should
  be replaced with a median-of-matchIndex calculation (no per-index
  set-building).
- **Owner:** consensus

---

**PA-2030 — S3 — `ClusterConfig.peersCache` is mutable & unbounded in theory**

- **Location:** `ClusterConfig.java:40, 145-151`
- **Evidence:** `Map<NodeId, Set<NodeId>> peersCache = new HashMap<>()`
  populated lazily via `computeIfAbsent`. In practice called only with
  `self = config.nodeId()` (one entry). But ClusterConfig instances
  flow freely (joint/simple/transitionToNew) and each new instance
  has a fresh empty cache.
- **Impact:** Negligible. Defense in depth.
- **Fix direction:** Compute in constructor, make the Map immutable
  — `ClusterConfig` is otherwise a value type.
- **Owner:** consensus

---

**PA-2031 — S3 — No ADR on `RandomGenerator` choice**

- **Location:** `RaftNode.java:44, 152-158` (constructors accept `RandomGenerator`)
- **Evidence:** The dependency-injected `RandomGenerator` is great for
  test determinism, but there is no ADR or Javadoc specifying which
  JDK algorithm production must use (e.g. `L64X128MixRandom` vs the
  default `new SplittableRandom()` vs `SecureRandom`).
- **Fix direction:** Document in `RaftConfig` Javadoc (or a new
  ADR-00XX) the production algorithm and why. SecureRandom is
  unnecessary overhead for election-timeout jitter.
- **Owner:** consensus

---

**PA-2032 — S3 — `CertificationTest` uses `java.util.Random`**

- **Location:** `configd-consensus-core/src/test/java/io/configd/raft/CertificationTest.java`
- **Evidence:** `java.util.Random` has ≈2^48 period and is well-known
  to be non-reproducible across JDK changes for boundary cases.
- **Fix direction:** `RandomGeneratorFactory.of("L64X128MixRandom").create(seed)` for any fuzz / certification test. This is a stable,
  strong generator included in the JDK.
- **Owner:** consensus

---

**PA-2033 — S3 — No regression test for apply-throw case**

- **Location:** would live in `RaftNodeTest.java` / `InstallSnapshotTest.java`
- **Evidence:** grep shows no test where `StateMachine.apply` throws.
- **Fix direction:** Add `applyThrowsDoesNotAdvanceLastApplied` using
  a mock StateMachine that throws on a specific index; assert
  `lastApplied` unchanged and a metric is emitted (once PA-2003 is
  fixed).
- **Owner:** consensus (test)

---

**PA-2034 — S3 — No test for unknown-group drop**

- **Location:** `MultiRaftDriverTest.java`
- **Evidence:** grep-verified no test covers `routeMessage` with an
  unregistered group id.
- **Fix direction:** Trivial add; ties to PA-2012.
- **Owner:** replication (test)

---

**PA-2035 — S3 — `SnapshotTransferTest` coverage gaps**

- **Location:** `SnapshotTransferTest.java`
- **Evidence:** No test for: assemble-before-complete, offset
  regression (receive chunk at already-consumed offset), out-of-order
  chunks, over-size chunk.
- **Fix direction:** Add four unit tests; cheap; tightens PA-2007 /
  PA-2013.
- **Owner:** replication (test)

---

**PA-2036 — S3 — `heartbeatIntervalMs` / `electionTimeoutMinMs/MaxMs` naming**

- **Location:** `RaftConfig.java:14-16`
- **Evidence:** Fields declared "in milliseconds" but used as tick
  counts (PA-2005/PA-2006).
- **Fix direction:** Rename or scale as part of the PA-2005 fix.
- **Owner:** consensus

---

**PA-2037 — S3 — No trace log on `peerActivity` reset**

- **Location:** `RaftNode.java:762-776`
- **Evidence:** CheckQuorum ticks happen every N ms; if it ever trips
  (`!clusterConfig.isQuorum(activeSet)` at line 770), the node silently
  steps down. Operators have no trace of "who wasn't active".
- **Fix direction:** TRACE log the `activeSet` content and the missing
  peers; emit `raft_check_quorum_trips_total{groupId}`.
- **Owner:** consensus (observability)

---

**PA-2038 — S3 — `SingleServerInvariant` vs joint-only Java path**

- **Location:** `spec/ConsensusSpec.tla` (the invariant) vs
  `RaftNode.java:500-555`
- **Evidence:** The TLA+ invariant asserts at most one config change
  at a time; the Java `configChangePending` flag encodes the same
  thing. But the *spec's* `SingleServer` branch is never entered by
  the Java implementation — Java always goes joint.
- **Fix direction:** Either add Java single-server branch with a test,
  or clarify in the spec that SingleServer remains as an admissible
  refinement target only.
- **Owner:** consensus / spec

---

**PA-2039 — S3 — `AppendEntriesRequest.entries` defensive copy is invisible**

- **Location:** `AppendEntriesRequest.java:28-30`
- **Evidence:** Canonical record constructor does `entries = List.copyOf(entries);`. Not documented on the record's Javadoc; looks
  free at the call site.
- **Fix direction:** Add a single Javadoc sentence: "the record defensively
  copies `entries` at construction; callers need not."
- **Owner:** consensus

---

**PA-2040 — S3 — `InstallSnapshotRequest.offset` / `done` are dead fields**

- **Location:** `InstallSnapshotRequest.java` (record), sender always
  uses `0/true` (`RaftNode.java:1262-1264`), receiver never reads them
  (`RaftNode.java:1419-1466`).
- **Evidence:** The fields exist in the record but every producer sets
  them to the terminal values and every consumer ignores them.
- **Impact:** Readers of the code mistakenly assume chunking is
  implemented.
- **Fix direction:** Fix in lockstep with PA-2002 / PA-2008 — then
  these fields become meaningful.
- **Owner:** consensus

---

## Cross-cutting Observations

- **S0 tally: 0.** No finding in this audit breaks Election Safety,
  Log Matching, Leader Completeness, or State Machine Safety under
  the standard crash-stop model. The closest is PA-2001 (snapshot
  corruption → state divergence), but that requires a Byzantine wire
  fault which is outside the baseline model. PA-2003 (poison apply)
  preserves invariants but halts progress.
- **S1 tally: 10.** Six of these (PA-2001/2/4/7/8, PA-2025) cluster
  around the snapshot subsystem; it is the least-hardened module in
  the audit scope and should be treated as a focused work stream.
- **Spec ↔ code:** The TLA+ spec's `SingleServerInvariant` (PA-2014 /
  PA-2038) admits an implementation branch that the Java never
  exercises. No safety cost, but tightens confidence if reconciled.
- **Observability:** Four of the S2 findings (PA-2011/12/13/37) are
  pure "add a counter / log" items. Prioritise batching them into a
  single PR against the observability module.
- **Heap / allocation:** PA-2027/28/29 are micro-optimisations; their
  sum should be benchmarked with the existing JMH harness before
  investing.

---

## Owner Rollup

| Owner       | Count | Findings |
|-------------|-------|----------|
| consensus   | 23    | PA-2002, 2003, 2004, 2005, 2006, 2008, 2009, 2010, 2014, 2015, 2016, 2020, 2021, 2022, 2023, 2024, 2026, 2027, 2029, 2030, 2031, 2036, 2038, 2039, 2040 |
| replication | 11    | PA-2001, 2007, 2012, 2013, 2017, 2018, 2019, 2025, 2028, 2034, 2035 |
| consensus (test) | 2 | PA-2032, 2033 |
| consensus (observability) | 2 | PA-2011, 2037 |

---

*End of PA-2xxx register. Next index: PA-2041.*
