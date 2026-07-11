# ADR-0033: Commit-confirmed write acknowledgement

- **Status:** Accepted (amendments folded in; implemented and independently verified).
- **Date:** 2026-06-10

## Context

`consistency-contract.md` section 6 promises: *"After a client writes key K and receives acknowledgment
with commit sequence S"* - the ack model is **ack-after-commit, carrying the commit sequence**.
The contract is not ambiguous about the ack point: a commit sequence exists only once the entry
is quorum-committed and applied; section 4 defines that sequence as the per-group gap-free monotonic
counter (INV-V1/V2), and section 6 has the client set its read cursor to S. What the contract does not
specify is the **failure taxonomy** a client sees when a write cannot be confirmed. This ADR
fixes that gap and authorizes the API semantic change.

Current implementation (the defect): `RaftNode.propose` returns `ACCEPTED` after leader-local
append, pre-quorum (`RaftNode.java:283-289`); `ConfigWriteService.put` maps that to
`Accepted(nextProposalId.getAndIncrement())` - a free-running local `AtomicLong`, not a commit
sequence (`ConfigWriteService.java:150-154`); `HttpApiServer` returns
`200 "Accepted: proposalId=N"` (`HttpApiServer.java:277-278`). An acked write can vanish on
leader failover (observed live in smoke testing).

## Decision

1. **Propose returns its log position.** `RaftNode.propose` returns the assigned `(index, term)`
   on acceptance (typed result; the existing rejection reasons - NOT_LEADER,
   TRANSFER_IN_PROGRESS, OVERLOADED, validation - are unchanged). No change to append,
   replication, or commit logic.
2. **A commit-outcome seam on the tick thread.** New `whenCommitOutcome(index, term, callback)`
   symmetric to the existing `whenReadReady` (`RaftNode.java:453-460`). Outcome predicates
   (definite outcomes only where Raft makes them permanent):
   - **COMMITTED** when `lastApplied >= index` and the log entry at `index` carries `term`
     (it is the proposed entry, now applied).
   - **LOST** when `lastApplied >= index` and the applied entry at `index` carries a **different
     term** - Log Matching makes that slot permanent on every replica, so this proposal can never
     commit. This is the only definite-loss trigger. Truncation of the entry without a
     replacement applied, and step-down, are not definitive (a replica still holding the entry
     can win a later election and commit it) - those cases resolve on a later apply at `index`,
     or surface as **Indeterminate** at the service deadline. This is by design: a false
     "definitely lost" that later commits is a phantom write; Indeterminate is the honest answer.
   - Sweep triggers: evaluated at **registration** (fires inline if already decidable - required
     for the single-node immediate-commit path) and on every **apply** advancing `lastApplied`.
     **InstallSnapshot covering `index`** on a node whose `lastApplied` had not reached `index`
     fires **INDETERMINATE_LOCALLY** (per-index term unrecoverable from a snapshot); the service
     maps it to Indeterminate. On the proposing leader, apply always precedes local compaction of
     `index`, so this arises only after step-down.
   All registration and firing on the tick thread, preserving the single-thread routing invariant.
   Armed-callback count is bounded by the existing `maxPendingProposals` backpressure plus
   deadline cleanup; the one-shot callback must tolerate post-deadline late completion
   (no double-complete, no map-entry leak - mirror `completeRead` cleanup).
3. **The write service blocks until outcome or deadline.** `ConfigWriteService.put/delete` waits
   (virtual-thread-friendly `CompletableFuture.get` with deadline) and returns:
   - `Committed(seq)` where **seq is the applied-mutation sequence** the state machine assigns
     when it applies this entry - the same counter the read path serves as the version cursor,
     which is what the contract's section 6 read-your-writes comparison consumes. This counter
     increments **only on mutating applies** (no-op and RCFG entries are skipped), so it is
     neither the log index nor section 4's seq exactly as originally worded; section 4's seq
     definition is reconciled to the applied-mutation sequence in the same contract pass covered
     by ADR-0035. To surface it per index, **`StateMachine.apply` changes signature from `void`
     to `long`** (returns the assigned seq; sentinel `-1` for non-mutating applies, for which the
     callback surfaces the current sequence - any S <= current version satisfies read-your-writes
     for a no-op), and `applyCommitted` threads `(index, term) -> seq` to the firing seam. This
     apply-boundary change is explicitly authorized by this ADR.
   - `Lost(leaderHint)` - definite non-commit, safe to retry.
   - `Indeterminate()` - deadline expired with the outcome unknown (e.g., quorum slow, leadership
     in flux). The write may still commit later. Distinguishable from both success and definite
     failure; client may re-read or retry (PUT/DELETE are last-writer-wins idempotent payloads).
   - Default write deadline: **5 s** (config `writeCommitTimeoutMs`), chosen to comfortably exceed
     worst-case re-election; this deadline should be revisited once the known 10x tick-unit bug in
     the tick-config path is fixed. This is a real millisecond deadline on the future, not a tick
     count - it must not route through the tick-config path affected by that bug. The existing
     150 ms `PROPOSE_TIMEOUT_MS` accept-marshalling budget is subsumed: the marshalled tick task
     performs propose and registers the commit-outcome callback atomically (same task), capturing
     `(index, term)` inside the task so a slow tick queue cannot lose the position; the service
     then waits a single end-to-end `writeCommitTimeoutMs` deadline on one future.
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
6. **Protective tripwire (load-bearing for this fix):** the single-writer owner-thread assertion
   on the store/state-machine apply path is added with this change, so any future caller violating
   the tick-thread-only apply invariant trips immediately in test/sim and counts a violation metric
   in prod.

