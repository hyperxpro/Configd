# Session 2 — Recorded Formal-Methods Decisions

Three decisions: (a) TLC liveness scope (RR-026), (b) Apalache descope (RR-063), (c) evidence-hygiene
cleanup plan (RR-061/RR-062, with RR-060 cross-check).

> **B7 EXECUTION OUTCOME (2026-06-11).** The decisions below were executed:
> - **(a) Liveness:** ReadIndexSpec now has a fair `LiveSpec` + the `ReadEventuallyServed` property,
>   checked **GREEN** at smoke bounds (`gates/spec-smoke/ReadIndexSpec-liveness.cfg`, 24s), with the
>   **wrong-fairness vacuity proven** (same property under the unfair `Spec` → VIOLATED:
>   `ReadIndexSpec-livenessvacuity.cfg`, capture saved). This is the first model-checked liveness property
>   in the repo. ConsensusSpec `EdgePropagationLiveness` stays the documented bounded-model artifact (the
>   spec header F-V2-02 already records the expected stutter-at-bound) and SnapshotInstall liveness is
>   deferred (its always-enabled `CrashRestart` makes unconditional progress unprovable without a
>   crash-fairness modeling choice — out of scope; safety + the durability invariants are the deliverable).
>   Full-bound liveness runs remain deferred-and-documented (est. >1h for ConsensusSpec).
> - **(b) Apalache:** descoped; the CM-003 disposition text is in this file (unchanged).
> - **(c) Hygiene:** EXECUTED — `spec/tlc-output.txt` regenerated from this tree (no foreign-tree cite),
>   `spec/states/` (454 files) + 11 ignored-yet-tracked `.bin` removed from git, `spec/states/` gitignored,
>   and the reproducible counterexamples are now the committed seeded-bug cfgs (ConsensusSpec-ackonappend,
>   SnapshotInstallSpec-truncatebeforepersist) rather than opaque `.bin` dumps.

---

## (a) RR-026 — TLC liveness: what THIS session checks

**Finding.** No liveness property has ever been model-checked. `EdgePropagationLiveness` is **commented
out** of `ConsensusSpec.cfg` (`:42-44`, header note "expect 10x+ runtime"); `ReadIndexSpec.cfg` and
`SnapshotInstallSpec.cfg` have **no `PROPERTIES`** block at all; `CHECK_DEADLOCK FALSE` is set in all three
cfgs (full and smoke). All 19 checked invariants are safety-only.

**Decision (recommended): enable a minimum, bounded liveness pass this session; defer the full-bound
liveness run with documentation.** Concretely:

1. **Deadlock checking is NOT cheaply re-enabled as-is.** `CHECK_DEADLOCK FALSE` is *correct* for these
   models: all three deadlock at the model bounds by construction (terms/indices exhausted → no action
   enabled; `tlc-results.md:90-91` "Non-bug: Deadlock at model bounds"). Flipping it to TRUE would report
   the expected boundary deadlock as a violation. **Do not blindly enable it.** The honest move is a
   separate **deadlock-before-bound** check: keep `CHECK_DEADLOCK FALSE` on the bounded cfgs, and instead
   add one liveness property per spec that asserts progress *short of* the bound (below), which catches a
   real deadlock (a stuck state reachable before exhaustion) without the boundary false-positive.

2. **One bounded liveness property per spec, run under the smoke cfgs IF runtime allows.**
   - **ConsensusSpec — `EdgePropagationLiveness`** (already written, `ConsensusSpec.tla:267-270`:
     `(∃ n: commitIndex[n] >= i) ~> (edgeVersion[e] >= i)`). Run it under the smoke cfg
     (`MaxTerm=2, MaxLogLen=3`) with `SPECIFICATION Spec` + `PROPERTIES EdgePropagationLiveness`. The spec
     header (`:260-266`, F-V2-02) warns of a *spurious* bounded-model liveness violation at
     `MaxTerm` exhaustion (all nodes self-vote, no further election possible). **Mitigation:** the
     `WF_vars(Next)` fairness in `Spec` (`ConsensusSpec.tla:511`) plus the smoke `MaxTerm=2` reduces but
     may not eliminate the artifact. Run it; if the only counterexample is the known stutter-at-bound
     case, record it as a documented bounded-model artifact (NOT a fix), exactly as the header already
     anticipates. This is the property of record for the §0 edge-propagation guarantee.
   - **ReadIndexSpec — new `ReadEventuallyServed`** (small addition; the spec lacks any liveness
     property and `Spec` has **no fairness** — `ReadIndexSpec.tla:186` `Spec == Init /\ [][Next]_vars`).
     Property: every initiated ReadIndex is eventually served or invalidated by a term change, i.e. a
     pending read leads-to (served ∨ leader-stepped-down). Requires adding `WF_vars(Next)` (or targeted
     fairness on `ReadHeartbeatAck`/`CompleteReadIndex`) to `Spec` — a real spec change, so it is staged
     for the spec re-run task, not done here.
   - **SnapshotInstallSpec — new `SnapshotEventuallyInstalled`** (same situation: no fairness,
     `:145`). Property: a follower behind the leader's snapshot eventually installs it (a `SendInstall…`
     leads-to the follower's `snapshot.index` reaching the leader's). Also needs fairness added → staged.

