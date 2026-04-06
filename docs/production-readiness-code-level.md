# Production-Readiness Certification — Code-Level

**Certifier:** Iter-3 Code-Level Production Hardening Pass (Opus 4.7).
**Date (UTC):** 2026-04-25.
**Build under certification:** working tree at `HEAD = 22d2bf3` plus
the iter-3 diff (uncommitted — see §2 below for the file manifest).
**Reactor evidence:** `./mvnw test` on JDK 25 (Corretto 25.0.2) +
`--enable-preview` → **21,394 tests, 0 failures, 0 errors,
BUILD SUCCESS in ~57 s.** Surefire reports under
`*/target/surefire-reports/TEST-*.xml` are the on-disk artefacts.

> **Scope honesty (read this first):** This certification covers
> **code-level production-readiness only**. Empirical validation
> (load, soak, chaos, burn-in) was explicitly deferred to production
> observation per the user decision recorded in the prompt's
> `<the_tradeoff_being_made>` block. The "Known Limitations" section
> below states explicitly what production observation will reveal.
> See also `docs/known-limitations.md` for the standalone limitation
> manifest and `docs/automation-prerequisites.md` for the
> calendar-bounded gates that remain YELLOW per the prior loop's
> §4.7 honesty invariant.

---

## 1. What this pass changed

Iter-3 was scoped to the highest-priority carry-over from
`docs/loop-state.json` `open_items`: the wire-protocol revision
(W1+W2). Per `docs/iter-3-scope.md`, the other three named carry-overs
(W3 HttpApiServerMetricsTest, W4 ChaosScenariosTest, plus the
~50 v0.2 backlog items) are explicitly out of scope.

Eight rounds of adversarial review converged the code through this
sequence (each pass listed in `docs/code-readiness-state.json`-style
shorthand "S0+S1"):

| Pass | New S0 | New S1 | What was found |
|------|-------|--------|---------------|
| 1 | 3 | 6 | initial bounds-check + handler-dispatch + clamp gaps |
| 2 | 3 | 5 | encoder-side enforcement + decoder ordering + ADR honesty |
| 3 | 2 | 4 | propose-side guard + transport-adapter resilience + decoder validation |
| 4 | 1 | 4 | inflightCount leak from pass-3's defensive swallow |
| 5 | 0 | 0 | first clean pass; 2 S2s recorded |
| 6 | 0 | 1 | CI guardrail referenced but absent (iter-2 doc-vs-reality drift) |
| 7 | 0 | 0 | second clean pass at S0/S1; 4 S2s on CI workflow |
| 8 | 0 | 0 | sealed; 1 S2 on multi-commit-push edge case |

Two consecutive clean passes (7, 8) at S0/S1 seal the iter-3 changes
per the §3 phase-4 rule. The remaining S2s are CI-workflow polish
and explicitly tracked.

---

## 2. File manifest (iter-3 diff)

