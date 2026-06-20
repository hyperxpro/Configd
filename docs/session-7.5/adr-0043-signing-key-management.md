# ADR-0043 — Signing-key management: fail-closed on co-location; prod = external secret (D-1 P1)

- **Status:** Accepted (Session 7.5). Supersedes the S7 warn-only mitigation (decision-log D-1, D-6).
- **Severity reclassification:** D-1 is reclassified **residual → P1** (correctness/security risk: a core
  security guarantee is defeated under a documented threat). Tracked in `docs/readiness-register.md`.
- **Crypto-review flag:** the PA-2021 / audit-log key constructions remain flagged for specialist
  cryptographic review before production (S7 handoff §2) — this ADR changes *key location/posture*, not
  the primitives.

## Context

PA-2021 (ADR-0042) protects the Raft at-rest artifacts (snapshot blob, WAL records,
`DurableRaftState`) with HMAC-SHA-256 keyed by `K_integrity`, and the audit-log chain with `K_audit`.
**Both are HKDF-derived from the cluster Ed25519 signing key** (`ConfigdServer.deriveRaftIntegrityEnvelope`
/ `deriveAuditLogKey`). Their secrecy — and therefore all tamper-evidence — depends on the signing key
living **outside attacker-writable artifact storage**.

The S7 insecure **default** co-located the key with the data it protects:
`dataDir.resolve("signing-key.bin")` (`ConfigdServer.java:210`). Under that default, a **T3/A2 storage
or backup writer who can tamper the WAL/snapshot can ALSO read the key and recompute a valid MAC** —
Layer B integrity is worthless against a full-host/storage compromise. S7 emitted a LOUD warning but
**continued startup** (`deriveRaftIntegrityEnvelope` warns, returns). The warning is not a control; an
operator who never reads stderr ships an insecure cluster. **The fail-closed behavior is the
deliverable** (charter §2).

## Decision

1. **Relocate the default off the data path.** When `--signing-key-file` is not supplied, the default
   signing-key path resolves **outside `dataDir`** (a dedicated key location, NOT inside the directory
   whose contents the derived keys protect). The out-of-box default is therefore not co-located.
2. **Fail-closed on co-location.** If the *resolved* signing-key path (default or explicit) is inside
   `dataDir` (`isInsideDataDir`, comparing normalized absolute paths), the server **REFUSES to start /
   refuses to derive integrity keys**, throwing a clear error that names the offending paths and the
   remedy. Co-location is a fatal misconfiguration, not a warning.
3. **Explicit, off-by-default override** for dev / single-host / test:
   `--allow-insecure-key-colocation` (equivalently `CONFIGD_ALLOW_INSECURE_KEY_COLOCATION=1`). When set,
   the server keeps the legacy behavior (the LOUD warning, then continue). Default OFF ⇒ fail-closed.
4. **Negative test is the proof.** A co-located key with no override ⇒ startup refused with the clear
   error (the gate-7.5 D-1 cell). Positive: key outside `dataDir` ⇒ starts; co-located + override ⇒
   starts with the warning.

## Production key-management expectation (normative)

The cluster signing key is a **secret to be provisioned by the platform, never generated into the data
volume in production**. Acceptable production sources, in preference order:

1. **KMS / HSM-backed** key delivered to the process as a mounted secret or via an init-time fetch
   (e.g. AWS KMS-decrypted secret, Vault, SPIFFE/SVID-mounted material). The private key never lands on
   the same volume as the WAL/snapshot/backups.
2. **Mounted read-only secret** on storage with a **distinct trust boundary** from the data volume
   (e.g. Kubernetes `Secret` mounted RO; Compose `/secrets/signing-key.bin:ro` — the existing prod
   Compose pattern). The artifact-writer principal must NOT have read on the key mount.
3. The relocated local default is a **development convenience only**; production MUST use (1) or (2).

Rotation, distribution, and the cross-node shared-key model are unchanged by this ADR (the key is the
cluster identity; see `setup-secrets.sh`). Rotation/escrow remain an operator runbook concern.

## Consequences

- **+** At-rest integrity (PA-2021) and the audit chain now hold against the T3/A2 storage-writer **by
  default**, closing the honest fence S7 left open (threat-model §5.1) for the default deployment.
- **+** Mis-deployments fail loudly at boot instead of silently shipping defeated integrity.
- **−** Operators who relied on the co-located default must now pass `--signing-key-file` (outside the
  data dir) or the explicit override. The relocated default keeps single-host/dev ergonomic.
- **Residual (unchanged):** an attacker who holds the signing key itself can still forge MACs — that is
  the irreducible "hold the key ⇒ own the integrity" property, now fenced to *key custody* (KMS/HSM/RO
  mount) rather than *filesystem co-location*. Carry-forward to the crypto-review flag.

## Validation
gate-7.5 D-1 cell (co-located → refused) green; S4 durability (`SnapshotCrashRecoveryTest`) + gate-7
PA-2021 re-green after the change (no integrity regression); gate-1 `smoke-multinode` still green under
the relocated default.
