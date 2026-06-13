# Workstream D §1 — Reconfiguration status check (the P0-escalation gate)

Charter §6 step 1: *re-verify joint consensus against current code; if reconfiguration is
a stub or single-server-change shortcut, STOP and escalate a P0.*

## Verdict: joint consensus is REAL — NO P0 escalation.

Evidence (current code, this session):
- `RaftNode.proposeConfigChange` (≈`:514`) implements **joint consensus**: it appends a
  `C_old,new` joint entry, and on its commit the leader auto-appends `C_new`, transitioning
  to the simple new config — not a single-server add/remove shortcut.
- `ClusterConfig` carries the joint state; `isQuorum(activeSet)` enforces the **dual
  majority** (both old and new majorities) during the joint phase — used by CheckQuorum,
  commit advancement, and ReadIndex (verified in `tickHeartbeat`/`buildActiveSetAndReset`).
- The three reconfig safety twins — `single_server_invariant`, `no_op_before_reconfig`,
  `reconfig_safety` — exist and are **fire-verified** in `AssertionTwinFiringTest` and
  call-site-pinned in `InvariantCallSiteTest.proposeConfigChangeInvokesReconfigTwins`.
- RR-018 (P1) is RESOLVED for the in-sim half: `ReconfigurationTest$JointConsensusEndToEnd`
  completes a real `{1,2,3}→{1,2,3,4}` joint→final transition, a post-reconfig election, and
  a restart-with-recompute-from-log; the mutant-kill capture
  (`docs/session-2/captures/rr-018-test-devacuation.txt`) shows the `isConfigChangeEntry`
  guard mutant breaks the new tests.

## Owed work moves to D §2 (reconfiguration UNDER FAULT), not implementation.

Because the mechanism is real, Workstream D is the *chaos* charter (membership change under
load / partition / leader crash mid-joint / kill -9 mid-reconfig per phase boundary,
reusing the B2 kill matrix), **not** a P0 implementation effort.

### One genuine gap to target (carried from the S2 RR-018 review, non-blocking there):
`leaderElectionDuringJointPhaseStillCompletesTheChange` does **not** actually elect during
the joint phase — instrumentation at the S2 review showed `isJoint()==false` by the election
point (the transition had already finalized in the 30-round delivery). So **no test yet
exercises a leader crash / election while the config is genuinely joint** (`C_old,new`
committed, `C_new` not yet). That is exactly charter D §2's "leader crash mid-joint-config"
and the historical single-server-change commit-bug class. The D experiments must:
1. Assert `isJoint()` true at the injection point (bound delivery to commit `C_old,new` but
   not `C_new`), then crash/transfer the leader and verify the new leader (chosen under the
   dual majority) finalizes the transition with no committed-entry loss and no split-brain.
2. kill -9 at each reconfig phase boundary (pre-joint / joint-committed / final-committed)
   reusing the B2 kill matrix → restart → recovered config equals committed config.

### Live admin seam stays S6.
`proposeConfigChange` still has zero non-test callers (no admin API). Live reconfig
fault-testing is structurally blocked until the S6 admin seam exists; the in-sim joint
consensus path is what D exercises here.