| File | Δ | Purpose |
|------|---|---------|
| `configd-transport/src/main/java/io/configd/transport/FrameCodec.java` | rewritten | v1 wire format (version byte + CRC32C trailer); public `MAX_FRAME_SIZE`; encoder-side `checkPayloadFitsFrame`; ByteBuffer-overload checks `remaining`; `peekLength` validates length range; validation order length → CRC → version → type |
| `configd-transport/src/main/java/io/configd/transport/TcpRaftTransport.java` | edited | decode-vs-handler exception separation; uses `FrameCodec.MAX_FRAME_SIZE`; structured drop on UnsupportedWireVersionException / IllegalArgumentException |
| `configd-consensus-core/src/main/java/io/configd/raft/InstallSnapshotResponse.java` | extended | new 4th field `lastIncludedIndex` with non-negative compact-ctor invariant |
| `configd-consensus-core/src/main/java/io/configd/raft/RaftNode.java` | edited | `propose()` rejects commands > 1 MiB; 3 InstallSnapshot send sites populate `lastIncludedIndex` with `Math.max(snapshotIndex, lastApplied)`; `handleInstallSnapshotResponse` clamps to `[snapIndex, max(commitIndex, snapshotIndex, lastIndex)]`; `sendAppendEntries`/`sendInstallSnapshot` skip `inflightCount` increment on encoder reject |
| `configd-server/src/main/java/io/configd/server/RaftMessageCodec.java` | extended | bounds checks on all 8 decoders; encoder-side `checkInstallSnapshotFitsFrame` / `checkAppendEntriesFitsFrame`; `MAX_SNAPSHOT_BLOB_LEN=4 MiB`, `MAX_COMMAND_LEN=1 MiB`, `MAX_ENTRIES_PER_APPEND=10000`; decoder validates negative `lastIncludedIndex` + negative `configLen` |
| `configd-server/src/main/java/io/configd/server/RaftTransportAdapter.java` | edited | re-throws encoder IAE so producer can manage `inflightCount` correctly (no swallow at adapter layer) |
| `configd-consensus-core/src/test/java/io/configd/raft/InstallSnapshotTest.java` | edited | 3 call sites updated to 4-arg `InstallSnapshotResponse(.., 0L)` |
| `configd-server/src/test/java/io/configd/server/RaftMessageCodecTest.java` | edited | 1 call site updated; new `lastIncludedIndex` round-trip assertion |
| `configd-transport/src/test/java/io/configd/transport/FrameCodecTest.java` | edited | `frameSizeCalculation` updated to include trailer |
| `configd-transport/src/test/java/io/configd/transport/FrameCodecPropertyTest.java` | new | jqwik fuzz of `FrameCodec` (11 properties × 50–500 tries) — promoted from `.iter3-deferred-tests/` |
| `configd-transport/src/test/java/io/configd/transport/wirecompat/WireCompatGoldenBytesTest.java` | new | 16 dynamic tests pinning `FrameCodec.encode` byte-output against `wire-fixtures/v1/*.bin` — promoted |
| `configd-transport/src/test/java/io/configd/transport/wirecompat/WireFixtureGenerator.java` | new | maintenance tool for fixture regeneration — promoted |
| `configd-transport/src/test/java/io/configd/transport/FrameCodecCrcVectorTest.java` | new | 3 fixed-vector tests; pins CRC32C against a hand-computed reference (independently re-verified by the pass-2 reviewer with pure-Python CRC32C) |
| `configd-transport/src/test/java/io/configd/transport/FrameCodecEncoderBoundsTest.java` | new | 6 tests for encoder-side bounds (added in pass-4 because property tests only covered the decoder) |
| `configd-server/src/test/java/io/configd/server/RaftMessageCodecPropertyTest.java` | new | jqwik fuzz of `RaftMessageCodec` (12 properties × 100–200 tries) — promoted |
| `.github/workflows/ci.yml` | edited | new `wire-compat` job: greps the PR / push diff and fails if `wire-fixtures/v<N>/` bytes change without a matching `WIRE_VERSION` bump (added in pass-6) |
| `docs/decisions/adr-0029-wire-format-v1.md` | new | ADR documenting the v1 layout, validation order, encoder/decoder symmetry, snapshot-cap known limitation, §8.10 fixture-bump CI guardrail with honest scope |
| `docs/iter-3-scope.md` | new | scope-decision document |
| `docs/production-readiness-code-level.md` | new | this file |
| `docs/known-limitations.md` | new | companion file enumerating what production observation will reveal |

Pre-existing iter-1 + iter-2 changes (M files in `git status`) are
unchanged in iter-3 and not enumerated here.

---

## 3. §2 Quality-bar walkthrough

Status legend: ✅ met (this pass directly covers it) · 🟡 partial
(prior pass covers it; iter-3 didn't change) · ⏸️ deferred (carryover
to a separate pass).

### §2.1 Correctness

