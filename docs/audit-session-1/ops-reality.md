# Ops-Claims Reality Check — Configd (audit-session-1)

**Auditor:** sre-auditor · **Date:** 2026-06-10 · **Workspace:** `/home/ubuntu/ws-smoke`
**Method:** every metric/command/endpoint referenced by `ops/` and `docs/`
is treated as a **claim** and grepped against `src/main` (file:line) and/or
scraped from a **live** 3-node smoke cluster. Docs are never evidence.

---

## 1. Metrics: alert/dashboard references vs. code emission

**How names map:** `PrometheusExporter.sanitizeName` (`PrometheusExporter.java:161`)
turns `.`/`-` into `_`; counters get a `_total` suffix, histograms emit
`_count` + percentile lines, and `_bucket{le=…}` lines **only** when a
`BucketSchedule` is registered. The running server uses the **single-arg**
`new PrometheusExporter(metricsRegistry)` (`ConfigdServer.java:533-534`),
whose ctor passes `Collections.emptyMap()` for schedules
(`PrometheusExporter.java:83-85`) → **NO `_bucket` lines are ever emitted at
runtime.** Confirmed by live scrape (below): zero `_bucket` lines.

| Metric (Prom name) | Referenced where | Emitted in code (file:line) | VERDICT |
|---|---|---|---|
| `configd_write_commit_seconds_bucket` | alerts (write-commit fast/slow burn), dashboard p99 panel | histogram registered `ConfigdMetrics.java:90`; **`_bucket` series NOT emitted at runtime** (empty schedule) | **PHANTOM at runtime** (bucket series absent) |
| `configd_write_commit_seconds_count` | (denominator of same alerts) | `ConfigdMetrics.java:90` (`configd.write.commit.seconds`) | exists (but always 0 — NOOP, see below) |
| `configd_write_commit_total` | availability alert denominator | `ConfigdMetrics.java:82` registered; increment path = `StateMachineMetrics.onWriteCommitSuccess` which is **NOOP** in the server (`ConfigStateMachine` built with 4-arg ctor → `StateMachineMetrics.NOOP`, `ConfigdServer.java:209`) | **exists but DEAD** — reads 0 on a real committed write |
| `configd_write_commit_failed_total` | availability alert numerator | `ConfigdMetrics.java:83`; same NOOP wiring | exists but DEAD (always 0) |
| `configd_edge_read_seconds_bucket` | alerts (edge-read fast/slow/p999), dashboard | `ConfigdMetrics.java` (`configd.edge.read.seconds`); `_bucket` NOT emitted | **PHANTOM at runtime** |
| `configd_edge_read_total` | (edge read counter) | registered `ConfigdMetrics.java`; **never incremented in src/main** (no edge read path runs) | exists but DEAD (0) |
| `configd_propagation_delay_seconds_bucket` | propagation fast-burn alert, dashboard | registered; `_bucket` NOT emitted; propagation path dead (see §3) | **PHANTOM at runtime** |
| `propagation_lag_violation_total` | (PropagationLivenessMonitor) | `PropagationLivenessMonitor.java:26,52` | exists but **cannot fire** (§3) |
| `configd_raft_pending_apply_entries` | saturation alert, dashboard | `ConfigdMetrics.java` gauge (`configd.raft.pending.apply.entries`) | exists (live scrape shows it, value 0) |
| `configd_snapshot_install_failed_total` | snapshot-install alert | `ConfigdMetrics.java` (`configd.snapshot.install.failed`) | exists (0) |
| `configd_raft_elections_total` | **dashboard** "elections" panel | grep src/main → **no emitter anywhere** | **PHANTOM** |
| `configd_subscription_prefix_count` | **dashboard** "subscriptions" panel | grep src/main → **no emitter anywhere** | **PHANTOM** |
| `configd_build_info` | **dashboard** build-info panel | grep src/main → **no emitter anywhere** | **PHANTOM** |

**Phantom-metric count:**
- **3 metrics with NO emitter at all** (`configd_raft_elections_total`,
  `configd_subscription_prefix_count`, `configd_build_info`).
- **3 histogram `_bucket` families never emitted at runtime**
  (write-commit, edge-read, propagation) — every MWMBR burn-rate alert
  expression and dashboard p99 panel that queries `*_seconds_bucket` has
  **no time series to read**.
- **2 counters wired to NOOP** (`configd_write_commit_total`,
  `configd_write_commit_failed_total`) → the `ConfigdControlPlaneAvailability`
  page evaluates `failed/(failed+total)` with both terms permanently 0.

Grep evidence (no emitter):
```
$ grep -rn "raft_elections_total|electionsTotal|subscription_prefix|subscriptionPrefix|build_info|buildInfo" \
    --include="*.java" | grep src/main      →  (empty)
```

