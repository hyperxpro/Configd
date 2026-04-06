# DR Drills

Result files from disaster-recovery drills. Each drill writes one
file under `results/<drill>-<UTC timestamp>/result.txt` following the
contract in
[`../runbooks/runbook-conformance-template.md`](../runbooks/runbook-conformance-template.md).

## Cadence (operator-procured)

| Drill | Cadence |
|-------|---------|
| `restore-from-snapshot` | quarterly |
| `control-plane-down` (leader-loss recovery) | monthly |
| `snapshot-install` | quarterly |
| `disaster-declaration` (tabletop) | semi-annually |

## Marking GA review

GA review (`docs/ga-review.md`) reads the most recent result for each
drill. A runbook is GREEN only when:

- A non-tabletop result file exists.
- `within_sla=true`, `invariant_check=pass`, `data_conformance=pass`.
- The drill cadence is being met (last result is within the cadence
  window above).

Otherwise the GA review records the drill as YELLOW with the actual
measured state. Do not mark green based on intent; only on a measured
result.

## Empty for now

This directory is currently empty because no drills have been
executed against this branch. When you run a drill, follow the
template in `../runbooks/runbook-conformance-template.md` for the
result file format and commit the result file under `results/`.
