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

`Decrypt` takes a structured carrier, not a bare byte slice (verified from the generated
`...v2/types.pb.go`; field numbers verbatim, the hand-written `.proto` comments not read):

```protobuf
message BlobInfo {
  bytes   ciphertext  = 1;   // class:"public"
  bytes   iv          = 2;   // class:"secret"
  bytes   hmac        = 3;   // class:"public"
  KeyInfo key_info    = 5;
  Struct  client_data = 8;   // AAD / provenance slot
}
message KeyInfo {
  uint64 mechanism   = 1;
  string key_id      = 3;    // class:"public" — opaque version/label
  bytes  wrapped_key = 5;    // class:"secret" — the wrapped DEK
  bytes  key         = 9;    // class:"secret" — plaintext DEK (optional)
}
```

`BlobInfo` carries the ciphertext **plus `KeyInfo{key_id, mechanism, wrapped_key}` and a `client_data` AAD
slot**, so a reader selects the right KEK with zero coordination — the direct analogue of our
**`WrappedKey(KeyId, ciphertext, context)`** ([`key-material-types.md`](key-material-types.md) §2.3): `key_id`
→ `KeyId`, `wrapped_key` → `ciphertext`, `client_data` → `context`. The wrapped form is self-describing,
exactly as our `WrappedKey` is. (Note the per-field `class:"secret"`/`class:"public"` tags — Vault's
redaction discipline; see §1.5.)

### 1.3 Selection by config; auto-unseal calls the Wrapper once at boot

A provider is chosen by a **`seal` stanza** in Vault's config, by name:

```hcl
seal "awskms"  { region = "us-east-1"  kms_key_id = "alias/vault-unseal" }
# or:  seal "pkcs11" { ... }   /  seal "gcpckms" { ... }   /  seal "transit" { ... }
```

…and **resolved by a compiled-in `switch` over a closed `WrapperType` string enum** — *not* a `ServiceLoader`
or out-of-tree plugin. Each case statically imports its provider package and calls `NewWrapper()` +
`SetConfig()` (config is **pushed via variadic options**, not constructor-injected); the decisive tell is
that `seal "pkcs11"` is a compile-time case that *errors unless you run the Enterprise HSM binary*
(`internalshared/configutil/kms.go`). So Vault is the **"explicit, name-keyed, compiled-in registry"**
alternative I weigh in [`kms-provider-spi.md`](kms-provider-spi.md) §8 — closed set, simplest — against the
`ServiceLoader` hybrid (open set, no core edit per provider).

At startup Vault calls the seal Wrapper's **`Decrypt` once** to unwrap the root key, then runs on the
in-memory keyring — *auto-unseal* (no human, no Shamir): *"At startup, Vault connects to the trusted device or
service and prompts it to decrypt the root key from storage"* (seal concepts). This **boot-once, then
cached** lifecycle is the R1/R2 contract of our SPI ([`kms-provider-spi.md`](kms-provider-spi.md) §3): the KMS
is on the rare boot path, never the per-operation path. The cost is a lifecycle coupling, not a throughput
one — *"Using auto unseal creates a strict Vault lifecycle dependency on the underlying seal mechanism. If a
seal mechanism such as the Cloud KMS key becomes unavailable or is deleted before you migrate the seal, you
cannot recover access to the Vault cluster"* — exactly the boot-time-KMS dependency our R3/R4 fail-closed
contract governs.

### 1.4 The cautionary exception — "seal wrap" turns KMS into a *runtime* dependency

Vault Enterprise's optional **seal wrap** applies the seal (KMS/HSM) as an *outer* layer on crown-jewel
values on every access — which converts the KMS from a *boot-time* dependency into a *runtime* one:
*"This implies that the seal must be available throughout Vault's runtime"* (sealwrap doc), mitigated only for
reads (*"values will be cached in memory un-seal-wrapped … which will mitigate this for read-heavy
workloads"*). This is the precise anti-pattern our SPI forbids by construction (no per-op method → no way to
put the KMS on the runtime path). It is the documented data point proving the danger is real, not
hypothetical.

