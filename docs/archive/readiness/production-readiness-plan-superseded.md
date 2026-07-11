# Production-readiness plan (superseded, homed 2026-07-09)

The original root `PRODUCTION-READINESS-PLAN.md` was the first plan-of-record for taking Configd from a
verified-but-unwired core to a production system. Every phase in it started as not-started; the work it
planned has since been done and merged (the multi-Raft, edge data-plane, security, frozen-format,
wire-hardening, auth, and client work). The plan referenced three working docs that no longer exist
(`docs/STATE-OF-REALITY.md`, `docs/READINESS-LEDGER.md`, `docs/SESSION-PLAYBOOK.md`), so it was deleted;
its one durable idea is preserved here.

**The durable sequencing principle (worth keeping):** "a verified-correct component wired in unsafely is
more dangerous than an absent one, because it looks done." So the plan made the existing core trustworthy
before building the missing data plane -- Phase A hardened and proved what already ran (the Raft
integration race, the invariant net, linearizability, store hardening), Phase B built and proved the edge
data plane on top of a trustworthy core, and Phase C measured, drilled, and rolled out. That
harden-before-build ordering is the lesson; the calendar mechanics are not.

**Where the live status now lives:**
- `docs/archive/readiness/v1-go-no-go-2026-07-01.md` -- the v1 go/no-go review.
- `docs/archive/readiness/production-readiness-register.md` -- the audited per-subsystem readiness register.
- `docs/operations/known-limitations.md` / `burn-in-contract.md` -- the honest edges, the limitations
  that remain, and the first-30-days posture.
