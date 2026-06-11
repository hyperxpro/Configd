# Review — B7 formal + B8 boundary second-agent verification

- **Reviewer:** review-architect (Session 2)
- **Date:** 2026-06-11
- **B7 (formal):** commits `c3dc42f`, `a3c93c0`, `08ca7ab`, `f4de684` — rows RR-026, RR-029 (W-1
  residual), RR-030, RR-061, RR-062, RR-063.
- **B8 (boundary):** commit `8af99b8` — rows RR-042, RR-066, RR-001-narrowing; ADR-0034 sign-off.
- **Constraint honored:** runs kept small/targeted (a PIT agent + a jcstress agent were active);
  path-scoped commit; did not touch poms or `gates/gate-2.sh`.

---

## Verdicts

| Row | Verdict |
|---|---|
| RR-026 (liveness) | **APPROVE — RESOLVED (honest partial).** |
| RR-029 W-1 residual | **APPROVE — MY F-1 IS CLOSED.** |
| RR-030 (7 twins) | **APPROVE — RESOLVED.** |
| RR-061 / RR-062 / RR-063 (hygiene) | **APPROVE — RESOLVED.** |
| RR-042 (write-only sink) | **UPHELD as RESOLVED-as-interface-delivered.** |
| RR-066 (race fix) | **APPROVE — RESOLVED; race sound, no dup/skip/torn.** |
| RR-001-narrowing | **Narrowing accurate — stays OPEN correctly.** |
| **ADR-0034** | **APPROVED — Status set to Accepted with my sign-off.** |

Strongest finding B1: the bound-reduction story is honest — the non-exhausted MaxTerm=3 partial run
(4.19M distinct / 0 violations / 40 min) is recorded, not hidden; one stale per-spec header nit.
Strongest finding B2: the exactly-full `(head−tail) >= capacity` guard is the load-bearing RR-066
fix (a `>` would silently overwrite the oldest with no GAP) — verified correct at every occupancy.

---

## BATCH 1 — B7 formal

### 1. The 7 new twins — faithful, reachable, prod-metric/test-throwing

I read every new check site and compared each predicate against its `.tla` invariant:

| Twin | Site | .tla invariant | Faithful? |
|---|---|---|---|
| `read_freshness` | `assertReadServeInvariants` | ReadFreshness (`readIdx <= appliedIndex`) | yes (`servedIdx <= lastApplied`) |
| `no_stale_leader_serve` | `assertReadServeInvariants` | NoStaleLeaderServe (`term <= currentTerm`) | yes + `role==LEADER` (stronger) |
| `read_index_bounded` | `assertReadServeInvariants` | ReadIndexBoundedByMaxIndex | yes, runtime form (`<= commitIndex`) |
| `snapshot_bounded` | `triggerSnapshot` | SnapshotBoundedByCommitted | yes (`appliedIndex <= commitIndex`) |
| `snapshot_no_commit_revert` | `checkSnapshotInstallTwins` | NoCommitRevert | yes (`inTerm >= curSnapTerm`) |
| `snapshot_matching` | `checkSnapshotInstallTwins` | SnapshotMatching | yes (`localTermAtIn == inTerm`) |
| `snapshot_term_consistent` | `checkSnapshotSendTwin` | InflightTermMonotonic | yes (`recordedTerm == sendTerm`) |

All use the IDENTICAL production `invariantChecker.check(name, false, …)` call shape (so prod =
metric+log via `InvariantMonitor(throwOnViolation=false)`, test/sim = throwing). All are reachable
in prod wiring: `assertReadServeInvariants` from the serve paths (`whenReadReady` `:628`,
`fireReadyCallbacks` `:701`); `checkSnapshotInstallTwins` from `handleInstallSnapshot` `:1994`;
`checkSnapshotSendTwin` from `sendInstallSnapshot` `:1690`; `snapshot_bounded` inside the real
`triggerSnapshot`. The Javadoc honestly labels them defense-in-depth (the live readiness/install
gate makes the condition hold; the twin catches a regression that corrupts the record or removes
the gate). The `read_index_bounded` mapping to the spec's `<= MaxIndex` is rendered as the
operationally meaningful runtime form `<= commitIndex` — the right prod predicate.

