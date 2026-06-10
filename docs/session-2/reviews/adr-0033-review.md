# Review — ADR-0033 (Commit-confirmed write acknowledgement, RR-004 / P0)

- **Reviewer:** review-architect (Session 2)
- **Date:** 2026-06-10
- **Verdict:** **APPROVE-WITH-CHANGES**
- **One-line gate on approval:** the ADR authorizes the right *decision* (ack-after-commit, typed
  failure taxonomy, tick-thread seam), but it **mischaracterizes the code it builds on** in one
  load-bearing place: the "commit sequence S" it promises clients does **not** exist as the ADR
  describes (see finding **e**). The implementer MUST resolve (e) and (b) before code; (d) is a
  consumer list the ADR under-counts.

The decision is sound and the blast radius is acceptable. The required changes below are
corrections to the *mechanism description and seam contract*, not a different decision.

---

## Per-point findings

### a. Contract reading — **CORRECT.** No §6 text change for the ack point.

`consistency-contract.md:164` — *"After a client writes key K and receives acknowledgment with
commit sequence S"* — combined with §4 (`:117-119`: a seq is assigned only to a *committed* entry)
and §6 step 1 (`:165`: client sets `VersionCursor.version = S`) makes the ack point unambiguous:
**a commit sequence cannot exist before commit, so the ack is necessarily post-commit.** The ADR's
claim that §6 "was always commit-confirmed" is right; the implementation, not the contract, is the
liar. No edit to §6's ack sentence is needed.

The ADR is also right that the **failure taxonomy is genuinely absent** from the contract today —
§6/§1 describe only the success path. Adding it to §1/§6 is new normative text, not a rewrite.
(Note for the implementer: the contract §8 assertion table and §7 mapping have separate drift
tracked by RR-031/RR-016; do not fold those into this change — the ADR correctly scopes them out
at `:85`.)

### b. `(index, term)` outcome matching — **CORRECT Raft predicate, INCOMPLETE seam.** Must change.

