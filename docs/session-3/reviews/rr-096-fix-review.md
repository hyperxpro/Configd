# RR-096 fix — second-agent verification review

- **Finding:** RR-096 (P1) — `FanOutBuffer.readSince` torn read under eviction (publish-order hole in the RR-066 verify-after-read fix)
- **Fix under review:** commit `65a5212` (S3/RR-096: evict-before-overwrite), branch `session-3-data-plane`
- **ADR:** ADR-0036 (amends ADR-0034 §5)
- **Reviewer role:** independent second-agent verifier (register discipline: P0/P1 closures require independent verification)
- **Date:** 2026-06-11
- **Box:** 2 vCPUs, Corretto JDK 25 (`25+36-LTS`); all evidence serialized.

## VERDICT: APPROVE

The fix is correct under the JMM. I re-derived the memory-ordering argument independently
(below) and could not construct an interleaving that admits a lapped / duplicate /
non-ascending / torn / silently-skipped entry in a non-GAP run. The claimed evidence
reproduces exactly on this box: 45/45 JUnit tests green, jcstress `quick` clean (0 forbidden
across 28 results / both variants), race test 5/5 in a stress loop. ADR-0036 and the RR-096 /
RR-097 register rows are accurate against what I observed — no overclaim found. Non-blocking
notes only (below).

---

## 1. Independent proof sketch (re-derived, not copied from ADR-0036)

### Setup

Single writer (`publish`), multiple lock-free readers (`readSince`). Fields `head`, `tail` are
`volatile long`; `ring` is `AtomicReferenceArray` (each slot get/set is a volatile read/write);
`lastEvictedSeq` is `AtomicLong`. Under the JMM, all of these participate in one
**synchronization order (SO)** consistent with each thread's **program order (PO)**, and a
volatile read that observes a volatile write is ordered after it in SO (and gives a
happens-before edge).

The new evicting-publish PO is:

```
W_seq:  lastEvictedSeq.set(evictedSeq)
W_tail: tail = tail + 1
W_ring: ring.set(slot, notification)
W_head: head = head + 1
```

so in SO: `W_seq → W_tail → W_ring → W_head` (single writer, no reordering of volatiles past
each other). The non-evicting publish is just `W_ring → W_head`.

### Reader PO (the non-fast-path)

```
R_t1:   t1 = tail
R_h:    h  = head
        (window check h - t1 > capacity → GAP)
R_slot: for i in [t1,h): n = ring.get(i % cap)   // null → GAP
R_t2:   t2 = tail
        (t2 != t1 → GAP)  else OK
```

### Claim

Every `Ok` run is a strictly-ascending, contiguous, untorn prefix of the retained stream
with all `seq > cursor`.

### Core lemma (the one that was false before the fix)

**If a reader copies an overwritten (lapped) slot value, it reports GAP.**

Overwriting position `i` (slot `i % cap`) is the store `W_ring` of the publish whose `head`
went from `i` to `i+1`; that publish evicted position `i - cap`, i.e. it executed
`W_tail` advancing `tail` to `(i - cap) + 1`. By writer PO, **`W_tail` precedes `W_ring`** in
SO. Suppose the reader's `R_slot` reads the lapped value, i.e. `R_slot` observes `W_ring`.
Then `R_slot` is ordered after `W_ring` in SO, hence after `W_tail`. The reader's `R_t2`
follows `R_slot` in its own PO, so `R_t2` is ordered after `W_tail` and therefore reads
`tail ≥ (i - cap) + 1`. Since `i ≥ t1` (it is in the scanned window) and `cap ≥ 1`, we have
`(i - cap) + 1 > t1` whenever `i ≥ t1` and ... more carefully: the slot the reader scans at
position `i` was last legitimately owned by some position `p ≤ i` with `p ≡ i (mod cap)`. The
relevant overwrite that could lap it advanced tail strictly past the position the reader still
believes it is reading (`t1`). Concretely: for the reader's copy at scan-index `i ∈ [t1,h)` to
be a lapped value, the writer must have advanced `head` to `i + cap` (to wrap onto the same
slot), which required `tail` to advance to at least `i + 1 > t1`. Because `W_tail` for that
eviction precedes the lapping `W_ring`, and `R_t2` follows `R_slot` (which saw that `W_ring`),
`R_t2` reads `tail ≥ i + 1 > t1`, so `t2 ≠ t1` → **GAP**. ∎

