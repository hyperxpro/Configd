# Triage Verifier — Independent Reproductions (Session 1)

Verifier: second-agent cross-review per the audit rule "no P0/P1 enters the register without
independent reproduction." Branch `session-1-ground-truth`, HEAD `423a654`. All evidence below was
collected from scratch (own greps / file reads / git history); no cluster or iptables experiments
were run (mutation-testing constraint) — CF-21 was verified by code tracing as instructed, and is
additionally backed by the prior A3-B empirical observation recorded in the ledger (R-15).
Zero of the two permitted heavy runs were consumed; all verification was static.

Severity scale: P0 = safety violation / data-loss risk / cannot run / core guarantee
FICTION-or-CONTRADICTED; P1 = correctness risk / major guarantee untested / hot-path constraint
violated; P2 = quality/operability; P3 = polish.

---

## P0 candidates

### P0-1 — End-to-end undemonstrable: committed writes can never reach an edge
**VERDICT: CONFIRMED (cross-verified in Phase A by 3 agents; spot-check passed). Severity: P0.**

My evidence (all three anchors re-derived independently):
- `configd-server/src/main/java/io/configd/server/ConfigdServer.java:360` — state-machine listener
  ends at `fanOutBuffer.append(delta)`. The only other src/main reference to the buffer is the
  passive getter `fanOutBuffer()` at `ConfigdServer.java:794-795`; repo-wide grep shows no drain
  caller outside tests.
- `HttpApiServer.java:74-82` — exactly four HTTP contexts (`/health/live`, `/health/ready`,
  `/metrics`, `/v1/config/`); no subscribe/watch/stream context. Repo-wide grep for listening
  sockets in src/main finds only two: `HttpApiServer.java:71` (`HttpServer.create`) and
  `TcpRaftTransport.java:118-119` (Raft port). `grep -rln "java.net|javax.net|com.sun.net"
  */src/main` hits only configd-server, configd-transport, and configd-linz's test client —
  **zero networking imports in configd-distribution-service and configd-edge-cache**.
- `configd-edge-cache/.../EdgeConfigClient.java:1-40` — imports only store/common types;
  `applyDelta(ConfigDelta)` (line 130) takes in-process objects. No edge `main()`, no client
  transport anywhere.
- Consolidation wording spot-checked at `docs/audit-session-1/ground-truth.md:95-109` — accurate
  and matches my findings verbatim (dead-end at `fanOutBuffer.append`, 4 contexts, Plumtree
  broadcast benchmark-only — my grep for `.broadcast(` in non-test src/main returns nothing).

Register-ready: The system's headline pipeline — propagate a committed config to an edge — does not
exist at runtime. Committed deltas dead-end in an in-memory ring buffer with no consumer; the server
exposes no subscription/fan-out endpoint and no socket besides the HTTP API and the Raft port; the
edge client library has zero networking. Every staleness, read-your-writes, and monotonic-read
guarantee in `consistency-contract.md` is therefore unfalsifiable end-to-end. P0: core guarantee is
fiction.

---

### P0-2 / CF-21 — Timeout-less blocking connect/TLS handshake on the single Raft event-loop thread
**VERDICT: CONFIRMED. Severity: P0.**

My evidence (full code trace, as instructed in lieu of a live experiment):
- `configd-transport/src/main/java/io/configd/transport/TcpRaftTransport.java:343` — plaintext path
  `new Socket(address.getAddress(), address.getPort())` (blocking constructor-connect, no timeout);
  TLS path lines 324 + 340 — `factory.createSocket(host, port)` (blocking connect) followed by
  `socket.startHandshake()` (blocking). `grep setSoTimeout|connect(.*timeout` over the transport
  module: zero hits. Established-connection writes (`sendFrame`, lines 398-412: `out.write` +
  `flush`) also have no write timeout.
- Call chain to the tick thread: `send()` (line 153) → `PeerConnection.sendFrame` →
  `ensureConnected()` (line 415) → `createClientSocket`. Callers of `transport.send` are all inside
  `RaftNode` (heartbeats/votes/appends, e.g. `RaftNode.java:1255,1299`) which post-R-01 executes
  exclusively on the single `tickExecutor` thread: `ConfigdServer.java:292`
  (newSingleThreadScheduledExecutor), `:316` (inbound marshalled), `:433/:715` (propose
  marshalled), `:491` (reads marshalled), `:556-578` (tick loop scheduled).
- Backoff dead code: `ConnectionManager.canSend` (`ConnectionManager.java:119`) — only callers are
  in `ConnectionManagerTest.java`. Nothing rate-limits reconnect attempts; after
  `handleSendFailure` (TcpRaftTransport.java:308-314) removes the connection, the next tick's send
  re-creates it and re-blocks.
- OS arithmetic: a blocking connect to a black-holed peer (SYN dropped) waits the kernel default
  `tcp_syn_retries=6` ≈ ~127 s. Tick cadence target is 10 ms (`ConfigdServer.java:83`). While
  blocked, the node processes no ticks, no inbound Raft messages, no proposals, no linearizable
  reads — and re-blocks on every subsequent send attempt for as long as the peer is black-holed.
