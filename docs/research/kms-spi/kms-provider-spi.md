# The `KmsProvider` SPI — interface contract, default provider, AWS sketch, layering

> **Session:** KMS-SPI design research, 2026-06-28. **Status:** design + recommendation only — no
> production crypto, no provider modules built. Reads on from the encryption-at-rest research
> ([`../encryption-at-rest/recommendation.md`](../encryption-at-rest/recommendation.md), Option D) and from
> [`key-material-types.md`](key-material-types.md) (the §3 type analysis) and [`prior-art.md`](prior-art.md).
>
> This document specifies the **interface contract**: the method signatures, the lifecycle/availability/
> fail-closed requirements that any implementer MUST honour, the zero-dependency default provider, one cloud
> module (AWS) sketch proving the contract is implementable, the module layering, and provider discovery. A
> **compile-checked signature sketch** lives in [`sketch/`](sketch/) and is the normative reference for the
> signatures quoted here (it compiles and its design-contract checks pass on Corretto JDK 25).

---

## 1. The interface's narrow job

The encryption-at-rest research keeps **KMS off the hot path** by design (Option B/D, node-local
storage-layer AES-256-GCM at the ADR-0042 seam, keyed by per-segment DEKs derived **locally**). KMS does
exactly one thing in that design: **custody + unseal of a per-node root key.** At boot the node unwraps the
root key once; thereafter every per-segment DEK is an HKDF derivation done locally, with no further KMS
interaction ([`../encryption-at-rest/configd-analysis.md`](../encryption-at-rest/configd-analysis.md) §5).

So the SPI is **small and single-purpose**, and is deliberately **NOT a general crypto API**:

- It exposes **wrap** (seal a root key — setup/rotation) and **unwrap** (unseal it — boot), plus
  **key-identity/versioning** for rotation.
- It **MUST NOT** expose per-record `encrypt`/`decrypt`. That omission is load-bearing: a per-record method
  is exactly how an implementer would (accidentally or lazily) put a KMS round-trip on the write/replay path
  — the correlated-dependency liveness trap §3 dissects. The data-plane cipher is the **core's** job, run on
  the locally-derived DEK; the provider never sees a config record.

This mirrors the prior art precisely: Vault's seal `Wrapper`, the Kubernetes KMS-v2 plugin, and every
cloud-KMS envelope flow all operate on **a small key blob (a root key / a DEK), never the bulk data**
([`prior-art.md`](prior-art.md) §1–§3).

The provider sits at one tier of the standard two-tier envelope:

```
   KEK  (master key, in the KMS/HSM, or — for `local` — the cluster signing key)
    │   the KmsProvider's domain: wrap / unwrap ONE per-node root key
    ▼
  RootKey  (per-node, unsealed ONCE at boot, cached in RAM for the node's lifetime)
    │   the encryption layer's domain (out of this SPI's scope): HKDF locally, no KMS
    ▼
   DEKs  (per-segment data keys → AES-256-GCM over WAL records / snapshot at the ADR-0042 seam)
```

---

## 2. The interface contract

The full, compile-checked source is in [`sketch/io/configd/kms/KmsProvider.java`](sketch/io/configd/kms/KmsProvider.java).
The contract in signatures:

```java
public interface KmsProvider extends AutoCloseable {

    String      type();                    // discovery discriminator: "local", "aws-kms", ...
    KeyId       currentKeyId();            // active KEK id + keyring term (observability + rotation)

    Provisioned generateRootKey()              throws KmsUnavailableException;   // setup (once)
    WrappedKey  wrap(RootKey rootKey)          throws KmsUnavailableException;   // rotation (rare)
    RootKey     unwrap(WrappedKey wrapped)     throws KmsUnavailableException;   // BOOT (once) — the only runtime call
    default void healthCheck()                 throws KmsUnavailableException {} // optional pre-flight

    default void close() {}                 // release the KMS client; NOT the root key

    record Provisioned(RootKey rootKey, WrappedKey wrapped) {}
}
```

Three value types carry the key material — designed in [`key-material-types.md`](key-material-types.md) and
summarised in §4 below:

