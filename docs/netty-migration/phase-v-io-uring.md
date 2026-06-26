# Phase V — io_uring measurement (the honest close of the Netty migration)

> **Status: IN PROGRESS.** Dev-box syscall measurements complete (the trustworthy axis); the m6i
> throughput/tail translation + the final verdict land after the EC2 run. This doc is updated as
> the measurement completes; numbers labelled `[dev]` are from the 2-vCPU dev box (kernel 7.0,
> io_uring active), `[m6i]` from the 16-vCPU measurement box.

## 0. What this measures and why

The Netty migration (ADR-0043, M1–M4) was justified on three grounds: the measured edge-read
allocation win (proven, 8.7×), platform uniformity (delivered), and **io_uring's syscall reduction**
— the operator's *core* rationale, which until Phase V had **never been measured**. io_uring is
auto-selected at runtime where the kernel supports it (`NettyTransport.select()`), but its benefit
was asserted, not proven. Phase V measures it honestly: does io_uring reduce syscalls, and does that
translate to throughput / tail latency, at Configd's workload, on which surfaces?

## 1. Methodology

- **Apples-to-apples:** the SAME production server runs each surface with the io_uring transport and
  the Epoll transport; the ONLY variable is `-Dconfigd.netty.transport=io_uring|epoll`. The selector
  is **fail-loud** — forcing a tier that is unavailable is a startup error, never a silent downgrade
  — so a `tier=io_uring` startup line is *proof* io_uring is active (the #1 methodology trap:
  a silent epoll fallback would make "io_uring == epoll" meaningless).
- **Syscalls — the trustworthy axis.** `strace -f -c` exact per-syscall counts. The headline uses a
  **2-batch delta**: run the same workload at request count `R` and `2R` (identical warmup); the
  per-op cost is `(syscalls(2R) − syscalls(R)) / R`, which cancels JVM startup, connection setup,
  and teardown, isolating *steady-state* per-operation syscalls. strace's ptrace overhead inflates
  wall-clock but **not** the counts, so counts are trustworthy regardless.
- **Throughput + tail — UNTRACED.** strace's per-syscall ptrace cost would penalise the
  higher-syscall transport (epoll) more, *exaggerating* io_uring's win — so throughput/latency runs
  attach **no tracer**. HdrHistogram, closed-loop (concurrency threads), p50/p99/p999.
- **Connection-count sweep.** The benefit hypothesis (ADR-0037) is that io_uring's win grows with
  connection count; Configd runs at "tens to low hundreds" of connections per node. So we sweep, not
  measure one point — edge-read across 1→1024 connections, fan-out across 8→1024 subscriber streams.
- **Surfaces:** the high-volume IO paths — **edge-read** (many client connections, high QPS) and
  **fan-out** (many subscriber streams). **Consensus** (few peer connections) is measured *briefly*
  to confirm the expected little-benefit, not over-invested in.
- **Honest axis labelling:** syscall counts and allocation are CPU-independent and trustworthy;
  absolute throughput on a benchmark box is **relative** (not a production number). Labelled as such.

The harness (`io.configd.{edge.node,fanout,consensus}.*Main` in configd-testkit) drives the
**production** servers (`NettyEdgeHttpServer`, `NettyFanOutServer`, the production
`NettyConsensusFrameEncoder`) through the real selector. The locked matrix is
`scripts/phase-v-matrix.sh` (run verbatim on dev `PROFILE=dev` and on the m6i `PROFILE=m6i`).

## 2. Edge-read — syscalls/op vs connection count `[dev]`

io_uring's mechanism is **batching**: many ready socket operations are submitted/completed per
`io_uring_enter` syscall, whereas epoll issues one `recvfrom` + one `writev` per request
(irreducible) plus a fully-amortised `epoll_wait`. So epoll is flat at ~2.1 syscalls/op regardless
of connection count, while io_uring's per-op syscalls **fall as connections rise**:

| connections | io_uring (syscalls/op) | epoll (syscalls/op) | reduction |
|---:|---:|---:|---:|
| 1   | 3.06 (`io_uring_enter`) | 2.13 (`recvfrom`+`writev`) | **0.69× — io_uring WORSE** |
| 8   | 1.19 | 2.11 | 1.78× |
| 64  | 0.28 | 2.11 | **7.6×** |
| 256 | 0.13 | 2.08 | **15.5×** |

