# Findings — Design vs. Reality (Configd)

**Teammate:** design-vs-reality · **Mode:** READ-ONLY · **Date:** 2026-06-06
**Method:** Every substantive doc claim located in code and classified. Tags are literal:
`[VERIFIED-PASS]` `[VERIFIED-FAIL]` `[EXISTS-UNTESTED]` `[DOC-ONLY]` `[ABSENT]`.
Audit/certification docs are NOT treated as evidence for themselves.

**Checks I actually ran:** full reactor `./mvnw -o test` -> **exit 0, 21,394 tests, 0 failures, 0 errors**
(surefire XML aggregate). Structural greps for wiring/orphans. Read of the server bootstrap,
Raft core, config store, Plumtree, transport, and the docs in my brief.

---

## Bottom line (5 bullets)

- **The single-node control plane is largely real; the "globally distributed edge data plane" is largely paper.**
  Honest split of the ~19k LOC main: roughly **55-60% real and wired** (Raft consensus, MVCC config store + lock-free
  read path, HTTP API, TCP+TLS Raft transport, observability, HLC), **~20% real-but-orphaned library code**
  (Plumtree, HyParView, CatchUpService, ReplicationPipeline, SnapshotTransfer, FlowController, edge-cache),
  **~20-25% pure paper** (hierarchical/multi-region Raft, closed-timestamp follower reads, live edge fan-out,
  the named consistency-contract property suite, the Netty/Spring/OTel/gRPC/JCTools/Agrona stack).
- **The headline architecture -- hierarchical Raft (global + regional groups), Plumtree fan-out to edges, multi-region --
  is NOT what runs.** `ConfigdServer` composes a runnable system, but it registers exactly **one** Raft group (`group 0`),
  routes every scope to it, and the Plumtree/HyParView/FanOutBuffer it builds form a **dead end**: deltas are appended to a
  buffer nothing reads, and `PlumtreeNode.broadcast()` is **never called** in non-test code. There are **no edge nodes, no
  cross-region replication, and no wire path from the control plane to any edge.**
- **The consistency contract over-promises.** Its 7 maps every invariant to a named property test
  (`LinearizabilityTest`, `StalenessUpperBoundTest`, `MonotonicReadFailoverTest`, ...) -- **none of those 9 classes exist**,
  and there is **no linearizability checker** anywhere (no Wing-&-Gong/Jepsen). Edge-staleness/RYW/monotonic-on-failover
  guarantees ride on a data plane that isn't wired. The Raft-side invariants (`sequence_monotonic`, `sequence_gap_free`) ARE
  real runtime assertions in `ConfigStateMachine`.
- **The mandated tech stack is mostly absent.** No `io.netty`, `org.springframework`, `io.grpc`, `io.opentelemetry`,
  `org.jctools`, or `org.agrona` anywhere in `src/main` (verified by grep, zero hits). Transport is blocking
  `java.net.Socket`/`SSLSocket` (thread-per-connection); the HTTP API is JDK `com.sun.net.httpserver`. ADR-0010
  ("netty-grpc-transport") and ADR-0026 ("opentelemetry-interop-stub") are titled for tech the code does not use.
- **The genuinely strong, verified core:** Raft (PreVote, CheckQuorum, leadership transfer/TimeoutNow, ReadIndex,
  InstallSnapshot, joint-consensus reconfiguration), the lock-free volatile-snapshot MVCC read path, the CRC32C framed
  wire protocol, and the TLA+ ConsensusSpec (13.7M states, 8 invariants, TLC PASS) are real, wired, and tested. The docs are
  honest about *empirical* gaps (`known-limitations.md`) but **silent about the *architectural* gaps above** -- that silence
  is the danger.

---

## Component map

