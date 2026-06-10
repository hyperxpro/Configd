# ADR-0033: Commit-confirmed write acknowledgement (RR-004)

- **Status:** Accepted (review-architect APPROVED-WITH-CHANGES — amendments folded in; consensus-correctness-engineer co-signed as implementer; both sign-offs recorded below per Session-2 rule 7). Implemented in Session 2 (RR-004); see `docs/session-2/captures/rr-004-prefix-failure.txt`.
- **Date:** 2026-06-10
- **Session:** 2 (Control-Plane Correctness)
- **Finding:** RR-004 (P0) — ack ≠ commit; contract §6 CONTRADICTED (CM-009/CM-046/CM-059)

## Context

`consistency-contract.md` §6 promises: *"After a client writes key K and receives acknowledgment
with commit sequence S"* — the ack model is **ack-after-commit, carrying the commit sequence**.
The contract is not ambiguous about the ack point: a commit sequence exists only once the entry
is quorum-committed and applied; §4 defines that sequence as the per-group gap-free monotonic
counter (INV-V1/V2), and §6 has the client set its read cursor to S. What the contract does NOT
specify is the **failure taxonomy** a client sees when a write cannot be confirmed. This ADR
fixes that gap and authorizes the API semantic change.

Current implementation (the defect): `RaftNode.propose` returns `ACCEPTED` after leader-local
append, pre-quorum (`RaftNode.java:283-289`); `ConfigWriteService.put` maps that to
`Accepted(nextProposalId.getAndIncrement())` — a free-running local `AtomicLong`, not a commit
sequence (`ConfigWriteService.java:150-154`); `HttpApiServer` returns
`200 "Accepted: proposalId=N"` (`HttpApiServer.java:277-278`). An acked write can vanish on
leader failover (observed live, smoke-test.md §3).

## Decision

1. **Propose returns its log position.** `RaftNode.propose` returns the assigned `(index, term)`
   on acceptance (typed result; the existing rejection reasons — NOT_LEADER,
   TRANSFER_IN_PROGRESS, OVERLOADED, validation — are unchanged). No change to append,
   replication, or commit logic.
2. **A commit-outcome seam on the tick thread.** New `whenCommitOutcome(index, term, callback)`
   symmetric to the existing `whenReadReady` (`RaftNode.java:453-460`). Outcome predicates
   (amended per review finding b — definite outcomes only where Raft makes them permanent):
   - **COMMITTED** when `lastApplied >= index` and the log entry at `index` carries `term`
     (it is the proposed entry, now applied).
   - **LOST** when `lastApplied >= index` and the applied entry at `index` carries a **different
     term** — Log Matching makes that slot permanent on every replica, so this proposal can never
     commit. This is the ONLY definite-loss trigger. Truncation of the entry without a
     replacement applied, and step-down, are NOT definitive (a replica still holding the entry
     can win a later election and commit it) — those cases resolve on a later apply at `index`,
     or surface as **Indeterminate** at the service deadline. This is by design: a false
     "definitely lost" that later commits is a phantom write; Indeterminate is the honest answer.
   - Sweep triggers: evaluated at **registration** (fires inline if already decidable — required
     for the single-node immediate-commit path) and on every **apply** advancing `lastApplied`.
     **InstallSnapshot covering `index`** on a node whose `lastApplied` had not reached `index`
     fires **INDETERMINATE_LOCALLY** (per-index term unrecoverable from a snapshot); the service
     maps it to Indeterminate. On the proposing leader, apply always precedes local compaction of
     `index`, so this arises only after step-down.
   All registration and firing on the tick thread, preserving the R-01 single-thread invariant.
   Armed-callback count is bounded by the existing `maxPendingProposals` backpressure plus
   deadline cleanup; the one-shot callback MUST tolerate post-deadline late completion
   (no double-complete, no map-entry leak — mirror `completeRead` cleanup).
