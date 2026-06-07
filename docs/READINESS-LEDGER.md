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

**Last updated:** 2026-06-07 — Session A3-B (linearizability + fault-injection harness **BUILT**:
new module `configd-linz` — real separate-JVM cluster over the real TCP transport, OS-level
iptables/kill-9 faults, trusted Porcupine checker) on branch `session-a3-linearizability`. **All six
exit gates green; R-04 CLOSED.** Committed pre-merge for human review — A3-D design + A3-B build merge
together as one unit. **Phase A: linearizable under the fault classes A3 tested, with R-12 (reconfig)
and R-14 (ack≠commit) explicitly OUTSTANDING** (NOT unqualified-done).

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

> **Why gates are pasted-command + output, never recalled:** this agent once surfaced a *different*
> project's (an HTTP-client codebase's) state and reported it here with full confidence.
> Cross-repo / cross-session recall is contaminable; only a command + its output from *this* working
> tree is evidence. Memory primes which file to open — it never substitutes for opening it.

**The recurring failure mode of this project — name it when you see it:**
> A **verified-but-untested-integration seam** — a correct component wired into the running system
> in an unsafe or unverified way, so it *looks* done. The canonical instance is the formally
> verified single-threaded `RaftNode` driven concurrently by the server (Risk R-01). Whenever you
> find another (a verified/real unit invoked across threads without marshalling, or glued together
> only in test code), **log it as a new finding here**, classified.
>
> **A1 sharpened this prior (2026-06-06):** the assessment framed R-01 as *tick-vs-inbound*, but a
> **third** off-thread caller existed — the **write/propose path, live even in single-node mode** —
> and was found only by an adversarial reviewer + a discriminating test, **not** by static reads.
> **Load-bearing prior for Phase B:** the fan-out / edge-pipeline wiring (B1/B2/B3) almost certainly
> has **more cross-thread callers than a static call-graph shows**. Budget adversarial review + real
> concurrency tests for every B-phase seam; treat "static inspection found N callers" as a *floor*,
> not the count.

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
| consensus — runtime invariant enforcement | `[VERIFIED-PASS]` (wired to InvariantMonitor ✓ A2; fail-open metric + SEVERE log, observable at `/metrics`) | `[VERIFIED-FAIL]` (NOOP in prod) | A2 ✓ |
| config-store (MVCC) | `[VERIFIED-PASS]` | same | A4 (latent R-1/W-1/W-2 hardening) |
| edge-cache | `[EXISTS-UNTESTED]` (orphan) | same | B2 / B4 |
| distribution (Plumtree/HyParView fan-out) | `[EXISTS-UNTESTED]` (lib) / `[ABSENT]` (live path) | same | B1 / B2 |
| replication-engine | `[EXISTS-UNTESTED]` (skeleton, one group) | same | B1 / B2 |
| transport | `[VERIFIED-PASS]` (TCP/TLS) / `[ABSENT]` (Netty/gRPC) | same | Session 0 (relabel), C1 |
| control-plane (API) | `[VERIFIED-PASS]` (JDK HTTP) / `[ABSENT]` (Spring) | same | Session 0 (relabel) |
| server (bootstrap) | `[VERIFIED-PASS]` (single node; Raft event-loop thread-confined ✓ A1) / `[VERIFIED-FAIL]` (as documented architecture) | same | A1 ✓, B2, Session 0 |
| testkit (DST + JMH) | `[VERIFIED-PASS]` (sim) / `[EXISTS-UNTESTED]` (perf numbers) | same | A4 (seed sweep), C1 |
| linearizability harness (`configd-linz`) | `[VERIFIED-PASS]` (real separate-JVM + OS faults + trusted Porcupine; R-04 closed ✓ A3-B) | `[ABSENT]` | A3-B ✓ |
| observability | `[VERIFIED-PASS]` (InvariantMonitor now wired ✓ A2) | same | A2 ✓ |
| spec (TLA+) | `[VERIFIED-PASS]` (green; 3 vacuous invariants de-vacuumed ✓ A2; all 3 specs now in CI) | `[VERIFIED-PASS]` (green) / `[VERIFIED-FAIL]` (some invariants vacuous) | A2 ✓ |

---

## 3. Risk register (ordered by where correctness is LEAST verified)

> From `STATE-OF-REALITY.md §5`. Status ∈ {OPEN, IN-PROGRESS, CLOSED}. A risk is CLOSED only with
> a pasted exit-gate command + output in the §4 entry that closed it.

