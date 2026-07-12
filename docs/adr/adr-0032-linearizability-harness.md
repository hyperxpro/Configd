# ADR-0032: Linearizability Test Harness - Tooling Decision

## Status

Accepted (2026-06-27). Originally proposed 2026-06-06, after independently investigating and then
cross-examining the design space. Closes the gap left by the absence of any linearizability checker
in the test suite.

> **Ratification note (2026-06-27).** The harness described below was built, reviewed, and has been
> operating in CI since. The bespoke Java orchestrator and the trusted `anishathalye/porcupine`
> checker live under `configd-linz/` (the Go checker is `configd-linz/src/main/go/porcupine-check`,
> built by `gates/gate-1.sh` when `PORCUPINE_BIN` is unset); the checker self-test gate
> (`CheckerSelfTest`, `PORCUPINE_BIN`-gated, `gates/gate-1.sh` step b) plus discrimination
> (`scripts/run-discrimination.sh`) and the replayable sim-history cross-check (`SimHistoryCheck`) are
> wired; `consistency-contract.md` section 7 maps INV-L1 onto exactly this harness. One reality delta
> since authoring: the wire path is now Netty (ADR-0043 supersedes the `TcpRaftTransport` named
> throughout the Context and Evidence sections below), so the orchestrator drives the real Netty
> server wire path; the tooling decision itself (bespoke Java orchestration plus Porcupine primary /
> Elle drop-in cross-check / Elle-primary-on-BATCH) is unchanged and is what is accepted here. The
> Context, Decision, and Evidence sections below describe the repository at authoring time
> (2026-06-06); their file:line references are historical.

> **Measurement update (2026-07-10).** The harness went from a 15-second, N=1, quorum-preserving smoke
> test to a real Jepsen-grade faulted-linearizability matrix run against release commit `299ba14`. The
> nemesis set now includes `SIGSTOP`/`SIGCONT` pauses, `iptables -m statistic` packet loss, multi-node
> quorum-breaking partitions, `libfaketime` clock skew, and overlapping fault combinations (a new
> adversarial schedule), run on N=3 and N=5 across at-rest-encryption / auth / clock-skew / multi-shard
> postures - every recorded history came back linearizable, with results pinned in git history under
> `docs/measurement/e1-faulted-linz-2026-07-10/`. The standing CI job (`gates/gate-2.sh` linzgate,
> `GATE2_FAULTED=1`) now runs the real adversarial matrix (`configd-linz/scripts/run-matrix.sh`) rather
> than the smoke. Both discrimination seeds needed re-authoring against the evolved code (the
> originals had bit-rotted); re-authoring `lost-acked-write` surfaced that the raft-anchor durability
> kernel fail-closes a lost write (a single-layer durability defeat now yields refuse-to-start, not
> silent loss). Asymmetric or partial (bridge, non-transitive) partitions remain a recorded follow-up
> that needs network namespaces - the single-host loopback substrate cannot source-address per-pair
> cuts. A separate, still-pending arc covers endurance (a 72-hour-plus soak).

## Context

`consistency-contract.md` section 1 claims control-plane writes are linearizable and ReadIndex reads
are linearizable. The mapped test (`INV-L1 -> LinearizabilityTest`) is scripted single-threaded
sequential visibility, not a concurrent-history check - no Knossos/Elle/Wing-Gong/Porcupine-style
checker exists anywhere in the test suite. The linearizability harness has to close that gap: it must
drive the real multi-process binary under an adversarial, replayable fault schedule and check the
recorded history for linearizability of root-group writes and ReadIndex reads.

Three facts about the real binary (re-confirmed by file:line this session) shape the decision:

1. **`ack != commit`.** `ConfigWriteService.put` returns `200 Accepted(proposalId)` the instant the leader
   appends to its **local** log (`proposer.propose()` true), *before* quorum-commit; `proposalId` is a
   local `AtomicLong`, not a Raft index (`ConfigWriteService.java:150-154,84/101`; `node.propose` returns
   after local append, `RaftNode.java:283-289`). So a successful HTTP response is **indeterminate** w.r.t.
   commit. The harness must model writes accordingly (indeterminate writes are placed anywhere >= their invocation, or omitted).
2. **The live write model is a per-key linearizable register.** The wired API exposes only single-key
   `PUT`/`DELETE` (`HttpApiServer.java:216-218`; `ConfigWriteService.java:121,164`). The contract names
   `BATCH` (section 1) but it is **not wired**. There are no multi-key atomic transactions on the live path.
3. **The mode that hid an earlier production race must not be reused.** All multi-node tests today run on
   the in-process, single-threaded `SimulatedNetwork`/`RaftSimulation` (`SimulatedNetwork.java:15,33-34`)
   - the exact path that hid that race. This harness exists to test the multi-process wire path that mode
   cannot see.

