# Adversarial Deterministic Simulation Extension (§4.3) — Design

**Status:** DESIGN ONLY (Session 2, branch `session-2-correctness`). No code in this commit.
**Author:** simulation-engineer. **Closes (on implementation):** RR-027 (fault classes),
RR-012 (sweep vacuity); feeds §4.4 / RR-016 (linearizability history) and RR-003's durable-prefix
invariant.

> Grounding rule: every capability/gap below cites current `file:line`. The fix landed in RR-010
> (`RaftSimulation.electionRandom(NodeId)`, `RaftSimulation.java:69-72`; `mixSeed` SplitMix64 `:79-84`) is the
> seed-derivation pattern reused throughout. Production code is untouched by this design.

---

## 1. Current capability inventory

What the in-process sim already does — all seed-derived and replayable after RR-010:

| Capability | Where | Notes |
|---|---|---|
| Single-threaded, deterministic tick loop | `RaftSimulation.tick()` (`RaftSimulation.java:98-104`) | clock +1ms, deliver due msgs, then each node `tick()` (`ConsistencyPropertyTests.java:104-109`) |
| Seeded network latency | `SimulatedNetwork.send` (`SimulatedNetwork.java:78`) | `min + random.nextInt(max-min+1)`, seeded `Random(seed)` (`:34`) |
| Seeded drop | `SimulatedNetwork.send` (`SimulatedNetwork.java:76`) | `random.nextDouble() < dropRate`; rate set via `setDropRate` (`:48`) |
| Static partitions (uni/bi-directional) | `addPartition`/`isolate`/`healAll` (`SimulatedNetwork.java:53-71`) | bitset-encoded `(from<<32)|to` (`:110-112`); `isolateNode` (`RaftSimulation.java:147-154`) |
| Deterministic per-node election RNG | `RaftSimulation.electionRandom(NodeId)` (`:69-72`) — **RR-010** | threaded at `ConsistencyPropertyTests.java:75-79` |
| Deterministic clock | `SimulatedClock` (`SimulatedClock.java`) | one shared cluster clock; `advanceMs`/`advanceNanos`/`setTimeMs` |
| Per-step sim observer seam | `RaftSimulation.addInvariantChecker(Consumer<RaftSimulation>)` (`:90-92`), run in `checkInvariants()` (`:161-165`) | sim-level; currently unused by the property tests |
| Per-node in-node invariant seam | `RaftNode.InvariantChecker` (`RaftNode.java:161-164`), 8 named sites | **the harness passes NOOP** (5-arg ctor `:233-235`); the 7-arg ctor `:182-184` wires a real one |

**Missing (RR-027, `readiness-register.md:61`; harness-runs §3):** message **duplication** NO,
**reorder beyond latency jitter** NO, **delay spikes** NO, **per-node clock skew** NO, **disk faults
/ crash-restart-with-state-loss** NO, **concurrent randomized client workload under faults** NO,
**dynamic (scheduled) partition/heal** — only manual `isolateNode`. The `@Buggify` seam exists
(`BuggifyRuntime.java`) but has **0 call sites** outside common (RR-027) and a design flaw: it is
**global static mutable state** (`BuggifyRuntime.java:14-16`), not sim-instance-scoped, so two sim
instances in one JVM (e.g. the RR-010 replay-twice pattern) would share `enabledPoints`/`random` —
unusable as the per-sim fault source. **We do not build on `BuggifyRuntime`;** faults are driven from
a sim-instance-scoped RNG (below).

---

## 2. Fault classes to add (§4.3)

All faults draw from **`AdversarialSchedule`**, a new sim-owned object seeded once from the master
seed via the RR-010 pattern: `faultRng = L64X128MixRandom.create(mixSeed(seed, FAULT_STREAM_TAG))`,
where `FAULT_STREAM_TAG` is a fixed constant distinct from any node id so the fault stream never
collides with an election stream. The schedule is a **pure function of the seed** — replay by seed
alone reproduces every fault at the same logical tick.

Implementation is a `SimulatedNetwork` extension + a thin sim-level scheduler; **no production change**.

