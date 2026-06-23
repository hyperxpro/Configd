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

---

# Netty-migration session — transport → Netty (the platform decision)

> The entries above (DR-1..5) are the **measure-first** session, which concluded with a *surgical*
> recommendation ("Netty earns one surface; two get cheaper no-Netty fixes; one is acquitted").
> This session operates under a **changed steer** (DR-M0): the operator made an architectural
> decision to standardize the whole transport platform on Netty. The measurement still stands and
> is cited honestly — the decision is a forward-looking bet, **not** a claim Netty is faster
> everywhere. These entries are append-only; the entries above are unchanged.

## DR-M0 — Steer change: standardize all transport on Netty (supersedes the surgical recommendation)
**Date:** 2026-06-23 · **Type:** scope · **Status:** decided (operator architectural decision)

The prior session's evidence-gated recommendation was *surgical* (Netty only for edge-read; no-Netty
in-place fixes for consensus/fan-out). The operator has instead decided to **standardize ALL four
surfaces on Netty** — recorded in [ADR-0043](../decisions/adr-0043-netty-transport-platform.md),
which supersedes ADR-0037 wholesale. The honest rationale (ADR-0043): **io_uring** (syscall
reduction — the unmeasured axis the platform unlocks, validated in Phase V) + **platform
uniformity** + the **measured edge-read 8.7× win**, explicitly **accepting measured-neutral
performance on the consensus/fan-out wire codecs** (the head-to-head proved Netty ties the JDK at ~0
there). This is NOT "Netty is faster everywhere" — that claim is forbidden by the measurement. The
head-to-head verdict (verdict.md) remains the authoritative *measurement*; ADR-0043 is the
authoritative *decision*, made on partly non-performance grounds. Staged by verification, lowest-risk
first (M1 edge-read → M2 admin → M3 fan-out → M4 consensus), each behind a CI-green per-surface gate.

## DR-N1 — Edge-read (M1) is PLAINTEXT by design; "edge mTLS" reconciled to the fan-out surface (M3)
**Date:** 2026-06-23 · **Type:** technical/scope · **Status:** decided · **Veto window:** open

The charter §M1 and DoD say "re-prove edge mTLS" for M1. **Established against the code:** the edge
**read** HTTP surface (`EdgeHttpServer`) is built with the plaintext JDK `HttpServer.create(...)`,
NOT `HttpsServer` — and `EdgeNodeMain` passes its `TlsManager` to the **fan-out** client
(`EdgeStreamClient`, surface 3), *not* to `EdgeHttpServer`. So the edge read API is **plaintext by
design** (it is the client-facing read surface; mTLS lives on the inter-service fan-out + consensus
surfaces). Reconciliation (like DR-1's charter-vs-reality correction): **M1 preserves and re-proves
the controls surface 2 actually carries** — strong-read fail-close (`X-Fail-Closed`), not-subscribed
+ cursor-behind refusal (`X-Configd-Refused`), staleness header (`X-Configd-Stale`), the `/metrics`
Bearer gate (F-S7-TLS-2), method validation (405), the request-size ceiling, and slowloris
resistance — and **"edge mTLS" is correctly a property of the fan-out surface, re-proven in M3.**
Adding mTLS to a currently-plaintext public read API would be a *security/behaviour change*, not a
transport migration, and is out of scope here (it would also break existing plaintext clients);
flagged for the operator should they want it as a separate decision.

## DR-N2 — M1 design: extract transport-agnostic read logic; equivalence by construction
**Date:** 2026-06-23 · **Type:** methodology · **Status:** decided · **Veto window:** open

The head-to-head prototype `NettyEdgeReadServer` (testkit) proved *response-equivalent on the
hit/not-subscribed/cursor-behind paths it was tested on*, but it **diverges from the full production
contract**: it lacks the `/health/live`, `/health/ready`, and `/metrics` endpoints; lacks method
validation (405); lacks the `/metrics` Bearer gate; and sets `X-Configd-Stale` **only on hits**
whereas `EdgeHttpServer` sets it on **all** read paths (`EdgeHttpServerTest.staleHeaderSetOnAllReads…`).
Re-implementing the handler logic in the Netty handler risks exactly such silent divergence.
**Decision:** extract `EdgeHttpServer`'s decision logic into a transport-agnostic core (request →
response descriptor: status + headers + body bytes + the metrics/INV-M1 side-effects), have **both**
the JDK adapter and the new production Netty adapter delegate to it, and prove the refactored JDK
server is behaviour-identical (`EdgeHttpServerTest` stays green) **before** building the Netty
adapter on the same logic. M1.4 then re-runs the full negative-test matrix against the Netty adapter
(equivalence by construction, not by hopeful re-implementation), plus new hardening tests
(size-ceiling, slowloris, leak-detector PARANOID).

## DR-N3 — Session scope: foundation + M1 to a verified green seam; stop clean otherwise
**Date:** 2026-06-23 · **Type:** scope/sequencing · **Status:** decided (conservative default)

Per the Prime Directive (one decision, staged by verification; never a surface half-migrated), this
session lays the foundation (Phase R API+selector doc; ADR-0043; Netty production deps incl. io_uring
coordinates) and drives **M1 edge-read** as far as a clean, committed, GREEN, second-agent-verified
seam allows. The atomic production swap (`EdgeNodeMain` → Netty server) happens **only** after M1.1–
M1.6 are all green; until that flip, production stays on the JDK `EdgeHttpServer` (the new Netty
server is built and fully verified alongside it — never a half-migrated production path). If the full
S7 re-proof + gc-proof cannot all land clean this session, stop at the foundation seam **without**
the swap. M2–M4 are explicitly out of scope for this session (over-claiming four-surface migration is
the failure mode this project exists to prevent).

## DR-N4 — Routing tightened to exact-match (intentional); "byte-identical" claim qualified
**Date:** 2026-06-23 · **Type:** technical · **Status:** decided (second-agent finding F1) · **Veto window:** open

The original JDK `EdgeHttpServer` registered four `HttpServer.createContext` contexts, which match by
**longest path PREFIX** — so suffix variants of the fixed endpoints (`/health/livez`, `/health/live/x`,
`/metricsXYZ`, `/metrics/foo`) were served by their handler, and an unmatched path returned the JDK's
built-in **empty** 404. Both new adapters route through `EdgeReadHandler`, which matches the fixed
endpoints by **exact** `path.equals(...)` (and `/v1/config/` by prefix), so those suffix variants now
**404**, and the unmatched-path body is `"Not Found"`. The second-agent replay (F1) flagged that this
contradicts the unqualified "byte-identical by construction" claim. **Decision: keep the exact-match
tightening** — it is safer (e.g. `/metricsXYZ` must not reach the Prometheus exposition; the JDK
prefix-match was a framework artifact, not an intended contract) and consistent across both
transports — and (a) pin it with a test (`AbstractEdgeReadServerContract.routingIsExactMatchForFixedEndpoints`,
run on JDK + Netty + NIO) and (b) qualify the claim everywhere to **"byte-identical on the canonical
request paths; routing is exact-match (this DR)"**. This converts a silent behaviour change into a
documented, tested decision — no dropped control (the agent confirmed the dropped-control hunt is
empty), just a deliberate, recorded tightening.