| ID | Risk | Sev | Status | Owning session | Best evidence (baseline) |
|---|---|---|---|---|---|
| **R-01** | Multi-node concurrency race on `RaftNode` + `ConfigStateMachine` — algorithm verified, integration unsafe & untested (the recurring failure mode). | 🔴 | **CLOSED (A1, 2026-06-06)** | **Fixed:** ALL RaftNode access (tick, inbound, **propose**, read) marshalled onto the single `tickExecutor` via `ConfigdServer.raftInboundHandler` + `raftProposer` seams. `RaftInboundMarshallingTest` (3 tests) pass-with-fix / fail-without per seam; `./mvnw -fae test` BUILD SUCCESS. Commits c0b6617, c702657. Reviewer-confirmed: no off-thread mutator remains. |
| **R-02** | No runtime invariant enforcement in production — both checkers NOOP; `InvariantMonitor` never wired. | 🔴 | **CLOSED (A2, 2026-06-06)** | **Fixed:** `ConfigdServer` wires `InvariantMonitor` to BOTH checkers (RaftNode + ConfigStateMachine); a violation increments a named metric visible at `/metrics` + a SEVERE log (fail-open), throws in testMode. `InvariantNetMetricTest` observes a real `per_key_order` violation in a RUNNING server (fails if reverted to NOOP). Reviewer-confirmed. |
| **R-03** | Edge data plane unverified against any live pipeline — fan-out is a write-only sink. | 🟠 | OPEN | **B1/B2/B3** | `FanOutBuffer.append` `ConfigdServer.java:301` with no draining reader; `broadcast()` benchmark-only. |
| **R-04** | "Linearizability verified" with no history checker — `LinearizabilityTest` is scripted single-threaded. | 🟠 | **CLOSED (A3-B, 2026-06-07)** | A3-D (design) + A3-B (build) | **Built (`configd-linz`) + all 6 gates green.** Real **separate-JVM** cluster (shaded `configd-server` jar) over the real `TcpRaftTransport`, OS-level iptables `REJECT`/`kill -9` faults, faithful client history → **trusted Porcupine** checker (per-key linearizable register; ack≠commit writes modeled `:info`-floating + confirm-bound). **(i)** self-test 6/6 incl. timeout→`info`-never-`fail` flip + 4/4 unit; **(ii)** discrimination — lost-acked-write (no-op `appendToLog`) → **RED** (a value confirmed by a linearizable read-back vanished post-restart), stale-read (delete the `readIndex`/`isReadReady` leader guards) → **RED** (a lagging follower served a superseded value), controls GREEN; **(iii)** linearizable across seeds 2001-2004 on **3- AND 5-node**, faults active; **(iv)** seed→byte-identical schedule; **(v)** `./mvnw -fae test` BUILD SUCCESS (21,408 / 0 fail / 0 err); **(vi)** independent Opus reviewer CONFIRMED all three (real multi-process not sim; `:info`-not-`:fail` sound; discrimination genuinely RED). Full detail: §4 A3-B entry. |
| **R-05** | Green ≠ coverage — count inflation, unseeded election RNG, vacuous TLA invariants, misnamed reconfig test. | 🟠 | **PARTIAL** — (c) vacuous invariants **DONE (A2)**; (a) count, (b) seed sweep, (d) misnamed test remain **A4** | (c) DONE: ReadFreshness/NoStaleLeaderServe/NoCommitRevert de-vacuumed (TLC green + seeded-bug counterexamples) + RaftNode `version_monotonicity` & SM `sequence_*` (runtime vacuity) fixed/removed; all 3 specs in CI. Remaining: `ConsistencyPropertyTests.java:77` unseeded; `ReconfigurationTest.java:257-270` vacuous. |
| **R-06** | Multi-region / hierarchical Raft is a deploy-shaped false promise. | 🟠 | **DECIDED (Session 0)** — docs reconciled; orphan-code removal owed to Phase B | ADR-0030 rejects WAN write consensus; `architecture.md §5` + `adr-0015` marked **Superseded by ADR-0030**. Orphaned multi-region/edge code still present (removal = Phase B). |
| **R-07** | Latent store hazards (R-1 unclone'd `byte[]`, W-1 unenforced single-writer, W-2 non-volatile getters). | 🟡 | **DORMANT — CONDITIONAL on the A1 single-thread marshalling invariant holding.** Becomes live again the instant any node access escapes the tick thread. | **A4** | `ReadResult.java:56-58`; single-writer unguarded; `ConfigStateMachine` public getters. **A4's W-1 owner-thread assertion is the regression TRIPWIRE that protects the A1 fix — it fires if a future change drives the node off the tick thread. It is load-bearing, NOT optional cleanup.** |
| **R-08** | Perf "SURPASSES Quicksilver 4/4" + stack assumptions unbacked (Netty/JCTools/ZGC not present). | 🟡 | **PARTIAL** — live scorecards relabeled MODELED (Session 0); measurement owed to **C1** | `gap-analysis.md §6` + `performance.md §11` SURPASSES→MODELED; suite-size pinned 21,394 + stale TLC citations flagged in `final-report.md`/`verdict.md`/`ga-review.md`/`ga-approval.md`. Stack still absent; measurement pending C1. |
| **R-09** | Write availability does NOT meet §0.1 99.999% under **full-region** loss — single-region root, manual standby cutover (A2 covers AZ loss only). **GA BLOCKER.** | 🔴 | OPEN — **GA BLOCKER** | **Phase B**: `adr-0024` v0.2 sub-second region failover | ADR-0030 "SLO impact"; Amendment A2; **ADR-0031 (Accepted — option (a), 2026-06-06: keep 99.999%, fix by design)**. |
| **R-10** | `GLOBAL`/security keys need a fail-closed linearizable strong-read path (INV-1) — not wired: no strong-read key class, no fail-closed enforcement, no testable contract entry. | 🟠 | OPEN | **Phase B** (testable `consistency-contract.md` entry) | ADR-0030 INV-1 / Amendment A1. |
| **R-11** | Data residency unsolved — single global root non-compliant for hard-localization data classes (INV-2); needs a deploy-time guardrail. | 🟠 | OPEN | **Phase B** (deploy guardrail) + `adr-0024` v0.2 per-jurisdiction roots | ADR-0030 INV-2 / Amendment A3. |
| **R-12** | Joint-consensus reconfiguration is **unverified end-to-end AND structurally untestable until a live caller exists** — there is no path from the running binary that exercises a membership change, let alone one under fault. **This must CLOSE before Phase A can be declared complete (unqualified)** — it is one of Phase A's two named outstanding exceptions (with R-14). | 🟠 | OPEN — **DEFERRED by A3-D** to a dedicated reconfig session; **A3-B re-confirmed still structurally untestable** | dedicated reconfig session (post-A3) | **A3-D, re-confirmed A3-B:** `proposeConfigChange` (`RaftNode.java:514`) has **zero non-test callers**; `AdminService` is never instantiated outside tests (`grep 'new AdminService'` → 0 non-test); the only test `configChangePreservedAcrossElections` (`ReconfigurationTest.java:257-270`) is vacuous (= R-05d). A3 fences to writes + ReadIndex reads; injecting reconfig faults would require **adding an admin reconfig seam** — itself a *new* verified-but-untested-integration seam (the A1 prior) — explicitly **NOT** done in A3-B. Owed: a session that wires a reconfig seam **with a tripwire**, then fault-tests reconfig-under-partition/election. |
| **R-13** | InstallSnapshot has a silent-drop **liveness cliff**: a snapshot > 16 MiB is dropped, not chunked → a lagging follower can get permanently stuck. | 🟡 | OPEN — surfaced by A3-D | later session | **A3-D:** install is single-shot (`sendInstallSnapshot:1283-1292` always `offset=0,done=true`; `handleInstallSnapshot:1501` one `restoreSnapshot`); over-cap snapshot hits the IAE path (`RaftNode.java:1300`) against the 16 MiB wire frame cap (`FrameCodec.java:86`) and is silently dropped (corrects the docs' "4 MiB / offset-ignored chunking" framing). Not an A3 linearizability **safety** fault (no partial-install state to corrupt) — recorded as a liveness gap. |
| **R-14** | **`ack ≠ commit`** — `ConfigWriteService.put/delete` returns `200 Accepted` on **local append** (before quorum-commit); `proposalId` is a local `AtomicLong`, not a Raft commit index. A client "success" is therefore **not** a commit → **read-your-writes is not guaranteed**, and the response contradicts the contract's ack model (`consistency-contract.md §6` "acknowledgment with commit sequence S"). An **R-01-class gap** (the system behaves in a way the contract does not admit) — **surfaced, not introduced,** by A3-B. | 🟠 | **OPEN** | **A5 — commit-confirmed write path (dedicated session; NOT an A3-B follow-on)** | `ConfigWriteService.java:150-154` (`proposer.propose()` local append → `WriteResult.Accepted(nextProposalId.getAndIncrement())`); `RaftNode.java:283-289` (`propose` returns after local append, before quorum-commit); `HttpApiServer.java:278` (`200` on `Accepted`). Fix = a synchronous commit-confirmed write path (block until applied, return the commit seq), buildable on `whenReadReady`/`lastApplied` (`RaftNode.java:453-460,424`) — `a3-harness-design.md §6(B)`. |
| **R-15** | **Timeout-less `connect()` on the single tick thread** — the Raft transport opens outbound peer connections with `new Socket(addr, port)` and **no connect timeout** (`TcpRaftTransport.java:343`), invoked from `send()` on the one serialized tick thread (the A1 invariant). A single **black-holed** peer (e.g. an iptables DROP partition) blocks the leader's tick loop for the full TCP SYN timeout → the leader cannot commit to **anyone**. A liveness/availability gap surfaced by A3-B. | 🟡 | OPEN — surfaced by A3-B | later session | **A3-B:** empirically reproduced — DROP-isolating a follower intermittently stalled the leader so a write never committed; the harness had to switch to iptables `REJECT --reject-with tcp-reset` (fail-fast) to test safety at all. Fix = a bounded `connect()`/send timeout or async connect off the tick thread. Not an A3 linearizability **safety** fault. |

**New risks from Session 0 (topology-decision residuals):** R-09 (full-region write-availability §0.1
violation), R-10 (GLOBAL-key fail-closed strong-read, INV-1), R-11 (data residency, INV-2) — all
OPEN, owned by Phase B. These are accepted topology trade-offs, not verified-but-untested-integration
*seams* (no new cross-thread seam found this session).

**GA blockers (must be CLOSED before GA):** **R-09** — per `ADR-0031` (option (a), ratified
2026-06-06), the §0.1 99.999% write-availability target is **kept**; GA MUST NOT proceed until
sub-second automatic region failover (`adr-0024` v0.2) meets it through a full-region loss.

**C-phase obligations (deferred, recorded by A2):**
1. **Per-invariant fail-closed classification.** A2 ships the runtime invariant net **fail-open**
   (metric + SEVERE log). Fail-closed (halt / step-down) is NOT a global toggle — it is a
   per-invariant decision: safety/corruption invariants (`state_machine_safety`, `log_matching`,
   `leader_completeness`, `version_monotonicity`, `per_key_order`) are halt-worthy candidates;
   liveness/freshness invariants are observe-only. An assertion MUST **earn halt authority by
   demonstrating a zero false-positive rate in metric mode first** — do not grant halt authority
   before that evidence exists.
2. **Repo cruft:** committed TLC trace artifacts (`spec/*_TTrace_*`, `spec/states/`) should be
   `.gitignore`d (pre-existing, ~225 MB per `inventory.md`; not A2 scope).

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
  inbound-only fix missed the **propose path** (live even single-node) — a third off-thread caller
  **beyond the assessment's tick-vs-inbound framing of R-01**, invisible to the static call-graph and
  surfaced only by adversarial review + a discriminating test. Fixed within A1 (the `raftProposer`
  seam + propose-flood/deterministic tests). See the §0 "load-bearing prior for Phase B" this raised.
- **Task 3 (other same-class seams):** SEAM-1 candidate (`TcpRaftTransport.messageHandler`
  registration) **verified and dismissed** — handler registered before `transport.start()`; field
  is volatile (`TcpRaftTransport.java:63`). The propose path was the one real additional seam —
  fixed, not left open. No other off-thread node mutator (reviewer re-grep).
- **Component re-classification:** R-01 `[VERIFIED-FAIL]` (threading model) → **`[VERIFIED-PASS]`**
  (event loop serialized; discriminating test in place). R-07 de-escalated (no longer live).
  Minor cleanup: corrected the stale `ConfigWriteService` "propose is thread-safe" javadoc.
- **Stop point:** committed on branch `session-a1-raft-race` (c0b6617 inbound · c702657 propose ·
  + this finalize commit); **stops for human review before merge.**

### Session A2 — Runtime invariant net (R-02) + de-vacuum the TLA proof (R-05c) (2026-06-06)
- **Mode:** single Opus session (lead) + one Opus reviewer subagent; **plan-mode first** (plan
  approved before any edit). Branch: `session-a2-invariant-net`.
- **R-02 — net was NOOP in prod, now ON + observable:** `ConfigdServer` hoists `MetricsRegistry`
  above the state machine, instantiates `InvariantMonitor(registry, testMode=false)`, and bridges
  BOTH `InvariantChecker` SAMs (RaftNode's + ConfigStateMachine's) to it; passes real checkers (was
  `NOOP` on RaftNode; was the 3-arg SM ctor → null → NOOP). `InvariantMonitor` now emits a SEVERE
  log **and** a named metric on violation (fail-open), throws in testMode. The metric shares the
  registry the `PrometheusExporter` reads → visible at `/metrics`.
- **No wired check that asserts nothing** (operator directive): removed SM `sequence_monotonic`/
  `sequence_gap_free` (locally tautological); the reviewer caught the SAME vacuity in RaftNode
  `version_monotonicity` (which the wiring had just activated) → de-vacuumed to assert against the
  log entry's own index. Kept the real checks (RaftNode's 8 ↔ ConsensusSpec invariants; SM
  `per_key_order`).
- **R-05c — de-vacuumed 3 TLA invariants + CI + tlc-results:** `ReadFreshness`
  (`readIdx <= appliedIndex[server]`, the F-0009 property), `NoStaleLeaderServe`
  (`term <= currentTerm[server]`), `NoCommitRevert` (a higher-index inflight snapshot can't revert
  the term). `ci.yml` now runs all 3 specs; `spec/tlc-results.md` regenerated from the live `.cfg`
  (drops the removed `NoStaleOverwrite`).
- **Exit gate — `[VERIFIED-PASS]`:**
  - (i)/(vi) `InvariantNetMetricTest`: a real `per_key_order` violation in a RUNNING server
    increments `invariant_violation_per_key_order_total` in the live `/metrics` exposition (+ SEVERE
    log observed). Discrimination: reverting the wiring to NOOP fails the test (verified on scratch).
  - (ii) all 3 specs TLC-green live (ConsensusSpec 3,299,086 / ReadIndexSpec 2,276,125 /
    SnapshotInstallSpec 847,124 distinct); each de-vacuumed invariant **fails with a seeded buggy
    action** (scratch): `Invariant ReadFreshness / NoStaleLeaderServe / NoCommitRevert is violated`.
  - (iii) `ci.yml` runs ConsensusSpec + ReadIndexSpec + SnapshotInstallSpec.
  - (iv) `./mvnw -fae test` → BUILD SUCCESS.
  - (v) Independent Opus reviewer: **CONFIRMED** (net wired + observable; no vacuous wired check
    survives; de-vacuumed invariants real; test discriminates, no false-green). Notes: (a) RaftNode
    `version_monotonicity` vacuity — **fixed this session**; (b) `NoCommitRevert` is non-vacuous but
    partly implied by `InflightTermMonotonic` (the weakest of the three) — recorded, acceptable.
- **Component re-classification:** consensus runtime-invariant-enforcement `[VERIFIED-FAIL]` →
  **`[VERIFIED-PASS]`**; spec (TLA+) vacuity resolved; observability InvariantMonitor wired. **R-02
  CLOSED**; **R-05 → PARTIAL** ((c) done; a/b/d remain A4).
- **Recorded:** the C-phase per-invariant fail-closed obligation (§3 GA-blockers note); repo-cruft
  note (committed TLC trace artifacts should be gitignored).
- **Stop point:** committed on `session-a2-invariant-net` (53c86f8 impl · 56c1684 version_monotonicity
  fix · + this finalize commit); **stops for human review before merge.**

### Session A3-D — Design the linearizability + fault-injection harness (R-04) (2026-06-06)
- **Mode:** agent team — 3 Opus teammates (`distributed-systems-lens` = fault matrix; `consistency-lens`
  = what-is-checked + discrimination plan; `chaos-lens` = injection mechanism + reproducibility),
  investigating independently then cross-examining; lead orchestrated ordering and assembled the two docs
  from their content. Branch `session-a3-linearizability` (off the A1+A2 chain). **DESIGN ONLY — no
  code/spec/test changed.** STOP for human review before A3-B (the build).
- **Decision (ADR-0032, 3/3 SIGN-OFF):** a **bespoke Java harness** with two *separable* parts — (i) a
  Java orchestrator (separate-JVM cluster from the shaded jar over the real TCP transport; **OS-level
  iptables/tc + kill-9 fault injection**; concurrent JDK-HttpClient client; checker-neutral op-history
  recorder) feeding (ii) a **trusted third-party checker — Porcupine (primary)**, with **Elle** a drop-in
  optional cross-check via the neutral history and the future primary the day multi-key atomic `BATCH` is
  wired. **Rejected (with concrete cost):** full Jepsen/Clojure-as-driver (~400–700 LOC Clojure / 3–6 day
  ramp to *rebuild* the proven Java orchestration; its transactional-cycle strength unused for a per-key
  register); a hand-rolled checker; **Lincheck (category error** — in-JVM data-structure checker, no
  processes/partitions/crash); reusing the in-process `SimulatedNetwork` (the R-01 blind spot); a
  transport shim as primary. Toolchain honesty: Porcupine is Go and Go is *also* absent here, so the
  decision does **not** rest on a false "no-ramp" claim.
- **Cross-examination was real, not ceremonial:** both Jepsen advocates (`consistency-lens`,
  `distributed-systems-lens`) **withdrew** their Round-1 Jepsen+Elle vote under challenge — conceding that
  (a) Porcupine has first-class indeterminate-op support (so "Elle handles indeterminate, Porcupine
  doesn't" was false), (b) the live model is a per-key register (Porcupine's home turf; only single-key
  PUT/DELETE wired — `BATCH` is `[ABSENT]`), and (c) the **orchestrator is separable from the checker**, so
  "Jepsen vs bespoke" was a false binary. The original bespoke advocate (`chaos-lens`) conceded "no ramp"
  was overstated (Go also absent) and that the hand-written glue is the real risk → formalized a **checker
  self-test gate**. Net 3/3 for bespoke-Java + Porcupine. The lone residual (primary checker: Porcupine vs
  Elle-default) was a **recorded 2-1 lead resolution** (per-key partitioning bounds Porcupine's runtime;
  neutral history keeps Elle a drop-in); the dissenter accepts it on the merits. **The advocates crossed
  over** — the strongest sign the debate moved on evidence.
- **Load-bearing facts re-confirmed by file:line (A1/A2 shifted lines):** `ack ≠ commit`
  (`ConfigWriteService.java:150-154` — `200 Accepted` on local append, `proposalId` a local counter, not a
  commit) → **every write is indeterminate at ack time**; default GET is a **stale** local read,
  linearizable read needs `?consistency=linearizable` (`HttpApiServer.java:233/244`); linearizable GET is
  **flaky** (150 ms ReadIndex timeout, `ConfigdServer.java:512`) → read-503 = `:info`; CheckQuorum is wired
  (`RaftNode.java:776-785`) and the ReadIndex lease is quorum-based (`:1616-1627`) → a partitioned leader
  steps down and can't serve a stale lin-read (so the stale-read bug must *actively* break the `:421`
  guard); no `--seed`/determinism seam (`ConfigdServer.java:214-215`).
