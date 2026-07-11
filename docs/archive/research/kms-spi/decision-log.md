# KMS provider SPI -- decision log

Design research from 2026-06-28, based on `main` (which already contains the encryption-at-rest and
watches research). No production crypto, no provider modules, no cloud SDK integration, no money spent.
Deliverables are the four docs in this directory plus the compile-checked [`sketch/`](sketch/) plus this
log.

## Methodology

- **Read the parent research first; do not contradict it.** The encryption-at-rest research
  ([`../encryption-at-rest/`](../encryption-at-rest/)) already decided the crypto axis: node-local
  storage-layer AES-256-GCM at the ADR-0042 seam, KMS used only for boot-time unseal of a per-node root
  key (Option D). This SPI is the interface for that one seam; every claim here is checked against that
  research and the code it cites.
- **Prior art before opinion, on the interface axis.** The encryption research already extracted the
  crypto mechanism prior art. This research covers the orthogonal SPI / plugin shape: Vault's
  `go-kms-wrapping` seal `Wrapper`, the Kubernetes KMS plugin gRPC API (v1beta1 + v2), CockroachDB's
  store-key model (a contrast, config-driven, not a plugin SPI), and the cloud-KMS Java SDK shapes, plus
  the JVM key-material-handling literature. Load-bearing claims were verified. Captured in
  [`prior-art.md`](prior-art.md).
- **Verify the load-bearing facts empirically, not from memory.** The central key-material claim, that the
  JCA standard symmetric type can't be wiped, was measured on this repo's runtime (Corretto JDK 25): a
  probe showed `SecretKeySpec.destroy()` throws `DestroyFailedException`, leaves `isDestroyed()` false, and
  `getEncoded()` still returns the key (JDK-8160206). The same probe confirmed `javax.crypto.KDF` (JEP 478)
  is finalized (non-preview) on JDK 25. See [`key-material-types.md`](key-material-types.md) §1.1 / §2.8.
- **De-risk the design by compiling it.** The interface, the value types, the default provider, and the
  discovery seam were written as a compile-checked signature sketch ([`sketch/`](sketch/)) with a
  behavioral smoke test (13/13 contract checks pass on JDK 25). The wipe lifecycle, redaction, defensive
  copies, and fail-loud selection are demonstrated to actually work; only the crypto is stubbed.
- **Hard rules honored:** design plus recommendation only (no working crypto, no cloud SDK integration, no
  provider built); not raw `byte[]` for key material; the lifecycle/availability contract is encoded in
  the interface, not just prose; the default is the zero-dependency HKDF-from-signing-key provider and the
  core pulls in no cloud SDK; no custom crypto (the SPI wraps established KMS APIs plus standard JCA
  types); consistent with the encryption-at-rest research; docs-only under
  `docs/archive/research/kms-spi/`.

## Findings / decisions recorded (evidence, not directives)

- **D-KMS-1 -- The SPI's job is custody plus unseal of a per-node root key, and only that.** Two
  operations (`wrap` for setup/rotation, `unwrap` for boot) plus key-identity/versioning. There is
  deliberately no per-record `encrypt`/`decrypt`, its absence is the structural guarantee that a KMS
  round-trip can never reach the write/replay path (the correlated-dependency liveness trap, encryption
  research §5). The data-plane cipher is the core's job on the locally-derived DEK. Matches every
  prior-art system (Vault `Wrapper`, K8s KMS-v2, cloud envelope), all operate on a small key blob, never
  the bulk data.
- **D-KMS-2 -- `byte[]` is the wrong type for live key material; recommend typed carriers.**
  Empirically (JDK 25) `SecretKeySpec`/`Destroyable` cannot wipe (above). Recommend three distinct types:
  `RootKey` (live; `AutoCloseable`+`Destroyable` with a real `Arrays.fill` wipe; redacted `toString`;
  scoped raw access; bridges transiently to a JCA `SecretKey`), `WrappedKey` (opaque sealed ciphertext plus
  `KeyId` plus AAD; persistable/loggable, the type distinction from `RootKey` is the safety property), and
  `KeyId` (non-secret identity plus keyring term). Off-heap `MemorySegment` and Tink's full token framework
  were evaluated and deferred/declined as over-engineering for a boot-only key (the principle of scoped
  access is adopted; the dependency is not). Validated in [`sketch/`](sketch/).
- **D-KMS-3 -- The interface enforces the availability discipline (R1-R5).** (R1) KMS only at
  boot/rotation, never per-op, no method exists to put it on the hot path. (R2) the core unseals once,
  caches the `RootKey`, and drops the provider, so "KMS off the hot path" is structural. (R3)
  configured-but-unreachable-at-boot means fail closed (refuse to start), never fall back to no-encryption
  or another provider, the same posture `NettyTransport.select()` already applies to a forced transport,
  and ADR-0042 applies to integrity. (R4) distinguish boot-unreachable (fail closed) from a running-node
  blip (continue on cached key, never re-invoked). (R5) auto-unseal only, never interactive Shamir on the
  availability path. `KmsUnavailableException` is checked so the fail-closed decision can't be silently
  skipped.
- **D-KMS-4 -- The default is `LocalDerivedKmsProvider` (zero dependency).** Derives the root key from
  the already-loaded cluster signing key via HKDF, the encryption research's Option B-minimal, a third
  derived key beside `K_integrity`/`K_audit` (`ConfigdServer.java:1173,1262`). No new file, no external
  call, no new boot failure mode, so `unwrap` never fails closed, it cannot threaten consensus liveness.
  Fate-sharing with the signing key and the absence of independent rotation are documented as known
  properties; it inherits the signing-key co-location guard.