| Type | Role | Persistable? | Loggable? | Wiped? |
|---|---|---|---|---|
| **`KeyId`** | non-secret identity of the KEK + keyring term | yes | **yes** | n/a (non-secret) |
| **`WrappedKey`** | the **sealed** root key (ciphertext + `KeyId` + AAD context) | **yes** | yes (redacted) | n/a (ciphertext) |
| **`RootKey`** | the **live** root key handle | **never** | **never** (redacted) | **yes** (`AutoCloseable`+`Destroyable`) |

### What each method does, per provider

| Method | `local` (default) | a KMS provider (e.g. AWS) |
|---|---|---|
| `generateRootKey()` | derive `RootKey = HKDF(signing-key, salt=keyId, info="…/kek/v1")`; `WrappedKey` = a non-secret derivation descriptor (empty ciphertext) | `GenerateDataKey(CMK, AES_256)` → plaintext DEK becomes `RootKey`, `CiphertextBlob` becomes `WrappedKey.ciphertext` |
| `unwrap(wrapped)` | **re-derive** from the signing key (no external call → never fails closed) | one `Decrypt(CiphertextBlob)` → plaintext → `RootKey` |
| `wrap(rootKey)` | return the derivation descriptor (no sealing) | `Encrypt(CMK, rootKey)` → new `CiphertextBlob` (rewrap under a rotated CMK) |
| `currentKeyId()` | `local:<signing-keyId>#1` | `aws-kms:<cmk-arn>#<term>` |

`WrappedKey` is **opaque to the core** — only the owning provider interprets its bytes. For `local` they are
a re-derivation descriptor; for a KMS they are ciphertext. The core just persists the `WrappedKey` and hands
it back at the next boot.

---

## 3. The lifecycle + availability + fail-closed contract (the heart)

The encryption research names the trap precisely: **if a KMS call is needed and KMS is down, a restarting
node cannot rejoin → quorum shrinks during an incident, and the dependency is correlated across nodes**
([`../encryption-at-rest/configd-analysis.md`](../encryption-at-rest/configd-analysis.md) §5;
[`prior-art.md`](prior-art.md) §3.3). The interface **enforces** the discipline rather than merely
documenting it. These are **REQUIREMENTS on every implementer** (encoded in the
[`KmsProvider`](sketch/io/configd/kms/KmsProvider.java) Javadoc and the type signatures):

### R1 — KMS is called ONLY at boot (unwrap) and setup/rotation (wrap); NEVER per-operation

There is **no per-record method** to put a KMS call on. The only runtime call is the one-time boot
`unwrap`. A provider **MUST NOT** perform network/KMS I/O outside
`generateRootKey`/`wrap`/`unwrap`/`healthCheck`. Per-record crypto is the core's job, on the locally-derived
DEK — the provider never sees a config record.

### R2 — a running node holds the root key for its lifetime; the provider is then dropped

The core calls `unwrap` exactly once, caches the `RootKey`, and **drops its reference to the provider**
(and `close()`s the KMS client). After unseal there is **no live provider handle on which a per-op KMS call
could be made** — "KMS off the hot path" becomes **structural**, not merely a documented intention. A KMS
outage *after* boot cannot reach any code path: the node continues on its cached `RootKey`.

> **Recommended core wiring (not built this session):**
> ```java
> RootKey root;
> try (KmsProvider kms = KmsProviders.select(cfg, signingKeyIkm, localKeyId)) {
>     kms.healthCheck();                       // optional pre-flight
>     root = kms.unwrap(loadPersistedWrappedKey());   // THE one boot call
> }   // KMS client closed; `kms` out of scope — no per-op handle survives
> installRootKeyForNodeLifetime(root);         // derive per-segment DEKs locally from here on
> ```

### R3 — fail-closed at boot; **never** fall back

