# State of Reality — Configd (Quicksilver-class config system)

> **Read-only forensic assessment.** No code, specs, docs, or tests were modified.
> Produced by a four-lens agent team (consensus-correctness, concurrency-readpath,
> verification-evidence, design-vs-reality) that investigated independently, then
> cross-examined each other; the lead independently re-verified every load-bearing
> claim below. Date: 2026-06-06. JDK: Corretto 25.0.0.36.2. Build: `./mvnw` (Maven 3.9.9).
>
> **Every finding carries exactly one literal classification:**
> `[VERIFIED-PASS]` I ran it; it works · `[VERIFIED-FAIL]` I ran it; it failed/contradicts ·
> `[EXISTS-UNTESTED]` present but not run/triggered · `[DOC-ONLY]` described, no implementing code ·
> `[ABSENT]` claimed/expected, does not exist.
>
> Docs under `docs/audit/`, `docs/certification/`, `verification/`, `spec/tlc-results.md`,
> and `perf/results/` were treated as **claims to verify**, never as evidence.
> Per-lens evidence with full command output lives in
> `verification-runs/state-of-reality/findings-*.md`.

---

## 1. Honest verdict (one paragraph)

**Roughly half of this system is real and the other half is paper — and the verification
narrative oversells both.** The *single-node control plane* is genuinely real, runs, and is
tested green: a complete Raft implementation (1648-line `RaftNode.java` — PreVote, durable
term/vote, replication, quorum commit, joint-consensus reconfiguration, InstallSnapshot,
leadership transfer, ReadIndex), an MVCC config store with a genuinely lock-free read path,
a CRC32C-framed TCP+TLS transport, a JDK-HTTP control API, and wired observability. The full
reactor builds and `Tests run: 21,394, Failures: 0, Errors: 0, Skipped: 2` (`./mvnw -fae test`
→ BUILD SUCCESS), and TLA+ `ConsensusSpec` model-checks **green on a live re-run**
(13,775,323 states / 3,299,086 distinct / depth 25, all 9 invariants). That core is **~55–60%
of the ~19k LOC** and it is the real deal. **But the headline — a "globally distributed edge
data plane" — is largely paper:** the server registers exactly **one** Raft group, there is no
multi-region / hierarchical Raft / closed-timestamp follower-read code, the Plumtree/HyParView
fan-out is real library code that is **never invoked** (the `FanOutBuffer` deltas are appended
to and **never drained**; `PlumtreeNode.broadcast()` is called only in a benchmark), and no
wire path connects the control plane to any edge. **And the proof is thinner than advertised:**
~20,000 of the 21,394 tests are a single parameterized test; several TLA+ invariants are
tautological; the runtime invariant checkers are wired to **NOOP in production** (no live safety
net at all); "linearizability verified" has **no history checker**; the performance "surpasses
Quicksilver, 4/4" verdict rests on numbers the perf doc itself labels *"MODELED, NOT MEASURED"*;
and stale/imported verification artifacts are cited as current GA/certification evidence.
**The single most dangerous defect:** the verified-correct Raft is wired into the server in a
**thread-unsafe** way (inbound messages on per-connection virtual threads + a separate tick
thread, sharing an explicitly non-synchronized `RaftNode`), and **no test exercises it** — so the
algorithm is verified while its integration is both unsafe and unverified, precisely in the
multi-node mode the system exists for.

**Real-vs-paper split (design-vs-reality estimate, corroborated):** ~55–60% real & wired ·
~20% real-but-orphaned library code · ~20–25% pure paper.

---

## 2. Component evidence table

