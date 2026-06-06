# Configd — Claude Code Session Prompts

> **Provenance.** Persisted to the repo on 2026-06-06 from the operator's session playbook. The
> only change from the pasted original is mechanical: ledger references updated from the pre-rename
> `docs/PROGRESS.md` to the live `docs/READINESS-LEDGER.md` (see that file's header). `PROMPT.md`
> in the repo root is the separate project *mission* doc and is untouched.

> **Setup (once):** agent teams are experimental — set `CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS=1`
> in `settings.json`, Claude Code ≥ v2.1.32. Set default teammate model to **Opus** in `/config`
> (teammates don't inherit the lead's `/model`). Keep teams to 3–5 teammates.
>
> **Team vs solo (decides the prompt shape):** use a **team** for parallel research / design /
> verification-review (independent lenses that challenge each other). Use a **single session**
> (optionally + a reviewer subagent) for focused, sequential, same-file implementation — a team
> there only creates file conflicts.
>
> **Watch every run:** the lead tends to (a) start doing work itself instead of delegating and
> (b) declare "done" before teammates finish. Both are countered explicitly in the prompts below;
> if you see either, tell the lead "wait for your teammates to finish before proceeding."

---

## 0. Reusable session-prompt template

Fill the `<>` slots. This encodes every invariant we rely on, so any session you instantiate
inherits the right discipline.

```text
Before anything: read docs/STATE-OF-REALITY.md and docs/READINESS-LEDGER.md in full, plus the
findings files relevant to this session under verification-runs/state-of-reality/. Treat every doc
as a CLAIM TO VERIFY, never as evidence.

GOAL (this session): <one sentence>

MODE: <single session | agent team of N Opus teammates>.
  If team: spawn <N> teammates, all Opus, with these NON-OVERLAPPING lenses: <list>. They must
  investigate independently, then CHALLENGE each other's findings before converging — disagreements
  get resolved with evidence, not deference. Lead: do NOT implement yourself; wait for all teammates
  to finish; do NOT declare done until each has reported with its exit-gate evidence.

EVIDENCE RUBRIC (mandatory on every claim, no exceptions):
  [VERIFIED-PASS] I ran it; here is the command + output.
  [VERIFIED-FAIL] I ran it; here is the failing output.
  [EXISTS-UNTESTED] present but I could not run/trigger it.
  [DOC-ONLY] described in docs/comments; no implementing code.
  [ABSENT] claimed/expected; does not exist.

FORBIDDEN: aspirational language ("will", "is designed to", "should work"); trusting a doc claim
without reading the code; marking any gate passed without a pasted command + output.

TASKS (each tied to file:line where known): <list>

EXIT GATE (the definition of done — must be a runnable demonstration, not a judgment):
  <exact command(s) + the result that proves success>

ON FINISH: append to docs/READINESS-LEDGER.md — what changed, the exit-gate command + output, and a
re-classification of every component touched (using the rubric). If you discover a new
"verified-but-untested-integration" seam (a correct component wired in unsafely/untested), log it
as a new finding — that class is this project's recurring failure mode.

If team: when the gate is met and READINESS-LEDGER.md is updated, shut down teammates, then (as
lead) clean up the team. Findings files persist on disk.
```

---

## 1. Session 0 — Decontaminate docs + lock the topology decision  *(Opus team, 3 teammates)*

This is a design-review decision with mechanical cleanup, so it's team-shaped: independent
research + an adversary stress-testing the decision to CUT global multi-region Raft.

```text
Before anything: read docs/STATE-OF-REALITY.md and docs/READINESS-LEDGER.md in full, and the
design-vs-reality findings under verification-runs/state-of-reality/. Treat every doc as a claim to
verify.

GOAL: (1) decontaminate the docs of every unsupported "verified/measured/surpasses" claim, and
(2) make and record the load-bearing architecture decision: adopt a Quicksilver-shaped topology
(centralized strongly-consistent single-group writes + asynchronous fan-out to eventually-
consistent edges with a bounded-staleness contract) and explicitly REJECT global multi-region /
hierarchical Raft write consensus.

MODE: agent team, 3 Opus teammates, investigating independently then challenging each other:
  - "prior-art-researcher": establish, with citations, how Cloudflare Quicksilver actually works
    (centralized root writes -> async fan-out to every data center -> edge-local eventually/
    sequentially-consistent reads; "reflected across the network in seconds"; v2 = MVCC + tiered
    caching). Contrast with etcd (single-group Raft) and true multi-region consensus. Produce the
    "Influenced by" + "Reasoning" material for the ADR.
  - "topology-architect": write the ADR proposing the Quicksilver-shaped decision, with concrete
    latency reasoning against the §0.1 targets.
  - "devils-advocate": argue the STRONGEST possible case FOR keeping global multi-region Raft.
    The decision only stands if it survives this challenge. Record the rejected-alternative
    analysis with concrete failure modes / latency costs, not hand-waving.

Lead does the mechanical decontamination directly (don't delegate same-file edits): 
  - Relabel gap-analysis.md:243-252 "SURPASSES Quicksilver 4/4" and all docs/performance.md
    numbers as "MODELED, NOT MEASURED" until JMH artifacts exist.
  - Remove citations of stale/cross-machine artifacts as proof: spec/tlc-results.md,
    spec/tlc-output.txt, verification-runs/tlc-rerun.log, docs/certification/verdict.md:43,86,
    verification/final-report.md. Note tlc-results.md lists the removed NoStaleOverwrite invariant
    and omits LeaderCompleteness/VersionMonotonicity vs the live ConsensusSpec.cfg:36.
  - Reconcile the four conflicting suite-size numbers (20,132/20,149/21,246/21,285) to the live
    21,394, or stop quoting suite size as a quality signal.
  - Mark architecture.md:181-205 (core/regional/edge tiers, non-voting replicas, closed-timestamp
    follower reads) as SUPERSEDED by the new ADR. Mark ADR-0010 (netty-grpc) and ADR-0026
    (opentelemetry-stub) as roadmap-or-removed since src/main uses neither.

EVIDENCE RUBRIC + FORBIDDEN: as in the template (no "verified/measured" without a runnable
artifact; cite file:line; researcher cites public sources for Quicksilver claims).

EXIT GATE: 
  - `grep -rin "surpass\|measured\|verified" docs/ | <reviewed>` shows no claim lacking a backing
    artifact (lead confirms each remaining one).
  - The topology ADR is committed, includes the devils-advocate's rejected-alternative analysis,
    and is signed off by all three teammates in its Reviewers section.

ON FINISH: append the decision + the decontamination diff summary to docs/READINESS-LEDGER.md. Shut
down teammates; lead cleans up the team.
```

---

## 2. Session A1 — Kill the Raft integration race  *(single Opus session + reviewer subagent)*

Same-file surgery on RaftNode / MultiRaftDriver / ConfigdServer — solo, NOT a team. Use plan mode
so you approve the approach before any edit.

```text
Before anything: read docs/STATE-OF-REALITY.md §5.1 and the consensus-correctness +
concurrency-readpath findings under verification-runs/state-of-reality/ in full.

GOAL: eliminate the multi-node concurrency race in which a formally-verified, single-threaded
RaftNode is driven concurrently, with a test that proves the race is gone AND would catch it if
reintroduced.

MODE: single session, Opus. Start in PLAN MODE — present your approach and the exact diff plan for
my approval before editing. After implementation, spawn ONE Opus reviewer subagent to independently
verify the threading argument and the test's discriminating power.

THE BUG (verify each link yourself, file:line):
  - Inbound Raft messages run on per-connection virtual threads: TcpRaftTransport
    (newVirtualThreadPerTaskExecutor) -> acceptLoop -> handleInboundConnection -> inboundHandler
    -> driver.routeMessage -> MultiRaftDriver.java:119 node.handleMessage(...)  -- NO lock, NO
    marshalling.
  - A separate single tick thread (ConfigdServer.java:394 "configd-tick") drives driver.tick() ->
    node.tick().
  - apply runs on inbound threads too: handleAppendEntries:851 -> applyCommitted ->
    stateMachine.apply.
  - RaftNode is explicitly "single-threaded ... No synchronization" (RaftNode.java:17-18). So
    currentTerm/votedFor/log/commitIndex/nextIndex/matchIndex and the state machine are raced;
    stateMachine.apply can double-enter.
  - The READ path was deliberately marshalled onto the tick thread (ConfigdServer.java:453); the
    inbound MESSAGE path (:257) was not. The fix should reuse that exact marshalling pattern.

TASKS:
  1. Re-serialize the Raft event loop: marshal inbound routeMessage onto the existing single
     tickExecutor (pattern at ConfigdServer.java:453), or give MultiRaftDriver its own single-
     thread executor, so tick() and handleMessage() (and thus apply) never run concurrently.
     Preserve the read-path marshalling. Justify the choice in the plan.
  2. Add a concurrent tick+inbound stress test (jcstress or a targeted harness) — this is
     [ABSENT] today. It must drive tick() and inbound handleMessage() from distinct threads under
     contention and assert no torn consensus state / no double-apply.
  3. Before A1 is done, grep for OTHER seams where a verified component is invoked across threads
     without marshalling (same failure class). List any found as new READINESS-LEDGER.md findings.

EVIDENCE RUBRIC + FORBIDDEN: as in the template.

EXIT GATE (all three, with pasted output):
  - The new stress test passes after the fix: `./mvnw -pl <module> test -Dtest=<StressTest>`.
  - PROVE it discriminates: revert the fix on a scratch branch, show the same test FAILS, restore
    the fix. (A test that can't fail proves nothing.)
  - Full suite still green: `./mvnw -fae test` -> BUILD SUCCESS.
  - The reviewer subagent independently confirms tick() and handleMessage() can no longer run
    concurrently (cite the post-fix call path).

ON FINISH: append to docs/READINESS-LEDGER.md — the fix, the discriminating-test evidence
(pass-with-fix / fail-without), the full-suite result, and a re-classification of the consensus
component from "[VERIFIED-FAIL] threading model" to its new state. Log any new cross-thread seams
found in task 3.
```

---

## How to generate the remaining sessions

For each later session in `PRODUCTION-READINESS-PLAN.md`, instantiate the template — but author it
**at the start of that session, against the current `READINESS-LEDGER.md`**, not now. Quick shape
guide:

- **A2** (real invariant net + de-vacuum TLA): single Opus session. Couple the runtime assertion
  and the spec predicate so they're the same invariant; don't wire a checker that asserts nothing.
- **A3** (linearizability + Jepsen nemesis): Opus team for the harness *design* (data-plane +
  consistency + reliability lenses), single session for the build. This is the true Phase A gate.
- **A4** (coverage/store hardening): single Opus session.
- **B1** (fan-out + edge contract): Opus team — the real design session; produce ADRs + testable
  staleness invariants + spec extension.
- **B2 / B4** (wire pipeline / edge bootstrap): single Opus sessions.
- **B3** (staleness under faults): Opus team for review, single session for the harness.
- **C1–C3** (measure / drills / rollout): single sessions + your own judgment; C3 is human-gated.

When you're ready to run any of these, ask me and I'll write its full prompt against whatever
READINESS-LEDGER.md says at that point — so it's grounded in evidence, not in today's guesses.
