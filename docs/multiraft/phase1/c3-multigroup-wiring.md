# Phase 1 — C3: multi-group wiring on the Phase-0 owner-executor (design note)

> N groups on the `ownerExecutor(gid)` pool; per-shard isolation proven on the REAL `MultiRaftDriver`
> (review SF1 makes this mandatory); coalesced-heartbeat flat-in-N re-confirmed at Phase-1 group counts;
> S2–S4 per-shard + cross-shard fault. Consensus-adjacent → FOUR-WAY. Status: DESIGN (code after C2 DONE).

## The two surfaces

### Surface 1 — the shared-node owner-pool isolation sim (mandatory, SF1)
The V machinery used INDEPENDENT per-shard harnesses, so cross-shard isolation was structural (no real
coupling-leak RED). C3 builds the COMPLEMENTARY fidelity: **one `MultiRaftDriver` with an
`OwnerExecutorPool` of P threads driving N groups** (`ownerExecutor(gid)=pool[gid % P]`), the production
shape. Here isolation is NOT structural — groups share owner threads — so a real coupling leak can be
injected and shown to go RED:
- Build on the existing `OwnerIsolationMultiOwnerTest` (replication-engine) which already runs N=3 groups
  on one driver+pool and proves the `assertOwnerThread` net catches a cross-group access.
- Extend with the **S2–S4 surface per group** (each group's `SimInvariants`-equivalent safety + in-node
  checks) AND **cross-shard fault schedules** (kill group A's leader / partition A) asserting group B's
  safety holds and B keeps committing.
- **The coupling-leak RED (the SF1 mandate):** inject a deliberate isolation breach — e.g. a stuck/blocked
  owner thread that starves the sibling groups it co-owns, OR a missed marshalling hop that runs group B's
  entry on group A's owner — and assert a check goes RED (the `assertOwnerThread` net already fires on the
  missed-hop class; the starvation class needs a liveness witness per group). This is what makes isolation
  genuinely non-vacuous at the shared-node fidelity.
- **Coalesced-heartbeat flat-in-N** re-confirmed at Phase-1 N (the existing `HeartbeatCoalescingTest`
  proves cost flat in G at G=1/16/256; re-run at the chosen N to confirm the property holds under the
  Phase-1 workload).

### Surface 2 — the production server wiring (N groups, the groupId fix; DL-P1-06)
Make the server able to run N>1 groups end-to-end (needed for "ready for EC2"), with N=1 byte-identical:
- **Register N groups**: `ConfigdServer:367` `addGroup(0, node)` → loop over `shardMap.shardIds()`,
  building one `RaftNode` + per-group transport per shard. At N=1 (default) this is exactly today.
- **The `RaftTransportAdapter` groupId fix (NO wire-format change — the groupId is already in the frame):**
  - *Inbound* (`RaftTransportAdapter:64-69` drops `frame.groupId()`; `ConfigdServer:1217` routes to the
    captured constant 0): thread `frame.groupId()` through — change the inbound handler to carry the
    groupId and route `driver.routeMessage(frame.groupId(), msg)`.
  - *Outbound* (`RaftTransportAdapter:52` stamps the construction-time constant): construct one adapter
    per group at wiring (each stamped with its groupId), or make the send group-parametric. The
    `CoalescingRaftTransport` wraps per group already.
  - At N=1 only group 0 exists ⇒ behavior identical; the fix is latent-correctness for N>1.
- **Per-group owner/flush/proposer wiring** already group-parametric in the driver; the server loop
  resolves `ownerExecutor(gid)` per group (the C2 de-binding).

## Rehoming stays DORMANT (DL-P1-07)
C3 activates NO placement movement — owners are the static `floorMod(gid, P)`. The D-016
re-verify-on-activation obligation does NOT trigger. (If a future phase activates rehoming, re-run the
M2b proofs with it live.)

## What C3 does NOT do
- No production wire-format change (epoch + CoalescedHeartbeat frame deferred — DL-P1-04/05); at N>1 over
  TCP, heartbeats go un-coalesced on the wire until the operator-gated EC2-prep adds the frame (the
  in-process coalescing + the sim proof stand; the wire optimization is the EC2 step).
- No EC2 run.

## Verification (C3 DONE criteria)
- Shared-node owner-pool isolation sim: S2–S4 per group + cross-shard fault green; the coupling-leak RED
  demonstrated (SF1 mandate satisfied); coalesced-HB flat-in-N at Phase-1 N.
- Server wiring: N=1 byte-identical (regression); N>1 routes inbound/outbound by real groupId (unit/
  integration test of the adapter fix — a frame stamped gid=k reaches group k, not 0).
- Four-way: implementer + diff-review + independent re-run + red-team (attack: a missed hop under the
  pool, a frame mis-stamped, an N=1 regression, the correlated-node-loss election storm).
