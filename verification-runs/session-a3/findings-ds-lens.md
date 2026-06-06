# Session A3 — Findings: distributed-systems-lens (THE FAULT MATRIX)

> Round-1 independent investigation. Scope fence: **control-plane ROOT Raft group only**
> (DEFAULT_RAFT_GROUP). Linearizability of writes + ReadIndex reads against the **real
> multi-process binary**. Edge/staleness/monotonic-read is B3 and out of scope.
>
> Every claim carries a rubric literal + `file:line` or command output. I read code; I did not
> build/run the binary (chaos-lens owns bring-up). Where I say "VERIFIED" without a run, it is a
> code-trace verification of a load-bearing fact, with the exact `file:line`.

---

## 0. Load-bearing facts I re-confirmed from code (the matrix is built on these)

| # | Fact | Classification | Evidence (file:line) |
|---|---|---|---|
| F1 | **CheckQuorum EXISTS and is wired into the live tick loop.** Every heartbeat round the leader builds the set of peers that ACKed since the last round; if that set is not a quorum, it **steps down to follower immediately**. | `[EXISTS-UNTESTED]` (against a real partition) / logic `[VERIFIED-PASS]` by read | `RaftNode.java:776-791` `tickHeartbeat` -> `buildActiveSetAndReset()` (`:1596-1613`) -> `if (!clusterConfig.isQuorum(activeSet)) { becomeFollower(currentTerm); return; }` (`:784-786`). peerActivity set TRUE only on AppendEntriesResponse (`:881`) / InstallSnapshotResponse (`:1551`), reset FALSE each round (`:1609`). |
| F2 | **PreVote EXISTS** -- candidates run a no-term-increment PreVote round before electing; a follower won't grant PreVote if it has a recent leader. | logic `[VERIFIED-PASS]` by read | `startPreVote():1077`, `handlePreVoteRequest():949` (`wouldGrantPreVote = !hasRecentLeader && logOk`, `:970`), quorum->`startElection()` (`:1014-1017`). |
| F3 | **ReadIndex lease is QUORUM-based, NOT time/clock-based.** A read is confirmed only when a heartbeat round sees a quorum active (`confirmAllLeadership`). There is no time-leased read that a stale leader could serve. | `[VERIFIED-PASS]` by read | `readIndex():392-404` records commitIndex; `confirmPendingReads(activeSet):1621-1627` calls `readIndexState.confirmAllLeadership()` **only** `if (clusterConfig.isQuorum(activeSet))`; `isReadReady():417-424` re-checks `role==LEADER`. Server marshals onto tick thread, 150ms HTTP deadline (`ConfigdServer.java:491-518`). |
| F4 | **Raft timers are TICK-COUNT based, fed by a monotonic `scheduleAtFixedRate` (nanoTime). A wall-clock jump does NOT move election/heartbeat timers.** Wall clock (`Clock`) is used only for HLC timestamps stamped on entries, never for election/heartbeat/lease. | `[VERIFIED-PASS]` by read | `RaftNode.java:59` comment "Timer state (tick-based, not wall-clock)"; `tickElection():760-773`, `tickHeartbeat():776-791` compare integer tick counters only. Driver: `tickExecutor.scheduleAtFixedRate(... TICK_PERIOD_MS ...)` (`ConfigdServer.java:556-578`), `TICK_PERIOD_MS=10` (`:83`). No `System.currentTimeMillis`/`nanoTime`/`clock` in RaftNode timer paths (grep: only `System.err` hits). |
| F5 | **Effective timeout values, real binary:** election timeout = `electionTimeoutMinMs..MaxMs` consumed as a **tick count** = 150..300 ticks x 10ms = **~1.5-3.0 s**; heartbeat/CheckQuorum cadence = `heartbeatIntervalMs`=50 ticks x 10ms = **~500 ms**. The `*Ms` field names are misleading -- they are tick counts at runtime. **No CLI/ServerConfig knob exists to tune them**; `RaftConfig.of(...)` hardcodes 150/300/50. | `[VERIFIED-PASS]` by read | `tickElection():762` `electionTicksElapsed >= electionTimeoutTicks`; `resetElectionTimeout():1648-1650` sets ticks = `electionTimeoutMinMs + rand`; `tickHeartbeat():778` `>= config.heartbeatIntervalMs()`. `RaftConfig.of` defaults 150,300,50 (`RaftConfig.java:83`); wired `RaftConfig.of(config.nodeId(), config.peers())` (`ConfigdServer.java:212`). |
| F6 | **ACK != COMMIT.** `propose()` does: local `log.append` (fsync to WAL) -> `broadcastAppendEntries` -> `maybeAdvanceCommitIndex` (commits **only** single-node) -> returns `ACCEPTED`. In multi-node, ACCEPTED means *durably appended on the leader*, NOT quorum-committed. The HTTP `proposalId` is a local `AtomicLong`, unrelated to the Raft index. | `[VERIFIED-PASS]` by read | `RaftNode.propose():283-289` (append->broadcast->maybeAdvance->return ACCEPTED); `ConfigWriteService.put():150-154` returns `Accepted(nextProposalId.getAndIncrement())`; `nextProposalId = new AtomicLong(1)` (`:84,:101`); HTTP `"Accepted: proposalId="` (`HttpApiServer.java:278`). |
| F7 | **Local log append is fsynced before propose returns.** Durability of an appended entry is real: `RaftLog.append -> storage.appendToLog` -> `channel.force(true)` (fsync). DurableRaftState (term/vote) also fsyncs before in-memory update. So a crash *after* ACCEPTED preserves the entry on that node's disk. | `[VERIFIED-PASS]` by read | `RaftLog.append():276-285` -> `storage.appendToLog(WAL_NAME, ...)` (`:283`); `FileStorage.appendToLog():89-110` ends `channel.force(true)` (`:110`); `DurableRaftState.persistValues():128-134` `storage.put + storage.sync()`; term persisted BEFORE in-memory (`setTerm():75-80`, `setTermAndVote():114-119`). |
| F8 | **InstallSnapshot is SINGLE-SHOT; chunking is a no-op.** The leader always sends `offset=0, done=true, data=<entire snapshot>`. The follower handler **never reads `offset` or `done`** -- it calls `restoreSnapshot(req.data())` on the whole blob in one apply. The only size limit is the wire frame cap = **16 MiB** (NOT 4 MiB as STATE-OF-REALITY 4.8 claims); an over-cap snapshot is silently DROPPED (IAE path), not chunked. | chunking `[ABSENT]` (no-op) / single-shot `[VERIFIED-PASS]` by read | `sendInstallSnapshot():1283-1292` constructs `InstallSnapshotRequest(..., 0 /*offset*/, data, true /*done*/, ...)`; over-cap -> `catch IllegalArgumentException ... "snapshot too large for v1 wire"; return;` (`:1300-1305`). `handleInstallSnapshot():1460-1526` ignores `offset`/`done`, single `restoreSnapshot(req.data())` (`:1501`). Frame cap `FrameCodec.MAX_FRAME_SIZE = 16 * 1024 * 1024` (`FrameCodec.java:86`). Record doc: "entire snapshot is sent in a single message" (`InstallSnapshotRequest.java:16-18`). |
| F9 | **Reconfiguration is UNREACHABLE from the running binary.** `proposeConfigChange` has **zero non-test callers** anywhere in `src/main`. `AdminService`/`MembershipChanger` (the would-be reconfig API) is **never instantiated** outside tests. There is no HTTP endpoint, no CLI flag, no wire message that triggers a membership change. Cluster membership is fixed at boot from `--peers`/`--peer-addresses`. | `[ABSENT]` (live trigger) / logic `[EXISTS-UNTESTED]` | grep `proposeConfigChange` non-test src/main -> **0**; grep `new AdminService` non-test -> **0** (both commands run, empty output). `RaftConfig.of(config.nodeId(), config.peers())` (`ConfigdServer.java:212`), peers parsed once in `ServerConfig.parse` (`ServerConfig.java:90-92,122-124`). |
| F10 | **Default GET is a STALE local-store read, not linearizable.** Linearizable ReadIndex is gated behind `?consistency=linearizable`; a plain GET returns `configStore.get(key)` with header `X-Consistency: stale` regardless of leadership. The linearizability workload MUST use `?consistency=linearizable`. | `[VERIFIED-PASS]` by read | `HttpApiServer.handleGet():231-245` -- `linearizable = query.contains("consistency=linearizable")`; else `result = configStore.get(key)` (`:244`), header `"stale"` (`:254`). Linearizable path -> `readService.linearizableRead(key)`; null => 503 "Not Leader" (`:236-242`). |
| F11 | **R-01 is closed: the live event loop is single-threaded.** Tick, inbound routing, propose, and read all marshal onto the one `configd-tick` `tickExecutor`. Faults now surface *protocol* bugs, not the A1 race (which A1 killed + tripwired). | `[VERIFIED-PASS]` (per A1 ledger + read) | `tickExecutor = newSingleThreadScheduledExecutor("configd-tick")` (`ConfigdServer.java:292-293`); inbound `raftInboundHandler(... tickExecutor)` (`:316`); propose `raftProposer(... tickExecutor ...)` (`:433`); read `tickExecutor.execute(...)` (`:491`). |
| F12 | **TCP transport (the real wire) starts ONLY when `--peer-addresses` is non-empty.** Single-node/default mode starts no TCP transport (the in-process mode that hid R-01). A3 must drive the multi-process wire path: >=3 separate JVMs with `--peer-addresses` set. | `[VERIFIED-PASS]` (per lead pointer + read) | `tcpTransport.start()` gated on non-empty peerAddresses (`ConfigdServer.java:246-247,318`); TcpRaftTransport blocking SSLSocket + virtual-thread-per-connection. |

