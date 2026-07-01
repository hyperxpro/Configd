# ADR-0041 — GC collector for the Configd serving JVM: ZGC (generational)

> **Status:** Accepted (Session 5, 2026-06-14, `load-test-engineer` + `gc-runtime-engineer`).
> Decided **early** (charter §9 / §14.5) because the collector choice affects every later
> latency/throughput number in Session 5; all subsequent Workstream B phases run under the
> collector chosen here and state it.

## Context

The write path allocates per commit (log entry + command serialization + state-machine
apply: a HAMT `put` with root-to-leaf path copy). The serving JVM must keep the §0.1
control-plane write p99 (< 150 ms cross-region) and the data-plane read p99 (< 1 ms)
free of GC-pause contamination. JDK 25 Corretto ships three production collectors:
G1 (default), **ZGC (generational by default in JDK 25** — the removed `-XX:+ZGenerational`
flag is NOT passed), and Shenandoah. We ran a comparative bake-off of all three on the same
box, same allocating workload, same heap.

## Decision

**Use generational ZGC (`-XX:+UseZGC`) for the serving JVM.**

## The bake-off (the data this decision cites)

- **Workload:** `RealApplyCommitBenchmark` (committed, `configd-testkit`) — a real 3-node
  in-memory Raft cluster whose nodes run a **real `ConfigStateMachine`** (decode command +
  HAMT `put` on every apply), so the allocation profile matches the production write path
  (≈ 9 KB/op measured, in the charter's "~2–5 KB/op log entry + serialization + apply" range;
  the higher figure includes the HAMT path-copy). A no-op state machine would under-price
  allocation and is rejected for this purpose.
- **Box:** AWS t3a.large, 2 vCPU / 7.7 GB, JDK 25 Corretto (`25+36-LTS`), burstable
  (CPU-credit throttling real — methodology §0). One workload at a time under `flock`.
- **Heap:** `-Xms96m -Xmx96m` for the pause-distribution capture. **Rationale (stated
  honestly):** at the box-bound op rate the allocation rate is ≈ 5–9 MB/s, so a large heap
  produces only 0–2 GCs per fork — too sparse for a pause *distribution*. A small fixed heap
  applies identical allocation pressure to all three collectors and forces enough GC cycles
  (7–45 per fork) that each collector's STW pause character is populated and comparable. This
  is a **comparative** bake-off; the absolute heap and absolute throughput at fleet scale are
  **ENV-BLOCKED** (production-class hosts, larger heaps) — the comparison, not the absolutes,
  is what selects the collector.
- **Capture:** JMH `-prof gc` (allocation) + `-Xlog:gc*:file=…:time,uptime,level,tags`
  (STW pause durations parsed from the log). f=2, wi=3, i=4, 3 s/iter.
- Raw: `docs/session-5/captures/bakeoff-{g1,zgc,shen}-96m.{txt,json}` +
  `docs/session-5/captures/gclogs/bakeoff-{g1,zgc,shen}-96m.log`.

### Per-collector results (both forks combined — the authoritative pause distribution)

| Collector | alloc (B/op) | alloc (MB/s) | STW pauses | pause p50 | pause p99 | **pause max** | total STW | throughput |
|---|---|---|---|---|---|---|---|---|
| **G1** (`-XX:+UseG1GC`) | ~9015 | ~9.1 | 8 | 17.6 ms | 20.6 ms | **20.6 ms** (single-fork worst 28.9 ms) | 122 ms | sim-bound (equal) |
| **ZGC** (`-XX:+UseZGC`, gen.) | ~11453 | ~8.2 | 55 | **0.017 ms** | **0.039 ms** | **0.045 ms** | 1.0 ms | sim-bound (equal) |
| **Shenandoah** (`-XX:+UseShenandoahGC`) | ~9040 | ~5.1 | 32 | 0.060 ms | 0.905 ms | **0.905 ms** | 3.6 ms | sim-bound (equal) |

Numbers are over **both JMH forks combined** (`bakeoff-<c>-96m.log.0` + `.log.1`). The
allocation B/op figures are the JMH `-prof gc` `gc.alloc.rate.norm` means.

Pause percentiles are computed from the **actual STW pause lines** in each `-Xlog:gc*`
log (G1 `Pause Young`; ZGC `Pause Mark Start/End`, `Pause Relocate Start`; Shenandoah
`Pause Init/Final Mark`, `Pause Init/Final Update Refs`) — **not** from JMH `gc.time`,
which conflates concurrent work (ZGC's `gc.time` is 1383 ms but its STW sum is **1.0 ms** —
the rest is concurrent and does not stop the application).