### 2. AssertionTwinFiringTest (both modules) — non-vacuous, fires for the right reason

`RecordingChecker.expectFires(twin, r)` asserts not just that *an* `AssertionError` was thrown but
that **the specific named twin** is in the fired list (`fired.subList(...).contains(twin)`) — so a
different twin/exception cannot satisfy it. Each falsifiable twin is driven via a poisoned REAL
input through the REAL check method: `read_freshness` (readIndex 9999 ≫ lastApplied);
`read_index_bounded` (first bumps lastApplied past commitIndex to pass the freshness gate, isolating
the bound twin — attention to firing the *intended* twin); `no_stale_leader_serve` (read recorded at
term+5); the snapshot twins via genuinely-violating descriptors; `snapshot_bounded` via the real
`triggerSnapshot` with lastApplied poisoned past commitIndex. The structurally-guarded in-node twins
(election_safety, log_matching, …) are fired via `fireInNodeTwinForTest` — the identical production
`check(name, false, …)` shape — and the Javadoc is transparent that these sit behind guards where
the real predicate cannot be false (the honest InvariantNetMetricTest pattern). The final gate
asserts EVERY twin in `RAFTNODE_TWINS` was observed firing. **Re-ran:** consensus-core 1/1 (2.5s);
config-store 2/2 (the W-1 test — see §6). Both green.

### 3. The two TLC counterexamples — re-ran both, documented violations reproduced

- `ConsensusSpec-ackonappend.cfg` (`ACK_ON_APPEND=TRUE`) → **`Invariant AckImpliesCommitted is
  violated`** (560 states / 228 distinct, 21s). A write acked on local append is truncated on a
  leadership change and lost — the RR-004 defect; proves AckImpliesCommitted non-vacuous.
- `SnapshotInstallSpec-truncatebeforepersist.cfg` (`PERSIST_BEFORE_TRUNCATE=FALSE`) → **`Invariant
  DurablePrefix is violated`** (162 states / 62 distinct, 17s). The trace shows LocalSnapshot then
  TruncateWal (walBase 1→2) while durIndex stays 0 — a committed index in neither durable snapshot
  nor durable WAL; the RR-003 silent-loss defect. (Runtimes a bit above the ~1s claim — CPU
  contention with the PIT agent — but both report the documented violation.)

### 4. Bound reductions — adequacy judged honestly: SOUND

The >3×-blowup story holds: ConsensusSpec gained `acked` + `ClientAck` + `AckImpliesCommitted`,
which multiply the space against the term/reconfig product. At **MaxTerm=3 the full run did NOT
exhaust in 40 min (16.6M states / 4.19M distinct / depth 19 / 1.2M queued / 0 violations in the
explored prefix)** — and this partial run is **recorded, not hidden** (tlc-results.md Summary). At
MaxTerm=2 it EXHAUSTS (2.285M distinct, No error, 28m29s) and still reaches election + re-election +
joint reconfig + the commit-confirmed ack cycle. SnapshotInstall MaxIndex 4→2 is a fundamental
state-space change (4 new per-node vars × always-enabled CrashRestart ≈ (MaxIndex+1)^12); MaxIndex=2
exhausts (1.797M distinct, 13m26s) and exercises snapshot-of-snapshot + the full
commit→WAL→snapshot→persist→truncate→crash cycle. The full-term-dynamics for the ack defect are
delegated to the dedicated counterexample cfg (re-run above). **Adequate**, with one honest caveat:
the MaxTerm=3 safety result is a *non-exhausted 0-violation prefix*, correctly presented as such.

**Non-blocking doc nit:** `tlc-results.md` line 82's per-spec header says SnapshotInstallSpec
"MaxTerm=3, MaxIndex=3 (was 4)", but the authoritative Summary table (line 18) and the **actual
`SnapshotInstallSpec.cfg`** both say MaxTerm=2 / MaxIndex=2. The header is stale on both counts —
worth a one-line fix so the doc is internally consistent.

### 5. Liveness reformulation — meaningful, not a weakened tautology; vacuity re-proven

