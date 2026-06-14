# Session 6 — Decision Log

Per charter §2 (autonomy directive): every technical/correctness/methodology judgment call is
self-resolved (fresh `opus` sub-agent where there is genuine ambiguity) and logged here for
retroactive veto; every scope/sequencing call takes the conservative default and is logged with
rationale. Each entry: ID, type (TECH/SCOPE), question, resolution, evidence, who/how.

---

## D-1 (TECH) — RR-110: §11 backpressure ladder, relabel-vs-implement, per clause

**Question.** `architecture.md §11` + `performance.md §4` document a 4-clause overload ladder
(write queue→429+Retry-After+hysteresis; apply-lag→503; ReadIndex-queue→429; an enforced
load-shed priority order). The as-built code implements only a bounded proposal queue
(`RaftNode:372` `uncommitted >= maxPendingProposals=1024` → `WriteResult.Overloaded` →
`HttpApiServer:411` 429, **no** `Retry-After`) plus the fan-out 80/100 path (which matches).
Resolve on the merits: is the *doc* over-specified (→ relabel) or is the *documented* behavior
the correct contract the code wrongly omits (→ implement, red/green)?

**Method.** Fresh `opus` sub-agent (`rr110-arbiter`) instructed to argue BOTH sides per clause and
decide on engineering merits, verifying every as-built claim by reading the code (not trusting the
register). Full argument captured in the agent transcript; verdict ratified by `operability-lead`
below with one addition.

**Resolution (ratified).**
- **Clause 1 (write queue → 429):** **IMPLEMENT** the `Retry-After: 1` response header only — it is
  a genuine, cheap, hot-path-free client contract (set on the already-shed slow path at
  `HttpApiServer:411`), and it must be backed by an **emitted + tested** overload-reject counter
  (new `configd.write.rejected.overloaded` → `configd_write_rejected_overloaded_total`), which also
  gives the "sustained 429 rate" alert (S5 handoff §1) a real series. **RELABEL** the doc threshold
  `>1000`→`1024` and **drop** the fictional `<500` two-level hysteresis: the single hard bound is a
  legitimate, simpler, level-triggered design; at the boundary it sheds correctly (accept-or-429),
  there is no costly state to flap between, and S4 EXP-010 already corroborates 1024 as the real
  plateau. Inventing a 500-entry low-water band would be red/green churn on the consensus hot path
  (Hard Rule 8) for zero safety gain.
- **Clause 2 (apply-lag → 503):** **RELABEL** (remove the 503 shed from the contract). On this
  single-writer-applies-inline design, an apply stall back-pressures commit, so `uncommitted`
  climbs to the 1024 bound and the **existing 429 already fires** — the apply-lag-503 protects a
  failure mode largely subsumed by clause 1, while a new reject branch + a cross-thread
  `commitIndex−lastApplied` read on the tick loop's hottest object is squarely Hard-Rule-8 risk.
  **Addition by lead (D-2):** separately wire the *observability* gauge `raft_pending_apply_entries`
  to a real (thread-safe) supplier so the existing `ConfigdRaftPipelineSaturation` **warn** alert
  becomes honest — observability only, **no shed action**.
- **Clause 3 (ReadIndex-queue → 429):** **RELABEL.** Reads are served lock-free from the edge HAMT
  and never shed; control-plane linearizable reads are rare by design and already fail *closed*
  (503 on unconfirmed leadership, `HttpApiServer:282,297`) — the missing piece is a load-shed on a
  path that by design carries no load. Relabel to the as-built reality.
- **Clause 4 (load-shed priority order):** **RELABEL** as *emergent/observed* ordering. There is no
  priority scheduler; producer-priority / region-distance / read-class are not inputs to any shed
  decision. The documented order is the emergent behavior of independent mechanisms (edge reads
  never shed; writes 429 at the bound; non-leader/strong reads fail-closed 503). Building a real
  cross-class scheduler on the write hot path to encode an order the system already approximates is
  Hard-Rule-8 territory for no gain.

**Net.** 3 RELABEL + 1 narrow IMPLEMENT. Edit BOTH `architecture.md §11` and `performance.md §4` to
the as-built reality; ship the `Retry-After: 1` header backed by a tested overload-reject counter;
wire the apply-pending gauge as observability. Every §11 signal an alert fires on (the 429 / the
apply-pending gauge) is then a correct, emitted, tested series — satisfying charter §1/§10.4.

**Evidence.** `RaftNode.java:370-373`, `RaftConfig.java:30,183`, `HttpApiServer.java:410-411`
(no Retry-After), `ConfigdServer.java:332` (gauge `()->0L`), `ReadIndexState.pendingCount()` never
shed, `FanOutConfig.java:86` (matches). Arbiter agent `rr110-arbiter`.

---

## D-2 (TECH) — Metric wire-up semantics: what each SLO series actually measures

