# Phase V — io_uring measurement (the honest close of the Netty migration)

> **Status: COMPLETE.** Syscall axis measured on the dev box (free) and the m6i; throughput/tail
> translation measured on an m6i.4xlarge (16 vCPU, kernel 6.18 AL2023, io_uring confirmed active);
> all four headlines independently reproduced on the box by a second agent before teardown. The
> measured verdict is **§7**. `[dev]` = 2-vCPU dev box (kernel 7.0), `[m6i]` = the measurement box.

## 0. What this measured and why

The Netty migration (ADR-0043, M1–M4) was justified on three grounds: the measured edge-read
allocation win (proven, 8.7×), platform uniformity (delivered), and **io_uring's syscall reduction**
— the operator's *core* rationale, which until Phase V had **never been measured**. io_uring is
auto-selected at runtime where the kernel supports it (`NettyTransport.select()`), Epoll/NIO the
always-correct fallback. Phase V measures the io_uring axis honestly: does it reduce syscalls, and
does that translate to throughput / tail latency, at Configd's workload, on which surfaces?

## 1. Methodology

- **Apples-to-apples:** the SAME production server runs each surface with io_uring and with Epoll;
  the ONLY variable is `-Dconfigd.netty.transport=io_uring|epoll`. The selector is **fail-loud** —
  forcing an unavailable tier is a startup error, never a silent downgrade — so a `tier=io_uring`
  startup line *proves* io_uring is active (the #1 trap: a silent epoll fallback makes
  "io_uring == epoll" meaningless). On the m6i, **20/20** server processes asserted tier==forced.
- **Syscalls — the trustworthy axis.** `strace -f -c` exact counts, **2-batch delta** (`R` and `2R`,
  identical warmup): per-op = `(calls(2R) − calls(R)) / R`, cancelling startup/connection/teardown.
  ptrace inflates wall-clock but not counts.
- **Throughput + tail — UNTRACED.** strace's per-syscall ptrace cost penalises the *higher*-syscall
  transport (epoll), so a traced throughput comparison is not just noisy — **it inverts**: under
  strace the io_uring 1024-subscriber fan-out measured *faster* than epoll (3.49M vs 1.22M notif/s),
  while untraced epoll is **2× faster** (§5). The syscall-count and throughput axes tell opposite
  stories; only the untraced run answers the throughput question. HdrHistogram, closed-loop.
- **Connection-count sweep** (not one point): edge-read 1→1024 connections, fan-out 8→1024
  subscriber streams; consensus measured briefly (few peer connections).
- **Honest axis labelling:** syscall counts are CPU-independent and trustworthy; absolute throughput
  on a benchmark box is relative. The io_uring-vs-epoll *delta* (same box, same workload) is the result.
- **Independent reproduction:** a second agent re-measured all four headlines on the box with its own
  harness (not the matrix script), confirmed io_uring active + same workload + no slow-consumer
  confound, before teardown.

Harness + locked matrix: `docs/netty-migration/scripts/{phase-v-matrix.sh,phase-v-parse.py}` (run
verbatim on dev `PROFILE=dev` and m6i `PROFILE=m6i`); production-server mains in
`configd-testkit/.../io/configd/{edge.node,fanout,consensus}`.

## 2. The key methodological finding: io_uring's batching is per-EVENT-LOOP density

`[dev]` measured edge-read at **7.6× fewer syscalls** at 64 connections; `[m6i]` measured the same
point at **1.10×**. This is not noise — it is the core mechanism. io_uring batches the socket ops
*ready on one event loop* into one `io_uring_enter`; Netty runs **one event loop per core**
(`workerThreads = availableProcessors()`). So the batching factor tracks **connections-per-event-loop**,
not total connections. The 2-vCPU dev box (2 loops) concentrated 64 connections at 32/loop → heavy
batching; the 16-vCPU m6i (16 loops) spread the same 64 at 4/loop → little batching. **The dev-box
numbers overstated io_uring's benefit; the multi-core m6i is the representative measurement.** On
production-class hardware, Configd's "tens-to-low-hundreds" of connections (ADR-0037) give *low*
per-loop density and therefore a *small* syscall reduction.

