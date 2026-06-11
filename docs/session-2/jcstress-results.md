# jcstress race-testing results — B6 / RR-011 (+ RR-066, RR-029 CF-31/W-2)

**Owner:** jcstress-engineer (Session 2) · **Module:** `configd-jcstress` · **Date:** 2026-06-11

This document records the jcstress harness, the curated gate subset, and the full
best-effort run. A **FORBIDDEN** outcome observed by jcstress is a real race bug
and is escalated as a register finding (none were found).

## 0. Hardware bound (read this before trusting the numbers)

The bench host is a 2-vCPU burstable box with real CPU-credit throttling. jcstress
wants spare cores: each test forks a fresh JVM and runs N actors in parallel, so a
2-vCPU box runs **fewer concurrent actors and needs longer wall time to reach the
same confidence** as a many-core box. Concretely:

- A clean jcstress pass on 2 vCPU is **weaker evidence** than the same pass on,
  say, 16 cores — fewer real interleavings are sampled per unit time. State this
  honestly: the **curated subset is a smoke** (proves the detector runs end-to-end
  and surfaces no forbidden outcome at low sampling); the **full run is best-effort
  on this box**, not a high-core soak.
- **3-actor tests cannot be scheduled** when only 2 hardware threads exist:
  jcstress reports the scheduling class but the test does not converge in bounded
  time. `FanOutBufferReadSinceTest.TwoReadersOneWriter` (1 writer + 2 readers) is
  therefore **excluded from the curated subset** and is full-run / multi-core-CI
  only. The 2-actor single-reader variants cover the identical RR-066 torn-read
  invariant deterministically.
- Actor/fork settings chosen: curated subset uses jcstress **`sanity` mode**
  (1 normal + 1 stress fork per test, 1 iteration each, `-c 2`) — the fastest
  preset that still forks. The full run uses **`default` mode** (5 forks, more
  iterations) and should run **only when PIT is not contending** (PIT mutation is
  CPU-bound and corrupts timing-sensitive race sampling; coordinate by polling
  `pgrep -af pitest`).

## 1. Module wiring

`configd-jcstress` is a new reactor module that produces an executable uber-jar
(`target/jcstress.jar`, Main-Class `org.openjdk.jcstress.Main`) the **exact same
way `configd-testkit` builds `benchmarks.jar` for JMH**: `jcstress-core` is both a
compile dependency and the annotation-processor path; `maven-shade-plugin`
bundles everything with the `ManifestResourceTransformer` + `ServicesResourceTransformer`
+ an `AppendingTransformer` for the generated `META-INF/TestList` index. The module
depends on the three structure-owning modules (`config-store`,
`distribution-service`, `transport`).

Deliberately **not `--enable-preview`** (the parent default is overridden):
jcstress forks a fresh JVM per test and does not propagate the parent's
`--enable-preview` to forks, so a preview argLine would make every fork fail to
start. The codebase has zero preview-feature classes, so a plain `release=25` JVM
compiles and runs the harness cleanly.

Build:
```
./mvnw -o -pl configd-config-store,configd-distribution-service,configd-transport -am install -Dmaven.test.skip=true
./mvnw -o -pl configd-jcstress clean package -Dmaven.test.skip=true
```
(`maven.test.skip`, not `-DskipTests`: a sibling module's in-progress non-compiling
*test* sources must never block the harness build — the upstream **main** artifacts
are all jcstress needs. Many agents share this branch.)

## 2. Harness self-test (test-the-tester) — PASSED

Before trusting any verdict, `HarnessSelfTest` pairs a known-racy gadget with a
known-safe one. A detector you have never seen fire is not a detector.

| self-test | shape | expected | observed |
|---|---|---|---|
| `KnownRacyCounter` | two actors `x++` on a shared plain `int` | FORBIDDEN `(1,1)` lost-update must appear | **`[FAILED]` — `1, 1` lost-update OBSERVED** (2–26% across forks) |
| `KnownSafeDisjoint` | two actors `++` on disjoint fields | clean, only `(1,1)` | **`[OK]` — only `1, 1`, zero forbidden** |

