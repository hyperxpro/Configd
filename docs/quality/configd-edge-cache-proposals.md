# configd-edge-cache — idiomatic-Java quality proposals (REVIEW-ONLY, DO NOT APPLY)

Pre-EC2-measurement quality pass. **Zero edits were applied to this module.** Every one of the
13 main source files is named in the §2 NO-TOUCH list — this module *is* the measured edge
read / cache / delta / staleness hot path plus the poison-pill decision logic and the frozen
metric series. Each item below is a **proposal only**; applying any of it before the paid EC2
measurement risks moving the measured read floor or the allocation profile of the delta path and
must be done (if at all) as a separate, oracle-verified change after the measurement.

Files reviewed (all §2): `EdgeClientCore`, `LocalConfigStore`, `DeltaApplier`, `VersionCursor`,
`BloomFilter`, `StalenessTracker`, `StrongReadKeyClass`, `PrefixStorageFilter`,
`PrefixSubscription`, `EdgeConfigClient`, `EdgeMetrics`, `PoisonPillDetector`, `PoisonPillPolicy`.

Overall verdict: the module is exemplary, idiomatic Java 25 (pattern-matching `switch`, sealed
interfaces, validating record compact-constructors, RCU single-writer discipline, lock-free
volatile-pointer reads, deliberate lazy allocation). The findings below are micro-nits, not
defects. The headline item (P1) is a genuine gratuitous allocation on the delta-apply path.

---

## P1 — `PrefixStorageFilter.filter()` allocates a set snapshot just to test emptiness (delta path)