**Two doc corrections to flag to peers:** (a) STATE-OF-REALITY 4.8 / known-limitations say the
snapshot cap is **4 MiB** -- the actual wire cap is **16 MiB** (`FrameCodec.java:86`); no separate
4 MiB constant exists in `src/main`. (b) The "offset/done ignored" claim is **correct** (F8).

---

## 1. THE FAULT MATRIX

Linearizability invariant under test throughout = **INV-L1 / INV-W1** (per-key + per-group total
order; a completed write is visible to a later op; ReadIndex never serves a value older than a
write that completed-before it). Anchors: per_key_order runtime check (`ConfigStateMachine`, wired
by A2), ReadIndex gating (F3), quorum commit (`maybeAdvanceCommitIndex:1319`).

### Core / clean faults

| Fault | Raft mechanism | Could break | Inject vs real binary (WHAT) | Expected on correct system |
|---|---|---|---|---|
| **F-A. Symmetric partition: minority vs majority** | leader election / quorum-commit | none if correct; split-brain double-commit if quorum math wrong | Cut all TCP between the two groups (chaos: iptables/tc on bind-port 9090). Client load on both sides. | Majority commits + serves linearizable reads; minority writes 503, linearizable reads time out -> 503. No two committed values at one index. History linearizable. |
| **F-B. Leader crash (kill -9), no restart** | leader election / quorum-commit | lost-acked-write; availability gap | `kill -9` the leader JVM. | New leader within ~1.5-3 s (F5). All *quorum-committed* entries survive (Leader Completeness). In-flight ACCEPTED-but-uncommitted (F6) MAY be lost -- allowed because ACK!=commit. |
| **F-C. Crash + restart of a follower** | replication / InstallSnapshot catch-up | log divergence on recovery | `kill -9` a follower, restart same `--node-id`/`--data-dir`. | Recovers fsynced term/vote (F7) + WAL, rejoins, catches up via AppendEntries or InstallSnapshot. No committed entry lost/reordered. |
| **F-D. Message loss / high drop (no full partition)** | replication / heartbeat / CheckQuorum | liveness only | Probabilistic packet drop on Raft TCP links (chaos: `tc netem loss`). | Progress slows; heavy loss -> leader loses CheckQuorum (F1), steps down, re-elects. Safety holds. |

