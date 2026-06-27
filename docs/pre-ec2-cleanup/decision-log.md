# Pre-EC2 Cleanup — Decision Log (2026-06-27)

Session goal: get the system **tidy and internally consistent before the (money-gated) EC2 N×knee +
soak + DR measurement** — de-flake CI, ratify the contract-critical ADRs, reconcile stale docs, and
apply the operator's two product decisions (watches → v2; encryption-at-rest → v2 + fix the `secure/`
namespace trap). **No consensus/sharding logic changed. No money spent. No features built** (watches /
encryption are decisions + docs).

Branch `pre-ec2-cleanup` off `origin/main` `777bac5`. Stops at the PR/merge gate.

---

## DL-0 — Branch base: cut off `origin/main` (777bac5), independent of PR #13

PR #13 (Seam G, the N>1 boot switch-flip) is OPEN and stopped at its own merge gate. The cleanup targets
(RehomingInjectedSweepTest in gate-phase0, gate-4 boot, ADR-0030/0032, consistency-contract.md,
known-limitations.md, the `secure/` namespace) **all exist on `777bac5`** and Seam G touched **none** of
them (verified: `git diff 777bac5 HEAD --name-only` ∩ cleanup-targets = ∅). So the cleanup is cut as an
**independent** branch off `777bac5`, not stacked on PR #13 — the operator can merge the two in either
order. (The de-flaked RehomingInjectedSweepTest also benefits PR #13's CI, which hit that exact flake.)

---

## Task 1 — De-flake CI

### DL-1 — `RehomingInjectedSweepTest`: wall-clock-free VERDICT, real threads preserved

**Root cause.** Not a logic bug — a **throttle-sensitive verdict**. The test runs a large real-multi-owner
concurrency workload and gated PASS/FAIL on tight wall-clock budgets: per-task `Future.get(10s)` round-trips
and a `producersDone.await(90s)`. On the 2-vCPU box under **CPU-credit-exhaustion**, a correct ~1.3s run
balloons past those budgets and false-fails (the documented "re-run green" flake). The asserted invariants
(zero `raft_owner_thread` fires, per-group commit growth, no deadlock) are **interleaving-independent** —
only the budgets were fragile.

**Why NOT a deterministic / FIFO scheduler (the brief's first suggestion).** This test's core assertion is
that `raft_owner_thread` **never fires** across rehomes — owner isolation — which is **only meaningful with
genuinely distinct OS owner threads**. A single-thread / FIFO deterministic scheduler would bind every group
to one thread, making the isolation assertion pass **vacuously** and destroying the exact coverage this test
exists for (live multi-owner handoff races the deterministic sim cannot model — stated in the test's own
Javadoc). So the schedule **stays non-deterministic by design**; what is made deterministic is the **verdict**.

**Fix (test-only).** Removed the throughput budgets: per-task `.get(10s)` → unbounded `.get()`;
`producersDone.await(90s)` → `producersDone.await()`. The **sole** wall-clock deadline is now the
method-level `@Timeout(300)` → **`@Timeout(600)`**, a pure DEADLOCK ceiling (a correct run is ~seconds;
600s only fires on a genuine wedge, then with a JUnit thread dump). Cleanup joins kept a generous 60s bound
(a thread ignoring `shutdownNow` interrupt is a real bug, not throttle). Class Javadoc documents all of this.

**Red / green / stability (proof).**
- **Baseline (green):** 1.307s, 853 rehomes, 3959 proposals, `ownerFires=0`.
- **Stability (the core proof):** FIXED, default load, **16 CPU burners (≈credit-exhaustion throttle), 10/10 PASS** (no budget to blow).
- **Red/green contrast (decisive, identical conditions, iters=160000):**
  - **RED — ORIGINAL: FAIL** at test-time **90.36s** — `AssertionFailedError: the injected workload did
    not finish in time (deadlock?)` at `RehomingInjectedSweepTest…runOneSweep:225` (the
    `producersDone.await(90s)` line). A **false** deadlock — the work was progressing, it just needed
    >90s; this is exactly the throttle-flake mode, reproduced via workload instead of throttle.
  - **GREEN — FIXED: PASS** at the same iters=160000 — test-time **162.8s**. It completes the *same*
    workload that failed the original at 90s, precisely because there is no throughput budget — only the
    `@Timeout(600)` deadlock ceiling. (Original FAIL@90.36s vs Fixed PASS@162.8s, identical conditions.)
- Root-cause-confirming datapoint: original passes at iters=70000 in **57.58s** against the **90s** budget — i.e. ~1.6× more work/throttle tips it over; the fix has no such budget (60.01s, PASS).
- **Second-agent replay (independent): PASS.** A fresh agent ran 8/8 (default) + 3/3 (12-burner throttle),
  `ownerFires=0` every run, and reviewed the diff: the change removes only throughput budgets, preserves
  every safety assertion (zero owner-fires ×4 sites, commit growth, non-vacuity), migrates deadlock detection
  to `@Timeout(600)` (+ thread dump — strictly better than the old bare-assert message), and keeps
  owner-isolation non-vacuous (real distinct `configd-raft-owner-*` threads). The throttle evidence (injector
  ~10× slower, `ownerFires` still 0) proves the verdict is throughput-independent — i.e. the fix targets the
  actual root cause.

### DL-2 — gate-4 boot + other flake sweep

**gate-4 boot (`ConfigdServerTest`).** The named gate-4 boot test is `serverStartsAndStopsCleanly`, guarded
by the class-level `@Timeout(10)`. Measured under 16-burner oversubscription: **3/3 PASS** (~28s class time,
dominated by the keytool `@BeforeAll`; the boot *method* stayed ≪ 10s) — i.e. NOT reproducibly flaky at
inducible throttle. But burner-oversubscription does not *reduce* total CPU (the box keeps 2 full cores, just
shared); the documented flake is **credit-exhaustion**, which cuts total CPU to a baseline fraction and would
slow the cold-JVM boot's real CPU work toward the 10s ceiling. **Fix (defensive, evidence-informed):** raised
the class `@Timeout(10) → @Timeout(60)` — generous hang headroom that cannot mask a real hang, matching the
file's established RR-094 method-override precedent; fast wiring tests unaffected, keytool-heavy tests keep
their own larger method `@Timeout`. (No literal "125 ms" boot timeout exists in the code — the memory's figure
was imprecise; the mechanism is the `@Timeout(10)` ceiling vs a throttled cold-JVM boot.)

**Other candidates (assessed; left as-is — not reported flakes, budgets adequate / author-tuned):**
- `ConfigdServerTest` linearizable-read `result.get(150ms)` (lines 539/641): the tests exercise the
  **not-leader fast path** (`readIndex()<0` → completes immediately), so the 150ms is never actually awaited.
- `MultiGroupBringupTest.proposeAndAwaitApply` 5s apply deadline: generous for a single-node
  propose→tick→apply (a few ticks); not a reported flake.
- `BootstrapColdStartTest` 20s self-election deadline: generous (election ~150–300ms); already tolerant.
- `NettyConsensusLivenessTest` 500ms delivery budget: an explicit author-tuned "non-flaky ceiling, ≪ the
  1000ms election floor" perf guardrail — loosening it would weaken the proof; left as-is.

No `@Disabled` / flaky-annotated / "rerun-noted" tests exist beyond `RehomingInjectedSweepTest` (DL-1).

---

## Task 2 — Ratify the Proposed ADRs

### DL-3 — ADR-0030 (Quicksilver topology): Proposed → **Accepted**, with a reality-update note

The **core decision stands** (single region-local Raft root for writes + async bounded-staleness edge
fan-out; reject multi-region/hierarchical Raft *write* consensus; the latency arithmetic is unchanged).
The doc body predates: (a) multi-Raft sharding (built; partitions the root *within* a region — exactly the
"partition the root, not the regions" path the ADR endorsed in Rejected-Alt #6; default N=1, N>1 aggregate
**unmeasured**), (b) the now-WIRED async fan-out (Session 3), (c) the Phase-0 fix of the
`RaftNode`/`ConfigStateMachine` race, (d) ADR-0031's acceptance (the "ratification pending" caveat is
discharged). Rather than rewrite ~700 lines of historical record, a dated **Ratification & reality-update
note** at the top marks it Accepted and reconciles each delta; open residuals (full-region failover →
adr-0024 v2; residency A3; end-to-end propagation perf → EC2) are restated honestly.

### DL-4 — ADR-0032 (linearizability harness): Proposed → **Accepted**

The "STOP for human review before implementation (A3-B)" gate is satisfied: the harness was built and is
CI-wired (`configd-linz` orchestrator + `anishathalye/porcupine` checker; `CheckerSelfTest` gate in
`gate-1.sh`; `consistency-contract.md §7` maps INV-L1 onto it). Marked Accepted with a ratification note;
one reality delta recorded — the wire path is now Netty (ADR-0043 supersedes the `TcpRaftTransport` named in
the Decision).

### DL-5 — Other Proposed ADRs: scanned; 4 **deferred with reason**

Full scan: exactly **6** Proposed ADRs. **0030 + 0032 ratified** (above); **ADR-0031 confirmed already
Accepted** (it was the dependency ADR-0030 cited). The remaining 4 — `adr-multiraft-partitioning`,
`adr-multiraft-topology`, `adr-multiraft-cross-shard`, `adr-throughput-target` (all Session-M1, "awaits
operator sign-off") — are **DEFERRED with reason**: they specify the **N>1 sharding architecture (a v2
capability)**; v1 ships single-group (N=1) and **no v1 claim depends on them**. `adr-throughput-target`
renegotiates §0.1 throughput to a **derived per-shard aggregate** that the **EC2 N×knee measurement will
validate** — premature to ratify before that. Their ratification belongs to the **multi-Raft workstream's
go/no-go**, not this v1 cleanup. (Recorded in register D-1 area + here.)

---

## Task 3 — Reconcile stale docs

### DL-6 — `consistency-contract.md §2 / INV-S2`: staleness is now commit-ts/frontier-based

The §2 "Implementation status" callout + the §7 INV-S1/S2 row described the **old idle-time proxy** ("Today
StalenessTracker measures local idle time … Session 3 makes commit-ts load-bearing … until then"). Reality
(verified in `StalenessTracker.java`): the idle-time proxy was **deleted** (ADR-0039); staleness is measured
against the **covered frontier** = `max(commit_ts(last applied notification), server_now(last cursor-matched
HEARTBEAT))`, commit-ts being the leader-assigned commit/apply timestamp (ADR-0035 §2). Updated to match;
kept honest that the p99 staleness *distribution at scale* (INV-S2) is still owed to the deferred soak.

### DL-7 — `known-limitations.md`: reconciled to the audited register

The file was dated 2026-04-25 (iter-3, pre-S1) and badly stale. Prepended an authoritative **"Current v1
known limitations (2026-06-27)"** section (no at-rest encryption / `secure/` honesty / no secrets; no client
watches → v2; single-group N=1, ~800/s measured knee, N>1 unmeasured; empirical validation deferred — soak
OOM'd at 3.45h, DR drills never run) cross-referenced to the register; preserved the iter-3 text below as a
clearly-marked **historical record** (rather than re-vouching for its drifted specifics).

### DL-8 — `Integration-Guide.md` + `README.md`: user-facing accuracy

Integration-Guide gained an "Important v1 limitations (read first)" section (no watches → polling/v2; no
at-rest encryption / `secure/`=freshness-not-confidentiality / don't store secrets) and a corrected
staleness example (pass the leader commit-ts, not the local clock). README (a bare stub) gained real
content + a status/limitations pointer.

---

## Task 4 — Apply the operator's two product decisions

### DL-9 — Watches → **v2** (documented, not built)

Confirmed absent as a v1 client feature (register §4.8 🟡: `WatchService` is server-internal, zero
production registrants, no HTTP/SSE route/wire frame). Documented as a v1 limitation / v2 feature in
known-limitations, README, Integration-Guide, and register §4.8/§11.8/D-area. Not built.

### DL-10 — Encryption at rest → **v2 (RR-098)**, and the `secure/` trap fixed by **honesty, not rename**

Encryption at rest is absent (no `javax.crypto.Cipher` in `src/main`; values incl. `secure/` keys are
plaintext, integrity-checked only via HMAC, ADR-0042). Operator decision: **v2**. Documented loudly
("Configd does not encrypt at rest in v1; do not store secrets; use a secret manager; RR-098 → v2") in
known-limitations, README, Integration-Guide, consistency-contract §1/§9, and register §8.2/D-2.

**The `secure/` trap — fixed by clarifying semantics, NOT renaming. Rationale:**
- `secure/` is **code-enforced** (`StrongReadKeyClass.DEFAULT_PREFIX`) but for **read FRESHNESS**, not
  encryption: it is the strong-read key class (always-linearizable, fail-closed, never served stale) for
  security-*critical* decisions (ACL/auth revocations, kill-switches, legal gates). It has a **legitimate
  non-encryption meaning** — which the brief says to "document precisely."
- **Renaming the default would be a SAFETY REGRESSION:** the default-protected prefix is `secure/`
  (fail-closed default, ADR-0030 INV-1); changing it would silently drop strong-read (fail-closed)
  protection for any existing `secure/` keys on upgrade. It would also amend an Accepted ADR's INV-1 and
  ripple through ~10 code/test files — the opposite of "least-disruptive."
- **The user-facing trap is fully closed by documentation:** the user-facing docs did not previously
  mention `secure/` at all; the honesty notes now co-locate "freshness, not confidentiality; no at-rest
  encryption; don't store secrets" everywhere `secure/` appears (consistency-contract §1/§9,
  known-limitations, README, Integration-Guide) **and in the code** (`StrongReadKeyClass` +
  `StrongReadPolicy` Javadoc — comment-only, no behavior change).
- A future rename to e.g. `strict/` is offered as an optional v2 product decision, but is not done here.

---

## Register updates (docs/readiness/production-readiness-register.md)

- §11.1: ADR-0030 "formally unratified" → **ratified Accepted**.
- D-1: **RESOLVED** (ADR-0030 + ADR-0032 Accepted).
- D-2 / §8.2: encryption **DECIDED: accept-as-v2** + `secure/`=freshness-not-confidentiality.
- §4.8 / §11.8: watches **DECIDED: v2**.

---

## What did NOT change (guardrails honored)

- **No consensus / sharding / replication logic.** Edits: one test (RehomingInjectedSweepTest budgets),
  doc/markdown, and **comment-only** Javadoc in two source files (StrongReadKeyClass, StrongReadPolicy).
- N=1 and N>1 behavior unchanged. No features built. No EC2 / money. No merge to main.
