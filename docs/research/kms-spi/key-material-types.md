# Key-material types — why not `byte[]`, and the recommended typed contract

> **Session:** KMS-SPI design research, 2026-06-28. **Status:** design + recommendation only — no production
> crypto. This is the **core** deliverable (charter §3): the argument that raw `byte[]` is the wrong type for
> key material in a security SPI, an evaluation of the typed alternatives, and the **recommended concrete
> types**. Feeds [`kms-provider-spi.md`](kms-provider-spi.md); the types are realised (compile-checked) in
> [`sketch/`](sketch/).
>
> The load-bearing behavioural claims here were **verified empirically on this project's runtime**
> (Amazon Corretto **JDK 25**, the version `java -version` reports in this repo), with a small probe whose
> output is reproduced in §1.1 — not asserted from memory. External-library and JDK-bug citations corroborate
> the empirical results in [`prior-art.md`](prior-art.md) §5.

---

## 0. The guiding principle

> **The type system should make leaking or misusing live key material hard, and make the wrapped-vs-live
> distinction explicit.**

A root key passes through three states in this SPI — *sealed* (ciphertext, persistable), *live* (plaintext,
must be wiped), and *identified* (non-secret id/version). Each should be a **distinct Java type** so the
compiler — not a reviewer's vigilance — enforces what may be persisted, logged, or must be wiped. Raw
`byte[]` collapses all three into one untyped, unwipeable, freely-`toString`-able array. We design past it.

---

## 1. Why `byte[]` (and bare `SecretKeySpec`) is the wrong type for *live* key material

### 1.1 It is not deterministically wipeable — empirically, on JDK 25

Key material must be promptly and reliably zeroed: a `byte[]` lives until GC, GC **copying collectors can
leave stale copies** of it elsewhere in the heap, and it can land in **heap dumps, core dumps, and swap**
(CWE-316, *Cleartext Storage of Sensitive Information in Memory*). The standard JCA answer is "use a
`Destroyable` key and call `destroy()`." **On this runtime, that does not work for the JCA's own standard
symmetric-key type.** A probe run on Corretto JDK 25:

```
[1] SecretKeySpec overrides destroy()?      false
    SecretKeySpec overrides isDestroyed()?  false
    isDestroyed() before: false
    destroy() THREW DestroyFailedException
    isDestroyed() after:  false
    getEncoded() after destroy() still returns key bytes? true     ← key NEVER wiped

[2] default Destroyable.isDestroyed(): false
    default Destroyable.destroy() THREW: DestroyFailedException

[3] KeyGenerator AES key class: javax.crypto.spec.SecretKeySpec    ← even generated keys are SecretKeySpec here
    destroy() THREW; isDestroyed() after: false
```

So `javax.crypto.spec.SecretKeySpec` — the type you get from `new SecretKeySpec(bytes, "AES")` *and*, on
this JDK, from `KeyGenerator.getInstance("AES").generateKey()` — **does not override `destroy()`**, inherits
the `Destroyable` default that **throws `DestroyFailedException`**, leaves `isDestroyed()` **false**, and
**still returns the key via `getEncoded()` afterwards.** This is the long-standing JDK bug **JDK-8160206**
("`SecretKeySpec` doesn't implement `destroy()`"), still observable in 2026. A key type that cannot be wiped
is the wrong type for a root key. *(The codebase's existing `K_integrity`/`K_audit` derivations wrap HKDF
output in `SecretKeySpec` — acceptable for a process-lifetime HMAC key, but **not** the model to copy for a
root key we explicitly want to scope and wipe.)*

### 1.2 It is untyped — the compiler can't tell ciphertext from a live key, or root from derived

A `byte[]` of wrapped ciphertext (safe to persist and log) and a `byte[]` of live key material (never log,
wipe ASAP) have the **same type**. So do a root key and a derived data key. Nothing stops a caller writing a
live key to disk, putting it in a log line, or using a root key where a DEK was meant. The bug is invisible
to the compiler. **The type distinction is the defence** (§2.3).

### 1.3 No controlled `toString()` — invites accidental leakage

