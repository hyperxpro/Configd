# 01 — Vault as a Configd KMS provider (`configd-kms-vault`)

> **Arc:** Group B, §2.1 — Vault KMS provider. **Status:** investigation / recommendation only —
> no production code written, nothing built, nothing committed. **Read-only research.**
> **Author seat:** investigation agent. **Date:** 2026-07-06.
>
> This document studies Vault's own primary docs, grounds the design in Configd's *actual* frozen
> `KmsProvider` SPI and Gate-4 keyring seam at `file:line`, and hands the implementers a concrete
> `configd-kms-vault` design. It does **not** copy code and does **not** port anything. Primary
> sources are cited by URL + section; load-bearing text is quoted verbatim.
>
> **Vault docs retrieved 2026-07-06** from `developer.hashicorp.com` (current docs; the Transit
> engine and its API have been stable since Vault 0.6.2). Where a version matters it is called out.

---

## 1. Executive summary + recommendation on every fork

Configd's at-rest stack already keeps **KMS off the hot path by construction**: the frozen
`KmsProvider` SPI has **no per-record `encrypt`/`decrypt`** — it only *seals* (`wrap`) and *unseals*
(`unwrap`) one per-node root, and the core drops the provider after boot (R1–R5,
`KmsProvider.java:26-51`). Vault slots into that seam cleanly **only if we use it as a root-key
custodian, never as a per-record cipher service.**

**Headline recommendation**

