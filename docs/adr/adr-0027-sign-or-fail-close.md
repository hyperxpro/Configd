# ADR-0027: Sign-or-Fail-Close in the State Machine

## Status
Accepted (2026-04-19)

## Context

Every committed Configd command (`PUT`, `DELETE`, `BATCH`) is signed by the
state machine before its mutation is published to subscribers. The
signature (Ed25519 over the canonical batch encoding plus an `(epoch,
nonce)` replay-binding payload — see ADR-0018 fan-out and the F-0052
finding) is what edge-side `DeltaApplier` instances verify before
applying the delta locally. An unsigned (or wrongly-signed) command on
the apply path therefore has two failure modes:

1. **Edges that verify** reject the delta and fall behind, eventually
   tripping the staleness alert.
2. **Edges that don't verify** silently accept a tampered or unsigned
   mutation, which is the supply-chain failure the signing chain exists
   to prevent.

The authoritative signing call lives in
`configd-config-store/src/main/java/io/configd/store/ConfigStateMachine.java`
(method `signCommand`). When the configured `ConfigSigner` is unable
to produce a signature — either because the underlying provider throws
a `GeneralSecurityException`, or because the signer is verify-only and
its `sign()` throws `IllegalStateException` — we must NOT publish the
mutation as if signing had succeeded.

The S3 row in `docs/ga-review.md` and the `signFailureFailsClose`
regression test in `ConfigStateMachineTest` already encode this
behaviour. This ADR formalises the contract those tests assert against,
so that the contract is discoverable from the decisions index rather
than only from the test name.

## Decision

The state machine **fails closed on signature failure**:

1. `ConfigStateMachine.signCommand` catches both
   `GeneralSecurityException` and `IllegalStateException` from the
   underlying signer.
2. On either, it logs at SEVERE, clears the cached
   `lastSignature`/`lastEpoch`/`lastNonce`, and re-throws an
   `IllegalStateException` with message prefix `fail-close: signing
   failed for committed command`.
3. The thrown exception propagates out of `apply()`. Raft's apply
   contract treats an unchecked throw as a hard fault and refuses to
   commit further entries until operator intervention.
4. **No unsigned mutation is ever published to subscribers**, because
   `notifyListeners(...)` only runs on the success path of
   `signCommand`.

The contract is symmetric across `PUT`, `DELETE`, and `BATCH` — the
fail-close path is the same single `signCommand` invocation in each
arm of the apply switch.

## Rationale

1. **Supply-chain integrity.** Edge-side `DeltaVerifier`/`DeltaApplier`
   refuses unsigned deltas; the only way to keep the contract honest
   is to refuse to produce them in the first place.
2. **No partial-trust state.** Letting `apply()` succeed without a
   signature would mean two replicas that both committed the same Raft
   entry could disagree on whether the resulting delta is verifiable
   downstream. That divergence is unrecoverable without a forensic
   audit.
3. **Loud failure beats silent corruption.** Halting the state
   machine pages the operator immediately (write availability drops);
   silent acceptance would surface much later, possibly after the
   tampered config had already been deployed.

## Consequences

- **Positive:** No unsigned mutation ever crosses the apply boundary.
  The signing chain is the single source of truth for delta
  authenticity. The fail-close path is testable from a single seed
  (`ConfigStateMachineTest$SigningIntegration.signFailureFailsClose`).
- **Negative:** Write availability becomes a function of signing-key
  health. A corrupted, unreadable, or revoked Ed25519 keypair stops
  writes globally until the operator rotates per
  `runbooks/disaster-recovery.md` ("Signing key compromise" section).
- **Operator burden:** the on-call rotation (ADR-0025) must include
  a key-rotation drill at least quarterly to keep mean time to recover
  from a key-health failure inside the documented incident SLA.

## Related

- ADR-0018 — event-driven notification (consumer of the signed delta)
- ADR-0025 — on-call rotation procurement (who responds when fail-close trips)
- F-0052 (signing-payload binding `epoch`+`nonce`)
- gap-closure §S3 (PA-1004)
- `docs/ga-review.md` — Phase 7 row "Sign-or-fail-close in state machine"
- `runbooks/disaster-recovery.md` — signing-key compromise path

## Verification

- **Testable via:** `configd-config-store/src/test/java/io/configd/store/ConfigStateMachineTest.java` — the nested `SigningIntegration` test class includes `signFailureFailsClose`, which constructs a verify-only `ConfigSigner` (public key only, `sign()` throws), then asserts that `apply(...)` throws `IllegalStateException` with message containing `signing failed`. The same class also covers the success path (`signatureVerifiesWithPublicKey`) so the regression has both polarity assertions.
- **Invalidated by:** any change to `ConfigStateMachine.signCommand` that catches `GeneralSecurityException` / `IllegalStateException` and returns normally instead of re-throwing; or by moving the `notifyListeners(...)` call before `signCommand(...)` in the apply switch (which would publish first, sign second).
- **Operator check:** in the live cluster, `configd_state_machine_apply_failure_total{reason="sign_fail_close"}` (when wired by F5/F6 metric work) increments visibly, and `configd_write_commit_total` rate drops to zero — pages the on-call rotation per `ConfigdControlPlaneAvailability`. The incident commander follows `runbooks/disaster-recovery.md` "Signing key compromise" branch to rotate the keypair.