- **The discrimination plan (the load-bearing deliverable — answers "who verifies the verifier"):** two
  seeded bugs, each with an exact mutation site + schedule + expected RED — (1) **lost acked write**:
  `FileStorage.java:110` `channel.force(true)`→no-op, write→read-back→kill-9→restart→value gone; (2)
  **stale read**: delete `RaftNode.java:421` `if (role != RaftRole.LEADER) return false;`, isolate the
  deposed leader, read stale — plus a 6-test **checker self-test suite** (incl. the timeout→`info`-not-
  `fail` flip) that gates the hand-written glue. A green run is meaningful ONLY after each bug turns the
  checker RED first (the A3-B exit-gate order).
- **Evidence:** `[VERIFIED-PASS]` design artifacts exist & signed — `docs/decisions/adr-0032-linearizability-harness.md`
  (Reviewers: 3× SIGN-OFF), `docs/a3-harness-design.md` (§14: 3× SIGN-OFF); per-lens findings persisted
  under `verification-runs/session-a3/findings-{ds,consistency,chaos}-lens.md`. Feasibility prototype
  `[VERIFIED-PASS]` (3-JVM + real iptables partition + kill-9/restart), scratch in `/tmp` (not committed),
  cleaned up (no stray procs / iptables rules). No code/spec/test ran because nothing was modified.