| Component | Classification | Best evidence (file:line) | Real or skeleton? |
|---|---|---|---|
| **consensus (Raft algorithm)** | `[VERIFIED-PASS]` | `RaftNode.java` 1648 lines: PreVote `:1077`, election `:1119`, durable vote `:938`, replication `:1221/:797`, quorum commit `:1319`, joint reconfig `:514`, ReadIndex `:392`; `ClusterConfig.java:117-123` dual-majority quorum; 159 tests, 0 fail, 2 honest `@Disabled` | **Real**, single-group, full feature set; exercised by 3- & 5-node partition tests + TLA+. |
| **consensus (runtime invariant enforcement)** | `[VERIFIED-FAIL]` (prod) | `ConfigdServer.java:248` wires `RaftNode.InvariantChecker.NOOP`; `:188` builds `ConfigStateMachine` via the `(store,clock,signer)` ctor → `invariantChecker=null` → `InvariantChecker.NOOP` (`ConfigStateMachine.java:161,136`) | **Disabled in production.** Check call sites exist; both are fed NOOP; `InvariantMonitor` is never wired in. |
| **config-store (MVCC)** | `[VERIFIED-PASS]` | `VersionedConfigStore.java:42` volatile snapshot, `:189` lock-free `get`; immutable `HamtMap`; `...ConcurrencyTest`/`...AllocationTest` green | **Real.** Immutable HAMT, single-writer/multi-reader, zero-alloc miss. |
| **edge-cache** | `[EXISTS-UNTESTED]` (orphan) | `LocalConfigStore`, `EdgeConfigClient.java:130`, `StalenessTracker`; **no** `src/main` outside tests references `io.configd.edge` | **Real library, not wired.** Edges exist only inside `EndToEndTest`, which glues store→edge by hand. |
| **distribution (Plumtree/HyParView fan-out)** | `[EXISTS-UNTESTED]` (lib) / `[ABSENT]` (live path) | `PlumtreeNode.broadcast()` called only at `PlumtreeFanOutBenchmark.java:62/76`; `FanOutBuffer.append` at `ConfigdServer.java:301` with **no draining reader** anywhere in `src/main` | **Real protocol code, dead-ended.** Write-only sink; nothing broadcasts real deltas. |
| **replication-engine** | `[EXISTS-UNTESTED]` | `MultiRaftDriver.java` is a generic group map; `addGroup(DEFAULT_RAFT_GROUP, …)` is the only registration; `ReplicationPipeline`/`SnapshotTransfer`/`FlowController`/`CatchUpService` are orphans (only self-tests reference them) | **Skeleton as a "replication engine."** One group, no cross-region, no live log-shipping/flow-control. |
| **transport** | `[VERIFIED-PASS]` (TCP/TLS) / `[ABSENT]` (Netty/gRPC) | `FrameCodec.java` len+ver+type+CRC32C; `TcpRaftTransport.java` blocking `SSLSocket`, `Executors.newVirtualThreadPerTaskExecutor()`; tests green. No `io.netty`/`io.grpc` in `src/main` | **Real wire protocol + blocking thread/virtual-thread-per-connection transport — not Netty/gRPC.** |
| **control-plane (API)** | `[VERIFIED-PASS]` (JDK HTTP) / `[ABSENT]` (Spring) | `HttpApiServer.java:74-82` GET/PUT/DELETE/health/metrics via `com.sun.net.httpserver`; ACL/auth/rate-limit present. No `org.springframework` in `src/main` | **Real REST endpoints, not Spring Boot.** `scope` accepted but collapsed to group 0 downstream. |
| **server (bootstrap)** | `[VERIFIED-PASS]` (runs single node) / `[VERIFIED-FAIL]` (as the documented architecture) | `ConfigdServer.java:154` start, `:759` main, tick loop `:518`; registers **one** group `:251-252`; fan-out path goes nowhere | **Composes & runs a single node.** Not the hierarchical/multi-region system the docs describe. |
| **testkit (DST + JMH)** | `[VERIFIED-PASS]` (sim) / `[EXISTS-UNTESTED]` (perf numbers) | `RaftSimulation.java:37`/`SimulatedNetwork.java:34` seeded `new Random(seed)`, reproducible across 2 runs; 9 JMH benches under `src/main/.../bench/`, `benchmarks.jar` runs | **Real seeded simulation + runnable JMH.** Published perf numbers are not backed by committed artifacts. |
| **observability** | `[VERIFIED-PASS]` | `MetricsRegistry`/`SloTracker`/`PrometheusExporter`/`PropagationLivenessMonitor` instantiated `ConfigdServer.java:307-321`; tests green | **Real & wired.** Prometheus exposition, SLO burn-rate, metric families. |
| **spec (TLA+)** | `[VERIFIED-PASS]` (green) / `[VERIFIED-FAIL]` (some invariants vacuous) | Live TLC: Consensus 3,299,086 distinct / ReadIndex 2,276,125 / Snapshot 847,124, all "No error". But `ReadIndexSpec.tla:237,251` consequent `TRUE`; `SnapshotInstallSpec.tla:173` is `A≤B ∨ A>B` | **Real, substantive spec that genuinely model-checks — but its safety claims are partly hollow.** Apalache `[ABSENT]`. |

