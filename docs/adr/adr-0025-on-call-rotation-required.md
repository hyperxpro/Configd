# ADR-0025: On-call Rotation Is an Operator Responsibility

## Status
Accepted (2026-04-17). Codifies R-12.

## Context

A "production-ready" distributed system depends on a 24/7 on-call
rotation that owns paging, runbooks, and post-incident review. Configd
ships the runbooks and the alert-rule generator (Phase 8) but the
rotation itself — humans, paging service, escalation tree — is
operator-procured.

The GA review (Phase 11) needs to be unambiguous on this: an absent
rotation is not a code bug we can close, but a deployment-time
prerequisite the operator must own.

## Decision

The on-call rotation is the deploying operator's responsibility. The
Configd project ships:

1. The runbooks (`runbooks/*.md`).
2. The alert-rule generator (`ops/alerts/`).
3. The Prometheus / Grafana templates (`ops/dashboards/`).
4. The runbook-conformance template that maps each alert to the
   runbook section that resolves it.

The Configd project does not ship the paging service integration, the
escalation tree, or any roster.

## Rationale

1. **Paging service choice is operator-specific** (PagerDuty,
   OpsGenie, internal ChatOps). Hard-wiring one would force the
   operator to use that tool.
2. **Escalation trees are organisational.** Configd cannot model who
   reports to whom, what the escalation latencies are, or the legal
   coverage requirements per region.
3. **The GA gate stays honest.** Marking on-call "green" because we
   shipped runbooks would mislead the operator into thinking they have
   a rotation when they don't.

## Consequences

- The GA review records on-call as **YELLOW: operator-procured —
  rotation MUST be in place before production launch**.
- Each runbook ends with an "Operator setup required" section
  enumerating the paging integration the runbook assumes.
- The ops/alerts/ generator emits alert manifests that include a
  `runbook_url` annotation but no notification channel.

## Related

- R-12 (gap-closure §5)
- `runbooks/` (operator runbooks)
- `ops/alerts/` (alert generator — Phase 8)

## Verification

- **Testable via:** the alert generator emits `runbook_url` annotations without notification channels — verified by the test corpus over `ops/alerts/configd-slo-alerts.yaml` (parsed by `configd-observability/src/test/java/io/configd/observability/ProductionSloDefinitionsTest.java` and the burn-rate evaluator under `BurnRateAlertEvaluatorTest.java`). Each runbook ending with an "Operator-Setup" section is the structural assertion of this ADR — see the runbook conformance template `ops/runbooks/runbook-conformance-template.md`.
- **Invalidated by:** an alert manifest that hard-wires a paging-service receiver, or by the GA review marking on-call GREEN without a procured rotation.
- **Operator check:** confirm the operator's deployment of `ops/alerts/configd-slo-alerts.yaml` is wired to the operator's paging service (e.g. PagerDuty/OpsGenie) — Configd ships no default. `docs/ga-review.md` records on-call as YELLOW until the operator confirms.
