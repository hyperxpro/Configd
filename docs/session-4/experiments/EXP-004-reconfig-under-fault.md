# EXP-004 — Reconfiguration under fault (Workstream D §2): split-brain prevention + mid-joint crash recovery

- **Workstream:** D §2 (reconfiguration UNDER FAULT — the historically-deadly cells, charter §6 + §10.4)
- **Register rows:** RR-018 (P1, RESOLVED in S2) — this hardens the *under-fault* half D§2 owns; no new RR (the system is correct; this de-vacuates two charter cells).
- **Owner:** consensus-reliability-engineer · matrix arbitration: review-architect
- **Status:** GREEN — two new discriminating cells landed with mutation-revert captures; no production change.

## 1. The gap (cited)

`docs/session-4/reconfiguration-status-check.md` (D §1) established that joint consensus is
REAL (no P0) and that the owed work is the *chaos* charter, not implementation. It named one
genuine carried gap from the S2 RR-018 review and the kill-matrix reconfig cells:

1. The S2 work proved the **positive** dual-majority path —
   `leaderElectionDuringJointPhaseStillCompletesTheChange` (S2 commit `4eec0b6`) elects a
   *dual-majority* survivor set `{2,3,4}` mid-joint and finalizes. What **no** test pinned is
   the **negative**, historically-deadly property: that a **single majority** (old-only or
   new-only) **cannot** elect during the joint phase. This is the Ongaro single-server-reconfig
   defect class — treating a joint config as one majority lets two disjoint majorities each
   elect a leader in overlapping terms = split brain = lost acked writes.
2. Kill-matrix reconfig rows "kill -9 pre-joint" and "kill -9 with C_old,new committed, C_new
   not" were ⏳ — the existing restart test (`recomputeConfigFromLogRestoresMembershipAcrossRestart`)
   only recovers the **simple final** config, never the **joint** state.

**Cited oracle.** `docs/consistency-contract.md` (no two leaders commit in the same term; no
acked write lost) + `docs/architecture.md` §6 (leader-loss → bounded re-election, no split-brain)
+ the runtime twins `single_server_invariant` / `no_op_before_reconfig` / `reconfig_safety`
(fire-verified in `AssertionTwinFiringTest`). The mechanism under test is
`ClusterConfig.isQuorum` (dual majority for joint) on the PreVote, real-vote, `becomeLeader`
INV-1, and commit-advancement paths, and `recomputeConfigFromLog` on the restart path.

## 2. The two new cells (deterministic, in `ReconfigurationTest$JointConsensusEndToEnd`)

### Cell A — `oldMajorityAloneCannotElectDuringJointPhase_splitBrainPrevention`

Membership change **{1,2,3} → {3,4,5}** (remove 1,2; add 4,5; only 3 shared). The ENTIRE old
cluster `{1,2,3}` is a majority of `C_old` but holds only ONE member of `C_new` (`{3}`, < 2).
Construction:
- Elect n1, commit a user write (`committedBefore`). Enter joint `C_old,new`.
- Land `C_old,new` in n2/n3's logs (one delivery round → in-memory joint via
  `recomputeConfigFromLog`), then **drop the responses** so the leader never commits the joint
  entry → never appends `C_new` → all of {1,2,3} stay genuinely mid-joint.
- Drive n2 into a clean PreVote and inject a **full old majority** of grants `{1,2,3}` directly
  (the harness's `electAmong` gates PreVotes on leader-recency, which would mask the property;
  direct injection makes the dual-majority gate the SOLE possible cause of failure).

**Oracle:** the old-majority-only PreVote must NOT advance the term / start a real election
(`currentTerm` unchanged, role stays FOLLOWER); the committed prefix is intact;
single-leader-per-term holds. **Positive control (self-validating, non-vacuous):** add ONE
new-side voter's grant (n4) → grant set `{1,2,3,4}` is now a majority of BOTH configs → the
PreVote MUST succeed and advance the term — proving the gate is specifically the `C_new`
majority, not a blanket failure.