| Component | Classification | Best evidence pointer | Real or skeleton? |
|---|---|---|---|
| **consensus** (Raft) | `[VERIFIED-PASS]` | `RaftNode.java:392` (readIndex), `:299` (transferLeadership), `:514` (proposeConfigChange/joint), `:949` (PreVote); `RaftNodeTest`/`ReconfigurationTest`/`ReadIndexStateTest` green | **Real.** Single-group Raft with the full feature set, exercised by tests + TLA+. |
| **replication** (engine) | `[EXISTS-UNTESTED]` (as a *replication engine*) | `MultiRaftDriver.java:67` is a generic group container; `ReplicationPipeline/SnapshotTransfer/FlowController/HeartbeatCoalescer` are **orphans** (only their own tests reference them) | **Skeleton.** Only `MultiRaftDriver` is wired, and only with 1 group. No log-shipping/batching/flow-control on the live path; no cross-region. |
| **config-store** | `[VERIFIED-PASS]` | `VersionedConfigStore.java:42` (volatile snapshot), `:189` (lock-free get), `HamtMap`; `VersionedConfigStorePropertyTest`/`...ConcurrencyTest`/`...AllocationTest` green | **Real.** MVCC, immutable HAMT, single-writer/multi-reader, zero-alloc miss. Matches the read-path design. |
| **edge-cache** | `[EXISTS-UNTESTED]` | `LocalConfigStore`, `EdgeConfigClient.java:130` (applyDelta), `StalenessTracker`, `BloomFilter`, `PoisonPillDetector`; unit-tested | **Real library, orphaned at runtime.** No server/distribution/api code references `io.configd.edge`. Edges exist only inside tests. |
| **distribution** (fan-out) | `[EXISTS-UNTESTED]` library / `[ABSENT]` live path | `PlumtreeNode.java:117` (broadcast -- never called outside tests/bench), `HyParViewOverlay`, `CatchUpService` (orphan), `FanOutBuffer.append` fed but never drained (`ConfigdServer.java:301`) | **Real protocol code, dead-ended.** Plumtree/HyParView are correct implementations but never broadcast real deltas; FanOutBuffer is a write-only sink. |
| **transport** | `[VERIFIED-PASS]` (as plain TCP/TLS) / `[ABSENT]` (as "Netty/gRPC") | `FrameCodec.java` (len+version+type+CRC32C), `TcpRaftTransport.java:6-19` (`java.net`/`SSLSocket`, blocking); `FrameCodec*Test`/`TcpRaftTransportTest` green | **Real wire protocol + transport, but NOT Netty/gRPC.** Thread-per-connection blocking sockets. Carries Raft only; distribution does not use it. |
| **control-plane** (API) | `[VERIFIED-PASS]` (as JDK HTTP) / `[ABSENT]` (as Spring Boot) | `HttpApiServer.java:74-82` (GET/PUT/DELETE/health/metrics via `com.sun.net.httpserver`); `ConfigWriteService.java:147` | **Real REST endpoints, not Spring.** ACL/auth/rate-limit present. `put(scope,...)` exists but scope is collapsed to group 0 downstream. |
| **server** (bootstrap) | `[VERIFIED-PASS]` (runs) / claims **partially** `[VERIFIED-FAIL]` | `ConfigdServer.java:154` (`start`), `:759` (`main`), tick loop `:518`; `ConfigdServerTest` green | **Composes & runs a single node.** But it wires ONE Raft group, no regions, and a fan-out path that goes nowhere -- so it is NOT the architecture the docs describe. |
| **testkit** (sim/bench) | `[VERIFIED-PASS]` sim / `[EXISTS-UNTESTED]` bench numbers | `RaftSimulation`/`SimulatedNetwork` (seeded, replayable); 9 JMH benchmarks under `src/main/java/io/configd/bench/` | **Real deterministic sim + runnable JMH.** But the perf *numbers* in `performance.md` are self-flagged "MODELED, NOT MEASURED" for this commit. |
| **observability** | `[VERIFIED-PASS]` | `InvariantMonitor`, `SloTracker`, `PrometheusExporter`, `MetricsRegistry` (HdrHistogram); wired in `ConfigdServer.java:311-321`; tests green | **Real & wired.** Prometheus exposition, SLO burn-rate, invariant counters all instantiated in the server. |
| **spec** (TLA+) | `[VERIFIED-PASS]` | `spec/ConsensusSpec.tla` + `spec/tlc-results.md` (13,775,323 states, 8 invariants PASS, depth 25); `ReadIndexSpec`, `SnapshotInstallSpec` + replayer tests | **Real.** Substantive spec with documented bug-finding; runtime replayers tie it to code. Does NOT cover edge propagation. |

