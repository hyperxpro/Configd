# Prior art — pluggable KMS SPI shapes + key-material handling

> **Session:** KMS-SPI design research, 2026-06-28. **Status:** research only — no code.
> **Scope:** how mature systems expose **pluggable key custody** at the *interface* level — the plugin
> contract, what it operates on, how a provider is selected, the availability semantics — and how the JVM
> ecosystem handles **live key material**. The crypto *mechanism* prior art (cipher, envelope, unseal,
> rotation, threat model) is already extracted in the parent research
> ([`../encryption-at-rest/prior-art.md`](../encryption-at-rest/prior-art.md)); this document is its
> orthogonal **interface-shape** companion and does not repeat it.

---

## 0. The recurring shape

Every system that makes key custody pluggable converges on the same interface:

> **one small abstraction — a `Wrapper` / plugin / keyring — that does `wrap`/`unwrap` of a small key blob
> (a root key or a DEK), never the bulk data; selected by configuration; with the key-encryption key (KEK)
> living behind it (in a KMS/HSM/file).**

That shape is exactly what this SPI adopts ([`kms-provider-spi.md`](kms-provider-spi.md)). The systems differ
in *where the plugin runs* (in-process Go interface vs out-of-process gRPC), *what it operates on* (root key
vs per-object DEK), and *how it is discovered* — and each difference is a lesson recorded below.

---

## 1. HashiCorp Vault — the `go-kms-wrapping` seal `Wrapper` (the closest prior art)

Vault is the operator's named reference. The relevant artifact is **not** the barrier (the crypto mechanism,
covered in the parent research) but the **seal abstraction**: the single Go interface by which Vault plugs
AWS KMS / Azure Key Vault / GCP CKMS / OCI / AliCloud / Vault Transit / PKCS#11-HSM into the *same* unseal
path. It lives in the standalone library **`github.com/hashicorp/go-kms-wrapping`**.

### 1.1 The `Wrapper` interface — wrap/unwrap of one blob, nothing more

The core interface (`wrapping.go`) is, in essence:

```go
type Wrapper interface {
    Type(ctx context.Context) (WrapperType, error)              // "awskms", "azurekeyvault", "pkcs11", ...
    KeyId(ctx context.Context) (string, error)                  // id of the KEK currently in use (rotation)
    SetConfig(ctx context.Context, opt ...Option) (*WrapperConfig, error)
    Encrypt(ctx context.Context, plaintext []byte, opt ...Option) (*BlobInfo, error)  // wrap
    Decrypt(ctx context.Context, ciphertext *BlobInfo, opt ...Option) ([]byte, error) // unwrap
}
// optional extension interfaces a Wrapper may also implement:
//   InitFinalizer { Init(ctx); Finalize(ctx) }      — lifecycle
//   HmacComputer  { HmacKeyId(ctx) string }          — for the recovery/HMAC key
```

The shape that matters: **`Encrypt`/`Decrypt` operate on a `plaintext []byte` that is the *root key*, and
return/accept a `*BlobInfo` carrier** — not Vault's stored data. Vault encrypts its data with the *barrier*
keyring; the seal `Wrapper` only ever wraps/unwraps the **root key** that protects that keyring. This is the
one-blob-custody contract our SPI copies (its `wrap`/`unwrap` map directly to `Encrypt`/`Decrypt`, its
`type()`/`currentKeyId()` to `Type`/`KeyId`).

### 1.2 `BlobInfo` — the self-describing wrapped-key carrier

`Decrypt` takes a structured carrier, not a bare byte slice (`types.proto`):

```protobuf
message BlobInfo {
  bytes   ciphertext = 1;
  bytes   iv         = 2;
  bytes   hmac       = 3;
  bool    wrapped    = 4;
  KeyInfo key_info   = 5;   // { Mechanism, WrappedKey, KeyId, Encoding, ... }
}
```

`BlobInfo` carries the ciphertext **plus the `KeyId` and mechanism** that produced it, so a reader selects
the right KEK with zero coordination — the direct analogue of our **`WrappedKey(KeyId, ciphertext, context)`**
([`key-material-types.md`](key-material-types.md) §2.3). The wrapped form is self-describing, exactly as our
`WrappedKey` is.