**Question.** The SLO series (`configd.write.commit.seconds`, `.apply.seconds`,
`.edge.read.seconds`, `.propagation.delay.seconds`, `raft.pending.apply.entries`, the
`write.commit`/`.failed` counters) are *registered* in `ConfigdMetrics` but **never recorded** —
the S1 "9 SLO metrics hardwired to zero" defect, still live (no record/increment call site exists
in main; the raft-pending gauge is literally `()->0L` at `ConfigdServer:332`). Where, semantically,
is each recorded so the dashboard/alert measures the *right thing*?

**Resolution (lead, self-resolved technical).**
- **`write_commit_seconds` + `write_commit_total` + `write_commit_failed_total`:** record **true
  end-to-end commit latency** at the `raftProposer` lambda (`ConfigdServer:~895-924`), which runs on
  the HTTP write thread (OFF the R-01 tick hot path; already allocates a `CompletableFuture`) and
  sees the final outcome. `t0` at lambda entry; on `Committed` → `record(now−t0)` + `total++`; on
  `Lost`/`Indeterminate` → `failed++`; on `Overloaded` → `write_rejected_overloaded_total++`. This
  is the 16 ms S5 "write commit p99 (local component)" number — semantically correct for the
  "< 150 ms" SLO. NOT apply-duration.
- **`apply_seconds`:** the **apply duration** already captured at `ConfigStateMachine.apply()`
  (`applyStart` at :247) — route the existing `StateMachineMetrics.onWriteCommitSuccess(nanos)`
  through a new `ConfigdMetrics`-backed `StateMachineMetrics` adapter into `applySeconds().record`,
  NOT into `write_commit_seconds` (different quantity). Also wire `onSnapshotInstallFailed` →
  `snapshotInstallFailed`, `onSnapshotRebuildSuccess` → `snapshotRebuild`.
- **`raft_pending_apply_entries`:** thread-safe — publish `commitIndex − lastApplied` to an
  `AtomicLong` on the **tick thread** (the only thread allowed to read `RaftLog`, whose
  `commitIndex`/`lastApplied` are non-volatile plain longs — `RaftLog:50,55`); the gauge supplier
  reads the `AtomicLong`. No cross-thread `RaftNode` read.
- **`edge_read_seconds`:** belongs to the **edge process** (separate registry; the control-plane
  `ConfigdMetrics` instance is not loaded there). Register an `configd.edge.read.seconds` histogram
  in the edge registry recorded at the **HTTP serving boundary** (`EdgeHttpServer` read handler),
  NOT the core `EdgeClientCore.read()` (the gate-5 0-B/op, 1.60 µs path). The HTTP handler already
  allocates, so this cannot regress gate-5 — verified by re-running gate-5.
- **`propagation_delay_seconds`:** a **ghost** — cannot be populated in-process without a
  cross-process clock probe. The real served signal is `edge_staleness_ms` (per-edge gauge,
  ADR-0039 frontier, already emitted + contract-tested, S5 handoff §1). **Reconcile** the
  propagation dashboard panel + alert to `edge_staleness_ms`; do NOT fake-populate the control-plane
  histogram.
- **`raft_elections_total` / `subscription_prefix_count` (dashboard panels 5/6):** evaluate during
  implementation — wire a real, contract-tested series if a clean event/registry seam exists, else
  **REMOVE the panel** (a panel on a non-existent series IS the S1 defect; honesty over decoration).
  Decision recorded in D-4 after investigation.

**Keystone deliverable.** A control-plane metrics-wiring contract test that drives the REAL paths
(a real single-node commit, an apply, an overload-reject) and asserts each series records a
NON-ZERO value — the "proven emitted with real data" test the cartographer found missing, closing
the S1 debt at the gate. Implemented: `MetricsWiringContractTest` (4 tests, all green) +
`EdgeHttpServerTest.servedReadRecordsLatencyHistogram` (edge-process read latency).

---

## D-3 (TECH) — Third live blind-dashboard defect found: exporter built WITHOUT histogram schedules

**Finding.** While wiring D-2 I discovered the control-plane `PrometheusExporter` was constructed as
`new PrometheusExporter(metricsRegistry)` (no schedules) at `ConfigdServer` — so even once the SLO
histograms record data, they render as *quantile* lines, NOT the `_bucket{le=...}` series the
burn-rate alerts query (`configd_write_commit_seconds_bucket{le="0.150"}`, etc.). The alert bucket
series would be permanently empty → the burn-rate alerts could never evaluate. This is a THIRD
S1-class defect that survived F5/H-001 (which created `histogramSchedules()` but never passed it to
the live exporter; the S5 cartographer also mis-reported it as wired).

