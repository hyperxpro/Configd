# Configd v1 - First-30-Days Burn-In Contract

> **Status: Ready for operator adoption.** This document defines the first-30-days operating posture
> for Configd v1. It is a posture the operator *adopts and operates*; the reliability engineer
> produces the document, the operator owns the rotation, the ratification, and the exit call. See the
> v1 readiness review (`docs/archive/readiness/v1-go-no-go-2026-07-01.md`, section 0 and section 5.1)
> for the context behind this contract.
>
> **Docs-only.** Nothing here modifies production code. Every threshold below is grounded in a
> measured EC2 baseline and cites the source doc; every metric name is verified in code and cited to
> `file:line`. Where a signal has **no real emitted series**, that is stated plainly rather than
> papered over with an invented metric.

---

## 1. Why this contract exists (the honest empirical envelope)

v1's stability evidence is a **6-hour soak - not 30 days, not 24 hours.** The prior 24 h attempt
OOM'd at **3.45 h** (box capacity on that run, *not* a leak; the clean-code soak reached
the full 6 h flat). So v1 does **not** ship with a claim of proven 30-day stability. It ships with a
**heightened first-30-days posture**: tighter alert thresholds than steady-state, a daily
error-budget review, predefined rollback triggers, and a named on-call - held until a clean 30-day
production window converts "6 h + heightened watch" into "30-day proven."

**What is measured and green** (the two paid EC2 runs, docs-only, against a `main`-identical server):

- **Durability** - `ec2-2026-06-30/02-dr-drills.md`: 372 ms leader-loss write-availability gap,
  **1 bounded election (no storm)**, **0/1000 committed-write loss** across three fault modes; node
  recovery RTO 4.2 s (WAL) / 5.9 s (snapshot).
- **Long-run stability** - `ec2-2026-06-30/04-soak.md`: **6 h clean** (21,601 s, 691 samples), FD
  flat, RSS 2.6 % spread, heap floor stable, GC 0.92 %, 0 rejected.
- **Horizontal scale** - `ec2-horizontal-2026-07-01/02-scaling-curve.md` + `04-verdict.md`:
  near-linear **2.45x on 3 machines** (656 -> 1075 -> 1607 w/s), cluster-bound by consensus churn, not
  hardware.
- **Single-node ceiling** - `ec2-2026-06-30/01-nxknee.md`: **~800 w/s open-loop knee**,
  **leadership-churn-bound at ~20 % CPU / ~16 % NVMe** - not fsync/CPU/disk saturation.

**What the contract explicitly accepts as bounded (the edges to watch, not hide):**

| Edge | The honest bound |
|---|---|
| Soak duration | **6 h, not 24 h / 30 d.** Leak/OOM *risk* closed on clean code; long-tail stability is what the 30-day window proves. |
| DR topology | Drills ran on the **single-box, 3-co-located-node** topology. Cross-machine failover adds network RTT to the 372 ms gap; the *correctness* (no loss, bounded election) is topology-independent and the cross-box consensus path was separately exercised in run 2. |
| Throughput | **No literal 10 k/s sustained and no 100 k burst** has ever run. Single-cluster max measured = 1607 w/s @ 3 machines; 10 k/s is a sharded-aggregate target, path-proven not number-captured. |
| WAN | **No cross-region / WAN** measurement. Single-region by design; both runs same-AZ. |
| Leadership | **Auto-balanced (on by default at N>1), but the 2.45x was measured under MANUAL placement.** `LeaderBalanceLoop` sheds an over-owned leader per cycle; the ADMIN `transfer-leadership` route is wired. The balancer is built + E2E-tested, **not yet load-measured at scale** — do not claim it proven at the 2.45x number. Transfer-on-graceful-shutdown remains a follow-up. |
| Alert values | The `ops/alerts/` thresholds are **PROPOSED / design-set, not calibrated** against production SLO (register 7.12). Calibrating them is a burn-in *exit* deliverable. |

