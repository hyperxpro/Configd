# F7 — Tier-1-PERF-DOC-HONESTY — Fix Report

**Subagent:** F7 (iter-1)
**Date:** 2026-04-19
**Repo SHA at fix time:** `22d2bf3071271334998df7a721e533c187d1dc17`
**Closes:** P-001 (§8.1 hard-rule violation), DOC-016 (cross-region modeled-vs-measured disclaimer).

---

## Task 1 — HdrHistogram audit of `docs/performance.md`

Searched for: `HdrHistogram`, `high.dynamic.range`, `HDR.precision`, `HDR-precision` (case-insensitive).

| Line | Verbatim hit |
|---|---|
| 75 | `- **HdrHistogram** for all latency measurements (percentile-accurate, no averaging)` |

**Total occurrences:** 1. No synonyms (`high-dynamic-range`, `HDR-precision`, etc.) appeared anywhere else in the file. The doc only had one false mechanism claim — the rest of §3 talks about JMH which is true.

The actual implementation (`configd-observability/src/main/java/io/configd/observability/MetricsRegistry.java`, `DefaultHistogram` inner class, line 42 `DEFAULT_HISTOGRAM_CAPACITY = 4096`) is a fixed 4096-slot ring buffer with O(n log n) percentile via `Arrays.sort` on the snapshot — definitively NOT HdrHistogram.

---

## Task 2 — Strike / replace HdrHistogram claim

### Edit at line 75 of `docs/performance.md`

**BEFORE:**
```
### Measurement Tools
- **HdrHistogram** for all latency measurements (percentile-accurate, no averaging)
- JMH with `-prof perfasm` for hot loop analysis
- Coordinated omission correction enabled
```

**AFTER:**
```
### Measurement Tools
- **Latency is recorded into a fixed 4096-slot ring-buffer histogram** (see `configd-observability/src/main/java/io/configd/observability/MetricsRegistry.java` — `DefaultHistogram`); reported percentiles are computed from this approximation, **NOT from a true high-dynamic-range (HdrHistogram) histogram**. Buckets must be aligned to recorded units (see ADR-0026 / `PA-5018`). The ring buffer overwrites old samples once `count > 4096`, so reported percentiles reflect a sliding window of the most recent samples — not the whole observation period.
- JMH with `-prof perfasm` for hot loop analysis
- Coordinated omission: **NOT** corrected by the in-process histogram; rely on JMH `Mode.SampleTime` for tail-accurate measurements where it matters.
```

Measured percentile numbers in §3 (the "JMH Measured (avg)" column and the per-benchmark JMH score blocks) were **preserved unchanged** — only the mechanism claim was struck.

---

## Task 3 — Cross-region modeled-vs-measured disclaimer (DOC-016)

Searched: `cross-region`, `cross-DC`, `RTT`, `geographic`, `inter-region`. Hits at lines 98, 168, 179–180, 251, 255, 267, 275, 303, 352. Reviewed each — every cross-region/cross-DC predicted number is *modeled* (no end-to-end multi-region cluster artifact exists under `perf/results/`; the only result file is `perf/results/smoke/result.txt`, a 60s YELLOW soak with `status=YELLOW (no workload wired; duration honoured)`). Therefore every cross-region predicted row was tagged `MODELED, NOT MEASURED` per the conservative rule in the prompt.

### Edit set

**Line 97–98 (Targets table):**

BEFORE:
```
| Control plane write (intra-region) | < 2ms | < 5ms | *network-bound, not benchmarkable in-process* | BY DESIGN |
| Control plane write (cross-region) | < 70ms | < 150ms | *network-bound, 68ms RTT minimum* | BY DESIGN |
```

AFTER:
```
| Control plane write (intra-region) | < 2ms | < 5ms | *network-bound, not benchmarkable in-process — **MODELED, NOT MEASURED*** | BY DESIGN |
| Control plane write (cross-region) | < 70ms | < 150ms | *network-bound, 68ms RTT minimum — **MODELED, NOT MEASURED** (no measured-on-real-cross-region-cluster artifact under `perf/results/`)* | BY DESIGN |
```

**Line 168 (Analysis bullet 3):**

BEFORE: `3. **Propagation < 500ms p99:** Plumtree broadcast to 500 peers = 7.25μs CPU time. Network RTT is the bottleneck, not CPU. With 2-3 hops × 100ms inter-region RTT = 200-300ms. **TARGET ACHIEVABLE.**`

