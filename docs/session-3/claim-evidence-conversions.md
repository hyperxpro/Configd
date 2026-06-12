# Claim–Evidence Matrix — Session-3 Conversion Addendum

> The Session-1 matrix (`docs/audit-session-1/claim-evidence-matrix.md`) is an immutable
> audit artifact. This addendum records the data-plane rows Session 3 was charged to
> convert (charter §7 DoD: "data-plane FICTION rows converted with commands named"),
> following the Session-2 addendum's format and verbs. Statuses here SUPERSEDE the
> Session-1 statuses for the listed CM-IDs as of branch `session-3-data-plane`.
> Conversion verbs as in the S2 addendum: **→VERIFIED**, **→RESOLVED-BY-FIX**,
> **→RESOLVED-BY-AMEND**, **→DESCOPED**; plus **→PARTIAL(owner)** where the claim's
> mechanism converted but a named numeric/scale residual is owned by a later session.

## The headline: the data plane exists (RR-001 RESOLVED)

| CM | S1 status | New status | Evidence |
|---|---|---|---|
| CM-006 | FICTION ("no live edge pipeline exists") | **→PARTIAL(S5)** — pipeline RESOLVED-BY-FIX; SLO numbers owed | The live pipeline exists end-to-end: `bash gates/e2e-compose-scenario.sh` (19/19, captured `docs/session-3/captures/e2e-compose-scenario-run.txt`, independently re-run by review-architect); staleness measured per ADR-0039 (frontier) and propagated wall-clock by the V2 probe in BOTH modes (`java -cp configd-testkit/target/benchmarks.jar io.configd.probe.LivePropagationProbeMain --mode boundary|edge`). The NUMERIC SLO targets (p99 < 500 ms etc.) are Session 5's by charter §3 V2 (CT-02's sanctioned deferral). |
| CM-022 | FICTION (no live broadcast path; Plumtree/HyParView ticked but unwired) | **→RESOLVED-BY-FIX** (delivery) with topology deviation recorded | Committed config now reaches every edge live: C1 `FanOutServer` push-based cursor-acknowledged mTLS streams over the ADR-0034 boundary (`FullChainDeliveryTest`, `FanOutServerIntegrationTest`, E2E phase 1). Deviation: delivery is direct per-session fan-out (ADR-0037/0038), NOT epidemic broadcast trees — `HyParViewOverlay` remains shelfware, RR-088 (narrowed), S7 disposition. Architecture §7's backpressure/catch-up/slow-consumer text amended to as-built (S3 doc pass). |
| CM-042 | FICTION (no edge connection protocol at all) | **→RESOLVED-BY-FIX** | `EdgeFrame.Subscribe` carries `resumeCursor` + `failoverResumeCursor` in the wire handshake (EDGE_WIRE_VERSION 0x01, golden-pinned); proven across a real failover: `EdgeFailoverTest` (kill subscribed endpoint mid-stream → reconnect to next endpoint carrying the cursor → cursor-monotonic resume). |

## Staleness family

| CM | S1 status | New status | Evidence |
|---|---|---|---|
| CM-036 | as recorded (no end-to-end staleness measurement) | **→PARTIAL(S5)** — mechanism RESOLVED-BY-FIX; bounds owed | The measurement is real and contract-true: ADR-0039 frontier (`StalenessTrackerTest`, `EdgeStalenessFrontierSimTest` — idle-but-heartbeating pinned CURRENT, partitioned edge walks the ladder); distribution machinery live in both probe modes. The p99/p999/p9999 BOUNDS are Session 5's (CT-02). |
| CM-038 | as recorded (violation-handling chain unproven) | **→RESOLVED-BY-FIX** | All four advertised reactions exist at the real serving surface: `X-Configd-Stale` header (CT-03), `configd_edge_staleness_violation_total` (CT-04), `/health/ready` 503 at DEGRADED+ (CT-05, the LB-unhealthy signal), DISCONNECTED→re-bootstrap (CT-06, `EdgeReBootstrapOnDisconnectTest` sim+process; E2E phase 3 walks the whole chain live). |
| CM-039 | as recorded (INV-S1/S2 asserted transitions, not distributions) | **→PARTIAL(S5)** | INV-S1 enforced through the monitor seam at the real read path; INV-S2's distribution machinery exists (probe, both modes, `EdgeStalenessDistributionSimTest`); the 0.01/0.0001 ratios are Session 5's targets (CT-02). |
| CM-049 | CONTRADICTED (test measured its own sync loop) | **→RESOLVED-BY-FIX** (transitions) + disclaimed legacy leg | `StalenessUpperBoundTest` rebuilt at C2: true-stall transitions + the ADR-0039 regression test (idle-but-heartbeating). The legacy distribution leg (`edgeStalenessStaysWithinBounds…`) is DISCLAIMED in the CT-02 row (it still measures its own loop; not load-bearing for any flip; rewrite at V2/S5 — c2-contract-qa-audit). |

## Monotonic-read family

| CM | S1 status | New status | Evidence |
|---|---|---|---|
| CM-040 | as recorded (library mechanism only, no serving surface) | **→RESOLVED-BY-FIX** | INV-M1/M2 enforced at the REAL serving surface: cursor-bound HTTP reads (`EdgeHttpServerTest`), across process failover (`EdgeFailoverTest`, CT-11/12), across restart (`MonotonicReadAcrossEdgeRestartTest` ×2 — sim + process, CT-13; the RR-100 wedge-finder), across 4 edges with one cursor (CT-09, E2E phases 1-4 with the per-edge monotonic watch — no edge ever saw a decrease across a leader kill). |
| CM-041 | as recorded (block-100ms-then-serve-stale not implemented) | **→RESOLVED-BY-AMEND** | The contract was amended, not the code bent to fiction: §3 Edge Failover steps 3-4 now specify immediate consistent refusal (404 + `X-Configd-Refused: cursor-behind`), uniform across steady state/catch-up/failover (C2-4 ruling, contract pass; `EdgeFailoverTest` pins it). The blocking-catch-up variant is recorded as a possible future enhancement, not current behavior. |
| CM-053 | as recorded (assert_monotonic_read wired in lib only) | **→RESOLVED-BY-FIX** with a recorded side-effect | The seam is live at the edge serving surface: refused reads route through the monitor-wired store so `invariant.violation.monotonic_read` fires (screen condition C2-1). Side-effect registered honestly: at the serving surface the series now conflates benign catch-up refusals with store regressions — RR-099 (P3, S6 alert design). |

## Conversion provenance

Component sign-off chain: `docs/session-3/reviews/c{1..6}-signoff-review.md` +
`c{1..6}-contract-qa-audit.md`; contract map end state
`docs/session-3/contract-test-map.md` (34 PASSING / 3 owned PARTIAL / 3 ADR / 1 N-A);
RR-001 closure justification: register row RR-001 (review-architect, 2026-06-12).
