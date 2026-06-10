# Session 1 Final Verdict — State of the System

> Audit lead, Production-Readiness Pipeline Session 1 (Ground Truth & Build Integrity).
> Audit target: branch `session-1-ground-truth` @ `423a654`, 2026-06-10. Method: 5-agent team,
> every P0/P1 independently reproduced by a second agent; every status backed by a re-runnable
> command or file:line collected this session. Full evidence: `docs/audit-session-1/*`,
> `docs/readiness-register.md` (94 findings, all owned).

## Verdict

**Configd is one real system wearing the documentation of another.** What exists is a competent,
genuinely tested **single-group Raft control plane**: it builds clean from checkout, elects
leaders, replicates, survives leader kill -9 without losing committed data, fsyncs before
acking peers, serves lock-free reads, speaks mTLS, and its TLC specs check out digit-for-digit
(safety only). What does not exist is the product the docs sell: a **globally distributed edge
data plane**. Committed writes die in an undrained buffer; no fan-out listener, no edge process,
no wire path — the headline guarantee of the consistency contract is unfalsifiable because its
subject is absent. Between those poles sits the deepest problem this audit found: **the proof
layer is weaker than the code.** 93.4% of the "21,408 tests" is one seed sweep with vacuous-pass
paths; mutation testing shows the Raft safety kernel's named certification test asserts a
tautology while the §5.4.2 commit guard, vote persistence, and every consensus fsync can be
deleted without a single test failing; the client API and auth gate have 0/60 branches covered;
all nine SLO metrics read zero on a live cluster and 6/9 alerts can never fire. Of 192 falsifiable
documentation claims, **85 verified, 66 contradicted, 19 are fiction**. The system is not close
to production; more importantly, until Sessions 2–3 rebuild the proof layer, nobody can honestly
say *how far* it is.

**The five most dangerous things in this codebase right now are: (1) the edge data plane does not
exist at runtime — every propagation, staleness, and read-your-writes promise is fiction
(RR-001); (2) clients receive HTTP 200 before quorum commit, so a leader failover can silently
discard acknowledged writes, exactly contradicting the contract's ack model (RR-004); (3) one
black-holed peer freezes an entire node — tick, reads, writes, elections — for ~127 s per connect
attempt, because a timeout-less blocking connect runs on the single thread the A1 fix routed
everything through (RR-002); (4) the Raft safety kernel's test protection is partly illusory —
commit-guard, vote-durability, and fsync mutations all survive a green suite, and a latent
restart-after-compaction path silently loses data (RR-085/RR-086/RR-003); (5) production would be
flying blind on a green dashboard — every SLO counter is hardwired to zero, two-thirds of the
alert rules query series that are never emitted, and the propagation monitor is structurally
incapable of firing (RR-046-area, S6 rows).**

## Numbers

| | |
|---|---|
| Clean-checkout build | PASS (5m25s, zero modifications needed) |
| Test suite | 21,408 green / **1,408 real** (93.4% = one sweep) |
| Mutation scores (PIT) | consensus-core **58%**, distribution-service **55%** (< 60% line), config-store 71%, edge-cache 79%, replication-engine 84% |
| Claim–evidence matrix | 192 claims: 85 VERIFIED · 66 CONTRADICTED · 19 FICTION · 21 EXISTS-UNVERIFIED · 1 ENV-BLOCKED |
| Readiness register | **94 findings: 4 P0 · 20 P1 · 45 P2 · 25 P3** — all OPEN, all owned by Sessions 2–7 |
| Multi-node smoke | control plane 6/6 PASS · edge step FAIL (the P0) |
| Harnesses | JMH ✓ TLC ✓(safety only) DST ✓(determinism partial) linz ✓ · jcstress ✗ Jepsen ✗ Apalache ✗ |
| gate-1 | exists, in CI, **honestly FAILING** on the throttled audit box (RR-094 timeout flake; steps b–e green; build step green on idle hardware) |

## Honesty notes

- The two pre-pipeline self-audits (STATE-OF-REALITY, READINESS-LEDGER) were directionally right
  and materially helpful — but this session found errors in them (R-13's wrong threshold), claims
  they never re-verified (phantom supply-chain CI), and new P0-class defects they missed entirely
  (restart-after-compaction data loss; tick-thread connect freeze severity; safety-kernel
  mutation blindness). Trust level for inherited "CLOSED" stamps: verify, always.
- This session fixed nothing and hid nothing: zero modifications to tracked code; the gate ships
  red rather than tuned green; all severity disputes are recorded in the register, not averaged.
- Session 2 starts at `handoff-to-session-2.md`. The pipeline's spine is now machine-verifiable:
  if `gates/gate-1.sh` and the register disagree with a future claim, the gate and the register win.