> **Baseline caveat that shapes every absolute number below.** The soak resource baselines (FD,
> threads, RSS) were captured on **3 co-located JVMs on one box** and are reported as *totals* across
> those three. A production node is **one JVM per box**. Treat the soak absolutes as the **shape**
> evidence (perfectly flat = no leak); **re-capture the per-instance production floor in the first
> 48 h** and set absolute thresholds relative to *that* (the recipe is given per-signal below).

---

## 2. Heightened alerting thresholds

Each threshold ties to a measured baseline and its source doc. "Existing" = the rule already lives in
`ops/alerts/configd-slo-alerts.yaml` (this contract keeps or tightens it); "NEW" = added for burn-in.
Metric series are the sanitized Prometheus names; the definition `file:line` is in the provenance
table (section 6). Series carry no app-level `instance`/`cluster` labels - those come from the scrape config.

### 2A. The OOM / resource-leak class (the risk the soak closed - watch it re-open)

| Signal | Measured baseline (source) | Heightened alarm | Rationale |
|---|---|---|---|
| **Heap-used floor** | Post-GC floor **~390-404 MB**, sawtooth peaks **~545 MB**, always returns; heap max 2 g/node (`04-soak.md`) | **WARN** `jvm_heap_used_bytes / jvm_heap_max_bytes > 0.5` for 1h; keep **PAGE** `> 0.9` for 30m (existing `ConfigdHeapPressure`). Leak-shape: `min_over_time(jvm_heap_used_bytes[6h])` climbing past ~2x the ~400 MB floor. | Soak floor sat at ~20 % of a 2 g heap, peaks ~27 %. A *rising floor* (not the sawtooth) is the leak class the 3.45 h OOM would show. `>0.5` is far below the 0.9 page but ~2.5x the measured floor-fraction. Recalibrate the fraction to the deployed heap. |
| **RSS / container working set** | RSS total **2,436 -> 2,498 MB, 2.6 % spread** (ZGC commit plateau reached early) (`04-soak.md`) | Container / `process_resident_memory_bytes` drift **> ~10 %** above the established plateau **and rising** -> investigate; approaching the container memory limit -> rollback-consideration (section 3). | The 3.45 h failure was **box/container capacity** (not a leak) - so container memory is the true OOM-*kill* guard. **FLAG:** RSS is **not** an app-emitted series (see section 6); watch it via the node/container exporter. |
| **File descriptors** | **Flat 350 -> 350** (min = max = 350 over 21,601 s), 3-JVM total (`04-soak.md`) | Leak-shape (any sustained monotonic climb off the per-instance 48 h floor) -> **WARN**; keep `max(process_open_fds) > 500` for 15m backstop (existing `ConfigdFileDescriptorLeak`). | The FD-release fix produced a *perfectly flat* 350; any upward slope is exactly that regression class. **FLAG:** the existing rule's rationale cites a stale baseline (~69) - the real baseline is **350**; 500 is ~1.43x it. 350 is a 3-JVM total -> set the per-instance backstop at ~1.4x the observed per-instance floor. |
| **Threads** | **172-180** (pool jitter), flat, 3-JVM total (`04-soak.md`) | `max(jvm_threads_current) > 300` for 15m **WARN** (tightened from the existing 400). | Flat 172-180; monotonic growth = thread leak. **FLAG:** existing rule cites a stale baseline (~93); real baseline 172-180 (total). 300 is ~1.7x it; re-baseline per-instance. |
| **GC overhead** | **0.92 %** overhead, **linear** accumulation (1.96 s -> 198.4 s cum over 6 h), no degradation (`04-soak.md`) | `rate(jvm_gc_collection_millis[5m]) / 1000 > 0.05` (5 % wall) for 30m **WARN** (NEW). | 5 % is ~5.4x the 0.92 % baseline yet far from a GC-bound state - catches ZGC allocation-pressure drift early. Pair with the heap-floor row (a rising floor + rising GC = the leak signature). |

### 2B. The throughput / consensus class (the knee is churn, not CPU - watch churn)