---

## Claims table (doc claim -> classification -> implementing code)

| Doc claim (file:line) | Classification | Implementing code (file:line) or "none" |
|---|---|---|
| Hierarchical Raft: GLOBAL group (5 voters/3 regions) + REGIONAL groups (3 voters) -- `architecture.md:27-31,137-164` | `[VERIFIED-FAIL]` / `[DOC-ONLY]` | `ConfigdServer.java:251-252` registers exactly one group (`DEFAULT_RAFT_GROUP=0`). No global/regional/region concept in main code. `research.md:618` itself says "Single Raft group". |
| Scope-based routing (GLOBAL/REGIONAL/LOCAL -> different groups) -- `architecture.md:166-171`, `consistency-contract.md:104` | `[DOC-ONLY]` | Interface exists (`ConfigWriteService.java:118,147` takes `scope`), but `ConfigdServer.java:368-371` proposer ignores scope and always proposes to group 0. |
| Plumtree eager/lazy fan-out, IHAVE/GRAFT/PRUNE -- `architecture.md:236-251`, ADR-0003 | `[EXISTS-UNTESTED]` (library) / `[ABSENT]` (live) | `PlumtreeNode.java:40-262` is a real, complete protocol impl with `PlumtreeNodeTest`. But `broadcast()`/`drainOutbox()` are **never called** outside tests/bench (grep). |
| Live write->edge propagation; "control plane -> data plane via committed log entries" -- `architecture.md:42,72-74` | `[VERIFIED-FAIL]` | `ConfigdServer.java:301` appends `ConfigDelta` to `FanOutBuffer`; **nothing drains it** (grep: only `append`, `import`, field). No code moves deltas to Plumtree/transport/edges. |
| HyParView overlay for peer discovery -- `architecture.md:159,238`, ADR-0016 | `[EXISTS-UNTESTED]` | `HyParViewOverlay.java` + test; wired to Plumtree peer-set in `ConfigdServer.java:279-285`, but the overlay has no real peers (no edges connect). |
| Multi-region: core/regional/edge tiers, non-voting replicas, RTT matrix -- `architecture.md:181-205,426-436`, ADR-0015 | `[DOC-ONLY]` | grep "region/Region/non-voting/nonVoter" in `src/main` -> only `ConfigScope` enum, one SLO string, one benchmark. No multi-region code. |
| Closed-timestamp follower reads (~3s staleness, 200ms side-transport) -- `architecture.md:190-196` | `[ABSENT]` | grep "closedTimestamp/closed.?timestamp/closed ts" -> no hits in `src/main`. |
| Catch-up protocol (WAL deltas / chunked snapshot 1MB+CRC, resume) -- `architecture.md:261-265` | `[EXISTS-UNTESTED]` (orphan) | `CatchUpService.java` + `SnapshotTransfer.java` exist with tests, but neither is referenced by server/distribution main code (grep -> orphan). |
| Slow-consumer policy (credits, quarantine, 3-strikes) -- `architecture.md:277-283` | `[EXISTS-UNTESTED]` | `SlowConsumerPolicy.java` + test; instantiated in `ConfigdServer.java:273` but never consulted (no live consumers to police). |
| Edge bounded staleness < 500ms p99 with CURRENT->STALE->DEGRADED -- `consistency-contract.md:38-57` | `[EXISTS-UNTESTED]` (tracker) / `[DOC-ONLY]` (guarantee) | `StalenessTracker.java` + test compute states correctly, but it is never wired into a serving edge; the p99 bound is unmeasured (`known-limitations.md:38-54`). |
| Monotonic-read-on-failover; RYW via intra-region Plumtree -- `consistency-contract.md:84-89,162-184` | `[DOC-ONLY]` | `VersionCursor.java` exists; failover/RYW depend on the unwired edge+fan-out path. No `MonotonicReadFailoverTest`/`ReadYourWritesTest` class exists. |
| "Every invariant maps to a property test in testkit/" (9 named classes) -- `consistency-contract.md:188-203` | `[ABSENT]` | None of the 9 classes exist (`find` -> NOT FOUND for all). No linearizability checker (Wing&Gong/Jepsen) anywhere. |
| Runtime assertions: `sequence_monotonic`, `sequence_gap_free` on apply thread -- `consistency-contract.md:212-213` | `[VERIFIED-PASS]` | `ConfigStateMachine.java:261-296` calls `invariantChecker.check("sequence_monotonic"/"sequence_gap_free", ...)`. Real, wired, tested. |
| ReadIndex linearizable reads (record commit idx, confirm leadership, wait apply) -- `consistency-contract.md:16-20` | `[VERIFIED-PASS]` | `RaftNode.java:392-433` + `ConfigdServer.java:453-490` full dispatch; `ReadIndexStateTest`/`ReadIndexLinearizabilityReplayerTest` green. |
| Transport = Netty, framed, mTLS, zero-copy -- ADR-0010, `rewrite-plan` | `[VERIFIED-PASS]` (framed+mTLS) / `[ABSENT]` (Netty/zero-copy) | `FrameCodec.java` (CRC32C frame) + `TcpRaftTransport.java` blocking `SSLSocket`. No `io.netty` in `src/main` (grep). |
| Control plane = Spring Boot -- `PROMPT Output5`, `architecture.md:30` | `[ABSENT]` | `HttpApiServer.java` uses `com.sun.net.httpserver`. No `org.springframework` in `src/main`. |
| JCTools MPSC/SPSC, Agrona off-heap on hand-off -- `PROMPT`, `performance.md:60` | `[ABSENT]` | No `org.jctools`/`org.agrona` in `src/main`. `performance.md:60` self-admits "planned, not yet integrated". |
| OpenTelemetry tracing -- `PROMPT`, ADR-0026 | `[ABSENT]` (real OTel) | ADR-0026 titled "interop-stub"; no `io.opentelemetry` in `src/main`. |
| Reactor green: 21,394 tests / 0 fail / 0 err on JDK25 -- `known-limitations.md:21-24` | `[VERIFIED-PASS]` | Ran `./mvnw -o test` -> exit 0; surefire aggregate = 21,394 / 0 / 0. |
| TLA+ TLC PASS, 8 invariants, 13.7M states -- `known-limitations.md:28`, `spec/tlc-results.md` | `[VERIFIED-PASS]` (artifact) | `spec/ConsensusSpec.tla`, `spec/tlc-results.md`; replayer tests (`SnapshotInstallSpecReplayerTest`) green. (TLC not re-run by me; artifact + replayers verified.) |
| JMH perf numbers (HAMT p50 80ns, Raft 815K ops/s, etc.) -- `performance.md:93-174` | `[EXISTS-UNTESTED]` | Benchmarks real & runnable (`testkit/src/main/.../bench/*`), but doc self-flags **"MODELED, NOT MEASURED -- P-017"** for this commit; no `perf/results/jmh-<sha>/` artefact. |
| Surpass-Quicksilver scorecard: 4/4 axes SURPASSED -- `gap-analysis.md:243-252` | `[DOC-ONLY]` | Verdicts rest on CPU microbench + modeled network math; the edge-staleness and cross-region-latency axes depend on the unwired data plane / absent multi-region. Not end-to-end measured. |
| InstallSnapshot chunking via offset/done -- `architecture.md:264`, wire format | `[VERIFIED-FAIL]` (chunking) | `known-limitations.md:84-90` concedes 4 MiB cap; offset/done fields "currently ignored by the leader". Honest concession. |
| Single-writer/multi-reader, no locks on read path -- `architecture.md:119-122`, `performance.md:53` | `[VERIFIED-PASS]` | `VersionedConfigStore.java:189-197` (one volatile read, no lock/CAS); `LocalConfigStore` mirrors it; concurrency/allocation tests green. |

