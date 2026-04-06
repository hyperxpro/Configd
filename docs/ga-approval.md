# GA Approval — Configd v0.1

**Status:** UNSIGNED TEMPLATE. This file is not an approval. It is the
form an approver completes to grant approval. While every signature
line is blank, no GA promotion has occurred.

This template was authored by the autonomous self-healing loop on
2026-04-22 as a Type C **artifact preparation** (not Type C closure —
the loop cannot close Type C). Per the §4.7 honesty invariant, the
loop produced the form; only humans can fill it in.

---

## 1. The statement being signed

By signing this document, I attest, **under my own name and not on
behalf of the loop**, that:

1. I have personally read `docs/ga-review.md` in full and accept its
   GREEN / YELLOW / RED accounting as accurate as of the commit SHA
   recorded in §3 below.
2. I have personally verified each item in the §2 minimum-evidence set
   below — not "the loop reported it green," but "I have the artifact
   in hand or have witnessed the operation."
3. I have personally reviewed the §4 residuals list and accept each
   accepted-residual on behalf of the v0.1 release.
4. I am authorised by my organisation to grant GA promotion for the
   role I am signing under (architect / SRE / security / performance /
   release engineer).
5. I understand that signing here authorises promotion of the commit
   SHA in §3 to the GA channel, and nothing else. Subsequent commits
   require a fresh approval cycle.

This signature attests to **personal verification of Type B and Type C
evidence**. It does not attest to the loop's work — Type A gates were
green when the loop terminated and that is recorded in
`docs/loop-state.json` (`stability_signal_history`); it is not
re-attested here.

---

## 2. Minimum evidence set (per `docs/handoff.md` §6)

Approver MUST tick every box and attach the named artifact. An unchecked
box is a blocker; a checked box without an attachment is a violation
of the §4.7 honesty invariant and invalidates this approval.

- [ ] **§6.1 Green test suite on the approved SHA.**
      Command: `./mvnw -T 1C test`
      Expected: `BUILD SUCCESS`, `Tests run: ≥ 20132`.
      Attach: surefire log line and the SHA the run was on.
      _Artifact / link:_ ____________________________________________

- [ ] **§6.2 End-to-end release dry-run on a fork.**
      Per `docs/handoff.md` §2 Step 7. Image must be built, signed by
      Cosign, attested by SLSA, SBOM attached, `cosign verify` and
      `gh attestation verify` both pass from an unrelated environment.
      _Release URL:_ ______________________________________________
      _`cosign verify` output:_ ____________________________________
      _`gh attestation verify` output:_ ____________________________

- [ ] **§6.3 On-call rotation attestation.**
      Per `docs/handoff.md` §2 Step 5 — operator-lead signature on
      `ops/on-call-rotation.md` (or equivalent), naming paging service,
      24×7 schedule, escalation path, incident-commander pool.
      _Operator-lead signature line / commit SHA:_ _________________

- [ ] **§6.4 Disposition for every RED row.**
      Per `docs/handoff.md` §2 Step 6. Every RED in
      `docs/ga-review.md` resolves to one of: implementation commit
      SHA *or* a row in `docs/decisions/adr-0027-v0.1-accepted-residuals.md`
      (which must exist before signing).
      _ADR-0027 commit SHA (if used):_ _____________________________

- [ ] **§6.5 At least one real drill executed.**
      Either restore-from-snapshot (Step 3) **or** leader-loss
      recovery (Step 4) — one is enough; both is preferred.
      _Drill result file path:_ ____________________________________

---

## 3. Approved commit

**SHA:** ______________________________________________________________
**Branch / tag:** ____________________________________________________
**Reactor evidence:** _________________________________________________
   (paste the literal `[INFO] Tests run: …, Failures: 0, Errors: 0` line
   from a local run on this SHA)

---

## 4. Accepted residuals — explicit list