| Bar item | Status | Evidence |
|----------|--------|----------|
| No TODO/FIXME/XXX in safety-critical paths | 🟡 | iter-1 / iter-2 swept; iter-3 added none |
| Every public method on safety-critical class has ≥ 1 negative test | ✅ | `FrameCodecPropertyTest`, `RaftMessageCodecPropertyTest`, `FrameCodecEncoderBoundsTest` all add negative paths for the iter-3 surface |
| Catch blocks handle / rethrow with context / explain | ✅ | `TcpRaftTransport.handleInboundConnection` decode/handler split with structured logging; `RaftNode.send*` catches IAE with context |
| No empty catch blocks | ✅ | none introduced |
| Null returns documented or replaced with Optional | ✅ | `InstallSnapshotResponse.lastIncludedIndex` is `long`; `Frame.payload` non-null per record contract |
| Primitive boundary checks explicit | ✅ | `RaftMessageCodec` 8 decoder methods + 2 encoder methods + `FrameCodec.checkPayloadFitsFrame` are all explicit; pass-1 found and fixed `BufferUnderflowException` escapes |
| Concurrency primitives correct | 🟡 | `RaftLog` documented single-threaded; `RaftNode` single tick-thread; iter-3 didn't change concurrency |
| equals/hashCode consistent | 🟡 | record types provide both; iter-3 added one record field, default equals/hashCode covers it |
| Serialization formats versioned, deserialization bounded | ✅ | `FrameCodec.WIRE_VERSION = 0x01` plus per-type `MAX_*` bounds in `RaftMessageCodec`; ADR-0029 documents the forward-compat contract |

### §2.2 Error Handling and Resilience

| Bar item | Status | Evidence |
|----------|--------|----------|
| Every external call has explicit timeout | 🟡 | not changed by iter-3; pre-existing `TcpRaftTransport` timeout config |
| Every retry has bound + backoff | 🟡 | pre-existing |
| Every queue has bounded capacity + overflow policy | 🟡 | pre-existing; `MAX_ENTRIES_PER_APPEND=10000` newly enforced at codec layer |
| Cache bounded + eviction | 🟡 | pre-existing |
| Connection pool bounded + exhaustion policy | 🟡 | pre-existing |
| Every dependency has documented failure mode | ✅ | iter-3: ADR-0029 documents wire-version-mismatch / CRC-mismatch / size-cap failure modes and what the operator sees |
| Circuit breakers on cross-service calls | 🟡 | pre-existing; `inflightCount` leak via swallow was a pass-3 introduced regression now fixed at producer boundary |
| Graceful shutdown | 🟡 | pre-existing |

### §2.3 Observability

| Bar item | Status | Evidence |
|----------|--------|----------|
| Every public API call emits RED metric | 🟡 | iter-3 did NOT wire `ConfigdMetrics`/`SafeLog` into `TcpRaftTransport.handleInboundConnection` or `RaftTransportAdapter.send`; explicitly deferred to W3 (HttpApiServer observability pass). Drops still surface to `System.err` with structured exception types but not as Prometheus metrics. **This is a known gap, called out as S2-1 in pass-5 and S1-4 in pass-4.** |
| Resource saturation metrics | 🟡 | pre-existing |
| Every SLI from `docs/ga-review.md` has wired metric | 🟡 | pre-existing; iter-3 did not change SLIs |
| Every log line has correlation ID | 🟡 | pre-existing; iter-3 added `from`/`messageType` context to handler-error log lines but used `System.err` not structured logger |
| Error logs include full exception chain | ✅ | iter-3 `TcpRaftTransport` handler-error path uses `e.printStackTrace(System.err)`; encoder-error logs include exception class + message |
| Cross-service tracing | 🟡 | pre-existing OpenTelemetry stub per ADR-0026 |
| Histograms (HdrHistogram) p50/p99/p999/p9999 | 🟡 | pre-existing |
| Sampling configured | 🟡 | pre-existing |
| No INFO-level on per-request hot path | ✅ | iter-3 added no log calls on the encode/decode hot path |
| No unbounded label cardinality | 🟡 | pre-existing |

### §2.4 Deployability and Reversibility

