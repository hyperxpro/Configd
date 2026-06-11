# Review — A5 batch second-agent verification (RR-006, RR-020, RR-018, RR-015, RR-031)

- **Reviewer:** review-architect (Session 2)
- **Date:** 2026-06-11
- **Scope:** commits `9905d9c` (RR-006), `8b4b0b1` (RR-020), `a207419` (RR-018), `2a1830b`
  (contract pass: RR-015/RR-031). Five P1 rows flipped to RESOLVED pending this verification.

---

## Per-row verdicts

| Row | Verdict | Stays RESOLVED? |
|---|---|---|
| RR-006 (10× timing) | **APPROVE** | Yes |
| RR-020 (fail-closed strong reads) | **APPROVE — no bypass found** | Yes |
| RR-018 (reconfig de-vacuation) | **APPROVE with a non-blocking finding** | Yes |
| RR-015 (HLC reconciliation) | **APPROVE** | Yes |
| RR-031 (contract sweep) | **APPROVE** | Yes |

No blocking findings. One material non-blocking finding on RR-018 (a test whose name overstates
what it exercises) and one hardening recommendation on RR-020.

---

## RR-020 (fail-closed strong reads) — HARDEST SCRUTINY. **No bypass found.**

The headline risk was whether the `secure/`-prefix strong-read class can be **bypassed** so a key
that ought to be linearizable+fail-closed reaches the local stale-read path. I traced every vector.

**Key extraction is uniform and decode-before-check.** `ConfigHandler.handle` extracts
`key = path.substring("/v1/config/".length())` from `exchange.getRequestURI().getPath()`, and
**every** verb (`handleGet` classification, `handleGet` read, `handlePut`, `handleDelete`) uses that
**same** string. There is no `URLDecoder`, no `.normalize()`, no `toLowerCase`, no `getRawPath` in
the server (grepped). `URI.getPath()` **percent-decodes** (verified empirically:
`/v1/config/%73ecure/x` → `getPath()` = `/v1/config/secure/x`), so the prefix check runs on the
DECODED key — the same key the store resolves.

Vector-by-vector (all on the bench JDK 25):
- **URL-encoding `%73ecure/x`** → decoded `secure/x` → `isStrongReadKey` TRUE → fail-closed enforced.
  The decode happens *before* the check, so encoding makes evasion **harder**, not easier. SAFE.
- **`secure%2Fx`** (`%2F`→`/`) → decoded `secure/x` → TRUE. Collapses to the same key; both strong. SAFE.
- **Case `Secure/x`** → not strong (case-sensitive `startsWith`), BUT the store is case-sensitive too,
  so `Secure/x` is a *different* key than `secure/x` → 404. **No value of a strong key leaks.**
- **Traversal `secure/../x`** → `getPath()` does NOT collapse `..` → key `secure/../x` →
  `startsWith("secure/")` TRUE → strong, fail-closed. SAFE.
- **`//secure/x`** → key `/secure/x` → not strong, but a *different* stored key → 404. No leak.
- **Query-string interference** → the key comes solely from the path; `getQuery()` is read only to
  detect `consistency=linearizable`. A query cannot supply or alter the key. SAFE.

The invariant that makes all of these safe: **classification key ≡ store-resolution key** (both are
the byte-identical decoded `getPath()` substring). There is no code path where the string used to
decide "is this strong?" differs from the string used to fetch the value, so you cannot read a
strong key's value under a non-strong classification.

**Fail-closed on every exception path — structurally guaranteed.** In `handleGet`, the
`configStore.get(key)` local read sits in the `else` branch reached **only** when `strongReadKey ==
false`. A strong key takes one of: `readService == null` → `failClosed`+return; `linearizableRead`
returns null → `failClosed`+return; or a confirmed non-null result. **There is no try/catch in
`handle`/`handleGet`** and no top-level Filter, so if `linearizableRead`/`confirmLeadership` *throws*,
it propagates out of the handler (HttpServer closes with 500/empty) — it never falls through to local
state. A strong key can never reach `configStore.get`.

**503 distinguishability + X-Leader-Hint — correct.** `failClosed` sets `X-Fail-Closed: strong-read`
(distinguishes the denial from an ordinary 503), `X-Leader-Hint` when a leader is known, a body
naming `ADR-0030 INV-1`, and never echoes the local value. `StrongReadFailClosedTest` pins all of
this (follower hint = "3", value "DENY" never in the body). The non-strong explicit-linearizable
path correctly returns a plain 503 "Not Leader" (a stale read of a non-strong key is
contract-permitted, so it does not fail-closed-deny). Policy ctor rejects blank prefixes (would match
every key) and empty-set disables enforcement — both tested.

**Re-run:** `StrongReadFailClosedTest` **9/9 green (8.31 s)**.

**Hardening recommendation (non-blocking):** the suite does not pin the encoded-key behavior. Since
the decode-before-check safety depends on JDK `getPath()` semantics, add a regression test asserting
`/v1/config/%73ecure/killswitch` is classified strong (fail-closed on a follower) and
`/v1/config/Secure/killswitch` 404s — to lock the property against a future change that introduces a
raw-path read or a normalization step. Not required for the flip; the current behavior is safe.

