# ADR-0032: Linearizability Test Harness — Tooling Decision

## Status

**Proposed** (2026-06-06) — output of Session A3-D (Opus design team: `distributed-systems-lens`,
`consistency-lens`, `chaos-lens`, investigating independently then cross-examining; lead orchestrated
ordering and assembled this ADR from their content). **STOP for human review before any implementation
(A3-B).** Closes the design half of risk **R-04** ([ABSENT] linearizability checker). Sign-off: see
**Reviewers** (3 of 3).

## Context

`docs/consistency-contract.md §1` claims control-plane **writes are linearizable** and **ReadIndex reads
are linearizable**. The mapped test (`INV-L1 → LinearizabilityTest`) is **scripted single-threaded
sequential visibility**, not a concurrent-history check; no Knossos/Elle/Wing-Gong/Porcupine exists
anywhere (`STATE-OF-REALITY.md §3/§4.4`, R-04 `[ABSENT]`). A3 is the true Phase-A gate: it must drive the
**real multi-process binary** under an adversarial, replayable fault schedule and check the recorded
history for linearizability of root-group writes and ReadIndex reads.

Three facts about the real binary (re-confirmed by file:line this session) shape the decision:

1. **`ack ≠ commit`.** `ConfigWriteService.put` returns `200 Accepted(proposalId)` the instant the leader
   appends to its **local** log (`proposer.propose()` true), *before* quorum-commit; `proposalId` is a
   local `AtomicLong`, not a Raft index (`ConfigWriteService.java:150-154,84/101`; `node.propose` returns
   after local append, `RaftNode.java:283-289`). So a successful HTTP response is **indeterminate** w.r.t.
   commit. The harness must model writes accordingly (see `docs/a3-harness-design.md §6`).
2. **The live write model is a per-key linearizable register.** The wired API exposes only single-key
   `PUT`/`DELETE` (`HttpApiServer.java:216-218`; `ConfigWriteService.java:121,164`). The contract names
   `BATCH` (`§1`) but it is **not wired**. There are no multi-key atomic transactions on the live path.
3. **The mode that hid R-01 must not be reused.** All multi-node tests today run on the in-process,
   single-threaded `SimulatedNetwork`/`RaftSimulation` (`SimulatedNetwork.java:15,33-34`) — the exact path
   that hid the R-01 race. A3 exists to test the multi-process wire path it cannot see.

**The central decision (the adversary fought it):** full Jepsen (Clojure) with the Elle checker driving the
live binary, vs. a bespoke Java harness feeding Porcupine. The cross-examination dissolved this into a
**false binary** (see below) and the team converged 3-of-3.

## Decision

**Build a bespoke Java harness with two separable parts, feeding a TRUSTED third-party linearizability
checker:**

- **(i) Orchestration — bespoke, in Java. Decided 3-of-3; proven on this box `[VERIFIED-PASS]`.**
  - **Cluster driver:** launch each node as a **separate JVM process** from the shaded server jar
    (`io.configd.server.ConfigdServer`, `configd-server/pom.xml:46/62`) with real
    `--peer-addresses`, over the real `TcpRaftTransport` blocking-SSLSocket wire path. 3- and 5-node.
  - **Fault injection — OS-level**, via `iptables`/`tc` (and process `kill -9`/restart), driven by
    shelling out from Java. This is real Jepsen practice and the only mechanism that exercises the
    socket-level behavior R-01 proved the sim hides.
  - **Concurrent client + history recorder:** JDK `HttpClient`, client-side wall-clock invoke/response
    timestamps, emitting a **checker-neutral op-history** (invoke / ok / fail / info events).
- **(ii) Checker — a trusted third-party linearizability checker over the recorded history.**
  - **Primary, gate-required: Porcupine** (`anishathalye/porcupine`, the checker etcd uses). Rationale:
    the live model is a **per-key linearizable register** — Porcupine's modeled domain — and Porcupine has
    **first-class indeterminate-op support** (an op with an unknown/absent return is placed anywhere ≥ its
    invocation or omitted), which is exactly what `ack ≠ commit` and timed-out writes require. Per-key
    history partitioning bounds its (superlinear) runtime.
  - The op-history file is **checker-neutral**, so **Elle** (Jepsen's checker, `elle.core/check` runs on a
    history alone) is a **drop-in optional cross-check** for long-soak runs (Elle scales better on long
    histories) **and becomes the mandated primary the day multi-key atomic `BATCH` is wired** (cross-key
    cycle detection becomes load-bearing then). Running both = two independent trusted checkers agreeing.

