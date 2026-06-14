# Workstream B — Write Path & Throughput (Session 5 Performance Validation)

> **Owner:** `load-test-engineer` + `gc-runtime-engineer`. **Branch:** `session-5-performance`.
> **Binding methodology:** `docs/session-5/methodology.md` (signed off). Every number below cites
> its CO treatment, its LOCAL-VERIFIED / ENV-BLOCKED label, and (for cross-region) the §2 RTT matrix.
> **Collector:** all phases run under **generational ZGC** (ADR-0041). **JDK:** 25 Corretto
> (`25+36-LTS`). **Box:** AWS t3a.large, 2 vCPU / 7.7 GB, burstable (CPU-credit throttling real).

---

## 0. Honesty split up front (what this box can and cannot prove)

| §0.1 target | This doc's result | Label |
|---|---|---|
| Write commit p99 < 150 ms **cross-region** | local commit component measured; cross-region = local + RTT (modeled) | **LOCAL-VERIFIED (local component) + ENV-BLOCKED (WAN) → M-1** |
| Write throughput **10k/s sustained** | box ceiling ≈ **125–172 commits/s** (single-host, 2 vCPU) — 10k/s NOT achievable here | **ENV-BLOCKED** (box-bound; needs production-class hosts — M-?) |
| Write throughput **100k/s burst** | characterized shed behavior at overload | characterization (not pass/fail) |
| GC collector choice | ZGC, with the pause-distribution data | **LOCAL-VERIFIED** (comparative bake-off; fleet absolutes ENV-BLOCKED) |

The cross-region commit total is **never** marked VERIFIED. The 10k/s target is **not faked**: the
generator self-calibration (F4) proves the *system on this box* tops out at ~125–172 commits/s, ~80×
below 10k/s — this is reported as the achieved rate + the reason, per the methodology's honesty rule.

---

## 1. Phase 1 — GC collector decision (ADR-0041): **ZGC**

Full decision + rationale: `docs/decisions/adr-0041-gc-collector.md`. Summary of the bake-off
(`RealApplyCommitBenchmark`, real `ConfigStateMachine` apply ≈ 9 KB/op, `-Xms96m -Xmx96m` to force
a populated pause distribution, JMH `-prof gc` + `-Xlog:gc*`, STW pauses parsed from the log):

| Collector | alloc B/op | alloc MB/s | STW pauses | pause p50 | pause p99 | **pause max** | total STW | throughput |
|---|---|---|---|---|---|---|---|---|
| **G1** | ~9015 | ~9.1 | 8 | 17.6 ms | 20.6 ms | **20.6 ms** (28.9 single-fork) | 122 ms | sim-bound (equal) |
| **ZGC** (chosen) | ~11453 | ~8.2 | 55 | **0.017 ms** | **0.039 ms** | **0.045 ms** | 1.0 ms | sim-bound (equal) |
| **Shenandoah** | ~9040 | ~5.1 | 32 | 0.060 ms | 0.905 ms | **0.905 ms** | 3.6 ms | sim-bound (equal) |

- Pauses parsed from **actual `-Xlog:gc*` STW lines**, NOT JMH `gc.time` (which conflates ZGC's
  concurrent work — ZGC `gc.time` 1383 ms but STW sum **1.0 ms**). "No ZGC-because-low-pause without
  the pause histogram" — the table IS the per-collector distribution.
- **Decision: ZGC** — max STW pause **0.045 ms** vs G1 **20.6 ms** (~450×) and Shenandoah **0.905 ms**
  (~20×), at **no throughput cost** (throughput sim-bound, equal across collectors). ZGC's honest
  cost: higher alloc B/op (~11.4 KB vs ~9.0 KB — colored-pointer/barrier bookkeeping).
- **Read path collector-robust:** WS-A's 0-alloc read path (p99<1 ms verdict on G1) holds under any
  collector; ZGC only tightens the µs/ms tail a read could land behind.
- **Heap:** bake-off `-Xms96m -Xmx96m` (to populate pauses); serving JVM (Phases 2–4) `-Xms1g -Xmx1g`.
  Fleet-scale heap (10^9 keys) **ENV-BLOCKED**.