- Prior empirical confirmation on record: `docs/READINESS-LEDGER.md:134` (R-15, A3-B) — DROP-style
  isolation of a follower stalled the leader so a write never committed; the A3 harness had to use
  REJECT (fail-fast RST) to test at all.

Register-ready: One black-holed peer (routine partition behavior) turns any node — including the
leader — catatonic: the timeout-less blocking connect/TLS handshake runs on the single serialized
Raft event-loop thread that owns tick, inbound, propose, and reads, stalling all of them for the
~2-minute kernel SYN timeout, repeatedly. The existing `ConnectionManager` backoff is dead code
(zero callers). Empirically observed in session A3-B (ledger R-15). P0: a single-node network fault
becomes a full-node, and on the leader a cluster-wide, outage.

Severity-dispute note: READINESS-LEDGER R-15 carries this as 🟡 "liveness gap, later session"; under
this audit's charter (cannot run under routine fault) it is P0. Disagreement recorded.

---

### P0-3 / CF-42 — Restart after compaction = silent data loss (snapshot RAM-only, WAL prefix deleted, recovery silently skips)
**VERDICT: CONFIRMED; severity decision: P0 (latent), explicitly coupled to CF-43.**

My evidence (each link verified end-to-end):
1. Snapshot never persisted: `RaftNode.triggerSnapshot` (`RaftNode.java:329-349`) stores the
   state-machine snapshot only in the in-RAM field `latestSnapshot` (`RaftNode.java:85`, write at
   `:346`). Grep over all of `latestSnapshot` usage: no persistence call. The `Storage` interface
   (`configd-common/.../Storage.java`, `FileStorage.java`) has no snapshot-blob path and no caller
   ever `put()`s snapshot data; the only persisted artifact is 16 bytes of *metadata*
   (`RaftLog.java:432-436`, `SNAPSHOT_META_KEY` = index+term, no data). The follower-side ingest
   (`handleInstallSnapshot`, `RaftNode.java:1501-1510`) likewise restores into RAM only.
2. WAL prefix destroyed: `RaftLog.compact` (`RaftLog.java:399-438`) drops entries ≤ index from
   memory and `rewriteWal()` (`RaftLog.java:485-507`) rewrites the WAL **without** the compacted
   prefix (or deletes it entirely if empty).
3. Recovery silently skips: `RaftLog` recovery (`RaftLog.java:87-147`) reads only the truncated WAL
   + metadata; `lastApplied` restarts at 0 (`RaftLog.java:92`); no constructor path restores the
   state machine (`RaftNode.java:151-185`). `applyCommitted` (`RaftNode.java:1356-1394`):
   `entryAt(nextApply)` returns `null` for any index ≤ snapshotIndex (`RaftLog.java:192-197`), and
   the `entry != null` guard means the loop **silently advances `lastApplied` past every missing
   entry** (line 1389) without applying or logging. Result: after restart, all compacted entries
   are absent from the state machine — silent loss of the compacted key-space.
4. Reachability (CF-43 interplay): the ONLY production caller of `triggerSnapshot()` is
   `sendInstallSnapshot` (`RaftNode.java:1277`), which is reached only when `termAt(prevIndex) ==
   -1` (`RaftNode.java:1230-1234`), i.e. only when compaction has *already* raised snapshotIndex —
   circular. No server-side size/interval trigger exists (repo grep: only RaftNode + one unit
   test). So in the wired server the loss path is currently unreachable.

Severity decision and justification: **P0 (latent)** rather than P1. The data-loss mechanism is
fully present and complete in committed code; the only thing preventing it in production is a
*different* defect (CF-43, compaction unreachable). The obvious fix for CF-43/CF-40 (wire a
size-threshold `triggerSnapshot()` call to stop unbounded WAL growth) immediately arms CF-42 and
converts every post-compaction restart into silent data loss. Registering this below P0 invites
exactly that sequence. The two findings must be fixed together (persist snapshot data + restore at
boot before any compaction trigger is wired).

Register-ready: Snapshots exist only in RAM: `triggerSnapshot`/`handleInstallSnapshot` never write
snapshot data to storage, while `RaftLog.compact` permanently deletes the corresponding WAL prefix,
and recovery's `applyCommitted` silently skips any index missing from the log. Any node that
compacts and then restarts silently loses the entire compacted prefix from its state machine.
Currently latent only because compaction is unreachable in the wired server (CF-43) — fixing CF-43
without fixing this activates the loss path. P0-latent; must be fixed jointly with CF-43.

---

### P0-4 / CF-44 (= ledger R-14) — HTTP 200 on leader-local append, pre-commit; contract §6 contradicted
**VERDICT: CONFIRMED (cross-verified in Phase A: smoke live + archaeology + matrix; spot-check passed). Severity: P0.**

My evidence:
- `RaftNode.propose` (`RaftNode.java:283-289`): `log.append(entry); broadcastAppendEntries();
  maybeAdvanceCommitIndex(); return ProposalResult.ACCEPTED;` — ACCEPTED is returned after the
  *local* append; in a multi-node cluster commit requires later quorum acks.
