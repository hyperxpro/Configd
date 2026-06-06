# READINESS LEDGER — Production-Readiness (post-assessment)

> **This is the live ledger every session reads first and appends to last.** It is the single
> source of truth for *current* component truth + risk status, and an append-only history of what
> each session changed and proved.
>
> **Not to be confused with** `docs/progress.md` — that is the *superseded* pre-assessment
> "Autonomous GA-Hardening Progress Log" (2026-04-17). It is contaminated by the exact claims this
> effort is correcting (e.g. it quotes "21,285 tests"; the live count is **21,394**). Do not read
> it as evidence and do not extend it. This file (`docs/READINESS-LEDGER.md`) replaces it as the
> live ledger.
>
> **Grounding baseline:** `docs/STATE-OF-REALITY.md` (2026-06-06) + the four per-lens findings in
> `verification-runs/state-of-reality/`. Those are read-only forensic evidence; this file tracks
> change *from* that baseline.
>
> **Plan of record:** `PRODUCTION-READINESS-PLAN.md` (repo root).

**Last updated:** 2026-06-06 — Session A1 (R-01 Raft event-loop serialization) on branch
`session-a1-raft-race`, committed pre-merge for review. First code change of the effort.

---

## 0. Rules of this ledger (the contract every session inherits)

**Evidence rubric — exactly one literal classification on every claim, no exceptions:**

| Literal | Meaning |
|---|---|
| `[VERIFIED-PASS]` | I ran it; here is the command + output. |
| `[VERIFIED-FAIL]` | I ran it; here is the failing/contradicting output. |
| `[EXISTS-UNTESTED]` | Present in code, but I could not run/trigger it. |
| `[DOC-ONLY]` | Described in docs/comments; no implementing code. |
| `[ABSENT]` | Claimed/expected; does not exist. |

**Forbidden:** aspirational language ("will", "is designed to", "should work"); trusting a doc
claim without reading the code; marking any gate passed without a pasted command + output.

**The recurring failure mode of this project — name it when you see it:**
> A **verified-but-untested-integration seam** — a correct component wired into the running system
> in an unsafe or unverified way, so it *looks* done. The canonical instance is the formally
> verified single-threaded `RaftNode` driven concurrently by the server (Risk R-01). Whenever you
> find another (a verified/real unit invoked across threads without marshalling, or glued together
> only in test code), **log it as a new finding here**, classified.

**How to append (every session, on finish):**
1. Add a dated entry to **§4 Session log** — what changed, the exit-gate command(s) + pasted
   output, and a re-classification (rubric) of every component touched.
2. Update the affected rows in **§2 Component status** and **§3 Risk register** in place.
3. If you discovered a new verified-but-untested-integration seam, add it to §3 as a new R-row.

---

## 1. Where we are (one paragraph, honest)

Roughly half the system is real and wired; the other half is real-but-orphaned library code or
pure paper, and the verification narrative oversells both. The single-node control plane (full
Raft *algorithm*, lock-free MVCC store, CRC32C TCP+TLS transport, JDK-HTTP API, wired
observability) is genuinely real and tests green (`./mvnw -fae test` → 21,394 tests, 0 fail, 2
skipped; `ConsensusSpec` TLC green live). The advertised "globally distributed edge data plane" is
largely paper: one Raft group is registered, the Plumtree/HyParView fan-out is never invoked
(`FanOutBuffer` is appended to and never drained), and no wire path connects control plane to edge.
The most dangerous single defect was a **verified-but-untested-integration seam**: the verified Raft
was driven thread-unsafely by the server, with no test exercising it (Risk **R-01** — now **CLOSED**
in Session A1: all node access serialized onto the single tick thread). Full detail:
`docs/STATE-OF-REALITY.md`.

**Real-vs-paper split (baseline estimate):** ~55–60% real & wired · ~20% real-but-orphaned ·
~20–25% pure paper.

---

## 2. Component status (current truth — update rows in place)

> Initialized 2026-06-06 from `STATE-OF-REALITY.md §2`. "Baseline" = state at assessment;
> "Current" diverges as sessions land. Until a session changes a row, Current = Baseline.

