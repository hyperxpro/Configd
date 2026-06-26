# ADR-0043 — Standardize all network transport on Netty 4.2 (supersedes ADR-0037 wholesale)

- **Status:** Accepted (Netty-migration, operator architectural decision). Staged by verification —
  each surface migrates + re-proves its S7 controls behind its own CI-green gate (charter §1/§5).
- **Date:** 2026-06-23
- **Session:** Netty-migration (transport → Netty)
- **Supersedes:** [ADR-0037](adr-0037-edge-transport-jdk-stack.md) (edge transport reuses the JDK
  stack, no Netty) — **wholesale**, on the grounds recorded below. Also retires the *intent* of
  [ADR-0010](adr-0010-netty-grpc-transport.md) ("Netty + gRPC + Spring", long-documented
  fiction) — note this ADR adopts **idiomatic Netty 4.2 only**: no gRPC, no Spring, no
  resurrection of ADR-0010's stack.
- **Evidence:** the built-and-raced head-to-head [docs/jdk-vs-netty/verdict.md](../jdk-vs-netty/verdict.md)
  (independently reproduced; [verification.md](../jdk-vs-netty/verification.md)); API research
  [docs/netty-migration/netty42-api.md](../netty-migration/netty42-api.md).
- **Decision log:** [docs/netty-migration/decision-log.md](../netty-migration/decision-log.md).
- **Migration status (2026-06-25): COMPLETE — all four surfaces migrated.** M1 edge-read + M2 admin
  (PR #2) and M3 fan-out (PR #3) are on `main`; **M4 consensus wire** is on branch `netty-migration-m4`
  (PR pending; CI 12/12 green; four-way verified — implementer + diff-review + independent re-run +
  adversarial red-team (SAFE TO MERGE) + fresh-context Verifier (APPROVE, 0 must-fix)). With M4, the
  inter-node Raft transport (`TcpRaftTransport`) is replaced in production by `NettyRaftTransport`, the
  last surface — so **ADR-0037 is now FULLY superseded** (not merely in principle: the
  JDK-socket/SSLSocket consensus transport it ratified no longer runs in production). The honest
  rationale is unchanged — **io_uring substrate + platform uniformity + the measured edge-read 8.7×
  win**, explicitly **accepting measured-neutral performance on the consensus/fan-out wire codecs**:
  M4's gc-proof confirms the production consensus encoder allocates **~0 B/op** (not the 160 microbench
  artifact) because the send is driven from the event loop (DR-N17), exactly as this ADR's consensus
  risk note required; the M3 no-spurious-election timing re-closes on the real Netty wire (the
  load-bearing proof). The JDK `TcpRaftTransport` is retained as the M4 contract's JDK baseline + the
  documented fast-revert. **Phase V (io_uring measurement) is now COMPLETE** (2026-06-26, m6i.4xlarge):
  the io_uring rationale is **measured as NOT validated** — latent at best, a ~2× throughput
  regression at high fan-out — see the honest-rationale point 1 below and
  [phase-v-io-uring.md](../netty-migration/phase-v-io-uring.md). The migration stands on its other two
  measured grounds (edge-read allocation 8.7× + uniformity); io_uring stays an auto-selected tier with
  Epoll the proven, faster fallback.

## Context

ADR-0037 chose the JDK-socket / `TlsManager` / `FrameCodec` stack for the edge data plane and
priced — but declined — Netty, on a **measured-workload connection-scale** rationale: per-node
fan-out is tens-to-low-hundreds of long-lived connections (tree fan-out, k=16/k=64), so "Netty's
advantage (10k+ ephemeral connections per process, syscall amortization) is not this workload,"
gated behind a `>1k-subscribers-per-node` precondition not currently met. That reasoning was sound
**on its own axis** and is not refuted here.

Two things changed the decision basis:

1. **The contested performance question was settled by building both stacks and racing them**
   (head-to-head, this branch — additive, testkit-only, no production transport touched). The
   honest result, after deep adversarial verification (every "Netty is worse" intermediate number
   turned out to be a harness artifact):
   - **Edge-read HTTP: Netty WINS decisively on allocation — 15,010 → 1,716 B/req server-side
     (8.7×, independently reproduced 8.8×)**, transport-attributable (`com.sun.net.httpserver` has
     no in-place lever to remove its per-request `HttpExchange`/header/stream garbage).
   - **Consensus + fan-out wire codecs: Netty TIES the JDK at ~0/floor** when built idiomatically
     (in-pipeline encode, sized buffers, writes on the event loop). The JDK reaches the same ~0 via
     a free in-place `encode(ByteBuffer)` into-variant, so the JDK was preferred *on cost only* (no
     dependency), **not** because Netty is slower. Done right, Netty does not lose on the wire.
   - **io_uring: documented, NOT benchmarked** (separate axis; kernel supports it here).