`byte[].toString()` is harmless (`[B@1b6d3586`), but the moment key bytes are carried in a field of some
record/holder, a default `toString()` / structured-logging reflection / an exception message
(`"failed for key " + key`) can serialise the **contents**. A key type must define a **redacted**
`toString()` as part of its contract, which a bare array cannot.

### 1.4 It carries no metadata — a wrapped key needs its identity alongside it

A sealed key is meaningless without **which KEK + keyring version** produced it (for zero-coordination
selection on read and for rotation). A `byte[]` carries none of this; the caller must thread a parallel
`keyId` by hand, and they can desynchronise. The metadata belongs **in the type**.

---

## 2. The patterns evaluated

Each pattern, its fit for "custody + unseal of one per-node root key," and its trade-off.

### 2.1 `javax.crypto.SecretKey` / `SecretKeySpec` — the JCA standard symmetric type

- **Pro:** speaks the exact language the consumers speak — `Cipher.init`, `Mac.init`, the new
  `javax.crypto.KDF` (§2.8) all take a `SecretKey`. Zero impedance with the crypto libraries.
- **Con (decisive for the *live* key):** **not reliably wipeable** — §1.1, empirically. `destroy()` throws
  and the bytes survive.
- **Verdict:** **use it only as a transient bridge**, never as the *owning* representation of the root key.
  Our live handle (`RootKey`) materialises a `SecretKeySpec` on demand via `toSecretKey(alg)` for a `Cipher`/
  `KDF` call, documents that the result can't be wiped, and keeps the **authoritative, wipeable copy in its
  own `byte[]`**. The `SecretKey` is a short-lived adapter; the `RootKey` is the lifecycle owner.

### 2.2 `javax.security.auth.Destroyable` — the JDK's wipe contract

- **Pro:** the standard interface (`destroy()` + `isDestroyed()`) that signals and, when *properly
  implemented*, enforces the wipe lifecycle. Implementing it makes our intent legible to any security-aware
  consumer.
- **Con:** the **default methods are a trap** — `destroy()` throws `DestroyFailedException`, `isDestroyed()`
  returns false (§1.1 [2]). Inheriting the defaults (as `SecretKeySpec` does) is worse than nothing: it
  *looks* destroyable and isn't.
- **Verdict:** **adopt the interface, override both methods for real.** `RootKey implements Destroyable`
  with a `destroy()` that actually `Arrays.fill(material, (byte)0)` and a truthful `isDestroyed()`. The
  smoke test confirms the backing array is genuinely zeroed and use-after-wipe throws.

### 2.3 A sealed/opaque `WrappedKey` type — the type distinction *is* the safety property

- **Pro:** makes "sealed ciphertext, safe to persist/log" a **different type** from "live key, must wipe."
  The compiler then prevents the §1.2 confusions: you cannot pass a `RootKey` to a persist call, and a
  `WrappedKey` carries its own `KeyId`. This is the single highest-leverage choice — it converts a class of
  runtime leaks into compile errors. Mirrors Vault's `BlobInfo`, K8s' prefixed ciphertext, Cockroach's
  per-file `key_id` ([`prior-art.md`](prior-art.md) §1–§4).
- **Con:** one more type; a `byte[]` field inside still needs a redacted `toString()` and defensive copies
  (records default to array *identity* equals — must override).
- **Verdict:** **adopt.** `WrappedKey(KeyId, byte[] ciphertext, Map context)` — `byte[]` is acceptable *here*
  because it is ciphertext, not live material; it still gets a length-only `toString()` and copy-in/copy-out.

### 2.4 `AutoCloseable` handles + try-with-resources — structural "wipe after use"

- **Pro:** scopes the lifetime of live material to a lexical block, so the wipe is a property of the code's
  *shape*, not a thing each implementer must remember:
  `try (RootKey k = provider.unwrap(w)) { derive(k); }` wipes `k` on every exit path including exceptions.
- **Con:** only as good as the discipline of always using try-with-resources (a lint/review item); a handle
  stashed in a field outlives the block by design (the node-lifetime root key is exactly this — held
  deliberately, wiped at shutdown).
