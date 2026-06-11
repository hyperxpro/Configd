# Claim–Evidence Matrix — Session-2 Conversion Addendum

> The Session-1 matrix (`docs/audit-session-1/claim-evidence-matrix.md`) is an immutable audit
> artifact (charter Hard Rule 8). This addendum records the rows Session 2 was charged to convert
> (`handoff-to-session-2.md` §4), with the new status and the re-runnable evidence that converted
> it. Statuses here SUPERSEDE the Session-1 statuses for the listed CM-IDs as of branch
> `session-2-correctness`. Each row cites the commit and the command/test that proves it.
>
> Conversion verbs: **→VERIFIED** (now true as stated, with a re-runnable proof);
> **→RESOLVED-BY-FIX** (was CONTRADICTED/FICTION because the code was wrong/absent; the code is now
> fixed so the documented claim holds); **→RESOLVED-BY-AMEND** (the claim was wrong and the
> document was corrected by ADR/contract edit so doc and code now agree); **→DESCOPED** (claim
> formally withdrawn with an ADR).

## Ack model (RR-004 / ADR-0033)

| CM | S1 status | New status | Evidence |
|---|---|---|---|
| CM-009 | CONTRADICTED (ack≠commit) | **→RESOLVED-BY-FIX** | ADR-0033; HTTP 200 `Committed: seq=S` only after quorum commit+apply (`HttpApiServer.handlePut`); `AckEqualsCommitTest` (200 acked writes survive randomized leader-kill, 3 fault shapes) + live linz 200⇒`:ok` LINEARIZABLE 8/8 matrix. Commits `cdb7314`/`4bb6323`, linz `3ac8cef`. |
| CM-046 | CONTRADICTED (ack carries local proposalId) | **→RESOLVED-BY-FIX** | Same; `Committed(seq)` carries the applied-mutation sequence S that §6's cursor consumes. `RaftProposerCommitConfirmTest`. |
| CM-059 | CONTRADICTED (§9 RYW "Guaranteed") | **→RESOLVED-BY-FIX** | Ack point is now commit-confirmed; §9 holds on the control-plane axis (edge propagation remains RR-001/S3). |
| CM-171 | VERIFIED (R-14 open) | **note** | R-14/RR-004 now RESOLVED; the "open" qualifier is historical. |

## HLC / staleness (RR-015 / ADR-0035)

| CM | S1 status | New status | Evidence |
|---|---|---|---|
| CM-010 | FICTION (no per-entry HLC) | **→DESCOPED** | ADR-0035 (Accepted): per-entry HLC withdrawn under single-group ADR-0030; staleness redefined as leader-assigned commit timestamp (ADR-0034 `CommitNotification.commitTimestampMillis`). Contract §2/§4 amended (commit `2a1830b`). |
| CM-037 | CONTRADICTED (staleness off missing field) | **→RESOLVED-BY-AMEND** | §2 staleness measurement redefined to the commit-notification timestamp; S3 implements the measurement (handoff). |
| CM-044 | FICTION (per-entry HLC for cross-group) | **→DESCOPED** | Cross-group ordering moot under one group; ADR-0035. |
| CM-064 | CONTRADICTED (ADR-0004 half-implemented) | **→RESOLVED-BY-AMEND** | ADR-0004 amended by ADR-0035; §4 seq reworded to the applied-mutation sequence (matches ADR-0033). |

## Linearizability story (RR-016 / RR-028 / CM-137 / CM-165)

