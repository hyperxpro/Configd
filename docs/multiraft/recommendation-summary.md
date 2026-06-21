# Multi-Raft — Recommendation Summary (Decision Package for Operator Sign-Off)

> **Session M1 deliverable (Phase 3). Read-only research. NO production code was written.** Date:
> 2026-06-21. This one page is the decision package. Full reasoning: the four ADRs in
> `docs/decisions/adr-multiraft-*.md` + `adr-throughput-target.md`; evidence: `docs/multiraft/prior-art.md`
> and `docs/multiraft/configd-analysis.md`. Every recommendation was attacked by a `red-team-critic` and
> its surviving critiques are folded into the ADRs. **The ADRs are Proposed, not Accepted — they await
> your sign-off.**

---

## The four recommendations (3 decisions + the corrected target)

| # | Decision | Recommendation | Key tradeoff it resolves | Prior-art borrowed |
|---|---|---|---|---|
| **D-A** | Partitioning: hash vs range | **HASH-within-scope** — `hash(namespace, key) mod N_scope`; `ConfigScope` is the tier selector, hash spreads within the tier | Configd's reads are **point lookups**, so Range's only benefit (scan locality) is inapplicable; hash kills the deployment-burst hot-shard that *is* the measured ~1000/s collapse | Google **Slicer** (hash → range-partition the hashed space); Dynamo consistent hashing |
| **D-B** | Topology: static vs dynamic | **STATIC-N (N≈16, deploy-derived) behind a `ShardMap` seam; dynamic deferred to v2** as a drop-in swap. **Co-deliver the consensus-thread fix** (prerequisite) | Both wins (throughput + 1/N blast containment) come from static-N; dynamic split/merge is the survey's worst bug-vein for **zero** extra throughput | CockroachDB **MultiRaft scheduler + coalesced heartbeats**; TiKV raftstore; Spanner **`movedir`** (deferred); TiKV `RegionEpoch` routing |
| **D-C** | Cross-shard atomicity | **DISCLAIM** — no distributed transactions; co-locate related keys → existing single-group atomic `BATCH`. Supersedes ADR-0023's "2PC over Raft" bullet | §0.2 names cross-key atomicity a **non-goal**; BUILD breaks the <150 ms budget AND forces a per-key→strict-serializable rewrite of the linz harness | etcd/ZK/Consul **intra-group txn** (= our single-shard `BATCH`); reject TiKV Percolator / CRDB intents / Spanner 2PC |
| **§0.1** | Throughput target | **Corrected to a derived, falsifiable aggregate:** `per_shard_knee × N × efficiency`, proven on hardware | Retires the modeled, never-measured 10k/s single-group figure (RR-047); separates sustained-commit from burst-absorption | etcd ~10k/s batched single-group as the per-shard envelope ceiling |

The package **coheres**: D-A hash *de-risks* D-B (removes the main forcing function for dynamic); D-C disclaim keeps the per-shard sequence/cursor model (ADR-0004/0035) intact; the framing is **intra-region throughput sharding of the centralized root** — coherent with ADR-0030 (which rejected *geo-distributed* multi-group writes, not co-located sharding).

## The heartbeat-amplification mitigation (the gating risk — Hard Rule 3)

Configd's measured ~800/s ceiling **is** single-tick-thread heartbeat starvation, and `MultiRaftDriver.tick()` ticks all groups on one thread. **Naive N-group multi-Raft is provably *strictly worse* than the single group today** (≤800/s shared N ways ≈ 50/s/shard at N=16; any group's heartbeat slip triggers an election). The design avoids this **only** by:
1. **Coalesced heartbeats** (per peer-node per tick) → heartbeat cost flat in N. *(CockroachDB/TiKV.)*
2. **A small sharded tick-executor pool** (`ownerExecutor(shardId)=pool[shardId%poolSize]`) + **per-tick broadcast-coalescing** → proposal/apply/broadcast parallelized across cores.

**Honest status (red-team-corrected): both levers are UNBUILT today** (`RaftNode.propose():460` broadcasts per-proposal; no coalesced HB), and the sharded pool **deletes the single-thread synchronization (R-01) that currently makes the non-synchronized `RaftNode` safe** — so it must rebuild per-shard single-owner-thread marshalling across {tick, routeMessage, propose, commit-callback, group-commit flush, maybeCompact, ReadIndex, metric reads}. This is **partly-greenfield consensus-core work that re-opens the S2–S4 integration-verification surface**, not a drop-in. It is the **first build-phase work**, before any shard-routing.

