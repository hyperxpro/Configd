# Netty-migration — Decision Log

Per charter §2 (autonomy): technical/methodology decisions self-resolved and logged here for
retroactive veto; scope/sequencing decisions default conservative and logged.

---

## DR-1 — The charter's premise is partly counterfactual; this is a Netty *introduction*, not a Spring teardown
**Date:** 2026-06-22 · **Type:** scope · **Status:** decided (operator-confirmed)

`grep io.netty` / `grep org.springframework` across all poms + `src/main` → **zero hits**.
[ADR-0010](../decisions/adr-0010-netty-grpc-transport.md) ("Netty+gRPC+Spring") is Superseded
documented fiction; [ADR-0037](../decisions/adr-0037-edge-transport-jdk-stack.md) ratified the
JDK-socket stack. So there is no Spring request path to dismantle, and the S7 controls the
charter feared losing are already framework-decoupled plain-Java services (see
[inventory.md](inventory.md) §2). The migration's security risk is therefore re-wiring the
*same* control objects onto a new handler, not recovering lost framework behaviour.

## DR-2 — Standing ADR-0037 conflict surfaced to the operator; steer = measure-first, evidence-gated
**Date:** 2026-06-22 · **Type:** scope/sequencing · **Status:** decided (operator steer)

ADR-0037 deliberately chose the JDK stack on a *connection-scale* rationale and prices Netty
behind a `>1k-subs/node` precondition not currently met. The charter's justification is a
different, unweighed axis — **allocation/GC** — and it was **unmeasured**. Rather than
introduce a heavyweight dependency against a ratified ADR on an unproven premise, the operator
confirmed: **build the transport-shell `-prof gc` baseline across all four surfaces first;
migrate a surface to Netty only where the numbers convict it AND it matters at the real
workload; leave negligible-shell surfaces on the JDK stack (no migration for consistency);
supersede ADR-0037 via a new ADR only for surfaces actually migrated.** Report the baseline
before touching any transport.

## DR-3 — Baseline measurement method
**Date:** 2026-06-22 · **Type:** methodology · **Status:** decided · **Veto window:** open

- **Instrument:** JMH `-prof gc`, metric `gc.alloc.rate.norm` (B/op) — the same instrument that
  produced the existing S3/S7.5 allocation evidence, so results are comparable. Allocation/op
  is largely CPU-count-independent, so the 2-vCPU box yields trustworthy B/op (latency is
  explicitly *not* the baseline's purpose and is not claimed).
- **Surfaces 3 & 4 (codec):** measure the app-controlled per-message allocation the transports
  actually call (`FrameCodec.encode`/`decode`, `EdgeFrameCodec.encode`/`decode`) — the term a
  pooled `ByteBuf` would replace. Consensus also measures the **existing-but-unused**
  `encode(ByteBuffer)` into-variant as the achievable floor (shows how much is removable
  *without* Netty). `SSLSocket` I/O allocation is a separate component, measured end-to-end
  only for a convicted surface.
- **Surfaces 1 & 2 (HTTP):** end-to-end loopback with the real servers. Because `-prof gc` is
  JVM-wide (client+server), each real endpoint is paired with a trivial `/health/live`
  **control**; the delta isolates the read path, the control is the JDK-shell+client floor.
- Raw captures: `docs/netty-migration/baseline/*.txt` (date + git SHA header).

## DR-4 — `EdgeHttpAllocBenchmark` placed in `io.configd.edge.node`, no production change
**Date:** 2026-06-22 · **Type:** technical · **Status:** decided · **Veto window:** open

`EdgeNodeMetrics` is package-private and is a required `EdgeHttpServer`/`EdgeClientCore`
collaborator. The edge benchmark is therefore declared in `io.configd.edge.node` (within
`configd-testkit`), exactly as `EdgeHttpServerTest` is — JMH discovers benchmarks by
annotation, so the package is irrelevant to the gate. This avoids widening any production
visibility merely for a benchmark. The other three benchmarks remain in `io.configd.bench`.

## DR-5 — Second-agent replay of the baseline; three corrections; verdicts upheld
**Date:** 2026-06-22 · **Type:** methodology/verification · **Status:** decided

A fresh `java-distinguished-engineer` agent adversarially audited all four benchmarks against
the production paths, every headline B/op against object/array layout, and the four migration
verdicts (charter §6 "no fix without … second-agent replay"). Outcome: **all four verdicts
upheld** (none flipped), all spot-checked numbers mechanistically exact, but **three
faithfulness corrections** — two of which corrupt headline *numbers* and were fixed before the
numbers anchor scope:

1. **(decisive) Fan-out NOTIFY measured an UNSIGNED legacy delta; production signs every
   delta.** `ConfigdServer` initializes Ed25519 signing as mandatory, so steady-state fan-out
   carries a 64-byte signature + non-zero epoch + 8-byte nonce per notification (extra wire
   bytes + per-notification `signature`/`nonce` clones). `EdgeWireAllocBenchmark` fixed to the
   signed shape and **re-measured** (REV2). The prior unsigned numbers understated a
   *conviction* surface.
2. **Consensus send understated ~2×.** `TcpRaftTransport.encodeWire` allocates a *second*
   `byte[4+frame]` to prepend the 4-byte sender id; the codec-only leg omitted it. Added a
   faithful `encodeSendWire` leg (REV2). This *reinforces* the acquittal (more send
   allocation, still removable in place) but the in-place fix must also fold the sender id into
   the reused buffer — slightly larger than "~2 lines."
3. **Edge-read conviction rests on a client+server upper bound.** `-prof gc` is JVM-wide, the
   JDK `HttpClient` is plausibly the larger half of the ~36 KB floor, so the *server-side*
   shell-vs-read ratio is bounded, not measured. The edge-read Netty conviction is therefore
   **explicitly provisional**, gated on two numbers before any code: (a) server-side
   allocation split (async-profiler `alloc` / out-of-JVM client) and (b) production edge read
   QPS. The HTTP benchmarks themselves were judged faithful (real 200 path, `/health/live`
   control sound) and were not re-run.

Confirmed-real (not artifacts): `encodeInto ≈ 0` is genuine escape-analysis scalar
replacement (writes proven live by the ~1 µs runtime), so the consensus in-place-to-zero
claim stands. Audit agent id `adc6597036eb58d6d`.