| Fork | Recommendation | One-line why |
|---|---|---|
| **Seal model** | **(a) custodian/seal** — Vault Transit `datakey`+`decrypt` seals/unseals **one** per-node secret; Configd derives DEKs locally. **Reject (b)** Transit-as-per-record-cipher. | (b) would put a Vault round-trip on every write/replay — exactly what the SPI's missing per-record method forbids (`KmsProvider.java:9-14`, R1). |
| **Which Vault engine** | **Transit secrets engine** (`transit/datakey/plaintext`, `transit/decrypt`, `transit/rewrap`, `transit/keys/:name/rotate`). | It is Vault's "encryption as a service" that keeps the KEK inside Vault and hands back only a small wrapped blob — the standard envelope shape. |
| **Auth method (default)** | **AppRole** as the portable default; **Kubernetes auth** when on K8s; **TLS cert auth** where operators want to reuse Configd's existing workload PKI. Make the auth method itself config-selectable. | AppRole is Vault's recommended machine default; K8s auth and cert auth *eliminate* secret-zero when the platform already issues an identity. |
| **Credential lifecycle** | **No renewal daemon needed.** Log in → one `unwrap` → `close()`. R2 (drop the provider after boot) means the Vault token is used once and discarded. | Boot-only KMS usage is the whole point; a long-lived renewed token + background thread would be dead weight (and a new liveness surface). |
| **Client library** | **Hand-roll against the HTTP API with the JDK `java.net.http.HttpClient`** (already used in-tree) for the *runtime* path (login + `transit/decrypt`), with a tiny purpose-built reader for the ~3 JSON fields we consume. **`jopenlibs/vault-java-driver` v6.2.2** is the sanctioned fallback if the team prefers a vetted client. | The runtime surface is **two endpoints**; the security-critical crypto happens *inside Vault* (we only shuttle base64 blobs). Hand-rolling keeps even the optional module dependency-free and matches house style; the core is insulated either way. |
| **Where it plugs in** | Route the **keyring-wrapping key custody** (today `KEK_wrap`/`K_keyringMac`, HKDF-from-signing-key) through the `KmsProvider`. `unwrap` returns **one** sealed secret `S`; Configd derives the two keyring keys from `S` and the existing Gate-4 `NodeKeyring` machinery is unchanged. | Preserves the entire Gate-4 multi-term, crash-atomic, non-destructive-rotation keyring while making exactly **one** Vault call at boot. |
| **Discovery wiring** | Build the missing `ServiceLoader<KmsProviderFactory>` hook (the SPI research's still-open decision #2). Today the seam is a hardcoded `"local"` name-check at `ConfigdServer.java:1452-1461` that instantiates **no** provider. | Any non-`local` provider is impossible until discovery exists — this is a real prerequisite, not a detail. |

**The single most important framing for the implementers:** the shipped Gate-4 boot path
(`ConfigdServer.buildTermVersionedEnvelope`, `ConfigdServer.java:1444-1507`) does **not currently call
the `KmsProvider` SPI at all** for `local` — it derives `KEK_wrap`/`K_keyringMac` straight from the
signing key and only *name-checks* that the provider is `"local"`. So "make Vault real" is two jobs:
(1) actually **wire the SPI** as the source of keyring-key custody, and (2) implement the Vault
provider behind it. Job (1) is the load-bearing one and is where `local` and `vault` must converge on
a single seam.

---

## 2. Reference findings (primary sources)

### 2.1 Vault Transit — the custodian/seal primitives

Source: **Transit secrets engine docs** (`developer.hashicorp.com/vault/docs/secrets/transit`) and
**Transit API** (`developer.hashicorp.com/vault/api-docs/secret/transit`), retrieved 2026-07-06.

**Generate a sealed root (the `GenerateDataKey` analogue).** `POST /v1/transit/datakey/plaintext/:name`
returns both the plaintext key and its Vault-wrapped form:

```
POST /v1/transit/datakey/plaintext/my-key   { "bits": 256, "context": "<b64>" }
-> { "data": { "plaintext": "<b64 32-byte key>", "ciphertext": "vault:v1:abcdefgh" } }
```

Use `plaintext` mode to receive the live key **once** at provisioning; persist only `ciphertext`.
(`type=wrapped` returns ciphertext only — useful if a *separate* trusted party mints the key.)
`bits` ∈ {128, 256, 512}; Configd wants **256**.

**Unseal at boot (the one runtime call).** `POST /v1/transit/decrypt/:name`:

```
POST /v1/transit/decrypt/my-key   { "ciphertext": "vault:v1:...", "associated_data": "<b64 AAD>" }
-> { "data": { "plaintext": "<b64 key>" } }
```

`associated_data` is **base64 AAD authenticated with the AEAD ciphers** (`aes256-gcm`,
`aes128-gcm96`, `chacha20-poly1305`) — the node-identity binding, verbatim from the API doc:
*"Specifies base64 encoded associated data (also known as additional data or AAD) to also be
authenticated with AEAD ciphers."* A relocated/copied blob whose AAD doesn't match fails to decrypt.

**Rewrap / rotate (the rotation primitives).**
- `POST /v1/transit/keys/:name/rotate` — *"generate a new encryption key and add it to the keyring
  for the named key."* Docs, verbatim: *"Future encryptions will use this new key. **Old data can
  still be decrypted due to the use of a key ring.**"*
- `POST /v1/transit/rewrap/:name` — *"rewraps the provided ciphertext using the latest version of the
  named key. Because this never returns plaintext, it is possible to delegate this functionality to
  untrusted users or scripts."*

**Self-describing ciphertext = old-data-still-decrypts, proven.** Docs, verbatim: *"The returned
ciphertext starts with `vault:v1:`. The first prefix (`vault`) identifies that it has been wrapped by
Vault. The `v1` indicates the key version 1 was used to encrypt the plaintext; **therefore, when you
rotate keys, Vault knows which version to use for decryption.**"* So after a `rotate`, a previously
persisted `vault:v1:` blob still decrypts with **no** action by Configd — Vault selects the version
from the prefix. `min_decryption_version` (via `POST /transit/keys/:name/config`) is the *deliberate*
retirement knob: *"Adjusting this as part of a key rotation policy can prevent old copies of
ciphertext from being decrypted, should they fall into the wrong hands."*

**Vault never stores the ciphertext.** Verbatim: *"Note that Vault does not store any of this data.
The caller is responsible for storing the encrypted ciphertext."* → Configd persists the `WrappedKey`
itself (beside the keyring), which is exactly the SPI's contract (`WrappedKey.java:8-18`).

**NIST rotation guidance is baked in.** Transit docs recommend rotating an AES-GCM key version before
~2³⁵ (≈ 4 billion) encryptions. This matters for Configd's *own* rekey ceiling accounting but is
orthogonal to the boot-only custodian use (we perform **one** decrypt per boot, not per record).

### 2.2 Auth methods — how a Configd node authenticates *to* Vault

Source: **Auth methods overview** (`.../vault/docs/auth`) and **AppRole**
(`.../vault/docs/auth/approle`), retrieved 2026-07-06.

**AppRole (recommended portable default).** Two-part credential: a `RoleID` (non-secret selector,
bake into node config) and a `SecretID` (the secret). Login, verbatim path/response:

```
POST /v1/auth/approle/login   { "role_id": "...", "secret_id": "..." }
-> { "auth": { "client_token": "...", "lease_duration": 2764800, "renewable": true, "policies": [...] } }
```

The returned `auth.client_token` is what authenticates the subsequent Transit call. Role knobs:
`token_ttl`, `token_policies`, `secret_id_ttl`, `secret_id_num_uses`, `secret_id_bound_cidrs`,
`token_bound_cidrs`. **Secret-zero / bootstrap:** the SecretID is the bootstrap secret. Vault's
answer is the **trusted-orchestrator + response-wrapping** pattern (docs "Response Wrapping" §; the
official code examples ship a `wrappingToken` path): an orchestrator fetches a *response-wrapped*
SecretID and hands the node a **single-use, short-TTL wrapping token**; the node unwraps it once to
get the real SecretID. The wrapping token is worthless if intercepted (single use) or if it expires,
and unwrap-count tampering is detectable. RoleID is distributed openly; SecretID is confined.

**Kubernetes auth (recommended on K8s — no secret-zero).** The node presents its projected
ServiceAccount JWT (`/var/run/secrets/kubernetes.io/serviceaccount/token`); Vault verifies it via the
cluster's TokenReview API and issues a Vault token. The credential is **the platform-issued SA token**
— there is no SecretID to deliver, so the secret-zero problem dissolves. Renewal is automatic (kubelet
re-projects the token).

**TLS cert auth (compelling for Configd specifically).** Vault authenticates a client X.509 cert
against configured trusted CAs (`/v1/auth/cert/login`). **Configd nodes already hold an mTLS workload
cert on both wire planes** — that same identity can authenticate to Vault, reusing existing PKI and
existing rotation. Trade-off: it couples Vault-trust to the cluster's mTLS CA (blast-radius / separation
concern) unless Vault trusts a *distinct* CA slice.

