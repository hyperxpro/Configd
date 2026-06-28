# KMS-SPI signature sketch (compile-checked design artifact)

A **design artifact**, not production code and not a Maven module. It is the normative reference for the
signatures discussed in [`../kms-provider-spi.md`](../kms-provider-spi.md) and
[`../key-material-types.md`](../key-material-types.md). It contains **no working crypto** — the one
derivation point (`LocalDerivedKmsProvider.deriveRootKey`) deliberately throws
`UnsupportedOperationException`; everything else (the wipe lifecycle, redaction, defensive copies, fail-loud
selection) is real, because it is memory hygiene and control flow, not cryptography.

## Contents (`io/configd/kms/`)

| File | Role |
|---|---|
| `KeyId.java` | non-secret KEK identity + keyring version (record, loggable) |
| `WrappedKey.java` | the **sealed** root key — ciphertext + `KeyId` + AAD context (record; persistable/loggable, redacted) |
| `RootKey.java` | the **live** root key handle — `AutoCloseable` + `Destroyable`, real wipe, redacted `toString`, scoped raw access |
| `KmsProvider.java` | the SPI — `type`/`currentKeyId`/`generateRootKey`/`wrap`/`unwrap`/`healthCheck` (no per-op crypto) |
| `KmsUnavailableException.java` | checked — forces the conscious fail-closed decision at the boot seam |
| `KmsConfig.java` | read-only `configd.raft.encryption.kms.*` config access for factories |
| `KmsProviderFactory.java` | `ServiceLoader` SPI by which an optional module advertises its provider |
| `KmsProviders.java` | name-based selection (default `local`) + `ServiceLoader` discovery; **fail-loud, never silent downgrade** |
| `LocalDerivedKmsProvider.java` | the zero-dependency default (HKDF-from-signing-key); crypto stubbed |
| `../SketchSmokeTest.java` | behavioural validation of the lifecycle + redaction + fail-loud selection |

## Compile + run the design-contract checks (Corretto JDK 25)

```sh
cd docs/research/kms-spi/sketch
javac -d out io/configd/kms/*.java SketchSmokeTest.java
java  -cp out SketchSmokeTest
# → "All 13 design-contract checks passed."
rm -rf out
```

The checks demonstrate: `RootKey` wipe-on-close actually zeroes the backing array; use-after-wipe throws;
`RootKey`/`WrappedKey` `toString()` redact the bytes; `WrappedKey` copies defensively and compares
structurally; provider selection defaults to `local` and **fails loud** (never silently falls back) when a
named provider is absent.
