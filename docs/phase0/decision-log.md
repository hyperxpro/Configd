# Phase 0 — Decision Log

Per session §2 (autonomy directive) and §8.8: every self-resolved technical decision and every
conservative-default scope decision is recorded here for retroactive veto. Newest first.

| ID | Date | Type | Decision | Rationale | Reversible? |
|---|---|---|---|---|---|
| D-007 | 2026-06-21 | correctness (scope) | Repaired a PRE-EXISTING baseline CI failure outside Phase 0 scope: `TcpRaftTransport.closeQuietly(Socket)` NPE'd on a null socket in `tearDown` (a handshake-failed `PeerConnection` holds a null socket), reddening `build-and-test`/gate-1/gate-2 for ≥6 commits. Fixed minimally — null guard mirroring the already-guarded `closeQuietly(AutoCloseable)` sibling. Red→green verified on JDK 25; second-agent replayed. Evidence: [baseline-ci-transport-closequietly.md](captures/baseline-ci-transport-closequietly.md). | The operator gated harness work on a green baseline (RR-107: local-green ≠ CI-green). A deterministic, contained NPE in a cleanup helper is the safest possible unblock and directly serves that gate. Not a consensus-core change; zero TLS/security behavior change. | Yes (revert the guard). |
| D-006 | 2026-06-21 | technical | The R-01′ owner-thread tripwire reuses `RaftNode`'s existing `InvariantChecker` seam (`:219–224`) — throws in test/sim, metric+SEVERE in prod — mirroring `ConfigStateMachine.assertOwnerThread()` (RR-029/W-1). **Correction:** `RaftNode` has NO `metrics` field (verified `:240–364`); route the tripwire purely through `invariantChecker.check("raft_owner_thread", …)`, whose documented contract (`:214–217`) is already "throw in test, increment a metric in prod." | The seam already drives the 9 in-node invariants; reusing it means zero new verification plumbing and identical test/prod duality. | Yes (impl detail). |
| D-005 | 2026-06-21 | scope | M1 artifacts (4 `adr-multiraft-*`/`adr-throughput-target` ADRs + `docs/multiraft/`) are currently **uncommitted** (`git status ??`). Will bring them into git at the first clean seam to prevent loss; immutable thereafter (§7). | They are "accepted/immutable" per the brief but at risk while untracked. Committing them is the conservative, loss-preventing default. | Yes. |
| D-004 | 2026-06-21 | sequencing | Wrote `threading-contract.md` (Workstream A.3) **before** building the concurrent harness (A.1), though §4 numbers the harness first. | The contract is the specification the harness *asserts*; it is explicitly "written before the [re-threading] code." Writing it first de-risks the harness design. **Hard constraint preserved:** no Workstream B re-threading is blessed until the harness exists and is proven to catch an injected race. | n/a |
| D-003 | 2026-06-21 | technical | The Workstream C decision-gate **number** (single-group throughput with the fix) is a **dedicated-hardware lane item**, not measurable in this 2-vCPU codespace (~136–172 commits/s ceiling, ENV-BLOCKED). Here: build + smoke the measurement harness only. | Matches the S5/S7.5 reality and the session's own §6 caveat ("otherwise local with the honest caveat that a dedicated-hardware confirmation is a manifest item"). | n/a |
| D-002 | 2026-06-21 | scope | Commit to `main` at clean, pushed seams (per this project's established norm) rather than branch-per-change. | Every prior session and the current `HEAD` history commit directly to `main`; the session brief itself directs "checkpoint at a clean, committed, pushed seam." Branch-per-change would diverge from the repo's entire history. | Yes. |
| D-001 | 2026-06-21 | scope | Read "gates 1–7.5 stay green" as **gates 1–7 + the S7.5 perf/soak surface**. There is no `gate-7.5.sh`. | Recon: the gate chain is `gate-1.sh … gate-7.sh`; "7.5" exists only as `docs/session-7.5/` + `perf/` scripts. The cumulative correctness chain is 1–7; S7.5 is the perf/soak work layered on. | n/a |

## Standing assumptions (carried, not yet decisions)

- **No live soak in this environment.** `ps` shows no `java`/`mvn` soak process here; the S7.5
  soak-trend commits were recorded against a remote perf box (m6id.4xlarge). A soak may still be
  live on that hardware — irrelevant to dev work in this repo, but I will not assume this box is
  the perf box.
- **Build is Maven multi-module**, 14 `configd-*` modules; running the full gate chain here is
  multi-hour. Discipline: keep the build + touched-module tests green as I go; run targeted gate
  steps (gate-1 build, consensus-core tests, jcstress curated subset) at seams; defer the full
  chain to CI/nightly; **report honestly what was run vs. deferred.**
