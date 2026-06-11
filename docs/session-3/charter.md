# Configd Production-Readiness Pipeline — Session 3 of 8: Edge Data Plane Construction

> Verbatim session charter (the controlling mission text for branch `session-3-data-plane`).
> Committed to the repo so every Session-3 artifact (contract-test map components, design
> notes, gate-3, register rows) can cite it by section. The original repo-wide mission
> prompt is `PROMPT.md` (root); this charter governs Session 3 specifically.

## 0. Context

Sessions 1–2 are complete on branches `session-1-ground-truth` and `session-2-correctness`
(gates 1 and 2 green in CI — verify this before any work; if CI shows red, fixing that is your
first task and a register finding).

State of the world:
- The control plane is hardened: ack-after-commit (RR-004 fixed, ClientAck spec), durable-prefix
  recovery (RR-003 fixed, DurablePrefix spec), no blocking connects on consensus threads
  (RR-002 fixed), adversarial deterministic simulator with 10k-seed sweeps, linearizability
  checking, 18 proven assertion twins, mutation-enforced test suite.
- The edge data plane still does not exist (RR-001, OPEN, narrowed). Session 2 delivered the
  commit-notification boundary: a bounded, cursor-based, log-replayable interface (ADR-0034).
  Committed writes are now observable at that boundary and nowhere beyond it.
- RR-095 (7 liveness stall seeds) is owned by Session 4 — you re-run those seeds against your
  integrated system and report deltas; you do not own the fix unless your code causes new ones.

**This session builds the edge data plane.** It is the largest construction effort in the
pipeline, and construction is precisely the mode that produced Session 1's verdict ("one real
system wearing the documentation of another"). The countermeasure is structural, not
motivational: see §1.

Authoritative inputs, read in this order before any work:
1. `docs/session-2/handoff-to-session-3.md` — interface spec, guarantees to satisfy, open
   findings, infrastructure to reuse.
2. `docs/decisions/adr-0034*.md` — the commit-notification boundary you consume. You may extend
   it via a new ADR; you may not bypass it.
3. `docs/consistency-contract.md` — the edge-facing guarantees (staleness bound, monotonic
   reads, read-your-writes scope, version semantics) are your acceptance criteria, verbatim.
4. `docs/architecture.md` §7 (fan-out), §8 (edge caching), §11 (backpressure/overload), §12
   (WAN modeling) — the design you are implementing. Where reality forces deviation, ADR first.
5. `docs/readiness-register.md` — RR-001 and every data-plane-area finding.

## 1. Prime Directive: VERIFICATION MACHINERY FIRST, THEN COMPONENTS — ONE AT A TIME

The failure mode to prevent: five components built in parallel, integrated at the end, "tested"
by docs. The rule set:

1. **Phase V before any component:** extend the deterministic simulator with edge actors and
   fan-out channels, build the synthetic propagation probe, and write the contract property-test
   skeletons (failing, because nothing exists yet). These failing tests are the session's
   backlog made executable.
2. **Strict component sequencing:** a component is DONE only when it runs inside the simulator
   under adversarial schedules AND its unit/property tests pass AND `review-architect` has
   signed its design note. The next component may not start its implementation until the
   previous one is DONE. (Design notes and interfaces may be drafted ahead; code may not.)
3. **No documentation ahead of code.** Design notes are written per component as it lands,
   describing what IS, with test names as citations. Aspirational docs are a register finding.
4. **Every contract guarantee maps to a named, passing test by session end** — or the contract
   is renegotiated by ADR. Silent under-delivery is the one unforgivable outcome.

## 2. Agent Team

| Agent | Responsibility |
|---|---|
| `data-plane-lead-engineer` | Fan-out service, catch-up/replay, slow-consumer policy |
| `edge-node-engineer` | Edge process, versioned snapshots, delta application, read path |
| `simulation-engineer` | Edge actors in the simulator, propagation probe, fault schedules |
| `contract-qa-engineer` | Owns the contract→test map; writes property/conformance tests first |
| `review-architect` | Component sign-offs, ADR arbitration, sequencing enforcement |
| `sre-engineer` | Backpressure/overload policy reality, operational hooks (not dashboards — Session 6 owns those, but emit the metrics now) |

**Review rule:** per-component sign-off requires `review-architect` + `contract-qa-engineer`.
The QA engineer's question is always the same: "which contract clause does this satisfy, and
which test proves it?"