---

## Worst doc/reality gaps (ranked by danger to a trusting reader)

1. **"Globally distributed" / multi-region replication is absent, yet the architecture, consistency contract, and
   scorecard all assert cross-region guarantees.** A reader provisioning this as a multi-region config plane gets a
   **single Raft group on one logical cluster** with no cross-region replication, no closed-timestamp follower reads, no
   non-voting replicas. Evidence: `architecture.md:181-205` vs. grep (region concept absent in `src/main`);
   `research.md:618` even contradicts `architecture.md` ("Single Raft group"). **Danger: deploy-shaped false promise.**

2. **The edge data plane (Plumtree fan-out to edges) does not run.** Docs promise push-based < 500ms global propagation;
   the server appends deltas to a `FanOutBuffer` that **nothing drains** and never calls `PlumtreeNode.broadcast()`.
   Every edge-staleness, monotonic-read-on-failover, and read-your-writes guarantee in `consistency-contract.md` is
   therefore **untested against a live pipeline**. Evidence: `ConfigdServer.java:301` (append-only), grep (no broadcast
   caller), `EndToEndTest` glues store->edge **by hand in test code**. **Danger: the system's headline capability is paper.**

3. **The consistency contract claims a property-test suite that does not exist.** 7 names 9 test classes (incl. a
   linearizability checker) "in testkit/"; **zero** exist, and there is **no linearizability checker at all**. The Raft
   *sequence* invariants are genuinely asserted+tested, but "linearizability verified" is unsupported. Evidence:
   `consistency-contract.md:188-203` vs. `find` (all NOT FOUND). **Danger: a correctness claim with no backing test.**

