# ADR-0044: Signing-key management - fail-closed on co-location; production key is an external secret

## Status
Accepted. Supersedes the earlier warn-only signing-key mitigation (the server used to warn and continue; it now refuses to start by default).

> **Extended by:** the signing-key custody now composes with a persisted dual-slot **keyring**
> (`NodeKeyring`) that holds independent per-term encryption roots (non-destructive rotation), and with an
> optional **external KMS** custody path - the per-node keyring-custody secret can be sealed in a versioned
> `raft-kms-root` carrier (`KmsSealedRootStore`) via the Vault Transit provider, moving the root of trust
> off-host so at-rest confidentiality no longer fate-shares with a co-located signing key. The co-location
> fail-closed rule below is unchanged. See `docs/design/group-b/07-upgrade-capability-as-built.md` §2 and
> `docs/operations/deployer-must-know.md` §1.

This ADR changes where the signing key lives and how a co-located key is handled. It does not change the cryptographic primitives; the HMAC/audit-log key constructions are still flagged for specialist cryptographic review before a high-assurance production deployment.

## Context

The at-rest integrity layer (ADR-0042) protects the Raft durability artifacts - the snapshot blob, the WAL records, and `DurableRaftState` - with HMAC-SHA-256 under `K_integrity`, and protects the audit-log chain under `K_audit`. Both keys are HKDF-derived from the cluster Ed25519 signing key (`ConfigdServer.deriveRaftIntegrityEnvelope` and `deriveAuditLogKey`, with domain-separated info strings `configd/raft-at-rest-integrity/v2` and `configd/audit-log-integrity/v1`). Their secrecy - and therefore all of the tamper-evidence - depends on the signing key living outside attacker-writable artifact storage.

The convenient default puts the key next to the data it protects: when `--signing-key-file` is not supplied, the key resolves to `dataDir.resolve("signing-key.bin")` so that a restart keeps the signature chain valid. Under that layout a storage- or backup-writer who can tamper with the WAL or snapshot can also read the key and recompute a valid MAC, so the integrity layer is worthless against a full-host or full-storage compromise (adversary A2/T3). A loud warning is not a control: an operator who never reads stderr would ship an insecure cluster. The fail-closed refusal is the deliverable.

## Decision

1. **Fail closed on co-location.** Before deriving any key, `enforceSigningKeyNotColocated` compares the resolved signing-key path against the data directory (`isInsideDataDir`, normalized absolute paths, `startsWith`). If the key is inside `dataDir`, the server throws a `SecurityException` and refuses to start. The error names the offending paths and the remedy. Co-location is a fatal misconfiguration, not a warning.

2. **The default path is inside `dataDir`, so the out-of-box posture is fail-closed.** With no `--signing-key-file` and no opt-out, the default `dataDir/signing-key.bin` is co-located, so the server refuses to start until the operator either points `--signing-key-file` at a path outside the data directory or explicitly opts into the insecure layout. The secure choice is the one that requires no flag.

3. **Explicit, off-by-default opt-out for dev, test, and single-host.** Setting the system property `configd.security.allowColocatedSigningKey=true` (or the environment variable `CONFIGD_ALLOW_COLOCATED_SIGNING_KEY=true`, for CI and docker-compose where `-D` is awkward) downgrades the refusal to a loud stderr banner plus a `SEVERE` log line, and startup continues. There is no CLI flag for this; it is a system property or environment variable only. Default unset means fail-closed.

4. **The negative test is the proof.** A co-located key with no opt-out refuses startup with the clear error. A key outside `dataDir` starts normally; a co-located key with the opt-out set starts with the warning.

## Production key-management expectation (normative)

The cluster signing key is a secret provisioned by the platform, never generated into the data volume in production. Acceptable production sources, in preference order:

1. **KMS- or HSM-backed**, delivered to the process as a mounted secret or an init-time fetch (for example an AWS KMS-decrypted secret, Vault, or SPIFFE/SVID-mounted material). The private key never lands on the same volume as the WAL, snapshot, or backups.
2. **A read-only mounted secret** on storage with a trust boundary distinct from the data volume (for example a Kubernetes `Secret` mounted read-only, or the `/secrets/signing-key.bin:ro` compose pattern). The principal that can write artifacts must not have read access to the key mount.
3. The relocated local file is a development convenience only; production must use (1) or (2).

Rotation, key distribution, and the cross-node shared-key model are unchanged by this decision - the signing key is the cluster identity, and rotation and escrow remain an operator runbook concern.

## Consequences

- At-rest integrity and the audit chain now hold against a storage-writing adversary by default, because a co-located key stops the server rather than silently defeating the MAC.
- Mis-deployments fail loudly at boot instead of shipping defeated integrity in silence.
- Operators who relied on the co-located default must now mount the key on separate storage or set the opt-out. The default keeps single-host and dev use one flag away.
- Residual: an adversary who holds the signing key itself can still forge MACs. That is the irreducible "hold the key, own the integrity" property; this decision fences it to key custody (KMS, HSM, or a read-only mount) rather than filesystem co-location. It stays on the cryptographic-review list.

## Validation

The co-located-key-refused case is covered by a negative test; the durability and at-rest-integrity suites stay green after the change (no integrity regression), and multi-node smoke startup still works under a correctly separated key.