**The orchestrator/checker reframe (why this is not "Jepsen vs bespoke"):** the fault-injection +
cluster-driving + history-recording (the hard half) is checker-agnostic and was *demonstrated* in pure Java;
every candidate checker consumes the same invoke/ok/fail/info history as a terminal batch step. So the only
thing the full Clojure/Jepsen framework would add over the proven Java orchestration is the checker — and for
a per-key register, Porcupine matches Elle on detection power and indeterminate handling. We therefore pay
**zero Clojure tax for zero capability gain**, while keeping Elle reachable through the neutral history.

**Non-negotiable: the checker self-test gate.** The hand-written glue (client adapter + history recorder +
status→`{ok,fail,info}` mapping) is the project's named failure mode (a real unit wired in an unverified way).
Before any real run's verdict is trusted, a suite of **synthetic histories with pinned GREEN/RED verdicts**
must pass through the *real* recorder→checker pipe — including the timeout→`info`-not-`fail` flip
(`a3-harness-design.md §11`). This is the "who checks the checker" answer; it applies to whichever checker is
used.

### Residual recorded (not smoothed over): primary-checker split was 2-1
`consistency-lens` and `distributed-systems-lens` favored **Porcupine primary** on model-fit (per-key
register; Porcupine's home turf; Elle's transactional power unused today). `chaos-lens` favored **Elle as
default + Porcupine cross-check** on long-history scaling and dual-checker assurance. The lead resolved it to
**Porcupine primary / Elle optional cross-check / Elle future-primary-on-BATCH**, because per-key history
partitioning (agreed by all three) substantially bounds Porcupine's runtime — neutralizing the dissent's main
objection — while keeping the gate to a single installed runtime. The checker-neutral history preserves the
dissent's strongest point (Elle is a drop-in, never locked out). All three sign the resolution.

## Rejected alternatives (with concrete cost, not hand-waving)