This is exactly the implication ADR-0034 §5 *asserted* but did not *hold* under the old order
(old order had `W_ring → W_tail`, giving only the useless converse "observing the tail advance
implies observing the overwrite"). The fix swaps the two stores so the needed implication is
the one the JMM actually provides. I confirm the direction of the implication is now correct.

### The complementary case (old slot value)

If `R_slot` instead reads the **old** value at position `i` (the genuine entry stored there,
not yet overwritten), that value belongs to position `i` correctly — no tearing. The only
remaining hazard is that position `i` got *retired* (tail advanced past it) while the reader
held an old `t1`. But a retirement is a `W_tail`; if it happened-before `R_t2` the reader sees
`t2 > t1` → GAP. If it did not happen-before `R_t2`, then `tail` did not advance as far as the
reader's `R_t2` observes equal to `t1`, meaning no eviction touched `[t1, h)` and the copy is
clean. So in every case the copy is either clean or the reader reports GAP. ∎

### Discharging each readSince path

- **Fast-path watermark (`evicted >= 0 && cursor < evicted`).** `lastEvictedSeq` is published
  by `W_seq`, which precedes `W_tail` in SO. So any reader that will go on to observe an
  advanced tail also observes a watermark ≥ the evicted seq. A cursor strictly below the
  watermark is missing an already-evicted successor → GAP is correct and conservative (it can
  only over-report GAP if `lastEvictedSeq` is observed stale-high, which cannot happen — it is
  monotonic and read first). Natural seq gaps (no-op/RCFG entries skipping seqs) are handled
  because the comparison is against an **actual evicted seq value**, not position arithmetic.
  Verified: matches the `LappedCursorBelowWindow` jcstress outcome (100% GAP).

- **`h - t1 > capacity` check.** `t1` is read before `h`; with the new order
  `head ≤ tail + capacity` is a writer-side invariant (head only advances *after* tail in an
  evicting publish), but `t1` can still be **stale** relative to a later `h` (the reader read
  `tail` then the writer did a full evicting publish then the reader read `head`). The window
  bound rejects that stale pairing → GAP. Correct: without it, the loop could visit a wrapped
  slot twice. Retained appropriately; ADR-0036 correctly notes it is now a *stale-t1* guard,
  not a *transient-overshoot* guard.

- **null-slot check.** A null slot means the writer is mid-append at `head` (slot not yet
  `W_ring`-published) or a wrapped slot the reader is racing. Treated as GAP — conservative and
  sound; never serves a partial/torn run. (For a fresh slot in a not-yet-wrapped ring, `head`
  would not have advanced to include it, so the loop bound `i < h` excludes it; the null branch
  fires only on genuine races, yielding GAP.)

- **`t2 != t1` re-check.** Covered by the core lemma + complementary case above.

- **`seq > cursor` filter.** Applied per entry inside the loop; an `Ok` run only contains
  `seq > cursor`. Combined with contiguity, the run is the strictly-ascending prefix of the
  retained stream above the cursor.

### New-hazard audit (the reordering's own risks)

1. **Transient `capacity − 1` occupancy.** During an evicting publish, between `W_tail` and
   `W_ring`, `size() == head - tail == capacity - 1` and the slot is momentarily the *old*
   value while `tail` already points past it. A reader in this window reads `t1` = advanced
   tail; its scan starts one position higher and excludes the retired slot. Worst case it
   returns a 1-entry-smaller `Ok` run (the retired entry is simply not yet replaced) — never a
   seq gap, never a duplicate. If the reader instead reads the *old* `tail` for `t1` and the
   `W_tail` lands during its copy, `t2 != t1` → GAP. Both outcomes are legal. **No hazard.**

2. **`oldestSeqInternal()` during the `W_tail` → `W_ring` window.** `oldestSeqInternal` reads
   `tail` (now advanced), then `ring.get(tail % cap)`. The slot at the *new* tail is a live,
   already-published entry (it was published at least `capacity` appends ago and not yet
   re-reached), so it returns a valid oldest seq. The *retired* slot is never read by
   `oldestSeqInternal`. There is a benign skew window where `oldestSeqInternal` could read
   `tail` advanced but a concurrently-in-progress `W_ring` not yet visible — but `W_ring`
   writes the *new head slot*, not the tail slot, so it cannot affect the tail read. The only
   value `oldestSeqInternal` returns is used as the GAP floor (advisory for replay) and in the
   empty check; a slightly-stale floor only makes the consumer replay from an equal-or-older
   point, which is safe. **No hazard.** (It can return `-1` transiently if it observes
   `h == t` racing an append; that is the documented empty sentinel and the consumer treats a
   GAP with floor `-1` as "replay from snapshot", which is safe.)

3. **`capacity == 1` corner.** slot is always `0`. First publish: `head-tail = 0 < 1`, no
   evict, `ring.set(0)`, head→1. Second publish: `head-tail = 1 >= 1` → evict: capture seq of
   slot 0, `lastEvictedSeq.set`, `tail→1`, `ring.set(0, new)`, `head→2`. A reader between
   `W_tail` and `W_ring` sees `t1 = 1`, `h = 1` (if head not yet advanced) → empty window →
   `Ok([])`; or `h = 2` with the new value and `h - t1 = 1 ≤ capacity` → scans slot 0, gets new
   value (seq matches its position 1), `t2 == 1` → clean single-entry run; or reads the lapped
   value with stale `t1 = 0` → `h - t1 = 2 > 1` → GAP. All legal. The unit test
   `capacityOneEvictsOnEveryAppend` and `CommitNotificationSourceTest` (cap can be 1 via
   `1 + rnd.nextInt(16)`) exercise this and pass. **No hazard.**

### `deltasSince` consumer-unreachability

`deltasSince`/`latest`/`oldestVersion`/`canReplayFrom` retain the old non-atomic
tail-then-head scan (RR-066 hazard) but are *legacy*: the `CommitNotificationSource` boundary
S3 consumes is `readSince` only. I confirmed `deltasSince` is not reachable from the
`CommitNotificationSource` interface; its remaining callers are the legacy fan-out wiring and
`FanOutBufferTest`, whose concurrent tests explicitly assert only *structural* validity (no
torn fields), not contiguity — consistent with the documented weaker contract. This is
unchanged by RR-096 and correctly out of scope. **Non-blocking note** below flags it for
eventual removal so it cannot regress into a consumer path.

**Conclusion of proof:** with evict-before-overwrite, no non-GAP `readSince` run can contain a
lapped/duplicate/non-ascending/torn/silently-skipped entry. The fix is sound. I could not
refute it.

---

## 2. Invariant check against the test suites (semantic, not just green)

- **`size()` after eviction = `head - tail`.** With `tail` advanced before `ring.set`, an
  external (single-threaded test) observer always sees `size() == capacity` post-fill
  (`head` and `tail` advance in lockstep per evicting publish; the transient `capacity-1` is
  invisible to a non-concurrent caller because both stores complete within `publish`).
  `FanOutBufferRaceTest` asserts `size() == capacity`, `latestSeq() == totalWrites`,
  `oldestSeq() == totalWrites - capacity + 1` post-run — all hold and passed. Semantically
  correct under the new order.
- **`oldestSeq()` after eviction.** `= seq at tail`. `CommitNotificationSourceTest`
  `boundSustainedAppendsNeverGrowBeyondRing` asserts `oldestSeq == cap*100 - cap + 1`; passes.
  Consistent with eviction retiring exactly the oldest.
- **`droppedTotal` accounting.** `droppedTotal.incrementAndGet()` fires once per evicting
  publish, in the `willEvict` branch, before the tail advance. One increment per eviction;
  `overflowIncrementsDropCountAndStaleCursorGetsGap` asserts `droppedTotal == 3` after 3
  overflows and passes. The race test asserts `droppedTotal >= totalWrites - capacity`; holds.
  Accounting is exact and unaffected by the reorder (the increment is bookkeeping, not part of
  the ordering argument).
- **GAP floor carried in `Result.Gap.oldestRetainedSeq`.** Equals `oldestSeqInternal()`;
  `overflowIncrementsDropCountAndStaleCursorGetsGap` asserts the GAP floor equals
  `buf.oldestSeq()`; passes. The replay-then-tail model test
  (`replayThenTailObservesEveryMutationEffectExactly`, 25 seeds) proves exactly-once-over-effect
  across overflow and passes.

All claimed invariants hold semantically, not merely by green bar.

---

## 3. Evidence re-run (exact results, serialized on the 2-vCPU box)

### 3a. JUnit (`FanOutBufferRaceTest, FanOutBufferTest, CommitNotificationSourceTest`)

Command:
```
./mvnw -q -pl configd-distribution-service test \
  -Dtest='FanOutBufferRaceTest,FanOutBufferTest,CommitNotificationSourceTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```
Result: **EXIT 0.** Aggregated across surefire reports (FanOutBufferTest reports per
`@Nested` class):

| Class | Tests | Fail | Err | Skip | Time |
|---|---|---|---|---|---|
| FanOutBufferRaceTest | 2 | 0 | 0 | 0 | 0.575 s |
| FanOutBufferTest (7 nested) | 35 | 0 | 0 | 0 | — |
| CommitNotificationSourceTest | 8 | 0 | 0 | 0 | 0.443 s |
| **TOTAL** | **45** | **0** | **0** | **0** | — |

(The top-level `FanOutBufferTest.txt` shows "Tests run: 0" because every test lives in a
`@Nested` class reported separately; the nested reports sum to 35. Not a miscount.)

### 3b. jcstress quick (`ExactlyFullWrap`, `LappedCursorBelowWindow`)

Commands:
```
./mvnw -q -pl configd-distribution-service install -Dmaven.test.skip=true        # EXIT 0
./mvnw -q -o -pl configd-jcstress clean package -Dmaven.test.skip=true           # EXIT 0 (jcstress.jar 5.7 MB)
timeout 580 java --enable-preview -jar configd-jcstress/target/jcstress.jar \
  -t 'FanOutBufferReadSinceTest\.(ExactlyFullWrap|LappedCursorBelowWindow)' \
  -m quick -r /tmp/rr096-verify
```
Result: **EXIT 0.**
```
(Results: 28 planned; 28 passed, 0 failed, 0 soft errs, 0 hard errs)
RUN RESULTS:
  Interesting tests: No matches.
  Failed tests:      No matches.
  Error tests:       No matches.
  All remaining tests: 2 matching test results.
```
A verbose re-run confirms **forbidden state 9 (TORN) = 0 samples** in every configuration, and
crucially that `ExactlyFullWrap` *exercises* the race (it produces a healthy mix of outcome 0
GAP, e.g. 29%–94%, and outcome 1 clean-run, e.g. 5.7%–71%, across runs — both reachable, never
TORN). `LappedCursorBelowWindow` returns 100% GAP (cursor 0 always below the post-seed
watermark) — correct, driven by the fast-path watermark. Representative per-test tables:
```
ExactlyFullWrap:           0 -> 936k..1.16M (GAP)   1 -> 70k..936k (clean)   9 -> 0 (FORBIDDEN)
LappedCursorBelowWindow:   0 -> 1.0M..1.3M  (GAP)   1 -> 0                   9 -> 0 (FORBIDDEN)
```
This is the discriminating test: pre-fix the register/ADR/captures record 14 forbidden-state-9
observations for `ExactlyFullWrap` in this exact `quick` invocation; post-fix I observe zero.

### 3c. Race-test stress loop (independent corroboration of the "8/8 green" claim)

I ran `FanOutBufferRaceTest` **5×** consecutively (serialized): **5/5 green, 0 failures.** As
documented, the 2-vCPU box cannot reproduce the original probabilistic failure (it needs ≥4
true cores); this loop confirms no regression and the jcstress run is the load-bearing
deterministic evidence on this hardware. (The register's "8/8" was on the fix-author's run; my
5/5 is consistent — neither can disprove the bug on 2 vCPUs, which is exactly why jcstress is
the gating evidence and why ADR-0036's gate-3 change moves these variants to `quick` mode.)

---

## 4. ADR-0036 + register accuracy

I checked every concrete claim in ADR-0036 and the RR-096 / RR-097 register rows against the
source and my runs:

- New evicting order `watermark → tail → overwrite → head` — **matches** `FanOutBuffer.publish`
  lines 133–144 exactly.
- "`W_tail` precedes `W_ring` in sync order; reader observing `W_ring` observes `W_tail`;
  `R_t2` follows `R_slot`" — **correct** (my §1 re-derivation reaches the same implication, and
  it is the *non-trivial* direction the old order lacked).
- "`head − tail ≤ capacity` is now a writer-side invariant (transiently `capacity − 1`)" —
  **correct**; the reader's `h − t1 > capacity` check is correctly retained for stale-`t1`.
- "one extra volatile store per *evicting* publish only (the tail store moved, it was not
  added)" — **correct**: same 4 stores as before, reordered; non-evicting path unchanged
  (`W_ring → W_head`).