3. **Runtime estimate (decides defer-or-not).** Liveness checking is "10x+" the safety runtime
   (`ConsensusSpec.cfg:41`). Smoke safety wall-times on this box, **CPU credits available** (full cfgs in
   parens for reference):
   - ConsensusSpec smoke: **2m04s** safety (full: 14m00s). At 10x → ~20 min smoke liveness; **under 1 h**
     → run this session.
   - ReadIndexSpec smoke: **10s** safety (full: 7m45s). At 10x → ~2 min smoke liveness; run this session.
   - SnapshotInstallSpec smoke: **11s** safety (full: 2m37s). At 10x → ~2 min smoke liveness; run.
   - **Full-bound liveness is deferred-and-documented:** ConsensusSpec full at 10x ≈ **2h20m** (>1 h
     threshold) → defer with a recorded estimate. Apply the credit-throttle multiplier from the smoke cfg
     headers (~3.3x) when the box is throttled: even smoke ConsensusSpec liveness could hit ~65 min
     throttled, so **gate the smoke liveness run on credits-available** (or run it on a quiet box per the
     environment note) and budget accordingly.

   **Net:** run all three **smoke** liveness properties this session (est. ~20 min + ~2 min + ~2 min
   credits-available); **defer all three full-bound** liveness runs with the estimates above recorded.
   Keep `CHECK_DEADLOCK FALSE`; the progress properties cover real (pre-bound) stuckness.

**Caveat to flag to the lead:** the ReadIndex/Snapshot liveness properties require adding fairness to their
`Spec` (currently fairness-free). That is a substantive spec edit — it belongs to the spec re-run task and
must be reviewed (a wrong fairness annotation can make a liveness check vacuously pass). The ConsensusSpec
property needs only an uncomment + the documented artifact handling.

---

## (b) RR-063 — Apalache: formally descope

**Finding.** Apalache is claimed as part of the verification approach (`research.md:577`) but **no binary
or config exists** anywhere on the machine (`which apalache` + `find /home /usr/local /opt -iname
'*apalache*'` → nothing; CM-003). The three specs are written for TLC and run under TLC in CI
(`ci.yml`); the `EXTENDS` and idioms are TLC-shaped (e.g. `CHOOSE`, explicit `CHECK_DEADLOCK`).

**Decision (recommended): formally descope Apalache. TLC is the verification of record.** Installing and
learning Apalache mid-session is scope creep with no payoff this session: TLC already model-checks all 19
safety invariants green at the documented bounds, and the liveness work above is also TLC. Apalache's
distinct value (symbolic/SMT checking, unbounded-ish parameters via induction) is a *future* enhancement,
not a Session-2 deliverable, and adopting it would require re-validating that the specs type-check under
Apalache's stricter typing — a multi-day effort.

**Descope text (for the claim-evidence pass to cite for CM-003):**

> **Apalache — DESCOPED (ADR/decision, Session 2, RR-063/CM-003).** Apalache was named in `research.md`
> as part of the formal-methods approach but was never installed, configured, or run; no binary or `.cfg`
> for it exists in the repo or on the build host. The verification of record for Configd's TLA+ specs is
> **TLC** (`tla2tools.jar`, run in CI via `.github/workflows/ci.yml` against `ConsensusSpec`,
> `ReadIndexSpec`, `SnapshotInstallSpec`). All references to Apalache as a current verification tool are
> **aspirational/future-work** and are relabeled as such; the claim that Apalache is part of the *current*
> approach is **withdrawn**. Re-introducing Apalache (for symbolic checking / inductive invariants at
> larger parameters) is tracked as future work, not a release gate.

Action for the doc-honesty pass: change `research.md:577` (and any sibling claim) from present-tense
"verified with TLC and Apalache" to "verified with TLC; Apalache is future work." (That edit is owned by
the claim-evidence / doc-reconciliation task, not this document.)

---

## (c) RR-061 / RR-062 — Evidence-hygiene cleanup plan (RR-060 cross-checked, not resolved)

**Findings.**
- **RR-061 (P3):** `spec/tlc-output.txt:3-7` cites a **foreign tree** `/home/ubuntu/Programming/Configd`
  (this repo is `/home/ubuntu/Code/Configd`) and is still historically cited as evidence. Confirmed: the
  file's parse lines reference `/home/ubuntu/Programming/Configd/spec/...`.