Every YELLOW row in `docs/ga-review.md` that the approver chooses to
accept *as-is* for v0.1 must be enumerated here, with the named
compensating control. Items left implicit are not accepted. RED rows
must either be GREEN (implemented) or YELLOW-via-accepted-residual
before signing — `docs/decisions/adr-0027-v0.1-accepted-residuals.md`
holds the formal acceptance.

| GA row | Status accepted | Compensating control / mitigation | Sunset date |
|--------|-----------------|------------------------------------|-------------|
|        |                 |                                    |             |
|        |                 |                                    |             |
|        |                 |                                    |             |

---

## 5. Signatures

Five role-based signatures. **All five required.** A missing signature
is not "deferred consent" — it blocks GA. Roles may be combined onto
one human if the organisation does so by policy, but each role is
signed for separately.

### 5.1 Architect

| Field | Value |
|-------|-------|
| Name | _____________________________________________________________ |
| Date (UTC) | _______________________________________________________ |
| Signature | ________________________________________________________ |
| What I attest | I personally validated the formal-spec conformance review per `docs/handoff.md` §2 Step 8. The TLA+ specs match the Java implementation as of the SHA in §3. |

### 5.2 Site reliability engineer

| Field | Value |
|-------|-------|
| Name | _____________________________________________________________ |
| Date (UTC) | _______________________________________________________ |
| Signature | ________________________________________________________ |
| What I attest | I personally witnessed at least one drill from §6.5 and read its result file. The on-call rotation per §6.3 is real. The runbooks in `ops/runbooks/` are executable as-written. |

### 5.3 Security

| Field | Value |
|-------|-------|
| Name | _____________________________________________________________ |
| Date (UTC) | _______________________________________________________ |
| Signature | ________________________________________________________ |
| What I attest | I personally reviewed every RED row in `docs/ga-review.md` §Security and either implemented it or signed off on its acceptance in ADR-0027. The supply-chain pipeline (B5/B6/B7/PA-6012) was exercised end-to-end per §6.2. |

### 5.4 Performance

| Field | Value |
|-------|-------|
| Name | _____________________________________________________________ |
| Date (UTC) | _______________________________________________________ |
| Signature | ________________________________________________________ |
| What I attest | At least one real-duration calendar-bounded harness from `docs/operator-runsheet.md` was executed on production-shaped hardware and its result file is committed. The numbers in `docs/perf-baseline.md` are backed by JMH JSON / HdrHistogram artifacts under `perf/results/` for the SHA in §3. |

### 5.5 Release engineer

| Field | Value |
|-------|-------|
| Name | _____________________________________________________________ |
| Date (UTC) | _______________________________________________________ |
| Signature | ________________________________________________________ |
| What I attest | The release pipeline was exercised end-to-end per §6.2. The image at the release URL in §6.2 deploys cleanly to a staging cluster matching `deploy/kubernetes/`. The image SHA in §6.2 is the deployable artifact for this approval. |

---

## 6. Revocation

This approval expires 90 calendar days after the latest signature
date in §5, **or** on the date of any of the following events,
whichever is sooner:

- A new commit lands on the GA channel that is not the SHA in §3.
- A finding of severity S0 or S1 (per the loop's severity rubric) is
  raised against the SHA in §3 and is not closed within 7 calendar
  days.
- The on-call rotation attested in §6.3 lapses or is materially altered.
- The image at the release URL in §6.2 is withdrawn, revoked, or its
  Cosign signature fails to verify.
- Any signatory in §5 issues a written revocation. A single revocation
  voids the approval for the role of that signatory, and the
  organisation must re-collect that role's signature to re-instate.

Once revoked or expired, this file is **historical** and a new
`docs/ga-approval.md` must be authored from this template for any
subsequent GA cycle. Do not edit this file post-signature; archive a
copy under `docs/ga-approval-history/<UTC>.md` and start fresh.

---

## 7. Versioning

This template is v1, authored 2026-04-22 by the autonomous self-healing
loop (Opus 4.7) immediately after iter-2 termination per §5
(`stability_signal = 0` × 2 consecutive iterations). The loop's
output ends here; a human must fill in everything above.
