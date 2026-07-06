# Wire-hardening arc — design trail

This directory is the working record of the wire-hardening arc: the sweep that took both of
Configd's wire planes (the intra-cluster **Raft** plane and the driver-facing **edge/fan-out**
plane) from "works between trusted peers" to "safe against a hostile sender on either end."

**The normative wire specification is not here.** It lives in the driver-protocol RFC —
[`../../rfc/driver-protocol/`](../../rfc/driver-protocol/), principally
[`06-wire-framing.md`](../../rfc/driver-protocol/06-wire-framing.md) (edge plane §1–§12, Raft
plane §13) and [`07-errors.md`](../../rfc/driver-protocol/07-errors.md). Gate 5 made the RFC
match the hardened code byte-for-byte, so a driver author builds a conformant, hostile-server-safe
client from the RFC alone. The documents here are the **reasoning behind** that spec: the threat
model, the per-frame attack analysis, the findings, the fixes, and the test coverage that backs
them. Keep them as reference; consult the RFC for what the bytes are today.

## The gates

### Gate 1 — catalog, threat model, attack surface
- [`gate-1/catalog-raft-plane.md`](gate-1/catalog-raft-plane.md) — Raft-plane frames, field-by-field byte layout.
- [`gate-1/catalog-edge-plane.md`](gate-1/catalog-edge-plane.md) — edge / fan-out plane frames, field-by-field byte layout.
- [`gate-1/threat-model.md`](gate-1/threat-model.md) — the hostile-sender threat model (what an attacker on each end can send).
- [`gate-1/attack-matrix.md`](gate-1/attack-matrix.md) — every frame × attack class, systematically.
- [`gate-1/prior-art.md`](gate-1/prior-art.md) — how mature binary protocols defend the same surface, and where Configd sat.
- [`gate-1/findings-register.md`](gate-1/findings-register.md) — the consolidated findings (Gate 1 output → Gate 2 backlog); the WH-NN identifiers used throughout the arc.
- [`gate-1/lead-notes.md`](gate-1/lead-notes.md) — independent grounding read by the arc lead.

### Gate 2 — fix every gap, consistently
- [`gate-2/workstream-A-identity-binding.md`](gate-2/workstream-A-identity-binding.md) — Raft peer-identity binding (WH-08/09).
- [`gate-2/workstream-C-edge-antiexhaustion.md`](gate-2/workstream-C-edge-antiexhaustion.md) — edge anti-exhaustion (WH-11/12/13/15).
- [`gate-2/workstream-D-codec-strictness.md`](gate-2/workstream-D-codec-strictness.md) — codec strictness batch (WH-05/06/07/10/14).

### Gate 3 — property / fuzz testing every codec
- [`gate-3/fuzz-coverage.md`](gate-3/fuzz-coverage.md) — the structured fuzz coverage, per codec, CI-integrated.

### Gate 4 — integration + multi-node E2E
- [`gate-4/e2e-coverage.md`](gate-4/e2e-coverage.md) — the integration and multi-node end-to-end coverage, including the hostile-peer-in-a-live-cluster scenario.

### Gate 5 — RFC ⇄ code validation
- [`gate-5/rfc-validation.md`](gate-5/rfc-validation.md) — the drift found between the RFC and the hardened code, and the corrections that made the RFC normative and byte-exact.

### Gate 6 — documentation cleanup
- [`gate-6/cleanup-plan.md`](gate-6/cleanup-plan.md) — the audit that established the RFC as *the* wire spec and reconciled the surrounding wire docs against it.