`ReadEventuallyServed == HasServeableRead ~> ~HasServeableRead`. The `InitiateReadIndex`
`~HasServeableRead` gate is what makes it meaningful: it prevents new reads from piling on while a
serveable one waits, so a serveable read is a *continuous completion witness* that weak fairness
(WF on CompleteReadIndex/ApplyEntry/ReadHeartbeatAck) must drain — no churn-masking. **I re-ran the
fairness-vacuity check:** under the unfair base `Spec` the property is **VIOLATED** with a stuttering
counterexample (TLC even names the missing fairness constraint), 740 states, 1s. So the LiveSpec
GREEN is *earned by the fairness*, not vacuous. This is the right way to validate a liveness property
(first one model-checked in this repo).

### 6. W-1 violation test — closes my F-1

`io.configd.store.AssertionTwinFiringTest.applyOwnerThreadTwinIsObservedFiring` binds the owner on
the test thread (first apply), drives `apply` from a real SECOND thread (`off-owner-apply`), and
asserts (a) the off-owner apply threw `AssertionError` via the wired checker, (b) `apply_owner_thread`
is in the fired list, (c) `onApplyOwnerThreadViolation` incremented exactly once (prod metric path).
This is precisely the violation-path test my RR-004 review (F-1) required — non-vacuous (asserts the
specific twin + the metric). **Re-ran 2/2 green.** RR-029's row correctly states what remains: the
W-1 portion is closed; **R-1 (`ReadResult.value()` live `byte[]`, CF-31) and W-2 (non-volatile SM
getters, CF-30) stay OPEN, owned by the jcstress round** — accurate.

### RR-061/062/063 hygiene — verified

RR-061: `grep -c 'Programming/Configd' spec/tlc-output.txt` → 0 (regenerated locally). RR-062:
`git ls-files spec/states` → 0 (was 454), `spec/states/` in `.gitignore`, 0 tracked `.bin`; the
non-vacuity claim is now reproducible by the two committed cfgs (re-run in §3). RR-063: `which
apalache` + `find … -iname '*apalache*'` → nothing; TLC is the verification of record; descope honest.

---

## BATCH 2 — B8 boundary

### ADR-0034 — APPROVED (Status → Accepted, sign-off recorded in the ADR)

The boundary contract S3 builds against is sound on all four verification points the ADR requested:
schema justification (seq/commitTimestampMillis/delta — exactly what S3 needs, transport-agnostic),
cursor/GAP semantics (`Ok` contiguous run | `Gap(oldestRetainedSeq)`; caught-up ⇒ empty `Ok`, not
GAP; never a partial/duplicated run), the drop-with-metric policy justified by the log-as-replay
source (post-RR-003 durable prefix reconstructs everything; the buffer is a hot-path cache), and the
explicit no-fan-out/no-wire/no-edge scope. The rejected-alternatives section is thorough (no
backpressure on the apply thread; no unbounded buffer; no locks; one leader clock not per-entry HLC;
GAP not a truncated-flag). Details in the ADR sign-off block.

### 2. RR-066 race fix — adversarial read: SOUND, no duplicate/skip/torn reachable

I walked every dangerous interleaving against the publish order
`ring.set → head++ → (on evict) lastEvictedSeq.set → tail = head − capacity`:

- **DUPLICATE** — prevented by `h − t1 > capacity → GAP`. The reader reads `tail` (t1) FIRST then
  `head` (h), so a mid-eviction lap (head observed ahead of the matching tail advance) shows as
  `h−t1 > capacity` → GAP. When `h−t1 ≤ capacity`, the window `[t1,h)` spans ≤ capacity positions, so
  `i % capacity` is unique — no slot visited twice.
- **SKIP** — prevented by the `lastEvictedSeq` watermark. Evictions are monotonic in seq (drop-oldest
  over an append-monotonic stream), so the most-recently-evicted seq is the HIGHEST evicted; a reader
  with `cursor >= evicted` has nothing-it-needs evicted, and `cursor < evicted` ⇒ GAP. Exact even
  across natural seq gaps (no-op/RCFG skip seq) because it compares against an actual evicted seq, not
  position arithmetic.