The central question was full Jepsen (Clojure) with the Elle checker driving the live binary, versus a
bespoke Java harness feeding Porcupine. Cross-examining both options dissolved this into a false binary
(see below).

## Decision

**Build a bespoke Java harness with two separable parts, feeding a trusted third-party linearizability
checker:**

- **(i) Orchestration - bespoke, in Java. Proven on this box.**
  - **Cluster driver:** launch each node as a **separate JVM process** from the shaded server jar
    (`io.configd.server.ConfigdServer`, `configd-server/pom.xml:46/62`) with real
    `--peer-addresses`, over the real `TcpRaftTransport` blocking-SSLSocket wire path. 3- and 5-node.
  - **Fault injection - OS-level**, via `iptables`/`tc` (and process `kill -9`/restart), driven by
    shelling out from Java. This is real Jepsen practice and the only mechanism that exercises the
    socket-level behavior the earlier race proved the sim hides.
  - **Concurrent client + history recorder:** JDK `HttpClient`, client-side wall-clock invoke/response
    timestamps, emitting a **checker-neutral op-history** (invoke / ok / fail / info events).
- **(ii) Checker - a trusted third-party linearizability checker over the recorded history.**
  - **Primary, gate-required: Porcupine** (`anishathalye/porcupine`, the checker etcd uses). Rationale:
    the live model is a **per-key linearizable register** - Porcupine's modeled domain - and Porcupine has
    **first-class indeterminate-op support** (an op with an unknown/absent return is placed anywhere >= its
    invocation or omitted), which is exactly what `ack != commit` and timed-out writes require. Per-key
    history partitioning bounds its (superlinear) runtime.
  - The op-history file is **checker-neutral**, so **Elle** (Jepsen's checker, `elle.core/check` runs on a
    history alone) is a **drop-in optional cross-check** for long-soak runs (Elle scales better on long
    histories) **and becomes the mandated primary the day multi-key atomic `BATCH` is wired** (cross-key
    cycle detection becomes load-bearing then). Running both means two independent trusted checkers agreeing.

**The orchestrator/checker split (why this is not "Jepsen vs bespoke"):** the fault-injection +
cluster-driving + history-recording (the hard half) is checker-agnostic and was *demonstrated* in pure Java;
every candidate checker consumes the same invoke/ok/fail/info history as a terminal batch step. So the only
thing the full Clojure/Jepsen framework would add over the proven Java orchestration is the checker - and for
a per-key register, Porcupine matches Elle on detection power and indeterminate handling. This pays
**zero Clojure tax for zero capability gain**, while keeping Elle reachable through the neutral history.

**Non-negotiable: the checker self-test gate.** The hand-written glue (client adapter + history recorder +
status->`{ok,fail,info}` mapping) is a real unit wired in an otherwise-unverified way. Before any real run's
verdict is trusted, a suite of **synthetic histories with pinned green/red verdicts** must pass through the
*real* recorder->checker pipe - including the timeout->`info`-not-`fail` flip. This is the "who checks the
checker" answer; it applies to whichever checker is used.

### Why Porcupine over Elle as primary

The two live options were Porcupine-primary (best model fit: the live system is a per-key linearizable
register, Porcupine's home turf, and Elle's transactional power is unused today) versus Elle-as-default
with Porcupine as a cross-check (better scaling on long histories, and two independent checkers agreeing
is stronger assurance). Porcupine primary wins here because per-key history partitioning bounds
Porcupine's runtime, neutralizing the main cost of the Porcupine-primary choice, while the checker-neutral
history preserves the strongest part of the alternative: Elle stays a drop-in, never locked out, and
becomes primary the day `BATCH` ships.

## Rejected alternatives (with concrete cost, not hand-waving)