## RR-006 (10× timing) — APPROVE

**Conversion is correct at the boundaries.** `RaftConfig.toTicks(ms) = max(1, round(ms /
tickPeriodMs))` (round-to-nearest, floor 1). At `tickPeriodMs=10`: election 150→15 / 300→30,
heartbeat 50→5 — the documented [15,30] tick window and 5-tick heartbeat. At `tickPeriodMs=1`
(sim/identity): 150/300/50 → 150/300/50 ticks, byte-identical to pre-fix. The compact ctor validates
`tickPeriodMs > 0` AND the **derived** `electionMinTicks >= heartbeatTicks * 3`
(`MIN_ELECTION_HEARTBEAT_TICK_RATIO`): at 10 ms, `15 >= 5*3` (exactly 3, passes); a too-coarse period
(e.g. 100 ms → heartbeatTicks `round(0.5)`=1, electionMinTicks `round(1.5)`=2, `2 >= 3` false) is
**rejected at construction** — the heartbeat-below-tick-period case fails closed. `RaftNode` caches
the three derived tick counts in its ctor (`:202-204`) and uses them in `resetElectionTimeout`
(`:2039`) and `tickHeartbeat` (`:1050`); it never reads a `...Ms` field as a tick count.
`ConfigdServer` builds production config via `RaftConfig.of(nodeId, peers, TICK_PERIOD_MS=10)`
(`:220`), and the scheduler runs at the same 10 ms (`:596`).

**Sim byte-identity confirmed.** The testkit uses `RaftConfig.of(nodeId, peers)` (1 ms identity), so
schedules are unchanged. `SimulationDeterminismTest` **2/2 green** (re-ran despite the concurrent
testkit work).

**Pre-fix capture consistent.** 217 ticks × 10 ms = 2.17 s realized election timeout, matching the
live smoke-test ~2.3 s and inside the buggy 1.5–3.0 s window; the unit test asserts the post-fix
target lands in [15,30] ticks. `RaftNodeTest$TimingConversionTests` **4/4 green**.

**Live drill (second-agent re-run).** `rr-006-reelection-drill.sh`, jar verified via `javap` to
contain the derived-tick accessors. First attempt failed at the readiness gate (a startup flake on
this CPU-credit-throttled 2-vCPU box — a manual launch confirmed the cluster *does* elect a leader,
node 3 = 200); the **second run passed**: kill leader (node 1) → new leader node 3 →
**re-election (kill → first commit-confirmed write) = 0.575 s**. This is well under the pre-fix
~2.3 s and proves the 10× fix. It is somewhat above the capture's 0.235–0.317 s; the gap is
throttling + that the drill measures the full kill→re-elect→**commit-confirmed write** cycle (not
just the election) at ~1 s poll granularity. Honest measured number recorded; the fix is proven.

## RR-018 (reconfig de-vacuation) — APPROVE with a non-blocking finding

**The de-vacuation is genuine and the mutant-kill is real.** I read the OLD vs NEW bodies: F-C3
(`rejectsConfigChangeBeforeNoopCommitted`) now drives node 1 to LEADER *without* delivering the
no-op responses, asserts `proposeConfigChange` returns FALSE and the config stays non-joint while
`noopCommittedInCurrentTerm == false`, then delivers the no-op and asserts the same call now
SUCCEEDS — genuinely pinning the no-op-commit gate (the old body asserted the *opposite* of its
name). F-C2 (`configChangePreservedAcrossElections`) now does a real `{1,2,3}→{1,2,3,4}` RCFG
through joint→final, forces a leadership change, and asserts the new leader still serves the
4-voter config — the old body only checked `commitIndex >= 2` after a normal command.
`completesJointToFinalTransition...` is a real first-completed-transition test (C_old,new commits →
auto-append C_new → C_new commits → simple 4-voter; all followers incl. n4 converge).
`recomputeConfigFromLogRestoresMembershipAcrossRestart` **genuinely** exercises recovery: `restartNode`
rebuilds with `RaftConfig.of(id, staticPeers.get(id))` (the original 3-node static peers) over the
retained WAL, so discovering n4 as a voter can only come from `recomputeConfigFromLog`. The
discrimination claim holds: reverting the `isConfigChangeEntry` guard (`→ false`) breaks finalization
on all three end-to-end tests (config stays JOINT forever) while the old vacuous bodies stay green —
a mutant the old tests structurally cannot see. **`ReconfigurationTest` 11/11 green.**

