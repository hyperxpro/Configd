# Harness Execution Forensics — Session 1 (Phase A)

**Agent:** test-forensics-engineer · **Date:** 2026-06-10 · **Workspace:** `/home/ubuntu/ws-clean` (fresh clone of `session-1-ground-truth`, HEAD `423a654`, pre-built `BUILD SUCCESS` / 21,408 tests)
**Machine:** 2 CPUs, 7.7 GB RAM, JDK 25 (Corretto 25.0.0.36.2), shared box — **all timing numbers below are executability evidence only, NOT performance evidence.**
**Method:** every claim = command + pasted output. Verdicts: CONFIRMED / CONTRADICTED / PARTIAL / ENVIRONMENT-BLOCKED.

---

## Summary table

| Harness | Exists | Executes | Result (this run) | Full-run cost estimate | Owning future session |
|---|---|---|---|---|---|
| JMH benchmarks (9 classes, `benchmarks.jar`) | YES (9 classes, 25 methods) | YES — all 9 classes, exit 0, no crashes | All produce numbers; read-path GC profile: HAMT get ≈0 B/op, **VersionedConfigStore.getHit = 32 B/op** | full matrix `-wi 5 -i 10 -f 3 -prof gc` ≈ hours (param matrix ×25 methods) | perf-baseline session |
| TLA+ / TLC (3 specs) | YES (`spec/*.tla` + `.cfg` + `tla2tools.jar` v2026.04.09) | YES — all 3 ran to completion (exit 0) | **All 3 "No error"; state counts match prior claims EXACTLY** (13.78M/3.30M/d25 · 12.40M/2.28M/d38 · 6.00M/0.85M/d14); liveness never checked | measured: 14m00s + 7m45s + 2m37s on 2 cores (~25 min total) | formal-verification session |
| Apalache | NO — no binary anywhere on machine | n/a | doc mentions only (`ConsensusSpec.cfg` comment suggests it) | n/a | n/a |
| Deterministic simulation (testkit) | YES (`RaftSimulation`/`SimulatedNetwork`/`SeedSweepTest`) | YES — bounded 500-seed sweep ×2, 1000/1000 pass both | aggregate outcomes identical; **per-node election RNG unseeded** (determinism claim PARTIAL) | full 100k-seed sweep ≈ 30 min (doc claim) | sim-hardening session |
| jcstress | **NO** — 0 POM hits, 0 Java hits, 0 files | n/a | FINDING: lock-free structures have no jcstress coverage | new harness required | concurrency session |
| Linearizability harness (`configd-linz`) | YES (module + scripts + Go checker source; checker binary built here in 2.9 s) | YES — self-tests 6/6+4/4 (un-skipped); real 3-node faulted run executed | **VERDICT: LINEARIZABLE** (seed 4242, 4 OS faults fired, 801 ops, exit 0, ~25 s); teardown clean | full gate suite (i)–(iv) ≈ 30–45 min | — (R-04 owner A3) |
| Jepsen | **NO** — word appears only in `.md` docs | n/a | confirmed absent (matches prior audit) | n/a | chaos session |
| Chaos harness (other) | NO code — `docs/prr/chaos-report.md` is an audit memo about testkit, not a harness; 0 Java "chaos" hits | n/a | `@Buggify` exists but has **0 production injection points** (ADR-0007 claims ~1000) | n/a | chaos session |

---

## 1. JMH benchmarks

### Inventory

```
$ grep -rln "@Benchmark" /home/ubuntu/ws-clean --include=*.java
configd-testkit/src/main/java/io/configd/bench/{HamtReadBenchmark,HamtWriteBenchmark,
HistogramBenchmark,HybridClockBenchmark,PlumtreeFanOutBenchmark,RaftCommitBenchmark,
SubscriptionMatchBenchmark,VersionedStoreReadBenchmark,WatchFanOutBenchmark}.java   (9 files)
```

