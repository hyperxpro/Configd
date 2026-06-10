# Handoff: Session 1 → Session 2 (Correctness Hardening)

> Session 2 scope per the pipeline charter: simulation, linearizability, property tests, jcstress.
> Read first: `docs/readiness-register.md` (the live register — every finding below is a row
> there), `ground-truth.md`, `claim-evidence-matrix.md`. The pre-pipeline
> `docs/READINESS-LEDGER.md` is historical context only.
> Session 1 was audit-only: **nothing was fixed**. Session 2 is the first session allowed to fix.

## 1. Ground rules inherited

- Keep `gates/gate-1.sh` green while you work; add `gate-2` for your own exit criteria.
- Evidence discipline unchanged: VERIFIED needs a re-runnable command; no P0/P1 closes without
  independent reproduction of the fix (discriminating test: passes with fix, fails without).
- The recurring failure mode of this codebase is the **verified-but-untested-integration seam**
  (correct component, unsafe/unverified wiring). Session 1 confirmed three more instances
  (CF-21, CF-44, OPS wiring). Budget adversarial review for every seam you touch.

## 2. P0s owned by Session 2 (fix + prove)

| Register row | What | The proof Session 2 must produce |
|---|---|---|
| RR ack≠commit (CF-44 / old R-14) | `RaftNode.propose` returns after local append (`RaftNode.java:283-289`); `HttpApiServer.java:277-278` turns that into HTTP 200 with a local `AtomicLong` id. Contract §6 promises ack-with-commit-sequence. | Commit-confirmed write path (block until applied / return commit seq — `whenReadReady`/`lastApplied` seams exist at `RaftNode.java:424,453-460`). Discriminating test: acked write survives immediate leader kill; linz reruns with 200⇒`:ok` (not `:info`) and stays LINEARIZABLE. |
| RR tick-thread stall (CF-21 / old R-15) | Timeout-less `new Socket(...)`:343 + `startHandshake()`:340 in `TcpRaftTransport` run on the single tick thread that owns tick/inbound/propose/reads post-R-01; `ConnectionManager.canSend` has zero callers. One black-holed peer ⇒ ~127s kernel SYN timeout per attempt, repeating — node-wide freeze. | Bounded connect/handshake timeouts or async connect off the tick thread, **without** breaking the R-01 single-thread invariant. Discriminating test: iptables DROP one peer; leader keeps committing to the rest (the A3-B session had to switch faults to REJECT precisely because DROP stalled everything — that observation is your regression test). |

Note: the other two P0s (edge plane absent; restart-after-compaction data loss) are owned by
Session 3 — do not drive-by them, but your simulation work must not paper over them either.

## 3. P1s owned by Session 2

1. **TF-1 — consensus-core mutation score 58%**, survivors in the safety kernel:
   `maybeAdvanceCommitIndex:1330` (§5.4.2 guard) survives mutation — its certification test asserts
   a by-construction tautology (`CertificationTest` ~:315-320); `handleRequestVote:938` vote-persist
   call removable (post-crash double-vote). Kill these mutants with real tests. Re-run PIT
   (wiring documented in `test-forensics.md` §5; PIT 1.25.4 + junit5-plugin 1.2.3 work on JDK 25)
   and get consensus-core ≥ 60% honestly.
2. **TF-8 — configd-server is the least-tested module** (61% line / 46% branch);
   `HttpApiServer$ConfigHandler` 0/60 branches incl. `checkAuth`; `RaftTransportAdapter` 0% — the
   real network seam is never traversed by tests.
3. **HF-2 — "deterministic" simulation isn't**: election RNG entropy-seeded
   (`ConsistencyPropertyTests.java:77` → `RaftNode.java:1650`). Thread the seed; then make the
   10k-seed sweep actually explore distinct schedules.
4. **SW-1 — sweep vacuity**: 3 silent-return paths in `commitSurvivesLeaderFailure`
   (SeedSweepTest:65-68,72-75,85-88) make 20,000 "tests" able to pass while testing nothing.
5. **HF-3 — jcstress absent**: lock-free `VersionedConfigStore`/`HamtMap` need a race harness
   (charter assigns jcstress to this session).
6. **CF-50 — all timing 10× documented**: `...Ms` config values consumed as 10ms tick counts
   (`RaftNode.java:1648-1650`); live re-election measured ~2.3s vs documented 150-300ms. Fix the
   units (or the docs) and add a test pinning real-time semantics.
