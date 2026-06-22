# Phase 0 — Workstream B — Stage 2 — M2b Handoff (H-4 CLOSED)

> **State:** M2b CLOSES H-4 (the co-tenant group-rehoming hazard). Branch `phase0-B-rethreading`
> (pushed); `main` stays pinned at the verified baseline `cedc706` (D-010) — B merges to `main` only at
> the §6 gate (M4). **M1 + M2a + M2b done; M3 (coalesced heartbeats) + M4 (gate-B + merge) remain.**

---

## 1. What M2b closed — H-4, all three failure modes proven impossible

H-4 is the co-tenant rehoming hazard: a group moving from owner thread A to B is a cross-thread handoff
of unsynchronised `RaftNode` state with a double-ownership / lost-message / torn-state window. M2a built
the MECHANISM (dormant); M2b proved it SAFE under concurrent fault and closed H-4.

| Failure mode | Closed by | Evidence |
|---|---|---|
| **No double-ownership** | the JMM jcstress proof (S2) + zero off-owner fires across the injected sweep (S3) | `RehomingDoubleOwnershipTest` — FORBIDDEN unreachable across ~116M samples; the broken-handoff control hits it (non-vacuous). `RehomingInjectedSweepTest` — 0 fires across tens of thousands of rehomes. |
| **No lost/misrouted message** | check-and-bounce (re-queue, never drop) + the preserved missed-hop detector | `RehomingHandoffTest` (missed-hop fires for never-rehomed groups), commits keep flowing in the sweep. |
| **No torn state** | `quiesceForHandoff` force-syncs before detach + the uninterruptible `.get()` barriers publish A's final state | `RehomingSubMechanismsTest` (quiesce flushes durable across a rehome), durable commit growth in the sweep. |

## 2. What was BUILT + VERIFIED (M2b)

| Piece | Commits | Verified |
|---|---|---|
| **S1 — deferred sub-mechanisms** (quiesce + FlushScheduler-retarget + abortHandoff + `isDetached`) | `3a44cf0`→`a78e662`→`1cb3009`→`c3d29ac` | Four-way: implementer + diff-review SOUND + independent re-run (consensus-core 342/0, replication-engine 138/0, server 165/0) + **red-team BROKE-IT** (Finding 1 interrupt-wedge P1, Finding 2 flush-livelock P2 — both fixed red/green) + **second-agent replay** (found the symmetric routeMessage livelock — fixed). NO P0/safety breach. `RehomingSubMechanismsTest` (4) + `RehomingRobustnessTest` (4). D-018. |
| **S2 — jcstress no-double-ownership** (the crux, JMM-level) | `3af0b06`→`57fd5e8` | Four-way verifier: SOUND + FAITHFUL + NON-VACUOUS (detector no blind spot, barrier JLS-faithful, control fair). Control hits FORBIDDEN (0.05–1%); clean unreachable across ~116M samples + non-vacuous (0,0 at 99.9%). Gate fix: rehoming proofs run at `-m quick` (sanity false-passes the control ~20%). `RehomingDoubleOwnershipTest` (3 @State). |
| **S3 — rehoming-injected S2–S4 surface** | `aec2beb` | 22 seeds, up to 16,581 rehomes/seed under concurrent multi-owner load, every group committing 600–1,230+ entries across the rehomes, **0 fires on every seed**; test-the-tester (neuter the migrating gate → RED). Deterministic sim intact: `SeedSweepTest` 20,001/0. `RehomingInjectedSweepTest`. |
| **S4 — H-4 → CLOSED + gate + this handoff** | (S4 commit) | threading-contract H-4 → CLOSED; `gates/gate-phase0.sh` (CI-wired, cumulative 1–7 + phase0); D-019. |

## 3. The mechanism as-built (for Phase-1 activation)

`MultiRaftDriver.rehomeGroup(g, target)` — quiesce→publish→adopt, orchestrated with **uninterruptible**
executor `.get()` barriers (happens-before ⇒ no torn state, no double-ownership):
1. `migrating.add(g)` — tick + marshalled work now skip / bounce `g`.
2. on the LOSING owner A: `quiesceForHandoff()` (force-sync) → `groupOwner[g]=target` (publish) →
   `beginHandoff()` (detach → the `HANDOFF` sentinel).
