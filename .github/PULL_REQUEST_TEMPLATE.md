<!-- Read CONTRIBUTING.md before your first PR. The bar for correctness and operator-honesty is high. -->

## What and why

<!-- What does this change do, and what problem does it solve? Link the issue if there is one. -->

## Checklist

- [ ] `./mvnw -T 1C verify` passes locally on every commit in this PR (not just the last one).
- [ ] If a public API, wire, or on-disk contract changed: an ADR is included in this PR and a
      supported-version note is added.
- [ ] If an operator-visible behavior changed: the runbook under `ops/runbooks/` is updated and still
      satisfies the conformance template.
- [ ] If a latency/throughput/availability target number changed: an independent sign-off is recorded
      in this description.
- [ ] No metric is referenced from prose without being registered in `ConfigdMetrics`.
- [ ] Any performance number added to docs is backed by a measurement artifact attached to the PR or
      pinned in a referenced commit, or carries an explicit "modeled, not measured" note.