| Bar item | Status | Evidence |
|----------|--------|----------|
| Every DB migration has undo | n/a | configd has no DB migrations |
| Wire-protocol change behind version negotiation | ⏸️ | v1 is the *first* shipped wire format; the version byte exists so v2 can negotiate (ADR-0029 §"Forward-compat contract for v2"). v1 itself is a strict-equality tripwire — no backwards compat with the never-shipped v0 header-only format. Honest. |
| Risky changes behind feature flags | 🟡 | iter-3 wire format is not flagged — it is the v0.1 wire format, period. The version byte IS the future-proofing mechanism |
| Kill switches per traffic class + tested | 🟡 | pre-existing |
| Rolling deploy preserves availability | ⏸️ | calendar-bounded gate; iter-3 cannot validate without real cluster |
| Rollback automated and rehearsed in CI | ⏸️ | calendar-bounded gate |
| Configuration loading precedence | 🟡 | pre-existing |
| Secrets via secret manager | 🟡 | pre-existing |

### §2.5 Security at the Code Level

| Bar item | Status | Evidence |
|----------|--------|----------|
| mTLS enforced on every inter-service connection | 🟡 | pre-existing |
| Every external input validated | ✅ | iter-3: every wire-decode path now has explicit bounds checks (`checkRemaining`, `checkBlobLen`, `checkPayloadFitsFrame`); negative-length and oversize-blob attacks blocked at framing AND application layer |
| Authorization at boundary | 🟡 | pre-existing |
| No `Runtime.exec` / `ProcessBuilder` with user input | ✅ | iter-3 introduced none |
| No reflection on user input | ✅ | iter-3 introduced none |
| No untrusted Java serialization | ✅ | typed binary codec only; iter-3 hardened with version + checksum |
| Audit log append-only with hash chain | 🟡 | pre-existing per `S8` (RED, deferred to v0.2) |
| Secret rotation coded | 🟡 | pre-existing |

### §2.6 Test Quality

| Bar item | Status | Evidence |
|----------|--------|----------|
| 90 % line coverage on safety-critical modules | ⏸️ | not measured this pass; reactor pass-rate is 100 % but coverage instrumentation requires a separate jacoco run |
| 85 % branch coverage | ⏸️ | same |
| Property tests for every consistency invariant | 🟡 | iter-3 added jqwik for `FrameCodec` and `RaftMessageCodec` codec invariants; consensus invariants pre-existing in `SnapshotInstallSpecReplayerTest` etc. |
| ≥ 10⁵ deterministic-simulation seeds in CI | 🟡 | pre-existing `SeedSweepTest` at 10⁴ in CI per `ci.yml` |
| ≥ 75 % mutation score on consensus-core/config-store | ⏸️ | not measured this pass |
| Every regression test references its issue/PR | 🟡 | iter-1/iter-2 conformance; iter-3 added new tests, not regression tests |
| Flake rate < 0.5 % over last 100 runs | ⏸️ | not measured |

### §2.7 Documentation

| Bar item | Status | Evidence |
|----------|--------|----------|
| Module README exists | 🟡 | pre-existing |
| Public API has Javadoc | ✅ | every new/changed public symbol has Javadoc; `FrameCodec.WIRE_VERSION`, `MAX_FRAME_SIZE`, `UnsupportedWireVersionException`, `Frame`, `encode`, `decode`, `peekLength`, `frameSize`, `checkPayloadFitsFrame` all documented; `RaftMessageCodec` updated for new `lastIncludedIndex` payload format and 3 new public size constants |
| `docs/production-readiness-code-level.md` exists | ✅ | this file |
| `docs/known-limitations.md` exists | ✅ | companion file |
| Runbooks per alert | 🟡 | pre-existing |

---

## 4. Closed iter-3 findings (with adversarial review trail)

