# ⚠️ Historical snapshots — NOT current evidence

The files under `docs/audit/` (`master-findings.md`, `test-completeness.md`,
`structural-integrity.md`, etc.) are **point-in-time audit snapshots** retained as historical
record. They are **not** the authoritative current state and **must not be cited as current
evidence**. They are intentionally **not rewritten** — rewriting dated records would itself be
dishonest.

For the authoritative current state, use:

- **Forensic assessment:** `docs/STATE-OF-REALITY.md`
- **Live readiness ledger:** `docs/READINESS-LEDGER.md`
- **Live test-suite size:** **21,394** (`./mvnw -fae test`; evidence
  `verification-runs/state-of-reality/live-mvn-test.log`). Any other suite-size number here
  (e.g. 20,132 / 20,149 / 21,222 / 21,246 / 21,285 / 21,402) is historical.
- **Perf "SURPASSES Quicksilver" claims** here are **MODELED, NOT MEASURED** and withdrawn pending
  Phase C1 measurement (see `docs/gap-analysis.md §6`, `docs/performance.md §11`).
- **Topology:** any multi-region / hierarchical-Raft *write* design referenced here is **superseded
  by `docs/decisions/adr-0030-quicksilver-shaped-topology.md`**.