- **Verdict:** **adopt.** `RootKey implements AutoCloseable` (delegating to `destroy()`), so the *common*
  case (derive-then-discard) is structurally safe and the *deliberate* case (hold for node lifetime) is an
  explicit, visible exception.

### 2.5 Sealed interfaces / records (modern Java) — closed, type-checked carriers

- **Pro:** **records** give immutable, value-semantic carriers for `KeyId`/`WrappedKey`/`Provisioned` with
  minimal ceremony. **Sealing** a hierarchy makes the wrapped-vs-live set closed and exhaustively
  switchable.
- **Con — a sharp one for an SPI:** **do not seal the `KmsProvider` interface itself.** The whole point is
  *"extensible to any KMS without forking the core"* — a sealed provider interface would forbid third-party
  implementations. Sealing is right for *closed data* (the carriers), wrong for the *open extension point*.
- **Verdict:** **records for the carriers; the provider interface stays open (unsealed).** `RootKey` is a
  final class (not a record) because it has mutable wipe state and identity semantics, which records model
  poorly.

### 2.6 Off-heap / `MemorySegment` (FFM API) for the live key — avoid heap-dump exposure

- **Pro:** key bytes in a native `MemorySegment` (allocated from an `Arena`) are **not on the Java heap**, so
  they don't appear in heap dumps and aren't moved/copied by a GC. `configd-common` already depends on
  `org.agrona` (off-heap buffers), so the capability is in-tree.
- **Con:** real complexity, and a subtle footgun — **`Arena.close()` frees but does not zero** the segment;
  you must `segment.fill((byte)0)` *before* close to actually wipe (the same explicit-wipe discipline as the
  heap path, just off-heap). It also doesn't help the unavoidable moment the key crosses into a JCA
  `SecretKey` (on-heap) for the `Cipher`. For a **boot-only, node-lifetime, single** root key (not a
  high-churn pool), the heap-dump exposure window is small and already mitigated by wiping on close.
- **Verdict:** **over-engineering for v2's first step — document as a future hardening, not v1 of the SPI.**
  The recommended `RootKey` keeps an on-heap `byte[]` it wipes; the design note records that its internals
  could move to an `Arena`-backed `MemorySegment` later **with no API change** (the `byte[]` is private), so
  this is a reversible, deferrable decision — exactly where it should sit.

### 2.7 Tink-style token-gated access (`SecretKeyAccess`) — scoped, conspicuous raw access

- **Pro:** Google Tink gates raw key bytes behind a `SecretKeyAccess` token so that obtaining the plaintext
  is **explicit and greppable** (`InsecureSecretKeyAccess.get()`), never accidental
  ([`prior-art.md`](prior-art.md) §5). The principle — *raw access is possible but deliberately scoped and
  conspicuous at the call site* — is exactly right.
- **Con:** Tink's full framework is a large dependency and a different key-management model than a tiny boot
  SPI needs.
- **Verdict:** **adopt the principle, not the dependency.** `RootKey` exposes no raw getter; the one path to
  the bytes is `withMaterial(Function)` — a scoped, conspicuous call that hands the consumer a **clone**
  (the live backing array never escapes), which the consumer wipes. This is the lightweight analogue of
  token-gated access.

### 2.8 `javax.crypto.KDF` (JEP 478) — relevant to the derivation, not the key type

- **Finding (verified on JDK 25):** `javax.crypto.KDF` is **present and non-preview** — `KDF.getInstance(
  "HKDF-SHA256")` compiles and runs **without `--enable-preview`** (provider `SunJCE`). The JDK now ships
  HKDF natively; Configd's hand-rolled `io.configd.common.Hkdf` predates it.
- **Relevance:** marginal to *key-material types* (KDF operates on `SecretKey`s, which is why our
  `toSecretKey` bridge exists), but worth flagging for whoever builds the derivation: the `local` provider
  and the per-segment DEK derivation **could** use `javax.crypto.KDF` instead of the bespoke `Hkdf`. A note
  for the encryption-layer build, not a type decision.

---

## 3. The recommended concrete types