| Fault | Mechanism (design) | Seam |
|---|---|---|
| **Drop** | already present; adversarial schedule sets `dropRate` windows | `setDropRate` (`:48`) |
| **Reorder** | per-message latency drawn so two in-flight msgs can swap delivery order; the `PriorityQueue` by `deliverAtMs` (`:25,85`) already reorders on unequal latencies — adversarial mode widens the latency band and, on tie, breaks ties by a seeded key instead of insertion order (today ties are insertion-ordered — a hidden determinism dependency; we make tie-break explicit + seeded) | new `deliverAt` jitter + seeded tie-break field on `PendingMessage` |
| **Duplication** | on `send`, with seed-derived probability, enqueue the *same* `PendingMessage` a second time at `deliverAt + dupDelay`. Raft must be idempotent under dup (AppendEntries/RequestVote are); a dup that changes state machine output is a RED | `send` (`:74-80`) wrapper |
| **Delay spike** | seed-derived rare large latency (e.g. ×50) on a chosen `(from,to)` for a window | `send` latency override |
| **Partition schedule** (full / partial / asymmetric) | scheduler emits timed `addPartition`/`removePartition` events at seed-derived logical offsets. **Asymmetric** = add only `(a→b)` not `(b→a)` — already expressible (`addPartition` is unidirectional, `:53`). **Heal is a first-class scheduled event** (aligns with `a3-harness-design.md:110`) | `addPartition`/`removePartition` (`:53-59`) |
| **Crash-restart (incl. mid-fsync)** | **integration point, not a second primitive — the RR-003 primitive now exists and we design against it verbatim.** `CrashStorage implements Storage` (`configd-consensus-core/src/test/java/io/configd/raft/CrashStorage.java:48`): every mutating op (`put`/`appendToLog`/`truncateLog`/`renameLog`) is buffered *pending* until `sync()` promotes it to the durable image; `crash()` (`:141-144`) discards everything still pending — exactly a power-loss of un-fsynced writes. **Restart** = `recoveredView()` (`:111-113`) → a fresh `Storage` over only the durable image, fed to `new RaftNode(...)`. **Crash point** is chosen deterministically by `armCrashAfterWrites(n)` (`:121-123`, 1-based op count incl. mid-`compact()`); `operationCount()` (`:126-128`) discovers step indices. **Sim wiring:** the adversarial schedule derives `n` from the seed and arms a crash; on fire, rebuild the node from `recoveredView()` + a fresh `electionRandom(id)`. **One real seam gap:** `CrashStorage` is *package-private in `io.configd.raft` test scope*, not in configd-testkit where the sim lives — so either (a) RR-003 promotes it to `public` (or to a shared test-fixtures artifact), or (b) crash-recovery scenarios run in `configd-consensus-core` test scope reusing the `ClusterHarness` logic. **Decision owed jointly with RR-003** (flagged in §7 step 6); do NOT fork a second crash primitive. |
| **Concurrent client workload** | a seed-derived stream of PUT/DELETE/READ ops interleaved with faults (see §6 for the op record). Writes carry unique tokens; reads are linearizable `readIndex` reads (`RaftNode.readIndex`/`isReadReady`, `:433,458`) | new `WorkloadDriver`, ticked alongside nodes |

**Per-node clock skew:** today `sim.clock()` is one shared `SimulatedClock`. Design: wrap each node's
clock view in a `SkewedClock(base, offsetMs)` where `offsetMs` is seed-derived per node and bounded
(skew must stay < election timeout or it is a liveness fault, not a safety probe). The node already
takes a `Clock` indirectly via the transport's `sim.clock().currentTimeMillis()` (`:74`); skew is
injected by giving each node its own `Clock` view. Bounded skew only — unbounded skew is a documented
liveness scenario, reported not failed (§3).

---

## 3. Continuous invariant checking (every step, every seed)

Two seams, used together:

**(A) In-node, per-event — reuse `RaftNode.InvariantChecker` (`RaftNode.java:161-164`).** The
adversarial harness constructs nodes with the **7-arg ctor** (`:182-184`) passing a real checker
instead of `NOOP`. This gives, *for free*, the 8 existing named checks at their exact mutation sites:
`election_safety` (`:1383`), `leader_completeness` (`:1389`), `log_matching` (`:1041`),
`state_machine_safety` (`:1591`), `version_monotonicity` (`:1585`), `single_server_invariant`
(`:740`), `no_op_before_reconfig` (`:745`), `reconfig_safety` (`:753`). A `false` condition →
**safety violation → fail the seed** with the seed + tick + node + message printed for replay.

**(B) Sim-level cross-node observer — reuse `addInvariantChecker(Consumer<RaftSimulation>)`
(`:90-92`).** Runs after every `tick()` via `checkInvariants()` (`:103`). Checks that need a global view of all nodes:

| Invariant | Check (sim-level) | Source of truth |
|---|---|---|
| Single-leader-per-term | ≤1 node in `LEADER` per `currentTerm()` across all nodes | `node.role()`/`currentTerm()` (`RaftNode.java:956-957`) — same logic as `SeedSweepTest.java:40-48` |
| Leader Completeness (committed entries survive leader change) | a value committed (observed via `store.currentVersion()` / read-back) is present on every future leader | `store(i)` (`ConsistencyPropertyTests.java:97`) |
| Log Matching across replicas | for any two nodes, logs agree up to the lower `commitIndex` | `log(i).commitIndex()`/entry-at-index |
| State Machine Safety (no divergent apply at same index) | per-index applied value identical across nodes that applied it | per-node SM listener (`addListener`, used `ConsistencyPropertyTests.java:582`) |
| Version monotonicity **at every observer** | each node's `store.currentVersion()` never decreases tick-to-tick | `store(i).currentVersion()` |
| No stale overwrite | a higher-version value for a key is never replaced by a lower-version one at any observer | per-key version from SM listener |
| **Durable-prefix (RR-003)** | after crash+restart, the recovered `commitIndex` prefix equals the pre-crash committed prefix (no committed entry vanishes) | `CrashStorage.crash()`→`recoveredView()` (`CrashStorage.java:141,111`) rebuild vs pre-crash committed snapshot — **co-owned with RR-003**; hooks the crash event |

**Safety vs liveness (charter: liveness is reported, never hidden).** A check in (A)/(B) returning
`false` is a **safety violation → the seed FAILS** (assertion). A **liveness stall** — no leader
elected within budget, or a proposal uncommitted within budget — is **NOT a failed assertion**: the
harness records it to a per-run `liveness-findings-<seed>.txt` and the run continues / ends green for
safety. This mirrors the existing `SeedSweepTest` "skip gracefully" intent (`SeedSweepTest.java:65-68`)
— **but with the RR-012 fix**: a graceful skip must still satisfy the §4 activity predicate, so a
liveness skip is *recorded*, not silent. Distinguisher: safety = an invariant predicate is false;
liveness = a goal predicate (leader-exists, op-committed) is not yet true within a tick budget.

---

## 4. Vacuity defense (the RR-012 lesson)

RR-012 (`readiness-register.md:46`): `SeedSweepTest.java:67,74,87` are three bare `return`
statements that pass green having asserted nothing — a seed that elected no leader, committed nothing,
or failed over to nothing still counts as a pass. Fix: **every seed must prove it did work, or fail.**

Each scenario class declares a **minimum-activity predicate** evaluated at end-of-run; if unmet the
**seed fails loudly** (not skipped):

| Scenario class | Minimum-activity predicate (must ALL hold or the seed FAILS) |
|---|---|
| Election sweep | ≥1 leader was elected during the run AND ≥2 distinct terms observed |
| Commit-durability sweep | ≥1 value committed (a `store.currentVersion()` advance was observed) AND ≥1 leader failover completed (old leader isolated, new leader reached stable) |
| Adversarial-workload | ≥1 fault fired (the `AdversarialSchedule` records a non-empty event list) AND ≥1 client write committed AND ≥1 linearizable read returned `:ok` |
| Crash-recovery (RR-003) | ≥1 crash event executed AND ≥1 committed entry existed pre-crash (else the durable-prefix check is vacuous) |