| Signal | Measured baseline (source) | Heightened alarm | Rationale |
|---|---|---|---|
| **Leader-churn / election rate** | Churn-free below the knee (soak 300 w/s, `04-soak.md`); healthy failover = **1 bounded election** (`02-dr-drills.md`); **at the ~800 w/s knee: 26-43 elections / 20 s**; N=4/8 clean = 0-2 (`01-nxknee.md`) | `increase(configd_raft_elections_total[10m]) > 10` (excluding known deploys/failovers) **WARN**; sustained storm (approx the knee rate, e.g. `> 30` in 5m) **PAGE** (NEW - no existing rule). | **The single most important early-warning for this system.** The single-node ceiling is *leadership-churn-bound*; elections rise **before** throughput collapses, and the cluster churns at only ~20-62 % CPU so CPU will not warn you. 1 election = healthy failover; sustained double-digits/min = at/approaching the knee. |
| **Write shed / rejection** | **0 rejected** of 9,000 at 300 w/s (`04-soak.md`); knee ~656-800 w/s | `rate(configd_write_rejected_overloaded_total[5m]) > 1` for 5m **WARN** (existing `ConfigdWriteOverloadShedding`); in burn-in, **any** sustained shed is investigated and correlated with the election rate. | 0-reject baseline sits well below the knee. Sustained shed **+** rising elections = offered load nearing the churn knee -> shard the keyspace or shed upstream. |
| **Commit p99 latency drift** | p50 **~2.2-2.5 ms**, p99 **~3-6 ms**, no drift (`04-soak.md`); SLO le = 150 ms (`configd-slo-alerts.yaml`) | `histogram_quantile(0.99, sum by (le)(rate(configd_write_commit_seconds_bucket[5m]))) > 0.025` for 10m **WARN** (NEW, tighter than the 150 ms burn-rate). Keep the existing fast/slow burn PAGES on le = 0.150. | 25 ms is 4-8x the 3-6 ms baseline but still **6x inside** the 150 ms SLO - an early-warning of commit-pipeline drift long before the SLO budget burns. |
| **Per-node CPU (backstop only)** | N=3 knee **~62 %/box** (~38 % idle) (`02-scaling-curve.md`, `04-verdict.md`); single-group churn ceiling hit at **~20 % CPU** (`01-nxknee.md`) | Node/container CPU **> 85 %** sustained **WARN** (backstop). | **Do not use CPU as the throughput early-warning** - the cluster saturates (churns) at 20-62 % CPU, well below any CPU alarm. 85 % is a genuine-hardware-saturation backstop *above* the measured 62 % knee. **FLAG:** not an app series; node/container exporter. |
| **Raft apply backlog / follower lag** | Apply backlog ~0 steady-state; recovery RTO 4.2 s / 5.9 s (`02-dr-drills.md`) | `max(configd_raft_pending_apply_entries) > 5000` for 5m (existing `ConfigdRaftApplyBacklog`); per-shard `raft_shard_apply_lag_<gid>` rising-and-not-recovering; **`raft_shard_replication_lag_max_<gid>`** (the per-follower replication-lag gauge) rising-and-not-recovering. | A leader that commits but cannot apply, or a follower that never catches up, shows here. **Updated (Gate-2):** the per-follower replication-lag gauge `raft_shard_replication_lag_max_<gid>` now exists — it is the real follower-wedge signal this row previously lacked, no longer a proxy-only. |

### 2C. The correctness / security / snapshot / edge class