### 1.3 Selection by config; auto-unseal calls the Wrapper once at boot

A provider is chosen by a **`seal` stanza** in Vault's config, by name:

```hcl
seal "awskms"  { region = "us-east-1"  kms_key_id = "alias/vault-unseal" }
# or:  seal "pkcs11" { ... }   /  seal "gcpckms" { ... }   /  seal "transit" { ... }
```

At startup Vault calls the seal Wrapper's **`Decrypt` once** to unwrap the root key, then runs on the
in-memory keyring — *auto-unseal* (no human, no Shamir). This **boot-once, then cached** lifecycle is the R1/
R2 contract of our SPI ([`kms-provider-spi.md`](kms-provider-spi.md) §3): the KMS is on the rare boot path,
never the per-operation path. Config-name selection is the model our discovery seam follows
(`configd.raft.encryption.kms.provider`, §8 of the contract).

### 1.4 The cautionary exception — "seal wrap" turns KMS into a *runtime* dependency

Vault Enterprise's optional **seal wrap** applies the seal (KMS/HSM) as an *outer* layer on crown-jewel
values on every access — which converts the KMS from a *boot-time* dependency into a *runtime* one (the KMS
must then be reachable continuously, not just at unseal). This is the precise anti-pattern our SPI forbids by
construction (no per-op method → no way to put the KMS on the runtime path). It is the documented data point
proving the danger is real, not hypothetical.

### 1.5 Key-material handling (Go)

Go has no `Destroyable`; the libraries pass `[]byte` and the discipline is **explicit zeroing** of the
plaintext key after use (the same "wipe ASAP" intent our `RootKey.destroy()` makes structural on the JVM).
The `Wrapper` never exposes a long-lived plaintext-key getter — plaintext exists only transiently inside
`Encrypt`/`Decrypt`.

---

## 2. Kubernetes — the KMS provider plugin gRPC API (the canonical pluggable interface)

Kubernetes makes KMS pluggable via an **out-of-process gRPC plugin** the kube-apiserver talks to over a
**Unix domain socket**. This is the richest worked example of a *KMS plugin contract* in a major OSS system,
and its out-of-process model is the strongest lesson for *keeping the core dependency-free*.

### 2.1 v1beta1 — the original three RPCs

```protobuf
service KeyManagementService {
  rpc Version(VersionRequest) returns (VersionResponse) {}
  rpc Encrypt(EncryptRequest) returns (EncryptResponse) {}
  rpc Decrypt(DecryptRequest) returns (DecryptResponse) {}
}
```

### 2.2 v2 (KEP-3299, stable in Kubernetes 1.29) — adds `Status` + `key_id`

```protobuf
service KeyManagementService {
  rpc Status (StatusRequest)  returns (StatusResponse)  {}   // version, healthz, key_id
  rpc Encrypt(EncryptRequest) returns (EncryptResponse) {}   // {uid, plaintext} -> {ciphertext, key_id, annotations}
  rpc Decrypt(DecryptRequest) returns (DecryptResponse) {}   // {uid, ciphertext, key_id, annotations} -> {plaintext}
}
message EncryptResponse { bytes ciphertext = 1; string key_id = 2; map<string, bytes> annotations = 3; }
message StatusResponse  { string version = 1; string healthz = 2; string key_id = 3; }
```

Two design points carried into our SPI:

- **`key_id` is the rotation signal.** `Status` returns an authoritative `key_id`; when it changes (operator
  rotated the KEK), the apiserver mints a fresh DEK under the new KEK — **KEK rotation with no apiserver
  restart**. Our `currentKeyId()` plays exactly this role.