If the provider is **configured but `unwrap` (or `healthCheck`) throws `KmsUnavailableException`** at boot,
the node **refuses to start** — mirroring ADR-0042's sticky fail-closed posture and the D-1 refuse-to-start
guard (`ConfigdServer.enforceSigningKeyNotColocated`, `ConfigdServer.java:1214`). It MUST NOT:
- fall back to **no encryption** (that silently voids the at-rest guarantee), nor
- silently fall back to a **different provider** (e.g. `local`).

This is the same rule the codebase already applies to a forced-but-unavailable transport tier
(`NettyTransport.select()`, `NettyTransport.java:128` — *"Refusing to silently downgrade — a silent
downgrade is how a 'we ran on io_uring/epoll' claim becomes fiction"*). The KMS analogue: **a silent
downgrade is how a "data is KMS-protected" claim becomes fiction.** The selection seam reproduces this
verbatim (see §8).

`KmsUnavailableException` is **checked on purpose**: the §3 discipline requires a *conscious* decision at
the boot seam, so the type system forces the caller to handle it (refuse-to-start) rather than ignore it.

### R4 — distinguish "configured + unreachable at boot" from "already running, KMS blips"

- **configured + KMS unreachable at boot** → fail closed (R3): the node refuses to start. The unseal cannot
  proceed, and a node that cannot decrypt its own WAL/snapshot must not pretend it can.
- **already running, KMS blips** → continue on the cached `RootKey` (R2): the provider is not re-invoked, so
  the blip is invisible to the data path. This is the property that keeps a KMS outage from becoming a
  cluster-wide write-availability outage.

### R5 — no interactive unseal on the availability path

Auto-unseal only. A provider **MUST NOT** block on human input (no Shamir-style threshold prompt) to unseal,
because the config store is on the read/availability path — unlike a secrets manager, it cannot afford to
sit sealed waiting for operators ([`prior-art.md`](prior-art.md) §1.3, the Vault seal-vs-config-store
tension). The built-in `local` provider sidesteps this entirely (nothing to unseal); a KMS provider
satisfies it because cloud-KMS auto-unseal is a single non-interactive `Decrypt`.

### Why these are achievable for Configd specifically

The write path is already **`fsync`/election-bound, not KMS-bound**
([`../encryption-at-rest/configd-analysis.md`](../encryption-at-rest/configd-analysis.md) §6), and the
durable artifacts a restarting node must decrypt are **node-local** (the replication wire carries plaintext
over mTLS — verified, §2 of that doc). So one boot-time unwrap + a cached root key is sufficient; no design
pressure ever pushes the KMS toward the per-op path. The interface simply forbids re-introducing it.

---

## 4. The key-material types in the signatures (summary; full analysis in `key-material-types.md`)

Raw `byte[]` is the wrong type for live key material (not wipeable, untyped, no controlled `toString`, no
metadata — [`key-material-types.md`](key-material-types.md) §1, empirically demonstrated). The SPI therefore
uses three typed carriers:

- **`RootKey`** — the live key handle: `AutoCloseable` + `Destroyable`, owns and **actually zeroes** its
  `byte[]` on `close()`, has a **redacted `toString()`**, and guards use-after-wipe. It is deliberately
  **not** a `javax.crypto.SecretKeySpec`, because — verified on Corretto JDK 25 — `SecretKeySpec.destroy()`
  *throws* and leaves the key readable (JDK-8160206). `try (RootKey k = provider.unwrap(w)) { … }` makes
  "wipe after use" structural. A `toSecretKey(alg)` bridge exists for the JCA `Cipher`/`KDF` consumer, with
  the honest caveat that the returned `SecretKeySpec` can't be wiped, so it must be transient.
- **`WrappedKey`** — the opaque sealed carrier: ciphertext + `KeyId` + AAD context. The **type distinction
  from `RootKey` is itself the safety property** — the compiler stops you handing a live key to a persist
  call, or logging one. `byte[]` is fine *here* because it is ciphertext, not live material; it still gets a
  redacted `toString()` (length only) and defensive copies.
- **`KeyId`** — the non-secret, loggable identity (provider type + KEK reference + keyring version) that
  makes each `WrappedKey` self-describing, so any node selects the right key on read with zero coordination
  (Vault term / Cockroach `key_id` / K8s `key_id`).

These choices and the alternatives weighed (Tink's token-gated access, off-heap `MemorySegment`, sealed
hierarchies, `javax.crypto.KDF`) are in [`key-material-types.md`](key-material-types.md).

---

## 5. The default provider — `LocalDerivedKmsProvider` (zero dependency)

The in-core default (`sketch/io/configd/kms/LocalDerivedKmsProvider.java`) **derives the root key from the
already-loaded cluster signing key via HKDF** — exactly the encryption research's Option **B-minimal**:

```
K_enc(root) = HKDF(IKM = signing-key encoding, salt = keyId bytes, info = "configd/raft-at-rest-encryption/kek/v1", len = 32)
```

This is a **third derived key beside the existing two** that already use this precise construction:
`K_integrity` (`ConfigdServer.deriveRaftIntegrityEnvelope`, `ConfigdServer.java:1173`) and `K_audit`
(`deriveAuditLogKey`, `ConfigdServer.java:1262`). So the default adds **no new file, no new
distribution channel, no external call, and no new boot failure mode** — the key is available the instant
the signing key is read.

- `generateRootKey()` / `unwrap()` perform the (deterministic) derivation; `unwrap` **never throws
  `KmsUnavailableException`** because there is nothing external to be unavailable. This is precisely why the
  default **cannot threaten consensus liveness** — it satisfies R1–R5 trivially.
- `wrap()` returns a non-secret **derivation descriptor** (`WrappedKey` with empty ciphertext); nothing is
  sealed because reconstruction is by re-derivation.

**Known property — fate-sharing (documented, not a defect):** the encryption key shares fate with the
signing key (a signing-key leak makes at-rest data decryptable). The marginal loss is bounded — a
signing-key leak already lets the attacker forge committed state — and the trade buys zero new dependencies.
The default inherits the **D-1 co-location guard** (the signing key must live outside the data directory,
`ConfigdServer.java:1214`); there is **no independent encryption-key rotation** (rotating it means rotating
the signing key). Graduate to a KMS provider when off-host custody or managed rotation is required.

---

## 6. One cloud module, end-to-end — `configd-kms-aws` (sketch, NOT built)

Proves the contract is implementable and that it satisfies §3. The module depends on the SPI + the AWS KMS
SDK only; the **core pulls in no AWS SDK** (§7). *Signatures per AWS SDK for Java v2; this is a design
sketch, not compiled against the SDK this session — confirm against `prior-art.md` §3 / the SDK Javadoc when
built.*

```java
// module configd-kms-aws  (depends on: configd-kms-spi, software.amazon.awssdk:kms)
public final class AwsKmsProvider implements KmsProvider {

    private final KmsClient kms;                 // software.amazon.awssdk.services.kms
    private final String cmkArn;                 // the KEK — never leaves AWS in plaintext
    private final Map<String,String> encCtx;     // AAD: e.g. {"node":nodeId,"purpose":"raft-at-rest-kek"}

    public String type() { return "aws-kms"; }

    public Provisioned generateRootKey() throws KmsUnavailableException {
        try {
            GenerateDataKeyResponse r = kms.generateDataKey(b -> b
                .keyId(cmkArn).keySpec(DataKeySpec.AES_256).encryptionContext(encCtx));
            byte[] plaintext = r.plaintext().asByteArray();          // the root key, in the clear
            RootKey root = new RootKey(plaintext, keyIdOf(r.keyId()));// RootKey OWNS + wipes it
            WrappedKey wrapped = new WrappedKey(root.keyId(),
                r.ciphertextBlob().asByteArray(), encCtx);           // persist this; safe at rest
            // (zero the SdkBytes-backed temporaries; SdkBytes copies are not auto-wiped)
            return new Provisioned(root, wrapped);
        } catch (KmsException e) { throw new KmsUnavailableException("KMS GenerateDataKey failed", e); }
    }

    public RootKey unwrap(WrappedKey w) throws KmsUnavailableException {   // THE one boot call
        try {
            DecryptResponse r = kms.decrypt(b -> b
                .ciphertextBlob(SdkBytes.fromByteArray(w.ciphertext()))
                .encryptionContext(w.context()));                    // AAD must match → relocation fails
            return new RootKey(r.plaintext().asByteArray(), w.keyId());
        } catch (KmsException e) { throw new KmsUnavailableException("KMS Decrypt failed (fail closed)", e); }
    }

    public WrappedKey wrap(RootKey root) throws KmsUnavailableException { /* kms.encrypt(...) → new blob */ }
    public KeyId currentKeyId() { /* cmkArn + rotation term, cf. K8s Status.key_id */ }
    public void close() { kms.close(); }                             // release the SDK client after boot
}
```

**How it satisfies the §3 availability contract:**

- **R1/R2:** `unwrap` is the only runtime KMS call (one `Decrypt` at boot). The core then caches the
  `RootKey` and `close()`s the client → KMS is off the per-op path *structurally*. A KMS outage after boot
  is invisible.
- **R3:** any `Decrypt` failure (KMS down, CMK disabled/deleted/denied, throttled) surfaces as
  `KmsUnavailableException` → the node fails closed. AWS's own EBS precedent — *"the attachment fails,
  because Amazon EBS cannot use the KMS key to decrypt the volume's encrypted data key"*
  ([`prior-art.md`](prior-art.md) §3.3) — is the same posture: no key, no start.
- **R4:** boot-time unreachable → refuse; running-node blip → never re-invoked. De-risk the boot dependency
  with a **multi-Region CMK + exponential backoff** (the documented mitigation,
  [`prior-art.md`](prior-art.md) §3.3) so a single-region KMS endpoint is not a correlated boot SPOF.
- **R5:** auto-unseal — the `Decrypt` is non-interactive.
- **AAD binding:** the `encryptionContext` binds node identity into the seal, so node A's `WrappedKey`
  cannot be unwrapped as node B (a relocated/copied blob fails to decrypt — the K8s/AWS encryption-context
  property).

The other modules are analogous one-method-each implementations: `configd-kms-azure`
(`CryptographyClient.wrapKey/unwrapKey`), `configd-kms-gcp` (`KeyManagementServiceClient.encrypt/decrypt` —
no `GenerateDataKey`, generate the DEK locally then wrap), `configd-kms-vault` (`transit/encrypt` ·
`transit/decrypt` · `transit/datakey`), `configd-kms-pkcs11` (a JCA `SunPKCS11` HSM — no extra dependency).
See [`prior-art.md`](prior-art.md) §3–§4 for the verified SDK shapes.

---

## 7. Module layering — the core pulls in NO cloud SDK

```
configd-kms-spi        interface + KeyId/WrappedKey/RootKey + LocalDerivedKmsProvider + KmsProviders
   (zero new deps)     ← the only thing configd-server/-common compile-depends on
        ▲
        ├── configd-kms-aws      + software.amazon.awssdk:kms
        ├── configd-kms-azure    + com.azure:azure-security-keyvault-keys
        ├── configd-kms-gcp      + com.google.cloud:google-cloud-kms
        ├── configd-kms-vault    + a Vault HTTP client (or raw java.net.http)
        └── configd-kms-pkcs11   + (none — JDK-built-in SunPKCS11)
```

- The **SPI + default + value types** are tiny and dependency-free, so they can live in a new
  **`configd-kms-spi`** module (cleanest — an explicit, versioned plugin contract) **or** be folded into the
  existing **`configd-common`** (where `Hkdf`/`IntegrityEnvelope` already live — fewer modules). *Operator
  decision; I lean to the dedicated module for a clean contract, `configd-common` is the pragmatic
  alternative.*
- Each cloud module is a **separate Maven artifact** that depends on the SPI + its own SDK, pinned by that
  SDK's BOM in the module's own `dependencyManagement`. The artifact is added to the **server's runtime
  classpath only when used**; `configd-server` never compile-depends on it (discovery is by `ServiceLoader`,
  §8). The core therefore never inherits a cloud SDK's transitive footprint or CVE surface — the same
  "out-of-process / out-of-tree plugin keeps the core dependency-free" lesson as the Kubernetes KMS plugin
  ([`prior-art.md`](prior-art.md) §2).

---

## 8. Provider discovery / selection — mirror `NettyTransport.select()`

The codebase selects pluggable implementations by a **system-property name with a fail-loud override** and
has **no existing `ServiceLoader` usage**. The recommendation is a **hybrid** that stays idiomatic while
enabling out-of-tree plugins (`sketch/io/configd/kms/KmsProviders.java`):

- **Selection by name** (the codebase convention, cf. `configd.netty.transport`):
  `configd.raft.encryption.kms.provider = local | aws-kms | azure-keyvault | gcp-kms | vault-transit | pkcs11`,
  **default `local`**.
- **Discovery via `ServiceLoader<KmsProviderFactory>`**: an optional module ships
  `META-INF/services/io.configd.kms.KmsProviderFactory`; its mere presence on the classpath registers its
  `type()`. The core lists discovered factories and instantiates the **one** the config names — so the core
  never compile-references a cloud module, yet third parties can add a `configd-kms-foo` without forking the
  core. (This is exactly how JCA providers and JDBC drivers compose.)
- **`local` is built in** (wired directly with the signing key, always available, zero deps) — it is never a
  `ServiceLoader` entry.
- **Fail-loud, never silent downgrade** (R3): naming a provider whose module is absent is a **startup
  error**, not a fall-back to `local`. The sketch's selection reproduces the `NettyTransport.select()`
  posture verbatim — *"Refusing to silently fall back to 'local' — a silent downgrade is how a 'data is
  KMS-protected' claim becomes fiction."* Validated by the smoke test (`SketchSmokeTest`: *"forcing an
  absent provider FAILS LOUD … and refuses to silently fall back to local"*).

**Trade-off, stated:** `ServiceLoader` is new to this codebase (a small amount of plumbing + a discovery
test) but is the clean way to keep the core cloud-SDK-free while supporting arbitrary providers; a purely
**explicit registry** (core hard-codes the provider names it knows) is simpler but forces a core change for
every new provider. The hybrid takes the explicit *name* from config and the *implementation* from
`ServiceLoader`, getting both properties. **Operator decision.**

---

## 9. What this SPI deliberately does NOT do (boundaries)

- **No per-record `encrypt`/`decrypt`** — the data-plane cipher is the core's job at the ADR-0042 seam, on
  the locally-derived DEK (§1, R1). Keeping it out is the structural guarantee against the liveness trap.
- **No key *distribution*** — the root key is per-node and never leaves a node; there is no cluster-wide key
  exchange (that is the end-to-end/Option-C model the encryption research rejects for the consensus plane).
- **No rotation *engine*** — the SPI exposes `wrap` (rewrap) and `currentKeyId` (the rotation signal,
  cf. K8s `Status.key_id`), but the keyring/term state machine and the lazy re-encryption sweep are the
  encryption layer's concern, designed when Option D is built.
- **No custom crypto** — providers wrap established KMS APIs (AWS/Azure/GCP/Vault) and standard JCA types;
  the default uses HKDF. Per the charter, no primitive is rolled here.

---

## 10. Open decisions for the operator (carried to the handoff)

1. **The key-material types** — ratify `RootKey`/`WrappedKey`/`KeyId` as designed
   ([`key-material-types.md`](key-material-types.md)), including the `toSecretKey` JCA-bridge caveat and
   whether to adopt scoped-access (Tink-style) or off-heap for the live key.
2. **The discovery mechanism** — hybrid `ServiceLoader` + name (recommended) vs an explicit registry.
3. **SPI module placement** — dedicated `configd-kms-spi` (recommended) vs fold into `configd-common`.
4. **Which providers to build first** — `local` ships with Option B-minimal; the first cloud module is built
   when a named off-host-custody/compliance requirement appears (likely `aws-kms`).

All four are **designed-in now** so that when v2 encryption (Option D) is built, the providers slot in with
**no core change** — interface-first, exactly as the charter asks.