| Component | Current classification | Baseline (2026-06-06) | Owning session(s) |
|---|---|---|---|
| consensus — Raft algorithm | `[VERIFIED-PASS]` (single-group) | same | — (solid; do not re-litigate) |
| consensus — runtime invariant enforcement | `[VERIFIED-FAIL]` (NOOP in prod) | same | A2 |
| config-store (MVCC) | `[VERIFIED-PASS]` | same | A4 (latent R-1/W-1/W-2 hardening) |
| edge-cache | `[EXISTS-UNTESTED]` (orphan) | same | B2 / B4 |
| distribution (Plumtree/HyParView fan-out) | `[EXISTS-UNTESTED]` (lib) / `[ABSENT]` (live path) | same | B1 / B2 |
| replication-engine | `[EXISTS-UNTESTED]` (skeleton, one group) | same | B1 / B2 |
| transport | `[VERIFIED-PASS]` (TCP/TLS) / `[ABSENT]` (Netty/gRPC) | same | Session 0 (relabel), C1 |
| control-plane (API) | `[VERIFIED-PASS]` (JDK HTTP) / `[ABSENT]` (Spring) | same | Session 0 (relabel) |
| server (bootstrap) | `[VERIFIED-PASS]` (single node; Raft event-loop thread-confined ✓ A1) / `[VERIFIED-FAIL]` (as documented architecture) | same | A1 ✓, B2, Session 0 |
| testkit (DST + JMH) | `[VERIFIED-PASS]` (sim) / `[EXISTS-UNTESTED]` (perf numbers) | same | A4 (seed sweep), C1 |
| observability | `[VERIFIED-PASS]` | same | A2 (wire InvariantMonitor) |
| spec (TLA+) | `[VERIFIED-PASS]` (green) / `[VERIFIED-FAIL]` (some invariants vacuous) | same | A2 |

---

## 3. Risk register (ordered by where correctness is LEAST verified)

> From `STATE-OF-REALITY.md §5`. Status ∈ {OPEN, IN-PROGRESS, CLOSED}. A risk is CLOSED only with
> a pasted exit-gate command + output in the §4 entry that closed it.