### Cell B — `restartRecoversJointStateFromDurableJointEntry`  (+ `preJointRestartRecoversOldConfigAndChangeCanBeReproposed`)

kill -9 with `C_old,new` durable but `C_new` not. Land `C_old,new` in n2's durable log
(uncommitted), restart n2 over its retained `Storage`. The fresh `RaftConfig` lists only the
static `{1,3}` peers, so the only way the recovered node can be **joint** with
`newVoters={1,2,3,4}` is `recomputeConfigFromLog` reading the durable joint entry.
**Oracle:** restart → `isJoint()` with the exact old/new voter sets. The pre-joint companion
cell pins the recompute *fallback*: a node with no durable config entry recovers the OLD simple
config and the change stays re-proposable.

## 3. Mutation-revert captures (red/green, charter §1.3)

| Mutation | Where | Cell turned RED | Capture |
|---|---|---|---|
| **M1** — drop the dual-majority clause from `isQuorum` (the single-majority bug) | `ClusterConfig.isQuorum` | Cell A: old majority `{1,2,3}` advanced the term `1→2` and started a real election (`expected <1> but was <2>`) | `captures/exp-004-m1-split-brain-RED.txt` |
| **M2** — recovery ignores the log (`recomputeConfigFromLog()` disabled in ctor) | `RaftNode` ctor `:268` | Cell B RED (recovered config not joint: `expected <true> but was <false>`); pre-joint cell **stayed GREEN** — isolating the "recovers JOINT" claim | `captures/exp-004-m2-restart-joint-RED.txt` |

Both mutations reverted; production source byte-clean (`git diff` shows only the test file).
Post-revert GREEN: `ReconfigurationTest` (14) + `ClusterConfigTest` (23) + `ReconfigPathUnitTest`
all pass; `JointConsensusEndToEnd` 3 → 6 cells.

## 4. Verdict

The dual-majority election gate and joint-state crash recovery are **correct and now pinned by
discriminating tests** that fail under the exact historical defect. D§2's two carried cells are
de-vacuated. The mid-joint *leadership-loss → finalize* path (positive) remains covered by the
S2 `leaderElectionDuringJointPhaseStillCompletesTheChange` cell. Remaining D§2 work (not blocking
this verdict): reconfig under *sustained write load* and *during a live netem partition* on
Compose — these belong with Workstream C (no live admin seam exists for `proposeConfigChange`
until S6, so the in-sim joint path is what D exercises; recorded in the status check).

## 4a. Independent second-agent sign-off (charter §2 review rule + matrix completeness)

A second agent independently re-applied M1 and M2 from a clean tree and reproduced both REDs to
the exact assertion message + line number (M1 → Cell A `:686`, `expected <1> but was <2>`; M2 →
Cell B `:746`, `expected <true> but was <false>`, Cell B' stayed GREEN), confirmed the 6-test
GREEN baseline and byte-clean reverts (`git diff -- */src/main/` empty), and traced the
production paths to rule out vacuity: it verified the injected PreVote responses are genuinely
counted by `handlePreVoteResponse` (no term-equality guard; equal-term, not dropped), that the
non-voter early-return in `startPreVote` is NOT taken (n2 is a C_old voter), and that the
dual-majority `isQuorum` is the SOLE gate between `preVotesReceived={1,2,3}` and `startElection()`.
Verdict: **both cells SOUND, non-blocking**. One by-design note: Cell B' does not independently
discriminate the recompute regression (its expected simple-config recovery coincides with the
static-config init under M2) — that is the correct behavior for the "pre-joint" cell, and the
JOINT property is fully covered by Cell B. **D§2 in-sim is signed off.**

## 5. Reproduction

```
./mvnw -o -pl configd-consensus-core test \
  -Dtest='ReconfigurationTest$JointConsensusEndToEnd' -Dsurefire.failIfNoSpecifiedTests=false
# RED replays: apply M1 (drop the && new-majority clause in ClusterConfig.isQuorum) → Cell A fails;
#              apply M2 (comment recomputeConfigFromLog() in the RaftNode ctor)     → Cell B fails, pre-joint passes.
```