### 1.5 Key-material handling (Go) — and a deliberate JVM divergence

Go has no `Destroyable`, and — corrected from a first assumption — **`go-kms-wrapping` does NOT zero key
material**: a grep of `wrapper.go`/`envelope.go`/`const.go`/`signer.go`/`wrappers/aead` finds **zero** hits
for `zero|wipe|destroy|memzero|mlock`. The library passes plaintext as plain `[]byte` (`Encrypt(plaintext
[]byte)`, `Decrypt(...) []byte`, `KeyExporter.KeyBytes() []byte`) and relies on the **declarative
`class:"secret"`/`class:"public"` struct tags** (§1.2) for *log/event redaction* — not memory hygiene (Go's
copying GC makes reliable zeroing impractical, so they don't try). **Our JVM design deliberately diverges:**
the JVM *can* wipe (`Destroyable` + `Arrays.fill`), so `RootKey` does — this is an improvement over the
reference, stated honestly as divergence, not parity. We keep the secret/public-redaction idea (our redacted
`toString`) and add the wipe the reference lacks.

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

In KMS **v2** the apiserver does the envelope locally: a 32-byte seed/DEK AES-GCM-encrypts the Secret; the
**plugin wraps the DEK/seed**, not the Secret — *"the API server generates a DEK at startup and caches it.
The API server also makes a call to the KMS plugin to encrypt the DEK using the remote KEK. **This is a
one-time call at startup and on KEK rotation.** The API server then uses the cached DEK to encrypt the
resources"* (v2 beta blog). Per-write DEKs are then `HKDF-Expand(SHA-256)` derivations of the seed (KEP-3299:
*"the crypto properties of KMS v1 (one DEK per write) without the network overhead"*). The contrast with v1 —
*"a new DEK is generated for every encryption … for every write request, the API server makes a call to the
KMS plugin"* — is exactly why v2 exists. So the plugin is **off the per-object path** — the same "KMS wraps a
small key, not the bulk data, amortized off the hot path" contract as Vault and as our SPI (R1).

### 2.4 Out-of-process over a Unix socket → the core carries no cloud SDK

The plugin is a **separately-shipped binary** the apiserver reaches at `unix:///…/socket.sock`
(`--encryption-provider-config`): *"The KMS provider uses gRPC to communicate with a specific KMS plugin over
a UNIX domain socket. The KMS plugin, which is implemented as a gRPC server and deployed on the same host(s)
as the Kubernetes control plane, is responsible for all communication with the remote KMS"* (kms-provider
doc). The cloud SDK lives **in the plugin process**, not in kube-apiserver. This
is the architectural ancestor of our **module layering** ([`kms-provider-spi.md`](kms-provider-spi.md) §7):
the core depends only on the thin contract; each provider's SDK is isolated in its own optional artifact, so
the core never inherits a cloud SDK's footprint or CVE surface. (Configd's providers are in-process Maven
modules discovered by `ServiceLoader` rather than separate processes — same isolation property, lighter
mechanism, since Configd providers run trusted in the node JVM.)

### 2.5 Availability semantics

