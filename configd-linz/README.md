# configd-linz — Linearizability + Fault-Injection Harness (A3 / R-04)

Drives a **real separate-JVM Configd cluster** (the shaded `configd-server` jar) over the
**real Netty consensus transport** (ADR-0043), under **OS-level faults**, records a
**checker-neutral op-history**, and verifies it with a **trusted third-party checker — Porcupine**
(the checker etcd uses). It deliberately does **not** use the in-process `SimulatedNetwork` — that is
the single-threaded path that hid the R-01 race. Design: ADR-0032. The E1 measurement run and its
methodology are pinned under `docs/measurement/e1-faulted-linz-*`.

**Fault classes (the nemesis set).** `kill -9` + restart-into-the-live-cluster, symmetric
`iptables -j REJECT` partitions (single-node and multi-node → quorum-breaking), `SIGSTOP`/`SIGCONT`
process pauses (the stale-leader / stop-the-world nemesis), probabilistic packet loss
(`iptables -m statistic`), clock skew (`libfaketime`, a timestamp perturbation — elections are
tick-driven), and **overlapping combinations** of all of these. Two schedule modes: `SEQUENTIAL`
(the original one-at-a-time, quorum-preserving smoke) and `ADVERSARIAL` (Jepsen-grade combination
nemeses in quorum-breaking bursts). Postures on the consensus path: at-rest encryption, bearer-token
auth, clock skew.

## Layout

```
src/main/java/io/configd/linz/
  cluster/   ClusterNode, Cluster        separate-JVM nodes (1..n); posture (auth/encrypt/skew); pause()/resume()
  fault/     FaultInjector               iptables REJECT partitions + statistic-DROP packet loss (sudo -n), tracked+healed
  client/    ConfigClient                JDK HttpClient (optional bearer token); status -> {ok,info,fail}; follows X-Leader-Hint
  history/   Op, HistoryRecorder, PorcupineHistoryWriter   recorder + the ack!=commit encoding
  schedule/  Schedule, ScheduleJson      seeded SEQUENTIAL | ADVERSARIAL plan; reproducible schedule-<seed>.json
  check/     PorcupineChecker, Verdict   shells the Go checker; maps exit code -> verdict
  runner/    HarnessMain                 fault+workload run (concurrent apply/heal fault scheduler)
             LostWriteScenario           lost-acked-write discrimination
             StaleReadScenario           stale-read discrimination
src/main/go/porcupine-check/  main.go    trusted checker (per-key linearizable register)
src/test/java/.../CheckerSelfTest        checker self-test: synthetic histories, pinned verdicts (needs PORCUPINE_BIN)
                 HistoryWriterUnitTest    pure-Java encoding coverage (runs in ./mvnw test)
scripts/   build-porcupine.sh  run-discrimination.sh  run-gate.sh  run-matrix.sh   (run-matrix.sh = the E1 matrix)
discrimination/  lost-acked-write.patch  stale-read.patch
```

## How the history is modeled (the load-bearing correctness)

Each key is an **independent linearizable register**; the history is partitioned per key. Every PUT
carries a globally-unique token (`s<seed>:c<client>:<seq>`) so a read pins exactly which write it saw.

Because the system has `ack != commit` (a `200 Accepted` is returned on local append, *before*
quorum-commit — risk **R-14**), a write's completion cannot be taken as a commit:

- **Writes** are indeterminate. Porcupine v1.2.0 cannot model a call with no return (it requires every
  op to have a return), so we encode an indeterminate write as an `Operation` that **floats**: its
  `Return` is stretched forward so it may linearize anywhere at or after its call. A floating write is
  always legal in the register model, so this can never *cause* a false RED.
- **Confirm-bound** (tractability): floating *every* write to END makes all writes mutually concurrent
  and explodes Porcupine's superlinear search. So a write whose token is later observed by an OK read
  is pinned to that read's response time (the write provably committed by then — a tight, sound upper
  bound); only never-observed writes float to END. Sound *and* tractable.