### DANGEROUS INTERSECTIONS (each with the code path I read)

| Fault | Raft mechanism | Could break | Inject vs real binary (WHAT) | Expected on correct system | Code path I read |
|---|---|---|---|---|---|
| **F-E. ASYMMETRIC partition isolating the leader (gray failure).** Leader can still send heartbeats but followers' responses are dropped (or its outbound is dropped while it still thinks it's leader). | **CheckQuorum** (the mechanism that exists FOR this) + **ReadIndex lease** | **Stale leadership -> stale linearizable read.** A partitioned leader still serving ReadIndex while a new leader committed a newer write would violate INV-L1. | One-directional drop: allow leader->followers, drop followers->leader (or vice versa). chaos: asymmetric iptables DROP on one direction. Keep a client hitting the OLD leader with `?consistency=linearizable` reads + writes. | **CheckQuorum SAVES this (F1):** within ~500 ms (one heartbeat round, F5) leader sees no quorum -> `becomeFollower` (`:784-786`). After: writes -> NotLeader; ReadIndex -> `confirmAllLeadership` never fires (F3) -> 150 ms deadline -> 503. Majority re-elects (PreVote, F2). **No stale read served.** | `tickHeartbeat:776-791` -> `buildActiveSetAndReset:1596-1613` (peerActivity TRUE only on AE/IS *responses* `:881,:1551`); `confirmPendingReads:1621-1627`; `isReadReady:417-424`. If CheckQuorum were ABSENT, the quorum-gated lease (F3) would still make reads *time out* (not go stale), but writes would hang and clients wouldn't be redirected. **The single most important "does the mechanism fire" test in A3.** |
| **F-F. Partial / BRIDGE partition.** C sees A and B; A and B cannot see each other. | leader election / CheckQuorum / quorum-commit | **Election churn / dual-leader window / commit stall.** A and B each reach only C (the swing voter). | DROP A<->B both directions; leave A<->C and B<->C up. chaos: per-pair iptables. Client load on all three. | At most one leader per term can hold a quorum (needs 2 of {A,B,C}; only C is shared -> A+C or B+C, never both; second asker rejected by durable per-term vote). PreVote damps churn; CheckQuorum forces a leader losing C to step down ~500 ms. Committed history linearizable; reduced availability + churn. | `handleRequestVote:907-942` (one vote/term via durable `votedFor`); `isQuorum` dual-count (`ClusterConfig.java:117-123`); CheckQuorum F1; PreVote F2. The fault PreVote+CheckQuorum jointly exist for. |
| **F-G. Crash (kill -9) + restart right after a write is "acked."** "acked" = client got HTTP 200 `Accepted: proposalId=N` (F6). | quorum-commit / durable WAL / leader election | **Lost-acked-write IF the harness equates 200 with commit.** True invariant: no *committed* write is lost; a 200-ACCEPTED write is **indeterminate**. | `kill -9` the leader **immediately after** a 200 on a PUT; restart; then linearizable-read the key from the new leader. chaos kills; ds-lens defines the oracle. **Harness MUST NOT assert every 200 survives.** | Two legal, both-linearizable outcomes: (1) reached quorum pre-crash -> survives on new leader; (2) only on dead leader's WAL (F7), never quorum-replicated -> may be **dropped or truncated** on rejoin. The checker must model a 200 as **{committed OR aborted}** (an "info"/indeterminate op), not a definite write. **Coordinate w/ consistency-lens: the #1 oracle hazard.** | `propose:283-289` (append+fsync+broadcast, returns before quorum); `ConfigWriteService.put:150-154`; WAL fsync F7; uncommitted-tail truncation on rejoin via `log.appendEntries` check + `recomputeConfigFromLog` (`handleAppendEntries:819-841`). |
| **F-H. Crash DURING InstallSnapshot.** | InstallSnapshot / log compaction | follower state corruption / divergence on partial install -- IF install were chunked | Force a lagging follower to need a snapshot (kill it, let leader compact past its nextIndex; compaction every ~10 s, `COMPACTION_INTERVAL_TICKS=1000`x10ms), restart so it receives InstallSnapshot, then `kill -9` mid-receive. | **Bounded by F8: single atomic apply on the follower's tick thread.** No multi-chunk intermediate state to corrupt: either the whole `restoreSnapshot(req.data())` (`:1501`) + `log.compact` (`:1504`) ran, or it died before responding and restarts from pre-install durable state and re-requests. **Real value is narrow:** over-16-MiB snapshot is **dropped, not chunked** (F8) -> follower **permanently stuck** (liveness defect, NOT a linearizability bug). | `sendInstallSnapshot:1274-1307` (single-shot, IAE-drop `:1300`); `handleInstallSnapshot:1460-1526` (one `restoreSnapshot` + `log.compact`); `triggerSnapshot:329`; compaction `ConfigdServer.java:565`. |
| **F-I. Clock jump / drift beyond election timeout.** | (intended target: election timeout / lease) | stale-read via lease expiry mis-accounting -- IF the lease were time-based | Step the wall clock fwd/back > election timeout on leader and on a follower. chaos: libfaketime/clock_settime. | **Largely a NO-OP for Raft safety/liveness (F4/F5): timers are tick-count on monotonic `scheduleAtFixedRate`.** A wall-clock jump does NOT expire elections, move the heartbeat/CheckQuorum cadence, and the ReadIndex lease is quorum-based (F3). Only effect: HLC timestamps on entries (cross-group approx ordering, not root-group linearizability). **The valuable test is proving the negative** -- clock chaos can't perturb the root group's linearizability -- and catching any future lease-tied-to-wall-time regression. | `RaftNode.java:59` (tick-based); `tickElection`/`tickHeartbeat` integer counters; `scheduleAtFixedRate` (`ConfigdServer.java:556-578`); Clock only for HLC entry stamps. |
| **F-J. Reconfiguration under partition / during an election** (CX-2(a), 5.5d -- `[EXISTS-UNTESTED]`; `configChangePreservedAcrossElections` is **vacuous**: proposes a normal command, never a config change/election). | joint-consensus reconfig + leader election + quorum-commit | dual-majority quorum violation / committed-entry loss across a config change racing a leader change | **CANNOT be injected against the real binary (F9):** `proposeConfigChange` has no live caller, `AdminService` never wired, no HTTP/CLI/wire trigger. A3 must EITHER (a) add a minimal admin/test seam calling `proposeConfigChange` (new surface, tripwire it like A1), OR (b) test at the `RaftNode`/`MultiRaftDriver` level via in-process simulated transport -- which is NOT the real wire and fails A3's "real binary" mandate. | Correct system: a leader change mid-joint-config retains the joint (C_old,new) config the new leader held as a follower (`becomeLeader` does not recompute; `recomputeConfigFromLog` on every non-empty AE) -> commit needs BOTH majorities -> no committed entry lost, no split-brain. | `proposeConfigChange:514-563`; `recomputeConfigFromLog` on AE (`handleAppendEntries:830-841`); `becomeLeader:1163` retains config; dual-majority `maybeAdvanceCommitIndex` + `ClusterConfig.isQuorum:117-123`. **Biggest GAP: most dangerous untested case is also the one the binary gives no handle to trigger.** |