---

## 3. What actually RUNS GREEN — verification scorecard

| Verification type | Classification | Command (as run) | Headline result |
|---|---|---|---|
| Full reactor (JUnit 5) | `[VERIFIED-PASS]` | `./mvnw -fae test` | **21,394 tests, 0 fail, 0 err, 2 skipped → BUILD SUCCESS** (390 surefire XMLs). Non-testkit modules = **1,262** tests. |
| Property-based (jqwik) | `[VERIFIED-PASS]` | full run; 64 `@Property` methods | Green. Caveat: **5** methods use `tries=1` (single example, not a sweep). |
| Deterministic simulation (DST) | `[VERIFIED-PASS]` | `./mvnw -pl configd-testkit test -Dtest=SeedSweepTest` ×2 | Identical `tests=20000 failures=0` both runs; seeding real & reproducible. |
| TLC — ConsensusSpec | `[VERIFIED-PASS]` | `java -cp spec/tla2tools.jar tlc2.TLC -config ConsensusSpec.cfg ConsensusSpec.tla` | 13,775,323 / 3,299,086 distinct / depth 25 / "No error". Reproduces documented counts exactly. |
| TLC — ReadIndexSpec / SnapshotInstallSpec | `[VERIFIED-PASS]` locally / `[EXISTS-UNTESTED]` in CI | live rerun (lead + consensus lens) | Both green live (2,276,125 / 847,124 distinct). **But CI (`ci.yml:61`) runs only ConsensusSpec.** |
| JMH benchmarks | `[VERIFIED-PASS]` (harness) | built `benchmarks.jar`; ran `HybridClockBenchmark` | Executes, real ns/op. 9 benchmark classes present. |
| Integration (EndToEnd) | `[VERIFIED-PASS]` | full run | `EndToEndTest` nested suites green — but glues store→edge **in test code**, not via the live fan-out path. |
| Wire-compat (golden) | `[VERIFIED-PASS]` (+2 `@Disabled` stubs) | full run | `FrameCodec*PropertyTest` green; 2 skips are honest stubs (R-005). |
| **Jepsen** | **`[ABSENT]`** | `find -iname '*jepsen*'` / `-name '*.clj'` → nothing | No Clojure project, no nemesis harness, 0 CI jobs. Word appears only in docs. |
| **Linearizability history checker** | **`[ABSENT]`** | grep Knossos/Elle/Wing-Gong/Porcupine → 0 | `LinearizabilityTest` is a **scripted single-threaded** write→readIndex→get visibility check, not a concurrent-history check. |
| **Perf result artifacts** | **`[DOC-ONLY]` / placeholder** | inspected `perf/results/*` | `jmh-…-PLACEHOLDER/` empty; `smoke/result.txt` = `YELLOW (no workload wired)`. `docs/performance.md` numbers self-flagged *"MODELED, NOT MEASURED"*. |