3. **The write service blocks until outcome or deadline.** `ConfigWriteService.put/delete` waits
   (virtual-thread-friendly `CompletableFuture.get` with deadline) and returns:
   - `Committed(seq)` where **seq is the applied-mutation sequence** the state machine assigns
     when it applies this entry — the same counter the read path serves as the version cursor,
     which is what contract §6's read-your-writes comparison consumes. Amended per review
     finding e: this counter increments **only on mutating applies** (no-op and RCFG entries are
     skipped), so it is neither the log index nor contract §4's per-committed-entry seq as
     currently worded; §4's seq definition is reconciled to the applied-mutation sequence in the
     RR-031/RR-015 contract pass this session. To surface it per index, **`StateMachine.apply`
     changes signature from `void` to `long`** (returns the assigned seq; sentinel `-1` for
     non-mutating applies, for which the callback surfaces the current sequence — any S ≤ current
     version satisfies RYW for a no-op), and `applyCommitted` threads `(index, term) → seq` to
     the firing seam. This apply-boundary change is explicitly authorized by this ADR.
   - `Lost(leaderHint)` — definite non-commit, safe to retry.
   - `Indeterminate()` — deadline expired with the outcome unknown (e.g., quorum slow, leadership
     in flux). The write MAY still commit later. Distinguishable from both success and definite
     failure; client may re-read or retry (PUT/DELETE are last-writer-wins idempotent payloads).
   - Default write deadline: **5 s** (config `writeCommitTimeoutMs`), chosen >> worst-case
     re-election; revisit when RR-006 fixes the 10× tick-unit bug. This is a real millisecond
     deadline on the future, NOT a tick count (it must not route through the RR-006-affected
     tick-config path). Per review finding f: the existing 150 ms `PROPOSE_TIMEOUT_MS`
     accept-marshalling budget is subsumed — the marshalled tick task performs propose AND
     registers the commit-outcome callback atomically (same task), capturing `(index, term)`
     inside the task so a slow tick queue cannot lose the position; the service then waits a
     single end-to-end `writeCommitTimeoutMs` deadline on one future.
4. **HTTP mapping** (`HttpApiServer`):
   | Outcome | HTTP | Body |
   |---|---|---|
   | Committed | **200** | `Committed: seq=<S>` |
   | NotLeader (pre-append) | 503 + `X-Leader-Hint` | `Not Leader` (definite, retryable) |
   | Lost (post-append) | **503** + `X-Leader-Hint` if known | `Lost leadership before commit` (definite, retryable) |
   | Overloaded | 429 | unchanged (definite, retryable) |
   | Indeterminate | **504** | `Commit unconfirmed within deadline; outcome unknown; safe to retry or re-read` |
   | Validation | 400 | unchanged (permanent) |
   200 is returned **only after quorum commit + local apply**. No other path returns 200.
5. **`proposalId` is retired from the client API.** The response carries the commit sequence S.
6. **Protective tripwire (RR-029/W-1, load-bearing for this fix):** the single-writer
   owner-thread assertion on the store/state-machine apply path is added with this change, so
   any future caller violating the tick-thread-only apply invariant trips immediately in
   test/sim and counts a violation metric in prod.

## Discriminating proof (charter §1)

Pre-fix failure capture required. Test: drive the deterministic simulator (post-RR-010) with
randomized leader-kill points in the window between local append and quorum commit, plus leader
network isolation and slow-follower quorum delay; assert **no write acknowledged 200/Committed is
ever absent from the post-failover committed log**, across many seeds. Additionally the linz
harness re-runs with 200 mapped to `:ok` (not `:info`) and must stay LINEARIZABLE under faults.
Mutation check: mutants in the changed region (outcome matching on `(index, term)`, the
200-only-on-commit branch) must be killed by the new tests.

## Consequences / blast radius

- Write latency now includes quorum RTT + apply (contract-compliant; was a lie before).
- Any test asserting `Accepted: proposalId=` asserted the defect and is updated (recorded in the
  register row; this is not "weakening a discriminating test" — those tests pinned the bug).