## 3. Edge-read `[m6i]` — syscalls/op and throughput vs connection count

| conns | io_uring s/op | epoll s/op | reduction | io_uring req/s | epoll req/s | io_uring p99µs | epoll p99µs |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 1    | 3.00 | 2.00 | **0.67× (worse)** | 13,205 | 12,864 | 92.7 | 94.0 |
| 8    | 3.00 | 2.00 | **0.67× (worse)** | 50,746 | 51,217 | 257 | 257 |
| 64   | 1.82 | 2.00 | 1.10× | 53,616 | 54,329 | 2,384 | 2,363 |
| 256  | 1.37 | 2.00 | 1.46× | 52,256 | 51,535 | 8,987 | 9,282 |
| 1024 | 1.44 | 2.00 | 1.39× | 43,213 | 43,711 | 51,839 | 49,906 |

epoll is structurally pinned at **2.00 syscalls/op** (1 `recvfrom` + 1 `writev`; `epoll_wait`
amortised to ~0) at every concurrency. io_uring ranges from **worse** (3.0/op at low density:
submit-read + submit-write + await ≈ 3 enters with nothing to batch) to **1.46× better** at conn256.
**Throughput and p99 are TIED at every connection count** (within ~1–2%). The edge surface is
request-processing-bound (HTTP parse + response build dominate), not syscall-bound — so even the
1.46× syscall reduction yields **no** throughput or tail win. (2nd-agent: throughput tied 53,520 vs
53,440 @ conn64; edge shape reproduced, conn256 1.30× vs 1.46× — `io_uring_enter` coalescing variance.)

## 4. Fan-out `[m6i]` — syscalls/delivery and delivery throughput vs subscriber count

| subscribers | io_uring s/deliv | epoll s/deliv | reduction | io_uring notif/s | epoll notif/s | io_uring/epoll tp |
|---:|---:|---:|---:|---:|---:|---:|
| 8    | 2.116 | 1.184 | 0.56× (worse) | 31,996 | 31,997 | 1.00 |
| 64   | 0.078 | 0.116 | 1.50× | 255,771 | 253,921 | 1.01 |
| 256  | 0.019 | 0.038 | 2.02× | 1,019,398 | 1,017,167 | 1.00 |
| 1024 | 0.005 | 0.024 | **5.33×** | **2,037,310** | **4,024,205** | **0.51** |

Fan-out writes to N subscriber sockets per committed notification, so io_uring's batching of those N
writes grows with N — **up to 5.3× fewer syscalls at 1024 subscribers**. But the throughput goes the
**opposite** way: tied through 256 subscribers, then at 1024 io_uring delivers **~half** of epoll
(2.04M vs 4.02M notif/s) with **~8× worse tail** (p99 24,134 ms vs 3,131 ms). The slow-consumer
governor is ruled out (0 demotions both transports; thresholds raised identically — transport
isolation). **This is a real io_uring throughput deficit at high fan-out**, independently confirmed:
both transports delivered an *identical* 81,920,000 notifications, but io_uring took **41.5 s** vs
epoll's **21.7 s** wall-clock (~1.9×). Same work, ~2× slower — despite 5× fewer syscalls.

## 5. Consensus `[m6i]` — syscalls/frame at one connection

| | io_uring | epoll |
|---|---:|---:|
| socket-IO syscalls/frame | **4.01** (3.01 `io_uring_enter` + 1.00 wakeup `write`) | **2.00** (1.00 `sendto` + 1.00 write) |

io_uring is **~2× WORSE** at one connection — it cannot batch with a single frame in flight
(submit + await-completion per send). Confirms the charter's expectation: the consensus wire (N−1
peers, few connections) sees **no io_uring benefit and a penalty**. Consistent across dev + m6i.

