# Findings — chaos-lens (Session A3-D design)

Lens: HOW faults are physically injected against the REAL multi-process binary + REPRODUCIBILITY.
Design only (but feasibility was PROTOTYPED on this box). Rubric literal + command/file:line on every claim.

## 1. Injection mechanism — OS-level (iptables/tc) driven from Java is PRIMARY
- **In-process SimulatedNetwork DISQUALIFIED** `[VERIFIED-FAIL]` as the A3 mechanism. `SimulatedNetwork.java:15,33-34`
  is single-threaded, in-process, `new Random(seed)`, an in-memory `PriorityQueue` of message objects (never
  bytes on a socket); `RaftSimulation.java:18-20` "single thread with controlled time advancement." Per
  STATE-OF-REALITY §5.1 + ledger R-01, the concurrency race lived on the multi-process wire path *invisible to
  exactly this path*. Reusing it re-creates the original blind spot — the whole reason A3 exists.
- **OS-level chosen.** `iptables` DROP / `tc netem` on the real JVMs is TLS-agnostic at L4, exercises the real
  `TcpRaftTransport` blocking-SSLSocket / virtual-thread-per-connection path
  (`TcpRaftTransport.java:100,121,206-207`), models reconnect-backoff / half-open / step-down, and was proved
  on this box (§2). A transport-level shim would have to be linked INTO the binary under test = changes the
  thing being tested; kept only as a labelled fallback for deterministic per-RPC reorder/dup.

## 2. Feasibility proof — `[VERIFIED-PASS]` except 2.3b
- **2.1 Build:** `./mvnw -q -pl configd-server -am package -DskipTests` → exit 0; shaded
  `configd-server-0.1.0-SNAPSHOT.jar` (2.5 MB); `configd-server/pom.xml:46,62` mainClass
  `io.configd.server.ConfigdServer`.
- **2.2 3 JVMs on loopback**, `--peers` excludes self (`RaftConfig.clusterSize = peers+1`, `RaftConfig.java:67`),
  all `/health/ready=200` → leader elected (node 1). No election log line exists — leadership observable only
  via HTTP (`ConfigdServer.java:417-423`).
- **2.3 Writes/reads:** PUT→leader `200 / Accepted: proposalId=1`; followers `503` + header `X-leader-hint: 1`
  + body `Not Leader (leader=Node-1)` (`HttpApiServer.java:279-285/305-311`; hint from `raftNode.leaderId()`
  at `ConfigdServer.java:441-442`); stale GET on a follower returned the replicated value at `X-config-version: 1`.
- **2.3b Linearizable GET is FLAKY** `[VERIFIED-FAIL]` (intermittent). `?consistency=linearizable` on the
  *healthy leader* returned 503 on 4 of 5 attempts. Root cause: the ReadIndex confirmation future has a 150 ms
  hard timeout (`ConfigdServer.java:512`, `resultFuture.get(150, …)`; `PROPOSE_TIMEOUT_MS=150` at `:89`) and the
  heartbeat-quorum round trip often misses it. **Harness consequence:** a linearizable-read 503 must be recorded
  as `:info` (indeterminate), never a failed read of a definite value, or the checker sees false anomalies.
- **2.4 Real partition:** `iptables` DROP on the leader's Raft port 9101 (4 rules, both directions) → port 8102
  is NEW LEADER (200), node3 hints leader=2; isolated node1 stepped down (PUT→503, `/health/ready→503`). Heal →
  node1 rejoined and caught up to version 3. (Confirms CheckQuorum-driven step-down on the real wire.)
- **2.5 Crash durability:** `kill -9` the leader + restart from the same `--data-dir` → committed
  `durable-marker-v9` present on all 3 nodes post-crash (real WAL + `storage.sync()`; `RaftLog.java:367,436`,
  `FileStorage.java:97-110`).
- **2.6 Teardown:** all PIDs killed, 4 iptables rules `-D`'d, ports free; scratch in `/tmp/a3proto` (not committed).

## 3. Reproducibility model
Seed → `SplittableRandom(seed)` in the orchestrator, `.split()` into independent fault-stream / workload-stream
substreams; every fault and op carries a seed-derived logical offset (ms from t0); the schedule is written to
`schedule-<seed>.json` (run-from-seed OR replay-file). **Gate (iii) proof = `diff` of two `schedule-<seed>.json`
runs → byte-identical.** This is the honest, correct claim because the binary has **NO determinism seam**
`[VERIFIED-FAIL]` on "internally reproducible": election RNG is wall-clock-seeded (`ConfigdServer.java:214-215`,
`nodeId*31 + System.nanoTime()`), timeouts use `Clock.system()` (`:169`), no `--seed` flag — after heal the
cluster re-elected to a *different* node. So reproducibility is of the **inputs** (what faults/ops at what
logical offsets), NOT which node wins; recorded *histories* differ run-to-run by design. Cluster nondeterminism
is tamed by making the checker leader-agnostic and the client follow `X-leader-hint` dynamically across
re-elections. (A3 is design-only, so adding a binary determinism seam is out of scope.)