4. **Mandated production stack (Netty/Spring/gRPC/OTel/JCTools/Agrona) is absent; ADR titles imply otherwise.**
   Transport is blocking thread-per-connection sockets; HTTP is JDK's built-in server. The throughput/tail/GC arguments
   that lean on Netty zero-copy + JCTools hand-off + ZGC tuning are not validated against the actual stack. Evidence:
   grep (zero hits in `src/main`); `performance.md:60` self-admits JCTools "not yet integrated". **Danger: perf model
   assumes machinery that isn't there.**

5. **Perf "SURPASSES Quicksilver, 4/4" rests on modeled numbers the perf doc itself disclaims.** `performance.md:80-91`
   labels the measured column "MODELED, NOT MEASURED" with a placeholder results dir; `known-limitations.md:38-54` says no
   soak/JMH/allocation profiling was run. The scorecard presents these as wins. **Danger: marketing verdict on
   unreproduced data.** (Lower rank because the perf doc is at least internally honest if you read the disclaimer.)

---

## Cross-examination requests for peers

- **To consensus-correctness:** Confirm/refute that joint-consensus reconfiguration in `RaftNode.java:514-574`
  (and the `recomputeConfigFromLog` path `:669-700`) is safe under leader change *mid-joint-config*, and that the
  single-group-only wiring doesn't hide a latent dual-group routing bug. Also: does ReadIndex
  (`RaftNode.java:392`) correctly gate on `lastApplied >= readIndex` for a *single-node* group where there's no
  heartbeat quorum to confirm? (`ConfigdServer.java:453-490` is the live caller.)