The predicate is cheap counters incremented at the existing observation points (leader found, version
advanced, fault emitted, crash executed). **The RR-012 de-vacuation of `SeedSweepTest` itself rides
this work**: its three `return`s become `assertActivity(...)` calls feeding the same predicate, plus a
demonstrated **injected-violation catch** (a deliberately corrupted run that the harness must RED —
captured to `docs/session-2/captures/`, mirroring the RR-010 pre-fix capture discipline).

---

## 5. Scale plan

Per-seed cost is grounded in the RR-010 run: `SeedSweepTest` ran **1000 cases in 4.91s** (2 methods ×
500 seeds), i.e. **~4.9 ms/seed wall** on the 2-vCPU box for the current 2000-tick scenario. The
adversarial scenario adds workload + dup/skew bookkeeping — budget **~3×**, call it **~15 ms/seed**.

| Tier | Size | Est. runtime (2-vCPU, ~15ms/seed, single-thread surefire) | Use |
|---|---|---|---|
| **Gate** | **fixed committed seed set, ≥500** (a hand-pinned `gate-seeds.txt`, including every historically-failing seed) | ~7.5 s | runs in gate-2 on every change; deterministic, no flake |
| **Nightly** | 10k sweep | ~2.5 min single-thread; ~1.5 min with surefire forks (the box has 2 vCPU — fork count 2, mind CPU-credit throttling, see env memory) | cron, not the gate |

The gate set is **committed seeds**, not a count range, so a regression that only one seed catches stays
caught forever (the RR-010 lesson: a failing seed must be replayable by number — it now is).

**Shrinking (schedule minimization) for a failing seed.** Because the seed *fully determines* the
schedule, "minimize the seed" is meaningless — instead we minimize the **derived schedule** the seed
expands to, then ship the minimized schedule as a standalone replayable artifact:

1. Expand the failing seed once into its concrete `AdversarialSchedule` (the list of fault events with
   logical-tick offsets + the client op list) and its tick budget — this is the reducible object.
2. **Delta-debug the event list:** greedily drop fault events / client ops / shorten the tick budget;
   after each removal re-run; keep the removal iff the safety violation still reproduces. Standard ddmin
   over an ordered event list.