## 4. Topology (3- and 5-node)
Per-node Raft port 9101+, API port 8101+, own `--data-dir`, full `--peer-addresses` map on every node; quorum
`size/2+1` (`RaftConfig.java:78`). Leader discovery: follow `X-leader-hint` on PUT/DELETE 503; linearizable-read
503 has NO hint (`HttpApiServer.java:240`) → treat indeterminate + backoff. **TLS OFF for the matrix** (plaintext
vs SSLSocket is the same blocking-socket/threading path `TcpRaftTransport.java:316-345`; iptables is L4
TLS-agnostic; TLS only worsens the 150 ms read-timeout flakiness) **+ one TLS-on smoke run** to keep mTLS
(`F-0050/F-0051`) honest. Launch via `java -jar` with logs→`nN.log` + PID; readiness barrier on `/health/ready`;
teardown = kill -9 + pkill sweep + always flush my iptables rules + verify with `ss`.

## 5. ADR position — CROSS-EXAMINATION OUTCOME
Round-1 position: bespoke Java + Porcupine. Under cross-examination, **conceded all three challenges:**
- **"No ramp" overstated** — `go: command not found` on this box too; the honest Porcupine path installs a Go
  toolchain + a pinned `anishathalye/porcupine` checkout + a ~40-line Go `main` calling
  `porcupine.CheckOperations`. The Elle path *also* installs a fresh runtime (Clojure/lein absent; nothing
  cached in `~/.m2`). **Both install one runtime + one small shim — the delta is close to a wash on "install a
  runtime."** Will not carry "no ramp" into the draft. (JVM-checker escape hatch rejected: Lincheck is a
  data-structure checker, Knossos is superseded — staying single-language trades away the trust property.)
- **The hand-written GLUE is the real risk locus** (project's named failure mode). Formalized de-risking = a
  mandatory **checker self-test suite** of synthetic histories with KNOWN verdicts fed through the REAL
  recorder→checker pipe: (1) sequential-sane→GREEN; (2) stale-read anomaly→RED; (3) timed-out-write `:info` then
  read-T→GREEN, **flip same op to `:fail`→RED** (the single most important test — proves timeout→info, never
  fail); (4) lin-read-503 `:info`→GREEN, flip to fabricated `:ok`→RED; (5) default-GET-stale modeled as a window
  read→GREEN, same bytes on a linearizable read→RED; (6) unique-token precondition. Glue not trusted until 1-6
  pass with every flip. Honest residual: this tests the *mapping*, not the generator/timeline ergonomics Jepsen
  gives for free.
- **False binary CONCEDED — the synthesis.** The orchestrator (proven Java) is separable from the checker; every
  candidate checker consumes the same invoke/ok/fail/info op history. `elle.core/check` runs on a history alone
  (no Jepsen generator/nemesis needed). So the genuinely-contested decision collapses to *which checker* consumes
  the Java-recorded history.

**Refined recommendation: Java orchestration (proven, uncontested) emitting a checker-neutral op-history,
fed to a TRUSTED checker — decided as two independent axes.** My checker lean shifted to **Elle as default**
(better long-history scaling — mitigates Porcupine's superlinear blowup — + richer anomaly inference) **with
Porcupine as a cross-check** (two trusted checkers agreeing = strongest assurance), history kept checker-neutral
so we are not locked in. [Lead note: the team's majority + the per-key-partitioning runtime bound resolved this
residual toward **Porcupine primary, Elle optional cross-check / future-primary** — see ADR-0032 §Decision.]

## 6. Convergence answers
- **Q1 reproducibility artifact:** `diff schedule-<seed>.json` byte-identical; pin inputs not cluster reactions
  (no determinism seam). Honest + correct.
- **Q2 Porcupine blowup bound:** (a) per-key partitioning (dominant win — each register sub-history is small);
  (b) op-count / wall-time caps per run (checker timeout ⇒ run is *indeterminate*, not a pass); (c) windowing for
  very long soaks. (Per-key partitioning is also why the workload uses a small keyspace.)
- **Q3 reconfig:** DEFER out of A3 — unreachable from the binary; adding an admin reconfig endpoint is a server
  feature change past A3's fence. Do NOT smuggle a new endpoint into A3-B.
- **Q4 TLS:** OFF for the matrix + one TLS-on smoke run.