- **Component re-classification:** none (no component code touched). **R-04 OPEN → IN-PROGRESS** (design
  done; build + discrimination owed to A3-B — NOT a green gate yet). **New risks: R-12** (reconfig
  unreachable from the binary + untested under fault — deferred, no new seam in A3-B) and **R-13**
  (InstallSnapshot > 16 MiB silent-drop liveness cliff — corrects the docs' "4 MiB/offset-ignored
  chunking" framing).
- **Recorded for A3-B:** the ack-semantics decision is **(A)-now** (model every write `:info`; lost-write
  RED sourced from a read-back) — the gate does **not** require (B); **(B) a commit-confirmed synchronous
  write path is a recommended A3-B follow-on** (a real product gap: no client-visible commit confirmation
  today). Faults dropped-with-reason (clock-jump = tick-based timers; InstallSnapshot-crash = single-shot
  no-op) are analyzed, not silently omitted.
- **Stop point:** committed on `session-a3-linearizability`; **stops for human review before any
  implementation.** Teammates shut down + team cleaned up after sign-off; findings persist on disk.

### Session A3-B — Build the linearizability + fault-injection harness — close R-04 (2026-06-07)
- **Mode:** single Opus session (lead) + one Opus reviewer subagent at the end; **plan-mode first** (plan
  approved before any edit). Branch `session-a3-linearizability` (continues A3-D). First code of the A3
  build. Ledger-prep commit `b7f36e2` (R-14, R-12 reclassify, §0 evidence rule) preceded the build.
- **Built — new Maven module `configd-linz`** (added to the reactor): a real **separate-JVM** cluster
  launched from the shaded `configd-server` jar over the **real `TcpRaftTransport`** (NOT the in-process
  `SimulatedNetwork` — the R-01 blind spot), driven by a concurrent JDK-`HttpClient` workload over a small
  key set, under **OS-level faults** (iptables partitions + `kill -9`), recording a checker-neutral per-key
  op-history fed to a **trusted third-party checker — Porcupine** (Go; installed user-local, pinned via
  `go.mod`/`go.sum`; the binary is gitignored, rebuilt by `scripts/build-porcupine.sh`).
- **Load-bearing build decisions (grounded by reading source, not taken from the design verbatim):**
  - **Porcupine v1.2.0 cannot model a call-without-return** — an unmatched call makes the history
    non-linearizable, it is NOT "placeable anywhere" (verified in `checker.go`/`model.go`). So an
    indeterminate (ack≠commit / timed-out) write is encoded as a **floating `Operation`** (`Return = END`),
    the sound finite equivalent. Tractability is restored by **confirm-bound**: a write later observed by an
    OK read is pinned to that read's response time (it provably committed by then — a tighter, still-sound
    upper bound); only never-observed writes float to END. Floating only ADDS linearization freedom → it
    **cannot cause a false RED** (reviewer independently judged it sound).
  - **ack≠commit:** every write `:info`; reads pin reality; FAIL writes (503 NotLeader / 4xx — rejected
    pre-propose, never committed) and indeterminate (503/timeout) reads dropped; unique PUT tokens so a read
    pins exactly which write it observed.
  - **Single-host fault reality (verified, not assumed):** unbound loopback sockets always source from
    `127.0.0.1`, so per-node SOURCE-IP partitions are impossible without netns — partition by Raft `--dport`.
    The **F-F bridge** partition (per-pair cut) is **deferred to a netns follow-up** (recorded, not silently
    dropped). The **stale-read** discrimination was **adapted** from "isolate the deposed leader" (not
    single-host injectable: CheckQuorum steps it down, and a no-step-down mutation leaks heartbeats and
    blocks re-election) to a **lagging isolated follower** serving a local read as if linearizable — the same
    INV-L1 / FIND-0002 safety class.
  - **iptables `REJECT --reject-with tcp-reset`, not `DROP`:** DROP black-holes → the transport's
    timeout-less `connect()` on the tick thread stalls the leader (new risk **R-15**); REJECT fails fast so
    the majority makes progress while the node is still isolated — what a SAFETY test needs.
