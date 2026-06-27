# Multi-Raft Phase 1 — Decision Log (autonomous decisions, for retroactive veto)

> Phase 1 build session (autonomous, operator asleep). Per charter §0: every decision the session
> resolves without operator input is logged here so the operator can veto it on review. STOP-only
> gates: merge to main, spending money (EC2), destructive/irreversible ops. Everything else: decide +
> log + proceed. "Disagreements recorded, not averaged."
>
> Distinct from M1's `docs/multiraft/decision-log.md` (the research self-resolved calls) and the
> operator decisions in `recommendation-summary.md`. M1's architecture is SETTLED (charter §1) — these
> are *implementation* decisions on top of it, not re-litigations.

| ID | Decision | Basis | Reversible? |
|---|---|---|---|
| **DL-P1-01** | Branched `multiraft-phase1` off `origin/main` (`c58ac1f`). Confirmed Phase 0 (owner-executor pool, `assertOwnerThread` net, coalesced heartbeats, dormant rehoming) is mainline and the working tree is content-identical to `origin/main`. | Charter §9.1 (confirm foundation mainline before building). | Yes (branch) |
| **DL-P1-02** | `ShardMap` interface + `StaticShardMap` live in **`configd-replication-engine`** (`io.configd.replication`), beside `MultiRaftDriver`. | It is the routing layer for the multi-Raft driver; the module already depends on `configd-common` (home of `ConfigScope`) and the driver is already group-parametric (`propose(gid)`, `ownerExecutor(gid)`). Keeps routing with consensus, not in the thin API module. | Yes (move) |
| **DL-P1-03** | **Verification-first**: build the multi-shard simulator (`S` shards × `R` nodes) + the 6 new invariants + a NON-VACUITY proof (a deliberately-broken router goes RED) **before** any production sharding code. The `ShardMap` interface is defined as the contract-under-test; the production `StaticShardMap` is hardened in C1. | Charter §2 Prime Directive (the Session-3 discipline): machinery first, proven non-vacuous, strict component sequencing. | n/a (process) |
| **DL-P1-04** | **WIRE epoch field DEFERRED** — Phase 1 does NOT grow `FrameCodec.HEADER_SIZE` / bump `WIRE_VERSION`. The `ShardMap.epoch()` *in-memory* field IS included (returns `0` in v1, free). | (1) M1 **DL-M1-09 explicitly left reserve-now-vs-defer to the operator** — it is not a settled decision the charter tells me to implement. (2) It is a production **wire-format break**: `HEADER_SIZE 18→26`, `WIRE_VERSION 0x01→0x02` (a hard cutover — decode strictly rejects mismatched versions, no negotiation handshake), regenerate all 16 golden fixtures, edit the hand-rolled `NettyConsensusFrameEncoder` — an outward-facing, hard-to-reverse compatibility commitment. (3) **Static-N v1 never bumps epoch**, so nothing in Phase 1 needs the wire field to *function*; the C2 redirect protocol uses per-shard **leader hints** (the existing `X-Leader-Hint` generalized), not the wire epoch. The clean v1/v2 seam is the `ShardMap` *interface*, which IS built. Teed up for the operator in the handoff with exact file:line cost. | Operator decides (flagged) |
| **DL-P1-05** | **CoalescedHeartbeat WIRE frame + oversized-frame bound DEFERRED** to the operator-gated EC2-prep (next session). Phase 1 keeps the prod wire unchanged; coalescing is proven in-process by the sim. | Same wire-format-break class as DL-P1-04 (a new `MessageType 0x11` + codec + golden fixture forces a `WIRE_VERSION` bump). It is only needed for **real multi-node over TCP** — i.e. the EC2 aggregate-throughput validation, which the charter (§5) defers to the next, money-gated session. Documented as the precise ready-to-execute residual (file:line from the wire-surface map) in the handoff. | Operator/next session |
| **DL-P1-06** | The **`RaftTransportAdapter` groupId-threading fix IS in scope** (C3): inbound must route on `frame.groupId()` (today it drops it and routes to the constant `DEFAULT_RAFT_GROUP=0`); outbound must stamp each message's real group (today a single adapter stamps `0`). | This is a pure **correctness/wiring** fix with **NO wire-format change** — the groupId byte is *already* encoded in the frame (offset 6); only the adapter→handler seam and the per-group wiring change. Without it, N>1-over-TCP collapses all groups onto group 0. The N=1 path stays byte-identical. Needed to make "ready for EC2" true. | Yes |
| **DL-P1-07** | **Rehoming stays DORMANT** — Phase 1 activates NO placement movement (static-N only). The owner is the static `floorMod(gid, poolSize)`. | Charter §1/§8: dynamic resharding deferred; rehoming dormant unless activated. Therefore the **D-016 re-verify-on-activation obligation does not trigger** this phase. | n/a |
| **DL-P1-08** | **Durability unchanged** — Level 0/1, fsync-before-ack always; Phase 1 adds NO early-ack path. Sharding instantiates N independent per-group durability paths, each identical to today's single group. | Charter §1 operator decision (Durability Level 0/1 only). | n/a |

## Open items explicitly handed to the operator (not self-decided)

- **Wire epoch reservation** (DL-P1-04) — reserve now (one deliberate `WIRE_VERSION` bump in v1) vs accept a v2 wire break. M1 DL-M1-09.
- **CoalescedHeartbeat wire frame** (DL-P1-05) — bundle into EC2-prep with the epoch decision (both are `WIRE_VERSION`-bump changes; do them together if reserving).
- **N and the 10k/s aggregate target** — deploy-derived, measured on dedicated hardware (the EC2 step). Not frozen here.

## Provenance

Foundation + seams re-verified at `file:line` this session (the three production seams — propose/routing,
wire/codec, observability/gate — were each mapped against live `HEAD`; see `design.md` §2). M1 architecture
(`adr-multiraft-{partitioning,topology,cross-shard}.md`) taken as SETTLED, not re-opened.
