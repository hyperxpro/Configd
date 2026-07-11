# Wire-hardening: design trail

This directory is the reasoning behind the sweep that took both of Configd's wire planes (the
intra-cluster **Raft** plane and the driver-facing **edge/fan-out** plane) from "works between
trusted peers" to "safe against a hostile sender on either end."

**The normative wire specification is not here.** It lives in the driver-protocol RFC -
[`../../rfc/driver-protocol/`](../../rfc/driver-protocol/), principally
[`06-wire-framing.md`](../../rfc/driver-protocol/06-wire-framing.md) (edge plane §1-§12, Raft
plane §13) and [`07-errors.md`](../../rfc/driver-protocol/07-errors.md). The RFC matches the
hardened code byte-for-byte, so a driver author builds a conformant, hostile-server-safe client
from the RFC alone. What's here is the reasoning behind that spec: the threat model, the
per-frame attack analysis, and the prior-art comparison that backs it.

- [`gate-1/threat-model.md`](gate-1/threat-model.md) - the hostile-sender threat model (what an attacker on each end can send).
- [`gate-1/attack-matrix.md`](gate-1/attack-matrix.md) - every frame x attack class, systematically.
- [`gate-1/prior-art.md`](gate-1/prior-art.md) - how mature binary protocols defend the same surface, and where Configd sat.

Keep these as reference; consult the RFC for what the bytes are today.