- The HTTP write thread blocks only for this pre-commit result: `ConfigdServer.raftProposer`
  (`ConfigdServer.java:709-737`) completes the future with `driver.propose(...)`'s immediate return.
- `ConfigWriteService.put` (`ConfigWriteService.java:150-154`) maps it to
  `WriteResult.Accepted(nextProposalId.getAndIncrement())` — `proposalId` is a free-running local
  `AtomicLong` (`:84,:101`), not a commit sequence.
- `HttpApiServer.java:278`: `sendResponse(exchange, 200, "Accepted: proposalId=" + ...)`.
- Contract: `docs/consistency-contract.md:161-185` (§6) — "After a client writes key K and receives
  acknowledgment **with commit sequence S**: Client sets its `VersionCursor.version = S` …"
  (INV-RYW1 is defined off S). The implementation returns neither a commit guarantee nor a commit
  sequence, so the documented read-your-writes mechanism cannot be used at all, and a leader that
  acks then crashes before replication loses an acknowledged write on failover.

P0-vs-P1 call: P0 per charter — §6 is a core guarantee and it is CONTRADICTED, not merely untested;
additionally this is acked-write-loss risk (data loss as experienced by the client). Phase A agents
were split P0/P1 per the candidate note; my call is P0. Dispute recorded.

Register-ready: Writes are acknowledged with HTTP 200 when the leader appends locally, before
quorum commit, and the returned `proposalId` is a meaningless local counter rather than the commit
sequence the consistency contract (§6) requires. Read-your-writes as specified is unimplementable
by clients, and an acknowledged write can vanish on leader failover. Contract §6 is contradicted by
the implementation: P0.

---

## P1 candidates

### BI-1 — Supply-chain CI job recorded as wired; never existed in any commit
**VERDICT: CONFIRMED. Severity: P1.**

My evidence:
- `docs/loop-state.json:37-39`: `"ci_secret_scan_wired": true, "ci_dep_cve_scan_wired": true`,
  evidence string naming a "`.github/workflows/ci.yml` supply-chain-scan job: Trivy fs … +
  gitleaks-action@v2".
- Reproduction over history: only two commits in the entire repo (all refs) ever touched
  `.github/workflows/` — `d849eb1` and `53c86f8`. `git show <c>:.github/workflows/ci.yml | grep -ci
  "trivy|gitleaks"` → **0 for both**. Current `ci.yml` has 3 jobs (`build-and-test`,
  `tlc-model-check`, `wire-compat`) — no scan job.
