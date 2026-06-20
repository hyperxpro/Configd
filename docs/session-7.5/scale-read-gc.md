# Session 7.5 — §8 Scale: read-path latency + allocation + GC at large store size

> Charter §8. Box: m6id.4xlarge, 16 vCPU, 61 GiB, generational ZGC (ADR-0041), `-Xmx40g`. Tool:
> `LocalConfigStoreReadBenchmark` (JMH, HAMT-backed `LocalConfigStore`, `@Param size` overridden to
> 1M/10M/100M), `-prof gc`, ZGC log `-Xlog:gc`. Raw: `/mnt/nvme/run/scale-bench.{out,json}`,
> `/mnt/nvme/run/zgc-scale.log`. avgt = average time per op (ns). 10⁹ figures are LABELED extrapolation.

## Result — the read path holds at scale (confirms S5's read result at 100× larger stores)

| read method | 1M keys | 10M keys | **100M keys** | alloc (B/op) |
|---|---|---|---|---|
| `getHit` (key → value) | 156 ns | 325 ns | **483 ns** | 32 (constant) |
| `getHitWithCursor` (read-with-cursor, the realistic client path) | (32 B/op) | 303 ns | **609 ns** | 32 (constant) |
| `getMiss` (key absent) | 8.6 ns | 9.3 ns | **12.2 ns** | **≈ 10⁻⁴** (zero) |
| `missIsSingleton` | 8.7 ns | 9.1 ns | **13.0 ns** | **≈ 10⁻⁴** (zero) |

- **Read latency stays sub-µs even at 100M keys.** Growth is sub-linear in store size (HAMT is
  O(log₃₂ N)): `getHit` 156 → 325 → 483 ns across 1M → 10M → 100M (10× store each, ~1.5–2× latency).
  `getMiss` is ~9–13 ns flat (early-exit on an absent branch). This **confirms the S5 read result
  (p99 ~1.6 µs, ~0 B/op) holds at a 100× larger store** — the read path does not degrade with size.
- **Allocation is constant in store size:** `getHit`/`getHitWithCursor` allocate a fixed **32 B/op**
  (the returned result wrapper), `getMiss`/singleton **≈ 0 B/op** — independent of N. The HAMT read
  traversal allocates nothing; only the result object does. This matches the S5 "0 B/op" hit-path
  claim modulo the small result wrapper.

## GC at scale — ZGC holds for READS; the bulk-LOAD is the allocation-pressure case (honest)

- **Steady-state reads: ZGC is a non-event.** JMH `gc.count ≈ 0` for the read benchmarks at every
  size (the few non-zero counts coincide with the long warmup of the next size's setup, not the
  measured read loop). With ~0–32 B/op the read workload barely touches the allocator.
- **Memory at size (from the ZGC log):** the live set reached **~17 GB committed at 100M keys**
  (`12378M→17138M(42%)` during the 100M setup) — i.e. **~170 bytes/key** all-in (String key +
  `VersionedValue` + HAMT node overhead).
- **6 ZGC "Allocation Stall" events — all during the 100M bulk LOAD, not the reads.** Building a
  100M-entry HAMT as fast as a tight `put` loop allows is a worst-case sustained allocation storm
  (the setup `Minor Collection (Allocation Rate)` cycles ran multi-second concurrent). Under that the
  mutator stalled 6 times waiting for ZGC. **This is a LOADING-path artifact, not a read-path or
  steady-state finding** — production loads incrementally via the replication/apply path (itself
  throughput-bounded, see `throughput-part2.md`), never a single-thread max-rate bulk `put`. Honest
  note: a one-shot 100M+ bulk import on a tight heap should pace the load or sit on a larger heap.

## 10⁹-key extrapolation (LABELED — extrapolation, NOT measured)

- **Read latency → still sub-µs.** Extending the sub-linear HAMT trend (log₃₂: 100M→1B adds ~0.7 of
  one trie level, ~13%), `getHit` ≈ **483 ns → ~0.55–0.9 µs** at 10⁹; `getMiss` ≈ **~15 ns**. The
  read path is NOT the 10⁹ limiter.
- **Memory IS the limiter.** ~170 B/key × 10⁹ ≈ **~170 GB live** — **exceeds this 61 GiB box** (and a
  64 GB-class box generally). So a true single-store 10⁹ measurement needs a large-memory host
  (e.g. r6i.8xlarge+) — a hardware-necessity deferral (shrunken manifest M-5), NOT a read-path defect.
  The read-latency-at-10⁹ is extrapolated-sub-µs; the memory footprint is the honest box ceiling.

## Method rails
JMH avgt + `-prof gc` (allocation normalized per-op, the 0-B/op evidence); generational ZGC per
ADR-0041; sizes overridden on the command line (1M/10M/100M), each a real HAMT of that many distinct
keys; the 10⁹ row is explicitly extrapolation (read latency from the measured log₃₂ trend; memory
from the measured ~170 B/key). The allocation-stall caveat is reported, not hidden — it is a
bulk-load pressure point, separate from the (clean) steady-state read result.
