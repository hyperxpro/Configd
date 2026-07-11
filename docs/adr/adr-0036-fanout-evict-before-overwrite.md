# ADR-0036: Amendment to ADR-0034 section 5 - eviction must advance `tail` before the in-place overwrite

- **Status:** Accepted (independently verified).
- **Date:** 2026-06-11
- **Amends:** ADR-0034 (commit-notification boundary). ADR-0034 is treated as immutable; this ADR records the correction as a standalone amendment. Everything else in ADR-0034 stands.

## Context

ADR-0034 section 5 specified the verify-after-read protocol for `FanOutBuffer.readSince`
and argued its soundness from the appender's publish order
`ring.set -> head++ -> tail = head - capacity`. Clause 2 of that argument -

> "publish order is `ring.set -> head++ -> tail=`, so `head` can be observed ahead of the
> matching `tail` advance"

- treated the overwrite-before-tail-advance order as benign (a transient GAP source).
It is not benign. The first CI run on this branch (run 27364178571,
4-vCPU runner) failed `FanOutBufferRaceTest.concurrentReaderNeverSeesDuplicateOrSkippedSeqAndEventuallySeesAll`
in two independent jobs: a non-GAP run contained `158890` followed by `158870`.

**The hole.** The reader's final `t2 == t1` tail re-check assumed: *if a reader observed
an in-place overwrite, it will observe the tail advance.* With the overwrite ordered
**before** the tail advance, the JMM guarantees only the converse. Interleaving:

1. Reader reads `t1 = T`, `h`, starts copying `[T, h)`.
2. Writer (full ring) executes `ring.set(slot, n_new)` - the overwrite - but has not
   yet executed `tail = head - capacity`.
3. Reader reads the overwritten slot: a lapped, newer notification at an old position.
4. Reader finishes the copy and reads `t2 = tail = T`. Check passes. The run is torn.

The window is two instructions wide on the writer; it is essentially unreachable on the
2-vCPU dev box and probabilistic on 4-vCPU hardware - which is exactly how it escaped
local verification and was caught by CI. jcstress
(`FanOutBufferReadSinceTest.ExactlyFullWrap`) detects it deterministically
in `quick` mode (14 forbidden-state observations on the dev box); the initial CI gate had only run the
curated subset in `sanity` mode.

## Decision

The evicting publish order becomes **watermark -> tail advance -> overwrite -> head advance**:

```
if (head - tail >= capacity) {
    lastEvictedSeq.set(ring.get(slot).seq());  // 1. watermark (before tail, unchanged)
    droppedTotal++; metrics.onDropped();
    tail = tail + 1;                           // 2. retire the position BEFORE clobbering it
}
ring.set(slot, notification);                  // 3. the in-place overwrite
head = head + 1;                               // 4. publish the new position
```

**Soundness.** All four stores are volatile. `W_tail` precedes `W_ring` in the writer's
program order, hence in the synchronization order. If a reader's copy included an
overwritten slot value (`R_slot` sees `W_ring`), then `R_slot` is ordered after `W_ring`,
hence after `W_tail`; the reader's final `R_t2` follows `R_slot` in its program order and
therefore returns `tail > t1` -> GAP. A reader that instead read the slot's **old** value
attributes it to the correct position (the entry genuinely stored there); if that position
was concurrently retired, `t2 != t1` again forces GAP. Either way **no non-GAP run can
contain a lapped entry** - the property ADR-0034 section 5 claimed, now actually delivered.

Consequences of the new order:
- `head - tail <= capacity` is now a writer-side invariant (transiently `capacity - 1`
  during an evicting publish). The reader's `h - t1 > capacity` check is retained: `t1`
  can be stale relative to a later `h` read. The "negative/risk" note in ADR-0034
  (`readSince` GAPs during the one-append eviction window where `head > tail + capacity`)
  is obsolete - that exposure no longer exists; transient GAPs now arise only from a tail
  advance occurring during a reader's copy window.
- The watermark-before-tail order is unchanged, so the fast-path GAP argument of
  ADR-0034 section 5 is unaffected.
- Append path: still single-writer, lock-free, allocation-free. One extra volatile store
  per *evicting* publish only (the tail store moved, it was not added).
- The buffer transiently holds `capacity - 1` readable entries during an evicting
  publish (the retired slot is excluded before its replacement is visible) - observable
  only as a 1-entry-smaller `Ok` run, never as a gap in seq.

## Rejected alternatives

- **Reader-side seq validation (check ascending/contiguous, retry on anomaly).** Treats
  the symptom; the copied window could still mix positions from different laps, and
  "looks ascending" does not prove "is the retained run". The writer-side order fix makes
  the existing proof shape airtight instead.
- **Version/stamp per slot (seqlock-style).** Correct but adds a second array + two more
  volatile ops per publish for no additional guarantee over the order fix.
- **Lock the eviction path.** Violates the no-locks hard rule on paths a reader can spin
  against, and is unnecessary.

## Verification

- Pre-fix: CI run 27364178571 (two independent job failures) + jcstress `quick`:
  `ExactlyFullWrap` forbidden state 9 observed in 14 JVM configurations.
- Post-fix: same jcstress invocation clean (2/2 results, 0 forbidden);
  `FanOutBufferRaceTest` 8/8 stress-loop runs green; full
  `configd-distribution-service` suite (156) green; CI re-run green.
- The CI gate adds the FanOutBuffer eviction jcstress variants in `quick` mode (the prior
  `sanity` smoke demonstrably cannot catch this class).