| CM | S1 status | New status | Evidence |
|---|---|---|---|
| CM-048 | CONTRADICTED (Wing&Gong test is scripted) | **→RESOLVED-BY-AMEND** | Contract §7 rewritten to cite the real Porcupine checker + sim-history bridge (`AdversarialSim`/`OpHistoryTest`/`SimHistoryCheck`). Commit `2a1830b`/`3ac8cef`. |
| CM-137 | EXISTS-UNVERIFIED (discrimination not re-run) | **→VERIFIED** | Discrimination gates re-run live at HEAD: lost-acked-write + stale-read patches ⇒ checker RED; controls GREEN (`docs/session-2/captures/linz-discrimination.txt`). |
| CM-165 | EXISTS-UNVERIFIED (six gates partial) | **→VERIFIED** | Gates re-verified: self-tests 8/8, discrimination RED/GREEN (ii), faulted live runs (iii), byte-identical schedule (iv); seed matrix 2001-04 × {3,5}-node 8/8 LINEARIZABLE (`linz-rr004-matrix.txt`). |
| CM-032 | VERIFIED (scoped to one run) | **→VERIFIED (broadened)** | Now multi-seed/5-node matrix + sim-history checking, not one run. |
| CM-035 | VERIFIED (scoped) | **→VERIFIED (broadened)** | Same; INV-L1 holds across the matrix. |

## Determinism / sweep / timing (RR-010 / RR-012 / RR-006)

| CM | S1 status | New status | Evidence |
|---|---|---|---|
| CM-189 | CONTRADICTED (election RNG entropy-seeded) | **→RESOLVED-BY-FIX** | `RaftSimulation.electionRandom(NodeId)` derives the election RNG from the master seed; `SimulationDeterminismTest` (same seed ⇒ byte-identical schedule). Commit `c452aa1`. |
| CM-191 | CONTRADICTED (election ~2.3s vs documented 150-300ms) | **→RESOLVED-BY-FIX** | RR-006: `...Ms` config now real milliseconds (tickPeriodMs conversion); live re-election 0.235–0.575s; `TimingConversionTests`. Commit `9905d9c`. Documented 150-300ms values now accurate. |
| CM-049 | CONTRADICTED (StalenessUpperBoundTest asserts transitions) | **→RESOLVED-BY-AMEND** | Contract §7 row corrected to describe the actual threshold-transition assertion; p99-distribution claim marked S3-owed. |
| CM-052 | CONTRADICTED (§8 names removed assertions) | **→RESOLVED-BY-AMEND** | §8 table: removed `sequence_monotonic`/`sequence_gap_free` rows; added `durable_prefix_no_gap`, `apply_owner_thread`, `version_monotonicity`. |
| CM-058 | CONTRADICTED (wrong exception type) | **→RESOLVED-BY-AMEND** | §8 corrected to `AssertionError`. |

## Runtime twins / formal (RR-030 / RR-026 / RR-063 / CM-002 / CM-003)

| CM | S1 status | New status | Evidence |
|---|---|---|---|
| CM-002 | CONTRADICTED (7 invariants lack twins) | **→RESOLVED-BY-FIX** | B7 built the 7 missing twins (ReadIndexSpec×3 + SnapshotInstallSpec×4); `AssertionTwinFiringTest` observes every twin firing; `docs/session-2/assertion-verification.md` has no UNVERIFIED row. Commits `c3dc42f`/`a3c93c0`. |
| CM-003 | FICTION (Apalache claimed, absent) | **→DESCOPED** | Apalache formally withdrawn (`docs/session-2/formal-decisions.md`); TLC is the verification of record. ADR-0007 amendment / research.md disposition owed in S6 doc-honesty pass. |

## Notes on rows NOT converted (kept honest with register references)

- **CM-001** (election liveness + edge-propagation not model-checked): PARTIALLY converted — RR-026
  now model-checks one bounded liveness property (ReadIndexSpec `ReadEventuallyServed`); election
  liveness + edge-propagation liveness remain bounded-model-only/deferred (full-bound deferred,
  documented in `spec/tlc-results.md`). Row stays CONTRADICTED-as-stated with the RR-026 partial.
- Edge/data-plane CM rows (CM-006/022/036–042 etc.) remain CONTRADICTED/FICTION — RR-001 is OPEN,
  owned by Session 3; the §4.6 boundary (ADR-0034) reduces blast radius but does not implement the
  wire path.