**Non-blocking finding — `leaderElectionDuringJointPhaseStillCompletesTheChange` overstates what it
exercises.** The test name and the de-vacuation capture claim "an election happens after C_old,new
commits but **before finalization**" and "the new leader (dual-majority) **completes** the
transition." I instrumented the exact setup: after `deliverAllMessages(30)` at the pre-election point
(test line 556), **`leader.clusterConfig().isJoint() == false`** and `voters == {1,2,3,4}` — the
transition has **already finalized** before the leadership change. (This is consistent with test 1,
which uses the same `deliverAllMessages(30)` and asserts finalization at line 514.) The test does NOT
assert `isJoint()` at the moment of isolation, so it does not pin the "during joint phase" premise;
the new leader inherits an already-completed config rather than *completing* a mid-joint transition.
This does **not** demote RR-018 — the two named de-vacuations, the completed-transition test, the
restart/recovery test, and the mutant-kill are all genuine — but the specific mid-joint-election
claim is not actually exercised. **Recommendation:** insert `assertTrue(leader.clusterConfig().isJoint())`
immediately before `dropAllMessages()`, and bound the pre-election delivery so C_old,new commits but
C_new does not (so the election genuinely lands inside the joint phase). Route to the RR-018 owner;
the row stays RESOLVED with this qualification recorded.

## RR-015 / RR-031 (contract reconciliation pass) — APPROVE

Diffed `consistency-contract.md` against ADR-0033 and ADR-0035 (both Accepted). Every change is
faithful and I found **no silent guarantee-weakening**.

- **ADR-0033 (faithful):** §1's new write-ack taxonomy table (Committed→200 / NotLeader→503+hint /
  Lost→503+hint / Indeterminate→504 / Overloaded→429 / Validation→400; "200 only on commit") matches
  the implemented `HttpApiServer` sealed-switch I verified in the RR-004 review. The new §1
  GLOBAL/strong-read paragraph matches RR-020 as implemented.
- **ADR-0035 (faithful):** §2 staleness redefinition (`edge_wall_now − commit_ts`, no per-entry HLC,
  NTP ≤50 ms residual), INV-S1 rewording, §4 seq→applied-mutation (skips no-op/RCFG), §4 HLC bullet
  removed + table footnote, §5.3 cross-group→N/A (ADR-0030), INV-W2 hlc conjunct dropped, INV-V1/V2
  over the mutation stream, §9 row — all match ADR-0035's PATCH PLAN verbatim. ADR-0004 noted
  "amended, not superseded."
- **§2 bounds NOT weakened.** INV-S2 (p99 < 500 ms, p9999 < 2 s) and the STALE/DEGRADED/DISCONNECTED
  thresholds are **kept as contract targets**, with an explicit "Session 3 implements the
  measurement" callout and "the bounds below are the contract targets" marker — exactly the
  required S3-implements posture, not a deletion. The §3/§6 RYW change (cursor-behind → immediate
  `NOT_FOUND`, no blocking `ryw_timeout`) is a **correction to match code** (CM-017/CM-041), not a
  weakening — it documents actual `LocalConfigStore.get(key, cursor)` behavior and flags the blocking
  variant as a future enhancement.
- **§8 assertion table matches code (spot-checked all names):** `durable_prefix_no_gap` at
  `RaftNode.java:257` (ctor) + `:1655` (applyCommitted) — exact line match; `apply_owner_thread` at
  `ConfigStateMachine.java:280` (+ `onApplyOwnerThreadViolation` `:279`) — exact; `version_monotonicity`
  at `RaftNode.java:1675`; `per_key_order` at `ConfigStateMachine.java:315`; metric prefix
  `invariant.violation.` at `InvariantMonitor.java:43`; the removed `assert_sequence_monotonic`/
  `assert_sequence_gap_free` rows correctly reflect the A2 vacuity removal; `AssertionError` (not the
  fictional `InvariantViolationException`) per CM-058. All accurate.
- **§7 INV-L1 row matches `linz-plan.md` §5** essentially verbatim: `configd-linz` + Porcupine
  (`anishathalye/porcupine`), separate-JVM cluster over real `TcpRaftTransport`, OS faults (iptables
  REJECT + kill -9), per-key checker-neutral history, ack≠commit / 200⇒`:ok`, NOT Wing&Gong, real
  binary `HarnessMain`→`porcupine-check`, gates `CheckerSelfTest`/`run-discrimination.sh`/
  `run-gate.sh`. The INV-S1/S2 row correction (threshold transitions today, p99 owed to S3) matches
  ADR-0035's handoff.

---

## Re-run evidence (second-agent verification)

After `install -DskipTests` of consensus-core + server deps; runs kept targeted (a concurrent agent
is building configd-testkit). No testkit files touched.

- `RaftNodeTest$TimingConversionTests` (RR-006): **4/4, 0.64 s.**
- `SimulationDeterminismTest` (RR-006 byte-identity): **2/2, 1.76 s.**
- `rr-006-reelection-drill.sh` (RR-006 live): **re-election 0.575 s** (2nd attempt; 1st a startup
  flake under CPU throttling — cluster verified to elect on manual launch). Self-cleans its PIDs.
- `StrongReadFailClosedTest` (RR-020): **9/9, 8.31 s.**
- `ReconfigurationTest` (RR-018): **11/11, ~0.7 s.**

All five rows stay RESOLVED. RR-018 carries the qualification above (mid-joint-election claim not
pinned); RR-020 carries the encoded-key regression-test recommendation. Both are non-blocking,
routed to their owners.