- `ProposalResult`/`WriteResult` API change is source-incompatible inside the repo; no external
  consumers exist.
- Backpressure path unchanged (OVERLOADED still pre-append, 429).
- Contract §6 needs **no text change** for the ack point (it was always commit-confirmed); §1/§6
  gain the failure taxonomy above; §4's seq definition is reconciled to the applied-mutation
  sequence (with RR-031/RR-015, which own the same §4 text); §8/§7 fixes ride RR-031/RR-016.
- Full in-repo consumer list (review finding d, table in
  `docs/session-2/reviews/adr-0033-review.md`): `WriteResult`/`RaftProposer`/`ProposalResult`
  types, `ConfigWriteService.put/delete`, `RaftNode.propose` + new seam,
  `ConfigdServer.raftProposer`, `HttpApiServer.handlePut/handleDelete`,
  `ConfigWriteServiceTest` (pins the defect), **`gates/smoke-multinode.sh`** (its comments
  document the defect as correct; its leader probe must tolerate commit-wait latency),
  `gates/gate-1.sh` stale RR-004 caveat. `docs/audit-session-1/smoke-test.md` is immutable audit
  evidence — not rewritten.
- The commit-outcome seam is exactly what §4.6's commit-notification interface consumes —
  Session 3's fan-out subscribes to the same apply-path notification, bounded per ADR-0034 (to
  be written with the §4.6 work).

## Alternatives considered

- **202 Accepted + status-poll endpoint:** rejected — contract §6 promises synchronous ack with
  S; adds a client round-trip and a new unauthenticated surface for no benefit at config-write
  rates.
- **Ack on quorum commit but before apply:** rejected — S must be the applied sequence for §6
  read-your-writes (cursor compares against applied state at read time); apply lag on the leader
  is tick-bounded and tiny; acking pre-apply re-opens a read-your-writes hole.
- **Per-request commit listener on a separate executor:** rejected — violates R-01 single-thread
  routing; the `whenReadReady` precedent shows the tick-thread callback pattern works.

## Sign-off

- review-architect: APPROVED-WITH-CHANGES 2026-06-10 — must specify the real per-index seq mechanism (the client seq is the applied-mutation counter, not the log index nor contract §4 seq; `StateMachine.apply` returns void so the seq is not observable per-index at the callback) and enumerate the commit-outcome sweep triggers {apply, step-down, truncation}; see `docs/session-2/reviews/adr-0033-review.md`
- second reviewer (consensus-correctness-engineer): CO-SIGNED 2026-06-10 (as implementer) — decision implemented exactly as ratified. Review findings (b)/(e)/(f) and the full consumer table (d) are all addressed: (b) sweep triggers wired to {apply, step-down (re-evaluate only, never drain to LOST — a stepped-down former leader's entry may still commit), InstallSnapshot covering index → INDETERMINATE_LOCALLY}, truncation-without-apply surfaces as Indeterminate at the deadline by design; (e) the client seq IS the applied-mutation sequence — `StateMachine.apply` now returns it (`-1` for non-mutating/RCFG), threaded per-`(index,term)` to the seam so the COMMITTED outcome carries the correct per-index seq (verified by `CommitOutcomeSeamTest` with a decorrelating SEQ_OFFSET); (f) the 150 ms accept budget is subsumed by one end-to-end 5 s `writeCommitTimeoutMs` (real ms, not a tick count), `(index,term)` captured inside the marshalled task, one-shot callback tolerant of post-deadline late completion with tick-thread cleanup (`cancelCommitOutcome`, no map leak). Discriminating proof: `AckEqualsCommitTest` fails pre-fix (acked-write loss 119/119/180) and is green + non-vacuous post-fix; three mutation reverts each kill a paired test (capture appendix). One implementation note (not a deviation): a self-found seq-pruning bug — `recordAppliedSeq` wiped `appliedSeqByIndex` when no callback was pending — was caught by the seam test and fixed before merge. The fix itself gets independent review after me (per Session-2 rule 7).