- **`annotations` travel with the ciphertext** — provider metadata stored next to the wrapped DEK, so decrypt
  is self-describing. The analogue of our `WrappedKey.context` (and Vault's `KeyInfo`).

### 2.3 The plugin operates on the **DEK**, never the Secret value

In KMS **v2** the apiserver does the envelope locally: a local DEK AES-GCM-encrypts the Secret; the **plugin
wraps the 32-byte DEK/seed**, not the Secret. A single seed is wrapped **once per apiserver / per KEK
rotation** and reused (HKDF-expanded per write). So the plugin is **off the per-object path** — the same
"KMS wraps a small key, not the bulk data, and is amortized off the hot path" contract as Vault and as our
SPI (R1).

### 2.4 Out-of-process over a Unix socket → the core carries no cloud SDK

The plugin is a **separately-shipped binary** the apiserver reaches at `unix:///…/socket.sock`
(`--encryption-provider-config`). The cloud SDK lives **in the plugin process**, not in kube-apiserver. This
is the architectural ancestor of our **module layering** ([`kms-provider-spi.md`](kms-provider-spi.md) §7):
the core depends only on the thin contract; each provider's SDK is isolated in its own optional artifact, so
the core never inherits a cloud SDK's footprint or CVE surface. (Configd's providers are in-process Maven
modules discovered by `ServiceLoader` rather than separate processes — same isolation property, lighter
mechanism, since Configd providers run trusted in the node JVM.)

### 2.5 Availability semantics

The plugin's `Status.healthz` is polled; an unhealthy/unreachable KMS plugin makes the apiserver unable to
decrypt at-rest Secrets — encryption is on the availability path, and the operator is expected to keep the
KMS reachable. This is the same correlated-dependency caution our R3/R4 fail-closed contract addresses; our
mitigation is to keep the dependency at **boot only** and continue on the cached root key thereafter.

---

## 3. CockroachDB — the store-key model (a deliberate *contrast*: not a plugin SPI)

CockroachDB is the closest structural analogue to Configd (Raft-replicated, range-sharded, WAL + snapshots),
so its key-management *interface* is instructive precisely because it is **not** a plugin SPI:

- Key custody is **a file path on the command line**, not an interface:
  `--enterprise-encryption=path=<store>,key=<aes-128.key>,old-key=<...>`, with the key file generated by
  `cockroach gen encryption-key -s 128 aes-128.key`.
- Rotation is "supply old + new key file"; there is **no pluggable KMS interface** to AWS/Azure/GCP at the
  store-encryption layer — the operator wires KMS *outside* Cockroach (e.g. fetch the key file from a secret
  manager at deploy).

**The lessons, even without an SPI:** (a) Cockroach still makes every on-disk file **self-describing about
its key** (a per-file `key_id` in a plaintext registry) and rotates by **rewrap, not bulk re-encrypt** — the
self-describing-key + cheap-rotation pattern is universal, SPI or not, and is why our `WrappedKey` carries a
`KeyId`. (b) The store-key-file model is the **"config-driven, not plugin" alternative** to a `KmsProvider`
SPI: simpler (no interface), but it pushes all KMS integration onto the operator and offers no in-process
custody abstraction. Our **`local` provider** is morally this file/derivation model (zero-dependency,
operator-supplied root of trust = the signing key); the **SPI** is what Cockroach lacks, added so cloud
custody is a drop-in rather than an external wiring exercise.

---

## 4. Cloud-KMS Java SDK shapes — the substrate each provider wraps

The optional modules wrap these. What matters for the SPI is that each reduces to **wrap/unwrap of one root
key**, and each is a **separate Maven artifact** (so the core stays SDK-free).

| Provider | Java entry point | wrap (setup/rotation) | unwrap (boot) | Maven artifact |
|---|---|---|---|---|
| **AWS KMS** | `software.amazon.awssdk.services.kms.KmsClient` | `generateDataKey(GenerateDataKeyRequest{keyId=cmk, keySpec=AES_256, encryptionContext})` → `plaintext`+`ciphertextBlob` (`SdkBytes`); or `encrypt(...)` to rewrap | `decrypt(DecryptRequest{ciphertextBlob, encryptionContext})` → `plaintext` | `software.amazon.awssdk:kms` |
| **GCP Cloud KMS** | `com.google.cloud.kms.v1.KeyManagementServiceClient` | `encrypt(name, plaintext)` — **no `GenerateDataKey`**; generate the DEK locally then wrap (Tink recommended) | `decrypt(name, ciphertext)` | `com.google.cloud:google-cloud-kms` |
| **Azure Key Vault** | `com.azure.security.keyvault.keys.cryptography.CryptographyClient` | `wrapKey(KeyWrapAlgorithm, key)` | `unwrapKey(KeyWrapAlgorithm, encrypted)` | `com.azure:azure-security-keyvault-keys` |
| **Vault Transit** | HTTP API (or a Vault Java client) | `POST transit/encrypt/:name` (key never leaves Vault); `transit/datakey/...` replicates GenerateDataKey | `POST transit/decrypt/:name` | (HTTP / thin client) |
| **PKCS#11 HSM** | JCA `SunPKCS11` provider | `Cipher`/`KeyStore` wrap against the HSM token | unwrap against the token | **none — JDK-built-in** |

