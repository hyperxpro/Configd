# Runbook Conformance Template

**Purpose:** every runbook in `ops/runbooks/` claims to make the
cluster recover. This template defines what "recovered" means, so
those claims can be tested.

A drill is a periodic, scheduled exercise of a runbook against a real
or simulated cluster. The expected cadence per gap-closure §4 is:

| Drill | Cadence | Owner |
|-------|---------|-------|
| Restore from snapshot | quarterly | operator on-call |
| Leader-loss recovery (`control-plane-down.md`) | monthly | operator on-call |
| Snapshot-install failure (`snapshot-install.md`) | quarterly | operator on-call |
| Disaster-declaration tabletop | semi-annually | operator + IC pool |

GA records this cadence as **YELLOW** until the operator has run
each drill at least once with a measured-elapsed result file. Do not
mark green without a real measured run.

## Conformance contract for a drill

A drill is "passed" if and only if:

1. **The runbook's stated outcome is achievable from the documented
   steps alone.** No undocumented commands, no implicit knowledge.
2. **Recovery time is within the operator's SLA.** Record the actual
   wall time from incident declaration to write-resumption.
3. **No invariant is violated during recovery.** Run
   `InvariantMonitor.checkAll()` (or scrape the
   `configd_invariant_violation_*` counters) and assert that
   `InvariantMonitor.violations()` returns an empty map — i.e. every
   registered invariant has a violation count of `0`. The
   `InvariantMonitor` API actually shipped is `checkAll()` for the
   batch run plus `violations()` for the per-invariant counter map; the
   earlier `assertAll()` name was never landed.
4. **Conformance check on the restored data** passes for restore
   drills:
   - For every key documented as "must survive snapshot",
     `getValue(key) == snapshotValue(key)` byte-for-byte.
   - For every key documented as "in flight at snapshot time", the
     value is either `snapshotValue(key)` or a strictly later commit;
     never an older value.

## Drill result file format

Drills write a result file under
`ops/dr-drills/results/<drill>-<timestamp>/result.txt`:

```
drill_name=<runbook-stem>
runbook=ops/runbooks/<file>.md
operator=<email or rotation handle>
start_utc=<ISO 8601>
end_utc=<ISO 8601>
measured_recovery_sec=<integer>
sla_recovery_sec=<integer>
within_sla=<true|false>
invariant_check=<pass|fail>
data_conformance=<pass|fail|n/a>
notes=<free text — runbook gaps, surprising behaviour, follow-ups>
```

## Tabletop drills

For runbooks where physical execution is impractical (signing-key
compromise, multi-region partition), the operator may run a tabletop:

1. Convene the on-call rotation.
2. Read the runbook aloud, step by step.
3. At each step, ask: "Could we execute this *right now* with the
   tooling and access we have?" Record any "no" as a follow-up.
4. The drill passes if every step is executable; fails otherwise.

Tabletop result files use `data_conformance=n/a` and document the
follow-ups in `notes`.

## What "GREEN" requires

Per gap-closure §4, the GA review can record a runbook as GREEN only
when:

- A real (non-tabletop) drill has been executed against the runbook.
- The drill result file shows `within_sla=true`,
  `invariant_check=pass`, and (where applicable)
  `data_conformance=pass`.
- The drill cadence above is being met.

GA review records anything less as YELLOW with the actual measured
state, never rounded up.

## Related

- All runbooks under `ops/runbooks/`
- `docs/decisions/adr-0025-on-call-rotation-required.md`
- `docs/progress.md` — Phase 10 records what has actually been drilled