**Token auth (not for production machines).** A raw Vault token in config/env. Simple, but it *is* the
secret-zero with none of the mitigations — acceptable only for tests/dev. Vault treats direct tokens as
the fallback, not the machine default.

**JWT/OIDC auth.** The node presents a signed JWT from a trusted OIDC IdP (`/v1/auth/jwt/login`);
Vault validates the signature against the IdP's JWKS and maps claims → policy. Good where an IdP
already issues workload JWTs (SPIFFE/OIDC, CI federation); overlaps Kubernetes auth (which is a
specialization).

**HashiCorp's own steer:** AppRole is documented as *"intended for machine-oriented workflows,"* and
the platform-native methods (Kubernetes, cert, JWT) are preferred *where the platform already supplies
an identity* because they remove the SecretID-delivery problem. That is exactly our recommendation:
**AppRole default, platform-native when available.**

### 2.3 Java client landscape

Source: **Vault client libraries** (`.../vault/api-docs/libraries`) + GitHub repo metadata, 2026-07-06.

- **There is no first-party HashiCorp Java SDK.** The official libraries page lists only *community*
  Java clients: **Quarkus Vault**, **Spring Vault**, **vault-java-driver**, **vault-java-client-simple**.
  (The only HashiCorp-maintained SDK is Go's `hashicorp/vault/api`.)
- **`jopenlibs/vault-java-driver`** — the *maintained* fork of the (now-stale) `BetterCloud/vault-java-driver`.
  Latest release **v6.2.2, published 2026-05-29**; self-described *"Zero-dependency Java client for
  HashiCorp's Vault"*, Apache-2.0. BetterCloud's original last pushed 2023-12-12 (effectively dormant).
- **Spring Vault** 4.1.0 (2026-06-10) — well-maintained but pulls the Spring stack; overkill here.
- Raw **`java.net.http.HttpClient`** — JDK built-in, **already used in Configd's src/main**
  (`configd-linz/.../client/ConfigClient.java`, plus several testkit drivers), and it takes a JDK
  `SSLContext` directly (reuses the mTLS trust config the codebase already builds).

### 2.4 Testcontainers recipe

Source: **Testcontainers Java Vault module** (`java.testcontainers.org/modules/vault/`), 2026-07-06.

Artifact `org.testcontainers:testcontainers-vault:2.0.5` (legacy coordinate `org.testcontainers:vault`).
Verbatim usage from the module page:

```java
static VaultContainer<?> vault = new VaultContainer<>("hashicorp/vault:1.13")
    .withVaultToken(VAULT_TOKEN)
    .withInitCommand("secrets enable transit", "write -f transit/keys/my-key");
// reach it: vault.getHttpHostAddress() + "/v1/..."   header X-Vault-Token: VAULT_TOKEN
// run CLI in-container: vault.execInContainer("vault", "write", "-f", "auth/approle/role/...");
```

Notes: image is **`hashicorp/vault`** (the post-BSL-rename repo; the old `vault` DockerHub images
stopped near 1.13). Dev mode auto-unseals with a known root token — perfect for bootstrapping engines
and auth methods via `withInitCommand`, then reading back RoleID/SecretID with `execInContainer`.

---

## 3. Configd seam grounding (`file:line`) — SPI method → Vault call

The frozen SPI (`configd-common/src/main/java/io/configd/common/kms/`):

| SPI element | Location | Vault mapping |
|---|---|---|
| `KmsProvider` interface + R1–R5 contract | `KmsProvider.java:52-99` (contract javadoc `:26-51`) | Implement in `configd-kms-vault`. |
| `generateRootKey() : Provisioned` | `KmsProvider.java:67` | `POST transit/datakey/plaintext/:name` → `plaintext`⇒`RootKey`, `ciphertext`("vault:vN:")⇒`WrappedKey.ciphertext`. **Setup, once.** |
| `unwrap(WrappedKey) : RootKey` | `KmsProvider.java:84` | `POST transit/decrypt/:name` `{ciphertext, associated_data=<node AAD>}` → `plaintext`⇒`RootKey`. **The ONE boot call.** |
| `wrap(RootKey) : WrappedKey` | `KmsProvider.java:75` | Re-seal on KEK rotation: `POST transit/encrypt/:name` (or `transit/rewrap/:name` on the stored blob). **Rare.** |
| `currentKeyId() : KeyId` | `KmsProvider.java:58` | `KeyId("vault-transit", <mount/keyName>, <configd keyring term>)`; the Vault key version lives in the `vault:vN:` prefix (decoupled — see §4.4). |
| `healthCheck()` | `KmsProvider.java:87` | `GET /v1/sys/health` (pre-flight reachability). |
| `close()` | `KmsProvider.java:93` | Close the `HttpClient`; drop the Vault token. |
| `KmsUnavailableException` (checked) | `KmsUnavailableException.java:15` | Any Vault failure (login/decrypt/timeout/403/503) ⇒ throw ⇒ node **fails closed** (R3). |
| `RootKey` (owns+zeroes bytes) | `RootKey.java:41-135` | Wrap the decrypted key material; `destroy()` genuinely wipes (`RootKey.java:106-111`). Zero the base64/`byte[]` temporaries from the HTTP response too. |
| `WrappedKey` (opaque, persistable) | `WrappedKey.java:29-66` | Holds the `vault:vN:` ciphertext + `KeyId` + AAD context; persisted beside the keyring, self-describing. |

**The real boot seam and the wiring gap (load-bearing):**

- `ConfigdServer.buildTermVersionedEnvelope`, **`ConfigdServer.java:1444-1507`** — the actual Gate-4
  boot path. At **`:1452-1461`** it only *name-checks* `configd.raft.encryption.kms.provider == "local"`
  and **throws for anything else**; it never constructs a `KmsProvider`.
- At **`:1468-1469`** it derives `K_keyringMac` and `KEK_wrap` **directly from the signing key** via
  `deriveKeyringKey(...)` (`ConfigdServer.java:1523-1531`), then at **`:1484-1494`** loads the
  `NodeKeyring` and unseals all retained per-term roots.
- `NodeKeyring` (`configd-consensus-core/src/main/java/io/configd/raft/NodeKeyring.java`) wraps
  **independent random per-term roots** under `KEK_wrap` locally (AES-GCM via `KeyringCodec`), retains
  every old term (`unsealRootKeys :130`, `rotateTerm :153`, `rewrapForNewSigningKey :177`). This is the
  machinery Vault must **not** disturb.
- `SegmentKeyManager.unsealFrom(KmsProvider, WrappedKey)`,
  **`configd-common/.../SegmentKeyManager.java:185-189`** — the *intended* SPI call site
  (`provider.unwrap(wrapped)`), currently **not on the boot path**. Either this becomes the seam, or the
  keyring-key derivation at `ConfigdServer.java:1468` is re-sourced from the provider (recommended — §4.2).
- Discovery idiom to mirror: `NettyTransport.select()`,
  **`configd-netty/.../NettyTransport.java:75-125`** — name-selection + fail-loud, *never* a silent
  downgrade (`:121-125`). **Note:** despite the SPI research's plan, **no `ServiceLoader` exists in
  src/main today** (verified: zero hits) — the hybrid discovery must be *built*, not merely mirrored.

---

## 4. Recommended `configd-kms-vault` design (for the implementers)

### 4.1 Module layering

```
configd-common (SPI: KmsProvider, RootKey, WrappedKey, KeyId, KmsUnavailableException)
   ▲
   └── configd-kms-vault   (NEW optional module)
          depends on: configd-common  + (recommended) nothing else — java.net.http only
          registers:  META-INF/services/<KmsProviderFactory>   (once discovery exists)
```

The **core stays Vault-free**; `configd-server` gains it only on the runtime classpath when
`configd.raft.encryption.kms.provider=vault-transit`. This matches the SPI research's layering
(`docs/archive/research/kms-spi/kms-provider-spi.md` §7).

### 4.2 The seal model, wired to Gate-4 (recommended: seal ONE keyring-wrapping secret)

Route keyring-key custody through the provider so `local` and `vault` share one seam:

- **`unwrap` returns a single 32-byte secret `S`** (the SPI `RootKey`). Configd then derives the two
  keyring keys from `S` via HKDF with the existing distinct info strings —
  `K_keyringMac = HKDF(S, "configd/keyring-mac/v1")`, `KEK_wrap = HKDF(S, "configd/keyring-wrap/v1")`
  (`ConfigdServer.java:1509-1514`) — instead of from the signing key. For `local`, `S` = signing-key
  material (today's behaviour, byte-identical). For `vault`, `S` = the Transit-sealed secret.
- **Exactly one Vault round-trip at boot** (`transit/decrypt`), then the *unchanged* `NodeKeyring`
  locally wraps/unwraps all per-term data roots. Gate-4's multi-term retention, crash-atomic dual-slot
  writer, and non-destructive rotation all keep working (R2: the provider is used once and dropped).
- **Persisted artifact:** the `WrappedKey` (Vault ciphertext of `S`) lives beside `raft-keyring` in
  `dataDir`, self-describing via `KeyId` + the `vault:vN:` prefix.

*Rejected alternative* — **per-term wrap (SPI-literal, N boot calls):** wrap each keyring root under
Transit directly (`transit/encrypt` per term), so boot issues one `transit/decrypt` **per retained
term**. It is faithful to the literal single-root SPI but multiplies boot Vault calls by the term count
and is a larger change to `KeyringCodec`. Prefer the one-secret model above; flag as an operator choice
if per-root Vault custody is a compliance requirement.

*Rejected alternative* — **(b) Transit as per-record cipher:** call `transit/encrypt`/`decrypt` on
every WAL record/snapshot chunk. This **violates R1** (`KmsProvider.java:27-31`), puts a correlated
external dependency on the write/replay path, and is precisely why the SPI omits a per-record method.
**Do not build this.**

### 4.3 Exact Vault calls per SPI method (runtime path in bold)

1. **`generateRootKey()`** (provisioning): AppRole/K8s/cert login → `POST transit/datakey/plaintext/<key>`
   `{bits:256, context:<b64 nodeAAD>}` → `RootKey`(plaintext), `WrappedKey`("vault:vN:" ciphertext). Zero
   the plaintext temporaries; `close()` the client.
2. **`unwrap(w)`** (boot — the one call): login → **`POST transit/decrypt/<key>`**
   `{ciphertext:w.ciphertext, associated_data:<b64 nodeAAD from w.context>}` → `RootKey`. Any failure ⇒
   `KmsUnavailableException`.
3. `wrap(root)` (KEK rotation, rare): `POST transit/encrypt/<key>` on the current secret, or
   `POST transit/rewrap/<key>` on the stored blob after `transit/keys/<key>/rotate`.
4. `currentKeyId()`: `KeyId("vault-transit", "<mount>/<key>", term)`.
5. `healthCheck()`: `GET /v1/sys/health` (pre-flight reachability; map Vault's documented health
   status codes — healthy vs sealed/standby/uninitialized — per the `sys/health` API doc when built;
   unreachable ⇒ `KmsUnavailableException`).

### 4.4 Rotation & the "old data still decrypts" proof

Two **independent** rotation counters, both self-describing — keep them decoupled:

- **Vault transit key version** — bumped by `transit/keys/<key>/rotate`; recorded in the `vault:vN:`
  prefix of each persisted `WrappedKey`. After a rotate, an old `vault:v1:` blob **still decrypts** with
  no Configd action (Vault picks the version from the prefix — §2.1, verbatim). `min_decryption_version`
  is the explicit retirement knob.
- **Configd keyring term** — `KeyId.version` (`KeyId.java:23-31`), the generation of Configd's *own*
  per-term data roots in `NodeKeyring`. Unaffected by Vault rotation.

Rotating the Vault KEK re-seals `S` (rewrap) without touching the keyring; rotating the Configd term
appends a new data root. Old-term data verifies/decrypts because `SegmentKeyManager.overTerms(...)`
(`ConfigdServer.java:1493-1494`) loads all retained roots. **Test both independently** (Phase 2).

### 4.5 Auth method (default + selectable), fail-closed, credential lifecycle

- `configd.kms.vault.auth.method = approle` (default) `| kubernetes | cert | token`.
- **AppRole default:** `roleId` in config; `secretId` delivered as a **response-wrapped single-use
  wrapping token** by a trusted orchestrator (unwrap once at boot). **Kubernetes:** present the projected
  SA JWT — no secret-zero. **Cert:** reuse the node's existing mTLS client cert against Vault's cert
  auth. **Token:** dev/test only.
- **Lifecycle is trivial (R2):** login → one `unwrap` → `close()`. No token-renewal daemon, no
  background thread, no new liveness surface. (If online rotation/`wrap` is ever done on a running node,
  that call re-authenticates ad hoc; it is not on the data path.)
- **Fail-closed (R3), verbatim posture from the seam we mirror** (`NettyTransport.java:121-125`): a
  configured-but-unreachable Vault at boot ⇒ `KmsUnavailableException` ⇒ **node refuses to start**,
  never a silent downgrade to `local` or to no encryption. A blip *after* boot is invisible (provider
  already dropped — R4).

### 4.6 Config keys + defaults

| Key | Default | Meaning |
|---|---|---|
| `configd.raft.encryption.kms.provider` | `local` | Set `vault-transit` to select this module (fail-loud if module absent — R3). |
| `configd.kms.vault.address` | *(required)* | e.g. `https://vault.internal:8200`. TLS required. |
| `configd.kms.vault.transitMount` | `transit` | Transit engine mount path. |
| `configd.kms.vault.transitKeyName` | *(required)* | Named Transit key (e.g. `configd-root-kek`). |
| `configd.kms.vault.namespace` | *(none)* | Vault Enterprise namespace header, if used. |
| `configd.kms.vault.auth.method` | `approle` | `approle\|kubernetes\|cert\|token`. |
| `configd.kms.vault.auth.approle.roleId` | *(req if approle)* | Non-secret RoleID. |
| `configd.kms.vault.auth.approle.wrappedTokenFile` | *(req if approle)* | Path to the response-wrapped SecretID token (trusted-orchestrator delivery). |
| `configd.kms.vault.auth.kubernetes.role` | *(req if k8s)* | Vault K8s role. |
| `configd.kms.vault.auth.kubernetes.jwtPath` | `/var/run/secrets/kubernetes.io/serviceaccount/token` | Projected SA token. |
| `configd.kms.vault.tls.caFile` | *(recommended)* | Pin Vault's server CA (reuse existing SSLContext plumbing). |
| `configd.kms.vault.aadContext` | node id | `associated_data` binding the seal to this node. |
| `configd.kms.vault.timeoutMs` | e.g. `5000` | Boot-call timeout → fail-closed on expiry. |

### 4.7 Client library call

Recommend **hand-roll with `java.net.http.HttpClient`** (reuses the in-tree HTTP + `SSLContext`
idiom; runtime surface is *two* endpoints — login + `transit/decrypt`; the only JSON we read is
`auth.client_token`, `data.plaintext`, `data.ciphertext`, so a ~40-line field reader suffices and the
module stays dependency-free). If the team prefers a vetted client for defence-in-depth, use
**`io.github.jopenlibs:vault-java-driver:6.2.2`** (maintained, zero-dependency, Apache-2.0) confined to
`configd-kms-vault` — the core never sees it. **Operator/implementer call.**

### 4.8 Testcontainers plan (Phase 2)

One-line recipe: **`new VaultContainer<>("hashicorp/vault:1.13").withVaultToken(TOKEN).withInitCommand("secrets enable transit", "write -f transit/keys/configd-root-kek type=aes256-gcm96", "auth enable approle", "write auth/approle/role/configd token_policies=configd secret_id_ttl=10m", ...)`**, then `execInContainer("vault","read","-format=json","auth/approle/role/configd/role-id")` + `write -f .../secret-id` to mint credentials, and drive the provider against `getHttpHostAddress()`.

Coverage the test phase must include:
- Happy path: `generateRootKey` → persist `WrappedKey` → new provider instance `unwrap` → byte-identical `RootKey`.
- **Vault-unreachable at boot** (stop the container / point at a dead port) ⇒ `KmsUnavailableException` ⇒ **refuse to start** (R3) — the fail-closed proof.
- **Rotation:** `transit/keys/.../rotate`, then prove an old persisted `vault:v1:` blob still decrypts (R: old-data-still-decrypts). Independently, exercise Configd keyring-term rotation.
- **AAD mismatch:** decrypt with wrong `associated_data` ⇒ failure (relocation defence).
- Each auth method bootstrapped in-container (AppRole via CLI; token trivially; cert/k8s as feasible in CI).
- Provider dropped after boot (R2): assert no Vault call occurs on the write/replay path.

---

## 5. Open questions for the operator / lead

1. **Seam shape (the big one):** adopt the *one-secret* model (§4.2 — Vault seals a single `S`, keyring
   keys derived from it, one boot call, minimal change) vs the *per-term-wrap* SPI-literal model (N boot
   calls, larger `KeyringCodec` change)? Recommendation: one-secret.
2. **Discovery mechanism:** the SPI research left this open and **no `ServiceLoader` exists yet**. Build
   the hybrid name+`ServiceLoader` discovery, or extend the hardcoded switch at
   `ConfigdServer.java:1452`? A non-`local` provider is blocked until this is decided. Recommendation:
   hybrid `ServiceLoader<KmsProviderFactory>` (keeps the core Vault-free).
3. **Client library:** hand-roll (house style, zero deps) vs `jopenlibs/vault-java-driver` (vetted).
   Recommendation: hand-roll for the 2-endpoint runtime surface.
4. **Default auth method** confirmation: AppRole portable-default with response-wrapped SecretID, and
   whether to *prefer* Kubernetes/cert auth automatically when the platform supplies an identity.
5. **Vault topology for boot availability:** to avoid a correlated boot SPOF, require HA Vault +
   Performance/DR replication (or a per-region Transit mount) so a single Vault outage cannot brick a
   quorum of Configd nodes restarting together (the same correlated-dependency concern the SPI research
   §3 names for cloud KMS). Operator sizing decision.
6. **Transit key ownership & policy:** who mints/rotates the Transit key and owns the `min_decryption_version`
   policy (Configd operators vs a central Vault team), and whether each node gets its own Transit key or
   shares one with per-node AAD.
```