| ID | Risk | Sev | Status | Owning session | Best evidence (baseline) |
|---|---|---|---|---|---|
| **R-01** | Multi-node concurrency race on `RaftNode` + `ConfigStateMachine` — algorithm verified, integration unsafe & untested (the recurring failure mode). | 🔴 | **CLOSED (A1, 2026-06-06)** | **Fixed:** ALL RaftNode access (tick, inbound, **propose**, read) marshalled onto the single `tickExecutor` via `ConfigdServer.raftInboundHandler` + `raftProposer` seams. `RaftInboundMarshallingTest` (3 tests) pass-with-fix / fail-without per seam; `./mvnw -fae test` BUILD SUCCESS. Commits c0b6617, c702657. Reviewer-confirmed: no off-thread mutator remains. |
| **R-02** | No runtime invariant enforcement in production — both checkers NOOP; `InvariantMonitor` never wired. | 🔴 | OPEN | **A2** | `ConfigdServer.java:248` RaftNode NOOP; `:188`→null→NOOP via `ConfigStateMachine.java:136`. |
| **R-03** | Edge data plane unverified against any live pipeline — fan-out is a write-only sink. | 🟠 | OPEN | **B1/B2/B3** | `FanOutBuffer.append` `ConfigdServer.java:301` with no draining reader; `broadcast()` benchmark-only. |
| **R-04** | "Linearizability verified" with no history checker — `LinearizabilityTest` is scripted single-threaded. | 🟠 | OPEN | **A3** | grep Knossos/Elle/Wing-Gong/Porcupine → 0. |
| **R-05** | Green ≠ coverage — count inflation (~20k of 21,394 = one parameterized test), unseeded per-node election RNG, vacuous TLA invariants, misnamed reconfig test. | 🟠 | OPEN | **A2** (vacuous invariants) + **A4** (seed sweep, misnamed test) | `ConsistencyPropertyTests.java:77` unseeded; `ReadIndexSpec.tla:237,251` & `SnapshotInstallSpec.tla:173` tautological; `ReconfigurationTest.java:257-270` vacuous. |
| **R-06** | Multi-region / hierarchical Raft is a deploy-shaped false promise. | 🟠 | **DECIDED (Session 0)** — docs reconciled; orphan-code removal owed to Phase B | ADR-0030 rejects WAN write consensus; `architecture.md §5` + `adr-0015` marked **Superseded by ADR-0030**. Orphaned multi-region/edge code still present (removal = Phase B). |
| **R-07** | Latent store hazards (R-1 unclone'd `byte[]`, W-1 unenforced single-writer, W-2 non-volatile getters). | 🟡 | OPEN (DE-ESCALATED) — no longer *live* now R-01 is closed (single-thread node access restored); hardening still owed | **A4** | `ReadResult.java:56-58`; single-writer unguarded; `ConfigStateMachine` public getters. |
| **R-08** | Perf "SURPASSES Quicksilver 4/4" + stack assumptions unbacked (Netty/JCTools/ZGC not present). | 🟡 | **PARTIAL** — live scorecards relabeled MODELED (Session 0); measurement owed to **C1** | `gap-analysis.md §6` + `performance.md §11` SURPASSES→MODELED; suite-size pinned 21,394 + stale TLC citations flagged in `final-report.md`/`verdict.md`/`ga-review.md`/`ga-approval.md`. Stack still absent; measurement pending C1. |
| **R-09** | Write availability does NOT meet §0.1 99.999% under **full-region** loss — single-region root, manual standby cutover (A2 covers AZ loss only). **GA BLOCKER.** | 🔴 | OPEN — **GA BLOCKER** | **Phase B**: `adr-0024` v0.2 sub-second region failover | ADR-0030 "SLO impact"; Amendment A2; **ADR-0031 (Accepted — option (a), 2026-06-06: keep 99.999%, fix by design)**. |
| **R-10** | `GLOBAL`/security keys need a fail-closed linearizable strong-read path (INV-1) — not wired: no strong-read key class, no fail-closed enforcement, no testable contract entry. | 🟠 | OPEN | **Phase B** (testable `consistency-contract.md` entry) | ADR-0030 INV-1 / Amendment A1. |
| **R-11** | Data residency unsolved — single global root non-compliant for hard-localization data classes (INV-2); needs a deploy-time guardrail. | 🟠 | OPEN | **Phase B** (deploy guardrail) + `adr-0024` v0.2 per-jurisdiction roots | ADR-0030 INV-2 / Amendment A3. |

**New risks from Session 0 (topology-decision residuals):** R-09 (full-region write-availability §0.1
violation), R-10 (GLOBAL-key fail-closed strong-read, INV-1), R-11 (data residency, INV-2) — all
OPEN, owned by Phase B. These are accepted topology trade-offs, not verified-but-untested-integration
*seams* (no new cross-thread seam found this session).

**GA blockers (must be CLOSED before GA):** **R-09** — per `ADR-0031` (option (a), ratified
2026-06-06), the §0.1 99.999% write-availability target is **kept**; GA MUST NOT proceed until
sub-second automatic region failover (`adr-0024` v0.2) meets it through a full-region loss.

---

## 4. Session log (append-only)

### Session S — Scaffolding + ledger rename (2026-06-06)
- **Mode:** lead, solo. No code, spec, or test changed.
- **What changed:** created this ledger and `PRODUCTION-READINESS-PLAN.md`, both seeded from
  `STATE-OF-REALITY.md §2/§3/§5/§6`; then renamed the ledger `docs/PROGRESS.md` →
  `docs/READINESS-LEDGER.md` to remove the case-collision with the superseded lowercase
  `docs/progress.md` (a case-insensitive checkout would conflate them). Added a superseded banner
  to `docs/progress.md` pointing here, and committed the whole set as one "establish readiness
  ledger" commit.
- **Why:** the operator's session playbook opens every session with "read the ledger" and closes
  with "append to the ledger", and references `PRODUCTION-READINESS-PLAN.md` — but neither existed.
  This unblocks the plan without touching any verified surface.
- **Finding (doc-vs-reality):** the on-disk `PROMPT.md` is the project *mission* doc and contains
  no ledger references — it is **not** the session playbook. The operator's session playbook is now
  persisted as `docs/SESSION-PLAYBOOK.md` (its ledger references updated to this file's name).
- **Evidence:** `[VERIFIED-PASS]` — `ls docs/READINESS-LEDGER.md PRODUCTION-READINESS-PLAN.md`
  lists both files; `ls docs/PROGRESS.md` returns no such file (rename complete). Creation/rename
  only; nothing else was run because nothing else was modified.
- **Component re-classification:** none (no component touched). Component status §2 and risk
  register §3 are initialized equal to the `STATE-OF-REALITY.md` baseline, all R-rows OPEN.
- **Open follow-up for the next session:** the next session (whichever is launched) must still run
  its own "Before anything" reads of `STATE-OF-REALITY.md` + the relevant `findings-*.md` and
  treat them as claims to verify.

### Session 0 — Topology decision (ADR-0030) + doc decontamination (2026-06-06)
- **Mode:** agent team — 3 Opus teammates (`prior-art-researcher`, `topology-architect`,
  `devils-advocate`) investigating independently then cross-examining; lead orchestrated ordering
  and did the mechanical decontamination directly. Branch: `session-0-topology-adr`. No
  code/spec/test changed.
- **Decision:** adopt a **Quicksilver-shaped topology** — one centralized strongly-consistent Raft
  root + asynchronous bounded-staleness edge fan-out; **reject** global multi-region / hierarchical
  Raft *write* consensus. Recorded as **ADR-0030** (Status: Proposed; all three teammates SIGN-OFF).
  Stub **ADR-0031** opens the §0.1 write-availability-target renegotiation (human-gated).
- **Cross-examination was real, not ceremonial:** ordering enforced — the architect did not draft
  the ADR until researcher + adversary both reported. Round-3 sign-off caught the architect
  **fabricating a §0.2 non-goal** ("not a low-latency regional write store") to prop up the #3
  rebuttal; struck after the devils-advocate flagged it (verified `grep -c` = 0). Researcher
  independently confirmed all latency math/citations faithful; nothing `[UNVERIFIED]` promoted to fact.
- **Three forced amendments (honest engagement, not a clean win):** A1 `GLOBAL`/security strong-read
  key class (→ INV-1); A2 multi-AZ root for automatic AZ-loss survival; A3 residency explicitly
  deferred (→ INV-2). Four adversary points booked as honest residuals → new risks **R-09/R-10/R-11**.
- **Decontamination diff (lead, mechanical):**
  - `gap-analysis.md §6` + `performance.md §11` scorecards: "SURPASSES / 4-of-4 / Measured" →
    **MODELED, NOT MEASURED** (verdicts withdrawn pending C1).
  - `architecture.md §5` (Multi-Region Strategy) + `adr-0015` (Status: Accepted → **Superseded by
    ADR-0030**) marked superseded.
  - Suite-size pinned to live **21,394** (evidence `verification-runs/state-of-reality/live-mvn-test.log`)
    in `final-report.md`, `certification/verdict.md`, `ga-review.md`, `ga-approval.md`; historical
    20,132/20,149/21,246/21,285 flagged as point-in-time. (Dated review/audit/prr snapshots left as
    historical record, superseded by this pin.)
  - Stale TLA+ artifact citations (`verification-runs/tlc-rerun.log`) struck/flagged in
    `final-report.md:93,310`; `verdict.md` TLC row noted as "Not re-run / not current evidence".
- **Evidence:** `[VERIFIED-PASS]` — `docs/decisions/adr-0030-*.md` + `adr-0031-*.md` exist; ADR-0030
  Status=Proposed with 3× SIGN-OFF; fabricated §0.2 item `grep -c` = 0. Findings persisted under
  `verification-runs/session-0/`. No code/spec/test ran (nothing modified).
- **Component re-classification:** none (no component code touched). R-06 → DECIDED (docs reconciled;
  orphan-code removal = Phase B); R-08 → PARTIAL (live scorecards relabeled; measurement = C1).
- **Stop point / human gates:** committed on branch `session-0-topology-adr`; **stops for human
  review before merge.** Pending human ratification: the §0.1 write-availability KNOWN VIOLATION
  (R-09) via ADR-0031. Teammates left idle + resumable (not cleaned up) in case review needs ADR
  changes. INV-1/INV-2 enforcement + orphan-code removal are Phase B obligations.

### Session 0 — review fixups + merge to main (2026-06-06)
- **ADR-0031 ratified: option (a)** — keep §0.1 99.999% write-availability; sub-second region
  failover (`adr-0024` v0.2) is a **GA BLOCKER** (R-09 updated; (b)/(c) rejected).
- **Historical-record notices** added at `docs/review/`, `docs/prr/`, `docs/audit/` pointing at the
  authoritative pinned numbers (live suite size **21,394**; `STATE-OF-REALITY.md`). Dated snapshots
  NOT rewritten (rewriting dated records would itself be dishonest).
- **Merge:** `session-0-topology-adr` → `main` (`--no-ff`); this fixups commit is the last on the
  branch before merge. Session 0 teammates were ephemeral subagents (Agent tool, not a TeamCreate
  team) and have all terminated — nothing left running to shut down.
- **Next:** branch `session-a1-raft-race` for Session A1 (R-01, the Raft integration race).

### Session A1 — Kill the Raft integration race (R-01) (2026-06-06)
- **Mode:** single Opus session (lead) + one Opus reviewer subagent; **plan-mode first** (plan
  approved before any edit). Branch: `session-a1-raft-race`. **First code change of the effort.**
- **The race (verified by file:line):** the explicitly single-threaded `RaftNode` ("No
  synchronization is used", `RaftNode.java:17-21`) was driven concurrently by THREE entry points on
  THREE threads — `tick()` ("configd-tick"), inbound `handleMessage()` (per-connection virtual
  threads), and `propose()` (HTTP virtual threads) — racing currentTerm/log/commitIndex and
  double-entering `stateMachine.apply`. The read path was already marshalled; inbound and propose
  were not.
- **Fix:** marshal ALL node access onto the single `tickExecutor`. Hoisted executor creation above
  the transport wiring; added `ConfigdServer.raftInboundHandler` (inbound) and `raftProposer`
  (writes) seams that `tickExecutor.execute(...)` the routing/proposal; rewired both. Tick, inbound,
  propose, and read now all run on the one "configd-tick" thread.
- **Exit gate — `[VERIFIED-PASS]`:**
  - (i) `./mvnw -pl configd-server test -Dtest=RaftInboundMarshallingTest` → 3 tests, 0 fail.
  - (ii) **Discrimination (per seam):** scratch branches — revert inbound → inbound + stress fail;
    revert propose → propose + stress fail (sentinel "observed 2 concurrent entries"; deterministic
    "apply ran on main, expected raft-test-exec"); restore.
  - (iii) `./mvnw -fae test` → **BUILD SUCCESS** (full reactor; +1 net test method).
  - (iv) Independent Opus reviewer: **CONFIRMED** — all four seams confined to the one tickExecutor
    thread; no remaining off-thread RaftNode mutator; no deadlock; validation exceptions preserved.
- **Cross-examination caught a real gap:** the reviewer's first pass (CONCERNS) found my initial
  inbound-only fix missed the **propose path** (live even single-node). Fixed within A1 (the
  `raftProposer` seam + propose-flood/deterministic tests) — the recurring
  verified-but-untested-integration failure mode, found and closed in the same session.
- **Task 3 (other same-class seams):** SEAM-1 candidate (`TcpRaftTransport.messageHandler`
  registration) **verified and dismissed** — handler registered before `transport.start()`; field
  is volatile (`TcpRaftTransport.java:63`). The propose path was the one real additional seam —
  fixed, not left open. No other off-thread node mutator (reviewer re-grep).
- **Component re-classification:** R-01 `[VERIFIED-FAIL]` (threading model) → **`[VERIFIED-PASS]`**
  (event loop serialized; discriminating test in place). R-07 de-escalated (no longer live).
  Minor cleanup: corrected the stale `ConfigWriteService` "propose is thread-safe" javadoc.
- **Stop point:** committed on branch `session-a1-raft-race` (c0b6617 inbound · c702657 propose ·
  + this finalize commit); **stops for human review before merge.**

<!-- Append new session entries ABOVE this line, newest-first or newest-last (pick one and keep it
     consistent). Each entry: Mode · What changed · Exit-gate command + pasted output · Component
     re-classification (rubric) · Any new R-row seams. -->
