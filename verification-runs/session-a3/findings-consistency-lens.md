# Findings — consistency-lens (Session A3-D design)

Lens: WHAT IS CHECKED and HOW + the DISCRIMINATION PLAN. Design only; no implementation.
All claims carry a rubric literal + file:line/command. Lines re-confirmed this session (A1/A2 shifted them).

## 0. Re-confirmed load-bearing facts

- **ack ≠ commit** `[VERIFIED-PASS]`. `ConfigWriteService.put` returns `Accepted(nextProposalId.getAndIncrement())`
  the instant `proposer.propose()` returns true — i.e. after the leader appends to its **local** log only
  (`ConfigWriteService.java:150-154`). `proposalId` is a local `AtomicLong(1)` (`:84/:101`), unrelated to
  the Raft index/commit-seq. `node.propose()` returns `ACCEPTED` after local append + a non-blocking
  `broadcastAppendEntries()` (`RaftNode.java:283-289`; `maybeAdvanceCommitIndex` commits synchronously
  only for a single-node cluster). `raftProposer` blocks the HTTP thread only until ACCEPTED, not commit
  (`ConfigdServer.java:709-737`). **So "200 Accepted" is INDETERMINATE w.r.t. commit** — a deposed leader's
  uncommitted entry is later truncated (`RaftLog.java:299-313`).
- **Default GET is a STALE local read; linearizable read needs `?consistency=linearizable`** `[VERIFIED-PASS]`.
  `HttpApiServer.java:233` parses the query param; `:236-237` → `readService.linearizableRead` (ReadIndex);
  `:244` default → `configStore.get(key)` (stale local). `:240` returns 503 "Not Leader - cannot serve
  linearizable read" when leadership confirm fails. **The harness client MUST send the param on every read**
  or it records stale reads as linearizable (a false-green generator).
- **No history checker exists** `[ABSENT]`. grep knossos/porcupine/elle/jepsen/lincheck/wing-gong over
  `*.java/*.clj/*.go` → only `cancel*` false positives + doc mentions. The R-04 `LinearizabilityTest`
  (`ConsistencyPropertyTests.java:242-384`) is scripted single-threaded sequential visibility (confirms CX-1).
- **Toolchain:** no clojure/lein/clj, no go on this box; JDK 25 Corretto; Maven Central reachable.

## 1. Model — per-key linearizable register
Each key = an independent linearizable register; partition the recorded history by key, check each
sub-history independently. Correct semantic target: INV-W1 (`consistency-contract.md:152-153`) and §5:139-140
("Per-Key Total Order REQUIRED"); INV-L1 (`:29-32`) is its real-time instance. Sound *and* tractable:
the contract disclaims cross-GROUP order (`§5:145-148`) and A3 is fenced to the single root group, so per-key
checks cannot false-RED on a legitimate cross-key interleaving; per-key partitioning is the standard sound
reduction (Porcupine/Jepsen `independent`) collapsing O(n!) into short per-key searches. Values are made
globally unique (`clientId:opSeq:nonce`) so a read pins exactly which write it observed.

## 2. History recording fidelity
Client-side wall-clock (`System.nanoTime()` immediately before send = invoke, after full response = response;
single driver JVM → one monotonic clock). Per op: `client_id`, `op_type` (PUT / DELETE-as-write-of-⊥ / READ),
`key`, `arg` (unique token), `ret` (observed token or ⊥), `invoke_ts`, `response_ts`, `status`,
`consistency` (must be `linearizable` for reads), optional `X-Config-Version` (auxiliary only — server's own
claim, never load-bearing for the verdict).

## 3. Ack-semantics fork — DECISION: (A) now (the gate floor), (B) recommended as an A3-B follow-on
- **(A)** Record every 200-Accepted write as `:info`/indeterminate; tests the binary exactly as it ships
  (zero SUT change). Reads pin reality — an observed value promotes its info-write to "happened," and `:ok`
  reads carry the real-time backbone, so the checker retains full discriminating power.
- **(B)** Add a commit-confirmed synchronous write path (block until applied, return commit seq — buildable on
  `whenReadReady`/`lastApplied`, `RaftNode.java:424,453-460`) so writes record `:ok`. Tests the contract's
  *actual* ack model (`§1:11`, `§6:163` "acknowledgment with commit sequence S") and fixes a real product gap
  (no client-visible commit confirmation today).