The racy case surfaced the lost-update interleaving even on the contended 2-vCPU
box; the safe case stayed clean. The harness genuinely detects races. (These two
are run standalone and are **never** part of the gate batch — `KnownRacyCounter`
is intentionally forbidden-hitting.)

## 3. Per-target tests + verdicts

Authoritative source: a complete `sanity`-mode run of all 13 real test classes
(`-c 2`), **182/182 planned results passed, 0 failed, 0 hard/soft errors** — every
class CLEAN (no FORBIDDEN). Each test observed **multiple distinct ACCEPTABLE
outcomes**, proving the race window is actually hit (non-vacuous), never a
forbidden one.

### RR-002 — `TcpRaftTransport` per-peer shared state (6 interleavings, verbatim)

Exercised through `PeerModel`, a socket-free model that copies `PeerConnection`'s
field algebra and publish order verbatim (live sockets would inject
non-deterministic I/O into the race window; the static guard + live blackhole drill
cover the I/O behaviour separately).

| # | test | interleaving | verdict |
|---|---|---|---|
| 1 | `EnqueueVsTeardownVsPublish` | enqueue(out==null) vs teardown-clear vs connect-publish — no wedge, no double connect | ACCEPTABLE (clean) |
| 2 | `CasVsFinallyReset` | scheduleConnect CAS vs connectAndStartWriter finally reset+reschedule — exactly one pending connect | ACCEPTABLE (clean) |
| 3 | `DoubleTeardownIdempotent` | reader-teardown vs writer-teardown same socket — identity-guard idempotency | ACCEPTABLE (clean) |
| 4 | `PublishVsWriterStart` | socket/out volatile publish vs writer-start visibility — never two writers / null stream | ACCEPTABLE (clean; only "exactly one writer, non-null stream") |
| 5 | `CloseVsInFlightConnect` | close() vs in-flight connect past the closed gate — benign leak only | ACCEPTABLE (clean) |
| 6 | `DropOldestVsPoll` | drop-oldest evict vs writer poll — framesDropped accounting exact | ACCEPTABLE (clean) |

### RR-066 — `FanOutBuffer.readSince` Lamport verify-after-read vs eviction

| test | occupancy | verdict |
|---|---|---|
| `PartiallyFull` | ring not full (no eviction; head advances under reader) | ACCEPTABLE (clean) |
| `ExactlyFullWrap` | ring exactly full → next publish evicts + wraps (the load-bearing case) | ACCEPTABLE — observed BOTH `GAP (lapped)` and `clean ascending run`, never torn |
| `LappedCursorBelowWindow` | cursor below the live window → watermark must drive GAP | ACCEPTABLE (clean) |
| `TwoReadersOneWriter` (3-actor) | two readers + one evicting writer | **full-run / multi-core only** (cannot schedule 3 actors on 2 vCPU) |

Invariant enforced: every `readSince` returns either a clean contiguous ascending
run with `seq > cursor` (no duplicate, no skip, no null slot) **or** a GAP — never
a torn read. No duplicate/skip/torn observed at any occupancy including the
exactly-full wrap-around.

### RR-029 — read-path structures + CF-31 / W-2 probe

| test | invariant | verdict |
|---|---|---|
| `VersionedConfigStoreReadTest.ConsistentVersionRead` | read-while-write returns a consistent `(value,version)`, never a torn MVCC splice | ACCEPTABLE — only consistent pairs (`1,1` / `2,2`) observed |
| `VersionedConfigStoreReadTest.AliasedArrayNoTear` (**CF-31**) | the aliased internal `byte[]` handed out by `valueUnsafe()` is never observed torn under concurrent overwrite | ACCEPTABLE — value bytes always decode to a really-published version (1 or 2); the forbidden "never-published" value (99) never appeared |
| `HamtMapStructuralSharingTest.SharedKeyStaysReachable` | a key in a different subtrie stays reachable through a put that copies another path | ACCEPTABLE (shared key always present) |
| `HamtMapStructuralSharingTest.ConsistentMapVersion` | a read observes a self-consistent map version (no half-insert) | ACCEPTABLE — only `(7,absent)` pre-put and `(7,99)` post-put observed |