- **D-KMS-5 -- Module layering keeps the core cloud-SDK-free.** The SPI plus value types plus default
  plus discovery are tiny and dependency-free (a dedicated `configd-kms-spi` module recommended; folding
  into `configd-common` is the pragmatic alternative). Each cloud provider,
  `configd-kms-{aws,azure,gcp,vault,pkcs11}`, is a separate optional Maven artifact depending on the SPI
  plus its own SDK, on the runtime classpath only when used. The AWS sketch
  ([`kms-provider-spi.md`](kms-provider-spi.md) §6) shows `GenerateDataKey`/`Decrypt`/`Encrypt`
  implementing the contract end-to-end and satisfying R1-R5 (one `Decrypt` at boot, cached after, client
  closed; multi-Region CMK plus backoff to de-risk the boot dependency).
- **D-KMS-6 -- Discovery: hybrid name-selection plus `ServiceLoader`, mirroring
  `NettyTransport.select()`.** `configd.raft.encryption.kms.provider = local | aws-kms | ...` (default
  `local`) selects by name (the codebase convention); optional providers are discovered via
  `ServiceLoader<KmsProviderFactory>` so the core never compile-references a cloud module. Fail-loud,
  never silent downgrade. Note: there was no existing `ServiceLoader` use in the codebase at this point, so
  this adds a small, idiomatic-adjacent pattern; a purely explicit registry is the simpler alternative
  (operator decision).
- **D-KMS-7 -- `javax.crypto.KDF` (JEP 478) is finalized on JDK 25.** Verified: `KDF.getInstance(
  "HKDF-SHA256")` compiles/runs without `--enable-preview` (SunJCE). Flagged for the eventual
  derivation/encryption-layer build, the `local` provider and per-segment DEK derivation could use the
  JDK-native HKDF instead of the bespoke `io.configd.common.Hkdf`. Not a type decision; a build note.

## What was not done (honest scope)

- **No working crypto.** No HKDF derivation, no AES, no cloud `Encrypt`/`Decrypt` is implemented. The
  `LocalDerivedKmsProvider` derivation point throws `UnsupportedOperationException`; the AWS sketch is
  design-level (its SDK signatures are stated from the SDK docs, not compiled against the AWS SDK at this
  point, confirm when built).
- **No provider modules, no cloud SDK dependency added.** Nothing was added to any `pom.xml`; the sketch
  is a standalone artifact, not a Maven module.
- **No money, no measurement.** No EC2, no KMS account. (The encryption research already flags the EC2
  encryption-on/off arm; the KMS boot-unseal latency would be measured then.)
- **No ADR, no change to any shipped doc** beyond adding this research directory.

## Handoff -- the SPI is designed; what the operator must decide

To be designed-in when the encryption work (Option D) is built, so providers slot in with no core change:

1. **The key-material types** -- ratify `RootKey`/`WrappedKey`/`KeyId` (D-KMS-2), including the
   `toSecretKey` JCA-bridge caveat (the one un-wipeable residual) and whether to adopt off-heap (deferred)
   or fuller scoped-access for the live key.
2. **The discovery mechanism** -- hybrid `ServiceLoader` plus name (recommended, D-KMS-6) vs. an explicit
   registry.
3. **The SPI module placement** -- dedicated `configd-kms-spi` (recommended) vs. fold into
   `configd-common`.
4. **Which providers to build first** -- `local` ships with B-minimal; the first cloud module (likely
   `aws-kms`) is built when a named off-host-custody / compliance / managed-rotation requirement appears
   (the same trigger the encryption research sets for graduating B to D).

When a provider is built it must: keep KMS off the per-op path (one boot `unwrap`, cache, drop the
provider); fail closed at boot, never fall back; bind node identity into the KMS encryption-context (AAD);
retain old keyring terms until a re-encryption sweep has rewritten every WAL entry, snapshot, and backup;
and reuse the typed key material so live keys are wiped and never logged. The construction sits beside
ADR-0042 and is flagged for the same specialist crypto review. (This has since been built; see
`docs/architecture/architecture.md`.)

## Pointers

- [`prior-art.md`](prior-art.md) -- Vault `Wrapper` / K8s KMS plugin / Cockroach store-key / cloud-KMS
  SDKs; interface shapes plus key-material handling, with citations.
- [`key-material-types.md`](key-material-types.md) -- the `byte[]`-is-wrong analysis plus the recommended
  types.
- [`kms-provider-spi.md`](kms-provider-spi.md) -- the contract, default provider, AWS sketch, layering,
  discovery.
- [`sketch/`](sketch/) -- the compile-checked signature artifact (plus smoke test).
- Parent: [`../encryption-at-rest/`](../encryption-at-rest/) (this SPI is its key-management interface;
  Option D is where it is built). Precedent:
  [`../../adr/adr-0042-snapshot-wal-raftstate-integrity.md`](../../../adr/adr-0042-snapshot-wal-raftstate-integrity.md).
- Seam: `ConfigdServer.deriveRaftIntegrityEnvelope`/`deriveAuditLogKey` (`ConfigdServer.java:1173,1262`);
  selection precedent `NettyTransport.select()` (`NettyTransport.java:82,128`).