- **Indeterminate reads** (linearizable-read 503/timeout) and **definite-fail** writes (503 NotLeader /
  4xx — rejected before propose, so never committed) are **dropped**.
- **OK reads** keep their real `[call, ret]` — the real-time backbone.

The discrimination power survives floating writes because of unique tokens + confirming reads: an OK
read "uses up" its write's single linearization point, so a later re-observation of a superseded value
is unexplainable -> RED.

## Single-host fault constraints (honest)

On single-host loopback the kernel sources all outbound connections from `127.0.0.1` (verified), so
per-node **source-IP** partitions are impossible without network namespaces. We therefore partition by
**destination Raft port** (`--dport`), which cleanly isolates one node's inbound socket. Consequences:

- **Isolate/kill/pause of any node** are faithful: isolating a leader's inbound fails CheckQuorum -> it
  steps down (~500 ms) -> the majority re-elects. A `SIGSTOP` pause freezes a node with its sockets
  open (the stale-leader window). **Quorum-breaking** partitions are exercised by isolating a *set* of
  nodes at once (each becomes an isolated singleton) — a majority isolation drives total unavailability,
  a minority isolation leaves the majority serving while the isolated nodes must never serve a stale
  read. This is the safety property; a *connected*-minority-with-its-own-leader and true **asymmetric /
  bridge (non-transitive) partitions** need per-pair source-addressed cuts and remain the **netns
  follow-up** (recorded, not silently claimed) — the same safety edge is already stressed here by pauses
  + isolation + quorum-breaking combinations.
- **Stale-read discrimination** is adapted: a deposed-leader-still-serving is not single-host injectable
  (CheckQuorum + the heartbeat leak), so the same safety violation is injected as a **lagging isolated
  follower** serving a local read as if linearizable — exactly what `RaftNode.readIndex`/`isReadReady`
  exist to forbid.

## Discrimination is re-proven on HEAD (the harness is not blind)

Both seeded bugs in `discrimination/` are re-authored against the current code and turn the checker RED
(controls GREEN) — see `run-discrimination.sh`. Re-authoring `lost-acked-write` surfaced a **strong
positive property**: the raft-anchor durability kernel *fail-closes* a lost write. With only the WAL
write no-opped, every node refuses to start (`WAL recovery head-rollback ... a committed-and-acked
durable entry vanished - refusing, fail closed`), so a single-layer durability defeat is INDETERMINATE,
never a silent loss. To exercise the checker's discrimination of the lost-write *shape*, the seed now
defeats BOTH layers (the WAL write and the anchor head-rollback guard); the single-layer INDETERMINATE
is itself the evidence the guard is load-bearing.

## Running

```bash
# 1. Build the trusted checker (installs a user-local Go toolchain if needed)
bash configd-linz/scripts/build-porcupine.sh
export PORCUPINE_BIN="$PWD/configd-linz/bin/porcupine-check"

# GATE (i): checker self-test (8 synthetic histories through the real recorder->checker)
./mvnw -pl configd-linz test -Dtest=CheckerSelfTest      # needs PORCUPINE_BIN; else skipped

# GATE (ii): discrimination — both seeded bugs must turn the checker RED, controls GREEN
bash configd-linz/scripts/run-discrimination.sh both

# GATE (iii)+(iv): unmodified GREEN on 3- and 5-node + reproducibility (SEQUENTIAL smoke)
bash configd-linz/scripts/run-gate.sh "1001 1002 1003"

# The E1 MATRIX: real adversarial combination nemeses on N=3 + N=5 across postures. Every
# recorded history must be LINEARIZABLE. --profile smoke is a quick local subset; --profile
# full (the measurement run) needs a non-burstable box + libfaketime for the skew posture.
bash configd-linz/scripts/run-matrix.sh --out /tmp/e1 --profile smoke --nodes "3 5" \
  --postures "base encrypt auth" --adv-seeds 3 --adv-dur 45000 --keys 12 --shard 1/1

# GATE (v): full build + suite (self-test auto-skips without PORCUPINE_BIN, so CI stays green)
./mvnw -fae test
```