1. **Full Jepsen / Clojure framework as the driver + Elle.** *Genuinely strong, fairly considered, rejected.*
   Elle is battle-tested and gives readable named-cycle anomaly explanations; "we wrote our own checker" is a
   claim worth distrusting on its face. **But:** (a) the orchestrator is separable from the checker (above), so
   adopting the framework would mean **rebuilding the `db`/`client`/`nemesis` namespaces** - an estimated
   **~400-700 LOC of Clojure across 4 namespaces, 3-6 days of Clojure ramp** - to **duplicate the Java
   orchestration we already proved works** (3-node bring-up + real iptables partition with re-election/
   step-down + kill-9/restart durability, all confirmed on this box); (b) Clojure **and** `lein` are **absent on
   this box** and nothing is cached in `~/.m2` (so it is *not* a free runtime - see cost #2 below); (c) Elle's
   signature strength is dependency-cycle detection over **transactional/serializable** histories, which is
   **unused** for a single-key register. *Re-entry condition:* the day multi-key atomic `BATCH` is wired, or
   for very-long-soak histories - Elle is kept reachable via the neutral history.
2. **A hand-rolled linearizability checker.** Rejected outright. "Who checks the checker?" - neither chosen
   option does this; both Porcupine and Elle are trusted third parties. The status quo before this ADR *was*
   a hand-rolled single-threaded `LinearizabilityTest`; replacing it with another bespoke checker would
   repeat the same mistake.
3. **Lincheck (a JVM checker).** Rejected as a **category error.** Lincheck checks interleavings of an in-JVM
   concurrent **data structure** in one process; it has no notion of separate processes, network partitions,
   crash/restart, or durability - i.e. none of the faults that produce Configd's real distributed violations.
   Using it would recreate the original mistake (verifying a component while its distributed wiring goes unchecked).
4. **Reusing the in-process `SimulatedNetwork`.** Rejected. Single-threaded, in-process, message-objects-not-
   bytes (`SimulatedNetwork.java:15,33-34`); it is precisely the path that **hid the earlier race**. A green run on it
   would prove the system works in the one mode that can't break.
5. **A transport-level fault shim (linked into the binary).** Rejected as *primary* (kept as a labelled
   fallback for deterministic per-RPC reorder/dup). It changes the binary under test and misses socket-level
   behavior - the opposite of the harness's purpose. Lower fidelity than OS-level injection.
6. **Toolchain delta is honest, not zero.** Porcupine is **Go**, and Go is also **absent on this box**. The
   Porcupine path installs a Go toolchain + a pinned `porcupine` checkout + a ~40-line Go `main` calling
   `porcupine.CheckOperations`. So both the chosen path and the Jepsen path install **one** non-Java runtime;
   the decision does **not** rest on a false "no-ramp" claim - it rests on reusing the proven Java orchestration
   and the model-fit of the checker.

## Consequences

- **Positive:** drives the real multi-process wire path (closing the earlier blind spot); a trusted checker
  answers "who checks the checker"; the checker self-test gate retires the hand-written-glue risk with the
  same discriminating-test discipline used elsewhere in the harness; the neutral history future-proofs the
  checker choice.
- **Costs / limits (recorded honestly):**
  - One non-Java runtime to install (Go + Porcupine). CI must build/cache it.
  - **No determinism seam in the binary** (election RNG is `nanoTime`-seeded, `ConfigdServer.java:214-215`;
    no `--seed`). Reproducibility is therefore of the **external schedule** (fault timing + client-op offsets,
    seeded `SplittableRandom`), proven by `diff schedule-<seed>.json` byte-identical - **not** of which node
    wins. The recorded *history* differs run-to-run by design.
  - **Linearizable GET is flaky on the wire** (intermittent; 150 ms ReadIndex confirm timeout,
    `ConfigdServer.java:512`): a lin-read 503 must be recorded `:info`, never a failed read.
  - **Reconfiguration faults are out of scope:** `proposeConfigChange` has no live caller; injecting a
    reconfig fault would require adding an admin reconfig seam (itself a new untested-integration seam).
    Deferred. In the same vein, clock-jump faults are out of scope because Raft timers are tick-based, not
    wall-clock-driven (`RaftNode.java:59`), and InstallSnapshot-under-crash is modeled as a single-shot no-op
    against the known 16 MiB snapshot-liveness ceiling.
  - Porcupine runtime is bounded by per-key partitioning + op caps + a checker timeout (timeout means the run is
    *indeterminate*, not a pass).

## Evidence

- Confirmed: 3-JVM bring-up + real `iptables` partition (leading to re-election and leader step-down) plus
  `kill -9`/restart durability, verified on this box.
- Confirmed: `ack != commit` - `ConfigWriteService.java:150-154`; `RaftNode.java:283-289`.
- Confirmed: default GET is stale; a linearizable read needs `?consistency=linearizable` -
  `HttpApiServer.java:233,236-237,244,254`.
- Confirmed flaky: the linearizable read intermittently fails - `ConfigdServer.java:512`
  (`resultFuture.get(150, ...)`).
- Confirmed: CheckQuorum is wired (a partitioned leader steps down) - `RaftNode.java:776-785`;
  the ReadIndex lease is quorum-based - `:1616-1627`.
- Exists but untested, with no trigger reaching it: reconfiguration - `proposeConfigChange`
  (`RaftNode.java:514`) has no non-test caller; `AdminService` never wires it.
- Absent: no history checker exists anywhere in the tree (a grep over `*.java`/`*.clj`/`*.go` returns
  nothing); the Clojure and Go toolchains are both absent from this box.
- Discrimination mutation sites: lost-acked-write at `FileStorage.java:110` (`channel.force(true)`) and the
  rewrite path at `:62`; stale-read at `RaftNode.java:421` (`if (role != RaftRole.LEADER) return false;`).
