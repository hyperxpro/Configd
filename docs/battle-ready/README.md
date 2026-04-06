# Battle-Ready Artifacts

This directory contains all artifacts from the final hardening sprint that drove Configd from PRR-audited to battle-ready.

## Documents

| Document | Owner | Description |
|----------|-------|-------------|
| [verdict.md](verdict.md) | launch-commander | Final launch decision: GO-WITH-CONDITIONS |
| [finding-closure-report.md](finding-closure-report.md) | findings-remediation-lead | All 37 findings (25 PRR + 12 new) tracked to closure |
| [adversarial-report.md](adversarial-report.md) | chaos-commander | Adversarial scenarios beyond PRR chaos matrix |
| [performance-final.md](performance-final.md) | performance-closer | JMH benchmarks, hot-path audit, regression gates |
| [security-final.md](security-final.md) | security-closer | External review findings, fixes, residual risks |
| [operational-final.md](operational-final.md) | sre-launch-lead | Runbook coverage, SLO framework, alert evaluation |
| [release-rehearsal.md](release-rehearsal.md) | release-commander | CI pipeline, canary plan, kill switches |

## Key Metrics

- **37 findings** closed (25 PRR + 12 hardening), 0 open, 0 waivers
- **1 critical correctness bug** found and fixed (joint consensus election quorum)
- **4 security blockers** found and fixed (mTLS, signing, rate limiter, auth wiring)
- **2,975 tests** green across all modules
- **43+ new regression tests** added during hardening
- **10,000-seed** sweep in CI, configurable to 100,000

## How to Audit

1. Start with [verdict.md](verdict.md) -- read the gate checklist
2. For any unchecked box, follow the evidence link
3. Cross-reference [finding-closure-report.md](finding-closure-report.md) for per-finding detail
4. Run `./mvnw clean verify` to reproduce the green build