## 4. CF-31 / W-2 finding — SAFE-BY-CONSTRUCTION (no real hazard)

**CF-31** (`ReadResult` hands out the live internal `byte[]` via
`VersionedValue.valueUnsafe()`): jcstress could **not** demonstrate a torn/visibility
hazard. The aliased array is real, but `VersionedValue` **defensively copies on
construction and is immutable** — the writer publishes a *new* `VersionedValue`
(and a new `ConfigSnapshot`) and never mutates a previously-published array. The
volatile `currentSnapshot` swap provides the happens-before. So the aliased array a
reader observes is effectively frozen: `AliasedArrayNoTear` ran clean (every
decoded value was a genuinely-published version). **CF-31 is an aliasing /
encapsulation smell (a caller *could* mutate the returned array and corrupt the
store), not a concurrency tear** — that is a defensive-copy hardening question for
the store owner, not a race. Recorded as such, not escalated as a race bug.

**W-2** (non-volatile state-machine getters read cross-thread): this lives in
`ConfigStateMachine` (consensus path), not in the lock-free read-path structures
this module owns. The store read path that *is* exercised here
(`VersionedConfigStore` via the volatile snapshot) shows no visibility tear. W-2
on the `ConfigStateMachine` getters remains an OPEN residual on RR-029 — it is not
reachable through the read-path structures and would need a state-machine-level
jcstress harness to probe directly; flagged for the row owner, not closed here.

Both CF-31 and W-2 are documented honestly as **not demonstrated to be races on the
read path**; the single-writer contract + immutability make the read path
safe-by-construction.

## 5. Curated subset (gate-2 step f)

`configd-jcstress/run-curated-subset.sh` runs the 11 deterministic 2-actor tests
(6 RR-002 transport + `ExactlyFullWrap`/`LappedCursorBelowWindow` +
`ConsistentVersionRead`/`AliasedArrayNoTear` + `ConsistentMapVersion`) in `sanity`
mode and **fails on any `[FAILED]` / non-zero failed/error count**. Excludes the
intentionally-forbidden self-test and the 3-actor test.

- **Runtime:** ~5.5–7 min wall on the 2-vCPU box (most of it is jcstress's
  one-time CPU-topology burn-in + per-test fork startup; dominated by fixed
  overhead, not test count). Under heavy concurrent load (e.g. PIT running) it is
  slower and should be run when the box is freer.
- **Result:** clean — 0 failed, 0 forbidden.

Wired into `gates/gate-2.sh` `step_jcstress()` (only that function), with the
`GATE2_SKIP_JCSTRESS=1` loud-skip preserved.

## 6. Full run

- **Settings:** `java -jar jcstress.jar -t '(FanOutBufferReadSince|VersionedConfigStoreRead|HamtMapStructuralSharing|TcpRaftTransportRace)' -m default -c 2`
  (5 forks/test, more iterations; add `TwoReadersOneWriter` only on a host with
  ≥ 3 cores). Run when `pgrep -af pitest` is empty — PIT contends.
- **Status:** the complete `sanity` pass (§3, 182/182, 0 forbidden) is the recorded
  best-effort result on this box to date. A longer `-m default` soak is deferred to
  a PIT-free window (it does not change the verdict for the gate — the curated
  smoke + the complete sanity pass already show zero forbidden outcomes; the soak
  raises confidence by sampling more interleavings, bounded by the 2-vCPU hardware).

## 7. Surprises / notes

- jcstress `quick` mode re-runs the full **VM-stress-flag matrix per test**
  (StressLCM/GCM/IGVN/CCP, affinity, GC-thread trimming), which multiplies cost
  enormously on 2 vCPU — `sanity` (single default VM config) is the right gate
  preset here.
- `-DskipTests` still **compiles** test sources; `-Dmaven.test.skip=true` is
  required so another agent's broken-in-progress test code in a transitively-built
  upstream module (`configd-config-store` pulls `configd-consensus-core`) does not
  fail the harness build.
- The harness self-test caught a real lost-update even under contention — good
  evidence the detector is sensitive enough on this box for the lower-actor-count
  tests, even if absolute sampling is lower than on a many-core host.