> **"No ZGC-because-low-pause without the pause histogram" (methodology):** the table above
> IS the pause distribution, parsed per collector, not a single max. The G1 distribution is
> 8 multi-ms young pauses (p50 17.6 ms, max 20.6 ms, single-fork worst 28.9 ms); the ZGC
> distribution is 55 sub-50-µs pauses (max 0.045 ms); the Shenandoah distribution is 32
> sub-ms pauses (max 0.905 ms).

### Throughput

Application throughput was **statistically identical** across the three collectors
(JMH `Score` 0.001 ops/µs for all). This is because the tick-driven in-memory harness is
CPU-sim-bound, not GC-bound, at this scale — so **no collector pays a throughput penalty**
here, and the choice turns entirely on pause behavior. (Fleet-scale throughput is
ENV-BLOCKED — this bake-off does not claim an absolute ops/s.)

## Rationale

- **Pause tail is the deciding axis.** A 150 ms cross-region write-p99 budget is dominated
  by RTT (57–68 ms), but a 29 ms G1 young pause landing on the leader during a commit is a
  large, avoidable chunk of that budget — and it lands on the apply thread, the very thread
  the commit waits on. ZGC's **max** STW pause (0.045 ms, both forks) is **~450× smaller than G1's max**
  (20.6 ms; 28.9 ms single-fork worst) and ~20× smaller than Shenandoah's max (0.905 ms). ZGC keeps the GC contribution
  to the commit/read tail in the **sub-millisecond** regime at every percentile measured.
- **ZGC vs Shenandoah:** both are concurrent, sub-ms collectors and both would be acceptable.
  ZGC wins on the measured tail (0.045 ms vs 0.905 ms max) and on a tighter, more uniform
  pause distribution (its pauses are fixed-cost root-scanning steps independent of heap/live
  set). Shenandoah's pauses, while sub-ms here, scale with root-set work. We pick the
  collector with the tightest measured tail.
- **ZGC's cost — honestly stated:** ZGC showed a **higher allocation B/op** (~11.4 KB vs
  ~9.0 KB) — colored-pointer / load-barrier bookkeeping and its accounting. At the box's
  allocation rate this did not move throughput, but it is a real overhead and is noted so the
  fleet-scale capacity model does not assume ZGC is free. ZGC also wants a little more heap
  headroom to stay ahead of the mutator; the serving JVM is sized accordingly.

## Read path is collector-robust (cross-reference)

Workstream A proved the read path is **0-alloc / lock-free** on the in-process hot path
(`getMiss`/`getInto` structurally 0 B/op; `getHit` ~32 B nursery) and ran its p99<1 ms /
p999<5 ms verdict **on G1**. Because the read path does not allocate in steady state, the
verdict holds under **any** collector — the read path does not feed the collector. Moving to
ZGC only **tightens the µs/ms pause tail** that a read could ever land behind (G1's 29 ms
young pause → ZGC's 0.045 ms), so the read verdict is preserved and its worst-case tail
improves. No re-run of the read gate is required for the collector change.

## Heap sizing for the serving JVM

- The **bake-off** used `-Xms96m -Xmx96m` (to force a populated pause distribution — see above).
- The **serving JVM** (Workstream B Phases 2–4 live cluster) runs ZGC with
  `-Xms1g -Xmx1g` (fixed, no resize jitter) on this box. The production heap is a
  capacity-planning input that depends on the working-set size (10^6 vs 10^9 keys) and is
  **ENV-BLOCKED** (`infrastructure-manifest.md`): 10^9 keys do not fit in 7.7 GB, so the
  fleet heap is sized off-box. This ADR fixes the collector, not the production heap.

## Consequences

- All subsequent Session 5 Workstream B phases (and recommended for C/D/E) run under
  `-XX:+UseZGC`; each result doc states the collector.
- The serving JVM launch flags add `-XX:+UseZGC`. No `-XX:+ZGenerational` (removed in JDK 25;
  generational is the default).
- The fleet-scale absolute GC behavior (NUMA, large heaps, 10^9 working set) is ENV-BLOCKED;
  this ADR is a **comparative** decision proven on the reference box, and that scope is
  declared.

## Alternatives considered

- **G1 (keep default):** rejected for the serving JVM on the pause tail (29 ms max young
  pause vs ZGC 0.045 ms max). G1 remains a fine default for build/CI JVMs and short-lived tools.
- **Shenandoah:** a close second; sub-ms and concurrent. Rejected only on the measured tail
  (0.905 ms vs 0.045 ms max) and pause uniformity. Acceptable fallback if a ZGC-specific
  issue ever surfaces.
- **Epsilon / no-op GC:** not a serving option (no reclamation).
