# KMS provider SPI -- design research

Design plus recommendation for a pluggable key-custody interface (`KmsProvider` SPI) so Configd's core
stays dependency-light while AWS / Azure / GCP / Vault / HSM are separate optional modules, extensible to
any KMS without forking the core. Design only at the time -- no production crypto, no provider modules
built yet.

This is the interface for the key-management axis of the encryption-at-rest research: its
[`../encryption-at-rest/recommendation.md`](../encryption-at-rest/recommendation.md) recommends node-local
storage-layer AES-256-GCM (Option B/D) with the data key derived locally and KMS used only for boot-time
unseal of a per-node root key, never per-operation. This SPI is exactly that boot-unseal seam, designed so
providers slot in with no core change when the encryption work is built. (It has since been built -- see
`docs/architecture/architecture.md` and ADR-0042 for the shipped KMS-SPI and encryption-at-rest design.)

## Read in this order

1. **[`prior-art.md`](prior-art.md)** -- how Vault (the `go-kms-wrapping` seal `Wrapper`), Kubernetes (the
   KMS plugin gRPC API, v1beta1 + v2), CockroachDB (the store-key model, a contrast, not a plugin SPI), and
   the cloud-KMS SDKs expose pluggable key custody; the SPI patterns and the key-material handling. (The
   crypto mechanism prior art -- cipher/envelope/unseal/rotation -- is in the encryption research's
   [`../encryption-at-rest/prior-art.md`](../encryption-at-rest/prior-art.md); this one is about interface
   shape.)
2. **[`key-material-types.md`](key-material-types.md)** -- the core question. Why raw `byte[]` is the wrong
   type for live key material (empirically demonstrated on JDK 25: `SecretKeySpec.destroy()` throws and
   never wipes -- JDK-8160206), the evaluation of `SecretKey`/`Destroyable`/opaque-`WrappedKey`/
   `AutoCloseable`/sealed-records/off-heap/Tink-token-access, and the recommended concrete types.
3. **[`kms-provider-spi.md`](kms-provider-spi.md)** -- the interface contract: method signatures
   (wrap/unwrap/keyId, no per-op crypto), the lifecycle + availability + fail-closed contract as
   implementer requirements, the default `LocalDerivedKmsProvider`, one cloud (AWS) implementation
   sketch, the module layering, and provider discovery.
4. **[`decision-log.md`](decision-log.md)** -- methodology, decisions (evidence, not directives), honest
   scope, and the handoff.
5. **[`sketch/`](sketch/)** -- a compile-checked Java signature sketch of the SPI + value types + default
   provider + discovery (no working crypto). Compiles and its 13 design-contract checks pass on Corretto
   JDK 25 -- see [`sketch/README.md`](sketch/README.md).

## The recommendation in one line

A small SPI whose only job is wrap/unwrap of a per-node root key (no per-record crypto, so KMS can never
reach the hot path); typed key material (`RootKey` live+wiped, `WrappedKey` sealed, `KeyId` non-secret)
instead of `byte[]`; a lifecycle/availability/fail-closed contract that the interface enforces; a
zero-dependency default (`LocalDerivedKmsProvider`, HKDF from the signing key); and cloud providers as
separate optional modules discovered by name plus `ServiceLoader`, so the core pulls in no cloud SDK. This
is the design that shipped.

**Precedent / siblings:** [`../encryption-at-rest/`](../encryption-at-rest/) (the parent research, this SPI
is its key-management interface),
[`../../adr/adr-0042-snapshot-wal-raftstate-integrity.md`](../../../adr/adr-0042-snapshot-wal-raftstate-integrity.md)
(the at-rest envelope seam this composes with), and `ConfigdServer.deriveRaftIntegrityEnvelope` /
`deriveAuditLogKey` (the HKDF-from-signing-key derivations the default generalizes).