Realised and compile-checked in [`sketch/io/configd/kms/`](sketch/io/configd/kms/); behaviourally validated
by [`sketch/SketchSmokeTest.java`](sketch/SketchSmokeTest.java) (13/13 design-contract checks pass on JDK
25).

### `RootKey` — the live handle  (patterns 2.1-bridge + 2.2 + 2.4 + 2.7)

```java
public final class RootKey implements AutoCloseable, Destroyable {
    private final byte[] material;       // owned; wiped on close()
    private final KeyId  keyId;          // non-secret identity (loggable)
    public  int      length();           // non-secret
    public  SecretKey toSecretKey(String algorithm);     // transient JCA bridge — DO NOT retain
    public  <R> R    withMaterial(Function<byte[],R> use);// scoped, clone-handing raw access
    @Override public void    destroy();  // Arrays.fill(material,0); idempotent; never throws
    @Override public boolean isDestroyed();
    @Override public void    close();    // = destroy() → try-with-resources scopes the lifetime
    @Override public String  toString(); // redacted: keyId + destroyed flag, NEVER the bytes
}
```

Why: it is the one place live key material exists, so it is the one type that is `Destroyable` (for real,
§2.2), `AutoCloseable` (§2.4), redacted (§1.3), and offers only scoped raw access (§2.7). It is **not** a
`SecretKeySpec` (§1.1) but **bridges** to one transiently (§2.1).

### `WrappedKey` — the opaque sealed carrier  (pattern 2.3 + 2.5)

```java
public record WrappedKey(KeyId keyId, byte[] ciphertext, Map<String,String> context) {
    // copy-in/out defensive copies; structural equals/hashCode; toString shows ciphertext LENGTH only
}
```

Why: the **type distinction from `RootKey` is the safety property** (§2.3) — persistable, loggable,
never-wiped ciphertext is a different type from a live key. `byte[]` is acceptable here (it's ciphertext);
it still gets redaction + defensive copies.

### `KeyId` — non-secret identity + version  (pattern 2.5)

```java
public record KeyId(String providerType, String reference, int version) { /* loggable toString */ }
```

Why: makes every `WrappedKey` self-describing for zero-coordination selection on read and carries the
keyring **term** for O(1) rotation (§1.4; the Vault/Cockroach/K8s self-describing-key lesson).

### The shape, at a glance

```
   KeyId          (record, non-secret)            ── safe to persist + log
   WrappedKey     (record, ciphertext + KeyId)    ── safe to persist + log (redacted)
   RootKey        (final, Destroyable+AutoCloseable) ── never persist, never log, wiped on close
```

---

## 4. The honest residual (the boundary, stated)

- **The JCA bridge can't be wiped.** `toSecretKey()` returns a `SecretKeySpec` whose bytes survive
  `destroy()` (§1.1). We minimise the window (transient, not retained) and keep the authoritative copy in
  `RootKey` (wiped), but the moment a key is handed to a `Cipher`/`KDF` there is a JVM-resident copy we
  cannot deterministically zero. This is a **JVM/JCA limitation (JDK-8160206), not a design choice** — named,
  not hidden. It is also why the encryption layer should prefer deriving **short-lived per-segment DEKs**
  over using the root key directly.
- **GC copying still applies to any on-heap `byte[]`** between writes and the wipe; off-heap (§2.6) would
  shrink this window but is deferred as over-engineering for a boot-only key, recoverable later with no API
  change.
- **`destroy()` is best-effort against a live-process adversary.** Wiping defends heap/core dumps and swap of
  a *stopped or crashed* process; it does not defend an attacker reading a *running* node's RAM — the same
  threat boundary every system in [`prior-art.md`](prior-art.md) draws, and the one the encryption research
  draws too. The types raise the floor; they do not change the threat model.

**Net:** the recommended types make the wrapped-vs-live distinction a **compile-time** property, make
"wipe after use" **structural**, and make leakage-via-`toString` **impossible by construction** — the
achievable security properties on the JVM — while being honest about the one residual (the un-wipeable JCA
bridge) that no JVM key type can currently close.