- **9 classes total, all in `configd-testkit/src/main/java/io/configd/bench/`** — the prior phrasing "9 plus PlumtreeFanOutBenchmark" is wrong; Plumtree is one of the 9.
- Doc drift: `docs/performance.md:339` says they live in `src/test/java/...` — actually `src/main/java` (moved by F-0043 so the shade plugin can bundle them). `performance.md` §10 lists only 6 of the 9 classes (missing Histogram, SubscriptionMatch, WatchFanOut).
- Build/run mechanism: maven-shade in `configd-testkit/pom.xml` produces an executable `target/benchmarks.jar` (mainClass `org.openjdk.jmh.Main`). Already present in the pre-built workspace (6.9 MB, `-rw-rw-r-- … Jun 10 17:59`).
- `java -jar benchmarks.jar -l` lists **25 benchmark methods** but prints the 3 HybridClock entries **twice** (`unzip -p … META-INF/BenchmarkList | grep -c HybridClock` → 6): benign duplicate rows in the shaded BenchmarkList (AppendingTransformer); JMH still runs them correctly.

### Smoke runs (executability)

Command per class (one `@Param` value pinned to bound runtime):

```
java -jar configd-testkit/target/benchmarks.jar "io.configd.bench.<Class>" \
  -f 1 -wi 1 -i 1 -w 1s -r 1s [-p <param>=<value>]
```

| Class | Pinned param | Executes | Exit | Order of magnitude observed (NOT perf evidence) |
|---|---|---|---|---|
| HamtReadBenchmark | size=10000 | YES | 0 | get ≈ 100 ns/op, getMiss ≈ 3 ns/op |
| HamtWriteBenchmark | size=10000 | YES | 0 | putNew/putOverwrite ≈ 280 ns/op |
| HistogramBenchmark | — | YES | 0 | record ≈ 27–33 ops/µs, p99 query ≈ 563 ops/µs |
| HybridClockBenchmark | clockType=system | YES | 0 | now ≈ 50 ns/op |
| PlumtreeFanOutBenchmark | fanOut=50 | YES | 0 | 2.2–14.3 µs/op |
| RaftCommitBenchmark | clusterSize=3 | YES | 0 | ≈ 0.005 ops/µs (≈5 K commits/s in 1-iter smoke) |
| SubscriptionMatchBenchmark | prefixes=1000 | YES | 0 | ≈ 21 µs/op |
| VersionedStoreReadBenchmark | size=10000 | YES | 0 | getHit ≈ 189 ns/op, getMiss ≈ 5 ns/op |
| WatchFanOutBenchmark | watcherCount=100 | YES | 0 | 1.4–18 µs/op |

All 9 classes: **execute, no crashes** (`exit=0` each; logs in `/home/ubuntu/audit-artifacts/jmh/*.log`).
Note: `docs/performance.md:345` claims RaftCommitBenchmark ≈ 815 K ops/s (3 voters); the 1-iteration smoke run shows ≈5 K ops/s. Smoke mode + shared box means this is **not** a refutation, but the published number is not reproducible from any committed artifact either — it remains `[EXISTS-UNTESTED]`, as STATE-OF-REALITY already concedes.

### Zero-allocation read-path claim (`-prof gc`)

```
java -jar configd-testkit/target/benchmarks.jar \
  "io.configd.bench.(VersionedStoreReadBenchmark|HamtReadBenchmark)" \
  -f 1 -wi 2 -i 3 -w 1s -r 1s -p size=10000 -prof gc
```

Result (final aggregate table, pasted):

```
HamtReadBenchmark.get:gc.alloc.rate.norm                           10000  avgt    3    0.001 ±   0.003    B/op
HamtReadBenchmark.getMiss:gc.alloc.rate.norm                       10000  avgt    3   ≈ 10⁻⁵              B/op
VersionedStoreReadBenchmark.getHit:gc.alloc.rate.norm              10000  avgt    3   32.001 ±   0.001    B/op
VersionedStoreReadBenchmark.getMiss:gc.alloc.rate.norm             10000  avgt    3   ≈ 10⁻⁴              B/op
VersionedStoreReadBenchmark.getWithMinVersion:gc.alloc.rate.norm   10000  avgt    3   32.001 ±   0.004    B/op
VersionedStoreReadBenchmark.snapshotGet:gc.alloc.rate.norm         10000  avgt    3    0.001 ±   0.001    B/op
```