- **File:** `src/main/java/io/configd/edge/PrefixStorageFilter.java:74`
- **Current:** `if (subscriptions.prefixes().isEmpty()) { return delta; }`
- **Proposed:** `if (subscriptions.isEmpty()) { return delta; }`
- **Rationale:** `PrefixSubscription.prefixes()` returns
  `Collections.unmodifiableSet(new LinkedHashSet<>(prefixes))` — it **allocates a full
  LinkedHashSet snapshot** of the subscribed prefixes on every call. Here that snapshot is built
  only to call `.isEmpty()` on it and is then discarded. `PrefixSubscription` already exposes a
  lock-free, allocation-free `isEmpty()` (line 79–81) whose javadoc says verbatim *"Lock-free and
  allocation-free, unlike `prefixes()` (which snapshots)."* — it exists for exactly this caller.
  `filter()` is invoked on the single-writer **delta-apply path** (`EdgeConfigClient.applyDelta`
  and `EdgeClientCore.applyNotification`'s read-store mirror), so this snapshot is allocated per
  applied delta on the full-store (empty-subscription) common path.
- **Behavior-identity:** Exact. Both forms test whether the backing `CopyOnWriteArraySet` is empty;
  `prefixes().isEmpty()` merely snapshots first. The full-store branch still `return delta;`
  unchanged, so `EdgePrefixStorageFilterTest.filterForStorageReturnsOriginalWhenNothingDropped`
  (`assertSame(original, …)`, line 228) stays green. That test already documents the *intent* that
  the full-store path is allocation-free; this proposal removes the one allocation that currently
  contradicts that intent.
- **Why propose, not apply:** `PrefixStorageFilter` and `PrefixSubscription` are both §2. The change
  *reduces* the allocation profile of a measured path — which is still a change to the allocation
  profile that the EC2 measurement / a divergence-analyst's allocation oracle must observe and
  bless. Apply it as a post-measurement, allocation-oracle-verified commit.
- **Priority:** High (only genuine allocation win found; on a measured path).

---

## P2 — `EdgeConfigClient.metrics()` snapshots the prefix set just to read its size (monitoring path)

- **File:** `src/main/java/io/configd/edge/EdgeConfigClient.java:308`
- **Current:** `subscriptions.prefixes().size()`
- **Proposed (needs an SPI addition):** add a lock-free `int size()` to `PrefixSubscription`
  (mirroring its existing `isEmpty()`), then call `subscriptions.size()`.
- **Rationale:** Same gratuitous LinkedHashSet snapshot as P1, here only to read `.size()`.
- **Behavior-identity:** Exact for the count. **But** it requires *adding a public method* to
  `PrefixSubscription` — an API change, which the brief forbids in this pass — and `metrics()` is the
  cold monitoring/snapshot path, **not** the read floor, so the win is negligible.
- **Why propose, not apply:** API addition + §2 type + cold path. Low value.
- **Priority:** Low.

---

## P3 — `StalenessTracker.stalenessMs()` ternary clamp → `Math.max` (readability only)

- **File:** `src/main/java/io/configd/edge/StalenessTracker.java:319`
- **Current:** `return staleMs < 0 ? 0 : staleMs;`
- **Proposed:** `return Math.max(0L, staleMs);`
- **Rationale:** `Math.max(0L, …)` reads as "clamp to non-negative" more directly; the JIT lowers
  both to the same branchless form.
- **Behavior-identity:** Exact (both yield `0` for negative, `staleMs` otherwise; no NaN concerns —
  these are `long`s).
- **Why propose, not apply:** `stalenessMs()` is the staleness *measurement* read by `currentState()`,
  `isStale()`, and `EdgeMetrics` — a decision input on a §2 type. Readability-only; not worth the
  measurement risk now. (`EdgeClientCore.refreshCursorLag` line 668 and `onSubscribeOk` line 410 use
  the same `Math.max` idiom already, so adopting it here is consistent, not novel.)
- **Priority:** Very low (cosmetic).

---

## P4 — `StrongReadKeyClass` constructor: redundant intermediate `LinkedHashSet` (cold construction)

- **File:** `src/main/java/io/configd/edge/StrongReadKeyClass.java:63–72`
- **Observation:** the ctor copies the input into a `LinkedHashSet copy`, then returns
  `Set.copyOf(copy)`. `Set.copyOf` does **not** preserve iteration order, so the `LinkedHashSet`'s
  ordering is not actually retained — the intermediate set is a redundant allocation. (The loop's
  per-element null/blank validation is the load-bearing part and must stay.) One could validate while
  iterating the input directly and copy once.
- **Behavior-identity:** Order is irrelevant for a prefix-membership predicate (`isStrongReadKey`
  short-circuits on first match), so either form is behavior-equivalent. The validation order/messages
  must be preserved exactly.
- **Why propose, not apply:** Construction-only (cold), §2 type, and the gain is one short-lived
  allocation at startup. Not worth touching a strong-read/identity class before measurement.
- **Priority:** Very low.

---

## P5 — `PrefixSubscription.matchingPrefixes()` inline-return + FQN nit (cold / possibly unused)

- **File:** `src/main/java/io/configd/edge/PrefixSubscription.java:104–110`, and `:92`
- **Observations:**
  - `:106–109` assigns `Set<String> result = …stream().collect(toUnmodifiableSet()); return result;`
    — the local is immediately returned and could be inlined. `matchingPrefixes` has **no caller in
    this module's main sources** (the hot filter path uses `matches`); it is public API, so it may be
    used by other modules/tests — do not remove.
  - `:92` uses the fully-qualified `new java.util.LinkedHashSet<>(prefixes)` while the rest of the
    file imports collection types normally; an import + simple name would be more consistent.
- **Why propose, not apply:** Both are pure style; the FQN cleanup is formatting churn (the brief
  forbids formatting-only edits), and `matchingPrefixes` is a §2-type public method off the hot path.
- **Priority:** Very low (cosmetic).

---

## Traps — idioms that LOOK safe here but are NOT byte-identical (do **not** "fix" these)

Recorded so a future pass does not mistake them for free wins:

- **`requireNonNull` does not replace the manual null checks in the record compact constructors.**
  `VersionCursor` (`:18–25`), `EdgeMetrics` (`:24–40`), and `ConnectionDirective.ReconnectNextEndpoint`
  (`EdgeClientCore:118–124`) throw **`IllegalArgumentException`** on bad input, including some null
  checks. Swapping a null check to `Objects.requireNonNull` changes the thrown type to
  **`NullPointerException`** — an observable contract change that the validation tests assert. Leave
  them as-is. (Where the code already *wants* NPE — e.g. `StrongReadKeyClass`/`PrefixStorageFilter`
  ctors — it already uses `requireNonNull`; the distinction is intentional.)

- **`DeltaApplier.buildVerificationPayload()` (`:297–303`) is a one-line wrapper around
  `delta.signingPayload()` — keep it.** It is a deliberate documentation anchor for the
  canonical-batch-form / byte-identical sign-vs-verify contract on the security path. Inlining it
  saves nothing and erases the rationale comment. Considered and rejected.

- **`DeltaApplier.persistEpoch()` (`:382–409`) leaves a `.tmp` file on a `move` failure** (other than
  `AtomicMoveNotSupportedException`). Adding `finally { Files.deleteIfExists(tmp); }` *changes
  filesystem side effects* on the cold epoch-persistence path — a divergence-analyst flags any FS
  behavior change. The leftover tmp is harmless (overwritten on the next epoch advance) and arguably
  intentional. If ever cleaned up, do it as a deliberate robustness change with its own test, not as a
  "quality nit."

- **`LocalConfigStore` `@SuppressWarnings("FieldMayBeFinal")` on `currentSnapshot` (`:47`) is
  correct** — the field is the RCU publication point written by `applyDelta`/`loadSnapshot`; it cannot
  be `final`. Do not "tidy" the suppression away.

---

## Context — dormant code (informational; do NOT remove in this pass)

- **`BloomFilter` is not referenced by any other main source in this module** (verified by grep). The
  v1 production-readiness register already flags it as dormant/never-wired (the `@see LocalConfigStore`
  is aspirational — the read path traverses the HAMT directly and does not consult a Bloom filter). It
  is nonetheless §2-listed and pinned by `BloomFilterTest` + `BloomFilterPropertyTest` and carries an
  immutability/FPR contract. **Do not remove it in a byte-identity pass** — dead-code removal of a
  tested, register-tracked class is a scope/posture decision for a separate change, not a quality nit.

---

## Verification performed for this pass

- `./mvnw -q -pl configd-edge-cache -am test-compile` — compile of the module + upstream deps.
- No source edits applied, so no module test run was required; the named behavioral oracle for the
  delta/storage path is `EdgePrefixStorageFilterTest` (and the read/staleness oracles
  `LocalConfigStoreTest`, `StalenessTrackerTest`, `StalenessSkewTripwireTest`,
  `BootstrapCutoverExactnessTest`). Note: there is **no** strict `getThreadAllocatedBytes` allocation
  oracle inside `configd-edge-cache/src/test` — the measured `<1 B/op` read floor is asserted by an
  out-of-module benchmark, which is precisely why P1/P2 are propose-only.