AFTER: `3. **Propagation < 500ms p99:** Plumtree broadcast to 500 peers = 7.25μs CPU time (measured in-process). Network RTT is the bottleneck, not CPU. **MODELED, NOT MEASURED:** 2-3 hops × 100ms inter-region RTT = 200-300ms wall-clock. **TARGET ACHIEVABLE (modeled).**`

(The 7.25μs CPU number is preserved as in-process measured.)

**Lines 178–181 (Basis for Write Path Estimates):**

BEFORE:
```
### Basis for Write Path Estimates
- Intra-region Raft commit: network RTT (2-5ms) + apply (< 1ms) = 3-6ms p50
- Cross-region: us-east-1 ↔ eu-west-1 = 68ms RTT + processing = ~78ms p50
- p99 includes: batch wait (200μs), queuing delay, network jitter (10-20ms)
```

AFTER:
```
### Basis for Write Path Estimates

**MODELED, NOT MEASURED:** The numbers below are analytical estimates derived from published cloud-provider RTT tables, not measurements taken on a real multi-region cluster. No artifact exists under `perf/results/` for cross-region write commit latency. Treat as a budget, not a result.

- Intra-region Raft commit: network RTT (2-5ms) + apply (< 1ms) = 3-6ms p50 — **MODELED**
- Cross-region: us-east-1 ↔ eu-west-1 = 68ms RTT + processing = ~78ms p50 — **MODELED**
- p99 includes: batch wait (200μs), queuing delay, network jitter (10-20ms) — **MODELED**
```

**Lines 251–253 (Section 7 header):**

Inserted a section-level callout immediately after `## 7. Cross-Region RTT Impact`:

```
> **MODELED, NOT MEASURED:** All numbers in this section are derived from published third-party RTT tables (AWS CloudPing + Azure measurements), **not** from a Configd cluster running in these regions. No cross-region cluster artifact exists under `perf/results/`. Treat as sizing budgets, not verified results.
```

**Lines 265–276 (Global / Regional Raft Commit Budget):**

BEFORE: each bullet was an unqualified prediction (e.g., `**68ms**`, `**~78ms p50**`, `**Target met:**`).

AFTER: each section is prefixed with `**MODELED, NOT MEASURED.**` and each bullet's status is qualified with `— modeled` and the closer reads `**Target met (modeled):** ... — *no measured artifact*`.

**Lines 352–357 (Surpass-Quicksilver Scorecard):**

BEFORE:
```
| **Write commit latency (p99, cross-region)** | ~500ms (batched) | < 150ms | ~100ms (68ms RTT + 32ms overhead) | ✅ SURPASSED |
| **Edge staleness (p99 propagation)** | ~2.3s (unverified) | < 500ms global | ~300-400ms (2-3 hop Plumtree) | ✅ SURPASSED |
| **Write throughput (sustained)** | ~350 writes/sec | 10K/s base, 100K/s burst | 10K+/s per group × N groups | ✅ SURPASSED |
| **Operational complexity** | External Raft + Salt + replication tree | Zero external coordination | Embedded Raft, single artifact | ✅ SURPASSED |

**All four axes surpass baseline.** Scorecard requirement (≥3 of 4 with no regression) met.
```

AFTER:
```
| **Write commit latency (p99, cross-region)** | ~500ms (batched) | < 150ms | **MODELED, NOT MEASURED:** ~100ms (68ms RTT + 32ms overhead) | MODELED ONLY |
| **Edge staleness (p99 propagation)** | ~2.3s (unverified) | < 500ms global | **MODELED, NOT MEASURED:** ~300-400ms (2-3 hop Plumtree) | MODELED ONLY |
| **Write throughput (sustained)** | ~350 writes/sec | 10K/s base, 100K/s burst | 10K+/s per group × N groups (in-process JMH; cluster-level not measured) | PARTIAL |
| **Operational complexity** | External Raft + Salt + replication tree | Zero external coordination | Embedded Raft, single artifact | ARCHITECTURAL |

**Honesty note:** The first three rows are *modeled* against the published Quicksilver baseline using component-level JMH numbers + RTT tables; no end-to-end cross-region cluster artifact exists under `perf/results/`. Per §8.1 of the loop directive, these rows must NOT be cited as authoritative until a real multi-region run lands. The fourth row is architectural and verifiable by inspection.
```

(The "all four surpass" assertion was demoted because three of four are modeled; row-1 status `BY DESIGN` was kept on the network-bound rows where the prediction is dimensional and not a benchmark claim.)

