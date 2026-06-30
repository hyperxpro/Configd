# configd-config-store — Idiomatic-Java Quality Proposals (REVIEW & PROPOSE ONLY)

**Pass type:** conservative byte-identity / behavior-preserving quality review (pre-EC2-measurement).
**Outcome:** **0 edits applied.** Every `src/main` source file in this module is on the §2
no-touch list (zero-alloc read/hot path, byte-identical apply/snapshot/delta, wire codec, crypto
signing/key-custody, or write-accept decision logic). There is **no non-§2 source file** here, so
nothing qualifies for in-place application under the brief's EDITABLE rules. The items below are
therefore **proposals only** — none were applied. Each notes *why* it is §2 and how a reviewer
should treat it.

Scope reviewed: all 16 files under `configd-config-store/src/main/java/io/configd/store/`.
Tests were not modified (no assertion was weakened; none was plainly wrong).

Classification legend:
- **[BP]** behavior-preserving / byte-identical if applied (could be landed by a §2-cleared change).
- **[BC]** behavior-changing — must NOT be folded into a byte-identity pass; needs its own review.
- **[COSMETIC]** no byte/behavior impact (imports, comments, code shape) — pure readability.

---

## 1. SigningKeyStore.java — dead no-op statement (latent test-helper defect)

**File/line:** `SigningKeyStore.java:170`, inside the package-private test helper `writeForTest(...)`.

```java
PosixFilePermissions.fromString("rw-------");
```

**Observation:** the return value of `PosixFilePermissions.fromString(...)` is computed and
**discarded** — this is a no-op statement. The surrounding intent (mirroring `generateAndWrite`,
which correctly calls `Files.setPosixFilePermissions(path, …)` at lines 142–147) is clearly to chmod
the written file to `0600`. As written, `writeForTest` leaves the file at the default umask.

**Evidence / current blast radius:** `writeForTest` has **no callers anywhere in the repo**
(`grep -rn writeForTest` → declaration only), so the defect is currently **dormant** — it cannot
mis-set permissions on a test artifact today because nothing invokes it. It becomes a real
test-hygiene bug the moment a future test wires it up.

**Why not applied:** `SigningKeyStore.java` is an explicit §2 file (crypto / signing-key custody).
Even though this line is in test-support code and off the signature byte path, the module charter
lists the whole file as no-touch, and the conservative rule is propose-not-apply.

**Two distinct proposals (do not conflate):**
- **(1a) [BP]** Remove the dead no-op line. Byte-identical: the statement does nothing today, and
  the helper has no callers, so deletion changes no observable behavior.
- **(1b) [BC]** *Fix* the latent bug by wiring it up:
  `Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));`. This
  **changes behavior** (it would start enforcing `0600` on the written file) and must be reviewed
  on its own merits — it is NOT part of a byte-identity pass.

