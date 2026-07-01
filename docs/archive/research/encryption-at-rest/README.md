# Encryption at Rest — Research (RR-098)

Evidence base for the v2 encryption-at-rest decision. **Research only — no code, no crypto implemented.**
Configd today provides at-rest **integrity** (HMAC, ADR-0042) but **not confidentiality**; encryption at
rest is a deferred v2 item (RR-098, accept-as-v2 decided by the operator on 2026-06-27, register §11-B D-2).
This directory answers *how to do it properly* so that decision and the eventual build are evidence-based.

## Read in this order

1. **[`prior-art.md`](prior-art.md)** — how Vault, etcd/Kubernetes, cloud KMS, and database TDE actually
   implement encryption at rest, at the mechanism level (barrier/envelope/unseal/rotation/threat-model),
   with primary-source citations.
2. **[`configd-analysis.md`](configd-analysis.md)** — the crux. Plaintext-surface inventory; the
   consensus WAL/snapshot **storage-layer vs end-to-end** analysis (verified against code); the **edge**
   key-distribution fork; **key-availability vs failover**; the **write-path performance** estimate; the
   `secure/` / per-namespace option.
3. **[`recommendation.md`](recommendation.md)** — four ranked options (A none → D Vault-style) with a
   threat-defense matrix and cost/fit table, and a clear **v1/v2 recommendation for the operator**.
4. **[`decision-log.md`](decision-log.md)** — methodology, findings, honest scope, and handoff.

## The recommendation in one line

v1 correctly stays as-is (integrity-only + "don't store secrets"); the recommended **v2 first step is
node-local, storage-layer AES-256-GCM encryption of the WAL/snapshot at the existing ADR-0042 seam, keyed by
HKDF from the cluster signing key** — no wire change, no cluster-wide key distribution, ~no write-path cost;
graduating to a KMS-auto-unsealed keyring when off-host custody or managed rotation is required.

**Precedent:** [`../../decisions/adr-0042-snapshot-wal-raftstate-integrity.md`](../../decisions/adr-0042-snapshot-wal-raftstate-integrity.md)
— this research is the *confidentiality* sibling of that *integrity* work.