- **Consequence:** under (A) the lost-write discrimination MUST be write→read-back-confirms→crash→value-gone
  (the RED is sourced from the post-crash read contradicting the pre-crash read; the ack's indeterminacy is
  irrelevant once a read proves the write happened). The **gate does not require (B)**; (B) is recommended,
  not a prerequisite.

## 4. Indeterminate-op handling (harness-correctness — the subtle bug class)
Timeout / connection-reset / 5xx-other / kill-mid-flight → `:info`, **NEVER `:fail`** (a timed-out write may
have committed; `:fail` causes BOTH false-RED and false-GREEN). Definitive non-occurrence (`:fail`/reject)
only for 503 Not Leader / 429 / 400 / 403 (all reject at-or-before `propose` returning NOT_LEADER —
`ConfigWriteService.java:138-152`). **Linearizable-read 503 (the flaky 150ms-timeout case,
`ConfigdServer.java:512`) → `:info`, never a failed read of a definite value.** Porcupine ingests `:info` as a
`call` with no matching `return` (placeable anywhere ≥ call, or omittable); Elle/Knossos as `:type :info`.
Mandatory checker self-test: a synthetic `:info`-write-T then read-T must be GREEN; the SAME op flipped to
`:fail` must go RED — else the checker mis-ingests status and every green is worthless.

## 5. DISCRIMINATION PLAN (load-bearing; each has an unmutated control → GREEN)
1. **LOST ACKED WRITE.** Mutate `FileStorage.java:110` `channel.force(true)` → no-op (and the rewrite path
   `:62`); alt: skip `storage.appendToLog` (`RaftLog.java:282-283`). Schedule: write k=T_new, ack + (mode A)
   a linearizable read-back confirming T_new, `kill -9` the leader after the read, restart from the same
   `--data-dir` → a later linearizable GET returns T_old/404. **Expected RED:** non-linearizable on k — an
   observed/committed value disappears (Porcupine: no valid linearization; Elle: ww/rw cycle on k).
2. **STALE READ.** Mutate `RaftNode.java:421` — delete the `if (role != RaftRole.LEADER) return false;`
   re-check in `isReadReady` (the FIND-0002 guard); alt: confirm leadership unconditionally at `:399-402`,
   bypassing the `confirmPendingReads` quorum (`:1616`). Schedule: PUT k=T1 to L_old, partition L_old from the
   majority, PUT k=T2 to L_new (completes before the read begins), linearizable GET k against the deposed
   L_old → returns stale T1. **Expected RED:** read returned a value older than a write that completed before
   it began — direct INV-L1 violation (`consistency-contract.md:29-32`).

A green run is meaningful ONLY after each seeded bug turns the checker RED. "Who verifies the verifier" is
answered here + by the §4 self-tests.

## 6. ADR position — CROSS-EXAMINATION OUTCOME (changed from Round 1)
Round-1 position: full Jepsen + Elle. **Withdrawn under cross-examination — all three challenges conceded:**
- Porcupine has first-class indeterminate-op support (etcd's timed-out-write pattern), so the "Elle handles
  indeterminate, Porcupine doesn't" premise is **false** — the two checkers are equal on indeterminate handling.
- The live model is a **per-key register** (`ConfigWriteService` exposes only single-key put/delete; BATCH is
  in the contract but NOT wired) — Porcupine's home turf; Elle's transactional cycle-detection power is unused.
- "Who checks the checker" does NOT discriminate Elle from Porcupine (both third-party/trusted); it only rules
  out a hand-rolled checker — neither option is. The real untrusted surface is the harness glue → §4 self-test.
- The orchestrator is **separable** from the checker → "Jepsen vs bespoke" is a false binary.
- **Lincheck = category error** (in-JVM concurrent data structures, no notion of processes/partitions/crash).

**Refined recommendation: reuse the proven Java orchestrator + a trusted checker; for a per-key register
that checker is Porcupine** (equal trust, native indeterminate support, model fit, no orchestration rebuild).
Jepsen+Elle recorded as a genuinely-strong, fairly-considered alternative rejected on model-fit +
orchestration-reuse grounds — the right tool the day multi-key atomic BATCH is wired.