- **RR-062 (P3):** the seeded-bug counterexample `.bin` traces that `tlc-results.md` calls "non-vacuous,
  proven by seeded counterexample" are **gitignored** (`.gitignore:4 *.bin`) → the proof is
  unreproducible from a clean checkout. Compounding: **11 `.bin` TTrace files are nonetheless tracked**
  (committed before / despite the ignore rule — `git ls-files spec/*.bin` → 11), and **454 `spec/states/`
  working files are committed as cruft** (RR-062's "~600" is the on-disk figure; 454 are tracked). CI does
  run all 3 specs (`ci.yml:61-66`), so the green run is reproducible even though the *counterexample* is
  not.
- **RR-060 (P2, S7-owned — CROSS-CHECK ONLY):** 15 committed TTrace failure artifacts (the 11 `.bin` + 4
  `.tla`: `ReadIndexSpec_TTrace_1776462705.tla`, three `SnapshotInstallSpec_TTrace_*.tla`) capture
  ReadIndex/Snapshot *failure* runs narrated nowhere in `tlc-results.md`. **Cross-check result:** the 4
  readable `.tla` traces are TLC's standard TTrace-on-FAILED output; the SnapshotInstall ones show the
  diverging-term / two-leader scenarios consistent with the de-vacuumed `NoCommitRevert` seeded-bug runs
  (`tlc-results.md:42-45`), and the ReadIndex one with the de-vacuumed `ReadFreshness`/`NoStaleLeaderServe`
  seeding (`:30-35`). I.e. they appear to be the *intended* seeded-counterexample artifacts, not evidence
  of an un-narrated real spec failure — **but confirming that and documenting each is RR-060's job (S7), I
  am only flagging the likely provenance, not resolving it.**

**Decision: cleanup actions (EXECUTE LATER with the spec re-run task — listed, not performed here).**

1. **Regenerate `spec/tlc-output.txt` from this tree, or remove it.** Recommended: **regenerate** it by
   running the full TLC trio from `spec/` in `/home/ubuntu/Code/Configd` and capturing fresh output (paths
   will then read `/home/ubuntu/Code/Configd/...`). If a canonical run-log is wanted, name it
   unambiguously (e.g. `spec/tlc-run-<date>.txt`) and have `tlc-results.md` cite that exact file. Fixes
   RR-061's foreign-tree citation.

2. **Make the seeded counterexamples reproducible (RR-062 core).** Choose one:
   - **(preferred) Stop relying on committed `.bin`.** Replace the "proven by seeded counterexample" claim
     in `tlc-results.md` with a **re-runnable recipe**: a committed `spec/seeded-bugs/<inv>.tla` variant
     (the buggy action) + the exact TLC command that produces the counterexample, so anyone can regenerate
     it. Counterexample reproduction becomes a command, not a binary artifact. This is the durable fix and
     matches the charter's "VERIFIED needs a re-runnable command."
   - **(fallback) Track the `.bin` traces explicitly.** If keeping the binaries, `git add -f` the specific
     counterexample `.bin` files AND add a `tlc-results.md` section naming each and what it proves; but
     `.bin` is opaque and version-fragile, so the recipe approach is preferred.

3. **Remove `spec/states/**` from version control.** The 454 tracked `spec/states/` files are TLC working
   directories (fingerprint/queue/checkpoint data) — pure cruft, never evidence. Action:
   `git rm -r --cached spec/states/` and add `spec/states/` to `.gitignore`. (They regenerate on every TLC
   run.)

4. **Reconcile the `*.bin` gitignore vs the tracked `.bin` files.** Either (a) keep `*.bin` ignored and
   `git rm --cached` the 11 tracked TTrace `.bin` (if action 2 takes the recipe route, they are no longer
   needed as evidence), or (b) if action 2's fallback is chosen, narrow the ignore so the kept
   counterexample binaries are tracked deliberately. Do not leave the contradiction (ignored-yet-tracked)
   in place.

5. **RR-060 hand-off (do NOT resolve):** leave the 15 TTrace failure artifacts for S7 to narrate at
   release review; attach this section's provenance cross-check (likely seeded-counterexample outputs from
   the A2 de-vacuation runs) as a starting note. If actions 2–4 remove/rename the `.bin` traces, flag that
   to S7 so its "15 artifacts" inventory stays accurate.

**Sequencing:** all of (c) executes alongside the spec re-run (when TLC is run from this tree anyway), so
the regenerated `tlc-output.txt` and the recipe-based counterexamples are produced in the same pass that
re-verifies the specs. Nothing here touches `spec/` in Session 2's documentation phase.