## Discriminating proof

Pre-fix failure capture required. Test: drive the deterministic simulator with randomized leader-kill
points in the window between local append and quorum commit, plus leader network isolation and
slow-follower quorum delay; assert no write acknowledged 200/Committed is ever absent from the
post-failover committed log, across many seeds. Additionally the linearizability harness (ADR-0032)
re-runs with 200 mapped to `:ok` (not `:info`) and must stay linearizable under faults.
Mutation check: mutants in the changed region (outcome matching on `(index, term)`, the
200-only-on-commit branch) must be killed by the new tests.

## Consequences / blast radius

- Write latency now includes quorum RTT + apply (contract-compliant; was a lie before).
- Any test asserting `Accepted: proposalId=` asserted the defect and is updated; this is not
  weakening a discriminating test - those tests pinned the bug.
- `ProposalResult`/`WriteResult` API change is source-incompatible inside the repo; no external
  consumers exist.
- Backpressure path unchanged (OVERLOADED still pre-append, 429).
- Contract section 6 needs **no text change** for the ack point (it was always commit-confirmed); section 1 and
  section 6 gain the failure taxonomy above; section 4's seq definition is reconciled to the applied-mutation
  sequence (see ADR-0035, which carries the same section 4 text); the section 7 and section 8 fixes ride the
  same contract pass.
- Full in-repo consumer list: `WriteResult`/`RaftProposer`/`ProposalResult`
  types, `ConfigWriteService.put/delete`, `RaftNode.propose` + new seam,
  `ConfigdServer.raftProposer`, `HttpApiServer.handlePut/handleDelete`,
  `ConfigWriteServiceTest` (pins the defect), **`gates/smoke-multinode.sh`** (its comments
  document the defect as correct; its leader probe must tolerate commit-wait latency),
  `gates/gate-1.sh` (its stale caveat about this behavior is removed). The pre-fix smoke-test
  output is immutable audit evidence - not rewritten.
- The commit-outcome seam is exactly what ADR-0034's commit-notification interface consumes - the
  edge fan-out subscribes to the same apply-path notification, bounded per ADR-0034.

## Alternatives considered

- **202 Accepted + status-poll endpoint:** rejected - the contract's section 6 promises synchronous
  ack with S; adds a client round-trip and a new unauthenticated surface for no benefit at config-write
  rates.
- **Ack on quorum commit but before apply:** rejected - S must be the applied sequence for section 6
  read-your-writes (cursor compares against applied state at read time); apply lag on the leader
  is tick-bounded and tiny; acking pre-apply re-opens a read-your-writes hole.
- **Per-request commit listener on a separate executor:** rejected - violates the single-thread
  routing invariant; the `whenReadReady` precedent shows the tick-thread callback pattern works.

## Verification

Discriminating proof: `AckEqualsCommitTest` fails pre-fix (acked-write loss observed in 119/119/180
runs) and is green and non-vacuous post-fix; three mutation reverts each kill a paired test. The
per-index sequence mechanism is verified by `CommitOutcomeSeamTest` (with a decorrelating
`SEQ_OFFSET`), confirming the client-visible sequence is the applied-mutation counter (not the log
index), correctly threaded per-`(index, term)` so the `COMMITTED` outcome carries the right sequence.
The sweep triggers are wired to {apply, step-down (re-evaluate only - a stepped-down former leader's
entry may still commit, so it is never drained straight to `LOST`), and `InstallSnapshot` covering the
index, which yields `INDETERMINATE_LOCALLY`}; truncation without a later apply surfaces as
`Indeterminate` at the deadline, by design. One bug was caught during this work rather than after it:
`recordAppliedSeq` wiped `appliedSeqByIndex` even when no callback was pending, which the seam test
caught and which was fixed before merge.