7. **MX-1 — the contract's HLC is fiction**: `LogEntry.java:13` has no timestamp; staleness is
   defined off a field that doesn't exist. Reconcile contract §2/§4 + architecture §2 + ADR-0004
   with reality (implement or amend — an ADR either way).
8. **MX-2 — linearizability verification story**: contract §7 claims Wing&Gong checking; the named
   test is scripted single-threaded. The real checker is `configd-linz` (unmerged branch artifact,
   absent from CI, discrimination gates not re-verified this session). Merge it, wire
   self-tests into CI (gate-1 step b already runs them), re-run the discrimination gates, fix §7.
9. **Carried R-12 — reconfiguration unverified end-to-end and structurally untestable**:
   `proposeConfigChange` (`RaftNode.java:514`) has zero non-test callers; the one
   election-survival test asserts the opposite of its name (vacuous). Joint-consensus mutation
   coverage 46%. Needs a live admin seam (built with a tripwire — see R-07's W-1 owner-thread
   assertion) before it can be fault-tested.
10. **Carried R-10 — GLOBAL-key fail-closed strong read**: no key-class, no enforcement, no
    contract entry (ADR-0030 INV-1).

Also yours (P2, listed in the register): TLC liveness never checked (commented out in all cfgs);
7 spec invariants lack runtime twins (CM-002); @Buggify 0 call sites vs ADR-0007's "~1000";
SpotBugs' 19 MT_CORRECTNESS warnings on RaftNode need disposition; A2's seeded-bug traces are
gitignored (non-reproducible); contract §8 names removed assertions and a wrong exception type;
vacuous named regression tests (Figure-8 tautology, FIND-0005 injects no exception).

## 4. Claim–evidence matrix rows Session 2 must convert

From `claim-evidence-matrix.md` (statuses as of Session 1 close):
- **EXISTS-UNVERIFIED → VERIFIED:** CM-137 (linz discrimination gates), CM-165 (R-04 "six gates
  green" — gates ii/iv + multi-seed/5-node), linz-related CM-032/035 beyond single-seed smoke.
- **CONTRADICTED → resolved (fix or amend doc):** CM-009/CM-046 (ack model), CM-010/037/044/064
  (HLC), CM-048/049 (contract §7 test descriptions), CM-052/058 (§8 stale), CM-189 (same-seed
  determinism), CM-191 (election timing).
- **FICTION → implemented or formally descoped:** CM-002 runtime-twin gaps; Apalache claim
  (CM-003) — decide and write it down.

## 5. Environment & cost notes

- Box: 2 vCPU / 7.7GB. Full reactor `clean verify` ≈ 5.5 min. TLC full runs: 14m00s / 7m45s /
  2m37s (Consensus/ReadIndex/Snapshot) at `-workers 2`. PIT: ~25 min/core-module capped (see
  test-forensics.md §5 for exact wiring; run per-module).
- Porcupine checker: builds from repo Go sources in ~3s; `gate-1.sh` builds it automatically when
  `PORCUPINE_BIN` is unset and Go exists.
- linz fault injection needs passwordless sudo (iptables REJECT + kill -9) — present here. Use
  REJECT, not DROP, until CF-21 is fixed (DROP stalls the leader — that's the bug).
- Scratch workspaces from Session 1 (reusable): `/home/ubuntu/ws-clean` (built clone + PIT/JaCoCo
  wiring), `/home/ubuntu/ws-smoke` (cluster scratch), `/home/ubuntu/audit-artifacts/` (raw run
  logs: jmh, tlc, linz-run, sweep runs).
- Known CI fragility: two keytool TLS tests flake under the JaCoCo agent (timeout-related;
  mitigation recorded in test-forensics.md). Untracked `configd-linz/runs/` (59 dirs, 8.1MB) are
  A3-B leftovers — decide tracked-artifact policy when merging linz.

## 6. Exit expectations (Session 2 defines its own gate, but minimally)

`gate-1.sh` stays green; `gate-2` adds: linz multi-seed run in CI-or-nightly, jcstress smoke,
PIT threshold ≥60% on consensus-core, the two P0 discriminating tests, and re-run of the full
TLC trio (bound adequacy reviewed). Every closed register row gets its Resolution-evidence cell
filled with the command + output that proves it.