Also: `HybridClockBenchmark.now` (clockType=system) → `gc.alloc.rate.norm 0.001 ± 0.001 B/op` — the `performance.md:39` "0 B/op after F-0041" claim is **CONFIRMED**.

**Verdict on "zero-allocation steady-state read path":**
- Hot read **hit** (`VersionedConfigStore.getHit`): **32 B/op — CONTRADICTED** as a blanket zero-alloc claim. (One `ReadResult` record per hit.)
- Hot read **miss**: ≈0 B/op — CONFIRMED (`NOT_FOUND` singleton, `performance.md:33`).
- Raw HAMT get/getMiss: ≈0 B/op — CONFIRMED (`performance.md:342`).
- Nuance: `performance.md:34` itself concedes the hit path allocates, but states **~24 B/op**, and `performance.md:41` says **~48 bytes** — both wrong vs. the measured **32 B/op**, and the two doc numbers contradict each other. `STATE-OF-REALITY.md:60` says "zero-alloc **miss**" which is the accurate phrasing.

---

## 2. TLA+ / TLC

### What each spec checks (from the live `.cfg`)

| Spec | Constants | CHECK_DEADLOCK | Invariants |
|---|---|---|---|
| ConsensusSpec | Nodes={n1,n2,n3}, MaxTerm=3, MaxLogLen=3, Values={v1,v2} | FALSE | TypeOK, ElectionSafety, StateMachineSafety, LeaderCompleteness, LogMatching, VersionMonotonicity, ReconfigSafety, SingleServerInvariant, NoOpBeforeReconfig (9) |
| ReadIndexSpec | Nodes={n1,n2,n3}, MaxTerm=2, MaxIndex=2 | FALSE | TypeOK, ElectionSafety, ReadIndexBoundedByMaxIndex, ReadFreshness, NoStaleLeaderServe (5) |
| SnapshotInstallSpec | Nodes={n1,n2,n3}, MaxTerm=3, MaxIndex=4 | FALSE | TypeOK, SnapshotBoundedByCommitted, SnapshotMatching, NoCommitRevert, InflightTermMonotonic (5) |

Liveness (`EdgePropagationLiveness`) is **commented out** in `ConsensusSpec.cfg` — only safety invariants are checked. No SYMMETRY in use.

### Live re-run