---

## 4. Doc-vs-reality gaps (worst first — what a trusting reader gets wrong)

1. **"Globally distributed" / multi-region replication is ABSENT.** `architecture.md:181-205`
   promises core/regional/edge tiers, non-voting replicas, RTT matrices, closed-timestamp
   follower reads (~3 s staleness). Reality: `ConfigdServer.java:251-252` registers **one** group;
   grep for `region`/`non-voting`/`closedTimestamp` in `src/main` → nothing. `research.md:618`
   even contradicts `architecture.md`: *"Single Raft group."* `[DOC-ONLY]` / `[ABSENT]`.
2. **The edge data plane does not run.** `architecture.md:42,72-74` promises push-based
   `<500 ms` global propagation via committed log → fan-out → edges. Reality:
   `ConfigdServer.java:301` appends `ConfigDelta` to a `FanOutBuffer` that **nothing drains**;
   `PlumtreeNode.broadcast()` is called only in `PlumtreeFanOutBenchmark`. Every edge-staleness /
   read-your-writes / monotonic-read-on-failover guarantee in `consistency-contract.md` therefore
   rides on an **unwired** pipeline. `[VERIFIED-FAIL]`.
3. **No live runtime safety net.** `consistency-contract.md` §8 implies runtime invariant
   assertions. Reality: **both** invariant checkers are NOOP in a running server
   (`ConfigdServer.java:248` RaftNode NOOP; `:188` ConfigStateMachine → null → NOOP via
   `ConfigStateMachine.java:136`); `InvariantMonitor` exists in observability but is **never
   connected**. `[VERIFIED-FAIL]`. (The assertion *logic* is real — and fires — **only** in unit
   tests that pass a throwing checker.)
4. **"Linearizability verified" is unsupported.** The 9 property classes named in
   `consistency-contract.md:188-203` **do exist** (as `@Nested` in `ConsistencyPropertyTests.java`,
   all green) — but **none implements a linearizability history checker**; `LinearizabilityTest`
   is a scripted single-threaded visibility test. `[DOC-ONLY]` for the linearizability claim.
   (This corrects an initial "the classes don't exist" finding — they exist, they just don't
   prove what the doc says.)
5. **Mandated production stack is absent.** No `io.netty`, `org.springframework`, `io.grpc`,
   `io.opentelemetry`, `org.jctools`, `org.agrona` anywhere in `src/main`. Transport is blocking
   sockets; HTTP is the JDK built-in server. ADR-0010 ("netty-grpc-transport") and ADR-0026
   ("opentelemetry-interop-stub") are titled for tech the code does not use; `performance.md:60`
   self-admits JCTools is *"planned, not yet integrated."* `[ABSENT]`.
6. **Perf "SURPASSES Quicksilver, 4/4"** (`gap-analysis.md:243-252`) rests on CPU microbenchmarks
   + modeled network math; the perf doc itself labels the measured column *"MODELED, NOT
   MEASURED"* and `known-limitations.md:38-54` concedes no soak/JMH/allocation profiling was run.
   `[DOC-ONLY]`.
7. **Stale / imported verification artifacts are cited as current proof.** `spec/tlc-results.md`
   lists the removed `NoStaleOverwrite` invariant and omits `LeaderCompleteness`/
   `VersionMonotonicity` (stale vs live `ConsensusSpec.cfg:36`); `spec/tlc-output.txt` &
   `verification-runs/tlc-rerun.log` reference a **different machine tree**
   (`/home/ubuntu/Programming/Configd`). These are then cited as evidence in
   `docs/certification/verdict.md:43,86`, `verification/final-report.md:93,310`, and
   `spec-code-map.md:31`; `ga-review.md:34` claims TLC-pass on **all 3** specs while CI runs only
   one. `[VERIFIED-FAIL]`. (Honest exception: `claims-register.md:99-100` marks TLC "Unverified".)
   The suite size is quoted as **four different numbers** across docs (20,132 / 20,149 / 21,246 /
   21,285) — none equals the live **21,394**.