3. The result is a minimal `schedule-<seed>-min.json` (same format as the §6 history's input side) that
   still REDs — checked into the failing-seed corpus and runnable directly (bypassing seed expansion),
   so the repro is both small and stable even if `mixSeed` derivation ever changes.

This is the in-sim analogue of `a3-harness-design.md:130-133`'s "pin the inputs" — here we can pin
*and* shrink because the sim is fully deterministic.

---

## 6. History capture for linearizability (§4.4 / RR-016, feeds B5)

§4.4's Porcupine-based configd-linz checker needs invocation/response histories with real-time bounds.
The deterministic sim is a **second, cheaper, fully-replayable** history source alongside the
real-binary harness of `a3-harness-design.md`. **To let B5 consume it without re-instrumenting, the
sim emits the identical checker-neutral op-history format** that doc already defines
(`a3-harness-design.md:43-49,85-99`):

Per op record (one JSON line; **same fields, same semantics** as `a3-harness-design.md:45-49`):
`client_id`, `op_type` (PUT / DELETE-as-write-of-⊥ / READ), `key`, `arg` (globally unique token
`clientId:opSeq:nonce`), `ret` (observed token or ⊥), `invoke_ts`, `response_ts`, `status`,
`consistency` (`linearizable` on reads). **Real-time backbone = the `SimulatedClock`** (`invoke_ts` =
`sim.clock().currentTimeMillis()` immediately before propose; `response_ts` = after commit/read
completes) — logical but monotonic and total, which is exactly what Porcupine needs.

**Ack semantics (gates correctness — `a3-harness-design.md:68-99`):** a `propose`-accepted write is
`:info` (ack ≠ commit, RR-004); it is promoted to "happened" only when a read observes its token. A
timed-out/uncommitted write is `:info`, **never** `:fail`. A rejected propose is `:fail`. Per-key
partitioned histories (each key an independent register, `a3-harness-design.md:35-41`).

**Capture point:** a sim-level `HistoryRecorder` registered as an `addInvariantChecker` observer **and**
hooked at the `WorkloadDriver`'s propose/read calls — invoke recorded before `node.propose`/
`node.readIndex`, response recorded when `store.currentVersion()`/`isReadReady` confirms. Emitted to
`history-<seed>.jsonl`. **Across failovers:** the recorder is cross-node (sim-level), so a write under
the old leader and a read under the new leader land in one history with correct real-time order — the
exact failover linearizability case (`a3-harness-design.md:104-110`). Output is checker-neutral so
Porcupine is primary and Elle is a drop-in cross-check (`a3-harness-design.md:140-142`).

**Scope note:** this sim history does NOT replace the real-multi-process harness (the sim hides the
blocking-socket/TLS wire path — `a3-harness-design.md:51-59`, the R-01 blind spot). It is a fast,
replayable, high-fault-density *complement* that finds logic bugs cheaply; the binary harness remains
the wire-truth source.

---

## 7. Work plan (ordered; each step names its guarding test)

This is sized for one session and folds in RR-012. Steps are independently committable; each is guarded
by an existing or step-local test so no step regresses the green suite.

1. **De-vacuate `SeedSweepTest` (RR-012).** Replace the 3 bare `return`s (`:67,74,87`) with the
   §4 activity predicate + recorded liveness-skips; add an injected-violation test that must RED, capture
   it. *Guard:* `SeedSweepTest` itself (now non-vacuous) + the new injected-violation capture.
2. **Sim-instance fault RNG + `AdversarialSchedule` skeleton** (seed-derived via `mixSeed`, distinct
   stream tag). No faults yet — just the deterministic schedule object + replay-by-seed test. *Guard:*
   new `AdversarialScheduleDeterminismTest` (same shape as `SimulationDeterminismTest`: same seed ⇒
   identical schedule digest).
3. **Wire real `InvariantChecker` into the harness** (7-arg ctor) + sim-level cross-node observer (§3).
   *Guard:* `ConsistencyPropertyTests` (must stay 44/44 — the checker fires but never falsely REDs on the
   known-good seeds) + a deliberately-broken-node unit test that the checker catches.
4. **Network faults: reorder/dup/delay-spike + dynamic partition schedule.** *Guard:* `SeedSweepTest`
   election/commit safety must stay green under faults at the gate seed set; `SimulationDeterminismTest`
   extended to assert the faulted run is still byte-replayable.
5. **Per-node clock skew (bounded).** *Guard:* election-safety sweep green; an over-skew scenario lands in
   liveness-findings (not a failure) — asserted by a test that expects a *recorded* stall, not a RED.
6. **Crash-restart against RR-003's `CrashStorage`** (`CrashStorage.java`, now landed) + durable-prefix
   invariant. **First resolve the visibility seam** (CrashStorage is package-private in
   `configd-consensus-core` test scope; the sim is in configd-testkit): co-decide with RR-003 whether to
   promote it `public`/extract a test-fixtures module, or to run crash scenarios in consensus-core test
   scope. *Guard:* co-owned RR-003 crash-recovery test (e.g. `SnapshotCrashRecoveryTest`); durable-prefix
   check REDs on a seeded `armCrashAfterWrites` cut that drops a committed entry.
7. **`HistoryRecorder` + `history-<seed>.jsonl` emission** (§6). *Guard:* a round-trip test that feeds a
   sim history into the configd-linz Porcupine checker and asserts LINEARIZABLE on a known-good seed and
   a RED on a seeded injected stale-read (hands B5 a working consumer contract).
8. **Schedule minimization (ddmin)** over a failing seed's expanded schedule (§5). *Guard:* a test with a
   known-RED seed whose minimized `schedule-min.json` still REDs and is strictly smaller.
9. **Gate vs nightly tiers** (§5): commit `gate-seeds.txt` (≥500), wire the 10k nightly cron separately.

**Dependencies/ordering:** 1→2→3 are prerequisites for everything; 6 waits on RR-003's primitive (seam
ready now); 7 hands off to B5 (RR-016) and can land in parallel after 3. RR-027 closes after 4+5+6;
RR-012 closes after 1.