## 6. Kernel / fallback note

io_uring requires a recent kernel (5.x+); on the m6i (AL2023, kernel 6.18, `io_uring_disabled=0`) it
initialised and was confirmed active. It is a **performance tier, never a correctness dependency** —
Epoll/NIO are the always-correct fallback that CI exercises (the M1–M4 contracts run green on
forced-NIO/forced-Epoll), and most deploy targets without io_uring fall back to Epoll with **zero
behavioural change**. Critically, §4 shows Epoll is not merely a safe fallback but the **faster**
transport for high fan-out.

## 7. Verdict — the io_uring rationale is NOT validated; latent at best, a regression at scale

Measured honestly, at Configd's workload on representative multi-core hardware, io_uring's
syscall-reduction mechanism is real but **does not deliver the performance the migration assumed**:

1. **The syscall reduction does not translate to throughput or tail latency anywhere measured.**
   Edge-read is tied io_uring-vs-epoll at every connection count; the per-op syscall reduction is
   also *smaller than the dev box suggested* (1.1–1.5×, not 7.6×) because batching density falls as
   cores/event-loops rise (§2). The workloads are processing-bound, not syscall-bound.
2. **At high fan-out (1024 subscriber streams) io_uring REGRESSES throughput ~2× and tail ~8×** vs
   Epoll, despite 5× fewer syscalls — a genuine, independently-verified deficit (identical delivered
   counts, ~2× wall-time, zero demotions). 1024 is above ADR-0037's current "tens-to-low-hundreds"
   but a plausible growth target — exactly the scale the migration's io_uring bet was *for*.
3. **Consensus (few connections) is ~2× worse** on syscalls; no benefit at its scale either.

So Phase V answers the charter's question with the *latent / not-delivered* verdict, stated for what
it is: **io_uring provides no measured throughput or tail benefit at Configd's workload, and a
throughput regression at high fan-out.** This does **not** unwind the migration — it stands on its
other two, *measured* grounds (the edge-read allocation **8.7×** win + platform uniformity), and
io_uring is a runtime-auto-selected tier with Epoll as the proven, and here **faster**, fallback. But
the io_uring-specific rationale, which was the operator's stated core justification, is **measured as
unrealised**, not validated.

### Scope of the result (honest bounds)
This measures io_uring **as deployed**: Netty 4.2's `IoUring` transport at defaults (no SQPOLL,
default ring sizes, one event loop per core), auto-selected. The high-fan-out deficit may be a
tunable artifact of that configuration (ring sizing, SQPOLL, dedicating fewer/more loops to the
fan-out surface) rather than a fundamental io_uring limit — **unexplored here**. Absolute throughput
is from a benchmark box (relative, not a production SLO); the io_uring-vs-epoll *delta* is the result.

### Recommendations (for the operator — not actioned here)
- **Do not rely on io_uring for a performance benefit** in the migration's justification; the
  honest basis is the edge-read allocation win + uniformity.
- **Consider preferring Epoll for the fan-out surface** (or not auto-selecting io_uring there) until
  the 1024-subscriber throughput deficit is understood — on io_uring-capable hosts, production
  fan-out at high subscriber counts would otherwise run ~2× slower than on Epoll hosts.
- The auto-select-with-fallback design means today (current scale, fan-out ≤256) the choice is
  **performance-neutral**; this is a forward-scale concern, surfaced early by measurement.

## 8. Cost + provenance
- m6i.4xlarge (16 vCPU, 64 GiB), ap-south-1, on-demand: launched 2026-06-26T19:37:52Z, terminated
  ~20:10:40Z ≈ **33 min ≈ $0.47**. Box torn down immediately on capture (SG + key-pair deleted).
- All raw artifacts: `phase-v-results-m6i/` (strace summaries + client logs) + the 2nd-agent's
  independent `verify-out/` were kept on the box; the parsed tables + matrix log were captured
  off-box. jar sha256 `89eb3cd3…` identical dev↔m6i.