| Pass | Finding | Disposition |
|------|---------|-------------|
| P1 S0-1 | TcpRaftTransport silently swallows decode RuntimeExceptions | Fixed — handler-error caught separately and logged with class + message + stack |
| P1 S0-2 | 5 sister decoders missed bounds checks | Fixed — `checkRemaining` applied to all 8 decoders |
| P1 S0-3 | `MAX_FRAME_SIZE < 2 × MAX_SNAPSHOT_BLOB_LEN` arithmetic | Fixed — `MAX_SNAPSHOT_BLOB_LEN` lowered to 4 MiB; `checkInstallSnapshotFitsFrame` added |
| P1 S1-1 | `lastIncludedIndex` echo unused by leader | Fixed — `handleInstallSnapshotResponse` clamps + uses it |
| P1 S1-2 | `log.lastApplied()` insufficient on snapshot reject | Fixed — `Math.max(snapshotIndex, lastApplied)` |
| P1 S1-3 | Frame record drops version, docstring overpromises | Fixed — docstring updated to honest "tripwire until v2 Hello handshake" |
| P1 S1-4 | `peekLength` unvalidated | Fixed — bounds-check inside |
| P1 S1-5 | No external-CRC fixed-vector test | Fixed — `FrameCodecCrcVectorTest` |
| P2 S0-1 | Leader clamp regresses to 0 on cold-start | Fixed — clamp upper bound `max(commitIndex, snapshotIndex, lastIndex)` |
| P2 S0-2 | `MAX_SNAPSHOT_BLOB_LEN=8` math wrong | Fixed — lowered to 4 MiB |
| P2 S0-3 | Encoder lacks MAX_FRAME_SIZE enforcement | Fixed — `checkPayloadFitsFrame` |
| P2 S1-1 | TcpRaftTransport catch-all swallows handler errors | Fixed — handler dispatch separated from decode |
| P2 S1-2 | Stale javadoc on InstallSnapshotResponse format | Fixed |
| P2 S1-3 | WireFixtureGenerator doesn't exercise RaftMessageCodec | Documented as known gap in ADR-0029 §8.10 |
| P2 S1-4 | MAX_FRAME_SIZE constant duplicated | Fixed — public constant referenced from TcpRaftTransport |
| P2 S1-5 | InstallSnapshotResponse no negative-index validation | Fixed — compact-ctor validates |
| P3 S0-1 | propose() no command-size guard | Fixed — `> 1 MiB` rejected at API boundary |
| P3 S0-2 | sendInstallSnapshot kills broadcast on encoder reject | Fixed — RaftTransportAdapter re-throws; RaftNode.send* catches and skips inflight increment |
| P3 S1-1 | RaftTransportAdapter.send doesn't catch encoder | Fixed at producer (RaftNode) instead |
| P3 S1-3 | Decoder doesn't validate negative lastIncludedIndex | Fixed — explicit check before record ctor |
| P3 S1-4 | Snapshot cap drop undocumented | Fixed — ADR-0029 §"Known limitation" added |
| P4 S0-1 | inflightCount leak from pass-3 swallow | Fixed — adapter no longer swallows; producer manages tracking |
| P4 S1-1 | propose() MAX_COMMAND_LEN duplicated as literal | Documented as "iter-4 cleanup" (cross-module constant requires configd-common touch) |
| P4 S1-2 | No encoder-side property tests | Fixed — `FrameCodecEncoderBoundsTest` |
| P4 S1-3 | ByteBuffer-overload partial write on small buf | Fixed — `remaining` check |
| P4 S1-4 | RaftTransportAdapter swallow no metric | Documented (W3 carryover); structured stderr in interim |
| P5 S2-2 | CRC ordering vs version mismatch | Fixed — CRC now precedes version check |
| P6 S1-1 | wire-compat CI guardrail referenced but absent | Fixed — `.github/workflows/ci.yml` `wire-compat` job added |
| P7 S2-1..4 | CI workflow polish (shallow fetch, two-tree diff, push gating, doc drift) | Fixed |
| P8 S2-1 | Multi-commit push edge case | Fixed — uses `${{ github.event.before }}` |

---

## 5. Open carryovers (NOT fixed in this pass; explicitly deferred)