**Resolution (lead, self-resolved technical).** Pass `ConfigdMetrics.histogramSchedules()` to the
control-plane exporter and `ConfigdMetrics.edgeProcessHistogramSchedules()` to the edge exporter.
Guarded by the contract test asserting the `le=0.150` / `le=0.001` bucket lines render in the live
scrape (`MetricsWiringContractTest`, `EdgeHttpServerTest`). Added `ConfigdServer.scrapeMetrics()`
(renders via the SAME production exporter) so the guard exercises the real wiring, not a test-built
exporter.

## D-4 (TECH) — Dashboard panels 5/6 (leader churn, subscribed prefixes): wire real series, don't delete

**Question.** The dashboard `configd-overview.json` panels query `configd_raft_elections_total`
(panel 5) and `configd_subscription_prefix_count` (panel 6) — neither existed as an emitted series.
Charter Hard Rule 1: a panel on a non-existent series IS the S1 defect. Delete the panels, or wire
the series?

**Resolution (lead, self-resolved technical).** WIRE both — each has a clean, thread-safe seam, so
deletion would needlessly drop real operational signal:
- `configd_raft_elections_total`: incremented on the tick thread by the positive delta of
  `RaftNode.currentTerm()` across ticks (a term bump ≙ an election / leadership change). Read on the
  tick thread only (R-01-safe against the non-volatile `currentTerm` field). NB: every node observes
  the same term advances, so the "leader churn" panel should use `max(...)` across nodes, not `sum`
  — the dashboard query is written accordingly.
- `configd_subscription_prefix_count`: a sampled gauge over `SubscriptionManager.prefixCount()`
  (`HashMap.size()` — a plain int field read; a benign race yields at most a momentarily-stale value,
  which is standard gauge semantics for a capacity panel).

Both are asserted non-zero in `MetricsWiringContractTest.gaugesAndElectionsCounterAreNotHardwiredToZero`.

## D-5 (TECH) — JVM/process runtime metrics: wire a binder rather than ship phantom panels

**Question.** The charter's runtime dashboard board (GC/heap/FD/threads) and the leak alerts need
JVM/process series. None were emitted (no binder; S5 measured them with external `jstat`/`/proc`).
Ship the board on phantom series (the S1 defect), or wire real ones?

**Resolution (lead).** Added `JvmMetrics` (java.lang.management gauges: heap used/max, threads, open
FDs, GC time/count) bound in BOTH processes. Contained (~50 lines, scrape-time MXBean reads, off the
hot path), and it makes the runtime board + the FD/thread/heap leak alerts query REAL, contract-proven
series. Hard Rule 1 respected (no panel on a non-emitted series).

## D-6 (SCOPE) — Runbook execution-validation: validate the fault+recovery mechanism; mark live-threshold injectors PENDING honestly

**Decision (conservative default, logged).** Every runbook's FAULT and RECOVERY MECHANISM is validated
by an EXECUTED harness/test (OverloadChaosTest, StorageEnospcConsensusReactionTest, GameDayDrillTest,
MetricsWiringContractTest, BackupRestoreRoundTripTest, the e2e-compose phases, the wedge-family tests —
all green; `runbooks-validation.md`). For four alerts no harness drives the live system *across the
threshold* (edge-read p99, raft backlog>5000, snapshot ≥3-fail/15m, resource-leak — emergent or
needs production-scale load). Rather than fabricate an injector or silently claim validation, those are
marked PENDING: the alert RULE is proven to fire/quiet on the threshold value by promtool and the
mechanism is tested; the live-threshold drill is deferred to S7.5 / the M-items. Honest over complete.

## D-7 (SCOPE) — Rolling upgrade/rollback: prove the interop invariant + durable restart; defer the live cross-binary matrix

**Decision (conservative default, logged).** A literal cross-version N↔N+1 mixed-cluster no-gap
*measurement* needs a second release artifact; the repo is a single `0.1.0-SNAPSHOT`. So S6 proves the
**interop invariant** instead — wire byte-stability within wire-version `0x01` (golden-fixture tests:
any two builds at `0x01` are byte-identical, so old↔new interop holds by construction), plus durable
no-write-loss restart and backup/restore state-equality, with the version-bump discipline gate-enforced
(a wire change breaks the golden test → forces a `WIRE_VERSION` bump + the deferred Hello handshake +
the `@Disabled` WAL/snapshot compat stubs). The live cross-binary fleet measurement is the first S7.5
item once a v0.2 tag exists. Not claimed as load-validated (`deployment.md §3`).

## D-8 (TECH) — Alert fires/quiet via pinned promtool, not an in-repo PromQL evaluator

**Decision (lead).** The deployed alerts are PromQL rules; the faithful fires/quiet test is
`promtool test rules` (Prometheus' own framework) with synthetic series. Re-implementing PromQL
(`histogram_quantile`, `rate`, `increase`) in-repo would be error-prone and unfaithful. gate-6 fetches
a pinned promtool (2.53.2) — a single static binary — and runs `check rules` + `test rules`. The
in-house `BurnRateAlertEvaluator` (SloTracker) remains for in-process SLO tracking; it is not the
deployed-rule test.