## Estimated effort / risk of the resulting build arc

| Phase | Work | Size | Risk | In v1? |
|---|---|---|---|---|
| **0 (gating prerequisite)** | Consensus-thread decoupling: coalesced heartbeats + sharded `ownerExecutor` pool + per-tick broadcast-coalescing; new concurrent tick+inbound+propose+flush stress test (jcstress/sim) | **Large** (consensus-core) | **High** — re-opens S2–S4 verification; the project's hardest-won surface | **Yes** |
| **1** | Static-N sharding: `ShardMap`/`StaticShardMap` (hash-within-scope), wire **epoch field + `WIRE_VERSION` bump**, `ConfigScope`→pool routing, **wire single-group `BATCH`** (CM-033) + cross-shard write guard, **per-shard observability** | Medium | Medium | Yes |
| **2** | Hardware validation: dedicated multi-box re-measure of the per-shard knee, derive N, prove the aggregate (HdrHistogram + iostat/mpstat rails) | Medium | Medium — N and 10k/s are unproven until this | Yes |
| **v2 (deferred)** | Dynamic resharding (`DynamicShardMap` + Spanner-`movedir`-style split/merge); cross-shard txns **only on a named requirement** | Large | High (the bug-vein) | **No** |

**Red-team's overall verdict:** the four decisions are **architecturally sound and correctly grounded**; the package previously **oversold readiness** (treated an already-fixed race as live, cited unbuilt levers as "validated," claimed wire/blast-radius properties the code lacks). Those are now corrected in the ADRs. One-liner: *"the decisions are right; the prerequisites are a larger, partly-greenfield consensus-integration build than first implied, and N=16 / 10k-s are deploy-derived, not validated."*

---

## What you are being asked to approve

### Two SCOPE-DEFINING decisions (these set the whole arc's length)

1. **Dynamic resharding — IN or OUT of v1?** → **Recommend OUT** (v2 extension via the `ShardMap` seam). *If IN:* the online split/merge state machine + its combinatorial fault matrix (the survey's worst bug class) lands in v1 and gates the throughput win, for no extra throughput.
2. **Cross-shard transactions — IN or OUT?** → **Recommend OUT (disclaim).** *Requires* confirming the **supersession of ADR-0023's "cross-shard transaction coordinator (2PC over Raft)" migration bullet.**

### Supporting decisions (recommended defaults; confirm or override)

3. **Approve Phase 0 as the gating prerequisite** — accept that the consensus-thread rework is partly-greenfield and re-opens S2–S4 verification (without it, sharding is a regression).
4. **Default N=1 below the throughput threshold** (small deployments keep whole-keyspace `BATCH`; shard only when measured throughput forces it) — confirm.
5. **Wire single-group `BATCH` (CM-033) as a hard co-delivery item** of sharding, plus a cross-shard-multi-key write guard — confirm.
6. **Reserve the epoch field in the wire frame and bump `WIRE_VERSION` once now** (vs. accepting a v2 wire break) — decide.
7. **Hash input = `(namespace, key)`; default spread-all-namespaces with opt-in dedicated pools (ADR-0017)** — confirm (load-bearing: it sets tenant-isolation strategy).
8. **N is deploy-derived, not frozen at 16** — accept that the per-shard knee is re-measured on a dedicated host before N is baked.
9. **Is sustained 10k/s a genuine requirement or a continuity anchor?** Confirm (real config load is rollout bursts, already handled by graceful shed).
10. **Day-2 operability is a v1 deliverable:** per-shard health series (today's metrics are group-0-only), a documented+tested reshard procedure (honestly, absent vnodes/`movedir`, a downtime re-bootstrap), and the cross-shard write guard — confirm scope.

### Cross-cutting OSS-operator note (red-team)

For an operator with no placement team, **day-2 is the weakest surface**: node add/loss = manual reshard with no online-move tooling; a hot shard's only lever is `429`-shed; per-shard health is currently invisible. Items 5, 8, 10 above exist to close this — they are not optional polish.

---

## Status

All ADRs are **Proposed**. **This session STOPS here — no build, no production code — awaiting your
sign-off** on the two scope-defining decisions (and the supporting defaults) above. On sign-off, the
first build session is Phase 0 (the consensus-thread prerequisite), not shard-routing.
