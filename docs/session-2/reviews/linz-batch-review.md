# Review — B5 linz batch second-agent verification (RR-016, RR-028, RR-004)

- **Reviewer:** review-architect (Session 2)
- **Date:** 2026-06-11
- **Scope:** commits `3ac8cef` (execution round — ADR-0033 200⇒:ok, discrimination RED, seed matrix,
  sim-history bridge), `df8450d` (rows + contract §7 cite + gate-2 spec), `3bd7cab` (anomaly soak).
- **Constraint honored:** no live clusters / no iptables — verified from captures + offline re-runs
  (configd-linz / configd-testkit, Maven, targeted; the live evidence is the captures').

---

## Verdicts

| Row | Verdict |
|---|---|
| RR-004 (ack≠commit, W-1 F-1 re-owning) | **APPROVE the linz framing — RESOLVED on the ack-model axis. My F-1 ruling: ACCEPT the re-owning to B7/RR-029.** |
| RR-016 (linz CI + contract §7) | **APPROVE — stays RESOLVED.** |
| RR-028 (gate re-verification + soak) | **APPROVE — stays RESOLVED**, honest non-reproduction framing preserved. |

One non-blocking finding (ConfigClient 5xx-other mapping vs its own Javadoc — Finding 4). No verdict
is changed by it; the captures do not depend on the 5xx path.

---

## 1. RR-004 framing — my explicit F-1 ruling: **ACCEPT the re-owning.**

My original review (`rr-004-fix-review.md`) was APPROVE-WITH-CHANGES, the one required change being
"add a 2-thread test that trips `ConfigStateMachine.assertOwnerThread`" (the RR-029/W-1 owner-thread
*violation*-path test), worded as "before the final RESOLVED flip." The linz agent flips RR-004 →
RESOLVED and re-owns that test to B7 under RR-029, arguing W-1 is protective hardening of the apply
seam, not part of the ack≠commit defect.

**I accept this, and the reasoning is sound:**
- RR-004's load-bearing defect (CF-44) is *ack ≠ commit* — HTTP 200 before quorum commit, acked
  write vanishing on failover. That defect is fixed (commit-confirmed ack, `whenCommitOutcome` seam,
  200-only-on-commit) and now **doubly proven** on two independent discriminating axes: the
  deterministic sim (`AckEqualsCommitTest`, 200 seeds × 3 fault shapes, which I re-verified last
  review) AND the live linz harness (the `lost-acked-write` mutation goes RED on a committed+applied+
  confirmed write vanishing; the unmodified build stays LINEARIZABLE with 200⇒:ok ACTIVE — 150
  committed PUTs pinned in the checked history).
- The W-1 owner-thread tripwire is a *distinct concern* (CF-34, "single-writer precondition
  unenforced") tracked by **RR-029**, which predates RR-004 and is about store/concurrency hazards,
  not the ack model. The ack model's correctness does **not** depend on the tripwire firing — it
  depends on the seam predicates, the 5s real-ms deadline, and the HTTP sealed-switch, all of which
  are tested and now live-proven. My F-1 was correct that the *violation path* needs a test; that
  test belongs with the tripwire's owner, not with the ack≠commit closure.

**Condition on which I accept (verified):** the obligation must remain tracked, not dropped. It is —
I grepped `assertOwnerThread`/`apply_owner_thread` over all test sources: **0 hits** (the test
genuinely does not exist yet, matching the row's claim), and it is owned by the concurrency/jcstress
workstream: RR-029 is OPEN with the tripwire landed, and RR-011 (jcstress, OPEN) line 280 says "FIX
HERE — … anything the RR-002 threading fix touches; curated subset into gate-2," which is the B7
home for this test. So the obligation is genuinely carried forward. **RR-004 RESOLVED on the
ack-model axis is correct.** (Recorded in the row.)

## 2. Captures internal-consistency

**Discrimination (`linz-discrimination.txt`) — both seeded bugs RED, controls GREEN, evidence chains
genuine.**
- *Lost-acked-write RED:* I read `LostWriteScenario` to confirm the RED is a genuinely
  committed+applied+confirmed write vanishing, not a mere appended entry. The scenario calls
  `putCommitted` (waits for an **OK = 200 = committed+applied** PUT), additionally requires the value
  is durably applied via a reliable default-GET, then a linearizable read-back confirms `T_new`, and
  ONLY THEN `kill -9` the whole cluster + restart. The mutation (`appendToLog` no-op) means the bytes
  are never written, so a full-cluster kill+restart loses the confirmed entry from every node (404).
  The capture's "confirmed T_new (committed + applied) → kill -9 all → restart → read OK '' → RED" is
  a sound chain. The comment correctly explains why `kill -9` (not just no-fsync) is needed: a real
  box keeps the OS page cache across a process kill, so only a genuine no-op makes the loss
  observable. The POST-RR-003 interaction note (durable_prefix_no_gap does NOT pre-empt the RED — no
  snapshot in a short run ⇒ no boundary gap ⇒ clean restart from empty WAL) is honest and correct.
- *Stale-read RED:* isolated follower serves stale `v1` after `v2` confirmed → `Illegal`. Control
  serves INFO (dropped) → GREEN. Genuine.

**Matrix (`linz-rr004-matrix.txt`) — 200⇒:ok demonstrably ACTIVE.** The checked history for seed 4242
carries **150 committed PUTs + 369 OK reads = 519 OK ops** within the 537 per-key checked ops
(64+82+65+51+57+75+75+68); the ~18 remainder are kept-but-floated writes. The 150 committed PUTs
being present and OK (not dropped as INFO) is the genuine proof the mapping is LIVE — it is NOT a
vacuous "no 200s occurred" pass. 8/8 seed-matrix LINEARIZABLE, all faults>0. **Gate-iv byte-identical:**
both `--schedule-only` runs of seed 777 give sha256 `724fd2d7…d60a` = `724fd2d7…d60a` (58042 bytes,
799 faults). Arithmetic and sha256 verified consistent.

**Soak (`linz-anomaly-soak.txt`) — 61/61 with honest framing.** 61 distinct-seed faulted runs (3-/
5-node, 35-min budget) → 61 LINEARIZABLE / 0 NON-LIN / 0 INDET. The framing is exactly right: it
explicitly says "61 runs is a bounded soak, NOT a proof of absence … recorded as 'not reproduced in
61 runs', not 'closed'," and notes the original A3-B history was not preserved. iptables INPUT drill
rules 0 after. This is the correct honest posture for a non-reproducing rare anomaly.

## 3. Offline re-runs

- **`CheckerSelfTest` 7/7 + `HistoryWriterUnitTest` 5/5** (configd-linz, `PORCUPINE_BIN` set to the
  repo binary, **0 skips**) — green. The self-test exercises the real `porcupine-check` binary.
- **Sim-history bridge (`SimHistoryCheck`) re-run myself, both directions:**
  - *Positive:* regenerated `history-4242.jsonl` via `OpHistoryTest` (39 ops), ran `SimHistoryCheck`
    → **LINEARIZABLE, exit 0** (reproduces the capture exactly: 4 keys, all Ok).
  - *Negative (anomaly injection):* I built a sim-format jsonl with a genuine stale-read anomaly
    (OK PUT v1 → read v1 → OK PUT v2 → read v2 confirms → a later read returns v1) and ran
    `SimHistoryCheck` → **NON-LINEARIZABLE, exit 1** (`key "k" ops=5 -> Illegal`). The bridge REDs on
    a real anomaly for the right reason (a confirmed-committed write un-observed by a later real-time
    read), reproducing the capture's negative path. **The bridge is not vacuously green.**

## 4. ConfigClient mapping diff (`ConfigClient.java:90-116`)

- **200 ⇒ OK** (was INFO) — correct per ADR-0033: 200 is returned only after quorum commit + local
  apply, so the write definitely happened. This is the load-bearing RR-004 flip.
- **504 ⇒ INFO** (new) — correct: Indeterminate (commit unconfirmed within the deadline) is exactly
  INFO semantics (may-or-may-not-commit; neither OK nor a definite FAIL).
- **503 ⇒ FAIL** (with one leader-hint hop) and **400/403/429 ⇒ FAIL** — unchanged; correct
  (definite non-commit; 429 Overloaded is pre-append so FAIL is right).
- **timeout / conn-refused ⇒ INFO** (catch block) — correct (may have committed).
- **CheckerSelfTest 7a/7b genuinely discriminate** (read them): 7a = OK PUT then OK read of the same
  token → LINEARIZABLE; 7b = OK PUT v1, OK PUT v2 confirmed by a read, then a *later* real-time read
  (ts 7-8, after v2's confirm at 5-6) returns v1 → NON_LINEARIZABLE. The RED case fails for the right
  reason — a confirmed-committed write cannot be un-observed by a later read (the RR-004 RYW
  guarantee). The real-time ordering of the ops is correct, so it is not a spurious RED.

**Finding 4 (non-blocking) — a 5xx-other write maps to FAIL, but the Javadoc promises INFO.** The
class Javadoc says "PUT/DELETE timeout / conn-refused / **5xx-other → INFO** (may have committed)."
But the code maps every status that is not 200/504/503 to FAIL (line 116, "400 / 403 / 429 and other
definite rejections"). A genuine HTTP 500/502 on a write (e.g. an uncaught server-handler exception
that nonetheless committed — `handlePut`→`writeService.put` has no try/catch, and the server's
`sendWriteResult` only emits {200,400,429,503,504}, so a 5xx would come from an uncaught throw) would
be recorded **FAIL** ("definitely did not happen") when it MAY have committed. For a linearizability
checker, FAIL on a may-have-committed op is the *unsafe* direction (it can mask a real lost-write: a
write that 500'd but committed, later read, would not be flagged). **Risk in practice is low** — the
server write path returns no 5xx-other by design (the sealed-switch is exhaustive), and an uncaught
throw more often surfaces as a connection drop (→ the `catch`→INFO path) than a parsed 500. The
seeded-bug captures produce 200/absent, not 5xx, so the verdicts are unaffected. **Recommendation
(route to the linz owner, non-blocking):** map any 5xx that is not 504 to INFO to match the class's
own Javadoc and the safe direction; today the code and its Javadoc disagree.

## 5. RR-016 / RR-028 row-text accuracy

- **RR-016 CI wiring — accurate.** `ci.yml:96-101` wires `actions/setup-go@v5` off
  `configd-linz/src/main/go/porcupine-check/go.mod`/`go.sum`; `gates/gate-1.sh` step (b) builds
  `porcupine-check` and runs the `PORCUPINE_BIN`-gated `CheckerSelfTest` (now **7/7, 0 skips**),
  failing LOUDLY if Go is absent / `PORCUPINE_BIN` unset, with `GATE1_SKIP_LINZ=1` reported loudly.
  The "Session-1 never-runs-in-CI evidence is now STALE" framing is honest (it was correct at audit
  time). The contract §7 cite (df8450d) corrects the replayable-complement sentence to the REAL
  `AdversarialSim + HistoryRecorder` / `OpHistoryTest` / `SimHistoryCheck` path — verified those exist
  and do what the cite says.
- **RR-028 six-gate accounting — accurate, with the right caveat preserved.** The row states which
  gates are **re-verified at HEAD** vs inherited: gate (ii) discrimination RED + controls GREEN
  [re-verified, `linz-discrimination.txt`], gate (iii) seed matrix 8/8 [re-verified,
  `linz-rr004-matrix.txt`], gate (iv) byte-identical schedule [re-verified, matching sha256]. Gate
  (i) self-tests were re-run by me (7/7 + 5/5). The anomaly **soak's "not closed — original history
  not preserved" caveat is preserved verbatim** in the row ("61 runs ≠ proof of absence … recorded as
  'not reproduced in 61 runs'"). This is the correct honest accounting.

## Re-run evidence (summary)

| Item | Result |
|---|---|
| `CheckerSelfTest` (configd-linz, PORCUPINE_BIN set) | 7/7, 0 skips, 0.29 s |
| `HistoryWriterUnitTest` | 5/5, 0 skips |
| `OpHistoryTest` → `history-4242.jsonl` | 39 ops emitted |
| `SimHistoryCheck` (positive, seed 4242) | LINEARIZABLE, exit 0 |
| `SimHistoryCheck` (negative, injected stale-read) | NON-LINEARIZABLE, exit 1 |
| `assertOwnerThread` test grep | 0 hits (W-1 violation test genuinely not yet written; tracked B7) |
| gate-iv sha256 (from capture, re-checked arithmetic) | `724fd2d7…d60a` == `724fd2d7…d60a` |

All three rows stay RESOLVED. RR-004's W-1 F-1 is re-owned to B7/RR-029 (accepted, tracked).
One non-blocking ConfigClient 5xx-mapping note routed to the linz owner.
