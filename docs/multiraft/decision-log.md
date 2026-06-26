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

---

# Workstream C — the re-threaded single-group throughput MEASUREMENT (2026-06-26)

> Resolves DL-M1-05/06's forward reference: the per-shard knee, measured on hardware.
> Verdict: `docs/multiraft/workstream-c-throughput.md`. Captures: `docs/multiraft/captures/wsC-ladder/`.

| ID | Self-resolved call | Basis | Reversible? |
|---|---|---|---|
| **DL-C-01** | Measured the **production binary** (current `main`: Phase 0 + Netty), **forcing `-Dconfigd.netty.transport=epoll`** and verifying `[tier=epoll]` at runtime — not a synthetic pre-Phase-0/pre-Netty isolation point | What ships is what to measure; the consensus wire is independently transport-neutral (`jdk-vs-netty/`), and forcing epoll removes the io_uring confound (Phase V: io_uring ~2× worse for consensus) | n/a (methodology) |
| **DL-C-02** | Primary ladder at **production defaults** (admission OFF, ownerPoolSize=1, group-commit ON) | Exact apples-to-apples with the S7.5 no-admission ladder, so the delta is attributable to Phase 0 alone | n/a |
| **DL-C-03** | **Same instance TYPE as S7.5** (m6id.4xlarge), co-location confound **deliberately retained** | Holds hardware constant ⇒ the throughput delta (≈0) is attributable to software (Phase 0), not the box; the shared co-location confound is honestly labelled and flagged for a dedicated-host re-measure | n/a |
| **DL-C-04** | Reported the knee as **~800/s, UNCHANGED by Phase 0**, and attributed it to the single owner thread per group (case c reproduced) — Phase 0 lifts the *aggregate* (sharding), not the single-group knee | Ladder identical to S7.5 across 9 rates + 3-pass variance; fsync free, box ~75% idle, elections monotonic, 1–2 `configd-*` threads hot; thread-named + independently reproduced by a 2nd agent on-box | n/a (measured fact) |
| **DL-C-05** | Recommendation **leans v2-deferral** (ship fast single group + admission for v1) **but did NOT unilaterally decide v1/v2** — presented the "10k/s-sustained-is-a-hard-contract ⇒ v1-sharding" branch and left the call to the operator | Real config workload ~hundreds/s, bursty (Quicksilver ~347/s global); the 10k/s legacy target is modeled/never-validated. Charter §1/§5: recommend with numbers, operator decides scope | Yes (operator decides) |
| **DL-C-06** | Did **not fabricate a pass/fail target**; measured the honest knee and reported it against references (S7.5 ~800, etcd ~10k, Quicksilver ~347, config-burst) | Charter §1/§6.3 | n/a |

**Autonomy exercised (charter §2):** provisioned/ran/tore-down one m6id.4xlarge on-demand (≤$5 ceiling),
dry-run-green-on-the-free-box-first, verified teardown against the AWS API. Did not merge to main (left as the
operator gate). Production code unchanged (measurement only; the harness `perf/wsC-ladder.sh` is additive).