## 3. Phase V — Verification Machinery (build first, nothing else until done)

### V1. Simulator edge actors
- Edges as first-class deterministic actors: subscribe, receive, apply, serve reads, crash,
  restart, lag, partition. Fan-out channels with the same fault repertoire as consensus
  channels (drop, reorder, duplicate, delay).
- New invariants checked every step on every seed, at every edge:
  - **Version monotonicity per edge:** no observer ever sees a version decrease.
  - **No stale overwrite:** an applied version V is never replaced by V' < V.
  - **Eventual delivery:** every committed write reaches every live, connected edge within the
    simulated staleness bound; violations are liveness findings with seed numbers.
  - **Snapshot–delta equivalence:** an edge bootstrapped from snapshot+deltas converges to a
    state byte-identical to an edge that streamed everything.
- Reuse Session 2's seed/replay/shrink infrastructure. Do not fork it.

### V2. Synthetic propagation probe
- A workload driver that timestamps each committed write at the boundary and at each edge's
  visibility moment, producing HdrHistogram staleness distributions per edge and globally.
- Runs in two modes: simulator (logical time — correctness of the bound's *mechanism*) and live
  multi-node (wall time — Session 5 will use this for the real p99 < 500 ms target; here it
  must merely work and produce honest numbers on throttled hardware, recorded with caveats).

### V3. Contract→test map
- `docs/session-3/contract-test-map.md`: one row per edge-facing contract clause →
  named test(s) → status. Created on day one with every row FAILING or UNIMPLEMENTED; the
  session ends when every row is PASSING or ADR-renegotiated. This file is the session's
  progress bar and is included in gate-3.

## 4. Components (strict order; each: code + tests + sim integration + design note + sign-off)

### C1. Fan-out distribution service
- Consumes the ADR-0034 boundary. Push-based, cursor-acknowledged streams per subscriber, with
  the architecture §7 subscription model (per-key / prefix / full-store — implement what §7
  specifies; descope by ADR if needed).
- Coalescing: multiple updates to the same key in flight may collapse to the latest, but version
  cursors must never skip in a way that breaks gap detection (define and test the exact rule).
- Backpressure per §11: bounded per-subscriber queues, explicit overflow→catch-up demotion (a
  slow subscriber is switched from streaming to replay mode, never an unbounded queue, never a
  silent drop without cursor evidence). Every policy threshold is a named config with a metric.

### C2. Edge node process
- A real, separately runnable process (this is what RR-001's "no edge process" indicts).
  Netty transport to the fan-out service, mTLS consistent with the control plane's.
  [Transport library deviation recorded in ADR-0037: same mTLS stack, JDK sockets, no Netty.]
- Versioned immutable snapshots + delta application onto the existing lock-free read path
  (volatile snapshot pointer; the Session 1-verified read-path constraints are inherited law:
  no locks, no CAS loops, no steady-state allocation — verify with a brief JMH gc-profile run,
  full perf is Session 5).
- Every read returns its version cursor. Per-session monotonic reads enforced exactly as the
  contract scopes them, including across reconnect to a *different* fan-out endpoint (the
  contract's edge-failover clause — test it explicitly).

### C3. Catch-up, replay, and gap detection
- Cursor-gap detection on the edge; recovery via replay from the boundary (log-backed) or
  snapshot+delta re-bootstrap when the gap exceeds replay horizon. Both paths tested, including
  the horizon-boundary case, under concurrent writes.
- Poison-pill / bad-entry handling and negative caching per architecture §8 — implement or
  explicitly descope by ADR (do not leave it ambiguous).

### C4. Slow-consumer policy
- Implement §7's documented thresholds: demotion to catch-up, quarantine, disconnect,
  re-bootstrap. Each transition: a test, a metric, a structured log event. The policy must be
  exercised in the simulator (a deliberately-lagging edge actor walks the full state machine).

### C5. New-edge bootstrap
- Zero-state edge joins under sustained concurrent writes: snapshot transfer + cutover to live
  stream with no gap and no duplicate-application divergence (idempotent apply or exact cutover
  cursor — choose, justify, test). The V1 snapshot–delta equivalence invariant is the judge.

### C6. End-to-end integration
- Extend the multi-node environment: 3 control-plane nodes + ≥ 3 edge processes via Docker
  Compose. Scripted scenario: sustained writes → verify propagation; kill the leader mid-stream
  → verify no edge sees a version decrease and staleness recovers; partition one edge → verify
  demotion, catch-up, and convergence; bootstrap a fresh edge mid-load.
- Re-run the RR-095 stall seeds against the integrated simulator config; report deltas in the
  register (owned by Session 4 unless your code introduced new stalls — those you own).

## 5. Gate-3

`gates/gate-3.sh`, CI-wired, cumulative (gates 1 and 2 must stay green):
- Contract→test map: every row PASSING (ADR-renegotiated rows reference their ADR)
- Simulator with edge actors: committed gate seed set (≥ 500 seeds) with the V1 invariant set,
  zero safety violations
- Propagation probe runs in both modes and emits histograms (mechanism check, not perf targets)
- E2E Compose scenario passes end to end, throttle-robust (reuse Session 2's
  retry-across-churn patterns; no sleeps as synchronization)
- Slow-consumer state machine walk passes
- Brief JMH gc-profile check: zero steady-state allocation on the edge read path
- Mutation thresholds: new modules ≥ 65% from day one (cheaper to enforce during construction
  than to retrofit — Session 1 proved this); Session 2's thresholds must not regress

## 6. Hard Rules

1. Phase V completes before any component implementation begins. Component order is C1→C6,
   gated by sign-offs. Parallel *design* is fine; parallel *implementation* is not.
2. No bypassing ADR-0034. Extensions to the boundary go through a new ADR.
3. Inherited hot-path law applies to the edge read path: no locks, no allocation, no reflection,
   no per-request INFO logging. Violations are P1 findings even in your own new code.
4. No performance *tuning* (Session 5's job) — but no performance-disqualifying *designs*
   either: unbounded queues, per-update full-snapshot shipping, O(subscribers) work under a
   global lock. `review-architect` screens for these at design-note time.
5. No new external runtime dependencies without an ADR (the original "zero external
   coordination" target still stands).
6. Register discipline unchanged: new P0/P1s need second-agent reproduction; RR-001 is closed
   only when the E2E scenario and the contract→test map jointly prove the advertised data plane
   exists at runtime — `review-architect` writes that closure justification personally.
7. Sessions 1–2 artifacts immutable; new docs under `docs/session-3/`.
8. Emit production metrics (staleness, cursor lag, queue depths, policy transitions) as you
   build — Session 6 wires dashboards/alerts, but it cannot wire series that don't exist; that
   is exactly how the Session 1 "blind green dashboard" finding (S6 rows) happened.

## 7. Definition of Done

- [ ] Phase V machinery delivered first (commit history proves the ordering)
- [ ] C1–C6 each DONE per §1's definition, with per-component design notes citing test names
- [ ] Contract→test map: every edge-facing clause PASSING or ADR-renegotiated
- [ ] RR-001 RESOLVED with `review-architect`'s closure justification in the register
- [ ] Simulator edge invariants: gate seed set clean; a 10k-seed integrated sweep run at least
      once, zero safety violations; liveness findings registered with seeds
- [ ] RR-095 seeds re-run on the integrated system; deltas reported in the register
- [ ] E2E Compose scenario green; propagation probe histograms captured in both modes
- [ ] Metrics emitted for every policy threshold and propagation stage (named list in handoff)
- [ ] gate-1, gate-2, gate-3 all green in CI
- [ ] Claim–evidence matrix: data-plane FICTION rows converted with commands named
- [ ] `docs/session-3/handoff-to-session-4.md`: the fault surface of the new data plane (what
      Session 4's chaos matrix must now cover that the original §4 matrix didn't), known
      weak points, environment notes, and the integrated-simulator configs to reuse

## 8. Working Order

1. Verify CI gate status on the pushed branches. Read the five authoritative inputs.
2. Phase V (all agents converge on it — the simulator and the failing contract tests are the
   spine of everything after).
3. C1→C6 in strict sequence under the §1 regime. `sre-engineer` reviews each component's
   backpressure/overload behavior and metric emission as it lands, not at the end.
4. Integrated 10k sweep + RR-095 re-run + E2E scenario as the closing verification block.
5. Gate-3 assembled, CI-wired, run end-to-end and captured. Handoff written from results.
