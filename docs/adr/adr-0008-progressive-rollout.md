# ADR-0008: Health-Mediated Progressive Config Rollout

## Status
Accepted

## Context
Three Cloudflare outages in 4 months (November 2025, December 2025, February 2026) share the same root pattern: config changes propagated globally in seconds with no staged rollout, no canary, no health-mediated deployment. Cloudflare's "Code Orange" initiative explicitly acknowledges this architectural gap.

## Decision
Config changes traverse deterministic deployment stages with health-mediated gates, enforced at the **protocol level** (not application-layer policy):

```
CANARY (1 node) → 1% → 10% → 50% → 100%
```

### Mechanism
1. Writer submits config change with rollout policy (default: progressive; override: immediate for emergencies with audit trail).
2. Control plane commits the change to Raft log with `rollout_stage: CANARY`.
3. Distribution service selects canary node(s) per deterministic hash of change ID.
4. Canary node applies change. Health signals collected for `canary_bake_time` (default: 30s).
5. If health signals pass thresholds: control plane advances to next stage via new Raft log entry.
6. If health signals degrade: automatic rollback. Previous config version re-propagated.
7. At each stage, the set of target nodes expands according to consistent hashing of node IDs.

### Health Signals
- **Application-level:** Error rate, latency p99, panic/crash count — reported by edge nodes via metrics stream.
- **System-level:** CPU, memory, disk — collected via standard monitoring.
- **Config-specific:** Validation callbacks registered per key prefix. Edge node runs callback before applying config; failure = rejection + metric.

### Emergency Override
- `rollout_policy: IMMEDIATE` bypasses staging. Requires elevated ACL permission.
- All immediate rollouts logged to audit trail with operator identity.
- Post-incident review required for all immediate rollouts (enforced by policy, not code).

## Influenced by
- **Cloudflare Code Orange:** Post-incident initiative acknowledging need for staged config deployment.
- **AWS AppConfig:** Gradual deployment (20% every 2 minutes). Rollback on CloudWatch alarm.
- **LaunchDarkly:** Feature flag progressive rollout with health-gated advancement.

## Reasoning
Quicksilver's "within seconds" propagation is a liability, not a feature, when the config is wrong. The three Cloudflare outages demonstrate that speed without safety is a vector for global incidents. Progressive rollout converts a potential global outage into a canary-detected incident affecting < 1% of traffic.

## Consequences
- **Positive:** Bad config affects canary only (< 1% traffic). Automatic rollback on health degradation. Audit trail for all overrides.
- **Negative:** Propagation time increases from seconds to minutes for progressive rollout. Not suitable for emergency security patches (use IMMEDIATE override).
- **Risks and mitigations:** Canary selection bias (canary always the same node) mitigated by deterministic hash rotation per change ID. Health signal lag mitigated by configurable bake time per stage.

## Reviewers
- principal-distributed-systems-architect: ✅
- site-reliability-engineer: ✅
- chaos-engineer: ✅
