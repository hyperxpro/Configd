# ADR-0031: Renegotiate §0.1 Write-Availability Target Under Full-Region Loss

## Status

**Proposed (stub).** Decision TBD — this ADR enumerates options without deciding.
Raised by `adr-0030-quicksilver-shaped-topology.md` (KNOWN ACCEPTED VIOLATION; human
ratification pending).

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

**TBD.** Options enumerated, none selected:

- **(a) Keep 99.999% as a single flat target.** Commit to **sub-second automatic region failover**
  via the `adr-0024` v0.2 cross-DC bridge (per-DC roots + async bridge with a defined merge
  consistency model) **before GA**. The target stands; the design must grow to meet it.
- **(b) Renegotiate to a tiered SLO.** State **99.999% under AZ loss** and a **lower, explicitly
  stated tier with a published RTO under full-region loss** (e.g. a separate availability number +
  a manual-cutover RTO). Splits the single number into failure-domain-scoped tiers.
- **(c) Explicit risk-acceptance of the violation.** Document and accept that full-region-loss
  write availability is below 99.999% for the relevant release, with owner and review date, and no
  design change.

A decision among (a)/(b)/(c) requires human ratification and is out of scope for this stub.

## Influenced by

- `adr-0030-quicksilver-shaped-topology.md` — the single-region root and Amendment A2 that
  produce the AZ-loss vs full-region-loss asymmetry.
- `adr-0024-cross-dc-bridge-deferred.md` — the deferred per-DC Raft + async bridge that option (a)
  would require, and that already rejects WAN-stretched Raft on the SLO.

## Reasoning

TBD — to be filled when an option is selected. The selection must quantify the chosen target(s),
the RTO under each failure domain, and (for option a) the GA gating condition tied to the
`adr-0024` v0.2 bridge.

## Rejected Alternatives

TBD — none rejected yet; this stub enumerates the live options above.

## Consequences

- Positive: TBD (depends on selected option).
- Negative: TBD.
- Risks and mitigations: until this ADR is decided and ratified, ADR-0030's full-region-loss
  write availability remains a KNOWN ACCEPTED VIOLATION of §0.1, gated for human review before
  merge.

## Reviewers

- prior-art-researcher: PENDING
- devils-advocate: PENDING
- topology-architect: PENDING