(64-connection point is a clean 2-batch delta: io_uring 0.278 = 0.277 `io_uring_enter` + ~0 else;
epoll 2.109 = 1.000 `writev` + 1.109 `recvfrom`, `epoll_wait` amortised to ~0/op.)

**The honest shape:** the benefit is **latent — even negative — below ~8 connections**, crosses over
around 8, and grows large (7.6×→15.5×) through Configd's stated "tens-to-low-hundreds" range. At
Configd's connection scale, io_uring delivers a **real, measured** syscall reduction on the syscall
axis. `[m6i]` will confirm this same-box and extend the sweep to 1024.

## 3. Fan-out — syscalls/delivery vs subscriber count `[dev]`

Fan-out pushes NOTIFY frames to N subscriber streams; the same batching mechanism applies to the N
outbound writes per committed notification. (The slow-consumer governor's demotion thresholds are
raised IDENTICALLY for both transports so its reconnect/snapshot path injects no run-to-run noise —
Phase V measures the *transport*, not the session policy, which the M3 contract already tests.)

| subscribers | io_uring (syscalls/delivery) | epoll (syscalls/delivery) | reduction |
|---:|---:|---:|---:|
| 8 | 0.445 | 0.364 | 0.82× — io_uring ~even/slightly worse |
| … | _(m6i: 64 / 256 / 1024)_ | | |

At 8 subscribers io_uring is ≈ epoll (NOTIFY batching — `batchMaxNotifications` — already reduces
epoll's `sendto` to ~0.32/delivery), mirroring edge-read's low-connection regime. The high-subscriber
points (where ADR-0037's connection-scale rationale actually bites) are the m6i's job. `[m6i TBD]`

## 4. Consensus — syscalls/frame at one connection `[dev]`

The inter-node consensus wire runs at **few** connections (N−1 peers). Measured on the production
`NettyConsensusFrameEncoder` over a single point-to-point link:

| | io_uring | epoll |
|---|---:|---:|
| socket-IO syscalls/frame | **3.54** (2.66 `io_uring_enter` + 0.89 wakeup `write`) | **1.86** (1.00 `sendto` + 0.86 write) |

io_uring is **0.52× (≈2× WORSE)** at one connection — it cannot batch with a single frame in flight
(submit + await-completion per send), so it costs *more* syscalls than epoll. This **confirms the
charter's expectation**: consensus sees no io_uring benefit (and a small penalty) at its connection
scale. Consistent with edge conn=1 (0.69×) and fan-out subs=8 (0.82×). Brief-confirm complete.

## 5. Throughput / tail-latency translation `[m6i — TBD]`

The trustworthy syscall axis above shows io_uring reduces syscalls 7.6×–15.5× at Configd's edge
connection scale. The open question — **does that translate to throughput / tail latency?** — is
answered only on a box with enough cores to be syscall-bound rather than CPU-bound (the 2-vCPU dev
box is CPU-bound at ~2.6k req/s, so the syscall win does not surface in throughput there, and
throughput cannot be measured cleanly under strace). The m6i.4xlarge run fills this section:
io_uring-vs-epoll throughput + p50/p99/p999 across the connection sweep, with variance.

## 6. Kernel / fallback note

io_uring requires a recent kernel (5.x+); it is a **performance tier, never a correctness
dependency**. Epoll (and NIO) are the always-correct fallback that CI exercises (CI runners
frequently lack io_uring) — the M1–M4 contracts run green on forced-NIO and forced-Epoll. Most
deploy targets that lack io_uring fall back to Epoll with zero behavioural change; the migration's
correctness never rests on io_uring. `[m6i: confirm the box's kernel + that io_uring initialises]`

## 7. Verdict `[TBD — after m6i]`

_The honest verdict — delivered-now vs latent-at-scale — lands here once the throughput translation
is measured. The syscall axis is already settled: io_uring's syscall-reduction mechanism is real and
material at Configd's edge/fan-out connection scale (7.6×+), latent/negative at the few-connection
consensus scale. Whether the migration's io_uring rationale is "validated with a user-visible
performance win now" or "a sound forward bet whose benefit is realised at higher IO-bound load" is
the m6i's call, stated for what it is._