- **AWS `EncryptionContext`** is a `Map<String,String>` of **additional authenticated data** — non-secret,
  **exact-match-to-decrypt**; a relocated/renamed blob fails. This is what our `WrappedKey.context` binds
  (node identity → node A's wrapped key won't unwrap as node B).
- **Availability caveats (verbatim-class, from the parent research §3.3):** KMS is **regional**; the
  per-account request rate is a **shared ceiling** (a noisy neighbour can throttle); a **disabled/deleted/
  denied CMK or a KMS outage** makes `Decrypt` fail outright (AWS's EBS example: *"the attachment fails,
  because Amazon EBS cannot use the KMS key to decrypt the volume's encrypted data key"*). **Multi-Region
  keys + exponential backoff** are the documented mitigations. These justify our R3/R4: keep the dependency
  at boot only, fail closed, and de-risk with multi-Region.

### 4.1 The AWS Encryption SDK — the ecosystem's *provider* abstraction

Beyond the raw `KmsClient`, the **AWS Encryption SDK for Java** offers the ecosystem's own pluggable
abstraction worth noting as prior art: a **`Keyring`** / `MasterKeyProvider` produces and wraps data keys,
and a **`CachingCryptoMaterialsManager`** caches data keys with **max-age / max-messages / max-bytes**
bounds — a *security-vs-availability* dial. We **do not adopt** this framework (it is a value-encryption
toolkit; our SPI is a tiny boot-unseal seam), but it confirms the shape — a provider interface + a caching
layer that keeps the KMS off the hot path — and the bounded-reuse discipline informs how long a cached root
key/DEK may live before rotation.

---

## 5. Key-material handling across the field — what the types must do

| Ecosystem | Live-key representation | Wipe mechanism | Lesson for our types |
|---|---|---|---|
| **Go** (Vault, go-kms-wrapping) | `[]byte`, transient | **manual zeroing**; never a long-lived getter | keep plaintext transient; no raw getter → our `RootKey.withMaterial` scoped access |
| **Java / JCA** | `javax.crypto.SecretKey` | **broken** — `SecretKeySpec.destroy()` throws & doesn't wipe (JDK-8160206, verified JDK 25) | don't use `SecretKeySpec` as the owning type; implement `Destroyable` for real → `RootKey` |
| **Google Tink** | `SecretBytes` / `Bytes`, gated by `SecretKeyAccess` | access to raw bytes requires an explicit **token** (`InsecureSecretKeyAccess.get()`) — conspicuous, greppable | adopt the *principle* (scoped, conspicuous raw access), not the dependency |
| **General JVM guidance** | `char[]`/`byte[]` over `String` | wipeable arrays, but GC-copy/heap-dump exposure remains (CWE-316) | wipe on close + redacted `toString`; off-heap is the deeper (deferred) hardening |

The composite lesson — and the one [`key-material-types.md`](key-material-types.md) turns into concrete types:
**a live key must be a wipeable, redacted, scoped handle (`RootKey`), distinct in type from its sealed,
persistable, self-describing carrier (`WrappedKey`); raw bytes are accessible only deliberately and
transiently.** No major system trusts a bare `byte[]` for live key custody.

---

## 6. Synthesis — the interface lessons for Configd's SPI

1. **One small abstraction, wrap/unwrap of a root key — never the bulk data** (Vault `Wrapper`, K8s plugin
   on the DEK). → the `KmsProvider` contract; no per-record method, by design.
2. **The wrapped blob is self-describing** (Vault `BlobInfo`/`KeyId`, K8s `key_id`+`annotations`, Cockroach
   per-file `key_id`). → `WrappedKey` carries `KeyId` + `context`.
3. **A `Status`/`KeyId` signal drives rotation without restart** (K8s `Status.key_id`, Vault `KeyId`). →
   `currentKeyId()`.
4. **Select the provider by config name; auto-unseal once at boot** (Vault `seal` stanza). → the
   `configd.raft.encryption.kms.provider` selection seam, fail-loud.
5. **Keep the core dependency-free by isolating each SDK** (K8s out-of-process plugin). → optional Maven
   modules + `ServiceLoader`; the core carries no cloud SDK.
6. **The "no SPI, just a key file" alternative exists and is simpler** (Cockroach) — but pushes KMS
   integration onto the operator. → our `local` default is that model; the SPI is the drop-in upgrade
   Cockroach lacks.
7. **Never trust a bare `byte[]` for a live key; the wrapped/live distinction is a type distinction**
   (JCA broken-destroy, Tink token-gated access, Go manual-zero). → `RootKey` vs `WrappedKey`.

These are applied to the contract in [`kms-provider-spi.md`](kms-provider-spi.md) and to the key types in
[`key-material-types.md`](key-material-types.md).

---

## Source index

*Interface-level sources for this document (the crypto-mechanism sources are in the parent research's
[`../encryption-at-rest/prior-art.md`](../encryption-at-rest/prior-art.md) source index).*

- **Vault seal SPI:** `github.com/hashicorp/go-kms-wrapping` (`wrapping.go` — the `Wrapper` interface;
  `types.proto` — `BlobInfo`/`KeyInfo`); developer.hashicorp.com/vault — seal/unseal, seal config stanzas
  (awskms / azurekeyvault / gcpckms / pkcs11 / transit), seal-wrap.
- **Kubernetes KMS plugin:** `github.com/kubernetes/kms` (`apis/v1beta1/api.proto`, `apis/v2/api.proto`);
  kubernetes.io — *Using a KMS provider*; KEP-3299 (KMS v2 improvements); the KMS-v2 beta blog (2023-05).
- **CockroachDB:** cockroachlabs.com — encryption reference + operational guide (`--enterprise-encryption`,
  `cockroach gen encryption-key`, the per-file key registry).
- **Cloud KMS Java SDKs:** AWS SDK for Java v2 `KmsClient` (GenerateDataKey/Decrypt/Encrypt,
  `EncryptionContext`, `SdkBytes`); GCP `KeyManagementServiceClient`; Azure `CryptographyClient`
  (wrapKey/unwrapKey); Vault Transit API; the AWS Encryption SDK for Java (`Keyring`/`MasterKeyProvider`/
  `CachingCryptoMaterialsManager`).
- **Key-material handling:** JDK bug **JDK-8160206** (`SecretKeySpec` destroy); `javax.security.auth.Destroyable`
  Javadoc; Google Tink `SecretKeyAccess`/`InsecureSecretKeyAccess`/`SecretBytes`; JEP 478 (`javax.crypto.KDF`);
  the FFM `MemorySegment`/`Arena` docs; CWE-316.

> **Verification note.** The interface signatures and proto shapes above are stated from the primary sources
> named in the index (the `go-kms-wrapping` `Wrapper`/`BlobInfo`; the `kubernetes/kms` v1beta1/v2 protos; the
> cloud SDK Javadocs; the Cockroach CLI flags). Two classes of fact were independently verified this session:
> the **JDK-8160206 `SecretKeySpec.destroy()` behaviour**, checked **empirically on Corretto JDK 25**
> ([`key-material-types.md`](key-material-types.md) §1.1), and the **`ConfigdServer`/`NettyTransport` code
> citations**, checked against the source. The **availability caveats** (AWS regional/throttle/EBS-fail,
> Vault seal-wrap-as-runtime-dependency) are carried verbatim from the parent research's
> [`../encryption-at-rest/prior-art.md`](../encryption-at-rest/prior-art.md) §3.3 / §1.6, which verified them
> against AWS/HashiCorp docs. Where a verbatim quote is not reproduced inline here, the named primary source
> is the authority.