| Signal | Measured baseline (source) | Heightened alarm | Rationale |
|---|---|---|---|
| **Snapshot size + chunked-transfer health** | Chunked InstallSnapshot (per-chunk cap 4 MiB = `MAX_SNAPSHOT_CHUNK_BYTES`, default chunk 1 MiB) lifts the old total-state ceiling; the follower reassembles in heap under a fail-closed `configd.raft.maxReassembledSnapshotBytes` cap (default 512 MiB) — an over-cap reassembly is refused (drop partial, `SEVERE` log, no OOM/corruption), leaving that follower out of quorum until the cap is raised | (a) `configd_snapshot_bytes` gauge — track snapshot size vs the per-chunk cap and the reassembly cap (this is the size gauge the burn-in contract asked for); (b) `raft_shard_snapshot_reassembly_refused_<gid>` and `raft_shard_snapshot_chunk_send_rejected_<gid>` — **any increase = PAGE**; (c) existing `ConfigdSnapshotInstallStalled` `increase(configd_snapshot_install_failed_total[15m]) >= 3`; (d) `raft_shard_replication_lag_max_<gid>` as the follower-wedge proxy. | **Updated (Gate-2):** the snapshot-size gauge and the reassembly-refused / chunk-send-rejected counters this row previously said were **missing now exist** — detection is no longer log-watch-only. The `SEVERE` reassembly-refusal log still fires and is now metric-backed. |
| **ACL policy load failed** | Healthy = 0 (`configd.acl.policy.load.failed`, `ConfigdMetrics.java:90`) | `increase(configd_acl_policy_load_failed_total[15m]) >= 1` for 5m **WARN** (existing `ConfigdAclPolicyLoadFailed`) -> investigate immediately; if it persists while `configd_acl_policy_reload_total` does **not** advance, last-good is frozen (poison key) -> escalate to the rollback check (section 3). | A failed load = an `_acl/` update **silently did not apply**; the loader fails closed to last-good, so clients see no error. A poison key delivered via snapshot/replay **freezes every subsequent policy update** until removed -> security drift. |
| **Leadership distribution (N>1 only)** | 2.45x **requires** 1-leader-per-box (`02-scaling-curve.md`); leaders can "sweep" onto one node; **2-1-0 tolerated** (1628 w/s) (`05-leadership-placement.md`) | Alarm if `max(raft_node_leader_count) - min(raft_node_leader_count)` stays imbalanced across several balancer cycles (base cadence 30 s) - e.g. one node leads **all** N groups while another leads **0** (a sweep the balancer has not corrected). | **FLAG:** only material at **N>1** - v1 default is N=1, so dormant unless sharded. The built-in `LeaderBalanceLoop` (on by default, sheds one over-owned leader per cycle) normally corrects drift automatically; the alarm catches a **stuck** imbalance the balancer failed to fix. Response: a manual `transfer-leadership` (the route is wired) or a rolling restart. Robust to 2-1-0; a persistent full sweep forfeits the horizontal benefit until corrected. |
| **Edge staleness** | Contract section 2 state boundaries: CURRENT->STALE **500 ms**, STALE->DEGRADED **5 s** (the degraded alert fires earlier, at 2 s); prior p99 reference **255 ms** (`configd-slo-alerts.yaml` comment) | Existing `ConfigdEdgeStalenessWarn` `max(edge_staleness_ms) > 500` for 2m / `ConfigdEdgeStalenessDegraded` `> 2000` for 1m; watch `configd_edge_staleness_violation_total` increments and `edge_staleness_implausible_total` (clock skew, existing `ConfigdClockSkewSuspected`). | The 500 ms and 5 s state boundaries are **contract-defined, not empirical** (the 2 s degraded alert is a tighter operational threshold). **FLAG:** edge staleness was **not re-measured under load** in either EC2 run (both were write-plane) - the 255 ms baseline is a prior reference. Burn-in must **capture the real production edge-staleness distribution** to calibrate (this is a direct input to closing register section 7.12). |
| **Correctness invariants** | 0 violations expected (`invariant.violation.*`, `InvariantMonitor.java:43,234`; consistency-contract section 8 INV-M1 / INV-S1) | Any `increase(invariant_violation_monotonic_read_total) > 0` or `invariant_violation_staleness_bound_total > 0` = **correctness-drift INVESTIGATION** (not a standalone page); a confirmed violation under normal operation = rollback-consideration (section 3). | These bridge the two data-plane consistency invariants into metrics. A real violation means the consistency contract itself is breaking - the highest-severity class of drift. |
| **Control-plane write availability** | SLO **99.999 %** over 30 min (existing `ConfigdControlPlaneAvailability`) | Keep as-is (page). In burn-in, review the burn against budget **daily** (section 4). | The denominator is failed+total so a full outage still pages (no NaN). This is the top-line control-plane SLO for the daily error-budget review. |

