# Multi-Raft Session M1 — Decision Log (self-resolved research questions, for retroactive veto)

> Session M1, 2026-06-21. Research-only. These are judgment calls the session resolved **without
> operator input** so the work could converge; each is listed so the operator can veto it on review.
> Distinct from the **operator decisions** in `recommendation-summary.md` (which the session deliberately
> left open). "Disagreements recorded, not averaged" per the charter.

| ID | Self-resolved call | Basis | Reversible? |
|---|---|---|---|
| **DL-M1-01** | Framed the whole arc as **intra-region throughput sharding of the centralized root** (N co-located groups in one low-RTT cluster), NOT a revival of the rejected multi-region/hierarchical-Raft geo-topology | ADR-0030 rejected *WAN-stretched* write quorums, explicitly "does not contradict ADR-0023" (which defers sharding to v2). TiKV model. | Yes (framing) |
| **DL-M1-02** | D-A = **hash-within-scope**, not pure `hash(key)`, not `ConfigScope`-as-shard-key, not range | Point-lookup read pattern (no scans) + composition with ADR-0017 (namespace) / ADR-0030 (scope tier) | Yes (deploy constant) |
| **DL-M1-03** | **Hibernation OUT for v1** — resolved the topology-researcher's "borderline-prerequisite" rating *against* prior-art's TiKV #34906 (20-min leaderless failover) | At N≈16, coalesced heartbeats already flatten idle HB cost in N, so hibernation's marginal benefit is small while its failover risk is large vs the 99.999% SLO. **Recorded as a disagreement resolved, not split.** | Yes (v2 option, if paired with health-driven wake) |
| **DL-M1-04** | D-C = **DISCLAIM**, and **supersede ADR-0023's "cross-shard 2PC over Raft" migration bullet** | §0.2 names cross-key atomicity a non-goal; `research.md:343/385/401` already rejected Percolator/parallel-commits/Spanner-2PC; the bullet was an offhand note, not a requirement | The supersession needs operator confirmation (flagged) |
| **DL-M1-05** | Throughput target **re-expressed as a derived aggregate** (`per_shard_knee × N × efficiency`), keeping 10k/s as the *aggregate* number | ADR-0031 precedent ("grow the design, don't lower the promise"); retires the modeled RR-047 single-group 10k/s. **But flagged "is 10k/s the right target?" for the operator** rather than self-deciding to change it | Yes (operator can re-set) |
| **DL-M1-06** | **N ≈ 16** as the working count, from `10000 / (800 × 0.75) ≈ 16.7` | Measured ~800/s knee × efficiency 0.75 vs the 10k/s aggregate | Yes — explicitly **deploy-derived, not frozen** (red-team) |
| **DL-M1-07** | **Adopted all 5 red-team amendments** and corrected the ADRs' readiness claims (unbuilt levers relabeled; R-01/ownerExecutor; 1/3 blast radius; wire-break epoch; N=1 default; BATCH hard requirement; day-2 observability) | The red-team's findings were verified against the live code (`FrameCodec` no-epoch; `RaftNode.propose():460` per-propose broadcast; R-01 marshalling at `ConfigdServer.java:362-365`; `CommandCodec` BATCH codec-only) | n/a (corrections) |
| **DL-M1-08** | Introduced **default N=1 below the throughput threshold** as a design recommendation (red-team-originated) | Prevents a consistency regression vs etcd/today's single group for small deployments | Yes — operator confirms |
| **DL-M1-09** | Did **not** self-decide the **epoch wire-reservation** (reserve+bump now vs accept a v2 wire break) — left it as an explicit operator decision | It is a real cost-now-vs-break-later tradeoff with no dominant answer; verified `FrameCodec` has no reserved bytes | Open (operator decides) |

## Cross-examinations where lenses corrected each other (evidence the convergence was real)

- **Hibernation (escalation→OUT):** topology-researcher rated it "borderline-prerequisite"; prior-art-analyst surfaced TiKV #34906 (catastrophic failover). Lead resolved to **OUT for v1** (DL-M1-03), not an average.
- **Readiness (de-escalation):** the first ADR drafts (lead) labeled the per-tick-broadcast lever "validated" and treated the STATE-OF-REALITY race as live; the **red-team-critic refuted both** against the live code (R-01 fixed; broadcast still per-propose, unbuilt). Lead **reversed** the claims (DL-M1-07) — the single most material correction of the session.
- **D-C internal contradiction (surfaced):** consistency-researcher caught that ADR-0023's migration plan names a 2PC coordinator that contradicts §0.2; resolved by explicit supersession (DL-M1-04), flagged for operator confirmation rather than silently struck.

## Verification posture

In-repo load-bearing claims were re-verified by the lead at `file:line` (the seams, the timing constants,
the BATCH codec, the wire header, the R-01 marshalling, the `research.md` rejections). External prior-art
mechanisms/bugs are cited to public sources gathered by the research agents (`prior-art.md` citation
list); they were not independently re-run. No production code was written or modified this session.
