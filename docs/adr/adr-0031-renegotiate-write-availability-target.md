# ADR-0031: Write-Availability Target Stays at 99.999%; Full-Region Failover Remains a Known Limitation

## Status

Accepted (2026-06-06).

## Context

The control-plane write-availability target is 99.999% (5.26 min/yr error budget). Changing a
target like this warrants its own ADR - this is that ADR.

`adr-0030-quicksilver-shaped-topology.md` adopts a centralized, single-region root for writes.
With its voters spread across at least three availability zones in one region (Amendment A2),
the design meets 99.999% for single-AZ loss: failover is automatic, fenced, and loss-free (a Raft
election across AZs, ~150-300 ms). It does not meet 99.999% under full-region loss: recovery is a
manual standby cutover, and a single multi-minute-RTO event breaches the annual budget on its own.
Edge reads (a separate 99.9999% target) are unaffected - they are served from local copies
independent of root liveness.

This gap is real and it stays real: closing it would mean per-region roots bridged by an
asynchronous replication tier with its own consistency model (a cross-DC bridge, sketched and
deferred in `adr-0024-cross-dc-bridge-deferred.md`), and that bridge is not built. There is no
plan or target date to build it. `docs/operations/known-limitations.md` states the practical
consequence: Configd is measured and deployed single-region, and "is not designed for cross-region
or WAN operation." This ADR is the formal record of that trade-off for the write-availability
target specifically.

## Decision

Keep 99.999% as a single, flat write-availability target. Full-region loss is a known, accepted
architectural limitation of the single-region-root topology, not a gate blocking anything.

- The target stands unchanged at 99.999% (5.26 min/yr). It is not weakened to fit what the
  single-region design can currently deliver.
- Single-AZ loss already meets the target automatically, today, via `adr-0030`'s multi-AZ voter
  placement (Amendment A2) - no human involved, no data loss.
- Full-region loss does not meet the target: recovery is a manual standby cutover, on the order of
  minutes. Closing this would require the cross-DC bridge described in `adr-0024` (per-region
  roots plus an async bridge with a defined merge-consistency model), which does not exist and is
  not being built. Do not deploy a single-region root for a workload that needs five-nines write
  availability through a full-region loss.

## Influenced by

- `adr-0030-quicksilver-shaped-topology.md` - the single-region root and Amendment A2 that produce
  the AZ-loss vs. full-region-loss asymmetry.
- `adr-0024-cross-dc-bridge-deferred.md` - the per-region-roots-plus-async-bridge design that
  would close this gap, and that already rejects WAN-stretched Raft on the same latency budget.

## Reasoning

Keeping one five-nines number is more honest than publishing a target the system already meets:
it states plainly what is and is not covered, rather than quietly lowering the bar to match
current capability. The gap is bounded and well understood - AZ loss is already automatic, fenced,
and loss-free (`adr-0030` Amendment A2, ~150-300 ms Raft election); only full-region loss is
unmet, and only on the write path (edge reads are unaffected). Recovery time by failure domain:
single-AZ loss is automatic (a sub-second election); full-region loss is a manual cutover
(minutes), and stays that way unless a cross-DC bridge is built - which today it is not, and
nothing in this codebase's roadmap says otherwise.

## Rejected Alternatives

- **A tiered SLO (99.999% for AZ loss, a lower tier plus an RTO figure for full-region loss).**
  Rejected: publishing a lower full-region tier normalizes a permanent downgrade of the
  write-availability contract and complicates the number a reader has to reason about, for a gap
  that is already fully described elsewhere (this ADR, `known-limitations.md`). One flat number
  stays simpler and just as honest, provided the exception is documented plainly - which this ADR
  does.
- **Silently accepting the gap without recording it.** Rejected: that would leave the target
  looking unconditionally met when it is not. Recording the limitation explicitly, rather than
  letting the number stand unqualified, is the whole point of writing this ADR.

## Consequences

- **Positive:** the 99.999% write-availability target is preserved and stated honestly; the
  full-region-loss gap is documented as a known limitation rather than left as a silent
  discrepancy between the target and reality.
- **Negative:** a full-region loss genuinely breaches the five-nines write-availability target,
  and there is no currently-built or currently-planned path to close that gap - it would require
  the cross-DC bridge described in `adr-0024`.
- **Risks and mitigations:** do not deploy a single-region root for a workload that requires
  five-nines write availability through a full-region loss. `adr-0030` Amendment A2 already covers
  single-AZ loss automatically, which is the more common event. See
  `docs/operations/known-limitations.md` for the operator-facing statement of this limitation.