- **Exit gate — `[VERIFIED-PASS]` (all six, pasted output; discrimination BEFORE green):**
  - **(i) Checker self-test FIRST:** `CheckerSelfTest` 6/6 through the real recorder→Porcupine pipe, incl.
    test 3 — `3a timeout-as-INFO → LINEARIZABLE`; the SAME op flipped `3b → FAIL → NON-LINEARIZABLE` (the
    decisive "who checks the checker" flip); + `HistoryWriterUnitTest` 4/4 (pure-Java, runs in default CI).
  - **(ii) Discrimination (`scripts/run-discrimination.sh both`):** lost-acked-write
    (`FileStorage.appendToLog`→no-op; write→linearizable read-back confirms T_new→full-cluster `kill -9`+
    restart) — control GREEN (`post-restart read OK value='Tnew'`), mutated **RED** (`OK value=''` — a
    confirmed value VANISHED). stale-read (delete the `readIndex`/`isReadReady` leader guards) — control
    GREEN (`follower read INFO` = 503, no stale read), mutated **RED** (`follower read OK value='v1'` — a
    lagging non-leader served a superseded value). **DISCRIMINATION PASS.** Mutations applied to a scratch
    build via committed `.patch` files and reverted (production source never broken).
  - **(iii) Unmodified GREEN:** `LINEARIZABLE` across seeds 2001-2004 on BOTH **3- and 5-node** clusters
    (8/8), 4-5 OS-level faults (isolate-leader / isolate-node / kill-leader / kill-node) active throughout.
  - **(iv) Reproducibility:** seed 777 → byte-identical `schedule-777-n3.json` across two runs (matching
    sha256; 4 faults + 795 planned ops). Inputs pinned (seeded `SplittableRandom`), not which node wins.
  - **(v)** `./mvnw -fae test` → **BUILD SUCCESS** — 21,408 run, 0 fail, 0 error, 8 skipped (6 = the
    Porcupine self-test auto-skipping without `PORCUPINE_BIN`, so default CI stays green without a Go
    toolchain; 2 pre-existing).
  - **(vi) Independent Opus reviewer: CONFIRMED all three** — real separate-JVM over the real transport
    (zero non-linz `io.configd` imports; `ProcessBuilder`+`java -jar`; `destroyForcibly`=SIGKILL); timed-out
    ops `:info` not `:fail` (confirm-bound judged sound; re-ran self-test 3); both seeded bugs genuinely RED
    from real contradictions, controls GREEN (re-ran the full discrimination); clean teardown (git clean, no
    iptables rules, no stray JVMs). **No false-green or false-red risk found.**