8. **InstallSnapshot chunking** (`architecture.md:264`) — `known-limitations.md:84-90` concedes a
   4 MiB cap and that offset/done fields are *"currently ignored by the leader."* `[VERIFIED-FAIL]`
   on chunking (honest concession).

---

## 5. Top risks — ordered by where correctness is LEAST verified

> Ordering principle: a verified-correct *component* wired into the system in an unverified or
> unsafe *way* is more dangerous than an absent component, because it looks done.

1. **🔴 Multi-node concurrency race on `RaftNode` + `ConfigStateMachine` (algorithm verified,
   integration unsafe & untested).** Inbound Raft messages run on per-connection **virtual
   threads** (`TcpRaftTransport.java` `newVirtualThreadPerTaskExecutor()`; `acceptLoop` →
   `handleInboundConnection` → `inboundHandler.accept` → `driver.routeMessage` →
   `MultiRaftDriver.java:119` `node.handleMessage(message)` — **no lock, no marshalling**), while
   a **separate single tick thread** (`ConfigdServer.java:394` `configd-tick`) drives
   `driver.tick()` → `node.tick()`. `RaftNode` is explicitly *"single-threaded… No
   synchronization is used"* (`RaftNode.java:17-18`); `handleAppendEntries:851 → applyCommitted →
   stateMachine.apply` means **apply runs on inbound threads too**. So core consensus state
   (`currentTerm`, `votedFor`, log, `commitIndex`, `nextIndex/matchIndex`) and the store are
   raced, and `stateMachine.apply` can double-enter. The *read* path was deliberately marshalled
   onto the tick thread (`ConfigdServer.java:453`) — the *inbound message* path (`:257`) was not.
   **Classification: `[VERIFIED-FAIL]` on the threading model** (threads proven distinct &
   unsynchronized by file:line) / **`[EXISTS-UNTESTED]` as a triggered race** — no test exercises
   tick+inbound concurrency; the 159 green consensus tests drive the node single-threaded via the
   in-process/simulated transport, so they **cannot** catch it. This is also the root cause that
   makes concurrency hazards W-1/W-2 (below) live in multi-node mode.
   *Note: in single-node/default mode the TCP transport is not started (peer addresses empty →
   no-op transport), so the race manifests specifically in the multi-node "distributed" mode the
   product is built for.*
2. **🔴 No runtime invariant enforcement in production.** Both checkers are NOOP
   (`ConfigdServer.java:248`, `:188`); `InvariantMonitor` is never wired. The documented "TLA+ →
   runtime assertion" safety net does nothing in a running server. `[VERIFIED-FAIL]`.
3. **🟠 Edge data plane unverified against any live pipeline.** Fan-out is a write-only sink
   (`FanOutBuffer.append` with no reader); `broadcast()` benchmark-only. Every edge-staleness /
   RYW / monotonic-read guarantee in `consistency-contract.md` is therefore untested end-to-end.
   `[VERIFIED-FAIL]` (live path) / `[EXISTS-UNTESTED]` (library).
4. **🟠 "Linearizability verified" without a history checker.** No Knossos/Elle/Wing-Gong/
   Porcupine anywhere; `LinearizabilityTest` is scripted single-threaded. The strongest
   correctness claim in the consistency contract has no concurrent-history backing. `[ABSENT]`.
5. **🟠 Green ≠ coverage: count inflation + low diversity + vacuous spec invariants.**
   (a) ~20,000 of 21,394 tests are one parameterized test. (b) `SeedSweepTest` **does** exercise
   real leader-failure safety (probe: ~100% of seeds reach the assertion, bail paths fire ~0%) —
   **but** the per-node election RNG is **unseeded** (`ConsistencyPropertyTests.java:77`), so "10k
   distinct seeds" is one scenario ×10k with timing jitter, not 10k adversarial schedules
   (`[VERIFIED-FAIL]` on the "10k distinct executions" framing). (c) TLA invariants
   `ReadFreshness`/`NoStaleLeaderServe` (`ReadIndexSpec.tla:237,251`) and `NoCommitRevert`
   (`SnapshotInstallSpec.tla:173`) are tautological — their green is near-meaningless.
   (d) The reconfiguration-under-election test `configChangePreservedAcrossElections`
   (`ReconfigurationTest.java:257-270`) is **vacuous/misnamed** — it proposes a normal command,
   never a config change or election. Joint-reconfig-under-leader-change is `[EXISTS-UNTESTED]`.
6. **🟠 Multi-region / hierarchical Raft is a deploy-shaped false promise** (see §4.1).
7. **🟡 Latent store hazards (become live under risk #1).**
   - **R-1** `ReadResult.value()` (`ReadResult.java:56-58`) returns the HAMT's **live internal
     `byte[]`** (no clone), contradicting its "immutable" javadoc; sibling paths
     (`VersionedValue.value()`, `Put.value()`) clone. Today the only caller is
     `HttpApiServer.java:251` (`os.write(value)`, read-only) → `[EXISTS-UNTESTED]` latent.
   - **W-1** single-writer is a **documented-but-unenforced** precondition (no guard/owner-thread
     assertion). Live only under risk #1.
   - **W-2** `ConfigStateMachine` exposes non-volatile fields via public getters; within one apply
     thread there is a program-order edge, but under risk #1 (concurrent apply threads) it races.
8. **🟡 Perf claims & stack assumptions unbacked** (see §4.5–4.6): the throughput/tail/GC model
   assumes Netty zero-copy + JCTools hand-off + ZGC tuning that isn't present.

> **What is genuinely solid (do not re-litigate):** the Raft *algorithm* logic, the lock-free
> volatile-snapshot MVCC read path (no locks/CAS on either read path — verified by grep + code
> trace; safe publication over an all-final immutable HAMT), the CRC32C framed wire protocol, the
> single-node ReadIndex gating, the seeded deterministic simulation harness, and the live-green
> `ConsensusSpec` model check. These are real.

---

## 6. What to build / verify first (ordered by risk reduction per unit effort)

1. **Serialize the Raft event loop (cheap, kills risk #1).** Marshal inbound `routeMessage` onto
   the existing single `tickExecutor` — the exact pattern already used for reads at
   `ConfigdServer.java:453` — or give `MultiRaftDriver` its own single-thread executor so
   `tick()` and `handleMessage()` never run concurrently. Then add a **concurrent tick+inbound
   stress test** (or jcstress) that today does not exist. *Highest ROI: a few lines re-establish
   the single-threaded invariant the whole `RaftNode` design assumes.*
2. **Turn the safety net back on (cheap, kills risk #2).** Construct a real `InvariantChecker`
   (bridge to `InvariantMonitor`) and pass it to both `RaftNode` (`ConfigdServer.java:248`) and
   `ConfigStateMachine` (`:188`); decide throw-in-test / metric-in-prod. Add one test asserting a
   violation is actually observed in the running server.
3. **Add a real linearizability checker** (Knossos/Elle/Porcupine-style history checker) over a
   concurrent client workload; stop labeling the scripted single-threaded visibility test
   "linearizability." Until then, downgrade the `consistency-contract.md` claim.
4. **De-vacuum the formal proof.** Fix the tautological invariants (`ReadIndexSpec.tla:237,251`,
   `SnapshotInstallSpec.tla:173`) so they constrain real state; add `ReadIndexSpec` and
   `SnapshotInstallSpec` to CI (`ci.yml` runs only `ConsensusSpec`); regenerate
   `spec/tlc-results.md` from the live `.cfg` and stop citing the imported `tlc-rerun.log`.
5. **Either wire the edge data plane or delete the promise.** Drain `FanOutBuffer` → `PlumtreeNode
   .broadcast` → transport → edge, with an end-to-end propagation test; **or** remove the orphaned
   distribution/edge code and the `architecture.md`/`consistency-contract.md` sections that
   promise it. Today it is a deploy-shaped false promise.
6. **Make the seed sweep real.** Seed the per-node election RNG (`ConsistencyPropertyTests.java:77`)
   so 10k seeds are 10k distinct schedules, not one scenario ×10k; fix the misnamed
   `configChangePreservedAcrossElections` to actually propose a config change and force an
   election (or delete it). Stop quoting "20k tests" as suite size.
7. **Reconcile the docs with the code.** Mark multi-region/hierarchical-Raft/closed-timestamp and
   the Netty/Spring/gRPC/OTel/JCTools/Agrona stack as roadmap (or remove); relabel the perf
   scorecard as modeled until JMH artifacts are committed under `perf/results/`.
8. **Harden the store (small).** Clone in `ReadResult.value()` (R-1); add an owner-thread
   assertion to enforce single-writer (W-1); make the `ConfigStateMachine` cross-thread-read
   fields `volatile` (W-2). Low cost; closes the latent hazards independent of #1.

---

## 7. How the team cross-examined (where lenses corrected each other)

The four-lens method changed conclusions — evidence that the cross-examination was real, not
ceremonial:

- **Runtime safety net (escalation):** `design-vs-reality` initially rated the
  `sequence_monotonic`/`sequence_gap_free` assertions `[VERIFIED-PASS]` ("real, wired"). Under
  challenge it **reversed its own call** to `[VERIFIED-FAIL]` after tracing `ConfigdServer.java:188`
  → null → NOOP — matching `consensus-correctness` and the lead's independent trace.
- **W-1/W-2 liveness (escalation, conflict resolved):** `design-vs-reality` de-escalated W-2 to
  "latent (single apply thread)"; `consensus-correctness` **refuted** it by proving inbound
  messages run on virtual threads. The lead independently confirmed
  `MultiRaftDriver.java:119` calls `handleMessage` with no marshalling → **`consensus-correctness`
  is correct**; W-1/W-2 are production-live in multi-node mode.
- **R-1 (de-escalation):** `concurrency-readpath` flagged R-1 High; `design-vs-reality` traced the
  only caller (`HttpApiServer.java:251`, read-only) → refined to `[EXISTS-UNTESTED]` latent.
- **SeedSweep vacuity (de-escalation):** `verification-evidence` suspected the seed sweep asserts
  nothing on many seeds; `consensus-correctness` **refuted** it with an empirical probe (~100% of
  seeds reach the real assertion) — but both agreed the *count* is inflated and *diversity* is low.
- **Consistency-contract test classes (correction):** `design-vs-reality` reported the 9 classes
  `[ABSENT]`; `verification-evidence` (which ran the suite) found them as `@Nested` and corrected
  the finding to "exist & pass, but none is a real linearizability checker."

---

## 8. Evidence index

Full per-lens findings, with every command and its output, in
`verification-runs/state-of-reality/`:

- `findings-consensus-correctness.md` — TLA+ live reruns, spec-invariant→code map, SeedSweep
  probe, joint-reconfig analysis, the inbound-thread race.
- `findings-concurrency-readpath.md` — read-path walkthrough, lock-free verification, snapshot
  publication safety, R-1/W-1/W-2, ReadIndex TOCTOU analysis.
- `findings-verification-evidence.md` — live 21,394-test aggregate, verification scorecard,
  Jepsen/JMH/DST status, stale-artifact citations.
- `findings-design-vs-reality.md` — component map, claim-by-claim doc→code table, worst gaps,
  invariant-checker resolution.

*All classifications in this report are backed by a command I (or a named teammate) ran, or by a
specific `file:line`. No claim here rests on a doc asserting itself.*
