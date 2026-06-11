# configd-linz — Linearizability + Fault-Injection Harness (A3 / R-04)

Drives a **real separate-JVM Configd cluster** (the shaded `configd-server` jar) over the
**real `TcpRaftTransport`**, under **OS-level faults** (`iptables` partitions + `kill -9`), records a
**checker-neutral op-history**, and verifies it with a **trusted third-party checker — Porcupine**
(the checker etcd uses). It deliberately does **not** use the in-process `SimulatedNetwork` — that is
the single-threaded path that hid the R-01 race. Design: `docs/a3-harness-design.md`, ADR-0032.

## Layout

```
src/main/java/io/configd/linz/
  cluster/   ClusterNode, Cluster        separate-JVM nodes (1..n) on distinct 127.0.0.1 ports
  fault/     FaultInjector               iptables --dport DROP partitions + kill-9 (sudo -n), tracked + healed
  client/    ConfigClient                JDK HttpClient; status -> {ok,info,fail}; follows X-Leader-Hint
  history/   Op, HistoryRecorder, PorcupineHistoryWriter   recorder + the ack!=commit encoding
  schedule/  Schedule, ScheduleJson      seeded fault+workload plan; reproducible schedule-<seed>.json
  check/     PorcupineChecker, Verdict   shells the Go checker; maps exit code -> verdict
  runner/    HarnessMain                 gate (iii)/(iv) fault+workload run
             LostWriteScenario           gate (ii) lost-acked-write discrimination
             StaleReadScenario           gate (ii) stale-read discrimination
src/main/go/porcupine-check/  main.go    ~150-line trusted checker (per-key linearizable register)
src/test/java/.../CheckerSelfTest        gate (i): 8 synthetic histories, pinned verdicts (needs PORCUPINE_BIN)
                 HistoryWriterUnitTest    pure-Java encoding coverage (runs in ./mvnw test)
scripts/   build-porcupine.sh  run-discrimination.sh  run-gate.sh
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

- **F-E (isolate leader)** and **isolate/kill of any node** are faithful: isolating a leader's inbound
  fails CheckQuorum -> it steps down (~500 ms) -> stops heartbeating -> the majority re-elects.
- **F-F (bridge partition)** needs per-*pair* source-addressed cuts -> deferred to a netns-based
  follow-up (recorded, not silently dropped).
- **Stale-read discrimination** is adapted: a deposed-leader-still-serving is not single-host injectable
  (CheckQuorum + the heartbeat leak), so the same safety violation is injected as a **lagging isolated
  follower** serving a local read as if linearizable — exactly what `RaftNode.readIndex`/`isReadReady`
  exist to forbid.

## Running

```bash
# 1. Build the trusted checker (installs a user-local Go toolchain if needed)
bash configd-linz/scripts/build-porcupine.sh
export PORCUPINE_BIN="$PWD/configd-linz/bin/porcupine-check"

# GATE (i): checker self-test (8 synthetic histories through the real recorder->checker)
./mvnw -pl configd-linz test -Dtest=CheckerSelfTest      # needs PORCUPINE_BIN; else skipped

# GATE (ii): discrimination — both seeded bugs must turn the checker RED, controls GREEN
bash configd-linz/scripts/run-discrimination.sh both

# GATE (iii)+(iv): unmodified GREEN on 3- and 5-node + reproducibility
bash configd-linz/scripts/run-gate.sh "1001 1002 1003"

# GATE (v): full build + suite (self-test auto-skips without PORCUPINE_BIN, so CI stays green)
./mvnw -fae test
```