- **TORN slot** — prevented by `AtomicReferenceArray` per-slot atomicity (a slot holds the old or new
  reference, never torn) plus the verify-after-read (`t2 != t1 → GAP` catches an in-place overwrite
  during the copy; a null slot also ⇒ GAP).

**The implementer's exactly-full fix is correct and covers wrap-around at every occupancy.**
`willEvict = (head − tail) >= capacity` uses `>=`: at exactly-full (`head−tail == capacity`,
`head%cap == tail%cap`) the slot being written IS the oldest, so it must evict; a `>` would silently
overwrite the oldest WITHOUT capturing the watermark or advancing tail — a silent skip with no GAP.
The `>=` is the load-bearing correctness bit. **Legacy `deltasSince` is genuinely
consumer-unreachable** — grep: 0 src/main callers (the `log.append`/`sb.append` hits are unrelated);
production drains via `readSince` (`ConfigdServer:389` publishes). **Re-ran `FanOutBufferRaceTest`
2/2** (the 200k-write/4-reader/cap-64 stress + the reader-paced exactly-once test).

### 3. Replay contract — `replayThenTailObservesEveryMutationEffectExactly` genuinely proves it

Non-vacuous. An `Authoritative` model tracks the true cumulative state; the consumer materializes its
own view by applying tailed notifications and, on GAP, `view.clear()` + adopts the replay snapshot
wholesale, resuming from the snapshot seq. The assertion `mapsEqual(consumerView, auth.snapshotMap())`
is a **real byte-level key/value equality** — a skip or double-application would diverge the views.
25 seeds × 50-250 rounds × bursts up to cap×2+2 (cap randomized 1-16) force frequent overflow → GAP
→ replay. A per-run contiguity assertion (`n.seq() > prev`) pins no-dup. The cursor reaches the
authoritative seq. This is exactly-once **over effect** across overflow. The **snapshot-equivalent**
ReplaySource is honestly characterized in ADR-0034 §4 ("snapshot-equivalent, not full historical-log
replay"; the edge applies cumulative state; an auditing consumer would get a separate WAL-backed
seam, out of scope) — the test's `replayFromSnapshot` returns the cumulative snapshot, matching it.

### 4. RR-042 judgment — UPHELD as RESOLVED-as-interface-delivered

The dead-code defect (write-only sink, no consumer-facing read path) is genuinely gone: the read side
now exists as a race-safe, GAP-signalling, replayable contract wired into prod. I verified **no
production consumer drains it yet** (no `readSince` caller in src/main outside the boundary;
`configd-edge-cache` still has no networking) — but that absence is correctly **RR-001's scope**, and
the row tracks it there. Reclassifying RESOLVED is honest *because the row is explicit* that "the
actual edge drain is RR-001's remaining S3 wire work, not RR-042's." I uphold it with that framing —
the row must not be read as "the buffer is drained in prod." RR-001 stays OPEN with reduced blast
radius (verified accurate).

---

## Re-run evidence (summary)

| Item | Result |
|---|---|
| consensus-core `AssertionTwinFiringTest` (16 twins fire) | 1/1, 2.5s |
| config-store `AssertionTwinFiringTest` (per_key_order + W-1 2-thread) | 2/2 |
| TLC `ConsensusSpec-ackonappend.cfg` | AckImpliesCommitted violated, 228 distinct, 21s |
| TLC `SnapshotInstallSpec-truncatebeforepersist.cfg` | DurablePrefix violated, 62 distinct, 17s |
| TLC `ReadIndexSpec-livenessvacuity.cfg` (unfair Spec) | ReadEventuallyServed VIOLATED (stutter), 740 states, 1s |
| `CommitNotificationSourceTest` + `FanOutBufferRaceTest` | 10/10 (direct JUnit launcher — reactor transiently unbuildable: a concurrent agent's malformed `configd-jcstress/pom.xml`, NOT my edit) |

All rows hold. Two non-blocking nits: (1) tlc-results.md line 82 stale SnapshotInstall bound header
(should be MaxTerm=2/MaxIndex=2); (2) RR-001 correctly stays OPEN — narrowing only.
