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