- **Exact invocation:**
  ```
  flock /tmp/configd-mvn.lock java --enable-preview \
    -XX:+UseZGC -Xms96m -Xmx96m \
    -Xlog:gc*:file=docs/session-5/captures/gclogs/bakeoff-zgc-96m.log:time,uptime,level,tags \
    -jar configd-testkit/target/benchmarks.jar \
    RealApplyCommitBenchmark -p clusterSize=3 -p valueBytes=256 \
    -f 2 -wi 3 -w 3 -i 4 -r 3 -prof gc -rf json -rff docs/session-5/captures/bakeoff-zgc-96m.json
  ```
  (G1: `-XX:+UseG1GC`; Shenandoah: `-XX:+UseShenandoahGC`. ZGC generational by default in JDK 25 —
  the removed `-XX:+ZGenerational` is NOT passed.)
- **Raw:** `docs/session-5/captures/bakeoff-{g1,zgc,shen}-96m.{txt,json}`,
  `docs/session-5/captures/gclogs/bakeoff-{g1,zgc,shen}-96m.log.{0,1}`.

---

## 2. Phase 2 — Write-commit latency: local VERIFIED + cross-region MODELED

### 2.1 Local quorum-commit component (LOCAL-VERIFIED)

Measured on the **live 3-node single-host cluster** over the real HTTP→propose→quorum→commit→200
path (`OpenLoopWriteDriver atrate`, open-loop intended-time scheduling, CO-corrected — latency =
completion − **scheduled** send time). This is the most faithful local measurement: real loopback
HTTP, real 3-node Raft replication, real fsync, real apply. Driven **well under the box ceiling** so
the number reflects per-commit cost, not queueing.

| Offered rate | count | p50 | p90 | p99 | p999 | p9999 | max | CO |
|---|---|---|---|---|---|---|---|---|
| **50 writes/s** (clean) | 1500 | **7.07 ms** | 11.35 ms | **15.74 ms** | 22.42 ms | 23.01 ms | 23.01 ms | open-loop, intended-time |
| 100 writes/s | 3000 | 9.92 ms | 96.9 ms | 286.7 ms | 303.1 ms | 311.8 ms | 311.8 ms | open-loop, intended-time |

- **Authoritative local commit component: p50 ≈ 7 ms, p99 ≈ 16 ms** (the 50/s clean run — the box
  has headroom there, so the distribution reflects the commit mechanism, not contention).
- The **100/s tail blow-up (p99 287 ms)** is real and instructive: at 100/s the 2-vCPU box begins to
  contend (GC/scheduling/periodic election-timer interference), and the **CO correction surfaces the
  stalls** as inflated intended-time latency rather than hiding them — exactly the methodology §3b
  behaviour. It is a box-contention artefact, not the commit mechanism; the 50/s run is the clean
  local component.
- **Scope honesty:** this is the *single-host* commit (CPU + loopback + fsync). No real network leg.
- **Cross-check (in-process CPU floor):** the tick-driven in-memory commit (`WriteCommitDriver
  commit-latency` / `RealApplyCommitBenchmark -bm sample`) has a per-op **min ≈ 122–210 µs** of pure
  consensus CPU, but its *median* (~2 ms) is dominated by the sim's deliver/tick plumbing (it ticks
  all nodes up to 50× per commit) — so the tick-driven harness is **NOT** a faithful local-latency
  percentile (a stated finding; "tick-driven ≠ wall-clock rate", methodology). The live HTTP number
  above is the authoritative local commit component; the in-process min is only a CPU-floor sanity
  check that the ~7 ms HTTP p50 is dominated by loopback+fsync+HTTP, not by consensus CPU.
- **Invocation:** `OpenLoopWriteDriver atrate <nodeMap> 50 30 2 256` (raw:
  `docs/session-5/captures/wsB-phase2b-50.txt`; 100/s: `wsB-phase2-lowrate.txt`).

### 2.2 Cross-region commit p99 (MODELED — ENV-BLOCKED, M-1)

Applying methodology §2: `modeled_commit_p99 = local_commit_component + RTT(follower completing the
majority)`, a **lower bound** (the local component already carries the serial leader-side terms:
HTTP, propose serialization, fsync, apply — they are inside the ~16 ms p99 above).