- Repeated false claims: `.gitleaks.toml:10` ("This config is referenced by
  .github/workflows/ci.yml" — it is not); `release.yml:97-99` ("The CI workflow … only Trivy-scans
  the filesystem" — CI runs no Trivy at all).

Register-ready: The loop-state record asserts secret-scanning and dependency-CVE-scanning are wired
into CI, citing a `supply-chain-scan` job that has never existed in any version of `ci.yml` across
all branches; `.gitleaks.toml` and `release.yml` comments repeat the claim. The project's recorded
security posture (iter0 prerequisites "met") is false, and no source-tree CVE/secret scanning runs
anywhere. P1.

### BI-2 — Deferred P1 test files lost; "reversible by mv" claim false
**VERDICT: CONFIRMED. Severity: P2 (ADJUSTED from P1 — dispute recorded).**

My evidence:
- Machine-wide `find / -name "HttpApiServerMetricsTest.java" -o -name "ChaosScenariosTest.java"`
  and `find / -type d -name ".iter3-deferred-tests"` → zero hits (including read-only ws-clean and
  ws-smoke).
- `git log --all --name-only` → neither file ever committed (they were untracked when "moved").
- Claims: `docs/loop-state.json:16-17` lists both as open P1 carry-overs; `loop-state.json:33,64`
  and `docs/review/iter-002/verify.md:38-55` record the move to
  `/home/ubuntu/Programming/Configd/.iter3-deferred-tests/` (the *old* repo path) and assert
  "reversible by `mv`" (verify.md:55). The other two deferred files (FrameCodecPropertyTest,
  RaftMessageCodecPropertyTest) were promoted in-tree and survive; these two are gone.

Severity-dispute note: originating build agent rated P1. My call: P2 — no runtime behavior is
affected; the underlying coverage gaps (HTTP metrics path, chaos scenarios) are already tracked as
their own open items. What this finding adds is (a) two test artifacts permanently lost and (b) a
false "reversible" evidence claim — an evidence-integrity/quality defect. Disagreement recorded;
register at P2 with the integrity note.

Register-ready: Two deferred test files carried as open P1 items (`HttpApiServerMetricsTest`,
`ChaosScenariosTest`) were never committed and the `.iter3-deferred-tests/` directory said to hold
them no longer exists anywhere on the machine (it lived under the repo's old path). The documented
"reversible by `mv`" guarantee is false; the carry-over items are unactionable as written and the
tests must be rewritten from their descriptions. P2 (evidence integrity / lost test assets).

### CF-43 + CF-40 — Compaction unreachable → WAL grows forever; boot failure at 2 GiB
**VERDICT: CONFIRMED (both links). Severity: P1 (escalates toward P0 with uptime/scale).**

My evidence:
- CF-43: only caller of `triggerSnapshot()` is `sendInstallSnapshot` (`RaftNode.java:1277`);
  `sendInstallSnapshot` is reached only via `termAt(prevIndex) == -1` (`RaftNode.java:1230-1234`),
  which with `snapshotIndex == 0` cannot occur (termAt returns -1 only below snapshotIndex or above
  lastIndex; nextIndex bookkeeping keeps prevIndex ≤ lastIndex). `log.compact` has exactly two
  callers: `triggerSnapshot` (:347) and `handleInstallSnapshot` (:1504) — the latter requires a
  leader that already has a snapshot. Circular: no node can ever take the first snapshot. No
  size/interval trigger exists in configd-server (the `Compactor` at `ConfigdServer.java:566` is
  the distribution-layer delta compactor, unrelated to the Raft log).
- CF-40: `FileStorage.readLog` (`FileStorage.java:128`): `ByteBuffer.allocate((int) fileSize)`. At
  fileSize ≥ 2^31 the int cast goes negative → `IllegalArgumentException` from `allocate` during
  `RaftLog` recovery (`RaftLog.java:99`) → node cannot boot. Below that, a multi-GB single
  on-heap buffer risks OOM. Since every entry is retained forever (CF-43), every node's WAL grows
  monotonically and all replicas approach the cliff together.

Register-ready: Raft log compaction is unreachable in the wired server (the only trigger is inside
the snapshot-send path, which itself requires a prior compaction), so the WAL grows without bound;
and WAL recovery casts the file size to `int`, so any node restarting with a ≥ 2 GiB WAL fails to
boot with an IllegalArgumentException. Because all replicas grow at the same rate, the failure mode
is an eventual cluster-wide crash-loop on restart. P1 now; time-bomb to P0. Must be fixed jointly
with CF-42 (see P0-3).

### CF-50 — `...Ms`-named config values consumed as 10 ms tick counts (10× timing)
**VERDICT: CONFIRMED. Severity: P1.**

My evidence: `RaftConfig.of` defaults 150/300/50 documented as milliseconds
(`RaftConfig.java:13-15`, used by the server at `ConfigdServer.java:212`). Consumption as tick
counts: `RaftNode.resetElectionTimeout` (`RaftNode.java:1648-1650`) sets `electionTimeoutTicks =
electionTimeoutMinMs + random(...)`; `tickElection` (:761-762) and `tickHeartbeat` (:777-778)
compare per-tick counters against these values; the production tick period is 10 ms
(`ConfigdServer.java:83`, schedule at :556-578). Effective: election timeout 1.5–3.0 s (docs say
150–300 ms), heartbeat 500 ms (docs say 50 ms). Matches the smoke run's observed ~2.3 s
re-election (`ground-truth.md:101-103`).

Register-ready: Millisecond-named Raft timing config is consumed as 10 ms tick counts, making the
real election timeout 1.5–3 s and the heartbeat 500 ms — 10× every documented value. Failover takes
seconds, all contract staleness/failover budgets derived from 150–300 ms are wrong as deployed, and
the error widens the window of every other availability finding (e.g. CF-21). Confirmed empirically
by the live kill-leader test (~2.3 s). P1.

### CF-22 — FileChannel open + fsync + close per WAL entry, on the tick thread; full rewrite on truncate
**VERDICT: CONFIRMED (slightly worse than candidate wording: per ENTRY, not per 64-entry batch). Severity: P1.**

My evidence: `FileStorage.appendToLog` (`FileStorage.java:89-114`) opens a new FileChannel, writes
one framed entry, `force(true)`, closes — per call. `RaftLog.append` (:276-286) makes one call per
entry; `appendAll` (:292-296) loops per entry, so a 64-entry AppendEntries batch
(`maxBatchSize=64`, `RaftConfig.of`) = 64 open/fsync/close cycles. `truncateFrom` → `rewriteWal`
(`RaftLog.java:485-507`) re-appends *every remaining entry* through the same per-entry path (O(n)
fsyncs) plus a directory fsync. All of it executes on the single tick/event-loop thread (propose
and inbound append paths are marshalled there, `ConfigdServer.java:316,:433`).

Register-ready: Every WAL entry is persisted by opening a fresh FileChannel, writing, fsyncing, and
closing — per entry, on the single Raft event-loop thread; follower conflict truncation rewrites
the entire WAL with one fsync per surviving entry. Write latency/throughput are bounded by
(entries × fsync) and the consensus loop stalls behind disk on every append. Hot-path constraint
violated: P1.

### CF-29 — Marshalled inbound Raft tasks swallow Throwables silently → mute-zombie follower
**VERDICT: CONFIRMED. Severity: P1.**

My evidence: `ConfigdServer.raftInboundHandler` (`ConfigdServer.java:688-691`) returns
`(from, message) -> raftExecutor.execute(() -> driver.routeMessage(groupId, message))` — no
try/catch. The executor is the single-thread `ScheduledThreadPoolExecutor`
(`ConfigdServer.java:292`), whose `execute()` wraps tasks in a ScheduledFutureTask: a thrown
Throwable is captured into a Future nobody retains — never rethrown, no
UncaughtExceptionHandler, no log, no metric (JDK STPE semantics). The transport's own
handler-dispatch catch (`TcpRaftTransport.java:275-283`) cannot see it because the failure happens
asynchronously after marshalling. So a follower whose disk fails (e.g. `FileStorage.appendToLog` →
`UncheckedIOException`, `FileStorage.java:111-113`, thrown inside
`handleAppendEntries → log.append`) persists nothing, sends no response to the leader, and emits no
signal whatsoever. H-009 fixed only the *scheduled tick lambda* (`ConfigdServer.java:556-577`
catch → `handleTickLoopThrowable`); marshalled inbound tasks (and reads at :491) have no
equivalent. The propose path, by contrast, does catch Throwable (:715-721) — the asymmetry confirms
the gap is the inbound lane.

Register-ready: Inbound Raft messages are marshalled onto the tick executor with no exception
handling; the ScheduledThreadPoolExecutor silently captures any Throwable into a discarded Future.
A follower with a failing disk becomes a mute zombie — it acks nothing, logs nothing, and
increments no counter — while H-009's throwable instrumentation covers only the periodic tick
lambda. P1 (silent correctness/availability failure mode with zero observability).

### HF-1 — "Zero-alloc read" contradicted: getHit = 32.001 B/op
**VERDICT: CONFIRMED (allocation site named without JMH). Severity: P1 (per charter "hot-path constraint violated"; see note).**

My evidence: hit path `VersionedConfigStore.get` (`VersionedConfigStore.java:189-197`) →
`ReadResult.found(vv.valueUnsafe(), vv.version())` → `new ReadResult(byte[] ref, long, boolean)`
(`ReadResult.java:30-45`). `HamtMap.get` is allocation-free (bitmap descent, no boxing —
`HamtMap.java:209-223`). Instance size: 12 B header (compressed oops) + 4 B array ref + 8 B long +
1 B boolean = 25 → 8-byte-aligned **32 B** — exactly the measured 32.001 B/op
(`docs/audit-session-1/harness-runs.md:77`); miss path returns the `NOT_FOUND` singleton (0 B,
matches). The strict-zero-alloc escape hatch `getInto` (`VersionedConfigStore.java:234-254`,
`LocalConfigStore.java:178`) has **zero production callers** (only its definitions and the
allocation test). Contradicted claims: `docs/battle-ready/performance-final.md:18`
("VersionedStoreReadBenchmark … 0 B"), `docs/gap-analysis.md:100` ("Zero-allocation read path
verified by JMH"), `docs/inventory.md:65` ("re-fixed" via getInto — which nothing calls). Note:
`docs/performance.md:33-34` honestly concedes ~24 B/op per VDR-0001, so the docs contradict each
other.

Register-ready: The hot read path allocates one 32-byte `ReadResult` per cache hit
(`ReadResult.found` at `VersionedConfigStore.java:196`), exactly matching the JMH-measured
32.001 B/op; the zero-alloc `getInto` API added to "re-fix" the claim has no production callers.
Battle-ready and gap-analysis docs still assert 0 B / "verified zero-allocation," contradicting
both the measurement and the project's own VDR-0001 concession. P1 (stated hot-path constraint
violated; doc set internally inconsistent).

### HF-2 — "Deterministic" simulation has entropy-seeded per-node election RNG
**VERDICT: CONFIRMED. Severity: P1.**

My evidence: `ConsistencyPropertyTests.ClusterHarness` (`ConsistencyPropertyTests.java:53-83`)
passes the test seed only to `RaftSimulation` (network: `RaftSimulation.java:35-39`), while each
node is built with `RandomGenerator.of("L64X128MixRandom")` (`ConsistencyPropertyTests.java:77`) —
a fresh, entropy-seeded generator per JDK spec. That generator drives election randomization at
`RaftNode.resetElectionTimeout` (`RaftNode.java:1648-1650`). So election schedules are uncontrolled
by the seed, falsifying "same seed = same execution" (`ConsistencyPropertyTests.java:29`,
`RaftSimulation.java:16`) — failures are not replayable, and the 10k-seed SeedSweep varies only
network behavior while the dominant nondeterminism (election timing) is unseeded entropy.

Register-ready: The simulation harness seeds only the network; every RaftNode's election RNG is
created entropy-seeded (`RandomGenerator.of("L64X128MixRandom")`), so "deterministic, same seed =
same execution" is false. Seed-sweep results are non-reproducible and the claimed 10k-seed coverage
does not systematically explore election interleavings. P1 (the core verification methodology's
determinism claim is broken).

### HF-3 — No jcstress anywhere; lock-free structures race-untested
**VERDICT: CONFIRMED. Severity: P1.**

My evidence: `grep -ri jcstress` over all pom.xml and *.java → zero hits; mentions exist only in
docs/plans (`docs/performance.md:48` lists jcstress as part of the methodology; PROMPT.md:357;
gap-closure.md:224 defers it). The lock-free `VersionedConfigStore`/`HamtMap` (single-writer,
multi-reader volatile publication) and `FanOutBuffer` have no race harness of any kind.

Register-ready: No jcstress (or equivalent, e.g. Lincheck) dependency, harness, or test exists
anywhere in the build, despite docs/performance.md listing it as part of the verification
methodology. The lock-free read-path structures the product's headline latency claims rest on have
never been tested under contended publication/visibility races. P1 (major guarantee untested).

### OPS-1 — `configd_write_commit_total` / `_failed_total` never incremented
**VERDICT: CONFIRMED — and broader than the candidate. Severity: P1.**

My evidence: `ConfigdMetrics` eagerly registers the counters (`ConfigdMetrics.java:82-93`) so they
export as 0, but repo-wide grep for `writeCommitTotal()/writeCommitFailed()/writeCommitSeconds()`
finds only a unit test (`ConfigdMetricsTest.java:78-79`). In `ConfigdServer` the instance is
created at `:381` as `new ConfigdMetrics(metricsRegistry, () -> 0L)` — the
`configd_raft_pending_apply_entries` gauge is wired to a **constant 0** — and the object's only
production use is `handleTickLoopThrowable`. In fact *none* of the nine SLO metric handles
(`applySeconds`, `edgeReadTotal`, `edgeReadSeconds`, `propagationDelaySeconds`,
`snapshotInstallFailed`, `snapshotRebuild`, …) has any production call site. The
ControlPlaneAvailability alert (`ops/alerts/configd-slo-alerts.yaml:143-168`) divides by
failed+total — both permanently 0 — so it can never fire even during a total write outage.

Register-ready: Every SLO metric in ConfigdMetrics is registered but never written by production
code: write commit/fail counters stay 0 through real commits, the Raft pending-apply gauge is wired
to a constant `() -> 0L`, and snapshot/edge/propagation metrics are equally dead. The availability
alert computes 0/0 forever, so a fully broken control plane never pages. The metric pipeline that
F5/H-001 closure claims is "wired" is decorative end-to-end. P1.

### OPS-2 — No histogram `_bucket` families at runtime → 6/9 alerts + p99 panels can never fire
**VERDICT: CONFIRMED. Severity: P1.**

My evidence: `ConfigdServer.java:533-534` constructs the exporter with the single-arg ctor
`new PrometheusExporter(metricsRegistry)`, which sets `schedules = Collections.emptyMap()`
(`PrometheusExporter.java:83-85`); `_bucket{le=...}` lines are emitted only for histograms present
in `schedules` (`PrometheusExporter.java:117+`). The prepared schedule map
`ConfigdMetrics.histogramSchedules()` (`ConfigdMetrics.java:150-157`) has **zero callers** in
src/main. Alerts referencing `_bucket` series: `ops/alerts/configd-slo-alerts.yaml:23,42,66,86,103,
119` — 6 of the 9 alert rules (both write-commit burn rates, both edge-read burn rates, edge-read
p999, propagation) query series that are never emitted.

Register-ready: The production exporter is constructed without bucket schedules, so no
`_bucket{le=...}` time series is ever emitted; the schedule map written to match the alert
thresholds exists in code but is never passed in. Six of nine SLO alert rules and every
histogram-based dashboard panel query series that cannot exist. Combined with OPS-1, the entire
alerting surface is inert. P1.

### MX-1 — Per-entry HLC timestamps are fiction
**VERDICT: CONFIRMED. Severity: P1.**

My evidence: `LogEntry` is `record LogEntry(long index, long term, byte[] command)`
(`LogEntry.java:13`) — no timestamp of any kind. `HybridClock` exists
(`configd-common/.../HybridClock.java`) but its only consumers are a benchmark
(`HybridClockBenchmark.java`) and its own tests. The contract defines staleness off the missing
field: `docs/consistency-contract.md:48` ("Each Raft log entry carries an HLC timestamp… the
`StalenessTracker` computes the difference…"), `:119`, and INV-S1 (`:59-66`); cross-group ordering
claims (`:146-156`, architecture.md:60,70) also depend on it. `StalenessTracker.recordUpdate`
takes a timestamp parameter documented as "(informational)" (`StalenessTracker.java:96-98`) and
measures elapsed local time instead.

Register-ready: The contract's staleness bound (INV-S1/S2) and cross-group HLC ordering are defined
over a per-entry HLC timestamp that does not exist — `LogEntry` carries only (index, term, command)
and `HybridClock` has no production consumer. The §2 staleness guarantee is unimplementable as
specified and is not what the code measures. P1 (contract guarantee fiction).

### MX-2 — Contract §7's Wing & Gong concurrent checker doesn't exist as named
**VERDICT: CONFIRMED (with scope note). Severity: P1.**

My evidence: contract §7 (`docs/consistency-contract.md:193`) maps INV-L1 to `LinearizabilityTest`:
"concurrent writes + reads … verify history is linearizable using Wing & Gong algorithm." The
actual `LinearizabilityTest` (`ConsistencyPropertyTests.java:234-280+`) is two scripted,
single-threaded, sequential scenarios (write → readIndex → assert) — no concurrent history, no
checker. No checker exists in configd-testkit (grep). Scope note: `configd-linz` (session A3-B)
does contain a `PorcupineChecker` (`configd-linz/src/main/java/io/configd/linz/check/`), but it is
a separate harness present only on the session branches — absent from local main (`c23cd34`) and
origin/main — and is not the contract's named test; the contract text was not updated.

Register-ready: The consistency contract claims INV-L1 is verified by a Wing&Gong-style
linearizability check over concurrent histories; the named test is actually two scripted
single-threaded scenarios with no checker, and no checker exists in the testkit. A real checker
harness (configd-linz, Porcupine-style) exists only on an unmerged session branch and is not wired
to the contract or CI. P1 (the contract's central verification claim is false as written).

### MX-3 — ADR-0023 verification section cites nonexistent `shardId` field and `configd_raft_groups_total` metric
**VERDICT: CONFIRMED. Severity: P2 (ADJUSTED from P1 grouping — dispute recorded).**

My evidence: `docs/decisions/adr-0023*.md:37,60-62` claims "`ClusterConfig` schema reserves the
`shardId` field," "asserted by … ClusterConfigTest.java," and "Operator check:
`configd_raft_groups_total` gauge equals 1." Reproduction: `shardId` occurs in **zero** Java files
(including `ClusterConfig.java` and `ClusterConfigTest.java` — 0 matches); `raft_groups` occurs in
zero Java/YAML files. Both cited verification artifacts are fabrications.

Severity-dispute note: submitted in the P1 group; my call is P2 — no runtime behavior depends on
the fiction, but it is fabricated verification evidence inside a ratified ADR, which matters to
this audit's evidence rule. Disagreement recorded.

Register-ready: ADR-0023's "testable via / operator check" section cites a `ClusterConfig.shardId`
field, a test asserting it, and a `configd_raft_groups_total` gauge — none of which exist anywhere
in the codebase. The ADR's verification claims are fabricated and the single-group invariant has no
runtime check. P2 (evidence integrity).

### MX-5 — Ledger R-13 cites the wrong snapshot-drop threshold (16 MiB; binding cap is 4 MiB)
**VERDICT: CONFIRMED. Severity: P2 (ADJUSTED from P1 grouping — dispute recorded).**

My evidence: `RaftMessageCodec.MAX_SNAPSHOT_BLOB_LEN = 4 * 1024 * 1024` with per-blob IAE in
`checkBlobLen`/`checkInstallSnapshotFitsFrame` (`RaftMessageCodec.java` — blob cap checked
*before* the combined 16 MiB `FrameCodec.MAX_FRAME_SIZE` check, which only binds past 8 MiB+ of
combined blobs). The IAE propagates through `RaftTransportAdapter.send` to the silent-drop catch at
`RaftNode.java:1298-1305` (stderr only; `sendInstallSnapshot` is single-shot `offset=0,done=true`,
`:1288-1290`). `docs/READINESS-LEDGER.md:132` (R-13) states the cliff is "> 16 MiB" and explicitly
"corrects the docs' 4 MiB framing" — the correction went the wrong way: the binding threshold for
the snapshot data blob is 4 MiB.

Severity-dispute note: submitted in the P1 group; my call is P2 — the underlying liveness cliff is
already tracked as open R-13; this finding fixes the recorded threshold (4× earlier than believed),
which re-prioritizes R-13 but is itself a ledger-accuracy defect. Disagreement recorded.

Register-ready: The ledger's R-13 entry records the InstallSnapshot silent-drop cliff at the 16 MiB
wire-frame cap; the actually-binding limit is the 4 MiB per-blob cap in RaftMessageCodec, hit first
and surfacing through the same silent-drop path at RaftNode.java:1298-1305. The liveness cliff
arrives at one quarter of the documented size. P2 correction to an open P1-class risk (R-13).

### SW-1 — SeedSweepTest.commitSurvivesLeaderFailure has 3 silent vacuous-pass paths
**VERDICT: CONFIRMED (cross-verified ×2 in Phase A; spot-check passed). Severity: P1.**

My evidence: `configd-testkit/src/test/java/io/configd/testkit/SeedSweepTest.java:65-68` (no leader
elected → `return`), `:72-75` (commit timeout → `return`), `:85-88` (no new leader → `return`) —
each bare `return` records a green test with zero assertions executed. Combined with HF-2 (the seed
doesn't control election timing), there is no lower bound on how many of the 10,000 "passes"
exercised the actual property. JUnit `Assumptions`/aborted-test reporting is not used, so vacuous
runs are indistinguishable from real ones.

Register-ready: The flagship 10k-seed durability sweep silently returns (passing green) whenever
leader election, commit, or re-election doesn't happen in time — three escape hatches with no
counter, assumption, or abort marker. The sweep's pass count therefore proves nothing about how
often the committed-write-survives-failover property was actually checked. P1 (core safety evidence
can be vacuous).

---

## P2-boundary severity opinions (single-verifier calls, no dual repro required)

| Item | My evidence | Severity opinion |
|---|---|---|
| SpotBugs report-only with RaftNode concurrency warnings | `pom.xml:151` `<failOnError>false</failOnError>` (effort Max, threshold Medium, `spotbugs` goal bound to verify); live report `configd-consensus-core/target/spotbugsXml.xml`: **19** BugInstances, all primary class `io.configd.raft.RaftNode`, all MT_CORRECTNESS (16 AT_STALE_THREAD_WRITE_OF_PRIMITIVE, 4 AT_NONATOMIC_OPERATIONS_ON_SHARED_VARIABLE, 2 AT_NONATOMIC_64BIT — note: 19, not 18 as submitted) | **P2.** Post-R-01 single-writer marshalling makes many benign, but non-volatile fields (`leaderId`, `role`, term) are read cross-thread by health checks/HTTP (`ConfigdServer.java:417-422`), so the warnings are not all noise; the gate being report-only means regressions land silently. |
| configd-linz harness absent from CI | `grep -i linz .github/workflows/ci.yml` → 0; ci.yml `-pl` lists exclude the module; module exists in root `pom.xml:24` on this branch only | **P2**, with the note that R-04's closure is not durable until the harness runs in CI; absent that, it should block R-04's "closed" status rather than the release. |
| ADR-0030 still "Proposed" | `docs/decisions/adr-0030*.md:3-5`: "**Proposed** (under review). Not yet Accepted." while Session 0 work and doc decontamination already build on it | **P2.** Governance drift: the topology the whole plan assumes is unratified. |
| TLC liveness never checked | `ConsensusSpec.cfg:40-45`: liveness block (`PROPERTIES EdgePropagationLiveness`) commented out with "uncomment … expect 10x+ runtime"; `ReadIndexSpec.cfg`/`SnapshotInstallSpec.cfg`: no PROPERTIES at all; `spec/tlc-results.md` reports invariants (safety) only | **P2.** All TLC evidence is safety-only; no temporal property has ever been model-checked. Should be stated wherever TLC results are cited. |
| @Buggify: 0 call sites vs ADR-0007 "~1000" | `Buggify.java`/`BuggifyRuntime.java` exist in configd-common; repo-wide grep: zero call sites anywhere (no file outside configd-common references Buggify; none inside uses it either); `adr-0007:28` claims "~1000 injection points" | **P2.** Capability fiction in a ratified ADR; the DST story has no fault-injection surface at all. |
| 15 committed TLC failure-trace artifacts | `git ls-files spec/ | grep -c TTrace` → 15 `.bin` (+ 4 trace `.tla`, + `spec/states/`); `grep TTrace spec/tlc-results.md` → 0 — no document explains which failures they captured or that they were fixed; ledger:153 already flags them as cruft | **P3.** Hygiene/provenance polish; remove or document. |
| CI bare `mvn` vs release `./mvnw` | `ci.yml:29,32,35,38` use unpinned `mvn`; `release.yml:49,56,60` use `./mvnw` | **P3** (toolchain-pinning inconsistency; CI results can drift from release builds — cheap fix, low present risk). |

---

## Verdict summary

| ID | Verdict | Final severity | Dispute? |
|---|---|---|---|
| P0-1 | CONFIRMED | P0 | — |
| P0-2 / CF-21 | CONFIRMED | P0 | ledger R-15 carried it as 🟡/deferred — recorded |
| P0-3 / CF-42 | CONFIRMED | P0 (latent; fix jointly with CF-43) | decision requested, justified above |
| P0-4 / CF-44 | CONFIRMED | P0 | Phase A split P0/P1 → my call P0 |
| BI-1 | CONFIRMED | P1 | — |
| BI-2 | ADJUSTED | P2 (was P1) | yes — recorded |
| CF-43 + CF-40 | CONFIRMED | P1 (time-bomb → P0) | — |
| CF-50 | CONFIRMED | P1 | — |
| CF-22 | CONFIRMED (worse: per entry) | P1 | — |
| CF-29 | CONFIRMED | P1 | — |
| HF-1 | CONFIRMED (alloc site named) | P1 | nuance noted (VDR-0001 partial concession) |
| HF-2 | CONFIRMED | P1 | — |
| HF-3 | CONFIRMED | P1 | — |
| OPS-1 | CONFIRMED (broader: all 9 SLO metrics dead) | P1 | — |
| OPS-2 | CONFIRMED | P1 | — |
| MX-1 | CONFIRMED | P1 | — |
| MX-2 | CONFIRMED (scope note re configd-linz) | P1 | — |
| MX-3 | ADJUSTED | P2 (was P1 group) | yes — recorded |
| MX-5 | ADJUSTED | P2 (was P1 group) | yes — recorded |
| SW-1 | CONFIRMED | P1 | — |

No candidate failed reproduction; nothing was REFUTED.