1. **Full Jepsen / Clojure framework as the driver + Elle.** *Genuinely strong, fairly considered, rejected.*
   Elle is battle-tested and gives readable named-cycle anomaly explanations; "we wrote our own checker" is a
   claim a principal distrusts. **But:** (a) the orchestrator is separable from the checker (above), so
   adopting the framework would mean **rebuilding the `db`/`client`/`nemesis` namespaces** — an estimated
   **~400–700 LOC of Clojure across 4 namespaces, 3–6 days of Clojure ramp** — to **duplicate the Java
   orchestration we already proved works** (3-node bring-up + real iptables partition with re-election/
   step-down + kill-9/restart durability, all `[VERIFIED-PASS]`); (b) Clojure **and** `lein` are **absent on
   this box** and nothing is cached in `~/.m2` (so it is *not* a free runtime — see cost #2 below); (c) Elle's
   signature strength is dependency-cycle detection over **transactional/serializable** histories, which is
   **unused** for a single-key register. *Re-entry condition:* the day multi-key atomic `BATCH` is wired, or
   for very-long-soak histories — Elle is kept reachable via the neutral history.
2. **A hand-rolled linearizability checker.** Rejected outright. "Who checks the checker?" — neither chosen
   option does this; both Porcupine and Elle are trusted third parties. The R-04 status quo *is* a hand-rolled
   single-threaded `LinearizabilityTest`; replacing it with another bespoke checker would repeat the sin.
3. **Lincheck (a JVM checker).** Rejected as a **category error.** Lincheck checks interleavings of an in-JVM
   concurrent **data structure** in one process; it has no notion of separate processes, network partitions,
   crash/restart, or durability — i.e. none of the faults that produce Configd's real distributed violations.
   Using it would recreate the original sin (verifying a component while its distributed wiring goes unchecked).
4. **Reusing the in-process `SimulatedNetwork`.** Rejected. Single-threaded, in-process, message-objects-not-
   bytes (`SimulatedNetwork.java:15,33-34`); it is precisely the path that **hid R-01**. A green run on it
   would prove the system works in the one mode that can't break.
5. **A transport-level fault shim (linked into the binary).** Rejected as *primary* (kept as a labelled
   fallback for deterministic per-RPC reorder/dup). It changes the binary under test and misses socket-level
   behavior — the opposite of A3's purpose. Lower fidelity than OS-level injection.
6. **Toolchain delta is honest, not zero.** Porcupine is **Go**, and Go is also **absent on this box**. The
   Porcupine path installs a Go toolchain + a pinned `porcupine` checkout + a ~40-line Go `main` calling
   `porcupine.CheckOperations`. So both the chosen path and the Jepsen path install **one** non-Java runtime;
   the decision does **not** rest on a false "no-ramp" claim — it rests on reusing the proven Java orchestration
   and the model-fit of the checker.

## Consequences

- **Positive:** drives the real multi-process wire path (the R-01 blind spot); a trusted checker answers
  "who checks the checker"; the checker self-test gate retires the hand-written-glue risk with the same
  discriminating-test discipline as A1/A2; the neutral history future-proofs the checker choice.
- **Costs / limits (recorded honestly):**
  - One non-Java runtime to install (Go + Porcupine). CI must build/cache it.
  - **No determinism seam in the binary** (election RNG is `nanoTime`-seeded, `ConfigdServer.java:214-215`;
    no `--seed`). Reproducibility is therefore of the **external schedule** (fault timing + client-op offsets,
    seeded `SplittableRandom`), proven by `diff schedule-<seed>.json` byte-identical — **not** of which node
    wins. The recorded *history* differs run-to-run by design.
  - **Linearizable GET is flaky on the wire** (`[VERIFIED-FAIL]` intermittent; 150 ms ReadIndex confirm
    timeout, `ConfigdServer.java:512`): a lin-read 503 must be recorded `:info`, never a failed read.
  - **Reconfiguration faults are out of A3 scope:** `proposeConfigChange` has no live caller; injecting a
    reconfig fault would require adding an admin reconfig seam (itself a new untested-integration seam — the
    A1 prior). Deferred to a dedicated session (recorded as a residual, `a3-harness-design.md §13`).
  - Porcupine runtime is bounded by per-key partitioning + op caps + a checker timeout (timeout ⇒ run is
    *indeterminate*, not a pass).

## Evidence appendix (selected, `[classified]` + file:line / command)

- `[VERIFIED-PASS]` 3-JVM bring-up + real `iptables` partition (→ re-election + leader step-down) + `kill -9`
  / restart durability on this box (chaos-lens prototype, output in `findings-chaos-lens.md §2`).
- `[VERIFIED-PASS]` `ack ≠ commit` — `ConfigWriteService.java:150-154`; `RaftNode.java:283-289`.
- `[VERIFIED-PASS]` default GET stale / linearizable needs `?consistency=linearizable` —
  `HttpApiServer.java:233,236-237,244,254`.
- `[VERIFIED-FAIL]` flaky linearizable read — `ConfigdServer.java:512` (`resultFuture.get(150, …)`).
- `[VERIFIED-PASS]` CheckQuorum wired (partitioned leader steps down) — `RaftNode.java:776-785`;
  ReadIndex lease is quorum-based — `:1616-1627`.
- `[EXISTS-UNTESTED]/[ABSENT-trigger]` reconfig unreachable — `proposeConfigChange RaftNode.java:514` has no
  non-test caller; `AdminService` never wired.
- `[ABSENT]` no history checker exists — grep over `*.java/*.clj/*.go` → 0; toolchain: clojure/lein/go absent.
- Discrimination mutation sites (exact): lost-acked-write `FileStorage.java:110` (`channel.force(true)`)
  + rewrite path `:62`; stale-read `RaftNode.java:421` (`if (role != RaftRole.LEADER) return false;`).

## Reviewers

All three signed after independent re-verification of the parts they own (file:line). The primary-checker
residual was a recorded 2-1 lead resolution; the dissenter (chaos-lens) accepts it on the merits.

- **distributed-systems-lens: SIGN-OFF** — fault matrix (§8) + dropped/deferred rulings (clock-jump =
  tick-based timers `RaftNode.java:59`; InstallSnapshot-crash = single-shot no-op + 16 MiB liveness cliff;
  reconfig F-J = no live trigger, deferred with no-new-seam-in-A3-B) match my Round-2 rulings with file:line
  reasons intact; the checker resolution is faithful to the Porcupine-primary position I argued, with the
  BATCH flip-back condition preserved.
- **consistency-lens: SIGN-OFF** — the discrimination mutation sites (lost-acked-write `FileStorage.java:110`
  / `:62`; stale-read `RaftNode.java:421`) and their expected REDs are exactly right (re-verified by
  file:line); the §11.3 self-test gates the hand-written glue with the timeout→`info`-not-`fail` flip as
  test 3; §6/§7 state my ack-fork ((A)-now + read-back-sourced RED; (B) an A3-B follow-on, not a gate
  prerequisite) and full indeterminate-op mapping without misstatement.
- **chaos-lens: SIGN-OFF (dissent on primary checker, recorded)** — OS-level injection, the `[VERIFIED-PASS]`
  3-JVM + real-iptables-partition + kill-9/restart feasibility proof, the `diff schedule-<seed>.json`
  reproducibility model (inputs pinned, not which node wins), the TLS-off+smoke topology, and the §11.3
  self-test gate + honest "Go-also-absent / no-ramp-is-overstated" framing are all captured faithfully. I
  argued Elle-primary; I accept the 2-1 resolution to Porcupine-primary because the per-key partitioning the
  design adopts (my own proposal) genuinely bounds Porcupine's runtime and the checker-neutral history keeps
  Elle a drop-in cross-check + future-primary-on-BATCH.