- **Recorded honestly (residual, not smoothed over):** ONE early high-density write-heavy run produced a
  non-linearizable history under the (also-sound) float-to-END encoding; it did **not reproduce** across
  30+ subsequent runs (18 at identical high-density write-heavy conditions) under the final confirm-bound
  encoding, and the harness reliably discriminates the seeded violations and is consistently green on the
  unmodified binary. Classified as an **unresolved rare anomaly** (genuine rare edge case vs transient
  artifact — undetermined); the `PORCUPINE_DUMP` instrumentation is retained to catch it if it recurs; a
  dedicated soak is owed.
- **Component re-classification:** **R-04 `[ABSENT]`/IN-PROGRESS → `[VERIFIED-PASS]` — CLOSED.** New
  component row: linearizability harness (`configd-linz`) `[VERIFIED-PASS]`. **New risk R-15** (timeout-less
  `connect()` on the tick thread — a liveness gap, surfaced by the DROP-vs-REJECT investigation).
- **Phase A status (precise):** the root control-plane Raft group is **linearizable under the fault classes
  A3 tested** — leader/node isolation partitions, `kill -9` + restart, leader-crash chains, on 3- and
  5-node clusters — **with TWO named exceptions OUTSTANDING: R-12** (joint-consensus reconfiguration —
  unverified end-to-end AND structurally untestable until a live caller exists) and **R-14** (`ack ≠ commit`
  — read-your-writes not guaranteed). **"Phase A done with two named exceptions" — NOT unqualified-done.**
  Also owed (recorded): the **F-F bridge** partition (needs netns) and **R-15** (connect-stall).
- **Stop point:** committed on `session-a3-linearizability`; **STOPS for human review before merge** — A3-D
  design + A3-B build merge together as one unit only after this gate is green (it is). Reviewer subagent
  left idle + resumable.

<!-- Append new session entries ABOVE this line, newest-first or newest-last (pick one and keep it
     consistent). Each entry: Mode · What changed · Exit-gate command + pasted output · Component
     re-classification (rubric) · Any new R-row seams. -->
