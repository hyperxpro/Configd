# Production-readiness register audit -- decision log

> Read-heavy audit, docs-only. No production code was touched.

| ID | Decision | Basis | Reversible? |
|---|---|---|---|
| **DL-PRR-01** | Reconstructed the register structure (11 sections x 12 = 132 items) and authored it at `docs/archive/readiness/production-readiness-register.md` -- the operator's drafted content was not present and the path did not exist. | If absent, reconstruct the section/item structure and audit from the codebase. | Yes |
| **DL-PRR-02** | Applied the hard evidence rule strictly: nothing marked done without a named artifact, verified against the live code at HEAD, not docs/memory. This downgraded 3 false done-marks (4.8 watches, 10.12 Buggify, 5.11 BloomFilter -- all "class exists implies assumed done" traps). | False done-marks are the dangerous ones. | n/a |
| **DL-PRR-03** | Gathered evidence via 7 parallel adversarial sub-audits (one per section-group) plus operator spot-verification of the 4 highest-stakes claims (ADR-0030/0032 Proposed; encryption-at-rest absent; `owner==0` per-shard gate; watches' missing client surface). | Scale (132 items) plus the rule to audit special-attention items hardest. | n/a |
| **DL-PRR-04** | Opened the PR from a fresh docs branch off the current `origin/main` (`d93d2b1`), not the branch the EC2-prep handoff was on, so the PR diff is purely `docs/readiness/`. | Keep the docs audit independent of the already-merged EC2-prep handoff. | Yes |
| **DL-PRR-05** | The blocker shortlist is a recommendation, not a decision; the §11-B decision backlog lists the open calls. The auditor does not draw the ship line. | A recommendation for the readiness review, not a decision. | n/a |
| **DL-PRR-06** | Flagged but did not fix two stale docs found mid-audit: `known-limitations.md` (mutation/jacoco/alloc "not measured" now obsolete) and `consistency-contract.md` §2 line 74 plus the staleness-invariant row (describe the deleted staleness proxy). | This session is the register only (docs-only, no scope to rewrite other docs); recommended a follow-up doc-reconciliation pass. | Yes |
| **DL-PRR-07** | Did not execute the full ~21k-test reactor. A done mark rests on artifact-exists plus CI-wired (the gate chain has prior green-CI evidence); done items whose pass could not be independently confirmed are marked with a dagger. | Impractical per-item; the gate chain is the standing pass-evidence. No production code changed. | n/a |

## Status counts recorded (132 items)

done 86, partial 29, absent 7, non-goal 6, unmeasured 4. Pre-fill corrections: 3 optimistic-to-downgraded
(false done-marks), ~9 pessimistic-to-upgraded (memory under-credited shipped work). See the register §0.

## Explicitly handed to the operator / readiness review (not self-decided)

The §11-B open decision backlog (D-1 through D-8): ratify ADR-0030/0032; encryption-at-rest decision;
security-on-by-default; empirical-validation/burn-in posture; wire-epoch reservation; region-failover GA
blocker (ADR-0031); snapshot 4 MiB cap; dead-code (wire or delete).

*Audited at HEAD `74ab070` against `origin/main` `d93d2b1`, 2026-06-27.*
