# Certification Artifacts Index

> Terminal certification for Configd — 2026-04-11

## Documents

| Artifact | Description |
|---|---|
| [verdict.md](verdict.md) | Final certification verdict and gate status |
| [findings.md](findings.md) | All findings (CERT-0001 through CERT-0014) with status |
| [spec-code-map.md](spec-code-map.md) | TLA+ action/invariant → Java file:line mapping |

## Key Results

- **3 blocker bugs found and fixed** (follower config recomputation, CheckQuorum joint consensus, WAL crash safety)
- **7 major bugs found and fixed** (ReadIndex joint consensus, non-voter guards, configChangePending, config recovery, snapshot config, noop detection, simulation determinism)
- **4 missing critical tests identified** (Figure 8, joint consensus + leader failure, ReadIndex step-down, config truncation/revert)
- **20,353 tests pass** after all fixes (0 failures, 0 errors)
- **Verdict: NOT CERTIFIED** — code bugs fixed, but infrastructure-dependent gate items remain