The two predicates are correct Raft:
- **COMMITTED** iff `lastApplied >= index` AND `log.termAt(index) == proposedTerm` — the entry that
  applied at `index` is *this* proposal (Raft Log Matching §5.3 guarantees (index,term) uniquely
  identifies an entry's content).
- **LOST** iff `lastApplied >= index` (or the slot is decided) AND the term at `index` differs — a
  new leader overwrote the slot; this proposal can never commit *as this proposal*.

**The gap:** the ADR fires the seam only "from the apply path" (`whenCommitOutcome` "fired from the
apply path", decision §2). But the precedent it cites, `whenReadReady`, is actually swept on
**three** events, and the third is the one that closes the dead-proposal window:
1. apply — `applyCommitted` → `fireReadyCallbacks` (`RaftNode.java:1393`);
2. leadership confirm — `confirmPendingReads` → `fireReadyCallbacks` (`:1625`);
3. **step-down** — `becomeFollower` explicitly drains the callbacks (`RaftNode.java:1058-1068`),
   precisely so the HTTP future completes promptly instead of waiting out the deadline.

The dead-proposal window the charter asks about is real and the ADR does not address it:
**a follower truncates the proposed entry on a conflicting AppendEntries** (`log.appendEntries(...)`
inside `handleAppendEntries`; truncation is Raft §5.3). After truncation `commitIndex` never moves,
so `applyCommitted` produces no apply event at `index`, and the node may not step down. The LOST
predicate ("entry at index no longer carries term") is now *true* but **nothing re-evaluates it** —
the future hangs to the 5s deadline and reports **Indeterminate**, not Lost.

This is **safe** (Indeterminate is a strict superset of Lost; no false Committed, no acked-write
loss), so it does not block approval. But the ADR overclaims by stating LOST fires "when the entry
no longer carries `term`" as if promptly. **Required change:** the ADR must enumerate the
commit-outcome sweep trigger set to mirror `whenReadReady` — at minimum **{apply, term change /
step-down (`becomeFollower`), log truncation/conflict-overwrite}** — or explicitly state that
truncation-without-apply is reported as Indeterminate by design and accept the latency. The seam
registration MUST be tick-thread-only exactly as `whenReadReady` (`:447-460`).

### c. Failure taxonomy — **SOUND and defensible.** No change required; one note.

Committed / NotLeader / Lost / Overloaded / Indeterminate / Validation is complete and non-
overlapping for the write path. **504-as-Indeterminate is defensible** and correct: 504 is the only
mapping that says "I do not know the outcome" without falsely claiming success (2xx) or definite
failure (5xx-definite); the body text correctly tells the client it is safe to retry/re-read
(idempotent LWW payloads). Nothing is conflated *semantically*: NotLeader (pre-append, definite)
and Lost (post-append, definite) are kept as distinct `WriteResult` variants even though both map
to HTTP 503 + `X-Leader-Hint` — that HTTP collapse is fine (both retryable, same hint), and keeping
them distinct in the typed result is the right call. One note: `OVERLOADED` stays pre-append (429),
which is consistent with the existing `RaftNode.propose` backpressure gate (`:278-282`) — good.

### d. Blast radius / consumer list — **UNDER-COUNTED.** The ADR says "no external consumers"
(true) but the *in-repo* set the implementer must update is larger than the row implies.
**Every consumer the implementer MUST touch:**

| Consumer | File:line | What changes |
|---|---|---|
| `WriteResult` sealed interface | `ConfigWriteService.java:32-41` | replace `Accepted(long proposalId)` with `Committed(long seq)`; add `Lost(NodeId leaderHint)` and `Indeterminate()`; keep `NotLeader`/`ValidationFailed`/`Overloaded` |
| `RaftProposer` functional interface | `ConfigWriteService.java:46-56` | `boolean propose(...)` is insufficient — must surface `(index,term)` (or a richer result) so the service can register `whenCommitOutcome`. This is the interface the `ConfigdServer.raftProposer` seam implements |
| `ConfigWriteService.put` / `delete` | `ConfigWriteService.java:149-154`, `:176-181` | retire `nextProposalId` (`:84`,`:101`); block on the commit-outcome future with the 5s deadline; map to the new variants |
| `ProposalResult` | `ProposalResult.java:8-17` | `ACCEPTED` must now carry, or be paired with, the assigned `(index,term)` (`RaftNode.propose` returns this enum today at `:289`) |
| `RaftNode.propose` | `RaftNode.java:249,283-289` | return `(index,term)` on acceptance (decision §1) |
| new `RaftNode.whenCommitOutcome` | new, symmetric to `whenReadReady` `:453-460` | + the sweep wiring in `applyCommitted`/`becomeFollower`/truncation per finding (b) |
| `ConfigdServer.raftProposer` | `ConfigdServer.java:709-737` | currently returns `boolean` via a 150 ms future (`PROPOSE_TIMEOUT_MS`, `:89`); must marshal *both* the append-accept and the commit-outcome registration on `tickExecutor`, and surface the outcome to the service |
| `HttpApiServer.handlePut` | `HttpApiServer.java:275-290` | `Accepted: proposalId=` → `Committed: seq=`; add `Lost`→503 and `Indeterminate`→504 arms (switch is exhaustive over a sealed type, so it **will not compile** until updated — good tripwire) |
| `HttpApiServer.handleDelete` | `HttpApiServer.java:301-316` | same; `Deleted: proposalId=` → `Committed: seq=` (or keep a delete-specific 200 body, but seq-bearing) |
| `ConfigWriteServiceTest` | `configd-control-plane-api/src/test/.../ConfigWriteServiceTest.java:11-16,65-80` | `putAcceptedByLeader`, `deleteAccepted`, **`proposalIdsIncrement`** all assert the defect; rewrite to assert `Committed(seq)` semantics. (Per ADR `:79-81`: this is updating tests that pinned the bug, not weakening a discriminating test.) |
| **`gates/smoke-multinode.sh`** | `:18,:82-88,:95-102,:144-146` | **load-bearing** — the script *documents the defect as correct*: comments "200 == local-append ACK, NOT quorum commit (R-14)" (`:18`,`:95`,`:101`) are now FALSE; leader detection via "node that answers a probe PUT with 200" (`:82-88`) now writes a **really-committed** `__leader_probe__` entry to the log (side-effect change) and must tolerate the 5s outcome wait vs its `--max-time 2/3` curls |
| `gates/gate-1.sh` | `:e` step drives smoke-multinode + step (a) runs the suite incl. the test above; the RR-004 caveat block (`gate-1.sh` header, "RR-004: the suite asserts the IMPLEMENTED pre-commit-ack behavior") becomes stale once the fix lands |
| `docs/audit-session-1/smoke-test.md` | `§3` (`:104,:121-126,:271-280`) | narrates `Accepted: proposalId=N` "returned the instant the leader appends" as the *observed defect*; it is audit evidence — do **not** rewrite history, but the register row should note the body format changed |

Not consumers (verified clean): `configd-distribution-service`, `configd-observability` (no
`WriteResult`/`proposalId` refs); `SnapshotInstallSpecReplayerTest` matched only on the word
"Accepted" in an unrelated context.

### e. The seq the client gets — **THE LOAD-BEARING DEFECT. The ADR papers over a real gap.**

The ADR claims (decision §3, and consequence `:43`): *"seq is the per-group commit sequence of the
applied entry … the sequence the state machine assigns at apply — gap-free per INV-V2 — surfaced
through the commit-outcome callback."* **Three things are wrong:**

1. **seq ≠ log index, and seq ≠ contract §4 seq.** `ConfigStateMachine.sequenceCounter` increments
   **only on mutating applies** — no-op entries are skipped (`ConfigStateMachine.java:249-251`,
   "the sequence counter is not incremented"), and config-change (RCFG) entries **never reach
   `apply` at all** (`RaftNode.applyCommitted:1383-1387` routes them to
   `handleCommittedConfigChange`, bypassing the state machine). The log index, by contrast,
   advances on *every* committed entry incl. the leader's election no-op and every reconfig entry.
   So `sequenceCounter` is gap-free over **mutations only** — a *third* quantity that is neither the
   log index nor the contract §4 "every committed entry gets prev+1" sequence (`contract §4:118`).
   The ADR's "== INV-V2 gap-free" is true *for the mutation stream* but the ADR conflates it with
   the contract's per-committed-entry seq. **This must be stated honestly:** the client seq is the
   *applied-mutation* sequence (`store.currentVersion()`-based), and the contract §4 definition of
   seq must be reconciled to that (coordinate with RR-031's §4 reconciliation, and RR-015's HLC
   reconciliation — they touch the same §4 text).

2. **The seq is not observable per-index at the callback point.** `StateMachine.apply` returns
   `void` (`StateMachine.java:18`). The only accessor is `ConfigStateMachine.sequenceCounter()`
   (`:690-693`), which returns the **current** counter. `applyCommitted` applies entries in a
   `while` loop (`RaftNode.java:1357-1390`) — multiple entries can be applied in one tick. If the
   callback for `index` reads `sequenceCounter()` after the sweep, it gets the seq of the **last**
   applied mutation, not the one at `index`. **There is no per-`index` → seq map exposed.** The
   mechanism the ADR's parenthetical assumes ("surfaced through the commit-outcome callback") does
   not exist.

3. **Building it requires a signature/seam change the ADR does not authorize.** To surface the
   correct seq for a specific `index`, either `StateMachine.apply` must return the assigned seq (and
   `applyCommitted` must thread it to a per-index outcome record), or `ConfigStateMachine` must
   publish an `(index → seq)` mapping the seam can read. The ADR's §2 says "No change to append,
   replication, or commit logic" and frames the seam as pure-additive — but a correct seq requires
   touching the apply→SM boundary. **Required change:** the ADR must specify *how* the per-index seq
   is captured (recommend: `StateMachine.apply` returns `long appliedSeq`, or a no-op sentinel for
   non-mutating/RCFG entries, and `applyCommitted` records `(index,term)→seq` for the firing seam).
   Until that is specified, the "Committed(seq)" contract is unimplementable as written.

This single finding is why the verdict is APPROVE-WITH-CHANGES rather than APPROVE.

### f. Single-thread invariant / seam fit — **FITS. Two hazards to pin, no deadlock.**

- **VT-friendliness is real.** The HTTP server uses `Executors.newVirtualThreadPerTaskExecutor()`
  (`HttpApiServer.java:85`). Blocking `CompletableFuture.get(deadline)` on the write VT, with the
  future completed *from the tick thread*, parks the VT and frees the carrier — exactly the read
  path's proven pattern (`ConfigdServer.java:483-528`). **No carrier starvation.**
- **No deadlock**, provided the tick thread never *waits* on a commit-outcome future. Registration
  must be fire-and-return on the tick thread (like `whenReadReady`, which runs the callback inline
  if already ready and otherwise just `put`s it — `RaftNode.java:455-459`). The seam must follow
  this shape; do not have the tick thread join the HTTP future.
- **Hazard 1 — two budgets.** The append-accept marshalling already times out at
  `PROPOSE_TIMEOUT_MS = 150 ms` (`ConfigdServer.java:89`, used by `raftProposer:723`). The new write
  deadline is **5 s**. These are different waits; the implementer must make the 150 ms accept a
  *sub-step* of the 5 s outcome wait (or unify them), and must NOT let a 150 ms accept-timeout report
  Indeterminate when the entry actually appended — the existing raftProposer already documents
  at-least-once (`:704-707`): the queued `driver.propose` may still append after the future
  times out, so a naive port would lose the `(index,term)` and force Indeterminate. Capture
  `(index,term)` *inside* the marshalled task before completing the accept future.
- **Hazard 2 — late completion after deadline.** Per finding (b), an outcome may arrive after the 5 s
  Indeterminate is returned. The seam's one-shot callback must tolerate the registrant already
  having abandoned the wait (mirror `completeRead`/timeout cleanup at `ConfigdServer.java:518-526`):
  no double-complete, no leak of the callback map entry.
- Note RR-006 (10× tick-unit bug) is correctly flagged as a future revisit of the 5 s default
  (ADR `:48-49`); since `writeCommitTimeoutMs` is a *real* `CompletableFuture` millisecond deadline
  (not a tick count), it is not itself subject to the RR-006 10× error — confirm the implementer
  wires it as a real millisecond timeout, not through the tick-count config path.

---

## Required changes (summary, for the implementer)

1. **(e) — blocking:** specify the real seq mechanism. The client seq is the *applied-mutation*
   counter (`ConfigStateMachine.sequenceCounter`), which skips no-ops and RCFG entries and is **not**
   the log index nor the contract §4 per-committed-entry seq. Make `StateMachine.apply` return the
   assigned seq (sentinel for non-mutating/RCFG) and thread it to a per-`(index,term)` outcome record
   so the callback surfaces the *correct* seq for *that* index. Reconcile contract §4's seq
   definition (with RR-031/RR-015, same §4 text).
2. **(b) — blocking:** enumerate the commit-outcome sweep trigger set to mirror `whenReadReady` —
   {apply, step-down/`becomeFollower`, truncation/conflict-overwrite} — or explicitly accept that
   truncation-without-apply surfaces as Indeterminate (latency-bounded by the 5 s deadline).
3. **(d):** update the full consumer list above — including the two gate scripts and the test that
   pins the defect.
4. **(f):** reconcile the 150 ms accept timeout with the 5 s outcome deadline; capture `(index,term)`
   inside the marshalled task; make the one-shot callback tolerant of post-deadline late completion.

No change required to: the decision itself, the HTTP status mapping table, the 504-as-Indeterminate
choice, the §6 ack-point text, or the RR-029/W-1 owner-thread tripwire fold-in (good — it is the
right protection for the apply seam and is load-bearing as stated).
