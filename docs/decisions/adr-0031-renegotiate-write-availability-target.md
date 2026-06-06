# ADR-0031: Renegotiate §0.1 Write-Availability Target Under Full-Region Loss

## Status

**Accepted — option (a)** (2026-06-06, ratified by the human owner). Raised by
`adr-0030-quicksilver-shaped-topology.md`; this ratification resolves that ADR's KNOWN ACCEPTED
VIOLATION by **keeping** the §0.1 target unchanged and making sub-second region failover a **GA
blocker** (see Decision).

## Context

§0.1 (`PROMPT.md:23, :27`) sets **99.999% control-plane write availability** (= 5.26 min/yr
error budget) and mandates that **changing a target requires an ADR with justification**.

`adr-0030-quicksilver-shaped-topology.md` adopts a centralized single-region root for writes.
With Amendment A2 (voters across ≥ 3 AZs in one region), the design meets 99.999% for
**single-AZ loss** — failover is automatic, fenced, and loss-free (Raft election across AZs).
It does **NOT** meet 99.999% under **FULL-region loss**: recovery is a **manual standby cutover**
(sub-second automatic region failover is deferred to the `adr-0024` v0.2 cross-DC bridge), and a
single multi-minute-RTO event breaches the annual budget. Edge **reads** (99.9999% target) are
unaffected — served from local copies independent of root liveness.

Because §0.1 requires an ADR to change a target, the full-region-loss write-availability gap is
recorded in ADR-0030 as a KNOWN ACCEPTED VIOLATION and formally renegotiated here.

## Decision

**Selected: option (a) — keep 99.999% write-availability as a single flat target; sub-second
automatic region failover is a GA BLOCKER.**

- The §0.1 target stands unchanged at **99.999%** (5.26 min/yr). It is NOT weakened to fit the
  current single-region design.
- **GA BLOCKER:** GA MUST NOT proceed while full-region loss requires manual standby cutover.
  Closing the gap requires **sub-second automatic region failover** via the `adr-0024` v0.2 cross-DC
  bridge (per-DC roots + async bridge with a defined merge consistency model). The design grows to
  meet the target.
- Until v0.2 ships: ADR-0030 Amendment A2 (voters across ≥ 3 AZs) already meets 99.999% for
  **single-AZ** loss; a single-region deployment MUST NOT be relied on for five-nines write
  availability through a **full-region** loss — that is the gap the GA blocker closes.

## Influenced by

- `adr-0030-quicksilver-shaped-topology.md` — the single-region root and Amendment A2 that
  produce the AZ-loss vs full-region-loss asymmetry.
- `adr-0024-cross-dc-bridge-deferred.md` — the deferred per-DC Raft + async bridge that option (a)
  would require, and that already rejects WAN-stretched Raft on the SLO.

## Reasoning

Keeping a single five-nines target preserves the integrity of the §0.1 contract: the system grows
to meet the promise rather than the promise shrinking to fit the system. The gap is bounded and
well-understood — AZ loss is already automatic/fenced/loss-free via ADR-0030 Amendment A2
(~150–300 ms Raft election); only **full-region** loss is unmet, and only on the **write** path
(edge reads unaffected). Making that gap a hard GA blocker — rather than a tiered downgrade (b) or a
silent acceptance (c) — keeps the §0.1 number honest and forces the `adr-0024` v0.2 cross-DC bridge
to land before GA. RTO by failure domain: single-AZ loss = automatic (sub-second election);
full-region loss = manual cutover (minutes) **today** — the GA-blocking condition; target post-v0.2
= sub-second automatic region failover.

## Rejected Alternatives

- **(b) Tiered SLO (99.999% AZ / lower tier + RTO full-region).** Rejected: publishing a lower
  full-region tier normalizes a downgrade of the §0.1 write-availability contract and complicates
  the SLO; the gap is closable by design (option a) rather than by lowering the bar.
- **(c) Explicit risk-acceptance of the violation.** Rejected: it would leave a §0.1 target
  silently unmet at GA — the precise failure mode ADR-0030's "do not bust a target silently"
  principle exists to prevent.

## Consequences

- **Positive:** the §0.1 99.999% write-availability target is preserved and honest; a clear,
  enforceable GA gate exists; ADR-0030's KNOWN ACCEPTED VIOLATION is resolved into a tracked GA
  blocker rather than an open silent gap.
- **Negative:** GA is **blocked** until the `adr-0024` v0.2 cross-DC bridge delivers sub-second
  automatic region failover — a real schedule dependency (Phase B / pre-GA).
- **Risks and mitigations:** until v0.2, do not deploy a single-region root for workloads requiring
  five-nines write availability through a full-region loss; ADR-0030 Amendment A2 covers single-AZ
  loss automatically meanwhile. Tracked as ledger risk **R-09 (GA blocker)**.

## Reviewers

- **Ratified by: human owner (operator), 2026-06-06** — selected option (a).
- prior-art-researcher / devils-advocate / topology-architect: N/A (human target-policy
  ratification, not a technical design review).