Live scrape (leader, AFTER a confirmed committed write — note all 0, no `_bucket`):
```
configd_snapshot_install_failed_total 0
configd_write_commit_failed_total 0
configd_edge_read_total 0
propagation_lag_violation_total 0
configd_write_commit_total 0
configd_snapshot_rebuild_total 0
configd_raft_pending_apply_entries 0
configd_edge_read_seconds_count 0
configd_propagation_delay_seconds_count 0
configd_apply_seconds_count 0
configd_write_commit_seconds_count 0
```
(11 series, 22 lines total. No `*_bucket{le=…}`. No leader/term/role gauge.)

---

## 2. Runbooks: do their commands/scripts/endpoints exist?

Spot-checked every distinct command/script/endpoint reference across
`ops/runbooks/*` (12 files) and `docs/runbooks/*` (9 files).

| Reference | Kind | Exists? | Evidence / VERDICT |
|---|---|---|---|
| `ops/scripts/restore-snapshot.sh` | script | **YES** | present, `--help` rc=0; **but K8s-only** — fails-closed `exit 3` if `kubectl` absent (verified live) → **not runnable on a bare host** |
| `ops/scripts/restore-conformance-check.sh` | script | **YES** | present, executable; invoked by restore-snapshot.sh step 4 |
| `/health/ready`, `/metrics`, `/v1/config/` | HTTP | **YES** | `HttpApiServer.java:74-82`; all verified live |
| `/raft/status` | HTTP | **NO** | no `/raft/*` context in server (`HttpApiServer.java`); **runbooks self-flag this** — every runbook using it carries a TODO/PA- caveat (e.g. `control-plane-down.md:59,91`: "`/raft/status` … on HttpApiServer today" + "admin endpoint missing"). Honest gap, not a silent lie. |
| `/raft/add`, `/raft/remove`, `/raft/transfer` | HTTP | **NO** | no membership/transfer admin endpoint exists; carried with caveats in `snapshot-install.md`, `disaster-recovery.md` |
| `kubectl … -l app=configd …` (get/exec/scale/delete/rollout/cordon) | k8s | **conditional** | these are `kubectl` ops against the StatefulSet named `configd`, NOT a `configd` CLI; valid **only** with a K8s cluster + kubectl (absent here) |
| `configd snapshot create` (operator-runsheet §2) | CLI | **NO** | **no `configd` CLI main exists** (only `ConfigdServer` server main). The promised `snapshot-manifest.json` producer does not exist. |
| `InvariantMonitor.assertAll()` → "INVARIANTS OK" (runsheet §2 pass criterion) | API | **NO** | `InvariantMonitor` has `check`/`checkAll`/`violations`/`assertMonotonicRead`/`assertStalenessBound`/`register` — **no `assertAll()`**, no "INVARIANTS OK" log line (grep empty). The runsheet's pass gate references a non-existent method. |

**Summary:** the two restore scripts are real but **K8s-bound (unrunnable on
this host)**. The Raft admin endpoints (`/raft/*`) the runbooks lean on do
not exist — but the runbooks **honestly caveat** that gap. The
operator-runsheet's `configd snapshot create` and
`InvariantMonitor.assertAll()` pass-criteria reference tooling/methods that
**do not exist** and are **not** caveated — those are the misleading ones.

---

## 3. PropagationLivenessMonitor — what does it actually measure?

**Claim (class javadoc):** "Runtime counterpart of TLA+ LIVE-1: every
committed write eventually reaches every live edge … Fires a violation if any
live edge falls behind by more than the threshold."

**Reality: it measures nothing and can never fire.** Its violation logic
(`PropagationLivenessMonitor.java:45-56`) iterates `edgeAppliedVersions`,
which is **only** populated by `updateEdgeApplied(edgeId, version)` (`:33`),
and compares against `leaderCommitIndex`, **only** set by
`updateLeaderCommit` (`:29`).

```
$ grep -rn "updateEdgeApplied|updateLeaderCommit|propagationMonitor\.|\.checkAll()" \
    --include="*.java" | grep src/main
ConfigdServer.java:559:                propagationMonitor.checkAll();
PropagationLivenessMonitor.java:29:    public void updateLeaderCommit(...)   # defn only
PropagationLivenessMonitor.java:33:    public void updateEdgeApplied(...)    # defn only
```

In the running server, `checkAll()` is called every 10 ms tick
(`ConfigdServer.java:559`), but **neither feed method is ever called
anywhere in `src/main`.** Therefore:
- `edgeAppliedVersions` is **permanently empty** → `checkAll()` loops zero
  times and returns 0 on every tick.
- `leaderCommitIndex` is **permanently 0**.
- `propagation_lag_violation_total` is structurally incapable of incrementing.

This is consistent with §"Edge" of `smoke-test.md`: the fan-out/edge pipeline
is severed, so there are no edges to track. The monitor is a no-op decoration.
Live scrape confirms `propagation_lag_violation_total 0` after a committed
write. **VERDICT: incapable of ever firing; measures nothing.**

---

## 4. Alerts: do rule label/metric names match what the exporter exposes?

`ops/alerts/configd-slo-alerts.yaml` (5 groups, 9 rules). Name-format check
against `PrometheusExporter` output:

| Alert | Series it queries | Exporter emits that name-format? | Verdict |
|---|---|---|---|
| `ConfigdWriteCommitFastBurn`/`SlowBurn` | `configd_write_commit_seconds_bucket{le="0.150"}`, `_count` | `_count` YES; **`_bucket` NO** (empty schedule) | **broken** — numerator series absent |
| `ConfigdEdgeReadFastBurn`/`SlowBurn`/`P999Breach` | `configd_edge_read_seconds_bucket{le="0.001"}` | **`_bucket` NO** | **broken** — no bucket series |
| `ConfigdPropagationFastBurn` | `configd_propagation_delay_seconds_bucket` | **`_bucket` NO**; pipeline dead (§3) | **broken** |
| `ConfigdControlPlaneAvailability` | `configd_write_commit_failed_total`, `configd_write_commit_total` | names MATCH format; but both **NOOP-wired → always 0** (`ConfigdServer.java:209`) | name OK, **value always 0** (alert's own comment H-004 acknowledges the `_total`-counts-successes wiring problem) |
| `ConfigdRaftPipelineSaturation` | `configd_raft_pending_apply_entries` | MATCH (live scrape shows it) | **OK (format + presence)** |
| `ConfigdSnapshotInstallStalled` | `configd_snapshot_install_failed_total` | MATCH (live scrape shows it) | **OK (format)** |

**Name-format finding:** the **counter / gauge / `_count`** names the alerts
use are correct (`.`→`_`, `_total` suffix all match `sanitizeName`). The
failure is not a typo — it is **structural**: every burn-rate rule depends on
`*_seconds_bucket` series that the running server **never produces** (single-arg
exporter ctor → empty `BucketSchedule`). 2 of 9 rules (raft saturation,
snapshot-install) reference live series and would function; the 6 burn-rate
rules and the availability rule are non-functional against this binary.

---

## 5. DR drills (`ops/dr-drills/`)

**Prose + empty results — nothing runnable here.** `ops/dr-drills/README.md`
states verbatim: *"This directory is currently empty because no drills have
been executed against this branch."* The drills it lists
(`restore-from-snapshot`, `control-plane-down`, `snapshot-install`,
`disaster-declaration`) are **procedures** that delegate to the K8s-bound
`ops/scripts/restore-snapshot.sh` + `restore-conformance-check.sh` and to
`kubectl`. With no kubectl and no K8s cluster on this host, **none is runnable
as written.** Verified: `restore-snapshot.sh` dry-run fails-closed at the
kubectl gate (`exit 3`). No `results/` files exist → no measured drill
evidence exists on this branch.

---

## 6. Live `/metrics` and `/health` vs. dashboard expectations

Scraped from the live 3-node smoke cluster (leader, after a committed write).

**`/health`:** `/health/live` → 200 (always, once HTTP up); `/health/ready`
→ 200 with `{"healthy":true,"checks":[{"name":"raft-leader",…}]}` once a
leader exists. **Single readiness check** (`raft-leader`); no startup probe;
readiness does not flip on drain (no drain state exists).

**`/metrics` exposed (11 series, all 0):** listed in §1. Notably **absent**
vs. what dashboards/alerts expect:
- **No `_bucket{le=…}` lines** for any of the 3 SLO histograms (dashboards'
  `histogram_quantile(…_bucket…)` panels render empty).
- **No `configd_raft_elections_total`, `configd_subscription_prefix_count`,
  `configd_build_info`** (3 dashboard panels have no data source).
- **No leader/term/role gauge** at all — an on-call cannot tell from
  `/metrics` which node is leader (had to probe with a PUT instead).
- Core write metrics (`configd_write_commit_total`, `_seconds`) read **0**
  even though the write demonstrably committed and replicated to all 3 nodes
  (NOOP wiring, `ConfigdServer.java:209`).

**Net:** the dashboard (`ops/dashboards/configd-overview.json`, 7 panels)
would render: 3 panels empty (phantom metrics), 3 p99 panels empty (no
buckets), and the raft-pending-apply panel functional. The SLO alerting layer
is effectively non-operational against this binary.

---

## Phantom / dead-metric tally

- **3** metrics with no emitter anywhere (`configd_raft_elections_total`,
  `configd_subscription_prefix_count`, `configd_build_info`).
- **3** histogram `_bucket` families never emitted at runtime (write-commit,
  edge-read, propagation) → 6 of 9 alert rules + 3 dashboard p99 panels broken.
- **2** counters NOOP-wired → always 0 (`configd_write_commit_total`,
  `configd_write_commit_failed_total`) → availability page non-functional.
- **1** monitor incapable of firing (`PropagationLivenessMonitor`).
- **Tooling phantoms:** `configd` CLI (entire `configd <verb>` family),
  `configd snapshot create`, `InvariantMonitor.assertAll()`/"INVARIANTS OK",
  and `/raft/*` admin endpoints — none exist (the `/raft/*` ones are
  caveated in-runbook; the runsheet's CLI/assertAll are not).
