# Session 5 — Performance Measurement Methodology (the rules every number obeys)

> **Status:** foundation document. Per charter §14.2 this is established and signed off by
> `review-architect` BEFORE any bulk measurement. Every downstream number in Session 5 cites
> this doc for (a) how it avoids coordinated omission, (b) whether it is LOCAL-VERIFIED or
> ENV-BLOCKED, and (c) the RTT matrix it uses for cross-region modeling.

This document fixes three things so they are decided once and applied uniformly:

1. **The honesty split** — what local hardware can and cannot prove (charter §0 honesty rule).
2. **The cited RTT matrix** — the single source of cross-region latency used by every model.
3. **Coordinated-omission discipline** — how each class of harness avoids the #1 way latency lies.

Plus the reporting/reproducibility rules (HdrHistogram only; committed harness + exact invocation).

---

## 0. The reference box (and why absolute numbers carry a caveat)

All local measurement runs on the audit box unless a result states otherwise:

- **AWS t3a.large, 2 vCPU / 7.7 GB**, burstable. **CPU-credit throttling is real** (RR-094): after
  sustained load, build/compute times inflate ~2.4× (5.5 min → 13 min observed) and timing-sensitive
  work flakes. Heavy Maven/JMH runs are **serialized** on a `flock` mutex; no two compute workloads
  run concurrently.
- **JDK 25 Corretto** (`25.0.x-amzn`) via `./mvnw` (Maven 3.9.9). `--enable-preview` in poms is
  vestigial (0 preview classes). GC collector for the serving JVM is decided **with data** in
  Workstream E's ADR; until then microbenchmarks declare their collector explicitly per run.

**Consequence for honesty:** a 2-vCPU burstable box is **not** production edge/server hardware. Local
absolute latencies are therefore reported as **"reference-hardware" numbers** that prove the
*mechanism and the local component of the budget*, not the production SLO. Where the production
number depends on hardware we do not have (NUMA topology, dedicated cores, 10^9-key working sets,
real WAN), the claim is split LOCAL-VERIFIED + ENV-BLOCKED (§1) and the missing piece is enumerated
in `infrastructure-manifest.md`. Throttling is mitigated per-run by (a) the `flock` serialization,
(b) a warm-up that reaches steady state before measurement, and (c) reporting the JMH error/CI bands
so a throttled run is visible as variance rather than silently biasing the headline.

---

## 1. The honesty split — LOCAL-VERIFIED vs ENV-BLOCKED (charter §0, Hard rules 1 & 7)

> **A latency target whose name contains "cross-region" or "global" CANNOT be marked VERIFIED on a
> single box.** Marking it green on a laptop is the exact dishonesty Session 1 was built to catch.

Every §0.1 target is classified into one of three states, and the classification is fixed here:

| §0.1 target | Local component (VERIFIED here) | Cross-region / scale component (ENV-BLOCKED) |
|---|---|---|
| Read p99 < 1 ms / p999 < 5 ms (in-process) | **Fully local** — in-process read latency at 10^6 keys (HdrHistogram). | 10^9-key working set (won't fit in 7.7 GB) → documented extrapolation, flagged, not measured. |
| Write commit p99 < 150 ms cross-region | Local quorum-commit component (consensus + apply, no network). | + RTT(quorum) from §2 matrix → modeled total; real number needs multi-region hosts. |
| Edge propagation p99 < 500 ms global | Local multi-edge fan-out staleness (Docker Compose), HdrHistogram. | + modeled WAN leg; real number needs cross-region edges. |
| Write throughput 10k/s sustained, 100k/s burst | **Fully local** mechanism (in-process / single-host) — drive it, hold it, report concurrent latency. | Per-region cluster capacity at fleet scale; real saturation needs production-class hosts. |
| Read path: 0 alloc, no locks, no CAS loops | **Fully local** — JMH `-prof gc` (alloc) + async-profiler/`-prof perfnorm` (locks). | none — this is an in-process invariant, fully provable here. |

**Rule:** a result doc states, for each number, exactly one of:
- `LOCAL-VERIFIED` — measured here, the target is in-process / single-host, no caveat beyond §0.
- `LOCAL-VERIFIED (local component) + ENV-BLOCKED (WAN/scale component) → manifest item N` — the split
  form for cross-region / global / 10^9-scale targets. The modeled total is reported as
  `PENDING real-hardware confirmation`, never VERIFIED.
- `MODELED` — no local measurement is possible at all (e.g. tail-amplification across a real tree);
  the model and its inputs are shown, and it is labeled a model.

No fourth state. "Skipped" is not allowed; the absence of local proof is an ENV-BLOCKED manifest item
with exact infra, not a silence.

---

## 2. The cited RTT matrix (canonical — every cross-region model uses THIS table)

The matrix lives in `architecture.md §12` and is reproduced here as the **single canonical source**;
`performance.md §7` carried an identical copy — both now reference this table so the numbers cannot
drift apart. These are **representative median inter-region round-trip times** for AWS regions, the
standard public dataset used for capacity planning.

| Route | RTT (ms) | Used for |
|---|---|---|
| us-east-1 ↔ us-west-2 | 57 | Global Raft quorum |
| us-east-1 ↔ eu-west-1 | 68 | Global Raft quorum |
| us-east-1 ↔ eu-central-1 | 92 | Regional relay |
| us-east-1 ↔ ap-northeast-1 | 148 | Non-voting replica |
| us-east-1 ↔ ap-southeast-1 | 220 | Non-voting replica |
| eu-west-1 ↔ eu-central-1 | 20 | EU regional Raft |
| ap-northeast-1 ↔ ap-southeast-1 | 69 | AP regional Raft |

**Source & status.** Values are representative medians consistent with the public AWS inter-region
latency datasets at **cloudping.co** / **cloudping.info** (continuously-measured AWS region-to-region
RTT) and AWS region documentation. They are a **declared model input**, NOT a measurement taken on
our hardware: the box is single-region single-host. **Real region-pair RTTs (and their p99 tails,
which matter more than the median for a p99 commit target) are ENV-BLOCKED** — see
`infrastructure-manifest.md` (multi-region hosts). The matrix is used uniformly; no cross-region
number in Session 5 invents its own RTT.

**Cross-region commit budget — the computation rule (applied uniformly).** For a write-commit p99
model: `modeled_commit = local_commit_component + RTT(to the follower that completes the majority)`.
For a 5-voter global group led from us-east-1 with followers {us-west-2 57, eu-west-1 68,
ap-northeast-1 148, ap-southeast-1 220}, a majority of 5 is 3 = leader + 2 acks → commit gates on the
**2nd-fastest** follower RTT = **68 ms** (one round trip; AppendEntries is leader→follower→leader).
A flexible-quorum / 3-voter co-located placement gates on the **fastest** follower = 57 ms. The model
reports both placements. The cross-region tail (p99) additionally carries network jitter, which the
median matrix understates — another reason the modeled total is `PENDING real-hardware confirmation`,
not VERIFIED.

**Staleness/propagation budget.** Edge propagation = local fan-out tree latency (measured) + the WAN
leg for distant edges (modeled from this matrix, 1–3 Plumtree hops). Tail amplification across the
fan-out tree is modeled per `architecture.md §12` / `performance.md §8` and labeled MODELED.

---

## 3. Coordinated-omission discipline (charter §3 rule, §5, Hard rule 4)

> Coordinated omission (CO) is the #1 way latency numbers lie: when a measurement loop stalls, the
> requests that *should* have been issued during the stall are never timed, so the histogram omits
> exactly the worst latencies — the ones a user would have seen. Every Session 5 latency harness
> states, in its result doc, which of the mechanisms below it uses.

There are two harness classes, and the correct CO treatment differs:

### 3a. In-process microbenchmarks (Workstream A read path; HLC; HAMT; fan-out CPU cost) — JMH

- Use **`Mode.SampleTime`** (per-invocation latency sampling) for any number reported as a
  percentile, and **`Mode.Throughput`** only for ops/s. **Never `Mode.AverageTime` for a tail claim**
  — an average is not a percentile (this is why the prior `performance.md` `avgt` table is being
  re-run; see §5).
- **Why JMH SampleTime is not subject to CO here:** there is no externally-imposed arrival schedule
  to fall behind. JMH times each individual invocation's *service time* and records it into its own
  HdrHistogram; a stall lengthens the one sample that contains it rather than hiding the samples that
  "should" have happened. The hazard CO describes (a fixed request cadence whose skipped slots vanish)
  does not exist in a tight per-op service-time loop. This is stated explicitly so the claim is "CO is
  structurally absent for this harness," not "CO was ignored."
- Report the JMH **error band / CI** alongside the percentile so throttling on the 2-vCPU box shows up
  as variance. Pin `-f` (forks), `-wi`/`-w` (warmup) past steady state, `-prof gc`, `-prof perfnorm`.

### 3b. Load / service-time harnesses (Workstream B throughput & write-commit; C propagation/staleness; D overload)

These DO have an intended schedule (a target offered rate), so CO is a live hazard and must be
corrected one of two ways — the harness names which:

- **Open-loop, intended-time scheduling.** The generator issues each request at its *scheduled*
  send time `t_i = i / rate`, independent of when prior requests complete. Latency is measured as
  `actual_completion − scheduled_send_time` (not `− actual_send_time`). A stall therefore inflates the
  measured latency of every request whose scheduled slot fell inside the stall — exactly the requests
  CO would otherwise drop. Backlog is bounded; if the generator itself cannot keep up, that is recorded
  as a generator-saturation finding, not hidden.
- **HdrHistogram expected-interval correction.** When a closed-loop driver is unavoidable, record with
  `Histogram.recordValueWithExpectedInterval(latency, expectedInterval)`, where `expectedInterval =
  1/targetRate`. HdrHistogram then synthesizes the omitted samples a stall would have produced.
- **Forbidden for any latency claim:** a naive closed-loop "issue next request only after the previous
  returns, time each in isolation" generator with no expected-interval correction. That is the
  canonical CO lie; it is not used for any reported percentile. (A closed-loop driver MAY be used to
  find the saturation throughput in Workstream B/D, but the *latency-at-rate* distribution is always
  produced by an open-loop or expected-interval-corrected path.)

### 3c. Staleness probe (Workstream C) — a note

Staleness is `wall_now(edge) − commit_ts(last_applied_notification)` (consistency-contract §2,
ADR-0035). It is sampled by the probe at the edge against leader-assigned commit timestamps. CO for
the staleness distribution is avoided by sampling on a **fixed wall-clock schedule at the edge** (the
sampler's own cadence does not pause when the data plane stalls — a stalled propagation shows up as a
growing staleness sample, which is the signal we want), and by driving the *write* side with the §3b
open-loop generator. Clock-offset between hosts is bounded/recorded (single-host Compose ≈ shared
clock; the cross-host term is ENV-BLOCKED).

---

## 4. Reporting & reproducibility rules (charter §3, Hard rules 2/3/5/6)

1. **HdrHistogram distributions only** for latency: report **p50 / p99 / p999 / p9999** (and max).
   No averages as a tail claim.
2. **Every number ships with its harness + the exact invocation**, committed and re-runnable, named in
   the result doc. Raw output is captured under `perf/results/<date-or-commit>/` or
   `docs/session-5/captures/`. "It didn't happen if it isn't reproducible."
3. **Every optimization is a before/after** on the same harness on the same box, delta recorded. No
   "should be faster."
4. **No correctness regression for speed:** after any hot-path change, the relevant safety gate
   (jcstress / sim sweep / linearizability / gate-1..4) must stay green. A faster system that fails a
   safety oracle is a regression, full stop.
5. **Local vs infrastructure-bound is declared per measurement** (§1).
6. **Soak is real-duration or it is labeled a short run** — a 60 s smoke is never called a soak.

---

## 5. Disposition of the prior `performance.md` numbers (the audit rule)

`performance.md` is the original charter's Output 4. Its read-path table is JMH **`avgt`** (averages)
captured on developer hardware with **no committed artifact** for the current commit (its own P-017
banner says so; the `perf/results/jmh-…-PLACEHOLDER` dir is literal). Therefore, per Hard rule 5 and
the charter's "reproduced or marked CONTRADICTED" requirement:

- Each prior claim is **re-run on this box as an HdrHistogram distribution** (SampleTime), and the
  result doc records `REPRODUCED` (within the JMH error band) or `CONTRADICTED` (with the new number).
- An `avgt` number is not "wrong" but is **insufficient** as a tail claim; the re-run supersedes it
  with a percentile distribution. The reconciliation table lives in the Workstream A result doc and is
  folded back into `performance.md`.
- The Quicksilver scorecard's "SURPASSED" verdicts were already withdrawn (Session 0); they are
  re-filled only from committed measured artifacts (§0.3 scorecard), never from the model column.

---

## 6. Sign-off

`review-architect` signs this off (methodology gate) before Workstreams A–E begin bulk measurement.
Any later methodology dispute (e.g. "is this benchmark measuring coordinated omission?") is resolved
by a fresh `opus` sub-agent and logged in the closeout Decision Log (charter §2, §4).

**Sources for the RTT matrix:** [cloudping.co — AWS Latency Monitoring](https://www.cloudping.co/),
[cloudping.info](https://www.cloudping.info/) (continuously-measured AWS inter-region RTT), and AWS
region documentation. Values are representative medians; real region-pair RTT/jitter is ENV-BLOCKED.