### Disclaimer audit summary

| Line(s) | Original status | Final tag | Reasoning |
|---|---|---|---|
| 98 (intra-region row) | unqualified | `MODELED, NOT MEASURED` | Conservative — no real-cluster artifact |
| 98 (cross-region row) | unqualified | `MODELED, NOT MEASURED` | Conservative — no real-cluster artifact |
| 168 (analysis bullet 3) | partial — only the 7.25μs piece is measured | mixed: in-process **measured** + cross-region **MODELED** | Split honestly |
| 179–181 (basis for write estimates) | unqualified | `MODELED, NOT MEASURED` block | All three lines are derivations, not measurements |
| 251–276 (Section 7) | section heading mentioned "Modeled" but per-row predictions read as facts | section banner + per-bullet `— modeled` tags | Made explicit at the row level |
| 303 (lognormal model footnote) | already self-labeled `*Based on modeled latency distribution*` | left as-is | Already honest |
| 352 (scorecard cross-region) | `✅ SURPASSED` | `MODELED ONLY` | Cannot claim surpassed without a measurement |
| 353 (scorecard edge staleness) | `✅ SURPASSED` | `MODELED ONLY` | Same |
| 354 (scorecard throughput) | `✅ SURPASSED` | `PARTIAL` | In-process JMH is real, cluster-level isn't |
| 355 (scorecard ops complexity) | `✅ SURPASSED` | `ARCHITECTURAL` | Inspectable, not benchmarked |

No measured cross-region artifact was found, so no row received a `**MEASURED 2026-04-NN on <env>**` tag. The only `perf/results/` content prior to this fix was `perf/results/smoke/result.txt` (60s soak harness, status YELLOW, no workload wired). It is not relevant to cross-region claims and was not cited.

---

## Task 4 — JMH artifact directory

Created `perf/results/jmh-2026-04-19T00Z-PLACEHOLDER/README.md` containing the placeholder text from the prompt with `<SHA>` substituted to `22d2bf3071271334998df7a721e533c187d1dc17`. No prior `perf/results/jmh-*` directory existed; no JMH `.json` or `.txt` log files existed anywhere in the repo. The placeholder is the honest fallback.

Path: `/home/ubuntu/Programming/Configd/perf/results/jmh-2026-04-19T00Z-PLACEHOLDER/README.md`

---

## Task 5 — Local JMH sanity run

**Skipped** per the prompt's escape clause. Rationale:

- The benchmark harness (`HistogramBenchmark.java` plus the `benchmarks.jar` shade-plugin pipeline in `configd-testkit/pom.xml`) requires a full `mvn package` of the testkit module and its transitive deps (configd-common, consensus-core, config-store, edge-cache, distribution-service, transport, observability) to produce the executable uber-jar.
- A clean build + JMH run with the existing `@Warmup(3i × 2s)` + `@Measurement(5i × 2s)` × 3 benchmarks × 1 fork cannot honestly be claimed to fit the <60s budget, especially with annotation-processor regen for JMH and the `--enable-preview` Java 25 toolchain bring-up.
- Per §8 of the loop directive, fabricating a JMH log would be worse than no log. The placeholder from Task 4 is the honest fallback; deferring the real run to a CI invocation that can pin the SHA is the correct trade.

No `HistogramBenchmark.log` was committed.

---

## Files changed

| Path | Change |
|---|---|
| `/home/ubuntu/Programming/Configd/docs/performance.md` | HdrHistogram claim struck; cross-region predictions tagged MODELED; scorecard re-graded |
| `/home/ubuntu/Programming/Configd/perf/results/jmh-2026-04-19T00Z-PLACEHOLDER/README.md` | New — honest placeholder per Task 4 |
| `/home/ubuntu/Programming/Configd/docs/review/iter-001/fixers/F7-report.md` | This report |

## Closure status

- **P-001 (§8.1 violation):** CLOSED. The unique HdrHistogram occurrence is gone; mechanism is now described as a 4096-slot ring buffer with the correct caveats. No measured percentile number was changed.
- **DOC-016 (cross-region disclaimer):** CLOSED. Every cross-region predicted number now carries a `MODELED, NOT MEASURED` tag and the section/scorecard banners explicitly call out the absence of a real-cluster artifact.
- **JMH artifact gap:** PARTIALLY CLOSED via honest placeholder. Real artifact pending a CI run pinned to SHA `22d2bf3071271334998df7a721e533c187d1dc17` or its successor.