| Placement | local p99 component | + RTT (§2 matrix) | **modeled cross-region p99** | vs 150 ms target |
|---|---|---|---|---|
| 5-voter global (us-east leader; 2nd-fastest = eu-west) | 16 ms | + **68 ms** | **≈ 84 ms** | < 150 ms ✅ **PENDING (M-1)** |
| 3-voter co-located (fastest = us-west) | 16 ms | + **57 ms** | **≈ 73 ms** | < 150 ms ✅ **PENDING (M-1)** |

- **Label: LOCAL-VERIFIED (local component) + ENV-BLOCKED (WAN component) → manifest item M-1.** The
  cross-region total is reported `PENDING real-hardware confirmation`, **never VERIFIED** (the RTT
  matrix is a declared model input; real region-pair RTT *and its p99 jitter* — which matters more
  than the median for a p99 commit target — needs multi-region hosts).
- Both placements clear the 150 ms target with comfortable margin **in the model**, even with the
  box-inflated 16 ms local p99 (a production-class single-region leader would have a tighter local
  component, widening the margin). This does **not** make the target green.

---

## 3. Phase 3 — 10k/s sustained: **NOT achievable on this box (ENV-BLOCKED)**

### 3.1 F4 generator/system self-calibration (the precondition)

Before trusting any latency-at-rate, the generator+system ceiling was measured against the live
cluster (closed-loop, N workers; `OpenLoopWriteDriver calibrate`). The ceiling is a **system**
ceiling here, not a generator ceiling — the generator (curl/HttpClient) can issue far faster than the
single-host 3-node cluster can commit on 2 shared vCPUs:

Two sweeps were run (a degraded/loaded cluster and a warm/clean one); both put the ceiling in the
**~125–172 commits/s** band. The clean sweep (`wsB-calibrate.txt`):

| concurrency | committed/s (clean) | non-200 | (degraded-run committed/s) | note |
|---|---|---|---|---|
| 1 | 136 | 0 | 71 | single in-flight |
| 2 | 150 | 0 | 111 | |
| **4** | **172** | **0** | **125** | **peak, clean** |
| 8 | 160 | 0 (degraded: 105×503) | 117 | high-conc starts starving heartbeats |
| 16 | — | — | 121 (29×503) | churn (degraded run) |
| 32 | — | — | 58 (998×503) | thrashing — heartbeats starved (degraded run) |

- **System commit ceiling on this box ≈ 125–172 writes/s** (concurrency 4, zero errors; the exact
  figure depends on cluster warmth / CPU-credit state). Beyond ~4–8 in-flight, throughput *drops*:
  the write threads starve the Raft heartbeat/election threads on 2 vCPUs, the leader loses
  CheckQuorum and steps down, and writes bounce as 503 (NotLeader). This is a **box** limit (2 shared
  vCPUs running 3 JVMs + loopback HTTP + fsync), not a design limit.
- Raw: `docs/session-5/captures/wsB-calibrate.txt`.

### 3.2 The 10k/s run (held 70 s ≥ 60 s requirement)

`OpenLoopWriteDriver atrate <nodeMap> 10000 70 256 512` — 700,000 intended writes (10k/s × 70 s):

| metric | value |
|---|---|
| intended | 700,000 |
| **committed (200)** | **4,800** (achieved ≈ **62 commits/s**) |
| rejected at generator (worker pool/queue full = backpressure) | 646,216 |
| 503 (NotLeader/Lost — leadership churn under load) | 47,600 |
| 504 (commit deadline exceeded) | 1,363 |
| exceptions | 21 |
| retargets (leader-hint follows) | 170 |
| CO-corrected latency | p50 1.8 ms · **p90 2.26 s · p99 5.21 s · p999 7.98 s · max 11.36 s** |

- **Verdict: 10,000 writes/s is NOT sustainable on this box.** Achieved ≈ 62 commits/s under the 10k
  offer; the system saturates ~80–160× below target. The **CO correction makes the saturation
  honest** — requests scheduled during the stall accumulate multi-second intended-time latency
  (p99 5.2 s), instead of the naive-closed-loop lie that would have reported a flattering sub-ms p50
  on the handful that completed.
