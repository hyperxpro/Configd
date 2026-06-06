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

**Last updated:** 2026-06-06 — Session S (scaffolding); ledger renamed `PROGRESS.md` →
`READINESS-LEDGER.md`. No code/spec/test changed.

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
The most dangerous single defect is a **verified-but-untested-integration seam**: the verified Raft
is driven thread-unsafely by the server, with no test exercising it (Risk **R-01**). Full detail:
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
| server (bootstrap) | `[VERIFIED-PASS]` (single node) / `[VERIFIED-FAIL]` (as documented architecture) | same | A1, B2, Session 0 |
| testkit (DST + JMH) | `[VERIFIED-PASS]` (sim) / `[EXISTS-UNTESTED]` (perf numbers) | same | A4 (seed sweep), C1 |
| observability | `[VERIFIED-PASS]` | same | A2 (wire InvariantMonitor) |
| spec (TLA+) | `[VERIFIED-PASS]` (green) / `[VERIFIED-FAIL]` (some invariants vacuous) | same | A2 |

---

## 3. Risk register (ordered by where correctness is LEAST verified)

> From `STATE-OF-REALITY.md §5`. Status ∈ {OPEN, IN-PROGRESS, CLOSED}. A risk is CLOSED only with
> a pasted exit-gate command + output in the §4 entry that closed it.

| ID | Risk | Sev | Status | Owning session | Best evidence (baseline) |
|---|---|---|---|---|---|
| **R-01** | Multi-node concurrency race on `RaftNode` + `ConfigStateMachine` — algorithm verified, integration unsafe & untested (the recurring failure mode). | 🔴 | OPEN | **A1** | inbound on virtual threads `MultiRaftDriver.java:119` (no marshalling) vs tick thread `ConfigdServer.java:394`; `RaftNode.java:17-18` "no synchronization"; apply on inbound via `:851`. |
| **R-02** | No runtime invariant enforcement in production — both checkers NOOP; `InvariantMonitor` never wired. | 🔴 | OPEN | **A2** | `ConfigdServer.java:248` RaftNode NOOP; `:188`→null→NOOP via `ConfigStateMachine.java:136`. |
| **R-03** | Edge data plane unverified against any live pipeline — fan-out is a write-only sink. | 🟠 | OPEN | **B1/B2/B3** | `FanOutBuffer.append` `ConfigdServer.java:301` with no draining reader; `broadcast()` benchmark-only. |
| **R-04** | "Linearizability verified" with no history checker — `LinearizabilityTest` is scripted single-threaded. | 🟠 | OPEN | **A3** | grep Knossos/Elle/Wing-Gong/Porcupine → 0. |
| **R-05** | Green ≠ coverage — count inflation (~20k of 21,394 = one parameterized test), unseeded per-node election RNG, vacuous TLA invariants, misnamed reconfig test. | 🟠 | OPEN | **A2** (vacuous invariants) + **A4** (seed sweep, misnamed test) | `ConsistencyPropertyTests.java:77` unseeded; `ReadIndexSpec.tla:237,251` & `SnapshotInstallSpec.tla:173` tautological; `ReconfigurationTest.java:257-270` vacuous. |
| **R-06** | Multi-region / hierarchical Raft is a deploy-shaped false promise. | 🟠 | OPEN | **Session 0** (decision/ADR) + docs | `architecture.md:181-205` vs one group `ConfigdServer.java:251-252`; `research.md:618` self-contradicts. |
| **R-07** | Latent store hazards become live under R-01: R-1 unclone'd `byte[]`, W-1 unenforced single-writer, W-2 non-volatile getters. | 🟡 | OPEN | **A4** | `ReadResult.java:56-58`; single-writer unguarded; `ConfigStateMachine` public getters. |
| **R-08** | Perf "SURPASSES Quicksilver 4/4" + stack assumptions unbacked (Netty/JCTools/ZGC not present). | 🟡 | OPEN | **Session 0** (relabel) + **C1** (measure) | `gap-analysis.md:243-252`; `performance.md` "MODELED, NOT MEASURED"; no `io.netty`/`org.jctools` in `src/main`. |

**New seams discovered after baseline:** _(none yet — append R-09+ here as found)_

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
- **Finding (doc-vs-reality):** the session playbook is **not** a repo file — the on-disk
  `PROMPT.md` is the project *mission* doc and contains no ledger references. The plan/ledger now
  refer to "the session playbook" generically; persisting it in-repo is an open decision.
- **Evidence:** `[VERIFIED-PASS]` — `ls docs/READINESS-LEDGER.md PRODUCTION-READINESS-PLAN.md`
  lists both files; `ls docs/PROGRESS.md` returns no such file (rename complete). Creation/rename
  only; nothing else was run because nothing else was modified.
- **Component re-classification:** none (no component touched). Component status §2 and risk
  register §3 are initialized equal to the `STATE-OF-REALITY.md` baseline, all R-rows OPEN.
- **Open follow-up for the next session:** the next session (whichever is launched) must still run
  its own "Before anything" reads of `STATE-OF-REALITY.md` + the relevant `findings-*.md` and
  treat them as claims to verify.

<!-- Append new session entries ABOVE this line, newest-first or newest-last (pick one and keep it
     consistent). Each entry: Mode · What changed · Exit-gate command + pasted output · Component
     re-classification (rubric) · Any new R-row seams. -->