---

## 3. Rollback triggers

These are the **concrete signatures** that trip a rollback decision. Each is a precise, measured
tripwire - not a vibe. A rollback trigger firing **pages the named owner and starts the rollback
runbook** (`ops/runbooks/release.md`); it is not a "watch and see."

| # | Trigger | Precise, measured tripwire | Why it's a rollback (baseline it violates) |
|---|---|---|---|
| **R1** | **Heap / RSS drift past the soak envelope (OOM class)** | Post-GC heap **floor** (`min_over_time(jvm_heap_used_bytes[6h])`) rising monotonically past ~2x the ~400 MB soak floor **AND** container RSS climbing >10-15 % past its plateau and continuing toward the container limit - i.e. a *rising trend*, not a sawtooth peak. | The soak proved a **stable floor** (~390-404 MB) and a **2.6 % RSS spread** over 6 h. A monotonic climb is the exact 3.45 h-OOM signature re-emerging (`04-soak.md`). Roll back before the container OOM-kills. |
| **R2** | **FD leak (off the flat-350 baseline)** | `process_open_fds` on any instance climbing monotonically off its established 48 h floor (e.g. crossing 500 and continuing to 700+), not plateauing. | The soak FD count was **perfectly flat (min = max = 350)** - the FD-release fixes' whole point. Any sustained growth is a regression of that fix (`04-soak.md`). |
| **R3** | **Any committed-write loss** | **One** confirmed loss: a key that returned `200` (committed) later reads back missing or stale after a fault; or `increase(invariant_violation_monotonic_read_total) > 0` (a version went backwards). | The durability contract is **0-loss** - DR proved **0/1000 across three fault modes** (`02-dr-drills.md`). There is no acceptable nonzero loss rate; a single loss is a **hard, immediate** rollback. |
| **R4** | **Election storm (vs the DR-proven single bounded election)** | `increase(configd_raft_elections_total[5m])` showing a sustained storm (approx. the knee's 26-43/20 s = 78-129/min, or simply sustained double-digits/min) with **no** correlated deploy/known-failover, and write availability degrading. | DR failover was **1 bounded election, 372 ms gap, no storm** (`02-dr-drills.md`). A self-sustaining storm means the cluster is churning leaderless - availability is forfeit. (Distinguish from a one-shot election on a deliberate failover, which is healthy.) |
| **R5** | **ACL policy load failed and stuck** | `configd_acl_policy_load_failed_total` firing **while** `configd_acl_policy_reload_total` does not advance across the window, and the stuck state cannot be cleared by removing the offending `_acl/` key. | The loader fails closed to last-good, so this is **silent to clients** yet means `_acl/` updates are not applying and a poison key freezes all subsequent policy updates - **stale ACL = security drift**. Roll back to the last-known-good policy state. |

**Rollback mechanics** are in `ops/runbooks/release.md` and the DR runbooks
(`ops/runbooks/disaster-recovery.md`, `ops/runbooks/restore-from-snapshot.md`); this contract defines
*when* to pull the trigger, not the mechanics.

---

## 4. On-call posture (first 30 days)

**Named on-call.** The operator names the rotation and the escalation owner **before the first
production node** - the contract requires a *named* human, not "whoever notices." Record the primary,
the secondary, and the escalation owner in the on-call rotation doc (`ops/on-call-rotation.md`, per
ADR-0025 "on-call rotation required").

**The heightened watch - the first-30-days dashboard (in priority order):**
1. **The OOM/leak trio** - heap-used floor trend, container RSS trend, `process_open_fds` slope
   (section 2A). These re-open the one risk the soak closed.
2. **Leader-churn / election rate** - `configd_raft_elections_total` (section 2B). The earliest knee signal;
   the system churns before it slows, and it churns at low CPU.
3. **Commit p99 drift** - the 25 ms early-warning, well inside the 150 ms SLO (section 2B).
4. **Write availability + shed rate** - the top-line SLO and the 0-reject baseline (sections 2B and 2C).
5. **Correctness invariants + edge staleness** - any nonzero invariant counter, and the real
   production staleness distribution being captured for calibration (section 2C).
6. **Snapshot log-watch + ACL load state** - the two silent-failure classes (section 2C).
7. **Leadership distribution** - only if deployed at N>1 (section 2C).

**Daily error-budget review**: once per day, walk the burn-rate on the three SLO
families - control-plane write availability (99.999 %), write-commit p99 (vs 150 ms), edge staleness
(vs the section 2 boundaries) - and record whether any budget is burning faster than 1x. This is the ritual
that surfaces slow drift the paging thresholds miss.

**Escalation ladder:**
- **WARN** - on-call investigates within the alert's `for:` window; annotate the dashboard; no page.
- **PAGE** - immediate acknowledgement; drive to the runbook named in the rule's `runbook_url`.
- **Rollback trigger (R1-R5)** - page the escalation owner, start `ops/runbooks/release.md`, and treat
  as an incident regardless of time of day.

**Instrumentation debt to close early in the window** (so the last 30-day-clean claim is defensible):
a **snapshot-size-bytes gauge** and a **per-follower matchIndex-lag gauge** - the two signals this
contract can only watch by proxy or by log today (section 2C snapshot row, section 6). File these as the first
burn-in follow-ups; they do not block the ship.

### Exit criteria - when v1 is declared "stable"

The burn-in contract **relaxes** when **all** of the following hold over a **clean, continuous
30-day production window**:

1. **No rollback trigger (R1-R5) fired**, and no unexplained drift in the OOM/leak trio (heap floor,
   RSS, FD all flat within the soak envelope's shape).
2. **The three SLO families held** (write availability 99.999 %, commit p99 inside budget, edge
   staleness inside the section 2 boundaries) with no sustained budget burn.
3. **The election rate stayed churn-free** under production load (the soak/knee distinction held: at
   or below the knee, not into 26-43/20 s churn).

On exit:
- The **heightened thresholds relax to steady-state** (the tightened commit-p99, election, and
  leak-drift warns return to the SLO-native values).
- The **PROPOSED alert values are recalibrated** against the now-measured production SLO distribution
  - **this closes register 7.12** ("threshold VALUES are design-set, not calibrated"). In particular:
  set the FD/thread absolute backstops from the observed **per-instance** floors (not the 3-JVM soak
  totals), and set the edge-staleness thresholds from the captured production distribution rather than
  the S5 reference.
- The **empirical envelope claim upgrades** from "6 h soak + heightened watch" to "30-day production
  window observed" - which is precisely what the 6 h soak could not assert, and is the substance of
  what C4 buys.

Until then, v1 runs **under this contract**.

---

## 5. Cross-links

- **`docs/archive/readiness/v1-go-no-go-2026-07-01.md`** - the v1 readiness review (dated 2026-07-01): section 0
  (the ship context), section 3 (the empirical verdict every threshold is grounded in), section 5.1 (the
  operability caveats). Note: two caveats that review lists have since been **closed** — leadership is now
  auto-balanced (on by default) and the 4 MiB snapshot ceiling is lifted by chunked transfer; the
  alert-thresholds-not-calibrated caveat remains (a burn-in *exit* deliverable).
- **`docs/operations/operator-runsheet.md`** - the secure-by-config release gates (Auth, mTLS, Audit,
  Replay, Signing-key, Strong-reads) an operator verifies before a node is production-ready. This
  contract is the *production* first-30-days posture that runs **after** those gates pass.
- **`docs/operations/deployer-must-know.md`** - the ten deployer MUST-KNOW requirements
  (don't-store-secrets, legacy-SUBSCRIBE segregation, `scope` is not isolation, upgrade-ordering,
  monitor-leadership-distribution, cross-identity policy alignment, no-silent-public-bind, write-admission
  default, shard-aware readiness, key-material core-dump/swap controls). The chunked-snapshot and leadership
  items feed directly into sections 2C and 3 of this contract.
- **Measurement docs (the grounding):**
  - `docs/archive/measurement/ec2-2026-06-30/04-soak.md` - the leak/OOM/GC/FD/heap/commit-p99 baselines.
  - `docs/archive/measurement/ec2-2026-06-30/01-nxknee.md` - the ~800 w/s churn knee + election-churn baseline.
  - `docs/archive/measurement/ec2-2026-06-30/02-dr-drills.md` - durability, 372 ms failover, 0-loss, RTO.
  - `docs/archive/measurement/ec2-horizontal-2026-07-01/02-scaling-curve.md` + `04-verdict.md` - the 2.45x
    curve, per-node CPU, leadership requirement.
  - `docs/archive/measurement/ec2-horizontal-2026-07-01/05-leadership-placement.md` - the leadership-balancing
    operability gap.
- **Alert rules this contract heightens:** `ops/alerts/configd-slo-alerts.yaml` (+ the
  fires/stays-quiet proof `ops/alerts/configd-slo-alerts.test.yaml`).
- **Runbook responses:** `ops/runbooks/` (`release.md`, `resource-leak.md`, `raft-saturation.md`,
  `overload-shedding.md`, `snapshot-install.md`, `acl-policy-load.md`, `control-plane-down.md`,
  `disaster-recovery.md`, `restore-from-snapshot.md`) and `docs/operations/runbooks/` (`leader-stuck.md`,
  `write-freeze.md`, `cert-rotation.md`).
- **`docs/operations/known-limitations.md`** - the "Snapshot transfer: chunked" section (the follower-lag
  and reassembly-refusal signals this contract now watches via the Gate-2 metrics, no longer by proxy).

---

## 6. Metric & threshold provenance (every series verified in code)

Registry names are dot-separated; the Prometheus exporter sanitizes dots to underscores and appends
`_total` to counters. The sanitized name is what the alert expressions query.

| Sanitized series | Type | Defined at (`file:line`) | Notes |
|---|---|---|---|
| `jvm_heap_used_bytes` | gauge | `configd-observability/.../JvmMetrics.java:30` (bound `:45`) | |
| `jvm_heap_max_bytes` | gauge | `JvmMetrics.java:31` (bound `:46`) | |
| `jvm_threads_current` | gauge | `JvmMetrics.java:32` (bound `:50`) | |
| `process_open_fds` | gauge | `JvmMetrics.java:33` (bound `:51`) | |
| `jvm_gc_collection_millis` | gauge | `JvmMetrics.java:34` (bound `:52`) | cumulative GC ms; rate /1000 = GC-s/s |
| `configd_write_commit_seconds_bucket` | histogram | `ConfigdMetrics.java:41`; buckets `:248-263` | le buckets include 0.005/0.010/**0.025**/0.050/.../**0.150** |
| `configd_write_commit_total` / `_failed_total` | counter | `ConfigdMetrics.java:39` / `:40` | availability SLO numerator/denominator |
| `configd_write_rejected_overloaded_total` | counter | `ConfigdMetrics.java:56` | bounded proposal-queue shed -> 429 |
| `configd_raft_elections_total` | counter | `ConfigdMetrics.java:62`; incremented `ConfigdServer.java:1077` | **the churn signal.** Group-0-scoped at N=1 |
| `configd_raft_pending_apply_entries` | gauge | `ConfigdMetrics.java:46` (bound `:140-141`) | leader commit-apply backlog |
| `configd_snapshot_install_failed_total` | counter | `ConfigdMetrics.java:47` | follower-side apply failure - **not** the leader drop |
| `configd_acl_policy_load_failed_total` | counter | `ConfigdMetrics.java:90` | |
| `configd_acl_policy_reload_total` | counter | `ConfigdMetrics.java:91` | advances on each accepted load (freeze detector) |
| `raft_shard_leader_<gid>` | gauge | `ConfigdServer.java:1721` (registered `:613`) | 1 if this node leads gid else 0 |
| `raft_node_leader_count` | gauge | `ConfigdServer.java:1725` | # shards this node leads - the distribution signal |
| `raft_shard_apply_lag_<gid>` | gauge | `ConfigdServer.java:1718` | per-shard commitIndex-lastApplied |
| `edge_staleness_ms` | gauge | `configd-edge-node/.../EdgeNodeMetrics.java:152` | live wall-frontier (ADR-0039) |
| `edge_staleness_state` | gauge | `EdgeNodeMetrics.java:153` | 0=CURRENT 1=STALE 2=DEGRADED 3=DISCONNECTED |
| `configd_edge_staleness_violation_total` | counter | `EdgeNodeMetrics.java:109` | into-STALE+ transitions |
| `edge_staleness_implausible_total` | counter | `configd-edge-cache/.../StalenessTracker.java:73` (`IMPLAUSIBLE_METRIC`); wired via `EdgeNodeMetrics` | future-dated frontier = clock skew |
| `invariant_violation_monotonic_read_total` | counter | `InvariantMonitor.java:43` (prefix) + `:234` (increment); name `:115` | **FLAG:** code prefix is `invariant.violation.`; some javadoc (`:113`) says `configd.invariant.violation.*` - the *code* wins |
| `invariant_violation_staleness_bound_total` | counter | `InvariantMonitor.java:43` + `:234`; name `:122` | INV-S1 |

**Snapshot cap constant (verified):** `MAX_SNAPSHOT_BLOB_LEN = 4 * 1024 * 1024` (4 MiB) -
`configd-server/src/main/java/io/configd/server/RaftMessageCodec.java:88` - is now a **per-chunk** ceiling
under chunked InstallSnapshot (`RaftNode.MAX_SNAPSHOT_CHUNK_BYTES`, default chunk 1 MiB), not a total-state
ceiling. A large snapshot streams as ordered chunks; the follower reassembles in heap under the fail-closed
`configd.raft.maxReassembledSnapshotBytes` cap (default 512 MiB). The old leader-side ">4 MiB total drop"
(stderr `snapshot too large for v1 wire`) **no longer exists** — that string is gone from source. Health is
now metric-backed: `configd_snapshot_bytes`, `raft_shard_snapshot_reassembly_refused_<gid>`,
`raft_shard_snapshot_chunk_send_rejected_<gid>`. Matches `docs/operations/known-limitations.md` §"Snapshot
transfer: chunked".

**New app-emitted series (Gate-2 observability arc — replace the earlier "no series / log-watch only" guidance):**
- **`configd_snapshot_bytes`** (gauge) — snapshot size vs the per-chunk / reassembly caps (the snapshot-bytes
  gauge this contract asked for 3×).
- **`raft_shard_replication_lag_max_<gid>`** (gauge) — the per-follower replication-lag / wedge signal
  (replaces the `matchIndex`-lag-by-proxy guidance).
- **`raft_shard_snapshot_reassembly_refused_<gid>`**, **`raft_shard_snapshot_chunk_send_rejected_<gid>`**,
  **`raft_shard_append_send_rejected_<gid>`** (per-shard counters).
- **`configd_raft_transport_frames_dropped`**, **`configd_raft_transport_inbound_connections_refused`**,
  **`configd_raft_transport_connection_decode_dropped_total`** (transport-drop counters — the encoder-drop
  observability this contract flagged).
- **`configd_http_request_rejected_bad_request_total`** / **`..._payload_too_large_total`** (HTTP admission).

**Signals still with NO app-emitted series (watch via the node/container exporter):**
- **RSS / container working set** - `process_resident_memory_bytes` / cgroup memory. `JvmMetrics` exposes
  heap, not RSS.
- **Per-node CPU** - node/container exporter. Not app-emitted.

---

*Produced 2026-07-01 by the reliability engineer. Docs-only; no production
code modified. Thresholds grounded in the two EC2 measurement runs; metric names verified in the
`configd-observability` SSOT, the edge metrics, and `ConfigdServer`. For operator adoption.*
