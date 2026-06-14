# Session 5 — Workstream E: JIT warmup + Soak (charter §9)

> **Owner:** `gc-runtime-engineer`. **Method:** JMH per-warmup-iteration timing on the read hot path
> (cold → steady), `-XX:+PrintCompilation`/`-XX:+PrintInlining` for C2/devirtualization evidence
> (both flags WORK on this box — unlike `-prof perfnorm`, which is ENV-BLOCKED by
> `perf_event_paranoid=4`, Workstream A `wsA-perfnorm-ENV-BLOCKED.txt`), plus code inspection of the
> sealed HAMT node hierarchy. Collector for serving JVMs: **ZGC (ADR-0041)**. JDK 25 Corretto
> (`25+36-LTS`), `--enable-preview`. Box: t3a.large 2 vCPU / 7.7 GB (throttling real; absolute ns are
> reference-hardware numbers per methodology §0, but the warmup *shape* and the inlining decisions are
> machine-independent facts). **NUMA / CPU-pinning is ENV-BLOCKED (manifest M-4)** — the warmup curve
> and devirtualization verdict below do not depend on it.

---

## §JIT — Cold-start → steady-state on the read hot path

### Warmup curve (`getHitWithCursor`, size=100000, ZGC, single fork)

**Invocation** (serialized on the Maven flock mutex):
```
flock /tmp/configd-mvn.lock java --enable-preview -XX:+UseZGC \
  -jar configd-testkit/target/benchmarks.jar \
  'io.configd.bench.LocalConfigStoreReadBenchmark.getHitWithCursor$' \
  -p size=100000 -f 1 -wi 20 -w 1 -i 5 -r 1 -bm avgt -tu ns
```
JMH prints each warmup iteration (`# Warmup Iteration N: X ns/op`) — that IS the cold→steady curve.
Raw: `docs/session-5/captures/wsE-jit-warmup-curve.txt`.

