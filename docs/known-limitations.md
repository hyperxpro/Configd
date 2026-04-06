# Known Limitations — v0.1

**Authored:** 2026-04-25 (iter-3 code-level production-readiness pass).
**Companion to:** `docs/production-readiness-code-level.md`,
`docs/automation-prerequisites.md`, `docs/ga-approval.md`,
`docs/ga-review.md`.

> **Read this if you are about to sign `docs/ga-approval.md`.**
> v0.1 is **code-level** production-ready, not **empirically**
> production-ready. The user explicitly chose to defer load / soak /
> chaos / burn-in validation to production observation. This file
> states what that deferral means, concretely.

---

## What was validated

- **Code-level correctness** via 8 rounds of adversarial review
  on iter-3 (the wire-protocol v1 changes), terminating in two
  consecutive clean passes at the S0/S1 level.
- **Reactor green** at 21,394 tests, 0 failures, 0 errors,
  ~57 s wall-clock on JDK 25 + `--enable-preview`. Surefire
  evidence under `*/target/surefire-reports/TEST-*.xml`.
- **Property-based fuzzing** of `FrameCodec` (11 properties × 50–500
  tries) and `RaftMessageCodec` (12 properties × 100–200 tries).
- **Wire-format byte-equality** against checked-in fixtures pinned
  by an externally-verified CRC32C reference vector.
- **Formal model checking** (TLC) of Consensus, ReadIndex,
  SnapshotInstall — pre-existing, unchanged by iter-3.
- **CI guardrail** for fixture-bump without version-bump (added in
  pass-6, polished in passes 7–8).

## What was NOT validated

These are the empirical gates the user accepted as production-observation
items rather than pre-GA gates:

### Performance under sustained load

- 72-hour soak: not run (C1 YELLOW per `docs/ga-review.md`).
- 7-day burn-in with periodic chaos: not run (C2 YELLOW).
- 14-day shadow traffic: not run (C4 YELLOW).
- 30-day longevity: not run (C3 YELLOW).
- JMH benchmarks of the new encode/decode path with CRC32C: not run.
- Allocation profiling on hot paths under realistic traffic: not run.

**What this means in production:**

- Performance regressions surface in front of users, not before.
- The CRC32C compute cost is theoretically bounded
  (~0.5 ns/byte hardware-accelerated on x86 / ARMv8) but unmeasured
  on your traffic mix.
- Fan-out amplification, GC behaviour, and lock contention patterns
  are unmeasured until production.

### Fault recovery under real failure modes

- Real network partitions: not exercised.
- Real disk-full / fsync-stall events: not exercised (network-only
  chaos in `SimulatedNetwork`; `C-104, C-108, C-111, C-112, C-113`
  RED in `docs/ga-review.md`).
- Real cert rotation under load: not exercised (S5 YELLOW —
  production rebuild not landed; chaos-only negative path).
- Real leader-loss recovery against an SLA: not exercised
  (Runbook-conformance YELLOW; zero drills executed).
- Real restore-from-snapshot drill: not exercised
  (`ops/dr-drills/results/` empty).

**What this means in production:**

- Concurrency bugs that only manifest under sustained load surface
  during real incidents.
- The first follower-bootstrap from snapshot in production is
  the actual chaos-engineering exercise.

### Snapshot size cap (NEW iter-3 limitation)

- `MAX_SNAPSHOT_BLOB_LEN = 4 MiB` (`RaftMessageCodec`). If
  `ConfigStateMachine.snapshot()` exceeds this, the affected
  follower **cannot** bootstrap from snapshot — must catch up via
  AppendEntries from the leader's oldest retained entry. If the
  leader has compacted past entries the follower needs, that
  follower is permanently behind.
- v0.2 will implement chunked InstallSnapshot via the `offset` /
  `done` fields (already in the wire format, currently ignored by
  the leader). v0.1 ships with the cap.
- **Mitigation in v0.1:** tune snapshot policy so state stays
  under 4 MiB at snapshot time. Monitor per-follower `matchIndex`
  lag against leader `commitIndex` as a proxy for "follower is
  stuck because snapshot install rejected".
