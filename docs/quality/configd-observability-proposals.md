# configd-observability — §2 NO-TOUCH quality proposals (REVIEW-ONLY, NOT APPLIED)

This module is pinned by an anti-"blind-dashboard" gate and a metrics-contract test:
no emitted metric series (name/labels/help), no SLO/alert/threshold number, and no
Prometheus exposition byte may change. Per the shared quality-pass brief, the files below
are **§2 NO-TOUCH** — they carry MEASURED/PROVEN/byte-pinned properties, so they are
**reviewed and proposed only, never edited** in this pass. The bright line is drawn at the
*file* level on purpose: a divergence-analyst diffs these files expecting ZERO changes, so
even a provably-safe edit (e.g. an unused-import removal) is deferred to keep that diff empty.

Nothing in this document was applied. The only applied change in this pass is the
`SafeLog.redact` internal hex-encoding nit in an EDITABLE file (see the agent report).

Each item notes WHY it looks safe and WHY it was nonetheless deferred.

---

## 1. MetricsRegistry.java — remove unused import `java.util.stream.Collectors`
- **Location:** line 11 (`import java.util.stream.Collectors;`).
- **Finding:** `Collectors` is never referenced in the file (`snapshot()` builds a
  `java.util.LinkedHashMap` directly and uses `forEach`; verified by grep). It is an unused import.
- **Why safe-looking:** imports are compile-time only; removing an unused one produces
  identical bytecode and cannot affect registry semantics or any emitted series.
- **Why deferred:** registry-semantics SSOT and a §2 file. Propose-only per the file-level bright line.
- **Secondary (stylistic, do not act on alone):** line 207 uses the FQN
  `new java.util.LinkedHashMap<>()` while no `LinkedHashMap` import exists; if the unused
  `Collectors` import were ever swapped, a `LinkedHashMap` import would read more symmetrically.
  Pure style — no behavior impact.

## 2. PrometheusExporter.java — remove unused import `java.util.LinkedHashMap`
- **Location:** line 4 (`import java.util.LinkedHashMap;`).
- **Finding:** `LinkedHashMap` is never referenced in the file (verified by grep). Unused import.
- **Why safe-looking:** compile-time only; the exposition `export()` output bytes are produced by
  `StringBuilder` and are wholly independent of this import.
- **Why deferred:** this is THE exposition-format file (scrape-contract bytes). §2 bright line ⇒ propose-only.
- **Secondary (readability, not a bug):** `BucketSchedule.of` (lines 59-68) builds the label list and
  cutoff array in two passes over `keySet()` then `values()`. A single `entrySet()` pass would be
  clearer. It is behavior-identical for the order-preserving maps callers actually pass
  (`ConfigdMetrics` passes `LinkedHashMap`), but because correctness here relies on
  `keySet()`/`values()` iteration-order correspondence, leave it untouched in a byte-identity pass.

## 3. SloTracker.java — remove unused import `java.util.Collections`
- **Location:** line 4 (`import java.util.Collections;`).
- **Finding:** `Collectors` IS used (line 142, `toUnmodifiableMap`) but `Collections.` is never
  referenced (verified by grep). The `Collections` import is unused.
- **Why safe-looking:** compile-time only; no SLO numbers, windows, or compliance math are touched.
- **Why deferred:** SLO/compliance computation file. §2 ⇒ propose-only.
- **Secondary (DRY refactor, deferred):** `compliance()` (lines 105-120) and `statusFor()`
  (lines 212-227) duplicate the same window-scan loop (`total`/`failures` accumulation under the
  cutoff). Extracting a private helper would de-duplicate them, but it edits the compliance math on
  a frozen-number file, so it is a refactor proposal — NOT a safe byte-identity nit.

## 4. InvariantMonitor.java — remove unused import `java.util.Collections`
- **Location:** line 3 (`import java.util.Collections;`).
- **Finding:** `Collectors` IS used (line 223) but `Collections.` is never referenced
  (verified by grep). The `Collections` import is unused.
- **Why safe-looking:** compile-time only; the violation-counter names (`invariant.violation.*`)
  and the SEVERE-log fail-open behavior are untouched.
- **Why deferred:** liveness/invariant monitor that fires on real conditions. §2 ⇒ propose-only.

## 5. BurnRateAlertEvaluator.java — replace wildcard `import java.util.*`
- **Location:** line 3 (`import java.util.*;`).
- **Finding:** the only `java.util` types used are `ArrayList`, `List`, `Map`, `Objects`. A wildcard
  import is inconsistent with the rest of the module (every other file uses explicit imports) and is
  discouraged by common Java style guides.
- **Why safe-looking:** compile-time only; the burn-rate thresholds (14.4, 1.0) and the
  fast/slow-burn classification are untouched.
- **Why deferred:** frozen burn-rate-threshold file. §2 ⇒ propose-only.
- **Secondary (design observation, NOT a safe nit):** `sinks` (line 22) is a plain `ArrayList`
  iterated in `evaluate()` (line 63) while `addSink` (line 28) can mutate it. If sinks were ever
  registered concurrently with an `evaluate()` sweep there is a `ConcurrentModificationException` /
  visibility risk. A `CopyOnWriteArrayList` (or documenting single-threaded setup) would close it —
  but that CHANGES allocation/concurrency behavior, so it is a design decision for the owner, not a
  behavior-preserving quality nit. Flagged for awareness only.

## 6. ConfigdMetrics.java — orphaned/duplicate Javadoc block (cosmetic)
- **Location:** lines 231-241 — two consecutive `/** ... */` blocks sit directly above
  `edgeProcessHistogramSchedules()`. Only the second (lines 235-241) is the method's Javadoc; the
  first (lines 231-234, "Latency schedule for write-commit / apply paths ...") is a dangling comment
  that actually describes `latencySecondsSchedule()` further down (line 248), which has no Javadoc.
- **Why safe-looking:** comments only — zero bytecode/exposition impact. The fix is to move the
  stray block onto `latencySecondsSchedule()` (or delete it).
- **Why deferred:** ConfigdMetrics is the metric-series SSOT (§2). Cosmetic, propose-only.

---

### Files reviewed with no proposals
- **ProductionSloDefinitions.java** — clean; all SLO targets/windows frozen and correctly expressed.
- **PropagationLivenessMonitor.java** — clean; no unused imports; `propagation.lag.violation`
  series frozen.

### Note on the EDITABLE files
- **JvmMetrics.java** — already idiomatic; no behavior-preserving change was worth a diff. The
  `sumGc` enhanced-for over `ManagementFactory.getGarbageCollectorMXBeans()` was deliberately left
  as-is: the list's `RandomAccess`-ness is an unguaranteed JDK impl detail, the list is tiny, the
  loop runs at scrape time (cold/warm, not a measured hot path), and the enhanced-for IS the idiom —
  converting it would be a non-obvious micro-opt, not a clear win.
- **SafeLog.java** — one nit applied (see agent report): `redact()` per-byte
  `String.format("%02x", ...)` (allocates a `Formatter`, boxes the byte, builds a transient string
  each of 8 iterations) replaced with a `HEX_DIGITS[]` lookup. Proven byte-identical over all 256
  byte values + 2,000,000 random 8-byte SHA-256 prefixes. `redact` output is a log fingerprint, not
  a metric series/label/wire byte.