- **This is an ENV-BLOCKED result, reported as the achieved rate + the reason, not a fake pass.** The
  §0.1 throughput target is a "fully-local single-host mechanism" (methodology §1), but on THIS
  single host (2 burstable vCPUs) the mechanism is box-bound at ~125–172/s. Confirming 10k/s sustained
  needs a production-class host (more cores so write threads don't starve Raft heartbeats; faster
  storage for fsync). Enumerated for `infrastructure-manifest.md`.
- Raw: `docs/session-5/captures/wsB-phase3-10k.txt`.

---

## 4. Phase 4 — 100k/s burst characterization (hands to Workstream D)

### 4.1 What sheds, and how (the operative finding for D)

At write overload on this box, the **dominant shed mechanism is leadership instability (503
NotLeader/Lost), which trips BEFORE any architected backpressure**:

- A rapid PUT slam at the leader (1,500 rapid PUTs, parallel) returned **10×200 / 1,490×503** — the
  leader is CPU-starved by the write load, loses quorum heartbeats, and **steps down (CheckQuorum)**;
  writes then bounce as 503. Raw: `docs/session-5/captures/wsB-phase4-ratelimit-probe.txt`.
- At 100k/s offered (2 s intended = 200,000 requests), the generator itself cannot maintain the 10 µs
  intended cadence on 2 vCPUs (a **generator-saturation finding per F4** — the 2 s window took 7.9 s
  wall), and the saturated worker pool sheds **199,112 / 200,000 (99.6%) at the bounded-queue
  rejection**; of the few that reached the server, **862 committed (200), 26 got 503, ZERO got 429**.
  So at extreme overload the shed is overwhelmingly **generator/client-side backpressure + a little
  503 churn — the architected server 429 paths are NOT reached on this box.** CO-corrected latency
  p50 = 3.46 s, p99 = 4.62 s, max = 6.0 s (the saturation, made honest). Raw: `wsB-phase4-100k.txt`.
- **Why we do NOT see the architected backpressure (429) here:** the box cannot push enough committed
  throughput to fill either backpressure trigger before CPU starvation collapses leadership:
  - the **HTTP write rate limiter** (hard-coded **10,000/s, burst 10,000** — `ConfigdServer.java:481`,
    maps a reject → `WriteResult.Overloaded` → **HTTP 429**) would shed at 10k/s, but the box never
    sustains 10k committed/s;
  - the **Raft pending-proposal queue** (`maxPendingProposals` default **1024** —
    `RaftConfig.of`, line 183 → `ProposalResult.OVERLOADED` → **HTTP 429**) would shed at 1024
    uncommitted, but the box commits too slowly to ever accumulate 1024 in flight before the leader
    steps down.

### 4.2 FLAG for Workstream D — arch §11 doc-vs-measured mismatch (do not fix here)

- **arch §11** ("Backpressure & Overload Policy") says **Write reject at "Raft queue > 1000 entries"**
  (and "Accept when queue < 500").
- **As-built:** the Raft backpressure trigger is `maxPendingProposals` **= 1024** (default;
  `RaftConfig.of` line 183; `RaftNode.propose` rejects when `lastIndex − commitIndex >=
  maxPendingProposals` → `OVERLOADED` → HTTP 429). This corroborates **S4 EXP-010**
  (`queuePlateau = 1024 = maxPendingProposals`).
