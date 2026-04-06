# TLC Model Checking Results

## Specification
- **File:** ConsensusSpec.tla
- **Config:** ConsensusSpec.cfg

## Parameters
- Nodes: {n1, n2, n3}
- MaxTerm: 3
- MaxLogLen: 3
- Values: {v1, v2}

## Invariants Checked
- TypeOK
- ElectionSafety
- StateMachineSafety
- NoStaleOverwrite
- LogMatching
- ReconfigSafety
- SingleServerInvariant
- NoOpBeforeReconfig

## Results
- **Status:** PASS
- **States found:** 13,775,323
- **Distinct states:** 3,299,086
- **Depth of state graph:** 25
- **Time:** 04min 10s
- **Date:** 2026-04-10

## Invariant Verification
| Invariant | Status |
|-----------|--------|
| TypeOK | PASS |
| ElectionSafety | PASS |
| StateMachineSafety | PASS |
| NoStaleOverwrite | PASS |
| LogMatching | PASS |
| ReconfigSafety | PASS |
| SingleServerInvariant | PASS |
| NoOpBeforeReconfig | PASS |

## Bugs Found and Fixed During Model Checking

TLC uncovered several spec bugs that were fixed before the final successful run:

### Bug 1: Out-of-bounds tuple access in AppendEntry (line 399)
- **Symptom:** `RuntimeException: Attempted to access index 0 of tuple <<>>`
- **Cause:** TLA+/TLC evaluates both branches of `\/` (disjunction) even when the first is TRUE, causing `log[m][prevIdx]` to be evaluated when `prevIdx = 0`.
- **Fix:** Changed `(prevIdx = 0 \/ ...)` to `IF prevIdx = 0 THEN TRUE ELSE ...` to guard the array access.

### Bug 2: Missing log truncation in AppendEntry
- **Symptom:** LogMatching invariant violation.
- **Cause:** When a leader overwrote a conflower's log entry at a given index, only that single entry was replaced (via `EXCEPT`) without truncating subsequent entries. This left stale suffix entries with inconsistent terms.
- **Fix:** Changed the overwrite logic to use `Append(SubSeq(log[m], 1, idx - 1), entry)` to properly truncate the log from the overwrite point onward.

### Bug 3: Missing follower step-down on AppendEntries
- **Symptom:** ElectionSafety invariant violation (two leaders in same term after reconfig).
- **Cause:** The AppendEntry action only stepped the follower down when `currentTerm[n] > currentTerm[m]`, but not when terms were equal. A candidate that received a valid AppendEntries from a leader in its own term should convert to follower.
- **Fix:** Changed `state'` to unconditionally set the receiver to "follower" upon accepting AppendEntries.

### Bug 4: Configuration not recomputed on log truncation
- **Symptom:** ReconfigSafety invariant violation.
- **Cause:** When AppendEntry truncated/overwrote a config entry on a follower, the follower's `config` was only updated based on the new entry type. If the overwritten entry was a config entry and the new one was not, the stale config persisted.
- **Fix:** Added `EffectiveConfig(logSeq)` helper that scans a log sequence for the latest config entry, and used it to recompute the follower's config from the resulting log after truncation.

### Bug 5: SingleServerInvariant too strict for inherited entries
- **Symptom:** SingleServerInvariant violation.
- **Cause:** The invariant counted ALL uncommitted config entries in a leader's log, including those inherited from previous terms. A newly elected leader may inherit uncommitted config entries (both joint and final) from a predecessor.
- **Fix:** Restricted the count to config entries in the leader's own current term (`log[n][i].term = currentTerm[n]`).

### Bug 6: NoStaleOverwrite too strict
- **Symptom:** NoStaleOverwrite invariant violation.
- **Cause:** The invariant required that committed entries be identical across ALL nodes' logs, even nodes that haven't been replicated to yet. A stale leader can still have old entries until overwritten.
- **Fix:** Changed to only compare entries at indices that BOTH nodes have committed, aligning with StateMachineSafety.

### Bug 7: Leader not counting itself in quorum
- **Improvement:** AdvanceCommitIndex didn't include the leader in the quorum agreement set (matchIndex[n][n] was always 0). Added `{n} \cup {...}` to explicitly include the leader.

### Non-bug: Deadlock at model bounds
- **Symptom:** Deadlock reported by TLC.
- **Cause:** At MaxTerm/MaxLogLen bounds, no actions are enabled. This is expected boundary behavior.
- **Fix:** Added `CHECK_DEADLOCK FALSE` to the config file.