Recommendation: prefer (1b) as a small standalone correctness PR (it makes the test helper match the
production path's security posture), or (1a) if the helper is to be removed entirely (see §2).

---

## 2. SigningKeyStore.java — unused public method `format(UUID)`

**File/line:** `SigningKeyStore.java:173–176`.

```java
/** Utility: format a UUID without dashes — unused currently but handy. */
public static String format(UUID id) {
    return id.toString().replace("-", "");
}
```

**Observation:** dead code. The javadoc itself states "unused currently." Confirmed: zero callers
repo-wide (`grep -rn SigningKeyStore.format` → none). Likewise `writeForTest` (§1) has no callers —
both are unreferenced helpers in this file.

**Why not applied:** §2 crypto file; additionally `format` is `public`, so removing it is technically
a (narrow) public-API change, which the brief forbids in this pass.

**Proposal [BP-if-removed]:** remove `format(UUID)` (and consider removing the unused `writeForTest`
helper) in a dedicated dead-code-cleanup PR. Removal is behavior-preserving (no callers), but because
it touches a §2 crypto file and a `public` member, it should be a deliberate, separately-reviewed
change rather than a drive-by edit.

---

## 3. DeltaComputer.java — collapsible duplicated `Put` emission

**File/line:** `DeltaComputer.java:48–54`, inside `compute(...)`.

```java
if (fromVal == null) {
    // New key
    mutations.add(new ConfigMutation.Put(key, toVal.valueUnsafe()));
} else if (!Arrays.equals(fromVal.valueUnsafe(), toVal.valueUnsafe())) {
    // Changed value
    mutations.add(new ConfigMutation.Put(key, toVal.valueUnsafe()));
}
```

**Observation:** both branches emit the **identical** statement
`mutations.add(new ConfigMutation.Put(key, toVal.valueUnsafe()))`. The two arms can be collapsed
into one condition:

```java
if (fromVal == null || !Arrays.equals(fromVal.valueUnsafe(), toVal.valueUnsafe())) {
    mutations.add(new ConfigMutation.Put(key, toVal.valueUnsafe()));
}
```

This is **[BP]** — the produced mutation set and its ordering are unchanged (short-circuit `||`
preserves the exact null-then-equals evaluation order, so `valueUnsafe()`/`Arrays.equals` are
invoked on precisely the same inputs). The inline `// New key` / `// Changed value` comments would
be folded into a single comment.

**Why not applied:** `DeltaComputer.java` is an explicit §2 file (byte-identical delta). The output
of `compute` feeds `ConfigDelta` → `CommandCodec.encodeBatch` → the **signed** delta payload, so any
re-ordering of `mutations` would change signed bytes. The collapse above does *not* reorder, but the
prudent action under a pre-measurement freeze is to propose, not apply. A divergence-analyst should
confirm the `forEach` visitation order and the mutation append order are untouched before landing.

---

## 4. DeltaComputer.java — presizable `HashSet` (warm-path GC hypothesis)

**File/line:** `DeltaComputer.java:42`.

```java
Set<String> toKeys = new HashSet<>();
```

**Observation:** the eventual size is known up front (it equals `to.size()`), so the set could be
presized to avoid incremental rehashing on large target snapshots:
`new HashSet<>(Math.max(16, (int) (to.size() / 0.75f) + 1))`.

**Why not applied / classification:** this is a **perf hypothesis**, not a guaranteed win — it needs
a benchmark to confirm it helps and that the presize math is right, which the brief says makes it a
PROPOSE (not a safe nit). It is also a §2 file. Delta computation is a warm path (delta propagation
/ bulk sync), not the asserted zero-alloc read floor, so the upside is modest. Defer unless a delta
micro-benchmark shows rehash pressure.

---

## 5. ConfigValidator.java — fully-qualified `java.util.List`

**File/line:** `ConfigValidator.java:99`.

```java
public ValidationResult validateAll(java.util.List<ConfigMutation> mutations) {
```

**Observation:** `List` is referenced by fully-qualified name because the file imports
`java.util.Map` and `java.util.Objects` but not `java.util.List`. Adding `import java.util.List;`
and using the simple name would match the idiom used elsewhere in the module.

**Why not applied / classification:** **[COSMETIC]** (imports never affect emitted bytecode
semantics). However `ConfigValidator.java` is an explicit §2 file (write-accept/reject decision
logic), so it is propose-only. Pure readability; zero behavior impact.

---

## 6. ConfigSigner.java — `java.security.*` wildcard import (LOW VALUE / OPTIONAL)

**File/line:** `ConfigSigner.java:3` — `import java.security.*;`.

**Observation:** this is the only wildcard import in `configd-config-store/src/main`; the rest of the
module uses explicit imports. Expanding it to the specific types
(`KeyPair, PrivateKey, PublicKey, Signature, SignatureException, GeneralSecurityException`) would be
locally more consistent.

**Why this is LOW VALUE:** wildcard imports are **not** banned by the codebase — there are 26 of
them across `src/main` repo-wide — so this is a tolerated style, not a convention violation. Combined
with the file being §2 crypto, there is little reason to touch it. **[COSMETIC]**, optional; listed
only for completeness. Recommend leaving as-is.

---

## 7. ConfigStateMachine.java — `secureRandom` comment says "lazy" but the field is eager

**File/line:** `ConfigStateMachine.java:99–100`.

```java
/** Secure random source for nonces (lazy; costs nothing when unsigned). */
private final SecureRandom secureRandom = new SecureRandom();
```

**Observation:** the field is an **eager** initializer — `new SecureRandom()` is allocated and seeded
at construction for every state machine, signed or not. The word "lazy" mis-describes it. The
"costs nothing when unsigned" half is defensible if read as *the entropy draw* (`nextBytes`) only
happens inside `signCommand` when `signer != null`; the allocation/seed itself is unconditional.

**Why not applied / classification:** `ConfigStateMachine.java` is the core §2 consensus apply path.
Two options, both propose-only:
- **[COSMETIC]** Clarify the comment, e.g. "eagerly constructed; only the per-sign `nextBytes` draw
  is conditional on a configured signer." (No code change.)
- **[BC]** Genuinely lazy-init the field. **Not recommended** — it would introduce a
  null-check/visibility concern into the single-writer apply path for negligible benefit, changing
  construction-time behavior in a consensus-critical class. Reject for this pass.

Recommendation: at most the comment clarification, and only as part of an unrelated change to this
file — not on its own during a freeze.

---

## Files reviewed with NO findings (reference-quality §2 code, hands-off)

- **HamtMap.java** — persistent zero-alloc trie; `@SuppressWarnings` are necessary for the
  Object[]-array generics pattern; the unused `hash`/`shift` params in `CollisionNode.get` are
  required by the `Node` interface contract. Nothing to change.
- **VersionedConfigStore.java** — asserted zero-alloc read path (`VersionedConfigStoreAllocationTest`).
  Pristine; single-volatile-read consistency in `getInto`/`getPrefixVersioned` is deliberate.
- **CommandCodec.java** — wire codec pinned by `CommandCodecPropertyTest`; clean.
- **ConfigDelta.java** — defensive copies, canonical `signingPayload()`; byte-exact by design.
- **ConfigMutation.java / VersionedValue.java / ReadResult.java / ConfigSnapshot.java** — immutable
  value types with correct `equals`/`hashCode`; the `@Deprecated(forRemoval=true)` `foundReusable`
  on `ReadResult` is intentional API and left as-is (removal would be an API change).
- **Compactor.java** — the double `history.size() > retentionCount` check in `compact()` is an
  intentional concurrency guard, not redundancy. Left as-is.
- **StateMachineMetrics.java / VerifyKeyExporter.java** — clean; `VerifyKeyExporter.main`'s
  catch-all-and-exit is idiomatic for a CLI entry point.

---

## Summary

| # | File:line | Item | Class | Applied? |
|---|-----------|------|-------|----------|
| 1 | SigningKeyStore.java:170 | dead no-op `fromString` (latent missing chmod) | [BP] remove / [BC] fix | No (§2 crypto) |
| 2 | SigningKeyStore.java:173 | unused public `format(UUID)` (+ unused `writeForTest`) | [BP] remove | No (§2 crypto, public) |
| 3 | DeltaComputer.java:48 | collapse duplicated `Put` branches | [BP] | No (§2 delta) |
| 4 | DeltaComputer.java:42 | presize `HashSet` | perf hypothesis | No (§2, needs bench) |
| 5 | ConfigValidator.java:99 | import `java.util.List` | [COSMETIC] | No (§2 decision) |
| 6 | ConfigSigner.java:3 | wildcard import (tolerated repo-wide) | [COSMETIC] optional | No (§2 crypto) |
| 7 | ConfigStateMachine.java:99 | "lazy" comment vs eager field | [COSMETIC] | No (§2 consensus) |

Highest-signal item is **#1** (a genuine, if dormant, latent defect in a test helper) — best handled
as a small standalone correctness PR outside this byte-identity pass. Everything else is cosmetic or
a deferred perf hypothesis.