| ID | Carryover | Reason for deferral |
|----|-----------|--------------------|
| W3 | HttpApiServerMetricsTest — thread MetricsRegistry/SloTracker through HttpApiServer read path | Requires API-shape change in HttpApiServer; separate observability pass. Test file remains in `.iter3-deferred-tests/` |
| W4 | ChaosScenariosTest — extend SimulatedNetwork chaos APIs (addLinkSlowness, freezeNode, simulateTlsReload, clearChaos, setDropEveryNth) | Independent of wire format; separate chaos-infra pass. Test file remains in `.iter3-deferred-tests/` |
| W5 (new) | ConfigdMetrics + SafeLog wired into TcpRaftTransport / RaftTransportAdapter for drop counters | Pass-4 S1-4 / pass-5 S2-1; observability pass |
| W6 (new) | `RaftMessageCodecGoldenBytesTest` — per-MessageType payload fixtures pinning the application-layer byte layout, mirroring what `WireCompatGoldenBytesTest` does for the FrameCodec layer | Pass-2 S1-3; described in ADR-0029 §8.10 "Known gap"; separate codec-coverage pass |
| W7 (new) | Cross-module constant for MAX_COMMAND_LEN (currently duplicated literal in `RaftNode.propose` and `RaftMessageCodec`) | Pass-4 S1-1; configd-common touch |
| W8 (new) | Snapshot chunking via offset/done fields | ADR-0029 "Known limitation"; v0.2 |
| All v0.2 backlog | ~50 items per `loop-state.json` `open_items` | Pre-existing residual; iter-3 did not change |
| Calendar-bounded gates (C1–C4, drills, on-call) | per §4.7 honesty invariant | not loop-promotable; tracked in `docs/automation-prerequisites.md` |
| Type C human signoff (`docs/ga-approval.md`) | per §4.7 honesty invariant | human gate by design |

---

## 6. Known Limitations — what production observation will reveal

This pass is a **code-level** certification. The following are
explicitly NOT validated and will surface during the first 30 days
of production:

1. **Performance characteristics under sustained load.** No JMH or
   soak run was executed. The CRC32C overhead is bounded
   theoretically (~0.5 ns/byte hardware-accelerated) but unmeasured
   on your traffic mix. The new bounds checks are O(1) per call
   but were not benched.
2. **Fault recovery under real partitions.** The new `lastIncludedIndex`
   leader-side use, the InstallSnapshot reject paths echoing
   `Math.max(snapshotIndex, lastApplied)`, and the
   `RaftTransportAdapter` defense-in-depth catch are exercised by
   unit tests only — no real partition / network failure / disk
   stall has tested the paths.
3. **Snapshot blob 4 MiB cap (ADR-0029 §"Known limitation").** If
   `ConfigStateMachine.snapshot()` produces > 4 MiB, the affected
   follower is permanently blocked from snapshot bootstrap until
   v0.2 lands chunked install. The fix today is "tune snapshot
   frequency policy so state stays under 4 MiB at snapshot time" —
   no metric exports the size to alert on this. **Operators must
   monitor `latestSnapshot.data().length` indirectly via
   per-follower `matchIndex` lag against leader `commitIndex`.**
4. **Wire-version mismatch alerting.** v1 is the first version, so
   no v0/v1 mixed traffic exists. If a future v2 ships without
   ADR-0030+ Hello handshake support, a rolling upgrade will see
   `UnsupportedWireVersionException` at the receiver — currently
   logged to `System.err`, not to a metric. **No alert fires.**
5. **Multi-MiB AppendEntries throughput.** Default `maxBatchBytes =
   256 KB` is far below the codec's 1 MiB per-entry cap; throughput
   under per-entry sizes approaching the cap is unknown.
6. **CI guardrail effectiveness.** The new `wire-compat` CI job
   protects against fixture-bump-without-version-bump. It has not
   been triggered by a real PR yet; the regex against
   `WIRE_VERSION` declaration is brittle to formatting drift
   (e.g., line wrap, modifier reordering).

---

## 7. First 30 Days operating plan

Concrete actions for the on-call rotation during the v0.1 burn-in
window:

1. **Heightened alerting for new failure modes.** Add stderr scrapers
   (or kubectl-logs alerts) for these substrings:
   - `"Inbound wire-version mismatch"` (FrameCodec rejection — investigate version drift)
   - `"Inbound frame decode failure"` (CRC or framing — investigate hardware / corruption)
   - `"Dropping AppendEntries to ... (codec rejected)"` (oversized command — investigate proposer)
   - `"Dropping InstallSnapshot to ... (codec rejected — snapshot too large for v1 wire)"` (snapshot > 4 MiB — investigate state-machine growth)
   - `"Inbound handler error from peer"` (consensus / state-machine fault — investigate stack trace)
2. **Daily error-budget review.** For 30 days, the on-call lead reviews:
   - Per-follower `matchIndex` lag against leader `commitIndex`
   - Count of stderr lines matching the patterns in (1)
   - Reactor test pass rate on the latest commit to `main`
3. **No concurrent feature deploys.** During the 30-day window, only
   bug fixes / observability wiring / W3/W4/W5 carryover work merges
   to `main`. Larger features wait.
4. **Predefined rollback triggers.** Roll back v0.1 → previous build
   if any of:
   - > 1 % of follower heartbeats see decode-failure stderr lines
   - Any follower's snapshot install fails > 3 times consecutively
     (indicates the 4 MiB cap is being hit in production)
   - Sustained `matchIndex` lag > 1000 entries on any follower
     for > 5 min (indicates a poisoned log entry the encoder rejects
     on every retry)
   - Test-suite flake rate > 1 % over a 24 h window
5. **Named on-call rotation.** Per `docs/operator-runsheet.md` step 7,
   the on-call rotation is a Type C residual that must be filled
   before signing `docs/ga-approval.md`. Iter-3 does not change
   this. The 30-day burn-in cannot start until R-12 has named
   humans on a paging schedule.

---

## 8. Honesty checks

- **Numbers in this document are real measurements.** 21,394 / 0F /
  0E / ~57 s came from the latest `./mvnw test` after `rm -rf
  */target/surefire-reports`. The breakdown 21,346 (clean baseline)
  + 39 (initial iter-3 promoted tests) + 9 (CRC-ordering swap +
  encoder-bounds tests) = 21,394 is self-consistent.
- **No Type B (calendar-bounded) gate has been flipped to GREEN by
  this pass.** All YELLOW gates from `docs/ga-review.md` remain
  YELLOW; iter-3 did not change them. Per §4.7 honesty invariant.
- **No Type C (human-required) gate has been flipped to GREEN.**
  `docs/ga-approval.md` remains unsigned.
- **The adversarial review trail (8 passes) is the verification of
  this certification's "code-level production-readiness" claim.**
  Two consecutive clean passes (7, 8) per §3 phase-4 sealed iter-3
  at the S0/S1 level. S2s recorded here are honest about what
  remains to polish.
- **Iter-3 did not redo prior audit passes.** PRR / certification /
  audit / battle-ready / iter-1 / iter-2 work stands as it was on
  2026-04-19. This certification builds on that work; it does not
  replace it.

---

## 9. What this certification authorizes

This document certifies that the **iter-3 wire-protocol revision
(W1+W2)** is code-level production-ready. It does NOT:

- Authorize sign-off on `docs/ga-approval.md` (human gate, separate).
- Validate the v0.1 system under real load, real chaos, or real cert
  rotation (calendar-bounded gates, separate; per
  `docs/automation-prerequisites.md`).
- Promote any Type B or Type C gate to GREEN.
- Replace the operator's responsibility to wire the §7 alert
  scrapers / dashboards / on-call rotation before promoting v0.1.

What it DOES:

- Records that the wire-protocol v1 implementation, the
  InstallSnapshot follower-progress field, and the surrounding
  bounds-check / resilience changes have passed eight rounds of
  adversarial review with two consecutive clean passes at the
  S0/S1 level.
- Provides the file manifest (§2), the closed-finding trail (§4),
  the open-carryover register (§5), the known-limitations summary
  (§6), and the first-30-days operating plan (§7) needed for an
  approver to make an informed promotion decision.
- Establishes that the adversarial review process used in this
  pass is itself a useful artefact: the "iter-3 closed eight
  pass-trail" reproduces if a future iteration repeats the §3
  phase-4 protocol.
