# Production-Readiness Plan — Configd

> **Plan of record.** Derived from `docs/STATE-OF-REALITY.md` (2026-06-06, §5 risks + §6 "what to
> build/verify first") and the session methodology in `PROMPT.md`. Live status is tracked in
> `docs/READINESS-LEDGER.md` (the ledger) — this file is the *map*, the ledger is the *territory*.
>
> **Sequencing principle (from the assessment):** a verified-correct component wired in unsafely is
> more dangerous than an absent one, because it looks done. So we **make the existing core
> trustworthy before building the missing data plane**: Phase A hardens and *proves* what already
> runs; Phase B builds and proves the edge data plane on top of a trustworthy core; Phase C
> measures, drills, and rolls out. Session 0 (docs + topology decision) gates Phase B because the
> data-plane design depends on the topology ADR.
>
> **Session playbook:** the prompt templates referenced below (§0 reusable template, §1 Session 0,
> §2 Session A1, …) live in `docs/SESSION-PLAYBOOK.md`. Note `PROMPT.md` in the repo root is the
> *project mission* doc — a different document that does not contain these templates.

---

## How to run a session

1. Open `docs/READINESS-LEDGER.md` and `docs/STATE-OF-REALITY.md` + the relevant `findings-*.md`;
   treat every doc as a **claim to verify**, never as evidence.
2. Instantiate the **§0 reusable template from the session playbook** for the session — author it
   *at the start of that session against the current `READINESS-LEDGER.md`*, not in advance.
3. Obey the evidence rubric; no gate is "passed" without a pasted command + output.
4. On finish, append to `docs/READINESS-LEDGER.md` (§4 entry + update §2/§3 in place).

**Team vs solo:** team = parallel research/design/verification with independent lenses that
challenge each other (spawn real Opus teammates, non-overlapping lenses, cross-examine before
converging). Solo = focused, sequential, same-file implementation (a team there only causes file
conflicts) — optionally + one reviewer subagent.

---

## Status at a glance

| Session | Title | Mode | Closes risk(s) | Status |
|---|---|---|---|---|
| **0** | Decontaminate docs + lock topology decision | Team (3 Opus) | R-06, R-08 (relabel) | NOT STARTED |
| **A1** | Kill the Raft integration race | Solo + reviewer | R-01 (and W-1/W-2 trigger) | NOT STARTED |
| **A2** | Real invariant net + de-vacuum the TLA proof | Solo | R-02, R-05(c) | NOT STARTED |
| **A3** | Linearizability + Jepsen nemesis | Team (design) → solo (build) | R-04 | NOT STARTED |
| **A4** | Coverage realism + store hardening | Solo | R-05(a,b,d), R-07 | NOT STARTED |
| **B1** | Fan-out + edge consistency contract | Team (design) | R-03 (design) | NOT STARTED |
| **B2** | Wire the fan-out → edge pipeline | Solo | R-03 (live path) | NOT STARTED |
| **B3** | Staleness under faults | Team (review) → solo (harness) | R-03 (proof) | NOT STARTED |
| **B4** | Edge bootstrap / cold-start | Solo | R-03 (completeness) | NOT STARTED |
| **C1** | Measure (real perf artifacts) | Solo | R-08 (measure) | NOT STARTED |
| **C2** | Operational drills | Solo | operability | NOT STARTED |
| **C3** | Staged rollout | Solo (human-gated) | go-live | NOT STARTED |

> Keep this table and `docs/READINESS-LEDGER.md §3` in sync. When a session moves, update both.

---

## Session 0 — Decontaminate docs + lock the topology decision  *(Team, 3 Opus)*
- **Goal:** (1) strip every unsupported "verified/measured/surpasses" claim from the docs; (2) make
  and record the load-bearing architecture decision — adopt a **Quicksilver-shaped topology**
  (centralized strongly-consistent single-group writes + asynchronous fan-out to eventually-
  consistent edges with a bounded-staleness contract) and explicitly **reject** global multi-region
  / hierarchical Raft write consensus.
- **Why now:** R-06/R-08 contaminate every downstream decision, and Phase B's data-plane design is
  undefined until the topology ADR exists. Cheap relative to its leverage.
- **Lenses (non-overlapping, cross-examine):** prior-art-researcher (how Quicksilver actually
  works, with citations) · topology-architect (writes the ADR with latency reasoning) ·
  devils-advocate (argues the strongest case FOR multi-region Raft — the decision only stands if it
  survives). Lead does the mechanical decontamination directly (same-file edits).
- **Exit gate:** `grep -rin "surpass\|measured\|verified" docs/` reviewed, no claim lacking a
  backing artifact; topology ADR committed with the devils-advocate's rejected-alternative analysis
  and all three teammates signed off in its Reviewers section.
- **Maps to** `STATE-OF-REALITY.md §6.7` + §4.1/§4.5–4.7. Full prompt: `docs/SESSION-PLAYBOOK.md` §1.

---

## Phase A — Make the existing core trustworthy

Goal of the phase: the single-node-real core stops over-claiming and starts being *provably* safe
under the concurrency it actually runs with. **A3 is the true Phase A gate.**

### A1 — Kill the Raft integration race  *(Solo + reviewer, plan-mode first)*
- **Goal:** eliminate the multi-node race where a verified single-threaded `RaftNode` is driven
  concurrently (inbound virtual threads + tick thread + apply), with a test that proves the race is
  gone **and would catch it if reintroduced**.
- **Approach:** marshal inbound `routeMessage` onto the existing single `tickExecutor` (the pattern
  already used for reads at `ConfigdServer.java:453`), or give `MultiRaftDriver` its own
  single-thread executor; preserve the read-path marshalling.
- **Exit gate (all, pasted):** new concurrent tick+inbound stress test passes after the fix;
  **discrimination proof** — revert the fix on a scratch branch, show the same test FAILS, restore;
  `./mvnw -fae test` → BUILD SUCCESS; reviewer subagent independently confirms `tick()` and
  `handleMessage()` can no longer run concurrently (post-fix call path).
- **Also:** grep for other cross-thread seams where a verified component is invoked without
  marshalling (same failure class) → log as new R-rows.
- **Closes** R-01 (and removes the trigger for W-1/W-2). Maps to `§6.1`. Full prompt: `docs/SESSION-PLAYBOOK.md` §2.

### A2 — Real invariant net + de-vacuum the TLA proof  *(Solo)*
- **Goal:** turn the runtime safety net back on, and make the formal proof constrain real state.
- **Tasks:** construct a real `InvariantChecker` bridged to `InvariantMonitor`; pass it to both
  `RaftNode` (`ConfigdServer.java:248`) and `ConfigStateMachine` (`:188`); decide
  throw-in-test / metric-in-prod. **Couple the runtime assertion and the spec predicate so they are
  the same invariant — do not wire a checker that asserts nothing.** Fix the tautological invariants
  (`ReadIndexSpec.tla:237,251`, `SnapshotInstallSpec.tla:173`); add `ReadIndexSpec` and
  `SnapshotInstallSpec` to CI (`ci.yml` runs only `ConsensusSpec`); regenerate `spec/tlc-results.md`
  from the live `.cfg`.
- **Exit gate:** a test shows a real invariant violation is *observed in the running server* (not
  just in a unit test fed a throwing checker); the de-vacuumed invariants still model-check green
  live AND demonstrably fail when negated (paste both); CI config shows all three specs run.
- **Closes** R-02 and R-05(c). Maps to `§6.2` + `§6.4`.

### A3 — Linearizability + Jepsen nemesis  *(Team for design → solo for build)*  **← Phase A gate**
- **Goal:** a real concurrent-history linearizability check (Knossos/Elle/Porcupine-style) over a
  concurrent client workload under fault injection — not the scripted single-threaded visibility
  test currently labeled "linearizability."
- **Mode:** Opus team for the *harness design* (data-plane + consistency + reliability lenses);
  single session for the build.
- **Exit gate:** the history checker runs against a concurrent workload + nemesis (partition / leader
  kill) and reports linearizable (paste run); a deliberately introduced stale-read bug makes it
  report **non-linearizable** (discrimination proof); `consistency-contract.md` linearizability
  claim re-labeled to match what the checker actually proves.
- **Closes** R-04. Maps to `§6.3`.

### A4 — Coverage realism + store hardening  *(Solo)*
- **Goal:** stop inflating the test story, and close the latent store hazards.
- **Tasks:** seed the per-node election RNG (`ConsistencyPropertyTests.java:77`) so 10k seeds are
  10k distinct schedules; fix or delete the misnamed `configChangePreservedAcrossElections`
  (`ReconfigurationTest.java:257-270`) so it actually proposes a config change and forces an
  election; stop quoting "20k tests" as suite size. Clone in `ReadResult.value()` (R-1); add an
  owner-thread assertion for single-writer (W-1); make `ConfigStateMachine` cross-thread-read fields
  `volatile` (W-2).
- **Exit gate:** seeded sweep shown to produce distinct schedules (paste evidence of divergence
  across seeds); fixed reconfig test exercises a config change under election (paste); store
  hardening lands with `./mvnw -fae test` green.
- **Closes** R-05(a,b,d) and R-07. Maps to `§6.6` + `§6.8`.

---

## Phase B — Build & prove the edge data plane

Gated on **Session 0** (topology ADR) and a trustworthy core (Phase A). Goal: either the
"globally distributed edge data plane" becomes real and proven, or the promise is deleted — no
deploy-shaped false promises survive Phase B.

### B1 — Fan-out + edge consistency contract  *(Team — the real design session)*
- **Goal:** design the committed-log → fan-out → edge pipeline and the **bounded-staleness
  contract**, as ADRs + *testable* staleness invariants + a TLA+ spec extension.
- **Lenses:** data-plane · consistency · reliability. Produce ADRs, the staleness invariant
  predicates, and the spec extension.
- **Exit gate:** ADR(s) committed with sign-off; staleness invariant stated as a checkable
  predicate (not prose); spec extension model-checks green live with a *non-vacuous* staleness
  invariant (negate-it-fails proof).
- **Addresses** R-03 (design). Maps to `§6.5`.

### B2 — Wire the fan-out → edge pipeline  *(Solo)*
- **Goal:** drain `FanOutBuffer` → `PlumtreeNode.broadcast` → transport → edge `LocalConfigStore`,
  replacing the write-only sink with a live path.
- **Exit gate:** end-to-end propagation test through the *live* pipeline (not test-code glue): a
  write at the control plane appears at an edge within the contracted bound (paste); discrimination
  proof that the test fails if the drain is disconnected.
- **Closes** R-03 (live path). Maps to `§6.5`.

### B3 — Staleness under faults  *(Team for review → solo for harness)*
- **Goal:** prove the bounded-staleness contract holds under partitions, slow links, and edge
  restarts.
- **Exit gate:** fault harness drives the documented faults and asserts the staleness bound +
  read-your-writes / monotonic-read guarantees hold (paste); a deliberately loosened bound makes it
  fail (discrimination).
- **Closes** R-03 (proof).

### B4 — Edge bootstrap / cold-start  *(Solo)*
- **Goal:** an edge joining cold converges to current state within the contract (snapshot +
  catch-up), not just steady-state deltas.
- **Exit gate:** cold-start test: fresh edge reaches consistency with the control plane within the
  bound (paste).
- **Closes** R-03 (completeness).

---

## Phase C — Measure, drill, roll out

### C1 — Measure (real perf artifacts)  *(Solo)*
- **Goal:** replace modeled perf numbers with committed JMH/soak/allocation artifacts under
  `perf/results/`; relabel `docs/performance.md` and the "SURPASSES Quicksilver 4/4" verdict to
  match measured reality (or restate as modeled).
- **Exit gate:** committed perf artifacts + a doc scorecard whose every number cites a committed
  artifact.
- **Closes** R-08 (measure). Maps to `§4.5–4.6`.

### C2 — Operational drills  *(Solo)*
- **Goal:** exercise the operator runbooks against the real system (failover, rollback, edge loss);
  fix what the drills break.
- **Exit gate:** each drill executed with pasted evidence; runbook corrected where reality diverged.

### C3 — Staged rollout  *(Solo, human-gated)*
- **Goal:** canary → staged → GA, with explicit human approval at each promotion. **No autonomous
  promotion.**
- **Exit gate:** human sign-off recorded per stage; rollback rehearsed before each promotion.

---

## Dependency graph (text)

```
Session 0 ──┬─────────────────────────────► (gates) Phase B
            │
Phase A:  A1 ─► A2 ─► A3* ─► A4      (A3 is the Phase A gate; A4 may run alongside A2/A3)
                         │
                         ▼
Phase B:  B1 ─► B2 ─► B3 ─► B4
                         │
                         ▼
Phase C:  C1 ─► C2 ─► C3 (human-gated)
```
\* A1 must precede A3 (a linearizability check over a racy server proves nothing); A2's runtime net
should be on before A3 so violations are observable; A4's store hardening is independent and can
land any time after A1.