3. on the GAINING owner B: `adoptOwnerThread()` (ordered after the detach by the barrier).
4. `migrating.remove(g)`.
On a gaining-owner failure → `abortHandoff` rolls back to A (exact pre-rehome routing + re-adopt); a dead
losing owner leaves `g` LOUDLY wedged on HANDOFF (never silently mis-owned). The production flush is
retargeted through `dispatchFlush` (re-resolves the current owner, check-and-bounce); `flushDurable` is
owner-guarded; `routeMessage`/`runFlushOnCurrentOwner` do not livelock a wedged group (`!isDetached`).

**DORMANT in production** (single-group, `rehomeGroup` has no production caller) and **inert at N=1**.

## 4. ⚠ D-016 ACTIVATION CAVEAT (carry into Phase 1)

The mechanism ships DORMANT and **all M2b proofs are dormant-state proofs**. **Phase 1 MUST re-verify the
rehoming mechanism when it is ACTUALLY ACTIVATED by a placement policy** — the dormant-state proofs do not
transfer to live use (a live placement policy adds: when-to-rehome, concurrent add/remove vs rehome,
multi-node groups rehoming mid-replication, rehome-under-partition). Re-run the jcstress proof + the
injected sweep + the full S2–S4 surface against the ACTIVE policy before trusting live resharding.

## 5. What REMAINS in Stage 2 (resume here)

### M3 — coalesced heartbeats (cost flat in N) — NOT STARTED (its own dangerous-change budget)
- `HeartbeatCoalescer` EXISTS (`configd-replication-engine`, tested) but is UNWIRED. Wire it into the
  heartbeat send path (one heartbeat per peer-node per tick regardless of group count). This touches the
  consensus core (RaftNode's heartbeat broadcast) — DANGEROUS, behind the net, four-way.
- Prove: heartbeat traffic flat in group count; no election spuriously triggered by coalescing under
  load. Re-run S2–S4. Checkpoint.

### M4 — gate-B + merge B to main (§6 gate)
- Assemble `gates/gate-B.sh` (CI-wired, cumulative 1–7 + phase0) — can call `gate-phase0.sh`. Add the
  coalesced-heartbeat cost-flat-in-N proof. Record `cedc706` baseline-green.
- Merge B → main ONLY when gate-B is green in CI and all milestones are four-way verified.

## 6. Residual risks / notes

- **Rehoming is DORMANT** (no production caller) — see §4. The proofs are unit/JMM/macro/injected-sweep;
  Phase-1 activation re-verifies.
- **Interrupt during a handoff** is now safe (uninterruptible barriers, S1 Finding 1) — the handoff
  completes or rolls back atomically; the interrupt is re-asserted, never lost.
- **A doubly-faulted handoff** (both owners die mid-rehome) leaves `g` LOUDLY wedged on HANDOFF (every
  access fires; the flush/inbound fire once, not silently spin — S1 Finding 2 + the replay routeMessage
  fix). Loud, not silent; an operator-recoverable terminal state.
- **The sweep is single-node groups** (the real-executor harness models multi-OWNER concurrency, which the
  deterministic multi-NODE sim cannot); cross-node invariants are covered by the unchanged 20,001-seed sim.
  Phase-1 multi-node rehoming is a new surface (§4).
- **2-vCPU box:** targeted `-Dtest`, never the full reactor; the jcstress + gate chain is multi-hour →
  defer to CI. See `[[configd-environment]]`.

## 7. How to resume

Resume from the S4 commit. Prime directive holds: **four-way each dangerous step** (implementer +
diff-review + independent re-run + adversarial red-team), the net re-proven NON-VACUOUS for each class,
S2–S4 re-closed after each milestone, **STOP CLEAN at the last verified seam**. Start with M3 (coalesced
heartbeats) — its own dangerous-change budget — then M4. M1 + M2a + M2b are COMPLETE fallbacks.