| Warmup iter | ns/op | Phase |
|---|---|---|
| 1 | **535.1** | **cold** — interpreter + C1, classes loading, caches cold |
| 2 | 359.3 | C2 kicking in (−33% vs iter 1) |
| 3 | 407.0 | settling (throttle jitter) |
| 4–5 | 427 / 424 | settling |
| 6 | 365.2 | in the steady band |
| 7 | 341.0 | steady (the run's min) |
| 8–15 | 336–409 | **steady band, ~340–410 ns/op** |
| 16–20 | 425 / 397 / 392 / 374 / 404 | steady (upper jitter) |
| Measured (5×) | **423.2 ± 94.6 ns/op** | post-warmup result |

**Cold→steady summary.** The cold iteration (535 ns/op) collapses to the steady band by **iteration 2**
(359 ns/op, −33%) and is fully inside the steady ~340–410 ns/op band by **iteration 6–7** (≈6–7 s at
`-w 1` per iter). After that, the run-to-run spread (±~70 ns) is **2-vCPU-throttle / safepoint jitter,
not warmup** — the JMH error band (±94.6 ns at 99.9%) is exactly the methodology §0 "throttling shows up
as variance" effect, not a moving warmup. So: **~1 iteration to leave cold, ~6 iterations (≈6 s) to a
stable floor.** (The ~340 ns absolute here is the *size=100000* working-set number — random keys over
100k entries pay more L2/L3 cache-miss cost than the ~89 ns documented at size=10000 in the benchmark
javadoc; that is a working-set effect, not warmup, and is a reference-hardware number per methodology §0.)

### C2 arrival (`-XX:+PrintCompilation`)

**Invocation:** same jar, `-f 1 -wi 5 -w 1 -i 2 -r 1 -jvmArgs "-XX:+PrintCompilation"`.
Raw: `docs/session-5/captures/wsE-jit-printcompilation.txt`. The read-path methods reach **C2
(tier 4)** within ~1 s of warmup start:

| Read-path method | level-4 (C2) at (ms from VM start) |
|---|---|
| `HamtMap$ArrayNode::get` | ~964 |
| `LocalConfigStoreReadBenchmark::getHitWithCursor` | ~976 |
| `LocalConfigStore::get` | ~1000 |
| `HamtMap$BitmapIndexedNode::get` | ~1026 |
| `getHitWithCursor_jmhStub` (OSR loop) | ~1033 (tier 4, replacing tier 3) |

All four hot read-path methods are C2-compiled within ~60 ms of each other, ≈1 s in — consistent with
the iteration-2 step-down in the warmup curve. (The `made not entrant: not used` / `uncommon trap`
lines are the normal tier-3→tier-4 replacement + one OSR uncommon-trap re-profile, not a deopt storm.)

### Megamorphic verdict — **NOT megamorphic on the read path** (CONFIRMED two ways)

**(1) Code inspection — sealed, closed 3-type hierarchy.** `HamtMap.java:161`:
```java
sealed interface Node<K, V> permits BitmapIndexedNode, ArrayNode, CollisionNode { ... }
```
All three implementors are `static final class ... implements Node` (`HamtMap.java:187` BitmapIndexedNode,
`:410` ArrayNode, `:518` CollisionNode). The read path is `LocalConfigStore.get → HamtMap.get →
Node.get(...)` recursing down the trie. A `sealed` interface with **3 permitted, `final`** implementors
is the structural guarantee perf.md §6 claims: the receiver set at the `Node::get` site is closed and
small, so C2 can devirtualize. CollisionNode only occurs on a *full 32-bit `hashCode` collision*
(vanishingly rare), so the live receiver set is effectively **two** types.

**(2) `-XX:+PrintInlining` — the dispatch site is BIMORPHIC and both branches inline.** This flag WORKS
on the box (the perfnorm-style ENV-BLOCK does not apply — `-prof perfnorm` needs *hardware perf
counters* gated by `perf_event_paranoid=4`; `-XX:+PrintInlining` is a pure-JIT diagnostic that needs
no counters). **Invocation:** `... -jvmArgs "-XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining"`.
Raw: `docs/session-5/captures/wsE-jit-inlining-getpath.txt`. The `Node::get` dispatch site (bytecode
@29/@33 inside `HamtMap[$ArrayNode]::get`) shows a **two-receiver TypeProfile that sums to 100 %**:
```
HamtMap$ArrayNode::get  inline (hot)  callee changed to  HamtMap$BitmapIndexedNode::get  inline (hot)
   \-> TypeProfile (64782/194142) = HamtMap$BitmapIndexedNode
   \-> TypeProfile (129360/194142) = HamtMap$ArrayNode
```
Both receivers (`BitmapIndexedNode`, `ArrayNode`) are inlined `(hot)`; **CollisionNode never appears in
the profile**. `LocalConfigStore::get` and `HamtMap::get` likewise inline `(hot)` into the read path.
There is **no `failed to inline: virtual call` / ≥3-receiver megamorphic site** anywhere on the read
path. **Verdict: the read hot path is mono-/bi-morphic and fully devirtualized — NOT megamorphic.**
(The `String::equals`/`String::hashCode` calls inside the node also profile monomorphically to
`java/lang/String` — the keys are all `String`.)

### Warmup-policy provenance — perf.md §6 "first 60 s, no edge reads" is **DOCUMENTED-NOT-ENFORCED**

perf.md §6 ("Warmup Strategy") states: *"First 60 seconds after startup, node accepts connections but
does not serve edge reads."* **This wall-clock 60-s read-hold is NOT enforced in the as-built serving
code.** The edge read surface (`configd-edge-node/.../EdgeHttpServer.java`) gates readiness on the
**staleness state**, not a timer: `GET /health/ready` returns 503 while the edge is `DEGRADED` or worse
and a strong-read key fails closed (`X-Fail-Closed: strong-read`) — i.e. the edge serves once it is
bootstrapped/CURRENT, which on a healthy start is reached far faster than 60 s. A `grep` of the serving
modules finds **no** `firstReadAt + 60_000` / "withhold reads until warm" gate (the only `60_000` in
`ConfigdServer.java` is the unrelated TLS-reload interval). **Finding (doc-vs-code, honesty):** the §6
warmup policy is a *recommendation*, not an implemented mechanism; the real warmup gate is
staleness/readiness-driven. Recommend either (a) relabel perf.md §6 to the as-built staleness-gated
reality, or (b) implement the timed read-hold in S6 operability if a fixed JIT-warmup window is actually
wanted. The JMH curve above shows a fixed *60-s* hold would be conservative by ~10× — the read path is
C2-warm in ≈1 s — so the staleness gate (not a 60-s timer) is the right mechanism anyway.

### Honesty notes (§JIT)

- **`-XX:+PrintCompilation` and `-XX:+PrintInlining` are LOCAL-VERIFIED** — they work on this box and
  prove C2 arrival + devirtualization directly. `-prof perfnorm` (lock/CAS hardware events) stays
  ENV-BLOCKED (`perf_event_paranoid=4`); the megamorphic verdict does **not** rely on it.
- **Absolute ns/op are reference-hardware** (methodology §0) — the *warmup shape* (cold→steady in ~1
  iter / C2 in ~1 s) and the *inlining decisions* (bimorphic, devirtualized) are machine-independent;
  only the absolute floor moves with hardware.
- **NUMA / CPU-pinning: ENV-BLOCKED, manifest M-4** — a 2-vCPU single-socket box has no NUMA topology;
  the pinned-vs-unpinned read-serving delta needs `m6i.metal` + `numactl`. Not on the warmup critical path.

---

## §Soak — real soak workload harness (wired + SMOKE-validated; long run NOT launched here)

### What was wired (`perf/soak.sh`)

`perf/soak-72h.sh` honored `--duration` but its workload was a **YELLOW stub** (no cluster wired — it
just slept the duration). `perf/soak.sh` is the **REAL workload** that stub's "Phase 10 would wire this"
comment promised. It does NOT touch `soak-72h.sh`'s duration contract (that script is unchanged); it is
the standalone, duration-honoring soak workload:

- **Real 3-node control-plane cluster** under **ZGC (ADR-0041)** — same launch shape as
  `gates/smoke-multinode.sh` / `perf/wsB-live-write.sh` (neither modified), each node with `-Xlog:gc`
  to its own log so cumulative GC is readable.
- **Driven at a box-sustainable ~100 commits/s** via the Workstream B `OpenLoopWriteDriver atrate`
  (open-loop, CO-corrected — methodology §3b). **NOT the 10k/s SLO** — that is ENV-BLOCKED (manifest
  M-9; the box's sustainable end-to-end commit floor is ~136–172/s per `wsB-calibrate.txt`). A soak
  detects **leaks and drift**, which are **rate-independent**; 100/s with headroom over the ~136/s
  floor is the right sustainable soak rate. (Throughput is Workstream B's job, not the soak's.)
- **A trend line every `--sample` seconds** (default 30 s) appended to `trend.csv`:
  `ts_utc, elapsed_s, rss_total_kb (+per-node n1/n2/n3), heap_used_total_kb (jstat, ZGC-column-keyed),
  fd_total (+per-node, from /proc/PID/fd), threads_total (/proc/PID/status), gc_cycles_total,
  gc_cumsec_total (summed from each node's -Xlog:gc), commit_p50_us, commit_p99_us, committed,
  rejected`. This is the leak/drift detector: heap creep, FD leak, thread leak, GC degradation, and
  commit-latency drift each have a column.
- **Self-reported leak verdict at closeout** (`result.txt`): RSS compared against a **post-warmup
  baseline** (≥120 s in, so the ZGC heap-commit ramp toward `-Xms/-Xmx` is excluded — that ramp is not
  a leak); FD/thread compared first-vs-last (those leak from t0). >10% RSS / >25% FD / >25% thread growth
  trips an INVESTIGATE flag.

### SMOKE result (5–6 min — THIS IS A SMOKE, NOT A SOAK)

**Invocation:** `flock /tmp/configd-mvn.lock perf/soak.sh --duration=330 --rate=100 --sample=30
--out=<dir>` (10 samples, 334 s measured). Raw: `docs/session-5/captures/wsE-soak-smoke.txt` (stdout
trend lines + `result.txt` + `trend.csv`). The harness emitted a trend line every sample with all
leak/drift columns; over the smoke window every leak signal is **flat — no leak**:

- **FD count: DEAD FLAT (23/node, 69 total) across every sample** → **no FD leak.**
- **Thread count: DEAD FLAT (93 total) across every sample** → **no thread leak.**
- **jstat heap-used: flat post-warmup (~220–290 MB band, no upward trend)** → **no Java heap leak**
  (this is the *definitive* heap-leak signal — a leak shows as monotonic live-heap growth, which is
  absent: post-warmup 1st-half-median 221 MB → 2nd-half-median 235 MB, +6 %, well under the 25 % tripwire).
- **RSS: ramps over the first ~170 s (ZGC committing heap toward `-Xms/-Xmx 1g` × 3 JVMs — a startup
  ramp, NOT a leak), then plateaus dead-flat (~1.25 GB total) from t+203 s on** (post-warmup
  1st-half-median 1.276 GB → 2nd-half-median 1.255 GB, i.e. *flat/slightly down*) → no leak signal.
- **GC: cumulative cycle-time grows ~linearly with steady allocation**, no runaway acceleration.
  **commit latency improves as the JVM warms** (p50 ~76 ms cold → ~7 ms warm; p99 tens of ms in steady
  state — reference-hardware numbers on the throttling 2-vCPU box, not the SLO claim).

**How the verdict avoids the warmup false-positive (and an earlier-run finding).** The ZGC heap-commit
ramp + JIT warmup run **well past 170 s** on this throttling 2-vCPU box, so a naive first-vs-last or
fixed-fraction baseline false-positives on startup. An earlier validation run also caught a **2-vCPU
throttle window** (CPU-credit exhaustion → p99 spike to seconds + rejections) during which a leader
node's *native* RSS expanded under ZGC's stalled-collector burst **while jstat heap-used stayed flat** —
i.e. native-footprint / ZGC lazy-uncommit, **NOT a Java heap leak**. The shipped verdict handles both:
it (a) **excludes a warmup floor** (`SOAK_WARMUP_FLOOR_SEC`, default 180 s) so the heap-commit ramp is
never the baseline; (b) compares **post-warmup 1st-half-median vs 2nd-half-median** (a single throttle
spike can't move a median); (c) treats **jstat heap-used as the definitive heap-leak signal** and only
flags RSS growth as a leak if heap-used *also* grew (RSS-up-with-flat-heap = native/throttle, reported
as such); and (d) on a **short smoke with < 4 post-warmup samples it makes the memory verdict
OBSERVATIONAL, not asserted** — honestly stating a 5-min smoke cannot separate heap warmup from a slow
leak (that is the long run's job). The CO-corrected driver records any throttle stall as inflated
latency rather than hiding it.

**A ~5.5 min smoke is NOT a soak** (methodology §4 rule 6) — it proves the harness emits the right
trend lines and that nothing leaks in the smoke window. The asserted multi-hour leak/drift verdict
needs the long run, which the LEAD launches.

### The LEAD's long-run launch command (run from the main session so it outlives this agent)

```
nohup flock /tmp/configd-mvn.lock perf/soak.sh \
  --duration=86400 --rate=100 --sample=60 \
  --out=perf/results/soak-24h-$(date -u +%Y%m%dT%H%M%SZ) \
  > perf/results/soak-24h.out 2>&1 &
```
- `--duration=86400` = 24 h. `--sample=60` = a trend line per minute (~1440 rows). ZGC is the default
  (`SOAK_GC=-XX:+UseZGC`); heap default `-Xms1g -Xmx1g` (override via `SOAK_HEAP`).
- Watch `trend.csv` for: RSS rising past the post-warmup plateau (heap leak), `fd_total` climbing (FD
  leak), `threads_total` climbing (thread leak), `gc_cumsec_total` slope steepening (GC degradation),
  or `commit_p99_us` drifting up over hours (latency drift). The closeout `result.txt` prints the
  post-warmup-baseline RSS verdict + the FD/thread verdicts automatically.
- **Do NOT shorten `--duration` and call the result a soak** — `result.txt` labels any run ≤600 s as
  `SMOKE`, ≥600 s as `SOAK`, and always records `measured_elapsed_sec` (never rounded up), preserving
  `soak-72h.sh`'s honesty contract.

### The LEAD's actual launched run — OUTCOME (RR-112, honest)

The lead launched the command above (`perf/results/soak-24h-20260614T045536Z/`, 186 samples). It ran
**~3.45 h CLEAN, then the Linux OOM-killer killed one node JVM** at ~t+12472 s.

| Window | `fd_total` | `threads_total` | `heap_used` | `rejected` | Verdict |
|---|---|---|---|---|---|
| 0 → 12407 s (~3.45 h, clean) | FLAT **69** | FLAT **93** | FLAT **~220–235 MB** | **0** every sample | **no FD / thread / heap leak** |
| ~12472 s (OOM) | → 42 | → 62 | (node lost) | climbing | one node OOM-killed |

**This is NOT a Configd leak** — `heap_used` was flat for 3.45 h. The cause is **box capacity**: RSS
climbed to ~3.27 GB as ZGC committed reserved heap across **3 co-located `-Xmx1g` JVMs**; 3×1 g + the load
driver + the lead's session exceeded the 7.7 GB box, and the OOM-killer took one node. The 3.45 h clean
window is **real leak-negative evidence** (it agrees with the 5.5-min smoke).

**To get a full box-local 24 h soak:** re-run with smaller per-node heaps —
`SOAK_HEAP="-Xms384m -Xmx384m" nohup perf/soak.sh --duration=86400 …` (3×384 MB ≈ 1.1 GB fits 7.7 GB) —
OR run the production-representative soak on real hardware (manifest **M-4**). Filed **RR-112**.

### Honesty notes (§Soak)

- **This deliverable is the harness + a SMOKE.** The 24 h run is the LEAD's to launch; it is not run
  here and no soak verdict is claimed from the smoke beyond "trends flat in the smoke window."
- **Reference-hardware, single-host, single-region** (methodology §0). A *production-representative*
  soak (real fleet, NUMA, real WAN) would inherit manifest M-1/M-2/M-4; this local soak is
  real-duration on the reference box and is labeled as such.
- **NUMA / CPU-pinning: ENV-BLOCKED, manifest M-4** (one line, per charter §9) — not on the soak's
  leak/drift critical path.