**Matrix headline (rows that decide tool choice):** F-E (asymmetric-leader-isolation, CheckQuorum
must fire), F-F (bridge partition), F-G (crash-after-ack with indeterminate-commit oracle), F-J
(reconfig-under-partition -- *uninjectable at the binary today*). F-H and F-I are **largely no-ops
for safety** given F8 (no chunking) and F4 (tick-based timers); their value is exposing a liveness
cliff (16 MiB snapshot silent-drop) and proving a negative (clock can't break the root group).

---

## 2. SCHEDULE MODEL -- what one seed must drive (faults CONTINUOUS during workload)

Goal: **one seed = one fully reproducible schedule** of faults interleaved with a concurrent client
workload, replayable bit-for-bit. chaos-lens owns the *mechanism*; I own *what the seed varies*. The
seed drives a single deterministic PRNG that, in strict sequence, produces every nondeterministic
choice below. Replay = same seed -> same `(t, action)` trace.

**The seed must drive, as an ordered event stream on a logical/virtual clock:**

1. **Fault selection** -- which fault from 1 fires next, weighted toward dangerous intersections
   (F-E, F-F, F-G), away from near-no-ops (F-H, F-I) once their negatives are established. Seed picks
   *fault type* + *parameters*: victim node, asymmetry direction (F-E), bridged pair (F-F), clock-jump
   magnitude (F-I), snapshot size relative to the 16 MiB cliff (F-H).
2. **Fault timing** -- inter-fault delay and, critically, the phase relative to the workload: a fault
   should sometimes land *immediately after a 200-ACCEPTED ack* (F-G's sharpest case), sometimes
   mid-election, sometimes mid-replication, so intersections in time (fault-cap-ack, fault-cap-election)
   are reachable, not just average-case.
3. **Fault duration / HEALING** -- hold time before heal, and heal *ordering* (in a bridge partition,
   which link heals first). Healing is itself a seeded event. Every schedule must end in a
   **fully-healed quiescent window** >= a few election timeouts (~5-10 s given F5) so the checker has
   a settled final state.
4. **Workload shape** -- key set (include hot keys with many concurrent writers to stress per-key
   order, INV-W1), op mix (PUT/DELETE/linearizable-GET -- GET **must** carry `?consistency=linearizable`,
   F10), which client targets which node (so reads sometimes hit a soon-to-be-isolated leader, F-E),
   concurrency level.
5. **Nemesis vs workload interleaving** -- the single seed serializes *both* streams into one total
   order on the virtual clock, so the entire run is a pure function of the seed. The SeedSweep lesson
   (R-05b: "10k seeds" only varied network jitter, not the schedule) sharpened: vary the **schedule of
   adversarial decisions**, not just timing noise.

**Replayability invariants:** (a) all randomness from one seeded PRNG consumed in fixed order; (b)
wall-clock never read for control decisions (binary satisfies this for Raft, F4 -- but the *harness*
nemesis timer must also be on a logical/seeded clock or heal/fault timing drifts across runs); (c)
client op-issue order and recorded history timestamped on the same clock the checker uses.

---

## 3. ADR VOTE -- full Jepsen (Clojure + Elle) vs bespoke Java + Porcupine

I am the independent third vote. Feasibility, fault-by-fault: can each tool inject every fault against
the real multi-process binary, and how much custom code.

| Fault | Jepsen injection | Bespoke Java injection | Notes |
|---|---|---|---|
| F-A symmetric partition | `nemesis/partition-random-halves` -- built in, zero custom code | Custom iptables/tc + orchestration. Moderate | Jepsen wins on built-in nemeses |
| F-E asymmetric leader-isolation | Directional grudge partition (small custom grudge fn) | Custom directional iptables + leader detection. Moderate | Both feasible; Jepsen grudge closer to ready |
| F-F bridge partition | Grudge map expresses A!B, A-C, B-C directly (custom grudge) | Custom per-pair iptables. Moderate | **Neither injects for free; both need a custom topology spec** |
| F-G crash-after-ack | `nemesis/node-start-stopper` + kill -9 (Jepsen strength) | `Process.destroyForcibly()` + restart. Easy | **Equal.** Hard part is the *oracle*, not injection |
| F-H crash-during-InstallSnapshot | Kills mid-RPC, but timing the kill mid-install needs custom binary-state probing | Java can read `/metrics`/logs to detect snapshot-in-flight and time the kill -- tighter coupling easier | **Bespoke slightly favored** for timing; F8 makes it low-value either way |
| F-I clock jump | `nemesis/clock-scrambler`/`bump-time` -- **built in** | Custom libfaketime wiring | **Jepsen wins** -- but F4 makes this a near-no-op for safety |
| F-J reconfig-under-partition | **Neither can inject -- F9: no live trigger; both need a new admin/test seam first** | Same blocker | **Decisive: binary-surface gap, tool-independent** |
| History capture + check | **Elle** infers dependency-graph cycles and handles indeterminate (info) ops natively -- exactly what F-G needs; thin Clojure HTTP client | **Porcupine** needs a hand-written model + per-op linearization; indeterminate ops expressible but you build them | **Elle materially better for our oracle** (F-G indeterminate writes; per-key/per-group cycles map to INV-W1/INV-L1) |

### The deciding criterion

> **The fault that decides this is F-G's oracle, not any injection mechanism.** Every fault is
> *injectable by both* with comparable custom code (partitions need a grudge/iptables spec either way;
> F-J is blocked for both). Injection is a wash. What is NOT a wash is the **correctness oracle:**
> because ACK!=COMMIT (F6/F-G), the history is full of *indeterminate* ops, and the checker must reason
> about per-key (INV-W1) and per-group (INV-L1) cycles over a concurrent history. **Elle was built for
> exactly this**; Porcupine makes us hand-build the model + indeterminate-op handling and gives a weaker
> failure diagnostic (a non-linearizable point, not a cycle witness).

### My vote

**I vote for the Jepsen + Elle stack -- conditionally, and not for the reason consistency-lens will
argue.** Consistency-lens will sell Jepsen on injection breadth; that is the *weaker* argument
(injection is a wash; F-J blocked for both; F-I/F-H low-value). The *real* reason is the **Elle
oracle**: it natively handles the indeterminate-commit history F6/F-G force on us, and its cycle
witnesses beat Porcupine's "not linearizable here." chaos-lens's pro-bespoke case is strongest on F-H
timing (Java reads `/metrics` to time the kill) -- granted, but F-H is low-value given F8, so it
shouldn't drive the decision.

**Conditions on my vote (cross-examination hooks):**
1. **The binary must grow observability the nemesis can key on** regardless of tool -- at minimum a
   leader/term/commit-index endpoint so the schedule targets "the current leader" deterministically.
   (Today leadership is inferable only via 503-on-write / X-Leader-Hint, `HttpApiServer.java:280-284`.)
2. **F-J stays out of scope OR the team adds a reconfig seam first** (F9). I won't vote for a stack on
   the basis of a fault neither stack can trigger. If reconfig-under-fault is in scope, that's a
   *prerequisite code change*, decided before the tool.
3. **The Clojure surface must stay thin** -- client + grudge fns only; if the team reimplements the
   binary's protocol in Clojure, the bespoke-Java case wins on maintainability.

**One-line reason:** *Injection is a wash and our worst fault (F-J) is blocked for both, so the decider
is the oracle -- Elle natively handles the indeterminate-commit history that ACK!=COMMIT forces, which
Porcupine makes us hand-build -- therefore Jepsen+Elle, conditioned on adding leader/term observability
and resolving the reconfig-trigger gap first.*

---

## 4. Cross-examination hooks

- **To chaos-lens:** F-H and F-I are near-no-ops for *safety* (F8 no chunking; F4 tick-based timers).
  Do you have a code path that makes either a real linearizability oracle? If not, they're
  liveness/regression tests, not safety faults, and shouldn't carry weight in the tool vote.
- **To consistency-lens:** the pro-Jepsen case must rest on the **Elle oracle for indeterminate ops
  (F-G)**, not injection breadth. Argue injection breadth and I'll show it's a wash and F-J is blocked
  for both -- which *weakens* the Jepsen case to a coin-flip.
- **Expect to be challenged on F4 (clock):** someone may claim a wall-clock dependency I missed. I
  grepped RaftNode timer paths -- Clock feeds only HLC entry stamps; timers are integer ticks on
  `scheduleAtFixedRate`. If a peer finds a wall-clock read in the lease/election/commit path, F-I and
  my vote's weighting change. The one fact most worth a second pair of eyes.
- **Expect to be challenged on F9 (reconfig unreachable):** if a peer finds a live `proposeConfigChange`
  caller or a wired `AdminService` I missed, F-J becomes injectable and re-enters the tool comparison.

---

## Phase 2 — Cross-examination outcome (Round 2)

**ADR vote RE-CAST — Round-1 vote (Jepsen+Elle, conditional) WITHDRAWN.** Both premises it rested on were
wrong, owned here rather than defended:
- **Challenge A conceded.** "Elle handles indeterminate, Porcupine makes us hand-build it" is **false** —
  Porcupine has first-class indeterminate-op support (`call` with unknown/absent return, placeable anywhere
  ≥ invocation or omitted; etcd uses exactly this for timed-out writes). And the live model is a **per-key
  linearizable register** (verified: `ConfigWriteService` exposes only single-key `put`/`delete:121,164`,
  no batch/txn on the wired path) — Porcupine's home turf; Elle's transactional cycle-detection power is
  unused for a single-register-per-key model.
- **Challenge B conceded — injection is NOT a wash; it tilts Java-native.** chaos-lens *proved* on this box
  iptables-partition→re-election/step-down + kill-9/restart durability, all from Java with zero Clojure, and
  Jepsen's nemesis *also just shells out*. No fault in the matrix is injected materially better by Jepsen's
  library than by a Java shell-out (F-E/F-F/F-I are grudge/clock conveniences over the same OS calls; F-I is
  a near-no-op; F-J is blocked for both).
- **Challenge C endorsed — the reframe.** Orchestrator is separable from checker; "Jepsen vs bespoke" was a
  false binary. Decision collapses to two axes: (i) orchestration = Java-native, **proven**, decided;
  (ii) checker = Porcupine vs Elle on detection-power-per-effort for a per-key register.

**Re-cast vote: bespoke Java orchestrator (proven) + Porcupine as the checker. Reject the full Jepsen/Clojure
stack.** Single decisive criterion: *with OS-level injection proven Java-native and identical to what Jepsen's
nemesis shells out to, the only thing the Clojure stack adds is the checker — and for a single-key linearizable
register with indeterminate timed-out writes, Porcupine is the modeled fit with native indeterminate support,
while Elle's transactional cycle-detection power is unused. Zero Clojure tax for zero capability gain.*
**Flip-back condition:** if a future scope wires multi-key atomic BATCH (named in the contract, not wired
today), cross-key cycle detection becomes load-bearing and Elle re-enters.

### Fault-matrix rulings (recorded with reasons — analyzed, not silently omitted)
- **STAY (real safety faults):** F-E asymmetric leader-isolation (CheckQuorum / ReadIndex-lease test,
  `tickHeartbeat:784-786`, `confirmPendingReads:1621-1627`); F-F bridge partition (PreVote+CheckQuorum +
  one-vote-per-term, `handleRequestVote:907-942`, `ClusterConfig.isQuorum:117-123`); F-G crash-after-ack
  durability (with the indeterminate-commit oracle; aligns with the lost-acked-write seed at
  `FileStorage.java:110`); F-A symmetric partition **+ HEALING** (heal is a first-class scheduled event);
  repeated re-election under churn (F-D/F-B chain — exercises Leader Completeness across terms); plus the
  **stale-read negative control** (delete `role != LEADER` recheck at `RaftNode.java:421`) that proves F-E's
  oracle actually bites.
- **DROPPED with file:line reason (not silently):** F-I clock-jump/drift — Raft timers are tick-count on a
  monotonic `scheduleAtFixedRate` (`RaftNode.java:59`; `tickElection:760`/`tickHeartbeat:776`), wall clock
  feeds only HLC entry stamps; keep as a one-shot regression assertion, not a recurring fault. F-H
  crash-during-InstallSnapshot — install is single-shot/atomic-apply (`sendInstallSnapshot:1283-1292` always
  `offset=0,done=true`; `handleInstallSnapshot:1501` one `restoreSnapshot`), no partial-install state to
  corrupt; record instead the **liveness cliff** (a snapshot > 16 MiB is silently dropped,
  `FrameCodec.java:86`, IAE path `RaftNode.java:1300` → follower permanently stuck) as a finding owed to a
  later session.
- **DEFERRED (residual gap, named):** F-J reconfig-under-partition/election — `proposeConfigChange`
  (`RaftNode.java:514`) has zero non-test callers; `AdminService` never wired. Do NOT add an admin reconfig
  seam in A3-B (it would be a *new verified-but-untested-integration seam* — exactly the A1 prior — and widens
  A3 past its fence). Record: joint-consensus reconfig is `[EXISTS-UNTESTED]` with no live trigger; the only
  existing test `configChangePreservedAcrossElections:257-270` is vacuous (§5.5d); owed to a dedicated
  reconfig session that (a) wires a reconfig seam **with a tripwire**, then (b) fault-tests reconfig-under-partition.