2. **The operator made an architectural decision to standardize the whole transport platform on
   Netty** — a forward-looking bet whose justification is explicitly **not** "Netty is faster
   everywhere" (the measurement above forbids that claim). See the honest rationale below.

## Decision

**Adopt Netty 4.2 as the single network-transport platform across all four surfaces** — edge-read
HTTP, admin API, fan-out streaming, and the inter-node consensus wire — superseding ADR-0037
wholesale. The migration is **staged by verification**, surface by surface, lowest-risk first
(M1 edge-read → M2 admin → M3 fan-out → M4 consensus), each behind a CI-green gate that re-proves
that surface's S7 controls by negative test and re-closes its S2–S4 / M3-timing behaviour.

### The honest rationale (recorded as such, per charter §8)

Netty is adopted for **three** reasons, of which only one is a measured per-surface performance win:

1. **io_uring (the platform unlock — the *bet*, now MEASURED in Phase V: NOT validated).** Netty 4.2
   makes io_uring a first-class transport (`io.netty.channel.uring`). io_uring's benefit is
   **syscall reduction** via batched submission/completion rings — an axis the allocation
   benchmarks could not see. This was the stated core justification, and Phase V measured it
   honestly (m6i.4xlarge, 16 vCPU, io_uring confirmed active; both transports apples-to-apples;
   independently reproduced — see [phase-v-io-uring.md](../netty-migration/phase-v-io-uring.md)).
   **The measured verdict is *latent at best, a regression at scale*:** the syscall reduction is real
   but (a) **smaller than expected on multi-core hardware** — io_uring batches per-event-loop, and
   Netty runs one loop per core, so at Configd's connection scale the per-loop density (hence
   batching) is low (edge-read ~1.1–1.5× fewer syscalls on 16 cores, not the dev box's 7.6×); (b) it
   **does not translate to throughput or tail latency** anywhere measured (edge-read tied
   io_uring-vs-epoll at every connection count); and (c) at **high fan-out (1024 subscriber streams)
   io_uring REGRESSES throughput ~2× and tail ~8×** vs Epoll, despite 5× fewer syscalls (identical
   delivered counts, ~2× wall-time, zero demotions). Consensus (few connections) is ~2× worse on
   syscalls. **So io_uring delivers no measured performance benefit at Configd's workload, and a
   throughput regression at growth scale.** It remains a runtime-auto-selected tier with Epoll the
   proven — and here *faster* — fallback; the migration does **not** rest on it (it rests on #2/#3).
   Honest scope: this is io_uring *as deployed* (Netty 4.2 defaults, no SQPOLL); whether tuning
   recovers a benefit is unexplored. **ACTIONED (2026-06-26):** the `NettyTransport.select()` auto
   default is flipped to **Epoll → NIO; io_uring is now opt-in** via
   `-Dconfigd.netty.transport=io_uring` — the auto path no longer selects the transport measured
   slower (epoll is the proven-faster fallback). io_uring stays a first-class, fail-loud opt-in for
   operators whose workload has the per-event-loop connection density to benefit.
2. **Platform uniformity (an engineering judgment).** One transport stack across all surfaces is
   simpler to reason about, staff, review, and extend (HTTP/2, finer backpressure, connection
   scaling past ADR-0037's threshold) than four bespoke JDK-socket implementations. This is a
   maintainability/optionality argument, not a benchmark.
3. **The measured edge-read win (proven).** 8.7× less server-side allocation on the highest-volume
   read surface, with no JDK in-place alternative — the one surface where the measurement convicts
   Netty outright.

### What is explicitly accepted as the price

- **Measured-neutral performance on the consensus and fan-out wire codecs.** The head-to-head
  proved Netty ties the JDK at ~0/floor there; the JDK had a free in-place fix. Standardizing on
  Netty on those surfaces buys **uniformity + the io_uring substrate**, at **no allocation gain**
  today (and a small `WriteTask` cost on consensus unless the send is driven from the event loop,
  which the migration does). This is a deliberate, eyes-open trade, **not** a performance claim.
- **A heavyweight new runtime dependency** with its own threading model, refcounted buffer
  lifecycle, and security-review surface — exactly what ADR-0037 hard-rule-5 guarded against. This
  ADR is the positive case hard-rule-5 requires; the cost is paid down by re-proving every S7
  control per surface (below) and by the io_uring/uniformity upside.

### Non-negotiable migration constraints (charter §3, enforced by the gate)

- **Security preserved, re-proven by negative test per surface.** Every S7 control that applied to
  a surface must hold on its new Netty pipeline AND be proven by the test that proves the attack
  fails. A silently-dropped control is the worst outcome of a transport migration.
- **Auto-select Epoll → NIO; io_uring opt-in, fail-loud, fallback CI-proven.** io_uring is a
  performance tier, never a correctness dependency; Epoll/NIO are the always-correct (and, per Phase
  V, *faster*) default the CI runners exercise (netty42-api.md §1/§3). *(Originally io_uring → Epoll
  → NIO; flipped to Epoll-default + io_uring-opt-in after Phase V measured io_uring slower — see the
  honest-rationale point 1 above.)*
- **Allocation proven by `-prof gc` per surface:** the edge-read 8.7× holds; the wire codecs stay
  at their ~0 floor (idiomatic in-pipeline encode mandatory — naive Netty usage that regresses the
  floor is a defect, not "Netty being slower").
- **No correctness/perf regression:** S2–S4, the S5/S7.5 perf gates, and M3 coalesced-heartbeat
  timing all re-close per relevant surface; the consensus surface (M4) gets four-way rigor.

## Consequences

- **Reverses ADR-0037's "no Netty / zero new external dependency" consequence.** `netty-transport`,
  `netty-buffer`, `netty-codec-http`, `netty-codec`, and the Epoll + io_uring native transports
  (classes + `linux-x86_64` native binaries) become production dependencies, pinned at
  `4.2.15.Final` (root pom `<netty.version>`), added per-module as each surface migrates (M1 wires
  `configd-edge-node`).
- **ADR-0037's connection-scale math is not invalidated** — it correctly says today's per-node
  connection count does not *force* Netty. This decision overrides it on *different* grounds
  (io_uring + uniformity + the edge-read win), and ADR-0037 itself left the door open ("if a future
  session demonstrates… swapping the socket loop for an NIO/Netty endpoint is localized… this ADR
  does not foreclose it, it prices it"). This ADR pays that price deliberately.
- **mTLS / S7 surface (preserved, re-proven where it exists).** The S7 controls are already
  framework-decoupled plain-Java services (`TlsManager`, `AuthInterceptor`, `AclService`,
  `AuditLog`, `ReplayGuard`, the strong-read/not-subscribed/cursor policies) wired directly into
  handlers — a Netty handler re-wires the *same objects*. **Surface-specific note:** the edge-read
  HTTP surface (M1) is **plaintext by design** (client-facing read API; `EdgeNodeMain` passes
  `TlsManager` to the fan-out `EdgeStreamClient`, not to `EdgeHttpServer`), so "edge mTLS" is a
  property of the **fan-out** surface (M3), not M1 — see decision-log DR-N1. M1 re-proves the
  controls it actually carries (strong-read fail-close, not-subscribed/cursor-behind refusal,
  staleness header, `/metrics` bearer gate, method validation, the request-size ceiling, slowloris
  resistance).
- **Phase V (io_uring validation) is EC2-gated and may be a separate session.** The M1–M4
  migrations do **not** depend on it — Epoll/NIO are the fallback either way; io_uring is the upside
  the platform unlocks, and its delivered-vs-latent verdict is recorded honestly when measured.
- **Wire compatibility is unchanged.** Netty re-implements the *transport*, not the wire formats:
  the consensus `FrameCodec` (ADR-0029 golden-fixture-guarded) and the `EdgeFrameCodec` byte
  layouts are preserved byte-for-byte (re-proven by their existing golden-fixture tests on the
  Netty pipeline). M3's single-pass codec rewrite is codec-internal and independent of Netty.

## Sign-off

- Operator: architectural decision (recorded in the migration charter).
- Per-surface verification: each of M1–M4 carries its own second-agent replay + CI-green gate; this
  ADR is ratified incrementally as each surface's gate goes green (the first being M1 edge-read).