- "transiently holds `capacity − 1` readable entries … observable only as a 1-entry-smaller
  `Ok` run, never a gap in seq" — **correct** per new-hazard audit item 1.
- Rejected alternatives (reader-side seq validation; seqlock stamp; lock the eviction path) —
  reasoning is sound; the writer-side order fix is minimal and sufficient.
- Verification section: pre-fix "14 forbidden observations", post-fix "same invocation clean
  (2/2 results, 0 forbidden)", "full distribution-service suite green" — **consistent with what
  I observed** (I see 28 results because `quick` plans more configs on this box than the "2/2"
  shorthand, but the substance — 0 forbidden, both variants pass — matches; see non-blocking
  note 2).
- RR-096 row: every discriminating-evidence claim reproduces. "Existing-test note" (S2's race
  test + `ExactlyFullWrap` *were* correct and *did* discriminate; the gap was hardware + sanity
  mode) is accurate — the test code predates the fix and detects the bug.
- RR-097 row: process claim (branches never pushed, first CI run surfaced RR-096) — outside my
  re-run scope but internally consistent with the captures and the ADR; I did confirm the fix
  lands on `session-3-data-plane` (HEAD `65a5212`), and that `session-2-correctness` is a
  separate branch (the register's "S2 tip remains red, branch immutable" note is honest).

**No overclaim found.** The one wording mismatch (post-fix "2/2 results" vs the observed "28
results / 2 matching tests") is a shorthand, not an overclaim — both describe a clean run; I
note it as non-blocking for precision.

---

## 5. Adversarial pass (attempts to break exactly-once-over-effect)

I tried to construct a still-broken interleaving in each of the listed directions; all fail to
produce a torn non-GAP run:

1. **Multi-publish burst, reader mid-eviction.** Reader reads `t1`, then the writer does *k*
   evicting publishes before the reader reads `h`. The window check `h - t1 > capacity` fires
   whenever the burst advanced head past `t1 + capacity`; for smaller bursts, any slot the
   reader copies that was overwritten by the burst is governed by the core lemma → its `R_t2`
   sees an advanced tail → GAP. Cannot tear.
2. **Reader starting mid-eviction (between `W_tail` and `W_ring`).** Reader reads the advanced
   `t1`; the retired slot is excluded from `[t1,h)`. It either gets a clean shorter run or, if
   another eviction lands during its copy, GAP. Cannot tear.
3. **Cursor exactly at the watermark (`cursor == evicted`).** Fast-path is `cursor < evicted`,
   so `cursor == evicted` does *not* short-circuit to GAP — correct: the entry with
   `seq == evicted` is the one already consumed (cursor sits on it); its *successor* may still
   be retained, so the reader proceeds to the window scan and serves `seq > cursor`
   contiguously, or GAPs if that successor was also evicted (then `lastEvictedSeq` would have
   advanced past `cursor`, re-triggering the fast path on the next call). No off-by-one hole.
   The `caughtUpCursorReturnsEmptyOkNotGap` and `readSince(3)`-served-contiguously assertions in
   `CommitNotificationSourceTest` pin exactly this boundary and pass.
4. **Natural seq gaps (no-op/RCFG entries skip seq numbers).** The watermark compares against an
   *actual evicted seq value*, and the run is built by `seq > cursor` filtering — neither relies
   on seqs being dense. A skipped seq is simply a value that never appears; contiguity is over
   the *retained notification stream*, not over the integers. The `applyNotifications` model
   check (`n.seq() > prev`) only requires strict ascent, which holds. Cannot manufacture a false
   GAP or a false OK from gaps.
5. **`cursor` above `head` (consumer ahead of buffer — caught up).** Loop produces empty run,
   `Ok([])`; not a gap. Matches `caughtUpCursorReturnsEmptyOkNotGap`. Correct.
6. **`capacity == 1` rapid lap.** Covered in §1 new-hazard item 3; every interleaving resolves
   to empty-Ok / single-clean / GAP.

I could not construct a counterexample. The adversarial pass strengthens the APPROVE.

---

## Findings

### [NON-BLOCKING] N1 — legacy `deltasSince`/`latest`/`oldestVersion` retain the RR-066 non-atomic scan
`FanOutBuffer.deltasSince` (and `latest`, `oldestVersion`, `latestVersion`) still read
`tail`-then-`head` non-atomically with no verify-after-read, i.e. the exact RR-066 hazard the
`readSince` path was built to close. This is **safe today** because none of them is reachable
through the `CommitNotificationSource` boundary S3 consumes (verified), and the concurrent
`FanOutBufferTest` cases assert only structural validity for them. **Risk:** a future caller
wiring `deltasSince` into a real consumer path would silently reintroduce torn reads.
**Recommendation (non-blocking):** mark these `@Deprecated`, or add a one-line class-doc
assertion that they are consumer-unreachable, so a reviewer catches any future hookup. Does not
block prod.

### [NON-BLOCKING] N2 — ADR-0036 verification wording "2/2 results" vs observed "28 results"
ADR-0036 §Verification says post-fix jcstress is "clean (2/2 results, 0 forbidden)". On this
box the same `quick` invocation plans **28 results** (multiple JVM/stress configs per test) and
reports "2 matching test results" in the summary line. Both describe a clean run; the "2/2" is
shorthand for the two *test classes*, not the result count. **Recommendation (non-blocking):**
reword to "both variants pass, 0 forbidden across N planned results" to avoid a future reader
mistaking 2 for the config count. Documentation only; does not block prod.

### [NON-BLOCKING] N3 — 2-vCPU box cannot reproduce the original JUnit failure
`FanOutBufferRaceTest` passed 5/5 here, but (as the ADR/register state) this hardware lacks the
true parallelism to surface the original probabilistic tear; the deterministic evidence is
jcstress `quick`. This is already correctly documented and gate-3 moves the variants to `quick`
mode in CI. **No action needed**; recorded so the green JUnit loop is not mistaken for the
load-bearing evidence. Does not block prod.

---

## Summary

APPROVE. Fix `65a5212` correctly closes RR-096: I independently re-derived the JMM argument and
confirmed that with `lastEvictedSeq.set → tail++ → ring.set → head++`, `W_tail` precedes
`W_ring` in synchronization order, so any reader whose copy includes an overwritten slot
necessarily observes `t2 > t1` and reports GAP — the implication ADR-0034 §5 claimed but the old
order did not provide. All readSince paths (watermark fast-path, `h−t1>capacity`, null-slot,
`t2` re-check), both publish paths, and the new-order-specific hazards (transient `capacity−1`
occupancy, `oldestSeqInternal` during the tail/ring window, `capacity==1`) are sound; legacy
`deltasSince` remains consumer-unreachable. Evidence reproduces: 45/45 JUnit green; jcstress
`quick` 28/28 passed with 0 forbidden state-9 (TORN) and `ExactlyFullWrap` demonstrably
exercising the eviction race (GAP+clean both observed, never torn); race test 5/5 in a stress
loop. ADR-0036 and the RR-096/RR-097 register rows are accurate with no overclaim (one
shorthand-wording nit, N2). Three non-blocking notes; nothing blocking for prod. No production
code modified by this review.