- **MISMATCH: doc "1000" vs as-built "1024".** Also: arch §11 names a second trigger ("Raft apply lag
  > 5000 → 503") and a hysteresis ("Accept when queue < 500") that the as-built single-threshold
  `maxPendingProposals` gate does **not** implement (no separate apply-lag-503 path, no
  500-entry hysteresis on the propose gate observed). **FLAGGED for Workstream D — not fixed here**
  (per scope: WS-B does not touch the register or the backpressure thresholds).

### 4.3 Saturation point + shed summary (for D's matrix)

| dimension | measured on this box |
|---|---|
| sustained commit ceiling | ≈ 125–172 writes/s (concurrency 4) |
| first failure mode under load | **503 leadership step-down (CheckQuorum)** from CPU starvation |
| architected 429 (rate-limit 10k/s) | not reached (box can't sustain 10k/s) |
| architected 429 (Raft queue 1024) | not reached (commits too slow to accumulate 1024 in flight) |
| architected 503 (apply lag > 5000) | not reached / not implemented as a distinct path — FLAG for D |
| 504 (commit deadline) | appears under overload (commit cannot complete within the write deadline) |
| useful metric for D | `configd_raft_pending_apply_entries` (gauge, exposed at `/metrics`) |

- Raw: `docs/session-5/captures/wsB-phase4-100k.txt`, `wsB-phase4-ratelimit-probe.txt`.
- **Honest caveat:** because the box collapses leadership before the architected backpressure
  engages, the *architected* shed thresholds (rate-limiter 10k/s, Raft-queue 1024, apply-lag 5000)
  could not be exercised end-to-end here — verifying that the 429/503-apply-lag ladder fires at its
  designed thresholds is **ENV-BLOCKED** (needs a host that can sustain enough throughput to reach
  them) and is Workstream D's domain. The plateau **value** (1024) is confirmed from code + S4.

---

## 5. Coordinated-omission statement (per harness)

| Harness | mode | CO treatment |
|---|---|---|
| `RealApplyCommitBenchmark` (Phase 1 alloc/GC, Phase 2 cross-check) | JMH Throughput / SampleTime | CO structurally absent (no arrival schedule; per-op service time — methodology §3a). |
| `WriteCommitDriver bakeoff` (alloc generator) | closed-loop | CO N/A — measures allocation/GC/throughput, not a latency percentile (§3b allows closed-loop for saturation/throughput). |
| `OpenLoopWriteDriver atrate` (Phase 2 latency, Phase 3 10k/s) | **open-loop, intended-time** | latency = completion − **scheduled** send time; stalls inflate (not drop) the affected requests; generator backpressure counted, never hidden (§3b). |
| `OpenLoopWriteDriver calibrate` (F4, Phase 4 slam) | closed-loop | used ONLY for the ceiling/saturation throughput, never for a reported latency percentile (§3b-permitted). |

---

## 6. Reproduction (committed harnesses + exact invocations)

All harnesses are committed and re-runnable; serialize Maven/JMH on `flock /tmp/configd-mvn.lock`.

- **GC bake-off (Phase 1):** `RealApplyCommitBenchmark` +
  `configd-testkit/.../raft/InMemoryRaftCluster.java`. Invocation in §1 (run per collector).
- **Local commit latency / cross-region model (Phase 2):** `OpenLoopWriteDriver atrate` against the
  live cluster (`perf/wsB-live-write.sh phase2`). In-process cross-check:
  `WriteCommitDriver commit-latency` / `RealApplyCommitBenchmark -bm sample`.
- **10k/s + calibration (Phase 3):** `perf/wsB-live-write.sh phase3` / `calibrate`.
- **100k/s burst (Phase 4):** `perf/wsB-live-write.sh phase4` (+ the rate-limit slam probe).
- **Live cluster launcher (does NOT modify any `gate-*.sh`):** `perf/wsB-live-write.sh`
  (3-node localhost cluster, ZGC, leader-following open-loop driver).
- **Raw captures:** `docs/session-5/captures/wsB-*.txt`, `docs/session-5/captures/gclogs/`.

### Build
```
flock /tmp/configd-mvn.lock ./mvnw -B -pl configd-testkit,configd-server -am package -Dmaven.test.skip=true
```

---

## 7. Verdicts (one line each)

- **GC collector:** **ZGC** (ADR-0041) — max STW pause 0.045 ms vs G1 20.6 ms, no throughput cost. LOCAL-VERIFIED (comparative; fleet absolutes ENV-BLOCKED).
- **Local write-commit p99:** **≈ 16 ms** (p50 ≈ 7 ms), single-host loopback + real fsync. **LOCAL-VERIFIED (local component)**.
- **Cross-region write-commit p99 (modeled):** **≈ 84 ms** (5-voter) / **≈ 73 ms** (3-voter), both < 150 ms — **PENDING real-hardware confirmation (M-1)**, never VERIFIED.
- **10k/s sustained:** **NOT achievable on this box** — ceiling ≈ 125–172 commits/s (2 vCPU). **ENV-BLOCKED** (achieved rate + reason reported; not faked).
- **100k/s burst:** dominant shed = **503 leadership step-down from CPU starvation**, before any architected 429; Raft-queue plateau confirmed **1024** (vs arch §11 "1000") → **FLAGGED for Workstream D**.