- **To concurrency-readpath:** Confirm/refute that `VersionedConfigStore` (volatile `currentSnapshot`, `:42/:103/:189`)
  and `ConfigStateMachine`'s listener fan-out (`ConfigdServer.java:288-306`, which calls `configStore.snapshot()` and
  `fanOutBuffer.append` from the apply/tick thread) are free of read-path locks AND of cross-thread visibility hazards
  for the HTTP read threads (`HttpApiServer` reads via `configReader` `ConfigdServer.java:427-432`). I claim the read
  path is genuinely lock-free; please stress the *publication* edge.
- **To verification-evidence:** I assert the 9 named property tests in `consistency-contract.md:188-203` do not exist and
  no linearizability checker exists. Please independently confirm, and assess whether any *existing* test
  (`VersionedConfigStorePropertyTest`, `RaftSimulationTest`, `SeedSweepTest`) actually checks a linearizability or
  edge-staleness *invariant*, or only per-component behavior. Also confirm the perf numbers in `performance.md:93-174`
  have no reproducible artefact under `perf/results/`.

---

## Phase 2 — Cross-examination

I am the integrator; all three hinge on `configd-server` wiring the peers couldn't see. Verdicts below
with literal tags, file:line, and the construction traces I followed.

### CX-1 — Runtime invariant checkers in a running server: **AGREE with consensus-correctness. I REFINE my own tag.**

Both checkers are NOOP in production. Distinct objects/types, both disabled:

- **RaftNode checker:** `ConfigdServer.java:248` passes `RaftNode.InvariantChecker.NOOP` → every
  `invariantChecker.check(...)` inside RaftNode is a no-op. (consensus-correctness correct.)
- **ConfigStateMachine checker (the one I called live):** the server builds it at `ConfigdServer.java:188`
  via `new ConfigStateMachine(configStore, clock, configSigner)` → the `(store, clock, ConfigSigner)`
  ctor `ConfigStateMachine.java:161` delegates with `invariantChecker = null` (`:162`) → field defaults to
  `InvariantChecker.NOOP` (`:136`). So `sequence_monotonic` / `sequence_gap_free` / `per_key_order`
  (`:262/:265/:270…`) are **also NOOP at runtime.**
- These are **two distinct types**: `RaftNode.InvariantChecker` and `ConfigStateMachine.InvariantChecker`
  (separate nested functional interfaces, each with its own `NOOP`). Neither is wired to a real impl.
- **The real impl `InvariantMonitor` (observability) is instantiated NOWHERE in the server** (grep: only its
  own file + tests). It has a matching `check(String,boolean,String)` signature but nothing adapts it into
  either state machine or RaftNode in production. So its "test-mode throws / prod-mode records metric"
  behavior never runs in `ConfigdServer`.

**Definitive list — runtime invariant assertions LIVE in production:** *none.* All of
`sequence_monotonic`, `sequence_gap_free`, `per_key_order` (ConfigStateMachine) and every RaftNode
INV-* check are NOOP in a running `ConfigdServer`. They fire ONLY in tests, which pass a throwing checker.