Command (run from scratch copies under `/home/ubuntu/audit-artifacts/tlc/<Spec>/`, so generated `states/` dirs don't dirty the tree; 25-min `timeout 1500` cap each):

```
java -XX:+UseParallelGC -Xmx3g -cp spec/tla2tools.jar tlc2.TLC -workers 2 -config <X>.cfg <X>.tla
```

TLC version: `2026.04.09.014118` (matches `tlc-results.md` header claim "TLC 2026.04.09"; note the .md also says "v1.8.0" in the same sentence — internally inconsistent labeling, same jar).

| Spec | Completes? | Wall (this box) | States generated | Distinct | Depth | Result | Prior claim (tlc-results.md) | Verdict |
|---|---|---|---|---|---|---|---|---|
| ConsensusSpec | YES (exit 0) | **843 s** (14m00s) | **13,775,323** | **3,299,086** | **25** | "Model checking completed. No error has been found." | 13,775,323 / 3,299,086 / 25 / No error | **CONFIRMED — exact match** |
| ReadIndexSpec | YES (exit 0) | **467 s** (7m45s) | **12,403,444** | **2,276,125** | **38** | No error | 12,403,444 / 2,276,125 / 38 / No error | **CONFIRMED — exact match** |
| SnapshotInstallSpec | YES (exit 0) | **159 s** (2m37s) | **5,995,717** | **847,124** | **14** | No error | 5,995,717 / 847,124 / 14 / No error | **CONFIRMED — exact match** |

Pasted final lines (ConsensusSpec; the other two logs are identical in form, in `/home/ubuntu/audit-artifacts/tlc/*.log`):

```
Model checking completed. No error has been found.
13775323 states generated, 3299086 distinct states found, 0 states left on queue.
The depth of the complete state graph search is 25.
Finished in 14min 00s at (2026-06-10 18:35:23)
```

All 19 invariants across the 3 specs model-check green at the configured bounds — **the headline TLC claims are real and reproducible to the exact state count.** Two caveats: (a) the **wall-time comments are not reproducible here** — `ConsensusSpec.cfg` says "~4 min on 2 cores" (measured 14m00s, first ~5 min contended by my own JMH/Maven runs), `ReadIndexSpec.cfg` says "~58s" (measured 7m45s uncontended — ~8× off), `SnapshotInstallSpec.cfg` says "~21s" (measured 2m37s) — the state counts are the deterministic, machine-independent part and they match exactly; (b) **only safety invariants are checked** — the single temporal property in the spec (`EdgePropagationLiveness`) is commented out of `ConsensusSpec.cfg`, so no liveness has ever been model-checked. Minor: `tlc-results.md` header says "tla2tools.jar v1.8.0" while the jar self-reports "Version 2026.04.09.014118" (same jar, sloppy labeling).

### Counterexample trace files committed in `spec/`

`spec/` contains **15 committed `*_TTrace_*` files** (7 ConsensusSpec `.bin`; 1 ReadIndexSpec `.bin`+`.tla` pair; 3 SnapshotInstallSpec `.bin`+`.tla` pairs) plus 8 committed `spec/states/` fingerprint dirs. TLC writes TTrace files **only when a run finds a violation/error**. Filename epochs decode to: ConsensusSpec 2026-04-10 (×6) and 2026-04-14 (×1); ReadIndexSpec 2026-04-17 21:51 UTC; SnapshotInstallSpec 2026-04-17 21:56 UTC (×3). Presence confirmed; git-history forensics on them is owned by another agent. They are consistent with `tlc-results.md`'s own "Bugs Found and Fixed During Model Checking" narrative (failed runs preceded the final green run) — but they are stale build artifacts committed to the tree.

### Apalache

```
$ which apalache apalache-mc            → nothing
$ find /home /usr/local /opt -iname '*apalache*'   → no binary, no install dir
```

Only doc mentions (e.g. `ConsensusSpec.cfg` comment "For larger explorations, use Apalache"). **Claim "Apalache absent" CONFIRMED.**

---

## 3. Deterministic simulation (configd-testkit)

### Entry points

- `configd-testkit/src/main/java/io/configd/testkit/`: `RaftSimulation` (orchestrator), `SimulatedNetwork` (latency/drop/partition), `SimulatedClock`.
- Tests: `SeedSweepTest` (parameterized sweep, default **10,000 seeds × 2 methods = 20,000 cases**; bounded via `-Dconfigd.seedSweep.count`), `ConsistencyPropertyTests` (contains `ClusterHarness`, lines 44–211), `RaftSimulationTest`, `SimulatedNetworkTest`, `EndToEndTest`.

### Reproducibility double-run (bounded, honest)

```
$ ./mvnw -pl configd-testkit test -o -Dtest=SeedSweepTest -Dconfigd.seedSweep.count=500   # ×2
run 1: Tests run: 1000, Failures: 0, Errors: 0, Skipped: 0  -- in io.configd.testkit.SeedSweepTest
run 2: Tests run: 1000, Failures: 0, Errors: 0, Skipped: 0  -- in io.configd.testkit.SeedSweepTest
```

Aggregate outcomes identical across the two runs (1000/1000 both). **BUT** this is outcome-level reproducibility only — bit-identical execution is **not** guaranteed, because:

- `ConsistencyPropertyTests.java:77` constructs every `RaftNode` with `RandomGenerator.of("L64X128MixRandom")` — **entropy-seeded, not derived from the simulation seed**.
- `RaftNode.java:1650` uses that generator for **election-timeout randomization** — so the actual schedule per "seed" varies run to run.
- `RaftSimulation.java:37` and `SimulatedNetwork.java:34` DO seed `new java.util.Random(seed)` (the April `prr/chaos-report.md` §1.3 finding was partially fixed) — message latency/drop are seed-deterministic; node timers are not.
- `STATE-OF-REALITY.md:177-180` already concedes this ("the per-node election RNG is unseeded … one scenario ×10k with timing jitter, not 10k adversarial schedules"). Independently **CONFIRMED by code reading**.

### Silent-return (vacuous-pass) paths — independent confirmation

`SeedSweepTest.commitSurvivesLeaderFailure` (`SeedSweepTest.java:60-97`) has exactly **3 silent-return paths**, confirmed by reading the source:

- lines 65–68: `if (leader < 0) { /* skip gracefully */ return; }` — no leader elected in 1200 ticks
- lines 72–75: `if (seq <= 0) { … return; }` — commit timeout
- lines 85–88: `if (newLeader < 0) { … return; }` — no new leader after isolation

A seed that takes any of these paths **passes while asserting nothing**. (STATE-OF-REALITY:178 reports an empirical probe found the bail paths fire ~0% — plausible but that probe is not committed; the structural vacuity stands.) `electionSafety` (lines 31–56) does assert per seed, but is itself vacuous for any seed where no leader is ever elected (empty `leadersPerTerm` ⇒ loop body never runs).

### Fault classes: injected (code) vs claimed (ADR-0007:28-29)

| Fault class | Claimed (ADR-0007) | Actually injectable (code) | Evidence |
|---|---|---|---|
| Message drop | YES | **YES** (probabilistic) | `SimulatedNetwork.setDropRate`, line 76 |
| Network partition symmetric | YES | **YES** | `isolate(a,b)` lines 63-66 |
| Network partition asymmetric | YES | **YES** | `addPartition(from,to)` line 53 |
| Message latency | YES | **YES** (uniform 1–10 ms) | ctor lines 33-38, line 78 |
| Message reorder | YES | **PARTIAL** — emergent from random latency only; no explicit reorder API | PriorityQueue by deliverAtMs, line 25 |
| Message duplication | YES | **NO** — no API exists | full read of SimulatedNetwork.java |
| Clock skew (per-node) | YES | **NO** — one shared `SimulatedClock` for the whole cluster | `RaftSimulation.java:38`, ClusterHarness wiring |
| Machine/rack failure (crash+restart with state loss) | YES | **NO** — only message isolation; no process kill / restart-from-durable-state in sim | `RaftSimulation.isolateNode` lines 113-120 |
| Disk failure / slow disk | YES | **NO** — `Storage.inMemory()` only | ClusterHarness, RaftNode ctor line 204 |
| `@Buggify` ~1000 injection points | YES ("~1000") | **NO — 0 production call sites.** `@Buggify`/`BuggifyRuntime` exist in configd-common but `grep -rln "BuggifyRuntime.shouldFire\|@Buggify" --include=*.java` outside configd-common → **zero hits** | grep, exit 1 |

(The April `docs/prr/chaos-report.md` §1.2 already documented most of these gaps — the doc-vs-code table above matches its findings; ADR-0007 remains the over-claiming document.)

---

## 4. jcstress

Search commands and results:

```
$ grep -rn "jcstress" /home/ubuntu/ws-clean --include="*.xml"            → no hits (exit 1)
$ grep -rln "openjdk.jcstress\|@JCStressTest\|jcstress" --include="*.java"  → no hits (exit 1)
$ find /home/ubuntu/ws-clean -iname "*jcstress*" -not -path "*/.git/*"   → no files
```

Only mention anywhere: `docs/STATE-OF-REALITY.md:214` — which itself admits a concurrency stress test "today does not exist".

**FINDING (confirmed): no jcstress dependency, module, or test exists.** The lock-free structures (`VersionedConfigStore` volatile-snapshot publication, `HamtMap` immutable reads) have **no race-condition harness coverage** beyond ordinary JUnit `…ConcurrencyTest` classes. Nothing to smoke-run.

---

## 5. Linearizability harness (configd-linz) — the R-04 "CLOSED" claim

### Module + docs

`configd-linz` exists as a reactor module: separate-JVM cluster (`cluster/ClusterNode` launches `java --enable-preview -jar configd-server-…jar` per node via ProcessBuilder), OS faults (`fault/FaultInjector`: `sudo -n iptables -I INPUT -p tcp --dport <raftPort> -j REJECT --reject-with tcp-reset` + `kill -9`), JDK HttpClient workload, per-key history → Go Porcupine checker (`src/main/go/porcupine-check`, pins `anishathalye/porcupine v1.2.0` via go.mod/go.sum). Matches `docs/a3-harness-design.md` / ADR-0032 / README structure claims.

### runs/ artifacts in the MAIN repo (untracked A3 leftovers, inventoried)

`/home/ubuntu/Code/Configd/configd-linz/runs/`: **8.1 MB, 834 files, 59 run directories** (all dated 2026-06-07, the A3-B session), `runs/` and `bin/` are gitignored (`configd-linz/.gitignore`). Contents: per-seed `history-<seed>-n{3,5}.json` + `schedule-<seed>-n{3,5}.json` for seeds 1,2,3,5,7,8,13,21,42,99 (n3) and **2001–2004 (n3 AND n5)** — exactly the gate-(iii) seeds claimed in the ledger; plus `cluster-*` node dirs with `n*.log`, and discrimination dirs (`lostwrite-*` control/mutated, `staleread-*` control/mutated incl. try1–try3). Also a prebuilt `bin/porcupine-check` (ELF, statically linked Go). The artifact inventory is **consistent with the A3-B ledger narrative** (incl. the multiple `staleread-…-try1/2/3` attempts the ledger describes).

### (a) PORCUPINE_BIN

```
$ which porcupine porcupine-check go     → nothing on PATH
$ find /home /usr/local /opt -iname '*porcupin*'
  /home/ubuntu/Code/Configd/configd-linz/bin/porcupine-check   (prebuilt, untracked)
  /home/ubuntu/go/pkg/mod/github.com/anishathalye/porcupine@v1.2.0   (module cache)
$ ~/sdk/go/bin/go version                → go1.26.4 linux/amd64 (user-local toolchain present)
```

Built fresh in the audit workspace (harness-enablement change, see section below): `bash configd-linz/scripts/build-porcupine.sh` → **built in 2.9 s** → `/home/ubuntu/ws-clean/configd-linz/bin/porcupine-check`.

### (b) PORCUPINE_BIN-gated self-tests

```
$ PORCUPINE_BIN=/home/ubuntu/ws-clean/configd-linz/bin/porcupine-check ./mvnw -pl configd-linz test -o
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 -- in io.configd.linz.CheckerSelfTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in io.configd.linz.HistoryWriterUnitTest
BUILD SUCCESS
```

All 6 previously-skipped self-tests now run and pass, including the decisive flip pairs (pasted): `3a timeout-as-INFO → LINEARIZABLE` / `3b timeout-flipped-to-FAIL → NON-LINEARIZABLE`; `4a linread-503-INFO → LINEARIZABLE` / `4b flipped → NON-LINEARIZABLE`; `5a/5b` likewise; `2 stale-read-anomaly → NON-LINEARIZABLE`. **Gate (i) claim CONFIRMED.**

### (c) Real-cluster scenario

Coordination honored: started only after `/home/ubuntu/SMOKE-DONE` appeared; ports 11400+/10400+ chosen clear of the smoke cluster; pre-run baseline `sudo -n iptables -S` had **zero** REJECT/`--dport` rules.

```
$ java --enable-preview -cp configd-linz/target/classes io.configd.linz.runner.HarnessMain \
    --seed 4242 --nodes 3 --clients 4 --keys 8 --duration 15000 \
    --base-raft 11400 --base-api 10400 --jar configd-server/target/configd-server-0.1.0-SNAPSHOT.jar \
    --out /home/ubuntu/audit-artifacts/linz-run
[harness] schedule -> .../schedule-4242-n3.json (4 faults, 801 planned ops)
[harness] initial leader = node 3
[fault] +2575ms kill -9 node 2, restart in 1605ms
[fault] +5218ms kill -9 node 3, restart in 1362ms
[fault] +8025ms isolate node 3 for 1772ms
[fault] +10942ms isolate node 2 for 1336ms
[harness] recorded 801 ops; history -> .../history-4242-n3.json; checking...
key "k0" ops=36 -> Ok   … (all 8 keys Ok) …
VERDICT: LINEARIZABLE
[harness] seed=4242 nodes=3 faults=4 ops=801 -> LINEARIZABLE      (exit=0)
```

**The harness is REAL and EXECUTES in this environment**: 3 separate `configd-server` JVMs, 4 OS-level faults actually fired (2× `kill -9` with restart, 2× iptables `--dport` REJECT isolation — sudo worked), 801-op concurrent history recorded, trusted Porcupine checker returned **LINEARIZABLE**, exit 0, wall ≈ 25 s.
**Post-run cleanup verified:** `sudo -n iptables -S` byte-identical to the pre-run baseline (`diff` → `IPTABLES-IDENTICAL-TO-BASELINE`; `grep REJECT|--dport` → 0 hits); `ps aux | grep configd-server` → no stray JVMs (the harness's own teardown healed/killed everything; no manual cleanup needed).
**Artifacts:** `/home/ubuntu/audit-artifacts/linz-run/` (`harness.log`, `schedule-4242-n3.json`, `history-4242-n3.json`, `cluster-4242-n3-*/` node logs, `iptables-before/after.txt`).

**R-04 "CLOSED" verdict: SUPPORTED on the executability axis.** Everything I could re-execute matches the A3-B ledger entry: gate (i) reproduced exactly (6/6 + 4/4), one gate-(iii)-style faulted run reproduced (LINEARIZABLE under live faults), the runs/ artifact inventory matches the claimed gate runs (incl. seeds 2001-2004 n3+n5). Not re-verified by me (time): the discrimination gate (ii) RED runs, the full seed matrix, and the gate-(iv) byte-identical-schedule proof (artifact pairs `schedule-200X-n3/n5.json` with identical sizes are consistent with it). The ledger's own honestly-recorded residual — one early non-reproducing non-linearizable history, "a dedicated soak is owed" — remains open.

---

## 6. Chaos / Jepsen

```
$ grep -rln "jepsen" --include="*.java" --include="*.xml" --include="*.clj" --include="*.sh" --include="*.yml"  → no hits (exit 1)
$ grep -rli "jepsen" (all files)   → 20+ hits, ALL .md docs
$ grep -rln "chaos\|Chaos" --include="*.java"   → no hits
```

**CONFIRMED: no Jepsen harness exists** — the word appears only in documentation (plans, audit memos, ADR-0032 comparisons). The only chaos-adjacent code is the testkit fault API covered in §3 (drop/partition/latency — proven to start: the seed sweep above exercises `isolateNode` on every seed) and `@Buggify`, which has **zero production injection points** (§3 table). `docs/prr/chaos-report.md` is an *audit report about* the simulation framework, not a runnable harness.

---

## Harness-enablement changes (complete log)

1. **Built the Porcupine checker binary** in the audit workspace: `bash /home/ubuntu/ws-clean/configd-linz/scripts/build-porcupine.sh` (used pre-existing `~/sdk/go` go1.26.4 + warm module cache; 2.9 s) → `/home/ubuntu/ws-clean/configd-linz/bin/porcupine-check`. Path is gitignored; no tracked file touched. Needed to un-skip `CheckerSelfTest` and run the harness (items 5b/5c).
2. **Scratch copies of `spec/*.tla|.cfg`** to `/home/ubuntu/audit-artifacts/tlc/<Spec>/` so TLC's generated `states/` and trace files don't dirty the clone. No tracked file touched.
3. **One linz scenario run** wrote artifacts to `/home/ubuntu/audit-artifacts/linz-run/` (not the repo) and transiently inserted/removed iptables REJECT rules + killed/restarted its own node JVMs; post-run state verified byte-identical to baseline (§5c).

No production source, test source, POM, or doc in either repo was modified. The only file written to the main repo is this report.

---

## Candidate findings

| ID | Sev | Finding | Evidence |
|---|---|---|---|
| HF-1 | **P1** | **"Zero-allocation read path" is contradicted on the hit path: `VersionedConfigStore.getHit` = 32 B/op** (and `getWithMinVersion` = 32 B/op). Misses and raw HAMT reads are genuinely ≈0 B/op. The two doc numbers for the hit (24 B/op at `performance.md:34`, ~48 B at `:41`) are mutually inconsistent and both ≠ measured. | §1 `-prof gc` table |
| HF-2 | **P1** | **Determinism hole in the flagship "deterministic" simulation:** per-node election RNG is entropy-seeded (`ConsistencyPropertyTests.java:77` → `RaftNode.java:1650`), so "same seed = same execution" does not hold for the 20k-case SeedSweep; the 10k seeds are one scenario × jitter, not 10k schedules. (Already conceded in STATE-OF-REALITY:177-180; independently confirmed in code.) | §3 |
| HF-3 | **P1** | **No jcstress (or equivalent) coverage for the lock-free hot path** (`VersionedConfigStore`, `HamtMap`) — zero dependency/annotations/module. | §4 |
| HF-4 | **P2** | **`commitSurvivesLeaderFailure` has 3 silent-return vacuous-pass paths** (SeedSweepTest.java:65-68, 72-75, 85-88) — a seed can pass while asserting nothing; no counter records how often. Confirms the other agent's finding. | §3 |
| HF-5 | **P2** | **ADR-0007 over-claims fault coverage:** no message duplication, no per-node clock skew, no disk faults, no crash/restart-with-state-loss in the simulation; "~1000 @Buggify injection points" → **0 production call sites**. | §3 table |
| HF-6 | **P2** | **No liveness has ever been model-checked:** the only temporal property (`EdgePropagationLiveness`) is commented out of `ConsensusSpec.cfg`; all 19 checked invariants are safety-only, and `CHECK_DEADLOCK FALSE` everywhere. The TLC safety claims themselves are CONFIRMED exactly (13,775,323/3,299,086/25 · 12,403,444/2,276,125/38 · 5,995,717/847,124/14, all "No error"). | §2 |
| HF-7 | **P3** | **Committed TLC failure artifacts:** 15 `*_TTrace_*` counterexample files + 8 `states/` dirs committed in `spec/` (Apr 10–17). TLC only writes TTrace on failed runs. Consistent with the documented fix history, but they are stale build artifacts in the tree; the ReadIndex/Snapshot TTraces carry the SAME date (2026-04-17) as the `.cfg` "Verified 2026-04-17 … all PASS" comments — fail-before-or-after-green ordering is not determinable from mtimes (clone resets them); git forensics owned by another agent. | §2 |
| HF-8 | **P3** | Doc drift around JMH: `performance.md` §10 wrong path (`src/test` vs `src/main`), lists 6 of 9 classes; `benchmarks.jar -l` double-lists HybridClock entries (shade AppendingTransformer duplication). RaftCommit "815K ops/s" not reproducible from any committed artifact. | §1 |
| HF-9 | **P3** | **R-04 harness executability CONFIRMED** (positive finding): self-tests 6/6 un-skip and pass with a freshly-built checker; one real 3-node faulted run (kill -9 ×2, iptables isolate ×2, 801 ops) → LINEARIZABLE; clean teardown verified. Residuals that remain open and are NOT covered by this audit: gate-(ii) discrimination not re-run, the ledger's own "unresolved rare anomaly" (one early non-reproducing non-linearizable history) still lacks its owed soak, and the harness depends on out-of-band Go (checker binary gitignored — CI runs self-test only when PORCUPINE_BIN is set, i.e. effectively never in default CI: the 6 tests were Skipped in the clean build). | §5 |

Raw logs for everything in this report: `/home/ubuntu/audit-artifacts/` (jmh/, tlc/, sweep-run*.txt, linz-run/).