The plugin's `Status.healthz` is **polled ~every minute (every 10s when unhealthy)** and flows into the
apiserver's own health endpoint — *"Any value other than \"ok\" is failing healthz. On failure, the
associated API server healthz endpoint will contain this value as part of the error message"* (v2 proto). An
undecryptable resource fails closed on read — *"Any calls to the Kubernetes API that attempt to read that
resource will fail until it is deleted or a valid decryption key is provided"* (encrypt-data doc). Encryption
is on the availability path, and the operator is expected to keep the KMS reachable. This is the same
correlated-dependency caution our R3/R4 fail-closed contract addresses; our mitigation is to keep the
dependency at **boot only** and continue on the cached root key thereafter. *(The precise "KMS-plugin
unreachability fails `/readyz` but not `/livez`" startup nuance is KEP-3299-sourced, not verbatim-quoted.)*

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

*(Nuance: this is CockroachDB **self-hosted** store-level encryption-at-rest, which is exclusively
file-based. CockroachDB **Cloud** (managed) has a separate **CMEK** feature that does point at cloud KMS — but
that is a managed-service control plane, not the self-hosted store-key mechanism contrasted here. The
"config-driven key file, no plugin SPI" framing is accurate for the self-hosted store.)*

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

- **AWS `EncryptionContext`** is a `Map<String,String>` of **additional authenticated data** — *"a collection
  of non-secret key-value pairs … When you use an encryption context to encrypt data, you must specify the
  same (an exact case-sensitive match) encryption context to decrypt the data … Otherwise, the request to
  decrypt fails with an InvalidCiphertextException"* (KMS dev guide). This is what our `WrappedKey.context`
  binds (node identity → node A's wrapped key won't unwrap as node B). KMS symmetric `Encrypt` plaintext is
  capped at 4096 bytes — fine for a 256-bit root key.
- **Availability caveats (verbatim):** KMS is **regional**; the per-account request rate is a **shared
  ceiling** — *"Throttling is based on all requests on KMS keys of all types in the Region … includes
  requests from all principals in the AWS account, including … AWS services on your behalf"*; a deleted CMK is
  terminal — *"After a KMS key is deleted, you can no longer decrypt the data that was encrypted under that
  KMS key, which means that data becomes unrecoverable"*; a KMS outage makes `Decrypt` fail (the EBS example:
  *"the attachment fails, because Amazon EBS cannot use the KMS key to decrypt the volume's encrypted data
  key"*). **Multi-Region keys** are the SPOF mitigation (*"encrypt data in one AWS Region and decrypt it in a
  different AWS Region without re-encrypting or making a cross-Region call"*) — with the counter-caveat that
  supports a **single-Region default**: *"For most data security needs, the Regional isolation and fault
  tolerance of Regional resources make standard AWS KMS single-Region keys a best-fit solution."* These
  justify our R3/R4: keep the dependency at boot only, fail closed, de-risk with multi-Region only when DR
  demands it.

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
| **Go** (Vault, go-kms-wrapping) | `[]byte`, transient | **none** — verified no `zero/wipe/memzero`; relies on `class:"secret"` redaction tags only | the JVM *can* wipe, so we do (deliberate divergence — §1.5); keep plaintext transient |
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

- **Vault seal SPI:** `go-kms-wrapping` [`wrapper.go`](https://github.com/hashicorp/go-kms-wrapping/blob/main/wrapper.go)
  (the `Wrapper` + `HmacComputer`/`InitFinalizer`/`KeyExporter` interfaces), the generated
  [`...v2/types.pb.go`](https://github.com/hashicorp/go-kms-wrapping/blob/main/github.com.hashicorp.go.kms.wrapping.v2.types.pb.go)
  (`BlobInfo`/`KeyInfo`/`EnvelopeInfo`), [`const.go`](https://github.com/hashicorp/go-kms-wrapping/blob/main/const.go)
  (closed `WrapperType` enum), Vault [`internalshared/configutil/kms.go`](https://github.com/hashicorp/vault/blob/main/internalshared/configutil/kms.go)
  (the compiled-in selection switch); developer.hashicorp.com/vault [seal](https://developer.hashicorp.com/vault/docs/concepts/seal) ·
  [sealwrap](https://developer.hashicorp.com/vault/docs/enterprise/sealwrap).
- **Kubernetes KMS plugin:** [`kms/apis/v1beta1/api.proto`](https://github.com/kubernetes/kms/blob/release-1.28/apis/v1beta1/api.proto) ·
  [`apis/v2/api.proto`](https://github.com/kubernetes/kms/blob/release-1.29/apis/v2/api.proto);
  [*Using a KMS provider*](https://kubernetes.io/docs/tasks/administer-cluster/kms-provider/) ·
  [*Encrypting … at Rest*](https://kubernetes.io/docs/tasks/administer-cluster/encrypt-data/) ·
  [KEP-3299](https://github.com/kubernetes/enhancements/blob/master/keps/sig-auth/3299-kms-v2-improvements/README.md) ·
  [KMS-v2 beta blog](https://kubernetes.io/blog/2023/05/16/kms-v2-moves-to-beta/) · k8s/k8s PR #78540.
- **CockroachDB:** [encryption reference](https://www.cockroachlabs.com/docs/stable/encryption)
  (`--enterprise-encryption`, `cockroach gen encryption-key`, per-file registry; self-hosted vs Cloud CMEK).
- **Cloud KMS Java SDKs:** AWS [`KmsClient`](https://docs.aws.amazon.com/java/api/latest/software/amazon/awssdk/services/kms/KmsClient.html) ·
  [GenerateDataKey](https://docs.aws.amazon.com/kms/latest/APIReference/API_GenerateDataKey.html) ·
  [encryption-context](https://docs.aws.amazon.com/kms/latest/developerguide/encrypt_context.html) ·
  [request quotas](https://docs.aws.amazon.com/kms/latest/developerguide/requests-per-second.html) ·
  [unusable keys](https://docs.aws.amazon.com/kms/latest/developerguide/unusable-kms-keys.html) ·
  [multi-Region keys](https://docs.aws.amazon.com/kms/latest/developerguide/multi-region-keys-overview.html);
  GCP [envelope encryption](https://cloud.google.com/kms/docs/envelope-encryption) (no GenerateDataKey, Tink);
  Azure [`CryptographyClient`](https://learn.microsoft.com/en-us/java/api/com.azure.security.keyvault.keys.cryptography.cryptographyclient);
  Vault [Transit API](https://developer.hashicorp.com/vault/api-docs/secret/transit);
  [AWS Encryption SDK concepts](https://docs.aws.amazon.com/encryption-sdk/latest/developer-guide/concepts.html)
  (`Keyring`/`MasterKeyProvider`/`CachingCryptoMaterialsManager`).
- **Key-material handling:** [JDK-8160206](https://bugs.openjdk.org/browse/JDK-8160206) ("SecretKeySpec should
  implement destroy()"); [`SecretKeySpec`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/javax/crypto/spec/SecretKeySpec.html) /
  [`Destroyable`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/javax/security/auth/Destroyable.html) Javadoc (JDK 25) +
  [OpenJDK master source](https://raw.githubusercontent.com/openjdk/jdk/master/src/java.base/share/classes/javax/crypto/spec/SecretKeySpec.java);
  Tink [access-control design](https://developers.google.com/tink/design/access_control) /
  [`SecretBytes`](https://github.com/tink-crypto/tink-java/blob/main/src/main/java/com/google/crypto/tink/util/SecretBytes.java);
  JEP [478](https://openjdk.org/jeps/478)→[510](https://openjdk.org/jeps/510) (`javax.crypto.KDF`, final JDK 25);
  [`Arena`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/foreign/Arena.html);
  [CWE-316](https://cwe.mitre.org/data/definitions/316.html).

> **Verification note.** The interface signatures, proto shapes, and quotes above were gathered by parallel
> research agents and **verified against the primary sources linked in this index** — `go-kms-wrapping`
> `wrapper.go`/`types.pb.go`/`const.go` and Vault `kms.go` read at `main`; the `kubernetes/kms` protos read at
> the release tags; the cloud SDK Javadocs and KMS dev-guide pages; the OpenJDK 25 Javadoc + master source.
> Two facts were **independently re-verified**: the **`SecretKeySpec.destroy()` behaviour**, checked
> **empirically on Corretto JDK 25** ([`key-material-types.md`](key-material-types.md) §1.1) *and* against
> OpenJDK master (the fix is absent in JDK 25 and 26-dev); and the **`ConfigdServer`/`NettyTransport` code
> citations**, checked against the source. **Could not be pinned verbatim** (flagged honestly): the
> JDK-8160206 administrative *status label* (issue tracker WAF-blocked — but the code is ground truth) and the
> JEP 478/510 *Status field* (openjdk.org WAF-blocked — finalization confirmed via the OpenJDK Security Group
> lead's JDK 25 write-up); and the K8s `/readyz`-not-`/livez` startup nuance (KEP-sourced, paraphrased).