- **Operator-visible signal:** stderr line
  `"Dropping InstallSnapshot to ... (codec rejected — snapshot too
  large for v1 wire)"`. No Prometheus metric exports this yet
  (W5 carryover).

### Wire-version mismatch alerting

- v1 is the first version, so no v0/v1 mixed traffic exists today.
- v2 will land with a Hello handshake (ADR-0030+, not yet authored)
  to negotiate version. Until then, mixed-version traffic terminates
  the connection with `UnsupportedWireVersionException`.
- **Operator-visible signal:** stderr line
  `"Inbound wire-version mismatch (observed=0xNN); dropping
  connection"`. No metric.

### Encoder-drop observability

- `RaftTransportAdapter.send` re-throws `IllegalArgumentException`
  from the encoder; `RaftNode.sendAppendEntries` /
  `sendInstallSnapshot` catch it, log to stderr, skip the
  `inflightCount` increment, and return.
- This prevents the cluster-wide outage that pass-3 / pass-4
  identified, but **no metric counter exports drop frequency** —
  it's stderr only. Operators must scrape logs.
- **W5 carryover** wires `ConfigdMetrics.raftOutboundDrop({type, reason})`
  in a separate observability pass.

### Test-coverage instrumentation

- Line / branch coverage on safety-critical modules: not measured
  this pass. Reactor pass-rate is 100 %, but jacoco was not run.
- Mutation-testing score on consensus-core / config-store: not
  measured.

---

## What "production-ready code-level" specifically means here

When `docs/production-readiness-code-level.md` certifies the iter-3
diff as production-ready, that statement is bounded to:

- Every public symbol introduced by iter-3 has Javadoc, a negative
  test, and an explicit boundary check at every untrusted-input
  edge.
- The §3 phase-4 adversarial-review protocol terminated cleanly:
  two consecutive passes by independent reviewers found 0 S0 and
  0 S1 issues.
- The build produces verifiable evidence (surefire reports, fixture
  files, ADR document).
- Failure modes are documented (ADR-0029, this file) such that an
  operator can correlate a production symptom to its root cause
  without reverse-engineering the code.

It does NOT mean:

- The system has been observed performing under realistic load.
- Failure modes have been observed actually firing in production.
- Recovery procedures have been observed working in production.
- The first 30 days are not the actual burn-in.

---

## First 30 days = the burn-in

Per the prompt's `<the_tradeoff_being_made>`: **"the first 30 days
of production are de facto burn-in, with associated incident risk."**

The operator's job during that window is documented in
`docs/production-readiness-code-level.md` §7. In summary:

- Heightened alerting on the stderr substrings listed in §7 item 1.
- Daily error-budget review.
- No concurrent feature deploys.
- Predefined rollback triggers (4 specific ones in §7 item 4).
- Named on-call rotation per `docs/operator-runsheet.md` step 7
  must be in place before the burn-in starts.

If the cluster passes the 30-day window without triggering any
rollback, the empirical-validation gap is closed by observation.
Until then, **this is a code-level certification operating as an
empirically-unproven contract.**

---

## What to do if you are the GA approver

1. Read `docs/ga-review.md` for the gate-by-gate state.
2. Read `docs/production-readiness-code-level.md` for the iter-3
   delta and the file manifest.
3. Read this file (`docs/known-limitations.md`) so you understand
   what your signature is asserting and what it is NOT asserting.
4. Read `docs/automation-prerequisites.md` for the calendar-bounded
   gates that remain non-promotable.
5. Read `docs/operator-runsheet.md` so you know the operator-side
   work that must precede signing.
6. Make an informed signing decision on `docs/ga-approval.md`. The
   loop / iter-3 / this certification all stop short of that
   signature by design — per `<the_tradeoff_being_made>` and the
   §4.7 honesty invariant, the human signature is the thing the
   automation cannot do.

If after reading these files you are not comfortable signing,
that is the system working as designed. The deferral was your
explicit decision; revisit the deferral, not the certification.