**Tag correction:** I previously tagged `ConfigStateMachine.java:261-296` `[VERIFIED-PASS]` ("real, wired,
tested"). That was correct for *the assertion logic and its test coverage*, but **WRONG about "wired" in a
running server.** Corrected classification of the production wiring:
`[VERIFIED-FAIL]` — *consistency-contract §8 claims these assertions are a live production safety net
("metric-mode in production"); in `ConfigdServer` they are NOOP.* The assertion *code* remains
`[VERIFIED-PASS]` only under test, where a real checker is injected. This is genuine design/reality drift and
strengthens consensus-correctness's row "Runtime InvariantChecker enforces TLA+ invariants in production →
VERIFIED-FAIL".

### CX-2 — R-1 (`ReadResult.value()` returns live HAMT array): **REFINE concurrency-readpath: latent, not live → `[EXISTS-UNTESTED]`.**

The escape is real (the array in `ReadResult` IS the live HAMT internal array: `VersionedConfigStore.java:196`
passes `vv.valueUnsafe()`, and `ReadResult.value()` `ReadResult.java:56-58` returns it by reference, no copy).
But I traced **every** caller across server/api/distribution/observability/edge:

- **The only consumer of `ReadResult.value()` is `HttpApiServer.java:251`:** `byte[] value = result.value();`
  then `os.write(value)` (`:257`). **Read-only.** Not mutated, not written into, not handed to a pooled/reused
  buffer.
- The other `.value()` grep hits (`PrometheusExporter.java:111/115/138/149`) are `Metric.value()` returning a
  `long` — a different type, irrelevant.
- No caller in `configd-control-plane-api` / `configd-distribution-service` calls `ReadResult.value()` at all.

**Verdict: `[EXISTS-UNTESTED]` (latent hazard).** A copy-free path from HAMT internals to a caller exists and
no test asserts `value()` returns a copy (concurrency-readpath correct on the asymmetry: `VersionedValue.value()`
clones, `ReadResult.value()` does not), but **no current caller mutates it**, so there is no live corruption
path today. concurrency-readpath's `[VERIFIED-PASS]` was for "path traced" — agreed the path is real; the
*bug-is-live* part is refuted. Danger is a future caller; worth a hardening fix.

### CX-3 — W-2 (non-volatile `ConfigStateMachine` fields read via getters): **REFUTE as a live visibility bug → `[EXISTS-UNTESTED]` (latent).**

The fields (`lastSignature` `:72`, `signingEpoch` `:79`, `lastNonce` `:85`, `lastEpoch` `:91`,
`sequenceCounter` `:107`) are non-volatile and written with plain stores on the apply path
(`:611-614`, `:274/:286/:298`). The getters (`:663/:671/:679/:689/:698`) are called from exactly one place in
production: the state-machine listener lambda at **`ConfigdServer.java:288-303`** (reads `lastSignature()` `:290`,
`lastEpoch()` `:293`, `lastNonce()` `:294`).

That listener is **not** a separate thread. `ConfigStateMachine` notifies listeners *synchronously inside
`apply()`* — class doc `:27-28`: "listener notification happens on the apply thread"; fire sites
`notifyListeners(...)` at `:276/:288/:300` run after the field writes at `:611-614`, **same thread, same call
stack, program order.** `apply()` itself runs on the single tick thread (`driver.tick()` on `tickExecutor`,
`ConfigdServer.java:518-520`). So the write→read is a same-thread program-order edge — **a happens-before edge
exists; no stale read is possible for this caller.** The getters also return defensive `clone()`s for the
byte[] (`:664/:680`).

**No production caller reads these getters from the HTTP request thread or any non-apply thread** (grep: the
only main-code callers are `ConfigdServer.java:290/293/294`). So W-2 is **not a live bug.**

**Verdict: `[EXISTS-UNTESTED]` (latent).** The fields *ought* to be `volatile` defensively (a future
cross-thread getter call — e.g., exposing `sequenceCounter()` on an HTTP status endpoint — would silently
become a visibility bug with no compiler warning), but today every read is on the apply/tick thread with a
program-order HB edge. concurrency-readpath's own `[EXISTS-UNTESTED]` "reachability depends on configd-server
wiring (out of scope here)" is the right tag; I confirm the reachability resolves to *safe today*.

**Net for Phase 2:** CX-1 escalates (a real VERIFIED-FAIL: no live runtime safety net — I correct my own
earlier over-credit). CX-2 and CX-3 de-escalate two peer bugs from live to latent (both `[EXISTS-UNTESTED]`),
because the single server-side caller of each is read-only / same-thread.
